package com.github.carwithinforandroid.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.github.carwithinforandroid.core.CastSession;
import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.VehicleProfile;
import com.github.carwithinforandroid.launcher.CarAppLauncher;
import com.github.carwithinforandroid.outputs.zeeho.ZeehoNetwork;
import com.github.carwithinforandroid.outputs.zeeho.ZeehoP2p;
import com.github.carwithinforandroid.outputs.zeeho.ZeehoDiscovery;
import com.github.carwithinforandroid.outputs.zeeho.ZeehoPrefs;

/**
 * 仅 debug 包可用的命令行入口。
 *
 * <p>真机调试时靠手指点屏幕既慢又容易点偏，而且锁屏后完全点不到。这里把手上的动作
 * 暴露成广播，adb 一条命令就能驱动：
 *
 * <pre>
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.CARLIFE_CONNECT
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.CARLIFE_CONNECT -e pkg com.oplus.ocar
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.STOP
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.STATE
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.DIAGNOSE
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.TESTPATTERN
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.P2P_BUILD
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.P2P_SCAN
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.HOTSPOT --es ssid "热点名" --es psk "密码"
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.JOIN_HOTSPOT
 * adb shell am broadcast -a com.github.carwithinforandroid.DEBUG.FORGET --es what target
 * </pre>
 *
 * <p>只有 debuggable 的包才响应，正式包直接忽略，避免留一个可被任意 App 触发的开关。
 */
public final class DebugCommandReceiver extends BroadcastReceiver {

