package app.openring.core.analysis;

import java.util.Arrays;
import java.util.List;

/** Small numeric helpers. NaN means "no value" throughout the analysis code. */
public final class Stats {
    private Stats() {}

    public static double median(double[] values) {
        double[] v = finite(values);
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        int m = v.length / 2;
        return v.length % 2 == 0 ? (v[m - 1] + v[m]) / 2 : v[m];
    }

    public static double mean(double[] values) {
        double s = 0;
        int n = 0;
        for (double x : values) {
            if (!Double.isNaN(x)) {
                s += x;
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    public static double sd(double[] values) {
        double m = mean(values);
        if (Double.isNaN(m)) return Double.NaN;
        double s = 0;
        int n = 0;
        for (double x : values) {
            if (!Double.isNaN(x)) {
                s += (x - m) * (x - m);
                n++;
            }
        }
        return n < 2 ? Double.NaN : Math.sqrt(s / n);
    }

    public static double percentile(double[] values, double p) {
        double[] v = finite(values);
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        double idx = p * (v.length - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return v[lo] + (v[hi] - v[lo]) * (idx - lo);
    }

    public static double[] finite(double[] values) {
        int n = 0;
        for (double x : values) if (!Double.isNaN(x) && !Double.isInfinite(x)) n++;
        double[] out = new double[n];
        int k = 0;
        for (double x : values) if (!Double.isNaN(x) && !Double.isInfinite(x)) out[k++] = x;
        return out;
    }

    public static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            Double d = list.get(i);
            out[i] = d == null ? Double.NaN : d;
        }
        return out;
    }

    public static int countFinite(double[] values) {
        return finite(values).length;
    }

    /** Piecewise linear curve through sorted (x, y) anchors, clamped at the ends. */
    public static double curve(double x, double[][] anchors) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x <= anchors[0][0]) return anchors[0][1];
        for (int i = 1; i < anchors.length; i++) {
            double x0 = anchors[i - 1][0], y0 = anchors[i - 1][1];
            double x1 = anchors[i][0], y1 = anchors[i][1];
            if (x <= x1) {
                double t = Math.abs(x1 - x0) < 1e-12 ? 0 : (x - x0) / (x1 - x0);
                return y0 + t * (y1 - y0);
            }
        }
        return anchors[anchors.length - 1][1];
    }

    public static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    public static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
