#!/usr/bin/env bash
# CarWithin 真机调试助手（便携 adb，无需安装 Android SDK）
#
#   ./scripts/adb-carlife.sh wait                 等待设备并打印基本信息
#   ./scripts/adb-carlife.sh inspect              拉出 Car+ 的 APK，列出全部 Activity 与 exported
#   ./scripts/adb-carlife.sh ports                看 7 个 CarLife 端口是否在 LISTEN
#   ./scripts/adb-carlife.sh try                  逐个 am start 疑似入口，观察端口命中
#   ./scripts/adb-carlife.sh logs [秒数]          抓 logcat（默认 60 秒）
#   ./scripts/adb-carlife.sh install [apk路径]    安装 debug 包
#   ./scripts/adb-carlife.sh shot [文件名]        截图到 dist/
#   ./scripts/adb-carlife.sh dbg <命令> [秒数]    广播驱动 debug 包，不用手点屏幕
#       命令：connect | connect-launch | testpattern | stop | state | diagnose
#       例：./scripts/adb-carlife.sh dbg connect 15
#
# 环境变量：CAR_PKG（默认 com.oplus.ocar）、APP_PKG（默认 com.github.carwithinforandroid）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# adb 查找顺序：$ADB_BIN 显式指定 → 项目内 tools → 项目上一级 tools → PATH
find_adb() {
  local c
  for c in "${ADB_BIN:-}" \
           "$ROOT/tools/platform-tools/adb.exe" \
           "$ROOT/../tools/platform-tools/adb.exe" \
           "$(command -v adb 2>/dev/null)"; do
    if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
ADB="$(find_adb)" || {
  echo "找不到 adb。二选一："
  echo "  A) 下载便携版：cd \"$ROOT/..\" && mkdir -p tools && cd tools && \\"
  echo "     curl -L -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip && unzip pt.zip"
  echo "  B) 指定已有路径：ADB_BIN=/c/platform-tools/adb.exe $0 ..."
  exit 1
}
CAR_PKG="${CAR_PKG:-com.oplus.ocar}"
APP_PKG="${APP_PKG:-com.github.carwithinforandroid}"
PY="${PY:-python}"

# CarLife 七端口（十进制 → /proc/net/tcp 里的十六进制）
PORTS="7240 8240 9240 9241 9242 9340 9440"

usage() {
  cat <<'EOF'
CarWithin 真机调试助手（便携 adb，无需安装 Android SDK）

  ./scripts/adb-carlife.sh wait                 确认设备已连接并打印基本信息
  ./scripts/adb-carlife.sh inspect              拉出 Car+ 的 APK，列出全部 Activity 与 exported
  ./scripts/adb-carlife.sh ports                看 7 个 CarLife 端口是否在 LISTEN
  ./scripts/adb-carlife.sh try                  逐个 am start 疑似入口，观察端口命中
  ./scripts/adb-carlife.sh logs [秒数]          抓 logcat（默认 60 秒），同时盯端口变化
  ./scripts/adb-carlife.sh install [apk路径]    卸载旧包并安装 debug 包
  ./scripts/adb-carlife.sh shot [文件名]        截图到 dist/shots/
  ./scripts/adb-carlife.sh dbg <命令> [秒数]    广播驱动 debug 包，不用手点屏幕
      命令：connect | connect-launch | testpattern | stop | state | diagnose
      例：./scripts/adb-carlife.sh dbg connect 15

环境变量：CAR_PKG（默认 com.oplus.ocar）、APP_PKG（默认 com.github.carwithinforandroid）、
          ADB_BIN（自定义 adb 路径）
EOF
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
    timeout 60 "$ADB" wait-for-device 2>/dev/null
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
  local last=""
  for _ in $(seq 1 $((secs/5))); do
    sleep 5
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
  local apk="${1:-$ROOT/dist/CarWithin-0.4.2-dev19-debug.apk}"
  [ -f "$apk" ] || { echo "APK 不存在：$apk"; return 1; }
  echo "卸载旧包（签名不同必须卸）…"
  "$ADB" uninstall "$APP_PKG" >/dev/null 2>&1
  echo "安装 $(basename "$apk")"
  "$ADB" install -r -g "$apk"
  "$ADB" shell dumpsys package "$APP_PKG" | grep -E "versionName|versionCode" | head -2 | tr -d '\r'
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
    *) echo "未知命令：$cmd（可用 connect / connect-launch / testpattern / stop / state / diagnose）"; return 1 ;;
  esac
  "$ADB" logcat -c >/dev/null 2>&1
  echo "== 发送 DEBUG.$action =="
  "$ADB" shell am broadcast -a "com.github.carwithinforandroid.DEBUG.$action" 2>&1 | tr -d '\r' | head -2
  echo "== 观察 ${secs}s =="
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$ADB" logcat -s CarWithin 2>/dev/null | tr -d '\r' | grep -v '^-----'
  else
    "$ADB" logcat -d -s CarWithin 2>/dev/null | tr -d '\r' | grep -v '^-----'; sleep "$secs"
  fi
  echo "== 端口 =="
  cmd_ports
}

case "${1:-}" in
  wait) cmd_wait ;;
  inspect) cmd_inspect ;;
  ports) cmd_ports ;;
  try) cmd_try ;;
  logs) shift; cmd_logs "${1:-60}" ;;
  install) shift; cmd_install "${1:-}" ;;
  shot) shift; cmd_shot "${1:-}" ;;
  dbg) shift; cmd_dbg "${1:-state}" "${2:-10}" ;;
  *) usage ;;
esac
