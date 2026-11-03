package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;

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
final class PeriodCalendar extends View {
    interface Listener {
        /** A day read by sliding over it. */
        void read(LocalDate day);

        /** A day tapped without moving: open the editor. */
        void tap(LocalDate day);
    }

    static final int ROWS = 6;
    private final Cycles cy;
    private final Listener listener;
    /** The estimate's bands and their legend; off in the drip import's preview, which shows only the file's days. */
    boolean bands = true;
    private final DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    private YearMonth month;
    private LocalDate readDay;
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    private final float head, cellH, legendH;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    PeriodCalendar(Context c, Cycles cy, YearMonth month, Listener listener) {
        super(c);
        this.cy = cy;
        this.month = month;
        this.listener = listener;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        head = Ui.dp(c, 22);
        cellH = Ui.dp(c, 42);
        legendH = Ui.dp(c, 44);
        stroke.setStyle(Paint.Style.STROKE);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Ui.REGULAR);
        setContentDescription("Period calendar, touch and slide to read the days, tap a day to change it");
    }

    void month(YearMonth m) {
        month = m;
        readDay = null;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(head + ROWS * cellH + legendH));
    }

    /** The place of the first of the month in the first row. */
    int offset() {
        return (month.atDay(1).getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
    }

    /** The day at a point of the grid, or null outside this month's days. */
    LocalDate dayAt(float x, float y) {
        float cw = getWidth() / 7f;
        int col = (int) Math.max(0, Math.min(6, Math.floor(x / cw)));
        int row = (int) Math.floor((y - head) / cellH);
        if (row < 0 || row >= ROWS) return null;
        int dom = row * 7 + col - offset() + 1;
        return dom >= 1 && dom <= month.lengthOfMonth() ? month.atDay(dom) : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (dx > slop && dx > dy) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (dy > slop) {
                        return false; // the page scrolls
                    }
                }
                if (dragging) {
                    LocalDate d = dayAt(e.getX(), e.getY());
                    if (d != null && !d.equals(readDay)) {
                        readDay = d;
                        invalidate();
                        listener.read(d);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    LocalDate d = dayAt(e.getX(), e.getY());
                    if (d != null) {
                        readDay = d;
                        invalidate();
                        performClick();
                        listener.tap(d);
                    }
                }
                dragging = false;
                return true;
            default:
                dragging = false;
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), cw = w / 7f;
        Locale loc = Locale.getDefault();
        // weekday letters
        txt.setTextSize(Ui.dp(c, 11));
        txt.setColor(Ui.TEXT_3);
        for (int i = 0; i < 7; i++) {
            String n = firstDay.plus(i).getDisplayName(TextStyle.NARROW, loc);
            cv.drawText(n, cw * (i + 0.5f), head - Ui.dp(c, 8), txt);
        }
        int off = offset(), len = month.lengthOfMonth();
        float rad = Math.min(cw, cellH) * 0.36f;
        // bands behind the days: the ovulation range, then the expected period on top
        for (int pass = 0; pass < (bands ? 3 : 0); pass++) {
            for (int dom = 1; dom <= len; dom++) {
                LocalDate d = month.atDay(dom);
                boolean in = pass == 0 ? cy.inOvulation(d) : pass == 1 ? cy.inExpected(d) : cy.inFertile(d);
                if (!in) continue;
                int i = off + dom - 1, col = i % 7, row = i / 7;
                float x0 = col * cw, top = head + row * cellH;
                boolean left = col > 0 && dom > 1 && (pass == 0 ? cy.inOvulation(d.minusDays(1)) : pass == 1 ? cy.inExpected(d.minusDays(1)) : cy.inFertile(d.minusDays(1)));
                boolean right = col < 6 && dom < len && (pass == 0 ? cy.inOvulation(d.plusDays(1)) : pass == 1 ? cy.inExpected(d.plusDays(1)) : cy.inFertile(d.plusDays(1)));
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
            int i = off + dom - 1, col = i % 7, row = i / 7;
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
        float ly = head + ROWS * cellH + Ui.dp(c, 14);
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
