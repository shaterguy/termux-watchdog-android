package com.shaterguy.termuxwatchdog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public final class AlarmScheduler {
    private static final long BACKUP_INTERVAL_MS = 15L * 60L * 1000L;
    private static final int REQUEST_CODE = 41001;

    private AlarmScheduler() {}

    public static void scheduleBackup(Context context) {
        if (!new WatchdogStateStore(context).monitoringEnabled()) {
            cancelBackup(context);
            return;
        }

        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }

        PendingIntent pending = backupPendingIntent(context);
        long triggerAt = SystemClock.elapsedRealtime() + BACKUP_INTERVAL_MS;
        alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending);
    }

    public static void cancelBackup(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) {
            alarms.cancel(backupPendingIntent(context));
        }
    }

    private static PendingIntent backupPendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
