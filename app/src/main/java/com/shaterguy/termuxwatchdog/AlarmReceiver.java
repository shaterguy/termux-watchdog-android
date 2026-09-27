package com.shaterguy.termuxwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmScheduler.scheduleBackup(context);
        WatchdogService.requestStart(context);
    }
}
