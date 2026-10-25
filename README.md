# Advanced Camera

Android 12+ camera app built with Kotlin, Jetpack Compose, and Camera2. The app lists cameras exposed by Android, inspects each camera's capabilities, and disables unsupported manual/RAW controls rather than assuming every device supports them.

> **No Android Studio or local Android SDK required to get an APK.** GitHub Actions builds a debug APK whenever code is pushed and also supports manual runs. Download it from the run's **Artifacts** section.

The English-language UI supports logical cameras and selectable physical IDs reported by logical cameras, EV compensation, manual ISO/shutter/focus, tap metering, supported white-balance presets/custom Kelvin, JPEG/PNG/DNG/RAW+JPEG, five aspect ratios, still resolution selection, digital zoom with slider and pinch gestures, flash modes, a self-timer, preview grids, a live virtual horizon, dynamic Android 12+ colors, and a configurable text OSD. Camera-specific controls are exposed only when Camera2 reports support. Overlay fields include GPS/address/speed/course and sun/moon times, capture time, exposure/lens/35 mm equivalent, image resolution/format/color space/WB/focus, device/camera ID, orientation, battery, storage/RAM, optional network/SSID/local IP, optional standard Android ambient-temperature sensor (only if the device exposes one; no Pixel-specific API), and app version. OSD field switches, title, size, opacity, and enable state are saved to the app-private `files/overlay-config.json`; location, device, network, SSID, and IP fields are off by default. Location permission is requested only when the user enables a location-dependent field; Wi-Fi name (SSID) has its own opt-in switch and is read locally only when enabled. The app does not fetch weather, read IMEI/serial numbers, or send telemetry. Original DNG data is preserved; a companion JPEG is generated when the OSD is on.

## Build an APK with GitHub Actions

The `Android APK` workflow runs on pushes, pull requests, and manual dispatches. To get the APK without installing Android Studio:

1. Open the repository's **Actions** tab and select **Android APK**.
2. Open the latest successful run for your branch.
3. Download the `advanced-camera-debug-apk` artifact. It contains `app-debug.apk`; install it on an Android 12+ device.

GitHub retains the uploaded artifact for 14 days. This CI artifact is a debug APK, suitable for testing and sideloading; it is not a Play Store release.

## Build locally (optional)

If you later have a JDK 17+ and Android SDK Platform 35 + Build Tools 35.0.0 installed, run from the repository root:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The minimum supported Android version is API 31. Physical camera availability, flash, zoom, manual controls, RAW, and maximum resolutions depend on what Camera2 exposes on the device. The self-timer and preview grid are app-level features. RAW files remain unmodified; when the OSD is enabled, a stamped JPEG companion is saved alongside each DNG.

## Create an installable release APK

1. Create a release signing key once. Android's `keytool` prompts for the passwords; keep a secure backup of the keystore because future app updates must use the same key:

   ```sh
   mkdir -p "$HOME/.android"
   keytool -genkeypair -v \
     -keystore "$HOME/.android/advanced-camera-release.jks" \
     -storetype JKS -alias advanced-camera -keyalg RSA -keysize 4096 -validity 10000
   ```

   ```sh
   chmod 600 "$HOME/.android/advanced-camera-release.jks"
   ```

2. Put signing values in your user-level `~/.gradle/gradle.properties`, **not in this repository**:

   ```properties
   CAMERA_RELEASE_STORE_FILE=/absolute/path/to/advanced-camera-release.jks
   CAMERA_RELEASE_STORE_PASSWORD=your-keystore-password
   CAMERA_RELEASE_KEY_ALIAS=advanced-camera
   CAMERA_RELEASE_KEY_PASSWORD=your-key-password
   ```

   Restrict access to the file and never commit passwords or the keystore:

   ```sh
   chmod 600 "$HOME/.gradle/gradle.properties"
   ```

3. Build signed release artifacts:

   ```sh
   ./gradlew assembleRelease bundleRelease
   ```

   The APK you can install/share is `app/build/outputs/apk/release/app-release.apk`; the Play Store bundle is `app/build/outputs/bundle/release/app-release.aab`. Install the APK with `adb install -r app/build/outputs/apk/release/app-release.apk`. Without all four signing properties, Gradle intentionally produces an unsigned release APK; the build fails if only some signing values are supplied.