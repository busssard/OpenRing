package app.openring.core.analysis;

import app.openring.core.Texts;

/**
 * Stage percentages for the screens (issue #32). REM, light and deep are shares of the time asleep and are rounded so
 * they always add up to 100; awake is a share of the time in bed. The usual ranges are the ones in {@code ui/Explain}.
 */
public final class StageShare {
    private StageShare() {}

    /** Below this many minutes asleep (or in bed) there is nothing to divide. */
    public static final double MIN_BASE = 1;

    /**
     * Deep, light and REM minutes as whole percentages of their sum (the time asleep), adding up to exactly 100
     * (largest remainder). Negative minutes count as 0. Returns null when a stage is missing (NaN) or there is under a
     * minute of sleep.
     */
    public static int[] sleepShares(double deep, double light, double rem) {
        if (Double.isNaN(deep) || Double.isNaN(light) || Double.isNaN(rem)) return null;
        double[] m = {clean(deep), clean(light), clean(rem)};
        double sum = m[0] + m[1] + m[2];
        if (sum < MIN_BASE) return null;
        int[] out = new int[3];
        double[] rest = new double[3];
        int given = 0;
        for (int i = 0; i < 3; i++) {
            double exact = 100 * m[i] / sum;
            out[i] = (int) Math.floor(exact);
            rest[i] = exact - out[i];
            given += out[i];
        }
        // hand out the missing points to the largest remainders, ties to the earlier stage
        while (given < 100) {
            int best = 0;
            for (int i = 1; i < 3; i++) if (rest[i] > rest[best]) best = i;
            out[best]++;
            rest[best] = -1;
            given++;
        }
        return out;
    }

    /** A part as a rounded percentage of a base (awake of time in bed); NaN when the base is missing or under a minute. */
    public static double share(double part, double base) {
        if (Double.isNaN(part) || Double.isNaN(base) || base < MIN_BASE) return Double.NaN;
        return Math.round(100 * Math.max(0, part) / base);
    }

    /** Usual ranges, written as they follow "usual" ("Usual 13 to 23% of sleep"); null where no range is known. */
    public static final String USUAL_DEEP = "13 to 23%", USUAL_REM = "about 20%";

    /**
     * One line under a stage tile: "10% of sleep (usual 13 to 23%)", "63% of sleep" without a range, and only the range
     * when the percentage is unknown ("Usual about 20% of sleep", or "No data yet" without a range).
     * {@code of} is "sleep" or "time in bed".
     */
    public static String note(double pct, String of, String usual) {
        if (Double.isNaN(pct)) return usual == null ? "No data yet" : "Usual " + usual + " of " + of;
        String s = Math.round(pct) + "% of " + of;
        return usual == null ? s : s + " (usual " + usual + ")";
    }

    /**
     * Time in bed without stage data (steps 0, issue #107), worked out from the stored stages so nights analysed
     * before show it too: {minutes in all, minutes of it before falling asleep}. Before falling asleep is 0 when
     * there was no sleep at all (that night has no latency). Both 0 without stages or time in bed.
     */
    public static double[] noData(int[] stages, double inBedMin) {
        if (stages == null || stages.length == 0 || Double.isNaN(inBedMin) || inBedMin <= 0) return new double[] {0, 0};
        double step = inBedMin / stages.length;
        int none = 0, before = 0, first = -1;
        for (int i = 0; i < stages.length; i++) {
            int c = stages[i];
            if (c >= 1 && c <= 3 && first < 0) first = i;
            if (c < 1 || c > 4) {
                none++;
                if (first < 0) before++;
            }
        }
        return new double[] {none * step, first < 0 ? 0 : before * step};
    }

    /**
     * The line under the stage rows when part of the night has no stage data, null when under a minute (issue #107):
     * "No data: there was no sleep stage for 20 min. It counts as time in bed, not as asleep or awake, so it lowers
     * efficiency. All of it was before you fell asleep, so latency includes it." Without any sleep ({@code slept}
     * false) efficiency and latency are unknown, so the line stops after "awake". Durations as on the rows
     * ({@link Texts#minutes}: "20 min", "1h 40m").
     */
    public static String noDataNote(double totalMin, double beforeMin, boolean slept) {
        long all = Double.isNaN(totalMin) ? 0 : Math.round(totalMin);
        if (all < 1) return null;
        long before = Double.isNaN(beforeMin) || !slept ? 0 : Math.min(all, Math.round(beforeMin));
        String s = "No data: there was no sleep stage for " + Texts.minutes(all) + ". It counts as time in bed, not as asleep or awake";
        if (!slept) return s + ".";
        s += ", so it lowers efficiency.";
        if (before >= all) return s + " All of it was before you fell asleep, so latency includes it.";
        if (before >= 1) return s + " " + Texts.minutes(before) + " of it was before you fell asleep, so latency includes that part.";
        return s;
    }

    /**
     * The minutes before falling asleep for {@link #noDataNote}, matching the shown No data row ({@code shownNone},
     * rounded together with the other rows): all of it when all the time without stage data was before sleep (so the
     * note says "All of it", review of #201), else the exact minutes but never more than the row shows.
     */
    public static double shownBefore(double noneMin, double beforeMin, double shownNone) {
        if (Double.isNaN(beforeMin) || Double.isNaN(shownNone)) return beforeMin;
        return beforeMin >= noneMin ? shownNone : Math.min(beforeMin, shownNone);
    }

