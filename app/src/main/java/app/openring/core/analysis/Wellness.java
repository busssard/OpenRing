package app.openring.core.analysis;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Daily activity, sleep debt, symptom radar and metabolic estimates. Ports of ecore functions via
 * open_oura ({@code sleep_debt_calculate}, Schofield BMR, steps to meters) and
 * open_health ({@code symptoms.rs}, the MET to steps and calories path).
 */
public final class Wellness {
    private Wellness() {}

    // ----------------------------------------------------------------------- metabolic

    /** Schofield basal metabolic rate, kcal per day. sex: 0 male, 1 female, other = average. */
    public static double bmr(double age, int sex, double weightKg) {
        double[][] male = {{3, 59.512, -30.4}, {10, 22.706, 504.3}, {18, 17.686, 658.2}, {30, 15.057, 692.2}, {60, 11.472, 873.1}, {999, 11.711, 587.7}};
        double[][] female = {{3, 58.317, -31.1}, {10, 20.315, 485.9}, {18, 13.384, 692.6}, {30, 14.818, 486.6}, {60, 8.126, 845.6}, {999, 9.082, 658.5}};
        double m = band(male, age, weightKg), f = band(female, age, weightKg);
        return sex == 0 ? m : sex == 1 ? f : (m + f) / 2;
    }

    private static double band(double[][] c, double age, double kg) {
        for (double[] r : c) if (age < r[0]) return r[1] * kg + r[2];
        double[] r = c[c.length - 1];
        return r[1] * kg + r[2];
    }

    /** Heart rate ratio VO2max estimate (Uth 2004): 15.3 x HRmax / HRrest, HRmax = 208 - 0.7 age. */
    public static double vo2maxUth(double age, double restingHr) {
        if (Double.isNaN(restingHr) || restingHr < 30) return Double.NaN;
        return 15.3 * (208 - 0.7 * age) / restingHr;
    }

    /** The VO2 max range a person can have, ml/kg/min (issue #116); outside it a heart rate reading was off. */
    public static final double VO2MAX_LOW = 10, VO2MAX_HIGH = 90;

    /** {@code v} when it is a VO2 max a person can have, NaN otherwise. Only for display, stored values stay as they are. */
    public static double vo2maxShown(double v) {
        return v >= VO2MAX_LOW && v <= VO2MAX_HIGH ? v : Double.NaN;
    }

    public static double stepsToMeters(double steps) {
        return steps * 0.762;
    }

    // ------------------------------------------------------------------------ activity

    public static final class Day {
        public String date;
        public double steps, activeKcal, totalKcal, distanceM;
        public double lowMin, mediumMin, highMin, inactiveMin, restMin, nonWearMin;
        public double trainingKcal; // medium + high
        public int longInactiveStreaks;
        public double avgMet = Double.NaN;
        public double[] metProfile = new double[96]; // 15 minute mean MET, NaN where no data
        public int coveredMin;
        /** Real minutes of the day: 1440, or 1380 and 1500 when the clocks change by an hour (issue #114). */
        public int dayMin;
        /** The resting part of {@link #totalKcal}: the whole day's resting burn, or its share up to {@link #totalUntil} (issue #155). */
        public double restingKcal;
        /** Unix seconds up to which the resting part counts on a day still running when analysed; NaN for a day that has ended. */
        public double totalUntil = Double.NaN;
        public double targetKcal;
        public Night.Score score;
    }

    /** Start and end of the daytime window for long sitting periods (issue #154), minutes after midnight by the clock: 08:00 to 22:00. */
    public static final int DAYTIME_FROM_MIN = 8 * 60, DAYTIME_TO_MIN = 22 * 60;

    /**
     * Build one local day of activity from per minute MET samples (unix seconds at minute start) for the real day
     * {@code from} to {@code to} (its midnights in {@code zone}, 23 or 25 hours on a clock change day, issue #114):
     * every real minute counts once, and the chart's quarter hours and the 08:00 to 22:00 window go by each
     * minute's own clock time, as {@link DialMath#fill} does for the screen clock. When the clocks go back both
     * passes of the repeated hour land on the same quarter hours (their average); when they go forward the skipped
     * ones stay empty. A {@code zone} of null counts the clock from {@code from}.
     * Minutes inside {@code sleeps} count as rest, also those without a value (issue #112), so every real minute is in
     * exactly one of high, medium, low, inactive, rest or not worn. Step counts are an estimate from intensity
     * (the ring's own step decoder is a proprietary model). Values that are not plausible
     * ({@code EventCodec.plausibleMet}) must be left out by the caller.
     */
    public static Day day(String date, double from, double to, ZoneId zone, TS met, List<double[]> sleeps, double weightKg, double bmrKcal) {
        return day(date, from, to, zone, met, sleeps, weightKg, bmrKcal, Double.POSITIVE_INFINITY);
    }

