package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <li>A day without a device night takes the night entered by hand (issue #427, {@link ShownNights#withHand}'s rule: not
 * overlapping a shown device night, never with only the phone): its bedtime, wake time, wake ups, time asleep and score,
 * from {@link Trends#HAND}. Readiness stays the ring's stored one (a night by hand's readiness is only worked out on Sleep).</li>
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
        return read(db, today, now, excluded, dev, phone, new int[1]);
    }

    /** As above; {@code skipped[0]} counts the stored days and nights that could not be read (left out, for the log). */
    public static List<Trends.Day> read(Db db, LocalDate today, double now, Set<String> excluded, ShownNights.Devices dev, ZoneId phone,
            final int[] skipped) {
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
                if (o == null) skipped[0]++;
                if (x == null || o == null) continue;
                fromDay(x, o);
            }
        }
        // the ring's main nights, one at a time, as bedtime, wake time and wake ups (the same day: the last one by start)
        final Map<LocalDate, double[]> ring = new HashMap<LocalDate, double[]>();
        final Map<LocalDate, String> ringZone = new HashMap<LocalDate, String>();
        // every main night's {start, end} per day: a night by hand they overlap is not counted (ShownNights.withHand)
        final Map<LocalDate, List<double[]>> ringSpans = new HashMap<LocalDate, List<double[]>>();
        final ZoneId fallback = phone;
        if (dev.ring) {
            db.eachNightJson(from, to, true, new java.util.function.Consumer<String>() {
                @Override
                public void accept(String s) {
                    // only the top level fields are read, not the stages and series (most of a night's text)
                    Map<String, String> o = fields(s);
                    if (o == null || !o.containsKey("day")) {
                        skipped[0]++;
                        return;
                    }
                    LocalDate d = BadgeDays.parse(text(o.get("day")));
                    ring.put(d, night(o, fallback));
                    if (o.containsKey("zone")) ringZone.put(d, text(o.get("zone")));
                    List<double[]> sp = ringSpans.get(d);
                    if (sp == null) ringSpans.put(d, sp = new ArrayList<double[]>());
                    sp.add(new double[] {num(o.get("start")), num(o.get("end"))});
                }
            });
        }
        List<Object[]> zones = db.zones();
        DeviceHistory.DayZones noted = BadgeDays.dayZones(zones, phone);
        TreeMap<LocalDate, JSONObject> watch = new TreeMap<LocalDate, JSONObject>();
        DeviceHistory.DayZones watchZones = WatchHistory.dayZones(db, phone);
        if (dev.watch && WatchStore.any(db)) watch = ShownNights.watch(db, first, today, watchZones, dev.age);
        // the shown device nights' days and {start, end}, for the nights by hand below
        Set<String> deviceDays = new HashSet<String>();
        List<double[]> deviceSpans = new ArrayList<double[]>();
        for (Trends.Day x : out) {
            // the day's zone for the trips marked on the chart (phase 2): its ring night's, else the one noted then
            String rz = ringZone.get(x.date);
            x.zone = rz != null ? rz : noted.of(x.date).getId();
            double[] r = ring.get(x.date);
            JSONObject w = watch.get(x.date);
            boolean useWatch = w != null && MainDevice.nightFrom(dev.main, r != null, true) == MainDevice.WATCH;
            if (useWatch) {
                deviceDays.add(x.date.toString());
                deviceSpans.add(new double[] {Json.d(w, "start"), Json.d(w, "end")});
            } else if (r != null) {
                deviceDays.add(x.date.toString());
                deviceSpans.addAll(ringSpans.get(x.date));
            }
            if (useWatch) {
                double[] n = night(w, noted.of(x.date));
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
        // the nights entered by hand (issue #427) of the days without a device night, as Sleep shows them; with only the phone
        // none, as there (sleep needs a ring or a watch)
        if (dev.history != MainDevice.PHONE && HandNights.any(db)) {
            for (Map.Entry<LocalDate, JSONObject> e : ShownNights.hand(db, first, today, watchZones, dev.age, deviceDays, deviceSpans).entrySet()) {
                Trends.Day x = by.get(e.getKey());
                if (x == null) continue;
                JSONObject h = e.getValue();
                setNight(x, night(h, watchZones.of(e.getKey())), Trends.HAND);
                // the night by hand is the day's night, as on Sleep: its time asleep and score
                x.v[Trends.ASLEEP] = Double.NaN;
                x.v[Trends.SLEEP_SCORE] = Double.NaN;
                x.set(Trends.ASLEEP, Json.d(h, "asleep_min"), Trends.HAND);
                JSONObject sc = h.optJSONObject("score");
                if (sc != null && sc.has("value") && sc.optInt("value", -1) >= 0) x.set(Trends.SLEEP_SCORE, sc.optInt("value"), Trends.HAND);
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

    /**
     * The events Trends marks on its time axis (issue #374, phase 2) that are stored apart from the values of each day: every
     * left out time (the days it touches), the first day of each logged period (the period tracker's own rule,
     * {@link Periods#cycles}), the watch's workouts (when its rows are read), and when the stored nights were worked out with
     * "Count phone use as awake" ({@code kv screen_awake}, written by the analysis) the first day with screen data (a switch of
     * that setting works out every night with screen data again, so the stored nights all follow one rule; only from that
     * day on can phone use move a night). Nothing is guessed: when a
     * setting was switched is not stored, so it is not marked. A table that cannot be read gives no events of its kind
     * ({@code skipped[0]} counts them, for the log).
     */
    public static List<app.openring.core.analysis.TrendChange.Event> events(Db db, LocalDate today, ZoneId phone, ShownNights.Devices dev,
            int[] skipped) {
        List<app.openring.core.analysis.TrendChange.Event> out = new ArrayList<app.openring.core.analysis.TrendChange.Event>();
        DeviceHistory.DayZones zones = WatchHistory.dayZones(db, phone);
        try {
            for (double[] r : db.excluded()) {
                out.add(new app.openring.core.analysis.TrendChange.Event(app.openring.core.analysis.TrendChange.LEFT_OUT, day(r[0], zones, phone),
                        day(Math.max(r[0], r[1] - 1), zones, phone), "Time left out"));
            }
        } catch (RuntimeException e) {
            skipped[0]++;
        }
        try {
            for (app.openring.core.analysis.Cycles.Period p : Periods.cycles(db, today).periods) {
                out.add(new app.openring.core.analysis.TrendChange.Event(app.openring.core.analysis.TrendChange.PERIOD, p.start, p.start, "Period started"));
            }
        } catch (RuntimeException e) {
            skipped[0]++;
        }
        if (dev.watch) {
            try {
                android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT start FROM watch_workouts ORDER BY start", null);
                try {
                    while (c.moveToNext()) {
                        LocalDate d = day(c.getLong(0), zones, phone);
                        out.add(new app.openring.core.analysis.TrendChange.Event(app.openring.core.analysis.TrendChange.WORKOUT, d, d, "Workout"));
                    }
                } finally {
                    c.close();
                }
            } catch (RuntimeException e) {
                skipped[0]++;
            }
        }
        if (dev.ring && db.kvLong("screen_awake", 0) == 1) {
            double first = db.screenStart(true);
            if (first > 0) {
                LocalDate d = day(first, zones, phone);
                out.add(new app.openring.core.analysis.TrendChange.Event(app.openring.core.analysis.TrendChange.PHONE_AWAKE, d, d,
                        "Phone use counted as awake from here"));
            }
        }
        return out;
    }

    /** The local date of {@code unix} in the zone noted for that day. */
    static LocalDate day(double unix, DeviceHistory.DayZones zones, ZoneId phone) {
        java.time.Instant t = java.time.Instant.ofEpochSecond((long) Math.floor(unix));
        LocalDate guess = t.atZone(phone).toLocalDate();
        return t.atZone(zones.of(guess)).toLocalDate();
    }

    /**
     * A short text that changes whenever what {@link #read} reads may have changed: the last analysis, the stored nights and days,
     * the screen sessions and reads, the apps, the zones, the watch's and the phone's rows, the nights by hand. Trends reads the history again only
     * when it differs from the last read (code review of #413), so coming back to the page keeps the period touched. A table
     * that cannot be counted gives "?".
     */
    public static String stamp(Db db) {
        StringBuilder b = new StringBuilder(db.fileName).append('|').append(db.kv("last_analysis", "")).append('|').append(db.kv("analyzed_until", ""));
        String[] q = {"SELECT COUNT(*) FROM nights", "SELECT COUNT(*) FROM days", "SELECT COALESCE(MAX(stop), 0) FROM screen", "SELECT COUNT(*) FROM screen",
            "SELECT COALESCE(MAX(stop), 0) FROM screen_reads", "SELECT COUNT(*) FROM apps", "SELECT COUNT(*) FROM zones",
            "SELECT COUNT(*) FROM watch_sleep", "SELECT COUNT(*) FROM watch_steps", "SELECT COUNT(*) FROM watch_hr", "SELECT COUNT(*) FROM phone_steps",
            // the nights by hand: a save adds a row, a delete or Undo moves a removal mark, and the keys in force tell which
            "SELECT COUNT(*) FROM sleep_manual", "SELECT COUNT(*) FROM sleep_manual WHERE COALESCE(gone, 0) <> 0",
            "SELECT COALESCE(SUM(made), 0) FROM sleep_manual WHERE COALESCE(gone, 0) = 0",
            // the events of phase 2: left out times, the period log (an edit notes when it was made), the watch's workouts
            "SELECT COUNT(*) + COALESCE(MAX(made), 0) FROM excluded", "SELECT COUNT(*) + COALESCE(MAX(made), 0) FROM period_days",
            "SELECT COUNT(*) + COALESCE(MAX(made), 0) FROM period_ends", "SELECT COUNT(*) FROM watch_workouts"};
        for (String x : q) {
            try {
                b.append('|').append(db.count(x));
            } catch (RuntimeException e) {
                b.append("|?");
            }
        }
        return b.toString();
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

    /** As above from a night already parsed (the watch's, worked out on each read). */
    static double[] night(JSONObject o, ZoneId zone) {
        ZoneId nz = Json.zone(o, zone);
        JSONObject sc = o.optJSONObject("score");
        return new double[] {SleepHistory.clockOf(Json.d(o, "sleep_start"), nz), SleepHistory.clockOf(Json.d(o, "sleep_end"), nz),
            o.has("awakenings") ? o.optInt("awakenings") : Double.NaN, o.has("stage_detail") ? o.optInt("stage_detail") : -1,
            Json.d(o, "asleep_min"), sc != null && sc.has("value") && sc.optInt("value", -1) >= 0 ? sc.optInt("value") : Double.NaN};
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

    /**
     * A raw string without its quotes and with its escapes read (Android's org.json writes "/" as "\/", so a stored zone is
     * "Europe\/Zurich"; code review of #413). Anything else as it is.
     */
    public static String text(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"') return raw;
        String in = raw.substring(1, raw.length() - 1);
        if (in.indexOf('\\') < 0) return in;
        StringBuilder b = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c != '\\' || i + 1 >= in.length()) {
                b.append(c);
                continue;
            }
            char e = in.charAt(++i);
            switch (e) {
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 < in.length()) {
                        try {
                            b.append((char) Integer.parseInt(in.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                            // kept as written below
                        }
                    }
                    b.append('\\').append(e);
                    break;
                default: b.append(e); // a quote, a backslash or a slash
            }
        }
        return b.toString();
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
     * phone (without a ring), the first night by hand (not with only the phone), the first read of the usage log; {@code today} without any. No limit: Trends covers the whole
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
        if (dev.history != MainDevice.PHONE) {
            // a night by hand before any device's data (none with only the phone, as Sleep)
            long h = db.count("SELECT COALESCE(MIN(start), -1) FROM sleep_manual WHERE COALESCE(gone, 0) = 0");
            if (h > 0) f = BadgeDays.min(f, app.openring.core.HandNight.dayOf(h, phone));
        }
        List<double[]> reads = ScreenDays.reads(db);
        if (!reads.isEmpty() && reads.get(0)[0] > 0) {
            f = BadgeDays.min(f, java.time.Instant.ofEpochSecond((long) Math.floor(reads.get(0)[0])).atZone(phone).toLocalDate());
        }
        return f;
    }
}
