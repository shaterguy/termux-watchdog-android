package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LifecycleRulesTest {
    private static final String OLD = "kernel:00000000-0000-4000-8000-000000000001";
    private static final String NEW = "kernel:00000000-0000-4000-8000-000000000002";
    @Test public void onlyPositiveComparableBootEvidenceReleasesGate() {
        assertTrue(LifecycleRules.verifiedBootChanged(OLD, NEW));
        assertTrue(LifecycleRules.verifiedBootChanged("count:1", "count:2"));
        assertFalse(LifecycleRules.verifiedBootChanged(OLD, OLD));
        assertFalse(LifecycleRules.verifiedBootChanged(OLD, ""));
        assertFalse(LifecycleRules.verifiedBootChanged("", NEW));
        assertFalse(LifecycleRules.verifiedBootChanged(OLD, "count:2"));
        assertFalse(LifecycleRules.verifiedBootChanged("count:2", "count:1"));
        assertFalse(LifecycleRules.verifiedBootChanged("count:-1", "count:2"));
        assertFalse(LifecycleRules.verifiedBootChanged("kernel:bad", NEW));
    }
    @Test public void uncertainWriteAndCommitFailuresNeverReleasePriorGate() {
        assertTrue(LifecycleRules.definitePreAdmissionRejection("prepare-stop", "SAVER_ACTIVE"));
        assertTrue(LifecycleRules.definitePreAdmissionRejection("prepare-stop", "PIDFD_UNAVAILABLE"));
        assertFalse(LifecycleRules.definitePreAdmissionRejection("prepare-stop", "STATE_IO"));
        assertFalse(LifecycleRules.definitePreAdmissionRejection("prepare-stop", "INTERNAL"));
        assertFalse(LifecycleRules.definitePreAdmissionRejection("commit-stop", "SAVER_ACTIVE"));
        assertTrue(LifecycleRules.definitePreAdmissionRejection("prepare-start", "SAVER_ACTIVE"));
        assertTrue(LifecycleRules.definitePreAdmissionRejection("prepare-start", "BOOT_ID_UNAVAILABLE"));
        assertFalse(LifecycleRules.definitePreAdmissionRejection("prepare-start", "RESUME_FENCE_FAILED"));
    }
    @Test public void waitsAreBounded() {
        assertEquals(12_000L, LifecycleRules.callbackTimeout("prepare-stop"));
        assertEquals(12_000L, LifecycleRules.callbackTimeout("commit-stop"));
        assertEquals(16_000L, LifecycleRules.callbackTimeout("check-start"));
    }
}
