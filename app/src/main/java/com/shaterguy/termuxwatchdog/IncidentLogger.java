package com.shaterguy.termuxwatchdog;

import android.app.ActivityManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class IncidentLogger {
    private static final long MAX_BYTES = 256L * 1024L;
    private static final int KEEP_BYTES = 192 * 1024;

    private final Context context;
    private final File logFile;

    public IncidentLogger(Context context) {
        this.context = context.createDeviceProtectedStorageContext();
        this.logFile = new File(this.context.getFilesDir(), "incidents.jsonl");
    }

    public synchronized void append(
            String event,
            String incidentId,
            long detectedAtMs,
            long lastHealthSuccessAtMs,
            int consecutiveFailures,
            int recoveryAttempt,
            String runCommandResult,
            long termuxRecoveryAtMs,
            long healthRecoveryAtMs,
            long watchdogUptimeMs,
            String lastFailureReason) {
        try {
            JSONObject json = new JSONObject();
            json.put("event", event);
            json.put("incident_id", nullable(incidentId));
            json.put("detected_at", instantOrNull(detectedAtMs));
            json.put("last_health_success_at", instantOrNull(lastHealthSuccessAtMs));
            json.put("consecutive_failures", consecutiveFailures);
            json.put("recovery_attempt", recoveryAttempt);
            json.put("run_command_result", nullable(runCommandResult));
            json.put("termux_recovery_at", instantOrNull(termuxRecoveryAtMs));
            json.put("health_recovery_at", instantOrNull(healthRecoveryAtMs));
            json.put("watchdog_uptime_ms", Math.max(0L, watchdogUptimeMs));
            json.put("screen_state", screenState());
            json.put("battery_state", batteryState());
            json.put("memory_available_bytes", availableMemoryBytes());
            json.put("last_failure_reason", nullable(lastFailureReason));
            json.put("device_manufacturer", Build.MANUFACTURER);
            json.put("device_model", Build.MODEL);
            json.put("android_version", Build.VERSION.RELEASE);
            json.put("api_level", Build.VERSION.SDK_INT);

            byte[] line = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(line);
                out.flush();
            }
            trimIfNeeded();
        } catch (Exception ignored) {
            // The watchdog loop must survive diagnostic logging failures.
        }
    }

    public synchronized List<String> readRecentLines(int limit) {
        int boundedLimit = Math.max(0, Math.min(limit, 20));
        ArrayDeque<String> recent = new ArrayDeque<>(Math.max(1, boundedLimit));
        if (boundedLimit == 0 || !logFile.isFile()) {
            return new ArrayList<>();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (recent.size() == boundedLimit) {
                    recent.removeFirst();
                }
                recent.addLast(line);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }

        ArrayList<String> result = new ArrayList<>(recent.size());
        while (!recent.isEmpty()) {
            result.add(recent.removeLast());
        }
        return result;
    }

    private Object instantOrNull(long epochMs) {
        return epochMs > 0L ? Instant.ofEpochMilli(epochMs).toString() : JSONObject.NULL;
    }

    private Object nullable(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    private String screenState() {
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) {
            return "unknown";
        }
        return pm.isInteractive() ? "interactive" : "not_interactive";
    }

    private String batteryState() {
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery == null) {
            return "unknown";
        }
        int percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return percent == Integer.MIN_VALUE ? "unknown" : "percent=" + percent;
    }

    private long availableMemoryBytes() {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) {
            return -1L;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem;
    }

    private void trimIfNeeded() throws Exception {
        if (logFile.length() <= MAX_BYTES) {
            return;
        }

        byte[] buffer;
        long start;
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            start = Math.max(0L, raf.length() - KEEP_BYTES);
            raf.seek(start);
            buffer = new byte[(int) (raf.length() - start)];
            raf.readFully(buffer);
        }

        int offset = 0;
        if (start > 0L) {
            while (offset < buffer.length && buffer[offset] != '\n') {
                offset++;
            }
            if (offset < buffer.length) {
                offset++;
            }
        }

        try (FileOutputStream out = new FileOutputStream(logFile, false)) {
            out.write(buffer, offset, buffer.length - offset);
        }
    }
}
