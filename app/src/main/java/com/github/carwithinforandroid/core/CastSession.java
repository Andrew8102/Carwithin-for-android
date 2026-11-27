package com.github.carwithinforandroid.core;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.github.carwithinforandroid.outputs.zeeho.ZeehoOutput;
import com.github.carwithinforandroid.sources.carlife.CarLifePhoneSource;
import com.github.carwithinforandroid.sources.screen.ScreenCaptureSource;
import com.github.carwithinforandroid.sources.screen.TestPatternSource;

import java.util.concurrent.atomic.AtomicBoolean;

/** 单例投屏会话：按车型配置串联采集源（录屏或测试图）、极核输出与界面状态。 */
public final class CastSession {

    private static final CastSession INSTANCE = new CastSession();

    private final CastEngine engine = new CastEngine();
    private final ScreenCaptureSource screenSource = new ScreenCaptureSource();
    private final ZeehoOutput output = new ZeehoOutput();
    private final AtomicBoolean active = new AtomicBoolean(false);

    private PhoneVideoSource currentSource;
    private VehicleProfile profile;
    private int testPattern = TestPatternSource.PATTERN_NONE;
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

    /** 注册本地预览帧旁路（不需要仪表在线，CarLife 收到的帧会先走这里）。 */
    public void setFrameTap(CastEngine.FrameTap tap) {
        engine.setFrameTap(tap);
    }

    /** 应用车型配置与测试图设置；仅在空闲时生效。 */
    public void configure(Context context) {
        if (active.get()) return;
        profile = VehicleProfile.load(context);
        testPattern = VehicleProfile.testPattern(context);
        output.configure(profile);
        screenSource.configure(profile.width, profile.height, profile.fps, profile.bitrateKbps);
    }

    /** 用手机屏幕采集启动投屏。 */
    public void start(Context context, MediaProjection projection, Listener listener) {
        if (active.get()) return;
        if (listener != null) this.listener = listener;
        if (profile == null) configure(context);
        if (projection == null) {
            LogBus.log("未获得录屏授权，无法开始屏幕投屏");
            return;
        }
        screenSource.bind(projection);
        currentSource = screenSource;
        begin(context, "屏幕采集");
    }

    /** 用 CarLife 车机端来源启动投屏：接收 Car+ 推出的 H.264，不需要录屏授权。 */
    public void startCarLife(Context context, Listener listener) {
        if (active.get()) return;
        if (listener != null) this.listener = listener;
        if (profile == null) configure(context);
        currentSource = new CarLifePhoneSource(profile.width, profile.height, profile.fps);
        begin(context, "CarLife 车机端");
    }

    /** 用测试图启动投屏，不需要录屏授权。 */
    public void startTest(Context context, Listener listener) {
        if (active.get()) return;
        if (listener != null) this.listener = listener;
        if (profile == null) configure(context);
        int pattern = testPattern == TestPatternSource.PATTERN_NONE
                ? TestPatternSource.PATTERN_BARS : testPattern;
        currentSource = new TestPatternSource(pattern, profile.width, profile.height,
                profile.fps, profile.bitrateKbps);
        begin(context, "测试图 " + TestPatternSource.patternName(pattern));
    }

    private void begin(Context context, String sourceLabel) {
        engine.attach(currentSource, output);
        // 采集源自己失败（典型：CarLife 等端口 30s 超时）时自动收尾，
        // 否则 active 一直为 true，按钮全部禁用，用户只能干看着没法重试。
        engine.setFatalHandler(this::finishOnFailure);
        active.set(true);
        FrameStats.get().reset();
        LogBus.log("开始投屏协商（" + sourceLabel + " → " + profile.shortLabel() + "）");
        if (!profile.implemented) {
            LogBus.log("注意：该车型输出协议尚未实现，当前用极核 PXC 栈尝试，可能不出画");
        }
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
        currentSource = null;
        LogBus.log("投屏已停止");
        notifyChanged();
    }

    /**
     * 采集源致命失败后的收尾：在独立线程里做，避免在采集线程内部 join 自己。
     * 结束后 active 归 false，界面按钮恢复可用，用户可直接重试。
     */
    private void finishOnFailure() {
        Thread t = new Thread(() -> {
            if (!active.compareAndSet(true, false)) return;
            engine.stop();
            worker = null;
            currentSource = null;
            LogBus.log("会话已结束（采集源失败）：可以直接重新点击，或改用「录屏投屏」");
            notifyChanged();
        }, "cast-fail-cleanup");
        t.setDaemon(true);
        t.start();
    }

    public boolean isActive() {
        return active.get();
    }

    /** 仪表输出是否已建立；false 表示当前只在本地预览、不推极核。 */
    public boolean outputReady() {
        return engine.isOutputReady();
    }

    public VehicleProfile profile() {
        return profile;
    }

    public int testPattern() {
        return testPattern;
    }

    public String status() {
        if (!active.get()) return "IDLE";
        String state = engine.status();
        if ("STREAMING".equals(output.status())) return "STREAMING";
        return state;
    }

    public String detail() {
        String source = currentSource == null ? "无" : currentSource.status();
        String target = output.targetIp() == null ? "" : "  ·  仪表 " + output.targetIp();
        return "来源 " + source + "  ·  输出 " + output.status() + target;
    }

    /** 采集源自己的阶段文本（不受引擎状态覆盖），用于判断 CarLife 是否已连上。 */
    public String sourcePhase() {
        return currentSource == null ? "" : currentSource.status();
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
