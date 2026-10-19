import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (never committed): storeFile, storePassword,
// keyAlias, keyPassword. Without it, release builds are signed with the local debug key, for
// sideloading.
val keystore = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.atvos.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.atvos.launcher"
        minSdk = 31 // Android 12+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // The Google-encrypted dependency report: in the Play bundle (so Play can flag outdated SDKs),
    // not in sideloaded APKs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

// No dependencies: the app uses only the Android framework.
