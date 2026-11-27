# carwithin-for-android

把安卓手机互联协议的画面，桥接到 **极核 ZEEHO AE4 MAX2+ 6.2 寸仪表**的社区实验项目。

目标链路：

```
手机互联来源  →  H.264 视频帧  →  极核 PXC/TCP 输出  →  仪表出画
```

阶段顺序：**OPPO Car+（CarLife 栈）→ ICCOA CarLink → 华为 HiCar**。

> **当前状态（2026-11-27）：项目骨架，不是可用 APK。**
> 极核帧封装、RSA 身份、网段发现已落地；TCP 会话协商、CarLife 收流、转码与真机验证尚未完成。
> 请把本项目当作协议移植的起点，而不是已经能用的投屏工具。

---

## 为什么做这个项目

| 已有项目 | 做到了什么 | 缺什么 |
| --- | --- | --- |
| [CarNine-Ninebot](https://github.com/xiaolu5hao/CarNine-Ninebot) | 安卓端冒充 CarLife 车机，接收 OPPO Car+ 的 H.264 画面 | 输出端只支持九号私有 UDP 5012 |
| [CarWithin](https://github.com/xmz28/CarWithin) | iOS 端完整逆向了极核 6.2 寸仪表的 PXC/TCP 输出协议 | 依赖 iOS 私有 CarPlay 接口，无法移植到安卓 |
| [ninebot_pro](https://github.com/xmz28/ninebot_pro) | 已验证安卓端可以驱动极核 6.2 寸仪表（1024×480 @25 FPS） | 画面来源是本机屏幕采集，不接 Car+ / ICCOA |

本项目把三段拼起来：**复用手机侧接收，移植仪表侧输出**。

## 功能支持矩阵

### 手机侧（画面来源）

| 手机品牌 | 协议 | 阶段 | 当前状态 |
| --- | --- | --- | --- |
| OPPO / 一加 / realme | Car+（CarLife 七端口） | 阶段一 | 槽位已建，`CarLifeLink` 待移植 |
| 小米 / 红米 | CarWith（ICCOA CarLink） | 阶段二 | 槽位已建，依赖专有 SDK，见 [ICCOA 现状](docs/ICCOA-STATUS.md) |
| vivo | Jovi InCar（ICCOA CarLink） | 阶段二 | 同上 |
| 华为 / 荣耀 | HiCar | 阶段三 | 仅预留目录 |

> 注意：Car+ 与 CarWith **不是同一套协议**，CarNine 的接收栈只认 ColorOS 的 `com.oplus.ocar`。

### 仪表侧（输出目标）

| 仪表 | 分辨率 / 通道 | 当前状态 |
| --- | --- | --- |
| 极核 AE4 MAX2+ 6.2 寸（老款） | 1024×480，PXC/TCP 四端口 | 协议已逆向，实现骨架已落地，待真机验证 |
| 极核 AE4i Pro 2026 款 | 2048×968，PXC TCP 10920/10921/10922 | **不支持**，CarWithin issue #64 亦未解决 |
| 极核 AE4 Pro 25 款 / AE6 | 多种分辨率 | **不支持**，连接有未结问题 |
| 九号各型号仪表 | UDP 5012 | **不在本项目范围**，请用 CarNine |

## 快速开始

### 构建环境

- JDK 17
- Android SDK Platform 37（AGP 9.5.2 / Gradle 9.6.0，wrapper 已包含）

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 本机没有 Android SDK，提交前未做编译验证。首次构建如报 SDK 缺失，请先安装 Platform 37 与 Build Tools。

### 真机使用前提（后续版本）

1. 手机与仪表在同一 Wi-Fi / Wi-Fi Direct 网络。
2. 在仪表或极核 App 中**手动开启投屏**（本项目不依赖 BLE 唤醒）。
3. OPPO 手机需安装并支持 Car+。

当前版本安装后只能看到状态页，不会真正投屏。

## 架构

```
app/src/main/java/com/github/carwithinforandroid/
├── MainActivity.java             # 状态页（骨架）
├── core/                         # VideoFrame / PhoneVideoSource / DashboardOutput / CastEngine
├── sources/
│   ├── carlife/CarLifePhoneSource.java   # 阶段一：OPPO Car+
│   ├── iccoa/IccoaPhoneSource.java       # 阶段二：ICCOA（专有 SDK 槽位）
│   └── hicar/HiCarPhoneSource.java       # 阶段三：华为 HiCar（预留）
└── outputs/zeeho/
    ├── ZeehoProtocol.java        # 控制帧 / 媒体帧 / H.264 AU 封装
    ├── ZeehoIdentity.java        # RSA-2048 身份、HUID 签名、X.509 公钥
    ├── ZeehoDiscovery.java       # 网段扫描发现仪表
    ├── ZeehoConnection.java      # 四端口 TCP 会话状态机
    └── ZeehoOutput.java          # 对接 CastEngine 的输出端
```

极核端口（手机监听，仪表反向连回）：

| 端口 | 方向 | 用途 |
| --- | --- | --- |
| 10930 | 手机 → 仪表 | 发现与投屏请求 |
| 10922 | 仪表 → 手机 | 会话控制（JSON 命令） |
| 10711 | 仪表 → 手机 | 媒体控制（尺寸、身份回显、启动） |
| 10710 | 仪表 → 手机 | 视频拉帧（0x72）与 H.264 AU |

详见 [架构说明](docs/ARCHITECTURE.md)、[极核协议摘录](docs/ZEEHO-PROTOCOL.md)。

## 路线图与验证

- [开发路线图](docs/ROADMAP.md)：M0 骨架 → M1 协议闭环 → M2 OPPO 接通 → M3 稳定性 → M4 ICCOA → M5 HiCar
- [真机测试计划](docs/TEST-PLAN.md)：需要回传的日志、抓包与验收清单
- [贡献指南](CONTRIBUTING.md)：协议取证、代码规范与提交方式

## 已知限制

- 没有真机就无法完成握手与出画验证；协议细节可能需要根据实车日志校正。
- 极核老协议**不支持仪表触摸反控**，操作仍在手机上完成。
- ICCOA / HiCar 的协议实现依赖厂商专有 SDK，公开仓库只有编译桩，见 [ICCOA 现状](docs/ICCOA-STATUS.md)。
- 本项目是独立社区实验，与小米、OPPO、vivo、华为、ICCOA、极核及上述项目作者**无官方关联**。

## 许可证

**AGPL-3.0-only**。完整许可证见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md) 与 `third_party_licenses/`，合规义务说明见 [docs/LEGAL.md](docs/LEGAL.md)。

理由：项目移植了 CarWithin（AGPL-3.0）的极核协议实现，组合工程须按 AGPL-3.0 分发。CarNine 为 Apache-2.0，其声明单独保留。
