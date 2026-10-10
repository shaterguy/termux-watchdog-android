package com.shaterguy.termuxwatchdog;

/** Process-local evidence only; callers serialize access with the mode-store lock. */
final class PowerModeReadiness {
    private static final long FRESH_MS = 30_000L;
    private long observedRequestAt = -1;

    void observed(long requestStartedAt) { observedRequestAt = requestStartedAt; }
    void invalidate() { observedRequestAt = -1; }

    boolean canEnable(long now, boolean hasError) {
        return !hasError && observedRequestAt >= 0 && now >= observedRequestAt
                && now - observedRequestAt < FRESH_MS;
    }
}
