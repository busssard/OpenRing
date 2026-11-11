package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.openring.core.analysis.cycleforecast.Methods;

/**
 * How close the estimate from this cycle's temperature rise would have been (issue #383), against the calendar on the same
 * days. For each cycle that is over and has temperatures, the app is replayed ({@link Cycles#replay}) day by day from the
 * first day a rise could be confirmed, with the log and the nights up to that day only, until it shows the measured
 * estimate; that estimate and the calendar's of the same day are compared with the logged start. Cycles where no rise
 * showed in time are not counted. Nothing is stored.
 */
public final class RiseBacktest {
    /** "Best for you" leaves the temperature out only when at least this many cycles say it was further off. */
    public static final int MIN = 3;

    /** One replayed cycle. */
    public static final class Case {
        /** The cycle's first day, the logged next start, and the day the measured estimate first showed. */
        public final LocalDate start, next, day;
        /** The measured and the calendar's expected start on that day, and their ranges each way. */
        public final LocalDate measured, calendar;
        public final int range, calendarRange;

        Case(LocalDate start, LocalDate next, LocalDate day, LocalDate measured, int range, LocalDate calendar, int calendarRange) {
            this.start = start;
            this.next = next;
            this.day = day;
            this.measured = measured;
            this.range = range;
            this.calendar = calendar;
            this.calendarRange = calendarRange;
        }

        /** Days the period came after the measured (calendar) expected day; below 0 earlier. */
        public int late() {
            return (int) ChronoUnit.DAYS.between(measured, next);
        }

        public int calendarLate() {
            return (int) ChronoUnit.DAYS.between(calendar, next);
        }

        @Override
        public String toString() {
            return start + ": on " + day + " measured " + measured + ", calendar " + calendar + ", came " + next;
        }
    }

    /** The calendar method the measured estimate sits on (drip style is replayed on Open Ring: it never uses temperature). */
    public final int method;
    public final List<Case> cases;
    /** Mean errors in days (NaN without a case), and how many starts fell in each range. */
    public final double mae, calendarMae;
    public final int hits, calendarHits;

    private RiseBacktest(int method, List<Case> cases) {
        this.method = method;
        this.cases = Collections.unmodifiableList(cases);
        double a = 0, b = 0;
        int h = 0, ch = 0;
        for (Case c : cases) {
            a += Math.abs(c.late());
            b += Math.abs(c.calendarLate());
            if (Math.abs(c.late()) <= c.range) h++;
            if (Math.abs(c.calendarLate()) <= c.calendarRange) ch++;
        }
        mae = cases.isEmpty() ? Double.NaN : a / cases.size();
        calendarMae = cases.isEmpty() ? Double.NaN : b / cases.size();
        hits = h;
        calendarHits = ch;
    }

    public int n() {
        return cases.size();
    }

    /** Whether the temperature may change the estimate: fewer than {@link #MIN} cases, or it was not further off. */
    public boolean helps() {
        return n() < MIN || mae <= calendarMae + 1e-9;
    }

