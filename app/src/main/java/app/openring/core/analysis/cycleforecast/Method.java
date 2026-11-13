package app.openring.core.analysis.cycleforecast;

/**
 * One way to expect the next cycle from the cycles before it (issue #383). Every method sees only the lengths it is given,
 * so the backtest can hand it the cycles before any logged start and nothing after ({@link Backtest}).
 */
public interface Method {
    /** {@link Methods#OPEN_RING} and so on; what Settings stores. */
    int id();

    /**
     * The next cycle from {@code lengths}: every cycle's length in days, oldest first, those that look like a missed log
     * included (each method decides what to do with them). Never null, also for no cycle at all.
     */
    Forecast predict(int[] lengths);
}
