#!/usr/bin/env bash
#
# 窗口收割机 —— 蹲守 ADB 的短暂窗口，抓到就焊死。
#
# 用法：
#   ./harvest.sh                  # 默认蹲 30 分钟，焊接 + 切 TCP
#   DURATION=600 ./harvest.sh     # 蹲 10 分钟
#   DO_TCPIP=0 ./harvest.sh       # 只焊接，不切 TCP（WiFi 不可靠时用）
#   ADB=/opt/platform-tools/adb ./harvest.sh
#
# 关键设计：
#   1. 轮询间隔默认 0.4s —— 太快会 fork 爆宿主机，太慢会错过窗口
#   2. 窗口内只做 setprop / settings，绝不碰耗时操作
#   3. 按价值排序：先焊接（保住 USB 通道），再切 TCP（多一条通道）
#   4. 焊接成功立刻开 WiFi、关飞行模式 —— 给第二条通道上保险
#
set -u

ADB="${ADB:-adb}"
DURATION="${DURATION:-1800}"      # 蹲守总时长（秒）
INTERVAL="${INTERVAL:-0.4}"       # 轮询间隔（秒）
TCP_PORT="${TCP_PORT:-5555}"      # adb tcpip 端口
DO_TCPIP="${DO_TCPIP:-1}"         # 1=切 TCP，0=只焊接
LOG="${LOG:-/tmp/adb_harvest.log}"

log() { echo "$*" | tee -a "$LOG"; }

: > "$LOG"
log "=== harvest start $(date '+%F %T') ==="
log "adb=$ADB  duration=${DURATION}s  interval=${INTERVAL}s  tcpip=$DO_TCPIP"

command -v "$ADB" >/dev/null 2>&1 || { log "!! 找不到 adb：$ADB"; exit 1; }

"$ADB" kill-server  >/dev/null 2>&1
sleep 1
"$ADB" start-server >/dev/null 2>&1

# 清掉可能残留的网络连接，避免把 IP 设备误当成窗口目标
END=$(( $(date +%s) + DURATION ))
HIT=0

while [ "$(date +%s)" -lt "$END" ]; do
    # 只要 USB 设备（序列号里不含冒号），排除已连上的 WiFi 目标
    S=$("$ADB" devices 2>/dev/null | awk 'NR>1 && NF>=2 && $1 !~ /:/ && $2=="device"{print $1; exit}')

    if [ -z "$S" ]; then
        sleep "$INTERVAL"
        continue
    fi

    HIT=$((HIT+1))
    log ""
    log "##### [$(date '+%T')] WINDOW HIT #$HIT: $S #####"

    # ---------- 第一步：焊接 adb_enabled ----------
    # 窗口期 AMS 未就绪，settings 会报 IActivityManager==null / SecurityException，
    # 所以要一直重试；一旦写入，UsbDeviceManager 会当场把 adb 加回 USB 组合。
    ok=0
    for k in $(seq 1 30); do
        "$ADB" -s "$S" shell settings put global adb_enabled 1 >/dev/null 2>&1
        V=$("$ADB" -s "$S" shell settings get global adb_enabled 2>/dev/null | tr -d '\r ')
        log "  [weld $k] adb_enabled=[$V] $(date '+%T')"
        if [ "$V" = "1" ]; then
            log "  *** adb_enabled=1 焊接成功 ***"
            ok=1
            break
        fi
        sleep 0.4
    done
    [ "$ok" = "1" ] || log "  !! 焊接未成功（窗口太短，继续蹲）"

    # ---------- 第二步：切 TCP ----------
    # 注意：tcpip 会重启 adbd，USB 连接必然断开 —— 这是预期行为，不是失败。
    if [ "$DO_TCPIP" = "1" ]; then
        "$ADB" -s "$S" shell setprop service.adb.tcp.port "$TCP_PORT" >/dev/null 2>&1
        "$ADB" -s "$S" tcpip "$TCP_PORT" >>"$LOG" 2>&1 \
            && log "  *** tcpip $TCP_PORT 已投递（USB 断开是正常的）***"
    fi

    # ---------- 第三步：给第二条通道上保险 ----------
    "$ADB" -s "$S" shell 'settings put global airplane_mode_on 0' >/dev/null 2>&1
    "$ADB" -s "$S" shell 'svc wifi enable' >/dev/null 2>&1
    log "  [wifi 已投递] $(date '+%T')"

    # 给它一点时间，然后继续蹲（万一设备又重启了）
    sleep 3
done

log ""
log "=== harvest end $(date '+%F %T')  hits=$HIT ==="
[ "$HIT" -gt 0 ] && log "下一步：adb connect <设备IP>:$TCP_PORT"
