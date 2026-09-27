package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryBackoffTest {
    @Test
    public void policyMatchesRequiredSequenceAndCap() {
        assertEquals(0L, RecoveryBackoff.delayBeforeAttemptMs(1));
        assertEquals(10_000L, RecoveryBackoff.delayBeforeAttemptMs(2));
        assertEquals(30_000L, RecoveryBackoff.delayBeforeAttemptMs(3));
        assertEquals(300_000L, RecoveryBackoff.delayBeforeAttemptMs(4));
        assertEquals(900_000L, RecoveryBackoff.delayBeforeAttemptMs(5));
        assertEquals(1_800_000L, RecoveryBackoff.delayBeforeAttemptMs(6));
        assertEquals(1_800_000L, RecoveryBackoff.delayBeforeAttemptMs(99));
    }

    @Test
    public void controllerUsesElapsedTimeAndResets() {
        RecoveryBackoff backoff = new RecoveryBackoff();
        long now = 100_000L;

        assertTrue(backoff.shouldAttempt(now));
        assertEquals(1, backoff.nextAttemptNumber());
        backoff.markAttempt(now);

        assertFalse(backoff.shouldAttempt(now + 9_999L));
        assertTrue(backoff.shouldAttempt(now + 10_000L));
        assertEquals(2, backoff.nextAttemptNumber());

        backoff.reset();
        assertEquals(0, backoff.completedAttempts());
        assertTrue(backoff.shouldAttempt(now));
        assertEquals(1, backoff.nextAttemptNumber());
    }
}
