package app.openring.data;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.openring.core.Backups;

/**
 * The copy of the history before an update (issue #242). Android gives no warning before it replaces the app, so
 * the new version copies the history on its first start, before anything opens it: {@link #ensure} runs first in
 * {@link Db#get} and {@link Db#replaceWith}, under Db's class lock, so no helper has the file open, upgraded or
 * changed it yet. The copy is a plain byte copy of the file and its write ahead log, written as {@code .partial},
 * renamed to {@code .raw} when complete, and only then the new version is noted; a stop before the note leaves a
 * {@code .partial} (deleted at the next start, which copies again). A later job checks the copy ({@link #checkPending}:
 * the log folded in, {@code Db.inspect}) and renames it to {@code .db}, or {@code .bad} when it fails. The two newest
 * checked copies stay; an older one is deleted only after newer ones passed their check. The history itself is only
 * read here, never written.
 */
public final class BeforeUpdate {
    private BeforeUpdate() {}

    static final String DIR = "before-update";
    /** The side files a history can have next to it; {@code -shm} only mirrors the log and is never copied. */
    static final String[] SIDE = {"-wal", "-journal"};
    /** Files folders whose first open in this process was handled (one per app; the tests use many). */
    private static final Set<String> HANDLED = new HashSet<String>();
    /** Guards the folder of the copies: the copy at the start, the check, going back, deleting them all. */
    static final Object LOCK = new Object();
    /** Free bytes as the tests set them, -1 to ask the file system. */
    static long freeForTest = -1;

