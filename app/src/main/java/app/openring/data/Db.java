package app.openring.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.openring.core.EventCodec;
import app.openring.core.RingEvent;
import app.openring.core.TimeLine;

/**
 * Local SQLite store. Raw ring events are kept losslessly (so better decoders can re process old
 * data) and dated against wall clock anchors per boot epoch; computed nights and days are cached
 * as JSON. Nothing leaves the phone unless you export it.
 */
public final class Db extends SQLiteOpenHelper {
    static final int VERSION = 6;
    private static Db instance;
    public static final String REAL = "openring.db", DEMO = "openring-demo.db";
    /** The database file in use: the real history, or the demo history in demo mode. */
    public final String fileName;

    public static synchronized Db get(Context c) {
        String name = Prefs.of(c).demo() ? DEMO : REAL;
        if (instance != null && !instance.fileName.equals(name)) {
            instance.close();
            instance = null;
        }
        if (instance == null) {
            if (REAL.equals(name)) recoverSwap(c);
            instance = new Db(c.getApplicationContext(), name);
        }
        return instance;
    }

    /**
     * A left over {@code openring.db.old} means the last {@link #replaceWith} never finished (the
     * app was stopped in the middle): the file next to it may be missing or half copied, so the
     * kept history comes back. Runs before the real file is opened; never throws.
     */
    static synchronized void recoverSwap(Context c) {
        java.io.File dst = c.getDatabasePath(REAL);
        String old = dst.getPath() + ".old";
        if (!new java.io.File(old).exists()) return;
        try {
            for (String s : SIDE) new java.io.File(dst.getPath() + s).delete();
            for (String s : SIDE) move(old + s, dst.getPath() + s);
        } catch (java.io.IOException e) {
            // tried again before the next open or swap; the kept file is never deleted here
        }
    }

