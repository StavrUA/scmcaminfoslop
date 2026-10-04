package ua.stavrua.advancedcamera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Geocoder
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class CaptureMetadata(
    val dateTime: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val accuracy: Float?,
    val address: String?,
    val iso: Int?,
    val exposureNanos: Long?,
    val aperture: Float?,
    val focalLength: Float?,
    val equivalentFocalLength: Int?,
    val ev: Float,
    val cameraName: String,
    val device: String,
    val heading: String,
    val battery: String,
    val storage: String,
    val memory: String,
    val network: String,
    val ssid: String?,
    val ipAddress: String,
    val ambientTemperature: Float?,
    val resolution: String,
    val aspectRatio: String,
    val fileFormat: String,
    val colorSpace: String,
    val whiteBalance: String,
    val focusMode: String,
    val sunMoon: String?,
    val speedCourse: String?,
    val appVersion: String,
    val customTitle: String,
)

object MetadataCollector {
    fun collect(
        context: Context,
        location: Location?,
        orientation: FloatArray?,
        camera: CameraOption,
        resultIso: Int?,
        exposureNanos: Long?,
        settings: CameraSettings,
        overlay: OverlayConfig,
        imageWidth: Int,
        imageHeight: Int,
        ambientTemperature: Float?,
    ): CaptureMetadata {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEE, yyyy-MM-dd HH:mm:ss.SSS z", Locale.ENGLISH)
        dateFormat.timeZone = TimeZone.getDefault()
        val calendar = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            time = now
        }
        val dateTime = "${dateFormat.format(now)} · W${calendar.get(java.util.Calendar.WEEK_OF_YEAR)}"
        val address = if (OverlayField.ADDRESS in overlay.enabledFields) location?.let {
            runCatching {
                if (!Geocoder.isPresent()) return@runCatching null
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.ENGLISH).getFromLocation(it.latitude, it.longitude, 1)
                    ?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        } else null
        val battery = if (OverlayField.BATTERY in overlay.enabledFields) batteryStatus(context) else ""
        val selected = overlay.enabledFields
        val locationText = if (OverlayField.GPS in selected) location else null
        val deviceText = if (OverlayField.DEVICE in overlay.enabledFields) "${Build.MANUFACTURER} ${Build.MODEL} · ${camera.name}" else ""
        val sensor = camera.characteristics
        val focalLength = sensor.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensorWidthMm = sensor.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        val equivalent = if (focalLength != null && sensorWidthMm != null && sensorWidthMm > 0f) {
            (focalLength * 36f / sensorWidthMm).toInt()
        } else null
        val aspect = if (settings.aspectRatio.ratio != null) settings.aspectRatio.title else if (imageWidth > 0 && imageHeight > 0) {
            simplifyAspect(imageWidth, imageHeight)
        } else settings.aspectRatio.title
        val wb = when (settings.whiteBalance) {
            WhiteBalance.AUTO -> "Auto (camera)"
            WhiteBalance.CUSTOM -> "Custom · ${settings.kelvin} K"
            else -> settings.whiteBalance.title
        }

