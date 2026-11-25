package app.openring.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.openring.core.LifeTags;
import app.openring.core.LifeTimes;

/**
 * The life factors the person logs (issue #292, phase 1): which tags a calendar day has, and the person's own tags and
 * chips. Stored like the period log ({@link Periods}): calendar dates as text, so travel never moves a day; only added to;
 * taking a tag off a day keeps the row as a removal mark ({@code gone} 1), never a delete, so a restore cannot bring it back;
 * kept when another ring is paired ({@code Db.KEPT_TABLES}), wiped by Delete all; carried from the phone into a restored
 * backup, the phone's row winning ({@link #read}, {@link #merge}). Nothing here starts an analysis or changes a score, and
 * nothing of it is ever listed by the Data check, written to the log or shown in a notification.
 * <ul>
 * <li>{@code life_days}: one row per day and tag; {@code time} "HH:MM" (the local clock time on that day) or null, and since
 * schema 15 {@code minutes} the length or null (issue #435, "When and how long?", {@link #setTime}); {@code span} the id
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
        {"day, tag, time, minutes, span, made, gone", "TTTIIRI"},
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

    /**
     * Schema 15 (issue #435): {@code life_days.minutes}, the length of a logged time (null: none). Only adds the column, and only
     * when it is missing, so it is safe to run twice and an upgrade stopped before it goes on; no row changes (every row keeps
     * its values, {@code minutes} null). A restored older backup gets it the same way.
     */
    static void add15(SQLiteDatabase db) {
        if (!hasColumn(db, "life_days", "minutes")) db.execSQL("ALTER TABLE life_days ADD COLUMN minutes INTEGER");
    }

    /** Whether {@code table} has {@code column} ({@code PRAGMA table_info}, which every SQLite the app runs on has). */
    static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) if (column.equals(c.getString(1))) return true;
            return false;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------------------------- one day's row

    /** One row of {@code life_days}, removal marks included. */
    public static final class Row {
        public final String day, tag;
        /** "HH:MM" or null, and the length in minutes or null (issue #435). */
        public final String time;
        public final Integer minutes;
        public final Long span;
        public final double made;
        public final boolean gone;

        Row(String day, String tag, String time, Integer minutes, Long span, double made, boolean gone) {
            this.day = day;
            this.tag = tag;
            this.time = time;
            this.minutes = minutes;
            this.span = span;
            this.made = made;
            this.gone = gone;
        }

        @Override
        public String toString() {
            return day + " " + tag + " " + span + " " + made + (gone ? " gone" : "") + (time == null && minutes == null ? "" : " " + time + " " + minutes);
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
     * row of that tag. Taking it off keeps the row as a removal mark, never a delete; it also marks the day removed for every
     * alias of {@code tag} that holds it (an own tag with the same name, {@code LifeTags.Known}, issue #422), so the day really
     * goes, all in one transaction. Putting it on writes {@code tag} only.
     */
    public static void set(Db db, String day, String tag, boolean on, double made) {
        String d = date(day);
        tag(tag);
        if (on) {
            write(db.getWritableDatabase(), d, tag, made, false);
            return;
        }
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            // the aliases holding the day are read inside the transaction, so they are the rows this write marks
            List<String> also = holding(db, d, known(db).aliases(tag));
            int n = 0;
            write(w, d, tag, made, true);
            stop(++n);
            for (String a : also) {
                write(w, d, a, made, true);
                stop(++n);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** One day's row of one tag, on or as a removal mark, without a time: one statement. */
    private static void write(SQLiteDatabase w, String day, String tag, double made, boolean gone) {
        w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, NULL, NULL, NULL, ?, ?)",
                new Object[] {day, tag, made, gone ? 1L : 0L});
    }

    /** The keys of {@code keys} that have {@code day} in force. */
    private static List<String> holding(Db db, String day, List<String> keys) {
        List<String> out = new ArrayList<String>();
        for (String k : keys) {
            Row r = row(db, day, k);
            if (r != null && !r.gone) out.add(k);
        }
        return out;
    }

    /**
     * The rows {@link #set} is about to write for {@code tag} on {@code day}, as they are now, for Undo: the tag's own (null when
     * there is none), and taking it off, those of its aliases that hold the day.
     */
    public static List<Row> before(Db db, String day, String tag, boolean on) {
        String d = date(day);
        List<Row> out = new ArrayList<Row>();
        // one transaction, so the tag's row and its aliases' rows are read as one state of the file
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            out.add(row(db, d, tag));
            if (!on) for (String a : holding(db, d, known(db).aliases(tag))) out.add(row(db, d, a));
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        return out;
    }

    /** Puts {@code r} back as it was (Undo); {@code before} null (the day never had the tag) leaves a removal mark of that tag only. */
    public static void restore(Db db, String day, String tag, Row before, double made) {
        if (before == null) {
            write(db.getWritableDatabase(), date(day), tag(tag), made, true);
            return;
        }
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[] {date(before.day), tag(before.tag), before.time, before.minutes == null ? null : (Object) (long) before.minutes, before.span,
                    before.made, before.gone ? 1L : 0L});
    }

    /**
     * "When and how long?" (issue #435): {@code tag} on {@code day} in force with the start {@code time} ("HH:MM", the local
     * clock time of that day) and {@code minutes} (null: none), or both null for "Without a time". The day is logged if it was
     * not; a day of a several day entry keeps its span id. One transaction (the span is read inside it), so a stop leaves the
     * row as it was. Undo puts back the row {@link #row} read before ({@link #restore}).
     */
    public static void setTime(Db db, String day, String tag, String time, Integer minutes, double made) {
        String d = date(day);
        tag(tag);
        if (time != null && LifeTimes.parse(time) < 0) throw new IllegalArgumentException("not a time");
        if (time == null && minutes != null || !LifeTimes.validLength(minutes)) throw new IllegalArgumentException("not a length");
        // a day tag (Stress, Holiday, Illness, Travel) has no time; "Without a time" is fine for any tag
        if (time != null && !LifeTimes.timed(tag)) throw new IllegalArgumentException("not a tag of a moment");
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            Row was = row(db, d, tag);
            Long span = was != null && !was.gone ? was.span : null;
            w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    new Object[] {d, tag, time, minutes == null ? null : (Object) (long) minutes, span, made});
            stop(1);
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /**
     * Every day in force with a time (issue #435), oldest first: the day, the key as stored (an alias is read as its factor by
     * the caller, {@code LifeTimes.find} and {@code LifeTimes.bands}), the time and the length. A row whose day or time is not
     * one (only a damaged file could hold it) is left out; a length that cannot be is read as none.
     */
    public static List<LifeTimes.Entry> times(Db db) {
        List<LifeTimes.Entry> out = new ArrayList<LifeTimes.Entry>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, tag, time, minutes FROM life_days WHERE gone = 0 AND time IS NOT NULL ORDER BY day, tag", null);
        try {
            while (c.moveToNext()) {
                LocalDate day;
                try {
                    day = LocalDate.parse(c.getString(0));
                } catch (RuntimeException e) {
                    continue;
                }
                String time = c.getString(2);
                if (LifeTimes.parse(time) < 0) continue;
                Integer m = c.isNull(3) ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, c.getLong(3)));
                out.add(new LifeTimes.Entry(day, c.getString(1), time, LifeTimes.validLength(m) ? m : null));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Tests only: a several day entry throws after this many rows, as a stop half way would. */
    static volatile int failSpanAfter = -1;

    /**
     * Tests only: the other writes over several rows (a tap off reaching aliases, {@link #removeOwn}, {@link #addOwn} bringing
     * keys back) throw after this many rows, as a stop half way would.
     */
    static volatile int failRowsAfter = -1;

    private static void stop(int rows) {
        if (rows == failRowsAfter) throw new IllegalStateException("test stop");
    }

    /**
     * {@code tag} on every day from {@code from} to {@code to} (both included), one row per day sharing the span id
     * {@code span}, in one transaction: a stop half way writes none of them. A day that has the tag already keeps its time and
     * length (issue #435); a removal mark is taken back without one. Returns the days written; none when {@code to} is
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
                // a day that already has the tag keeps its time and length (a Sauna at 19:00 stays when Sauna becomes several
                // days); UPDATE then INSERT OR IGNORE, as SQLite's upsert needs Android 11 and the app runs from Android 8
                w.execSQL("UPDATE life_days SET span = ?, made = ?, time = CASE WHEN gone = 0 THEN time END, minutes = CASE WHEN gone = 0 THEN minutes END, gone = 0"
                        + " WHERE day = ? AND tag = ?", new Object[] {span, made, d.toString(), tag});
                w.execSQL("INSERT OR IGNORE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, NULL, NULL, ?, ?, 0)",
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
        Cursor c = db.getReadableDatabase().rawQuery("SELECT day, tag, span, made, gone, time, minutes FROM life_days WHERE day = ? AND tag = ?",
                new String[] {date(day), tag});
        try {
            return c.moveToFirst() ? new Row(c.getString(0), c.getString(1), c.isNull(5) ? null : c.getString(5), c.isNull(6) ? null : (int) c.getLong(6),
                    c.isNull(2) ? null : c.getLong(2), c.getDouble(3), c.getInt(4) != 0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * The tags in force on each day from {@code from} to {@code to} (both included), by day, each day's tags in key order. An
     * own tag with the same name as an older one is read as that one, a day once ({@code LifeTags.Known.fold}, issue #422).
     */
    public static TreeMap<LocalDate, List<String>> days(Db db, String from, String to) {
        return known(db).fold(days(db.getReadableDatabase(), "WHERE gone = 0 AND day >= ? AND day <= ? ", new String[] {date(from), date(to)}));
    }

    /** Every day with a tag in force, oldest first, read as {@link #days} reads them. */
    public static TreeMap<LocalDate, List<String>> all(Db db) {
        return known(db).fold(days(db.getReadableDatabase(), "WHERE gone = 0 ", null));
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
     * own tag (any case) brings that one back with its days and its first spelling, together with the keys read as it (issue
     * #422), in one transaction.
     */
    public static String addOwn(Db db, String typed, long millis) {
        LifeTags.Known k = known(db);
        // the name of a removed own tag brings it back even when a fixed tag has that name since (an own "Sauna" removed before
        // issue #435 made Sauna a fixed tag, review of #444): checked before the fixed names refuse it
        String typedSame = LifeTags.sameName(typed);
        boolean removedOwn = false;
        for (LifeTags.Tag t : k.all.values()) removedOwn |= t.removed && LifeTags.isOwn(t.key) && LifeTags.sameName(t.name).equals(typedSame);
        String name = removedOwn ? typed : LifeTags.cleanName(typed);
        if (name == null) return null;
        String same = removedOwn ? typedSame : LifeTags.sameName(name);
        for (LifeTags.Tag t : k.own()) if (LifeTags.sameName(t.name).equals(same)) return null;
        // the name of a removed own tag brings that tag back (with its days), never a second key with the same name
        for (LifeTags.Tag t : k.all.values()) {
            if (t.removed && LifeTags.sameName(t.name).equals(same)) {
                List<String> keys = new ArrayList<String>();
                keys.add(t.key);
                keys.addAll(k.aliases(t.key));
                SQLiteDatabase w = db.getWritableDatabase();
                w.beginTransaction();
                try {
                    int n = 0;
                    for (String key : keys) {
                        w.execSQL("UPDATE life_tags SET gone = 0, shown = 1, ord = NULL, made = ? WHERE key = ?", new Object[] {millis / 1000.0, key});
                        stop(++n);
                    }
                    w.setTransactionSuccessful();
                } finally {
                    w.endTransaction();
                }
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

    /**
     * Removes the own tag {@code key} and the keys read as it (issue #422): a removal mark ({@code gone} 1) each, never a
     * delete, in one transaction; their logged days stay.
     */
    public static void removeOwn(Db db, String key, double made) {
        if (!LifeTags.isOwn(key)) throw new IllegalArgumentException("not an own tag");
        LifeTags.Known k = known(db);
        List<String> keys = new ArrayList<String>();
        keys.add(k.key(key));
        keys.addAll(k.aliases(k.key(key)));
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            int n = 0;
            for (String x : keys) {
                w.execSQL("UPDATE life_tags SET gone = 1, shown = 0, made = ? WHERE key = ?", new Object[] {made, x});
                stop(++n);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /**
     * The chips on Today's row: exactly {@code keys}, in that order, every other known tag off. One transaction. A fixed tag
     * keeps no name; an own tag keeps its name and removal mark; the keys read as an own tag (issue #422) get its choice too.
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
                    for (String a : k.aliases(t.key)) {
                        w.execSQL("UPDATE life_tags SET shown = ?, ord = ?, made = ? WHERE key = ?", new Object[] {shown, ord, made, a});
                    }
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
     * emptied it), never the real one, in one transaction; the several day entries share their span id as typed ones do; a few
     * have a time and a length ({@code core/LifeTimes.demo}, issue #435).
     */
    static void demo(final Db db, LocalDate today, final double now, int days, int coldBack) {
        final TreeMap<LocalDate, List<String>> tags = LifeTags.demo(today, days, coldBack);
        final List<LocalDate[]> spans = LifeTags.demoSpans(today, coldBack);
        final List<LifeTimes.Entry> times = LifeTimes.demo(today);
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
                        LifeTimes.Entry at = LifeTimes.find(times, e.getKey(), t, null);
                        w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, ?, ?, ?, ?, 0)",
                                new Object[] {e.getKey().toString(), t, at == null ? null : at.time,
                                    at == null || at.minutes == null ? null : (Object) (long) (int) at.minutes, span, now});
                    }
                }
            }
        });
    }

    /**
     * The made up tags over the demo's made up year (issue #292, phase 2, {@code core/LifeTags.demoYear}), after
     * {@code Demo.olderYear} stored its nights: Alcohol placed by each night's HRV against its usual (worked out by
     * {@code core/analysis/FactorEffect.points} from the stored day summaries), the week of illness as one several day entry.
     * Only into {@code db}, the demo file, in one transaction; nothing without the made up year.
     */
    static void demoYear(final Db db, LocalDate today, java.time.ZoneId zone, final double now) {
        String until = db.kv(Demo.YEAR_UNTIL, null);
        if (until == null) return;
        LocalDate last = LocalDate.parse(until), first = last.minusDays(Demo.YEAR_DAYS - 1);
        Map<LocalDate, Double> hrv = new java.util.HashMap<LocalDate, Double>();
        for (app.openring.core.analysis.FactorEffect.Point p : app.openring.core.analysis.FactorEffect.points(
                LifeEffects.nights(db, today, zone, app.openring.core.analysis.LeftOut.NONE), today)) {
            double x = p.diff[app.openring.core.analysis.FactorEffect.HRV];
            if (!Double.isNaN(x)) hrv.put(p.tagDay, x);
        }
        java.util.Set<LocalDate> ill = new java.util.HashSet<LocalDate>();
        LocalDate illFrom = Demo.illFrom(today);
        for (int i = 0; i < Demo.ILL_DAYS; i++) ill.add(illFrom.plusDays(i));
        final TreeMap<LocalDate, List<String>> tags = LifeTags.demoYear(first, last, hrv, ill);
        final long span = (long) (now * 1000) + 10;
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase w = db.getWritableDatabase();
                for (Map.Entry<LocalDate, List<String>> e : tags.entrySet()) {
                    for (String t : e.getValue()) {
                        w.execSQL("INSERT OR REPLACE INTO life_days(day, tag, time, minutes, span, made, gone) VALUES (?, ?, NULL, NULL, ?, ?, 0)",
                                new Object[] {e.getKey().toString(), t, LifeTags.ILLNESS.equals(t) ? (Object) span : null, now});
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
