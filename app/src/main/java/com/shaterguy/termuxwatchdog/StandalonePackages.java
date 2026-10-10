package com.shaterguy.termuxwatchdog;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import java.lang.reflect.Method;

/** Android 16 IPackageManager shape, fail closed on unsupported platform/visibility. */
final class StandalonePackages implements PackageIdentityReader {
    private final Object packages;
    private final Method getOwners;
    private final Method getInfo;
    StandalonePackages() throws Exception {
        packages = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        getOwners = contract.getMethod("getPackagesForUid", int.class);
        getInfo = contract.getMethod("getPackageInfo", String.class, long.class, int.class);
    }
    public String[] owners(int uid) throws Exception { return (String[]) getOwners.invoke(packages, uid); }
    public PackageInfo info(String name) throws Exception {
        return (PackageInfo) getInfo.invoke(packages, name, (long) PackageManager.GET_SIGNING_CERTIFICATES, Process.myUid() / 100000);
    }
}
