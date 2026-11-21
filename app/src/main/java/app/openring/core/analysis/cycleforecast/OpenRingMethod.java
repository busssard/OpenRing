package app.openring.core.analysis.cycleforecast;

import app.openring.core.analysis.Cycles;

/**
 * Open Ring's rule since #252: cycles more than {@link Cycles#FAR} days from the middle value of all are left out, unless
 * half of them or more are ({@link Methods#leftOut}); of the newest {@link Cycles#WINDOW} kept, the median under {@link Cycles#TRIM_FROM} cycles, from then on the mean
 * without the shortest and the longest; the range 1.28 standard deviations each way, {@link Cycles#WIDE} days under
 * {@link Cycles#FEW} cycles.
 */
public final class OpenRingMethod implements Method {
    OpenRingMethod() {}

    @Override
    public int id() {
        return Methods.OPEN_RING;
    }

    @Override
    public Forecast predict(int[] lengths) {
        int[] u = used(lengths);
        if (u.length == 0) return Forecast.none(Methods.OPEN_RING);
        double typical = typical(u);
        double sd = sd(u);
        boolean few = u.length < Cycles.FEW;
        double range = few ? Cycles.WIDE : Cycles.Z80 * sd;
        return new Forecast(Methods.OPEN_RING, typical, few ? Cycles.PERSON_SD : sd, range, few ? Cycles.WIDE : Forecast.days(range), u.length, false);
    }

    /**
     * The typical length of the cycles the rule rests on ({@code used}, oldest first): {@link Cycles#DEFAULT_LENGTH} without any,
     * their median under {@link Cycles#TRIM_FROM}, from then on their mean without the shortest and the longest. {@code Cycles}
     * describes the person's cycles with it too, so the rule lives here only.
     */
    public static double typical(int[] used) {
        if (used.length == 0) return Cycles.DEFAULT_LENGTH;
        return used.length < Cycles.TRIM_FROM ? Methods.median(used) : Methods.trimmedMean(used);
    }

    /** How much the cycles the rule rests on vary: their sample standard deviation, NaN with fewer than 2. */
    public static double sd(int[] used) {
        return Methods.sd(used);
    }

    /** The cycles the rule rests on: kept (within {@link Cycles#FAR} days of the middle value), the newest {@link Cycles#WINDOW}. */
    static int[] used(int[] lengths) {
        int[] kept = Methods.kept(lengths);
        return java.util.Arrays.copyOfRange(kept, Math.max(0, kept.length - Cycles.WINDOW), kept.length);
    }
}
