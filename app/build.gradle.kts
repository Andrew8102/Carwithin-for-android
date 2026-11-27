plugins {
    id("com.android.application")
}

/**
 * 版本号自动推导，避免手动改 build.gradle.kts 导致 APK 内版本号不变。
 *
 * 正式版（CI 打 tag 时注入 RELEASE_TAG）：版本号 = tag，如 v0.3.0 -> 0.3.0。
 * 预览版（main 每次推送，不注入 RELEASE_TAG）：版本号 = gradle.properties 的
 * carwithin.version + VERSION_SUFFIX（如 -dev.42）-> 0.3.0-dev.42；
 * 若声明的版本不大于最近 tag（说明已发布过），自动 patch +1，
 * 保证版本号永远比最近发布版本新，不会看起来"没更新"。
 *
 * versionCode 由语义化版本计算：major*10000 + minor*100 + patch（0.3.0 -> 300），
 * 预览版与同基线正式版一致，两者可以互相覆盖安装。
 */
data class AppVersion(val name: String, val code: Int)

fun semverParts(raw: String): IntArray {
    val clean = raw.trim().removePrefix("v").substringBefore("-")
    val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
    return intArrayOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
}

fun codeOf(parts: IntArray): Int = parts[0] * 10000 + parts[1] * 100 + parts[2]

fun gitDescribeTag(): String? = runCatching {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
}.getOrNull()

fun resolveVersion(): AppVersion {
    val suffix = System.getenv("VERSION_SUFFIX")?.trim()?.takeIf { it.isNotBlank() } ?: ""
    val envTag = System.getenv("RELEASE_TAG")?.takeIf { it.isNotBlank() }
    val declared = project.findProperty("carwithin.version")?.toString()?.takeIf { it.isNotBlank() } ?: "0.3.0"

    var parts = semverParts(envTag ?: declared)

    if (envTag == null) {
        val latest = gitDescribeTag()?.let { semverParts(it) }
        if (latest != null && codeOf(parts) <= codeOf(latest)) {
            parts = intArrayOf(latest[0], latest[1], latest[2] + 1)
        }
    }

    return AppVersion("${parts[0]}.${parts[1]}.${parts[2]}$suffix", codeOf(parts))
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
        getByName("debug") {
            // 调试包与正式版同包名（便于用同一份配置/授权复现问题）。
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
            // 有正式密钥时 debug 包也用同一把钥匙签：
            // CI 每次构建都会新建 debug.keystore，签名一变就必须先卸载才能升级，
            // 那样每次装包都要丢数据、重给权限；用正式密钥后 debug ↔ release 可互相覆盖。
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
