package app.openring.core.analysis;

/**
 * Turns one detected sleep window plus its nocturnal streams into a {@link Night}: hypnogram,
 * durations, clinical sleep metrics (port of open_health {@code sleep_metrics}), vitals and the
 * nightly temperature (port of ecore {@code nightly_temperature_calculate}).
 */
public final class NightAnalyzer {
    private NightAnalyzer() {}

    /** Raw inputs for one window. All series are unix seconds. */
    public static final class Input {
        public double start, end;
        public TS hr5 = new TS();       // 5 minute average HR (hrv_event) or per window HR
        public TS hrv5 = new TS();      // 5 minute RMSSD
        public TS beats = new TS();     // beat level HR (green IBI quality, aohr)
        public TS motion = new TS();    // motion seconds per window
        public TS temp = new TS();      // skin temperature C
        public TS spo2 = new TS();      // SpO2 percent
        public TS breath = new TS();    // breaths per minute from asleep windows
        public TS sleepState = new TS();// ring sleep_state (0 awake, 1 or 2 asleep)
        public int[] ringStages;        // optional ring hypnogram aligned to start, 30 s epochs
    }

    public static Night analyze(Input in) {
        Night n = new Night();
        n.start = in.start;
        n.end = in.end;
        n.inBedMin = (in.end - in.start) / 60.0;

        TS hr = in.hr5.isEmpty() ? bin(in.beats, in.start, in.end, 300) : in.hr5;
        hr = hr.filter(30, 140, in.start - 600, in.end + 600).sort();
        TS hrv = in.hrv5.filter(1, 250, in.start - 600, in.end + 600).sort();
        n.hr = hr;
        n.hrv = hrv;
        n.motion = in.motion.sort();

        // ---- staging ----
        int[] stages = null;
        if (in.ringStages != null && covered(in.ringStages) >= 0.6) {
            stages = in.ringStages;
            n.stageSource = "ring";
        }
        if (stages == null) {
            int[] s = Stager.stageFromSignals(in.start, in.end, hr, hrv, in.motion);
            if (s.length > 0) {
                stages = s;
                n.stageSource = "signals";
            }
        }
        if (stages == null) {
            int[] s = Stager.stageFromSleepState(in.start, in.end, in.sleepState);
            if (s.length > 0 && covered(s) >= 0.5) {
                stages = s;
                n.stageSource = "ring state";
            }
        }
        if (stages == null) {
            int epochs = (int) Math.max(1, Math.floor((in.end - in.start) / 30));
            stages = new int[epochs];
            java.util.Arrays.fill(stages, Stager.LIGHT);
            n.stageSource = "window";
        }
        n.stages = stages;
        metrics(n, stages, in);

        // ---- vitals ----
        double from = Double.isNaN(n.sleepStart) ? in.start : n.sleepStart;
        double to = Double.isNaN(n.sleepEnd) ? in.end : n.sleepEnd;
        TS hrSleep = hr.filter(30, 140, from - 300, to + 300);
        if (!hrSleep.isEmpty()) {
            n.avgHr = hrSleep.mean();
            // lowest 5 minute average, as Oura reports "lowest resting heart rate"
            int idx = -1;
            for (int i = 0; i < hrSleep.size(); i++) if (idx < 0 || hrSleep.v(i) < hrSleep.v(idx)) idx = i;
            n.lowestHr = hrSleep.v(idx);
            n.lowestHrTime = hrSleep.t(idx);
        }
        TS hrvSleep = hrv.filter(1, 250, from - 300, to + 300);
        if (!hrvSleep.isEmpty()) {
            n.avgHrv = hrvSleep.mean();
            n.maxHrv = hrvSleep.max();
        }
        TS br = in.breath.filter(5, 40, from, to);
        if (br.size() >= 3) n.breath = br.median();
        TS sp = in.spo2.filter(70, 100, from, to).sort();
        if (sp.size() >= 10) {
            n.spo2 = sp.mean();
            n.spo2Low = Stats.percentile(sp.values(), 0.05);
            n.spo2Series = bin(sp, in.start, in.end, 300);
        }
        TS tp = in.temp.filter(25, 42, from, to).sort();
        n.tempSeries = bin(tp, in.start, in.end, 300);
        n.temp = nightlyTemperature(tp.values());
        if (Double.isNaN(n.temp) && tp.size() > 0) n.temp = tp.median();

        // HRV by stage
        n.hrvDeep = stageMean(hrv, stages, in.start, in.end, Stager.DEEP);
        n.hrvRem = stageMean(hrv, stages, in.start, in.end, Stager.REM);
        return n;
    }

    static double covered(int[] s) {
        if (s.length == 0) return 0;
        int c = 0;
        for (int x : s) if (x >= 1 && x <= 4) c++;
        return c / (double) s.length;
    }

