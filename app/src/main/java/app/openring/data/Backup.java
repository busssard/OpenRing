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
import app.openring.core.InputLimit;
import app.openring.core.analysis.StageFilter;

/**
 * Open Ring backup file: a zip holding {@code openring-backup.json} (ring key, ring identity,
 * profile) and, for a full backup, {@code openring.db} (all synced history and results).
 * Restoring it on a new phone sets the app up without pairing again. The key is in plain text
 * inside the file, so the file must be kept as private as the key itself.
 * <p>
 * While this phone cannot read its saved key (issue #199) a backup is still written, without the key (issue #288): the
 * JSON then has no {@code key} and says {@code "no_key": true}. This version restores such a file (settings and
 * history, never touching a key on the phone); an older version finds no key in it and refuses the whole file with
 * "The backup holds no valid ring key." before it changes anything.
 */
public final class Backup {
    private Backup() {}

    static final String JSON = "openring-backup.json", DB = "openring.db";
    /** The mark of a backup written without the key (issue #288). */
    static final String NO_KEY = "no_key";

    public static final class Result {
        public boolean ok;
        public boolean history;
        /** The backup had no ring key (issue #288): the key on this phone, if any, stayed as it was. */
        public boolean noKey;
        /** The phone's file was damaged, so its period log could not be carried into the restored history (review of #275). */
        public boolean logDamaged;
        public String message;
    }

    /**
     * Write a backup. With {@code withHistory} the checked copy of the history is made first
     * ({@link Exporter#snapshot}, issue #115), so a copy that fails its check leaves the file empty
     * ({@link Exporter.NotSaved}) instead of half a backup.
     */
    /**
     * As described above. Returns whether the key is in the file: false when a key is saved that this phone cannot read
     * (issue #288), then the file holds everything else and is marked as without a key. With no key saved at all
     * nothing is written.
     */
    public static boolean write(Context c, OutputStream os, boolean withHistory) throws IOException {
        Begun b = begin(c, withHistory);
        try {
            return write(b, os);
        } finally {
            release(b);
        }
    }

    /**
     * A backup's first half (review of #340): the key read and, for a full backup, the checked copy of the history made,
     * before the file the person picked is opened. A copy that fails its check ({@link Exporter.NotSaved}) then leaves
     * that file as it was. Released with {@link #release(Begun)}.
     */
    public static final class Begun {
        final Prefs p;
        final byte[] key;
        File snap;

        Begun(Prefs p, byte[] key) {
            this.p = p;
            this.key = key;
        }
    }

    public static Begun begin(Context c, boolean withHistory) throws IOException {
        Prefs p = Prefs.of(c);
        byte[] key = p.loadKey();
        if (key == null && !p.hasKey()) throw new IOException("no ring key saved on this phone");
        Begun b = new Begun(p, key);
        b.snap = withHistory ? Exporter.snapshot(c) : null;
        return b;
    }

    /** The second half: the file itself. Returns whether the key is in it (see {@link #write(Context, OutputStream, boolean)}). */
    public static boolean write(Begun b, OutputStream os) throws IOException {
        write(b.p, b.key, os, b.snap);
        return b.key != null;
    }

    public static void release(Begun b) {
        if (b == null) return;
        Exporter.release(b.snap);
        b.snap = null;
    }

