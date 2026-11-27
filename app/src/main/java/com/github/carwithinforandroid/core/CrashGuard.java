package com.github.carwithinforandroid.core;

import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 崩溃兜底：把未捕获异常的堆栈写入运行日志，并持久化到 SharedPreferences，
 * 下次启动时自动回放到「运行日志」卡片，用户可点「复制日志」回传，方便无 adb 时排查。
 */
public final class CrashGuard {

    private static final String PREFS = "carwithin_crash";
    private static final String KEY_LAST = "last_crash";
    private static volatile boolean installed;

    private CrashGuard() {
    }

    /** 安装全局未捕获异常处理器，应在 Activity onCreate 最前面调用。 */
    public static void install(Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String trace = describe(throwable);
                LogBus.log("!! 崩溃：" + throwable.getClass().getSimpleName());
                for (String line : trace.split("\n")) {
                    LogBus.log(line);
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST, trace)
                        .apply();
            } catch (Throwable ignored) {
                // 兜底里再抛异常就没有意义了
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /** 把上次崩溃堆栈回放到运行日志里（仅回放一次，回放后清除）。 */
    public static void restoreLastCrash(Context context) {
        try {
            String trace = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST, null);
            if (trace == null || trace.trim().isEmpty()) return;
            LogBus.log("上次运行崩溃，堆栈如下（点「复制日志」可回传）：");
            for (String line : trace.split("\n")) {
                LogBus.log(line);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearLastCrash(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_LAST).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
