package app.openring.data;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.analysis.ClockAverage;
import app.openring.core.analysis.SleepPattern;
import app.openring.core.analysis.StageFilter;
import app.openring.core.analysis.WakeUps;
import app.openring.core.analysis.Stats;

/** Averages over the main nights of a period: the average night pattern plus typical values. */
public final class SleepHistory {
    public SleepPattern pattern = new SleepPattern();
    public int nights;
    public String firstDay, lastDay;
    public double asleepMin = Double.NaN, inBedMin = Double.NaN, deepMin = Double.NaN, remMin = Double.NaN,
            lightMin = Double.NaN, awakeMin = Double.NaN, efficiency = Double.NaN, score = Double.NaN,
            lowestHr = Double.NaN, hrv = Double.NaN, wakeUps = Double.NaN;
    /** Typical clock times as minutes after midnight (NaN when unknown). */
    public double bedtimeMin = Double.NaN, wakeMin = Double.NaN;
    /** Spread of the sleep start, minutes (a measure of how regular bedtimes are). */
    public double bedtimeSdMin = Double.NaN;
    /**
     * Stage detail level all nights were analysed with, so the Wake ups line can say what was counted
     * (issue #128); nights stored without one count as Detailed (as on the night), -1 when their wake up
     * rules differ (Calm and another level; Detailed and Balanced count the same wake ups).
     */
    public int stageDetail = StageFilter.DETAILED;

    /**
     * The orange line after a trip (issue #22): {@code night}'s zone differs from the zone of the night
     * in {@code nights} that started last before it. Null without a night before or without a change.
     */
    public static String travelLine(JSONObject night, List<JSONObject> nights) {
        if (night == null) return null;
        double start = Json.d(night, "start");
        JSONObject prev = null;
        for (JSONObject o : nights) if (Json.d(o, "start") < start && (prev == null || Json.d(o, "start") > Json.d(prev, "start"))) prev = o;
        if (prev == null) return null;
        double mid = (start + Json.d(night, "end")) / 2;
        return app.openring.core.analysis.ClockChange.travel(prev.optString("zone", null), night.optString("zone", null), mid);
    }

    /** Main nights whose day lies in [fromDay, toDay] (ISO dates; "0000-01-01" for all time). */
    public static SleepHistory load(Db db, String fromDay, String toDay, ZoneId zone) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (String s : db.nightsJson(fromDay, toDay, true)) {
            JSONObject o = Json.parse(s);
            if (o != null) list.add(o);
        }
        return of(list, zone);
    }

    /** {@code zone}: for nights stored without their own zone (before 1.26, demo mode); others use theirs (issue #22). */
    public static SleepHistory of(List<JSONObject> nights, ZoneId zone) {
        SleepHistory h = new SleepHistory();
        int n = nights.size();
        h.nights = n;
        if (n == 0) return h;
        h.firstDay = nights.get(0).optString("day");
        h.lastDay = nights.get(n - 1).optString("day");
        double[] asleep = new double[n], inBed = new double[n], deep = new double[n], rem = new double[n], light = new double[n],
                awake = new double[n], eff = new double[n], score = new double[n], low = new double[n], hrv = new double[n],
                wakes = new double[n], bed = new double[n], wake = new double[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = nights.get(i);
            int lv = StageFilter.clampLevel(o.optInt("stage_detail", StageFilter.DETAILED));
            // Detailed and Balanced keep the same wake ups (a minute or more): only a different rule is a mix
            if (i == 0) h.stageDetail = lv;
            else if (h.stageDetail >= 0 && WakeUps.shortestKeptMin(h.stageDetail) != WakeUps.shortestKeptMin(lv)) h.stageDetail = -1;
            asleep[i] = Json.d(o, "asleep_min");
            inBed[i] = Json.d(o, "in_bed_min");
            deep[i] = Json.d(o, "deep_min");
            rem[i] = Json.d(o, "rem_min");
            light[i] = Json.d(o, "light_min");
            awake[i] = Json.d(o, "awake_min");
            // from the stored minutes, so nights analysed before issue #107 average the same way (StageShare.efficiency)
            eff[i] = app.openring.core.analysis.StageShare.efficiency(asleep[i], inBed[i], Json.d(o, "efficiency"));
            JSONObject sc = o.optJSONObject("score");
            score[i] = sc != null && sc.has("value") && sc.optInt("value", -1) >= 0 ? sc.optInt("value") : Double.NaN;
            low[i] = Json.d(o, "lowest_hr");
            hrv[i] = Json.d(o, "avg_hrv");
            wakes[i] = o.has("awakenings") ? o.optInt("awakenings") : Double.NaN;
            ZoneId nz = Json.zone(o, zone);
            bed[i] = clockOf(Json.d(o, "sleep_start"), nz);
            wake[i] = clockOf(Json.d(o, "sleep_end"), nz);
            double start = Json.d(o, "start"), end = Json.d(o, "end");
            int[] st = Json.stages(o);
            if (!Double.isNaN(start) && end > start && st.length > 0) {
                // each stage at its own clock time, so a clock change night keeps its clock times (issue #137)
                h.pattern.add(start, st, (end - start) / st.length, nz);
            }
        }
        h.asleepMin = Stats.mean(asleep);
        h.inBedMin = Stats.mean(inBed);
        h.deepMin = Stats.mean(deep);
        h.remMin = Stats.mean(rem);
        h.lightMin = Stats.mean(light);
        h.awakeMin = Stats.mean(awake);
        h.efficiency = Stats.mean(eff);
        h.score = Stats.mean(score);
        h.lowestHr = Stats.mean(low);
        h.hrv = Stats.mean(hrv);
        h.wakeUps = Stats.mean(wakes);
        // on the clock face, so times around 16:00 (day sleep) average as well as those around midnight (issue #164)
        double[] b = ClockAverage.of(bed);
        h.bedtimeMin = b[0];
        h.bedtimeSdMin = b[1];
        h.wakeMin = ClockAverage.of(wake)[0];
        return h;
    }

    static double clockMin(double unix, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond((long) unix).atZone(zone);
        return z.getHour() * 60 + z.getMinute() + z.getSecond() / 60.0;
    }

    /** Clock minutes after midnight (NaN stays NaN); {@link ClockAverage} averages them on the clock face. */
    static double clockOf(double unix, ZoneId zone) {
        return Double.isNaN(unix) ? Double.NaN : clockMin(unix, zone);
    }
}
