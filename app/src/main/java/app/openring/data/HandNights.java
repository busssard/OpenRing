package app.openring.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import app.openring.core.HandNight;
import app.openring.core.Texts;

/**
 * The nights entered by hand (issue #427): one table, {@code sleep_manual}, only added to. A row is one night, its key
 * {@code made} (unix seconds when it was saved); {@code start} and {@code stop} are In bed and Up, {@code wakes} the wake marks
 * as a small JSON list ({@code core/HandNight.wakesText}), {@code gone} 1 when it was deleted or changed. A change writes a
 * new row and marks the old one gone, a delete only marks it gone, Undo clears the mark: no row is ever overwritten or
 * deleted, so a restore can never bring back a night that was taken away on the phone.
 *
 * <p>Nothing from a ring or a watch is changed: a night by hand is only shown for a day without a device night
 * ({@code core/MainDevice.nightFrom}); a device night later wins and this one stays stored. Kept when another ring is paired
 * ({@code Db.KEPT_TABLES}), emptied by Delete all, carried from the phone into a restored backup, the phone's row winning
 * ({@link #read}, {@link #merge}); an older backup without the table keeps the phone's. The Data check lists counts only;
 * nothing of it is written to the log or shown in a notification. Demo mode has its own made up night in the demo file
 * ({@link #demo}).
 *
 * <p>Writers get the database with {@code Db.get(c)} for each write and never keep a {@code Db} across calls (a restore swaps
 * the file, see {@link Periods}).
 */
public final class HandNights {
    private HandNights() {}

    /** The one table: kept when another ring is paired, emptied by Delete all. */
    static final String[] TABLES = {"sleep_manual"};

    private static final String COLUMNS = "made, start, stop, wakes, gone";

    /**
     * Schema 14: only adds the table ({@code IF NOT EXISTS}), safe to run twice. Older files (and restored older backups) get it
     * empty. No other table or row changes.
     */
    static void create14(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_manual (made REAL PRIMARY KEY, start REAL, stop REAL, wakes TEXT, gone INTEGER DEFAULT 0)");
    }

    /** One stored night by hand, removal marks included. */
    public static final class Row {
        public final double made, start, stop;
        public final List<double[]> wakes;
        public final boolean gone;

        Row(double made, double start, double stop, List<double[]> wakes, boolean gone) {
            this.made = made;
            this.start = start;
            this.stop = stop;
            this.wakes = wakes;
            this.gone = gone;
        }

        /** The wake marks kept inside the night, as shown and counted. */
        public List<double[]> marks() {
            return HandNight.marks(start, stop, wakes);
        }

        @Override
        public String toString() {
            return (long) made + " " + (long) start + " " + (long) stop + " " + HandNight.wakesText(wakes) + (gone ? " gone" : "");
        }
    }

    /** What a save wrote, so Undo can put it back: the new row's key and the rows it marked gone. */
    public static final class Saved {
        public final double made;
        public final List<Double> replaced;

        Saved(double made, List<Double> replaced) {
            this.made = made;
            this.replaced = replaced;
        }
    }

    /** Tests only: a save throws after marking the rows it replaces, as a stop half way would. */
    static volatile boolean failSave;

    /**
     * Saves a night from {@code start} (In bed) to {@code stop} (Up) with {@code wakes} at {@code made} (unix seconds), in one
     * transaction: the new row, and every night in force it overlaps (the one being changed, or an earlier entry of the same
     * night) marked gone. A stop half way writes nothing. Two saves in the same second get the next free key.
     */
    public static Saved save(Db db, double start, double stop, List<double[]> wakes, double made) {
        return save(db, start, stop, wakes, made, Double.NaN);
    }

