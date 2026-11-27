package com.github.carwithinforandroid;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
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

import com.github.carwithinforandroid.core.CastSession;
import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.VehicleProfile;
import com.github.carwithinforandroid.launcher.CarAppLauncher;
import com.github.carwithinforandroid.sources.screen.TestPatternSource;

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
    private Button stopButton;
    private Button testButton;

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
        session.configure(this);

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
        addStatsCard(root);
        addVehicleCard(root);
        addDebugCard(root);
        addLogCard(root);
        addFooter(root);
        setContentView(scroll);

        session.setListener(() -> handler.post(this::render));
        requestRuntimePermissions();
        handler.post(refresher);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresher);
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
        tip.setText("手机通常不会自动显示 Car+ / CarWith 界面。先在这里拉起它，让它在本地监听投屏端口，再开始投屏。");
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
        launchBtn.setOnClickListener(v -> doLaunch());
        Button detectBtn = button("检测安装", "#475569");
        detectBtn.setOnClickListener(v -> detectInstalled());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(8);
        launchBtn.setLayoutParams(lp1);
        detectBtn.setLayoutParams(lp2);
        btnRow.addView(launchBtn);
        btnRow.addView(detectBtn);
        card.addView(btnRow);

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
        autoLaunchCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                VehicleProfile.setAutoLaunch(this, isChecked));
        card.addView(autoLaunchCheck);

        root.addView(card);
    }

    private void doLaunch() {
        String pkg = packageInput.getText().toString().trim();
        String activity = activityInput.getText().toString().trim();
        VehicleProfile.setLaunchPackage(this, pkg);
        launchResult.setText(CarAppLauncher.launch(this, pkg, activity));
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
        startButton.setOnClickListener(v -> {
            if (VehicleProfile.autoLaunch(this)) {
                doLaunch();
            }
            requestCastPermission();
        });
        card.addView(startButton);

        testButton = button("用测试图验证出画（无需录屏授权）", "#8B5CF6");
        testButton.setOnClickListener(v -> session.startTest(this, null));
        card.addView(testButton);

        stopButton = button("停止投屏", "#475569");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopService(
                new Intent(this, CastService.class).setAction(CastService.ACTION_STOP)));
        card.addView(stopButton);

        TextView hint = new TextView(this);
        hint.setText("步骤：仪表开启投屏 → 手机连仪表 Wi-Fi → 拉起车机界面 → 开始投屏。测试图用于确认仪表能出画。");
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
        saveBtn.setOnClickListener(v -> saveConfig());
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
        card.addView(logValue);
        root.addView(card);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("v0.2.0  ·  AGPL-3.0\n独立社区项目 · 与厂商无官方关联");
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
        Intent intent = new Intent(this, CastService.class).setAction(CastService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAST_PERMISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAST_PERMISSION) return;
        if (resultCode != RESULT_OK || data == null) {
            LogBus.log("用户取消录屏授权");
            stopService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) return;
        MediaProjection projection = manager.getMediaProjection(resultCode, data);
        Intent service = new Intent(this, CastService.class).setAction(CastService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        session.start(this, projection, null);
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
        testButton.setEnabled(!active);
        stopButton.setEnabled(active);

        List<String> lines = LogBus.lines();
        StringBuilder builder = new StringBuilder();
        int from = Math.max(0, lines.size() - 8);
        for (int i = from; i < lines.size(); i++) {
            builder.append(lines.get(i)).append('\n');
        }
        logValue.setText(builder.length() == 0 ? "暂无日志。" : builder.toString());
    }

    private static String hintFor(String status) {
        if ("STREAMING".equals(status)) return "视频流已推送至仪表，操作请在手机上完成。";
        if (status.startsWith("ERROR")) return "发生错误，请查看下方运行日志与 docs/TEST-PLAN.md。";
        if ("STARTING".equals(status) || "WAITING_DASHBOARD".equals(status)) {
            return "正在与仪表协商，请确认仪表已开启投屏且未连其他手机。";
        }
        return "尚未开始。可先用测试图验证出画，再开始录屏投屏。";
    }

    private static String colorFor(String status) {
        if ("STREAMING".equals(status)) return GREEN;
        if (status.startsWith("ERROR")) return RED;
        if ("IDLE".equals(status)) return MUTED;
        return AMBER;
    }

    /* ---------------- 工具 ---------------- */

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
