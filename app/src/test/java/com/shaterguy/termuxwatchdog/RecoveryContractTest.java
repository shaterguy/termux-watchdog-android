package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryContractTest {
    @Test
    public void recoveryCommandIsFixedAndAbsolute() {
        assertEquals("/data/data/com.termux/files/home/bin/termux-recover-all", RecoveryContract.RECOVERY_SCRIPT);
        assertTrue(RecoveryContract.RECOVERY_SCRIPT.startsWith("/data/data/com.termux/"));
    }

    @Test
    public void termuxAndHealthTargetsAreFixed() {
        assertEquals("com.termux", RecoveryContract.TERMUX_PACKAGE);
        assertEquals("com.termux.permission.RUN_COMMAND", RecoveryContract.RUN_COMMAND_PERMISSION);
        assertEquals("http://127.0.0.1:17841/health", RecoveryContract.DRIVE_WORKER_HEALTH);
    }
}
