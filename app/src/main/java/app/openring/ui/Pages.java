package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.Scores;
import app.openring.core.analysis.ScreenTime;
import app.openring.core.analysis.StageShare;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.TS;
import app.openring.data.Json;
import app.openring.data.Prefs;

/** The six main screens, built from a {@link DayData}. */
public final class Pages {
    private Pages() {}

    public interface Nav {
        void go(int tab);

        void live();

        void sync();

        /** Show another day (e.g. a night picked from the recent nights strip). */
        void showDay(java.time.LocalDate day);

        /** Load the day again (after a setting on the page changed). */
        void reload();
    }

    public static final int TODAY = 0, SLEEP = 1, READINESS = 2, ACTIVITY = 3, BODY = 4, SCREEN = 5;

    // ======================================================================== TODAY

    public static View today(final Context c, DayData d, final Nav nav, String syncLine) {
        LinearLayout root = Ui.vbox(c);
        LinearLayout rings = Ui.card(c);
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.CENTER);
        row.addView(ringButton(c, d.score("readiness"), "Readiness", Ui.READY, nav, READINESS), Ui.weight(1));
        row.addView(ringButton(c, d.score("sleep"), "Sleep", Ui.SLEEP, nav, SLEEP), Ui.weight(1));
        row.addView(ringButton(c, d.score("activity_score"), "Activity", Ui.ACTIVE, nav, ACTIVITY), Ui.weight(1));
        double sc = d.score("screen");
        row.addView(ringButton(c, sc, "Screen", Ui.OXY, nav, SCREEN, Double.isNaN(sc) && !screenReady(c) ? "Tap to set up" : null), Ui.weight(1));
        rings.addView(row);
        root.addView(rings);

