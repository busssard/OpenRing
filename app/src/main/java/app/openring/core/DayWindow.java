package app.openring.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Which days a chart of the last {@code n} days shows when an older day is picked (owner's report of 23 November: going back a
 * day hid the newer values, which felt odd). The window keeps ending today while the picked day is inside it, and the picked day is
 * highlighted; picked further back than that, the window moves one day per day, the picked day being its oldest, so as many newer
 * days as fit stay in view and nothing jumps. Plain Java, so the JVM tests run it.
 */
public final class DayWindow {
    private DayWindow() {}

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
}
