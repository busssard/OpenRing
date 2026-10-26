package app.openring.ui;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.openring.data.Db;
import app.openring.data.Json;

/** Everything one screen needs for a date: the day summary, its main sleep, naps and history. */
public final class DayData {
    public LocalDate date;
    public JSONObject day;
    public JSONObject night;
    public final List<JSONObject> naps = new ArrayList<JSONObject>();
    /** Day summaries for the 30 days ending at {@link #date}, keyed by ISO date. */
    public final Map<String, JSONObject> history = new HashMap<String, JSONObject>();
    /** Newest readings from the last sync (for the Body page). */
    public app.openring.data.RightNow now;
    /** Main nights from 30 days before the selected date to 30 days after it (at most up to tomorrow), oldest first (issue #98). */
    public final List<JSONObject> recent = new ArrayList<JSONObject>();
    /** Screen time of the selected date and the days before (Screen tab, Today ring); null if it could not be read. */
    public app.openring.data.ScreenDays.Info screen;
    /** The zone this day is shown in: its main night's, else the zone noted then, else the phone's (issue #22). */
    public java.time.ZoneId zone = java.time.ZoneId.systemDefault();

    public static DayData load(Context c, LocalDate date) {
        Db db = Db.get(c);
        DayData d = new DayData();
        d.date = date;
        String key = date.toString();
        d.day = Json.parse(db.dayJson(key));
        for (String s : db.nightsJson(key, key, false)) {
            JSONObject o = Json.parse(s);
            if (o == null) continue;
            if (o.optBoolean("main")) d.night = o;
            else d.naps.add(o);
        }
        java.time.ZoneId phone = java.time.ZoneId.systemDefault();
        java.time.ZoneId noted = app.openring.core.analysis.ClockChange.zone(db.zoneAt(date.atTime(12, 0).atZone(phone).toEpochSecond()));
        d.zone = Json.zone(d.night, noted == null ? phone : noted);
        // a window around the date, so an old date loads as fast as a recent one (issue #98)
        for (String s : db.recentNights(date, LocalDate.now())) {
            JSONObject o = Json.parse(s);
            if (o != null) d.recent.add(o);
        }
        try {
            d.now = app.openring.data.RightNow.load(c);
        } catch (RuntimeException ignored) {
        }
        try {
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            d.screen = app.openring.data.ScreenDays.info(db, date, d.zone, p.screenGoalMin(), p.screenExcluded(),
                    p.screenMinShowMin() * 60.0);
        } catch (RuntimeException ignored) {
        }
        for (String[] r : db.daysJson(date.minusDays(29).toString(), key)) {
            JSONObject o = Json.parse(r[1]);
            if (o != null) d.history.put(r[0], o);
        }
        return d;
    }

    /** Values of a day field for the {@code n} days ending at the selected date (NaN if missing). */
    public double[] series(String field, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = history.get(date.minusDays(n - 1 - i).toString());
            out[i] = Json.d(o, field);
        }
        return out;
    }

    /** Score value from a nested score object of a day summary, or NaN. */
    public double[] scoreSeries(String field, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = history.get(date.minusDays(n - 1 - i).toString());
            JSONObject s = o == null ? null : o.optJSONObject(field);
            out[i] = s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
        }
        return out;
    }

    public String[] weekdayLabels(int n) {
        String[] l = new String[n];
        for (int i = 0; i < n; i++) {
            String s = date.minusDays(n - 1 - i).getDayOfWeek().getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault());
            l[i] = s;
        }
        return l;
    }

    public double score(String key) {
        if ("screen".equals(key)) return screen == null || screen.score == null ? Double.NaN : screen.score.value;
        if ("sleep".equals(key)) {
            JSONObject s = night == null ? null : night.optJSONObject("score");
            return s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
        }
        JSONObject s = day == null ? null : day.optJSONObject(key);
        return s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
    }

    public JSONObject scoreObj(String key) {
        if ("sleep".equals(key)) return night == null ? null : night.optJSONObject("score");
        return day == null ? null : day.optJSONObject(key);
    }

    /** The zone a night is shown in: its own, else this day's (issue #22). */
    public java.time.ZoneId zoneOf(JSONObject night) {
        return Json.zone(night, zone);
    }

    /** The orange line after a trip: the main night's zone differs from the main night before it (issue #22). */
    public String travelLine() {
        if (night == null) return null;
        double start = Json.d(night, "start");
        JSONObject prev = null;
        for (JSONObject o : recent) if (Json.d(o, "start") < start && (prev == null || Json.d(o, "start") > Json.d(prev, "start"))) prev = o;
        if (prev == null) return null;
        double mid = (start + Json.d(night, "end")) / 2;
        return app.openring.core.analysis.ClockChange.travel(prev.optString("zone", null), night.optString("zone", null), mid);
    }

    public boolean empty() {
        return day == null && night == null;
    }
}
