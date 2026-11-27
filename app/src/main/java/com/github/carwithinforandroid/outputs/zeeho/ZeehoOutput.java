package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;

import com.github.carwithinforandroid.core.DashboardOutput;
import com.github.carwithinforandroid.core.VideoFrame;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** 极核 AE4 MAX2+ 6.2 寸输出实现。 */
public final class ZeehoOutput implements DashboardOutput {

    private ZeehoConnection connection;

    @Override
    public void start(Context context, int width, int height, int fps) throws IOException {
        if (width != 1024 || height != 480) {
            throw new IllegalArgumentException("极核 6.2 寸固定 1024x480");
        }
        ZeehoIdentity identity;
        try {
            identity = ZeehoIdentity.load(context);
        } catch (GeneralSecurityException e) {
            throw new IOException("本机身份初始化失败", e);
        }
        String localIp = localIp(context);
        if (localIp == null) throw new IOException("未取得 Wi-Fi 地址");
        connection = new ZeehoConnection(localIp, null, identity,
                context.getPackageName(), versionCode(context));
        try {
            connection.start();
        } catch (GeneralSecurityException e) {
            throw new IOException("仪表会话握手失败", e);
        }
    }

    @Override
    public void onFrame(VideoFrame frame) throws IOException {
        if (connection != null) connection.sendFrame(frame);
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

    private static String localIp(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        int value = wifi.getConnectionInfo().getIpAddress();
        if (value == 0) return null;
        return (value & 0xff) + "." + ((value >> 8) & 0xff) + "."
                + ((value >> 16) & 0xff) + "." + ((value >> 24) & 0xff);
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
