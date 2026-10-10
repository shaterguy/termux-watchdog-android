package com.shaterguy.termuxwatchdog;

/** Dependency-free tests for the actual protocol and persisted-intent policy. */
public final class PowerModeRegression {
    private static int checks;
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static PowerModeSnapshot identified(String raw, String id) {
        return PowerModeSnapshot.parse(raw.replace("\"command_id\":\"\"", "\"command_id\":\"" + id + "\"")
                .replace("\"command_sequence\":0", "\"command_sequence\":" + (id.equals(A) ? 1 : 2)));
    }
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    private static String wire(String state, String requested, boolean fence, long revision) {
        return "{\"schema\":\"termux-watchdog-mode-v2\",\"command_id\":\"\",\"command_sequence\":0,\"state\":\"" + state
                + "\",\"requested_mode\":\"" + requested
                + "\",\"operation_id\":\"op-123\",\"revision\":" + revision
                + ",\"auth_owner\":\"android\",\"auth_state\":\"ready\",\"auth_broker_running\":" + !state.equals("power_saving") + ""
                + ",\"shared_wakelock_state\":\"ownership_unknown_no_release\",\"recovery_suppressed\":" + fence
                + ",\"paused_services\":0,\"pending_services\":0,\"error_code\":\"\"}";
    }
    private static void rejects(String json) {
        try { PowerModeSnapshot.parse(json); throw new AssertionError("Accepted invalid protocol"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        checks = 0;
        rejects(wire("normal", "normal", false, 0).replace(",\"shared_wakelock_state\":\"ownership_unknown_no_release\"", ""));
        rejects(wire("normal", "normal", false, 0).replace("ownership_unknown_no_release", "released"));
        PowerModeSnapshot normal = PowerModeSnapshot.parse(wire("normal", "normal", false, 0));
        PowerModeSnapshot active = identified(wire("power_saving", "power_saving", true, 2), A);
        check(normal.isNormalVerified(), "normal requires the actual recovery fence to be absent");
        check(!active.isNormalVerified(), "saver blocks automatic recovery");
        check(active.isPowerSavingVerified(), "broker-down settled saver can release native process");
        check(!PowerModeSnapshot.parse(wire("draining", "power_saving", true, 1)).isPowerSavingVerified(), "draining never stops native process");
        check(!PowerModeSnapshot.parse(wire("normal", "normal", true, 1)).isNormalVerified(),
                "a normal label with a recovery fence is not verified normal");
        check(!PowerModeSnapshot.parse(wire("normal", "normal", false, 1)
                .replace("\"paused_services\":0", "\"paused_services\":1")).isNormalVerified(),
                "normal with still-paused services cannot release recovery");
        rejects("\0" + wire("normal", "normal", false, 0) + "\0");
        rejects(wire("normal", "normal", false, 0).replace("{", "{\u000b"));
        check(PowerModeSnapshot.parse(" \t\r\n" + wire("normal", "normal", false, 0) + "\r\n").isNormalVerified(),
                "JSON whitespace is accepted");
        rejects(wire("normal", "normal", false, 0) + "garbage");
        rejects(wire("normal", "normal", false, 0).replace("\"normal\",\"requested", "\"normal\",\"state\":\"power_saving\",\"requested"));
        rejects(wire("normal", "normal", false, 0).replace("\"revision\":0", "\"revision\":-1"));
        rejects(wire("normal", "normal", false, 0).replace("\"revision\":0", "\"revision\":0.5"));
        rejects(wire("normal", "normal", false, 0).replace("\"revision\":0", "\"revision\":9223372036854775808"));
        rejects(wire("normal", "normal", false, 0).replace("\"recovery_suppressed\":false", "\"recovery_suppressed\":\"false\""));
        rejects(wire("normal", "normal", false, 0).replace("\"ready\"", "\"made_up\""));
        rejects(wire("normal", "normal", false, 0).replace("\"error_code\":\"\"", "\"error_code\":\"password=secret\""));
        rejects(wire("normal", "normal", false, 0).replace("\"paused_services\":0,", ""));
        rejects(" ".repeat(16385));
        rejects(wire("normal", "normal", false, 0).replace("\"normal\"", "\"norm\\u0061l\""));
        rejects(wire("normal", "normal", false, 0).replace("termux-watchdog-mode-v2", "termux-watchdog-mode-v1"));
        rejects(wire("normal", "normal", false, 0).replace("android", "termux"));
        rejects(wire("power_saving", "power_saving", true, 1).replace("\"auth_broker_running\":false", "\"auth_broker_running\":true"));
        PowerModePolicy migrated = new PowerModePolicy(1, true, "auth_only", true, 1, A);
        check(migrated.desiredMode().equals("power_saving") && migrated.blocksRecovery() && migrated.awaitingAcknowledgement(), "dev4 intent migrates without releasing gate");
        check(!PowerModeText.error("MODE_RESPONSE_INVALID", false).contains("잠금"), "normal-mode response error does not claim recovery lock");
        check(PowerModeText.error("MODE_RESPONSE_INVALID", true).contains("잠금"), "blocked response error truthfully reports retained lock");
        check(!PowerModeText.error("SECRET_CUSTOM_CODE", false).contains("SECRET_CUSTOM_CODE"), "unmapped mode errors sanitized");
        PowerModePolicy policy = new PowerModePolicy();
        check(!policy.blocksRecovery(), "existing normal monitoring stays available before a request");
        long enable = policy.begin("enable", A);
        check(policy.blocksRecovery() && policy.awaitingAcknowledgement(), "enable blocks before dispatch");
        try { policy.begin("disable", B); throw new AssertionError("queued enable can be overtaken"); }
        catch (IllegalStateException expected) { checks++; }
        check(!policy.observe(enable, "status", normal), "pre-dispatch normal status cannot cancel enable intent");
        check(policy.blocksRecovery(), "lost enable callback remains conservative");
        PowerModePolicy restored = new PowerModePolicy(policy.generation(), policy.blocksRecovery(),
                policy.desiredMode(), policy.awaitingAcknowledgement(), policy.revision(), policy.commandId());
        check(restored.blocksRecovery(), "persisted enable survives app recreation");
        check(restored.observe(enable, "status", active), "fresh authoritative active status reconciles lost callback");
        check(!restored.awaitingAcknowledgement() && restored.blocksRecovery(), "active observation clears pending only");
        long disable = restored.begin("disable", B);
        check(!restored.observe(enable, "enable", active), "late enable callback rejected after disable");
        check(!restored.observe(disable, "status", active), "old active status does not undo pending disable");
        PowerModeSnapshot resumed = identified(wire("normal", "normal", false, 4), B);
        check(restored.observe(disable, "disable", resumed) && !restored.blocksRecovery(), "verified resume releases gate");
        check(!restored.observe(disable, "status", active), "older revision cannot reapply a stale state");
        PowerModePolicy reconnect = new PowerModePolicy();
        check(reconnect.observe(0, "status", active) && reconnect.blocksRecovery(), "reconnect adopts real active mode");
        check(reconnect.observe(1, "status", resumed) && !reconnect.blocksRecovery(), "external verified restoration reconciles");
        PowerModePolicy failed = new PowerModePolicy();
        long request = failed.begin("enable", A);
        PowerModeSnapshot blocked = identified(wire("blocked", "power_saving", true, 1)
                .replace("\"error_code\":\"\"", "\"error_code\":\"TOPOLOGY_UNVERIFIED\""), A);
        check(failed.observe(request, "enable", blocked) && failed.blocksRecovery(), "blocked transition never resumes recovery");
        long rescue = failed.begin("disable", B);
        check(failed.observe(rescue, "disable", resumed) && !failed.blocksRecovery(), "explicit restore can leave blocked transition");
        PowerModePolicy rejected = new PowerModePolicy();
        long rejectedId = rejected.begin("enable", A);
        rejected.commandFinishedWithoutObservation(rejectedId);
        check(!rejected.awaitingAcknowledgement() && rejected.blocksRecovery(),
                "terminal failure allows restore without pretending a successful mode");
        rejected.begin("disable", B);
        PowerModePolicy sameMode = new PowerModePolicy();
        sameMode.observe(0, "status", identified(wire("resuming", "normal", true, 4), A));
        long retry = sameMode.begin("disable", B);
        check(!sameMode.observe(retry, "status", identified(wire("normal", "normal", false, 6), A)),
                "existing same-mode completion cannot acknowledge a queued command");
        check(sameMode.awaitingAcknowledgement(), "same-mode retry remains single flight");
        check(sameMode.observe(retry, "status", identified(wire("normal", "normal", false, 7), B)),
                "only matching accepted command identity acknowledges lost callback");
        rejects(wire("normal", "normal", false, 0).replace("\"command_id\":\"\"", "\"command_id\":\"not-a-uuid\""));
        PowerModePolicy freshApp = new PowerModePolicy();
        PowerModeSnapshot remoteHighwater = PowerModeSnapshot.parse(wire("normal", "normal", false, 20)
                .replace("\"command_sequence\":0", "\"command_sequence\":999")
                .replace("\"command_id\":\"\"", "\"command_id\":\"" + A + "\""));
        check(freshApp.observe(0, "status", remoteHighwater) && freshApp.generation() == 999,
                "recreated app learns authoritative sequence before issuing a new command");
        check(freshApp.begin("enable", A) == 1000, "next command exceeds authoritative highwater");
        rejects(wire("normal", "normal", false, 0).replace("\"command_sequence\":0", "\"command_sequence\":9007199254740992"));
        rejects(wire("normal", "normal", false, 0).replace("\"command_sequence\":0", "\"command_sequence\":1"));
        rejects(wire("normal", "normal", false, 0).replace("\"command_id\":\"\"", "\"command_id\":\"" + A + "\""));
        PowerModePolicy interruptedEnable = new PowerModePolicy();
        long interrupted = interruptedEnable.begin("enable", A);
        check(!interruptedEnable.observe(interrupted, "status", identified(wire("normal", "normal", false, 1), A)),
                "matching enable tuple without committed mode intent cannot release recovery");
        check(interruptedEnable.blocksRecovery() && interruptedEnable.awaitingAcknowledgement(),
                "interrupted enable remains visibly unresolved");
        System.out.println("POWER_MODE_REGRESSION_PASS checks=" + checks);
    }
}
