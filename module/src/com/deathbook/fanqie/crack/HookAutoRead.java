package com.deathbook.fanqie.crack;

/**
 * The auto-read ad gate -- 无限时长自动阅读, part two.
 *
 * <p>Forcing the {@code AutoPage} privilege to "forever" makes the app believe
 * the user has unlimited auto-read time, and that is what the counter and the
 * accumulate controller read. It is <em>not</em> the only place that decides
 * whether to interrupt the reader with a rewarded video, and missing the other
 * one is why a first pass at this crack still showed "看视频继续自动阅读" once
 * the time ran out on a real device.
 *
 * <p>Two sites matter, and they were found by tracing the string
 * {@code 自动阅读权益已到期} ("auto-read privilege expired") to its emitter:
 *
 * <ol>
 *   <li>{@code com.dragon.read.reader.moduleconfig.TemporaryReaderLifecycleListener$b}
 *       -- on the {@code action_auto_read_changed} broadcast, computes
 *       {@code expired = !zh6.e.b()}, then calls {@code zh6.e.c(2, ...)} to show
 *       the rewarded video.</li>
 *   <li>{@code zh6.m.subscribe} -- when there is no privilege, grants one by
 *       {@code zh6.e.a(4, ...)}, again behind a rewarded video.</li>
 * </ol>
 *
 * <p>Both sites consult the same two escape hatches before doing anything, and
 * both are ordinary app feature switches:
 *
 * <pre>
 *   if (NsAdApi.IMPL.inspireAdDisable()) { ...no ad... }
 *   if (NsMineDepend.IMPL.isGoogleMarket()) { ...no ad... }
 * </pre>
 *
 * <p>{@code inspireAdDisable()} reads a server AB flag
 * ({@code AutoReadingShowAd.inspireAdDisable}) and is the intended way to turn
 * this ad off for a market or a cohort -- exactly the semantics wanted here, and
 * unlike {@code isGoogleMarket()} it does not change which features the app
 * thinks it has.
 *
 * <p>{@link #hookGate} additionally forces {@code zh6.e.b()} itself, so the
 * decision is closed at the source as well as at both consumers. Belt and
 * braces on purpose: the cost is one extra hook, and the failure mode being
 * defended against -- auto-read stopping mid-chapter to demand an ad -- is the
 * one that made it out of the first round of testing.
 */
public final class HookAutoRead {

    /** The privilege helper; {@code zh6.e.b()} is the "is auto-read available" gate. */
    private static final String CLS_AUTO_READ_HELPER = "zh6.e";

    /** {@code NsAdApi.IMPL}'s implementation -- owns inspireAdDisable(). */
    private static final String CLS_NS_AD_IMPL = "com.dragon.read.component.biz.impl.NsAdImpl";

    private HookAutoRead() {
    }

    public static void install(ClassLoader cl) {
        hookGate(cl);
        hookInspireAdSwitch(cl);
    }

    /**
     * {@code static boolean zh6.e.b()} -- the single source of truth for
     * "does this user still have auto-read time", read by the expiry listener
     * and by the privilege-granting path alike.
     *
     * <pre>
     *   PrivilegeInfoModel p = ...getPrivilege("6836977122288866051");
     *   if (p == null) return false;
     *   if (!p.isForever() &amp;&amp; p.leftSecondsAfterInit(null) &lt;= 0) return false;
     *   return true;
     * </pre>
     */
    private static void hookGate(ClassLoader cl) {
        Class<?> helper = Hooks.findClassOrNull(CLS_AUTO_READ_HELPER, cl);
        if (helper == null) {
            XLog.w("zh6.e not found -- auto-read gate not hooked");
            return;
        }
        Hooks.raw(helper, "b", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
                Probe.important("zh6.e.b", "自动阅读权益可用 -> true");
            }
        });
    }

    /**
     * {@code boolean NsAdImpl.inspireAdDisable()} -- the app's own switch for
     * "do not show the auto-read rewarded video", read by both ad sites.
     */
    private static void hookInspireAdSwitch(ClassLoader cl) {
        Class<?> adImpl = Hooks.findClassOrNull(CLS_NS_AD_IMPL, cl);
        if (adImpl == null) {
            XLog.w("NsAdImpl not found -- inspireAdDisable not hooked");
            return;
        }
        Hooks.raw(adImpl, "inspireAdDisable", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
                Probe.important("NsAdImpl.inspireAdDisable",
                        "关闭自动阅读激励视频 -> true");
            }
        });
    }
}