    private static final String PREFIX = "com.github.carwithinforandroid.DEBUG.";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!isDebuggable(context)) return;

        String action = intent == null ? null : intent.getAction();
        if (action == null || !action.startsWith(PREFIX)) return;
        String cmd = action.substring(PREFIX.length());

        Context app = context.getApplicationContext();
        CastSession session = CastSession.get();
        session.configure(app);
        LogBus.log("[debug] 收到命令 " + cmd);

        switch (cmd) {
            case "CARLIFE_CONNECT": {
                // 只连端口，不拉起 Car+。端口本来就开着时用它，避免把 CarLife 进程替换掉。
                if (session.isActive()) session.stop();
                session.startCarLife(app, null);
                LogBus.log("[debug] 已启动 CarLife 来源（不拉起 Car+）");
                break;
            }
            case "CARLIFE_CONNECT_LAUNCH": {
                if (session.isActive()) session.stop();
                session.startCarLife(app, null);
                String pkg = intent.getStringExtra("pkg");
                if (pkg == null || pkg.trim().isEmpty()) pkg = VehicleProfile.launchPackage(app);
                final String pkgFinal = pkg;
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                        if (!session.isActive()) return;
                        String phase = session.sourcePhase();
                        if (phase.contains("连通") || phase.contains("画布")) {
                            LogBus.log("[debug] 端口已连通，跳过拉起 Car+");
                            return;
                        }
                        String act = CarAppLauncher.bestCandidate(app, pkgFinal);
                        LogBus.log("[debug] 拉起 " + pkgFinal + " / " + act);
                        LogBus.log(CarAppLauncher.launch(app, pkgFinal, act));
                    } catch (Throwable e) {
                        LogBus.log("[debug] 拉起流程异常：" + e);
                    }
                }, "debug-launch").start();
                break;
            }
            case "TESTPATTERN": {
                if (session.isActive()) session.stop();
                session.startTest(app, null);
                LogBus.log("[debug] 已启动测试图");
                break;
            }
            case "STOP": {
                session.stop();
                LogBus.log("[debug] 已请求停止");
                break;
            }
            case "STATE": {
                LogBus.log("[debug] status=" + session.status()
                        + " | active=" + session.isActive()
                        + " | outputReady=" + session.outputReady()
                        + " | phase=" + session.sourcePhase()
                        + " | detail=" + session.detail());
                break;
            }
            case "DIAGNOSE": {
                new Thread(() -> {
                    try {
                        LogBus.log("[debug] ==== 诊断开始 ====");
                        LogBus.log("[debug] " + CarAppLauncher.describeActivities(app, VehicleProfile.launchPackage(app)));
                        int open = CarAppLauncher.countOpenPorts(300);
                        LogBus.log("[debug] CarLife 端口监听数 " + open + "/7");
                        LogBus.log("[debug] 网卡 " + ZeehoNetwork.describe(app));
                        LogBus.log("[debug] Wi-Fi Direct " + (ZeehoNetwork.hasDirect(app)
                                ? "已建立（p2p0 有地址）"
                                : "未建立 —— 可以试 P2P_BUILD 自己建，或先去极核 App 点一次「投屏」"));
                        LogBus.log("[debug] P2P 权限 " + (ZeehoP2p.hasPermission(app) ? "有" : "无（需要「附近的设备」/「位置信息」）"));
                        LogBus.log("[debug] ==== 诊断结束 ====");
                    } catch (Throwable e) {
                        LogBus.log("[debug] 诊断异常：" + e);
                    }
                }, "debug-diag").start();
                break;
            }
            case "P2P_BUILD": {
                // 自己建 Wi-Fi Direct：验证不带极核 App 能不能把组建起来
                new Thread(() -> {
                    try {
                        LogBus.log("[debug] ==== 自建 Wi-Fi Direct ====");
                        if (!ZeehoP2p.hasPermission(app)) {
                            LogBus.log("[debug] 缺权限，先授权：adb shell pm grant "
                                    + app.getPackageName() + " android.permission.NEARBY_WIFI_DEVICES");
                            return;
                        }
                        ZeehoP2p link = new ZeehoP2p(app);
                        String owner = link.connectToDashboard(20_000);
                        if (owner == null) {
                            LogBus.log("[debug] 未建成：仪表不在 P2P 可见状态（可能只认官方 App 的请求）");
                            return;
                        }
                        for (int i = 0; i < 20 && !ZeehoNetwork.hasDirect(app); i++) {
                            Thread.sleep(300);
                        }
                        LogBus.log("[debug] 组主 " + owner + " | 网卡 " + ZeehoNetwork.describe(app));
                        // 组主地址就是仪表，直接拿它去敲 10930，省掉扫 254 个地址
                        boolean reachable;
                        try (java.net.Socket probe = new java.net.Socket()) {
                            if (link.network() != null) link.network().bindSocket(probe);
                            probe.connect(new java.net.InetSocketAddress(owner,
                                    ZeehoDiscovery.DISCOVERY_PORT), 1500);
                            reachable = true;
                        } catch (Exception e) {
                            reachable = false;
                        }
                        LogBus.log("[debug] 仪表 " + owner + ":" + ZeehoDiscovery.DISCOVERY_PORT
                                + " " + (reachable ? "有响应，可以点「重连仪表」" : "无响应（仪表的投屏服务没开）"));
                    } catch (Throwable e) {
                        LogBus.log("[debug] P2P_BUILD 异常：" + e);
                    }
                }, "debug-p2p").start();
                break;
            }
            case "P2P_SCAN": {
                new Thread(() -> {
                    try {
                        LogBus.log("[debug] ==== 扫描 P2P 设备 ====");
                        new ZeehoP2p(app).connectToDashboard(15_000);
                        LogBus.log("[debug] ==== 扫描结束 ====");
                    } catch (Throwable e) {
                        LogBus.log("[debug] P2P_SCAN 异常：" + e);
                    }
                }, "debug-p2p-scan").start();
                break;
            }
            case "HOTSPOT": {
                // 写仪表热点凭据。CarWithin（iOS）就是靠用户手填这组东西连上的，
                // Android 上用 WifiNetworkSpecifier 可以全自动，不需要极核 App。
                String ssid = intent.getStringExtra("ssid");
                String psk = intent.getStringExtra("psk");
                if (ssid == null || ssid.trim().isEmpty()) {
                    LogBus.log("[debug] 用法：--es ssid \"热点名\" --es psk \"密码\"（开放网络 psk 传空）");
                    break;
                }
                ZeehoPrefs.saveHotspot(app, ssid, psk);
                LogBus.log("[debug] 已保存仪表热点：" + ZeehoPrefs.describe(app));
                LogBus.log("[debug] 之后启动投屏会自动先试这个热点；想立刻试发 JOIN_HOTSPOT");
                break;
            }
            case "JOIN_HOTSPOT": {
                new Thread(() -> {
                    try {
                        LogBus.log("[debug] ==== 加入仪表热点 ====");
                        LogBus.log("[debug] 配置：" + ZeehoPrefs.describe(app));
                        if (!ZeehoPrefs.hasHotspot(app)) {
                            LogBus.log("[debug] 还没配热点，先用 HOTSPOT 命令写入 SSID/密码");
                            return;
                        }
                        ZeehoP2p link = new ZeehoP2p(app);
                        boolean ok = link.joinDashboardHotspot(ZeehoPrefs.hotspotSsid(app),
                                ZeehoPrefs.hotspotPsk(app), null, 25_000);
                        LogBus.log("[debug] 加入结果 " + (ok ? "成功" : "失败"));
                        LogBus.log("[debug] 网卡 " + ZeehoNetwork.describe(app));
                        if (!ok) return;
                        String gw = ZeehoNetwork.guessGateway(app);
                        ZeehoNetwork.Nic nic = ZeehoNetwork.best(app);
                        LogBus.log("[debug] 网关猜测 " + gw + "，当前网卡 " + nic);
                        if (nic != null) {
                            boolean hit = gw != null && ZeehoDiscovery.probe(gw,
                                    ZeehoDiscovery.DISCOVERY_PORT, nic.network, 1500);
                            LogBus.log("[debug] 仪表 " + gw + ":" + ZeehoDiscovery.DISCOVERY_PORT
                                    + " " + (hit ? "有响应 → 可以点投屏了" : "无响应（仪表没进投屏页？）"));
                        }
                    } catch (Throwable e) {
                        LogBus.log("[debug] JOIN_HOTSPOT 异常：" + e);
                    }
                }, "debug-hotspot").start();
                break;
            }
            case "FORGET": {
                // 清掉记住的东西，回到「第一次连接」的状态
                String what = intent.getStringExtra("what");
                if ("hotspot".equalsIgnoreCase(what)) {
                    ZeehoPrefs.clearHotspot(app);
                } else if ("target".equalsIgnoreCase(what)) {
                    ZeehoPrefs.clearLastTarget(app);
                } else {
                    ZeehoPrefs.clearHotspot(app);
                    ZeehoPrefs.clearLastTarget(app);
                }
                LogBus.log("[debug] 已清除，当前：" + ZeehoPrefs.describe(app));
                break;
            }
            default:
                LogBus.log("[debug] 未知命令：" + cmd);
                break;
        }
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** 供调试文档引用的命令清单。 */
    public static String usage() {
        return "am broadcast -a " + PREFIX + "{CARLIFE_CONNECT|CARLIFE_CONNECT_LAUNCH|TESTPATTERN|STOP|STATE"
                + "|DIAGNOSE|P2P_BUILD|P2P_SCAN|HOTSPOT|JOIN_HOTSPOT|FORGET}"
                + "  [--es pkg com.oplus.ocar] [--es ssid ..] [--es psk ..] [--es what hotspot|target]"
                + "   (需要 debuggable 包, 当前 API " + Build.VERSION.SDK_INT + ")";
    }
}
