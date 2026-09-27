package com.shaterguy.termuxwatchdog;

public final class RecoveryContract {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    public static final String RUN_COMMAND_SERVICE_CLASS = "com.termux.app.RunCommandService";
    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String RECOVERY_SCRIPT =
            "/data/data/com.termux/files/home/bin/termux-recover-all";
    public static final String DRIVE_WORKER_HEALTH = "http://127.0.0.1:17841/health";

    private RecoveryContract() {}
}
