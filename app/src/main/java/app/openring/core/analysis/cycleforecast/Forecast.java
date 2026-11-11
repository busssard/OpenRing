package app.openring.core.analysis.cycleforecast;

import app.openring.core.analysis.Cycles;

/**
 * What one method expects of the next cycle (issue #383): its length, how sure it is, and the range the calendar shows.
 * Pure numbers; {@code Cycles} turns them into dates.
 */
public final class Forecast {
    /** The method that made it ({@link Methods#OPEN_RING} and so on). */
    public final int method;
    /** The expected cycle length in days, unrounded. The calendar's next start is the last start plus its rounded value. */
    public final double mean;
    /** How much the next cycle may differ from {@link #mean} (one standard deviation, days); used for the ovulation range. */
    public final double spread;
    /** The range each way in days before rounding (about 4 in 5 starts), and as the calendar shows it. */
    public final double range;
    public final int shown;
    /** How many cycles it rests on. */
    public final int n;
    /** Too few cycles of its own: it used Open Ring's rule for few cycles instead. */
    public final boolean fallback;

    Forecast(int method, double mean, double spread, double range, int shown, int n, boolean fallback) {
        this.method = method;
        this.mean = mean;
        this.spread = spread;
        this.range = range;
        this.shown = shown;
        this.n = n;
        this.fallback = fallback;
    }

    /** A range shown as whole days: at least 1. */
    static int days(double range) {
        return Math.max(1, (int) Math.round(range));
    }

    /** The expected length in whole days, as the calendar counts it. */
    public int days() {
        return (int) Math.round(mean);
    }

    /** The same numbers marked as made by {@code method} with another's rule (too few cycles). */
    Forecast as(int method) {
        return new Forecast(method, mean, spread, range, shown, n, true);
    }

    @Override
    public String toString() {
        return Methods.name(method) + ": " + String.format(java.util.Locale.ROOT, "%.2f", mean) + " +-" + shown
                + (fallback ? " (few cycles)" : "") + ", spread " + String.format(java.util.Locale.ROOT, "%.2f", spread);
    }

    /** The calendar's default without any cycle of the person's own (Bull et al. 2019), as Open Ring shows it. */
    static Forecast none(int method) {
        return new Forecast(method, Cycles.DEFAULT_LENGTH, Cycles.PERSON_SD, Cycles.WIDE, Cycles.WIDE, 0, method != Methods.OPEN_RING);
    }
}
