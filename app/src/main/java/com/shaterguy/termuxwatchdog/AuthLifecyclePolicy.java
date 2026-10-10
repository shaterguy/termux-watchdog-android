package com.shaterguy.termuxwatchdog;

/** Foreground preparation and verified saver policy; no native foreground service exists. */
final class AuthLifecyclePolicy {
    static boolean canStart(boolean enabled, boolean unlocked, String origin) {
        return enabled && unlocked && ("visible".equals(origin) || "visible_restore".equals(origin));
    }
    static boolean shouldPause(boolean verifiedSaver, String desired, boolean pending) {
        return verifiedSaver && "power_saving".equals(desired) && !pending;
    }
}