    /** Replays the cycles of {@code full} (its log, its nights) that are over and have a temperature in them. */
    static RiseBacktest run(Cycles full) {
        int method = Methods.usesTemperature(full.methodId) ? full.methodId : Methods.OPEN_RING;
        List<Case> out = new ArrayList<Case>();
        if (full.rise == null) return new RiseBacktest(method, out);
        // the days only move forward, so each signal's noise up to the day is kept as a running middle value
        Noise ringNoise = new Noise(full.ring, TempRise.NOISE_RING), mouthNoise = new Noise(full.mouth, TempRise.NOISE_MOUTH);
        // every cycle that is over, also those the whole history leaves out (which ones that is depends on later cycles)
        for (Cycles.Cycle r : full.cycles) {
            // a rise needs six nights and three high ones: without that many nights in the cycle none can show
            if (Math.max(count(full.ring, r.start, r.next), count(full.mouth, r.start, r.next)) < TempRise.LOW + TempRise.HIGH) continue;
            LocalDate last = r.next.minusDays(1);
            for (LocalDate d = r.start.plusDays(TempRise.LOW + TempRise.HIGH - 1); !d.isAfter(last); d = d.plusDays(1)) {
                // first the running cycle alone, as the replay would see it on d (each signal's noise from the nights up to d):
                // the whole replay only on a day a rise shows, so years of nights stay quick
                if (!shows(full.ring, ringNoise, r.start, d) && !shows(full.mouth, mouthNoise, r.start, d)) continue;
                Cycles c = Cycles.replay(full, d, method);
                if (c.method != Cycles.MEASURED || c.periods.isEmpty() || !c.periods.get(c.periods.size() - 1).start.equals(r.start)) continue;
                out.add(new Case(r.start, r.next, d, c.next, c.range, c.calendarNext, c.calendarRange));
                break;
            }
        }
        return new RiseBacktest(method, out);
    }

    /** Nights of {@code v} from {@code from} to the day before {@code to}. */
    static int count(java.util.TreeMap<LocalDate, Double> v, LocalDate from, LocalDate to) {
        return v == null ? 0 : v.subMap(from, true, to, false).size();
    }

    /**
     * Whether {@code v} shows a rise in the cycle from {@code start} on {@code d}, as {@link TempRise#analyse} would with the
     * nights up to {@code d}: the noise from all of them, the rise from the cycle's own (and the {@link TempRise#ODD_DAYS}
     * before it, for the odd night check).
     */
    static boolean shows(java.util.TreeMap<LocalDate, Double> v, Noise noise, LocalDate start, LocalDate d) {
        if (v == null || v.isEmpty()) return false;
        java.util.TreeMap<LocalDate, Double> cycle = new java.util.TreeMap<LocalDate, Double>(v.subMap(start.minusDays(TempRise.ODD_DAYS), true, d, true));
        return TempRise.find(cycle, start, null, d, noise.upTo(d)).found();
    }

    /**
     * {@link TempRise#noise} of the nights up to a day that only moves forward: the steps between nights on following days
     * are added in order to two heaps (the lower and the upper half), so each answer is the same number without sorting
     * every step again.
     */
    static final class Noise {
        private final List<LocalDate> at = new ArrayList<LocalDate>();
        private final List<Double> steps = new ArrayList<Double>();
        private final java.util.PriorityQueue<Double> low = new java.util.PriorityQueue<Double>(16, Collections.<Double>reverseOrder()),
                high = new java.util.PriorityQueue<Double>();
        private final double fallback;
        private int added;

        Noise(java.util.TreeMap<LocalDate, Double> v, double fallback) {
            this.fallback = fallback;
            LocalDate prev = null;
            double pv = 0;
            if (v == null) return;
            for (java.util.Map.Entry<LocalDate, Double> e : v.entrySet()) {
                Double x = e.getValue();
                if (x == null || Double.isNaN(x)) continue;
                if (prev != null && ChronoUnit.DAYS.between(prev, e.getKey()) == 1) {
                    at.add(e.getKey());
                    steps.add(Math.abs(x - pv));
                }
                prev = e.getKey();
                pv = x;
            }
        }

        /** The noise of the nights up to {@code d}; {@code d} never earlier than the last call's. */
        double upTo(LocalDate d) {
            while (added < at.size() && !at.get(added).isAfter(d)) {
                double x = steps.get(added++);
                if (low.isEmpty() || x <= low.peek()) low.add(x);
                else high.add(x);
                if (low.size() > high.size() + 1) high.add(low.poll());
                else if (high.size() > low.size()) low.add(high.poll());
            }
            int n = low.size() + high.size();
            if (n < TempRise.NOISE_STEPS) return fallback;
            double mid = n % 2 == 1 ? low.peek() : (low.peek() + high.peek()) / 2;
            return Math.max(0.01, mid / 0.954);
        }
    }
}