        val focusMode = if (settings.manualFocus && camera.manualFocus) "Manual" else when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in camera.availableAfModes -> "AF-C"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in camera.availableAfModes -> "AF-C video"
            CaptureRequest.CONTROL_AF_MODE_AUTO in camera.availableAfModes -> "AF-S / tap focus"
            else -> "Fixed focus"
        }
        val activeFormat = when (settings.format) {
            FileFormat.JPEG -> "JPEG 96%"
            FileFormat.PNG -> "PNG"
            FileFormat.DNG -> if (settings.watermarkEnabled) "DNG + stamped JPEG" else "DNG"
            FileFormat.RAW_JPEG -> "RAW + JPEG"
        }
        val net = if (OverlayField.NETWORK in overlay.enabledFields) networkState(context) else ""
        val ssid = if (OverlayField.WIFI_SSID in overlay.enabledFields) wifiSsid(context) else null
        val localIp = if (OverlayField.IP_ADDRESS in selected) localIpAddress() else "Off"
        val processedJpeg = settings.format != FileFormat.DNG || settings.watermarkEnabled
        val outputSize = if (processedJpeg && settings.aspectRatio.ratio != null && imageWidth > 0 && imageHeight > 0) {
            cropDimensions(imageWidth, imageHeight, settings.aspectRatio.ratio)
        } else imageWidth to imageHeight
        val sizeText = if (outputSize.first > 0 && outputSize.second > 0) {
            "${outputSize.first} × ${outputSize.second} · %.1f MP".format(Locale.US, outputSize.first * outputSize.second / 1_000_000.0)
        } else "Unavailable"
        val sunMoon = if (OverlayField.SUN_MOON in selected && location != null) {
            sunMoonText(location.latitude, location.longitude, now.time)
        } else null
        val speedCourse = if (OverlayField.SPEED_COURSE in selected) location?.let(::speedCourseText) else null
        val memory = if (OverlayField.MEMORY in overlay.enabledFields) memoryUsage(context) else ""
        val storage = if (OverlayField.STORAGE in overlay.enabledFields) storageUsage() else ""
        val ambient = if (OverlayField.AMBIENT_TEMP in overlay.enabledFields) ambientTemperature else null
        val appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val build = if (BuildConfig.DEBUG) "Debug" else "Release"
            "Advanced Camera v${info.versionName ?: "?"} ($build)"
        }.getOrDefault("Advanced Camera")

        return CaptureMetadata(
            dateTime = dateTime,
            latitude = locationText?.latitude,
            longitude = locationText?.longitude,
            altitude = locationText?.takeIf { it.hasAltitude() }?.altitude,
            accuracy = locationText?.takeIf { it.hasAccuracy() }?.accuracy,
            address = address,
            iso = resultIso ?: if (settings.manualExposure) settings.iso else null,
            exposureNanos = exposureNanos ?: if (settings.manualExposure) settings.shutterNanos else null,
            aperture = sensor.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            focalLength = focalLength,
            equivalentFocalLength = equivalent,
            ev = if (settings.manualExposure) {
                0f
            } else {
                sensor.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    ?.let { settings.exposureCompensation * it.numerator.toFloat() / it.denominator.toFloat() }
                    ?: settings.exposureCompensation.toFloat()
            },
            cameraName = camera.name,
            device = deviceText,
            heading = if (OverlayField.ORIENTATION in overlay.enabledFields) orientationText(orientation) else "",
            battery = battery,
            storage = storage,
            memory = memory,
            network = net,
            ssid = ssid,
            ipAddress = localIp,
            ambientTemperature = ambient,
            resolution = sizeText,
            aspectRatio = aspect,
            fileFormat = activeFormat,
            colorSpace = when (settings.format) {
                FileFormat.DNG -> "RAW sensor data"
                FileFormat.RAW_JPEG -> "RAW + sRGB JPEG"
                else -> "sRGB"
            },
            whiteBalance = wb,
            focusMode = focusMode,
            sunMoon = sunMoon,
            speedCourse = speedCourse,
            appVersion = appVersion,
            customTitle = settings.customTitle,
        )
    }

    fun lines(metadata: CaptureMetadata, config: OverlayConfig): List<String> = buildList {
        if (metadata.customTitle.isNotBlank()) add(metadata.customTitle)
        config.enabledFields.forEach { field ->
            val value = when (field) {
                OverlayField.DATE_TIME -> metadata.dateTime
                OverlayField.GPS -> metadata.latitude?.let { latitude ->
                    val longitude = metadata.longitude ?: return@let "GPS unavailable"
                    "GPS %.6f, %.6f · Alt %s · ±%s".format(
                        Locale.US,
                        latitude,
                        longitude,
                        metadata.altitude?.let { "%.1f m".format(Locale.US, it) } ?: "n/a",
                        metadata.accuracy?.let { "%.1f m".format(Locale.US, it) } ?: "n/a",
                    )
                } ?: "GPS unavailable"
                OverlayField.ADDRESS -> metadata.address ?: "Address unavailable"
                OverlayField.SPEED_COURSE -> metadata.speedCourse ?: "Speed/course unavailable"
                OverlayField.SUN_MOON -> metadata.sunMoon ?: "Sun/moon unavailable (location off)"
                OverlayField.EXPOSURE -> "ISO ${metadata.iso ?: "Auto"} · ${metadata.exposureNanos?.let(::formatExposure) ?: "Auto"} · f/${metadata.aperture?.let { "%.1f".format(Locale.US, it) } ?: "?"} · EV ${"%+.2f".format(Locale.US, metadata.ev)}"
                OverlayField.LENS -> "Lens ${metadata.focalLength?.let { "%.1f mm".format(Locale.US, it) } ?: "?"} · ${metadata.equivalentFocalLength?.let { "$it mm eq." } ?: "35 mm eq. n/a"}"
                OverlayField.RESOLUTION -> "Resolution ${metadata.resolution}"
                OverlayField.ASPECT -> "Aspect ${metadata.aspectRatio}"
                OverlayField.FILE_FORMAT -> "Format ${metadata.fileFormat}"
                OverlayField.COLOR_SPACE -> "Color ${metadata.colorSpace}"
                OverlayField.WHITE_BALANCE -> "WB ${metadata.whiteBalance}"
                OverlayField.FOCUS -> "Focus ${metadata.focusMode}"
                OverlayField.DEVICE -> metadata.device.ifBlank { "Device info unavailable" }
                OverlayField.ORIENTATION -> metadata.heading
                OverlayField.BATTERY -> metadata.battery
                OverlayField.STORAGE -> metadata.storage
                OverlayField.MEMORY -> metadata.memory
                OverlayField.NETWORK -> metadata.network
                OverlayField.WIFI_SSID -> "Wi-Fi SSID ${metadata.ssid ?: "unavailable"}"
                OverlayField.IP_ADDRESS -> "Local IP ${metadata.ipAddress}"
                OverlayField.AMBIENT_TEMP -> "Ambient ${metadata.ambientTemperature?.let { "%.1f°C".format(Locale.US, it) } ?: "Sensor unavailable"}"
                OverlayField.APP_VERSION -> metadata.appVersion
            }
            if (value.isNotBlank()) add(value)
        }
    }

    private fun orientationText(orientation: FloatArray?): String = orientation?.let {
        "Az ${((Math.toDegrees(it[0].toDouble()) + 360) % 360).toInt()}° · " +
            "Pitch ${Math.toDegrees(it[1].toDouble()).toInt()}° · Roll ${Math.toDegrees(it[2].toDouble()).toInt()}°"
    } ?: "Orientation unavailable"

    private fun batteryStatus(context: Context): String {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = statusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = when (statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        return "Battery ${level.takeIf { it in 0..100 }?.let { "$it%" } ?: "?"} · $status" +
            if (temperature >= 0) " · %.1f°C".format(Locale.US, temperature / 10f) else ""
    }

    private fun storageUsage(): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        "Storage free ${formatBytes(stat.availableBytes)} / ${formatBytes(stat.totalBytes)}"
    }.getOrDefault("Storage unavailable")

    private fun memoryUsage(context: Context): String = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val used = (info.totalMem - info.availMem).coerceAtLeast(0)
        "RAM ${formatBytes(used)} used / ${formatBytes(info.totalMem)}"
    }.getOrDefault("RAM unavailable")

    private fun networkState(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return@runCatching "Network: no signal"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val rssi = runCatching {
                    @Suppress("DEPRECATION")
                    context.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
                }.getOrNull()
                "Network: Wi-Fi${rssi?.takeIf { it in -100..0 }?.let { " · ${signalBars(it)} bars" } ?: ""}"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Network: Mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Network: Ethernet"
            else -> "Network: connected"
        }
    }.getOrDefault("Network: unavailable")

    @Suppress("DEPRECATION")
    private fun wifiSsid(context: Context): String? {
        val hasLocation = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return null
        val ssid = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        return ssid?.takeIf { it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
    }

    private fun localIpAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: "Unavailable"
    }.getOrDefault("Unavailable")

    private fun speedCourseText(location: Location): String {
        val speed = location.speed.takeIf { location.hasSpeed() }
        val bearing = if (location.hasBearing()) {
            val degrees = location.bearing
            " · ${cardinal(degrees)} (${degrees.toInt()}°)"
        } else ""
        val speedText = speed?.let { "%.1f km/h (%.2f m/s)".format(Locale.US, it * 3.6f, it) } ?: "unavailable"
        return "Speed $speedText$bearing"
    }

    private fun cardinal(degrees: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return directions[((degrees + 22.5f) / 45f).toInt() % directions.size]
    }

    private fun sunMoonText(latitude: Double, longitude: Double, timeMillis: Long): String {
        val sunrise = solarTime(latitude, longitude, timeMillis, rising = true)
        val sunset = solarTime(latitude, longitude, timeMillis, rising = false)
        val moon = moonPhase(timeMillis)
        return "Sunrise ${sunrise ?: "—"} · Sunset ${sunset ?: "—"} · Moon $moon"
    }

    private fun solarTime(latitude: Double, longitude: Double, timeMillis: Long, rising: Boolean): String? {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMillis }
        val dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val longitudeHour = longitude / 15.0
        val approximate = dayOfYear + ((if (rising) 6.0 else 18.0) - longitudeHour) / 24.0
        val meanAnomaly = 0.9856 * approximate - 3.289
        var trueLongitude = meanAnomaly + 1.916 * sin(Math.toRadians(meanAnomaly)) +
            0.020 * sin(2 * Math.toRadians(meanAnomaly)) + 282.634
        trueLongitude = (trueLongitude + 360) % 360
        var rightAscension = Math.toDegrees(kotlin.math.atan(0.91764 * kotlin.math.tan(Math.toRadians(trueLongitude))))
        rightAscension = (rightAscension + 360) % 360
        rightAscension += floor(trueLongitude / 90) * 90 - floor(rightAscension / 90) * 90
        rightAscension /= 15
        val sinDeclination = 0.39782 * sin(Math.toRadians(trueLongitude))
        val cosDeclination = cos(kotlin.math.asin(sinDeclination))
        val cosineHourAngle = (cos(Math.toRadians(90.833)) - sinDeclination * sin(Math.toRadians(latitude))) /
            (cosDeclination * cos(Math.toRadians(latitude)))
        if (cosineHourAngle !in -1.0..1.0) return null
        val hourAngle = if (rising) 360 - Math.toDegrees(kotlin.math.acos(cosineHourAngle))
        else Math.toDegrees(kotlin.math.acos(cosineHourAngle))
        val localMeanTime = hourAngle / 15 + rightAscension - 0.06571 * approximate - 6.622
        val utcHours = (localMeanTime - longitudeHour + 24) % 24
        val instant = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.MILLISECOND, (utcHours * 3_600_000).toInt())
        }
        return SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = TimeZone.getDefault() }.format(instant.time)
    }

    private fun moonPhase(timeMillis: Long): String {
        val synodicMonth = 29.530588853
        val knownNewMoon = 947182440000.0
        val age = ((timeMillis - knownNewMoon) / 86_400_000.0 % synodicMonth + synodicMonth) % synodicMonth
        val phase = age / synodicMonth
        return when {
            phase < 0.035 || phase >= 0.965 -> "New"
            phase < 0.215 -> "Waxing crescent"
            phase < 0.285 -> "First quarter"
            phase < 0.465 -> "Waxing gibbous"
            phase < 0.535 -> "Full"
            phase < 0.715 -> "Waning gibbous"
            phase < 0.785 -> "Last quarter"
            else -> "Waning crescent"
        }
    }

    private fun simplifyAspect(width: Int, height: Int): String {
        val divisor = gcd(width, height)
        return "${width / divisor}:${height / divisor}"
    }

    private fun cropDimensions(width: Int, height: Int, targetRatio: Float): Pair<Int, Int> {
        val ratio = width.toFloat() / height
        val orientedTargetRatio = if (ratio < 1f) 1f / targetRatio else targetRatio
        return if (ratio > orientedTargetRatio) {
            (height * orientedTargetRatio).toInt().coerceAtLeast(1) to height
        } else {
            width to (width / orientedTargetRatio).toInt().coerceAtLeast(1)
        }
    }

    private fun gcd(first: Int, second: Int): Int {
        var a = first
        var b = second
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a.coerceAtLeast(1)
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB".format(Locale.US, gb)
        else "%.0f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    private fun signalBars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        else -> 1
    }

    private fun formatExposure(nanos: Long): String {
        if (nanos <= 0) return "Auto"
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1) "%.2f s".format(Locale.US, seconds)
        else "1/${(1.0 / seconds).toInt().coerceAtLeast(1)} s"
    }
}
