package com.github.carwithinforandroid.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import com.github.carwithinforandroid.core.LogBus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 车机互联界面启动器 + 诊断。
 *
 * <p>重要前提：OPPO Car+（com.oplus.ocar）是手机端 CarLife（MD），
 * 只有当它连上支持 ICCOA / 百度 CarLife+ / 亿连的<strong>车机端（HU）</strong>时，
 * 才会进入 CarLife 模式并让 7 个端口在 127.0.0.1 监听。极核 AE4 MAX2+ 是私有 PXC
 * 协议仪表，不是 CarLife 车机，因此<strong>本 App 无法凭空让 Car+ 配对</strong>。
 *
 * <p>本类提供：① 拉起 Car+ 的入口（部分 ColorOS 版本可直接进 CarLife 模式）；
 * ② 诊断 Car+ 是否安装、版本、可拉起组件；③ 探测 7 端口确认 CarLife 组件是否真的在监听。
 * 若诊断显示 Car+ 未进入 CarLife 模式，请改用「录屏投屏」——它不依赖任何车机配对。
 */
public final class CarAppLauncher {

    /** CarLife 手机端（Car+ 内组件）监听的本机端口。 */
    public static final int[] CARLIFE_PORTS = {7240, 8240, 9240, 9241, 9242, 9340, 9440};

    public static final class Target {
        public final String label;
        public final String packageName;
        public final String activity;
        public final String note;

        public Target(String label, String packageName, String activity, String note) {
            this.label = label;
            this.packageName = packageName;
            this.activity = activity;
            this.note = note;
        }
    }

    /** 候选列表。包名来自社区项目经验，因厂商版本差异，均支持用户自定义覆盖。 */
    public static final List<Target> TARGETS = Arrays.asList(
            new Target("OPPO Car+", "com.oplus.ocar", "com.oplus.ocar.CarlifeActivity",
                    "需 ColorOS 且已装 Car+；能否进入 CarLife 模式取决于版本与是否已连车机"),
            new Target("小米 CarWith", "com.miui.carlink", "",
                    "HyperOS 版本包名可能变化，找不到时用自定义输入"),
            new Target("vivo Jovi InCar", "com.vivo.carassistant", "",
                    "需在系统设置中启用互联"),
            new Target("华为 HiCar", "com.huawei.hicar", "",
                    "部分机型为系统内置，可能不允许外部拉起"),
            new Target("百度 CarLife+", "com.baidu.carlife", "",
                    "通用 CarLife 手机端；同样需车机端配对")
    );

    private CarAppLauncher() {
    }

