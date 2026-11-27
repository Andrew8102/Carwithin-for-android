package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.github.carwithinforandroid.core.DashboardOutput;
import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.VehicleProfile;
import com.github.carwithinforandroid.core.VideoFrame;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** 极核 PXC/TCP 输出实现。支持车型配置覆盖分辨率、发现端口与目标 IP。 */
public final class ZeehoOutput implements DashboardOutput {

    private ZeehoConnection connection;
    private VehicleProfile profile;

    /** 应用车型配置，必须在 start 之前调用。 */
    public void configure(VehicleProfile profile) {
        this.profile = profile;
    }

    @Override
    public void start(Context context, int width, int height, int fps) throws IOException {
        if (profile != null) {
            width = profile.width;
            height = profile.height;
        }
        ZeehoIdentity identity;
        try {
            identity = ZeehoIdentity.load(context);
        } catch (GeneralSecurityException e) {
            throw new IOException("本机身份初始化失败", e);
        }
        String localIp = localIp(context);
        if (localIp == null) throw new IOException("未取得 Wi-Fi 地址，请先连接仪表 Wi-Fi");
        String targetIp = VehicleProfile.targetIp(context);
        if (targetIp != null && targetIp.trim().isEmpty()) targetIp = null;

        int discoveryPort = profile == null ? ZeehoConnection.DEFAULT_DISCOVERY_PORT : profile.discoveryPort;
        connection = new ZeehoConnection(localIp, targetIp, identity,
                context.getPackageName(), versionCode(context),
                Build.MODEL, String.valueOf(Build.VERSION.SDK_INT),
                width, height, discoveryPort);
        try {
            connection.start();
        } catch (GeneralSecurityException e) {
            throw new IOException("仪表会话握手失败", e);
        }
    }

    @Override
    public void onFrame(VideoFrame frame) throws IOException {
        if (connection != null) {
            connection.sendFrame(frame);
            FrameStats.get().onSent();
        }
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public String status() {
        return connection == null ? "IDLE" : connection.status();
    }

    public String targetIp() {
        return connection == null ? null : connection.targetIp();
    }

    private static String localIp(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            int value = info.getIpAddress();
            if (value == 0) return null;
            return (value & 0xff) + "." + ((value >> 8) & 0xff) + "."
                    + ((value >> 16) & 0xff) + "." + ((value >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    private static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }
}
