package app.openring.core.analysis;

import java.util.Arrays;

/**
 * When the ring came off the finger, from its stored history (issue #74, "where was it last").
 * A worn ring reads skin temperature (27 C or more) and heart rate; a ring lying somewhere reads
 * room temperature and no heart rate. Only the end of the history matters: a ring taken off for a
 * shower and put back on ends warm, so it reads as worn.
 *
 * <p>Warmth alone is not trusted (issue #94): a ring on its charger, under a duvet or in a sunny
 * window also reads 27 C or more, but stores no heart rate. A warm reading counts as a finger only
 * with a heart rate near it, and one stray warm reading or heart rate does not end hours of cool
 * readings.
 */
public final class LastWorn {
    private LastWorn() {}

    /** Same border as {@code RightNow.WORN_MIN_C}: a finger reads about 28 to 36 C. */
    public static final double WORN_MIN_C = 27.0;
    /** Heart rate up to this long after the first cool reading still fits a ring being taken off. */
    public static final double HR_GRACE_S = 300;
    /**
     * A warm reading counts as a finger with a heart rate this close (30 minutes: the ring stores one
     * every 5 minutes at night, the daytime one skips readings while the hand moves). A heart rate
     * counts with another heart rate or a warm reading this close.
     */
    public static final double NEAR_S = 1800;
    /** Warm for longer than this without any heart rate is not trusted as a finger (1 hour). */
    public static final double NO_HR_S = 3600;
    /**
     * The heart rate rule needs proof that the ring stores daytime heart rate: at least this many warm
     * readings outside sleep (up to the newest daytime heart rate), of which {@link #DAY_COVER} have one
     * within {@link #NEAR_S}. A ring with only night heart rate (or sparse daytime heart rate) is judged
     * by temperature, as before issue #94.
     */
    public static final int MIN_DAY_READINGS = 12;
    public static final double DAY_COVER = 0.7;

    public static final int NO_DATA = 0, WORN = 1, OFF = 2, UNSURE = 3;

    public static final class Result {
        public int state = NO_DATA;
        /**
         * WORN: the newest sign of wearing (warm reading or heart rate). OFF: when it was taken off,
         * NaN when it was not worn at all within the history given. UNSURE: the last heart rate, after
         * which it stayed warm without one.
         */
        public double at = Double.NaN;
        /** WORN and UNSURE: the newest skin temperature in C (NaN when the newest sign was a heart rate). */
        public double skinC = Double.NaN;
        /** OFF: the typical temperature since it came off (median of the cool readings), about the room's. */
        public double roomC = Double.NaN;
        /** The newest temperature reading (unix seconds). */
        public double newest = Double.NaN;
        /** The newest heart rate that counts (unix seconds), NaN when none. */
        public double lastHr = Double.NaN;
        /** Whether the heart rate rule applied (the ring stores daytime heart rate, see {@link #DAY_COVER}). */
        public boolean hrRule;
        /** WORN: the newest warm stretch has a heart rate near it. */
        public boolean confirmed;
        /** WORN: warm for under an hour with no heart rate near yet, so probably just put on. */
        public boolean justOn;
        /** Whether the history holds any heart rate; without one, temperature alone decides. */
        public boolean hasHr;
        /**
         * OFF: the first of the newest warm readings after the time it came off that did not count
         * (no heart rate near them), NaN when none. {@link #warmNoHrC} is their highest temperature,
         * {@link #warmNoHrCount} how many there were and {@link #warmNoHrNow} whether they last to the
         * newest reading (then it lies somewhere warm right now).
         */
        public double warmNoHrAt = Double.NaN, warmNoHrC = Double.NaN;
        public int warmNoHrCount;
        public boolean warmNoHrNow;
        /** OFF: the newest heart rate after the time it came off that did not count (a stray), NaN when none. */
        public double strayHrAt = Double.NaN;
    }

    /** All heart rates count as daytime ones (see {@link #find(double[], double[], double[], double[])}). */
    public static Result find(double[] tempT, double[] tempC, double[] hrT) {
        return find(tempT, tempC, new double[0], hrT);
    }

