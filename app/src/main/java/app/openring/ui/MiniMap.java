package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.SearchWalk;

/**
 * The search walk drawn from above (issue #74, phase 2): up is the way the person faced at Start.
 * The path from the start, one dot per half second of readings coloured by signal (dim blue weak,
 * warm strong, scaled to this walk), where the person is now and which way they face, the
 * "probably around here" circle, a scale bar and the legend. Touch a dot to read its time, signal
 * and distance from the start; a vertical swipe scrolls the page.
 */
final class MiniMap extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), circle = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG), tipTxt = new Paint(Paint.ANTI_ALIAS_FLAG), legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final int slop;
    private List<SearchWalk.Point> points = Collections.emptyList();
    private List<double[]> bins = Collections.emptyList();
    private SearchWalk.Spot spot;
    private double hereX, hereY, facing = Double.NaN;
    private long wallOffsetMs;
    private int picked = -1;
    private float downX, downY;
    private boolean dragging;
    // the current view transform, from the last draw
    private double mx, my, scale = 1;
    private float cx, cy;

    /** The dim blue of a weak reading and the warm colours of a strong one (from the app palette). */
    static final int WEAK = Ui.blend(Ui.OXY, Ui.CARD, 0.45f);

    MiniMap(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Ui.alpha(Ui.TEXT_3, 0.8f));
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(Ui.dp(c, 2));
        mark.setStrokeCap(Paint.Cap.ROUND);
        txt.setColor(Ui.TEXT_3);
        txt.setTextSize(Ui.dp(c, 10));
        circle.setStyle(Paint.Style.STROKE);
        circle.setStrokeWidth(Ui.dp(c, 1.5f));
        circle.setColor(Ui.alpha(Ui.HEART, 0.8f));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(c, 1.5f));
        edge.setColor(Ui.alpha(Ui.TEMP, 0.8f));
        edge.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 6), Ui.dp(c, 5)}, 0));
        tipBg.setColor(Ui.BG);
        tipTxt.setColor(Ui.TEXT);
        tipTxt.setTextSize(Ui.dp(c, 11.5f));
        tipTxt.setTypeface(Ui.MEDIUM);
    }

    /**
     * New walk data. {@code wallOffsetMs} turns the walk's times (elapsed realtime) into wall clock
     * times for the readout; {@code facing} is the heading now (relative to the start).
     */
    void set(SearchWalk w, SearchWalk.Spot spot, double facing, long wallOffsetMs) {
        points = w == null ? Collections.<SearchWalk.Point>emptyList() : w.path();
        bins = w == null ? Collections.<double[]>emptyList() : w.bins();
        hereX = w == null ? 0 : w.x();
        hereY = w == null ? 0 : w.y();
        this.spot = spot;
        this.facing = facing;
        this.wallOffsetMs = wallOffsetMs;
        if (picked >= bins.size()) picked = -1;
        invalidate();
    }

    /** Signal range for the colours: the walk's weakest and strongest dot, at least 6 dB apart. */
    double[] range() {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] b : bins) {
            lo = Math.min(lo, b[3]);
            hi = Math.max(hi, b[3]);
        }
        if (bins.isEmpty()) return new double[] {-90, -50};
        if (hi - lo < 6) {
            double m = (hi + lo) / 2;
            lo = m - 3;
            hi = m + 3;
        }
        return new double[] {lo, hi};
    }

    /** Dim blue (weak) through orange to warm red (strong). */
    static int color(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5 ? Ui.blend(WEAK, Ui.ACTIVE, (float) (t * 2)) : Ui.blend(Ui.ACTIVE, Ui.HEART, (float) ((t - 0.5) * 2));
    }

    float sx(double x) {
        return (float) (cx + (x - mx) * scale);
    }

    float sy(double y) {
        return (float) (cy - (y - my) * scale);
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), h = getHeight();
        float top = Ui.dp(c, 18), bottom = Ui.dp(c, 30);
        // what to fit: the path, the dots and the circle, plus a margin; at least 3 m across
        double x0 = 0, x1 = 0, y0 = 0, y1 = 0;
        for (SearchWalk.Point p : points) {
            x0 = Math.min(x0, p.x);
            x1 = Math.max(x1, p.x);
            y0 = Math.min(y0, p.y);
            y1 = Math.max(y1, p.y);
        }
        if (spot != null && spot.radius <= SearchWalk.CLEAR_RADIUS_M) {
            x0 = Math.min(x0, spot.x - spot.radius);
            x1 = Math.max(x1, spot.x + spot.radius);
            y0 = Math.min(y0, spot.y - spot.radius);
            y1 = Math.max(y1, spot.y + spot.radius);
        }
        x0 -= 0.5;
        x1 += 0.5;
        y0 -= 0.5;
        y1 += 0.5;
        double span = Math.max(3, Math.max(x1 - x0, y1 - y0));
        float aw = w - Ui.dp(c, 8), ah = h - top - bottom;
        scale = Math.min(aw / Math.max(x1 - x0, 1e-6), ah / Math.max(y1 - y0, 1e-6));
        scale = Math.min(scale, Math.min(aw, ah) / span);
        mx = (x0 + x1) / 2;
        my = (y0 + y1) / 2;
        cx = w / 2;
        cy = top + ah / 2;

        txt.setTextAlign(Paint.Align.CENTER);
        txt.setColor(Ui.TEXT_3);
        cv.drawText("Up is the way you faced at Start", w / 2, Ui.dp(c, 11), txt);

        // the likely area under everything else
        if (spot != null && spot.radius <= SearchWalk.CLEAR_RADIUS_M) {
            float px = sx(spot.x), py = sy(spot.y), pr = (float) (spot.radius * scale);
            if (spot.clear) {
                fill.setShader(new RadialGradient(px, py, pr, new int[] {Ui.alpha(Ui.HEART, 0.38f), Ui.alpha(Ui.HEART, 0.18f), Ui.alpha(Ui.HEART, 0.04f)},
                        new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP));
                cv.drawCircle(px, py, pr, fill);
                cv.drawCircle(px, py, pr, circle);
                txt.setColor(Ui.TEXT);
                cv.drawText("Probably around here", px, Math.max(top + txt.getTextSize(), py - pr - Ui.dp(c, 4)), txt);
            } else if (spot.edge) {
                cv.drawCircle(px, py, pr, edge);
                txt.setColor(Ui.TEMP);
                cv.drawText("Farther this way", px, Math.max(top + txt.getTextSize(), py - pr - Ui.dp(c, 4)), txt);
            }
        }

        // the path
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            SearchWalk.Point p = points.get(i);
            if (i == 0) path.moveTo(sx(p.x), sy(p.y));
            else path.lineTo(sx(p.x), sy(p.y));
        }
        cv.drawPath(path, line);

        // the dots, weak first so the strong ones lie on top
        double[] rg = range();
        Integer[] order = new Integer[bins.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(bins.get(a)[3], bins.get(b)[3]);
            }
        });
        for (int i : order) {
            double[] b = bins.get(i);
            double t = (b[3] - rg[0]) / (rg[1] - rg[0]);
            dot.setColor(color(t));
            cv.drawCircle(sx(b[0]), sy(b[1]), Ui.dp(c, 2.5f) + (float) (Ui.dp(c, 2.5f) * t), dot);
        }

        // start: an open ring with its label
        mark.setColor(Ui.TEXT_2);
        cv.drawCircle(sx(0), sy(0), Ui.dp(c, 6), mark);
        txt.setColor(Ui.TEXT_2);
        txt.setTextAlign(Paint.Align.LEFT);
        cv.drawText("Start", sx(0) + Ui.dp(c, 9), sy(0) + Ui.dp(c, 4), txt);

        // you: a white dot with a tick the way you face now
        if (!Double.isNaN(facing)) {
            double a = Math.toRadians(facing);
            float hx = sx(hereX), hy = sy(hereY), len = Ui.dp(c, 14);
            mark.setColor(Ui.TEXT);
            cv.drawLine(hx, hy, (float) (hx + Math.sin(a) * len), (float) (hy - Math.cos(a) * len), mark);
        }
        dot.setColor(Ui.TEXT);
        cv.drawCircle(sx(hereX), sy(hereY), Ui.dp(c, 5), dot);

        // scale bar, bottom left: the largest round length up to a quarter of the width
        double[] steps = {0.5, 1, 2, 5, 10, 20};
        double bar = steps[0];
        for (double s : steps) if (s * scale <= w * 0.25) bar = s;
        float by = h - Ui.dp(c, 8), bx = Ui.dp(c, 4), bw = (float) (bar * scale);
        mark.setColor(Ui.TEXT_2);
        cv.drawLine(bx, by, bx + bw, by, mark);
        cv.drawLine(bx, by - Ui.dp(c, 4), bx, by, mark);
        cv.drawLine(bx + bw, by - Ui.dp(c, 4), bx + bw, by, mark);
        txt.setColor(Ui.TEXT_2);
        txt.setTextAlign(Paint.Align.LEFT);
        cv.drawText((bar < 1 ? "0.5" : String.valueOf((int) bar)) + " m", bx + bw + Ui.dp(c, 6), by + Ui.dp(c, 3), txt);

        // legend, bottom right: weak -84 [colours] -61 dBm strong
        if (!bins.isEmpty()) {
            String lo = "weak " + Math.round(rg[0]), hi = Math.round(rg[1]) + " dBm strong";
            float gw = Ui.dp(c, 48), gh = Ui.dp(c, 6);
            float hiW = txt.measureText(hi), loW = txt.measureText(lo);
            float gx1 = w - Ui.dp(c, 4) - hiW - Ui.dp(c, 6), gx0 = gx1 - gw;
            legend.setShader(new LinearGradient(gx0, 0, gx1, 0, new int[] {color(0), color(0.5), color(1)}, null, Shader.TileMode.CLAMP));
            r.set(gx0, by - gh, gx1, by);
            cv.drawRoundRect(r, gh / 2, gh / 2, legend);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(hi, gx1 + Ui.dp(c, 6), by + Ui.dp(c, 1), txt);
            cv.drawText(lo, gx0 - Ui.dp(c, 6) - loW, by + Ui.dp(c, 1), txt);
        }

        // the readout of the touched dot
        if (picked >= 0 && picked < bins.size()) {
            double[] b = bins.get(picked);
            float px = sx(b[0]), py = sy(b[1]);
            mark.setColor(Ui.TEXT);
            cv.drawCircle(px, py, Ui.dp(c, 8), mark);
            String tip = Charts.clock((b[4] + wallOffsetMs) / 1000.0) + secs(b[4] + wallOffsetMs) + ", " + Math.round(b[3]) + " dBm, "
                    + Ui.fmt(Math.hypot(b[0], b[1]), 1) + " m from the start";
            float pad = Ui.dp(c, 6), tw = tipTxt.measureText(tip), bw2 = tw + pad * 2, bh = tipTxt.getTextSize() + pad * 1.4f;
            float left = Math.max(0, Math.min(w - bw2, px - bw2 / 2));
            r.set(left, 0, left + bw2, bh);
            cv.drawRoundRect(r, bh / 2, bh / 2, tipBg);
            cv.drawText(tip, left + pad, bh / 2 + tipTxt.getTextSize() * 0.36f, tipTxt);
        }
    }

    /** ":05", the seconds after the clock's minutes. */
    static String secs(double ms) {
        long s = Math.floorMod((long) Math.floor(ms / 1000), 60L);
        return String.format(Locale.getDefault(), ":%02d", s);
    }

    /** The dot nearest to a touch, or -1 when none is within 28 dp. */
    int nearest(float x, float y) {
        int best = -1;
        double bd = Ui.dp(getContext(), 28);
        for (int i = 0; i < bins.size(); i++) {
            double[] b = bins.get(i);
            double d = Math.hypot(sx(b[0]) - x, sy(b[1]) - y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** Like the other charts: a tap reads a dot, a sideways drag keeps reading, a vertical swipe scrolls, a tap away from the dots clears. */
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
                    if (dy > slop && dy >= dx) return false;
                    if (dx > slop) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging) {
                    int n = nearest(e.getX(), e.getY());
                    if (n >= 0) picked = n;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    picked = nearest(e.getX(), e.getY());
                    invalidate();
                    performClick();
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                return true;
            default:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
