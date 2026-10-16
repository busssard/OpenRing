package app.openring.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

import app.openring.core.EventCodec;
import app.openring.core.RingEvent;
import app.openring.core.TimeLine;

/**
 * Local SQLite store. Raw ring events are kept losslessly (so better decoders can re process old
 * data) and dated against wall clock anchors per boot epoch; computed nights and days are cached
 * as JSON. Nothing leaves the phone unless you export it.
 */
public final class Db extends SQLiteOpenHelper {
    static final int VERSION = 4;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) upgradeTo2(db);
        if (oldV < 3) upgradeTo3(db);
        if (oldV < 4) upgradeTo4(db);
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

    public long undatedCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE unix IS NULL", null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    // ---------------------------------------------------------------------- nights, days

    public void replaceNights(double from, double to, List<Object[]> rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
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

    public void putDay(String day, String json) {
        ContentValues cv = new ContentValues();
        cv.put("day", day);
        cv.put("json", json);
        getWritableDatabase().insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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

    /** Store the ranges each app was in front; a range with a known start replaces the stored one. */
    public void putApps(java.util.Map<String, List<double[]>> apps) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<String, List<double[]>> e : apps.entrySet()) {
                for (double[] s : e.getValue()) {
                    ContentValues cv = new ContentValues();
                    cv.put("pkg", e.getKey());
                    cv.put("start", s[0]);
                    cv.put("stop", s[1]);
                    db.insertWithOnConflict("apps", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Ranges of apps in front that overlap [from, to), per package name, oldest first. */
    public java.util.Map<String, List<double[]>> apps(double from, double to) {
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

    /** Start of the oldest stored app range, or -1 (days before it have screen time but no app split). */
    public double appsStart() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(start) FROM apps", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : -1;
        } finally {
            c.close();
        }
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
        public int version;
        public long events;
        /** Newest dated event (unix seconds), NaN when nothing is dated. */
        public double newest = Double.NaN;
    }

    public static final String DAMAGED = "damaged", NEWER = "newer";
    private static final String[] TABLES = {"events", "nights", "days", "kv"};

    /** Does nothing, so Android's default handler cannot delete a file we only look at. */
    private static final android.database.DatabaseErrorHandler KEEP_FILE = new android.database.DatabaseErrorHandler() {
        @Override
        public void onCorruption(SQLiteDatabase db) {}
    };

    /**
     * Look at a database file on its own, read only and without localized collators, so the file
     * stays byte for byte as it was (a read write open on Android can switch the journal mode or
     * write android_metadata). With {@code check} (a backup's copy) it must pass
     * {@code PRAGMA quick_check}, have a version this app can open and hold the Open Ring tables.
     * Without it (the phone's own history) only the event count and newest date are read.
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
                for (String t : TABLES) {
                    if (!t.equals(first(d, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + t + "'"))) {
                        i.problem = DAMAGED;
                        return i;
                    }
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
     * the new one opens through the helper (which also upgrades an older copy) and holds exactly
     * that many events (Android's default error handler can replace a broken file with an empty
     * one that "opens"). Otherwise the old file comes back and an IOException tells the caller
     * nothing changed. If the app is stopped in the middle, {@link #recoverSwap} puts the old file
     * back before the next open.
     *
     * Nobody else writes meanwhile: restore runs only on the Setup screen after the key was
     * cleared (or before one was ever saved), so sync and the background job cannot reach the
     * ring and stay idle. A running analysis is a separate case, handled where analysis runs.
     */
    public static synchronized void replaceWith(Context c, java.io.File src, long expectedEvents) throws java.io.IOException {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        java.io.File dst = c.getDatabasePath(REAL);
        dst.getParentFile().mkdirs();
        String old = dst.getPath() + ".old";
        // an unfinished earlier swap: its kept history comes back first, it is never deleted
        recoverSwap(c);
        if (new java.io.File(old).exists()) throw new java.io.IOException("an earlier restore could not be undone");
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
        for (String s : SIDE) new java.io.File(old + s).delete();
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

    /** Wipe everything (keeps nothing). */
    public void resetAll() {
        SQLiteDatabase db = getWritableDatabase();
        for (String t : new String[] {"events", "anchors", "nights", "days", "battery", "kv", "screen", "apps"}) db.execSQL("DELETE FROM " + t);
    }

    /** Forget computed results so the next analysis rebuilds everything from raw events. */
    public void resetAnalysis() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM nights");
        db.execSQL("DELETE FROM days");
        put("analyzed_until", 0);
    }
}
