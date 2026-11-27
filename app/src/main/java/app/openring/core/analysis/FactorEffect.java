package app.openring.core.analysis;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Life factors, phase 2 (issue #292): how your nights went after a factor, for you personally. Pure Java, nothing stored:
 * worked out from the day summaries and the logged days each time the card is built (in the background).
 *
 * <ul>
 * <li><b>Pairs:</b> a factor on day D belongs to the night that starts on the evening of D, the night whose morning is D + 1.
 * Every day of a several day entry counts. Nights that touch a left out time (#254) and days without a ring night after
 * them are skipped.</li>
 * <li><b>When a factor counts:</b> from {@link Factor#from} (the first day anything was logged; an own tag from the day it was
 * made). Days before it are unknown, not "without"; days in {@link Factor#unknown} too (a running cycle without a measured
 * rise). Every other day without the factor counts as without.</li>
 * <li><b>Measure:</b> each night against your usual, the median of the main nights of the {@link #USUAL_DAYS} days before it
 * (at most {@link Baselines#USUAL_NIGHTS}, as readiness reads them), with at least {@link #USUAL_MIN} values. HRV in % of the
 * usual, lowest heart rate in bpm, temperature in °C, sleep score in points.</li>
 * <li><b>Compare:</b> the mean of the nights with minus the mean of the nights without, over the {@link #DAYS} days up to
 * today. A number needs {@link #MIN_WITH} nights with, {@link #MIN_WITHOUT} without, and nights with in {@link #MIN_WEEKS}
 * calendar weeks.</li>
 * <li><b>Range:</b> 99 % (decisions of 17 November: clear only at 99 % per factor and measure). The weeks (Monday to Sunday
 * of the day logged) are drawn again with replacement {@link #DRAWS} times, seeded from the values, so the same history always
 * gives the same text; the spread of the draws gets the small sample correction k / (k - 1) and Student's t for k - 1, k the
 * weeks of the side with fewer of them (with the factor or without it), as {@code Trends.compare} does. Clear when the whole range is on one side of zero.</li>
 * </ul>
 */
public final class FactorEffect {
    private FactorEffect() {}

    public static final int HRV = 0, RHR = 1, TEMP = 2, SCORE = 3, MEASURES = 4;
    /** Nights with the factor and without it a number needs. */
    public static final int MIN_WITH = 10, MIN_WITHOUT = 30;
    /**
     * Calendar weeks each side (the nights with the factor and the nights without) must fall in for a range: the side with fewer
     * weeks gives Student's t and the small sample correction. With {@link #MIN_WITH} and {@link #MIN_WITHOUT} nights each side
     * always has at least two; this guards the range itself.
     */
    public static final int MIN_WEEKS = 2;
    public static final int DRAWS = 2000;
    /** The days compared, up to today. */
    public static final int DAYS = 365;
    /**
     * Values "your usual" needs among the nights before. One place for the owner's answer on #443 (7, as the Your day line, or
     * 3, as readiness's baselines); readiness itself keeps {@link Baselines#usual(double[])} unchanged whatever is chosen here.
     */
    public static final int USUAL_MIN = Baselines.MIN_NIGHTS;
    /** The days before a night whose main nights its usual reads (readiness reads the main nights of 90 days). */
    public static final int USUAL_DAYS = 90;

    /** Kinds of factor: a logged tag, or one of the period tracker's (automatic, for people who use it). */
    public static final int TAG = 0, PERIOD = 1, AFTER_OVULATION = 2;

    /** Results of one factor. */
    public static final int NUMBER = 0, FEW_WITH = 1, FEW_WITHOUT = 2, FEW_VALUES = 3;

    // ------------------------------------------------------------------ input

    /** One main night by its morning ({@code day}), its four values (NaN for none), and whether it touches a left out time. */
    public static final class Night {
        public final LocalDate day;
        public final double[] v;
        public final boolean leftOut;

        public Night(LocalDate day, double hrv, double rhr, double temp, double score, boolean leftOut) {
            this.day = day;
            this.v = new double[] {hrv, rhr, temp, score};
            this.leftOut = leftOut;
        }
    }

    /** One factor: its key and name, its kind, the days with it, the first day that counts, the days that are unknown. */
    public static final class Factor {
        public final String key, name;
        public final int kind;
        public final Set<LocalDate> days;
        public final LocalDate from;
        public final Set<LocalDate> unknown;

        public Factor(String key, String name, int kind, Set<LocalDate> days, LocalDate from, Set<LocalDate> unknown) {
            this.key = key;
            this.name = name;
            this.kind = kind;
            this.days = days == null ? Collections.<LocalDate>emptySet() : days;
            this.from = from;
            this.unknown = unknown == null ? Collections.<LocalDate>emptySet() : unknown;
        }

        boolean counts(LocalDate tagDay) {
            return (from == null || !tagDay.isBefore(from)) && !unknown.contains(tagDay);
        }
    }

    // ------------------------------------------------------------------ output

    /** One night of the 365 days: the day before it (where a factor is logged), its values, usual values and differences. */
    public static final class Point {
        public final LocalDate tagDay;
        public final double[] value, usual, diff;

        Point(LocalDate tagDay, double[] value, double[] usual, double[] diff) {
            this.tagDay = tagDay;
            this.value = value;
            this.usual = usual;
            this.diff = diff;
        }
    }

    /** The comparison of one measure: the difference, its 99 % range, the counts. NaN where there is no number. */
    public static final class Measure {
        public final int measure, nWith, nWithout, weeksWith;
        public final double diff, lo, hi;

        Measure(int measure, int nWith, int nWithout, int weeksWith, double diff, double lo, double hi) {
            this.measure = measure;
            this.nWith = nWith;
            this.nWithout = nWithout;
            this.weeksWith = weeksWith;
            this.diff = diff;
            this.lo = lo;
            this.hi = hi;
        }

        public boolean enough() {
            return !Double.isNaN(diff) && !Double.isNaN(lo);
        }

        /** The whole range on one side of zero. */
        public boolean clear() {
            return enough() && (lo > 0 || hi < 0);
        }
    }

    /** What one factor shows. */
    public static final class Result {
        public final Factor factor;
        public final int state;
        /** Nights after a day with the factor and without it that count (with a ring night), weeks with the factor. */
        public final int nWith, nWithout, weeksWith;
        public final Measure[] m;
        /** The measure of the headline: HRV, or the next one with a number; -1 without a number. */
        public final int headline;
        /** The name of a tag on more than half of this factor's nights, null for none. */
        public final String overlap;
        /** The nights that count for this factor, oldest first, and which are after a day with it. */
        public final List<Point> points;
        public final List<Boolean> with;

        Result(Factor factor, int state, int nWith, int nWithout, int weeksWith, Measure[] m, int headline, String overlap, List<Point> points,
                List<Boolean> with) {
            this.factor = factor;
            this.state = state;
            this.nWith = nWith;
            this.nWithout = nWithout;
            this.weeksWith = weeksWith;
            this.m = m;
            this.headline = headline;
            this.overlap = overlap;
            this.points = points;
            this.with = with;
        }

        public boolean clear() {
            return state == NUMBER && m[headline].clear();
        }

        /** 0 clear, 1 a number not clear yet, 2 too few: the order of the rows. */
        public int rank() {
            return state != NUMBER ? 2 : clear() ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ the computation

    /**
     * Every night of the 365 days up to {@code today} with its usual values, from {@code nights} (main nights by their morning,
     * any order, also the 90 days before the 365 for the usual). Nights that touch a left out time are not points (they still
     * count in the usual of the nights after them, as in readiness).
     */
    public static List<Point> points(List<Night> nights, LocalDate today) {
        List<Night> sorted = new ArrayList<Night>(nights);
        Collections.sort(sorted, new Comparator<Night>() {
            @Override
            public int compare(Night a, Night b) {
                return a.day.compareTo(b.day);
            }
        });
        LocalDate first = today.minusDays(DAYS - 1);
        List<Point> out = new ArrayList<Point>();
        int lo = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Night n = sorted.get(i);
            if (n.day.isBefore(first) || n.day.isAfter(today) || n.leftOut) continue;
            LocalDate windowFrom = n.day.minusDays(USUAL_DAYS);
            while (lo < i && sorted.get(lo).day.isBefore(windowFrom)) lo++;
            // the earlier nights of the window (a second night stored for the same morning is not one of them)
            int end = i;
            while (end > lo && !sorted.get(end - 1).day.isBefore(n.day)) end--;
            double[] value = n.v.clone(), usual = new double[MEASURES], diff = new double[MEASURES];
            for (int k = 0; k < MEASURES; k++) {
                double[] before = new double[end - lo];
                for (int j = lo; j < end; j++) before[j - lo] = sorted.get(j).v[k];
                usual[k] = Baselines.usual(before, USUAL_MIN);
                diff[k] = difference(k, value[k], usual[k]);
            }
            out.add(new Point(n.day.minusDays(1), value, usual, diff));
        }
        return out;
    }

    /** A night's value against the usual: HRV in % of it, the others in their own unit; NaN when either is missing. */
    public static double difference(int measure, double value, double usual) {
        if (Double.isNaN(value) || Double.isNaN(usual) || Double.isInfinite(value) || Double.isInfinite(usual)) return Double.NaN;
        if (measure == HRV) return usual > 0 ? 100 * (value / usual - 1) : Double.NaN;
        return value - usual;
    }

    /**
     * Each factor with a logged day in the {@link #DAYS} days up to today, in the order the card lists them (clear, then not clear
     * yet, then too few; more nights first).
     */
    public static List<Result> of(List<Night> nights, List<Factor> factors, LocalDate today) {
        List<Point> pts = points(nights, today);
        List<Result> out = new ArrayList<Result>();
        LocalDate from = today.minusDays(DAYS);
        for (Factor f : factors) {
            // one row per factor logged in the 365 days (today included, its night is still to come): an old one shows no row
            boolean recent = false;
            for (LocalDate d : f.days) recent |= !d.isBefore(from) && !d.isAfter(today);
            if (recent) out.add(one(pts, f, factors));
        }
        sort(out);
        return out;
    }

    static void sort(List<Result> out) {
        Collections.sort(out, new Comparator<Result>() {
            @Override
            public int compare(Result a, Result b) {
                int c = Integer.compare(a.rank(), b.rank());
                return c != 0 ? c : Integer.compare(b.nWith, a.nWith);
            }
        });
    }

    /** One factor against the points ({@code all} for the overlap check among the tags). */
    static Result one(List<Point> pts, Factor f, List<Factor> all) {
        List<Point> mine = new ArrayList<Point>();
        List<Boolean> with = new ArrayList<Boolean>();
        int nWith = 0, nWithout = 0;
        java.util.Set<LocalDate> weeks = new java.util.HashSet<LocalDate>();
        for (Point p : pts) {
            if (!f.counts(p.tagDay) || !anyValue(p)) continue;
            boolean w = f.days.contains(p.tagDay);
            mine.add(p);
            with.add(w);
            if (w) {
                nWith++;
                weeks.add(week(p.tagDay));
            } else {
                nWithout++;
            }
        }
        Measure[] m = new Measure[MEASURES];
        for (int k = 0; k < MEASURES; k++) m[k] = measure(mine, with, k, f.key);
        int headline = -1;
        for (int k = 0; k < MEASURES && headline < 0; k++) if (m[k].enough()) headline = k;
        int state;
        if (nWith < MIN_WITH) state = FEW_WITH;
        else if (nWithout < MIN_WITHOUT) state = FEW_WITHOUT;
        else if (headline < 0) state = FEW_VALUES;
        else state = NUMBER;
        if (state != NUMBER) headline = -1;
        return new Result(f, state, nWith, nWithout, weeks.size(), m, headline, overlap(f, mine, with, all), mine, with);
    }

    static boolean anyValue(Point p) {
        for (double x : p.diff) if (!Double.isNaN(x)) return true;
        return false;
    }

    /** The Monday of {@code d}'s calendar week. */
    static LocalDate week(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    /** One measure: the difference and its 99 % range from resampling whole weeks; no range below the minimums. */
    static Measure measure(List<Point> pts, List<Boolean> with, int k, String key) {
        // per week: sum and count with, sum and count without
        TreeMap<LocalDate, double[]> byWeek = new TreeMap<LocalDate, double[]>();
        int nw = 0, no = 0;
        long seed = 292L * 31 + k;
        for (int i = 0; i < key.length(); i++) seed = seed * 31 + key.charAt(i);
        for (int i = 0; i < pts.size(); i++) {
            double x = pts.get(i).diff[k];
            if (Double.isNaN(x)) continue;
            boolean w = with.get(i);
            LocalDate wk = week(pts.get(i).tagDay);
            double[] s = byWeek.get(wk);
            if (s == null) byWeek.put(wk, s = new double[4]);
            if (w) {
                s[0] += x;
                s[1]++;
                nw++;
            } else {
                s[2] += x;
                s[3]++;
                no++;
            }
            seed = seed * 31 + pts.get(i).tagDay.toEpochDay();
            seed = seed * 31 + (w ? 1 : 0);
            seed = seed * 31 + Double.doubleToLongBits(x);
        }
        int weeksWith = 0, weeksWithout = 0;
        for (double[] s : byWeek.values()) {
            if (s[1] > 0) weeksWith++;
            if (s[3] > 0) weeksWithout++;
        }
        // the side with fewer weeks sets how sure the range can be, as in Trends.compare (code review of 418df41: with t and the
        // correction from the weeks with the factor alone, a factor on almost every day was called clear 3 to 5 times in 100
        // without any effect)
        int fewer = Math.min(weeksWith, weeksWithout);
        if (nw == 0 || no == 0) return new Measure(k, nw, no, weeksWith, Double.NaN, Double.NaN, Double.NaN);
        double[][] w = byWeek.values().toArray(new double[0][]);
        double diff = ratio(w, null);
        if (nw < MIN_WITH || no < MIN_WITHOUT || fewer < MIN_WEEKS) return new Measure(k, nw, no, weeksWith, diff, Double.NaN, Double.NaN);
        int kk = w.length;
        Random r = new Random(seed);
        double[] draws = new double[DRAWS];
        int[] pick = new int[kk];
        int got = 0;
        // a draw without a week of either side has no difference: drawn again (at most 10 times as many tries in all)
        for (int tries = 0; got < DRAWS && tries < DRAWS * 10; tries++) {
            for (int i = 0; i < kk; i++) pick[i] = r.nextInt(kk);
            double d = ratio(w, pick);
            if (!Double.isNaN(d)) draws[got++] = d;
        }
        if (got < 2) return new Measure(k, nw, no, weeksWith, diff, Double.NaN, Double.NaN);
        if (got < DRAWS) draws = java.util.Arrays.copyOf(draws, got);
        double sd = Stats.sd(draws);
        double half = t99(fewer - 1) * Math.sqrt(sd * sd * fewer / (fewer - 1.0));
        return new Measure(k, nw, no, weeksWith, diff, diff - half, diff + half);
    }

    /** Mean with minus mean without of the weeks {@code pick} (all weeks for null); NaN when a side has no night. */
    static double ratio(double[][] weeks, int[] pick) {
        double sw = 0, nw = 0, so = 0, no = 0;
        int n = pick == null ? weeks.length : pick.length;
        for (int i = 0; i < n; i++) {
            double[] s = weeks[pick == null ? i : pick[i]];
            sw += s[0];
            nw += s[1];
            so += s[2];
            no += s[3];
        }
        return nw == 0 || no == 0 ? Double.NaN : sw / nw - so / no;
    }

    /** Student's t for a two sided 99 % range by degrees of freedom (index 0 unused); 120 and more about the normal 2.576. */
    static final double[] T99 = {Double.NaN, 63.657, 9.925, 5.841, 4.604, 4.032, 3.707, 3.499, 3.355, 3.250, 3.169, 3.106, 3.055, 3.012,
        2.977, 2.947, 2.921, 2.898, 2.878, 2.861, 2.845, 2.831, 2.819, 2.807, 2.797, 2.787, 2.779, 2.771, 2.763, 2.756, 2.750};

    static double t99(int df) {
        if (df < 1) return Double.NaN;
        if (df < T99.length) return T99[df];
        if (df <= 40) return 2.750 + (2.704 - 2.750) * (df - 30) / 10.0;
        if (df <= 60) return 2.704 + (2.660 - 2.704) * (df - 40) / 20.0;
        if (df <= 120) return 2.660 + (2.617 - 2.660) * (df - 60) / 60.0;
        return 2.576;
    }

    /** The tag on more than half of this factor's nights with it (the largest share; the first listed on a tie). Tags only. */
    static String overlap(Factor f, List<Point> pts, List<Boolean> with, List<Factor> all) {
        if (f.kind != TAG || all == null) return null;
        int n = 0;
        for (Boolean b : with) if (b) n++;
        if (n == 0) return null;
        String best = null;
        int bestCount = 0;
        for (Factor o : all) {
            if (o == f || o.kind != TAG || o.key.equals(f.key)) continue;
            int c = 0;
            for (int i = 0; i < pts.size(); i++) if (with.get(i) && o.days.contains(pts.get(i).tagDay)) c++;
            if (c * 2 > n && c > bestCount) {
                best = o.name;
                bestCount = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the factors from the logged days

    /**
     * The tag factors from the days in force ({@code days}, each day's keys read as one factor, {@code LifeTags.Known.fold}):
     * one per key in {@code names} (key to name, in the order listed) that has a logged day. They count from the first day
     * anything was logged; an own tag ({@code made} its day, null for a fixed one) not before the day it was made.
     */
    public static List<Factor> tags(Map<LocalDate, List<String>> days, Map<String, String> names, Map<String, LocalDate> made) {
        List<Factor> out = new ArrayList<Factor>();
        if (days == null || days.isEmpty()) return out;
        LocalDate first = null;
        Map<String, Set<LocalDate>> by = new HashMap<String, Set<LocalDate>>();
        for (Map.Entry<LocalDate, List<String>> e : days.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (first == null || e.getKey().isBefore(first)) first = e.getKey();
            for (String k : e.getValue()) {
                Set<LocalDate> s = by.get(k);
                if (s == null) by.put(k, s = new java.util.HashSet<LocalDate>());
                s.add(e.getKey());
            }
        }
        for (Map.Entry<String, String> n : names.entrySet()) {
            Set<LocalDate> s = by.get(n.getKey());
            if (s == null || s.isEmpty()) continue;
            LocalDate from = first;
            LocalDate m = made == null ? null : made.get(n.getKey());
            if (m != null && m.isAfter(from)) from = m;
            // a day logged before the own tag's key says it was made (a clock set wrong) still counts
            for (LocalDate d : s) if (d.isBefore(from)) from = d;
            out.add(new Factor(n.getKey(), n.getValue(), TAG, s, from, null));
        }
        return out;
    }

    /**
     * The period tracker's two factors from {@code cycles} (for people who use it, with a logged period): Period days (the logged
     * ones) and After ovulation (the measured rise of a cycle, else the estimate from the next start; a running cycle without a
     * measured rise is unknown). Both count from the first logged period (at the earliest the day before the 365 days) up to
     * {@code today}. Empty without a period.
     */
    public static List<Factor> cycle(Cycles cycles, LocalDate today) {
        List<Factor> out = new ArrayList<Factor>();
        if (cycles == null || cycles.periods.isEmpty()) return out;
        // only the days the comparison can reach (the 365 days and the day before them); earlier days cost time on every load
        LocalDate first = cycles.periods.get(0).start, window = today.minusDays(DAYS + 1);
        LocalDate from = first.isBefore(window) ? window : first;
        Set<LocalDate> period = new java.util.HashSet<LocalDate>(), after = new java.util.HashSet<LocalDate>(),
                unsure = new java.util.HashSet<LocalDate>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            if (cycles.periodOn(d) != null) {
                period.add(d);
                continue;
            }
            String ph = cycles.phase(d);
            if (ph == null) {
                unsure.add(d);
            } else if (ph.startsWith("after ovulation")) {
                // a running cycle (no next start logged) counts only with its measured rise
                boolean running = cycles.periodBefore(d) == cycles.periods.get(cycles.periods.size() - 1);
                if (running && !ph.endsWith("(measured)")) unsure.add(d);
                else after.add(d);
            } else if (ph.startsWith("expected period")) {
                unsure.add(d);
            } else if (cycles.periodBefore(d) == cycles.periods.get(cycles.periods.size() - 1) && !ph.endsWith("(measured)")) {
                // before ovulation in a running cycle without a measured rise: not known yet whether ovulation has come
                unsure.add(d);
            }
        }
        out.add(new Factor(KEY_PERIOD, PERIOD_NAME, PERIOD, period, from, null));
        out.add(new Factor(KEY_OVULATION, OVULATION_NAME, AFTER_OVULATION, after, from, unsure));
        return out;
    }

    public static final String KEY_PERIOD = "cycle:period", KEY_OVULATION = "cycle:after_ovulation";
    public static final String PERIOD_NAME = "Period days", OVULATION_NAME = "After ovulation";

    // ------------------------------------------------------------------ texts

    public static final String TITLE = "Life factors and your nights";
    public static final String INTRO = "Each night against your usual: the nights after days with a factor and the nights after days without it, the last 12 months.";
    public static final String NOT_ADVICE = "These are patterns in your own nights, not medical advice. A pattern does not show what caused it.";
    public static final String NOTHING = "Log factors on Today. From 10 days with a factor and 30 without it, this card shows how your nights went after it.";
    public static final String NO_RING = "This needs nights from the ring.";
    public static final String CYCLE_TEMP = "Ovulation is partly found from your night temperature, so a warmer temperature after it is expected.";
    public static final String UNREADABLE = "How your nights went after each factor could not be worked out just now. Try again later.";
    public static final String CHART_NOTE = "Each dot is one night against your usual. Filled: after a day with the factor. Grey: without it. Touch a dot to read it.";
    public static final String BAR_NOTE = "The dot is the difference, the band its range, the line zero.";
    /** The chips over the evidence chart, by measure. */
    public static final String[] CHIP = {"HRV", "Lowest heart rate", "Temperature", "Sleep score"};
    static final String[] SUBJECT = {"your HRV", "your lowest heart rate", "your temperature", "your sleep score"};
    static final String[] LABEL = {"HRV", "Lowest heart rate", "Temperature", "Sleep score"};

    /** "12 %", "2.1 bpm", "0.05 °C", "3 points" of the size of {@code x}. */
    public static String amount(int measure, double x, Locale l) {
        double a = Math.abs(x);
        switch (measure) {
            case HRV:
                return Math.round(a) + " %";
            case RHR:
                return String.format(l, "%.1f", a) + " bpm";
            case TEMP:
                return String.format(l, "%.2f", a) + " °C";
            default:
                long p = Math.round(a);
                return p + (p == 1 ? " point" : " points");
        }
    }

    /** The bare number of {@link #amount} (no unit), for the second end of a range. */
    static String number(int measure, double x, Locale l) {
        double a = Math.abs(x);
        switch (measure) {
            case RHR:
                return String.format(l, "%.1f", a);
            case TEMP:
                return String.format(l, "%.2f", a);
            default:
                return String.valueOf(Math.round(a));
        }
    }

    /** True when {@code x} shows as no change at all. */
    static boolean none(int measure, double x) {
        return Double.parseDouble(number(measure, x, Locale.ROOT)) == 0;
    }

    /** "higher" or "lower" ("warmer" or "cooler" for the temperature). */
    static String word(int measure, double x) {
        if (measure == TEMP) return x > 0 ? "warmer" : "cooler";
        return x > 0 ? "higher" : "lower";
    }

    /** "12 % lower", or "about the same" when it shows as 0 (issue #474: "no change to 2 % higher" read like an increase). */
    static String said(int measure, double x, Locale l) {
        return none(measure, x) ? "about the same" : amount(measure, x, l) + " " + word(measure, x);
    }

    /** "After a day with Alcohol", "In the nights after ovulation", "On period days": the start of the sentence. */
    public static String lead(Factor f) {
        if (f.kind == AFTER_OVULATION) return "In the nights after ovulation";
        if (f.kind == PERIOD) return "On period days";
        return "After a day with " + f.name;
    }

    /** " that night" for a tag (the night that starts that evening), nothing for the cycle factors. */
    static String thatNight(Factor f) {
        return f.kind == TAG ? " that night" : "";
    }

    /** "(26 nights after Alcohol, 337 without)", "(165 nights after ovulation, 198 other nights)", "(63 period days, 300 other days)". */
    static String counts(Factor f, int with, int without) {
        if (f.kind == AFTER_OVULATION) return "(" + with + " nights after ovulation, " + without + " other nights)";
        if (f.kind == PERIOD) return "(" + with + " period days, " + without + " other days)";
        return "(" + with + " nights after " + f.name + ", " + without + " without)";
    }

    /** The touch readout's side: "after a day with Alcohol" or "after a day without Alcohol", "after ovulation" ... */
    static String side(Factor f, boolean with) {
        if (f.kind == AFTER_OVULATION) return with ? "after ovulation" : "before ovulation or a period day";
        if (f.kind == PERIOD) return with ? "a period day" : "not a period day";
        return (with ? "after a day with " : "after a day without ") + f.name;
    }

    /** The headline sentence of a factor, or its count when there is no number. */
    public static String headline(Result r, Locale l) {
        Factor f = r.factor;
        switch (r.state) {
            case FEW_WITH:
                if (f.kind == TAG) return r.nWith + " of " + MIN_WITH + " days logged with " + f.name + " that have a ring night after them. The effect shows from " + MIN_WITH + ".";
                return f.name + ": " + r.nWith + " of the " + MIN_WITH + " days needed so far, each with a ring night after it.";
            case FEW_WITHOUT:
                return f.name + " is on almost every day: " + r.nWithout + " of the " + MIN_WITHOUT + " nights without it that are needed so far.";
            case FEW_VALUES:
                return "Too few of these nights have values to compare yet.";
            default:
                break;
        }
        Measure m = r.m[r.headline];
        int k = r.headline;
        StringBuilder b = new StringBuilder(lead(f)).append(", ").append(SUBJECT[k]).append(" was ");
        b.append(none(k, m.diff) ? "about the same" : amount(k, m.diff, l) + " " + word(k, m.diff)).append(thatNight(f));
        b.append(' ').append(counts(f, m.nWith, m.nWithout));
        if (m.clear()) {
            double near = m.lo > 0 ? m.lo : m.hi, far = m.lo > 0 ? m.hi : m.lo;
            // a near end that shows as 0 reads "up to": "somewhere between 0 % and 4 % lower" would read as no change
            if (none(k, near)) b.append(", somewhere up to ").append(amount(k, far, l));
            else b.append(", somewhere between ").append(number(k, near, l)).append(k == HRV ? " %" : "").append(" and ").append(amount(k, far, l));
            b.append(' ').append(word(k, far)).append(". This is a pattern in your own data, not a general rule.");
        } else {
            b.append(". ").append(unclear(k, m.lo, m.hi, l));
        }
        return b.toString();
    }

    /** The short status of a factor's row: "HRV 12 % lower, clear (22 nights)", "4 of 10 days so far". */
    public static String status(Result r, Locale l) {
        switch (r.state) {
            case FEW_WITH:
                return r.nWith + " of " + MIN_WITH + " days so far";
            case FEW_WITHOUT:
                return r.nWithout + " of " + MIN_WITHOUT + " nights without it so far";
            case FEW_VALUES:
                return "Too few nights with a value";
            default:
                break;
        }
        Measure m = r.m[r.headline];
        int k = r.headline;
        return LABEL[k] + " " + (none(k, m.diff) ? "about the same" : amount(k, m.diff, l) + " " + word(k, m.diff)) + ", "
                + (m.clear() ? "clear" : "not clear yet") + " (" + m.nWith + " nights)";
    }

    /** A detail line of measure {@code k}: "Lowest heart rate: 2.1 bpm higher, between 0.8 and 3.4 higher. Clear." */
    public static String detail(Result r, int k, Locale l) {
        Measure m = r.m[k];
        if (!m.enough()) return LABEL[k] + ": too few nights with a value.";
        StringBuilder b = new StringBuilder(LABEL[k]).append(": ").append(none(k, m.diff) ? "about the same" : amount(k, m.diff, l) + " " + word(k, m.diff));
        if (m.clear()) {
            double near = m.lo > 0 ? m.lo : m.hi, far = m.lo > 0 ? m.hi : m.lo;
            if (none(k, near)) b.append(", up to ").append(number(k, far, l));
            else b.append(", between ").append(number(k, near, l)).append(" and ").append(number(k, far, l));
            b.append(' ').append(word(k, far)).append(". Clear.");
        } else {
            b.append(unclearDetail(k, m.lo, m.hi, l));
        }
        return b.toString();
    }

    /**
     * The headline's end for a range across zero: "It could be anything from 1 % lower to 2 % higher, so this is not clear yet.",
     * "... from about the same to 2 % higher ...", or "Your HRV stayed about the same across the whole range, so there is no
     * clear difference." when both ends show as 0 (review of f8ddbc5: "about the same, yet not clear" read as a contradiction).
     */
    public static String unclear(int k, double lo, double hi, Locale l) {
        if (none(k, lo) && none(k, hi)) return "Your " + measureName(k) + " stayed about the same across the whole range, so there is no clear difference.";
        return "It could be anything from " + said(k, lo, l) + " to " + said(k, hi, l) + ", so this is not clear yet.";
    }

    /** A detail line's end for a range across zero: ", could be 0.8 lower to 1.2 higher. Not clear yet." (no units, as the line's range). */
    public static String unclearDetail(int k, double lo, double hi, Locale l) {
        if (none(k, lo) && none(k, hi)) return ", about the same at both ends, no clear difference.";
        return ", could be " + rangeEnd(k, lo, l) + " to " + rangeEnd(k, hi, l) + ". Not clear yet.";
    }

    /** The measure's name inside a sentence: "HRV", "lowest heart rate", "temperature", "sleep score". */
    static String measureName(int k) {
        return k == HRV ? LABEL[k] : LABEL[k].toLowerCase(Locale.ROOT);
    }

    static String rangeEnd(int k, double x, Locale l) {
        return none(k, x) ? "about the same" : number(k, x, l) + " " + word(k, x);
    }

    /** "Often logged together with Alcohol, so the effect may belong to either." Null without an overlap or without a number. */
    public static String overlapLine(Result r) {
        return r.overlap == null || r.state != NUMBER ? null : "Often logged together with " + r.overlap + ", so the effect may belong to either.";
    }

    /** Touch on the evidence chart: "Night after Fri 7 Nov, after Alcohol" and "HRV 42 ms, your usual 48 (12 % under)". */
    public static String pointText(Result r, int i, int k, LocalDate today, Locale l) {
        Point p = r.points.get(i);
        boolean w = r.with.get(i);
        String day = p.tagDay.format(DateTimeFormatter.ofPattern(p.tagDay.getYear() == today.getYear() ? "EEE d MMM" : "EEE d MMM yyyy", l));
        String head = "Night after " + day + ", " + side(r.factor, w);
        if (Double.isNaN(p.value[k])) return head + "\n" + LABEL[k] + ": no value";
        if (Double.isNaN(p.usual[k])) return head + "\n" + LABEL[k] + " " + value(k, p.value[k], l) + ", no usual yet";
        double d = p.diff[k];
        String side = none(k, d) ? "as usual" : amount(k, d, l) + (d > 0 ? " over" : " under");
        return head + "\n" + LABEL[k] + " " + value(k, p.value[k], l) + ", your usual " + usual(k, p.usual[k], l) + " (" + side + ")";
    }

    /**
     * The usual with its unit (issue #474): "53 ms", "52.4 bpm", "35.80 °C", "78". The heart rate keeps its decimal (not
     * {@link #value}, which rounds it), so the difference in brackets still fits it.
     */
    public static String usual(int k, double v, Locale l) {
        // the precision is number()'s, so it lives in one place (review of f8ddbc5); a usual is never below 0
        String n = number(k, v, l);
        switch (k) {
            case HRV:
                return n + " ms";
            case RHR:
                return n + " bpm";
            case TEMP:
                return n + " °C";
            default:
                return n;
        }
    }

    /** A night's own value: "42 ms", "52 bpm", "35.92 °C", "78". */
    public static String value(int k, double v, Locale l) {
        switch (k) {
            case HRV:
                return Math.round(v) + " ms";
            case RHR:
                return Math.round(v) + " bpm";
            case TEMP:
                return String.format(l, "%.2f", v) + " °C";
            default:
                return String.valueOf(Math.round(v));
        }
    }

    /** The axis and chart label of a difference: "+12 %", "-2.1 bpm" ... as a signed short text. */
    public static String axis(int k, double x, Locale l) {
        String s = x > 0 && !none(k, x) ? "+" : x < 0 && !none(k, x) ? "−" : "";
        return s + amount(k, x, l).replace(" points", "").replace(" point", "");
    }

    // ------------------------------------------------------------------ beside the number (phase 3)

    /**
     * Under the lines beside readiness when at least one effect shows (phase 3, option A: display only). "the night's", not
     * "last night's": an earlier day shown says "That night" (review of 9cfb9c4), as {@link #BESIDE_NOTE_SLEEP} does.
     */
    public static final String BESIDE_NOTE_READY = "A pattern in your own nights, not a rule. Readiness does not change because of it: the night's readings already show the effect.";
    /** The same under Sleep's "Before this night" line. */
    public static final String BESIDE_NOTE_SLEEP = "A pattern in your own nights, not a rule. The sleep score itself does not change: it comes from the night's own readings.";
    /** The hint of the card with at least one effect line (it opens Body, where the card Life factors and your nights is). */
    public static final String BESIDE_HINT = "Tap for how your nights went after each factor on Body.";
    /** The hint of the card without one, as before phase 3. */
    public static final String LOGGED_HINT = "As you logged them on Today. Tap for the month on Body.";

    /**
     * Phase 3 (issue #292, option A): one sentence per tag of the day before ({@code keys}, in the order of the logged line) whose
     * result is clear and whose difference does not show as 0, for the card beside readiness and beside the night. Not clear
     * tags, tags without a result and the cycle factors get no line. Empty for null or empty input. Changes no number.
     */
    public static List<String> beside(List<Result> results, List<String> keys, LocalDate dayBefore, boolean shownIsToday, Locale l) {
        List<String> out = new ArrayList<String>();
        if (results == null || keys == null || dayBefore == null) return out;
        for (String key : keys) {
            Result r = null;
            for (Result x : results) if (x != null && x.factor.kind == TAG && x.factor.key.equals(key)) r = x;
            if (r == null || r.state != NUMBER || r.headline < 0 || !r.clear()) continue;
            int k = r.headline;
            Measure m = r.m[k];
            double last = Double.NaN;
            for (Point p : r.points) {
                if (!p.tagDay.equals(dayBefore)) continue;
                if (!Double.isNaN(p.value[k]) && !Double.isNaN(p.usual[k])) last = p.diff[k];
            }
            String s = besideLine(r.factor.name, k, m.diff, m.nWith, last, shownIsToday, l);
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * One line beside the number: "After a day with Alcohol, your HRV is usually 12 % lower that night (clear, from 26 nights after it). Last night: 9 %
     * lower than your usual." The second sentence only for a {@code last} difference (NaN for none); "That night" for an earlier
     * day shown. Null when {@code diff} shows as 0 (nothing to say beside the number).
     */
    public static String besideLine(String name, int k, double diff, int nights, double last, boolean shownIsToday, Locale l) {
        if (Double.isNaN(diff) || none(k, diff)) return null;
        StringBuilder b = new StringBuilder("After a day with ").append(name).append(", ").append(SUBJECT[k]).append(" is usually ")
                .append(amount(k, diff, l)).append(' ').append(word(k, diff)).append(" that night (clear, from ").append(nights)
                .append(nights == 1 ? " night after it)." : " nights after it).");
        if (!Double.isNaN(last) && !Double.isInfinite(last)) {
            b.append(shownIsToday ? " Last night: " : " That night: ").append(said(k, last, l)).append(" ").append(none(k, last) ? "as" : "than")
                    .append(" your usual.");
        }
        return b.toString();
    }

    /** Every fixed text, for the tests (plain, no dashes). */
    public static String[] texts() {
        return new String[] {TITLE, INTRO, NOT_ADVICE, NOTHING, NO_RING, CYCLE_TEMP, UNREADABLE, CHART_NOTE, BAR_NOTE, BESIDE_NOTE_READY, BESIDE_NOTE_SLEEP,
            BESIDE_HINT, LOGGED_HINT};
    }

    /** Days between two dates (for the chart's axis). */
    public static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
