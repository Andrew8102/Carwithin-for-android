package com.github.carwithinforandroid.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * CarLife 画面的本机小窗预览：按协商出的实际画布等比居中绘制。
 *
 * <p>用 Bitmap 直绘而不是 SurfaceView：切页/布局变化时 Surface 会失效并让 MediaCodec 静默丢帧，
 * 位图路径更稳。移植自 CarNine（AGPL-3.0）。
 */
public final class PreviewView extends View {

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private volatile Bitmap frame;
    private int aspectW = 1024;
    private int aspectH = 480;
    private String placeholder = "等待 Car+ 画面…";

    public PreviewView(Context context) {
        super(context);
        setBackgroundColor(Color.parseColor("#020617"));
        placeholderPaint.setColor(Color.parseColor("#475569"));
        placeholderPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setFrame(Bitmap bitmap) {
        frame = bitmap;
        postInvalidateOnAnimation();
    }

    public void setPlaceholder(String text) {
        placeholder = text;
        postInvalidateOnAnimation();
    }

    public void setAspect(int w, int h) {
        if (w > 0 && h > 0 && (w != aspectW || h != aspectH)) {
            aspectW = w;
            aspectH = h;
            requestLayout();
        }
    }

    /** 画面实际绘制区域（等比居中，可能有黑边）。 */
    public Rect drawnRect() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw == 0 || vh == 0) {
            dst.set(0, 0, 0, 0);
            return dst;
        }
        float ratio = (float) aspectW / aspectH;
        int w = vw;
        int h = Math.round(vw / ratio);
        if (h > vh) {
            h = vh;
            w = Math.round(vh * ratio);
        }
        int left = (vw - w) / 2;
        int top = (vh - h) / 2;
        dst.set(left, top, left + w, top + h);
        return dst;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = frame;
        if (b == null || b.isRecycled()) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            canvas.drawText(placeholder, cx, cy, placeholderPaint);
            return;
        }
        canvas.drawBitmap(b, null, drawnRect(), paint);
    }
}