    /**
     * @param tempT   times of the skin temperature readings (unix seconds, any order)
     * @param tempC   the readings in C
     * @param nightT  times of heart rates the ring stores while asleep (HRV events), any order, may be empty
     * @param dayT    times of the other valid heart rates (daytime measurement), any order, may be empty
     */
    public static Result find(double[] tempT, double[] tempC, double[] nightT, double[] dayT) {
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
        boolean[] warm = new boolean[n];
        for (int i = 0; i < n; i++) {
            t[i] = tempT[order[i]];
            c[i] = tempC[order[i]];
            warm[i] = c[i] >= WORN_MIN_C;
        }
        r.newest = t[n - 1];
        double[] night = nightT.clone(), day = dayT.clone();
        Arrays.sort(night);
        Arrays.sort(day);
        double[] hr = new double[night.length + day.length];
        System.arraycopy(night, 0, hr, 0, night.length);
        System.arraycopy(day, 0, hr, night.length, day.length);
        Arrays.sort(hr);
        r.hasHr = hr.length > 0;

        // 1. does this ring store daytime heart rate? Only then is warmth without one suspicious.
        int eligible = 0, covered = 0;
        double lastDay = day.length > 0 ? day[day.length - 1] : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n && t[i] <= lastDay; i++) {
            if (!warm[i] || hasNear(night, t[i], NEAR_S)) continue;
            eligible++;
            if (hasNear(day, t[i], NEAR_S)) covered++;
        }
        r.hrRule = eligible >= MIN_DAY_READINGS && covered >= DAY_COVER * eligible;

        // 2. heart rates with another heart rate near (a lone one is judged in step 4)
        boolean[] paired = new boolean[hr.length];
        int pairs = 0;
        for (int j = 0; j < hr.length; j++) {
            paired[j] = (j > 0 && hr[j] - hr[j - 1] <= NEAR_S) || (j + 1 < hr.length && hr[j + 1] - hr[j] <= NEAR_S);
            if (paired[j]) pairs++;
        }
        double[] pairedT = new double[pairs];
        for (int j = 0, g = 0; j < hr.length; j++) if (paired[j]) pairedT[g++] = hr[j];

        // 3. warm stretches: one with a heart rate near any of its readings is a finger as a whole (the
        // heart rate may stop while the hand moves); one without counts only when the ring stores too
        // little daytime heart rate to check it (then temperature decides, as before issue #94)
        boolean[] finger = new boolean[n];
        boolean[] stretchHr = new boolean[n];
        for (int s = 0; s < n; ) {
            if (!warm[s]) {
                s++;
                continue;
            }
            int e = s;
            while (e + 1 < n && warm[e + 1]) e++;
            boolean near = false;
            for (int i = s; i <= e && !near; i++) near = hasNear(pairedT, t[i], NEAR_S);
            for (int i = s; i <= e; i++) {
                finger[i] = near || !r.hrRule;
                stretchHr[i] = near;
            }
            s = e + 1;
        }

        // 4. a lone heart rate counts next to a finger reading, or after the newest reading when that one is warm
        boolean[] hrOk = new boolean[hr.length];
        int hrCount = 0;
        for (int j = 0; j < hr.length; j++) {
            boolean ok = paired[j] || (hr[j] > t[n - 1] && warm[n - 1]);
            if (!ok) {
                int i = nearest(t, hr[j]);
                for (int q = i; q >= 0 && Math.abs(hr[j] - t[q]) <= NEAR_S && !ok; q--) ok = finger[q];
                for (int q = i; q < n && Math.abs(t[q] - hr[j]) <= NEAR_S && !ok; q++) ok = finger[q];
            }
            hrOk[j] = ok;
            if (ok) hrCount++;
        }
        double[] good = new double[hrCount];
        for (int j = 0, g = 0; j < hr.length; j++) if (hrOk[j]) good[g++] = hr[j];
        if (good.length > 0) r.lastHr = good[good.length - 1];

