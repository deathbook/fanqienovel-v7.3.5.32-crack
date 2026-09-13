package de.robv.android.xposed;

/** COMPILE-TIME STUB ONLY -- see {@link XposedBridge}. */
public class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass,
                                                                     String methodName,
                                                                     XC_MethodHook callback) {
        return null;
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                                          XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod,
                                                  XC_MethodHook callback) {
        return null;
    }

    public static void unhookMethod(java.lang.reflect.Member hookMethod, XC_MethodHook callback) {
    }

    public static Object invokeOriginalMethod(java.lang.reflect.Member method, Object thisObject,
                                              Object[] args) throws Throwable {
        return null;
    }
}
