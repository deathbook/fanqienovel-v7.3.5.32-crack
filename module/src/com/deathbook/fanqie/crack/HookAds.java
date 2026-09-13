package com.deathbook.fanqie.crack;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Removing advertising.
 *
 * <p>FanQie funnels every "should I show an ad here" question through
 * {@code NsAdDependImpl}, a ~120-method implementation of {@code NsAdApi} that
 * the reader, the audio player, the 短剧 player and the splash screen all reach
 * via the {@code NsAdApi.IMPL} service locator. The privilege-layer hooks in
 * {@link HookPrivileges} already answer the underlying questions
 * ({@code hasNoAdPrivilege()}, {@code isNoAd(id)}, {@code isBookAdFree(id)}),
 * but three of this class's own predicates are computed locally and never
 * consult the privilege map at all -- {@code isReaderAdFree()} in particular
 * walks the current book's own ad configuration.
 *
 * <p>Those three are forced here. {@code readerIsFreeDownload()} and
 * {@code readerIsAdFree()} are left to {@link HookPrivileges}, since both are
 * derived from {@code PrivilegeManager.isVip()} which is already true.
 *
 * <p>{@code isReaderAdFree()} returns {@code int}, not {@code boolean} -- it is
 * used as a 0/1 flag ({@code readerIsAdFree()} is literally
 * {@code isReaderAdFree() != 0}), so it is forced to 1 rather than to a
 * boolean.
 */
public final class HookAds {

    private HookAds() {
    }

    public static void install(ClassLoader cl) {
        Class<?> ad = Hooks.findClassOrNull(Const.CLS_NS_AD_DEPEND_IMPL, cl);
        if (ad == null) {
            XLog.w("NsAdDependImpl not found -- local ad predicates not hooked");
        } else {
            Hooks.forceResult(ad, "isReaderAdFree", Integer.valueOf(1));
            Hooks.forceTrue(ad, "readerIsAdFree");
            Hooks.forceTrue(ad, "audioIsAdFree", String.class);
            Hooks.forceFalse(ad, "readerHasLeftAdForFreeChapter");
            Hooks.forceFalse(ad, "enableReaderNaturalFlowBanner");
            Hooks.forceFalse(ad, "isLocalBookShowChapterFrontAd");
            Hooks.forceFalse(ad, "isLocalBookShowChapterMiddleAd");
            Hooks.forceResult(ad, "getMiddleAdCount", Integer.valueOf(0));

            // Guarded separately: passing a null Class into findAndHookMethod
            // would throw and be mis-reported as a signature mismatch.
            Class<?> page = Hooks.findClassOrNull(
                    "com.dragon.reader.lib.parserlevel.model.page.IDragonPage", cl);
            if (page != null) {
                Hooks.forceFalse(ad, "isReaderAdPageData", page);
            }

            // "keepInspireEntrance" is what asks the user to watch a rewarded
            // video to keep a perk alive. With the perks already permanent there
            // is nothing to keep, and the entrance should not be reserved.
            // Signature is keepInspireEntrance(String) -- checked against the
            // dex after an earlier guess at the parameter type missed.
            Hooks.raw(ad, "keepInspireEntrance", String.class, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
        }

        // --- Book-level ad flags -----------------------------------------
        // Both are declared native on the Java side and implemented in
        // libdragoncore.so; forcing the Java entry point is the only thing this
        // module can do without touching the shipped .so.
        //
        // They do not share a signature: BookInfo.isAdFree() is no-arg while
        // SaaSBookInfo.isAdFree(boolean) takes the caller's own ad-free hint.
        Hooks.forceTrueIfPresent(cl, Const.CLS_BOOK_INFO, "isAdFree");
        Hooks.forceTrueIfPresent(cl, Const.CLS_SAAS_BOOK_INFO, "isAdFree", boolean.class);
    }
}
