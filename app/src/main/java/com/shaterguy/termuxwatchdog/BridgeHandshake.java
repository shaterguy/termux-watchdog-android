package com.shaterguy.termuxwatchdog;

/** No credentials, Android dependencies, or persistent trust decisions. */
final class BridgeHandshake {
    static final String PACKAGE = "com.shaterguy.termuxwatchdog";
    static final String RECEIVER = PACKAGE + ".NativeAuthReceiver";
    static final String ACTION = PACKAGE + ".AUTH_BRIDGE_V1";
    static final String ENDPOINT = PACKAGE + ".IAuthBridge.v1";
    static final String CALLBACK = PACKAGE + ".IAuthBootstrap.v1";
    static final String WATCHDOG_SHA256 = "31b19b19e934e1d32b570160e4252aef7b27935a45bb3a6ea56cc5f18f395a54";
    static final int VERSION = 1;
    static final int TX_HELLO = 1;
    static final int TX_REQUEST = 2;
    static final int TX_FETCH = 3;
    static final int TX_CLOSE = 4;
    static final int CALLBACK_OFFER = 1;
    static final int CALLBACK_READY = 2;
    static final int MAX_REQUEST_PARCEL_BYTES = 17408;
    static final long PROVIDER_BUDGET_MS = 5500;
    static final int MAX_PARCEL_BYTES = 1024;
    static final long SERVER_DEADLINE_MS = 7000;
    private final String challenge;
    private final long deadline;
    private boolean used;
    BridgeHandshake(String challenge, long deadline) {
        if (!validChallenge(challenge)) throw new IllegalArgumentException("BAD_REQUEST");
        this.challenge = challenge; this.deadline = deadline;
    }
    static boolean validChallenge(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    synchronized boolean claim(int version, String value, long now) {
        if (used) return false;
        used = true;
        return version == VERSION && challenge.equals(value) && now < deadline;
    }
    synchronized void close() { used = true; }
    static boolean permitsWatchdog(int peerUid, int installedUid, String[] owners, String[] signers) {
        return peerUid >= 10000 && installedUid == peerUid && owners != null && owners.length == 1
                && PACKAGE.equals(owners[0]) && signers != null && signers.length == 1 && WATCHDOG_SHA256.equals(signers[0]);
    }
}
