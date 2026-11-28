package app.openring.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import app.openring.core.Texts;
import app.openring.core.analysis.TS;
import app.openring.data.Json;
import app.openring.data.Prefs;
import app.openring.ui.Pages.Nav;

/** The Body tab (issue #293, phase 0: moved out of {@link Pages} unchanged). */
final class BodyPage {
    private BodyPage() {}

    static View view(final Context c, DayData d, final Nav nav) {
        LinearLayout root = Ui.vbox(c);
        JSONObject day = d.day;
        // the period tracker first (issue #252), when the profile says female, the Settings switch says so, or in demo mode
        if (d.periodShown) root.addView(PeriodCard.body(c, d, nav));
        // without a ring (issue #373): no ring readings, live buttons or night charts, one card instead; with neither a ring
        // nor a watch no heart rate either
        boolean ring = d.has.ring;
        if (app.openring.core.Start.shows(Pages.BODY, app.openring.core.Start.HEART_NEEDS_DEVICE, d.has)) {
            root.addView(DeviceViews.needCard(c, null, app.openring.core.Start.HEART_NEEDS, true));
            root.addView(DeviceViews.needCard(c, app.openring.core.Start.NIGHT_TITLE, app.openring.core.Start.NIGHT_TEXT, false));
            if (d.periodShown && d.periods != null) {
                root.addView(PeriodAnalysis.cycles(c, d));
                if (!d.periods.periods.isEmpty()) root.addView(PeriodAnalysis.accuracy(c, d));
            }
            root.addView(LifeCard.body(c, d));
            return root;
        }
        if (ring) root.addView(rightNow(c, d.now));
        LinearLayout hr = Ui.card(c);
        hr.addView(Ui.caption(c, "Heart rate today"));
        // the day's own hours, midnight to midnight as the analysis counted them (23 or 25 on a clock change, issue #22)
        double ds = d.date.atStartOfDay(d.zone).toEpochSecond(), de = d.date.plusDays(1).atStartOfDay(d.zone).toEpochSecond();
        // the main device's line, with the other one's where it was not worn (issue #251); with only the ring its own
        TS line = d.merged != null ? d.merged.hr : Json.series(day == null ? null : day.optJSONArray("hr_day"));
        // the tiles come from the points the chart draws, also for days analysed before (issue #72)
        app.openring.core.analysis.DayHeart dh = app.openring.core.analysis.DayHeart.of(line, ds, de);
        boolean has = dh.points > 0;
        // in the hour that happens twice when the clocks go back, "(first time)" or "(second time)" (issue #97)
        hr.addView(Ui.statRow(c, Ui.stat(c, "Lowest", Ui.fmt(dh.low, 0), "bpm", 0, has ? "at " + PageParts.clock(dh.lowAt, d.zone)
                        + app.openring.core.analysis.ClockChange.repeatNote(d.zone, dh.lowAt) : null),
                Ui.stat(c, "Average", Ui.fmt(dh.avg, 0), "bpm", 0, has ? "of all quarter hours" : null),
                Ui.stat(c, "Highest", Ui.fmt(dh.high, 0), "bpm", 0, has ? "at " + PageParts.clock(dh.highAt, d.zone)
                        + app.openring.core.analysis.ClockChange.repeatNote(d.zone, dh.highAt) : null)));
        if (has) {
            hr.addView(Ui.text(c, "Each point of the line is a quarter hour average, midnight to midnight. Lowest and highest are its lowest and highest points. Touch the line to read any time.",
                    12, Ui.TEXT_3, Ui.REGULAR));
            String twice = app.openring.core.analysis.ClockChange.repeatText(app.openring.core.analysis.ClockChange.onDay(d.zone, ds, de));
            if (twice != null) hr.addView(Ui.text(c, twice, 12, Ui.TEXT_3, Ui.REGULAR));
            double night = Json.d(day, "rhr");
            if (!Double.isNaN(night)) hr.addView(Ui.text(c, "Lowest in last night's sleep: " + Ui.fmt(night, 0) + " bpm (Resting HR on Today, from the ring's own 5 minute night readings, so it can differ by a beat or two from the line).",
                    12, Ui.TEXT_3, Ui.REGULAR));
        }
        // the middle label at noon, also when the day has 23 or 25 hours
        double noon = d.date.atTime(12, 0).atZone(d.zone).toEpochSecond();
        // a time left out because someone else wore the ring: a grey band, readable by touch (issue #254)
        // logged times of life factors (issue #435): a band with its name, read by touch, and under the chart what the line
        // measured during each, next to the day's average (measured values only, never a cause)
        List<app.openring.core.LifeTimes.Band> dayLife = LifeCard.bands(d, ds, de);
        Charts.LineChart dayChart = new Charts.LineChart(c, line, ds, de, Ui.HEART).unit(" bpm").zone(d.zone).midLabel(noon).leftOut(d.leftOutPieces()).life(dayLife);
        dayChart.setContentDescription(dayLife.isEmpty() ? "Heart rate of the day, touch to read it" : "Heart rate of the day with your logged times, touch to read them");
        hr.addView(dayChart, Ui.sized(c, -1, 140));
        View during = LifeCard.during(c, d, line, dayLife, dh.avg);
        if (during != null) hr.addView(during);
        String fromOther = d.merged == null ? null : app.openring.core.MainDevice.heartLine(d.merged, d.zone);
        if (fromOther != null) {
            hr.addView(Ui.gap(c, 4));
            hr.addView(Ui.text(c, fromOther, 12, Ui.OXY, Ui.REGULAR));
            hr.addView(Ui.text(c, Explain.MAIN_FILLED, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        if (!ring) {
            root.addView(hr);
            // resting and day heart rate over 30 days and cardio capacity from the watch (owner's report of 13 November)
            HistoryViews.body(c, root, d, Prefs.of(c).age());
            root.addView(DeviceViews.needCard(c, app.openring.core.Start.NIGHT_TITLE,
                    HistoryViews.watch(d) ? Explain.BODY_NEEDS_RING : app.openring.core.Start.NIGHT_TEXT, false));
            if (d.periodShown && d.periods != null) {
                // without a ring this is the only temperature chart (drip's mouth temperatures), so it stays next to the cycles
                root.addView(PeriodAnalysis.cycles(c, d));
                if (!d.periods.periods.isEmpty()) root.addView(PeriodAnalysis.accuracy(c, d));
                if (!d.periods.periods.isEmpty()) root.addView(PeriodAnalysis.temperature(c, d));
            }
            return root;
        }
        hr.addView(Ui.gap(c, 10));
        TextView live = Ui.button(c, "Measure live heart rate and HRV", Ui.HEART, false);
        live.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.live(false);
            }
        });
        hr.addView(live, Ui.matchWrap());
        hr.addView(Ui.gap(c, 8));
        TextView ox = Ui.button(c, "Measure blood oxygen now", Ui.OXY, false);
        ox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // the same way as the heart rate button, so demo mode never reaches the ring (issue #185)
                nav.live(true);
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
                top.addView(Ui.text(c, Texts.radar(k, a.optDouble(0), Json.d(day, "temp_dev"), Locale.getDefault()) + (flagged ? "  unusual" : ""), 13, flagged ? Ui.WARN : Ui.TEXT_2, Ui.REGULAR));
                rc.addView(top);
                rc.addView(new Charts.RangeGauge(c, a.optDouble(0), a.optDouble(1), a.optDouble(2), flagged), Ui.sized(c, -1, 18));
            }
            rc.addView(Ui.gap(c, 6));
            rc.addView(Ui.text(c, "That night's values; skin temperature is the change from your usual. The bar under each is your normal range. "
                    + "A wellness signal compared with your own recent nights, not a diagnosis.", 11, Ui.TEXT_3, Ui.REGULAR));
        }
        root.addView(rc);

        LinearLayout vo = Ui.card(c);
        vo.addView(Ui.caption(c, "Cardio capacity (estimate)"));
        // only the heart rate estimate the text names, and only a value a person can have (issue #116)
        double v = Json.d(day, "vo2max"), shown = app.openring.core.analysis.Wellness.vo2maxShown(v);
        if (!Double.isNaN(shown)) vo.addView(Ui.text(c, Ui.fmt(shown, 0) + " ml/kg/min", 24, Ui.TEXT, Ui.LIGHT));
        vo.addView(Ui.body(c, Explain.vo2max(v, d.night != null)));
        vo.addView(Ui.gap(c, 4));
        vo.addView(Ui.text(c, Explain.VO2MAX, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(vo);

        root.addView(PageParts.infoCard(c, "Night time measurements", Explain.NIGHT_SIGNALS));
        root.addView(PageParts.chartCard(c, "Resting heart rate, 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "rhr", 30), Double.NaN, Double.NaN, Ui.HEART).daily(d.end(30), 30).pick(d.date).unit(" bpm"), null));
        root.addView(PageParts.chartCard(c, "HRV, 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "hrv", 30), Double.NaN, Double.NaN, Ui.READY).daily(d.end(30), 30).pick(d.date).unit(" ms"), null));
        root.addView(PageParts.chartCard(c, "Breathing rate, 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "breath", 30), Double.NaN, Double.NaN, Ui.OXY).daily(d.end(30), 30).pick(d.date).unit("/min"), null));
        root.addView(PageParts.chartCard(c, "Blood oxygen, 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "spo2", 30), Double.NaN, Double.NaN, Ui.OXY).daily(d.end(30), 30).pick(d.date).range(88, 100).unit("%"), null));
        // with the period tracker and a logged period, the one temperature chart carries the cycle (owner's feedback of
        // 11 November: no second temperature chart at the end of Body); without it the chart stays as it was
        if (d.periodShown && d.periods != null && !d.periods.periods.isEmpty()) root.addView(PeriodAnalysis.temperature(c, d));
        else root.addView(PageParts.chartCard(c, "Temperature deviation, 30 days", new Charts.LineChart(c, PageParts.daysSeries(d, "temp_dev", 30), Double.NaN, Double.NaN, Ui.TEMP).daily(d.end(30), 30).pick(d.date).empty("Appears after 3 nights, it compares with your usual").range(-1.5, 1.5).reference(0).noFill().signed().unit(" \u00b0C"), null));
        // the period tracker's analysis at the end (issue #252, phase 1a2): the cycles over time
        if (d.periodShown && d.periods != null) {
            root.addView(PeriodAnalysis.cycles(c, d));
            // how close each prediction method was on these cycles (issue #383)
            if (!d.periods.periods.isEmpty()) root.addView(PeriodAnalysis.accuracy(c, d));
        }
        // the life factors logged on Today, as a month (issue #292): the evidence itself
        root.addView(LifeCard.body(c, d));
        // how the nights went after each factor, for you (issue #292, phase 2); only with a ring
        View effects = LifeCard.effects(c, d);
        if (effects != null) root.addView(effects);
        return root;
    }

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
        if (!Double.isNaN(now.hrAt)) when.append("Heart rate at ").append(Charts.clock(now.hrAt))
                .append(app.openring.core.analysis.ClockChange.repeatNote(java.time.ZoneId.systemDefault(), now.hrAt)).append(". ");
        if (!Double.isNaN(now.skinTempAt)) when.append("Temperature at ").append(Charts.clock(now.skinTempAt))
                .append(app.openring.core.analysis.ClockChange.repeatNote(java.time.ZoneId.systemDefault(), now.skinTempAt)).append(". ");
        if (!Double.isNaN(now.spo2At)) when.append("Blood oxygen at ").append(Charts.clock(now.spo2At))
                .append(app.openring.core.analysis.ClockChange.repeatNote(java.time.ZoneId.systemDefault(), now.spo2At)).append(". ");
        when.append("The ring measures on its own schedule, every few minutes when you are still. Sync to update.");
        card.addView(Ui.text(c, when.toString(), 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }
}
