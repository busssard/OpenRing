package app.openring.ui;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.TrendChange;
import app.openring.core.analysis.Trends;
import app.openring.data.Db;
import app.openring.data.Prefs;
import app.openring.data.TrendDays;

/**
 * Trends (issue #374, phase 1): statistics over months and years. Chips pick the group (as the tabs) and its measure, and
 * Weeks, Months or Years over the whole history; the chart ({@link Charts.Months}) shows each period's dot with the middle half
 * of its days and every day behind it, read by touch; then "this month against the last". The history is read once per
 * open ({@link TrendDays#read}, in the background) into one small array per day; a chip only groups it again. Opened from the
 * card at the bottom of each tab ({@link TrendsCard}), at that tab's group and the month of the shown day. Reads only.
 * Phase 2: "Did something change?" ({@link TrendChange}, its shuffles once per measure and read, off the main thread) as a
 * dashed line with the average before and after, its sentence, and the events as marks under the chart, read by touch.
 * Phase 3: cycle and period length in the Body group (with the period tracker on and 2 periods logged), a group Life with the
 * days of each logged factor, and "Mark days with" under the chart, which draws a factor's periods as marks (None when the page
 * opens; display only, it changes no number and no sentence).
 */
public final class TrendsActivity extends Activity {
    /** Extras: the group to open at ({@code Trends.G_SLEEP} and so on), the shown day (yyyy-mm-dd), and for the screen test a measure and period. */
    static final String GROUP = "group", DAY = "day", MEASURE = "measure", PERIOD = "period", TOUCHED = "touched";
    /** Phase 3: a factor's key, in the Life group the factor shown, else the factor marked; and the mark kept through a turn. */
    static final String FACTOR = "factor", MARK = "mark";

