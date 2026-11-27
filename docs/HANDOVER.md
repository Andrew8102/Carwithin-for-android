# 交接与现场调试手册

> 给「换一台机器、把手机带到车机旁边继续调试」这个场景写的。
> 结论先行：**Windows 上能跑通的部分都跑通了；卡住的是「不经过极核 App 怎么连上仪表」这一件事。**
> 最后更新：0.5.3 开发期。
>
> **两条路都在真机跑通了：**
> **Plan B** —— 全屏预览让极核 App 录屏投出去（用户已实测出画，画面糊/卡/带状态栏，见第 4 节）；
> **Plan A** —— PXC 直推（代码就绪，未在仪表旁实测，见第 7 节）。
> **组网方式已确认：不是 Wi-Fi Direct，是普通热点，SSID/密码就印在投屏二维码里（见第 5 节）。**

---

## 1. 现在已经确定的事实

真机环境：OPPO PKH110（Find N5），Android 17 / API 37，ColorOS。

| 环节 | 状态 | 证据 |
| --- | --- | --- |
| CarLife 七端口自连 `127.0.0.1` | ✅ 7/7 LISTEN | 7240 / 8240 / 9240 / 9241 / 9242 / 9340 / 9440 |
| 17 步握手 | ✅ 全通 | 收帧稳定 8~18 FPS，预览累计 4967 帧 |
| 本地预览出图 | ✅ 出 Car+ 车机桌面 | 画布 **848×480@25**（不是 profile 里写的 1024×480） |
| 预览触控回传 | ✅ 可用 | 点 `423,239`（848×480 正中）命中；点导航卡切到地图并起播音乐 |
| 全屏预览 → 极核录屏投仪表 | ✅ 真机出画 | 用户实测「全屏可以，曲线救国没问题」；画面糊/卡/带状态栏，见第 4 节 |
| 组网方式 | ✅ 已确认 | 投屏二维码解出 `ssid=ZEEHO-7ce663&pwd=271639c053&auth=wpa2-psk`，是普通热点不是 P2P，见第 5 节 |
| 极核 PXC 会话本体 | ⏸ 未实测 | 需要在仪表旁边跑，见第 7 节 |

一条容易踩的坑已经写进脚本：**Car+ 必须冷启动**。它后台跑过之后，再连只会停在「OPPO 智行」启动页（预览 luma 恒 19）；`am force-stop com.oplus.ocar` 后重连才进桌面（luma 26~28）。

## 2. 还没解决的（按优先级）

1. **不经过极核 App 连仪表** —— 现在有了热点 SSID/密码（第 5 节），
   用 `WifiNetworkSpecifier` 就能连，是最有希望的一条；PXC 会话本体仍待仪表旁实测。
2. **仪表认不认 848×480。** 6.2 寸官方申报 1024×480，而 CarLife 只能给 848×480。如果仪表硬按 32:15 解码，画面会花/黑，那就得加「解码 → 缩放 → 重编码 + EGL letterbox」，成本高一个量级。**现场第一步就要验证这条。**
3. **仪表反控触摸**（10711 上 `0x20`）未实现。当前触控只作用于本机 Car+，没往仪表送。
4. 车机二维码的内容还没拿到，不知道里面是不是带了组网参数。
5. ~~「劫持极核 App 投屏流」互斥/证伪~~ —— **误判，已作废**。Plan B 真机跑通，见第 4 节。

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

## 4. 曲线救国：全屏预览 + 极核 App 录屏（Plan B，**真机跑通，可用**）

> ### ✅ 结论（2026-11-27 真机实测，用户验证）
>
> **这条路是通的。用户反馈：全屏可以，曲线救国没问题** —— 极核 App 录我们的全屏预览
> 能投到仪表上。此前写的「互斥 / 证伪」是误判，已作废。
>
> 实际观感有三个问题（都不影响链路成立，只影响体验）：
> 1. **画面偏糊** —— 两段编码：CarLife 848×480 解码 → 屏幕 → MediaProjection 再编码，
>    二次压缩 + 缩放。
> 2. **有点卡** —— 同理，多一跳采集编码必然带延迟。
> 3. **上下有状态栏，画面反而变小** —— 极核 App 录的是「整个屏幕」，
>    把状态栏一起录进去了；系统栏那几十像素白占画面。
>
> **这三个都是「全屏预览这一层」能修的，不必退回去做 PXC 直推。**
> 修法见本节末尾「已知问题与改法」。它是当前**唯一在真机上出过画**的方案，
> 优先级应高于 Plan A。

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

