package com.lm.guard;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.hardware.Camera;
import android.media.FaceDetector;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.util.List;

/* loaded from: classes.dex */
public class Probe extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static final String TAG = "GUARD";
    Camera cam;
    HandlerThread ht;
    Handler hw;
    SurfaceView sv;
    TextView tv;
    final StringBuilder sb = new StringBuilder();
    volatile int frames = 0;
    volatile long lastFrameAt = 0;
    int detectW = 0;
    int detectH = 0;
    volatile boolean busy = false;
    int attempt = 0;
    volatile int prevW = 0;
    volatile int prevH = 0;
    int saveCount = 0;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        FrameLayout frameLayout = new FrameLayout(this);
        this.sv = new SurfaceView(this);
        frameLayout.addView(this.sv, new FrameLayout.LayoutParams(2, 2));
        this.tv = new TextView(this);
        this.tv.setTextColor(-1);
        this.tv.setTextSize(17.0f);
        this.tv.setBackgroundColor(-16777216);
        frameLayout.addView(this.tv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frameLayout);
        this.sv.getHolder().addCallback(this);
        this.ht = new HandlerThread("probe");
        this.ht.start();
        this.hw = new Handler(this.ht.getLooper());
    }

    void log(String str) {
        Log.i(TAG, str);
        this.sb.append(str).append('\n');
        runOnUiThread(new Runnable() { // from class: com.lm.guard.Probe.1
            @Override // java.lang.Runnable
            public void run() {
                Probe.this.tv.setText(Probe.this.sb.toString());
            }
        });
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        this.hw.postDelayed(new Runnable() { // from class: com.lm.guard.Probe.2
            @Override // java.lang.Runnable
            public void run() {
                Probe.this.tryOpen();
            }
        }, 300L);
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
    }

    void release() {
        try {
            if (this.cam != null) {
                this.cam.setPreviewCallback(null);
                this.cam.stopPreview();
                this.cam.release();
            }
        } catch (Throwable th) {
        }
        this.cam = null;
    }

    void tryOpen() {
        if (this.attempt >= 25) {
            log("give up after 25 attempts");
            return;
        }
        this.attempt++;
        release();
        try {
            long currentTimeMillis = System.currentTimeMillis();
            this.cam = Camera.open(0);
            long currentTimeMillis2 = System.currentTimeMillis() - currentTimeMillis;
            if (currentTimeMillis2 > 800) {
                log("attempt " + this.attempt + " slow open " + currentTimeMillis2 + "ms -> retry");
                this.hw.postDelayed(new Runnable() { // from class: com.lm.guard.Probe.3
                    @Override // java.lang.Runnable
                    public void run() {
                        Probe.this.tryOpen();
                    }
                }, 400L);
                return;
            }
            this.cam.setPreviewDisplay(this.sv.getHolder());
            this.cam.setPreviewCallback(this);
            this.frames = 0;
            this.lastFrameAt = 0L;
            this.cam.startPreview();
            log("attempt " + this.attempt + " open=" + currentTimeMillis2 + "ms startPreview OK");
            this.hw.postDelayed(new Runnable() { // from class: com.lm.guard.Probe.4
                @Override // java.lang.Runnable
                public void run() {
                    if (Probe.this.frames == 0) {
                        Probe.this.log("attempt " + Probe.this.attempt + " no frames in 3s -> retry");
                        Probe.this.hw.postDelayed(new Runnable() { // from class: com.lm.guard.Probe.4.1
                            @Override // java.lang.Runnable
                            public void run() {
                                Probe.this.tryOpen();
                            }
                        }, 300L);
                    } else {
                        Probe.this.log("attempt " + Probe.this.attempt + " GOT FRAMES");
                        Probe.this.measure();
                    }
                }
            }, 3000L);
        } catch (Throwable th) {
            String stackTraceString = Log.getStackTraceString(th);
            int indexOf = stackTraceString.indexOf("\n", stackTraceString.indexOf("\n") + 1);
            StringBuilder append = new StringBuilder().append("attempt ").append(this.attempt).append(" fail ").append(th).append(" @ ");
            if (indexOf > 0) {
                stackTraceString = stackTraceString.substring(0, indexOf).replace("\n", " | ");
            }
            log(append.append(stackTraceString).toString());
            log("  cam=" + (this.cam == null ? "null" : "obj") + " holder=" + (this.sv.getHolder() != null ? "ok" : "null") + " surfaceValid=" + (this.sv.getHolder().getSurface() != null && this.sv.getHolder().getSurface().isValid()));
            this.hw.postDelayed(new Runnable() { // from class: com.lm.guard.Probe.5
                @Override // java.lang.Runnable
                public void run() {
                    Probe.this.tryOpen();
                }
            }, 400L);
        }
    }

    void measure() {
        try {
            Camera.Parameters parameters = this.cam.getParameters();
            List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
            StringBuilder sb = new StringBuilder("sizes:");
            for (Camera.Size size : supportedPreviewSizes) {
                sb.append(' ').append(size.width).append('x').append(size.height);
            }
            log(sb.toString());
            Camera.Size previewSize = parameters.getPreviewSize();
            log("now " + previewSize.width + "x" + previewSize.height + " fps=" + parameters.getPreviewFrameRate());
            this.prevW = previewSize.width;
            this.prevH = previewSize.height;
            this.detectW = previewSize.width / 2;
            this.detectH = previewSize.height / 2;
            if ((this.detectW & 1) == 1) {
                this.detectW--;
            }
            if ((this.detectH & 1) == 1) {
                this.detectH--;
            }
            log("detect at " + this.detectW + "x" + this.detectH);
        } catch (Throwable th) {
            log("getParameters blocked! " + th);
        }
    }

    @Override // android.hardware.Camera.PreviewCallback
    public void onPreviewFrame(final byte[] bArr, Camera camera) {
        this.frames++;
        this.lastFrameAt = System.currentTimeMillis();
        if (this.prevW == 0 || this.busy || this.frames % 20 != 0) {
            return;
        }
        this.busy = true;
        final int i = this.prevW;
        final int i2 = this.prevH;
        this.hw.post(new Runnable() { // from class: com.lm.guard.Probe.6
            @Override // java.lang.Runnable
            public void run() {
                long currentTimeMillis = System.currentTimeMillis();
                try {
                    Bitmap nv21ToBmp = Probe.nv21ToBmp(bArr, i, i2, Probe.this.detectW, Probe.this.detectH);
                    long currentTimeMillis2 = System.currentTimeMillis();
                    FaceDetector.Face[] faceArr = new FaceDetector.Face[4];
                    int findFaces = new FaceDetector(Probe.this.detectW, Probe.this.detectH, 4).findFaces(nv21ToBmp, faceArr);
                    String str = "f=" + Probe.this.frames + " conv=" + (currentTimeMillis2 - currentTimeMillis) + " det=" + (System.currentTimeMillis() - currentTimeMillis2) + " faces=" + findFaces;
                    if (findFaces > 0) {
                        FaceDetector.Face face = faceArr[0];
                        PointF pointF = new PointF();
                        face.getMidPoint(pointF);
                        str = str + " eye=" + ((int) face.eyesDistance()) + " conf=" + face.confidence() + " mid=(" + ((int) pointF.x) + "," + ((int) pointF.y) + ")";
                    }
                    Probe.this.log(str);
                    if (findFaces > 0 && Probe.this.saveCount < 3) {
                        Probe.this.saveCount++;
                        FileOutputStream fileOutputStream = new FileOutputStream("/sdcard/guard_probe_" + Probe.this.saveCount + ".jpg");
                        nv21ToBmp.compress(Bitmap.CompressFormat.JPEG, 85, fileOutputStream);
                        fileOutputStream.close();
                    }
                } catch (Throwable th) {
                    Probe.this.log("det err " + th);
                }
                Probe.this.busy = false;
            }
        });
    }

    static Bitmap nv21ToBmp(byte[] bArr, int i, int i2, int i3, int i4) {
        int i5 = i / i3;
        if (i5 < 1) {
            i5 = 1;
        }
        int[] iArr = new int[i3 * i4];
        int i6 = i * i2;
        for (int i7 = 0; i7 < i4; i7++) {
            int i8 = i7 * i5;
            for (int i9 = 0; i9 < i3; i9++) {
                int i10 = i9 * i5;
                int i11 = 255;
                int i12 = bArr[(i8 * i) + i10] & 255;
                int i13 = ((i8 >> 1) * i) + i6 + (i10 & (-2));
                int i14 = (bArr[i13] & 255) - 128;
                int i15 = (bArr[i13 + 1] & 255) - 128;
                int i16 = ((91881 * i14) >> 16) + i12;
                int i17 = i12 - (((i15 * 22554) + (i14 * 46802)) >> 16);
                int i18 = i12 + ((i15 * 116130) >> 16);
                if (i16 < 0) {
                    i16 = 0;
                } else if (i16 > 255) {
                    i16 = 255;
                }
                if (i17 < 0) {
                    i17 = 0;
                } else if (i17 > 255) {
                    i17 = 255;
                }
                if (i18 < 0) {
                    i11 = 0;
                } else if (i18 <= 255) {
                    i11 = i18;
                }
                iArr[(i7 * i3) + i9] = ((i17 & 252) << 8) | ((i16 & 248) << 16) | (-16777216) | (i11 >> 3);
            }
        }
        Bitmap createBitmap = Bitmap.createBitmap(i3, i4, Bitmap.Config.RGB_565);
        createBitmap.setPixels(iArr, 0, i3, 0, 0, i3, i4);
        return createBitmap;
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        release();
        if (this.ht != null) {
            this.ht.quit();
        }
        super.onDestroy();
    }
}
