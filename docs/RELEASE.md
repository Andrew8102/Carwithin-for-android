# 自动构建与发布

## 触发方式

`.github/workflows/release.yml` 提供两种触发：

1. **打标签推送**（推荐）：本地执行 `git tag v0.1.0 && git push origin v0.1.0`
2. **手动触发**：GitHub 仓库 → Actions → `Release APK` → Run workflow

两者都会构建 release APK，创建 GitHub Release 并上传 APK。

## 签名配置

默认使用 Android debug 密钥签名（可直接 `adb install`，但不能作为正式发布密钥）。
如需用自己的密钥，在 GitHub 仓库 → Settings → Secrets and variables → Actions 里添加 4 个 secret：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | 签名 keystore 文件的 base64 文本（`base64 -w0 release.jks`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 签名别名 |
| `KEY_PASSWORD` | 签名密钥密码 |

配置后，CI 会把 keystore 解码为仓库根目录的 `release.jks`，`app/build.gradle.kts`
自动读取环境变量完成签名。`.gitignore` 已排除 `*.jks`，本地文件不会被提交。

## 版本号

`app/build.gradle.kts` 中的 `versionCode` / `versionName` 目前是固定的 `1` / `0.0.1-scaffold`。
发布正式版本前请手动递增，或在 CI 中根据标签注入（尚未实现）。

## 验证

推送 workflow 后：

- 打开 GitHub 仓库 → Actions 查看构建状态
- 构建成功后 → Releases 页面查看 APK 附件
- 调试模式下 `./gradlew :app:assembleDebug` 仍可用
