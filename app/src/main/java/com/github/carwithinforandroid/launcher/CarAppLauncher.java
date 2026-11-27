package com.github.carwithinforandroid.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import com.github.carwithinforandroid.core.LogBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 车机互联界面启动器。手机系统通常不会主动显示 Car+ / CarWith 界面，
 * 需要显式拉起它们的组件，让其在本地监听端口，之后才能录屏投到仪表。
 */
public final class CarAppLauncher {

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
                    "CarNine 已验证的组件；一加/realme 同为 ColorOS 通常通用"),
            new Target("小米 CarWith", "com.miui.carlink", "",
                    "HyperOS 版本包名可能变化，找不到时用自定义输入"),
            new Target("vivo Jovi InCar", "com.vivo.carassistant", "",
                    "需在系统设置中启用互联"),
            new Target("华为 HiCar", "com.huawei.hicar", "",
                    "部分机型为系统内置，可能不允许外部拉起"),
            new Target("百度 CarLife+", "com.baidu.carlife", "",
                    "通用 CarLife 手机端")
    );

    private CarAppLauncher() {
    }

    /** 返回本机已安装的候选（含自定义包名由调用方追加）。 */
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
     * 拉起车机界面。优先显式组件，失败回退到系统 launch intent。
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
            String msg = "未找到可拉起的界面：" + packageName + "。请确认已安装，或到系统设置开启互联。";
            LogBus.log(msg);
            return msg;
        }
        try {
            context.startActivity(chosen);
            String msg = "已拉起 " + packageName + "（" + path + "），等待其开始监听投屏端口…";
            LogBus.log(msg);
            return msg;
        } catch (Exception e) {
            String msg = "拉起失败：" + e.getMessage();
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
