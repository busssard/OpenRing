package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.DeviceHistory;

/**
 * The watch's nights in the form of the ring's stored nights (watch statistics, owner's report of 13 November), so the
 * ring's own screens and averages show them: the recent nights strip, the night timeline and Further analysis
 * ({@link SleepHistory#of}). Nothing is stored; the objects live only while a screen shows them.
 */
public final class WatchHistory {
    private WatchHistory() {}

    /**
     * One watch night as the ring's night fields: its day, window, stages (one digit a minute), minutes of each stage, time asleep
     * and in bed, efficiency, sleep start and end, wake ups, latency when the watch marked one, the lowest heart rate and when,
     * and the score. Fields the watch has no signal for (HRV, breathing, blood oxygen, temperature) are absent, as on a ring
     * night without them.
     */
    public static JSONObject nightJson(DeviceHistory.NightRow r) {
        JSONObject o = new JSONObject();
        Json.put(o, "day", r.day.toString());
        Json.put(o, "main", true);
        Json.put(o, "source", "watch");
        // the watch does not tell REM from light sleep: the screens show them as one stage (owner's report of 13 November)
        if (!r.fields.stageSource.equals("watch")) Json.put(o, "light_rem", true);
        Json.put(o, "start", (double) r.night.start);
        Json.put(o, "end", (double) r.night.end);
        StringBuilder st = new StringBuilder(r.night.stages.length);
        for (int s : r.night.stages) st.append((char) ('0' + Math.max(0, Math.min(9, s))));
        Json.put(o, "stages", st.toString());
        Json.put(o, "deep_min", r.night.deepMin);
        Json.put(o, "light_min", r.night.lightMin);
        Json.put(o, "rem_min", r.night.remMin);
        Json.put(o, "awake_min", r.night.awakeMin);
        Json.put(o, "asleep_min", r.night.asleepMin());
        Json.put(o, "in_bed_min", r.night.inBedMin());
        Json.put(o, "efficiency", r.fields.efficiency);
        Json.put(o, "sleep_start", r.fields.sleepStart);
        Json.put(o, "sleep_end", r.fields.sleepEnd);
        Json.put(o, "latency_min", r.fields.latencyMin);
        if (!Double.isNaN(r.fields.sleepStart)) Json.put(o, "awakenings", r.fields.awakenings);
        if (r.lowest > 0) {
            Json.put(o, "lowest_hr", r.lowest);
            Json.put(o, "lowest_hr_time", (double) r.lowestAt);
        }
        if (!Double.isNaN(r.score)) {
            JSONObject sc = new JSONObject();
            Json.put(sc, "value", r.score);
            Json.put(o, "score", sc);
        }
        return o;
    }

    /** The nights as {@link #nightJson}, in the same order. */
    public static List<JSONObject> json(List<DeviceHistory.NightRow> rows) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (DeviceHistory.NightRow r : rows) out.add(nightJson(r));
        return out;
    }

    /**
     * Further analysis of the watch's nights whose day lies from {@code fromDay} to {@code toDay} (ISO dates, "2000-01-01" for
     * all time): the average night, typical values and times, as {@link SleepHistory#load} gives them for the ring. Reads only
     * the sleep pieces and heart rates, from the evening before the first day.
     */
    public static SleepHistory load(Db db, String fromDay, String toDay, ZoneId zone, double age, boolean rem) {
        LocalDate first = LocalDate.parse(fromDay), last = LocalDate.parse(toDay);
        double from = first.atStartOfDay(zone).toEpochSecond() - 86400, to = last.plusDays(1).atStartOfDay(zone).toEpochSecond();
        app.openring.core.MainDevice.Watch w = WatchStore.nightRows(db, from, to);
        return SleepHistory.of(json(DeviceHistory.nightRows(w.sleep, w.heart, first, last, zone, age, rem)), zone);
    }
}
