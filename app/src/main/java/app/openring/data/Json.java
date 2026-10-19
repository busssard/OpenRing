package app.openring.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import app.openring.core.analysis.Night;
import app.openring.core.analysis.TS;

/** JSON (de)serialisation of computed results. NaN is stored as absent. */
public final class Json {
    private Json() {}

    public static void put(JSONObject o, String k, double v) {
        try {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, Math.round(v * 1000) / 1000.0);
        } catch (JSONException ignored) {
        }
    }

    public static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    public static double d(JSONObject o, String k) {
        return o == null || !o.has(k) ? Double.NaN : o.optDouble(k, Double.NaN);
    }

    public static JSONObject parse(String s) {
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    public static JSONArray series(TS ts, int maxPoints) {
        JSONArray a = new JSONArray();
        int n = ts.size();
        if (n == 0) return a;
        int step = Math.max(1, (int) Math.ceil(n / (double) maxPoints));
        for (int i = 0; i < n; i += step) {
            int to = Math.min(n, i + step);
            double t = 0, v = 0;
            for (int k = i; k < to; k++) {
                t += ts.t(k);
                v += ts.v(k);
            }
            JSONArray p = new JSONArray();
            try {
                p.put(Math.round(t / (to - i)));
                p.put(Math.round(v / (to - i) * 100) / 100.0);
            } catch (JSONException ignored) {
            }
            a.put(p);
        }
        return a;
    }

    public static TS series(JSONArray a) {
        TS ts = new TS();
        if (a == null) return ts;
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.optJSONArray(i);
            if (p != null && p.length() >= 2) ts.add(p.optDouble(0), p.optDouble(1));
        }
        return ts;
    }

    public static JSONObject score(Night.Score s) {
        JSONObject o = new JSONObject();
        if (s == null) return o;
        put(o, "value", s.value);
        put(o, "provisional", s.provisional);
        JSONArray cs = new JSONArray();
        for (Night.Contributor c : s.contributors) {
            JSONObject co = new JSONObject();
            put(co, "key", c.key);
            put(co, "label", c.label);
            put(co, "score", c.score);
            put(co, "weight", c.weight);
            put(co, "detail", c.detail);
            cs.put(co);
        }
        put(o, "contributors", cs);
        return o;
    }

    public static JSONObject night(Night n, String day) {
        JSONObject o = new JSONObject();
        put(o, "day", day);
        put(o, "start", n.start);
        put(o, "end", n.end);
        put(o, "main", n.main);
        put(o, "source", n.stageSource);
        StringBuilder st = new StringBuilder(n.stages.length);
        for (int s : n.stages) st.append((char) ('0' + s));
        put(o, "stages", st.toString());
        StringBuilder raw = new StringBuilder(n.stagesRaw.length);
        for (int s : n.stagesRaw) raw.append((char) ('0' + s));
        put(o, "stages_raw", raw.toString());
        put(o, "stage_detail", n.stageDetail);
        put(o, "in_bed_min", n.inBedMin);
        put(o, "asleep_min", n.asleepMin);
        put(o, "deep_min", n.deepMin);
        put(o, "light_min", n.lightMin);
        put(o, "rem_min", n.remMin);
        put(o, "awake_min", n.awakeMin);
        put(o, "efficiency", n.efficiency);
        put(o, "latency_min", n.latencyMin);
        put(o, "waso_min", n.wasoMin);
        put(o, "rem_latency_min", n.remLatencyMin);
        put(o, "awakenings", n.awakenings);
        put(o, "cycles", n.cycles);
        put(o, "restless_pct", n.restlessPct);
        put(o, "sleep_start", n.sleepStart);
        put(o, "sleep_end", n.sleepEnd);
        put(o, "midpoint", n.midpoint);
        put(o, "lowest_hr", n.lowestHr);
        put(o, "lowest_hr_time", n.lowestHrTime);
        put(o, "avg_hr", n.avgHr);
        put(o, "avg_hrv", n.avgHrv);
        put(o, "max_hrv", n.maxHrv);
        put(o, "hrv_deep", n.hrvDeep);
        put(o, "hrv_rem", n.hrvRem);
        put(o, "breath", n.breath);
        put(o, "temp", n.temp);
        put(o, "spo2", n.spo2);
        put(o, "spo2_low", n.spo2Low);
        put(o, "phone_min", n.phoneMin);
        put(o, "hr_series", series(n.hr, 160));
        put(o, "motion_series", series(app.openring.core.analysis.NightAnalyzer.bin(n.motion, n.start, n.end, 300), 200));
        put(o, "hrv_series", series(n.hrv, 160));
        put(o, "spo2_series", series(n.spo2Series, 120));
        put(o, "temp_series", series(n.tempSeries, 120));
        put(o, "score", score(n.score));
        return o;
    }

    /** The zone a night was slept in (issue #22), {@code fallback} for older nights or an id this phone does not know. */
    public static java.time.ZoneId zone(JSONObject night, java.time.ZoneId fallback) {
        java.time.ZoneId z = night == null ? null : app.openring.core.analysis.ClockChange.zone(night.optString("zone", null));
        return z == null ? fallback : z;
    }

    public static int[] stages(JSONObject night) {
        return digits(night.optString("stages", ""));
    }

    /** Stages before the detail filter; nights analysed before version 1.11 only have the filtered ones. */
    public static int[] stagesRaw(JSONObject night) {
        String raw = night.optString("stages_raw", "");
        return raw.isEmpty() ? stages(night) : digits(raw);
    }

    static int[] digits(String s) {
        int[] out = new int[s.length()];
        for (int i = 0; i < out.length; i++) out[i] = s.charAt(i) - '0';
        return out;
    }
}
