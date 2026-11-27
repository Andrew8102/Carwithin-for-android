<div align="center">

# CarWithin for Android

**简体中文**

**把安卓手机的屏幕，投到极核 AE4 MAX2+ 6.2 寸仪表上。**

[![Android 10+](https://img.shields.io/badge/Android-10%2B-2E7D32?style=flat-square)](#下载安装)
[![连接-仪表_Wi--Fi](https://img.shields.io/badge/连接-仪表_Wi--Fi-546E7A?style=flat-square)](#工作原理)
[![协议-PXC_TCP](https://img.shields.io/badge/协议-PXC_TCP-1565C0?style=flat-square)](#仪表与传输)
[![许可-AGPL_3.0](https://img.shields.io/badge/许可-AGPL_3.0-6A1B9A?style=flat-square)](#许可证)

手机录屏 → H.264 编码（1024×480@25）→ 极核 PXC/TCP → 仪表出画。任何安卓 App 的画面都能投，包括导航、音乐与各类手机互联界面。

[下载安装](#下载安装) · [界面预览](#界面预览) · [功能矩阵](#功能矩阵) · [开始使用](#开始使用) · [工作原理](#工作原理) · [常见问题](#常见问题)

</div>

> **工作原理与官方极核 App 一致**：点击投屏后录制手机屏幕内容，实时推到仪表。本项目是独立实现，需要真机验证；协议来自 CarWithin 逆向，已按 Swift 原文逐项移植。

## 下载安装

| 平台与版本 | 系统要求 | 下载入口 | 安装方式 |
| --- | --- | --- | --- |
| **Android 0.1.0** | Android 10+（API 29），建议 Android 13+ | [最新 Release](https://github.com/Andrew8102/Carwithin-for-android/releases/latest) | debug 签名，可直接安装 |

- APK 由 GitHub Actions 自动构建，`v*` tag 推送或手动触发 Release workflow 生成新包，见 [docs/RELEASE.md](docs/RELEASE.md)。
- 默认 debug 签名可直接安装；同签名版本可覆盖，不同签名请先卸载旧版。
- 首次投屏会弹出系统录屏授权，必须点「允许」。

## 界面预览

深色控制台，纯代码构建界面（`MainActivity.java`），无外部 UI 依赖。

| 投屏控制与实时状态 | 链路、日志与仪表信息 |
| --- | --- |
| 「开始投屏 / 停止投屏」按钮、实时状态（IDLE / 协商中 / STREAMING / ERROR）、来源与输出细节、仪表 IP。 | 三步链路（屏幕采集 → H.264 编码 → 极核输出）、运行日志、协议与兼容性说明。 |

## 功能矩阵

### 画面来源

| 来源 | 支持情况 | 说明 |
| --- | --- | --- |
| **手机屏幕**（MediaProjection） | ✅ 已实现 | 录制整个屏幕，任意 App 画面 |
| OPPO Car+ 界面 | ✅ 通过录屏间接支持 | 打开 Car+ 后录屏即可投，无需逆向 Car+ 协议 |
| 小米 CarWith / 华为 HiCar 界面 | ✅ 通过录屏间接支持 | 同上，只要能在手机上显示 |
| 直连 Car+ / ICCOA / HiCar 协议 | ❌ 未实现 | 需厂商专有 SDK，见 [ICCOA 现状](docs/ICCOA-STATUS.md) |

### 仪表输出

| 仪表 | 分辨率 | 编码与传输 | 默认帧率 | 当前状态 |
| --- | --- | --- | --- | --- |
| 极核 AE4 MAX2+ 6.2 寸 | 1024×480 | H.264 / PXC TCP | 25 FPS | 协议完整实现，待真机验证 |
| 极核 AE4i Pro 2026 款 | 2048×968 | PXC TCP | 未知 | 不支持，CarWithin issue #64 未解决 |
| 极核 AE4 Pro 25 款 / AE6 | 多种 | 多种 | 未知 | 未适配 |
| 九号各型号仪表 | 848×480 等 | UDP 5012 | 20 FPS | 不在范围，请用 [CarNine](https://github.com/xiaolu5hao/CarNine-Ninebot) |

## 开始使用

1. 在仪表或极核官方 App 上**开启投屏模式**（等待手机连接）。
2. 手机连接仪表的 Wi-Fi / Wi-Fi Direct 网络。
3. 打开本 App，点「开始投屏」。
4. 系统弹出录屏授权，点「允许」。
5. App 自动发现仪表 IP、完成 RSA 身份握手，状态变为 **STREAMING** 即开始出画。
6. 在手机上正常操作导航 / 音乐等 App，仪表同步显示。
7. 点「停止投屏」结束。极核老协议不支持仪表触摸反控，操作留在手机上。

> 若长时间停在「协商中」：确认仪表投屏已开启且未被其他手机占用，然后退出仪表投屏重新开启再试。

## 工作原理

```
手机屏幕 (MediaProjection 录屏)
        ↓
MediaCodec 编码 H.264 Annex-B（1024×480 · 25 FPS · 2500 kbps）
        ↓ VideoFrame
CastEngine → ZeehoOutput → ZeehoConnection（PXC/TCP 四端口）
        ↓
极核 AE4 MAX2+ 仪表出画
```

本项目的协议来自三个已有工作的交叉验证：

| 已有项目 | 做到了什么 | 本项目怎么用 |
| --- | --- | --- |
| [CarWithin](https://github.com/xmz28/CarWithin) | iOS 端完整逆向极核 6.2 寸 PXC/TCP 输出协议 | 逐行移植 `Zeeho62Legacy.swift` 的握手、媒体协商与视频帧封装到 Java |
| [ninebot_pro](https://github.com/xmz28/ninebot_pro) | 安卓端已验证可驱动极核 6.2 寸（1024×480 @25 FPS） | 确认安卓屏幕采集 + 该规格可行，沿用其参数 |
| [CarNine-Ninebot](https://github.com/xiaolu5hao/CarNine-Ninebot) | 安卓端接收 OPPO Car+ 的 H.264 | 参考其安卓工程结构；本项目改用更通用的屏幕采集 |

## 仪表与传输

极核 AE4 MAX2+ 6.2 寸不走九号 UDP 5012，而是 PXC/TCP 四端口，手机监听、仪表反向连回：

| 端口 | 方向 | 用途 |
| --- | --- | --- |
| 10930 | 手机 → 仪表 | 发现与投屏请求（0x70000010 / 0x70000011） |
| 10922 | 仪表 → 手机 | 会话控制，JSON 命令 |
| 10711 | 仪表 → 手机 | 媒体控制，尺寸、身份回显、启动 |
| 10710 | 仪表 → 手机 | 视频拉帧（0x72）与 H.264 AU |

**连接。** 手机扫描网段发现仪表后，通过 10930 发起投屏请求；仪表接受后反向连接 10922 / 10711 / 10710。仪表在控制通道报 `HUName` 与 `HUID`，手机用 RSA-2048 对 HUID 原始字节做 PKCS#1 v1.5 签名并回传 X.509 公钥。

**画面处理。** 媒体通道协商 1024×480 / H.264；视频通道由仪表发 `0x72` 逐帧拉取，手机回 Annex-B H.264 AU（首帧带 SPS/PPS）。默认目标 25 FPS、2500 kbps。

**唤醒与反控。** 不需要 BLE 唤醒，投屏在仪表或官方 App 中手动开启。极核老协议不支持仪表触摸反控（媒体通道 `0x20` 明确不执行）。

完整协议摘录见 [docs/ZEEHO-PROTOCOL.md](docs/ZEEHO-PROTOCOL.md)，架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 开发路线图

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、协议帧、RSA 身份、网段发现 | 已落地 |
| M1 | 极核协议闭环：屏幕采集 → 仪表出画 | 已实现，待真机验证 |
| M2 | 稳定性：断线重连、切后台保活、日志与统计 | 进行中 |
| M3 | 其他极核型号 / 固件适配 | 待真机取证 |
| M4 | ICCOA CarLink / 华为 HiCar 直连 | 受专有 SDK 阻塞 |

详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 常见问题

**装了就能投屏吗？** 代码链路已完整（录屏、编码、握手、推流都实现了），但**尚未在真机上验证出画**。需要你实机测试并回传日志；协议按 CarWithin 已验证的 Swift 实现移植，理论上可用。

**扫不到仪表？** 确认仪表已手动开启投屏、手机与仪表在同一 Wi-Fi、未被其他手机占用。Android 13+ 需附近 Wi-Fi 权限，Android 12 及以下需定位权限。

**点了开始但一直「协商中」？** 退出仪表投屏后重新开启，再点开始。若仍失败，查看 App 内运行日志，按 [docs/TEST-PLAN.md](docs/TEST-PLAN.md) 脱敏后回传。

**画面卡顿或花屏？** 码率与帧率按 1024×480@25 / 2500 kbps 设定，与 ninebot_pro 安卓端一致。若仍异常，可能是手机编码器差异，需调整 `ScreenCaptureSource.java` 中的参数。

**支持投 ICCOA / Car+ / HiCar 的内容吗？** 支持「投这些内容在手机屏幕上显示的画面」（通过录屏），但不是直连它们的协议。已在本机打开的任意 App 都能投。

**APK 是谁签名的？** 默认 debug 密钥，可直接安装。配置仓库 Secrets 后 CI 可用自己的密钥签名，见 [docs/RELEASE.md](docs/RELEASE.md)。

## 参与开发

欢迎真机验证、协议取证、代码改进：

- [贡献指南](CONTRIBUTING.md)：协议取证、代码规范与提交方式
- [真机测试计划](docs/TEST-PLAN.md)：需要回传的日志、抓包与脱敏清单
- [自动构建与发布](docs/RELEASE.md)：打 tag 自动构建 APK 并发布到 Releases

## 许可证

**AGPL-3.0-only**。完整许可证见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md) 与 `third_party_licenses/`，合规义务说明见 [docs/LEGAL.md](docs/LEGAL.md)。

项目移植了 CarWithin（AGPL-3.0）的极核协议实现，组合工程须按 AGPL-3.0 分发；CarNine（Apache-2.0）的声明单独保留。

本项目是独立社区实验，与小米、OPPO、vivo、华为、ICCOA、极核、九号及上述项目作者无官方关联。
