package com.deathbook.fanqie.crack;

/**
 * Every constant that ties the module to one specific build of the target.
 *
 * <p>The target is FanQie Novel (番茄免费小说) {@code com.dragon.read}
 * v7.3.5.32 / versionCode 73532, the Lesson-2 sample shipped by the course.
 * A different build will very likely need this file (and only this file)
 * re-derived.  The derivation commands live in {@code docs/}.
 */
public final class Const {

    private Const() {
    }

    /** Target package. Nothing else is touched. */
    public static final String TARGET_PACKAGE = "com.dragon.read";

    /** Logcat tag; also the prefix of every XposedBridge log line. */
    public static final String TAG = "FanQieCrack";

    /** Course sample version this module was derived from. */
    public static final String TARGET_VERSION = "7.3.5.32 (73532)";

    // ------------------------------------------------------------------
    // FanQie's privilege system
    // ------------------------------------------------------------------
    // PrivilegeManager keys its whole model on opaque 19-digit snowflake ids.
    // Every has*Privilege()/get*Privilege() helper is a thin wrapper around
    // hasPrivilege(String) / getPrivilege(String) with one of these ids, and
    // the interesting ones are *native* -- the decision itself is made inside
    // lib/armeabi-v7a/libdragoncore.so, which is the lesson's anti-tamper
    // layer.  Hooking the Java boundary sidesteps the native code entirely and
    // is ABI independent, which matters here because the sample only ships
    // 32-bit ARM libraries.

    /** Vip -- the master membership flag. */
    public static final String PID_VIP = "6825868665112494095";

    /** NoAd -- reader/audio ad-free. */
    public static final String PID_NO_AD = "6703327401314620167";

    /** NoAdAllScene -- ad-free across every scene, not just the reader. */
    public static final String PID_NO_AD_ALL_SCENE = "7077535443348116268";

    /** ShortSeriesNoAd -- ad-free for 短剧. */
    public static final String PID_SHORT_SERIES_NO_AD = "7313754740460884790";

    /**
     * AutoPage -- 自动阅读 (auto page turn). This is the one behind
     * "无限时长自动阅读": the controller asks for its remaining seconds and
     * stops the timer at zero.
     */
    public static final String PID_AUTO_PAGE = "6836977122288866051";

    /** ReadPaidBook -- reading paid/rights-managed books. */
    public static final String PID_READ_PAID_BOOK = "7210376203117531962";

    /** OfflineReading -- offline (downloaded) reading window. */
    public static final String PID_OFFLINE_READING = "6703327578779816712";

    /** BookDownload -- whole-book download. */
    public static final String PID_BOOK_DOWNLOAD = "6766572795204735752";

    /** AudioDownload -- audio download. */
    public static final String PID_AUDIO_DOWNLOAD = "6836976852234408712";

    /** TtsNature / TtsConsumption / TtsNewUser -- 听书 (TTS) quota triplet. */
    public static final String PID_TTS_NATURE = "6703327493505422087";
    public static final String PID_TTS_CONSUMPTION = "7025948416286921516";
    public static final String PID_TTS_NEW_USER = "7026654500215608108";

    /** ReadConsume / SkipCard / InspiresBook / FansSticker / VipShortSeries. */
    public static final String PID_READ_CONSUME = "7232191200411783994";
    public static final String PID_SKIP_CARD = "7602149827249787710";
    public static final String PID_INSPIRES_BOOK = "6703327536606089992";
    public static final String PID_FANS_STICKER = "7036359604355224364";
    public static final String PID_VIP_SHORT_SERIES = "7315003038136013631";

    /**
     * CommentForbidden is deliberately NOT unlocked -- it is a *negative*
     * privilege.  Reporting it as owned would take the user's comment box away,
     * which is the classic way a blanket "hasPrivilege() -> true" hook breaks
     * an app.
     */
    public static final String PID_COMMENT_FORBIDDEN = "6885168538881889039";

