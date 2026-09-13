package com.deathbook.fanqie.crack;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The privilege layer -- the single pivot the whole crack hangs off.
 *
 * <p>FanQie models every entitlement as a {@code PrivilegeInfoModel} held in a
 * {@code HashMap<String, PrivilegeInfoModel>} owned by {@code PrivilegeManager},
 * keyed by 19-digit snowflake ids. On top of that map sit ~18 convenience
 * helpers ({@code hasAutoPagePrivilege()}, {@code hasNoAdPrivilege()}, ...),
 * and on top of those sit the two interfaces the rest of the app actually
 * calls: {@code IPrivilegeManager} (impl {@code PrivilegeManager}) and
 * {@code NsVipApi} (impl {@code NsVipImpl}).
 *
 * <p>The convenience helpers delegate to {@code hasPrivilege(String)}, which is
 * declared {@code native} and satisfied from
 * {@code lib/armeabi-v7a/libdragoncore.so}. That is the anti-tamper design of
 * this sample: patch the smali of {@code hasAutoPagePrivilege()} and you get
 * nowhere, because the answer comes from C++.
 *
 * <p>This class does not fight that. It sits one frame above: force
 * {@code hasPrivilege} to say yes for the ids the module grants, synthesise a
 * forever-lived {@code PrivilegeInfoModel} whenever {@code getPrivilege} would
 * have returned null, and make the two model predicates that everything else
 * compares against ({@code isForever}, {@code leftSecondsAfterInit}) report a
 * permanent entitlement.
 *
 * <p>Two deliberate exclusions keep this from breaking the app:
 *
 * <ul>
 *   <li>{@code CommentForbidden} is a <em>negative</em> privilege. A blanket
 *       {@code hasPrivilege -> true} would confiscate the user's comment box.
 *       Only ids in {@link Const#UNLOCKED} are granted.</li>
 *   <li>Unknown ids fall through to the original implementation, so
 *       server-driven privileges the module has never heard of keep working
 *       normally.</li>
 * </ul>
 */
public final class HookPrivileges {

    /**
     * The target application's class loader.
     *
     * <p>This has to be {@code lpparam.classLoader} and not
     * {@code HookPrivileges.class.getClassLoader()}. The module's own loader
     * can see the framework and the module, and nothing else -- resolving an
     * app class through it throws {@code ClassNotFoundException}. The first
     * build of this module made exactly that mistake, and its only symptom was
     * a single log line deep inside an after-hook:
     * {@code syntheticPrivilege(...) -- ClassNotFoundException:
     * com.dragon.read.user.model.PrivilegeInfoModel}. Every privilege lookup
     * silently kept returning null.
     */
    private static volatile ClassLoader appLoader;

    private HookPrivileges() {
    }

    public static void install(ClassLoader cl) {
        appLoader = cl;

        final Class<?> pm = Hooks.findClassOrNull(Const.CLS_PRIVILEGE_MANAGER, cl);
        if (pm == null) {
            XLog.w("PrivilegeManager not found -- privilege hooks skipped");
            return;
        }

        hookHasPrivilege(pm);
        hookGetPrivilege(pm);
        hookModelPredicates(cl);
        hookConvenienceFlags(pm);
    }

    /**
     * {@code native boolean hasPrivilege(String)} -- the bottom of the chain
     * every {@code has*Privilege()} helper funnels into.
     */
    private static void hookHasPrivilege(Class<?> pm) {
        Hooks.raw(pm, "hasPrivilege", String.class, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                String id = (String) param.args[0];
                if (Const.isUnlocked(id)) {
                    param.setResult(Boolean.TRUE);
                    Probe.hit("hasPrivilege", "id=" + id + " -> true");
                }
            }
        });
    }

    /**
     * {@code PrivilegeInfoModel getPrivilege(String)} -- a plain
     * {@code HashMap.get}.
     *
     * <p>Returning a non-null model here is what {@code hasPrivilege} cannot do
     * on its own: the auto-read controller reads the model directly and asks it
     * how many seconds are left, never consulting {@code hasPrivilege} at all.
     */
    private static void hookGetPrivilege(final Class<?> pm) {
        Hooks.raw(pm, "getPrivilege", String.class, new XC_MethodHook() {
            @Override
            public void afterHookedMethod(MethodHookParam param) {
                Object real = param.getResult();
                String id = (String) param.args[0];
                if (real != null) {
                    // The server really did grant this one; leave it alone.
                    return;
                }
                if (!Const.isUnlocked(id)) {
                    return;
                }
                Object synth = syntheticPrivilege(id);
                if (synth != null) {
                    param.setResult(synth);
                    if (Const.PID_AUTO_PAGE.equals(id)) {
                        // 无限时长自动阅读 -- the decision the assignment turns
                        // on, so it is logged unconditionally rather than
                        // sampled away.
                        Probe.important("getPrivilege", "id=" + id
                                + " (AutoPage/自动阅读) -> synthesised forever-model");
                    } else {
                        Probe.hit("getPrivilege", "id=" + id + " -> synthesised forever-model");
                    }
                }
            }
        });
    }

    /**
     * Build a {@code PrivilegeInfoModel} that claims a permanent entitlement.
     *
     * <p>Fields are written directly rather than through the setters: the model
     * is a Gson DTO whose setters take primitives ({@code setIsForever(int)},
     * {@code setLeftTime(long)}), and going through reflection on those means
     * caring about primitive-vs-boxed matching for no benefit.
     */
    private static Object syntheticPrivilege(String id) {
        ClassLoader cl = appLoader;
        if (cl == null) {
            return null;
        }
        try {
            Class<?> model = XposedHelpers.findClass(Const.CLS_PRIVILEGE_INFO_MODEL, cl);
            Object instance = XposedHelpers.newInstance(model, new Object[0]);
            XposedHelpers.setObjectField(instance, "id", id);
            XposedHelpers.setObjectField(instance, "name", "永久权益");
            XposedHelpers.setIntField(instance, "isForever", Const.FOREVER);
            // Both numeric fields are SECONDS -- see Const.FOREVER_SECONDS.
            XposedHelpers.setLongField(instance, "leftTime", Const.FOREVER_SECONDS);
            XposedHelpers.setLongField(instance, "expireTime", Const.FOREVER_EXPIRE_SECONDS);
            XposedHelpers.setObjectField(instance, "remainTimes", Long.valueOf(999_999L));
            return instance;
        } catch (Throwable t) {
            XLog.hookFailed("syntheticPrivilege(" + id + ")", t);
            return null;
        }
    }

    /**
     * {@code PrivilegeInfoModel} predicates.
     *
     * <p>These carry the whole "无限时长" requirement. The auto-read controller
     * computes its remaining budget as
     * {@code privilege.isForever() ? Long.MAX_VALUE : privilege.leftSecondsAfterInit()},
     * so making both true/huge is exactly the difference between "watch an ad
     * every N minutes" and "never runs out".
     */
    private static void hookModelPredicates(ClassLoader cl) {
        final Class<?> model = Hooks.findClassOrNull(Const.CLS_PRIVILEGE_INFO_MODEL, cl);
        if (model == null) {
            XLog.w("PrivilegeInfoModel not found -- model predicates not hooked");
            return;
        }

        Hooks.raw(model, "isForever", new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
                Probe.hit("PrivilegeInfoModel.isForever", "-> true");
            }
        });
        Hooks.raw(model, "available", new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        });

        // Signature is leftSecondsAfterInit(Long) -- the argument is a nullable
        // override, unrelated to the returned budget.
        Hooks.raw(model, "leftSecondsAfterInit", Long.class, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Long.valueOf(Const.FOREVER_SECONDS));
                Probe.hit("PrivilegeInfoModel.leftSecondsAfterInit",
                        "-> " + Const.FOREVER_SECONDS);
            }
        });

        // getExpireTime() feeds the "expires on ..." UI. An untouched 0 reads as
        // "expired" in several comparisons, so pin it far into the future.
        Hooks.forceResult(model, "getExpireTime", Long.valueOf(Const.FOREVER_EXPIRE_SECONDS));

        // The raw DTO getter, in case anything reads the field's twin directly.
        Hooks.forceResult(model, "getIsForever", Integer.valueOf(Const.FOREVER));
    }

    /**
     * The named helpers.
     *
     * <p>Most already route through {@code hasPrivilege}, so hooking them is
     * redundant -- but "redundant" is cheap insurance against a build where one
     * of them short-circuits to a literal {@code false} before consulting the
     * map. The native ones in particular are cheap to force and impossible to
     * reach any other way.
     */
    private static void hookConvenienceFlags(final Class<?> pm) {
        // --- membership -------------------------------------------------
        Hooks.raw(pm, "isVip", new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
                Probe.hit("PrivilegeManager.isVip", "-> true");
            }
        });
        Hooks.forceTrue(pm, "isAnyVip");
        Hooks.forceTrue(pm, "isFakeVipActive");
        Hooks.forceTrue(pm, "hasVipShortSeriesPrivilege");
        Hooks.forceTrue(pm, "adVipAvailable");
        Hooks.forceTrue(pm, "canReadShortStory");
        Hooks.forceTrue(pm, "canShowVipRelational");   // native
        Hooks.forceFalse(pm, "showPayVipEntranceInChapterEnd"); // native: no "buy VIP" nag

        // --- ad-free ----------------------------------------------------
        Hooks.forceTrue(pm, "hasNoAdPrivilege");               // native
        Hooks.forceTrue(pm, "hasNoAdFollAllScene");            // native
        Hooks.forceTrue(pm, "hasNoAdForShortSeries");          // native
        Hooks.forceTrue(pm, "hasNoAdReadConsumptionPrivilege");// native
        Hooks.forceTrue(pm, "isForeverNoAd");                  // native

        // --- auto read (无限时长自动阅读) --------------------------------
        Hooks.forceTrue(pm, "hasAutoPagePrivilege");

        // --- the rest of the entitlement set ----------------------------
        Hooks.forceTrue(pm, "hasReadPaidBookPrivilege");
        Hooks.forceTrue(pm, "hasOfflineReadingPrivilege");
        Hooks.forceTrue(pm, "hasBookDownloadPrivilege", String.class);
        Hooks.forceTrue(pm, "hasInspireBookPrivilege");
        Hooks.forceTrue(pm, "hasTtsConsumptionPrivilege");
        Hooks.forceTrue(pm, "hasTtsNaturePrivilege");
        Hooks.forceTrue(pm, "hasTtsNewUserPrivilege");
        Hooks.forceTrue(pm, "hasTtsPrivilege");
        Hooks.forceTrue(pm, "hasNewUserFreeDownloadPrivilege");
        Hooks.forceTrue(pm, "hasLocalOfflineReadPrivilege", String.class);
        Hooks.forceTrue(pm, "gotLocalOfflineReadPrivilege", String.class);
        Hooks.forceTrue(pm, "isHasFansStickerPrivilege");

        // --- per-book ad flags take an argument -------------------------
        Hooks.forceTrue(pm, "isNoAd", String.class);              // native
        Hooks.forceResult(pm, "isBookAdFree", Integer.valueOf(1), String.class); // native

        // --- negative privileges stay honest ----------------------------
        Hooks.forceFalse(pm, "checkCommentForbidden");
    }
}
