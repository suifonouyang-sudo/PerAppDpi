package com.perapp.dpi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** 监控常驻通知：既是前台服务的载体，也是随时能停的出口 */
public final class Notif {

    public static final String CH = "dpi_watch";
    public static final int ID = 8801;

    private Notif() {
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CH) != null) return;
            // 用 DEFAULT：IMPORTANCE_LOW 会把通知折叠，action 按钮根本不显示
            NotificationChannel ch = new NotificationChannel(CH, "密度监控",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            AppLog.i("NOTIF", "建渠道失败: " + t);
        }
    }

    public static Notification build(Context c, String title, String content, boolean paused) {
        ensureChannel(c);

        PendingIntent open = PendingIntent.getActivity(c, 10,
                new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent toggle = PendingIntent.getService(c, 11,
                new Intent(c, WatchService.class).setAction(WatchService.ACT_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stop = PendingIntent.getService(c, 12,
                new Intent(c, WatchService.class).setAction(WatchService.ACT_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(open)
                .addAction(0, paused ? "继续" : "暂停", toggle)
                .addAction(0, "停止并还原", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
