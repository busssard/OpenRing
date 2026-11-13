package app.openring.data;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
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
     * Some night of the period comes from a watch that does not tell REM from light sleep, so the screen shows "Light and
     * REM" as one stage ({@link WatchHistory#load}); false for the ring's nights.
     */
    public boolean lightRem;

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

    /**
     * Main nights whose day lies in [fromDay, toDay] (ISO dates; "0000-01-01" for all time), read and folded one night
     * at a time (issue #196): only the dozen values per night the averages need are kept, never the parsed nights, so
     * "All time" over years holds about one night at once instead of all of them. Gives the same result as
     * {@link #of(List, ZoneId)} on the same nights.
     */
    public static SleepHistory load(Db db, String fromDay, String toDay, ZoneId zone) {
        final Fold f = new Fold(zone);
        db.eachNightJson(fromDay, toDay, true, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String s) {
                JSONObject o = Json.parse(s);
                if (o != null) f.add(o);
            }
        });
        return f.finish();
    }

    /** {@code zone}: for nights stored without their own zone (before 1.26, demo mode); others use theirs (issue #22). */
    public static SleepHistory of(List<JSONObject> nights, ZoneId zone) {
        Fold f = new Fold(zone);
        for (JSONObject o : nights) f.add(o);
        return f.finish();
    }

    /**
     * The averages built night by night (issue #196): {@link #add} takes one night in start order and keeps its values
     * in columns of numbers, {@link #finish} averages them. {@link #of} and {@link #load} both use it, so they agree.
     */
    static final class Fold {
        private static final int ASLEEP = 0, IN_BED = 1, DEEP = 2, REM = 3, LIGHT = 4, AWAKE = 5, EFF = 6, SCORE = 7, LOW = 8,
                HRV = 9, WAKES = 10, BED = 11, WAKE = 12, COLUMNS = 13;
        private final ZoneId zone;
        private final SleepHistory h = new SleepHistory();
        private double[][] col = new double[COLUMNS][32];
        private int n;

        Fold(ZoneId zone) {
            this.zone = zone;
        }

        void add(JSONObject o) {
            if (n == col[0].length) for (int k = 0; k < COLUMNS; k++) col[k] = Arrays.copyOf(col[k], n * 2);
            int i = n++;
            if (i == 0) h.firstDay = o.optString("day");
            h.lastDay = o.optString("day");
            int lv = StageFilter.clampLevel(o.optInt("stage_detail", StageFilter.DETAILED));
            // Detailed and Balanced keep the same wake ups (a minute or more): only a different rule is a mix
            if (i == 0) h.stageDetail = lv;
            else if (h.stageDetail >= 0 && WakeUps.shortestKeptMin(h.stageDetail) != WakeUps.shortestKeptMin(lv)) h.stageDetail = -1;
            col[ASLEEP][i] = Json.d(o, "asleep_min");
            col[IN_BED][i] = Json.d(o, "in_bed_min");
            col[DEEP][i] = Json.d(o, "deep_min");
            col[REM][i] = Json.d(o, "rem_min");
            col[LIGHT][i] = Json.d(o, "light_min");
            col[AWAKE][i] = Json.d(o, "awake_min");
            // from the stored minutes, so nights analysed before issue #107 average the same way (StageShare.efficiency)
            col[EFF][i] = app.openring.core.analysis.StageShare.efficiency(col[ASLEEP][i], col[IN_BED][i], Json.d(o, "efficiency"));
            JSONObject sc = o.optJSONObject("score");
            col[SCORE][i] = sc != null && sc.has("value") && sc.optInt("value", -1) >= 0 ? sc.optInt("value") : Double.NaN;
            col[LOW][i] = Json.d(o, "lowest_hr");
            col[HRV][i] = Json.d(o, "avg_hrv");
            col[WAKES][i] = o.has("awakenings") ? o.optInt("awakenings") : Double.NaN;
            ZoneId nz = Json.zone(o, zone);
            col[BED][i] = clockOf(Json.d(o, "sleep_start"), nz);
            col[WAKE][i] = clockOf(Json.d(o, "sleep_end"), nz);
            double start = Json.d(o, "start"), end = Json.d(o, "end");
            int[] st = Json.stages(o);
            if (!Double.isNaN(start) && end > start && st.length > 0) {
                // each stage at its own clock time, so a clock change night keeps its clock times (issue #137)
                h.pattern.add(start, st, (end - start) / st.length, nz);
            }
        }

        SleepHistory finish() {
            h.nights = n;
            if (n == 0) return h;
            h.asleepMin = Stats.mean(column(ASLEEP));
            h.inBedMin = Stats.mean(column(IN_BED));
            h.deepMin = Stats.mean(column(DEEP));
            h.remMin = Stats.mean(column(REM));
            h.lightMin = Stats.mean(column(LIGHT));
            h.awakeMin = Stats.mean(column(AWAKE));
            h.efficiency = Stats.mean(column(EFF));
            h.score = Stats.mean(column(SCORE));
            h.lowestHr = Stats.mean(column(LOW));
            h.hrv = Stats.mean(column(HRV));
            h.wakeUps = Stats.mean(column(WAKES));
            // on the clock face, so times around 16:00 (day sleep) average as well as those around midnight (issue #164)
            double[] b = ClockAverage.of(column(BED));
            h.bedtimeMin = b[0];
            h.bedtimeSdMin = b[1];
            h.wakeMin = ClockAverage.of(column(WAKE))[0];
            return h;
        }

        /** The values of one column for the nights added, exactly {@code n} long as the averages expect. */
        private double[] column(int k) {
            return Arrays.copyOf(col[k], n);
        }
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
