package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * How much detail a hypnogram keeps (issue #9). Merges stage runs shorter than a minimum into a
 * neighbour, with a separate, lower minimum for awake runs so real awakenings survive.
 *
 * This changes detail, not accuracy: the stager's estimates stay what they are, only short runs are
 * folded into the stage around them. Unknown epochs (0) are never changed and never absorb anything.
 */
public final class StageFilter {
    private StageFilter() {}

    /** Detail levels shown in Settings. Index is what {@code Prefs.stageDetail()} stores. */
    public static final int DETAILED = 0, BALANCED = 1, CALM = 2;
    public static final String[] NAMES = {"Detailed", "Balanced", "Calm"};
    /** Minimum run length in 30 second epochs for sleep stages and for awake, per level. */
    static final int[] MIN_STAGE = {1, 6, 10};
    static final int[] MIN_WAKE = {1, 2, 4};
    /**
     * Version of the merge rules. Nights analysed with an older one are analysed again once
     * ({@code Analyzer}). 1: issue #9, 2: sleep never merges into awake (issue #28).
     */
    public static final int VERSION = 2;

    public static int minStageEpochs(int level) {
        return MIN_STAGE[clampLevel(level)];
    }

    public static int minWakeEpochs(int level) {
        return MIN_WAKE[clampLevel(level)];
    }

    public static int clampLevel(int level) {
        return Math.max(0, Math.min(NAMES.length - 1, level));
    }

    /** Filter with one of the preset levels. */
    public static int[] filter(int[] stages, int level) {
        return filter(stages, minStageEpochs(level), minWakeEpochs(level));
    }

    /**
     * Repeatedly merges the shortest run below its minimum ({@code minWakeEpochs} for awake,
     * {@code minStageEpochs} for deep, light and REM) into the neighbour closest in depth
     * (deep, light, REM, awake); a tie goes to the longer neighbour, then to the earlier one.
     * A deep, light or REM run only merges into a sleep neighbour, never into awake (issue #28); with
     * none it stays, and is looked at again after later merges. Awake runs merge only between two
     * known runs. Unknown epochs never absorb anything. So every awake epoch of the result was awake
     * in the input, awake runs are kept or removed whole (never lengthened or joined), and falling
     * asleep and waking up do not move. The result has the same length as the input.
     */
    public static int[] filter(int[] stages, int minStageEpochs, int minWakeEpochs) {
        int[] out = stages.clone();
        if (minStageEpochs <= 1 && minWakeEpochs <= 1) return out;
        List<int[]> runs = runs(out); // {code, start, end}
        while (true) {
            int pick = -1, pickLen = Integer.MAX_VALUE;
            int target = -1;
            for (int i = 0; i < runs.size(); i++) {
                int[] r = runs.get(i);
                if (r[0] == 0) continue;
                int len = r[2] - r[1];
                int min = r[0] == Stager.WAKE ? minWakeEpochs : minStageEpochs;
                if (len >= min || len >= pickLen) continue;
                int t = neighbour(runs, i);
                if (t < 0) continue;
                // awake at the start or end of the night (or next to missing data) stays: merging it
                // would move falling asleep or waking up into time the stager marked as awake
                if (r[0] == Stager.WAKE && !bothKnown(runs, i)) continue;
                pick = i;
                pickLen = len;
                target = t;
            }
            if (pick < 0) break;
            int code = runs.get(target)[0];
            int[] r = runs.get(pick);
            for (int k = r[1]; k < r[2]; k++) out[k] = code;
            r[0] = code;
            runs = join(runs);
        }
        return out;
    }

    static boolean bothKnown(List<int[]> runs, int i) {
        return i > 0 && runs.get(i - 1)[0] != 0 && i + 1 < runs.size() && runs.get(i + 1)[0] != 0;
    }

    /**
     * Index of the run that run {@code i} should merge into, or -1 when it has none: unknown runs
     * never absorb anything, and a sleep run never merges into awake.
     */
    static int neighbour(List<int[]> runs, int i) {
        int[] r = runs.get(i);
        int a = i > 0 && canAbsorb(runs.get(i - 1)[0], r[0]) ? i - 1 : -1;
        int b = i + 1 < runs.size() && canAbsorb(runs.get(i + 1)[0], r[0]) ? i + 1 : -1;
        if (a < 0) return b;
        if (b < 0) return a;
        int da = Math.abs(depth(runs.get(a)[0]) - depth(r[0]));
        int db = Math.abs(depth(runs.get(b)[0]) - depth(r[0]));
        if (da != db) return da < db ? a : b;
        int la = runs.get(a)[2] - runs.get(a)[1], lb = runs.get(b)[2] - runs.get(b)[1];
        return lb > la ? b : a;
    }

    /** Whether a run of stage {@code into} may take over a run of stage {@code from}. */
    static boolean canAbsorb(int into, int from) {
        return into != 0 && (into != Stager.WAKE || from == Stager.WAKE);
    }

    /** Position on the depth scale: deep 0, light 1, REM 2, awake 3. */
    static int depth(int code) {
        switch (code) {
            case Stager.DEEP: return 0;
            case Stager.LIGHT: return 1;
            case Stager.REM: return 2;
            default: return 3;
        }
    }

    static List<int[]> runs(int[] s) {
        List<int[]> out = new ArrayList<int[]>();
        int i = 0;
        while (i < s.length) {
            int j = i;
            while (j < s.length && s[j] == s[i]) j++;
            out.add(new int[] {s[i], i, j});
            i = j;
        }
        return out;
    }

    static List<int[]> join(List<int[]> runs) {
        List<int[]> out = new ArrayList<int[]>();
        for (int[] r : runs) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last[0] == r[0]) last[2] = r[2];
            else out.add(r);
        }
        return out;
    }

    /** Number of times the stage changes between known epochs (for the Settings preview). */
    public static int changes(int[] s) {
        int c = 0, prev = 0;
        for (int x : s) {
            if (x == 0) continue;
            if (prev != 0 && x != prev) c++;
            prev = x;
        }
        return c;
    }

    /** Number of awake runs between the first and last sleep epoch, of any length. */
    public static int wakeRuns(int[] s) {
        int first = -1, last = -1;
        for (int i = 0; i < s.length; i++) {
            if (s[i] >= 1 && s[i] <= 3) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return 0;
        int c = 0;
        for (int i = first; i <= last; i++) if (s[i] == Stager.WAKE && s[i - 1] != Stager.WAKE) c++;
        return c;
    }
}
