package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Trends, phase 2 (issue #374): "Did something change?" and the events on the time axis.
 *
 * <p>A change is a step in a measure's daily values over the whole history (the same in Weeks, Months and Years). Binary
 * segmentation: the day where the values before and after differ the most (the largest {@code sqrt(k (n - k) / n) |mean
 * before - mean after|}, at least {@link #MIN_SIDE} values on each side), then a permutation test that shuffles whole
 * calendar weeks ({@link #SHUFFLES} times, so a week of illness or a weekday pattern moves as one piece). The step is shown
 * only when at most {@link #MAX_AS_BIG} shuffles give one as big (1 in 100 with the step itself counted). A second step
 * only when the same test finds one on one side and the first still holds between the second and the far end. Seeded
 * from the values: the same history always gives the same answer. Nothing is stored.
 *
 * <p>Events are what is known from stored data, never guessed: a lasting switch of the device a measure comes from, a
 * change of the stage detail level between stored nights, a week or more without values, a change of time zone, and what
 * {@code data/TrendDays.events} reads (left out times, logged periods, the watch's workouts, the first day phone use
 * counted as awake). It names what happened around a step, never "because".
 */
public final class TrendChange {
    private TrendChange() {}

    /** Shuffles of whole weeks per test. */
    public static final int SHUFFLES = 999;
    /** A step is shown when at most this many shuffles give one as big: (1 + 9) / (1 + 999) = 1 in 100. */
    public static final int MAX_AS_BIG = 9;
    /** Values on each side of a step at least (three weeks of nights). */
    public static final int MIN_SIDE = 21;
    /** Calendar weeks with a value the part tested needs at least (about two months). */
    public static final int MIN_WEEKS = 8;
    /** At most two steps. */
    public static final int MOST = 2;
    /** Days without a value that make a "no nights" mark: a week or more. */
    public static final int GAP_DAYS = 7;
    /** Values from one device in a row that make a switch of device lasting. */
    public static final int LASTING = 7;
    /** The most carry over from one day to the next the shuffles keep. */
    static final double MAX_CARRY = 0.9;

    public static final String NONE = "No clear change over this time.";
    public static final String TOO_FEW = "Too few weeks to look for a change yet.";
    public static final String NOT_SPREAD = "Regularity is a spread over many nights, not a value of each night, so no change is looked for here.";
    public static final String CAPTION = "Did something change?";

    // ------------------------------------------------------------------ the result

    /** One step: from {@code date} (the first day of the new level) the level moves from {@code before} to {@code after}. */
    public static final class Step {
        public final LocalDate date;
        /** The levels as drawn (clock times around the view's centre), the 90 % range of {@code after - before}. */
        public final double before, after, lo, hi;
        /** Shuffles that gave as big a step (0 to {@link #MAX_AS_BIG}). */
        public final int asBig;
        /** A clear step (at least one day to day spread): its day is named; else only the part of the month ("mid June"). */
        public final boolean clear;
        public final String text;

        public Step(LocalDate date, double before, double after, double lo, double hi, int asBig, boolean clear, String text) {
            this.date = date;
            this.clear = clear;
            this.before = before;
            this.after = after;
            this.lo = lo;
            this.hi = hi;
            this.asBig = asBig;
            this.text = text;
        }
    }

    /** A level line: the average of the values from {@code from} to {@code to} (both days with a value). */
    public static final class Level {
        public final LocalDate from, to;
        public final double value;

        Level(LocalDate from, LocalDate to, double value) {
            this.from = from;
            this.to = to;
            this.value = value;
        }
    }

    /** The answer for one measure: its steps (none, one or two) with their levels, and the sentence. */
    public static final class Result {
        public final List<Step> steps = new ArrayList<Step>();
        public final List<Level> levels = new ArrayList<Level>();
        /** False when too few weeks or the measure is a spread: nothing was tested. */
        public boolean looked;
        public String text = NONE;

        /** The step whose date lies from {@code from} to {@code to}, null for none. */
        public Step in(LocalDate from, LocalDate to) {
            for (Step s : steps) if (!s.date.isBefore(from) && !s.date.isAfter(to)) return s;
            return null;
        }
    }

    // ------------------------------------------------------------------ the series

    /** A measure's values by date, oldest first, clock times as drawn ({@code Trends.of}'s centre plus up to 12 hours). */
    static final class Series {
        final LocalDate[] dates;
        final double[] x;
        /** The calendar week (Monday) of each value, counted from the first. */
        final int[] week;

        Series(LocalDate[] dates, double[] x) {
            this.dates = dates;
            this.x = x;
            week = new int[x.length];
            if (x.length == 0) return;
            long mon0 = Trends.start(dates[0], Trends.WEEKS).toEpochDay();
            for (int i = 0; i < x.length; i++) week[i] = (int) ((Trends.start(dates[i], Trends.WEEKS).toEpochDay() - mon0) / 7);
        }
    }

    static Series series(List<Trends.Day> days, int measure, LocalDate today) {
        int col = Trends.column(measure), kind = Trends.KIND[measure];
        List<Trends.Day> with = new ArrayList<Trends.Day>();
        for (Trends.Day d : days) if (!Double.isNaN(d.v[col]) && Trends.finished(measure, d.date, today)) with.add(d);
        Collections.sort(with, new Comparator<Trends.Day>() {
            @Override
            public int compare(Trends.Day a, Trends.Day b) {
                return a.date.compareTo(b.date);
            }
        });
        LocalDate[] dates = new LocalDate[with.size()];
        double[] x = new double[with.size()];
        for (int i = 0; i < x.length; i++) {
            dates[i] = with.get(i).date;
            x[i] = with.get(i).v[col];
        }
        if (kind == Trends.CLOCK && x.length > 0) {
            // as the chart draws them (Trends.of: the clock average of the whole history, then up to 12 hours either way)
            double centre = ClockAverage.of(x.clone())[0];
            for (int i = 0; i < x.length; i++) x[i] = centre + Trends.around(x[i], centre);
        }
        return new Series(dates, x);
    }

    // ------------------------------------------------------------------ the test

    /** The best split of one part of a series and how many shuffles of its weeks gave as big a step. */
    static final class Test {
        boolean enough;
        /** The index of the first value after the step. */
        int split = -1;
        double stat;
        int asBig;
        /** How much of a day's distance from the mean carries over to the next (0 to {@link #MAX_CARRY}). */
        double carry;

        boolean holds() {
            return enough && split >= 0 && asBig <= MAX_AS_BIG;
        }
    }

    /** The largest step statistic in {@code y[0..n)} and its split, {stat, split}; split -1 when the part is too short. */
    static double[] best(double[] y, int n) {
        double total = 0;
        for (int i = 0; i < n; i++) total += y[i];
        double s = 0, bestStat = -1;
        int bestK = -1;
        for (int k = 1; k < n; k++) {
            s += y[k - 1];
            if (k < MIN_SIDE || n - k < MIN_SIDE) continue;
            double t = Math.sqrt((double) k * (n - k) / n) * Math.abs(s / k - (total - s) / (n - k));
            if (t > bestStat) {
                bestStat = t;
                bestK = k;
            }
        }
        return new double[] {bestStat, bestK};
    }

    /** Tests the values {@code [a, b)} of {@code s}, shuffles seeded with {@code seed}. */
    static Test test(Series s, int a, int b, long seed) {
        Test t = new Test();
        int n = b - a;
        // the weeks of the part: runs of values in one calendar week
        List<int[]> blocks = new ArrayList<int[]>();
        for (int i = a; i < b; i++) {
            if (i == a || s.week[i] != s.week[i - 1]) blocks.add(new int[] {i, i + 1});
            else blocks.get(blocks.size() - 1)[1] = i + 1;
        }
        if (blocks.size() < MIN_WEEKS || n < 2 * MIN_SIDE) return t;
        t.enough = true;
        double[] y = new double[n];
        System.arraycopy(s.x, a, y, 0, n);
        double[] obs = best(y, n);
        t.stat = obs[0];
        // a split exists: the part has at least 2 * MIN_SIDE values
        t.split = a + (int) obs[1];
        // what is shuffled: each day's distance from the mean with the part carried over from the day before taken out
        // (sleep debt, a cold: a day is like the one before it), put back after the shuffle. Without this a shuffle loses
        // the carry over at every week's edge, and steps "as big" became too rare (2 in 100 false alarms where 1 was meant)
        // The carry is estimated only between days that follow each other (not across a day without a value), from each
        // day's distance to the mean of the part. Distances to the two levels around the split found were tried (review of
        // #468): they take the slow part of the noise for a step, so the carry came out smaller and false alarms rose
        // (2.6 in 100 instead of 1.8 with a carry of 0.7, 150 made up histories of 12 measures)
        double m = mean(s.x, a, b), num = 0, den = 0;
        for (int i = a; i < b; i++) {
            double d = s.x[i] - m;
            den += d * d;
            if (i > a && s.dates[i - 1].plusDays(1).equals(s.dates[i])) num += d * (s.x[i - 1] - m);
        }
        t.carry = den > 0 ? Math.max(0, Math.min(MAX_CARRY, num / den)) : 0;
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = (s.x[a + i] - m) - (i > 0 ? t.carry * (s.x[a + i - 1] - m) : 0);
        // as big: the same within rounding (a sum in another order)
        double limit = t.stat * (1 - 1e-9);
        int nb = blocks.size();
        int[] order = new int[nb];
        for (int i = 0; i < nb; i++) order[i] = i;
        Random r = new Random(seed);
        for (int k = 0; k < SHUFFLES; k++) {
            for (int i = nb - 1; i > 0; i--) {
                int j = r.nextInt(i + 1), tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            int at = 0;
            double prev = 0;
            for (int o : order) {
                int[] bl = blocks.get(o);
                for (int i = bl[0]; i < bl[1]; i++) {
                    prev = (at == 0 ? 0 : t.carry * prev) + e[i - a];
                    y[at++] = prev;
                }
            }
            if (best(y, n)[0] >= limit) {
                t.asBig++;
                // already more than allowed: the answer is "no" whatever the other shuffles give
                if (t.asBig > MAX_AS_BIG) break;
            }
        }
        return t;
    }

    /**
     * The seed of a test: from the measure and which test it is (0 the whole history, 1 the left side, 2 the right, 3 the
     * first again), never from the values, so the shuffles are the same every day and one more day changes only the data
     * (review of #468: a seed from the values let an answer near the limit flip from one day to the next). Deterministic:
     * the same history always gives the same answer.
     */
    static long seed(int measure, int which) {
        return 374L * 7919 + measure * 104729L + which;
    }

    // ------------------------------------------------------------------ the answer

    /** Whether something changed in {@code measure} over the whole history, its steps and the sentence. */
    public static Result of(List<Trends.Day> days, int measure, LocalDate today, Locale l) {
        Result out = new Result();
        if (Trends.KIND[measure] == Trends.SPREAD) {
            out.text = NOT_SPREAD;
            return out;
        }
        Series s = series(days, measure, today);
        int n = s.x.length;
        Test first = test(s, 0, n, seed(measure, 0));
        if (!first.enough) {
            out.text = TOO_FEW;
            return out;
        }
        out.looked = true;
        if (!first.holds()) return out;
        List<Integer> splits = new ArrayList<Integer>(), asBig = new ArrayList<Integer>();
        splits.add(first.split);
        asBig.add(first.asBig);
        // a second step: the stronger of the two sides, and the first must still hold between it and the far end
        Test left = test(s, 0, first.split, seed(measure, 1));
        Test right = test(s, first.split, n, seed(measure, 2));
        Test second = null;
        if (left.holds() && (!right.holds() || left.asBig < right.asBig || left.asBig == right.asBig && left.stat >= right.stat)) second = left;
        else if (right.holds()) second = right;
        // at most MOST (two) steps: the second side is tested once, never a third
        if (second != null) {
            boolean onLeft = second == left;
            int a = onLeft ? second.split : 0, b = onLeft ? n : second.split;
            Test again = test(s, a, b, seed(measure, 3));
            if (again.holds()) {
                Test early = second.split < again.split ? second : again, late = early == second ? again : second;
                splits.clear();
                asBig.clear();
                splits.add(early.split);
                splits.add(late.split);
                asBig.add(early.asBig);
                asBig.add(late.asBig);
            }
        }
        steps(out, s, splits, asBig, measure, l);
        return out;
    }

    /**
     * The answer with steps at {@code dates} (each the first day of its new level) without the shuffles (as big: 0), worded
     * and drawn as {@link #of} does: a step whose range holds no change is left out and the others are worked out again
     * from the merged parts. For tests of the levels and sentences on steps placed by hand.
     */
    public static Result at(List<Trends.Day> days, int measure, LocalDate today, List<LocalDate> dates, Locale l) {
        Result out = new Result();
        Series s = series(days, measure, today);
        List<Integer> splits = new ArrayList<Integer>(), asBig = new ArrayList<Integer>();
        for (LocalDate d : dates) {
            int k = 0;
            while (k < s.x.length && s.dates[k].isBefore(d)) k++;
            if (k > 0 && k < s.x.length && !splits.contains(k)) {
                splits.add(k);
                asBig.add(0);
            }
        }
        Collections.sort(splits);
        out.looked = true;
        steps(out, s, splits, asBig, measure, l);
        return out;
    }

    /**
     * The steps at {@code splits} with their levels (the median for a measure shown as a typical day, as its dots) and
     * ranges, into {@code out}. A step whose range holds no change is no step to draw (review of #468: the shuffles and the
     * range disagree); it is dropped, its two parts merge, and the steps left are worked out again from the merged parts
     * (code review of f04a5c5: the kept step's sentence used only its neighbouring part while the drawn levels used the
     * merged ones), until every step left has a range without zero.
     */
    static void steps(Result out, Series s, List<Integer> splitsIn, List<Integer> asBigIn, int measure, Locale l) {
        int n = s.x.length;
        boolean median = Trends.KIND[measure] == Trends.MEDIAN;
        List<Integer> splits = new ArrayList<Integer>(splitsIn), asBig = new ArrayList<Integer>(asBigIn);
        List<Step> steps = new ArrayList<Step>();
        while (!splits.isEmpty()) {
            List<int[]> parts = new ArrayList<int[]>();
            int from = 0;
            for (int k : splits) {
                parts.add(new int[] {from, k});
                from = k;
            }
            parts.add(new int[] {from, n});
            steps.clear();
            // the step to drop: of those whose range holds zero, the weakest (its size the smallest part of its range)
            int drop = -1;
            double weakest = Double.MAX_VALUE;
            for (int i = 0; i < splits.size(); i++) {
                int[] pa = parts.get(i), pb = parts.get(i + 1);
                double before = level(s.x, pa[0], pa[1], median), after = level(s.x, pb[0], pb[1], median), diff = after - before;
                double half = half(s, pa, pb, median, seed(measure, 10 + i));
                if (diff - half <= 0 && diff + half >= 0) {
                    double weight = half > 0 ? Math.abs(diff) / half : 0;
                    if (weight < weakest) {
                        weakest = weight;
                        drop = i;
                    }
                    continue;
                }
                boolean clear = Math.abs(diff) >= spread(s, pa, pb);
                LocalDate date = s.dates[pb[0]];
                steps.add(new Step(date, before, after, diff - half, diff + half, asBig.get(i), clear, sentence(measure, date, clear, diff, diff - half, diff + half, l)));
            }
            if (drop < 0) break;
            splits.remove(drop);
            asBig.remove(drop);
            steps.clear();
        }
        if (steps.isEmpty()) return;
        // the levels between the steps kept: the same parts the steps' numbers come from
        int at = 0;
        for (int k : splits) {
            out.levels.add(new Level(s.dates[at], s.dates[k - 1], level(s.x, at, k, median)));
            at = k;
        }
        out.levels.add(new Level(s.dates[at], s.dates[n - 1], level(s.x, at, n, median)));
        StringBuilder text = new StringBuilder();
        for (Step st : steps) {
            out.steps.add(st);
            if (text.length() > 0) text.append(' ');
            text.append(st.text);
        }
        out.text = text.toString();
    }

    /** The level of {@code x[a, b)}: its mean, or its median for a measure shown as a typical day. */
    static double level(double[] x, int a, int b, boolean median) {
        if (!median) return mean(x, a, b);
        return Stats.median(java.util.Arrays.copyOfRange(x, a, b));
    }

    /** Draws of whole weeks for the range of a step's size. */
    public static final int DRAWS = 1000;
    /**
     * The range is widened by this much: drawing weeks again alone held the true size of made up steps in 112 of 128 ranges
     * (88 %; the day of the step is the one where it looks biggest, and days carry over into the next week); with it in 117
     * of 128 (91 %, {@code TrendChangeTest.knownStep}, steps of 0.75 to 1.5 spreads found near their date).
     */
    public static final double WIDER = 1.1;

    /**
     * Half the 90 % range of {@code level(after) - level(before)}: whole weeks of each side drawn again {@link #DRAWS} times,
     * the spread of the differences, Student's t for the fewer weeks, widened by {@link #WIDER}.
     */
    static double half(Series s, int[] pa, int[] pb, boolean median, long seed) {
        List<double[]> wa = weeks(s, pa[0], pa[1]), wb = weeks(s, pb[0], pb[1]);
        Random r = new Random(seed);
        double[] d = new double[DRAWS];
        for (int k = 0; k < DRAWS; k++) d[k] = drawn(wb, r, median) - drawn(wa, r, median);
        int df = Math.min(wa.size(), wb.size()) - 1;
        return t90(df) * Stats.sd(d) * WIDER;
    }

    /** The values of {@code [a, b)} by calendar week. */
    static List<double[]> weeks(Series s, int a, int b) {
        List<double[]> out = new ArrayList<double[]>();
        int start = a;
        for (int i = a + 1; i <= b; i++) {
            if (i == b || s.week[i] != s.week[i - 1]) {
                out.add(java.util.Arrays.copyOfRange(s.x, start, i));
                start = i;
            }
        }
        return out;
    }

    /** The level of as many weeks drawn at random (with repeats) as there are. */
    static double drawn(List<double[]> weeks, Random r, boolean median) {
        int n = 0;
        int[] pick = new int[weeks.size()];
        for (int i = 0; i < pick.length; i++) {
            pick[i] = r.nextInt(pick.length);
            n += weeks.get(pick[i]).length;
        }
        double[] v = new double[n];
        int at = 0;
        for (int p : pick) for (double x : weeks.get(p)) v[at++] = x;
        return level(v, 0, n, median);
    }

    /** The day to day spread around the two levels (pooled standard deviation): a step at least this big is clear. */
    static double spread(Series s, int[] pa, int[] pb) {
        double ma = mean(s.x, pa[0], pa[1]), mb = mean(s.x, pb[0], pb[1]), sum = 0;
        for (int i = pa[0]; i < pa[1]; i++) sum += (s.x[i] - ma) * (s.x[i] - ma);
        for (int i = pb[0]; i < pb[1]; i++) sum += (s.x[i] - mb) * (s.x[i] - mb);
        int n = pa[1] - pa[0] + pb[1] - pb[0];
        return n > 2 ? Math.sqrt(sum / (n - 2)) : 0;
    }

    static double mean(double[] x, int a, int b) {
        double s = 0;
        for (int i = a; i < b; i++) s += x[i];
        return s / (b - a);
    }

    /** Student's t for a two sided 90 % range: the table up to 10 degrees of freedom, then 1.645 + 1.6 / df (within 0.01). */
    public static double t90(int df) {
        if (df < 1) return Trends.T90[1];
        if (df <= 10) return Trends.T90[df];
        return 1.645 + 1.6 / df;
    }

    /** "12 June 2026". */
    public static String date(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
    }

    /** "3 Mar 2026". */
    static String shortDate(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
    }

    /**
     * "From about 12 June 2026 you sleep 25 min longer on average than before (15 min to 35 min longer)." A step that is not
     * clear names only the part of its month: "From around mid June 2026 ...". A measure shown as a typical day says "on a
     * typical day". A step too small to show in the unit says "slightly".
     */
    public static String sentence(int measure, LocalDate date, boolean clear, double diff, double lo, double hi, Locale l) {
        StringBuilder s = new StringBuilder(clear ? "From about " + date(date) : "From around " + roughDate(date)).append(' ')
                .append(Trends.SUBJECT[measure]).append(' ');
        s.append(Trends.none(measure, diff, l) ? "slightly" : Trends.amount(measure, diff, l)).append(' ').append(Trends.word(measure, diff));
        s.append(Trends.KIND[measure] == Trends.MEDIAN ? " on a typical day" : " on average").append(" than before ").append(Trends.bracket(measure, lo, hi, l));
        return s.toString();
    }

    /** "early June 2026" (days 1 to 10), "mid June 2026" (11 to 20), "late June 2026". */
    public static String roughDate(LocalDate d) {
        String part = d.getDayOfMonth() <= 10 ? "early" : d.getDayOfMonth() <= 20 ? "mid" : "late";
        return part + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
    }

    // ------------------------------------------------------------------ events

    public static final int DEVICE = 0, DETAIL = 1, PHONE_AWAKE = 2, ZONE = 3, LEFT_OUT = 4, PERIOD = 5, WORKOUT = 6, NO_DATA = 7;

    /** Something that happened from {@code from} to {@code to} (one day for most), with its short text. */
    public static final class Event {
        public final int kind;
        public final LocalDate from, to;
        public final String text;

        public Event(int kind, LocalDate from, LocalDate to, String text) {
            this.kind = kind;
            this.from = from;
            this.to = to.isBefore(from) ? from : to;
            this.text = text;
        }

        /** "Time left out (3 to 5 Mar 2026)", "Period started (12 Mar 2026)". */
        public String withDate() {
            return text + " (" + span(from, to) + ")";
        }
    }

    /** "3 Mar 2026", "3 to 9 Mar 2026", "28 Feb to 2 Mar 2026", "30 Dec 2025 to 2 Jan 2026". */
    static String span(LocalDate a, LocalDate b) {
        if (a.equals(b)) return shortDate(a);
        String mb = b.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        if (a.getYear() != b.getYear()) return shortDate(a) + " to " + shortDate(b);
        if (a.getMonth() != b.getMonth()) return a.getDayOfMonth() + " " + a.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " to " + shortDate(b);
        return a.getDayOfMonth() + " to " + b.getDayOfMonth() + " " + mb + " " + b.getYear();
    }

    /** Whether an event of {@code kind} is marked on {@code measure}'s chart. */
    public static boolean applies(int kind, int measure) {
        boolean sleep = measure <= Trends.SLEEP_SCORE;
        switch (kind) {
            case DETAIL:
            case PHONE_AWAKE:
                // they change how nights are worked out
                return sleep;
            case PERIOD:
                // the cycle moves these (the warmer nights after ovulation, a higher heart rate)
                return measure == Trends.TEMP || measure == Trends.RHR || measure == Trends.HRV || measure == Trends.READINESS;
            case WORKOUT:
                return measure == Trends.STEPS || measure == Trends.KCAL || measure == Trends.READINESS || measure == Trends.RHR || measure == Trends.HRV;
            default:
                return true;
        }
    }

    /**
     * The events of {@code measure}'s chart, oldest first: what its own values show (a lasting switch of device, a change of
     * the stage detail level, a week or more without values, a change of time zone) and those of {@code known} that apply.
     */
    public static List<Event> events(List<Trends.Day> days, int measure, LocalDate today, List<Event> known) {
        List<Event> out = new ArrayList<Event>();
        int col = Trends.column(measure);
        boolean night = Trends.night(measure);
        String unit = night ? "nights" : "days";
        List<Trends.Day> with = new ArrayList<Trends.Day>();
        for (Trends.Day d : days) if (!Double.isNaN(d.v[col]) && Trends.finished(measure, d.date, today)) with.add(d);
        // a lasting switch of device: LASTING values in a row from another device than the last lasting run
        int lastSource = -1;
        for (int i = 0; i < with.size(); ) {
            int src = with.get(i).src[col], j = i;
            while (j < with.size() && with.get(j).src[col] == src) j++;
            if (j - i >= LASTING) {
                if (lastSource >= 0 && src != lastSource && src > 0) {
                    String name = Trends.SOURCE_NAMES[Math.min(Trends.SOURCE_NAMES.length - 1, src)];
                    String what = src == Trends.HAND ? "Nights entered by hand" : capital(name) + " " + unit;
                    out.add(new Event(DEVICE, with.get(i).date, with.get(i).date, what + " from here"));
                }
                lastSource = src;
            }
            i = j;
        }
        // a change of the stage detail level between stored nights (a night by hand has none)
        if (applies(DETAIL, measure)) {
            int level = -1;
            for (Trends.Day d : with) {
                if (d.detail < 0 || d.src[col] == Trends.HAND) continue;
                int lv = StageFilter.clampLevel(d.detail);
                if (level >= 0 && lv != level) out.add(new Event(DETAIL, d.date, d.date, "Stage detail " + StageFilter.NAMES[lv] + " from here"));
                level = lv;
            }
        }
        // a week or more without values, between the first and the last
        for (int i = 1; i < with.size(); i++) {
            LocalDate a = with.get(i - 1).date, b = with.get(i).date;
            if (ChronoUnit.DAYS.between(a, b) - 1 >= GAP_DAYS) out.add(new Event(NO_DATA, a.plusDays(1), b.minusDays(1), "No " + unit));
        }
        out.addAll(zones(days));
        if (known != null) for (Event e : known) if (applies(e.kind, measure)) out.add(e);
        Collections.sort(out, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                int c = a.from.compareTo(b.from);
                return c != 0 ? c : Integer.compare(a.kind, b.kind);
            }
        });
        return out;
    }

    /** A change of time zone between two days that moved the clock (a trip; not a change of summer time, same zone). */
    static List<Event> zones(List<Trends.Day> days) {
        List<Event> out = new ArrayList<Event>();
        ZoneId last = null;
        for (Trends.Day d : days) {
            ZoneId z = ClockChange.zone(d.zone);
            if (z == null) continue;
            if (last != null && !z.equals(last)) {
                java.time.Instant noon = d.date.atTime(12, 0).atZone(z).toInstant();
                if (!z.getRules().getOffset(noon).equals(last.getRules().getOffset(noon))) out.add(new Event(ZONE, d.date, d.date, "Clock moved to " + place(z) + " time"));
            }
            last = z;
        }
        return out;
    }

    /** The place of a zone for people: "New York" from America/New_York, the id itself for one without a place. */
    static String place(ZoneId z) {
        String id = z.getId();
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1).replace('_', ' ');
    }

    /** "Watch" from "watch" (a source's name is never empty). */
    static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * The events touching {@code from} to {@code to}: "Period started (3 Mar 2026), 2 workouts". Workouts are counted ("3
     * workouts"), and so are periods once more than one started in the span ("13 periods started" in a year; code review of
     * f04a5c5: a year's readout listed every one).
     */
    public static String list(List<Event> events, LocalDate from, LocalDate to) {
        StringBuilder s = new StringBuilder();
        int workouts = 0, periods = 0;
        Event period = null;
        for (Event e : events) {
            if (e.to.isBefore(from) || e.from.isAfter(to)) continue;
            if (e.kind == WORKOUT) {
                workouts++;
                continue;
            }
            if (e.kind == PERIOD) {
                periods++;
                period = e;
                continue;
            }
            if (s.length() > 0) s.append(", ");
            s.append(e.withDate());
        }
        if (periods == 1) s.append(s.length() > 0 ? ", " : "").append(period.withDate());
        if (periods > 1) s.append(s.length() > 0 ? ", " : "").append(periods).append(" periods started");
        if (workouts > 0) s.append(s.length() > 0 ? ", " : "").append(workouts == 1 ? "1 workout" : workouts + " workouts");
        return s.toString();
    }

    /**
     * The touch readout of period {@code i} with what phase 2 adds: the step in it, if any, and its marks. "March 2026: 7h 5m
     * asleep on average, ..., 29 nights\nFrom about 12 March 2026 you sleep 25 min longer ...\nMarks: Period started (3 Mar 2026)".
     */
    public static String readout(Trends.View view, int i, Result change, List<Event> events, Locale l) {
        StringBuilder s = new StringBuilder(Trends.readout(view, i, l));
        Trends.Bucket b = view.buckets.get(i);
        Step st = change == null ? null : change.in(b.start, b.end);
        if (st != null) s.append('\n').append(st.text);
        String marks = events == null ? "" : list(events, b.start, b.end);
        if (!marks.isEmpty()) s.append("\nMarks: ").append(marks);
        return s.toString();
    }

    /** The line under the chart on what phase 2 draws, "" when it draws nothing. */
    public static String chartNote(Result change, List<Event> events) {
        boolean steps = change != null && !change.steps.isEmpty(), marks = events != null && !events.isEmpty();
        if (!steps && !marks) return "";
        StringBuilder s = new StringBuilder();
        if (steps) s.append("Dashed line: where your values stepped. Lines: your average before and after. ");
        if (marks) s.append("Marks under the chart: events such as a new device, a trip or a time left out. ");
        return s.append("Touch a period to read them.").toString();
    }

    /** Days around a clear step whose events are named with it; around a step that is not clear, six weeks (its day is rough). */
    public static final int AROUND_DAYS = 14, AROUND_ROUGH_DAYS = 42;

    /** "Around then: Watch nights from here (10 Jun 2026)." for the events within two weeks of the steps; "" without any. */
    public static String around(Result r, List<Event> events) {
        StringBuilder s = new StringBuilder();
        for (Step st : r.steps) {
            int w = st.clear ? AROUND_DAYS : AROUND_ROUGH_DAYS;
            // workouts are many and everywhere: they stay under the chart, not here
            List<Event> some = new ArrayList<Event>();
            for (Event e : events) if (e.kind != WORKOUT) some.add(e);
            String near = list(some, st.date.minusDays(w), st.date.plusDays(w));
            if (near.isEmpty()) continue;
            if (s.length() > 0) s.append(' ');
            s.append("Around ").append(st.clear ? shortDate(st.date) : roughDate(st.date)).append(": ").append(near).append('.');
        }
        return s.toString();
    }

    /**
     * Where {@code d} sits on the chart of {@code view}, in columns: its period's index plus 0.5 plus its place in the column,
     * as the small dots ({@code Trends.View.dayOffset}: from the period's first day with a value at -0.4 to its last at 0.4,
     * days outside them at the edge). NaN outside the chart. For many dates build {@link Columns} once.
     */
    public static double place(Trends.View view, LocalDate d) {
        return new Columns(view).place(d);
    }

    /** Each period's first and last day with a value, read once from a view, so a date's place is a search, not a walk. */
    public static final class Columns {
        final long[] start, end, first, last;

        public Columns(Trends.View view) {
            int nb = view.buckets.size();
            start = new long[nb];
            end = new long[nb];
            first = new long[nb];
            last = new long[nb];
            for (int i = 0; i < nb; i++) {
                start[i] = view.buckets.get(i).start.toEpochDay();
                end[i] = view.buckets.get(i).end.toEpochDay();
                first[i] = Long.MAX_VALUE;
                last[i] = Long.MIN_VALUE;
            }
            for (int i = 0; i < view.dayDates.size(); i++) {
                int b = view.dayBucket.get(i);
                long e = view.dayDates.get(i).toEpochDay();
                first[b] = Math.min(first[b], e);
                last[b] = Math.max(last[b], e);
            }
        }

        public double place(LocalDate d) {
            long e = d.toEpochDay();
            int lo = 0, hi = start.length - 1, b = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (e < start[mid]) hi = mid - 1;
                else if (e > end[mid]) lo = mid + 1;
                else {
                    b = mid;
                    break;
                }
            }
            if (b < 0) return Double.NaN;
            long a = first[b], z = last[b];
            if (a == Long.MAX_VALUE || z == a) return b + 0.5;
            double off = 0.4 * (2.0 * (e - a) - (z - a)) / (z - a);
            return b + 0.5 + Math.max(-0.4, Math.min(0.4, off));
        }
    }

    /**
     * What the chart draws for phase 2, in columns (worked out off the main thread once per chart, so drawing and touching
     * only scale them to pixels; review of #468): each level from, to and value, each step, each event from and to (NaN
     * outside the chart) and whether it is one day.
     */
    public static final class Drawn {
        public final double[] levelFrom, levelTo, levelValue, steps, eventFrom, eventTo;
        public final boolean[] eventDay;

        Drawn(int levels, int stepCount, int events) {
            levelFrom = new double[levels];
            levelTo = new double[levels];
            levelValue = new double[levels];
            steps = new double[stepCount];
            eventFrom = new double[events];
            eventTo = new double[events];
            eventDay = new boolean[events];
        }
    }

    /** {@link Drawn} of {@code change} and {@code events} on {@code view}. */
    public static Drawn drawn(Trends.View view, Result change, List<Event> events) {
        Columns c = new Columns(view);
        int nl = change == null ? 0 : change.levels.size(), ns = change == null ? 0 : change.steps.size(), ne = events == null ? 0 : events.size();
        Drawn out = new Drawn(nl, ns, ne);
        for (int i = 0; i < nl; i++) {
            Level lv = change.levels.get(i);
            out.levelFrom[i] = c.place(lv.from);
            out.levelTo[i] = c.place(lv.to);
            out.levelValue[i] = lv.value;
        }
        for (int i = 0; i < ns; i++) out.steps[i] = c.place(change.steps.get(i).date);
        for (int i = 0; i < ne; i++) {
            Event e = events.get(i);
            out.eventFrom[i] = c.place(e.from);
            out.eventTo[i] = c.place(e.to);
            out.eventDay[i] = e.from.equals(e.to);
        }
        return out;
    }
}
