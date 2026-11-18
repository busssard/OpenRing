package app.openring.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.SortedMap;
import java.util.Locale;
import java.util.TreeMap;

import app.openring.core.analysis.TS;
import app.openring.core.withings.Records;
import app.openring.core.withings.WatchDays;
import app.openring.data.Db;
import app.openring.data.Prefs;
import app.openring.data.WatchStore;

/**
 * The Watch page (issue #251, phase 1b): what the Withings watch measured, as it sent it. For the day picked: its steps
 * (with the last 30 days as dots), its workouts, its heart rate curve, Withings' own sleep of the night that ended on it,
 * and the battery per day with its drop per day. Every chart reads by touch. Reads the database in the background; in
 * demo mode the made up watch of the demo file.
 */
public final class WatchPageActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView dayLabel;
    private WatchStore.Data data;
    private WatchDays.Zones zones;
    /** The per day answers, worked out once on the loading thread (the taps on another day only look them up). */
    private WatchDays.Index index;
    private LocalDate day;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        scroll = new ScrollView(this);
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
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Watch"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));

        LinearLayout nav = Ui.hbox(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = Ui.smallButton(this, "Day before", Ui.TEXT_2);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (day != null) show(day.minusDays(1));
            }
        });
        TextView next = Ui.smallButton(this, "Next day", Ui.TEXT_2);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (day != null && day.isBefore(LocalDate.now())) show(day.plusDays(1));
            }
        });
        dayLabel = Ui.text(this, "", 15, Ui.TEXT, Ui.MEDIUM);
        dayLabel.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(dayLabel, Ui.weight(1));
        nav.addView(next);
        root.addView(nav, Ui.matchWrap());
        root.addView(Ui.gap(this, 8));
        content = Ui.vbox(this);
        root.addView(content, Ui.matchWrap());
        content.addView(Ui.text(this, "Reading the watch's data", 14, Ui.TEXT_2, Ui.REGULAR));
        setContentView(scroll);
        load();
    }

    /** Reads every stored row and the zones in the background, then shows the newest day with data. */
    void load() {
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                WatchStore.Data d = null;
                WatchDays.Zones z = null;
                WatchDays.Index ix = null;
                String err = null;
                try {
                    Db db = Db.get(appCtx);
                    // a newer decoder (an update) works the rows out again first, in one transaction (issue #251)
                    if (WatchStore.ensureDecoded(db)) app.openring.ble.RingManager.get(appCtx).log("watch page: a new decoder, every stored message decoded again");
                    WatchStore.Data read = WatchStore.load(db);
                    // the zones and the per day answers once, here and not on each tap (without zone rows: the phone's zone)
                    z = zonesOf(db.zones());
                    // REM per night, from each watch's own rows (code review of #389)
                    ix = new WatchDays.Index(read.steps, read.moves, read.heart, read.workouts, read.sleep, read.battery, z, WatchStore.rem(db));
                    d = read;
                } catch (RuntimeException e) {
                    d = null;
                    err = e.getClass().getSimpleName();
                    app.openring.ble.RingManager.get(appCtx).log("watch page: not read (" + err + ")");
                }
                final WatchStore.Data got = d;
                final WatchDays.Zones zs = z;
                final WatchDays.Index gotIndex = ix;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (got == null) {
                            content.removeAllViews();
                            content.addView(Ui.text(WatchPageActivity.this, "The watch's data could not be read. Settings, Help, Data check and log shows why.", 14, Ui.WARN, Ui.REGULAR));
                            return;
                        }
                        data = got;
                        zones = zs;
                        index = gotIndex;
                        show(newestDay());
                    }
                });
            }
        }, "watch page").start();
    }

    /** The zone at a time from the zones table, else the phone's (issue #22's rule, {@code WatchDays.zoneIn}). */
    static WatchDays.Zones zonesOf(List<Object[]> rows) {
        return WatchDays.zones(rows, ZoneId.systemDefault());
    }

    LocalDate newestDay() {
        long t = 0;
        for (Records.Steps s : data.steps) t = Math.max(t, s.start);
        for (Records.Heart x : data.heart) t = Math.max(t, x.t);
        return t == 0 ? LocalDate.now() : WatchDays.day(t, zones);
    }

    void show(LocalDate d) {
        day = d;
        dayLabel.setText(d.equals(LocalDate.now()) ? "Today" : d.equals(LocalDate.now().minusDays(1)) ? "Yesterday"
                : d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + d.getDayOfMonth() + "." + d.getMonthValue() + ".");
        final int y = scroll.getScrollY();
        content.removeAllViews();
        Context c = this;
        LinearLayout intro = Ui.card(c);
        String name = data.name == null ? "Withings watch" : data.name;
        intro.addView(Ui.text(c, name, 16, Ui.TEXT, Ui.MEDIUM));
        Prefs p = Prefs.of(c);
        intro.addView(Ui.text(c, p.demo() ? "Demo mode: made up watch data." : p.watchLastOk() == 0 ? "Not synced yet."
                : "Last sync " + SettingsActivity.ago(p.watchLastOk()) + ".", 13, Ui.TEXT_2, Ui.REGULAR));
        intro.addView(Ui.gap(c, 4));
        intro.addView(Ui.text(c, Explain.WATCH_PAGE, 12, Ui.TEXT_3, Ui.REGULAR));
        content.addView(intro);
        if (data.messages == 0) {
            LinearLayout e = Ui.card(c);
            e.addView(Ui.body(c, p.watchAddress() == null ? Explain.WATCH_ADD : "Nothing synced yet. Settings, Watch, Sync the watch now."));
            content.addView(e);
            return;
        }
        content.addView(steps(c, d));
        content.addView(workouts(c, d));
        content.addView(heart(c, d));
        content.addView(sleep(c, d));
        content.addView(battery(c, d));
        // the page was rebuilt for another day: stay where the person was, after layout
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
            }
        });
    }

    View steps(Context c, LocalDate d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Steps"));
        SortedMap<LocalDate, Integer> per = index.steps;
        SortedMap<LocalDate, Integer> metres = index.metres;
        double v = WatchDays.of(per, d), m = WatchDays.of(metres, d);
        card.addView(Ui.statRow(c, Ui.stat(c, "Steps", Double.isNaN(v) ? "--" : String.format(Locale.getDefault(), "%,d", (int) v), "", Ui.ACTIVE, "Counted by the watch on this day"),
                Ui.stat(c, "Distance", Double.isNaN(m) ? "--" : WatchDays.distance((int) m), "", Ui.ACTIVE, "The watch's own estimate from your steps")));
        double kcal = WatchDays.of(index.kcal, d), up = WatchDays.of(index.climb, d);
        card.addView(Ui.statRow(c, Ui.stat(c, "Calories", Double.isNaN(kcal) ? "--" : String.format(Locale.getDefault(), "%,d", (int) kcal), "kcal", Ui.ACTIVE,
                "Burned by moving, as the watch counted it"), Ui.stat(c, "Climbed", Double.isNaN(up) ? "--" : String.valueOf((int) up), "m", Ui.ACTIVE,
                "Metres up, the watch's own estimate")));
        TS ts = new TS();
        for (java.util.Map.Entry<LocalDate, Integer> e : per.entrySet()) {
            if (e.getKey().isAfter(d) || e.getKey().isBefore(d.minusDays(29))) continue;
            ts.add(e.getKey().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + 43200, e.getValue());
        }
        // the day per hour, touch readable (owner's report of 11 November); the hours add up to the day's count above
        long from = WatchDays.dayStart(d, zones), to = WatchDays.dayStart(d.plusDays(1), zones);
        if (!Double.isNaN(v)) {
            card.addView(Ui.gap(c, 8));
            card.addView(Ui.text(c, "Through the day", 12, Ui.TEXT_3, Ui.REGULAR));
            card.addView(new PhoneViews.DayLine(c, index.stepsPerHour(d), null, app.openring.core.MainDevice.WATCH, null, from, to, zones.at(from + 43200),
                    "Watch steps per hour, touch to read the numbers"), Ui.sized(c, -1, 120));
            card.addView(Ui.text(c, Explain.WATCH_HOURS, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, "Last 30 days", 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(new Charts.LineChart(c, ts, Double.NaN, Double.NaN, Ui.ACTIVE).daily(d, 30).unit(" steps").empty("No steps synced yet"), Ui.sized(c, -1, 120));
        card.addView(Ui.text(c, Explain.WATCH_STEPS, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(c, Explain.WATCH_DISTANCE + " " + Explain.WATCH_CALORIES + " " + Explain.WATCH_CLIMB, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    View workouts(Context c, LocalDate d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Workouts"));
        List<Records.Workout> ws = index.workoutsOn(d);
        if (ws.isEmpty()) card.addView(Ui.text(c, "No workout on this day.", 14, Ui.TEXT_2, Ui.REGULAR));
        for (Records.Workout w : ws) card.addView(Ui.text(c, WatchDays.workoutLine(w, zones), 14, Ui.TEXT, Ui.REGULAR));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, Explain.WATCH_WORKOUTS, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    View heart(Context c, LocalDate d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Heart rate"));
        List<long[]> hr = index.heartOn(d);
        TS ts = new TS();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (long[] x : hr) {
            ts.add(x[0], x[1]);
            lo = (int) Math.min(lo, x[1]);
            hi = (int) Math.max(hi, x[1]);
        }
        if (!hr.isEmpty()) {
            card.addView(Ui.statRow(c, Ui.stat(c, "Lowest", String.valueOf(lo), "bpm", Ui.HEART, "The lowest reading the watch stored on this day"),
                    Ui.stat(c, "Highest", String.valueOf(hi), "bpm", Ui.HEART, "The highest reading, often in a workout")));
        }
        long from = WatchDays.dayStart(d, zones), to = WatchDays.dayStart(d.plusDays(1), zones);
        card.addView(new Charts.LineChart(c, ts, from, to, Ui.HEART).unit(" bpm").zone(zones.at(from + 43200)).empty("No heart rate on this day"), Ui.sized(c, -1, 150));
        card.addView(Ui.text(c, Explain.WATCH_HEART, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    View sleep(Context c, LocalDate d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Sleep, as Withings sees it"));
        WatchDays.Night n = index.nightFor(d);
        if (n == null) {
            card.addView(Ui.text(c, "No sleep ended on this day.", 14, Ui.TEXT_2, Ui.REGULAR));
        } else {
            ZoneId z = zones.at(n.end);
            card.addView(Ui.statRow(c, Ui.stat(c, "Asleep", Ui.hm(n.asleepMin()), "", Ui.SLEEP, "Light, deep and REM added up"),
                    Ui.stat(c, "Awake", Ui.hm(n.awakeMin), "", Ui.SLEEP, "Awake inside the night, as the watch marked it")));
            boolean rem = index.rem.tells(n);
            if (rem) {
                card.addView(Ui.statRow(c, Ui.stat(c, "Deep", Ui.hm(n.deepMin), "", Ui.SLEEP, "Deep sleep as the watch counted it"),
                        Ui.stat(c, "Light", Ui.hm(n.lightMin), "", Ui.SLEEP, "Light sleep as the watch counted it")));
                card.addView(Ui.statRow(c, Ui.stat(c, "REM", Ui.hm(n.remMin), "", Ui.SLEEP, "REM sleep as the watch counted it"),
                        Ui.stat(c, "No data", Ui.hm(n.otherMin), "", Ui.SLEEP, "Time in the night without a sleep record")));
            } else {
                // the watch does not tell REM from light sleep (owner's report of 13 November): one stage, no empty REM
                card.addView(Ui.statRow(c, Ui.stat(c, "Deep", Ui.hm(n.deepMin), "", Ui.SLEEP, "Deep sleep as the watch counted it"),
                        Ui.stat(c, "Light and REM", Ui.hm(n.lightMin + n.remMin), "", Ui.SLEEP, Explain.LIGHT_REM)));
                card.addView(Ui.statRow(c, Ui.stat(c, "No data", Ui.hm(n.otherMin), "", Ui.SLEEP, "Time in the night without a sleep record")));
            }
            card.addView(Ui.gap(c, 8));
            WatchDays.NightHeart hr = index.heartIn(n);
            boolean withHr = !hr.points.isEmpty();
            card.addView(new Charts.Hypnogram(c, n.stages, n.start, n.end).zone(z).heart(withHr ? hr.points : null).lightRem(!rem),
                    Ui.sized(c, -1, withHr ? 230 : 150));
            if (withHr) {
                card.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", String.valueOf(hr.lowest), "bpm", Ui.HEART, "The watch's lowest reading this night"),
                        Ui.stat(c, "Average HR", Ui.fmt(hr.average, 0), "bpm", Ui.HEART, "Of its " + hr.points.size() + " readings this night")));
                card.addView(Ui.text(c, Explain.WATCH_NIGHT_HEART, 12, Ui.TEXT_3, Ui.REGULAR));
            }
        }
        card.addView(Ui.text(c, Explain.WATCH_SLEEP, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    View battery(Context c, LocalDate d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Battery"));
        Records.Battery now = index.newestBattery;
        long upTo = WatchDays.dayStart(d.plusDays(1), zones);
        double drop = index.dropPerDay(upTo - 7 * 86400L);
        card.addView(Ui.statRow(c, Ui.stat(c, "Now", now == null ? "--" : String.valueOf(now.pct), "%", Ui.READY, "At the last sync"),
                Ui.stat(c, "Per day", Double.isNaN(drop) ? "--" : Ui.fmt(drop, 1), "%", Ui.READY, "How much it fell per day in the last 7 days")));
        TS ts = new TS();
        for (java.util.Map.Entry<LocalDate, Integer> e : index.battery.entrySet()) {
            if (e.getKey().isAfter(d) || e.getKey().isBefore(d.minusDays(29))) continue;
            ts.add(e.getKey().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + 43200, e.getValue());
        }
        card.addView(new Charts.LineChart(c, ts, Double.NaN, Double.NaN, Ui.READY).daily(d, 30).range(0, 100).unit("%").noFill().empty("No battery reading yet"), Ui.sized(c, -1, 110));
        card.addView(Ui.text(c, Explain.WATCH_BATTERY + " " + Explain.WATCH_DROP, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }
}