    static void metrics(Night n, int[] st, Input in) {
        int len = st.length;
        double epochMin = n.inBedMin / Math.max(1, len);
        int onset = -1, last = -1;
        int deep = 0, light = 0, rem = 0, wake = 0, unknown = 0;
        for (int i = 0; i < len; i++) {
            int c = st[i];
            if (c >= 1 && c <= 3) {
                if (onset < 0) onset = i;
                last = i;
            }
            if (c == 1) deep++;
            else if (c == 2) light++;
            else if (c == 3) rem++;
            else if (c == 4) wake++;
            else unknown++;
        }
        n.deepMin = deep * epochMin;
        n.lightMin = light * epochMin;
        n.remMin = rem * epochMin;
        n.awakeMin = wake * epochMin;
        n.asleepMin = (deep + light + rem) * epochMin;
        if (onset < 0) return;
        int known = len - unknown;
        n.efficiency = known > 0 ? 100.0 * (deep + light + rem) / known : Double.NaN;
        n.latencyMin = onset * epochMin;
        n.sleepStart = n.start + onset * epochMin * 60;
        n.sleepEnd = n.start + (last + 1) * epochMin * 60;
        n.midpoint = (n.sleepStart + n.sleepEnd) / 2;

        int waso = 0;
        for (int i = onset; i <= last; i++) if (st[i] == 4) waso++;
        n.wasoMin = waso * epochMin;
        int minWake = Math.max(1, (int) Math.ceil(1.0 / epochMin));
        n.awakenings = countBouts(st, onset, last + 1, 4, minWake);
        for (int i = onset; i < len; i++) {
            if (st[i] == 3) { n.remLatencyMin = (i - onset) * epochMin; break; }
        }
        int mergeGap = Math.max(1, (int) Math.round(15.0 / epochMin));
        int minRem = Math.max(1, (int) Math.ceil(3.0 / epochMin));
        n.cycles = countPeriods(st, onset, last + 1, 3, mergeGap, minRem);

        // restlessness: share of motion windows inside the sleep period that saw movement
        int moving = 0, total = 0;
        for (int i = 0; i < in.motion.size(); i++) {
            double t = in.motion.t(i);
            if (t < n.sleepStart || t > n.sleepEnd) continue;
            total++;
            if (in.motion.v(i) >= 2) moving++;
        }
        if (total >= 10) n.restlessPct = 100.0 * moving / total;
    }

    static int countBouts(int[] seq, int from, int to, int code, int minLen) {
        int count = 0, run = 0;
        for (int i = from; i < to; i++) {
            if (seq[i] == code) {
                run++;
            } else {
                if (run >= minLen) count++;
                run = 0;
            }
        }
        if (run >= minLen) count++;
        return count;
    }

    static int countPeriods(int[] seq, int from, int to, int code, int mergeGap, int minLen) {
        java.util.List<int[]> runs = new java.util.ArrayList<int[]>();
        int i = from;
        while (i < to) {
            if (seq[i] == code) {
                int s = i;
                while (i < to && seq[i] == code) i++;
                runs.add(new int[] {s, i});
            } else {
                i++;
            }
        }
        if (runs.isEmpty()) return 0;
        java.util.List<int[]> merged = new java.util.ArrayList<int[]>();
        merged.add(runs.get(0));
        for (int k = 1; k < runs.size(); k++) {
            int[] lastRun = merged.get(merged.size() - 1);
            int[] r = runs.get(k);
            if (r[0] - lastRun[1] < mergeGap) lastRun[1] = r[1];
            else merged.add(r);
        }
        int c = 0;
        for (int[] r : merged) if (r[1] - r[0] >= minLen) c++;
        return c;
    }

    static double stageMean(TS series, int[] stages, double start, double end, int code) {
        if (stages.length == 0 || series.isEmpty()) return Double.NaN;
        double span = Math.max(1, end - start);
        double s = 0;
        int c = 0;
        for (int i = 0; i < series.size(); i++) {
            double f = (series.t(i) - start) / span;
            if (f < 0 || f > 1) continue;
            int idx = Math.min(stages.length - 1, (int) (f * stages.length));
            if (stages[idx] == code) {
                s += series.v(i);
                c++;
            }
        }
        return c == 0 ? Double.NaN : s / c;
    }

    /** Mean of the samples falling in each {@code width} second bucket, placed at bucket centres. */
    public static TS bin(TS in, double from, double to, double width) {
        TS out = new TS();
        if (in.isEmpty() || to <= from) return out;
        int bins = (int) Math.max(1, Math.ceil((to - from) / width));
        double[] m = in.binMeans(from, from + bins * width, bins);
        for (int i = 0; i < bins; i++) if (!Double.isNaN(m[i])) out.add(from + (i + 0.5) * width, m[i]);
        return out;
    }

    /**
     * ecore nightly_temperature: 7 sample sliding median, 30 sample windows, each window
     * contributes its max when its range is under 2.5 C; the result is the minimum of those maxima
     * (needs 4 windows). Returns NaN when there are not enough windows.
     */
    public static double nightlyTemperature(double[] tempsC) {
        int[] ring = new int[7];
        int idx = 0;
        int winMin = Integer.MAX_VALUE, winMax = 0;
        java.util.List<Integer> maxima = new java.util.ArrayList<Integer>();
        for (int i = 0; i < tempsC.length; i++) {
            ring[idx] = (int) Math.round(Math.max(0, tempsC[i] * 100));
            idx = idx == 6 ? 0 : idx + 1;
            int[] s = ring.clone();
            java.util.Arrays.sort(s);
            int m = s[3];
            if (m != 0) {
                winMin = Math.min(winMin, m);
                winMax = Math.max(winMax, m);
            }
            if ((i + 1) % 30 == 0) {
                if (winMax >= winMin && winMax != 0 && winMax - winMin < 250) maxima.add(winMax);
                winMin = Integer.MAX_VALUE;
                winMax = 0;
            }
        }
        if (maxima.size() < 4) return Double.NaN;
        int min = Integer.MAX_VALUE;
        for (int v : maxima) min = Math.min(min, v);
        return min / 100.0;
    }
}
