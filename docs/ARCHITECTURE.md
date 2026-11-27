# 架构

## 数据链路

```
手机互联来源 (PhoneVideoSource)
  ├─ CarLife 接收（OPPO Car+，阶段一）
  ├─ ICCOA CarLink（小米/OPPO/vivo，阶段二，需专有 SDK）
  └─ HiCar（华为，阶段三）
        ↓ VideoFrame (Annex-B H.264 AU)
CastEngine
        ↓
ZeehoOutput → ZeehoConnection → 极核仪表
```

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

## 为什么用 ICCOA 作为长期方向

ICCOA CarLink 是小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar 共用的协议；一套接收端能覆盖多数国产安卓手机。但公开实现依赖专有 `icar-sdk.dex`，详见 `ICCOA-STATUS.md`。