    /**
     * The backup file around a checked single file copy of the history ({@code snap}, null for key and settings only).
     * {@code key} null: a saved key this phone cannot read; the file says so ({@link #NO_KEY}) instead of holding it.
     */
    static void write(Prefs p, byte[] key, OutputStream os, File snap) throws IOException {
        JSONObject o = new JSONObject();
        Json.put(o, "format", "openring-backup");
        Json.put(o, "version", 1);
        Json.put(o, "created", System.currentTimeMillis() / 1000);
        // no "key" at all without one: every older version then refuses the file before it changes anything
        if (key != null) Json.put(o, "key", Hex.encode(key));
        else Json.put(o, NO_KEY, true);
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
        Json.put(o, "period_shown", p.periodChoice());
        Json.put(o, "badges_on", p.badgesOn());
        // the reminder and "My periods have stopped" (issue #252, phase 1a2): the person's own, never the demo's
        Json.put(o, "period_remind_days", p.periodRemindDays());
        Json.put(o, "period_remind_min", p.periodRemindMinute());
        if (p.periodStoppedReal() != null) Json.put(o, "period_stopped", p.periodStoppedReal());
        if (p.periodAskLaterReal() != null) Json.put(o, "period_ask_later", p.periodAskLaterReal());
        Json.put(o, "screen_min_show", p.screenMinShowMin());
        Json.put(o, "stage_detail", p.stageDetail());
        Json.put(o, "finder_sound", p.finderSound());
        // the automatic backups' choices (issue #242); the folder is not in the file, its permission belongs to this installation
        Json.put(o, "backup_days", p.backupDays());
        Json.put(o, "backup_keep", p.backupKeep());
        // the watch (issue #251): which one and how often it syncs; its messages are in the history
        Json.put(o, "watch_address", p.watchAddress());
        Json.put(o, "watch_name", p.watchName());
        Json.put(o, "watch_per_day", p.watchPerDay());
        Json.put(o, "main_device", p.mainDevice());
        // the watch's setup choices (issue #251, watch setup): step goal, distance unit, the alarm
        Json.put(o, "watch_goal", p.watchGoal());
        Json.put(o, "watch_metric", p.watchMetric());
        app.openring.core.withings.WatchSetup.Alarm al = p.watchAlarm();
        Json.put(o, "watch_alarm_on", al.on);
        Json.put(o, "watch_alarm_h", al.hour);
        Json.put(o, "watch_alarm_m", al.minute);
        Json.put(o, "watch_alarm_days", al.days);
        Json.put(o, "watch_alarm_smart", al.smart);
        ZipOutputStream z = new ZipOutputStream(os);
        // the fastest level (issue #222): packs about three times faster than the default for a file about a tenth larger;
        // any zip reader, also every older version, reads it the same
        z.setLevel(java.util.zip.Deflater.BEST_SPEED);
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
        /** The backup's key; null for a backup written without one ({@link #noKey}). */
        byte[] key;
        /** The backup holds no ring key (issue #288): a key on this phone stays as it is. */
        public boolean noKey;
        /** This plan's own copy of the backup's history (a unique file in the cache). */
        File db;
        long backupEvents;
    }

    /**
     * A plan that puts back a checked copy of the history from this phone ({@code db}, a temp file from {@link #newTemp}
     * that the plan then owns) with the key saved now and without settings (issue #242): key and settings stay.
     */
    static Plan fromCopy(File db, byte[] key, long events) {
        Plan p = new Plan();
        p.db = db;
        p.key = key;
        p.history = true;
        p.backupEvents = events;
        return p;
    }

    /** Runs inside the swap, before the history is replaced (with the ring and the analysis lock held). */
    interface Before {
        void run() throws IOException;
    }

    static final String NOT_KEPT = "Your history could not be kept as a copy first, nothing was changed.";
    /** A JSON backup without a usable key; also what every older version says about a backup without a key (issue #288). */
    static final String NO_VALID_KEY = "The backup holds no valid ring key.";
    /** Added to the result of a restore from a backup without a key (issue #288), by whether this phone has a key it can use. */
    public static final String NO_KEY_ENTER = "This copy has no ring key, enter your key in Setup (Use my existing key) to sync again.";
    public static final String NO_KEY_KEPT = "This copy has no ring key, the key on this phone stays.";