### 已经真机验证到哪一步（0.5.3-dev.32）

| 验证项 | 结果 |
| --- | --- |
| 点「全屏预览」后进全屏 | ✅ 横屏、状态栏和导航栏消失、Toast 提示出现 |
| 画面等比居中 | ✅ 848×480 按高度铺满，左右留黑边（手机屏约 2.34:1，比画面宽） |
| 返回键退出 | ✅ 日志打出「已退出全屏预览」，回到列表 |
| 崩溃 | ✅ 无 |

### 现场要盯的两个新坑（这次真机踩到的）

**坑 1：Car+ 会抢焦点。** 全屏后 CarLife 会话一连通，`mCurrentFocus` 就变成
`com.oplus.ocar/OCarLauncherImprovedActivity`，我们的窗口可能被盖住。
判断方法：

```bash
adb shell "dumpsys window | grep mCurrentFocus"
```

如果焦点是 ocar，用最近任务键切回本应用再看。**切回来之后如果焦点稳住了，
就说明只是建立会话那一下抢的，不影响录屏**；如果反复被抢，这条路就只剩"录 Car+ 自己"。

**坑 2：`screencap` 会被 Car+ 的虚拟显示骗。** 系统里有多个 display 时它
"defaulting to the first display found"，很可能截到 Car+ 的虚拟显示而不是主屏 ——
你以为自己截的是本应用，其实是 Car+ 的内容。**截图必须指定主屏 id**：

```bash
adb shell "dumpsys SurfaceFlinger --display-id"      # 找 HWC display 0 那一行的 id
adb shell "screencap -d <id> /sdcard/a.png"
adb pull /sdcard/a.png
```

`./scripts/adb-carlife.sh shot` 已经按这个逻辑改好了，会自己找主屏 id。

> 顺带一个坑：Android 16+ 的 `screencap -p` 会把警告打到 stdout，
> `adb exec-out screencap -p > a.png` 得到的文件开头是 `[Warning] ...` 而不是 PNG 头，
> 图片打不开。用「存设备再 pull」的方式。

### 当时的待确认项 → 现有答案

1. ~~`adb shell screenrecord` 能不能录到 Car+ 全屏界面~~
   → **能，而且用户已经用它投到仪表出画了。** Plan B 成立。
2. ~~Car+ 抢焦点是"只抢一次"还是"持续抢"~~
   → 会抢焦点，但不影响极核 App 录屏投屏这条路。

### 已知问题与改法（用户实测反馈）

| 现象 | 原因 | 改法 |
| --- | --- | --- |
| 画面偏糊 | CarLife 848×480 解码成 Bitmap，再被 MediaProjection 二次采集编码 | 全屏时让画面**铺满**而不是等比居中（减少黑边=减少被录进去的无效像素）；或提高码率 |
| 有点卡 | 多一跳采集→编码→传输的固有延迟 | 这一跳无法从 App 侧消除，属方案固有代价 |
| **上下有任务栏，画面变小** | ⚠️ **大概率不是系统状态栏，是 motorplay 自己画的任务栏** | 见下方「两种投屏方式」，若是 motorplay 画的则 App 侧修不了 |

### 极核官方有两种投屏方式（2026-11-27 用户澄清）

| 方式 | 做法 | 画面 | 上下任务栏 |
| --- | --- | --- | --- |
| **① motorplay 导航投屏** | 手机端搜索地址 → 直接让 motorplay 导航 | 全屏 | **有，motorplay 自己画的任务栏** |
| **② 镜像投屏** | 手机屏幕整个镜像过去 | 全屏 | 取手机画面，含系统状态栏 |

用户说的「上下有任务栏」出现在方式①。这是 **motorplay 应用自己的 UI**，
不是 Android 系统状态栏 —— 所以 `hideSystemBars()` 那套对它**无效**，我们从 App 侧改不掉。
之前写「状态栏可修」是判断错了，这里更正。

**这对我们意味着什么：** Plan B 走的是方式②（极核 App 录我们的全屏预览 → 镜像投屏）。
如果走方式①那种「导航内容直推」，得让 motorplay 认我们的画面为导航源，
那是另一条完全不同的路（要接 motorplay 的导航协议），当前没实现。

