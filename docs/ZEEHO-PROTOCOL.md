# 极核 AE4 MAX2+ 6.2 寸协议摘录

来源：`reference/CarWithin/App/Zeeho62Legacy.swift`（本地源码，不提交）。

## 帧格式

控制帧（16 字节头，小端）：

```
u32 command
u32 size = 16 + payload.length
u32 command ^ size
u32 identifier
payload (JSON)
```

媒体控制帧（8 字节头，小端）：

```
u16 command
u16 payload.length
u32 token
payload
```

视频帧：可选 8 字节前缀（首帧），随后 `u32 length`（小端）+ Annex-B H.264 AU。仪表先发 `0x72` 空载荷请求，手机才发一帧。

## 命令速查

| 通道 | 命令 | 含义 |
| --- | --- | --- |
| 发现 | 0x70000010 / 0x70000011 | 投屏请求 / 接受（status=true） |
| 控制 | 0x10000 / 0x10010 | 设备资料请求 / 应答（HUName、HUID） |
| 控制 | 0x10450 | 时间同步 |
| 控制 | 0x20000 | 会话开始，随后手机发 0x204c0、0x20020、0x20030、0x20050、0x205a0 |
| 控制 | 0x20590 | 极核镜像启动 |
| 控制 | 0x70000000 | 保活 |
| 媒体 | 0x10 / 0x11 | 媒体配置 / 应答（编码 2=H.264，1024×480） |
| 媒体 | 0x70 / 0x71 | 媒体启动 / 确认 |
| 媒体 | 0x30 / 0x31 | 端口信息 |
| 视频 | 0x72 | 拉帧请求 |

## 身份

- RSA-2048 密钥对，本机保存 PKCS#8 私钥与 UUID 形式的 phoneID。
- 对 HUID 的 UTF-8 原始字节做 PKCS#1 v1.5 签名（不是先做 SHA 摘要）。
- 公钥以 X.509 SubjectPublicKeyInfo 的 DER base64 发送。

## 尺寸与帧率

- 仪表上报 1024×480 或 1024×488；手机固定回 1024×480。
- H.264，25 FPS，ninebot_pro 安卓端默认码率 2500 kbps。

## 触摸

媒体通道 `0x20` 在 CarWithin 实现里明确不执行反控。老协议下仪表不能触摸操作手机。
