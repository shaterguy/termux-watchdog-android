package com.shaterguy.termuxwatchdog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Trust is explicitly configured; never infer a trusted certificate on first connection. */
final class AuthPeerPolicy {
    static final String TERMUX_PACKAGE = "com.termux";
    private static final Set<String> KNOWN_OWNERS = new HashSet<>(Arrays.asList(
            "com.termux", "com.termux.api", "com.termux.boot", "com.termux.window",
            "com.termux.styling", "com.termux.tasker", "com.termux.widget"));
    static boolean permitsOwners(int peerUid, String[] owners, int[] uids, String[][] signers, String pin) {
        if (!configured(pin) || peerUid < 10_000 || owners == null || uids == null || signers == null
                || owners.length == 0 || owners.length > KNOWN_OWNERS.size()
                || owners.length != uids.length || owners.length != signers.length) return false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < owners.length; i++) {
            if (!KNOWN_OWNERS.contains(owners[i]) || !seen.add(owners[i]) || uids[i] != peerUid
                    || signers[i] == null || signers[i].length != 1 || !pin.equals(signers[i][0])) return false;
        }
        return seen.contains(TERMUX_PACKAGE);
    }
    static boolean configuredPins(String csv) {
        if (csv == null) return false;
        String[] pins = csv.split(",", -1);
        if (pins.length < 1 || pins.length > 2) return false;
        Set<String> unique = new HashSet<>();
        for (String pin : pins) if (!configured(pin) || !unique.add(pin)) return false;
        return true;
    }
    static boolean permitsPinnedOwners(int peerUid, String[] owners, int[] uids, String[][] signers, String csv) {
        if (!configuredPins(csv) || owners == null || signers == null || owners.length != signers.length) return false;
        for (int i = 0; i < owners.length; i++) {
            if (TERMUX_PACKAGE.equals(owners[i]) && signers[i] != null && signers[i].length == 1) {
                for (String pin : csv.split(",")) {
                    if (pin.equals(signers[i][0])) return permitsOwners(peerUid, owners, uids, signers, pin);
                }
                return false;
            }
        }
        return false;
    }
    static boolean configured(String pin) { return pin != null && pin.matches("[0-9a-f]{64}"); }
    static boolean permits(int peerUid, int installedUid, String installedPackage, String[] currentSigners, String pin) {
        return configured(pin) && peerUid >= 10_000 && peerUid == installedUid
                && TERMUX_PACKAGE.equals(installedPackage) && currentSigners != null
                && currentSigners.length == 1 && pin.equals(currentSigners[0]);
    }
}
