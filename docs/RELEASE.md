# 自动构建与发布

## 三种触发方式

`.github/workflows/release.yml` 覆盖三种场景：

| 触发 | 产物 | 版本号 | 发布位置 |
| --- | --- | --- | --- |
| **推送 `v*` tag**（正式发版） | 正式版 | tag 去掉 `v`，如 `v0.3.0` → `0.3.0` | 独立 Release（非 prerelease） |
| **推送 main**（日常开发） | 开发预览版 | `carwithin.version` + `-dev.<构建号>`，如 `0.3.0-dev.42` | 滚动更新 `dev-latest` prerelease |
| **手动触发** | 填了 tag → 正式版；留空 → 预览版 | 同上 | 同上 |

正式发版：

```bash
git tag v0.3.0 && git push origin v0.3.0
```

> `dev-latest` 是 **pre-release**，GitHub 的 `/releases/latest` 接口和仓库首页的
> 「Latest」标记都会跳过 prerelease，所以首页仍显示最近一个正式版。要拿最新预览包，
> 直接进 Releases 页面找 **开发预览版 · 自动构建** 那一条。
> `pull_request` 只跑 `android.yml` 做编译校验，不产出 Release。

## 版本号规则

版本号全部自动推导，无需手改 `build.gradle.kts`：

- **正式版**：版本号 = git tag（`RELEASE_TAG` 由 CI 注入），`v1.2.5` → `1.2.5`。
- **预览版**：基线取 `gradle.properties` 里的 `carwithin.version`，加上 `-dev.<构建号>`。
  若该版本不大于最近一个 tag（说明这版已经发过了），自动把 patch +1，
  所以预览版永远比最近发布版本新，不会出现"版本号一直不更新"。
- `versionCode = major*10000 + minor*100 + patch`（`0.3.0` → `300`）；
  预览版与同基线正式版 `versionCode` 一致，两者可以互相覆盖安装。
- 想给下一轮开发指定新基线时，改 `gradle.properties` 的 `carwithin.version` 即可。

CI 日志里的 `Show APK version` 步骤会打印真正打进 APK 的 `versionName`/`versionCode`，
`Verify APK signature` 步骤会用 `apksigner` 打印签名证书，可据此确认是否正式签名。

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

### 用 API 自动写入 Secrets

想免手点，可用 REST API 写入（需要 **fine-grained PAT 勾选 `Secrets: Read and write`**，
或 classic PAT 带 `repo` 权限）：

```bash
# 1. 取仓库公钥
curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/<owner>/<repo>/actions/secrets/public-key
# 2. 用返回的 key 做 libsodium sealed box 加密后 PUT
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/<owner>/<repo>/actions/secrets/KEY_ALIAS \
  -d '{"encrypted_value":"<base64>","key_id":"<key_id>"}'
```

> 踩坑记录：只有 `Actions: write`（能触发 workflow）不代表能写 Secrets，
> 缺 `Secrets: Read and write` 会返回 `403 Resource not accessible by personal access token`。

## 验证

推送 workflow 后：

- 打开 GitHub 仓库 → Actions 查看构建状态
- 构建成功后 → Releases 页面查看 APK 附件（正式版看 `v*`，预览版看 `dev-latest`）
- 调试模式下 `./gradlew :app:assembleDebug` 仍可用

