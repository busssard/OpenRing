package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Decides where the sleeps are. Simplified port of open_health {@code normalize_bed_periods} and
 * {@code beds_from_sleep_signal}: the ring's own bedtime_period markers come first, ring staged
 * sleeps next, and runs of the ring's own sleep_state fill nights the ring never declared.
 * Adjacent pieces split by a short awakening are merged, but never across a gap the ring calls
 * awake from end to end, except a wake up in the middle of the night (issue #42): two pieces up to
 * 3 hours apart, the gap from 22:00 at the earliest to before 06:30, the piece before it begun at
 * 21:00 or later and the ring worn through the gap, are one night, and the time the ring calls
 * awake in the gap is returned as awake time. The night's edges are never stretched into awake time.
 */
public final class NightFinder {
    private NightFinder() {}

    static final double MAX_BREAK_S = 60 * 60;
    static final double MAX_EXTENSION_S = 3 * 60 * 60;
    static final double MAX_SUPPORT_GAP_S = 30 * 60;
    static final double MIN_DERIVED_S = 45 * 60;
    static final double MIN_PERIOD_S = 15 * 60;
    static final int SMOOTHING = 5;
    /** Longest wake up in the middle of the night that still joins two pieces (issue #42). */
    static final double MAX_NIGHT_WAKE_S = 3 * 60 * 60;
    /**
     * Such a wake up starts at 22:00 or later and ends before 06:30, and the sleep before it began
     * at 21:00 or later (clock minutes): a sofa sleep in the evening or a nap after getting up stays apart.
     */
    static final int WAKE_FROM_MIN = 22 * 60, WAKE_TO_MIN = 6 * 60 + 30, SLEEP_FROM_MIN = 21 * 60;
    /** Version of these rules; a new one makes the app analyse all nights again. */
    public static final int VERSION = 2;

    /** A local clock, so the finder can tell night from day without Android. */
    public interface LocalClock {
        /**
         * Minutes since local midnight at {@code unix}, on the clock of the zone a sleep ending at {@code end}
         * was slept in (issue #161: never the phone's zone now, which differs after a trip).
         */
        int minuteOfDay(double unix, double end);
    }

    public static final class Period {
        public double start, end;
        public boolean staged;
        public int[] codes; // ring hypnogram when staged
        /**
         * When the ring's hypnogram really ends (issue #110). It stays put when a merge makes the period
         * longer (a bedtime period around or after the ring's run), so the stages keep their clock times.
         * NaN when not staged.
         */
        public double codesEnd = Double.NaN;
        /** Wake ups in the middle of the night the ring calls awake {start, end} (issue #42). */
        public List<double[]> awake = new ArrayList<double[]>();

        public Period(double s, double e) {
            start = s;
            end = e;
        }

        /** The ring's hypnogram at its real times; a period without {@link #codesEnd} ends it at {@link #end}. */
        public RingRun ringRun() {
            if (codes == null) return null;
            double e = Double.isNaN(codesEnd) ? end : codesEnd;
            return new RingRun(e - codes.length * 30.0, e, codes);
        }
    }

    public static final class RingRun {
        public final double start, end;
        public final int[] codes;

        public RingRun(double start, double end, int[] codes) {
            this.start = start;
            this.end = end;
            this.codes = codes;
        }
    }

    /**
     * @param bedtimes explicit bedtime_period windows {start, end}
     * @param runs     ring staged sleeps
     * @param state    sleep_state samples (0 awake, 1 or 2 asleep)
     * @param support  times of nocturnal only events (value ignored)
     */
    public static List<Period> find(List<double[]> bedtimes, List<RingRun> runs, TS state, TS support) {
        return find(bedtimes, runs, state, support, null);
    }

    /** As above; with a {@code clock} a long wake up in the middle of the night no longer splits it. */
    public static List<Period> find(List<double[]> bedtimes, List<RingRun> runs, TS state, TS support, LocalClock clock) {
        state.sort();
        support.sort();
        List<double[]> asleep = asleepRegions(state);
        List<Period> periods = new ArrayList<Period>();
        for (double[] b : bedtimes) if (b[1] > b[0]) periods.add(new Period(b[0], b[1]));
        for (RingRun r : runs) {
            if (r.end - r.start < MIN_DERIVED_S) continue;
            Period p = new Period(r.start, r.end);
            p.staged = true;
            p.codes = r.codes;
            p.codesEnd = r.end;
            periods.add(p);
        }
        // nights the ring never declared: its own sleep_state, or failing that nocturnal events
        List<double[]> derived = new ArrayList<double[]>(asleep);
        if (asleep.isEmpty()) derived = runsOf(support);
        for (double[] d : derived) {
            if (d[1] - d[0] < MIN_DERIVED_S) continue;
            if (covered(periods, d[0], d[1])) continue;
            boolean overlapsRing = false;
            for (RingRun r : runs) if (r.start < d[1] && d[0] < r.end) overlapsRing = true;
            if (overlapsRing) continue;
            periods.add(new Period(d[0], d[1]));
        }
        sort(periods);
        periods = merge(periods, asleep, state, clock);

        // carry a declared night on while sleep signals keep coming (never across a staged start)
        for (Period p : periods) {
            if (p.staged) continue;
            double wall = Double.POSITIVE_INFINITY;
            for (Period q : periods) if (q.staged && q.start > p.end) wall = Math.min(wall, q.start);
            double end0 = p.end;
            for (int i = 0; i < support.size(); i++) {
                double t = support.t(i);
                if (t < end0 || t > end0 + MAX_EXTENSION_S || t > wall) continue;
                // only carry on into time the ring itself calls asleep: lying awake in the
                // morning (or the phone syncing) must not stretch the night
                if (!asleep.isEmpty() && (!inside(asleep, t) || !asleepNear(state, t))) continue;
                if (t - p.end > MAX_SUPPORT_GAP_S) break;
                p.end = Math.max(p.end, t);
            }
        }
        periods = merge(periods, asleep, state, clock);
        List<Period> out = new ArrayList<Period>();
        for (Period p : periods) if (p.end - p.start >= MIN_PERIOD_S) out.add(p);
        return out;
    }

    static void sort(List<Period> ps) {
        Collections.sort(ps, new Comparator<Period>() {
            @Override
            public int compare(Period a, Period b) {
                return Double.compare(a.start, b.start);
            }
        });
    }

    static boolean covered(List<Period> ps, double s, double e) {
        for (Period p : ps) if (p.start <= s && e <= p.end) return true;
        return false;
    }

    static boolean inside(List<double[]> regions, double t) {
        for (double[] r : regions) if (t >= r[0] && t <= r[1]) return true;
        return false;
    }

    /** The ring's nearest sleep_state sample within 5 minutes says asleep. */
    static boolean asleepNear(TS state, double t) {
        int n = state.size();
        if (n == 0) return true;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (state.t(mid) < t) lo = mid + 1;
            else hi = mid;
        }
        int best = lo;
        if (lo > 0 && Math.abs(state.t(lo - 1) - t) < Math.abs(state.t(lo) - t)) best = lo - 1;
        return Math.abs(state.t(best) - t) <= 300 && state.v(best) >= 1;
    }

    static List<Period> merge(List<Period> ps, List<double[]> asleep) {
        return merge(ps, asleep, new TS(), null);
    }

    static List<Period> merge(List<Period> ps, List<double[]> asleep, TS state, LocalClock clock) {
        double[] nightEnd = nightEnds(ps);
        List<Period> out = new ArrayList<Period>();
        for (int i = 0; i < ps.size(); i++) {
            Period p = ps.get(i);
            if (out.isEmpty()) {
                out.add(p);
                continue;
            }
            Period prev = out.get(out.size() - 1);
            double gap = p.start - prev.end;
            boolean awakeThroughout = false;
            if (!asleep.isEmpty() && gap > 0) {
                awakeThroughout = true;
                for (double[] r : asleep) if (r[0] < p.start && prev.end < r[1]) awakeThroughout = false;
            }
            boolean join = gap <= MAX_BREAK_S && !awakeThroughout;
            if (!join && nightWake(prev.start, prev.end, p.start, nightEnd[i], state, clock)) {
                List<double[]> gapAwake = awakeParts(prev.end, p.start, asleep);
                // ring stages on either side keep their own times; the gap is awake (issue #42)
                if (prev.staged || p.staged) {
                    prev.codes = stitch(prev, p, gapAwake);
                    prev.staged = true;
                    prev.codesEnd = Math.max(prev.end, p.end); // stitch ends its grid at the joined end
                }
                prev.awake.addAll(gapAwake);
                prev.awake.addAll(p.awake);
                prev.end = Math.max(prev.end, p.end);
                prev.start = Math.min(prev.start, p.start);
                continue;
            }
            if (join) {
                prev.awake.addAll(p.awake);
                if (p.staged && !prev.staged) {
                    // the codes keep their own end, the period may grow past it (issue #110)
                    prev.codes = p.codes;
                    prev.codesEnd = p.codesEnd;
                    prev.staged = true;
                }
                prev.end = Math.max(prev.end, p.end);
                prev.start = Math.min(prev.start, p.start);
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * For each piece, the end of the last piece reached from it through gaps of at most 3 hours (the longest
     * night wake): the end of the night it may join. Every piece of one night gets the same end, so the zone
     * its clock is read in is decided once per night, not per piece (issue #161, review of PR #206).
     */
    static double[] nightEnds(List<Period> ps) {
        double[] out = new double[ps.size()];
        int i = 0;
        while (i < ps.size()) {
            double end = ps.get(i).end;
            int j = i + 1;
            while (j < ps.size() && ps.get(j).start - end <= MAX_NIGHT_WAKE_S) {
                end = Math.max(end, ps.get(j).end);
                j++;
            }
            for (int k = i; k < j; k++) out[k] = end;
            i = j;
        }
        return out;
    }

    /** Minutes since 12:00 the day before, so a night's clock times count up across midnight. */
    static int fromNoon(LocalClock clock, double unix, double end) {
        return (clock.minuteOfDay(unix, end) + 12 * 60) % (24 * 60);
    }

    /**
     * A gap {@code [a, b]} after a sleep that began at {@code sleepStart} is a wake up in the middle
     * of the night: at most 3 hours, from 22:00 at the earliest to before 06:30, the sleep before it
     * begun at 21:00 or later, and the ring reporting its sleep state at least every 30 minutes
     * through it (a ring taken off is not a wake up). All three times are read on the clock of the
     * zone the sleep was slept in, decided from the end of the whole night ({@code end}, see
     * {@link #nightEnds}; issue #161).
     */
    static boolean nightWake(double sleepStart, double a, double b, double end, TS state, LocalClock clock) {
        if (clock == null || b <= a || b - a > MAX_NIGHT_WAKE_S) return false;
        int from = fromNoon(clock, a, end), to = fromNoon(clock, b, end), began = fromNoon(clock, sleepStart, end);
        int wakeFrom = WAKE_FROM_MIN - 12 * 60, wakeTo = WAKE_TO_MIN + 12 * 60, sleepFrom = SLEEP_FROM_MIN - 12 * 60;
        if (from < wakeFrom || to >= wakeTo || to < from || began < sleepFrom || began > from) return false;
        // first sample at or after a (state is sorted)
        int lo = 0, hi = state.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (state.t(mid) < a) lo = mid + 1;
            else hi = mid;
        }
        double last = a;
        for (int i = lo; i < state.size() && state.t(i) <= b; i++) {
            if (state.t(i) - last > MAX_SUPPORT_GAP_S) return false;
            last = state.t(i);
        }
        return b - last <= MAX_SUPPORT_GAP_S;
    }

    /**
     * Ring stages for two pieces joined across a night wake: the first piece's codes, awake (4) in
     * the gap where the ring says awake, the second piece's codes, 0 (unknown) where a piece has
     * none. Each piece's codes end at that piece's {@link Period#codesEnd} (issue #110), and the
     * result ends at the joined end, so every code keeps its real time.
     */
    static int[] stitch(Period a, Period b, List<double[]> gapAwake) {
        double end = Math.max(a.end, b.end), start = Math.min(a.start, b.start);
        int cells = (int) Math.max(1, Math.ceil((end - start) / 30 - 1e-9));
        double base = end - cells * 30.0;
        int[] out = new int[cells];
        for (double[] g : gapAwake) {
            for (int i = 0; i < cells; i++) {
                double t = base + i * 30.0;
                if (t >= g[0] && t + 30 <= g[1] + 1e-9) out[i] = 4;
            }
        }
        place(out, base, a);
        place(out, base, b);
        return out;
    }

    private static void place(int[] out, double base, Period p) {
        if (!p.staged || p.codes == null) return;
        double runStart = p.ringRun().start;
        for (int i = 0; i < p.codes.length; i++) {
            int idx = (int) Math.round((runStart + i * 30.0 - base) / 30);
            if (idx >= 0 && idx < out.length) out[idx] = p.codes[i];
        }
    }

    /** The parts of {@code [a, b]} outside the ring's asleep regions. */
    public static List<double[]> awakeParts(double a, double b, List<double[]> asleep) {
        List<double[]> out = new ArrayList<double[]>();
        double t = a;
        for (double[] r : asleep) {
            if (r[1] <= t || r[0] >= b) continue;
            if (r[0] > t) out.add(new double[] {t, r[0]});
            t = Math.max(t, r[1]);
        }
        if (t < b) out.add(new double[] {t, b});
        return out;
    }

    /** Smoothed stretches the ring calls sleep (majority over 5 neighbouring windows). */
    public static List<double[]> asleepRegions(TS state) {
        List<double[]> regions = new ArrayList<double[]>();
        int n = state.size();
        if (n < SMOOTHING) return regions;
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - SMOOTHING / 2), hi = Math.min(n, i + SMOOTHING / 2 + 1);
            int a = 0;
            for (int k = lo; k < hi; k++) if (state.v(k) >= 1) a++;
            if (a * 2 <= hi - lo) continue;
            double t = state.t(i);
            if (!regions.isEmpty() && t - regions.get(regions.size() - 1)[1] <= MAX_BREAK_S) {
                regions.get(regions.size() - 1)[1] = t;
            } else {
                regions.add(new double[] {t, t});
            }
        }
        return regions;
    }

    static List<double[]> runsOf(TS support) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < support.size(); i++) {
            double t = support.t(i);
            if (!out.isEmpty() && t - out.get(out.size() - 1)[1] <= MAX_BREAK_S) {
                out.get(out.size() - 1)[1] = t;
            } else {
                out.add(new double[] {t, t});
            }
        }
        return out;
    }

    /**
     * Assemble ring hypnogram pages into staged sleeps. Pages arrive in a burst with page index
     * counting up; the run ends at the last page's emission time and runs back 30 s per epoch.
     */
    public static List<RingRun> ringRuns(List<double[]> pageTimes, List<int[]> pageIndexAndCodes) {
        List<RingRun> runs = new ArrayList<RingRun>();
        List<int[]> codes = new ArrayList<int[]>();
        double runEnd = 0;
        int prevPage = -10;
        for (int i = 0; i < pageTimes.size(); i++) {
            int[] pc = pageIndexAndCodes.get(i);
            int page = pc[0];
            int[] c = java.util.Arrays.copyOfRange(pc, 1, pc.length);
            if (page == prevPage + 1 && !codes.isEmpty()) {
                codes.add(c);
                runEnd = pageTimes.get(i)[0];
            } else {
                if (!codes.isEmpty()) runs.add(build(runEnd, codes));
                codes = new ArrayList<int[]>();
                codes.add(c);
                runEnd = pageTimes.get(i)[0];
            }
            prevPage = page;
        }
        if (!codes.isEmpty()) runs.add(build(runEnd, codes));
        return runs;
    }

    private static RingRun build(double end, List<int[]> pages) {
        int total = 0;
        for (int[] p : pages) total += p.length;
        int[] all = new int[total];
        int k = 0;
        for (int[] p : pages) for (int x : p) all[k++] = x;
        return new RingRun(end - total * 30.0, end, all);
    }

    /** Ring hypnogram cells re-gridded onto a period's 30 s epochs (0 where not covered). */
    public static int[] align(RingRun run, double start, double end) {
        int cells = (int) Math.max(1, Math.floor((end - start) / 30));
        int[] out = new int[cells];
        for (int i = 0; i < run.codes.length; i++) {
            double at = run.start + i * 30.0;
            if (at < start || at >= end) continue;
            int idx = Math.min(cells - 1, (int) ((at - start) / 30));
            out[idx] = run.codes[i];
        }
        return out;
    }
}
