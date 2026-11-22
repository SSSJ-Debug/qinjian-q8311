# 补丁：把 1280 宽的界面适配到 1024 宽的屏幕

> 这是一个**通用问题**，不只针对本项目：
> 别人写的 App 到你设备上"右边少一块"、"按钮点不到"、"文字被切"，
> 绝大多数都是同一类原因——**坐标是按别的屏幕尺寸写死的**。

---

## 一、症状

界面右侧有一整块内容从来没见过，文字断在半截：

```
……点「开始看家」后这里显示实       ← 后面没了
```

右上角的按钮只看到一半，甚至完全看不到。

**先别怀疑系统、别怀疑分辨率设置。先怀疑 App 的坐标。**

---

## 二、怎么定位（10 分钟）

### 步骤 1：确认窗口本身是全屏的

```bash
adb shell dumpsys window windows | grep -A6 "<包名>/"
```

看这一行：

```
Requested w=1024 h=768
mConfiguration={... w1024dp h768dp 160dpi ...}
```

如果 `w=1024 h=768`、`fillxfill`，说明**窗口没问题，是 App 内部布局溢出**。

### 步骤 2：反编译，找硬编码坐标

```bash
# 抓 APK
adb shell pm path <包名>            # 得到 /data/app/xxx/base.apk
adb pull /data/app/xxx/base.apk ./app.apk

# 反编译（jadx 看逻辑，apktool 改代码）
jadx -d src app.apk
apktool d -f app.apk -o app_dir
```

在 `onDraw` 或 `onLayout` 里找**超出屏幕宽度的数字**：

```java
canvas.drawText("返回桌面", 1046.0f, 66.0f, tp);      // 1046 > 1024，铁证
canvas.drawRoundRect(642, 108, 1248, 456, 22, 22, p); // 右边界 1248
```

**判断标准**：如果最大 x 坐标接近 1280（或 1600、1920），说明它是按那个宽度设计的。

### 步骤 3：确认设计稿宽度

把所有绘制坐标的最大值找出来，取一个"整齐"的数：

| 最大坐标 | 推测设计宽度 |
|---|---|
| 1248 / 1204 / 1026 | **1280** |
| 1560 / 1580 | 1600 |
| 1900 / 1918 | 1920 |

---

## 三、两种修法，选哪个

### 方案 A：逐个改坐标（不推荐）

看起来最直接，实际最坑：

- 同一个数字（比如 `1204`）常常在"按钮右边"和"箭头位置"两处复用，**改一处毁另一处**
- 绘制坐标和**触摸判定坐标**是两套代码，改了一处忘另一处 → 看起来对了但点不动
- 坐标有几十个，漏一个就是一条错位的线

**除非你的界面只有十几个坐标，否则别走这条。**

### 方案 B：整体缩放（推荐）⭐

把整棵视图树当成一张"设计稿大小的画布"，等比缩放到真实屏幕上。

```java
public static void wrap(Activity a, View root) {
    FrameLayout outer = new FrameLayout(a);

    // 1. 让内容树拿到设计稿大小的布局空间
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1280, 768);
    outer.addView(root, lp);

    // 2. 等比缩放
    DisplayMetrics dm = a.getResources().getDisplayMetrics();
    float s = Math.min(dm.widthPixels / 1280f, dm.heightPixels / 768f);

    root.setPivotX(0);
    root.setPivotY(0);
    root.setScaleX(s);
    root.setScaleY(s);

    // 3. 垂直居中（内容变矮了，居中更好看）
    root.setTranslationY((dm.heightPixels - 768f * s) / 2f);

    a.setContentView(outer);
}
```

调用处，把 `setContentView(root)` 换成：

```java
Fit.wrap(this, root);
```

**三个界面都这么改**（主界面 / 子页面 / 相册页），一行一个。

---

## 四、为什么方案 B 连触摸都不用改

这是它最妙的地方：

```
用户点在屏幕 (512, 400)
        ↓  系统按 View 的 scale/pivot/translation 做矩阵逆变换
子 View 收到 (640, 404)   ← 正是设计稿坐标系
        ↓
原来的 in(x, y, 130, 470, 438, 512) 判断照旧精确命中
```

`View.dispatchTouchEvent` 内部会做 `Matrix.invert()` 再用逆矩阵映射坐标，
**你完全不用碰事件代码**。这也是为什么它比"逐个改坐标"安全一个数量级。

---

## 五、反编译 APK 怎么改（没有源码时）

如果拿不到源码，只能改 smali：

### 5.1 加一个新类

```smali
# smali/com/xxx/Fit.smali
.method public static wrap(Landroid/app/Activity;Landroid/view/View;)V
    .locals 8
    # ... 上面 Java 逻辑的 smali 翻译 ...
.end method
```

### 5.2 把三处 setContentView 换掉

```diff
- invoke-virtual {p0, p1}, Lcom/xxx/MainActivity;->setContentView(Landroid/view/View;)V
+ invoke-static {p0, p1}, Lcom/xxx/Fit;->wrap(Landroid/app/Activity;Landroid/view/View;)V
```

好处：`p0` 就是 Activity 自身，`p1` 就是 root View，**两个参数都是现成的，不用分配新寄存器**。

### 5.3 回编译 + 签名

```bash
apktool b app_dir -o app_new.apk
zipalign -f 4 app_new.apk app_aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android \
  --out app_signed.apk app_aligned.apk
adb uninstall <包名>
adb install app_signed.apk
```

**注意**：签名变了必须卸载重装，App 的本地数据（SharedPreferences 等）会清空。

---

## 六、两个必须知道的坑

### 坑 1：View 的 canvas 有自己的边界

想把背景画得比 View 高、靠缩放补满屏幕——**做不到**：

```java
canvas.drawRect(0, 0, getWidth(), 960, paint);   // View 只有 768 高
                                                  // 超出的部分被 Canvas 裁掉
```

View 的 canvas 会被 clip 到 View 自身的 bounds。想补背景，得在**外层容器**上设背景。

### 坑 2：上下留边是必然的

等比缩放后，1280×768 的内容放进 1024×768，会变成 1024×614 —— 上下各留 77px。

三个选择：

| 做法 | 效果 |
|---|---|
| 接受它（推荐） | 内容完整、比例正确，像"信箱模式"，不难受 |
| 外层铺同色渐变背景 | 视觉上几乎看不出边界 |
| 强行 `scaleY=1.0` | 填满了，但字全被压扁 20%，圆变椭圆 |

---

## 七、这个思路的适用范围

任何"坐标写死 + 屏幕尺寸不匹配"的情况都适用：

- 老电视 App 装到小屏上
- 手机 App 装到车机/平板上
- 定制 ROM 改了分辨率导致原厂 App 显示不全
- 同一个 APK 要给多种尺寸的设备用

**判断口诀**：只要界面是"自绘"或者用了大量 `setXxx(固定dp/px)`，
且最大坐标 ≠ 实际屏幕尺寸——就是这个病，这个方子能治。
