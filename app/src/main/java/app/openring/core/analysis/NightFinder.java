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
 * awake from end to end.
 */
public final class NightFinder {
    private NightFinder() {}

    static final double MAX_BREAK_S = 60 * 60;
    static final double MAX_EXTENSION_S = 3 * 60 * 60;
    static final double MAX_SUPPORT_GAP_S = 30 * 60;
    static final double MIN_DERIVED_S = 45 * 60;
    static final double MIN_PERIOD_S = 15 * 60;
    static final int SMOOTHING = 5;

    public static final class Period {
        public double start, end;
        public boolean staged;
        public int[] codes; // ring hypnogram when staged

        public Period(double s, double e) {
            start = s;
            end = e;
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
        periods = merge(periods, asleep);

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
        periods = merge(periods, asleep);
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
        List<Period> out = new ArrayList<Period>();
        for (Period p : ps) {
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
            if (gap <= MAX_BREAK_S && !awakeThroughout) {
                if (p.staged && !prev.staged) {
                    prev.codes = p.codes;
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
