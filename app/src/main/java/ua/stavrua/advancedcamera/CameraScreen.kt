package ua.stavrua.advancedcamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var ui by remember { mutableStateOf(CameraUiState()) }
    val loadedOverlayConfig = remember(context) {
        runCatching { OverlayConfigStore.load(context) }
    }
    var overlayConfig by remember(context) {
        mutableStateOf(loadedOverlayConfig.getOrElse { OverlayConfig() })
    }
    var location by remember { mutableStateOf<Location?>(null) }
    var orientation by remember { mutableStateOf<FloatArray?>(null) }
    var ambientTemperature by remember { mutableStateOf<Float?>(null) }
    val latestLocation by rememberUpdatedState(location)
    val latestOrientation by rememberUpdatedState(orientation)
    val latestOverlayConfig by rememberUpdatedState(overlayConfig)
    val latestAmbientTemperature by rememberUpdatedState(ambientTemperature)
    val controller = remember(context) {
        Camera2Controller(
            context = context,
            onState = { ui = it },
            locationProvider = { latestLocation },
            orientationProvider = { latestOrientation },
            overlayConfigProvider = { latestOverlayConfig },
            ambientTemperatureProvider = { latestAmbientTemperature },
            displayRotationProvider = { context.activityDisplayRotation() },
        )
    }
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCameraPermission by remember { mutableStateOf(cameraGranted) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) controller.refreshCameras()
        if (hasLocationPermission && overlayConfig.needsLocation) location = readLastLocation(context)
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else {
            controller.refreshCameras()
        }
    }

    LaunchedEffect(overlayConfig) {
        controller.updateSettings(
            ui.settings.copy(
                watermarkEnabled = overlayConfig.watermarkEnabled,
                watermarkScale = overlayConfig.watermarkScale,
                watermarkOpacity = overlayConfig.watermarkOpacity,
                customTitle = overlayConfig.customTitle,
            ),
        )
    }

    DisposableEffect(
        controller,
        hasLocationPermission,
        overlayConfig.needsLocation,
        overlayConfig.enabledFields.contains(OverlayField.ORIENTATION),
        overlayConfig.enabledFields.contains(OverlayField.AMBIENT_TEMP),
    ) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val wantsOrientation = OverlayField.ORIENTATION in overlayConfig.enabledFields
        val wantsAmbient = OverlayField.AMBIENT_TEMP in overlayConfig.enabledFields
        val rotationSensor = if (wantsOrientation) sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) else null
        val temperatureSensor = if (wantsAmbient) sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) else null
        val locationManager = context.getSystemService(LocationManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                val values = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, values)
                orientation = values
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val locationListener = object : android.location.LocationListener {
            override fun onLocationChanged(updatedLocation: Location) {
                location = updatedLocation
            }
        }
        val temperatureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                ambientTemperature = event.values.firstOrNull()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (rotationSensor != null) sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        if (temperatureSensor != null) {
            sensorManager.registerListener(temperatureListener, temperatureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (hasLocationPermission && overlayConfig.needsLocation) {
            try {
                location = readLastLocation(context)
                locationManager.getProviders(true).forEach { provider ->
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener)
                }
            } catch (_: SecurityException) {
                location = null
            }
        }
        onDispose {
            sensorManager.unregisterListener(listener)
            sensorManager.unregisterListener(temperatureListener)
            if (hasLocationPermission && overlayConfig.needsLocation) locationManager.removeUpdates(locationListener)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    val camera = ui.cameras.firstOrNull { "${it.openCameraId}:${it.physicalCameraId ?: "logical"}" == ui.selectedCameraId }
    val settings = ui.settings
    var showOverlayFields by remember { mutableStateOf(false) }
    var previewGrid by remember { mutableStateOf(PreviewGrid.OFF) }
    var showLevelGuide by remember { mutableStateOf(true) }
    val currentZoom by rememberUpdatedState(settings.zoom)
    val maximumZoom by rememberUpdatedState(camera?.maxDigitalZoom ?: 1f)

    fun saveOverlayConfig(updated: OverlayConfig) {
        try {
            OverlayConfigStore.save(context, updated)
            overlayConfig = updated
            if (!overlayConfig.needsLocationPermission && updated.needsLocationPermission && !hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        } catch (exception: Exception) {
            ui = ui.copy(error = "Could not save overlay configuration: ${exception.localizedMessage}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f)
                .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
            controller = controller,
            ready = ui.isReady,
            grid = previewGrid,
            rollRadians = orientation?.getOrNull(2),
            showLevelGuide = showLevelGuide,
            onPinchZoom = { scale ->
                if (maximumZoom > 1f) {
                    controller.setZoom((currentZoom * scale).coerceIn(1f, maximumZoom))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADVANCED CAMERA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (overlayConfig.needsLocationPermission && !hasLocationPermission) {
                    Text(
                        "Enable location",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                    )
                }
            }
            if (!hasCameraPermission) {
                Button(
                    onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant camera permission")
                }
            }
            DropdownField(
                label = "Camera",
                selected = camera?.lensDescription() ?: if (ui.cameras.isEmpty()) "Searching cameras…" else "Select camera",
                options = ui.cameras.map { it.lensDescription() to "${it.openCameraId}:${it.physicalCameraId ?: "logical"}" },
                onSelect = controller::selectCamera,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = "Flash",
                    selected = if (camera?.flashAvailable == true) settings.flashMode.title else "Unavailable",
                    options = if (camera?.flashAvailable == true) {
                        FlashMode.entries.map { it.title to it.name }
                    } else emptyList(),
                    modifier = Modifier.weight(1f),
                    onSelect = { mode ->
                        controller.updateSettings(
                            settings.copy(
                                flashMode = FlashMode.valueOf(mode),
                                manualExposure = false,
                            ),
                        )
                    },
                )
                DropdownField(
                    label = "Timer",
                    selected = if (settings.captureTimerSeconds == 0) "Off" else "${settings.captureTimerSeconds} sec",
                    options = listOf("Off" to "0", "3 sec" to "3", "5 sec" to "5", "10 sec" to "10"),
                    modifier = Modifier.weight(1f),
                    onSelect = { seconds ->
                        controller.updateSettings(settings.copy(captureTimerSeconds = seconds.toInt()))
                    },
                )
                DropdownField(
                    label = "Grid",
                    selected = previewGrid.title,
                    options = PreviewGrid.entries.map { it.title to it.name },
                    modifier = Modifier.weight(1f),
                    onSelect = { previewGrid = PreviewGrid.valueOf(it) },
                )
                DropdownField(
                    label = "Level",
                    selected = if (showLevelGuide) "On" else "Off",
                    options = listOf("On" to "on", "Off" to "off"),
                    modifier = Modifier.weight(1f),
                    onSelect = { showLevelGuide = it == "on" },
                )
            }
            if (camera != null && camera.maxDigitalZoom > 1f) {
                ValueSlider(
                    "Zoom",
                    settings.zoom,
                    1f,
                    camera.maxDigitalZoom,
                    "%.1fx".format(settings.zoom),
                    { controller.updateSettings(settings.copy(zoom = it)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = "Aspect",
                    selected = settings.aspectRatio.title,
                    options = AspectRatioChoice.entries.map { it.title to it.name },
                    modifier = Modifier.weight(1f),
                    onSelect = { name ->
                        controller.updateSettings(settings.copy(aspectRatio = AspectRatioChoice.valueOf(name), selectedSize = null))
                    },
                )
                DropdownField(
                    label = "Format",
                    selected = settings.format.title,
                    options = FileFormat.entries
                        .filter { it == FileFormat.JPEG || it == FileFormat.PNG || camera?.supportsRaw == true }
                        .map { it.title to it.name },
                    modifier = Modifier.weight(1f),
                    onSelect = { controller.updateSettings(settings.copy(format = FileFormat.valueOf(it))) },
                )
            }
            if (camera != null) {
                DropdownField(
                    label = "Resolution",
                    selected = settings.selectedSize?.let { "${it.width} × ${it.height}" } ?: "Maximum",
                    options = camera.sizesFor(settings.aspectRatio).map { "${it.width} × ${it.height}" to "${it.width}x${it.height}" },
                    onSelect = { selected ->
                        val size = camera.sizesFor(settings.aspectRatio).firstOrNull { "${it.width}x${it.height}" == selected }
                        if (size != null) controller.updateSettings(settings.copy(selectedSize = size))
                    },
                )
                Text(
                    buildString {
                        append(if (camera.manualSensor) "Manual sensor supported" else "Manual ISO/shutter unavailable")
                        append(if (camera.supportsRaw) " · RAW supported" else " · RAW unavailable")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            HorizontalDivider()
            Text("PRO CONTROLS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (camera != null) {
                SettingToggle(
                    title = "Manual exposure",
                    subtitle = "ISO + shutter override automatic exposure",
                    checked = settings.manualExposure && camera.manualSensor,
                    enabled = camera.manualSensor,
                    onCheckedChange = {
                        controller.updateSettings(
                            settings.copy(
                                manualExposure = it,
                                flashMode = if (it) FlashMode.OFF else settings.flashMode,
                            ),
                        )
                    },
                )
                if (settings.manualExposure && camera.manualSensor) {
                    val isoRange = camera.isoRange
                    if (isoRange != null) {
                        ValueSlider(
                            "ISO",
                            settings.iso.toFloat(),
                            isoRange.lower.toFloat(),
                            isoRange.upper.toFloat(),
                            "${settings.iso}",
                            { controller.updateSettings(settings.copy(iso = it.roundToInt())) },
                        )
                    }
                    val exposureRange = camera.exposureTimeRange
                    if (exposureRange != null) {
                        val minStops = kotlin.math.ln(exposureRange.lower.toDouble())
                        val maxStops = kotlin.math.ln(exposureRange.upper.toDouble())
                        val current = settings.shutterNanos.coerceIn(exposureRange.lower, exposureRange.upper)
                        ValueSlider(
                            "Shutter",
                            kotlin.math.ln(current.toDouble()).toFloat(),
                            minStops.toFloat(),
                            maxStops.toFloat(),
                            formatShutter(current),
                            { value ->
                                controller.updateSettings(settings.copy(shutterNanos = kotlin.math.exp(value.toDouble()).toLong()))
                            },
                        )
                    }
                } else {
                    val range = camera.exposureRange
                    ValueSlider(
                        "Exposure compensation",
                        settings.exposureCompensation.toFloat(),
                        range.lower.toFloat(),
                        range.upper.toFloat(),
                        "${settings.exposureCompensation} EV steps",
                        { controller.updateSettings(settings.copy(exposureCompensation = it.roundToInt())) },
                    )
                }
                SettingToggle(
                    title = "Manual focus",
                    subtitle = "Tap the preview to set autofocus/metering",
                    checked = settings.manualFocus && camera.manualFocus,
                    enabled = camera.manualFocus,
                    onCheckedChange = { controller.updateSettings(settings.copy(manualFocus = it)) },
                )
                if (settings.manualFocus && camera.manualFocus) {
                    ValueSlider(
                        "Focus distance",
                        settings.focusDistance,
                        0f,
                        camera.maxFocusDistance,
                        "%.2f diopters".format(settings.focusDistance),
                        { controller.updateSettings(settings.copy(focusDistance = it)) },
                    )
                }
                DropdownField(
                    label = "White balance",
                    selected = settings.whiteBalance.title,
                    options = WhiteBalance.entries.filter { mode ->
                        when (mode) {
                            WhiteBalance.AUTO -> true
                            WhiteBalance.CUSTOM -> camera.supportsCustomWhiteBalance
                            else -> {
                                val requestMode = when (mode) {
                                    WhiteBalance.DAYLIGHT -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                                    WhiteBalance.CLOUDY -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                                    WhiteBalance.INCANDESCENT -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                                    WhiteBalance.FLUORESCENT -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                                    else -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
                                }
                                requestMode in camera.availableWhiteBalanceModes
                            }
                        }
                    }.map { it.title to it.name },
                    onSelect = { controller.updateSettings(settings.copy(whiteBalance = WhiteBalance.valueOf(it))) },
                )
                if (settings.whiteBalance == WhiteBalance.CUSTOM) {
                    ValueSlider(
                        "Temperature",
                        settings.kelvin.toFloat(),
                        2000f,
                        10000f,
                        "${settings.kelvin} K",
                        { controller.updateSettings(settings.copy(kelvin = it.roundToInt())) },
                    )
                }
            }
            HorizontalDivider()
            SettingToggle(
                title = "Metadata watermark / OSD",
                subtitle = "Draw GPS, address, time, exposure, device, sensors and battery",
                checked = overlayConfig.watermarkEnabled,
                enabled = true,
                onCheckedChange = { enabled ->
                    controller.updateSettings(settings.copy(watermarkEnabled = enabled))
                    saveOverlayConfig(overlayConfig.copy(watermarkEnabled = enabled))
                },
            )
            if (overlayConfig.watermarkEnabled) {
                OutlinedTextField(
                    value = overlayConfig.customTitle,
                    onValueChange = { title ->
                        val trimmed = title.take(100)
                        controller.updateSettings(settings.copy(customTitle = trimmed))
                        saveOverlayConfig(overlayConfig.copy(customTitle = trimmed))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom title") },
                    singleLine = true,
                )
                ValueSlider(
                    "OSD size",
                    settings.watermarkScale,
                    0.5f,
                    2.5f,
                    "%.1fx".format(settings.watermarkScale),
                    { value ->
                        controller.updateSettings(settings.copy(watermarkScale = value))
                        saveOverlayConfig(overlayConfig.copy(watermarkScale = value))
                    },
                )
                ValueSlider(
                    "OSD opacity",
                    settings.watermarkOpacity,
                    0.2f,
                    1f,
                    "${(settings.watermarkOpacity * 100).roundToInt()}%",
                    { value ->
                        controller.updateSettings(settings.copy(watermarkOpacity = value))
                        saveOverlayConfig(overlayConfig.copy(watermarkOpacity = value))
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showOverlayFields = !showOverlayFields },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showOverlayFields) "Hide overlay fields" else "Choose overlay fields · ${overlayConfig.enabledFields.size} enabled",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (showOverlayFields) {
                    Text(
                        "Selections are saved locally in the private file files/overlay-config.json. Location, device ID, network, IP and Wi-Fi name are off by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    loadedOverlayConfig.exceptionOrNull()?.let {
                        Text(
                            "The saved configuration could not be read; defaults are active. Saving a change will replace the invalid file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OverlayField.entries.groupBy { it.group }.forEach { (group, fields) ->
                        Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        fields.forEach { field ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val nextFields = if (field in overlayConfig.enabledFields) {
                                        overlayConfig.enabledFields - field
                                    } else {
                                        overlayConfig.enabledFields + field
                                    }
                                    saveOverlayConfig(overlayConfig.copy(enabledFields = nextFields))
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = field in overlayConfig.enabledFields,
                                    onCheckedChange = { checked ->
                                        val nextFields = if (checked) overlayConfig.enabledFields + field
                                        else overlayConfig.enabledFields - field
                                        saveOverlayConfig(overlayConfig.copy(enabledFields = nextFields))
                                    },
                                )
                                Text(
                                    field.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Text(
                    if (location != null) "GPS fix · ${"%.5f".format(location!!.latitude)}, ${"%.5f".format(location!!.longitude)}"
                    else "No recent GPS fix; location fields will be marked unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ui.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = controller::capture,
                    enabled = ui.isReady && !ui.isCapturing,
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(if (ui.isCapturing) "…" else "●", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    when {
                        !hasCameraPermission -> "Camera permission needed"
                        ui.isCapturing -> "Saving…"
                        ui.isReady -> "Tap shutter to capture"
                        else -> "Starting camera…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    controller: Camera2Controller,
    ready: Boolean,
    grid: PreviewGrid,
    rollRadians: Float?,
    showLevelGuide: Boolean,
    onPinchZoom: (Float) -> Unit,
) {
    val latestPinchZoom by rememberUpdatedState(onPinchZoom)
    Box(modifier = modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize()
                .pointerInput(controller) {
                    detectTapGestures { offset ->
                        controller.tapToFocus(offset.x, offset.y, size.width, size.height)
                    }
                }
                .pointerInput(controller) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) latestPinchZoom(zoomChange)
                    }
                },
            factory = { context ->
                SurfaceView(context).also { view ->
                    view.holder.addCallback(controller)
                }
            },
        )
        if (grid != PreviewGrid.OFF) {
            Canvas(Modifier.fillMaxSize()) {
                val color = Color.White.copy(alpha = 0.72f)
                val strokeWidth = 1.dp.toPx()
                if (grid == PreviewGrid.THIRDS) {
                    val verticalStep = size.width / 3f
                    val horizontalStep = size.height / 3f
                    for (index in 1..2) {
                        drawLine(color, Offset(verticalStep * index, 0f), Offset(verticalStep * index, size.height), strokeWidth)
                        drawLine(color, Offset(0f, horizontalStep * index), Offset(size.width, horizontalStep * index), strokeWidth)
                    }
                } else {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val arm = 20.dp.toPx()
                    drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth, cap = StrokeCap.Round)
                    drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth, cap = StrokeCap.Round)
                }
            }
            if (showLevelGuide && rollRadians != null) {
                Canvas(Modifier.align(Alignment.Center).size(width = 150.dp, height = 40.dp)) {
                    val rollDegrees = Math.toDegrees(rollRadians.toDouble()).toFloat()
                    val levelColor = if (kotlin.math.abs(rollDegrees) < 1.5f) Color(0xFF66BB6A) else Color(0xFFFF7043)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val halfLength = size.width * 0.38f
                    rotate(rollDegrees, center) {
                        drawLine(
                            levelColor.copy(alpha = 0.9f),
                            Offset(center.x - halfLength, center.y),
                            Offset(center.x + halfLength, center.y),
                            3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(levelColor, 5.dp.toPx(), center)
                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            Offset(center.x - halfLength, center.y - 8.dp.toPx()),
                            Offset(center.x - halfLength, center.y + 8.dp.toPx()),
                            2.dp.toPx(),
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            Offset(center.x + halfLength, center.y - 8.dp.toPx()),
                            Offset(center.x + halfLength, center.y + 8.dp.toPx()),
                            2.dp.toPx(),
                        )
                    }
                }
            }
        }
        if (!ready) {
            Text(
                "Camera preview",
                modifier = Modifier.align(Alignment.Center),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f),
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text("Tap preview to focus", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { if (options.isNotEmpty()) expanded = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (title, value) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(
    title: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    if (maximum <= minimum) return
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(minimum, maximum),
            onValueChange = onValueChange,
            valueRange = minimum..maximum,
        )
    }
}

private fun formatShutter(nanos: Long): String {
    val seconds = nanos / 1_000_000_000.0
    return if (seconds >= 1.0) "%.2f s".format(seconds) else "1/${(1 / seconds).toInt().coerceAtLeast(1)} s"
}

private fun readLastLocation(context: Context): Location? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    return try {
        val manager = context.getSystemService(LocationManager::class.java)
        manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }
}

private fun Context.activityDisplayRotation(): Int {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.display?.rotation ?: android.view.Surface.ROTATION_0
        current = current.baseContext
    }
    return android.view.Surface.ROTATION_0
}
