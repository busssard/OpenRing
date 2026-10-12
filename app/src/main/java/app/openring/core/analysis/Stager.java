package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Model free sleep staging from the ring's own nocturnal streams (5 minute HR/HRV and per window
 * motion). Faithful port of open_health {@code stage_night_from_signals},
 * {@code refine_deep_stages_with_hrv} and {@code smooth_stages}. Used when the ring did not
 * stage the night itself (Ring 4 and 5 do not emit hypnogram pages; the official app runs a
 * proprietary neural network instead).
 *
 * Stage codes: 1 deep, 2 light, 3 REM, 4 awake, 0 unknown. Epochs are 30 seconds.
 */
public final class Stager {
    private Stager() {}

    public static final int DEEP = 1, LIGHT = 2, REM = 3, WAKE = 4;

    public static int[] stageFromSignals(double start, double end, TS hrT, TS hrvT, TS motionT) {
        if (end <= start) return new int[0];
        int epochs = (int) Math.max(1, Math.floor((end - start) / 30.0));
        if (epochs < 120) return new int[0];
        TS hrPts = hrT.filter(30.0001, 139.9999, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).sort();
        if (hrPts.size() < 12) return new int[0];
        TS hrvPts = hrvT.filter(1e-9, Double.MAX_VALUE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).sort();

        double span = Math.max(1, end - start);
        double epochS = span / epochs;
        double[] hrEp = new double[epochs];
        for (int i = 0; i < epochs; i++) hrEp[i] = hrPts.interp(start + (i + 0.5) * epochS);
        double[] hrvRaw = new double[epochs];
        if (!hrvPts.isEmpty()) for (int i = 0; i < epochs; i++) hrvRaw[i] = hrvPts.interp(start + (i + 0.5) * epochS);
        double[] hrvEp = new double[epochs];
        for (int i = 0; i < epochs; i++) {
            int lo = Math.max(0, i - 12), hi = Math.min(epochs, i + 13);
            double s = 0;
            for (int k = lo; k < hi; k++) s += hrvRaw[k];
            hrvEp[i] = s / Math.max(1, hi - lo);
        }
        double[] mo = epochMotion(motionT, start, span, epochs);

        double[] sortedHr = hrEp.clone();
        Arrays.sort(sortedHr);
        double hrMed = sortedHr[sortedHr.length / 2];
        double hrvMed = 0, hrvP62 = 0, hrvP80 = 0;
        if (!hrvPts.isEmpty()) {
            double[] sh = hrvEp.clone();
            Arrays.sort(sh);
            int nh = sh.length - 1;
            hrvMed = sh[nh / 2];
            hrvP62 = sh[(nh * 62) / 100];
            hrvP80 = sh[(nh * 80) / 100];
        }

        int[] stages = new int[epochs];
        Arrays.fill(stages, LIGHT);
        int onset = 0;
        int maxOnset = Math.min(epochs / 4, 120);
        for (int i = 0; i < maxOnset; i++) {
            int endChk = Math.min(i + 10, epochs);
            boolean quiet = true;
            for (int k = i; k < endChk; k++) if (mo[k] > 4.0) { quiet = false; break; }
            if (quiet) { onset = i; break; }
        }
        for (int i = 0; i < onset; i++) stages[i] = WAKE;

        int wakeEnd = epochs;
        int minWakeEnd = Math.max(onset, epochs - 90);
        boolean foundQuietEnd = false;
        for (int i = epochs - 1; i >= minWakeEnd; i--) {
            int loChk = Math.max(Math.max(0, i - 20), onset);
            boolean quiet = true;
            for (int k = loChk; k <= i; k++) if (mo[k] > 2.0) { quiet = false; break; }
            if (quiet) { wakeEnd = i + 1; foundQuietEnd = true; break; }
        }
        if (!foundQuietEnd) {
            while (wakeEnd > minWakeEnd && mo[wakeEnd - 1] >= 4.0) wakeEnd--;
        }
        for (int i = wakeEnd; i < epochs; i++) stages[i] = WAKE;

        // brief mid night arousals around substantial movement
        for (int i = onset; i < wakeEnd; i++) {
            int loN = Math.max(Math.max(0, i - 4), onset), hiN = Math.min(i + 5, wakeEnd);
            double near = 0;
            for (int k = loN; k < hiN; k++) near += mo[k];
            if (mo[i] >= 8.0 || (mo[i] >= 5.0 && near >= 10.0)) {
                int lo = Math.max(Math.max(0, i - 1), onset), hi = Math.min(i + 2, wakeEnd);
                for (int k = lo; k < hi; k++) stages[k] = WAKE;
            }
        }

        int[] refined = refineDeep(stages, hrPts, hrvPts, motionT, start, end);

        int remStart = Math.min(onset + 90, wakeEnd);
        int remEnd = Math.max(Math.max(0, wakeEnd - 2), remStart);
        double sleepSpan = Math.max(1, wakeEnd - onset);
        for (int i = remStart; i < remEnd; i++) {
            if (refined[i] != LIGHT || mo[i] > 2.0) continue;
            double frac = (i - onset) / sleepSpan;
            double cyclePos = ((i - onset) % 176) / 176.0;
            boolean inRemPhase = cyclePos >= 0.56 && cyclePos <= 0.90;
            boolean isRem;
            if (!hrvPts.isEmpty() && hrvP80 > hrvMed) {
                double h = hrvEp[i];
                isRem = (inRemPhase && h >= hrvP62) || (frac >= 0.30 && h >= hrvP80)
                        || (frac >= 0.80 && inRemPhase && h >= hrvMed * 0.90);
            } else {
                isRem = inRemPhase && (frac >= 0.35 || hrEp[i] >= hrMed - 0.5);
            }
            if (isRem) refined[i] = REM;
        }
        return smooth(refined, 5);
    }

