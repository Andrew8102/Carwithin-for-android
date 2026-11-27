package com.github.carwithinforandroid.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内轻量日志总线：协议层写入，界面层轮询展示。 */
public final class LogBus {

    private static final int MAX = 120;
    private static final CopyOnWriteArrayList<String> LINES = new CopyOnWriteArrayList<>();

    private LogBus() {
    }

    public static void log(String message) {
        if (message == null || message.isEmpty()) return;
        String line = java.text.SimpleDateFormat.getTimeInstance(java.text.SimpleDateFormat.MEDIUM)
                .format(new java.util.Date()) + "  " + message;
        LINES.add(line);
        while (LINES.size() > MAX) {
            LINES.remove(0);
        }
        android.util.Log.i("CarWithin", message);
    }

    public static List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static void clear() {
        LINES.clear();
    }
}