    private Db(Context c, String name) {
        super(c, name, null, VERSION);
        fileName = name;
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, epoch INTEGER NOT NULL, tag INTEGER NOT NULL, ds INTEGER NOT NULL,"
                + " body BLOB NOT NULL, captured INTEGER NOT NULL, unix REAL, UNIQUE(tag, ds, body) ON CONFLICT IGNORE)");
        db.execSQL("CREATE INDEX ev_tag_unix ON events(tag, unix)");
        db.execSQL("CREATE INDEX ev_epoch_ds ON events(epoch, ds)");
        db.execSQL("CREATE TABLE anchors (epoch INTEGER NOT NULL, ds INTEGER NOT NULL, unix INTEGER NOT NULL, source TEXT NOT NULL,"
                + " PRIMARY KEY(epoch, ds, source))");
        db.execSQL("CREATE TABLE nights (start REAL PRIMARY KEY, stop REAL NOT NULL, day TEXT NOT NULL, main INTEGER NOT NULL,"
                + " score INTEGER, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX nights_day ON nights(day)");
        db.execSQL("CREATE TABLE days (day TEXT PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE battery (unix INTEGER PRIMARY KEY, pct INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)");
        upgradeTo2(db);
        upgradeTo3(db);
        upgradeTo4(db);
        upgradeTo5(db);
        upgradeTo6(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) upgradeTo2(db);
        if (oldV < 3) upgradeTo3(db);
        if (oldV < 4) upgradeTo4(db);
        if (oldV < 5) upgradeTo5(db);
        if (oldV < 6) upgradeTo6(db);
    }

    /**
     * v6: the time ranges of Android's usage log that were really read (issue #131), so days the log
     * no longer had when it was read count as unknown, not as 0. Older databases (and restored older
     * backups) get one range from their oldest to their newest stored screen session: what was shown
     * before stays, a gap after it shows at the next read.
     */
    private static void upgradeTo6(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS screen_reads (start REAL PRIMARY KEY, stop REAL NOT NULL)");
        // no HAVING without GROUP BY: Android's SQLite accepts that only from 3.39 (Android 14); an
        // aggregate over an empty table gives one row of NULLs, which the WHERE leaves out
        db.execSQL("INSERT OR REPLACE INTO screen_reads(start, stop) SELECT a, b FROM (SELECT MIN(start) AS a, MAX(stop) AS b FROM screen)"
                + " WHERE a IS NOT NULL AND b IS NOT NULL");
    }

    /** v5: the phone's time zone over time (issue #22), so each night keeps the zone it was slept in. */
    private static void upgradeTo5(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS zones (start REAL PRIMARY KEY, stop REAL NOT NULL, id TEXT NOT NULL)");
    }

    /** v4: when each app was in front (package name, unix seconds), to leave chosen apps out of screen time. */
    private static void upgradeTo4(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS apps (pkg TEXT NOT NULL, start REAL NOT NULL, stop REAL NOT NULL, PRIMARY KEY (pkg, start))");
        db.execSQL("CREATE INDEX IF NOT EXISTS apps_stop ON apps(stop)");
    }

    /** v3: the phone's screen sessions (issue #13), unix seconds; a session still running is updated later. */
    private static void upgradeTo3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS screen (start REAL PRIMARY KEY, stop REAL NOT NULL)");
    }

    /** v2: boot segments, so each reboot of the ring is dated on its own. */
    private static void upgradeTo2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE events ADD COLUMN seg INTEGER");
        db.execSQL("CREATE INDEX ev_seg_ds ON events(seg, ds)");
        db.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES ('analyzed_until', '0')");
    }

    // ------------------------------------------------------------------------------- kv

    public String kv(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?", new String[] {k});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : def;
        } finally {
            c.close();
        }
    }

    public long kvLong(String k, long def) {
        try {
            return Long.parseLong(kv(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void put(String k, Object v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v == null ? null : String.valueOf(v));
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // --------------------------------------------------------------------------- events

    /** Insert one drained batch and the anchors it carries. Returns rows actually inserted. */
    public int insertBatch(List<RingEvent> events, long epoch, long captured) {
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            SQLiteStatement st = db.compileStatement("INSERT INTO events(epoch, tag, ds, body, captured) VALUES (?,?,?,?,?)");
            for (RingEvent e : events) {
                st.bindLong(1, epoch);
                st.bindLong(2, e.tag);
                st.bindLong(3, e.ds);
                st.bindBlob(4, e.body);
                st.bindLong(5, captured);
                if (st.executeInsert() != -1) inserted++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    /**
     * Phone anchor: the newest drained event happened "now". Stored as a time_sync row (open_health
     * does the same) so the whole time mapping can be rebuilt from the events table alone.
     */
    public void insertPhoneAnchor(long epoch, long ds, long unix) {
        List<RingEvent> one = new ArrayList<RingEvent>();
        one.add(new RingEvent(EventCodec.TIME_SYNC, ds, TimeLine.phoneAnchorBody(unix)));
        insertBatch(one, epoch, unix);
    }

    /** Result of {@link #rebuildTime}. */
    public static final class TimeReport {
        public int segments, anchoredSegments;
        public long datedBefore, datedAfter, total;
        /** Rows whose segment or date was written (all other rows already held the result). */
        public long changed;
        /** Rows of the events table this run read (issue #220). */
        public long rowsRead;
        public List<TimeLine.Segment> segs;
    }

    /** Rows read per query by {@link #rebuildTime}; small pages keep Android's cursor window cheap. */
    static final int PAGE = 20000;

    /**
     * Work out the boot segment and wall clock time of every event from scratch, then write only
     * the rows whose stored values differ (issue #21). Runs after every sync and repairs history
     * that an interrupted sync left undated. The result is the same, row for row and bit for bit,
     * as the earlier full rewrite (all rows undated, then each segment dated in turn with
     * {@code unix = anchorUnix + (ds - anchorDs) / 10.0}); the pipeline test checks that.
     */
    public synchronized TimeReport rebuildTime(long nowUnix) {
        SQLiteDatabase db = getWritableDatabase();
        TimeReport rep = new TimeReport();
        // read and write in one transaction, so an insert from another thread waits instead of
        // landing between the scan and the writes
        db.beginTransaction();
        try {
            int n = (int) countOf(db, "SELECT COUNT(*) FROM events");
            long[] id = new long[n], epoch = new long[n], ds = new long[n], anchor = new long[n];
            boolean[] phone = new boolean[n];
            int[] oldSeg = new int[n];
            double[] oldUnix = new double[n];
            int i = 0;
            long last = -1;
            while (i < n) {
                // pages by id: one cursor over the whole table makes Android step over all rows
                // before it again each time it refills its window
                Cursor c = db.rawQuery("SELECT id, epoch, tag, ds, CASE WHEN tag IN (66, 133) THEN body END, seg, unix FROM events"
                        + " WHERE id > ? ORDER BY id LIMIT " + PAGE, new String[] {String.valueOf(last)});
                int got = 0;
                try {
                    while (i < n && c.moveToNext()) {
                        id[i] = c.getLong(0);
                        epoch[i] = c.getLong(1);
                        int tag = c.getInt(2);
                        ds[i] = c.getLong(3);
                        anchor[i] = -1;
                        if (!c.isNull(4)) {
                            byte[] body = c.getBlob(4);
                            anchor[i] = EventCodec.anchorUnix(tag, body);
                            phone[i] = tag == EventCodec.TIME_SYNC && EventCodec.isPhoneAnchor(body);
                        }
                        oldSeg[i] = c.isNull(5) ? -1 : c.getInt(5);
                        oldUnix[i] = c.isNull(6) ? Double.NaN : c.getDouble(6);
                        if (!c.isNull(6)) rep.datedBefore++;
                        last = id[i];
                        i++;
                        got++;
                    }
                } finally {
                    c.close();
                }
                if (got == 0) break;
            }
            n = i;
            rep.total = n;
            rep.rowsRead = n;
            List<TimeLine.Segment> segs = TimeLine.build(trim(id, n), trim(epoch, n), trim(ds, n), trim(anchor, n), trim(phone, n), nowUnix);
            epoch = null;
            anchor = null;
            phone = null;
            rep.segs = segs;
            rep.segments = segs.size();
            // replay the full rewrite in memory, in its order: everything undated, then per segment
            // its rows and stray phone anchors get the segment, then the dates of its regions
            int[] seg = new int[n];
            double[] unix = new double[n];
            java.util.Arrays.fill(seg, -1);
            java.util.Arrays.fill(unix, Double.NaN);
            for (TimeLine.Segment sg : segs) {
                int lo = java.util.Arrays.binarySearch(id, 0, n, sg.firstId), hi = java.util.Arrays.binarySearch(id, 0, n, sg.lastId);
                for (int k = lo; k <= hi; k++) seg[k] = sg.no;
                int[] stray = new int[sg.strayIds.size()];
                for (int s = 0; s < stray.length; s++) {
                    stray[s] = java.util.Arrays.binarySearch(id, 0, n, sg.strayIds.get(s));
                    seg[stray[s]] = sg.no;
                }
                if (sg.anchors.isEmpty()) continue;
                rep.anchoredSegments++;
                List<long[]> regions = TimeLine.regions(sg);
                for (int k = lo; k <= hi; k++) unix[k] = dateIn(regions, ds[k], unix[k]);
                for (int k : stray) unix[k] = dateIn(regions, ds[k], unix[k]);
            }
            // an event cannot happen well after it was downloaded, nor before 2015
            long ceiling = nowUnix + 6 * 3600;
            SQLiteStatement up = db.compileStatement("UPDATE events SET seg = ?, unix = ? WHERE id = ?");
            try {
                for (int k = 0; k < n; k++) {
                    if (unix[k] > ceiling || unix[k] < 1420000000) unix[k] = Double.NaN;
                    if (!Double.isNaN(unix[k])) rep.datedAfter++;
                    // exact: SQLite and Java do the same double steps, and a stored REAL reads back
                    // unchanged; a value off by a bit is only written again, never kept wrong
                    if (seg[k] == oldSeg[k] && Double.doubleToLongBits(unix[k]) == Double.doubleToLongBits(oldUnix[k])) continue;
                    if (seg[k] < 0) up.bindNull(1);
                    else up.bindLong(1, seg[k]);
                    if (Double.isNaN(unix[k])) up.bindNull(2);
                    else up.bindDouble(2, unix[k]);
                    up.bindLong(3, id[k]);
                    up.executeUpdateDelete();
                    rep.changed++;
                }
            } finally {
                up.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return rep;
    }

    /**
     * Date of {@code ds} from the region that holds it ({lo, hi, anchorDs, anchorUnix}, sorted,
     * hi exclusive), computed as the SQL did; {@code keep} when no region holds it.
     */
    static double dateIn(List<long[]> regions, long ds, double keep) {
        int lo = 0, hi = regions.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (regions.get(mid)[0] <= ds) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best < 0) return keep;
        long[] r = regions.get(best);
        return ds < r[1] ? r[3] + (ds - r[2]) / 10.0 : keep;
    }

    private static long[] trim(long[] a, int n) {
        return a.length == n ? a : java.util.Arrays.copyOf(a, n);
    }

    private static boolean[] trim(boolean[] a, int n) {
        return a.length == n ? a : java.util.Arrays.copyOf(a, n);
    }

    private static long countOf(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    public long count(String sql) {
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Newest dated events of the given types since {@code sinceUnix}, newest first. */
    public List<Row> latest(int[] tags, double sinceUnix, int limit) {
        StringBuilder in = new StringBuilder();
        for (int t : tags) {
            if (in.length() > 0) in.append(',');
            in.append(t);
        }
        List<Row> out = new ArrayList<Row>();
        Cursor c = getReadableDatabase().rawQuery("SELECT tag, ds, unix, body FROM events WHERE tag IN (" + in
                + ") AND unix >= ? ORDER BY unix DESC LIMIT " + limit, new String[] {String.valueOf(sinceUnix)});
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.tag = c.getInt(0);
                r.ds = c.getLong(1);
                r.unix = c.getDouble(2);
                r.body = c.getBlob(3);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** The time of the newest dated event of the given types before {@code beforeUnix}, NaN when there is none. */
    public double newestBefore(int[] tags, double beforeUnix) {
        StringBuilder in = new StringBuilder();
        for (int t : tags) {
            if (in.length() > 0) in.append(',');
            in.append(t);
        }
        Cursor c = getReadableDatabase().rawQuery("SELECT unix FROM events WHERE tag IN (" + in
                + ") AND unix < ? ORDER BY unix DESC LIMIT 1", new String[] {String.valueOf(beforeUnix)});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN;
        } finally {
            c.close();
        }
    }

    /** {tag, total, dated, first unix, last unix} per event type, most frequent first. */
    public List<double[]> tagStats() {
        List<double[]> out = new ArrayList<double[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT tag, COUNT(*), COUNT(unix), MIN(unix), MAX(unix) FROM events GROUP BY tag ORDER BY COUNT(*) DESC", null);
        try {
            while (c.moveToNext()) {
                out.add(new double[] {c.getInt(0), c.getLong(1), c.getLong(2), c.isNull(3) ? Double.NaN : c.getDouble(3), c.isNull(4) ? Double.NaN : c.getDouble(4)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** One stored event row. */
    public static final class Row {
        public int tag;
        public long ds;
        public double unix;
        public byte[] body;
    }

    public List<Row> events(int[] tags, double fromUnix, double toUnix) {
        StringBuilder in = new StringBuilder();
        for (int t : tags) {
            if (in.length() > 0) in.append(',');
            in.append(t);
        }
        List<Row> out = new ArrayList<Row>();
        Cursor c = getReadableDatabase().rawQuery("SELECT tag, ds, unix, body FROM events WHERE tag IN (" + in
                + ") AND unix >= ? AND unix < ? ORDER BY unix", new String[] {String.valueOf(fromUnix), String.valueOf(toUnix)});
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.tag = c.getInt(0);
                r.ds = c.getLong(1);
                r.unix = c.getDouble(2);
                r.body = c.getBlob(3);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Most recent events for the raw event viewer. */
    public List<Row> recentEvents(int limit) {
        List<Row> out = new ArrayList<Row>();
        Cursor c = getReadableDatabase().rawQuery("SELECT tag, ds, unix, body FROM events ORDER BY id DESC LIMIT " + limit, null);
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.tag = c.getInt(0);
                r.ds = c.getLong(1);
                r.unix = c.isNull(2) ? Double.NaN : c.getDouble(2);
                r.body = c.getBlob(3);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public double[] datedRange() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(unix), MAX(unix), COUNT(*) FROM events WHERE unix IS NOT NULL", null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) return new double[] {c.getDouble(0), c.getDouble(1), c.getLong(2)};
            return null;
        } finally {
            c.close();
        }
    }

    public long eventCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Every undated event arrived after the last dating (Find my ring stores its quick download undated, issue #192): the
     * next analysis dates them, so nothing waits for a missing time record. False when an event the last dating saw is
     * still undated, or no analysis ran yet.
     */
    public boolean undatedAllNew() {
        long seen = kvLong("event_count", -1);
        if (seen < 0) return false;
        long undated = undatedCount();
        return undated > 0 && undated == eventCount() - seen;
    }

    public long undatedCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE unix IS NULL", null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    // ---------------------------------------------------------------------- nights, days

    /** Deletes the stored nights that end at or after {@code from} and start before {@code to}, then stores {@code rows}. */
    public void replaceNights(double from, double to, List<Object[]> rows) {
        replaceNights(from, to, rows, null);
    }

    /**
     * As {@link #replaceNights(double, double, List)}, and in the same transaction sets the main flag
     * (column and JSON) of stored nights that stay, by start ({@code keptMain} may be null; issue #132).
     */
    public void replaceNights(double from, double to, List<Object[]> rows, Map<Double, Boolean> keptMain) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (keptMain != null) {
                for (Map.Entry<Double, Boolean> e : keptMain.entrySet()) {
                    Cursor c = db.rawQuery("SELECT json FROM nights WHERE start = ?", new String[] {String.valueOf(e.getKey())});
                    String js;
                    try {
                        js = c.moveToFirst() ? c.getString(0) : null;
                    } finally {
                        c.close();
                    }
                    JSONObject o = js == null ? null : Json.parse(js);
                    if (o == null) continue;
                    Json.put(o, "main", e.getValue().booleanValue());
                    db.execSQL("UPDATE nights SET main = ?, json = ? WHERE start = ?", new Object[] {e.getValue() ? 1 : 0, o.toString(), e.getKey()});
                }
            }
            db.execSQL("DELETE FROM nights WHERE stop >= ? AND start < ?", new Object[] {from, to});
            for (Object[] r : rows) {
                ContentValues cv = new ContentValues();
                cv.put("start", (Double) r[0]);
                cv.put("stop", (Double) r[1]);
                cv.put("day", (String) r[2]);
                cv.put("main", (Integer) r[3]);
                cv.put("score", (Integer) r[4]);
                cv.put("json", (String) r[5]);
                db.insertWithOnConflict("nights", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The earliest start of a stored night that begins before {@code unix} and ends at or after it
     * (so {@link #replaceNights} from {@code unix} would delete it), NaN when none does (issue #132).
     */
    public double nightAcross(double unix) {
        String u = String.valueOf(unix);
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(start) FROM nights WHERE start < ? AND stop >= ?", new String[] {u, u});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN;
        } finally {
            c.close();
        }
    }

    /** Stored nights of {@code day} that end before {@code unix}: {start, asleep minutes, main 1 or 0} (issue #132). */
    public List<double[]> nightsOfDayBefore(String day, double unix) {
        List<double[]> out = new ArrayList<double[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, main, json FROM nights WHERE day = ? AND stop < ? ORDER BY start",
                new String[] {day, String.valueOf(unix)});
        try {
            while (c.moveToNext()) {
                JSONObject o = Json.parse(c.getString(2));
                out.add(new double[] {c.getDouble(0), o == null ? 0 : o.optDouble("asleep_min", 0), c.getInt(1)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** The days of stored nights ending at or after {@code unix} (issue #132). */
    public List<String> nightDaysFrom(double unix) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT day FROM nights WHERE stop >= ?", new String[] {String.valueOf(unix)});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** {start, stop} of stored nights ending in [from, to), by start (issue #132). */
    public List<double[]> nightSpans(double from, double to) {
        List<double[]> out = new ArrayList<double[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, stop FROM nights WHERE stop >= ? AND stop < ? ORDER BY start",
                new String[] {String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) out.add(new double[] {c.getDouble(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        return out;
    }

    /** The earliest day of a stored night ending at or after {@code unix}, null when there is none. */
    public String firstNightDay(double unix) {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(day) FROM nights WHERE stop >= ?", new String[] {String.valueOf(unix)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** Nights as JSON strings ordered by start, for days in [fromDay, toDay]. */
    public List<String> nightsJson(String fromDay, String toDay, boolean mainOnly) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery("SELECT json FROM nights WHERE day >= ? AND day <= ?" + (mainOnly ? " AND main=1" : "")
                + " ORDER BY start", new String[] {fromDay, toDay});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** Days before and after a date that the "Recent nights" strip shows (issue #98). */
    public static final int RECENT_DAYS = 30;

    /**
     * Main nights for the "Recent nights" strip of {@code date}, ordered by start: from {@link #RECENT_DAYS}
     * days before it to as many after it, never past the day after {@code today} (a night in a zone ahead can
     * end there). An old date loads no more nights than one in the middle of the history (issue #98).
     */
    public List<String> recentNights(java.time.LocalDate date, java.time.LocalDate today) {
        java.time.LocalDate to = date.plusDays(RECENT_DAYS), cap = today.plusDays(1);
        if (to.isAfter(cap)) to = cap.isBefore(date) ? date : cap;
        return nightsJson(date.minusDays(RECENT_DAYS).toString(), to.toString(), true);
    }

    /** True when any main night is stored (the Sleep tab offers Further analysis then, issue #98). */
    public boolean anyMainNight() {
        return count("SELECT EXISTS (SELECT 1 FROM nights WHERE main = 1)") > 0;
    }

    /** The nights of one day ordered by start, each as {start, main (Boolean), json} (issue #139). */
    public List<Object[]> nightsOfDay(String day) {
        List<Object[]> out = new ArrayList<Object[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, main, json FROM nights WHERE day = ? ORDER BY start", new String[] {day});
        try {
            while (c.moveToNext()) out.add(new Object[] {c.getDouble(0), c.getInt(1) == 1, c.getString(2)});
        } finally {
            c.close();
        }
        return out;
    }

    public void putDay(String day, String json) {
        ContentValues cv = new ContentValues();
        cv.put("day", day);
        cv.put("json", json);
        getWritableDatabase().insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Removes the stored summary of {@code day}; true when there was one (issue #58). */
    public boolean deleteDay(String day) {
        return getWritableDatabase().delete("days", "day = ?", new String[] {day}) > 0;
    }

    /** The stored days after {@code day}, oldest first (issue #58). */
    public List<String> daysAfter(String day) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery("SELECT day FROM days WHERE day > ? ORDER BY day", new String[] {day});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * True when an undated event follows the newest dated one in the ring's order (row id): its boot has no
     * usable time record yet (for example a sync cut off before its phone anchor), so it may belong to any
     * time after the newest dated event (issue #58). Older undated boots (ring restarts without a time
     * record) do not count, and neither do time records themselves (time_sync 66, rtc_beacon 133): they
     * hold no data for a day, and a phone anchor that fits no boot stays undated for good. Walks back from
     * the newest row only.
     */
    public boolean undatedAfterNewest() {
        Cursor c = getReadableDatabase().rawQuery("SELECT EXISTS (SELECT 1 FROM events WHERE unix IS NULL AND tag NOT IN (66, 133) AND id >"
                + " (SELECT id FROM events WHERE unix IS NOT NULL ORDER BY id DESC LIMIT 1))", null);
        try {
            return c.moveToFirst() && c.getInt(0) == 1;
        } finally {
            c.close();
        }
    }

    public String dayJson(String day) {
        Cursor c = getReadableDatabase().rawQuery("SELECT json FROM days WHERE day=?", new String[] {day});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public List<String[]> daysJson(String fromDay, String toDay) {
        List<String[]> out = new ArrayList<String[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT day, json FROM days WHERE day >= ? AND day <= ? ORDER BY day", new String[] {fromDay, toDay});
        try {
            while (c.moveToNext()) out.add(new String[] {c.getString(0), c.getString(1)});
        } finally {
            c.close();
        }
        return out;
    }

    public String latestDay() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MAX(day) FROM days", null);
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    // --------------------------------------------------------------------------- screen

    /** Store screen sessions {start, end}; a session with a known start replaces the stored one. */
    public int putScreen(List<double[]> sessions) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (double[] s : sessions) {
                ContentValues cv = new ContentValues();
                cv.put("start", s[0]);
                cv.put("stop", s[1]);
                db.insertWithOnConflict("screen", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return sessions.size();
    }

    /** Screen sessions that overlap [from, to), oldest first. */
    public List<double[]> screen(double from, double to) {
        List<double[]> out = new ArrayList<double[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, stop FROM screen WHERE stop > ? AND start < ? ORDER BY start",
                new String[] {String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) out.add(new double[] {c.getDouble(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Store the ranges each app was in front. A range joins every stored range of the same app it
     * overlaps or touches into one row, so reading the same time twice stores the same rows as reading
     * it once (issue #71, as {@code ScreenTime.store}).
     */
    public void putApps(java.util.Map<String, List<double[]>> apps) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<String, List<double[]>> e : apps.entrySet()) {
                for (double[] s : e.getValue()) {
                    if (!(s[1] > s[0])) continue;
                    double a = s[0], b = s[1];
                    // grow the range over every row it reaches (old rows may overlap each other too); the
                    // index on stop keeps this to the rows since the range starts, not the app's whole history
                    boolean grew = true;
                    while (grew) {
                        grew = false;
                        Cursor c = db.rawQuery("SELECT start, stop FROM apps INDEXED BY apps_stop WHERE stop >= ? AND pkg = ? AND start <= ?",
                                new String[] {String.valueOf(a), e.getKey(), String.valueOf(b)});
                        try {
                            while (c.moveToNext()) {
                                if (c.getDouble(0) < a) {
                                    a = c.getDouble(0);
                                    grew = true;
                                }
                                if (c.getDouble(1) > b) {
                                    b = c.getDouble(1);
                                    grew = true;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                    db.execSQL("DELETE FROM apps INDEXED BY apps_stop WHERE stop >= ? AND pkg = ? AND start <= ?", new Object[] {a, e.getKey(), b});
                    ContentValues cv = new ContentValues();
                    cv.put("pkg", e.getKey());
                    cv.put("start", a);
                    cv.put("stop", b);
                    db.insertWithOnConflict("apps", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Ranges of apps in front that overlap [from, to), per package name, oldest first. Rows of one app
     * stored overlapping by older versions are merged here (issue #71), so no moment counts twice;
     * the stored rows stay as they are.
     */
    public java.util.Map<String, List<double[]>> apps(double from, double to) {
        return app.openring.core.analysis.ScreenTime.merge(appRows(from, to));
    }

    /** The stored rows as they are, for {@link #apps} and the tests. */
    java.util.Map<String, List<double[]>> appRows(double from, double to) {
        java.util.Map<String, List<double[]>> out = new java.util.LinkedHashMap<String, List<double[]>>();
        Cursor c = getReadableDatabase().rawQuery("SELECT pkg, start, stop FROM apps WHERE stop > ? AND start < ? ORDER BY start",
                new String[] {String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) {
                List<double[]> l = out.get(c.getString(0));
                if (l == null) out.put(c.getString(0), l = new ArrayList<double[]>());
                l.add(new double[] {c.getDouble(1), c.getDouble(2)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Forget screen sessions and app ranges that end after {@code unix}, to read them again with new rules. */
    public void deleteScreenSince(double unix) {
        getWritableDatabase().execSQL("DELETE FROM screen WHERE stop > ?", new Object[] {unix});
        getWritableDatabase().execSQL("DELETE FROM apps WHERE stop > ?", new Object[] {unix});
    }

    /** Start of the oldest stored app range, or -1 (days before it have screen time but no app split). */
    public double appsStart() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(start) FROM apps", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : -1;
        } finally {
            c.close();
        }
    }

    /** Start of the newest stored app range, or -1: the next read of the app log starts there (issue #71). */
    public double appsNewest() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MAX(start) FROM apps", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : -1;
        } finally {
            c.close();
        }
    }

    /**
     * Note that Android's usage log was read from {@code from} to {@code to} (issue #131); ranges that
     * overlap or touch become one row, so the table only grows by one row per real gap.
     */
    public void putScreenRead(double from, double to) {
        if (!(to > from)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<double[]> all = screenReads();
            all.add(new double[] {from, to});
            db.execSQL("DELETE FROM screen_reads");
            for (double[] r : app.openring.core.analysis.ScreenTime.mergeRanges(all)) {
                db.execSQL("INSERT INTO screen_reads(start, stop) VALUES (?, ?)", new Object[] {r[0], r[1]});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The ranges of Android's usage log that were read, oldest first (merged; empty before any read). */
    public List<double[]> screenReads() {
        List<double[]> out = new ArrayList<double[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, stop FROM screen_reads ORDER BY start", null);
        try {
            while (c.moveToNext()) out.add(new double[] {c.getDouble(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        return out;
    }

    /** Start of the newest (first = false) or oldest (first = true) stored screen session, or -1. */
    public double screenStart(boolean first) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + (first ? "MIN" : "MAX") + "(start) FROM screen", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : -1;
        } finally {
            c.close();
        }
    }

    /**
     * New input for time already analysed (screen use, issue #13): the next analysis starts again two
     * days before {@code unix} instead of only redoing the last two days.
     */
    public void reanalyseFrom(double unix) {
        long until = kvLong("analyzed_until", 0);
        if (until > 0 && unix < until) put("analyzed_until", Math.max(1, (long) unix));
    }

    // ---------------------------------------------------------------------------- zones

    /**
     * Note the phone's time zone {@code id} at {@code unix} (issue #22): the same zone as the newest
     * row extends it, another zone opens a new row (never before the newest row ends, so a phone
     * clock set back cannot make rows overlap). The very first row starts at 0, so everything before
     * belongs to it. Returns the end of the row before when a new row was opened, NaN otherwise.
     */
    public synchronized double noteZone(double unix, String id) {
        if (id == null || id.isEmpty()) return Double.NaN;
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT start, stop, id FROM zones ORDER BY start DESC LIMIT 1", null);
        double start = Double.NaN, stop = Double.NaN;
        String last = null;
        try {
            if (c.moveToFirst()) {
                start = c.getDouble(0);
                stop = c.getDouble(1);
                last = c.getString(2);
            }
        } finally {
            c.close();
        }
        if (last == null) {
            db.execSQL("INSERT OR REPLACE INTO zones(start, stop, id) VALUES (0, ?, ?)", new Object[] {unix, id});
            return Double.NaN;
        }
        if (last.equals(id)) {
            if (unix > stop) db.execSQL("UPDATE zones SET stop = ? WHERE start = ?", new Object[] {unix, start});
            return Double.NaN;
        }
        double from = Math.max(unix, stop);
        if (from <= start) from = start + 1;
        db.execSQL("INSERT OR REPLACE INTO zones(start, stop, id) VALUES (?, ?, ?)", new Object[] {from, from, id});
        return stop;
    }

    /**
     * The zone id valid at {@code unix}: the row that holds it; between two rows the later one (the
     * first note after it, "the last time zone counts for the whole night"); after the newest row the
     * newest. Null when no zone was ever noted (demo mode, or before the first run of 1.26).
     */
    public String zoneAt(double unix) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM zones WHERE stop >= ? ORDER BY start LIMIT 1", new String[] {String.valueOf(unix)});
        try {
            if (c.moveToFirst()) return c.getString(0);
        } finally {
            c.close();
        }
        c = getReadableDatabase().rawQuery("SELECT id FROM zones ORDER BY start DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * When {@code unix} lies in a gap between two rows: the id of the row before the gap (the zone
     * you were in when last seen before it), else null.
     */
    public String zoneBeforeGap(double unix) {
        String u = String.valueOf(unix);
        if (count("SELECT COUNT(*) FROM zones WHERE start <= " + u + " AND stop >= " + u) > 0) return null;
        if (count("SELECT COUNT(*) FROM zones WHERE start > " + u) == 0) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM zones WHERE stop < ? ORDER BY start DESC LIMIT 1", new String[] {u});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** All rows {start, stop} with their ids, oldest first (tests and the zone count). */
    public List<Object[]> zones() {
        List<Object[]> out = new ArrayList<Object[]>();
        Cursor c = getReadableDatabase().rawQuery("SELECT start, stop, id FROM zones ORDER BY start", null);
        try {
            while (c.moveToNext()) out.add(new Object[] {c.getDouble(0), c.getDouble(1), c.getString(2)});
        } finally {
            c.close();
        }
        return out;
    }

    public void addBattery(long unix, int pct) {
        ContentValues cv = new ContentValues();
        cv.put("unix", unix);
        cv.put("pct", pct);
        getWritableDatabase().insertWithOnConflict("battery", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** What a database file holds, read without the app's helper (no upgrade, nothing written). */
    public static final class Info {
        /** The file exists and could be read. */
        public boolean exists, ok;
        /** Why it cannot be restored: {@link #DAMAGED} or {@link #NEWER}, null when fine. */
        public String problem;
        /** What the schema check found missing (issue #156), for the log; null when the schema is complete. */
        public String schema;
        public int version;
        public long events;
        /** Stored nights, -1 when they cannot be read. */
        public long nights;
        /** Newest dated event (unix seconds), NaN when nothing is dated. */
        public double newest = Double.NaN;
    }

    public static final String DAMAGED = "damaged", NEWER = "newer";

    /**
     * The schema every Open Ring history has (issue #156): each table, the version that added it and
     * the columns the app reads and writes. {@code events.seg} came with version 2. The first public
     * release already was version 2 with the six base tables, and no version ever dropped one.
     */
    private static final Object[][] SCHEMA = {
        {"events", 1, new String[] {"id", "epoch", "tag", "ds", "body", "captured", "unix"}},
        {"anchors", 1, new String[] {"epoch", "ds", "unix", "source"}},
        {"nights", 1, new String[] {"start", "stop", "day", "main", "score", "json"}},
        {"days", 1, new String[] {"day", "json"}},
        {"battery", 1, new String[] {"unix", "pct"}},
        {"kv", 1, new String[] {"k", "v"}},
        {"screen", 3, new String[] {"start", "stop"}},
        {"apps", 4, new String[] {"pkg", "start", "stop"}},
        {"zones", 5, new String[] {"start", "stop", "id"}},
        {"screen_reads", 6, new String[] {"start", "stop"}},
    };

    /**
     * What is wrong with the schema of {@code d} for a history at {@code version}, or null when it
     * matches. Every table up to that version must be a table (not a view) with its columns; a table
     * of a later version that is already there must have them too, since the upgrade's
     * {@code CREATE TABLE IF NOT EXISTS} keeps it as it is. A column the app does not know that is
     * NOT NULL without a default would make every insert fail, so it is refused as well.
     * Uses only {@code sqlite_master} and {@code PRAGMA table_info}, which SQLite 3.18 has.
     */
    static String schemaProblem(SQLiteDatabase d, int version) {
        for (Object[] t : SCHEMA) {
            String name = (String) t[0];
            int since = (Integer) t[1];
            java.util.List<String> need = new java.util.ArrayList<String>(java.util.Arrays.asList((String[]) t[2]));
            // events.seg: needed from version 2, known (and added by the upgrade) before
            java.util.List<String> known = new java.util.ArrayList<String>(need);
            if ("events".equals(name)) {
                known.add("seg");
                if (version >= 2) need.add("seg");
            }
            String type = first(d, "SELECT type FROM sqlite_master WHERE name = '" + name + "'");
            if (type == null) {
                if (version >= since) return "no table " + name;
                continue;
            }
            if (!"table".equals(type)) return name + " is a " + type;
            java.util.Set<String> have = new java.util.HashSet<String>();
            Cursor c = d.rawQuery("PRAGMA table_info(" + name + ")", null);
            try {
                while (c.moveToNext()) {
                    String col = c.getString(1);
                    have.add(col);
                    // a column the app never fills: NOT NULL (3), no default (4), not part of the key (5)
                    if (!known.contains(col) && c.getInt(3) != 0 && c.isNull(4) && c.getInt(5) == 0) return name + "." + col + " is NOT NULL without a default";
                }
            } finally {
                c.close();
            }
            for (String col : need) if (!have.contains(col)) return "no column " + name + "." + col;
        }
        return null;
    }

    /** Does nothing, so Android's default handler cannot delete a file we only look at. */
    private static final android.database.DatabaseErrorHandler KEEP_FILE = new android.database.DatabaseErrorHandler() {
        @Override
        public void onCorruption(SQLiteDatabase db) {}
    };

    /**
     * Look at a database file on its own, read only and without localized collators, so the file
     * stays byte for byte as it was (a read write open on Android can switch the journal mode or
     * write android_metadata). With {@code check} (a backup's copy) it must pass
     * {@code PRAGMA quick_check}, have a version this app can open and hold every Open Ring table
     * and column of that version ({@link #schemaProblem}, issue #156). Without it (the phone's own
     * history) only the event and night counts and the newest date are read.
     */
    static Info inspect(java.io.File f, boolean check) {
        Info i = new Info();
        if (!f.isFile()) return i;
        i.exists = true;
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP_FILE);
            i.version = d.getVersion();
            if (check) {
                if (!"ok".equals(first(d, "PRAGMA quick_check"))) {
                    i.problem = DAMAGED;
                    return i;
                }
                if (i.version > VERSION) {
                    i.problem = NEWER;
                    return i;
                }
                if (i.version < 1) {
                    i.problem = DAMAGED;
                    return i;
                }
                i.schema = schemaProblem(d, i.version);
                if (i.schema != null) {
                    i.problem = DAMAGED;
                    return i;
                }
            }
            Cursor c = d.rawQuery("SELECT COUNT(*), MAX(unix) FROM events", null);
            try {
                if (c.moveToFirst()) {
                    i.events = c.getLong(0);
                    if (!c.isNull(1)) i.newest = c.getDouble(1);
                }
            } finally {
                c.close();
            }
            try {
                String n = first(d, "SELECT COUNT(*) FROM nights");
                i.nights = n == null ? 0 : Long.parseLong(n);
            } catch (RuntimeException e) {
                i.nights = -1; // only the phone's own file can get here without a nights table
            }
            i.ok = true;
        } catch (RuntimeException e) {
            i.ok = false;
            i.problem = DAMAGED;
        } finally {
            if (d != null) {
                try {
                    d.close();
                } catch (RuntimeException ignored) {
                    // nothing left to do with a file we only looked at
                }
            }
        }
        return i;
    }

    private static String first(SQLiteDatabase d, String sql) {
        Cursor c = d.rawQuery(sql, null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    private static final String[] SIDE = {"", "-wal", "-shm", "-journal"};

    /**
     * Swap the real database for a restored copy (a backup) that {@link #inspect} accepted and
     * found {@code expectedEvents} events in. The old file is kept as {@code openring.db.old} until
     * the new one opens through the helper (which also upgrades an older copy), holds exactly
     * that many events (Android's default error handler can replace a broken file with an empty
     * one that "opens") and has every table and column of this version. Otherwise the old file comes back and an IOException tells the caller
     * nothing changed. If the app is stopped in the middle, {@link #recoverSwap} puts the old file
     * back before the next open.
     *
     * Nobody else writes meanwhile: {@link Backup#finish} calls this only through
     * {@code core/RingGate.whenFree} (issue #219), with the ring lock held, no sync under way (a
     * download keeps its own Db object, which would reopen this file by name) and the analysis
     * lock held, so no ring task or analysis stores into either file during the swap.
     *
     * Before the rename the real file is checkpointed ({@code wal_checkpoint(TRUNCATE)}), so all
     * of the old history sits in {@code openring.db} itself and its side files hold nothing that
     * {@link #recoverSwap} could lose.
     */
    public static synchronized void replaceWith(Context c, java.io.File src, long expectedEvents) throws java.io.IOException {
        java.io.File dst = c.getDatabasePath(REAL);
        dst.getParentFile().mkdirs();
        String old = dst.getPath() + ".old";
        // an unfinished earlier swap: its kept history comes back first, it is never deleted
        recoverSwap(c);
        if (new java.io.File(old).exists()) throw new java.io.IOException("an earlier restore could not be undone");
        // side files of a .old that is gone (a cleanup cut off half way) belong to nothing
        for (int i = 1; i < SIDE.length; i++) new java.io.File(old + SIDE[i]).delete();
        if (instance != null && !REAL.equals(instance.fileName)) {
            instance.close();
            instance = null;
        }
        if (instance == null && dst.exists()) instance = new Db(c.getApplicationContext(), REAL);
        if (instance != null) {
            try {
                Cursor cp = instance.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
                try {
                    cp.moveToFirst();
                } finally {
                    cp.close();
                }
            } catch (RuntimeException e) {
                // a history that cannot be opened has nothing left in its side files to keep
            }
            instance.close();
            instance = null;
        }
        try {
            for (String s : SIDE) move(dst.getPath() + s, old + s);
        } catch (java.io.IOException e) {
            recoverSwap(c);
            throw e;
        }
        try {
            copy(src, dst);
            instance = new Db(c.getApplicationContext(), REAL);
            long n = instance.eventCount();
            if (n != expectedEvents) throw new java.io.IOException("the restored history holds " + n + " events, expected " + expectedEvents);
            // opened and upgraded: it must now have every table and column of this version (issue #156)
            String missing = schemaProblem(instance.getReadableDatabase(), VERSION);
            if (missing != null) throw new java.io.IOException("the restored history is incomplete: " + missing);
        } catch (java.io.IOException | RuntimeException e) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (RuntimeException ignored) {
                    // the new file is deleted next anyway
                }
                instance = null;
            }
            recoverSwap(c);
            throw new java.io.IOException("the restored history did not open", e);
        }
        // side files first, the main .old last: while it exists, recoverSwap treats the swap as unfinished
        for (int i = 1; i < SIDE.length; i++) new java.io.File(old + SIDE[i]).delete();
        new java.io.File(old).delete();
    }

    /** Rename when the file exists; nothing to do when it does not. */
    private static void move(String from, String to) throws java.io.IOException {
        java.io.File f = new java.io.File(from);
        if (f.exists() && !f.renameTo(new java.io.File(to))) throw new java.io.IOException("could not rename " + f.getName());
    }

    private static void copy(java.io.File src, java.io.File dst) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(src);
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** Wipe everything (keeps nothing), in one transaction: a failure part way leaves everything as it was. */
    public void resetAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String t : new String[] {"events", "anchors", "nights", "days", "battery", "kv", "screen", "apps", "zones", "screen_reads"}) db.execSQL("DELETE FROM " + t);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Deletes every stored night and day summary, for Re analyse all. Only called inside the analysis's own
     * {@link #inTransaction}, so the old results stay until the new ones are stored with them (issue #230).
     */
    public void clearResults() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM nights");
        db.execSQL("DELETE FROM days");
    }

    /**
     * Runs {@code r} as one transaction (issue #230): everything it writes is stored together when it returns, and
     * nothing of it when it throws anything (also an Error) or the app is ended meanwhile. Reads of this thread see
     * its own writes; other connections (the screens) see the state before until it is committed. Writes from other
     * threads wait until it ends, so {@code r} must not wait for them (nor call a method synchronized on this Db).
     */
    public void inTransaction(Runnable r) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            r.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ------------------------------------------------- a long analysis and the ring tasks (issue #230)

    private final Object ringGate = new Object();
    /** Ring tasks storing what they read right now (sync download, Live, factory reset). */
    private int ringWriters;
    /** A long analysis has the results open in its transaction; ring tasks do not start storing meanwhile. */
    private boolean longRun;

    /**
     * A ring task that will store what it reads (a sync download, Live, factory reset) starts: true, and it calls
     * {@link #releaseForRing} when it is done. False while a long analysis replaces the results: every write would
     * wait minutes for it, with the ring held, so the task waits before it takes the ring or does not start.
     */
    public boolean holdForRing() {
        synchronized (ringGate) {
            if (longRun) return false;
            ringWriters++;
            return true;
        }
    }

    /** The ring task of {@link #holdForRing} stores nothing more. */
    public void releaseForRing() {
        synchronized (ringGate) {
            if (ringWriters > 0) ringWriters--;
            ringGate.notifyAll();
        }
    }

    /** A long analysis replaces the results now (a sync waits for it, Live does not start). */
    public boolean longRun() {
        synchronized (ringGate) {
            return longRun;
        }
    }

    /** Waits at most {@code ms} for a long analysis to end; true when none runs (any more). */
    public boolean awaitLongRun(long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        synchronized (ringGate) {
            while (longRun) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return false;
                ringGate.wait(left);
            }
            return true;
        }
    }

    /**
     * A long analysis starts: from now on no ring task starts storing, and this waits for those storing right now to
     * end, so none of them can hang on the analysis's transaction with the ring held. Returns how many it waited for.
     * Called before the transaction opens, never inside it; {@link #endLongRun} follows in a finally.
     */
    int beginLongRun() {
        synchronized (ringGate) {
            longRun = true;
            int waited = ringWriters;
            boolean interrupted = false;
            while (ringWriters > 0) {
                try {
                    ringGate.wait(1000);
                } catch (InterruptedException e) {
                    // the analysis goes on: a write that waits is only slower, never lost
                    interrupted = true;
                    break;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return waited;
        }
    }

    /** The long analysis has ended (stored or rolled back): ring tasks may store again. */
    void endLongRun() {
        synchronized (ringGate) {
            longRun = false;
            ringGate.notifyAll();
        }
    }
}
