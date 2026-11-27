package com.github.carwithinforandroid.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

/**
 * CarLife 画面的本机小窗预览：按协商出的实际画布等比居中绘制。
 *
 * <p>用 Bitmap 直绘而不是 SurfaceView：切页/布局变化时 Surface 会失效并让 MediaCodec 静默丢帧，
 * 位图路径更稳。移植自 CarNine（AGPL-3.0）。
 *
 * <p>触控也是在这里接的：手指坐标先减掉黑边、再按画布比例换算成 CarLife 画布像素，
 * 交给 {@link TouchSink} 走 TOUCH 通道（9340）回传给手机。
 */
public final class PreviewView extends View {

    /** 把预览上的触点换算成画布坐标后回调（坐标为画布像素）。 */
    public interface TouchSink {
        boolean onTouch(int action, int x, int y);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private volatile Bitmap frame;
    private int aspectW = 1024;
    private int aspectH = 480;
    private String placeholder = "等待 Car+ 画面…";
    private TouchSink touchSink;

    public PreviewView(Context context) {
        super(context);
        setBackgroundColor(Color.parseColor("#020617"));
        placeholderPaint.setColor(Color.parseColor("#475569"));
        placeholderPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        setClickable(true);
    }

    public void setTouchSink(TouchSink sink) {
        this.touchSink = sink;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchSink sink = touchSink;
        if (sink == null) return super.onTouchEvent(event);
        // 多指只取第一根，CarLife 的触摸是单点语义
        if (event.getPointerCount() > 1) return true;

        Rect r = drawnRect();
        if (r.width() <= 0 || r.height() <= 0) return false;

        int action = event.getActionMasked();
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        boolean inside = event.getX() >= r.left && event.getX() <= r.right
                && event.getY() >= r.top && event.getY() <= r.bottom;

        // 黑边上的手势交还给外层 ScrollView，否则点在画面边上就滚不动页面
        if (!inside && !up) return false;

        float x = clamp(event.getX(), r.left, r.right);
        float y = clamp(event.getY(), r.top, r.bottom);
        int cx = Math.round((x - r.left) / (float) r.width() * aspectW);
        int cy = Math.round((y - r.top) / (float) r.height() * aspectH);

        // 手指出画或手势被打断时要补一个 CANCEL（按 UP 发），
        // 否则手机侧会一直以为手指还按着，后续点击全部失效
        int outAction = (!inside && up) ? MotionEvent.ACTION_CANCEL : action;
        boolean handled = sink.onTouch(outAction, cx, cy);

        if (action == MotionEvent.ACTION_DOWN) {
            if (!handled) return false; // 没有可触控的会话：不要拦滚动
            performClick();
            // 抢走滑动，否则预览里的拖动（例如地图平移）会被当成滚页
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (up && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
