package app.openring.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exports: one CSV row per day, or a copy of the whole SQLite database. */
public final class Exporter {
    private Exporter() {}

    private static final String[] COLS = {"date", "sleep_score", "asleep_min", "rhr", "hrv", "temp", "temp_dev", "breath", "spo2", "steps",
        "active_kcal", "total_kcal", "distance_m", "low_min", "medium_min", "high_min", "inactive_min", "target_kcal", "vo2max"};

    public static void dailyCsv(Context c, OutputStream os) throws IOException {
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        for (String col : COLS) head.append(col).append(',');
        head.append("readiness_score,activity_score\n");
        w.write(head.toString());
        List<String[]> days = Db.get(c).daysJson("0000-00-00", "9999-99-99");
        for (String[] d : days) {
            JSONObject o = Json.parse(d[1]);
            if (o == null) continue;
            StringBuilder row = new StringBuilder();
            for (String col : COLS) {
                Object v = o.opt(col);
                row.append(v == null ? "" : String.valueOf(v)).append(',');
            }
            JSONObject r = o.optJSONObject("readiness"), a = o.optJSONObject("activity_score");
            row.append(r == null ? "" : String.valueOf(r.optInt("value"))).append(',');
            row.append(a == null ? "" : String.valueOf(a.optInt("value"))).append('\n');
            w.write(row.toString());
        }
        w.flush();
    }

    /** The copy of the history did not pass its check (issue #115); nothing was written to the file. */
    public static final class NotSaved extends IOException {
        NotSaved(String reason) {
            super(reason);
        }
    }

    /**
     * A copy of the whole database in use (real or demo), checked first (issue #115): the file the
     * person picked gets nothing unless the copy is complete. See {@link #snapshot}.
     */
    public static void database(Context c, OutputStream os) throws IOException {
        File snap = snapshot(c);
        try {
            stream(snap, os);
        } finally {
            release(snap);
        }
        os.flush();
    }

    /** SQLite 3.27 brought {@code VACUUM INTO}; Android 8 to 10 ship 3.18 and 3.22. */
    static boolean canVacuumInto(SQLiteDatabase d) {
        Cursor cur = d.rawQuery("SELECT sqlite_version()", null);
        try {
            if (!cur.moveToFirst()) return false;
            String[] v = cur.getString(0).split("\\.");
            int major = Integer.parseInt(v[0]), minor = v.length > 1 ? Integer.parseInt(v[1]) : 0;
            return major > 3 || major == 3 && minor >= 27;
        } catch (RuntimeException e) {
            return false;
        } finally {
            cur.close();
        }
    }

    /**
     * A consistent, checked copy of the database in use, as a temp file in the cache that the caller
     * releases with {@link #release}. The live file is only read: it is never checkpointed or copied
     * while it can change, so a backup can no longer miss rows or mix old and new pages.
     * <ul>
     * <li>A running analysis is waited for first (as restore does), so no backup shows one half way.</li>
     * <li>SQLite 3.27 or later: {@code VACUUM INTO} the temp file, one statement that reads every
     * table at one moment while sync and analysis carry on.</li>
     * <li>Older SQLite (Android 8 to 10): the database's write lock is held (a transaction with a
     * write that changes nothing, rolled back) while the file and its write ahead log are copied.
     * No commit, and so no checkpoint, can run meanwhile; a sync waits for those seconds. Opening
     * the copy applies its log the way SQLite recovers after a crash.</li>
     * </ul>
     * Then the copy becomes a single file ({@code journal_mode=DELETE}) and must pass
     * {@link Db#inspect} (quick_check, version, every table and column) and hold at least the events
     * the history had just before. Otherwise {@link NotSaved}.
     */
    public static File snapshot(Context c) throws IOException {
        return snapshot(c, null);
    }

