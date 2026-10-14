import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import app.openring.core.EventCodec;
import app.openring.core.Protocol;
import app.openring.core.RingEvent;
import app.openring.data.Analyzer;
import app.openring.data.DataCheck;
import app.openring.data.Db;
import app.openring.data.Json;

/**
 * End to end on the JVM: a synthetic three day ring history, encoded byte for byte the way the
 * ring sends it, goes through the app's real storage, dating and analysis code (Android classes
 * are replaced by small JDBC backed stand ins). Covers the field failure: a first sync that is
 * interrupted before the drain reaches the present.
 */
public class PipelineTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object info) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (info == null ? "" : "  (" + info + ")"));
    }

    static final ZoneId ZONE = ZoneId.of("Europe/Zurich");
    static final Random rnd = new Random(42);

    /** Ring ds counter: boots 9 days before day 0 (ds counts deciseconds since boot). */
    static long T0;
    static final long DS0 = 9L * 86400 * 10;

    static long ds(double unix) {
        return DS0 + Math.round((unix - T0) * 10);
    }

    static byte[] le16(int... v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            b[2 * i] = (byte) v[i];
            b[2 * i + 1] = (byte) (v[i] >> 8);
        }
        return b;
    }

    static List<RingEvent> history(int days) {
        List<RingEvent> ev = new ArrayList<RingEvent>();
        for (int d = 0; d < days; d++) {
            double day = T0 + d * 86400.0; // local midnight
            double bed = day - 1.25 * 3600 + rnd.nextGaussian() * 900; // ~22:45 the evening before
            double wake = day + 6.75 * 3600 + rnd.nextGaussian() * 900; // ~06:45
            // ---- night streams ----
            for (double t = bed; t < wake; t += 300) {
                double f = (t - bed) / (wake - bed);
                int hr = (int) Math.round(60 - 9 * Math.sin(Math.PI * Math.min(1, f * 1.6)) + rnd.nextGaussian());
                int state = (f < 0.04 || f > 0.98) ? 0 : 1;
                int motion = state == 0 ? 20 : (rnd.nextDouble() < 0.05 ? 6 : 0);
                byte[] spi = new byte[10];
                spi[0] = (byte) (hr * 2);
                spi[4] = (byte) Math.round((14 + rnd.nextGaussian() * 0.5) * 8);
                spi[6] = (byte) motion;
                spi[7] = (byte) state;
                spi[8] = 0x00;
                spi[9] = 0x10;
                ev.add(new RingEvent(EventCodec.SLEEP_PERIOD_INFO_2, ds(t), spi));
            }
            for (double t = bed; t < wake; t += 1800) {
                byte[] hrv = new byte[12];
                for (int k = 0; k < 6; k++) {
                    double tt = t + k * 300, f = (tt - bed) / (wake - bed);
                    hrv[2 * k] = (byte) Math.round(60 - 9 * Math.sin(Math.PI * Math.min(1, f * 1.6)));
                    hrv[2 * k + 1] = (byte) Math.round(45 + 25 * f + rnd.nextGaussian() * 4);
                }
                ev.add(new RingEvent(EventCodec.HRV_EVENT, ds(t), hrv));
            }
            for (double t = bed; t < wake; t += 60) {
                double f = (t - bed) / (wake - bed);
                boolean awake = f < 0.04 || f > 0.98;
                int ms = awake ? 12 : (rnd.nextDouble() < 0.03 ? 5 : 0);
                ev.add(new RingEvent(EventCodec.MOTION_EVENT, ds(t), new byte[] {(byte) ms, 1, 2, 3}));
                ev.add(new RingEvent(EventCodec.SLEEP_TEMP_EVENT, ds(t), le16((int) Math.round((35.3 + rnd.nextGaussian() * 0.04) * 100))));
                ev.add(new RingEvent(EventCodec.SPO2_EVENT, ds(t), new byte[] {0, 96, 97, 96, 95, 97}));
            }
            // the ring declares the night when it wakes
            byte[] bt = new byte[8];
            Protocol.Le.putU32(bt, 0, ds(bed));
            Protocol.Le.putU32(bt, 4, ds(wake));
            ev.add(new RingEvent(EventCodec.BEDTIME_PERIOD, ds(wake + 600), bt));
            // ---- daytime: MET per minute in 10 minute events, quality gated beats ----
            for (double t = wake; t < day + 22.5 * 3600; t += 600) {
                double hour = (t - day) / 3600;
                byte[] met = new byte[11];
                met[0] = 1;
                for (int k = 1; k <= 10; k++) {
                    double m = (hour > 17.5 && hour < 18.5) ? 6.5 : (hour > 12 && hour < 12.5 ? 3.5 : 1.3 + rnd.nextDouble() * 0.6);
                    met[k] = (byte) Math.round(m * 10);
                }
                ev.add(new RingEvent(EventCodec.ACTIVITY_INFO, ds(t), met));
                byte[] ibi = new byte[4];
                int i1 = 800 + rnd.nextInt(80);
                ibi[0] = (byte) (i1 >> 3);
                ibi[1] = (byte) ((i1 & 7) | (1 << 3));
                ibi[2] = (byte) (i1 >> 3);
                ibi[3] = (byte) ((i1 & 7) | (1 << 3));
                ev.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, ds(t), ibi));
            }
        }
        java.util.Collections.sort(ev, new java.util.Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        return ev;
    }

    public static void main(String[] args) throws Exception {
        LocalDate day0 = LocalDate.of(2026, 10, 7);
        T0 = day0.atStartOfDay(ZONE).toEpochSecond();
        long now = T0 + 3 * 86400 + 7 * 3600 + 1800; // day 3, 07:30
        List<RingEvent> all = history(3);
        // the newest event happened a few minutes before the sync
        long lastDs = all.get(all.size() - 1).ds;
        System.out.println("synthetic history: " + all.size() + " events");

        File dir = Files.createTempDirectory("openring").toFile();
        Context ctx = new Context(dir);
        Db db = Db.get(ctx);

        // ---- sync 1: interrupted at 60 % (link lost, never reaches the present) ----
        int cut = (int) (all.size() * 0.6);
        for (int i = 0; i < cut; i += 4096) db.insertBatch(all.subList(i, Math.min(cut, i + 4096)), 1, now - 3600);
        Analyzer.run(ctx, null);
        DataCheck.Report r1 = DataCheck.run(ctx);
        System.out.println("after interrupted sync:");
        for (DataCheck.Step s : r1.steps) System.out.println("    " + s.state + " " + s.title + ": " + s.detail);
        check("interrupted sync is reported, not silent", r1.steps.get(2).state == 2, r1.steps.get(2).title);

        // ---- sync 2: resumes, drains to the present, phone anchor written by the sink ----
        for (int i = cut; i < all.size(); i += 4096) db.insertBatch(all.subList(i, Math.min(all.size(), i + 4096)), 1, now);
        long eventUnix = T0 + (lastDs - DS0) / 10;
        db.insertPhoneAnchor(1, lastDs, eventUnix);
        Analyzer.run(ctx, null);

        long total = db.count("SELECT COUNT(*) FROM events WHERE tag <> 66");
        long dated = db.count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL AND tag <> 66");
        check("every event dated, including the interrupted part", dated == total, dated + "/" + total);
        double err = db.count("SELECT MAX(ABS(unix - (" + T0 + " + (ds - " + DS0 + ") / 10.0))) FROM events WHERE unix IS NOT NULL");
        check("dates are exact", err < 2, err + " s");

        DataCheck.Report r2 = DataCheck.run(ctx);
        System.out.println(r2.text);
        for (DataCheck.Step s : r2.steps) check("data check: " + s.title, s.state == 0, s.detail);

        for (int d = 0; d < 3; d++) {
            String key = day0.plusDays(d).toString();
            JSONObject dayJ = Json.parse(db.dayJson(key));
            List<String> nights = db.nightsJson(key, key, true);
            check(key + " has a day summary", dayJ != null, null);
            check(key + " has a main night", nights.size() == 1, nights.size());
            if (nights.isEmpty() || dayJ == null) continue;
            JSONObject n = Json.parse(nights.get(0));
            JSONObject sc = n.optJSONObject("score");
            System.out.printf("    %s night %s %.0f min asleep, deep %.0f rem %.0f, lowest HR %.0f, HRV %.0f, temp %.2f, breath %.1f, SpO2 %.0f, sleep score %s%n",
                    key, n.optString("source"), n.optDouble("asleep_min"), n.optDouble("deep_min"), n.optDouble("rem_min"),
                    n.optDouble("lowest_hr"), n.optDouble("avg_hrv"), n.optDouble("temp"), n.optDouble("breath"), n.optDouble("spo2"),
                    sc == null ? "-" : sc.opt("value"));
            check(key + " asleep 6 to 9 h", n.optDouble("asleep_min") > 360 && n.optDouble("asleep_min") < 540, n.optDouble("asleep_min"));
            check(key + " lowest HR near 51", Math.abs(n.optDouble("lowest_hr") - 51) < 4, n.optDouble("lowest_hr"));
            check(key + " sleep score", sc != null && sc.optInt("value", -1) >= 50, sc == null ? null : sc.opt("value"));
            check(key + " steps estimated", dayJ.optDouble("steps", 0) > 1000 || d == 2, dayJ.opt("steps"));
            check(key + " heart rate curve", dayJ.optJSONArray("hr_day") != null, null);
            JSONObject rd = dayJ.optJSONObject("readiness");
            System.out.printf("    %s readiness %s, activity %s, steps %s, active kcal %s%n", key, rd == null ? "-" : rd.opt("value"),
                    dayJ.optJSONObject("activity_score") == null ? "-" : dayJ.optJSONObject("activity_score").opt("value"),
                    dayJ.opt("steps"), dayJ.opt("active_kcal"));
            check(key + " readiness", rd != null && rd.optInt("value", -1) > 0, rd == null ? null : rd.opt("value"));
        }

        // ---- a ring reboot: counter restarts, the official app had synced time once ----
        long epoch2Base = 2000;
        List<RingEvent> boot2 = new ArrayList<RingEvent>();
        double bootUnix = now + 3600;
        byte[] ts = new byte[4];
        Protocol.Le.putU32(ts, 0, (long) bootUnix + 60);
        boot2.add(new RingEvent(EventCodec.TIME_SYNC, epoch2Base + 600, ts));
        for (int k = 0; k < 30; k++) boot2.add(new RingEvent(EventCodec.MOTION_EVENT, epoch2Base + 600 + k * 600, new byte[] {1, 1, 2, 3}));
        db.insertBatch(boot2, 2, (long) bootUnix + 3 * 3600);
        Analyzer.run(ctx, null);
        long seg2 = db.count("SELECT COUNT(DISTINCT seg) FROM events");
        long boot2Dated = db.count("SELECT COUNT(*) FROM events WHERE ds < 100000 AND unix IS NOT NULL");
        check("reboot becomes its own segment", seg2 == 2, seg2);
        check("reboot dated from its own time record", boot2Dated == boot2.size(), boot2Dated);

        // ---- night JSON carries the movement strip for the depth chart ----
        List<String> lastNights = db.nightsJson(day0.toString(), day0.plusDays(2).toString(), true);
        JSONObject ln = Json.parse(lastNights.get(lastNights.size() - 1));
        check("movement strip stored with the night", ln.optJSONArray("motion_series") != null && ln.optJSONArray("motion_series").length() > 50,
                ln.optJSONArray("motion_series") == null ? null : ln.optJSONArray("motion_series").length());

        // ---- a ring lying on a table: says "asleep", 115 bpm noise, room temperature ----
        long realNow = System.currentTimeMillis() / 1000;
        List<RingEvent> table = new ArrayList<RingEvent>();
        long base = 500;
        byte[] ts3 = new byte[4];
        Protocol.Le.putU32(ts3, 0, realNow - 3 * 3600);
        table.add(new RingEvent(EventCodec.TIME_SYNC, base, ts3));
        for (int k = 0; k < 36; k++) { // 3 hours, every 5 minutes
            long d3 = base + k * 3000;
            byte[] spi = new byte[10];
            spi[0] = (byte) 230; // 115 bpm
            spi[4] = (byte) 160;
            spi[7] = 1; // "asleep"
            spi[9] = 0x10;
            table.add(new RingEvent(EventCodec.SLEEP_PERIOD_INFO_2, d3, spi));
            table.add(new RingEvent(EventCodec.TEMP_EVENT, d3 + 1, le16(1994, 2000, 1473)));
        }
        db.insertBatch(table, 3, realNow);
        long nightsBefore = db.count("SELECT COUNT(*) FROM nights");
        Analyzer.run(ctx, null);
        long nightsAfter = db.count("SELECT COUNT(*) FROM nights");
        check("ring on a table is not a night", nightsAfter == nightsBefore, nightsBefore + " -> " + nightsAfter);
        app.openring.data.RightNow now2 = app.openring.data.RightNow.load(ctx);
        check("latest sensor temperature read", Math.abs(now2.skinTemp - 19.94) < 0.01, now2.skinTemp);

        // ---- the same ring, worn: skin temperature ----
        List<RingEvent> worn = new ArrayList<RingEvent>();
        worn.add(new RingEvent(EventCodec.TEMP_EVENT, base + 36 * 3000 + 600, le16(3310, 3300, 2900)));
        byte[] ts4 = new byte[4];
        Protocol.Le.putU32(ts4, 0, realNow - 60);
        worn.add(new RingEvent(EventCodec.TIME_SYNC, base + 36 * 3000 + 600 + 1, ts4));
        db.insertBatch(worn, 3, realNow);
        Analyzer.run(ctx, null);
        app.openring.data.RightNow now3 = app.openring.data.RightNow.load(ctx);
        check("worn ring recognised", now3.worn == 1, now3.skinTemp + " C, worn " + now3.worn);

        // ---- sleep history: average night over all analysed nights ----
        app.openring.data.SleepHistory sh = app.openring.data.SleepHistory.load(db, "2000-01-01", "2100-01-01", java.time.ZoneId.of("Europe/Zurich"));
        check("history finds the nights", sh.nights >= 1, sh.nights + " nights");
        double peak = 0;
        for (int i = 0; i < app.openring.core.analysis.SleepPattern.BINS; i++) peak = Math.max(peak, sh.pattern.inBed(i));
        check("average night has an in bed peak", peak > 0.5 && peak <= 1.0001, peak);
        check("typical asleep minutes", sh.asleepMin > 120 && sh.asleepMin < 720, sh.asleepMin);
        check("typical wake time in the morning", sh.wakeMin > 4 * 60 && sh.wakeMin < 11 * 60, sh.wakeMin);

        // ---- backup: save everything, wipe, restore on a "new phone" ----
        app.openring.data.Prefs pr = app.openring.data.Prefs.of(ctx);
        byte[] keyBytes = app.openring.core.Hex.parseKey("4431967d8bacc2659743142b68391d9a");
        pr.saveKey(keyBytes);
        pr.setProfile(41, 1, 63, 168, 0);
        long eventsBefore = Db.get(ctx).count("SELECT COUNT(*) FROM events");
        java.io.ByteArrayOutputStream full = new java.io.ByteArrayOutputStream();
        app.openring.data.Backup.write(ctx, full, true);
        java.io.ByteArrayOutputStream small = new java.io.ByteArrayOutputStream();
        app.openring.data.Backup.write(ctx, small, false);
        java.io.File dir2 = java.nio.file.Files.createTempDirectory("openring2").toFile();
        Context phone2 = new Context(dir2);
        app.openring.data.Backup.Result br = app.openring.data.Backup.read(phone2, new java.io.ByteArrayInputStream(full.toByteArray()));
        check("full backup restores", br.ok && br.history, br.message);
        check("restored key matches", java.util.Arrays.equals(app.openring.data.Prefs.of(phone2).loadKey(), keyBytes), "");
        check("restored profile", app.openring.data.Prefs.of(phone2).age() == 41 && app.openring.data.Prefs.of(phone2).sex() == 1, "");
        long eventsAfter = Db.get(phone2).count("SELECT COUNT(*) FROM events");
        check("restored history", eventsAfter == eventsBefore, eventsBefore + " -> " + eventsAfter);
        app.openring.data.Backup.Result bs = app.openring.data.Backup.read(phone2, new java.io.ByteArrayInputStream(small.toByteArray()));
        check("key only backup restores", bs.ok && !bs.history, bs.message);
        app.openring.data.Backup.Result bt = app.openring.data.Backup.read(phone2,
                new java.io.ByteArrayInputStream("  4431967d8bacc2659743142b68391d9a\n".getBytes("UTF-8")));
        check("plain text key file accepted", bt.ok, bt.message);
        app.openring.data.Backup.Result bad = app.openring.data.Backup.read(phone2, new java.io.ByteArrayInputStream("hello".getBytes("UTF-8")));
        check("random file rejected", !bad.ok, bad.message);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
