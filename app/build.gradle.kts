plugins {
    id("com.android.application")
}

/**
 * 版本号从发布 tag 自动推导，避免每次手动改 build.gradle.kts 导致 APK 内版本号不变。
 * 优先级：RELEASE_TAG 环境变量（CI 打 tag 时注入） > git describe > 默认 0.3.0。
 * versionCode 由语义化版本计算：major*10000 + minor*100 + patch（0.3.0 -> 300）。
 */
data class AppVersion(val name: String, val code: Int)

fun resolveVersion(): AppVersion {
    val raw = System.getenv("RELEASE_TAG")
        ?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()
        ?: "v0.3.0"

    val clean = raw.trim().removePrefix("v").substringBefore("-")
    val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return AppVersion(clean.ifBlank { "0.3.0" }, 300)
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return AppVersion("$major.$minor.$patch", major * 10000 + minor * 100 + patch)
}

val appVersion = resolveVersion()

android {
    namespace = "com.github.carwithinforandroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.carwithinforandroid"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersion.code
        versionName = appVersion.name
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
