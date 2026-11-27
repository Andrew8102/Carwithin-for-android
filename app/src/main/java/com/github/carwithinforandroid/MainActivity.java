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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.carwithinforandroid.core.CastSession;
import com.github.carwithinforandroid.core.LogBus;

import java.util.List;

/** 投屏控制台：授权录屏、连接极核仪表、实时查看链路状态。 */
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

    private TextView stateValue;
    private TextView stateHint;
    private TextView detailValue;
    private TextView logValue;
    private Button startButton;
    private Button stopButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addControlCard(root);
        addStatusCard(root);
        addChainCard(root);
        addOutputCard(root);
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
        title.setTextSize(30f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor(TEXT));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("手机屏幕  →  极核 AE4 MAX2+ 仪表");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(Color.parseColor(ACCENT));
        subtitle.setPadding(0, dp(4), 0, 0);
        root.addView(subtitle);

        TextView desc = new TextView(this);
        desc.setText("录制屏幕内容并投到仪表 · 1024×480 · 社区实验项目");
        desc.setTextSize(12f);
        desc.setTextColor(Color.parseColor(MUTED));
        desc.setPadding(0, dp(6), 0, dp(12));
        root.addView(desc);
    }

    /* ---------------- 控制卡片 ---------------- */

    private void addControlCard(LinearLayout root) {
        LinearLayout card = card("投屏控制");

        startButton = button("开始投屏", "#0EA5E9");
        startButton.setOnClickListener(v -> requestCastPermission());
        card.addView(startButton);

        stopButton = button("停止投屏", "#475569");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP)));
        card.addView(stopButton);

        TextView hint = new TextView(this);
        hint.setText("开始前：① 仪表或官方 App 开启投屏  ② 手机连接仪表 Wi-Fi  ③ 允许本应用录屏");
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor(MUTED));
        hint.setLineSpacing(0, 1.35f);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        root.addView(card);
    }

    private Button button(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15f);
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

    /* ---------------- 状态卡片 ---------------- */

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = card("实时状态");

        stateValue = new TextView(this);
        stateValue.setTextSize(22f);
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

    private void addChainCard(LinearLayout root) {
        LinearLayout card = card("投屏链路");

        addChainNode(card, "①", "屏幕采集", "MediaProjection · 录制屏幕内容", ACCENT);
        addChainArrow(card);
        addChainNode(card, "②", "H.264 编码", "1024×480 · 25 FPS · 2500 kbps", ACCENT);
        addChainArrow(card);
        addChainNode(card, "③", "极核仪表输出", "PXC/TCP · 10930/10922/10711/10710", ACCENT);

        root.addView(card);
    }

    private void addChainNode(LinearLayout card, String number, String title, String detail, String color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(number);
        badge.setTextSize(14f);
        badge.setTextColor(Color.parseColor(color));
        badge.setTypeface(null, Typeface.BOLD);
        badge.setWidth(dp(34));
        row.addView(badge);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14f);
        t.setTextColor(Color.parseColor(TEXT));
        texts.addView(t);
        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextSize(11f);
        d.setTextColor(Color.parseColor(MUTED));
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
    }

    private void addChainArrow(LinearLayout card) {
        TextView arrow = new TextView(this);
        arrow.setText("↓");
        arrow.setTextSize(16f);
        arrow.setTextColor(Color.parseColor(BORDER));
        arrow.setPadding(dp(12), dp(2), 0, dp(2));
        card.addView(arrow);
    }

    private void addOutputCard(LinearLayout root) {
        LinearLayout card = card("仪表与协议");

        addStatusRow(card, "目标仪表", "极核 AE4 MAX2+ 6.2 寸", GREEN);
        addStatusRow(card, "视频规格", "H.264 · 1024×480 · 25 FPS", TEXT);
        addStatusRow(card, "握手方式", "RSA2048 身份 · HUID 签名回显", MUTED);
        addStatusRow(card, "触摸反控", "老协议不支持，操作在手机上完成", MUTED);
        addStatusRow(card, "兼容性", "其他极核型号 / 固件需独立验证", AMBER);

        root.addView(card);
    }

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
        footer.setText("v0.1.0  ·  AGPL-3.0\n独立社区项目 · 与厂商无官方关联");
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

        boolean active = session.isActive();
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        List<String> lines = LogBus.lines();
        StringBuilder builder = new StringBuilder();
        int from = Math.max(0, lines.size() - 9);
        for (int i = from; i < lines.size(); i++) {
            builder.append(lines.get(i)).append('\n');
        }
        logValue.setText(builder.length() == 0 ? "暂无日志，点击「开始投屏」开始。" : builder.toString());
    }

    private static String hintFor(String status) {
        if ("STREAMING".equals(status)) return "视频流已推送至仪表，操作请在手机上完成。";
        if (status.startsWith("ERROR")) return "发生错误，请查看下方运行日志与 docs/TEST-PLAN.md。";
        if ("STARTING".equals(status) || "WAITING_DASHBOARD".equals(status)) {
            return "正在与仪表协商，请确认仪表已开启投屏且未连其他手机。";
        }
        return "尚未开始投屏。点击上方按钮授权录屏并连接仪表。";
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

    private void addStatusRow(LinearLayout parent, String label, String value, String color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(10f);
        dot.setTextColor(Color.parseColor(color));
        dot.setPadding(0, 0, dp(8), 0);
        row.addView(dot);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13f);
        t.setTextColor(Color.parseColor(TEXT));
        texts.addView(t);
        TextView d = new TextView(this);
        d.setText(value);
        d.setTextSize(11f);
        d.setTextColor(Color.parseColor(MUTED));
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
