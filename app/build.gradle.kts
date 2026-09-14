plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debashis.carlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.debashis.carlauncher"
        // The head unit is Android 11 / SDK 30 exactly. No compatibility shims needed.
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// Deliberately zero dependencies: no AppCompat, no Material, no Compose.
// Everything here is framework API. Keeps the APK tiny and the heap small on a 1.9 GB RK3326.
dependencies { }
