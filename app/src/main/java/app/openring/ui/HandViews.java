package app.openring.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.HandNight;
import app.openring.data.Db;
import app.openring.data.HandNights;
import app.openring.data.Json;
import app.openring.data.Prefs;

/**
 * The night entered by hand on the screens (issue #427): Today's question after a sync, the Sleep tab's offer on a day
 * without a night, the night by hand on the Sleep tab (one block "Asleep (by hand)" with the wake marks, Change and Delete with
 * Undo), the card of a night by hand a device night replaced, and the bar both the Sleep tab and the entry screen
 * ({@link HandNightActivity}) draw. Every write is one statement or one transaction in the background, then the page loads
 * again; nothing starts an analysis. The log gets an error's kind only, never a time or a day.
 */
final class HandViews {
    private HandViews() {}

    /** The last change, until Undo, the next change or 10 minutes. */
    static Undo undo;

    /**
     * What one change wrote, so Undo can put it back in the same file: a save ({@code saved}) or a delete ({@code removed}, the
     * key). After a restore, going back to a copy or Delete all (a new {@code Db}, or {@link #forget}) Undo writes nothing.
     */
    static final class Undo {
        final Db db;
        final boolean demo;
        final HandNights.Saved saved;
        final double removed;
        final String text;
        final long at = System.currentTimeMillis();

        Undo(Db db, boolean demo, HandNights.Saved saved, double removed, String text) {
            this.db = db;
            this.demo = demo;
            this.saved = saved;
            this.removed = removed;
            this.text = text;
        }
    }

    /** Delete all, a restore or going back to a copy: the last change belongs to a file that is gone, so no Undo for it. */
    static void forget() {
        undo = null;
    }

    /** Opens the entry screen for the night of {@code day}; {@code made} the night to change, NaN for a new one. */
    static void open(Context c, LocalDate day, double made) {
        Intent i = new Intent(c, HandNightActivity.class).putExtra("day", day.toString());
        if (!Double.isNaN(made)) i.putExtra("made", made);
        c.startActivity(i);
    }

    // ------------------------------------------------------------------------------ Today

