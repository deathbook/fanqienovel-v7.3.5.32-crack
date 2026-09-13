package com.deathbook.fanqie.crack;

/**
 * The VIP surface, outside {@code PrivilegeManager}.
 *
 * <p>{@code PrivilegeManager.isVip()} is only one of several VIP entry points
 * FanQie exposes. The 156 {@code isVip()} call sites in the sample reach it
 * through two different seams:
 *
 * <ul>
 *   <li>{@code NsVipApi.isVip(VipCommonSubType)} -- the SDK-facing seam, also
 *       carrying the "is this a SVIP / ad-free-VIP / pub-VIP" sub-type query;</li>
 *   <li>{@code NsCommonDepend.isVip()} and {@code NsUserInfoDepend.isVip()} --
 *       the app-service seam, plus the {@code /user/info} bridge that the H5
 *       pages read.</li>
 * </ul>
 *
 * <p>Hooking only {@code PrivilegeManager} leaves those seams reporting "not a
 * member", which shows up as a profile page that still says 未开通 while the
 * reader already behaves as if VIP. Both seams are therefore forced here.
 *
 * <p>The sub-type predicate is forced true for every {@code VipCommonSubType}
 * rather than only for the plain VIP constant: the app also asks "is this user
 * an ad-free VIP" and "is this user a SVIP" when deciding whether to draw the
 * ad-free badge and to skip 短剧 pre-rolls, and answering only the plain query
 * leaves those two gates shut.
 */
public final class HookVip {

    private HookVip() {
    }

    public static void install(ClassLoader cl) {
        // Resolved once, and allowed to be absent: if the sub-type enum is
        // missing we simply skip the one-argument overload instead of passing
        // a null Class into the hook machinery.
        Class<?> subType = Hooks.findClassOrNull(
                "com.dragon.read.rpc.model.VipCommonSubType", cl);

        // --- SDK-facing VIP api: NsVipApi.IMPL ---------------------------
        // NsVipImpl declares only the sub-type overload -- verified against the
        // dex, where asking for a no-arg isVip() here produced a hook miss.
        Class<?> nsVipImpl = Hooks.findClassOrNull(Const.CLS_NS_VIP_IMPL, cl);
        if (nsVipImpl != null) {
            if (subType != null) {
                Hooks.forceTrue(nsVipImpl, "isVip", subType);
            } else {
                XLog.w("VipCommonSubType not found -- NsVipImpl.isVip left alone");
            }
        } else {
            XLog.w("NsVipImpl not found -- SDK-facing VIP seam left alone");
        }

        // --- app-service seam: NsCommonDepend.IMPL -----------------------
        Class<?> common = Hooks.findClassOrNull(Const.CLS_NS_COMMON_DEPEND_IMPL, cl);
        if (common != null) {
            Hooks.forceTrue(common, "isVip");
            if (subType != null) {
                Hooks.forceTrue(common, "isVip", subType);
            }
        } else {
            XLog.w("NsCommonDependImpl not found");
        }

        // --- user-info bridge used by H5 / hybrid pages ------------------
        Class<?> userInfo = Hooks.findClassOrNull(Const.CLS_NS_USER_INFO_DEPEND_IMPL, cl);
        if (userInfo != null) {
            Hooks.forceTrue(userInfo, "isVip");
        } else {
            XLog.w("NsUserInfoDependImpl not found");
        }
    }
}