    /** As above, a change of the night {@code replaces} (marked gone too, also when the new times no longer overlap it). */
    public static Saved save(Db db, double start, double stop, List<double[]> wakes, double made, double replaces) {
        if (Double.isNaN(start) || Double.isNaN(stop) || Double.isInfinite(start) || Double.isInfinite(stop) || stop - start < HandNight.MIN_NIGHT_S) {
            throw new IllegalArgumentException("not a night");
        }
        String text = HandNight.wakesText(HandNight.marks(start, stop, wakes));
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            double key = made;
            while (count(w, "SELECT COUNT(*) FROM sleep_manual WHERE made = ?", new String[] {Double.toString(key)}) > 0) key += 1;
            List<Double> replaced = new ArrayList<Double>();
            Cursor c = w.rawQuery("SELECT made FROM sleep_manual WHERE COALESCE(gone, 0) = 0 AND ((start < ? AND stop > ?) OR made = ?)",
                    new String[] {Double.toString(stop), Double.toString(start), Double.toString(replaces)});
            try {
                while (c.moveToNext()) replaced.add(c.getDouble(0));
            } finally {
                c.close();
            }
            for (Double r : replaced) w.execSQL("UPDATE sleep_manual SET gone = 1 WHERE made = ?", new Object[] {r});
            if (failSave) throw new IllegalStateException("test stop");
            w.execSQL("INSERT INTO sleep_manual(" + COLUMNS + ") VALUES (?, ?, ?, ?, 0)", new Object[] {key, start, stop, text});
            w.setTransactionSuccessful();
            return new Saved(key, replaced);
        } finally {
            w.endTransaction();
        }
    }

    /** Deletes the night {@code made}: a removal mark ({@code gone} 1), never a delete of the row. */
    public static void remove(Db db, double made) {
        db.getWritableDatabase().execSQL("UPDATE sleep_manual SET gone = 1 WHERE made = ?", new Object[] {made});
    }

    /** Undo of a delete: the night {@code made} is in force again. */
    public static void undoRemove(Db db, double made) {
        db.getWritableDatabase().execSQL("UPDATE sleep_manual SET gone = 0 WHERE made = ?", new Object[] {made});
    }

    /** Undo of a save: its new row gone, the rows it replaced in force again, in one transaction. */
    public static void undo(Db db, Saved s) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.execSQL("UPDATE sleep_manual SET gone = 1 WHERE made = ?", new Object[] {s.made});
            for (Double r : s.replaced) w.execSQL("UPDATE sleep_manual SET gone = 0 WHERE made = ?", new Object[] {r});
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    private static long count(SQLiteDatabase d, String sql, String[] args) {
        Cursor c = d.rawQuery(sql, args);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------------------------- reading

    /** The night {@code made} (also a gone one), null when there is none. */
    public static Row row(Db db, double made) {
        List<Row> l = rows(db.getReadableDatabase(), "WHERE made = ?", new String[] {Double.toString(made)});
        return l.isEmpty() ? null : l.get(0);
    }

    /** The nights in force that start from {@code from} up to {@code to} (unix seconds), oldest first. */
    public static List<Row> between(Db db, double from, double to) {
        return rows(db.getReadableDatabase(), "WHERE COALESCE(gone, 0) = 0 AND start >= ? AND start < ?", new String[] {Double.toString(from), Double.toString(to)});
    }

    /** The night in force on the bar of {@code day} in {@code zone} (the newest when there are more), null for none. */
    public static Row forDay(Db db, LocalDate day, ZoneId zone) {
        double[] bar = HandNight.bar(day, zone);
        List<Row> l = between(db, bar[0], bar[1]);
        Row best = null;
        for (Row r : l) if (best == null || r.made > best.made) best = r;
        return best;
    }

    private static List<Row> rows(SQLiteDatabase d, String where, String[] args) {
        List<Row> out = new ArrayList<Row>();
        Cursor c = d.rawQuery("SELECT " + COLUMNS + " FROM sleep_manual " + where + " ORDER BY start, made", args);
        try {
            while (c.moveToNext()) {
                // only a damaged row could lack its times; it is not shown
                if (c.isNull(0) || c.isNull(1) || c.isNull(2)) continue;
                double s = c.getDouble(1), e = c.getDouble(2);
                if (!(e > s)) continue;
                out.add(new Row(c.getDouble(0), s, e, HandNight.wakes(c.isNull(3) ? null : c.getString(3)), !c.isNull(4) && c.getInt(4) != 0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Whether the file holds any row (the demo build checks its own was written). */
    public static boolean any(Db db) {
        return db.count("SELECT COUNT(*) FROM sleep_manual") > 0;
    }

    // ------------------------------------------------------------------------------------- the night as the screens show it

    /**
     * The night of {@code r} in the ring's night fields ({@code Json.night}) for {@code day} in {@code zone}: no stages (source
     * {@code hand}), its score from total sleep, timing and wake ups, its zone, its key ({@code made}) and its wake marks
     * ({@code wakes}, each {start, end}). {@code age} for the score (NaN for none).
     */
    public static JSONObject nightJson(Row r, LocalDate day, ZoneId zone, double age) {
        JSONObject o = Json.night(HandNight.night(r.start, r.stop, r.wakes, zone, age), day.toString());
        Json.put(o, "zone", zone.getId());
        Json.put(o, "made", r.made);
        JSONArray w = new JSONArray();
        for (double[] m : r.marks()) w.put(new JSONArray(java.util.Arrays.asList(m[0], m[1])));
        Json.put(o, "wakes", w);
        // the score's own note: which parts it has (the screens show it under the score)
        JSONObject sc = o.optJSONObject("score");
        if (sc != null) Json.put(sc, "hand", true);
        return o;
    }

    /** The wake marks of a night JSON from {@link #nightJson}, each {start, end}; empty for any other night. */
    public static List<double[]> marks(JSONObject night) {
        List<double[]> out = new ArrayList<double[]>();
        JSONArray w = night == null ? null : night.optJSONArray("wakes");
        if (w == null) return out;
        for (int i = 0; i < w.length(); i++) {
            JSONArray a = w.optJSONArray(i);
            if (a != null && a.length() == 2) out.add(new double[] {a.optDouble(0), a.optDouble(1)});
        }
        return out;
    }

    /** A night JSON entered by hand. */
    public static boolean isHand(JSONObject night) {
        return night != null && HandNight.SOURCE.equals(night.optString("source"));
    }

    /**
     * The nights by hand of the days from {@code first} to {@code last}, each in its day's zone ({@code zones}), as night JSON by
     * day (the newest of a day when there are more). Reads only the rows of those bars.
     */
    public static TreeMap<LocalDate, JSONObject> days(Db db, LocalDate first, LocalDate last, app.openring.core.DeviceHistory.DayZones zones, double age) {
        TreeMap<LocalDate, JSONObject> out = new TreeMap<LocalDate, JSONObject>();
        double from = HandNight.bar(first, zones.of(first))[0], to = HandNight.bar(last, zones.of(last))[1];
        TreeMap<LocalDate, Row> best = new TreeMap<LocalDate, Row>();
        for (Row r : between(db, from - 86400, to + 86400)) {
            // the day whose bar holds its start, in that day's zone (a trip moves the bar with the zone)
            LocalDate d = HandNight.dayOf(r.start, zones.of(HandNight.dayOf(r.start, zones.of(first))));
            if (d.isBefore(first) || d.isAfter(last)) continue;
            Row b = best.get(d);
            if (b == null || r.made > b.made) best.put(d, r);
        }
        for (java.util.Map.Entry<LocalDate, Row> e : best.entrySet()) out.put(e.getKey(), nightJson(e.getValue(), e.getKey(), zones.of(e.getKey()), age));
        return out;
    }

    /**
     * The nights by hand whose day lies from {@code fromDay} to {@code toDay} (ISO dates) that neither the ring (a main night) nor the watch has a night for
     * (by day, and overlapping none of their nights, the rule of the replaced card), as night JSON ordered by start: what Further analysis adds to the
     * ring's nights (issue #427). Each day in the zone noted then ({@code WatchHistory.dayZones}). Empty without any row.
     */
    public static List<JSONObject> withoutRingNight(Db db, String fromDay, String toDay, ZoneId phone) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (!any(db)) return out;
        LocalDate first, last;
        try {
            first = LocalDate.parse(fromDay);
            last = LocalDate.parse(toDay);
        } catch (RuntimeException e) {
            return out;
        }
        // only the days that have a row: the oldest row bounds an "All time" range
        List<Row> rows = rows(db.getReadableDatabase(), "WHERE COALESCE(gone, 0) = 0", null);
        if (rows.isEmpty()) return out;
        LocalDate oldest = HandNight.dayOf(rows.get(0).start, phone).minusDays(1);
        if (first.isBefore(oldest)) first = oldest;
        if (first.isAfter(last)) return out;
        app.openring.core.DeviceHistory.DayZones zones = WatchHistory.dayZones(db, phone);
        TreeMap<LocalDate, JSONObject> hand = days(db, first, last, zones, Double.NaN);
        if (hand.isEmpty()) return out;
        java.util.Set<String> ringDays = new java.util.HashSet<String>();
        List<double[]> spans = new ArrayList<double[]>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, start, stop FROM nights WHERE main = 1 AND day >= ? AND day <= ?",
                new String[] {first.minusDays(1).toString(), last.plusDays(1).toString()});
        try {
            while (c.moveToNext()) {
                ringDays.add(c.getString(0));
                spans.add(new double[] {c.getDouble(1), c.getDouble(2)});
            }
        } finally {
            c.close();
        }
        // the watch's nights too: a night by hand a watch night replaced is not counted (the Sleep tab says it was replaced)
        if (db.count("SELECT EXISTS (SELECT 1 FROM watch_sleep)") != 0) {
            for (JSONObject w : ShownNights.watch(db, first.minusDays(1), last.plusDays(1), zones, Double.NaN).values()) {
                ringDays.add(w.optString("day"));
                spans.add(new double[] {Json.d(w, "start"), Json.d(w, "end")});
            }
        }
        for (java.util.Map.Entry<LocalDate, JSONObject> e : hand.entrySet()) {
            JSONObject o = e.getValue();
            if (ringDays.contains(e.getKey().toString()) || HandNight.overlaps(Json.d(o, "start"), Json.d(o, "end"), spans)) continue;
            out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- the Data check

    /** The Data check's line: counts only, never a time or a day. */
    static String checkLine(Db db) {
        long all = db.count("SELECT COUNT(*) FROM sleep_manual");
        long gone = db.count("SELECT COUNT(*) FROM sleep_manual WHERE COALESCE(gone, 0) <> 0");
        return "Nights by hand: " + Texts.count(all - gone, "night", "nights") + " kept, " + gone + " deleted or changed\n";
    }

    // ------------------------------------------------------------------------------------- demo

    /**
     * The demo's made up night by hand ({@code core/HandNight.demo}), written into {@code db}, the demo file (its build emptied
     * it), never the real one.
     */
    static void demo(Db db, LocalDate today, ZoneId zone, double now) {
        List<double[]> d = HandNight.demo(today, HandNight.DEMO_BACK, zone);
        save(db, d.get(0)[0], d.get(0)[1], d.subList(1, d.size()), now);
    }

    // ------------------------------------------------------------------------------------- the restore merge (Db.replaceWith)

    /** Reads every row of {@code d} (removal marks and damaged rows too, so nothing is lost). */
    static List<Object[]> read(SQLiteDatabase d) {
        List<Object[]> out = new ArrayList<Object[]>();
        Cursor c = d.rawQuery("SELECT " + COLUMNS + " FROM sleep_manual", null);
        try {
            while (c.moveToNext()) {
                Object[] row = new Object[5];
                for (int i = 0; i < 5; i++) {
                    if (c.isNull(i)) continue;
                    row[i] = i == 3 ? c.getString(i) : i == 4 ? (Object) c.getLong(i) : (Object) c.getDouble(i);
                }
                if (row[0] != null) out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Writes {@code rows} into {@code d} in one transaction, each replacing the row with the same key, so the phone's rows win
     * over the backup's, removal marks included; a row only the backup has stays. Twice gives the same.
     */
    static void merge(SQLiteDatabase d, List<Object[]> rows) throws java.io.IOException {
        d.beginTransaction();
        try {
            for (Object[] r : rows) d.execSQL("INSERT OR REPLACE INTO sleep_manual(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)", r);
            Db.mergeStep(Db.MERGE_HAND);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
