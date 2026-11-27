package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.github.carwithinforandroid.core.DashboardOutput;
import com.github.carwithinforandroid.core.FrameStats;
import com.github.carwithinforandroid.core.LogBus;
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
        // 尺寸以调用方给的为准：CarLife 只能给 848×480，若这里硬套车型的 1024×480，
        // 仪表会按错的尺寸解码。等真正需要 32:15 时再做缩放重编码，不要在这一层偷改。
        int localWidth = width > 0 ? width : (profile == null ? 1024 : profile.width);
        int localHeight = height > 0 ? height : (profile == null ? 480 : profile.height);
        ZeehoIdentity identity;
        try {
            identity = ZeehoIdentity.load(context);
        } catch (GeneralSecurityException e) {
            throw new IOException("本机身份初始化失败", e);
        }
        // 极核跟仪表走 Wi-Fi Direct：真正能通的是 p2p0，而 WifiManager 只报 wlan0。
        // 照 wlan0 去找，扫的是家里路由器，永远「未发现仪表」。
        LogBus.log("本机网卡：" + ZeehoNetwork.describe(context));
        ZeehoNetwork.Nic nic = ZeehoNetwork.best(context);
        if (nic == null) {
            throw new IOException("当前没有局域网（只有移动数据）。先在极核 App 点「投屏」把车机连上");
        }
        if (!ZeehoNetwork.hasDirect(context)) {
            LogBus.log("提示：没有 p2p0。极核的车机链路是 App 发起的 Wi-Fi Direct，"
                    + "要先去极核 App 点一次「投屏」，再回来点「重连仪表」");
        }
        String localIp = nic.ip;
        LogBus.log("用 " + nic + " 去找仪表");
        String targetIp = VehicleProfile.targetIp(context);
        if (targetIp != null && targetIp.trim().isEmpty()) targetIp = null;

        int discoveryPort = profile == null ? ZeehoConnection.DEFAULT_DISCOVERY_PORT : profile.discoveryPort;
        ZeehoConnection created = new ZeehoConnection(localIp, targetIp, identity,
                context.getPackageName(), versionCode(context),
                Build.MODEL, String.valueOf(Build.VERSION.SDK_INT),
                localWidth, localHeight, discoveryPort);
        connection = created;
        try {
            created.start();
        } catch (IOException e) {
            created.close();          // 关掉已 bind 的 3 个监听端口，否则重试必报端口占用
            connection = null;
            throw e;
        } catch (GeneralSecurityException e) {
            created.close();
            connection = null;
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

    private static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }
}
