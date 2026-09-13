package com.deathbook.fanqie.crack.noroot;

import com.dragon.read.component.biz.impl.privilege.PrivilegeManager;
import com.dragon.read.user.model.PrivilegeInfoModel;

/**
 * The no-root crack, expressed as a subclass of the app's own privilege hub.
 *
 * <p>FanQie makes the interesting privilege decisions {@code native} and backs
 * them with {@code libdragoncore.so}. That is the sample's anti-tamper move: an
 * smali edit of {@code hasAutoPagePrivilege()} changes nothing, because the
 * answer is produced in C++.
 *
 * <p>It cannot stop a subclass. A {@code native} method may be overridden by an
 * ordinary Java method, and {@code PrivilegeManager.getInstance()} builds the
 * singleton with a plain {@code new-instance} in its own smali. Repointing that
 * one instruction at this class therefore replaces every privilege decision in
 * the app, including the thirteen natives, with no hooking framework, no
 * injected runtime, and nothing that depends on the Android version.
 *
 * <p>(Removing the {@code native} flag in smali instead would <em>not</em> work:
 * the library registers its methods with {@code RegisterNatives} from
 * {@code JNI_OnLoad}, and registering a non-native method fails, which turns
 * into a failed {@code System.loadLibrary}.)
 *
 * <p>Two deliberate exclusions, same as the LSPosed module:
 *
 * <ul>
 *   <li>{@code CommentForbidden} is a <em>negative</em> privilege. A blanket
 *       {@code hasPrivilege -> true} would take the user's comment box away.</li>
 *   <li>Ids the crack has never heard of fall through to the real
 *       implementation, so server-driven privileges keep working.</li>
 * </ul>
 *
 * <p>The three methods that are left alone are the ones the crack has no
 * business faking: {@code getPrivilegeJson()} still reports the server's own
 * view (so the app's internal consistency checks see what they expect), and
 * {@code updateVipInfo()} still stores whatever the server sent.
 */
public class CrackPrivilegeManager extends PrivilegeManager {

    // ------------------------------------------------------------------
    // FanQie keys its whole privilege model on opaque 19-digit snowflake ids.
    // Every has*Privilege()/get*Privilege() helper is a wrapper around
    // hasPrivilege(String) / getPrivilege(String) with one of these.
    // ------------------------------------------------------------------
    private static final String PID_VIP = "6825868665112494095";
    private static final String PID_NO_AD = "6703327401314620167";
    private static final String PID_NO_AD_ALL_SCENE = "7077535443348116268";
    private static final String PID_SHORT_SERIES_NO_AD = "7313754740460884790";
    /** 自动阅读 -- the one the whole "无限时长" requirement hangs off. */
    private static final String PID_AUTO_PAGE = "6836977122288866051";
    private static final String PID_READ_PAID_BOOK = "7210376203117531962";
    private static final String PID_OFFLINE_READING = "6703327578779816712";
    private static final String PID_BOOK_DOWNLOAD = "6766572795204735752";
    private static final String PID_AUDIO_DOWNLOAD = "6836976852234408712";
    private static final String PID_TTS_NATURE = "6703327493505422087";
    private static final String PID_TTS_CONSUMPTION = "7025948416286921516";
    private static final String PID_TTS_NEW_USER = "7026654500215608108";
    private static final String PID_READ_CONSUME = "7232191200411783994";
    private static final String PID_SKIP_CARD = "7602149827249787710";
    private static final String PID_INSPIRES_BOOK = "6703327536606089992";
    private static final String PID_FANS_STICKER = "7036359604355224364";
    private static final String PID_VIP_SHORT_SERIES = "7315003038136013631";

    /** CommentForbidden is deliberately absent -- it is a negative privilege. */
    private static final String[] UNLOCKED = {
            PID_VIP, PID_NO_AD, PID_NO_AD_ALL_SCENE, PID_SHORT_SERIES_NO_AD,
            PID_AUTO_PAGE, PID_READ_PAID_BOOK, PID_OFFLINE_READING,
            PID_BOOK_DOWNLOAD, PID_AUDIO_DOWNLOAD, PID_TTS_NATURE,
            PID_TTS_CONSUMPTION, PID_TTS_NEW_USER, PID_READ_CONSUME,
            PID_SKIP_CARD, PID_INSPIRES_BOOK, PID_FANS_STICKER,
            PID_VIP_SHORT_SERIES,
    };

