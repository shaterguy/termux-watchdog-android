package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Accept only a one-shot PendingIntent result for an outstanding fixed command. */
public final class PowerModeResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !PowerModeBridge.ACTION.equals(intent.getAction())) return;
        Uri identity = intent.getData();
        if (identity == null || !"termux-watchdog".equals(identity.getScheme())
                || !"power-mode".equals(identity.getHost())) return;
        String nonce = identity.getLastPathSegment();
        if (nonce == null) return;
        PowerModeStore store = new PowerModeStore(context);
        PowerModeStore.Request request = store.consume(nonce);
        if (request == null) return;
        try {
            Bundle result = intent.getBundleExtra("result");
            if (result == null || !(result.get("err") instanceof Integer)
                    || ((Integer) result.get("err")) != Activity.RESULT_OK
                    || !(result.get("exitCode") instanceof Integer)
                    || ((Integer) result.get("exitCode")) != 0) {
                store.terminalFailure(request, "MODE_COMMAND_FAILED");
                return;
            }
            String output = result.getString("stdout");
            if (output == null || output.length() > PowerModeSnapshot.MAX_LENGTH) {
                store.terminalFailure(request, "MODE_RESPONSE_INVALID");
                return;
            }
            // Termux supplies lengths as String; accept no truncation or malformed length.
            Object length = result.get("stdout_original_length");
            if (length != null && Long.parseLong(length.toString()) != output.length()) {
                store.terminalFailure(request, "MODE_RESPONSE_TRUNCATED");
                return;
            }
            if (store.observe(request, output)) {
                NativeAuthBridge.pauseIfSettled(context);
                PowerModeBridge.reconcileMonitoring(context);
            }
        } catch (RuntimeException error) {
            store.terminalFailure(request, "MODE_RESPONSE_INVALID");
        }
    }
}
