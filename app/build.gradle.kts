import java.util.Properties

plugins {
    id("com.android.application")
}

// The release build reads storeFile, storePassword, keyAlias and keyPassword from keystore.properties. If the file is
// not there, the release build uses the debug key.
val keystore = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.atvos.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.atvos.launcher"
        minSdk = 31 // Android 12 and later
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = false
    }

    // Adds the encrypted dependency report to the app bundle only. APK files do not contain it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

dependencies {
    // Google Play Billing without its transitive dependencies. The classes in src/main/java/androidx replace the
    // AndroidX calls that it makes.
    implementation("com.android.billingclient:billing:9.1.0") { isTransitive = false }
}
