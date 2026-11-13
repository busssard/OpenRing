package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ovulation from the temperature rise (issue #252, phase 2). After ovulation the temperature stays a little higher until
 * the next period; this finds that step in one cycle of one signal (the ring's nightly skin temperature, or the morning
 * temperatures by mouth imported from drip, each on its own) with the classic three over six rule:
 *
 * <ul>
 * <li>the <b>baseline</b> is the highest of the {@link #LOW} nights before the first high night (they must lie in the
 * cycle, within {@link #LOW} + 2 days, so up to two nights may be missing);</li>
 * <li>the first high night and the next ones (at most one missing day between two of them) each lie more than
 * {@link #MARGIN} above it, and the third at least {@link #thirdNeed} above it: 0.2 °C for a quiet signal, less for a
 * noisier one ({@link #THIRD_PER_NOISE} times the signal's own night to night noise, from {@link #THIRD_MIN} to
 * {@link #THIRD_MAX}). One exception as in the classic rule: a third night that falls short, or one night that dips to the
 * baseline in between, needs a fourth high night;</li>
 * <li>the rise must <b>last</b>: the nights from the first high one to the end of the cycle (or today) lie on average at
 * least {@link #LAST_PER_NOISE} times the noise above the six (at least {@link #LAST_MIN}), so one warm spell does not
 * count;</li>
 * <li>in a cycle that is over, ovulation lies {@link #LUTEAL_MIN} to {@link #LUTEAL_MAX} days before the next start.</li>
 * </ul>
 *
 * The likely ovulation day is the day before the first high night. A night far from its neighbours (more than
 * {@link #ODD_PER_NOISE} times the noise and at least {@link #ODD_MIN} °C, as with a fever or a warm room) is left out and
 * named; nights without a value (no ring night, a left out time, a short night) are missing. Pure Java on calendar dates,
 * nothing is stored: the screens work it out on each load.
 */
public final class TempRise {
    private TempRise() {}

    /** How sure a result is. */
    public static final int NOT_FOUND = 0, POSSIBLE = 1, CLEAR = 2;
    /** The nights the baseline is taken from, and the high nights that must follow. */
    public static final int LOW = 6, HIGH = 3;
    /** Every high night lies more than this above the baseline (°C; the Experiment view's rule). */
    public static final double MARGIN = 0.05;
    /** The third high night's distance above the baseline: this times the noise, from {@link #THIRD_MIN} to {@link #THIRD_MAX}. */
    public static final double THIRD_PER_NOISE = 1.0, THIRD_MIN = 0.1, THIRD_MAX = 0.2;
    /** The rise lasts: the high phase's average lies this many times the noise above the six, and at least {@link #LAST_MIN}. */
    public static final double LAST_PER_NOISE = 1.0, LAST_MIN = 0.1;
    /** Clear: the rise is at least this many times the noise, with no missing night and no fourth night needed. */
    public static final double CLEAR_PER_NOISE = 2.0;
    /** A night this many times the noise from the middle of its neighbours, and at least {@link #ODD_MIN} °C, is left out. */
    public static final double ODD_PER_NOISE = 4.0, ODD_MIN = 0.5;
    /** Neighbours each way for that check. */
    public static final int ODD_DAYS = 3;
    /** In a cycle that is over, ovulation lies this many days before the next start, both included. */
    public static final int LUTEAL_MIN = 7, LUTEAL_MAX = 19;
    /** The noise when a signal has too few nights to measure its own (°C): the ring's skin, and by mouth. */
    public static final double NOISE_RING = 0.15, NOISE_MOUTH = 0.1;
    /** Night to night steps needed to measure the noise. */
    public static final int NOISE_STEPS = 10;
    /** A ring night with less sleep than this (minutes) is too short for a steady temperature and counts as missing. */
    public static final double SHORT_NIGHT_MIN = 180;

    /** What one signal shows in one cycle. */
    public static final class Rise {
        /** {@link #CLEAR}, {@link #POSSIBLE} or {@link #NOT_FOUND}. */
        public final int confidence;
        /** Not found: too few nights in the cycle to look ({@link #FEW}), or no lasting rise ({@link #FLAT}). */
        public final int why;
        /** The first high night (by the day its morning falls on), and the likely ovulation day before it; null when not found. */
        public final LocalDate first, ovulation;
        /** The six nights of the baseline and the high nights (three, or four when a fourth confirmed), oldest first. */
        public final List<LocalDate> low, high;
        /** The night that dipped to the baseline between the high ones (the classic rule's one exception), else null. */
        public final LocalDate dipped;
        /** The values of those nights (°C). */
        public final TreeMap<LocalDate, Double> values;
        /** The highest of the six, their average, and the average from the first high night to the end of the cycle (°C). */
        public final double baseline, lowMean, highMean;
        /** How far above the baseline the third high night had to be, and the noise it was measured against (°C). */
        public final double third, noise;
        /** Nights in the cycle with a value, and those left out as far from their neighbours. */
        public final int nights;
        public final List<LocalDate> odd;

        Rise(int confidence, int why, LocalDate first, List<LocalDate> low, List<LocalDate> high, LocalDate dipped, TreeMap<LocalDate, Double> values, double baseline, double lowMean, double highMean,
                double third, double noise, int nights, List<LocalDate> odd) {
            this.confidence = confidence;
            this.why = why;
            this.first = first;
            this.ovulation = first == null ? null : first.minusDays(1);
            this.low = Collections.unmodifiableList(low);
            this.high = Collections.unmodifiableList(high);
            this.dipped = dipped;
            this.values = values;
            this.baseline = baseline;
            this.lowMean = lowMean;
            this.highMean = highMean;
            this.third = third;
            this.noise = noise;
            this.nights = nights;
            this.odd = Collections.unmodifiableList(odd);
        }

        public boolean found() {
            return confidence > NOT_FOUND;
        }

        /** The rise: the high phase's average minus the six nights' (°C), NaN when not found. */
        public double size() {
            return found() ? highMean - lowMean : Double.NaN;
        }

        @Override
        public String toString() {
            return found() ? (confidence == CLEAR ? "clear " : "possible ") + ovulation : why == FEW ? "few nights" : "no rise";
        }
    }

    /** Why nothing was found. */
    public static final int FOUND = 0, FEW = 1, FLAT = 2;

    /**
     * The night to night noise of {@code v} (°C): the middle of the steps between nights on following days, divided by 0.954
     * (for random noise of spread s the steps' middle size is 0.954 s; the one step of a rise barely moves it). Without
     * {@link #NOISE_STEPS} steps, {@code fallback}.
     */
    public static double noise(java.util.NavigableMap<LocalDate, Double> v, double fallback) {
        List<Double> steps = new ArrayList<Double>();
        LocalDate prev = null;
        double pv = 0;
        if (v != null) {
            for (Map.Entry<LocalDate, Double> e : v.entrySet()) {
                Double x = e.getValue();
                if (x == null || Double.isNaN(x)) continue;
                if (prev != null && ChronoUnit.DAYS.between(prev, e.getKey()) == 1) steps.add(Math.abs(x - pv));
                prev = e.getKey();
                pv = x;
            }
        }
        if (steps.size() < NOISE_STEPS) return fallback;
        double[] s = new double[steps.size()];
        for (int i = 0; i < s.length; i++) s[i] = steps.get(i);
        Arrays.sort(s);
        int n = s.length;
        double mid = n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
        return Math.max(0.01, mid / 0.954);
    }

    /** The third high night's distance above the baseline for a signal with {@code noise}. */
    public static double thirdNeed(double noise) {
        return Math.max(THIRD_MIN, Math.min(THIRD_MAX, THIRD_PER_NOISE * noise));
    }

    /**
     * The nights of {@code v} from {@code from} to {@code to} (both included) that are far from their neighbours: more than
     * max({@link #ODD_MIN}, {@link #ODD_PER_NOISE} × noise) from the middle of the nights up to {@link #ODD_DAYS} days each
     * way (at least 3 of them).
     */
    static List<LocalDate> odd(TreeMap<LocalDate, Double> v, LocalDate from, LocalDate to, double noise) {
        List<LocalDate> out = new ArrayList<LocalDate>();
        double limit = Math.max(ODD_MIN, ODD_PER_NOISE * noise);
        for (Map.Entry<LocalDate, Double> e : v.subMap(from, true, to, true).entrySet()) {
            if (!ok(e.getValue())) continue;
            List<Double> around = new ArrayList<Double>();
            for (Double x : v.subMap(e.getKey().minusDays(ODD_DAYS), true, e.getKey().plusDays(ODD_DAYS), true).values()) if (ok(x)) around.add(x);
            around.remove(e.getValue());
            if (around.size() < 3) continue;
            Collections.sort(around);
            int n = around.size();
            double mid = n % 2 == 1 ? around.get(n / 2) : (around.get(n / 2 - 1) + around.get(n / 2)) / 2;
            if (Math.abs(e.getValue() - mid) > limit) out.add(e.getKey());
        }
        return out;
    }

    static boolean ok(Double x) {
        return x != null && !Double.isNaN(x) && !Double.isInfinite(x);
    }

    /**
     * The rise in {@code v} (day to °C, by the day the morning falls on) in the cycle from {@code start}: up to the day
     * before {@code next} when the cycle is over ({@code next} not null), else up to {@code today}. {@code noise} is the
     * signal's own ({@link #noise}). Takes the first night where the rule holds and the rise lasts.
     */
    public static Rise find(java.util.NavigableMap<LocalDate, Double> v, LocalDate start, LocalDate next, LocalDate today, double noise) {
        LocalDate end = next != null ? next.minusDays(1) : today;
        TreeMap<LocalDate, Double> in = new TreeMap<LocalDate, Double>();
        if (v != null && !end.isBefore(start)) {
            // the neighbours for the odd night check may lie just outside the cycle
            for (Map.Entry<LocalDate, Double> e : v.subMap(start.minusDays(ODD_DAYS), true, end.plusDays(ODD_DAYS), true).entrySet()) {
                if (ok(e.getValue())) in.put(e.getKey(), e.getValue());
            }
        }
        List<LocalDate> odd = end.isBefore(start) ? new ArrayList<LocalDate>() : odd(in, start, end, noise);
        TreeMap<LocalDate, Double> c = new TreeMap<LocalDate, Double>(end.isBefore(start) ? in : in.subMap(start, true, end, true));
        for (LocalDate d : odd) c.remove(d);
        int nights = c.size();
        double third = thirdNeed(noise);
        List<LocalDate> none = new ArrayList<LocalDate>();
        if (nights < LOW + HIGH) return new Rise(NOT_FOUND, FEW, null, none, none, null, new TreeMap<LocalDate, Double>(), Double.NaN, Double.NaN, Double.NaN, third, noise, nights, odd);
        // the first high night: after six nights of the cycle, and in a cycle that is over within the luteal limits
        LocalDate lo = start.plusDays(LOW), hi = end.minusDays(HIGH - 1);
        if (next != null) {
            LocalDate a = next.minusDays(LUTEAL_MAX - 1), b = next.minusDays(LUTEAL_MIN - 1);
            if (a.isAfter(lo)) lo = a;
            if (b.isBefore(hi)) hi = b;
        }
        for (LocalDate f = lo; !f.isAfter(hi); f = f.plusDays(1)) {
            Double fv = c.get(f);
            if (fv == null) continue;
            // the six nights before, within eight days (two may be missing) and inside the cycle
            List<LocalDate> low = new ArrayList<LocalDate>();
            LocalDate from = f.minusDays(LOW + 2).isBefore(start) ? start : f.minusDays(LOW + 2);
            for (LocalDate d : c.subMap(from, true, f, false).descendingKeySet()) {
                if (low.size() == LOW) break;
                low.add(0, d);
            }
            if (low.size() < LOW) continue;
            double base = Double.NEGATIVE_INFINITY, lowSum = 0;
            for (LocalDate d : low) {
                base = Math.max(base, c.get(d));
                lowSum += c.get(d);
            }
            double lowMean = lowSum / LOW;
            if (fv <= base + MARGIN) continue;
            // the high nights: f and the nights after it, at most one missing day between two of them. One exception as in
            // the classic rule: a night that dips to the baseline, or a third night that falls short, needs a fourth high one
            List<LocalDate> high = new ArrayList<LocalDate>();
            high.add(f);
            boolean dip = false;
            int need = HIGH;
            LocalDate last = f;
            for (Map.Entry<LocalDate, Double> e = c.higherEntry(f); e != null && high.size() < need; e = c.higherEntry(e.getKey())) {
                if (ChronoUnit.DAYS.between(last, e.getKey()) > 2) break;
                last = e.getKey();
                if (e.getValue() > base + MARGIN) high.add(e.getKey());
                else if (dip) break;
                else {
                    dip = true;
                    need = HIGH + 1;
                }
            }
            if (high.size() < need) continue;
            boolean fourth = dip;
            if (!dip && c.get(high.get(HIGH - 1)) < base + third) {
                Map.Entry<LocalDate, Double> e4 = c.higherEntry(high.get(HIGH - 1));
                if (e4 == null || ChronoUnit.DAYS.between(high.get(HIGH - 1), e4.getKey()) > 2 || e4.getValue() <= base + MARGIN) continue;
                high.add(e4.getKey());
                fourth = true;
            } else if (dip) {
                // with a dip the rise must still reach the third night's height once
                double top = Double.NEGATIVE_INFINITY;
                for (LocalDate d : high) top = Math.max(top, c.get(d));
                if (top < base + third) continue;
            }
            // the rise lasts to the end of the cycle (or today)
            double hs = 0;
            int hn = 0;
            for (double x : c.subMap(f, true, end, true).values()) {
                hs += x;
                hn++;
            }
            double highMean = hs / hn;
            if (highMean - lowMean < Math.max(LAST_MIN, LAST_PER_NOISE * noise)) continue;
            boolean clear = !fourth && highMean - lowMean >= CLEAR_PER_NOISE * noise;
            TreeMap<LocalDate, Double> vals = new TreeMap<LocalDate, Double>();
            for (LocalDate d : low) vals.put(d, c.get(d));
            for (LocalDate d : high) vals.put(d, c.get(d));
            LocalDate dipped = null;
            if (dip) for (LocalDate d : c.subMap(f, true, high.get(high.size() - 1), true).keySet()) if (!high.contains(d)) dipped = d;
            if (dipped != null) vals.put(dipped, c.get(dipped));
            return new Rise(clear ? CLEAR : POSSIBLE, FOUND, f, low, high, dipped, vals, base, lowMean, highMean, third, noise, nights, odd);
        }
        return new Rise(NOT_FOUND, FLAT, null, none, none, null, new TreeMap<LocalDate, Double>(), Double.NaN, Double.NaN, Double.NaN, third, noise, nights, odd);
    }

    // ------------------------------------------------------------ each cycle, both signals, the own luteal length

    /** Which signal a cycle's result comes from. */
    public static final int RING = 1, MOUTH = 2, BOTH = 3;
    /** The ring and the mouth agree when their days lie at most this far apart. */
    public static final int AGREE_DAYS = 2;
    /** The own time from ovulation to the next period counts from this many cycles with a rise ({@code Cycles.FEW}). */
    public static final int LUTEAL_FROM = 3;
    /** The own time uses at most this many of the newest cycles with a rise ({@code Cycles.WINDOW}). */
    public static final int LUTEAL_WINDOW = 12;
    /**
     * A measured ovulation day, give or take this many days: clear, possible. In the made up cycles of the tests (a rise of
     * 0.3 °C, noise 0.1 to 0.2 °C, 15 % missing nights) 4 in 5 found days lay that close.
     */
    public static final int CLEAR_RANGE = 1, POSSIBLE_RANGE = 2;
    /** The spread of a measured day's error in those cycles (days): clear, possible. */
    public static final double CLEAR_SD = 1.0, POSSIBLE_SD = 1.8;

    /** One cycle: what each signal shows, and the answer from both. */
    public static final class Result {
        /** The cycle's first day, and the next period's (null for the cycle running today). */
        public final LocalDate start, next;
        public final Rise ring, mouth;
        /** {@link #CLEAR}, {@link #POSSIBLE} or {@link #NOT_FOUND}, from both signals. */
        public final int confidence;
        /** {@link #RING}, {@link #MOUTH} or {@link #BOTH} (0 when not found). */
        public final int source;
        /** Both found a rise, more than {@link #AGREE_DAYS} days apart. */
        public final boolean disagree;
        /** The likely ovulation day, null when not found. */
        public final LocalDate ovulation;

        Result(LocalDate start, LocalDate next, Rise ring, Rise mouth) {
            this.start = start;
            this.next = next;
            this.ring = ring;
            this.mouth = mouth;
            boolean r = ring.found(), m = mouth.found();
            if (r && m) {
                long apart = Math.abs(ChronoUnit.DAYS.between(ring.ovulation, mouth.ovulation));
                disagree = apart > AGREE_DAYS;
                // two signals that agree are clear; the day is the surer one's, else the earlier (a rise shows late rather than early)
                Rise pick = ring.confidence != mouth.confidence ? (ring.confidence > mouth.confidence ? ring : mouth)
                        : ring.ovulation.isAfter(mouth.ovulation) ? mouth : ring;
                ovulation = pick.ovulation;
                confidence = disagree ? POSSIBLE : CLEAR;
                source = BOTH;
            } else {
                disagree = false;
                Rise one = r ? ring : m ? mouth : null;
                ovulation = one == null ? null : one.ovulation;
                confidence = one == null ? NOT_FOUND : one.confidence;
                source = r ? RING : m ? MOUTH : 0;
            }
        }

        public boolean found() {
            return confidence > NOT_FOUND;
        }

        /** Days from the ovulation to the next period (the luteal phase), null while the cycle runs or without a rise. */
        public Integer luteal() {
            return next == null || ovulation == null ? null : (int) ChronoUnit.DAYS.between(ovulation, next);
        }

        @Override
        public String toString() {
            return start + " " + (found() ? (confidence == CLEAR ? "clear " : "possible ") + ovulation : "not found");
        }
    }

    /** Every cycle's result and what they give together. */
    public static final class Analysis {
        /** Oldest first; the cycle running today last, when there is one. */
        public final List<Result> cycles;
        /** The cycle running today, else null. */
        public final Result current;
        /** The night to night noise of each signal (°C). */
        public final double ringNoise, mouthNoise;
        /** The own luteal length: the middle value of the newest cycles with a rise, their spread, and how many. */
        public final double luteal, lutealSd;
        public final int lutealN;

        Analysis(List<Result> cycles, Result current, double ringNoise, double mouthNoise) {
            this.cycles = Collections.unmodifiableList(cycles);
            this.current = current;
            this.ringNoise = ringNoise;
            this.mouthNoise = mouthNoise;
            List<Integer> l = new ArrayList<Integer>();
            for (Result r : cycles) if (r.luteal() != null && !r.disagree) l.add(r.luteal());
            List<Integer> use = l.subList(Math.max(0, l.size() - LUTEAL_WINDOW), l.size());
            int[] v = new int[use.size()];
            for (int i = 0; i < v.length; i++) v[i] = use.get(i);
            lutealN = v.length;
            luteal = Cycles.median(v);
            lutealSd = Cycles.sd(v);
        }

        /** Enough cycles with a rise for the own luteal length. */
        public boolean ownLuteal() {
            return lutealN >= LUTEAL_FROM;
        }

        /** The own luteal length in whole days. */
        public int lutealDays() {
            return (int) Math.round(luteal);
        }

        /** The result of the cycle that holds {@code d}, else null. */
        public Result at(LocalDate d) {
            for (Result r : cycles) if (!d.isBefore(r.start) && (r.next == null || d.isBefore(r.next))) return r;
            return null;
        }

        /** Cycles that are over, and those of them with a rise. */
        public int over() {
            int n = 0;
            for (Result r : cycles) if (r.next != null) n++;
            return n;
        }

        public int overFound() {
            int n = 0;
            for (Result r : cycles) if (r.next != null && r.found()) n++;
            return n;
        }
    }

    /**
     * The rise in every cycle of {@code cy} (the cycles that are over, and the one running today) in the ring's nights
     * ({@code ring}: °C by the day the morning falls on, without left out and short nights) and in the morning temperatures
     * by mouth ({@code mouth}, those drip left out already removed), each with its own noise. Either may be empty or null.
     */
    public static Analysis analyse(Cycles cy, TreeMap<LocalDate, Double> ring, TreeMap<LocalDate, Double> mouth) {
        return analyse(cy, ring, mouth, false);
    }

    /**
     * As above; with {@code newest} only the cycles the estimate needs (issue #383, the backtest's replay): from the newest
     * back until {@link #LUTEAL_WINDOW} of them give a luteal length. {@link Analysis#current}, the luteal length and its
     * spread are the same as with every cycle; {@link Analysis#cycles} holds fewer.
     */
    static Analysis analyse(Cycles cy, TreeMap<LocalDate, Double> ring, TreeMap<LocalDate, Double> mouth, boolean newest) {
        TreeMap<LocalDate, Double> r = ring == null ? new TreeMap<LocalDate, Double>() : ring, m = mouth == null ? new TreeMap<LocalDate, Double>() : mouth;
        double rn = noise(r, NOISE_RING), mn = noise(m, NOISE_MOUTH);
        List<Result> out = new ArrayList<Result>();
        int withLuteal = 0;
        for (int i = cy.cycles.size() - 1; i >= 0 && !(newest && withLuteal >= LUTEAL_WINDOW); i--) {
            Cycles.Cycle c = cy.cycles.get(i);
            if (c.leftOut) continue; // maybe a period was not logged: two cycles in one, no luteal length from it
            Result res = new Result(c.start, c.next, find(r, c.start, c.next, cy.today, rn), find(m, c.start, c.next, cy.today, mn));
            out.add(0, res);
            if (res.luteal() != null && !res.disagree) withLuteal++;
        }
        Result current = null;
        if (!cy.periods.isEmpty()) {
            LocalDate s = cy.periods.get(cy.periods.size() - 1).start;
            if (!s.isAfter(cy.today) && !cy.waiting) {
                current = new Result(s, null, find(r, s, null, cy.today, rn), find(m, s, null, cy.today, mn));
                out.add(current);
            }
        }
        return new Analysis(out, current, rn, mn);
    }

    /** A ring night's temperature for the rise: NaN for a night shorter than {@link #SHORT_NIGHT_MIN} minutes asleep. */
    public static double ringNight(double temp, double asleepMin) {
        if (Double.isNaN(temp) || (!Double.isNaN(asleepMin) && asleepMin < SHORT_NIGHT_MIN)) return Double.NaN;
        return temp;
    }

    // ------------------------------------------------------------------------------- texts

    /** "your ring's temperature", "your mouth temperatures", "your ring's and your mouth temperatures". */
    public static String sourceWords(int source) {
        switch (source) {
            case RING: return "your ring's temperature";
            case MOUTH: return "your temperatures by mouth";
            default: return "your ring's and your mouth temperatures";
        }
    }

    /** One line under a cycle in "Your cycles": the day found and how sure, or why none. */
    public static String line(Result r, Cycles cy, java.util.Locale loc) {
        if (r.found()) {
            String s = (r.confidence == CLEAR ? "Ovulation likely on " : "Ovulation possibly on ") + cy.date(r.ovulation, loc);
            if (r.disagree) s += ": ring and mouth disagree by " + Math.abs(ChronoUnit.DAYS.between(r.ring.ovulation, r.mouth.ovulation)) + " days";
            else s += (r.confidence == CLEAR ? ", a clear rise in " : ", a weaker rise in ") + sourceWords(r.source);
            Integer l = r.luteal();
            if (l != null) s += ", " + app.openring.core.Texts.count(l, "day", "days") + " before the next period";
            return s + ".";
        }
        int nights = Math.max(r.ring.nights, r.mouth.nights);
        if (nights == 0) return "No temperatures in this cycle.";
        if (r.ring.why == FEW && r.mouth.why == FEW) {
            return r.next == null ? "Too few nights yet to look for a rise (" + (LOW + HIGH) + " needed)." : "Too few nights to look for a rise.";
        }
        return r.next == null ? "No lasting rise yet in this cycle." : "No clear rise found. That happens, also in cycles with an ovulation.";
    }

    /**
     * What touching a marked night reads for one signal ({@code name}: "Ring" or "Mouth"), short enough for the chart's
     * tip: the likely ovulation, a baseline night (the highest is the line), a high night and how far above the line, or
     * the one night that dipped. Null for any other night.
     */
    public static String nightNote(Rise r, LocalDate d, String name, java.util.Locale loc) {
        if (r == null || !r.found()) return null;
        if (d.equals(r.ovulation)) return name + ": likely ovulation, the day before the rise";
        Double v = r.values.get(d);
        if (v == null) return null;
        if (r.low.contains(d)) return name + ": baseline night " + (r.low.indexOf(d) + 1) + " of " + LOW + (v == r.baseline ? ", the highest: the line" : "");
        if (d.equals(r.dipped)) return name + ": dipped to the line, one such night is allowed";
        int i = r.high.indexOf(d);
        if (i < 0) return null;
        return name + ": high night " + (i + 1) + " of " + r.high.size() + ", " + String.format(loc, "%.2f", v - r.baseline) + " °C above the line";
    }
}
