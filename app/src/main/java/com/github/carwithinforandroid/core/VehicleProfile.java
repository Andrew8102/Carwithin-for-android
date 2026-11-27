package com.github.carwithinforandroid.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 车型配置。数据驱动：每个预设描述分辨率、编码、传输与端口。
 * 只有极核 PXC/TCP 输出栈已实现，其他车型为取证/调试预设，UI 会明确标注状态。
 */
public final class VehicleProfile {

    public static final int CODEC_H264 = 0;
    public static final int CODEC_JPEG = 1;

    public static final int TRANSPORT_PXC_TCP = 0;   // 极核：10930 发现 / 10922 控制 / 10711 辅助 / 10710 视频
    public static final int TRANSPORT_RTP_UDP = 1;   // 九号：UDP 5012 视频 / 5013 控制
    public static final int TRANSPORT_JPEG_TCP = 2;  // 台铃：TCP + JPEG
    public static final int TRANSPORT_KCP = 3;       // 九号七寸实验

    public final String id;
    public final String brand;
    public final String name;
    public int width;
    public int height;
    public int fps;
    public int bitrateKbps;
    public int jpegQuality;
    public int codec;
    public int transport;
    public int discoveryPort;
    public boolean implemented;
    public String status;
    public String note;

    private VehicleProfile(String id, String brand, String name, int width, int height,
                           int fps, int bitrateKbps, int jpegQuality, int codec, int transport,
                           int discoveryPort, boolean implemented, String status, String note) {
        this.id = id;
        this.brand = brand;
        this.name = name;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.jpegQuality = jpegQuality;
        this.codec = codec;
        this.transport = transport;
        this.discoveryPort = discoveryPort;
        this.implemented = implemented;
        this.status = status;
        this.note = note;
    }

    /** 内置预设。参数来自 ninebot_pro README 的公开规格；小牛为通用占位，需实车取证。 */
    public static List<VehicleProfile> presets() {
        List<VehicleProfile> list = new ArrayList<>(Arrays.asList(
                new VehicleProfile("zeeho_ae4m2", "极核", "AE4 MAX2+ 6.2寸",
                        1024, 480, 25, 2500, 80, CODEC_H264, TRANSPORT_PXC_TCP, 10930,
                        true, "已验证", "H.264/TCP，一台实车出画；当前唯一完整实现的输出协议"),
                new VehicleProfile("ninebot_5", "九号", "5寸 848×480",
                        848, 480, 20, 2000, 80, CODEC_H264, TRANSPORT_RTP_UDP, 5012,
                        false, "待移植", "H.264/RTP(UDP 5012/5013)，ninebot_pro 已在 KZ110 验证"),
                new VehicleProfile("ninebot_7", "九号", "7寸 1024×600（实验）",
                        1024, 600, 25, 2000, 80, CODEC_H264, TRANSPORT_KCP, 5012,
                        false, "实验", "H.264/KCP，ninebot_pro 标为实验功能"),
                new VehicleProfile("tailing_43", "台铃", "4.3寸 480×272",
                        480, 272, 20, 0, 80, CODEC_JPEG, TRANSPORT_JPEG_TCP, 0,
                        false, "待移植", "JPEG/TCP，按仪表请求发完整 JPEG，质量可调"),
                new VehicleProfile("niu_generic", "小牛", "通用占位（待取证）",
                        960, 540, 25, 2500, 80, CODEC_H264, TRANSPORT_PXC_TCP, 10930,
                        false, "待取证", "小牛协议暂无公开资料，参数仅为占位，需抓包取证后调整"),
                new VehicleProfile("custom", "自定义", "自定义参数",
                        1024, 480, 25, 2500, 80, CODEC_H264, TRANSPORT_PXC_TCP, 10930,
                        false, "自定义", "手动填写全部分辨率与端口参数")
        ));
        return list;
    }

    public static VehicleProfile byId(String id) {
        for (VehicleProfile p : presets()) if (p.id.equals(id)) return p;
        return presets().get(0);
    }

    public String codecName() {
        return codec == CODEC_JPEG ? "JPEG" : "H.264";
    }

    public String transportName() {
        switch (transport) {
            case TRANSPORT_RTP_UDP: return "RTP/UDP";
            case TRANSPORT_JPEG_TCP: return "JPEG/TCP";
            case TRANSPORT_KCP: return "KCP";
            default: return "PXC/TCP";
        }
    }

    public String shortLabel() {
        return brand + " · " + name;
    }

    private static final String PREFS = "cw_profile";
    private static final String KEY_ID = "id";
    private static final String KEY_W = "width";
    private static final String KEY_H = "height";
    private static final String KEY_FPS = "fps";
    private static final String KEY_BITRATE = "bitrate";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_PORT = "discovery_port";
    private static final String KEY_IP = "target_ip";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_AUTO_LAUNCH = "auto_launch";
    private static final String KEY_LAUNCH_PKG = "launch_pkg";

    /** 载入当前配置，覆盖预设的可调字段。 */
    public static VehicleProfile load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        VehicleProfile base = byId(sp.getString(KEY_ID, "zeeho_ae4m2"));
        base.width = sp.getInt(prefix(base) + KEY_W, base.width);
        base.height = sp.getInt(prefix(base) + KEY_H, base.height);
        base.fps = sp.getInt(prefix(base) + KEY_FPS, base.fps);
        base.bitrateKbps = sp.getInt(prefix(base) + KEY_BITRATE, base.bitrateKbps);
        base.jpegQuality = sp.getInt(prefix(base) + KEY_QUALITY, base.jpegQuality);
        base.discoveryPort = sp.getInt(prefix(base) + KEY_PORT, base.discoveryPort);
        return base;
    }

    public void save(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putString(KEY_ID, id);
        e.putInt(prefix(this) + KEY_W, width);
        e.putInt(prefix(this) + KEY_H, height);
        e.putInt(prefix(this) + KEY_FPS, fps);
        e.putInt(prefix(this) + KEY_BITRATE, bitrateKbps);
        e.putInt(prefix(this) + KEY_QUALITY, jpegQuality);
        e.putInt(prefix(this) + KEY_PORT, discoveryPort);
        e.apply();
    }

    private static String prefix(VehicleProfile p) {
        return p.id + "_";
    }

    public static String targetIp(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IP, "");
    }

    public static void setTargetIp(Context context, String ip) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_IP, ip).apply();
    }

    public static int testPattern(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PATTERN, 0);
    }

    public static void setTestPattern(Context context, int pattern) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PATTERN, pattern).apply();
    }

    public static boolean autoLaunch(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LAUNCH, false);
    }

    public static void setAutoLaunch(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_LAUNCH, value).apply();
    }

    public static String launchPackage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAUNCH_PKG, "com.oplus.ocar");
    }

    public static void setLaunchPackage(Context context, String pkg) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAUNCH_PKG, pkg).apply();
    }
}