    /** As above; {@code vacuum} forces one way (tests), null picks it by the SQLite version. */
    static File snapshot(Context c, Boolean vacuum) throws IOException {
        cleanStale(c);
        File tmp = newTemp(c);
        boolean ok = false;
        try {
            long before;
            synchronized (Analyzer.class) {
                Db db = Db.get(c);
                SQLiteDatabase d = db.getWritableDatabase();
                before = db.eventCount();
                if (vacuum != null ? vacuum : canVacuumInto(d)) {
                    // VACUUM INTO refuses a file that is already there
                    if (!tmp.delete() && tmp.exists()) throw new IOException("could not prepare " + tmp.getName());
                    // only from SQLite 3.27 (canVacuumInto), older phones take the copy path below
                    d.execSQL("VACUUM INTO ?", new Object[] {tmp.getPath()});
                } else {
                    copyLocked(d, c.getDatabasePath(db.fileName), tmp);
                }
            }
            singleFile(tmp);
            Db.Info i = Db.inspect(tmp, true);
            if (!i.ok) throw new NotSaved("the copy did not pass its check: " + i.problem + (i.schema != null ? " (" + i.schema + ")" : ""));
            if (i.events < before) throw new NotSaved("the copy holds " + i.events + " events, the history had " + before);
            ok = true;
            return tmp;
        } catch (RuntimeException e) {
            // SQLite could not make or open the copy (no space left, for example): the sentence for a
            // file that could not be saved fits better than "did not pass its check"
            throw new IOException("the copy could not be made", e);
        } finally {
            if (!ok) release(tmp);
        }
    }

    /** Copy the database file and its write ahead log while holding the write lock (old SQLite). */
    private static void copyLocked(SQLiteDatabase d, File live, File tmp) throws IOException {
        d.beginTransaction();
        try {
            // takes the write lock now, also where a transaction starts without it
            d.execSQL("DELETE FROM kv WHERE 0");
            copy(live, tmp);
            File wal = new File(live.getPath() + "-wal");
            if (wal.exists()) copy(wal, new File(tmp.getPath() + "-wal"));
        } finally {
            d.endTransaction(); // never marked successful: nothing was written, nothing to keep
        }
    }

    /** Fold a copied log into the copy and switch it to one file, so it can be streamed and checked. */
    static void singleFile(File tmp) throws IOException {
        SQLiteDatabase d = SQLiteDatabase.openDatabase(tmp.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP_FILE);
        try {
            Cursor cur = d.rawQuery("PRAGMA journal_mode=DELETE", null);
            String mode;
            try {
                mode = cur.moveToFirst() ? cur.getString(0) : null;
            } finally {
                cur.close();
            }
            if (!"delete".equalsIgnoreCase(mode)) throw new NotSaved("the copy stayed in journal mode " + mode);
        } finally {
            d.close();
        }
        if (new File(tmp.getPath() + "-wal").exists()) throw new NotSaved("the copy kept its write ahead log");
    }

    /** Does nothing, so Android's default handler cannot delete the copy while it is looked at. */
    private static final android.database.DatabaseErrorHandler KEEP_FILE = new android.database.DatabaseErrorHandler() {
        @Override
        public void onCorruption(SQLiteDatabase db) {}
    };

    static void stream(File f, OutputStream os) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private static void copy(File src, File dst) throws IOException {
        OutputStream out = new FileOutputStream(dst);
        try {
            stream(src, out);
        } finally {
            out.close();
        }
    }

    /** Temp copies of exports running in this process; any other export*.db in the cache is left over. */
    private static final java.util.Set<String> LIVE = new java.util.HashSet<String>();

    private static File newTemp(Context c) throws IOException {
        File f = File.createTempFile("export-", ".db", c.getCacheDir());
        synchronized (LIVE) {
            LIVE.add(f.getName());
        }
        return f;
    }

    /** Delete a copy made by {@link #snapshot} and its side files. */
    public static void release(File f) {
        if (f == null) return;
        for (String s : new String[] {"", "-wal", "-shm", "-journal"}) new File(f.getPath() + s).delete();
        synchronized (LIVE) {
            LIVE.remove(f.getName());
        }
    }

    /** Delete copies that no export in this process owns (left by an app that was stopped). */
    static void cleanStale(Context c) {
        File[] fs = c.getCacheDir().listFiles();
        if (fs == null) return;
        synchronized (LIVE) {
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("export-")) continue;
                int end = n.indexOf(".db");
                if (end < 0) continue;
                if (!LIVE.contains(n.substring(0, end + 3))) f.delete();
            }
        }
    }
}
