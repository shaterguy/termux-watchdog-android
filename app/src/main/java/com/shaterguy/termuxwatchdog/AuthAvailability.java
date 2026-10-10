package com.shaterguy.termuxwatchdog;
final class AuthAvailability {
    static boolean mayUse(boolean verifiedBuild,boolean enabled,boolean unlocked,boolean saver) {
        return verifiedBuild && enabled && unlocked && !saver;
    }
    static String state(boolean verifiedBuild,boolean enabled,boolean unlocked,boolean saver,String sdkState) {
        if (!verifiedBuild) return "not_configured";
        if (!enabled || !unlocked || saver) return "not_ready";
        return sdkState;
    }
}
