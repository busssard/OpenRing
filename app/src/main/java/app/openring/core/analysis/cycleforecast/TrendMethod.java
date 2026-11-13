package app.openring.core.analysis.cycleforecast;

import app.openring.core.analysis.Cycles;

/**
 * Follows your last cycle (issue #383): an autoregressive model of order 1 on the kept cycles (Open Ring's rule for leaving
 * cycles out). The next cycle is the mean plus {@code phi} times how far the last kept cycle lay from it. {@code phi} is the
 * correlation of each cycle with the one before ({@code r}), shrunk toward none by how much of it chance explains with
 * {@code n} cycles: {@code phi = r × max(0, 1 − 1/(n r²))} (a correlation's noise is about 1/√n), at most
 * {@link #MAX_PHI} either way. So a history without a real link gives the plain mean. The range is 1.28 times the spread
 * that is left, {@code sd × √(1 − phi²)}. Under {@link #FROM} kept cycles Open Ring's rule stands in.
 */
final class TrendMethod implements Method {
    /** Kept cycles it needs before it estimates a link. */
    static final int FROM = 6;
    static final double MAX_PHI = 0.9;

    @Override
    public int id() {
        return Methods.TREND;
    }

    @Override
    public Forecast predict(int[] lengths) {
        int[] x = Methods.kept(lengths);
        if (x.length < FROM) return Methods.OPEN.predict(lengths).as(Methods.TREND);
        double m = Methods.mean(x), phi = phi(x, m);
        double sd = Methods.sd(x), spread = sd * Math.sqrt(1 - phi * phi);
        double range = Cycles.Z80 * spread;
        return new Forecast(Methods.TREND, m + phi * (x[x.length - 1] - m), spread, range, Forecast.days(range), x.length, false);
    }

    /** The shrunk link between one cycle and the next. */
    static double phi(int[] x, double m) {
        double num = 0, den = 0;
        for (int i = 0; i < x.length; i++) {
            den += (x[i] - m) * (x[i] - m);
            if (i > 0) num += (x[i] - m) * (x[i - 1] - m);
        }
        if (den <= 0) return 0;
        double r = num / den;
        double keep = 1 - 1 / (x.length * r * r);
        double phi = keep > 0 ? r * keep : 0;
        return Math.max(-MAX_PHI, Math.min(MAX_PHI, phi));
    }
}
