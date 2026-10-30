package app.openring.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import app.openring.core.Hex;
import app.openring.core.analysis.StageFilter;

/**
 * Open Ring backup file: a zip holding {@code openring-backup.json} (ring key, ring identity,
 * profile) and, for a full backup, {@code openring.db} (all synced history and results).
 * Restoring it on a new phone sets the app up without pairing again. The key is in plain text
 * inside the file, so the file must be kept as private as the key itself.
 */
public final class Backup {
    private Backup() {}

    static final String JSON = "openring-backup.json", DB = "openring.db";

    public static final class Result {
        public boolean ok;
        public boolean history;
        public String message;
    }

    /**
     * Write a backup. With {@code withHistory} the checked copy of the history is made first
     * ({@link Exporter#snapshot}, issue #115), so a copy that fails its check leaves the file empty
     * ({@link Exporter.NotSaved}) instead of half a backup.
     */
    public static void write(Context c, OutputStream os, boolean withHistory) throws IOException {
        Prefs p = Prefs.of(c);
        byte[] key = p.loadKey();
        if (key == null) throw new IOException("no ring key saved on this phone");
        File snap = withHistory ? Exporter.snapshot(c) : null;
        try {
            write(p, key, os, snap);
        } finally {
            Exporter.release(snap);
        }
    }

