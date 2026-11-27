# 架构

> 本文描述的是**代码结构**与**设计意图**。整个项目尚未在真机上跑通任何一条链路，
> 下面的箭头代表"代码这样连"，不代表"实际这样工作"。

## 数据链路

```
手机互联来源 (PhoneVideoSource)
  ├─ ScreenCaptureSource（MediaProjection 录屏，通用，代码已写）
  ├─ CarLifePhoneSource（OPPO Car+ 七端口，零转码，本机冒充 HU，代码已写）
  ├─ TestPatternSource（测试图，免授权，代码已写）
  ├─ IccoaPhoneSource（专有 icar-sdk.dex，槽位）
  └─ HiCarPhoneSource（华为，占位）
        ↓ VideoFrame (Annex-B H.264 AU)
CastEngine ─┬→ FrameTap → H264Decoder → PreviewView（本地预览，不依赖仪表）
            └→ ZeehoOutput → ZeehoConnection → 极核仪表（未验证）
```

**采集与输出解耦**：`CastEngine.start()` 先试 `output.start()`，失败只记日志并置
`outputReady=false`，然后照常 `source.start()`。此时状态为 `RUNNING_LOCAL_ONLY`，
帧仍然会走 `FrameTap` 到本地预览。这样调 CarLife 链路时不需要极核主机在场。

CarLife 来源链路：按协议，`com.oplus.ocar`（Car+）在本机 127.0.0.1 监听 7 个 TCP 端口
（CMD 7240 / VIDEO 8240 / MEDIA 9240 / TTS 9241 / VR 9242 / TOUCH 9340 / DATA 9440），
`CarLifeLink` 作为车机端（HU）连入，完成版本协商 → 身份证明(MD5) → RSA/AES 内容加密 →
VIDEO_INIT 画布协商 → 逐帧收 H.264。帧经 `H264.toAnnexB` 规范化后交给 CastEngine。
协议栈自 CarNine 移植（`sources/carlife/CarLifeLink.java`，约 1000 行，仅依赖 JDK）。
**这条链路在本项目未实测，端口是否真的会开尚且未知。**

**HU 就在手机里（推断）**：CarLife 的 HU 不需要是另一台设备。CarNine 在其 README 里记录了
在 realme 真机上的做法——它自己冒充 HU 连本机回环收流，再转推九号仪表。若该做法可复现，
则"极核不是 CarLife 车机"不构成障碍，唯一变量是能否让 Car+ 交出镜像 Surface 并开始监听端口
（见 `docs/ICCOA-STATUS.md`）。本项目尚未复现。

## 端口与时序（极核 AE4 MAX2+ 6.2 寸）

| 端口 | 方向 | 用途 |
| --- | --- | --- |
| 10930 | 手机 → 仪表 | 发现与投屏请求（0x70000010 / 0x70000011） |
| 10922 | 仪表 → 手机 | 会话控制（JSON 命令） |
| 10711 | 仪表 → 手机 | 媒体控制（尺寸、身份回显、启动） |
| 10710 | 仪表 → 手机 | 视频拉帧（0x72）与 H.264 AU |

手机在这三个端口监听，仪表反向连回。不是九号 UDP 5012。

## 关键协商点

1. 仪表在控制通道报 `HUName` 与 `HUID`；手机用 RSA-2048 对 HUID 原始字节做 PKCS#1 v1.5 签名，回传 X.509 公钥 JSON。
2. 媒体通道 0x10 携带仪表上报尺寸（必须是 1024×480 或 1024×488）与签名回显；手机回 0x11 指定 H.264 与 1024×480。
3. 媒体通道 0x70 → 0x71 启动；视频通道收到 0x72 后发送一帧 H.264 AU。
4. 控制通道 0x20000 之后依次发送 0x204c0、0x20020、0x20030、0x20050、0x205a0，极核再发 0x20590 镜像启动。
5. 0x70000000 周期保活。

## 为什么用 CarLife 替代 ICCOA

ICCOA CarLink 是小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar 共用的协议；一套接收端理论上能覆盖多数国产安卓手机。但公开实现依赖专有 `icar-sdk.dex`，无法开源移植。因此 OPPO Car+ 改走 CarLife 车机端协议栈（CarNine 已验证路线），无需专有 SDK；小米 / 华为暂时用通用录屏路线。详见 `ICCOA-STATUS.md`。
