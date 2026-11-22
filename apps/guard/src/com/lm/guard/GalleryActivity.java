package com.lm.guard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Scroller;
import android.widget.Toast;
import java.io.File;
import java.io.FilenameFilter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/* loaded from: classes.dex */
public class GalleryActivity extends Activity {
    static final int BG_DARK = -16052458;
    static final int BLUE = -13668374;
    static final int CARD_LINE = -14405564;
    static final float CH = 160.0f;
    static final int COLS = 4;
    static final float CW = 284.0f;
    static final float GAP = 16.0f;
    static final int GREEN = -13648015;
    static final float GRID_BOTTOM = 752.0f;
    static final float GRID_TOP = 190.0f;
    static final int RED = -1750963;
    static final float ROW_H = 176.0f;
    static final int TXT = -1445896;
    static final int TXT2 = -6640446;
    static final int TXT3 = -10588023;
    static final float X0 = 48.0f;
    LruCache<String, Bitmap> cache;
    GalleryView view;
    final ArrayList<Item> items = new ArrayList<>();
    final HashSet<String> sel = new HashSet<>();
    final Handler ui = new Handler();
    final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    static class Item {
        File f;
        boolean failed;
        boolean loading;
        long size;
        Bitmap thumb;
        long time;

        Item() {
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setFlags(1024, 1024);
        this.cache = new LruCache<String, Bitmap>(8388608) { // from class: com.lm.guard.GalleryActivity.1
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // android.util.LruCache
            public int sizeOf(String str, Bitmap bitmap) {
                if (bitmap == null) {
                    return 0;
                }
                return bitmap.getByteCount();
            }
        };
        this.view = new GalleryView(this);
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.addView(this.view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frameLayout);
        reload();
    }

    void reload() {
        this.items.clear();
        this.sel.clear();
        File[] listFiles = new File("/sdcard/HomeGuard").listFiles(new FilenameFilter() { // from class: com.lm.guard.GalleryActivity.2
            @Override // java.io.FilenameFilter
            public boolean accept(File file, String str) {
                return str.toLowerCase().endsWith(".jpg");
            }
        });
        if (listFiles != null) {
            Arrays.sort(listFiles, new Comparator<File>() { // from class: com.lm.guard.GalleryActivity.3
                @Override // java.util.Comparator
                public int compare(File file, File file2) {
                    return Long.compare(file2.lastModified(), file.lastModified());
                }
            });
            for (File file : listFiles) {
                Item item = new Item();
                item.f = file;
                item.size = file.length();
                item.time = file.lastModified();
                this.items.add(item);
            }
        }
        if (this.view != null) {
            this.view.offsetY = 0.0f;
            this.view.invalidate();
        }
    }

    long totalBytes() {
        Iterator<Item> it = this.items.iterator();
        long j = 0;
        while (it.hasNext()) {
            j += it.next().size;
        }
        return j;
    }

    static String human(long j) {
        if (j < 1024) {
            return j + " B";
        }
        if (j < 1048576) {
            Locale locale = Locale.US;
            double d = j;
            Double.isNaN(d);
            return String.format(locale, "%.0f KB", Double.valueOf(d / 1024.0d));
        }
        if (j < 1073741824) {
            Locale locale2 = Locale.US;
            double d2 = j;
            Double.isNaN(d2);
            return String.format(locale2, "%.1f MB", Double.valueOf(d2 / 1048576.0d));
        }
        Locale locale3 = Locale.US;
        double d3 = j;
        Double.isNaN(d3);
        return String.format(locale3, "%.2f GB", Double.valueOf(d3 / 1.073741824E9d));
    }

    void ensureThumb(final Item item) {
        if (item.thumb != null || item.loading || item.failed) {
            return;
        }
        final String name = item.f.getName();
        Bitmap bitmap = this.cache.get(name);
        if (bitmap != null) {
            item.thumb = bitmap;
        } else {
            item.loading = true;
            new Thread(new Runnable() { // from class: com.lm.guard.GalleryActivity.4
                @Override // java.lang.Runnable
                public void run() {
                    Bitmap tmpBmp2 = null;
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = GalleryActivity.COLS;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        tmpBmp2 = BitmapFactory.decodeFile(item.f.getAbsolutePath(), options);
                    } catch (Throwable th) {
                    }
                    final Bitmap bitmap2 = tmpBmp2;
                    GalleryActivity.this.ui.post(new Runnable() { // from class: com.lm.guard.GalleryActivity.4.1
                        @Override // java.lang.Runnable
                        public void run() {
                            item.loading = false;
                            if (bitmap2 == null) {
                                item.failed = true;
                            } else {
                                item.thumb = bitmap2;
                                GalleryActivity.this.cache.put(name, bitmap2);
                            }
                            if (GalleryActivity.this.view != null) {
                                GalleryActivity.this.view.invalidate();
                            }
                        }
                    });
                }
            }).start();
        }
    }

