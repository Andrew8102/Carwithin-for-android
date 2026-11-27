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

    signingConfigs {
        // CI 中把 release.jks 解码到仓库根目录后，这里自动接管签名。
        val keystoreFile = rootProject.file("release.jks")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("release") {
            // 有签名配置就用它；没有则由 Android 插件产出未签名 APK（CI 里仍可上传）。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
