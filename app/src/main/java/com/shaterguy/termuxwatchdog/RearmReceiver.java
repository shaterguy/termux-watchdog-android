package com.shaterguy.termuxwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RearmReceiver extends BroadcastReceiver {
    private static final String EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        WatchdogStateStore stateStore = new WatchdogStateStore(context);
        if (!stateStore.monitoringEnabled()) {
            AlarmScheduler.cancelBackup(context);
            return;
        }

        AlarmScheduler.scheduleBackup(context);
        String action = intent == null ? null : intent.getAction();
        if (!EXACT_ALARM_PERMISSION_CHANGED.equals(action)) {
            WatchdogService.requestStart(context);
        }
    }
}
