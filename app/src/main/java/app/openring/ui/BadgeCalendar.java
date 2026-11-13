package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import app.openring.core.MonthGrid;

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
            List<Integer> cs = colors.get(d);
            if (cs != null && !cs.isEmpty()) {
                // side by side and centred; up to all 7 kinds fit a cell of a narrow phone
                float dot = Ui.dp(c, 2.4f), step = Math.min(dot * 2 + Ui.dp(c, 1.6f), (cw - Ui.dp(c, 4)) / cs.size());
                float x0 = cx - step * (cs.size() - 1) / 2f;
                for (int k = 0; k < cs.size(); k++) {
                    fill.setColor(cs.get(k));
                    cv.drawCircle(x0 + k * step, cy + rad + Ui.dp(c, 2), Math.min(dot, step * 0.45f), fill);
                }
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
