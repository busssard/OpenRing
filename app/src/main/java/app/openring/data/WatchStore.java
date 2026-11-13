package app.openring.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.Texts;
import app.openring.core.withings.Frame;
import app.openring.core.withings.Records;

/**
 * The watch's stored data (issue #251, phase 1b, schema 9; {@code watch_moves} schema 10, watch setup). Raw first, like the ring: every message the watch sends is
 * kept as it came in {@code watch_raw} (session start in ms and place in the session as its key, so two equal messages
 * are both kept) and never changed. The rows the screens read ({@code watch_steps}, {@code watch_hr},
 * {@code watch_sleep}, {@code watch_workouts}, {@code watch_battery}) are worked out from it by {@code core/withings/Records}
 * and keyed by device and time, so the same message twice adds nothing. A new decoder works everything out again in
 * one transaction ({@link #ensureDecoded}): the old rows stay until the new ones are complete. Pairing another ring
 * keeps all of it, Delete all empties it, a restore keeps the phone's raw messages and devices
 * ({@link #read}, {@link #merge}) and works the rows out again.
 */
public final class WatchStore {
    private WatchStore() {}

    /** Every watch table: kept when another ring is paired ({@code Db.RING_TABLES} leaves them out). */
    static final String[] TABLES = {"watch_raw", "watch_devices", "watch_steps", "watch_hr", "watch_sleep", "watch_workouts", "watch_battery", "watch_moves"};
    /** The tables worked out from {@code watch_raw}. */
    static final String[] DECODED = {"watch_steps", "watch_hr", "watch_sleep", "watch_workouts", "watch_battery", "watch_moves"};
    /** kv: the decoder version the stored rows come from. */
    static final String DECODER = "watch_decoder";
    /** Tests only: decoding throws after this many messages, as a stop half way would. */
    static volatile int failDecodeAfter = -1;

