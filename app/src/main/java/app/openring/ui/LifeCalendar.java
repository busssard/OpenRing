package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import app.openring.core.MonthGrid;

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

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), cw = w / MonthGrid.COLS;
        drawWeekdays(cv, cw);
        int off = offset(), len = month.lengthOfMonth();
        float rad = Math.min(cw, cellH) * 0.36f;
        txt.setTextSize(Ui.dp(c, 13));
        stroke.setStrokeWidth(Ui.dp(c, 1.4f));
        fill.setColor(Ui.LIFE);
        for (int dom = 1; dom <= len; dom++) {
            LocalDate d = month.atDay(dom);
            int i = off + dom - 1, col = i % MonthGrid.COLS, row = i / MonthGrid.COLS;
            float cx = cw * (col + 0.5f), cy = head + row * cellH + cellH / 2 - Ui.dp(c, 4);
            List<String> tags = days.get(d);
            int n = tags == null ? 0 : tags.size();
            if (n > 0) {
                // side by side and centred; a day with many factors keeps every dot, closer together on a narrow phone
                float dot = Ui.dp(c, 2.4f), step = Math.min(dot * 2 + Ui.dp(c, 1.6f), (cw - Ui.dp(c, 4)) / n);
                float x0 = cx - step * (n - 1) / 2f;
                for (int k = 0; k < n; k++) cv.drawCircle(x0 + k * step, cy + rad + Ui.dp(c, 2), Math.min(dot, step * 0.45f), fill);
            }
            if (d.equals(today)) {
                stroke.setColor(Ui.TEXT);
                cv.drawCircle(cx, cy, rad, stroke);
            }
            if (d.equals(readDay)) {
                stroke.setColor(Ui.TEXT_2);
                r.set(cx - cw / 2 + Ui.dp(c, 1), cy - cellH / 2 + Ui.dp(c, 4), cx + cw / 2 - Ui.dp(c, 1), cy + cellH / 2 + Ui.dp(c, 2));
                cv.drawRoundRect(r, Ui.dp(c, 8), Ui.dp(c, 8), stroke);
            }
            txt.setColor(d.isAfter(today) ? Ui.TEXT_3 : Ui.TEXT);
            cv.drawText(String.valueOf(dom), cx, cy + txt.getTextSize() * 0.36f, txt);
        }
    }
}
