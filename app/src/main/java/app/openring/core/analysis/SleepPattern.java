package app.openring.core.analysis;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRules;

/**
 * The average night: for every 10 minutes of clock time, round the whole clock from 20:00, the share
 * of nights spent in each stage at that moment. Nights are laid over each other by clock time, so
 * the result also shows when bedtime and waking usually happen (the share in bed rises and falls).
 * Each stage cell counts at its own clock time, so a night the clocks change keeps its real clock
 * times (issue #137): in autumn both passes of the repeated hour share its bins, in spring the
 * skipped hour gets nothing from that night. Sleep at any time of day is kept (issue #164); the
 * chart draws only the part people are in bed ({@link #range}).
 */
public final class SleepPattern {
    public static final int BIN_MIN = 10;
    /** Clock time of bin 0, minutes after midnight (20:00). */
    public static final int ANCHOR_MIN = 20 * 60;
    /** The whole clock: 24 hours. */
    public static final int SPAN_MIN = 24 * 60;
    public static final int BINS = SPAN_MIN / BIN_MIN;

    /** Minutes per bin and stage summed over all nights; index 1 deep, 2 light, 3 REM, 4 awake. */
    private final double[][] minutes = new double[BINS][5];
    private int nights;

    /**
     * Add one night by the clock minute of its start, every cell counted on from there (no clock
     * change inside the night; nights with a zone use {@link #add(double, int[], double, ZoneId)}).
     *
     * @param startClockMin start of the sleep window as minutes after local midnight (0 to 1440)
     * @param stages        30 second (or {@code epochS}) stage codes from the window start
     * @param epochS        length of one stage cell in seconds
     */
    public void add(double startClockMin, int[] stages, double epochS) {
        if (stages == null || stages.length == 0 || epochS <= 0) return;
        nights++;
        double step = epochS / 60.0;
        double[][] night = new double[BINS][5];
        for (int i = 0; i < stages.length; i++) put(night, startClockMin + (i + 0.5) * step, stages[i], step);
        addNight(night);
    }

    /**
     * Add one night, each stage cell at the local clock time of its middle in {@code zone} (issue #137).
     *
     * @param startUnix start of the sleep window, unix seconds
     */
    public void add(double startUnix, int[] stages, double epochS, ZoneId zone) {
        if (stages == null || stages.length == 0 || epochS <= 0) return;
        nights++;
        ZoneRules rules = zone.getRules();
        double step = epochS / 60.0;
        double[][] night = new double[BINS][5];
        for (int i = 0; i < stages.length; i++) {
            double t = startUnix + (i + 0.5) * epochS;
            int off = rules.getOffset(Instant.ofEpochSecond((long) Math.floor(t))).getTotalSeconds();
            put(night, (t + off) / 60.0, stages[i], step);
        }
        addNight(night);
    }

    /**
     * Adds one night's bins. A bin where this night has more than 10 minutes (both passes of the
     * repeated hour when the clocks go back) counts as 10 minutes with the stage mix of all of them,
     * so one night is never more than one night in bed and the other nights' shares stay right.
     */
    private void addNight(double[][] night) {
        for (int b = 0; b < BINS; b++) {
            double[] m = night[b];
            double total = m[1] + m[2] + m[3] + m[4];
            if (total <= 0) continue;
            double f = Math.min(1, BIN_MIN / total);
            for (int k = 1; k <= 4; k++) minutes[b][k] += m[k] * f;
        }
    }

    /** Adds {@code step} minutes of {@code stage} at clock minute {@code clock} (any value, taken round the clock). */
    private static void put(double[][] into, double clock, int stage, double step) {
        if (stage < 1 || stage > 4) return;
        double rel = clock - ANCHOR_MIN;
        rel = ((rel % SPAN_MIN) + SPAN_MIN) % SPAN_MIN;
        int bin = Math.min(BINS - 1, (int) Math.floor(rel / BIN_MIN));
        into[bin][stage] += step;
    }

    public int nights() {
        return nights;
    }

    /** {@code bin} taken round the clock, so a chart range that passes 20:00 can go on counting. */
    private static int wrap(int bin) {
        return ((bin % BINS) + BINS) % BINS;
    }

    /** Share of nights (0 to 1) in {@code stage} during {@code bin}. */
    public double share(int bin, int stage) {
        return nights == 0 ? 0 : Math.min(1, minutes[wrap(bin)][stage] / (nights * (double) BIN_MIN));
    }

    /** Share of nights in bed (any stage) during {@code bin}. */
    public double inBed(int bin) {
        double s = 0;
        for (int k = 1; k <= 4; k++) s += share(bin, k);
        return Math.min(1, s);
    }

    /** Clock minutes after midnight at the start of {@code bin} (any bin, taken round the clock). */
    public static int clockMin(int bin) {
        return (ANCHOR_MIN + wrap(bin) * BIN_MIN) % (24 * 60);
    }

    /**
     * First and last bin worth drawing: where at least {@code minShare} of nights are in bed,
     * widened by an hour on each side and snapped to whole hours, at most the whole clock. It starts
     * after the longest stretch with fewer nights in bed, so day sleep is drawn as well as night sleep;
     * the end can pass {@link #BINS} (the chart goes on past 20:00). {0, BINS} when nobody is in bed.
     */
    public int[] range(double minShare) {
        boolean[] on = new boolean[BINS];
        int count = 0;
        for (int i = 0; i < BINS; i++) {
            on[i] = inBed(i) >= minShare;
            if (on[i]) count++;
        }
        if (count == 0 || count == BINS) return new int[] {0, BINS};
        // the longest run of empty bins round the clock; the first one found wins a tie
        int bestStart = 0, bestLen = 0;
        for (int i = 0; i < BINS; i++) {
            if (on[i] || !on[wrap(i - 1)]) continue; // a run starts after a bin in bed
            int len = 0;
            while (len < BINS && !on[wrap(i + len)]) len++;
            if (len > bestLen) {
                bestLen = len;
                bestStart = i;
            }
        }
        int first = wrap(bestStart + bestLen), last = first + BINS - bestLen - 1;
        int perHour = 60 / BIN_MIN;
        int a = (Math.floorDiv(first, perHour) - 1) * perHour;
        int b = ((last + perHour) / perHour + 1) * perHour;
        if (a < 0) {
            a += BINS;
            b += BINS;
        }
        if (b - a > BINS) b = a + BINS;
        return new int[] {a, b};
    }
}
