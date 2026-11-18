package app.openring.core.analysis.cycleforecast;

import app.openring.core.analysis.Cycles;

/**
 * Recent cycles count more (issue #383): simple exponential smoothing of the kept cycles (Open Ring's rule for leaving
 * cycles out). The level starts at the mean of the first {@link Cycles#FEW} cycles and moves a share {@code a} toward each
 * new cycle; {@code a} is the one of {@link #WEIGHTS} with the smallest one step error over the person's own history (the
 * usual way to fit it), so a drifting history gets a larger share and a steady one a smaller. The range is 1.28 times the
 * root mean square of those one step errors. Under {@link #FROM} kept cycles Open Ring's rule stands in.
 */
final class SmoothMethod implements Method {
    /** The shares tried, 0.05 to 0.6. */
    static final double[] WEIGHTS = {0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6};
    /** Kept cycles it needs: the start level and two one step errors. */
    static final int FROM = Cycles.FEW + 2;

    @Override
    public int id() {
        return Methods.SMOOTH;
    }

    @Override
    public Forecast predict(int[] lengths) {
        int[] x = Methods.kept(lengths);
        if (x.length < FROM) return Methods.OPEN.predict(lengths).as(Methods.SMOOTH);
        double bestErr = Double.POSITIVE_INFINITY, bestLevel = Double.NaN;
        for (double a : WEIGHTS) {
            double[] r = run(x, a);
            if (r[1] < bestErr - 1e-12) {
                bestErr = r[1];
                bestLevel = r[0];
            }
        }
        double spread = Math.sqrt(bestErr);
        double range = Cycles.Z80 * spread;
        return new Forecast(Methods.SMOOTH, bestLevel, spread, range, Forecast.days(range), x.length, false);
    }

    /** The level after all of {@code x} with share {@code a}, and the mean squared one step error. */
    static double[] run(int[] x, double a) {
        double level = 0;
        for (int i = 0; i < Cycles.FEW; i++) level += x[i];
        level /= Cycles.FEW;
        double sq = 0;
        int n = 0;
        for (int i = Cycles.FEW; i < x.length; i++) {
            double e = x[i] - level;
            sq += e * e;
            n++;
            level += a * e;
        }
        return new double[] {level, sq / n};
    }
}
