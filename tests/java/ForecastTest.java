import app.openring.core.DripCsv;
import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.RiseBacktest;
import app.openring.core.analysis.cycleforecast.Backtest;
import app.openring.core.analysis.cycleforecast.Forecast;
import app.openring.core.analysis.cycleforecast.Method;
import app.openring.core.analysis.cycleforecast.Methods;

import java.time.LocalDate;
import java.util.*;

/**
 * Issue #383: the period prediction methods ({@code core/analysis/cycleforecast}), the walk forward backtest that never
 * sees a later cycle, the temperature replay ({@code RiseBacktest}), the method choice in {@code Cycles}, the demo's 3
 * years and the texts. Made up cycle histories only. Run with tests/run_tests.sh.
 */
public class ForecastTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  detail: " + detail);
        check(name, ok);
    }

    static final LocalDate C0 = LocalDate.of(2023, 1, 9);

    /** A log with a 5 day period at C0 and after each length. */
    static TreeMap<LocalDate, Integer> log(int... lengths) {
        TreeMap<LocalDate, Integer> m = new TreeMap<LocalDate, Integer>();
        LocalDate s = C0;
        for (int i = 0; i <= lengths.length; i++) {
            for (int k = 0; k < 5; k++) m.put(s.plusDays(k), k < 2 ? Cycles.HEAVY : Cycles.MEDIUM);
            if (i < lengths.length) s = s.plusDays(lengths[i]);
        }
        return m;
    }

    static LocalDate lastStart(int... lengths) {
        LocalDate s = C0;
        for (int l : lengths) s = s.plusDays(l);
        return s;
    }

    static int[] rep(int v, int n) {
        int[] a = new int[n];
        Arrays.fill(a, v);
        return a;
    }

    static int[] cat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] out = new int[n];
        int k = 0;
        for (int[] p : parts) for (int v : p) out[k++] = v;
        return out;
    }

    /** {@code n} cycles around {@code mean} with spread {@code sd}, from a fixed seed. */
    static int[] random(int n, double mean, double sd, long seed) {
        Random r = new Random(seed);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = (int) Math.max(18, Math.round(mean + sd * r.nextGaussian()));
        return a;
    }

    static Forecast f(int id, int... lengths) {
        return Methods.get(id).predict(lengths);
    }

    public static void main(String[] args) {
        openRingUnchanged();
        methods();
        bayes();
        backtest();
        neverFuture();
        choice();
        temperature();
        demo();
        texts();
        review();
        System.out.println("forecast: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Open Ring as a method gives exactly the estimate Cycles gave before #383, on many histories. */
    static void openRingUnchanged() {
        List<int[]> hs = new ArrayList<int[]>();
        hs.add(new int[0]);
        hs.add(new int[] {31});
        hs.add(new int[] {27, 33});
        hs.add(new int[] {28, 29, 30});
        hs.add(new int[] {28, 58, 29, 27});
        hs.add(new int[] {20, 60});
        hs.add(rep(28, 15));
        hs.add(cat(random(30, 29, 2.5, 1), new int[] {57}, random(5, 29, 2.5, 2)));
        for (int s = 3; s < 40; s++) hs.add(random(s, 28 + s % 4, 1 + s % 5, s));
        int same = 0, total = 0;
        for (int[] h : hs) {
            LocalDate today = lastStart(h).plusDays(9);
            Cycles cy = Cycles.of(log(h), null, today);
            // the formulas of #252, written out again here
            int u = cy.used.size();
            int length = (int) Math.round(cy.typical);
            int range = u < Cycles.FEW ? Cycles.WIDE : Math.max(1, (int) Math.round(Cycles.Z80 * cy.sd));
            double s = u < Cycles.FEW ? Cycles.PERSON_SD : cy.sd;
            int ovRange = Math.max(Cycles.OVULATION_MIN, (int) Math.round(Cycles.Z80 * Math.sqrt(s * s + Cycles.LUTEAL_SD * Cycles.LUTEAL_SD)));
            boolean ok = cy.next.equals(lastStart(h).plusDays(length)) && cy.range == range && cy.ovulationRange == ovRange
                    && cy.step == cy.length && cy.methodId == Methods.OPEN_RING && cy.choice == Methods.OPEN_RING;
            Cycles w = cy.withMethod(Methods.OPEN_RING);
            ok &= w.next.equals(cy.next) && w.range == cy.range && w.ovulation.equals(cy.ovulation) && w.ovulationRange == cy.ovulationRange
                    && w.rangeExact == cy.rangeExact;
            Forecast fo = f(Methods.OPEN_RING, h);
            ok &= Math.abs(fo.mean - cy.typical) < 1e-12 && fo.shown == cy.calendarRange;
            if (ok) same++;
            else System.out.println("  detail: " + Arrays.toString(h) + " " + cy.next + " +-" + cy.range + " " + fo);
            total++;
        }
        check("#383 Open Ring as a method: the same next start, range, ovulation and its range as before on " + total + " histories", same == total, same + " of " + total);
    }

    static void methods() {
        // regular: every method expects 28
        int[] reg = rep(28, 12);
        for (int id : new int[] {Methods.OPEN_RING, Methods.DRIP, Methods.SMOOTH, Methods.TREND}) {
            check("#383 regular 28 days: " + Methods.name(id) + " expects 28 exactly", Math.abs(f(id, reg).mean - 28) < 1e-9 && f(id, reg).days() == 28, f(id, reg));
        }
        check("#383 regular: drip style give or take 1 day (spread under 1.5), Open Ring at least 1", f(Methods.DRIP, reg).shown == 1 && f(Methods.OPEN_RING, reg).shown == 1);
        check("#383 drip style: give or take 2 days from a spread of 1.5 on", f(Methods.DRIP, 26, 30, 28, 31, 25, 29).shown == 2);
        check("#383 drip style: its mean of the newest 12 with nothing left out (a 58 day cycle pulls it)",
                Math.abs(f(Methods.DRIP, cat(rep(28, 11), new int[] {58})).mean - (28 * 11 + 58) / 12.0) < 1e-9
                        && f(Methods.OPEN_RING, cat(rep(28, 11), new int[] {58})).days() == 28);
        check("#383 drip style under 3 cycles: Open Ring's rule, marked as such", f(Methods.DRIP, 30, 26).fallback && f(Methods.DRIP, 30, 26).shown == Cycles.WIDE
                && f(Methods.DRIP, 30, 26).method == Methods.DRIP);
        check("#383 no cycle at all: each method expects 29 days give or take 5 (Bayes from the study)", f(Methods.OPEN_RING).days() == 29 && f(Methods.SMOOTH).days() == 29
                && f(Methods.TREND).days() == 29 && f(Methods.DRIP).shown == 5 && f(Methods.BAYES).days() == 29);

        // a trend: 34 down to 26 days; smoothing follows it, the plain average lags
        int[] trend = new int[33];
        for (int i = 0; i < trend.length; i++) trend[i] = 34 - i / 4;
        Forecast sm = f(Methods.SMOOTH, trend), or = f(Methods.OPEN_RING, trend);
        check("#383 a trend: recent cycles count more expects close to the newest (26), Open Ring's 12 more", sm.days() <= 27 && or.mean > sm.mean, sm + " / " + or);
        Backtest tb = Backtest.run(trend);
        check("#383 a trend: in the backtest smoothing is closer than drip style and Open Ring", tb.of(Methods.SMOOTH).mae < tb.of(Methods.OPEN_RING).mae
                && tb.of(Methods.SMOOTH).mae < tb.of(Methods.DRIP).mae, tb.of(Methods.SMOOTH) + " / " + tb.of(Methods.OPEN_RING));

        // alternating long and short: the autoregressive model expects a short one after a long one
        int[] alt = new int[24];
        for (int i = 0; i < alt.length; i++) alt[i] = i % 2 == 0 ? 25 : 32;
        Forecast tr = f(Methods.TREND, alt); // last is 32
        check("#383 alternating: follows your last cycle expects a short one after a long one", tr.days() <= 26, tr);
        Backtest ab = Backtest.run(alt);
        check("#383 alternating: it wins the backtest by days, and Best for you picks it", ab.of(Methods.TREND).mae + 2 < ab.of(Methods.OPEN_RING).mae && ab.best() == Methods.TREND,
                ab.of(Methods.TREND) + " / " + ab.of(Methods.OPEN_RING));
        // no link: the autoregressive model is the plain mean (shrunk to none)
        int[] iid = random(40, 29, 2.5, 5);
        Forecast ti = f(Methods.TREND, iid);
        double mean = 0;
        int[] kept = Methods.kept(iid);
        for (int v : kept) mean += v;
        mean /= kept.length;
        check("#383 no link between cycles: follows your last cycle stays within half a day of the mean", Math.abs(ti.mean - mean) < 0.5, ti + " mean " + mean);
        check("#383 follows your last cycle needs 6 cycles, before that Open Ring's rule", f(Methods.TREND, 28, 30, 29, 27, 31).fallback && !f(Methods.TREND, 28, 30, 29, 27, 31, 28).fallback);
        check("#383 smoothing needs 5 cycles", f(Methods.SMOOTH, 28, 30, 29, 27).fallback && !f(Methods.SMOOTH, 28, 30, 29, 27, 31).fallback);
        check("#383 every range is at least 1 day and the spread a number, also for steady cycles", f(Methods.SMOOTH, reg).shown == 1 && f(Methods.TREND, reg).shown == 1
                && !Double.isNaN(f(Methods.TREND, reg).spread) && f(Methods.BAYES, reg).shown >= 1);
        check("#383 an unknown id is Open Ring; valid ids are Settings' choices", Methods.get(42).id() == Methods.OPEN_RING && !Methods.valid(42) && Methods.valid(Methods.BEST)
                && Methods.CHOICES.length == Methods.ALL.length + 1);
    }

    static void bayes() {
        Forecast none = f(Methods.BAYES), one = f(Methods.BAYES, 35), many = f(Methods.BAYES, rep(35, 20));
        check("#383 Bayes: from the study (29.3) without a cycle, most of the way to the first own cycle after one", Math.abs(none.mean - 29.3) < 1e-9
                && one.mean > 33 && one.mean < 35, none + " / " + one);
        check("#383 Bayes: the range narrows as cycles come in", none.range > one.range && one.range > many.range && many.shown <= 2, none + " / " + one + " / " + many);
        // a period that was not logged: 56 days among 28s counts as two cycles
        int[] skip = cat(rep(28, 10), new int[] {56}, rep(28, 3));
        Forecast fs = f(Methods.BAYES, skip);
        check("#383 Bayes: a 56 day cycle among 28s is two cycles (a missed log), the mean stays 28", fs.days() == 28 && Math.abs(fs.mean - 28) < 0.15, fs);
        check("#383 Bayes: and its range stays narrow", fs.shown <= 2, fs);
        // an irregular person's long cycle stays one cycle
        int[] irr = {24, 33, 27, 36, 22, 31, 29, 38, 25, 30, 40, 27};
        Forecast fi = f(Methods.BAYES, irr);
        double m = 0;
        for (int v : irr) m += v;
        m /= irr.length;
        check("#383 Bayes: an irregular person's 40 day cycle is not taken for two", Math.abs(fi.mean - m) < 1.0, fi + " mean " + m);
        // one far short cycle (a bleed logged as a period) is pulled to the edge, not taken as it is
        int[] odd = cat(rep(29, 12), new int[] {9}, rep(29, 2));
        check("#383 Bayes: a 9 day cycle among 29s moves the mean by less than half a day", Math.abs(f(Methods.BAYES, odd).mean - 29) < 0.5, f(Methods.BAYES, odd));
        // 40 cycles: close to the person's own numbers
        int[] forty = random(40, 31, 2, 9);
        double fm = 0;
        for (int v : forty) fm += v;
        fm /= forty.length;
        check("#383 Bayes with 40 cycles: within 0.2 days of her average", Math.abs(f(Methods.BAYES, forty).mean - fm) < 0.2, f(Methods.BAYES, forty) + " " + fm);
    }

    static void backtest() {
        int[] h = cat(random(20, 29, 2.5, 3), new int[] {58}, random(15, 29, 2.5, 4));
        Backtest bt = Backtest.run(h);
        check("#383 backtest: every method scored on the same cycles, from the 4th on, the 58 day one left out", bt.scores.length == Methods.ALL.length
                && bt.scored() == h.length - Backtest.FROM - 1 && bt.farOut == 1 && bt.cycles == h.length, bt.scored() + " " + bt.farOut);
        boolean same = true;
        for (Backtest.Score s : bt.scores) same &= s.n == bt.scored() && Arrays.equals(s.index, bt.scores[0].index) && Arrays.equals(s.actual, bt.scores[0].actual);
        check("#383 backtest: the same cycles for every method", same);
        Backtest.Score o = bt.of(Methods.OPEN_RING);
        double e = 0;
        int hits = 0;
        for (int i = 0; i < o.n; i++) {
            e += Math.abs(o.late(i));
            if (Math.abs(o.late(i)) <= o.range[i]) hits++;
        }
        check("#383 backtest: the mean error and the hits count each scored cycle", Math.abs(o.mae - e / o.n) < 1e-12 && o.hits == hits && o.hitRate() == (double) hits / o.n);
        int best = bt.best();
        boolean lowest = true;
        for (Backtest.Score s : bt.scores) lowest &= bt.of(best).mae <= s.mae + 1e-12;
        check("#383 Best for you: the smallest mean error", lowest, Methods.name(best));
        check("#383 Best for you: a tie goes to Open Ring (all 0 days off on steady cycles)", Backtest.run(rep(28, 10)).best() == Methods.OPEN_RING
                && Backtest.run(rep(28, 10)).of(Methods.BAYES).mae == Backtest.run(rep(28, 10)).of(Methods.OPEN_RING).mae);
        check("#383 Best for you: Open Ring while fewer than 3 cycles can be checked", Backtest.run(new int[] {25, 33, 25, 33, 25}).scored() == 2
                && Backtest.run(new int[] {25, 33, 25, 33, 25}).best() == Methods.OPEN_RING && Backtest.run(new int[0]).best() == Methods.OPEN_RING);
        check("#383 backtest without cycles: nothing scored, NaN errors", Backtest.run(new int[] {28, 29}).scored() == 0 && Double.isNaN(Backtest.run(new int[] {28}).scores[0].mae));
    }

    /** A method that remembers what it was given. */
    static final class Spy implements Method {
        final int[] all;
        final List<Integer> sizes = new ArrayList<Integer>();
        boolean prefix = true;

        Spy(int[] all) {
            this.all = all;
        }

        @Override
        public int id() {
            return 99;
        }

        @Override
        public Forecast predict(int[] lengths) {
            sizes.add(lengths.length);
            prefix &= Arrays.equals(lengths, Arrays.copyOf(all, lengths.length));
            return Methods.get(Methods.OPEN_RING).predict(lengths);
        }
    }

    /** The backtest never shows a method the cycle it predicts or anything after it. */
    static void neverFuture() {
        int[] h = cat(random(25, 30, 3, 11), new int[] {61}, random(14, 30, 3, 12));
        Spy spy = new Spy(h);
        Backtest bt = Backtest.run(h, new Method[] {spy});
        boolean before = spy.sizes.size() == bt.scored();
        for (int j = 0; j < bt.scored(); j++) before &= spy.sizes.get(j) == bt.scores[0].index[j];
        check("#383 never the future: for each scored cycle the method got exactly the cycles before it, in order", before && spy.prefix, spy.sizes);
        // changing a later cycle never changes an earlier prediction, and a cut history gives the same first predictions
        for (int cut = 6; cut < h.length; cut += 5) {
            int[] changed = h.clone();
            for (int i = cut; i < changed.length; i++) changed[i] = 45 - i % 7;
            Backtest a = Backtest.run(h), b = Backtest.run(changed), c = Backtest.run(Arrays.copyOf(h, cut));
            boolean ok = true;
            for (int k = 0; k < a.scores.length; k++) {
                for (int j = 0; j < c.scores[k].n; j++) {
                    ok &= a.scores[k].index[j] == c.scores[k].index[j] && a.scores[k].expected[j] == c.scores[k].expected[j]
                            && a.scores[k].range[j] == c.scores[k].range[j] && b.scores[k].expected[j] == a.scores[k].expected[j];
                }
            }
            check("#383 never the future: the predictions before cycle " + cut + " are the same whatever comes after", ok);
        }
    }

    static void choice() {
        int[] alt = new int[16];
        for (int i = 0; i < alt.length; i++) alt[i] = i % 2 == 0 ? 25 : 32;
        LocalDate today = lastStart(alt).plusDays(8);
        Cycles base = Cycles.of(log(alt), null, today);
        Cycles best = base.withMethod(Methods.BEST);
        check("#383 Best for you: the method the backtest picked, its length for the next start", best.choice == Methods.BEST && best.methodId == best.backtest().best()
                && best.methodId == Methods.TREND && best.calendarNext.equals(lastStart(alt).plusDays(best.step)) && best.step == best.forecast.days(), best.forecast);
        check("#383 the choice changes the estimate only: the same periods, cycles and descriptive numbers", best.periods.toString().equals(base.periods.toString())
                && best.cycles.toString().equals(base.cycles.toString()) && best.typical == base.typical && best.sd == base.sd && best.length == base.length
                && best.waiting == base.waiting);
        check("#383 Best for you here expects a short cycle after the long one, Open Ring the typical", best.step <= 26 && base.step == base.length && base.step >= 28, best.step + " / " + base.step);
        List<Cycles.Ahead> a = best.ahead(today.plusDays(200));
        check("#383 the months ahead count with the method's length", a.size() > 3 && a.get(2).start.equals((best.next.isBefore(today) ? today : best.next).plusDays(2L * best.step)), a);
        check("#383 an unknown choice (a newer backup) is Open Ring", base.withMethod(77).methodId == Methods.OPEN_RING && base.withMethod(77).choice == Methods.OPEN_RING);
        Cycles drip = base.withMethod(Methods.DRIP);
        check("#383 drip style: give or take 2 here, ovulation still at least 3 days", drip.range == 2 && drip.ovulationRange >= Cycles.OVULATION_MIN && drip.methodId == Methods.DRIP);
        Cycles few = Cycles.of(log(30), null, lastStart(30).plusDays(3)).withMethod(Methods.BEST);
        check("#383 Best for you with one cycle: Open Ring, give or take 5", few.methodId == Methods.OPEN_RING && few.range == Cycles.WIDE && few.next.equals(lastStart(30).plusDays(30)));
        check("#383 the touch text names the method when it is not Open Ring", best.describe(best.next, Locale.ENGLISH).contains("(Follows your last cycle)"),
                best.describe(best.next, Locale.ENGLISH));
        Cycles none = Cycles.of(new TreeMap<LocalDate, Integer>(), null, today).withMethod(Methods.BEST);
        check("#383 an empty log: no estimate, nothing breaks", none.next == null && none.backtest().scored() == 0 && none.riseBacktest() == null);
    }

    /** Nights with a rise of 0.3 °C from the day after {@code ovulation} of each cycle (days before the next start) on. */
    static TreeMap<LocalDate, Double> nights(int[] lengths, int[] lutealDays, LocalDate to, long seed) {
        Random r = new Random(seed);
        TreeMap<LocalDate, Double> out = new TreeMap<LocalDate, Double>();
        LocalDate s = C0;
        List<LocalDate> warm = new ArrayList<LocalDate>();
        for (int i = 0; i < lengths.length; i++) {
            LocalDate next = s.plusDays(lengths[i]);
            LocalDate ov = next.minusDays(lutealDays[i]);
            for (LocalDate d = ov.plusDays(1); d.isBefore(next); d = d.plusDays(1)) warm.add(d);
            s = next;
        }
        for (LocalDate d = C0; !d.isAfter(to); d = d.plusDays(1)) out.put(d, 36.3 + 0.05 * r.nextGaussian() + (warm.contains(d) ? 0.3 : 0));
        return out;
    }

    static void temperature() {
        // cycles that vary, a steady 13 days from ovulation to the period: the temperature is closer than the calendar
        int[] h = random(14, 29, 3, 21);
        int[] lut = rep(13, h.length);
        LocalDate today = lastStart(h).plusDays(6);
        TreeMap<LocalDate, Double> ring = nights(h, lut, today, 1);
        Cycles cy = Cycles.of(log(h), null, today).withMethod(Methods.BEST).measured(ring, null);
        RiseBacktest rb = cy.riseBacktest();
        check("#383 temperature replay: most cycles with a rise replayed, each on a day before the period", rb.n() >= h.length - 4, rb.cases);
        boolean before = true;
        for (RiseBacktest.Case c : rb.cases) before &= c.day.isBefore(c.next) && c.day.isAfter(c.start);
        check("#383 temperature replay: the day it showed lies inside its cycle", before);
        check("#383 temperature replay: a steady luteal phase beats a calendar of varying cycles", rb.mae < rb.calendarMae && cy.riseUsed && rb.helps(), rb.mae + " / " + rb.calendarMae);
        // never the future: nights after each replay day and later periods change nothing about that case
        RiseBacktest.Case c0 = rb.cases.get(2);
        TreeMap<LocalDate, Double> cut = new TreeMap<LocalDate, Double>(ring.headMap(c0.day, true));
        for (LocalDate d = c0.day.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) cut.put(d, 37.5); // nonsense after that day
        int[] other = h.clone();
        for (int i = 0; i < other.length; i++) if (lastStart(Arrays.copyOf(h, i)).isAfter(c0.next)) other[i] = 40;
        Cycles cy2 = Cycles.of(log(other), null, today).withMethod(Methods.OPEN_RING).measured(cut, null);
        RiseBacktest rb2 = cy2.riseBacktest();
        RiseBacktest.Case c2 = null;
        for (RiseBacktest.Case c : rb2.cases) if (c.start.equals(c0.start)) c2 = c;
        Cycles cyOpen = Cycles.of(log(h), null, today).withMethod(Methods.OPEN_RING).measured(ring, null);
        RiseBacktest.Case c1 = null;
        for (RiseBacktest.Case c : cyOpen.riseBacktest().cases) if (c.start.equals(c0.start)) c1 = c;
        check("#383 temperature replay never the future: other nights after the day and other later cycles, the same case", c1 != null && c2 != null
                && c1.day.equals(c2.day) && c1.measured.equals(c2.measured) && c1.calendar.equals(c2.calendar) && c1.range == c2.range, c1 + " / " + c2);

        // steady cycles, a luteal phase all over the place: the calendar is closer, Best for you leaves the temperature out
        int[] steady = rep(28, 14);
        int[] wild = {9, 17, 10, 16, 9, 17, 10, 16, 9, 17, 10, 16, 9, 17};
        LocalDate t2 = lastStart(steady).plusDays(22);
        TreeMap<LocalDate, Double> wr = nights(steady, wild, t2, 2);
        Cycles bad = Cycles.of(log(steady), null, t2).withMethod(Methods.BEST).measured(wr, null);
        RiseBacktest br = bad.riseBacktest();
        check("#383 a luteal phase that varies a lot: further off than the calendar, Best for you does not use the temperature", br.n() >= RiseBacktest.MIN
                && br.mae > br.calendarMae && !bad.riseUsed && bad.method != Cycles.MEASURED, br.mae + " / " + br.calendarMae + " " + br.cases);
        check("#383 and says why", bad.riseOff().startsWith("Your temperature is not used: on ") && bad.ovulationLine(Locale.ENGLISH).contains("Your temperature is not used"),
                bad.ovulationLine(Locale.ENGLISH));
        Cycles chosen = Cycles.of(log(steady), null, t2).withMethod(Methods.OPEN_RING).measured(wr, null);
        check("#383 a chosen method keeps the temperature (only Best for you decides by the backtest)", chosen.riseUsed);
        Cycles drip = Cycles.of(log(h), null, today).withMethod(Methods.DRIP).measured(ring, null);
        check("#383 drip style never uses the temperature, its replay sits on Open Ring", !drip.riseUsed && drip.method == Cycles.CALENDAR
                && drip.riseBacktest().method == Methods.OPEN_RING && drip.riseOff().startsWith("drip style uses start days only"));
        check("#383 without temperatures there is no replay", Cycles.of(log(h), null, today).withMethod(Methods.BEST).riseBacktest() == null
                && Cycles.of(log(h), null, today).withMethod(Methods.BEST).measured(null, null).riseBacktest().n() == 0);
        Cycles kept = Cycles.of(log(h), null, today).withMethod(Methods.BAYES).measured(ring, null);
        check("#383 measured keeps the chosen method", kept.choice == Methods.BAYES && kept.methodId == Methods.BAYES);
    }

    static void demo() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        TreeMap<LocalDate, Integer> all = Cycles.demoHistory(today), recent = Cycles.demo(today);
        check("#383 demo: the newest periods are the demo's of #252 day for day", all.tailMap(recent.firstKey(), true).equals(recent));
        Cycles cy = Cycles.of(all, null, today).withMethod(Methods.BEST);
        check("#383 demo: about 3 years, 37 cycles, today on cycle day 20", cy.cycles.size() == 37 && cy.cycleDay(today) == Cycles.DEMO_DAY
                && cy.periods.get(0).start.isBefore(today.minusYears(3).plusDays(30)), cy.periods.get(0));
        Backtest bt = cy.backtest();
        check("#383 demo: 33 periods checked, the missed one (57 days) not counted", bt.scored() == 33 && bt.farOut == 1, bt.scored() + " " + bt.farOut);
        StringBuilder sb = new StringBuilder();
        for (Backtest.Score s : bt.scores) sb.append(String.format(Locale.ROOT, "%s %.2f days off, %d of %d in range; ", Methods.name(s.method), s.mae, s.hits, s.n));
        System.out.println("    #383 demo backtest: " + sb);
        check("#383 demo: Best for you picks Learns from every cycle (closest on the made up years), not Open Ring", cy.methodId == Methods.BAYES
                && bt.of(Methods.BAYES).mae < bt.of(Methods.OPEN_RING).mae, sb);
        check("#383 demo: drip style is furthest off and hits its narrow range least", bt.of(Methods.DRIP).mae >= bt.of(Methods.OPEN_RING).mae
                && bt.of(Methods.DRIP).hits < bt.of(Methods.OPEN_RING).hits, sb);
        TreeMap<LocalDate, Double> mouth = new TreeMap<LocalDate, Double>();
        for (Map.Entry<LocalDate, DripCsv.Temp> e : DripCsv.demoMouth(today, Cycles.demoWarmNights(today)).entrySet()) if (!e.getValue().excluded) mouth.put(e.getKey(), e.getValue().c);
        Cycles m = cy.measured(null, mouth);
        System.out.println("    #383 demo temperature: " + m.riseBacktest().cases);
        check("#383 demo: the made up mouth temperatures give replayed cycles, the temperature used", m.riseBacktest().n() >= 2 && m.riseUsed && m.method == Cycles.MEASURED,
                m.riseBacktest().cases);
        // a long history with a temperature every night is still worked out in a moment
        TreeMap<LocalDate, Double> every = new TreeMap<LocalDate, Double>();
        Random r = new Random(5);
        for (LocalDate d = all.firstKey(); !d.isAfter(today); d = d.plusDays(1)) every.put(d, 36.4 + 0.08 * r.nextGaussian());
        long t0 = System.nanoTime();
        Cycles big = cy.measured(every, null);
        big.riseBacktest();
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("    #383 3 years with a flat temperature every night: " + ms + " ms");
        check("#383 3 years of nights: worked out in under 2 s on the JVM", ms < 2000, ms);
    }

    static void texts() {
        List<String> all = new ArrayList<String>();
        for (int id : Methods.CHOICES) {
            all.add(Methods.name(id));
            all.add(Methods.line(id));
            all.add(Methods.how(id));
            all.add(Methods.rangeWords(id));
        }
        all.addAll(app.openring.ui.Explain.periodAccuracyHow());
        all.add(app.openring.ui.Explain.PERIOD_ACC_ROWS);
        all.add(app.openring.ui.Explain.PERIOD_ACC_DOTS);
        all.add(app.openring.ui.Explain.PERIOD_METHOD_SETTINGS);
        LocalDate today = LocalDate.of(2026, 11, 12);
        Cycles cy = Cycles.of(Cycles.demoHistory(today), null, today).withMethod(Methods.BEST);
        all.add(app.openring.ui.Explain.periodBestNow(cy));
        all.add(app.openring.ui.Explain.periodRange(cy));
        all.addAll(app.openring.ui.Explain.periodCardHow(cy));
        boolean dashes = false;
        for (String s : all) if (s.contains(" - ") || s.contains(" – ") || s.contains("—")) {
            dashes = true;
            System.out.println("  dash in: " + s);
        }
        check("#383 texts: no dashes as punctuation", !dashes);
        String now = app.openring.ui.Explain.periodBestNow(cy);
        check("#383 Settings names the method Best for you uses, its error and Open Ring's", now.startsWith("Best for you uses Learns from every cycle now: ")
                && now.contains("days off on average over 33 of your past periods, Open Ring "), now);
        String range = app.openring.ui.Explain.periodRange(cy);
        check("#383 How it works on the card: the method, its range and how it did", range.startsWith("Next period: your last start plus " + cy.step + " days from Learns from every cycle (picked for you).")
                && range.contains("On 33 of your past periods it was "), range);
        Cycles few = Cycles.of(log(30, 28), null, lastStart(30, 28).plusDays(2)).withMethod(Methods.BEST);
        check("#383 Settings before 3 periods can be checked", app.openring.ui.Explain.periodBestNow(few).startsWith("Best for you uses Open Ring until 3 of your periods"),
                app.openring.ui.Explain.periodBestNow(few));
        check("#383 How it works lists every method once", app.openring.ui.Explain.periodAccuracyHow().size() == Methods.ALL.length + 5);
        check("#383 Settings' names", Methods.name(Methods.BEST).equals("Best for you") && Methods.name(Methods.DRIP).equals("drip style")
                && Methods.name(Methods.BAYES).equals("Learns from every cycle"));
    }

    /** The code review of dev dadbb71: long cycles in Bayes, drip style's ranges, cycle days ahead, the outlier rule. */
    static void review() {
        Locale en = Locale.ENGLISH;
        // 1. Bayes: steady long cycles are one cycle each, never split into halves by the study's 29.3 day start
        for (int len : new int[] {45, 48, 49, 50, 60}) {
            Forecast b = f(Methods.BAYES, rep(len, 12));
            check("#383 review: Bayes with twelve " + len + " day cycles expects about " + len, Math.abs(b.mean - len) < 1.0, b);
        }
        int[] longFirst = {50, 39, 41, 40, 42, 40, 41, 39, 40, 42};
        Forecast lf = f(Methods.BAYES, longFirst);
        check("#383 review: Bayes with a 50 day first cycle, then about 40: expects about 40", lf.mean > 39.5 && lf.mean < 42, lf);
        Forecast early = f(Methods.BAYES, cat(rep(28, 3), new int[] {56}, rep(28, 6)));
        check("#383 review: Bayes still takes a 56 day cycle after three 28s as a missed log", Math.abs(early.mean - 28) < 0.3 && early.shown <= 2, early);
        Forecast mid = f(Methods.BAYES, cat(rep(28, 6), new int[] {57}, rep(28, 2)));
        check("#383 review: and a 57 day one among 28s", Math.abs(mid.mean - 28) < 0.3, mid);
        int[] fifty = rep(50, 12);
        Cycles cb = Cycles.of(log(fifty), null, lastStart(fifty).plusDays(30)).withMethod(Methods.BAYES);
        check("#383 review: in the app on cycle day 31 of 50 day cycles, not late, next period about 50 days after the last",
                cb.lateBy() == 0 && cb.step >= 49 && cb.next.equals(lastStart(fifty).plusDays(cb.step)) && cb.nextLine().startsWith("Next period in about "), cb.nextLine() + " " + cb.next);
        List<Cycles.Ahead> fa = cb.ahead(cb.today.plusDays(365));
        check("#383 review: the year ahead runs at about 50 days", fa.size() > 3 && fa.get(1).start.equals(fa.get(0).start.plusDays(cb.step)) && cb.step >= 49, fa);

        // 2. drip style: the ovulation range follows its own period range, and later cycles are never narrower
        int[] miss = cat(rep(28, 11), new int[] {56});
        Cycles dr = Cycles.of(log(miss), null, lastStart(miss).plusDays(3)).withMethod(Methods.DRIP);
        double lut = Cycles.Z80 * Cycles.LUTEAL_SD;
        int expectOv = Math.max(Cycles.OVULATION_MIN, (int) Math.round(Math.sqrt(dr.rangeExact * dr.rangeExact + lut * lut)));
        check("#383 review: drip style's ovulation range comes from its own period range (" + dr.range + " days), not from a spread with the 56 day cycle",
                dr.range == 2 && dr.ovulationRange == expectOv && dr.ovulationRange <= 4, dr.range + " / " + dr.ovulationRange);
        boolean grows = true;
        for (Cycles cy : new Cycles[] {dr, Cycles.of(log(miss), null, lastStart(miss).plusDays(3)).withMethod(Methods.BAYES),
                Cycles.of(log(random(20, 29, 3, 7)), null, lastStart(random(20, 29, 3, 7)).plusDays(4)).withMethod(Methods.BEST)}) {
            List<Cycles.Ahead> a = cy.ahead(cy.today.plusDays(400));
            for (int i = 1; i < a.size(); i++) grows &= a.get(i).range >= a.get(i - 1).range && a.get(i).ovulationRange >= a.get(i - 1).ovulationRange;
            if (!grows) System.out.println("  detail: " + a);
        }
        check("#383 review: no later cycle has a narrower period or ovulation range than a nearer one", grows);
        check("#383 review: drip style's ovulation text names its range", app.openring.ui.Explain.periodOvulation(dr).contains("drip"),
                app.openring.ui.Explain.periodOvulation(dr));

        // 3. the "about 4 in 5" sentence only where it holds; drip style says what its range is
        String dl = dr.expectedLine(en);
        check("#383 review: drip style's range is not called 4 in 5, it says it is drip's 1 or 2 days", !dl.contains("4 in 5") && dl.contains("drip style shows 2 days each side"), dl);
        int[] var = random(20, 29, 3, 7);
        Cycles vd = Cycles.of(log(var), null, lastStart(var).plusDays(4)).withMethod(Methods.DRIP);
        Backtest.Score vs = vd.backtest().of(Methods.DRIP);
        check("#383 review: and how often the start fell in it on her own periods", vd.expectedLine(en).endsWith("On " + vs.n + " of your past periods the start fell in it "
                + vs.hits + " times."), vd.expectedLine(en));
        Cycles vo = Cycles.of(log(var), null, lastStart(var).plusDays(4));
        Backtest.Score os = vo.backtest().of(Methods.OPEN_RING);
        check("#383 review: other methods keep about 4 in 5, with their real count", vo.expectedLine(en).contains("About 4 in 5 periods start within such a range.")
                && vo.expectedLine(en).endsWith("On " + os.n + " of your past periods the start fell in it " + os.hits + " times."), vo.expectedLine(en));
        check("#383 review: without periods to check, only the 4 in 5", Cycles.of(log(28, 29, 30), null, lastStart(28, 29, 30).plusDays(3)).expectedLine(en)
                .endsWith("About 4 in 5 periods start within such a range."));

        // 4. touching a day ahead: the cycle day counts from that cycle's own expected start
        int[] reg = rep(28, 8);
        Cycles rc = Cycles.of(log(reg), null, lastStart(reg).plusDays(3));
        List<Cycles.Ahead> ra = rc.ahead(rc.today.plusDays(120));
        String two = rc.describe(ra.get(2).start, en), ov3 = rc.describe(ra.get(2).ovulation, en);
        check("#383 review: two cycles ahead the most likely first day is cycle day 1 of that cycle, not 85", two.contains("\nCycle day 1 if the period starts on "
                + rc.weekday(ra.get(2).start, en)) && !two.contains("Cycle day 85"), two);
        check("#383 review: its ovulation day is cycle day 15 of its cycle", ov3.contains("\nCycle day 15 if the period starts on " + rc.weekday(ra.get(1).start, en)), ov3);
        check("#383 review: today and days before the next expected start still count from the last start", rc.describe(rc.today.plusDays(5), en).contains("\nCycle day 9")
                && rc.describe(rc.next.minusDays(1), en).contains("\nCycle day 28") && !rc.describe(rc.next.minusDays(1), en).contains("if the period"), rc.describe(rc.next.minusDays(1), en));
        Cycles late = Cycles.of(log(reg), null, lastStart(reg).plusDays(33));
        check("#383 review: with a late period, the days after today still count from the last start until the next expected one",
                late.describe(late.today.plusDays(2), en).contains("\nCycle day 36") && late.lateBy() > 0, late.describe(late.today.plusDays(2), en));

        // 5. cycles that vary a lot: the rule that leaves far cycles out never leaves out half of them or more
        int[] alt = {21, 45, 21, 45, 21, 45};
        Cycles ac = Cycles.of(log(alt), null, lastStart(alt).plusDays(3));
        check("#383 review: alternating 21 and 45 days: every cycle counts, not the 29 day default", ac.used.size() == 6 && ac.leftOutCount() == 0
                && Methods.kept(alt).length == 6 && f(Methods.OPEN_RING, alt).n == 6 && ac.length == 33, ac.used);
        check("#383 review: and the card does not say to log 3 cycles", !ac.expectedLine(en).contains("Until you have logged") && (ac.unusualLine(en) == null || !ac.unusualLine(en).contains("left out")),
                ac.expectedLine(en) + " | " + ac.unusualLine(en));
        check("#383 review: a missed log among regular cycles is still left out", Methods.kept(cat(rep(28, 8), new int[] {56})).length == 8
                && Cycles.of(log(cat(rep(28, 8), new int[] {56})), null, lastStart(cat(rep(28, 8), new int[] {56})).plusDays(3)).leftOutCount() == 1);
        int[] three = {28, 29, 60};
        Cycles tc = Cycles.of(log(three), null, lastStart(three).plusDays(3));
        check("#383 review: 3 cycles with one far: says only 2 count and why, not to log 3", tc.used.size() == 2
                && tc.expectedLine(en).contains("Only 2 of your 3 cycles count") && !tc.expectedLine(en).contains("Until you have logged"), tc.expectedLine(en));
        Backtest altB = Backtest.run(new int[] {21, 45, 21, 45, 21, 45, 21, 45, 21, 45});
        check("#383 review: the backtest scores such cycles too, not every one as far", altB.scored() > 0, altB.scored() + " " + altB.farOut);
    }
}
