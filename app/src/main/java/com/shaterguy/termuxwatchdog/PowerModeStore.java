package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.UUID;

/** Intent, callback identities and the conservative recovery gate survive process death. */
final class PowerModeStore {
    private static final Object LOCK = new Object();
    private static final PowerModeReadiness READINESS = new PowerModeReadiness();
    private static final long CALLBACK_TIMEOUT_MS = 20_000L;
    private final SharedPreferences prefs;

    PowerModeStore(Context context) {
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("watchdog_state", Context.MODE_PRIVATE);
    }

    PowerModePolicy policy() {
        synchronized (LOCK) {
            return new PowerModePolicy(prefs.getLong("power_generation", 0),
                    prefs.getBoolean("power_mode_blocked", false),
                    prefs.getString("power_desired", "normal"),
                    prefs.getBoolean("power_pending", false),
                    prefs.getLong("power_revision", -1),
                    prefs.getString("power_command_id", ""));
        }
    }

    Request register(String command) {
        synchronized (LOCK) {
            // Recheck inside the registration lock: UI/bridge checks may already be stale.
            if (command.equals("enable") && !canEnable()) return null;
            long now = SystemClock.elapsedRealtime();
            long age = now - prefs.getLong("power_request_time_status", -CALLBACK_TIMEOUT_MS);
            if (command.equals("status") && age >= 0 && age < CALLBACK_TIMEOUT_MS
                    && !prefs.getString("power_nonce_status", "").isEmpty()) return null;
            PowerModePolicy policy = policy();
            String nonce = UUID.randomUUID().toString();
            SharedPreferences.Editor edit = prefs.edit();
            if (command.equals("status") && !prefs.getString("power_nonce_status", "").isEmpty()) {
                edit.putString("power_error", "MODE_RESULT_TIMEOUT");
            }
            if (!command.equals("status")) {
                policy.begin(command, nonce);
                edit.remove("power_nonce_status").remove("power_nonce_enable").remove("power_nonce_disable");
                edit.putString("power_error", "");
            }
            save(edit, policy);
            edit.putString("power_nonce_" + command, nonce)
                    .putLong("power_generation_" + command, policy.generation())
                    .putLong("power_request_time_" + command, now);
            if (!edit.commit()) throw new IllegalStateException("MODE_STATE_SAVE_FAILED");
            return new Request(command, nonce, policy.generation());
        }
    }

    Request consume(String nonce) {
        synchronized (LOCK) {
            for (String command : new String[]{"status", "enable", "disable"}) {
                if (!nonce.isEmpty() && nonce.equals(prefs.getString("power_nonce_" + command, ""))) {
                    long generation = prefs.getLong("power_generation_" + command, -1);
                    if (!prefs.edit().remove("power_nonce_" + command).commit()) return null;
                    return new Request(command, nonce, generation);
                }
            }
            return null;
        }
    }

    boolean observe(Request request, String raw) {
        PowerModeSnapshot snapshot = PowerModeSnapshot.parse(raw);
        synchronized (LOCK) {
            PowerModePolicy policy = policy();
            if (!policy.observe(request.generation, request.command, snapshot)) {
                // The correlated command has ended, even if its observation is stale.
                // Release only its in-flight latch, never the conservative recovery gate.
                if (!request.command.equals("status")) terminalFailure(request,
                        snapshot.errorCode.isEmpty() ? "MODE_RESPONSE_STALE" : snapshot.errorCode);
                return false;
            }
            SharedPreferences.Editor edit = prefs.edit();
            if (!policy.awaitingAcknowledgement()) edit.remove("power_nonce_enable").remove("power_nonce_disable");
            save(edit, policy);
            edit.putString("power_snapshot", raw).putString("power_error", "")
                    .putLong("power_observed_at", System.currentTimeMillis());
            if (!edit.commit()) {
                // Never start recovery when durable release of the gate is uncertain.
                prefs.edit().putBoolean("power_mode_blocked", true)
                        .putString("power_error", "MODE_STATE_SAVE_FAILED").commit();
                return false;
            }
            if (request.command.equals("status")) {
                // Start age at dispatch, so a late callback cannot rejuvenate old evidence.
                READINESS.observed(prefs.getLong("power_request_time_status", -1));
            }
            return true;
        }
    }

    void transportFailure(Request request, String code) {
        synchronized (LOCK) {
            if (request.generation != policy().generation()) return;
            READINESS.invalidate();
            prefs.edit().putString("power_error", code).commit();
        }
    }

    void terminalFailure(Request request, String code) {
        synchronized (LOCK) {
            PowerModePolicy policy = policy();
            if (request.generation != policy.generation()) return;
            READINESS.invalidate();
            if (!request.command.equals("status")) policy.commandFinishedWithoutObservation(request.generation);
            SharedPreferences.Editor edit = prefs.edit();
            save(edit, policy);
            edit.putString("power_error", code).commit();
        }
    }

    /** Only called by a protected system boot broadcast, not process/activity recreation. */
    void onDeviceBoot(int bootCount) {
        if (bootCount < 0) return;
        synchronized (LOCK) {
            // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED can arrive in the same boot.
            if (prefs.getLong("power_boot_seen", -1) == bootCount) return;
            READINESS.invalidate();
            PowerModePolicy policy = policy();
            boolean wasPending = policy.awaitingAcknowledgement();
            policy.commandFinishedWithoutObservation(policy.generation());
            SharedPreferences.Editor edit = prefs.edit();
            save(edit, policy);
            edit.putLong("power_boot_seen", bootCount)
                    .remove("power_nonce_status").remove("power_nonce_enable").remove("power_nonce_disable");
            if (wasPending) edit.putString("power_error", "MODE_REBOOT_INTERRUPTED");
            edit.commit();
        }
    }

    void unavailable(String code) {
        synchronized (LOCK) {
            READINESS.invalidate();
            prefs.edit().putString("power_error", code).commit();
        }
    }

    boolean canEnable() {
        synchronized (LOCK) {
            return READINESS.canEnable(SystemClock.elapsedRealtime(), !error().isEmpty());
        }
    }

    PowerModeSnapshot snapshot() {
        try { return PowerModeSnapshot.parse(prefs.getString("power_snapshot", "")); }
        catch (IllegalArgumentException error) { return null; }
    }

    String error() {
        long now = SystemClock.elapsedRealtime();
        for (String command : new String[]{"enable", "disable", "status"}) {
            long age = now - prefs.getLong("power_request_time_" + command, now);
            if (!prefs.getString("power_nonce_" + command, "").isEmpty()
                    && (age < 0 || age >= CALLBACK_TIMEOUT_MS)) return "MODE_RESULT_TIMEOUT";
        }
        return prefs.getString("power_error", "");
    }
    long observedAt() { return prefs.getLong("power_observed_at", 0); }

    private static void save(SharedPreferences.Editor edit, PowerModePolicy policy) {
        edit.putString("power_command_id", policy.commandId())
                .putLong("power_generation", policy.generation())
                .putBoolean("power_mode_blocked", policy.blocksRecovery())
                .putString("power_desired", policy.desiredMode())
                .putBoolean("power_pending", policy.awaitingAcknowledgement())
                .putLong("power_revision", policy.revision());
    }

    static final class Request {
        final String command;
        final String nonce;
        final long generation;
        Request(String command, String nonce, long generation) {
            this.command = command;
            this.nonce = nonce;
            this.generation = generation;
        }
    }
}
