package com.github.carwithinforandroid;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.carwithinforandroid.core.CastEngine;

/** 状态面板：展示桥接引擎状态、链路结构与各协议模块的当前进度。 */
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

    private final CastEngine engine = new CastEngine();
    private TextView stateValue;
    private TextView stateHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(BG));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addStatusCard(root);
        addChainCard(root);
        addSourcesCard(root);
        addOutputCard(root);
        addHintCard(root);
        addFooter(root);

        setContentView(scroll);
        render();
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
        subtitle.setText("安卓互联  →  极核 AE4 MAX2+ 仪表");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(Color.parseColor(ACCENT));
        subtitle.setPadding(0, dp(4), 0, 0);
        root.addView(subtitle);

        TextView desc = new TextView(this);
        desc.setText("把手机互联协议的画面桥接到仪表 · 社区实验项目");
        desc.setTextSize(12f);
        desc.setTextColor(Color.parseColor(MUTED));
        desc.setPadding(0, dp(6), 0, 0);
        root.addView(desc);
    }

    /* ---------------- 状态卡片 ---------------- */

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = card("引擎状态");

        stateValue = new TextView(this);
        stateValue.setTextSize(24f);
        stateValue.setTypeface(null, Typeface.BOLD);
        stateValue.setTextColor(Color.parseColor(MUTED));
        stateValue.setPadding(0, dp(6), 0, 0);
        card.addView(stateValue);

        stateHint = new TextView(this);
        stateHint.setTextSize(12f);
        stateHint.setTextColor(Color.parseColor(MUTED));
        stateHint.setPadding(0, dp(4), 0, 0);
        card.addView(stateHint);

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setTextColor(Color.WHITE);
        refresh.setTextSize(14f);
        refresh.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0284C7"));
        bg.setCornerRadius(dp(10));
        refresh.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        refresh.setLayoutParams(lp);
        refresh.setOnClickListener(v -> render());
        card.addView(refresh);

        root.addView(card);
    }

    /* ---------------- 链路卡片 ---------------- */

    private void addChainCard(LinearLayout root) {
        LinearLayout card = card("投屏链路");

        addChainNode(card, "①", "手机互联来源", "Car+ / CarWith / HiCar", MUTED);
        addChainArrow(card);
        addChainNode(card, "②", "H.264 视频帧", "CastEngine 桥接与转码", MUTED);
        addChainArrow(card);
        addChainNode(card, "③", "极核仪表输出", "PXC/TCP 四端口 · 1024×480@25", ACCENT);

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

    /* ---------------- 来源卡片 ---------------- */

    private void addSourcesCard(LinearLayout root) {
        LinearLayout card = card("手机互联来源");

        addStatusRow(card, "OPPO Car+", "CarLife 七端口 · 待移植", AMBER);
        addStatusRow(card, "ICCOA CarLink", "小米 / vivo · 需专有 SDK", AMBER);
        addStatusRow(card, "华为 HiCar", "规划中", MUTED);

        root.addView(card);
    }

    /* ---------------- 输出卡片 ---------------- */

    private void addOutputCard(LinearLayout root) {
        LinearLayout card = card("仪表输出");

        addStatusRow(card, "极核 AE4 MAX2+ 6.2 寸", "协议骨架已落地 · 待真机验证", GREEN);
        addStatusRow(card, "视频规格", "H.264 · 1024×480 · 25 FPS", TEXT);
        addStatusRow(card, "控制端口", "10930 / 10922 / 10711 / 10710", MUTED);
        addStatusRow(card, "触摸反控", "老协议不支持", MUTED);

        root.addView(card);
    }

    /* ---------------- 提示卡片 ---------------- */

    private void addHintCard(LinearLayout root) {
        LinearLayout card = card("本机测试提示");

        TextView hint = new TextView(this);
        hint.setText("当前为骨架版本：可安装、可查看状态，但不会真正投屏。\n"
                + "投屏需在仪表上手动开启；手机需与仪表处于同一 Wi-Fi。\n"
                + "真机日志与抓包请按 docs/TEST-PLAN.md 脱敏后回传。");
        hint.setTextSize(12f);
        hint.setTextColor(Color.parseColor(MUTED));
        hint.setLineSpacing(0, 1.35f);
        hint.setPadding(0, dp(6), 0, dp(2));
        card.addView(hint);

        root.addView(card);
    }

    /* ---------------- 底部 ---------------- */

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("v0.0.1-scaffold  ·  AGPL-3.0\n独立社区项目 · 与厂商无官方关联");
        footer.setTextSize(11f);
        footer.setTextColor(Color.parseColor(BORDER));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(20), 0, 0);
        root.addView(footer);
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        String status = engine.status();
        stateValue.setText(status);
        String hint;
        String color;
        if ("RUNNING".equals(status)) {
            hint = "引擎运行中。真机出画仍需协议协商校正。";
            color = GREEN;
        } else if (status.startsWith("ERROR") || status.startsWith("FRAME_ERROR")) {
            hint = "发生错误，请查看 logcat 与 docs/TEST-PLAN.md。";
            color = RED;
        } else if ("STREAMING".equals(status)) {
            hint = "视频流已推送至仪表。";
            color = GREEN;
        } else {
            hint = "尚未开始投屏。CarLife 来源与极核输出正在移植中。";
            color = AMBER;
        }
        stateHint.setText(hint);
        stateValue.setTextColor(Color.parseColor(color));
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
