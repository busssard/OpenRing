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
    /** Active calorie target; 0 means automatic. */
    public int targetKcal() { return sp.getInt("target_kcal", 0); }

    public void setProfile(int age, int sex, int weight, int height, int target) {
        sp.edit().putInt("age", age).putInt("sex", sex).putInt("weight", weight).putInt("height", height).putInt("target_kcal", target).apply();
    }

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
    public void setBattery(int pct, long at) { sp.edit().putInt("battery", pct).putLong("battery_at", at).apply(); }
    public long lastSync() { return sp.getLong("last_sync", 0); }
    public long lastAttempt() { return sp.getLong("last_attempt", 0); }
    public void setLastAttempt(long t) { sp.edit().putLong("last_attempt", t).apply(); }
    public void setLastSync(long t) { sp.edit().putLong("last_sync", t).apply(); }
    public String lastSyncError() { return sp.getString("last_sync_error", null); }
    public void setLastSyncError(String e) { sp.edit().putString("last_sync_error", e).apply(); }
    public boolean backgroundSync() { return sp.getBoolean("bg_sync", true); }
    public void setBackgroundSync(boolean b) { sp.edit().putBoolean("bg_sync", b).apply(); }
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
