<div align="center">

# CarWithin for Android

**简体中文**

**把安卓手机互联协议的画面，带到极核 AE4 MAX2+ 6.2 寸仪表上。**

[![Android 骨架版](https://img.shields.io/badge/Android-0.0.1--scaffold-2E7D32?style=flat-square)](https://github.com/Andrew8102/Carwithin-for-android/releases/tag/v0.0.1-scaffold)
[![连接-仪表_Wi--Fi](https://img.shields.io/badge/连接-仪表_Wi--Fi-546E7A?style=flat-square)](#工作原理)
[![协议-PXC_TCP](https://img.shields.io/badge/协议-PXC_TCP-1565C0?style=flat-square)](#仪表与传输)
[![许可-AGPL_3.0](https://img.shields.io/badge/许可-AGPL_3.0-6A1B9A?style=flat-square)](#许可证)

Android 手机互联（OPPO Car+、ICCOA CarLink、华为 HiCar）→ H.264 视频帧 → 极核 PXC/TCP 输出 → 仪表出画。

[下载安装](#下载安装) · [界面预览](#界面预览) · [功能矩阵](#功能矩阵) · [开始使用](#开始使用) · [工作原理](#工作原理) · [常见问题](#常见问题)

</div>

> **当前状态：项目骨架。** 极核协议帧封装、RSA 身份、网段发现已落地；TCP 会话协商、CarLife 收流、转码与真机验证尚未完成。
> 现在安装后可查看状态面板，**还不会真正投屏**。请把它当作协议移植的起点，而不是已可用的投屏工具。

## 下载安装

| 平台与版本 | 系统要求 | 下载入口 | 安装方式 |
| --- | --- | --- | --- |
| **Android 0.0.1-scaffold** | Android 10+（API 29） | [下载 APK](https://github.com/Andrew8102/Carwithin-for-android/releases/download/v0.0.1-scaffold/carwithin-for-android-main.apk) · [发布说明](https://github.com/Andrew8102/Carwithin-for-android/releases/tag/v0.0.1-scaffold) | debug 签名，可直接安装 |

- APK 由 GitHub Actions 自动构建，每次 `v*` tag 推送或手动触发 Release workflow 都会生成新包。构建与发布说明见 [docs/RELEASE.md](docs/RELEASE.md)。
- 当前包为骨架状态面板，用于验证安装、界面与引擎状态显示；**不包含投屏功能**。
- 同签名版本可覆盖安装；不同签名请先卸载旧版。

## 界面预览

当前版本的深色状态面板：

| 引擎状态与链路 | 来源与输出进度 |
| --- | --- |
| 顶部展示引擎运行状态、投屏链路三步流程（手机互联 → H.264 帧 → 极核输出），状态按 IDLE / RUNNING / ERROR 变色。 | 手机互联来源（Car+ / ICCOA / HiCar）与仪表输出（分辨率、帧率、端口、触摸反控）分卡片展示，附本机测试提示。 |

界面由代码构建（`MainActivity.java`），无外部 UI 依赖；主题为深色 Material 风格。

## 功能矩阵

### 手机互联来源

| 功能 | OPPO / 一加 / realme | 小米 / 红米 / vivo | 华为 / 荣耀 |
| --- | --- | --- | --- |
| 协议 | Car+（CarLife 七端口） | ICCOA CarLink | HiCar |
| 接收栈 | 槽位已建，`CarLifeLink` 待移植 | 槽位已建，依赖专有 SDK | 仅预留目录 |
| 当前状态 | 阶段一主线 | 阶段二，见 [ICCOA 现状](docs/ICCOA-STATUS.md) | 阶段三 |

Car+ 与 CarWith **不是同一套协议**。CarNine 的接收栈只认 ColorOS 的 `com.oplus.ocar`，不能直接拿来做小米 CarWith。

### 仪表输出

| 仪表 | 分辨率 | 编码与传输 | 默认帧率 | 当前状态 |
| --- | --- | --- | --- | --- |
| 极核 AE4 MAX2+ 6.2 寸 | 1024×480 | H.264 / PXC TCP | 25 FPS | 协议骨架已落地，待真机验证 |
| 极核 AE4i Pro 2026 款 | 2048×968 | PXC TCP 10920/10921/10922 | 未知 | 不支持，CarWithin issue #64 未解决 |
| 极核 AE4 Pro 25 款 / AE6 | 多种 | 多种 | 未知 | 不支持，连接有未结问题 |
| 九号各型号仪表 | 848×480 等 | UDP 5012 | 20 FPS | 不在本项目范围，请用 [CarNine](https://github.com/xiaolu5hao/CarNine-Ninebot) |

## 开始使用

当前骨架版本：

1. 下载并安装上面的 APK；首次打开会看到状态面板。
2. 点击「刷新状态」查看引擎当前状态（IDLE / RUNNING / ERROR）。
3. 真机验证需在仪表上手动开启投屏，手机与仪表处于同一 Wi-Fi。
4. 需要回传日志时，按 [docs/TEST-PLAN.md](docs/TEST-PLAN.md) 脱敏后提供。

后续版本（投屏功能接通后）的操作步骤：

1. 手机打开 Car+ 并进入导航。
2. App 内选择来源「OPPO Car+」，扫描并连接仪表网络。
3. 在仪表上确认投屏；画面由 `CastEngine` 转发到极核输出端。
4. 点「停止」结束投屏。极核老协议不支持仪表触摸反控，操作留在手机上。

## 工作原理

```
手机互联来源 (PhoneVideoSource)
  ├─ CarLife 接收（OPPO Car+，阶段一）
  ├─ ICCOA CarLink（小米/vivo，阶段二，需专有 SDK）
  └─ HiCar（华为，阶段三）
        ↓ VideoFrame (Annex-B H.264 AU)
CastEngine
        ↓
ZeehoOutput → ZeehoConnection → 极核仪表
```

本项目把三段已有能力接起来：

| 已有项目 | 做到了什么 | 缺什么 |
| --- | --- | --- |
| [CarNine-Ninebot](https://github.com/xiaolu5hao/CarNine-Ninebot) | 安卓端冒充 CarLife 车机，接收 OPPO Car+ 的 H.264 画面 | 输出端只支持九号私有 UDP 5012 |
| [CarWithin](https://github.com/xmz28/CarWithin) | iOS 端完整逆向极核 6.2 寸 PXC/TCP 输出协议 | 依赖 iOS 私有接口，无法移植安卓 |
| [ninebot_pro](https://github.com/xmz28/ninebot_pro) | 安卓端已验证可驱动极核 6.2 寸仪表（1024×480 @25 FPS） | 画面来自本机屏幕采集，不接 Car+ / ICCOA |

## 仪表与传输

极核 AE4 MAX2+ 6.2 寸不走九号 UDP 5012，而是 PXC/TCP 四端口，手机监听、仪表反向连回：

| 端口 | 方向 | 用途 |
| --- | --- | --- |
| 10930 | 手机 → 仪表 | 发现与投屏请求（0x70000010 / 0x70000011） |
| 10922 | 仪表 → 手机 | 会话控制，JSON 命令 |
| 10711 | 仪表 → 手机 | 媒体控制，尺寸、身份回显、启动 |
| 10710 | 仪表 → 手机 | 视频拉帧（0x72）与 H.264 AU |

**连接。** 手机通过 10930 发起投屏请求，仪表接受后反向连接 10922 / 10711 / 10710。仪表在控制通道报 `HUName` 与 `HUID`，手机用 RSA-2048 对 HUID 原始字节做 PKCS#1 v1.5 签名并回传 X.509 公钥。

**画面处理。** 媒体通道协商 1024×480 / H.264；视频通道由仪表发 `0x72` 逐帧拉取，手机回 Annex-B H.264 AU。默认目标 25 FPS、2500 kbps（与 ninebot_pro 安卓端一致）。

**唤醒与反控。** 不需要 BLE 唤醒，投屏在仪表或官方 App 中手动开启。极核老协议不支持仪表触摸反控（媒体通道 `0x20` 明确不执行）。

完整协议摘录见 [docs/ZEEHO-PROTOCOL.md](docs/ZEEHO-PROTOCOL.md)，架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 开发路线图

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、协议帧、RSA 身份、网段发现 | 已落地 |
| M1 | 极核协议闭环：静态测试图推到仪表出画 | 进行中 |
| M2 | OPPO Car+ 接通：CarLifeLink 移植与转码兜底 | 待开始 |
| M3 | 稳定性：保活、断线重连、日志与统计 | 待开始 |
| M4 | ICCOA CarLink：小米 / vivo | 受专有 SDK 阻塞 |
| M5 | 华为 HiCar | 规划中 |

详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 常见问题

**现在装了能投屏吗？** 不能。当前是骨架状态面板，用于验证安装、界面与引擎状态。投屏功能正在移植中。

**扫不到仪表？** 先确认仪表已手动开启投屏，手机与仪表在同一 Wi-Fi。后续版本会提供 Wi-Fi Direct 扫描。

**连接成功但没有画面？** 先核对仪表型号与分辨率预设。极核 AE4i Pro 2026 款（2048×968）不在支持范围；九号仪表请用 CarNine。

**为什么 ICCOA 还不做？** MiAuto / Car++ 等接收端的 ICCOA 协议依赖专有 `icar-sdk.dex`，公开仓库只有编译桩，拿不到源码。见 [docs/ICCOA-STATUS.md](docs/ICCOA-STATUS.md)。

**Car+ 和 CarWith 能通用吗？** 不能。Car+ 是 ColorOS 的 `com.oplus.ocar`，CarWith 属 ICCOA 体系，接收栈不同。

**APK 是谁签名的？** 默认用 Android debug 密钥签名，可直接安装。若配置了仓库 Secrets（`KEYSTORE_BASE64` 等），CI 会用你自己的密钥签名，见 [docs/RELEASE.md](docs/RELEASE.md)。

## 参与开发

欢迎真机验证、协议取证、代码移植与文档改进：

- [贡献指南](CONTRIBUTING.md)：协议取证、代码规范与提交方式
- [真机测试计划](docs/TEST-PLAN.md)：需要回传的日志、抓包与脱敏清单
- [自动构建与发布](docs/RELEASE.md)：打 tag 自动构建 APK 并发布到 Releases

## 许可证

**AGPL-3.0-only**。完整许可证见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md) 与 `third_party_licenses/`，合规义务说明见 [docs/LEGAL.md](docs/LEGAL.md)。

项目移植了 CarWithin（AGPL-3.0）的极核协议实现，组合工程须按 AGPL-3.0 分发；CarNine（Apache-2.0）的声明单独保留。

本项目是独立社区实验，与小米、OPPO、vivo、华为、ICCOA、极核、九号及上述项目作者无官方关联。
