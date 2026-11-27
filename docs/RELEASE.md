# 自动构建与发布

## 触发方式

`.github/workflows/release.yml` 提供两种触发：

1. **打标签推送**（推荐）：本地执行 `git tag v0.3.0 && git push origin v0.3.0`
2. **手动触发**：GitHub 仓库 → Actions → `Release APK` → Run workflow

两者都会构建 release APK，创建 GitHub Release 并上传 APK。

## 签名配置（正式签名）

未配置密钥时，CI 退回 **debug 签名**：能安装，但系统会提示「未验证的应用」，
debug 密钥 365 天到期且不同机器签名不一致，覆盖升级会失败。
正式分发请配置下面 5 个 Secret，CI 即用正式密钥签名，安装不受限。

在 GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret** 添加：

| Secret | 值 | 说明 |
| --- | --- | --- |
| `KEYSTORE_BASE64` | 见下方生成物 | keystore 文件的 base64 文本（`base64 -w0 release.jks`） |
| `KEYSTORE_PASSWORD` | `CarWithin2026Release` | keystore 库密码 |
| `KEY_ALIAS` | `carwithin-release` | 签名别名 |
| `KEY_PASSWORD` | `CarWithin2026Release` | 签名密钥密码 |
| `KEYSTORE_TYPE` | `PKCS12` | 密钥库类型（openssl 生成的为 PKCS12） |

> 上面这组值来自本项目生成的自签名 release 密钥（RSA-2048，有效期至 2056 年）。
> 私钥保存在本地 `signing/`，已被 `.gitignore` 排除，**不会提交到仓库**。
> 若你更想用自己的密钥，按下面命令重新生成后替换全部 5 个 Secret 即可：
>
> ```bash
> openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
> openssl req -new -x509 -key private.pem -out cert.pem -days 10950 \
>   -subj "/C=CN/O=YourName/CN=YourApp"
> openssl pkcs12 -export -inkey private.pem -in cert.pem \
>   -name your-alias -out release.jks -passout pass:你的密码
> base64 -w0 release.jks
> ```

配置后，CI 将 `KEYSTORE_BASE64` 解码为仓库根目录的 `release.jks`，
`app/build.gradle.kts` 读取环境变量完成正式签名。

## 版本号

`app/build.gradle.kts` 中的 `versionCode` / `versionName` 当前为 `3` / `0.3.0`。
发布新版本前手动递增，或在 CI 中根据标签注入（尚未实现）。

## 验证

推送 workflow 后：

- 打开 GitHub 仓库 → Actions 查看构建状态
- 构建成功后 → Releases 页面查看 APK 附件
- 调试模式下 `./gradlew :app:assembleDebug` 仍可用
