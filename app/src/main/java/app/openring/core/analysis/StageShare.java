package app.openring.core.analysis;

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
     * (largest remainder). Missing or negative minutes count as 0. Returns null when there is under a minute of sleep.
     */
    public static int[] sleepShares(double deep, double light, double rem) {
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
    public static final String USUAL_DEEP = "13 to 23%", USUAL_REM = "about a fifth";

    /**
     * One line under a stage tile: "10% of sleep (usual 13 to 23%)", "63% of sleep" without a range, and only the range
     * when the percentage is unknown ("Usual about a fifth of sleep", or "Average per night" without a range).
     * {@code of} is "sleep" or "time in bed".
     */
    public static String note(double pct, String of, String usual) {
        if (Double.isNaN(pct)) return usual == null ? "Average per night" : "Usual " + usual + " of " + of;
        String s = Math.round(pct) + "% of " + of;
        return usual == null ? s : s + " (usual " + usual + ")";
    }

    private static double clean(double v) {
        return Double.isNaN(v) || v < 0 ? 0 : v;
    }
}
