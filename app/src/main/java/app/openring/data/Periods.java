package app.openring.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * The period log (issue #252): the days the person logged with their amount, the days marked as the end of a period,
 * and (for the drip import, phase 1b) imported temperatures and the imports themselves. Tables and columns are described
 * at {@code Db.upgradeTo8}. Days are calendar dates as text (yyyy-mm-dd), so travel never moves a logged day. Each write
 * is a single statement. Nothing here starts an analysis, and nothing of it is ever listed by the Data check.
 *
 * A restore carries these rows over from the phone into the restored history ({@link Db#replaceWith}): the only rows
 * ever merged, since a typed day cannot be downloaded again. "Nothing" on a day therefore never deletes its row but keeps
 * a removal mark ({@link #REMOVED}), so a restore cannot bring the day back from the backup.
 *
 * Writers get the database with {@code Db.get(c)} for each write and never keep a {@code Db} across calls: a restore
 * ({@link Db#replaceWith}) closes the instance and swaps the file under it, and a kept {@code Db} would open the file by
 * name again and could write into it while it is being replaced (review of #275).
 */
public final class Periods {
    private Periods() {}

    /** Amounts of a day: removed by the person ("Nothing"), spotting, light, medium, heavy. */
    public static final int REMOVED = -1, SPOTTING = 0, LIGHT = 1, MEDIUM = 2, HEAVY = 3;

    /** The tables of the period log: kept when another ring is paired, wiped by Delete all local data. */
    static final String[] TABLES = {"period_days", "period_ends", "period_temps", "period_imports"};

    /** Each table's columns and their types (T text, I integer, R real), in the order {@link Rows} keeps them. */
    private static final String[][] COLUMNS = {
        {"day, flow, src, prev, made", "TIIIR"},
        {"day, made, gone", "TRI"},
        {"day, time, c, excluded, src, gone", "TTRIII"},
        {"id, made, days, temps, gone", "IRIII"},
    };

    /** One row of {@code period_days}. */
    public static final class Day {
        public final String day;
        public final int flow;
        /** 0 typed on this phone, else the id of the import that added it. */
        public final long src;
        /** Unix seconds of the entry. */
        public final double made;

        Day(String day, int flow, long src, double made) {
            this.day = day;
            this.flow = flow;
            this.src = src;
            this.made = made;
        }

        /** The person set this day to "Nothing": it counts as no entry. */
        public boolean removed() {
            return flow == REMOVED;
        }

        @Override
        public String toString() {
            return day + " " + flow + " " + src + " " + made;
        }
    }

    /** Sets the amount of {@code day} ({@link #REMOVED} to {@link #HEAVY}), typed now. */
    public static void setFlow(Db db, String day, int flow) {
        setFlow(db, day, flow, System.currentTimeMillis() / 1000.0);
    }

    /**
     * Sets the amount of {@code day} as typed by the person at {@code made} (unix seconds): one statement, replacing what
     * the day held (also an imported amount, which then counts as typed). {@link #REMOVED} keeps the row as a removal mark.
     */
    public static void setFlow(Db db, String day, int flow, double made) {
        if (flow < REMOVED || flow > HEAVY) throw new IllegalArgumentException("flow " + flow);
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO period_days(day, flow, src, prev, made) VALUES (?, ?, 0, NULL, ?)",
                new Object[] {date(day), (long) flow, made});
    }

    /** "Nothing" on {@code day}: a removal mark, never a delete. */
    public static void remove(Db db, String day) {
        setFlow(db, day, REMOVED);
    }

    /** Marks {@code day} as the end of a period (once per day). */
    public static void setEnd(Db db, String day) {
        setEnd(db, day, System.currentTimeMillis() / 1000.0);
    }

    public static void setEnd(Db db, String day, double made) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO period_ends(day, made, gone) VALUES (?, ?, 0)", new Object[] {date(day), made});
    }

    /** Takes the end mark off {@code day}, typed now. */
    public static void clearEnd(Db db, String day) {
        clearEnd(db, day, System.currentTimeMillis() / 1000.0);
    }

    /**
     * Takes the end mark off {@code day} at {@code made}: one statement that keeps the row as a removal mark ({@code gone} 1),
     * never a delete, so a restore cannot bring the end back from the backup. Readers leave such rows out.
     */
    public static void clearEnd(Db db, String day, double made) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO period_ends(day, made, gone) VALUES (?, ?, 1)", new Object[] {date(day), made});
    }

    /** The logged days from {@code from} to {@code to} (both included, yyyy-mm-dd), oldest first, removal marks included. */
    public static List<Day> days(Db db, String from, String to) {
        return readDays(db, "WHERE day >= ? AND day <= ? ", new String[] {date(from), date(to)});
    }

    /** Every logged day, oldest first, removal marks included. */
    public static List<Day> all(Db db) {
        return readDays(db, "", null);
    }

    /** The row of {@code day} (a removal mark included), or null when the day was never logged. */
    public static Day day(Db db, String day) {
        List<Day> l = readDays(db, "WHERE day = ? ", new String[] {date(day)});
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * The period tracker's estimate on {@code today} from the whole log (issue #252): every logged day with its amount (removal
     * marks count as no entry) and every end mark in force. Reads only; nothing is stored.
     */
    public static app.openring.core.analysis.Cycles cycles(Db db, java.time.LocalDate today) {
        return cycles(db, today, null);
    }

    /** As above, with the day the person said "My periods have stopped" (null when not; phase 1a2). */
    public static app.openring.core.analysis.Cycles cycles(Db db, java.time.LocalDate today, java.time.LocalDate stoppedSince) {
        return cycles(db.getReadableDatabase(), today, stoppedSince);
    }

    /** The estimate from the period tables of {@code d}: every day and every end mark in force. Reads only. */
    static app.openring.core.analysis.Cycles cycles(SQLiteDatabase d, java.time.LocalDate today, java.time.LocalDate stoppedSince) {
        java.util.Map<java.time.LocalDate, Integer> flows = new java.util.HashMap<java.time.LocalDate, Integer>();
        Cursor c = d.rawQuery("SELECT day, flow FROM period_days ORDER BY day", null);
        try {
            while (c.moveToNext()) flows.put(java.time.LocalDate.parse(c.getString(0)), c.getInt(1));
        } finally {
            c.close();
        }
        List<java.time.LocalDate> ends = new ArrayList<java.time.LocalDate>();
        c = d.rawQuery("SELECT day FROM period_ends WHERE gone = 0 ORDER BY day", null);
        try {
            while (c.moveToNext()) ends.add(java.time.LocalDate.parse(c.getString(0)));
        } finally {
            c.close();
        }
        return app.openring.core.analysis.Cycles.of(flows, ends, today, stoppedSince);
    }

    /** A damaged page found while reading for the reminder leaves the file as it is (Android's default would delete it). */
    private static final android.database.DatabaseErrorHandler KEEP_FILE = new android.database.DatabaseErrorHandler() {
        @Override
        public void onCorruption(SQLiteDatabase db) {}
    };

    /**
     * The reminder's read only helper (issue #252, phase 1a2; Design review): the estimate from the person's own log in
     * the real file {@code openring.db}, also while demo mode is on, without {@link Db#get}: no {@code Db} is opened,
     * closed or swapped, nothing is upgraded or written, and the demo file is never read. One read only connection that
     * is closed again. Null when the real file does not exist (no log); any other problem (a file from before the
     * period tables, a damaged page, a swap under way) throws, and the caller keeps what it had planned.
     */
    public static app.openring.core.analysis.Cycles readReal(android.content.Context c, java.time.LocalDate today, java.time.LocalDate stoppedSince) {
        java.io.File f = c.getDatabasePath(Db.REAL);
        if (!f.isFile()) return null;
        SQLiteDatabase d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP_FILE);
        try {
            return cycles(d, today, stoppedSince);
        } finally {
            d.close();
        }
    }

    private static List<Day> readDays(Db db, String where, String[] args) {
        List<Day> out = new ArrayList<Day>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, flow, src, made FROM period_days " + where + "ORDER BY day", args);
        try {
            while (c.moveToNext()) out.add(new Day(c.getString(0), c.getInt(1), c.getLong(2), c.getDouble(3)));
        } finally {
            c.close();
        }
        return out;
    }

    /** The end marks from {@code from} to {@code to} (both included), oldest first; cleared ones left out. */
    public static List<String> ends(Db db, String from, String to) {
        return readEnds(db, "AND day >= ? AND day <= ? ", new String[] {date(from), date(to)});
    }

    /** Every end mark, oldest first; cleared ones left out. */
    public static List<String> allEnds(Db db) {
        return readEnds(db, "", null);
    }

    private static List<String> readEnds(Db db, String range, String[] args) {
        List<String> out = new ArrayList<String>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day FROM period_ends WHERE gone = 0 " + range + "ORDER BY day", args);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** The years a logged day can lie in: no person logs a period in another one, and 0000 or 9999 are placeholders. */
    static final int FIRST_YEAR = 1900, LAST_YEAR = 2999;

    /** {@code day} when it is a real calendar date written yyyy-mm-dd in a year a person can log, else an IllegalArgumentException. */
    static String date(String day) {
        if (day == null || day.length() != 10) throw new IllegalArgumentException("not a date: " + day);
        java.time.LocalDate d;
        try {
            d = java.time.LocalDate.parse(day);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("not a date: " + day, e);
        }
        if (!d.toString().equals(day) || d.getYear() < FIRST_YEAR || d.getYear() > LAST_YEAR) throw new IllegalArgumentException("not a date: " + day);
        return day;
    }

    // ------------------------------------------------------------- the drip import (phase 1b)

    /** One row of {@code period_temps}: an imported mouth temperature. */
    public static final class Temp {
        public final String day;
        public final app.openring.core.DripCsv.Temp t;
        /** The import that added it. */
        public final long src;
        /** The import was undone: a removal mark, no reading. */
        public final boolean gone;

        Temp(String day, app.openring.core.DripCsv.Temp t, long src, boolean gone) {
            this.day = day;
            this.t = t;
            this.src = src;
            this.gone = gone;
        }
    }

    /** One import in force (not undone). */
    public static final class Import {
        /** Its time in milliseconds, also the {@code src} of the rows it added. */
        public final long id;
        /** Unix seconds. */
        public final double made;
        /** The days and temperatures it added. */
        public final int days, temps;

        Import(long id, double made, int days, int temps) {
            this.id = id;
            this.made = made;
            this.days = days;
            this.temps = temps;
        }
    }

    /** Every imported temperature, undone ones included (the preview needs both), oldest first. */
    public static List<Temp> allTemps(Db db) {
        return readTemps(db.getReadableDatabase(), "", null);
    }

    /** The imported temperatures in force from {@code from} to {@code to} (both included), oldest first. */
    public static java.util.TreeMap<java.time.LocalDate, app.openring.core.DripCsv.Temp> temps(Db db, String from, String to) {
        java.util.TreeMap<java.time.LocalDate, app.openring.core.DripCsv.Temp> out = new java.util.TreeMap<java.time.LocalDate, app.openring.core.DripCsv.Temp>();
        for (Temp t : readTemps(db.getReadableDatabase(), "WHERE gone = 0 AND day >= ? AND day <= ? ", new String[] {date(from), date(to)})) {
            out.put(java.time.LocalDate.parse(t.day), t.t);
        }
        return out;
    }

    private static List<Temp> readTemps(SQLiteDatabase d, String where, String[] args) {
        List<Temp> out = new ArrayList<Temp>();
        Cursor c = d.rawQuery("SELECT day, time, c, excluded, src, gone FROM period_temps " + where + "ORDER BY day", args);
        try {
            while (c.moveToNext()) {
                out.add(new Temp(c.getString(0), new app.openring.core.DripCsv.Temp(c.getDouble(2), c.isNull(1) ? null : c.getString(1), c.getInt(3) != 0),
                        c.getLong(4), c.getInt(5) != 0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** The imports in force (not undone), newest first. */
    public static List<Import> imports(Db db) {
        List<Import> out = new ArrayList<Import>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT id, made, days, temps FROM period_imports WHERE gone = 0 ORDER BY id DESC", null);
        try {
            while (c.moveToNext()) out.add(new Import(c.getLong(0), c.getDouble(1), c.getInt(2), c.getInt(3)));
        } finally {
            c.close();
        }
        return out;
    }

    /** The phone's days as the preview compares them: every day with its amount, removal marks as {@link #REMOVED}. */
    public static java.util.TreeMap<java.time.LocalDate, Integer> flowsForImport(Db db) {
        java.util.TreeMap<java.time.LocalDate, Integer> out = new java.util.TreeMap<java.time.LocalDate, Integer>();
        for (Day d : all(db)) out.put(java.time.LocalDate.parse(d.day), d.flow);
        return out;
    }

    /** What an import wrote: its id (0 when it had nothing to write, then nothing at all was written), days and temperatures. */
    public static final class Imported {
        public final long id;
        public final int days, temps;

        Imported(long id, int days, int temps) {
            this.id = id;
            this.days = days;
            this.temps = temps;
        }
    }

    /** Tests only: the import throws after this many rows were written, as a stop halfway would (-1: never). */
    static volatile int failImportAfter = -1;

    /**
     * Imports a drip file's days ({@code flows}, 0 spotting to 3 heavy) and mouth temperatures into the period log, in ONE
     * transaction: a stop or failure on the way saves nothing. The decision of each day and temperature is made again
     * inside the transaction from the rows there now ({@link app.openring.core.DripImport#day}, {@code temp}), so a day
     * logged between the preview and the tap keeps winning: days already logged win, unless {@code takeFile}; then the
     * replaced amount goes into {@code prev}, so undoing puts it back. Temperatures already there are never replaced. Rows
     * it adds carry {@code src} = the import's id. With nothing to write (the same file again) nothing is written, not
     * even an import row, and the id is 0. The id is the time in milliseconds, one higher while it is taken.
     */
    public static Imported importDrip(Db db, final java.util.Map<java.time.LocalDate, Integer> flows,
            final java.util.Map<java.time.LocalDate, app.openring.core.DripCsv.Temp> temps, final boolean takeFile, long idTime, final double made) {
        for (java.time.LocalDate d : flows.keySet()) date(d.toString());
        for (java.time.LocalDate d : temps.keySet()) date(d.toString());
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            java.util.Map<java.time.LocalDate, Integer> have = flowsForImport(db);
            java.util.Map<java.time.LocalDate, Temp> haveT = new java.util.HashMap<java.time.LocalDate, Temp>();
            for (Temp t : readTemps(w, "", null)) haveT.put(java.time.LocalDate.parse(t.day), t);
            long id = idTime;
            while (count(w, "SELECT COUNT(*) FROM period_imports WHERE id = ?", String.valueOf(id)) > 0) id++;
            int days = 0, nt = 0, written = 0;
            for (java.util.Map.Entry<java.time.LocalDate, Integer> e : flows.entrySet()) {
                int f = e.getValue();
                if (f < SPOTTING || f > HEAVY) throw new IllegalArgumentException("flow " + f);
                Integer h = have.get(e.getKey());
                int k = app.openring.core.DripImport.day(f, h);
                if (k == app.openring.core.DripImport.SAME || (k == app.openring.core.DripImport.DIFFER && !takeFile)) continue;
                w.execSQL("INSERT OR REPLACE INTO period_days(day, flow, src, prev, made) VALUES (?, ?, ?, ?, ?)",
                        new Object[] {e.getKey().toString(), (long) f, id, h == null ? null : (Object) (long) (int) h, made});
                days++;
                stop(++written);
            }
            for (java.util.Map.Entry<java.time.LocalDate, app.openring.core.DripCsv.Temp> e : temps.entrySet()) {
                Temp h = haveT.get(e.getKey());
                if (app.openring.core.DripImport.temp(e.getValue(), h == null ? null : h.t, h != null && h.gone) != app.openring.core.DripImport.ADD) continue;
                app.openring.core.DripCsv.Temp t = e.getValue();
                w.execSQL("INSERT OR REPLACE INTO period_temps(day, time, c, excluded, src, gone) VALUES (?, ?, ?, ?, ?, 0)",
                        new Object[] {e.getKey().toString(), t.time, t.c, t.excluded ? 1L : 0L, id});
                nt++;
                stop(++written);
            }
            if (days + nt == 0) {
                w.setTransactionSuccessful();
                return new Imported(0, 0, 0);
            }
            w.execSQL("INSERT INTO period_imports(id, made, days, temps, gone) VALUES (?, ?, ?, ?, 0)", new Object[] {id, made, (long) days, (long) nt});
            w.setTransactionSuccessful();
            return new Imported(id, days, nt);
        } finally {
            w.endTransaction();
        }
    }

    private static void stop(int written) {
        if (written == failImportAfter) throw new IllegalStateException("test stop after " + written + " rows");
    }

    private static long count(SQLiteDatabase d, String sql, String arg) {
        Cursor c = d.rawQuery(sql, new String[] {arg});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Undoes the import {@code id} as a whole, in one transaction, removing exactly the rows it added, never a row logged
     * in Open Ring: each day it still holds ({@code src} = id; a day the person changed since counts as typed and stays)
     * gets back the amount it replaced ({@code prev}, as typed) or, where it added the day, a removal mark that keeps the
     * import's id (no entry, so a restore cannot bring the day back); its temperatures and the import get {@code gone} 1.
     * Nothing is deleted. False when there is no such import in force (also the second time).
     */
    public static boolean undoImport(Db db, long id, double made) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            if (count(w, "SELECT COUNT(*) FROM period_imports WHERE gone = 0 AND id = ?", String.valueOf(id)) == 0) return false;
            Object[] a = {made, id};
            // first the days it replaced (back to the person's amount), then the days it added (a mark with its id)
            w.execSQL("UPDATE period_days SET flow = prev, src = 0, prev = NULL, made = ? WHERE src = ? AND prev IS NOT NULL", a);
            w.execSQL("UPDATE period_days SET flow = -1, made = ? WHERE src = ? AND prev IS NULL", a);
            w.execSQL("UPDATE period_temps SET gone = 1 WHERE src = ?", new Object[] {id});
            w.execSQL("UPDATE period_imports SET gone = 1 WHERE id = ?", new Object[] {id});
            w.setTransactionSuccessful();
            return true;
        } finally {
            w.endTransaction();
        }
    }

    // ------------------------------------------------------------- the restore merge (Db.replaceWith)

    /** Every row of the period tables of one file, as read before a restore's swap. */
    static final class Rows {
        /** Per table of {@link #TABLES}, its rows with the values of {@link #COLUMNS}. */
        final List<List<Object[]>> tables = new ArrayList<List<Object[]>>();
    }

    /** Reads every row of the period tables of {@code d} (one with none gives empty lists). */
    static Rows read(SQLiteDatabase d) {
        Rows r = new Rows();
        for (int t = 0; t < TABLES.length; t++) {
            List<Object[]> rows = new ArrayList<Object[]>();
            String types = COLUMNS[t][1];
            Cursor c = d.rawQuery("SELECT " + COLUMNS[t][0] + " FROM " + TABLES[t], null);
            try {
                while (c.moveToNext()) {
                    Object[] row = new Object[types.length()];
                    for (int i = 0; i < row.length; i++) {
                        if (c.isNull(i)) continue;
                        char k = types.charAt(i);
                        row[i] = k == 'T' ? c.getString(i) : k == 'I' ? (Object) c.getLong(i) : (Object) c.getDouble(i);
                    }
                    rows.add(row);
                }
            } finally {
                c.close();
            }
            r.tables.add(rows);
        }
        return r;
    }

    /**
     * Writes {@code r} into {@code d} in one transaction, each row replacing the one with the same key (the same day, or
     * the same import), so the rows read from the phone win over the backup's. Running it twice gives the same result.
     */
    static void merge(SQLiteDatabase d, Rows r) throws java.io.IOException {
        d.beginTransaction();
        try {
            for (int t = 0; t < TABLES.length; t++) {
                String cols = COLUMNS[t][0];
                StringBuilder q = new StringBuilder();
                for (int i = 0; i < COLUMNS[t][1].length(); i++) q.append(i == 0 ? "?" : ", ?");
                String sql = "INSERT OR REPLACE INTO " + TABLES[t] + "(" + cols + ") VALUES (" + q + ")";
                for (Object[] row : r.tables.get(t)) d.execSQL(sql, row);
                if (t == 0) Db.mergeStep(Db.MERGE_WRITE);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
