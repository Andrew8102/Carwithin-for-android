package com.github.carwithinforandroid;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.github.carwithinforandroid.core.CastSession;
import com.github.carwithinforandroid.core.CrashGuard;
import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.VehicleProfile;
import com.github.carwithinforandroid.core.VideoFrame;
import com.github.carwithinforandroid.launcher.CarAppLauncher;
import com.github.carwithinforandroid.media.H264Decoder;
import com.github.carwithinforandroid.sources.screen.TestPatternSource;
import com.github.carwithinforandroid.ui.PreviewView;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** 投屏控制台：车机界面拉起、投屏控制、车型调试配置、测试图与帧率监控。 */
public class MainActivity extends Activity {

    private static final String BG = "#0B1120";
    private static final String CARD = "#1E293B";
    private static final String BORDER = "#334155";
    private static final String TEXT = "#E2E8F0";
    private static final String MUTED = "#94A3B8";
    private static final String ACCENT = "#38BDF8";
    private static final String GREEN = "#4ADE80";
    private static final String AMBER = "#FBBF24";
    private static final String RED = "#F87171";

    private static final int REQUEST_CAST_PERMISSION = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;

    private final CastSession session = CastSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<VehicleProfile> profiles = VehicleProfile.presets();

    private TextView stateValue;
    private TextView stateHint;
    private TextView detailValue;
    private TextView statsValue;
    private TextView launchResult;
    private TextView logValue;
    private EditText packageInput;
    private EditText activityInput;
    private EditText targetIpInput;
    private EditText widthInput;
    private EditText heightInput;
    private EditText fpsInput;
    private EditText bitrateInput;
    private EditText discoveryPortInput;
    private Spinner profileSpinner;
    private Spinner patternSpinner;
    private CheckBox autoLaunchCheck;
    private Button startButton;
    private Button carlifeButton;
    private Button stopButton;
    private Button testButton;
    private Button oneKeyButton;

    private PreviewView previewView;
    private TextView previewHint;
    private H264Decoder decoder;
    private volatile int previewFrames;
    private volatile int previewLuma = -1;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            FrameStats.get().sample();
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 先装崩溃兜底，再回放上次崩溃堆栈（方便无 adb 时把日志复制回传）。
        CrashGuard.install(this);
        CrashGuard.restoreLastCrash(this);
        session.configure(this);
        // 每份日志首行带版本，避免"改了却没生效 / 装的是旧包"这类排查盲区。
        LogBus.log("CarWithin " + appVersion() + " · Android " + Build.VERSION.SDK_INT
                + " · " + Build.MODEL);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addLaunchCard(root);
        addControlCard(root);
        addStatusCard(root);
        addPreviewCard(root);
        addStatsCard(root);
        addVehicleCard(root);
        addLogCard(root);
        addFooter(root);
        setContentView(scroll);