    /** Everything the module grants. */
    public static final String[] UNLOCKED = {
            PID_VIP,
            PID_NO_AD,
            PID_NO_AD_ALL_SCENE,
            PID_SHORT_SERIES_NO_AD,
            PID_AUTO_PAGE,
            PID_READ_PAID_BOOK,
            PID_OFFLINE_READING,
            PID_BOOK_DOWNLOAD,
            PID_AUDIO_DOWNLOAD,
            PID_TTS_NATURE,
            PID_TTS_CONSUMPTION,
            PID_TTS_NEW_USER,
            PID_READ_CONSUME,
            PID_SKIP_CARD,
            PID_INSPIRES_BOOK,
            PID_FANS_STICKER,
            PID_VIP_SHORT_SERIES,
    };

    /** Fast membership test for the hot hasPrivilege(String) path. */
    public static boolean isUnlocked(String privilegeId) {
        if (privilegeId == null) {
            return false;
        }
        for (String id : UNLOCKED) {
            if (id.equals(privilegeId)) {
                return true;
            }
        }
        return false;
    }

    /** "Forever" sentinel used by FanQie's own model: is_forever == 1. */
    public static final int FOREVER = 1;

    /**
     * Seconds handed out for a forever privilege.
     *
     * <p>Every time field in this app is in <b>seconds</b>, and callers
     * routinely do {@code value * 1000}. {@code Long.MAX_VALUE / 4} is 1000x
     * too large for that: it overflows to a negative long inside
     * {@code PrivilegeInfoModel.leftSecondsAfterInit}, which then clamps to 0
     * and reports an <em>expired</em> privilege -- the exact opposite of the
     * intent. {@code Long.MAX_VALUE / 1000} is the largest value that survives
     * one {@code *1000} without overflowing, which is ~292 million years.
     */
    public static final long FOREVER_SECONDS = Long.MAX_VALUE / 1000L;

    /**
     * Expiry timestamp for a forever privilege, in <b>seconds</b>.
     *
     * <p>The unit is not a guess: {@code a54.g.getCommentForbiddenLeftDays()}
     * computes {@code (privilege.getExpireTime() * 1000) - System.currentTimeMillis()},
     * so {@code expire_time} is seconds since the epoch.
     *
     * <p>The value is FanQie's own permanent-membership sentinel, which the
     * app renders as {@code 5555-05-20}. Using it instead of an arbitrary huge
     * number matters: any component that formats this field would otherwise
     * print a date millions of years out, which is both an obvious tell and a
     * layout hazard in fixed-width labels.
     */
    public static final long FOREVER_EXPIRE_SECONDS = 113_143_651_200L;

    // ------------------------------------------------------------------
    // Class names (kept as strings so a rename fails soft, not at class load)
    // ------------------------------------------------------------------
    public static final String CLS_PRIVILEGE_MANAGER =
            "com.dragon.read.component.biz.impl.privilege.PrivilegeManager";
    public static final String CLS_PRIVILEGE_INFO_MODEL =
            "com.dragon.read.user.model.PrivilegeInfoModel";
    public static final String CLS_VIP_INFO_MODEL =
            "com.dragon.read.user.model.VipInfoModel";
    public static final String CLS_NS_VIP_IMPL =
            "com.dragon.read.component.biz.impl.NsVipImpl";
    public static final String CLS_NS_COMMON_DEPEND_IMPL =
            "com.dragon.read.component.NsCommonDependImpl";
    public static final String CLS_NS_USER_INFO_DEPEND_IMPL =
            "com.dragon.read.component.NsUserInfoDependImpl";
    public static final String CLS_NS_AD_DEPEND_IMPL =
            "com.dragon.read.component.NsAdDependImpl";

    public static final String CLS_BOOK_INFO = "com.dragon.read.api.bookapi.BookInfo";
    public static final String CLS_SAAS_BOOK_INFO = "com.dragon.read.reader.model.SaaSBookInfo";

    /** Device fingerprint helpers used by the reporting SDKs. */
    public static final String CLS_DEVICE_UTILS_BD =
            "com.bytedance.android.standard.tools.device.DeviceUtils";
    public static final String CLS_DEVICE_UTILS_COMMON =
            "com.bytedance.common.utility.DeviceUtils";
    public static final String CLS_TINKER_UTILS = "com.tencent.tinker.lib.utils.Utils";
}