        if (d.empty()) {
            LinearLayout e = Ui.card(c);
            e.addView(Ui.h2(c, "No data for this day yet"));
            e.addView(Ui.gap(c, 6));
            e.addView(Ui.body(c, "Wear your ring and sync. Sleep appears after your first night, readiness after the morning sync, "
                    + "and baselines become personal after about two weeks. If you synced and still see nothing, the data check "
                    + "shows where it stops."));
            e.addView(Ui.gap(c, 12));
            TextView check = Ui.button(c, "Open data check", Ui.TEXT_2, false);
            check.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    c.startActivity(new android.content.Intent(c, LogActivity.class));
                }
            });
            e.addView(check, Ui.matchWrap());
            e.addView(Ui.gap(c, 12));
            TextView b = Ui.button(c, "Sync now", Ui.SLEEP, true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nav.sync();
                }
            });
            e.addView(b, Ui.matchWrap());
            root.addView(e);
        } else {
            LinearLayout digest = Ui.card(c);
            digest.addView(Ui.caption(c, "Your day"));
            digest.addView(Ui.gap(c, 6));
            digest.addView(Ui.text(c, headline(d), 20, Ui.TEXT, Ui.LIGHT));
            digest.addView(Ui.gap(c, 6));
            digest.addView(Ui.body(c, digest(d)));
            root.addView(digest);

            LinearLayout stats = Ui.card(c);
            stats.addView(Ui.caption(c, "Key metrics"));
            JSONObject n = d.night;
            stats.addView(Ui.statRow(c,
                    Ui.stat(c, "Total sleep", n == null ? "--" : Ui.hm(Json.d(n, "asleep_min")), "", Ui.SLEEP, "Time asleep last night, naps not counted"),
                    Ui.stat(c, "Resting HR", Ui.fmt(Json.d(n, "lowest_hr"), 0), "bpm", Ui.HEART, "Lowest heart rate while asleep")));
            stats.addView(Ui.statRow(c,
                    Ui.stat(c, "HRV", Ui.fmt(Json.d(n, "avg_hrv"), 0), "ms", Ui.READY, "Average while asleep, higher is calmer"),
                    // temp_dev is a difference from the usual skin temperature, not a body temperature (issue #32)
                    Ui.stat(c, "Temp vs usual", signed(Json.d(d.day, "temp_dev")), "°C", Ui.TEMP, "Skin temperature compared with your usual")));
            stats.addView(Ui.statRow(c,
                    Ui.stat(c, "Steps", Ui.fmt(Json.d(d.day, "steps"), 0), "est.", Ui.ACTIVE, "Estimated from your movement"),
                    Ui.stat(c, "Active burn", Ui.fmt(Json.d(d.day, "active_kcal"), 0), "kcal", Ui.ACTIVE, "Calories burned by moving")));
            root.addView(stats);

            JSONObject radar = d.day == null ? null : d.day.optJSONObject("radar");
            if (radar != null && radar.optBoolean("available")) {
                LinearLayout r = Ui.card(c);
                r.addView(Ui.caption(c, "Symptom radar"));
                r.addView(Ui.gap(c, 4));
                int dec = radar.optInt("decision");
                r.addView(Ui.text(c, radar.optString("status"), 18, dec == 0 ? Ui.GOOD : Ui.WARN, Ui.REGULAR));
                r.addView(Ui.body(c, dec == 0 ? "Your temperature, heart rate, HRV and breathing look like your normal."
                        : "Some of your body signals moved away from your normal. Take it easy and see Body for details."));
                root.addView(r);
            }
        }

        LinearLayout live = Ui.card(c);
        LinearLayout lr = Ui.hbox(c);
        LinearLayout lt = Ui.vbox(c);
        lt.addView(Ui.h2(c, "Live heart rate"));
        lt.addView(Ui.body(c, "Measure right now with your ring"));
        lr.addView(lt, Ui.weight(1));
        TextView lb = Ui.button(c, "Start", Ui.HEART, false);
        lb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.live();
            }
        });
        lr.addView(lb);
        live.addView(lr);
        root.addView(live);

        TextView foot = Ui.text(c, syncLine, 12, Ui.TEXT_3, Ui.REGULAR);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 16));
        root.addView(foot);
        return root;
    }

    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab) {
        return ringButton(c, v, label, color, nav, tab, null);
    }

    /** A small score ring that opens its tab; four share a row, so each takes the width it gets (at most 96 dp). */
    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab, String note) {
        Charts.ScoreRing r = new Charts.ScoreRing(c, v, label, color, false);
        LinearLayout box = Ui.vbox(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 84)));
        TextView verdict = Ui.text(c, note != null ? note : Double.isNaN(v) ? " " : Scores.verdict((int) v), 12, Ui.TEXT_2, Ui.REGULAR);
        verdict.setGravity(Gravity.CENTER);
        box.addView(verdict);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nav.go(tab);
            }
        });
        return box;
    }

    static String headline(DayData d) {
        double r = d.score("readiness");
        if (!Double.isNaN(r)) {
            if (r >= 85) return "You're well recovered. A good day to push a little.";
            if (r >= 70) return "You're in good shape. Keep a steady pace today.";
            return "Your body is asking for rest. Take it easy today.";
        }
        double s = d.score("sleep");
        if (!Double.isNaN(s)) return s >= 85 ? "You slept really well." : s >= 70 ? "You slept reasonably well." : "Last night's sleep was short or restless.";
        return "Keep wearing your ring to build your picture.";
    }

    static String digest(DayData d) {
        StringBuilder sb = new StringBuilder();
        JSONObject rd = d.scoreObj("readiness");
        if (rd != null) {
            JSONArray cs = rd.optJSONArray("contributors");
            JSONObject worst = null, best = null;
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject o = cs.optJSONObject(i);
                if (worst == null || o.optDouble("score") < worst.optDouble("score")) worst = o;
                if (best == null || o.optDouble("score") > best.optDouble("score")) best = o;
            }
            if (best != null) sb.append(best.optString("label")).append(" is your strongest contributor");
            if (worst != null && worst.optDouble("score") < 75) {
                sb.append(", while ").append(worst.optString("label").toLowerCase(Locale.getDefault())).append(" needs attention");
            }
            sb.append(". ");
            if (rd.optBoolean("provisional")) sb.append("Your baselines are still forming, so scores will become more personal over the next two weeks. ");
        }
        JSONObject debt = d.day == null ? null : d.day.optJSONObject("sleep_debt");
        if (debt != null && debt.optBoolean("valid")) {
            double m = debt.optDouble("debt_min", 0);
            if (m >= 180) sb.append("You carry about ").append(Ui.hm(m)).append(" of sleep debt. ");
        }
        if (sb.length() == 0) sb.append("Synced and analysed on this phone. Nothing left the device.");
        return sb.toString().trim();
    }

    /** The night as a list of moments: bedtime, falling asleep, longer wake ups, lowest HR, waking. */
    static View timeline(Context c, JSONObject n, java.time.ZoneId z) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Night timeline"));
        card.addView(Ui.gap(c, 6));
        int[] st = Json.stages(n);
        double s = Json.d(n, "start"), e = Json.d(n, "end");
        double ep = st.length == 0 ? 30 : (e - s) / st.length;
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {Charts.clock(s, z), "Start of the sleep window"});
        int first = -1, last = -1;
        for (int i = 0; i < st.length; i++) if (st[i] >= 1 && st[i] <= 3) {
            if (first < 0) first = i;
            last = i;
        }
        double low = Json.d(n, "lowest_hr_time");
        boolean lowDone = Double.isNaN(low);
        if (first >= 0) {
            rows.add(new String[] {Charts.clock(s + first * ep, z), "Fell asleep" + (first > 0 ? " after " + Math.round(first * ep / 60) + " min" : "")});
            int i = first;
            while (i <= last) {
                if (st[i] != 4) { i++; continue; }
                int j = i;
                while (j <= last && st[j] == 4) j++;
                double a = s + i * ep, b = s + j * ep;
                if (!lowDone && low < a) {
                    rows.add(new String[] {Charts.clock(low, z), "Lowest heart rate, " + Ui.fmt(Json.d(n, "lowest_hr"), 0) + " bpm"});
                    lowDone = true;
                }
                // short stirrings are normal; list wake ups of 5 minutes or more
                if (b - a >= 300) rows.add(new String[] {Charts.clock(a, z), "Awake for " + Math.round((b - a) / 60) + " min, back asleep " + Charts.clock(b, z)});
                i = j;
            }
            if (!lowDone) rows.add(new String[] {Charts.clock(low, z), "Lowest heart rate, " + Ui.fmt(Json.d(n, "lowest_hr"), 0) + " bpm"});
            rows.add(new String[] {Charts.clock(s + (last + 1) * ep, z), "Woke up"});
        }
        rows.add(new String[] {Charts.clock(e, z), "End of the sleep window"});
        for (String[] r : rows) {
            LinearLayout row = Ui.hbox(c);
            row.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
            TextView t = Ui.text(c, r[0], 14, Ui.SLEEP, Ui.MEDIUM);
            row.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 58), -2));
            row.addView(Ui.text(c, r[1], 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
            card.addView(row);
        }
        return card;
    }

    static String signed(double v) {
        if (Double.isNaN(v)) return "--";
        if (Math.abs(v) < 0.05) return String.format(Locale.getDefault(), "%.1f", 0.0); // not "-0.0"
        return String.format(Locale.getDefault(), "%+.1f", v);
    }

    // ======================================================================== SLEEP

    public static View sleep(Context c, DayData d, Nav nav) {
        LinearLayout root = Ui.vbox(c);
        JSONObject n = d.night;
        // each night in the zone it was slept in (issue #22)
        java.time.ZoneId nz = d.zoneOf(n);
        root.addView(hero(c, d.score("sleep"), "Sleep", Ui.SLEEP,
                n == null ? "No main sleep recorded for this day" : "Total sleep " + Ui.hm(Json.d(n, "asleep_min"))
                        + "  ·  " + Charts.clock(Json.d(n, "start"), nz) + " to " + Charts.clock(Json.d(n, "end"), nz)));
        if (!d.recent.isEmpty()) root.addView(recentNights(c, d, nav));
        if (n == null) {
            root.addView(infoCard(c, "About sleep", Explain.SLEEP));
            LinearLayout how = Ui.card(c);
            how.addView(Ui.caption(c, "How to see your sleep"));
            how.addView(Ui.gap(c, 4));
            how.addView(Ui.body(c, "Wear the ring overnight (not on the charger) and sync in the morning. You will see your sleep depth "
                    + "through the night, sleep stages, heart rate, HRV, blood oxygen and breathing."));
            root.addView(how);
            addNaps(c, root, d);
            if (!d.recent.isEmpty()) root.addView(furtherAnalysis(c, d));
            return root;
        }

        LinearLayout hyp = Ui.card(c);
        hyp.addView(Ui.caption(c, "Sleep depth through the night"));
        hyp.addView(Ui.gap(c, 10));
        double clockAt = Json.d(n, "clock_change_at");
        hyp.addView(new Charts.Hypnogram(c, Json.stages(n), Json.d(n, "start"), Json.d(n, "end")).clockChange(clockAt).zone(nz), Ui.sized(c, -1, 170));
        JSONArray mv = n.optJSONArray("motion_series");
        if (mv != null && mv.length() > 0) {
            hyp.addView(Ui.gap(c, 6));
            hyp.addView(Ui.text(c, "Movement", 11, Ui.TEXT_3, Ui.MEDIUM));
            hyp.addView(new Charts.Strip(c, Json.series(mv), Json.d(n, "start"), Json.d(n, "end"), Ui.ACTIVE), Ui.sized(c, -1, 26));
        }
        hyp.addView(Ui.gap(c, 4));
        hyp.addView(Ui.text(c, "Touch or slide along the chart to see the time and stage.", 11, Ui.TEXT_3, Ui.REGULAR));
        hyp.addView(Ui.gap(c, 8));
        hyp.addView(Ui.text(c, Explain.STAGES, 12, Ui.TEXT_2, Ui.REGULAR));
        hyp.addView(Ui.gap(c, 10));
        // REM, light and deep as shares of time asleep (adding up to 100), awake of time in bed (issue #32)
        double deepMin = Json.d(n, "deep_min"), lightMin = Json.d(n, "light_min"), remMin = Json.d(n, "rem_min"), awakeMin = Json.d(n, "awake_min");
        int[] share = StageShare.sleepShares(deepMin, lightMin, remMin);
        hyp.addView(stageRowPct(c, "Awake", awakeMin, StageShare.share(awakeMin, Json.d(n, "in_bed_min")), Ui.STAGE_AWAKE));
        hyp.addView(stageRowPct(c, "REM", remMin, share == null ? Double.NaN : share[2], Ui.STAGE_REM));
        hyp.addView(stageRowPct(c, "Light", lightMin, share == null ? Double.NaN : share[1], Ui.STAGE_LIGHT));
        hyp.addView(stageRowPct(c, "Deep", deepMin, share == null ? Double.NaN : share[0], Ui.STAGE_DEEP));
        hyp.addView(Ui.gap(c, 2));
        hyp.addView(Ui.text(c, "REM, light and deep are shares of your time asleep, so they add up to 100%. Awake is a share of your time in bed.",
                11, Ui.TEXT_3, Ui.REGULAR));
        String src = n.optString("source");
        hyp.addView(Ui.gap(c, 6));
        hyp.addView(Ui.text(c, "ring".equals(src) ? "Stages from your ring's own analysis"
                : "signals".equals(src) ? "Stages estimated on this phone from heart rate, HRV and movement"
                : "Only sleep and wake could be told apart this night", 11, Ui.TEXT_3, Ui.REGULAR));
        if (n.has("stage_detail")) {
            String level = app.openring.core.analysis.StageFilter.NAMES[app.openring.core.analysis.StageFilter.clampLevel(n.optInt("stage_detail"))];
            hyp.addView(Ui.text(c, "Stage detail: " + level + " (Settings, Sleep stage detail)", 11, Ui.TEXT_3, Ui.REGULAR));
        }
        double phone = Json.d(n, "phone_min");
        if (phone >= 1) {
            hyp.addView(Ui.gap(c, 4));
            hyp.addView(Ui.text(c, "Phone in use for " + Ui.fmt(phone, 0) + " min during this night, shown as awake (Settings, Screen time).",
                    12, Ui.OXY, Ui.REGULAR));
        }
        int clockMin = n.optInt("clock_change_min", 0);
        if (!Double.isNaN(clockAt) && clockMin != 0) {
            // daylight saving during the night (issue #22); the clock times were stored in the zone of the analysis
            hyp.addView(Ui.gap(c, 4));
            hyp.addView(Ui.text(c, app.openring.core.analysis.ClockChange.text(clockMin, n.optString("clock_change_from", ""),
                    n.optString("clock_change_to", "")), 12, Ui.ACTIVE, Ui.REGULAR));
        }
        String travel = d.travelLine();
        if (travel != null) {
            // a trip: this night is in another zone than the night before (issue #22)
            hyp.addView(Ui.gap(c, 4));
            hyp.addView(Ui.text(c, travel, 12, Ui.ACTIVE, Ui.REGULAR));
        }
        root.addView(hyp);

        root.addView(timeline(c, n, nz));
        root.addView(contributors(c, d.scoreObj("sleep"), Ui.SLEEP, "Contributors"));
        root.addView(infoCard(c, "About the sleep score", Explain.SLEEP));

        LinearLayout stats = Ui.card(c);
        stats.addView(Ui.caption(c, "Details"));
        // a short line under every number (issue #47); ranges as in Explain
        stats.addView(Ui.statRow(c, Ui.stat(c, "Time in bed", Ui.hm(Json.d(n, "in_bed_min")), "", 0, "From start to end of the sleep window"),
                Ui.stat(c, "Efficiency", Ui.fmt(Json.d(n, "efficiency"), 0), "%", 0, "Share of time in bed asleep, 85% or more is good")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Latency", Ui.fmt(Json.d(n, "latency_min"), 0), "min", 0, "Time to fall asleep, 10 to 20 min is ideal"),
                Ui.stat(c, "Wake ups", String.valueOf(n.optInt("awakenings")), "", 0, "Wake ups of a minute or more")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", Ui.fmt(Json.d(n, "lowest_hr"), 0), "bpm", 0, "Lowest heart rate while asleep"),
                Ui.stat(c, "Average HRV", Ui.fmt(Json.d(n, "avg_hrv"), 0), "ms", 0, "Average while asleep, higher is calmer")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Breathing", Ui.fmt(Json.d(n, "breath"), 1), "/min", 0, "Breaths per minute while asleep"),
                Ui.stat(c, "Blood oxygen", Ui.fmt(Json.d(n, "spo2"), 0), "%", 0, "Average while asleep")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Cycles", String.valueOf(n.optInt("cycles")), "", 0, "Rounds of light, deep and REM, about 90 min each"),
                Ui.stat(c, "Skin temp", Ui.fmt(Json.d(n, "temp"), 2), "°C", 0, "Average at night, lower than body temperature")));
        root.addView(stats);

        double s = Json.d(n, "start"), e = Json.d(n, "end");
        root.addView(chartCard(c, "Heart rate", new Charts.LineChart(c, Json.series(n.optJSONArray("hr_series")), s, e, Ui.HEART).markMin().unit(" bpm").zone(nz),
                "Lowest " + Ui.fmt(Json.d(n, "lowest_hr"), 0) + " bpm at " + (Double.isNaN(Json.d(n, "lowest_hr_time")) ? "--" : Charts.clock(Json.d(n, "lowest_hr_time"), nz))));
        root.addView(chartCard(c, "Heart rate variability", new Charts.LineChart(c, Json.series(n.optJSONArray("hrv_series")), s, e, Ui.READY).unit(" ms").zone(nz),
                "Average " + Ui.fmt(Json.d(n, "avg_hrv"), 0) + " ms, highest " + Ui.fmt(Json.d(n, "max_hrv"), 0) + " ms"));
        JSONArray sp = n.optJSONArray("spo2_series");
        if (sp != null && sp.length() > 0) {
            root.addView(chartCard(c, "Blood oxygen", new Charts.LineChart(c, Json.series(sp), s, e, Ui.OXY).range(85, 100).unit("%").zone(nz),
                    "Average " + Ui.fmt(Json.d(n, "spo2"), 1) + "%"));
        }

        JSONObject debt = d.day == null ? null : d.day.optJSONObject("sleep_debt");
        if (debt != null) {
            LinearLayout dc = Ui.card(c);
            dc.addView(Ui.caption(c, "Sleep debt"));
            dc.addView(Ui.gap(c, 4));
            double m = debt.optDouble("debt_min", 0);
            boolean valid = debt.optBoolean("valid");
            dc.addView(Ui.text(c, !valid ? "Building" : m < 180 ? "None" : m < 360 ? "Low" : m < 540 ? "Moderate" : "High", 20, Ui.TEXT, Ui.LIGHT));
            dc.addView(Ui.body(c, (valid ? Ui.hm(m) + " over the last two weeks. " : "Needs five nights of data. ")
                    + "Your sleep need is " + Ui.fmt(debt.optDouble("need_h", 8), 1) + " h."));
            root.addView(dc);
        }

        LinearLayout week = Ui.card(c);
        week.addView(Ui.caption(c, "Total sleep, last 7 days"));
        week.addView(Ui.gap(c, 8));
        double[] asleep = d.series("asleep_min", 7);
        for (int i = 0; i < asleep.length; i++) asleep[i] /= 60.0;
        week.addView(new Charts.BarChart(c, asleep, d.weekdayLabels(7), Ui.SLEEP).valueFormat("%.1f h").target(8), Ui.sized(c, -1, 130));
        root.addView(week);
        addNaps(c, root, d);
        root.addView(furtherAnalysis(c, d));
        return root;
    }

    // ---- recent nights: tap one to open it ----
    static View recentNights(Context c, DayData d, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Recent nights"));
        card.addView(Ui.gap(c, 2));
        card.addView(Ui.text(c, "Tap a night to open it. Height is time asleep.", 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 8));
        double max = 8 * 60;
        for (JSONObject o : d.recent) max = Math.max(max, Json.d(o, "asleep_min") + Math.max(0, Json.d(o, "awake_min")));
        final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.hbox(c);
        int selIndex = -1;
        String sel = d.date.toString();
        for (int i = 0; i < d.recent.size(); i++) {
            JSONObject o = d.recent.get(i);
            final String day = o.optString("day");
            boolean selected = day.equals(sel);
            if (selected) selIndex = i;
            LinearLayout col = Ui.vbox(c);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            JSONObject sc = o.optJSONObject("score");
            String score = sc != null && sc.has("value") ? String.valueOf(sc.optInt("value")) : "--";
            col.addView(Ui.text(c, score, 12, selected ? Ui.TEXT : Ui.TEXT_2, Ui.MEDIUM));
            col.addView(new Charts.NightBar(c, Json.d(o, "deep_min"), Json.d(o, "light_min"), Json.d(o, "rem_min"), Json.d(o, "awake_min"), max, selected),
                    new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 84)));
            java.time.LocalDate ld = java.time.LocalDate.parse(day);
            col.addView(Ui.text(c, ld.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), 11,
                    selected ? Ui.SLEEP : Ui.TEXT_3, Ui.MEDIUM));
            col.addView(Ui.text(c, ld.getDayOfMonth() + "." + ld.getMonthValue() + ".", 10, Ui.TEXT_3, Ui.REGULAR));
            col.setClickable(true);
            col.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nav.showDay(java.time.LocalDate.parse(day));
                }
            });
            row.addView(col, new LinearLayout.LayoutParams(Ui.dp(c, 44), -2));
        }
        hs.addView(row);
        card.addView(hs);
        // keep the selected night in view (newest at the right)
        final int target = selIndex < 0 ? d.recent.size() - 1 : selIndex;
        hs.post(new Runnable() {
            @Override
            public void run() {
                int x = Ui.dp(hs.getContext(), 44) * target - hs.getWidth() / 2 + Ui.dp(hs.getContext(), 22);
                hs.scrollTo(Math.max(0, x), 0);
            }
        });
        return card;
    }

    // ---- further analysis: averages over a period, folded away by default ----
    static boolean analysisOpen;
    static int analysisPeriod = 1; // 0 week, 1 month, 2 year, 3 all time

    static View furtherAnalysis(final Context c, final DayData d) {
        final LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.hbox(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ht = Ui.vbox(c);
        ht.addView(Ui.h2(c, "Further analysis"));
        ht.addView(Ui.text(c, "Your average night and typical values over a week, month, year or all time", 12, Ui.TEXT_3, Ui.REGULAR));
        head.addView(ht, Ui.weight(1));
        final TextView arrow = Ui.text(c, analysisOpen ? "\u25B4" : "\u25BE", 20, Ui.TEXT_2, Ui.REGULAR);
        head.addView(arrow);
        card.addView(head);
        final LinearLayout body = Ui.vbox(c);
        card.addView(body);
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                analysisOpen = !analysisOpen;
                arrow.setText(analysisOpen ? "\u25B4" : "\u25BE");
                fillAnalysis(c, d, body);
            }
        });
        fillAnalysis(c, d, body);
        return card;
    }

    static void fillAnalysis(final Context c, final DayData d, final LinearLayout body) {
        body.removeAllViews();
        if (!analysisOpen) return;
        body.addView(Ui.gap(c, 12));
        LinearLayout chips = Ui.hbox(c);
        final String[] names = {"Week", "Month", "Year", "All time"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView chip = Ui.button(c, names[i], i == analysisPeriod ? Ui.SLEEP : Ui.TEXT_3, false);
            chip.setTextSize(13);
            int ph = Ui.dp(c, 6), pv = Ui.dp(c, 7);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    analysisPeriod = k;
                    fillAnalysis(c, d, body);
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
            chips.addView(chip, lp);
        }
        body.addView(chips);
        final LinearLayout result = Ui.vbox(c);
        result.addView(Ui.gap(c, 10));
        result.addView(Ui.text(c, "Calculating", 13, Ui.TEXT_3, Ui.REGULAR));
        body.addView(result);
        final java.time.LocalDate to = d.date;
        final java.time.LocalDate from = analysisPeriod == 0 ? to.minusDays(6) : analysisPeriod == 1 ? to.minusDays(29)
                : analysisPeriod == 2 ? to.minusDays(364) : java.time.LocalDate.of(2000, 1, 1);
        final int period = analysisPeriod;
        new android.os.AsyncTask<Void, Void, app.openring.data.SleepHistory>() {
            @Override
            protected app.openring.data.SleepHistory doInBackground(Void... v) {
                return app.openring.data.SleepHistory.load(app.openring.data.Db.get(c), from.toString(), to.toString(),
                        java.time.ZoneId.systemDefault());
            }

            @Override
            protected void onPostExecute(app.openring.data.SleepHistory h) {
                if (period != analysisPeriod || !analysisOpen) return;
                showAnalysis(c, h, result, period);
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    static String clockOf(double min) {
        if (Double.isNaN(min)) return "--";
        int m = (int) Math.round(min) % 1440;
        return String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60);
    }

    static void showAnalysis(Context c, app.openring.data.SleepHistory h, LinearLayout out, int period) {
        out.removeAllViews();
        out.addView(Ui.gap(c, 10));
        if (h.nights == 0) {
            out.addView(Ui.body(c, "No nights in this period yet."));
            return;
        }
        String span = h.nights == 1 ? "1 night" : h.nights + " nights";
        if (h.firstDay != null && !h.firstDay.equals(h.lastDay)) span += ", " + h.firstDay + " to " + h.lastDay;
        out.addView(Ui.caption(c, "Average night"));
        out.addView(Ui.text(c, span, 12, Ui.TEXT_3, Ui.REGULAR));
        out.addView(Ui.gap(c, 6));
        out.addView(new Charts.Pattern(c, h.pattern), Ui.sized(c, -1, 210));
        out.addView(Ui.gap(c, 6));
        out.addView(Ui.text(c, "For each time of night, the share of nights spent in each stage. The outline shows how often you "
                + "are in bed at that time, so its rise and fall show your usual bedtime and wake time. Touch the chart to read a time.",
                12, Ui.TEXT_2, Ui.REGULAR));
        out.addView(Ui.gap(c, 12));
        out.addView(Ui.caption(c, "Typical values"));
        // the same shares as on the night: stages of time asleep, awake of time in bed (issue #32)
        int[] share = StageShare.sleepShares(h.deepMin, h.lightMin, h.remMin);
        double deepPct = share == null ? Double.NaN : share[0], lightPct = share == null ? Double.NaN : share[1],
                remPct = share == null ? Double.NaN : share[2];
        out.addView(Ui.statRow(c, Ui.stat(c, "Asleep", Ui.hm(h.asleepMin), "", Ui.SLEEP, "Average per night"),
                Ui.stat(c, "Sleep score", Ui.fmt(h.score, 0), "", Ui.SLEEP, "Average per night")));
        out.addView(Ui.statRow(c, Ui.stat(c, "Falls asleep", clockOf(h.bedtimeMin), "", 0, "Usual time you fall asleep"),
                Ui.stat(c, "Wakes up", clockOf(h.wakeMin), "", 0, "Usual time you wake up")));
        out.addView(Ui.statRow(c, Ui.stat(c, "Deep", Ui.hm(h.deepMin), "", Ui.STAGE_DEEP, StageShare.note(deepPct, "sleep", StageShare.USUAL_DEEP)),
                Ui.stat(c, "REM", Ui.hm(h.remMin), "", Ui.STAGE_REM, StageShare.note(remPct, "sleep", StageShare.USUAL_REM))));
        out.addView(Ui.statRow(c, Ui.stat(c, "Light", Ui.hm(h.lightMin), "", Ui.STAGE_LIGHT, StageShare.note(lightPct, "sleep", null)),
                Ui.stat(c, "Awake in bed", Ui.hm(h.awakeMin), "", Ui.STAGE_AWAKE, StageShare.note(StageShare.share(h.awakeMin, h.inBedMin), "time in bed", null))));
        out.addView(Ui.statRow(c, Ui.stat(c, "Efficiency", Ui.fmt(h.efficiency, 0), "%", 0, "Share of time in bed asleep"),
                Ui.stat(c, "Wake ups", Ui.fmt(h.wakeUps, 1), "per night", 0, "Wake ups of a minute or more")));
        out.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", Ui.fmt(h.lowestHr, 0), "bpm", Ui.HEART, "Average lowest heart rate"),
                Ui.stat(c, "Average HRV", Ui.fmt(h.hrv, 0), "ms", Ui.READY, "Average while asleep")));
        if (!Double.isNaN(h.bedtimeSdMin)) {
            out.addView(Ui.gap(c, 6));
            String reg = h.bedtimeSdMin < 30 ? "very regular" : h.bedtimeSdMin < 60 ? "fairly regular" : "irregular";
            out.addView(Ui.text(c, "Falling asleep varies by about " + Math.round(h.bedtimeSdMin) + " min from night to night ("
                    + reg + "). Regular sleep times help sleep quality.", 12, Ui.TEXT_2, Ui.REGULAR));
        }
        if (h.nights < 7) {
            out.addView(Ui.gap(c, 6));
            out.addView(Ui.text(c, "Averages settle after about a week of nights.", 11, Ui.TEXT_3, Ui.REGULAR));
        }
    }

    static void addNaps(Context c, LinearLayout root, DayData d) {
        if (d.naps.isEmpty()) return;
        LinearLayout nc = Ui.card(c);
        nc.addView(Ui.caption(c, "Naps and other sleep"));
        for (JSONObject o : d.naps) {
            nc.addView(Ui.gap(c, 6));
            java.time.ZoneId oz = d.zoneOf(o);
            nc.addView(Ui.text(c, Charts.clock(Json.d(o, "start"), oz) + " to " + Charts.clock(Json.d(o, "end"), oz) + "   " + Ui.hm(Json.d(o, "asleep_min")) + " asleep", 15, Ui.TEXT, Ui.REGULAR));
        }
        root.addView(nc);
    }

    static View stageRow(Context c, String name, double min, double total, int color) {
        return stageRowPct(c, name, min, 100 * min / total, color);
    }

    /** A stage row with its percentage worked out by the caller (NaN shows "--"). */
    static View stageRowPct(Context c, String name, double min, double pct, int color) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        View dot = new View(c);
        dot.setBackground(Ui.rounded(color, Ui.dp(c, 5)));
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)));
        TextView t = Ui.text(c, "  " + name, 14, Ui.TEXT, Ui.REGULAR);
        row.addView(t, Ui.weight(1));
        row.addView(Ui.text(c, Ui.hm(min) + "   ", 14, Ui.TEXT, Ui.REGULAR));
        row.addView(Ui.text(c, Double.isNaN(pct) ? "--" : Ui.fmt(pct, 0) + "%", 13, Ui.TEXT_3, Ui.REGULAR));
        return row;
    }

    // ===================================================================== READINESS

    public static View readiness(Context c, DayData d) {
        LinearLayout root = Ui.vbox(c);
        JSONObject sc = d.scoreObj("readiness");
        root.addView(hero(c, d.score("readiness"), "Readiness", Ui.READY,
                sc == null ? "Readiness appears the morning after a night with the ring on" : headline(d)));
        root.addView(infoCard(c, "What is readiness?", Explain.READINESS));
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
        root.addView(signals(c, d));
        root.addView(contributors(c, sc, Ui.READY, "Contributors"));

        LinearLayout t = Ui.card(c);
        t.addView(Ui.caption(c, "Temperature vs usual"));
        t.addView(Ui.text(c, signed(Json.d(d.day, "temp_dev")) + " \u00b0C", 26, Ui.TEXT, Ui.LIGHT));
        t.addView(Ui.body(c, "Deviation from your usual nightly skin temperature. Within about half a degree is normal."));
        t.addView(Ui.gap(c, 8));
        t.addView(new Charts.LineChart(c, daysSeries(d, "temp_dev", 30), Double.NaN, Double.NaN, Ui.TEMP).daily(d.date, 30).empty("Appears after 3 nights, it compares with your usual").range(-1.5, 1.5).reference(0).noFill(), Ui.sized(c, -1, 120));
        root.addView(t);

        root.addView(chartCard(c, "HRV, last 30 days", new Charts.LineChart(c, daysSeries(d, "hrv", 30), Double.NaN, Double.NaN, Ui.READY).daily(d.date, 30).unit(" ms"),
                "Average " + Ui.fmt(Stats.mean(d.series("hrv", 30)), 0) + " ms. Compare with yourself, not with others: HRV differs a lot between people."));
        root.addView(chartCard(c, "Resting heart rate, last 30 days", new Charts.LineChart(c, daysSeries(d, "rhr", 30), Double.NaN, Double.NaN, Ui.HEART).daily(d.date, 30).unit(" bpm"),
                "Average " + Ui.fmt(Stats.mean(d.series("rhr", 30)), 0) + " bpm. A downward trend usually means improving fitness and recovery."));
        LinearLayout week = Ui.card(c);
        week.addView(Ui.caption(c, "Readiness, last 7 days"));
        week.addView(Ui.gap(c, 8));
        week.addView(new Charts.BarChart(c, d.scoreSeries("readiness", 7), d.weekdayLabels(7), Ui.READY), Ui.sized(c, -1, 130));
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
            String when = Charts.clock(low, d.zoneOf(d.night));
            String rel = (s1 > s0) ? ((low - s0) / (s1 - s0) <= 0.5 ? ", in the first half of the night (good)" : ", late in the night") : "";
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.body(c, "Lowest heart rate reached at " + when + rel + "."));
        }
        return card;
    }

    static double usual(DayData d, String field) {
        double[] v = d.series(field, 15);
        double[] prior = java.util.Arrays.copyOf(v, 14);
        return Stats.countFinite(prior) >= 3 ? Stats.mean(prior) : Double.NaN;
    }

    static View signalRow(Context c, String name, double value, double usual, String unit, int dp, boolean lowerIsBetter) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        row.addView(Ui.text(c, name, 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
        LinearLayout right = Ui.vbox(c);
        right.setGravity(Gravity.END);
        right.addView(Ui.text(c, Ui.fmt(value, dp) + " " + unit, 15, Double.isNaN(value) ? Ui.TEXT_3 : Ui.TEXT, Ui.MEDIUM));
        String u;
        int col = Ui.TEXT_3;
        if (Double.isNaN(usual) || Double.isNaN(value)) {
            u = "usual value after a few nights";
        } else {
            double diff = value - usual;
            boolean better = lowerIsBetter ? diff < 0 : diff > 0;
            u = "usual " + Ui.fmt(usual, dp) + (Math.abs(diff) < Math.pow(10, -dp) ? "" : (diff > 0 ? ", higher" : ", lower"));
            if (Math.abs(diff) >= Math.max(Math.abs(usual) * 0.05, Math.pow(10, -dp))) col = better ? Ui.GOOD : Ui.WARN;
        }
        right.addView(Ui.text(c, u, 11, col, Ui.REGULAR));
        row.addView(right);
        return row;
    }

    static View infoCard(Context c, String title, String text) {
        LinearLayout card = Ui.card(c);
        card.setBackground(Ui.rounded(Ui.CARD_HI, Ui.dp(c, 18)));
        card.addView(Ui.caption(c, title));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.body(c, text));
        return card;
    }

    // ====================================================================== ACTIVITY

    public static View activity(Context c, DayData d) {
        LinearLayout root = Ui.vbox(c);
        JSONObject day = d.day;
        double active = Json.d(day, "active_kcal"), target = Json.d(day, "target_kcal");
        root.addView(hero(c, d.score("activity_score"), "Activity", Ui.ACTIVE,
                day == null ? "No activity data for this day" : Ui.fmt(active, 0) + " of " + Ui.fmt(target, 0) + " kcal goal"));
        if (day == null) return root;

        LinearLayout goal = Ui.card(c);
        goal.addView(Ui.caption(c, "Goal progress"));
        goal.addView(Ui.gap(c, 8));
        goal.addView(new Ui.Bar(c, target > 0 ? active / target : 0, Ui.ACTIVE), Ui.sized(c, -1, 8));
        goal.addView(Ui.statRow(c, Ui.stat(c, "Active burn", Ui.fmt(active, 0), "kcal", 0, "Calories burned by moving"),
                Ui.stat(c, "Total burn", Ui.fmt(Json.d(day, "total_kcal"), 0), "kcal", 0, "Active burn plus what your body uses at rest")));
        goal.addView(Ui.statRow(c, Ui.stat(c, "Steps", Ui.fmt(Json.d(day, "steps"), 0), "est.", 0, "Estimated from how much you moved, so \"est.\""),
                Ui.stat(c, "Distance", Ui.fmt(Json.d(day, "distance_m") / 1000.0, 1), "km", 0, "Estimated from the steps")));
        root.addView(goal);

        LinearLayout intensity = Ui.card(c);
        intensity.addView(Ui.caption(c, "Daily movement"));
        intensity.addView(Ui.gap(c, 6));
        double high = Json.d(day, "high_min"), med = Json.d(day, "medium_min"), low = Json.d(day, "low_min"), inact = Json.d(day, "inactive_min"), rest = Json.d(day, "rest_min");
        double tot = Math.max(1, high + med + low + inact + rest);
        intensity.addView(stageRow(c, "High", high, tot, 0xFFFF8A5B));
        intensity.addView(stageRow(c, "Medium", med, tot, Ui.ACTIVE));
        intensity.addView(stageRow(c, "Low", low, tot, 0xFFFFDDA8));
        intensity.addView(stageRow(c, "Inactive", inact, tot, Ui.TEXT_3));
        intensity.addView(stageRow(c, "Rest", rest, tot, Ui.STAGE_LIGHT));
        root.addView(intensity);

        JSONArray prof = day.optJSONArray("met_profile");
        if (prof != null) {
            double[] hourly = new double[24];
            String[] labels = new String[24];
            for (int h = 0; h < 24; h++) {
                double s = 0;
                int n = 0;
                for (int q = 0; q < 4; q++) {
                    double v = prof.isNull(h * 4 + q) ? Double.NaN : prof.optDouble(h * 4 + q);
                    if (!Double.isNaN(v)) {
                        s += v;
                        n++;
                    }
                }
                hourly[h] = n == 0 ? Double.NaN : s / n;
                labels[h] = h % 6 == 0 ? String.valueOf(h) : "";
            }
            LinearLayout pc = Ui.card(c);
            pc.addView(Ui.caption(c, "Intensity through the day (MET)"));
            pc.addView(Ui.gap(c, 8));
            int peak = 0;
            for (int h = 0; h < 24; h++) if (!Double.isNaN(hourly[h]) && (Double.isNaN(hourly[peak]) || hourly[h] > hourly[peak])) peak = h;
            pc.addView(new Charts.BarChart(c, hourly, labels, Ui.ACTIVE).valueFormat("%.1f").highlight(peak), Ui.sized(c, -1, 120));
            pc.addView(Ui.gap(c, 6));
            // the bands of Wellness.day (issue #47)
            pc.addView(Ui.text(c, "MET is how hard your body works, 1 is resting. Under 1.5 counts as inactive, 1.5 to 3 low, 3 to 6 medium, "
                    + "6 or more high (the bands of Daily movement).", 12, Ui.TEXT_3, Ui.REGULAR));
            root.addView(pc);
        }

        JSONObject as = day.optJSONObject("activity_score");
        if (as != null) {
            int worn = day.optInt("activity_days", 7);
            if (worn < 7) {
                LinearLayout note = Ui.card(c);
                note.addView(Ui.body(c, Explain.activityDays(worn)));
                root.addView(note);
            }
            root.addView(contributors(c, as, Ui.ACTIVE, "Contributors"));
            root.addView(infoCard(c, "About the activity score", Explain.ACTIVITY));
        }
        else {
            LinearLayout n = Ui.card(c);
            n.addView(Ui.body(c, "The activity score needs at least an hour of wear time today."));
            root.addView(n);
        }

        LinearLayout week = Ui.card(c);
        week.addView(Ui.caption(c, "Active burn, last 7 days"));
        week.addView(Ui.gap(c, 8));
        week.addView(new Charts.BarChart(c, d.series("active_kcal", 7), d.weekdayLabels(7), Ui.ACTIVE).target(target), Ui.sized(c, -1, 130));
        root.addView(week);
        return root;
    }

    // ======================================================================== SCREEN

    /** True when screen time can be read: demo mode, or Android 9 or newer with usage access. */
    static boolean screenReady(Context c) {
        return Prefs.of(c).demo() || app.openring.data.ScreenUse.supported() && app.openring.data.ScreenUse.allowed(c);
    }

    /**
     * The Screen tab: a score, the day as a clock dial, the numbers, the last 14 days as rows and the
     * apps, with a list of apps not to count (for example maps while navigating).
     */
    public static View screen(final Context c, DayData d, final Nav nav) {
        LinearLayout root = Ui.vbox(c);
        boolean demo = Prefs.of(c).demo();
        if (!demo && !app.openring.data.ScreenUse.supported()) {
            root.addView(infoCard(c, "Screen time", "Screen time needs Android 9 or newer."));
            return root;
        }
        if (!demo && !app.openring.data.ScreenUse.allowed(c)) {
            LinearLayout card = Ui.card(c);
            card.addView(Ui.h2(c, "See when you use your phone"));
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.body(c, "Open Ring can show how long and when this phone's screen was on, next to your sleep, "
                    + "and give it a score. For this, switch on Open Ring in Android's usage access list (the button takes you there). "
                    + "Like everything else it stays on this phone."));
            card.addView(Ui.gap(c, 12));
            TextView allow = Ui.button(c, "Allow usage access", Ui.OXY, true);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    app.openring.data.ScreenUse.openAccessSettings(c);
                }
            });
            card.addView(allow, Ui.matchWrap());
            card.addView(Ui.gap(c, 8));
            card.addView(Ui.text(c, "Android asks for this itself, apps cannot switch it on. You can switch it off there at any time.",
                    12, Ui.TEXT_3, Ui.REGULAR));
            root.addView(card);
            return root;
        }
        final app.openring.data.ScreenDays.Info in = d.screen;
        boolean today = d.date.equals(java.time.LocalDate.now());
        if (in == null || in.day == null) {
            root.addView(infoCard(c, "Screen time", "No screen time known for this day. Open Ring has screen time from about a week "
                    + "before you allowed usage access."));
            if (in != null) root.addView(screenRows(c, in, nav));
            return root;
        }
        int goal = in.goalMin;
        String sub = Ui.hm(in.day.totalMin) + (goal > 0 ? " of " + hours(goal) + " goal" : "") + (today ? ", so far today" : "");
        root.addView(hero(c, in.score == null ? Double.NaN : in.score.value, "Screen", Ui.OXY, sub));

        // the day as a clock
        LinearLayout dial = Ui.card(c);
        dial.addView(Ui.caption(c, "When you used the phone"));
        dial.addView(Ui.gap(c, 8));
        final java.util.Map<String, String> names = new java.util.HashMap<String, String>();
        dial.addView(new Charts.ScreenDial(c, in.dial, in.nights, in.sessions, in.dayApps, new Charts.ScreenDial.Names() {
            @Override
            public String of(String pkg) {
                String n = names.get(pkg);
                if (n == null) names.put(pkg, n = app.openring.data.ScreenUse.label(c, pkg));
                return n;
            }
        }, in.from, in.to, System.currentTimeMillis() / 1000.0, Ui.hm(in.day.totalMin), today ? "today" : "on this day")
                .shownAs(ScreenTime.widen(in.sessions, Prefs.of(c).screenMinShowMin() * 60.0)).zone(in.zone), Ui.sized(c, -1, 300));
        dial.addView(Ui.gap(c, 8));
        int minShow = Prefs.of(c).screenMinShowMin();
        dial.addView(Ui.text(c, "The day as a clock, midnight at the top. Each blue line is 5 minutes of the day with phone use, "
                + "brighter the more of them was used. Purple: your sleep. Touch the clock and slide around it to see the times; "
                + "tap the middle for the total again.", 12, Ui.TEXT_2, Ui.REGULAR));
        String change = app.openring.core.analysis.ClockChange.dialText(app.openring.core.analysis.ClockChange.find(in.zone, in.from, in.to));
        if (change != null) {
            // a day the clocks changed (issue #46): the orange arc on the clock is explained here
            dial.addView(Ui.gap(c, 4));
            dial.addView(Ui.text(c, change, 12, Ui.ACTIVE, Ui.REGULAR));
        }
        dial.addView(Ui.gap(c, 10));
        dial.addView(Ui.text(c, "Each use shows as at least", 12, Ui.TEXT_3, Ui.MEDIUM));
        dial.addView(minShowChips(c, minShow, nav));
        dial.addView(Ui.text(c, "Only for the clock and the days below, so short looks do not flicker. The times and the score always count the real minutes.",
                12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(dial);

        // the numbers
        LinearLayout num = Ui.card(c);
        num.addView(Ui.caption(c, "This day"));
        num.addView(Ui.statRow(c, Ui.stat(c, "Uses", String.valueOf(in.day.turnedOn), "times", 0),
                Ui.stat(c, "Longest", Ui.fmt(in.day.longestMin, 0), "min", 0)));
        num.addView(Ui.statRow(c, Ui.stat(c, "First look", clock(in.firstLook, in.zone), "", 0),
                Ui.stat(c, "Not counted", Ui.fmt(in.excludedMin, 0), "min", 0)));
        num.addView(Ui.statRow(c, Ui.stat(c, "Before bed", Ui.fmt(in.bedMin, 0), "min", 0),
                Ui.stat(c, "In the night", Ui.fmt(in.nightMin, 0), "min", 0)));
        num.addView(Ui.text(c, "Uses: how often you started using the phone (unlocked it, or turned the screen on if it has no lock screen). Longest: the longest use in one go. "
                + "First look: the first time after waking up. Not counted: time in apps you left out (below). "
                + "Before bed: the hour before you fell asleep last night. In the night: between falling asleep and waking up. "
                + "Phone use counts while the phone is unlocked and the screen is on; the lock screen lighting up for a "
                + "message does not count.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(num);

        // what makes the score
        if (in.score != null) {
            LinearLayout sc = Ui.card(c);
            sc.addView(Ui.caption(c, "Contributors"));
            int g = goal > 0 ? goal : app.openring.core.analysis.ScreenScore.DEFAULT_GOAL_MIN;
            sc.addView(Ui.contributor(c, "Screen time", Ui.hm(in.day.totalMin) + " of " + hours(g), in.score.amount, Ui.OXY,
                    "Half your goal or less is best, the goal itself still good, twice the goal or more lowers this to zero."));
            if (!Double.isNaN(in.score.bedtime)) {
                sc.addView(Ui.contributor(c, "Before bed", Ui.fmt(in.bedMin, 0) + " min", in.score.bedtime, Ui.OXY,
                        "Screen light and content in the last hour keep the brain awake. 5 minutes or less is best, 45 or more lowers this to zero."));
                sc.addView(Ui.contributor(c, "In the night", Ui.fmt(in.nightMin, 0) + " min", in.score.night, Ui.OXY,
                        "Checking the phone after waking up at night makes it harder to fall asleep again. 1 minute or less is best, 20 or more lowers this to zero."));
            } else {
                sc.addView(Ui.text(c, "No night known for this day, so only the screen time counts.", 12, Ui.TEXT_3, Ui.REGULAR));
            }
            root.addView(sc);
        }
        root.addView(infoCard(c, "About the screen score", Explain.SCREEN));

        root.addView(screenRows(c, in, nav));
        root.addView(screenApps(c, in, nav));
        TextView more = Ui.text(c, "Daily goal and whether phone use at night counts as awake: Settings, Screen time.", 12, Ui.TEXT_3, Ui.REGULAR);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 16));
        root.addView(more);
        return root;
    }

    /** Choice of how long each use shows at least on the clock and the day rows (display only). */
    static View minShowChips(final Context c, int current, final Nav nav) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        int[] opts = {0, 5, 10, 15};
        for (final int m : opts) {
            boolean on = m == current;
            TextView chip = Ui.button(c, m == 0 ? "Exact" : m + " min", on ? Ui.OXY : Ui.TEXT_2, on);
            chip.setTextSize(13);
            int ph = Ui.dp(c, 12), pv = Ui.dp(c, 6);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.of(c).setScreenMinShowMin(m);
                    nav.reload();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(0, 0, Ui.dp(c, 6), 0);
            row.addView(chip, lp);
        }
        return row;
    }

    /** "37m" under an hour, "1h 5m" above. */
    static String span(double min) {
        return Double.isNaN(min) || min >= 59.5 ? Ui.hm(min) : Math.round(min) + "m";
    }

    /** "07:42" for a moment, "--" when unknown. */
    static String clock(double unix, java.time.ZoneId zone) {
        if (Double.isNaN(unix)) return "--";
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond((long) unix).atZone(zone == null ? java.time.ZoneId.systemDefault() : zone);
        return String.format(Locale.getDefault(), "%02d:%02d", z.getHour(), z.getMinute());
    }

    static View screenRows(Context c, app.openring.data.ScreenDays.Info in, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Last " + in.rows.length + " days"));
        card.addView(Ui.gap(c, 8));
        card.addView(new Charts.ScreenRows(c, in.rows, in.date, new Charts.ScreenRows.Pick() {
            @Override
            public void day(java.time.LocalDate day) {
                nav.showDay(day);
            }
        }), Ui.sized(c, -1, Charts.ScreenRows.heightDp(in.rows.length)));
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, "One row per day, midnight to midnight, newest on top. Blue: phone in use. Purple: sleep. "
                + "Green dot: first look after waking up. Right: the day's screen time. Tap a day to open it.", 12, Ui.TEXT_2, Ui.REGULAR));
        return card;
    }

    static View screenApps(final Context c, app.openring.data.ScreenDays.Info in, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Apps on this day"));
        card.addView(Ui.gap(c, 4));
        java.util.Set<String> ex = Prefs.of(c).screenExcluded();
        if (!in.appsKnown) {
            card.addView(Ui.body(c, "Times per app start with version 1.15, from about a week before it was installed."));
        } else if (in.apps.isEmpty()) {
            card.addView(Ui.body(c, "No app was in front for more than half a minute."));
        } else {
            int shown = 0;
            for (java.util.Map.Entry<String, Double> e : in.apps) {
                boolean out = ex.contains(e.getKey());
                // the 8 most used, plus every app left out so it never disappears silently
                if (shown >= 8 && !out) continue;
                shown++;
                LinearLayout row = Ui.hbox(c);
                row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
                row.addView(Ui.text(c, app.openring.data.ScreenUse.label(c, e.getKey()), 14, out ? Ui.TEXT_3 : Ui.TEXT, Ui.REGULAR), Ui.weight(1));
                row.addView(Ui.text(c, (out ? "not counted, " : "") + span(e.getValue()), 14, out ? Ui.TEXT_3 : Ui.TEXT_2, Ui.REGULAR));
                card.addView(row);
            }
            if (in.noAppMin >= 1) {
                LinearLayout row = Ui.hbox(c);
                row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
                row.addView(Ui.text(c, "No app in front", 14, Ui.TEXT_2, Ui.REGULAR), Ui.weight(1));
                row.addView(Ui.text(c, span(in.noAppMin), 14, Ui.TEXT_2, Ui.REGULAR));
                card.addView(row);
            }
        }
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, "Time each app was in front while you used the phone. No app in front: uses shorter than half a minute "
                + "and screens Android does not name. Apps you leave out (for example maps while you "
                + "navigate) do not count for the total, the clock, the days or the score. Phone use at night still counts as awake "
                + "for your sleep.", 12, Ui.TEXT_2, Ui.REGULAR));
        TextView pick = Ui.smallButton(c, ex.isEmpty() ? "Choose apps not to count" : "Apps not counted: " + ex.size(), Ui.OXY);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickExcluded(c, nav);
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(pick, lp);
        return card;
    }

    /** A list of the apps seen in the last 10 days (and those already left out) with tick boxes. */
    static void pickExcluded(final Context c, final Nav nav) {
        final Prefs p = Prefs.of(c);
        final java.util.Set<String> ex = p.screenExcluded();
        final List<String> pkgs = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        app.openring.data.Db db = app.openring.data.Db.get(c);
        // per day over the days app times are known (fewer than 10 right after installing 1.15)
        double since = db.appsStart();
        double days = since < 0 ? 10 : Math.max(1, Math.min(10, (System.currentTimeMillis() / 1000.0 - since) / 86400));
        for (java.util.Map.Entry<String, Double> e : app.openring.data.ScreenDays.recentApps(db, 10)) {
            pkgs.add(e.getKey());
            labels.add(app.openring.data.ScreenUse.label(c, e.getKey()) + "  (" + span(e.getValue() / days) + " a day)");
        }
        for (String x : ex) {
            if (pkgs.contains(x)) continue;
            pkgs.add(x);
            labels.add(app.openring.data.ScreenUse.label(c, x) + "  (not used lately)");
        }
        if (pkgs.isEmpty()) {
            android.widget.Toast.makeText(c, "No apps known yet. Use the phone a little and try again.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = ex.contains(pkgs.get(i));
        new android.app.AlertDialog.Builder(c)
                .setTitle("Apps not to count")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int which, boolean on) {
                        checked[which] = on;
                    }
                })
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        List<String> out = new ArrayList<String>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) out.add(pkgs.get(i));
                        p.setScreenExcluded(out);
                        nav.reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "3h", "2h 30m" or "45m" for a goal in minutes. */
    static String hours(int min) {
        if (min < 60) return min + "m";
        return min % 60 == 0 ? (min / 60) + "h" : (min / 60) + "h " + (min % 60) + "m";
    }

    // ========================================================================== BODY

    public static View body(final Context c, DayData d, final Nav nav) {
        LinearLayout root = Ui.vbox(c);
        JSONObject day = d.day;
        root.addView(rightNow(c, d.now));
        LinearLayout hr = Ui.card(c);
        hr.addView(Ui.caption(c, "Heart rate today"));
        // the day's own hours, midnight to midnight as the analysis counted them (23 or 25 on a clock change, issue #22)
        double ds = d.date.atStartOfDay(d.zone).toEpochSecond(), de = d.date.plusDays(1).atStartOfDay(d.zone).toEpochSecond();
        TS line = Json.series(day == null ? null : day.optJSONArray("hr_day"));
        // the tiles come from the points the chart draws, also for days analysed before (issue #72)
        app.openring.core.analysis.DayHeart dh = app.openring.core.analysis.DayHeart.of(line, ds, de);
        boolean has = dh.points > 0;
        hr.addView(Ui.statRow(c, Ui.stat(c, "Lowest", Ui.fmt(dh.low, 0), "bpm", 0, has ? "at " + clock(dh.lowAt, d.zone) : null),
                Ui.stat(c, "Average", Ui.fmt(dh.avg, 0), "bpm", 0, has ? "of all quarter hours" : null),
                Ui.stat(c, "Highest", Ui.fmt(dh.high, 0), "bpm", 0, has ? "at " + clock(dh.highAt, d.zone) : null)));
        if (has) {
            hr.addView(Ui.text(c, "Each point of the line is a quarter hour average, midnight to midnight. Lowest and highest are its lowest and highest points. Touch the line to read any time.",
                    12, Ui.TEXT_3, Ui.REGULAR));
            double night = Json.d(day, "rhr");
            if (!Double.isNaN(night)) hr.addView(Ui.text(c, "Lowest in last night's sleep: " + Ui.fmt(night, 0) + " bpm (5 minute averages, as on Today and the Sleep page).",
                    12, Ui.TEXT_3, Ui.REGULAR));
        }
        hr.addView(new Charts.LineChart(c, line, ds, de, Ui.HEART).unit(" bpm").zone(d.zone), Ui.sized(c, -1, 140));
        hr.addView(Ui.gap(c, 10));
        TextView live = Ui.button(c, "Measure live heart rate and HRV", Ui.HEART, false);
        live.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.live();
            }
        });
        hr.addView(live, Ui.matchWrap());
        hr.addView(Ui.gap(c, 8));
        TextView ox = Ui.button(c, "Measure blood oxygen now", Ui.OXY, false);
        ox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new android.content.Intent(c, LiveActivity.class).putExtra("spo2", true));
            }
        });
        hr.addView(ox, Ui.matchWrap());
        root.addView(hr);

        JSONObject radar = day == null ? null : day.optJSONObject("radar");
        LinearLayout rc = Ui.card(c);
        rc.addView(Ui.caption(c, "Symptom radar"));
        if (radar == null || !radar.optBoolean("available")) {
            rc.addView(Ui.body(c, "Needs seven nights of history. It watches skin temperature, resting heart rate, HRV and breathing for changes from your normal."));
        } else {
            int dec = radar.optInt("decision");
            rc.addView(Ui.text(c, radar.optString("status"), 20, dec == 0 ? Ui.GOOD : Ui.WARN, Ui.LIGHT));
            JSONObject mk = radar.optJSONObject("markers");
            Iterator<String> it = mk == null ? null : mk.keys();
            while (it != null && it.hasNext()) {
                String k = it.next();
                JSONArray a = mk.optJSONArray(k);
                if (a == null || a.length() < 4) continue;
                rc.addView(Ui.gap(c, 8));
                LinearLayout top = Ui.hbox(c);
                top.addView(Ui.text(c, k, 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
                boolean flagged = a.optDouble(3) > 0;
                top.addView(Ui.text(c, Ui.fmt(a.optDouble(0), k.startsWith("Skin") ? 2 : 1) + (flagged ? "  unusual" : ""), 13, flagged ? Ui.WARN : Ui.TEXT_2, Ui.REGULAR));
                rc.addView(top);
                rc.addView(new Charts.RangeGauge(c, a.optDouble(0), a.optDouble(1), a.optDouble(2), flagged), Ui.sized(c, -1, 18));
            }
            rc.addView(Ui.gap(c, 6));
            rc.addView(Ui.text(c, "A wellness signal compared with your own recent nights, not a diagnosis.", 11, Ui.TEXT_3, Ui.REGULAR));
        }
        root.addView(rc);

        LinearLayout vo = Ui.card(c);
        vo.addView(Ui.caption(c, "Cardio capacity (estimate)"));
        double v = Json.d(day, "vo2max");
        if (Double.isNaN(v)) {
            Prefs p = Prefs.of(c);
            v = app.openring.core.analysis.Wellness.vo2maxJackson(p.age(), p.sex() == 1, p.weightKg());
        }
        vo.addView(Ui.text(c, Ui.fmt(v, 0) + " ml/kg/min", 24, Ui.TEXT, Ui.LIGHT));
        vo.addView(Ui.body(c, "Estimated VO2 max from your age and lowest night time heart rate (Uth method). A rough guide, not a lab test."));
        root.addView(vo);

        root.addView(infoCard(c, "Night time measurements", Explain.NIGHT_SIGNALS));
        root.addView(chartCard(c, "Resting heart rate, 30 days", new Charts.LineChart(c, daysSeries(d, "rhr", 30), Double.NaN, Double.NaN, Ui.HEART).daily(d.date, 30).unit(" bpm"), null));
        root.addView(chartCard(c, "HRV, 30 days", new Charts.LineChart(c, daysSeries(d, "hrv", 30), Double.NaN, Double.NaN, Ui.READY).daily(d.date, 30).unit(" ms"), null));
        root.addView(chartCard(c, "Breathing rate, 30 days", new Charts.LineChart(c, daysSeries(d, "breath", 30), Double.NaN, Double.NaN, Ui.OXY).daily(d.date, 30).unit("/min"), null));
        root.addView(chartCard(c, "Blood oxygen, 30 days", new Charts.LineChart(c, daysSeries(d, "spo2", 30), Double.NaN, Double.NaN, Ui.OXY).daily(d.date, 30).range(88, 100).unit("%"), null));
        root.addView(chartCard(c, "Temperature deviation, 30 days", new Charts.LineChart(c, daysSeries(d, "temp_dev", 30), Double.NaN, Double.NaN, Ui.TEMP).daily(d.date, 30).empty("Appears after 3 nights, it compares with your usual").range(-1.5, 1.5).reference(0).noFill(), null));
        return root;
    }

    // ======================================================================= shared

    /** The newest readings from the last sync, and whether the ring looks worn. */
    static View rightNow(Context c, app.openring.data.RightNow now) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Latest readings from the ring"));
        if (now == null) return card;
        String wear = now.worn == 1 ? "On your finger" : now.worn == 0 ? "Not on a finger" : "Unknown, sync to check";
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, wear, 18, now.worn == 0 ? Ui.WARN : now.worn == 1 ? Ui.GOOD : Ui.TEXT_2, Ui.LIGHT));
        if (now.worn == 0) {
            card.addView(Ui.body(c, String.format(java.util.Locale.getDefault(),
                    "The ring's sensor read %.1f \u00b0C at %s, room temperature. It only measures heart rate, temperature and blood oxygen "
                            + "while worn snugly, not on the charger or a table.", now.skinTemp, Charts.clock(now.skinTempAt))));
        }
        card.addView(Ui.statRow(c,
                Ui.stat(c, "Heart rate", Ui.fmt(now.hr, 0), "bpm", Ui.HEART),
                Ui.stat(c, "Sensor temp", Ui.fmt(now.skinTemp, 1), "\u00b0C", Ui.TEMP),
                Ui.stat(c, "Blood oxygen", Ui.fmt(now.spo2, 0), "%", Ui.OXY)));
        StringBuilder when = new StringBuilder("Heart rate is the newest reading. Sensor temp is the ring's own reading, lower than body "
                + "temperature (room temperature when not worn). Blood oxygen is the share of oxygen in your blood. ");
        if (!Double.isNaN(now.hrAt)) when.append("Heart rate at ").append(Charts.clock(now.hrAt)).append(". ");
        if (!Double.isNaN(now.skinTempAt)) when.append("Temperature at ").append(Charts.clock(now.skinTempAt)).append(". ");
        if (!Double.isNaN(now.spo2At)) when.append("Blood oxygen at ").append(Charts.clock(now.spo2At)).append(". ");
        when.append("The ring measures on its own schedule, every few minutes when you are still. Sync to update.");
        card.addView(Ui.text(c, when.toString(), 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    static TS daysSeries(DayData d, String field, int n) {
        TS ts = new TS();
        double[] v = d.series(field, n);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(v[i])) continue;
            double t = d.date.minusDays(n - 1 - i).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() + 43200;
            ts.add(t, v[i]);
        }
        return ts;
    }

    static View hero(Context c, double score, String label, int color, String sub) {
        LinearLayout card = Ui.card(c);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Charts.ScoreRing ring = new Charts.ScoreRing(c, score, label, color, true);
        card.addView(ring, new LinearLayout.LayoutParams(Ui.dp(c, 190), Ui.dp(c, 190)));
        TextView v = Ui.text(c, Double.isNaN(score) ? "" : Scores.verdict((int) score), 18, Ui.scoreColor(score, color), Ui.MEDIUM);
        v.setGravity(Gravity.CENTER);
        card.addView(v);
        TextView s = Ui.body(c, sub);
        s.setGravity(Gravity.CENTER);
        s.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), 0);
        card.addView(s);
        return card;
    }

    static View contributors(Context c, JSONObject score, int color, String title) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, title));
        JSONArray cs = score == null ? null : score.optJSONArray("contributors");
        if (cs == null || cs.length() == 0) {
            card.addView(Ui.body(c, "Not enough data yet"));
            return card;
        }
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            card.addView(Ui.contributor(c, o.optString("label"), o.optString("detail"), o.optDouble("score"), color, Explain.of(o.optString("key"))));
        }
        return card;
    }

    static View chartCard(Context c, String title, View chart, String foot) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, title));
        card.addView(Ui.gap(c, 8));
        card.addView(chart, Ui.sized(c, -1, 140));
        if (foot != null) {
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.text(c, foot, 12, Ui.TEXT_2, Ui.REGULAR));
        }
        return card;
    }
}
