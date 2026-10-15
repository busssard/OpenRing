package app.openring.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Design tokens and small view builders. All UI is built in code (no layout XML). */
public final class Ui {
    private Ui() {}

    // palette: calm deep night, one accent per pillar
    public static final int BG = 0xFF0A0E17;
    public static final int CARD = 0xFF131A27;
    public static final int CARD_HI = 0xFF1A2333;
    public static final int LINE = 0xFF263044;
    public static final int TEXT = 0xFFEEF1F6;
    public static final int TEXT_2 = 0xFF98A2B5;
    public static final int TEXT_3 = 0xFF5F6A80;
    public static final int SLEEP = 0xFF8E9BFF;
    public static final int READY = 0xFF6BD3B4;
    public static final int ACTIVE = 0xFFFFB46B;
    public static final int HEART = 0xFFFF6F86;
    public static final int TEMP = 0xFFFFD27A;
    public static final int OXY = 0xFF7CC8FF;
    public static final int WARN = 0xFFFF8A65;
    public static final int GOOD = 0xFF6BD3B4;

    public static final int STAGE_AWAKE = 0xFFE9ECF2;
    public static final int STAGE_REM = 0xFF9CC6FF;
    public static final int STAGE_LIGHT = 0xFF5D79F2;
    public static final int STAGE_DEEP = 0xFF2E3B9E;

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int scoreColor(double s, int accent) {
        if (Double.isNaN(s)) return TEXT_3;
        if (s >= 85) return accent;
        if (s >= 70) return blend(accent, 0xFFB7C0D0, 0.35f);
        return WARN;
    }

    public static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t), (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(true);
        return t;
    }

    public static TextView h1(Context c, String s) {
        TextView t = text(c, s, 26, TEXT, LIGHT);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView h2(Context c, String s) {
        return text(c, s, 17, TEXT, MEDIUM);
    }

    public static TextView body(Context c, String s) {
        TextView t = text(c, s, 14, TEXT_2, REGULAR);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    public static TextView caption(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.getDefault()), 11, TEXT_3, MEDIUM);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A rounded surface with padding; children are stacked vertically. */
    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(rounded(CARD, dp(c, 18)));
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 12));
        l.setLayoutParams(lp);
        return l;
    }

    public static View gap(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        lp.setMargins(0, dp(c, 10), 0, dp(c, 10));
        v.setLayoutParams(lp);
        return v;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams sized(Context c, int wDp, int hDp) {
        return new LinearLayout.LayoutParams(wDp < 0 ? ViewGroup.LayoutParams.MATCH_PARENT : dp(c, wDp), dp(c, hDp));
    }

    /** Background for text inputs: darker than the card with a visible outline (brighter when focused). */
    public static android.graphics.drawable.Drawable input(Context c) {
        GradientDrawable idle = rounded(BG, dp(c, 12));
        idle.setStroke(dp(c, 1.2f), alpha(TEXT_3, 0.7f));
        GradientDrawable focus = rounded(BG, dp(c, 12));
        focus.setStroke(dp(c, 2), SLEEP);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focus);
        s.addState(new int[0], idle);
        return s;
    }

    /** Pill shaped button. */
    public static TextView button(Context c, String label, int color, boolean filled) {
        TextView b = text(c, label, 15, filled ? 0xFF0A0E17 : color, MEDIUM);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 20), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = rounded(filled ? color : 0x00000000, dp(c, 24));
        if (!filled) bg.setStroke(dp(c, 1.2f), alpha(color, 0.6f));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(color, 0.25f)), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** A smaller outlined pill for secondary actions (13 sp); wraps to more lines instead of overlapping. */
    public static TextView smallButton(Context c, String label, int color) {
        TextView b = button(c, label, color, false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int ph = dp(c, 14), pv = dp(c, 8);
        b.setPadding(ph, pv, ph, pv);
        b.setMinHeight(dp(c, 40));
        return b;
    }

    /** Label over a big value with a unit, for stat tiles. */
    public static LinearLayout stat(Context c, String label, String value, String unit, int accent) {
        LinearLayout l = vbox(c);
        l.addView(caption(c, label));
        LinearLayout row = hbox(c);
        row.setGravity(Gravity.BOTTOM);
        TextView v = text(c, value, 24, value.equals("--") ? TEXT_3 : TEXT, LIGHT);
        row.addView(v);
        if (unit != null && !unit.isEmpty()) {
            TextView u = text(c, " " + unit, 13, TEXT_2, REGULAR);
            u.setPadding(0, 0, 0, dp(c, 4));
            row.addView(u);
        }
        l.addView(row);
        if (accent != 0) {
            View bar = new View(c);
            bar.setBackground(rounded(accent, dp(c, 2)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 18), dp(c, 3));
            lp.setMargins(0, dp(c, 4), 0, 0);
            bar.setLayoutParams(lp);
            l.addView(bar);
        }
        return l;
    }

    /** A grid row of stat tiles with equal widths. */
    public static LinearLayout statRow(Context c, View... tiles) {
        LinearLayout row = hbox(c);
        row.setGravity(Gravity.TOP);
        for (View t : tiles) row.addView(t, weight(1));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(c, 6), 0, dp(c, 6));
        row.setLayoutParams(lp);
        return row;
    }

    /** Contributor row: label, detail, verbal rating and a thin progress bar. */
    public static LinearLayout contributor(Context c, String label, String detail, double score, int accent) {
        return contributor(c, label, detail, score, accent, null);
    }

    /** Contributor row with a plain language explanation under the bar. */
    public static LinearLayout contributor(Context c, String label, String detail, double score, int accent, String explain) {
        LinearLayout l = vbox(c);
        l.setPadding(0, dp(c, 8), 0, dp(c, 8));
        LinearLayout top = hbox(c);
        TextView name = text(c, label, 15, TEXT, REGULAR);
        top.addView(name, weight(1));
        TextView rating = text(c, app.openring.core.analysis.Scores.rating(score), 13, scoreColor(score, accent), MEDIUM);
        top.addView(rating);
        l.addView(top);
        if (detail != null && !detail.isEmpty()) {
            TextView d = text(c, detail, 12, TEXT_3, REGULAR);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            l.addView(d);
        }
        Bar bar = new Bar(c, score / 100.0, scoreColor(score, accent));
        LinearLayout.LayoutParams lp = sized(c, -1, 5);
        lp.setMargins(0, dp(c, 7), 0, 0);
        l.addView(bar, lp);
        if (explain != null) {
            TextView e = text(c, explain, 12, TEXT_2, REGULAR);
            e.setLineSpacing(0, 1.15f);
            e.setPadding(0, dp(c, 6), 0, 0);
            l.addView(e);
        }
        return l;
    }

    /** A thin rounded progress bar. */
    public static final class Bar extends View {
        private final double value;
        private final int color;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        public Bar(Context c, double value, int color) {
            super(c);
            this.value = Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
            this.color = color;
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float h = getHeight(), w = getWidth();
            p.setColor(LINE);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(color);
            r.set(0, 0, (float) (w * value), h);
            if (value > 0) cv.drawRoundRect(r, h / 2, h / 2, p);
        }
    }

    public static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    public static String fmt(double v, int dp) {
        if (Double.isNaN(v)) return "--";
        return String.format(Locale.getDefault(), "%." + dp + "f", v);
    }

    public static String hm(double minutes) {
        if (Double.isNaN(minutes)) return "--";
        long m = Math.round(minutes);
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
