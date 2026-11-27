package app.openring.core.analysis;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Trends (issue #374, phase 1): the days of the whole history grouped into calendar weeks (Monday to Sunday), months and
 * years, one dot per period with the middle half of its days as a band, and "this month against the last" with an honest
 * range. A day counts in the period of its stored {@code day} (the morning a night ends on, as Sleep shows it), so a trip or
 * a clock change files nights where the tabs show them. Everything is worked out from one small array of values per day
 * ({@link Day}, read once by {@code data/TrendDays}); a change of measure or period only groups it again. Nothing is stored.
 */
public final class Trends {
    private Trends() {}

    // ------------------------------------------------------------------ periods, sources, measures

    public static final int WEEKS = 0, MONTHS = 1, YEARS = 2;
    /** The period chips. */
    public static final String[] PERIOD_NAMES = {"Weeks", "Months", "Years"};
    /** A period with fewer days than this is a hollow dot (a rough average): a week 2, a month 7, a year 60. */
    public static final int[] FEW = {2, 7, 60};

    /** Where a day's value comes from (0: none; {@link #HAND}: a night entered by hand, issue #427). */
    public static final int NONE = 0, RING = 1, WATCH = 2, PHONE = 3, HAND = 4;
    static final String[] SOURCE_NAMES = {"", "ring", "watch", "phone", "by hand"};

    public static final int ASLEEP = 0, BEDTIME = 1, WAKE = 2, REGULARITY = 3, WAKE_UPS = 4, SLEEP_SCORE = 5, READINESS = 6, RHR = 7,
            HRV = 8, TEMP = 9, STEPS = 10, KCAL = 11, SCREEN = 12, COUNT = 13;

    /** The chip of each measure. */
    public static final String[] NAMES = {"Time asleep", "Bedtime", "Wake time", "Regularity", "Wake ups", "Sleep score", "Readiness",
        "Resting heart rate", "HRV", "Temperature", "Steps", "Active calories", "Screen time"};

    public static final int G_SLEEP = 0, G_READINESS = 1, G_ACTIVITY = 2, G_BODY = 3, G_SCREEN = 4;
    /** The group chips, as the tabs (Sleep, Ready, Activity, Body, Screen). */
    public static final String[] GROUP_NAMES = {"Sleep", "Readiness", "Activity", "Body", "Screen"};
    /** The measures of each group, the first one shown when the group is picked. */
    public static final int[][] GROUPS = {{ASLEEP, BEDTIME, WAKE, REGULARITY, WAKE_UPS, SLEEP_SCORE}, {READINESS, TEMP, HRV, RHR},
        {STEPS, KCAL}, {RHR, HRV, TEMP}, {SCREEN}};

    /** How a period's dot is worked out from its days. */
    static final int MEAN = 0, MEDIAN = 1, CLOCK = 2, SPREAD = 3;
    static final int[] KIND = {MEAN, CLOCK, CLOCK, SPREAD, MEAN, MEAN, MEAN, MEAN, MEAN, MEAN, MEAN, MEAN, MEDIAN};
    /** A value of the night (counted in nights) or of the day. */
    static final boolean[] NIGHT = {true, true, true, true, true, true, false, true, true, true, false, false, false};

    /** The group a tab opens Trends at: Sleep 1, Ready 2, Activity 3, Body 4, Screen 5 (Today 0 opens Sleep). */
    public static int groupOfTab(int tab) {
        switch (tab) {
            case 2: return G_READINESS;
            case 3: return G_ACTIVITY;
            case 4: return G_BODY;
            case 5: return G_SCREEN;
            default: return G_SLEEP;
        }
    }

    /** The column a measure reads: Regularity reads the bedtimes. */
    static int column(int measure) {
        return measure == REGULARITY ? BEDTIME : measure;
    }

    /** True for a measure counted in nights ("29 nights"), false for days. */
    public static boolean night(int measure) {
        return NIGHT[measure];
    }

    // ------------------------------------------------------------------ one day

    /**
     * One day's values ({@link #COUNT} measures, NaN for none; bedtime and wake time as clock minutes after midnight, 0 to 1440)
     * and where each comes from ({@link #RING}, {@link #WATCH}, {@link #PHONE}, {@link #HAND}). {@code detail}: the stage detail level its night
     * was analysed with (-1 unknown), for the note under wake ups.
     */
    public static final class Day {
        public final LocalDate date;
        public final double[] v = new double[COUNT];
        public final byte[] src = new byte[COUNT];
        public int detail = -1;
        /** The day's time zone id (its night's, else the one noted then), null when unknown: a trip marked on the chart (phase 2). */
        public String zone;

        public Day(LocalDate date) {
            this.date = date;
            Arrays.fill(v, Double.NaN);
        }

        /** Sets measure {@code m} to {@code value} from {@code source} (NaN leaves it empty). */
        public Day set(int m, double value, int source) {
            if (Double.isNaN(value) || Double.isInfinite(value)) return this;
            v[m] = value;
            src[m] = (byte) source;
            return this;
        }

        public boolean any() {
            for (double x : v) if (!Double.isNaN(x)) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ periods

    /** The first day of the period {@code d} lies in: the Monday of its week, the first of its month or of its year. */
    public static LocalDate start(LocalDate d, int period) {
        if (period == WEEKS) return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        if (period == MONTHS) return d.withDayOfMonth(1);
        return d.withDayOfYear(1);
    }

    /** The first day of the period after the one starting at {@code s}. */
    public static LocalDate next(LocalDate s, int period) {
        return period == WEEKS ? s.plusWeeks(1) : period == MONTHS ? s.plusMonths(1) : s.plusYears(1);
    }

    /** "Week of 2 Mar 2026", "March 2026", "2026". */
    public static String label(LocalDate start, int period) {
        if (period == WEEKS) return "Week of " + start.getDayOfMonth() + " " + start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + start.getYear();
        if (period == MONTHS) return monthName(start) + " " + start.getYear();
        return String.valueOf(start.getYear());
    }

    /** The short label under the chart: "2 Mar", "Mar 2026", "2026". */
    public static String axisLabel(LocalDate start, int period) {
        String mon = start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        if (period == WEEKS) return start.getDayOfMonth() + " " + mon;
        if (period == MONTHS) return mon + " " + start.getYear();
        return String.valueOf(start.getYear());
    }

    static String monthName(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** One dot of the chart: a period with its value, the middle half of its days and how many days count. */
    public static final class Bucket {
        public final LocalDate start, end;
        /** Days with a value, and of them from each source. */
        public int n;
        public final int[] bySource = new int[SOURCE_NAMES.length];
        /** The dot (mean, median, clock average or spread) and the band (25th to 75th percentile), NaN for none. */
        public double value = Double.NaN, lo = Double.NaN, hi = Double.NaN;
        /** Fewer days than {@link #FEW}: drawn hollow, "only 4 nights". */
        public boolean hollow;

        Bucket(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    /** A measure grouped into periods over the whole history, with every day's value for the faint dots behind them. */
    public static final class View {
        public final int measure, period;
        public final List<Bucket> buckets = new ArrayList<Bucket>();
        /** The days with a value and their value as drawn (clock times unwrapped around {@link #centre}). */
        public final List<LocalDate> dayDates = new ArrayList<LocalDate>();
        /**
         * Where each day's small dot goes: the period it belongs to, and its place in that period's column, from -0.4 (its
         * first day with a value) to 0.4 (its last), 0 for a period with one day. Each period has a column of the same width with
         * its dot in the middle, so a day's dot is always next to its period's dot (a year with only its last six weeks too).
         */
        public final List<Integer> dayBucket = new ArrayList<Integer>();
        public final List<Double> dayOffset = new ArrayList<Double>();
        public final List<Double> dayValues = new ArrayList<Double>();
        /** Clock measures: the middle of the whole history's times; values are drawn as this plus up to 12 hours either way. */
        public double centre = Double.NaN;
        /**
         * Wake ups: the stage detail level of every night counted ({@link SleepHistory}'s rule), -1 when they differ. Nights by
         * hand have none and are left out (issue #459); with only those, Detailed.
         */
        public int detail = StageFilter.DETAILED;

        View(int measure, int period) {
            this.measure = measure;
            this.period = period;
        }

        /** No day has a value. */
        public boolean empty() {
            return dayDates.isEmpty();
        }

        /** The bucket holding {@code d}, -1 when none. */
        public int indexOf(LocalDate d) {
            for (int i = 0; i < buckets.size(); i++) if (!d.isBefore(buckets.get(i).start) && !d.isAfter(buckets.get(i).end)) return i;
            return -1;
        }

        /** The lowest and highest of every value, band and day drawn, {lo, hi}; NaN without any. */
        public double[] range() {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double v : dayValues) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            for (Bucket b : buckets) {
                for (double v : new double[] {b.value, b.lo, b.hi}) {
                    if (Double.isNaN(v)) continue;
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
            return lo > hi ? new double[] {Double.NaN, Double.NaN} : new double[] {lo, hi};
        }
    }

    /** Minutes from {@code centre} to {@code v} the short way round the clock, -720 to below 720. */
    public static double around(double v, double centre) {
        return ((v - centre + 720) % 1440 + 1440) % 1440 - 720;
    }

    /**
     * {@code measure} of {@code days} grouped by {@code period}, from the first period with a value to the last, empty periods
     * in between kept (a gap). {@code today} is left out of the day measures that are not finished (steps, active calories,
     * screen time); the night that ended this morning counts.
     */
    public static View of(List<Day> days, int measure, int period, LocalDate today) {
        View out = new View(measure, period);
        int col = column(measure), kind = KIND[measure];
        List<Day> with = new ArrayList<Day>();
        for (Day d : days) {
            if (Double.isNaN(d.v[col])) continue;
            if (!finished(measure, d.date, today)) continue;
            with.add(d);
        }
        if (with.isEmpty()) return out;
        boolean clock = kind == CLOCK || kind == SPREAD;
        if (clock) {
            double[] all = new double[with.size()];
            for (int i = 0; i < all.length; i++) all[i] = with.get(i).v[col];
            out.centre = ClockAverage.of(all)[0];
        }
        LocalDate first = with.get(0).date, last = with.get(with.size() - 1).date;
        for (Day d : with) {
            if (d.date.isBefore(first)) first = d.date;
            if (d.date.isAfter(last)) last = d.date;
        }
        boolean firstDetail = true;
        for (LocalDate s = start(first, period); !s.isAfter(last); s = next(s, period)) {
            LocalDate e = next(s, period).minusDays(1);
            Bucket b = new Bucket(s, e);
            List<Double> vals = new ArrayList<Double>();
            for (Day d : with) {
                if (d.date.isBefore(s) || d.date.isAfter(e)) continue;
                vals.add(d.v[col]);
                b.n++;
                b.bySource[Math.max(0, Math.min(SOURCE_NAMES.length - 1, d.src[col]))]++;
                // a night by hand has no stages, so no detail level: its wake ups count, the note does not ask (issue #459)
                if (measure == WAKE_UPS && d.src[col] != HAND) {
                    int lv = d.detail < 0 ? StageFilter.DETAILED : StageFilter.clampLevel(d.detail);
                    if (firstDetail) out.detail = lv;
                    else if (out.detail >= 0 && WakeUps.shortestKeptMin(out.detail) != WakeUps.shortestKeptMin(lv)) out.detail = -1;
                    firstDetail = false;
                }
            }
            double[] v = Stats.toArray(vals);
            b.hollow = b.n < FEW[period];
            if (b.n > 0) fill(b, v, kind, out.centre);
            // a spread's small dots: how far each bedtime was from the period's own clock average (none for a lone night)
            double mid = kind == SPREAD && b.n > 1 ? ClockAverage.of(v)[0] : Double.NaN;
            int startAt = out.dayDates.size();
            for (Day d : with) {
                if (d.date.isBefore(s) || d.date.isAfter(e)) continue;
                double x = d.v[col];
                if (kind == CLOCK) x = out.centre + around(x, out.centre);
                else if (kind == SPREAD) x = Math.abs(around(x, mid));
                if (Double.isNaN(x)) continue;
                out.dayDates.add(d.date);
                out.dayValues.add(x);
            }
            // the days of this period spread over its column by date, from its first day with a value to its last
            int count = out.dayDates.size() - startAt;
            if (count > 0) {
                long a = out.dayDates.get(startAt).toEpochDay(), z = a;
                for (int j = startAt; j < out.dayDates.size(); j++) {
                    a = Math.min(a, out.dayDates.get(j).toEpochDay());
                    z = Math.max(z, out.dayDates.get(j).toEpochDay());
                }
                for (int j = startAt; j < out.dayDates.size(); j++) {
                    out.dayBucket.add(out.buckets.size());
                    out.dayOffset.add(z == a ? 0.0 : 0.4 * (2.0 * (out.dayDates.get(j).toEpochDay() - a) - (z - a)) / (z - a));
                }
            }
            out.buckets.add(b);
        }
        return out;
    }

    /** The dot and band of a period from its values; a spread (NaN for one night) has no band. */
    static void fill(Bucket b, double[] v, int kind, double centre) {
        if (kind == MEAN || kind == MEDIAN) {
            b.value = kind == MEAN ? Stats.mean(v) : Stats.median(v);
            b.lo = Stats.percentile(v, 0.25);
            b.hi = Stats.percentile(v, 0.75);
        } else if (kind == CLOCK) {
            // around the period's own clock average, so 23:30 to 00:30 is a one hour band, never the whole day
            double m = ClockAverage.of(v)[0];
            double[] d = new double[v.length];
            for (int i = 0; i < v.length; i++) d[i] = around(v[i], m);
            double shown = centre + around(m, centre);
            b.value = shown;
            b.lo = shown + Stats.percentile(d, 0.25);
            b.hi = shown + Stats.percentile(d, 0.75);
        } else {
            b.value = ClockAverage.of(v)[1];
        }
    }

    /** False for today's steps, active calories and screen time (the day is not over); everything else counts. */
    static boolean finished(int measure, LocalDate day, LocalDate today) {
        if (measure != STEPS && measure != KCAL && measure != SCREEN) return true;
        return day.isBefore(today);
    }

    // ------------------------------------------------------------------ enough history

    /** Days from the first day with any value to {@code today}, both counted; 0 without any. */
    public static int daysSoFar(List<Day> days, LocalDate today) {
        LocalDate first = null;
        for (Day d : days) if (d.any() && (first == null || d.date.isBefore(first))) first = d.date;
        if (first == null || first.isAfter(today)) return 0;
        return (int) ChronoUnit.DAYS.between(first, today) + 1;
    }

    /** At least two months of history: the first day with a value is two calendar months or more before {@code today}. */
    public static boolean enough(List<Day> days, LocalDate today) {
        LocalDate first = null;
        for (Day d : days) if (d.any() && (first == null || d.date.isBefore(first))) first = d.date;
        return first != null && !first.isAfter(today.minusMonths(2));
    }

    /** "Trends need at least two months. You have 23 days so far." */
    public static String tooShort(int daysSoFar) {
        if (daysSoFar <= 0) return "Trends need at least two months. You have no days with data yet.";
        return "Trends need at least two months. You have " + (daysSoFar == 1 ? "1 day" : daysSoFar + " days") + " so far.";
    }

    // ------------------------------------------------------------------ texts of one period

    /** "7h 5m" as {@code Ui.hm}. */
    static String hm(double minutes) {
        long m = Math.round(minutes);
        return (m / 60) + "h " + (m % 60) + "m";
    }

    /** "23:40" for clock minutes, any number of days around (the drawn values go past midnight). */
    public static String clock(double min) {
        int m = (int) (((Math.round(min) % 1440) + 1440) % 1440);
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** A value of {@code measure} with its unit: "7h 5m", "23:40", "34 min", "2.3", "78", "54 bpm", "+0.15 °C", "8,412". */
    public static String value(int measure, double v, Locale l) {
        if (Double.isNaN(v)) return "--";
        switch (measure) {
            case ASLEEP:
            case SCREEN:
                return hm(v);
            case BEDTIME:
            case WAKE:
                return clock(v);
            case REGULARITY:
                return Math.round(v) + " min";
            case WAKE_UPS:
                return String.format(l, "%.1f", v);
            case RHR:
                return Math.round(v) + " bpm";
            case HRV:
                return Math.round(v) + " ms";
            case TEMP:
                return (v >= 0.005 ? "+" : "") + String.format(l, "%.2f", Math.abs(v) < 0.005 ? 0.0 : v) + " °C";
            case STEPS:
                return String.format(l, "%,d", Math.round(v));
            case KCAL:
                return Math.round(v) + " kcal";
            default:
                return String.valueOf(Math.round(v));
        }
    }

    /** The value of a period as the readout says it: "7h 5m asleep on average", "asleep at 23:40 on average". */
    static String stat(int measure, double v, Locale l) {
        String s = value(measure, v, l);
        switch (measure) {
            case ASLEEP: return s + " asleep on average";
            case BEDTIME: return "asleep at " + s + " on average";
            case WAKE: return "awake at " + s + " on average";
            case REGULARITY: return "bedtimes vary by " + s;
            case WAKE_UPS: return s + " wake ups a night on average";
            case SLEEP_SCORE: return "sleep score " + s + " on average";
            case READINESS: return "readiness " + s + " on average";
            case RHR: return "resting heart rate " + s + " on average";
            case HRV: return "HRV " + s + " on average";
            case TEMP: return "temperature " + s + " from your usual on average";
            case STEPS: return s + " steps a day on average";
            case KCAL: return s + " active a day on average";
            default: return s + " screen time on a typical day";
        }
    }

    /** "29 nights", "only 4 nights", "29 nights: 20 ring, 9 watch" (the devices named when more than one counted). */
    static String count(Bucket b, int measure) {
        String unit = NIGHT[measure] ? (b.n == 1 ? "night" : "nights") : (b.n == 1 ? "day" : "days");
        String s = (b.hollow ? "only " : "") + b.n + " " + unit;
        int kinds = 0;
        for (int i = 1; i < SOURCE_NAMES.length; i++) if (b.bySource[i] > 0) kinds++;
        if (kinds < 2) return s;
        StringBuilder sb = new StringBuilder(s).append(":");
        boolean first = true;
        for (int i = 1; i < SOURCE_NAMES.length; i++) {
            if (b.bySource[i] == 0) continue;
            sb.append(first ? " " : ", ").append(b.bySource[i]).append(' ').append(SOURCE_NAMES[i]);
            first = false;
        }
        return sb.toString();
    }

    /**
     * The touch readout of period {@code i}: "March 2026: 7h 5m asleep on average, middle half 6h 40m to 7h 30m, 29 nights: 20
     * ring, 9 watch".
     */
    public static String readout(View view, int i, Locale l) {
        Bucket b = view.buckets.get(i);
        String head = label(b.start, view.period) + ": ";
        String none = NIGHT[view.measure] ? "no nights" : "no days";
        if (b.n == 0) return head + none;
        if (Double.isNaN(b.value)) return head + "too few nights for a spread, " + count(b, view.measure);
        StringBuilder s = new StringBuilder(head).append(stat(view.measure, b.value, l));
        if (KIND[view.measure] != SPREAD && b.n > 1) {
            s.append(", middle half ").append(value(view.measure, b.lo, l)).append(" to ").append(value(view.measure, b.hi, l));
        }
        return s.append(", ").append(count(b, view.measure)).toString();
    }

    /** The short readout on the chart itself: "March 2026" and the value. */
    public static String tip(View view, int i, Locale l) {
        Bucket b = view.buckets.get(i);
        return label(b.start, view.period) + "\n" + (b.n == 0 ? "no data" : value(view.measure, b.value, l));
    }

    /** The note under the chart: what dot, band and hollow mean for this period and measure. */
    public static String note(int measure, int period) {
        String each = period == WEEKS ? "week" : period == MONTHS ? "month" : "year";
        String unit = NIGHT[measure] ? "nights" : "days";
        String few = "Hollow: fewer than " + FEW[period] + " " + unit + ".";
        if (KIND[measure] == SPREAD) {
            return "Dot: how much your bedtimes vary in each " + each + ". Small dots: how far each bedtime was from that " + each
                    + "'s average. " + few + " Touch to read.";
        }
        String dot = KIND[measure] == MEDIAN ? "Dot: the typical day (median) of each " + each + "." : "Dot: the average of each " + each + ".";
        return dot + " Band: the middle half of its " + unit + ". Small dots: each " + (NIGHT[measure] ? "night" : "day") + ". " + few + " Touch to read.";
    }

    /** One line on what the measure is (every number on screen gets one). */
    public static String explain(int measure, int detail) {
        switch (measure) {
            case ASLEEP: return "Time asleep each night, without the time awake in bed.";
            case BEDTIME: return "The clock time you fell asleep each night.";
            case WAKE: return "The clock time you woke up for the day.";
            case REGULARITY: return "How much your bedtime moves from night to night (its spread). Under 30 min is very regular, as Further analysis says.";
            case WAKE_UPS: return "Wake ups each night. " + WakeUps.averageLine(detail) + ".";
            case SLEEP_SCORE: return "The sleep score of each night, 0 to 100.";
            case READINESS: return "Readiness of each day, 0 to 100.";
            case RHR: return "The lowest heart rate of each night.";
            case HRV: return "Average heart rate variability while asleep each night. The ring measures it, a watch does not.";
            case TEMP: return "Night skin temperature against your usual. The ring measures it, a watch does not.";
            case STEPS: return "Steps of each day the device was worn for an hour or more (days without it are left out, not zero).";
            case KCAL: return "Active calories of each day worn for an hour or more (days without it are left out, not zero).";
            default: return "Screen time of each whole day this phone recorded, without the apps you left out.";
        }
    }

    // ------------------------------------------------------------------ this month against the last

    /** Draws of the resampling, and whole weeks each month needs at least. */
    public static final int DRAWS = 2000, MIN_WEEKS = 3, MIN_DAYS = 7;
    public static final String TOO_FEW_NOW = "Too few days this month to compare yet.";
    /** Wake ups counted at stage detail levels that keep different wake ups: a changed setting is no change in sleep. */
    public static final String MIXED_DETAIL = "Wake ups were counted at different stage detail levels in these two months, so they are not compared.";

    /** The comparison: the difference of this month so far to the last month, its 90 % range, and the sentence. */
    public static final class Compare {
        public final boolean enough;
        public final double diff, lo, hi;
        /** Whole weeks with a value on the side with fewer of them; Student's t for the range uses one less (0 when not compared). */
        public final int weeks;
        public final String text;

        Compare(boolean enough, double diff, double lo, double hi, int weeks, String text) {
            this.enough = enough;
            this.diff = diff;
            this.lo = lo;
            this.hi = hi;
            this.weeks = weeks;
            this.text = text;
        }

        static Compare not(String text) {
            return new Compare(false, Double.NaN, Double.NaN, Double.NaN, 0, text);
        }
    }

    /** The values of {@code measure} in [{@code from}, {@code to}] in blocks of 7 days from {@code from} (the last may be shorter). */
    static List<double[]> blocks(List<Day> days, int measure, LocalDate from, LocalDate to) {
        int col = column(measure);
        int n = (int) ChronoUnit.DAYS.between(from, to) + 1;
        List<List<Double>> b = new ArrayList<List<Double>>();
        for (int i = 0; i < (n + 6) / 7; i++) b.add(new ArrayList<Double>());
        for (Day d : days) {
            if (d.date.isBefore(from) || d.date.isAfter(to) || Double.isNaN(d.v[col])) continue;
            b.get((int) ChronoUnit.DAYS.between(from, d.date) / 7).add(d.v[col]);
        }
        List<double[]> out = new ArrayList<double[]>();
        for (List<Double> x : b) out.add(Stats.toArray(x));
        return out;
    }

    /** Whole weeks (7 days all in the range) with at least one value. */
    static int wholeWeeks(List<double[]> blocks, LocalDate from, LocalDate to) {
        int n = (int) ChronoUnit.DAYS.between(from, to) + 1, whole = 0;
        for (int i = 0; i < blocks.size(); i++) if ((i + 1) * 7 <= n && blocks.get(i).length > 0) whole++;
        return whole;
    }

    static int values(List<double[]> blocks) {
        int n = 0;
        for (double[] b : blocks) n += b.length;
        return n;
    }

    /** The statistic of pooled values: mean, median, or for clock times their mean around {@code centre}, or the spread. */
    static double statistic(double[] v, int kind, double centre) {
        if (kind == MEAN) return Stats.mean(v);
        if (kind == MEDIAN) return Stats.median(v);
        double[] u = new double[v.length];
        for (int i = 0; i < v.length; i++) u[i] = around(v[i], centre);
        return kind == CLOCK ? Stats.mean(u) : Stats.sd(u);
    }

    static double[] pool(List<double[]> blocks, int[] pick) {
        int n = 0;
        for (int i : pick) n += blocks.get(i).length;
        double[] out = new double[n];
        int k = 0;
        for (int i : pick) for (double x : blocks.get(i)) out[k++] = x;
        return out;
    }

    static int[] all(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    /**
     * This month so far (today left out, it is not over) against the whole last month, with a 90 % range from resampling
     * whole weeks ({@link #DRAWS} draws, the seed from the values, so the same history always gives the same sentence; the
     * spread of the draws widened for the few weeks a month has, see {@link #T90}). Each month needs {@link #MIN_WEEKS} whole
     * weeks with a value and {@link #MIN_DAYS} days, else it says so.
     */
    public static Compare compare(List<Day> days, int measure, LocalDate today, Locale l) {
        LocalDate thisFrom = today.withDayOfMonth(1), thisTo = today.minusDays(1);
        LocalDate lastFrom = thisFrom.minusMonths(1), lastTo = thisFrom.minusDays(1);
        String lastName = monthName(lastFrom);
        if (thisTo.isBefore(thisFrom)) return Compare.not(TOO_FEW_NOW);
        List<double[]> now = blocks(days, measure, thisFrom, thisTo), before = blocks(days, measure, lastFrom, lastTo);
        int wn = wholeWeeks(now, thisFrom, thisTo), wb = wholeWeeks(before, lastFrom, lastTo);
        if (wn < MIN_WEEKS || values(now) < MIN_DAYS) return Compare.not(TOO_FEW_NOW);
        if (wb < MIN_WEEKS || values(before) < MIN_DAYS) return Compare.not("Too few days in " + lastName + " to compare.");
        if (measure == WAKE_UPS && !oneRule(days, lastFrom, thisTo)) return Compare.not(MIXED_DETAIL);
        // only blocks with a value are drawn (an empty week drawn only makes a draw smaller)
        now = withValues(now);
        before = withValues(before);
        int kind = KIND[measure];
        double centre = Double.NaN;
        if (kind == CLOCK || kind == SPREAD) {
            double[] both = pool(now, all(now.size()));
            double[] b2 = pool(before, all(before.size()));
            double[] all = Arrays.copyOf(both, both.length + b2.length);
            System.arraycopy(b2, 0, all, both.length, b2.length);
            centre = ClockAverage.of(all)[0];
        }
        double diff = statistic(pool(now, all(now.size())), kind, centre) - statistic(pool(before, all(before.size())), kind, centre);
        long seed = 374;
        for (double[] b : now) for (double x : b) seed = seed * 31 + Double.doubleToLongBits(x);
        seed = seed * 17 + measure;
        for (double[] b : before) for (double x : b) seed = seed * 31 + Double.doubleToLongBits(x);
        Random r = new Random(seed);
        double[] dn = new double[DRAWS], db = new double[DRAWS];
        int kn = now.size(), kb = before.size();
        int[] pn = new int[kn], pb = new int[kb];
        for (int k = 0; k < DRAWS; k++) {
            for (int i = 0; i < kn; i++) pn[i] = r.nextInt(kn);
            for (int i = 0; i < kb; i++) pb[i] = r.nextInt(kb);
            dn[k] = statistic(pool(now, pn), kind, centre);
            db[k] = statistic(pool(before, pb), kind, centre);
        }
        // a month has only 3 to 5 weeks to resample: their spread alone is too narrow (about one month in five would show a
        // change that is not there), so each side's variance gets the small sample correction k / (k - 1) and the range
        // Student's t for the fewer weeks of the two, as a 90 % range should
        double vn = sq(Stats.sd(dn)) * kn / (kn - 1.0), vb = sq(Stats.sd(db)) * kb / (kb - 1.0);
        // the degrees of freedom: whole weeks with a value on the side with fewer, less one (an empty week or the last few
        // days of a month are no week of their own)
        int weeks = Math.min(wn, wb);
        double half = T90[Math.min(weeks - 1, T90.length - 1)] * Math.sqrt(vn + vb);
        double lo = diff - half, hi = diff + half;
        return new Compare(true, diff, lo, hi, weeks, sentence(measure, diff, lo, hi, lastName, l));
    }

    static List<double[]> withValues(List<double[]> blocks) {
        List<double[]> out = new ArrayList<double[]>();
        for (double[] b : blocks) if (b.length > 0) out.add(b);
        return out;
    }

    /**
     * Wake ups of every night in [{@code from}, {@code to}] counted by one rule ({@link #of}'s: a night without a level as
     * Detailed, a night by hand left out, as it has no detail level; issue #459).
     */
    static boolean oneRule(List<Day> days, LocalDate from, LocalDate to) {
        int col = column(WAKE_UPS), shortest = -1;
        for (Day d : days) {
            if (d.date.isBefore(from) || d.date.isAfter(to) || Double.isNaN(d.v[col]) || d.src[col] == HAND) continue;
            int k = WakeUps.shortestKeptMin(d.detail < 0 ? StageFilter.DETAILED : StageFilter.clampLevel(d.detail));
            if (shortest < 0) shortest = k;
            else if (k != shortest) return false;
        }
        return true;
    }

    /** Student's t for a two sided 90 % range by degrees of freedom (index 0 unused, 30 and more about the normal 1.645). */
    static final double[] T90 = {Double.NaN, 6.314, 2.920, 2.353, 2.132, 2.015, 1.943, 1.895, 1.860, 1.833, 1.812, 1.645};

    static double sq(double x) {
        return Double.isNaN(x) ? 0 : x * x;
    }

    /** What a difference is called: "you sleep", "longer", "shorter" and how it is said. */
    static final String[] SUBJECT = {"you sleep", "you fall asleep", "you wake up", "your bedtimes vary", "you have", "your sleep score is",
        "your readiness is", "your resting heart rate is", "your HRV is", "your temperature is", "you walk", "you burn",
        "your screen time is"};
    static final String[] UP = {"longer", "later", "later", "more", "more", "higher", "higher", "higher", "higher", "higher", "more", "more", "longer"};
    static final String[] DOWN = {"shorter", "earlier", "earlier", "less", "fewer", "lower", "lower", "lower", "lower", "lower", "fewer", "fewer", "shorter"};

    /** A difference without its sign: "12 min", "1h 5m", "0.8 wake ups a night", "3 points", "1,200 steps a day". */
    public static String amount(int measure, double v, Locale l) {
        double a = Math.abs(v);
        switch (measure) {
            case ASLEEP:
            case BEDTIME:
            case WAKE:
            case REGULARITY:
            case SCREEN:
                return Math.round(a) >= 60 ? hm(a) : Math.round(a) + " min";
            case WAKE_UPS:
                return String.format(l, "%.1f", a) + " wake ups a night";
            case SLEEP_SCORE:
            case READINESS:
                return Math.round(a) == 1 ? "1 point" : Math.round(a) + " points";
            case RHR:
                return Math.round(a) + " bpm";
            case HRV:
                return Math.round(a) + " ms";
            case TEMP:
                return String.format(l, "%.2f", a) + " °C";
            case STEPS:
                return String.format(l, "%,d", Math.round(a)) + " steps a day";
            default:
                return Math.round(a) + " kcal a day";
        }
    }

    /** Zero as the amount shows it ("0 min", "0.0 wake ups"): no direction. */
    static boolean none(int measure, double v, Locale l) {
        return amount(measure, v, l).equals(amount(measure, 0, l));
    }

    static String word(int measure, double v) {
        return v > 0 ? UP[measure] : DOWN[measure];
    }

    /**
     * "This month you sleep 12 min longer on average than in October (could be 4 min shorter to 28 min longer), so about the
     * same." or, when the range is on one side, "... 25 min longer on average than in October (10 min to 40 min longer)."
     */
    public static String sentence(int measure, double diff, double lo, double hi, String lastName, Locale l) {
        String avg = KIND[measure] == MEDIAN ? "on a typical day" : KIND[measure] == SPREAD ? "" : "on average";
        StringBuilder s = new StringBuilder("This month ").append(SUBJECT[measure]).append(' ');
        if (none(measure, diff, l)) s.append("about the same").append(avg.isEmpty() ? "" : " " + avg).append(" as in ");
        else s.append(amount(measure, diff, l)).append(' ').append(word(measure, diff)).append(avg.isEmpty() ? "" : " " + avg).append(" than in ");
        return s.append(lastName).append(' ').append(bracket(measure, lo, hi, l)).toString();
    }

    /**
     * The range in brackets with the sentence's end: "(10 min to 40 min longer)." when it is on one side, else "(could be 4 min
     * shorter to 28 min longer), so about the same." (also the steps of {@link TrendChange}).
     */
    static String bracket(int measure, double lo, double hi, Locale l) {
        StringBuilder s = new StringBuilder("(");
        boolean loNone = none(measure, lo, l), hiNone = none(measure, hi, l);
        if (lo > 0 && !loNone || hi < 0 && !hiNone) {
            double a = lo > 0 ? lo : hi, b = lo > 0 ? hi : lo;
            s.append(amount(measure, a, l)).append(" to ").append(amount(measure, b, l)).append(' ').append(word(measure, b)).append(").");
        } else {
            s.append("could be ").append(loNone ? "the same" : amount(measure, lo, l) + " " + word(measure, lo)).append(" to ")
                    .append(hiNone ? "the same" : amount(measure, hi, l) + " " + word(measure, hi)).append("), so about the same.");
        }
        return s.toString();
    }
}
