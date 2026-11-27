package app.openring.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The grid of the month calendars (the Badges page and the period tracker, {@code ui/MonthView}): seven columns from the
 * locale's first day of the week, six rows under a row of weekday letters. Which day a touch is on, and whether a move is
 * a sideways slide over the days or the page scrolling, live here once so both calendars agree and the JVM tests see them.
 */
public final class MonthGrid {
    public static final int COLS = 7, ROWS = 6;

    /** {@link #gesture}: not decided yet, a sideways slide over the days, or a move upwards or downwards (the page scrolls). */
    public static final int WAIT = 0, SLIDE = 1, SCROLL = 2;

    private MonthGrid() {
    }

    /** The column of the first of the month in the first row. */
    public static int offset(YearMonth month, DayOfWeek firstDay) {
        return (month.atDay(1).getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
    }

    /** The column of day {@code dom} of a month whose first day is in column {@code offset}; {@link #dayAt} reads it back. */
    public static int col(int offset, int dom) {
        return (offset + dom - 1) % COLS;
    }

    /** The row of day {@code dom} of a month whose first day is in column {@code offset}; {@link #dayAt} reads it back. */
    public static int row(int offset, int dom) {
        return (offset + dom - 1) / COLS;
    }

    /**
     * The distance between the centres of {@code n} dots side by side under a day of a cell {@code cw} wide: {@code dot}
     * radius and {@code gap} between them, closer together when they would not fit in the cell less {@code margin}, so a
     * day with many dots keeps every one (the Badges and life factors calendars).
     */
    public static float dotStep(float dot, float gap, float margin, float cw, int n) {
        return Math.min(dot * 2 + gap, (cw - margin) / n);
    }

    /** The centre of the first of {@code n} dots {@code step} apart, centred on {@code cx}. */
    public static float dotFirst(float cx, float step, int n) {
        return cx - step * (n - 1) / 2f;
    }

    /** The radius of each dot: {@code dot}, smaller when they are close together, so they never touch. */
    public static float dotRadius(float dot, float step) {
        return Math.min(dot, step * 0.45f);
    }

    /**
     * The day at a point of a grid {@code width} wide whose rows start {@code head} from the top and are {@code cellH}
     * high, or null outside this month's days. A point left or right of the grid counts in the first or last column.
     */
    public static LocalDate dayAt(YearMonth month, DayOfWeek firstDay, float width, float head, float cellH, float x, float y) {
        float cw = width / COLS;
        int col = (int) Math.max(0, Math.min(COLS - 1, Math.floor(x / cw)));
        int row = (int) Math.floor((y - head) / cellH);
        if (row < 0 || row >= ROWS) return null;
        int dom = row * COLS + col - offset(month, firstDay) + 1;
        return dom >= 1 && dom <= month.lengthOfMonth() ? month.atDay(dom) : null;
    }

    /**
     * What a move of {@code dx}, {@code dy} (both distances, never negative) from where the finger went down means, with
     * {@code slop} the touch slop: sideways past the slop and more sideways than up or down is a slide, else past the slop
     * up or down lets the page scroll, else it is not decided yet.
     */
    public static int gesture(float dx, float dy, int slop) {
        if (dx > slop && dx > dy) return SLIDE;
        if (dy > slop) return SCROLL;
        return WAIT;
    }
}