    int deleteSelected() {
        Iterator it = new ArrayList(this.sel).iterator();
        int i = 0;
        while (it.hasNext()) {
            String str = (String) it.next();
            try {
                if (new File("/sdcard/HomeGuard", str).delete()) {
                    i++;
                    this.cache.remove(str);
                }
            } catch (Throwable th) {
            }
        }
        GuardService.lastEvent = "已删除 " + i + " 张照片";
        reload();
        this.view.confirmVisible = false;
        Toast.makeText(this, "已删除 " + i + " 张", 0).show();
        return i;
    }

    class GalleryView extends View {
        static final int MODE_GRID = 0;
        static final int MODE_VIEW = 1;
        final Typeface bold;
        boolean confirmVisible;
        float downX;
        float downY;
        boolean dragging;
        Bitmap fullBmp;
        boolean fullFailed;
        boolean fullLoading;
        final Paint ip;
        float lastSwipeX;
        float lastTwoX;
        float lastTwoY;
        float lastX;
        float lastY;
        long longPressAt;
        boolean longPressFired;
        int longPressIdx;
        final Runnable longPressRunnable;
        float maxScale;
        float minScale;
        int mode;
        boolean moved;
        final Matrix mtx;
        float offsetY;
        final Paint p;
        final Path path;
        final PointF pinchMid;
        float pinchStartDist;
        float pinchStartScale;
        final RectF r;
        float scale;
        final Scroller scroller;
        final Paint tp;
        boolean twoFinger;
        float tx;
        float ty;
        float vDragX;
        float vDragY;
        float viewDownX;
        float viewDownY;
        int viewIndex;
        boolean viewMoved;
        VelocityTracker vt;

        GalleryView(Context context) {
            super(context);
            this.p = new Paint(MODE_VIEW);
            this.tp = new Paint(MODE_VIEW);
            this.ip = new Paint(3);
            this.r = new RectF();
            this.path = new Path();
            this.bold = Typeface.create("sans-serif-medium", MODE_GRID);
            this.offsetY = 0.0f;
            this.downX = 0.0f;
            this.downY = 0.0f;
            this.lastY = 0.0f;
            this.dragging = false;
            this.moved = false;
            this.confirmVisible = false;
            this.mode = MODE_GRID;
            this.viewIndex = -1;
            this.fullLoading = false;
            this.fullFailed = false;
            this.scale = 1.0f;
            this.minScale = 1.0f;
            this.maxScale = 4.0f;
            this.tx = 0.0f;
            this.ty = 0.0f;
            this.twoFinger = false;
            this.pinchStartDist = 0.0f;
            this.pinchStartScale = 1.0f;
            this.pinchMid = new PointF();
            this.mtx = new Matrix();
            this.viewDownX = 0.0f;
            this.viewDownY = 0.0f;
            this.viewMoved = false;
            this.lastTwoX = 0.0f;
            this.lastTwoY = 0.0f;
            this.vDragX = 0.0f;
            this.vDragY = 0.0f;
            this.lastSwipeX = 0.0f;
            this.longPressAt = 0L;
            this.longPressFired = false;
            this.longPressIdx = -1;
            this.longPressRunnable = new Runnable() { // from class: com.lm.guard.GalleryActivity.GalleryView.1
                @Override // java.lang.Runnable
                public void run() {
                    if (!GalleryView.this.moved && GalleryView.this.mode == 0 && GalleryView.this.longPressIdx >= 0) {
                        GalleryView.this.longPressFired = true;
                        GalleryView.this.openView(GalleryView.this.longPressIdx);
                    }
                }
            };
            this.lastX = 0.0f;
            this.scroller = new Scroller(context);
            this.tp.setTypeface(this.bold);
        }

        float maxScroll() {
            double size = GalleryActivity.this.items.size();
            Double.isNaN(size);
            return Math.max(0.0f, ((((float) Math.ceil(size / 4.0d)) * GalleryActivity.ROW_H) - GalleryActivity.GAP) - 562.0f);
        }

        void clamp() {
            float maxScroll = maxScroll();
            if (this.offsetY < 0.0f) {
                this.offsetY = 0.0f;
            }
            if (this.offsetY > maxScroll) {
                this.offsetY = maxScroll;
            }
        }

        RectF cardRect(int i) {
            int i2 = i % GalleryActivity.COLS;
            int i3 = i / GalleryActivity.COLS;
            float f = (i2 * 300.0f) + GalleryActivity.X0;
            float f2 = ((i3 * GalleryActivity.ROW_H) + GalleryActivity.GRID_TOP) - this.offsetY;
            this.r.set(f, f2, GalleryActivity.CW + f, GalleryActivity.CH + f2);
            return this.r;
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            if (this.mode == MODE_VIEW) {
                drawViewer(canvas);
                return;
            }
            this.p.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, getHeight(), -15591388, GalleryActivity.BG_DARK, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.p);
            this.p.setShader(null);
            drawHeader(canvas);
            drawToolbar(canvas);
            drawGrid(canvas);
            drawEdgeFade(canvas);
            drawScrollBar(canvas);
            if (this.confirmVisible) {
                drawConfirm(canvas);
            }
            for (int i = MODE_GRID; i < GalleryActivity.this.items.size(); i += MODE_VIEW) {
                RectF cardRect = cardRect(i);
                if (cardRect.bottom > 150.0f && cardRect.top < 792.0f) {
                    GalleryActivity.this.ensureThumb(GalleryActivity.this.items.get(i));
                }
            }
            this.tp.setTypeface(this.bold);
        }

