package ua.stavrua.advancedcamera

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

enum class OverlayField(val title: String, val group: String, val enabledByDefault: Boolean) {
    DATE_TIME("Date, time, time zone and week", "Time", true),
    GPS("GPS coordinates, altitude and accuracy", "Location", false),
    ADDRESS("Reverse-geocoded address", "Location", false),
    SPEED_COURSE("GPS speed and course / bearing", "Location", false),
    SUN_MOON("Sunrise, sunset and moon phase", "Location", false),
    EXPOSURE("ISO, shutter, aperture and EV", "Camera", true),
    LENS("Lens and 35 mm equivalent focal length", "Camera", true),
    RESOLUTION("Image resolution and megapixels", "Camera", true),
    ASPECT("Image aspect ratio", "Camera", true),
    FILE_FORMAT("File format and JPEG quality", "Camera", true),
    COLOR_SPACE("Image color space", "Camera", true),
    WHITE_BALANCE("White balance / Kelvin", "Camera", true),
    FOCUS("Focus mode", "Camera", true),
    DEVICE("Device model and camera ID", "Device", false),
    ORIENTATION("Heading, pitch and roll", "Device", true),
    BATTERY("Battery level, status and temperature", "Device", true),
    STORAGE("Free and total storage", "Device", true),
    MEMORY("RAM usage", "Device", true),
    AMBIENT_TEMP("Ambient temperature sensor", "Device", false),
    NETWORK("Network type and signal", "Network", false),
    WIFI_SSID("Wi-Fi network name (SSID)", "Network", false),
    IP_ADDRESS("Local IP address", "Network", false),
    APP_VERSION("App version and build type", "Device", true),
}

data class OverlayConfig(
    val enabledFields: Set<OverlayField> = OverlayField.entries.filter { it.enabledByDefault }.toSet(),
    val watermarkEnabled: Boolean = true,
    val watermarkScale: Float = 1f,
    val watermarkOpacity: Float = 0.88f,
    val customTitle: String = "",
) {
    val needsLocation: Boolean
        get() = enabledFields.any {
            it == OverlayField.GPS ||
                it == OverlayField.ADDRESS ||
                it == OverlayField.SPEED_COURSE ||
                it == OverlayField.SUN_MOON
        }

    val needsLocationPermission: Boolean
        get() = needsLocation || OverlayField.WIFI_SSID in enabledFields
}

object OverlayConfigStore {
    private const val FILE_NAME = "overlay-config.json"
    private const val TAG = "OverlayConfigStore"

    fun load(context: Context): OverlayConfig {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        return try {
            val root = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            val defaults = OverlayConfig()
            val fields = root.optJSONArray("enabledFields")
            val enabled = if (fields == null) {
                defaults.enabledFields
            } else {
                buildSet {
                    for (index in 0 until fields.length()) {
                        runCatching { OverlayField.valueOf(fields.getString(index)) }.getOrNull()?.let(::add)
                    }
                }
            }
            OverlayConfig(
                enabledFields = enabled,
                watermarkEnabled = root.optBoolean("watermarkEnabled", defaults.watermarkEnabled),
                watermarkScale = root.optDouble("watermarkScale", defaults.watermarkScale.toDouble()).toFloat().coerceIn(0.5f, 2.5f),
                watermarkOpacity = root.optDouble("watermarkOpacity", defaults.watermarkOpacity.toDouble()).toFloat().coerceIn(0.2f, 1f),
                customTitle = root.optString("customTitle", defaults.customTitle).take(100),
            )
        } catch (_: FileNotFoundException) {
            OverlayConfig()
        } catch (exception: Exception) {
            Log.e(TAG, "Could not read the saved overlay configuration.", exception)
            throw IllegalStateException("Could not read the saved overlay configuration.", exception)
        }
    }

    fun save(context: Context, config: OverlayConfig) {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        val root = JSONObject()
            .put("version", 1)
            .put("enabledFields", org.json.JSONArray(config.enabledFields.map { it.name }.sorted()))
            .put("watermarkEnabled", config.watermarkEnabled)
            .put("watermarkScale", config.watermarkScale.coerceIn(0.5f, 2.5f).toDouble())
            .put("watermarkOpacity", config.watermarkOpacity.coerceIn(0.2f, 1f).toDouble())
            .put("customTitle", config.customTitle.take(100))
        val stream = file.startWrite()
        try {
            stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }
}
