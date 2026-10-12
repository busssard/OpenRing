package app.openring.core.analysis;

import java.util.Arrays;

/** A growable time series of (unix seconds, value) pairs. */
public final class TS {
    private double[] t = new double[16];
    private double[] v = new double[16];
    private int n;
    private boolean sorted = true;

    public void add(double time, double value) {
        if (n == t.length) {
            t = Arrays.copyOf(t, n * 2);
            v = Arrays.copyOf(v, n * 2);
        }
        if (n > 0 && time < t[n - 1]) sorted = false;
        t[n] = time;
        v[n] = value;
        n++;
    }

    public int size() {
        return n;
    }

    public double t(int i) {
        return t[i];
    }

    public double v(int i) {
        return v[i];
    }

    public boolean isEmpty() {
        return n == 0;
    }

    /** Sort by time (stable for equal times is not required). */
    public TS sort() {
        if (sorted) return this;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final double[] tt = t;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(tt[a], tt[b]);
            }
        });
        double[] nt = new double[Math.max(16, n)], nv = new double[Math.max(16, n)];
        for (int i = 0; i < n; i++) {
            nt[i] = t[idx[i]];
            nv[i] = v[idx[i]];
        }
        t = nt;
        v = nv;
        sorted = true;
        return this;
    }

    /** New series with samples whose value lies in [lo, hi] and time in [from, to]. */
    public TS filter(double lo, double hi, double from, double to) {
        TS out = new TS();
        for (int i = 0; i < n; i++) {
            if (v[i] >= lo && v[i] <= hi && t[i] >= from && t[i] <= to) out.add(t[i], v[i]);
        }
        return out;
    }

    public double[] values() {
        return Arrays.copyOf(v, n);
    }

    public double mean() {
        if (n == 0) return Double.NaN;
        double s = 0;
        for (int i = 0; i < n; i++) s += v[i];
        return s / n;
    }

    public double min() {
        double m = Double.NaN;
        for (int i = 0; i < n; i++) if (Double.isNaN(m) || v[i] < m) m = v[i];
        return m;
    }

    public double max() {
        double m = Double.NaN;
        for (int i = 0; i < n; i++) if (Double.isNaN(m) || v[i] > m) m = v[i];
        return m;
    }

    public double median() {
        return Stats.median(values());
    }

    /** Linear interpolation at time x, clamped at the ends (series must be sorted). */
    public double interp(double x) {
        if (n == 0) return 0;
        if (x <= t[0]) return v[0];
        if (x >= t[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] <= x) lo = mid;
            else hi = mid;
        }
        if (t[hi] <= t[lo]) return v[lo];
        double f = (x - t[lo]) / (t[hi] - t[lo]);
        return v[lo] * (1 - f) + v[hi] * f;
    }

    /** Bucket means into fixed width bins over [from, to); empty bins are NaN. */
    public double[] binMeans(double from, double to, int bins) {
        double[] sum = new double[bins];
        int[] cnt = new int[bins];
        double w = (to - from) / bins;
        for (int i = 0; i < n; i++) {
            if (t[i] < from || t[i] >= to) continue;
            int b = (int) ((t[i] - from) / w);
            if (b < 0 || b >= bins) continue;
            sum[b] += v[i];
            cnt[b]++;
        }
        double[] out = new double[bins];
        for (int i = 0; i < bins; i++) out[i] = cnt[i] > 0 ? sum[i] / cnt[i] : Double.NaN;
        return out;
    }
}
