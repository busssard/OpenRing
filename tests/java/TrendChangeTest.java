import app.openring.core.analysis.StageFilter;
import app.openring.core.analysis.TrendChange;
import app.openring.core.analysis.Trends;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Trends, phase 2 (issue #374): "Did something change?" on made up histories (a known step is found within a week of its
 * date, no change gives no step in about 1 in 100 measures, the same answer on every opening, two steps, too few weeks) and
 * the events on the time axis. Run by tests/run_tests.sh on a line of its own.
 */
public class TrendChangeTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) System.out.println("  got: " + got + " want: " + want);
        check(name, ok);
    }

    static final Locale L = Locale.US;
    static final LocalDate TODAY = LocalDate.parse("2026-11-24");
    static final List<String> TEXTS = new ArrayList<String>();

    public static void main(String[] args) {
        knownStep();
        typicalDay();
        falseAlarms();
        sameEveryTime();
        twoSteps();
        tooFew();
        clockStep();
        texts();
        events();
        place();
        readouts();
        lifeMarks();
        phase3Measures();
        speed();
        plain();
        System.out.println("trend changes: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Typical level and day to day spread of each measure (made up, about a person's). */
    static final double[] LEVEL = {420, 1410, 420, 0, 3, 78, 75, 55, 45, 0, 8000, 400, 200};
    static final double[] SD = {40, 35, 35, 0, 1.5, 7, 9, 2.5, 9, 0.15, 3000, 150, 70};

    /**
     * A made up history of {@code days} days up to {@code TODAY} of every measure without a change: a weekday pattern, day to
     * day noise that carries over to the next day (0.3), a week of illness, a cycle in the temperature, gaps of a few days,
     * and from {@code stepFrom} (null for none) {@code step} added to {@code measure}.
     */
    static List<Trends.Day> history(long seed, int days, int measure, LocalDate stepFrom, double step) {
        return history(seed, days, measure, stepFrom, step, true);
    }

    /** As above, {@code illness} false for none. */
    static List<Trends.Day> history(long seed, int days, int measure, LocalDate stepFrom, double step, boolean illness) {
        return history(seed, days, measure, stepFrom, step, illness, 0.3);
    }

    /** As above with {@code carry} of each day's noise carried into the next (0.3 above), the day to day spread kept. */
    static List<Trends.Day> history(long seed, int days, int measure, LocalDate stepFrom, double step, boolean illness, double carryOver) {
        Random r = new Random(seed);
        double keep = Math.sqrt(1 - carryOver * carryOver) / Math.sqrt(1 - 0.09);
        List<Trends.Day> out = new ArrayList<Trends.Day>();
        double[] carry = new double[Trends.COUNT];
        LocalDate first = TODAY.minusDays(days);
        int ill = 30 + r.nextInt(Math.max(1, days - 60));
        for (int i = 0; i < days; i++) {
            LocalDate d = first.plusDays(i);
            Trends.Day x = new Trends.Day(d);
            x.detail = StageFilter.BALANCED;
            out.add(x);
            // the ring off for a day now and then
            if (r.nextDouble() < 0.06) continue;
            boolean weekend = d.getDayOfWeek().getValue() >= 6, sick = illness && i >= ill && i < ill + 7;
            for (int m = 0; m < Trends.COUNT; m++) {
                if (m == Trends.REGULARITY) continue;
                carry[m] = carryOver * carry[m] + r.nextGaussian() * SD[m] * keep;
                double v = LEVEL[m] + carry[m];
                if (weekend) v += m == Trends.ASLEEP ? 35 : m == Trends.BEDTIME ? 40 : m == Trends.WAKE ? 60 : m == Trends.STEPS ? -1500 : m == Trends.SCREEN ? 60 : 0;
                if (sick) v += m == Trends.TEMP ? 0.6 : m == Trends.RHR ? 5 : m == Trends.HRV ? -12 : m == Trends.STEPS ? -4000 : m == Trends.READINESS ? -20 : 0;
                if (m == Trends.TEMP) v += 0.2 * Math.sin(2 * Math.PI * i / 28.0);
                if (stepFrom != null && m == measure && !d.isBefore(stepFrom)) v += step;
                if (m == Trends.BEDTIME || m == Trends.WAKE) v = ((v % 1440) + 1440) % 1440;
                if (m == Trends.WAKE_UPS) v = Math.max(0, Math.round(v));
                if (m == Trends.STEPS || m == Trends.KCAL || m == Trends.SCREEN) v = Math.max(0, v);
                x.set(m, v, Trends.RING);
            }
        }
        return out;
    }

    /** The known step of each measure in the tests: {@code size} day to day spreads, for the temperature twice that (its cycle). */
    static double step(int m, double size) {
        return (m == Trends.TEMP ? 2 : 1) * size * SD[m];
    }

    /**
     * Known steps in every measure at four dates: {found, within {@code days} of the date, true size in the range of those,
     * the farthest off, exactly one step and that one within {@code days}, named as clear}.
     */
    static int[] steps(double size, int days) {
        int found = 0, near = 0, inRange = 0, worst = 0, single = 0, clear = 0;
        for (int m = 0; m < Trends.COUNT; m++) {
            if (m == Trends.REGULARITY) continue;
            for (int k = 0; k < 4; k++) {
                LocalDate at = TODAY.minusDays(100 + 50 * k);
                double st = step(m, size);
                TrendChange.Result r = TrendChange.of(history(1000 + 10 * m + k, 365, m, at, st, false), m, TODAY, L);
                if (r.steps.isEmpty()) continue;
                found++;
                long off = Long.MAX_VALUE;
                for (TrendChange.Step s : r.steps) off = Math.min(off, Math.abs(java.time.temporal.ChronoUnit.DAYS.between(at, s.date)));
                worst = (int) Math.max(worst, off);
                TrendChange.Step s = r.in(at.minusDays(days), at.plusDays(days));
                if (s == null) continue;
                near++;
                if (r.steps.size() == 1) single++;
                if (s.clear) clear++;
                if (s.lo <= st && st <= s.hi) inRange++;
            }
        }
        return new int[] {found, near, inRange, worst, single, clear};
    }

    /** A known step is found within a week of its date, in each measure, alone, and its size is in the range. */
    static void knownStep() {
        int tries = 4 * (Trends.COUNT - 1);
        // a clear step (one and a half spreads): within a week of its date, the only step, named by its day
        int[] c = steps(1.5, 7);
        System.out.println("    known step of 1.5 spreads: found in " + c[0] + " of " + tries + ", within a week of its date in " + c[1]
                + " (the farthest " + c[3] + " days off), alone in " + c[4] + ", named by its day in " + c[5] + ", true size in the range in " + c[2] + " of " + c[1]);
        check("a step of 1.5 spreads is found in every measure", c[0] == tries);
        check("found within a week of its date (at least 9 in 10)", c[1] * 10 >= tries * 9);
        check("one step stays one, in its place (at least 9 in 10)", c[4] * 10 >= tries * 9);
        check("a step of 1.5 spreads is named by its day (at least 9 in 10 of those)", c[5] * 10 >= c[1] * 9);
        // a smaller step (one spread): still found, its date rougher
        int[] s1 = steps(1.0, 14);
        System.out.println("    known step of 1 spread: found in " + s1[0] + " of " + tries + ", within two weeks of its date in " + s1[1]
                + " (the farthest " + s1[3] + " days off), alone in " + s1[4] + ", true size in the range in " + s1[2] + " of " + s1[1]);
        check("a step of one spread is found in every measure", s1[0] == tries);
        check("found within two weeks of its date (at least 8 in 10)", s1[1] * 10 >= tries * 8);
        // steps under one spread (review of #468): found less often, and their date only roughly (named by the part of the month)
        int[] s75 = steps(0.75, 42), s50 = steps(0.5, 42);
        System.out.println("    known step of 0.75 spreads: found in " + s75[0] + " of " + tries + ", within six weeks in " + s75[1] + " (the farthest "
                + s75[3] + " days off), named by its day in " + s75[5] + "; 0.5 spreads: found in " + s50[0] + ", within six weeks in " + s50[1]
                + ", named by its day in " + s50[5]);
        check("0.75 spreads: found in at least 3 in 4", s75[0] * 4 >= tries * 3);
        check("0.75 spreads: within six weeks in at least 3 in 4 of those found", s75[1] * 4 >= s75[0] * 3);
        check("0.5 spreads: a found step is mostly named roughly (its day at most in 1 in 4)", s50[5] * 4 <= Math.max(1, s50[1]));
        // the range: about 9 in 10 hold the true size, for clear and small steps together
        int held = c[2] + s1[2] + s75[2], of = c[1] + s1[1] + s75[1];
        System.out.printf(L, "    the range holds the true size in %d of %d steps found near their date (%.0f %%)%n", held, of, 100.0 * held / of);
        check("the range holds the true size in at least 85 in 100", held * 100 >= of * 85);
        // the date in the sentence, and the levels drawn on both sides
        LocalDate at = LocalDate.parse("2026-06-15");
        TrendChange.Result r = TrendChange.of(history(7, 365, Trends.ASLEEP, at, 50), Trends.ASLEEP, TODAY, L);
        check("one step", r.steps.size() == 1 && r.looked);
        eq("two levels", r.levels.size(), 2);
        TrendChange.Step s = r.steps.get(0);
        check("the step goes up by about 50 min", s.after - s.before > 35 && s.after - s.before < 65);
        check("its range holds the step", s.lo < s.after - s.before && s.after - s.before < s.hi);
        check("a clear step: the sentence starts with its day: " + r.text, s.clear && r.text.startsWith("From about " + TrendChange.date(s.date) + " you sleep "));
        check("no more than 1 in 100 shuffles as big", s.asBig <= TrendChange.MAX_AS_BIG);
        check("levels meet at the step", r.levels.get(1).from.equals(s.date) && r.levels.get(0).to.isBefore(s.date));
        // a small step: the part of the month
        TrendChange.Result small = null;
        for (int k = 0; k < 20 && (small == null || small.steps.isEmpty() || small.steps.get(0).clear); k++) {
            small = TrendChange.of(history(300 + k, 365, Trends.ASLEEP, at, 28), Trends.ASLEEP, TODAY, L);
        }
        check("a small step: the part of the month: " + small.text, !small.steps.isEmpty() && !small.steps.get(0).clear
                && small.text.startsWith("From around " + TrendChange.roughDate(small.steps.get(0).date) + " you sleep "));
    }

    /** Screen time is shown as a typical day (the median), its levels and sentence too (review of #468). */
    static void typicalDay() {
        LocalDate at = TODAY.minusDays(150);
        List<Trends.Day> h = history(91, 365, Trends.SCREEN, at, 90, false);
        // a few very long days before the step pull a mean up, not the median
        for (Trends.Day d : h) if (!Double.isNaN(d.v[Trends.SCREEN]) && d.date.isBefore(at) && d.date.getDayOfMonth() == 5) d.v[Trends.SCREEN] = 900;
        TrendChange.Result r = TrendChange.of(h, Trends.SCREEN, TODAY, L);
        check("screen time step found: " + r.text, r.steps.size() >= 1 && r.text.contains("on a typical day") && !r.text.contains("on average"));
        TrendChange.Level first = r.levels.get(0);
        List<Double> v = new ArrayList<Double>();
        for (Trends.Day d : h) if (!Double.isNaN(d.v[Trends.SCREEN]) && !d.date.isBefore(first.from) && !d.date.isAfter(first.to) && d.date.isBefore(TODAY)) v.add(d.v[Trends.SCREEN]);
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        check("the level before is the median of its days", Math.abs(first.value - app.openring.core.analysis.Stats.median(a)) < 1e-9);
        check("its range holds the true 90 min", r.steps.get(0).lo <= 90 && 90 <= r.steps.get(0).hi);
    }

    /** Made up histories without any change: across all 12 measures about 1 in 100 shows a step that is not there. */
    static void falseAlarms() {
        // decision 1 of #468 pinned: the same limit with no carry over and with a strong one
        for (double carry : new double[] {0, 0.7}) {
            int t = 0, a = 0;
            for (int h = 0; h < 60; h++) {
                List<Trends.Day> days = history(70000 + h, 365, -1, null, 0, true, carry);
                for (int m = 0; m < Trends.COUNT; m++) {
                    if (m == Trends.REGULARITY) continue;
                    t++;
                    if (!TrendChange.of(days, m, TODAY, L).steps.isEmpty()) a++;
                }
            }
            System.out.printf(L, "    no change, carry %.1f: %d false alarms in %d measures (%.1f %%)%n", carry, a, t, 100.0 * a / t);
            check("carry " + carry + ": false alarms at most 2 in 100 measures", a * 100 <= 2 * t);
        }
        int tests = 0, alarms = 0, anyAlarm = 0, histories = 150, quiet = 0;
        int[] perMeasure = new int[Trends.COUNT];
        for (int h = 0; h < histories; h++) {
            List<Trends.Day> days = history(50000 + h, 365, -1, null, 0);
            boolean any = false;
            for (int m = 0; m < Trends.COUNT; m++) {
                if (m == Trends.REGULARITY) continue;
                TrendChange.Result r = TrendChange.of(days, m, TODAY, L);
                tests++;
                check("looked at " + Trends.NAMES[m], r.looked);
                if (r.steps.isEmpty() && r.text.equals(TrendChange.NONE) && r.levels.isEmpty()) quiet++;
                if (!r.steps.isEmpty()) {
                    alarms++;
                    perMeasure[m]++;
                    any = true;
                }
            }
            if (any) anyAlarm++;
        }
        StringBuilder sb = new StringBuilder();
        for (int m = 0; m < Trends.COUNT; m++) if (perMeasure[m] > 0) sb.append(' ').append(Trends.NAMES[m]).append(' ').append(perMeasure[m]);
        System.out.printf(L, "    no change: %d false alarms in %d measures (%.1f %%), %d of %d histories with one in any measure;%s%n",
                alarms, tests, 100.0 * alarms / tests, anyAlarm, histories, sb);
        // nominal 1 in 100; up to 2 in 100 allowed for the chance of the made up histories
        check("false alarms at most 2 in 100 measures", alarms * 100 <= 2 * tests);
        eq("every measure without a step says so, without level lines", quiet, tests - alarms);
    }

    /** Seeded, not from the values: every opening gives the same answer, the shuffles as big included. */
    static void sameEveryTime() {
        List<Trends.Day> h = history(11, 400, Trends.STEPS, TODAY.minusDays(150), 2000);
        TrendChange.Result a = TrendChange.of(h, Trends.STEPS, TODAY, L), b = TrendChange.of(h, Trends.STEPS, TODAY, L);
        eq("same history, same sentence", a.text, b.text);
        check("a step to compare", !a.steps.isEmpty());
        for (int i = 0; i < a.steps.size(); i++) {
            eq("same shuffles as big", a.steps.get(i).asBig, b.steps.get(i).asBig);
            eq("same range", a.steps.get(i).lo, b.steps.get(i).lo);
        }
        // a step near the limit: the same count and text on every opening
        List<Trends.Day> w = history(12, 365, Trends.HRV, TODAY.minusDays(100), 4);
        TrendChange.Result r1 = TrendChange.of(w, Trends.HRV, TODAY, L), r2 = TrendChange.of(w, Trends.HRV, TODAY, L);
        eq("same text near the limit", r1.text, r2.text);
        eq("same steps", r1.steps.size(), r2.steps.size());
    }

    /** Two clear steps (up, then down again) are both found; a second needs the test on its own side. */
    static void twoSteps() {
        LocalDate up = TODAY.minusDays(300), down = TODAY.minusDays(120);
        List<Trends.Day> h = history(21, 420, -1, null, 0, false);
        for (Trends.Day d : h) {
            if (Double.isNaN(d.v[Trends.RHR])) continue;
            if (!d.date.isBefore(up) && d.date.isBefore(down)) d.v[Trends.RHR] += 4;
        }
        TrendChange.Result r = TrendChange.of(h, Trends.RHR, TODAY, L);
        System.out.println("    two steps (" + up + ", " + down + "): " + r.text);
        eq("two steps", r.steps.size(), 2);
        eq("three levels", r.levels.size(), 3);
        check("the first near its date", r.in(up.minusDays(7), up.plusDays(7)) != null);
        check("the second near its date", r.in(down.minusDays(7), down.plusDays(7)) != null);
        check("up then down", r.steps.size() == 2 && r.steps.get(0).after > r.steps.get(0).before && r.steps.get(1).after < r.steps.get(1).before);
        check("two sentences: " + r.text, r.text.contains("higher") && r.text.contains("lower") && r.text.indexOf("From about") != r.text.lastIndexOf("From about"));
        // a staircase: the bigger later step is found first, the smaller earlier one on its left side
        List<Trends.Day> st = history(23, 420, -1, null, 0, false);
        for (Trends.Day d : st) {
            if (Double.isNaN(d.v[Trends.RHR])) continue;
            if (!d.date.isBefore(up)) d.v[Trends.RHR] += 4;
            if (!d.date.isBefore(down)) d.v[Trends.RHR] += 8;
        }
        TrendChange.Result sr = TrendChange.of(st, Trends.RHR, TODAY, L);
        check("staircase: two steps up, oldest first: " + sr.text, sr.steps.size() == 2 && sr.steps.get(0).date.isBefore(sr.steps.get(1).date)
                && sr.in(up.minusDays(7), up.plusDays(7)) != null && sr.in(down.minusDays(7), down.plusDays(7)) != null);
        check("staircase: each step goes up", sr.steps.size() == 2 && sr.steps.get(0).after > sr.steps.get(0).before && sr.steps.get(1).after > sr.steps.get(1).before);
        // Student's t: the table, then close to the normal
        eq("t90 with 1 degree of freedom", TrendChange.t90(1), 6.314);
        eq("t90 without a degree of freedom as with one", TrendChange.t90(0), 6.314);
        check("t90 with 20 within 0.01 of 1.725", Math.abs(TrendChange.t90(20) - 1.725) < 0.01);
        // a single step never gets a second from noise on its sides
        TrendChange.Result one = TrendChange.of(history(22, 420, Trends.RHR, up, 4), Trends.RHR, TODAY, L);
        eq("one step stays one", one.steps.size(), 1);
        check("at most two steps", r.steps.size() <= TrendChange.MOST && sr.steps.size() <= TrendChange.MOST && one.steps.size() <= TrendChange.MOST);
        mergedParts();
    }

    /**
     * Two steps where the first one's range holds zero (code review of f04a5c5): it is dropped, and the step kept is worked
     * out again from the merged parts, so its numbers and sentence match the levels drawn. Before the fix its "before" was
     * only the part between the two steps.
     */
    static void mergedParts() {
        LocalDate a = TODAY.minusDays(280), b = TODAY.minusDays(140);
        List<Trends.Day> h = history(24, 420, -1, null, 0, false);
        for (Trends.Day d : h) {
            if (Double.isNaN(d.v[Trends.RHR])) continue;
            // nothing at the first date, a clear step at the second
            if (!d.date.isBefore(b)) d.v[Trends.RHR] += 8;
        }
        TrendChange.Result r = TrendChange.at(h, Trends.RHR, TODAY, java.util.Arrays.asList(a, b), L);
        eq("the tiny step is dropped: one step", r.steps.size(), 1);
        eq("two levels", r.levels.size(), 2);
        TrendChange.Step s = r.steps.get(0);
        check("the clear step kept, at its date", s.date.equals(b) || s.date.isAfter(b) && s.date.isBefore(b.plusDays(3)));
        // the level before is the mean of every value before the kept step, not only those after the dropped one
        double sum = 0;
        int n = 0;
        for (Trends.Day d : h) {
            if (Double.isNaN(d.v[Trends.RHR]) || !d.date.isBefore(s.date)) continue;
            sum += d.v[Trends.RHR];
            n++;
        }
        check("the step's level before is the merged part's: " + s.before + " and " + sum / n, Math.abs(s.before - sum / n) < 1e-9);
        check("the step's levels are the drawn ones", Math.abs(s.before - r.levels.get(0).value) < 1e-9 && Math.abs(s.after - r.levels.get(1).value) < 1e-9);
        check("the drawn level before starts at the first value", r.levels.get(0).from.equals(h.get(0).date) || r.levels.get(0).from.isBefore(a));
        eq("its sentence from the same numbers", r.text, TrendChange.sentence(Trends.RHR, s.date, s.clear, s.after - s.before, s.lo, s.hi, L));
        check("its range holds no zero", s.lo > 0 && s.asBig == 0 && r.looked);
        // a flat history: both ranges hold zero, one dropped after the other
        TrendChange.Result both = TrendChange.at(history(25, 420, -1, null, 0, false), Trends.RHR, TODAY, java.util.Arrays.asList(a, b), L);
        check("no step in a flat history: both ranges hold zero, nothing drawn: " + both.text, both.steps.isEmpty() && both.levels.isEmpty() && both.text.equals(TrendChange.NONE));
    }

    /** Under 8 weeks with values: nothing is tested, and Regularity (a spread) never is. */
    static void tooFew() {
        List<Trends.Day> h = history(31, 38, -1, null, 0);
        TrendChange.Result r = TrendChange.of(h, Trends.ASLEEP, TODAY, L);
        check("38 days (6 weeks): not looked", !r.looked && r.steps.isEmpty());
        eq("38 days: says so", r.text, TrendChange.TOO_FEW);
        TrendChange.Result g = TrendChange.of(history(31, 365, -1, null, 0), Trends.REGULARITY, TODAY, L);
        check("regularity: not looked", !g.looked);
        eq("regularity: says why", g.text, TrendChange.NOT_SPREAD);
        eq("no days at all", TrendChange.of(new ArrayList<Trends.Day>(), Trends.HRV, TODAY, L).text, TrendChange.TOO_FEW);
        // today's steps are not over: left out as on the chart
        List<Trends.Day> t = history(32, 365, -1, null, 0);
        Trends.Day today = new Trends.Day(TODAY).set(Trends.STEPS, 900000, Trends.RING);
        t.add(today);
        check("today's steps left out", TrendChange.of(t, Trends.STEPS, TODAY, L).steps.isEmpty()
                == TrendChange.of(t.subList(0, t.size() - 1), Trends.STEPS, TODAY, L).steps.isEmpty());
    }

    /** Bedtimes around midnight: a step from 23:40 to 00:20 is 40 min later, not 23 hours earlier. */
    static void clockStep() {
        LocalDate at = TODAY.minusDays(150);
        List<Trends.Day> h = history(41, 365, -1, null, 0);
        for (Trends.Day d : h) {
            if (Double.isNaN(d.v[Trends.BEDTIME])) continue;
            double v = 1420 + (d.v[Trends.BEDTIME] - 1410 + 720 + 1440) % 1440 - 720 + (d.date.isBefore(at) ? 0 : 40);
            d.v[Trends.BEDTIME] = ((v % 1440) + 1440) % 1440;
        }
        TrendChange.Result r = TrendChange.of(h, Trends.BEDTIME, TODAY, L);
        check("bedtime step found", r.steps.size() >= 1);
        TrendChange.Step s = r.in(at.minusDays(7), at.plusDays(7));
        check("near its date", s != null);
        check("about 40 min later: " + r.text, s != null && s.after - s.before > 25 && s.after - s.before < 55 && r.text.contains("later"));
    }

    static void texts() {
        LocalDate d = LocalDate.parse("2026-06-12");
        eq("sentence on one side", t(TrendChange.sentence(Trends.ASLEEP, d, true, 25, 15, 35, L)),
                "From about 12 June 2026 you sleep 25 min longer on average than before (15 min to 35 min longer).");
        eq("sentence down", t(TrendChange.sentence(Trends.STEPS, d, true, -1500, -2100, -900, L)),
                "From about 12 June 2026 you walk 1,500 steps a day fewer on average than before (900 steps a day to 2,100 steps a day fewer).");
        eq("sentence across zero", t(TrendChange.sentence(Trends.HRV, d, true, 3, -1, 7, L)),
                "From about 12 June 2026 your HRV is 3 ms higher on average than before (could be 1 ms lower to 7 ms higher), so about the same.");
        eq("too small for its unit", t(TrendChange.sentence(Trends.TEMP, d, true, 0.001, 0.0005, 0.002, L)).substring(0, 52), "From about 12 June 2026 your temperature is slightly");
        eq("a small step: the part of the month", t(TrendChange.sentence(Trends.ASLEEP, d, false, 20, 8, 32, L)),
                "From around mid June 2026 you sleep 20 min longer on average than before (8 min to 32 min longer).");
        eq("screen time: a typical day", t(TrendChange.sentence(Trends.SCREEN, LocalDate.parse("2026-06-03"), false, -30, -45, -15, L)),
                "From around early June 2026 your screen time is 30 min shorter on a typical day than before (15 min to 45 min shorter).");
        eq("late", TrendChange.roughDate(LocalDate.parse("2026-06-21")), "late June 2026");
        t(TrendChange.NONE);
        t(TrendChange.TOO_FEW);
        t(TrendChange.NOT_SPREAD);
        t(TrendChange.CAPTION);
        // the comparison of phase 1 says the same as before the range moved into its own method
        eq("phase 1 sentence unchanged", Trends.sentence(Trends.ASLEEP, 12, -4, 28, "October", L),
                "This month you sleep 12 min longer on average than in October (could be 4 min shorter to 28 min longer), so about the same.");
        eq("phase 1 sentence on one side", Trends.sentence(Trends.ASLEEP, 25, 10, 40, "October", L),
                "This month you sleep 25 min longer on average than in October (10 min to 40 min longer).");
    }

    static String t(String s) {
        TEXTS.add(s);
        return s;
    }

    static Trends.Day night(String date, double asleep, int src) {
        Trends.Day d = new Trends.Day(LocalDate.parse(date)).set(Trends.ASLEEP, asleep, src);
        d.detail = StageFilter.BALANCED;
        return d;
    }

    /** Events from the values themselves and the known ones, each where it applies. */
    static void events() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        LocalDate first = LocalDate.parse("2026-01-05");
        for (int i = 0; i < 80; i++) {
            LocalDate x = first.plusDays(i);
            if (i >= 40 && i < 50) {
                d.add(new Trends.Day(x));
                continue; // ten days without the ring
            }
            // the watch from day 60 on, with one stray watch night before (no lasting switch)
            int src = i >= 60 || i == 20 ? Trends.WATCH : Trends.RING;
            Trends.Day n = night(x.toString(), 420, src);
            if (i >= 70) n.detail = StageFilter.CALM;
            n.zone = i >= 30 && i < 35 ? "America/New_York" : "Europe/Zurich";
            d.add(n);
        }
        List<TrendChange.Event> known = new ArrayList<TrendChange.Event>();
        known.add(new TrendChange.Event(TrendChange.PERIOD, first.plusDays(3), first.plusDays(3), "Period started"));
        known.add(new TrendChange.Event(TrendChange.LEFT_OUT, first.plusDays(5), first.plusDays(6), "Time left out"));
        known.add(new TrendChange.Event(TrendChange.WORKOUT, first.plusDays(5), first.plusDays(5), "Workout"));
        known.add(new TrendChange.Event(TrendChange.WORKOUT, first.plusDays(6), first.plusDays(6), "Workout"));
        known.add(new TrendChange.Event(TrendChange.PHONE_AWAKE, first.plusDays(8), first.plusDays(8), "Phone use counted as awake from here"));
        List<TrendChange.Event> e = TrendChange.events(d, Trends.ASLEEP, LocalDate.parse("2026-06-01"), known);
        StringBuilder sb = new StringBuilder();
        for (TrendChange.Event x : e) sb.append(x.withDate()).append(" | ");
        String all = sb.toString();
        check("a lasting switch to the watch: " + all, all.contains("Watch nights from here (6 Mar 2026)"));
        check("one stray watch night is no switch", !all.contains("25 Jan 2026"));
        check("ten days without nights: " + all, all.contains("No nights (14 to 23 Feb 2026)"));
        // one bracket, the way home named by its place too (code review of f04a5c5)
        check("a trip there and back", all.contains("Clock moved to New York time (4 Feb 2026)") && all.contains("Clock moved to Zurich time (9 Feb 2026)")
                && !all.contains("Trip") && !all.contains(") ("));
        check("stage detail changed", all.contains("Stage detail Calm from here (16 Mar 2026)"));
        check("left out time on every chart", all.contains("Time left out (10 to 11 Jan 2026)"));
        check("phone use on sleep", all.contains("Phone use counted as awake from here (13 Jan 2026)"));
        check("no period mark on time asleep", !all.contains("Period"));
        check("no workouts on time asleep", !all.contains("Workout"));
        check("oldest first", e.get(0).from.equals(first.plusDays(5)));
        // on temperature (here no values): the period, not the detail level nor phone use
        List<TrendChange.Event> temp = TrendChange.events(d, Trends.TEMP, LocalDate.parse("2026-06-01"), known);
        String ts = TrendChange.list(temp, first, first.plusDays(100));
        check("temperature: period and trip: " + ts, ts.contains("Period started (8 Jan 2026)") && ts.contains("Clock moved") && !ts.contains("Stage") && !ts.contains("Phone"));
        // periods counted once more than one started in the span (code review of f04a5c5: a year listed about 13)
        List<TrendChange.Event> cycle = new ArrayList<TrendChange.Event>();
        for (int k = 0; k < 13; k++) cycle.add(new TrendChange.Event(TrendChange.PERIOD, first.plusDays(28 * k), first.plusDays(28 * k), "Period started"));
        cycle.add(new TrendChange.Event(TrendChange.LEFT_OUT, first.plusDays(40), first.plusDays(41), "Time left out"));
        cycle.add(new TrendChange.Event(TrendChange.WORKOUT, first.plusDays(50), first.plusDays(50), "Workout"));
        eq("a year: periods counted", t(TrendChange.list(cycle, first, first.plusDays(364))), "Time left out (14 to 15 Feb 2026), 13 periods started, 1 workout");
        eq("two periods: counted", TrendChange.list(cycle, first, first.plusDays(30)), "2 periods started");
        eq("one period: with its date", TrendChange.list(cycle, first.plusDays(1), first.plusDays(30)), "Period started (2 Feb 2026)");
        // steps: the workouts counted
        String ss = TrendChange.list(TrendChange.events(d, Trends.STEPS, LocalDate.parse("2026-06-01"), known), first, first.plusDays(10));
        eq("steps: workouts counted", t(ss), "Time left out (10 to 11 Jan 2026), 2 workouts");
        eq("one workout", TrendChange.list(known.subList(2, 3), first, first.plusDays(10)), "1 workout");
        eq("nothing", TrendChange.list(known, first.plusDays(50), first.plusDays(60)), "");
        // nights entered by hand for weeks: a switch of their own
        List<Trends.Day> hand = new ArrayList<Trends.Day>();
        for (int i = 0; i < 20; i++) hand.add(night(first.plusDays(i).toString(), 420, i < 10 ? Trends.RING : Trends.HAND));
        String hs = TrendChange.list(TrendChange.events(hand, Trends.ASLEEP, LocalDate.parse("2026-06-01"), null), first, first.plusDays(30));
        eq("nights by hand from here", hs, "Nights entered by hand from here (15 Jan 2026)");
        // an event that ends before it starts is one day
        TrendChange.Event back = new TrendChange.Event(TrendChange.LEFT_OUT, first, first.minusDays(2), "Time left out");
        eq("an end before the start is the start", back.to, first);
        // the last few nights from another device are no lasting switch yet
        List<Trends.Day> tail = new ArrayList<Trends.Day>();
        for (int i = 0; i < 20; i++) tail.add(night(first.plusDays(i).toString(), 420, i < 16 ? Trends.RING : Trends.WATCH));
        eq("three watch nights at the end: no switch yet", TrendChange.events(tail, Trends.ASLEEP, LocalDate.parse("2026-06-01"), null).size(), 0);
        // a zone without a place keeps its id
        List<Trends.Day> utc = new ArrayList<Trends.Day>();
        for (int i = 0; i < 2; i++) {
            Trends.Day x = night(first.plusDays(i).toString(), 420, Trends.RING);
            x.zone = i == 0 ? "Europe/Zurich" : "UTC";
            utc.add(x);
        }
        eq("a zone without a place", TrendChange.list(TrendChange.events(utc, Trends.ASLEEP, LocalDate.parse("2026-06-01"), null), first, first.plusDays(5)),
                "Clock moved to UTC time (6 Jan 2026)");
        // a clock change in one zone is no trip
        List<Trends.Day> dst = new ArrayList<Trends.Day>();
        for (int i = 0; i < 4; i++) {
            Trends.Day x = night(LocalDate.parse("2026-03-27").plusDays(i).toString(), 420, Trends.RING);
            x.zone = "Europe/Zurich";
            dst.add(x);
        }
        // a zone of the same clock is no trip either
        dst.get(3).zone = "Europe/Berlin";
        check("summer time and a zone of the same clock: no trip", TrendChange.events(dst, Trends.ASLEEP, LocalDate.parse("2026-06-01"), null).isEmpty());
        // the spans as written
        eq("span across months", TrendChange.list(java.util.Collections.singletonList(new TrendChange.Event(TrendChange.LEFT_OUT, LocalDate.parse("2026-02-27"),
                LocalDate.parse("2026-03-02"), "Time left out")), first, first.plusDays(100)), "Time left out (27 Feb to 2 Mar 2026)");
        eq("span across years", TrendChange.list(java.util.Collections.singletonList(new TrendChange.Event(TrendChange.LEFT_OUT, LocalDate.parse("2025-12-30"),
                LocalDate.parse("2026-01-02"), "Time left out")), first.minusDays(30), first.plusDays(100)), "Time left out (30 Dec 2025 to 2 Jan 2026)");
        // around a step: the events within two weeks named with it, never "because"
        List<Trends.Day> h = history(61, 365, Trends.ASLEEP, TODAY.minusDays(150), 40);
        TrendChange.Result r = TrendChange.of(h, Trends.ASLEEP, TODAY, L);
        List<TrendChange.Event> near = new ArrayList<TrendChange.Event>();
        LocalDate sd = r.steps.get(0).date;
        near.add(new TrendChange.Event(TrendChange.DEVICE, sd.plusDays(3), sd.plusDays(3), "Watch nights from here"));
        near.add(new TrendChange.Event(TrendChange.ZONE, sd.plusDays(60), sd.plusDays(60), "Time zone Asia/Tokyo"));
        String around = t(TrendChange.around(r, near));
        // two steps, each with its own line
        List<Trends.Day> two = history(21, 420, -1, null, 0, false);
        for (Trends.Day x : two) if (!Double.isNaN(x.v[Trends.ASLEEP]) && !x.date.isBefore(TODAY.minusDays(300)) && x.date.isBefore(TODAY.minusDays(120))) x.v[Trends.ASLEEP] += 60;
        TrendChange.Result tr = TrendChange.of(two, Trends.ASLEEP, TODAY, L);
        List<TrendChange.Event> both = new ArrayList<TrendChange.Event>();
        for (TrendChange.Step x : tr.steps) both.add(new TrendChange.Event(TrendChange.ZONE, x.date, x.date, "Time zone Asia/Tokyo"));
        String a2 = TrendChange.around(tr, both);
        check("around two steps: two lines: " + a2, tr.steps.size() == 2 && a2.indexOf("Around") != a2.lastIndexOf("Around"));
        check("around: the near event only: " + around, around.startsWith("Around ") && around.contains("Watch nights") && !around.contains("Tokyo"));
        check("around: never because", !around.toLowerCase(Locale.ROOT).contains("because"));
        // workouts stay under the chart, not in the line around a step
        List<TrendChange.Event> work = new ArrayList<TrendChange.Event>();
        work.add(new TrendChange.Event(TrendChange.WORKOUT, sd.plusDays(1), sd.plusDays(1), "Workout"));
        eq("around: no workouts", TrendChange.around(r, work), "");
        // a step that is not clear looks six weeks around it, a clear one two
        TrendChange.Step rough = new TrendChange.Step(sd, 0, 1, 0.5, 1.5, 0, false, "x");
        TrendChange.Result rr = new TrendChange.Result();
        rr.steps.add(rough);
        List<TrendChange.Event> far = new ArrayList<TrendChange.Event>();
        far.add(new TrendChange.Event(TrendChange.LEFT_OUT, sd.plusDays(30), sd.plusDays(30), "Time left out"));
        check("around a rough step: a month off is named, with the part of the month", TrendChange.around(rr, far).startsWith("Around " + TrendChange.roughDate(sd) + ": Time left out"));
        eq("around a clear step: a month off is not", TrendChange.around(r, far), "");
        eq("around without events", TrendChange.around(r, new ArrayList<TrendChange.Event>()), "");
    }

    /** A date's place on the chart is where its small dot is. */
    static void place() {
        List<Trends.Day> h = history(71, 120, -1, null, 0);
        Trends.View v = Trends.of(h, Trends.ASLEEP, Trends.MONTHS, TODAY);
        for (int i = 0; i < v.dayDates.size(); i += 7) {
            double p = TrendChange.place(v, v.dayDates.get(i));
            double dot = v.dayBucket.get(i) + 0.5 + v.dayOffset.get(i);
            check("place of " + v.dayDates.get(i), Math.abs(p - dot) < 1e-9);
        }
        check("outside the chart", Double.isNaN(TrendChange.place(v, TODAY.plusYears(2))));
        // what the chart draws, worked out once: the same places
        TrendChange.Result r = TrendChange.of(history(7, 365, Trends.ASLEEP, LocalDate.parse("2026-06-15"), 50), Trends.ASLEEP, TODAY, L);
        Trends.View rv = Trends.of(history(7, 365, Trends.ASLEEP, LocalDate.parse("2026-06-15"), 50), Trends.ASLEEP, Trends.WEEKS, TODAY);
        List<TrendChange.Event> ev = new ArrayList<TrendChange.Event>();
        ev.add(new TrendChange.Event(TrendChange.LEFT_OUT, LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-04"), "Time left out"));
        ev.add(new TrendChange.Event(TrendChange.PERIOD, TODAY.minusYears(3), TODAY.minusYears(3), "Period started"));
        TrendChange.Drawn dr = TrendChange.drawn(rv, r, ev);
        check("drawn: the step where place puts it", dr.steps.length == 1 && dr.steps[0] == TrendChange.place(rv, r.steps.get(0).date));
        check("drawn: the levels", dr.levelFrom.length == 2 && dr.levelFrom[1] == TrendChange.place(rv, r.levels.get(1).from) && dr.levelValue[0] == r.levels.get(0).value);
        check("drawn: a span and an event outside the chart", !dr.eventDay[0] && dr.eventTo[0] > dr.eventFrom[0] && dr.eventDay[1] && Double.isNaN(dr.eventFrom[1]));
        check("drawn: nothing", TrendChange.drawn(rv, null, null).steps.length == 0);
        // a month with one night: the middle of its column
        List<Trends.Day> one = new ArrayList<Trends.Day>();
        one.add(night("2026-01-10", 400, Trends.RING));
        one.add(night("2026-02-10", 400, Trends.RING));
        one.add(night("2026-02-20", 400, Trends.RING));
        Trends.View ov = Trends.of(one, Trends.ASLEEP, Trends.MONTHS, TODAY);
        check("one night: the middle", Math.abs(TrendChange.place(ov, LocalDate.parse("2026-01-25")) - 0.5) < 1e-12);
        check("a day before the first night of its month: the column's edge", Math.abs(TrendChange.place(ov, LocalDate.parse("2026-02-01")) - 1.1) < 1e-12);
        check("before the chart", Double.isNaN(TrendChange.place(v, TODAY.minusYears(2))));
    }

    /** The touch readout: the period's numbers, then the step in it and its marks; the note under the chart says what is drawn. */
    static void readouts() {
        LocalDate at = LocalDate.parse("2026-06-15");
        List<Trends.Day> h = history(7, 365, Trends.ASLEEP, at, 30);
        TrendChange.Result r = TrendChange.of(h, Trends.ASLEEP, TODAY, L);
        Trends.View v = Trends.of(h, Trends.ASLEEP, Trends.MONTHS, TODAY);
        TrendChange.Step st = r.steps.get(0);
        int i = v.indexOf(st.date);
        List<TrendChange.Event> ev = new ArrayList<TrendChange.Event>();
        ev.add(new TrendChange.Event(TrendChange.ZONE, st.date.plusDays(1), st.date.plusDays(1), "Time zone Europe/Lisbon"));
        String with = t(TrendChange.readout(v, i, r, ev, L));
        String[] lines = with.split("\n");
        eq("readout: the period's numbers first", lines[0], Trends.readout(v, i, L));
        eq("readout: then the step", lines[1], st.text);
        check("readout: then the marks: " + with, lines.length == 3 && lines[2].startsWith("Marks: Time zone Europe/Lisbon ("));
        int other = i == 0 ? 1 : 0;
        eq("readout of a period without a step or mark", TrendChange.readout(v, other, r, ev, L), Trends.readout(v, other, L));
        eq("readout without phase 2", TrendChange.readout(v, other, null, null, L), Trends.readout(v, other, L));
        eq("chart note: nothing drawn", TrendChange.chartNote(new TrendChange.Result(), new ArrayList<TrendChange.Event>()), "");
        eq("chart note: steps and marks", t(TrendChange.chartNote(r, ev)), "Dashed line: where your values stepped. Lines: your average before and after. "
                + "Marks under the chart: events such as a new device, a trip or a time left out. Touch a period to read them.");
        eq("chart note: marks only", t(TrendChange.chartNote(null, ev)), "Marks under the chart: events such as a new device, a trip or a time left out. Touch a period to read them.");
        eq("chart note: steps only", t(TrendChange.chartNote(r, null)), "Dashed line: where your values stepped. Lines: your average before and after. Touch a period to read them.");
    }

    /**
     * Phase 3: the marks of the factor picked under "Mark days with": only its days, on every measure but the factors' own,
     * none for None, counted in the readout, one per period on the chart, and never in a sentence or a test.
     */
    static void lifeMarks() {
        java.util.TreeMap<LocalDate, List<String>> log = new java.util.TreeMap<LocalDate, List<String>>();
        log.put(LocalDate.parse("2026-06-05"), java.util.Arrays.asList("alcohol", "meditation"));
        log.put(LocalDate.parse("2026-06-12"), java.util.Arrays.asList("alcohol"));
        log.put(LocalDate.parse("2026-06-20"), java.util.Arrays.asList("meditation"));
        log.put(LocalDate.parse("2026-08-01"), java.util.Arrays.asList("alcohol"));
        List<TrendChange.Event> al = TrendChange.life(log, "alcohol", "Alcohol");
        eq("only the picked factor's days", al.size(), 3);
        check("each a day of kind LIFE named as on Today", al.get(0).kind == TrendChange.LIFE && al.get(0).from.equals(al.get(0).to)
                && "Alcohol".equals(al.get(0).text) && al.get(0).from.equals(LocalDate.parse("2026-06-05")));
        check("None: no marks", TrendChange.life(log, null, null).isEmpty() && TrendChange.life(null, "alcohol", "Alcohol").isEmpty());
        boolean every = true;
        for (int m = 0; m < Trends.MEASURES; m++) every &= TrendChange.applies(TrendChange.LIFE, m) == (m != Trends.LIFE);
        check("marked on every measure but the factors' own", every);
        List<Trends.Day> h = history(7, 365, Trends.ASLEEP, LocalDate.parse("2026-06-15"), 30);
        int on = 0;
        for (int m = 0; m < Trends.COUNT; m++) {
            int n = 0;
            for (TrendChange.Event e : TrendChange.events(h, m, TODAY, al)) if (e.kind == TrendChange.LIFE) n++;
            if (n == 3) on++;
        }
        eq("every daily measure gets the three marks", on, Trends.COUNT);
        int cyc = 0;
        for (TrendChange.Event e : TrendChange.events(h, Trends.CYCLE, TODAY, al)) if (e.kind == TrendChange.LIFE) cyc++;
        eq("cycle length gets them too", cyc, 3);
        eq("the readout counts them", t(TrendChange.list(al, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"))), "Alcohol on 2 days");
        eq("one day", t(TrendChange.list(al, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))), "Alcohol on 1 day");
        List<TrendChange.Event> mixed = new ArrayList<TrendChange.Event>(al);
        mixed.add(new TrendChange.Event(TrendChange.WORKOUT, LocalDate.parse("2026-06-07"), LocalDate.parse("2026-06-07"), "Workout"));
        eq("after the other marks", t(TrendChange.list(mixed, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"))), "1 workout, Alcohol on 2 days");
        // one mark per month in the middle of its column
        Trends.View v = Trends.of(h, Trends.ASLEEP, Trends.MONTHS, TODAY);
        TrendChange.Drawn dr = TrendChange.drawn(v, null, al);
        int shown = 0;
        int jun = v.indexOf(LocalDate.parse("2026-06-01")), aug = v.indexOf(LocalDate.parse("2026-08-01"));
        boolean middle = true;
        for (int i = 0; i < al.size(); i++) {
            check("a life mark is flagged", dr.eventLife[i]);
            if (Double.isNaN(dr.eventFrom[i])) continue;
            shown++;
            middle &= dr.eventFrom[i] == jun + 0.5 || dr.eventFrom[i] == aug + 0.5;
        }
        check("one mark per month with alcohol (" + shown + "), in its middle", shown == 2 && middle);
        // display only: the sentences and the change test are the same with the marks
        TrendChange.Result r = TrendChange.of(h, Trends.ASLEEP, TODAY, L);
        List<TrendChange.Event> base = TrendChange.events(h, Trends.ASLEEP, TODAY, null), marked = TrendChange.events(h, Trends.ASLEEP, TODAY, al);
        eq("the text around a step is the same with the marks", TrendChange.around(r, marked), TrendChange.around(r, base));
        eq("the note under the chart is the same with the marks", TrendChange.chartNote(r, marked), TrendChange.chartNote(r, base));
        eq("only marks of a factor: no phase 2 note", TrendChange.chartNote(null, al), "");
        String ro = TrendChange.readout(v, jun, r, marked, L);
        check("the touch readout adds them: " + ro, ro.endsWith("Alcohol on 2 days") && ro.startsWith(Trends.readout(v, jun, L)));
    }

    /** Phase 3's measures have no value per day: no test, a sentence why, and only the trips and stored events as marks. */
    static void phase3Measures() {
        List<Trends.Day> h = history(5, 200, Trends.ASLEEP, null, 0);
        TrendChange.Result c = TrendChange.of(h, Trends.CYCLE, TODAY, L), p = TrendChange.of(h, Trends.PERIOD_DAYS, TODAY, L),
                f = TrendChange.of(h, Trends.LIFE, TODAY, L);
        eq("cycle length", t(c.text), "Cycle length has one value per cycle, not per day, so no change is looked for here.");
        eq("period length", t(p.text), "Period length has one value per period, not per day, so no change is looked for here.");
        eq("life factors", t(f.text), "These are days you logged, so no change is looked for here. What a factor goes with in your nights is on Body, card Life factors and your nights.");
        check("nothing tested, no step", !c.looked && !p.looked && !f.looked && c.steps.isEmpty() && f.levels.isEmpty());
        List<TrendChange.Event> known = new ArrayList<TrendChange.Event>();
        known.add(new TrendChange.Event(TrendChange.PERIOD, TODAY.minusDays(30), TODAY.minusDays(30), "Period started"));
        known.add(new TrendChange.Event(TrendChange.LEFT_OUT, TODAY.minusDays(40), TODAY.minusDays(39), "Time left out"));
        List<TrendChange.Event> e = TrendChange.events(h, Trends.CYCLE, TODAY, known);
        check("cycle length: the time left out, not each period start (every dot is one)", e.size() == 1 && e.get(0).kind == TrendChange.LEFT_OUT);
    }

    /** Three years, every measure: well under the time Trends may take (the phone is a few times slower). */
    static void speed() {
        List<Trends.Day> h = history(81, 3 * 365, Trends.ASLEEP, TODAY.minusDays(400), 30);
        for (int m = 0; m < Trends.COUNT; m++) TrendChange.of(h, m, TODAY, L);
        long t0 = System.nanoTime();
        int steps = 0;
        for (int m = 0; m < Trends.COUNT; m++) steps += TrendChange.of(h, m, TODAY, L).steps.size();
        double ms = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        TrendChange.of(h, Trends.ASLEEP, TODAY, L);
        double one = (System.nanoTime() - t1) / 1e6;
        // the slowest case: a step in every measure, so every test runs all its shuffles, with a second step looked for
        LocalDate at = TODAY.minusDays(500);
        for (Trends.Day d : h) {
            if (d.date.isBefore(at)) continue;
            for (int m = 0; m < Trends.COUNT; m++) if (!Double.isNaN(d.v[m]) && m != Trends.BEDTIME && m != Trends.WAKE) d.v[m] += step(m, 1.5);
        }
        long t2 = System.nanoTime();
        int all = 0;
        for (int m = 0; m < Trends.COUNT; m++) all += TrendChange.of(h, m, TODAY, L).steps.size();
        double slow = (System.nanoTime() - t2) / 1e6;
        System.out.printf(L, "    three years, every measure: %.0f ms (%d steps), time asleep with its step alone %.0f ms; a step in every measure: %.0f ms (%d steps)%n",
                ms, steps, one, slow, all);
        check("three years, every measure under 500 ms", ms < 500);
        check("three years, a step in every measure, under 1 s", slow < 1000 && all >= Trends.COUNT - 3);
    }

    static void plain() {
        // the text under the card claims only what knownStep shows (code review of f04a5c5): two weeks from one spread, a slow
        // change as one step
        String ex = app.openring.ui.Explain.TRENDS_CHANGE;
        check("explain: within two weeks, not a week", ex.contains("one usual day to day difference or more is usually found within two weeks")
                && !ex.contains("within about a week") && ex.contains("weeks or even months off"));
        check("explain: a slow change", ex.contains("A slow change shows as one step where it moved fastest."));
        TEXTS.add(ex);
        for (String s : TEXTS) {
            check("no dash as punctuation: " + s, !s.contains(" - ") && !s.contains(" -- ") && !s.contains("–") && !s.contains("—"));
        }
    }
}
