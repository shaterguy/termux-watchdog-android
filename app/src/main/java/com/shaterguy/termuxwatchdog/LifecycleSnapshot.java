package com.shaterguy.termuxwatchdog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded control receipt. It never carries commands, account data or tokens. */
final class LifecycleSnapshot {
    static final int MAX_LENGTH = 4096;
    static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "schema", "action", "command_id", "command_sequence", "state", "boot_id",
            "termux_uid", "observed_processes", "supervisor_count", "enabled_registrations",
            "running_registrations", "registration_errors", "login_observed",
            "final_stop_confirmed", "error_code", "observed_at_ms"));
    private static final Set<String> ERRORS = new HashSet<>(Arrays.asList("", "BAD_COMMAND",
            "PROTOCOL_MISMATCH", "UID_MISMATCH", "PACKAGE_UID_UNAVAILABLE", "BOOT_ID_UNAVAILABLE",
            "STATE_INVALID", "STATE_IO", "BUSY", "STALE_COMMAND", "SAVER_ACTIVE",
            "SAVER_STATE_UNAVAILABLE", "PREPARE_EXPIRED", "PIDFD_UNAVAILABLE", "PROC_UNREADABLE",
            "SERVICE_STOP_UNAVAILABLE", "SERVICE_STOP_FAILED", "PARENT_EXIT_TIMEOUT", "STOP_TIMEOUT",
            "START_TIMEOUT", "LOGIN_NOT_OBSERVED", "DUPLICATE_SUPERVISORS", "ORPHAN_SUPERVISORS",
            "SUPERVISOR_UNAVAILABLE", "REGISTRATION_ERRORS", "RESUME_FENCE_FAILED", "INTERNAL"));
    private static final Pattern MEMBER = Pattern.compile(
            "[ \\t\\r\\n]*\"([a-z_]+)\"[ \\t\\r\\n]*:[ \\t\\r\\n]*(\"[A-Za-z0-9_-]*\"|true|false|0|[1-9][0-9]*)[ \\t\\r\\n]*");
    final String action, commandId, state, bootId, errorCode;
    final long commandSequence, observedAtMs;
    final int termuxUid, observedProcesses, supervisorCount, enabledRegistrations;
    final int runningRegistrations, registrationErrors;
    final boolean loginObserved;

    private LifecycleSnapshot(Map<String, String> values) {
        if (!string(values, "schema").equals("termux-watchdog-lifecycle-v1")) throw invalid();
        action = string(values, "action");
        if (!validAction(action)) throw invalid();
        commandId = string(values, "command_id");
        if (!commandId.matches(UUID_PATTERN)) throw invalid();
        commandSequence = number(values, "command_sequence");
        if (commandSequence == 0 || commandSequence > MAX_SEQUENCE) throw invalid();
        state = string(values, "state");
        if (!stateMatches(action, state)) throw invalid();
        bootId = string(values, "boot_id");
        if (!bootId.matches(UUID_PATTERN) && !(state.equals("REJECTED") && bootId.isEmpty())) throw invalid();
        termuxUid = integer(values, "termux_uid");
        if (termuxUid == 0 && !state.equals("REJECTED")) throw invalid();
        observedProcesses = integer(values, "observed_processes");
        supervisorCount = integer(values, "supervisor_count");
        enabledRegistrations = integer(values, "enabled_registrations");
        runningRegistrations = integer(values, "running_registrations");
        registrationErrors = integer(values, "registration_errors");
        if (runningRegistrations > enabledRegistrations) throw invalid();
        loginObserved = bool(values, "login_observed");
        if (bool(values, "final_stop_confirmed")) throw invalid();
        errorCode = string(values, "error_code");
        if (!ERRORS.contains(errorCode)) throw invalid();
        if (state.equals("REJECTED") && errorCode.isEmpty()) throw invalid();
        if (state.equals("START_OBSERVED") && (!loginObserved || supervisorCount != 1
                || registrationErrors != 0 || runningRegistrations != enabledRegistrations)) throw invalid();
        if (!state.equals("REJECTED") && !state.equals("START_PARTIAL") && !errorCode.isEmpty()) throw invalid();
        observedAtMs = number(values, "observed_at_ms");
    }

    static boolean validAction(String action) {
        return Arrays.asList("prepare-stop", "commit-stop", "prepare-start", "check-start").contains(action);
    }

    static boolean stateMatches(String action, String state) {
        if (state.equals("REJECTED")) return true;
        switch (action) {
            case "prepare-stop": return state.equals("STOP_PREPARED");
            case "commit-stop": return state.equals("STOP_COMMITTED");
            case "prepare-start": return state.equals("READY_TO_LAUNCH");
            case "check-start": return state.equals("START_OBSERVED") || state.equals("START_PARTIAL");
            default: return false;
        }
    }

    static LifecycleSnapshot parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) throw invalid();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c < 32 && c != '\t' && c != '\r' && c != '\n') || c > 126) throw invalid();
        }
        String text = raw.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw invalid();
        int cursor = 1, end = text.length() - 1;
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
        return new LifecycleSnapshot(values);
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
    private static int integer(Map<String, String> values, String key) {
        long value = number(values, key);
        if (value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("LIFECYCLE_RESPONSE_INVALID");
    }
}
