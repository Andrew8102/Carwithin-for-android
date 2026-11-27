package com.github.carwithinforandroid.outputs.zeeho;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

import com.github.carwithinforandroid.core.LogBus;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 自己把 Wi-Fi Direct 建起来，不再依赖极核 App。
 *
 * <p>移植自 CarNine 的 {@code net/CastWifi.java}（AGPL-3.0），并补了两处它没有的东西：
 * <ol>
 *   <li>连上之后把 P2P 的 {@link Network} 对象留下来 —— 光有 p2p0 的 IP 不够，
 *       出站流量默认还走 wlan0/移动数据，必须靠 {@code Network.bindSocket} 才真正从 p2p0 出去；</li>
 *   <li>P2P 扫不到时退回 {@link WifiNetworkSpecifier} 直接加入仪表热点（部分固件是普通 AP 模式）。</li>
 * </ol>
 *
 * <p>仪表（组主）在 P2P 组里通常是 {@code x.x.x.1}，本机拿组主地址直接当目标 IP，
 * 就省掉了扫 254 个地址的发现过程。
 */
public final class ZeehoP2p implements Closeable {

    /** 仪表 P2P 设备名前缀；九号/极核都沿用 Wi-Fi Direct 默认的 DIRECT-xx 命名。 */
    private static final String DIRECT_PREFIX = "DIRECT-";

    private final Context context;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;

    private volatile CountDownLatch peersLatch = new CountDownLatch(1);
    private final List<WifiP2pDevice> peers = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean groupFormed;
    private volatile String groupOwnerIp;
    private volatile Network network;
    private volatile boolean selfIsOwner;

    private ConnectivityManager.NetworkCallback specifierCallback;
    private Network specifierNetwork;

