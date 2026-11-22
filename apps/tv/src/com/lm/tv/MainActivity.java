package com.lm.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 看电视 —— 极简直播播放器（Android 4.4 / API19 / armv7）
 *
 * 设计取舍：
 *   - 不用 ExoPlayer（minSdk 21+），直接用系统 MediaPlayer 的原生 HLS 支持，APK 只有几十 KB
 *   - 频道表放 assets/channels.txt，格式 "分组|名称|地址"，换源不用重新编译
 *   - 也可以把新频道表 push 到 /sdcard/tv/channels.txt，App 会优先读它
 *
 * v4（长稳版）：
 *   - 去掉左侧那枚常驻小把手：列表一收就整块收回，屏幕上不再留任何图标
 *     （要叫回列表：点一下画面，或按遥控器上下键）
 *   - 加画面看门狗：位置 10 秒不动就静默重建播放器，从直播点重新起播。
 *     这样即使上游抽风、代理断流，也只会黑一下、1~2 秒自己回来，不会永久冻住
 */
public class MainActivity extends Activity {
    static final String TAG = "TV";
    static final boolean DIRECT_TEST = true;   // 直连播放（不经自建代理）。实测这颗 HAL 直连完全够用，链路越短越不容易出错

    static final int COLOR_BG = 0xFF0E0E12;
    static final int COLOR_PANEL = 0xE616161C;   // 半透明，收起时能透出画面
    static final int COLOR_ROW_ACTIVE = 0xFF2A2A36;
    static final int COLOR_TEXT = 0xFFE8E8EE;
    static final int COLOR_DIM = 0xFF8A8A96;
    static final int COLOR_ACCENT = 0xFFFF6A3D;

    static final int PANEL_DP = 300;

    /** 画面看门狗：播放器多久没来要过任何东西就判定卡死（正常播放实测 9~19 秒一次） */
    static final long NO_REQ_MS = 35000;
    /** 兜底：多久没吃到全新分片（正常播放实测最长 ~41 秒，这里放得很宽，只当最后一道网） */
    static final long NEW_SEG_MS = 120000;
    /** 两次自动恢复之间至少隔这么久，避免反复重建 */
    static final long RECOVER_GAP_MS = 12000;

