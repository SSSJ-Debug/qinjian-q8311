# 亲见 Q8311 智能音箱

> 一台锁在客厅角落的圆屏音箱，和一根数据线能做成的事。
>
> 本项目是 **亲见 Q8311**（Android 4.4.4 / 8 寸圆形屏 / 高通 MSM8909）的完整改造记录：
> 核心是一套**不拆机、不刷机、不 root**，纯靠 USB 线 + 轮询脚本重新拿到 ADB 权限的通用方法，
> 外加圆屏适配方案、实测可装应用清单，以及这台机器上踩过的所有坑。
>
> 一句话：**ADB 不是没有，是只存在 8~14 秒。**

<sub>仓库名 `qinjian-q8311` 读作「**亲见**」——GitHub 仓库名不支持中文字符，故用拼音代替。</sub>

[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey)](#平台差异)
[![No Root](https://img.shields.io/badge/root-not%20required-success)](#)
[![Android](https://img.shields.io/badge/android-4.4%20~%2010-blue)](#适用范围)

---

## 这个问题长什么样

你插上 USB 线，设备管理器 / `system_profiler` / `lsusb` 都能看到它：

```
Vendor ID:  0x05c6   (Qualcomm)
Product ID: 0x9002
```

但 `adb devices` 永远是空的。翻遍设置也找不到「USB 调试」开关，或者找到了打开也没用。

**绝大多数人到这里就放弃了，结论是"这机器没有 ADB"。**

**真相是：ADB 有，只是活不过开机后的十几秒。**

高通平台的 USB 组合是**动态**的，同一个设备在不同阶段枚举成不同的 PID：

| VID:PID | 包含接口 | 有 ADB 吗 |
|---|---|---|
| `05c6:9002` | diag / serial_tty / serial_smd | ❌ |
| `05c6:9020` | 上面三个 + **`ff/42/01`（ADB 接口）** | ✅ |
| `05c6:9008` | EDL 紧急下载模式 | — |

系统启动时 `adbd` 先跑起来（此时枚举成 `9020`），等 `UsbDeviceManager` 读到 `adb_enabled = 0`（出厂默认），就把 adb 从组合里摘掉，退回 `9002`。

```
开机 → adbd 启动 → USB 枚举 9020（8~14 秒窗口）→ 系统起完 → 摘掉 adb → 退回 9002
```

所以策略不是"连上去"，而是**蹲守那个窗口**。

---

## 三步法

```
①  蹲窗口          ②  在窗口内做对的事        ③  把状态焊死
   ─────────         ──────────────────        ──────────────
 轮询 adb devices   只能 setprop，不能 settings   adb_enabled=1 + 开机自修复
 间隔 0.3~0.5s      （AMS 还没起来）            或 adb tcpip 常驻
```

两条路线，按你的设备情况选：

| 路线 | 做什么 | 适合 |
|---|---|---|
| **A. 切 TCP**（推荐先试） | 窗口内 `adb tcpip 5555`，之后全程走 WiFi | WiFi 能起来、能拿到 IP |
| **B. 只焊接** | 窗口内反复 `settings put global adb_enabled 1` | WiFi 不可靠，或想保住 USB 通道 |

**A 和 B 不冲突，实战里两个都做。**

---

## 快速开始

### 0. 准备

```bash
# 只需要 platform-tools（adb），不需要 SDK 全家桶
# macOS: brew install android-platform-tools
# 或直接下载: https://developer.android.com/tools/releases/platform-tools

adb version   # 确认能用
```

### 1. 先确认你的设备满足前提

```bash
# macOS
ioreg -p IOUSB -w0 -l | grep -B2 -A12 "Android"

# Linux
lsusb | grep 05c6

# Windows (PowerShell)
Get-PnpDevice | Where-Object {$_.InstanceId -like "*VID_05C6*"}
```

看 `Product ID`：

- `0x9002` → **没 ADB**，正常，去蹲窗口
- `0x9020` → **有 ADB**，立刻跑脚本，别等
- `0x9008` → EDL 模式，本方法不适用

### 2. 跑窗口收割脚本

```bash
# 先把脚本里的 ADB 路径改成你自己的
vim scripts/harvest.sh        # 改 ADB=/path/to/adb

# 先启动脚本，再给设备开机/重启
./scripts/harvest.sh

# 全程日志
tail -f /tmp/adb_harvest.log
```

看到这些就是成了：

```
##### [10:24:31] WINDOW HIT: <serial> #####
  [1] adb_enabled=[1] 10:24:33
  *** adb_enabled=1 成功 ***
  *** tcpip 5555 已投递 ***
```

### 3. 从网络侧接管

```bash
adb connect <设备IP>:5555
adb -s <设备IP>:5555 shell echo OK
```

**到这一步就自由了**——ActivityManager 已经起来，`settings` / `pm` / `am` 全都能用。

### 4. 焊死，防止下次重启又要重来

```bash
adb shell settings put global adb_enabled 1
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
```

然后装一个开机自修复的 App（见 [`apps/`](apps/)），让它每次开机自己把 `adb_enabled` 制造一次变化事件。

---

## 为什么"窗口期只能 setprop"这件事是全局关键

这是所有人第一次都会踩的坑，也是整件事的分水岭：

```
开机 ──→ adbd 运行（窗口期）──→ 系统起完（AMS 就绪）
         ↑                        ↑
    只能 setprop              才能 settings put
    写 settings 必崩          但 adb 已经没了
```

**死循环。** 窗口期写 `settings` 会报 `IActivityManager == null` 或 `SecurityException`，
等 AMS 起来了 adb 又断了。

打破死循环的办法只有两个：

1. **`adb tcpip 5555`** —— 让 adbd 换个地方（网络）监听，摆脱 USB 组合的反复横跳
2. **设备端常驻代理** —— 窗口期把脚本 push 进去 `setsid` 脱离启动，让它在设备侧等 AMS 就绪再执行

两个都在 [`scripts/`](scripts/) 里。

---

## 仓库内容

```
docs/
  01-原理.md              ADB 为什么只存在几秒（USB 组合 / adbd / UsbDeviceManager）
  02-窗口收割.md          脚本怎么写，轮询间隔怎么定，为什么 0.3~0.5s
  03-持久化.md            让 ADB 每次开机自己回来（BootReceiver + 变化事件）
  04-事故抢救.md          双通道全失联了怎么救（这篇能救砖）
  05-踩坑清单.md          13 个坑，按踩到的概率排序
  06-跨平台与跨设备.md     macOS / Windows / Linux 差异，高通以外能不能用
  07-换一台机器怎么套.md  把方法迁移到别的机型/系统的检查表

scripts/
  harvest.sh       窗口收割 —— 轮询 + 焊接 + 切 TCP（macOS / Linux）
  harvest.ps1      同上，Windows PowerShell 版
  agent.sh         设备端常驻代理 —— 脱离 adb 连接继续跑
  revive.sh        一键抢救 —— 双通道失联时的组合拳

apps/
  guard/           看家（人脸检测抓拍 → 邮件告警），含 1280→1024 屏幕适配补丁
  tv/              电视直播（HLS 代理 + 多源聚合），含旧 TLS 栈的绕行方案

tools/
  README.md        adb / aapt2 / apksigner 版本说明，签名与重打包
```

---

## 适用范围

**同时满足才有戏：**

| 条件 | 怎么查 | 说明 |
|---|---|---|
| 高通平台 | USB 出现 `05c6` | 有 diag/serial/adb 的动态组合 |
| `ro.adb.secure = 0` | 窗口期 `getprop ro.adb.secure` | **关键**，为 0 才不需要在屏幕上盲点授权 |
| `persist.sys.usb.config` 含 `adb` | 窗口期 `getprop` | 说明固件没彻底删掉 adb 通道 |
| 能物理接触 USB 口 | — | 不能拆机就得有外露口 |
| 设备能开机 | — | 窗口只存在于开机过程 |

**不适用：**

- 联发科 / 展讯平台（USB 组合逻辑不同，需要换思路）
- `ro.adb.secure = 1` 且屏幕不可操作（窗口期内没法完成授权点击）
- 固件已彻底移除 adbd（`persist.sys.usb.config` 里连 adb 都没有）
- 只能进 EDL（`9008`）—— 那要走 firehose 刷机，不是这个仓库的范围

> **白牌机、定制机、运营商定制终端**命中率最高——厂商常常保留 adb 通道只为产线测试，
> 只是把用户侧的开关藏了/关了。

---

## 一条最重要的经验

> **先确认"现在有几条通道"，只剩一条时，禁止做任何可能触发重启的操作。**

USB 和 WiFi 是你的两条命。只剩一条的时候去改系统设置、去 `am force-stop` 关键服务、
去 `settings put` 一个大范围开关——任何一个意外重启都会让你同时失去两条通道，
然后你只能拔电源（如果设备有电池，还得等它放完电）。

我们踩过这个坑，完整过程写在 [`docs/04-事故抢救.md`](docs/04-事故抢救.md)。

---

## 平台差异

| 平台 | 可行性 | 额外门槛 |
|---|---|---|
| **macOS** | ✅ | 零门槛，adb 走原生 USB，不用装驱动 |
| **Linux** | ✅ | 需要 udev 规则或全程 sudo；`qcserial` 会抢占串口接口但不影响 ADB 接口 |
| **Windows** | ✅ | 要给 `VID_05C6` 手动装 ADB 驱动（设备管理器指定） |

---

## 许可

MIT。方法本身没有版权，踩过的坑值得被后来的人绕开。

如果这套流程救活了你的设备，欢迎开 issue 报告你的机型 —— 让"适用范围"这张表越来越准。
