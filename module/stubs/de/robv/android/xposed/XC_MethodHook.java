package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * COMPILE-TIME STUB ONLY -- see {@link XposedBridge}.
 *
 * <p>{@code beforeHookedMethod}/{@code afterHookedMethod} are {@code public}
 * both here and in the module's own subclasses. The framework dex on the test
 * device declares them public (ACC_PUBLIC, not ACC_PROTECTED); overriding a
 * public method as public is always legal, and so is overriding a protected one
 * as public, so this spelling is correct against either build.
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    public void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    public void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam extends de.robv.android.xposed.callbacks.XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public boolean returnEarly;
        private Object result = null;
        private Throwable throwable = null;

        public Object getResult() {
            return this.result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return this.throwable;
        }

        public boolean hasThrowable() {
            return this.throwable != null;
        }

        public void setThrowable(Throwable t) {
            this.throwable = t;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (this.throwable != null) {
                throw this.throwable;
            }
            return this.result;
        }
    }

    /** Non-static inner class in the real API -- it carries {@code this$0}. */
    public class Unhook {
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
        }
    }
}
