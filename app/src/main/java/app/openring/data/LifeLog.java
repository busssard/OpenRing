package app.openring.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.openring.core.LifeTags;

/**
 * The life factors the person logs (issue #292, phase 1): which tags a calendar day has, and the person's own tags and
 * chips. Stored like the period log ({@link Periods}): calendar dates as text, so travel never moves a day; only added to;
 * taking a tag off a day keeps the row as a removal mark ({@code gone} 1), never a delete, so a restore cannot bring it back;
 * kept when another ring is paired ({@code Db.KEPT_TABLES}), wiped by Delete all; carried from the phone into a restored
 * backup, the phone's row winning ({@link #read}, {@link #merge}). Nothing here starts an analysis or changes a score, and
 * nothing of it is ever listed by the Data check, written to the log or shown in a notification.
 * <ul>
 * <li>{@code life_days}: one row per day and tag; {@code time} "HH:MM" or null (phase 1 stores none); {@code span} the id
 * shared by the days of one several day entry (its time in milliseconds), null for a single day; {@code made} unix seconds;
 * {@code gone} 1 when the person took the tag off the day.</li>
 * <li>{@code life_tags}: one row per tag the person changed: own tags with their {@code name}, and for any tag whether it is
 * on Today's row ({@code shown} 1 or 0, null as by default) and its place there ({@code ord}); {@code gone} 1 when an own tag
 * was removed (its days stay).</li>
 * </ul>
 * Writers get the database with {@code Db.get(c)} for each write and never keep a {@code Db} across calls (a restore swaps the
 * file, see {@link Periods}).
 */
public final class LifeLog {
    private LifeLog() {}

    /** Both tables: kept when another ring is paired, emptied by Delete all. */
    static final String[] TABLES = {"life_days", "life_tags"};

    /** Each table's columns and their types (T text, I integer, R real), in the order {@link Rows} keeps them. */
    private static final String[][] COLUMNS = {
        {"day, tag, time, span, made, gone", "TTTIRI"},
        {"key, name, shown, ord, made, gone", "TTIIRI"},
    };

    /**
     * Schema 12: only adds, each table on its own ({@code IF NOT EXISTS}), safe to run twice, and an upgrade stopped after the
     * first goes on with the second. Older files (and restored older backups) get them empty. No other table or row changes.
     */
    static void create12(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS life_days (day TEXT NOT NULL, tag TEXT NOT NULL, time TEXT, span INTEGER, made REAL NOT NULL,"
                + " gone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, tag))");
        db.execSQL("CREATE TABLE IF NOT EXISTS life_tags (key TEXT PRIMARY KEY, name TEXT, shown INTEGER, ord INTEGER, made REAL NOT NULL,"
                + " gone INTEGER NOT NULL DEFAULT 0)");
    }

    // ------------------------------------------------------------------------------------- one day's row

    /** One row of {@code life_days}, removal marks included. */
    public static final class Row {
        public final String day, tag;
        public final Long span;
        public final double made;
        public final boolean gone;

        Row(String day, String tag, Long span, double made, boolean gone) {
            this.day = day;
            this.tag = tag;
            this.span = span;
            this.made = made;
            this.gone = gone;
        }

        @Override
        public String toString() {
            return day + " " + tag + " " + span + " " + made + (gone ? " gone" : "");
        }
    }

    /** A day that can be stored (yyyy-mm-dd, the years the period log takes); the value never goes into the message. */
    static String date(String day) {
        try {
            return Periods.date(day);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a date");
        }
    }

    static String tag(String key) {
        if (!LifeTags.valid(key)) throw new IllegalArgumentException("not a tag");
        return key;
    }

    /** {@code tag} on {@code day} on or off, typed now: one statement. */
    public static void set(Db db, String day, String tag, boolean on) {
        set(db, day, tag, on, System.currentTimeMillis() / 1000.0);
    }

