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
    private static final int VERSION = 3;
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
        if (instance == null) instance = new Db(c.getApplicationContext(), name);
        return instance;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) upgradeTo2(db);
        if (oldV < 3) upgradeTo3(db);
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
        public List<TimeLine.Segment> segs;
    }

    /**
     * Rebuild boot segments and the wall clock time of every event from scratch. Cheap enough to
     * run after every sync, and it repairs history that an interrupted sync left undated.
     */
    public synchronized TimeReport rebuildTime(long nowUnix) {
        SQLiteDatabase db = getWritableDatabase();
        TimeReport rep = new TimeReport();
        rep.datedBefore = count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL");
        Cursor c = db.rawQuery("SELECT id, epoch, tag, ds, CASE WHEN tag IN (66, 133) THEN body END FROM events ORDER BY id", null);
        int n = c.getCount();
        long[] id = new long[n], epoch = new long[n], ds = new long[n], anchor = new long[n];
        boolean[] phone = new boolean[n];
        try {
            int i = 0;
            while (c.moveToNext()) {
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
                i++;
            }
        } finally {
            c.close();
        }
        rep.total = n;
        List<TimeLine.Segment> segs = TimeLine.build(id, epoch, ds, anchor, phone, nowUnix);
        rep.segs = segs;
        rep.segments = segs.size();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE events SET unix = NULL, seg = NULL");
            for (TimeLine.Segment sg : segs) {
                db.execSQL("UPDATE events SET seg = ? WHERE id BETWEEN ? AND ?", new Object[] {sg.no, sg.firstId, sg.lastId});
                for (Long stray : sg.strayIds) db.execSQL("UPDATE events SET seg = ? WHERE id = ?", new Object[] {sg.no, stray});
                if (sg.anchors.isEmpty()) continue;
                rep.anchoredSegments++;
                for (long[] r : TimeLine.regions(sg)) {
                    db.execSQL("UPDATE events SET unix = ? + (ds - ?) / 10.0 WHERE seg = ? AND ds >= ? AND ds < ?",
                            new Object[] {r[3], r[2], sg.no, r[0], r[1]});
                }
            }
            // an event cannot happen well after it was downloaded, nor before 2015
            db.execSQL("UPDATE events SET unix = NULL WHERE unix > ? OR unix < 1420000000", new Object[] {nowUnix + 6 * 3600});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        rep.datedAfter = count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL");
        return rep;
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

    /** Wipe everything (keeps nothing). */
    /**
     * Swap the whole database for a restored copy (a backup). The open connection is closed first;
     * the next {@link #get} opens the new file and upgrades its schema if it is older.
     */
    public static synchronized void replaceWith(Context c, java.io.File src) throws java.io.IOException {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        java.io.File dst = c.getDatabasePath(REAL);
        dst.getParentFile().mkdirs();
        new java.io.File(dst.getPath() + "-wal").delete();
        new java.io.File(dst.getPath() + "-shm").delete();
        new java.io.File(dst.getPath() + "-journal").delete();
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    public void resetAll() {
        SQLiteDatabase db = getWritableDatabase();
        for (String t : new String[] {"events", "anchors", "nights", "days", "battery", "kv", "screen"}) db.execSQL("DELETE FROM " + t);
    }

    /** Forget computed results so the next analysis rebuilds everything from raw events. */
    public void resetAnalysis() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM nights");
        db.execSQL("DELETE FROM days");
        put("analyzed_until", 0);
    }
}
