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
        List<Day> out = new ArrayList<Day>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, flow, src, made FROM period_days WHERE day >= ? AND day <= ? ORDER BY day",
                new String[] {date(from), date(to)});
        try {
            while (c.moveToNext()) out.add(new Day(c.getString(0), c.getInt(1), c.getLong(2), c.getDouble(3)));
        } finally {
            c.close();
        }
        return out;
    }

    /** Every logged day, oldest first, removal marks included. */
    public static List<Day> all(Db db) {
        return days(db, "0000-01-01", "9999-12-31");
    }

    /** The end marks from {@code from} to {@code to} (both included), oldest first; cleared ones left out. */
    public static List<String> ends(Db db, String from, String to) {
        List<String> out = new ArrayList<String>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day FROM period_ends WHERE day >= ? AND day <= ? AND gone = 0 ORDER BY day",
                new String[] {date(from), date(to)});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** Every end mark, oldest first. */
    public static List<String> allEnds(Db db) {
        return ends(db, "0000-01-01", "9999-12-31");
    }

    /** {@code day} when it is a real calendar date written yyyy-mm-dd, else an IllegalArgumentException. */
    static String date(String day) {
        if (day == null || day.length() != 10) throw new IllegalArgumentException("not a date: " + day);
        if ("0000-01-01".equals(day) || "9999-12-31".equals(day)) return day; // the open ends of a range
        try {
            if (!java.time.LocalDate.parse(day).toString().equals(day)) throw new IllegalArgumentException("not a date: " + day);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("not a date: " + day, e);
        }
        return day;
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
