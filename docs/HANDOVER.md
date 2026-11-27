# 交接与现场调试手册

> 给「换一台机器、把手机带到车机旁边继续调试」这个场景写的。
> 结论先行：**Windows 上能跑通的部分都跑通了；卡住的是「不经过极核 App 怎么连上仪表」这一件事。**
> 最后更新：0.5.1 开发期。

---

## 1. 现在已经确定的事实

真机环境：OPPO PKH110（Find N5），Android 17 / API 37，ColorOS。

| 环节 | 状态 | 证据 |
| --- | --- | --- |
| CarLife 七端口自连 `127.0.0.1` | ✅ 7/7 LISTEN | 7240 / 8240 / 9240 / 9241 / 9242 / 9340 / 9440 |
| 17 步握手 | ✅ 全通 | 收帧稳定 8~18 FPS，预览累计 4967 帧 |
| 本地预览出图 | ✅ 出 Car+ 车机桌面 | 画布 **848×480@25**（不是 profile 里写的 1024×480） |
| 预览触控回传 | ✅ 可用 | 点 `423,239`（848×480 正中）命中；点导航卡切到地图并起播音乐 |
| Wi-Fi Direct 通道 | ⚠️ 只能扫设备 | 能读到本机设备名 `OPPO Find N5`，8 秒干净退出；家中无仪表，扫不到符合预期 |
| 极核 PXC 会话本体 | ⏸ 未实测 | 需要在仪表旁边跑，见第 7 节 |

一条容易踩的坑已经写进脚本：**Car+ 必须冷启动**。它后台跑过之后，再连只会停在「OPPO 智行」启动页（预览 luma 恒 19）；`am force-stop com.oplus.ocar` 后重连才进桌面（luma 26~28）。

## 2. 还没解决的（按优先级）

1. **不经过极核 App 怎么连上仪表。** 这是唯一真正的拦路虎，见第 3 节。
2. **仪表认不认 848×480。** 6.2 寸官方申报 1024×480，而 CarLife 只能给 848×480。如果仪表硬按 32:15 解码，画面会花/黑，那就得加「解码 → 缩放 → 重编码 + EGL letterbox」，成本高一个量级。**现场第一步就要验证这条。**
3. **仪表反控触摸**（10711 上 `0x20`）未实现。当前触控只作用于本机 Car+，没往仪表送。
4. 车机二维码的内容还没拿到，不知道里面是不是带了组网参数。

## 3. 关于「怎么连上仪表」——参照项目给出的答案

这一节是这次核对参考实现的核心结论，不要再绕回去。

### CarWithin（iOS，主项目）

> `reference/` 在 `.gitignore` 里，**不会随仓库到 Mac**。想在新机器上重看这几个上游项目：
> ```bash
> mkdir -p reference && cd reference
> git clone --depth 1 https://github.com/xmz28/CarWithin
> git clone --depth 1 https://github.com/xiaolu5hao/CarNine-Ninebot CarNine
> ```
> 下面这些结论都是从源文件读出来的，不看也不会影响现场调试。

去读 `reference/CarWithin/App/` 就会发现：**它没有一行组网代码。**

- `TailingDiscovery.swift`：只按 `localIP + netmask` 扫当前 /24 的 10930 端口。
- `TailingConnection.swift`：只往 `targetIP` 发 PXC 握手。
- 报错文案是「无法读取当前 Wi-Fi 子网，**请连接仪表 Wi-Fi**」。

也就是说它把组网推给了用户：「iPhone 加入仪表 Wi-Fi，选择同名预设」。README 里还提到要在「Wi-Fi 与网络」里填**名称和密码**。

**推论（很重要）**：iOS 不支持 Wi-Fi Direct。CarWithin 能跑通，说明**极核仪表在投屏页会开一个普通热点（AP 模式）**，手机只需要用标准 Wi-Fi 连上它。用户在极核 App 点「投屏」时那一下「无感的连 Wi-Fi」，本质就是极核 App 用预置的 SSID/密码替用户连了这个热点。

所以 Android 上正确的路子不是死磕 `WifiP2pManager`，而是：