    /**
     * {@code tag} on {@code day} on ({@code on}) or taken off at {@code made} (unix seconds): one statement replacing the day's
     * row of that tag. Taking it off keeps the row as a removal mark, never a delete.
     */
    public static void set(Db db, String day, String tag, boolean on, double made) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, span, made, gone) VALUES (?, ?, NULL, NULL, ?, ?)",
                new Object[] {date(day), tag(tag), made, on ? 0L : 1L});
    }

    /** Puts {@code r} back as it was (Undo); {@code before} null (the day never had the tag) leaves a removal mark. */
    public static void restore(Db db, String day, String tag, Row before, double made) {
        if (before == null) {
            set(db, day, tag, false, made);
            return;
        }
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, span, made, gone) VALUES (?, ?, NULL, ?, ?, ?)",
                new Object[] {date(before.day), tag(before.tag), before.span, before.made, before.gone ? 1L : 0L});
    }

    /** Tests only: a several day entry throws after this many rows, as a stop half way would. */
    static volatile int failSpanAfter = -1;

    /**
     * {@code tag} on every day from {@code from} to {@code to} (both included), one row per day sharing the span id
     * {@code span}, in one transaction: a stop half way writes none of them. Returns the days written; none when {@code to} is
     * before {@code from}.
     */
    public static int setSpan(Db db, LocalDate from, LocalDate to, String tag, long span, double made) {
        List<LocalDate> days = LifeTags.span(from, to);
        for (LocalDate d : days) date(d.toString());
        tag(tag);
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            int n = 0;
            for (LocalDate d : days) {
                w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, span, made, gone) VALUES (?, ?, NULL, ?, ?, 0)",
                        new Object[] {d.toString(), tag, span, made});
                if (++n == failSpanAfter) throw new IllegalStateException("test stop");
            }
            w.setTransactionSuccessful();
            return n;
        } finally {
            w.endTransaction();
        }
    }

    /** The row of {@code tag} on {@code day} (a removal mark included), or null when there never was one. */
    public static Row row(Db db, String day, String tag) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, tag, span, made, gone FROM life_days WHERE day = ? AND tag = ?",
                new String[] {date(day), tag});
        try {
            return c.moveToFirst() ? new Row(c.getString(0), c.getString(1), c.isNull(2) ? null : c.getLong(2), c.getDouble(3), c.getInt(4) != 0) : null;
        } finally {
            c.close();
        }
    }

    /** The tags in force on each day from {@code from} to {@code to} (both included), by day, each day's tags in key order. */
    public static TreeMap<LocalDate, List<String>> days(Db db, String from, String to) {
        return days(db.getReadableDatabase(), "WHERE gone = 0 AND day >= ? AND day <= ? ", new String[] {date(from), date(to)});
    }

    /** Every day with a tag in force, oldest first. */
    public static TreeMap<LocalDate, List<String>> all(Db db) {
        return days(db.getReadableDatabase(), "WHERE gone = 0 ", null);
    }

    /** The tags in force on {@code day}, in key order (empty when none). */
    public static List<String> on(Db db, String day) {
        List<String> l = days(db, day, day).get(LocalDate.parse(day));
        return l == null ? new ArrayList<String>() : l;
    }

    private static TreeMap<LocalDate, List<String>> days(SQLiteDatabase d, String where, String[] args) {
        TreeMap<LocalDate, List<String>> out = new TreeMap<LocalDate, List<String>>();
        Cursor c = d.rawQuery("SELECT day, tag FROM life_days " + where + "ORDER BY day, tag", args);
        try {
            while (c.moveToNext()) {
                LocalDate day;
                try {
                    day = LocalDate.parse(c.getString(0));
                } catch (RuntimeException e) {
                    continue; // only a damaged row could hold something else; it is not shown
                }
                List<String> l = out.get(day);
                if (l == null) out.put(day, l = new ArrayList<String>());
                l.add(c.getString(1));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Whether the file holds any life factor row (the demo build checks its own was written). */
    public static boolean any(Db db) {
        return db.count("SELECT (SELECT COUNT(*) FROM life_days) + (SELECT COUNT(*) FROM life_tags)") > 0;
    }

    // ------------------------------------------------------------------------------------- the tags and chips

    /** Every stored choice (own tags, chips), as {@code core/LifeTags.Known} reads them. */
    public static List<LifeTags.Choice> choices(Db db) {
        List<LifeTags.Choice> out = new ArrayList<LifeTags.Choice>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT key, name, shown, ord, gone FROM life_tags ORDER BY key", null);
        try {
            while (c.moveToNext()) {
                out.add(new LifeTags.Choice(c.getString(0), c.isNull(1) ? null : c.getString(1), c.isNull(2) ? null : c.getInt(2),
                        c.isNull(3) ? null : c.getInt(3), c.getInt(4) != 0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public static LifeTags.Known known(Db db) {
        return new LifeTags.Known(choices(db));
    }

    /**
     * A new own tag named {@code typed} (cleaned by {@code LifeTags.cleanName}), on Today's row, made at {@code millis}. Returns
     * its key, or null when the name cannot be a tag or an own tag in force already has it (any case). The name of a removed
     * own tag (any case) brings that one back with its days and its first spelling.
     */
    public static String addOwn(Db db, String typed, long millis) {
        String name = LifeTags.cleanName(typed);
        if (name == null) return null;
        LifeTags.Known k = known(db);
        for (LifeTags.Tag t : k.own()) if (t.name.equalsIgnoreCase(name)) return null;
        // the name of a removed own tag brings that tag back (with its days), never a second key with the same name
        for (LifeTags.Tag t : k.all.values()) {
            if (t.removed && t.name.equalsIgnoreCase(name)) {
                db.getWritableDatabase().execSQL("UPDATE life_tags SET gone = 0, shown = 1, ord = NULL, made = ? WHERE key = ?", new Object[] {millis / 1000.0, t.key});
                return t.key;
            }
        }
        String key = LifeTags.ownKey(millis);
        // two tags in the same millisecond (only a test can do that): the next free number
        while (count(db, key) > 0) key = LifeTags.ownKey(++millis);
        db.getWritableDatabase().execSQL("INSERT INTO life_tags(key, name, shown, ord, made, gone) VALUES (?, ?, 1, NULL, ?, 0)",
                new Object[] {key, name, millis / 1000.0});
        return key;
    }

    private static long count(Db db, String key) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM life_tags WHERE key = ?", new String[] {key});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Removes the own tag {@code key}: a removal mark ({@code gone} 1), never a delete; its logged days stay. */
    public static void removeOwn(Db db, String key, double made) {
        if (!LifeTags.isOwn(key)) throw new IllegalArgumentException("not an own tag");
        db.getWritableDatabase().execSQL("UPDATE life_tags SET gone = 1, shown = 0, made = ? WHERE key = ?", new Object[] {made, key});
    }

    /**
     * The chips on Today's row: exactly {@code keys}, in that order, every other known tag off. One transaction. A fixed tag
     * keeps no name; an own tag keeps its name and removal mark.
     */
    public static void setChips(Db db, List<String> keys, double made) {
        LifeTags.Known k = known(db);
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (LifeTags.Tag t : k.all.values()) {
                int at = keys.indexOf(t.key);
                Object shown = at >= 0 && !t.removed ? 1L : 0L;
                Object ord = at >= 0 ? (Object) (long) at : null;
                if (LifeTags.isBuiltIn(t.key)) {
                    w.execSQL("INSERT OR REPLACE INTO life_tags(key, name, shown, ord, made, gone) VALUES (?, NULL, ?, ?, ?, 0)",
                            new Object[] {t.key, shown, ord, made});
                } else {
                    w.execSQL("UPDATE life_tags SET shown = ?, ord = ?, made = ? WHERE key = ?", new Object[] {shown, ord, made, t.key});
                }
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    // ------------------------------------------------------------------------------------- demo

    /**
     * The demo's made up tags ({@code core/LifeTags.demo}, never Intimacy), written into {@code db}, the demo file (its build
     * emptied it), never the real one, in one transaction; the several day entries share their span id as typed ones do.
     */
    static void demo(final Db db, LocalDate today, final double now, int days, int coldBack) {
        final TreeMap<LocalDate, List<String>> tags = LifeTags.demo(today, days, coldBack);
        final List<LocalDate[]> spans = LifeTags.demoSpans(today, coldBack);
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase w = db.getWritableDatabase();
                for (Map.Entry<LocalDate, List<String>> e : tags.entrySet()) {
                    for (String t : e.getValue()) {
                        Long span = null;
                        if (LifeTags.ILLNESS.equals(t) || LifeTags.HOLIDAY.equals(t)) {
                            for (int i = 0; i < spans.size(); i++) {
                                LocalDate[] s = spans.get(i);
                                if (!e.getKey().isBefore(s[0]) && !e.getKey().isAfter(s[1])) span = (long) (now * 1000) + i;
                            }
                        }
                        w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, span, made, gone) VALUES (?, ?, NULL, ?, ?, 0)",
                                new Object[] {e.getKey().toString(), t, span, now});
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------- the restore merge (Db.replaceWith)

    /** Every row of both tables of one file, as read before a restore's swap. */
    static final class Rows {
        final List<List<Object[]>> tables = new ArrayList<List<Object[]>>();
    }

    /** Reads every row of both tables of {@code d}. */
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
     * Writes {@code r} into {@code d} in one transaction, each row replacing the one with the same key (the same day and tag,
     * or the same tag), so the rows read from the phone win over the backup's, removal marks included. Twice gives the same.
     */
    static void merge(SQLiteDatabase d, Rows r) throws java.io.IOException {
        d.beginTransaction();
        try {
            for (int t = 0; t < TABLES.length; t++) {
                StringBuilder q = new StringBuilder();
                for (int i = 0; i < COLUMNS[t][1].length(); i++) q.append(i == 0 ? "?" : ", ?");
                String sql = "INSERT OR REPLACE INTO " + TABLES[t] + "(" + COLUMNS[t][0] + ") VALUES (" + q + ")";
                for (Object[] row : r.tables.get(t)) d.execSQL(sql, row);
                if (t == 0) Db.mergeStep(Db.MERGE_LIFE);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
