package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 极核连接相关的持久化配置。
 *
 * <p>为什么要存仪表热点：CarWithin（iOS 参考实现）根本没有组网代码，它的前提就是
 * 「iPhone 先加入仪表 Wi-Fi」，然后 App 只在应用层做 PXC 握手。Android 上同理 ——
 * 极核仪表在投屏页会开一个普通热点，只要知道 SSID/密码，用
 * {@code WifiNetworkSpecifier} 就能连上，不需要极核 App，也不需要 Wi-Fi Direct。
 *
 * <p>热点凭据目前只能用 adb 广播写入（现场调试优先）：
 * <pre>
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.HOTSPOT \
 *     -p com.github.carwithinforandroid -f 0x20 --es ssid "ZEEHO-XXXX" --es psk "12345678"
 * </pre>
 * 开放网络把 psk 传空字符串。
 */
public final class ZeehoPrefs {

    private static final String FILE = "zeeho_link";
    private static final String K_SSID = "hotspot_ssid";
    private static final String K_PSK = "hotspot_psk";
    private static final String K_LAST_TARGET = "last_target_ip";

    private ZeehoPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 已配置的仪表热点名，未配置返回 null。 */
    public static String hotspotSsid(Context context) {
        String value = prefs(context).getString(K_SSID, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** 仪表热点密码，开放网络返回空串；未配置热点时返回 null。 */
    public static String hotspotPsk(Context context) {
        if (hotspotSsid(context) == null) return null;
        String value = prefs(context).getString(K_PSK, "");
        return value == null ? "" : value.trim();
    }

    public static boolean hasHotspot(Context context) {
        return hotspotSsid(context) != null;
    }

    /** @param psk 为空表示开放网络（无密码）。 */
    public static void saveHotspot(Context context, String ssid, String psk) {
        prefs(context).edit()
                .putString(K_SSID, ssid == null ? "" : ssid.trim())
                .putString(K_PSK, psk == null ? "" : psk.trim())
                .apply();
    }

    public static void clearHotspot(Context context) {
        prefs(context).edit().remove(K_SSID).remove(K_PSK).apply();
    }

    /** 上次握手成功的仪表地址，用来跳过整段扫描。 */
    public static String lastTarget(Context context) {
        String value = prefs(context).getString(K_LAST_TARGET, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static void saveLastTarget(Context context, String ip) {
        if (ip == null || ip.trim().isEmpty()) return;
        prefs(context).edit().putString(K_LAST_TARGET, ip.trim()).apply();
    }

    public static void clearLastTarget(Context context) {
        prefs(context).edit().remove(K_LAST_TARGET).apply();
    }

    /** 一行摘要，打日志用。 */
    public static String describe(Context context) {
        String ssid = hotspotSsid(context);
        String psk = hotspotPsk(context);
        String target = lastTarget(context);
        return "热点=" + (ssid == null ? "未配置"
                : ssid + (psk == null || psk.isEmpty() ? "(开放)" : "(有密码 " + psk.length() + " 位)"))
                + " 上次仪表=" + (target == null ? "无" : target);
    }
}
