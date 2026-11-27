package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Network;
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
    private ZeehoP2p p2p;
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
        String configuredTarget = VehicleProfile.targetIp(context);
        if (configuredTarget != null && configuredTarget.trim().isEmpty()) configuredTarget = null;
        String autoTarget = null;

        // 没有现成的 P2P 组就自己建一个，不再等用户去极核 App 里点「投屏」。
        // 参考 CarNine 的做法：自己 discoverPeers → connect(仪表当 GO) → 拿组主地址。
        if (!ZeehoNetwork.hasDirect(context)) {
            if (!ZeehoP2p.hasPermission(context)) {
                LogBus.log("缺「附近的设备/位置信息」权限，无法自己建 Wi-Fi Direct，"
                        + "只能先去极核 App 点一次「投屏」把组建起来");
            } else {
                LogBus.log("没有 p2p0，尝试自己建 Wi-Fi Direct（不用极核 App）…");
                ZeehoP2p link = new ZeehoP2p(context);
                p2p = link;
                autoTarget = link.connectToDashboard(15_000);
                if (autoTarget != null) {
                    LogBus.log("Wi-Fi Direct 已建立，仪表（组主）" + autoTarget);
                    // DHCP 给 p2p0 分地址要一会儿，等它真拿到再选网卡
                    for (int i = 0; i < 20 && !ZeehoNetwork.hasDirect(context); i++) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } else {
                    LogBus.log("自建 Wi-Fi Direct 未成功，退回已连的 Wi-Fi");
                }
            }
        } else {
            LogBus.log("检测到 p2p0，直接复现已有的 Wi-Fi Direct 组");
        }

        ZeehoNetwork.Nic nic = ZeehoNetwork.best(context);
        if (nic == null) {
            throw new IOException("当前没有局域网（只有移动数据）。先在极核 App 点「投屏」把车机连上");
        }
        Network network = nic.network;
        if (p2p != null && p2p.network() != null) network = p2p.network();
        String localIp = nic.ip;
        LogBus.log("用 " + nic + (network == null ? "（无 Network 对象，走默认路由）" : "（已绑定该网络）") + " 去找仪表");

        String targetIp = configuredTarget != null ? configuredTarget : autoTarget;

        int discoveryPort = profile == null ? ZeehoConnection.DEFAULT_DISCOVERY_PORT : profile.discoveryPort;
        ZeehoConnection created = new ZeehoConnection(localIp, targetIp, identity,
                context.getPackageName(), versionCode(context),
                Build.MODEL, String.valueOf(Build.VERSION.SDK_INT),
                localWidth, localHeight, discoveryPort, network);
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
        // 保留 P2P 组：断开组会让仪表退出投屏页，重连又要重新协商。
        // 只关广播接收器，避免泄漏。
        if (p2p != null) {
            p2p.close();
            p2p = null;
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
