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
 * cycles. Phase 2: the ovulation each cycle's temperature rise shows ({@code core/analysis/TempRise}), a line under each
 * cycle, and the rise marked on the temperature line (baseline, high nights, the likely ovulation). Everything is worked
 * out from the log and the nights on each load ({@code core/analysis/Cycles}); nothing is stored.
 */
final class PeriodAnalysis {
    private PeriodAnalysis() {}

    /** The list under the cycle length chart shows this many newest cycles until "Show all" is tapped. */
    static final int LIST = 6;
    /** "Show all" was tapped, kept while the page is rebuilt. */
    static boolean listAll;
    /** The How it works of Your cycles and of the night temperature card are open, kept while the page is rebuilt. */
    static final boolean[] howOpen = {false}, tempHowOpen = {false}, accHowOpen = {false};

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
            // the dashed line is the length the estimate expects (the method of Settings, issue #383), the band its range
            card.addView(new Dots(c, x, y, faded, tips, cy.step, cy.range, " days", "Cycle lengths, touch a dot to read it"), Ui.sized(c, -1, 130));
            card.addView(PeriodCard.note(c, Explain.periodDots(true)));
            Cycles.Summary s = cy.cycleSummary();
            if (s.n > 0) {
                card.addView(summary(c, s, "days"));
                card.addView(PeriodCard.note(c, Explain.periodCycles(s.n, cy.leftOutCount())));
            }
            list(c, card, cy, loc);
        }
        if (cy.rise != null) ovulation(c, card, cy, loc);

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
        // how the numbers and charts are worked out, and that they are not birth control: one tap away (owner, 3 November)
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.howItWorks(c, howOpen, Explain.periodCyclesHow(cy)), Ui.matchWrap());
        return card;
    }

    /** Average, spread, shortest and longest in one row. */
    /** The card's caption (screens.sh scrolls to it). */
    static final String ACC_CAPTION = "How close the estimates were";

    /**
     * "How close the estimates were" (issue #383), after Your cycles: each prediction method's average error in days and how
     * often the start fell in its range on the person's own cycles (the backtest, {@code cycleforecast.Backtest}), the one in
     * use marked; a dot for each period with the error of the method in use, touch readable; and the temperature's replay
     * against the calendar on the same days ({@code RiseBacktest}). The evidence for Settings' Prediction; nothing stored.
     */
    static View accuracy(Context c, DayData d) {
        Cycles cy = d.periods;
        Locale loc = Locale.getDefault();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, ACC_CAPTION));
        app.openring.core.analysis.cycleforecast.Backtest bt = cy.backtest();
        if (bt.scored() == 0) {
            card.addView(Ui.body(c, "Appears once you have logged " + (app.openring.core.analysis.cycleforecast.Backtest.FROM + 2)
                    + " periods: each method then says when your 5th period would start from the 3 cycles before it, and so on, and you see which came closest."));
            return card;
        }
        card.addView(Ui.text(c, "Each method said when each of your last " + bt.scored() + " periods would start, from only the cycles before it.", 14, Ui.TEXT, Ui.REGULAR));
        double max = 0;
        for (app.openring.core.analysis.cycleforecast.Backtest.Score sc : bt.scores) max = Math.max(max, sc.mae);
        for (app.openring.core.analysis.cycleforecast.Backtest.Score sc : bt.scores) card.addView(methodRow(c, cy, sc, max), Ui.matchWrap());
        card.addView(PeriodCard.note(c, Explain.PERIOD_ACC_ROWS));
        if (bt.farOut > 0) card.addView(PeriodCard.note(c, Texts.count(bt.farOut, "cycle", "cycles") + " far from the rest "
                + (bt.farOut == 1 ? "is" : "are") + " not counted, probably a period that was not logged."));

        // the evidence: each period's error with the method in use
        app.openring.core.analysis.cycleforecast.Backtest.Score use = bt.of(cy.methodId);
        card.addView(Ui.gap(c, 14));
        card.addView(Ui.text(c, "Each period, " + app.openring.core.analysis.cycleforecast.Methods.name(cy.methodId), 15, Ui.TEXT, Ui.MEDIUM));
        int n = use.n;
        double[] x = new double[n], y = new double[n];
        String[] tips = new String[n];
        for (int i = 0; i < n; i++) {
            Cycles.Cycle k = cy.cycles.get(use.index[i]);
            x[i] = k.next.toEpochDay();
            y[i] = use.late(i);
            tips[i] = errorTip(cy, k, use.expected[i], use.range[i], loc);
        }
        card.addView(new Dots(c, x, y, new boolean[n], tips, 0, use.avgRange, " days", "Days each period came after the expected day, touch a dot to read it"), Ui.sized(c, -1, 130));
        card.addView(PeriodCard.note(c, Explain.PERIOD_ACC_DOTS));

        // the temperature on top
        app.openring.core.analysis.RiseBacktest rb = cy.riseBacktest();
        card.addView(Ui.gap(c, 14));
        card.addView(Ui.text(c, "With your temperature", 15, Ui.TEXT, Ui.MEDIUM));
        if (rb == null || rb.n() == 0) {
            card.addView(PeriodCard.note(c, "Once a cycle with a temperature rise has ended with a logged period, this compares the estimate from the rise with the calendar."));
        } else {
            card.addView(Ui.statRow(c,
                    Ui.stat(c, "Temperature", Ui.fmt(rb.mae, 1), "days off", Ui.PERIOD),
                    Ui.stat(c, "Calendar", Ui.fmt(rb.calendarMae, 1), "days off", 0)));
            card.addView(PeriodCard.note(c, Explain.periodAccTemp(rb)));
            if (!cy.riseUsed) card.addView(PeriodCard.note(c, cy.riseOff()));
        }
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.howItWorks(c, accHowOpen, Explain.periodAccuracyHow()), Ui.matchWrap());
        return card;
    }

    /** One method: its name (the one in use marked), a bar as long as its error, the error and how often it was in range. */
    static View methodRow(Context c, Cycles cy, app.openring.core.analysis.cycleforecast.Backtest.Score sc, double max) {
        boolean inUse = sc.method == cy.methodId;
        int color = inUse ? Ui.PERIOD : Ui.TEXT_3;
        LinearLayout row = Ui.vbox(c);
        row.setPadding(0, Ui.dp(c, 8), 0, 0);
        LinearLayout top = Ui.hbox(c);
        String name = app.openring.core.analysis.cycleforecast.Methods.name(sc.method);
        if (inUse) name += cy.choice == app.openring.core.analysis.cycleforecast.Methods.BEST ? ", in use (best for you)" : ", in use";
        TextView nm = Ui.text(c, name, 14, inUse ? Ui.TEXT : Ui.TEXT_2, inUse ? Ui.MEDIUM : Ui.REGULAR);
        top.addView(nm, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(Ui.text(c, Ui.fmt(sc.mae, 1) + " days off", 14, inUse ? Ui.TEXT : Ui.TEXT_2, inUse ? Ui.MEDIUM : Ui.REGULAR));
        row.addView(top, Ui.matchWrap());
        LinearLayout bar = Ui.hbox(c);
        View fill = new View(c);
        fill.setBackground(Ui.rounded(Ui.alpha(color, inUse ? 0.85f : 0.45f), Ui.dp(c, 3)));
        float share = max > 0 ? (float) Math.max(0.02, sc.mae / max) : 0.02f;
        bar.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(c, 6), share));
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, Ui.dp(c, 6), 1 - share));
        LinearLayout.LayoutParams bl = Ui.matchWrap();
        bl.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));
        row.addView(bar, bl);
        row.addView(Ui.text(c, "In range " + sc.hits + " of " + sc.n + " (" + Math.round(100 * sc.hitRate()) + " %), give or take "
                + Ui.fmt(sc.avgRange, 1) + " days on average", 12, Ui.TEXT_3, Ui.REGULAR));
        row.setContentDescription(name + ": " + Ui.fmt(sc.mae, 1) + " days off on average, in range " + sc.hits + " of " + sc.n);
        return row;
    }

    /** What touching a period's dot reads, short enough for one line: "4 Jan: expected 6 Jan, 2 days early, in range". */
    static String errorTip(Cycles cy, Cycles.Cycle k, int expectedLength, int range, Locale loc) {
        LocalDate expected = k.start.plusDays(expectedLength);
        int late = (int) ChronoUnit.DAYS.between(expected, k.next);
        String when = late == 0 ? "on that day" : Texts.count(Math.abs(late), "day", "days") + (late > 0 ? " late" : " early");
        return cy.date(k.next, loc) + ": expected " + cy.date(expected, loc) + ", " + when + (Math.abs(late) <= range ? ", in range" : ", out of range");
    }

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
            // phase 2: where ovulation most likely was, from the temperature rise
            app.openring.core.analysis.TempRise.Result r = cy.rise == null || k.leftOut ? null : cy.rise.at(k.start);
            if (r != null) {
                TextView t = Ui.text(c, app.openring.core.analysis.TempRise.line(r, cy, loc), 12, r.found() ? Ui.TEXT_2 : Ui.TEXT_3, Ui.REGULAR);
                t.setPadding(Ui.dp(c, 12), 0, 0, Ui.dp(c, 4));
                box.addView(t);
            }
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

    /** "Ovulation from your temperature": this cycle, in how many cycles a rise was found, the own luteal length, how. */
    static void ovulation(Context c, LinearLayout card, Cycles cy, Locale loc) {
        app.openring.core.analysis.TempRise.Analysis a = cy.rise;
        card.addView(Ui.gap(c, 14));
        card.addView(Ui.text(c, "Ovulation from your temperature", 15, Ui.TEXT, Ui.MEDIUM));
        if (a.current != null) card.addView(Ui.text(c, "This cycle: " + app.openring.core.analysis.TempRise.line(a.current, cy, loc), 13, Ui.TEXT, Ui.REGULAR));
        int over = a.over(), found = a.overFound();
        card.addView(Ui.statRow(c,
                Ui.stat(c, "Rise found", over == 0 ? "--" : found + " of " + over, "cycles", Ui.PERIOD, Explain.periodFound(over, found)),
                Ui.stat(c, "Ovulation to period", a.ownLuteal() ? String.valueOf(a.lutealDays()) : "--", "days", Ui.PERIOD,
                        Explain.periodLuteal(a.lutealN, a.lutealDays(), a.lutealSd))));
        // how the rise is found: in How it works at the end of the card (Explain.periodCyclesHow)
    }

    // ------------------------------------------------------------------- temperature

    /** The caption of Body's temperature chart while the period tracker draws the cycle on it. */
    static final String TEMP_CAPTION = "Temperature deviation and your cycle";

    /**
     * Body's night temperature chart with the cycle drawn on it (owner's feedback of 11 November: one temperature chart, not
     * a second one at the end of Body): the nights compared with the usual, never fewer days than the plain chart's 30, with
     * the period days, the measured rise, and the expected period and ovulation range ahead, readable by touch.
     */
    static View temperature(Context c, DayData d) {
        Cycles cy = d.periods;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, TEMP_CAPTION));
        LocalDate from = d.periodFrom != null ? d.periodFrom : cy.tempFrom(), to = d.periodTo != null ? d.periodTo : cy.today;
        // the time axis fits the data: from the first night (or drip morning) with a value, when that is later
        LocalDate first = null;
        if (!d.periodTemps.isEmpty()) first = d.periodTemps.firstKey();
        if (!d.periodMouth.isEmpty() && (first == null || d.periodMouth.firstKey().isBefore(first))) first = d.periodMouth.firstKey();
        if (first != null && first.isAfter(from) && first.isBefore(to)) from = first;
        boolean mouth = !d.periodMouth.isEmpty();
        card.addView(Ui.gap(c, 6));
        // a found rise adds lines to the touch text: a little more room
        int h = (mouth ? 190 : 170) + (cy.rise != null ? 30 : 0);
        card.addView(new TempLine(c, cy, from, to, d.periodTemps, d.periodMouth), Ui.sized(c, -1, h));
        card.addView(legend(c, cy, mouth), legendParams(c));
        card.addView(PeriodCard.note(c, Explain.PERIOD_TEMP_LINE));
        card.addView(Ui.howItWorks(c, tempHowOpen, Explain.periodTempHow(mouth)), Ui.matchWrap());
        return card;
    }

    static LinearLayout.LayoutParams legendParams(Context c) {
        LinearLayout.LayoutParams kl = Ui.matchWrap();
        kl.setMargins(0, Ui.dp(c, 4), 0, 0);
        return kl;
    }

    /** One small legend line: each mark in its own colour, the parts the chart can show. */
    static TextView legend(Context c, Cycles cy, boolean mouth) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        key(sb, "\u25A0", Ui.alpha(Ui.PERIOD, 0.6f), "Period");
        if (cy.estimates()) key(sb, "\u25A0", Ui.alpha(Ui.PERIOD, 0.35f), "Expected period");
        if (cy.estimates()) {
            key(sb, "\u00A6", Ui.PERIOD, "Most likely first day");
            key(sb, "\u25A0", Ui.alpha(Ui.READY, 0.45f), "Ovulation range");
            key(sb, "\u25AC", Ui.READY, "Fertile days");
        }
        if (cy.rise != null) key(sb, "|", Ui.READY, "Measured ovulation");
        if (cy.rise != null) key(sb, "\u25CB", Ui.TEXT, "High night");
        if (mouth) {
            key(sb, "\u25CF", Ui.TEMP, "Ring, skin at night (left)");
            key(sb, "\u25CF", MOUTH, "Mouth, morning (right)");
        }
        TextView t = Ui.text(c, "", 12, Ui.TEXT_2, Ui.REGULAR);
        t.setText(sb);
        t.setContentDescription("Legend of the temperature chart");
        return t;
    }

    private static void key(android.text.SpannableStringBuilder sb, String mark, int color, String name) {
        if (sb.length() > 0) sb.append("   ");
        int a = sb.length();
        sb.append(mark);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), a, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\u00A0').append(name.replace(' ', '\u00A0'));
    }

    /** Screen test (extra {@code period_rise}, demo mode only): the temperature line opens touched on the first high night. */
    static boolean risePending;

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
     * columns, the estimate's ovulation range green and its expected period light red behind the line, each darkest on its
     * most likely day, the expected first day a dashed red line, the fertile days a green bar at the bottom, and a thin line
     * after today when {@code to} lies ahead. With drip
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
                grid = new Paint(Paint.ANTI_ALIAS_FLAG), col = new Paint(), mline = new Paint(Paint.ANTI_ALIAS_FLAG), mdot = new Paint(Paint.ANTI_ALIAS_FLAG),
                base = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), ovu = new Paint(Paint.ANTI_ALIAS_FLAG);

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
            base.setStyle(Paint.Style.STROKE);
            base.setStrokeWidth(Ui.dp(c, 1.4f));
            base.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 5), Ui.dp(c, 3)}, 0));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 1.5f));
            ovu.setColor(Ui.READY);
            ovu.setStrokeWidth(Ui.dp(c, 1.5f));
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
            // the bands behind: period days, the estimate's ovulation range and expected period, each darkest on its most
            // likely day (owner's feedback of 11 November), and the fertile days as a bar along the bottom
            java.util.List<Cycles.Ahead> ahead = cy.ahead(to);
            Cycles.Ahead one = ahead.isEmpty() ? null : ahead.get(0);
            for (int i = 0; i < n; i++) {
                LocalDate d = from.plusDays(i);
                double wo = one == null ? 0 : one.ovulationWeight(d), wp = one == null ? 0 : one.periodWeight(d);
                int color = cy.periodOn(d) != null ? Ui.alpha(Ui.PERIOD, 0.24f) : wo > 0 ? Ui.alpha(Ui.READY, (float) (0.07 + 0.2 * wo))
                        : wp > 0 ? Ui.alpha(Ui.PERIOD, (float) (0.06 + 0.2 * wp)) : 0;
                if (color != 0) {
                    col.setColor(color);
                    cv.drawRect(left + i * cw, top, left + (i + 1) * cw, bottom, col);
                }
                if (one != null && one.inFertile(d)) {
                    col.setColor(Ui.READY);
                    cv.drawRect(left + i * cw, bottom - Ui.dp(c, 3), left + (i + 1) * cw, bottom, col);
                }
            }
            if (one != null && !one.start.isBefore(from) && !one.start.isAfter(to)) {
                // the most likely first day of the next period: a dashed red line
                base.setColor(Ui.alpha(Ui.PERIOD, 0.9f));
                float x = xOf(one.start, cw, left);
                Path dp = new Path();
                dp.moveTo(x, top);
                dp.lineTo(x, bottom);
                cv.drawPath(dp, base);
            }
            if (to.isAfter(cy.today) && !cy.today.isBefore(from)) {
                // where the nights end and the days to come begin
                float x = left + (ChronoUnit.DAYS.between(from, cy.today) + 1) * cw;
                cv.drawLine(x, top, x, bottom, grid);
                txt.setTextAlign(Paint.Align.CENTER);
                if (x - left > Ui.dp(c, 60) && right - x > Ui.dp(c, 60)) cv.drawText("today", x, h - Ui.dp(c, 3), txt);
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
            marks(cv, cw, left, top, bottom, span, hasMouth, mlo, mhi);
            if (risePending && Float.isNaN(scrubX)) {
                // the screen test's touch: the first high night of the newest rise on the line
                LocalDate f = null;
                for (app.openring.core.analysis.TempRise.Result r : cy.rise == null ? new java.util.ArrayList<app.openring.core.analysis.TempRise.Result>() : cy.rise.cycles) {
                    if (r.ring.found() && !r.ring.first.isBefore(from)) f = r.ring.first;
                }
                if (f != null) scrubX = xOf(f, cw, left);
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

        /**
         * The rise each cycle shows (phase 2), evidence first: the baseline as a dashed line over its six nights and the high
         * ones, a ring around each high night, and a thin green line at the likely ovulation. The ring's line shows the
         * difference from the usual while the rise is found in the nightly temperature itself, so its baseline is drawn
         * moved by the middle difference between the two on those nights (a few hundredths at most).
         */
        private void marks(Canvas cv, float cw, float left, float top, float bottom, double span, boolean hasMouth, double mlo, double mhi) {
            if (cy.rise == null) return;
            Context c = getContext();
            for (app.openring.core.analysis.TempRise.Result r : cy.rise.cycles) {
                if (r.ring.found()) {
                    java.util.List<Double> off = new java.util.ArrayList<Double>();
                    for (java.util.Map.Entry<LocalDate, Double> e : r.ring.values.entrySet()) {
                        Double dv = v.get(e.getKey());
                        if (dv != null) off.add(e.getValue() - dv);
                    }
                    if (!off.isEmpty()) {
                        java.util.Collections.sort(off);
                        double b = r.ring.baseline - off.get(off.size() / 2);
                        base.setColor(Ui.alpha(Ui.TEMP, 0.9f));
                        line(cv, r.ring, cw, left, (float) py(b, span, top, bottom));
                        ring.setColor(Ui.TEXT);
                        for (LocalDate d : r.ring.high) {
                            Double dv = v.get(d);
                            float x = xOf(d, cw, left);
                            if (dv != null && x >= left) cv.drawCircle(x, py(dv, span, top, bottom), Ui.dp(c, 4.5f), ring);
                        }
                    }
                }
                if (hasMouth && r.mouth.found()) {
                    base.setColor(Ui.alpha(MOUTH, 0.9f));
                    line(cv, r.mouth, cw, left, my(r.mouth.baseline, mlo, mhi, top, bottom));
                    ring.setColor(MOUTH);
                    for (LocalDate d : r.mouth.high) {
                        app.openring.core.DripCsv.Temp m = mouth.get(d);
                        float x = xOf(d, cw, left);
                        if (m != null && x >= left) cv.drawCircle(x, my(m.c, mlo, mhi, top, bottom), Ui.dp(c, 4.2f), ring);
                    }
                }
                if (r.found()) {
                    float x = xOf(r.ovulation, cw, left);
                    if (x >= left && !r.ovulation.isAfter(to)) cv.drawLine(x, top, x, bottom, ovu);
                }
            }
        }

        /** The dashed baseline at {@code y} from the first of the six nights to the last high one, inside the chart. */
        private void line(Canvas cv, app.openring.core.analysis.TempRise.Rise r, float cw, float left, float y) {
            float x0 = Math.max(left, xOf(r.low.get(0), cw, left)), x1 = xOf(r.high.get(r.high.size() - 1), cw, left);
            if (x1 <= left) return;
            Path p = new Path();
            p.moveTo(x0, y);
            p.lineTo(x1, y);
            cv.drawPath(p, base);
        }

        private float xOf(LocalDate d, float cw, float left) {
            return left + (ChronoUnit.DAYS.between(from, d) + 0.5f) * cw;
        }

        private static float my(double val, double lo, double hi, float top, float bottom) {
            return (float) (bottom - (val - lo) / (hi - lo) * (bottom - top));
        }

        private static float py(double val, double span, float top, float bottom) {
            return (float) (bottom - (val + span) / (2 * span) * (bottom - top));
        }
    }
}
