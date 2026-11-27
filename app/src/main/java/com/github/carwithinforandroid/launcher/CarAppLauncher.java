package com.github.carwithinforandroid.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import com.github.carwithinforandroid.core.LogBus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 车机互联界面启动器 + 诊断。
 *
 * <p>OPPO Car+（com.oplus.ocar）是手机端 CarLife（MD）。实测本项目已能让它在本机
 * 127.0.0.1 打开 7 个端口（说明 HU 确实可以由同一台手机内的 App 扮演），但<b>窗口期极短</b>：
 * Car+ 拉起后端口开一下就因无人连入而关闭。因此正确顺序是「先让 CarLifeLink 在端口上等，
 * 再去拉起 Car+」，而不是「先拉起、再手动点直连」。
 *
 * <p>本类提供：① 拉起 Car+ 入口（{@link #launch}）；② 诊断安装/组件/端口（{@link #diagnose}）；
 * ③ 枚举全部 Activity 并逐个试探（{@link #tryEnterCarlife}）；④ 挑出最可能的入口
 * （{@link #bestCandidate}）供一键流程使用。
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
                    .append("  Car+ 还没进入 CarLife 模式。点「尝试进 CarLife」会枚举入口\n")
                    .append("  并逐个拉起试探；也可以直接用「录屏投屏」。\n");
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
                    + "拉起界面不等于进入 CarLife 模式；点「诊断」看 7 个端口，"
                    + "或用「尝试进 CarLife」自动逐个入口试探。";
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

    /* ------------------------------------------------------------ 组件枚举与尝试 */

    /** 一个已声明的 Activity。 */
    public static final class ActivityEntry {
        public final String name;
        public final boolean exported;
        public final boolean enabled;
        /** 名字里含 carlife/cast/mirror/link 等字样，优先尝试。 */
        public final boolean likelyCarLife;

        ActivityEntry(String name, boolean exported, boolean enabled) {
            this.name = name;
            this.exported = exported;
            this.enabled = enabled;
            this.likelyCarLife = isLikelyCarLife(name);
        }

        public String shortName() {
            int i = name.lastIndexOf('.');
            return i < 0 ? name : name.substring(i + 1);
        }
    }

    private static boolean isLikelyCarLife(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("carlife") || n.contains("carplay") || n.contains("cast")
                || n.contains("mirror") || n.contains("carlink") || n.contains("icar");
    }

    /**
     * 枚举某个包声明的全部 Activity。
     *
     * <p>这是解决「找不到可拉起界面」的关键：不同 ColorOS / Car+ 版本里 CarLife 入口类名不一样，
     * 而且未导出的组件 {@code resolveActivity} 会返回 null，必须直接从 PackageInfo 里取。
     * 依赖 AndroidManifest 的 {@code <queries>} 包可见性声明。
     */
    public static List<ActivityEntry> activities(Context context, String packageName) {
        List<ActivityEntry> out = new ArrayList<>();
        if (packageName == null || packageName.trim().isEmpty()) return out;
        String pkg = packageName.trim();
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(pkg,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
            } else {
                info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            }
        } catch (Throwable e) {
            LogBus.log("枚举组件失败：" + pkg + " " + e.getMessage());
            return out;
        }
        ActivityInfo[] acts = info.activities;
        if (acts == null) return out;
        for (ActivityInfo ai : acts) {
            if (ai == null || ai.name == null) continue;
            out.add(new ActivityEntry(ai.name, ai.exported, ai.enabled));
        }
        Collections.sort(out, (a, b) -> {
            if (a.likelyCarLife != b.likelyCarLife) return a.likelyCarLife ? -1 : 1;
            return a.name.compareTo(b.name);
        });
        return out;
    }

    /** 格式化成可读列表，★ 标记疑似 CarLife 入口。 */
    public static String describeActivities(Context context, String packageName) {
        List<ActivityEntry> list = activities(context, packageName);
        if (list.isEmpty()) {
            return "枚举不到 " + packageName + " 的 Activity。\n"
                    + "可能原因：未安装、被系统隐藏，或包可见性声明未命中。";
        }
        StringBuilder sb = new StringBuilder("共 " + list.size()
                + " 个 Activity（★ = 疑似 CarLife 入口）：\n");
        for (ActivityEntry e : list) {
            sb.append(e.likelyCarLife ? "★ " : "· ").append(e.name);
            if (!e.exported) sb.append("  [未导出]");
            if (!e.enabled) sb.append("  [已禁用]");
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 当前有几个 CarLife 端口在监听。 */
    public static int countOpenPorts(int timeoutMs) {
        int n = 0;
        for (int port : CARLIFE_PORTS) {
            if (portOpen(port, timeoutMs)) n++;
        }
        return n;
    }

    /**
     * 挑出最可能的 CarLife 入口：疑似、已导出、已启用，按枚举顺序取第一个。
     *
     * @return Activity 全名；没有合适候选时返回 null
     */
    public static String bestCandidate(Context context, String packageName) {
        for (ActivityEntry e : activities(context, packageName)) {
            if (e.likelyCarLife && e.exported && e.enabled) return e.name;
        }
        return null;
    }

    /** 进度回调（在调用线程触发，UI 侧需自行切主线程）。 */
    public interface Progress {
        void onProgress(String text);
    }

    /**
     * 逐个拉起「疑似 CarLife」的可导出 Activity，每次拉起后轮询 7 个端口，命中即停。
     *
     * <p>阻塞方法，必须在后台线程调用。端口在 127.0.0.1 上未监听时会立刻 RST，
     * 所以每轮探测很快；一旦 7 个端口全部监听，说明 Car+ 已交出了镜像 Surface 并进入 CarLife 模式。
     *
     * @return 多行可读报告
     */
    public static String tryEnterCarlife(Context context, String packageName, Progress progress) {
        final int perTryMs = 2500;
        if (packageName == null || packageName.trim().isEmpty()) {
            return "包名为空，请先填写车机互联 App 的包名。";
        }
        String pkg = packageName.trim();
        List<ActivityEntry> all = activities(context, pkg);
        if (all.isEmpty()) {
            return "枚举不到 " + pkg + " 的组件，无法尝试。\n请先确认已安装、且包名正确。";
        }

        int already = countOpenPorts(200);
        if (already == CARLIFE_PORTS.length) {
            return "✓ 7 个端口已经在监听，Car+ 已处于 CarLife 模式，直接点「CarLife 直连」即可。";
        }

        List<ActivityEntry> candidates = new ArrayList<>();
        for (ActivityEntry e : all) {
            if (e.likelyCarLife && e.exported && e.enabled) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            return "没有「疑似 CarLife 且可外部拉起」的 Activity。\n\n"
                    + describeActivities(context, pkg);
        }

        StringBuilder sb = new StringBuilder("依次尝试 " + candidates.size()
                + " 个疑似入口（每个等 " + perTryMs + "ms 观察端口）：\n");
        if (progress != null) progress.onProgress(sb.toString());

        for (ActivityEntry e : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(pkg, e.name));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t) {
                sb.append("✗ ").append(e.shortName()).append(" 拉起失败：")
                        .append(t.getMessage()).append('\n');
                if (progress != null) progress.onProgress(sb.toString());
                continue;
            }
            if (progress != null) progress.onProgress(sb + "已拉起 " + e.shortName() + "，观察端口…");

            int open = 0;
            long deadline = System.currentTimeMillis() + perTryMs;
            while (System.currentTimeMillis() < deadline) {
                open = countOpenPorts(150);
                if (open == CARLIFE_PORTS.length) break;
                sleep(200);
            }
            boolean hit = open == CARLIFE_PORTS.length;
            sb.append(hit ? "✓ " : "△ ").append(e.shortName())
                    .append(" → ").append(open).append("/7 端口\n");
            if (progress != null) progress.onProgress(sb.toString());
            if (hit) {
                sb.append("成功：Car+ 已进入 CarLife 模式，7 个端口已监听。\n")
                        .append("注意：这个窗口只有一两秒，本方法的探测连接会打断它。\n")
                        .append("要真正收流，请回②点「一键：拉起 Car+ 并直连」——它先等端口再拉起。\n");
                LogBus.log("Car+ 已进入 CarLife 模式（由 " + e.name + " 拉起，7/7 端口）");
                return sb.toString();
            }
        }

        sb.append("\n未命中。可能这些入口都不能单独把 Car+ 拉进 CarLife 模式，\n")
                .append("也可能端口开了又立刻关，而本轮没踩上。可点「枚举组件」看全量列表，\n")
                .append("把可疑类名填进 Activity 输入框后，用②的「一键：拉起 Car+ 并直连」再试。\n");
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
