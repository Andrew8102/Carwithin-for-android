package com.github.carwithinforandroid.core;

import java.util.concurrent.atomic.AtomicLong;

/** 帧率统计：采集、编码、发送三个阶段的 FPS、码率与丢帧。 */
public final class FrameStats {

    private static final FrameStats INSTANCE = new FrameStats();

    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicLong encodedFrames = new AtomicLong();
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong encodedBytes = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    private volatile long windowStartMs = System.currentTimeMillis();
    private volatile double captureFps;
    private volatile double encodeFps;
    private volatile double sendFps;
    private volatile double bitrateKbps;
    private volatile String lastReport = "";

    private FrameStats() {
    }

    public static FrameStats get() {
        return INSTANCE;
    }

    public void onCaptured() {
        capturedFrames.incrementAndGet();
    }

    public void onEncoded(int bytes) {
        encodedFrames.incrementAndGet();
        encodedBytes.addAndGet(bytes);
    }

    public void onSent() {
        sentFrames.incrementAndGet();
    }

    public void onDropped() {
        droppedFrames.incrementAndGet();
    }

    /** 每 1 秒调用一次，计算窗口内指标。 */
    public void sample() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMs;
        if (elapsed < 500) return;
        double seconds = elapsed / 1000.0;
        captureFps = capturedFrames.getAndSet(0) / seconds;
        encodeFps = encodedFrames.getAndSet(0) / seconds;
        sendFps = sentFrames.getAndSet(0) / seconds;
        bitrateKbps = encodedBytes.getAndSet(0) * 8.0 / 1000.0 / seconds;
        windowStartMs = now;
        lastReport = String.format(
                "采集 %.1f FPS · 编码 %.1f FPS · 发送 %.1f FPS%n码率 %.0f kbps · 累计丢帧 %d",
                captureFps, encodeFps, sendFps, bitrateKbps, droppedFrames.get());
    }

    public String report() {
        return lastReport.isEmpty() ? "等待数据…" : lastReport;
    }

    public void reset() {
        capturedFrames.set(0);
        encodedFrames.set(0);
        sentFrames.set(0);
        encodedBytes.set(0);
        droppedFrames.set(0);
        windowStartMs = System.currentTimeMillis();
        captureFps = encodeFps = sendFps = bitrateKbps = 0;
        lastReport = "";
    }
}
