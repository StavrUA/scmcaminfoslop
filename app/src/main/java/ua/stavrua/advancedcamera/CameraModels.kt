package ua.stavrua.advancedcamera

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size

data class CameraOption(
    val openCameraId: String,
    val physicalCameraId: String?,
    val name: String,
    val facing: Int,
    val characteristics: CameraCharacteristics,
    val jpegSizes: List<Size>,
    val rawSizes: List<Size>,
) {
    val supportsRaw: Boolean get() = rawSizes.isNotEmpty()
    val flashAvailable: Boolean
        get() = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val maxDigitalZoom: Float
        get() = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.coerceAtLeast(1f) ?: 1f
    val manualSensor: Boolean
        get() = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val manualFocus: Boolean
        get() = (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
    val availableWhiteBalanceModes: Set<Int>
        get() = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
    val availableAfModes: Set<Int>
        get() = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
    val supportsCustomWhiteBalance: Boolean
        get() = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
    val exposureRange: Range<Int>
        get() = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val isoRange: Range<Int>?
        get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureTimeRange: Range<Long>?
        get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val maxFocusDistance: Float
        get() = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
}

enum class AspectRatioChoice(val title: String, val ratio: Float?) {
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f),
    RATIO_16_10("16:10", 16f / 10f),
    FULL("Full", null),
}

enum class FileFormat(val title: String) {
    JPEG("JPEG"),
    HEIC("HEIC"),
    PNG("PNG"),
    DNG("RAW (DNG)"),
    RAW_JPEG("RAW + JPEG"),
}

enum class WhiteBalance(val title: String) {
    AUTO("Auto"),
    DAYLIGHT("Daylight"),
    CLOUDY("Cloudy"),
    INCANDESCENT("Incandescent"),
    FLUORESCENT("Fluorescent"),
    CUSTOM("Custom Kelvin"),
}

enum class FlashMode(val title: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On"),
}

enum class PreviewGrid(val title: String) {
    OFF("Off"),
    THIRDS("Rule of thirds"),
    CROSSHAIR("Crosshair"),
}

data class CameraSettings(
    val exposureCompensation: Int = 0,
    val iso: Int = 400,
    val shutterNanos: Long = 10_000_000L,
    val manualExposure: Boolean = false,
    val manualFocus: Boolean = false,
    val focusDistance: Float = 0f,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val kelvin: Int = 5500,
    val format: FileFormat = FileFormat.JPEG,
    val imageQuality: Int = 96,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val aspectRatio: AspectRatioChoice = AspectRatioChoice.RATIO_4_3,
    val selectedSize: Size? = null,
    val zoom: Float = 1f,
    val flashMode: FlashMode = FlashMode.OFF,
    val captureTimerSeconds: Int = 0,
    val watermarkEnabled: Boolean = true,
    val watermarkScale: Float = 1f,
    val watermarkOpacity: Float = 0.88f,
    val customTitle: String = "",
)

data class CameraUiState(
    val cameras: List<CameraOption> = emptyList(),
    val selectedCameraId: String? = null,
    val settings: CameraSettings = CameraSettings(),
    val isReady: Boolean = false,
    val isCapturing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

fun CameraOption.sizesFor(aspect: AspectRatioChoice): List<Size> {
    if (aspect == AspectRatioChoice.FULL || aspect.ratio == null) return jpegSizes
    return jpegSizes.filter { size ->
        val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
        kotlin.math.abs(ratio - aspect.ratio) < 0.035f
    }.ifEmpty { jpegSizes }
}

fun CameraOption.lensDescription(): String {
    val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.firstOrNull()
    val focalText = focalLength?.let { " · ${"%.1f".format(it)} mm" } ?: ""
    val facingText = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
        else -> "Rear"
    }
    return "$facingText$focalText · $name"
}
