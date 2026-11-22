# 窗口收割机 —— Windows PowerShell 版
#
# 用法：
#   .\harvest.ps1
#   .\harvest.ps1 -Duration 600 -AdbPath "C:\platform-tools\adb.exe"
#
# 前提：已为 VID_05C6 安装 ADB 驱动（设备管理器 → 更新驱动 → 手动指定 Android ADB Interface）

param(
    [string]$AdbPath  = "adb",
    [int]$Duration    = 1800,
    [double]$Interval = 0.4,
    [int]$TcpPort     = 5555,
    [switch]$NoTcpip
)

$Log = "$env:TEMP\adb_harvest.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss')  $msg"
    Write-Host $line
    Add-Content -Path $Log -Value $line
}

Log "=== harvest start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="

& $AdbPath kill-server  2>$null | Out-Null
Start-Sleep -Seconds 1
& $AdbPath start-server 2>$null | Out-Null

$deadline = (Get-Date).AddSeconds($Duration)
$hits = 0

while ((Get-Date) -lt $deadline) {

    $devices = & $AdbPath devices 2>$null
    # 只要 USB 设备（序列号不含冒号）
    $serial = ($devices | Select-Object -Skip 1 |
               Where-Object { $_ -match "^\S+\s+device$" -and $_ -notmatch ":" } |
               ForEach-Object { ($_ -split "\s+")[0] } |
               Select-Object -First 1)

    if (-not $serial) {
        Start-Sleep -Milliseconds ([int]($Interval * 1000))
        continue
    }

    $hits++
    Log ""
    Log "##### WINDOW HIT #$hits : $serial #####"

    # ---- 焊接 adb_enabled ----
    $ok = $false
    for ($k = 1; $k -le 30; $k++) {
        & $AdbPath -s $serial shell settings put global adb_enabled 1 2>$null | Out-Null
        $v = (& $AdbPath -s $serial shell settings get global adb_enabled 2>$null) -replace '\s',''
        Log "  [weld $k] adb_enabled=[$v]"
        if ($v -eq "1") { Log "  *** adb_enabled=1 焊接成功 ***"; $ok = $true; break }
        Start-Sleep -Milliseconds 400
    }
    if (-not $ok) { Log "  !! 焊接未成功，继续蹲" }

    # ---- 切 TCP ----
    if (-not $NoTcpip) {
        & $AdbPath -s $serial shell setprop service.adb.tcp.port $TcpPort 2>$null | Out-Null
        $r = & $AdbPath -s $serial tcpip $TcpPort 2>&1
        Log "  tcpip: $r （USB 断开是正常的）"
    }

    # ---- 给第二条通道上保险 ----
    & $AdbPath -s $serial shell "settings put global airplane_mode_on 0" 2>$null | Out-Null
    & $AdbPath -s $serial shell "svc wifi enable" 2>$null | Out-Null

    Start-Sleep -Seconds 3
}

Log ""
Log "=== harvest end  hits=$hits ==="
if ($hits -gt 0) { Log "下一步：adb connect <设备IP>:$TcpPort" }
