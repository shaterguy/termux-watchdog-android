package com.shaterguy.termuxwatchdog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately narrow JSON subset. Credentials never pass through diagnostics. */
final class AuthProtocol {
    static final int MAX_FRAME_BYTES = 16_384;
    static final int MAX_TOKEN_BYTES = 8_192;
    private static final Pattern MEMBER = Pattern.compile(
            "[ \\t]*\"([A-Za-z]+)\"[ \\t]*:[ \\t]*(1|\"[A-Za-z0-9._~+/=-]*\")[ \\t]*");
    private static final Set<String> ERRORS = new HashSet<>(Arrays.asList("AUTH_REAUTH_REQUIRED",
            "AUTH_NOT_CONFIGURED", "AUTH_UNAVAILABLE", "AUTH_TIMEOUT", "BAD_REQUEST", "PEER_DENIED"));
    private static final Set<String> STATES = new HashSet<>(Arrays.asList("ready", "reauth_required",
            "not_ready", "unavailable", "not_configured"));

    static final class Request {
        final String operation;
        final String rejectedToken;
        Request(String operation, String rejectedToken) { this.operation = operation; this.rejectedToken = rejectedToken; }
    }

    static boolean validToken(String token) {
        return token != null && token.length() <= MAX_TOKEN_BYTES
                && token.matches("[A-Za-z0-9._~+/-]+=*");
    }

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int count = 0; count < MAX_FRAME_BYTES; count++) {
            int next = input.read();
            if (next == '\n') {
                if (input.read() != -1) throw new IOException("BAD_REQUEST");
                return frame.toString(StandardCharsets.US_ASCII.name());
            }
            if (next < 0 || next > 127 || next == '\r' || (next < 32 && next != '\t')) throw new IOException("BAD_REQUEST");
            frame.write(next);
        }
        throw new IOException("BAD_REQUEST");
    }

    static Request parse(String raw) {
        if (raw == null || raw.length() >= MAX_FRAME_BYTES) throw invalid();
        String value = raw.replaceAll("^[ \\t]+|[ \\t]+$", "");
        if (!value.startsWith("{") || !value.endsWith("}")) throw invalid();
        int end = value.length() - 1;
        int position = 1;
        Map<String, String> values = new HashMap<>();
        while (position < end) {
            Matcher member = MEMBER.matcher(value);
            member.region(position, end);
            if (!member.lookingAt() || values.put(member.group(1), member.group(2)) != null) throw invalid();
            position = member.end();
            if (position == end) break;
            if (value.charAt(position++) != ',' || position == end) throw invalid();
        }
        if (!"1".equals(values.get("version"))) throw invalid();
        String operation = unquote(values.get("op"));
        Set<String> expected = new HashSet<>(Arrays.asList("version", "op"));
        String rejected = null;
        if (operation.equals("refresh")) {
            expected.add("rejectedAccessToken");
            rejected = unquote(values.get("rejectedAccessToken"));
            if (!validToken(rejected)) throw invalid();
        } else if (!operation.equals("status") && !operation.equals("access")) throw invalid();
        if (!expected.equals(values.keySet())) throw invalid();
        return new Request(operation, rejected);
    }

    static boolean isProbe(String raw) {
        return raw != null && raw.length() <= 128 && raw.matches("[ \\t]*\\{[ \\t]*\\\"version\\\"[ \\t]*:[ \\t]*1[ \\t]*,[ \\t]*\\\"op\\\"[ \\t]*:[ \\t]*\\\"probe\\\"[ \\t]*\\}[ \\t]*");
    }
    /** Canonical server replies only; status can never carry a credential. */
    static boolean validResponse(String raw, boolean statusOperation) {
        if (raw == null || raw.length() >= MAX_FRAME_BYTES) return false;
        for (String code : ERRORS) if (error(code).equals(raw + "\n")) return true;
        if (statusOperation) {
            for (String state : STATES) if (status(state).equals(raw + "\n")) return true;
            return false;
        }
        String prefix = "{\"version\":1,\"ok\":true,\"accessToken\":\"";
        String suffix = "\",\"expiryKnown\":false}";
        if (!raw.startsWith(prefix) || !raw.endsWith(suffix)) return false;
        return validToken(raw.substring(prefix.length(), raw.length() - suffix.length()));
    }

    static String success(String token) {
        if (!validToken(token)) return error("AUTH_UNAVAILABLE");
        return "{\"version\":1,\"ok\":true,\"accessToken\":\"" + token + "\",\"expiryKnown\":false}\n";
    }
    static String status(String state) {
        if (!STATES.contains(state)) state = "unavailable";
        return "{\"version\":1,\"ok\":true,\"state\":\"" + state + "\",\"expiryKnown\":false}\n";
    }
    static String error(String code) {
        if (!ERRORS.contains(code)) code = "AUTH_UNAVAILABLE";
        return "{\"version\":1,\"ok\":false,\"error\":{\"code\":\"" + code + "\"}}\n";
    }
    static String safeError(String code) { return ERRORS.contains(code) ? code : "AUTH_UNAVAILABLE"; }
    private static String unquote(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length()-1) != '"') throw invalid();
        return value.substring(1, value.length()-1);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("BAD_REQUEST"); }
}
