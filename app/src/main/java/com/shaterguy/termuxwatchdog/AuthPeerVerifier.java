package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

/** Fresh installed-package metadata; a public signing key is not publisher authentication. */
final class AuthPeerVerifier {
    private final PackageIdentityReader packages;
    AuthPeerVerifier(Context context) {
        PackageManager pm = context.getPackageManager();
        packages = new PackageIdentityReader() {
            public String[] owners(int uid) { return pm.getPackagesForUid(uid); }
            public PackageInfo info(String name) throws Exception {
                return pm.getPackageInfo(name, Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            }
        };
    }
    AuthPeerVerifier(PackageIdentityReader packages) { this.packages = packages; }
    boolean configured() { return AuthPeerPolicy.configuredPins(BuildConfig.TERMUX_SIGNER_SHA256); }
    boolean verify(int peerUid) {
        if (!configured()) return false;
        try {
            String[] owners = packages.owners(peerUid);
            if (owners == null || owners.length == 0 || owners.length > 7) return false;
            int[] uids = new int[owners.length];
            String[][] signerDigests = new String[owners.length][];
            for (int owner = 0; owner < owners.length; owner++) {
                PackageInfo info = packages.info(owners[owner]);
                if (info.applicationInfo == null || !owners[owner].equals(info.packageName)) return false;
                uids[owner] = info.applicationInfo.uid;
                signerDigests[owner] = signers(info);
            }
            return AuthPeerPolicy.permitsPinnedOwners(peerUid, owners, uids, signerDigests, BuildConfig.TERMUX_SIGNER_SHA256);
        } catch (Exception ignored) { return false; }
    }
    int verifiedWatchdogUid() {
        try {
            PackageInfo info = packages.info(BridgeHandshake.PACKAGE);
            int uid = info != null && info.applicationInfo != null ? info.applicationInfo.uid : -1;
            return verifyWatchdog(uid) ? uid : -1;
        } catch (Exception ignored) { return -1; }
    }
    boolean verifyWatchdog(int peerUid) {
        try {
            PackageInfo info = packages.info(BridgeHandshake.PACKAGE);
            return info != null && info.applicationInfo != null && BridgeHandshake.PACKAGE.equals(info.packageName)
                    && BridgeHandshake.permitsWatchdog(peerUid, info.applicationInfo.uid, packages.owners(peerUid), signers(info));
        } catch (Exception ignored) { return false; }
    }
    private static String[] signers(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return null;
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length != 1) return null;
        String[] result = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            result[i] = hex.toString();
        }
        return result;
    }

}
