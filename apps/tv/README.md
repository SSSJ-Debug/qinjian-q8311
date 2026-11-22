# tv · 电视直播

一个能在 Android 4.4 / 5.x 上跑起来的 IPTV 播放器：
多源聚合、频道表自动更新、HLS 代理。

**核心不是播放器，是"老设备怎么上网"这件事。**

---

## 一、老设备的 TLS 问题

Android 4.4（API 19）的 `SSLSocket` 默认只协商 TLS 1.0/1.1。
现在绝大多数 CDN 都强制 TLS 1.2+，结果就是：

```
javax.net.ssl.SSLHandshakeException: SSL handshake aborted
```

**你可能想：那就打开 TLS 1.2 呗。**

```java
// 这个方案在部分 ROM 上可行，在这台设备上不行
SSLContext ctx = SSLContext.getInstance("TLSv1.2");
```

很多定制 ROM 的 `Provider` 里根本没有 TLSv1.2 的实现，
或者 `SSLSocketFactory` 被厂商改过，强开会崩在 native 层。

---

## 二、本项目的解法：把拉取外包出去

```
   ┌──────────────┐   每 2 小时    ┌──────────────┐
   │  路由器/小主机 │ ───────────→  │  公网 m3u 源  │
   │ (OpenWrt等)  │   （TLS 没问题）└──────────────┘
   └──────┬───────┘
          │ 存成本地文件，走明文 HTTP
          ↓
   ┌──────────────┐
   │  老设备 App  │  只读 http://<局域网>/tv/list.m3u
   └──────────────┘
```

**关键点**：

- 那台"代拉"的设备只需要 24 小时在线 + 有正常的 TLS 栈（现代路由器都行）
- 老设备只走**明文 HTTP**，绕开所有 TLS 问题
- 保留几条公网源做兜底（能连上的时候还是有点用的）

这个套路对所有"设备老、但家里有台常开的机器"的场景都通用。

---

## 三、代码结构

```
src/com/lm/tv/
  MainActivity.java    界面 + 播放控制（自绘，无第三方 UI 库）
  HlsProxy.java        本地 HLS 代理：把 m3u8 拉下来改写分片地址
  SourceUpdater.java   多源聚合 + 频道表更新 + 6 小时缓存
```

### HlsProxy 为什么需要

部分源的 m3u8 里分片地址是相对的，或者带了 `Referer`/`User-Agent` 校验。
播放器（尤其是老的 MediaPlayer）不会带这些头，直接播会 403。

本地起一个 `ServerSocket`，把 m3u8 拉下来、改写分片为绝对地址、加上必要的头，
再喂给播放器。这样播放器只需要播 `http://127.0.0.1:<port>/xxx.m3u8`。

### SourceUpdater 的缓存策略

```
启动 → 检查本地 channels.txt 的 mtime
     → 6 小时内 → 直接用（不联网，秒开）
     → 超过 6 小时 → 后台拉新，先播旧的
     → 拉失败 → 继续用旧的，静默重试
```

**永远不要让"更新节目源"阻塞"播放"。** 老设备上任何网络等待都是几百毫秒起步。

---

## 四、改造成你自己的

```java
// SourceUpdater.java
static final String[] LISTS = {
        "http://<你的路由器>/tv/result.m3u",     // 局域网副本（首选）
        "https://.../result.m3u",                 // 公网兜底
};
```

把第一条改成你自己的局域网地址，或者直接删掉只留公网（前提是你的设备 TLS 没问题）。

频道表格式就是标准 m3u：

```
#EXTM3U
#EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV-1 综合
http://example.com/cctv1.m3u8
```

---

## 五、已知限制

- 不支持 DRM
- 不支持 4K（老 GPU 解不动）
- 频道切换有 1~2 秒黑屏（MediaPlayer 重初始化的代价，换 ExoPlayer 能好但体积会大 20 倍）
- 只做了 HLS，没做 RTMP / FLV

这些限制在这个使用场景下都能接受：**能稳定看频道，比什么都重要。**
