package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import app.openring.core.Texts;
import app.openring.core.analysis.Cycles;

/**
 * The period tracker's analysis at the end of Body (issue #252, phase 1a2): "Your cycles" (cycle day, days to the next
 * expected period, regularity, cycle length, period length and amount over time with their numbers, every cycle and period
 * a dot readable by touch next to the typical length) and the ring's nightly temperature line as evidence next to the
 * cycles (no ovulation is found from it yet, that is phase 2). Everything is worked out from the log on each load
 * ({@code core/analysis/Cycles}); nothing is stored.
 */
final class PeriodAnalysis {
    private PeriodAnalysis() {}

    /** The list under the cycle length chart shows this many newest cycles until "Show all" is tapped. */
    static final int LIST = 6;
    /** "Show all" was tapped, kept while the page is rebuilt. */
    static boolean listAll;

    static View cycles(final Context c, DayData d) {
        final Cycles cy = d.periods;
        final Locale loc = Locale.getDefault();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Your cycles"));
        if (cy.periods.isEmpty()) {
            card.addView(Ui.body(c, "Your cycle lengths, period lengths and amounts appear here once you have logged a period."));
            return card;
        }
        // where you are now
        int day = cy.cycleDay(cy.today);
        Integer next = cy.daysToNext();
        int late = cy.lateBy();
        String nextValue, nextUnit = "days";
        if (next == null) nextValue = "--";
        else if (late > 0) {
            nextValue = String.valueOf(late);
            nextUnit = late == 1 ? "day late" : "days late";
        } else if (next > 0) {
            nextValue = String.valueOf(next);
            nextUnit = next == 1 ? "day" : "days";
        } else {
            nextValue = next == 0 ? "Today" : "Any day";
            nextUnit = "";
        }
        card.addView(Ui.statRow(c,
                Ui.stat(c, "Cycle day", cy.stopped || day <= 0 ? "--" : String.valueOf(day), "today", Ui.PERIOD, Explain.PERIOD_CYCLE_DAY),
                Ui.stat(c, "Next period", nextValue, nextUnit, Ui.PERIOD, Explain.periodNext(next, cy.stopped, late))));
        String vary = cy.regularityLine();
        if (vary != null) {
            card.addView(Ui.text(c, vary, 14, Ui.TEXT, Ui.REGULAR));
            card.addView(PeriodCard.note(c, Explain.periodVary(cy.used.size())));
        }

        // cycle length over time
        card.addView(Ui.gap(c, 14));
        card.addView(Ui.text(c, "Cycle length", 15, Ui.TEXT, Ui.MEDIUM));
        if (cy.cycles.isEmpty()) {
            card.addView(PeriodCard.note(c, "Appears once you have logged the start of a second period: a cycle runs from one start to the next."));
        } else {
            int n = cy.cycles.size();
            double[] x = new double[n], y = new double[n];
            boolean[] faded = new boolean[n];
            String[] tips = new String[n];
            for (int i = 0; i < n; i++) {
                Cycles.Cycle k = cy.cycles.get(i);
                x[i] = k.start.toEpochDay();
                y[i] = k.length;
                faded[i] = k.leftOut;
                tips[i] = cy.cycleTip(k, loc);
            }
            card.addView(new Dots(c, x, y, faded, tips, cy.length, cy.range, " days", "Cycle lengths, touch a dot to read it"), Ui.sized(c, -1, 130));
            card.addView(PeriodCard.note(c, Explain.periodDots(true)));
            Cycles.Summary s = cy.cycleSummary();
            if (s.n > 0) {
                card.addView(summary(c, s, "days"));
                card.addView(PeriodCard.note(c, Explain.periodCycles(s.n, cy.leftOutCount())));
            }
            list(c, card, cy, loc);
        }

        // period length and amount over time
        List<Cycles.Period> done = cy.finished();
        card.addView(Ui.gap(c, 14));
        card.addView(Ui.text(c, "Period length", 15, Ui.TEXT, Ui.MEDIUM));
        if (done.isEmpty()) {
            card.addView(PeriodCard.note(c, "Appears once a period is over."));
        } else {
            int n = done.size();
            double[] x = new double[n], len = new double[n], score = new double[n];
            String[] lt = new String[n], at = new String[n];
            for (int i = 0; i < n; i++) {
                Cycles.Period p = done.get(i);
                x[i] = p.start.toEpochDay();
                len[i] = p.days();
                score[i] = cy.amountOf(p).score;
                lt[i] = cy.periodTip(p, loc);
                at[i] = cy.amountTip(p, loc);
            }
            Cycles.Summary ps = cy.periodSummary();
            card.addView(new Dots(c, x, len, new boolean[n], lt, ps.mean, Double.NaN, " days", "Period lengths, touch a dot to read it"), Ui.sized(c, -1, 120));
            card.addView(PeriodCard.note(c, Explain.periodDots(false)));
            card.addView(summary(c, ps, "days"));
            card.addView(PeriodCard.note(c, Explain.periodLengths(ps.n)));

            card.addView(Ui.gap(c, 14));
            card.addView(Ui.text(c, "Amount", 15, Ui.TEXT, Ui.MEDIUM));
            Cycles.Summary as = cy.amountSummary();
            card.addView(new Dots(c, x, score, new boolean[n], at, as.mean, Double.NaN, "", "Amount scores, touch a dot to read it"), Ui.sized(c, -1, 120));
            card.addView(Ui.statRow(c,
                    Ui.stat(c, "Average", Ui.fmt(as.mean, 1), "", 0),
                    Ui.stat(c, "Lowest", String.valueOf(as.min), "", 0),
                    Ui.stat(c, "Highest", String.valueOf(as.max), "", 0)));
            card.addView(PeriodCard.note(c, Explain.PERIOD_AMOUNT));
        }
        card.addView(Ui.gap(c, 10));
        card.addView(PeriodCard.note(c, Cycles.NOT_ADVICE));
        return card;
    }

