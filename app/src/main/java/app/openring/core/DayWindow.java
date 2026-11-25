package app.openring.core;

import app.openring.core.analysis.Stats;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Which days a chart of the last {@code n} days shows when an older day is picked (owner's report of 23 November: going back a
 * day hid the newer values, which felt odd). The window keeps ending today while the picked day is inside it, and the picked day is
 * highlighted; picked further back than that, the window moves one day per day, the picked day being its oldest, so as many newer
 * days as fit stay in view and nothing jumps. Only the drawing follows this window: what is worked out for the picked day (its usual
 * values, the averages and counts under the charts) keeps using the days up to the picked day ({@link #values} ending on it,
 * {@link #usual}), so a display window never changes a number (code review of #455). Plain Java, so the JVM tests run it.
 */
public final class DayWindow {
    private DayWindow() {}

    /** One stored value of a day, NaN when there is none. */
    public interface Value {
        double of(LocalDate day);
    }

    /** The last day of the window of {@code n} days that shows {@code picked}, given {@code today}. */
    public static LocalDate end(LocalDate picked, LocalDate today, int n) {
        if (today == null || !today.isAfter(picked) || n < 1) return picked;
        LocalDate far = picked.plusDays(n - 1);
        return today.isBefore(far) ? today : far;
    }

    /** Where {@code picked} is in the window of {@code n} days ending {@code end}: 0 the oldest, n-1 the last; -1 outside. */
    public static int index(LocalDate picked, LocalDate end, int n) {
        long back = ChronoUnit.DAYS.between(picked, end);
        return back < 0 || back >= n ? -1 : (int) (n - 1 - back);
    }

    /** The values of the {@code n} days ending {@code end}, the oldest first (NaN where a day has none). */
    public static double[] values(LocalDate end, int n, Value value) {
        double[] out = new double[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) out[i] = value.of(end.minusDays(out.length - 1 - i));
        return out;
    }

    /**
     * The usual value next to a night's body signal: the mean of the 14 days before {@code day} (never the day itself or a later
     * one, whatever a chart shows), NaN with fewer than 3 of them.
     */
    public static double usual(LocalDate day, Value value) {
        double[] prior = values(day.minusDays(1), 14, value);
        return Stats.countFinite(prior) >= 3 ? Stats.mean(prior) : Double.NaN;
    }

    /**
     * What an average or count under a chart of {@code n} days ending {@code end} covers when it is worked out for the days up to
     * {@code picked}: "" when the chart ends on the picked day (the everyday case, the text stays as it was), else
     * " in the n days up to the day you picked", since the chart then also shows newer days.
     */
    public static String upTo(LocalDate picked, LocalDate end, int n) {
        return end.equals(picked) ? "" : " in the " + n + " days up to the day you picked";
    }
}
