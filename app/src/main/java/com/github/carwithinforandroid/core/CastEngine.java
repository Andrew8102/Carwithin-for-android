package com.github.carwithinforandroid.core;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把手机来源的视频帧桥接到仪表输出。
 *
 * <p>采集与输出是<b>解耦</b>的：仪表不在线（没连仪表 Wi-Fi、握手失败）时只记一条日志并继续采集，
 * 帧仍然会走到本地预览（{@link FrameTap}）。这样调试 Car+ 链路不需要极核主机在场。
 */
public final class CastEngine implements PhoneVideoSource.Callback {

    /** 帧旁路：供本地预览解码使用，在采集线程回调，实现方必须自行保证线程安全。 */
    public interface FrameTap {
        void onFrame(VideoFrame frame);
    }

    private final AtomicReference<String> state = new AtomicReference<>("IDLE");
    private PhoneVideoSource source;
    private DashboardOutput output;
    private volatile FrameTap tap;

    /** 仪表输出是否真的建立了会话；false 表示只采集不推流。 */
    private volatile boolean outputReady;
    private volatile boolean outputFailureLogged;
    private volatile boolean stopped;
    /** 是否已有线程在连仪表，防止连点「重连仪表」开出多条会话。 */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    /** 采集源致命失败时的回调：由会话层收尾，避免界面卡在「无法重试」状态。 */
    private volatile Runnable fatalHandler;

    /**
     * 向仪表申报的画面尺寸。默认取车型配置，但 CarLife 只能给 848×480，
     * 所以由会话层按采集源的真实画布覆盖 —— 申报尺寸必须和实际推的 H.264 一致，
     * 否则仪表按错的尺寸解码，画面会花屏或整体错位。
     */
    private volatile int outputWidth = 1024;
    private volatile int outputHeight = 480;
    private volatile int outputFps = 25;

    public void setOutputSize(int width, int height, int fps) {
        if (width > 0 && height > 0) {
            this.outputWidth = width;
            this.outputHeight = height;
        }
        if (fps > 0) this.outputFps = fps;
    }

    public void setFatalHandler(Runnable handler) {
        this.fatalHandler = handler;
    }

    public void setFrameTap(FrameTap tap) {
        this.tap = tap;
    }

    public void attach(PhoneVideoSource source, DashboardOutput output) {
        this.source = source;
        this.output = output;
    }

    public void start(Context context) {
        if (source == null || output == null) {
            state.set("NO_SOURCE_OR_OUTPUT");
            return;
        }
        state.set("STARTING");
        outputReady = false;
        outputFailureLogged = false;
        stopped = false;

        // 先启动采集源。CarLife 的窗口期只有一两秒：Car+ 打开 7 个端口后若没有 HU 连入
        // 会立刻放弃，所以必须先让 CarLifeLink 在端口上等，再让用户去拉起 Car+。
        // 采集失败就直接结束，不启动输出。
        try {
            source.start(context, this);
        } catch (Throwable e) {
            state.set("ERROR: " + e.getMessage());
            onError(e);
            return;
        }
        // 采集过程中可能已通过 onError 进入 ERROR；不要用 RUNNING_LOCAL_ONLY 覆盖掉失败原因。
        if (!state.get().startsWith("ERROR")) {
            state.set("RUNNING_LOCAL_ONLY");
        }

        // 仪表输出独立线程：网络发现/握手可能耗时数秒，不能挡住采集线程。
        // 而且用户常常先点投屏、后连仪表 Wi-Fi，所以失败要重试而不是一次定生死。
        startOutputThread(context, 6);
    }

    /** 只重连仪表，不打断已建立的 CarLife 会话（重开一次要等端口窗口，很贵）。 */
    public void retryOutput(Context context) {
        if (stopped || outputReady || source == null || output == null) return;
        LogBus.log("手动重连仪表…");
        startOutputThread(context, 3);
    }

    private void startOutputThread(Context context, int attempts) {
        if (!connecting.compareAndSet(false, true)) {
            LogBus.log("正在连仪表，稍等（不要连点）");
            return;
        }
        Thread outputThread = new Thread(() -> {
            try {
                for (int i = 1; i <= attempts && !stopped && !outputReady; i++) {
                    try {
                        output.start(context, outputWidth, outputHeight, outputFps);
                        outputReady = true;
                        if (!state.get().startsWith("ERROR")) state.set("RUNNING");
                        LogBus.log("推流已就绪：向仪表申报 " + outputWidth + "x" + outputHeight
                                + "@" + outputFps + "，画面直接转发");
                        return;
                    } catch (Throwable e) {
                        String why = e.getMessage() == null ? e.toString() : e.getMessage();
                        LogBus.log("仪表输出未就绪（第 " + i + "/" + attempts + " 次）：" + why);
                        try {
                            output.stop();
                        } catch (Throwable ignored) {
                            // 半启动的监听端口要关掉，否则重试时 bind 会失败
                        }
                        if (i == attempts || stopped) break;
                        if (i == 1) {
                            LogBus.log("会每 10 秒重试一次：现在去把手机连上仪表 Wi-Fi 也来得及");
                        }
                        try {
                            Thread.sleep(10_000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!stopped && !outputReady) {
                    LogBus.log("仪表仍未连上：当前只在本地预览显示");
                    LogBus.log("连上仪表 Wi-Fi 后点「重连仪表」，不用重开 CarLife 会话");
                }
            } finally {
                connecting.set(false);
            }
        }, "cast-output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    public void stop() {
        state.set("STOPPING");
        stopped = true;
        if (source != null) {
            try {
                source.stop();
            } catch (Exception ignored) {
                // 授权可能已失效，不影响输出侧清理
            }
        }
        if (output != null) {
            try {
                output.stop();
            } catch (Exception ignored) {
                // 输出可能从未建立，忽略
            }
        }
        outputReady = false;
        state.set("IDLE");
    }

    public String status() {
        return state.get();
    }

    public boolean isOutputReady() {
        return outputReady;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 预览优先：即使推流失败也要能看到画面
        FrameTap current = tap;
        if (current != null) {
            try {
                current.onFrame(frame);
            } catch (Throwable ignored) {
                // 预览解码失败不应影响推流
            }
        }
        if (!outputReady || output == null) return;
        try {
            output.onFrame(frame);
        } catch (Exception e) {
            outputReady = false;
            state.set("FRAME_ERROR: " + e.getMessage());
            if (!outputFailureLogged) {
                outputFailureLogged = true;
                LogBus.log("仪表推帧失败，已停止推流（本地预览继续）：" + e.getMessage());
            }
        }
    }

    @Override
    public void onState(String s) {
        if (!"SCREEN_CAPTURE_RUNNING".equals(s)) state.set(s);
    }

    @Override
    public void onError(Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        state.set("ERROR: " + msg);
        LogBus.log("引擎错误：" + msg);
        Runnable handler = fatalHandler;
        if (handler != null) {
            try {
                handler.run();
            } catch (Throwable ignored) {
                // 收尾失败不应再抛
            }
        }
    }
}
