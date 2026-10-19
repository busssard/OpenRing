package app.openring.core.analysis;

import java.util.Arrays;

/**
 * "Hot and cold" for the ring finder (issue #74): turns the noisy signal strength (RSSI, dBm) of
 * the ring's advertisements into a steady value, a closeness band that does not flicker at a
 * border, a fill for the meter and a click interval for the Geiger counter sound.
 *
 * Smoothing: the median of the last {@link #MEDIAN_N} readings removes single spikes and dips,
 * then an average that moves {@link #FOLLOW} of the way to each new median keeps it calm but quick.
 * It tells warmer or colder; it cannot give metres (walls, blankets, the body and the ring's
 * angle move the signal by 6 to 10 dB).
 */
public final class Proximity {
    public static final int MEDIAN_N = 5;
    public static final double FOLLOW = 0.35;
    /** Without a reading for this long the ring counts as not heard and the smoothing starts over. */
    public static final long LOST_MS = 10000;

    public static final int NOT_HEARD = 0, FAR = 1, NEARBY = 2, ROOM = 3, CLOSE = 4;
    /** Lower borders of FAR / NEARBY, NEARBY / ROOM and ROOM / CLOSE in dBm. */
    public static final double[] BORDERS = {-84, -72, -60};
    /** The band changes only once the value is this far past a border. */
    public static final double HYSTERESIS = 4;
    /** The meter is empty at {@link #EMPTY} dBm and full at {@link #FULL} dBm. */
    public static final double EMPTY = -100, FULL = -45;
    /** Time between clicks: slowest when the meter is empty, fastest when full. */
    public static final long CLICK_SLOW_MS = 1200, CLICK_FAST_MS = 70;

    private final double[] last = new double[MEDIAN_N];
    private int count;
    private double value = Double.NaN;
    private long lastAt = Long.MIN_VALUE;
    private int band = NOT_HEARD;

    /** Adds one reading at {@code atMs} (any monotonic clock) and returns the smoothed value. */
    public double add(long atMs, double rssi) {
        if (lastAt != Long.MIN_VALUE && atMs - lastAt >= LOST_MS) reset();
        lastAt = atMs;
        last[count % MEDIAN_N] = rssi;
        count++;
        double m = median(last, Math.min(count, MEDIAN_N));
        value = Double.isNaN(value) ? m : value + FOLLOW * (m - value);
        band = bandFor(value, band);
        return value;
    }

    /** Call regularly (about once a second) so a ring that went quiet turns into "not heard". */
    public void tick(long nowMs) {
        if (lastAt != Long.MIN_VALUE && nowMs - lastAt >= LOST_MS) reset();
    }

    private void reset() {
        count = 0;
        value = Double.NaN;
        band = NOT_HEARD;
    }

    /** The smoothed signal in dBm, NaN while not heard. */
    public double value() {
        return value;
    }

    public int band() {
        return band;
    }

    /** Milliseconds since the last reading, or -1 if nothing was heard yet. */
    public long sinceLast(long nowMs) {
        return lastAt == Long.MIN_VALUE ? -1 : nowMs - lastAt;
    }

    static double median(double[] v, int n) {
        double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** The band of {@code v} without hysteresis. */
    public static int bandOf(double v) {
        if (Double.isNaN(v)) return NOT_HEARD;
        int b = FAR;
        for (double border : BORDERS) if (v >= border) b++;
        return b;
    }

    /** The band of {@code v} when the label shows {@code current}: it moves only {@link #HYSTERESIS} dB past a border. */
    public static int bandFor(double v, int current) {
        int plain = bandOf(v);
        if (current == NOT_HEARD || plain == NOT_HEARD || plain == current) return plain;
        if (plain > current) {
            // closer: past the upper border of the current band by the margin, else stay
            int b = current;
            while (b < plain && v >= BORDERS[b - 1] + HYSTERESIS) b++;
            return b;
        }
        int b = current;
        while (b > plain && v < BORDERS[b - 2] - HYSTERESIS) b--;
        return b;
    }

    /** 0 (empty) to 1 (full) for the meter; 0 while not heard. */
    public static double fill(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, (v - EMPTY) / (FULL - EMPTY)));
    }

    /** Milliseconds between clicks, geometric from slow (empty) to fast (full); -1 means silent. */
    public static long clickIntervalMs(double v) {
        if (Double.isNaN(v)) return -1;
        double f = fill(v);
        return Math.round(CLICK_SLOW_MS * Math.pow((double) CLICK_FAST_MS / CLICK_SLOW_MS, f));
    }

    /** The label of a band, as shown under the meter. */
    public static String label(int band) {
        switch (band) {
            case CLOSE: return "Very close (within an arm)";
            case ROOM: return "In this room";
            case NEARBY: return "Nearby";
            case FAR: return "Far or behind walls";
            default: return "Not heard for 10 seconds";
        }
    }
}
