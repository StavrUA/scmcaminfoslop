plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = listOf(
    "CAMERA_RELEASE_STORE_FILE",
    "CAMERA_RELEASE_STORE_PASSWORD",
    "CAMERA_RELEASE_KEY_ALIAS",
    "CAMERA_RELEASE_KEY_PASSWORD",
).associateWith { key ->
    providers.gradleProperty(key).orElse(providers.environmentVariable(key)).orNull
}
val releaseSigningConfigured = signingProperties.values.all { !it.isNullOrBlank() }
if (signingProperties.values.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing requires all CAMERA_RELEASE_* values. See README.md.")
}

android {
    namespace = "ua.stavrua.advancedcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.stavrua.advancedcamera"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.create("release").apply {
                    storeFile = file(signingProperties.getValue("CAMERA_RELEASE_STORE_FILE")!!)
                    storePassword = signingProperties.getValue("CAMERA_RELEASE_STORE_PASSWORD")
                    keyAlias = signingProperties.getValue("CAMERA_RELEASE_KEY_ALIAS")
                    keyPassword = signingProperties.getValue("CAMERA_RELEASE_KEY_PASSWORD")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