    /**
     * As above, analysed at {@code now} (unix seconds): on a day that has not ended by then Total burn counts the
     * resting burn only for the share of the day that has passed (issue #155, as Oura's total burn grows through the day).
     */
    public static Day day(String date, double from, double to, ZoneId zone, TS met, List<double[]> sleeps, double weightKg, double bmrKcal, double now) {
        Day d = new Day();
        d.date = date;
        Arrays.fill(d.metProfile, Double.NaN);
        int n = (int) Math.max(0, Math.round((to - from) / 60));
        d.dayMin = n;
        double[] perMin = new double[n];
        Arrays.fill(perMin, Double.NaN);
        for (int i = 0; i < met.size(); i++) {
            int m = (int) Math.floor((met.t(i) - from) / 60);
            if (m < 0 || m >= n) continue;
            perMin[m] = met.v(i);
        }
        double[] prof = new double[96];
        int[] profN = new int[96];
        double metSum = 0;
        int sitting = 0; // sitting minutes of the current stretch between 08:00 and 22:00 (issue #154)
        for (int m = 0; m < n; m++) {
            double t = from + m * 60 + 30;
            boolean asleep = false;
            for (double[] s : sleeps) if (t >= s[0] && t < s[1]) asleep = true;
            double v = perMin[m];
            int clock = clockMin(from + m * 60, from, zone);
            boolean daytime = clock >= DAYTIME_FROM_MIN && clock < DAYTIME_TO_MIN;
            if (Double.isNaN(v)) {
                // asleep without a value is still rest (issue #112): the ring found the sleep, it was worn
                if (asleep) d.restMin++;
                else d.nonWearMin++;
                if (sitting >= 60) d.longInactiveStreaks++;
                sitting = 0;
                continue;
            }
            d.coveredMin++;
            metSum += v;
            prof[clock / 15] += v;
            profN[clock / 15]++;
            double act = Math.max(0, v - 1) * weightKg / 60.0;
            d.activeKcal += act;
            if (asleep) {
                d.restMin++;
            } else if (v < 1.5) {
                d.inactiveMin++;
            } else if (v < 3) {
                d.lowMin++;
            } else if (v < 6) {
                d.mediumMin++;
                d.trainingKcal += act;
            } else {
                d.highMin++;
                d.trainingKcal += act;
            }
            // steps per minute by intensity (open_health heuristic, with a light band added)
            if (!asleep) {
                if (v >= 7) d.steps += 150;
                else if (v >= 2.5) d.steps += 105;
                else if (v >= 1.8) d.steps += 25;
            }
            boolean sedentary = !asleep && v < 1.5;
            if (sedentary) {
                if (daytime) sitting++;
            } else {
                if (sitting >= 60) d.longInactiveStreaks++;
                sitting = 0;
            }
        }
        // a stretch still running at the end of the day: the same rule
        if (sitting >= 60) d.longInactiveStreaks++;
        for (int b = 0; b < 96; b++) if (profN[b] > 0) d.metProfile[b] = prof[b] / profN[b];
        if (d.coveredMin > 0) d.avgMet = metSum / d.coveredMin;
        d.steps = Math.round(d.steps / 10.0) * 10;
        d.distanceM = stepsToMeters(d.steps);
        d.restingKcal = restingKcal(bmrKcal, from, to, now);
        if (now < to) d.totalUntil = Math.max(from, now);
        d.totalKcal = d.restingKcal + d.activeKcal;
        return d;
    }

    /**
     * The resting burn of the day {@code from} to {@code to} up to {@code now} (issue #155): all of {@code bmrKcal} once
     * the day has ended, before that the share of the day's real seconds that has passed (0 before it began).
     */
    public static double restingKcal(double bmrKcal, double from, double to, double now) {
        if (!(to > from) || !(now < to)) return bmrKcal; // ended, or no time given
        return bmrKcal * Stats.clamp((now - from) / (to - from), 0, 1);
    }

