package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.StageShare;
import app.openring.core.analysis.WakeUps;
import app.openring.data.Json;
import app.openring.data.Prefs;
import app.openring.ui.Pages.Nav;

/** The Sleep tab: the ring's or the watch's night, Recent nights, Further analysis (issue #293, phase 0: moved out of {@link Pages} unchanged). */
final class SleepPage {
    private SleepPage() {}

    /**
     * The night as a list of moments: bedtime, falling asleep, every wake up of a minute or more (the
     * ones Wake ups counts, issue #70), lowest HR, waking. The rows come from {@link WakeUps#rows}; {@code extra} rows
     * (null for none) are sorted in by time ({@link app.openring.core.analysis.TimelineRow#merge}).
     */
    static LinearLayout timeline(Context c, JSONObject n, java.time.ZoneId z, List<double[]> leftOut,
            List<app.openring.core.analysis.TimelineRow> extra) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Night timeline"));
        String note = n.has("stage_detail") ? WakeUps.levelNote(n.optInt("stage_detail")) : null;
        // sleep sounds (issue #380, phase 1c): their rows in yellow
        boolean sounds = app.openring.core.SoundMarks.anyYellow(extra);
        card.addView(Ui.text(c, "Every wake up of a minute or more, as counted in Wake ups." + (note == null ? "" : " " + note)
                + (sounds ? " " + app.openring.core.SoundMarks.TIMELINE_NOTE : ""), 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 6));
        int[] st = Json.stages(n);
        double s = Json.d(n, "start"), e = Json.d(n, "end");
        double ep = WakeUps.step(st, s, e);
        // the rows, with left out times next to the night (issue #254), in core so they are tested
        List<app.openring.core.analysis.TimelineRow> rows = app.openring.core.analysis.TimelineRow.merge(
                WakeUps.rows(st, s, e, Json.d(n, "lowest_hr_time"), Json.d(n, "lowest_hr"), leftOut, z, Locale.getDefault()), extra);
        for (app.openring.core.analysis.TimelineRow r : rows) {
            LinearLayout row = Ui.hbox(c);
            row.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
            boolean sound = app.openring.core.analysis.TimelineRow.SOUND.equals(r.kind) || app.openring.core.analysis.TimelineRow.LISTEN.equals(r.kind);
            TextView t = Ui.text(c, r.clock, 14, sound ? Ui.TEMP : Ui.SLEEP, Ui.MEDIUM);
            row.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 58), -2));
            row.addView(Ui.text(c, r.text, 14, sound ? Ui.TEXT_2 : Ui.TEXT, Ui.REGULAR), Ui.weight(1));
            card.addView(row);
        }
        int brief = WakeUps.shortOnes(st, ep);
        if (brief > 0) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.text(c, "Also " + brief + (brief == 1 ? " moment" : " moments")
                    + " awake under a minute, drawn on the chart but not counted as wake ups.", 11, Ui.TEXT_3, Ui.REGULAR));
        }
        return card;
    }

    /** The line under Wake ups; names the level's rule when it folds short wake ups (Calm). */
    static String wakeUpsLine(JSONObject n) {
        return "Awake " + WakeUps.rule(n.optInt("stage_detail", 0)) + ", each one is in the timeline";
    }

    /**
     * Time in bed as the Sleep page shows it: the minutes between the shown start and end times, so the window, Time in
     * bed and the stage rows agree (issues #111, #138). -1 when unknown.
     */
    static long shownInBed(JSONObject n) {
        double s = Json.d(n, "start"), e = Json.d(n, "end");
        return Double.isNaN(s) || Double.isNaN(e) ? -1 : WakeUps.shownMinutes(s, e);
    }

    static View view(Context c, DayData d, Nav nav) {
        // no ring and no watch (issue #329): one card instead of empty charts
        if (PhoneViews.needsDevice(c, d)) return DeviceViews.needDevice(c);
        // a night entered by hand: neither device has a night for this day (issue #427)
        if (d.handShown) return HandViews.sleep(c, d, nav);
        // the watch's night: the main device's, or the ring has none for this day (issue #251)
        if (d.merged != null && d.merged.watchNight != null) return watchSleep(c, d, nav, d.merged.watchNight);
        if (!d.has.ring && d.night == null) {
            // only a watch, no night from it (issue #373): what to do, no ring instructions
            LinearLayout root = Ui.vbox(c);
            root.addView(PageParts.hero(c, Double.NaN, "Sleep", Ui.SLEEP, app.openring.core.Start.NO_WATCH_NIGHT));
            // slept without the watch: enter the night by hand (issue #427)
            View offer = HandViews.offerCard(c, d, nav);
            if (offer != null) root.addView(offer);
            // the watch's nights around this day and its statistics still show (owner's report of 13 November)
            if (!d.watchRecent.isEmpty()) root.addView(recentNights(c, d, d.watchRecent, nav));
            root.addView(SoundViews.card(c, d, nav));
            root.addView(PageParts.infoCard(c, "About sleep", Explain.SLEEP_WATCH_ONLY));
            HistoryViews.sleep(c, root, d);
            if (HistoryViews.watch(d)) root.addView(furtherAnalysis(c, d));
            return root;
        }
        LinearLayout root = Ui.vbox(c);
        JSONObject n = d.night;
        // each night in the zone it was slept in (issue #22)
        java.time.ZoneId nz = d.zoneOf(n);
        // the time asleep first and big, time in bed in the line under it (issue #409); the same values as Details
        root.addView(n == null ? PageParts.hero(c, d.score("sleep"), "Sleep", Ui.SLEEP, "No main sleep recorded for this day", d.watchVersion("sleep"))
                : PageParts.hero(c, d.score("sleep"), "Sleep", Ui.SLEEP, app.openring.core.SleepHero.asleep(Json.d(n, "asleep_min"), Json.stages(n)),
                        app.openring.core.SleepHero.window(Json.d(n, "start"), Json.d(n, "end"), shownInBed(n), nz), d.watchVersion("sleep")));
        if (!d.recent.isEmpty()) root.addView(recentNights(c, d, nav));
        View left = PageParts.leftOutCard(c, d);
        if (left != null) root.addView(left);
        if (n == null) {
            // slept without the ring and the watch: enter the night by hand (issue #427)
            View offer = HandViews.offerCard(c, d, nav);
            if (offer != null) root.addView(offer);
            root.addView(SoundViews.card(c, d, nav));
            root.addView(PageParts.infoCard(c, "About sleep", Explain.SLEEP));
            LinearLayout how = Ui.card(c);
            how.addView(Ui.caption(c, "How to see your sleep"));
            how.addView(Ui.gap(c, 4));
            how.addView(Ui.body(c, "Wear the ring overnight (not on the charger) and sync in the morning. You will see your sleep depth "
                    + "through the night, sleep stages, heart rate, HRV, blood oxygen and breathing."));
            root.addView(how);
            addNaps(c, root, d);
            if (d.anyNight) root.addView(furtherAnalysis(c, d));
            return root;
        }
        // a night entered by hand for this day stays stored, the ring's is shown (issue #427)
        View replaced = HandViews.replacedCard(c, d, nav);
        if (replaced != null) root.addView(replaced);
        // what was logged for the day before this night (issue #292): "Before this night: Alcohol, Meditation."
        View life = LifeCard.night(c, d, nav);
        if (life != null) root.addView(life);

        LinearLayout hyp = Ui.card(c);
        hyp.addView(Ui.caption(c, "Sleep depth through the night"));
        hyp.addView(Ui.gap(c, 10));
        double clockAt = Json.d(n, "clock_change_at");
        // logged times of life factors that reach into the night (issue #435): bands, read by touch
        List<app.openring.core.LifeTimes.Band> nightLife = LifeCard.bands(d, Json.d(n, "start"), Json.d(n, "end"), nz);
        hyp.addView(TourOverlay.mark(new Charts.Hypnogram(c, Json.stages(n), Json.d(n, "start"), Json.d(n, "end")).clockChange(clockAt).zone(nz).life(nightLife), app.openring.core.Tour.CHART), Ui.sized(c, -1, 170));
        JSONArray mv = n.optJSONArray("motion_series");
        boolean moves = mv != null && mv.length() > 0;
        if (moves) {
            hyp.addView(Ui.gap(c, 6));
            hyp.addView(Ui.text(c, "Movement", 11, Ui.TEXT_3, Ui.MEDIUM));
            // readable by touch, the readout above its bars (code health of #380)
            hyp.addView(new Charts.Strip(c, Json.series(mv), Json.d(n, "start"), Json.d(n, "end"), Ui.ACTIVE).zone(nz), Ui.sized(c, -1, 48));
            hyp.addView(Ui.text(c, app.openring.core.NightStrip.MOVEMENT_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
        }
        // sleep sounds of the night on the same axis (issue #380, phase 1c), read by touch, each mark playable
        SoundViews.strip(c, d, hyp, Json.d(n, "start"), Json.d(n, "end"), nz, true);
        hyp.addView(Ui.gap(c, 4));
        hyp.addView(Ui.text(c, moves ? "Touch or slide along the charts to see the time, the stage and the movement."
                : "Touch or slide along the chart to see the time and stage.", 11, Ui.TEXT_3, Ui.REGULAR));
        hyp.addView(Ui.gap(c, 8));
        hyp.addView(Ui.text(c, Explain.STAGES, 12, Ui.TEXT_2, Ui.REGULAR));
        hyp.addView(Ui.gap(c, 10));
        // REM, light and deep as shares of time asleep (adding up to 100), awake of time in bed (issue #32)
        double deepMin = Json.d(n, "deep_min"), lightMin = Json.d(n, "light_min"), remMin = Json.d(n, "rem_min"), awakeMin = Json.d(n, "awake_min");
        double inBedMin = Json.d(n, "in_bed_min");
        int[] share = StageShare.sleepShares(deepMin, lightMin, remMin);
        // time in bed without stage data, so the rows add up to time in bed and a low efficiency is explained (issue #107)
        double[] none = StageShare.noData(Json.stages(n), inBedMin);
        // the minutes shown, rounded together so they add up to Total sleep and Time in bed (issue #111)
        double[] shown = StageShare.shownMinutes(deepMin, lightMin, remMin, awakeMin, none[0], Json.d(n, "asleep_min"), inBedMin,
                shownInBed(n));
        // the row only when the night really has time without stage data (review of #201)
        String noneNote = none[0] > 0 ? StageShare.noDataNote(shown[4], StageShare.shownBefore(none[0], none[1], shown[4]),
                Json.d(n, "asleep_min") > 0) : null;
        hyp.addView(PageParts.stageRowPct(c, "Awake", shown[3], StageShare.share(awakeMin, inBedMin), Ui.STAGE_AWAKE));
        hyp.addView(PageParts.stageRowPct(c, "REM", shown[2], share == null ? Double.NaN : share[2], Ui.STAGE_REM));
        hyp.addView(PageParts.stageRowPct(c, "Light", shown[1], share == null ? Double.NaN : share[1], Ui.STAGE_LIGHT));
        hyp.addView(PageParts.stageRowPct(c, "Deep", shown[0], share == null ? Double.NaN : share[0], Ui.STAGE_DEEP));
        if (noneNote != null) hyp.addView(PageParts.stageRowPct(c, "No data", shown[4], StageShare.share(none[0], inBedMin), Ui.TEXT_3));
        hyp.addView(Ui.gap(c, 2));
        hyp.addView(Ui.text(c, "REM, light and deep are shares of your time asleep, so they add up to 100%. "
                + (noneNote == null ? "Awake is a share of your time in bed." : "Awake and No data are shares of your time in bed."),
                11, Ui.TEXT_3, Ui.REGULAR));
        if (noneNote != null) hyp.addView(Ui.text(c, noneNote, 11, Ui.TEXT_3, Ui.REGULAR));
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

        LinearLayout tl = timeline(c, n, nz, d.leftOut, SoundViews.timelineRows(d, nz));
        SoundViews.timelineNote(c, tl, d);
        root.addView(tl);
        // sleep sounds of this night (issue #380): the night's marks with their clips, Listen tonight
        root.addView(SoundViews.card(c, d, nav));
        root.addView(PageParts.contributors(c, d.scoreObj("sleep"), Ui.SLEEP, "Contributors", null, n));
        root.addView(PageParts.infoCard(c, "About the sleep score", Explain.SLEEP));

        LinearLayout stats = Ui.card(c);
        stats.addView(Ui.caption(c, "Details"));
        // a short line under every number (issue #47); ranges as in Explain
        // Total sleep first, next to Time in bed (issue #409); the other tiles keep their order, two per row
        stats.addView(Ui.statRow(c, Ui.stat(c, "Total sleep", Ui.hm(Json.d(n, "asleep_min")), "", Ui.SLEEP, TOTAL_SLEEP_NOTE),
                Ui.stat(c, "Time in bed", Ui.hm(shownInBed(n) < 0 ? Double.NaN : shownInBed(n)), "", 0, "From start to end of the sleep window")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Efficiency", Ui.fmt(PageParts.nightEfficiency(n), 0), "%", 0, "Share of time in bed asleep, 85% or more is good"),
                Ui.stat(c, "Latency", Ui.fmt(Json.d(n, "latency_min"), 0), "min", 0, "Time to fall asleep, 10 to 20 min is ideal")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Wake ups", String.valueOf(n.optInt("awakenings")), "", 0, wakeUpsLine(n)),
                Ui.stat(c, "Lowest HR", Ui.fmt(Json.d(n, "lowest_hr"), 0), "bpm", 0, "Lowest heart rate while asleep")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Average HRV", Ui.fmt(Json.d(n, "avg_hrv"), 0), "ms", 0, "Average while asleep, higher is calmer"),
                Ui.stat(c, "Breathing", Ui.fmt(Json.d(n, "breath"), 1), "/min", 0, "Breaths per minute while asleep")));
        stats.addView(Ui.statRow(c, Ui.stat(c, "Blood oxygen", Ui.fmt(Json.d(n, "spo2"), 0), "%", 0, "Average while asleep"),
                Ui.stat(c, "Cycles", String.valueOf(n.optInt("cycles")), "", 0, "Rounds of light, deep and REM, about 90 min each")));
        // the last tile keeps half the width, as every other tile
        stats.addView(Ui.statRow(c, Ui.stat(c, "Skin temp", Ui.fmt(Json.d(n, "temp"), 2), "°C", 0, "Steady level at night, lower than body temperature"),
                new View(c)));
        root.addView(stats);

        double s = Json.d(n, "start"), e = Json.d(n, "end");
        root.addView(PageParts.chartCard(c, "Heart rate", new Charts.LineChart(c, Json.series(n.optJSONArray("hr_series")), s, e, Ui.HEART).markMin().unit(" bpm").zone(nz).nearestMinute()
                        .life(nightLife),
                "Lowest " + Ui.fmt(Json.d(n, "lowest_hr"), 0) + " bpm at " + (Double.isNaN(Json.d(n, "lowest_hr_time")) ? "--" : PageParts.minute(Json.d(n, "lowest_hr_time"), nz)
                        + app.openring.core.analysis.ClockChange.repeatNote(nz, WakeUps.roundMin(Json.d(n, "lowest_hr_time"))))));
        root.addView(PageParts.chartCard(c, "Heart rate variability", new Charts.LineChart(c, Json.series(n.optJSONArray("hrv_series")), s, e, Ui.READY).unit(" ms").zone(nz).nearestMinute()
                        .life(nightLife),
                "Average " + Ui.fmt(Json.d(n, "avg_hrv"), 0) + " ms, highest " + Ui.fmt(Json.d(n, "max_hrv"), 0) + " ms"));
        JSONArray sp = n.optJSONArray("spo2_series");
        if (sp != null && sp.length() > 0) {
            root.addView(PageParts.chartCard(c, "Blood oxygen", new Charts.LineChart(c, Json.series(sp), s, e, Ui.OXY).range(85, 100).unit("%").zone(nz).nearestMinute()
                            .life(nightLife),
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
        double[] asleep = d.chartSeries("asleep_min", 7);
        for (int i = 0; i < asleep.length; i++) asleep[i] /= 60.0;
        // touch reads each day, so the newer days next to a picked one read too (code review of #455)
        week.addView(new Charts.BarChart(c, asleep, d.weekdayLabels(7), Ui.SLEEP).valueFormat("%.1f h").target(8).highlight(d.pickedIndex(7))
                .tips(HistoryViews.tips(d, asleep, new HistoryViews.Fmt() {
                    @Override
                    public String of(double v) {
                        return Ui.hm(v * 60) + " asleep";
                    }
                })), Ui.sized(c, -1, 130));
        root.addView(week);
        addNaps(c, root, d);
        root.addView(furtherAnalysis(c, d));
        return root;
    }

    /**
     * The Sleep tab with Withings' own night from the watch (issue #251): its times, its stages by touch and their minutes.
     * No sleep score (the score is the ring's analysis); the line says why and, when the ring also has a night, where it is.
     */
    static View watchSleep(Context c, DayData d, Nav nav, app.openring.core.withings.WatchDays.Night wn) {
        LinearLayout root = Ui.vbox(c);
        java.time.ZoneId nz = d.zone;
        // without a ring the score from the watch's night (issue #379); with a ring its version as a line
        boolean scored = d.fromDevice("sleep");
        // the time asleep first and big, time in bed in the line under it (issue #409); the same values as Details
        root.addView(PageParts.hero(c, scored ? d.score("sleep") : Double.NaN, "Sleep", Ui.SLEEP, app.openring.core.SleepHero.asleep(wn.asleepMin(), wn.stages),
                app.openring.core.SleepHero.window(wn.start, wn.end, wn.inBedMin(), nz), scored ? app.openring.core.DeviceScores.FROM_WATCH : d.watchVersion("sleep")));
        // without a ring the watch's own nights (owner's report of 13 November); with a ring the ring's, as before
        boolean watchOnly = HistoryViews.watch(d);
        if (watchOnly) {
            if (!d.watchRecent.isEmpty()) root.addView(recentNights(c, d, d.watchRecent, nav));
        } else if (!d.recent.isEmpty()) root.addView(recentNights(c, d, nav));
        View left = PageParts.leftOutCard(c, d);
        if (left != null) root.addView(left);
        // a night entered by hand for this day stays stored, the watch's is shown (issue #427)
        View replaced = HandViews.replacedCard(c, d, nav);
        if (replaced != null) root.addView(replaced);
        View life = LifeCard.night(c, d, nav);
        if (life != null) root.addView(life);
        LinearLayout hyp = Ui.card(c);
        hyp.addView(Ui.caption(c, "Sleep depth through the night"));
        hyp.addView(Ui.gap(c, 10));
        // the watch's heart rate of the night under the stages, on the same time axis (the owner's wish of 11 November)
        app.openring.core.withings.WatchDays.NightHeart hr = d.merged.watchNightHeart;
        boolean withHr = hr != null && !hr.points.isEmpty();
        // a watch that does not tell REM from light sleep shows them as one stage (owner's report of 13 November)
        boolean lightRem = !d.watchRem.tells(wn);
        hyp.addView(TourOverlay.mark(new Charts.Hypnogram(c, wn.stages, wn.start, wn.end), app.openring.core.Tour.CHART).zone(nz).heart(withHr ? hr.points : null).lightRem(lightRem)
                        .life(LifeCard.bands(d, wn.start, wn.end, nz)),
                Ui.sized(c, -1, withHr ? 250 : 170));
        // sleep sounds of the night on the same axis (issue #380, phase 1c); the night timeline below only without a ring
        app.openring.core.DeviceHistory.NightRow row = watchOnly ? d.deviceHistory.night(d.date) : null;
        SoundViews.strip(c, d, hyp, wn.start, wn.end, nz, row != null);
        hyp.addView(Ui.gap(c, 4));
        hyp.addView(Ui.text(c, withHr ? "Touch or slide along the chart to see the time, the stage and the nearest heart rate."
                : "Touch or slide along the chart to see the time and stage.", 11, Ui.TEXT_3, Ui.REGULAR));
        if (withHr) {
            hyp.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", String.valueOf(hr.lowest), "bpm", Ui.HEART, "At " + PageParts.minute(hr.lowestAt, nz) + ", the watch's lowest reading"),
                    Ui.stat(c, "Average HR", Ui.fmt(hr.average, 0), "bpm", Ui.HEART, "Of its " + hr.points.size() + " readings this night")));
            hyp.addView(Ui.text(c, Explain.WATCH_NIGHT_HEART, 11, Ui.TEXT_3, Ui.REGULAR));
        }
        hyp.addView(Ui.gap(c, 8));
        // awake and no record are shares of the whole night, the stages of the time asleep, as on the ring's night
        double inBed = wn.inBedMin();
        int[] share = StageShare.sleepShares(wn.deepMin, wn.lightMin, wn.remMin);
        hyp.addView(PageParts.stageRowPct(c, "Awake", wn.awakeMin, StageShare.share(wn.awakeMin, inBed), Ui.STAGE_AWAKE));
        if (lightRem) {
            // one row: light and REM together, its share the two shares added (so the rows still add up to 100%)
            hyp.addView(PageParts.stageRowPct(c, "Light and REM", wn.lightMin + wn.remMin, share == null ? Double.NaN : share[1] + share[2], Ui.STAGE_LIGHT));
        } else {
            hyp.addView(PageParts.stageRowPct(c, "REM", wn.remMin, share == null ? Double.NaN : share[2], Ui.STAGE_REM));
            hyp.addView(PageParts.stageRowPct(c, "Light", wn.lightMin, share == null ? Double.NaN : share[1], Ui.STAGE_LIGHT));
        }
        hyp.addView(PageParts.stageRowPct(c, "Deep", wn.deepMin, share == null ? Double.NaN : share[0], Ui.STAGE_DEEP));
        if (wn.otherMin > 0) hyp.addView(PageParts.stageRowPct(c, "No data", wn.otherMin, StageShare.share(wn.otherMin, inBed), Ui.TEXT_3));
        hyp.addView(Ui.gap(c, 2));
        hyp.addView(Ui.text(c, (lightRem ? "Light and REM, and deep are shares of your time asleep, so they add up to 100%. "
                : "REM, light and deep are shares of your time asleep, so they add up to 100%. ")
                + (wn.otherMin > 0 ? "Awake and No data are shares of the whole night. " + Explain.WATCH_NO_RECORD : "Awake is a share of the whole night."),
                11, Ui.TEXT_3, Ui.REGULAR));
        if (lightRem) hyp.addView(Ui.text(c, Explain.LIGHT_REM, 11, Ui.TEXT_3, Ui.REGULAR));
        hyp.addView(Ui.gap(c, 6));
        String from = app.openring.core.MainDevice.nightLine(d.merged);
        if (from != null) hyp.addView(Ui.text(c, from, 12, Ui.OXY, Ui.REGULAR));
        hyp.addView(Ui.text(c, scored ? Explain.WATCH_NIGHT_SCORE : Explain.MAIN_WATCH_NIGHT, 11, Ui.TEXT_3, Ui.REGULAR));
        String too = app.openring.core.MainDevice.ringTooLine(d.merged, d.score("sleep"));
        if (too != null) {
            hyp.addView(Ui.gap(c, 4));
            hyp.addView(Ui.text(c, too, 12, Ui.TEXT_2, Ui.REGULAR));
        }
        root.addView(hyp);
        // the night's timeline from the watch's stages, as the ring's (each wake up of a minute or more)
        if (row != null) {
            LinearLayout tl = timeline(c, app.openring.data.WatchHistory.nightJson(row), nz, new ArrayList<double[]>(), SoundViews.timelineRows(d, nz));
            SoundViews.timelineNote(c, tl, d);
            root.addView(tl);
        }
        root.addView(SoundViews.card(c, d, nav));
        if (scored) {
            DeviceViews.deviceParts(c, root, d, "sleep", Ui.SLEEP);
            root.addView(PageParts.infoCard(c, "About the sleep score", Explain.SLEEP_DEVICE));
        }
        // details, sleep debt, the 7 and 30 day charts and what needs a ring, without a ring only
        HistoryViews.sleep(c, root, d);
        addNaps(c, root, d);
        if (d.anyNight || watchOnly) root.addView(furtherAnalysis(c, d));
        return root;
    }

    // ---- recent nights: tap one to open it ----
    static View recentNights(Context c, DayData d, final Nav nav) {
        return recentNights(c, d, d.recent, nav);
    }

    /** The strip of {@code nights} (the ring's, or the watch's without a ring), the shown day's night selected. */
    static View recentNights(Context c, DayData d, List<JSONObject> nights, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Recent nights"));
        card.addView(Ui.gap(c, 2));
        card.addView(Ui.text(c, "Tap a night to open it. Height is time asleep.", 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 8));
        double max = 8 * 60;
        for (JSONObject o : nights) max = Math.max(max, Json.d(o, "asleep_min") + Math.max(0, Json.d(o, "awake_min")));
        final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.hbox(c);
        int selIndex = -1, upTo = 0;
        String sel = d.date.toString();
        for (int i = 0; i < nights.size(); i++) {
            JSONObject o = nights.get(i);
            final String day = o.optString("day");
            boolean selected = day.equals(sel);
            if (selected) selIndex = i;
            if (day.compareTo(sel) <= 0) upTo = i;
            LinearLayout col = Ui.vbox(c);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            JSONObject sc = o.optJSONObject("score");
            String score = sc != null && sc.has("value") ? String.valueOf(sc.optInt("value")) : "--";
            col.addView(Ui.text(c, score, 12, selected ? Ui.TEXT : Ui.TEXT_2, Ui.MEDIUM));
            // a night by hand has no stages: its time asleep as one block (issue #427)
            boolean hand = app.openring.data.HandNights.isHand(o);
            Charts.NightBar bar = new Charts.NightBar(c, hand ? 0 : Json.d(o, "deep_min"), hand ? Json.d(o, "asleep_min") : Json.d(o, "light_min"),
                    hand ? 0 : Json.d(o, "rem_min"), Json.d(o, "awake_min"), max, selected);
            if (hand) {
                // one neutral block, never a stage colour, and said as by hand
                bar.byHand();
                col.setContentDescription(app.openring.core.HandNight.STRIP + ", " + Ui.hm(Json.d(o, "asleep_min")) + " asleep");
            }
            col.addView(bar,
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
        // keep the selected night in view (newest at the right); a day without one shows the newest night up to
        // it, as the strip also holds the nights after an older date (issue #98)
        final int target = selIndex < 0 ? upTo : selIndex;
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
    static int analysisPeriod = 1; // 0 week, 1 30 days, 2 year, 3 all time

    /** Further analysis' tile key on Sleep (its fold is the tile's, owner's wish of 18 November). */
    static final String ANALYSIS_KEY = "further analysis";

    /**
     * Further analysis: averages over a period, folded by default. Its header is the tile's (ui/Tiles), so it folds, moves and
     * stays open or folded as every other tile; it calculates only while open.
     */
    static View furtherAnalysis(final Context c, final DayData d) {
        final LinearLayout card = Ui.card(c);
        final LinearLayout body = Ui.vbox(c);
        card.addView(body);
        analysisOpen = Tiles.open(c, Pages.SLEEP, ANALYSIS_KEY, false);
        Tiles.Spec s = Tiles.spec(card);
        s.key = ANALYSIS_KEY;
        s.title = "Further analysis";
        s.summary = "Your average night and typical values over the last week, 30 days, year or all time";
        s.standardOpen = false;
        s.listener = new Tiles.Listener() {
            @Override
            public void folded(boolean open) {
                analysisOpen = open;
                fillAnalysis(c, d, body);
            }
        };
        fillAnalysis(c, d, body, true);
        // calendar months over the whole history are Trends' (issue #374); the chips here stay rolling windows
        card.addView(TrendsCard.link(c, d));
        return card;
    }

    /**
     * Height in pixels of the last analysis result shown, per screen layout. A freshly built card
     * (another day, a sync) reserves it while it calculates, so the page keeps its length and the kept
     * scroll position still fits (issues #6 and #93). A height from before turning the phone or
     * changing the font size is not used (issue #127).
     */
    static final app.openring.core.KeptHeight lastResultHeight = new app.openring.core.KeptHeight();

    /** The screen layout of {@code c}, the key for {@link #lastResultHeight}. */
    static String layoutKey(Context c) {
        android.content.res.Configuration cfg = c.getResources().getConfiguration();
        return app.openring.core.KeptHeight.layout(cfg.screenWidthDp, cfg.fontScale, cfg.densityDpi);
    }
    /** Tag of the analysis result area until its first result is shown. */
    private static final Object CALCULATING = new Object();

    static void fillAnalysis(final Context c, final DayData d, final LinearLayout body) {
        fillAnalysis(c, d, body, false);
    }

    /**
     * {@code reserve}: the card is rebuilt with the page (another day, a sync), so keep the height of
     * the last result while calculating; the page then keeps its length and its scroll position. Not
     * when the person opens the card with its header, where nothing below would move.
     */
    static void fillAnalysis(final Context c, final DayData d, final LinearLayout body, boolean reserve) {
        body.removeAllViews();
        if (!analysisOpen) return;
        body.addView(Ui.gap(c, 12));
        LinearLayout chips = Ui.hbox(c);
        // "30 days", not "Month": Trends' months are calendar months (issue #374), one word never means two things
        final String[] names = {"Week", "30 days", "Year", "All time"};
        final TextView[] chip = new TextView[names.length];
        final LinearLayout result = Ui.vbox(c);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            chip[i] = Ui.button(c, names[i], i == analysisPeriod ? Ui.SLEEP : Ui.TEXT_3, false);
            chip[i].setTextSize(13);
            int ph = Ui.dp(c, 6), pv = Ui.dp(c, 7);
            chip[i].setPadding(ph, pv, ph, pv);
            chip[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (k == analysisPeriod) return;
                    analysisPeriod = k;
                    // only the result changes (issue #93): the chips stay, the old result stays faded
                    // in place until the new one is ready, so the page never gets shorter and the
                    // screen does not jump
                    for (int j = 0; j < chip.length; j++) Ui.outline(c, chip[j], j == k ? Ui.SLEEP : Ui.TEXT_3);
                    result.setAlpha(0.4f);
                    // the faded old chart must not answer touch: its values belong to the old period
                    setTouchable(result, false);
                    loadAnalysis(c, d, result);
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
            chips.addView(chip[i], lp);
        }
        body.addView(chips);
        result.addView(Ui.gap(c, 10));
        result.addView(Ui.text(c, "Calculating", 13, Ui.TEXT_3, Ui.REGULAR));
        if (reserve) result.setMinimumHeight(lastResultHeight.get(layoutKey(c)));
        result.setTag(CALCULATING);
        result.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                // remember real results only, not the "Calculating" line or the space kept for it
                if (v.getTag() == null) lastResultHeight.remember(layoutKey(v.getContext()), bottom - top);
            }
        });
        body.addView(result);
        loadAnalysis(c, d, result);
    }

    /** Enables or disables a view and everything inside it (charts ignore touch while disabled). */
    static void setTouchable(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setTouchable(g.getChildAt(i), on);
        }
    }

    /**
     * The averages are worked out one load at a time (issue #196): a year or all time reads every night of it, and
     * stepping through days used to start one more load each step, all at once.
     */
    private static final java.util.concurrent.Executor ANALYSIS_LOADS = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Counts the loads asked for; only the newest one is worked out and shown, older ones still waiting are skipped. */
    private static final java.util.concurrent.atomic.AtomicInteger analysisLoad = new java.util.concurrent.atomic.AtomicInteger();

    /** Loads the averages for the selected period in the background and shows them in {@code result}. */
    static void loadAnalysis(final Context c, final DayData d, final LinearLayout result) {
        // the period ends on the shown day, as on dev: these are averages, and a display window never changes a number (review of #455)
        final java.time.LocalDate to = d.date;
        final java.time.LocalDate from = analysisPeriod == 0 ? to.minusDays(6) : analysisPeriod == 1 ? to.minusDays(29)
                : analysisPeriod == 2 ? to.minusDays(364) : java.time.LocalDate.of(2000, 1, 1);
        final int period = analysisPeriod;
        final int load = analysisLoad.incrementAndGet();
        // without a ring the watch's nights (owner's report of 13 November), worked out from its rows
        final boolean watch = HistoryViews.watch(d);
        final app.openring.core.withings.WatchDays.Rem rem = d.watchRem;
        final java.time.LocalDate shown = d.date;
        final java.time.ZoneId shownZone = d.zone;
        final int age = Prefs.of(c).age();
        new android.os.AsyncTask<Void, Void, app.openring.data.SleepHistory>() {
            @Override
            protected app.openring.data.SleepHistory doInBackground(Void... v) {
                // a newer load was asked for while this one waited (another day, tab or period): its card is gone
                if (load != analysisLoad.get()) return null;
                if (watch) {
                    // each night in its day's zone, as its dial and the charts (code review of #389), the shown day in the dial's
                    app.openring.data.Db db = app.openring.data.Db.get(c);
                    final app.openring.core.DeviceHistory.DayZones noted = app.openring.data.WatchHistory.dayZones(db, java.time.ZoneId.systemDefault());
                    app.openring.core.DeviceHistory.DayZones zones = new app.openring.core.DeviceHistory.DayZones() {
                        @Override
                        public java.time.ZoneId of(java.time.LocalDate day) {
                            return day.equals(shown) ? shownZone : noted.of(day);
                        }
                    };
                    return app.openring.data.WatchHistory.load(db, from.toString(), to.toString(), zones, java.time.ZoneId.systemDefault(), age, rem);
                }
                return app.openring.data.SleepHistory.load(app.openring.data.Db.get(c), from.toString(), to.toString(),
                        java.time.ZoneId.systemDefault());
            }

            @Override
            protected void onPostExecute(app.openring.data.SleepHistory h) {
                // a later tap wins; its own result replaces this one
                if (h == null || load != analysisLoad.get() || period != analysisPeriod || !analysisOpen) return;
                // a screen gone with a turn of the phone shows nothing; the new one loads its own
                if (c instanceof android.app.Activity && ((android.app.Activity) c).isDestroyed()) return;
                // the old views go and the new ones come in the same step, before the next layout
                showAnalysis(c, h, result, period, watch, watch && h.lightRem);
                result.setAlpha(1f);
                setTouchable(result, true);
                result.setMinimumHeight(0);
                result.setTag(null);
            }
        }.executeOnExecutor(ANALYSIS_LOADS);
    }

    static String clockOf(double min) {
        if (Double.isNaN(min)) return "--";
        int m = (int) Math.round(min) % 1440;
        return String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60);
    }

    static void showAnalysis(Context c, app.openring.data.SleepHistory h, LinearLayout out, int period) {
        showAnalysis(c, h, out, period, false, false);
    }

    /**
     * As above; {@code watch}: the watch's nights without a ring, so no HRV row and a line that says where they come from;
     * {@code lightRem}: a watch that does not tell REM from light sleep, so "Light and REM" is one stage (only watch nights here).
     */
    static void showAnalysis(Context c, app.openring.data.SleepHistory h, LinearLayout out, int period, boolean watch, boolean lightRem) {
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
        out.addView(new Charts.Pattern(c, h.pattern).lightRem(lightRem), Ui.sized(c, -1, 210));
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
        // Deep, REM and Light rounded together so they add up to Asleep (issue #111); no time in bed is shown here
        double[] shown = StageShare.shownMinutes(h.deepMin, h.lightMin, h.remMin, h.awakeMin, 0, h.asleepMin, h.inBedMin, -1);
        out.addView(Ui.statRow(c, Ui.stat(c, "Asleep", Ui.hm(h.asleepMin), "", Ui.SLEEP, "Average per night"),
                Ui.stat(c, "Sleep score", Ui.fmt(h.score, 0), "", Ui.SLEEP, "Average per night")));
        out.addView(Ui.statRow(c, Ui.stat(c, "Falls asleep", clockOf(h.bedtimeMin), "", 0, "Usual time you fall asleep"),
                Ui.stat(c, "Wakes up", clockOf(h.wakeMin), "", 0, "Usual time you wake up")));
        if (lightRem) {
            // no REM row and no REM note: the watch cannot tell it from light sleep (owner's report of 13 November)
            double lr = (Double.isNaN(shown[1]) ? 0 : shown[1]) + (Double.isNaN(shown[2]) ? 0 : shown[2]);
            double lrPct = Double.isNaN(lightPct) ? Double.NaN : lightPct + (Double.isNaN(remPct) ? 0 : remPct);
            out.addView(Ui.statRow(c, Ui.stat(c, "Deep", Ui.hm(shown[0]), "", Ui.STAGE_DEEP, StageShare.note(deepPct, "sleep", StageShare.USUAL_DEEP)),
                    Ui.stat(c, "Light and REM", Ui.hm(lr), "", Ui.STAGE_LIGHT, StageShare.note(lrPct, "sleep", null))));
            out.addView(Ui.statRow(c, Ui.stat(c, "Awake in bed", Ui.hm(h.awakeMin), "", Ui.STAGE_AWAKE,
                    StageShare.note(StageShare.share(h.awakeMin, h.inBedMin), "time in bed", null))));
        } else {
            out.addView(Ui.statRow(c, Ui.stat(c, "Deep", Ui.hm(shown[0]), "", Ui.STAGE_DEEP, StageShare.note(deepPct, "sleep", StageShare.USUAL_DEEP)),
                    Ui.stat(c, "REM", Ui.hm(shown[2]), "", Ui.STAGE_REM, StageShare.note(remPct, "sleep", StageShare.USUAL_REM))));
            out.addView(Ui.statRow(c, Ui.stat(c, "Light", Ui.hm(shown[1]), "", Ui.STAGE_LIGHT, StageShare.note(lightPct, "sleep", null)),
                    Ui.stat(c, "Awake in bed", Ui.hm(h.awakeMin), "", Ui.STAGE_AWAKE, StageShare.note(StageShare.share(h.awakeMin, h.inBedMin), "time in bed", null))));
        }
        out.addView(Ui.statRow(c, Ui.stat(c, "Efficiency", Ui.fmt(h.efficiency, 0), "%", 0, "Share of time in bed asleep"),
                Ui.stat(c, "Wake ups", Ui.fmt(h.wakeUps, 1), "per night", 0,
                        WakeUps.averageLine(h.stageDetail))));
        if (watch) {
            out.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", Ui.fmt(h.lowestHr, 0), "bpm", Ui.HEART, "Average of the watch's lowest reading each night")));
        } else {
            out.addView(Ui.statRow(c, Ui.stat(c, "Lowest HR", Ui.fmt(h.lowestHr, 0), "bpm", Ui.HEART, "Average lowest heart rate"),
                    Ui.stat(c, "Average HRV", Ui.fmt(h.hrv, 0), "ms", Ui.READY, "Average while asleep")));
        }
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
        if (watch) {
            out.addView(Ui.gap(c, 6));
            out.addView(Ui.text(c, Explain.WATCH_ANALYSIS, 11, Ui.TEXT_3, Ui.REGULAR));
            if (lightRem) out.addView(Ui.text(c, Explain.LIGHT_REM, 11, Ui.TEXT_3, Ui.REGULAR));
        }
    }

    static void addNaps(Context c, LinearLayout root, DayData d) {
        if (d.naps.isEmpty()) return;
        LinearLayout nc = Ui.card(c);
        nc.addView(Ui.caption(c, "Naps and other sleep"));
        for (JSONObject o : d.naps) {
            nc.addView(Ui.gap(c, 6));
            java.time.ZoneId oz = d.zoneOf(o);
            nc.addView(Ui.text(c, PageParts.minute(Json.d(o, "start"), oz) + " to " + PageParts.minute(Json.d(o, "end"), oz) + "   " + Ui.hm(Json.d(o, "asleep_min")) + " asleep", 15, Ui.TEXT, Ui.REGULAR));
        }
        root.addView(nc);
    }

    /** Under the time asleep (issue #409): what Details says about Total sleep. */
    static final String TOTAL_SLEEP_NOTE = "Time asleep, without the time awake in bed";
}
