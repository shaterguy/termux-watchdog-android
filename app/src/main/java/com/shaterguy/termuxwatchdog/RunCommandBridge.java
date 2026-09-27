package com.shaterguy.termuxwatchdog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public final class RunCommandBridge {
    private RunCommandBridge() {}

    public static DispatchResult dispatch(Context context) {
        if (context.checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            return DispatchResult.failed("permission_not_granted");
        }

        Intent intent = new Intent(RecoveryContract.RUN_COMMAND_ACTION);
        intent.setComponent(new ComponentName(
                RecoveryContract.TERMUX_PACKAGE,
                RecoveryContract.RUN_COMMAND_SERVICE_CLASS));
        intent.putExtra(RecoveryContract.EXTRA_COMMAND_PATH, RecoveryCommandSpec.path());
        intent.putExtra(RecoveryContract.EXTRA_ARGUMENTS, RecoveryCommandSpec.arguments());
        intent.putExtra(RecoveryContract.EXTRA_BACKGROUND, RecoveryCommandSpec.background());
        intent.putExtra(RecoveryContract.EXTRA_WORKDIR, RecoveryCommandSpec.workdir());

        try {
            ComponentName started = context.startService(intent);
            if (started == null) {
                return DispatchResult.failed("termux_service_not_started");
            }
            return DispatchResult.dispatched();
        } catch (RuntimeException e) {
            return DispatchResult.failed(e.getClass().getSimpleName());
        }
    }

    public static final class DispatchResult {
        private final boolean dispatched;
        private final String detail;

        private DispatchResult(boolean dispatched, String detail) {
            this.dispatched = dispatched;
            this.detail = detail;
        }

        public static DispatchResult dispatched() {
            return new DispatchResult(true, "dispatched");
        }

        public static DispatchResult failed(String detail) {
            return new DispatchResult(false, detail == null ? "unknown" : detail);
        }

        public boolean wasDispatched() {
            return dispatched;
        }

        public String detail() {
            return detail;
        }
    }
}
