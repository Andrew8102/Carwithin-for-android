package com.github.carwithinforandroid.sources.screen;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.view.Surface;

import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.PhoneVideoSource;
import com.github.carwithinforandroid.core.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 手机屏幕采集源。用 MediaProjection 抓屏，MediaCodec 编成 1024×480 H.264 Annex-B。
 * 对应官方极核 App 的「点击投屏 → 录制屏幕内容」行为。
 */
public final class ScreenCaptureSource implements PhoneVideoSource {

    private static final String TAG = "ScreenCapture";
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 480;
    private static final int FPS = 25;
    private static final int BITRATE = 2_500_000;
    private static final int DENSITY = 160;

    private MediaProjection projection;
    private VirtualDisplay.Callback displayCallback;
    private MediaCodec codec;
    private VirtualDisplay virtualDisplay;
    private Thread encoderThread;
    private volatile boolean running;
    private Callback callback;
    private byte[] pendingConfig;

    public void bind(MediaProjection projection) {
        this.projection = projection;
    }

    @Override
    public void start(android.content.Context context, Callback callback) {
        if (projection == null) throw new IllegalStateException("尚未获得录屏授权");
        this.callback = callback;

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surface = codec.createInputSurface();
            codec.start();

            displayCallback = new VirtualDisplay.Callback() {
                @Override
                public void onStopped() {
                    LogBus.log("系统停止了录屏虚拟显示");
                }
            };
            virtualDisplay = projection.createVirtualDisplay(
                    "CarWithinCast", WIDTH, HEIGHT, DENSITY,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, displayCallback, null);

            running = true;
            encoderThread = new Thread(this::encodeLoop, "h264-encoder");
            encoderThread.start();
            callback.onState("SCREEN_CAPTURE_RUNNING");
            LogBus.log("屏幕采集已启动 " + WIDTH + "×" + HEIGHT + "@" + FPS + " " + (BITRATE / 1000) + "kbps");
        } catch (Exception e) {
            releaseCodec();
            callback.onError(e);
            throw new IllegalStateException("启动屏幕采集失败：" + e.getMessage(), e);
        }
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
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                LogBus.log("编码器输出格式：" + codec.getOutputFormat());
                continue;
            }
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
            byte[] accessUnit = buildAccessUnit(data);
            if (accessUnit == null) continue;
            boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (callback != null) callback.onFrame(new VideoFrame(accessUnit, System.currentTimeMillis(), keyframe));
        }
    }

    /** 把编码器输出规范成 00 00 00 01 开头的 Annex-B AU，首帧补 SPS/PPS。 */
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
        if (startsWith4(payload, 0)) return payload;
        if (startsWith3(payload, 0)) {
            byte[] normalized = new byte[payload.length + 1];
            normalized[0] = 0;
            System.arraycopy(payload, 0, normalized, 1, payload.length);
            return normalized;
        }
        return null;
    }

    private static boolean startsWith4(byte[] data, int offset) {
        return offset + 4 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 0 && data[offset + 3] == 1;
    }

    private static boolean startsWith3(byte[] data, int offset) {
        return offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 1;
    }

    private void notifyError(Throwable error) {
        if (callback != null) callback.onError(error);
        LogBus.log("编码线程异常：" + error.getMessage());
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = encoderThread;
        if (thread != null) {
            try {
                thread.join(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        encoderThread = null;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        releaseCodec();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
                // 授权已失效时无需强行停止
            }
            projection = null;
        }
        LogBus.log("屏幕采集已停止");
    }

    private void releaseCodec() {
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
        return running ? "SCREEN_CAPTURE_RUNNING" : "SCREEN_CAPTURE_IDLE";
    }
}
