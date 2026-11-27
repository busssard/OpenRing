package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * The Badges page's month calendar (issue #265, phase 1b): under each day with badges one dot per badge in its colour
 * (the colour of its tab, as on the badges themselves), today with an outline. Touch works like the period calendar ({@link PeriodCalendar}): slide sideways to read each
 * day the finger passes, a tap without movement opens the day, a move that starts upwards or downwards scrolls the page.
 */
final class BadgeCalendar extends MonthView {
    interface Listener extends MonthView.Listener {
    }

    private final Map<LocalDate, List<Integer>> colors;
    private final LocalDate today;

    BadgeCalendar(Context c, Map<LocalDate, List<Integer>> colors, LocalDate today, YearMonth month, Listener listener) {
        super(c, month, listener, true, "Badge calendar, touch and slide to read the days, tap a day to open it");
        this.colors = colors;
        this.today = today;
    }

    @Override
    protected int dots(LocalDate day) {
        List<Integer> cs = colors.get(day);
        return cs == null ? 0 : cs.size();
    }

    @Override
    protected int dotColor(LocalDate day, int k) {
        return colors.get(day).get(k);
    }

    @Override
    protected void onDraw(Canvas cv) {
        drawDotDays(cv, today);
    }
}
