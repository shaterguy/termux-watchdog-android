package com.shaterguy.termuxwatchdog;

public final class BridgeHandshakeRegression {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        String nonce = "a".repeat(64);
        check(BridgeHandshake.validChallenge(nonce));
        check(!BridgeHandshake.validChallenge("A".repeat(64)));
        check(!BridgeHandshake.validChallenge("a".repeat(63)));
        check(!BridgeHandshake.validChallenge(null));
        BridgeHandshake session = new BridgeHandshake(nonce, 7000);
        check(!session.claim(2, nonce, 1));
        check(!session.claim(1, nonce, 1)); // malformed attempt consumes session
        session = new BridgeHandshake(nonce, 7000);
        check(!session.claim(1, "b".repeat(64), 1));
        check(!new BridgeHandshake(nonce, 7000).claim(1, nonce, 7000));
        check(!new BridgeHandshake(nonce, 7000).claim(1, nonce, 8000));
        session = new BridgeHandshake(nonce, 7000);
        check(session.claim(1, nonce, 6999));
        check(!session.claim(1, nonce, 6999));
        session = new BridgeHandshake(nonce, 7000); session.close();
        check(!session.claim(1, nonce, 1));
        check(BridgeHandshake.permitsWatchdog(10123, 10123, new String[]{BridgeHandshake.PACKAGE}, new String[]{BridgeHandshake.WATCHDOG_SHA256}));
        check(!BridgeHandshake.permitsWatchdog(10123, 10124, new String[]{BridgeHandshake.PACKAGE}, new String[]{BridgeHandshake.WATCHDOG_SHA256}));
        check(!BridgeHandshake.permitsWatchdog(10123, 10123, new String[]{BridgeHandshake.PACKAGE,"other"}, new String[]{BridgeHandshake.WATCHDOG_SHA256}));
        check(!BridgeHandshake.permitsWatchdog(10123, 10123, new String[]{BridgeHandshake.PACKAGE}, new String[]{"b".repeat(64)}));
        check(!BridgeHandshake.permitsWatchdog(10123, 10123, new String[]{BridgeHandshake.PACKAGE}, new String[]{BridgeHandshake.WATCHDOG_SHA256,BridgeHandshake.WATCHDOG_SHA256}));
        System.out.println("Bridge handshake checks passed: " + checks);
    }
}