    static final String DAMAGED = "The history in this backup is damaged, nothing was changed.";
    static final String NEWER = "This backup comes from a newer Open Ring. Update the app first, nothing was changed.";
    static final String BROKEN = "This file is damaged or is not an Open Ring backup, nothing was changed.";
    static final String NOT_OPENED = "The history in this backup could not be opened. Your history on this phone was kept, nothing was changed.";
    /** The phone's own period log could not be read before the swap (review of #275): the backup is not to blame. */
    public static final String PERIODS_UNREADABLE = "The period log or the watch data on this phone could not be read, nothing was changed.";
    /** Added to the restore's result when the phone's file was damaged and its period log was left behind (review of #275). */
    public static final String PERIODS_DAMAGED = "The period log and the watch data on this phone were damaged and could not be kept, those of the backup are in place.";
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
            // a backup written while its phone could not read the key (issue #288): only with its mark and no key in it
            p.noKey = p.key == null && p.o != null && !p.o.has("key") && p.o.optBoolean(NO_KEY, false);
            if (p.key == null && !p.noKey) return fail(p, p.o == null ? "No 32 character key found in this file." : NO_VALID_KEY);
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

    /**
     * What a restore waits for, shown instead of {@link #RESTORING} (issue #219). A progress that also implements
     * {@link app.openring.core.RingGate.Stop} can stop the wait (review of #248); a swap once started always ends.
     */
    public interface Progress {
        void step(String what);
    }

    /**
     * As above; {@code progress} gets a line when the swap has to wait for a sync or an analysis first. The swap of the
     * history runs only while no ring task can store (issue #219, {@link app.openring.core.RingGate#whenFree}): a download
     * under way ends first, with every batch in the old history, and none starts until the new one is in place. When
     * the ring stays busy for {@link app.openring.core.RingGate#SWAP_WAIT_MS}, or the thread is interrupted while it
     * waits, nothing is restored ({@link app.openring.core.RingGate#restoreRefused}).
     */
    public static Result finish(final Context c, final Plan p, boolean replaceHistory, final Progress progress) throws IOException {
        return finish(c, p, replaceHistory, progress, null);
    }

    /** As above; {@code before} runs in the swap first, and when it fails nothing is replaced ({@link #NOT_KEPT}). */
    static Result finish(final Context c, final Plan p, boolean replaceHistory, final Progress progress, final Before before) throws IOException {
        if (p.error != null) return p.error;
        Result r = new Result();
        try {
            if (p.db != null && replaceHistory) {
                final IOException[] failed = {null};
                final boolean[] notKept = {false};
                final boolean[] waited = {false};
                final boolean[] logDamaged = {false};
                // demo mode is switched off only once the swap worked (applySettings). The order is the ring lock, then
                // the analysis lock (as pairing): an analysis that may still run after a sync no longer holds the ring
                // (issue #21), and closing its database under it would break it
                int why = app.openring.core.RingGate.RING.whenFree(new Runnable() {
                    @Override
                    public void run() {
                        // the wait is over: the line goes back to what happens now
                        if (waited[0]) progress.step(RESTORING);
                        synchronized (Analyzer.class) {
                            try {
                                if (before != null) {
                                    try {
                                        before.run();
                                    } catch (IOException | RuntimeException e) {
                                        notKept[0] = true;
                                        throw new IOException(e);
                                    }
                                }
                                logDamaged[0] = Db.replaceWith(c, p.db, p.backupEvents);
                            } catch (IOException e) {
                                failed[0] = e;
                            }
                        }
                    }
                }, app.openring.core.RingGate.SWAP_WAIT_MS, Analyzer.RUNNING, waitLine(progress, waited),
                        progress instanceof app.openring.core.RingGate.Stop ? (app.openring.core.RingGate.Stop) progress : null);
                if (why != app.openring.core.RingGate.RAN) {
                    // the sentence names what held it up: a sync, Live or the finder, a re analysis, or the person's stop
                    r.message = app.openring.core.RingGate.restoreRefused(why);
                    return r;
                }
                if (failed[0] != null) {
                    r.message = notKept[0] ? NOT_KEPT : failed[0] instanceof Db.PeriodLogUnreadable ? PERIODS_UNREADABLE : NOT_OPENED;
                    return r;
                }
                r.history = true;
                r.logDamaged = logDamaged[0];
            }
        } finally {
            cancel(c, p);
        }
        // the stage detail level belongs to how the history was analysed: it comes along with the
        // history, or onto a phone without analysed nights; a phone that keeps its own nights keeps
        // its level, since another one would analyse its whole history again
        Db.Info phone = r.history ? null : Db.inspect(c.getDatabasePath(Db.REAL), false);
        // a phone history that cannot be read counts as one with nights, so its level stays
        Prefs prefs = Prefs.of(c);
        applySettings(prefs, p.key, p.o, phone == null || !phone.exists || phone.ok && phone.nights == 0);
        // the key is saved: a Setup screen closed before showing the result must not start over. A backup without a key
        // (issue #288) leaves a phone without one in Setup, where the key is entered
        if (prefs.hasKey()) prefs.setSetupDone(true);
        r.ok = true;
        r.noKey = p.noKey;
        String what = p.noKey ? "settings" : "key and settings";
        if (r.history) r.message = "Restored your " + (p.noKey ? "settings and history" : "key, settings and history") + (r.logDamaged ? ". " + PERIODS_DAMAGED : "");
        else if (p.history) r.message = "Restored your " + what + ", the history on this phone stays";
        else r.message = "Restored your " + what;
        if (p.noKey) r.message += ". " + (prefs.loadKey() != null ? NO_KEY_KEPT : NO_KEY_ENTER);
        return r;
    }

