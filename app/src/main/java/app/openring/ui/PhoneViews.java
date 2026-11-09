package app.openring.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import app.openring.core.KeyState;
import app.openring.core.MainDevice;
import app.openring.core.PhoneSteps;
import app.openring.data.Prefs;

/**
 * Steps from this phone on the screens (issue #329, phase 1b): the card on Activity that asks for Physical activity in
 * context, the line when it was not allowed, and the evidence (the phone's intervals as light bars under the main device's
 * steps per hour, touch readable). Also the "Add a ring" helpers of the screens without a ring.
 */
final class PhoneViews {
    private PhoneViews() {}

    /** The request code of Physical activity (MainActivity and Settings answer it). */
    static final int REQUEST = 329;
    /** Screen test only (extra {@code phone_ask}, demo mode): Activity shows the card that asks, nothing is asked without a tap. */
    static boolean previewAsk;

    /** Asks for Physical activity (Android 10 and newer), noting that it was asked so a refusal reads as one later. */
    static void ask(Activity a) {
        Prefs.of(a).setPhoneAsked();
        if (android.os.Build.VERSION.SDK_INT >= 29) a.requestPermissions(new String[] {android.Manifest.permission.ACTIVITY_RECOGNITION}, REQUEST);
    }

    /**
     * The phone's card on Activity, null for none: in demo mode its made up steps; no step counter: one line; not allowed:
     * the card that asks (never asked) or the line that says it is off with "Allow" (asked before); allowed: the evidence of
     * this day, or a line that counting has started when nothing is stored yet.
     */
    static View activityCard(final Context c, DayData d) {
        Prefs p = Prefs.of(c);
        boolean demo = p.demo();
        if (demo && previewAsk) return askCard(c);
        if (!demo && !d.phoneSensor) {
            LinearLayout card = Ui.card(c);
            card.addView(Ui.caption(c, PhoneSteps.TITLE));
            card.addView(Ui.text(c, PhoneSteps.NO_SENSOR, 13, Ui.TEXT_2, Ui.REGULAR));
            return card;
        }
        if (!demo && !d.phoneAllowed) {
            if (!p.phoneAsked()) return askCard(c);
            LinearLayout card = Ui.card(c);
            card.addView(Ui.caption(c, PhoneSteps.TITLE));
            card.addView(Ui.text(c, PhoneSteps.DECLINED, 13, Ui.TEXT_2, Ui.REGULAR));
            TextView allow = Ui.smallButton(c, PhoneSteps.ALLOW, Ui.OXY);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (c instanceof Activity) Permissions.openSettings((Activity) c);
                }
            });
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.setMargins(0, Ui.dp(c, 10), 0, 0);
            card.addView(allow, lp);
            return card;
        }
        if (d.merged == null || d.merged.phone.isEmpty()) {
            if (demo || d.phoneAny) return null;
            LinearLayout card = Ui.card(c);
            card.addView(Ui.caption(c, PhoneSteps.TITLE));
            card.addView(Ui.text(c, PhoneSteps.WAITING, 13, Ui.TEXT_2, Ui.REGULAR));
            return card;
        }
        return evidenceCard(c, d);
    }

    /** "Count steps with this phone": what it does, and one button that asks for Physical activity. */
    static View askCard(final Context c) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, PhoneSteps.TITLE));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.body(c, PhoneSteps.ABOUT));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, Explain.PHONE_STEPS, 12, Ui.TEXT_3, Ui.REGULAR));
        TextView b = Ui.button(c, PhoneSteps.ASK, Ui.OXY, true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // demo mode never asks (the screen test only shows the card)
                if (Prefs.of(c).demo() || !(c instanceof Activity)) return;
                ask((Activity) c);
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 12), 0, 0);
        card.addView(b, lp);
        return card;
    }

    /** The evidence: the main device's steps per hour, the phone's intervals as light bars under them, touch readable. */
    static View evidenceCard(Context c, DayData d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, PhoneSteps.TITLE));
        card.addView(Ui.gap(c, 8));
        card.addView(new DayLine(c, d.merged, d.dayStart(), d.dayEnd(), d.zone), Ui.sized(c, -1, 150));
        card.addView(Ui.gap(c, 6));
        int base = d.merged.base >= 0 ? d.merged.base : d.merged.main;
        String top = base == MainDevice.PHONE ? "Bars: steps per hour from your phone." : "Bars: steps per hour from " + MainDevice.name(base) + ".";
        card.addView(Ui.text(c, top + " Light bars below: each time between two readings of your phone, higher when you walked more. Touch to see the numbers.",
                12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(c, PhoneSteps.SPREAD + " " + PhoneSteps.ABOUT, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    /** The day line of {@link #evidenceCard}. */
    static final class DayLine extends Charts.Scrub {
        private final MainDevice.Day day;
        private final double from, to;
        private final ZoneId zone;
        private final double[] hourly;
        private final int base;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        private final android.graphics.RectF r = new android.graphics.RectF();

        DayLine(Context c, MainDevice.Day day, double from, double to, ZoneId zone) {
            super(c);
            this.day = day;
            this.from = from;
            this.to = to;
            this.zone = zone;
            base = day.base >= 0 ? day.base : day.main;
            int hours = (int) Math.ceil((to - from) / 3600 - 1e-9);
            hourly = new double[Math.max(1, hours)];
            for (double[] p : day.baseParts) {
                int h = (int) p[0] / 4;
                if (h >= 0 && h < hourly.length) hourly[h] += p[1];
            }
            bar.setColor(Ui.ACTIVE);
            light.setColor(Ui.alpha(Ui.OXY, 0.4f));
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(Ui.LINE);
            grid.setStrokeWidth(1);
            setContentDescription("Steps through the day, touch to read the numbers");
        }

        private float x(double t, float left, float w) {
            return (float) (left + (t - from) / (to - from) * (w - left));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = 0, tipH = Ui.dp(getContext(), 22);
            float axis = h - Ui.dp(getContext(), 14);
            float rowTop = axis - Ui.dp(getContext(), 30), rowBottom = axis - Ui.dp(getContext(), 4);
            float barBottom = rowTop - Ui.dp(getContext(), 6), barTop = tipH;
            double max = 1;
            for (double v : hourly) max = Math.max(max, v);
            cv.drawLine(left, barBottom, w, barBottom, grid);
            float hw = (w - left) * (float) (3600 / (to - from));
            for (int k = 0; k < hourly.length; k++) {
                if (hourly[k] <= 0) continue;
                float bx = x(from + k * 3600, left, w);
                float bh = (float) (hourly[k] / max * (barBottom - barTop));
                r.set(bx + 1, barBottom - bh, bx + hw - 1, barBottom);
                cv.drawRoundRect(r, Ui.dp(getContext(), 2), Ui.dp(getContext(), 2), bar);
            }
            // the phone's intervals: as wide as their time, as high as their steps per minute
            double rateMax = 1;
            for (double[] i : day.phone) rateMax = Math.max(rateMax, i[2] / Math.max(60, i[1] - i[0]));
            for (double[] i : day.phone) {
                float a = x(Math.max(from, i[0]), left, w), b = x(Math.min(to, i[1]), left, w);
                double rate = i[2] / Math.max(60, i[1] - i[0]);
                float top = rowBottom - (float) Math.max(Ui.dp(getContext(), 2), rate / rateMax * (rowBottom - rowTop));
                r.set(a + 0.5f, top, Math.max(a + 1.5f, b - 0.5f), rowBottom);
                cv.drawRect(r, light);
            }
            cv.drawLine(left, rowBottom, w, rowBottom, grid);
            // the clock every 6 hours
            for (int k = 0; k * 3600 <= to - from; k += 6) {
                double t = from + k * 3600;
                txt.setTextAlign(k == 0 ? Paint.Align.LEFT : t >= to - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                cv.drawText(clock(t), x(t, left, w), h - Ui.dp(getContext(), 2), txt);
            }
            if (!Float.isNaN(scrubX)) {
                double t = from + (Math.max(left, Math.min(w - 1, scrubX)) - left) / (w - left) * (to - from);
                drawTip(cv, scrubX, tipH, rowBottom, tip(t));
            }
        }

        /** Both lines of the readout at {@code t}: the main device's hour, and the phone's interval there. */
        String tip(double t) {
            int k = (int) Math.max(0, Math.min(hourly.length - 1, Math.floor((t - from) / 3600)));
            double a = from + k * 3600, b = Math.min(to, a + 3600);
            String hour = clock(a) + " to " + clock(b);
            boolean worn = false;
            for (int s = k * 4; s < Math.min(day.baseWorn.length, k * 4 + 4); s++) worn |= day.baseWorn[s];
            long v = Math.round(hourly[k]);
            String first = !worn && v == 0 ? hour + ", " + MainDevice.name(base) + (base == MainDevice.PHONE ? " counted nothing" : " was not worn")
                    : hour + ", " + (base == MainDevice.WATCH ? "" : "about ") + String.format(Locale.getDefault(), "%,d", v) + (v == 1 ? " step" : " steps")
                            + " from " + MainDevice.name(base);
            PhoneSteps.Interval at = null;
            for (double[] i : day.phone) if (t >= i[0] && t < i[1]) at = new PhoneSteps.Interval(i[0], i[1], (long) i[2], 0, 0);
            if (at == null) return first + "\nNo reading of your phone covers this time";
            return first + "\n" + PhoneSteps.tip(at, from, to, zone, Locale.getDefault());
        }

        String clock(double t) {
            java.time.ZonedDateTime z = Instant.ofEpochSecond((long) Math.round(t)).atZone(zone);
            return String.format(Locale.getDefault(), "%02d:%02d", z.getHour(), z.getMinute());
        }
    }

    // ---------------------------------------------------------------------------------------- without a ring

    /** No ring by choice, outside demo mode (issue #329): ring only actions show "Add a ring" instead. */
    static boolean noRing(Context c) {
        Prefs p = Prefs.of(c);
        return KeyState.addRingInstead(p.demo(), p.ringState());
    }

    /** Opens Setup to pair a ring, from a screen without one; Back there returns here. */
    static void addRing(Context c) {
        c.startActivity(new Intent(c, SetupActivity.class).putExtra(SetupActivity.ADD_RING, true));
    }

    /** The window a ring only action shows without a ring: what is missing and "Add a ring". */
    static void addRingDialog(final Context c) {
        new android.app.AlertDialog.Builder(c)
                .setTitle(KeyState.ADD_RING)
                .setMessage(KeyState.NO_RING_TEXT)
                .setPositiveButton(KeyState.ADD_RING, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int which) {
                        addRing(c);
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** A full width "Add a ring" button. */
    static TextView addRingButton(final Context c, boolean filled) {
        TextView b = Ui.button(c, KeyState.ADD_RING, Ui.SLEEP, filled);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRing(c);
            }
        });
        return b;
    }

    /** Sleep and Ready show {@code DeviceViews.needDevice}: no ring, no watch and no night stored, outside demo mode. */
    static boolean needsDevice(Context c, DayData d) {
        // the devices as the day was loaded (issue #373: also the demo's previews)
        return d.has.phoneOnly() && !d.anyNight && (d.merged == null || d.merged.watchNight == null);
    }
}