```
知道 SSID/密码 → WifiNetworkSpecifier 请求该网络 → 系统的 wlan0 落到仪表网段
→ 扫 /24 找 10930 → 走 PXC 握手
```

这条路已经在代码里实现了（`ZeehoP2p.joinDashboardHotspot`，支持开放网络和有密码两种），只是**还缺 SSID/密码**。

### CarNine（Android，只管九号）

它是九号协议（UDP 5012/5013 + 20 字节头），跟极核的 PXC/TCP **完全两套**，不能混。唯一值得抄的是它自己发起 Wi-Fi Direct 的做法 —— 已经移植进 `ZeehoP2p`。

### 结论：连接顺序应该是

```
① 用现有网卡直接扫 10930（对应「已经在极核 App 点过投屏」这一最常见场景）
② 扫不到 → 用已配置的仪表热点 WifiNetworkSpecifier 连（对应 CarWithin 的路子）
③ 再不行 → 自建 Wi-Fi Direct（对应 CarNine 的路子）
```

代码已按这个顺序改好（`ZeehoOutput.start`）。**①②③ 哪条能成，就是现场要验证的东西。**

## 4. 曲线救国：全屏预览 + 极核 App 录屏（Plan B，已实现待实测）

如果 PXC 直连（Plan A）在现场卡住，这条能立刻验证「画面到底能不能上仪表」，
因为它完全绕开了协议和组网 —— 让极核 App 自己去连仪表。

**为什么之前说这条路不行、现在又行了：**

之前否掉是因为 Car+ 的画面画在它自己的**虚拟显示**里，MediaProjection 录系统屏幕录不到。
但现在我们的 App 已经把 CarLife 帧解码成 Bitmap 画在**普通 View** 上 ——
它就是普通的系统屏幕内容，录屏能录到。

**操作顺序（顺序不能错）：**

```
① 主界面先连上 Car+，确认预览里有画面（./scripts/adb-carlife.sh fresh 25）
② 点「全屏预览（给录屏投屏用）」→ 横屏 + 常亮 + 状态栏隐藏
③ 切到极核 App 点「投屏」→ 选「整个屏幕」→ 授权
④ 立刻切回本 App（否则录到的是极核 App 自己）
```

第 ④ 步是这条路最容易翻车的地方：MediaProjection 录的是**整个屏幕**，
谁在前台就录谁。授权完必须马上切回来。

**预期能力与限制：**

| 项目 | 情况 |
| --- | --- |
| 仪表出画 | 有可能，取决于极核 App 怎么处理录屏画面 |
| 画面比例 | 由手机屏幕比例决定（约 2.2:1），不是 848×480；仪表缩放时可能有裁切或黑边 |
| 仪表反控 | ❌ 没有。极核 App 的录屏投屏一般不带反控 |
| 手机操作车机 | ✅ 可以。全屏预览上的触摸仍然回传给 CarLife，手机等于一块触控板 |
| 稳定性 | 取决于极核 App 能不能在后台持续录屏（部分 ROM 会限制后台运行） |

这条路的定位是**取证**：能证明「CarLife → 解码 → 屏幕 → 录屏 → 仪表」这条链是通的，
但成品体验受手机屏幕比例和极核 App 后台策略限制，不能替代 Plan A。

## 5. 拿到 SSID/密码的四种办法

现场按顺序试，第一种最省事：

1. **在极核 App 点一次投屏，然后看手机当前连的 SSID**
   ```bash
   adb shell dumpsys wifi | grep -iE "SSID|mWifiInfo" | head -10
   adb shell cmd wifi status          # 新系统上这个更干净
   ```
   拿到名字后再试热点连接。密码如果猜不到，先用开放网络那一路试（传空 psk）。
2. **密码可能就写在仪表说明书/车机设置里**（很多车厂是固定密码，如 `12345678`）。
3. **从极核 App 的 APK 里挖**：硬编码的 SSID 前缀、默认密码经常直接躺在 strings 里。
   ```bash
   ./scripts/adb-carlife.sh inspect   # 会 pull 出 base.apk 到 dist/pull/
   # 然后对 base.apk 里的 classes*.dex / resources.arsc 做 strings 搜索
   ```
