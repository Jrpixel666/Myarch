plugins {
    id("com.android.application")
}

android {
    // The R-class / resource package is fixed; only the applicationId varies
    // per generated app, so MainActivity never has to move.
    namespace = "dev.myarch.forge.gen"
    compileSdk = 35

    defaultConfig {
        applicationId = "{{APP_ID}}"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "{{VERSION_NAME}}"
    }

    buildTypes {
        getByName("debug") {
            // Signed with the standard Android debug keystore (AGP creates
            // ~/.android/debug.keystore on first use), so `assembleDebug`
            // produces an installable, sideloadable APK with no key setup.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
