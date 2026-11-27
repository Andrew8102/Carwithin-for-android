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
        // 但注意顺序：CarWithin（iOS）根本没有组网代码，它是「用户先连仪表 Wi-Fi → App 直接扫 10930」。
        // 所以这里也先扫再组网：已经在极核 App 点过投屏（手机已连上仪表）时，
        // 直接沿着现有网卡找到仪表，不去动 P2P，避免把已经连好的组弄断。
        LogBus.log("本机网卡：" + ZeehoNetwork.describe(context));
        LogBus.log("连接配置：" + ZeehoPrefs.describe(context));
        String configuredTarget = VehicleProfile.targetIp(context);
        if (configuredTarget != null && configuredTarget.trim().isEmpty()) configuredTarget = null;
        String autoTarget = null;
        int discoveryPort = profile == null ? ZeehoConnection.DEFAULT_DISCOVERY_PORT : profile.discoveryPort;

        // 阶段 1：不组网，先用现有网卡找仪表。
        if (configuredTarget == null) {
            autoTarget = probeExisting(context, discoveryPort);
        }

        // 阶段 2：还找不到才建网。优先级：已配置的仪表热点（最接近 CarWithin）＞ 自建 Wi-Fi Direct。
        if (autoTarget == null && configuredTarget == null && !ZeehoNetwork.hasDirect(context)) {
            if (ZeehoPrefs.hasHotspot(context)) {
                LogBus.log("现有网卡找不到仪表，尝试加入仪表热点 "
                        + ZeehoPrefs.hotspotSsid(context) + " …");
                ZeehoP2p hotspotLink = new ZeehoP2p(context);
                p2p = hotspotLink;
                if (hotspotLink.joinDashboardHotspot(ZeehoPrefs.hotspotSsid(context),
                        ZeehoPrefs.hotspotPsk(context), null, 25_000)) {
                    settle();                       // 等 wlan0 换上仪表网段的地址
                    // 加入之后才能算网关，连之前算的是家里路由器
                    String guessed = ZeehoNetwork.guessGateway(context);
                    autoTarget = probeExisting(context, discoveryPort);
                    if (autoTarget == null) autoTarget = guessed;
                    if (autoTarget != null) LogBus.log("热点内仪表地址 " + autoTarget);
                } else {
                    LogBus.log("加入仪表热点失败（热点没开、密码不对，或仪表没进投屏页）");
                }
            }
            if (autoTarget == null) {
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
                        waitForDirect(context, 20);
                    } else {
                        LogBus.log("自建 Wi-Fi Direct 未成功，退回已连的 Wi-Fi");
                    }
                }
            }
        } else if (ZeehoNetwork.hasDirect(context)) {
            LogBus.log("检测到 p2p0，直接复现已有的 Wi-Fi Direct 组");
        }

        ZeehoNetwork.Nic nic = ZeehoNetwork.best(context);
        if (nic == null) {
            throw new IOException("当前没有局域网（只有移动数据）。先在极核 App 点「投屏」，"
                    + "或用 HOTSPOT 命令配好仪表热点后重试");
        }
        Network network = nic.network;
        if (p2p != null && p2p.network() != null) network = p2p.network();
        String localIp = nic.ip;
        LogBus.log("用 " + nic + (network == null ? "（无 Network 对象，走默认路由）" : "（已绑定该网络）") + " 去找仪表");

        String targetIp = configuredTarget != null ? configuredTarget : autoTarget;

        ZeehoConnection created = new ZeehoConnection(localIp, targetIp, identity,
                context.getPackageName(), versionCode(context),
                Build.MODEL, String.valueOf(Build.VERSION.SDK_INT),
                localWidth, localHeight, discoveryPort, network);
        connection = created;
        try {
            created.start();
            // 握手过了才记账：只有真的连通过，这个地址下次才值得先试
            ZeehoPrefs.saveLastTarget(context, created.targetIp());
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

    /**
     * 用当前最优网卡找仪表，全程不改变网络状态。
     *
     * <p>先试上次握手成功的地址（命中就省掉 8 秒扫描），再走 CarWithin 那套
     * 「网段 .1 → 全 /24 并发探测」。这是「用户已经在极核 App 点过投屏、手机已连上仪表」
     * 这个最常见场景下唯一要做的事。
     */
    private static String probeExisting(Context context, int port) {
        ZeehoNetwork.Nic nic = ZeehoNetwork.best(context);
        if (nic == null) return null;
        String last = ZeehoPrefs.lastTarget(context);
        if (last != null && sameSlash24(last, nic.ip)
                && ZeehoDiscovery.probe(last, port, nic.network, 800)) {
            LogBus.log("上次的仪表地址 " + last + " 仍然通，跳过扫描");
            return last;
        }
        LogBus.log("在 " + slash24(nic.ip) + " 里找 " + port + " 端口…");
        long started = System.currentTimeMillis();
        java.util.List<String> found = ZeehoDiscovery.discover(nic.ip, port, nic.network);
        long cost = System.currentTimeMillis() - started;
        if (found.isEmpty()) {
            LogBus.log("没找到仪表（耗时 " + cost + "ms）");
            return null;
        }
        LogBus.log("发现仪表 " + found.get(0) + "（耗时 " + cost + "ms）");
        return found.get(0);
    }

    /** 组网/DHCP 之后等 p2p0 真正拿到地址，避免用旧网卡去连。 */
    private static void waitForDirect(Context context, int rounds) {
        for (int i = 0; i < rounds && !ZeehoNetwork.hasDirect(context); i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void settle() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String slash24(String ip) {
        if (ip == null) return "?";
        int dot = ip.lastIndexOf('.');
        return dot < 0 ? ip : ip.substring(0, dot + 1) + "0/24";
    }

    private static boolean sameSlash24(String a, String b) {
        if (a == null || b == null) return false;
        return a.substring(0, Math.max(0, a.lastIndexOf('.')))
                .equals(b.substring(0, Math.max(0, b.lastIndexOf('.'))));
    }
}
