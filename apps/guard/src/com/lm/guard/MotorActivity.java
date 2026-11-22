package com.lm.guard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/* loaded from: classes.dex */
public class MotorActivity extends Activity {
    static final String BASE = "/sys/devices/platform/drvtc78s600fng.2/";

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(1152);
        setContentView(new Panel(this));
    }

    static class Panel extends View {
        static final int ACCENT = -13668374;
        static final int ACCENT_HI = -7358725;
        static final int BG_BOTTOM = -15591388;
        static final int BG_TOP = -16052458;
        static final int BORDER = -14800585;
        static final int CARD_BOT = -15854306;
        static final int CARD_TOP = -15525082;
        static final float CR = 84.0f;
        static final int DANGER = -1551012;
        static final float DCX = 316.0f;
        static final float DCY = 452.0f;
        static final float DR = 172.0f;
        static final float DSW = 76.0f;
        static final int TXT_HI = -1445382;
        static final int TXT_LOW = -10588026;
        static final int TXT_MID = -6442810;
        final Runnable breath;
        final Handler h;
        final List<Hit> hits;
        final SimpleDateFormat hms;
        String holdAct;
        final Runnable holdRun;
        String lastAct;
        final List<String> logs;
        final Paint pArc;
        final Paint pFill;
        final Paint pLine;
        int pps;
        String pressedAct;
        float pulse;
        String rawCmd;
        String stCmd;
        String stPos;
        String stPps;
        String stStep;
        int step;
        final Runnable tick;
        final Path tmpP;
        final RectF tmpR;
        final Paint tp;

        Panel(Context context) {
            super(context);
            this.pFill = new Paint(1);
            this.pLine = new Paint(1);
            this.pArc = new Paint(1);
            this.tp = new Paint(1);
            this.hms = new SimpleDateFormat("HH:mm:ss");
            this.hits = new ArrayList();
            this.logs = new ArrayList();
            this.tmpR = new RectF();
            this.tmpP = new Path();
            this.step = 50;
            this.pps = 1000;
            this.lastAct = "待命";
            this.stCmd = "—";
            this.stPos = "—";
            this.stStep = "—";
            this.stPps = "—";
            this.rawCmd = "stop";
            this.holdAct = null;
            this.pressedAct = null;
            this.pulse = 0.0f;
            this.h = new Handler();
            this.tick = new Runnable() { // from class: com.lm.guard.MotorActivity.Panel.1
                @Override // java.lang.Runnable
                public void run() {
                    Panel.this.refresh();
                    Panel.this.h.postDelayed(this, 800L);
                }
            };
            this.breath = new Runnable() { // from class: com.lm.guard.MotorActivity.Panel.2
                @Override // java.lang.Runnable
                public void run() {
                    Panel.this.pulse += 0.06f;
                    if (Panel.this.pulse > 1.0f) {
                        Panel.this.pulse = 0.0f;
                    }
                    Panel.this.invalidate();
                    Panel.this.h.postDelayed(this, 60L);
                }
            };
            this.holdRun = new Runnable() { // from class: com.lm.guard.MotorActivity.Panel.3
                @Override // java.lang.Runnable
                public void run() {
                    if (Panel.this.holdAct == null) {
                        return;
                    }
                    Panel.this.write("pps", String.valueOf(Panel.this.pps));
                    Panel.this.write("step", String.valueOf(Panel.this.step));
                    Panel.this.write("cmd", Panel.this.holdAct);
                    Panel.this.h.postDelayed(this, 160L);
                }
            };
            buildUi();
        }

        void buildUi() {
            this.hits.clear();
            // 返回看家：原来这页没有返回入口，只能按电源键回桌面，很多人找不到路
            this.hits.add(new Hit(880.0f, 40.0f, 996.0f, 94.0f, "返回", "back"));
            this.hits.add(new Hit(1014.0f, 40.0f, 1120.0f, 94.0f, "复位", "reset"));
            this.hits.add(new Hit(1134.0f, 40.0f, 1240.0f, 94.0f, "自检", "auto"));
            int[] iArr = {10, 20, 50, 100, 200};
            float f = 636.0f;
            float f2 = 636.0f;
            for (int i = 0; i < 5; i++) {
                int i2 = iArr[i];
                this.hits.add(new Hit(f2, 200.0f, f2 + 104.0f, 268.0f, String.valueOf(i2), "step:" + i2));
                f2 += 116.0f;
            }
            int[] iArr2 = {1000, 3000, 6000, 16000};
            for (int i3 = 0; i3 < 4; i3++) {
                int i4 = iArr2[i3];
                this.hits.add(new Hit(f, 384.0f, f + 133.0f, DCY, String.valueOf(i4), "pps:" + i4));
                f += 145.0f;
            }
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            drawBackground(canvas);
            drawHeader(canvas);
            drawDialCard(canvas);
            drawStepCard(canvas);
            drawSpeedCard(canvas);
            drawStatusCard(canvas);
        }

        void drawBackground(Canvas canvas) {
            this.pFill.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, getHeight(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.pFill);
            this.pFill.setShader(null);
            this.pFill.setShader(new RadialGradient(140.0f, -60.0f, 520.0f, new int[]{575442431, 0}, (float[]) null, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 0.0f, getWidth(), 460.0f, this.pFill);
            this.pFill.setShader(null);
        }

        void drawHeader(Canvas canvas) {
            this.tp.setShader(null);
            this.tp.setFakeBoldText(true);
            this.tp.setColor(TXT_HI);
            this.tp.setTextSize(40.0f);
            canvas.drawText("云台调节", 56.0f, 74.0f, this.tp);
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(18.0f);
            this.tp.setColor(TXT_LOW);
            canvas.drawText("T70L  ·  摄像机云台调节", 56.0f, 102.0f, this.tp);
            for (Hit hit : this.hits) {
                if ("reset".equals(hit.act) || "auto".equals(hit.act) || "back".equals(hit.act)) {
                    ghost(canvas, hit);
                }
            }
        }

        void ghost(Canvas canvas, Hit hit) {
            boolean equals = hit.act.equals(this.pressedAct);
            this.tmpR.set(hit.l, hit.t, hit.r, hit.bm);
            this.pFill.setShader(null);
            this.pFill.setStyle(Paint.Style.FILL);
            this.pFill.setColor(equals ? -14997707 : 0);
            canvas.drawRoundRect(this.tmpR, 26.0f, 26.0f, this.pFill);
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(1.5f);
            this.pLine.setColor(equals ? ACCENT : BORDER);
            canvas.drawRoundRect(this.tmpR, 26.0f, 26.0f, this.pLine);
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(21.0f);
            this.tp.setColor(equals ? ACCENT_HI : TXT_MID);
            centerText(canvas, hit.label, (hit.l + hit.r) / 2.0f, ((hit.t + hit.bm) / 2.0f) + 8.0f);
        }

        void drawDialCard(Canvas canvas) {
            card(canvas, 48.0f, 132.0f, 584.0f, 772.0f);
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(17.0f);
            this.tp.setColor(TXT_LOW);
            centerText(canvas, "上方 上抬   ·   下方 下压   ·   中心 立即停止", DCX, 730.0f);
            this.pArc.setStyle(Paint.Style.STROKE);
            this.pArc.setStrokeWidth(DSW);
            this.pArc.setStrokeCap(Paint.Cap.ROUND);
            this.pArc.setShader(null);
            this.pArc.setColor(-15459289);
            dialArc(canvas, 206.0f, 128.0f, this.pArc);
            dialArc(canvas, 26.0f, 128.0f, this.pArc);
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(1.0f);
            this.pLine.setColor(352321535);
            canvas.drawCircle(DCX, DCY, 128.0f, this.pLine);
            boolean z = "up".equals(this.holdAct) || "up".equals(this.pressedAct);
            boolean z2 = "down".equals(this.holdAct) || "down".equals(this.pressedAct);
            dialSeg(canvas, 206.0f, 128.0f, z, ACCENT, -14987329);
            dialSeg(canvas, 26.0f, 128.0f, z2, ACCENT, -14987329);
            chevron(canvas, DCX, 280.0f, true, -1379585, 9.0f, 40.0f);
            chevron(canvas, DCX, 624.0f, false, -1379585, 9.0f, 40.0f);
            boolean equals = "stop".equals(this.pressedAct);
            this.pFill.setShader(new RadialGradient(DCX, 428.0f, 126.0f, equals ? -955278 : -14208189, equals ? -3719098 : -15195859, Shader.TileMode.CLAMP));
            canvas.drawCircle(DCX, DCY, CR, this.pFill);
            this.pFill.setShader(null);
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(1.5f);
            this.pLine.setColor(equals ? -13877 : -13286054);
            canvas.drawCircle(DCX, DCY, CR, this.pLine);
            this.pLine.setStrokeWidth(1.0f);
            this.pLine.setColor(234881023);
            canvas.drawCircle(DCX, DCY, 96.0f, this.pLine);
            this.tmpR.set(302.0f, 422.0f, 330.0f, 450.0f);
            this.pFill.setStyle(Paint.Style.FILL);
            this.pFill.setColor(-1);
            canvas.drawRoundRect(this.tmpR, 5.0f, 5.0f, this.pFill);
            this.tp.setFakeBoldText(true);
            this.tp.setTextSize(18.0f);
            this.tp.setColor(-2299658);
            centerText(canvas, "停止", DCX, 486.0f);
            this.tp.setFakeBoldText(false);
        }

        void dialArc(Canvas canvas, float f, float f2, Paint paint) {
            this.tmpR.set(144.0f, 280.0f, 488.0f, 624.0f);
            canvas.drawArc(this.tmpR, f, f2, false, paint);
        }

        void dialSeg(Canvas canvas, float f, float f2, boolean z, int i, int i2) {
            this.pArc.setStyle(Paint.Style.STROKE);
            this.pArc.setStrokeWidth(DSW);
            this.pArc.setStrokeCap(Paint.Cap.ROUND);
            if (z) {
                this.pArc.setShader(new LinearGradient(144.0f, 280.0f, 488.0f, 624.0f, i, i2, Shader.TileMode.CLAMP));
                this.pArc.setAlpha(255);
            } else {
                this.pArc.setShader(null);
                this.pArc.setColor(-14997964);
                this.pArc.setAlpha(255);
            }
            this.tmpR.set(144.0f, 280.0f, 488.0f, 624.0f);
            canvas.drawArc(this.tmpR, f, f2, false, this.pArc);
            this.pArc.setShader(null);
        }

        void chevron(Canvas canvas, float f, float f2, boolean z, int i, float f3, float f4) {
            this.tmpP.reset();
            if (z) {
                float f5 = f4 / 2.0f;
                float f6 = f2 + 10.0f;
                this.tmpP.moveTo(f - f5, f6);
                this.tmpP.lineTo(f, f2 - 10.0f);
                this.tmpP.lineTo(f + f5, f6);
            } else {
                float f7 = f4 / 2.0f;
                float f8 = f2 - 10.0f;
                this.tmpP.moveTo(f - f7, f8);
                this.tmpP.lineTo(f, f2 + 10.0f);
                this.tmpP.lineTo(f + f7, f8);
            }
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(f3);
            this.pLine.setStrokeCap(Paint.Cap.ROUND);
            this.pLine.setStrokeJoin(Paint.Join.ROUND);
            this.pLine.setColor(i);
            canvas.drawPath(this.tmpP, this.pLine);
        }

        void drawStepCard(Canvas canvas) {
            card(canvas, 608.0f, 132.0f, 1232.0f, 300.0f);
            label(canvas, "步数", "STEP", 636.0f, 176.0f);
            for (Hit hit : this.hits) {
                if (hit.act.startsWith("step:")) {
                    pill(canvas, hit, false, 26);
                }
            }
        }

        void drawSpeedCard(Canvas canvas) {
            card(canvas, 608.0f, DCX, 1232.0f, 484.0f);
            label(canvas, "速度", "Hz", 636.0f, 360.0f);
            for (Hit hit : this.hits) {
                if (hit.act.startsWith("pps:")) {
                    pill(canvas, hit, false, 26);
                }
            }
        }

        void drawStatusCard(Canvas canvas) {
            card(canvas, 608.0f, 500.0f, 1232.0f, 772.0f);
            label(canvas, "状态", "STATUS", 636.0f, 542.0f);
            boolean z = !"stop".equals(this.rawCmd);
            int i = z ? ACCENT : -13878708;
            float f = 5.0f;
            if (z) {
                double d = this.pulse;
                Double.isNaN(d);
                f = 5.0f + (((float) Math.abs(Math.sin(d * 3.141592653589793d))) * 2.0f);
            }
            this.pFill.setStyle(Paint.Style.FILL);
            this.pFill.setColor(i);
            canvas.drawCircle(1204.0f, 534.0f, f, this.pFill);
            field(canvas, "电机", this.stCmd, 636.0f, 588.0f, 622.0f);
            field(canvas, "限位", this.stPos, 780.0f, 588.0f, 622.0f);
            field(canvas, "步数", this.stStep, 924.0f, 588.0f, 622.0f);
            field(canvas, "速度", this.stPps + " Hz", 1068.0f, 588.0f, 622.0f);
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(1.0f);
            this.pLine.setColor(-15064014);
            canvas.drawLine(636.0f, 646.0f, 1204.0f, 646.0f, this.pLine);
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(TXT_MID);
            canvas.drawText("本次", 636.0f, 682.0f, this.tp);
            this.tp.setColor(-2759693);
            canvas.drawText(this.lastAct, 690.0f, 682.0f, this.tp);
            this.tp.setTextSize(16.0f);
            this.tp.setColor(TXT_LOW);
            int size = this.logs.size();
            int i2 = 716;
            if (size == 0) {
                canvas.drawText("暂无操作记录 · 按住方向盘上方或下方开始", 636.0f, 716, this.tp);
                return;
            }
            for (int max = Math.max(0, size - 2); max < size; max++) {
                canvas.drawText(this.logs.get(max), 636.0f, i2, this.tp);
                i2 += 26;
            }
        }

        void field(Canvas canvas, String str, String str2, float f, float f2, float f3) {
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(TXT_LOW);
            canvas.drawText(str, f, f2, this.tp);
            this.tp.setFakeBoldText(true);
            this.tp.setTextSize(25.0f);
            this.tp.setColor(TXT_HI);
            canvas.drawText(str2, f, f3, this.tp);
            this.tp.setFakeBoldText(false);
        }

        void label(Canvas canvas, String str, String str2, float f, float f2) {
            this.tp.setFakeBoldText(true);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(TXT_MID);
            canvas.drawText(str, f, f2, this.tp);
            float measureText = this.tp.measureText(str);
            this.tp.setFakeBoldText(false);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(TXT_LOW);
            canvas.drawText(str2, f + measureText + 10.0f, f2, this.tp);
        }

        void card(Canvas canvas, float f, float f2, float f3, float f4) {
            this.tmpR.set(f, f2, f3, f4);
            this.pFill.setStyle(Paint.Style.FILL);
            this.pFill.setShader(new LinearGradient(0.0f, f2, 0.0f, f4, CARD_TOP, CARD_BOT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(this.tmpR, 26.0f, 26.0f, this.pFill);
            this.pFill.setShader(null);
            this.pLine.setStyle(Paint.Style.STROKE);
            this.pLine.setStrokeWidth(1.0f);
            this.pLine.setColor(BORDER);
            canvas.drawRoundRect(this.tmpR, 26.0f, 26.0f, this.pLine);
        }

        void pill(Canvas canvas, Hit hit, boolean z, int i) {
            boolean equals = hit.act.startsWith("step:") ? hit.act.equals("step:" + this.step) : hit.act.equals("pps:" + this.pps);
            boolean equals2 = hit.act.equals(this.pressedAct);
            this.tmpR.set(hit.l, hit.t, hit.r, hit.bm);
            if (!equals) {
                this.pFill.setShader(null);
                this.pFill.setStyle(Paint.Style.FILL);
                this.pFill.setColor(equals2 ? -15063499 : -15722462);
                canvas.drawRoundRect(this.tmpR, 22.0f, 22.0f, this.pFill);
                this.pLine.setStyle(Paint.Style.STROKE);
                this.pLine.setStrokeWidth(1.0f);
                this.pLine.setColor(equals2 ? -13874578 : -14998220);
                canvas.drawRoundRect(this.tmpR, 22.0f, 22.0f, this.pLine);
            } else {
                this.pFill.setShader(new LinearGradient(0.0f, hit.t, 0.0f, hit.bm, -14861725, -15455934, Shader.TileMode.CLAMP));
                this.pFill.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(this.tmpR, 22.0f, 22.0f, this.pFill);
                this.pFill.setShader(null);
                this.pLine.setStyle(Paint.Style.STROKE);
                this.pLine.setStrokeWidth(2.0f);
                this.pLine.setColor(ACCENT);
                canvas.drawRoundRect(this.tmpR, 22.0f, 22.0f, this.pLine);
            }
            this.tp.setFakeBoldText(equals);
            float f = i;
            this.tp.setTextSize(f);
            this.tp.setColor(equals ? ACCENT_HI : TXT_MID);
            centerText(canvas, hit.label, (hit.l + hit.r) / 2.0f, ((hit.t + hit.bm) / 2.0f) + (f * 0.36f));
            this.tp.setFakeBoldText(false);
        }

        void centerText(Canvas canvas, String str, float f, float f2) {
            canvas.drawText(str, f - (this.tp.measureText(str) / 2.0f), f2, this.tp);
        }

        @Override // android.view.View
        public boolean onTouchEvent(MotionEvent motionEvent) {
            float x = motionEvent.getX();
            float y = motionEvent.getY();
            if (motionEvent.getAction() == 0) {
                String hitTest = hitTest(x, y);
                this.pressedAct = hitTest;
                if ("up".equals(hitTest) || "down".equals(hitTest)) {
                    this.holdAct = hitTest;
                    this.lastAct = "up".equals(hitTest) ? "上抬中…" : "下压中…";
                    doAct(hitTest);
                    this.h.postDelayed(this.holdRun, 160L);
                } else if (hitTest != null) {
                    doAct(hitTest);
                }
                invalidate();
            } else if (motionEvent.getAction() == 2) {
                String hitTest2 = hitTest(x, y);
                if (same(hitTest2, this.pressedAct) || "up".equals(this.pressedAct) || "down".equals(this.pressedAct)) {
                    return true;
                }
                this.pressedAct = hitTest2;
                invalidate();
            } else if (motionEvent.getAction() == 1 || motionEvent.getAction() == 3) {
                if (this.holdAct != null) {
                    this.holdAct = null;
                    this.h.removeCallbacks(this.holdRun);
                    write("cmd", "stop");
                    this.lastAct = "松手停止";
                    say("松手 -> stop");
                }
                this.pressedAct = null;
                invalidate();
            }
            return true;
        }

        boolean same(String str, String str2) {
            return str == null ? str2 == null : str.equals(str2);
        }

        String hitTest(float f, float f2) {
            float f3 = f - DCX;
            float f4 = f2 - DCY;
            float sqrt = (float) Math.sqrt((f3 * f3) + (f4 * f4));
            if (sqrt <= CR) {
                return "stop";
            }
            if (sqrt >= 122.0f && sqrt <= 222.0f) {
                double degrees = Math.toDegrees(Math.atan2(f4, f3));
                if (degrees < 0.0d) {
                    degrees += 360.0d;
                }
                if (degrees >= 190.0d && degrees <= 350.0d) {
                    return "up";
                }
                if (degrees >= 10.0d && degrees <= 170.0d) {
                    return "down";
                }
            }
            for (Hit hit : this.hits) {
                if (f >= hit.l && f <= hit.r && f2 >= hit.t && f2 <= hit.bm) {
                    return hit.act;
                }
            }
            return null;
        }

        void doAct(String str) {
            if (str.startsWith("step:")) {
                this.step = Integer.parseInt(str.substring(5));
                say("步数设为 " + this.step);
                return;
            }
            if (str.startsWith("pps:")) {
                this.pps = Integer.parseInt(str.substring(4));
                say("速度设为 " + this.pps + " Hz");
                return;
            }
            if ("stop".equals(str)) {
                say("停止 -> " + write("cmd", "stop"));
                this.lastAct = "停止";
                return;
            }
            if ("back".equals(str)) {
                // 退回看家首页
                try {
                    if (getContext() instanceof android.app.Activity) {
                        ((android.app.Activity) getContext()).finish();
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            if ("reset".equals(str)) {
                say("复位 -> " + write("cmd", "reset"));
                this.lastAct = "复位脉冲";
            } else {
                if ("auto".equals(str)) {
                    say("自检 -> " + write("cmd", "auto"));
                    this.lastAct = "自检（200步 / 3000Hz）";
                    return;
                }
                String write = write("pps", String.valueOf(this.pps));
                String write2 = write("step", String.valueOf(this.step));
                String write3 = write("cmd", str);
                this.lastAct = ("up".equals(str) ? "上抬 " : "下压 ") + this.step + " 步 · " + this.pps + "Hz";
                say(this.lastAct + "   pps:" + write + " step:" + write2 + " cmd:" + write3);
            }
        }

        void say(String str) {
            this.logs.add(this.hms.format(new Date()) + "   " + str);
            while (this.logs.size() > 20) {
                this.logs.remove(0);
            }
        }

        String write(String str, String str2) {
            try {
                RandomAccessFile randomAccessFile = new RandomAccessFile(MotorActivity.BASE + str, "rw");
                randomAccessFile.write(str2.getBytes("UTF-8"));
                randomAccessFile.close();
                return "ok";
            } catch (Throwable th) {
                try {
                    return Runtime.getRuntime().exec(new String[]{"su", "-c", new StringBuilder().append("echo ").append(str2).append(" > ").append(MotorActivity.BASE).append(str).toString()}).waitFor() == 0 ? "ok(su)" : "拒绝:" + th.getClass().getSimpleName();
                } catch (Throwable th2) {
                    return "拒绝:" + th.getClass().getSimpleName();
                }
            }
        }

        String read(String str) {
            try {
                RandomAccessFile randomAccessFile = new RandomAccessFile(MotorActivity.BASE + str, "r");
                byte[] bArr = new byte[512];
                int read = randomAccessFile.read(bArr);
                randomAccessFile.close();
                return read <= 0 ? "" : new String(bArr, 0, read, "UTF-8").replace("\n", " ").replaceAll("\\s+", " ").trim();
            } catch (Throwable th) {
                return "(" + th.getClass().getSimpleName() + ")";
            }
        }

        void refresh() {
            String read = read("cmd");
            int indexOf = read.indexOf("current motor_cmd is");
            if (indexOf >= 0) {
                read = read.substring(indexOf + "current motor_cmd is".length()).trim();
            }
            this.rawCmd = read;
            this.stCmd = cn(read);
            this.stPos = cn(read("position"));
            this.stStep = read("step").replace("now step :", "").trim();
            this.stPps = read("pps").replace("now freq :", "").replace("Hz", "").trim();
            invalidate();
        }

        String cn(String str) {
            if (str == null) {
                return "—";
            }
            String trim = str.trim();
            return "stop".equals(trim) ? "已停止" : "up".equals(trim) ? "上抬中" : "down".equals(trim) ? "下压中" : "auto".equals(trim) ? "自检中" : "reset".equals(trim) ? "复位" : "Up".equals(trim) ? "上端" : "Down".equals(trim) ? "下端" : "Unknown".equals(trim) ? "中间" : trim.startsWith("(") ? "读取失败" : trim;
        }

        @Override // android.view.View
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refresh();
            this.h.post(this.tick);
            this.h.post(this.breath);
        }

        @Override // android.view.View
        protected void onDetachedFromWindow() {
            this.h.removeCallbacks(this.tick);
            this.h.removeCallbacks(this.breath);
            this.h.removeCallbacks(this.holdRun);
            super.onDetachedFromWindow();
        }
    }

    static class Hit {
        String act;
        float bm;
        float l;
        String label;
        float r;
        float t;

        Hit(float f, float f2, float f3, float f4, String str, String str2) {
            this.l = f;
            this.t = f2;
            this.r = f3;
            this.bm = f4;
            this.label = str;
            this.act = str2;
        }
    }
}
