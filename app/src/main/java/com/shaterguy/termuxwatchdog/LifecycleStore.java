package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Durable, boot-scoped admission gate, shared with power-mode command admission. */
final class LifecycleStore {
    static final Object LOCK = new Object();
    private static volatile boolean storageUncertain;
    private static final long STOP_SETTLE_MS = 22_000L;
    private final Context context;
    private final SharedPreferences prefs;
    private final LongSupplier elapsed;
    private final Supplier<String> kernelIdentity;
    private final IntSupplier bootCount;
    private final BooleanSupplier saverBlocked;

    LifecycleStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("watchdog_state", Context.MODE_PRIVATE);
        elapsed = SystemClock::elapsedRealtime;
        kernelIdentity = LifecycleStore::readKernelBootIdentity;
        bootCount = () -> {
            try { return Settings.Global.getInt(this.context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
            catch (RuntimeException ignored) { return -1; }
        };
        saverBlocked = () -> {
            PowerModePolicy policy = new PowerModeStore(this.context).policy();
            return policy.blocksRecovery() || policy.awaitingAcknowledgement();
        };
    }

    /** In-memory seams exercise this same state code without invoking Android or real startup. */
    LifecycleStore(SharedPreferences prefs, LongSupplier elapsed, Supplier<String> kernelIdentity,
                   IntSupplier bootCount, BooleanSupplier saverBlocked) {
        context = null;
        this.prefs = prefs;
        this.elapsed = elapsed;
        this.kernelIdentity = kernelIdentity;
        this.bootCount = bootCount;
        this.saverBlocked = saverBlocked;
    }

    boolean blocksRecovery() {
        synchronized (LOCK) {
            reconcileBoot();
            return storageUncertain || prefs.getBoolean("lifecycle_blocked", false);
        }
    }

    String state() {
        synchronized (LOCK) {
            reconcileBoot();
            expire();
            return prefs.getString("lifecycle_state", "RUNNING");
        }
    }

    boolean busy() {
        synchronized (LOCK) {
            expire();
            return !prefs.getString("lifecycle_pending", "").isEmpty()
                    || state().equals("READY_TO_LAUNCH") || settling();
        }
    }

    String error() { synchronized (LOCK) { expire(); return prefs.getString("lifecycle_error", ""); } }
    String snapshot() { return prefs.getString("lifecycle_snapshot", ""); }

    Request begin(String action, int uid) {
        synchronized (LOCK) {
            reconcileBoot();
            expire();
            if (!action.equals("prepare-stop") && !action.equals("prepare-start")) throw new IllegalArgumentException();
            if (saverBlocked.getAsBoolean()) {
                noteError("SAVER_ACTIVE"); return null;
            }
            if (busy()) { noteError("BUSY"); return null; }
            String boot = bootIdentity();
            if (boot.isEmpty()) { noteError("BOOT_ID_UNAVAILABLE"); return null; }
            if (uid <= 0) { noteError("PACKAGE_UID_UNAVAILABLE"); return null; }
            long sequence = prefs.getLong("lifecycle_generation", 0) + 1;
            if (sequence <= 0 || sequence > LifecycleSnapshot.MAX_SEQUENCE) {
                noteError("STATE_INVALID"); return null;
            }
            String nonce = UUID.randomUUID().toString();
            boolean priorBlocked = prefs.getBoolean("lifecycle_blocked", false);
            Request request = new Request(action, nonce, sequence, uid);
            SharedPreferences.Editor edit = prefs.edit()
                    .putBoolean("lifecycle_prior_blocked", priorBlocked)
                    .putBoolean("lifecycle_blocked", true)
                    .putString("lifecycle_boot", boot)
                    .putString("lifecycle_remote_boot", "")
                    .putString("lifecycle_nonce", nonce)
                    .putLong("lifecycle_generation", sequence)
                    .putInt("lifecycle_uid", uid)
                    .putString("lifecycle_state", action.equals("prepare-stop") ? "STOPPING" : "STARTING")
                    .putString("lifecycle_error", "").remove("lifecycle_snapshot");
            pending(edit, request);
            if (!commit(edit)) return null;
            return request;
        }
    }

    Request pending(String action, String nonce) {
        synchronized (LOCK) {
            reconcileBoot();
            expire();
            if (!action.equals(prefs.getString("lifecycle_pending", ""))
                    || !nonce.equals(prefs.getString("lifecycle_nonce", ""))) return null;
            return new Request(action, nonce, prefs.getLong("lifecycle_generation", 0),
                    prefs.getInt("lifecycle_uid", 0));
        }
    }

    /** Record exactly one expected callback; a valid preparation admits the next fixed phase. */
    Request observe(Request request, LifecycleSnapshot receipt, int currentUid, String raw) {
        synchronized (LOCK) {
            if (pending(request.action, request.nonce) == null) return null;
            if (!request.matches(receipt) || currentUid != request.uid
                    || (receipt.termuxUid != request.uid && !receipt.state.equals("REJECTED"))) {
                fail(request, "LIFECYCLE_RESPONSE_INVALID"); return null;
            }
            String remoteBoot = prefs.getString("lifecycle_remote_boot", "");
            if (!remoteBoot.isEmpty() && !remoteBoot.equals(receipt.bootId)) {
                fail(request, "BOOT_ID_CHANGED"); return null;
            }
            SharedPreferences.Editor edit = prefs.edit().remove("lifecycle_pending")
                    .putString("lifecycle_error", receipt.errorCode).putString("lifecycle_snapshot", raw);
            if (!receipt.bootId.isEmpty()) edit.putString("lifecycle_remote_boot", receipt.bootId);
            if (receipt.state.equals("REJECTED")) {
                // Before either admission, a definite refusal leaves the previous lifecycle gate intact.
                boolean blocked = LifecycleRules.definitePreAdmissionRejection(request.action, receipt.errorCode)
                        ? prefs.getBoolean("lifecycle_prior_blocked", false) : true;
                edit.putBoolean("lifecycle_blocked", blocked)
                        .putString("lifecycle_state", blocked ? "UNKNOWN" : "RUNNING");
                commit(edit); return null;
            }
            switch (receipt.state) {
                case "STOP_PREPARED":
                    Request next = new Request("commit-stop", request.nonce, request.sequence, request.uid);
                    pending(edit, next);
                    edit.putString("lifecycle_state", "STOPPING");
                    return commit(edit) ? next : null;
                case "STOP_COMMITTED":
                    edit.putString("lifecycle_state", "OFF_REQUESTED")
                            .putLong("lifecycle_stop_requested_at", elapsed.getAsLong());
                    break;
                case "READY_TO_LAUNCH":
                    edit.putString("lifecycle_state", "READY_TO_LAUNCH")
                            .putLong("lifecycle_ready_at", elapsed.getAsLong());
                    break;
                case "START_OBSERVED":
                    edit.putString("lifecycle_state", "RUNNING").putBoolean("lifecycle_blocked", false);
                    break;
                case "START_PARTIAL":
                    edit.putString("lifecycle_state", "START_PARTIAL");
                    break;
                default: fail(request, "LIFECYCLE_RESPONSE_INVALID"); return null;
            }
            if (commit(edit) && receipt.state.equals("START_OBSERVED")) storageUncertain = false;
            return null;
        }
    }

    void saveSnapshot(Request request, String raw) {
        synchronized (LOCK) {
            if (request.nonce.equals(prefs.getString("lifecycle_nonce", ""))
                    && request.sequence == prefs.getLong("lifecycle_generation", 0)) {
                commit(prefs.edit().putString("lifecycle_snapshot", raw));
            }
        }
    }

    /** Only the visible Activity may consume launch readiness and start the exported Activity. */
    Request takeLaunch() {
        synchronized (LOCK) {
            reconcileBoot();
            expire();
            if (!state().equals("READY_TO_LAUNCH")) return null;
            Request request = new Request("check-start", prefs.getString("lifecycle_nonce", ""),
                    prefs.getLong("lifecycle_generation", 0), prefs.getInt("lifecycle_uid", 0));
            SharedPreferences.Editor edit = prefs.edit().putString("lifecycle_state", "STARTING");
            pending(edit, request);
            return commit(edit) ? request : null;
        }
    }

    void fail(Request request, String code) {
        synchronized (LOCK) {
            if (!request.nonce.equals(prefs.getString("lifecycle_nonce", ""))
                    || request.sequence != prefs.getLong("lifecycle_generation", 0)
                    || !request.action.equals(prefs.getString("lifecycle_pending", ""))) return;
            commit(prefs.edit().remove("lifecycle_pending").putBoolean("lifecycle_blocked", true)
                    .putString("lifecycle_state", "UNKNOWN").putString("lifecycle_error", code));
        }
    }

    void noteError(String code) { synchronized (LOCK) { commit(prefs.edit().putString("lifecycle_error", code)); } }

    private void expire() {
        long now = elapsed.getAsLong();
        String pending = prefs.getString("lifecycle_pending", "");
        long age = now - prefs.getLong("lifecycle_dispatched_at", now);
        long timeout = LifecycleRules.callbackTimeout(pending);
        boolean ready = prefs.getString("lifecycle_state", "").equals("READY_TO_LAUNCH");
        long readyAge = now - prefs.getLong("lifecycle_ready_at", now);
        if ((!pending.isEmpty() && (age < 0 || age >= timeout))
                || (ready && (readyAge < 0 || readyAge >= 20_000L))) {
            commit(prefs.edit().remove("lifecycle_pending").putBoolean("lifecycle_blocked", true)
                    .putString("lifecycle_state", "UNKNOWN").putString("lifecycle_error", "LIFECYCLE_RESULT_TIMEOUT"));
        }
    }

    private boolean settling() {
        if (!prefs.getString("lifecycle_state", "").equals("OFF_REQUESTED")) return false;
        long age = elapsed.getAsLong() - prefs.getLong("lifecycle_stop_requested_at", 0);
        return age >= 0 && age < STOP_SETTLE_MS;
    }

    private void pending(SharedPreferences.Editor edit, Request request) {
        edit.putString("lifecycle_pending", request.action)
                .putLong("lifecycle_dispatched_at", elapsed.getAsLong());
    }

    private boolean commit(SharedPreferences.Editor edit) {
        if (edit.commit()) return true;
        storageUncertain = true;
        prefs.edit().putBoolean("lifecycle_blocked", true)
                .putString("lifecycle_state", "UNKNOWN").putString("lifecycle_error", "STATE_IO").commit();
        return false;
    }

    private void reconcileBoot() {
        String previous = prefs.getString("lifecycle_boot", "");
        if (previous.isEmpty()) return;
        String current = previous.startsWith("kernel:") ? kernelBootIdentity()
                : previous.startsWith("count:") ? countBootIdentity() : "";
        // Unknown identity, Activity recreation or duplicate boot broadcasts never release a gate.
        if (!LifecycleRules.verifiedBootChanged(previous, current)) return;
        if (commit(prefs.edit().putString("lifecycle_boot", current).putBoolean("lifecycle_blocked", false)
                .remove("lifecycle_pending").remove("lifecycle_nonce").remove("lifecycle_remote_boot")
                .remove("lifecycle_snapshot").putString("lifecycle_state", "RUNNING")
                .putString("lifecycle_error", ""))) storageUncertain = false;
    }

    private String bootIdentity() {
        String kernel = kernelBootIdentity();
        return kernel.isEmpty() ? countBootIdentity() : kernel;
    }

    private String kernelBootIdentity() {
        String identity = kernelIdentity.get();
        return identity != null && identity.matches("kernel:" + LifecycleSnapshot.UUID_PATTERN) ? identity : "";
    }

    private static String readKernelBootIdentity() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value != null && value.matches(LifecycleSnapshot.UUID_PATTERN)) return "kernel:" + value;
        } catch (IOException | SecurityException ignored) { }
        return "";
    }

    private String countBootIdentity() {
        int count = bootCount.getAsInt();
        return count >= 0 ? "count:" + count : "";
    }

    static final class Request {
        final String action, nonce;
        final long sequence;
        final int uid;
        Request(String action, String nonce, long sequence, int uid) {
            this.action = action; this.nonce = nonce; this.sequence = sequence; this.uid = uid;
        }
        boolean matches(LifecycleSnapshot receipt) {
            return action.equals(receipt.action) && nonce.equals(receipt.commandId)
                    && sequence == receipt.commandSequence;
        }
    }
}
