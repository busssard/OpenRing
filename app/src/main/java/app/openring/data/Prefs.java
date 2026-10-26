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
    public int age() { return sp.getInt("age", 35); }
    public int sex() { return sp.getInt("sex", 2); } // 0 male, 1 female, 2 unspecified
    public int weightKg() { return sp.getInt("weight", 72); }
    public int heightCm() { return sp.getInt("height", 175); }
    /** Whether a height was ever entered (the finder's step length says so when it uses 175 cm). */
    public boolean hasHeight() { return sp.contains("height"); }
    /** Active calorie target; 0 means automatic. */
    public int targetKcal() { return sp.getInt("target_kcal", 0); }

    public void setProfile(int age, int sex, int weight, int height, int target) {
        sp.edit().putInt("age", age).putInt("sex", sex).putInt("weight", weight).putInt("height", height).putInt("target_kcal", target).apply();
    }

    /** Sleep stage detail level, see {@code StageFilter} (0 detailed, 1 balanced, 2 calm). */
    public int stageDetail() { return sp.getInt("stage_detail", app.openring.core.analysis.StageFilter.BALANCED); }
    public void setStageDetail(int level) { sp.edit().putInt("stage_detail", level).apply(); }

    /** Daily screen time goal in minutes, 0 for none (issue #13). */
    public int screenGoalMin() { return sp.getInt("screen_goal", 180); }
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
    public boolean demo() { return sp.getBoolean("demo", false); }
    public void setDemo(boolean b) { sp.edit().putBoolean("demo", b).commit(); }

    public boolean setupDone() { return sp.getBoolean("setup_done", false); }
    public void setSetupDone(boolean b) { sp.edit().putBoolean("setup_done", b).apply(); }

    // ---- auth key ----
    public boolean hasKey() { return sp.contains("key_enc"); }

    public void saveKey(byte[] key16) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] enc = c.doFinal(key16);
            sp.edit().putString("key_enc", Base64.encodeToString(enc, Base64.NO_WRAP))
                    .putString("key_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            // Keystore unavailable (rare): fall back to app private storage
            sp.edit().putString("key_enc", "plain:" + Hex.encode(key16)).remove("key_iv").apply();
        }
    }

    public byte[] loadKey() {
        String enc = sp.getString("key_enc", null);
        if (enc == null) return null;
        if (enc.startsWith("plain:")) return Hex.parseKey(enc.substring(6));
        try {
            byte[] iv = Base64.decode(sp.getString("key_iv", ""), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, iv));
            return c.doFinal(Base64.decode(enc, Base64.NO_WRAP));
        } catch (Exception e) {
            return null;
        }
    }

    public void clearKey() {
        sp.edit().remove("key_enc").remove("key_iv").apply();
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
