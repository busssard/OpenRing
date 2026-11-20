package app.openring.ui;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.LocalDate;
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
 * Entering a night by hand (issue #427), one screen and no typing: the night bar from 18:00 to 14:00 with big In bed and Up
 * handles (and buttons that move them by 5 minutes), the time asleep big in the middle, I woke up in between (each tap adds a
 * 15 minute wake mark in the longest sleep stretch, to drag along the bar or stretch at its ends, a tap removes it), and Save.
 * The times start at the person's usual ones (the median of the last 14 shown nights, else 23:00 to 07:00) or, for Change, at
 * the stored night. Nothing is written before Save; Save writes one transaction in the background ({@link HandNights#save}).
 * Extras: {@code day} (yyyy-mm-dd, the day the night belongs to), {@code made} (the night to change), {@code demo_marks}
 * (int, demo mode only, the screen test: this many wake marks added).
 */
public final class HandNightActivity extends Activity {
    private LinearLayout body;
    private LocalDate day;
    private double made = Double.NaN;
    private ZoneId zone = ZoneId.systemDefault();
    private HandViews.HandBar bar;
    private TextView length, inBed, up, roomNote;
    private LinearLayout markList;
    private boolean saving;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        try {
            day = LocalDate.parse(getIntent().getStringExtra("day"));
        } catch (RuntimeException e) {
            day = LocalDate.now();
        }
        if (day.isAfter(LocalDate.now())) day = LocalDate.now();
        made = getIntent().getDoubleExtra("made", Double.NaN);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);
        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Night by hand"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        body = Ui.vbox(this);
        root.addView(body, Ui.matchWrap());
        body.addView(Ui.text(this, "Reading your nights", 14, Ui.TEXT_2, Ui.REGULAR));
        setContentView(scroll);
        load();
    }

    /** What the background read hands to the screen. */
    static final class Loaded {
        ZoneId zone;
        double start = Double.NaN, stop = Double.NaN, made = Double.NaN;
        List<double[]> marks = new ArrayList<double[]>();
        boolean failed;
    }

    void load() {
        final android.content.Context ctx = getApplicationContext();
        final LocalDate d = day;
        final double want = made;
        final int demoMarks = Prefs.of(this).demo() ? Math.max(0, getIntent().getIntExtra("demo_marks", 0)) : 0;
        new AsyncTask<Void, Void, Loaded>() {
            @Override
            protected Loaded doInBackground(Void... v) {
                Loaded l = new Loaded();
                try {
                    Db db = Db.get(ctx);
                    // the day's zone as the day load gives it without a ring night: the zone noted at its noon, else the phone's
                    l.zone = app.openring.data.WatchHistory.dayZones(db, ZoneId.systemDefault()).of(d);
                    HandNights.Row r = Double.isNaN(want) ? HandNights.forDay(db, d, l.zone) : HandNights.row(db, want);
                    if (r != null && !r.gone) {
                        l.start = r.start;
                        l.stop = r.stop;
                        l.marks = r.marks();
                        l.made = r.made;
                    } else {
                        // the usual times: the shown nights of the weeks before (the ring's, the watch's or by hand)
                        List<double[]> nights = new ArrayList<double[]>();
                        app.openring.core.DeviceHistory.DayZones zones = app.openring.data.WatchHistory.dayZones(db, ZoneId.systemDefault());
                        for (JSONObject o : app.openring.data.ShownNights.main(db, d.minusDays(30), d.minusDays(1), DayData.shownNow(ctx), zones)) {
                            double s = Json.d(o, "start"), e = Json.d(o, "end");
                            if (!Double.isNaN(s) && e > s) nights.add(new double[] {s, e});
                        }
                        double[] t = HandNight.defaults(d, l.zone, nights);
                        l.start = t[0];
                        l.stop = t[1];
                    }
                    for (int i = 0; i < demoMarks; i++) {
                        List<double[]> m = HandNight.addMark(l.start, l.stop, l.marks);
                        if (m != null) l.marks = m;
                    }
                } catch (Throwable e) {
                    l.failed = true;
                    try {
                        app.openring.ble.RingManager.get(ctx).log("night by hand: could not be read: " + e.getClass().getSimpleName());
                    } catch (Throwable ignored) {
                        // the page says it failed
                    }
                }
                return l;
            }

            @Override
            protected void onPostExecute(Loaded l) {
                if (isFinishing() || isDestroyed()) return;
                fill(l);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void fill(Loaded l) {
        body.removeAllViews();
        if (l.failed) {
            body.addView(Ui.body(this, HandNight.UNREADABLE));
            return;
        }
        zone = l.zone;
        made = l.made;
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, HandNight.title(day, Locale.getDefault())));
        card.addView(Ui.gap(this, 8));
        length = Ui.text(this, "", 40, Ui.TEXT, Ui.LIGHT);
        length.setGravity(Gravity.CENTER);
        card.addView(length, Ui.matchWrap());
        TextView what = Ui.text(this, Explain.HAND_LENGTH, 12, Ui.TEXT_3, Ui.REGULAR);
        what.setGravity(Gravity.CENTER);
        card.addView(what, Ui.matchWrap());
        card.addView(Ui.gap(this, 10));
        bar = new HandViews.HandBar(this, day, zone, l.start, l.stop, l.marks, true);
        bar.listener = new HandViews.HandBar.Changed() {
            @Override
            public void changed() {
                update();
            }
        };
        card.addView(bar, Ui.sized(this, -1, HandViews.HandBar.HEIGHT_DP));
        card.addView(Ui.text(this, HandNight.BAR_HINT, 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 10));
        inBed = Ui.text(this, "", 15, Ui.TEXT, Ui.MEDIUM);
        card.addView(stepRow(inBed, true));
        up = Ui.text(this, "", 15, Ui.TEXT, Ui.MEDIUM);
        card.addView(stepRow(up, false));
        body.addView(card);

        LinearLayout wake = Ui.card(this);
        wake.addView(Ui.caption(this, "Wake ups"));
        wake.addView(Ui.gap(this, 4));
        wake.addView(Ui.text(this, HandNight.MARK_HINT, 12, Ui.TEXT_3, Ui.REGULAR));
        markList = Ui.vbox(this);
        wake.addView(markList, Ui.matchWrap());
        roomNote = Ui.text(this, "", 12, Ui.WARN, Ui.REGULAR);
        roomNote.setVisibility(View.GONE);
        wake.addView(roomNote);
        wake.addView(Ui.gap(this, 8));
        TextView add = Ui.button(this, HandNight.WOKE, Ui.SLEEP, false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<double[]> m = HandNight.addMark(bar.start, bar.stop, bar.marks);
                if (m == null) {
                    roomNote.setText(HandNight.NO_ROOM);
                    roomNote.setVisibility(View.VISIBLE);
                    return;
                }
                bar.set(bar.start, bar.stop, m);
                update();
            }
        });
        wake.addView(add, Ui.matchWrap());
        body.addView(wake);

        body.addView(Ui.gap(this, 4));
        TextView save = Ui.button(this, HandNight.SAVE, Ui.SLEEP, true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        body.addView(save, Ui.matchWrap());
        body.addView(Ui.gap(this, 6));
        TextView note = Ui.text(this, Explain.HAND_SAVE, 12, Ui.TEXT_3, Ui.REGULAR);
        note.setGravity(Gravity.CENTER);
        body.addView(note, Ui.matchWrap());
        update();
    }

    /** "In bed 23:00" with buttons that move it 5 minutes earlier or later. */
    private LinearLayout stepRow(TextView label, final boolean bed) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.addView(label, Ui.weight(1));
        for (final int sign : new int[] {-1, 1}) {
            TextView b = Ui.smallButton(this, sign < 0 ? HandNight.EARLIER : HandNight.LATER, Ui.TEXT_2);
            b.setContentDescription((bed ? HandNight.IN_BED : HandNight.UP) + (sign < 0 ? ", 5 minutes earlier" : ", 5 minutes later"));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    double s = bar.start + (bed ? sign * HandNight.SNAP_S : 0), e = bar.stop + (bed ? 0 : sign * HandNight.SNAP_S);
                    // stops at the first or last wake mark, never drops one
                    double[] t = HandNight.times(day, zone, s, e, !bed, bar.marks);
                    bar.set(t[0], t[1], bar.marks);
                    update();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            row.addView(b, lp);
        }
        return row;
    }

    /** The big length, the two times and the list of wake marks, after every change. */
    void update() {
        length.setText(HandNight.length(HandNight.asleepMin(bar.start, bar.stop, bar.marks)));
        inBed.setText(HandNight.IN_BED + " " + HandNight.clock(bar.start, zone));
        up.setText(HandNight.UP + " " + HandNight.clock(bar.stop, zone));
        markList.removeAllViews();
        roomNote.setVisibility(View.GONE);
        if (bar.marks.isEmpty()) {
            markList.addView(Ui.text(this, "No wake ups entered (optional).", 13, Ui.TEXT_2, Ui.REGULAR));
            return;
        }
        for (int i = 0; i < bar.marks.size(); i++) {
            final int at = i;
            markList.addView(Ui.gap(this, 6));
            LinearLayout head = Ui.hbox(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.text(this, HandNight.markText(bar.marks.get(i), zone), 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
            TextView rm = Ui.smallButton(this, HandNight.REMOVE, Ui.TEXT_2);
            rm.setContentDescription("Remove wake up " + (at + 1));
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<double[]> l = new ArrayList<double[]>(bar.marks);
                    if (at < l.size()) l.remove(at);
                    bar.set(bar.start, bar.stop, l);
                    update();
                }
            });
            head.addView(rm);
            markList.addView(head, Ui.matchWrap());
            // the way without dragging: move the mark or change its length by 5 minutes
            LinearLayout row = Ui.hbox(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 4), 0, 0);
            row.addView(markButton(at, HandNight.EARLIER, ", 5 minutes earlier", 0, -1), markWeight());
            row.addView(markButton(at, HandNight.LATER, ", 5 minutes later", 0, 1), markWeight());
            row.addView(markButton(at, HandNight.SHORTER, ", 5 minutes shorter", 1, -1), markWeight());
            row.addView(markButton(at, HandNight.LONGER, ", 5 minutes longer", 1, 1), markWeight());
            markList.addView(row, Ui.matchWrap());
        }
    }

    private LinearLayout.LayoutParams markWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return lp;
    }

    /**
     * A button of wake mark {@code at}: {@code kind} 0 moves it 5 minutes ({@code sign}), 1 makes it 5 minutes longer or shorter
     * (its end); stopped at the night's ends and its neighbours ({@code HandNight.moveMark}, {@code resizeMark}).
     */
    private TextView markButton(final int at, String label, String spoken, final int kind, final int sign) {
        TextView b = Ui.smallButton(this, label, Ui.TEXT_2);
        b.setContentDescription("Wake up " + (at + 1) + spoken);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (at >= bar.marks.size()) return;
                double[] m = bar.marks.get(at);
                List<double[]> l = kind == 0 ? HandNight.moveMark(bar.start, bar.stop, bar.marks, at, m[0] + sign * HandNight.SNAP_S)
                        : HandNight.resizeMark(bar.start, bar.stop, bar.marks, at, true, m[1] + sign * HandNight.SNAP_S);
                bar.set(bar.start, bar.stop, l);
                update();
            }
        });
        return b;
    }

    /** Saves in the background (one transaction), notes Undo for the Sleep tab, then goes back to it. */
    void save() {
        if (saving || bar == null) return;
        saving = true;
        final android.content.Context ctx = getApplicationContext();
        final double s = bar.start, e = bar.stop;
        final List<double[]> m = new ArrayList<double[]>(bar.marks);
        final boolean change = !Double.isNaN(made);
        final double replaces = made;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    Db db = Db.get(ctx);
                    HandNights.Saved saved = HandNights.save(db, s, e, m, System.currentTimeMillis() / 1000.0, replaces);
                    HandViews.undo = new HandViews.Undo(db, Prefs.of(ctx).demo(), saved, Double.NaN, change ? HandNight.CHANGED : HandNight.SAVED);
                    return true;
                } catch (RuntimeException x) {
                    app.openring.ble.RingManager.get(ctx).log("night by hand not saved: " + x.getClass().getSimpleName());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                saving = false;
                if (isFinishing() || isDestroyed()) return;
                if (!ok) {
                    Toast.makeText(HandNightActivity.this, "The night could not be saved. Nothing was changed.", Toast.LENGTH_LONG).show();
                    return;
                }
                finish();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
