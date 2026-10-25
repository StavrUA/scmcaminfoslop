package ua.stavrua.advancedcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

class Camera2Controller(
    context: Context,
    private val onState: (CameraUiState) -> Unit,
    private val locationProvider: () -> Location?,
    private val orientationProvider: () -> FloatArray?,
    private val overlayConfigProvider: () -> OverlayConfig,
    private val ambientTemperatureProvider: () -> Float?,
    private val displayRotationProvider: () -> Int,
) : SurfaceHolder.Callback {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(appContext.mainLooper)
    private val thread = HandlerThread("Camera2Controller").apply { start() }
    private val cameraHandler = Handler(thread.looper)
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }
    private var state = CameraUiState()
    private var cameraOptions = emptyList<CameraOption>()
    private var previewSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: android.media.ImageReader? = null
    private var rawReader: android.media.ImageReader? = null
    private var rawEnabled = false
    private var capturePending = false
    private var pendingRaw: android.media.Image? = null
    private var pendingJpeg: android.media.Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var pendingMetadata: CaptureMetadata? = null
    private var pendingCamera: CameraOption? = null
    private var pendingSettings: CameraSettings? = null
    private var pendingFormat: FileFormat? = null
    private var pendingNeedsRaw = false
    private var pendingImageOrientation = 0
    private var captureGeneration = 0
    private var captureCountdownTask: Runnable? = null
    private var captureScheduled = false
    private var closed = false

    fun refreshCameras() {
        try {
            val options = mutableListOf<CameraOption>()
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                options += makeOption(id, null, characteristics)
                characteristics.physicalCameraIds.forEach { physicalId ->
                    runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                        .onSuccess { physical -> options += makeOption(id, physicalId, physical) }
                }
            }
            cameraOptions = options.distinctBy { optionKey(it) }
            val first = cameraOptions.firstOrNull()
            updateState { old ->
                val selected = old.selectedCameraId?.takeIf { key -> cameraOptions.any { optionKey(it) == key } }
                    ?: first?.let(::optionKey)
                old.copy(cameras = cameraOptions, selectedCameraId = selected, error = null)
            }
            if (previewSurface != null) startSelectedCamera()
        } catch (exception: CameraAccessException) {
            showError("Could not list cameras: ${exception.localizedMessage}")
        } catch (exception: SecurityException) {
            showError("Camera permission is required.")
        }
    }

    private fun makeOption(id: String, physicalId: String?, characteristics: CameraCharacteristics): CameraOption {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            .sortedByDescending { it.width.toLong() * it.height }
        val rawSizes = if (
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        ) {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
                .sortedByDescending { it.width.toLong() * it.height }
        } else emptyList()
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val name = if (physicalId == null) {
            "Camera $id"
        } else {
            val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val prefix = when {
                focal != null && focal < 3f -> "Ultrawide"
                focal != null && focal > 6f -> "Telephoto"
                facing == CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                else -> "Physical"
            }
            "$prefix $physicalId"
        }
        return CameraOption(id, physicalId, name, facing, characteristics, jpegSizes, rawSizes)
    }

    fun selectCamera(key: String) {
        if (state.selectedCameraId == key || state.isCapturing) return
        val nextCamera = cameraOptions.firstOrNull { optionKey(it) == key }
        val prior = state.settings
        val compatibleSettings = if (nextCamera == null) prior else prior.copy(
            selectedSize = null,
            zoom = prior.zoom.coerceIn(1f, nextCamera.maxDigitalZoom),
            flashMode = if (nextCamera.flashAvailable) prior.flashMode else FlashMode.OFF,
            format = if (prior.format == FileFormat.DNG || prior.format == FileFormat.RAW_JPEG) {
                if (nextCamera.supportsRaw) prior.format else FileFormat.JPEG
            } else prior.format,
            manualExposure = prior.manualExposure && nextCamera.manualSensor,
            manualFocus = prior.manualFocus && nextCamera.manualFocus,
            whiteBalance = when (prior.whiteBalance) {
                WhiteBalance.AUTO -> WhiteBalance.AUTO
                WhiteBalance.CUSTOM -> if (nextCamera.supportsCustomWhiteBalance) prior.whiteBalance else WhiteBalance.AUTO
                else -> {
                    val mode = when (prior.whiteBalance) {
                        WhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                        WhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                        WhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                        WhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                        else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                    }
                    if (mode in nextCamera.availableWhiteBalanceModes) prior.whiteBalance else WhiteBalance.AUTO
                }
            },
        )
        updateState { it.copy(selectedCameraId = key, settings = compatibleSettings, isReady = false, error = null) }
        startSelectedCamera()
    }

    fun updateSettings(settings: CameraSettings) {
        val oldSettings = state.settings
        val option = selectedOption()
        val available = option?.sizesFor(settings.aspectRatio).orEmpty()
        val selectedSize = settings.selectedSize?.takeIf { it in available } ?: available.firstOrNull()
        val flashMode = if (option?.flashAvailable == true && !settings.manualExposure) settings.flashMode else FlashMode.OFF
        val updatedSettings = settings.copy(
            selectedSize = selectedSize,
            zoom = settings.zoom.coerceIn(1f, option?.maxDigitalZoom ?: 1f),
            flashMode = flashMode,
        )
        updateState { it.copy(settings = updatedSettings) }
        cameraHandler.post { updateRepeatingRequest() }
        if (updatedSettings.selectedSize != oldSettings.selectedSize ||
            updatedSettings.aspectRatio != oldSettings.aspectRatio
        ) restartSession()
    }

    fun setZoom(zoom: Float) {
        updateSettings(state.settings.copy(zoom = zoom))
    }

    fun tapToFocus(x: Float, y: Float, width: Int, height: Int) {
        val option = selectedOption() ?: return
        val chars = option.characteristics
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAf == 0 && maxAe == 0) {
            updateState { it.copy(notice = "Tap-to-focus/metering is not supported by this camera.") }
            return
        }
        val nx = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val ny = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val crop = zoomCropRegion(active, state.settings.zoom)
        val sensorX = crop.left + (crop.width() * nx).toInt()
        val sensorY = crop.top + (crop.height() * ny).toInt()
        val half = (crop.width() * 0.06f).toInt().coerceAtLeast(20)
        val rect = Rect(
            (sensorX - half).coerceAtLeast(crop.left),
            (sensorY - half).coerceAtLeast(crop.top),
            (sensorX + half).coerceAtMost(crop.right),
            (sensorY + half).coerceAtMost(crop.bottom),
        )
        cameraHandler.post {
            try {
                val device = cameraDevice ?: return@post
                val session = captureSession ?: return@post
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    if (maxAf > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
                    if (maxAe > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        updateState { it.copy(notice = "Focus point set") }
                        cameraHandler.postDelayed({ updateRepeatingRequest() }, 600)
                    }
                }, cameraHandler)
            } catch (exception: CameraAccessException) {
                showError("Tap-to-focus failed: ${exception.localizedMessage}")
            }
        }
    }

    fun capture() {
        if (!state.isReady || state.isCapturing || captureScheduled) return
        val delaySeconds = state.settings.captureTimerSeconds.coerceIn(0, 10)
        if (delaySeconds > 0) {
            captureScheduled = true
            updateState { it.copy(isCapturing = true, error = null, notice = "Photo in $delaySeconds…") }
            cameraHandler.post {
                var remaining = delaySeconds
                lateinit var task: Runnable
                task = Runnable {
                    if (closed || !captureScheduled) return@Runnable
                    remaining--
                    if (remaining <= 0) {
                        captureCountdownTask = null
                        captureScheduled = false
                        updateState { it.copy(isCapturing = false, notice = null) }
                        captureNow()
                    } else {
                        updateState { it.copy(notice = "Photo in $remaining…") }
                        cameraHandler.postDelayed(task, 1_000)
                    }
                }
                captureCountdownTask = task
                cameraHandler.postDelayed(task, 1_000)
            }
            return
        }
        captureNow()
    }

    private fun captureNow() {
        if (!state.isReady) {
            captureScheduled = false
            updateState { it.copy(isCapturing = false, error = "Camera is not ready.") }
            return
        }
        val option = selectedOption()
        if (option == null) {
            showError("No camera is selected.")
            return
        }
        val settings = state.settings
        val format = settings.format
        val needsRaw = format == FileFormat.DNG || format == FileFormat.RAW_JPEG
        if (needsRaw && (!option.supportsRaw || !rawEnabled)) {
            showError("RAW capture is unavailable for this camera/session.")
            return
        }
        updateState { it.copy(isCapturing = true, error = null, notice = null) }
        cameraHandler.post {
            try {
                val device = cameraDevice ?: throw IllegalStateException("Camera is not open.")
                val session = captureSession ?: throw IllegalStateException("Camera preview is not ready.")
                val jpeg = jpegReader ?: throw IllegalStateException("JPEG output is unavailable.")
                capturePending = true
                val generation = ++captureGeneration
                pendingNeedsRaw = needsRaw
                pendingFormat = format
                pendingCamera = option
                pendingSettings = settings
                pendingMetadata = null
                pendingResult = null
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg.surface)
                    if (needsRaw) addTarget(rawReader?.surface ?: throw IllegalStateException("RAW output is unavailable."))
                    pendingImageOrientation = jpegOrientation(option.characteristics)
                    set(CaptureRequest.JPEG_ORIENTATION, pendingImageOrientation)
                    val needsPostProcessing = settings.watermarkEnabled ||
                        settings.format == FileFormat.PNG ||
                        settings.format == FileFormat.HEIC ||
                        settings.contrast != 1f ||
                        settings.saturation != 1f ||
                        settings.aspectRatio != AspectRatioChoice.FULL
                    set(
                        CaptureRequest.JPEG_QUALITY,
                        (if (needsPostProcessing) 100 else settings.imageQuality).coerceIn(5, 100).toByte(),
                    )
                    applyControls(this, option, settings, still = true)
                }
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        pendingResult = result
                        val overlayConfig = overlayConfigProvider()
                        val wantsLocation = overlayConfig.enabledFields.any {
                            it == OverlayField.GPS ||
                                it == OverlayField.ADDRESS ||
                                it == OverlayField.SPEED_COURSE ||
                                it == OverlayField.SUN_MOON
                        }
                        pendingMetadata = MetadataCollector.collect(
                            appContext,
                            if (wantsLocation) locationProvider() else null,
                            orientationProvider(),
                            option,
                            result.get(CaptureResult.SENSOR_SENSITIVITY),
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            pendingSettings ?: settings,
                            overlayConfigProvider(),
                            jpeg.width,
                            jpeg.height,
                            ambientTemperatureProvider(),
                        )
                        finalizeCaptureIfReady()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        failCapture("Capture failed: ${failure.reason}")
                    }
                }, cameraHandler)
                cameraHandler.postDelayed({
                    if (capturePending && generation == captureGeneration) failCapture("Camera capture timed out.")
                }, 15_000)
            } catch (exception: Exception) {
                failCapture(exception.localizedMessage ?: "Unable to capture image.")
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        previewSurface = holder.surface
        closed = false
        if (state.cameras.isEmpty()) refreshCameras() else startSelectedCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) restartSession()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        previewSurface = null
        closeCamera()
    }

    fun close() {
        closed = true
        captureScheduled = false
        captureCountdownTask?.let(cameraHandler::removeCallbacks)
        captureCountdownTask = null
        closeCamera()
        thread.quitSafely()
    }

    private fun optionKey(option: CameraOption): String = "${option.openCameraId}:${option.physicalCameraId ?: "logical"}"

    private fun selectedOption(): CameraOption? =
        cameraOptions.firstOrNull { optionKey(it) == state.selectedCameraId }

    private fun startSelectedCamera() {
        if (closed || previewSurface == null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showError("Camera permission is required.")
            return
        }
        cameraHandler.post {
            closeCameraOnHandler()
            val option = selectedOption() ?: cameraOptions.firstOrNull()
            if (option == null) {
                showError("No camera is available on this device.")
                return@post
            }
            try {
                cameraManager.openCamera(option.openCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSession(option)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        updateState { it.copy(isReady = false, error = "Camera disconnected.") }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        showError("Camera error ($error).")
                    }
                }, cameraHandler)
            } catch (exception: CameraAccessException) {
                showError("Could not open camera: ${exception.localizedMessage}")
            } catch (exception: SecurityException) {
                showError("Camera permission is required.")
            }
        }
    }

    private fun createSession(option: CameraOption) {
        try {
            val surface = previewSurface ?: return
            val size = state.settings.selectedSize
                ?: option.sizesFor(state.settings.aspectRatio).firstOrNull()
                ?: option.jpegSizes.firstOrNull()
                ?: throw IllegalStateException("Camera has no JPEG output size.")
            closeReaders()
            jpegReader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    if (!capturePending) image.close()
                    else {
                        pendingJpeg?.close()
                        pendingJpeg = image
                        finalizeCaptureIfReady()
                    }
                }, cameraHandler)
            }
            val rawSize = option.rawSizes.firstOrNull()
            rawReader = rawSize?.let {
                android.media.ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        if (!capturePending || !pendingNeedsRaw) image.close()
                        else {
                            pendingRaw?.close()
                            pendingRaw = image
                            finalizeCaptureIfReady()
                        }
                    }, cameraHandler)
                }
            }
            rawEnabled = rawReader != null
            configureSession(option, includeRaw = rawEnabled)
        } catch (exception: Exception) {
            showError("Could not prepare camera outputs: ${exception.localizedMessage}")
        }
    }

    private fun configureSession(option: CameraOption, includeRaw: Boolean) {
        val camera = cameraDevice ?: return
        val preview = previewSurface ?: return
        val jpeg = jpegReader ?: return
        try {
            val outputConfigurations = buildList {
                add(outputConfiguration(preview, option.physicalCameraId))
                add(outputConfiguration(jpeg.surface, option.physicalCameraId))
                if (includeRaw) rawReader?.surface?.let { add(outputConfiguration(it, option.physicalCameraId)) }
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigurations,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession?.close()
                        captureSession = session
                        rawEnabled = includeRaw
                        if (includeRaw.not() && option.supportsRaw) {
                            updateState { it.copy(notice = "Camera stream combination excludes RAW; JPEG capture remains available.") }
                        }
                        updateState { it.copy(isReady = true, error = null) }
                        updateRepeatingRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (includeRaw && rawReader != null) {
                            rawEnabled = false
                            configureSession(option, includeRaw = false)
                        } else {
                            updateState { it.copy(isReady = false, error = "Camera could not configure preview/output streams.") }
                        }
                    }
                },
            )
            camera.createCaptureSession(config)
        } catch (exception: Exception) {
            if (includeRaw) {
                rawEnabled = false
                configureSession(option, includeRaw = false)
            } else {
                showError("Could not create camera session: ${exception.localizedMessage}")
            }
        }
    }

    private fun outputConfiguration(surface: Surface, physicalCameraId: String?): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
        }

    private fun updateRepeatingRequest() {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return
            val preview = previewSurface ?: return
            val option = selectedOption() ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyControls(this, option, state.settings, still = false)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (exception: CameraAccessException) {
            showError("Could not update camera settings: ${exception.localizedMessage}")
        } catch (exception: IllegalArgumentException) {
            showError("This camera rejected one of the selected settings.")
        }
    }

    private fun applyControls(
        builder: CaptureRequest.Builder,
        option: CameraOption,
        settings: CameraSettings,
        still: Boolean,
    ) {
        val evRange = option.exposureRange
        val manual = settings.manualExposure && option.manualSensor
        val isoRange = option.isoRange
        val exposureTimeRange = option.exposureTimeRange
        val flashMode = if (still && option.flashAvailable && !manual) settings.flashMode else FlashMode.OFF
        val aeMode = when (flashMode) {
            FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            FlashMode.OFF -> CaptureRequest.CONTROL_AE_MODE_ON
        }
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, when {
            settings.manualFocus && option.manualFocus -> CaptureRequest.CONTROL_AF_MODE_OFF
            else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        })
        if (manual && isoRange != null && exposureTimeRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso.coerceIn(isoRange.lower, isoRange.upper))
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                settings.shutterNanos.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                settings.exposureCompensation.coerceIn(evRange.lower, evRange.upper),
            )
        }
        option.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { active ->
            builder.set(CaptureRequest.SCALER_CROP_REGION, zoomCropRegion(active, settings.zoom))
        }
        if (option.flashAvailable) builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        if (settings.manualFocus && option.manualFocus) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance.coerceIn(0f, option.maxFocusDistance))
        }
        val awb = when (settings.whiteBalance) {
            WhiteBalance.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            WhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalance.CUSTOM -> CaptureRequest.CONTROL_AWB_MODE_OFF
        }
        if (settings.whiteBalance == WhiteBalance.CUSTOM && option.supportsCustomWhiteBalance) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val gains = kelvinGains(settings.kelvin)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(gains[0], 1f, 1f, gains[1]))
        } else {
            val selectedMode = if (settings.whiteBalance == WhiteBalance.CUSTOM) CaptureRequest.CONTROL_AWB_MODE_AUTO else awb
            val supportedModes = option.availableWhiteBalanceModes
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                if (supportedModes.isEmpty() || selectedMode in supportedModes) selectedMode else CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if (still) builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
    }

    private fun kelvinGains(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(2000, 10000) / 100f
        val red = if (t <= 66) 255f else (329.698727446 * Math.pow(t - 60.0, -0.1332047592)).toFloat()
        val blue = if (t >= 66) 255f else (if (t <= 19) 0.0 else 138.5177312231 * Math.log(t - 10.0) - 305.0447927307).toFloat()
        return floatArrayOf((255f / red.coerceAtLeast(1f)).coerceIn(1f, 2.5f), (255f / blue.coerceAtLeast(1f)).coerceIn(1f, 2.5f))
    }

    private fun zoomCropRegion(active: Rect, requestedZoom: Float): Rect {
        val zoom = requestedZoom.coerceIn(1f, selectedOption()?.maxDigitalZoom ?: 1f)
        val width = (active.width() / zoom).toInt().coerceAtLeast(1)
        val height = (active.height() / zoom).toInt().coerceAtLeast(1)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun finalizeCaptureIfReady() {
        if (!capturePending || pendingJpeg == null || pendingResult == null || pendingMetadata == null) return
        if (pendingNeedsRaw && pendingRaw == null) return
        val jpeg = pendingJpeg ?: return
        val result = pendingResult ?: return
        val raw = pendingRaw
        val metadata = pendingMetadata ?: return
        val option = pendingCamera ?: return
        val settings = pendingSettings ?: state.settings
        val format = pendingFormat ?: FileFormat.JPEG
        pendingJpeg = null
        pendingRaw = null
        capturePending = false
        try {
            val jpegBytes = jpeg.planes[0].buffer.let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            jpeg.close()
            val rawImage = raw
            saveCaptured(jpegBytes, rawImage, result, metadata, option, settings, format)
        } catch (exception: Exception) {
            raw?.close()
            failCapture("Could not process image: ${exception.localizedMessage}")
        }
    }

    private fun saveCaptured(
        jpegBytes: ByteArray,
        rawImage: android.media.Image?,
        result: TotalCaptureResult,
        metadata: CaptureMetadata,
        option: CameraOption,
        settings: CameraSettings,
        format: FileFormat,
    ) {
        cameraHandler.post {
            var savedCount = 0
            var outputBitmap: Bitmap? = null
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val savesJpeg = format != FileFormat.DNG || settings.watermarkEnabled
                val needsCrop = savesJpeg && settings.aspectRatio.ratio?.let { requested ->
                    jpegAspectDiffers(jpegBytes, requested)
                } == true
                if (
                    savesJpeg &&
                    (settings.watermarkEnabled || format == FileFormat.PNG || format == FileFormat.HEIC ||
                        settings.contrast != 1f || settings.saturation != 1f || needsCrop)
                ) {
                    outputBitmap = prepareOutputBitmap(jpegBytes, metadata, settings)
                }
                fun saveBitmap(imageFormat: FileFormat, suffix: String = "") {
                    val mime = when (imageFormat) {
                        FileFormat.PNG -> "image/png"
                        FileFormat.HEIC -> "image/heic"
                        else -> "image/jpeg"
                    }
                    val extension = when (imageFormat) {
                        FileFormat.PNG -> "png"
                        FileFormat.HEIC -> "heic"
                        else -> "jpg"
                    }
                    writeToMediaStore(
                        "${fileBaseName(option)}_${stamp}${suffix}.$extension",
                        mime,
                    ) { stream ->
                        if (imageFormat == FileFormat.JPEG && outputBitmap == null) {
                            stream.write(jpegBytes)
                        } else {
                            val bitmap = outputBitmap
                                ?: throw IllegalStateException("Image processing did not produce a bitmap.")
                            val quality = if (imageFormat == FileFormat.PNG) 100 else settings.imageQuality
                            val compressFormat = when (imageFormat) {
                                FileFormat.PNG -> Bitmap.CompressFormat.PNG
                                FileFormat.HEIC -> Bitmap.CompressFormat.HEIC
                                else -> Bitmap.CompressFormat.JPEG
                            }
                            check(bitmap.compress(compressFormat, quality, stream)) { "Bitmap encoding failed." }
                        }
                    }
                    savedCount++
                }
                if (format == FileFormat.DNG || format == FileFormat.RAW_JPEG) {
                    val image = rawImage ?: throw IllegalStateException("RAW image data is missing.")
                    writeToMediaStore("${fileBaseName(option)}_${stamp}.dng", "image/x-adobe-dng") { stream ->
                        DngCreator(option.characteristics, result).use { creator ->
                            creator.setOrientation(pendingImageOrientation)
                            creator.writeImage(stream, image)
                        }
                    }
                    savedCount++
                    image.close()
                    if (settings.watermarkEnabled || format == FileFormat.RAW_JPEG) {
                        saveBitmap(FileFormat.JPEG, if (settings.watermarkEnabled) "_stamped" else "_jpeg")
                    }
                } else {
                    rawImage?.close()
                    saveBitmap(format)
                }
                outputBitmap?.recycle()
                updateState {
                    it.copy(
                        isCapturing = false,
                        notice = "Saved $savedCount image${if (savedCount == 1) "" else "s"} to Pictures/AdvancedCamera",
                        error = null,
                    )
                }
            } catch (exception: Exception) {
                outputBitmap?.recycle()
                rawImage?.close()
                updateState {
                    it.copy(
                        isCapturing = false,
                        error = "Saving failed: ${exception.localizedMessage ?: "unknown error"}",
                    )
                }
            }
        }
    }

    private fun prepareOutputBitmap(
        jpegBytes: ByteArray,
        metadata: CaptureMetadata,
        settings: CameraSettings,
    ): Bitmap {
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalStateException("Could not decode the camera JPEG.")
        val exifOrientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (rotation == 0f) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        val cropped = settings.aspectRatio.ratio?.let { cropToAspect(oriented, it) } ?: oriented
        if (cropped !== oriented) oriented.recycle()
        val processed = if (settings.contrast != 1f || settings.saturation != 1f) {
            applyImageAdjustments(cropped, settings).also { cropped.recycle() }
        } else cropped
        return if (settings.watermarkEnabled) {
            WatermarkRenderer.draw(processed, metadata, settings, overlayConfigProvider()).also { processed.recycle() }
        } else processed
    }

    private fun applyImageAdjustments(source: Bitmap, settings: CameraSettings): Bitmap {
        val adjusted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val saturation = ColorMatrix().apply { setSaturation(settings.saturation.coerceIn(0f, 2f)) }
        val contrast = settings.contrast.coerceIn(0.5f, 1.5f)
        val offset = 128f * (1f - contrast)
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        saturation.postConcat(contrastMatrix)
        android.graphics.Canvas(adjusted).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(saturation) },
        )
        return adjusted
    }

    private fun cropToAspect(source: Bitmap, targetRatio: Float): Bitmap {
        val sourceRatio = source.width.toFloat() / source.height
        val orientedTargetRatio = if (sourceRatio < 1f) 1f / targetRatio else targetRatio
        if (kotlin.math.abs(sourceRatio - orientedTargetRatio) < 0.015f) return source
        val (width, height) = if (sourceRatio > orientedTargetRatio) {
            (source.height * orientedTargetRatio).toInt().coerceIn(1, source.width) to source.height
        } else {
            source.width to (source.width / orientedTargetRatio).toInt().coerceIn(1, source.height)
        }
        return Bitmap.createBitmap(
            source,
            (source.width - width) / 2,
            (source.height - height) / 2,
            width,
            height,
        )
    }

    private fun jpegAspectDiffers(jpegBytes: ByteArray, targetRatio: Float): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return true
        val ratio = maxOf(bounds.outWidth, bounds.outHeight).toFloat() / minOf(bounds.outWidth, bounds.outHeight)
        return kotlin.math.abs(ratio - targetRatio) >= 0.015f
    }

    private fun writeToMediaStore(name: String, mimeType: String, writer: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AdvancedCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore could not create an output file.")
        try {
            resolver.openOutputStream(uri)?.use(writer)
                ?: throw IllegalStateException("Could not open output file.")
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, published, null, null)
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
        return uri
    }

    private fun fileBaseName(option: CameraOption): String =
        option.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(28)

    private fun jpegOrientation(chars: CameraCharacteristics): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = displayRotationProvider()
        val deviceRotation = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceRotation + 360) % 360
        } else {
            (sensorOrientation + deviceRotation) % 360
        }
    }

    private fun restartSession() {
        if (cameraDevice == null || previewSurface == null) return
        cameraHandler.post {
            captureSession?.close()
            captureSession = null
            selectedOption()?.let(::createSession)
        }
    }

    private fun closeReaders() {
        jpegReader?.close()
        rawReader?.close()
        jpegReader = null
        rawReader = null
    }

    private fun closeCamera() {
        cameraHandler.post { closeCameraOnHandler() }
    }

    private fun closeCameraOnHandler() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        closeReaders()
        rawEnabled = false
        updateState { it.copy(isReady = false) }
    }

    private fun failCapture(message: String) {
        captureScheduled = false
        captureCountdownTask?.let(cameraHandler::removeCallbacks)
        captureCountdownTask = null
        pendingJpeg?.close()
        pendingRaw?.close()
        pendingJpeg = null
        pendingRaw = null
        capturePending = false
        updateState { it.copy(isCapturing = false, error = message) }
    }

    private fun updateState(transform: (CameraUiState) -> CameraUiState) {
        val update = {
            state = transform(state)
            onState(state)
        }
        if (android.os.Looper.myLooper() == appContext.mainLooper) update() else mainHandler.post(update)
    }

    private fun showError(message: String) = updateState { it.copy(error = message, isReady = false) }
}