        void drawHeader(Canvas canvas) {
            this.tp.setTypeface(this.bold);
            this.tp.setTextSize(30.0f);
            this.tp.setColor(GalleryActivity.TXT);
            canvas.drawText("本地照片", GalleryActivity.X0, 68.0f, this.tp);
            float measureText = this.tp.measureText("本地照片");
            this.tp.setTextSize(15.0f);
            this.tp.setColor(GalleryActivity.TXT3);
            canvas.drawText("共 " + GalleryActivity.this.items.size() + " 张 · 占用 " + GalleryActivity.human(GalleryActivity.this.totalBytes()), measureText + GalleryActivity.X0 + 18.0f, 68.0f, this.tp);
            this.r.set(1090.0f, 40.0f, 1200.0f, 92.0f);
            outline(canvas, this.r, GalleryActivity.CARD_LINE, 22.0f);
            this.tp.setTextSize(17.0f);
            this.tp.setColor(GalleryActivity.TXT2);
            canvas.drawText("返回", 1145.0f - (this.tp.measureText("返回") / 2.0f), 72.0f, this.tp);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.0f);
            this.p.setColor(-15064014);
            canvas.drawLine(GalleryActivity.X0, 108.0f, 1232.0f, 108.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
        }

        void drawToolbar(Canvas canvas) {
            boolean z = !GalleryActivity.this.items.isEmpty();
            int size = GalleryActivity.this.sel.size();
            btn(canvas, GalleryActivity.X0, 122.0f, 168.0f, 172.0f, (size <= 0 || size != GalleryActivity.this.items.size()) ? "全选" : "取消全选", z, false);
            btn(canvas, 180.0f, 122.0f, 300.0f, 172.0f, "反选", z && size < GalleryActivity.this.items.size(), false);
            btn(canvas, 312.0f, 122.0f, 460.0f, 172.0f, size > 0 ? "删除 " + size + " 张" : "删除选中", size > 0, true);
            this.tp.setTextSize(GalleryActivity.GAP);
            this.tp.setColor(size > 0 ? GalleryActivity.GREEN : GalleryActivity.TXT3);
            String str = size > 0 ? "已选 " + size + " 张 · " + GalleryActivity.human(selectedBytes()) : "点照片选择，可批量删除";
            canvas.drawText(str, 1232.0f - this.tp.measureText(str), 155.0f, this.tp);
        }

        long selectedBytes() {
            Iterator<Item> it = GalleryActivity.this.items.iterator();
            long j = 0;
            while (it.hasNext()) {
                Item next = it.next();
                if (GalleryActivity.this.sel.contains(next.f.getName())) {
                    j += next.size;
                }
            }
            return j;
        }

