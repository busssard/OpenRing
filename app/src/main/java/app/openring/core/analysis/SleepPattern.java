package app.openring.core.analysis;

/**
 * The average night: for every 10 minutes of clock time from 20:00 to 12:00, the share of nights
 * spent in each stage at that moment. Nights are laid over each other by clock time, so the
 * result also shows when bedtime and waking usually happen (the share in bed rises and falls).
 */
public final class SleepPattern {
    public static final int BIN_MIN = 10;
    /** Clock time of bin 0, minutes after midnight (20:00). */
    public static final int ANCHOR_MIN = 20 * 60;
    public static final int SPAN_MIN = 16 * 60;
    public static final int BINS = SPAN_MIN / BIN_MIN;

    /** Minutes per bin and stage summed over all nights; index 1 deep, 2 light, 3 REM, 4 awake. */
    private final double[][] minutes = new double[BINS][5];
    private int nights;

    /**
     * Add one night.
     *
     * @param startClockMin start of the sleep window as minutes after local midnight (0 to 1440)
     * @param stages        30 second (or {@code epochS}) stage codes from the window start
     * @param epochS        length of one stage cell in seconds
     */
    public void add(double startClockMin, int[] stages, double epochS) {
        if (stages == null || stages.length == 0 || epochS <= 0) return;
        nights++;
        // minutes after 20:00; an evening start before 16:00 cannot be a night, a start after
        // 16:00 belongs to the evening that follows midnight's previous day
        double rel = startClockMin - ANCHOR_MIN;
        if (rel < -4 * 60) rel += 24 * 60;
        double step = epochS / 60.0;
        for (int i = 0; i < stages.length; i++) {
            int s = stages[i];
            if (s < 1 || s > 4) continue;
            int bin = (int) Math.floor((rel + (i + 0.5) * step) / BIN_MIN);
            if (bin < 0 || bin >= BINS) continue;
            minutes[bin][s] += step;
        }
    }

    public int nights() {
        return nights;
    }

    /** Share of nights (0 to 1) in {@code stage} during {@code bin}. */
    public double share(int bin, int stage) {
        return nights == 0 ? 0 : Math.min(1, minutes[bin][stage] / (nights * (double) BIN_MIN));
    }

    /** Share of nights in bed (any stage) during {@code bin}. */
    public double inBed(int bin) {
        double s = 0;
        for (int k = 1; k <= 4; k++) s += share(bin, k);
        return Math.min(1, s);
    }

    /** Clock minutes after midnight at the start of {@code bin}. */
    public static int clockMin(int bin) {
        return (ANCHOR_MIN + bin * BIN_MIN) % (24 * 60);
    }

    /**
     * First and last bin worth drawing: where at least {@code minShare} of nights are in bed,
     * widened by an hour on each side and snapped to whole hours. {0, BINS} when empty.
     */
    public int[] range(double minShare) {
        int a = -1, b = -1;
        for (int i = 0; i < BINS; i++) {
            if (inBed(i) >= minShare) {
                if (a < 0) a = i;
                b = i;
            }
        }
        if (a < 0) return new int[] {0, BINS};
        int perHour = 60 / BIN_MIN;
        a = Math.max(0, (a / perHour - 1) * perHour);
        b = Math.min(BINS, ((b + perHour) / perHour + 1) * perHour);
        return new int[] {a, b};
    }
}
