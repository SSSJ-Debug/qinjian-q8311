package com.lm.tv;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动找源。
 *
 * 思路：不维护死表，而是每次都去"别人已经在自动更新的聚合列表"里取最新的频道地址，
 * 按频道名归一后灌进本地表，每个台保留多个源，播放失败由播放器自动换下一个。
 *
 * 这么做的好处：
 *   1. 源失效是常态（公共源平均寿命以天计），表必须会自己更新，不能靠人推；
 *   2. 一次取不到就换下一个列表 / 镜像，单个列表挂掉不影响；
 *   3. 拉下来的结果缓存到 /sdcard/tv/channels.txt，断网时用上一次的。
 */
public final class SourceUpdater {

    static final String TAG = "TV";
    static final String OUT = "/sdcard/tv/channels.txt";
    static final long FRESH_MS = 6L * 60 * 60 * 1000;   // 6 小时内不重复更新

    /** 聚合列表（按优先级）。
     *  第一优先是路由器上的局域网副本：这台盒子是 Android 4.4，Java 的 TLS 栈太旧，
     *  拉 raw.githubusercontent 这类强制 TLS1.2 的站直接 SSL handshake aborted，
     *  所以让路由器（OpenWrt，24 小时在线）每 2 小时代拉一次，盒子只走明文 HTTP。
     *  后面几条是"万一路由器上没有"时的兜底。 */
    static final String[] LISTS = {
            "http://192.168.1.1/tv/result.m3u",
            "http://192.168.1.1/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/Guovin/TV/gd/output/result.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://live.zbds.top/tv/iptv4.txt",
    };

    static volatile boolean running = false;
    static volatile long lastRunAt = 0;

    public interface Cb {
        void done(boolean ok, int chans, int urls);
    }

    /** 本地表多久没更新了（没有文件就算很旧） */
    public static long ageMs() {
        File f = new File(OUT);
        return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
    }

    public static boolean stale() {
        return ageMs() > FRESH_MS;
    }

