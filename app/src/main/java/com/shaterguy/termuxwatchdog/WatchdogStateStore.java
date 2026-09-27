package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.SharedPreferences;

public final class WatchdogStateStore {
    private static final String PREFS = "watchdog_state";
    private final SharedPreferences prefs;

    public WatchdogStateStore(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public HealthStateMachine.State monitorState() {
        String raw = prefs.getString("monitor_state", HealthStateMachine.State.HEALTHY.name());
        try {
            return HealthStateMachine.State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return HealthStateMachine.State.HEALTHY;
        }
    }

    public int consecutiveFailures() {
        return Math.max(0, prefs.getInt("consecutive_failures", 0));
    }

    public void saveMonitorState(HealthStateMachine.State state, int failures, String reason) {
        prefs.edit()
                .putString("monitor_state", state.name())
                .putInt("consecutive_failures", Math.max(0, failures))
                .putString("last_failure_reason", reason == null ? "" : reason)
                .apply();
    }

    public long lastHealthSuccessAt() {
        return prefs.getLong("last_health_success_at", 0L);
    }

    public void recordHealthSuccess(long atMs) {
        prefs.edit()
                .putLong("last_health_success_at", atMs)
                .putString("last_failure_reason", "")
                .apply();
    }

    public String currentIncidentId() {
        return prefs.getString("incident_id", null);
    }

    public long incidentDetectedAt() {
        return prefs.getLong("incident_detected_at", 0L);
    }

    public void startIncident(String incidentId, long detectedAtMs) {
        prefs.edit()
                .putString("incident_id", incidentId)
                .putLong("incident_detected_at", detectedAtMs)
                .apply();
    }

    public void clearIncident() {
        prefs.edit()
                .remove("incident_id")
                .remove("incident_detected_at")
                .putInt("recovery_attempt", 0)
                .apply();
    }

    public void setRecoveryAttempt(int attempt) {
        prefs.edit().putInt("recovery_attempt", Math.max(0, attempt)).apply();
    }
}
