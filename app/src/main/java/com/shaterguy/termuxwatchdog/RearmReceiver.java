package com.shaterguy.termuxwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public final class RearmReceiver extends BroadcastReceiver {
    private static final String EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // Protected system actions prove old Termux processes can no longer be queued.
            // Boot count avoids treating unlock's second broadcast as another restart.
            try {
                new PowerModeStore(context).onDeviceBoot(Settings.Global.getInt(
                        context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
            } catch (RuntimeException ignored) {
                // Keep the gate and latch when boot identity cannot be verified.
            }
        }
        // The on-demand Binder receiver needs no boot-time service or Google call.
        WatchdogStateStore stateStore = new WatchdogStateStore(context);
        if (!stateStore.monitoringEnabled()) {
            AlarmScheduler.cancelBackup(context);
            return;
        }

        AlarmScheduler.scheduleBackup(context);
        if (!EXACT_ALARM_PERMISSION_CHANGED.equals(action)) {
            WatchdogService.requestStart(context);
        }
    }
}
