package com.deathbook.fanqie.crack.noroot;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-shot unpacker for the no-root build.
 *
 * <p>The no-root APK carries three extra files:
 *
 * <ul>
 *   <li>{@code lib/armeabi-v7a/libfqgadget.so} -- the Frida gadget, loaded by
 *       name through the normal native library path;</li>
 *   <li>{@code lib/armeabi-v7a/libfqgadget.config.so} -- the gadget's config,
 *       found by the gadget next to itself. It names the script to run by
 *       absolute path, which is knowable and stable
 *       ({@code <app data>/files/fanqie_crack.js});</li>
 *   <li>{@code assets/fanqie_crack.js} -- the script itself. The gadget cannot
 *       read an APK asset, so something has to unpack it first.</li>
 * </ul>
 *
 * <p>That "something" is this class, called from the patched
 * {@code MuteApplicationStub.attachBaseContext} immediately before
 * {@code System.loadLibrary("fqgadget")}. It runs once per install: a
 * version-stamped marker in the files directory short-circuits subsequent
 * launches, so the normal startup path costs one {@code File.exists()}.
 *
 * <p>Deliberately dependency-free -- no Xposed, no Frida binding, nothing but
 * {@code java.io}. It ends up in a {@code classes22.dex} alongside the app's
 * own 21, and the only thing it has to get right is that the script file exists
 * and is complete before the gadget opens it.
 */
public final class Bootstrap {

    private static final String TAG = "FanQieCrack";

    /** Asset name of the Frida script. */
    private static final String ASSET_SCRIPT = "fanqie_crack.js";

    /** Where the gadget's config expects the script to be. */
    private static final String SCRIPT_NAME = "fanqie_crack.js";

    /**
     * Bumped whenever the packaged script changes, so a reinstalled build
     * actually replaces the unpacked copy instead of reusing a stale one.
     * Keep this in step with the version in scripts/repack_noroot.ps1.
     */
    private static final String PAYLOAD_VERSION = "1";

    private Bootstrap() {
    }

    public static void init(Context context) {
        try {
            File files = context.getFilesDir();
            if (files == null) {
                return;
            }
            File script = new File(files, SCRIPT_NAME);
            File marker = new File(files, SCRIPT_NAME + ".v" + PAYLOAD_VERSION);

            if (marker.exists() && script.exists() && script.length() > 0) {
                return;
            }

            // Write to a temporary name and rename, so a process killed
            // mid-unpack can never leave a truncated script behind for the
            // gadget to parse on the next launch.
            File tmp = new File(files, SCRIPT_NAME + ".tmp");
            copyAsset(context, ASSET_SCRIPT, tmp);
            if (script.exists() && !script.delete()) {
                // Non-fatal: FileOutputStream below truncates in place.
            }
            if (!tmp.renameTo(script)) {
                copyAsset(context, ASSET_SCRIPT, script);
                tmp.delete();
            }
            marker.createNewFile();
        } catch (Throwable t) {
            // Never let the bootstrap abort app startup. If it fails the
            // gadget simply finds no script and the app runs uncracked, which
            // is a far better outcome than a launch crash.
            log("bootstrap failed: " + t);
        }
    }

    private static void copyAsset(Context context, String assetName, File dest)
            throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetName);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        log("unpacked " + assetName + " -> " + dest.getAbsolutePath()
                + " (" + dest.length() + " bytes)");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
    }
}
