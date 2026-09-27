package com.shaterguy.termuxwatchdog;

public final class RecoveryContract {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String RECOVERY_SCRIPT = "/data/data/com.termux/files/home/bin/termux-recover-all";
    public static final String DRIVE_WORKER_HEALTH = "http://127.0.0.1:17841/health";

    private RecoveryContract() {}
}
