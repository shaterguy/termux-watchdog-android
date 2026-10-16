package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LifecycleSnapshotTest {
    private static final String NONCE = "00000000-0000-4000-8000-000000000001";
    private static final String BOOT = "00000000-0000-4000-8000-000000000002";
    static String receipt(String action, String state, String error) {
        return "{\"schema\":\"termux-watchdog-lifecycle-v1\",\"action\":\"" + action
                + "\",\"command_id\":\"" + NONCE + "\",\"command_sequence\":1,\"state\":\""
                + state + "\",\"boot_id\":\"" + BOOT
                + "\",\"termux_uid\":10031,\"observed_processes\":42,\"supervisor_count\":1,"
                + "\"enabled_registrations\":12,\"running_registrations\":12,\"registration_errors\":0,"
                + "\"login_observed\":true,\"final_stop_confirmed\":false,\"error_code\":\""
                + error + "\",\"observed_at_ms\":12345}\n";
    }
    private static void rejects(String raw) {
        try { LifecycleSnapshot.parse(raw); fail("must reject"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void validFixedPhasesAndPartialObservation() {
        assertEquals("STOP_PREPARED", LifecycleSnapshot.parse(receipt("prepare-stop", "STOP_PREPARED", "")).state);
        assertEquals("STOP_COMMITTED", LifecycleSnapshot.parse(receipt("commit-stop", "STOP_COMMITTED", "")).state);
        assertEquals("READY_TO_LAUNCH", LifecycleSnapshot.parse(receipt("prepare-start", "READY_TO_LAUNCH", "")).state);
        assertTrue(LifecycleSnapshot.parse(receipt("check-start", "START_OBSERVED", "")).loginObserved);
        assertEquals("REGISTRATION_ERRORS", LifecycleSnapshot.parse(receipt("check-start", "START_PARTIAL",
                "REGISTRATION_ERRORS").replace("\"registration_errors\":0", "\"registration_errors\":1")).errorCode);
    }
    @Test public void rejectionRequiresKnownError() {
        rejects(receipt("prepare-stop", "REJECTED", ""));
        rejects(receipt("prepare-stop", "REJECTED", "ARBITRARY"));
        assertEquals("SAVER_ACTIVE", LifecycleSnapshot.parse(receipt("prepare-stop", "REJECTED", "SAVER_ACTIVE")).errorCode);
    }
    @Test public void noFinalStopClaimAccepted() {
        rejects(receipt("commit-stop", "STOP_COMMITTED", "").replace("\"final_stop_confirmed\":false", "\"final_stop_confirmed\":true"));
    }
    @Test public void actionStatePairsCannotBeSubstituted() {
        rejects(receipt("prepare-stop", "START_OBSERVED", ""));
        rejects(receipt("check-start", "STOP_COMMITTED", ""));
        rejects(receipt("shell", "REJECTED", "BAD_COMMAND"));
    }
    @Test public void successRequiresLoginSupervisorAndEveryEnabledRegistration() {
        String good = receipt("check-start", "START_OBSERVED", "");
        rejects(good.replace("\"login_observed\":true", "\"login_observed\":false"));
        rejects(good.replace("\"supervisor_count\":1", "\"supervisor_count\":2"));
        rejects(good.replace("\"registration_errors\":0", "\"registration_errors\":1"));
        rejects(good.replace("\"running_registrations\":12", "\"running_registrations\":11"));
    }
    @Test public void rejectsMissingExtraDuplicateAndTrailingCommaMembers() {
        String good = receipt("prepare-stop", "STOP_PREPARED", "");
        rejects(good.replace("\"termux_uid\":10031,", ""));
        rejects(good.replace("{", "{\"token\":\"anything\","));
        rejects(good.replace("{", "{\"termux_uid\":10031,"));
        rejects(good.replace("}", ",}"));
    }
    @Test public void sequenceMustBePositiveSafeInteger() {
        String good = receipt("prepare-stop", "STOP_PREPARED", "");
        for (String number : new String[]{"0", "-1", "01", "1.0", "1e2", "9007199254740992", "9223372036854775808"})
            rejects(good.replace("\"command_sequence\":1", "\"command_sequence\":" + number));
    }
    @Test public void uuidAndBootMustBeExact() {
        String good = receipt("prepare-stop", "STOP_PREPARED", "");
        rejects(good.replace(NONCE, "not-a-nonce"));
        rejects(good.replace(BOOT, ""));
        rejects(good.replace(BOOT, "00000000-0000-4000-8000-00000000000A"));
    }
    @Test public void boundsAndAsciiAreStrict() {
        String good = receipt("prepare-stop", "STOP_PREPARED", "");
        rejects(null);
        rejects(" ".repeat(LifecycleSnapshot.MAX_LENGTH) + good);
        rejects(String.valueOf((char) 0) + good);
        rejects(good.replace("STOP_PREPARED", "종료"));
        rejects(good.replace("\"observed_processes\":42", "\"observed_processes\":2147483648"));
    }
    @Test public void booleanAndNumericTypesCannotBeQuoted() {
        String good = receipt("prepare-stop", "STOP_PREPARED", "");
        rejects(good.replace("\"login_observed\":true", "\"login_observed\":\"true\""));
        rejects(good.replace("\"termux_uid\":10031", "\"termux_uid\":\"10031\""));
    }
}
