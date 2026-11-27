package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Body's month view of the life factors (issue #292, phase 1): under each day one dot per factor logged for it, today with an
 * outline, days still to come faded. The grid and the touch are the shared ones of the month calendars ({@link MonthView}):
 * slide sideways to read each day the finger passes, a tap reads the day it is on, a move that starts upwards or downwards
 * scrolls the page.
 */
final class LifeCalendar extends MonthView {
    interface Listener extends MonthView.Listener {
    }

    private final Map<LocalDate, List<String>> days;
    private final LocalDate today;

    LifeCalendar(Context c, Map<LocalDate, List<String>> days, LocalDate today, YearMonth month, Listener listener) {
        super(c, month, listener, true, "Life factors calendar, touch and slide to read the days");
        this.days = days;
        this.today = today;
    }

    /** Shows {@code day} as read (the frame a touch draws), without a touch: Body opened with a day read (screen test). */
    void read(LocalDate day) {
        readDay = day;
        invalidate();
    }

    @Override
    protected int dots(LocalDate day) {
        List<String> tags = days.get(day);
        return tags == null ? 0 : tags.size();
    }

    @Override
    protected int dotColor(LocalDate day, int k) {
        return Ui.LIFE;
    }

    @Override
    protected void onDraw(Canvas cv) {
        drawDotDays(cv, today);
    }
}
