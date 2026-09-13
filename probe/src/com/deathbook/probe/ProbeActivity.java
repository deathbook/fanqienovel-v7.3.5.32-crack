package com.deathbook.probe;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic: replicate exactly what the LSPosed manager does when it builds its
 * module list, and report what PackageManager actually returns.
 *
 * <p>LSPosed 1.8.6's manager decides "is this package a module" with
 *
 * <pre>
 *   for (PackageInfo pi : pm.getInstalledPackages(0x4C2280, false)) {
 *       Bundle b = pi.applicationInfo.metaData;
 *       if (b != null &amp;&amp; b.containsKey("xposedminversion")) -> it is a module
 *   }
 * </pre>
 *
 * <p>When a module does not appear in that list, the cause is either the module
 * APK (meta-data not visible to PackageManager) or the caller (packages not
 * visible to the manager). Those two look identical from the outside, and this
 * probe separates them by asking the same question from an ordinary app.
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "MODPROBE";

    private static final int LSPOSED_FLAGS = 0x4C2280; // what the manager passes

    private static final String TARGET = "com.deathbook.fanqie.crack";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "==== MODPROBE start (sdk=" + Build.VERSION.SDK_INT + ") ====");

        PackageManager pm = getPackageManager();

        // ---- 1. the exact scan LSPosed performs --------------------------
        List<String> found = new ArrayList<>();
        try {
            List<PackageInfo> all = pm.getInstalledPackages(LSPOSED_FLAGS);
            Log.i(TAG, "getInstalledPackages(0x" + Integer.toHexString(LSPOSED_FLAGS)
                    + ") returned " + all.size() + " packages");
            for (PackageInfo pi : all) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) {
                    continue;
                }
                Bundle md = ai.metaData;
                if (md != null && md.containsKey("xposedminversion")) {
                    found.add(pi.packageName);
                    Log.i(TAG, "  [MODULE] " + pi.packageName
                            + "  uid=" + ai.uid
                            + "  minVersion=" + md.get("xposedminversion")
                            + " (" + typeName(md.get("xposedminversion")) + ")"
                            + "  scope=" + md.get("xposedscope")
                            + " (" + typeName(md.get("xposedscope")) + ")");
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "  getInstalledPackages threw", t);
        }
        Log.i(TAG, "modules found by the LSPosed scan: " + found);

        // ---- 2. ask about the target package specifically ----------------
        for (int flags : new int[]{ LSPOSED_FLAGS, 0x80 /* GET_META_DATA */,
                                    0x80 | 0x2000 }) {
            try {
                PackageInfo pi = pm.getPackageInfo(TARGET, flags);
                ApplicationInfo ai = pi.applicationInfo;
                Bundle md = (ai == null) ? null : ai.metaData;
                Log.i(TAG, "getPackageInfo(" + TARGET + ", 0x"
                        + Integer.toHexString(flags) + ")"
                        + " -> ai=" + (ai != null)
                        + " metaData=" + (md == null ? "null" : "size=" + md.size()));
                if (md != null) {
                    for (String key : md.keySet()) {
                        Object v = md.get(key);
                        Log.i(TAG, "     " + key + " = " + v + " (" + typeName(v) + ")");
                    }
                }
            } catch (Throwable t) {
                Log.i(TAG, "getPackageInfo(" + TARGET + ", 0x"
                        + Integer.toHexString(flags) + ") threw " + t);
            }
        }

        // ---- 3. every signature-permission a visibility check cares about
        String[] queries = {
                "android.permission.QUERY_ALL_PACKAGES",
                "android.permission.GET_PACKAGE_SIZE",
        };
        for (String p : queries) {
            try {
                Log.i(TAG, "checkPermission " + p + " = "
                        + pm.checkPermission(p, getPackageName()));
            } catch (Throwable t) {
                Log.i(TAG, "checkPermission " + p + " threw " + t);
            }
        }

        Log.i(TAG, "==== MODPROBE end ====");
        finish();
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