    static class Channel {
        String group;
        String name;
        String[] urls;          // 一个频道可以有多个源，按顺序尝试（换节点/换源不用重编）
        Channel(String g, String n, String u) {
            group = g; name = n;
            String[] parts = u.split(";");
            int n2 = 0;
            for (int i = 0; i < parts.length; i++) if (parts[i].trim().length() > 0) n2++;
            urls = new String[n2];
            int k = 0;
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim();
                if (t.length() > 0) urls[k++] = t;
            }
        }
        String url() { return urls.length > 0 ? urls[0] : ""; }
    }

    private final List<Channel> channels = new ArrayList<Channel>();
    private FrameLayout root;
    private View curtain;          // 透明层，负责"点画面"手势（SurfaceView 吃不到触摸）
    private LinearLayout panel;
    private ListView list;
    private TextView info;
    private TextView status;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private MediaPlayer mp;
    private HlsProxy proxy;
    private int currentIdx = -1;
    private boolean surfaceReady = false;
    private boolean panelOpen = true;
    private int panelW = PANEL_DP;
    private final Handler ui = new Handler();
    private int retried = 0;
    private int urlIdx = 0;      // 当前用的是该频道的第几个源
    private ChannelAdapter adapter;

    // ── 看门狗状态 ──
    private volatile boolean prepared = false;
    private volatile int lastPos = -1;          // 直连模式的看门狗：上次播放位置
    private volatile long lastPosAt = 0;        // 位置发生变化的时间
    private VolumeBar vbar;                     // 音量条（放在左侧抽屉里，不压画面）
    private TextView vtxt;                      // 音量百分比
    private long wdLastRecover = 0;
    private int wdRecoverCount = 0;
    private long preparedAt = 0;
    private int wdTicks = 0;
    private long wdMaxReqGap = 0;
    /** 播放地址里的会话号：每次重建都换一个，绕开任何一层 HTTP 缓存 */
    private int session = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // 本地预取代理：上游对单连接限速，播放器单连接必然周期性卡顿，
        // 由代理用 6 连接并行把分片下到磁盘，播放器只跟本机要数据。
        proxy = HlsProxy.get(getCacheDir());
        proxy.start();

        panelW = dp(PANEL_DP);
        loadChannels();
        setContentView(buildUi());
        syncVolume();                 // 先按系统当前音量把条摆对

        // 默认播第一个
        if (!channels.isEmpty()) {
            ui.postDelayed(new Runnable() {
                public void run() { play(0); }
            }, 600);
        }
        ui.postDelayed(watchdog, 4000);

        // 频道表超过 6 小时就后台自动更新一次（源失效是常态，不能靠人推）
        if (SourceUpdater.stale()) {
            ui.postDelayed(new Runnable() {
                public void run() { autoRefreshSources(false); }
            }, 3000);
        }
    }

    /**
     * 自动找源。
     * quiet=true 表示是播放失败后触发的，成功就悄悄换表重播当前台；
     * quiet=false 表示是启动时到点更新，同样不打扰用户（只更新表，不打断正在播的台）。
     */
    private void autoRefreshSources(final boolean retryCurrent) {
        final int idx = currentIdx;
        SourceUpdater.refreshAsync(channels, new SourceUpdater.Cb() {
            public void done(final boolean ok, final int chans, final int urls) {
                ui.post(new Runnable() {
                    public void run() {
                        if (!ok) return;
                        reloadChannels();
                        Toast.makeText(MainActivity.this,
                                "已自动更新源：" + chans + " 个台 / " + urls + " 条线路", 0).show();
                        if (retryCurrent && idx >= 0 && idx < channels.size()) {
                            play(idx);
                        } else if (!prepared && idx >= 0 && idx < channels.size()) {
                            // 启动时自动更新完，如果当前还没播起来（多半是老表全挂了），
                            // 直接用新表重播，别让用户自己点一下
                            play(idx);
                        }
                    }
                });
            }
        });
    }

    /** 重新读表并刷新列表（保持当前播放的台号不变） */
    private void reloadChannels() {
        channels.clear();
        loadChannels();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ───────────────────────── UI ─────────────────────────

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        // 1) 视频：整屏铺满
        surface = new SurfaceView(this);
        holder = surface.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; attachSurface(); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { surfaceReady = true; attachSurface(); }
            public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }
        });
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2) 手势层：铺在视频上，用来接"点画面显隐列表"
        curtain = new View(this);
        curtain.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setPanel(!panelOpen, true); }
        });
        root.addView(curtain, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3) 频道抽屉：浮层，靠 translationX 滑出滑入。收起时整块移出屏幕，不留把手。
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(COLOR_PANEL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText("看电视");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 收起按钮：自绘一个"‹"，不依赖字体字形
        head.addView(new CollapseView(this), new LinearLayout.LayoutParams(dp(40), dp(34)));
        panel.addView(head);

        // 音量条：放在标题下面、列表上方 —— 抽屉展开时一定看得见、够得着；
        // 抽屉收起时整块跟着移出屏幕，画面上永远不留任何东西。
        panel.addView(buildVolumeRow());

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setBackgroundColor(0x00000000);
        list.setCacheColorHint(0);       // 防 4.4 上滚动时黑底
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                play(pos);
                setPanel(false, true);   // 选完台自动收起，直接看画面
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tip = new TextView(this);
        tip.setText("点画面可呼出 / 收起此列表");
        tip.setTextColor(COLOR_DIM);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tip.setPadding(dp(20), dp(8), dp(20), dp(12));
        panel.addView(tip);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(panel, pp);

        // 4) 左上角信息条（切台时闪现 3 秒，自动淡出）
        info = new TextView(this);
        info.setTextColor(0xFFFFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setPadding(dp(14), dp(7), dp(14), dp(7));
        info.setBackgroundColor(0x8C000000);
        info.setAlpha(0f);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.TOP | Gravity.LEFT;
        ip.leftMargin = dp(14);
        ip.topMargin = dp(14);
        root.addView(info, ip);

        // 5) 状态提示（居中偏下）
        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER;
        root.addView(status, sp);

        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 抽屉显隐。animate=false 用于首屏直接定位，避免看到滑动。 */
    private void setPanel(final boolean open, boolean animate) {
        if (panel == null) return;
        panelOpen = open;
        float target = open ? 0f : -panelW;
        if (animate) {
            panel.animate().translationX(target).setDuration(200).start();
        } else {
            panel.setTranslationX(target);
        }
        // 展开瞬间让当前台滚到可见位置，并同步一次音量（可能在别处被改过）
        if (open) {
            if (currentIdx >= 0) list.setSelection(currentIdx);
            syncVolume();
        }
    }

    /** 左上角信息条：显示 3 秒后淡出（列表展开时让到面板右侧，别压住标题） */
    private void showInfo(String s) {
        if (info == null || s == null) return;
        info.setText(s);
        info.setX(panelOpen ? panelW + dp(16) : dp(14));
        info.animate().cancel();
        info.setAlpha(1f);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 3000);
    }

    private final Runnable hideInfo = new Runnable() {
        public void run() {
            if (info != null) info.animate().alpha(0f).setDuration(450).start();
        }
    };

    /** 标题栏右侧的小箭头，点了收抽屉 */
    // ───────────────────────── 音量 ─────────────────────────

    /** 音量行： [音量] [====滑条====] [45%]；点在滑条上直接跳，拖动连续调 */
    private View buildVolumeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(6), dp(18), dp(4));

        TextView lab = new TextView(this);
        lab.setText("音量");
        lab.setTextColor(COLOR_DIM);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(lab, new LinearLayout.LayoutParams(dp(34),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        vbar = new VolumeBar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        bp.leftMargin = dp(6);
        bp.rightMargin = dp(6);
        row.addView(vbar, bp);

        vtxt = new TextView(this);
        vtxt.setTextColor(COLOR_TEXT);
        vtxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        vtxt.setGravity(Gravity.CENTER);
        row.addView(vtxt, new LinearLayout.LayoutParams(dp(40),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 从系统读当前媒体音量刷新控件 */
    private void syncVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (vbar != null) { vbar.setMax(max); vbar.setProgress(cur); }
            if (vtxt != null && max > 0) vtxt.setText((cur * 100 / max) + "%");
        } catch (Throwable ignored) {}
    }

    /** 写回系统音量：flag=0 不弹系统音量条（那是另一个遮挡） */
    private void setVolume(int v) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (v < 0) v = 0;
            if (v > max) v = max;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            if (vtxt != null && max > 0) vtxt.setText((v * 100 / max) + "%");
        } catch (Throwable ignored) {}
    }

    /** 手绘音量条：比 SeekBar 更贴这套暗色 UI，也不依赖系统控件样式 */
    class VolumeBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int max = 15;
        private int value = 7;

        VolumeBar(Context c) { super(c); }

        void setMax(int m) { if (m > 0) max = m; }
        void setProgress(int v) {
            if (v < 0) v = 0;
            if (v > max) v = max;
            value = v;
            invalidate();
        }

        private void pick(float x) {
            float t = x / Math.max(1, getWidth());
            int v = Math.round(t * max);
            if (v < 0) v = 0;
            if (v > max) v = max;
            if (v != value) { value = v; setVolume(v); invalidate(); }
        }

        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    pick(e.getX());
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    pick(e.getX());
                    return true;
            }
            return true;
        }

        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            int barH = dp(8);
            int top = (h - barH) / 2;
            int r = barH / 2;
            // 槽
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A3040);
            cv.drawRoundRect(new android.graphics.RectF(0, top, w, top + barH), r, r, p);
            // 已选
            int fw = (int) (w * (value / (float) Math.max(1, max)));
            if (fw > 0) {
                p.setColor(COLOR_ACCENT);
                cv.drawRoundRect(new android.graphics.RectF(0, top, fw, top + barH), r, r, p);
            }
            // 滑块
            p.setColor(0xFFFFFFFF);
            float cx = fw;
            if (cx < dp(7)) cx = dp(7);
            if (cx > w - dp(7)) cx = w - dp(7);
            cv.drawCircle(cx, h / 2, dp(7), p);
        }
    }

    class CollapseView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CollapseView(Context c) {
            super(c);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) { setPanel(false, true); }
            });
        }
        protected void onDraw(Canvas cv) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(COLOR_DIM);
            int w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            int a = dp(5);
            cv.drawLine(cx + a, cy - dp(7), cx - a, cy, p);
            cv.drawLine(cx - a, cy, cx + a, cy + dp(7), p);
        }
    }

    class ChannelAdapter extends BaseAdapter {
        public int getCount() { return channels.size(); }
        public Object getItem(int i) { return channels.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(11), dp(16), dp(11));

            Channel c = channels.get(pos);
            boolean active = (pos == currentIdx);
            row.setBackgroundColor(active ? COLOR_ROW_ACTIVE : 0x00000000);

            TextView name = new TextView(MainActivity.this);
            name.setText(c.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTextColor(active ? COLOR_ACCENT : COLOR_TEXT);
            if (active) name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);

            TextView g = new TextView(MainActivity.this);
            g.setText(c.group);
            g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            g.setTextColor(COLOR_DIM);
            row.addView(g);

            return row;
        }
    }

    // ───────────────────────── 播放 ─────────────────────────

    private void play(int idx) {
        if (idx < 0 || idx >= channels.size()) return;
        currentIdx = idx;
        retried = 0;
        urlIdx = 0;
        // 上次这个台能播的线路，这次先试它（源表里几十条线路，一条条试太慢）
        int start = 0;
        try {
            String good = prefs().getString("u_" + channels.get(idx).name, null);
            if (good != null) {
                for (int i = 0; i < channels.get(idx).urls.length; i++) {
                    if (good.equals(channels.get(idx).urls[i])) { start = i; break; }
                }
            }
        } catch (Throwable ignored) {}
        adapter.notifyDataSetChanged();
        if (panelOpen) list.setSelection(idx);
        startPlay(idx, start);
    }

    /** 播放第 idx 个频道的第 u 个源 */
    private void startPlay(int idx, int u) {
        Channel c = channels.get(idx);
        if (u >= c.urls.length) { onPlayFail("没有可用源"); return; }
        urlIdx = u;
        final String src = c.urls[u];
        Log.i(TAG, "切台 " + c.name + " [" + (u + 1) + "/" + c.urls.length + "] -> " + src);
        // 让代理切到新频道，并播放本机地址
        proxy.setChannel(src);
        proxy.kick();
        proxy.resetNewSegClock();
        session++;
        final String playUrl = DIRECT_TEST ? src : HlsProxy.localUrl(src, session);
        setStatus("正在缓冲 " + c.name + " …");
        releasePlayer();

        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (surfaceReady) mp.setDisplay(holder);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 4.4.4) AppleWebKit/537.36");
            mp.setDataSource(this, android.net.Uri.parse(playUrl), headers);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    Log.i(TAG, "准备完成，开始播放");
                    try {
                        if (currentIdx >= 0 && currentIdx < channels.size())
                            prefs().edit().putString("u_" + channels.get(currentIdx).name, src)
                                    .commit();
                    } catch (Throwable ignored) {}
                    prepared = true;
                    preparedAt = System.currentTimeMillis();
                    lastPos = -1;
                    lastPosAt = System.currentTimeMillis();
                    proxy.resetNewSegClock();
                    setStatus("");
                    p.start();
                    if (currentIdx >= 0 && currentIdx < channels.size())
                        showInfo(channels.get(currentIdx).name);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int what, int extra) {
                    Log.w(TAG, "播放错误 what=" + what + " extra=" + extra);
                    prepared = false;
                    onPlayFail("播放失败(" + what + "/" + extra + ")");
                    return true;
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) setStatus("缓冲中…");
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) setStatus("");
                    return false;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) {
                    // 直播不该"播完"，真播完了说明流断了：直接重开
                    Log.w(TAG, "播放结束（直播流不该结束），重建");
                    prepared = false;
                    scheduleRecover("流结束");
                }
            });
            mp.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "播放异常", e);
            onPlayFail("打不开：" + e.getMessage());
        }
    }

    // ───────────────────────── 看门狗（本次修复的核心保险） ─────────────────────────

    private final Runnable watchdog = new Runnable() {
        public void run() {
            try {
                checkStall();
            } catch (Throwable t) {
                Log.w(TAG, "看门狗异常（不影响续跑）: " + t);
            } finally {
                ui.postDelayed(this, 2000);     // 无论如何都要续上，否则看门狗就死了
            }
        }
    };

    /**
     * 冻死判据（实测校准过）：
     *   主判据：代理已经 NEW_SEG_MS 没给播放器喂过「全新分片」了。
     *          健康直播 10~25 秒内必有一个新片被吃进去；画面冻死时它会反复磕旧片，这个值只涨不落。
     *   加速判据：90 秒内同一片被要了 4 次以上（典型的"磕一片"卡死）。
     *
     * 注意：不能拿 getCurrentPosition() 当判据 —— 实测画面冻死时位置照样每秒 +1000ms 上涨。
     *
     * 另外要分清两种情况：
     *   - 上游自己断了（回源 8 秒没成功）→ 只提示"信号中断"，不重建播放器，等源回来自动续播；
     *   - 上游正常但播放器不吃 → 真·冻死，重建播放器。
     */
    private void checkStall() {
        MediaPlayer p = mp;
        if (p == null || !prepared) return;
        long now = System.currentTimeMillis();
        if (now - preparedAt < 20000) return;            // 刚起播 20 秒内不判

        // 直连模式：没有代理的分片计数可看，改用"播放位置是否还在走"。
        // 代理模式的那一套判据（新片/取数）在直连下全是没有意义的默认值，
        // 会把正常播放误判成冻死（实测每 20 秒踢一次，画面反复重连）。
        if (DIRECT_TEST) {
            int pos = -1;
            try { pos = p.getCurrentPosition(); } catch (Throwable ignored) {}
            if (pos != lastPos) { lastPos = pos; lastPosAt = now; }
            long stuck = now - lastPosAt;
            wdTicks++;
            if (wdTicks % 15 == 0) Log.i(TAG, "wd 直连 位置=" + pos + " 停滞=" + (stuck / 1000) + "s");
            if (stuck > 15000) {
                if (now - wdLastRecover < RECOVER_GAP_MS) return;
                wdLastRecover = now;
                wdRecoverCount++;
                Log.w(TAG, "看门狗：画面停住 " + (stuck / 1000) + "s，第 " + wdRecoverCount + " 次自动恢复");
                scheduleRecover("画面停滞");
            }
            return;
        }

        long sinceNew = (proxy == null) ? Long.MAX_VALUE : proxy.msSinceNewSegment();
        long sinceUp = (proxy == null) ? Long.MAX_VALUE : proxy.upstreamStaleMs();
        long sinceReq = (proxy == null) ? Long.MAX_VALUE : proxy.msSinceLastRequest();
        proxy.takeStorm();                              // 只做诊断记录，不参与判定
        boolean noNewSeg = sinceNew > NEW_SEG_MS;
        boolean noReq = sinceReq > NO_REQ_MS;            // 连 playlist 都不来要了 = 彻底停了

        wdTicks++;
        if (sinceReq != Long.MAX_VALUE && sinceReq > wdMaxReqGap) wdMaxReqGap = sinceReq;
        if (wdTicks % 15 == 0) {
            Log.i(TAG, "wd 新片距今=" + sec(sinceNew) + "s 回源距今=" + sec(sinceUp)
                    + "s 取数距今=" + sec(sinceReq) + "s 最长取数间隔=" + (wdMaxReqGap / 1000) + "s");
        }

        if (!noNewSeg && !noReq) return;
        // 上游本身断了 —— 重建播放器没用，只会反复黑屏；老实等它回来
        if (sinceUp > 8000) {
            setStatus("信号中断，正在重连…");
            return;
        }
        if (now - wdLastRecover < RECOVER_GAP_MS) return;
        wdLastRecover = now;
        wdRecoverCount++;
        Log.w(TAG, "看门狗：判定冻死（新片距今=" + sec(sinceNew) + "s 取数距今=" + sec(sinceReq)
                + "s），第 " + wdRecoverCount + " 次自动恢复");
        scheduleRecover("画面停滞");
    }

    private static long sec(long ms) {
        return (ms == Long.MAX_VALUE) ? -1 : ms / 1000;
    }

    private void scheduleRecover(final String why) {
        prepared = false;
        final int idx = currentIdx;
        if (idx < 0) return;
        ui.post(new Runnable() {
            public void run() {
                if (idx != currentIdx) return;
                setStatus("重新连接…");
                proxy.kick();
                startPlay(idx, urlIdx);
                ui.postDelayed(new Runnable() {
                    public void run() { setStatus(""); }
                }, 2500);
            }
        });
    }

    private void onPlayFail(String msg) {
        final int idx = currentIdx;
        Channel c = (idx >= 0 && idx < channels.size()) ? channels.get(idx) : null;
        // 1) 先换下一个源（比如主节点 403 就切上海节点）
        if (c != null && urlIdx + 1 < c.urls.length) {
            setStatus(msg + "，换线路…");
            ui.postDelayed(new Runnable() {
                public void run() { if (idx == currentIdx) startPlay(idx, urlIdx + 1); }
            }, 900);
            return;
        }
        // 2) 同源重试一次
        if (retried < 1) {
            retried++;
            setStatus(msg + "，重试中…");
            ui.postDelayed(new Runnable() {
                public void run() { if (idx == currentIdx) play(idx); }
            }, 1500);
        } else if (!SourceUpdater.running
                && System.currentTimeMillis() - SourceUpdater.lastRunAt > 10 * 60 * 1000) {
            // 3) 这个台所有线路都不通：别急着换台，先去网上找一批新源再重播。
            //    这是"源失效"能自愈的关键一步（10 分钟内只试一次，避免反复刷）。
            setStatus("所有线路都不通，正在自动找新源…");
            autoRefreshSources(true);
        } else if (idx + 1 < channels.size()) {
            setStatus(msg + "，自动换台");
            ui.postDelayed(new Runnable() {
                public void run() { if (idx == currentIdx) play(idx + 1); }
            }, 1200);
        } else {
            setStatus(msg + "（换一个台试试）");
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("tv", MODE_PRIVATE);
    }

    private void attachSurface() {
        if (mp != null && surfaceReady) {
            try { mp.setDisplay(holder); } catch (Throwable ignored) {}
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
            try { mp.reset(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            public void run() { status.setText(s); }
        });
    }

    // ───────────────────────── 频道表 ─────────────────────────

    private void loadChannels() {
        // 1) 优先读 /sdcard/tv/channels.txt（可以 adb push 换源，不用重装）
        try {
            java.io.File f = new java.io.File("/sdcard/tv/channels.txt");
            if (f.exists()) {
                parse(new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8")));
                Log.i(TAG, "从 /sdcard/tv/channels.txt 载入 " + channels.size() + " 个频道");
                if (!channels.isEmpty()) return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "读外部频道表失败: " + e);
        }
        // 2) 回落到内置
        try {
            parse(new BufferedReader(new InputStreamReader(
                    getAssets().open("channels.txt"), "UTF-8")));
            Log.i(TAG, "从 assets 载入 " + channels.size() + " 个频道");
        } catch (Throwable e) {
            Log.e(TAG, "载入内置频道表失败", e);
        }
    }

    private void parse(BufferedReader br) throws Exception {
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            channels.add(new Channel(p[0].trim(), p[1].trim(), p[2].trim()));
        }
        br.close();
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentIdx >= 0 && mp == null) play(currentIdx);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(watchdog);
        releasePlayer();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // BACK：列表开着就先收列表，不退出
        if (code == KeyEvent.KEYCODE_BACK && panelOpen) { setPanel(false, true); return true; }
        // 遥控器：左右切台
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { play(Math.max(0, currentIdx - 1)); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { play(Math.min(channels.size() - 1, currentIdx + 1)); return true; }
        // 上下键：列表收起时把它叫回来（没有小把手之后这是遥控器的主入口）
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!panelOpen) { setPanel(true, true); return true; }
        }
        return super.onKeyDown(code, e);
    }
}
