package app.openring.core.analysis.cycleforecast;

import java.util.Arrays;

import app.openring.core.analysis.Cycles;

/**
 * drip style (its FAQ, issue #383): the mean of the newest {@link Cycles#WINDOW} cycles from the first bleeding days only,
 * no cycle left out, give or take 1 day while their standard deviation is under {@link #NARROW_SD} days, else 2. drip shows
 * nothing under {@link Cycles#FEW} cycles; here Open Ring's rule for few cycles stands in, so every method has an answer.
 */
final class DripMethod implements Method {
    /** Under this standard deviation drip shows 1 day each side of the expected day, else 2. */
    static final double NARROW_SD = 1.5;

    @Override
    public int id() {
        return Methods.DRIP;
    }

    @Override
    public Forecast predict(int[] lengths) {
        if (lengths.length < Cycles.FEW) return Methods.OPEN.predict(lengths).as(Methods.DRIP);
        int[] u = Arrays.copyOfRange(lengths, Math.max(0, lengths.length - Cycles.WINDOW), lengths.length);
        double sd = Methods.sd(u);
        int range = sd < NARROW_SD ? 1 : 2;
        return new Forecast(Methods.DRIP, Methods.mean(u), sd, range, range, u.length, false);
    }
}
