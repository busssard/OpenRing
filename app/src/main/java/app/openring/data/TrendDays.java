package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import app.openring.core.DeviceHistory;
import app.openring.core.MainDevice;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.Trends;

/**
 * Reads what Trends (issue #374) needs for the whole history into one small array of values per day ({@link Trends.Day}),
 * once per open of the page; picking another measure or period only groups it again. Blocks; call it from a background
 * thread. Nothing is stored.
 *
 * <ul>
 * <li>The ring's day summaries ({@code days}) give time asleep, sleep score, readiness, resting heart rate, HRV and the
 * temperature deviation; steps and active calories only on a worn day ({@link Scores#worn} of {@code covered_min}), a day
 * without the ring is unknown, not zero.</li>
 * <li>The main nights give bedtime, wake time and wake ups (with the stage detail level), read one at a time and kept as
 * those few numbers (no stage pattern). Each day takes the night the screens show ({@link ShownNights}'s rule,
 * {@link MainDevice#nightFrom}): the watch's when it is the main device or the ring has none, then its time asleep and score
 * too.</li>
 * <li>Without a ring the watch's or the phone's days as the tabs show them ({@link DeviceHistory}): readiness, steps and
 * active calories on worn days, the lowest heart rate of the watch's nights.</li>
 * <li>Screen time per day as the Screen tab counts it ({@link ScreenDays#perDay}), each day in the zone the Screen tab
 * uses: its ring night's, else the zone noted then, else the phone's.</li>
 * </ul>
 */
public final class TrendDays {
    private TrendDays() {}

