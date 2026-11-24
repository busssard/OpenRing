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
        public final String text;

        Step(LocalDate date, double before, double after, double lo, double hi, int asBig, String text) {
            this.date = date;
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
        double m = mean(s.x, a, b), num = 0, den = 0;
        for (int i = a; i < b; i++) {
            double d = s.x[i] - m;
            den += d * d;
            if (i > a) num += d * (s.x[i - 1] - m);
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

    /** The seed of a test: from the values, the measure and the part, so one history always gives the same answer. */
    static long seed(Series s, int measure, int a, int b) {
        long seed = 374;
        for (int i = a; i < b; i++) seed = seed * 31 + Double.doubleToLongBits(s.x[i]);
        return seed * 17 + measure * 7919L + a * 104729L + b;
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
        Test first = test(s, 0, n, seed(s, measure, 0, n));
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
        Test left = test(s, 0, first.split, seed(s, measure, 0, first.split));
        Test right = test(s, first.split, n, seed(s, measure, first.split, n));
        Test second = null;
        if (left.holds() && (!right.holds() || left.asBig < right.asBig || left.asBig == right.asBig && left.stat >= right.stat)) second = left;
        else if (right.holds()) second = right;
        if (second != null && MOST > 1) {
            boolean onLeft = second == left;
            int a = onLeft ? second.split : 0, b = onLeft ? n : second.split;
            Test again = test(s, a, b, seed(s, measure, a, b));
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
        // the levels between the steps, and each step with its range
        List<int[]> parts = new ArrayList<int[]>();
        int from = 0;
        for (int k : splits) {
            parts.add(new int[] {from, k});
            from = k;
        }
        parts.add(new int[] {from, n});
        for (int[] p : parts) out.levels.add(new Level(s.dates[p[0]], s.dates[p[1] - 1], mean(s.x, p[0], p[1])));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < splits.size(); i++) {
            int[] pa = parts.get(i), pb = parts.get(i + 1);
            double before = mean(s.x, pa[0], pa[1]), after = mean(s.x, pb[0], pb[1]), diff = after - before;
            double[] sa = se2(s, pa[0], pa[1]), sb = se2(s, pb[0], pb[1]);
            int df = (int) Math.min(sa[1], sb[1]) - 1;
            double half = t90(df) * Math.sqrt(sa[0] + sb[0]);
            LocalDate date = s.dates[pb[0]];
            String t = sentence(measure, date, diff, diff - half, diff + half, l);
            out.steps.add(new Step(date, before, after, diff - half, diff + half, asBig.get(i), t));
            if (text.length() > 0) text.append(' ');
            text.append(t);
        }
        out.text = text.toString();
        return out;
    }

    static double mean(double[] x, int a, int b) {
        double s = 0;
        for (int i = a; i < b; i++) s += x[i];
        return s / (b - a);
    }

    /**
     * The squared standard error of the mean of {@code [a, b)} with its weeks as groups (values of one week move together),
     * with the small sample correction g / (g - 1), and the number of weeks g: {se², g}.
     */
    static double[] se2(Series s, int a, int b) {
        double m = mean(s.x, a, b), sum = 0, g = 0, cur = 0;
        for (int i = a; i < b; i++) {
            if (i > a && s.week[i] != s.week[i - 1]) {
                sum += cur * cur;
                cur = 0;
            }
            if (i == a || s.week[i] != s.week[i - 1]) g++;
            cur += s.x[i] - m;
        }
        sum += cur * cur;
        int n = b - a;
        double v = g > 1 ? sum / ((double) n * n) * g / (g - 1) : 0;
        return new double[] {v, g};
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
     * "From about 12 June 2026 you sleep 25 min longer on average than before (15 min to 35 min longer)." A step too small to
     * show in the unit says "slightly".
     */
    public static String sentence(int measure, LocalDate date, double diff, double lo, double hi, Locale l) {
        StringBuilder s = new StringBuilder("From about ").append(date(date)).append(' ').append(Trends.SUBJECT[measure]).append(' ');
        s.append(Trends.none(measure, diff, l) ? "slightly" : Trends.amount(measure, diff, l)).append(' ').append(Trends.word(measure, diff));
        s.append(" on average than before ").append(Trends.bracket(measure, lo, hi, l));
        return s.toString();
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
            if (j - i >= LASTING || j == with.size() && j - i >= 3) {
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
                if (!z.getRules().getOffset(noon).equals(last.getRules().getOffset(noon))) out.add(new Event(ZONE, d.date, d.date, "Time zone " + z.getId()));
            }
            last = z;
        }
        return out;
    }

    /** "Watch" from "watch" (a source's name is never empty). */
    static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The events touching {@code from} to {@code to}, workouts counted ("3 workouts"): "Period started (3 Mar 2026), 2 workouts". */
    public static String list(List<Event> events, LocalDate from, LocalDate to) {
        StringBuilder s = new StringBuilder();
        int workouts = 0;
        for (Event e : events) {
            if (e.to.isBefore(from) || e.from.isAfter(to)) continue;
            if (e.kind == WORKOUT) {
                workouts++;
                continue;
            }
            if (s.length() > 0) s.append(", ");
            s.append(e.withDate());
        }
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

    /** Days around a step whose events are named with it. */
    public static final int AROUND_DAYS = 14;

    /** "Around then: Watch nights from here (10 Jun 2026)." for the events within two weeks of the steps; "" without any. */
    public static String around(Result r, List<Event> events) {
        StringBuilder s = new StringBuilder();
        for (Step st : r.steps) {
            String near = list(events, st.date.minusDays(AROUND_DAYS), st.date.plusDays(AROUND_DAYS));
            if (near.isEmpty()) continue;
            if (s.length() > 0) s.append(' ');
            s.append("Around ").append(shortDate(st.date)).append(": ").append(near).append('.');
        }
        return s.toString();
    }

    /**
     * Where {@code d} sits on the chart of {@code view}, in columns: its period's index plus 0.5 plus its place in the column,
     * as the small dots ({@code Trends.View.dayOffset}: from the period's first day with a value at -0.4 to its last at 0.4,
     * days outside them at the edge). NaN outside the chart.
     */
    public static double place(Trends.View view, LocalDate d) {
        int b = view.indexOf(d);
        if (b < 0) return Double.NaN;
        long a = Long.MAX_VALUE, z = Long.MIN_VALUE;
        for (int i = 0; i < view.dayDates.size(); i++) {
            if (view.dayBucket.get(i) != b) continue;
            long e = view.dayDates.get(i).toEpochDay();
            a = Math.min(a, e);
            z = Math.max(z, e);
        }
        if (a == Long.MAX_VALUE || z == a) return b + 0.5;
        double off = 0.4 * (2.0 * (d.toEpochDay() - a) - (z - a)) / (z - a);
        return b + 0.5 + Math.max(-0.4, Math.min(0.4, off));
    }
}
