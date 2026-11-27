package com.github.carwithinforandroid.sources.carlife;

import android.content.Context;

import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.PhoneVideoSource;
import com.github.carwithinforandroid.core.VideoFrame;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CarLife 车机端（HU）来源：在本机 127.0.0.1 的 7 个端口接收 OPPO Car+ 推出的 H.264。
 *
 * <p>与屏幕录屏路线的区别：CarLife 直接拿到 Car+ 已编码好的 H.264，零转码、画质更好、
 * 且不需要 MEDIA_PROJECTION 录屏授权。代价是必须先用「拉起车机界面」启动 Car+，
 * 并等待它在本机监听端口。适用于 ICCOA 专有 SDK 不可用的情况。
 *
 * <p>协议栈 {@link CarLifeLink} 自 CarNine 移植，握手 / 加密 / 帧收发全部在独立守护线程，
 * {@link #start(Context, Callback)} 中的 connect 属于阻塞等待，需由调用方在后台线程执行。
 */
public final class CarLifePhoneSource implements PhoneVideoSource {

    /**
     * 向 Car+ 请求的画布尺寸：<b>848×480</b>。
     *
     * <p>这不是极核仪表的 1024×480。CarLife 的 VIDEO_INIT 只接受白名单里的标准画布
     * （848×480 / 800×480 / 1280×720 等），CarNine 在 realme 真机上验证过的就是 848×480。
     * 之前这里传极核的 1024×480，MD 很可能直接不认，于是卡在「请求画布」之后收不到任何帧。
     * 尺寸与仪表不一致的问题留到后面用 MediaCodec 转码解决，现阶段先保证能收到帧。
     */
    public static final int CARLIFE_WIDTH = 848;
    public static final int CARLIFE_HEIGHT = 480;
    public static final int CARLIFE_FPS = 25;

    /**
     * 等待 Car+ 在本机监听端口的最长时间。
     *
     * <p>实测 Car+ 被拉起后端口只开很短一瞬，所以本类必须<b>先</b>进入等待，
     * 再由调用方去拉起 Car+，两者顺序反了就会一直等到超时。
     */
    private static final long CONNECT_TIMEOUT_MS = 30_000L;

    private final int width;
    private final int height;
    private final int fps;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile CarLifeLink link;
    private volatile Callback callback;
    private volatile String phase = "CARLIFE_IDLE";
    /** 手机实际给的画布尺寸（VIDEO_INIT_DONE 回填）；0 表示还没协商出来。 */
    private volatile int canvasW;
    private volatile int canvasH;

    public CarLifePhoneSource(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    @Override
    public void start(Context context, Callback callback) {
        this.callback = callback;
        this.running.set(true);
        CarLifeLink.setSink(LogBus::log);
        setState("CARLIFE_WAITING_PHONE");
        LogBus.log("CarLife 来源启动：等待 Car+ 监听本机 7 个端口（" + width + "x" + height + "@" + fps + "）");

        CarLifeLink.Callback linkCallback = new CarLifeLink.Callback() {
            @Override
            public void onState(String state) {
                LogBus.log("CarLife：" + state);
                setState("CARLIFE " + state);
            }

            @Override
            public void onVideoInit(int w, int h, int actualFps) {
                canvasW = w;
                canvasH = h;
                LogBus.log("CarLife 画布协商：" + w + "x" + h + "@" + actualFps + "（请求 " + width + "x" + height + "@" + fps + "）");
                setState("CARLIFE 画布 " + w + "x" + h + "@" + actualFps);
            }

            @Override
            public void onVideoFrame(byte[] data, long timestampSec) {
                if (!running.get() || callback == null) return;
                byte[] au = H264.toAnnexB(data);
                boolean keyframe = H264.hasIdr(au);
                FrameStats.get().onCaptured();
                FrameStats.get().onEncoded(au.length);
                callback.onFrame(new VideoFrame(au, timestampSec * 1000L, keyframe));
            }

            @Override
            public void onClosed(String reason) {
                LogBus.log("CarLife 会话结束：" + reason);
                if (!running.get()) return;
                if ("等待手机端监听超时".equals(reason)) {
                    LogBus.log("提示：Car+ 的端口只开了很短一瞬。请用「拉起+直连（一键）」——它先让本类在端口上等，再去拉起 Car+。");
                }
                if (callback != null) {
                    callback.onError(new IOException("CarLife 会话结束：" + reason));
                }
            }
        };

        CarLifeLink current = new CarLifeLink(linkCallback, width, height, fps);
        this.link = current;
        current.connect(CONNECT_TIMEOUT_MS);
    }

    /** 请求手机端重出一个关键帧（花屏时可用于快速恢复）。 */
    public boolean requestKeyframe() {
        CarLifeLink current = link;
        return current != null && current.requestKeyframe();
    }

    /** 手机实际画布宽度；0 表示尚未协商出画布。 */
    public int canvasWidth() {
        return canvasW;
    }

    public int canvasHeight() {
        return canvasH;
    }

    /**
     * 把本机预览上的手指动作回传给手机（CarLife TOUCH 通道 9340）。
     *
     * @param action {@link android.view.MotionEvent} 的 action 常量
     * @param x      画布像素坐标（0..canvasWidth-1）
     * @param y      画布像素坐标（0..canvasHeight-1）
     */
    public boolean touch(int action, int x, int y) {
        CarLifeLink current = link;
        return current != null && current.touch(action, x, y);
    }

    @Override
    public void stop() {
        running.set(false);
        CarLifeLink current = link;
        if (current != null) {
            current.stop();
            link = null;
        }
        phase = "CARLIFE_IDLE";
    }

    @Override
    public String status() {
        return phase;
    }

    private void setState(String value) {
        this.phase = value;
        if (callback != null) callback.onState(value);
    }
}
