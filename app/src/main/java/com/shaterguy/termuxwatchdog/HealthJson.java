package com.shaterguy.termuxwatchdog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.SocketTimeoutException;

/** Validates one complete JSON object while retaining only its top-level ok boolean. */
final class HealthJson {
    // Bounds parser stack use, not response/catalog size. Exceeding it is UNKNOWN.
    private static final int MAX_DEPTH = 128;
    private final Reader input;
    private final long deadlineNanos;
    private int current;
    private boolean sawOk;
    private boolean healthy;

    private HealthJson(Reader input, long deadlineNanos) {
        this.input = new BufferedReader(input, 4096);
        this.deadlineNanos = deadlineNanos;
    }

    static boolean read(Reader input, long deadlineNanos) throws IOException {
        HealthJson parser = new HealthJson(input, deadlineNanos);
        parser.checkDeadline();
        parser.advance();
        parser.space();
        parser.object(0, true);
        parser.space();
        if (parser.current != -1) throw new InvalidJsonException();
        parser.checkDeadline();
        return parser.sawOk && parser.healthy;
    }

    private void checkDeadline() throws SocketTimeoutException {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new SocketTimeoutException("health_read_deadline");
        }
    }

    private void advance() throws IOException {
        checkDeadline();
        current = input.read();
    }

    private void space() throws IOException {
        while (current == ' ' || current == '\t' || current == '\r' || current == '\n') advance();
    }

    private void expect(int c) throws IOException {
        if (current != c) throw new InvalidJsonException();
        advance();
    }

    private void object(int depth, boolean root) throws IOException {
        guardDepth(depth);
        expect('{');
        space();
        if (current == '}') { advance(); return; }
        while (true) {
            boolean nameIsOk = string();
            space();
            expect(':');
            space();
            int kind = value(depth + 1);
            if (root && nameIsOk) {
                if (sawOk) throw new InvalidJsonException();
                sawOk = true;
                healthy = kind == 1;
            }
            space();
            if (current == '}') { advance(); return; }
            expect(',');
            space();
        }
    }

    private void array(int depth) throws IOException {
        guardDepth(depth);
        expect('[');
        space();
        if (current == ']') { advance(); return; }
        while (true) {
            value(depth + 1);
            space();
            if (current == ']') { advance(); return; }
            expect(',');
            space();
        }
    }

    private static void guardDepth(int depth) throws InvalidJsonException {
        if (depth > MAX_DEPTH) throw new InvalidJsonException("health_json_nesting_limit");
    }

    // 1 is the literal true; other well-formed values are 0.
    private int value(int depth) throws IOException {
        switch (current) {
            case '{': object(depth, false); return 0;
            case '[': array(depth); return 0;
            case '"': string(); return 0;
            case 't': literal("true"); return 1;
            case 'f': literal("false"); return 0;
            case 'n': literal("null"); return 0;
            default: number(); return 0;
        }
    }

    private void literal(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) expect(text.charAt(i));
    }

    // No String/StringBuilder allocation for catalog values or arbitrary field names.
    private boolean string() throws IOException {
        expect('"');
        int length = 0;
        boolean matches = true;
        while (current != '"') {
            if (current < 0x20) throw new InvalidJsonException();
            int decoded = current;
            if (current == '\\') {
                advance();
                switch (current) {
                    case '"': case '\\': case '/': decoded = current; advance(); break;
                    case 'b': decoded = '\b'; advance(); break;
                    case 'f': decoded = '\f'; advance(); break;
                    case 'n': decoded = '\n'; advance(); break;
                    case 'r': decoded = '\r'; advance(); break;
                    case 't': decoded = '\t'; advance(); break;
                    case 'u':
                        decoded = 0;
                        for (int i = 0; i < 4; i++) {
                            advance();
                            int digit = hex(current);
                            if (digit < 0) throw new InvalidJsonException();
                            decoded = decoded * 16 + digit;
                        }
                        advance();
                        break;
                    default: throw new InvalidJsonException();
                }
            } else {
                advance();
            }
            if (length >= 2 || decoded != (length == 0 ? 'o' : 'k')) matches = false;
            if (length < 3) length++;
        }
        advance();
        return matches && length == 2;
    }

    private static int hex(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean digit(int c) { return c >= '0' && c <= '9'; }

    private void number() throws IOException {
        if (current == '-') advance();
        if (current == '0') {
            advance();
        } else {
            if (current < '1' || current > '9') throw new InvalidJsonException();
            do { advance(); } while (digit(current));
        }
        if (current == '.') {
            advance();
            if (!digit(current)) throw new InvalidJsonException();
            do { advance(); } while (digit(current));
        }
        if (current == 'e' || current == 'E') {
            advance();
            if (current == '+' || current == '-') advance();
            if (!digit(current)) throw new InvalidJsonException();
            do { advance(); } while (digit(current));
        }
    }

    static final class InvalidJsonException extends IOException {
        InvalidJsonException() { super("health_invalid_json"); }
        InvalidJsonException(String reason) { super(reason); }
    }
}
