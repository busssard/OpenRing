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
import java.util.Map;

/**
 * The Badges page's month calendar (issue #265, phase 1b): a dot under each day with a badge, a bigger one with two or
 * more, today with an outline. Touch works like the period calendar ({@link PeriodCalendar}): slide sideways to read each
 * day the finger passes, a tap without movement opens the day, a move that starts upwards or downwards scrolls the page.
 */
final class BadgeCalendar extends View {
    interface Listener {
        /** A day read by sliding over it (or tapped). */
        void read(LocalDate day);

        /** A day tapped without moving: open it. */
        void tap(LocalDate day);
    }

    static final int ROWS = 6;
    private final Map<LocalDate, Integer> counts;
    private final LocalDate today;
    private final Listener listener;
    private final DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    private YearMonth month;
    private LocalDate readDay;
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    private final float head, cellH;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    BadgeCalendar(Context c, Map<LocalDate, Integer> counts, LocalDate today, YearMonth month, Listener listener) {
        super(c);
        this.counts = counts;
        this.today = today;
        this.month = month;
        this.listener = listener;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        head = Ui.dp(c, 22);
        cellH = Ui.dp(c, 42);
        stroke.setStyle(Paint.Style.STROKE);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Ui.REGULAR);
        setContentDescription("Badge calendar, touch and slide to read the days, tap a day to open it");
    }

    void month(YearMonth m) {
        month = m;
        readDay = null;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(head + ROWS * cellH));
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
                        listener.read(d);
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
        // weekday letters
        txt.setTextSize(Ui.dp(c, 11));
        txt.setColor(Ui.TEXT_3);
        for (int i = 0; i < 7; i++) {
            String n = firstDay.plus(i).getDisplayName(TextStyle.NARROW, Locale.getDefault());
            cv.drawText(n, cw * (i + 0.5f), head - Ui.dp(c, 8), txt);
        }
        int off = offset(), len = month.lengthOfMonth();
        float rad = Math.min(cw, cellH) * 0.36f;
        txt.setTextSize(Ui.dp(c, 13));
        stroke.setStrokeWidth(Ui.dp(c, 1.4f));
        for (int dom = 1; dom <= len; dom++) {
            LocalDate d = month.atDay(dom);
            int i = off + dom - 1, col = i % 7, row = i / 7;
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
