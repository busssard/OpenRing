import app.openring.core.DripCsv;
import app.openring.core.analysis.CycleFactors;
import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.FactorEffect;
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
    /** One decimal, as the card's rows show the days off. */
    static String fmt1(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }

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
        sameAsBefore();
        methods();
        bayes();
        backtest();
        neverFuture();
        choice();
        temperature();
        demo();
        texts();
        review();
        fairPick();
        cycleFactors();
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

    /**
     * The histories the clean ups of the design round (issue #383, phase 1) are checked on: the 45 of {@link #openRingUnchanged}
     * and 400 more from fixed seeds (0 to 45 cycles, 24 to 34 days, regular to irregular, some with a missed log, some
     * alternating or drifting). Made up only.
     */
    static List<int[]> histories() {
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
        for (int s = 0; s < 400; s++) {
            Random r = new Random(1000 + s);
            int n = r.nextInt(46);
            int[] h = random(n, 24 + r.nextInt(11), 0.5 + 5.5 * r.nextDouble(), 2000 + s);
            int kind = s % 8;
            for (int i = 0; i < n; i++) {
                if (kind == 5 && r.nextInt(9) == 0) h[i] *= 2; // a period that was not logged
                if (kind == 6) h[i] = (i % 2 == 0 ? 25 : 32) + r.nextInt(3) - 1; // alternating
                if (kind == 7) h[i] = Math.max(18, h[i] - i / 5); // slowly shorter
            }
            hs.add(h);
        }
        return hs;
    }

    /** Nights by the day their morning falls on, 0.3 °C warmer after each made up ovulation ({@code luteal} days before the next start). */
    static TreeMap<LocalDate, Double> warmNights(int[] lengths, int luteal, LocalDate to, long seed) {
        Random r = new Random(seed);
        Set<LocalDate> warm = new HashSet<LocalDate>();
        LocalDate s = C0;
        for (int l : lengths) {
            LocalDate next = s.plusDays(l);
            for (LocalDate d = next.minusDays(luteal - 1); d.isBefore(next); d = d.plusDays(1)) warm.add(d);
            s = next;
        }
        // the running cycle: warmer from a made up ovulation on its day 29 minus the luteal days
        for (LocalDate d = s.plusDays(29 - luteal); !d.isAfter(to); d = d.plusDays(1)) warm.add(d);
        TreeMap<LocalDate, Double> out = new TreeMap<LocalDate, Double>();
        for (LocalDate d = C0; !d.isAfter(to); d = d.plusDays(1)) if (r.nextInt(10) > 0) out.put(d, 36.3 + 0.06 * r.nextGaussian() + (warm.contains(d) ? 0.3 : 0));
        return out;
    }

    /** Everything a screen shows of one estimate: the numbers, every card text, the touch text of the expected day, the backtests. */
    static void describe(StringBuilder sb, Cycles cy, boolean best) {
        Locale en = Locale.ENGLISH;
        if (cy.method == Cycles.MEASURED) seen[0]++;
        if (cy.rise != null && !cy.riseUsed) seen[1]++;
        if (cy.methodId != Methods.OPEN_RING && cy.choice == Methods.BEST) seen[2]++;
        if (cy.method == Cycles.OWN_LUTEAL) seen[3]++;
        sb.append(cy.next).append('|').append(cy.range).append('|').append(cy.rangeExact).append('|').append(cy.calendarNext).append('|').append(cy.calendarRange)
                .append('|').append(cy.ovulation).append('|').append(cy.ovulationRange).append('|').append(cy.method).append('|').append(cy.methodId)
                .append('|').append(cy.step).append('|').append(cy.typical).append('|').append(cy.sd).append('|').append(cy.length).append('|').append(cy.riseUsed)
                .append('|').append(cy.luteal).append('|').append(cy.lutealSpread).append('|').append(cy.forecast).append('\n');
        sb.append(cy.status(en)).append('|').append(cy.statusNote(en)).append('|').append(cy.dayLine(en)).append('|').append(cy.nextLine()).append('|')
                .append(cy.expectedLine(en)).append('|').append(cy.ovulationLine(en)).append('|').append(cy.fertileLine(en)).append('|')
                .append(cy.regularityLine()).append('|').append(cy.unusualLine(en)).append('\n');
        if (cy.next != null) {
            sb.append(cy.describe(cy.next, en)).append('|').append(cy.ahead(cy.today.plusDays(120))).append('\n');
            sb.append(app.openring.ui.Explain.periodRange(cy)).append('|').append(app.openring.ui.Explain.periodOvulation(cy)).append('\n');
        }
        for (Backtest.Score s : cy.backtest().scores) sb.append(s).append(Arrays.toString(s.expected)).append(Arrays.toString(s.range)).append(';');
        sb.append('\n');
        if (best) sb.append(app.openring.ui.Explain.periodBestNow(cy)).append('\n');
        if (cy.rise != null) {
            RiseBacktest rb = cy.riseBacktest();
            sb.append(rb.method).append('|').append(rb.mae).append('|').append(rb.calendarMae).append('|').append(rb.hits).append('|').append(rb.calendarHits)
                    .append('|').append(rb.cases).append('|').append(cy.riseOff()).append('\n');
            for (RiseBacktest.Case c : rb.cases) sb.append(c.range).append(',').append(c.calendarRange).append(';');
        }
    }

    /**
     * A fingerprint (SHA-256, first 16 hex digits) of everything {@link #describe} shows, per Settings choice ({@code of} alone
     * is the app before #383), on {@link #histories}: the calendar on every history, the temperature on the first 45 and every
     * tenth of the seeded ones.
     */
    static Map<String, String> fingerprints() throws Exception {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH); // the card texts write their decimals in the phone's language
        try {
            return fingerprintsEn();
        } finally {
            Locale.setDefault(before);
        }
    }

    /** What {@link #describe} counted: measured estimates, temperature left out, Best for you not on Open Ring, own luteal days. */
    static final int[] seen = new int[4];

    static Map<String, String> fingerprintsEn() throws Exception {
        Map<String, StringBuilder> text = new TreeMap<String, StringBuilder>();
        int[] choices = {-1, Methods.BEST, Methods.OPEN_RING, Methods.DRIP, Methods.SMOOTH, Methods.TREND, Methods.BAYES};
        List<int[]> hs = histories();
        for (int k = 0; k < hs.size(); k++) {
            int[] h = hs.get(k);
            LocalDate today = lastStart(h).plusDays(3 + k % 40);
            Cycles base = Cycles.of(log(h), null, today);
            boolean temp = k < 45 || k % 10 == 0;
            TreeMap<LocalDate, Double> ring = temp ? warmNights(h, 11 + k % 5, today, k) : null;
            for (int ch : choices) {
                String name = ch < 0 ? "of" : Methods.name(ch);
                Cycles cy = ch < 0 ? base : base.withMethod(ch);
                StringBuilder sb = text.get(name + ", calendar");
                if (sb == null) text.put(name + ", calendar", sb = new StringBuilder());
                describe(sb, cy, ch == Methods.BEST);
                if (!temp) continue;
                sb = text.get(name + ", temperature");
                if (sb == null) text.put(name + ", temperature", sb = new StringBuilder());
                describe(sb, cy.measured(ring, null), ch == Methods.BEST);
            }
        }
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, StringBuilder> e : text.entrySet()) {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(e.getValue().toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i] & 0xff));
            out.put(e.getKey(), hex.toString());
        }
        return out;
    }

    /**
     * The fingerprints of {@link #fingerprints} worked out with the code of dev before the clean ups (shared statistics,
     * {@code measured} reusing the backtest, the RiseBacktest factory step), which must not change any of them. The margin and the
     * temperature rule after them change only Best for you and drip style's temperature, as marked; every other choice is the same.
     */
    static final String[][] BEFORE = {
        // changed on purpose by the margin and the temperature rule (design round of #383, phase 1); before: d7a57493e6e9913b, 8f397f5e86e9fa7d
        // and the stay line under 0.1 days ("only a few hundredths of a day closer", emulator shots of #412); before: f5bd6bb4e3c11948
        // and the stay line from 0.1 days on in hundredths ("0.22 days closer", review of ce5f994); before: 26175e035f3230d3
        // and Settings' own form of the stay line, without the rows above, a lead of 0.1 read as 0.10 (issue #440: 174 lines reworded,
        // 3 leads a hair below 0.1 now 0.10, nothing else); before: 24a8d85074003869
        {"Best for you, calendar", "374273ab6cab211c"},
        // and once more by the review of #412 (the temperature line in one sentence, "as close as the calendar"); before: 4fff7f0461946475
        // and the stay line under 0.1 days; before: dc377626c5c56b08
        // and the stay line from 0.1 days on in hundredths; before: ed6948149ea3290c
        // and Settings' own form of the stay line (issue #440: 34 lines reworded, 1 lead a hair below 0.1 now 0.10); before: c5c6c4d3b5fde693
        {"Best for you, temperature", "9305403bbd59eaf0"},
        {"Follows your last cycle, calendar", "e90423cb13d5775d"},
        {"Follows your last cycle, temperature", "a973dced7a5d960a"},
        {"Learns from every cycle, calendar", "831eff51a8271cea"},
        {"Learns from every cycle, temperature", "7efe6c4fbb3c2501"},
        {"Open Ring, calendar", "768ada8954912a1b"},
        {"Open Ring, temperature", "da908dd899895aeb"},
        {"Recent cycles count more, calendar", "9982cb3586c15222"},
        {"Recent cycles count more, temperature", "ad6b8060d0e6ff0a"},
        {"drip style, calendar", "f7aba84b4df6ac1b"},
        // drip style chosen by hand: its temperature replay now sits on drip style, and it says "chosen by hand"; before: ed1324e4238be86d
        {"drip style, temperature", "c0a3c7f37790149d"},
        {"of, calendar", "768ada8954912a1b"},
        {"of, temperature", "da908dd899895aeb"},
    };

    /** The clean ups of #383 phase 1 change nothing a screen shows, on 445 histories, with and without the temperature. */
    static void sameAsBefore() {
        try {
            long t0 = System.nanoTime();
            Map<String, String> now = fingerprints();
            System.out.println("    #383 fingerprints of " + histories().size() + " histories: " + (System.nanoTime() - t0) / 1000000 + " ms");
            System.out.println("    #383 fingerprints cover " + seen[0] + " measured estimates, " + seen[1] + " with the temperature left out, " + seen[2]
                    + " where Best for you left Open Ring, " + seen[3] + " with the own days from ovulation");
            check("#383 phase 1: the histories reach every state (measured, temperature left out, another method picked, own luteal days)",
                    seen[0] > 0 && seen[1] > 0 && seen[2] > 0 && seen[3] > 0, Arrays.toString(seen));
            boolean all = BEFORE.length == now.size();
            for (String[] b : BEFORE) all &= b[1].equals(now.get(b[0]));
            // when one differs, the new fingerprints to read (and, for a change meant to change them, to paste above)
            if (!all) for (Map.Entry<String, String> e : now.entrySet()) System.out.println("        {\"" + e.getKey() + "\", \"" + e.getValue() + "\"},");
            for (String[] b : BEFORE) {
                check("#383 phase 1: " + b[0] + " shows the same as before on " + histories().size() + " histories", b[1].equals(now.get(b[0])), now.get(b[0]) + " instead of " + b[1]);
            }
            check("#383 phase 1: every choice is compared", BEFORE.length == now.size(), BEFORE.length + " of " + now.size());
        } catch (Exception e) {
            check("#383 phase 1: fingerprints " + e, false);
        }
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
        // the design round's clean up: the same log's backtest is handed on, not run again (the scores are the same object)
        Cycles bm = best.measured(nights(alt, rep(13, alt.length), today, 3), null);
        check("#383 measured() and withMethod() reuse the backtest of the same log", bm.backtest() == best.backtest() && bm.methodId == best.methodId
                && best.withMethod(Methods.BEST).backtest() == best.backtest() && base.withMethod(Methods.DRIP).backtest() != null);
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
        // the design round's clean up: Best for you's replay runs on a Cycles built in full (make), the same as one run later
        Cycles byHand = Cycles.of(log(h), null, today).withMethod(cy.methodId).measured(ring, null);
        check("#383 the temperature backtest of Best for you equals the one run on a finished Cycles with that method", byHand.riseBacktest().cases.toString().equals(rb.cases.toString())
                && byHand.riseBacktest().mae == rb.mae && byHand.riseBacktest().calendarMae == rb.calendarMae, byHand.riseBacktest().cases + " / " + rb.cases);
        Cycles drip = Cycles.of(log(h), null, today).withMethod(Methods.DRIP).measured(ring, null);
        check("#383 drip style chosen by hand never uses the temperature, its replay sits on drip style", !drip.riseUsed && drip.method == Cycles.CALENDAR
                && drip.riseBacktest().method == Methods.DRIP && drip.riseOff().equals("drip style chosen by hand uses start days only, not your temperature."), drip.riseOff());
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
        check("#383 demo: Learns from every cycle is closest on the made up years, by less than the margin, so Best for you stays with Open Ring",
                bt.closest() == Methods.BAYES && bt.lead(Methods.BAYES) > 0 && bt.lead(Methods.BAYES) < Backtest.MARGIN && cy.methodId == Methods.OPEN_RING, sb);
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
        all.add(app.openring.ui.Explain.periodPick(cy));
        all.add(app.openring.ui.Explain.PERIOD_PICK_MARGIN);
        all.add(app.openring.ui.Explain.periodRange(cy));
        all.addAll(app.openring.ui.Explain.periodCardHow(cy));
        boolean dashes = false;
        for (String s : all) if (s.contains(" - ") || s.contains(" – ") || s.contains("—")) {
            dashes = true;
            System.out.println("  dash in: " + s);
        }
        check("#383 texts: no dashes as punctuation", !dashes);
        String now = app.openring.ui.Explain.periodBestNow(cy);
        check("#383 Settings names the method Best for you uses and why it stayed with Open Ring", now.equals("Best for you uses Open Ring now: "
                + String.format(Locale.getDefault(), "%.1f", cy.backtest().of(Methods.OPEN_RING).mae) + " days off on average over 33 of your past periods. Learns from every cycle was only a few hundredths of a day closer, too little for Best for you to switch."), now);
        Cycles bayes = cy.withMethod(Methods.BAYES);
        String range = app.openring.ui.Explain.periodRange(bayes);
        check("#383 How it works on the card: the method, its range and how it did", range.startsWith("Next period: your last start plus " + bayes.step + " days from Learns from every cycle.")
                && range.contains("On 33 of your past periods it was "), range);
        Cycles few = Cycles.of(log(30, 28), null, lastStart(30, 28).plusDays(2)).withMethod(Methods.BEST);
        check("#383 Settings before 6 periods can be checked, the period it starts from built from the constants (3 + 6 + 1)", app.openring.ui.Explain.periodBestNow(few)
                .equals("Best for you uses Open Ring until 6 of your periods can be checked (from the 10th period on; now 0).") && app.openring.ui.Explain.periodBestFrom() == 10,
                app.openring.ui.Explain.periodBestNow(few));
        check("#383 How it works lists every method once, and the life factors (phase 2)", app.openring.ui.Explain.periodAccuracyHow().size() == Methods.ALL.length + 6
                && app.openring.ui.Explain.periodAccuracyHow().contains(CycleFactors.HOW));
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
        Cycles td = tc.withMethod(Methods.DRIP);
        check("#383 review: drip style uses all 3 there and says what its range is", td.forecast.n == 3 && !td.forecast.fallback
                && td.expectedLine(en).contains("drip style shows") && !td.expectedLine(en).contains("Only 2"), td.expectedLine(en));
        check("#383 review: Learns from every cycle there gives its 4 in 5", tc.withMethod(Methods.BAYES).expectedLine(en).endsWith("About 4 in 5 periods start within such a range."),
                tc.withMethod(Methods.BAYES).expectedLine(en));
        Backtest altB = Backtest.run(new int[] {21, 45, 21, 45, 21, 45, 21, 45, 21, 45});
        check("#383 review: the backtest scores such cycles too, not every one as far", altB.scored() > 0, altB.scored() + " " + altB.farOut);
    }

    // ------------------------------------------------ the design round of #383, phase 1: a fair pick

    /** A stand in method: for the cycle at index i it expects {@code expect[i]} days (through Open Ring's rule on 5 such cycles). */
    static final class Fixed implements Method {
        final int id;
        final int[] expect;

        Fixed(int id, int[] expect) {
            this.id = id;
            this.expect = expect;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public Forecast predict(int[] lengths) {
            return Methods.get(Methods.OPEN_RING).predict(rep(expect[lengths.length], 5));
        }
    }

    /** Expects 28 days for every cycle of {@code n}, but {@code off} days more for the first {@code k} scored ones. */
    static int[] expect(int n, int k, int off) {
        int[] e = rep(28, n);
        for (int i = Backtest.FROM; i < Backtest.FROM + k; i++) e[i] = 28 + off;
        return e;
    }

    /** Best for you with Open Ring {@code open} and drip style {@code drip} days off in total over {@code scored} cycles of 28 days. */
    static Backtest margin(int scored, int open, int drip) {
        int n = Backtest.FROM + scored;
        return Backtest.run(rep(28, n), new Method[] {new Fixed(Methods.OPEN_RING, expect(n, open, 1)), new Fixed(Methods.DRIP, expect(n, drip, 1))});
    }

    static void fairPick() {
        // the margin: errors are whole days, so the mean error moves in steps of 1 / n
        Backtest m8 = margin(8, 2, 0);
        check("#383 margin: 8 scored, Open Ring 2 days off in total, drip style none: exactly 0.25 days closer switches", m8.scored() == 8
                && m8.lead(Methods.DRIP) == 0.25 && m8.best() == Methods.DRIP, m8.lead(Methods.DRIP));
        Backtest m8b = margin(8, 1, 0);
        check("#383 margin: 8 scored, 0.125 days closer stays with Open Ring", m8b.best() == Methods.OPEN_RING && m8b.closest() == Methods.DRIP);
        Backtest m24 = margin(25, 11, 5), m28 = margin(25, 12, 5);
        check("#383 margin: 25 scored, 0.24 days closer stays with Open Ring", m24.scored() == 25 && Math.abs(m24.lead(Methods.DRIP) - 0.24) < 1e-9
                && m24.best() == Methods.OPEN_RING && m24.closest() == Methods.DRIP, m24.lead(Methods.DRIP));
        check("#383 margin: 25 scored, 0.28 days closer switches", Math.abs(m28.lead(Methods.DRIP) - 0.28) < 1e-9 && m28.best() == Methods.DRIP, m28.lead(Methods.DRIP));
        boolean under = true;
        for (int k = 1; k < Backtest.MIN_SCORED; k++) under &= margin(k, k, 0).best() == Methods.OPEN_RING && margin(k, k, 0).lead(Methods.DRIP) == 1.0;
        check("#383 margin: under 6 scored cycles always Open Ring, even a whole day closer", Backtest.MIN_SCORED == 6 && under && margin(6, 6, 0).best() == Methods.DRIP);
        check("#383 closest and lead without scored cycles or without Open Ring: Open Ring and no number", Backtest.run(new int[] {28, 29}).closest() == Methods.OPEN_RING
                && Double.isNaN(Backtest.run(new int[] {28, 29}).lead(Methods.DRIP)) && Double.isNaN(margin(8, 2, 0).lead(99))
                && Double.isNaN(Backtest.run(rep(28, 12), new Method[] {new Fixed(Methods.DRIP, rep(28, 12))}).lead(Methods.DRIP))
                && Backtest.run(rep(28, 12), new Method[0]).best() == Methods.OPEN_RING);
        check("#383 margin: the texts say a quarter of a day, and the rule is 0.25", Backtest.MARGIN == 0.25 && app.openring.ui.Explain.PERIOD_PICK_MARGIN.equals(
                "Best for you uses another method only when it was at least a quarter of a day closer on average, over at least 6 periods. Smaller differences are luck."));

        // a tie between two methods other than Open Ring: the one listed first in Methods.ALL (drip style before smoothing)
        int n = Backtest.FROM + 10;
        Backtest tie = Backtest.run(rep(28, n), new Method[] {new Fixed(Methods.OPEN_RING, expect(n, 6, 1)), new Fixed(Methods.DRIP, expect(n, 0, 1)),
                new Fixed(Methods.SMOOTH, expect(n, 0, 1))});
        check("#383 a tie between drip style and Recent cycles count more, both clearly closer: drip style, listed first", tie.best() == Methods.DRIP
                && tie.of(Methods.DRIP).mae == tie.of(Methods.SMOOTH).mae && tie.lead(Methods.SMOOTH) == 0.6);

        // the cycle right after a skipped one (a period that was not logged): not left out, each method with its own rule
        int[] skip = cat(rep(28, 8), new int[] {56}, rep(28, 6));
        Backtest sk = Backtest.run(skip);
        Backtest.Score so = sk.of(Methods.OPEN_RING), sd = sk.of(Methods.DRIP);
        int after = -1, at56 = -1;
        for (int j = 0; j < so.n; j++) {
            if (so.index[j] == 9) after = j;
            if (so.index[j] == 8) at56 = j;
        }
        check("#383 after a skipped period: the 56 day cycle is not scored, the one after it is", sk.farOut == 1 && at56 < 0 && after >= 0, Arrays.toString(so.index));
        check("#383 after a skipped period: Open Ring leaves the 56 out and expects 28, drip style counts it and expects more", after >= 0 && so.expected[after] == 28
                && sd.expected[after] == (int) Math.round((28 * 8 + 56) / 9.0) && so.late(after) == 0, after >= 0 ? so.expected[after] + " / " + sd.expected[after] : "");

        // Backtest.run with 130 cycles (10 years) in a moment
        int[] ten = random(130, 29, 3, 77);
        Backtest.run(ten);
        long t0 = System.nanoTime();
        Backtest tb = Backtest.run(ten);
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("    #383 backtest of 130 cycles: " + ms + " ms");
        check("#383 backtest of 130 cycles: under 0.5 s on the JVM", ms < 500 && tb.scored() > 120, ms);

        // the temperature follows the choice, not the method: drip style picked by Best for you keeps it, chosen by hand not
        int[] dh = random(20, 29, 6, 29); // the first seed where drip style wins clearly (a search over seeds)
        LocalDate today = lastStart(dh).plusDays(6);
        TreeMap<LocalDate, Double> ring = nights(dh, rep(13, dh.length), today, 4);
        Cycles picked = Cycles.of(log(dh), null, today).withMethod(Methods.BEST).measured(ring, null);
        Cycles hand = Cycles.of(log(dh), null, today).withMethod(Methods.DRIP).measured(ring, null);
        RiseBacktest prb = picked.riseBacktest();
        check("#383 Best for you picks drip style here, by at least the margin", picked.methodId == Methods.DRIP && picked.backtest().lead(Methods.DRIP) >= Backtest.MARGIN,
                picked.backtest().lead(Methods.DRIP));
        check("#383 drip style picked by Best for you: the temperature is used (its own backtest decides)", picked.riseUsed && prb.helps() && prb.n() >= RiseBacktest.MIN
                && prb.method == Methods.DRIP, prb.mae + " / " + prb.calendarMae);
        check("#383 drip style chosen by hand: no temperature, and it says why", !hand.riseUsed && hand.method == Cycles.CALENDAR
                && hand.riseOff().equals("drip style chosen by hand uses start days only, not your temperature."), hand.riseOff());
        boolean cal = !prb.cases.isEmpty();
        for (RiseBacktest.Case c : prb.cases) {
            Cycles then = Cycles.of(new TreeMap<LocalDate, Integer>(log(dh).headMap(c.day, true)), null, c.day).withMethod(Methods.DRIP);
            cal &= c.calendar.equals(then.calendarNext) && c.calendarRange == then.calendarRange;
        }
        check("#383 drip style picked: each replayed cycle's calendar is drip style's own next start on that day", cal, prb.cases);
        String use = app.openring.ui.Explain.periodTempUse(picked);
        check("#383 the card names the mix and says why the temperature is used, in one sentence", use.equals("Best for you picked drip style and adds your night "
                + "temperature on top, because on " + prb.n() + " past cycles it was "
                + String.format(Locale.getDefault(), "%.1f", prb.mae) + " days off on average, the calendar " + String.format(Locale.getDefault(), "%.1f", prb.calendarMae) + "."), use);
        check("#383 and the Period card's How it works names it too", picked.method != Cycles.MEASURED
                && app.openring.ui.Explain.periodRange(picked).startsWith("Next period: your last start plus " + picked.step
                + " days from drip style (picked for you, with your night temperature on top). "), app.openring.ui.Explain.periodRange(picked));
        String f = String.format(Locale.getDefault(), "%.1f", 1.2);
        check("#383 the temperature line: other methods, a tie with the calendar, too few cycles", app.openring.ui.Explain.periodTempWords(false, 7, 1.2, 2.4)
                .equals("Your night temperature is used on top of the method picked, because on 7 past cycles it was " + f + " days off on average, the calendar "
                + String.format(Locale.getDefault(), "%.1f", 2.4) + ".")
                && app.openring.ui.Explain.periodTempWords(false, 7, 1.2, 1.2).equals("Your night temperature is used on top of the method picked, because on 7 past cycles "
                + "it was as close as the calendar (" + f + " days off on average).")
                && app.openring.ui.Explain.periodTempWords(true, 5, 1.24, 1.21).equals("Best for you picked drip style and adds your night temperature on top, because on 5 "
                + "past cycles it was as close as the calendar (" + f + " days off on average).")
                && app.openring.ui.Explain.periodTempWords(true, 1, 0, 3).equals("Best for you picked drip style and adds your night temperature on top until 3 past cycles "
                + "can show whether it is closer (now 1)."), app.openring.ui.Explain.periodTempWords(false, 7, 1.2, 1.2));
        String useShort = app.openring.ui.Explain.periodTempUseShort(picked);
        String closer = prb.mae < prb.calendarMae && !String.format(Locale.getDefault(), "%.1f", prb.mae).equals(String.format(Locale.getDefault(), "%.1f", prb.calendarMae))
                ? ", because it was closer on average over " + prb.n() + " past cycles." : ", because over " + prb.n() + " past cycles it was as close as the calendar on average.";
        check("#383 review of ce5f994: the card's short form without the numbers the tiles show, Settings keeps them", useShort.equals("Best for you picked drip style and "
                + "adds your night temperature on top" + closer) && !useShort.contains(String.format(Locale.getDefault(), "%.1f", prb.mae))
                && app.openring.ui.Explain.periodBestNow(picked).contains(use), useShort);
        check("#383 the short form: closer, a tie, too few cycles", app.openring.ui.Explain.periodTempWords(false, 7, 1.2, 2.4, false)
                .equals("Your night temperature is used on top of the method picked, because it was closer on average over 7 past cycles.")
                && app.openring.ui.Explain.periodTempWords(false, 7, 1.24, 1.21, false).equals("Your night temperature is used on top of the method picked, because over 7 "
                + "past cycles it was as close as the calendar on average.")
                && app.openring.ui.Explain.periodTempWords(true, 1, 0, 3, false).equals(app.openring.ui.Explain.periodTempWords(true, 1, 0, 3)),
                app.openring.ui.Explain.periodTempWords(false, 7, 1.24, 1.21, false));
        check("#383 no such line for a method chosen by hand or without the temperature", app.openring.ui.Explain.periodTempUse(hand) == null
                && app.openring.ui.Explain.periodTempUseShort(hand) == null
                && app.openring.ui.Explain.periodTempUse(Cycles.of(log(dh), null, today).withMethod(Methods.BEST)) == null);

        // the texts: the stay line, under 0.1 days "only a few hundredths", the period Best for you can start from
        Cycles demo = Cycles.of(Cycles.demoHistory(LocalDate.of(2026, 11, 12)), null, LocalDate.of(2026, 11, 12)).withMethod(Methods.BEST);
        check("#383 the card under the methods: the margin, and a few hundredths of a day for the demo's 0.03", app.openring.ui.Explain.periodPick(demo).equals(
                app.openring.ui.Explain.PERIOD_PICK_MARGIN + " Learns from every cycle was only a few hundredths of a day closer (the days off above are rounded), "
                + "too little to switch."), app.openring.ui.Explain.periodPick(demo));
        int[] small = random(20, 29, 2.6, 1); // Follows your last cycle 0.12 days closer (2 days over 17 periods)
        Cycles alt = Cycles.of(log(small), null, lastStart(small).plusDays(3)).withMethod(Methods.BEST);
        String stay = app.openring.ui.Explain.periodStay(alt);
        double lead = alt.backtest().lead(Methods.TREND);
        check("#383 the stay line with two decimals from 0.1 days on", alt.methodId == Methods.OPEN_RING && alt.backtest().closest() == Methods.TREND && lead >= 0.1
                && lead < Backtest.MARGIN && ("Follows your last cycle was " + String.format(Locale.getDefault(), "%.2f", 0.12) + " days closer (the days off above are rounded), "
                + "less than the quarter of a day needed to switch.").equals(stay), lead + " " + stay);
        // review of ce5f994: rows 2.7 and 2.4 (0.3 apart, more than a quarter) for a lead of 0.22, which one decimal showed as "0.2 days closer"
        int[] apart = random(12, 29, 2.6, 58);
        Cycles ap = Cycles.of(log(apart), null, lastStart(apart).plusDays(3)).withMethod(Methods.BEST);
        Backtest abt = ap.backtest();
        String apStay = app.openring.ui.Explain.periodStay(ap);
        check("#383 rows 0.3 apart for a lead of 0.22: the stay line says 0.22 and that the rows are rounded", ap.methodId == Methods.OPEN_RING
                && abt.closest() == Methods.DRIP && fmt1(abt.of(Methods.OPEN_RING).mae).equals(fmt1(2.7)) && fmt1(abt.of(Methods.DRIP).mae).equals(fmt1(2.4))
                && ("drip style was " + String.format(Locale.getDefault(), "%.2f", 0.22) + " days closer (the days off above are rounded), less than the quarter of a day "
                + "needed to switch.").equals(apStay), abt.lead(Methods.DRIP) + " " + apStay);
        // on many histories: whenever it stays, the number in the line is the lead in hundredths, never rounded up to a quarter
        boolean cut = true;
        int stays = 0;
        for (double spread : new double[] {1.5, 2.6, 4}) for (long seed = 1; seed <= 60; seed++) {
            int[] l = random(12 + (int) (seed % 20), 29, spread, seed);
            Cycles cy = Cycles.of(log(l), null, lastStart(l).plusDays(3)).withMethod(Methods.BEST);
            String st = app.openring.ui.Explain.periodStay(cy);
            if (st == null) continue;
            stays++;
            double ld = cy.backtest().lead(cy.backtest().closest());
            String want = ld < 0.1 - 1e-9 ? "only a few hundredths of a day closer (the days off above are rounded), too little to switch."
                    : String.format(Locale.getDefault(), "%.2f", Math.min(Math.round(ld * 100), 24) / 100.0) + " days closer (the days off above are rounded), less than the quarter";
            if (ld >= Backtest.MARGIN || !st.contains(want) || st.contains(String.format(Locale.getDefault(), "%.2f", 0.25))) {
                cut = false;
                System.out.println("  " + ld + ": " + st);
            }
        }
        check("#383 every stay line on 180 histories: the lead in hundredths, never a quarter, rows marked as rounded", cut && stays > 10, stays);
        // issue #440: Settings has no rows above and no margin line, so its form of the stay line names neither
        String setStay = app.openring.ui.Explain.periodStay(ap, false), setNow = app.openring.ui.Explain.periodBestNow(ap);
        check("#440 Settings' stay line: the lead and Best for you's quarter of a day, no rows above", ("drip style was "
                + String.format(Locale.getDefault(), "%.2f", 0.22) + " days closer, less than the quarter of a day Best for you needs to switch.").equals(setStay)
                && setNow.endsWith(" " + setStay) && !setNow.contains("above") && app.openring.ui.Explain.periodStay(ap, true).equals(apStay), setNow);
        String demoNow = app.openring.ui.Explain.periodBestNow(demo);
        check("#440 Settings' stay line under 0.1 days: a few hundredths, no rows above", demoNow.contains(" Learns from every cycle was only a few hundredths "
                + "of a day closer, too little for Best for you to switch.") && !demoNow.contains("above"), demoNow);
        String tenth = String.format(Locale.getDefault(), "%.2f", 0.1);
        check("#440 a lead of exactly 0.1 days (also 0.3 minus 0.2, a hair below) reads 0.10 on the card and in Settings, 0.0999 a few hundredths",
                app.openring.ui.Explain.periodStayWords("drip style", 0.1, true).equals("drip style was " + tenth + " days closer (the days off above are rounded), "
                + "less than the quarter of a day needed to switch.")
                && app.openring.ui.Explain.periodStayWords("drip style", 0.3 - 0.2, true).equals(app.openring.ui.Explain.periodStayWords("drip style", 0.1, true))
                && app.openring.ui.Explain.periodStayWords("drip style", 0.3 - 0.2, false).equals("drip style was " + tenth + " days closer, less than the quarter "
                + "of a day Best for you needs to switch.")
                && app.openring.ui.Explain.periodStayWords("drip style", 0.0999, true).equals("drip style was only a few hundredths of a day closer "
                + "(the days off above are rounded), too little to switch.")
                && app.openring.ui.Explain.periodStayWords("drip style", 0.0999, false).equals("drip style was only a few hundredths of a day closer, "
                + "too little for Best for you to switch."), app.openring.ui.Explain.periodStayWords("drip style", 0.3 - 0.2, false));
        check("#383 no stay line when Best for you switched or is not chosen", app.openring.ui.Explain.periodStay(picked) == null
                && app.openring.ui.Explain.periodStay(demo.withMethod(Methods.OPEN_RING)) == null);
        check("#383 ordinals", app.openring.ui.Explain.ordinal(1).equals("1st") && app.openring.ui.Explain.ordinal(2).equals("2nd") && app.openring.ui.Explain.ordinal(3).equals("3rd")
                && app.openring.ui.Explain.ordinal(5).equals("5th") && app.openring.ui.Explain.ordinal(10).equals("10th") && app.openring.ui.Explain.ordinal(11).equals("11th")
                && app.openring.ui.Explain.ordinal(12).equals("12th") && app.openring.ui.Explain.ordinal(22).equals("22nd") && app.openring.ui.Explain.ordinal(113).equals("113th"));
        check("#383 How it works counts from the constants", app.openring.ui.Explain.periodAccuracyHow().get(0).contains("from the 5th on")
                && app.openring.ui.Explain.periodAccuracyHow().get(1).startsWith("Best for you: the method that was closest on average, if it was at least a quarter of a day "
                + "closer than Open Ring over at least 6 periods, else Open Ring. Smaller differences are luck. So it can leave Open Ring from your 10th period on at the earliest."),
                app.openring.ui.Explain.periodAccuracyHow().get(1));
        List<String> texts = new ArrayList<String>(Arrays.asList(use, useShort, apStay, stay, Methods.line(Methods.DRIP), Methods.how(Methods.DRIP), hand.riseOff(), app.openring.ui.Explain.periodPick(demo),
                app.openring.ui.Explain.periodBestNow(picked), app.openring.ui.Explain.periodRange(picked), app.openring.ui.Explain.periodBestNow(demo), setStay, setNow));
        texts.addAll(app.openring.ui.Explain.periodAccuracyHow());
        boolean dashes = false;
        for (String t : texts) if (t == null || t.contains(" - ") || t.contains(" – ") || t.contains("—") || t.contains("–")) {
            dashes = true;
            System.out.println("  dash in: " + t);
        }
        check("#383 the new texts have no dashes as punctuation", !dashes);

        // how often the pick changes: on regular made up histories (SD 2.6 days, 40 cycles), the pick after every logged start
        int now = 0, before = 0, never = 0;
        for (long seed = 500; seed < 600; seed++) {
            int[] h = random(40, 29, 2.6, seed);
            int a = -1, b = -1, fa = 0, fb = 0;
            for (int k = 0; k <= h.length; k++) {
                Backtest bt = Backtest.run(Arrays.copyOf(h, k));
                // before the margin: the closest from 3 scored cycles on
                int x = bt.best(), y = bt.scored() < 3 ? Methods.OPEN_RING : bt.closest();
                if (a >= 0 && x != a) fa++;
                if (b >= 0 && y != b) fb++;
                a = x;
                b = y;
            }
            now += fa;
            before += fb;
            if (fa == 0) never++;
        }
        System.out.println("    #383 flips of Best for you over 100 regular histories of 40 cycles: " + now + " with the margin, " + before + " before; "
                + never + " never change");
        check("#383 the margin flips Best for you at most about half as often on regular cycles", now * 2 <= before + 10 && never >= 40, now + " / " + before + ", never " + never);
    }

    // ------------------------------------------------------------------ phase 2: life factors of each cycle

    /** Tags as the app reads them (FactorEffect.tags): {@code days} day to keys, the names in the list order, own tags' made days. */
    static List<FactorEffect.Factor> tags(Map<LocalDate, List<String>> days, Map<String, LocalDate> made, String... keyAndName) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyAndName.length; i += 2) names.put(keyAndName[i], keyAndName[i + 1]);
        return FactorEffect.tags(days, names, made);
    }

    static void tag(Map<LocalDate, List<String>> m, LocalDate d, String key) {
        List<String> l = m.get(d);
        if (l == null) m.put(d, l = new ArrayList<String>());
        l.add(key);
    }

    static CycleFactors cf(Cycles cy, List<FactorEffect.Factor> f) {
        return CycleFactors.of(cy, cy.backtest().of(cy.methodId), f);
    }

    static List<String> keys(List<FactorEffect.Factor> l) {
        List<String> out = new ArrayList<String>();
        for (FactorEffect.Factor f : l) out.add(f.key);
        return out;
    }

    /**
     * Issue #383, phase 2 (design comment of 25 November): the life factors logged in each scored cycle, by calendar day, from the
     * period's first day to the day before the next start; with, without and unknown; the texts, which only describe; a trip across
     * the date line. Made up histories only.
     */
    static void cycleFactors() {
        Locale en = Locale.ENGLISH;
        final int WITH = CycleFactors.WITH, WITHOUT = CycleFactors.WITHOUT, UNKNOWN = CycleFactors.UNKNOWN;
        // 1. the window's edges: six 28 day cycles, the scored ones are the 4th to 6th (index 3 to 5)
        int[] h = rep(28, 6);
        Cycles cy = Cycles.of(log(h), null, lastStart(h).plusDays(9));
        Map<LocalDate, List<String>> m = new TreeMap<LocalDate, List<String>>();
        tag(m, C0, "meditation");                        // the first logged day: everything counts from here
        tag(m, C0.plusDays(84), "illness");              // the first day of the 4th cycle's period
        tag(m, C0.plusDays(111), "travel");              // the day before the next start
        tag(m, C0.plusDays(112), "stress");              // the next start itself: the next cycle
        CycleFactors c = cf(cy, tags(m, null, "meditation", "Meditation", "illness", "Illness", "travel", "Travel", "stress", "Stress"));
        check("#383 phase 2: the scored periods are the backtest's", c.periods.size() == 3 && c.periods.get(0).cycle == cy.cycles.get(3), c.periods.size());
        check("#383 phase 2: a tag on the period's first day and on the day before the next start count for that cycle",
                c.periods.get(0).logged.keySet().equals(new HashSet<String>(Arrays.asList("illness", "travel"))), c.periods.get(0).logged);
        check("#383 phase 2: a tag on the day the next period came counts for the next cycle", c.periods.get(1).logged.keySet().equals(Collections.singleton("stress")),
                c.periods.get(1).logged);
        int counted = 0;
        for (CycleFactors.Period p : c.periods) for (List<LocalDate> l : p.logged.values()) counted += l.size();
        check("#383 phase 2: no day counts in two cycles", counted == 3, counted);
        check("#383 phase 2: with and without", c.state(0, "illness") == WITH && c.state(1, "illness") == WITHOUT && c.state(2, "travel") == WITHOUT, null);
        check("#383 phase 2: Meditation, only logged in an unscored cycle, is no chip; the others in the list order",
                keys(c.chips).equals(Arrays.asList("illness", "travel", "stress")), keys(c.chips));
        check("#383 phase 2: no faded dot when logging started before the scored cycles", !c.anyUnknown("illness"), null);
        check("#383 phase 2: the newest period with a factor", c.newest("stress") == 1 && c.newest("illness") == 0 && c.newest("meditation") == -1, null);
        check("#383 phase 2: a known cycle with nothing logged", c.tip(2, null, en).equals("Nothing logged that cycle."), c.tip(2, null, en));
        check("#383 phase 2: the readout names the factors with their cycle days", c.tip(0, null, en).equals("Logged that cycle: Illness 1 day (day 1),\nTravel 1 day (day 28)"),
                c.tip(0, null, en));
        check("#383 phase 2: the marked factor comes first", c.tip(0, "travel", en).startsWith("Logged that cycle: Travel 1 day (day 28),"), c.tip(0, "travel", en));
        check("#383 phase 2: one cycle, the decisions' sentence", c.line("illness", en).equals("The period on " + cy.date(cy.cycles.get(3).next, en)
                + " came on the expected day; you logged Illness that cycle (1 day)."), c.line("illness", en));

        // 2. unknown before the first logged day, an own tag from the day it was made, a removed tag's day as the first day
        m = new TreeMap<LocalDate, List<String>>();
        tag(m, C0.plusDays(120), "own:9");               // a removed own tag (not named): only the first logged day
        tag(m, C0.plusDays(122), "illness");             // the 5th cycle (112 to 139)
        tag(m, C0.plusDays(150), "meditation");          // the 6th cycle (140 to 167)
        tag(m, C0.plusDays(145), "own:7");
        Map<String, LocalDate> made = new HashMap<String, LocalDate>();
        made.put("own:7", C0.plusDays(143));             // made in the 6th cycle
        List<FactorEffect.Factor> f2 = tags(m, made, "illness", "Illness", "meditation", "Meditation", "own:7", "Late dinner");
        Set<LocalDate> copy = new HashSet<LocalDate>(f2.get(0).days);
        c = cf(cy, f2);
        check("#383 phase 2: the inputs are not changed", f2.get(0).days.equals(copy) && f2.size() == 3, null);
        check("#383 phase 2: a cycle before any logging is unknown, not without", c.state(0, "illness") == UNKNOWN && c.state(0, "meditation") == UNKNOWN, null);
        check("#383 phase 2: its readout", c.tip(0, null, en).equals("Before you started logging life factors (" + cy.date(C0.plusDays(120), en) + ")."), c.tip(0, null, en));
        check("#383 phase 2: the cycle logging started in: with, and unknown for what was not logged there yet",
                c.state(1, "illness") == WITH && c.state(1, "meditation") == UNKNOWN && c.state(2, "illness") == WITHOUT && c.state(2, "meditation") == WITH, null);
        check("#383 phase 2: an own tag counts from the day it was made", c.state(1, "own:7") == UNKNOWN && c.state(2, "own:7") == WITH, null);
        check("#383 phase 2: faded dots for the marked factor before it could be logged", c.anyUnknown("meditation"), null);
        m.remove(C0.plusDays(122));
        tag(m, C0.plusDays(160), "illness");
        c = cf(cy, tags(m, made, "illness", "Illness", "meditation", "Meditation"));
        check("#383 phase 2: the cycle logging started in, with nothing logged since", c.tip(1, null, en).equals("Nothing logged that cycle since you started on "
                + cy.date(C0.plusDays(120), en) + "."), c.tip(1, null, en));

        // 3. only scored cycles (a far one between), each method in use, the late days as the backtest's
        int[] far = {28, 29, 28, 57, 28, 29, 28, 30, 27};
        Cycles base = Cycles.of(log(far), null, lastStart(far).plusDays(5));
        m = new TreeMap<LocalDate, List<String>>();
        for (int i = 0; i < 300; i += 3) tag(m, C0.plusDays(i), "alcohol");
        List<FactorEffect.Factor> fa = tags(m, null, "alcohol", "Alcohol");
        boolean mapped = true;
        int checkedMethods = 0;
        for (int id : Methods.CHOICES) {
            Cycles w = base.withMethod(id);
            Backtest.Score use = w.backtest().of(w.methodId);
            c = cf(w, fa);
            mapped &= c.periods.size() == use.n && use.n > 0;
            for (int i = 0; mapped && i < use.n; i++) mapped = c.periods.get(i).cycle == w.cycles.get(use.index[i]) && c.periods.get(i).late == use.late(i);
            for (CycleFactors.Period p : c.periods) mapped &= p.cycle.length != 57;
            checkedMethods++;
        }
        check("#383 phase 2: the scored periods and their late days for every method, the far cycle left out", mapped && checkedMethods == Methods.CHOICES.length, null);

        // 4. the texts: one cycle, averages, every cycle
        int[] t = {28, 28, 28, 28, 31, 28, 25, 28};
        Cycles ct = Cycles.of(log(t), null, lastStart(t).plusDays(4));
        Backtest.Score ut = ct.backtest().of(ct.methodId);
        int[] starts = new int[t.length + 1];
        for (int i = 0; i < t.length; i++) starts[i + 1] = starts[i] + t[i];
        m = new TreeMap<LocalDate, List<String>>();
        // Illness on days 5 and 6 of the 5th cycle (index 4), Stress on scattered days of the 5th and 7th, Meditation every day
        tag(m, C0.plusDays(starts[4] + 4), "illness");
        tag(m, C0.plusDays(starts[4] + 5), "illness");
        tag(m, C0.plusDays(starts[4] + 2), "stress");
        tag(m, C0.plusDays(starts[6] + 9), "stress");
        tag(m, C0.plusDays(starts[6] + 12), "stress");
        for (int i = 0; i < starts[t.length]; i++) tag(m, C0.plusDays(i), "meditation");
        c = cf(ct, tags(m, null, "meditation", "Meditation", "stress", "Stress", "illness", "Illness"));
        int pi = -1, p6 = -1;
        for (int i = 0; i < ut.n; i++) {
            if (ut.index[i] == 4) pi = i;
            if (ut.index[i] == 6) p6 = i;
        }
        int late = ut.late(pi);
        String came = late == 0 ? "came on the expected day" : "came " + Math.abs(late) + (Math.abs(late) == 1 ? " day " : " days ") + (late > 0 ? "later" : "earlier") + " than expected";
        check("#383 phase 2: one cycle with the factor, the period's own sentence", c.line("illness", en).equals("The period on " + ct.date(ct.cycles.get(4).next, en)
                + " " + came + "; you logged Illness that cycle (2 days)."), c.line("illness", en) + " late " + late);
        check("#383 phase 2: the 31 day cycle came 3 days later than Open Ring expected", late == 3, late);
        double w = 0, o = 0;
        int nw = 0, no = 0;
        for (int i = 0; i < ut.n; i++) {
            if (c.state(i, "stress") == WITH) {
                w += ut.late(i);
                nw++;
            } else if (c.state(i, "stress") == WITHOUT) {
                o += ut.late(i);
                no++;
            }
        }
        w /= nw;
        o /= no;
        String avg = Math.abs(w) < 0.5 ? "about on the expected day on average" : String.format(en, "%.1f", Math.abs(w)) + " days " + (w > 0 ? "later" : "earlier") + " than expected on average";
        String oth = Math.abs(o) < 0.5 ? "came about on the expected day." : "came " + String.format(en, "%.1f", Math.abs(o)) + " days " + (o > 0 ? "later." : "earlier.");
        check("#383 phase 2: from two cycles, the averages with and without", nw == 2 && no >= 1 && c.line("stress", en).equals("Stress was logged in 2 of " + (nw + no)
                + " cycles. Those periods came " + avg + ", the other " + (no == 1 ? "one " : no + " ") + oth), c.line("stress", en));
        check("#383 phase 2: in every cycle, nothing to set it against", c.line("meditation", en).equals(CycleFactors.EVERY), c.line("meditation", en));
        check("#383 phase 2: a factor in no scored cycle has no line", c.line("nothing", en) == null, null);
        String tipAll = c.tip(pi, "illness", en);
        boolean wrapped = true;
        String[] lines = tipAll.split("\n");
        for (String l : lines) wrapped &= l.length() <= CycleFactors.WRAP;
        check("#383 phase 2: the readout wrapped, the marked factor first, then the list order", wrapped
                && tipAll.equals("Logged that cycle: Illness 2 days (days 5 to 6),\nMeditation 31 days (days 1 to 31),\nStress 1 day (day 3)"), tipAll);
        String tip6 = c.tip(p6, null, en);
        check("#383 phase 2: scattered days give the count only", tip6.contains("Stress 2 days") && !tip6.contains("Stress 2 days ("), tip6);
        // the words: only describing, plain
        List<String> words = new ArrayList<String>(Arrays.asList(CycleFactors.HOW, CycleFactors.CHANCE, CycleFactors.MARK, CycleFactors.EVERY,
                CycleFactors.NOTHING, CycleFactors.LEGEND_FADED, CycleFactors.legendRing("Illness"), c.line("illness", en), c.line("stress", en)));
        for (int i = 0; i < ut.n; i++) words.add(c.tip(i, "stress", en));
        boolean plain = true;
        for (String s : words) {
            String l = s.toLowerCase(Locale.ROOT);
            if (s.contains(" - ") || s.contains("–") || s.contains("—") || l.contains("because") || l.contains("caused") || l.contains("due to")) {
                plain = false;
                System.out.println("  not plain: " + s);
            }
        }
        check("#383 phase 2: texts plain, no dashes, never a cause", plain, null);

        // 5. only logged tags: the period tracker's own factors and empty ones are left out
        List<FactorEffect.Factor> mixed = new ArrayList<FactorEffect.Factor>(tags(m, null, "stress", "Stress"));
        mixed.add(new FactorEffect.Factor("period", "Period days", FactorEffect.PERIOD, new HashSet<LocalDate>(Arrays.asList(C0.plusDays(starts[5]))), C0, null));
        mixed.add(new FactorEffect.Factor("empty", "Empty", FactorEffect.TAG, null, C0, null));
        mixed.add(null);
        c = cf(ct, mixed);
        check("#383 phase 2: only logged tags are chips", keys(c.chips).equals(Collections.singletonList("stress")), keys(c.chips));
        c = cf(ct, new ArrayList<FactorEffect.Factor>());
        check("#383 phase 2: without any factor, no chip and no readout line", c.chips.isEmpty() && c.tip(0, null, en).isEmpty() && c.first == null, null);

        // 6. a trip across the date line: the same cycles and factors whatever the phone's zone
        dateLine();

        // 7. 130 cycles and 5 years of 6 tags a day
        int[] many = random(130, 29, 2.5, 383);
        Cycles cm = Cycles.of(log(many), null, lastStart(many).plusDays(3));
        cm.backtest();
        Map<LocalDate, List<String>> lots = new TreeMap<LocalDate, List<String>>();
        String[] six = {"meditation", "alcohol", "stress", "hard_workout", "sauna", "friends"};
        LocalDate end = lastStart(many);
        for (LocalDate d = end.minusYears(5); d.isBefore(end); d = d.plusDays(1)) for (String k : six) tag(lots, d, k);
        List<FactorEffect.Factor> fl = tags(lots, null, "meditation", "Meditation", "alcohol", "Alcohol", "stress", "Stress",
                "hard_workout", "Hard workout", "sauna", "Sauna", "friends", "Friends");
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            CycleFactors big = cf(cm, fl);
            for (int i = 0; i < big.periods.size(); i++) big.tip(i, "stress", en);
            big.line("stress", en);
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.println("    #383 phase 2: 130 cycles, 5 years of 6 tags a day: " + best / 1000000 + " ms");
        check("#383 phase 2: 130 cycles and 5 years of 6 tags a day in under 50 ms", best < 50000000L, best / 1e6);
    }

    /**
     * The Design review of 17 November: travel from the logged factor, cycle lengths by local dates, a trip across the date line
     * moves no period start. Auckland to Honolulu (a calendar day happens twice, one date) and back (a calendar day skipped,
     * nothing logged on it), Travel logged on the days away, the period logged the day after landing; an own tag made at 23:50
     * in Honolulu (the next day in Auckland) and logged that Honolulu day. Worked out with the phone's zone on each side of
     * the line: the same.
     */
    static void dateLine() {
        java.util.TimeZone was = java.util.TimeZone.getDefault();
        String[] zones = {"Pacific/Auckland", "Pacific/Honolulu", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC"};
        int[] h = {29, 28, 30, 29, 28, 29, 30};
        // the 7th period is logged the day after landing
        LocalDate land = lastStart(29, 28, 30, 29, 28, 29).minusDays(1);
        String first = null;
        boolean same = true, ownCounts = true, travelIn = true;
        try {
            for (String z : zones) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(z));
                java.time.ZoneId zone = java.time.ZoneId.of(z);
                Cycles cy = Cycles.of(log(h), null, lastStart(h).plusDays(6));
                Map<LocalDate, List<String>> m = new TreeMap<LocalDate, List<String>>();
                tag(m, C0, "meditation");
                // the trip out: three days away, the last the landing day
                for (int i = 2; i >= 0; i--) tag(m, land.minusDays(i), "travel");
                // the way back, a calendar day skipped
                tag(m, land.plusDays(20), "travel");
                tag(m, land.plusDays(22), "travel");
                // an own tag made at 23:50 in Honolulu the day before landing, logged on that Honolulu day
                LocalDate hnl = land.minusDays(1);
                long millis = hnl.atTime(23, 50).atZone(java.time.ZoneId.of("Pacific/Honolulu")).toInstant().toEpochMilli();
                String own = "own:" + millis;
                tag(m, hnl, own);
                Map<String, LocalDate> made = new HashMap<String, LocalDate>();
                made.put(own, java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate());
                CycleFactors c = cf(cy, tags(m, made, "travel", "Travel", own, "Market visit"));
                StringBuilder b = new StringBuilder();
                for (Cycles.Period p : cy.periods) b.append(p.start).append(',');
                b.append(Arrays.toString(cy.lengths())).append(cy.next);
                for (int i = 0; i < c.periods.size(); i++) b.append('|').append(c.periods.get(i).late).append(c.periods.get(i).logged).append(c.tip(i, "travel", Locale.ENGLISH));
                b.append(c.line("travel", Locale.ENGLISH));
                if (first == null) first = b.toString();
                else if (!first.equals(b.toString())) {
                    same = false;
                    System.out.println("  " + z + ": " + b);
                }
                int at = -1;
                for (int i = 0; i < c.periods.size(); i++) if (c.periods.get(i).cycle.next.equals(land.plusDays(1))) at = i;
                travelIn &= at >= 0 && c.state(at, "travel") == CycleFactors.WITH && c.periods.get(at).logged.get("travel").size() == 3
                        && cy.periods.get(6).start.equals(land.plusDays(1));
                ownCounts &= at >= 0 && c.state(at, own) == CycleFactors.WITH;
            }
        } finally {
            java.util.TimeZone.setDefault(was);
        }
        check("#383 phase 2 date line: the same period starts, cycle lengths, errors and factors in every zone", same, first);
        check("#383 phase 2 date line: Travel counts in the cycle of the trip, the period after landing starts on its day", travelIn, null);
        check("#383 phase 2 date line: an own tag made late in Honolulu still counts on its Honolulu day", ownCounts, null);
    }
}
