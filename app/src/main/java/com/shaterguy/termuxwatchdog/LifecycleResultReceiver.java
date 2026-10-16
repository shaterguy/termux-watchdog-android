package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/** Non-exported receiver for the fixed one-shot result capability issued to Termux. */
public final class LifecycleResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !LifecycleBridge.ACTION.equals(intent.getAction())) return;
        Uri identity = intent.getData();
        if (identity == null || !"termux-watchdog".equals(identity.getScheme())
                || !"lifecycle".equals(identity.getHost()) || identity.getQuery() != null
                || identity.getFragment() != null) return;
        List<String> path = identity.getPathSegments();
        if (path.size() != 2 || !LifecycleSnapshot.validAction(path.get(0))
                || !path.get(1).matches(LifecycleSnapshot.UUID_PATTERN)) return;
        LifecycleStore store = new LifecycleStore(context);
        LifecycleStore.Request request = store.pending(path.get(0), path.get(1));
        if (request == null) return;
        try {
            Bundle result = intent.getBundleExtra("result");
            if (result == null || !(result.get("err") instanceof Integer)
                    || ((Integer) result.get("err")) != Activity.RESULT_OK
                    || !(result.get("exitCode") instanceof Integer)
                    || ((Integer) result.get("exitCode")) != 0) {
                store.fail(request, "LIFECYCLE_COMMAND_FAILED"); return;
            }
            String output = result.getString("stdout");
            if (output == null || output.length() > LifecycleSnapshot.MAX_LENGTH) {
                store.fail(request, "LIFECYCLE_RESPONSE_INVALID"); return;
            }
            Object length = result.get("stdout_original_length");
            if (length != null && Long.parseLong(length.toString()) != output.length()) {
                store.fail(request, "LIFECYCLE_RESPONSE_TRUNCATED"); return;
            }
            LifecycleSnapshot receipt = LifecycleSnapshot.parse(output);
            LifecycleStore.Request next = store.observe(request, receipt,
                    LifecycleBridge.currentTermuxUid(context), output);
            if (next != null) LifecycleBridge.dispatch(context, next);
            PowerModeBridge.reconcileMonitoring(context);
        } catch (RuntimeException ignored) {
            store.fail(request, "LIFECYCLE_RESPONSE_INVALID");
        }
    }
}
