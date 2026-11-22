package com.lm.tv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import android.util.Log;

/**
 * HlsProxy —— 本地多连接预取代理（v4：长稳版）
 *
 * 【上游真实结构】5 个分片 × 10 秒 × 11.4MB，10 秒滑动窗口。
 *   1080i 码率 1.085 MB/s，而服务器单连接限速 0.64 MB/s —— 单连接连不上播放。
 *
 * 【v3 为什么要推倒重来】v3 播 30 秒左右整屏冻死，且再也不恢复。三个致命点：
 *   1) 响应里既没有 Cache-Control，playlist 又写着 ALLOW-CACHE:YES ——
 *      播放器（Chromium 网络栈）用启发式缓存把 m3u8 缓存住了，
 *      之后刷新拿到的永远是同一份、分片也已播完 → 它认为"没有新内容"，
 *      于是安静地什么都不再请求（实测：代理 10 个 HTTP 线程全空闲、
 *      播放器那条连接空转、画面 5 秒内两张截图一模一样）。
 *   2) 失败路径一律返回 "HTTP/1.0 200 OK + 一段 text"。播放器把那段文字
 *      当 TS 数据吃掉，既不报错也不推进 —— 于是"卡死"而不是"报错重连"。
 *   3) MEDIA-SEQUENCE 用"上游序号 + 裁剪偏移"算，上游那个序号其实是
 *      最新一片的编号，两边叠一起必然对不上，窗口一抖序号就可能回退。
 *
 * 【v4 的六条根治】
 *   1) 所有响应强制 no-store/no-cache，playlist 里 ALLOW-CACHE:NO；
 *      并且播放地址带递增会话号，缓存键每次都不同 —— 从物理上别想缓存。
 *   2) 窗口放到跟上游一样 5 片（50 秒余量），并在内存里"只前进不后退"：
 *      某次回源失败就继续用上一份，绝不给播放器一个更旧的窗口。
 *   3) MEDIA-SEQUENCE 直接用分片文件名里的真实编号（live_1_1_<编号>.ts），
 *      严格单调递增，不会再回退。
 *   4) 拿不到的分片返回真正的 404/503，让播放器立刻报错 → 走重连逻辑，
 *      而不是吞一段文字然后干等。
 *   5) 缓存清理只删"没在窗口里、没在最近两份 playlist 里、也不是正在播"的，
 *      不会再出现"文件被删掉了但 Seg 还说自己 done"的死状态。
 *   6) 传输中途断流就把 socket 掐掉（不发满 Content-Length），
 *      让播放器知道这段坏了，配合 MainActivity 的看门狗 5 秒内自愈。
 */
public class HlsProxy {
    static final String TAG = "TV";
    static final int PORT = 18765;
    static final int WINDOW = 5;                // 交给播放器的窗口：跟上游一样 5 片（50 秒余量）
    static final int CHUNK = 512 * 1024;        // 下载块大小，prefix 以 512KB 粒度连推
    static final int DL_THREADS = 6;            // 下载连接数（单连 0.64MB/s，需 >2 路才够）
    static final int HTTP_THREADS = 10;
    static final long PL_TTL = 1200;            // playlist 最多缓存 1.2 秒（回源本身才几十毫秒）
    static final long RETAIN_MS = 60000;        // 离开窗口的分片至少再留 60 秒
    static final int PIN_MAX = 12;              // 最近 playlist 里出现过的 key 全部钉住不删

    /** 一个分片的下载状态 */
    static class Seg {
        final long seq;                         // 分片真实编号（也是 media sequence）
        final String key;
        final String url;
        final File file;
        volatile long size = -1;                // 总字节数
        volatile long prefix = 0;               // 从 0 起「连续」可读到的字节数
        volatile boolean done = false;
        volatile boolean failed = false;
        volatile int gen = 0;
        volatile int chunkCount = 0;
        volatile byte[] chunkState;             // 每块状态：0=空闲 1=有人在下载 2=已落盘
        volatile long[] chunkAt;                // 每块被认领的时刻（用来发现"有人卡死了"）
        volatile int[] chunkTry;                // 每块尝试次数
        volatile int prefixChunk = 0;           // prefix 已推进到的块号
        volatile long lastServe = 0;            // 最近被播放器读取的时间（保护不被删）
        volatile long firstServeAt = 0;         // 第一次被播放器读取的时间（判定"死磕同一片"）
        final AtomicInteger served = new AtomicInteger(0);
        Seg(long q, String k, String u, File f) { seq = q; key = k; url = u; file = f; }
    }

