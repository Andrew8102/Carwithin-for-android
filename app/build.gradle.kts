plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.carwithinforandroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.carwithinforandroid"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-scaffold"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
