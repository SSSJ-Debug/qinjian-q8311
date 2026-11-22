#!/system/bin/sh
#
# 设备端常驻代理 —— 在 adb 断开之后继续跑。
#
# 为什么需要它：
#   窗口期 ActivityManager 还没起来，settings 命令必崩；
#   等 AMS 起来了，adb 窗口又已经关了。
#   所以让脚本留在设备上，等 AMS 就绪后自己干活。
#
# 部署方式（宿主机侧，窗口期内）：
#   adb push agent.sh /data/local/tmp/agent.sh
#   adb shell chmod 755 /data/local/tmp/agent.sh
#   adb shell "setsid /data/local/tmp/agent.sh </dev/null >/dev/null 2>&1 &"
#
# 日志：/data/local/tmp/agent.log
#
LOG=/data/local/tmp/agent.log
APK=${AGENT_APK:-/data/local/tmp/qjhome.apk}
DO_INSTALL=${DO_INSTALL:-0}

echo "=== agent start $(date) uid=$(id) ===" >> $LOG

# ---------- 阶段一：等 AMS 就绪 ----------
# 判据：settings get 能正常返回。返回空或报错就说明 AMS 还没发布。
n=0
while [ $n -lt 180 ]; do
    V=$(settings get global adb_enabled 2>&1)
    case "$V" in
        *Exception*|*"not found"*|"") echo "[$(date +%H:%M:%S)] AMS 未就绪 ($V)" >> $LOG ;;
        *) echo "[$(date +%H:%M:%S)] AMS ready, adb_enabled=$V" >> $LOG; break ;;
    esac
    n=$((n+1))
    sleep 1
done

# ---------- 阶段二：关飞行模式、开 WiFi ----------
# 这两步优先于 adb_enabled —— 先把第二条通道救回来
settings put global airplane_mode_on 0 >> $LOG 2>&1
svc wifi enable >> $LOG 2>&1
echo "[$(date +%H:%M:%S)] airplane=$(settings get global airplane_mode_on) wifi=$(settings get global wifi_on)" >> $LOG

# ---------- 阶段三：把 adb 焊回 USB 组合 ----------
# 关键：制造一次变化事件。
# UsbDeviceManager 的 AdbSettingsObserver 只在值"变化"时才重算 USB 组合，
# 如果开机时它读到 0 把 adb 摘了，之后光写 1 不一定触发；
# 先写 0 再写 1，制造变化，保证触发。
m=0
while [ $m -lt 60 ]; do
    settings put global adb_enabled 0 >/dev/null 2>&1
    sleep 1
    settings put global adb_enabled 1 >/dev/null 2>&1
    V=$(settings get global adb_enabled 2>&1 | tr -d '[:space:]')
    echo "[$(date +%H:%M:%S)] weld#$m adb_enabled=$V" >> $LOG
    if [ "$V" = "1" ]; then echo "[$(date +%H:%M:%S)] ADB_ENABLED_OK" >> $LOG; break; fi
    m=$((m+1))
    sleep 1
done

# ---------- 阶段四：标记初始化完成（绕过激活向导） ----------
settings put global device_provisioned 1 >> $LOG 2>&1
settings put secure user_setup_complete 1 >> $LOG 2>&1
echo "[$(date +%H:%M:%S)] provisioned=$(settings get global device_provisioned) setup=$(settings get secure user_setup_complete)" >> $LOG

# ---------- 阶段五（可选）：装一个桌面/工具 App ----------
if [ "$DO_INSTALL" = "1" ] && [ -f "$APK" ]; then
    k=0
    while [ $k -lt 60 ]; do
        R=$(pm install -r "$APK" 2>&1)
        echo "[$(date +%H:%M:%S)] install#$k: $R" >> $LOG
        case "$R" in *Success*) break ;; esac
        k=$((k+1))
        sleep 2
    done
fi

echo "[$(date +%H:%M:%S)] agent finish" >> $LOG
