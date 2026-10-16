package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

import app.openring.core.analysis.TS;

/** Hand drawn chart views: score ring, hypnogram, line chart and bar chart. */
public final class Charts {
    private Charts() {}

    static String clock(double unix) {
        ZonedDateTime z = Instant.ofEpochSecond((long) unix).atZone(ZoneId.systemDefault());
        return String.format(Locale.getDefault(), "%02d:%02d", z.getHour(), z.getMinute());
    }

    // ------------------------------------------------------------------- score ring

    public static final class ScoreRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), lab = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private double value;
        private String label;
        private final int color;
        private final boolean big;

        public ScoreRing(Context c, double value, String label, int color, boolean big) {
            super(c);
            this.value = value;
            this.label = label;
            this.color = color;
            this.big = big;
            track.setStyle(Paint.Style.STROKE);
            track.setColor(Ui.LINE);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            num.setColor(Ui.TEXT);
            num.setTypeface(Ui.LIGHT);
            num.setTextAlign(Paint.Align.CENTER);
            lab.setColor(Ui.TEXT_2);
            lab.setTypeface(Ui.MEDIUM);
            lab.setTextAlign(Paint.Align.CENTER);
            lab.setLetterSpacing(0.06f);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h);
            float stroke = size * (big ? 0.06f : 0.085f);
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            float pad = stroke / 2 + 2;
            oval.set((w - size) / 2 + pad, (h - size) / 2 + pad, (w + size) / 2 - pad, (h + size) / 2 - pad);
            cv.drawArc(oval, 135, 270, false, track);
            if (!Double.isNaN(value) && value >= 0) {
                arc.setShader(new LinearGradient(oval.left, oval.bottom, oval.right, oval.top, Ui.alpha(color, 0.55f), color, Shader.TileMode.CLAMP));
                cv.drawArc(oval, 135, (float) (270 * Math.min(1, value / 100.0)), false, arc);
            }
            num.setTextSize(size * (big ? 0.30f : 0.32f));
            String t = Double.isNaN(value) || value < 0 ? "--" : String.valueOf(Math.round(value));
            cv.drawText(t, w / 2, h / 2 + num.getTextSize() * 0.33f, num);
            if (label != null) {
                lab.setTextSize(size * (big ? 0.075f : 0.105f));
                cv.drawText(label.toUpperCase(Locale.getDefault()), w / 2, (h + size) / 2 - pad - stroke * 0.2f, lab);
            }
        }
    }

    // ------------------------------------------------------------------ touch readout

    /**
     * A chart you can touch: tap or slide sideways to read the value at that moment. Vertical
     * swipes still scroll the page; the readout stays until the next tap on the same spot.
     */
    public abstract static class Scrub extends View {
        protected float scrubX = Float.NaN;
        private float downX, downY;
        private boolean dragging;
        private final int slop;
        private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG), tipTxt = new Paint(Paint.ANTI_ALIAS_FLAG), cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tipR = new RectF();

        Scrub(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            tipBg.setColor(Ui.BG);
            tipTxt.setColor(Ui.TEXT);
            tipTxt.setTextSize(Ui.dp(c, 11.5f));
            tipTxt.setTypeface(Ui.MEDIUM);
            cursor.setColor(Ui.alpha(Ui.TEXT, 0.55f));
            cursor.setStrokeWidth(Ui.dp(c, 1));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
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
                            return false;
                        }
                    }
                    if (dragging) {
                        scrubX = e.getX();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        boolean same = !Float.isNaN(scrubX) && Math.abs(scrubX - e.getX()) < slop * 2;
                        scrubX = same ? Float.NaN : e.getX();
                        invalidate();
                        performClick();
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

        /** Vertical cursor at {@code x} from {@code top} to {@code bottom} with a label bubble on top. */
        protected void drawTip(Canvas cv, float x, float top, float bottom, String text) {
            cv.drawLine(x, top, x, bottom, cursor);
            float pad = Ui.dp(getContext(), 6);
            float tw = tipTxt.measureText(text);
            float bw = tw + pad * 2, bh = tipTxt.getTextSize() + pad * 1.4f;
            float left = Math.max(0, Math.min(getWidth() - bw, x - bw / 2));
            tipR.set(left, 0, left + bw, bh);
            cv.drawRoundRect(tipR, bh / 2, bh / 2, tipBg);
            cv.drawText(text, left + pad, bh / 2 + tipTxt.getTextSize() * 0.36f, tipTxt);
        }
    }

    // --------------------------------------------------------------------- hypnogram

    public static final class Hypnogram extends Scrub {
        private final int[] stages;
        private final double start, end;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        private final RectF r = new RectF();

        public Hypnogram(Context c, int[] stages, double start, double end) {
            super(c);
            this.stages = stages;
            this.start = start;
            this.end = end;
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            txt.setTypeface(Ui.REGULAR);
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
        }

        static int level(int s) {
            switch (s) {
                case 4: return 0;
                case 3: return 1;
                case 2: return 2;
                case 1: return 3;
                default: return -1;
            }
        }

        static String name(int s) {
            switch (s) {
                case 4: return "Awake";
                case 3: return "REM";
                case 2: return "Light";
                case 1: return "Deep";
                default: return "No data";
            }
        }

        static int color(int s) {
            switch (s) {
                case 4: return Ui.STAGE_AWAKE;
                case 3: return Ui.STAGE_REM;
                case 2: return Ui.STAGE_LIGHT;
                case 1: return Ui.STAGE_DEEP;
                default: return Ui.LINE;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float labelW = Ui.dp(getContext(), 44);
            float bottom = h - Ui.dp(getContext(), 18);
            float rowH = bottom / 4f;
            String[] names = {"Awake", "REM", "Light", "Deep"};
            for (int i = 0; i < 4; i++) {
                cv.drawText(names[i], 0, rowH * i + rowH * 0.62f, txt);
                cv.drawLine(labelW, rowH * (i + 1), w, rowH * (i + 1), grid);
            }
            int n = stages.length;
            if (n == 0) return;
            float cw = (w - labelW) / n;
            float rad = Math.min(rowH * 0.25f, Ui.dp(getContext(), 3));
            // depth line: joins each stage to the next, so the night reads as one descent and climb
            Paint link = new Paint(Paint.ANTI_ALIAS_FLAG);
            link.setStrokeWidth(Ui.dp(getContext(), 1.2f));
            link.setColor(Ui.alpha(Ui.TEXT_2, 0.45f));
            int prevLevel = -1;
            for (int k = 0; k < n; k++) {
                int lv = level(stages[k]);
                if (lv >= 0 && prevLevel >= 0 && lv != prevLevel) {
                    float x = labelW + k * cw;
                    cv.drawLine(x, rowH * prevLevel + rowH * 0.5f, x, rowH * lv + rowH * 0.5f, link);
                }
                if (lv >= 0) prevLevel = lv;
            }
            int i = 0;
            while (i < n) {
                int s = stages[i];
                int j = i;
                while (j < n && stages[j] == s) j++;
                int lv = level(s);
                if (lv >= 0) {
                    p.setColor(color(s));
                    r.set(labelW + i * cw, rowH * lv + rowH * 0.18f, labelW + j * cw, rowH * lv + rowH * 0.82f);
                    cv.drawRoundRect(r, rad, rad, p);
                }
                i = j;
            }
            if (!Float.isNaN(scrubX)) {
                int k = (int) Math.max(0, Math.min(n - 1, Math.floor((scrubX - labelW) / cw)));
                int a = k, b = k + 1;
                while (a > 0 && stages[a - 1] == stages[k]) a--;
                while (b < n && stages[b] == stages[k]) b++;
                double ep = (end - start) / n;
                double ta = start + a * ep, tb = start + b * ep;
                String label = clock(start + (k + 0.5) * ep) + "  " + name(stages[k]) + ", " + clock(ta) + " to " + clock(tb)
                        + " (" + Math.round((tb - ta) / 60) + " min)";
                drawTip(cv, labelW + (k + 0.5f) * cw, Ui.dp(getContext(), 20), bottom, label);
            }
            // time axis
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(clock(start), labelW, h - Ui.dp(getContext(), 3), txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(clock(end), w, h - Ui.dp(getContext(), 3), txt);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(clock((start + end) / 2), labelW + (w - labelW) / 2, h - Ui.dp(getContext(), 3), txt);
            txt.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------- strip chart

    /** A thin intensity strip (e.g. movement through the night): taller and stronger = more. */
    public static final class Strip extends View {
        private final TS series;
        private final double from, to;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Strip(Context c, TS series, double from, double to, int color) {
            super(c);
            this.series = series.sort();
            this.from = from;
            this.to = to;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = Ui.dp(getContext(), 44);
            p.setColor(Ui.LINE);
            cv.drawRect(left, h - 1, w, h, p);
            double max = 1;
            for (int i = 0; i < series.size(); i++) max = Math.max(max, series.v(i));
            float bw = Math.max(1.5f, (float) ((w - left) * 300 / Math.max(1, to - from)) - 1);
            for (int i = 0; i < series.size(); i++) {
                double v = series.v(i);
                if (v <= 0 || Double.isNaN(v)) continue;
                float x = (float) (left + (series.t(i) - from) / (to - from) * (w - left));
                float bh = (float) (Math.min(1, v / max) * (h - 2));
                p.setColor(Ui.alpha(color, (float) (0.35 + 0.65 * Math.min(1, v / max))));
                cv.drawRect(x - bw / 2, h - 1 - bh, x + bw / 2, h - 1, p);
            }
        }
    }

    // ---------------------------------------------------------------------- line chart

    public static final class LineChart extends Scrub {
        private final TS series;
        private final int color;
        private double from, to;
        private double yMin = Double.NaN, yMax = Double.NaN;
        private double band = Double.NaN;
        private String unit = "";
        private boolean fill = true;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean markMin;
        private boolean daily;
        private String emptyText = "No data";
        private java.time.LocalDate dayEnd;
        private int maxDays;

        public LineChart(Context c, TS series, double from, double to, int color) {
            super(c);
            this.series = series.sort();
            this.from = from;
            this.to = to;
            this.color = color;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(color);
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
            grid.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 3), Ui.dp(c, 4)}, 0));
            dot.setColor(color);
        }

        public LineChart range(double min, double max) {
            yMin = min;
            yMax = max;
            return this;
        }

        public LineChart reference(double y) {
            band = y;
            return this;
        }

        public LineChart unit(String u) {
            unit = u;
            return this;
        }

        public LineChart markMin() {
            markMin = true;
            return this;
        }

        /**
         * One value per day (placed at noon). The time axis fits the data: from the first day with
         * a value (at least a week back) to {@code end}, at most {@code maxDays}.
         */
        public LineChart daily(java.time.LocalDate end, int maxDays) {
            daily = true;
            dayEnd = end;
            this.maxDays = maxDays;
            return this;
        }

        public LineChart empty(String text) {
            emptyText = text;
            return this;
        }

        public LineChart noFill() {
            fill = false;
            return this;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = Ui.dp(getContext(), 30), bottom = h - Ui.dp(getContext(), 18), top = Ui.dp(getContext(), 8);
            if (series.size() == 0) {
                txt.setTextAlign(Paint.Align.CENTER);
                cv.drawText(emptyText, w / 2, h / 2, txt);
                return;
            }
            double lo = yMin, hi = yMax;
            if (Double.isNaN(lo) || Double.isNaN(hi)) {
                lo = series.min();
                hi = series.max();
                double padY = Math.max(1, (hi - lo) * 0.15);
                lo -= padY;
                hi += padY;
            }
            if (hi - lo < 1e-6) hi = lo + 1;
            if (daily) {
                double endT = dayEnd.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
                double first = endT;
                for (int i = 0; i < series.size(); i++) if (series.t(i) >= endT - maxDays * 86400.0 && series.t(i) < first) first = series.t(i);
                int span = (int) Math.max(7, Math.min(maxDays, Math.ceil((endT - first) / 86400.0)));
                from = endT - span * 86400.0;
                to = endT;
            } else if (!(to > from)) {
                from = series.t(0);
                to = series.t(series.size() - 1) + 1;
            }
            // horizontal guides with labels
            txt.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 2; i++) {
                double v = lo + (hi - lo) * i / 2;
                float y = (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
                cv.drawLine(left, y, w, y, grid);
                cv.drawText(format(v), 0, y + Ui.dp(getContext(), 3), txt);
            }
            if (!Double.isNaN(band)) {
                Paint ref = new Paint(grid);
                ref.setColor(Ui.alpha(color, 0.5f));
                float y = (float) (bottom - (band - lo) / (hi - lo) * (bottom - top));
                cv.drawLine(left, y, w, y, ref);
            }
            Path path = new Path(), fillPath = new Path();
            boolean started = false;
            double prevT = 0;
            float lastX = 0, firstX = 0;
            float minX = -1, minY = 0;
            double minV = Double.POSITIVE_INFINITY;
            double gap = daily ? 3.5 * 86400 : Math.max(1200, (to - from) / 24);
            float[] xs = new float[series.size()], ys = new float[series.size()];
            int[] idx = new int[series.size()];
            int shown = 0;
            for (int i = 0; i < series.size(); i++) {
                double t = series.t(i), v = series.v(i);
                if (t < from || t > to || Double.isNaN(v)) continue;
                float x = (float) (left + (t - from) / (to - from) * (w - left));
                float y = (float) (bottom - (Math.max(lo, Math.min(hi, v)) - lo) / (hi - lo) * (bottom - top));
                if (!started || t - prevT > gap) {
                    if (started && fill) {
                        fillPath.lineTo(lastX, bottom);
                        fillPath.lineTo(firstX, bottom);
                        fillPath.close();
                    }
                    path.moveTo(x, y);
                    fillPath.moveTo(x, bottom);
                    fillPath.lineTo(x, y);
                    firstX = x;
                    started = true;
                } else {
                    path.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
                lastX = x;
                prevT = t;
                xs[shown] = x;
                ys[shown] = y;
                idx[shown++] = i;
                if (v < minV) {
                    minV = v;
                    minX = x;
                    minY = y;
                }
            }
            if (fill && started) {
                fillPath.lineTo(lastX, bottom);
                fillPath.lineTo(firstX, bottom);
                fillPath.close();
                area.setShader(new LinearGradient(0, top, 0, bottom, Ui.alpha(color, 0.28f), Ui.alpha(color, 0.0f), Shader.TileMode.CLAMP));
                cv.drawPath(fillPath, area);
            }
            cv.drawPath(path, line);
            // dots for daily values and for lone points a line cannot show
            for (int k = 0; k < shown; k++) {
                boolean alone = (k == 0 || series.t(idx[k]) - series.t(idx[k - 1]) > gap)
                        && (k == shown - 1 || series.t(idx[k + 1]) - series.t(idx[k]) > gap);
                if (daily || alone) cv.drawCircle(xs[k], ys[k], Ui.dp(getContext(), daily ? 3 : 2.5f), dot);
            }
            if (markMin && minX >= 0) {
                cv.drawCircle(minX, minY, Ui.dp(getContext(), 4), dot);
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setColor(Ui.TEXT_2);
                cv.drawText(format(minV) + unit, minX, Math.min(bottom - 2, minY + Ui.dp(getContext(), 16)), txt);
                txt.setColor(Ui.TEXT_3);
            }
            float base = h - Ui.dp(getContext(), 3);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(clockOrDate(daily ? from + 43200 : from), left, base, txt);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(clockOrDate((from + to) / 2), left + (w - left) / 2, base, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(clockOrDate(daily ? to - 43200 : to), w, base, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            if (!Float.isNaN(scrubX) && shown > 0) {
                int best = 0;
                for (int k = 1; k < shown; k++) if (Math.abs(xs[k] - scrubX) < Math.abs(xs[best] - scrubX)) best = k;
                double t = series.t(idx[best]);
                String when = daily ? dayLabel(t) : clock(t);
                cv.drawCircle(xs[best], ys[best], Ui.dp(getContext(), 4.5f), dot);
                drawTip(cv, xs[best], Ui.dp(getContext(), 22), bottom, when + "  " + format(series.v(idx[best])) + unit);
            }
        }

        private static String dayLabel(double t) {
            ZonedDateTime z = Instant.ofEpochSecond((long) t).atZone(ZoneId.systemDefault());
            return z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + z.getDayOfMonth() + "." + z.getMonthValue() + ".";
        }

        private String clockOrDate(double t) {
            if (daily || to - from > 3 * 86400) {
                ZonedDateTime z = Instant.ofEpochSecond((long) t).atZone(ZoneId.systemDefault());
                return z.getDayOfMonth() + "." + z.getMonthValue() + ".";
            }
            return clock(t);
        }

        private static String format(double v) {
            return Math.abs(v) < 10 && v != Math.rint(v) ? String.format(Locale.getDefault(), "%.1f", v) : String.valueOf(Math.round(v));
        }
    }

    // ----------------------------------------------------------------------- bar chart

    public static final class BarChart extends View {
        private final double[] values;
        private final String[] labels;
        private final int color;
        private double target = Double.NaN;
        private int highlight = -1;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), ref = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private String format = "%.0f";

        public BarChart(Context c, double[] values, String[] labels, int color) {
            super(c);
            this.values = values;
            this.labels = labels;
            this.color = color;
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            txt.setTextAlign(Paint.Align.CENTER);
            ref.setColor(Ui.alpha(color, 0.6f));
            ref.setStrokeWidth(Ui.dp(c, 1));
            ref.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 4), Ui.dp(c, 4)}, 0));
            highlight = values.length - 1;
        }

        public BarChart target(double t) {
            target = t;
            return this;
        }

        public BarChart valueFormat(String f) {
            format = f;
            return this;
        }

        public BarChart highlight(int i) {
            highlight = i;
            return this;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float bottom = h - Ui.dp(getContext(), 16), top = Ui.dp(getContext(), 14);
            double max = Double.isNaN(target) ? 0 : target;
            for (double v : values) if (!Double.isNaN(v)) max = Math.max(max, v);
            if (max <= 0) max = 1;
            int n = values.length;
            float slot = w / Math.max(1, n);
            float bw = Math.min(slot * 0.56f, Ui.dp(getContext(), 22));
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2;
                double v = values[i];
                if (!Double.isNaN(v)) {
                    float y = (float) (bottom - v / max * (bottom - top));
                    bar.setColor(i == highlight ? color : Ui.alpha(color, 0.45f));
                    r.set(cx - bw / 2, y, cx + bw / 2, bottom);
                    cv.drawRoundRect(r, bw / 3, bw / 3, bar);
                    if (i == highlight) {
                        txt.setColor(Ui.TEXT);
                        cv.drawText(String.format(Locale.getDefault(), format, v), cx, y - Ui.dp(getContext(), 4), txt);
                        txt.setColor(Ui.TEXT_3);
                    }
                }
                if (labels != null && i < labels.length) cv.drawText(labels[i], cx, h - Ui.dp(getContext(), 2), txt);
            }
            if (!Double.isNaN(target)) {
                float y = (float) (bottom - target / max * (bottom - top));
                cv.drawLine(0, y, w, y, ref);
            }
        }
    }

    // ------------------------------------------------------------------- range gauge

    /** A horizontal band (your normal range) with today's value marked. */
    public static final class RangeGauge extends View {
        private final double value, lo, hi;
        private final boolean flagged;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public RangeGauge(Context c, double value, double lo, double hi, boolean flagged) {
            super(c);
            this.value = value;
            this.lo = lo;
            this.hi = hi;
            this.flagged = flagged;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            double span = Math.max(1e-6, hi - lo);
            double min = Math.min(value, lo - span * 0.6), max = Math.max(value, hi + span * 0.6);
            float y = h / 2;
            p.setColor(Ui.LINE);
            r.set(0, y - Ui.dp(getContext(), 2), w, y + Ui.dp(getContext(), 2));
            cv.drawRoundRect(r, 4, 4, p);
            float x0 = (float) ((lo - min) / (max - min) * w), x1 = (float) ((hi - min) / (max - min) * w);
            p.setColor(Ui.alpha(Ui.READY, 0.45f));
            r.set(x0, y - Ui.dp(getContext(), 4), x1, y + Ui.dp(getContext(), 4));
            cv.drawRoundRect(r, 8, 8, p);
            float xv = (float) ((value - min) / (max - min) * w);
            p.setColor(flagged ? Ui.WARN : Ui.TEXT);
            cv.drawCircle(Math.max(6, Math.min(w - 6, xv)), y, Ui.dp(getContext(), 5), p);
        }
    }

    // ------------------------------------------------------------- recent nights bar

    /**
     * One night as a small column: height is time asleep (scaled to {@code maxMin}), split into
     * deep at the bottom, then light, REM and awake on top. Highlighted when selected.
     */
    public static final class NightBar extends View {
        private final double deep, light, rem, awake, maxMin;
        private final boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public NightBar(Context c, double deep, double light, double rem, double awake, double maxMin, boolean selected) {
            super(c);
            this.deep = nz(deep);
            this.light = nz(light);
            this.rem = nz(rem);
            this.awake = nz(awake);
            this.maxMin = Math.max(60, maxMin);
            this.selected = selected;
        }

        static double nz(double v) {
            return Double.isNaN(v) ? 0 : Math.max(0, v);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float bw = Math.min(w * 0.55f, Ui.dp(getContext(), 18));
            float x0 = (w - bw) / 2, x1 = x0 + bw;
            if (selected) {
                p.setColor(Ui.alpha(Ui.SLEEP, 0.16f));
                r.set(0, 0, w, h);
                cv.drawRoundRect(r, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), p);
            }
            double[] parts = {deep, light, rem, awake};
            int[] colors = {Ui.STAGE_DEEP, Ui.STAGE_LIGHT, Ui.STAGE_REM, Ui.STAGE_AWAKE};
            float y = h - Ui.dp(getContext(), 4);
            float scale = (float) ((h - Ui.dp(getContext(), 8)) / maxMin);
            for (int i = 0; i < 4; i++) {
                float ph = (float) (parts[i] * scale);
                if (ph <= 0) continue;
                p.setColor(i == 3 ? Ui.alpha(colors[i], 0.55f) : colors[i]);
                cv.drawRect(x0, y - ph, x1, y, p);
                y -= ph;
            }
        }
    }

    // ---------------------------------------------------------- average night chart

    /**
     * The average night: for each clock time, the share of nights in deep, light, REM and awake,
     * stacked from the bottom. The top edge is the share of nights in bed at that time.
     */
    public static final class Pattern extends Scrub {
        private final app.openring.core.analysis.SleepPattern sp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        private final int a, b;

        public Pattern(Context c, app.openring.core.analysis.SleepPattern sp) {
            super(c);
            this.sp = sp;
            int[] rg = sp.range(0.05);
            a = rg[0];
            b = rg[1];
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = Ui.dp(getContext(), 30), top = Ui.dp(getContext(), 24);
            float legendH = Ui.dp(getContext(), 18), axisH = Ui.dp(getContext(), 16);
            float bottom = h - legendH - axisH;
            if (sp.nights() == 0 || b <= a) {
                txt.setTextAlign(Paint.Align.CENTER);
                cv.drawText("No nights in this period", w / 2, h / 2, txt);
                return;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k <= 2; k++) {
                float y = bottom - (bottom - top) * k / 2f;
                cv.drawLine(left, y, w, y, grid);
                cv.drawText((k * 50) + "%", 0, y + Ui.dp(getContext(), 3), txt);
            }
            int n = b - a;
            float bw = (w - left) / n;
            int[] order = {1, 2, 3, 4};
            int[] colors = {Ui.STAGE_DEEP, Ui.STAGE_LIGHT, Ui.STAGE_REM, Ui.STAGE_AWAKE};
            for (int i = 0; i < n; i++) {
                int bin = a + i;
                float y = bottom;
                for (int k = 0; k < 4; k++) {
                    float hh = (float) (sp.share(bin, order[k]) * (bottom - top));
                    if (hh <= 0) continue;
                    p.setColor(k == 3 ? Ui.alpha(colors[k], 0.6f) : colors[k]);
                    cv.drawRect(left + i * bw, y - hh, left + (i + 1) * bw + 0.5f, y, p);
                    y -= hh;
                }
            }
            // hour labels, every 2 hours (every 4 when the range is long)
            int perHour = 60 / app.openring.core.analysis.SleepPattern.BIN_MIN;
            int hours = n / perHour;
            int every = hours > 10 ? 4 : 2;
            txt.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i <= n; i += perHour) {
                int clock = app.openring.core.analysis.SleepPattern.clockMin(a + i) / 60;
                if (clock % every != 0) continue;
                float x = Math.max(left + Ui.dp(getContext(), 12), Math.min(w - Ui.dp(getContext(), 12), left + i * bw));
                cv.drawText(String.format(Locale.getDefault(), "%02d:00", clock), x, bottom + axisH - Ui.dp(getContext(), 3), txt);
            }
            // legend, or the readout at the touched time
            float ly = h - Ui.dp(getContext(), 4);
            String[] names = {"Deep", "Light", "REM", "Awake"};
            if (!Float.isNaN(scrubX)) {
                int i = (int) Math.max(0, Math.min(n - 1, Math.floor((scrubX - left) / bw)));
                int bin = a + i;
                int cm = app.openring.core.analysis.SleepPattern.clockMin(bin);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 4; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(names[k]).append(' ').append(Math.round(sp.share(bin, order[k]) * 100)).append('%');
                }
                drawTip(cv, left + (i + 0.5f) * bw, top, bottom, String.format(Locale.getDefault(), "%02d:%02d  in bed %d%%",
                        cm / 60, cm % 60, Math.round(sp.inBed(bin) * 100)));
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setColor(Ui.TEXT_2);
                cv.drawText(sb.toString(), left, ly, txt);
                txt.setColor(Ui.TEXT_3);
                return;
            }
            float x = left;
            txt.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k < 4; k++) {
                p.setColor(colors[k]);
                cv.drawCircle(x + Ui.dp(getContext(), 4), ly - Ui.dp(getContext(), 3.5f), Ui.dp(getContext(), 4), p);
                cv.drawText(names[k], x + Ui.dp(getContext(), 11), ly, txt);
                x += Ui.dp(getContext(), 18) + txt.measureText(names[k]);
            }
        }
    }

    // ------------------------------------------------------------------ screen time

    /** Where on a 24 hour circle (midnight at the top) a moment of the day lies, in degrees for drawArc. */
    static float dialAngle(double t, double from, double to) {
        return (float) (-90 + 360 * (t - from) / (to - from));
    }

    /**
     * The day as a clock dial: a short line for every 5 minutes the screen was on (brighter the
     * longer), the nights as a purple band inside, the total in the middle.
     */
    public static final class ScreenDial extends View {
        private final double[] fill;
        private final java.util.List<double[]> nights;
        private final double from, to, now;
        private final String total, sub;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        public ScreenDial(Context c, double[] fill, java.util.List<double[]> nights, double from, double to, double now, String total, String sub) {
            super(c);
            this.fill = fill;
            this.nights = nights;
            this.from = from;
            this.to = to;
            this.now = now;
            this.total = total;
            this.sub = sub;
            t.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float label = Ui.dp(getContext(), 18);
            float r = Math.min(w, h) / 2 - label;
            float r0 = r * 0.70f, r1 = r;
            // the hour ring under the lines
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 1));
            p.setColor(Ui.LINE);
            cv.drawCircle(cx, cy, r0, p);
            for (int hgt = 0; hgt < 24; hgt++) {
                double a = Math.toRadians(-90 + 15 * hgt);
                float l = hgt % 6 == 0 ? r * 0.08f : r * 0.04f;
                cv.drawLine(cx + (float) Math.cos(a) * (r0 - l), cy + (float) Math.sin(a) * (r0 - l),
                        cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0, p);
            }
            // nights: a band inside the hour ring
            float band = r * 0.06f, rb = r0 - r * 0.15f;
            p.setStrokeWidth(band);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(Ui.alpha(Ui.SLEEP, 0.55f));
            oval.set(cx - rb, cy - rb, cx + rb, cy + rb);
            for (double[] n : nights) {
                double a = Math.max(n[0], from), b = Math.min(n[1], to);
                if (b <= a) continue;
                float s = dialAngle(a, from, to);
                cv.drawArc(oval, s, dialAngle(b, from, to) - s, false, p);
            }
            // screen use: one line per step
            int n = fill.length;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(1.5f, (float) (2 * Math.PI * r1 / n) * 0.55f));
            for (int i = 0; i < n; i++) {
                if (fill[i] <= 0) continue;
                double a = Math.toRadians(-90 + 360.0 * (i + 0.5) / n);
                p.setColor(Ui.alpha(Ui.OXY, (float) (0.30 + 0.70 * fill[i])));
                float ra = r0 + r * 0.05f;
                cv.drawLine(cx + (float) Math.cos(a) * ra, cy + (float) Math.sin(a) * ra, cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, p);
            }
            // now, on today's dial
            if (now > from && now < to) {
                double a = Math.toRadians(dialAngle(now, from, to));
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.TEXT);
                cv.drawCircle(cx + (float) Math.cos(a) * (r1 + label * 0.35f), cy + (float) Math.sin(a) * (r1 + label * 0.35f), Ui.dp(getContext(), 3), p);
            }
            // hour labels
            t.setColor(Ui.TEXT_3);
            t.setTypeface(Ui.MEDIUM);
            t.setTextSize(Ui.dp(getContext(), 11));
            float off = t.getTextSize() * 0.35f;
            cv.drawText("0", cx, cy - r - label * 0.35f + off, t);
            cv.drawText("12", cx, cy + r + label * 0.55f + off, t);
            cv.drawText("6", cx + r + label * 0.5f, cy + off, t);
            cv.drawText("18", cx - r - label * 0.55f, cy + off, t);
            // the total in the middle
            t.setColor(Ui.TEXT);
            t.setTypeface(Ui.LIGHT);
            t.setTextSize(r * 0.22f);
            cv.drawText(total, cx, cy + t.getTextSize() * 0.15f, t);
            t.setColor(Ui.TEXT_2);
            t.setTypeface(Ui.REGULAR);
            t.setTextSize(Math.min(Ui.dp(getContext(), 13), r * 0.09f));
            cv.drawText(sub, cx, cy + r * 0.22f, t);
        }
    }

    /**
     * Several days as rows, midnight to midnight: blue where the screen was on, the nights shaded
     * purple, a green dot at the first look after waking, the total on the right. A tap on a row
     * picks that day.
     */
    public static final class ScreenRows extends View {
        public interface Pick {
            void day(java.time.LocalDate d);
        }

        private final app.openring.data.ScreenDays.Row[] rows;
        private final java.time.LocalDate selected;
        private final Pick pick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float downX, downY;

        public ScreenRows(Context c, app.openring.data.ScreenDays.Row[] rows, java.time.LocalDate selected, Pick pick) {
            super(c);
            this.rows = rows;
            this.selected = selected;
            this.pick = pick;
        }

        float header() {
            return Ui.dp(getContext(), 18);
        }

        float rowH() {
            return Ui.dp(getContext(), 26);
        }

        /** Height in dp for all rows and the hour labels. */
        public static int heightDp(int n) {
            return 18 + n * 26;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float left = Ui.dp(getContext(), 46), right = Ui.dp(getContext(), 52);
            float x0 = left, x1 = w - right, bw = x1 - x0;
            float hh = header(), rh = rowH(), track = Ui.dp(getContext(), 12);
            t.setTextSize(Ui.dp(getContext(), 11));
            t.setTypeface(Ui.MEDIUM);
            t.setColor(Ui.TEXT_3);
            t.setTextAlign(Paint.Align.CENTER);
            for (int hgt = 0; hgt <= 24; hgt += 6) cv.drawText(String.valueOf(hgt), x0 + bw * hgt / 24f, hh - Ui.dp(getContext(), 6), t);
            java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.getDefault());
            // newest day at the top, like a diary
            for (int k = 0; k < rows.length; k++) {
                app.openring.data.ScreenDays.Row row = rows[rows.length - 1 - k];
                float top = hh + k * rh, cy = top + rh / 2;
                boolean sel = row.date.equals(selected);
                if (sel) {
                    p.setColor(Ui.alpha(Ui.OXY, 0.12f));
                    r.set(0, top + 1, w, top + rh - 1);
                    cv.drawRoundRect(r, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), p);
                }
                boolean known = !Double.isNaN(row.totalMin);
                t.setTextAlign(Paint.Align.LEFT);
                t.setTypeface(sel ? Ui.MEDIUM : Ui.REGULAR);
                t.setColor(sel ? Ui.TEXT : known ? Ui.TEXT_2 : Ui.TEXT_3);
                cv.drawText(row.date.format(f), Ui.dp(getContext(), 4), cy + t.getTextSize() * 0.35f, t);
                r.set(x0, cy - track / 2, x1, cy + track / 2);
                p.setColor(Ui.alpha(Ui.LINE, 0.6f));
                cv.drawRoundRect(r, track / 2, track / 2, p);
                p.setColor(Ui.alpha(Ui.SLEEP, 0.35f));
                for (double[] n : row.nights) {
                    double a = Math.max(n[0], row.from), b = Math.min(n[1], row.to);
                    if (b <= a) continue;
                    cv.drawRect(x0 + (float) (bw * (a - row.from) / (row.to - row.from)), cy - track / 2,
                            x0 + (float) (bw * (b - row.from) / (row.to - row.from)), cy + track / 2, p);
                }
                int n = row.fill.length;
                float step = bw / n;
                for (int i = 0; i < n; i++) {
                    if (row.fill[i] <= 0) continue;
                    p.setColor(Ui.alpha(Ui.OXY, (float) (0.35 + 0.65 * row.fill[i])));
                    cv.drawRect(x0 + i * step, cy - track / 2, x0 + (i + 1) * step + 0.5f, cy + track / 2, p);
                }
                if (!Double.isNaN(row.firstLook)) {
                    p.setColor(Ui.GOOD);
                    cv.drawCircle(x0 + (float) (bw * (row.firstLook - row.from) / (row.to - row.from)), cy, Ui.dp(getContext(), 3.5f), p);
                }
                t.setTextAlign(Paint.Align.RIGHT);
                t.setColor(known ? (sel ? Ui.TEXT : Ui.TEXT_2) : Ui.TEXT_3);
                cv.drawText(known ? Ui.hm(row.totalMin) : "--", w - Ui.dp(getContext(), 4), cy + t.getTextSize() * 0.35f, t);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (pick == null) return false;
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
                return true;
            }
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                float slop = Ui.dp(getContext(), 10);
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) return true;
                int k = (int) ((e.getY() - header()) / rowH());
                if (k >= 0 && k < rows.length) {
                    performClick();
                    pick.day(rows[rows.length - 1 - k].date);
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
