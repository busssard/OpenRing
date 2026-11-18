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
 */
public final class TrendsActivity extends Activity {
    /** Extras: the group to open at ({@code Trends.G_SLEEP} and so on), the shown day (yyyy-mm-dd), and for the screen test a measure and period. */
    static final String GROUP = "group", DAY = "day", MEASURE = "measure", PERIOD = "period";

    private LinearLayout body;
    private int group, measure = -1, period = Trends.MONTHS, loads;
    private LocalDate shownDay, today;
    private boolean demo, preselect = true;
    private List<Trends.Day> days;
    private final boolean[] howOpen = {false};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        android.content.Intent in = getIntent();
        group = Math.max(0, Math.min(Trends.GROUPS.length - 1, in.getIntExtra(GROUP, Trends.G_SLEEP)));
        int m = in.getIntExtra(MEASURE, -1);
        if (m >= 0 && m < Trends.COUNT) measure = m;
        period = Math.max(0, Math.min(2, in.getIntExtra(PERIOD, Trends.MONTHS)));
        try {
            String d = in.getStringExtra(DAY);
            shownDay = d == null ? null : LocalDate.parse(d);
        } catch (RuntimeException e) {
            shownDay = null;
        }
        if (b != null) {
            group = b.getInt(GROUP, group);
            measure = b.getInt(MEASURE, measure);
            period = b.getInt(PERIOD, period);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // again on every return: a sync may have brought new nights meanwhile; the page shows the old ones until then
        load();
    }

    /** What the background read hands to the screen. */
    static final class Loaded {
        List<Trends.Day> days;
        LocalDate today;
        boolean failed;
    }

    void load() {
        final int mine = ++loads;
        final android.content.Context ctx = getApplicationContext();
        demo = Prefs.of(this).demo();
        new AsyncTask<Void, Void, Loaded>() {
            @Override
            protected Loaded doInBackground(Void... v) {
                Loaded l = new Loaded();
                l.today = LocalDate.now();
                try {
                    Prefs p = Prefs.of(ctx);
                    // the devices as the tabs show them: the ring, the watch's nights, without a ring the watch's or the phone's days
                    l.days = TrendDays.read(Db.get(ctx), l.today, System.currentTimeMillis() / 1000.0, p.screenExcluded(), DayData.shownNow(ctx),
                            ZoneId.systemDefault());
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
                if (mine != loads || isFinishing() || isDestroyed()) return;
                if (l.failed || l.days == null) {
                    body.removeAllViews();
                    LinearLayout card = Ui.card(TrendsActivity.this);
                    card.addView(Ui.body(TrendsActivity.this, Explain.TRENDS_FAILED));
                    body.addView(card);
                    return;
                }
                days = l.days;
                today = l.today;
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
            default: return Ui.SLEEP;
        }
    }

    /** A row of chips that scrolls sideways when it is wider than the screen. */
    HorizontalScrollView chipRow(String[] names, int current, int color, final Pick pick) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.hbox(this);
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
        }
        sv.addView(row);
        return sv;
    }

    interface Pick {
        void picked(int i);
    }

    /** Builds the page from the days read; a chip calls it again, without reading anything. */
    void fill() {
        body.removeAllViews();
        if (demo) {
            LinearLayout banner = Ui.card(this);
            banner.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.14f), Ui.dp(this, 14)));
            banner.addView(Ui.text(this, Explain.TRENDS_DEMO, 12, Ui.TEXT_2, Ui.REGULAR));
            body.addView(banner);
        }
        if (!Trends.enough(days, today)) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.body(this, Trends.tooShort(Trends.daysSoFar(days, today))));
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, Explain.TRENDS, 12, Ui.TEXT_3, Ui.REGULAR));
            body.addView(card);
            return;
        }
        final int[] measures = Trends.GROUPS[group];
        if (measure < 0 || !contains(measures, measure)) measure = measures[0];
        int c = color(group);
        body.addView(chipRow(Trends.GROUP_NAMES, group, c, new Pick() {
            @Override
            public void picked(int i) {
                if (i == group) return;
                group = i;
                measure = -1;
                fill();
            }
        }));
        body.addView(Ui.gap(this, 6));
        String[] names = new String[measures.length];
        int cur = 0;
        for (int i = 0; i < measures.length; i++) {
            names[i] = Trends.NAMES[measures[i]];
            if (measures[i] == measure) cur = i;
        }
        body.addView(chipRow(names, cur, c, new Pick() {
            @Override
            public void picked(int i) {
                if (measures[i] == measure) return;
                measure = measures[i];
                fill();
            }
        }));
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

        final Trends.View view = Trends.of(days, measure, period, today);
        LinearLayout card = Ui.card(this);
        String each = period == Trends.WEEKS ? "by week" : period == Trends.MONTHS ? "by month" : "by year";
        card.addView(Ui.caption(this, Trends.NAMES[measure] + ", " + each));
        card.addView(Ui.gap(this, 8));
        final TextView readout = Ui.text(this, "", 13, Ui.TEXT, Ui.REGULAR);
        final Locale l = Locale.getDefault();
        Charts.Months chart = new Charts.Months(this, view, c);
        chart.setContentDescription("Trends chart, " + Trends.NAMES[measure]);
        chart.pick(new Charts.Months.Pick() {
            @Override
            public void picked(int index) {
                readout.setText(index >= 0 ? Trends.readout(view, index, l) : newest(view, l));
            }
        });
        // opened from a day of an earlier period: that period touched (the month of the shown day, once per open)
        if (preselect && shownDay != null && today != null && Trends.start(shownDay, period).isBefore(Trends.start(today, period))) {
            chart.select(view.indexOf(shownDay));
        }
        preselect = false;
        card.addView(chart, Ui.sized(this, -1, 230));
        card.addView(Ui.gap(this, 8));
        readout.setText(newest(view, l));
        card.addView(readout);
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, Trends.note(measure, period), 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, Trends.explain(measure, view.detail), 12, Ui.TEXT_2, Ui.REGULAR));
        body.addView(card);

        LinearLayout cmp = Ui.card(this);
        cmp.addView(Ui.caption(this, Explain.TRENDS_COMPARE_CAPTION));
        cmp.addView(Ui.gap(this, 6));
        cmp.addView(Ui.body(this, Trends.compare(days, measure, today, l).text));
        cmp.addView(Ui.gap(this, 6));
        cmp.addView(Ui.text(this, Explain.TRENDS_COMPARE, 12, Ui.TEXT_3, Ui.REGULAR));
        body.addView(cmp);

        LinearLayout how = Ui.card(this);
        List<String> texts = new ArrayList<String>();
        texts.add(Explain.TRENDS);
        how.addView(Ui.howItWorks(this, howOpen, texts));
        body.addView(how);
    }

    /** The line under the chart before a touch: the newest period with a value, or how to read the chart. */
    static String newest(Trends.View view, Locale l) {
        for (int i = view.buckets.size() - 1; i >= 0; i--) if (view.buckets.get(i).n > 0) return Trends.readout(view, i, l);
        return "No days with a value yet.";
    }

    static boolean contains(int[] a, int x) {
        for (int v : a) if (v == x) return true;
        return false;
    }
}