        // 5. the newest warm stretch
        if (warm[n - 1]) {
            int s = n - 1;
            while (s > 0 && warm[s - 1]) s--;
            double lastHr = r.lastHr;
            boolean hrNear = stretchHr[n - 1] || (!Double.isNaN(lastHr) && lastHr >= t[s] - NEAR_S);
            if (!r.hrRule || (hrNear && t[n - 1] - lastHr <= NO_HR_S) || (!hrNear && t[n - 1] - t[s] <= NO_HR_S)) {
                r.state = WORN;
                r.at = Double.isNaN(lastHr) ? t[n - 1] : Math.max(t[n - 1], lastHr);
                r.skinC = c[n - 1];
                r.confirmed = hrNear;
                r.justOn = r.hrRule && !hrNear;
                return r;
            }
            if (hrNear) {
                // warm, with heart rate at first, then none for over an hour: charger, duvet, sun
                r.state = UNSURE;
                r.at = lastHr;
                r.skinC = c[n - 1];
                return r;
            }
            // warm for over an hour without any heart rate: it does not count, the cool readings before decide
            for (int i = s; i < n; i++) finger[i] = false;
        }

        // 4. the final stretch without a sign of a finger (cool readings and warm ones that did not count)
        int k = n;
        while (k > 0 && !finger[k - 1]) k--;
        double lastHr = r.lastHr;
        if (!Double.isNaN(lastHr) && lastHr > t[k] + HR_GRACE_S) {
            // heart beats while it read cool: cold fingers, the ring was still on
            int k2 = k;
            while (k2 < n && t[k2] <= lastHr) k2++;
            if (k2 == n) {
                r.state = WORN;
                r.at = lastHr; // the newest sign was a heart beat; the older warm reading would not be from then
                return r;
            }
            k = k2;
        }
        r.state = OFF;
        double off = k > 0 ? t[k - 1] : Double.NaN;
        if (!Double.isNaN(lastHr) && lastHr <= t[k] + HR_GRACE_S && (Double.isNaN(off) || lastHr > off)) off = Math.min(lastHr, t[k]);
        r.at = off;
        // what was left out after it came off, so the card can show it
        double from = Double.isNaN(off) ? Double.NEGATIVE_INFINITY : off;
        int runEnd = -1;
        for (int i = n - 1; i >= 0 && t[i] > from; i--) {
            if (warm[i] && !finger[i]) {
                if (runEnd < 0) runEnd = i;
                r.warmNoHrAt = t[i];
                r.warmNoHrC = Double.isNaN(r.warmNoHrC) ? c[i] : Math.max(r.warmNoHrC, c[i]);
                r.warmNoHrCount++;
            } else if (runEnd >= 0) {
                break;
            }
        }
        r.warmNoHrNow = runEnd == n - 1;
        for (int j = hr.length - 1; j >= 0 && hr[j] > from; j--) {
            if (!hrOk[j]) {
                r.strayHrAt = hr[j];
                break;
            }
        }
        int cool = 0;
        for (int i = k; i < n; i++) if (!warm[i]) cool++;
        if (cool > 0) {
            double[] room = new double[cool];
            for (int i = k, q = 0; i < n; i++) if (!warm[i]) room[q++] = c[i];
            Arrays.sort(room);
            r.roomC = cool % 2 == 1 ? room[cool / 2] : (room[cool / 2 - 1] + room[cool / 2]) / 2;
        }
        return r;
    }

    /** Index of the reading nearest to {@code x} in the sorted times. */
    static int nearest(double[] t, double x) {
        int i = Arrays.binarySearch(t, x);
        if (i >= 0) return i;
        i = -i - 1;
        if (i >= t.length) return t.length - 1;
        if (i > 0 && x - t[i - 1] < t[i] - x) return i - 1;
        return i;
    }

    /** Whether a sorted list holds a value within {@code w} of {@code x}. */
    static boolean hasNear(double[] sorted, double x, double w) {
        if (sorted.length == 0) return false;
        int i = Arrays.binarySearch(sorted, x);
        if (i >= 0) return true;
        i = -i - 1;
        return (i < sorted.length && sorted[i] - x <= w) || (i > 0 && x - sorted[i - 1] <= w);
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
