# tools · 工具链

不需要装 Android Studio。下面这些够用。

---

## 必需

### adb / platform-tools

```bash
# macOS
brew install android-platform-tools
# 或直接下载（官方，免安装）
# https://developer.android.com/tools/releases/platform-tools
```

版本要求：**≥ 1.0.41**。太老的 adb 对 USB 热插拔的处理有问题，抓窗口时会漏。

### git

自带的就够。

---

## 反编译 / 重打包

### jadx —— 看 Java 源码

```bash
brew install jadx
# 或下载 release：https://github.com/skylot/jadx/releases

jadx -d out_dir app.apk              # 全量
jadx -d out --no-res app.apk         # 不要资源，快一点
```

**用途**：读懂逻辑，找硬编码坐标，找 API 调用。
**注意**：jadx 的输出**不能直接编译**（匿名类、混淆、缺失的泛型），只能读。

### apktool —— 改 smali 并回编译

```bash
# 下载 apktool.jar：https://github.com/iBotPeaches/Apktool/releases
java -jar apktool.jar d -f app.apk -o app_dir      # 反编译
java -jar apktool.jar b app_dir -o app_new.apk     # 回编译
```

**需要 Java 8+**。macOS 上如果报找不到 Java：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

---

## 签名

### 生成自己的 keystore

```bash
keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

### 对齐 + 签名

```bash
BT=$ANDROID_HOME/build-tools/34.0.0

$BT/zipalign -f 4 app_new.apk app_aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out app_signed.apk app_aligned.apk
$BT/apksigner verify app_signed.apk
```

**顺序不能反**：先 `zipalign` 再 `apksigner`。
反过来签名会失效。

### 装到设备

```bash
adb uninstall <包名>          # 签名变了必须先卸载
adb install -r app_signed.apk
```

---

## aapt2 —— 查 APK 元信息

```bash
BT/aapt2 dump badging app.apk | grep -E "package:|sdkVersion|native-code"

# 查资源里的布尔值（比如某个功能开关）
BT/aapt2 dump resources app.apk | grep -A2 "profile_supported"
```

**用途**：装之前先确认 `minSdk` 和 `native-code` 架构，避免白装一次。

```bash
# 典型输出
package: name='com.example.app' versionCode='78010' versionName='9.0.701'
sdkVersion:'21'                    ← 设备的 API 必须 ≥ 这个
targetSdkVersion:'33'
native-code: 'arm64-v8a'           ← 设备架构必须包含其中一个
```

---

## 设备端能用但很简陋

Android 5.x 的 toolbox 里**没有** `head` / `tail` / `awk` / `grep`（部分有）。
凡是过滤，统统放到宿主机侧做：

```bash
# ✅ 对
adb shell "dumpsys activity" | grep mResumedActivity

# ❌ 错（设备上可能没 grep）
adb shell "dumpsys activity | grep mResumedActivity"
```

另外 `ps` 只显示 15 字符的进程名，判断进程要读 `/proc/*/cmdline`。

---

## 一个最小可用的编译流程（无 Gradle）

老项目常常是"一个 src 目录 + 一个 Manifest"，没有 Gradle 也能编：

```bash
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-19/android.jar

mkdir -p build/classes
javac -source 1.7 -target 1.7 -bootclasspath $PLATFORM \
      -d build/classes $(find src -name "*.java")

$BT/d8 --output build/ --lib $PLATFORM $(find build/classes -name "*.class")
# 得到 build/classes.dex

$BT/aapt2 link -o build/base.apk -I $PLATFORM \
      --manifest AndroidManifest.xml -R res/* --auto-add-overlay
# 再把 classes.dex 塞进去（用 zip 或 aapt add）
```

麻烦但不依赖 Gradle —— 在只有 JDK 的机器上能救命。
