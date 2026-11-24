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
        falseAlarms();
        sameEveryTime();
        twoSteps();
        tooFew();
        clockStep();
        texts();
        events();
        place();
        readouts();
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
        Random r = new Random(seed);
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
                carry[m] = 0.3 * carry[m] + r.nextGaussian() * SD[m];
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
     * Known steps in every measure at four dates: {found, within {@code days} of the date, true size in the 90 % range of
     * those}, and the farthest off.
     */
    static int[] steps(double size, int days) {
        int found = 0, near = 0, inRange = 0, worst = 0;
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
                if (s.lo <= st && st <= s.hi) inRange++;
            }
        }
        return new int[] {found, near, inRange, worst};
    }

    /** A known step is found within a week of its date, in each measure, and its size is in the range. */
    static void knownStep() {
        int tries = 4 * (Trends.COUNT - 1);
        // a clear step (one and a half spreads): within a week of its date
        int[] c = steps(1.5, 7);
        System.out.println("    known step of 1.5 spreads: found in " + c[0] + " of " + tries + ", within a week of its date in " + c[1]
                + " (the farthest " + c[3] + " days off), true size in the 90 % range in " + c[2] + " of " + c[1]);
        check("a step of 1.5 spreads is found in every measure", c[0] == tries);
        check("found within a week of its date (at least 9 in 10)", c[1] * 10 >= tries * 9);
        check("its true size inside the 90 % range (at least 8 in 10 of those)", c[2] * 10 >= c[1] * 8);
        // a smaller step (one spread, about the demo's 25 min of sleep): still found, its date rougher
        int[] s1 = steps(1.0, 14);
        System.out.println("    known step of 1 spread: found in " + s1[0] + " of " + tries + ", within two weeks of its date in " + s1[1]
                + " (the farthest " + s1[3] + " days off), true size in the 90 % range in " + s1[2] + " of " + s1[1]);
        check("a step of one spread is found in every measure", s1[0] == tries);
        check("found within two weeks of its date (at least 8 in 10)", s1[1] * 10 >= tries * 8);
        // the date in the sentence, and the levels drawn on both sides
        LocalDate at = LocalDate.parse("2026-06-15");
        TrendChange.Result r = TrendChange.of(history(7, 365, Trends.ASLEEP, at, 30), Trends.ASLEEP, TODAY, L);
        check("one step", r.steps.size() == 1 && r.looked);
        eq("two levels", r.levels.size(), 2);
        TrendChange.Step s = r.steps.get(0);
        check("the step goes up by about 30 min", s.after - s.before > 15 && s.after - s.before < 45);
        check("its range holds the step", s.lo < s.after - s.before && s.after - s.before < s.hi);
        check("the sentence starts with the date: " + r.text, r.text.startsWith("From about " + TrendChange.date(s.date) + " you sleep "));
        check("no more than 1 in 100 shuffles as big", s.asBig <= TrendChange.MAX_AS_BIG);
        check("levels meet at the step", r.levels.get(1).from.equals(s.date) && r.levels.get(0).to.isBefore(s.date));
    }

    /** Made up histories without any change: across all 12 measures about 1 in 100 shows a step that is not there. */
    static void falseAlarms() {
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

    /** Seeded from the values: every opening gives the same answer; another history another seed. */
    static void sameEveryTime() {
        List<Trends.Day> h = history(11, 400, Trends.STEPS, TODAY.minusDays(150), 2000);
        String a = TrendChange.of(h, Trends.STEPS, TODAY, L).text, b = TrendChange.of(h, Trends.STEPS, TODAY, L).text;
        eq("same history, same sentence", a, b);
        // a value near the border of the test: the count of shuffles as big is the same too
        List<Trends.Day> w = history(12, 365, Trends.HRV, TODAY.minusDays(100), 4);
        TrendChange.Result r1 = TrendChange.of(w, Trends.HRV, TODAY, L), r2 = TrendChange.of(w, Trends.HRV, TODAY, L);
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
        eq("sentence on one side", t(TrendChange.sentence(Trends.ASLEEP, d, 25, 15, 35, L)),
                "From about 12 June 2026 you sleep 25 min longer on average than before (15 min to 35 min longer).");
        eq("sentence down", t(TrendChange.sentence(Trends.STEPS, d, -1500, -2100, -900, L)),
                "From about 12 June 2026 you walk 1,500 steps a day fewer on average than before (900 steps a day to 2,100 steps a day fewer).");
        eq("sentence across zero", t(TrendChange.sentence(Trends.HRV, d, 3, -1, 7, L)),
                "From about 12 June 2026 your HRV is 3 ms higher on average than before (could be 1 ms lower to 7 ms higher), so about the same.");
        eq("too small for its unit", t(TrendChange.sentence(Trends.TEMP, d, 0.001, 0.0005, 0.002, L)).substring(0, 52), "From about 12 June 2026 your temperature is slightly");
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
        check("a trip there and back", all.contains("Time zone America/New_York (4 Feb 2026)") && all.contains("Time zone Europe/Zurich (9 Feb 2026)"));
        check("stage detail changed", all.contains("Stage detail Calm from here (16 Mar 2026)"));
        check("left out time on every chart", all.contains("Time left out (10 to 11 Jan 2026)"));
        check("phone use on sleep", all.contains("Phone use counted as awake from here (13 Jan 2026)"));
        check("no period mark on time asleep", !all.contains("Period"));
        check("no workouts on time asleep", !all.contains("Workout"));
        check("oldest first", e.get(0).from.equals(first.plusDays(5)));
        // on temperature (here no values): the period, not the detail level nor phone use
        List<TrendChange.Event> temp = TrendChange.events(d, Trends.TEMP, LocalDate.parse("2026-06-01"), known);
        String ts = TrendChange.list(temp, first, first.plusDays(100));
        check("temperature: period and trip: " + ts, ts.contains("Period started (8 Jan 2026)") && ts.contains("Time zone") && !ts.contains("Stage") && !ts.contains("Phone"));
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
        for (String s : TEXTS) {
            check("no dash as punctuation: " + s, !s.contains(" - ") && !s.contains(" -- ") && !s.contains("–") && !s.contains("—"));
        }
    }
}
