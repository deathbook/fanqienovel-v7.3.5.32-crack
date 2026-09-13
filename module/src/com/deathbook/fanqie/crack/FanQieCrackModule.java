package com.deathbook.fanqie.crack;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄免费小说 (FanQie Novel) Lesson-2 crack -- LSPosed / Xposed entry point.
 *
 * <p>Loaded from {@code assets/xposed_init}. Scoped to
 * {@link Const#TARGET_PACKAGE}; every other process is ignored, so the module
 * cannot affect anything but the target.
 *
 * <p><b>What it does</b>
 * <ol>
 *   <li><b>破解会员</b> -- {@link HookPrivileges} + {@link HookVip} force the
 *       membership predicates at both the privilege layer and the two
 *       service-locator seams.</li>
 *   <li><b>去除广告</b> -- {@link HookPrivileges} grants the ad-free
 *       privileges; {@link HookAds} additionally forces the three ad
 *       predicates {@code NsAdDependImpl} computes locally, plus the native
 *       per-book {@code isAdFree()} flags.</li>
 *   <li><b>无限时长自动阅读</b> -- {@link HookPrivileges} grants the
 *       {@code AutoPage} privilege and makes
 *       {@code PrivilegeInfoModel.isForever()} / {@code leftSecondsAfterInit()}
 *       report a permanent entitlement, which is the exact expression the
 *       auto-read controller uses to size its budget. {@link HookAutoRead}
 *       then closes the two places that interrupt the reader with a rewarded
 *       video when that budget looks exhausted.</li>
 * </ol>
 *
 * <p><b>Why the hooks are at the Java boundary.</b>
 * The interesting predicate -- {@code PrivilegeManager.hasPrivilege(String)} --
 * is {@code native}, implemented in
 * {@code lib/armeabi-v7a/libdragoncore.so}. That is the sample's anti-tamper
 * move: an smali patch of {@code hasAutoPagePrivilege()} changes nothing,
 * because the answer is produced in C++. Replacing the ArtMethod's entry point
 * works regardless, and unlike a native hook it is immune to the library being
 * rebuilt, packed, or replaced by a different ABI. It is also the only option
 * that works on this sample's 32-bit ARM libraries while running under an
 * emulator's ARM translation layer.
 *
 * <p>Each hook group is independently failure-tolerant: a class that is absent
 * in some future build logs {@code [MISS]} and the rest still installs. The
 * summary line at the end reports how many hooks landed, so "the module
 * loaded" and "the module works" stay distinguishable.
 */
public final class FanQieCrackModule implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !Const.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        long startedAt = System.nanoTime();
        XLog.i("================ FanQieNovelCrack attached ================");
        XLog.i("target=" + lpparam.packageName
                + "  module-build=" + Const.TARGET_VERSION);

        ClassLoader cl = lpparam.classLoader;

        // Groups are ordered cheapest-and-most-load-bearing first, so that if a
        // later group throws the important half of the crack is already in.
        safeInstall("privileges", cl);
        safeInstall("vip", cl);
        // After "privileges": zh6.e.b() reads the privilege map, so the gate is
        // only meaningful once those hooks are installed.
        safeInstall("auto-read", cl);
        safeInstall("ads", cl);
        safeInstall("anti-detect", cl);

        long micros = (System.nanoTime() - startedAt) / 1000L;
        XLog.i("SELFTEST hooks_installed=" + Hooks.installedCount()
                + " hooks_missed=" + Hooks.missedCount()
                + " took_us=" + micros);
        // Reported separately from the install counts: a hook can install and
        // never fire, and only the probe counters can tell those apart.
        Probe.summary("at-attach");
        XLog.i("==========================================================");
    }

    /**
     * One group failing must not take the others with it -- a partially hooked
     * app is still usable, an exception escaping handleLoadPackage is not.
     */
    private static void safeInstall(String group, ClassLoader cl) {
        try {
            if ("privileges".equals(group)) {
                HookPrivileges.install(cl);
            } else if ("vip".equals(group)) {
                HookVip.install(cl);
            } else if ("auto-read".equals(group)) {
                // After "privileges": the gate reads the privilege map, so it is
                // only meaningful once the privilege hooks are in.
                HookAutoRead.install(cl);
            } else if ("ads".equals(group)) {
                HookAds.install(cl);
            } else if ("anti-detect".equals(group)) {
                HookAntiDetect.install(cl);
            }
        } catch (Throwable t) {
            XLog.w("group '" + group + "' failed: " + t);
        }
    }
}
