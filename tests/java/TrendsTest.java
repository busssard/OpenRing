import app.openring.core.analysis.ClockAverage;
import app.openring.core.analysis.StageFilter;
import app.openring.core.analysis.Trends;
import app.openring.core.analysis.WakeUps;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Trends (issue #374, phase 1): calendar weeks, months and years, the dot and the middle half, hollow periods, the touch
 * readout, bedtimes across midnight, and "this month against the last" with its resampled range. Run by tests/run_tests.sh.
 */
public class TrendsTest {
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

    static void near(String name, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (!ok) System.out.println("  got: " + got + " want: " + want);
        check(name, ok);
    }

    static final Locale L = Locale.US;
    static final List<String> TEXTS = new ArrayList<String>();

    static String t(String s) {
        TEXTS.add(s);
        return s;
    }

    public static void main(String[] args) {
        edges();
        gapsAndOne();
        percentiles();
        addUp();
        midnight();
        spread();
        readouts();
        today();
        wakeUps();
        compare();
        compareKinds();
        enough();
        speed();
        plain();
        System.out.println("trends: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Trends.Day day(String date, int m, double v) {
        return new Trends.Day(LocalDate.parse(date)).set(m, v, Trends.RING);
    }

    /** Weeks run Monday to Sunday, months and years are calendar ones, also around a clock change and the turn of the year. */
    static void edges() {
        eq("week of a Sunday (the spring clock change day) starts the Monday before", Trends.start(LocalDate.parse("2026-03-29"), Trends.WEEKS), LocalDate.parse("2026-03-23"));
        eq("a Monday starts its own week", Trends.start(LocalDate.parse("2026-03-30"), Trends.WEEKS), LocalDate.parse("2026-03-30"));
        eq("month", Trends.start(LocalDate.parse("2026-03-31"), Trends.MONTHS), LocalDate.parse("2026-03-01"));
        eq("year", Trends.start(LocalDate.parse("2026-12-31"), Trends.YEARS), LocalDate.parse("2026-01-01"));
        eq("week across the turn of the year", Trends.start(LocalDate.parse("2027-01-01"), Trends.WEEKS), LocalDate.parse("2026-12-28"));
        eq("next week", Trends.next(LocalDate.parse("2026-12-28"), Trends.WEEKS), LocalDate.parse("2027-01-04"));
        eq("next year", Trends.next(LocalDate.parse("2026-01-01"), Trends.YEARS), LocalDate.parse("2027-01-01"));
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        // nights filed by their stored day: the night that ends on 1 April after a flight counts in April, the one of 31 March in March
        d.add(day("2026-03-29", Trends.ASLEEP, 400));
        d.add(day("2026-03-30", Trends.ASLEEP, 420));
        d.add(day("2026-03-31", Trends.ASLEEP, 440));
        d.add(day("2026-04-01", Trends.ASLEEP, 460));
        Trends.View w = Trends.of(d, Trends.ASLEEP, Trends.WEEKS, LocalDate.parse("2026-05-01"));
        eq("weeks: two", w.buckets.size(), 2);
        eq("weeks: Sunday 29 March in the week of 23 March", w.buckets.get(0).n, 1);
        eq("weeks: Monday to Wednesday in the next", w.buckets.get(1).n, 3);
        eq("week ends on Sunday", w.buckets.get(0).end, LocalDate.parse("2026-03-29"));
        Trends.View m = Trends.of(d, Trends.ASLEEP, Trends.MONTHS, LocalDate.parse("2026-05-01"));
        eq("months: March has 3, April 1", m.buckets.get(0).n * 10 + m.buckets.get(1).n, 31);
        eq("month ends on its last day", m.buckets.get(0).end, LocalDate.parse("2026-03-31"));
        Trends.View y = Trends.of(d, Trends.ASLEEP, Trends.YEARS, LocalDate.parse("2026-05-01"));
        eq("years: one", y.buckets.size(), 1);
        check("a year of 4 days is hollow (under 60)", y.buckets.get(0).hollow);
        eq("indexOf", m.indexOf(LocalDate.parse("2026-04-15")), 1);
        eq("indexOf outside", m.indexOf(LocalDate.parse("2025-04-15")), -1);
        eq("labels", Trends.label(LocalDate.parse("2026-03-23"), Trends.WEEKS) + "|" + Trends.label(LocalDate.parse("2026-03-01"), Trends.MONTHS)
                + "|" + Trends.label(LocalDate.parse("2026-01-01"), Trends.YEARS), "Week of 23 Mar 2026|March 2026|2026");
    }

    /** A month without any night stays as a gap, a month with one night is a hollow dot without a band. */
    static void gapsAndOne() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        for (int i = 1; i <= 20; i++) d.add(day(String.format("2026-01-%02d", i), Trends.ASLEEP, 420));
        d.add(day("2026-03-15", Trends.ASLEEP, 400));
        Trends.View m = Trends.of(d, Trends.MONTHS == 1 ? Trends.ASLEEP : 0, Trends.MONTHS, LocalDate.parse("2026-06-01"));
        eq("gap: three months", m.buckets.size(), 3);
        eq("gap: February empty", m.buckets.get(1).n, 0);
        check("gap: no dot", Double.isNaN(m.buckets.get(1).value));
        eq("gap: readout", t(Trends.readout(m, 1, L)), "February 2026: no nights");
        eq("gap: tip", Trends.tip(m, 1, L), "February 2026\nno data");
        Trends.Bucket one = m.buckets.get(2);
        check("one night: hollow", one.hollow && one.n == 1);
        check("one night: band is the night itself", one.lo == 400 && one.hi == 400 && one.value == 400);
        eq("one night: readout without a middle half", t(Trends.readout(m, 2, L)), "March 2026: 6h 40m asleep on average, only 1 night");
        check("twenty nights: not hollow", !m.buckets.get(0).hollow);
        check("empty view", Trends.of(new ArrayList<Trends.Day>(), Trends.ASLEEP, Trends.MONTHS, LocalDate.parse("2026-06-01")).empty());
        double[] r = Trends.of(new ArrayList<Trends.Day>(), Trends.ASLEEP, Trends.MONTHS, LocalDate.parse("2026-06-01")).range();
        check("empty range", Double.isNaN(r[0]) && Double.isNaN(r[1]));
        double[] r2 = m.range();
        check("range spans days and dots", r2[0] == 400 && r2[1] == 420);
    }

    /** The middle half with 1, 2 and 30 values. */
    static void percentiles() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        d.add(day("2026-01-05", Trends.STEPS, 1000));
        d.add(day("2026-02-05", Trends.STEPS, 1000));
        d.add(day("2026-02-06", Trends.STEPS, 3000));
        for (int i = 1; i <= 30; i++) d.add(day(String.format("2026-04-%02d", i), Trends.STEPS, i));
        Trends.View m = Trends.of(d, Trends.STEPS, Trends.MONTHS, LocalDate.parse("2026-06-01"));
        Trends.Bucket a = m.buckets.get(0), b = m.buckets.get(1), c = m.buckets.get(3);
        check("1 value: band is the value", a.lo == 1000 && a.hi == 1000 && a.value == 1000);
        check("2 values: a quarter and three quarters of the way", b.lo == 1500 && b.hi == 2500 && b.value == 2000);
        check("30 values: 8.25 to 22.75 around 15.5", c.lo == 8.25 && c.hi == 22.75 && c.value == 15.5);
        eq("2 values hollow", b.hollow, true);
    }

    /** The months added together give the whole history: every day counted once, the weighted means give the mean of all days. */
    static void addUp() {
        Random r = new Random(5);
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        double sum = 0;
        int n = 0;
        LocalDate s = LocalDate.parse("2024-02-10");
        for (int i = 0; i < 800; i++) {
            if (r.nextInt(5) == 0) continue;
            double v = 300 + r.nextInt(200);
            d.add(day(s.plusDays(i).toString(), Trends.ASLEEP, v));
            sum += v;
            n++;
        }
        for (int p = 0; p < 3; p++) {
            Trends.View v = Trends.of(d, Trends.ASLEEP, p, LocalDate.parse("2027-01-01"));
            int cnt = 0;
            double ws = 0;
            for (Trends.Bucket b : v.buckets) {
                cnt += b.n;
                if (b.n > 0) ws += b.value * b.n;
            }
            eq("periods add up to every day (" + Trends.PERIOD_NAMES[p] + ")", cnt, n);
            near("weighted means give the all time mean (" + Trends.PERIOD_NAMES[p] + ")", ws / cnt, sum / n, 1e-9);
            eq("one faint dot per day (" + Trends.PERIOD_NAMES[p] + ")", v.dayValues.size(), n);
        }
    }

    /** Bedtimes from 23:30 to 00:30 give a one hour wide spread, a band of about half an hour around midnight, not the whole day. */
    static void midnight() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        for (int i = 0; i < 30; i++) {
            double clock = (1410 + i * 2) % 1440; // 23:30, 23:32, ... 00:28
            d.add(new Trends.Day(LocalDate.parse("2026-03-01").plusDays(i)).set(Trends.BEDTIME, clock, Trends.RING).set(Trends.WAKE, 420 + i, Trends.RING));
        }
        Trends.View v = Trends.of(d, Trends.BEDTIME, Trends.MONTHS, LocalDate.parse("2026-06-01"));
        Trends.Bucket b = v.buckets.get(0);
        check("across midnight: band under an hour (" + b.lo + " to " + b.hi + ")", b.hi - b.lo > 20 && b.hi - b.lo < 40);
        check("across midnight: the average is about midnight", Math.abs(Trends.around(b.value, 0)) < 2);
        check("across midnight: band holds the average", b.lo < b.value && b.value < b.hi);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v.dayValues) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        check("across midnight: the small dots lie within an hour of each other", hi - lo < 61);
        String ro = t(Trends.readout(v, 0, L));
        check("across midnight: readout in clock times (" + ro + ")", ro.startsWith("March 2026: asleep at 23:") || ro.startsWith("March 2026: asleep at 00:"));
        check("across midnight: middle half in clock times", ro.contains("middle half 23:4") && ro.contains(" to 00:1"));
        Trends.View w = Trends.of(d, Trends.WAKE, Trends.MONTHS, LocalDate.parse("2026-06-01"));
        eq("wake time", Trends.value(Trends.WAKE, w.buckets.get(0).value, L), "07:15");
        eq("clock beyond a day", Trends.clock(1440 + 75) + Trends.clock(-30), "01:1523:30");
    }

    /** Regularity is the spread of the bedtimes of a period (ClockAverage), with small dots for each night's distance. */
    static void spread() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        double[] bed = new double[10];
        for (int i = 0; i < 10; i++) {
            bed[i] = (1380 + i * 13) % 1440;
            d.add(day("2026-05-" + String.format("%02d", i + 1), Trends.BEDTIME, bed[i]));
        }
        d.add(day("2026-06-03", Trends.BEDTIME, 1400));
        Trends.View v = Trends.of(d, Trends.REGULARITY, Trends.MONTHS, LocalDate.parse("2026-07-01"));
        near("regularity is the clock spread", v.buckets.get(0).value, ClockAverage.of(bed)[1], 1e-9);
        check("regularity has no band", Double.isNaN(v.buckets.get(0).lo) && Double.isNaN(v.buckets.get(0).hi));
        eq("regularity: small dots only where there is an average", v.dayValues.size(), 10);
        eq("regularity readout", t(Trends.readout(v, 0, L)), "May 2026: bedtimes vary by " + Math.round(ClockAverage.of(bed)[1]) + " min, 10 nights");
        eq("regularity of one night", t(Trends.readout(v, 1, L)), "June 2026: too few nights for a spread, only 1 night");
    }

    /** The touch readout names the devices when more than one counted, and every value its unit. */
    static void readouts() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        for (int i = 1; i <= 29; i++) d.add(new Trends.Day(LocalDate.parse("2026-03-01").plusDays(i - 1)).set(Trends.ASLEEP, 400 + i, i <= 20 ? Trends.RING : Trends.WATCH));
        Trends.View v = Trends.of(d, Trends.ASLEEP, Trends.MONTHS, LocalDate.parse("2026-06-01"));
        eq("readout with devices", t(Trends.readout(v, 0, L)), "March 2026: 6h 55m asleep on average, middle half 6h 48m to 7h 2m, 29 nights: 20 ring, 9 watch");
        eq("tip", Trends.tip(v, 0, L), "March 2026\n6h 55m");
        String[] want = {"6h 55m", "23:20", "07:00", "34 min", "2.3", "78", "74", "54 bpm", "42 ms", "+0.15 °C", "8,412", "420 kcal", "2h 10m"};
        double[] vals = {415, 1400, 420, 34.2, 2.33, 78.2, 74, 54.4, 41.6, 0.149, 8412.3, 420.4, 130};
        for (int m = 0; m < Trends.COUNT; m++) eq("value " + Trends.NAMES[m], Trends.value(m, vals[m], L), want[m]);
        eq("temperature below the usual", Trends.value(Trends.TEMP, -0.2, L), "-0.20 °C");
        eq("temperature at the usual", Trends.value(Trends.TEMP, -0.001, L), "0.00 °C");
        eq("no value", Trends.value(Trends.STEPS, Double.NaN, L), "--");
        for (int m = 0; m < Trends.COUNT; m++) {
            List<Trends.Day> one = new ArrayList<Trends.Day>();
            for (int i = 0; i < 9; i++) one.add(new Trends.Day(LocalDate.parse("2026-02-01").plusDays(i)).set(m == Trends.REGULARITY ? Trends.BEDTIME : m, vals[m] + i, m == Trends.STEPS ? Trends.PHONE : Trends.RING));
            Trends.View mv = Trends.of(one, m, Trends.MONTHS, LocalDate.parse("2026-06-01"));
            String ro = t(Trends.readout(mv, 0, L));
            check("readout of " + Trends.NAMES[m] + " counts " + (Trends.night(m) ? "nights" : "days") + ": " + ro, ro.startsWith("February 2026: ")
                    && ro.endsWith(Trends.night(m) ? "9 nights" : "9 days"));
            for (int p = 0; p < 3; p++) t(Trends.note(m, p));
            t(Trends.explain(m, StageFilter.BALANCED));
        }
        eq("note of a month", Trends.note(Trends.ASLEEP, Trends.MONTHS), "Dot: the average of each month. Band: the middle half of its nights. Small dots: each night. Hollow: fewer than 7 nights. Touch to read.");
        eq("note of screen weeks", Trends.note(Trends.SCREEN, Trends.WEEKS), "Dot: the typical day (median) of each week. Band: the middle half of its days. Small dots: each day. Hollow: fewer than 2 days. Touch to read.");
        check("note of a year", Trends.note(Trends.STEPS, Trends.YEARS).contains("fewer than 60 days"));
        eq("group of each tab", "" + Trends.groupOfTab(0) + Trends.groupOfTab(1) + Trends.groupOfTab(2) + Trends.groupOfTab(3) + Trends.groupOfTab(4) + Trends.groupOfTab(5), "001234");
    }

    /** Today's steps and screen time are left out (the day is not over), the night that ended this morning counts. */
    static void today() {
        LocalDate today = LocalDate.parse("2026-05-20");
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        d.add(new Trends.Day(today.minusDays(1)).set(Trends.STEPS, 9000, Trends.RING).set(Trends.ASLEEP, 400, Trends.RING).set(Trends.SCREEN, 100, Trends.PHONE));
        d.add(new Trends.Day(today).set(Trends.STEPS, 1000, Trends.RING).set(Trends.ASLEEP, 420, Trends.RING).set(Trends.SCREEN, 20, Trends.PHONE));
        eq("today's steps left out", Trends.of(d, Trends.STEPS, Trends.MONTHS, today).buckets.get(0).n, 1);
        eq("today's screen time left out", Trends.of(d, Trends.SCREEN, Trends.MONTHS, today).buckets.get(0).n, 1);
        eq("this morning's night counts", Trends.of(d, Trends.ASLEEP, Trends.MONTHS, today).buckets.get(0).n, 2);
        check("a day after today's not finished", !Trends.of(d, Trends.STEPS, Trends.MONTHS, today.minusDays(1)).buckets.isEmpty() == false);
    }

    /** Wake ups say which detail level counted them; a mix of rules says so. */
    static void wakeUps() {
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        for (int i = 0; i < 4; i++) {
            Trends.Day x = day("2026-05-0" + (i + 1), Trends.WAKE_UPS, i);
            x.detail = StageFilter.BALANCED;
            d.add(x);
        }
        eq("one level", Trends.of(d, Trends.WAKE_UPS, Trends.MONTHS, LocalDate.parse("2026-06-01")).detail, StageFilter.BALANCED);
        Trends.Day calm = day("2026-05-09", Trends.WAKE_UPS, 1);
        calm.detail = StageFilter.CALM;
        d.add(calm);
        eq("a mix of rules", Trends.of(d, Trends.WAKE_UPS, Trends.MONTHS, LocalDate.parse("2026-06-01")).detail, -1);
        Trends.Day unknown = day("2026-05-10", Trends.WAKE_UPS, 1);
        List<Trends.Day> u = new ArrayList<Trends.Day>();
        u.add(unknown);
        eq("a night without a level counts as Detailed", Trends.of(u, Trends.WAKE_UPS, Trends.MONTHS, LocalDate.parse("2026-06-01")).detail, StageFilter.DETAILED);
        check("wake ups line in the explanation", Trends.explain(Trends.WAKE_UPS, -1).contains(WakeUps.averageLine(-1)));
    }

    /** Days of a month with {@code base} plus noise (each day of [from, to]). */
    static void month(List<Trends.Day> d, LocalDate from, LocalDate to, int m, double base, double noise, Random r) {
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) d.add(new Trends.Day(x).set(m, base + (r.nextDouble() * 2 - 1) * noise, Trends.RING));
    }

    /** This month against the last: a known difference is found with a range on one side, none gives "about the same". */
    static void compare() {
        LocalDate today = LocalDate.parse("2026-11-29");
        Random r = new Random(1);
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        month(d, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Trends.ASLEEP, 420, 20, r);
        month(d, LocalDate.parse("2026-11-01"), today, Trends.ASLEEP, 445, 20, r);
        Trends.Compare c = Trends.compare(d, Trends.ASLEEP, today, L);
        t(c.text);
        check("known difference found: " + c.text, c.enough && c.diff > 18 && c.diff < 32 && c.lo > 0 && c.hi > c.lo);
        check("known difference: range on one side, no 'about the same': " + c.text, c.text.startsWith("This month you sleep 2") && c.text.contains("min longer on average than in October (")
                && !c.text.contains("could be") && c.text.endsWith("min longer)."));
        check("today left out of this month", Math.abs(c.diff - Trends.compare(d.subList(0, d.size() - 1), Trends.ASLEEP, today, L).diff) < 1e-12);
        eq("the same history gives the same sentence (seeded)", Trends.compare(d, Trends.ASLEEP, today, L).text, c.text);
        List<Trends.Day> same = new ArrayList<Trends.Day>();
        Random r2 = new Random(2);
        month(same, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Trends.ASLEEP, 420, 60, r2);
        month(same, LocalDate.parse("2026-11-01"), today, Trends.ASLEEP, 420, 60, r2);
        Trends.Compare s = Trends.compare(same, Trends.ASLEEP, today, L);
        t(s.text);
        check("no difference: the range crosses zero: " + s.text, s.enough && s.lo < 0 && s.hi > 0 && s.text.contains("(could be ") && s.text.endsWith("), so about the same."));
        // an honest 90 % range: over 300 made up histories without a change, at most about one in ten shows one
        int alarms = 0, found = 0;
        for (int k = 0; k < 300; k++) {
            Random rk = new Random(1000 + k);
            List<Trends.Day> x = new ArrayList<Trends.Day>(), y = new ArrayList<Trends.Day>();
            month(x, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Trends.ASLEEP, 420, 60, rk);
            month(x, LocalDate.parse("2026-11-01"), today, Trends.ASLEEP, 420, 60, rk);
            month(y, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Trends.ASLEEP, 420, 60, rk);
            month(y, LocalDate.parse("2026-11-01"), today, Trends.ASLEEP, 450, 60, rk);
            Trends.Compare cx = Trends.compare(x, Trends.ASLEEP, today, L), cy = Trends.compare(y, Trends.ASLEEP, today, L);
            if (cx.lo > 0 || cx.hi < 0) alarms++;
            if (cy.lo > 0) found++;
        }
        System.out.println("  trends: no change shown as one in " + alarms + " of 300, a 30 min change found in " + found + " of 300");
        check("no change: at most 1 in 10 says there is one (" + alarms + " of 300)", alarms <= 30);
        check("a 30 min change in noisy nights: found in most months (" + found + " of 300)", found >= 150);
        // 7, 10 and 21 days this month
        for (int days : new int[] {7, 10, 21}) {
            LocalDate tday = LocalDate.parse("2026-11-01").plusDays(days);
            List<Trends.Day> part = new ArrayList<Trends.Day>();
            for (Trends.Day x : d) if (x.date.isBefore(tday)) part.add(x);
            Trends.Compare p = Trends.compare(part, Trends.ASLEEP, tday, L);
            if (days < 21) eq(days + " days this month: too few", p.text, Trends.TOO_FEW_NOW);
            else check("21 days this month: three whole weeks compare: " + p.text, p.enough && p.diff > 10);
        }
        eq("the first of the month: nothing this month yet", Trends.compare(d, Trends.ASLEEP, LocalDate.parse("2026-11-01"), L).text, Trends.TOO_FEW_NOW);
        // last month with too few days
        List<Trends.Day> few = new ArrayList<Trends.Day>();
        for (Trends.Day x : d) if (!x.date.isBefore(LocalDate.parse("2026-10-20"))) few.add(x);
        eq("last month too few", t(Trends.compare(few, Trends.ASLEEP, today, L).text), "Too few days in October to compare.");
        // three whole weeks but each with a single night: under 7 days
        List<Trends.Day> sparse = new ArrayList<Trends.Day>();
        for (Trends.Day x : d) if (x.date.getMonthValue() == 10 || x.date.getDayOfMonth() % 7 == 1) sparse.add(x);
        eq("whole weeks but under 7 days", Trends.compare(sparse, Trends.ASLEEP, today, L).text, Trends.TOO_FEW_NOW);
    }

    /** The other kinds: clock times, the spread, the median, a lower value and a difference that rounds to nothing. */
    static void compareKinds() {
        LocalDate today = LocalDate.parse("2026-11-25");
        Random r = new Random(3);
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        // bedtimes around midnight, this month 40 minutes later; wake times the same
        for (LocalDate x = LocalDate.parse("2026-10-01"); x.isBefore(today); x = x.plusDays(1)) {
            boolean now = x.getMonthValue() == 11;
            double bed = ((now ? 20 : 1420) + r.nextGaussian() * (now ? 40 : 10) + 1440) % 1440;
            Trends.Day y = new Trends.Day(x).set(Trends.BEDTIME, bed, Trends.RING).set(Trends.WAKE, 420 + r.nextGaussian() * 5, Trends.RING)
                    .set(Trends.SCREEN, (now ? 90 : 150) + r.nextInt(30), Trends.PHONE).set(Trends.RHR, 55, Trends.RING)
                    .set(Trends.WAKE_UPS, now ? 2 : 1, Trends.RING);
            for (int m : new int[] {Trends.SLEEP_SCORE, Trends.READINESS, Trends.HRV, Trends.TEMP, Trends.STEPS, Trends.KCAL}) {
                y.set(m, (now ? 1.1 : 1.0) * (m == Trends.TEMP ? 0.2 : 50) + r.nextGaussian() * (m == Trends.TEMP ? 0.01 : 1), Trends.RING);
            }
            d.add(y);
        }
        Trends.Compare bed = Trends.compare(d, Trends.BEDTIME, today, L);
        check("bedtime across midnight: about 40 min later, not hours: " + t(bed.text), bed.diff > 25 && bed.diff < 55 && bed.text.contains("later on average"));
        Trends.Compare reg = Trends.compare(d, Trends.REGULARITY, today, L);
        check("regularity: bedtimes vary more: " + t(reg.text), reg.diff > 15 && reg.text.startsWith("This month your bedtimes vary ") && reg.text.contains(" more than in October"));
        Trends.Compare scr = Trends.compare(d, Trends.SCREEN, today, L);
        check("screen time: shorter on a typical day: " + t(scr.text), scr.diff < -40 && scr.text.contains("shorter on a typical day than in October") && !scr.text.contains("could be"));
        Trends.Compare rhr = Trends.compare(d, Trends.RHR, today, L);
        eq("no difference at all", t(rhr.text), "This month your resting heart rate is about the same on average as in October (could be the same to the same), so about the same.");
        Trends.Compare wu = Trends.compare(d, Trends.WAKE_UPS, today, L);
        check("wake ups: " + t(wu.text), wu.text.startsWith("This month you have 1.0 wake ups a night more on average"));
        for (int m : new int[] {Trends.SLEEP_SCORE, Trends.READINESS, Trends.HRV, Trends.TEMP, Trends.STEPS, Trends.KCAL, Trends.WAKE}) {
            Trends.Compare c = Trends.compare(d, m, today, L);
            check(Trends.NAMES[m] + ": " + t(c.text), c.enough && c.text.startsWith("This month "));
        }
        eq("an hour or more in hours", Trends.amount(Trends.ASLEEP, -75, L), "1h 15m");
        eq("one point", Trends.amount(Trends.SLEEP_SCORE, 1.2, L), "1 point");
        eq("negative range on one side", t(Trends.sentence(Trends.ASLEEP, -30, -45, -12, "October", L)),
                "This month you sleep 30 min shorter on average than in October (12 min to 45 min shorter).");
        eq("range from the same", t(Trends.sentence(Trends.STEPS, 500, 0.2, 900, "October", L)),
                "This month you walk 500 steps a day more on average than in October (could be the same to 900 steps a day more), so about the same.");
    }

    static void enough() {
        LocalDate today = LocalDate.parse("2026-11-18");
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        for (int i = 0; i < 23; i++) d.add(day(today.minusDays(22 - i).toString(), Trends.STEPS, 1000));
        check("23 days: not enough", !Trends.enough(d, today));
        eq("23 days so far", Trends.daysSoFar(d, today), 23);
        eq("too short text", t(Trends.tooShort(23)), "Trends need at least two months. You have 23 days so far.");
        eq("one day", t(Trends.tooShort(1)), "Trends need at least two months. You have 1 day so far.");
        eq("no days", t(Trends.tooShort(0)), "Trends need at least two months. You have no days with data yet.");
        eq("nothing: 0 days", Trends.daysSoFar(new ArrayList<Trends.Day>(), today), 0);
        d.add(new Trends.Day(today.minusMonths(3)));
        check("a day without any value does not count", !Trends.enough(d, today));
        d.add(day(today.minusMonths(2).toString(), Trends.SLEEP_SCORE, 80));
        check("two calendar months: enough", Trends.enough(d, today));
        eq("days so far from the first value", Trends.daysSoFar(d, today), 62);
    }

    /** Three years of days, every measure and period grouped again, well under a second (a chip change). */
    static void speed() {
        Random r = new Random(9);
        List<Trends.Day> d = new ArrayList<Trends.Day>();
        LocalDate s = LocalDate.parse("2023-11-18");
        for (int i = 0; i < 3 * 365; i++) {
            Trends.Day x = new Trends.Day(s.plusDays(i));
            for (int m = 0; m < Trends.COUNT; m++) x.set(m, 100 + r.nextInt(300), Trends.RING);
            d.add(x);
        }
        long t0 = System.nanoTime();
        int buckets = 0;
        for (int m = 0; m < Trends.COUNT; m++) for (int p = 0; p < 3; p++) buckets += Trends.of(d, m, p, LocalDate.parse("2026-11-18")).buckets.size();
        for (int m = 0; m < Trends.COUNT; m++) Trends.compare(d, m, LocalDate.parse("2026-11-18"), L);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("  trends: every measure and period of 3 years grouped and compared in %.0f ms (%d periods)%n", ms, buckets);
        check("3 years regrouped quickly (" + Math.round(ms) + " ms)", ms < 3000);
    }

    /** Every text: plain, no dashes as punctuation. */
    static void plain() {
        for (String n : Trends.NAMES) TEXTS.add(n);
        for (String n : Trends.GROUP_NAMES) TEXTS.add(n);
        for (String n : Trends.PERIOD_NAMES) TEXTS.add(n);
        TEXTS.add(Trends.TOO_FEW_NOW);
        boolean ok = true;
        for (String x : TEXTS) {
            if (x.contains(" - ") || x.contains("–") || x.contains("—") || x.contains(" -") && !x.contains(" -0")) {
                ok = false;
                System.out.println("  dash in: " + x);
            }
        }
        check("trends texts use no dashes as punctuation (" + TEXTS.size() + " texts)", ok);
    }
}
