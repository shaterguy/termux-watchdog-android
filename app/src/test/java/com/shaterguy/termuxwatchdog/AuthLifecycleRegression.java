package com.shaterguy.termuxwatchdog;
public final class AuthLifecycleRegression {
    private static int checks;
    private static void check(boolean yes, String what) { checks++; if (!yes) throw new AssertionError(what); }
    public static void main(String[] args) {
        check(AuthLifecyclePolicy.canStart(true, true, "visible"), "visible user path may prepare local bridge");
        check(!AuthLifecyclePolicy.canStart(true, true, "android.intent.action.BOOT_COMPLETED"), "boot must not initialize native auth");
        check(!AuthLifecyclePolicy.canStart(true, true, "android.intent.action.MY_PACKAGE_REPLACED"), "package update must not initialize native auth");
        check(!AuthLifecyclePolicy.canStart(true, false, "android.intent.action.BOOT_COMPLETED"), "credential locked fails closed");
        check(!AuthLifecyclePolicy.canStart(true, true, "android.intent.action.LOCKED_BOOT_COMPLETED"), "no direct boot authorization");
        check(!AuthLifecyclePolicy.canStart(false, true, "visible"), "no opt-in no credential use");
        check(!AuthLifecyclePolicy.canStart(true, true, "android.intent.action.TIME_TICK"), "arbitrary background triggers denied");
        check(!AuthLifecyclePolicy.canStart(true, true, null), "null trigger denied");
        check(AuthLifecyclePolicy.canStart(true, true, "visible_restore"), "visible restore prepares bridge before broker");
        check(AuthLifecyclePolicy.shouldPause(true, "power_saving", false), "settled saver stops native bridge after broker proof");
        check(!AuthLifecyclePolicy.shouldPause(false, "power_saving", false), "drain or unknown observation keeps bridge alive");
        check(!AuthLifecyclePolicy.shouldPause(true, "normal", true), "restore intent prevents stale saver stop");
        check(!AuthLifecyclePolicy.shouldPause(true, "power_saving", true), "pending mutation never stops bridge on old proof");
        System.out.println("AUTH_LIFECYCLE_REGRESSION_PASS checks=" + checks);
    }
}
