package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * COMPILE-TIME STUB ONLY -- see {@link XposedBridge}.
 *
 * <p>Every descriptor here was read out of the real framework dex
 * ({@code /data/adb/modules/zygisk_lsposed/framework/lspd.dex}) rather than
 * recalled from memory, and {@code scripts/verify_xposed_api.py} re-checks the
 * built module's references against that same dex on every build.
 *
 * <p>That check exists because the first build of this module got it wrong in a
 * way nothing else would have caught: {@code findAndHookMethod} returns
 * {@code XC_MethodHook.Unhook}, and declaring it {@code void} in the stub
 * compiled perfectly, installed cleanly, loaded cleanly, and then failed every
 * single hook at runtime with
 * {@code NoSuchMethodError: No static method findAndHookMethod(Ljava/lang/Class;
 * Ljava/lang/String;[Ljava/lang/Object;)V}. A module that attaches and then
 * quietly hooks nothing is the worst possible failure mode, so the build now
 * refuses to produce one.
 */
public final class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static void setIntField(Object obj, String fieldName, int value) {
    }

    public static long getLongField(Object obj, String fieldName) {
        return 0L;
    }

    public static void setLongField(Object obj, String fieldName, long value) {
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
    }

    public static Method findMethodExact(Class<?> clazz, String methodName,
                                         Object... parameterTypes) {
        return null;
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        return null;
    }
}
