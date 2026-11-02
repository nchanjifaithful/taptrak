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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
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
