package app.openring.data;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.openring.core.Backups;
import app.openring.core.OneRun;
import app.openring.core.RingGate;

/**
 * The automatic backups (issue #242): the job's run (check the copies made before an update, send a new one to the
 * folder, make a regular copy when one is due) and going back to a copy on the phone. The folder is reached through
 * {@link Folder} ({@code sync/SafFolder} on the phone, a plain directory in the tests). The live history is only read
 * ({@link Exporter#snapshot}); a folder file is written as {@code .partial}, read back and renamed, and only then are
 * older files of the app deleted ({@link Backups#folderToDelete}). Files the app did not write are never touched.
 */
public final class AutoBackup {
    private AutoBackup() {}

    /** Never two runs at once (the job and its one off check can meet). The manual backup should take it too (#222). */
    public static final OneRun<Object> RUN = new OneRun<Object>();

    /** How long a run waits for a sync, another ring task or an analysis before it tries again the next day. */
    static long waitMs = 5 * 60 * 1000L;

    /** The folder the person picked, by file name. */
    public interface Folder {
        /** The names of the files in it; {@link Gone} when it cannot be reached. */
        List<String> list() throws IOException;

        /** A new file; {@link Gone} or {@link Full} when it cannot be made. */
        OutputStream create(String name) throws IOException;

        InputStream open(String name) throws IOException;

        void rename(String from, String to) throws IOException;

        void delete(String name) throws IOException;
    }

    /** The folder is gone, or the app may no longer use it (card removed, permission taken back). */
    public static class Gone extends IOException {
        public Gone(String m, Throwable cause) {
            super(m, cause);
        }
    }

    /** The folder has no room left. */
    public static class Full extends IOException {
        public Full(String m, Throwable cause) {
            super(m, cause);
        }
    }

    /** What a run did, for the log and the tests. */
    public static final class Outcome {
        /** Why nothing was tried (demo mode, already running, not due, busy), null when it tried. */
        public String skipped;
        /** The reason code ({@link Backups}) when a folder copy failed. */
        public String error;
        /** The folder files written in this run. */
        public final List<String> written = new ArrayList<String>();
        /** Copies before an update that passed their check in this run. */
        public int checked;
    }

    /**
     * One run of the job at {@code now} (unix seconds). {@code folder} is null without a folder (then only the phone
     * copies are checked). {@code onlyCheck}: the one off job after an update, which makes no regular copy.
     */
    public static Outcome run(Context c, Folder folder, long now, boolean onlyCheck, Analyzer.Log log) {
        Outcome o = new Outcome();
        Prefs p = Prefs.of(c);
        if (p.demo()) {
            o.skipped = "demo mode";
            return o;
        }
        if (!RUN.start()) {
            o.skipped = "a backup is already running";
            return o;
        }
        try {
            o.checked = BeforeUpdate.checkPending(c, log);
            if (folder == null) return o;
            ZoneId z = ZoneId.systemDefault();
            BeforeUpdate.Entry update = unsent(c, p);
            if (update != null) {
                String name = Backups.folderName(LocalDateTime.ofInstant(Instant.ofEpochSecond(now), z), update.copy.version);
                File copy = new File(BeforeUpdate.dir(c), update.copy.file);
                String err = write(c, p, folder, name, copy, now, false, o, log);
                if (err == null) p.setUpdateCopySent((int) update.copy.code);
                else return o;
            }
            if (onlyCheck || !Backups.due(now, p.autoLastRegular(), p.backupDays())) {
                if (o.written.isEmpty()) o.skipped = onlyCheck ? "only the check" : "not due";
                return o;
            }
            String name = Backups.folderName(LocalDateTime.ofInstant(Instant.ofEpochSecond(now), z), null);
            write(c, p, folder, name, null, now, true, o, log);
            return o;
        } finally {
            RUN.finish();
        }
    }

