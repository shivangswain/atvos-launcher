import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing (Google Play's upload key) comes from keystore.properties (never committed):
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are signed with the
// local debug key, so a checkout without the key can still build and test them.
val keystore = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.atvos.launcher"
    compileSdk = 37

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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

dependencies {
    // Google Play billing, for the optional tip in Settings, and nothing it would bring along: the
    // three AndroidX calls it makes are stood in for in src/main/java/androidx (no AndroidX, no
    // Kotlin), its usage logging (datatransport) turns itself off when missing, and the one screen
    // needing androidx.activity (alternative billing, unused) is taken out of the manifest.
    implementation("com.android.billingclient:billing:9.1.0") { isTransitive = false }
}
