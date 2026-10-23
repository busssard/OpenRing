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
     * readings outside sleep (up to the newest daytime heart rate), of which {@link #DAY_COVER} have
     * a paired daytime heart rate (one with a heart rate of another event within {@link #NEAR_S}) within
     * {@link #NEAR_S}. A ring with only night heart rate, or sparse daytime heart rate (one value every 35
     * minutes pairs with nothing, issue #124), is judged by temperature, as before issue #94.
     */
    public static final int MIN_DAY_READINGS = 12;
    public static final double DAY_COVER = 0.7;
    /**
     * Only the last 48 hours before the newest reading show whether the ring stores daytime heart rate
     * now: when it stopped days ago (switched off, low battery), warmth without one means nothing.
     */
    public static final double DAY_RULE_S = 2 * 86400;

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
         * (no counted heart rate near them), NaN when none. {@link #warmNoHrC} is their highest temperature,
         * {@link #warmNoHrCount} how many there were and {@link #warmNoHrNow} whether they last to the
         * newest reading (then it lies somewhere warm right now).
         */
        public double warmNoHrAt = Double.NaN, warmNoHrC = Double.NaN;
        public int warmNoHrCount;
        public boolean warmNoHrNow;
        /**
         * OFF: how many stored heart rate events that did not count lie within {@link #NEAR_S} of those
         * warm readings (one event alone cannot vouch for them), and the newest of their times (NaN when none).
         */
        public int warmLoneHr;
        public double warmLoneHrAt = Double.NaN;
        /** OFF: the newest heart rate after the time it came off that did not count (a stray), NaN when none. */
        public double strayHrAt = Double.NaN;
        /**
         * OFF: the stray heart rate comes from one of the events counted in {@link #warmLoneHr} (an HRV event
         * reaching past the warm readings), so the card names it there and not again (issue #124).
         */
        public boolean strayInWarm;
    }

    /** All heart rates count as daytime ones, each from an event of its own. */
    public static Result find(double[] tempT, double[] tempC, double[] hrT) {
        return find(tempT, tempC, new double[0], null, hrT, null);
    }

    /** Each heart rate from an event of its own. */
    public static Result find(double[] tempT, double[] tempC, double[] nightT, double[] dayT) {
        return find(tempT, tempC, nightT, null, dayT, null);
    }

    /**
     * @param tempT   times of the skin temperature readings (unix seconds, any order)
     * @param tempC   the readings in C
     * @param nightT  times of heart rates the ring stores while asleep (HRV events), any order, may be empty
     * @param nightId the stored event each night heart rate comes from (0 or more, different events have
     *                different numbers, also across both lists); null: each heart rate is an event of its own
     * @param dayT    times of the other valid heart rates (daytime measurement), any order, may be empty
     * @param dayId   the same for the daytime heart rates
     */
    public static Result find(double[] tempT, double[] tempC, double[] nightT, int[] nightId, double[] dayT, int[] dayId) {
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
        double[] night = nightT.clone();
        Arrays.sort(night);
        // all heart rates in time order, each with the number of its event (an HRV event holds six values
        // 5 minutes apart, an AOHR event a burst seconds apart: they are one measurement, not several)
        final double[] allT = new double[nightT.length + dayT.length];
        int[] allId = new int[allT.length];
        for (int j = 0; j < nightT.length; j++) {
            allT[j] = nightT[j];
            allId[j] = nightId != null ? nightId[j] : -1 - j;
        }
        for (int j = 0; j < dayT.length; j++) {
            allT[nightT.length + j] = dayT[j];
            allId[nightT.length + j] = dayId != null ? dayId[j] : -1 - nightT.length - j;
        }
        Integer[] hOrder = new Integer[allT.length];
        for (int j = 0; j < hOrder.length; j++) hOrder[j] = j;
        Arrays.sort(hOrder, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(allT[a], allT[b]);
            }
        });
        double[] hr = new double[allT.length];
        int[] hrId = new int[allT.length];
        for (int j = 0; j < hr.length; j++) {
            hr[j] = allT[hOrder[j]];
            hrId[j] = allId[hOrder[j]];
        }
        r.hasHr = hr.length > 0;

        // 1. heart rates with a heart rate of another event near (a lone one is judged in step 4)
        boolean[] paired = new boolean[hr.length];
        int pairs = 0, dayPairs = 0;
        for (int j = 0; j < hr.length; j++) {
            paired[j] = otherEventNear(hr, hrId, j, NEAR_S);
            if (!paired[j]) continue;
            pairs++;
            if (hOrder[j] >= nightT.length) dayPairs++;
        }
        double[] pairedT = new double[pairs], pairedDay = new double[dayPairs];
        for (int j = 0, g = 0, d = 0; j < hr.length; j++) {
            if (!paired[j]) continue;
            pairedT[g++] = hr[j];
            if (hOrder[j] >= nightT.length) pairedDay[d++] = hr[j];
        }

        // 2. does this ring store daytime heart rate now? Only then is warmth without one suspicious. Only
        // paired daytime heart rates count, the same evidence step 3 asks for: a single value every 35 to 45
        // minutes pairs with nothing, so it would switch the rule on and then never vouch for a finger (issue #124)
        int eligible = 0, covered = 0;
        // the window ends at the newest daytime heart rate of any kind: sparse values today must count as
        // readings without a paired one, or yesterday's dense data alone would decide (review of PR #141)
        double lastDay = Double.NEGATIVE_INFINITY;
        for (double x : dayT) lastDay = Math.max(lastDay, x);
        for (int i = 0; i < n && t[i] <= lastDay; i++) {
            if (!warm[i] || t[i] < r.newest - DAY_RULE_S || hasNear(night, t[i], NEAR_S)) continue;
            eligible++;
            if (hasNear(pairedDay, t[i], NEAR_S)) covered++;
        }
        r.hrRule = eligible >= MIN_DAY_READINGS && covered >= DAY_COVER * eligible;

        // 3. warm stretches: one with a paired heart rate near any of its readings, or lone heart rates of two
        // events at most an hour apart near it, is a finger as a whole (the heart rate may stop while the
        // hand moves); one without counts only when the ring stores too
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
            if (!near) near = loneEvents(hr, hrId, t[s] - NEAR_S, t[e] + NEAR_S);
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
                // a heart rate many hours old (from the night) confirms nothing about now
                r.confirmed = hrNear && t[n - 1] - lastHr <= NO_HR_S;
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

        // 6. the final stretch without a sign of a finger (cool readings and warm ones that did not count)
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
        int runEnd = -1, runStart = -1;
        for (int i = n - 1; i >= 0 && t[i] > from; i--) {
            if (warm[i] && !finger[i]) {
                if (runEnd < 0) runEnd = i;
                runStart = i;
                r.warmNoHrAt = t[i];
                r.warmNoHrC = Double.isNaN(r.warmNoHrC) ? c[i] : Math.max(r.warmNoHrC, c[i]);
                r.warmNoHrCount++;
            } else if (runEnd >= 0) {
                break;
            }
        }
        r.warmNoHrNow = runEnd == n - 1;
        java.util.Set<Integer> warmEvents = new java.util.HashSet<Integer>();
        if (runEnd >= 0) {
            // heart rates that did not count during those warm readings: the card must not say they had none
            for (int j = 0; j < hr.length; j++) {
                if (hrOk[j] || hr[j] < t[runStart] - NEAR_S || hr[j] > t[runEnd] + NEAR_S) continue;
                warmEvents.add(hrId[j]);
                r.warmLoneHrAt = hr[j];
            }
            r.warmLoneHr = warmEvents.size();
        }
        for (int j = hr.length - 1; j >= 0 && hr[j] > from; j--) {
            if (!hrOk[j]) {
                r.strayHrAt = hr[j];
                r.strayInWarm = warmEvents.contains(hrId[j]);
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

    /** Writes a time for the card, like "today at 22:14" (the app passes its own, tests a fixed one). */
    public interface Clock {
        String when(double unix);
    }

    /**
     * The "where was it last" text with its evidence (issues #74, #94), followed by a line on how old the
     * newest reading is when that is more than 6 hours.
     */
    public static String text(Result r, double nowUnix, Clock clock) {
        String s = sentence(r, clock);
        double newest = Double.isNaN(r.lastHr) ? r.newest : Math.max(r.newest, r.lastHr);
        double age = nowUnix - newest;
        if (r.state != NO_DATA && age > 6 * 3600) {
            s += "\nThe newest synced reading is " + (age < 2 * 86400 ? Math.round(age / 3600) + " hours" : (long) Math.floor(age / 86400) + " days")
                    + " old, so this is how it was then.";
        }
        return s;
    }

    static String sentence(Result r, Clock clock) {
        String s;
        if (r.state == WORN) {
            s = "The newest reading (" + clock.when(r.at) + ") was from a finger (";
            if (Double.isNaN(r.skinC)) s += "a heart rate, with cool skin).";
            else if (r.confirmed) s += "skin " + one(r.skinC) + " °C"
                    // the newest sign can be a heart rate long after the newest temperature: give each its own time
                    + (Math.abs(r.newest - r.at) >= 60 ? " " + clock.when(r.newest) : "")
                    + ", heart rate until " + clock.when(r.lastHr) + ").";
            else if (r.justOn) s += "skin " + one(r.skinC) + " °C, no heart rate stored yet, so it was probably just put on).";
            else if (!r.hasHr) s += "skin " + one(r.skinC) + " °C, no heart rate in the synced data to confirm it).";
            else s += "skin " + one(r.skinC) + " °C, the ring stored too little daytime heart rate to confirm it).";
        } else if (r.state == UNSURE) {
            s = "Not sure. It still read warm at its newest reading (" + one(r.skinC) + " °C, " + clock.when(r.newest) + "), but stored no heart rate after "
                    + clock.when(r.at) + ", so it was probably taken off around then and lay somewhere warm (the charger, under a duvet, in the sun).";
        } else if (r.state == OFF) {
            if (!Double.isNaN(r.at)) {
                s = "Taken off " + clock.when(r.at) + (Double.isNaN(r.roomC) ? "." : " (then it lay at room temperature, about " + Math.round(r.roomC) + " °C).");
            } else {
                s = "Not worn in the last 7 days of synced data" + (Double.isNaN(r.roomC) ? "" : " (it reads room temperature, about " + Math.round(r.roomC) + " °C)") + ".";
            }
            // "without heart rate", or the lone heart rates it had, which are not enough on their own
            String hrs = r.warmLoneHr == 0 ? null : r.warmLoneHr == 1 ? "only a single heart rate (" + clock.when(r.warmLoneHrAt) + ")"
                    : "only " + r.warmLoneHr + " lone heart rates";
            if (r.warmNoHrNow) {
                s += " Since " + clock.when(r.warmNoHrAt) + " it reads warm" + (Double.isNaN(r.at) ? "" : " again") + " (up to " + one(r.warmNoHrC) + " °C) "
                        + (hrs == null ? "but without heart rate" : "with " + hrs + ", which is not enough")
                        + ", so it is probably not on a finger (the charger, the sun or something warm).";
            } else if (r.warmNoHrCount == 1) {
                s += " A warm reading " + clock.when(r.warmNoHrAt) + " (" + one(r.warmNoHrC) + " °C) "
                        + (hrs == null ? "had no heart rate, so it does not count." : "had " + hrs + ", which is not enough to count it.");
            } else if (r.warmNoHrCount > 1) {
                s += " Warm readings from " + clock.when(r.warmNoHrAt) + " (up to " + one(r.warmNoHrC) + " °C) "
                        + (hrs == null ? "had no heart rate, so they do not count." : "had " + hrs + ", which is not enough to count them.");
            }
            if (!Double.isNaN(r.strayHrAt) && !r.strayInWarm) {
                s += " A lone heart rate " + clock.when(r.strayHrAt) + " with no warm reading near it does not count.";
            }
        } else {
            s = "No temperature readings yet. Sync once to fill this in.";
        }
        return s;
    }

    /** One decimal in the phone's language, like the rest of the app ({@code Ui.fmt}). */
    static String one(double v) {
        return String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    /** Whether heart rate {@code j} has one from another event within {@code w} (times sorted). */
    static boolean otherEventNear(double[] t, int[] id, int j, double w) {
        for (int q = j - 1; q >= 0 && t[j] - t[q] <= w; q--) if (id[q] != id[j]) return true;
        for (int q = j + 1; q < t.length && t[q] - t[j] <= w; q++) if (id[q] != id[j]) return true;
        return false;
    }

    /**
     * Whether heart rates between {@code from} and {@code to} (times sorted) come from two different events
     * at most {@link #NO_HR_S} apart: sparse daytime heart rate (one value every 35 to 60 minutes) on a warm
     * finger, which a charger, duvet or sunny window does not store (review of PR #141). One event never
     * vouches for itself (review of PR #103).
     */
    static boolean loneEvents(double[] t, int[] id, double from, double to) {
        int j = Arrays.binarySearch(t, from);
        if (j < 0) j = -j - 1;
        while (j > 0 && t[j - 1] >= from) j--;
        for (; j < t.length && t[j] <= to; j++) {
            for (int q = j + 1; q < t.length && t[q] <= to && t[q] - t[j] <= NO_HR_S; q++) if (id[q] != id[j]) return true;
        }
        return false;
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
