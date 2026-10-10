package com.shaterguy.termuxwatchdog;
import android.content.pm.PackageInfo;
interface PackageIdentityReader {
    String[] owners(int uid) throws Exception;
    PackageInfo info(String name) throws Exception;
}