    /** "No night for last night. Did you sleep without your ring and watch?" with Enter by hand and Not now. */
    static View askCard(final Context c, final DayData d, final Pages.Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.body(c, HandNight.ASK));
        card.addView(Ui.gap(c, 10));
        LinearLayout row = Ui.hbox(c);
        TextView later = Ui.button(c, HandNight.NOT_NOW, Ui.TEXT_2, false);
        later.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // hidden for this night only: the next morning asks again
                Prefs.of(c).setHandNotNow(d.date.toString());
                nav.reload();
            }
        });
        TextView enter = Ui.button(c, HandNight.ASK_ENTER, Ui.SLEEP, true);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(c, d.date, Double.NaN);
            }
        });
        row.addView(later, Ui.weight(1));
        row.addView(Ui.gap(c, 8));
        row.addView(enter, Ui.weight(1));
        card.addView(row, Ui.matchWrap());
        return card;
    }

    // ------------------------------------------------------------------------------ Sleep

    /** The Sleep tab of a day without a night: Enter the night by hand (up to 30 days back), with Undo after a delete; null when not offered. */
    static View offerCard(final Context c, final DayData d, final Pages.Nav nav) {
        if (!d.handOk || !HandNight.offer(d.date, LocalDate.now()) || PhoneViews.needsDevice(c, d)) return null;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Night by hand"));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.body(c, Explain.HAND_OFFER));
        card.addView(Ui.gap(c, 10));
        TextView b = Ui.button(c, HandNight.ENTER, Ui.SLEEP, true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(c, d.date, Double.NaN);
            }
        });
        card.addView(b, Ui.matchWrap());
        undoRow(c, card, nav);
        return card;
    }

    /** The Sleep tab with the night entered by hand ({@code d.night}, shown as no device has one). */
    static View sleep(final Context c, final DayData d, final Pages.Nav nav) {
        LinearLayout root = Ui.vbox(c);
        final JSONObject n = d.night;
        ZoneId z = d.zoneOf(n);
        double s = Json.d(n, "start"), e = Json.d(n, "end");
        root.addView(Pages.hero(c, d.score("sleep"), "Sleep", Ui.SLEEP, "Total sleep " + Ui.hm(Json.d(n, "asleep_min")) + "  ·  "
                + HandNight.clock(s, z) + " to " + HandNight.clock(e, z), HandNight.SCORE_LINE));
        List<JSONObject> strip = d.has.ring ? d.recent : d.watchRecent;
        if (!strip.isEmpty()) root.addView(Pages.recentNights(c, d, strip, nav));
        View left = Pages.leftOutCard(c, d);
        if (left != null) root.addView(left);
        View life = LifeCard.night(c, d, nav);
        if (life != null) root.addView(life);

        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Your night"));
        card.addView(Ui.gap(c, 8));
        List<double[]> marks = HandNights.marks(n);
        HandBar bar = new HandBar(c, d.date, z, s, e, marks, false);
        card.addView(bar, Ui.sized(c, -1, HandBar.HEIGHT_DP));
        card.addView(Ui.text(c, "Touch or slide along the bar to see the time.", 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.text(c, HandNight.ENTERED, 12, Ui.SLEEP, Ui.MEDIUM));
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.statRow(c, Ui.stat(c, HandNight.IN_BED, HandNight.clock(s, z), "", Ui.SLEEP, "When you went to bed"),
                Ui.stat(c, HandNight.UP, HandNight.clock(e, z), "", Ui.SLEEP, "When you got up")));
        card.addView(Ui.statRow(c, Ui.stat(c, HandNight.ASLEEP, Ui.hm(Json.d(n, "asleep_min")), "", Ui.SLEEP, "In bed to up, without the times awake"),
                Ui.stat(c, "Wake ups", String.valueOf(marks.size()), "", 0, marks.isEmpty() ? "None entered"
                        : Ui.hm(Json.d(n, "waso_min")) + " awake in all")));
        for (double[] m : marks) card.addView(Ui.text(c, HandNight.markText(m, z), 13, Ui.TEXT_2, Ui.REGULAR));
        card.addView(Ui.gap(c, 10));
        LinearLayout row = Ui.hbox(c);
        TextView change = Ui.button(c, HandNight.CHANGE, Ui.SLEEP, false);
        final double made = d.hand == null ? Double.NaN : d.hand.made;
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(c, d.date, made);
            }
        });
        TextView del = Ui.button(c, HandNight.DELETE, Ui.TEXT_2, false);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Double.isNaN(made)) remove(c, nav, made);
            }
        });
        row.addView(change, Ui.weight(1));
        row.addView(Ui.gap(c, 8));
        row.addView(del, Ui.weight(1));
        card.addView(row, Ui.matchWrap());
        undoRow(c, card, nav);
        root.addView(card);

        root.addView(Pages.contributors(c, d.scoreObj("sleep"), Ui.SLEEP, "Contributors", null, null));
        LinearLayout nm = Ui.card(c);
        nm.addView(Ui.body(c, HandNight.NOT_MEASURED));
        nm.addView(Ui.gap(c, 4));
        nm.addView(Ui.text(c, Explain.HAND_SCORE, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(nm);
        root.addView(Pages.infoCard(c, "About a night by hand", Explain.HAND_ABOUT));
        Pages.addNaps(c, root, d);
        root.addView(Pages.furtherAnalysis(c, d));
        return root;
    }

    /** The card of a night by hand a device night replaced: the line, the note and Delete; null when there is none. */
    static View replacedCard(final Context c, final DayData d, final Pages.Nav nav) {
        if (d.hand == null || d.handShown || d.handReplacedBy < 0) return null;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Night by hand"));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, HandNight.replaced(d.handReplacedBy), 15, Ui.TEXT, Ui.MEDIUM));
        ZoneId z = d.zone;
        card.addView(Ui.text(c, HandNight.IN_BED + " " + HandNight.clock(d.hand.start, z) + ", " + HandNight.UP.toLowerCase(Locale.ROOT) + " "
                + HandNight.clock(d.hand.stop, z) + ", " + HandNight.length(HandNight.asleepMin(d.hand.start, d.hand.stop, d.hand.wakes)) + " asleep",
                13, Ui.TEXT_2, Ui.REGULAR));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, HandNight.replacedNote(d.handReplacedBy), 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 8));
        TextView del = Ui.button(c, HandNight.DELETE, Ui.TEXT_2, false);
        final double made = d.hand.made;
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remove(c, nav, made);
            }
        });
        card.addView(del, Ui.matchWrap());
        undoRow(c, card, nav);
        return card;
    }

    /** "Night by hand deleted. Undo" under the card, while the last change is fresh and in this file's mode. */
    static void undoRow(final Context c, LinearLayout card, final Pages.Nav nav) {
        final Undo u = undo;
        if (u == null || u.demo != Prefs.of(c).demo() || System.currentTimeMillis() - u.at >= 10 * 60 * 1000L) return;
        LinearLayout ur = Ui.hbox(c);
        ur.setGravity(Gravity.CENTER_VERTICAL);
        ur.addView(Ui.text(c, u.text, 13, Ui.TEXT_2, Ui.REGULAR), Ui.weight(1));
        TextView ub = Ui.smallButton(c, "Undo", Ui.TEXT_2);
        ub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undo(c, nav, u);
            }
        });
        ur.addView(ub);
        LinearLayout.LayoutParams ul = Ui.matchWrap();
        ul.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(ur, ul);
    }

    /** Deletes the night {@code made} (a removal mark) in the background, then loads the page again; Undo puts it back. */
    static void remove(Context c, final Pages.Nav nav, final double made) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    Db db = Db.get(ctx);
                    HandNights.remove(db, made);
                    undo = new Undo(db, Prefs.of(ctx).demo(), null, made, HandNight.DELETED);
                    return true;
                } catch (RuntimeException e) {
                    app.openring.ble.RingManager.get(ctx).log("night by hand not deleted: " + e.getClass().getSimpleName());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) Toast.makeText(screen, "The night could not be deleted. Nothing was changed.", Toast.LENGTH_LONG).show();
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Puts back what {@code u} changed, in the file it changed (never across demo mode), in one transaction. */
    static void undo(Context c, final Pages.Nav nav, final Undo u) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        undo = null;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    if (Prefs.of(ctx).demo() != u.demo) return false;
                    Db db = Db.get(ctx);
                    // the file was swapped since (a restore, going back to a copy): its rows are not the ones changed
                    if (db != u.db) return false;
                    if (u.saved != null) HandNights.undo(db, u.saved);
                    else HandNights.undoRemove(db, u.removed);
                    return true;
                } catch (RuntimeException e) {
                    app.openring.ble.RingManager.get(ctx).log("night by hand not undone: " + e.getClass().getSimpleName());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) Toast.makeText(screen, "Could not undo. Nothing was changed.", Toast.LENGTH_LONG).show();
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // ------------------------------------------------------------------------------ the bar

    /**
     * The night bar from 18:00 to 14:00 (issue #427): the night as one block "Asleep (by hand)" with the wake marks, the hours
     * under it. Touch reads the time and what was then. Editable on the entry screen: drag In bed and Up (big handles), drag a
     * mark along the bar or its ends to stretch it, a tap on a mark removes it; every time snaps to 5 minutes.
     */
    static final class HandBar extends View {
        static final int HEIGHT_DP = 150;
        /** Told after each change while editing. */
        interface Changed {
            void changed();
        }

        final LocalDate day;
        final ZoneId zone;
        final double barFrom, barTo;
        double start, stop;
        List<double[]> marks;
        private final boolean editable;
        Changed listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), tip = new Paint(Paint.ANTI_ALIAS_FLAG),
                tipBg = new Paint(Paint.ANTI_ALIAS_FLAG), lab = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int slop;
        /** What a touch moves: 0 nothing (reading), 1 In bed, 2 Up, 3 a mark, 4 a mark's start, 5 a mark's end. */
        private int target;
        private int markAt = -1;
        private float downX, downY;
        private double grabOffset;
        private boolean moved;
        private float readX = Float.NaN;

        HandBar(Context c, LocalDate day, ZoneId zone, double start, double stop, List<double[]> marks, boolean editable) {
            super(c);
            this.day = day;
            this.zone = zone;
            double[] b = HandNight.bar(day, zone);
            barFrom = b[0];
            barTo = b[1];
            this.start = start;
            this.stop = stop;
            this.marks = HandNight.marks(start, stop, marks);
            this.editable = editable;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 11));
            txt.setTypeface(Ui.REGULAR);
            txt.setTextAlign(Paint.Align.CENTER);
            lab.setColor(Ui.TEXT);
            lab.setTextSize(Ui.dp(c, 12));
            lab.setTypeface(Ui.MEDIUM);
            lab.setTextAlign(Paint.Align.CENTER);
            tip.setColor(Ui.TEXT);
            tip.setTextSize(Ui.dp(c, 12));
            tip.setTypeface(Ui.MEDIUM);
            tip.setTextAlign(Paint.Align.CENTER);
            tipBg.setColor(Ui.CARD_HI);
            setContentDescription(describe());
        }

        /** The whole night in words, for screen readers. */
        String describe() {
            StringBuilder b = new StringBuilder(HandNight.IN_BED + " " + HandNight.clock(start, zone) + ", " + HandNight.UP + " " + HandNight.clock(stop, zone));
            for (double[] m : marks) b.append(", ").append(HandNight.markText(m, zone));
            return b.toString();
        }

        void set(double start, double stop, List<double[]> marks) {
            this.start = start;
            this.stop = stop;
            this.marks = HandNight.marks(start, stop, marks);
            setContentDescription(describe());
            invalidate();
        }

        private float pad() {
            return Ui.dp(getContext(), 18);
        }

        float x(double t) {
            float w = getWidth() - 2 * pad();
            return pad() + (float) ((t - barFrom) / (barTo - barFrom)) * w;
        }

        double t(float x) {
            float w = Math.max(1, getWidth() - 2 * pad());
            double t = barFrom + (x - pad()) / w * (barTo - barFrom);
            return Math.max(barFrom, Math.min(barTo, t));
        }

        private float barTop() {
            return Ui.dp(getContext(), 44);
        }

        private float barBottom() {
            return Ui.dp(getContext(), 84);
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float top = barTop(), bottom = barBottom(), mid = (top + bottom) / 2, rad = Ui.dp(c, 8);
            // the whole bar, 18:00 to 14:00
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.CARD_HI);
            r.set(pad(), top, getWidth() - pad(), bottom);
            cv.drawRoundRect(r, rad, rad, p);
            // the night: one block "Asleep (by hand)", the wake marks over it
            p.setColor(Ui.SLEEP);
            r.set(x(start), top, x(stop), bottom);
            cv.drawRoundRect(r, rad, rad, p);
            p.setColor(Ui.STAGE_AWAKE);
            for (double[] m : marks) cv.drawRect(x(m[0]), top + Ui.dp(c, 3), x(m[1]), bottom - Ui.dp(c, 3), p);
            // the hours: a tick every hour, a label every 3 hours (18, 21, 0, 3, 6, 9, 12), by the clock of the day's zone
            p.setColor(Ui.alpha(Ui.TEXT_3, 0.8f));
            LocalDateTime h = day.minusDays(1).atTime(HandNight.BAR_FROM_H, 0);
            for (int k = 0; k <= 24; k++) {
                LocalDateTime at = h.plusHours(k);
                double t = at.atZone(zone).toEpochSecond();
                if (t > barTo + 1) break;
                float xx = x(t);
                cv.drawRect(xx - Ui.dp(c, 0.5f), bottom + Ui.dp(c, 2), xx + Ui.dp(c, 0.5f), bottom + Ui.dp(c, at.getHour() % 3 == 0 ? 8 : 5), p);
                if (at.getHour() % 3 == 0) cv.drawText(String.valueOf(at.getHour()), xx, bottom + Ui.dp(c, 20), txt);
            }
            // the handles: big circles with their names under the hours
            if (editable) {
                for (int k = 0; k < 2; k++) {
                    float xx = x(k == 0 ? start : stop);
                    p.setColor(Ui.TEXT);
                    cv.drawCircle(xx, mid, Ui.dp(c, 13), p);
                    p.setColor(Ui.SLEEP);
                    cv.drawCircle(xx, mid, Ui.dp(c, 9), p);
                }
            }
            float ly = bottom + Ui.dp(c, 40);
            float xs = x(start), xe = x(stop), gap = Ui.dp(c, 56);
            if (xe - xs < gap) {
                xs = (xs + xe) / 2 - gap / 2;
                xe = xs + gap;
            }
            cv.drawText(HandNight.IN_BED + " " + HandNight.clock(start, zone), Math.max(Ui.dp(c, 40), xs), ly, lab);
            cv.drawText(HandNight.UP + " " + HandNight.clock(stop, zone), Math.min(getWidth() - Ui.dp(c, 32), xe), ly + Ui.dp(c, 16), lab);
            // the readout of a touch
            if (!Float.isNaN(readX)) {
                String line = readout();
                float tw = tip.measureText(line) / 2 + Ui.dp(c, 8);
                float cx = Math.max(tw, Math.min(getWidth() - tw, readX));
                r.set(cx - tw, Ui.dp(c, 4), cx + tw, Ui.dp(c, 30));
                cv.drawRoundRect(r, Ui.dp(c, 6), Ui.dp(c, 6), tipBg);
                cv.drawText(line, cx, Ui.dp(c, 22), tip);
                p.setColor(Ui.alpha(Ui.TEXT, 0.55f));
                cv.drawRect(readX - Ui.dp(c, 0.5f), Ui.dp(c, 30), readX + Ui.dp(c, 0.5f), bottom, p);
            }
        }

        /** The line the touch reads: what it moves, else the time at the finger and what was then. */
        String readout() {
            if (target == 1) return HandNight.IN_BED + " " + HandNight.clock(start, zone);
            if (target == 2) return HandNight.UP + " " + HandNight.clock(stop, zone);
            if (target >= 3 && markAt >= 0 && markAt < marks.size()) return HandNight.markText(marks.get(markAt), zone);
            double t = HandNight.snap(t(readX));
            String at = HandNight.clock(t, zone);
            if (t < start || t >= stop) return at + ", not in bed";
            for (double[] m : marks) if (t >= m[0] && t < m[1]) return at + ", awake";
            return at + ", asleep (by hand)";
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float ex = e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ex;
                    downY = e.getY();
                    moved = false;
                    target = 0;
                    markAt = -1;
                    if (editable) pick(ex);
                    if (target != 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    readX = ex;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(ex - downX), dy = Math.abs(e.getY() - downY);
                    if (!moved) {
                        if (dx > slop && dx > dy) {
                            moved = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        } else if (dy > slop && target == 0) {
                            readX = Float.NaN;
                            invalidate();
                            return false;
                        }
                    }
                    if (moved) {
                        drag(ex);
                        readX = ex;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (editable && !moved && target >= 3 && markAt >= 0 && markAt < marks.size()) {
                        // a tap on a mark removes it
                        List<double[]> l = new ArrayList<double[]>(marks);
                        l.remove(markAt);
                        set(start, stop, l);
                        tell();
                        readX = Float.NaN;
                    } else if (target != 0) {
                        readX = Float.NaN;
                    }
                    target = 0;
                    markAt = -1;
                    performClick();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    target = 0;
                    markAt = -1;
                    readX = Float.NaN;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        /** What a touch at {@code x} grabs: the nearer handle within reach, else a mark (its ends stretch it), else nothing. */
        private void pick(float x) {
            float reach = Ui.dp(getContext(), 26), end = Ui.dp(getContext(), 10);
            float ds = Math.abs(x - x(start)), de = Math.abs(x - x(stop));
            if (Math.min(ds, de) <= reach) {
                target = ds <= de ? 1 : 2;
                if (ds == de && x > x(start)) target = 2;
                return;
            }
            for (int i = 0; i < marks.size(); i++) {
                double[] m = marks.get(i);
                float a = x(m[0]), b = x(m[1]);
                if (x < a - end || x > b + end) continue;
                markAt = i;
                target = Math.abs(x - a) <= end && b - a > 2 * end ? 4 : Math.abs(x - b) <= end && b - a > 2 * end ? 5 : 3;
                grabOffset = t(x) - m[0];
                return;
            }
        }

        private void drag(float x) {
            if (target == 0) return;
            double t = t(x);
            if (target == 1 || target == 2) {
                double[] st = HandNight.times(day, zone, target == 1 ? t : start, target == 2 ? t : stop, target == 2);
                set(st[0], st[1], marks);
            } else if (markAt >= 0 && markAt < marks.size()) {
                List<double[]> l = new ArrayList<double[]>();
                for (double[] m : marks) l.add(new double[] {m[0], m[1]});
                double[] m = l.get(markAt);
                double len = m[1] - m[0];
                if (target == 3) {
                    double a = HandNight.snap(t - grabOffset);
                    a = Math.max(start + HandNight.SNAP_S, Math.min(stop - HandNight.SNAP_S - len, a));
                    m[0] = a;
                    m[1] = a + len;
                } else if (target == 4) {
                    m[0] = Math.min(m[1] - HandNight.SNAP_S, HandNight.snap(t));
                } else {
                    m[1] = Math.max(m[0] + HandNight.SNAP_S, HandNight.snap(t));
                }
                // the moved mark stays the one held, also when the order changes
                double keep = m[0];
                set(start, stop, l);
                markAt = -1;
                for (int i = 0; i < marks.size(); i++) if (marks.get(i)[0] <= keep + 1 && marks.get(i)[1] >= keep) markAt = i;
            }
            tell();
        }

        private void tell() {
            if (listener != null) listener.changed();
        }
    }
}
