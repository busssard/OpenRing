package app.openring.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.openring.core.SleepSounds;
import app.openring.core.Texts;
import app.openring.core.analysis.SoundDetector;

/**
 * Sleep sounds (issue #380, phase 1a, schema 13): the marks of the sounds the phone heard at night and the clip files that go
 * with them. Phase 1a has no microphone, service or screen; it holds the tables and the rules the later phases rely on.
 *
 * <ul>
 * <li>{@code sound_nights}: one row per night listened ({@code start} unix seconds as the key, {@code stop}, the noise
 * {@code floor} in dB of full scale, the {@code clips} saved, {@code ended} why it stopped: {@link #ENDED_HAND},
 * {@link #ENDED_TIME}, {@link #ENDED_LIMIT} or {@link #ENDED_SYSTEM}).</li>
 * <li>{@code sound_clips}: one row per mark ({@code start} as the key, {@code stop}, {@code kind} as the detector names it,
 * {@code peak} dB of full scale, {@code file} the clip's plain file name, {@code kept} 1 when the person keeps it,
 * {@code gone} 1 once its sound is deleted: the mark stays).</li>
 * <li>{@code sound_gaps}: times the phone was not listening ({@code start}, {@code stop}, {@code why}:
 * {@link #GAP_SILENCE} when the microphone gave digital silence, {@link #GAP_SYSTEM} when Android stopped it).</li>
 * </ul>
 *
 * Rules (design round of #380, data safety):
 * <ul>
 * <li>The audio is never in the database, a backup or an export: one file per clip ({@code .m4a}, or {@code .wav} when the
 * phone's encoder fails) in the app's {@code no_backup/sounds} folder, the demo's in {@code no_backup/sounds-demo}. Which
 * folder belongs to which history follows the file ({@link #folder(Context, Db)}): the real file and the real folder, the
 * demo file and the demo folder, never crossed.</li>
 * <li>Rows are only added; a sound that is deleted (retention, a missing file, the sweep) keeps its mark with {@code gone} 1.
 * Only Delete all removes rows.</li>
 * <li>Kept when another ring is paired ({@code Db.KEPT_TABLES}); a restore keeps the phone's rows ({@link #read},
 * {@link #merge}: the phone wins), and a row whose file is not on this phone keeps its mark with {@code gone} 1.</li>
 * <li>The Data check holds counts only ({@link #checkLine}), never a time, kind, file name or sound.</li>
 * </ul>
 */
public final class Sounds {
    private Sounds() {}

    /** The three tables: kept when another ring is paired, carried through a restore, emptied by Delete all. */
    static final String[] TABLES = {"sound_nights", "sound_clips", "sound_gaps"};
    /** The clip folders inside the app's {@code no_backup} folder (never in Android's cloud backup, never in ours). */
    static final String REAL_FOLDER = "sounds", DEMO_FOLDER = "sounds-demo";
    /** How long clips keep their sound (days; {@link #KEEP_ALWAYS}: until deleted). 7 by default (decided on #380). */
    public static final int KEEP_WEEK = SleepSounds.KEEP_WEEK, KEEP_MONTH = SleepSounds.KEEP_MONTH, KEEP_ALWAYS = SleepSounds.KEEP_ALWAYS;
    /** Why a night stopped ({@code sound_nights.ended}); the same values as {@code core/SleepSounds}, which words them. */
    public static final String ENDED_HAND = SleepSounds.ENDED_HAND, ENDED_TIME = SleepSounds.ENDED_TIME, ENDED_LIMIT = SleepSounds.ENDED_LIMIT,
            ENDED_SYSTEM = SleepSounds.ENDED_SYSTEM;
    /** Why the phone was not listening ({@code sound_gaps.why}). */
    public static final String GAP_SILENCE = "silence", GAP_SYSTEM = "system";
    /** The sweep leaves files younger than this alone: a clip being written has no row yet. */
    static final long SWEEP_GRACE_MS = 10 * 60 * 1000L;

