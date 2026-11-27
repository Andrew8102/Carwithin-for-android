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
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        // CI 中把 release.jks 解码到仓库根目录后，这里自动接管正式签名。
        //仓库密钥为 PKCS12（openssl 生成），需显式 storeType；JKS 旧密钥也兼容。
        val keystoreFile = rootProject.file("release.jks")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                storeType = System.getenv("KEYSTORE_TYPE") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // 有正式密钥就用正式签名；没有则退回 debug 签名，保证 CI 仍能产出可安装的包。
            //注意：debug 签名有有效期且系统会提示「未验证」，正式分发请配置 Secrets。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
