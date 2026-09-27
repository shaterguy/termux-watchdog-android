package com.shaterguy.termuxwatchdog;

public final class RecoveryCommandSpec {
    private RecoveryCommandSpec() {}

    public static String path() {
        return RecoveryContract.RECOVERY_SCRIPT;
    }

    public static String workdir() {
        return RecoveryContract.TERMUX_HOME;
    }

    public static String[] arguments() {
        return new String[0];
    }

    public static boolean background() {
        return true;
    }
}
