package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 选本机该用哪块网卡跟仪表说话。
 *
 * <p>极核手机端跟仪表常见是 <b>Wi-Fi Direct（P2P）</b>组网的：用户在极核 App 里点「投屏」，
 * 由 App 发起 P2P 连接，系统自动完成配对，所以主观上「连车机 Wi-Fi 是无感的」——
 * 它在系统设置里根本不是一个能手动选的热点。这种情况下连接建在 <b>p2p0</b> 上，
 * 而 {@code WifiManager.getConnectionInfo()} 读的是 STA 接口（wlan0）。如果照它的 IP
 * 去扫网段、去 bind，扫的是家里路由器，仪表永远「未发现」。
 *
 * <p>但 iOS 参考实现 CarWithin 连组网代码都没有 —— 它要求用户「iPhone 加入仪表 Wi-Fi」，
 * 而 iOS 不支持 Wi-Fi Direct，说明仪表在投屏页同时会开一个<b>普通热点</b>（AP 模式）。
 * 因此两种网卡都要认：p2p0 优先，其次 wlan0。
 */
public final class ZeehoNetwork {

    private ZeehoNetwork() {
    }

    /** 一块网卡上的一个 IPv4 地址，附带它所属的 {@link Network}。 */
    public static final class Nic {
        public final String name;
        public final String ip;
        /**
         * 该地址所属的网络。可为 null（走接口枚举回退时拿不到）。
         *
         * <p>光有 IP 是不够的：Android 上 {@code socket.bind(ip, 0)} 只改源地址，
         * 出口仍按默认路由走，于是发往仪表的包会从 wlan0/移动数据出去。
         * 必须 {@code network.bindSocket(socket)} 才真正从这块网卡出。
         */
        public final Network network;

        Nic(String name, String ip, Network network) {
            this.name = name;
            this.ip = ip;
            this.network = network;
        }

        @Override
        public String toString() {
            return name + "=" + ip;
        }
    }

    /** 枚举所有非回环的 IPv4 地址（系统仍可能因为权限隐藏部分接口）。 */
    public static List<Nic> all(Context context) {
        List<Nic> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp == null) continue;
                    String ifname = lp.getInterfaceName();
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            out.add(new Nic(ifname == null ? "?" : ifname, addr.getHostAddress(), network));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 某些 ROM 上 ConnectivityManager 会抛 SecurityException，退回下面的接口枚举
        }
        if (out.isEmpty()) {
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            out.add(new Nic(ni.getName(), addr.getHostAddress(), null));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 枚举不了就只能报没网
            }
        }
        return out;
    }

    /** 越小越优先。 */
    private static int rank(String name) {
        if (name == null) return 90;
        String n = name.toLowerCase();
        if (n.startsWith("p2p")) return 0;                       // Wi-Fi Direct，极核真正走的这条
        if (n.startsWith("wlan")) return 10;                     // 普通 Wi-Fi
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) return 20;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("wwan")) return 80;
        if (n.startsWith("tun") || n.startsWith("vgate") || n.startsWith("dummy")
                || n.startsWith("ipsec") || n.startsWith("ppp")) return 85;
        return 50;
    }

    /**
     * 最可能通到仪表的本机地址。
     *
     * @return null 表示当前只有移动数据/VPN，没有能连仪表的局域网
     */
    public static Nic best(Context context) {
        Nic best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Nic nic : all(context)) {
            int r = rank(nic.name);
            if (r < bestRank) {
                best = nic;
                bestRank = r;
            }
        }
        return bestRank >= 80 ? null : best;
    }

    /** 指定网卡名对应的 Network；找不到返回 null。 */
    public static Network networkOf(Context context, String ifname) {
        if (ifname == null) return null;
        for (Nic nic : all(context)) {
            if (ifname.equalsIgnoreCase(nic.name) && nic.network != null) return nic.network;
        }
        return null;
    }

    /** 是否已经建立 Wi-Fi Direct（p2p0 有 IP）。false 表示还没有 P2P 组。 */
    public static boolean hasDirect(Context context) {
        for (Nic nic : all(context)) {
            if (nic.name != null && nic.name.toLowerCase().startsWith("p2p")) return true;
        }
        return false;
    }

    /**
     * 当前最优网段的网关地址（x.y.z.1）。
     *
     * <p>仪表当组主/热点时几乎总是网段第一个地址，CarWithin 的候选表里也把 .1 排在前面。
     * 加入热点之后才调用才有意义 —— 加入之前算出来的是上一个网络（例如家里路由器）。
     */
    public static String guessGateway(Context context) {
        Nic nic = best(context);
        if (nic == null || nic.ip == null) return null;
        int dot = nic.ip.lastIndexOf('.');
        return dot < 0 ? null : nic.ip.substring(0, dot + 1) + "1";
    }

    /** 日志用：p2p0=192.168.49.1, wlan0=192.168.123.104 */
    public static String describe(Context context) {
        List<Nic> list = all(context);
        if (list.isEmpty()) return "无 IPv4 地址";
        StringBuilder sb = new StringBuilder();
        for (Nic nic : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(nic);
        }
        return sb.toString();
    }
}