    /** 后台更新；完成后回调（回调在主线程） */
    public static void refreshAsync(final List<MainActivity.Channel> cur, final Cb cb) {
        if (running) return;
        running = true;
        lastRunAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                int chans = 0, urls = 0;
                try {
                    enableTls12();
                    Map<String, List<String>> pool = null;
                    for (int i = 0; i < LISTS.length && pool == null; i++) {
                        String text = get(LISTS[i]);
                        if (text == null || text.length() < 200) continue;
                        Map<String, List<String>> m = parse(text);
                        if (m.size() >= 8) pool = m;     // 少于 8 个台肯定是坏数据
                        Log.i(TAG, "列表 " + LISTS[i] + " -> 解析 " + m.size() + " 个台");
                    }
                    if (pool == null) {
                        Log.w(TAG, "所有聚合列表都取不到");
                    } else if (cur != null && !cur.isEmpty()) {
                        StringBuilder sb = new StringBuilder(8192);
                        for (MainActivity.Channel c : cur) {
                            List<String> cand = pool.get(key(c.name));
                            List<String> out = new ArrayList<String>();
                            if (cand != null) {
                                // http 优先：这台机器的 4.4 栈对很多 https 源握手失败
                                for (String u : cand) if (u.startsWith("http://") && !out.contains(u)) out.add(u);
                                for (String u : cand) if (!u.startsWith("http://") && !out.contains(u)) out.add(u);
                            }
                            // 新列表里没有这个台就把旧源接在后面，别把台弄没了
                            for (String u : c.urls) if (!out.contains(u)) out.add(u);
                            while (out.size() > 8) out.remove(out.size() - 1);
                            if (out.isEmpty()) continue;
                            sb.append(c.group).append('|').append(c.name).append('|');
                            for (int i = 0; i < out.size(); i++) {
                                if (i > 0) sb.append(';');
                                sb.append(out.get(i));
                            }
                            sb.append('\n');
                            chans++;
                            urls += out.size();
                        }
                        ok = write(sb.toString());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "更新源异常: " + t);
                }
                running = false;
                final boolean fOk = ok;
                final int fCh = chans, fUr = urls;
                Log.i(TAG, "自动找源完成 ok=" + ok + " 台=" + chans + " 源=" + urls);
                if (cb != null) cb.done(fOk, fCh, fUr);
            }
        }, "src-update").start();
    }

    // ───────────────────────── 解析 ─────────────────────────

    static Map<String, List<String>> parse(String text) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        String name = null;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXTINF")) {
                int c = line.lastIndexOf(',');
                name = (c >= 0 && c + 1 < line.length()) ? line.substring(c + 1).trim() : null;
                continue;
            }
            if (line.startsWith("#")) continue;
            String u = null, n = name;
            if (line.startsWith("http")) {
                u = line;
            } else if (line.indexOf(',') > 0) {          // txt 格式：名称,地址
                int c = line.indexOf(',');
                String tail = line.substring(c + 1).trim();
                if (tail.startsWith("http")) { n = line.substring(0, c).trim(); u = tail; }
            }
            name = null;
            if (u == null || n == null) continue;
            String k = key(n);
            List<String> l = out.get(k);
            if (l == null) { l = new ArrayList<String>(6); out.put(k, l); }
            if (!l.contains(u) && l.size() < 10) l.add(u);
        }
        return out;
    }

    /** 频道名归一：CCTV1/CCTV-1 高清/CCTV-1综合 → CCTV-1 */
    static String key(String n) {
        if (n == null) return "";
        String s = n.replace(" ", "").replace("　", "");
        s = s.replaceAll("(?i)(高清|超清|标清|蓝光|HD|直播|备用|测试)", "");
        if (s.startsWith("CCTV") || s.startsWith("央视")) {
            String t = s.replace("央视", "CCTV");
            int i = 5;
            if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '_')) i++;
            int j = i;
            while (j < t.length() && Character.isDigit(t.charAt(j))) j++;
            if (j > i) {
                String num = t.substring(i, j);
                boolean plus = j < t.length() && (t.charAt(j) == '+' || t.startsWith("plus", j));
                return "CCTV-" + Integer.parseInt(num) + (plus ? "+" : "");
            }
            if (t.indexOf("CCTV5+") >= 0) return "CCTV-5+";
        }
        return s;
    }

    // ───────────────────────── 存取 ─────────────────────────

    /**
     * Android 4.4 默认只协商 TLSv1.0，而 GitHub/CDN 现在一律要求 TLSv1.2，
     * 表现就是 SSLHandshakeException: handshake aborted。
     * 4.4 的 JSSE 其实是支持 TLSv1.2 的，只是没默认打开，这里手动打开。
     */
    static void enableTls12() {
        try {
            final javax.net.ssl.SSLSocketFactory base =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                    new javax.net.ssl.SSLSocketFactory() {
                        public String[] getDefaultCipherSuites() {
                            return base.getDefaultCipherSuites();
                        }

                        public String[] getSupportedCipherSuites() {
                            return base.getSupportedCipherSuites();
                        }

                        public java.net.Socket createSocket(java.net.Socket s, String host, int port,
                                                            boolean autoClose) throws java.io.IOException {
                            return wrap((javax.net.ssl.SSLSocket)
                                    base.createSocket(s, host, port, autoClose));
                        }

                        public java.net.Socket createSocket(String host, int port)
                                throws java.io.IOException {
                            return wrap((javax.net.ssl.SSLSocket) base.createSocket(host, port));
                        }

                        public java.net.Socket createSocket(String host, int port,
                                                            java.net.InetAddress localHost, int localPort)
                                throws java.io.IOException {
                            return wrap((javax.net.ssl.SSLSocket)
                                    base.createSocket(host, port, localHost, localPort));
                        }

                        public java.net.Socket createSocket(java.net.InetAddress host, int port)
                                throws java.io.IOException {
                            return wrap((javax.net.ssl.SSLSocket) base.createSocket(host, port));
                        }

                        public java.net.Socket createSocket(java.net.InetAddress address, int port,
                                                            java.net.InetAddress localAddress, int localPort)
                                throws java.io.IOException {
                            return wrap((javax.net.ssl.SSLSocket)
                                    base.createSocket(address, port, localAddress, localPort));
                        }
                    });
            Log.i(TAG, "已为 4.4 打开 TLSv1.2");
        } catch (Throwable e) {
            Log.w(TAG, "打开 TLSv1.2 失败: " + e);
        }
    }

    static java.net.Socket wrap(javax.net.ssl.SSLSocket s) {
        try {
            String[] old = s.getEnabledProtocols();
            boolean has = false;
            for (int i = 0; i < old.length; i++) if ("TLSv1.2".equals(old[i])) has = true;
            if (!has) {
                String[] n = new String[old.length + 1];
                System.arraycopy(old, 0, n, 0, old.length);
                n[old.length] = "TLSv1.2";
                s.setEnabledProtocols(n);
            }
        } catch (Throwable ignored) {}
        return s;
    }

    static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.4.4)");
            if (c.getResponseCode() != 200) return null;
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(65536);
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 4 * 1024 * 1024) break;
            }
            is.close();
            return bo.toString("UTF-8");
        } catch (Throwable e) {
            Log.w(TAG, "取列表失败 " + url + " : " + e);
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    static boolean write(String body) {
        try {
            File f = new File(OUT);
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
            w.write(body);
            w.close();
            fo.close();
            Log.i(TAG, "已写入 " + OUT + " (" + body.length() + " 字节)");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "写频道表失败: " + e);
            return false;
        }
    }

    static String readAll(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
