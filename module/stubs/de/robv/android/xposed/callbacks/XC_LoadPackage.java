package de.robv.android.xposed.callbacks;

/** COMPILE-TIME STUB ONLY -- see {@link de.robv.android.xposed.XposedBridge}. */
public abstract class XC_LoadPackage extends XCallback {

    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
