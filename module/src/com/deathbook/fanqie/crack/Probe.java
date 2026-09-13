package com.deathbook.fanqie.crack;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proof-of-life for hooks.
 *
 * <p>A hook that installs and never fires is indistinguishable from a hook that
 * was never installed -- and from one that fires and is ignored. This module's
 * first working build installed 53 hooks and still could not have been called
 * "verified", because nothing in its output showed the hooks being reached by
 * real application code.
 *
 * <p>Each probe therefore records how many times a hook actually fired, and
 * logs a bounded sample of the interesting calls with their arguments and the
 * value the module substituted. The final counters are dumped at the end of
 * {@code handleLoadPackage} and on demand, so a log dump is evidence rather
 * than a claim.
 *
 * <p>Logging is sampled rather than exhaustive: {@code hasPrivilege} is on the
 * hot path of every UI build, and logging every call would both flood logcat
 * and change the timing of the thing being measured.
 */
public final class Probe {

    /** How many interesting calls to log in full before going quiet. */
    private static final int SAMPLE = 40;

    private static final AtomicInteger fires = new AtomicInteger();
    private static final AtomicInteger logged = new AtomicInteger();
    private static final AtomicLong firstFireNanos = new AtomicLong(0);

    private Probe() {
    }

    /**
     * Record a hook hit. Logs the first {@link #SAMPLE} of them, then only
     * counts, so a long session produces a bounded log.
     */
    public static void hit(String what, String detail) {
        int n = fires.incrementAndGet();
        firstFireNanos.compareAndSet(0L, System.nanoTime());
        if (logged.incrementAndGet() <= SAMPLE) {
            XLog.i("PROBE " + what + "  " + detail);
        } else if (n == SAMPLE + 1) {
            XLog.i("PROBE " + what + ": sampling stopped after " + SAMPLE + " entries");
        }
    }

    public static int fireCount() {
        return fires.get();
    }

    /**
     * Record an event that must survive sampling.
     *
     * <p>The sampled log is right for hot predicates like {@code hasPrivilege},
     * where the fact that it fired at all is the interesting part. It is wrong
     * for the handful of decisions the assignment actually turns on: if the
     * auto-read entitlement query scrolls past position 40 of the sample, the
     * one piece of evidence that matters is missing. These get their own
     * counter and their own (much larger) cap.
     */
    private static final AtomicInteger importantLogged = new AtomicInteger();
    private static final int IMPORTANT_CAP = 200;

    public static void important(String what, String detail) {
        fires.incrementAndGet();
        int n = importantLogged.incrementAndGet();
        if (n <= IMPORTANT_CAP) {
            XLog.i("PROBE* " + what + "  " + detail);
        }
    }

    public static void summary(String where) {
        XLog.i("SELFTEST " + where
                + " probe_fires=" + fires.get()
                + " hooks_installed=" + Hooks.installedCount()
                + " hooks_missed=" + Hooks.missedCount());
    }
}