    /** Minutes after midnight by the clock in {@code zone} at {@code t} (0 to 1439); without a zone, minutes since {@code from}. */
    public static int clockMin(double t, double from, ZoneId zone) {
        // Without a zone the day is read as a plain 1440 minute day from its start (no clock change);
        // every caller in the app passes the day's zone.
        if (zone == null) return (int) Math.min(1439, Math.max(0, Math.floor((t - from) / 60)));
        long s = (long) Math.floor(t);
        int off = zone.getRules().getOffset(Instant.ofEpochSecond(s)).getTotalSeconds();
        return (int) Math.floorMod(Math.floorDiv(s + off, 60L), 1440L);
    }

    // ---------------------------------------------------------------------- sleep debt

    /** ecore sleep_debt_calculate. Index 0 is the most recent day. Returns {debtS, valid}. */
    public static double[] sleepDebt(int[] actualS, int[] needS) {
        int n = Math.min(actualS.length, needS.length);
        if (n == 0) return new double[] {0, 0};
        double decay = n == 1 ? 0 : 0.75 / (n - 1);
        double debt = 0;
        int valid = 0;
        for (int d = 0; d < n; d++) {
            if (actualS[d] <= 0 || needS[d] <= 0) continue;
            valid++;
            debt += (1 - decay * d) * (needS[d] - actualS[d]);
        }
        if (actualS[0] <= 0) return new double[] {0, 0};
        debt = Math.min(Math.max(debt, 0), 36000);
        debt = Math.round(debt / 2700.0) * 2700.0;
        return new double[] {debt, valid >= 5 ? 1 : 0};
    }

    /**
     * Personal sleep need (open_health): mean of the last 90 days after an IQR outlier fence,
     * clamped to 7 to 9 hours and rounded to 15 minutes; 8 hours until 14 valid days exist.
     */
    public static int sleepNeedS(double[] priorAsleepS) {
        double[] v = Stats.finite(priorAsleepS);
        List<Double> pos = new ArrayList<Double>();
        for (double x : v) if (x > 0) pos.add(x);
        if (pos.size() < 14) return 8 * 3600;
        double[] a = Stats.toArray(pos);
        double q1 = Stats.percentile(a, 0.25), q3 = Stats.percentile(a, 0.75);
        double fence = 1.5 * (q3 - q1);
        double s = 0;
        int c = 0;
        for (double x : a) if (x >= q1 - fence && x <= q3 + fence) { s += x; c++; }
        double need = Stats.clamp(s / Math.max(1, c), 7 * 3600, 9 * 3600);
        return (int) (Math.round(need / 900.0) * 900);
    }

    // ------------------------------------------------------------------- symptom radar

    public static final class Radar {
        public boolean available;
        public String status = "Not enough history";
        public int decision; // 0 none, 1 minor, 2 major
        public final Map<String, double[]> markers = new TreeMap<String, double[]>(); // label -> {value, lower, upper, flagged}
    }

    /**
     * Model free symptom radar (open_health symptoms.rs): skin temperature, lowest HR, HRV and
     * breathing rate against your own median and MAD over prior nights. Needs 7 nights.
     * Arrays hold history values; NaN entries are ignored.
     */
    public static Radar radar(double[] tonight, double[][] history) {
        String[] labels = {"Skin temperature", "Resting heart rate", "HRV", "Respiratory rate"};
        boolean[] highBad = {true, true, false, true};
        boolean[] asDev = {true, false, false, false};
        Radar r = new Radar();
        int flags = 0, strong = 0;
        for (int k = 0; k < 4; k++) {
            double[] h = Stats.finite(history[k]);
            if (Double.isNaN(tonight[k]) || h.length < 7) continue;
            double center = Stats.median(h);
            double[] dev = new double[h.length];
            for (int i = 0; i < h.length; i++) dev[i] = Math.abs(h[i] - center);
            double mad = Stats.median(dev);
            if (!(mad > 0)) continue;
            double sd = mad * 1.4826;
            double z = (tonight[k] - center) / sd;
            double bad = highBad[k] ? z : -z;
            boolean flagged = bad >= 2.0;
            if (flagged) {
                flags++;
                if (bad >= 3.0) strong++;
            }
            double origin = asDev[k] ? center : 0;
            r.markers.put(labels[k], new double[] {tonight[k] - origin, center - origin - 2 * sd, center - origin + 2 * sd, flagged ? 1 : 0});
            r.available = true;
        }
        if (!r.available) return r;
        r.decision = (flags >= 3 || (flags >= 2 && strong >= 1)) ? 2 : flags >= 1 ? 1 : 0;
        r.status = r.decision == 2 ? "Major signs" : r.decision == 1 ? "Minor signs" : "No signs";
        return r;
    }
}
