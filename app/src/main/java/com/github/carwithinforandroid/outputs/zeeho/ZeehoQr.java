package com.github.carwithinforandroid.outputs.zeeho;

import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析仪表投屏二维码的内容，取出热点凭据。
 *
 * <p>实测二维码是一个下载页 URL，查询参数里带齐了组网信息：
 * <pre>
 * https://zhbackend.zeehoev.com/app_download/?modelid=49901&amp;sn=RK7C&amp;action=9
 *   &amp;ssid=ZEEHO-7ce663&amp;pwd=271639c053&amp;auth=wpa2-psk
 *   &amp;mac=DC%3A0D%3A30%3A98%3ACD%3AED&amp;name=ZEEHO-7ce663
 * </pre>
 * {@link Uri#getQueryParameter(String)} 会自动做百分号解码（%3A → :），不必手动 unescape。
 *
 * <p>顺带兼容标准 {@code WIFI:T:WPA;S:ssid;P:pass;;} 格式 —— 万一以后二维码换成标准格式，
 * 不用再改代码。
 */
public final class ZeehoQr {

    /** 解析结果。ssid/password 至少有一个非空才算有效。 */
    public static final class Result {
        public final String ssid;
        public final String password;
        public final String auth;
        public final String mac;
        public final String raw;

        Result(String ssid, String password, String auth, String mac, String raw) {
            this.ssid = ssid;
            this.password = password;
            this.auth = auth;
            this.mac = mac;
            this.raw = raw;
        }

        public boolean hasCredentials() {
            return ssid != null && !ssid.isEmpty();
        }

        /** 日志摘要：密码只显示长度，不打到 logcat 里。 */
        public String describe() {
            return "SSID=" + ssid
                    + " 密码=" + (password == null ? "无(开放)" : password.length() + " 位")
                    + " 认证=" + (auth == null ? "未知" : auth)
                    + " 仪表MAC=" + (mac == null ? "无" : mac);
        }
    }

    private ZeehoQr() {
    }

    /** 解析扫码得到的文本；不是可识别的二维码时返回 null。 */
    public static Result parse(String text) {
        if (text == null) return null;
        String raw = text.trim();
        if (raw.isEmpty()) return null;

        // ① 极核的下载页 URL
        try {
            Uri uri = Uri.parse(raw);
            if (uri.getQueryParameterNames() != null && !uri.getQueryParameterNames().isEmpty()) {
                String ssid = firstNonEmpty(uri, "ssid", "name", "network");
                String pass = firstNonEmpty(uri, "pwd", "password", "psk", "pass");
                String auth = firstNonEmpty(uri, "auth", "security");
                String mac = firstNonEmpty(uri, "mac", "bssid");
                if (ssid != null) return new Result(ssid, pass, auth, mac, raw);
            }
        } catch (Throwable ignored) {
            // Uri.parse 对畸形输入偶尔抛异常，落到下面的格式再试
        }

        // ② 标准 WIFI: 格式：WIFI:T:WPA;S:ssid;P:pass;;
        if (raw.regionMatches(true, 0, "WIFI:", 0, 5)) {
            Map<String, String> fields = parseWifiString(raw.substring(5));
            String ssid = unescapeWifi(fields.get("S"));
            String pass = unescapeWifi(fields.get("P"));
            String auth = fields.get("T");
            if (ssid != null) return new Result(ssid, pass, auth, null, raw);
        }

        return null;
    }

    private static String firstNonEmpty(Uri uri, String... keys) {
        for (String key : keys) {
            String value = uri.getQueryParameter(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    /** 解析 {@code T:WPA;S:xxx;P:yyy;;} 里的键值对。 */
    private static Map<String, String> parseWifiString(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : body.split(";")) {
            int colon = part.indexOf(':');
            if (colon > 0) out.put(part.substring(0, colon).trim(), part.substring(colon + 1));
        }
        return out;
    }

    /** WIFI: 串里 \; \, \: 需要反转义。 */
    private static String unescapeWifi(String value) {
        if (value == null) return null;
        return value.replace("\\;", ";").replace("\\,", ",").replace("\\:", ":").replace("\\\\", "\\");
    }
}
