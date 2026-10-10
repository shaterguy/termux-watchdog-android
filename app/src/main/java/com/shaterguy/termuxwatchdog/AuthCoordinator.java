package com.shaterguy.termuxwatchdog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Process-memory-only single flight; SDK cache owns freshness, not an invented TTL. */
final class AuthCoordinator {
    interface Provider {
        CompletableFuture<Result> authorize();
        CompletableFuture<Void> clear(String rejectedToken);
    }
    static final class Result {
        final String token;
        final String code;
        final Object resolution;
        private Result(String token, String code, Object resolution) { this.token = token; this.code = code; this.resolution = resolution; }
        static Result token(String token) { return AuthProtocol.validToken(token) ? new Result(token, "", null) : error("AUTH_UNAVAILABLE"); }
        static Result error(String code) { return new Result(null, AuthProtocol.safeError(code), null); }
        static Result resolution(Object pendingIntent) { return new Result(null, "AUTH_REAUTH_REQUIRED", pendingIntent); }
        boolean ok() { return token != null; }
    }
    private final Provider provider;
    private final ScheduledExecutorService timers;
    private final long timeoutMs;
    private CompletableFuture<Result> inFlight;
    private String inFlightRejection;
    private String lastIssuedToken;
    private Object resolution;
    private String state = "not_ready";
    private long generation;
    private String cancellationCode = "AUTH_REAUTH_REQUIRED";

    AuthCoordinator(Provider provider, ScheduledExecutorService timers, long timeoutMs) {
        this.provider = provider;
        this.timers = timers;
        this.timeoutMs = timeoutMs;
    }

    synchronized CompletableFuture<Result> request(String rejectedToken) { return request(rejectedToken, Long.MAX_VALUE); }
    synchronized CompletableFuture<Result> request(String rejectedToken, long latestStartNanos) {
        if (System.nanoTime() > latestStartNanos) return CompletableFuture.completedFuture(Result.error("AUTH_TIMEOUT"));
        if (rejectedToken != null && !AuthProtocol.validToken(rejectedToken)) return CompletableFuture.completedFuture(Result.error("BAD_REQUEST"));
        if (inFlight != null) {
            if (rejectedToken == null || Objects.equals(rejectedToken, inFlightRejection)) return inFlight;
            // A rejection arriving during access must not reuse that possibly rejected result.
            long acceptedGeneration = generation;
            return inFlight.thenCompose(ignored -> {
                synchronized (AuthCoordinator.this) {
                    return acceptedGeneration == generation ? request(rejectedToken, latestStartNanos)
                            : CompletableFuture.completedFuture(Result.error(cancellationCode));
                }
            });
        }
        CompletableFuture<Result> result = new CompletableFuture<>();
        inFlight = result;
        inFlightRejection = rejectedToken;
        resolution = null;
        ScheduledFuture<?> timeout = timers.schedule(() -> finish(result, Result.error("AUTH_TIMEOUT")), timeoutMs, TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, error) -> timeout.cancel(false));
        try {
            CompletableFuture<Void> cleared = rejectedToken != null && (lastIssuedToken == null || lastIssuedToken.equals(rejectedToken))
                    ? provider.clear(rejectedToken) : CompletableFuture.completedFuture(null);
            cleared.thenCompose(ignored -> {
                synchronized (AuthCoordinator.this) {
                    if (inFlight != result) return CompletableFuture.completedFuture(Result.error("AUTH_TIMEOUT"));
                    return provider.authorize();
                }
            }).whenComplete((value, error) -> finish(result, error != null || value == null ? Result.error("AUTH_UNAVAILABLE") : value));
        } catch (RuntimeException ignored) { finish(result, Result.error("AUTH_UNAVAILABLE")); }
        return result;
    }

    private void finish(CompletableFuture<Result> request, Result value) {
        synchronized (this) {
        if (inFlight != request) return; // Cancellation/timeout owns the terminal state.
        inFlight = null;
        inFlightRejection = null;
        if (value.ok()) {
            lastIssuedToken = value.token;
            state = "ready";
        } else {
            state = value.code.equals("AUTH_REAUTH_REQUIRED") ? "reauth_required"
                    : value.code.equals("AUTH_NOT_CONFIGURED") ? "not_configured" : "unavailable";
        }
        resolution = value.resolution;
        }
        request.complete(value);
    }
    synchronized String state() { return state; }
    synchronized Object resolution() { return resolution; }
    void invalidate() { invalidate("AUTH_REAUTH_REQUIRED"); }
    void invalidate(String terminalCode) {
        CompletableFuture<Result> old;
        String code = AuthProtocol.safeError(terminalCode);
        synchronized (this) {
            cancellationCode = code;
            generation++;
            old = inFlight;
            inFlight = null;
            inFlightRejection = null;
            lastIssuedToken = null;
            resolution = null;
            state = "not_ready";
        }
        if (old != null) old.complete(Result.error(code));
    }
}
