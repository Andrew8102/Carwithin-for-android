package com.github.carwithinforandroid.core;

import android.content.Context;

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

        // 先起仪表输出，但失败不阻断采集：本地预览 / 诊断仍需要收到 Car+ 的帧。
        try {
            output.start(context, 1024, 480, 25);
            outputReady = true;
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            LogBus.log("仪表输出未就绪：" + why);
            LogBus.log("已切换为「只采集」：画面会在本地预览显示，不推极核");
        }

        try {
            source.start(context, this);
            state.set(outputReady ? "RUNNING" : "RUNNING_LOCAL_ONLY");
        } catch (Throwable e) {
            state.set("ERROR: " + e.getMessage());
            onError(e);
        }
    }

    public void stop() {
        state.set("STOPPING");
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
        state.set("ERROR: " + error.getMessage());
        LogBus.log("引擎错误：" + error.getMessage());
    }
}