**画质糊的根因也更清楚了**：方式②要把手机屏幕二次采集编码；
方式①如果是原生导航数据直推，理论上更清晰 —— 但需要逆向 motorplay 的投屏协议。


## 5. 组网方式已确认 —— 二维码里就有 SSID/密码（2026-11-27 解出）

**不是 Wi-Fi Direct，是普通 WPA2 热点。** 仪表投屏页那个二维码扫出来是一个下载页 URL，
但参数里把组网凭据全带上了：

```
https://zhbackend.zeehoev.com/app_download/?modelid=49901&sn=RK7C&action=9
  &ssid=ZEEHO-7ce663&pwd=271639c053&auth=wpa2-psk
  &mac=DC%3A0D%3A30%3A98%3ACD%3AED&name=ZEEHO-7ce663
```

| 参数 | 值 | 含义 |
| --- | --- | --- |
| `ssid` | `ZEEHO-7ce663` | **仪表热点名** |
| `pwd` | `271639c053` | **热点密码**（WPA2-PSK，10 位） |
| `auth` | `wpa2-psk` | 认证方式 |
| `mac` | `DC:0D:30:98:CD:ED` | 仪表 MAC（可用来确认是不是它） |
| `action` | `9` | 极核 App 的落地动作码 |
| `modelid` / `sn` | `49901` / `RK7C` | 车型/序列号 |

这印证了 CarWithin（iOS）的判断：它的源码里一行组网代码都没有，报错直接说「请连接
仪表 Wi-Fi」—— 因为**仪表就是个普通热点**，极核 App 扫码后拿到这组凭据替用户连上。
所谓「连 Wi-Fi 无感」就是这个动作。之前猜的 Wi-Fi Direct / p2p0 是走偏了。

**密码是否每次变化：待确认**（同名热点 `ZEEHO-7ce663` 后缀像设备号，不像随机会话）。
所以做成可配置，不写死。

### 方式 A：App 内扫码（0.5.5-dev.37 起，现场最省事）

「③ 车型与调试选项」卡里有个蓝色按钮 **「扫仪表二维码（自动填热点）」**：

1. 点它 → 授权相机 → 对准仪表投屏页的二维码
2. 识别后自动解析 `ssid`/`pwd`/`mac`，存进配置（密码不写进 logcat，只打长度）
3. 回主界面点 **「加入仪表热点（用已存的热点名/密码）」** → 弹系统确认框点确认
4. 日志打「网关」和「探针有响应」就成了

扫码用 ZXing core 纯本地解码，**不依赖 GMS / 不需要联网** —— 车机旁没网也能用。

### 方式 B：adb 命令写凭据（0.5.3-dev.32 起就支持）

```bash
./scripts/adb-carlife.sh hotspot "ZEEHO-7ce663" "271639c053"
./scripts/adb-carlife.sh dbg join-hotspot 15     # 立刻试连 + 探 10930
```

`join-hotspot` 用 `WifiNetworkSpecifier` 连，Android 10+ 会弹系统确认框，要在手机上点确认。
连上后日志会打「网卡」和「网关猜测」，网关一般就是仪表地址。

### 如果密码变了

**直接用方式 A 重新扫一次就行**。手抠 URL 的话：`ssid=` 和 `pwd=` 两个值，
URL 编解码注意 `%3A` = `:`，别把整个 URL 当密码传。

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

> **两条路都可选**：想快速看到出画走 Plan B（第 4 节，已验证）；想验证直推走本节。
> Plan B 的状态栏/糊/卡问题见第 4 节末尾；组网凭据来自二维码，见第 5 节。

1. 现场验证第 7 节第 3 步（848×480 能不能出画）—— 决定要不要做缩放重编码，这是工作量的分水岭。
2. 拿到仪表热点 SSID/密码，验证 `WifiNetworkSpecifier` 路径能不能完全脱离极核 App。
3. 仪表反控触摸（10711 `0x20`）。
4. 稳定性：长时间投屏、切后台、锁屏后恢复。
5. ~~全屏预览 + 极核录屏~~ —— **已真机跑通，不删**。剩糊/卡/状态栏三个体验问题，
   见第 4 节末尾；状态栏可在 App 侧修。
