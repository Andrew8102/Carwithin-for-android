package com.github.carwithinforandroid.core;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.github.carwithinforandroid.outputs.zeeho.ZeehoOutput;
import com.github.carwithinforandroid.sources.screen.ScreenCaptureSource;

import java.util.concurrent.atomic.AtomicBoolean;

/** 单例投屏会话：串联屏幕采集、极核输出与界面状态。 */
public final class CastSession {

    private static final CastSession INSTANCE = new CastSession();

    private final CastEngine engine = new CastEngine();
    private final ScreenCaptureSource source = new ScreenCaptureSource();
    private final ZeehoOutput output = new ZeehoOutput();
    private final AtomicBoolean active = new AtomicBoolean(false);

    private Thread worker;
    private Listener listener;

    private CastSession() {
    }

    public static CastSession get() {
        return INSTANCE;
    }

    public interface Listener {
        void onChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(Context context, MediaProjection projection, Listener listener) {
        if (active.get()) return;
        this.listener = listener;
        source.bind(projection);
        engine.attach(source, output);
        active.set(true);
        LogBus.log("开始极核投屏协商…");
        worker = new Thread(() -> {
            try {
                engine.start(context.getApplicationContext());
                LogBus.log("投屏已启动");
            } catch (Throwable e) {
                LogBus.log("投屏启动失败：" + e.getMessage());
            } finally {
                notifyChanged();
            }
        }, "cast-session");
        worker.setDaemon(true);
        worker.start();
        notifyChanged();
    }

    public void stop() {
        if (!active.get()) return;
        active.set(false);
        engine.stop();
        worker = null;
        LogBus.log("投屏已停止");
        notifyChanged();
    }

    public boolean isActive() {
        return active.get();
    }

    public String status() {
        if (!active.get()) return "IDLE";
        String state = engine.status();
        if ("STREAMING".equals(output.status())) return "STREAMING";
        return state;
    }

    public String detail() {
        return "来源 " + source.status() + "  ·  输出 " + output.status()
                + (output.targetIp() == null ? "" : "  ·  仪表 " + output.targetIp());
    }

    private void notifyChanged() {
        Listener current = listener;
        if (current != null) {
            try {
                current.onChanged();
            } catch (Exception ignored) {
                // 界面销毁时忽略回调异常
            }
        }
    }
}
