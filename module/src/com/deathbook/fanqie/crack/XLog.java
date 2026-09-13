package com.deathbook.fanqie.crack;

import android.util.Log;

/**
 * Logging that lands in two places at once.
 *
 * <p>{@code XposedBridge.log} is what the LSPosed manager's log tab shows, and
 * {@code android.util.Log} is what {@code adb logcat -s FanQieCrack} shows. The
 * module needs both because the evidence for the write-up is captured with
 * logcat, while a human debugging the hook uses the manager UI.
 *
 * <p>Everything is routed through here rather than calling the framework
 * directly so that the tag can never drift between the two sinks.
 */
public final class XLog {

    private XLog() {
    }

    public static void i(String message) {
        Log.i(Const.TAG, message);
        de.robv.android.xposed.XposedBridge.log(Const.TAG + ": " + message);
    }

    public static void w(String message) {
        Log.w(Const.TAG, message);
        de.robv.android.xposed.XposedBridge.log(Const.TAG + ": [W] " + message);
    }

    /**
     * A hook that failed to install is not fatal -- the target may simply not
     * have that class in this build. It is, however, always worth recording,
     * because a silently missing hook looks exactly like a working hook.
     */
    public static void hookFailed(String what, Throwable t) {
        String msg = "hook miss: " + what + " -- " + t;
        Log.w(Const.TAG, msg);
        de.robv.android.xposed.XposedBridge.log(Const.TAG + ": [MISS] " + what + " -- " + t);
    }

    public static void hookOk(String what) {
        de.robv.android.xposed.XposedBridge.log(Const.TAG + ": [OK] " + what);
    }
}
