import app.openring.core.MonthGrid;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Where the month calendars draw each day and its dots ({@code core/MonthGrid}, used by {@code ui/MonthView}): the column,
 * row and dot geometry give exactly what the copies in BadgeCalendar, LifeCalendar and PeriodCalendar gave before they
 * moved to MonthGrid (bit for bit), and every drawn day is the day a touch on its centre reads ({@link MonthGrid#dayAt}).
 * Run with tests/run_tests.sh.
 */
public class MonthGridTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "  (" + detail + ")");
        }
    }

    static void eq(String name, Object got, Object want) {
        check(name, got == null ? want == null : got.equals(want), "got " + got + ", want " + want);
    }

    /** Ui.dp as on a phone of {@code density}: rounded to whole pixels. */
    static int dp(float density, float v) {
        return Math.round(v * density);
    }

    public static void main(String[] args) {
        cells();
        dots();
        System.out.println("month grid: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The column and row of each day as the three calendars worked them out inline, and read back by a touch. */
    static void cells() {
        int same = 0, all = 0;
        for (int off = 0; off < MonthGrid.COLS; off++) {
            for (int dom = 1; dom <= 31; dom++) {
                all++;
                int i = off + dom - 1, col = i % MonthGrid.COLS, row = i / MonthGrid.COLS; // the old inline lines
                if (MonthGrid.col(off, dom) == col && MonthGrid.row(off, dom) == row) same++;
            }
        }
        eq("cells: every day of every offset in the same column and row as before", same, all);

        int back = 0, days = 0, inGrid = 0;
        int[] widths = {700, 1080, 1439};
        float[][] sizes = {{22, 42}, {57.75f, 110.25f}, {66, 126}};
        for (DayOfWeek first : DayOfWeek.values()) {
            for (int m = 0; m < 30; m++) {
                YearMonth month = YearMonth.of(2023, 11).plusMonths(m); // includes February 2024 (29 days)
                int off = MonthGrid.offset(month, first);
                for (int dom = 1; dom <= month.lengthOfMonth(); dom++) {
                    int col = MonthGrid.col(off, dom), row = MonthGrid.row(off, dom);
                    if (col >= 0 && col < MonthGrid.COLS && row >= 0 && row < MonthGrid.ROWS) inGrid++;
                    for (int width : widths) {
                        for (float[] hc : sizes) {
                            float head = hc[0], cellH = hc[1], cw = width / (float) MonthGrid.COLS;
                            days++;
                            LocalDate read = MonthGrid.dayAt(month, first, width, head, cellH, cw * (col + 0.5f), head + row * cellH + cellH / 2);
                            if (month.atDay(dom).equals(read)) back++;
                        }
                    }
                }
            }
        }
        eq("cells: a touch on the centre of each drawn day reads that day", back, days);
        eq("cells: every day of 30 months and 7 first weekdays inside the 7 by 6 grid", inGrid, days / 9);
        YearMonth nov = YearMonth.of(2026, 11); // 1 November 2026 is a Sunday
        int monday = MonthGrid.offset(nov, DayOfWeek.MONDAY);
        eq("cells: Monday first, the 1st (a Sunday) in the last column", MonthGrid.col(monday, 1), 6);
        eq("cells: Monday first, the 2nd starts the second row", MonthGrid.row(monday, 2) * 10 + MonthGrid.col(monday, 2), 10);
        eq("cells: Monday first, the 30th in the sixth row", MonthGrid.row(monday, 30), 5);
    }

    /** The dots under a day as BadgeCalendar and LifeCalendar worked them out inline, bit for bit. */
    static void dots() {
        int same = 0, all = 0, fit = 0, fitAll = 0;
        float[] densities = {1, 1.5f, 2, 2.625f, 2.75f, 3, 3.5f, 4};
        float[] widths = {240, 320, 360, 411.4f, 700, 1080, 1439};
        for (float density : densities) {
            float dot = dp(density, 2.4f);
            int gap = dp(density, 1.6f), margin = dp(density, 4);
            for (float width : widths) {
                float cw = width * density / MonthGrid.COLS, cx = cw * 3.5f;
                for (int n = 1; n <= 40; n++) {
                    all++;
                    // the old inline lines (Ui.dp gives an int)
                    float stepB = Math.min(dot * 2 + gap, (cw - margin) / n);
                    float x0B = cx - stepB * (n - 1) / 2f;
                    float radB = Math.min(dot, stepB * 0.45f);
                    float step = MonthGrid.dotStep(dot, gap, margin, cw, n);
                    float x0 = MonthGrid.dotFirst(cx, step, n);
                    float rad = MonthGrid.dotRadius(dot, step);
                    if (Float.floatToIntBits(step) == Float.floatToIntBits(stepB) && Float.floatToIntBits(x0) == Float.floatToIntBits(x0B)
                            && Float.floatToIntBits(rad) == Float.floatToIntBits(radB)) same++;
                    if (n <= 12 && cw >= 40 * density) {
                        fitAll++;
                        // centred, inside the cell less its margin, and never touching
                        float last = x0 + (n - 1) * step;
                        boolean ok = Math.abs((x0 + last) / 2 - cx) < 1e-3f && x0 - rad >= cx - cw / 2 && last + rad <= cx + cw / 2
                                && (n == 1 || 2 * rad < step);
                        if (ok) fit++;
                    }
                }
            }
        }
        eq("dots: step, first centre and radius bit for bit as before (8 densities, 7 widths, 1 to 40 dots)", same, all);
        eq("dots: up to 12 dots centred, inside the cell and apart", fit, fitAll);
        check("dots: enough cells compared", all >= 2000 && fitAll > 300, all + " and " + fitAll);
        eq("dots: few dots keep their spacing (2 dots, 2 px dot, 2 px gap, a wide cell)", MonthGrid.dotStep(2, 2, 4, 100, 2), 6f);
        eq("dots: many dots share the cell less its margin", MonthGrid.dotStep(6, 4, 10, 100, 9), 10f);
        eq("dots: one dot sits on the centre", MonthGrid.dotFirst(50, 7, 1), 50f);
        eq("dots: three dots start one step left", MonthGrid.dotFirst(50, 7, 3), 43f);
        eq("dots: close dots get smaller", MonthGrid.dotRadius(6, 10), 4.5f);
    }
}
