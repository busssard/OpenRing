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
     * "No data: the ring gave no sleep stage for 20 min. It counts as time in bed, not as asleep or awake, so it
     * lowers efficiency. All of it was before you fell asleep, so latency includes it."
     */
    public static String noDataNote(double totalMin, double beforeMin) {
        long all = Double.isNaN(totalMin) ? 0 : Math.round(totalMin);
        if (all < 1) return null;
        long before = Double.isNaN(beforeMin) ? 0 : Math.min(all, Math.round(beforeMin));
        String s = "No data: the ring gave no sleep stage for " + all + " min. It counts as time in bed, not as asleep or awake, "
                + "so it lowers efficiency.";
        if (before >= all) return s + " All of it was before you fell asleep, so latency includes it.";
        if (before >= 1) return s + " " + before + " min of it was before you fell asleep, so latency includes that part.";
        return s;
    }

    private static double clean(double v) {
        return v < 0 ? 0 : v;
    }
}
