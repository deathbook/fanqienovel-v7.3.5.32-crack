package com.deathbook.fanqie.crack;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Install-hook helpers that fail soft and count their successes.
 *
 * <p>Every hook in this module goes through here. That gives three properties
 * the write-up depends on:
 *
 * <ul>
 *   <li>a class or method that does not exist in a given build produces a
 *       {@code [MISS]} line instead of a crash or a silent no-op;</li>
 *   <li>the number of successfully installed hooks is known at the end, so
 *       "the module loaded" and "the module works" stop being the same claim;</li>
 *   <li>all hooks share one implementation of the "replace the return value"
 *       idiom, so behaviour cannot drift between them.</li>
 * </ul>
 */
public final class Hooks {

    private static int installed = 0;
    private static int missed = 0;

    private Hooks() {
    }

    public static int installedCount() {
        return installed;
    }

    public static int missedCount() {
        return missed;
    }

    /**
     * Force a method to always report {@code value}, whatever the original
     * implementation -- including a {@code native} one -- would have returned.
     *
     * <p>FanQie makes the interesting privilege decisions native on purpose, so
     * that a smali patch cannot reach them. Replacing the method's entry point
     * works for native methods too: LSPosed rewrites the ArtMethod so the JNI
     * stub is never entered, which is why this module needs no ABI-specific
     * code even though the sample ships armeabi-v7a only.
     */
    public static void forceResult(Class<?> clazz, String method, final Object value,
                                   Object... paramTypes) {
        final String what = clazz.getName() + "." + method;
        try {
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            };
            XposedHelpers.findAndHookMethod(clazz, method, args);
            installed++;
            XLog.hookOk(what + " -> " + value);
        } catch (Throwable t) {
            missed++;
            XLog.hookFailed(what, t);
        }
    }

    public static void forceBoolean(Class<?> clazz, String method, boolean value,
                                    Object... paramTypes) {
        forceResult(clazz, method, Boolean.valueOf(value), paramTypes);
    }

    public static void forceTrue(Class<?> clazz, String method, Object... paramTypes) {
        forceBoolean(clazz, method, true, paramTypes);
    }

    public static void forceFalse(Class<?> clazz, String method, Object... paramTypes) {
        forceBoolean(clazz, method, false, paramTypes);
    }

    /** Install a raw hook; returns true on success so callers can chain. */
    public static boolean raw(Class<?> clazz, String method, Object... paramTypesAndCallback) {
        String what = clazz.getName() + "." + method;
        try {
            XposedHelpers.findAndHookMethod(clazz, method, paramTypesAndCallback);
            installed++;
            XLog.hookOk(what);
            return true;
        } catch (Throwable t) {
            missed++;
            XLog.hookFailed(what, t);
            return false;
        }
    }

    /**
     * Look a class up by name, tolerating absence. {@code XposedHelpers.findClass}
     * throws when the class is missing, and an unguarded lookup would abort the
     * rest of {@code handleLoadPackage}.
     */
    public static Class<?> findClassOrNull(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve a class by name and force one of its methods, or log and give up.
     *
     * <p>Same effect as {@code findClassOrNull} + {@code forceTrue}, but keeps
     * the "class may be absent" case attached to the hook it belongs to instead
     * of spreading null checks across every caller.
     */
    public static void forceTrueIfPresent(ClassLoader cl, String className, String method,
                                          Object... paramTypes) {
        Class<?> c = findClassOrNull(className, cl);
        if (c == null) {
            missed++;
            XLog.hookFailed(className + "." + method, new NoClassDefFoundError(className));
            return;
        }
        forceTrue(c, method, paramTypes);
    }

    /** Hook every method with this name, ignoring ones that are not there. */
    public static void allMethods(Class<?> clazz, String method, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(clazz, method, callback);
            installed++;
            XLog.hookOk(clazz.getName() + "." + method + " (all)");
        } catch (Throwable t) {
            missed++;
            XLog.hookFailed(clazz.getName() + "." + method + " (all)", t);
        }
    }
}
