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

import app.openring.core.MonthGrid;

/**
 * What the month calendars ({@link BadgeCalendar}, {@link PeriodCalendar}) share: the grid ({@link MonthGrid}), the
 * weekday letters and the touch. Slide sideways (then anywhere) to read each day the finger passes, a tap without
 * movement opens the day, lifting after a slide does nothing, a move that starts upwards or downwards scrolls the page.
 */
abstract class MonthView extends View {
    interface Listener {
        /** A day read by sliding over it (on the Badges calendar also a tapped one). */
        void read(LocalDate day);

        /** A day tapped without moving: open it. */
        void tap(LocalDate day);
    }

    protected final DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    protected YearMonth month;
    protected LocalDate readDay;
    protected final float head, cellH;
    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF r = new RectF();
    private final Listener listener;
    /** A tap also reads the day before opening it (the Badges calendar). */
    private final boolean readOnTap;
    private final int slop;
    private float downX, downY;
    private boolean dragging;

    MonthView(Context c, YearMonth month, Listener listener, boolean readOnTap, String description) {
        super(c);
        this.month = month;
        this.listener = listener;
        this.readOnTap = readOnTap;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        head = Ui.dp(c, 22);
        cellH = Ui.dp(c, 42);
        stroke.setStyle(Paint.Style.STROKE);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Ui.REGULAR);
        setContentDescription(description);
    }

    /** The height under the six rows (the period calendar's legend). */
    protected float below() {
        return 0;
    }

    void month(YearMonth m) {
        month = m;
        readDay = null;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(head + MonthGrid.ROWS * cellH + below()));
    }

    /** The place of the first of the month in the first row. */
    int offset() {
        return MonthGrid.offset(month, firstDay);
    }

    /** The day at a point of the grid, or null outside this month's days. */
    LocalDate dayAt(float x, float y) {
        return MonthGrid.dayAt(month, firstDay, getWidth(), head, cellH, x, y);
    }

    /** The weekday letters above the columns, each column {@code cw} wide. */
    protected void drawWeekdays(Canvas cv, float cw) {
        Context c = getContext();
        txt.setTextSize(Ui.dp(c, 11));
        txt.setColor(Ui.TEXT_3);
        for (int i = 0; i < MonthGrid.COLS; i++) {
            String n = firstDay.plus(i).getDisplayName(TextStyle.NARROW, Locale.getDefault());
            cv.drawText(n, cw * (i + 0.5f), head - Ui.dp(c, 8), txt);
        }
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
                    int g = MonthGrid.gesture(Math.abs(e.getX() - downX), Math.abs(e.getY() - downY), slop);
                    if (g == MonthGrid.SLIDE) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (g == MonthGrid.SCROLL) {
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
                        if (readOnTap) listener.read(d);
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
}
