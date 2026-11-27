# 版权与第三方声明

Carwithin-for-android
Copyright (C) 2026 Andrew8102 及项目贡献者

本项目源代码以 **GNU Affero General Public License v3.0 only（AGPL-3.0-only）** 发布。
完整许可证文本见 [`LICENSE`](LICENSE)。本项目不包含任何专有 SDK 或厂商密钥。

## 移植与参考来源

### CarWithin（AGPL-3.0-only）

- 仓库：https://github.com/xmz28/CarWithin
- 用途：极核 AE4 MAX2+ 6.2 寸 PXC/TCP 协议实现参考
  （`App/Zeeho62Legacy.swift`、`App/TailingIdentity.swift`、`App/TailingDiscovery.swift`）
- 许可证原文：`third_party_licenses/CarWithin-AGPL-3.0.txt`
- 上游第三方声明：`third_party_licenses/CarWithin-THIRD_PARTY_NOTICES.txt`
- 许可影响：其 AGPL-3.0 代码被移植进本项目，因此本项目整体按 AGPL-3.0-only 分发。

### CarNine-Ninebot（Apache-2.0）

- 仓库：https://github.com/xiaolu5hao/CarNine-Ninebot
- 用途：CarLife 车机端七端口收流、Wi-Fi Direct 选网、前台服务结构参考
- 许可证原文：`third_party_licenses/CarNine-APACHE-2.0.txt`
- 许可影响：Apache-2.0 与 AGPL-3.0 兼容；合入其代码时保留原始版权与许可声明。

### ninebot_pro（参考，未使用代码）

- 仓库：https://github.com/xmz28/ninebot_pro
- 用途：确认安卓端可驱动极核 6.2 寸仪表（1024×480 H.264/TCP、25 FPS、2500 kbps）。
- 本项目未复制其代码。

## 商标与官方关系

ZEEHO（极核）、九号 Ninebot、OPPO、一加、realme、小米、vivo、华为、CarPlay、CarLife、
CarWith、Car+、CarLink、HiCar 等名称与商标归各自权利人所有。

本项目为独立社区实验项目，**未获得上述任何公司的授权、背书或官方支持**，
也不代表上述公司的立场。

## 合规提示

- 若分发包含本项目代码的二进制（APK），须同时提供对应源代码或书面获取方式，以满足 AGPL-3.0 第 13 条。
- 不得将厂商专有 SDK（如 `icar-sdk.dex`）提交进本仓库；如本地使用，请自行确认授权。
- 逆向协议仅用于个人设备互操作的社区研究，请勿用于商业产品侵犯他人权益。