        setupPreviewDecoder();
        session.setListener(() -> handler.post(this::render));
        requestRuntimePermissions();
        handler.post(refresher);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresher);
        session.setFrameTap(null);
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
        session.setListener(null);
        super.onDestroy();
    }

    /* ---------------- 头部 ---------------- */

    private void addHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("CarWithin");
        title.setTextSize(28f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor(TEXT));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("手机屏幕 / 测试图  →  电动两轮车仪表");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(Color.parseColor(ACCENT));
        subtitle.setPadding(0, dp(4), 0, 0);
        root.addView(subtitle);

        TextView desc = new TextView(this);
        desc.setText("支持极核 AE4 MAX2+ · 九号 / 台铃 / 小牛 参数预设 · 社区实验项目");
        desc.setTextSize(11f);
        desc.setTextColor(Color.parseColor(MUTED));
        desc.setPadding(0, dp(5), 0, dp(10));
        root.addView(desc);
    }

    /* ---------------- 车机界面启动卡 ---------------- */

    private void addLaunchCard(LinearLayout root) {
        LinearLayout card = card("① 拉起车机互联界面（投屏前）");

        TextView tip = new TextView(this);
        tip.setText("CarLife 全程在本机完成，不需要真车机：Car+ 的 CarLife 组件会在 127.0.0.1 监听 7 个端口，本 App 冒充车机（HU）连入收流。\n"
                + "操作顺序：①「尝试进 CarLife」自动逐个拉起入口并观察端口 → 端口 7/7 后 ②点「CarLife 直连」→ 画面应出现在下方本地预览。\n"
                + "录屏投屏不需要这一步。");
        tip.setTextSize(11f);
        tip.setTextColor(Color.parseColor(MUTED));
        tip.setLineSpacing(0, 1.3f);
        card.addView(tip);

        String savedPkg = VehicleProfile.launchPackage(this);
        packageInput = input("包名，如 com.oplus.ocar", savedPkg);
        card.addView(packageInput);
        activityInput = input("Activity（可留空，留空用系统入口）", "");
        card.addView(activityInput);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button launchBtn = button("拉起界面", "#6366F1");
        launchBtn.setOnClickListener(safe("拉起界面", this::doLaunch));
        Button diagBtn = button("诊断", "#0EA5E9");
        diagBtn.setOnClickListener(safe("诊断", this::doDiagnose));
        Button detectBtn = button("检测安装", "#475569");
        detectBtn.setOnClickListener(safe("检测安装", this::detectInstalled));
        launchBtn.setLayoutParams(rowParams());
        diagBtn.setLayoutParams(rowParams());
        detectBtn.setLayoutParams(rowParams());
        btnRow.addView(launchBtn);
        btnRow.addView(diagBtn);
        btnRow.addView(detectBtn);
        card.addView(btnRow);

        // 调试用：枚举 Car+ 真实组件 + 逐个尝试拉起，直到 7 端口开始监听。
        LinearLayout debugRow = new LinearLayout(this);
        debugRow.setOrientation(LinearLayout.HORIZONTAL);
        Button enumBtn = button("枚举组件", "#7C3AED");
        enumBtn.setOnClickListener(safe("枚举组件", this::doEnumerate));
        Button tryBtn = button("尝试进 CarLife", "#DB2777");
        tryBtn.setOnClickListener(safe("尝试进 CarLife", this::doTryEnterCarlife));
        enumBtn.setLayoutParams(rowParams());
        tryBtn.setLayoutParams(rowParams());
        debugRow.addView(enumBtn);
        debugRow.addView(tryBtn);
        card.addView(debugRow);

        launchResult = new TextView(this);
        launchResult.setTextSize(11f);
        launchResult.setTextColor(Color.parseColor(AMBER));
        launchResult.setPadding(0, dp(8), 0, 0);
        card.addView(launchResult);

        autoLaunchCheck = new CheckBox(this);
        autoLaunchCheck.setText("开始投屏前自动拉起上面的车机界面");
        autoLaunchCheck.setTextColor(Color.parseColor(TEXT));
        autoLaunchCheck.setTextSize(12f);
        autoLaunchCheck.setChecked(VehicleProfile.autoLaunch(this));
        autoLaunchCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try {
                VehicleProfile.setAutoLaunch(this, isChecked);
            } catch (Throwable e) {
                report("保存自动拉起设置", e);
            }
        });
        card.addView(autoLaunchCheck);

        root.addView(card);
    }

    private void doLaunch() {
        String pkg = packageInput.getText().toString().trim();
        String activity = activityInput.getText().toString().trim();
        VehicleProfile.setLaunchPackage(this, pkg);
        launchResult.setText("正在枚举入口并尝试拉起…\n（会依次试指定组件 → 疑似入口 → 全部组件）");
        // 枚举全部 Activity 可能要试几十个组件，放主线程会卡界面，必须丢后台。
        final Context app = getApplicationContext();
        new Thread(() -> {
            String text = CarAppLauncher.launch(app, pkg, activity);
            runOnUiThread(() -> launchResult.setText(text));
        }, "launch-ui").start();
    }

    private void doDiagnose() {
        String pkg = packageInput.getText().toString().trim();
        String activity = activityInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = VehicleProfile.launchPackage(this);
            packageInput.setText(pkg);
        }
        final String pkgFinal = pkg;
        final String activityFinal = activity;
        final Context app = getApplicationContext();
        launchResult.setText("诊断中，正在探测本机 7 个端口（最多约 3 秒）…");
        // 端口探测是阻塞式 Socket 连接，绝不能放在主线程，否则抛 NetworkOnMainThreadException 直接闪退。
        new Thread(() -> {
            String text;
            try {
                text = CarAppLauncher.diagnose(app, pkgFinal, activityFinal);
            } catch (Throwable e) {
                StringWriter writer = new StringWriter();
                e.printStackTrace(new PrintWriter(writer));
                text = "诊断失败：" + e + "\n\n" + writer;
                LogBus.log("诊断失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            final String out = text;
            runOnUiThread(() -> launchResult.setText(out));
        }, "diagnose").start();
    }

    /** 枚举 Car+ 声明的全部 Activity，用来找真实的 CarLife 入口类名。 */
    private void doEnumerate() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = VehicleProfile.launchPackage(this);
            packageInput.setText(pkg);
        }
        final String pkgFinal = pkg;
        if (pkgFinal.isEmpty()) {
            launchResult.setText("请先在上方填写包名，例如 com.oplus.ocar");
            return;
        }
        launchResult.setText("正在枚举 " + pkgFinal + " 的组件…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String text;
            try {
                text = CarAppLauncher.describeActivities(app, pkgFinal);
            } catch (Throwable e) {
                text = "枚举失败：" + e;
                LogBus.log("枚举组件失败：" + e);
            }
            final String out = text;
            runOnUiThread(() -> launchResult.setText(out));
        }, "enumerate-activities").start();
    }

    /** 逐个拉起疑似 CarLife 入口，命中 7 端口监听即停。 */
    private void doTryEnterCarlife() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = VehicleProfile.launchPackage(this);
            packageInput.setText(pkg);
        }
        final String pkgFinal = pkg;
        if (pkgFinal.isEmpty()) {
            launchResult.setText("请先在上方填写包名，例如 com.oplus.ocar");
            return;
        }
        launchResult.setText("开始逐个尝试拉起（每个约 2.5 秒，界面会跳到 Car+）…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String text;
            try {
                text = CarAppLauncher.tryEnterCarlife(app, pkgFinal, line ->
                        runOnUiThread(() -> launchResult.setText(line + "\n（进行中…）")));
            } catch (Throwable e) {
                text = "尝试失败：" + e;
                LogBus.log("尝试进入 CarLife 失败：" + e);
            }
            final String out = text;
            runOnUiThread(() -> launchResult.setText(out));
        }, "try-enter-carlife").start();
    }

    /**
     * 一键流程：<b>先</b>让 CarLife 来源在 7 个端口上等待，<b>再</b>去拉起 Car+。
     *
     * <p>顺序不能反。实测 Car+ 被拉起后端口只开很短一瞬，等它开完再手动点「直连」时
     * 早就关了，只能等到超时。
     */
    private void startCarlifeWithLaunch() {
        if (session.isActive()) {
            // 之前这里直接 return，界面上什么反应都没有，用户会以为按钮坏了。
            String phase = session.sourcePhase();
            LogBus.log("已有会话在进行中（" + phase + "），忽略重复点击");
            launchResult.setText("已经在等待/投屏中，不必重复点。\n"
                    + "当前阶段：" + (phase.isEmpty() ? session.status() : phase) + "\n"
                    + "要重来一遍请先点「停止投屏」。");
            Toast.makeText(this, "已在等待/投屏中，先「停止投屏」再重试", Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = VehicleProfile.launchPackage(this);
            packageInput.setText(pkg);
        }
        if (pkg.isEmpty()) {
            launchResult.setText("请先在上方填写包名，例如 com.oplus.ocar");
            return;
        }
        final String pkgFinal = pkg;
        final String actFinal = activityInput.getText().toString().trim();

        LogBus.log("一键流程：先在后台等 Car+ 的 7 个端口（最长 30s），再拉起 Car+");
        launchResult.setText("已在后台等待 Car+ 端口，约 0.3 秒后自动拉起 Car+…\n"
                + "弹出 Car+ 后请停留其中别退回桌面。\n"
                + "看下方「运行日志」是否出现「七个通道全部连通」。");
        Toast.makeText(this, "后台已开始等端口，马上拉起 Car+", Toast.LENGTH_SHORT).show();

        session.startCarLife(this, null);

        handler.postDelayed(() -> {
            if (!session.isActive()) return;
            launchCarPlus(pkgFinal, actFinal, "第 1 次", 0);
        }, 300);
        // 之后只在「还在等端口」时补拉，且每次换一个疑似入口，避免把已握手的会话打断
        handler.postDelayed(() -> retryLaunchCarPlus(pkgFinal, actFinal, "第 2 次", 1), 4500);
        handler.postDelayed(() -> retryLaunchCarPlus(pkgFinal, actFinal, "第 3 次", 2), 9000);
    }

    private void retryLaunchCarPlus(String pkg, String act, String round, int index) {
        if (!session.isActive()) return;
        // 已经连上或已出画就别再拉，免得把进行中的握手打断
        String phase = session.sourcePhase();
        if (phase.contains("连通") || phase.contains("画布") || previewFrames > 0) return;
        launchCarPlus(pkg, act, round, index);
    }

    /** 在后台解析入口并拉起 Car+，避免主线程做 PackageManager 查询。 */
    private void launchCarPlus(String pkg, String act, String round, int index) {
        new Thread(() -> {
            // 每次补拉换一个疑似入口，重复拉同一个类名没有意义
            String target = (index == 0 && act != null && !act.isEmpty()) ? act : null;
            if (target == null) target = CarAppLauncher.candidateAt(this, pkg, index);
            if (target == null) target = CarAppLauncher.bestCandidate(this, pkg);
            if (target == null) {
                LogBus.log(round + "：枚举不到疑似入口，改为全量尝试拉起 " + pkg);
                CarAppLauncher.launch(this, pkg, null);
                return;
            }
            LogBus.log(round + "拉起 " + pkg + " / " + target);
            String err = CarAppLauncher.launchExact(this, pkg, target);
            if (err != null) {
                LogBus.log(round + "失败（" + target + "）：" + err + "，继续用其它入口试");
            }
        }, "carplus-launch").start();
    }

    /** 包装点击事件：任何异常都记录并在界面提示，避免直接闪退。 */
    private View.OnClickListener safe(String label, Runnable action) {
        return v -> {
            try {
                action.run();
            } catch (Throwable e) {
                report(label, e);
            }
        };
    }

    private void report(String label, Throwable e) {
        LogBus.log(label + " 出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        String[] lines = writer.toString().split("\n");
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            LogBus.log(lines[i]);
        }
        try {
            Toast.makeText(this, label + " 出错：" + e.getClass().getSimpleName()
                    + "（详情见下方运行日志）", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        render();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(6);
        return lp;
    }

    private void detectInstalled() {
        List<CarAppLauncher.Target> installed = CarAppLauncher.installed(this);
        if (installed.isEmpty()) {
            launchResult.setText("未检测到已知车机互联 App。可在系统设置里查看包名后手动填写。");
            return;
        }
        StringBuilder sb = new StringBuilder("已安装：");
        for (CarAppLauncher.Target t : installed) {
            sb.append("\n· ").append(t.label).append("  ").append(t.packageName);
        }
        launchResult.setText(sb.toString());
    }

    /* ---------------- 投屏控制卡 ---------------- */

    private void addControlCard(LinearLayout root) {
        LinearLayout card = card("② 投屏控制");

        startButton = button("开始投屏（录屏）", "#0EA5E9");
        startButton.setOnClickListener(safe("开始投屏", () -> {
            if (VehicleProfile.autoLaunch(this)) {
                doLaunch();
            }
            requestCastPermission();
        }));
        card.addView(startButton);

        carlifeButton = button("CarLife 直连（自动拉起 Car+ 并抢端口）", "#14B8A6");
        // 走一键流程，而不是「先 doLaunch 再直连」：顺序反了就必然错过 Car+ 的端口窗口。
        carlifeButton.setOnClickListener(safe("CarLife 投屏", this::startCarlifeWithLaunch));
        card.addView(carlifeButton);

        // 关键按钮：先让 CarLifeLink 在 7 个端口上等，再去拉起 Car+。
        // Car+ 的端口窗口只有一两秒，手动「先拉起再点直连」必然错过。
        oneKeyButton = button("一键：拉起 Car+ 并直连（推荐先试这个）", "#F59E0B");
        oneKeyButton.setOnClickListener(safe("拉起并直连", this::startCarlifeWithLaunch));
        card.addView(oneKeyButton);

        testButton = button("用测试图验证出画（无需录屏授权）", "#8B5CF6");
        testButton.setOnClickListener(safe("测试图投屏", () -> session.startTest(this, null)));
        card.addView(testButton);

        stopButton = button("停止投屏", "#475569");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(safe("停止投屏", () -> stopService(
                new Intent(this, CastService.class).setAction(CastService.ACTION_STOP))));
        card.addView(stopButton);

        TextView hint = new TextView(this);
        hint.setText("排查顺序（每步都看下方「运行日志」和「本地预览」）：\n"
                + "① 点「用测试图验证出画」——不需要仪表也不需要 Car+，本地预览应出现彩条。\n"
                + "    出彩条 = 编码/解码/预览这条链路没问题；不出 = 问题在这一段，先别管车机。\n"
                + "② 点任意一个 CarLife 按钮（两个都是「先等端口再拉起 Car+」）。\n"
                + "    日志出现「七个通道全部连通」才算抢到窗口；一直「第 N 次…」就是没抢到。\n"
                + "③ 预览出画面后再去管极核：连上仪表 Wi-Fi 重试，日志会显示仪表输出是否就绪。");
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor(MUTED));
        hint.setLineSpacing(0, 1.35f);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        root.addView(card);
    }

    /* ---------------- 状态卡 ---------------- */

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = card("实时状态");

        stateValue = new TextView(this);
        stateValue.setTextSize(20f);
        stateValue.setTypeface(null, Typeface.BOLD);
        stateValue.setTextColor(Color.parseColor(MUTED));
        stateValue.setPadding(0, dp(4), 0, 0);
        card.addView(stateValue);

        stateHint = new TextView(this);
        stateHint.setTextSize(12f);
        stateHint.setTextColor(Color.parseColor(MUTED));
        stateHint.setPadding(0, dp(6), 0, 0);
        card.addView(stateHint);

        detailValue = new TextView(this);
        detailValue.setTextSize(11f);
        detailValue.setTypeface(Typeface.MONOSPACE);
        detailValue.setTextColor(Color.parseColor(MUTED));
        detailValue.setPadding(0, dp(8), 0, 0);
        card.addView(detailValue);

        root.addView(card);
    }

    /* ---------------- 本地预览卡 ---------------- */

    private void addPreviewCard(LinearLayout root) {
        LinearLayout card = card("Car+ 本地预览（不接极核也能验证出画）");

        previewView = new PreviewView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        lp.topMargin = dp(10);
        previewView.setLayoutParams(lp);
        card.addView(previewView);

        previewHint = new TextView(this);
        previewHint.setTextSize(11f);
        previewHint.setTypeface(Typeface.MONOSPACE);
        previewHint.setTextColor(Color.parseColor(MUTED));
        previewHint.setPadding(0, dp(8), 0, 0);
        previewHint.setText("尚未收到 Car+ 画面。CarLife 链路一旦收到帧，这里会直接显示。");
        card.addView(previewHint);

        root.addView(card);
    }

    /** 接好「CarLife 帧 → 本地 H.264 解码 → 预览位图」的通路。 */
    private void setupPreviewDecoder() {
        decoder = new H264Decoder(new H264Decoder.Listener() {
            @Override
            public void onDecoderReady(int w, int h) {
                handler.post(() -> {
                    if (previewView != null) previewView.setAspect(w, h);
                });
            }

            @Override
            public void onFrameRendered(long totalFrames, int luminance) {
                previewFrames = (int) totalFrames;
                previewLuma = luminance;
            }

            @Override
            public void onBitmap(android.graphics.Bitmap bitmap) {
                PreviewView view = previewView;
                if (view != null) view.setFrame(bitmap);
            }

            @Override
            public void onDecoderError(String message) {
                handler.post(() -> {
                    if (previewHint != null) previewHint.setText("本地预览解码失败：" + message);
                });
            }
        });
        session.setFrameTap(this::onSourceFrame);
    }

    /** 采集线程回调：把 Annex-B 帧喂给本地解码器。 */
    private void onSourceFrame(VideoFrame frame) {
        H264Decoder d = decoder;
        if (d == null || frame == null || frame.accessUnit == null) return;
        VehicleProfile p = session.profile();
        int w = p == null ? 1024 : p.width;
        int h = p == null ? 480 : p.height;
        d.feed(frame.accessUnit, 0, frame.accessUnit.length, frame.ptsMs * 1000L, w, h);
    }

    /* ---------------- 帧率统计卡 ---------------- */

    private void addStatsCard(LinearLayout root) {
        LinearLayout card = card("帧率与码率（每秒刷新）");
        statsValue = new TextView(this);
        statsValue.setTextSize(12f);
        statsValue.setTypeface(Typeface.MONOSPACE);
        statsValue.setTextColor(Color.parseColor(GREEN));
        statsValue.setPadding(0, dp(2), 0, 0);
        card.addView(statsValue);
        root.addView(card);
    }

    /* ---------------- 车型配置卡 ---------------- */

    private void addVehicleCard(LinearLayout root) {
        LinearLayout card = card("③ 车型与调试选项");

        profileSpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (VehicleProfile p : profiles) labels.add(p.shortLabel() + " [" + p.status + "]");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        card.addView(profileSpinner);

        widthInput = input("宽度（px）", String.valueOf(session.profile().width));
        heightInput = input("高度（px）", String.valueOf(session.profile().height));
        fpsInput = input("帧率（FPS）", String.valueOf(session.profile().fps));
        bitrateInput = input("码率（kbps）", String.valueOf(session.profile().bitrateKbps));
        discoveryPortInput = input("发现端口", String.valueOf(session.profile().discoveryPort));
        targetIpInput = input("仪表 IP（可留空自动发现）", VehicleProfile.targetIp(this));
        card.addView(widthInput);
        card.addView(heightInput);
        card.addView(fpsInput);
        card.addView(bitrateInput);
        card.addView(discoveryPortInput);
        card.addView(targetIpInput);

        int selected = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(session.profile().id)) selected = i;
        }
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyProfileToFields(profiles.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        profileSpinner.setSelection(selected);

        TextView patternLabel = new TextView(this);
        patternLabel.setText("测试图模式：");
        patternLabel.setTextSize(12f);
        patternLabel.setTextColor(Color.parseColor(TEXT));
        patternLabel.setPadding(0, dp(8), 0, dp(2));
        card.addView(patternLabel);

        patternSpinner = new Spinner(this);
        String[] patternNames = {"关闭（用真实屏幕）", "彩条", "棋盘格", "运动帧"};
        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, patternNames);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        patternSpinner.setAdapter(pAdapter);
        patternSpinner.setSelection(session.testPattern());
        card.addView(patternSpinner);

        Button saveBtn = button("保存配置（下次投屏生效）", "#0D9488");
        saveBtn.setOnClickListener(safe("保存配置", this::saveConfig));
        card.addView(saveBtn);

        TextView warn = new TextView(this);
        VehicleProfile p = session.profile();
        warn.setText("当前车型协议：" + p.codecName() + " / " + p.transportName()
                + "\n状态：" + p.status + " — " + p.note
                + "\n注：非极核车型的协议栈尚未实现，参数仅用于调试与取证。");
        warn.setTextSize(11f);
        warn.setTextColor(Color.parseColor(p.implemented ? GREEN : AMBER));
        warn.setPadding(0, dp(8), 0, 0);
        warn.setLineSpacing(0, 1.3f);
        card.addView(warn);

        root.addView(card);
    }

    private void applyProfileToFields(VehicleProfile p) {
        widthInput.setText(String.valueOf(p.width));
        heightInput.setText(String.valueOf(p.height));
        fpsInput.setText(String.valueOf(p.fps));
        bitrateInput.setText(String.valueOf(p.bitrateKbps));
        discoveryPortInput.setText(String.valueOf(p.discoveryPort));
    }

    private void saveConfig() {
        VehicleProfile p = profiles.get(profileSpinner.getSelectedItemPosition());
        try {
            p.width = parseInt(widthInput, p.width);
            p.height = parseInt(heightInput, p.height);
            p.fps = parseInt(fpsInput, p.fps);
            p.bitrateKbps = parseInt(bitrateInput, p.bitrateKbps);
            p.discoveryPort = parseInt(discoveryPortInput, p.discoveryPort);
            p.save(this);
            VehicleProfile.setTargetIp(this, targetIpInput.getText().toString().trim());
            VehicleProfile.setTestPattern(this, patternSpinner.getSelectedItemPosition());
            session.configure(this);
            if (p.implemented) {
                launchResult.setText("已保存：" + p.shortLabel() + " 协议已实现。");
            } else {
                launchResult.setText("已保存：" + p.shortLabel() + " 协议待实现，参数仅用于调试。");
            }
            LogBus.log("配置已保存 " + p.id + " " + p.width + "×" + p.height
                    + "@" + p.fps + " discovery=" + p.discoveryPort);
        } catch (NumberFormatException e) {
            launchResult.setText("参数格式错误，请检查数字输入。");
        }
    }

    private int parseInt(EditText field, int fallback) {
        String s = field.getText().toString().trim();
        return s.isEmpty() ? fallback : Integer.parseInt(s);
    }

    /* ---------------- 日志卡 ---------------- */

    private void addLogCard(LinearLayout root) {
        LinearLayout card = card("运行日志");
        logValue = new TextView(this);
        logValue.setTextSize(11f);
        logValue.setTypeface(Typeface.MONOSPACE);
        logValue.setTextColor(Color.parseColor(MUTED));
        logValue.setPadding(0, dp(4), 0, 0);
        logValue.setTextIsSelectable(true);
        card.addView(logValue);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copyBtn = button("复制日志", "#0EA5E9");
        copyBtn.setOnClickListener(safe("复制日志", this::copyLog));
        Button clearBtn = button("清空日志", "#475569");
        clearBtn.setOnClickListener(safe("清空日志", this::clearLog));
        copyBtn.setLayoutParams(rowParams());
        clearBtn.setLayoutParams(rowParams());
        row.addView(copyBtn);
        row.addView(clearBtn);
        card.addView(row);
        root.addView(card);
    }

    /** 把运行日志复制到剪贴板，方便没有 adb 时把崩溃信息发回。 */
    private void copyLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : LogBus.lines()) {
            sb.append(line).append('\n');
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "当前系统不支持剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("CarWithin 日志", sb.toString()));
        Toast.makeText(this, "已复制 " + LogBus.lines().size() + " 行日志", Toast.LENGTH_SHORT).show();
    }

    private void clearLog() {
        LogBus.clear();
        CrashGuard.clearLastCrash(this);
        render();
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("v" + appVersion() + "  ·  AGPL-3.0\n独立社区项目 · 与厂商无官方关联");
        footer.setTextSize(11f);
        footer.setTextColor(Color.parseColor(BORDER));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
    }

    /* ---------------- 权限与投屏控制 ---------------- */

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        }
    }

    private void requestCastPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_CAST_PERMISSION);
            return;
        }
        launchProjectionConsent();
    }

    private void launchProjectionConsent() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            LogBus.log("系统不支持屏幕采集");
            return;
        }
        // 只弹录屏授权；前台服务要等拿到令牌后再启动（Android 14 的顺序要求）。
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAST_PERMISSION);
        } catch (Throwable e) {
            report("请求录屏授权", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAST_PERMISSION) return;
        if (resultCode != RESULT_OK || data == null) {
            LogBus.log("用户取消录屏授权");
            render();
            return;
        }
        try {
            // 录屏令牌交给前台服务：Android 14+ 必须先启动 mediaProjection 类型前台服务，
            // 再调用 getMediaProjection()，顺序在 CastService 内部保证。
            Intent service = new Intent(this, CastService.class)
                    .setAction(CastService.ACTION_START)
                    .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CastService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            LogBus.log("已提交录屏授权，正在启动前台服务");
        } catch (Throwable e) {
            report("启动录屏服务", e);
        }
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAST_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchProjectionConsent();
        }
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        String status = session.status();
        stateValue.setText(status);
        stateHint.setText(hintFor(status));
        stateValue.setTextColor(Color.parseColor(colorFor(status)));
        detailValue.setText(session.detail());
        statsValue.setText(FrameStats.get().report());

        boolean active = session.isActive();
        startButton.setEnabled(!active);
        carlifeButton.setEnabled(!active);
        oneKeyButton.setEnabled(!active);
        testButton.setEnabled(!active);
        stopButton.setEnabled(active);

        List<String> lines = LogBus.lines();
        StringBuilder builder = new StringBuilder();
        int from = Math.max(0, lines.size() - 20);
        for (int i = from; i < lines.size(); i++) {
            builder.append(lines.get(i)).append('\n');
        }
        logValue.setText(builder.length() == 0 ? "暂无日志。" : builder.toString());

        renderPreviewHint();
    }

    private void renderPreviewHint() {
        if (previewHint == null) return;
        String push = session.outputReady() ? "推极核：开" : "推极核：关（仅本地预览）";
        if (previewFrames > 0) {
            previewHint.setText("已解码 " + previewFrames + " 帧 · 平均亮度 " + previewLuma + " · " + push);
        } else if (!session.isActive()) {
            previewHint.setText("尚未收到 Car+ 画面。CarLife 链路一旦收到帧，这里会直接显示。");
        } else if (decoder != null && decoder.isStarted()) {
            previewHint.setText("解码器已就绪，等待首个关键帧… · " + push);
        } else {
            previewHint.setText("等待 SPS/PPS（首个关键帧）… · " + push);
        }
    }

    private static String hintFor(String status) {
        if ("STREAMING".equals(status)) return "视频流已推送至仪表，操作请在手机上完成。";
        if ("RUNNING_LOCAL_ONLY".equals(status)) {
            return "仪表未连上，当前只在本地预览。预览卡出画说明「采集→编码→解码→显示」这段是通的（测试图也走同一段）；连上仪表 Wi-Fi 后会自动继续推流。";
        }
        if ("RUNNING".equals(status)) return "采集已启动，等待首帧。";
        if (status.startsWith("FRAME_ERROR")) return "推帧中断，本地预览仍在继续，请看下方日志。";
        if (status.startsWith("ERROR")) return "发生错误，请查看下方运行日志与 docs/TEST-PLAN.md。";
        if (status.startsWith("CARLIFE")) {
            return "CarLife 链路：正在等 Car+ 在 127.0.0.1 打开 7 个端口。"
                    + "用②的「一键：拉起 Car+ 并直连」成功率最高——它先等端口再拉起 Car+，抢那一两秒的窗口。";
        }
        if ("STARTING".equals(status) || "WAITING_DASHBOARD".equals(status)) {
            return "正在与仪表协商，请确认仪表已开启投屏且未连其他手机。";
        }
        return "尚未开始。可先用测试图验证出画，再选择录屏或 CarLife 投屏。";
    }

    private static String colorFor(String status) {
        if ("STREAMING".equals(status)) return GREEN;
        if ("RUNNING_LOCAL_ONLY".equals(status) || "RUNNING".equals(status)) return GREEN;
        if (status.startsWith("ERROR") || status.startsWith("FRAME_ERROR")) return RED;
        if ("IDLE".equals(status)) return MUTED;
        return AMBER;
    }

    /* ---------------- 工具 ---------------- */

    /** 从包管理器读取构建时注入的真实版本号，避免硬编码导致界面版本不更新。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(CARD));
        bg.setCornerRadius(dp(16));
        bg.setStroke(1, Color.parseColor(BORDER));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(12f);
        label.setTypeface(null, Typeface.BOLD);
        label.setTextColor(Color.parseColor(ACCENT));
        card.addView(label);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private EditText input(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setTextColor(Color.parseColor(TEXT));
        edit.setHintTextColor(Color.parseColor(BORDER));
        edit.setTextSize(13f);
        edit.setSingleLine(true);
        edit.setBackgroundResource(android.R.drawable.edit_text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        edit.setLayoutParams(lp);
        return edit;
    }

    private Button button(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14f);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
