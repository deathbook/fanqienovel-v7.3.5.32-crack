/*
 * FanQieNovelCrack -- Frida implementation (the no-root path).
 *
 * This is the same crack as the LSPosed module, expressed against Frida's Java
 * bridge instead of XposedBridge. Everything it needs to know about the target
 * is in the two tables below; the reasoning behind each entry is in
 * docs/03_hook_targets.md.
 *
 * Target: com.dragon.read 7.3.5.32 (73532), the Lesson-2 course sample.
 *
 * Deployed by scripts/repack_noroot.ps1, which drops this file into the APK's
 * native lib directory and points a Frida gadget at it.
 */

'use strict';

var TAG = 'FanQieCrack';

function log(msg) {
    console.log(TAG + ': ' + msg);
}

/* ------------------------------------------------------------------ *
 * FanQie's privilege ids. Every has*Privilege()/get*Privilege() helper
 * is a wrapper around hasPrivilege(String) / getPrivilege(String) with
 * one of these, and the interesting ones are native  --  the decision is
 * made inside lib/armeabi-v7a/libdragoncore.so. Hooking the Java
 * boundary sidesteps the C++ entirely and is ABI independent.
 * ------------------------------------------------------------------ */
var PID = {
    VIP:                 '6825868665112494095',
    NO_AD:               '6703327401314620167',
    NO_AD_ALL_SCENE:     '7077535443348116268',
    SHORT_SERIES_NO_AD:  '7313754740460884790',
    AUTO_PAGE:           '6836977122288866051',   // 自动阅读
    READ_PAID_BOOK:      '7210376203117531962',
    OFFLINE_READING:     '6703327578779816712',
    BOOK_DOWNLOAD:       '6766572795204735752',
    AUDIO_DOWNLOAD:      '6836976852234408712',
    TTS_NATURE:          '6703327493505422087',
    TTS_CONSUMPTION:     '7025948416286921516',
    TTS_NEW_USER:        '7026654500215608108',
    READ_CONSUME:        '7232191200411783994',
    SKIP_CARD:           '7602149827249787710',
    INSPIRES_BOOK:       '6703327536606089992',
    FANS_STICKER:        '7036359604355224364',
    VIP_SHORT_SERIES:    '7315003038136013631'
};

/* CommentForbidden is deliberately absent: it is a *negative* privilege,
 * and granting it would take the user's comment box away  --  the classic
 * way a blanket "hasPrivilege -> true" hook breaks an app. */
var UNLOCKED = {};
Object.keys(PID).forEach(function (k) { UNLOCKED[PID[k]] = true; });

function isUnlocked(id) {
    return id !== null && id !== undefined && UNLOCKED[id] === true;
}

/* Every time field in this app is in SECONDS, and callers routinely multiply by
 * 1000 -- PrivilegeInfoModel.leftSecondsAfterInit does exactly that. The first
 * version of this crack used Long.MAX_VALUE/4, which overflows to a *negative*
 * long under that multiply, gets clamped to 0, and makes the privilege look
 * expired. Long.MAX_VALUE/1000 is the largest value that survives one *1000,
 * which is still ~292 million years. */
var FOREVER_SECONDS = 9223372036854775;      // Long.MAX_VALUE / 1000

/* Expiry timestamp, also in SECONDS. The unit is not a guess:
 * a54.g.getCommentForbiddenLeftDays() computes
 *   (privilege.getExpireTime() * 1000) - System.currentTimeMillis()
 * so expire_time is epoch seconds. The value is FanQie's own permanent-VIP
 * sentinel, which the app renders as 5555-05-20 -- using an arbitrary huge
 * number instead makes any date formatting print a date millions of years out,
 * which is both an obvious tell and a layout hazard in fixed-width labels. */
var FOREVER_EXPIRE_SECONDS = 113143651200;   // 5555-05-20

/* Rate-limit the log so a long reading session cannot flood logcat. The
 * auto-read entitlement query in particular runs on a timer. */
var logged = {};
function logOnce(key, msg) {
    var n = logged[key] = (logged[key] || 0) + 1;
    if (n <= 20) {
        log(msg + (n === 20 ? '  (further occurrences suppressed)' : ''));
    }
}

var installed = 0;
var missed = 0;

