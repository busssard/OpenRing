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