4. **兜底**：不折腾组网，就让用户在极核 App 点投屏（那时手机已经连上了），
   我们的 App 走顺序①直接扫 —— 这也完全能跑，只是多一步手动操作。

## 6. 换到 MacBook 上的第一个小时

### 5.1 装 adb（只有这一个硬依赖）

```bash
brew install android-platform-tools
adb version
```

### 5.2 拿 APK：两条路，先走 A

**A. 走 GitHub Actions（推荐，Windows/Mac 都不需要装 Android SDK）**

推 `main` 分支就会自动构建预览版并挂到 Release（`dev-latest`）。Mac 上：

```bash
git clone https://github.com/Andrew8102/Carwithin-for-android.git
cd Carwithin-for-android
gh release download dev-latest -p '*.apk' -D dist   # 或直接在网页上点下载
```

> 注意：`signing/`（正式 keystore）和 `dist/` 都在 `.gitignore` 里，**不会随仓库过去**。
> 所以 Mac 上大概率**不能**直接 `./gradlew assembleDebug` —— 除非把 `signing/release.jks`
> 和对应密码手工拷过去（`keystore.properties` 或环境变量 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。
> CI 里有 Secrets，所以云端构建是零配置的。

**B. 本地构建（需要在 Mac 上装 JDK 17 + Android SDK）**

```bash
chmod +x gradlew                    # 仓库里已是 100755，但 Windows 上 clone 可能会丢
brew install --cask temurin@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

### 5.3 装到手机

```bash
./scripts/adb-carlife.sh doctor      # 先看环境+设备
./scripts/adb-carlife.sh install     # 默认装 dist/ 下最新的 debug 包
```

如果脚本报 `bad interpreter: bash^M`，说明仓库里的换行符被改坏了 —— 仓库已加 `.gitattributes` 强制 LF，正常不会出现；真遇到就 `sed -i '' 's/\r$//' scripts/adb-carlife.sh`。

## 7. 现场调试 runbook（按顺序做，每步都有明确的「成功长什么样」）

手机接 MacBook，坐到车机旁边。全程不用手点 App 界面，靠 adb 广播驱动。

### 第 0 步：清干净，确认基线

```bash
./scripts/adb-carlife.sh dbg diagnose 8
```

日志里应该看到：网卡列表、`Wi-Fi Direct 未建立`、`P2P 权限 有`。
如果权限是「无」，先补：

```bash
adb shell pm grant com.github.carwithinforandroid android.permission.NEARBY_WIFI_DEVICES
adb shell pm grant com.github.carwithinforandroid android.permission.ACCESS_FINE_LOCATION
```

### 第 1 步：让仪表进入投屏页

扫码页出来就行（不用真扫码）。**仪表不进这个页面，10930 是不开的。**

### 第 2 步：先试「极核 App 已经连上」这条路

在手机上点极核 App 的「投屏」（会弹安卓录屏授权，随便给它，我们不用它录屏）。
等它把网络连上后，**立刻把极核 App 的录屏停掉 / 退出投屏**：

- 停掉录屏：把通知栏那条「正在录屏」的通知关掉，或者直接 `am force-stop` 极核 App；
- **不要再点"结束投屏"这种会拆网络的按钮** —— 我们要保留它建好的网络，只是不要它占着仪表。CarWithin 的说明里也是「退出其他投屏 App，再启动本程序」。
- 仪表同一时间只认一个客户端，两个都连着会各自都拿不到 `0x72` 拉帧请求，表现成"握手成功但没画面"。

然后：

```bash
./scripts/adb-carlife.sh dbg diagnose 8
```

盯两件事：
- 网卡里有没有多出 `p2p0=192.168.x.x` 或 wlan0 换成了新网段；
- 记下这个网段，它就是仪表所在网段。

> 这一步的价值：**用官方 App 把网组起来，验证我们应用层能不能通。**
> 如果能通 → 说明 PXC 协议部分没问题，剩下的只是「怎么自己组网」这个工程问题。

### 第 3 步：验证仪表认不认 848×480

这一步是本次现场**最关键**的验证。先冷启动 Car+ 拿到真画面：

```bash
./scripts/adb-carlife.sh fresh 25
```

看到预览里出现车机桌面后，再触发投屏到仪表：

```bash
./scripts/adb-carlife.sh dbg connect 30
```

**判读**：

| 现象 | 结论 | 下一步 |
| --- | --- | --- |
| 仪表出画，比例正常 | 仪表接受 848×480 | 太好了，继续调帧率和稳定性 |
| 仪表出画但拉伸/两侧黑边 | 仪表按 1024×480 硬解 | 要么接受，要么做缩放重编码 |
| 仪表花屏/绿屏 | 尺寸或 SPS/PPS 不匹配 | 必须做重编码 + letterbox |
| 仪表黑屏、握手却成功 | 只出图不出帧，或 IDR 没发 | 查首帧是否 IDR |

日志看这几行：`发现仪表 x.x.x.x` → `已监听 ...:10922/10711/10710` → `仪表已接受投屏请求` → `媒体协商完成，等待仪表逐帧请求`。

### 第 4 步：反过来验证「不用极核 App 自己连」

先 `FORGET` 清记忆，避免上一轮的地址干扰判断：

```bash
./scripts/adb-carlife.sh dbg forget 3
```

然后按第 3 节的三条路依次试：

```bash
# ① 现有网卡直接扫（若此时手机仍连着仪表）
./scripts/adb-carlife.sh dbg connect 30

