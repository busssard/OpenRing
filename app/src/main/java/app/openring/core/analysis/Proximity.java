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
    /**
     * Readings outside {@link #MIN_DBM} to {@link #MAX_DBM} cannot be real (issue #95): 127 is the
     * Bluetooth controller's "not available" and -127 Android's lowest placeholder; phone receivers
     * stop decoding at about -100 to -105 dBm, and a received signal never reaches the ring's own
     * sending power (about 0 dBm).
     */
    public static final double MIN_DBM = -120, MAX_DBM = -1;

    private final double[] last = new double[MEDIAN_N];
    private int count;
    private double value = Double.NaN;
    private long lastAt = Long.MIN_VALUE;
    private int band = NOT_HEARD;

    /** Whether {@code dBm} can be a real signal strength (see {@link #MIN_DBM}). */
    public static boolean plausible(double dBm) {
        return dBm >= MIN_DBM && dBm <= MAX_DBM; // false for NaN too
    }

    /**
     * Which of several rings heard in one scan is the closest (setup without a saved ring, issue #105): the
     * index of the strongest {@link #plausible} signal, so a 127 ("not available") never beats a real reading.
     * When none is plausible, the highest raw value as before; -1 for an empty list.
     */
    public static int strongest(int[] rssi) {
        int best = -1, raw = -1;
        for (int i = 0; i < rssi.length; i++) {
            if (raw < 0 || rssi[i] > rssi[raw]) raw = i;
            if (plausible(rssi[i]) && (best < 0 || rssi[i] > rssi[best])) best = i;
        }
        return best >= 0 ? best : raw;
    }

    /**
     * Whether a new advertisement of a ring replaces the one kept for it in a scan: always, unless the new
     * signal is not {@link #plausible} and the kept one is (the kept reading stays comparable, issue #105).
     */
    public static boolean replaces(int keptRssi, int newRssi) {
        return plausible(newRssi) || !plausible(keptRssi);
    }

    /**
     * Adds one reading at {@code atMs} (any monotonic clock) and returns the smoothed value. A reading
     * that is not {@link #plausible} is ignored: it changes nothing and does not count as heard.
     */
    public double add(long atMs, double rssi) {
        if (!plausible(rssi)) return value;
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

    /** The signal chart shows at least this many dB, so a 1 dB wobble looks like the noise it is. */
    public static final double CHART_MIN_SPAN = 20;

    /**
     * The y range {lo, hi} of the signal chart for values between {@code min} and {@code max}: a
     * little room above and below, and at least {@code minSpan} dB around their middle.
     */
    public static double[] chartRange(double min, double max, double minSpan) {
        double pad = Math.max(1, (max - min) * 0.15);
        double lo = min - pad, hi = max + pad;
        if (hi - lo < minSpan) {
            double mid = (min + max) / 2;
            lo = mid - minSpan / 2;
            hi = mid + minSpan / 2;
        }
        return new double[] {lo, hi};
    }

    /** "now", "35 s ago", "1 min ago", "1 min 30 s ago": the time axis of the signal chart. */
    public static String ago(double seconds) {
        long s = Math.round(Math.max(0, seconds));
        if (s < 3) return "now";
        if (s < 60) return s + " s ago";
        long m = s / 60, rest = s % 60;
        return rest == 0 ? m + " min ago" : m + " min " + rest + " s ago";
    }

    /**
     * The label under the meter. {@code unusable}: the phone receives the ring but gives no usable signal
     * strength (only impossible values in the last {@link #LOST_MS}); then "not heard" would contradict the
     * line under it (issue #125).
     */
    public static String label(int band, boolean unusable) {
        return band == NOT_HEARD && unusable ? "No usable signal" : label(band);
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
