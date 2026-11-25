package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.util.Locale;

import app.openring.core.analysis.DaySummary;
import app.openring.core.analysis.Stats;
import app.openring.data.Json;
import app.openring.ui.Pages.Nav;

/** The Readiness tab and last night's body signals (issue #293, phase 0: moved out of {@link Pages}; its sentences are in core/analysis/DaySummary). */
final class ReadinessPage {
    private ReadinessPage() {}

    /** The sentence under the Readiness score, {@code core/analysis/DaySummary.headline} (issue #293, phase 0b). */
    static String headline(DayData d) {
        return DaySummary.headline(d.score("readiness"), d.score("sleep"));
    }

    static View view(Context c, DayData d, Nav nav) {
        // no ring and no watch (issue #329): one card instead of empty charts
        if (PhoneViews.needsDevice(c, d)) return DeviceViews.needDevice(c);
        LinearLayout root = Ui.vbox(c);
        JSONObject sc = d.scoreObj("readiness");
        if (app.openring.core.Start.shows(app.openring.core.Start.READY, app.openring.core.Start.READY_WATCH, d.has)) {
            // only a watch (issue #379): readiness from the watch's signals, what it used and what it left out
            boolean scored = d.fromDevice("readiness");
            String none = d.devices == null || d.devices.readinessNone == null ? app.openring.core.DeviceScores.WATCH_NO_READINESS : d.devices.readinessNone;
            root.addView(PageParts.hero(c, d.score("readiness"), "Readiness", Ui.READY, scored ? headline(d) : none,
                    scored ? app.openring.core.DeviceScores.FEWER : null));
            View life = LifeCard.morning(c, d, nav);
            if (life != null) root.addView(life);
            if (scored) {
                if (sc.optBoolean("provisional")) {
                    LinearLayout note = Ui.card(c);
                    note.addView(Ui.body(c, app.openring.core.DeviceScores.PROVISIONAL));
                    root.addView(note);
                }
                DeviceViews.deviceParts(c, root, d, "readiness", Ui.READY);
            }
            // last night's heart rate and the 30 and 7 day charts (owner's report of 13 November)
            HistoryViews.ready(c, root, d);
            root.addView(PageParts.infoCard(c, "What is readiness?", Explain.READINESS_DEVICE));
            root.addView(DeviceViews.needCard(c, null, app.openring.core.DeviceScores.RING_ADDS, false));
            return root;
        }
        root.addView(PageParts.hero(c, d.score("readiness"), "Readiness", Ui.READY,
                sc == null ? "Readiness appears the morning after a night with the ring on" : headline(d), d.watchVersion("readiness")));
        // what was logged the day before (issue #292): "Logged yesterday: Alcohol." Shown beside the number, never in it
        View life = LifeCard.morning(c, d, nav);
        if (life != null) root.addView(life);
        root.addView(PageParts.infoCard(c, "What is readiness?", Explain.READINESS));
        if (sc == null) {
            LinearLayout how = Ui.card(c);
            how.addView(Ui.caption(c, "How to get your first score"));
            how.addView(Ui.gap(c, 4));
            how.addView(Ui.body(c, "1. Wear the ring tonight, snug on the finger, not on the charger.\n"
                    + "2. In the morning open the app and sync.\n"
                    + "3. Readiness shows right away, and becomes personal after about two weeks of nights, once your normal "
                    + "resting heart rate, HRV and temperature are known."));
            root.addView(how);
            return root;
        }
        if (sc.optBoolean("provisional")) {
            LinearLayout note = Ui.card(c);
            note.addView(Ui.body(c, "Your baselines are still forming. Readiness compares each night with your personal normal, which takes about two weeks of nights to settle, so early scores move more."));
            root.addView(note);
        }
        if (sc.optBoolean("hand")) {
            // last night was entered by hand (issue #427): its sleep and the activity, no heart rate, HRV or temperature
            LinearLayout note = Ui.card(c);
            note.addView(Ui.body(c, Explain.HAND_READINESS));
            root.addView(note);
        }
        root.addView(signals(c, d));
        if (d.day != null && d.day.optBoolean("prev_day_unworn")) {
            // yesterday had less than an hour of ring data: its activity is left out, not counted as none (issue #240)
            LinearLayout note = Ui.card(c);
            note.addView(Ui.body(c, Explain.READINESS_UNWORN));
            root.addView(note);
        }
        root.addView(PageParts.contributors(c, sc, Ui.READY, "Contributors", d.day));

        LinearLayout t = Ui.card(c);
        t.addView(Ui.caption(c, "Temperature vs usual"));
        t.addView(Ui.text(c, PageParts.signed(Json.d(d.day, "temp_dev")) + " \u00b0C", 26, Ui.TEXT, Ui.LIGHT));
        t.addView(Ui.body(c, "Deviation from your usual nightly skin temperature. Within about half a degree is normal."));
        t.addView(Ui.gap(c, 8));
        t.addView(new Charts.LineChart(c, PageParts.daysSeries(d, "temp_dev", 30), Double.NaN, Double.NaN, Ui.TEMP).daily(d.end(30), 30).pick(d.date).empty("Appears after 3 nights, it compares with your usual").range(-1.5, 1.5).reference(0).noFill().signed().unit(" \u00b0C"), Ui.sized(c, -1, 120));
        root.addView(t);

        root.addView(PageParts.chartCard(c, "HRV, last 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "hrv", 30), Double.NaN, Double.NaN, Ui.READY).daily(d.end(30), 30).pick(d.date).unit(" ms"),
                "Average " + Ui.fmt(Stats.mean(d.series("hrv", 30)), 0) + " ms" + d.upTo(30) + ". Compare with yourself, not with others: HRV differs a lot between people."));
        root.addView(PageParts.chartCard(c, "Resting heart rate, last 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "rhr", 30), Double.NaN, Double.NaN, Ui.HEART).daily(d.end(30), 30).pick(d.date).unit(" bpm"),
                "Average " + Ui.fmt(Stats.mean(d.series("rhr", 30)), 0) + " bpm" + d.upTo(30) + ". A downward trend usually means improving fitness and recovery."));
        LinearLayout week = Ui.card(c);
        week.addView(Ui.caption(c, "Readiness, last 7 days"));
        week.addView(Ui.gap(c, 8));
        double[] ready = d.chartScoreSeries("readiness", 7);
        week.addView(new Charts.BarChart(c, ready, d.weekdayLabels(7), Ui.READY).highlight(d.pickedIndex(7))
                .tips(HistoryViews.tips(d, ready, new HistoryViews.Fmt() {
                    @Override
                    public String of(double v) {
                        return "readiness " + Math.round(v);
                    }
                })), Ui.sized(c, -1, 130));
        root.addView(week);
        return root;
    }

