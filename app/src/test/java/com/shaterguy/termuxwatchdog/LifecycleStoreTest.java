package com.shaterguy.termuxwatchdog;

import android.content.SharedPreferences;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Executes the production store with in-memory prefs/clock/boot/saver seams only. */
public final class LifecycleStoreTest {
    private static final int UID = 10031;
    private static final String BOOT_A = "kernel:00000000-0000-4000-8000-000000000001";
    private static final String BOOT_B = "kernel:00000000-0000-4000-8000-000000000002";
    private MemoryPrefs prefs;
    private LifecycleStore store;
    private long now;
    private String boot;
    private boolean saver;
    private LifecycleStore newStore() {
        return new LifecycleStore(prefs, () -> now, () -> boot, () -> -1, () -> saver);
    }
    @Before public void setup() {
        prefs = new MemoryPrefs(); now = 100; boot = BOOT_B; saver = false;
        // A verified boot transition resets process-local storage uncertainty from prior tests.
        prefs.edit().putString("lifecycle_boot", BOOT_A).putBoolean("lifecycle_blocked", true).commit();
        store = newStore();
        assertFalse(store.blocksRecovery());
    }
    private LifecycleStore.Request observe(LifecycleStore.Request request, String state, String error) {
        String raw = LifecycleSnapshotTest.receipt(request.action, state, error)
                .replace("00000000-0000-4000-8000-000000000001", request.nonce)
                .replace("\"command_sequence\":1", "\"command_sequence\":" + request.sequence);
        return store.observe(request, LifecycleSnapshot.parse(raw), UID, raw);
    }
    private LifecycleStore.Request prepareStart() {
        LifecycleStore.Request request = store.begin("prepare-start", UID);
        assertNotNull(request);
        observe(request, "READY_TO_LAUNCH", "");
        return store.takeLaunch();
    }
    @Test public void stopUsesTwoCorrelatedPhasesThenUnconfirmedLatch() {
        LifecycleStore.Request first = store.begin("prepare-stop", UID);
        assertTrue(store.blocksRecovery());
        assertNull(store.begin("prepare-start", UID));
        LifecycleStore.Request commit = observe(first, "STOP_PREPARED", "");
        assertNotNull(commit);
        assertEquals("commit-stop", commit.action);
        assertNull(store.pending(first.action, first.nonce));
        assertNull(observe(first, "STOP_PREPARED", ""));
        assertNotNull(store.pending(commit.action, commit.nonce));
        observe(commit, "STOP_COMMITTED", "");
        assertEquals("OFF_REQUESTED", store.state());
        assertTrue(store.blocksRecovery());
        assertTrue(store.busy());
        now += 22_001;
        assertFalse(store.busy());
        assertTrue(store.blocksRecovery());
    }
    @Test public void processRecreationKeepsGateAndPendingIdentity() {
        LifecycleStore.Request request = store.begin("prepare-stop", UID);
        store = newStore();
        assertTrue(store.blocksRecovery());
        assertNotNull(store.pending(request.action, request.nonce));
    }
    @Test public void timeoutKeepsGateAndLateResponseCannotReleaseNewGeneration() {
        LifecycleStore.Request old = store.begin("prepare-start", UID);
        now += 12_001;
        assertEquals("UNKNOWN", store.state());
        assertTrue(store.blocksRecovery());
        LifecycleStore.Request current = store.begin("prepare-stop", UID);
        assertTrue(current.sequence > old.sequence);
        observe(old, "READY_TO_LAUNCH", "");
        assertEquals("STOPPING", store.state());
        assertNotNull(store.pending(current.action, current.nonce));
    }
    @Test public void definiteSaverRefusalPreservesPriorGateForBothPreparations() {
        for (String action : new String[]{"prepare-stop", "prepare-start"}) {
            LifecycleStore.Request request = store.begin(action, UID);
            observe(request, "REJECTED", "SAVER_ACTIVE");
            assertFalse(store.blocksRecovery());
            assertEquals("RUNNING", store.state());
        }
    }
    @Test public void uncertainRefusalNeverReleasesPriorGate() {
        LifecycleStore.Request request = store.begin("prepare-start", UID);
        observe(request, "REJECTED", "RESUME_FENCE_FAILED");
        assertTrue(store.blocksRecovery());
        assertEquals("UNKNOWN", store.state());
    }
    @Test public void refusalWhileAlreadyBlockedDoesNotUnlock() {
        LifecycleStore.Request stop = store.begin("prepare-stop", UID);
        now += 12_001; store.state();
        LifecycleStore.Request start = store.begin("prepare-start", UID);
        observe(start, "REJECTED", "SAVER_ACTIVE");
        assertTrue(store.blocksRecovery());
    }
    @Test public void localSaverBlocksBothActionsWithoutNewGate() {
        saver = true;
        assertNull(store.begin("prepare-stop", UID));
        assertNull(store.begin("prepare-start", UID));
        assertFalse(store.blocksRecovery());
        assertEquals("SAVER_ACTIVE", store.error());
    }
    @Test public void onlyFreshObservedStartReleasesGate() {
        LifecycleStore.Request check = prepareStart();
        assertNotNull(check);
        assertTrue(store.blocksRecovery());
        assertNull(store.takeLaunch());
        observe(check, "START_OBSERVED", "");
        assertFalse(store.blocksRecovery());
        assertEquals("RUNNING", store.state());
    }
    @Test public void partialStartDoesNotClaimRecoveryReady() {
        LifecycleStore.Request check = prepareStart();
        observe(check, "START_PARTIAL", "REGISTRATION_ERRORS");
        assertTrue(store.blocksRecovery());
        assertEquals("START_PARTIAL", store.state());
    }
    @Test public void durableFailureRemainsBlockedUntilExplicitStartCommits() {
        LifecycleStore.Request check = prepareStart();
        prefs.failCommits = 1;
        observe(check, "START_OBSERVED", "");
        assertTrue(store.blocksRecovery());
        assertEquals("UNKNOWN", store.state());
        LifecycleStore.Request retry = prepareStart();
        observe(retry, "START_OBSERVED", "");
        assertFalse(store.blocksRecovery());
    }
    @Test public void sameBootAndUnknownIdentityDoNotUnlockButNewBootDoes() {
        store.begin("prepare-stop", UID);
        store = newStore();
        assertTrue(store.blocksRecovery());
        boot = "";
        assertTrue(store.blocksRecovery());
        boot = BOOT_A;
        assertFalse(store.blocksRecovery());
        assertEquals("RUNNING", store.state());
        assertTrue(store.snapshot().isEmpty());
    }
    @Test public void lostForegroundReadinessExpiresWithoutStartingActivity() {
        LifecycleStore.Request request = store.begin("prepare-start", UID);
        observe(request, "READY_TO_LAUNCH", "");
        now += 20_001;
        assertNull(store.takeLaunch());
        assertTrue(store.blocksRecovery());
        assertEquals("UNKNOWN", store.state());
    }
    @Test public void staleFailureCannotInvalidateNextPhase() {
        LifecycleStore.Request first = store.begin("prepare-stop", UID);
        LifecycleStore.Request commit = observe(first, "STOP_PREPARED", "");
        store.fail(first, "OLD_FAILURE");
        assertNotNull(store.pending(commit.action, commit.nonce));
        assertEquals("STOPPING", store.state());
    }
    @Test public void wrongCurrentUidCannotReleaseGate() {
        LifecycleStore.Request check = prepareStart();
        String raw = LifecycleSnapshotTest.receipt(check.action, "START_OBSERVED", "")
                .replace("00000000-0000-4000-8000-000000000001", check.nonce);
        store.observe(check, LifecycleSnapshot.parse(raw), UID + 1, raw);
        assertTrue(store.blocksRecovery());
        assertEquals("UNKNOWN", store.state());
    }

    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        int failCommits;
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String key, String def) { return (String) values.getOrDefault(key, def); }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> def) { return (Set<String>) values.getOrDefault(key, def); }
        public int getInt(String key, int def) { return (Integer) values.getOrDefault(key, def); }
        public long getLong(String key, long def) { return (Long) values.getOrDefault(key, def); }
        public float getFloat(String key, float def) { return (Float) values.getOrDefault(key, def); }
        public boolean getBoolean(String key, boolean def) { return (Boolean) values.getOrDefault(key, def); }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> changes = new HashMap<>();
                private final Set<String> removes = new HashSet<>();
                private boolean clear;
                public Editor putString(String key, String value) { changes.put(key, value); return this; }
                public Editor putStringSet(String key, Set<String> value) { changes.put(key, value); return this; }
                public Editor putInt(String key, int value) { changes.put(key, value); return this; }
                public Editor putLong(String key, long value) { changes.put(key, value); return this; }
                public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
                public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
                public Editor remove(String key) { removes.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (clear) values.clear();
                    for (String key : removes) values.remove(key);
                    values.putAll(changes);
                    if (failCommits > 0) { failCommits--; return false; }
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
}
