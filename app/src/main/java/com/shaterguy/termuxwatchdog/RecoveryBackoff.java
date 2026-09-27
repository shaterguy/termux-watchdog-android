package com.shaterguy.termuxwatchdog;

public final class RecoveryBackoff {
    private static final long TEN_SECONDS_MS = 10_000L;
    private static final long THIRTY_SECONDS_MS = 30_000L;
    private static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;
    private static final long FIFTEEN_MINUTES_MS = 15L * 60L * 1000L;
    private static final long THIRTY_MINUTES_MS = 30L * 60L * 1000L;

    private int completedAttempts;
    private long nextAttemptElapsedMs;

    public static long delayBeforeAttemptMs(int attemptNumber) {
        if (attemptNumber <= 1) {
            return 0L;
        }
        switch (attemptNumber) {
            case 2:
                return TEN_SECONDS_MS;
            case 3:
                return THIRTY_SECONDS_MS;
            case 4:
                return FIVE_MINUTES_MS;
            case 5:
                return FIFTEEN_MINUTES_MS;
            default:
                return THIRTY_MINUTES_MS;
        }
    }

    public boolean shouldAttempt(long nowElapsedMs) {
        return completedAttempts == 0 || nowElapsedMs >= nextAttemptElapsedMs;
    }

    public int nextAttemptNumber() {
        return completedAttempts + 1;
    }

    public void markAttempt(long nowElapsedMs) {
        completedAttempts++;
        nextAttemptElapsedMs =
                nowElapsedMs + delayBeforeAttemptMs(completedAttempts + 1);
    }

    public void reset() {
        completedAttempts = 0;
        nextAttemptElapsedMs = 0L;
    }

    public int completedAttempts() {
        return completedAttempts;
    }

    public long nextAttemptElapsedMs() {
        return nextAttemptElapsedMs;
    }
}
