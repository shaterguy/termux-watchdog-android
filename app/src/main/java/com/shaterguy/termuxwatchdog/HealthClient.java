package com.shaterguy.termuxwatchdog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HealthClient {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    public HealthResult check() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    RecoveryContract.DRIVE_WORKER_HEALTH).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return HealthResult.unhealthy("http_" + status);
            }

            String body = readBounded(connection.getInputStream());
            JSONObject json = new JSONObject(body);
            Object ok = json.opt("ok");
            if (!Boolean.TRUE.equals(ok)) {
                return HealthResult.unhealthy("json_ok_not_true");
            }
            return HealthResult.healthy();
        } catch (Exception e) {
            return HealthResult.unhealthy(e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBounded(InputStream input) throws Exception {
        try (InputStream in = input;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("health_response_too_large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static final class HealthResult {
        private final boolean healthy;
        private final String failureReason;

        private HealthResult(boolean healthy, String failureReason) {
            this.healthy = healthy;
            this.failureReason = failureReason;
        }

        public static HealthResult healthy() {
            return new HealthResult(true, "");
        }

        public static HealthResult unhealthy(String failureReason) {
            return new HealthResult(false, failureReason == null ? "unknown" : failureReason);
        }

        public boolean isHealthy() {
            return healthy;
        }

        public String failureReason() {
            return failureReason;
        }
    }
}