    private LinearLayout body;
    private int group, measure = -1, period = Trends.MONTHS, loads, fills;
    private LocalDate shownDay, today;
    /** A day of the period touched on the chart (kept through a chip change, a reload and a turn of the phone), null for none. */
    private LocalDate touched;
    private boolean demo;
    private List<Trends.Day> days;
    /** The events stored apart from the days (left out times, periods, workouts, phone use as awake; phase 2), with the days. */
    private List<TrendChange.Event> known;
    /** "Did something change?" by measure for the days read (the same in Weeks, Months and Years), worked out once per read. */
    private java.util.Map<Integer, TrendChange.Result> changes = new java.util.concurrent.ConcurrentHashMap<Integer, TrendChange.Result>();
    /** What the days were read from ({@code TrendDays.stamp} with the day and the devices): read again only when it differs. */
    private String stamp;
    private final boolean[] howOpen = {false};
    /** Phase 3: the period tracker's cycles (null when off or under 2 periods), the life log, its factors (most days first) and names. */
    private app.openring.core.analysis.Cycles cycles;
    private java.util.TreeMap<LocalDate, List<String>> life = new java.util.TreeMap<LocalDate, List<String>>();
    private List<String> factors = new ArrayList<String>();
    private java.util.Map<String, String> names = new java.util.HashMap<String, String>();
    /** The factor the Life group shows, and the one marked on the other measures (null: None, as the page opens; never stored). */
    private String factor, mark;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        android.content.Intent in = getIntent();
        group = Math.max(0, Math.min(Trends.GROUPS.length - 1, in.getIntExtra(GROUP, Trends.G_SLEEP)));
        int m = in.getIntExtra(MEASURE, -1);
        if (m >= 0 && m < Trends.MEASURES) measure = m;
        String f = in.getStringExtra(FACTOR);
        if (f != null && !f.isEmpty()) {
            if (group == Trends.G_LIFE) factor = f;
            else mark = f;
        }
        period = Math.max(0, Math.min(2, in.getIntExtra(PERIOD, Trends.MONTHS)));
        try {
            String d = in.getStringExtra(DAY);
            shownDay = d == null ? null : LocalDate.parse(d);
        } catch (RuntimeException e) {
            shownDay = null;
        }
        // opened from a day of an earlier week, month or year: that period touched
        LocalDate now = LocalDate.now();
        if (shownDay != null && Trends.start(shownDay, period).isBefore(Trends.start(now, period))) touched = shownDay;
        if (b != null) {
            group = b.getInt(GROUP, group);
            measure = b.getInt(MEASURE, measure);
            period = b.getInt(PERIOD, period);
            factor = b.getString(FACTOR, factor);
            mark = b.getString(MARK, mark);
            String t = b.getString(TOUCHED);
            try {
                touched = t == null ? null : LocalDate.parse(t);
            } catch (RuntimeException e) {
                touched = null;
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);
        LinearLayout top = Ui.hbox(this);
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
        top.addView(Ui.h1(this, "Trends"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        body = Ui.vbox(this);
        root.addView(body, Ui.matchWrap());
        body.addView(Ui.text(this, "Reading your history", 14, Ui.TEXT_2, Ui.REGULAR));
        setContentView(scroll);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(GROUP, group);
        out.putInt(MEASURE, measure);
        out.putInt(PERIOD, period);
        if (touched != null) out.putString(TOUCHED, touched.toString());
        out.putString(FACTOR, factor);
        out.putString(MARK, mark);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // on every return, but the history is read again only when something changed (a sync, an analysis, another day)
        load();
    }

    /** What the background read hands to the screen. */
    static final class Loaded {
        List<Trends.Day> days;
        List<TrendChange.Event> events;
        TrendDays.Logs logs;
        LocalDate today;
        String stamp;
        boolean failed, same;
    }

    void load() {
        final int mine = ++loads;
        final android.content.Context ctx = getApplicationContext();
        demo = Prefs.of(this).demo();
        final String before = days == null ? null : stamp;
        new AsyncTask<Void, Void, Loaded>() {
            @Override
            protected Loaded doInBackground(Void... v) {
                Loaded l = new Loaded();
                l.today = LocalDate.now();
                try {
                    Prefs p = Prefs.of(ctx);
                    Db db = Db.get(ctx);
                    // the devices as the tabs show them: the ring, the watch's nights, without a ring the watch's or the phone's days
                    app.openring.data.ShownNights.Devices dev = DayData.shownNow(ctx);
                    // phase 3: the cycle measures follow the period tracker's switch
                    boolean periodShown = app.openring.core.analysis.Cycles.shown(p.periodChoice(), p.sex(), p.demo());
                    // every field of the devices, the profile's age too (the score of a night by hand, issue #459)
                    l.stamp = TrendDays.stamp(db) + "|" + l.today + "|" + p.screenExcluded() + "|" + dev.key()
                            + "|" + ZoneId.systemDefault().getId() + "|" + periodShown;
                    if (l.stamp.equals(before)) {
                        l.same = true;
                        return l;
                    }
                    int[] skipped = new int[1];
                    l.days = TrendDays.read(db, l.today, System.currentTimeMillis() / 1000.0, p.screenExcluded(), dev, ZoneId.systemDefault(), skipped);
                    // a stored night or day that could not be read is left out; the log says how many, never which or what
                    if (skipped[0] > 0) app.openring.ble.RingManager.get(ctx).log("trends: " + skipped[0] + " stored nights or days could not be read, left out");
                    // the events on the time axis (phase 2): a kind that cannot be read is left out, the log says how many kinds
                    int[] noEvents = new int[1];
                    l.events = TrendDays.events(db, l.today, ZoneId.systemDefault(), dev, noEvents);
                    if (noEvents[0] > 0) app.openring.ble.RingManager.get(ctx).log("trends: " + noEvents[0] + " kinds of events could not be read, left out");
                    // phase 3: the cycles and the life log; one that cannot be read is left out, the log says how many
                    int[] noLogs = new int[1];
                    l.logs = TrendDays.logs(db, l.today, periodShown, noLogs);
                    if (noLogs[0] > 0) app.openring.ble.RingManager.get(ctx).log("trends: " + noLogs[0] + " logs could not be read, left out");
                } catch (Throwable e) {
                    l.failed = true;
                    try {
                        // the error's kind only, never a value
                        app.openring.ble.RingManager.get(ctx).log("trends: the history could not be read: " + e.getClass().getSimpleName());
                    } catch (Throwable ignored) {
                        // the page says it failed
                    }
                }
                return l;
            }

            @Override
            protected void onPostExecute(Loaded l) {
                if (mine != loads || isFinishing() || isDestroyed() || l.same) return;
                if (l.failed || l.days == null) {
                    body.removeAllViews();
                    LinearLayout card = Ui.card(TrendsActivity.this);
                    card.addView(Ui.body(TrendsActivity.this, Explain.TRENDS_FAILED));
                    body.addView(card);
                    return;
                }
                days = l.days;
                known = l.events;
                cycles = l.logs == null ? null : l.logs.cycles;
                life = l.logs == null ? new java.util.TreeMap<LocalDate, List<String>>() : l.logs.life;
                factors = l.logs == null ? new ArrayList<String>() : l.logs.factors;
                names = l.logs == null ? new java.util.HashMap<String, String>() : l.logs.names;
                // a new map: a result still being worked out for the old days lands in the old one
                changes = new java.util.concurrent.ConcurrentHashMap<Integer, TrendChange.Result>();
                today = l.today;
                stamp = l.stamp;
                fill();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The colour of a group, as its tab. */
    static int color(int group) {
        switch (group) {
            case Trends.G_READINESS: return Ui.READY;
            case Trends.G_ACTIVITY: return Ui.ACTIVE;
            case Trends.G_BODY: return Ui.HEART;
            case Trends.G_SCREEN: return Ui.OXY;
            case Trends.G_LIFE: return Ui.LIFE;
            default: return Ui.SLEEP;
        }
    }

    /** A row of chips that scrolls sideways when it is wider than the screen. */
    HorizontalScrollView chipRow(String[] names, int current, int color, final Pick pick) {
        final HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.hbox(this);
        TextView picked = null;
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView chip = Ui.button(this, names[i], i == current ? color : Ui.TEXT_3, false);
            chip.setTextSize(13);
            int ph = Ui.dp(this, 12), pv = Ui.dp(this, 7);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.picked(k);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(this, 6), 0);
            row.addView(chip, lp);
            if (i == current) picked = chip;
        }
        sv.addView(row);
        // the picked chip in view (a measure far right in its row), once the row has its size
        final TextView shown = picked;
        if (shown != null) sv.post(new Runnable() {
            @Override
            public void run() {
                int right = shown.getRight() + Ui.dp(TrendsActivity.this, 16);
                if (right > sv.getWidth()) sv.scrollTo(right - sv.getWidth(), 0);
            }
        });
        return sv;
    }

    interface Pick {
        void picked(int i);
    }

    /**
     * Builds the page from the days read; a chip calls it again, without reading anything. The grouping and the comparison
     * (its 2000 draws) are worked out on the background executor; the page keeps its views until they are ready and is then
     * rebuilt at once, as before, so nothing jumps. A newer chip or reload wins over an older result.
     */
    void fill() {
        final int mine = ++fills;
        final List<Trends.Day> ds = days;
        final List<TrendChange.Event> ev = known;
        final java.util.Map<Integer, TrendChange.Result> found = changes;
        final LocalDate t = today;
        if (!Trends.enough(ds, t)) {
            // no demo note here: the made up year is not in what is shown ("Trends need at least two months")
            body.removeAllViews();
            LinearLayout card = Ui.card(this);
            card.addView(Ui.body(this, Trends.tooShort(Trends.daysSoFar(ds, t))));
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, Explain.TRENDS, 12, Ui.TEXT_3, Ui.REGULAR));
            body.addView(card);
            return;
        }
        // phase 3: the Life group only with a logged factor, the cycle measures only with the tracker's cycles
        if (group == Trends.G_LIFE && factors.isEmpty()) group = Trends.G_SLEEP;
        final int[] measures = Trends.measures(group, cycles != null);
        if (measure < 0 || !contains(measures, measure)) measure = measures[0];
        if (group == Trends.G_LIFE && (factor == null || !factors.contains(factor))) factor = factors.get(0);
        if (mark != null && !factors.contains(mark)) mark = null;
        final int m = measure, p = period;
        final Locale l = Locale.getDefault();
        final android.content.Context ctx = getApplicationContext();
        final app.openring.core.analysis.Cycles cy = cycles;
        final java.util.Map<LocalDate, List<String>> lf = life;
        final String fk = factor, fName = Trends.inSentence(factor, name(factor)), mk = mark, mName = name(mark);
        final LocalDate first = ds.isEmpty() ? t : ds.get(0).date;
        new AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... v) {
                try {
                    // "Did something change?": its 999 shuffles once per measure and read, seeded, so every opening says the same
                    TrendChange.Result c = found.get(m);
                    if (c == null) {
                        long t0 = System.nanoTime();
                        c = TrendChange.of(ds, m, t, l);
                        found.put(m, c);
                        long ms = (System.nanoTime() - t0) / 1000000;
                        // only the time and how many steps, never a value
                        if (ms > 300) app.openring.ble.RingManager.get(ctx).log("trends: looking for a change took " + ms + " ms (" + c.steps.size() + " steps)");
                    }
                    // phase 3: the picked factor's days as marks on every other measure (display only)
                    List<TrendChange.Event> withLife = new ArrayList<TrendChange.Event>(ev == null ? new ArrayList<TrendChange.Event>() : ev);
                    if (mk != null && m != Trends.LIFE) withLife.addAll(TrendChange.life(lf, mk, mName));
                    Trends.View view;
                    String compared;
                    if (m == Trends.LIFE) {
                        view = Trends.counts(lf, fk, fName, p, t, first);
                        compared = Trends.lifeCompare(lf, fk, fName, t, first);
                    } else if (m == Trends.CYCLE) {
                        view = Trends.cycles(cy.cycles, p);
                        compared = Trends.cycleLine(cy.cycles);
                    } else if (m == Trends.PERIOD_DAYS) {
                        view = Trends.periods(cy.periods, cy.running, p);
                        compared = Trends.periodLine(cy.periods, cy.running);
                    } else {
                        view = Trends.of(ds, m, p, t);
                        compared = Trends.compare(ds, m, t, l).text;
                    }
                    List<TrendChange.Event> marks = m == Trends.LIFE ? new ArrayList<TrendChange.Event>() : TrendChange.events(ds, m, t, withLife);
                    // where the lines and marks go, worked out here once, so drawing and touching only scale them
                    return new Object[] {view, compared, c, marks, TrendChange.drawn(view, c, marks)};
                } catch (Throwable e) {
                    try {
                        // the error's kind only, never a value
                        app.openring.ble.RingManager.get(ctx).log("trends: could not be worked out: " + e.getClass().getSimpleName());
                    } catch (Throwable ignored) {
                        // the page says it failed
                    }
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Object[] r) {
                if (mine != fills || isFinishing() || isDestroyed()) return;
                if (r == null) {
                    body.removeAllViews();
                    LinearLayout card = Ui.card(TrendsActivity.this);
                    card.addView(Ui.body(TrendsActivity.this, Explain.TRENDS_FAILED));
                    body.addView(card);
                    return;
                }
                @SuppressWarnings("unchecked")
                List<TrendChange.Event> marks = (List<TrendChange.Event>) r[3];
                show((Trends.View) r[0], (String) r[1], (TrendChange.Result) r[2], marks, (TrendChange.Drawn) r[4]);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * The page with enough history: the demo note, the chips, the chart of {@code view} with the steps of {@code change} and the
     * {@code marks}, "Did something change?" and the comparison {@code compared}.
     */
    void show(final Trends.View view, String compared, final TrendChange.Result change, final List<TrendChange.Event> marks,
            TrendChange.Drawn drawn) {
        body.removeAllViews();
        if (demo) {
            LinearLayout banner = Ui.card(this);
            banner.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.14f), Ui.dp(this, 14)));
            banner.addView(Ui.text(this, Explain.TRENDS_DEMO, 12, Ui.TEXT_2, Ui.REGULAR));
            body.addView(banner);
        }
        final int[] measures = Trends.measures(group, cycles != null);
        int c = color(group);
        // the Life group (phase 3) only with a logged factor; it is the last chip
        String[] groupNames = factors.isEmpty() ? java.util.Arrays.copyOf(Trends.GROUP_NAMES, Trends.G_LIFE) : Trends.GROUP_NAMES;
        body.addView(chipRow(groupNames, group, c, new Pick() {
            @Override
            public void picked(int i) {
                if (i == group) return;
                group = i;
                measure = -1;
                fill();
            }
        }));
        body.addView(Ui.gap(this, 6));
        if (group == Trends.G_LIFE) {
            // one chip per logged factor, the most days first (phase 3)
            String[] fn = new String[factors.size()];
            for (int i = 0; i < fn.length; i++) fn[i] = name(factors.get(i));
            body.addView(chipRow(fn, Math.max(0, factors.indexOf(factor)), c, new Pick() {
                @Override
                public void picked(int i) {
                    if (factors.get(i).equals(factor)) return;
                    factor = factors.get(i);
                    fill();
                }
            }));
        } else {
            String[] mn = new String[measures.length];
            int cur = 0;
            for (int i = 0; i < measures.length; i++) {
                mn[i] = Trends.NAMES[measures[i]];
                if (measures[i] == measure) cur = i;
            }
            body.addView(chipRow(mn, cur, c, new Pick() {
                @Override
                public void picked(int i) {
                    if (measures[i] == measure) return;
                    measure = measures[i];
                    fill();
                }
            }));
        }
        body.addView(Ui.gap(this, 6));
        body.addView(chipRow(Trends.PERIOD_NAMES, period, c, new Pick() {
            @Override
            public void picked(int i) {
                if (i == period) return;
                period = i;
                fill();
            }
        }));
        body.addView(Ui.gap(this, 10));

        LinearLayout card = Ui.card(this);
        String each = period == Trends.WEEKS ? "by week" : period == Trends.MONTHS ? "by month" : "by year";
        // "Alcohol, days by month" for a life factor (phase 3)
        card.addView(Ui.caption(this, measure == Trends.LIFE ? name(factor) + ", days " + each : Trends.NAMES[measure] + ", " + each));
        card.addView(Ui.gap(this, 8));
        final TextView readout = Ui.text(this, "", 13, Ui.TEXT, Ui.REGULAR);
        final Locale l = Locale.getDefault();
        Charts.Months chart = new Charts.Months(this, view, c).marks(drawn);
        chart.setContentDescription("Trends chart, " + (measure == Trends.LIFE ? name(factor) : Trends.NAMES[measure]));
        chart.pick(new Charts.Months.Pick() {
            @Override
            public void picked(int index) {
                readout.setText(index >= 0 ? TrendChange.readout(view, index, change, marks, l) : newest(view, l));
                // kept by its first day, so another period or a reload touches the period holding it
                touched = index >= 0 ? view.buckets.get(index).start : null;
            }
        });
        if (touched != null) chart.select(view.indexOf(touched));
        card.addView(chart, Ui.sized(this, -1, 230));
        card.addView(Ui.gap(this, 8));
        readout.setText(newest(view, l));
        card.addView(readout);
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, Trends.note(measure, period), 12, Ui.TEXT_3, Ui.REGULAR));
        String drawnNote = TrendChange.chartNote(change, marks);
        if (!drawnNote.isEmpty()) card.addView(Ui.text(this, drawnNote, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, Trends.explain(measure, view.detail), 12, Ui.TEXT_2, Ui.REGULAR));
        // phase 3: Mark days with a factor, None first and picked when the page opens; only draws, changes no number
        if (group != Trends.G_LIFE && !factors.isEmpty()) {
            card.addView(Ui.gap(this, 10));
            card.addView(Ui.text(this, Explain.TRENDS_MARK, 12, Ui.TEXT_2, Ui.MEDIUM));
            card.addView(Ui.gap(this, 4));
            String[] mn = new String[factors.size() + 1];
            mn[0] = Explain.TRENDS_MARK_NONE;
            for (int i = 0; i < factors.size(); i++) mn[i + 1] = name(factors.get(i));
            card.addView(chipRow(mn, mark == null ? 0 : factors.indexOf(mark) + 1, Ui.LIFE, new Pick() {
                @Override
                public void picked(int i) {
                    String k = i == 0 ? null : factors.get(i - 1);
                    if (k == null ? mark == null : k.equals(mark)) return;
                    mark = k;
                    fill();
                }
            }));
            if (mark != null) {
                card.addView(Ui.gap(this, 4));
                card.addView(Ui.text(this, Trends.markNote(Trends.inSentence(mark, name(mark)), period), 12, Ui.TEXT_3, Ui.REGULAR));
            }
        }
        body.addView(card);

        // Did something change? (phase 2): the steps, the events around them, what it cannot say
        LinearLayout ch = Ui.card(this);
        ch.addView(Ui.caption(this, TrendChange.CAPTION));
        ch.addView(Ui.gap(this, 6));
        ch.addView(Ui.body(this, change.text));
        String around = TrendChange.around(change, marks);
        if (!around.isEmpty()) {
            ch.addView(Ui.gap(this, 4));
            ch.addView(Ui.text(this, around, 13, Ui.TEXT_2, Ui.REGULAR));
        }
        // how a step is found, only where one is looked for (phase 3's measures say why not)
        if (Trends.daily(measure)) {
            ch.addView(Ui.gap(this, 6));
            ch.addView(Ui.text(this, Explain.TRENDS_CHANGE, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        body.addView(ch);

        LinearLayout cmp = Ui.card(this);
        boolean cyc = measure == Trends.CYCLE || measure == Trends.PERIOD_DAYS;
        cmp.addView(Ui.caption(this, measure == Trends.CYCLE ? Explain.TRENDS_CYCLES_CAPTION : measure == Trends.PERIOD_DAYS
                ? Explain.TRENDS_PERIODS_CAPTION : Explain.TRENDS_COMPARE_CAPTION));
        cmp.addView(Ui.gap(this, 6));
        cmp.addView(Ui.body(this, compared));
        cmp.addView(Ui.gap(this, 6));
        cmp.addView(Ui.text(this, cyc ? Explain.TRENDS_CYCLES_NOTE : measure == Trends.LIFE ? Explain.TRENDS_LIFE_COMPARE : Explain.TRENDS_COMPARE,
                12, Ui.TEXT_3, Ui.REGULAR));
        body.addView(cmp);

        LinearLayout how = Ui.card(this);
        List<String> texts = new ArrayList<String>();
        texts.add(Explain.TRENDS);
        how.addView(Ui.howItWorks(this, howOpen, texts));
        body.addView(how);
    }

    /** The line under the chart before a touch: the newest period with a value, or how to read the chart. */
    static String newest(Trends.View view, Locale l) {
        for (int i = view.buckets.size() - 1; i >= 0; i--) {
            Trends.Bucket b = view.buckets.get(i);
            if (b.n > 0 || b.logged) return Trends.readout(view, i, l);
        }
        return "No days with a value yet.";
    }

    /** A factor's name as on Today's row ("Alcohol"), "" for none. */
    String name(String key) {
        if (key == null) return "";
        String n = names.get(key);
        return n == null ? key : n;
    }

    static boolean contains(int[] a, int x) {
        for (int v : a) if (v == x) return true;
        return false;
    }
}
