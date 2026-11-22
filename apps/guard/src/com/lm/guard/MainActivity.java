package com.lm.guard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.lm.guard.Mail;
import java.io.File;

/* loaded from: classes.dex */
public class MainActivity extends Activity {
    static final int AMBER = -1007058;
    static final int BG_DARK = -16052458;
    static final int BLUE = -13668374;
    static final int BLUE_D = -14987329;
    static final int CARD = -15262166;
    static final int CARD_LINE = -14405564;
    static final int GREEN = -13648015;
    static final int PV_H = 332;
    static final int PV_L = 650;
    static final int PV_T = 116;
    static final int PV_W = 590;
    static final int RED = -1750963;
    static final int TXT = -1445896;
    static final int TXT2 = -6640446;
    static final int TXT3 = -10588023;
    CheckBox cbSsl;
    CheckBox cbWake;
    Prefs cfg;
    EditText eConf;
    EditText eCool;
    EditText eHost;
    EditText ePass;
    EditText ePort;
    EditText eTo;
    EditText eUser;
    LinearLayout panel;
    TextView pvHint;
    FrameLayout root;
    ImageView shotView;
    PreviewView pvView;
    GuardView view;
    volatile Bitmap livePending;
    volatile boolean liveBusy;
    long lastShotStamp = 0;
    volatile long liveStamp = 0;
    Handler ui = new Handler();
    final Runnable refresh = new Runnable() { // from class: com.lm.guard.MainActivity.1
        @Override // java.lang.Runnable
        public void run() {
            MainActivity.this.view.invalidate();
            // 实时预览在跑时不要再往预览窗塞"最新抓拍"（会盖掉流动画面）；
            // 只有预览断了 1.5 秒以上，才退回显示最近一张抓拍。
            if (System.currentTimeMillis() - GuardService.previewAt > 1500) {
                if (MainActivity.this.pvView != null && MainActivity.this.pvView.getVisibility() == 0) {
                    MainActivity.this.pvView.setVisibility(8);
                    MainActivity.this.shotView.setVisibility(0);
                }
                MainActivity.this.loadLatest();
            }
            MainActivity.this.updateHint();
            MainActivity.this.ui.postDelayed(this, 1000L);
        }
    };

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setFlags(1024, 1024);
        this.cfg = Prefs.load(this);
        this.root = new FrameLayout(this);
        this.view = new GuardView(this);
        this.root.addView(this.view, new FrameLayout.LayoutParams(-1, -1));
        this.shotView = new ImageView(this);
        this.shotView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        this.shotView.setBackgroundColor(-15788767);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(PV_W, PV_H);
        layoutParams.leftMargin = PV_L;
        layoutParams.topMargin = PV_T;
        this.root.addView(this.shotView, layoutParams);
        // ★ 2026-11-08：实时预览专用画布，压在抓拍图上面。
        //   不用 ImageView.setImageBitmap —— 那个每帧都 new 一个 BitmapDrawable
        //   并触发一次 layout，1GB 的盒子上光这步就能把 UI 线程拖垮（看着像卡帧）。
        this.pvView = new PreviewView(this);
        FrameLayout.LayoutParams lpPv = new FrameLayout.LayoutParams(PV_W, PV_H);
        lpPv.leftMargin = PV_L;
        lpPv.topMargin = PV_T;
        this.pvView.setVisibility(8);
        this.root.addView(this.pvView, lpPv);
        this.pvHint = new TextView(this);
        this.pvHint.setText("暂无画面\n点「开始看家」后这里显示实时画面");
        this.pvHint.setTextColor(-11773072);
        this.pvHint.setTextSize(17.0f);
        this.pvHint.setGravity(17);
        FrameLayout.LayoutParams layoutParams2 = new FrameLayout.LayoutParams(PV_W, PV_H);
        layoutParams2.leftMargin = PV_L;
        layoutParams2.topMargin = PV_T;
        this.root.addView(this.pvHint, layoutParams2);
        buildPanel();
        this.root.addView(this.panel, new FrameLayout.LayoutParams(-1, -1));
        this.panel.setVisibility(8);
        setContentView(this.root);
        this.ui.post(this.refresh);
    }

    void startGuard() {
        if (!this.cfg.mailReady()) {
            Toast.makeText(this, "请先在设置里填好发件邮箱，否则只会在本机存照片", 1).show();
        }
        startService(new Intent(this, (Class<?>) GuardService.class));
        Toast.makeText(this, "看家已启动 · 屏幕可以关了", 0).show();
    }

    void stopGuard() {
        stopService(new Intent(this, (Class<?>) GuardService.class));
        Toast.makeText(this, "看家已停止", 0).show();
    }

    void sleepGuard() {
        if (!GuardService.running) {
            if (!this.cfg.mailReady()) {
                Toast.makeText(this, "没有配置发件邮箱，只会把照片存在本机", 1).show();
            }
            startService(new Intent(this, (Class<?>) GuardService.class));
        }
        Toast.makeText(this, "屏幕即将关闭，看家继续值守；按电源键可随时唤醒", 0).show();
        this.ui.postDelayed(new Runnable() { // from class: com.lm.guard.MainActivity.2
            @Override // java.lang.Runnable
            public void run() {
                GuardService.requestScreenOff(MainActivity.this);
            }
        }, 3200L);
    }

    class GuardView extends View {
        Typeface bold;
        Paint p;
        RectF r;
        long t0;
        Paint tp;

        GuardView(Context context) {
            super(context);
            this.p = new Paint(1);
            this.tp = new Paint(1);
            this.r = new RectF();
            this.bold = Typeface.create("sans-serif-medium", 0);
            this.t0 = System.currentTimeMillis();
            this.tp.setTypeface(this.bold);
        }

        void card(Canvas canvas, float f, float f2, float f3, float f4, int i) {
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor(i);
            canvas.drawRoundRect(this.r, 22.0f, 22.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(MainActivity.CARD_LINE);
            canvas.drawRoundRect(this.r, 22.0f, 22.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            float f;
            String str;
            int i;
            this.p.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, getHeight(), -15591388, MainActivity.BG_DARK, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.p);
            this.p.setShader(null);
            this.tp.setTextSize(30.0f);
            this.tp.setColor(MainActivity.TXT);
            canvas.drawText("看家", 48.0f, 66.0f, this.tp);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText("T70L  ·  人脸检测抓拍", 132.0f, 66.0f, this.tp);
            ghost(canvas, 866.0f, 40.0f, 1026.0f, 92.0f, "云台", false);
            ghost(canvas, 1046.0f, 40.0f, 1204.0f, 92.0f, "返回桌面", false);
            boolean z = GuardService.running;
            String str2 = "监控中";
            boolean z2 = GuardService.running && "监控中".equals(GuardService.state);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(16.0f);
            this.p.setColor(-14932682);
            canvas.drawCircle(300.0f, 272.0f, 124.0f, this.p);
            if (z) {
                float currentTimeMillis = ((System.currentTimeMillis() - this.t0) % 2600) / 2600.0f;
                this.p.setStrokeWidth(16.0f);
                this.p.setColor(z2 ? MainActivity.GREEN : MainActivity.AMBER);
                this.p.setAlpha(z2 ? 200 : 150);
                this.r.set(176.0f, 148.0f, 424.0f, 396.0f);
                f = 300.0f;
                canvas.drawArc(this.r, (-90.0f) + (currentTimeMillis * 360.0f), 110.0f, false, this.p);
                this.p.setAlpha(255);
            } else {
                f = 300.0f;
            }
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(22.0f);
            this.tp.setColor(MainActivity.TXT2);
            if (!z) {
                str2 = "待机";
            } else if (!z2) {
                str2 = "准备中";
            }
            canvas.drawText(str2, f - (this.tp.measureText(str2) / 2.0f), 260.0f, this.tp);
            this.tp.setTextSize(52.0f);
            this.tp.setTypeface(this.bold);
            this.tp.setColor(z ? MainActivity.TXT : MainActivity.TXT3);
            String valueOf = String.valueOf(GuardService.shots);
            canvas.drawText(valueOf, f - (this.tp.measureText(valueOf) / 2.0f), 316.0f, this.tp);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText("已抓拍", f - (this.tp.measureText("已抓拍") / 2.0f), 346.0f, this.tp);
            boolean z3 = GuardService.running;
            this.r.set(130.0f, 438.0f, 470.0f, 512.0f);
            this.p.setColor(z3 ? -12968670 : MainActivity.BLUE);
            if (!z3) {
                this.p.setShader(new LinearGradient(130.0f, 438.0f, 470.0f, 512.0f, -12878347, MainActivity.BLUE_D, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(this.r, 26.0f, 26.0f, this.p);
            this.p.setShader(null);
            this.tp.setTextSize(25.0f);
            this.tp.setColor(-1);
            String str3 = z3 ? "停止看家" : "开始看家";
            canvas.drawText(str3, f - (this.tp.measureText(str3) / 2.0f), 488.0f, this.tp);
            sleepBtn(canvas, 130.0f, 524.0f, 470.0f, 586.0f);
            button(canvas, 130.0f, 598.0f, 290.0f, 654.0f, "设置", false);
            button(canvas, 310.0f, 598.0f, 470.0f, 654.0f, "测试邮件", false);
            card(canvas, 642.0f, 108.0f, 1248.0f, 456.0f, MainActivity.CARD);
            this.tp.setTextSize(14.0f);
            if (!GuardService.running) {
                str = "摄像头 · 未启动";
                i = -11773072;
            } else if (GuardService.camW > 0) {
                str = "摄像头 " + GuardService.camW + "×" + GuardService.camH + (z2 ? " · 实时 " + GuardService.previewFps + "fps" : " · 接管中");
                i = z2 ? MainActivity.GREEN : MainActivity.AMBER;
            } else {
                str = "摄像头 · " + (GuardService.state == null ? "正在接管" : GuardService.state);
                i = MainActivity.AMBER;
            }
            this.tp.setColor(i);
            canvas.drawText(str, 660.0f, 478.0f, this.tp);
            card(canvas, 642.0f, 500.0f, 1248.0f, 752.0f, MainActivity.CARD);
            row(canvas, "已发送邮件", String.valueOf(GuardService.sent), GuardService.sent > 0 ? MainActivity.GREEN : MainActivity.TXT2, 540.0f);
            row(canvas, "发送失败", String.valueOf(GuardService.failed), GuardService.failed > 0 ? MainActivity.RED : MainActivity.TXT2, 574.0f);
            row(canvas, "发件邮箱", MainActivity.this.cfg.mailReady() ? "已配置" : "未配置", MainActivity.this.cfg.mailReady() ? MainActivity.GREEN : MainActivity.AMBER, 608.0f);
            rowLink(canvas, "本地照片", MainActivity.this.countFiles() + " 张", 642.0f);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText("最近事件", 664.0f, 684.0f, this.tp);
            this.tp.setTextSize(17.0f);
            this.tp.setColor(MainActivity.TXT);
            canvas.drawText(clip(GuardService.lastEvent == null ? "—" : GuardService.lastEvent, 32), 664.0f, 714.0f, this.tp);
            this.tp.setTextSize(14.0f);
            this.tp.setColor(-11312262);
            canvas.drawText("待机不录像，检出人脸抓拍一张并发到邮箱", 48.0f, 690.0f, this.tp);
            this.tp.setColor(-11180160);
            canvas.drawText("出门前点「息屏值守」，屏幕关掉后仍在工作 · 只保留最近 " + MainActivity.this.cfg.keepPhotos + " 张", 48.0f, 716.0f, this.tp);
            this.tp.setTypeface(Typeface.DEFAULT);
        }

        void button(Canvas canvas, float f, float f2, float f3, float f4, String str, boolean z) {
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor(z ? -14536372 : -15064269);
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(MainActivity.CARD_LINE);
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(MainActivity.TXT2);
            canvas.drawText(str, ((f + f3) / 2.0f) - (this.tp.measureText(str) / 2.0f), f2 + 42.0f, this.tp);
        }

        void sleepBtn(Canvas canvas, float f, float f2, float f3, float f4) {
            boolean z = GuardService.running;
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setShader(new LinearGradient(f, f2, f3, f4, z ? -14865342 : -14931394, -15590616, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.p.setShader(null);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(z ? -13547937 : -14009774);
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            float f5 = f + 44.0f;
            float f6 = (f2 + f4) / 2.0f;
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(2.8f);
            this.p.setStrokeCap(Paint.Cap.ROUND);
            this.p.setColor(z ? -7950628 : -6504208);
            float f7 = f6 - 11.5f;
            this.r.set(f5 - 11.5f, f7, f5 + 11.5f, 11.5f + f6);
            canvas.drawArc(this.r, 300.0f, 300.0f, false, this.p);
            canvas.drawLine(f5, f7 - 3.5f, f5, f6 + 1.0f, this.p);
            this.p.setStrokeCap(Paint.Cap.BUTT);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(20.0f);
            this.tp.setColor(MainActivity.TXT);
            canvas.drawText(z ? "立即息屏" : "息屏值守", f + 76.0f, 7.0f + f6, this.tp);
            this.tp.setTextSize(13.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText(z ? "屏幕已关也在跑" : "关屏继续工作", f + 190.0f, f6 + 6.0f, this.tp);
        }

        void ghost(Canvas canvas, float f, float f2, float f3, float f4, String str, boolean z) {
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(z ? MainActivity.BLUE : MainActivity.CARD_LINE);
            canvas.drawRoundRect(this.r, 22.0f, 22.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(17.0f);
            this.tp.setColor(MainActivity.TXT2);
            canvas.drawText(str, ((f + f3) / 2.0f) - (this.tp.measureText(str) / 2.0f), ((f2 + f4) / 2.0f) + 6.0f, this.tp);
        }

        void row(Canvas canvas, String str, String str2, int i, float f) {
            this.tp.setTextSize(16.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText(str, 664.0f, f, this.tp);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(i);
            canvas.drawText(str2, 1226.0f - this.tp.measureText(str2), f, this.tp);
        }

        void rowLink(Canvas canvas, String str, String str2, float f) {
            this.r.set(654.0f, f - 28.0f, 1236.0f, f + 12.0f);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor(-14997960);
            canvas.drawRoundRect(this.r, 12.0f, 12.0f, this.p);
            this.tp.setTextSize(16.0f);
            this.tp.setColor(MainActivity.TXT3);
            canvas.drawText(str, 666.0f, f, this.tp);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(MainActivity.TXT);
            canvas.drawText(str2, 1144.0f - this.tp.measureText(str2), f, this.tp);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(-9593880);
            canvas.drawText("管理", 1164.0f, f, this.tp);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(2.0f);
            this.p.setStrokeCap(Paint.Cap.ROUND);
            this.p.setColor(-9593880);
            float f2 = f - 6.0f;
            canvas.drawLine(1204.0f, f2 - 6.0f, 1210.0f, f2, this.p);
            canvas.drawLine(1210.0f, f2, 1204.0f, f2 + 6.0f, this.p);
            this.p.setStrokeCap(Paint.Cap.BUTT);
            this.p.setStyle(Paint.Style.FILL);
        }

        String clip(String str, int i) {
            return str.length() <= i ? str : str.substring(0, i) + "…";
        }

        @Override // android.view.View
        public boolean onTouchEvent(MotionEvent motionEvent) {
            if (motionEvent.getAction() != 1) {
                return true;
            }
            float x = motionEvent.getX();
            float y = motionEvent.getY();
            if (in(x, y, 130.0f, 470.0f, 438.0f, 512.0f)) {
                if (GuardService.running) {
                    MainActivity.this.stopGuard();
                } else {
                    MainActivity.this.startGuard();
                }
                invalidate();
                return true;
            }
            if (in(x, y, 130.0f, 470.0f, 524.0f, 586.0f)) {
                MainActivity.this.sleepGuard();
                return true;
            }
            if (in(x, y, 130.0f, 290.0f, 598.0f, 654.0f)) {
                MainActivity.this.openPanel();
                return true;
            }
            if (in(x, y, 310.0f, 470.0f, 598.0f, 654.0f)) {
                MainActivity.this.testMail();
                return true;
            }
            if (in(x, y, 866.0f, 1026.0f, 40.0f, 92.0f)) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) MotorActivity.class));
                return true;
            }
            if (in(x, y, 1046.0f, 1204.0f, 40.0f, 92.0f)) {
                MainActivity.this.moveTaskToBack(true);
                return true;
            }
            if (in(x, y, 650.0f, 1240.0f, 614.0f, 654.0f)) {
                MainActivity.this.openGallery();
                return true;
            }
            if (!in(x, y, 650.0f, 1240.0f, 116.0f, 448.0f)) {
                return true;
            }
            MainActivity.this.openGallery();
            return true;
        }

        boolean in(float f, float f2, float f3, float f4, float f5, float f6) {
            return f >= f3 && f <= f4 && f2 >= f5 && f2 <= f6;
        }
    }

    int countFiles() {
        try {
            String[] list = new File("/sdcard/HomeGuard").list();
            if (list == null) {
                return 0;
            }
            return list.length;
        } catch (Throwable th) {
            return 0;
        }
    }

    void buildPanel() {
        this.panel = new LinearLayout(this);
        this.panel.setOrientation(1);
        this.panel.setBackgroundColor(BG_DARK);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setPadding(48, 28, 48, 10);
        TextView textView = new TextView(this);
        textView.setText("看家设置");
        textView.setTextColor(TXT);
        textView.setTextSize(24.0f);
        textView.setTypeface(Typeface.create("sans-serif-medium", 0));
        linearLayout.addView(textView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button = new Button(this);
        button.setText("返回");
        button.setTextColor(TXT2);
        button.setBackgroundColor(-15064269);
        button.setOnClickListener(new View.OnClickListener() { // from class: com.lm.guard.MainActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.panel.setVisibility(8);
            }
        });
        linearLayout.addView(button);
        this.panel.addView(linearLayout);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(48, 8, 48, 40);
        scrollView.addView(linearLayout2);
        this.eHost = field(linearLayout2, "SMTP 服务器", this.cfg.host, false);
        this.ePort = field(linearLayout2, "端口（465 加密 / 587 STARTTLS / 25 明文）", String.valueOf(this.cfg.port), true);
        this.cbSsl = new CheckBox(this);
        this.cbSsl.setText("使用 SSL 直连（QQ/163 常用 465，勾上）");
        this.cbSsl.setTextColor(TXT2);
        this.cbSsl.setChecked(this.cfg.ssl);
        linearLayout2.addView(this.cbSsl);
        this.eUser = field(linearLayout2, "发件邮箱（例：xxx@qq.com）", this.cfg.user, false);
        this.ePass = field(linearLayout2, "授权码（不是登录密码）", this.cfg.pass, true);
        this.eTo = field(linearLayout2, "收件邮箱（可填同一个）", this.cfg.to, false);
        this.eCool = field(linearLayout2, "报警冷却秒数（同一场景不重复发）", String.valueOf(this.cfg.cooldownSec), true);
        this.eConf = field(linearLayout2, "灵敏度 0.1~0.9（越小越灵敏，默认 0.3）", String.valueOf(this.cfg.minConfidence), false);
        this.cbWake = new CheckBox(this);
        this.cbWake.setText("亮屏时自动回到看家页面（值守中才生效）");
        this.cbWake.setTextColor(TXT2);
        this.cbWake.setChecked(this.cfg.autoShowOnWake);
        linearLayout2.addView(this.cbWake);
        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(0);
        linearLayout3.setPadding(0, 24, 0, 0);
        Button button2 = new Button(this);
        button2.setText("保存");
        button2.setTextColor(-1);
        button2.setBackgroundColor(BLUE);
        button2.setOnClickListener(new View.OnClickListener() { // from class: com.lm.guard.MainActivity.4
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.savePanel();
            }
        });
        linearLayout3.addView(button2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button3 = new Button(this);
        button3.setText("保存并发测试邮件");
        button3.setTextColor(TXT);
        button3.setBackgroundColor(-14536372);
        button3.setOnClickListener(new View.OnClickListener() { // from class: com.lm.guard.MainActivity.5
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.savePanel();
                MainActivity.this.testMail();
            }
        });
        linearLayout3.addView(button3, new LinearLayout.LayoutParams(0, -2, 2.0f));
        linearLayout2.addView(linearLayout3);
        TextView textView2 = new TextView(this);
        textView2.setText("\n怎么拿授权码：\nQQ 邮箱 → 设置 → 账户 → 开启 SMTP 服务 → 生成授权码（16 位）\n163 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 → 设置客户端授权密码\n\n常见服务器：\n  smtp.qq.com  465  SSL\n  smtp.163.com 465  SSL\n  smtp.gmail.com 465 SSL\n");
        textView2.setTextColor(TXT3);
        textView2.setTextSize(14.0f);
        linearLayout2.addView(textView2);
        this.panel.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
    }

    EditText field(LinearLayout linearLayout, String str, String str2, boolean z) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextColor(TXT3);
        textView.setTextSize(14.0f);
        textView.setPadding(0, 14, 0, 4);
        linearLayout.addView(textView);
        EditText editText = new EditText(this);
        editText.setText(str2);
        editText.setTextColor(TXT);
        editText.setTextSize(16.0f);
        editText.setSingleLine(true);
        editText.setBackgroundColor(-15261650);
        editText.setPadding(22, 12, 22, 12);
        if (z) {
            editText.setInputType(2);
        }
        linearLayout.addView(editText, new LinearLayout.LayoutParams(-1, -2));
        return editText;
    }

    void openPanel() {
        this.eHost.setText(this.cfg.host);
        this.ePort.setText(String.valueOf(this.cfg.port));
        this.cbSsl.setChecked(this.cfg.ssl);
        this.eUser.setText(this.cfg.user);
        this.ePass.setText(this.cfg.pass);
        this.eTo.setText(this.cfg.to);
        this.eCool.setText(String.valueOf(this.cfg.cooldownSec));
        this.eConf.setText(String.valueOf(this.cfg.minConfidence));
        this.cbWake.setChecked(this.cfg.autoShowOnWake);
        this.panel.setVisibility(0);
    }

    void savePanel() {
        this.cfg.host = this.eHost.getText().toString().trim();
        try {
            this.cfg.port = Integer.parseInt(this.ePort.getText().toString().trim());
        } catch (Throwable th) {
        }
        this.cfg.ssl = this.cbSsl.isChecked();
        this.cfg.user = this.eUser.getText().toString().trim();
        this.cfg.pass = this.ePass.getText().toString().trim();
        this.cfg.to = this.eTo.getText().toString().trim();
        try {
            this.cfg.cooldownSec = Integer.parseInt(this.eCool.getText().toString().trim());
        } catch (Throwable th2) {
        }
        try {
            this.cfg.minConfidence = Float.parseFloat(this.eConf.getText().toString().trim());
        } catch (Throwable th3) {
        }
        this.cfg.autoShowOnWake = this.cbWake.isChecked();
        if (this.cfg.to.length() == 0) {
            this.cfg.to = this.cfg.user;
        }
        this.cfg.save(this);
        Toast.makeText(this, "已保存", 0).show();
        this.view.invalidate();
    }

    void openGallery() {
        try {
            startActivity(new Intent(this, (Class<?>) GalleryActivity.class));
        } catch (Throwable th) {
            Toast.makeText(this, "打不开相册：" + th, 1).show();
        }
    }

    void testMail() {
        final Prefs load = Prefs.load(this);
        if (!load.mailReady()) {
            Toast.makeText(this, "邮箱还没填完", 1).show();
        } else {
            Toast.makeText(this, "正在发送测试邮件…", 0).show();
            new Thread(new Runnable() { // from class: com.lm.guard.MainActivity.6
                @Override // java.lang.Runnable
                public void run() {
                    final Mail.Result send = Mail.send(load, "【看家】测试邮件", "这是一封来自 T70L 看家的测试邮件。\n\n收到它，就说明抓拍照片能正常发到你邮箱了。\n", null, null);
                    GuardService.lastEvent = send.ok ? "测试邮件已发出，请查收" : "测试失败：" + send.msg;
                    Log.i("GUARD", "TESTMAIL ok=" + send.ok + " msg=" + send.msg);
                    MainActivity.this.runOnUiThread(new Runnable() { // from class: com.lm.guard.MainActivity.6.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(MainActivity.this, send.ok ? "发送成功，去邮箱看看" : "发送失败：" + send.msg, 1).show();
                            MainActivity.this.view.invalidate();
                        }
                    });
                }
            }).start();
        }
    }

    /** 预览窗还没有画面时的提示语：把"卡在哪一步"直接写出来，别让人干等。 */
    void updateHint() {
        if (this.pvHint == null || this.pvHint.getVisibility() != 0) {
            return;
        }
        String want;
        if (!GuardService.running) {
            want = "暂无画面\n点「开始看家」后这里显示实时画面";
        } else if (GuardService.camW > 0) {
            want = "画面马上来…\n摄像头已就位，正在出第一帧";
        } else {
            want = "正在接管摄像头…\n原厂 App 正抱着相机，正在让它让出";
        }
        if (!want.contentEquals(this.pvHint.getText())) {
            this.pvHint.setText(want);
        }
    }

    void loadLatest() {
        try {
            File[] listFiles = new File("/sdcard/HomeGuard").listFiles();
            if (listFiles != null && listFiles.length != 0) {
                File file = listFiles[0];
                for (File file2 : listFiles) {
                    if (file2.lastModified() > file.lastModified()) {
                        file = file2;
                    }
                }
                long lastModified = file.lastModified();
                if (lastModified == this.lastShotStamp) {
                    return;
                }
                this.lastShotStamp = lastModified;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap decodeFile = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (decodeFile != null) {
                    this.shotView.setImageBitmap(decodeFile);
                    if (this.pvHint != null) {
                        this.pvHint.setVisibility(8);
                        return;
                    }
                    return;
                }
                return;
            }
            if (this.pvHint != null) {
                this.pvHint.setVisibility(0);
            }
        } catch (Throwable th) {
        }
    }

    /**
     * 实时预览画布：只做一件事——把服务送来的低清位图铺满窗口。
     * 自己 onDraw 直接画，不走 ImageView，不 new Drawable，不触发 layout。
     */
    class PreviewView extends View {
        Bitmap bm;
        long stamp;
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Rect dst = new Rect();

        PreviewView(Context context) {
            super(context);
            this.p.setFilterBitmap(true);   // 放大时双线性，低清图也不至于满屏锯齿
            this.p.setDither(true);
        }

        void setFrame(Bitmap bitmap, long stamp) {
            this.bm = bitmap;
            this.stamp = stamp;
            invalidate();
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            Bitmap b = this.bm;
            if (b == null || b.isRecycled()) {
                return;
            }
            int vw = getWidth();
            int vh = getHeight();
            int bw = b.getWidth();
            int bh = b.getHeight();
            if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
                return;
            }
            // CENTER_CROP：铺满、不拉伸变形、多的裁掉
            if (vw * bh >= vh * bw) {
                int dh = (int) ((long) vw * bh / bw);
                int top = (vh - dh) / 2;
                this.dst.set(0, top, vw, top + dh);
            } else {
                int dw = (int) ((long) vh * bw / bh);
                int left = (vw - dw) / 2;
                this.dst.set(left, 0, left + dw, vh);
            }
            canvas.save();
            canvas.clipRect(0, 0, vw, vh);
            canvas.drawBitmap(b, null, this.dst, this.p);
            canvas.restore();
            // 端到端绘制计数：服务和它一比，就知道是"相机慢"还是"界面画不出来"。
            GuardService.drawCnt++;
            if (this.stamp > 0) {
                GuardService.previewLag = (int) (System.currentTimeMillis() - this.stamp);
            }
        }
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        // ★ 2026-11-08：界面在前台时才挂实时预览出口。
        //   服务每帧都会看这个 sink，为 null 就直接返回 —— 息屏/切走时预览零开销，
        //   人脸检测和抓拍完全不受影响。
        //
        //   ★★ 关键：合并丢帧（latest-wins）。
        //   以前每来一帧就 runOnUiThread 排一个任务，UI 线程一旦忙不过来，
        //   队列就越排越长 → 画面延迟累积、一顿一顿，而且还在画早就过期的帧。
        //   现在同一时刻只允许一帧"在路上"，新的直接覆盖旧的，UI 永远画最新的。
        GuardService.previewSink = new GuardService.PreviewSink() {
            @Override // com.lm.guard.GuardService.PreviewSink
            public void onPreview(final Bitmap bm, long stamp) {
                MainActivity.this.liveStamp = stamp;
                MainActivity.this.livePending = bm;
                if (MainActivity.this.liveBusy) {
                    GuardService.previewDrop++;
                    return;
                }
                MainActivity.this.liveBusy = true;
                MainActivity.this.ui.post(new Runnable() {
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            if (MainActivity.this.pvView != null) {
                                if (MainActivity.this.pvView.getVisibility() != 0) {
                                    MainActivity.this.pvView.setVisibility(0);
                                    MainActivity.this.shotView.setVisibility(8);
                                    MainActivity.this.pvHint.setVisibility(8);
                                }
                                MainActivity.this.pvView.setFrame(MainActivity.this.livePending,
                                        MainActivity.this.liveStamp);
                            }
                        } finally {
                            MainActivity.this.liveBusy = false;
                        }
                    }
                });
            }
        };
        this.ui.post(this.refresh);
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        GuardService.previewSink = null;
        this.ui.removeCallbacks(this.refresh);
    }
}
