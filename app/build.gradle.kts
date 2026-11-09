plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.peak.officesentry"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peak.officesentry"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Real phones only (drops x86 emulator libs, roughly halves APK size)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // A fixed keystore lives in the repo so every CI build installs over the
    // previous one. It is NOT a secret - fine for a personal sideloaded app.
    signingConfigs {
        create("sentry") {
            storeFile = file("sentry.keystore")
            storePassword = "officesentry"
            keyAlias = "sentry"
            keyPassword = "officesentry"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sentry")
        }
        debug {
            signingConfig = signingConfigs.getByName("sentry")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // On-device ML Kit (bundled models: work offline, no Play Services download)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