    private static void write(Prefs p, byte[] key, OutputStream os, File snap) throws IOException {
        JSONObject o = new JSONObject();
        Json.put(o, "format", "openring-backup");
        Json.put(o, "version", 1);
        Json.put(o, "created", System.currentTimeMillis() / 1000);
        Json.put(o, "key", Hex.encode(key));
        Json.put(o, "address", p.address());
        Json.put(o, "serial", p.serial());
        Json.put(o, "hardware", p.hardware());
        Json.put(o, "firmware", p.firmware());
        JSONObject prof = new JSONObject();
        Json.put(prof, "age", p.age());
        Json.put(prof, "sex", p.sex());
        Json.put(prof, "weight", p.weightKg());
        Json.put(prof, "height", p.heightCm());
        Json.put(prof, "target_kcal", p.targetKcal());
        Json.put(o, "profile", prof);
        Json.put(o, "background_sync", p.backgroundSync());
        Json.put(o, "screen_goal", p.screenGoalMin());
        Json.put(o, "screen_awake", p.screenAwake());
        Json.put(o, "screen_exclude", new org.json.JSONArray(p.screenExcluded()));
        Json.put(o, "games_on", p.gamesOn());
        Json.put(o, "screen_min_show", p.screenMinShowMin());
        Json.put(o, "stage_detail", p.stageDetail());
        Json.put(o, "finder_sound", p.finderSound());
        ZipOutputStream z = new ZipOutputStream(os);
        z.putNextEntry(new ZipEntry(JSON));
        z.write(o.toString().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        if (snap != null) {
            z.putNextEntry(new ZipEntry(DB));
            Exporter.stream(snap, z);
            z.closeEntry();
        }
        z.finish();
        z.flush();
    }

    /**
     * A restore that has been read and checked but has changed nothing yet. {@link #ask} says the
     * phone holds history the backup lacks, so the person chooses (see {@link #finish}).
     */
    public static final class Plan {
        /** Set when nothing can be restored; the plan holds no temp file then. */
        public Result error;
        /** The backup holds a history (a full backup). */
        public boolean history;
        /** Replacing the history would lose data that only this phone has. */
        public boolean ask;
        /** The phone's history reaches later than the backup's (else it only holds more events). */
        public boolean phoneNewer;
        /** Newest dated event in the backup and on the phone, unix seconds, NaN when none. */
        public double backupNewest = Double.NaN, phoneNewest = Double.NaN;
        JSONObject o;
        byte[] key;
        /** This plan's own copy of the backup's history (a unique file in the cache). */
        File db;
        long backupEvents;
    }

    static final String DAMAGED = "The history in this backup is damaged, nothing was changed.";
    static final String NEWER = "This backup comes from a newer Open Ring. Update the app first, nothing was changed.";
    static final String BROKEN = "This file is damaged or is not an Open Ring backup, nothing was changed.";
    static final String NOT_OPENED = "The history in this backup could not be opened. Your history on this phone was kept, nothing was changed.";
    /** Re dating after a sync moves times by minutes, a sync adds hours. */
    static final double SLACK_S = 3600;

    /**
     * Restore from an Open Ring backup in one go. Also accepts a plain text file that only holds
     * the 32 character key (as people often save it in a note). When the phone holds history the
     * backup lacks, this keeps the phone's history and restores only key and settings.
     */
    public static Result read(Context c, InputStream raw) throws IOException {
        Plan p = prepare(c, raw);
        if (p.error != null) return p.error;
        return finish(c, p, !p.ask);
    }

    /** Read and check a backup and compare it with the phone's history. Changes nothing. */
    public static Plan prepare(Context c, InputStream raw) throws IOException {
        Plan p = new Plan();
        cleanStale(c);
        File tmp = null;
        boolean keep = false;
        try {
            BufferedInputStream in = new BufferedInputStream(raw);
            in.mark(4);
            int b0 = in.read(), b1 = in.read();
            in.reset();
            String json = null;
            if (b0 == 'P' && b1 == 'K') {
                ZipInputStream z = new ZipInputStream(in);
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String name = new File(e.getName()).getName();
                    if (JSON.equals(name)) {
                        json = new String(readAll(z, 1 << 20), StandardCharsets.UTF_8);
                    } else if (DB.equals(name)) {
                        if (tmp == null) tmp = newTemp(c);
                        OutputStream out = new FileOutputStream(tmp);
                        try {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                        } finally {
                            out.close();
                        }
                        p.db = tmp;
                    }
                }
            } else {
                json = new String(readAll(in, 1 << 20), StandardCharsets.UTF_8);
            }
            if (json == null) return fail(p, "This file is not an Open Ring backup.");
            json = withoutBom(json).trim();
            p.o = json.startsWith("{") ? Json.parse(json) : null;
            p.key = Hex.parseKey(p.o != null ? p.o.optString("key", "") : json);
            if (p.key == null) return fail(p, p.o == null ? "No 32 character key found in this file." : "The backup holds no valid ring key.");
            if (p.db != null) {
                if (!looksLikeSqlite(p.db)) return fail(p, DAMAGED);
                Db.Info b = Db.inspect(p.db, true);
                if (!b.ok) return fail(p, Db.NEWER.equals(b.problem) ? NEWER : DAMAGED);
                Db.Info phone = Db.inspect(c.getDatabasePath(Db.REAL), false);
                p.history = true;
                p.backupNewest = b.newest;
                p.backupEvents = b.events;
                p.phoneNewest = phone.newest;
                p.ask = wouldLose(phone, b);
                p.phoneNewer = phoneNewer(phone.newest, b.newest);
            }
            keep = true;
            return p;
        } catch (java.util.zip.ZipException | java.io.EOFException | TooLarge e) {
            // a cut off or damaged zip, or far too big for a backup: a plain sentence (issue #33);
            // a failure writing the temp copy (no space left) still reaches the caller
            return fail(p, BROKEN);
        } finally {
            if (!keep) {
                p.db = null;
                release(tmp);
            }
        }
    }

    /**
     * Apply a checked plan: key and settings always, the history only with {@code replaceHistory}.
     * If the restored history does not open, the old one is put back and nothing is restored.
     */
    public static Result finish(Context c, Plan p, boolean replaceHistory) throws IOException {
        return finish(c, p, replaceHistory, null);
    }

    /** Setup's line while a restore runs. */
    public static final String RESTORING = "Restoring";

    /** What a restore waits for, shown instead of {@link #RESTORING} (issue #219). */
    public interface Progress {
        void step(String what);
    }