/* ------------------------------------------------------------------ *
 * Small helpers
 * ------------------------------------------------------------------ */

/* Force a Java method to a constant return value, tolerating absence.
 * Frida throws on a missing class or overload, and an unguarded throw here
 * would abandon the rest of the hook set  --  so every install is wrapped. */
function force(className, methodName, value, argTypes, label) {
    var what = className.split('.').pop() + '.' + methodName;
    try {
        var C = Java.use(className);
        var overloads = C[methodName].overloads;
        var target = null;
        if (argTypes && argTypes.length) {
            for (var i = 0; i < overloads.length; i++) {
                if (overloads[i].argumentTypes.length === argTypes.length) {
                    var ok = true;
                    for (var j = 0; j < argTypes.length; j++) {
                        if (overloads[i].argumentTypes[j].className !== argTypes[j]) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) { target = overloads[i]; break; }
                }
            }
        } else {
            for (var k = 0; k < overloads.length; k++) {
                if (overloads[k].argumentTypes.length === 0) { target = overloads[k]; break; }
            }
        }
        if (target === null) {
            throw new Error('no matching overload');
        }
        target.implementation = function () {
            logOnce(what, 'PROBE ' + what + ' -> ' + value);
            return value;
        };
        installed++;
        log('[OK] ' + what + ' -> ' + value + (label ? '  (' + label + ')' : ''));
        return true;
    } catch (e) {
        missed++;
        log('[MISS] ' + what + (label ? '  (' + label + ')' : '') + ' -- ' + e);
        return false;
    }
}

function forceTrue(cls, m, argTypes, label) { return force(cls, m, true, argTypes, label); }
function forceFalse(cls, m, argTypes, label) { return force(cls, m, false, argTypes, label); }

/* ------------------------------------------------------------------ *
 * The crack
 * ------------------------------------------------------------------ */
