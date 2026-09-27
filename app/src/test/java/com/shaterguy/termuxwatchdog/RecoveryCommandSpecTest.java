package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryCommandSpecTest {
    @Test
    public void recoverySpecHasNoConfigurableCommandSurface() {
        assertEquals(
                "/data/data/com.termux/files/home/bin/termux-recover-all",
                RecoveryCommandSpec.path());
        assertEquals(
                "/data/data/com.termux/files/home",
                RecoveryCommandSpec.workdir());
        assertArrayEquals(new String[0], RecoveryCommandSpec.arguments());
        assertTrue(RecoveryCommandSpec.background());
    }

    @Test
    public void runCommandServiceContractIsFixed() {
        assertEquals("com.termux.RUN_COMMAND", RecoveryContract.RUN_COMMAND_ACTION);
        assertEquals(
                "com.termux.app.RunCommandService",
                RecoveryContract.RUN_COMMAND_SERVICE_CLASS);
        assertEquals(
                "com.termux.RUN_COMMAND_PATH",
                RecoveryContract.EXTRA_COMMAND_PATH);
        assertEquals(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                RecoveryContract.EXTRA_ARGUMENTS);
        assertEquals(
                "com.termux.RUN_COMMAND_BACKGROUND",
                RecoveryContract.EXTRA_BACKGROUND);
        assertEquals(
                "com.termux.RUN_COMMAND_WORKDIR",
                RecoveryContract.EXTRA_WORKDIR);
    }
}
