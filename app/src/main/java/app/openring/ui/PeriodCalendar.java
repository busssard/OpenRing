package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.time.LocalDate;
import java.time.YearMonth;

import app.openring.core.MonthGrid;
import app.openring.core.analysis.Cycles;

/**
 * The period tracker's month calendar (issue #252): logged days as filled circles (stronger the heavier), spotting as a
 * small dot, the unlogged days inside a period as rings, today with an outline. The expected next period and the
 * ovulation range are bands behind the days, the fertile window a bar under them: ranges, never one marked day.
 *
 * Touch: slide sideways (then anywhere) to read each day the finger passes; the card shows the date, the amount, the day
 * of the cycle and why the day is in a band. A tap without movement opens the day editor; lifting after a slide does
 * nothing. A move that starts upwards or downwards scrolls the page, as on the other charts ({@link Charts.Scrub}).
 */
final class PeriodCalendar extends MonthView {
    interface Listener extends MonthView.Listener {
    }

    private final Cycles cy;
    /** The estimate's bands and their legend; off in the drip import's preview, which shows only the file's days. */
    boolean bands = true;
    private final float legendH;

    PeriodCalendar(Context c, Cycles cy, YearMonth month, Listener listener) {
        super(c, month, listener, false, "Period calendar, touch and slide to read the days, tap a day to change it");
        this.cy = cy;
        legendH = Ui.dp(c, 44);
    }

    @Override
    protected float below() {
        return legendH;
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), cw = w / MonthGrid.COLS;
        drawWeekdays(cv, cw);
        int off = offset(), len = month.lengthOfMonth();
        float rad = Math.min(cw, cellH) * 0.36f;
        // bands behind the days: the ovulation range, then the expected period on top
        for (int pass = 0; pass < (bands ? 3 : 0); pass++) {
            for (int dom = 1; dom <= len; dom++) {
                LocalDate d = month.atDay(dom);
                boolean in = pass == 0 ? cy.inOvulation(d) : pass == 1 ? cy.inExpected(d) : cy.inFertile(d);
                if (!in) continue;
                int i = off + dom - 1, col = i % MonthGrid.COLS, row = i / MonthGrid.COLS;
                float x0 = col * cw, top = head + row * cellH;
                boolean left = col > 0 && dom > 1 && (pass == 0 ? cy.inOvulation(d.minusDays(1)) : pass == 1 ? cy.inExpected(d.minusDays(1)) : cy.inFertile(d.minusDays(1)));
                boolean right = col < MonthGrid.COLS - 1 && dom < len && (pass == 0 ? cy.inOvulation(d.plusDays(1)) : pass == 1 ? cy.inExpected(d.plusDays(1)) : cy.inFertile(d.plusDays(1)));
                if (pass == 2) {
                    // the fertile window: a bar under the day
                    fill.setColor(Ui.READY);
                    float bh = Ui.dp(c, 3.5f), y = top + cellH - Ui.dp(c, 4);
                    band(cv, x0, y - bh, x0 + cw, y, bh / 2, left, right);
                } else {
                    fill.setColor(pass == 0 ? Ui.alpha(Ui.READY, 0.16f) : Ui.alpha(Ui.PERIOD, 0.24f));
                    float inset = Ui.dp(c, 3);
                    band(cv, x0, top + inset, x0 + cw, top + cellH - inset, Ui.dp(c, 10), left, right);
                }
            }
        }
        // the days
        txt.setTextSize(Ui.dp(c, 13));
        stroke.setStrokeWidth(Ui.dp(c, 1.4f));
        for (int dom = 1; dom <= len; dom++) {
            LocalDate d = month.atDay(dom);
            int i = off + dom - 1, col = i % MonthGrid.COLS, row = i / MonthGrid.COLS;
            float cx = cw * (col + 0.5f), cyy = head + row * cellH + cellH / 2 - Ui.dp(c, 1);
            Integer f = cy.flows.get(d);
            boolean bleeding = f != null && f >= Cycles.LIGHT;
            if (bleeding) {
                fill.setColor(f == Cycles.HEAVY ? Ui.PERIOD : Ui.alpha(Ui.PERIOD, f == Cycles.MEDIUM ? 0.7f : 0.42f));
                cv.drawCircle(cx, cyy, rad, fill);
            } else if (cy.periodOn(d) != null) {
                stroke.setColor(Ui.alpha(Ui.PERIOD, 0.6f));
                cv.drawCircle(cx, cyy, rad, stroke);
            }
            if (f != null && f == Cycles.SPOTTING) {
                fill.setColor(Ui.PERIOD);
                cv.drawCircle(cx, cyy + rad * 0.72f, Ui.dp(c, 2.2f), fill);
            }
            if (d.equals(cy.today)) {
                stroke.setColor(Ui.TEXT);
                cv.drawCircle(cx, cyy, rad + Ui.dp(c, 2.5f), stroke);
            }
            if (d.equals(readDay)) {
                stroke.setColor(Ui.TEXT_2);
                r.set(cx - cw / 2 + Ui.dp(c, 1), cyy - cellH / 2 + Ui.dp(c, 2), cx + cw / 2 - Ui.dp(c, 1), cyy + cellH / 2);
                cv.drawRoundRect(r, Ui.dp(c, 8), Ui.dp(c, 8), stroke);
            }
            txt.setColor(bleeding && f == Cycles.HEAVY ? Ui.BG : d.isAfter(cy.today) ? Ui.TEXT_2 : Ui.TEXT);
            cv.drawText(String.valueOf(dom), cx, cyy + txt.getTextSize() * 0.36f, txt);
        }
        // legend, two by two
        float ly = head + MonthGrid.ROWS * cellH + Ui.dp(c, 14);
        txt.setTextSize(Ui.dp(c, 11));
        txt.setColor(Ui.TEXT_2);
        txt.setTextAlign(Paint.Align.LEFT);
        String[] names = {bands ? "Logged" : "In the file", "Expected period", "Ovulation range", "Fertile days"};
        for (int k = 0; k < (bands ? 4 : 1); k++) {
            float x = (k % 2) * (w / 2), y = ly + (k / 2) * Ui.dp(c, 18);
            float s = Ui.dp(c, 5);
            if (k == 0) {
                fill.setColor(Ui.PERIOD);
                cv.drawCircle(x + s, y - s * 0.7f, s, fill);
            } else if (k == 3) {
                fill.setColor(Ui.READY);
                r.set(x, y - s, x + 2 * s + Ui.dp(c, 4), y - s * 0.3f);
                cv.drawRoundRect(r, s, s, fill);
            } else {
                fill.setColor(k == 1 ? Ui.alpha(Ui.PERIOD, 0.35f) : Ui.alpha(Ui.READY, 0.3f));
                r.set(x, y - 2 * s, x + 2 * s + Ui.dp(c, 4), y + s * 0.4f);
                cv.drawRoundRect(r, s, s, fill);
            }
            cv.drawText(names[k], x + 2 * s + Ui.dp(c, 10), y, txt);
        }
        txt.setTextAlign(Paint.Align.CENTER);
    }

    /** A band piece over one day, rounded only where the band starts or ends in this row. */
    private void band(Canvas cv, float x0, float top, float x1, float bottom, float radius, boolean left, boolean right) {
        // one draw per day, clipped to it, so a see through band is not drawn twice anywhere
        cv.save();
        cv.clipRect(x0, top, x1, bottom);
        r.set(left ? x0 - 2 * radius : x0, top, right ? x1 + 2 * radius : x1, bottom);
        cv.drawRoundRect(r, radius, radius, fill);
        cv.restore();
    }
}