    /** Average, spread, shortest and longest in one row. */
    static View summary(Context c, Cycles.Summary s, String unit) {
        return Ui.statRow(c,
                Ui.stat(c, "Average", Ui.fmt(s.mean, 1), unit, 0),
                Ui.stat(c, "Spread", Double.isNaN(s.sd) ? "--" : Ui.fmt(s.sd, 1), unit, 0),
                Ui.stat(c, "Shortest", String.valueOf(s.min), unit, 0),
                Ui.stat(c, "Longest", String.valueOf(s.max), unit, 0));
    }

    /** Every cycle, newest first; the newest {@link #LIST} until "Show all" is tapped. */
    static void list(final Context c, final LinearLayout card, final Cycles cy, final Locale loc) {
        final LinearLayout box = Ui.vbox(c);
        card.addView(Ui.gap(c, 6));
        card.addView(box, Ui.matchWrap());
        fillList(c, box, cy, loc);
    }

    static void fillList(final Context c, final LinearLayout box, final Cycles cy, final Locale loc) {
        box.removeAllViews();
        int n = cy.cycles.size(), shown = listAll ? n : Math.min(n, LIST);
        for (int i = n - 1; i >= n - shown; i--) {
            Cycles.Cycle k = cy.cycles.get(i);
            box.addView(Ui.text(c, cy.cycleRow(k, loc), 13, k.leftOut || cy.unusual(k) ? Ui.TEXT_2 : Ui.TEXT, Ui.REGULAR));
        }
        if (n > LIST) {
            TextView more = Ui.smallButton(c, listAll ? "Show the newest " + LIST : "Show all " + n + " cycles", Ui.TEXT_2);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listAll = !listAll;
                    fillList(c, box, cy, loc);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, Ui.dp(c, 6), 0, 0);
            box.addView(more, lp);
        }
    }

    // ------------------------------------------------------------------- temperature

    static View temperature(Context c, DayData d) {
        Cycles cy = d.periods;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Night temperature and your cycle"));
        LocalDate from = cy.tempFrom();
        // the time axis fits the data: from the first night (or drip morning) with a value, when that is later
        LocalDate first = null;
        if (!d.periodTemps.isEmpty()) first = d.periodTemps.firstKey();
        if (!d.periodMouth.isEmpty() && (first == null || d.periodMouth.firstKey().isBefore(first))) first = d.periodMouth.firstKey();
        if (first != null && first.isAfter(from)) from = first;
        boolean mouth = !d.periodMouth.isEmpty();
        card.addView(Ui.gap(c, 6));
        card.addView(new TempLine(c, cy, from, cy.today, d.periodTemps, d.periodMouth), Ui.sized(c, -1, mouth ? 170 : 150));
        if (mouth) {
            // the two lines, labelled in their own colours
            LinearLayout keys = Ui.hbox(c);
            keys.addView(Ui.text(c, "\u25CF Ring, skin at night (left)", 12, Ui.TEMP, Ui.MEDIUM), Ui.weight(1));
            keys.addView(Ui.text(c, "\u25CF Mouth, morning (right)", 12, MOUTH, Ui.MEDIUM), Ui.weight(1));
            LinearLayout.LayoutParams kl = Ui.matchWrap();
            kl.setMargins(0, Ui.dp(c, 4), 0, 0);
            card.addView(keys, kl);
        }
        card.addView(PeriodCard.note(c, Explain.PERIOD_TEMP));
        if (mouth) card.addView(PeriodCard.note(c, Explain.PERIOD_MOUTH));
        return card;
    }

    /** The colour of the drip temperatures by mouth (phase 1b), apart from the ring's line. */
    static final int MOUTH = Ui.OXY;

    // ------------------------------------------------------------------------ charts

    static String shortDate(LocalDate d, boolean year) {
        return d.format(DateTimeFormatter.ofPattern(year ? "MMM yyyy" : "d MMM", Locale.getDefault()));
    }

    /**
     * One dot per cycle or period over time (x: its start day), with a dashed line at the typical length or the average and
     * the estimate's range as a band. Touch reads the nearest dot.
     */
    static final class Dots extends Charts.Scrub {
        private final double[] x, y;
        private final boolean[] faded;
        private final String[] tips;
        private final double line, band;
        private final String unit;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG),
                ref = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();

        Dots(Context c, double[] x, double[] y, boolean[] faded, String[] tips, double line, double band, String unit, String description) {
            super(c);
            this.x = x;
            this.y = y;
            this.faded = faded;
            this.tips = tips;
            this.line = line;
            this.band = band;
            this.unit = unit;
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
            grid.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 3), Ui.dp(c, 4)}, 0));
            ref.setColor(Ui.alpha(Ui.PERIOD, 0.8f));
            ref.setStrokeWidth(Ui.dp(c, 1.4f));
            ref.setStyle(Paint.Style.STROKE);
            ref.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 5), Ui.dp(c, 4)}, 0));
            fill.setColor(Ui.alpha(Ui.PERIOD, 0.14f));
            setContentDescription(description);
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            float left = Ui.dp(c, 30), right = w - Ui.dp(c, 8), top = Ui.dp(c, 26), bottom = h - Ui.dp(c, 18);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double v : y) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            if (!Double.isNaN(line)) {
                double b = Double.isNaN(band) ? 0 : band;
                lo = Math.min(lo, line - b);
                hi = Math.max(hi, line + b);
            }
            lo = Math.floor(lo - 1);
            hi = Math.ceil(hi + 1);
            if (hi - lo < 4) {
                double m = (hi + lo) / 2;
                lo = Math.floor(m - 2);
                hi = lo + 4;
            }
            double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
            for (double v : x) {
                x0 = Math.min(x0, v);
                x1 = Math.max(x1, v);
            }
            if (x1 - x0 < 30) {
                double m = (x0 + x1) / 2;
                x0 = m - 15;
                x1 = m + 15;
            }
            // guides with whole number labels
            txt.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 2; i++) {
                double v = lo + (hi - lo) * i / 2;
                float gy = py(v, lo, hi, top, bottom);
                cv.drawLine(left, gy, w, gy, grid);
                cv.drawText(String.valueOf(Math.round(v)), 0, gy + Ui.dp(c, 3), txt);
            }
            if (!Double.isNaN(line)) {
                if (!Double.isNaN(band) && band > 0) cv.drawRect(left, py(line + band, lo, hi, top, bottom), w, py(line - band, lo, hi, top, bottom), fill);
                float ly = py(line, lo, hi, top, bottom);
                Path p = new Path();
                p.moveTo(left, ly);
                p.lineTo(w, ly);
                cv.drawPath(p, ref);
            }
            float[] xs = new float[x.length], ys = new float[x.length];
            float r = Ui.dp(c, 4);
            for (int i = 0; i < x.length; i++) {
                xs[i] = (float) (left + r + (x[i] - x0) / (x1 - x0) * (right - left - 2 * r));
                ys[i] = py(y[i], lo, hi, top, bottom);
                dot.setColor(faded[i] ? Ui.alpha(Ui.PERIOD, 0.35f) : Ui.PERIOD);
                cv.drawCircle(xs[i], ys[i], r, dot);
            }
            // the first and last start on the time axis, with the year once it spans most of one
            boolean year = x1 - x0 > 300;
            float base = h - Ui.dp(c, 3);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(shortDate(LocalDate.ofEpochDay((long) Math.ceil(x0)), year), left, base, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(shortDate(LocalDate.ofEpochDay((long) Math.floor(x1)), year), w, base, txt);
            if (!Float.isNaN(scrubX) && x.length > 0) {
                int best = 0;
                for (int i = 1; i < x.length; i++) if (Math.abs(xs[i] - scrubX) < Math.abs(xs[best] - scrubX)) best = i;
                dot.setColor(Ui.TEXT);
                cv.drawCircle(xs[best], ys[best], r * 1.4f, dot);
                drawTip(cv, xs[best], Ui.dp(c, 22), bottom, tips[best]);
            }
        }

        private static float py(double v, double lo, double hi, float top, float bottom) {
            return (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
        }
    }

    /**
     * The nights' skin temperature compared with the usual, day by day from {@code from} to {@code to}: period days as red
     * columns, the estimate's ovulation range green and its expected period light red behind the line. With drip
     * temperatures by mouth (phase 1b) a second line in its own colour on its own scale (°C, on the right): a different
     * measurement, never mixed with the ring's; a reading drip left out is a hollow dot outside the line. Touch reads the
     * day under the finger, also one without a value.
     */
    static final class TempLine extends Charts.Scrub {
        private final Cycles cy;
        private final LocalDate from, to;
        private final TreeMap<LocalDate, Double> v;
        private final TreeMap<LocalDate, app.openring.core.DripCsv.Temp> mouth;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(Paint.ANTI_ALIAS_FLAG), col = new Paint(), mline = new Paint(Paint.ANTI_ALIAS_FLAG), mdot = new Paint(Paint.ANTI_ALIAS_FLAG);

        TempLine(Context c, Cycles cy, LocalDate from, LocalDate to, TreeMap<LocalDate, Double> v, TreeMap<LocalDate, app.openring.core.DripCsv.Temp> mouth) {
            super(c);
            this.cy = cy;
            this.from = from;
            this.to = to.isBefore(from) ? from : to;
            this.v = v;
            this.mouth = mouth;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Ui.TEMP);
            dot.setColor(Ui.TEMP);
            mline.setStyle(Paint.Style.STROKE);
            mline.setStrokeWidth(Ui.dp(c, 1.6f));
            mline.setStrokeJoin(Paint.Join.ROUND);
            mline.setColor(MOUTH);
            mdot.setColor(MOUTH);
            mdot.setStrokeWidth(Ui.dp(c, 1.2f));
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
            grid.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 3), Ui.dp(c, 4)}, 0));
            setContentDescription("Night temperature since " + shortDate(from, false) + (mouth.isEmpty() ? "" : ", with morning temperatures by mouth from drip")
                    + ", touch to read a night");
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            java.util.SortedMap<LocalDate, app.openring.core.DripCsv.Temp> ms = mouth.subMap(from, true, to, true);
            boolean hasMouth = !ms.isEmpty();
            float left = Ui.dp(c, 34), right = hasMouth ? w - Ui.dp(c, 38) : w, top = Ui.dp(c, hasMouth ? 40 : 26), bottom = h - Ui.dp(c, 18);
            int n = (int) ChronoUnit.DAYS.between(from, to) + 1;
            float cw = (right - left) / n;
            double span = 0.6;
            for (double x : v.subMap(from, true, to, true).values()) span = Math.max(span, Math.abs(x) + 0.15);
            span = Math.ceil(span * 10) / 10;
            // the mouth scale: its own range in °C, at least 0.6 wide
            double mlo = Double.POSITIVE_INFINITY, mhi = Double.NEGATIVE_INFINITY;
            for (app.openring.core.DripCsv.Temp t : ms.values()) {
                mlo = Math.min(mlo, t.c);
                mhi = Math.max(mhi, t.c);
            }
            if (hasMouth) {
                mlo = Math.floor((mlo - 0.1) * 10) / 10;
                mhi = Math.ceil((mhi + 0.1) * 10) / 10;
                if (mhi - mlo < 0.6) {
                    double m = (mhi + mlo) / 2;
                    mlo = Math.floor((m - 0.3) * 10) / 10;
                    mhi = mlo + 0.6;
                }
            }
            // the bands behind: period days, the estimate's expected period and ovulation range
            for (int i = 0; i < n; i++) {
                LocalDate d = from.plusDays(i);
                int color = cy.periodOn(d) != null ? Ui.alpha(Ui.PERIOD, 0.24f) : cy.inOvulation(d) ? Ui.alpha(Ui.READY, 0.18f)
                        : cy.inExpected(d) ? Ui.alpha(Ui.PERIOD, 0.12f) : 0;
                if (color == 0) continue;
                col.setColor(color);
                cv.drawRect(left + i * cw, top, left + (i + 1) * cw, bottom, col);
            }
            txt.setTextAlign(Paint.Align.LEFT);
            for (int k = -1; k <= 1; k++) {
                double val = k * span;
                float gy = py(val, span, top, bottom);
                cv.drawLine(left, gy, right, gy, grid);
                cv.drawText(Texts.signed(val, Locale.getDefault()), 0, gy + Ui.dp(c, 3), txt);
            }
            if (hasMouth) {
                txt.setTextAlign(Paint.Align.RIGHT);
                txt.setColor(MOUTH);
                for (int k = 0; k <= 2; k++) {
                    double val = mlo + (mhi - mlo) * k / 2;
                    cv.drawText(String.format(Locale.getDefault(), "%.1f", val), w, my(val, mlo, mhi, top, bottom) + Ui.dp(c, 3), txt);
                }
                txt.setColor(Ui.TEXT_3);
            }
            Path p = new Path();
            boolean started = false;
            LocalDate prev = null;
            for (java.util.Map.Entry<LocalDate, Double> e : v.subMap(from, true, to, true).entrySet()) {
                int i = (int) ChronoUnit.DAYS.between(from, e.getKey());
                float x = left + (i + 0.5f) * cw, y = py(e.getValue(), span, top, bottom);
                if (!started || ChronoUnit.DAYS.between(prev, e.getKey()) > 3) p.moveTo(x, y);
                else p.lineTo(x, y);
                started = true;
                prev = e.getKey();
                cv.drawCircle(x, y, Ui.dp(c, 2.5f), dot);
            }
            cv.drawPath(p, line);
            if (hasMouth) {
                // the readings drip counted make the line; one it left out is a hollow dot
                Path mp = new Path();
                boolean ms0 = false;
                LocalDate mprev = null;
                for (java.util.Map.Entry<LocalDate, app.openring.core.DripCsv.Temp> e : ms.entrySet()) {
                    int i = (int) ChronoUnit.DAYS.between(from, e.getKey());
                    float x = left + (i + 0.5f) * cw, y = my(e.getValue().c, mlo, mhi, top, bottom);
                    if (e.getValue().excluded) {
                        mdot.setStyle(Paint.Style.STROKE);
                        cv.drawCircle(x, y, Ui.dp(c, 2.8f), mdot);
                        mdot.setStyle(Paint.Style.FILL);
                        continue;
                    }
                    if (!ms0 || ChronoUnit.DAYS.between(mprev, e.getKey()) > 3) mp.moveTo(x, y);
                    else mp.lineTo(x, y);
                    ms0 = true;
                    mprev = e.getKey();
                    cv.drawCircle(x, y, Ui.dp(c, 2.2f), mdot);
                }
                cv.drawPath(mp, mline);
            }
            if (!started && !hasMouth) {
                txt.setTextAlign(Paint.Align.CENTER);
                cv.drawText("No nights with the ring in this time yet", (left + right) / 2, (top + bottom) / 2, txt);
            }
            float base = h - Ui.dp(c, 3);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(shortDate(from, false), left, base, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(shortDate(to, false), right, base, txt);
            if (!Float.isNaN(scrubX)) {
                int i = (int) Math.max(0, Math.min(n - 1, Math.floor((scrubX - left) / cw)));
                LocalDate d = from.plusDays(i);
                Double val = v.get(d);
                app.openring.core.DripCsv.Temp m = hasMouth ? mouth.get(d) : null;
                float x = left + (i + 0.5f) * cw;
                if (val != null) {
                    dot.setColor(Ui.TEXT);
                    cv.drawCircle(x, py(val, span, top, bottom), Ui.dp(c, 4), dot);
                    dot.setColor(Ui.TEMP);
                }
                if (m != null) {
                    mdot.setColor(Ui.TEXT);
                    cv.drawCircle(x, my(m.c, mlo, mhi, top, bottom), Ui.dp(c, 4), mdot);
                    mdot.setColor(MOUTH);
                }
                double dev = val == null ? Double.NaN : val;
                String tip = hasMouth ? cy.tempTip(d, dev, m == null ? Double.NaN : m.c, m == null ? null : m.time, m != null && m.excluded, Locale.getDefault())
                        : cy.tempTip(d, dev, Locale.getDefault());
                drawTip(cv, x, Ui.dp(c, hasMouth ? 36 : 22), bottom, tip);
            }
        }

        private static float my(double val, double lo, double hi, float top, float bottom) {
            return (float) (bottom - (val - lo) / (hi - lo) * (bottom - top));
        }

        private static float py(double val, double span, float top, float bottom) {
            return (float) (bottom - (val + span) / (2 * span) * (bottom - top));
        }
    }
}
