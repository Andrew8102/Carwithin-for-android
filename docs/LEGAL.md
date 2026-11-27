# 许可与合规说明

## 本项目的许可证

本项目整体采用 **GNU Affero General Public License, Version 3 only（AGPL-3.0-only）**。

- 完整文本：[`LICENSE`](../LICENSE)（与 `third_party_licenses/CarWithin-AGPL-3.0.txt` 一致）
- 版权与第三方声明：[`NOTICE.md`](../NOTICE.md)

### 为什么是 AGPL-3.0

`outputs/zeeho/` 中的极核协议实现移植自 CarWithin（`xmz28/CarWithin`，AGPL-3.0-only）。
AGPL 具有强传染性：只要分发包含其代码的衍生作品，整体须以 AGPL-3.0 授权。
因此即使其他模块来源更宽松，本仓库整体仍按 AGPL-3.0-only 发布。

### 分发 APK 时的义务（AGPL 第 13 条）

如果你编译并分发包含本项目代码的 APK（尤其是通过网络提供服务）：

1. 随二进制提供完整对应源代码，或提供显著、免费、可长期访问的源代码获取地址。
2. 保持所有版权声明、许可证文本与 `NOTICE.md` 不被移除。
3. 修改过的文件标明修改日期与修改者。
4. 不得把 AGPL 代码重新授权为专有许可。

### 与 Apache-2.0 代码的兼容

CarNine 为 Apache-2.0，可与 AGPL-3.0 组合；合入其代码时：

- 保留 `third_party_licenses/CarNine-APACHE-2.0.txt`；
- 在被修改文件中保留原始版权/许可头；
- 在 `NOTICE.md` 中记录来源与用途。

## 未包含在仓库中的内容

- **厂商专有 SDK**：ICCOA `icar-sdk.dex`、HiCar SDK 等均未包含，也不得提交。
- **厂商证书与密钥**：无 CarPlay/CarLife/CarWith/HiCar 的官方证书或私钥。
- **参考仓库源码**：`reference/` 目录已加入 `.gitignore`，仅本地对照使用。

## 身份与"证书"说明

`outputs/zeeho/ZeehoIdentity.java` 实现的不是 X.509 证书链，而是**设备身份密钥对**：

- 本机生成并持久化 RSA-2048 密钥对（PKCS#8 私钥 / X.509 SubjectPublicKeyInfo 公钥）与 UUID 形式的 phoneID；
- 对仪表报出的 `HUID` **原始字节**做 PKCS#1 v1.5 签名（不使用 SHA 摘要）；
- 将公钥 DER base64 后放入控制通道 JSON，供仪表校验。

该行为来自对 CarWithin 的实现移植，仅用于与自有仪表互操作。相关字段若需真机校正，
请在 issue 中描述，不要提交任何真实私钥或抓包中的敏感凭据。

## 商标声明

本项目为独立社区实验，与小米、OPPO、vivo、华为、ICCOA、极核、九号、CarWithin、
CarNine 及相应商标权利人无官方关联，未获授权或背书。