    /** 返回本机已安装的候选。 */
    public static List<Target> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        List<Target> result = new ArrayList<>();
        for (Target t : TARGETS) {
            if (isInstalled(pm, t.packageName)) result.add(t);
        }
        return result;
    }

    public static boolean isInstalled(Context context, String packageName) {
        return isInstalled(context.getPackageManager(), packageName);
    }

    private static boolean isInstalled(PackageManager pm, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 诊断指定包名：是否安装、版本、是否存在显式组件、系统启动入口，以及 CarLife 端口是否在监听。
     *
     * @return 多行可读诊断文本
     */
    public static String diagnose(Context context, String packageName, String activity) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = context.getPackageManager();
        String pkg = packageName == null ? "" : packageName.trim();

        if (pkg.isEmpty()) {
            sb.append("包名为空。\n");
        } else {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                sb.append("✓ 已安装 ").append(pkg).append("\n");
                sb.append("  版本 ").append(info.versionName)
                        .append(" (code ").append(info.versionCode).append(")\n");
            } catch (Exception e) {
                sb.append("✗ 未安装 ").append(pkg).append("\n");
                sb.append("  本机可能不是对应品牌系统（如 ColorOS），或未安装该 App。\n");
            }

            if (activity != null && !activity.trim().isEmpty()) {
                Intent explicit = new Intent();
                explicit.setComponent(new ComponentName(pkg, activity.trim()));
                ResolveInfo resolved = pm.resolveActivity(explicit, 0);
                if (resolved != null) {
                    sb.append("✓ 组件 ").append(activity.trim()).append(" 可解析\n");
                } else {
                    sb.append("✗ 组件 ").append(activity.trim())
                            .append(" 不存在或不可导出\n");
                }
            }
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            sb.append(launch != null ? "✓ 有系统启动入口\n" : "✗ 无系统启动入口\n");
        }

        sb.append("\nCarLife 端口状态（127.0.0.1）：\n");
        int listening = 0;
        StringBuilder ports = new StringBuilder();
        for (int port : CARLIFE_PORTS) {
            boolean open = portOpen(port, 350);
            if (open) listening++;
            ports.append(port).append(open ? "✓ " : "✗ ");
        }
        sb.append("  ").append(ports.toString()).append("\n");
        if (listening == 0) {
            sb.append("✗ 未检测到 CarLife 端口监听。\n")
                    .append("  Car+ 尚未进入 CarLife 模式——通常需要先连接支持\n")
                    .append("  ICCOA / CarLife+ / 亿连的车机。极核仪表不是这类车机，\n")
                    .append("  因此本场景CarLife 直连大概率不可用，请改用录屏投屏。\n");
        } else if (listening < CARLIFE_PORTS.length) {
            sb.append("△ 仅 ").append(listening).append("/").append(CARLIFE_PORTS.length)
                    .append(" 个端口监听，CarLife 可能正在启动。\n");
        } else {
            sb.append("✓ 7 个端口全部监听，Car+ 已进入 CarLife 模式，可尝试 CarLife 投屏。\n");
        }
        return sb.toString();
    }

    /** 探测本机端口是否有服务监听（连接成功即认为在监听）。 */
    private static boolean portOpen(int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 拉起车机互联界面。优先显式组件，失败回退到系统 launch intent。
     *
     * <p>注意：拉起 App 界面不等于进入 CarLife 模式。是否建立 7 个端口由 Car+ 自身决定，
     * 可用 {@link #diagnose(Context, String, String)} 确认。
     *
     * @return 可读的结果描述
     */
    public static String launch(Context context, String packageName, String activity) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "包名为空，请填写车机互联 App 的包名";
        }
        packageName = packageName.trim();
        PackageManager pm = context.getPackageManager();
        Intent chosen = null;
        String path = "";

        if (activity != null && !activity.trim().isEmpty()) {
            Intent explicit = new Intent();
            explicit.setComponent(new ComponentName(packageName, activity.trim()));
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(explicit, 0) != null) {
                chosen = explicit;
                path = "组件 " + activity.trim();
            }
        }
        if (chosen == null) {
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                chosen = launch;
                path = "系统启动入口";
            }
        }
        if (chosen == null) {
            String msg = "未找到可拉起的界面：" + packageName
                    + "。请确认已安装，或到系统设置开启互联。\n可点「诊断」查看详细原因。";
            LogBus.log(msg);
            return msg;
        }
        try {
            context.startActivity(chosen);
            String msg = "已拉起 " + packageName + "（" + path + "）。\n"
                    + "注意：拉起界面不代表已进入 CarLife 模式；请点「诊断」确认 7 个端口是否在监听。\n"
                    + "若端口未监听，说明 Car+ 需要先连接车机，本场景请改用录屏投屏。";
            LogBus.log("已拉起 " + packageName + "（" + path + "）");
            return msg;
        } catch (Exception e) {
            String msg = "拉起失败：" + e.getMessage()
                    + "。\n部分系统不允许外部拉起车机 App，可手动打开后点「诊断」。";
            LogBus.log(msg);
            return msg;
        }
    }

    /** 打开系统设置中的应用管理，方便用户查看已安装车机 App 的包名。 */
    public static void openAppSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            LogBus.log("打开应用设置失败：" + e.getMessage());
        }
    }
}
