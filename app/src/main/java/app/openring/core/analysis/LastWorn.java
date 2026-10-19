package app.openring.core.analysis;

import java.util.Arrays;

/**
 * When the ring came off the finger, from its stored history (issue #74, "where was it last").
 * A worn ring reads skin temperature (27 C or more) and heart rate; a ring lying somewhere reads
 * room temperature and no heart rate. Only the end of the history matters: a ring taken off for a
 * shower and put back on ends warm, so it reads as worn.
 */
public final class LastWorn {
    private LastWorn() {}

    /** Same border as {@code RightNow.WORN_MIN_C}: a finger reads about 28 to 36 C. */
    public static final double WORN_MIN_C = 27.0;
    /** Heart rate up to this long after the first cool reading still fits a ring being taken off. */
    public static final double HR_GRACE_S = 300;

    public static final int NO_DATA = 0, WORN = 1, OFF = 2;

    public static final class Result {
        public int state = NO_DATA;
        /**
         * WORN: the newest sign of wearing (warm reading or heart rate). OFF: when it was taken off,
         * NaN when it was not worn at all within the history given.
         */
        public double at = Double.NaN;
        /** WORN: the newest warm reading in C (NaN when only heart rate showed it). */
        public double skinC = Double.NaN;
        /** OFF: the typical temperature since it came off (median), about the room's. */
        public double roomC = Double.NaN;
        /** The newest temperature reading (unix seconds). */
        public double newest = Double.NaN;
    }

    /**
     * @param tempT times of the skin temperature readings (unix seconds, any order)
     * @param tempC the readings in C
     * @param hrT   times of valid heart rate readings (any order), may be empty
     */
    public static Result find(double[] tempT, double[] tempC, double[] hrT) {
        Result r = new Result();
        int n = tempT.length;
        if (n == 0) return r;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final double[] tt = tempT;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(tt[a], tt[b]);
            }
        });
        double[] t = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = tempT[order[i]];
            c[i] = tempC[order[i]];
        }
        r.newest = t[n - 1];
        double lastHr = Double.NaN;
        for (double h : hrT) if (Double.isNaN(lastHr) || h > lastHr) lastHr = h;

        if (c[n - 1] >= WORN_MIN_C) {
            r.state = WORN;
            r.at = Double.isNaN(lastHr) ? t[n - 1] : Math.max(t[n - 1], lastHr);
            r.skinC = c[n - 1];
            return r;
        }
        // the final stretch of cool readings
        int k = n - 1;
        while (k > 0 && c[k - 1] < WORN_MIN_C) k--;
        if (!Double.isNaN(lastHr) && lastHr > t[k] + HR_GRACE_S) {
            // heart beats while it read cool: cold fingers, the ring was still on
            int k2 = k;
            while (k2 < n && t[k2] <= lastHr) k2++;
            if (k2 == n) {
                r.state = WORN;
                r.at = lastHr;
                if (k > 0) r.skinC = c[k - 1];
                return r;
            }
            k = k2;
        }
        r.state = OFF;
        double off = k > 0 ? t[k - 1] : Double.NaN;
        if (!Double.isNaN(lastHr) && lastHr <= t[k] + HR_GRACE_S && (Double.isNaN(off) || lastHr > off)) off = Math.min(lastHr, t[k]);
        r.at = off;
        double[] cool = Arrays.copyOfRange(c, k, n);
        Arrays.sort(cool);
        r.roomC = cool.length % 2 == 1 ? cool[cool.length / 2] : (cool[cool.length / 2 - 1] + cool[cool.length / 2]) / 2;
        return r;
    }

    /**
     * Whether the ring was charging between two battery reads: 1 it went up by 3 points or more
     * (probably on the charger), 0 it went down by 3 or more (not charging), -1 unknown (no earlier
     * read, or about the same, which a full ring on its charger also shows).
     */
    public static int charging(int before, int now) {
        if (before < 0 || now < 0) return -1;
        if (now - before >= 3) return 1;
        return before - now >= 3 ? 0 : -1;
    }
}
