plugins {
    id("com.android.application")
}

android {
    namespace = "com.taptrak"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taptrak"
        minSdk = 24
        targetSdk = 34
        // Android needs this to rise with each update. Cloud builds number themselves.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "3.0"
    }

    // Cloud builds sign with one permanent key, because Android only installs an
    // update over the app if the signature matches. Local builds use the debug key.
    val keystorePath = System.getenv("TAPTRAK_KEYSTORE")
    val permanentKey = if (keystorePath == null) null else signingConfigs.create("permanent") {
        storeFile = file(keystorePath)
        storeType = "pkcs12"
        storePassword = System.getenv("TAPTRAK_KEYSTORE_PASSWORD")
        keyAlias = "taptrak"
        keyPassword = System.getenv("TAPTRAK_KEYSTORE_PASSWORD")
    }

    buildTypes {
        debug {
            if (permanentKey != null) signingConfig = permanentKey
        }
        release {
            isMinifyEnabled = false
            // Debug key by default so `assembleRelease` produces an installable APK.
            // Replace with your own release key before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No runtime dependencies: the app uses only the Android framework, which keeps the APK tiny.
dependencies {
}
