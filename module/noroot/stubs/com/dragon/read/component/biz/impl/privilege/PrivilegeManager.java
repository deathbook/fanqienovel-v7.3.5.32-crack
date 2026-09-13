package com.dragon.read.component.biz.impl.privilege;

import com.dragon.read.user.model.PrivilegeInfoModel;

/**
 * COMPILE-TIME STUB ONLY -- never dexed.
 *
 * <p>The no-root build adds a real subclass of this class to the APK and points
 * the app's own singleton factory at it. javac needs a superclass to compile
 * that subclass against, so this declares exactly the members
 * {@code CrackPrivilegeManager} touches. The bytecode it produces refers to
 * {@code com/dragon/read/component/biz/impl/privilege/PrivilegeManager}, which
 * at runtime is the app's real class.
 *
 * <p>Every descriptor here was read out of the dex, not recalled:
 * {@code scripts/dex_probe.py members original.apk impl.privilege.PrivilegeManager}.
 * A wrong descriptor would compile fine and then silently not override
 * anything -- the same failure mode that made the first LSPosed build hook
 * nothing at all.
 *
 * <p>{@code isVip}, {@code hasPrivilege}, {@code hasNoAd*} etc. are declared
 * {@code native} in the real class and satisfied by
 * {@code libdragoncore.so} through {@code RegisterNatives}. Subclassing is what
 * makes them reachable: a native method may be overridden by an ordinary Java
 * method, whereas removing the {@code native} flag in smali would make the
 * library's {@code RegisterNatives} call fail and break
 * {@code System.loadLibrary} entirely.
 */
public class PrivilegeManager {

    public PrivilegeManager() {
    }

    // --- native in the real class --------------------------------------
    public native boolean isVip();

    public native boolean hasPrivilege(String privilegeId);

    public native boolean hasNoAdPrivilege();

    public native boolean hasNoAdFollAllScene();

    public native boolean hasNoAdForShortSeries();

    public native boolean hasNoAdReadConsumptionPrivilege();

    public native boolean isForeverNoAd();

    public native boolean isNoAd(String bookId);

    public native int isBookAdFree(String bookId);

    public native boolean canShowVipRelational();

    public native boolean showPayVipEntranceInChapterEnd();

    // --- ordinary Java in the real class, used here as super calls ------
    public PrivilegeInfoModel getPrivilege(String privilegeId) {
        return null;
    }
}