    /**
     * As above; {@code progress} gets a line when the swap has to wait for a sync or an analysis first. The swap of the
     * history runs only while no ring task can store (issue #219, {@link app.openring.core.RingGate#whenFree}): a download
     * under way ends first, with every batch in the old history, and none starts until the new one is in place. When
     * the ring stays busy for {@link app.openring.core.RingGate#SWAP_WAIT_MS}, or the thread is interrupted while it
     * waits, nothing is restored ({@link app.openring.core.RingGate#RESTORE_REFUSED}).
     */
    public static Result finish(final Context c, final Plan p, boolean replaceHistory, final Progress progress) throws IOException {
        if (p.error != null) return p.error;
        Result r = new Result();
        try {
            if (p.db != null && replaceHistory) {
                final IOException[] failed = {null};
                final boolean[] waited = {false};
                // demo mode is switched off only once the swap worked (applySettings). The order is the ring lock, then
                // the analysis lock (as pairing): an analysis that may still run after a sync no longer holds the ring
                // (issue #21), and closing its database under it would break it
                boolean ran = app.openring.core.RingGate.RING.whenFree(new Runnable() {
                    @Override
                    public void run() {
                        // the wait is over: the line goes back to what happens now
                        if (waited[0]) progress.step(RESTORING);
                        synchronized (Analyzer.class) {
                            try {
                                Db.replaceWith(c, p.db, p.backupEvents);
                            } catch (IOException e) {
                                failed[0] = e;
                            }
                        }
                    }
                }, app.openring.core.RingGate.SWAP_WAIT_MS, Analyzer.RUNNING, waitLine(progress, waited));
                if (!ran) {
                    r.message = app.openring.core.RingGate.RESTORE_REFUSED;
                    return r;
                }
                if (failed[0] != null) {
                    r.message = NOT_OPENED;
                    return r;
                }
                r.history = true;
            }
        } finally {
            cancel(c, p);
        }
        // the stage detail level belongs to how the history was analysed: it comes along with the
        // history, or onto a phone without analysed nights; a phone that keeps its own nights keeps
        // its level, since another one would analyse its whole history again
        Db.Info phone = r.history ? null : Db.inspect(c.getDatabasePath(Db.REAL), false);
        // a phone history that cannot be read counts as one with nights, so its level stays
        applySettings(Prefs.of(c), p.key, p.o, phone == null || !phone.exists || phone.ok && phone.nights == 0);
        // the key is saved: a Setup screen closed before showing the result must not start over
        Prefs.of(c).setSetupDone(true);
        r.ok = true;
        if (r.history) r.message = "Restored your key, settings and history";
        else if (p.history) r.message = "Restored your key and settings, the history on this phone stays";
        else r.message = "Restored your key and settings";
        return r;
    }

    /** The gate's reason to wait as a line for {@code p}: a ring task, or an analysis (issue #219). */
    static app.openring.core.RingGate.Wait waitLine(final Progress p, final boolean[] waited) {
        if (p == null) return null;
        return new app.openring.core.RingGate.Wait() {
            @Override
            public void waiting(int why) {
                waited[0] = true;
                p.step(why == app.openring.core.RingGate.FOR_RING ? app.openring.core.RingGate.WAIT_RING : Demo.WAITING);
            }
        };
    }

    /** Drop a plan without restoring anything (removes its own temp file). */
    public static void cancel(Context c, Plan p) {
        if (p == null) return;
        release(p.db);
        p.db = null;
    }

    /**
     * Would replacing the phone's history with the backup's lose data? Yes when the phone has
     * events and either reaches more than an hour later or holds more events. A phone history that
     * cannot be read counts as yes, so the person decides.
     */
    static boolean wouldLose(Db.Info phone, Db.Info backup) {
        if (phone == null || !phone.exists) return false;
        if (!phone.ok) return true;
        if (phone.events == 0) return false;
        return phone.events > backup.events || phoneNewer(phone.newest, backup.newest);
    }

    static boolean phoneNewer(double phoneNewest, double backupNewest) {
        if (Double.isNaN(phoneNewest)) return false;
        return Double.isNaN(backupNewest) || phoneNewest > backupNewest + SLACK_S;
    }

    private static Plan fail(Plan p, String message) {
        p.error = new Result();
        p.error.message = message;
        return p;
    }