    static double[] epochMotion(TS motionT, double start, double span, int n) {
        double[] mo = new double[n];
        for (int k = 0; k < motionT.size(); k++) {
            double f = (motionT.t(k) - start) / span;
            if (f >= -1e-6 && f <= 1 + 1e-6) {
                int idx = (int) Math.max(0, Math.min(n - 1, Math.floor(f * n)));
                mo[idx] = Math.max(mo[idx], motionT.v(k));
            }
        }
        return mo;
    }

    /** Recover consolidated N3 bouts inside LIGHT using HR dips, HRV and stillness. */
    public static int[] refineDeep(int[] stages, TS hrPtsIn, TS hrvPtsIn, TS motionT, double start, double end) {
        int n = stages.length;
        if (n < 40 || contains(stages, DEEP) || end <= start) return stages.clone();
        int lightCount = 0;
        for (int s : stages) if (s == LIGHT) lightCount++;
        if (lightCount < 20) return stages.clone();
        TS hrPts = hrPtsIn.filter(30.0001, 139.9999, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).sort();
        if (hrPts.size() < 6) return stages.clone();
        TS hrvPts = hrvPtsIn.filter(1e-9, Double.MAX_VALUE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).sort();

        double span = Math.max(1, end - start);
        double epochS = span / n;
        double[] hrEp = new double[n];
        for (int i = 0; i < n; i++) hrEp[i] = hrPts.interp(start + (i + 0.5) * epochS);
        double[] hrvEp = new double[n];
        if (!hrvPts.isEmpty()) for (int i = 0; i < n; i++) hrvEp[i] = hrvPts.interp(start + (i + 0.5) * epochS);
        double[] mo = epochMotion(motionT, start, span, n);

        double hrMin = Double.POSITIVE_INFINITY, hrMax = Double.NEGATIVE_INFINITY;
        for (double h : hrEp) { hrMin = Math.min(hrMin, h); hrMax = Math.max(hrMax, h); }
        if (hrMax - hrMin < 2.0) return stages.clone();

        double[] localHr = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - 90), hi = Math.min(n, i + 91);
            double s = 0;
            for (int k = lo; k < hi; k++) s += hrEp[k];
            localHr[i] = s / Math.max(1, hi - lo);
        }
        double[] sortedHrv = hrvPts.values();
        Arrays.sort(sortedHrv);
        double hrvMed = sortedHrv.length == 0 ? 0 : sortedHrv[sortedHrv.length / 2];

        List<Integer> sleepIdx = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (stages[i] >= 1 && stages[i] <= 3) sleepIdx.add(i);
        if (sleepIdx.size() < 20) return stages.clone();
        int onset = sleepIdx.get(0);
        int finalSleep = sleepIdx.get(sleepIdx.size() - 1);
        double sleepSpan = Math.max(1, finalSleep - onset);

        List<int[]> runs = new ArrayList<int[]>();
        int i = 0;
        while (i < n) {
            if (stages[i] == LIGHT && mo[i] <= 1.0) {
                int j = i;
                while (j < n && stages[j] == LIGHT && mo[j] <= 1.0) j++;
                if (j - i >= 12) runs.add(new int[] {i, j});
                i = j;
            } else {
                i++;
            }
        }
        if (runs.isEmpty()) return stages.clone();

        List<int[]> segmented = new ArrayList<int[]>();
        for (int[] r : runs) {
            if (r[1] - r[0] <= 180) {
                segmented.add(r);
            } else {
                int cur = r[0];
                while (cur + 12 <= r[1]) {
                    int segEnd = Math.min(cur + 90, r[1]);
                    if (r[1] - segEnd < 24) segEnd = r[1];
                    segmented.add(new int[] {cur, segEnd});
                    cur = segEnd;
                }
            }
        }

        int firstCycleMaxLen = 0;
        for (int[] r : segmented) {
            double frac = (((r[0] + r[1]) / 2.0) - onset) / sleepSpan;
            if (frac < 0.16) firstCycleMaxLen = Math.max(firstCycleMaxLen, r[1] - r[0]);
        }

        List<double[]> scored = new ArrayList<double[]>(); // score, s, e, frac, dip
        for (int[] r : segmented) {
            int runLen = r[1] - r[0];
            double mid = (r[0] + r[1]) / 2.0;
            double sleepFrac = Math.max(0, (mid - onset) / sleepSpan);
            if (sleepFrac > 0.56) continue;
            if (sleepFrac < 0.12 && firstCycleMaxLen >= 40 && runLen < firstCycleMaxLen * 0.65) continue;
            double meanHr = mean(hrEp, r[0], r[1]);
            double meanLoc = mean(localHr, r[0], r[1]);
            int prevEnd = Math.max(r[0], 1);
            double prevHr = mean(hrEp, 0, prevEnd);
            double meanHrv = hrvPts.isEmpty() ? 0 : mean(hrvEp, r[0], r[1]);
            double hrDip = meanLoc - meanHr;
            double prevDip = prevHr - meanHr;
            if (prevDip < -0.25 || (prevDip < 0.10 && hrDip < 0.35)) continue;
            if (sortedHrv.length > 0 && sleepFrac > 0.12 && meanHrv > Math.max(hrvMed * 1.15, 38.0) && hrDip < 2.0) continue;
            double homeo = Math.exp(-2.2 * sleepFrac);
            double durBonus = Stats.clamp(Math.max(0, runLen - 12) / 48.0, 0, 1);
            double dipBonus = Stats.clamp(hrDip / 3.0, -0.5, 0.6);
            double score = homeo * 0.55 + durBonus * 0.35 + dipBonus * 0.25;
            scored.add(new double[] {score, r[0], r[1], sleepFrac, hrDip});
        }
        Collections.sort(scored, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(b[0], a[0]);
            }
        });
        int asleep = sleepIdx.size();
        int targetDeep = Math.max((int) Math.round(asleep * 0.105), 12);
        int[] out = stages.clone();
        List<Integer> centers = new ArrayList<Integer>();
        int deepCount = 0;
        for (double[] sr : scored) {
            if (deepCount >= targetDeep) break;
            int rs = (int) sr[1], re = (int) sr[2];
            double sleepFrac = sr[3], hrDip = sr[4];
            int runLen = re - rs;
            int mid = (rs + re) / 2;
            boolean near = false;
            for (int c : centers) if (Math.abs(mid - c) < 110) near = true;
            if (near) continue;
            int remaining = targetDeep - deepCount;
            if (remaining < 8) break;
            int sIdx, eIdx;
            if (sleepFrac < 0.16) {
                int lead = rs <= onset + 12 ? 2 : clampI(Math.max(0, runLen - 12) / 5, 2, 10);
                int tail = clampI(Math.max(0, runLen - (12 + lead)) / 6, 1, 4);
                int maxBout = Math.min(Math.min(56, Math.max(0, runLen - (lead + tail))), remaining);
                if (maxBout < 8) continue;
                int searchEnd = Math.max(Math.max(0, re - (tail + maxBout)), rs + lead);
                int bestS = rs + lead;
                double bestHr = Double.POSITIVE_INFINITY;
                for (int cand = rs + lead; cand <= searchEnd; cand++) {
                    if (cand + maxBout > n) break;
                    double m = mean(hrEp, cand, cand + maxBout);
                    if (m < bestHr) { bestHr = m; bestS = cand; }
                }
                sIdx = bestS;
                eIdx = bestS + maxBout;
            } else if (sleepFrac < 0.36) {
                int cap = hrDip >= 2.0 ? clampI((runLen * 9) / 20, 10, 42) : clampI((runLen * 2) / 5, 10, 26);
                int maxBout = Math.min(Math.min(cap, remaining), runLen);
                int tail = clampI(Math.max(0, runLen - maxBout) / 4, 1, 4);
                int e = Math.max(0, re - tail);
                int s = Math.max(Math.max(0, e - maxBout), rs + Math.min(4, runLen));
                sIdx = s;
                eIdx = e;
            } else {
                int maxBout = Math.min(Math.min(clampI((runLen * 2) / 5, 8, 22), remaining), runLen);
                int s = rs + Math.max(0, runLen - maxBout) / 2;
                sIdx = s;
                eIdx = s + maxBout;
            }
            if (eIdx <= sIdx || eIdx - sIdx < 8) continue;
            eIdx = Math.min(eIdx, n);
            for (int k = sIdx; k < eIdx; k++) out[k] = DEEP;
            deepCount += eIdx - sIdx;
            centers.add((sIdx + eIdx) / 2);
        }
        return out;
    }

    /** Mode filter over a centered odd window; unknown epochs stay unknown. */
    public static int[] smooth(int[] vals, int win) {
        if (vals.length < win || win < 3) return vals.clone();
        int half = win / 2;
        int[] out = new int[vals.length];
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == 0) { out[i] = 0; continue; }
            int a = Math.max(0, i - half), b = Math.min(vals.length, i + half + 1);
            int[] counts = new int[5];
            for (int k = a; k < b; k++) if (vals[k] >= 1 && vals[k] <= 4) counts[vals[k]]++;
            int best = vals[i], bestC = -1;
            // Rust max_by_key returns the LAST maximum among 1..=4
            for (int s = 1; s <= 4; s++) if (counts[s] >= bestC) { bestC = counts[s]; best = s; }
            out[i] = best;
        }
        return out;
    }

    /**
     * Last resort staging when HR is too sparse: sleep and wake from the ring's own per window
     * sleep_state (1 or 2 means asleep). Every epoch inherits the nearest sample.
     */
    public static int[] stageFromSleepState(double start, double end, TS state) {
        int epochs = (int) Math.floor((end - start) / 30.0);
        if (epochs <= 0 || state.isEmpty()) return new int[0];
        state.sort();
        int[] out = new int[epochs];
        int k = 0;
        for (int i = 0; i < epochs; i++) {
            double t = start + (i + 0.5) * 30;
            while (k + 1 < state.size() && Math.abs(state.t(k + 1) - t) <= Math.abs(state.t(k) - t)) k++;
            if (Math.abs(state.t(k) - t) > 20 * 60) {
                out[i] = 0;
            } else {
                out[i] = state.v(k) >= 1 ? LIGHT : WAKE;
            }
        }
        return smooth(out, 5);
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static double mean(double[] a, int from, int to) {
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }

    private static int clampI(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
