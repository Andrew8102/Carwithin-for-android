package com.github.carwithinforandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import com.github.carwithinforandroid.core.CastSession;
import com.github.carwithinforandroid.core.LogBus;

/**
 * 前台服务：Android 14+ 要求屏幕采集必须在 mediaProjection 类型前台服务中运行，
 * 并且<strong>先启动前台服务、再调用 {@link MediaProjectionManager#getMediaProjection(int, Intent)}</strong>，
 * 否则会抛 SecurityException: Media projections require a foreground service of type
 * ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION。
 *
 * <p>因此录屏令牌（resultCode + data）由界面层通过 Intent 传进来，由本服务在
 * {@link #onStartCommand} 里先 {@code startForeground(type)}、再取 MediaProjection，
 * 顺序符合 Android 14 的要求。
 */
public final class CastService extends Service {

    public static final String ACTION_START = "com.github.carwithinforandroid.START";
    public static final String ACTION_STOP = "com.github.carwithinforandroid.STOP";

    /** 录屏授权返回的 resultCode。 */
    public static final String EXTRA_RESULT_CODE = "resultCode";
    /** 录屏授权返回的 data Intent。 */
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "carwithin.cast";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProjection();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            // 必须先进入前台（并声明 mediaProjection 类型），再取录屏令牌。
            startAsForeground("CarWithin 正在连接极核仪表");
            startProjection(intent);
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground(String text) {
        Notification notification = notification(text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            // 例如未授予录屏权限时系统拒绝该类型，此时记录日志而不是让 App 崩溃。
            LogBus.log("前台服务启动失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (data == null) {
            LogBus.log("缺少录屏授权数据，无法开始投屏（请重新点开始投屏并允许录屏）");
            return;
        }
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                LogBus.log("当前系统不支持屏幕采集");
                return;
            }
            MediaProjection projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) {
                LogBus.log("获取 MediaProjection 失败，授权可能已失效，请重试");
                return;
            }
            CastSession.get().start(this, projection, null);
        } catch (Throwable e) {
            LogBus.log("启动屏幕采集失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void stopProjection() {
        try {
            CastSession.get().stop();
        } catch (Throwable e) {
            LogBus.log("停止投屏出错：" + e.getMessage());
        }
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
