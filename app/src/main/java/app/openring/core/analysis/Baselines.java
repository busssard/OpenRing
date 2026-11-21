package app.openring.core.analysis;

/**
 * The personal baselines of readiness (issue #292, phase 1.5): each value of a night against the main nights before it.
 * Plain arrays in, newest last, one entry per earlier main night (NaN where that night has no value), so the JVM tests
 * reach it and phase 2 ("each night against your own usual") uses the same rules instead of a copy.
 *
 * <ul>
 * <li>Lowest heart rate: mean and standard deviation of the last {@link #RHR_NIGHTS} nights.</li>
 * <li>HRV: a weighted average of the last {@link #HRV_RECENT_NIGHTS} nights and tonight (newest heaviest, weights 1, 2, 3
 * and so on, nights without a value skipped) against the mean of the last {@link #HRV_LONG_NIGHTS} nights.</li>
 * <li>Temperature: tonight minus the {@link #usual} value, the median of the last {@link #USUAL_NIGHTS} nights.</li>
 * <li>Where in the night the lowest heart rate came: 0 at sleep start, 1 at wake up.</li>
 * </ul>
 * Each baseline needs at least {@link #MIN_NIGHTS} nights with a value in its window; with fewer it stays NaN and its
 * contributor drops out of readiness. These are exactly the rules {@code data/Analyzer} had inline before, with the same
 * windows and the same order of floating point operations, so every stored readiness stays the same to the last bit
 * (tests/java/BaselinesTest.java and the pipeline test's fingerprints).
 */
public final class Baselines {
    private Baselines() {}

    /** Nights of the lowest heart rate baseline. */
    public static final int RHR_NIGHTS = 14;
    /** Earlier nights in the recent HRV average (tonight makes it two weeks). */
    public static final int HRV_RECENT_NIGHTS = 13;
    /** Nights of the long term HRV baseline, also the most any baseline reads: pass at least this many. */
    public static final int HRV_LONG_NIGHTS = 90;
    /** Nights of the usual value (the temperature baseline, and phase 2's "your usual"). */
    public static final int USUAL_NIGHTS = 30;
    /** Nights with a value a baseline needs in its window. */
    public static final int MIN_NIGHTS = 3;

    /** The baselines of one night, each NaN when it has too few nights (or tonight has no value where one is needed). */
    public static final class Result {
        public double rhrBaseline = Double.NaN, rhrSd = Double.NaN;
        public double hrvRecent = Double.NaN, hrvBaseline = Double.NaN;
        public double tempDev = Double.NaN;
        public double recoveryFraction = Double.NaN;

        /** Copies the values into a readiness input (they are its fields of the same names). */
        public void into(Scores.ReadinessInput ri) {
            ri.rhrBaseline = rhrBaseline;
            ri.rhrSd = rhrSd;
            ri.hrvRecent = hrvRecent;
            ri.hrvBaseline = hrvBaseline;
            ri.tempDev = tempDev;
            ri.recoveryFraction = recoveryFraction;
        }
    }

    /**
     * The baselines of tonight against the earlier main nights.
     *
     * @param rhrBefore the earlier nights' lowest heart rate, newest last (only the last {@link #RHR_NIGHTS} count)
     * @param hrvBefore the earlier nights' average HRV, newest last (the last {@link #HRV_LONG_NIGHTS} count)
     * @param tempBefore the earlier nights' temperature, newest last (the last {@link #USUAL_NIGHTS} count)
     * @param lowestHrTime when tonight's lowest heart rate came, sleepStart and sleepEnd tonight's sleep, all unix seconds
     */
    public static Result readiness(double[] rhrBefore, double[] hrvBefore, double[] tempBefore, double hrvTonight, double tempTonight,
            double lowestHrTime, double sleepStart, double sleepEnd) {
        Result r = new Result();
        double[] rhrHist = last(rhrBefore, RHR_NIGHTS);
        if (Stats.countFinite(rhrHist) >= MIN_NIGHTS) {
            r.rhrBaseline = Stats.mean(rhrHist);
            r.rhrSd = Stats.sd(rhrHist);
        }
        double[] hrv14 = last(hrvBefore, HRV_RECENT_NIGHTS);
        double[] hrv90 = last(hrvBefore, HRV_LONG_NIGHTS);
        if (!Double.isNaN(hrvTonight) && Stats.countFinite(hrv90) >= MIN_NIGHTS) {
            // two week weighted average (newest heaviest) vs the long term average
            double ws = 0, w = 0;
            double[] recent = new double[hrv14.length + 1];
            System.arraycopy(hrv14, 0, recent, 0, hrv14.length);
            recent[hrv14.length] = hrvTonight;
            for (int i = 0; i < recent.length; i++) {
                if (Double.isNaN(recent[i])) continue;
                double wi = 1 + i;
                ws += wi * recent[i];
                w += wi;
            }
            r.hrvRecent = ws / w;
            r.hrvBaseline = Stats.mean(hrv90);
        }
        if (!Double.isNaN(tempTonight)) {
            double u = usual(tempBefore);
            if (!Double.isNaN(u)) r.tempDev = tempTonight - u;
        }
        if (!Double.isNaN(lowestHrTime) && sleepEnd > sleepStart) {
            r.recoveryFraction = Stats.clamp((lowestHrTime - sleepStart) / (sleepEnd - sleepStart), 0, 1);
        }
        return r;
    }

    /**
     * The usual value of one measure before a night: the median of the last {@link #USUAL_NIGHTS} earlier nights (newest
     * last, NaN for a night without the value), NaN when fewer than {@link #MIN_NIGHTS} of them have one. A night without
     * the value still counts as one of the 30, so the window is the same month of nights for every measure; this is the
     * temperature baseline of readiness, and the baseline phase 2 compares each night with (issue #292).
     */
    public static double usual(double[] earlier) {
        double[] window = last(earlier, USUAL_NIGHTS);
        return Stats.countFinite(window) >= MIN_NIGHTS ? Stats.median(window) : Double.NaN;
    }

    /** The last {@code n} values (all of them when there are fewer), as {@code Analyzer.lastValues} takes them from the nights. */
    static double[] last(double[] values, int n) {
        int from = Math.max(0, values.length - n);
        double[] out = new double[values.length - from];
        System.arraycopy(values, from, out, 0, out.length);
        return out;
    }
}