    /** Last night's body signals next to your usual values (two weeks before). */
    static View signals(Context c, DayData d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Last night's body signals"));
        card.addView(Ui.gap(c, 4));
        card.addView(signalRow(c, "Resting heart rate", Json.d(d.day, "rhr"), usual(d, "rhr"), "bpm", 0, true));
        card.addView(signalRow(c, "Heart rate variability", Json.d(d.day, "hrv"), usual(d, "hrv"), "ms", 0, false));
        card.addView(signalRow(c, "Breathing rate", Json.d(d.day, "breath"), usual(d, "breath"), "/min", 1, true));
        card.addView(signalRow(c, "Blood oxygen", Json.d(d.day, "spo2"), usual(d, "spo2"), "%", 0, false));
        double low = Json.d(d.night, "lowest_hr_time"), s0 = Json.d(d.night, "sleep_start"), s1 = Json.d(d.night, "sleep_end");
        if (!Double.isNaN(low)) {
            String when = PageParts.minute(low, d.zoneOf(d.night));
            String rel = (s1 > s0) ? ((low - s0) / (s1 - s0) <= 0.5 ? ", in the first half of the night (good)" : ", late in the night") : "";
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.body(c, "Lowest heart rate reached at " + when + rel + "."));
        }
        return card;
    }

    /** The mean of the 14 days before the shown day, whatever the charts draw (code review of #455; {@code CoreTest.dayWindow}). */
    static double usual(DayData d, String field) {
        return d.usual(field);
    }

    static View signalRow(Context c, String name, double value, double usual, String unit, int dp, boolean lowerIsBetter) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        row.addView(Ui.text(c, name, 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
        LinearLayout right = Ui.vbox(c);
        right.setGravity(Gravity.END);
        right.addView(Ui.text(c, Ui.fmt(value, dp) + " " + unit, 15, Double.isNaN(value) ? Ui.TEXT_3 : Ui.TEXT, Ui.MEDIUM));
        // the line and its colour (5 % from the usual) are core/analysis/DaySummary's (issue #293, phase 0b)
        String u = DaySummary.signalLine(value, usual, dp, Locale.getDefault());
        int tone = DaySummary.signalTone(value, usual, dp, lowerIsBetter);
        int col = tone == DaySummary.BETTER ? Ui.GOOD : tone == DaySummary.WORSE ? Ui.WARN : Ui.TEXT_3;
        right.addView(Ui.text(c, u, 11, col, Ui.REGULAR));
        row.addView(right);
        return row;
    }
}
