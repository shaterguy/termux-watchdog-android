package com.shaterguy.termuxwatchdog;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

final class PowerModeBridge {
    static final String ACTION = "com.shaterguy.termuxwatchdog.POWER_MODE_RESULT";
    static final String SCRIPT = RecoveryContract.TERMUX_HOME + "/bin/termux-watchdog-mode";
    private PowerModeBridge() {}

    static void send(Context context, String command) {
        if (!command.equals("status") && !command.equals("enable") && !command.equals("disable")) {
            throw new IllegalArgumentException("MODE_COMMAND_INVALID");
        }
        PowerModeStore store = new PowerModeStore(context);
        if (command.equals("enable") && !store.canEnable()) {
            store.unavailable("MODE_BACKEND_NOT_READY");
            return;
        }
        if (context.checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            store.unavailable("TERMUX_PERMISSION_REQUIRED");
            return;
        }
        final PowerModeStore.Request request;
        try { request = store.register(command); }
        catch (IllegalStateException error) { return; }
        if (request == null) return;
        if (!command.equals("status")) suspendRecovery(context);
        Intent callback = new Intent(context, PowerModeResultReceiver.class)
                .setAction(ACTION)
                .setData(Uri.parse("termux-watchdog://power-mode/" + request.nonce));
        // Explicit one-shot capability; Termux must fill result extras. Receiver is not exported.
        PendingIntent result = PendingIntent.getBroadcast(context, 41003, callback,
                PendingIntent.FLAG_ONE_SHOT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
        Intent intent = new Intent(RecoveryContract.RUN_COMMAND_ACTION);
        intent.setComponent(new ComponentName(RecoveryContract.TERMUX_PACKAGE,
                RecoveryContract.RUN_COMMAND_SERVICE_CLASS));
        intent.putExtra(RecoveryContract.EXTRA_COMMAND_PATH, SCRIPT);
        intent.putExtra(RecoveryContract.EXTRA_ARGUMENTS, command.equals("status")
                ? new String[]{command} : new String[]{command, request.nonce, Long.toString(request.generation)});
        intent.putExtra(RecoveryContract.EXTRA_BACKGROUND, true);
        intent.putExtra(RecoveryContract.EXTRA_WORKDIR, RecoveryContract.TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
        try {
            if (context.startService(intent) == null) throw new IllegalStateException();
        } catch (RuntimeException error) {
            result.cancel();
            store.consume(request.nonce);
            store.terminalFailure(request, "TERMUX_COMMAND_UNAVAILABLE");
        }
    }

    static void suspendRecovery(Context context) {
        AlarmScheduler.cancelBackup(context);
        context.stopService(new Intent(context, WatchdogService.class));
    }

    static void reconcileMonitoring(Context context) {
        if (!new WatchdogStateStore(context).monitoringEnabled()) {
            suspendRecovery(context);
        } else if (!WatchdogService.isRunningInProcess()) {
            AlarmScheduler.scheduleBackup(context);
            WatchdogService.requestStart(context);
        }
    }
}