    private final File dir;
    private ServerSocket server;
    private Thread acceptThread;
    private Thread prefetchThread;
    private ExecutorService httpPool;
    private Thread[] dlThreads;
    private volatile boolean running = false;
    private volatile String upstream = null;
    private final AtomicInteger gen = new AtomicInteger(0);
    /** 唤醒预取线程用 */
    private final Object wake = new Object();
    /** 播放器此刻正在读的分片：下载线程优先喂它 */
    private volatile Seg playing = null;

    /** 当前窗口（首片在最前，即播放器起播片），只在 refreshPlaylist 里整体替换 */
    private final List<Seg> window = new ArrayList<Seg>();
    private final Object winLock = new Object();
    private final ConcurrentHashMap<String, Seg> segs = new ConcurrentHashMap<String, Seg>();
    private final ConcurrentHashMap<String, String> key2url = new ConcurrentHashMap<String, String>();
    /** 最近的 playlist 里出现过的 key，这些分片和文件一律不许删 */
    private final LinkedHashSet<String> pinned = new LinkedHashSet<String>();

    /** 已经交给播放器的最后一份 playlist 文本 */
    private volatile String plBody = null;
    private volatile String plFor = null;
    private volatile long plAt = 0;
    /** 播放器最近一次来要东西的时间（playlist 或分片），看门狗用来判断"它还在拉数据吗" */
    private volatile long lastReqAt = 0;
    /** 播放器最近一次"吃到一个它没见过的分片"的时间 —— 真·画面在推进的唯一硬指标 */
    private volatile long lastNewSegAt = 0;
    /** 最近一次成功回源的时间，用来区分"上游断了"和"播放器卡了" */
    private volatile long lastPlOkAt = 0;
    /** 反复索要同一分片的段号集合 */
    private final java.util.HashSet<Long> storm = new java.util.HashSet<Long>();
    private final Object stormLock = new Object();

    public long msSinceLastRequest() {
        long t = lastReqAt;
        return (t == 0) ? Long.MAX_VALUE : (System.currentTimeMillis() - t);
    }

    /**
     * 距离上次给播放器一个「全新分片」多久了。
     * 健康直播里每 10 秒必然有一个新分片被吃进去；冻住时它只会反复要旧片，这个值会一直涨。
     * 注意：不能拿 getCurrentPosition() 当判据 —— 实测这台机器画面冻死时，
     * 位置照样每秒 +1000ms 地往上涨，完全骗过基于位置的检测。
     */
    public long msSinceNewSegment() {
        long t = lastNewSegAt;
        return (t == 0) ? Long.MAX_VALUE : (System.currentTimeMillis() - t);
    }

    /** 上游多久没回源成功了（>8 秒基本就是源断了） */
    public long upstreamStaleMs() {
        long t = lastPlOkAt;
        return (t == 0) ? Long.MAX_VALUE : (System.currentTimeMillis() - t);
    }
    /** 窗口里最后一片的编号，用于「只前进不后退」判定 */
    private volatile long lastSeq = -1;
    private volatile long firstSeq = -1;

    private static HlsProxy inst;

    public static synchronized HlsProxy get(File cacheDir) {
        if (inst == null) inst = new HlsProxy(cacheDir);
        return inst;
    }

    /** 播放地址：带会话号，让播放器每次重开都拿到新的缓存键 */
    public static String localUrl(String upstreamUrl, int session) {
        try {
            return "http://127.0.0.1:" + PORT + "/p?s=" + session + "&u="
                    + URLEncoder.encode(upstreamUrl, "UTF-8");
        } catch (Exception e) {
            return upstreamUrl;
        }
    }

    private HlsProxy(File cacheDir) {
        dir = new File(cacheDir, "hls4");
        if (!dir.exists()) dir.mkdirs();
        cleanDir();
    }

    public synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket(PORT, 24, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            running = false;
            Log.e(TAG, "端口被占，代理不可用: " + e);
            return;      // 端口被占（上次残留）→ 放弃代理，App 退回直连
        }
        running = true;
        httpPool = Executors.newFixedThreadPool(HTTP_THREADS);

