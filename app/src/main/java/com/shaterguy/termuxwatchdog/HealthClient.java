package com.shaterguy.termuxwatchdog;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class HealthClient {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final long CHECK_DEADLINE_NANOS = 4_000_000_000L;

    public HealthResult check() {
        HttpURLConnection connection = null;
        long deadline = System.nanoTime() + CHECK_DEADLINE_NANOS;
        try {
            connection = (HttpURLConnection) new URL(
                    RecoveryContract.DRIVE_WORKER_HEALTH).openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return HealthResult.unknown("http_" + status);
            try (InputStream input = connection.getInputStream()) {
                return readResponse(status, input, deadline);
            }
        } catch (Exception e) {
            return failure(e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static HealthResult readResponse(int status, InputStream input, long deadline) {
        if (status != HttpURLConnection.HTTP_OK) return HealthResult.unknown("http_" + status);
        try {
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
            return HealthJson.read(reader, deadline)
                    ? HealthResult.healthy() : HealthResult.unknown("json_ok_not_true");
        } catch (Exception e) {
            // An incomplete/unreadable response is not proof that the endpoint is down.
            HealthResult result = failure(e);
            return result.isUnavailable() ? HealthResult.unknown("health_read_error") : result;
        }
    }

    static HealthResult failure(Exception error) {
        if (error instanceof ConnectException) {
            String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("econnrefused") || message.contains("connection refused")) {
                return HealthResult.unavailable("connection_refused");
            }
        }
        if (error instanceof SecurityException) return HealthResult.unknown("permission_denied");
        if (error instanceof SocketTimeoutException) return HealthResult.unknown("health_timeout");
        if (error instanceof HealthJson.InvalidJsonException) return HealthResult.unknown(error.getMessage());
        return HealthResult.unknown("health_read_error");
    }

    public static final class HealthResult {
        private enum Outcome { HEALTHY, UNAVAILABLE, UNKNOWN }
        private final Outcome outcome;
        private final String failureReason;
        private HealthResult(Outcome outcome, String failureReason) {
            this.outcome = outcome;
            this.failureReason = failureReason;
        }
        public static HealthResult healthy() { return new HealthResult(Outcome.HEALTHY, ""); }
        public static HealthResult unavailable(String reason) { return new HealthResult(Outcome.UNAVAILABLE, reason); }
        public static HealthResult unknown(String reason) { return new HealthResult(Outcome.UNKNOWN, reason); }
        public boolean isHealthy() { return outcome == Outcome.HEALTHY; }
        public boolean isUnavailable() { return outcome == Outcome.UNAVAILABLE; }
        public boolean isUnknown() { return outcome == Outcome.UNKNOWN; }
        public String failureReason() { return failureReason; }
    }
}