        void btn(Canvas canvas, float f, float f2, float f3, float f4, String str, boolean z, boolean z2) {
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor(!z ? -15459546 : z2 ? -12968670 : -15064269);
            canvas.drawRoundRect(this.r, 18.0f, 18.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(z ? z2 ? -9752524 : GalleryActivity.CARD_LINE : -15064269);
            canvas.drawRoundRect(this.r, 18.0f, 18.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(17.0f);
            this.tp.setColor(!z ? -11641742 : z2 ? -1013106 : GalleryActivity.TXT2);
            canvas.drawText(str, ((f + f3) / 2.0f) - (this.tp.measureText(str) / 2.0f), ((f2 + f4) / 2.0f) + 6.0f, this.tp);
        }

        void drawGrid(Canvas canvas) {
            canvas.save();
            canvas.clipRect(0.0f, 186.0f, getWidth(), 756.0f);
            if (GalleryActivity.this.items.isEmpty()) {
                this.tp.setTextSize(20.0f);
                this.tp.setColor(-12694432);
                canvas.drawText("还没有照片", (getWidth() - this.tp.measureText("还没有照片")) / 2.0f, 465.0f, this.tp);
                this.tp.setTextSize(15.0f);
                this.tp.setColor(-13747638);
                canvas.drawText("检测到人脸时会自动抓拍，存在这里", (getWidth() - this.tp.measureText("检测到人脸时会自动抓拍，存在这里")) / 2.0f, 501.0f, this.tp);
            }
            for (int i = MODE_GRID; i < GalleryActivity.this.items.size(); i += MODE_VIEW) {
                RectF cardRect = cardRect(i);
                if (cardRect.bottom >= 182.0f && cardRect.top <= 760.0f) {
                    drawCard(canvas, cardRect, GalleryActivity.this.items.get(i));
                }
            }
            canvas.restore();
        }

        void drawCard(Canvas canvas, RectF rectF, Item item) {
            boolean contains = GalleryActivity.this.sel.contains(item.f.getName());
            canvas.save();
            this.path.reset();
            this.path.addRoundRect(rectF, 14.0f, 14.0f, Path.Direction.CW);
            canvas.clipPath(this.path);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor(-15590874);
            canvas.drawRect(rectF, this.p);
            if (item.thumb != null) {
                Rect rect = new Rect(MODE_GRID, MODE_GRID, item.thumb.getWidth(), item.thumb.getHeight());
                Rect rect2 = new Rect((int) rectF.left, (int) rectF.top, (int) rectF.right, (int) rectF.bottom);
                this.ip.setAlpha(255);
                canvas.drawBitmap(item.thumb, rect, rect2, this.ip);
            } else {
                this.tp.setTextSize(14.0f);
                this.tp.setColor(-13747120);
                String str = item.failed ? "无法读取" : "加载中";
                canvas.drawText(str, rectF.centerX() - (this.tp.measureText(str) / 2.0f), rectF.centerY() + 5.0f, this.tp);
            }
            this.p.setShader(new LinearGradient(0.0f, rectF.bottom - 44.0f, 0.0f, rectF.bottom, MODE_GRID, -872415232, Shader.TileMode.CLAMP));
            canvas.drawRect(rectF.left, rectF.bottom - 44.0f, rectF.right, rectF.bottom, this.p);
            this.p.setShader(null);
            this.tp.setTextSize(14.0f);
            this.tp.setColor(-2694932);
            canvas.drawText(GalleryActivity.this.fmt.format(new Date(item.time)), rectF.left + 12.0f, rectF.bottom - 11.0f, this.tp);
            this.tp.setTextSize(13.0f);
            this.tp.setColor(-6311738);
            String human = GalleryActivity.human(item.size);
            canvas.drawText(human, (rectF.right - 12.0f) - this.tp.measureText(human), rectF.bottom - 11.0f, this.tp);
            if (contains) {
                this.p.setColor(1026519018);
                canvas.drawRect(rectF, this.p);
            }
            canvas.restore();
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(contains ? 3.0f : 1.0f);
            Paint paint = this.p;
            int i = GalleryActivity.BLUE;
            paint.setColor(contains ? GalleryActivity.BLUE : 872415231);
            this.r.set(rectF.left + 1.0f, rectF.top + 1.0f, rectF.right - 1.0f, rectF.bottom - 1.0f);
            canvas.drawRoundRect(this.r, 14.0f, 14.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            float f = rectF.right - 26.0f;
            float f2 = rectF.top + 26.0f;
            Paint paint2 = this.p;
            if (!contains) {
                i = 1711276032;
            }
            paint2.setColor(i);
            canvas.drawCircle(f, f2, 15.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(contains ? 2.4f : 1.8f);
            this.p.setColor(contains ? -1 : -1426063361);
            if (contains) {
                this.path.reset();
                this.path.moveTo(f - 6.5f, 0.5f + f2);
                this.path.lineTo(f - 1.5f, 6.0f + f2);
                this.path.lineTo(f + 7.0f, f2 - 5.5f);
                this.p.setStrokeCap(Paint.Cap.ROUND);
                this.p.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(this.path, this.p);
                this.p.setStrokeCap(Paint.Cap.BUTT);
            } else {
                canvas.drawCircle(f, f2, 9.0f, this.p);
            }
            this.p.setStyle(Paint.Style.FILL);
        }

        void drawEdgeFade(Canvas canvas) {
            this.p.setShader(new LinearGradient(0.0f, 188.0f, 0.0f, 212.0f, -15657180, 1120036, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 188.0f, getWidth(), 212.0f, this.p);
            this.p.setShader(new LinearGradient(0.0f, 726.0f, 0.0f, GalleryActivity.GRID_BOTTOM, 791065, -15986151, Shader.TileMode.CLAMP));
            canvas.drawRect(0.0f, 726.0f, getWidth(), GalleryActivity.GRID_BOTTOM, this.p);
            this.p.setShader(null);
        }

        void drawScrollBar(Canvas canvas) {
            float maxScroll = maxScroll();
            if (maxScroll <= 0.0f) {
                return;
            }
            float max = Math.max(60.0f, 315844.0f / (maxScroll + 562.0f));
            float f = ((562.0f - max) * (this.offsetY / maxScroll)) + GalleryActivity.GRID_TOP;
            this.p.setColor(1308622847);
            this.r.set(getWidth() - 11, f, getWidth() - 5, max + f);
            canvas.drawRoundRect(this.r, 3.0f, 3.0f, this.p);
        }

        void drawConfirm(Canvas canvas) {
            this.p.setColor(-872415232);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.p);
            float width = (getWidth() - 560) / 2.0f;
            float height = (getHeight() - 300) / 2.0f;
            this.r.set(width, height, 560.0f + width, height + 300.0f);
            this.p.setColor(-15262166);
            canvas.drawRoundRect(this.r, 26.0f, 26.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(GalleryActivity.CARD_LINE);
            canvas.drawRoundRect(this.r, 26.0f, 26.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTypeface(this.bold);
            this.tp.setTextSize(24.0f);
            this.tp.setColor(GalleryActivity.TXT);
            String str = "删除 " + GalleryActivity.this.sel.size() + " 张照片？";
            canvas.drawText(str, (getWidth() - this.tp.measureText(str)) / 2.0f, 76.0f + height, this.tp);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(GalleryActivity.TXT3);
            String str2 = "删除后无法恢复，共 " + GalleryActivity.human(selectedBytes());
            canvas.drawText(str2, (getWidth() - this.tp.measureText(str2)) / 2.0f, 112.0f + height, this.tp);
            RectF rectF = this.r;
            float f = 40.0f + width;
            float f2 = GalleryActivity.GRID_TOP + height;
            float f3 = 254.0f + height;
            rectF.set(f, f2, width + 260.0f, f3);
            this.p.setColor(-15064269);
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(GalleryActivity.TXT2);
            float f4 = height + 230.0f;
            canvas.drawText("取消", (((f + width) + 260.0f) / 2.0f) - (this.tp.measureText("取消") / 2.0f), f4, this.tp);
            float f5 = 300.0f + width;
            this.r.set(f5, f2, width + 520.0f, f3);
            this.p.setColor(GalleryActivity.RED);
            canvas.drawRoundRect(this.r, 20.0f, 20.0f, this.p);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(-1);
            canvas.drawText("删除", (((f5 + width) + 520.0f) / 2.0f) - (this.tp.measureText("删除") / 2.0f), f4, this.tp);
        }

        void outline(Canvas canvas, RectF rectF, int i, float f) {
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor(i);
            canvas.drawRoundRect(rectF, f, f, this.p);
            this.p.setStyle(Paint.Style.FILL);
        }

        void openView(int i) {
            if (i < 0 || i >= GalleryActivity.this.items.size()) {
                return;
            }
            this.viewIndex = i;
            this.fullBmp = null;
            this.fullLoading = false;
            this.fullFailed = false;
            this.scale = 1.0f;
            this.tx = 0.0f;
            this.ty = 0.0f;
            this.vDragX = 0.0f;
            this.vDragY = 0.0f;
            this.mode = MODE_VIEW;
            loadFull(i);
            invalidate();
        }

        void closeView() {
            this.mode = MODE_GRID;
            if (this.fullBmp != null && !this.fullBmp.isRecycled()) {
                this.fullBmp.recycle();
            }
            this.fullBmp = null;
            this.fullLoading = false;
            this.viewIndex = -1;
            invalidate();
        }

        void loadFull(final int i) {
            if (i < 0 || i >= GalleryActivity.this.items.size()) {
                return;
            }
            Item item = GalleryActivity.this.items.get(i);
            this.fullLoading = true;
            this.fullFailed = false;
            if (this.fullBmp != null && !this.fullBmp.isRecycled()) {
                this.fullBmp.recycle();
            }
            this.fullBmp = null;
            final String absolutePath = item.f.getAbsolutePath();
            new Thread(new Runnable() { // from class: com.lm.guard.GalleryActivity.GalleryView.2
                @Override // java.lang.Runnable
                public void run() {
                    Bitmap tmpBmp = null;
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        tmpBmp = BitmapFactory.decodeFile(absolutePath, options);
                    } catch (Throwable th) {
                    }
                    final Bitmap bitmap = tmpBmp;
                    GalleryActivity.this.ui.post(new Runnable() { // from class: com.lm.guard.GalleryActivity.GalleryView.2.1
                        @Override // java.lang.Runnable
                        public void run() {
                            if (GalleryView.this.mode != GalleryView.MODE_VIEW || i != GalleryView.this.viewIndex) {
                                if (bitmap != null) {
                                    bitmap.recycle();
                                    return;
                                }
                                return;
                            }
                            GalleryView.this.fullLoading = false;
                            if (bitmap == null) {
                                GalleryView.this.fullFailed = true;
                            } else {
                                GalleryView.this.fullBmp = bitmap;
                                GalleryView.this.scale = 1.0f;
                                GalleryView.this.tx = 0.0f;
                                GalleryView.this.ty = 0.0f;
                            }
                            GalleryView.this.invalidate();
                        }
                    });
                }
            }).start();
        }

        void switchView(int i) {
            int i2;
            if (GalleryActivity.this.items.isEmpty() || (i2 = this.viewIndex + i) < 0 || i2 >= GalleryActivity.this.items.size()) {
                return;
            }
            this.viewIndex = i2;
            this.scale = 1.0f;
            this.tx = 0.0f;
            this.ty = 0.0f;
            this.vDragX = 0.0f;
            this.vDragY = 0.0f;
            loadFull(i2);
            invalidate();
        }

        void drawViewer(Canvas canvas) {
            this.p.setShader(null);
            this.p.setColor(-16777216);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.p);
            if (this.viewIndex < 0 || this.viewIndex >= GalleryActivity.this.items.size()) {
                closeView();
                return;
            }
            float width = getWidth();
            float height = getHeight();
            if (this.fullBmp != null && !this.fullBmp.isRecycled()) {
                canvas.save();
                canvas.translate(width / 2.0f, height / 2.0f);
                canvas.scale(this.scale, this.scale);
                canvas.translate(this.tx + this.vDragX, this.ty + this.vDragY);
                canvas.translate((-this.fullBmp.getWidth()) / 2.0f, (-this.fullBmp.getHeight()) / 2.0f);
                this.ip.setAlpha(255);
                canvas.drawBitmap(this.fullBmp, 0.0f, 0.0f, this.ip);
                canvas.restore();
            } else if (this.fullLoading) {
                this.tp.setTextSize(18.0f);
                this.tp.setColor(GalleryActivity.TXT2);
                canvas.drawText("加载中…", (width / 2.0f) - (this.tp.measureText("加载中…") / 2.0f), height / 2.0f, this.tp);
            } else if (this.fullFailed) {
                this.tp.setTextSize(18.0f);
                this.tp.setColor(GalleryActivity.RED);
                canvas.drawText("无法读取这张照片", (width / 2.0f) - (this.tp.measureText("无法读取这张照片") / 2.0f), height / 2.0f, this.tp);
            }
            Item item = GalleryActivity.this.items.get(this.viewIndex);
            this.tp.setTypeface(this.bold);
            this.tp.setTextSize(19.0f);
            this.tp.setColor(GalleryActivity.TXT);
            canvas.drawText((this.viewIndex + MODE_VIEW) + " / " + GalleryActivity.this.items.size(), 40.0f, 58.0f, this.tp);
            this.tp.setTextSize(15.0f);
            this.tp.setColor(GalleryActivity.TXT2);
            canvas.drawText(GalleryActivity.this.fmt.format(new Date(item.time)) + "  ·  " + GalleryActivity.human(item.size), 100.0f, 58.0f, this.tp);
            float f = height - 84.0f;
            float f2 = height - 40.0f;
            drawViewBtn(canvas, width - 220.0f, f, width - 130.0f, f2, "删除");
            drawViewBtn(canvas, width - 120.0f, f, width - 40.0f, f2, "返回");
            this.tp.setTypeface(Typeface.DEFAULT);
            this.tp.setTextSize(14.0f);
            this.tp.setColor(GalleryActivity.TXT3);
            canvas.drawText("左右滑动切换 · 双指缩放 · 点空白处返回", (width / 2.0f) - (this.tp.measureText("左右滑动切换 · 双指缩放 · 点空白处返回") / 2.0f), height - 100.0f, this.tp);
            this.tp.setTypeface(this.bold);
        }

        void drawViewBtn(Canvas canvas, float f, float f2, float f3, float f4, String str) {
            this.r.set(f, f2, f3, f4);
            this.p.setStyle(Paint.Style.FILL);
            this.p.setColor("删除".equals(str) ? -12968670 : -15064269);
            canvas.drawRoundRect(this.r, 18.0f, 18.0f, this.p);
            this.p.setStyle(Paint.Style.STROKE);
            this.p.setStrokeWidth(1.4f);
            this.p.setColor("删除".equals(str) ? -9752524 : GalleryActivity.CARD_LINE);
            canvas.drawRoundRect(this.r, 18.0f, 18.0f, this.p);
            this.p.setStyle(Paint.Style.FILL);
            this.tp.setTextSize(17.0f);
            this.tp.setColor("删除".equals(str) ? -1013106 : GalleryActivity.TXT2);
            canvas.drawText(str, ((f + f3) / 2.0f) - (this.tp.measureText(str) / 2.0f), ((f2 + f4) / 2.0f) + 6.0f, this.tp);
        }

        @Override // android.view.View
        public boolean onTouchEvent(MotionEvent motionEvent) {
            return this.mode == MODE_VIEW ? onViewerTouch(motionEvent) : onGridTouch(motionEvent);
        }

        boolean onViewerTouch(MotionEvent motionEvent) {
            float x = motionEvent.getX();
            float y = motionEvent.getY();
            int actionMasked = motionEvent.getActionMasked();
            if (actionMasked == 0) {
                this.viewDownX = x;
                this.viewDownY = y;
                this.viewMoved = false;
                this.lastTwoX = x;
                this.lastTwoY = y;
                this.twoFinger = false;
                return true;
            }
            if (actionMasked == 5) {
                this.twoFinger = true;
                this.pinchStartDist = dist(motionEvent);
                this.pinchStartScale = this.scale;
                this.pinchMid.set(midX(motionEvent), midY(motionEvent));
                return true;
            }
            if (actionMasked == 2) {
                if (this.twoFinger && motionEvent.getPointerCount() >= 2) {
                    float dist = dist(motionEvent);
                    if (this.pinchStartDist > 0.0f) {
                        this.scale = clampScale(this.pinchStartScale * (dist / this.pinchStartDist));
                    }
                    float midX = midX(motionEvent);
                    float midY = midY(motionEvent);
                    this.tx += (midX - this.pinchMid.x) / this.scale;
                    this.ty += (midY - this.pinchMid.y) / this.scale;
                    this.pinchMid.set(midX, midY);
                    invalidate();
                    return true;
                }
                float f = x - this.lastTwoX;
                float f2 = y - this.lastTwoY;
                if (Math.abs(x - this.viewDownX) > 12.0f || Math.abs(y - this.viewDownY) > 12.0f) {
                    this.viewMoved = true;
                }
                if (this.scale > 1.001f) {
                    this.vDragX += f / this.scale;
                    this.vDragY += f2 / this.scale;
                    this.lastTwoX = x;
                    this.lastTwoY = y;
                    invalidate();
                    return true;
                }
                this.vDragX += f;
                this.lastTwoX = x;
                this.lastTwoY = y;
                invalidate();
                return true;
            }
            if (actionMasked == 6) {
                this.twoFinger = false;
                this.pinchStartDist = 0.0f;
                return true;
            }
            if (actionMasked == MODE_VIEW) {
                float width = getWidth();
                float height = getHeight();
                float f3 = height - 84.0f;
                float f4 = height - 40.0f;
                if (in(x, y, width - 120.0f, width - 40.0f, f3, f4)) {
                    closeView();
                    return true;
                }
                if (in(x, y, width - 220.0f, width - 130.0f, f3, f4)) {
                    deleteCurrent();
                    return true;
                }
                if (this.scale <= 1.001f && Math.abs(this.vDragX) > width * 0.18f && Math.abs(this.vDragX) > Math.abs(this.vDragY)) {
                    switchView(this.vDragX < 0.0f ? MODE_VIEW : -1);
                    return true;
                }
                this.vDragX = 0.0f;
                this.vDragY = 0.0f;
                if (!this.viewMoved) {
                    closeView();
                }
                return true;
            }
            if (actionMasked != 3) {
                return true;
            }
            this.twoFinger = false;
            this.pinchStartDist = 0.0f;
            this.vDragX = 0.0f;
            this.vDragY = 0.0f;
            return true;
        }

        float clampScale(float f) {
            if (f < this.minScale) {
                f = this.minScale;
            }
            return f > this.maxScale ? this.maxScale : f;
        }

        float dist(MotionEvent motionEvent) {
            if (motionEvent.getPointerCount() < 2) {
                return 0.0f;
            }
            float x = motionEvent.getX(MODE_GRID) - motionEvent.getX(MODE_VIEW);
            float y = motionEvent.getY(MODE_GRID) - motionEvent.getY(MODE_VIEW);
            return (float) Math.sqrt((x * x) + (y * y));
        }

        float midX(MotionEvent motionEvent) {
            return (motionEvent.getX(MODE_GRID) + motionEvent.getX(MODE_VIEW)) / 2.0f;
        }

        float midY(MotionEvent motionEvent) {
            return (motionEvent.getY(MODE_GRID) + motionEvent.getY(MODE_VIEW)) / 2.0f;
        }

        void deleteCurrent() {
            int i = this.viewIndex;
            if (i < 0 || i >= GalleryActivity.this.items.size()) {
                return;
            }
            Item item = GalleryActivity.this.items.get(i);
            try {
                item.f.delete();
                GalleryActivity.this.cache.remove(item.f.getName());
            } catch (Throwable th) {
            }
            GuardService.lastEvent = "已删除 1 张照片";
            Toast.makeText(GalleryActivity.this, "已删除", MODE_GRID).show();
            GalleryActivity.this.items.remove(i);
            if (GalleryActivity.this.items.isEmpty()) {
                closeView();
                invalidate();
                return;
            }
            this.viewIndex = Math.min(i, GalleryActivity.this.items.size() - 1);
            this.scale = 1.0f;
            this.tx = 0.0f;
            this.ty = 0.0f;
            this.vDragX = 0.0f;
            this.vDragY = 0.0f;
            loadFull(this.viewIndex);
            invalidate();
        }

        boolean onGridTouch(MotionEvent motionEvent) {
            int hit;
            float x = motionEvent.getX();
            float y = motionEvent.getY();
            boolean z = false;
            if (motionEvent.getAction() == 0) {
                if (this.confirmVisible) {
                    return true;
                }
                if (in(x, y, 1090.0f, 1200.0f, 40.0f, 92.0f)) {
                    GalleryActivity.this.finish();
                    return true;
                }
                if (in(x, y, GalleryActivity.X0, 168.0f, 122.0f, 172.0f)) {
                    if (!GalleryActivity.this.sel.isEmpty() && GalleryActivity.this.sel.size() == GalleryActivity.this.items.size()) {
                        z = true;
                    }
                    GalleryActivity.this.sel.clear();
                    if (!z) {
                        Iterator<Item> it = GalleryActivity.this.items.iterator();
                        while (it.hasNext()) {
                            GalleryActivity.this.sel.add(it.next().f.getName());
                        }
                    }
                    invalidate();
                    return true;
                }
                if (in(x, y, 180.0f, 300.0f, 122.0f, 172.0f)) {
                    HashSet hashSet = new HashSet();
                    Iterator<Item> it2 = GalleryActivity.this.items.iterator();
                    while (it2.hasNext()) {
                        Item next = it2.next();
                        if (!GalleryActivity.this.sel.contains(next.f.getName())) {
                            hashSet.add(next.f.getName());
                        }
                    }
                    GalleryActivity.this.sel.clear();
                    GalleryActivity.this.sel.addAll(hashSet);
                    invalidate();
                    return true;
                }
                if (in(x, y, 312.0f, 460.0f, 122.0f, 172.0f)) {
                    if (!GalleryActivity.this.sel.isEmpty()) {
                        this.confirmVisible = true;
                        invalidate();
                    }
                    return true;
                }
                this.downX = x;
                this.downY = y;
                this.lastY = y;
                this.dragging = false;
                this.moved = false;
                this.longPressFired = false;
                if (this.vt == null) {
                    this.vt = VelocityTracker.obtain();
                }
                this.vt.clear();
                this.vt.addMovement(motionEvent);
                if (y >= GalleryActivity.GRID_TOP && y <= GalleryActivity.GRID_BOTTOM && (hit = hit(x, y)) >= 0) {
                    this.longPressIdx = hit;
                    this.longPressAt = System.currentTimeMillis();
                    GalleryActivity.this.ui.postDelayed(this.longPressRunnable, 500L);
                }
                return true;
            }
            if (motionEvent.getAction() == 2) {
                if (this.confirmVisible) {
                    return true;
                }
                if (this.vt != null) {
                    this.vt.addMovement(motionEvent);
                }
                float f = y - this.lastY;
                if (Math.abs(y - this.downY) > 10.0f || Math.abs(x - this.downX) > 10.0f) {
                    this.dragging = true;
                    this.moved = true;
                    GalleryActivity.this.ui.removeCallbacks(this.longPressRunnable);
                }
                if (this.dragging) {
                    this.offsetY -= f;
                    clamp();
                    invalidate();
                }
                this.lastY = y;
                return true;
            }
            if (motionEvent.getAction() == MODE_VIEW) {
                GalleryActivity.this.ui.removeCallbacks(this.longPressRunnable);
                if (this.confirmVisible) {
                    handleConfirm(x, y);
                    return true;
                }
                if (this.longPressFired) {
                    this.longPressFired = false;
                    return true;
                }
                if (!this.moved && y >= GalleryActivity.GRID_TOP && y <= GalleryActivity.GRID_BOTTOM) {
                    int hit2 = hit(x, y);
                    if (hit2 >= 0) {
                        String name = GalleryActivity.this.items.get(hit2).f.getName();
                        if (!GalleryActivity.this.sel.remove(name)) {
                            GalleryActivity.this.sel.add(name);
                        }
                        invalidate();
                    }
                } else if (this.dragging && this.vt != null) {
                    this.vt.addMovement(motionEvent);
                    this.vt.computeCurrentVelocity(1000);
                    float yVelocity = this.vt.getYVelocity();
                    if (Math.abs(yVelocity) > 400.0f) {
                        this.scroller.fling(MODE_GRID, (int) this.offsetY, MODE_GRID, (int) (-yVelocity), MODE_GRID, MODE_GRID, MODE_GRID, (int) maxScroll());
                        invalidate();
                    }
                }
                this.dragging = false;
                this.moved = false;
                return true;
            }
            if (motionEvent.getAction() != 3) {
                return true;
            }
            GalleryActivity.this.ui.removeCallbacks(this.longPressRunnable);
            this.dragging = false;
            this.moved = false;
            return true;
        }

        float downX() {
            return this.lastX;
        }

        void handleConfirm(float f, float f2) {
            float width = (getWidth() - 560) / 2.0f;
            float height = (getHeight() - 300) / 2.0f;
            float f3 = height + GalleryActivity.GRID_TOP;
            float f4 = height + 254.0f;
            if (in(f, f2, width + 40.0f, width + 260.0f, f3, f4)) {
                this.confirmVisible = false;
                invalidate();
            } else if (in(f, f2, width + 300.0f, width + 520.0f, f3, f4)) {
                GalleryActivity.this.deleteSelected();
            }
        }

        int hit(float f, float f2) {
            for (int i = MODE_GRID; i < GalleryActivity.this.items.size(); i += MODE_VIEW) {
                RectF cardRect = cardRect(i);
                if (f >= cardRect.left && f <= cardRect.right && f2 >= cardRect.top && f2 <= cardRect.bottom) {
                    return i;
                }
            }
            return -1;
        }

        boolean in(float f, float f2, float f3, float f4, float f5, float f6) {
            return f >= f3 && f <= f4 && f2 >= f5 && f2 <= f6;
        }

        @Override // android.view.View
        public void computeScroll() {
            if (this.scroller.computeScrollOffset()) {
                this.offsetY = this.scroller.getCurrY();
                clamp();
                invalidate();
            }
        }
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
        if (this.cache != null) {
            this.cache.evictAll();
        }
    }
}