Java.perform(function () {
    log('================ FanQieNovelCrack (Frida) attached ================');
    var startedAt = Date.now();

    var PM = 'com.dragon.read.component.biz.impl.privilege.PrivilegeManager';
    var PIM = 'com.dragon.read.user.model.PrivilegeInfoModel';

    /* ---- 1. hasPrivilege(String): the bottom of the chain ---------- */
    try {
        var Pm = Java.use(PM);
        var origHasPrivilege = Pm.hasPrivilege;   // native
        Pm.hasPrivilege.implementation = function (id) {
            if (isUnlocked(id)) {
                logOnce('hasPrivilege', 'PROBE hasPrivilege id=' + id + ' -> true');
                return true;
            }
            /* Unknown ids fall through, so server-driven privileges the
             * script has never heard of keep working normally. */
            return origHasPrivilege.call(this, id);
        };
        installed++;
        log('[OK] PrivilegeManager.hasPrivilege (native) -- whitelist of ' +
            Object.keys(PID).length + ' ids');
    } catch (e) {
        missed++;
        log('[MISS] PrivilegeManager.hasPrivilege -- ' + e);
    }

    /* ---- 2. getPrivilege(String): synthesise the model ------------- */
    /* Returning a non-null model is what hasPrivilege cannot do on its own:
     * the auto-read controller reads the model directly and asks it how many
     * seconds are left, never consulting hasPrivilege at all. */
    function synthPrivilege(id) {
        try {
            var Model = Java.use(PIM);
            var m = Model.$new();
            m.setId(id);
            m.setName('永久权益');
            m.setIsForever(1);
            m.setLeftTime(FOREVER_SECONDS);
            m.setExpireTime(FOREVER_EXPIRE_SECONDS);
            m.setRemainTimes(Java.use('java.lang.Long').valueOf(999999));
            return m;
        } catch (e) {
            log('[MISS] syntheticPrivilege(' + id + ') -- ' + e);
            return null;
        }
    }

    try {
        var Pm2 = Java.use(PM);
        var origGetPrivilege = Pm2.getPrivilege;
        Pm2.getPrivilege.implementation = function (id) {
            var real = origGetPrivilege.call(this, id);
            if (real !== null && real !== undefined) {
                return real;            // the server really granted this one
            }
            if (!isUnlocked(id)) {
                return null;
            }
            var synth = synthPrivilege(id);
            if (synth !== null) {
                if (id === PID.AUTO_PAGE) {
                    /* 无限时长自动阅读 -- the decision this assignment turns
                     * on, so it is logged unconditionally rather than sampled. */
                    log('PROBE* getPrivilege id=' + id +
                        ' (AutoPage/自动阅读) -> synthesised forever-model');
                } else {
                    logOnce('getPrivilege', 'PROBE getPrivilege id=' + id +
                        ' -> synthesised forever-model');
                }
            }
            return synth;
        };
        installed++;
        log('[OK] PrivilegeManager.getPrivilege -- synthesises forever-models');
    } catch (e) {
        missed++;
        log('[MISS] PrivilegeManager.getPrivilege -- ' + e);
    }

    /* ---- 3. PrivilegeInfoModel predicates: the "无限时长" bit ------ */
    forceTrue(PIM, 'isForever', null, '永久');
    forceTrue(PIM, 'available', null, '永久');
    force(PIM, 'leftSecondsAfterInit', FOREVER_SECONDS, ['java.lang.Long'],
        '返回 ~2920 亿年');
    force(PIM, 'getExpireTime', FOREVER_EXPIRE_SECONDS, null, '到期时间 = 5555-05-20 (FanQie 自己的永久哨兵值)');
    force(PIM, 'getIsForever', 1, null);

    /* ---- 4. membership ------------------------------------------- */
    forceTrue(PM, 'isVip', null, 'native 会员判定');
    forceTrue(PM, 'isAnyVip', null);
    forceTrue(PM, 'isFakeVipActive', null);
    forceTrue(PM, 'hasVipShortSeriesPrivilege', null);
    forceTrue(PM, 'adVipAvailable', null);
    forceTrue(PM, 'canReadShortStory', null);
    forceTrue(PM, 'canShowVipRelational', null, 'native');
    forceFalse(PM, 'showPayVipEntranceInChapterEnd', null, 'native: 不再催开会员');

    /* ---- 5. ad-free ---------------------------------------------- */
    forceTrue(PM, 'hasNoAdPrivilege', null, 'native');
    forceTrue(PM, 'hasNoAdFollAllScene', null, 'native');
    forceTrue(PM, 'hasNoAdForShortSeries', null, 'native');
    forceTrue(PM, 'hasNoAdReadConsumptionPrivilege', null, 'native');
    forceTrue(PM, 'isForeverNoAd', null, 'native');

    /* ---- 6. auto read -------------------------------------------- */
    forceTrue(PM, 'hasAutoPagePrivilege', null, '自动阅读');

    /* ---- 7. the rest of the entitlement set ---------------------- */
    forceTrue(PM, 'hasReadPaidBookPrivilege', null);
    forceTrue(PM, 'hasOfflineReadingPrivilege', null);
    forceTrue(PM, 'hasBookDownloadPrivilege', ['java.lang.String']);
    forceTrue(PM, 'hasInspireBookPrivilege', null);
    forceTrue(PM, 'hasTtsConsumptionPrivilege', null);
    forceTrue(PM, 'hasTtsNaturePrivilege', null);
    forceTrue(PM, 'hasTtsNewUserPrivilege', null);
    forceTrue(PM, 'hasTtsPrivilege', null);
    forceTrue(PM, 'hasNewUserFreeDownloadPrivilege', null);
    forceTrue(PM, 'hasLocalOfflineReadPrivilege', ['java.lang.String']);
    forceTrue(PM, 'gotLocalOfflineReadPrivilege', ['java.lang.String']);
    forceTrue(PM, 'isHasFansStickerPrivilege', null);

    /* Per-book ad flags take an argument and are native. */
    forceTrue(PM, 'isNoAd', ['java.lang.String'], 'native');
    force(PM, 'isBookAdFree', 1, ['java.lang.String'], 'native');

    /* Negative privileges stay honest. */
    forceFalse(PM, 'checkCommentForbidden', null);

    /* ---- 7b. the auto-read ad gates ------------------------------ */
    /* Forcing the AutoPage privilege to "forever" is what the counter and the
     * accumulate controller read. It is NOT the only place that interrupts the
     * reader with a rewarded video, and missing the other one is why the first
     * version of this crack still showed "看视频继续自动阅读" on a real device.
     *
     * Traced from the string 自动阅读权益已到期, which has exactly one emitter:
     * TemporaryReaderLifecycleListener$b, on the action_auto_read_changed
     * broadcast. It computes
     *
     *     expired = !zh6.e.b();
     *     if (NsAdApi.IMPL.inspireAdDisable()) expired = false;
     *     if (expired) zh6.e.c(2, ...)   // <- the rewarded video
     *
     * and zh6.m.subscribe (当前无自动阅读权益) grants time the same way, behind
     * the same inspireAdDisable() check. Both are closed here: the gate itself,
     * and the app's own feature switch for this exact ad.
     */
    try {
        var Arh = Java.use('zh6.e');
        var origB = Arh.b;
        Arh.b.implementation = function () {
            logOnce('zh6.e.b', 'PROBE* zh6.e.b 自动阅读权益可用 -> true');
            return true;
        };
        installed++;
        log('[OK] zh6.e.b (静态门) -> true');
    } catch (e) {
        missed++;
        log('[MISS] zh6.e.b -- ' + e);
    }

    forceTrue('com.dragon.read.component.biz.impl.NsAdImpl', 'inspireAdDisable', null,
        '关掉自动阅读激励视频（App 自带的开关）');
    /* ---- 8. the VIP seams outside PrivilegeManager --------------- */
    /* 156 isVip() call sites reach the answer through three different doors;
     * closing only the PrivilegeManager one leaves the profile page still
     * saying 未开通 while the reader already behaves as if VIP. */
    forceTrue('com.dragon.read.component.biz.impl.NsVipImpl', 'isVip',
        ['com.dragon.read.rpc.model.VipCommonSubType'], 'SDK seam');
    forceTrue('com.dragon.read.component.NsCommonDependImpl', 'isVip', null, 'app seam');
    forceTrue('com.dragon.read.component.NsCommonDependImpl', 'isVip',
        ['com.dragon.read.rpc.model.VipCommonSubType']);
    forceTrue('com.dragon.read.component.NsUserInfoDependImpl', 'isVip', null,
        'H5 /user/info seam');

    /* ---- 9. ads decided locally in NsAdDependImpl ----------------- */
    /* These three never consult the privilege map: isReaderAdFree() walks the
     * current book's own ad configuration. Note it returns int (0/1), not
     * boolean -- readerIsAdFree() is literally isReaderAdFree() != 0. */
    var NSAD = 'com.dragon.read.component.NsAdDependImpl';
    force(NSAD, 'isReaderAdFree', 1, null, '返回 1 = 免广告');
    forceTrue(NSAD, 'readerIsAdFree', null);
    forceTrue(NSAD, 'audioIsAdFree', ['java.lang.String']);
    forceFalse(NSAD, 'readerHasLeftAdForFreeChapter', null);
    forceFalse(NSAD, 'enableReaderNaturalFlowBanner', null);
    forceFalse(NSAD, 'isLocalBookShowChapterFrontAd', null);
    forceFalse(NSAD, 'isLocalBookShowChapterMiddleAd', null);
    force(NSAD, 'getMiddleAdCount', 0, null);

    /* Book-level native ad flags. They do not share a signature. */
    forceTrue('com.dragon.read.api.bookapi.BookInfo', 'isAdFree', null, 'native');
    forceTrue('com.dragon.read.reader.model.SaaSBookInfo', 'isAdFree',
        ['boolean'], 'native');

    /* ---- 10. keep the device fingerprint clean -------------------- */
    /* Neither of these is a kill switch: decompiling the callers shows the
     * result only ever lands in a report payload. That is exactly why it is
     * worth answering "no"  --  otherwise every launch advertises "Xposed
     * present", the cheapest possible way to fingerprint a cracked install. */
    forceFalse('com.bytedance.android.standard.tools.device.DeviceUtils',
        'isInstallXposed', null, '上报指纹');
    forceFalse('com.bytedance.common.utility.DeviceUtils',
        'isInstallXposed', null, '上报指纹');
    forceFalse('com.tencent.tinker.lib.utils.Utils',
        'isXposedExists', ['java.lang.Throwable']);

    log('SELFTEST hooks_installed=' + installed + ' hooks_missed=' + missed +
        ' took_ms=' + (Date.now() - startedAt));
    log('==================================================================');
});
