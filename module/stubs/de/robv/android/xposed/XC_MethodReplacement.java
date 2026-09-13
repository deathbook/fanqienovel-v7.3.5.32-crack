package de.robv.android.xposed;

/** COMPILE-TIME STUB ONLY -- see {@link XposedBridge}. */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public XC_MethodReplacement() {
    }

    public XC_MethodReplacement(int priority) {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    // Real flags are ACC_PUBLIC|ACC_FINAL; they cannot narrow the parent's
    // visibility, so widening them here would have been a compile error.
    public final void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}
