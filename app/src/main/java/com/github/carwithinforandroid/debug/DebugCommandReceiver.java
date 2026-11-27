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
        return "am broadcast -a " + PREFIX + "{CARLIFE_CONNECT|CARLIFE_CONNECT_LAUNCH|TESTPATTERN|STOP|STATE|DIAGNOSE|P2P_BUILD|P2P_SCAN}"
                + "  [--es pkg com.oplus.ocar]   (需要 debuggable 包, 当前 API " + Build.VERSION.SDK_INT + ")";
    }
}