    /** Every date from the first day with any data to {@code today}, oldest first (empty days included, all NaN). */
    public static List<Trends.Day> read(Db db, LocalDate today, double now, Set<String> excluded, ShownNights.Devices dev, ZoneId phone) {
        LocalDate first = first(db, today, dev, phone);
        List<Trends.Day> out = new ArrayList<Trends.Day>();
        Map<LocalDate, Trends.Day> by = new HashMap<LocalDate, Trends.Day>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            Trends.Day x = new Trends.Day(d);
            out.add(x);
            by.put(d, x);
        }
        String from = first.toString(), to = today.toString();
        if (dev.ring) {
            for (String[] r : db.daysJson(from, to)) {
                Trends.Day x = by.get(BadgeDays.parse(r[0]));
                Map<String, String> o = fields(r[1]);
                if (x == null || o == null) continue;
                fromDay(x, o);
            }
        }
        // the ring's main nights, one at a time, as bedtime, wake time and wake ups (the same day: the last one by start)
        final Map<LocalDate, double[]> ring = new HashMap<LocalDate, double[]>();
        final Map<LocalDate, String> ringZone = new HashMap<LocalDate, String>();
        final ZoneId fallback = phone;
        if (dev.ring) {
            db.eachNightJson(from, to, true, new java.util.function.Consumer<String>() {
                @Override
                public void accept(String s) {
                    // only the top level fields are read, not the stages and series (most of a night's text)
                    Map<String, String> o = fields(s);
                    if (o == null || !o.containsKey("day")) return;
                    LocalDate d = BadgeDays.parse(text(o.get("day")));
                    ring.put(d, night(o, fallback));
                    if (o.containsKey("zone")) ringZone.put(d, text(o.get("zone")));
                }
            });
        }
        List<Object[]> zones = db.zones();
        DeviceHistory.DayZones noted = BadgeDays.dayZones(zones, phone);
        TreeMap<LocalDate, JSONObject> watch = new TreeMap<LocalDate, JSONObject>();
        if (dev.watch && WatchStore.any(db)) watch = ShownNights.watch(db, first, today, WatchHistory.dayZones(db, phone), dev.age);
        for (Trends.Day x : out) {
            double[] r = ring.get(x.date);
            JSONObject w = watch.get(x.date);
            boolean useWatch = w != null && MainDevice.nightFrom(dev.main, r != null, true) == MainDevice.WATCH;
            if (useWatch) {
                double[] n = night(fields(w.toString()), noted.of(x.date));
                setNight(x, n, Trends.WATCH);
                // the watch's night replaces the ring's for the sleep numbers, as on Sleep
                x.v[Trends.ASLEEP] = Double.NaN;
                x.v[Trends.SLEEP_SCORE] = Double.NaN;
                x.set(Trends.ASLEEP, Json.d(w, "asleep_min"), Trends.WATCH);
                JSONObject sc = w.optJSONObject("score");
                if (sc != null && sc.has("value")) x.set(Trends.SLEEP_SCORE, sc.optDouble("value"), Trends.WATCH);
                if (Double.isNaN(x.v[Trends.RHR])) x.set(Trends.RHR, Json.d(w, "lowest_hr"), Trends.WATCH);
            } else if (r != null) {
                setNight(x, r, Trends.RING);
                // a night without its day summary (the day was left out): its own time asleep and score
                if (Double.isNaN(x.v[Trends.ASLEEP])) x.set(Trends.ASLEEP, r[N_ASLEEP], Trends.RING);
                if (Double.isNaN(x.v[Trends.SLEEP_SCORE])) x.set(Trends.SLEEP_SCORE, r[N_SCORE], Trends.RING);
            }
        }
        if (dev.history >= 0) {
            // without a ring: the device's days as its dials show them, for days without a ring summary
            DeviceHistory h = BadgeDays.history(db, first, today, dev, noted);
            int src = h.device == MainDevice.WATCH ? Trends.WATCH : Trends.PHONE;
            for (Trends.Day x : out) {
                int i = h.index(x.date);
                if (i < 0) continue;
                if (Double.isNaN(x.v[Trends.READINESS])) x.set(Trends.READINESS, h.readiness[i], src);
                if (Scores.worn(h.wornMin[i])) {
                    if (Double.isNaN(x.v[Trends.STEPS])) x.set(Trends.STEPS, h.steps[i], src);
                    if (Double.isNaN(x.v[Trends.KCAL])) x.set(Trends.KCAL, h.kcal[i], src);
                }
                if (Double.isNaN(x.v[Trends.RHR])) x.set(Trends.RHR, h.rhr[i], src);
            }
        }
        if (ScreenDays.any(db)) {
            final Map<LocalDate, String> rz = ringZone;
            final DeviceHistory.DayZones n = noted;
            final ZoneId ph = phone;
            double[] min = ScreenDays.perDay(db, first, today, excluded, now, new DeviceHistory.DayZones() {
                @Override
                public ZoneId of(LocalDate day) {
                    String z = rz.get(day);
                    ZoneId own = z == null ? null : app.openring.core.analysis.ClockChange.zone(z);
                    return own != null ? own : n.of(day);
                }
            });
            for (int i = 0; i < min.length && i < out.size(); i++) out.get(i).set(Trends.SCREEN, min[i], Trends.PHONE);
        }
        return out;
    }

    /** The ring's day summary (its top level fields): its values, steps and active calories only on a worn day. */
    static void fromDay(Trends.Day x, Map<String, String> o) {
        x.set(Trends.ASLEEP, num(o.get("asleep_min")), Trends.RING);
        x.set(Trends.SLEEP_SCORE, num(o.get("sleep_score")), Trends.RING);
        Map<String, String> rd = o.containsKey("readiness") ? fields(o.get("readiness")) : null;
        if (rd != null) x.set(Trends.READINESS, num(rd.get("value")), Trends.RING);
        x.set(Trends.RHR, num(o.get("rhr")), Trends.RING);
        x.set(Trends.HRV, num(o.get("hrv")), Trends.RING);
        x.set(Trends.TEMP, num(o.get("temp_dev")), Trends.RING);
        if (Scores.worn(num(o.get("covered_min")))) {
            x.set(Trends.STEPS, num(o.get("steps")), Trends.RING);
            x.set(Trends.KCAL, num(o.get("active_kcal")), Trends.RING);
        }
    }

    static final int N_BED = 0, N_WAKE = 1, N_WAKES = 2, N_DETAIL = 3, N_ASLEEP = 4, N_SCORE = 5;

    /**
     * The few numbers of a night (its top level fields) Trends uses: bedtime and wake time as clock minutes in the night's own
     * zone ({@code zone} for a night without one, as Further analysis), wake ups, stage detail level (-1 for none), time
     * asleep, score. The same numbers as {@code SleepHistory}'s fold reads from the parsed night (pipeline test).
     */
    static double[] night(Map<String, String> o, ZoneId zone) {
        ZoneId own = o.containsKey("zone") ? app.openring.core.analysis.ClockChange.zone(text(o.get("zone"))) : null;
        ZoneId nz = own == null ? zone : own;
        Map<String, String> sc = o.containsKey("score") ? fields(o.get("score")) : null;
        double score = sc == null ? Double.NaN : num(sc.get("value"));
        double wakes = num(o.get("awakenings")), detail = num(o.get("stage_detail"));
        return new double[] {SleepHistory.clockOf(num(o.get("sleep_start")), nz), SleepHistory.clockOf(num(o.get("sleep_end")), nz),
            Double.isNaN(wakes) ? Double.NaN : (int) wakes, Double.isNaN(detail) ? -1 : (int) detail,
            num(o.get("asleep_min")), score >= 0 ? (int) score : Double.NaN};
    }

    // ------------------------------------------------------------------ the top level of a stored JSON object

    /**
     * The top level fields of the JSON object {@code json} as their raw texts (a number, "null", a quoted string, or a whole
     * nested object or array), without building the nested ones: a night's text is mostly its stages and series, and parsing
     * all of it for years of nights took most of a second (issue #374, Experiment view). Null when it is not an object.
     */
    public static Map<String, String> fields(String json) {
        if (json == null) return null;
        int n = json.length(), i = skip(json, 0);
        if (i >= n || json.charAt(i) != '{') return null;
        Map<String, String> out = new HashMap<String, String>();
        i = skip(json, i + 1);
        if (i < n && json.charAt(i) == '}') return out;
        while (i < n) {
            if (json.charAt(i) != '"') return null;
            int ke = stringEnd(json, i);
            if (ke < 0) return null;
            String key = json.substring(i + 1, ke - 1);
            i = skip(json, ke);
            if (i >= n || json.charAt(i) != ':') return null;
            i = skip(json, i + 1);
            int ve = valueEnd(json, i);
            if (ve < 0) return null;
            out.put(key, json.substring(i, ve).trim());
            i = skip(json, ve);
            if (i >= n) return null;
            char c = json.charAt(i);
            if (c == '}') return out;
            if (c != ',') return null;
            i = skip(json, i + 1);
        }
        return null;
    }

    static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The index after the string starting at {@code i} (a quote), -1 when it does not end. */
    static int stringEnd(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') j++;
            else if (c == '"') return j + 1;
        }
        return -1;
    }

    /** The index after the value starting at {@code i}, -1 when it does not end. */
    static int valueEnd(String s, int i) {
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c == '"') return stringEnd(s, i);
        if (c == '{' || c == '[') {
            StringBuilder open = new StringBuilder();
            for (int j = i; j < s.length(); j++) {
                char x = s.charAt(j);
                if (x == '"') {
                    j = stringEnd(s, j);
                    if (j < 0) return -1;
                    j--;
                } else if (x == '{' || x == '[') {
                    open.append(x);
                } else if (x == '}' || x == ']') {
                    int top = open.length() - 1;
                    if (open.charAt(top) != (x == '}' ? '{' : '[')) return -1;
                    open.setLength(top);
                    if (top == 0) return j + 1;
                }
            }
            return -1;
        }
        int j = i;
        while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != '}') {
            // a number, true, false or null holds no quote or colon
            if (s.charAt(j) == '"' || s.charAt(j) == ':') return -1;
            j++;
        }
        return j;
    }

    /** A raw number, NaN for none, null or anything else. */
    public static double num(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) == '"' || raw.charAt(0) == '{' || raw.charAt(0) == '[' || "null".equals(raw)) return Double.NaN;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A raw string without its quotes (the zone and day texts hold no escapes). */
    public static String text(String raw) {
        return raw != null && raw.length() >= 2 && raw.charAt(0) == '"' ? raw.substring(1, raw.length() - 1) : raw;
    }

    static void setNight(Trends.Day x, double[] n, int src) {
        x.v[Trends.BEDTIME] = Double.NaN;
        x.v[Trends.WAKE] = Double.NaN;
        x.v[Trends.WAKE_UPS] = Double.NaN;
        x.set(Trends.BEDTIME, n[N_BED], src);
        x.set(Trends.WAKE, n[N_WAKE], src);
        x.set(Trends.WAKE_UPS, n[N_WAKES], src);
        x.detail = (int) n[N_DETAIL];
    }

    /**
     * The first day with any data: the first day summary, the first record of the watch (when its rows are read) or of the
     * phone (without a ring), the first read of the usage log; {@code today} without any. No limit: Trends covers the whole
     * history.
     */
    static LocalDate first(Db db, LocalDate today, ShownNights.Devices dev, ZoneId phone) {
        LocalDate f = today;
        String d = db.firstDay();
        if (d != null) f = BadgeDays.min(f, BadgeDays.parse(d));
        if (dev.watch) {
            LocalDate w = ShownNights.firstDay(db, MainDevice.WATCH, phone);
            if (w != null) f = BadgeDays.min(f, w);
        }
        LocalDate dd = ShownNights.firstDay(db, dev.history, phone);
        if (dd != null) f = BadgeDays.min(f, dd);
        List<double[]> reads = ScreenDays.reads(db);
        if (!reads.isEmpty() && reads.get(0)[0] > 0) {
            f = BadgeDays.min(f, java.time.Instant.ofEpochSecond((long) Math.floor(reads.get(0)[0])).atZone(phone).toLocalDate());
        }
        return f;
    }
}
