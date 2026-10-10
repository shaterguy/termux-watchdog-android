package com.shaterguy.termuxwatchdog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow, flat, ASCII-only control protocol. Never accepts shell text or credentials. */
final class PowerModeSnapshot {
    static final int MAX_LENGTH = 16_384;
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("schema", "command_id", "command_sequence", "state", "requested_mode",
            "operation_id", "revision", "auth_owner", "auth_state", "auth_broker_running",
            "recovery_suppressed", "paused_services", "pending_services", "error_code", "shared_wakelock_state"));
    private static final Pattern MEMBER = Pattern.compile(
            "[ \\t\\r\\n]*\"([a-z_]+)\"[ \\t\\r\\n]*:[ \\t\\r\\n]*(\"[A-Za-z0-9_.:-]*\"|true|false|0|[1-9][0-9]*)[ \\t\\r\\n]*");
    static final String COMMAND_ID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final long MAX_COMMAND_SEQUENCE = 9_007_199_254_740_991L;
    final String commandId;
    final long commandSequence;
    final String state;
    final String requestedMode;
    final String operationId;
    final long revision;
    final String authState;
    final boolean authBrokerRunning;
    final boolean recoverySuppressed;
    final int pausedServices;
    final int pendingServices;
    final String errorCode;

    private PowerModeSnapshot(Map<String, String> values) {
        if (!"termux-watchdog-mode-v2".equals(string(values, "schema"))) throw invalid();
        commandSequence = number(values, "command_sequence");
        if (commandSequence > MAX_COMMAND_SEQUENCE) throw invalid();
        commandId = string(values, "command_id");
        if (!commandId.isEmpty() && !commandId.matches(COMMAND_ID_PATTERN)) throw invalid();
        if (commandId.isEmpty() != (commandSequence == 0)) throw invalid();
        state = string(values, "state");
        if (!Arrays.asList("normal", "draining", "power_saving", "resuming", "blocked").contains(state)) throw invalid();
        requestedMode = string(values, "requested_mode");
        if (!Arrays.asList("normal", "power_saving").contains(requestedMode)) throw invalid();
        operationId = string(values, "operation_id");
        if (operationId.length() > 128) throw invalid();
        revision = number(values, "revision");
        if (!"android".equals(string(values, "auth_owner"))) throw invalid();
        authState = string(values, "auth_state");
        if (!Arrays.asList("ready", "reauth_required", "not_ready", "retry_wait", "starting",
                "authorization_changed", "unknown").contains(authState)) throw invalid();
        if (!"ownership_unknown_no_release".equals(string(values, "shared_wakelock_state"))) throw invalid();
        authBrokerRunning = bool(values, "auth_broker_running");
        if (state.equals("power_saving") && authBrokerRunning) throw invalid();
        recoverySuppressed = bool(values, "recovery_suppressed");
        long paused = number(values, "paused_services");
        long pending = number(values, "pending_services");
        if (paused > 1024 || pending > 1024) throw invalid();
        pausedServices = (int) paused;
        pendingServices = (int) pending;
        errorCode = string(values, "error_code");
        if (!errorCode.matches("[A-Z0-9_]{0,96}")) throw invalid();
    }

    static PowerModeSnapshot parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) throw invalid();
        String text = raw.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
        if (!text.startsWith("{") || !text.endsWith("}")) throw invalid();
        int end = text.length() - 1;
        int cursor = 1;
        Map<String, String> values = new HashMap<>();
        while (cursor < end) {
            Matcher member = MEMBER.matcher(text);
            member.region(cursor, end);
            if (!member.lookingAt()) throw invalid();
            String key = member.group(1);
            if (!KEYS.contains(key) || values.put(key, member.group(2)) != null) throw invalid();
            cursor = member.end();
            if (cursor == end) break;
            if (text.charAt(cursor++) != ',' || cursor == end) throw invalid();
        }
        if (!values.keySet().equals(KEYS)) throw invalid();
        return new PowerModeSnapshot(values);
    }

    boolean isPowerSavingVerified() {
        return state.equals("power_saving") && requestedMode.equals("power_saving")
                && recoverySuppressed && !authBrokerRunning && pendingServices == 0 && errorCode.isEmpty();
    }

    boolean isNormalVerified() {
        return state.equals("normal") && requestedMode.equals("normal")
                && !recoverySuppressed && errorCode.isEmpty() && pendingServices == 0 && pausedServices == 0;
    }

    private static String string(Map<String, String> values, String key) {
        String value = values.get(key);
        if (!value.startsWith("\"") || !value.endsWith("\"")) throw invalid();
        return value.substring(1, value.length() - 1);
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = values.get(key);
        if (!value.equals("true") && !value.equals("false")) throw invalid();
        return value.equals("true");
    }

    private static long number(Map<String, String> values, String key) {
        try {
            long value = Long.parseLong(values.get(key));
            if (value < 0) throw invalid();
            return value;
        } catch (NumberFormatException error) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("MODE_RESPONSE_INVALID");
    }
}
