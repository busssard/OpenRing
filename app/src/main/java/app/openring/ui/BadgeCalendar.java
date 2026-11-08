package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import app.openring.core.MonthGrid;

/**
 * The Badges page's month calendar (issue #265, phase 1b): a dot under each day with a badge, a bigger one with two or
 * more, today with an outline. Touch works like the period calendar ({@link PeriodCalendar}): slide sideways to read each
 * day the finger passes, a tap without movement opens the day, a move that starts upwards or downwards scrolls the page.
 */
final class BadgeCalendar extends MonthView {
    interface Listener extends MonthView.Listener {
    }

    private final Map<LocalDate, Integer> counts;
    private final LocalDate today;

    BadgeCalendar(Context c, Map<LocalDate, Integer> counts, LocalDate today, YearMonth month, Listener listener) {
        super(c, month, listener, true, "Badge calendar, touch and slide to read the days, tap a day to open it");
        this.counts = counts;
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
        for (int dom = 1; dom <= len; dom++) {
            LocalDate d = month.atDay(dom);
            int i = off + dom - 1, col = i % MonthGrid.COLS, row = i / MonthGrid.COLS;
            float cx = cw * (col + 0.5f), cy = head + row * cellH + cellH / 2 - Ui.dp(c, 4);
            Integer n = counts.get(d);
            if (n != null && n > 0) {
                fill.setColor(Ui.GOOD);
                cv.drawCircle(cx, cy + rad + Ui.dp(c, 1), Ui.dp(c, n >= 2 ? 3.5f : 2.4f), fill);
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
