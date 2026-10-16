package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/** Only fixed, correlated lifecycle commands cross the already granted RUN_COMMAND boundary. */
final class LifecycleBridge {
    static final String ACTION = "com.shaterguy.termuxwatchdog.LIFECYCLE_RESULT";
    static final String SCRIPT = RecoveryContract.TERMUX_HOME + "/bin/termux-watchdog-lifecycle";
    private LifecycleBridge() {}

    static int currentTermuxUid(Context context) {
        try {
            int uid = context.getPackageManager().getApplicationInfo(RecoveryContract.TERMUX_PACKAGE, 0).uid;
            // Fresh package/signer verification does not prove the UID has only one package.
            return new AuthPeerVerifier(context).verify(uid) ? uid : -1;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { return -1; }
    }

    static void begin(Context context, String action) {
        LifecycleStore store = new LifecycleStore(context);
        if (context.checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            store.noteError("TERMUX_PERMISSION_REQUIRED"); return;
        }
        LifecycleStore.Request request = store.begin(action, currentTermuxUid(context));
        if (request == null) return;
        PowerModeBridge.suspendRecovery(context);
        dispatch(context, request);
    }

    static boolean dispatch(Context context, LifecycleStore.Request request) {
        LifecycleStore store = new LifecycleStore(context);
        if (!LifecycleSnapshot.validAction(request.action)
                || store.pending(request.action, request.nonce) == null) return false;
        if (currentTermuxUid(context) != request.uid) {
            store.fail(request, "UID_MISMATCH"); return false;
        }
        if (context.checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            store.fail(request, "TERMUX_PERMISSION_REQUIRED"); return false;
        }
        Intent callback = new Intent(context, LifecycleResultReceiver.class).setAction(ACTION)
                .setData(Uri.parse("termux-watchdog://lifecycle/" + request.action + "/" + request.nonce));
        PendingIntent result = PendingIntent.getBroadcast(context, 41006, callback,
                PendingIntent.FLAG_ONE_SHOT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
        Intent command = new Intent(RecoveryContract.RUN_COMMAND_ACTION)
                .setComponent(new ComponentName(RecoveryContract.TERMUX_PACKAGE,
                        RecoveryContract.RUN_COMMAND_SERVICE_CLASS));
        command.putExtra(RecoveryContract.EXTRA_COMMAND_PATH, SCRIPT);
        command.putExtra(RecoveryContract.EXTRA_ARGUMENTS, new String[]{"v1", request.action, request.nonce,
                Long.toString(request.sequence), Integer.toString(request.uid)});
        command.putExtra(RecoveryContract.EXTRA_BACKGROUND, true);
        command.putExtra(RecoveryContract.EXTRA_WORKDIR, RecoveryContract.TERMUX_HOME);
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
        try {
            if (context.startService(command) == null) throw new IllegalStateException();
            return true;
        } catch (RuntimeException ignored) {
            result.cancel();
            store.fail(request, "TERMUX_COMMAND_UNAVAILABLE"); return false;
        }
    }

    static void launchReady(Activity activity) {
        LifecycleStore store = new LifecycleStore(activity);
        LifecycleStore.Request request = store.takeLaunch();
        if (request == null) return;
        if (currentTermuxUid(activity) != request.uid) {
            store.fail(request, "UID_MISMATCH"); return;
        }
        // Start the bounded observation while still foreground. It waits for the new login.
        if (!dispatch(activity, request)) return;
        Intent launch = new Intent(Intent.ACTION_RUN)
                .setComponent(new ComponentName(RecoveryContract.TERMUX_PACKAGE, "com.termux.app.TermuxActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            // Mirrors Termux's official New session shortcut; does not replay a service list.
            activity.startActivity(launch);
        } catch (RuntimeException ignored) {
            store.fail(request, "TERMUX_ACTIVITY_UNAVAILABLE");
        }
    }
}
