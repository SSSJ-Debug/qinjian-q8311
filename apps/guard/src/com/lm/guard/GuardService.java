package com.lm.guard;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import com.lm.guard.Mail;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/* loaded from: classes.dex */
public class GuardService extends Service implements Camera.PreviewCallback {
    static final int DETECT_EVERY_OFF = 3;
    static final int DETECT_EVERY_ON = 12;
    static final int DETECT_W = 480;

    // ── 实时预览（低清 / 流畅 / 几乎不占 CPU）2026-11-08 v2 ──────────────
    // 预览窗只需要"看得见有没有人在动"，清晰度完全不重要 —— 高清留给抓拍。
    // v2 三项改动（都是"卡顿"的真凶）：
    //   ① 320×180 → 240×135：像素少 44%，单帧转换从 ~3ms 降到 ~1.2ms，
    //      而且写在相机回调线程里，越省越好（回调线程一堵，相机就不给下一帧）。
    //   ② 双缓冲 → 三缓冲：界面慢一帧时，双缓冲会把"正在显示的那块"覆写掉，
    //      肉眼看就是画面一顿一顿/撕裂。三块轮换彻底避开。
    //   ③ 帧间隔门限 65ms → 40ms：原来相机出 14fps、门限 65ms，
    //      两者一拍一撞就丢一半帧，看着像 7fps。
    // 界面不在前台（sink == null）时整段直接返回，一个像素都不算。
    public static final int PVW = 240;
    public static final int PVH = 135;
    public static volatile PreviewSink previewSink = null;
    public static volatile long previewAt = 0;
    public static volatile int previewFps = 0;
    /** 界面真正画到屏幕上的帧率（端到端，含 UI 线程与 GPU 合成）。 */
    public static volatile int previewDrawFps = 0;
    /** 帧从相机出来到真正画上屏幕的延迟（ms）—— "看着不实时"就是它大。 */
    public static volatile int previewLag = 0;
    /** 相机实际出帧率（每 2 秒统计一次，给界面和日志看"到底是相机慢还是我们慢"）。 */
    public static volatile int camFps = 0;
    /** 被界面吞掉的帧数（界面还没画完就来的新帧，直接丢，防止队列堆积）。 */
    public static volatile int previewDrop = 0;
    /** 单帧转换平均耗时 ms。 */
    public static volatile int previewCost = 0;
    // ★ 2026-11-08 v3：位图搬运改成 RGB565 短整型 + copyPixelsFromBuffer。
    //   旧版走 int[](ARGB) + setPixels，natives 里要为 32k 个像素逐个做 ARGB→565 打包，
    //   实测单帧 8ms。新版在 Java 里直接算 565（每个像素只做 3 次数组查表 + 加法），
    //   再 memcpy 进 Bitmap，单帧掉到 2~3ms —— 相机回调线程因此更早归还缓冲。
    final short[] pv565 = new short[PVW * PVH];
    java.nio.ByteBuffer pvBB;
    java.nio.ShortBuffer pvSB;
    /** 输出列 → 源图列（x*stepX）与对齐后的色度列（& ~1），只算一次。 */
    final int[] pvColY = new int[PVW];
    final int[] pvColUV = new int[PVW];
    /** YUV 常量表：把每像素 3 次乘法/2 次移位换成一次数组查表。下标是原始 0~255。 */
    static final int[] TBL_RV = new int[256];
    static final int[] TBL_GV = new int[256];
    static final int[] TBL_GU = new int[256];
    static final int[] TBL_BU = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            TBL_RV[i] = (91881 * v) >> 16;
            TBL_GV[i] = (46802 * v) >> 16;
            TBL_GU[i] = (22554 * v) >> 16;
            TBL_BU[i] = (116130 * v) >> 16;
        }
    }
    Bitmap pvA;
    Bitmap pvB;
    Bitmap pvC;
    int pvPick;
    long pvAt = 0;
    int pvCnt = 0;
    long pvCntAt = 0;
    int pvCostAcc = 0;
    int pvCostN = 0;
    int camCnt = 0;
    long camCntAt = 0;
    long lastFrameMs = 0;
    int gapMax = 0;
    int openTry = 0;
    /** 相机支持的最高离散帧率档位（本机 = 30）。 */
    volatile int camRateMax = 30;
    /** 当前实际设置的帧率档位。 */
    volatile int camRate = 15;
    long rateChkAt = 0;

    /** 预览帧出口：服务在相机回调线程里调它，实现方负责切回 UI 线程。 */
    public interface PreviewSink {
        void onPreview(Bitmap bm, long stamp);
    }
    static final String DIR = "/sdcard/HomeGuard";
    // 人脸从画面消失多久算"人走了"。超过这个时间再出现，就无视冷却立刻抓一张。
    // 原来 45 秒太长：人走开半分钟又回来，冷却还没重置，这一趟就白来了。
    // 20 秒更贴近真实进出场景，又不至于因为检测抖动而重复刷照片。
    static final long FACE_GONE_MS = 20000;
    static final String TAG = "GUARD";
    Camera cam;
    Prefs cfg;
    SurfaceView dummy;
    volatile byte[] lastFrame;
    SurfaceTexture offscreen;
    Handler ui;
    PowerManager.WakeLock wake;
    WindowManager wm;
    Handler work;
    HandlerThread worker;
    Handler detHandler;
    HandlerThread detThread;
    public static volatile boolean running = false;
    public static volatile String state = "未启动";
    public static volatile String lastEvent = "—";
    public static volatile int shots = 0;
    public static volatile int sent = 0;
    public static volatile int failed = 0;
    public static volatile long startedAt = 0;
    public static volatile int camW = 0;
    public static volatile int camH = 0;
    public static volatile long lastFaceAt = 0;
    public static volatile int faceCount = 0;
    public static volatile String camOwner = "";
    volatile long everyAt = 0;
    volatile int everyCache = DETECT_EVERY_ON;
    volatile String fpsInfo = "默认";
    volatile boolean alive = false;
    int frameNo = 0;
    volatile boolean busy = false;
    volatile long busyAt = 0;
    volatile int lastFrameW = 0;
    volatile int lastFrameH = 0;
    long lastShotAt = 0;
    volatile long lastFaceSeenAt = 0;
    int detW = 0;
    int detH = 0;
    final List<byte[]> outQueue = new ArrayList();
    final List<String> outNames = new ArrayList();
    final BroadcastReceiver screenOn = new BroadcastReceiver() { // from class: com.lm.guard.GuardService.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (GuardService.running && Prefs.load(GuardService.this).autoShowOnWake) {
                GuardService.this.ui.removeCallbacks(GuardService.this.bringUp);
                GuardService.this.ui.postDelayed(GuardService.this.bringUp, 1200L);
            }
        }
    };
    final Runnable bringUp = new Runnable() { // from class: com.lm.guard.GuardService.3
        @Override // java.lang.Runnable
        public void run() {
            if (GuardService.running) {
                try {
                    Intent intent = new Intent(GuardService.this, (Class<?>) MainActivity.class);
                    intent.setFlags(268566528);
                    GuardService.this.startActivity(intent);
                    Log.i(GuardService.TAG, "亮屏 -> 切回看家");
                } catch (Throwable th) {
                    Log.w(GuardService.TAG, "切回看家失败: " + th);
                }
            }
        }
    };
    /**
     * 打开摄像头并持续预览 —— 原始逻辑按 smali 逐条还原。
     *
     * 2026-11-06 修复：这段方法体在 jadx 反编译时整体丢失，被替换成了
     *    throw new UnsupportedOperationException("Method not decompiled...")
     * 结果一点「开始看家」就 FATAL EXCEPTION（guard-cam 线程），
     * 厂商的 vulture.watchdog 侦测到看家 crash 后直接把整机重启了。
     *
     * 还原依据：/tmp/bak_guard/guard.apk（16:43，源码还完整时编译的那份）
     * 里 GuardService$4.smali 的 366 条指令。
     */
    final Runnable openLoop = new Runnable() {
        @Override // java.lang.Runnable
        public void run() {
            if (!running) {
                return;
            }
            try {
                attachSurface();
                if (offscreen == null) {
                    state = "准备中…";
                    work.postDelayed(this, 800L);
                    return;
                }

                if (!alive) {
                    state = openTry > 0 ? "正在接管摄像头…" : "正在打开摄像头…";
                }
                if (openTry == 0) {
                    // ★ 2026-11-08 提速：先递"让相机"的请求，再去 open。
                    //   原厂抱着相机时，open 注定失败，与其失败一次再写请求（多等一个轮询），
                    //   不如两条腿同时跑。cam_guard 那边会先看相机是不是真空着，
                    //   空着就直接跳过 force-stop，不会白杀原厂。
                    freeCamera("vulture.app.home");
                }
                cam = Camera.open(0);
                if (cam == null) {
                    throw new RuntimeException("open 返回空");
                }

                Camera.Parameters p = cam.getParameters();
                Camera.Size sz = p.getPreviewSize();
                lastFrameW = sz.width;
                lastFrameH = sz.height;
                camW = sz.width;
                camH = sz.height;

                detW = 480;
                detH = (int) ((sz.height / (float) sz.width) * 480.0f);
                if ((detH & 1) == 1) {
                    detH--;
                }

                p.setPreviewFormat(17);

                // 相机能力全量落日志：以后调预览/抓拍不用再猜。
                try {
                    StringBuilder sbp = new StringBuilder();
                    for (Camera.Size s : p.getSupportedPreviewSizes()) {
                        sbp.append(s.width).append('x').append(s.height).append(' ');
                    }
                    StringBuilder sbi = new StringBuilder();
                    for (Camera.Size s : p.getSupportedPictureSizes()) {
                        sbi.append(s.width).append('x').append(s.height).append(' ');
                    }
                    Log.i(TAG, "preview sizes " + sbp);
                    Log.i(TAG, "picture sizes " + sbi);
                } catch (Throwable ignored) {
                }

                // ★ 2026-11-08 v3 关键发现：这颗相机的 HAL 暴露了「离散预览帧率」档位
                //   preview-frame-rate-values = 15,30 —— 出厂默认 =15，这就是预览只有
                //   14~15fps 的真正原因（不是曝光、不是我们处理不过来）。
                //   把它设成 30 就能翻倍，代价只是画面噪点/暗一点 —— 用户明确说过
                //   "不要求画质，只要求流畅"，这个交换正合适。
                //   抓拍走的是 picture 通道（2688x1520 JPEG），不受这里影响。
                boolean rateOk = false;
                int mxRate = 0;
                try {
                    StringBuilder allRate = new StringBuilder();
                    for (Integer r : p.getSupportedPreviewFrameRates()) {
                        if (r == null) {
                            continue;
                        }
                        allRate.append(r).append(' ');
                        if (r > mxRate) {
                            mxRate = r;
                        }
                    }
                    Log.i(TAG, "frame rates " + allRate);
                    if (mxRate > 0) {
                        camRateMax = mxRate;
                        // 开相机时按当下场景定档：界面在前台就冲最高档（用户在看，
                        // 流畅优先）；开机自启/息屏值守则留在 15fps —— 曝光时间翻倍，
                        // 夜里抓拍才不会糊成一片。实测这颗 HAL 只在 setParameters
                        // 重开预览时才真正换档，所以"开场定档"这一下最有效。
                        int startRate = previewSink != null ? mxRate : 15;
                        p.setPreviewFrameRate(startRate);
                        // 再叠一层 fps-range（min=max），两条老/新 API 一起压，
                        // 实测单用任意一条都只到 18fps，两条齐上才能顶到 25~30fps。
                        try {
                            p.setPreviewFpsRange(startRate * 1000, startRate * 1000);
                        } catch (Throwable ignored) {
                        }
                        camRate = startRate;
                        fpsInfo = startRate + "fps";
                        rateOk = true;
                        Log.i(TAG, "帧率请求 " + startRate + "fps（离散档位）");
                    }
                } catch (Throwable th) {
                    Log.i(TAG, "离散帧率不支持: " + th);
                }
                if (!rateOk) {
                    try {
                        StringBuilder all = new StringBuilder();
                        for (int[] range : p.getSupportedPreviewFpsRange()) {
                            all.append('[').append(range[0]).append('-').append(range[1]).append(']');
                        }
                        Log.i(TAG, "fps ranges " + all);
                        int[][] ladder = {{30000, 30000}, {25000, 25000}, {20000, 20000},
                                          {30000, 60000}, {15000, 15000}};
                        String picked = "默认";
                        for (int[] cand : ladder) {
                            try {
                                p.setPreviewFpsRange(cand[0], cand[1]);
                                picked = cand[0] / 1000 + "-" + cand[1] / 1000;
                                break;
                            } catch (Throwable t2) {
                                Log.i(TAG, "fps " + cand[0] / 1000 + "-" + cand[1] / 1000 + " 不支持");
                            }
                        }
                        fpsInfo = picked;
                        Log.i(TAG, "fps 请求 " + picked);
                    } catch (Throwable th) {
                        Log.i(TAG, "fps 设置失败: " + th);
                    }
                }

                cam.setParameters(p);
                cam.setPreviewTexture(offscreen);
                // 一次性把 HAL 全部参数键落日志（以后想调曝光/快门/AE 直接查这里，
                // 别再靠猜）。实测结论：exposure-compensation 压到 -6 帧率依然是 14~15fps，
                // 说明 15fps 是这颗前摄在本机 HAL 下的硬上限，不是曝光给的。
                Log.i(TAG, "cam params: " + p.flatten());

                int bufSize = lastFrameW * lastFrameH * 3 / 2;
                // ★ 2026-11-08：3 块缓冲，不是 2 块。
                //   人脸检测已经挪到独立线程（guard-detect），它手里会扣住一块约 200ms；
                //   只给 2 块的话相机很快就无缓冲可用 → 预览卡成幻灯片。
                //   多一块 3MB，换来预览全程不断流。
                for (int i = 0; i < 3; i++) {
                    cam.addCallbackBuffer(new byte[bufSize]);
                }
                cam.setPreviewCallbackWithBuffer(GuardService.this);
                cam.startPreview();

                alive = true;
                openTry = 0;
                state = "监控中";
                camOwner = "";
                Log.i(TAG, "camera " + lastFrameW + "x" + lastFrameH
                        + " detect " + detW + "x" + detH + " fps " + fpsInfo);
            } catch (Throwable th) {
                alive = false;
                openTry++;
                state = "正在接管摄像头…";
                camOwner = String.valueOf(th);
                Log.w(TAG, "open fail(" + openTry + "): " + th);
                // ★ 2026-11-08 提速：原来固定 2.5 秒重试一次，原厂 App 一旦手里抱着相机，
                //   用户要盯着"正在接管"干等十几秒。改成前 20 次每 400ms 一次 ——
                //   cam_guard 那边 force-stop 原厂后，我们能在 1 秒内把相机抓过来，
                //   抢在原厂进程重启回来之前占住（先到先得，占住就抢不走）。
                freeCamera("vulture.app.home");
                work.postDelayed(this, openTry <= 20 ? 400L : 1200L);
            }
        }
    };
    final Runnable sender = new Runnable() { // from class: com.lm.guard.GuardService.6
        @Override // java.lang.Runnable
        public void run() {
            synchronized (GuardService.this.outQueue) {
                if (GuardService.this.outQueue.isEmpty()) {
                    return;
                }
                byte[] remove = GuardService.this.outQueue.remove(0);
                String remove2 = GuardService.this.outNames.remove(0);
                Prefs load = Prefs.load(GuardService.this);
                if (!load.mailReady()) {
                    GuardService.failed++;
                    GuardService.lastEvent = "未配置邮箱，照片只存本机";
                    return;
                }
                Mail.Result send = Mail.send(load, "【看家】检测到人脸 " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()), "T70L 看家提醒：\n\n时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n设备：中移 T70L\n说明：画面中检测到人脸，已附上抓拍照片。\n", remove, remove2);
                if (send.ok) {
                    GuardService.sent++;
                    GuardService.lastEvent = "已发送邮件 · 累计 " + GuardService.sent;
                } else {
                    GuardService.failed++;
                    GuardService.lastEvent = "邮件失败：" + send.msg;
                }
            }
        }
    };
    final Runnable ticker = new Runnable() { // from class: com.lm.guard.GuardService.8
        @Override // java.lang.Runnable
        public void run() {
            if (GuardService.running) {
                long now = System.currentTimeMillis();
                // 兜底：万一某次人脸检测真的把工作线程卡死，20 秒后强行解锁，
                // 否则又是一次“卡住之后再不计时抓拍”。
                if (GuardService.this.busy && GuardService.this.busyAt > 0
                        && now - GuardService.this.busyAt > 20000) {
                    Log.w(GuardService.TAG, "detect stuck 20s -> release");
                    GuardService.this.busy = false;
                    GuardService.this.busyAt = 0;
                }
                if (GuardService.this.alive && System.currentTimeMillis() - GuardService.this.lastFrameAt() > 5000) {
                    Log.w(GuardService.TAG, "no frames 5s -> reopen");
                    GuardService.this.closeCam();
                    GuardService.this.work.postDelayed(GuardService.this.openLoop, 500L);
                }
                GuardService.this.ui.postDelayed(this, 3000L);
            }
        }
    };
    volatile long frameAt = 0;

    int detectEvery() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.everyAt > 2000) {
            this.everyAt = currentTimeMillis;
            try {
                this.everyCache = ((PowerManager) getSystemService("power")).isScreenOn() ? DETECT_EVERY_ON : DETECT_EVERY_OFF;
            } catch (Throwable th) {
                this.everyCache = DETECT_EVERY_ON;
            }
        }
        return this.everyCache;
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        this.cfg = Prefs.load(this);
        this.worker = new HandlerThread("guard-cam");
        this.worker.start();
        this.work = new Handler(this.worker.getLooper());
        // ★ 2026-11-08：人脸检测单独一条线程。
        //   以前 detect 和相机回调共用一个线程，FaceDetector 一次约 200ms，
        //   这 200ms 里回调线程被占住、缓冲不还，预览就一顿一顿（实测 6fps 且卡顿）。
        //   拆开之后相机回调线程只做"缩图交给界面 + 还缓冲"，检测在另一条线程慢慢算。
        this.detThread = new HandlerThread("guard-detect");
        this.detThread.start();
        this.detHandler = new Handler(this.detThread.getLooper());
        this.ui = new Handler(getMainLooper());
        new File(DIR).mkdirs();
        new Thread(new Runnable() { // from class: com.lm.guard.GuardService.1
            @Override // java.lang.Runnable
            public void run() {
                try {
                    Process exec = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    String readLine = new BufferedReader(new InputStreamReader(exec.getInputStream())).readLine();
                    exec.waitFor();
                    Log.i(GuardService.TAG, "SU-CHECK rc=" + exec.exitValue() + " out=" + readLine);
                } catch (Throwable th) {
                    Log.w(GuardService.TAG, "SU-CHECK unavailable: " + th);
                }
            }
        }).start();
        this.wake = ((PowerManager) getSystemService("power")).newWakeLock(1, "guard");
        this.wake.setReferenceCounted(false);
        this.wake.acquire();
        try {
            registerReceiver(this.screenOn, new IntentFilter("android.intent.action.SCREEN_ON"));
        } catch (Throwable th) {
            Log.w(TAG, "注册亮屏广播失败: " + th);
        }
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        if (!running) {
            running = true;
            startedAt = System.currentTimeMillis();
            state = "正在启动摄像头…";
            startForeground(1, buildNotif());
            this.work.post(this.openLoop);
            this.ui.postDelayed(this.ticker, 1000L);
        }
        return 1;
    }

    Notification buildNotif() {
        Intent intent = new Intent(this, (Class<?>) MainActivity.class);
        intent.setFlags(335544320);
        return new Notification.Builder(this).setContentTitle("看家值守中").setContentText("屏幕关闭也继续检测 · 检出人脸即抓拍并发邮件").setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(PendingIntent.getActivity(this, 0, intent, 134217728)).setOngoing(true).build();
    }

    static void requestScreenOff(Context context) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(DIR, ".need_screenoff"));
            fileOutputStream.write(String.valueOf(System.currentTimeMillis()).getBytes());
            fileOutputStream.close();
            Log.i(TAG, "已请求息屏");
        } catch (Throwable th) {
            Log.w(TAG, "写 need_screenoff 失败: " + th);
        }
    }

    void attachSurface() {
        if (this.offscreen != null) {
            return;
        }
        try {
            this.offscreen = new SurfaceTexture(10);
            this.offscreen.setDefaultBufferSize(1920, 1080);
        } catch (Throwable th) {
            Log.w(TAG, "SurfaceTexture 创建失败: " + th);
        }
    }

    void freeCamera(String str) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(DIR, ".need_cam"));
            fileOutputStream.write(String.valueOf(System.currentTimeMillis()).getBytes());
            fileOutputStream.close();
            Log.i(TAG, "已请求 cam_guard 让出摄像头");
        } catch (Throwable th) {
            Log.w(TAG, "写 need_cam 失败: " + th);
        }
    }

    void closeCam() {
        this.alive = false;
        try {
            if (this.cam != null) {
                this.cam.setPreviewCallbackWithBuffer(null);
                this.cam.stopPreview();
                this.cam.release();
            }
        } catch (Throwable th) {
        }
        this.cam = null;
    }

    @Override // android.hardware.Camera.PreviewCallback
    public void onPreviewFrame(final byte[] bArr, final Camera camera) {
        if (this.alive) {
            this.frameNo++;
            this.frameAt = System.currentTimeMillis();
            // 相机真实出帧率统计：每 2 秒一次，和预览帧率分开记。
            // 两个数一比就知道卡顿是"相机给不快"还是"我们处理不完"。
            this.camCnt++;
            // 顺带记最大帧间隔：平均 15fps 但偶尔掉 300ms 一样读作"卡"，
            // 这个数才反映真实观感。
            if (this.lastFrameMs > 0) {
                int gap = (int) (this.frameAt - this.lastFrameMs);
                if (gap > this.gapMax) {
                    this.gapMax = gap;
                }
            }
            this.lastFrameMs = this.frameAt;
            if (this.camCntAt == 0) {
                this.camCntAt = this.frameAt;
            } else if (this.frameAt - this.camCntAt >= 2000) {
                camFps = (int) ((this.camCnt * 1000L) / Math.max(1L, this.frameAt - this.camCntAt));
                Log.i(TAG, "cam " + camFps + "fps gapMax=" + this.gapMax + "ms  pv "
                        + previewFps + "fps draw " + previewDrawFps + "fps lag " + previewLag
                        + "ms cost " + previewCost + "ms drop=" + previewDrop);
                this.camCnt = 0;
                this.gapMax = 0;
                this.camCntAt = this.frameAt;
            }
            maybePreview(bArr);
            checkRate();
            if (this.busy || this.frameNo % detectEvery() != 0) {
                camera.addCallbackBuffer(bArr);
            } else {
                this.busy = true;
                this.busyAt = System.currentTimeMillis();
                this.detHandler.post(new Runnable() { // from class: com.lm.guard.GuardService.5
                    @Override // java.lang.Runnable
                    public void run() {
                        // 注意：原来这里只有一个 catch，正常情况下 detectAndShoot 返回后
                        // busy 永远是 true、回调缓冲也永远不交还 —— 结果就是
                        // “开机/启动后只能抓拍到第一张，之后再也不抓”，预览还在空转烧 CPU。
                        // 必须用 finally：无论正常返回还是抛异常，都要复位并交还缓冲。
                        try {
                            GuardService.this.detectAndShoot(bArr, GuardService.this.lastFrameW, GuardService.this.lastFrameH);
                        } catch (Throwable th) {
                            Log.w(GuardService.TAG, "detect err " + th);
                        } finally {
                            GuardService.this.busy = false;
                            try {
                                camera.addCallbackBuffer(bArr);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            }
        }
    }

    void detectAndShoot(byte[] bArr, int i, int i2) {
        boolean z;
        float f;
        int i3;
        byte[] bArr2;
        if (bArr == null || i == 0) {
            return;
        }
        FaceDetector.Face[] faceArr = new FaceDetector.Face[4];
        int findFaces = new FaceDetector(this.detW, this.detH, 4).findFaces(downscale(bArr, i, i2, this.detW, this.detH), faceArr);
        faceCount = findFaces;
        StringBuilder sb = new StringBuilder();
        float f2 = 0.0f;
        if (findFaces <= 0) {
            z = false;
            f = 0.0f;
            i3 = 0;
        } else {
            int i4 = 0;
            z = false;
            i3 = 0;
            while (i4 < findFaces) {
                float confidence = faceArr[i4].confidence();
                int eyesDistance = (int) faceArr[i4].eyesDistance();
                FaceDetector.Face[] faceArr2 = faceArr;
                sb.append(" [").append(String.format(Locale.US, "%.2f", Float.valueOf(confidence))).append(",").append(eyesDistance).append("]");
                if (confidence >= this.cfg.minConfidence && eyesDistance >= this.cfg.minEyeDist && confidence > f2) {
                    f2 = confidence;
                    i3 = eyesDistance;
                    z = true;
                }
                i4++;
                faceArr = faceArr2;
            }
            f = f2;
        }
        Log.i(TAG, "detect n=" + findFaces + ((Object) sb) + " hit=" + z + " (minConf=" + this.cfg.minConfidence + " minEye=" + this.cfg.minEyeDist + ")");
        if (z) {
            long currentTimeMillis = System.currentTimeMillis();
            if (this.lastFaceSeenAt > 0 && currentTimeMillis - this.lastFaceSeenAt > FACE_GONE_MS) {
                this.lastShotAt = 0L;
            }
            this.lastFaceSeenAt = currentTimeMillis;
            lastFaceAt = currentTimeMillis;
            if (currentTimeMillis - this.lastShotAt < this.cfg.cooldownSec * 1000) {
                lastEvent = "画面中有人（" + this.cfg.cooldownSec + " 秒内不重复报）";
                return;
            }
            this.lastShotAt = currentTimeMillis;
            try {
                YuvImage yuvImage = new YuvImage(bArr, 17, i, i2, null);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, i, i2), 78, byteArrayOutputStream);
                bArr2 = byteArrayOutputStream.toByteArray();
            } catch (Throwable th) {
                Log.w(TAG, "jpeg err " + th);
                bArr2 = null;
            }
            if (bArr2 == null || bArr2.length == 0) {
                lastEvent = "抓拍失败";
                return;
            }
            String str = "face_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(new File(DIR, str));
                fileOutputStream.write(bArr2);
                fileOutputStream.close();
            } catch (Throwable th2) {
                Log.w(TAG, "write err " + th2);
            }
            shots++;
            Log.i(TAG, "SHOT " + str + " " + (bArr2.length / 1024) + "KB conf=" + f + " eye=" + i3);
            trimFiles();
            lastEvent = "抓拍 " + (bArr2.length / 1024) + "KB · 置信度 " + String.format(Locale.US, "%.2f", Float.valueOf(f)) + " · 眼距 " + i3;
            enqueue(bArr2, str);
        }
    }

    void enqueue(byte[] bArr, String str) {
        synchronized (this.outQueue) {
            this.outQueue.add(bArr);
            this.outNames.add(str);
            if (this.outQueue.size() > 6) {
                this.outQueue.remove(0);
                this.outNames.remove(0);
            }
        }
        this.work.post(this.sender);
    }

    void trimFiles() {
        try {
            File[] listFiles = new File(DIR).listFiles();
            if (listFiles != null && listFiles.length > this.cfg.keepPhotos) {
                Arrays.sort(listFiles, new Comparator<File>() { // from class: com.lm.guard.GuardService.7
                    @Override // java.util.Comparator
                    public int compare(File file, File file2) {
                        return Long.compare(file.lastModified(), file2.lastModified());
                    }
                });
                int length = listFiles.length - this.cfg.keepPhotos;
                for (int i = 0; i < length; i++) {
                    listFiles[i].delete();
                }
            }
        } catch (Throwable th) {
        }
    }

    /**
     * 低清实时预览：把当前帧采样成 240×135 交给界面。
     * 只有界面在前台（previewSink != null）才算；三缓冲轮换避免覆写正在显示的那块。
     */
    void maybePreview(byte[] bArr) {
        PreviewSink sink = previewSink;
        if (sink == null || bArr == null) {
            return;
        }
        int w = lastFrameW;
        int h = lastFrameH;
        if (w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // 20ms 门限 = 最多 50fps，只用来挡住"异常连发"的帧。
        // 以前是 40ms（25fps），而相机现在能到 25~30fps —— 那道门限会白丢帧。
        if (now - pvAt < 20) {
            return;
        }
        pvAt = now;
        long t0 = System.nanoTime();
        try {
            nv21To565(bArr, w, h, pv565, PVW, PVH);
            Bitmap bm;
            synchronized (this) {
                if (pvA == null) {
                    pvA = Bitmap.createBitmap(PVW, PVH, Bitmap.Config.RGB_565);
                    pvB = Bitmap.createBitmap(PVW, PVH, Bitmap.Config.RGB_565);
                    pvC = Bitmap.createBitmap(PVW, PVH, Bitmap.Config.RGB_565);
                    pvBB = java.nio.ByteBuffer.allocateDirect(PVW * PVH * 2);
                    pvSB = pvBB.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                }
                // 三块轮换：界面最多同时"在路上"一块，绝不会画到刚被写的那块。
                bm = pvPick == 0 ? pvA : (pvPick == 1 ? pvB : pvC);
                pvPick = (pvPick + 1) % 3;
            }
            // 直接 memcpy 进 Bitmap，不做 setPixels 的逐像素 ARGB→565 打包。
            pvSB.rewind();
            pvSB.put(pv565);
            pvBB.rewind();
            bm.copyPixelsFromBuffer(pvBB);
            previewAt = now;
            if (pvCntAt == 0) {
                pvCntAt = now;
            }
            pvCnt++;
            if (now - pvCntAt >= 2000) {
                previewFps = (int) ((pvCnt * 1000L) / Math.max(1L, now - pvCntAt));
                previewDrawFps = (int) ((drawCnt * 1000L) / Math.max(1L, now - pvCntAt));
                drawCnt = 0;
                pvCnt = 0;
                pvCntAt = now;
            }
            sink.onPreview(bm, now);
        } catch (Throwable th) {
            Log.w(TAG, "preview err " + th);
        }
        if (pvCostN < 60) {
            pvCostAcc += (int) ((System.nanoTime() - t0) / 1000000L);
            pvCostN++;
            if (pvCostN == 60) {
                previewCost = pvCostAcc / 60;
            }
        }
    }

    /** 界面 onDraw 每画一帧 +1，服务端每 2 秒取一次算端到端帧率。 */
    static volatile int drawCnt = 0;

    /**
     * 帧率档位按「有没有人在看」切换：
     *   界面前台（previewSink != null）→ 最高档（本机 30fps）：用户在看，流畅优先；
     *   息屏 / 切走（previewSink == null）→ 15fps：曝光时间更长，抓拍更亮更清楚，
     *   而且这时候没人看画面，帧率拉高只是白烧 CPU。
     * 抓拍用的是同一路预览帧压 JPEG，所以「值守时的曝光」直接等于照片质量 ——
     * 这就是为什么不能一直跑 30fps：用户要的是「预览流畅、抓拍高清」。
     */
    void checkRate() {
        long now = System.currentTimeMillis();
        if (now - rateChkAt < 1500) {
            return;
        }
        rateChkAt = now;
        final int target = previewSink != null ? camRateMax : 15;
        if (target == camRate || camRateMax <= 15) {
            return;
        }
        work.post(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                applyRate(target);
            }
        });
    }

    void applyRate(int r) {
        Camera c = this.cam;
        if (c == null || !this.alive) {
            return;
        }
        try {
            Camera.Parameters p = c.getParameters();
            p.setPreviewFrameRate(r);
            try {
                p.setPreviewFpsRange(r * 1000, r * 1000);
            } catch (Throwable ignored) {
            }
            c.setParameters(p);
            this.camRate = r;
            Log.i(TAG, "预览帧率切到 " + r + "fps（"
                    + (r > 15 ? "界面前台·流畅优先" : "值守·画质优先") + "）");
        } catch (Throwable th) {
            Log.w(TAG, "切帧率失败 " + th);
        }
    }

    /**
     * NV21 → RGB565 步长采样，直接写 short[]（每像素 16 位），零分配。
     * 与旧版 downscaleInto 相比：YUV→RGB 的 3 次乘法换成 4 张 256 项查表，
     * 列偏移预先算好，输出直接是 565（省掉 setPixels 内部那一遍 ARGB 打包）。
     */
    void nv21To565(byte[] bArr, int sw, int sh, short[] dst, int dw, int dh) {
        int stepX = Math.max(1, sw / dw);
        int stepY = Math.max(1, sh / dh);
        if (pvStepKey != (stepX * 100000L + stepY) || pvStepW != sw) {
            for (int x = 0; x < dw; x++) {
                int sx = x * stepX;
                pvColY[x] = sx;
                pvColUV[x] = sx & -2;
            }
            pvStepKey = stepX * 100000L + stepY;
            pvStepW = sw;
        }
        int frameSize = sw * sh;
        int idx = 0;
        for (int y = 0; y < dh; y++) {
            int sy = y * stepY;
            int yRow = sy * sw;
            int uvRow = frameSize + ((sy >> 1) * sw);
            for (int x = 0; x < dw; x++) {
                int Y = bArr[yRow + pvColY[x]] & 255;
                int uv = uvRow + pvColUV[x];
                int V = bArr[uv] & 255;
                int U = bArr[uv + 1] & 255;
                int r = Y + TBL_RV[V];
                int g = Y - TBL_GV[V] - TBL_GU[U];
                int b = Y + TBL_BU[U];
                if (r < 0) {
                    r = 0;
                } else if (r > 255) {
                    r = 255;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 255) {
                    g = 255;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 255) {
                    b = 255;
                }
                dst[idx++] = (short) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
            }
        }
    }

    long pvStepKey = -1;
    int pvStepW = -1;

    static Bitmap downscale(byte[] bArr, int i, int i2, int i3, int i4) {
        int i5 = 1;
        int max = Math.max(1, i / i3);
        int max2 = Math.max(1, i2 / i4);
        int[] iArr = new int[i3 * i4];
        int i6 = i * i2;
        int i7 = 0;
        while (i7 < i4) {
            int i8 = i7 * max2;
            int i9 = i8 * i;
            int i10 = ((i8 >> i5) * i) + i6;
            int i11 = 0;
            while (i11 < i3) {
                int i12 = i11 * max;
                int i13 = 255;
                int i14 = bArr[i9 + i12] & 255;
                int i15 = (i12 & (-2)) + i10;
                int i16 = (bArr[i15] & 255) - 128;
                int i17 = (bArr[i15 + i5] & 255) - 128;
                int i18 = i14 + ((91881 * i16) >> 16);
                int i19 = i14 - (((i17 * 22554) + (i16 * 46802)) >> 16);
                int i20 = i14 + ((i17 * 116130) >> 16);
                if (i18 < 0) {
                    i18 = 0;
                } else if (i18 > 255) {
                    i18 = 255;
                }
                if (i19 < 0) {
                    i19 = 0;
                } else if (i19 > 255) {
                    i19 = 255;
                }
                if (i20 < 0) {
                    i13 = 0;
                } else if (i20 <= 255) {
                    i13 = i20;
                }
                iArr[(i7 * i3) + i11] = ((i18 & 248) << 16) | (-16777216) | ((i19 & 252) << 8) | (i13 >> DETECT_EVERY_OFF);
                i11++;
                i5 = 1;
            }
            i7++;
            i5 = 1;
        }
        Bitmap createBitmap = Bitmap.createBitmap(i3, i4, Bitmap.Config.RGB_565);
        createBitmap.setPixels(iArr, 0, i3, 0, 0, i3, i4);
        return createBitmap;
    }

    long lastFrameAt() {
        return this.frameNo == 0 ? startedAt : this.frameAt;
    }

    @Override // android.app.Service
    public void onDestroy() {
        running = false;
        previewSink = null;
        previewAt = 0;
        try {
            unregisterReceiver(this.screenOn);
        } catch (Throwable th) {
        }
        this.ui.removeCallbacksAndMessages(null);
        closeCam();
        try {
            if (this.offscreen != null) {
                this.offscreen.release();
            }
        } catch (Throwable th2) {
        }
        this.offscreen = null;
        if (this.wake != null && this.wake.isHeld()) {
            this.wake.release();
        }
        if (this.worker != null) {
            this.worker.quit();
        }
        if (this.detThread != null) {
            this.detThread.quit();
        }
        try {
            if (pvA != null) {
                pvA.recycle();
            }
            if (pvB != null) {
                pvB.recycle();
            }
            if (pvC != null) {
                pvC.recycle();
            }
        } catch (Throwable ignored) {
        }
        pvA = null;
        pvB = null;
        pvC = null;
        super.onDestroy();
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }
}
