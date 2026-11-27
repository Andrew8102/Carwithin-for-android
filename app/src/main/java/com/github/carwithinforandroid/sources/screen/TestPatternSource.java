package com.github.carwithinforandroid.sources.screen;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.PhoneVideoSource;
import com.github.carwithinforandroid.core.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 测试图源：用 Canvas 生成彩条 / 棋盘 / 运动帧并编码成 H.264。
 * 不需要录屏授权，用于验证仪表出画与测量帧率。
 */
public final class TestPatternSource implements PhoneVideoSource {

    public static final int PATTERN_NONE = 0;
    public static final int PATTERN_BARS = 1;
    public static final int PATTERN_CHECKER = 2;
    public static final int PATTERN_MOTION = 3;

    private final int pattern;
    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;

    private MediaCodec codec;
    private Surface surface;
    private Thread drawThread;
    private Thread encodeThread;
    private volatile boolean running;
    private Callback callback;
    private byte[] pendingConfig;
    private int frameIndex;

    public TestPatternSource(int pattern, int width, int height, int fps, int bitrateKbps) {
        this.pattern = pattern;
        this.width = width;
        this.height = height;
        this.fps = fps > 0 ? fps : 25;
        this.bitrate = bitrateKbps > 0 ? bitrateKbps * 1000 : 2_500_000;
    }

    @Override
    public void start(android.content.Context context, Callback callback) {
        this.callback = callback;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();

            running = true;
            drawThread = new Thread(this::drawLoop, "testpattern-draw");
            encodeThread = new Thread(this::encodeLoop, "testpattern-encode");
            drawThread.start();
            encodeThread.start();
            callback.onState("TEST_PATTERN_RUNNING");
            LogBus.log("测试图已启动：pattern=" + pattern + " " + width + "×" + height + "@" + fps);
        } catch (Exception e) {
            release();
            callback.onError(e);
            throw new IllegalStateException("启动测试图失败：" + e.getMessage(), e);
        }
    }

    private void drawLoop() {
        long intervalNs = 1_000_000_000L / fps;
        long next = System.nanoTime();
        while (running) {
            Canvas canvas = null;
            try {
                canvas = surface.lockHardwareCanvas();
            } catch (Exception ignored) {
                try {
                    canvas = surface.lockCanvas(null);
                } catch (Exception e) {
                    LogBus.log("测试图画布锁定失败：" + e.getMessage());
                    return;
                }
            }
            try {
                drawFrame(canvas, frameIndex++);
                FrameStats.get().onCaptured();
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Exception ignored) {
                }
            }
            next += intervalNs;
            long sleepMs = (next - System.nanoTime()) / 1_000_000L;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    private void drawFrame(Canvas canvas, int index) {
        switch (pattern) {
            case PATTERN_CHECKER:
                drawChecker(canvas);
                break;
            case PATTERN_MOTION:
                drawMotion(canvas, index);
                break;
            default:
                drawBars(canvas);
                break;
        }
    }

    private void drawBars(Canvas canvas) {
        int[] colors = {0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00,
                0xFFFF00FF, 0xFFFF0000, 0xFF0000FF};
        int barW = width / colors.length;
        for (int i = 0; i < colors.length; i++) {
            canvas.drawColor(colors[i]);
            if (i < colors.length - 1) {
                Paint p = new Paint();
                p.setColor(colors[i + 1]);
                canvas.drawRect(barW * (i + 1), 0, width, height, p);
            }
        }
        drawOverlay(canvas, "COLOR BARS");
    }

    private void drawChecker(Canvas canvas) {
        int cell = Math.max(24, width / 16);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        Paint black = new Paint();
        black.setColor(Color.BLACK);
        canvas.drawColor(Color.BLACK);
        for (int y = 0; y < height; y += cell) {
            for (int x = 0; x < width; x += cell) {
                if (((x / cell) + (y / cell)) % 2 == 0) {
                    canvas.drawRect(x, y, Math.min(x + cell, width), Math.min(y + cell, height), white);
                }
            }
        }
        drawOverlay(canvas, "CHECKERBOARD");
    }

    private void drawMotion(Canvas canvas, int index) {
        canvas.drawColor(0xFF101820);
        Paint block = new Paint();
        block.setColor(0xFF38BDF8);
        int size = Math.max(40, height / 4);
        int x = (int) ((index * (width / fps)) % Math.max(1, width - size));
        int y = (height - size) / 2;
        canvas.drawRect(x, y, x + size, y + size, block);
        Paint grid = new Paint();
        grid.setColor(0xFF334155);
        grid.setStyle(Paint.Style.STROKE);
        for (int gx = 0; gx < width; gx += size) canvas.drawLine(gx, 0, gx, height, grid);
        drawOverlay(canvas, "MOTION  #" + index);
    }

    private void drawOverlay(Canvas canvas, String text) {
        Paint bg = new Paint();
        bg.setColor(0x99000000);
        canvas.drawRect(0, 0, width, Math.max(36, height / 8), bg);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(Math.max(18f, height / 14f));
        p.setAntiAlias(true);
        canvas.drawText(text, 12, Math.max(28f, height / 14f), p);
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 200_000);
            } catch (Exception e) {
                if (running) notifyError(e);
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER || index < 0) continue;

            byte[] data;
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer == null) {
                codec.releaseOutputBuffer(index, false);
                continue;
            }
            try {
                data = new byte[info.size];
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                buffer.get(data);
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
            boolean configFrame = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (configFrame) {
                pendingConfig = data;
                continue;
            }
            byte[] au = buildAccessUnit(data);
            if (au == null) continue;
            FrameStats.get().onEncoded(au.length);
            boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (callback != null) callback.onFrame(new VideoFrame(au, System.currentTimeMillis(), keyframe));
        }
    }

    private byte[] buildAccessUnit(byte[] data) {
        byte[] payload = data;
        if (pendingConfig != null) {
            ByteArrayOutputStream merged = new ByteArrayOutputStream(pendingConfig.length + data.length);
            merged.write(pendingConfig, 0, pendingConfig.length);
            merged.write(data, 0, data.length);
            payload = merged.toByteArray();
            pendingConfig = null;
        }
        if (payload.length < 5) return null;
        if (payload[0] == 0 && payload[1] == 0 && payload[2] == 0 && payload[3] == 1) return payload;
        if (payload[0] == 0 && payload[1] == 0 && payload[2] == 1) {
            byte[] normalized = new byte[payload.length + 1];
            System.arraycopy(payload, 0, normalized, 1, payload.length);
            return normalized;
        }
        return null;
    }

    private void notifyError(Throwable e) {
        if (callback != null) callback.onError(e);
        LogBus.log("测试图编码异常：" + e.getMessage());
    }

    @Override
    public void stop() {
        running = false;
        join(drawThread);
        join(encodeThread);
        drawThread = null;
        encodeThread = null;
        release();
        LogBus.log("测试图已停止");
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void release() {
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception ignored) {
            }
            surface = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    @Override
    public String status() {
        return running ? "TEST_PATTERN_RUNNING" : "TEST_PATTERN_IDLE";
    }

    public static String patternName(int pattern) {
        switch (pattern) {
            case PATTERN_BARS: return "彩条";
            case PATTERN_CHECKER: return "棋盘格";
            case PATTERN_MOTION: return "运动帧";
            default: return "关闭";
        }
    }
}