# ② 热点方式（先用第 4 节的办法拿到 SSID）
./scripts/adb-carlife.sh hotspot "ZEEHO-XXXX" "12345678"
./scripts/adb-carlife.sh dbg join-hotspot 30

# ③ 自建 Wi-Fi Direct
./scripts/adb-carlife.sh dbg p2p-build 25
```

每次只看一行结论：`仪表 x.x.x.1:10930 有响应 → 可以点投屏了`。
有响应说明网络通了，剩下的是应用层；没响应就是网络还没通，继续换方法。

## 8. 命令速查

```bash
# 环境/设备
./scripts/adb-carlife.sh doctor                # 换机器第一件事
./scripts/adb-carlife.sh wait
./scripts/adb-carlife.sh install [apk]

# CarLife 侧（本机自连，跟仪表无关）
./scripts/adb-carlife.sh ports                 # 七端口是否 LISTEN
./scripts/adb-carlife.sh fresh [秒]            # 冷启动 Car+ 并直连
./scripts/adb-carlife.sh dbg state
./scripts/adb-carlife.sh shot out.png

# 仪表侧
./scripts/adb-carlife.sh hotspot <ssid> [psk]  # 写热点凭据
./scripts/adb-carlife.sh dbg diagnose 8        # 网卡 + P2P 状态
./scripts/adb-carlife.sh dbg connect 30        # 连仪表（不动 CarLife）
./scripts/adb-carlife.sh dbg join-hotspot 30
./scripts/adb-carlife.sh dbg p2p-build 25
./scripts/adb-carlife.sh dbg p2p-scan 15
./scripts/adb-carlife.sh dbg forget 3          # 清掉热点/上次地址记忆
```

手工发广播也行（注意 `-p` 和 `-f 0x20` 都不能省）：

```bash
adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.DIAGNOSE \
  -p com.github.carwithinforandroid -f 0x00000020
```

> **新增一个 DEBUG action 必须同时改两处**：`DebugCommandReceiver` 的 switch，
> 以及 `AndroidManifest.xml` 的 intent-filter。漏了清单，`am broadcast` 只会回
> `Broadcast completed: result=0`（看起来成功），但接收器根本收不到。

## 9. 架构速查

```
Car+ (com.oplus.ocar)                本应用                         极核仪表
   │  127.0.0.1:7240/8240/9240/9241    │                                │
   │  9242/9340/9440                   │                                │
   ├────────── 17 步握手 ─────────────►│                                │
   │◄───────── H.264 848×480@25 ───────┤                                │
   │◄───────── 触控 (9340) ────────────┤  本地预览 PreviewView          │
   │                                   │  （去黑边 + 坐标换算）          │
   │                                   ├── 10930 announce ─────────────►│
   │                                   │◄─ 10922 反连（下发 signedHUID）│
   │                                   │◄─ 10711 反连（0x72 拉帧/0x20） │
   │                                   ├── 10710 H.264 帧 ─────────────►│