        acceptThread = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "hls-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        prefetchThread = new Thread(new Runnable() {
            public void run() { prefetchLoop(); }
        }, "hls-prefetch");
        prefetchThread.setDaemon(true);
        prefetchThread.start();

        // 下载线程常驻，自己从「优先级队列」里抢块，不在 pool 里嵌套阻塞
        dlThreads = new Thread[DL_THREADS];
        for (int i = 0; i < DL_THREADS; i++) {
            dlThreads[i] = new Thread(new Runnable() {
                public void run() { dlLoop(); }
            }, "hls-dl-" + i);
            dlThreads[i].setDaemon(true);
            dlThreads[i].start();
        }
        Log.i(TAG, "代理启动 :" + PORT + "，窗口 " + WINDOW + " 片");
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (httpPool != null) httpPool.shutdownNow();
    }

    /** 切台：换代次、清缓存，下载线程立刻转向新频道 */
    public void setChannel(String url) {
        if (url == null || url.equals(upstream)) return;
        Log.i(TAG, "代理切台 -> " + url);
        upstream = url;
        gen.incrementAndGet();
        playing = null;
        segs.clear();
        key2url.clear();
        synchronized (winLock) { window.clear(); }
        synchronized (pinned) { pinned.clear(); }
        plBody = null;
        plFor = null;
        plAt = 0;
        lastSeq = -1;
        firstSeq = -1;
        lastNewSegAt = 0;
        lastPlOkAt = 0;
        lastReqAt = 0;
        cleanDir();
        synchronized (wake) { wake.notifyAll(); }   // 立刻唤醒预取线程去拉新频道
    }

    /** 看门狗用：丢掉缓存的 playlist，下一轮立刻回源，并解除"正在播"的钉死 */
    public void kick() {
        plAt = 0;
        playing = null;
        synchronized (wake) { wake.notifyAll(); }
    }

    /** 每次重建播放器时调一次：把"多久没吃到新片"的计时归零，避免刚重建就被判冻死 */
    public void resetNewSegClock() {
        lastNewSegAt = System.currentTimeMillis();
        synchronized (stormLock) { storm.clear(); }
    }

    /** 冻死特征：同一个分片被反复索要（播放器卡住时会拿同一片死磕） */
    public boolean takeStorm() {
        synchronized (stormLock) {
            if (storm.isEmpty()) return false;
            storm.clear();
            return true;
        }
    }

    // ───────────────────────── HTTP 服务 ─────────────────────────

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
                final Socket cli = s;
                httpPool.execute(new Runnable() {
                    public void run() { handle(cli); }
                });
            } catch (Exception e) {
                if (!running) return;
                try { if (s != null) s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handle(Socket cli) {
        try {
            cli.setSoTimeout(20000);
            cli.setTcpNoDelay(true);
            InputStream in = cli.getInputStream();
            // 自己读请求行 + 头，需要 Range
            String reqLine = readLine(in);
            if (reqLine == null) { cli.close(); return; }
            Map<String, String> hs = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) hs.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { cli.close(); return; }
            String method = parts[0];
            String path = parts[1];
            boolean head = "HEAD".equalsIgnoreCase(method);
            lastReqAt = System.currentTimeMillis();
            Log.i(TAG, "REQ " + method + " " + path + (hs.get("range") != null ? " range=" + hs.get("range") : ""));

            if (path.startsWith("/p")) {
                String u = param(path, "u");
                byte[] body;
                try {
                    body = buildOrServePlaylist(u);
                } catch (Exception e) {
                    Log.w(TAG, "playlist 生成失败: " + e);
                    sendStatus(cli, 503, "playlist unavailable", head);
                    return;
                }
                sendSimple(cli, body, "application/vnd.apple.mpegurl", head);
            } else if (path.startsWith("/s/")) {
                String key = path.substring(3);
                int q = key.indexOf('?');
                if (q >= 0) key = key.substring(0, q);
                serveSegment(cli, key, hs.get("range"), head);
            } else if (path.equals("/")) {
                sendSimple(cli, "ok".getBytes("UTF-8"), "text/plain", head);
            } else {
                sendStatus(cli, 404, "not found", head);
            }
        } catch (Exception e) {
            // 播放器断开是常态
        } finally {
            try { cli.close(); } catch (Exception ignored) {}
        }
    }

    /** 逐字节读一行（不能用 BufferedReader，会多读走 socket 里的数据） */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString().replace("\r", "");
            if (sb.length() > 2048) return sb.toString();
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String param(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String[] kv = path.substring(q + 1).split("&");
        for (String one : kv) {
            int e = one.indexOf('=');
            if (e > 0 && one.substring(0, e).equals(name)) {
                try { return URLDecoder.decode(one.substring(e + 1), "UTF-8"); }
                catch (Exception ex) { return null; }
            }
        }
        return null;
    }

    /** 公共响应头：一律禁止缓存（v4 的关键，v3 就是栽在这） */
    private static String head(String status, String ctype, int len) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(status).append("\r\n");
        sb.append("Content-Type: ").append(ctype).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        sb.append("Pragma: no-cache\r\n");
        sb.append("Expires: 0\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    private void sendSimple(Socket cli, byte[] body, String ctype, boolean headOnly) throws IOException {
        OutputStream os = cli.getOutputStream();
        os.write(head("HTTP/1.1 200 OK", ctype, body.length).getBytes("ISO-8859-1"));
        if (!headOnly) os.write(body);
        os.flush();
    }

    /** 失败必须是真失败：让播放器报错走重连，而不是吞一段文字干等 */
    private void sendStatus(Socket cli, int code, String msg, boolean headOnly) throws IOException {
        byte[] b = msg.getBytes("UTF-8");
        OutputStream os = cli.getOutputStream();
        os.write(head("HTTP/1.1 " + code + " " + (code == 404 ? "Not Found" : "Unavailable"),
                "text/plain", b.length).getBytes("ISO-8859-1"));
        if (!headOnly) os.write(b);
        os.flush();
    }

    /**
     * 分片响应：不等整片下完，边下边发。
     * 中途断流（prefix 卡住超过 20 秒）直接掐连接，让播放器知道这一段坏了。
     */
    private void serveSegment(Socket cli, String key, String rangeHdr, boolean headOnly) throws Exception {
        Seg s = segs.get(key);
        if (s == null || s.gen != gen.get()) {
            Log.w(TAG, "REQ 分片未知 " + shortKey(key));
            sendStatus(cli, 404, "no seg", headOnly);
            return;
        }
        // 插队：这个片就是播放器此刻要吃的，所有连接优先喂它
        boolean je = (playing == s);
        playing = s;
        s.lastServe = System.currentTimeMillis();
        int n = s.served.incrementAndGet();
        if (n == 1) {
            lastNewSegAt = System.currentTimeMillis();   // 播放器吃到一个全新的片
            s.firstServeAt = lastNewSegAt;
        } else if (n >= 4 && s.firstServeAt > 0
                && System.currentTimeMillis() - s.firstServeAt < 90000) {
            // 90 秒内同一片被要了 4 次以上：这是"卡住死磕"的典型特征
            synchronized (stormLock) { storm.add(s.seq); }
        }
        if (!je || n <= 2) Log.i(TAG, "REQ 片 " + s.seq + " 第" + n + "次 prefix=" + (s.prefix / 1024) + "KB");

        long t0 = System.currentTimeMillis();
        while (s.size <= 0 && !s.failed && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(25);
        }
        long size = s.size;
        if (size <= 0) {
            Log.w(TAG, "REQ 片 " + s.seq + " 大小未知 -> 503");
            sendStatus(cli, 503, "size unknown", headOnly);
            return;
        }
        long t1 = System.currentTimeMillis();
        while (!s.file.exists() && !s.failed && System.currentTimeMillis() - t1 < 6000) {
            Thread.sleep(20);
        }
        if (!s.file.exists()) {
            Log.w(TAG, "REQ 片 " + s.seq + " 文件不存在 -> 503");
            sendStatus(cli, 503, "no file", headOnly);
            return;
        }

        long start = 0, end = size - 1;
        boolean partial = false;
        if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
            try {
                String r = rangeHdr.substring(6);
                int dash = r.indexOf('-');
                String a = r.substring(0, dash).trim();
                String b = r.substring(dash + 1).trim();
                if (a.length() > 0) start = Long.parseLong(a);
                if (b.length() > 0) end = Long.parseLong(b);
                if (end >= size) end = size - 1;
                if (start < 0) start = 0;
                if (start > end) { start = 0; end = size - 1; }
                else partial = true;
            } catch (Exception ignored) { start = 0; end = size - 1; partial = false; }
        }

        OutputStream os = cli.getOutputStream();
        StringBuilder sb = new StringBuilder(256);
        sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: video/mp2t\r\n");
        sb.append("Content-Length: ").append(end - start + 1).append("\r\n");
        if (partial) sb.append("Content-Range: bytes ").append(start).append('-')
                .append(end).append('/').append(size).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Cache-Control: no-store, no-cache\r\n");
        sb.append("Connection: close\r\n\r\n");
        os.write(sb.toString().getBytes("ISO-8859-1"));
        os.flush();
        if (headOnly) return;

        RandomAccessFile r = new RandomAccessFile(s.file, "r");
        try {
            r.seek(start);
            long pos = start;
            byte[] buf = new byte[32768];
            int idle = 0;
            while (pos <= end) {
                long avail = s.prefix;              // 连续可读到的位置
                if (pos >= avail) {
                    if (s.done) break;              // 下完却还不够 → 文件有问题，掐掉
                    if (s.failed) break;
                    if (++idle > 1300) {            // 约 20 秒毫无进展 → 掐掉让播放器重连
                        Log.w(TAG, "REQ 片 " + s.seq + " 断流于 " + (pos / 1024) + "/" + (size / 1024) + "KB");
                        break;
                    }
                    Thread.sleep(15);
                    continue;
                }
                int want = (int) Math.min(buf.length, Math.min(avail - pos, end + 1 - pos));
                int k = r.read(buf, 0, want);
                if (k <= 0) { Thread.sleep(15); continue; }
                os.write(buf, 0, k);
                pos += k;
                idle = 0;
            }
            os.flush();
        } finally {
            try { r.close(); } catch (Exception ignored) {}
            s.lastServe = System.currentTimeMillis();
        }
    }

    // ───────────────────────── 播放列表 ─────────────────────────

    /**
     * 播放器每次刷新 playlist 都走这里。
     *
     * 关键：这里【绝不回源】——回源是预取线程的活。HTTP 线程只管把当前这一份交出去，
     * 这样窄带/抖动时播放器也不会被一个 10 秒的网络请求吊住（v3 会）。
     * 冷启动时最多等 3 秒，等预取线程把第一份拉回来。
     */
    private byte[] buildOrServePlaylist(String upUrl) throws Exception {
        String up = (upUrl != null) ? upUrl : upstream;
        if (up == null) throw new IOException("no upstream");
        long t0 = System.currentTimeMillis();
        // 只催一次。原来在循环里每 50ms 把 plAt 清零，等于把预取线程按最快的速度
        // 反复踢起来，上游一个慢请求就被叠加成几十个并发，反而更容易超时。
        plAt = 0;
        synchronized (wake) { wake.notifyAll(); }
        // 冷启动宽限从 3 秒放宽到 12 秒：实测第一次回源要 3~4 秒（服务器冷启动），
        // 3 秒的期限会让"其实已经拉到了"的播放列表被判成 empty playlist。
        while (plBody == null && running && System.currentTimeMillis() - t0 < 12000) {
            Thread.sleep(50);
        }
        String body = plBody;
        if (body == null) throw new IOException("empty playlist");
        return body.getBytes("UTF-8");
    }

    private static class Up {
        long seq;
        String head;
        String url;
    }

    /** 从 live_1_1_7269144.ts 里抠出真实编号 */
    static long seqFromUri(String uri) {
        int q = uri.indexOf('?');
        String p = (q > 0) ? uri.substring(0, q) : uri;
        int slash = p.lastIndexOf('/');
        String name = (slash >= 0) ? p.substring(slash + 1) : p;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        int us = name.lastIndexOf('_');
        String num = (us >= 0) ? name.substring(us + 1) : name;
        try { return Long.parseLong(num.trim()); } catch (Exception e) { return -1; }
    }

    private void refreshWindow(String up) {
        int g = gen.get();
        String text = httpGetString(up);
        if (text == null) {
            Log.w(TAG, "回源失败，沿用上一份窗口");
            return;
        }
        if (g != gen.get()) return;                     // 期间切台了，这份作废
        lastPlOkAt = System.currentTimeMillis();        // 回源成功
        String base = up;
        int slash = up.lastIndexOf('/');
        if (slash > 0) base = up.substring(0, slash);

        List<Up> list = new ArrayList<Up>(8);
        StringBuilder pend = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION")
                    || line.startsWith("#EXT-X-ALLOW-CACHE") || line.startsWith("#EXT-X-TARGETDURATION")
                    || line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                continue;       // 自己重新生成
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) continue;   // 裁窗口后无意义
                pend.append(line).append('\n');
                continue;
            }
            Up u = new Up();
            u.head = pend.toString();
            u.url = line.startsWith("http") ? line : base + "/" + line;
            long q = seqFromUri(u.url);
            if (q < 0) {
                // 抠不出编号：用上一片的编号 +1 兜底
                q = (list.isEmpty() ? 0 : list.get(list.size() - 1).seq) + 1;
            }
            u.seq = q;
            list.add(u);
            pend.setLength(0);
        }
        if (list.isEmpty()) {
            Log.w(TAG, "回源内容为空，沿用上一份窗口");
            return;
        }

        long newLast = list.get(list.size() - 1).seq;
        if (lastSeq >= 0 && newLast < lastSeq) {
            Log.w(TAG, "回源比手上更旧(" + newLast + "<" + lastSeq + ")，丢弃");
            return;
        }
        int from = Math.max(0, list.size() - WINDOW);
        List<Seg> next = new ArrayList<Seg>(WINDOW);
        long[] seqs = new long[WINDOW];
        StringBuilder out = new StringBuilder(1024);
        out.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n");
        out.append("#EXT-X-TARGETDURATION:11\n");
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(list.get(from).seq).append('\n');
        for (int i = from; i < list.size(); i++) {
            Up u = list.get(i);
            String key = md5(u.url);
            key2url.put(key, u.url);
            Seg s = segs.get(key);
            if (s == null) {
                s = makeSeg(u.seq, key, u.url, g);
                Seg prev = segs.putIfAbsent(key, s);
                if (prev != null) s = prev;
            }
            next.add(s);
            synchronized (pinned) {
                pinned.add(key);
                while (pinned.size() > PIN_MAX) {
                    Iterator<String> it = pinned.iterator();
                    it.next();
                    it.remove();
                }
            }
            out.append(u.head);
            out.append("/s/").append(key).append('\n');
        }
        synchronized (winLock) {
            window.clear();
            window.addAll(next);
        }
        if (newLast != lastSeq) {
            Log.i(TAG, "窗口 " + list.size() + " 片 -> 交给播放器 " + next.size()
                    + " 片，序号 " + firstOf(next) + "~" + newLast);
        }
        lastSeq = newLast;
        firstSeq = firstOf(next);
        plBody = out.toString();
    }

    private static long firstOf(List<Seg> l) {
        return (l == null || l.isEmpty()) ? -1 : l.get(0).seq;
    }

    // ───────────────────────── 预取 ─────────────────────────

    private void prefetchLoop() {
        while (running) {
            try {
                String up = upstream;
                if (up != null && System.currentTimeMillis() - plAt >= PL_TTL) {
                    int g = gen.get();
                    refreshWindow(up);
                    if (g == gen.get()) {
                        // 成功失败都记一次时间：失败时不刷新 plAt 会导致每 300ms
                        // 重试一次，把上游打成并发风暴。
                        plAt = System.currentTimeMillis();
                        if (plBody != null) { plFor = up; trimCache(); }
                    }
                }
            } catch (Exception e) {
                // 网络抖动，下一轮再试
            }
            synchronized (wake) {
                try { wake.wait(300); } catch (Exception ignored) {}
            }
        }
    }

    private Seg makeSeg(long seq, String key, String url, int g) {
        Seg s = new Seg(seq, key, url, new File(dir, key));
        s.gen = g;
        s.size = sizeFromUrl(url);
        if (s.size > 0) startChunks(s);
        return s;
    }

    /** 初始化块状态表（size 已知才能算块数） */
    private void startChunks(Seg s) {
        int n = (int) ((s.size + CHUNK - 1) / CHUNK);
        if (n <= 0) return;
        synchronized (s) {
            if (s.chunkState != null) return;
            s.chunkCount = n;
            s.chunkState = new byte[n];
            s.chunkAt = new long[n];
            s.chunkTry = new int[n];
            s.prefixChunk = 0;
        }
    }

    /**
     * 下载线程主循环。
     *
     * v4 关键改动：块不再用「单调递增的 nextChunk 一次性发出去」。
     * 那样只要有一条连接在一块的读取里假死（上游限速/半开连接，read 一直能拿到零星字节
     * 就永远不会超时），这块就再也没人重下 → prefix 永久卡在这个块边界 →
     * 播放器读到 1.5MB 就干等 → 画面冻死。实测就卡在 1536KB（正好第 3 块边界）。
     * 现在改成「认领 + 超时可被抢」：每块带认领时间，超过 25 秒没完成，
     * 另一条线程可以直接抢过来重下，所以任何一块都不会永久拖住全局。
     */
    private void dlLoop() {
        while (running) {
            Seg s = null;
            int idx = -1;
            try {
                List<Seg> cands = candidates();
                long now = System.currentTimeMillis();
                for (int i = 0; i < cands.size(); i++) {
                    Seg c = cands.get(i);
                    if (c == null || c.done || c.failed) continue;
                    if (c.gen != gen.get()) continue;
                    if (c.chunkState == null) { startChunks(c); if (c.chunkState == null) continue; }
                    idx = claim(c, now);
                    if (idx >= 0) { s = c; break; }
                }
            } catch (Exception e) {
                // ignore
            }
            if (s == null) {
                try { Thread.sleep(60); } catch (Exception ignored) {}
                continue;
            }
            try {
                fetchChunk(s, idx);
            } catch (Throwable t) {
                releaseChunk(s, idx, false);
            }
        }
    }

    /** 认领一块：从 prefix 往后找空块；没有就抢"认领超时"的块。返回 -1 表示这片刻没活干 */
    private int claim(Seg s, long now) {
        byte[] st = s.chunkState;
        if (st == null) return -1;
        int n = s.chunkCount;
        // ① 优先从 prefix 往后按顺序补（保证 prefix 能连续推进）
        for (int i = s.prefixChunk; i < n; i++) {
            if (st[i] != 0) continue;
            synchronized (s) {
                if (s.chunkState[i] != 0) continue;
                s.chunkState[i] = 1;
                s.chunkAt[i] = now;
                s.chunkTry[i]++;
            }
            return i;
        }
        // ② 没有空块，看看谁认领超时了（>25 秒还没下完 = 那条连接废了），抢过来
        for (int i = 0; i < n; i++) {
            if (st[i] != 1) continue;
            synchronized (s) {
                if (s.chunkState[i] != 1) continue;
                if (now - s.chunkAt[i] < 25000) continue;
                if (s.chunkTry[i] > 6) continue;        // 试太多次就放弃这块，别死循环
                s.chunkAt[i] = now;
                s.chunkTry[i]++;
            }
            return i;
        }
        return -1;
    }

    /** 下载一个 512KB 块，落盘后按序推进 prefix */
    private void fetchChunk(final Seg s, final int idx) {
        long a = (long) idx * CHUNK;
        long b = Math.min(s.size, a + CHUNK) - 1;
        if (a > b) { markChunk(s, idx); return; }
        int g = s.gen;
        long t0 = System.currentTimeMillis();

        for (int attempt = 0; attempt < 3; attempt++) {
            if (g != gen.get()) { releaseChunk(s, idx, false); return; }
            HttpURLConnection c = null;
            RandomAccessFile f = null;
            try {
                c = (HttpURLConnection) new URL(s.url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Range", "bytes=" + a + "-" + b);
                c.setRequestProperty("Cache-Control", "no-cache");
                int code = c.getResponseCode();
                if (code != 200 && code != 206) { continue; }
                InputStream is = c.getInputStream();
                byte[] buf = new byte[32768];
                long pos = a;
                f = new RandomAccessFile(s.file, "rw");
                int n;
                while ((n = is.read(buf)) > 0) {
                    if (g != gen.get()) { is.close(); releaseChunk(s, idx, false); return; }
                    // 硬超时：这一块总共只给 22 秒，超了立刻放手让别的线程重下，
                    // 否则上游"滴流"式回数据会让 read 永远不超时，块永远下不完。
                    if (System.currentTimeMillis() - t0 > 22000) {
                        Log.w(TAG, "块超时 片" + s.seq + " #" + idx + " 进度=" + (pos - a) / 1024 + "KB");
                        is.close();
                        releaseChunk(s, idx, false);
                        return;
                    }
                    f.seek(pos);
                    f.write(buf, 0, n);
                    pos += n;
                    if (pos > b) break;
                }
                is.close();
                if (pos > b) { markChunk(s, idx); return; }
            } catch (Throwable e) {
                // 换条连接重试
            } finally {
                if (f != null) try { f.close(); } catch (Exception ignored) {}
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
        // 这条线程尽力了：把块放回自由池，让别的线程（或下一秒的自己）接着下
        releaseChunk(s, idx, s.chunkTry[idx] > 6);
    }

    /** 释放认领。giveUp=true 时直接标记完成（挖个洞），避免一块烂数据卡死整片 */
    private void releaseChunk(Seg s, int idx, boolean giveUp) {
        if (s.chunkState == null || idx >= s.chunkCount) return;
        if (giveUp) {
            Log.w(TAG, "块放弃 片" + s.seq + " #" + idx);
            markChunk(s, idx);
            return;
        }
        synchronized (s) {
            if (s.chunkState != null && idx < s.chunkCount && s.chunkState[idx] == 1) {
                s.chunkState[idx] = 0;
            }
        }
    }

    /** 标记块完成，并把连续前缀往前推 */
    private void markChunk(Seg s, int idx) {
        synchronized (s) {
            if (s.chunkState == null || idx >= s.chunkCount) return;
            s.chunkState[idx] = 2;
            while (s.prefixChunk < s.chunkCount && s.chunkState[s.prefixChunk] == 2) {
                s.prefixChunk++;
                s.prefix = Math.min(s.size, (long) s.prefixChunk * CHUNK);
            }
            if (s.prefixChunk >= s.chunkCount) {
                s.prefix = s.size;
                s.done = true;
                Log.i(TAG, "片完成 " + s.seq + " " + (s.size / 1048576) + "MB");
            }
        }
    }

    /** 候选顺序：播放器正在读的 > 窗口第 1 片 > 第 2 片 > … > 第 5 片 */
    private List<Seg> candidates() {
        List<Seg> out = new ArrayList<Seg>(8);
        Seg p = playing;
        if (p != null && !p.done && !p.failed) out.add(p);
        List<String> snap;
        synchronized (winLock) {
            snap = new ArrayList<String>(window.size());
            for (Seg s : window) snap.add(s.key);
        }
        for (int i = 0; i < snap.size(); i++) {
            Seg s = segs.get(snap.get(i));
            if (s != null && s != p) out.add(s);
        }
        return out;
    }

    /** 从分片 URL 的 cl 参数直接拿总大小（主服务器都带），拿不到再探测 */
    private long sizeFromUrl(String url) {
        try {
            int i = url.indexOf("cl=");
            if (i > 0) {
                int e = url.indexOf('&', i);
                String v = (e > 0) ? url.substring(i + 3, e) : url.substring(i + 3);
                long n = Long.parseLong(v.trim());
                if (n > 0) return n;
            }
        } catch (Exception ignored) {}
        return probeSize(url);
    }

    private long probeSize(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int s = cr.lastIndexOf('/');
                    if (s > 0) return Long.parseLong(cr.substring(s + 1).trim());
                }
            }
            long len = c.getContentLength();
            return len > 0 ? len : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 只删「不在窗口里、不在最近两份 playlist 里、也不是正在播、且 60 秒没被读过」的分片。
     * v3 那个"目录文件超过 5 个就删最旧"的兜底会把正在用的文件删掉，已经删除。
     */
    private void trimCache() {
        List<String> keep;
        synchronized (winLock) {
            keep = new ArrayList<String>(window.size());
            for (Seg s : window) keep.add(s.key);
        }
        long now = System.currentTimeMillis();
        List<String> del = new ArrayList<String>();
        for (Iterator<Map.Entry<String, Seg>> it = segs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Seg> e = it.next();
            Seg s = e.getValue();
            if (keep.contains(e.getKey())) continue;
            synchronized (pinned) { if (pinned.contains(e.getKey())) continue; }
            if (s == playing) continue;
            if (s.served.get() > 0 && now - s.lastServe < RETAIN_MS) continue;
            if (s.served.get() == 0 && now - s.lastServe < RETAIN_MS) continue;
            del.add(e.getKey());
        }
        for (String k : del) {
            Seg s = segs.remove(k);
            if (s != null) {
                key2url.remove(k);
                try { s.file.delete(); } catch (Exception ignored) {}
            }
        }
        // 孤儿文件（重启残留）：2 分钟没动过就清掉
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (segs.containsKey(f.getName())) continue;
                if (now - f.lastModified() > 120000) f.delete();
            }
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    static final String UA = "Mozilla/5.0 (Linux; Android 4.4.4) AppleWebKit/537.36";

    private static String shortKey(String k) {
        return (k == null) ? "null" : (k.length() > 6 ? k.substring(0, 6) : k);
    }

    private static String httpGetString(String url) {
        byte[] d = httpGetBytes(url);
        if (d == null) return null;
        try { return new String(d, "UTF-8"); } catch (Exception e) { return null; }
    }

    private static final java.util.concurrent.atomic.AtomicInteger INFLIGHT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static byte[] httpGetBytes(String url) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        int n0 = INFLIGHT.incrementAndGet();
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Cache-Control", "no-cache, no-store");
            c.setRequestProperty("Pragma", "no-cache");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "GET " + (System.currentTimeMillis() - t0) + "ms code=" + code
                        + " 并发=" + n0 + " " + url);
                return null;
            }
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            Log.i(TAG, "GET " + (System.currentTimeMillis() - t0) + "ms code=" + code
                    + " len=" + bo.size() + " 并发=" + n0);
            return bo.toByteArray();
        } catch (Throwable e) {
            Log.w(TAG, "HTTP失败 " + (System.currentTimeMillis() - t0) + "ms 并发=" + n0
                    + " " + url + " : " + e);
            return null;
        } finally {
            INFLIGHT.decrementAndGet();
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void cleanDir() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }

    /** 自检：逐个探测不同端口/协议，把结果打进日志。只用于排障。 */
    public static void selfCheck() {
        final String[] urls = {
                "http://wifi.vivo.com.cn/generate_204",
                "http://120.76.248.139/live/bfgd/4200000099.m3u8",
                "http://74.91.26.218:82/live/cctv1hd.m3u8",
                "http://222.169.85.8:9901/tsfile/live/0001_1.m3u8?key=txiptv&playlive=1&authid=0",
                "https://www.baidu.com",
        };
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < urls.length; i++) {
                    long t0 = System.currentTimeMillis();
                    String r;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(urls[i]).openConnection();
                        c.setConnectTimeout(6000);
                        c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", UA);
                        int code = c.getResponseCode();
                        int len = 0;
                        try {
                            java.io.InputStream is = c.getInputStream();
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = is.read(b)) > 0) { len += n; if (len > 100000) break; }
                            is.close();
                        } catch (Exception ignored) {}
                        r = "code=" + code + " len=" + len;
                        c.disconnect();
                    } catch (Throwable e) {
                        r = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    Log.i(TAG, "SELF " + (System.currentTimeMillis() - t0) + "ms " + urls[i] + " -> " + r);
                }
            }
        }).start();
    }

    static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
