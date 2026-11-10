package app.openring.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

import app.openring.core.PhoneSteps;

/**
 * The phone's own steps (issue #329, phase 1b, schema 11). Raw first, like the ring and the watch: every reading of the
 * phone's step counter is kept as it came in {@code phone_readings} (boot count and elapsed time as its key, counter, wall
 * clock) and never changed or deleted, only added to. The intervals the screens read ({@code phone_steps}) are worked out
 * from the readings by {@code core/PhoneSteps}, always inside one transaction: a stop half way keeps the old rows.
 *
 * <ul>
 * <li>Readings always go into the real history file ({@link Db#real}), also while demo mode is shown; demo mode has its own
 * made up readings in its own file ({@link Demo}).</li>
 * <li>A restore keeps this phone's readings the backup lacks ({@link #read}, {@link #merge}: insert, never replace), like
 * the period log (#275), and works the intervals out again.</li>
 * <li>Delete all empties both tables; pairing another ring keeps them ({@code Db.KEPT_TABLES}): they are not ring data.</li>
 * </ul>
 */
public final class PhoneStore {
    private PhoneStore() {}

    /** Both tables: kept when another ring is paired ({@code Db.RING_TABLES} leaves them out), emptied by Delete all. */
    static final String[] TABLES = {"phone_readings", "phone_steps"};
    /** kv: the rule version ({@code PhoneSteps.RULE}) the stored intervals come from. */
    static final String RULE = "phone_rule";
    /** Tests only: working the intervals out throws after this many rows, as a stop half way would. */
    static volatile int failRebuildAfter = -1;

    /**
     * Schema 11: only adds, each table and the index on its own ({@code IF NOT EXISTS}), safe to run twice and to resume.
     * Older files (and restored older backups) get them empty.
     */
    static void create11(SQLiteDatabase db) {
        // device: a random id made once on the phone (Prefs.phoneDevice), so another phone's readings from a restored backup,
        // with the same small boot counts, are never differenced against this phone's
        db.execSQL("CREATE TABLE IF NOT EXISTS phone_readings (device TEXT NOT NULL, boot INTEGER NOT NULL, elapsed INTEGER NOT NULL,"
                + " counter INTEGER NOT NULL, wall REAL NOT NULL, PRIMARY KEY (device, boot, elapsed))");
        db.execSQL("CREATE TABLE IF NOT EXISTS phone_steps (device TEXT NOT NULL, boot INTEGER NOT NULL, elapsed INTEGER NOT NULL, start REAL NOT NULL,"
                + " stop REAL NOT NULL, steps INTEGER NOT NULL, PRIMARY KEY (device, boot, elapsed))");
        // a day's read: the intervals that end after its start (recent days read few rows)
        db.execSQL("CREATE INDEX IF NOT EXISTS phone_steps_stop ON phone_steps(stop)");
    }

    // ------------------------------------------------------------------------------------- writing

    /** What {@link #record} did. */
    public static final int STORED = 0, KNOWN = 1, TOO_SOON = 2, BUSY = 3;

    /**
     * Stores one reading into the real history (never the demo file, issue #329), with its boot's intervals worked out again,
     * in one transaction. Holds Db's class lock, the lock a restore's file swap holds, so the reading never lands in a file
     * half swapped. Returns {@link #BUSY} without writing while an analysis runs (its transaction would hold the write for
     * minutes): the counter keeps counting, so the next reading loses nothing, only one interval is longer.
     * {@code minGapS} &gt; 0: skipped ({@link #TOO_SOON}) when the newest reading of the same boot is younger than that.
     */
    public static int record(Context c, PhoneSteps.Reading r, long minGapS) {
        synchronized (Db.class) {
            if (Analyzer.running()) return BUSY;
            return add(Db.real(c), r, minGapS);
        }
    }

    /**
     * True when this phone's newest reading of boot {@code boot} is less than {@code minGapS} old at {@code elapsedMs}: the app
     * opening then does not switch the sensor on at all (review of #367). Reads the real history.
     */
    public static boolean recent(Context c, String device, long boot, long elapsedMs, long minGapS) {
        synchronized (Db.class) {
            return newestElapsed(Db.real(c).getReadableDatabase(), device, boot) > elapsedMs - minGapS * 1000;
        }
    }

