package com.shaterguy.termuxwatchdog;

import java.util.Arrays;

/** Pure safety decisions shared by persistent state and JVM tests. */
final class LifecycleRules {
    private LifecycleRules() {}

    static boolean verifiedBootChanged(String previous, String current) {
        if (previous == null || current == null || previous.equals(current)) return false;
        if (previous.startsWith("kernel:") && current.startsWith("kernel:")) {
            return previous.substring(7).matches(LifecycleSnapshot.UUID_PATTERN)
                    && current.substring(7).matches(LifecycleSnapshot.UUID_PATTERN);
        }
        if (previous.startsWith("count:") && current.startsWith("count:")) {
            try {
                long oldCount = Long.parseLong(previous.substring(6));
                long newCount = Long.parseLong(current.substring(6));
                return oldCount >= 0 && newCount > oldCount;
            } catch (NumberFormatException ignored) { return false; }
        }
        return false;
    }

    static boolean definitePreAdmissionRejection(String action, String code) {
        if (!action.equals("prepare-stop") && !action.equals("prepare-start")) return false;
        if (Arrays.asList("SAVER_ACTIVE", "SAVER_STATE_UNAVAILABLE", "UID_MISMATCH",
                "PACKAGE_UID_UNAVAILABLE", "BUSY", "STALE_COMMAND", "BOOT_ID_UNAVAILABLE").contains(code)) return true;
        return action.equals("prepare-stop") && Arrays.asList("PIDFD_UNAVAILABLE", "PROC_UNREADABLE",
                "SERVICE_STOP_UNAVAILABLE").contains(code);
    }

    static long callbackTimeout(String action) {
        return action.equals("check-start") ? 16_000L : 12_000L;
    }
}
