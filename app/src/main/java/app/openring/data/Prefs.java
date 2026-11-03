package app.openring.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import app.openring.core.Hex;
import app.openring.core.InputLimit;

/**
 * Settings and the ring auth key. The key is encrypted with an AES key that lives in the Android
 * Keystore, so it never sits in plain text on disk.
 */
public final class Prefs {
    private static final String ALIAS = "openring_ring_key";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("openring", Context.MODE_PRIVATE);
    }

    public static Prefs of(Context c) {
        return new Prefs(c);
    }

    // ---- profile ----
    public int age() { return sp.getInt("age", InputLimit.AGE.def); }
    public int sex() { return sp.getInt("sex", 2); } // 0 male, 1 female, 2 unspecified
    public int weightKg() { return sp.getInt("weight", InputLimit.WEIGHT_KG.def); }
    public int heightCm() { return sp.getInt("height", InputLimit.HEIGHT_CM.def); }
    /** Whether a height was ever entered (the finder's step length says so when it uses 175 cm). */
    public boolean hasHeight() { return sp.contains("height"); }
    /** Active calorie target; 0 means automatic. */
    public int targetKcal() { return sp.getInt("target_kcal", InputLimit.TARGET_KCAL.def); }

    public void setProfile(int age, int sex, int weight, int height, int target) {
        sp.edit().putInt("age", age).putInt("sex", sex).putInt("weight", weight).putInt("height", height).putInt("target_kcal", target).apply();
    }

    /** Sleep stage detail level, see {@code StageFilter} (0 detailed, 1 balanced, 2 calm). */
    public int stageDetail() { return sp.getInt("stage_detail", app.openring.core.analysis.StageFilter.BALANCED); }
    public void setStageDetail(int level) { sp.edit().putInt("stage_detail", level).apply(); }

    /** Daily screen time goal in minutes, 0 for none (issue #13). */
    public int screenGoalMin() { return sp.getInt("screen_goal", InputLimit.SCREEN_GOAL_MIN.def); }
    public void setScreenGoalMin(int m) { sp.edit().putInt("screen_goal", m).apply(); }
    /** Whether time with the phone screen on counts as awake in the sleep analysis (issue #13). */
    public boolean screenAwake() { return sp.getBoolean("screen_awake", true); }
    public void setScreenAwake(boolean b) { sp.edit().putBoolean("screen_awake", b).apply(); }
    /**
     * Apps whose time in front does not count as screen time (package names). The demo keeps its own
     * list, with its made up maps app left out, so demo and real settings never mix.
     */
    public java.util.Set<String> screenExcluded() {
        String v = demo() ? sp.getString("screen_exclude_demo", Demo.MAPS) : sp.getString("screen_exclude", "");
        java.util.Set<String> out = new java.util.TreeSet<String>();
        for (String x : v.split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }
    public void setScreenExcluded(java.util.Collection<String> pkgs) {
        StringBuilder b = new StringBuilder();
        for (String x : new java.util.TreeSet<String>(pkgs)) {
            if (x == null || x.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(x.trim());
        }
        sp.edit().putString(demo() ? "screen_exclude_demo" : "screen_exclude", b.toString()).apply();
    }
    /** Minutes each phone use shows as at least on the Screen tab's clock and day rows (not in totals). */
    public int screenMinShowMin() { return sp.getInt("screen_min_show", 5); }
    public void setScreenMinShowMin(int m) { sp.edit().putInt("screen_min_show", m).apply(); }
    /** Ring games show in Settings (issue #15); the motion test is always there. */
    public boolean gamesOn() { return sp.getBoolean("games_on", false); }
    public void setGamesOn(boolean b) { sp.edit().putBoolean("games_on", b).apply(); }

    /**
     * The Settings switch "Show the period tracker" (issue #252): -1 not touched (shown when the profile says female), 0 hidden,
     * 1 shown. Hiding deletes nothing. {@code core/analysis/Cycles.shown} decides with the profile and demo mode.
     */
    public int periodChoice() { return sp.getInt("period_shown", -1); }
    public void setPeriodChoice(int v) { sp.edit().putInt("period_shown", v < 0 ? -1 : v > 0 ? 1 : 0).apply(); }

    /**
     * "Remind me before my period" (issue #252, phase 1a2): days before the expected range ({@code core/PeriodRemind.DAYS},
     * 0 off, the default) and the time of day in minutes after midnight (9:00 by default). Anything else is stored as off
     * or 9:00. The reminder always comes from the person's own log, never from the demo's.
     */
    public int periodRemindDays() {
        int d = sp.getInt("period_remind_days", 0);
        return app.openring.core.PeriodRemind.validDays(d) ? d : 0;
    }
    public void setPeriodRemindDays(int d) { sp.edit().putInt("period_remind_days", app.openring.core.PeriodRemind.validDays(d) ? d : 0).apply(); }
    public int periodRemindMinute() { return app.openring.core.PeriodRemind.minute(sp.getInt("period_remind_min", app.openring.core.PeriodRemind.DEFAULT_MINUTE)); }
    public void setPeriodRemindMinute(int m) { sp.edit().putInt("period_remind_min", app.openring.core.PeriodRemind.minute(m)).apply(); }
    /** The cycle already reminded of (the start of its period), so one cycle never gets two; not in a backup. */
    public String periodRemindSent() { return sp.getString("period_remind_sent", null); }
    /** Written to disk before it returns, before the notification is posted, so a stop in between never sends twice. */
    public void setPeriodRemindSent(String key) { sp.edit().putString("period_remind_sent", key).commit(); }

    /**
     * "My periods have stopped": the day it was said (yyyy-mm-dd), null when not. Demo mode keeps its own, so the demo
     * never changes the person's. {@code core/analysis/Cycles} ends it by itself once a period is logged on that day or later.
     */
    public String periodStopped() { return sp.getString(demo() ? "period_stopped_demo" : "period_stopped", null); }
    /** The person's own, also while demo mode is on (the reminder and the backup use it). */
    public String periodStoppedReal() { return sp.getString("period_stopped", null); }
    public void setPeriodStopped(String day) { setDayKey(demo() ? "period_stopped_demo" : "period_stopped", day); }
    void setPeriodStoppedReal(String day) { setDayKey("period_stopped", day); }
    private void setDayKey(String key, String day) { // a date, or (null or not a date) none
        if (app.openring.core.analysis.Cycles.day(day) == null) sp.edit().remove(key).apply();
        else sp.edit().putString(key, day).apply();
    }
    /** When the person answered "Not yet" to "Have your periods stopped?" (yyyy-mm-dd), null when never; demo keeps its own. */
    public String periodAskLater() { return sp.getString(demo() ? "period_ask_later_demo" : "period_ask_later", null); }
    public String periodAskLaterReal() { return sp.getString("period_ask_later", null); }
    public void setPeriodAskLater(String day) { setDayKey(demo() ? "period_ask_later_demo" : "period_ask_later", day); }
    void setPeriodAskLaterReal(String day) { setDayKey("period_ask_later", day); }

    public boolean profileSet() { return sp.getBoolean("profile_set", false); }
    public void setProfileSet() { sp.edit().putBoolean("profile_set", true).apply(); }

    // ---- ring ----
    public String address() { return sp.getString("address", null); }
    public void setAddress(String a) { sp.edit().putString("address", a).apply(); }
    public String serial() { return sp.getString("serial", null); }
    public void setSerial(String s) { sp.edit().putString("serial", s).apply(); }
    public String firmware() { return sp.getString("firmware", null); }
    public void setFirmware(String s) { sp.edit().putString("firmware", s).apply(); }
    public String hardware() { return sp.getString("hardware", null); }
    public void setHardware(String s) { sp.edit().putString("hardware", s).apply(); }
    public int battery() { return sp.getInt("battery", -1); }
    public long batteryAt() { return sp.getLong("battery_at", 0); }
    /**
     * Saves a battery read. The read before is kept when it is at least 10 minutes older, so the
     * ring finder can tell whether the ring was charging in between (issue #74).
     */
    public void setBattery(int pct, long at) {
        SharedPreferences.Editor e = sp.edit();
        long before = batteryAt();
        if (before > 0 && at - before >= 600) e.putInt("battery_prev", battery()).putLong("battery_prev_at", before);
        e.putInt("battery", pct).putLong("battery_at", at).apply();
    }
    public int batteryBefore() { return sp.getInt("battery_prev", -1); }
    public long batteryBeforeAt() { return sp.getLong("battery_prev_at", 0); }
    // ---- measurement modes to put back on the ring (issue #221) ----

    /**
     * The mode to put {@code feature} back to after Live or the finder's blink changed it, or
     * {@code core/ModeRestore.NONE}. Only these Prefs; no ring data is touched.
     */
    public int pendingMode(int feature) { return sp.getInt("restore_mode_" + feature, app.openring.core.ModeRestore.NONE); }

    /** Written to disk before it returns ({@code commit}), so it outlives the app being ended right after. */
    public void setPendingMode(int feature, int mode) {
        if (mode < 0) sp.edit().remove("restore_mode_" + feature).commit();
        else sp.edit().putInt("restore_mode_" + feature, mode).commit();
    }

    /** "Use a different key or ring": the modes kept for the ring as it was no longer apply. */
    public void forgetPendingModes() {
        SharedPreferences.Editor e = sp.edit();
        for (int f : app.openring.core.ModeRestore.FEATURES) e.remove("restore_mode_" + f);
        e.commit();
    }

    /** The ring finder clicks and vibrates faster when closer; this switches both (issue #74). */
    public boolean finderSound() { return sp.getBoolean("finder_sound", true); }
    public void setFinderSound(boolean b) { sp.edit().putBoolean("finder_sound", b).apply(); }
    /**
     * Whether the person explicitly refused the Bluetooth permission at least once (Android offered its
     * explanation): only then does a later refusal without one mean Android no longer asks (issue #140).
     */
    public boolean permissionDenied() { return sp.getBoolean("perm_denied", false); }
    public void setPermissionDenied(boolean b) { sp.edit().putBoolean("perm_denied", b).apply(); }
    /** When this phone last heard the ring (a scan saw it or a connection opened), unix seconds, 0 never. */
    public long lastHeard() { return sp.getLong("last_heard", 0); }
    public void setLastHeard(long t) { sp.edit().putLong("last_heard", t).apply(); }
    public long lastSync() { return sp.getLong("last_sync", 0); }
    public long lastAttempt() { return sp.getLong("last_attempt", 0); }
    public void setLastAttempt(long t) { sp.edit().putLong("last_attempt", t).apply(); }
    public void setLastSync(long t) { sp.edit().putLong("last_sync", t).apply(); }
    public String lastSyncError() { return sp.getString("last_sync_error", null); }
    public void setLastSyncError(String e) { sp.edit().putString("last_sync_error", e).apply(); }
    public boolean backgroundSync() { return sp.getBoolean("bg_sync", true); }
    public void setBackgroundSync(boolean b) { sp.edit().putBoolean("bg_sync", b).apply(); }
    /** Demo mode: the app shows a made up history from its own database file. */
    // ---- the watch (issue #251, phase 1b) ----
    /** The watch this app syncs (its Bluetooth address), null for none. */
    public String watchAddress() { return sp.getString("watch_address", null); }
    public String watchName() { return sp.getString("watch_name", null); }
    public void setWatch(String address, String name) {
        sp.edit().putString("watch_address", address).putString("watch_name", name).remove("watch_error").apply();
    }
    /** Forget the watch: syncing stops, its stored data stays. */
    public void forgetWatch() { sp.edit().remove("watch_address").remove("watch_name").remove("watch_error").apply(); }
    /** Syncs a day in the background: 0 by hand, 1, 2 or 4 ({@code core/withings/WatchDue}). */
    public int watchPerDay() { return sp.getInt("watch_per_day", app.openring.core.withings.WatchDue.DEFAULT); }
    public void setWatchPerDay(int n) {
        if (app.openring.core.withings.WatchDue.valid(n)) sp.edit().putInt("watch_per_day", n).apply();
    }
    public long watchLastOk() { return sp.getLong("watch_last_ok", 0); }
    public long watchLastAttempt() { return sp.getLong("watch_last_try", 0); }
    /** The plain sentence of the last watch sync that did not finish, null after one that did. */
    public String watchError() { return sp.getString("watch_error", null); }
    public void setWatchResult(long attempt, boolean ok, String error) {
        SharedPreferences.Editor e = sp.edit().putLong("watch_last_try", attempt);
        if (ok) e.putLong("watch_last_ok", attempt);
        if (error == null) e.remove("watch_error");
        else e.putString("watch_error", error);
        e.apply();
    }

    public boolean demo() { return sp.getBoolean("demo", false); }
    public void setDemo(boolean b) { sp.edit().putBoolean("demo", b).commit(); }

    // ---- automatic backups (issue #242) ----
    /** The version code noted at the last start, 0 when none was noted yet. */
    public int notedVersion() { return sp.getInt("version_noted", 0); }
    /** Notes this version, written to disk before it returns: the copy before the update is complete (or not possible). */
    public boolean noteVersion(int code) { return sp.edit().putInt("version_noted", code).commit(); }
    /** Why no copy was made before the last update ({@code core/Backups.SPACE} or an error text), null when it was made. */
    public String beforeUpdateNote() { return sp.getString("before_note", null); }
    /** Whether the main screen still shows that no copy was made (until "OK"). */
    public boolean beforeUpdateUnseen() { return sp.getBoolean("before_unseen", false); }
    public void setBeforeUpdateNote(String note, boolean unseen) { sp.edit().putString("before_note", note).putBoolean("before_unseen", unseen).commit(); }
    public void clearBeforeUpdateUnseen() { sp.edit().putBoolean("before_unseen", false).apply(); }
    /** The newest update copy (its version code) that already went to the folder, 0 for none. */
    public int updateCopySent() { return sp.getInt("update_sent", 0); }
    public void setUpdateCopySent(int code) { sp.edit().putInt("update_sent", code).commit(); }
    /** The folder for the automatic backups (a tree URI with a kept permission), null when they are off. */
    public String backupFolder() { return sp.getString("backup_folder", null); }
    public String backupFolderName() { return sp.getString("backup_folder_name", null); }
    /**
     * Sets or (null) clears the folder. The list of files the app wrote belongs to the folder it wrote them to
     * ({@code auto_files_folder}): it stays when the backups are stopped and when the same folder is chosen again, so
     * the copies already there still count and the oldest of them still go (review of #259); another folder starts with
     * an empty list. The status starts afresh, except when the same folder is chosen again while it is set (only the
     * reason of the last failure goes: its permission was just given again).
     */
    public void setBackupFolder(String uri, String name) {
        SharedPreferences.Editor e = sp.edit();
        boolean same = uri != null && uri.equals(backupFolder());
        if (uri == null) e.remove("backup_folder").remove("backup_folder_name");
        else e.putString("backup_folder", uri).putString("backup_folder_name", name);
        e.remove("auto_error");
        if (!same) e.remove("auto_last_ok").remove("auto_last_size").remove("auto_kept").remove("auto_last_try").remove("auto_last_regular");
        if (uri != null && !uri.equals(sp.getString("auto_files_folder", null))) e.putString("auto_files_folder", uri).remove("auto_files");
        e.commit();
    }
    /** A copy every so many days (1, 7 or 30). */
    public int backupDays() { return sp.getInt("backup_days", app.openring.core.Backups.DEFAULT_DAYS); }
    public void setBackupDays(int d) { sp.edit().putInt("backup_days", d).apply(); }
    /** How many copies the folder keeps (3, 5 or 10). */
    public int backupKeep() { return sp.getInt("backup_keep", app.openring.core.Backups.DEFAULT_KEEP); }
    public void setBackupKeep(int k) { sp.edit().putInt("backup_keep", k).apply(); }
    /** The last good folder copy: when (unix seconds, 0 none), its size, how many copies the folder kept after it. */
    public long autoLastOk() { return sp.getLong("auto_last_ok", 0); }
    public long autoLastSize() { return sp.getLong("auto_last_size", 0); }
    public int autoKept() { return sp.getInt("auto_kept", 0); }
    /** The last good regular copy (not an update copy), which the schedule counts from. */
    public long autoLastRegular() { return sp.getLong("auto_last_regular", 0); }
    public long autoLastTry() { return sp.getLong("auto_last_try", 0); }
    /** Why the last try failed ({@code core/Backups} reason code), null when it worked. */
    public String autoError() { return sp.getString("auto_error", null); }
    public void setAutoResult(long tried, String error) {
        SharedPreferences.Editor e = sp.edit().putLong("auto_last_try", tried);
        if (error == null) e.remove("auto_error");
        else e.putString("auto_error", error);
        e.commit();
    }
    public void setAutoOk(long at, long size, int kept, boolean regular) {
        SharedPreferences.Editor e = sp.edit().putLong("auto_last_try", at).remove("auto_error").putLong("auto_last_ok", at).putLong("auto_last_size", size)
                .putInt("auto_kept", kept);
        if (regular) e.putLong("auto_last_regular", at);
        e.commit();
    }
    /** The folder files this app wrote (names, also of a file still being written), so it never deletes other files. */
    public java.util.Set<String> autoFiles() {
        java.util.Set<String> out = new java.util.TreeSet<String>();
        for (String x : sp.getString("auto_files", "").split("/")) if (!x.isEmpty()) out.add(x);
        return out;
    }
    /** Saved to disk before it returns, so a file is listed before it is created. */
    public boolean setAutoFiles(java.util.Collection<String> names) {
        StringBuilder b = new StringBuilder();
        for (String x : new java.util.TreeSet<String>(names)) {
            if (x == null || x.isEmpty() || x.contains("/")) continue;
            if (b.length() > 0) b.append('/');
            b.append(x);
        }
        return sp.edit().putString("auto_files", b.toString()).commit();
    }

    public boolean setupDone() { return sp.getBoolean("setup_done", false); }
    public void setSetupDone(boolean b) { sp.edit().putBoolean("setup_done", b).apply(); }

    // ---- auth key ----
    /** Whether a key is stored, also one this phone cannot read ({@link #keyState}, issue #199). */
    public boolean hasKey() { return sp.contains("key_enc"); }

    /**
     * {@code core/KeyState}: no key, a key this phone can read, or a stored key it cannot read (after a phone to phone
     * transfer the Keystore lock stays behind, issue #199). Reads the key once; never changes what is stored.
     */
    public int keyState() {
        return app.openring.core.KeyState.of(hasKey(), loadKey() != null);
    }

    /** Why the last stored key could not be read (exception and message, for the Bluetooth log), null when none failed. */
    public static String keyError() { return keyError; }
    private static volatile String keyError;

    public void saveKey(byte[] key16) {
        String[] e = encrypt(key16);
        put(sp.edit(), "key_enc", "key_iv", e).apply();
    }

    public byte[] loadKey() {
        return decrypt("key_enc", "key_iv");
    }

    /** Also drops the note that the key page was not shown yet (issue #218). */
    public void clearKey() {
        sp.edit().remove("key_enc").remove("key_iv").remove("pair_unseen").apply();
    }

    // ---- pairing (issue #218) ----

    /**
     * Keeps a key about to be written to the ring, encrypted like the saved key, beside it (the saved key stays as it
     * is). Written to disk before it returns ({@code commit}); false when that failed, then nothing goes to the ring.
     */
    public boolean keepPendingKey(byte[] key16, String serial) {
        SharedPreferences.Editor e = put(sp.edit(), "pending_key_enc", "pending_key_iv", encrypt(key16));
        if (serial == null) e.remove("pending_key_serial");
        else e.putString("pending_key_serial", serial);
        return e.commit();
    }

    /** The serial of the ring the kept key was meant for, or null. */
    public String pendingKeySerial() { return sp.getString("pending_key_serial", null); }

    /** The key kept from a pairing that did not finish, or null. */
    public byte[] pendingKey() {
        return decrypt("pending_key_enc", "pending_key_iv");
    }

    public void forgetPendingKey() {
        sp.edit().remove("pending_key_enc").remove("pending_key_iv").remove("pending_key_serial").commit();
    }

    /**
     * A pairing the ring accepted, in one write to disk: the key, the ring's address and serial, set up, and the note
     * that the key page was not shown yet; the kept key goes with {@code dropPending} (it was meant for this ring).
     * False when the write failed.
     */
    public boolean pairDone(byte[] key16, String address, String serial, String message, boolean dropPending) {
        SharedPreferences.Editor e = put(sp.edit(), "key_enc", "key_iv", encrypt(key16));
        if (address != null) e.putString("address", address);
        e.putString("serial", serial).putBoolean("setup_done", true).putString("pair_unseen", message);
        if (dropPending) e.remove("pending_key_enc").remove("pending_key_iv").remove("pending_key_serial");
        return e.commit();
    }

    /** The message of a pairing whose key page the person has not left yet, or null. */
    public String pairUnseen() { return sp.getString("pair_unseen", null); }

    public void clearPairUnseen() { sp.edit().remove("pair_unseen").commit(); }

    /** {enc, iv}: the key encrypted with the Keystore key, or {"plain:hex", null} when the Keystore is unavailable. */
    private static String[] encrypt(byte[] key16) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] enc = c.doFinal(key16);
            return new String[]{Base64.encodeToString(enc, Base64.NO_WRAP), Base64.encodeToString(c.getIV(), Base64.NO_WRAP)};
        } catch (Exception e) {
            // Keystore unavailable (rare): fall back to app private storage
            return new String[]{"plain:" + Hex.encode(key16), null};
        }
    }

    private static SharedPreferences.Editor put(SharedPreferences.Editor e, String encName, String ivName, String[] v) {
        e.putString(encName, v[0]);
        if (v[1] == null) e.remove(ivName);
        else e.putString(ivName, v[1]);
        return e;
    }

    private byte[] decrypt(String encName, String ivName) {
        String enc = sp.getString(encName, null);
        if (enc == null) return null;
        if (enc.startsWith("plain:")) return Hex.parseKey(enc.substring(6));
        try {
            byte[] iv = Base64.decode(sp.getString(ivName, ""), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, iv));
            return c.doFinal(Base64.decode(enc, Base64.NO_WRAP));
        } catch (Exception e) {
            // the stored value stays as it is: a lock that fails only for a while reads it again later (issue #199)
            if ("key_enc".equals(encName)) keyError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return null;
        }
    }

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }
}
