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

    public static void write(Context c, OutputStream os, boolean withHistory) throws IOException {
        Prefs p = Prefs.of(c);
        byte[] key = p.loadKey();
        if (key == null) throw new IOException("no ring key saved on this phone");
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
        ZipOutputStream z = new ZipOutputStream(os);
        z.putNextEntry(new ZipEntry(JSON));
        z.write(o.toString().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        if (withHistory) {
            z.putNextEntry(new ZipEntry(DB));
            Exporter.database(c, z);
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
                json = new String(readAll(in, 1 << 20), StandardCharsets.UTF_8).trim();
            }
            if (json == null) return fail(p, "This file is not an Open Ring backup.");
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
        if (p.error != null) return p.error;
        Result r = new Result();
        try {
            if (p.db != null && replaceHistory) {
                try {
                    // demo mode is switched off only once the swap worked (applySettings).
                    // Wait for an analysis that may still run after a sync (it no longer holds the
                    // ring, issue #21): closing its database under it would break it
                    synchronized (Analyzer.class) {
                        Db.replaceWith(c, p.db, p.backupEvents);
                    }
                } catch (IOException e) {
                    r.message = NOT_OPENED;
                    return r;
                }
                r.history = true;
            }
        } finally {
            cancel(c, p);
        }
        applySettings(Prefs.of(c), p.key, p.o);
        // the key is saved: a Setup screen closed before showing the result must not start over
        Prefs.of(c).setSetupDone(true);
        r.ok = true;
        if (r.history) r.message = "Restored your key, settings and history";
        else if (p.history) r.message = "Restored your key and settings, the history on this phone stays";
        else r.message = "Restored your key and settings";
        return r;
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

    private static void applySettings(Prefs p, byte[] key, JSONObject o) {
        p.setDemo(false);
        p.saveKey(key);
        if (o == null) return;
        if (!o.isNull("address") && o.optString("address").length() > 0) p.setAddress(o.optString("address"));
        if (!o.isNull("serial")) p.setSerial(o.optString("serial", null));
        if (!o.isNull("hardware")) p.setHardware(o.optString("hardware", null));
        if (!o.isNull("firmware")) p.setFirmware(o.optString("firmware", null));
        JSONObject prof = o.optJSONObject("profile");
        if (prof != null) {
            p.setProfile(prof.optInt("age", p.age()), prof.optInt("sex", p.sex()), prof.optInt("weight", p.weightKg()),
                    prof.optInt("height", p.heightCm()), prof.optInt("target_kcal", p.targetKcal()));
            p.setProfileSet();
        }
        if (o.has("background_sync")) p.setBackgroundSync(o.optBoolean("background_sync", true));
        if (o.has("screen_goal")) p.setScreenGoalMin(o.optInt("screen_goal", p.screenGoalMin()));
        if (o.has("screen_awake")) p.setScreenAwake(o.optBoolean("screen_awake", true));
        org.json.JSONArray ex = o.optJSONArray("screen_exclude");
        if (ex != null) {
            java.util.List<String> l = new java.util.ArrayList<String>();
            for (int i = 0; i < ex.length(); i++) l.add(ex.optString(i, ""));
            p.setScreenExcluded(l);
        }
        if (o.has("games_on")) p.setGamesOn(o.optBoolean("games_on", false));
        if (o.has("screen_min_show")) p.setScreenMinShowMin(o.optInt("screen_min_show", 5));
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

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new IOException("file too large");
        }
        return bo.toByteArray();
    }
}