    /** The gate's reason to wait as a line for {@code p}: a ring task, or an analysis (issue #219). */
    static app.openring.core.RingGate.Wait waitLine(final Progress p, final boolean[] waited) {
        if (p == null) return null;
        return new app.openring.core.RingGate.Wait() {
            @Override
            public void waiting(int why) {
                waited[0] = true;
                p.step(app.openring.core.RingGate.waitLine(why));
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

    static File newTemp(Context c) throws IOException {
        File f = File.createTempFile("restore-", ".db", c.getCacheDir());
        synchronized (LIVE) {
            LIVE.add(f.getName());
        }
        return f;
    }

    static void release(File f) {
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
        // without a key in the backup (issue #288, or going back to a phone copy) the phone's key, readable or not,
        // stays exactly as it is, and so does the ring it belongs to: a ring's identity only comes along with its key
        boolean ring = key != null || !p.hasKey();
        if (key != null) p.saveKey(key);
        if (o == null) return;
        if (ring) {
            if (!o.isNull("address") && o.optString("address").length() > 0) p.setAddress(o.optString("address"));
            if (!o.isNull("serial")) p.setSerial(o.optString("serial", null));
            if (!o.isNull("hardware")) p.setHardware(o.optString("hardware", null));
            if (!o.isNull("firmware")) p.setFirmware(o.optString("firmware", null));
        }
        JSONObject prof = o.optJSONObject("profile");
        if (prof != null) {
            int sex = prof.optInt("sex", p.sex());
            p.setProfile(InputLimit.AGE.clamp(prof.optInt("age", p.age())), sex >= 0 && sex <= 2 ? sex : p.sex(),
                    InputLimit.WEIGHT_KG.clamp(prof.optInt("weight", p.weightKg())), InputLimit.HEIGHT_CM.clamp(prof.optInt("height", p.heightCm())),
                    InputLimit.TARGET_KCAL.clamp(prof.optInt("target_kcal", p.targetKcal())));
            p.setProfileSet();
        }
        if (o.has("background_sync")) p.setBackgroundSync(o.optBoolean("background_sync", true));
        if (o.has("screen_goal")) p.setScreenGoalMin(InputLimit.SCREEN_GOAL_MIN.clamp(o.optInt("screen_goal", p.screenGoalMin())));
        if (o.has("screen_awake")) p.setScreenAwake(o.optBoolean("screen_awake", true));
        org.json.JSONArray ex = o.optJSONArray("screen_exclude");
        if (ex != null) {
            java.util.List<String> l = new java.util.ArrayList<String>();
            for (int i = 0; i < ex.length(); i++) l.add(ex.optString(i, ""));
            p.setScreenExcluded(l);
        }
        if (o.has("games_on")) p.setGamesOn(o.optBoolean("games_on", false));
        // the period tracker switch (issue #252); an older backup has none and leaves it as it is
        if (o.has("period_shown")) p.setPeriodChoice(o.optInt("period_shown", -1));
        // the badges switch (issue #265); an older backup has none and leaves it as it is
        if (o.has("badges_on")) p.setBadgesOn(o.optBoolean("badges_on", true));
        // phase 1a2: a value that is not one of the choices keeps the current one; an older backup has none of these
        if (app.openring.core.PeriodRemind.validDays(o.optInt("period_remind_days", -1))) p.setPeriodRemindDays(o.optInt("period_remind_days"));
        int remindMin = o.optInt("period_remind_min", -1);
        if (remindMin >= 0 && remindMin < 24 * 60) p.setPeriodRemindMinute(remindMin);
        // a backup that has the reminder but no "stopped" day was made while it was not said: it is not said after the restore either
        if (o.has("period_remind_days")) {
            p.setPeriodStoppedReal(o.isNull("period_stopped") ? null : o.optString("period_stopped", null));
            p.setPeriodAskLaterReal(o.isNull("period_ask_later") ? null : o.optString("period_ask_later", null));
        }
        if (o.has("screen_min_show")) {
            int m = o.optInt("screen_min_show", 5);
            p.setScreenMinShowMin(m == 0 || m == 5 || m == 10 || m == 15 ? m : 5);
        }
        if (stageDetail && o.has("stage_detail")) {
            int d = o.optInt("stage_detail", -1);
            if (d >= StageFilter.DETAILED && d <= StageFilter.CALM) p.setStageDetail(d);
        }
        if (o.has("finder_sound")) p.setFinderSound(o.optBoolean("finder_sound", true));
        // a value that is not one of the choices, or an older backup without them, keeps the current ones
        if (app.openring.core.Backups.validDays(o.optInt("backup_days", -1))) p.setBackupDays(o.optInt("backup_days"));
        if (app.openring.core.Backups.validKeep(o.optInt("backup_keep", -1))) p.setBackupKeep(o.optInt("backup_keep"));
        // the watch (issue #251); an older backup has none and leaves the phone's as it is
        if (!o.isNull("watch_address") && o.optString("watch_address").length() > 0) p.setWatch(o.optString("watch_address"), o.isNull("watch_name") ? null : o.optString("watch_name", null));
        if (app.openring.core.withings.WatchDue.valid(o.optInt("watch_per_day", -1))) p.setWatchPerDay(o.optInt("watch_per_day"));
        // the main device (one set of numbers): an older backup has none and keeps the phone's
        int main = o.optInt("main_device", -1);
        if (main == app.openring.core.withings.MainDevice.RING || main == app.openring.core.withings.MainDevice.WATCH) p.setMainDevice(main);
        // the watch's setup choices: an older backup has none and keeps the phone's; a restored alarm is sent at the next sync
        int goal = o.optInt("watch_goal", -1);
        if (goal >= 1000 && goal <= 50000) p.setWatchGoal(goal);
        if (o.has("watch_metric")) p.setWatchMetric(o.optBoolean("watch_metric", true));
        if (o.has("watch_alarm_on")) {
            app.openring.core.withings.WatchSetup.Alarm al = new app.openring.core.withings.WatchSetup.Alarm();
            al.on = o.optBoolean("watch_alarm_on", false);
            al.hour = o.optInt("watch_alarm_h", 7);
            al.minute = o.optInt("watch_alarm_m", 0);
            al.days = o.optInt("watch_alarm_days", 0x1f);
            al.smart = o.optInt("watch_alarm_smart", 0);
            p.setWatchAlarm(al);
        }
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