    /** The newest checked copy before an update that the folder has not had yet. */
    static BeforeUpdate.Entry unsent(Context c, Prefs p) {
        for (BeforeUpdate.Entry e : BeforeUpdate.list(c)) {
            if (e.copy.back || !Backups.CHECKED.equals(e.copy.state)) continue;
            return e.copy.code > p.updateCopySent() ? e : null;
        }
        return null;
    }

    /**
     * A new folder was chosen: only copies made from now on go there (an update copy from months ago would only be
     * confusing), and the schedule starts afresh.
     */
    public static void folderChosen(Context c, String uri, String name) {
        Prefs p = Prefs.of(c);
        int newest = p.updateCopySent();
        for (BeforeUpdate.Entry e : BeforeUpdate.list(c)) if (!e.copy.back) newest = (int) Math.max(newest, e.copy.code);
        p.setBackupFolder(uri, name);
        p.setUpdateCopySent(newest);
    }

    /**
     * Writes one folder file {@code name}: from {@code copy} (a checked phone copy) or, when null, from a snapshot of the
     * history made while no sync, ring task or analysis runs. Returns null when it worked, else the reason code (also
     * stored for Settings and Data check). The database is only read.
     */
    static String write(Context c, Prefs p, Folder folder, String name, File copy, long now, boolean regular, Outcome o, Analyzer.Log log) {
        String err = null;
        File snap = null;
        String partial = name + Backups.PART;
        boolean created = false;
        try {
            byte[] key = p.loadKey();
            if (key == null) throw new IOException("no ring key saved on this phone");
            List<String> present = folder.list();
            // a file of that name that the app did not write is never taken for one of its own (review of #259)
            if (present.contains(name) || present.contains(partial)) throw new IOException(name + " is there already");
            if (copy == null) {
                long size = c.getDatabasePath(Db.REAL).length() + new File(c.getDatabasePath(Db.REAL).getPath() + "-wal").length();
                long free = BeforeUpdate.freeForTest >= 0 ? BeforeUpdate.freeForTest : c.getCacheDir().getUsableSpace();
                if (!Backups.enoughSpace(free, size)) {
                    err = Backups.SPACE;
                    return err;
                }
                final File[] made = {null};
                final boolean[] demo = {false};
                final IOException[] failed = {null};
                final Context ctx = c;
                // the copy is made while nothing stores into the history (like a restore, issue #219); the zipping
                // after it holds nothing
                int why = RingGate.RING.whenFree(new Runnable() {
                    @Override
                    public void run() {
                        // demo mode switched on while this waited: the history open now is the demo file, never a backup
                        // (review of #259). Entering demo mode swaps through the same gate, so it cannot start meanwhile
                        if (Prefs.of(ctx).demo()) {
                            demo[0] = true;
                            return;
                        }
                        try {
                            made[0] = Exporter.snapshot(ctx);
                        } catch (IOException e) {
                            failed[0] = e;
                        }
                    }
                }, waitMs, Analyzer.RUNNING, null, null);
                if (why != RingGate.RAN) {
                    o.skipped = "the ring or an analysis stayed busy";
                    if (log != null) log.log("automatic backup waits for the next run: " + RingGate.waitLine(why));
                    return null;
                }
                if (demo[0]) {
                    o.skipped = "demo mode";
                    return null;
                }
                if (failed[0] != null) throw failed[0];
                snap = made[0];
            } else {
                snap = copy;
            }
            // listed before the file exists, so a stop leaves only a .partial the next run knows as its own
            Set<String> listed = p.autoFiles();
            listed.add(name);
            if (!p.setAutoFiles(listed)) throw new IOException("the list of copies could not be saved");
            OutputStream os = folder.create(partial);
            created = true;
            try {
                Backup.write(p, key, os, snap);
                os.flush();
            } finally {
                os.close();
            }
            long size = readBack(folder, partial, snap.length());
            folder.rename(partial, name);
            created = false;
            // the new file is complete and checked: only now do older files of the app go
            List<String> after = folder.list();
            for (String old : Backups.folderToDelete(after, listed, name, p.backupKeep())) {
                try {
                    folder.delete(old);
                    listed.remove(old.endsWith(Backups.PART) ? old.substring(0, old.length() - Backups.PART.length()) : old);
                    if (log != null) log.log("older backup deleted: " + old);
                } catch (IOException e) {
                    if (log != null) log.log("older backup not deleted: " + old + ": " + e.getMessage());
                }
            }
            after = folder.list();
            // names the app wrote that are no longer there (deleted by the person) leave the list
            listed.retainAll(withPartials(after));
            p.setAutoFiles(listed);
            int kept = 0;
            for (String n : after) if (Backups.folderFile(n) && listed.contains(n)) kept++;
            p.setAutoOk(now, size, kept, regular);
            o.written.add(name);
            if (log != null) log.log("automatic backup written: " + name + " (" + Backups.mb(size) + ", " + kept + " kept)");
            return null;
        } catch (Exporter.NotSaved e) {
            err = Backups.CHECK;
            if (log != null) log.log("automatic backup not saved: " + e.getMessage());
        } catch (NotComplete e) {
            err = Backups.CHECK;
            if (log != null) log.log("automatic backup did not read back: " + e.getMessage());
        } catch (Gone e) {
            err = Backups.GONE;
            if (log != null) log.log("automatic backup: folder not reachable: " + e.getMessage());
        } catch (Full e) {
            err = Backups.FULL;
            if (log != null) log.log("automatic backup: folder full: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            String text = chain(e);
            err = Backups.noSpace(text) ? (snap == null && copy == null ? Backups.SPACE : Backups.FULL) : Backups.WRITE;
            if (log != null) log.log("automatic backup failed: " + text);
        } finally {
            if (snap != null && snap != copy) Exporter.release(snap);
            if (created) {
                try {
                    folder.delete(partial); // the next run would delete it too
                } catch (IOException | RuntimeException ignored) {
                    // left as .partial, never counted, removed by the next run
                }
            }
            if (err != null) {
                p.setAutoResult(now, err);
                o.error = err;
            }
        }
        return err;
    }

    /** The file read back does not hold the whole history. */
    static final class NotComplete extends IOException {
        NotComplete(String m) {
            super(m);
        }
    }

    /**
     * Reads the written file back: every entry in full (a zip entry with a wrong CRC throws), the settings entry, and a
     * history entry exactly as long as the checked copy. Returns the file's size.
     */
    static long readBack(Folder folder, String name, long dbBytes) throws IOException {
        InputStream raw = folder.open(name);
        if (raw == null) throw new NotComplete("the file could not be opened again");
        final long[] size = {0};
        // the buffer below reads in blocks only, so counting the block reads counts every byte
        InputStream in = new java.io.FilterInputStream(raw) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) size[0] += n;
                return n;
            }
        };
        boolean json = false;
        long db = -1;
        try {
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in);
            ZipInputStream z = new ZipInputStream(bin);
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                long n = 0;
                int r;
                while ((r = z.read(buf)) > 0) n += r;
                if (Backup.JSON.equals(e.getName())) json = n > 0;
                else if (Backup.DB.equals(e.getName())) db = n;
            }
            // the zip's directory at its end, so the size is the whole file's
            while (bin.read(buf) > 0) {
                // only counted
            }
        } catch (java.util.zip.ZipException | java.io.EOFException e) {
            throw new NotComplete("the file is damaged: " + e.getMessage());
        } finally {
            in.close();
        }
        if (!json) throw new NotComplete("no settings in the file");
        if (db != dbBytes) throw new NotComplete("the history in the file has " + db + " bytes, the copy " + dbBytes);
        return size[0];
    }

    static Set<String> withPartials(List<String> names) {
        Set<String> out = new java.util.HashSet<String>();
        for (String n : names) out.add(n.endsWith(Backups.PART) ? n.substring(0, n.length() - Backups.PART.length()) : n);
        return out;
    }

    static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; t != null && i < 8; t = t.getCause(), i++) b.append(t).append("; ");
        return b.toString();
    }

    // ------------------------------------------------------------------ going back to a copy on the phone

    /** The time of the copy before going back as the tests set it, 0 for the clock. */
    static long nowForTest;

    static final String GONE_COPY = "This copy is no longer on the phone, nothing was changed.";
    static final String BAD_COPY = "This copy did not pass its check, nothing was changed.";

    /**
     * Puts the history of the phone copy {@code file} back (Settings, "Go back to this copy"). The copy is checked on
     * its own first (log folded in, {@code Db.inspect}); then, inside the swap (no sync, ring task or analysis), the
     * current history is kept as a copy of its own ("Copy from before going back") and the copy replaces it through
     * the checked restore ({@link Backup#finish}, {@link Db#replaceWith}). Key and settings stay. Any failure changes
     * nothing.
     */
    public static Backup.Result goBack(final Context c, String file, Backup.Progress progress) throws IOException {
        Backup.Result fail = new Backup.Result();
        if (Prefs.of(c).demo()) {
            fail.message = Backups.NOT_IN_DEMO;
            return fail;
        }
        byte[] key = Prefs.of(c).loadKey();
        if (key == null) {
            fail.message = "No ring key saved on this phone, nothing was changed.";
            return fail;
        }
        Backups.Copy cp = Backups.parse(file);
        File tmp = Backup.newTemp(c);
        boolean handed = false;
        try {
            synchronized (BeforeUpdate.LOCK) {
                File src = new File(BeforeUpdate.dir(c), file);
                if (cp == null || !src.isFile() || !(Backups.CHECKED.equals(cp.state) || Backups.RAW.equals(cp.state))) {
                    fail.message = cp != null && Backups.BAD.equals(cp.state) ? BAD_COPY : GONE_COPY;
                    return fail;
                }
                BeforeUpdate.copy(src, tmp);
                for (String s : BeforeUpdate.SIDE) {
                    File side = new File(src.getPath() + s);
                    if (side.exists()) BeforeUpdate.copy(side, new File(tmp.getPath() + s));
                }
            }
            Db.Info i;
            try {
                Exporter.singleFile(tmp);
                i = Db.inspect(tmp, true);
            } catch (IOException | RuntimeException e) {
                i = null;
            }
            if (i == null || !i.ok) {
                fail.message = BAD_COPY;
                return fail;
            }
            Backup.Plan plan = Backup.fromCopy(tmp, key, i.events);
            handed = true;
            final long now = nowForTest > 0 ? nowForTest : System.currentTimeMillis() / 1000;
            Backup.Result r = Backup.finish(c, plan, true, progress, new Backup.Before() {
                @Override
                public void run() throws IOException {
                    keepCurrent(c, now);
                }
            });
            if (r.history) {
                r.message = Backups.WENT_BACK;
                synchronized (BeforeUpdate.LOCK) {
                    String[] fs = BeforeUpdate.dir(c).list();
                    if (fs != null) for (String f : Backups.phoneToDelete(java.util.Arrays.asList(fs))) BeforeUpdate.deleteWithSides(new File(BeforeUpdate.dir(c), f));
                }
            }
            return r;
        } finally {
            if (!handed) Backup.release(tmp);
        }
    }

    /** The history as it is now becomes the phone copy "before going back" (checked by the snapshot). */
    static void keepCurrent(Context c, long now) throws IOException {
        File snap = Exporter.snapshot(c);
        try {
            synchronized (BeforeUpdate.LOCK) {
                File d = BeforeUpdate.dir(c);
                if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not make the folder for the copy");
                File partial = new File(d, Backups.backName(now, Backups.PARTIAL)), done = new File(d, Backups.backName(now, Backups.CHECKED));
                if (!snap.renameTo(partial)) BeforeUpdate.copy(snap, partial);
                BeforeUpdate.move(partial, done);
            }
        } finally {
            Exporter.release(snap);
        }
    }
}