    static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /** This version's code, 0 when it cannot be read (then nothing is compared or copied). */
    static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? String.valueOf(versionCode(c)) : n;
        } catch (Exception e) {
            return String.valueOf(versionCode(c));
        }
    }

    /**
     * Before the first open of either history file (real or demo) in this process: when this is a new version and a
     * real history file exists, it is copied first. Called by {@link Db} with its class lock held. Never throws and
     * never stops the start: any failure is noted ({@link Prefs#beforeUpdateNote}) and the app opens anyway. The demo
     * file is never copied, in demo mode the real one is.
     */
    static void ensure(Context c) {
        String key;
        try {
            key = c.getApplicationContext().getFilesDir().getPath();
        } catch (RuntimeException e) {
            return;
        }
        synchronized (HANDLED) {
            if (!HANDLED.add(key)) return;
        }
        try {
            copyIfUpdated(c);
        } catch (Throwable t) {
            // also out of memory: the start goes on, the reason is kept for Data check and the main screen
            try {
                Prefs p = Prefs.of(c);
                p.setBeforeUpdateNote("error: " + t, true);
                p.noteVersion(versionCode(c));
            } catch (Throwable ignored) {
                // nothing more can be done; the history opens as before
            }
        }
    }

    static void copyIfUpdated(Context c) throws IOException {
        // in demo mode the real file was not opened yet: an unfinished restore puts its kept history back first
        Db.recoverSwap(c);
        Prefs p = Prefs.of(c);
        int current = versionCode(c);
        File db = c.getDatabasePath(Db.REAL);
        synchronized (LOCK) {
            File d = dir(c);
            cleanPartials(d);
            int what = Backups.beforeUpdate(p.notedVersion(), current, db.isFile());
            if (what == Backups.SAME) return;
            if (what == Backups.NOTE) {
                p.noteVersion(current);
                return;
            }
            String name = versionName(c);
            // a copy for this version is there already (its note was lost): the history was opened since, so a new
            // copy would not be the one from before the update
            for (String st : new String[] {Backups.RAW, Backups.CHECKED, Backups.BAD}) {
                if (new File(d, Backups.copyName(current, name, st)).exists()) {
                    p.noteVersion(current);
                    return;
                }
            }
            long size = db.length();
            for (String s : SIDE) size += new File(db.getPath() + s).length();
            long free = freeForTest >= 0 ? freeForTest : c.getFilesDir().getUsableSpace();
            if (!Backups.enoughSpace(free, size)) {
                p.setBeforeUpdateNote(Backups.SPACE, true);
                p.noteVersion(current);
                return;
            }
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not make the folder for the copy");
            File partial = new File(d, Backups.copyName(current, name, Backups.PARTIAL));
            File raw = new File(d, Backups.copyName(current, name, Backups.RAW));
            try {
                copy(db, partial);
                for (String s : SIDE) {
                    File side = new File(db.getPath() + s);
                    if (side.exists()) copy(side, new File(partial.getPath() + s));
                }
                // the side files first, the main file last: only a complete .raw counts as a copy
                for (String s : SIDE) move(new File(partial.getPath() + s), new File(raw.getPath() + s));
                move(partial, raw);
            } catch (IOException e) {
                deleteWithSides(partial);
                for (String s : SIDE) new File(raw.getPath() + s).delete();
                p.setBeforeUpdateNote("error: " + e.getMessage(), true);
                p.noteVersion(current);
                return;
            }
            p.setBeforeUpdateNote(null, false);
            p.noteVersion(current);
        }
    }

    /** Copies left half written by a stop, and logs whose copy is gone. */
    static void cleanPartials(File d) {
        String[] fs = d.list();
        if (fs == null) return;
        Set<String> names = new HashSet<String>(Arrays.asList(fs));
        for (String f : fs) {
            boolean partial = f.endsWith("." + Backups.PARTIAL);
            for (String s : SIDE) if (f.endsWith("." + Backups.PARTIAL + s)) partial = true;
            boolean orphan = false;
            for (String s : SIDE) if (f.endsWith(s) && !names.contains(f.substring(0, f.length() - s.length()))) orphan = true;
            if (partial || orphan || f.endsWith("-shm")) new File(d, f).delete();
        }
    }

    /** A phone copy as Settings lists it. */
    public static final class Entry {
        public final Backups.Copy copy;
        /** When it was made (unix seconds) and its size on the phone, with its log. */
        public final long time, bytes;

        Entry(Backups.Copy copy, long time, long bytes) {
            this.copy = copy;
            this.time = time;
            this.bytes = bytes;
        }

        /** It can be gone back to (checked, or complete and not checked yet). */
        public boolean usable() {
            return Backups.CHECKED.equals(copy.state) || Backups.RAW.equals(copy.state);
        }
    }

    /** The copies on the phone: before updates first, newest first, then the copy before going back. */
    public static List<Entry> list(Context c) {
        // without the lock: the screens list the copies while a check may run for seconds; a copy renamed meanwhile
        // shows at the next look
        List<Entry> out = new ArrayList<Entry>();
        File d = dir(c);
        String[] fs = d.list();
        if (fs == null) return out;
        List<String> names = Arrays.asList(fs);
        for (boolean back : new boolean[] {false, true}) {
            for (Backups.Copy cp : Backups.copies(names, back)) {
                if (Backups.PARTIAL.equals(cp.state)) continue;
                File f = new File(d, cp.file);
                long bytes = f.length();
                for (String s : SIDE) bytes += new File(f.getPath() + s).length();
                out.add(new Entry(cp, back ? cp.code : f.lastModified() / 1000, bytes));
            }
        }
        return out;
    }

    /** Whether a copy waits for its check (names only, quick enough for the main screen's resume). */
    public static boolean pending(Context c) {
        String[] fs = dir(c).list();
        if (fs != null) for (String f : fs) {
            Backups.Copy cp = Backups.parse(f);
            if (cp != null && Backups.RAW.equals(cp.state)) return true;
        }
        return false;
    }

    /**
     * Checks every copy that waits for it: its log is folded in, then it must pass {@link Db#inspect} (quick_check, a
     * version this app can open, every table and column). It becomes {@code .db}, or {@code .bad} (kept, with its log
     * as it was when folding failed). Then older copies go as {@link Backups#phoneToDelete} says. Returns how many
     * passed now.
     */
    static int checkPending(Context c, Analyzer.Log log) {
        int passed = 0;
        synchronized (LOCK) {
            File d = dir(c);
            String[] fs = d.list();
            if (fs == null) return 0;
            for (Backups.Copy cp : Backups.copies(Arrays.asList(fs), false)) {
                if (!Backups.RAW.equals(cp.state)) continue;
                File f = new File(d, cp.file);
                long made = f.lastModified();
                boolean ok;
                String why = null;
                try {
                    Exporter.singleFile(f);
                    Db.Info i = Db.inspect(f, true);
                    ok = i.ok;
                    if (!ok) why = i.problem + (i.schema != null ? " (" + i.schema + ")" : "");
                } catch (IOException | RuntimeException e) {
                    ok = false;
                    why = String.valueOf(e);
                }
                File to = new File(d, cp.named(ok ? Backups.CHECKED : Backups.BAD));
                try {
                    for (String s : SIDE) move(new File(f.getPath() + s), new File(to.getPath() + s));
                    move(f, to);
                    to.setLastModified(made); // the date shown is when the copy was made, not when it was checked
                    new File(f.getPath() + "-shm").delete();
                } catch (IOException e) {
                    if (log != null) log.log("copy before the update to " + cp.version + " not renamed: " + e.getMessage());
                    continue;
                }
                if (ok) passed++;
                if (log != null) log.log("copy before the update to " + cp.version + (ok ? " passed its check" : " did not pass its check: " + why));
            }
            fs = d.list();
            if (fs != null) {
                for (String f : Backups.phoneToDelete(Arrays.asList(fs))) {
                    deleteWithSides(new File(d, f));
                    if (log != null) log.log("older copy deleted: " + f);
                }
            }
        }
        return passed;
    }

    /** Delete all local data: the phone copies go too (the folder copies stay). */
    public static void deleteAll(Context c) {
        synchronized (LOCK) {
            File[] fs = dir(c).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
    }

    /** The data check's line: copies (version, date, size, state) and why the last update had none. */
    static String checkLine(Context c, java.time.ZoneId z) {
        List<String[]> rows = new ArrayList<String[]>();
        for (Entry e : list(c)) {
            String state = Backups.CHECKED.equals(e.copy.state) ? "checked" : Backups.RAW.equals(e.copy.state) ? "not checked yet" : "failed its check";
            rows.add(new String[] {e.copy.back ? "before going back" : e.copy.version,
                Backups.day(e.time, z) + ", " + Backups.mb(e.bytes) + ", " + state});
        }
        String note = Prefs.of(c).beforeUpdateNote();
        if (Backups.SPACE.equals(note)) note = "no copy, not enough free space";
        else if (note != null) note = "no copy, " + note;
        return Backups.checkCopies(rows, note);
    }

    static void deleteWithSides(File f) {
        for (String s : SIDE) new File(f.getPath() + s).delete();
        new File(f.getPath() + "-shm").delete();
        f.delete();
    }

    static void move(File from, File to) throws IOException {
        if (from.exists() && !from.renameTo(to)) throw new IOException("could not rename " + from.getName());
    }

    static void copy(File src, File dst) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(src);
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                if (out instanceof java.io.FileOutputStream) ((java.io.FileOutputStream) out).getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