    /** Schema 9: only adds, each table on its own, safe to run twice and to resume. */
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_raw (session INTEGER NOT NULL, seq INTEGER NOT NULL, device TEXT NOT NULL,"
                + " type INTEGER NOT NULL, body BLOB NOT NULL, captured REAL NOT NULL, PRIMARY KEY(session, seq))");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_devices (address TEXT PRIMARY KEY, name TEXT, firmware TEXT, added REAL NOT NULL, removed REAL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_steps (device TEXT NOT NULL, start INTEGER NOT NULL, dur INTEGER NOT NULL,"
                + " steps INTEGER NOT NULL, metres INTEGER NOT NULL, kcal INTEGER NOT NULL, PRIMARY KEY(device, start))");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_hr (device TEXT NOT NULL, t INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY(device, t))");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_sleep (device TEXT NOT NULL, start INTEGER NOT NULL, dur INTEGER NOT NULL,"
                + " stage INTEGER NOT NULL, PRIMARY KEY(device, start))");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_workouts (device TEXT NOT NULL, start INTEGER NOT NULL, dur INTEGER NOT NULL,"
                + " kind INTEGER NOT NULL, steps INTEGER NOT NULL, metres INTEGER NOT NULL, kcal INTEGER NOT NULL, PRIMARY KEY(device, start))");
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_battery (device TEXT NOT NULL, t INTEGER NOT NULL, pct INTEGER NOT NULL,"
                + " state INTEGER NOT NULL, mv INTEGER NOT NULL, PRIMARY KEY(device, t))");
    }

    /**
     * Schema 10 (watch setup): per record its intensity and metres climbed and descended. Only adds one table, safe to run
     * twice; older files get it empty and the next decode fills it from their raw messages.
     */
    static void create10(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_moves (device TEXT NOT NULL, start INTEGER NOT NULL, met INTEGER NOT NULL,"
                + " up INTEGER NOT NULL, down INTEGER NOT NULL, PRIMARY KEY(device, start))");
    }

    // ------------------------------------------------------------------------------------- writing

    /** Stores one message as it came (its own small transaction, so a stop keeps everything before it). */
    public static void addRaw(Db db, long session, int seq, String device, byte[] body, double captured) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO watch_raw(session, seq, device, type, body, captured) VALUES (?, ?, ?, ?, ?, ?)",
                new Object[] {session, seq, device, Frame.type(body), body, captured});
    }

    /** Notes the watch (first time: when it was added), with the name and firmware its hello named when known. */
    public static void noteDevice(Db db, String address, String name, String firmware, double now) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.execSQL("INSERT OR IGNORE INTO watch_devices(address, name, firmware, added) VALUES (?, ?, ?, ?)", new Object[] {address, name, firmware, now});
        if (name != null) d.execSQL("UPDATE watch_devices SET name = ? WHERE address = ?", new Object[] {name, address});
        if (firmware != null) d.execSQL("UPDATE watch_devices SET firmware = ? WHERE address = ?", new Object[] {firmware, address});
        d.execSQL("UPDATE watch_devices SET removed = NULL WHERE address = ?", new Object[] {address});
    }

    /** The person forgot the watch: noted, nothing deleted. */
    public static void removeDevice(Db db, String address, double now) {
        db.getWritableDatabase().execSQL("UPDATE watch_devices SET removed = ? WHERE address = ?", new Object[] {now, address});
    }

    /**
     * Works out the rows of one session's messages, then moves where the next request of each kind starts
     * ({@link #since(Db, String, int, long)}) for the answers of this session that reached their end, all in one
     * transaction: a start only moves once the rows up to it are stored, and an answer cut off moves nothing.
     */
    public static Records.Decoded decodeSession(Db db, final long session) {
        final SQLiteDatabase d = db.getWritableDatabase();
        final Records.Decoded[] all = {new Records.Decoded()};
        // the session's data answers per device and request (a session has one of each)
        java.util.Map<String, List<byte[]>> answers = new java.util.TreeMap<String, List<byte[]>>();
        d.beginTransaction();
        try {
            Cursor c = d.rawQuery("SELECT device, body, captured FROM watch_raw WHERE session = ? ORDER BY seq", new String[] {String.valueOf(session)});
            try {
                while (c.moveToNext()) {
                    byte[] body = c.getBlob(1);
                    Records.Decoded one = new Records.Decoded();
                    Records.decode(body, (long) c.getDouble(2), one);
                    write(d, c.getString(0), one);
                    collect(all[0], one);
                    int type = Frame.type(body);
                    if (isRequest(type)) {
                        String k = cursorKey(c.getString(0), type);
                        List<byte[]> l = answers.get(k);
                        if (l == null) answers.put(k, l = new ArrayList<byte[]>());
                        l.add(body);
                    }
                }
            } finally {
                c.close();
            }
            for (java.util.Map.Entry<String, List<byte[]>> e : answers.entrySet()) {
                long got = Records.newestComplete(e.getValue());
                if (got <= 0) continue;
                String k = e.getKey();
                String have = kv(d, k);
                // no start noted yet (an older version, a restore): worked out from every raw message, this session's too
                long next = have == null ? fromRaw(d, deviceOf(k), typeOf(k)) : Math.max(parse(have), got);
                d.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", new Object[] {k, String.valueOf(next)});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return all[0];
    }

    private static void collect(Records.Decoded into, Records.Decoded one) {
        into.steps.addAll(one.steps);
        into.heart.addAll(one.heart);
        into.sleep.addAll(one.sleep);
        into.workouts.addAll(one.workouts);
        into.battery.addAll(one.battery);
        into.moves.addAll(one.moves);
        if (one.name != null) into.name = one.name;
        if (one.firmware >= 0) into.firmware = one.firmware;
        into.unknown += one.unknown;
    }

    /**
     * The value bound at {@code ?n} for {@code col}, or, when it is 0, the one already stored for the same device ({@code ?1})
     * and start ({@code ?2}). A record can come in the activity answer and again in the movement answer, each with only some
     * of its parts (the movement answer may lack the calories part): the second must not wipe what the first had (review of
     * #332). A record that comes again whole has the same or larger counts, so a non zero value always wins.
     */
    private static String kept(String table, String col, int n) {
        return "CASE WHEN ?" + n + " <> 0 THEN ?" + n + " ELSE COALESCE((SELECT " + col + " FROM " + table + " WHERE device = ?1 AND start = ?2), 0) END";
    }

    private static void write(SQLiteDatabase d, String device, Records.Decoded r) {
        if (!r.steps.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_steps(device, start, dur, steps, metres, kcal) VALUES (?1, ?2, "
                    + kept("watch_steps", "dur", 3) + ", " + kept("watch_steps", "steps", 4) + ", " + kept("watch_steps", "metres", 5) + ", " + kept("watch_steps", "kcal", 6) + ")");
            for (Records.Steps s : r.steps) {
                st.bindString(1, device);
                st.bindLong(2, s.start);
                st.bindLong(3, s.dur);
                st.bindLong(4, s.steps);
                st.bindLong(5, s.metres);
                st.bindLong(6, s.kcal);
                st.executeInsert();
            }
            st.close();
        }
        if (!r.heart.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_hr(device, t, bpm) VALUES (?,?,?)");
            for (Records.Heart h : r.heart) {
                st.bindString(1, device);
                st.bindLong(2, h.t);
                st.bindLong(3, h.bpm);
                st.executeInsert();
            }
            st.close();
        }
        if (!r.sleep.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_sleep(device, start, dur, stage) VALUES (?,?,?,?)");
            for (Records.Sleep s : r.sleep) {
                st.bindString(1, device);
                st.bindLong(2, s.start);
                st.bindLong(3, s.dur);
                st.bindLong(4, s.stage);
                st.executeInsert();
            }
            st.close();
        }
        if (!r.workouts.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_workouts(device, start, dur, kind, steps, metres, kcal) VALUES (?,?,?,?,?,?,?)");
            for (Records.Workout w : r.workouts) {
                st.bindString(1, device);
                st.bindLong(2, w.start);
                st.bindLong(3, w.dur);
                st.bindLong(4, w.kind);
                st.bindLong(5, w.steps);
                st.bindLong(6, w.metres);
                st.bindLong(7, w.kcal);
                st.executeInsert();
            }
            st.close();
        }
        if (!r.battery.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_battery(device, t, pct, state, mv) VALUES (?,?,?,?,?)");
            for (Records.Battery b : r.battery) {
                st.bindString(1, device);
                st.bindLong(2, b.t);
                st.bindLong(3, b.pct);
                st.bindLong(4, b.state);
                st.bindLong(5, b.mv);
                st.executeInsert();
            }
            st.close();
        }
        if (!r.moves.isEmpty()) {
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO watch_moves(device, start, met, up, down) VALUES (?1, ?2, "
                    + kept("watch_moves", "met", 3) + ", " + kept("watch_moves", "up", 4) + ", " + kept("watch_moves", "down", 5) + ")");
            for (Records.Moves m : r.moves) {
                st.bindString(1, device);
                st.bindLong(2, m.start);
                st.bindLong(3, m.met);
                st.bindLong(4, m.up);
                st.bindLong(5, m.down);
                st.executeInsert();
            }
            st.close();
        }
    }

    /**
     * Works every row out again from the raw messages, inside the caller's transaction: the rows are deleted and
     * written anew, and the decoder version noted. The caller commits only when it returns, so any stop keeps the old
     * rows. Reads the raw messages one at a time, so memory stays flat with years of them.
     */
    static void decodeAll(SQLiteDatabase d) {
        for (String t : DECODED) d.execSQL("DELETE FROM " + t);
        // where the next requests start is worked out again from the raw messages when next asked (a restore brings others)
        d.execSQL("DELETE FROM kv WHERE k LIKE '" + CURSOR + "%'");
        int n = 0;
        long last = Long.MIN_VALUE;
        int lastSeq = -1;
        // pages by key, so no cursor holds years of message bytes at once
        while (true) {
            Cursor c = d.rawQuery("SELECT session, seq, device, body, captured FROM watch_raw WHERE session > ? OR (session = ? AND seq > ?)"
                    + " ORDER BY session, seq LIMIT 2000", new String[] {String.valueOf(last), String.valueOf(last), String.valueOf(lastSeq)});
            int got = 0;
            try {
                while (c.moveToNext()) {
                    got++;
                    if (failDecodeAfter >= 0 && n >= failDecodeAfter) throw new IllegalStateException("test stop in decoding");
                    Records.Decoded one = new Records.Decoded();
                    Records.decode(c.getBlob(3), (long) c.getDouble(4), one);
                    write(d, c.getString(2), one);
                    last = c.getLong(0);
                    lastSeq = c.getInt(1);
                    n++;
                }
            } finally {
                c.close();
            }
            if (got == 0) break;
        }
        d.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", new Object[] {DECODER, String.valueOf(Records.VERSION)});
    }

    /** True when the stored rows come from this decoder (or nothing is stored yet). */
    public static boolean decoderCurrent(Db db) {
        return String.valueOf(Records.VERSION).equals(db.kv(DECODER, null)) || db.count("SELECT COUNT(*) FROM watch_raw") == 0;
    }

    /** Works every row out again when the decoder changed since they were stored; one transaction. Returns whether it did. */
    public static boolean ensureDecoded(Db db) {
        if (decoderCurrent(db)) return false;
        decodeAgain(db);
        return true;
    }

    /** Works every row out again, in one transaction. */
    public static void decodeAgain(Db db) {
        final SQLiteDatabase d = db.getWritableDatabase();
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                decodeAll(d);
            }
        });
    }

    /** kv: per watch and request, the newest record time of its whole answers ({@code watch_cursor_<type>_<address>}). */
    static final String CURSOR = "watch_cursor_";
    /** The data requests, each with its own start (owner's report of 11 November). */
    public static final int[] REQUESTS = {app.openring.core.withings.Proto.ACTIVITY, app.openring.core.withings.Proto.MOVEMENT,
        app.openring.core.withings.Proto.HEART_RATE};
    public static final long OVERLAP_S = 2 * 3600;

    static boolean isRequest(int type) {
        for (int r : REQUESTS) if (r == type) return true;
        return false;
    }

    static String cursorKey(String device, int type) {
        return CURSOR + Frame.hexType(type) + "_" + device;
    }

    private static String deviceOf(String key) {
        return key.substring(key.indexOf('_', CURSOR.length()) + 1);
    }

    private static int typeOf(String key) {
        String hex = key.substring(CURSOR.length(), key.indexOf('_', CURSOR.length()));
        return Integer.parseInt(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String kv(SQLiteDatabase d, String k) {
        Cursor c = d.rawQuery("SELECT v FROM kv WHERE k = ?", new String[] {k});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * The newest record time of every whole answer to request {@code type} from {@code device} in the raw messages, 0 for
     * none: each session's answer counts only when it reached its end part. Reads session by session, so memory stays flat.
     */
    static long fromRaw(SQLiteDatabase d, String device, int type) {
        long newest = 0, last = Long.MIN_VALUE;
        while (true) {
            Cursor c = d.rawQuery("SELECT DISTINCT session FROM watch_raw WHERE device = ? AND type = ? AND session > ? ORDER BY session LIMIT 500",
                    new String[] {device, String.valueOf(type), String.valueOf(last)});
            List<Long> sessions = new ArrayList<Long>();
            try {
                while (c.moveToNext()) sessions.add(c.getLong(0));
            } finally {
                c.close();
            }
            if (sessions.isEmpty()) return newest;
            for (long s : sessions) {
                List<byte[]> answer = new ArrayList<byte[]>();
                Cursor m = d.rawQuery("SELECT body FROM watch_raw WHERE session = ? AND device = ? AND type = ? ORDER BY seq",
                        new String[] {String.valueOf(s), device, String.valueOf(type)});
                try {
                    while (m.moveToNext()) answer.add(m.getBlob(0));
                } finally {
                    m.close();
                }
                newest = Math.max(newest, Records.newestComplete(answer));
                last = s;
            }
        }
    }

    /**
     * The newest record time of the whole answers to request {@code type} of this watch (unix seconds, 0 for none). Noted
     * per request when a session is decoded; when nothing is noted (an older version, after a restore or a new decoder) it
     * is worked out from the raw messages once and noted.
     */
    public static long newest(Db db, String device, int type) {
        String k = cursorKey(device, type);
        SQLiteDatabase d = db.getWritableDatabase();
        String have = kv(d, k);
        if (have != null) return parse(have);
        long v = fromRaw(d, device, type);
        d.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", new Object[] {k, String.valueOf(v)});
        return v;
    }

    /**
     * Where the next request {@code type} starts (unix seconds): 2 hours before the newest record of its last whole answers,
     * so a record still running then comes again whole (its key replaces it); 0 when it never had a whole answer (the sync
     * then asks {@code WatchSetup.FIRST_SINCE_S} back, see {@link #since(Db, String, int, long)}). Each request has its own
     * start: the heart rates inside an activity answer, or an activity answer next to a movement answer that was cut off,
     * never move another request's start (owner's report of 11 November).
     */
    public static long since(Db db, String device, int type) {
        long n = newest(db, device, type);
        return n > 0 ? Math.max(0, n - OVERLAP_S) : 0;
    }

    /** {@link #since(Db, String, int)}, or {@code now} minus a month when that request never had a whole answer. */
    public static long since(Db db, String device, int type, long now) {
        long s = since(db, device, type);
        return s > 0 ? s : Math.max(0, now - app.openring.core.withings.WatchSetup.FIRST_SINCE_S);
    }

    // ------------------------------------------------------------------------------------- reading

    /** Every stored row of every watch, for the Watch page (a year is some 20,000 heart rates, read in a moment). */
    public static final class Data {
        public final List<Records.Steps> steps = new ArrayList<Records.Steps>();
        public final List<Records.Heart> heart = new ArrayList<Records.Heart>();
        public final List<Records.Sleep> sleep = new ArrayList<Records.Sleep>();
        public final List<Records.Workout> workouts = new ArrayList<Records.Workout>();
        public final List<Records.Battery> battery = new ArrayList<Records.Battery>();
        public final List<Records.Moves> moves = new ArrayList<Records.Moves>();
        public String name, firmware;
        public long messages;
    }

    public static Data load(Db db) {
        Data out = new Data();
        SQLiteDatabase d = db.getReadableDatabase();
        Cursor c = d.rawQuery("SELECT start, dur, steps, metres, kcal FROM watch_steps ORDER BY start", null);
        try {
            while (c.moveToNext()) out.steps.add(new Records.Steps(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT t, bpm FROM watch_hr ORDER BY t", null);
        try {
            while (c.moveToNext()) out.heart.add(new Records.Heart(c.getLong(0), c.getInt(1)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT start, dur, stage FROM watch_sleep ORDER BY start", null);
        try {
            while (c.moveToNext()) out.sleep.add(new Records.Sleep(c.getLong(0), c.getInt(1), c.getInt(2)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT start, dur, kind, steps, metres, kcal FROM watch_workouts ORDER BY start", null);
        try {
            while (c.moveToNext()) out.workouts.add(new Records.Workout(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT t, pct, state, mv FROM watch_battery ORDER BY t", null);
        try {
            while (c.moveToNext()) out.battery.add(new Records.Battery(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT start, met, up, down FROM watch_moves ORDER BY start", null);
        try {
            while (c.moveToNext()) out.moves.add(new Records.Moves(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT name, firmware FROM watch_devices ORDER BY added DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) {
                out.name = c.getString(0);
                out.firmware = c.getString(1);
            }
        } finally {
            c.close();
        }
        out.messages = db.count("SELECT COUNT(*) FROM watch_raw");
        return out;
    }

    /**
     * The watch's rows from {@code from} to {@code to} (unix seconds) for one day's main device rule (issue #251,
     * {@code core/MainDevice}): records that start in it, sleep pieces that reach into it. Read on each day load.
     */
    public static app.openring.core.MainDevice.Watch window(Db db, double from, double to) {
        app.openring.core.MainDevice.Watch w = new app.openring.core.MainDevice.Watch();
        SQLiteDatabase d = db.getReadableDatabase();
        String[] a = {String.valueOf((long) Math.floor(from)), String.valueOf((long) Math.ceil(to))};
        Cursor c = d.rawQuery("SELECT start, dur, steps, metres, kcal FROM watch_steps WHERE start >= ? AND start < ? ORDER BY start", a);
        try {
            while (c.moveToNext()) w.steps.add(new Records.Steps(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT t, bpm FROM watch_hr WHERE t >= ? AND t < ? ORDER BY t", a);
        try {
            while (c.moveToNext()) w.heart.add(new Records.Heart(c.getLong(0), c.getInt(1)));
        } finally {
            c.close();
        }
        // a night's pieces start the evening before; the day load asks from a day earlier. The cast: an expression has no
        // column affinity, so a text parameter would never compare as a number
        c = d.rawQuery("SELECT start, dur, stage FROM watch_sleep WHERE start + dur > CAST(? AS INTEGER) AND start < ? ORDER BY start", a);
        try {
            while (c.moveToNext()) w.sleep.add(new Records.Sleep(c.getLong(0), c.getInt(1), c.getInt(2)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT start, dur, kind, steps, metres, kcal FROM watch_workouts WHERE start >= ? AND start < ? ORDER BY start", a);
        try {
            while (c.moveToNext()) w.workouts.add(new Records.Workout(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5)));
        } finally {
            c.close();
        }
        return w;
    }

    /**
     * Only the watch's sleep pieces and heart rates from {@code from} to {@code to} (the same rule as {@link #window}), for Further
     * analysis of the watch's nights over a year or all time, where the steps are not needed.
     */
    public static app.openring.core.MainDevice.Watch nightRows(Db db, double from, double to) {
        app.openring.core.MainDevice.Watch w = new app.openring.core.MainDevice.Watch();
        SQLiteDatabase d = db.getReadableDatabase();
        String[] a = {String.valueOf((long) Math.floor(from)), String.valueOf((long) Math.ceil(to))};
        Cursor c = d.rawQuery("SELECT t, bpm FROM watch_hr WHERE t >= ? AND t < ? ORDER BY t", a);
        try {
            while (c.moveToNext()) w.heart.add(new Records.Heart(c.getLong(0), c.getInt(1)));
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT start, dur, stage FROM watch_sleep WHERE start + dur > CAST(? AS INTEGER) AND start < ? ORDER BY start", a);
        try {
            while (c.moveToNext()) w.sleep.add(new Records.Sleep(c.getLong(0), c.getInt(1), c.getInt(2)));
        } finally {
            c.close();
        }
        return w;
    }

    /**
     * What the ring's stored results give for the main device rule: the day summary's steps, distance, movement per
     * quarter hour and heart rate line, and every night or nap in {@code nights} that reaches the day ({@code from} to
     * {@code to}). Reads only; a missing summary gives NaN and empty values.
     */
    public static app.openring.core.MainDevice.Ring ringDay(org.json.JSONObject day, org.json.JSONObject night, List<org.json.JSONObject> nights,
            double from, double to) {
        app.openring.core.MainDevice.Ring r = new app.openring.core.MainDevice.Ring();
        r.steps = Json.d(day, "steps");
        r.metres = Json.d(day, "distance_m");
        org.json.JSONArray prof = day == null ? null : day.optJSONArray("met_profile");
        for (int i = 0; prof != null && i < Math.min(96, prof.length()); i++) r.met[i] = prof.isNull(i) ? Double.NaN : prof.optDouble(i, Double.NaN);
        r.hr = Json.series(day == null ? null : day.optJSONArray("hr_day"));
        r.night = night != null;
        for (org.json.JSONObject n : nights) {
            if (n == null) continue;
            double s = Json.d(n, "start"), e = Json.d(n, "end");
            if (!Double.isNaN(s) && !Double.isNaN(e) && e > from && s < to) r.sleeps.add(new double[] {s, e});
        }
        return r;
    }

    /** The newest battery reading as {percent, unix seconds}, null for none (Settings, Watch). */
    public static long[] newestBattery(Db db) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT pct, t FROM watch_battery ORDER BY t DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? new long[] {c.getLong(0), c.getLong(1)} : null;
        } finally {
            c.close();
        }
    }

    /**
     * Which nights tell REM from light sleep ({@code WatchDays.Rem}, code review of #389): each watch that ever sent a REM record,
     * from its first REM record to the end of its last sleep piece; the nights of a watch that never did (the Steel HR) show
     * "Light and REM", whether it was worn before or after one with REM. Every night without any watch sleep row, so nothing
     * changes for a person without a watch.
     */
    public static app.openring.core.withings.WatchDays.Rem rem(Db db) {
        if (db.count("SELECT COUNT(*) FROM (SELECT 1 FROM watch_sleep LIMIT 1)") == 0) return app.openring.core.withings.WatchDays.Rem.ALWAYS;
        List<long[]> spans = new ArrayList<long[]>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MIN(CASE WHEN stage = " + Records.REM + " THEN start END), MAX(start + dur) FROM watch_sleep"
                + " GROUP BY device", null);
        try {
            while (c.moveToNext()) {
                if (c.isNull(0)) continue;
                long first = c.getLong(0);
                spans.add(new long[] {first, Math.max(first + 1, c.getLong(1))});
            }
        } finally {
            c.close();
        }
        return new app.openring.core.withings.WatchDays.Rem(spans.toArray(new long[0][]));
    }

    /** Only the watch's sleep pieces that reach into {@code from} to {@code to} (sleep debt reads 104 days of nights, not their steps). */
    public static List<Records.Sleep> sleepRows(Db db, double from, double to) {
        List<Records.Sleep> out = new ArrayList<Records.Sleep>();
        String[] a = {String.valueOf((long) Math.floor(from)), String.valueOf((long) Math.ceil(to))};
        Cursor c = db.getReadableDatabase().rawQuery("SELECT start, dur, stage FROM watch_sleep WHERE start + dur > CAST(? AS INTEGER) AND start < ? ORDER BY start", a);
        try {
            while (c.moveToNext()) out.add(new Records.Sleep(c.getLong(0), c.getInt(1), c.getInt(2)));
        } finally {
            c.close();
        }
        return out;
    }

    /** True when any watch message is stored. */
    public static boolean any(Db db) {
        return db.count("SELECT COUNT(*) FROM (SELECT 1 FROM watch_raw LIMIT 1)") > 0;
    }

    /**
     * The Data check's watch lines (issue #251): counts and dates only, never a value (no steps, heart rate, sleep or
     * battery level), so the copy stays safe to post.
     */
    public static String checkLines(Db db) {
        long raw = db.count("SELECT COUNT(*) FROM watch_raw");
        if (raw == 0) return "Watch: no messages stored\n";
        long sessions = db.count("SELECT COUNT(DISTINCT session) FROM watch_raw");
        double[] r = range(db);
        StringBuilder b = new StringBuilder();
        b.append("Watch: ").append(Texts.count(db.count("SELECT COUNT(*) FROM watch_devices"), "watch", "watches")).append(", ")
                .append(Texts.count(sessions, "sync", "syncs")).append(", ").append(String.format(Locale.US, "%,d", raw))
                .append(raw == 1 ? " message" : " messages").append(" stored, from ").append(DataCheck.date(r[0])).append(" to ").append(DataCheck.date(r[1])).append('\n');
        b.append("  Message types: ");
        Cursor c = db.getReadableDatabase().rawQuery("SELECT type, COUNT(*) FROM watch_raw GROUP BY type ORDER BY type", null);
        try {
            boolean first = true;
            while (c.moveToNext()) {
                if (!first) b.append(", ");
                first = false;
                b.append(Frame.hexType(c.getInt(0))).append(' ').append(c.getLong(1));
            }
        } finally {
            c.close();
        }
        b.append('\n');
        b.append("  Decoded (decoder ").append(db.kv(DECODER, "none")).append("): ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_steps"), "step record", "step records")).append(", ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_hr"), "heart rate", "heart rates")).append(", ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_sleep"), "sleep piece", "sleep pieces")).append(", ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_workouts"), "workout", "workouts")).append(", ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_battery"), "charge reading", "charge readings")).append(", ")
                .append(Texts.count(db.count("SELECT COUNT(*) FROM watch_moves"), "intensity and climb record", "intensity and climb records")).append('\n');
        return b.toString();
    }

    private static double[] range(Db db) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MIN(captured), MAX(captured) FROM watch_raw", null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) return new double[] {c.getDouble(0), c.getDouble(1)};
        } finally {
            c.close();
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    // ---------------------------------------------------------------------------- restore (issue #251)

    /** The phone's raw messages and devices, read before a restore swaps the file. */
    static final class Rows {
        final List<Object[]> raw = new ArrayList<Object[]>();
        final List<Object[]> devices = new ArrayList<Object[]>();
    }

    /** Reads every raw message and device of {@code d} (a file without any gives empty lists). */
    static Rows read(SQLiteDatabase d) {
        Rows r = new Rows();
        Cursor c = d.rawQuery("SELECT session, seq, device, type, body, captured FROM watch_raw", null);
        try {
            while (c.moveToNext()) r.raw.add(new Object[] {c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getBlob(4), c.getDouble(5)});
        } finally {
            c.close();
        }
        c = d.rawQuery("SELECT address, name, firmware, added, removed FROM watch_devices", null);
        try {
            while (c.moveToNext()) {
                r.devices.add(new Object[] {c.getString(0), c.isNull(1) ? null : c.getString(1), c.isNull(2) ? null : c.getString(2), c.getDouble(3),
                    c.isNull(4) ? null : (Object) c.getDouble(4)});
            }
        } finally {
            c.close();
        }
        return r;
    }

    /**
     * Writes the phone's rows into the restored file {@code d} in one transaction (the phone's row wins on the same key,
     * the backup's other messages stay), then works every row out again from all raw messages. Running it twice gives
     * the same result.
     */
    static void merge(SQLiteDatabase d, Rows r) {
        d.beginTransaction();
        try {
            for (Object[] row : r.raw) d.execSQL("INSERT OR REPLACE INTO watch_raw(session, seq, device, type, body, captured) VALUES (?, ?, ?, ?, ?, ?)", row);
            for (Object[] row : r.devices) d.execSQL("INSERT OR REPLACE INTO watch_devices(address, name, firmware, added, removed) VALUES (?, ?, ?, ?, ?)", row);
            decodeAll(d);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
