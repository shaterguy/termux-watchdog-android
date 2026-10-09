package com.shaterguy.termuxwatchdog;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Dependency-free host regression suite; also invoked by the Gradle JUnit adapter. */
public final class HealthRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static boolean parse(String json) throws IOException {
        return HealthJson.read(new StringReader(json), System.nanoTime() + 4_000_000_000L);
    }
    private static void invalid(String json) throws Exception {
        try { parse(json); throw new AssertionError("Accepted malformed JSON: " + json); }
        catch (HealthJson.InvalidJsonException expected) { checks++; }
    }
    private static HealthClient.HealthResult response(String body) {
        return HealthClient.readResponse(200,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                System.nanoTime() + 4_000_000_000L);
    }
    public static void main(String[] args) throws Exception {
        checks = 0;
        check(parse("{\"ok\":true}"), "minimal health");
        check(parse("{\"ok\":true,\"catalog\":\"" + "x".repeat(60000) + "\"}"), "real-sized catalog");
        check(parse("{\"catalog\":[{\"x\":[null,false,-1.2e+5,\"a\\n\\u0061\"]}],\"ok\":true}"), "ok after catalog");
        check(parse("{\"\\u006f\\u006b\":true}"), "escaped member name");
        check(!parse("{\"catalog\":{\"ok\":true}}"), "nested ok is not liveness");
        check(!parse("{\"ok\":false}"), "false health");
        check(!parse("{\"ok\":\"true\"}"), "boolean must be strict");
        check(!parse("{}"), "missing ok");
        for (String bad : new String[]{"", "[]", "{", "{\"ok\":true", "{\"ok\":true}x",
                "{\"ok\":true,}", "{\"ok\":true,\"ok\":false}",
                "{\"ok\":true,\"x\":[1,]}", "{\"ok\":true,\"x\":01}",
                "{\"ok\":true,\"x\":1.}", "{\"ok\":true,\"x\":1e}",
                "{\"ok\":true,\"x\":\"bad\\q\"}", "{\"ok\":true,\"x\":\"bad\n\"}",
                "{\"ok\":true,\"x\":NaN}", "{\"ok\":true,\"x\":tru}"}) invalid(bad);
        check(HealthJson.read(new CatalogReader(20_000_000), System.nanoTime() + 4_000_000_000L),
                "future 20MB catalog streams without proportional allocation");
        try { HealthJson.read(new StringReader("{\"ok\":true}"), System.nanoTime() - 1); throw new AssertionError("deadline"); }
        catch (SocketTimeoutException expected) { checks++; }
        try { parse("{\"ok\":true,\"nested\":" + "[".repeat(200) + "0" + "]".repeat(200) + "}"); throw new AssertionError("nesting guard"); }
        catch (HealthJson.InvalidJsonException expected) { checks++; }
        check(response("{\"ok\":true}").isHealthy(), "HTTP valid health");
        check(response("{\"ok\":true,\"x\":[}").isUnknown(), "malformed after ok is unknown");
        check(response("{\"ok\":true").isUnknown(), "truncated after ok is unknown");
        check(response("{\"ok\":false}").isUnknown(), "negative JSON must not claim stopped");
        check(HealthClient.readResponse(503, new ByteArrayInputStream(new byte[0]), Long.MAX_VALUE).isUnknown(), "HTTP error unknown");
        check(HealthClient.failure(new SocketTimeoutException("timeout")).isUnknown(), "timeout unknown");
        check(HealthClient.failure(new SecurityException("permission")).isUnknown(), "permission unknown");
        check(HealthClient.failure(new ConnectException("Permission denied")).isUnknown(), "connect permission unknown");
        check(HealthClient.failure(new IOException("read failed")).isUnknown(), "read failure unknown");
        check(HealthClient.failure(new ConnectException("Connection refused")).isUnavailable(), "refused endpoint unavailable");
        check(HealthClient.failure(new ConnectException("ECONNREFUSED (Connection refused)")).isUnavailable(), "Android refused endpoint");
        byte[] badUtf8 = new byte[]{'{','"','o','k','"',':','t','r','u','e',',','"','x','"',':','"',(byte)0xc3,0x28,'"','}'};
        check(HealthClient.readResponse(200, new ByteArrayInputStream(badUtf8), Long.MAX_VALUE).isUnknown(), "malformed UTF8 unknown");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new ConnectException("Connection refused"); }
        };
        check(HealthClient.readResponse(200, broken, Long.MAX_VALUE).isUnknown(), "body read failure never unavailable");
        Reader trickle = new Reader() {
            @Override public int read(char[] out, int offset, int length) throws IOException {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                out[offset] = ' '; return 1;
            }
            @Override public void close() {}
        };
        try { HealthJson.read(trickle, System.nanoTime() + 5_000_000L); throw new AssertionError("trickle deadline"); }
        catch (SocketTimeoutException expected) { checks++; }
        HealthStateMachine state = new HealthStateMachine();
        state.onHealthResult(false);state.onHealthResult(false);
        check(state.onUnknownResult() == HealthStateMachine.State.UNKNOWN, "unknown visible");
        check(state.consecutiveFailures() == 0, "unknown breaks failed sequence");
        check(state.onHealthResult(false) == HealthStateMachine.State.SUSPECT, "no recovery after unknown");
        state.onHealthResult(false);check(state.onHealthResult(false) == HealthStateMachine.State.DOWN, "three actual refused checks still recover");
        check(state.onUnknownResult() == HealthStateMachine.State.UNKNOWN, "unknown blocks existing down recovery");
        check(state.onHealthResult(true) == HealthStateMachine.State.HEALTHY, "healthy clears unknown");
        check(new HealthStateMachine(HealthStateMachine.State.UNKNOWN,0).state() == HealthStateMachine.State.UNKNOWN, "unknown survives reload");
        System.out.println("HEALTH_REGRESSION_PASS checks=" + checks);
    }
    private static final class CatalogReader extends Reader {
        private final String prefix = "{\"catalog\":\"";
        private final String suffix = "\",\"ok\":true}";
        private final int size;
        private int position;
        CatalogReader(int size) { this.size=size; }
        @Override public int read(char[] out,int offset,int length) {
            int total=prefix.length()+size+suffix.length();
            if(position==total)return -1;
            int n=Math.min(length,total-position);
            for(int i=0;i<n;i++,position++)out[offset+i]=position<prefix.length()?prefix.charAt(position):
                    position<prefix.length()+size?'x':suffix.charAt(position-prefix.length()-size);
            return n;
        }
        @Override public void close() {}
    }
}