    public ZeehoP2p(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** P2P 组是否已经建好。 */
    public boolean isConnected() {
        return groupFormed;
    }

    /** 组主（仪表）地址，没连上时为 null。 */
    public String groupOwnerIp() {
        return groupOwnerIp;
    }

    /** 该往哪个 Network 上绑 socket；没建组时为 null（调用方退回默认网络）。 */
    public Network network() {
        return network;
    }

    public boolean selfIsGroupOwner() {
        return selfIsOwner;
    }

    /** 注册广播。重复调用安全。 */
    public boolean start() {
        if (receiver != null) return true;
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            LogBus.log("[p2p] 不可用：Wi-Fi 没开");
            return false;
        }
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            LogBus.log("[p2p] 不可用：本机没有 Wi-Fi Direct 服务");
            return false;
        }
        channel = manager.initialize(context, Looper.getMainLooper(), () -> {
            LogBus.log("[p2p] 通道断开，下次操作前会重新初始化");
            receiver = null;
            channel = null;
        });
        if (channel == null) {
            LogBus.log("[p2p] 通道初始化失败");
            return false;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handle(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        try {
            // targetSdk 34 起注册非系统广播必须显式声明导出标志，否则直接抛 SecurityException。
            // P2P 这些广播都是系统发的，用 NOT_EXPORTED 就够。
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            LogBus.log("[p2p] 注册广播失败：" + e.getMessage());
            receiver = null;
            return false;
        }
        return true;
    }

    private void handle(Intent intent) {
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            boolean on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
            LogBus.log("[p2p] " + (on ? "已开启" : "已关闭"));
            return;
        }
        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            requestPeers();
            return;
        }
        if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            requestInfo();
            return;
        }
        if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            WifiP2pDevice self = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class)
                    : intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (self != null) {
                LogBus.log("[p2p] 本机 " + self.deviceName + " " + self.deviceAddress);
            }
        }
    }

    /**
     * 完整流程：扫描 → 挑仪表 → 连接 → 等组主地址。
     *
     * @return 组主 IP；null 表示没连上
     */
    public String connectToDashboard(long timeoutMs) {
        if (!start()) return null;
        if (!hasPermission(context)) {
            LogBus.log("[p2p] 缺权限：Android 13+ 需要「附近的设备」，以下需要「位置信息」");
            return null;
        }
        if (groupFormed && groupOwnerIp != null) {
            LogBus.log("[p2p] 已连，组主 " + groupOwnerIp);
            return groupOwnerIp;
        }

        peers.clear();
        // 每次扫描都要新的门闩，否则第二次调用会拿到上一次已经打开的旧门闩，
        // await 立即返回、peers 还是空的。
        final CountDownLatch latch = new CountDownLatch(1);
        peersLatch = latch;
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    LogBus.log("[p2p] 开始扫描附近设备…");
                }

                @Override
                public void onFailure(int reason) {
                    LogBus.log("[p2p] 扫描启动失败：" + reasonText(reason));
                    latch.countDown();
                }
            });
        } catch (SecurityException e) {
            LogBus.log("[p2p] 扫描被拒：" + e.getMessage());
            return null;
        }

        // 等第一批 peer 回来；扫不到就直接放弃，不要空等
        try {
            latch.await(Math.min(timeoutMs, 8000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        WifiP2pDevice dash = pickDashboard();
        if (dash == null) {
            LogBus.log("[p2p] 没扫到仪表。仪表需要先进入投屏等待页（车机上那个扫码界面），"
                    + "或者固件只在官方 App 请求时才开 P2P");
            return null;
        }
        LogBus.log("[p2p] 选中 " + dash.deviceName + " " + dash.deviceAddress);

        final CountDownLatch done = new CountDownLatch(1);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = dash.deviceAddress;
        try {
            config.wps.setup = WpsInfo.PBC;
        } catch (Throwable ignored) {
            // 个别 ROM 没这个类，用默认
        }
        // 0 = 尽量不做 GO：必须让仪表当组主，它的地址才是固定的 x.x.x.1
        config.groupOwnerIntent = 0;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    LogBus.log("[p2p] 连接请求已被接受，等协商…");
                    done.countDown();
                }

                @Override
                public void onFailure(int reason) {
                    LogBus.log("[p2p] 连接失败：" + reasonText(reason));
                    done.countDown();
                }
            });
            done.await(10, TimeUnit.SECONDS);
        } catch (SecurityException e) {
            LogBus.log("[p2p] 连接被拒：" + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        // 协商 + DHCP 需要几秒
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (groupFormed && groupOwnerIp != null) return groupOwnerIp;
            requestInfo();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        LogBus.log("[p2p] 等组主地址超时");
        return null;
    }

    /** 优先 DIRECT- 前缀；只有一个候选时也接受（极核/九号的设备名不一定是 DIRECT-）。 */
    private WifiP2pDevice pickDashboard() {
        WifiP2pDevice only = null;
        List<WifiP2pDevice> snapshot;
        synchronized (peers) {
            snapshot = new ArrayList<>(peers);
        }
        for (WifiP2pDevice d : snapshot) {
            if (d.deviceName != null && d.deviceName.toUpperCase().startsWith(DIRECT_PREFIX)) return d;
            if (only == null) only = d;
        }
        return snapshot.size() == 1 ? only : null;
    }

    private void requestPeers() {
        if (manager == null || channel == null) return;
        try {
            manager.requestPeers(channel, (WifiP2pDeviceList list) -> {
                List<WifiP2pDevice> got = new ArrayList<>(list.getDeviceList());
                peers.clear();
                peers.addAll(got);
                if (got.isEmpty()) {
                    LogBus.log("[p2p] 扫描结果为空");
                } else {
                    for (WifiP2pDevice d : got) {
                        LogBus.log("[p2p] 设备 " + d.deviceName + " " + d.deviceAddress
                                + " 状态=" + statusText(d.status));
                    }
                }
                peersLatch.countDown();
            });
        } catch (SecurityException e) {
            LogBus.log("[p2p] 读设备列表被拒：" + e.getMessage());
        }
    }

    private void requestInfo() {
        if (manager == null || channel == null) return;
        try {
            manager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
                if (info != null && info.groupFormed) {
                    String ip = info.groupOwnerAddress == null
                            ? null : info.groupOwnerAddress.getHostAddress();
                    groupFormed = true;
                    selfIsOwner = info.isGroupOwner;
                    groupOwnerIp = ip;
                    if (network == null) {
                        network = findP2pNetwork();
                        LogBus.log("[p2p] 组主 " + ip + (info.isGroupOwner ? "（本机是 GO）" : "")
                                + " · 网络 " + (network == null ? "未取到（将退回默认路由）" : "已绑定"));
                    }
                } else if (groupFormed) {
                    groupFormed = false;
                    groupOwnerIp = null;
                    network = null;
                    LogBus.log("[p2p] 组已断开");
                }
            });
        } catch (SecurityException e) {
            LogBus.log("[p2p] 读连接信息被拒：" + e.getMessage());
        }
    }

    /** 从系统网络列表里认出 p2p 接口对应的 Network（bindSocket 要用它）。 */
    private Network findP2pNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null || lp.getInterfaceName() == null) continue;
            if (lp.getInterfaceName().toLowerCase().startsWith("p2p")) return n;
        }
        return null;
    }

    /**
     * 兜底：不走 P2P，直接用系统 API 加入仪表的普通 Wi-Fi 热点。
     *
     * <p>这是 CarWithin（iOS）唯一的路子 —— 它没有组网代码，前提就是手机先加入仪表 Wi-Fi。
     *
     * @param ssid     仪表热点名
     * @param password 密码；空串表示开放网络
     */
    public boolean joinDashboardHotspot(String ssid, String password, String assumedIp, long timeoutMs) {
        if (ssid == null || ssid.trim().isEmpty()) {
            LogBus.log("[hotspot] 未配置仪表热点（SSID），跳过");
            return false;
        }
        String psk = password == null ? "" : password.trim();
        if (!psk.isEmpty() && psk.length() < 8) {
            LogBus.log("[hotspot] 密码少于 8 位，WPA2 不接受：" + psk.length());
            return false;
        }
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        // 上一次请求的 callback 必须先摘掉，否则每重连一次就在系统里留一个，
        // 且新请求会跟旧网络互相抢 wlan0。
        if (specifierCallback != null) {
            try {
                cm.unregisterNetworkCallback(specifierCallback);
            } catch (Exception ignored) {
                // 已经失效
            }
            specifierCallback = null;
            specifierNetwork = null;
        }
        WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(ssid.trim());
        if (psk.isEmpty()) {
            LogBus.log("[hotspot] 按开放网络加入 " + ssid);
        } else {
            builder.setWpa2Passphrase(psk);
        }
        WifiNetworkSpecifier specifier = builder.build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        specifierCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                specifierNetwork = n;
                network = n;
                groupFormed = true;
                groupOwnerIp = assumedIp;
                LogBus.log("[hotspot] 已加入仪表热点 " + ssid
                        + (assumedIp == null ? "" : "，目标 " + assumedIp));
                latch.countDown();
            }

            @Override
            public void onUnavailable() {
                LogBus.log("[hotspot] 找不到仪表热点 " + ssid);
                latch.countDown();
            }

            @Override
            public void onLost(Network n) {
                if (n != null && n.equals(specifierNetwork)) {
                    specifierNetwork = null;
                    groupFormed = false;
                    LogBus.log("[hotspot] 仪表热点断开");
                }
            }
        };
        try {
            cm.requestNetwork(request, specifierCallback, (int) timeoutMs);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return specifierNetwork != null;
        } catch (Exception e) {
            LogBus.log("[hotspot] 请求热点失败：" + e.getMessage());
            return false;
        }
    }

    /** 断开并移除 P2P 组（会让仪表退出投屏页）。 */
    public void disconnect() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && specifierCallback != null) {
            try {
                cm.unregisterNetworkCallback(specifierCallback);
            } catch (Exception ignored) {
                // 已经注销
            }
            specifierCallback = null;
            specifierNetwork = null;
        }
        if (manager != null && channel != null) {
            try {
                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        LogBus.log("[p2p] 组已移除");
                    }

                    @Override
                    public void onFailure(int reason) {
                        LogBus.log("[p2p] 移除组失败：" + reasonText(reason));
                    }
                });
            } catch (SecurityException ignored) {
                // 权限被撤
            }
        }
        groupFormed = false;
        groupOwnerIp = null;
        network = null;
    }

    @Override
    public void close() {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
                // 已经注销过
            }
            receiver = null;
        }
        channel = null;
        manager = null;
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "本机不支持 P2P";
            case WifiP2pManager.BUSY:
                return "P2P 忙（另一个 App 正在用）";
            case WifiP2pManager.ERROR:
                return "内部错误";
            default:
                return "reason=" + reason;
        }
    }

    private static String statusText(int status) {
        switch (status) {
            case WifiP2pDevice.CONNECTED:
                return "已连接";
            case WifiP2pDevice.INVITED:
                return "已邀请";
            case WifiP2pDevice.FAILED:
                return "失败";
            case WifiP2pDevice.AVAILABLE:
                return "可用";
            case WifiP2pDevice.UNAVAILABLE:
                return "不可用";
            default:
                return String.valueOf(status);
        }
    }
}