    /**
     * Long.MAX_VALUE / 1000, in SECONDS.
     *
     * <p>Every time field is in seconds and callers multiply by 1000 --
     * {@code PrivilegeInfoModel.leftSecondsAfterInit} does exactly that. Using
     * Long.MAX_VALUE/4 there overflows to a negative long, which clamps to 0 and
     * reports the privilege as <em>expired</em>: the exact opposite of the
     * intent. This is the largest value that survives one *1000 (~292 Myr).
     */
    private static final long FOREVER_SECONDS = Long.MAX_VALUE / 1000L;

    /**
     * Expiry, in SECONDS, set to FanQie's own permanent-membership sentinel --
     * the value the app itself renders as {@code 5555-05-20}. Using an
     * arbitrary huge number instead would make any component that formats this
     * field print a date millions of years out, which is both an obvious tell
     * and a layout hazard in fixed-width labels.
     */
    private static final long FOREVER_EXPIRE_SECONDS = 113_143_651_200L;

    /** A count, not a timestamp: large enough to read as unlimited, not absurd. */
    private static final Long UNLIMITED_TIMES = 999_999L;

    private static boolean isUnlocked(String id) {
        if (id == null) {
            return false;
        }
        for (String candidate : UNLOCKED) {
            if (candidate.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // membership
    // ------------------------------------------------------------------
    @Override
    public boolean isVip() {
        return true;
    }

    @Override
    public boolean hasPrivilege(String privilegeId) {
        if (isUnlocked(privilegeId)) {
            return true;
        }
        return super.hasPrivilege(privilegeId);
    }

    @Override
    public boolean canShowVipRelational() {
        return true;
    }

    /** Native, and the app's "please buy VIP" nag on the chapter-end page. */
    @Override
    public boolean showPayVipEntranceInChapterEnd() {
        return false;
    }

    // ------------------------------------------------------------------
    // ad-free
    // ------------------------------------------------------------------
    @Override
    public boolean hasNoAdPrivilege() {
        return true;
    }

    @Override
    public boolean hasNoAdFollAllScene() {
        return true;
    }

    @Override
    public boolean hasNoAdForShortSeries() {
        return true;
    }

    @Override
    public boolean hasNoAdReadConsumptionPrivilege() {
        return true;
    }

    @Override
    public boolean isForeverNoAd() {
        return true;
    }

    @Override
    public boolean isNoAd(String bookId) {
        return true;
    }

    @Override
    public int isBookAdFree(String bookId) {
        return 1;
    }

    // ------------------------------------------------------------------
    // the privilege model itself
    // ------------------------------------------------------------------

    /**
     * The real method is a plain {@code HashMap.get} on the server-populated
     * map, so {@code super.getPrivilege} is both the honest lookup and the
     * fall-through for ids the crack does not know about.
     *
     * <p>Returning a synthesised model here is what the {@code has*Privilege}
     * helpers cannot do on their own: the auto-read controller reads the model
     * directly and asks it how many seconds are left, never consulting
     * {@code hasPrivilege} at all.
     */
    @Override
    public PrivilegeInfoModel getPrivilege(String privilegeId) {
        PrivilegeInfoModel real = super.getPrivilege(privilegeId);
        if (real != null || !isUnlocked(privilegeId)) {
            return real;
        }
        return synthetic(privilegeId);
    }

    private static PrivilegeInfoModel synthetic(String privilegeId) {
        PrivilegeInfoModel model = new PrivilegeInfoModel();
        model.setId(privilegeId);
        model.setName("永久权益");
        model.setIsForever(1);
        model.setLeftTime(FOREVER_SECONDS);
        model.setExpireTime(FOREVER_EXPIRE_SECONDS);
        model.setRemainTimes(UNLIMITED_TIMES);
        return model;
    }
}
