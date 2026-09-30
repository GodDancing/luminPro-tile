package com.luminpro.tile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 可选的「持续保持」服务。
 *
 * <p>默认不启用：与模块的 boost 行为一致，直接写一次亮度节点即可长期有效，
 * 系统在用户不主动改亮度时不会回写。只有开启自动亮度、或系统会被动改写
 * 亮度节点的设备，才需要这个常驻服务周期性把亮度拉回目标值。
 *
 * <p>服务只做一件事：按 {@code interval_ms} 的节奏读一次节点，与目标值不符就写回。
 */
public class LockService extends Service {

    static final String ACTION_START = "com.luminpro.tile.START";
    static final String ACTION_STOP = "com.luminpro.tile.STOP";

    private static final String CHANNEL_ID = "luminpro_lock";
    private static final int NOTIFICATION_ID = 0x4C50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private volatile boolean busy;
    private boolean foreground;

    /** 启动保持服务；失败（如后台启动限制）时降级为一次性写入。 */
    static void start(Context ctx) {
        Intent intent = new Intent(ctx, LockService.class).setAction(ACTION_START);
        try {
            ctx.startForegroundService(intent);
        } catch (Throwable t) {
            Logx.w("无法启动保持服务，已降级为一次性写入: " + t.getMessage());
        }
    }

    static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, LockService.class));
        } catch (Throwable ignored) {
            // 服务未运行时忽略
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Bg.run(() -> {
                BrightnessController.unlock(getApplicationContext());
                stopSelf();
            });
            return START_NOT_STICKY;
        }

        // 服务是被 startForegroundService 拉起来的，系统要求 5 秒内必须调用
        // startForeground，否则直接以 RemoteServiceException 杀掉整个进程。
        // 前台化失败时只能立刻退出，绝不能继续跑下去。
        if (!goForeground()) {
            Logx.w("保持服务无法进入前台，已退出（锁定本身仍然生效，只是不再持续拉回）");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            Logx.i("保持服务已启动，间隔 " + Prefs.intervalMs(this) + " ms");
            scheduleTick();
        }
        return START_STICKY;
    }

    private void scheduleTick() {
        handler.postDelayed(() -> {
            if (!running) {
                return;
            }
            if (!Prefs.enabled(this) || !Prefs.keepAlive(this)) {
                Logx.i("锁定已结束，保持服务退出");
                stopSelf();
                return;
            }
            if (!busy) {
                busy = true;
                Bg.run(() -> {
                    try {
                        BrightnessController.holdTick(getApplicationContext());
                    } finally {
                        busy = false;
                    }
                });
            }
            scheduleTick();
        }, Prefs.intervalMs(this));
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Logx.i("保持服务已停止");
        super.onDestroy();
    }

    /** 前台化。返回是否成功 —— 失败时调用方必须立刻退出服务。 */
    private boolean goForeground() {
        if (foreground) {
            return true;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.notif_channel_desc));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stopSelfIntent = PendingIntent.getService(this, 1,
                    new Intent(this, LockService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(getString(R.string.notif_title))
                    .setContentText(Prefs.target(this) + " / " + Prefs.nodeMax(this))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.ic_tile),
                            getString(R.string.notif_stop),
                            stopSelfIntent).build())
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            return true;
        } catch (Throwable t) {
            Logx.w("保持服务前台化失败: " + t.getMessage());
            return false;
        }
    }
}
