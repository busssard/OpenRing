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
     * Restore from an Open Ring backup. Also accepts a plain text file that only holds the 32
     * character key (as people often save it in a note).
     */
    public static Result read(Context c, InputStream raw) throws IOException {
        Result r = new Result();
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(4);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        String json = null;
        File dbTmp = null;
        if (b0 == 'P' && b1 == 'K') {
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (JSON.equals(name)) {
                    json = new String(readAll(z, 1 << 20), StandardCharsets.UTF_8);
                } else if (DB.equals(name)) {
                    dbTmp = new File(c.getCacheDir(), "restore.db");
                    OutputStream out = new FileOutputStream(dbTmp);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        out.close();
                    }
                }
            }
        } else {
            json = new String(readAll(in, 1 << 20), StandardCharsets.UTF_8).trim();
        }
        if (json == null) {
            r.message = "This file is not an Open Ring backup.";
            return r;
        }
        JSONObject o = json.startsWith("{") ? Json.parse(json) : null;
        byte[] key = Hex.parseKey(o != null ? o.optString("key", "") : json);
        if (key == null) {
            r.message = o == null ? "No 32 character key found in this file." : "The backup holds no valid ring key.";
            return r;
        }
        if (dbTmp != null) {
            if (!looksLikeSqlite(dbTmp)) {
                dbTmp.delete();
                r.message = "The history inside this backup is damaged.";
                return r;
            }
            Prefs.of(c).setDemo(false);
            Db.replaceWith(c, dbTmp);
            dbTmp.delete();
            r.history = true;
        }
        Prefs p = Prefs.of(c);
        p.setDemo(false);
        p.saveKey(key);
        if (o != null) {
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
        }
        r.ok = true;
        r.message = r.history ? "Restored your key, settings and history" : "Restored your key and settings";
        return r;
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
