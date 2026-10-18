package app.openring.data;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.analysis.SleepPattern;
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
            asleep[i] = Json.d(o, "asleep_min");
            inBed[i] = Json.d(o, "in_bed_min");
            deep[i] = Json.d(o, "deep_min");
            rem[i] = Json.d(o, "rem_min");
            light[i] = Json.d(o, "light_min");
            awake[i] = Json.d(o, "awake_min");
            eff[i] = Json.d(o, "efficiency");
            JSONObject sc = o.optJSONObject("score");
            score[i] = sc != null && sc.has("value") && sc.optInt("value", -1) >= 0 ? sc.optInt("value") : Double.NaN;
            low[i] = Json.d(o, "lowest_hr");
            hrv[i] = Json.d(o, "avg_hrv");
            wakes[i] = o.has("awakenings") ? o.optInt("awakenings") : Double.NaN;
            ZoneId nz = Json.zone(o, zone);
            bed[i] = relClock(Json.d(o, "sleep_start"), nz);
            wake[i] = relClock(Json.d(o, "sleep_end"), nz);
            double start = Json.d(o, "start"), end = Json.d(o, "end");
            int[] st = Json.stages(o);
            if (!Double.isNaN(start) && end > start && st.length > 0) {
                h.pattern.add(clockMin(start, nz), st, (end - start) / st.length);
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
        double b = Stats.mean(bed), w = Stats.mean(wake);
        h.bedtimeMin = Double.isNaN(b) ? Double.NaN : ((b + SleepPattern.ANCHOR_MIN) % 1440 + 1440) % 1440;
        h.wakeMin = Double.isNaN(w) ? Double.NaN : ((w + SleepPattern.ANCHOR_MIN) % 1440 + 1440) % 1440;
        if (Stats.countFinite(bed) >= 2) h.bedtimeSdMin = Stats.sd(bed);
        return h;
    }

    static double clockMin(double unix, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond((long) unix).atZone(zone);
        return z.getHour() * 60 + z.getMinute() + z.getSecond() / 60.0;
    }

    /** Minutes after 20:00, so times around midnight average correctly (NaN stays NaN). */
    static double relClock(double unix, ZoneId zone) {
        if (Double.isNaN(unix)) return Double.NaN;
        double r = clockMin(unix, zone) - SleepPattern.ANCHOR_MIN;
        if (r < -4 * 60) r += 1440;
        return r;
    }
}
