# ICCOA CarLink 接入现状

## 结论

ICCOA CarLink 是覆盖小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar 的共用协议，方向上符合「一套协议覆盖多数安卓手机」。但**协议实现本身不是开源的**。

## 公开项目的实际情况

| 项目 | 说明 |
| --- | --- |
| `PiggyBackBayonet/MiAuto` (AGPL-3.0) | 车端接收器，0.1.3 已完成配对、投屏、UIBC 触控；协议来自第三方 `icar-sdk.dex`，仓库只有签名桩 `icar-sdk-stubs.jar` |
| `jiuzihe36/CarPlusPlus` (AGPL-3.0) | SDK 化结构，`sdk-iccoa` 是协议插件；同样依赖 `app/libs/icar-sdk.dex`（约 7 MB），不在仓库 |
| `opencarlink-cmd/OpenCarLink` (Apache-2.0) | README 声称支持 8 种协议，但源码尚未发布，仓库只有 README |
| `LiuuiL98/OpenCarlinkReceiver` | 诊断原型，README 明确说尚未实现 ICCOA 协议 |

两者共同点：**没有 dex 时 App 能编译安装，但无法与手机建立会话**。

## 本项目的处理方式

`sources/iccoa/IccoaPhoneSource` 定义为协议槽位。拿到合法可用的 SDK 后，按以下步骤接入：

1. 把 `icar-sdk.dex` 放到 `app/libs/`（不要提交）。
2. 按供应商要求把四个 `com.share.connect.*` 服务与权限写进 AndroidManifest。
3. 在 `IccoaPhoneSource` 中实现发现、配对码、连接状态、视频帧回调。
4. 把视频帧交给 `CastEngine`，由 `ZeehoOutput` 送到仪表。

在没有 SDK 前，阶段一尝试用 CarLife 栈接通 OPPO Car+；这条链路不需要专有 SDK，但受下节限制。

## 阶段一现状：CarLife 协议栈已实现，但场景受限（v0.3.0）

协议栈本身不依赖 ICCOA SDK：

- `sources/carlife/CarLifeLink.java`：自 CarNine 完整移植，七端口连接 + 版本协商 + MD5 身份证明 + RSA/AES 内容加密 + 视频帧接收，仅依赖 JDK。
- `sources/carlife/H264.java`：Annex-B / AVCC 转换与关键帧识别。
- `sources/carlife/CarLifePhoneSource.java`：把 CarLifeLink 包装成 `PhoneVideoSource`，接入 CastEngine。
- UI「①」新增「诊断」按钮（安装 / 版本 / 各 Activity / 7 端口监听状态），「②」CarLife 直连标注为实验性。

### 关键限制：Car+ 需要车机 HU 才会进入 CarLife 模式

CarLife 的角色是固定的：**MD（手机端）= Car+ 内的百度 CarLife+ 组件，负责编码并监听 7 个本机端口；
HU（车机端）= 连接这些端口的一方**。组件由 Car+ 通过 AIDL 拉起，只有当 Car+ 连上支持
ICCOA / 百度 CarLife+ / 亿连的**车机端**时，才会交出镜像 Surface、建立端口。

本项目目标仪表极核 AE4 MAX2+ 走私有 PXC/TCP 协议，**不是 CarLife 车机**。因此在本场景下：

- 强行 `startActivity(com.oplus.ocar/.CarlifeActivity)` 未必能让 Car+ 进入 CarLife 模式（取决于 ColorOS 版本）；
- 即使 App 界面被拉起，7 端口也不会监听，CarLifeLink 会一直停在「等待手机端监听」直到超时；
- CarNine 之所以能跑通，是因为它配的是**九号仪表**（该场景仪表承担了 CarLife 车机角色）。

**结论**：CarLife 直连在本项目属实验性、多数情况不可用。App 内「诊断」可确认 7 端口状态；
未监听请走不依赖车机配对的**录屏投屏**。ICCOA 直连仍是长期方向，待合法 SDK 可用时启用 `IccoaPhoneSource` 槽位。

## 华为 HiCar

HiCar 是另一套生态，不复用 ICCOA。`sources/hicar/` 目前只是占位，等 OPPO 与 ICCOA 稳定后再评估。
