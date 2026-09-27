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

    public boolean monitoringEnabled() {
        return prefs.getBoolean("monitoring_enabled", true);
    }

    public void setMonitoringEnabled(boolean enabled) {
        prefs.edit().putBoolean("monitoring_enabled", enabled).commit();
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

    public String lastFailureReason() {
        return prefs.getString("last_failure_reason", "");
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

    public long lastIncidentAt() {
        return prefs.getLong("last_incident_at", 0L);
    }

    public void startIncident(String incidentId, long detectedAtMs) {
        prefs.edit()
                .putString("incident_id", incidentId)
                .putLong("incident_detected_at", detectedAtMs)
                .putLong("last_incident_at", detectedAtMs)
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

    public void recordRecoverySuccess(long atMs) {
        prefs.edit()
                .putString("last_recovery_status", "SUCCESS")
                .putLong("last_recovery_at", atMs)
                .putString("last_recovery_detail", "post_recovery_health_ok")
                .apply();
    }

    public void recordRecoveryFailure(long atMs, String detail) {
        prefs.edit()
                .putString("last_recovery_status", "FAILED")
                .putLong("last_recovery_at", atMs)
                .putString("last_recovery_detail", detail == null ? "unknown" : detail)
                .apply();
    }

    public String lastRecoveryStatus() {
        return prefs.getString("last_recovery_status", "");
    }

    public long lastRecoveryAt() {
        return prefs.getLong("last_recovery_at", 0L);
    }

    public String lastRecoveryDetail() {
        return prefs.getString("last_recovery_detail", "");
    }
}