    /**
     * Efficiency as the screens show it (issue #107): time asleep over time in bed, worked out from the stored
     * minutes, so nights analysed before (whose stored efficiency left out the time without stage data) agree
     * without a re analysis. NaN when the stored efficiency is unknown (no sleep at all) or there is under a minute
     * in bed. For nights analysed now it is the stored value.
     */
    public static double efficiency(double asleepMin, double inBedMin, double stored) {
        if (Double.isNaN(stored) || Double.isNaN(asleepMin) || Double.isNaN(inBedMin) || inBedMin < MIN_BASE) return Double.NaN;
        return 100 * Math.max(0, Math.min(asleepMin, inBedMin)) / inBedMin;
    }

    /**
     * Whole minutes for parts that belong to one shown total (issue #111): each part rounded down, the minutes still
     * missing to {@code total} go to the largest remainders (ties to the earlier part), so the parts add up to the
     * total and each one is within a minute of its exact value. Negative parts count as 0. A part that is exactly 0
     * stays 0 and a part of half a minute or more never shows 0 (a one minute awakening never reads "Awake 0h 0m"
     * next to a wake up, review of #201). Null when a part is NaN or the total cannot be reached that way (it is too
     * far from the parts rounded down, or only by breaking the two rules before); the caller then rounds on its own.
     */
    public static long[] roundTogether(double[] parts, long total) {
        int n = parts.length;
        long[] out = new long[n];
        double[] rest = new double[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(parts[i]) || Double.isInfinite(parts[i])) return null;
            double v = clean(parts[i]);
            out[i] = (long) Math.floor(v);
            rest[i] = v - out[i];
            given += out[i];
        }
        long missing = total - given;
        if (missing < -n || missing > n) return null;
        // a part that is exactly 0 never gets a minute (no "No data 1 min" or "Deep 0h 1m" on a night without it);
        // the others take it, first a part of half a minute or more that is still 0, a second minute only once each
        // has had one
        int[] got = new int[n];
        for (long k = 0; k < missing; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!(clean(parts[i]) > 0)) continue;
                boolean need = out[i] == 0 && clean(parts[i]) >= 0.5;
                if (best < 0) { best = i; continue; }
                boolean bestNeed = out[best] == 0 && clean(parts[best]) >= 0.5;
                if (need != bestNeed) { if (need) best = i; continue; }
                if (got[i] < got[best] || (got[i] == got[best] && rest[i] > rest[best])) best = i;
            }
            if (best < 0) return null;
            out[best]++;
            got[best]++;
        }
        // a part of half a minute or more that is still 0 here would hide it (for example a short awakening)
        for (int i = 0; i < n; i++) if (out[i] == 0 && clean(parts[i]) >= 0.5) return null;
        // a total below the parts rounded down (the shown time in bed comes from rounded clock times): the minutes
        // come off the smallest remainders, one per part, never below 0, and never from a part shown as 1 whose exact
        // value is half a minute or more (a 1 minute awakening must not show 0 next to its wake up)
        for (long k = 0; k > missing; k--) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (out[i] <= 0 || rest[i] >= 2 || (out[i] == 1 && clean(parts[i]) >= 0.5)) continue;
                if (best < 0 || rest[i] < rest[best]) best = i;
            }
            if (best < 0) return null;
            out[best]--;
            rest[best] = 2;
        }
        return out;
    }

    /**
     * The minutes the stage rows show, {deep, light, rem, awake, no data}, rounded so they add up to the totals shown
     * next to them (issue #111); display only, the stored minutes stay exact. Deep, light and REM add up to the shown
     * time asleep ({@code round(asleepMin)}); awake and no data add up to {@code inBedShown} minus that. A group whose
     * exact minutes do not add up to its total within a minute (a mixed or older night) is rounded to its own sum
     * instead, so it never pretends to add up. {@code inBedShown} below 0 means no time in bed is shown (Further
     * analysis): awake and no data are then rounded each on its own. NaN (a missing stage) stays NaN and rounds the
     * rest of its group on its own.
     */
    public static double[] shownMinutes(double deep, double light, double rem, double awake, double noData,
                                        double asleepMin, double inBedMin, long inBedShown) {
        double[] sleep = {deep, light, rem};
        double sleepSum = deep + light + rem;
        long sleepTotal = !Double.isNaN(asleepMin) && Math.abs(sleepSum - asleepMin) < 1 ? Math.round(asleepMin) : -1;
        double[] s = group(sleep, sleepTotal);
        double[] rest = {awake, noData};
        long restTotal = -1;
        if (inBedShown >= 0 && !Double.isNaN(sleepSum) && !Double.isNaN(inBedMin)
                && Math.abs(sleepSum + awake + noData - inBedMin) < 1 && sleepTotal >= 0) {
            restTotal = inBedShown - (long) (s[0] + s[1] + s[2]);
        }
        double[] r = inBedShown < 0 ? new double[] {round(awake), round(noData)} : group(rest, restTotal);
        return new double[] {s[0], s[1], s[2], r[0], r[1]};
    }

    /** Rounded together to {@code total} when it can be reached, else to the parts' own rounded sum; NaN rounds alone. */
    private static double[] group(double[] parts, long total) {
        double sum = 0;
        for (double v : parts) sum += Double.isNaN(v) ? Double.NaN : clean(v);
        long[] t = total >= 0 ? roundTogether(parts, total) : null;
        if (t == null && !Double.isNaN(sum)) t = roundTogether(parts, Math.round(sum));
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = t != null ? t[i] : round(parts[i]);
        return out;
    }

    private static double round(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(clean(v));
    }

    private static double clean(double v) {
        return v < 0 ? 0 : v;
    }
}