    /** Temp files of plans in this process; anything else named restore*.db in the cache is left over. */
    private static final java.util.Set<String> LIVE = new java.util.HashSet<String>();

    private static File newTemp(Context c) throws IOException {
        File f = File.createTempFile("restore-", ".db", c.getCacheDir());
        synchronized (LIVE) {
            LIVE.add(f.getName());
        }
        return f;
    }

    private static void release(File f) {
        if (f == null) return;
        for (String s : new String[] {"", "-wal", "-shm", "-journal"}) new File(f.getPath() + s).delete();
        synchronized (LIVE) {
            LIVE.remove(f.getName());
        }
    }

    /**
     * Delete restore files that no plan in this process owns (left by a stopped app, or the
     * single {@code restore.db} older versions used).
     */
    public static void cleanStale(Context c) {
        File[] fs = c.getCacheDir().listFiles();
        if (fs == null) return;
        synchronized (LIVE) {
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("restore")) continue;
                int end = n.indexOf(".db");
                if (end < 0) continue;
                if (!LIVE.contains(n.substring(0, end + 3))) f.delete();
            }
        }
    }

    /**
     * Key and settings from a backup. Numbers are kept inside what Settings and Setup allow (issue
     * #33): a value out of range is set to the nearest limit, as typing it in Settings does; a choice
     * that does not exist keeps the current one.
     */
    private static void applySettings(Prefs p, byte[] key, JSONObject o, boolean stageDetail) {
        p.setDemo(false);
        p.saveKey(key);
        if (o == null) return;
        if (!o.isNull("address") && o.optString("address").length() > 0) p.setAddress(o.optString("address"));
        if (!o.isNull("serial")) p.setSerial(o.optString("serial", null));
        if (!o.isNull("hardware")) p.setHardware(o.optString("hardware", null));
        if (!o.isNull("firmware")) p.setFirmware(o.optString("firmware", null));
        JSONObject prof = o.optJSONObject("profile");
        if (prof != null) {
            int sex = prof.optInt("sex", p.sex());
            p.setProfile(clamp(prof.optInt("age", p.age()), 10, 100), sex >= 0 && sex <= 2 ? sex : p.sex(),
                    clamp(prof.optInt("weight", p.weightKg()), 30, 250), clamp(prof.optInt("height", p.heightCm()), 100, 230),
                    clamp(prof.optInt("target_kcal", p.targetKcal()), 0, 3000));
            p.setProfileSet();
        }
        if (o.has("background_sync")) p.setBackgroundSync(o.optBoolean("background_sync", true));
        if (o.has("screen_goal")) p.setScreenGoalMin(clamp(o.optInt("screen_goal", p.screenGoalMin()), 0, 1440));
        if (o.has("screen_awake")) p.setScreenAwake(o.optBoolean("screen_awake", true));
        org.json.JSONArray ex = o.optJSONArray("screen_exclude");
        if (ex != null) {
            java.util.List<String> l = new java.util.ArrayList<String>();
            for (int i = 0; i < ex.length(); i++) l.add(ex.optString(i, ""));
            p.setScreenExcluded(l);
        }
        if (o.has("games_on")) p.setGamesOn(o.optBoolean("games_on", false));
        if (o.has("screen_min_show")) {
            int m = o.optInt("screen_min_show", 5);
            p.setScreenMinShowMin(m == 0 || m == 5 || m == 10 || m == 15 ? m : 5);
        }
        if (stageDetail && o.has("stage_detail")) {
            int d = o.optInt("stage_detail", -1);
            if (d >= StageFilter.DETAILED && d <= StageFilter.CALM) p.setStageDetail(d);
        }
        if (o.has("finder_sound")) p.setFinderSound(o.optBoolean("finder_sound", true));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static boolean looksLikeSqlite(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] h = new byte[16];
            return in.read(h) == 16 && new String(h, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3");
        } finally {
            in.close();
        }
    }

    /** More than a backup's JSON or a key file can hold. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("file too large");
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new TooLarge();
        }
        return bo.toByteArray();
    }

    /** Editors like Windows Notepad start a saved text with a byte order mark (issue #33). */
    static String withoutBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
