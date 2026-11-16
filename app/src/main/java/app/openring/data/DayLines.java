package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Map;

import app.openring.core.DayLine;

/**
 * The ring's facts for Today's "Your day" line ({@code core/DayLine}) from the stored day summaries: the day, its night and the
 * 30 days before it (the screens' {@code DayData.history}). The usual resting heart rate and HRV are the median of the nights
 * before. The caller adds the sync age, the screen, the badge and the period phase. Tested in the pipeline test on the demo.
 */
public final class DayLines {
    private DayLines() {}

    /** The facts of {@code date}; {@code history} holds day summaries by ISO date (the day itself may be in it too). */
    public static DayLine.Facts ring(LocalDate date, boolean isToday, JSONObject day, JSONObject night, Map<String, JSONObject> history) {
        DayLine.Facts f = new DayLine.Facts();
        f.date = date;
        f.isToday = isToday;
        f.device = DayLine.RING;
        f.goalName = "Activity goal";
        f.readiness = value(day, "readiness");
        JSONObject sc = night == null ? null : night.optJSONObject("score");
        f.sleepScore = sc != null && sc.has("value") ? sc.optDouble("value") : Json.d(day, "sleep_score");
        f.asleepMin = night != null ? Json.d(night, "asleep_min") : Json.d(day, "asleep_min");
        JSONObject debt = day == null ? null : day.optJSONObject("sleep_debt");
        if (debt != null && debt.optBoolean("valid")) {
            f.debtMin = debt.optDouble("debt_min", Double.NaN);
            f.needMin = debt.optDouble("need_h", Double.NaN) * 60;
        }
        f.rhr = Json.d(day, "rhr");
        f.hrv = Json.d(day, "hrv");
        f.tempDev = Json.d(day, "temp_dev");
        f.steps = Json.d(day, "steps");
        double[] rhr = new double[30], hrv = new double[30];
        for (int k = 0; k < 30; k++) {
            JSONObject o = history.get(date.minusDays(30 - k).toString());
            rhr[k] = Json.d(o, "rhr");
            hrv[k] = Json.d(o, "hrv");
        }
        f.rhrUsual = DayLine.usual(rhr);
        f.hrvUsual = DayLine.usual(hrv);
        // 29 days before (the history's 30 days with this one), so a streak counts on beyond a week
        int n = 29;
        f.readinessBefore = new double[n];
        f.sleepBefore = new double[n];
        f.goalBefore = new boolean[n];
        for (int k = 0; k < n; k++) {
            JSONObject o = history.get(date.minusDays(n - k).toString());
            f.readinessBefore[k] = value(o, "readiness");
            f.sleepBefore[k] = Json.d(o, "sleep_score");
            f.goalBefore[k] = reached(o);
        }
        if (day != null && day.has("active_kcal") && day.has("target_kcal")) {
            f.goalToday = reached(day) ? Boolean.TRUE : isToday ? null : Boolean.FALSE;
        }
        JSONObject y = history.get(date.minusDays(1).toString());
        if (y != null) f.hardYesterdayMin = y.optDouble("medium_min", 0) + y.optDouble("high_min", 0);
        return f;
    }

    /** The active burn reached its target that day (the badge Goal reached's rule, without the worn check). */
    static boolean reached(JSONObject o) {
        double a = Json.d(o, "active_kcal"), t = Json.d(o, "target_kcal");
        return t > 0 && a >= t;
    }

    static double value(JSONObject o, String key) {
        JSONObject s = o == null ? null : o.optJSONObject(key);
        return s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
    }
}
