plugins {
    id("com.android.application")
}

android {
    namespace = "app.atvos.launcher"
    compileSdk = 36
    // Pinned to what the Nix-managed SDK ships (the SDK is read-only, Gradle can't fetch others)
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.atvos.launcher"
        minSdk = 34 // Android 14, the TCL C6K
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: sign with the local debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // Don't embed the Google-encrypted dependency metadata block
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// No dependencies: the app uses only the Android framework.