```

关键文件：

| 文件 | 职责 |
| --- | --- |
| `sources/carlife/CarLifePhoneSource.java` | 跟 Car+ 的七端口握手、收帧、发触控 |
| `core/CastEngine.java` | 采集→输出线程、失败重试（10s×6）、`retryOutput()` 不动 CarLife 会话 |
| `outputs/zeeho/ZeehoOutput.java` | 极核输出：**先扫再组网**的入口 |
| `outputs/zeeho/ZeehoNetwork.java` | 选哪块网卡（p2p0 优先，其次 wlan0） |
| `outputs/zeeho/ZeehoP2p.java` | 自建 Wi-Fi Direct + 热点连接 |
| `outputs/zeeho/ZeehoPrefs.java` | 热点凭据 / 上次成功地址 |
| `outputs/zeeho/ZeehoConnection.java` | PXC 握手与帧发送 |
| `ui/PreviewView.java` | 预览 + 触控回传 |

## 10. 协议速查（极核 6.2 寸）

| 端口 | 方向 | 用途 |
| --- | --- | --- |
| 10930 TCP | 手机 → 仪表 | 发现 / announce：`0x70000010` `{"phoneType","packageName"}`，等 `0x70000011` `{"status":true}` |
| 10922 TCP | **仪表 → 手机**（手机监听） | 控制通道，下发 signedHUID |
| 10711 TCP | **仪表 → 手机**（手机监听） | 辅助/反向控制；`0x72` 空载荷 = 逐帧拉帧请求；`0x20` = 触摸 |
| 10710 TCP | **仪表 → 手机** | H.264 视频流 |

两条容易记错的：

- **手机是服务端。** 10922/10711/10710 都是我们 `listen`，等仪表反连。先去 connect 这三个端口是没用的。
- **必须是「仪表拉一帧、我们发一帧」。** 不收到 `0x72` 就发帧，仪表会丢。

画布参数：6.2 寸官方申报 **1024×480 @25fps @2.5Mbit/s**；实际 CarLife 只能给 **848×480@25**。

## 11. 排错速查

| 现象 | 最可能的原因 | 怎么办 |
| --- | --- | --- |
| 「未发现仪表」 | 扫的是家里路由器网段 | `dbg diagnose` 看网卡，注意 p2p0 |
| 扫到了但握手超时 | 仪表没进投屏页 / 已被其他投屏占着 | 退出官方投屏，重新进投屏页 |
| `am broadcast` 无反应 | action 没在清单登记 | 补 `AndroidManifest.xml` intent-filter |
| `SecurityException` on registerReceiver | targetSdk 34 要求显式 exported 标志 | 用 `RECEIVER_NOT_EXPORTED` |
| 预览停在「OPPO 智行」logo | Car+ 是热启，没进 CarLife 模式 | `adb shell am force-stop com.oplus.ocar` 后重连 |
| 预览拉伸 | 解码器按 profile 配了 1024×480 | 用协商出来的实际画布尺寸 |
| 连上了但仪表黑屏 | 首帧不是 IDR | `sendFrame` 首帧强制 IDR |
| 重连报端口占用 | 上次没 close 掉监听的三个端口 | `ZeehoOutput.start` 失败分支要 `created.close()` |
| 换 Mac 后脚本报 `bash^M` | 仓库换行符 | 已加 `.gitattributes` 强制 LF |

## 12. 下一步（按价值排序）

1. 现场验证第 7 节第 3 步（848×480 能不能出画）—— 决定要不要做缩放重编码，这是工作量的分水岭。
2. 拿到仪表热点 SSID/密码，验证 `WifiNetworkSpecifier` 路径能不能完全脱离极核 App。
3. 仪表反控触摸（10711 `0x20`）。
4. 稳定性：长时间投屏、切后台、锁屏后恢复。
