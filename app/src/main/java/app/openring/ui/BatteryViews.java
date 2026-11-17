package app.openring.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.openring.core.DeviceBattery;

/**
 * The battery of each added device on Today, right under the scores (owner's wish of 15 November): a small battery drawn in
 * code, the device's name, the last level with how old it is, and "charging", "charge soon" or the days left, one line each. A tap
 * folds open what the numbers mean and the way to each battery's history (the ring's block in Settings, the Watch page). The
 * numbers come from {@code core/DeviceBattery}.
 */
final class BatteryViews {
    private BatteryViews() {}

    /** Whether the battery's details are open on Today; kept while the screen is rebuilt, closed when the app starts. */
    static final boolean[] open = {false};

    /**
     * One compact line per device that folds open (owner's wish of 17 November: "everything visible in one line"), or null
     * without any device (only the phone). Open: the explanation of the numbers and the way to each device's history.
     */
    static View rows(final Context c, DayData d) {
        if (d.batteries.isEmpty()) return null;
        LinearLayout lines = Ui.vbox(c);
        StringBuilder said = new StringBuilder("Battery");
        for (DeviceBattery.Status s : d.batteries) {
            lines.addView(row(c, s));
            said.append(", ").append(s.name()).append(' ').append(s.value()).append(s.note() == null ? "" : ", " + s.note());
        }
        Fold f = Fold.inline(c, lines, said.toString(), open, true);
        f.note(Explain.BATTERY_TODAY);
        LinearLayout ways = Ui.hbox(c);
        for (final DeviceBattery.Status s : d.batteries) {
            TextView b = Ui.textButton(c, s.name() + " " + Explain.BATTERY_HISTORY, Ui.OXY);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    history(c, s.device);
                }
            });
            ways.addView(b);
        }
        f.body.addView(ways, Ui.matchWrap());
        return f.view;
    }

    /** Where a device's battery history is: the ring's block in Settings, the Watch page. */
    static void history(Context c, int device) {
        if (device == DeviceBattery.WATCH) c.startActivity(new Intent(c, WatchPageActivity.class));
        else c.startActivity(new Intent(c, SettingsActivity.class).putExtra("section", "ring"));
    }

    /** One device's line: the battery drawn, the name, the level and its age, and the days left (or charging) at the end. */
    static View row(final Context c, final DeviceBattery.Status s) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
        int color = s.warn() ? Ui.WARN : s.newest == null || s.old ? Ui.TEXT_3 : Ui.READY;
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 13));
        il.setMargins(0, 0, Ui.dp(c, 10), 0);
        row.addView(new Icon(c, s.newest == null ? -1 : s.newest.pct, color, s.charging || s.rising), il);
        TextView name = Ui.text(c, s.name(), 14, Ui.TEXT, Ui.MEDIUM);
        name.setPadding(0, 0, Ui.dp(c, 8), 0);
        row.addView(name);
        row.addView(Fold.oneLine(Ui.text(c, s.value(), 14, s.warn() ? Ui.WARN : Ui.TEXT_2, Ui.REGULAR)));
        String note = s.note();
        TextView n = Fold.oneLine(Ui.text(c, note == null ? "" : note, 12, s.warn() ? Ui.WARN : s.charging || s.rising ? Ui.GOOD : Ui.TEXT_3, Ui.REGULAR));
        n.setGravity(Gravity.END);
        LinearLayout.LayoutParams nl = Ui.weight(1);
        nl.setMargins(Ui.dp(c, 8), 0, 0, 0);
        row.addView(n, nl);
        return row;
    }

    /** A battery lying on its side: an outline, the knob on the right, filled to the level; a lightning bolt while charging. */
    static final class Icon extends View {
        private final int pct, color;
        private final boolean charging;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Icon(Context c, int pct, int color, boolean charging) {
            super(c);
            this.pct = pct;
            this.color = color;
            this.charging = charging;
            line.setStyle(Paint.Style.STROKE);
            line.setColor(Ui.TEXT_2);
            fill.setColor(color);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float sw = Math.max(1.5f, h * 0.1f), knob = w * 0.1f, rad = h * 0.2f;
            line.setStrokeWidth(sw);
            r.set(sw / 2, sw / 2, w - knob - sw / 2, h - sw / 2);
            cv.drawRoundRect(r, rad, rad, line);
            fill.setColor(Ui.TEXT_2);
            cv.drawRect(w - knob, h * 0.3f, w, h * 0.7f, fill);
            if (pct >= 0) {
                float in = sw * 1.6f;
                float right = in + (w - knob - 2 * in) * Math.max(0.04f, Math.min(1, pct / 100f));
                fill.setColor(color);
                r.set(in, in, right, h - in);
                cv.drawRoundRect(r, rad / 2, rad / 2, fill);
            }
            if (charging) {
                // a small bolt in the middle, in the card's colour so it shows on the fill
                fill.setColor(Ui.CARD);
                float cx = (w - knob) / 2, top = h * 0.12f, bot = h * 0.88f;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx + h * 0.12f, top);
                p.lineTo(cx - h * 0.22f, h * 0.55f);
                p.lineTo(cx, h * 0.55f);
                p.lineTo(cx - h * 0.12f, bot);
                p.lineTo(cx + h * 0.22f, h * 0.45f);
                p.lineTo(cx, h * 0.45f);
                p.close();
                cv.drawPath(p, fill);
            }
        }
    }
}
