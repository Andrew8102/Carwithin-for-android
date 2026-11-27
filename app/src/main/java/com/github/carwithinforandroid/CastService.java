package com.github.carwithinforandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.github.carwithinforandroid.core.LogBus;

/** 前台服务：Android 14+ 要求屏幕采集必须在 mediaProjection 类型前台服务中运行。 */
public final class CastService extends Service {

    public static final String ACTION_START = "com.github.carwithinforandroid.START";
    public static final String ACTION_STOP = "com.github.carwithinforandroid.STOP";

    private static final String CHANNEL_ID = "carwithin.cast";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("CarWithin 投屏服务待命"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProjection();
        } else if (ACTION_START.equals(action)) {
            startForeground(NOTIFICATION_ID, notification("CarWithin 正在连接极核仪表"));
        }
        updateNotification("CarWithin 投屏服务运行中");
        return START_STICKY;
    }

    private void stopProjection() {
        com.github.carwithinforandroid.core.CastSession.get().stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "投屏服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("屏幕采集与仪表投屏运行通知");
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CarWithin for Android")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {
        LogBus.log("投屏服务结束");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
