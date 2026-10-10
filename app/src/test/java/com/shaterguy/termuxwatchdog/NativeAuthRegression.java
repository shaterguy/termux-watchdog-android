package com.shaterguy.termuxwatchdog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs the production pure seams without Android, Google services or real tokens. */
public final class NativeAuthRegression {
    private static int checks;
    private static final String TOKEN = "synthetic-token.1";
    private static final String NEXT = "synthetic-token.2";
    private static final String PIN = "a".repeat(64);
    private static void check(boolean yes, String what) {
        checks++;
        if (!yes) throw new AssertionError(what);
    }
    private static void rejects(String wire) throws Exception {
        try { AuthProtocol.parse(wire); throw new AssertionError("accepted bad request"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void badFrame(byte[] bytes) throws Exception {
        try { AuthProtocol.read(new ByteArrayInputStream(bytes)); throw new AssertionError("accepted bad frame"); }
        catch (IOException expected) { checks++; }
    }
    private static final class Provider implements AuthCoordinator.Provider {
        int calls;
        final List<String> cleared = new ArrayList<>();
        CompletableFuture<AuthCoordinator.Result> next = new CompletableFuture<>();
        CompletableFuture<Void> clearing = CompletableFuture.completedFuture(null);
        public CompletableFuture<AuthCoordinator.Result> authorize() { calls++; return next; }
        public CompletableFuture<Void> clear(String rejected) { cleared.add(rejected); return clearing; }
    }
    public static void main(String[] args) throws Exception {
        checks = 0;
        check(AuthProtocol.parse("{\"version\":1,\"op\":\"access\"}").operation.equals("access"), "access parsed");
        check(AuthProtocol.parse("{\"op\":\"status\",\"version\":1}").operation.equals("status"), "order independent");
        check(AuthProtocol.parse("{\"version\":1,\"op\":\"refresh\",\"rejectedAccessToken\":\"" + TOKEN + "\"}").rejectedToken.equals(TOKEN), "refresh exact token");
        for (String bad : new String[]{"", "{}", "[]", "null", "{\"version\":1,\"op\":\"revoke\"}",
                "{\"version\":2,\"op\":\"access\"}", "{\"version\":\"1\",\"op\":\"access\"}",
                "{\"version\":1,\"version\":1,\"op\":\"access\"}", "{\"version\":1,\"op\":\"status\",\"extra\":\"x\"}",
                "{\"version\":1,\"op\":\"refresh\"}", "{\"version\":1,\"op\":\"access\",\"rejectedAccessToken\":\"x\"}",
                "{\"version\":1,\"op\":\"refresh\",\"rejectedAccessToken\":\"\"}",
                "{\"version\":1,\"op\":\"refresh\",\"rejectedAccessToken\":\"x y\"}",
                "{\"version\":1,\"op\":\"refresh\",\"rejectedAccessToken\":\"x\\ny\"}",
                "{\"version\":1,\"op\":\"access\",}", "{\"version\":1,\"op\":\"access\"}x"}) rejects(bad);
        rejects("{\"version\":1,\"op\":\"refresh\",\"rejectedAccessToken\":\"" + "x".repeat(8193) + "\"}");
        String atLimit = "{\"version\":1,\"op\":\"access\"}";
        atLimit = " ".repeat(16383-atLimit.length()) + atLimit;
        check(AuthProtocol.read(new ByteArrayInputStream((atLimit + "\n").getBytes(StandardCharsets.US_ASCII))).length() == 16383, "exact total frame boundary");
        badFrame((atLimit + " \n").getBytes(StandardCharsets.US_ASCII));
        badFrame("{}".getBytes(StandardCharsets.US_ASCII));
        badFrame("{}\n{}\n".getBytes(StandardCharsets.US_ASCII));
        badFrame(new byte[]{(byte)0xff, '\n'});
        check(!AuthProtocol.status("ready").contains("accessToken"), "status has no token");
        check(AuthProtocol.success(TOKEN).contains("\"expiryKnown\":false"), "expiry honestly unknown");
        check(!AuthProtocol.success(TOKEN).contains("expires"), "no expiry fabricated");
        check(!AuthProtocol.error("token-containing-arbitrary-message").contains("token-containing"), "unknown error sanitized");
        check(AuthPeerPolicy.permits(10234, 10234, "com.termux", new String[]{PIN}, PIN), "verified package uid signer accepted");
        check(!AuthPeerPolicy.permits(10234, 10234, "com.termux", new String[]{PIN}, ""), "missing pin closed");
        check(!AuthPeerPolicy.permits(10234, 10235, "com.termux", new String[]{PIN}, PIN), "reassigned uid denied");
        check(!AuthPeerPolicy.permits(10234, 10234, "com.other", new String[]{PIN}, PIN), "wrong package denied");
        check(!AuthPeerPolicy.permits(10234, 10234, "com.termux", new String[]{"b".repeat(64)}, PIN), "wrong signer denied");
        check(!AuthPeerPolicy.permits(10234, 10234, "com.termux", new String[]{PIN, "b".repeat(64)}, PIN), "additional signer denied");
        check(!AuthPeerPolicy.permits(0, 0, "com.termux", new String[]{PIN}, PIN), "root is not the Termux application");
        check(AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux", "com.termux.api", "com.termux.boot"},
                new int[]{10234,10234,10234}, new String[][]{{PIN},{PIN},{PIN}}, PIN), "official shared UID owners allowed only with all matching proofs");
        check(!AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux", "com.termux.evil"},
                new int[]{10234,10234}, new String[][]{{PIN},{PIN}}, PIN), "prefix is not owner trust");
        check(!AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux", "com.termux.api"},
                new int[]{10234,10234}, new String[][]{{PIN},{"b".repeat(64)}}, PIN), "mixed shared UID signer denied");
        check(!AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux", "com.termux.api"},
                new int[]{10234,10235}, new String[][]{{PIN},{PIN}}, PIN), "shared UID package proof mismatch denied");
        check(!AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux.api"},
                new int[]{10234}, new String[][]{{PIN}}, PIN), "Termux base package required");
        check(!AuthPeerPolicy.permitsOwners(10234, new String[]{"com.termux", "com.termux"},
                new int[]{10234,10234}, new String[][]{{PIN},{PIN}}, PIN), "duplicate owner proof denied");
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        try {
            Provider provider = new Provider();
            AuthCoordinator auth = new AuthCoordinator(provider, timers, 2000);
            check(auth.state().equals("not_ready"), "cold start not ready");
            CompletableFuture<AuthCoordinator.Result> first = auth.request(null);
            check(first == auth.request(null) && provider.calls == 1, "concurrent access coalesces");
            provider.next.complete(AuthCoordinator.Result.token(TOKEN));
            check(first.get().token.equals(TOKEN) && auth.state().equals("ready"), "observed SDK success");
            provider.next = new CompletableFuture<>();
            CompletableFuture<AuthCoordinator.Result> second = auth.request(null);
            check(provider.calls == 2 && !second.isDone(), "subsequent access goes to SDK, no invented freshness cache");
            provider.next.complete(AuthCoordinator.Result.token(TOKEN));
            provider.next = new CompletableFuture<>();
            provider.clearing = new CompletableFuture<>();
            CompletableFuture<AuthCoordinator.Result> refresh = auth.request(TOKEN);
            check(provider.cleared.size() == 1 && provider.calls == 2, "wait for clear before authorize");
            check(refresh == auth.request(TOKEN), "same rejection refresh coalesces");
            check(refresh == auth.request(null), "access joins refresh");
            provider.clearing.complete(null);
            check(provider.calls == 3, "one authorize after clear");
            provider.next.complete(AuthCoordinator.Result.token(NEXT));
            check(refresh.get().token.equals(NEXT), "new token returned");
            provider.next = CompletableFuture.completedFuture(AuthCoordinator.Result.token(NEXT));
            check(auth.request(TOKEN).get().token.equals(NEXT) && provider.cleared.size() == 1, "stale rejection never clears newer issued token");
            Provider cold = new Provider();
            cold.next.complete(AuthCoordinator.Result.token(NEXT));
            AuthCoordinator restarted = new AuthCoordinator(cold, timers, 2000);
            check(restarted.request(TOKEN).get().ok() && cold.cleared.size() == 1 && cold.calls == 1, "refresh works after process restart");
            Provider denied = new Provider();
            Object resolution = new Object();
            denied.next.complete(AuthCoordinator.Result.resolution(resolution));
            AuthCoordinator waiting = new AuthCoordinator(denied, timers, 2000);
            check(waiting.request(null).get().code.equals("AUTH_REAUTH_REQUIRED"), "background consent is a safe result");
            check(waiting.resolution() == resolution && waiting.state().equals("reauth_required"), "resolution only available to UI adapter");
            Provider failing = new Provider();
            failing.next.completeExceptionally(new IllegalStateException("secret example"));
            check(new AuthCoordinator(failing, timers, 2000).request(null).get().code.equals("AUTH_UNAVAILABLE"), "raw provider exception never propagated");
            Provider late = new Provider();
            AuthCoordinator timeout = new AuthCoordinator(late, timers, 20);
            check(timeout.request(null).get(1, TimeUnit.SECONDS).code.equals("AUTH_TIMEOUT"), "provider bounded timeout");
            late.next.complete(AuthCoordinator.Result.token(TOKEN));
            check(!timeout.state().equals("ready"), "late token after timeout ignored");
            Provider disconnected = new Provider();
            AuthCoordinator invalidated = new AuthCoordinator(disconnected, timers, 2000);
            CompletableFuture<AuthCoordinator.Result> pending = invalidated.request(null);
            CompletableFuture<AuthCoordinator.Result> queuedRefresh = invalidated.request(TOKEN);
            invalidated.invalidate();
            disconnected.next.complete(AuthCoordinator.Result.token(TOKEN));
            check(pending.get().code.equals("AUTH_REAUTH_REQUIRED") && invalidated.state().equals("not_ready"), "disconnect invalidates in-flight token");
            check(queuedRefresh.get().code.equals("AUTH_REAUTH_REQUIRED") && disconnected.calls == 1, "disconnect cancels queued rejection too");
            Provider clearFail = new Provider();
            clearFail.clearing.complete(null);
            clearFail.clearing = new CompletableFuture<>();
            clearFail.clearing.completeExceptionally(new IOException("sensitive sdk error"));
            check(new AuthCoordinator(clearFail, timers, 2000).request(TOKEN).get().code.equals("AUTH_UNAVAILABLE") && clearFail.calls == 0, "clear failure never returns rejected token");
        } finally { timers.shutdownNow(); }
        String otherPin = "b".repeat(64);
        String pins = PIN + "," + otherPin;
        check(AuthPeerPolicy.configuredPins(pins), "two reviewed pins allowed");
        check(!AuthPeerPolicy.configuredPins(""), "empty pins fail closed");
        check(!AuthPeerPolicy.configuredPins(PIN + "," + PIN), "duplicate pins rejected");
        check(!AuthPeerPolicy.configuredPins(pins + "," + "c".repeat(64)), "at most two observed pins");
        check(AuthPeerPolicy.permitsPinnedOwners(10031, new String[]{"com.termux"}, new int[]{10031}, new String[][]{{PIN}}, pins), "phone signing family accepted");
        check(AuthPeerPolicy.permitsPinnedOwners(10252, new String[]{"com.termux", "com.termux.api", "com.termux.boot"}, new int[]{10252,10252,10252}, new String[][]{{otherPin},{otherPin},{otherPin}}, pins), "tablet signing family accepted");
        check(!AuthPeerPolicy.permitsPinnedOwners(10252, new String[]{"com.termux", "com.termux.api"}, new int[]{10252,10252}, new String[][]{{otherPin},{PIN}}, pins), "mixed allowed signing families still rejected");
        check(!AuthPeerPolicy.permitsPinnedOwners(10031, new String[]{"com.termux"}, new int[]{10031}, new String[][]{{"c".repeat(64)}}, pins), "unobserved signer rejected");
        check(AuthProtocol.validResponse(AuthProtocol.success(TOKEN).trim(), false), "canonical access response");
        check(AuthProtocol.validResponse(AuthProtocol.status("ready").trim(), true), "canonical status response");
        check(AuthProtocol.validResponse(AuthProtocol.error("AUTH_REAUTH_REQUIRED").trim(), false), "canonical reauth error");
        check(!AuthProtocol.validResponse(AuthProtocol.success(TOKEN).trim(), true), "status cannot disclose token");
        check(!AuthProtocol.validResponse(AuthProtocol.status("ready").trim(), false), "access cannot impersonate status");
        check(!AuthProtocol.validResponse(AuthProtocol.success(TOKEN).trim().replace("false", "true"), false), "no fabricated expiry");
        check(!AuthProtocol.validResponse(AuthProtocol.success(TOKEN).trim().replace("\"ok\":true", "\"ok\":true,\"ok\":true"), false), "duplicate response denied");
        check(!AuthProtocol.validResponse(AuthProtocol.error("AUTH_UNAVAILABLE").trim().replace("AUTH_UNAVAILABLE", "RAW_SDK_ERROR"), false), "unknown raw error denied");
        check(AuthProtocol.isProbe("{\"version\":1,\"op\":\"probe\"}"), "probe contract");
        check(!AuthProtocol.isProbe("{\"version\":1,\"op\":\"probe\",\"extra\":1}"), "probe exact keys");
        ScheduledExecutorService stopTimer = Executors.newSingleThreadScheduledExecutor();
        try {
            Provider stopProvider = new Provider();
            AuthCoordinator stopped = new AuthCoordinator(stopProvider, stopTimer, 1000);
            CompletableFuture<AuthCoordinator.Result> direct = stopped.request(null);
            CompletableFuture<AuthCoordinator.Result> queued = stopped.request(TOKEN);
            stopped.invalidate("AUTH_UNAVAILABLE");
            check(direct.get().code.equals("AUTH_UNAVAILABLE"), "local stop is not reconsent");
            check(queued.get().code.equals("AUTH_UNAVAILABLE"), "queued stop is not reconsent");
            stopProvider.next.complete(AuthCoordinator.Result.token(NEXT));
            check(stopped.state().equals("not_ready"), "late stop result cannot restore state");
        } finally { stopTimer.shutdownNow(); }
        ScheduledExecutorService budgetTimer = Executors.newSingleThreadScheduledExecutor();
        try {
            Provider budgetProvider = new Provider();
            AuthCoordinator bounded = new AuthCoordinator(budgetProvider, budgetTimer, 1000);
            bounded.request(null);
            CompletableFuture<AuthCoordinator.Result> queued = bounded.request(TOKEN, System.nanoTime() + 1_000_000L);
            Thread.sleep(5);
            budgetProvider.next.complete(AuthCoordinator.Result.token(TOKEN));
            check(queued.get().code.equals("AUTH_TIMEOUT"), "expired queued start budget");
            check(budgetProvider.calls == 1 && budgetProvider.cleared.isEmpty(), "no SDK flight after session budget expires");
        } finally { budgetTimer.shutdownNow(); }
        ScheduledExecutorService lockTimer = Executors.newSingleThreadScheduledExecutor();
        try {
            Provider lockProvider = new Provider();
            AuthCoordinator outside = new AuthCoordinator(lockProvider, lockTimer, 1000);
            final boolean[] held={true};
            outside.request(null).whenComplete((value,error)->held[0]=Thread.holdsLock(outside));
            lockProvider.next.complete(AuthCoordinator.Result.token(TOKEN));
            check(!held[0], "provider completion callbacks run outside coordinator lock");
            lockProvider.next=new CompletableFuture<>(); held[0]=true;
            outside.request(null).whenComplete((value,error)->held[0]=Thread.holdsLock(outside));
            outside.invalidate("AUTH_UNAVAILABLE");
            check(!held[0], "invalidation callbacks run outside coordinator lock");
        } finally { lockTimer.shutdownNow(); }
        System.out.println("NATIVE_AUTH_REGRESSION_PASS checks=" + checks);
    }
}
