#!/usr/bin/env bash
#
# 一键抢救 —— 双通道全失联之后用这个。
#
# 症状：USB 只剩 05c6:9002（无 adb），WiFi 也 ping 不通，adb devices 全空。
# 前提：设备能开机、能碰到 USB 口。
#
# 用法：
#   ./revive.sh
#   DEVICE_IP=192.168.1.100 ./revive.sh
#
set -u

ADB="${ADB:-adb}"
DEVICE_IP="${DEVICE_IP:-}"
TCP_PORT="${TCP_PORT:-5555}"
LOG="${LOG:-/tmp/adb_revive.log}"
AGENT="$(cd "$(dirname "$0")" && pwd)/agent.sh"

log() { echo "$*" | tee -a "$LOG"; }
: > "$LOG"
log "=== revive $(date '+%F %T') ==="

command -v "$ADB" >/dev/null 2>&1 || { log "!! 找不到 adb"; exit 1; }

# ---------- 步骤 0：先看看现在到底有没有活路 ----------
log ""
log "--- [0/5] 现有通道盘点 ---"
"$ADB" start-server >/dev/null 2>&1
"$ADB" devices -l | tee -a "$LOG"

if [ -n "$DEVICE_IP" ]; then
    log "尝试直连 $DEVICE_IP:$TCP_PORT ..."
    "$ADB" connect "$DEVICE_IP:$TCP_PORT" >>"$LOG" 2>&1
    if "$ADB" -s "$DEVICE_IP:$TCP_PORT" shell true >/dev/null 2>&1; then
        log "*** WiFi 通道还活着，直接用，不用抢救 ***"
        TARGET="$DEVICE_IP:$TCP_PORT"
    fi
fi

# ---------- 步骤 1：蹲窗口 ----------
if [ -z "${TARGET:-}" ]; then
    log ""
    log "--- [1/5] 蹲 ADB 窗口（最长 20 分钟）---"
    log "现在请：拔掉 USB → 等 10 秒 → 重新插上（或按电源键重启设备）"

    END=$(( $(date +%s) + 1200 ))
    TARGET=""
    while [ "$(date +%s)" -lt "$END" ]; do
        S=$("$ADB" devices 2>/dev/null | awk 'NR>1 && NF>=2 && $1 !~ /:/ && $2=="device"{print $1; exit}')
        if [ -n "$S" ]; then
            log "*** 抓到窗口：$S ***"
            TARGET="$S"
            break
        fi
        sleep 0.4
    done
fi

if [ -z "${TARGET:-}" ]; then
    log "!! 20 分钟内没抓到窗口。检查：线是不是数据线 / 驱动装了吗 / 设备真的重启了吗"
    exit 1
fi

# ---------- 步骤 2：趁窗口把代理推上去 ----------
log ""
log "--- [2/5] 部署设备端代理 ---"
"$ADB" -s "$TARGET" push "$AGENT" /data/local/tmp/agent.sh >>"$LOG" 2>&1 \
    && log "  agent.sh 已推送"
"$ADB" -s "$TARGET" shell chmod 755 /data/local/tmp/agent.sh >>"$LOG" 2>&1

# 三路脱离，提高存活率（不同 ROM 支持度不同）
"$ADB" -s "$TARGET" shell "setsid /data/local/tmp/agent.sh </dev/null >/dev/null 2>&1 &" >>"$LOG" 2>&1
"$ADB" -s "$TARGET" shell "nohup /data/local/tmp/agent.sh </dev/null >/dev/null 2>&1 &"      >>"$LOG" 2>&1
"$ADB" -s "$TARGET" shell "sh /data/local/tmp/agent.sh </dev/null >/dev/null 2>&1 &"          >>"$LOG" 2>&1
log "  代理已启动（setsid / nohup / 后台 三路都试了）"

# ---------- 步骤 3：窗口内能做的立刻做 ----------
log ""
log "--- [3/5] 窗口内抢救动作 ---"
for k in $(seq 1 20); do
    "$ADB" -s "$TARGET" shell settings put global adb_enabled 1 >/dev/null 2>&1
    V=$("$ADB" -s "$TARGET" shell settings get global adb_enabled 2>/dev/null | tr -d '\r ')
    log "  [weld $k] adb_enabled=[$V]"
    [ "$V" = "1" ] && break
    sleep 0.4
done

"$ADB" -s "$TARGET" shell "settings put global airplane_mode_on 0" >/dev/null 2>&1
"$ADB" -s "$TARGET" shell "svc wifi enable" >/dev/null 2>&1
"$ADB" -s "$TARGET" shell "setprop service.adb.tcp.port $TCP_PORT" >/dev/null 2>&1
"$ADB" -s "$TARGET" tcpip "$TCP_PORT" >>"$LOG" 2>&1
log "  已投递：adb_enabled / 关飞行模式 / 开 WiFi / tcpip $TCP_PORT"

# ---------- 步骤 4：等设备侧代理跑完 ----------
log ""
log "--- [4/5] 等设备侧代理生效（最多 3 分钟）---"
for i in $(seq 1 36); do
    sleep 5
    OUT=$("$ADB" -s "$TARGET" shell "tail -5 /data/local/tmp/agent.log" 2>/dev/null)
    if echo "$OUT" | grep -q "ADB_ENABLED_OK"; then
        log "  *** 代理报告 adb_enabled 已焊死 ***"
        break
    fi
    log "  ...等待中 ($((i*5))s)"
done

# ---------- 步骤 5：验证 ----------
log ""
log "--- [5/5] 验证 ---"
sleep 3
"$ADB" devices -l | tee -a "$LOG"

if [ -n "$DEVICE_IP" ]; then
    "$ADB" connect "$DEVICE_IP:$TCP_PORT" >>"$LOG" 2>&1
    "$ADB" devices | grep -q "$DEVICE_IP" && log "*** WiFi 通道已恢复 ***"
fi

log ""
log "=== 如果还是不通 ==="
log "1. 设备上手动点开工具 App 的「一键修复」"
log "2. 进开发者选项手动打开 USB 调试"
log "3. 断电 10 秒再来一次（部分 ROM 需要冷启动才重新枚举）"
