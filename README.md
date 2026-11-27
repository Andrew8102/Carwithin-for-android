<div align="center">

# CarWithin for Android

**简体中文**

**把安卓手机的屏幕，投到极核 AE4 MAX2+ 6.2 寸仪表上。**

[![Platform: Android 10+](https://img.shields.io/badge/Android-10%2B-blue)](#下载安装) [![Status: beta](https://img.shields.io/badge/status-beta-orange)](#工作原理) [![Release](https://img.shields.io/github/v/release/Andrew8102/Carwithin-for-android?label=release)](https://github.com/Andrew8102/Carwithin-for-android/releases/latest)

手机录屏 → H.264 编码 → PXC/TCP → 仪表出画。主推不依赖车机配对的录屏路线；另含实验性 CarLife 直连、多车型调试参数、测试图与实时帧率统计。

[下载安装](#下载安装) · [界面预览](#界面预览) · [功能矩阵](#功能矩阵) · [开始使用](#开始使用) · [调试功能](#调试功能) · [工作原理](#工作原理) · [常见问题](#常见问题)

</div>

> **工作原理与官方极核 App 一致**：点击投屏后录制手机屏幕内容，实时推到仪表。本项目是独立实现，需要真机验证；协议来自 CarWithin 逆向，已按 Swift 原文逐项移植。

## 下载安装

| 平台与版本 | 系统要求 | 下载入口 | 安装方式 |
| --- | --- | --- | --- |
| **Android 0.3.0**（正式版） | Android 10+（API 29），建议 Android 13+ | [最新 Release](https://github.com/Andrew8102/Carwithin-for-android/releases/latest) | 正式签名，可直接安装 |
| **开发预览版**（每个提交自动构建） | 同上 | [dev-latest](https://github.com/Andrew8102/Carwithin-for-android/releases/tag/dev-latest) | 同一套签名，可覆盖安装；版本号形如 `0.3.1-dev.42` |
| **debug 调试版**（同一入口附带） | 同上 | [dev-latest](https://github.com/Andrew8102/Carwithin-for-android/releases/tag/dev-latest) 里的 `CarWithin-debug.apk` | debug 签名、可连 adb；**装前必须先卸载正式版**，版本号形如 `0.3.2-dev.42-debug` |

- APK 由 GitHub Actions 自动构建：推 `v*` tag 发正式版，推 main 自动更新 `dev-latest` 预览版，见 [docs/RELEASE.md](docs/RELEASE.md)。
- 正式签名（RSA-2048）；若你此前装过 debug 签名的旧包，请先卸载再装。
- 只有「录屏投屏」会弹出系统录屏授权；CarLife 投屏与测试图无需录屏授权。

## 界面预览

深色控制台，纯代码构建界面（`MainActivity.java`），无外部 UI 依赖。

深色控制台，共七个卡片：车机界面拉起、投屏控制、实时状态、帧率统计、车型调试、运行日志、版本信息。

| 区域 | 内容 |
| --- | --- |
| ① 拉起车机互联界面 | 包名 / Activity 输入、「拉起界面」「诊断」（安装/版本/7 端口状态）「检测安装」、**「枚举组件」「尝试进 CarLife」**、自动拉起勾选框 |
| ② 投屏控制 | **录屏投屏（推荐）**、CarLife 直连（零转码）、测试图验证出画、停止投屏 |
| **Car+ 本地预览** | 收到 CarLife 帧就地解码显示，**不需要极核在线**也能验证链路是否通 |
| 实时状态 / 帧率统计 | 状态（IDLE / RUNNING_LOCAL_ONLY / STREAMING / ERROR）、来源与输出、采集 / 编码 / 发送 FPS、码率、丢帧 |
| ③ 车型与调试选项 | 车型下拉、分辨率 / 帧率 / 码率 / 发现端口 / 仪表 IP、测试图模式、协议状态说明 |

## 功能矩阵

### 画面来源

| 来源 | 支持情况 | 说明 |
| --- | --- | --- |
| **手机屏幕**（MediaProjection） | ✅ 已实现 | 录制整个屏幕，任意 App 画面 |
| **手机界面录屏**（OPPO Car+ / 小米 CarWith / HiCar 等任意 App） | ✅ **推荐，已实现** | 不依赖车机配对，只要画面能在手机显示就能投 |
| OPPO Car+（CarLife 直连） | ⚠️ 实验性，可自行拉起试探 | 7 端口接收 Car+ 原生 H.264（零转码、免录屏）。**不需要真车机**：Car+ 的 CarLife 组件在本机 `127.0.0.1` 监听，本 App 冒充 HU 连入。前置条件是先把 Car+ 拉进 CarLife 模式，用①「尝试进 CarLife」自动枚举入口试探 |
| ICCOA CarLink 直连 | ❌ 受专有 SDK 阻塞 | 需 `icar-sdk.dex`，见 [ICCOA 现状](docs/ICCOA-STATUS.md) |
| 华为 HiCar 直连 | ❌ 未实现 | 另一套专有生态，暂用录屏路线 |

> **关键修正**：CarLife 的 HU（车机端）**就是你手机上的这个 App**。Car+ 里的 CarLife 组件作为 MD（手机端）在本机回环暴露 7 个 TCP 端口，本 App 连上去握手、协商画布、收 H.264，全程不经过极核。
> 这与 CarNine 在 realme 真机上的验证一致——它配的九号仪表只负责**输出侧**，输入端同样是自己冒充 HU。
> 因此"必须先连一台 ICCOA/CarLife+ 车机"的说法不成立；真正的前提只是**把 Car+ 拉进 CarLife 模式**（不同 ColorOS 版本入口类名不同，故提供「枚举组件」+「尝试进 CarLife」）。
> 如果所有入口都试过仍不监听，再走录屏投屏。

### 仪表输出

| 仪表 | 分辨率 | 编码与传输 | 默认帧率 | 当前状态 |
| --- | --- | --- | --- | --- |
| 极核 AE4 MAX2+ 6.2 寸 | 1024×480 | H.264 / PXC TCP | 25 FPS | 协议完整实现，待真机验证 |
| 极核 AE4i Pro 2026 款 | 2048×968 | PXC TCP | 未知 | 不支持，CarWithin issue #64 未解决 |
| 极核 AE4 Pro 25 款 / AE6 | 多种 | 多种 | 未知 | 未适配 |
| 九号 5 寸 / 7 寸 | 848×480 / 1024×600 | RTP UDP 5012 / KCP | 20 / 25 FPS | 参数预设，协议栈待移植 |
| 台铃 4.3 寸 | 480×272 | JPEG / TCP | 20 FPS | 参数预设，协议栈待移植 |
| 小牛 | 待取证 | 待取证 | 待取证 | 通用占位，需实车抓包 |

## 开始使用

### 通用准备

1. 在仪表或极核官方 App 上**开启投屏模式**（等待手机连接）。
2. 手机连接仪表的 Wi-Fi / Wi-Fi Direct 网络。
3. 打开本 App，在「① 拉起车机互联界面」填写包名（OPPO Car+ 默认 `com.oplus.ocar`），点「拉起界面」。可勾选自动拉起。
4. App 自动发现仪表 IP、完成 RSA 身份握手，状态变为 **STREAMING** 即开始出画。

### 两条投屏路线（② 投屏控制任选其一）

| 路线 | 按钮 | 前置条件 | 特点 |
| --- | --- | --- | --- |
| **录屏投屏（推荐）** | 开始投屏（录屏） | 弹出录屏授权点「允许」 | 通用、不依赖车机配对，任意 App 画面 |
| **CarLife 直连（零转码）** | CarLife 直连 | 先点①「尝试进 CarLife」把 7 端口点亮 | Car+ 原生 H.264、零转码、免录屏；画面先在「本地预览」卡确认 |

点「停止投屏」结束。极核老协议不支持仪表触摸反控，操作留在手机上。

> 若长时间停在「协商中」：确认仪表投屏已开启且未被其他手机占用，然后退出仪表投屏重新开启再试。

> 拉起界面失败：先在手机系统设置里确认 Car+ / CarWith 已安装，点「检测安装」查看已识别的包名；厂商定制系统包名可能不同，可手动填写后拉起。

> **CarLife 停在「等待 Car+」**：说明 7 个端口没监听，也就是 Car+ 还没进 CarLife 模式。
> 先点①「**尝试进 CarLife**」——它会枚举 `com.oplus.ocar` 里所有可疑入口、逐个拉起并每 2.5 秒探一次端口，命中就停。
> 若全部未命中，点「枚举组件」看全量类名，把可疑的填进 Activity 输入框手动试。仍不行再走录屏投屏。

> **「未找到可拉起界面」**：说明 Car+ 没有 LAUNCHER 入口（它是系统组件），且默认的 `com.oplus.ocar.CarlifeActivity` 在当前版本不存在或未导出。
> 点「枚举组件」列出真实类名即可定位；`resolveActivity` 对未导出组件一律返回 null，必须靠枚举才看得到。

## 调试功能

### Car+ 本地预览（不接仪表也能验证）

`CastEngine` 把收到的每一帧先旁路给本地解码器（`H264Decoder`），在「Car+ 本地预览」卡里直接显示，
**完全不需要极核在线**。此时状态显示 `RUNNING_LOCAL_ONLY`，含义是"只采集、不推流"。

这样能把问题分段定位：

| 预览卡现象 | 说明 |
| --- | --- |
| 一直「等待 SPS/PPS」 | CarLife 握手没走完，或画布协商失败 |
| 出画但花屏 | 丢帧或解密不完整，看日志里的「解密失败」计数 |
| 出画正常、仪表黑屏 | 手机侧链路已通，问题在极核推流段（Wi-Fi、握手、型号） |

### debug 调试版

`dev-latest` 里除了正式签名的预览包，还附带 `CarWithin-debug.apk`：

- debug 签名，`versionName` 带 `-debug` 后缀，可 `adb logcat -s CarWithin` 抓全量日志。
- 与正式版**同包名**，安装前必须先卸载正式版（签名不同无法覆盖）。
- 适合排查崩溃堆栈、`NetworkOnMainThreadException`、MediaCodec 初始化失败等只能在 logcat 里看到的问题。

### 测试图验证出画

不依赖录屏授权，用 Canvas 生成彩条 / 棋盘格 / 运动帧编码推流。用于**先确认仪表能出画**，再排查录屏链路问题：

- 在「③ 车型与调试选项」的「测试图模式」选择彩条 / 棋盘格 / 运动帧，点「保存配置」。
- 回到「② 投屏控制」点「用测试图验证出画」。仪表若显示对应图案，说明协议与 Wi-Fi 链路正常。
- 测试图模式下不采集手机屏幕，适合快速验证仪表连接。

### 帧率与码率统计

「帧率与码率」卡片每秒刷新：采集 FPS、编码 FPS、发送 FPS、码率（kbps）、累计丢帧。三者差异可定位瓶颈：

- 采集高、发送低 → 仪表逐帧拉取慢或 Wi-Fi 带宽不足。
- 编码低 → 手机编码器负载高，可降低分辨率 / 帧率 / 码率。
- 全部为 0 → 采集或会话未启动，查看运行日志。

### 多车型调试参数

「③ 车型与调试选项」内置极核 / 九号 / 台铃 / 小牛预设，可逐项覆盖：

| 参数 | 说明 |
| --- | --- |
| 宽度 / 高度 | 编码分辨率，须与仪表物理尺寸匹配 |
| 帧率 | 目标 FPS，实际取决于仪表拉帧速度 |
| 码率 | H.264 目标码率（kbps） |
| 发现端口 | 仪表发现端口，极核默认 10930 |
| 仪表 IP | 留空自动扫描；已知 IP 可直接填写跳过扫描 |

> **重要**：目前只有极核 PXC/TCP 输出协议完整实现。九号 / 台铃 / 小牛的预设仅提供参数与取证框架，协议栈尚未移植，选择这些车型投屏可能不出画。参数与配置保存在本机，供实机抓包与后续适配使用。

## 工作原理

```
来源 A（通用）：手机屏幕 (MediaProjection 录屏) → MediaCodec 编码 H.264
来源 B（零转码）：OPPO Car+ → 本机 127.0.0.1 七个端口 → AES 解密 → H.264
                 ▲ 本 App 冒充车机(HU)连入；Car+ 需先被拉进 CarLife 模式
        ↓ VideoFrame
CastEngine ─┬→ 本地预览（H264Decoder → Bitmap，仪表不在线也能看）
            └→ ZeehoOutput → ZeehoConnection（PXC/TCP 四端口）
        ↓
极核 AE4 MAX2+ 仪表出画
```

本地预览是刻意解耦的：仪表不在线时 `CastEngine` 只记一条日志继续采集，
状态显示 `RUNNING_LOCAL_ONLY`，帧照常进解码器。所以调 Car+ 链路完全不需要极核主机参与。

本项目的协议来自三个已有工作的交叉验证：

| 已有项目 | 做到了什么 | 本项目怎么用 |
| --- | --- | --- |
| [CarWithin](https://github.com/xmz28/CarWithin) | iOS 端完整逆向极核 6.2 寸 PXC/TCP 输出协议 | 逐行移植 `Zeeho62Legacy.swift` 的握手、媒体协商与视频帧封装到 Java |
| [ninebot_pro](https://github.com/xmz28/ninebot_pro) | 安卓端已验证可驱动极核 6.2 寸（1024×480 @25 FPS） | 确认安卓屏幕采集 + 该规格可行，沿用其参数 |
| [CarNine-Ninebot](https://github.com/xiaolu5hao/CarNine-Ninebot) | 安卓端冒充 CarLife 车机（HU），收 OPPO Car+ 的 H.264 再转九号 UDP | 移植其 `CarLifeLink` 七端口协议栈与 `H264Decoder`；九号仪表只参与**输出侧**，输入端同样是它自己冒充 HU——证明不需要真 CarLife 车机 |

ICCOA CarLink 因依赖专有 `icar-sdk.dex` 无法开源移植，CarLife 作为替代路线。
CarLife 的 HU 端由本 App 本人扮演（跑在 127.0.0.1 回环上），因此**不依赖任何车机配对**；
唯一的外部变量是能否把 Car+ 拉进 CarLife 模式，故提供了组件枚举与自动尝试。
详见 [ICCOA 现状](docs/ICCOA-STATUS.md)。

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
| M3 | CarLife 直连 OPPO Car+（零转码） | 协议栈 + 本地预览已实现；待验证各 ColorOS 版本能否拉起 Car+ 进 CarLife 模式 |
| M4 | 其他极核型号 / 固件适配 | 待真机取证 |
| M5 | ICCOA CarLink / 华为 HiCar 直连 | 受专有 SDK 阻塞 |

详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 常见问题

**装了就能投屏吗？** 代码链路已完整（录屏、编码、握手、推流都实现了），但**尚未在真机上验证出画**。需要你实机测试并回传日志；协议按 CarWithin 已验证的 Swift 实现移植，理论上可用。

**扫不到仪表？** 确认仪表已手动开启投屏、手机与仪表在同一 Wi-Fi、未被其他手机占用。Android 13+ 需附近 Wi-Fi 权限，Android 12 及以下需定位权限。

**点了开始但一直「协商中」？** 退出仪表投屏后重新开启，再点开始。若仍失败，查看 App 内运行日志，按 [docs/TEST-PLAN.md](docs/TEST-PLAN.md) 脱敏后回传。

**画面卡顿或花屏？** 码率与帧率按 1024×480@25 / 2500 kbps 设定，与 ninebot_pro 安卓端一致。若仍异常，可能是手机编码器差异，需调整 `ScreenCaptureSource.java` 中的参数。

**支持投 ICCOA / Car+ / HiCar 的内容吗？** 支持"投这些内容在手机屏幕上显示的画面"——通过录屏，不依赖车机配对。CarLife 直连是额外实验项。

**CarLife 直连和录屏投屏选哪个？** 想省一次编码、要更清晰的画面就选 CarLife 直连（零转码），前提是能把 Car+ 拉进 CarLife 模式——点①「尝试进 CarLife」自动试探。不想折腾就用**录屏投屏**：通用、不挑车机、不依赖 Car+。

**CarLife 链路怎么自查？** 三步：①「诊断」看 7 端口；②「尝试进 CarLife」让端口变绿；③点「CarLife 直连」后看**本地预览卡**是否出画。本地预览不需要极核在线，出画即说明 Car+ → 本 App 这段已经通了，剩下的只是把流推给仪表。

**为什么在 App 里拉不起 Car+？** `com.oplus.ocar` 没有 LAUNCHER 入口，且 `CarlifeActivity` 这个类名在不同 Car+ 版本里会变，`resolveActivity` 对未导出组件又一律返回 null。所以提供了「枚举组件」直接读 `PackageInfo.activities` 拿真实类名，再「尝试进 CarLife」逐个拉起验证。全部入口都不行的话，说明该 ColorOS 版本把入口藏进了系统互联流程，此时用录屏路线。

**APK 是谁签名的？** 正式签名（RSA-2048，有效期至 2056 年），可直接安装、可覆盖升级。私钥不在仓库；如需换成自己的密钥，见 [docs/RELEASE.md](docs/RELEASE.md)。若此前装过旧版 debug 包，先卸载再装。

## 参与开发

欢迎真机验证、协议取证、代码改进：

- [贡献指南](CONTRIBUTING.md)：协议取证、代码规范与提交方式
- [真机测试计划](docs/TEST-PLAN.md)：需要回传的日志、抓包与脱敏清单
- [自动构建与发布](docs/RELEASE.md)：打 tag 自动构建 APK 并发布到 Releases

## 许可证

**AGPL-3.0-only**。完整许可证见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md) 与 `third_party_licenses/`，合规义务说明见 [docs/LEGAL.md](docs/LEGAL.md)。

项目移植了 CarWithin（AGPL-3.0）的极核协议实现，组合工程须按 AGPL-3.0 分发；CarNine（Apache-2.0）的声明单独保留。

本项目是独立社区实验，与小米、OPPO、vivo、华为、ICCOA、极核、九号及上述项目作者无官方关联。
