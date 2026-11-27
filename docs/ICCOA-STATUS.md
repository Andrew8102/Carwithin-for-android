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

## 阶段一现状：CarLife 协议栈已实现，仅差「拉起 Car+」（v0.4.0）

协议栈本身不依赖 ICCOA SDK：

- `sources/carlife/CarLifeLink.java`：自 CarNine 完整移植，七端口连接 + 版本协商 + MD5 身份证明 + RSA/AES 内容加密 + 视频帧接收，仅依赖 JDK。
- `sources/carlife/H264.java`：Annex-B / AVCC 转换与关键帧识别。
- `sources/carlife/CarLifePhoneSource.java`：把 CarLifeLink 包装成 `PhoneVideoSource`，接入 CastEngine。
- `media/H264Decoder.java` + `ui/PreviewView.java`：本地解码出画，不依赖极核在线。
- UI「①」新增「诊断」「枚举组件」「尝试进 CarLife」；「②」CarLife 直连可配本地预览。

### 角色澄清：HU 由本 App 扮演，不需要真车机

CarLife 的角色是固定的：**MD（手机端）= Car+ 内的百度 CarLife+ 组件，负责编码并在本机
`127.0.0.1` 上监听 7 个端口；HU（车机端）= 主动连接这些端口的一方**。

关键点是 **HU 可以就住在同一台手机里**。CarNine 正是这么做的：它在手机上冒充 HU 连本机回环，
把收到的 H.264 再按九号 UDP 协议推给仪表——**九号仪表只参与输出侧，不参与 CarLife 握手**。
此前本文档把九号仪表写成「承担 CarLife 车机角色」是错的。

所以本项目的极核仪表是否为 CarLife 车机，与这条链路无关。真正的唯一变量是：

> 能不能让 Car+ 交出镜像 Surface，让 7 个端口在本机开始监听。

### 唯一变量：如何拉起 Car+

Car+ 通过 AIDL `ICarLifeCastManager` 把「镜像 Surface + 宽高 + DPI」交给 CarLife 组件，组件才开始监听。
入口类名随 Car+ 版本变化（CarNine 验证的是 `com.oplus.ocar.CarlifeActivity`），且未导出的组件
`resolveActivity` 一律返回 null，所以光靠「拉起界面」经常报「未找到可拉起界面」。本项目为此提供：

1. **枚举组件**：直接读 `PackageInfo.activities`，列出 `com.oplus.ocar` 声明的全部 Activity，
   ★ 标记名字含 carlife/cast/mirror/carlink 的候选，并标注 `[未导出]` / `[已禁用]`。
2. **尝试进 CarLife**：对「疑似 + 可导出 + 已启用」的候选逐个 `startActivity`，
   每次等 2.5 秒轮询 7 个端口，一旦 7/7 全绿立刻停止并提示可用。
3. **诊断**：单次快照，确认当前端口与已安装信息。

三种可能的结果与对策：

| 结果 | 含义 | 下一步 |
| --- | --- | --- |
| 7/7 端口监听 | Car+ 已进入 CarLife 模式 | 点「CarLife 直连」，看本地预览出画 |
| 部分端口 | CarLife 正在启动或半途失败 | 再点一次诊断/尝试，或看日志里的握手步骤 |
| 0/7 端口 | 候选入口都不是有效入口 | 用全量枚举列表手动填 Activity 再试；仍不行则说明该系统把入口锁在系统互联流程里 |

**结论**：CarLife 直连已从「大概率不可用」修正为「有明确可操作的调试路径」。若枚举+尝试全部未命中，
说明该 ColorOS 版本不开放外部拉起，此时用不依赖 Car+ 的**录屏投屏**。ICCOA 直连仍是长期方向，
待合法 SDK 可用时启用 `IccoaPhoneSource` 槽位。

## 华为 HiCar

HiCar 是另一套生态，不复用 ICCOA。`sources/hicar/` 目前只是占位，等 OPPO 与 ICCOA 稳定后再评估。
