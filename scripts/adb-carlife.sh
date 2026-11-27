#!/usr/bin/env bash
# CarWithin 真机调试助手（便携 adb，Windows / macOS / Linux 通用）
#
#   ./scripts/adb-carlife.sh doctor               检查 adb / python / 设备是否就绪
#   ./scripts/adb-carlife.sh wait                 等待设备并打印基本信息
#   ./scripts/adb-carlife.sh inspect              拉出 Car+ 的 APK，列出全部 Activity 与 exported
#   ./scripts/adb-carlife.sh ports                看 7 个 CarLife 端口是否在 LISTEN
#   ./scripts/adb-carlife.sh try                  逐个 am start 疑似入口，观察端口命中
#   ./scripts/adb-carlife.sh logs [秒数]          抓 logcat（默认 60 秒）
#   ./scripts/adb-carlife.sh install [apk路径]    安装 debug 包
#   ./scripts/adb-carlife.sh shot [文件名]        截图到 dist/shots/
#   ./scripts/adb-carlife.sh dbg <命令> [秒数]    广播驱动 debug 包，不用手点屏幕
#       命令：connect | connect-launch | testpattern | stop | state | diagnose
#             p2p-build | p2p-scan | join-hotspot | forget
#   ./scripts/adb-carlife.sh hotspot <ssid> [psk] 写仪表热点凭据（psk 省略=开放网络）
#   ./scripts/adb-carlife.sh fresh [秒数]         冷启动一把梭：杀 Car+/CarLife → 直连 → 盯日志
#       Car+ 已在后台跑过时，CarLife 只会停在「OPPO 智行」启动页；必须先结束它。
#
# 环境变量：CAR_PKG（默认 com.oplus.ocar）、APP_PKG（默认 com.github.carwithinforandroid）、
#           ADB_BIN（自定义 adb 路径）、PY（自定义 python）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# adb 查找顺序：$ADB_BIN 显式指定 → 项目内 tools → 项目上一级 tools → 常见安装位置 → PATH
find_adb() {
  local c
  for c in "${ADB_BIN:-}" \
           "$ROOT/tools/platform-tools/adb" \
           "$ROOT/../tools/platform-tools/adb" \
           "$ROOT/tools/platform-tools/adb.exe" \
           "$ROOT/../tools/platform-tools/adb.exe" \
           "$HOME/Library/Android/sdk/platform-tools/adb" \
           "$HOME/Android/Sdk/platform-tools/adb" \
           "/opt/homebrew/bin/adb" \
           "/usr/local/bin/adb" \
           "$(command -v adb 2>/dev/null)"; do
    if [ -n "$c" ] && [ -x "$c" ] && [ ! -d "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
ADB="$(find_adb)" || {
  echo "找不到 adb。按系统二选一："
  echo "  macOS   brew install android-platform-tools"
  echo "  Linux   sudo apt install android-tools-adb"
  echo "  Windows 下载便携版："
  echo "     cd \"$ROOT/..\" && mkdir -p tools && cd tools && \\"
  echo "     curl -L -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip && unzip pt.zip"
  echo "  或指定已有路径：ADB_BIN=/path/to/adb $0 ..."
  exit 1
}
CAR_PKG="${CAR_PKG:-com.oplus.ocar}"
APP_PKG="${APP_PKG:-com.github.carwithinforandroid}"
# macOS 只有 python3，Windows 上 python 也能是 py -3
PY="${PY:-}"
if [ -z "$PY" ]; then
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
PY="${PY:-python3}"

# macOS 自带的 coreutils 里没有 timeout（装了 coreutils 才有 gtimeout）。
TIMEOUT=""
if command -v timeout >/dev/null 2>&1; then TIMEOUT=timeout
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT=gtimeout; fi

# 有 timeout 就用；没有就自己掐表（macOS 默认走这条）
run_timeout() {
  local secs="$1"; shift
  if [ -n "$TIMEOUT" ]; then
    "$TIMEOUT" "$secs" "$@"
    return $?
  fi
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill -TERM "$pid" 2>/dev/null ) &
  local watcher=$!
  wait "$pid" 2>/dev/null
  local rc=$?
  kill -TERM "$watcher" 2>/dev/null
  wait "$watcher" 2>/dev/null
  return $rc
}


# CarLife 七端口（十进制 → /proc/net/tcp 里的十六进制）
PORTS="7240 8240 9240 9241 9242 9340 9440"

usage() {
  cat <<'EOF'
CarWithin 真机调试助手（便携 adb，Windows / macOS / Linux 通用）

  ./scripts/adb-carlife.sh doctor               检查 adb / python / 设备是否就绪
  ./scripts/adb-carlife.sh wait                 确认设备已连接并打印基本信息
  ./scripts/adb-carlife.sh inspect              拉出 Car+ 的 APK，列出全部 Activity 与 exported
  ./scripts/adb-carlife.sh ports                看 7 个 CarLife 端口是否在 LISTEN
  ./scripts/adb-carlife.sh try                  逐个 am start 疑似入口，观察端口命中
  ./scripts/adb-carlife.sh logs [秒数]          抓 logcat（默认 60 秒），同时盯端口变化
  ./scripts/adb-carlife.sh install [apk路径]    卸载旧包并安装 debug 包
  ./scripts/adb-carlife.sh shot [文件名]        截图到 dist/shots/
  ./scripts/adb-carlife.sh dbg <命令> [秒数]    广播驱动 debug 包，不用手点屏幕
      命令：connect | connect-launch | testpattern | stop | state | diagnose
            p2p-build | p2p-scan | join-hotspot | forget
      例：./scripts/adb-carlife.sh dbg diagnose 10
  ./scripts/adb-carlife.sh hotspot <ssid> [psk] 写仪表热点凭据（不填 psk = 开放网络）
  ./scripts/adb-carlife.sh fresh [秒数]         冷启动：结束 Car+/CarLife → 直连 → 盯日志
      为什么需要 fresh：Car+ 已在后台运行过时，重新连接只会停在
      「OPPO 智行 / 百度 CarLife+」启动页，必须让它冷启动才会进入车机桌面。

环境变量：CAR_PKG（默认 com.oplus.ocar）、APP_PKG（默认 com.github.carwithinforandroid）、
          ADB_BIN（自定义 adb 路径）、PY（自定义 python）
EOF
}

# 一次性把环境问题暴露出来 —— 换机器（比如从 Windows 搬到 Mac）第一件事就跑这个
cmd_doctor() {
  echo "== 环境 =="
  echo "  OS        $(uname -s) $(uname -m)"
  echo "  adb       $ADB"
  echo "  adb 版本  $("$ADB" version 2>/dev/null | head -1 | tr -d '\r')"
  echo "  python    $PY ($($PY -V 2>&1 | tr -d '\r'))"
  echo "  timeout   ${TIMEOUT:-内置兜底（macOS 正常）}"
  echo "  项目      $ROOT"
  if [ -x "$ROOT/gradlew" ]; then
    echo "  gradlew   可执行（本机可自行构建）"
  else
    echo "  gradlew   不可执行，可在 macOS 上执行 chmod +x gradlew"
  fi
  echo
  echo "== 设备 =="
  "$ADB" devices
  echo
  echo "  debug 包  $("$ADB" shell pm list packages "$APP_PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://')"
  echo "  Car+ 包   $("$ADB" shell pm list packages "$CAR_PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://')"
  echo
  echo "== dist/ 里可安装的包 =="
  ls -t "$ROOT"/dist/*.apk 2>/dev/null | head -5 | while read -r f; do
    echo "  $(basename "$f")"
  done
  [ -d "$ROOT/dist" ] || echo "  （没有 dist/，从 GitHub Actions 的 artifact 里下载）"
}


require_device() {
  local n
  n=$("$ADB" devices | grep -c "device$")
  if [ "$n" -eq 0 ]; then
    echo "✗ 没有已授权的设备。"
    echo "  1) 手机：设置 → 关于本机 → 连点版本号 7 次，开启开发者选项"
    echo "  2) 开发者选项里打开「USB 调试」（ColorOS 还要关掉「USB 调试安全设置」限制）"
    echo "  3) 插线后手机上点「允许调试」，勾选「始终允许」"
    "$ADB" devices
    exit 1
  fi
}

cmd_wait() {
  if ! "$ADB" devices | grep -q "device$"; then
    echo "暂未发现已授权设备，最多等 60 秒…（Ctrl+C 取消）"
    echo "  1) 手机：设置 → 关于本机 → 连点「版本号」7 次，开启开发者选项"
    echo "  2) 开发者选项里打开「USB 调试」"
    echo "  3) 插线后手机上点「允许调试」，勾选「始终允许」"
    echo "  macOS 上如果完全没有设备：换一条数据线（很多充电线不带数据），"
    echo "  或先执行 adb kill-server && adb devices 重新握手"
    run_timeout 60 "$ADB" wait-for-device 2>/dev/null
  fi
  require_device
  echo
  echo "型号      $("$ADB" shell getprop ro.product.model | tr -d '\r')"
  echo "系统      Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "厂商      $("$ADB" shell getprop ro.product.manufacturer | tr -d '\r') / $("$ADB" shell getprop ro.product.brand | tr -d '\r')"
  echo "版本      $("$ADB" shell getprop ro.build.version.incremental | tr -d '\r')"
  echo "App 已装  $("$ADB" shell pm list packages "$APP_PKG" | tr -d '\r')"
  echo "Car+ 已装 $("$ADB" shell pm list packages "$CAR_PKG" | tr -d '\r')"
}

cmd_inspect() {
  require_device
  echo "== 拉取 $CAR_PKG 的 APK =="
  local path apk
  path=$("$ADB" shell pm path "$CAR_PKG" | tr -d '\r' | head -1 | sed 's/^package://')
  if [ -z "$path" ]; then
    echo "✗ 未安装 $CAR_PKG（或不是 OPPO/ColorOS）"
    "$ADB" shell pm list packages | grep -i -E "ocar|carlife|carwith|carlink|hicar" | tr -d '\r' || echo "  没找到任何车机互联相关包"
    return 1
  fi
  echo "  设备内路径 $path"
  mkdir -p "$ROOT/dist/pull"
  apk="$ROOT/dist/pull/base.apk"
  "$ADB" pull "$path" "$apk" >/dev/null 2>&1 || { echo "✗ pull 失败（可能是系统分区权限）"; return 1; }
  echo "  已保存 $apk ($(du -h "$apk" | cut -f1))"
  echo
  "$PY" "$ROOT/scripts/axml.py" "$apk" | tee "$ROOT/dist/pull/carplus-activities.json"
  echo
  echo "== 结论速看 =="
  "$PY" - "$ROOT/dist/pull/carplus-activities.json" <<'PYEOF'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
print("包名 %s，共 %d 个 activity，其中 exported=%d" % (d['package'], d['total'], d['exported']))
if d['launcher']:
    print("有桌面入口：", ", ".join(d['launcher']))
else:
    print("没有桌面入口（正常，Car+ 是系统组件）")
kw=("carlife","cast","mirror","link","projection","screen")
sus=[a for a in d['activities'] if any(k in a['name'].lower() for k in kw)]
print("疑似 CarLife 入口 %d 个：" % len(sus))
for a in sus:
    print("  %-58s exported=%s" % (a['name'], a['exported']))
PYEOF
}

# 打印当前处于 LISTEN 的 TCP 端口（十进制，每行一个）
listening_ports() {
  "$ADB" shell cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | tr -d '\r' \
    | awk 'NR>1 {split($2,a,":"); if ($4=="0A") print a[2]}' \
    | while read -r h; do printf "%d\n" "0x$h"; done | sort -un
}

cmd_ports() {
  require_device
  local open; open=$(listening_ports)
  echo "== CarLife 端口（127.0.0.1）=="
  local n=0 t=0
  for p in $PORTS; do
    t=$((t+1))
    if echo "$open" | grep -qx "$p"; then
      echo "  $p  ✓ LISTEN"; n=$((n+1))
    else
      echo "  $p  ✗"
    fi
  done
  echo "→ $n/$t 在监听"
  [ "$n" -eq 0 ] && echo "  Car+ 尚未进入 CarLife 模式"
}

cmd_try() {
  require_device
  local json="$ROOT/dist/pull/carplus-activities.json"
  [ -f "$json" ] || { echo "先跑 inspect"; cmd_inspect >/dev/null || return 1; }
  echo "逐个尝试拉起（每次等 2.5 秒看端口）…"
  "$PY" - "$json" <<'PYEOF' > "$ROOT/dist/pull/candidates.txt"
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
kw=("carlife","cast","mirror","link","projection","screen","connect")
for a in d['activities']:
    if any(k in a['name'].lower() for k in kw):
        print(a['name'])
PYEOF
  : > "$ROOT/dist/pull/try-report.txt"
  while read -r act; do
    [ -z "$act" ] && continue
    echo "--- $act"
    out=$("$ADB" shell am start -n "$CAR_PKG/$act" 2>&1 | tr -d '\r')
    echo "$out" | grep -E "Starting|Error|Exception|Warning" | head -3
    sleep 2.5
    open=$(listening_ports)
    n=0
    for p in $PORTS; do echo "$open" | grep -qx "$p" && n=$((n+1)); done
    echo "$act → $n/7" | tee -a "$ROOT/dist/pull/try-report.txt"
    if [ "$n" -eq 7 ]; then
      echo "✓ 命中！$act 能点亮 7 个端口"
      break
    fi
  done < "$ROOT/dist/pull/candidates.txt"
  echo
  cat "$ROOT/dist/pull/try-report.txt"
}

cmd_logs() {
  require_device
  local secs="${1:-60}"
  echo "抓 logcat ${secs}s（同时盯端口变化）…"
  "$ADB" logcat -c
  ( sleep "$secs" ) | "$ADB" logcat -v time 2>/dev/null | grep -iE "carwithin|carlife|oplus.ocar|AndroidRuntime|MediaCodec|libc.*Fatal" &
  local lp=$!
  local last="" elapsed=0
  # macOS 没有 seq，别用 $(seq 1 N)
  while [ "$elapsed" -lt "$secs" ]; do
    sleep 5
    elapsed=$((elapsed + 5))
    local cur; cur=$(listening_ports | tr '\n' ' ')
    if [ "$cur" != "$last" ]; then
      echo "[ports] $(echo "$cur" | wc -w) 个监听：$cur"
      last="$cur"
    fi
  done
  wait $lp 2>/dev/null
}

cmd_install() {
  require_device
  local apk="${1:-}"
  if [ -z "$apk" ]; then
    # 默认取 dist/ 下最新的 debug 包，避免脚本里写死版本号后被遗忘
    apk=$(ls -t "$ROOT"/dist/CarWithin-*-debug.apk 2>/dev/null | head -1)
  fi
  [ -n "$apk" ] && [ -f "$apk" ] || { echo "APK 不存在：${apk:-dist/ 下没有 CarWithin-*-debug.apk}"; return 1; }
  echo "卸载旧包（签名不同必须卸）…"
  "$ADB" uninstall "$APP_PKG" >/dev/null 2>&1
  echo "安装 $(basename "$apk")"
  "$ADB" install -r -g "$apk"
  "$ADB" shell dumpsys package "$APP_PKG" | grep -E "versionName|versionCode" | head -2 | tr -d '\r'
}

# 冷启动一把梭：Car+ 之前在后头跑过时，CarLife 只会停在「OPPO 智行」启动页。
# 根因是 CarLife 在 Car+ 已完成初始化后才接手，虚拟显示里画的还是自己的启动图。
# 结束 Car+ 及其连接进程 → 让本应用先在 7 个端口上等 → 再拉起 Car+，画面才会进车机桌面。
cmd_fresh() {
  require_device
  local secs="${1:-30}"
  echo "== 结束 Car+ / CarLife =="
  "$ADB" shell am broadcast -a "com.github.carwithinforandroid.DEBUG.STOP" -p "$APP_PKG" >/dev/null 2>&1
  for p in "$CAR_PKG" "com.baidu.carlife" "com.baidu.carlife.oppo"; do
    "$ADB" shell am force-stop "$p" >/dev/null 2>&1
    echo "  force-stop $p"
  done
  sleep 1
  cmd_dbg connect-launch "$secs"
}

cmd_shot() {
  require_device
  local name="${1:-shot-$(date +%H%M%S).png}"
  mkdir -p "$ROOT/dist/shots"
  "$ADB" exec-out screencap -p > "$ROOT/dist/shots/$name"
  echo "已保存 dist/shots/$name"
}


# 通过广播驱动 debug 包里的 DebugCommandReceiver，避免手点屏幕（锁屏时也能用）
cmd_dbg() {
  local cmd="${1:-state}" secs="${2:-10}" action
  case "$cmd" in
    connect)        action="CARLIFE_CONNECT" ;;
    connect-launch) action="CARLIFE_CONNECT_LAUNCH" ;;
    testpattern)    action="TESTPATTERN" ;;
    stop)           action="STOP" ;;
    state)          action="STATE" ;;
    diagnose)       action="DIAGNOSE" ;;
    p2p-build)      action="P2P_BUILD" ;;
    p2p-scan)       action="P2P_SCAN" ;;
    join-hotspot)   action="JOIN_HOTSPOT" ;;
    forget)         action="FORGET" ;;
    *) echo "未知命令：$cmd"
       echo "可用：connect / connect-launch / testpattern / stop / state / diagnose /"
       echo "      p2p-build / p2p-scan / join-hotspot / forget"
       return 1 ;;
  esac
  "$ADB" logcat -c >/dev/null 2>&1
  echo "== 发送 DEBUG.$action =="
  # 必须 -p 指定包名：自定义 action 的隐式广播在 Android 8+ 不会唤醒清单里的 receiver；
  # 加 -f 0x20（FLAG_INCLUDE_STOPPED_PACKAGES）让刚装完还没启动过的包也能收到。
  "$ADB" shell am broadcast -a "com.github.carwithinforandroid.DEBUG.$action" \
    -p "$APP_PKG" -f 0x00000020 2>&1 | tr -d '\r' | head -2
  echo "== 观察 ${secs}s =="
  # adb logcat 的 -s 只按 tag 过滤，所以这里用 run_timeout 保证 mac 上也能自动收尾
  run_timeout "$secs" "$ADB" logcat -s CarWithin 2>/dev/null | tr -d '\r' | grep -v '^-----'
  echo "== 端口 =="
  cmd_ports
}

# 写仪表热点凭据：CarWithin（iOS）里这是用户手填的 SSID/密码，
# Android 上写一次就能让 WifiNetworkSpecifier 全自动连上，不再需要极核 App。
cmd_hotspot() {
  local ssid="${1:-}" psk="${2:-}"
  if [ -z "$ssid" ]; then
    echo "用法：$0 hotspot <SSID> [密码]     密码留空表示开放网络"
    echo "  现场先看手机当前连的是哪个（极核 App 点过投屏后再看最准）："
    echo "    $ADB shell dumpsys wifi | grep -i \"mWifiInfo\\|SSID\" | head -5"
    return 1
  fi
  require_device
  echo "写入热点 SSID=$ssid 密码长度=${#psk}"
  # adb shell 会把后面所有参数拼成一条命令交给设备端 sh 再解析一遍，
  # 含空格的值不加引号会被拆成两个 extra。
  local qssid qpsk
  qssid="$(quote_for_device "$ssid")"
  qpsk="$(quote_for_device "$psk")"
  "$ADB" shell am broadcast -a "com.github.carwithinforandroid.DEBUG.HOTSPOT" \
    -p "$APP_PKG" -f 0x00000020 --es ssid "$qssid" --es psk "$qpsk" >/dev/null 2>&1
  echo "已写入，验证中…"
  run_timeout 4 "$ADB" logcat -s CarWithin 2>/dev/null | tr -d '\r' | grep -E "热点|HOTSPOT" | head -5
}

# 值里带空格时补一层单引号，交给设备端 sh 解析
quote_for_device() {
  if printf '%s' "$1" | grep -q '[[:space:]]'; then
    printf "'%s'" "$1"
  else
    printf '%s' "$1"
  fi
}

case "${1:-}" in
  doctor) cmd_doctor ;;
  wait) cmd_wait ;;
  inspect) cmd_inspect ;;
  ports) cmd_ports ;;
  try) cmd_try ;;
  logs) shift; cmd_logs "${1:-60}" ;;
  install) shift; cmd_install "${1:-}" ;;
  shot) shift; cmd_shot "${1:-}" ;;
  hotspot) shift; cmd_hotspot "${1:-}" "${2:-}" ;;
  dbg) shift; cmd_dbg "${1:-state}" "${2:-10}" ;;
  fresh) shift; cmd_fresh "${1:-30}" ;;
  *) usage ;;
esac
