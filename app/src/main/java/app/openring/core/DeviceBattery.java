package app.openring.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The battery of each added device on Today, right under the scores (owner's wish of 15 November): the last level the device
 * sent, how old that reading is, a warning when it is low, "charging" when the device says so, and an estimate of the days left
 * when the readings since the last charge fell steadily. Works only on readings that are already stored (the ring's
 * {@code battery} table, read at each sync and by Find my ring; the watch's {@code watch_battery}, one per watch sync); it stores
 * nothing. Plain Java so the JVM tests cover it.
 */
public final class DeviceBattery {
    private DeviceBattery() {}

    /** The devices, in the order of the rows. */
    public static final int RING = 0, WATCH = 1;
    /** The names on the rows. */
    public static final String[] NAMES = {"Ring", "Watch"};

    /** At or below this level (percent) the row turns to the warning colour and says "charge soon". */
    public static final int LOW = 20;
    /** A reading older than this (seconds, 2 days) is called old: the level may be lower now, no estimate. */
    public static final long OLD_S = 2 * 86400L;
    /** "charging" is only said for a reading at most this old (seconds, 1 hour): later the device may be off the charger. */
    public static final long FRESH_S = 3600L;
    /** The days left estimate reads at most this far back from the newest reading (seconds, 7 days, as the Watch page's drop). */
    public static final long WINDOW_S = 7 * 86400L;
    /** A rise of at least this many points between two readings in a row is a charge (as {@code LastWorn.charging}). */
    public static final int CHARGE_RISE = 3;
    /** The estimate needs at least this many readings since the last charge ... */
    public static final int MIN_READINGS = 3;
    /** ... spread over at least this many days (half a day, as the Watch page's drop per day) ... */
    public static final double MIN_SPAN_DAYS = 0.5;
    /** ... falling at least this many points a day (slower would give months from a rounding step) ... */
    public static final double MIN_RATE = 1.0;
    /** ... and lying close to a straight line (share of the change the line explains, 0 to 1): a steady drop. */
    public static final double MIN_FIT = 0.8;

    /** One stored reading: unix seconds, percent, and whether the device said it charges (1), said it does not (0) or neither (-1). */
    public static final class Reading {
        public final long t;
        public final int pct, charging;

        public Reading(long t, int pct, int charging) {
            this.t = t;
            this.pct = pct;
            this.charging = charging;
        }
    }

    /** What one row shows. */
    public static final class Status {
        /** {@link #RING} or {@link #WATCH}. */
        public final int device;
        /** The newest reading, null when the device has none yet. */
        public final Reading newest;
        /** Seconds since the newest reading (0 when there is none or it lies ahead of the phone's clock). */
        public final long age;
        /** The newest reading is older than {@link #OLD_S}. */
        public final boolean old;
        /** The level is at or below {@link #LOW}. */
        public final boolean low;
        /** The device said it charges, in a fresh reading. */
        public final boolean charging;
        /** The level went up by a charge since the reading before, in a fresh reading (the ring does not say whether it charges). */
        public final boolean rising;
        /** Days until empty from the steady drop since the last charge; NaN when the readings do not allow a sound estimate. */
        public final double daysLeft;

        Status(int device, Reading newest, long age, boolean old, boolean low, boolean charging, boolean rising, double daysLeft) {
            this.device = device;
            this.newest = newest;
            this.age = age;
            this.old = old;
            this.low = low;
            this.charging = charging;
            this.rising = rising;
            this.daysLeft = daysLeft;
        }

        public String name() {
            return NAMES[device];
        }

        /** "72 %, 2 h ago", or "No reading yet" without one. */
        public String value() {
            if (newest == null) return "No reading yet";
            return newest.pct + " %, " + ago(age);
        }

        /** The words after the level: "charging", "charge soon", "about 3 days left", "may be lower now"; null for none. */
        public String note() {
            if (newest == null) return device == WATCH ? "sync the watch to read it" : "sync to read it";
            if (charging) return "charging";
            if (rising) return "charging or just charged";
            List<String> parts = new ArrayList<String>();
            if (low) parts.add("charge soon");
            if (old) parts.add("may be lower now");
            else if (!Double.isNaN(daysLeft)) parts.add(daysText(daysLeft));
            if (parts.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append(sb.length() == 0 ? "" : ", ").append(p);
            return sb.toString();
        }

        /** The row shows in the warning colour: low and not on the charger. */
        public boolean warn() {
            return low && !charging && !rising;
        }
    }

    /** The row of {@code device} from its stored readings (any order), at {@code now} (unix seconds). */
    public static Status of(int device, List<Reading> rows, long now) {
        List<Reading> s = sorted(rows);
        if (s.isEmpty()) return new Status(device, null, 0, false, false, false, false, Double.NaN);
        Reading n = s.get(s.size() - 1);
        long age = Math.max(0, now - n.t);
        boolean fresh = age <= FRESH_S;
        boolean charging = fresh && n.charging == 1;
        Reading before = s.size() >= 2 ? s.get(s.size() - 2) : null;
        boolean rising = fresh && !charging && n.charging != 0 && before != null && n.pct - before.pct >= CHARGE_RISE;
        boolean old = age > OLD_S;
        double left = old || charging || rising ? Double.NaN : daysLeft(s, now);
        return new Status(device, n, age, old, n.pct <= LOW, charging, rising, left);
    }

    /**
     * Days until empty: the readings since the last charge (at most {@link #WINDOW_S} before the newest), a straight line
     * through them, the level now on that line divided by its fall per day. NaN unless there are {@link #MIN_READINGS} of them
     * over {@link #MIN_SPAN_DAYS}, falling at least {@link #MIN_RATE} a day and close to the line ({@link #MIN_FIT}).
     */
    public static double daysLeft(List<Reading> rows, long now) {
        List<Reading> run = sinceCharge(sorted(rows));
        if (run.size() < MIN_READINGS) return Double.NaN;
        Reading first = run.get(0), last = run.get(run.size() - 1);
        if ((last.t - first.t) / 86400.0 < MIN_SPAN_DAYS) return Double.NaN;
        double[] fit = fit(run);
        double rate = -fit[0];
        if (rate < MIN_RATE || fit[1] < MIN_FIT) return Double.NaN;
        double levelNow = last.pct - rate * Math.max(0, now - last.t) / 86400.0;
        return Math.max(0, levelNow) / rate;
    }

    /**
     * The readings after the newest charge, oldest first: from the newest back to the first one that is a rise of
     * {@link #CHARGE_RISE} or more over the reading before it or that the device marked as charging, and at most
     * {@link #WINDOW_S} before the newest.
     */
    static List<Reading> sinceCharge(List<Reading> sorted) {
        List<Reading> run = new ArrayList<Reading>();
        if (sorted.isEmpty()) return run;
        long from = sorted.get(sorted.size() - 1).t - WINDOW_S;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Reading r = sorted.get(i);
            if (r.t < from || r.charging == 1) break;
            run.add(0, r);
            if (i > 0 && r.pct - sorted.get(i - 1).pct >= CHARGE_RISE) break;
        }
        return run;
    }

    /** Least squares line through the readings: {slope in points per day, share of the change it explains (0 to 1)}. */
    static double[] fit(List<Reading> run) {
        int n = run.size();
        long t0 = run.get(0).t;
        double sx = 0, sy = 0;
        for (Reading r : run) {
            sx += (r.t - t0) / 86400.0;
            sy += r.pct;
        }
        double mx = sx / n, my = sy / n, sxx = 0, sxy = 0, syy = 0;
        for (Reading r : run) {
            double x = (r.t - t0) / 86400.0 - mx, y = r.pct - my;
            sxx += x * x;
            sxy += x * y;
            syy += y * y;
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double r2 = sxx == 0 || syy == 0 ? 0 : sxy * sxy / (sxx * syy);
        return new double[] {slope, r2};
    }

    /** "less than a day left", "about 1 day left", "about 3 days left". */
    public static String daysText(double days) {
        if (days < 1) return "less than a day left";
        long d = Math.round(days);
        return "about " + d + (d == 1 ? " day" : " days") + " left";
    }

    /** "just now", "5 min ago", "2 h ago", "1 day ago", "3 days ago". */
    public static String ago(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 120) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        long d = s / 86400;
        return d == 1 ? "1 day ago" : d + " days ago";
    }

    private static List<Reading> sorted(List<Reading> rows) {
        List<Reading> s = new ArrayList<Reading>(rows);
        Collections.sort(s, new Comparator<Reading>() {
            @Override
            public int compare(Reading a, Reading b) {
                return Long.compare(a.t, b.t);
            }
        });
        return s;
    }

    /**
     * The demo ring's readings (made up, demo mode only): one every 3 hours over {@code days} days up to {@code now}, falling about
     * 12 points a day and charged every 6 days for two hours, the last time yesterday from {@code chargeFrom} to {@code chargeTo}
     * (unix seconds, the demo's charger time, when it has no other data). Readings in the charger time rise.
     */
    public static List<Reading> demoRing(long now, int days, long chargeFrom, long chargeTo) {
        List<Reading> out = new ArrayList<Reading>();
        long step = 3 * 3600L;
        long start = (now - days * 86400L) / step * step;
        for (long t = start; t <= now - 600; t += step) out.add(new Reading(t, demoLevel(t, chargeFrom, chargeTo), -1));
        return out;
    }

    /** The demo ring's level at {@code t}: 12 points a day down from 100 since the end of the charge before, up while charging. */
    public static int demoLevel(long t, long chargeFrom, long chargeTo) {
        long cycle = 6 * 86400L;
        // the charge at or before t: the demo's one moved back by whole cycles
        long k = (long) Math.ceil((chargeFrom - t) / (double) cycle);
        long from = chargeFrom - Math.max(0, k) * cycle, to = from + (chargeTo - chargeFrom);
        if (t >= to) return (int) Math.max(1, Math.round(100 - 12 * (t - to) / 86400.0));
        double low = 100 - 12 * (cycle - (to - from)) / 86400.0;
        return (int) Math.round(low + (100 - low) * (t - from) / (double) (to - from));
    }
}