    /** Each table's columns and their types (T text, I integer, R real), in the order {@link Rows} keeps them. */
    private static final String[][] COLUMNS = {
        {"start, stop, floor, clips, ended", "RRRIT"},
        {"start, stop, kind, peak, file, kept, gone", "RRTRTII"},
        {"start, stop, why", "RRT"},
    };

    /**
     * Schema 13: only adds, each table on its own ({@code IF NOT EXISTS}), safe to run twice, and an upgrade stopped after the
     * first goes on with the others. Older files (and restored older backups) get them empty. No other table or row changes.
     */
    static void create13(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sound_nights (start REAL PRIMARY KEY, stop REAL, floor REAL, clips INTEGER NOT NULL DEFAULT 0, ended TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sound_clips (start REAL PRIMARY KEY, stop REAL NOT NULL, kind TEXT NOT NULL, peak REAL, file TEXT NOT NULL,"
                + " kept INTEGER NOT NULL DEFAULT 0, gone INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sound_gaps (start REAL PRIMARY KEY, stop REAL NOT NULL, why TEXT)");
    }

    // ------------------------------------------------------------------------------------- folders and files

    /** The clip folder of the real history ({@code demo} false) or of the demo; not made here. */
    public static File folder(Context c, boolean demo) {
        return new File(c.getNoBackupFilesDir(), demo ? DEMO_FOLDER : REAL_FOLDER);
    }

    /** The clip folder that belongs to {@code db}'s file: the demo file's folder for the demo, else the real one. */
    public static File folder(Context c, Db db) {
        return folder(c, Db.DEMO.equals(db.fileName));
    }

    /** A clip file name as rows hold it: a plain name (no folder), letters, digits, dot, dash or underscore, ending .m4a or .wav. */
    static boolean clipName(String name) {
        if (name == null || name.length() > 80 || name.startsWith(".") || !(name.endsWith(".m4a") || name.endsWith(".wav"))) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '.' || ch == '-' || ch == '_')) return false;
        }
        return true;
    }

    /** The plain files in {@code folder} (none when it does not exist); a folder that cannot be listed is an error. */
    static File[] files(File folder) throws java.io.IOException {
        if (!folder.exists()) return new File[0];
        File[] fs = folder.listFiles();
        if (fs == null) throw new java.io.IOException("the clip folder could not be listed");
        List<File> out = new ArrayList<File>();
        for (File f : fs) if (f.isFile()) out.add(f);
        return out.toArray(new File[0]);
    }

    private static Set<String> names(File folder) throws java.io.IOException {
        Set<String> out = new HashSet<String>();
        for (File f : files(folder)) out.add(f.getName());
        return out;
    }

    /** The space the clips take: their count and real size in bytes ({@code .m4a} and {@code .wav} files of the folder). */
    public static final class Space {
        public final int files;
        public final long bytes;

        Space(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** The space the clips in {@code folder} take now. */
    public static Space space(File folder) throws java.io.IOException {
        int n = 0;
        long bytes = 0;
        for (File f : files(folder)) {
            if (!clipName(f.getName())) continue;
            n++;
            bytes += f.length();
        }
        return new Space(n, bytes);
    }

    /** Removes {@code f} with everything in it (Delete all); what cannot be removed stays and the sweep removes it later. */
    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        f.delete();
    }

    // ------------------------------------------------------------------------------------- writing (phase 1b writes through these)

    /** Stores the night that started at {@code start}, replacing its row. */
    public static void addNight(Db db, double start, Double stop, Double floor, int clips, String ended) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO sound_nights(start, stop, floor, clips, ended) VALUES (?, ?, ?, ?, ?)",
                new Object[] {start, stop, floor, (long) clips, ended});
    }

    /**
     * Stores a mark with its clip file (a plain name in the history's folder). A row with the same start gets the new stop,
     * kind, peak and file and has its sound again; its Keep stays as the person set it (review of #420).
     */
    public static void addClip(Db db, double start, double stop, String kind, Double peak, String file) {
        if (!clipName(file)) throw new IllegalArgumentException("not a clip file name");
        store(db, start, stop, kind, peak, file, 0);
    }

    /**
     * Stores a mark without sound ({@code gone} 1, no file): a sound beyond the night's 60 clips, or a snoring stretch whose
     * clip was given up for louder ones (review of #420). The sweep, the retention and a restore leave it as it is; a clip
     * stored later for the same start ({@link #addClip}) gives it its sound. Keep stays as it was.
     */
    public static void addMark(Db db, double start, double stop, String kind, Double peak) {
        store(db, start, stop, kind, peak, "", 1);
    }

    private static void store(final Db db, final double start, final double stop, final String kind, final Double peak, final String file, final long gone) {
        if (!SoundDetector.SNORE.equals(kind) && !SoundDetector.COUGH.equals(kind) && !SoundDetector.TALK.equals(kind)) throw new IllegalArgumentException("not a kind");
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase w = db.getWritableDatabase();
                w.execSQL("INSERT OR IGNORE INTO sound_clips(start, stop, kind, peak, file, kept, gone) VALUES (?, ?, ?, ?, ?, 0, ?)",
                        new Object[] {start, stop, kind, peak, file, gone});
                w.execSQL("UPDATE sound_clips SET stop = ?, kind = ?, peak = ?, file = ?, gone = ? WHERE start = ?", new Object[] {stop, kind, peak, file, gone, start});
            }
        });
    }

    /** Stores a time the phone was not listening. */
    public static void addGap(Db db, double start, double stop, String why) {
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO sound_gaps(start, stop, why) VALUES (?, ?, ?)", new Object[] {start, stop, why});
    }

    /** Keep (or stop keeping) the clip of the mark at {@code start}: kept clips are never deleted by the retention. */
    public static void keep(Db db, double start, boolean keep) {
        db.getWritableDatabase().execSQL("UPDATE sound_clips SET kept = ? WHERE start = ?", new Object[] {keep ? 1L : 0L, start});
    }

    // ------------------------------------------------------------------------------------- the Data check

    /** "Sleep sounds: N nights, M clips (K with sound)": counts only, never a time, kind or file name. */
    static String checkLine(Db db) {
        long nights = db.count("SELECT COUNT(*) FROM sound_nights");
        long clips = db.count("SELECT COUNT(*) FROM sound_clips");
        long sound = db.count("SELECT COUNT(*) FROM sound_clips WHERE gone = 0");
        return "Sleep sounds: " + Texts.count(nights, "night", "nights") + ", " + Texts.count(clips, "clip", "clips") + " (" + sound + " with sound)\n";
    }

    // ------------------------------------------------------------------------------------- the sweep and the retention

    /** What a sweep did: files deleted (no row plays them), marks that lost their sound ({@code gone} set). */
    public static final class Swept {
        public final int files, rows;

        Swept(int files, int rows) {
            this.files = files;
            this.rows = rows;
        }
    }

    /** The sweep of {@code db}'s own folder (the real one for the real file, the demo's for the demo file). */
    public static Swept sweep(Context c, Db db) throws java.io.IOException {
        return sweep(db, folder(c, db), System.currentTimeMillis());
    }

    /**
     * Both ways, on {@code folder} only: a mark whose file is not there (or whose name is not a clip's) gets {@code gone} 1 and
     * keeps its mark; a file no mark with sound names is deleted, unless it changed within {@link #SWEEP_GRACE_MS} of
     * {@code nowMs} (a clip being written). Rows in one transaction, never deleted. A folder that cannot be listed changes
     * nothing (IOException).
     */
    static Swept sweep(Db db, File folder, long nowMs) throws java.io.IOException {
        File[] fs = files(folder);
        Set<String> present = new HashSet<String>();
        for (File f : fs) present.add(f.getName());
        final List<Double> lost = new ArrayList<Double>();
        Set<String> live = new HashSet<String>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT start, file FROM sound_clips WHERE gone = 0", null);
        try {
            while (c.moveToNext()) {
                String file = c.getString(1);
                if (clipName(file) && present.contains(file)) live.add(file);
                else lost.add(c.getDouble(0));
            }
        } finally {
            c.close();
        }
        markGone(db, lost);
        int deleted = 0;
        for (File f : fs) {
            if (live.contains(f.getName()) || f.lastModified() > nowMs - SWEEP_GRACE_MS) continue;
            if (f.delete()) deleted++;
        }
        return new Swept(deleted, lost.size());
    }

    private static void markGone(final Db db, final List<Double> starts) {
        if (starts.isEmpty()) return;
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                for (Double s : starts) db.getWritableDatabase().execSQL("UPDATE sound_clips SET gone = 1 WHERE start = ?", new Object[] {s});
            }
        });
    }

    /** The retention for {@code db}'s own folder, now. */
    public static int retain(Context c, Db db, int days) throws java.io.IOException {
        return retain(db, folder(c, db), System.currentTimeMillis() / 1000.0, days);
    }

    /**
     * Deletes the sound of every clip that started more than {@code days} days before {@code now} (unix seconds) and is not
     * kept; its mark stays with {@code gone} 1. {@code days} {@link #KEEP_ALWAYS} (or less) deletes nothing. A file that cannot
     * be deleted keeps its mark with sound (tried again next time). Returns the marks that lost their sound.
     */
    static int retain(Db db, File folder, double now, int days) {
        if (days <= KEEP_ALWAYS) return 0;
        double before = now - days * 86400.0;
        List<Double> done = new ArrayList<Double>();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT start, file FROM sound_clips WHERE gone = 0 AND kept = 0 AND start < ?",
                new String[] {String.valueOf(before)});
        try {
            while (c.moveToNext()) {
                String file = c.getString(1);
                File f = clipName(file) ? new File(folder, file) : null;
                if (f == null || !f.exists() || f.delete()) done.add(c.getDouble(0));
            }
        } finally {
            c.close();
        }
        markGone(db, done);
        return done.size();
    }

    // ------------------------------------------------------------------------------------- the restore merge (Db.replaceWith)

    /** Every row of the three tables of one file, as read before a restore's swap. */
    static final class Rows {
        final List<List<Object[]>> tables = new ArrayList<List<Object[]>>();
    }

    /** Reads every row of the three tables of {@code d}. */
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
     * Writes the phone's rows {@code phone} (null: none, the phone had no readable file) into the restored file {@code d}, each
     * replacing the backup's row with the same start, so the phone wins. Then, in the same transaction, every mark with sound
     * whose file is not in {@code folder} (the real folder: a restore always writes the real file), or whose file belongs to
     * another of the phone's marks, gets {@code gone} 1: the mark stays, it never points at a sound this phone does not have.
     * Twice gives the same. A folder that cannot be listed fails before anything is written.
     */
    static void merge(SQLiteDatabase d, Rows phone, File folder) throws java.io.IOException {
        Set<String> present = names(folder);
        Map<String, Double> phoneFiles = new HashMap<String, Double>();
        // only the phone's marks with sound own their file: a mark without sound that names it too owns nothing (review of #420)
        if (phone != null) for (Object[] row : phone.tables.get(1)) if (row[6] != null && (Long) row[6] == 0) phoneFiles.put((String) row[4], (Double) row[0]);
        d.beginTransaction();
        try {
            for (int t = 0; t < TABLES.length; t++) {
                if (phone != null) {
                    StringBuilder q = new StringBuilder();
                    for (int i = 0; i < COLUMNS[t][1].length(); i++) q.append(i == 0 ? "?" : ", ?");
                    String sql = "INSERT OR REPLACE INTO " + TABLES[t] + "(" + COLUMNS[t][0] + ") VALUES (" + q + ")";
                    for (Object[] row : phone.tables.get(t)) d.execSQL(sql, row);
                }
                if (t == 0) Db.mergeStep(Db.MERGE_SOUNDS);
            }
            List<Double> lost = new ArrayList<Double>();
            Cursor c = d.rawQuery("SELECT start, file FROM sound_clips WHERE gone = 0", null);
            try {
                while (c.moveToNext()) {
                    double start = c.getDouble(0);
                    String file = c.getString(1);
                    Double owner = phoneFiles.get(file);
                    if (!clipName(file) || !present.contains(file) || (owner != null && owner != start)) lost.add(start);
                }
            } finally {
                c.close();
            }
            for (Double s : lost) d.execSQL("UPDATE sound_clips SET gone = 1 WHERE start = ?", new Object[] {s});
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    // ------------------------------------------------------------------------------------- phase 1b: the screens and the night

    /** A mark as the screens read it. */
    public static final class Clip {
        public final double start, stop;
        public final String kind, file;
        public final boolean kept, gone;

        Clip(double start, double stop, String kind, String file, boolean kept, boolean gone) {
            this.start = start;
            this.stop = stop;
            this.kind = kind;
            this.file = file;
            this.kept = kept;
            this.gone = gone;
        }

        /** Whether it has a sound to play (not gone, a clip file name). */
        public boolean sound() {
            return !gone && clipName(file);
        }

        /** The mark as {@code core/SleepSounds} words it. */
        public SleepSounds.Mark mark() {
            return new SleepSounds.Mark(start, stop, kind, sound(), kept);
        }
    }

    /** One night listened, with its marks by start. */
    public static final class Night {
        public final double start;
        /** The last time it was known to listen (NaN when never written); while {@link #ended} is null it runs or was killed. */
        public final double stop;
        public final String ended;
        public final List<Clip> clips = new ArrayList<Clip>();
        /** Seconds the microphone gave only silence (another app held it). */
        public double silent;

        Night(double start, double stop, String ended) {
            this.start = start;
            this.stop = stop;
            this.ended = ended;
        }

        public List<SleepSounds.Mark> marks() {
            List<SleepSounds.Mark> out = new ArrayList<SleepSounds.Mark>();
            for (Clip c : clips) out.add(c.mark());
            return out;
        }
    }

    /** Where a night's marks and gaps end: before the next night, and a minute after its last known time (13 hours when unknown). */
    private static double nightEnd(Db db, double start, double stop) {
        double end = Double.isNaN(stop) ? start + 13 * 3600 : stop + 60;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MIN(start) FROM sound_nights WHERE start > ?", new String[] {String.valueOf(start)});
        try {
            if (c.moveToFirst() && !c.isNull(0)) end = Math.min(end, c.getDouble(0));
        } finally {
            c.close();
        }
        return end;
    }

    /** The night that started at {@code start}, with its marks and silence; null when there is none. */
    public static Night night(Db db, double start) {
        Night n = null;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT start, stop, ended FROM sound_nights WHERE start = ?", new String[] {String.valueOf(start)});
        try {
            if (c.moveToFirst()) n = new Night(c.getDouble(0), c.isNull(1) ? Double.NaN : c.getDouble(1), c.isNull(2) ? null : c.getString(2));
        } finally {
            c.close();
        }
        if (n == null) return null;
        double end = nightEnd(db, n.start, n.stop);
        String[] range = {String.valueOf(n.start), String.valueOf(end)};
        c = db.getReadableDatabase().rawQuery("SELECT start, stop, kind, file, kept, gone FROM sound_clips WHERE start >= ? AND start < ? ORDER BY start", range);
        try {
            while (c.moveToNext()) n.clips.add(new Clip(c.getDouble(0), c.getDouble(1), c.getString(2), c.getString(3), c.getLong(4) != 0, c.getLong(5) != 0));
        } finally {
            c.close();
        }
        c = db.getReadableDatabase().rawQuery("SELECT SUM(stop - start) FROM sound_gaps WHERE start >= ? AND start < ? AND why = ?",
                new String[] {range[0], range[1], GAP_SILENCE});
        try {
            if (c.moveToFirst() && !c.isNull(0)) n.silent = c.getDouble(0);
        } finally {
            c.close();
        }
        return n;
    }

    /** The newest night shown on {@code day}'s Sleep tab (started from 12:00 the day before to 12:00 that day), null for none. */
    public static Night forDay(Db db, java.time.LocalDate day, java.time.ZoneId zone) {
        double from = day.minusDays(1).atTime(12, 0).atZone(zone).toEpochSecond(), to = day.atTime(12, 0).atZone(zone).toEpochSecond();
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MAX(start) FROM sound_nights WHERE start >= ? AND start < ?",
                new String[] {String.valueOf(from), String.valueOf(to)});
        double start = Double.NaN;
        try {
            if (c.moveToFirst() && !c.isNull(0)) start = c.getDouble(0);
        } finally {
            c.close();
        }
        return Double.isNaN(start) ? null : night(db, start);
    }

    /** Whether any night was listened (the demo is built again when it has none). */
    static boolean any(Db db) {
        return db.count("SELECT COUNT(*) FROM sound_nights") > 0;
    }

    /**
     * Nights never closed (the phone ended the app while it listened) get {@link #ENDED_SYSTEM}; their stop stays the last
     * minute they were known to listen. {@code running}: the start of the night listening now (left alone), NaN for none.
     */
    static int closeStale(Db db, double running) {
        String[] arg = Double.isNaN(running) ? null : new String[] {String.valueOf(running)};
        String where = Double.isNaN(running) ? "ended IS NULL" : "ended IS NULL AND start != ?";
        int n = 0;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sound_nights WHERE " + where, arg);
        try {
            if (c.moveToFirst()) n = c.getInt(0);
        } finally {
            c.close();
        }
        if (n == 0) return 0;
        if (arg == null) db.getWritableDatabase().execSQL("UPDATE sound_nights SET ended = ? WHERE " + where, new Object[] {ENDED_SYSTEM});
        else db.getWritableDatabase().execSQL("UPDATE sound_nights SET ended = ? WHERE " + where, new Object[] {ENDED_SYSTEM, running});
        return n;
    }

    /**
     * "Delete all clips of this night" (removes rows, as Delete all does): the night's row, its marks and gaps in one transaction,
     * then their files. A file that cannot be deleted is removed by the next sweep, since no row names it any more. A night
     * listening now must be stopped first ({@code SoundNight.deleteNight} does). Returns the marks deleted.
     */
    public static int deleteNight(final Db db, File folder, final double start) {
        Night n = night(db, start);
        if (n == null) return 0;
        final double end = nightEnd(db, n.start, n.stop);
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase w = db.getWritableDatabase();
                w.execSQL("DELETE FROM sound_clips WHERE start >= ? AND start < ?", new Object[] {start, end});
                w.execSQL("DELETE FROM sound_gaps WHERE start >= ? AND start < ?", new Object[] {start, end});
                w.execSQL("DELETE FROM sound_nights WHERE start = ?", new Object[] {start});
            }
        });
        for (Clip c : n.clips) if (clipName(c.file)) new File(folder, c.file).delete();
        return n.clips.size();
    }

    /**
     * Delete sound (the clip list): the clip's file goes, its mark stays with {@code gone} 1, as the retention does. False when
     * the file is there and could not be deleted (the mark then keeps its sound).
     */
    public static boolean deleteSound(Db db, File folder, double start) {
        String file = null;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT file FROM sound_clips WHERE start = ?", new String[] {String.valueOf(start)});
        try {
            if (c.moveToFirst()) file = c.getString(0);
        } finally {
            c.close();
        }
        File f = clipName(file) ? new File(folder, file) : null;
        if (f != null && f.exists() && !f.delete()) return false;
        List<Double> one = new ArrayList<Double>();
        one.add(start);
        markGone(db, one);
        return true;
    }

    /**
     * The retention with the person's choice, then the sweep, on {@code db}'s own folder. The sweep is left out while a night
     * listens into that folder: a snoring stretch's clip waits for its mark until the stretch ends, which can be longer than the
     * sweep's grace. Returns the marks that lost their sound.
     */
    public static int tidy(Context c, Db db, int keepDays) throws java.io.IOException {
        int n = retain(c, db, keepDays);
        boolean real = !Db.DEMO.equals(db.fileName);
        if (!(real && SoundNight.isRunning())) n += sweep(c, db).rows;
        return n;
    }

    /** The last {@code SleepSounds.HISTORY} main nights up to {@code today} (each {start, end}, oldest first), for the usual times. */
    public static List<double[]> usualNights(Db db, java.time.LocalDate today) {
        List<double[]> out = new ArrayList<double[]>();
        for (String s : db.nightsJson(today.minusDays(SleepSounds.HISTORY + 7).toString(), today.toString(), true)) {
            org.json.JSONObject o = Json.parse(s);
            if (o == null) continue;
            double a = Json.d(o, "start"), b = Json.d(o, "end");
            if (!Double.isNaN(a) && !Double.isNaN(b) && b > a) out.add(new double[] {a, b});
        }
        return out.size() > SleepSounds.HISTORY ? new ArrayList<double[]>(out.subList(out.size() - SleepSounds.HISTORY, out.size())) : out;
    }

    /**
     * The demo's night of sounds, only into the demo file and its folder (Design review of #380): last night from 23:05 to 06:55,
     * talking at 00:41, a snoring stretch from 02:12 to 02:26, a cough like sound at 03:37 (each with a clip of tones made in
     * code, {@code SleepSounds.demoTone}, never a voice), a mark at 05:03 whose sound was deleted, and 12 minutes at 04:10 when
     * another app held the microphone. Old demo clip files are removed first.
     */
    static void demo(Context c, Db db, java.time.LocalDate today, java.time.ZoneId zone) throws java.io.IOException {
        if (!Db.DEMO.equals(db.fileName)) throw new IllegalStateException("demo clips only go into the demo file");
        File folder = folder(c, db);
        deleteTree(folder);
        if (!folder.mkdirs() && !folder.isDirectory()) throw new java.io.IOException("the demo clip folder could not be made");
        double start = today.minusDays(1).atTime(23, 5).atZone(zone).toEpochSecond(), stop = today.atTime(6, 55).atZone(zone).toEpochSecond();
        double talk = at(today, 0, 41, zone), snore = at(today, 2, 12, zone), snoreEnd = at(today, 2, 26, zone), loudest = at(today, 2, 19, zone);
        double cough = at(today, 3, 37, zone), gone = at(today, 5, 3, zone), gap = at(today, 4, 10, zone);
        demoClip(db, folder, talk, talk + 3.2, SoundDetector.TALK, -31, talk, 9);
        demoClip(db, folder, snore, snoreEnd, SoundDetector.SNORE, -27, loudest, 8);
        demoClip(db, folder, cough, cough + 0.6, SoundDetector.COUGH, -24, cough, 6);
        addMark(db, gone, gone + 4, SoundDetector.TALK, -35.0);
        addGap(db, gap, gap + 12 * 60, GAP_SILENCE);
        addNight(db, start, stop, -62.0, 3, ENDED_TIME);
    }

    private static double at(java.time.LocalDate day, int h, int m, java.time.ZoneId zone) {
        return day.atTime(h, m).atZone(zone).toEpochSecond();
    }

    private static void demoClip(Db db, File folder, double start, double stop, String kind, double peak, double sound, double seconds) throws java.io.IOException {
        String name = "s" + Math.round(sound * 1000) + ".wav";
        short[] pcm = SleepSounds.demoTone(kind, seconds);
        java.io.FileOutputStream o = new java.io.FileOutputStream(new File(folder, name));
        try {
            o.write(SleepSounds.wav(pcm, 0, pcm.length, SoundDetector.RATE));
        } finally {
            o.close();
        }
        addClip(db, start, stop, kind, peak, name);
    }
}
