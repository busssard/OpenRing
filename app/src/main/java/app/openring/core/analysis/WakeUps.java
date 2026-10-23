package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * The one definition of a wake up (issue #70): being awake for at least {@link #MIN_S} seconds
 * between falling asleep and waking up. The Sleep page number, the score, the night timeline and the
 * Settings preview all count with it, so they agree on the same stages. Awake pieces shorter than
 * that stay on the chart and are counted separately ({@link #shortOnes}), never dropped silently.
 */
public final class WakeUps {
    private WakeUps() {}

    /** Shortest wake up in seconds. */
    public static final double MIN_S = 60;

    /** Steps a wake up needs at this step length (2 for 30 second steps). */
    public static int minSteps(double stepS) {
        if (!(stepS > 0)) return 1;
        // the small allowance keeps rounding in stepS (end minus start over steps) from adding a step
        return Math.max(1, (int) Math.ceil(MIN_S / stepS - 1e-6));
    }

    /** The wake ups as {start step, end step (exclusive)}, in time order. */
    public static List<int[]> list(int[] stages, double stepS) {
        return runs(stages, stepS, true);
    }

    /** Number of wake ups. */
    public static int count(int[] stages, double stepS) {
        return list(stages, stepS).size();
    }

    /** Awake pieces between falling asleep and waking up that are too short to be a wake up. */
    public static int shortOnes(int[] stages, double stepS) {
        return runs(stages, stepS, false).size();
    }

    /** A time rounded to the nearest whole minute, so clock times and lengths shown together agree. */
    public static double roundMin(double unix) {
        return Math.round(unix / 60.0) * 60.0;
    }

    /**
     * Minutes shown for a span from {@code a} to {@code b}: the difference of the two rounded clock
     * times, so "Awake for 3 min, back asleep 02:17" after "02:14" adds up. A span of a minute or
     * more always rounds to at least 1 min, so a wake up never shows as 0 min.
     */
    public static long shownMinutes(double a, double b) {
        return Math.max(0, Math.round((roundMin(b) - roundMin(a)) / 60.0));
    }

    /**
     * What a stage detail level adds to the wake up rule, or null when it keeps every wake up of a
     * minute or more (Detailed, Balanced). Calm folds being awake under 2 minutes into sleep.
     */
    public static String levelNote(int level) {
        int lv = StageFilter.clampLevel(level);
        int sec = StageFilter.minWakeEpochs(lv) * 30;
        if (sec <= MIN_S) return null;
        return StageFilter.NAMES[lv] + " stage detail folds being awake under " + (sec / 60) + " minutes into sleep.";
    }

    /**
     * The wake up rule of a level for short lines (issue #128): "a minute or more" (Detailed, Balanced)
     * or "2 min or more with Calm", so a line under a wake up count says what it counted.
     */
    public static String rule(int level) {
        int min = shortestKeptMin(level);
        return min <= 1 ? "a minute or more" : min + " min or more with " + StageFilter.NAMES[StageFilter.clampLevel(level)];
    }

    /** Shortest wake up a level keeps, in whole minutes, for short labels. */
    public static int shortestKeptMin(int level) {
        return Math.max(1, StageFilter.minWakeEpochs(StageFilter.clampLevel(level)) * 30 / 60);
    }

    static List<int[]> runs(int[] st, double stepS, boolean wakeUps) {
        List<int[]> out = new ArrayList<int[]>();
        int first = -1, last = -1;
        for (int i = 0; i < st.length; i++) {
            if (st[i] >= 1 && st[i] <= 3) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return out;
        int min = minSteps(stepS);
        int i = first;
        while (i <= last) {
            if (st[i] != Stager.WAKE) { i++; continue; }
            int j = i;
            while (j <= last && st[j] == Stager.WAKE) j++;
            if ((j - i >= min) == wakeUps) out.add(new int[] {i, j});
            i = j;
        }
        return out;
    }
}
