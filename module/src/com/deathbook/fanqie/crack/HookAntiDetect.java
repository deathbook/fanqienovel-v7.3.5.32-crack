package com.deathbook.fanqie.crack;

/**
 * Keeping the device fingerprint clean.
 *
 * <p>FanQie's sample carries the ByteDance and Tencent SDKs, and both of them
 * collect "is there a hooking framework on this device" as a device attribute:
 * {@code com.bytedance.android.standard.tools.device.DeviceUtils.isInstallXposed()}
 * and the older {@code com.bytedance.common.utility.DeviceUtils} twin are called
 * during the device-registration handshake, and Tinker's crash handler asks the
 * same question to decide whether a crash is attributable to Xposed.
 *
 * <p>Neither is a kill switch -- decompiling the callers shows the result is
 * only ever put into a report payload. That is precisely why it is worth
 * answering "no": the cracked client is otherwise indistinguishable from a
 * normal one in the reader itself, but the device fingerprint would say
 * "Xposed present" on every launch, which is the cheapest possible signal to
 * fingerprint a cracked install by.
 *
 * <p>This mirrors the reporting-consistency work in the Lesson-1 answer: the
 * point is not to defeat an active detector, it is to stop the cracked build
 * from announcing itself.
 */
public final class HookAntiDetect {

    private HookAntiDetect() {
    }

    public static void install(ClassLoader cl) {
        forceFalseIfPresent(cl, Const.CLS_DEVICE_UTILS_BD, "isInstallXposed");
        forceFalseIfPresent(cl, Const.CLS_DEVICE_UTILS_COMMON, "isInstallXposed");

        Class<?> tinker = Hooks.findClassOrNull(Const.CLS_TINKER_UTILS, cl);
        if (tinker != null) {
            Hooks.forceFalse(tinker, "isXposedExists", Throwable.class);
        }
    }

    private static void forceFalseIfPresent(ClassLoader cl, String className, String method) {
        Class<?> c = Hooks.findClassOrNull(className, cl);
        if (c != null) {
            Hooks.forceFalse(c, method);
        }
    }
}