    /** As {@link #record}, into {@code db} itself (the tests, the demo). */
    static int add(Db db, final PhoneSteps.Reading r, long minGapS) {
        final SQLiteDatabase d = db.getWritableDatabase();
        final int[] out = {STORED};
        d.beginTransaction();
        try {
            if (exists(d, r.device, r.boot, r.elapsed)) {
                out[0] = KNOWN;
            } else if (minGapS > 0 && newestElapsed(d, r.device, r.boot) > r.elapsed - minGapS * 1000) {
                out[0] = TOO_SOON;
            } else {
                d.execSQL("INSERT OR IGNORE INTO phone_readings(device, boot, elapsed, counter, wall) VALUES (?, ?, ?, ?, ?)",
                        new Object[] {r.device, r.boot, r.elapsed, r.counter, r.wall});
                // a new boot's first reading may count since its start (a restart seen): its device is worked out again
                if (String.valueOf(PhoneSteps.RULE).equals(kv(d))) rebuildDevice(d, r.device);
                else rebuildAll(d);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return out[0];
    }

    /** Stores many readings (the demo's) and works every interval out again, in one transaction; known ones are kept. */
    public static void addAll(Db db, List<PhoneSteps.Reading> readings) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            SQLiteStatement st = d.compileStatement("INSERT OR IGNORE INTO phone_readings(device, boot, elapsed, counter, wall) VALUES (?, ?, ?, ?, ?)");
            for (PhoneSteps.Reading r : readings) {
                st.bindString(1, r.device);
                st.bindLong(2, r.boot);
                st.bindLong(3, r.elapsed);
                st.bindLong(4, r.counter);
                st.bindDouble(5, r.wall);
                st.executeInsert();
            }
            st.close();
            rebuildAll(d);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    private static boolean exists(SQLiteDatabase d, String device, long boot, long elapsed) {
        Cursor c = d.rawQuery("SELECT 1 FROM phone_readings WHERE device = ? AND boot = ? AND elapsed = ?",
                new String[] {device, String.valueOf(boot), String.valueOf(elapsed)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private static long newestElapsed(SQLiteDatabase d, String device, long boot) {
        Cursor c = d.rawQuery("SELECT MAX(elapsed) FROM phone_readings WHERE device = ? AND boot = ?", new String[] {device, String.valueOf(boot)});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : Long.MIN_VALUE / 2;
        } finally {
            c.close();
        }
    }

    private static String kv(SQLiteDatabase d) {
        Cursor c = d.rawQuery("SELECT v FROM kv WHERE k = ?", new String[] {RULE});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** The readings of one boot, by elapsed time. */
    private static List<PhoneSteps.Reading> readingsOf(SQLiteDatabase d, String where, String[] args) {
        List<PhoneSteps.Reading> out = new ArrayList<PhoneSteps.Reading>();
        Cursor c = d.rawQuery("SELECT device, boot, elapsed, counter, wall FROM phone_readings" + where + " ORDER BY device, boot, elapsed", args);
        try {
            while (c.moveToNext()) out.add(new PhoneSteps.Reading(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getDouble(4)));
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * One phone's intervals worked out again, inside the caller's transaction (they depend only on that phone's readings: a
     * boot's first reading needs the boot before it). Hourly readings make a few thousand rows a year, worked out in moments.
     */
    static void rebuildDevice(SQLiteDatabase d, String device) {
        d.execSQL("DELETE FROM phone_steps WHERE device = ?", new Object[] {device});
        write(d, PhoneSteps.intervals(readingsOf(d, " WHERE device = ?", new String[] {device})));
    }

    /**
     * Every interval worked out again from all readings, inside the caller's transaction, and the rule version noted: the
     * caller commits only when it returns, so any stop keeps the old rows.
     */
    static void rebuildAll(SQLiteDatabase d) {
        d.execSQL("DELETE FROM phone_steps");
        write(d, PhoneSteps.intervals(readingsOf(d, "", null)));
        d.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", new Object[] {RULE, String.valueOf(PhoneSteps.RULE)});
    }

    private static void write(SQLiteDatabase d, List<PhoneSteps.Interval> all) {
        SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO phone_steps(device, boot, elapsed, start, stop, steps) VALUES (?, ?, ?, ?, ?, ?)");
        int n = 0;
        try {
            for (PhoneSteps.Interval i : all) {
                if (failRebuildAfter >= 0 && n >= failRebuildAfter) throw new IllegalStateException("test stop in the rebuild");
                st.bindString(1, i.device);
                st.bindLong(2, i.boot);
                st.bindLong(3, i.elapsed);
                st.bindDouble(4, i.start);
                st.bindDouble(5, i.stop);
                st.bindLong(6, i.steps);
                st.executeInsert();
                n++;
            }
        } finally {
            st.close();
        }
    }

    /** Every interval worked out again, in one transaction (a new rule; Re analyse all leaves them, they are not analysis). */
    public static void rebuild(Db db) {
        final SQLiteDatabase d = db.getWritableDatabase();
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                rebuildAll(d);
            }
        });
    }

    /** Forgets which rule the stored intervals come from (the schema step), so {@link #ensureRule} works them out again. */
    static void ruleUnknown(SQLiteDatabase d) {
        d.execSQL("DELETE FROM kv WHERE k = ?", new Object[] {RULE});
    }

    /**
     * The phone's intervals around one day for the screens ({@code ui/DayData}, in the load's background task): first the
     * intervals are worked out again when their rule is not this version's (a new rule, or an upgrade that stopped half way),
     * unless an analysis runs (its transaction would hold the write; the next load does it). Then {@link #window}.
     */
    public static app.openring.core.MainDevice.Phone forDay(Db db, double from, double to) {
        if (!Analyzer.running()) ensureRule(db);
        return window(db, from, to);
    }

    /** Works the intervals out again when the rule changed since they were stored (or never noted); returns whether it did. */
    public static boolean ensureRule(Db db) {
        if (!any(db) || String.valueOf(PhoneSteps.RULE).equals(db.kv(RULE, null))) return false;
        rebuild(db);
        return true;
    }

    // ------------------------------------------------------------------------------------- reading

    /** True when any reading is stored. */
    public static boolean any(Db db) {
        return db.count("SELECT COUNT(*) FROM (SELECT 1 FROM phone_readings LIMIT 1)") > 0;
    }

    /** The intervals that reach into {@code from} to {@code to}, for one day's main device rule ({@code core/MainDevice}). */
    public static app.openring.core.MainDevice.Phone window(Db db, double from, double to) {
        app.openring.core.MainDevice.Phone p = new app.openring.core.MainDevice.Phone();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT start, stop, steps FROM phone_steps WHERE stop > ? AND start < ? ORDER BY start",
                new String[] {String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) p.intervals.add(new double[] {c.getDouble(0), c.getDouble(1), c.getLong(2)});
        } finally {
            c.close();
        }
        return p;
    }

    /** The wall time of the newest reading (unix seconds), 0 for none (Settings, Phone). */
    public static double lastReading(Db db) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MAX(wall) FROM phone_readings", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    /** The newest end of an interval (unix seconds), NaN for none: the day a phone only person follows ({@link Db#latestDay}). */
    static double newestStop(SQLiteDatabase d) {
        Cursor c = d.rawQuery("SELECT MAX(stop) FROM phone_steps", null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN;
        } finally {
            c.close();
        }
    }

    /** Every reading (the Data check; a reading a day for years is a few thousand rows). */
    public static List<PhoneSteps.Reading> readings(Db db) {
        return readingsOf(db.getReadableDatabase(), "", null);
    }

    /** The intervals ending after {@code from} (the Data check's last days). */
    static List<PhoneSteps.Interval> intervalsSince(Db db, double from) {
        List<PhoneSteps.Interval> out = new ArrayList<PhoneSteps.Interval>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT device, boot, elapsed, start, stop, steps FROM phone_steps WHERE stop > ? ORDER BY start",
                new String[] {String.valueOf(from)});
        try {
            while (c.moveToNext()) out.add(new PhoneSteps.Interval(c.getDouble(3), c.getDouble(4), c.getLong(5), c.getString(0), c.getLong(1), c.getLong(2)));
        } finally {
            c.close();
        }
        return out;
    }

    /** How many days the Data check lists the phone's day totals for. */
    static final int CHECK_DAYS = 14;

    /** The Data check's lines (issue #329): counts, dates and day totals only, never a clock time (see {@code PhoneSteps.checkLines}). */
    static String checkLines(Db db, Prefs p, java.time.LocalDate today, java.time.ZoneId zone) {
        double from = today.minusDays(CHECK_DAYS).atStartOfDay(zone).toEpochSecond();
        // the sensor's missing values belong to the real data: the demo's Data check names none
        boolean demo = Db.DEMO.equals(db.fileName);
        return PhoneSteps.checkLines(readings(db), intervalsSince(db, from), demo ? 0 : p.phoneNoReading(), demo ? 0 : p.phoneNoReadingAt(), today, CHECK_DAYS, zone);
    }

    // ---------------------------------------------------------------------------- restore (issue #329)

    /** Reads every reading of {@code d}, before a restore swaps the file (a file without the table gives none). */
    static List<Object[]> read(SQLiteDatabase d) {
        List<Object[]> out = new ArrayList<Object[]>();
        Cursor c = d.rawQuery("SELECT device, boot, elapsed, counter, wall FROM phone_readings", null);
        try {
            while (c.moveToNext()) out.add(new Object[] {c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getDouble(4)});
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Adds the phone's readings the restored file {@code d} lacks (insert, never replace: a reading with the same key is the
     * same reading) and works every interval out again, in one transaction. Running it twice gives the same result.
     */
    static void merge(SQLiteDatabase d, List<Object[]> rows) {
        d.beginTransaction();
        try {
            for (Object[] row : rows) d.execSQL("INSERT OR IGNORE INTO phone_readings(device, boot, elapsed, counter, wall) VALUES (?, ?, ?, ?, ?)", row);
            rebuildAll(d);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
