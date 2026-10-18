import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import app.openring.core.EventCodec;
import app.openring.core.Protocol;
import app.openring.core.RingEvent;
import app.openring.core.analysis.ScreenTime;
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

        // ---- sleep stage detail (issue #9): Balanced by default, raw stages kept, a change re analyses ----
        {
            String key = day0.plusDays(1).toString();
            JSONObject n = Json.parse(db.nightsJson(key, key, true).get(0));
            check("night stored with Balanced detail", n.optInt("stage_detail", -1) == 1, n.opt("stage_detail"));
            int[] raw = Json.stagesRaw(n), st = Json.stages(n);
            check("raw stages stored next to the filtered ones", raw.length == st.length && raw.length > 0, raw.length + "/" + st.length);
            check("stored stages are the filtered raw ones", Arrays.equals(st, app.openring.core.analysis.StageFilter.filter(raw, 1)), null);
            app.openring.data.Prefs.of(ctx).setStageDetail(2);
            Analyzer.run(ctx, null);
            JSONObject calm = Json.parse(db.nightsJson(key, key, true).get(0));
            check("changing the detail re analyses older nights", calm.optInt("stage_detail", -1) == 2, calm.opt("stage_detail"));
            check("Calm has no more stage changes than Balanced",
                    app.openring.core.analysis.StageFilter.changes(Json.stages(calm)) <= app.openring.core.analysis.StageFilter.changes(st), null);
            app.openring.data.Prefs.of(ctx).setStageDetail(1);
            Analyzer.run(ctx, null);
        }

        // ---- screen time (issue #13): phone use moves the start of a night and counts as awake ----
        {
            String key = day0.plusDays(1).toString();
            app.openring.data.Prefs sp = app.openring.data.Prefs.of(ctx);
            JSONObject before = Json.parse(db.nightsJson(key, key, true).get(0));
            double s0 = before.optDouble("start"), e0 = before.optDouble("end");
            check("no phone minutes without screen data", !before.has("phone_min"), before.opt("phone_min"));
            check("screen time unknown before any data", app.openring.data.ScreenDays.day(db, day0.plusDays(1), ZONE) == null, null);
            List<double[]> sc = new ArrayList<double[]>();
            sc.add(new double[] {s0 - 1200, s0 + 900});            // phone in hand when the ring thinks the night starts
            sc.add(new double[] {s0 + 4 * 3600, s0 + 4 * 3600 + 300}); // a 5 minute phone check at night
            sc.add(new double[] {s0 + 5 * 3600, s0 + 5 * 3600 + 10});  // a 10 second glance
            db.putScreen(sc);
            db.reanalyseFrom(sc.get(0)[0]);
            Analyzer.run(ctx, null);
            JSONObject after = Json.parse(db.nightsJson(key, key, true).get(0));
            check("night starts when the phone is put away", Math.abs(after.optDouble("start") - (s0 + 900)) < 1, (after.optDouble("start") - s0) + " s later");
            check("night end unchanged", Math.abs(after.optDouble("end") - e0) < 1, null);
            check("phone minutes stored (the 30 second steps counted as awake)", Math.abs(after.optDouble("phone_min") - 5) <= 0.5, after.opt("phone_min"));
            int[] raw = Json.stagesRaw(after);
            double a0 = after.optDouble("start"), step = (after.optDouble("end") - a0) / raw.length;
            boolean awake = true;
            for (int i = 0; i < raw.length; i++) {
                double t0 = a0 + i * step;
                if (t0 >= s0 + 4 * 3600 && t0 + step <= s0 + 4 * 3600 + 300) awake &= raw[i] == 4;
            }
            check("phone check counted as awake", awake, null);
            int glance = (int) ((s0 + 5 * 3600 - a0) / step);
            check("a 10 second glance alone is not awake", raw[glance] != 4 && raw[glance + 1] != 4, raw[glance] + " " + raw[glance + 1]);
            check("phone check is a wake up", after.optInt("awakenings") >= 1, after.opt("awakenings"));
            int[] shown = Json.stages(after);
            boolean shownAwake = true;
            for (int i = 0; i < shown.length; i++) {
                double t0 = a0 + i * step;
                if (t0 >= s0 + 4 * 3600 && t0 + step <= s0 + 4 * 3600 + 300) shownAwake &= shown[i] == 4;
            }
            check("the detail filter keeps phone use awake", shownAwake, null);

            // a session read while the screen was still on gets its real end later
            List<double[]> longer = new ArrayList<double[]>();
            longer.add(new double[] {s0 + 4 * 3600, s0 + 4 * 3600 + 420});
            db.putScreen(longer);
            List<double[]> stored = db.screen(s0 - 7200, e0);
            check("running session updated, not doubled", stored.size() == 3 && Math.abs(stored.get(1)[1] - (s0 + 4 * 3600 + 420)) < 1e-6, stored.size());

            // the Activity page numbers
            ScreenTime.Day sd = app.openring.data.ScreenDays.day(db, day0.plusDays(1), ZONE);
            check("screen day total", sd != null && Math.abs(sd.totalMin - (7 + 10 / 60.0)) < 0.01, sd == null ? null : sd.totalMin);
            check("screen day turned on and longest", sd != null && sd.turnedOn == 2 && Math.abs(sd.longestMin - 7) < 0.01, sd == null ? null : sd.turnedOn);
            check("screen day has 24 hours", sd != null && sd.hourly.length == 24, sd == null ? null : sd.hourly.length);
            double[] week = app.openring.data.ScreenDays.lastDays(db, day0.plusDays(1), 7, ZONE);
            check("days before the first screen data are unknown", Double.isNaN(week[0]) && Double.isNaN(week[4]), Arrays.toString(week));
            check("evening phone use counts for its day", Math.abs(week[5] - 35) < 0.01, week[5]);
            check("clock change days have 23 and 25 hours",
                    app.openring.data.ScreenDays.hourEdges(LocalDate.of(2026, 3, 29), ZONE).length == 24
                    && app.openring.data.ScreenDays.hourEdges(LocalDate.of(2026, 10, 25), ZONE).length == 26, null);
            int[] hrs = app.openring.data.ScreenDays.hours(app.openring.data.ScreenDays.hourEdges(LocalDate.of(2026, 10, 25), ZONE), ZONE);
            check("the repeated hour is labelled twice", hrs[2] == 2 && hrs[3] == 2 && hrs[24] == 23, Arrays.toString(hrs));

            // switched off: the night is as before
            sp.setScreenAwake(false);
            Analyzer.run(ctx, null);
            JSONObject off = Json.parse(db.nightsJson(key, key, true).get(0));
            check("switch off: night as without screen data", Math.abs(off.optDouble("start") - s0) < 1 && !off.has("phone_min"), off.opt("phone_min"));
            sp.setScreenAwake(true);
            Analyzer.run(ctx, null);
            JSONObject on = Json.parse(db.nightsJson(key, key, true).get(0));
            check("switch on again: phone use counts again", Math.abs(on.optDouble("start") - (s0 + 900)) < 1, null);

            // the couch: phone in use for the whole of a short window, it is not a night
            String key0 = day0.toString();
            JSONObject n0 = Json.parse(db.nightsJson(key0, key0, true).get(0));
            double c0 = n0.optDouble("start"), c1 = n0.optDouble("end");
            List<double[]> lost = new ArrayList<double[]>();
            lost.add(new double[] {c0 - 60, c1 + 60}); // one session all night: a lost "off", not used for sleep
            db.putScreen(lost);
            db.reanalyseFrom(c0 - 60);
            Analyzer.run(ctx, null);
            check("a session of many hours does not delete a night", db.nightsJson(key0, key0, true).size() == 1, null);
            db.getWritableDatabase().execSQL("DELETE FROM screen WHERE start = ?", new Object[] {c0 - 60});
            List<double[]> couch = new ArrayList<double[]>();
            for (double t = c0 - 60; t < c1; t += 3 * 3600) couch.add(new double[] {t, Math.min(t + 3 * 3600 - 120, c1 + 60)});
            db.putScreen(couch);
            db.reanalyseFrom(c0 - 60);
            Analyzer.run(ctx, null);
            check("phone in use the whole time: no night", db.nightsJson(key0, key0, true).isEmpty(), db.nightsJson(key0, key0, true).size());
            for (double[] x : couch) db.getWritableDatabase().execSQL("DELETE FROM screen WHERE start = ?", new Object[] {x[0]});
            db.reanalyseFrom(c0 - 60);
            Analyzer.run(ctx, null);
            check("night back once that phone use is gone", db.nightsJson(key0, key0, true).size() == 1, null);
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
        pr.setScreenGoalMin(120);
        pr.setScreenExcluded(java.util.Arrays.asList("com.google.android.apps.maps", " ", "org.osmand"));
        pr.setGamesOn(true);
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
        check("restored screen time goal", app.openring.data.Prefs.of(phone2).screenGoalMin() == 120, app.openring.data.Prefs.of(phone2).screenGoalMin());
        check("restored apps not counted", app.openring.data.Prefs.of(phone2).screenExcluded().equals(
                new java.util.TreeSet<String>(java.util.Arrays.asList("com.google.android.apps.maps", "org.osmand"))), app.openring.data.Prefs.of(phone2).screenExcluded());
        check("restored games switch", app.openring.data.Prefs.of(phone2).gamesOn(), null);
        check("restored history keeps screen time", Db.get(phone2).count("SELECT COUNT(*) FROM screen") == 3, Db.get(phone2).count("SELECT COUNT(*) FROM screen"));
        check("restored history keeps the zone history (#22)", Db.get(phone2).zones().size() == Db.get(ctx).zones().size()
                && !Db.get(phone2).zones().isEmpty() && Db.get(phone2).zones().get(0)[2].equals(Db.get(ctx).zones().get(0)[2]), Db.get(phone2).zones().size());
        long eventsAfter = Db.get(phone2).count("SELECT COUNT(*) FROM events");
        check("restored history", eventsAfter == eventsBefore, eventsBefore + " -> " + eventsAfter);
        app.openring.data.Backup.Result bs = app.openring.data.Backup.read(phone2, new java.io.ByteArrayInputStream(small.toByteArray()));
        check("key only backup restores", bs.ok && !bs.history, bs.message);
        app.openring.data.Backup.Result bt = app.openring.data.Backup.read(phone2,
                new java.io.ByteArrayInputStream("  4431967d8bacc2659743142b68391d9a\n".getBytes("UTF-8")));
        check("plain text key file accepted", bt.ok, bt.message);
        app.openring.data.Backup.Result bad = app.openring.data.Backup.read(phone2, new java.io.ByteArrayInputStream("hello".getBytes("UTF-8")));
        check("random file rejected", !bad.ok, bad.message);

        // ---- demo mode: made up history in its own file, real data untouched ----
        long realEvents = Db.get(phone2).count("SELECT COUNT(*) FROM events");
        long t = System.currentTimeMillis();
        app.openring.data.Demo.enter(phone2, null);
        Db demo = Db.get(phone2);
        check("demo uses its own database", demo.fileName.equals(Db.DEMO), demo.fileName);
        long demoNights = demo.count("SELECT COUNT(*) FROM nights WHERE main = 1");
        check("demo has about five weeks of nights", demoNights >= 28 && demoNights <= 36, demoNights + " nights in " + (System.currentTimeMillis() - t) + " ms");
        app.openring.data.SleepHistory dh = app.openring.data.SleepHistory.load(demo, "2000-01-01", "2100-01-01", java.time.ZoneId.systemDefault());
        check("demo nights look like real nights", dh.asleepMin > 330 && dh.asleepMin < 560 && dh.awakeMin > 10 && dh.deepMin > 20 && dh.remMin > 20,
                String.format(java.util.Locale.US, "asleep %.0f, awake %.0f, deep %.0f, rem %.0f, score %.0f", dh.asleepMin, dh.awakeMin, dh.deepMin, dh.remMin, dh.score));
        check("demo notes no time zones, so its nights look as before (#22)", demo.zones().isEmpty()
                && demo.count("SELECT COUNT(*) FROM nights WHERE json LIKE '%\"zone\"%'") == 0, demo.zones().size());
        check("demo days have readiness", demo.count("SELECT COUNT(*) FROM days WHERE json LIKE '%readiness%'") >= 20, "");
        ScreenTime.Day demoScreen = app.openring.data.ScreenDays.day(demo, LocalDate.now().minusDays(2), java.time.ZoneId.systemDefault());
        check("demo has screen time", demoScreen != null && demoScreen.totalMin > 90 && demoScreen.totalMin < 300 && demoScreen.turnedOn > 20,
                demoScreen == null ? null : Math.round(demoScreen.totalMin) + " min, turned on " + demoScreen.turnedOn);
        // apps in front (1.15): the demo leaves its maps app out, so counted time is below the raw time
        check("demo has apps in front", demo.count("SELECT COUNT(DISTINCT pkg) FROM apps") >= 5, demo.count("SELECT COUNT(DISTINCT pkg) FROM apps"));
        app.openring.data.Prefs dp = app.openring.data.Prefs.of(phone2);
        check("demo leaves maps out, the real list stays", dp.screenExcluded().contains(app.openring.data.Demo.MAPS), dp.screenExcluded());
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        int mapsDays = 0;
        app.openring.data.ScreenDays.Info di = null;
        for (int back = 1; back < 20; back++) {
            app.openring.data.ScreenDays.Info x = app.openring.data.ScreenDays.info(demo, LocalDate.now().minusDays(back), z, 180, dp.screenExcluded());
            if (x.excludedMin > 1) {
                mapsDays++;
                if (di == null) di = x;
            }
        }
        check("some demo days have maps not counted", mapsDays >= 2 && di != null, mapsDays + " days");
        if (di != null) {
            check("counted is raw minus not counted", Math.abs(di.day.totalMin + di.excludedMin - di.rawMin) < 0.01, di.rawMin);
            check("screen score known with a night", di.score != null && di.score.value >= 0 && di.score.value <= 100 && !Double.isNaN(di.score.bedtime), di.score == null ? null : di.score.value);
            check("first look after waking", !Double.isNaN(di.firstLook) && di.firstLook >= di.sleepEnd, null);
            check("before bed and night minutes", di.bedMin >= 0 && di.nightMin >= 0, di.bedMin + " / " + di.nightMin);
            double dialMin = 0;
            // a dial step is 5 minutes, or a little more or less on a day the clocks change (25 or 23 hours)
            double stepMin = (di.to - di.from) / di.dial.length / 60;
            for (double v : di.dial) dialMin += v * stepMin;
            check("dial adds up to the day", Math.abs(dialMin - di.day.totalMin) < 1, dialMin + " vs " + di.day.totalMin);
            check("apps of the day known and sorted", di.appsKnown && di.apps.size() >= 3 && di.apps.get(0).getValue() >= di.apps.get(1).getValue(), di.apps.size());
            check("14 day rows, last is the day", di.rows.length == 14 && di.rows[13].date.equals(di.date) && Math.abs(di.rows[13].totalMin - di.day.totalMin) < 0.01, null);
            check("rows show nights", di.rows[13].nights.size() >= 1, null);
            app.openring.data.ScreenDays.Info everyApp = app.openring.data.ScreenDays.info(demo, di.date, z, 180, new java.util.HashSet<String>());
            check("leaving apps out does not split screen sessions", everyApp.day.turnedOn == di.day.turnedOn, everyApp.day.turnedOn + " vs " + di.day.turnedOn);
            app.openring.data.ScreenDays.Info wide = app.openring.data.ScreenDays.info(demo, di.date, z, 180, dp.screenExcluded(), 15 * 60);
            double wideMin = 0, exactMin = 0;
            for (double v : wide.dial) wideMin += v * 5;
            for (double v : di.dial) exactMin += v * 5;
            check("each use shown longer on the clock only", wideMin > exactMin + 10 && Math.abs(wide.day.totalMin - di.day.totalMin) < 0.01
                    && wide.score.value == di.score.value, Math.round(exactMin) + " -> " + Math.round(wideMin) + " min shown");
            check("touch readout data: sessions and apps of the day", !wide.sessions.isEmpty() && !wide.dayApps.isEmpty()
                    && app.openring.core.analysis.ScreenTime.at(wide.sessions, wide.sessions.get(0)[0] + 1) != null, null);
            check("no app in front is small in the demo", wide.noAppMin >= 0 && wide.noAppMin < 5, wide.noAppMin);
            check("with every app counted nothing is left out", everyApp.excludedMin < 0.01 && Math.abs(everyApp.day.totalMin - di.rawMin) < 0.01, everyApp.excludedMin);
        }
        app.openring.data.ScreenDays.Info old = app.openring.data.ScreenDays.info(demo, LocalDate.now().minusDays(60), z, 180, dp.screenExcluded());
        check("days before the screen data are unknown", old.day == null && old.score == null && Double.isNaN(old.rows[13].totalMin), null);
        long before = demo.count("SELECT COUNT(*) FROM screen");
        demo.deleteScreenSince(System.currentTimeMillis() / 1000.0 - 3 * 86400);
        long after = demo.count("SELECT COUNT(*) FROM screen");
        check("new counting rules read the last days again", after < before && after > 0 && demo.count("SELECT COUNT(*) FROM apps WHERE stop > " + (System.currentTimeMillis() / 1000 - 3 * 86400)) == 0,
                before + " -> " + after);
        check("recent apps list", app.openring.data.ScreenDays.recentApps(demo, 10).size() >= 5, null);
        int phoneNights = 0;
        for (String js : demo.nightsJson("2000-01-01", "2100-01-01", true)) if (Json.d(Json.parse(js), "phone_min") >= 1) phoneNights++;
        check("demo nights show phone checks", phoneNights >= 3, phoneNights + " nights");
        List<String> demoMain = demo.nightsJson("2000-01-01", "2100-01-01", true);
        check("newest demo night has a phone check (for the screenshots)", Json.d(Json.parse(demoMain.get(demoMain.size() - 1)), "phone_min") >= 1, null);
        // right after midnight the made up person has not slept long enough yet for today to have a night,
        // so until 04:00 the newest demo day may still be yesterday
        java.time.LocalDateTime clock = java.time.LocalDateTime.now();
        boolean demoCurrent = clock.toLocalDate().toString().equals(demo.latestDay())
                || clock.getHour() < 4 && clock.toLocalDate().minusDays(1).toString().equals(demo.latestDay());
        check("demo today exists", demoCurrent, demo.latestDay());
        app.openring.data.Demo.leave(phone2);
        check("leaving the demo restores the real data", Db.get(phone2).fileName.equals(Db.REAL)
                && Db.get(phone2).count("SELECT COUNT(*) FROM events") == realEvents, "");
        check("real list of apps not counted is back", app.openring.data.Prefs.of(phone2).screenExcluded().size() == 2, app.openring.data.Prefs.of(phone2).screenExcluded());

        // ---- demo across daylight saving switches (issue #22), on fixed past dates and hours ----
        java.io.File dir3 = java.nio.file.Files.createTempDirectory("openring3").toFile();
        Context phone3 = new Context(dir3);
        ZoneId zz = ZoneId.systemDefault();
        // clocks back on 26 October 2025; "now" is 00:30, when tonight's night is still too short
        long fallNow = java.time.LocalDateTime.parse("2025-11-09T00:30:00").atZone(zz).toEpochSecond();
        app.openring.data.Demo.enter(phone3, null, LocalDate.parse("2025-11-09"), fallNow);
        dstChecks(Db.get(phone3), "back", "2025-10-26", "2025-10-26T01:00:00Z", -60, "03:00", "02:00");
        List<String> fallMain = Db.get(phone3).nightsJson("2000-01-01", "2100-01-01", true);
        JSONObject fallNewest = Json.parse(fallMain.get(fallMain.size() - 1));
        check("newest demo night has a phone check at 00:30", Json.d(fallNewest, "phone_min") >= 1, fallNewest.optString("day"));
        check("at 00:30 the newest demo night is last night", "2025-11-08".equals(fallNewest.optString("day")), fallNewest.optString("day"));
        // clocks forward on 29 March 2026, at noon
        long springNow = java.time.LocalDateTime.parse("2026-04-12T12:00:00").atZone(zz).toEpochSecond();
        app.openring.data.Demo.enter(phone3, null, LocalDate.parse("2026-04-12"), springNow);
        dstChecks(Db.get(phone3), "forward", "2026-03-29", "2026-03-29T01:00:00Z", 60, "02:00", "03:00");
        List<String> springMain = Db.get(phone3).nightsJson("2000-01-01", "2100-01-01", true);
        JSONObject springNewest = Json.parse(springMain.get(springMain.size() - 1));
        check("newest demo night has a phone check at noon", Json.d(springNewest, "phone_min") >= 1
                && "2026-04-12".equals(springNewest.optString("day")), springNewest.optString("day"));
        app.openring.data.Demo.leave(phone3);

        demoTwice();
        oldNights();
        restoreSafety(full.toByteArray(), keyBytes);
        rebuildSameAsOld();
        longHistoryTiming();
        zoneTable();
        travelZones();
        zoneStorage(full.toByteArray());

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
    /**
     * Issues #26 and #27: a restore checks the backup's history before anything changes, never
     * replaces newer history on the phone without asking, and keeps the old file until the new
     * one opens. {@code full} is a full backup of the main test history.
     */
    static void restoreSafety(byte[] full, byte[] key) throws Exception {
        byte[] otherKey = app.openring.core.Hex.parseKey("00112233445566778899aabbccddeeff");
        byte[] dbBytes = zipEntry(full, "openring.db");

        // a phone with the backup's history plus newer nights
        Context ph = freshPhone();
        app.openring.data.Backup.Result r0 = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(full));
        check("restore onto an empty phone needs no question", r0.ok && r0.history, r0.message);
        Db db = Db.get(ph);
        long base = db.eventCount();
        double newest = db.datedRange()[1];
        for (int i = 0; i < 400; i++) {
            db.getWritableDatabase().execSQL("INSERT INTO events(epoch, tag, ds, body, captured, unix) VALUES (?,?,?,?,?,?)",
                    new Object[] {1L, 0x46, 900000000L + i, new byte[] {(byte) i, (byte) (i >> 8), 7}, 1L, newest + 86400 * 4 + i * 60.0});
        }
        long phoneEvents = db.eventCount();
        app.openring.data.Prefs pp = app.openring.data.Prefs.of(ph);
        pp.saveKey(otherKey);
        pp.setScreenGoalMin(45);

        // #27: damaged history, nothing changes
        byte[] junk = new byte[4096];
        System.arraycopy("SQLite format 3\0".getBytes("US-ASCII"), 0, junk, 0, 16);
        for (int i = 16; i < junk.length; i++) junk[i] = (byte) (i * 31);
        app.openring.data.Backup.Result rd = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(withDb(full, junk)));
        check("damaged backup history is refused (#27)", !rd.ok && rd.message.contains("damaged"), rd.message);
        unchanged(ph, phoneEvents, otherKey, "after a damaged backup");

        // #27: a history from a newer app version
        byte[] newer = withVersion(dbBytes, 99);
        app.openring.data.Backup.Result rn = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(withDb(full, newer)));
        check("newer version history is refused (#27)", !rn.ok && rn.message.contains("newer Open Ring"), rn.message);
        unchanged(ph, phoneEvents, otherKey, "after a newer version backup");

        // a healthy SQLite file that is not an Open Ring history
        java.io.File plain = java.nio.file.Files.createTempFile("plain", ".db").toFile();
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + plain.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)");
            st.execute("PRAGMA user_version = 4");
        }
        app.openring.data.Backup.Result rp = app.openring.data.Backup.read(ph,
                new java.io.ByteArrayInputStream(withDb(full, java.nio.file.Files.readAllBytes(plain.toPath()))));
        check("history without the events table is refused", !rp.ok && rp.message.contains("damaged"), rp.message);
        unchanged(ph, phoneEvents, otherKey, "after a file without events");

        // #27: passes the check but its upgrade fails when opened: the old file comes back
        byte[] broken = withVersion(dbBytes, 1); // already has the v2 column, so the v2 upgrade fails
        app.openring.data.Backup.Plan pb = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(withDb(full, broken)));
        app.openring.data.Backup.Result rb = app.openring.data.Backup.finish(ph, pb, true);
        check("history that does not open is rolled back (#27)", !rb.ok && rb.message.contains("was kept"), rb.message);
        unchanged(ph, phoneEvents, otherKey, "after a failed swap");
        check("no old copy left after the roll back", !new java.io.File(ph.getDatabasePath(Db.REAL).getPath() + ".old").exists(), null);

        // review of #37: a swap cut off in the middle (app stopped) brings the old history back on the next open
        app.openring.data.Prefs.of(ph).setDemo(true);
        Db.get(ph);
        java.io.File real = ph.getDatabasePath(Db.REAL);
        check("real history file closed for the cut off swap", real.renameTo(new java.io.File(real.getPath() + ".old")), null);
        java.nio.file.Files.write(real.toPath(), java.util.Arrays.copyOf(dbBytes, 1000));
        app.openring.data.Prefs.of(ph).setDemo(false);
        check("a cut off swap brings the old history back", Db.get(ph).eventCount() == phoneEvents, Db.get(ph).eventCount());
        check("no old copy left after the recovery", !new java.io.File(real.getPath() + ".old").exists(), null);

        // review of #37: a swap whose file opens with other contents than checked is rolled back
        java.io.File copy = java.nio.file.Files.createTempFile("copy", ".db").toFile();
        java.nio.file.Files.write(copy.toPath(), dbBytes);
        String swapError = null;
        try {
            Db.replaceWith(ph, copy, 1);
        } catch (java.io.IOException e) {
            swapError = e.getMessage();
        }
        check("a swap with another event count than checked is rolled back", swapError != null && Db.get(ph).eventCount() == phoneEvents,
                swapError + ", " + Db.get(ph).eventCount());
        check("no old copy left after that roll back", !new java.io.File(real.getPath() + ".old").exists(), null);

        // review of #37: demo mode stays on when the swap fails
        app.openring.data.Prefs.of(ph).setDemo(true);
        app.openring.data.Backup.Plan pd = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(withDb(full, broken)));
        app.openring.data.Backup.Result rdm = app.openring.data.Backup.finish(ph, pd, true);
        check("a failed swap leaves demo mode as it was", !rdm.ok && app.openring.data.Prefs.of(ph).demo(), rdm.message);
        app.openring.data.Prefs.of(ph).setDemo(false);
        unchanged(ph, phoneEvents, otherKey, "after a failed swap in demo mode");

        // two plans at once keep their own files; stale ones go
        app.openring.data.Backup.Plan p1 = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        app.openring.data.Backup.Plan p2 = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        java.nio.file.Files.write(new java.io.File(ph.getCacheDir(), "restore.db").toPath(), new byte[] {1});
        app.openring.data.Backup.cleanStale(ph);
        String[] live = ph.getCacheDir().list(new java.io.FilenameFilter() {
            public boolean accept(java.io.File d, String n) {
                return n.startsWith("restore");
            }
        });
        check("two plans keep their own files, a stale one is removed", live != null && live.length == 2, java.util.Arrays.toString(live));
        app.openring.data.Backup.cancel(ph, p1);
        app.openring.data.Backup.cancel(ph, p2);
        noTemp(ph, "after two plans");

        // the shim now fails like Android for a newer version
        Context fresh = freshPhone();
        java.nio.file.Files.write(fresh.getDatabasePath("x.db").toPath(), newer);
        String thrown = null;
        try {
            new android.database.sqlite.SQLiteOpenHelper(fresh, "x.db", null, 4) {
                public void onCreate(android.database.sqlite.SQLiteDatabase d) {}
                public void onUpgrade(android.database.sqlite.SQLiteDatabase d, int o, int n) {}
            }.getWritableDatabase();
        } catch (RuntimeException e) {
            thrown = e.getMessage();
        }
        check("opening a newer version database fails like on Android", thrown != null && thrown.contains("downgrade"), thrown);

        // #26: an older backup over newer history asks first
        app.openring.data.Backup.Plan po = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        check("older backup over newer history asks (#26)", po.error == null && po.ask && po.phoneNewer,
                "ask " + po.ask + ", phone newer " + po.phoneNewer);
        check("the question knows both dates", po.phoneNewest > po.backupNewest + 3 * 86400, (long) po.backupNewest + " vs " + (long) po.phoneNewest);
        app.openring.data.Backup.Result rk = app.openring.data.Backup.finish(ph, po, false);
        check("key and settings only keeps the newer history (#26)", rk.ok && !rk.history && Db.get(ph).eventCount() == phoneEvents
                && rk.message.contains("stays"), rk.message + ", " + Db.get(ph).eventCount() + " events");
        check("key and settings only restores the key", java.util.Arrays.equals(app.openring.data.Prefs.of(ph).loadKey(), key), null);
        check("key and settings only restores the settings", app.openring.data.Prefs.of(ph).screenGoalMin() == 120, app.openring.data.Prefs.of(ph).screenGoalMin());
        noTemp(ph, "after key and settings only");
        app.openring.data.Backup.Result ra = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(full));
        check("restore in one go keeps newer history too", ra.ok && !ra.history && Db.get(ph).eventCount() == phoneEvents, ra.message);
        app.openring.data.Backup.Plan pc = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        app.openring.data.Backup.cancel(ph, pc);
        noTemp(ph, "after cancel");
        app.openring.data.Backup.Plan px = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        app.openring.data.Backup.Result rx = app.openring.data.Backup.finish(ph, px, true);
        check("replace history, when chosen, restores the backup's history", rx.ok && rx.history && Db.get(ph).eventCount() == base,
                phoneEvents + " -> " + Db.get(ph).eventCount());
        noTemp(ph, "after replace");
        check("no old copy left after a replace", !new java.io.File(ph.getDatabasePath(Db.REAL).getPath() + ".old").exists(), null);
        check("a restore marks setup as done", app.openring.data.Prefs.of(ph).setupDone(), null);

        // review of #37: side files of a .old that is gone are removed by the next swap
        java.io.File orphan = new java.io.File(ph.getDatabasePath(Db.REAL).getPath() + ".old-wal");
        java.nio.file.Files.write(orphan.toPath(), new byte[] {1, 2, 3});
        app.openring.data.Prefs.of(ph).setSetupDone(false);
        app.openring.data.Backup.Plan pz = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        app.openring.data.Backup.Result rz = app.openring.data.Backup.finish(ph, pz, true);
        java.io.File[] olds = ph.getDatabasePath(Db.REAL).getParentFile().listFiles(new java.io.FilenameFilter() {
            public boolean accept(java.io.File d, String n) {
                return n.startsWith(Db.REAL + ".old");
            }
        });
        check("a swap removes orphan .old side files and leaves no .old", rz.ok && olds != null && olds.length == 0,
                olds == null ? null : java.util.Arrays.toString(olds));

        // only more events (undated), not later: still asks
        for (int i = 0; i < 50; i++) {
            Db.get(ph).getWritableDatabase().execSQL("INSERT INTO events(epoch, tag, ds, body, captured) VALUES (?,?,?,?,?)",
                    new Object[] {2L, 0x46, 800000000L + i, new byte[] {(byte) i, 9}, 1L});
        }
        app.openring.data.Backup.Plan pm = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(full));
        check("more events on the phone, not later, still asks", pm.ask && !pm.phoneNewer, "ask " + pm.ask + ", phone newer " + pm.phoneNewer);
        app.openring.data.Backup.cancel(ph, pm);

        // a key only backup never asks and never touches the history
        java.io.ByteArrayOutputStream small = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(small)) {
            z.putNextEntry(new java.util.zip.ZipEntry("openring-backup.json"));
            z.write(zipEntry(full, "openring-backup.json"));
            z.closeEntry();
        }
        app.openring.data.Backup.Plan ps = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(small.toByteArray()));
        check("key only backup does not ask", ps.error == null && !ps.ask && !ps.history, null);
        app.openring.data.Backup.cancel(ph, ps);

        // the leftover temp file from an invalid key is removed too
        app.openring.data.Backup.Result ri = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(
                withDb(zipWithJson("{\"key\":\"nope\"}"), dbBytes)));
        check("invalid key refused", !ri.ok, ri.message);
        noTemp(ph, "after an invalid key");
    }

    static Context freshPhone() throws java.io.IOException {
        return new Context(java.nio.file.Files.createTempDirectory("openring-restore").toFile());
    }

    static void unchanged(Context c, long events, byte[] key, String when) {
        check("phone history unchanged " + when, Db.get(c).eventCount() == events, Db.get(c).eventCount());
        check("key and settings unchanged " + when, java.util.Arrays.equals(app.openring.data.Prefs.of(c).loadKey(), key)
                && app.openring.data.Prefs.of(c).screenGoalMin() == 45, app.openring.data.Prefs.of(c).screenGoalMin());
        noTemp(c, when);
    }

    static void noTemp(Context c, String when) {
        String[] left = c.getCacheDir().list(new java.io.FilenameFilter() {
            public boolean accept(java.io.File d, String n) {
                return n.startsWith("restore");
            }
        });
        check("no restore file left " + when, left != null && left.length == 0, left == null ? null : java.util.Arrays.toString(left));
    }

    static byte[] zipEntry(byte[] zip, String name) throws java.io.IOException {
        java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));
        java.util.zip.ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            if (name.equals(e.getName())) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
                return b.toByteArray();
            }
        }
        return null;
    }

    static byte[] zipWithJson(String json) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            z.putNextEntry(new java.util.zip.ZipEntry("openring-backup.json"));
            z.write(json.getBytes("UTF-8"));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    /** The backup's JSON with another history file. */
    static byte[] withDb(byte[] zip, byte[] db) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            z.putNextEntry(new java.util.zip.ZipEntry("openring-backup.json"));
            z.write(zipEntry(zip, "openring-backup.json"));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("openring.db"));
            z.write(db);
            z.closeEntry();
        }
        return out.toByteArray();
    }

    static byte[] withVersion(byte[] db, int version) throws Exception {
        java.io.File f = java.nio.file.Files.createTempFile("ver", ".db").toFile();
        java.nio.file.Files.write(f.toPath(), db);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("PRAGMA user_version = " + version);
        }
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    /**
     * Issue #20: a full re analysis must rebuild every night it deleted, also those older than 400 days.
     * Cheap sparse history: two boots of the made up ring, 7 nights each, about 500 days apart.
     */
    static void oldNights() throws Exception {
        long t = System.currentTimeMillis();
        Context c = new Context(Files.createTempDirectory("openring4").toFile());
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        String[] starts = {"2024-05-01", "2025-09-10"};
        for (int k = 0; k < 2; k++) {
            long t0 = LocalDate.parse(starts[k]).atStartOfDay(z).toEpochSecond();
            long end = t0 + 8 * 86400L + 12 * 3600;
            List<RingEvent> ev = app.openring.data.Demo.history(t0, 7, end, z, new Random(7 + k));
            for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), k + 1, end);
            long lastDs = ev.get(ev.size() - 1).ds;
            db.insertPhoneAnchor(k + 1, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10);
            Analyzer.run(c, null); // one sync per boot, like a person wearing the ring for years
        }
        long nights = db.count("SELECT COUNT(*) FROM nights"), days = db.count("SELECT COUNT(*) FROM days");
        String oldest = nights == 0 ? null : Json.parse(db.nightsJson("2000-01-01", "2100-01-01", true).get(0)).optString("day");
        check("syncs keep the nights of both boots, 500 days apart", nights == 14 && "2024-05-02".equals(oldest), nights + " nights, oldest " + oldest);
        // a new stage detail picked up by the next sync (incremental path) reaches the oldest night
        app.openring.data.Prefs pr = app.openring.data.Prefs.of(c);
        int level = pr.stageDetail() == app.openring.core.analysis.StageFilter.DETAILED
                ? app.openring.core.analysis.StageFilter.BALANCED : app.openring.core.analysis.StageFilter.DETAILED;
        pr.setStageDetail(level);
        Analyzer.run(c, null);
        JSONObject first = Json.parse(db.nightsJson("2000-01-01", "2100-01-01", true).get(0));
        check("new stage detail at a sync reaches the oldest night", first.optInt("stage_detail", -1) == level
                && "2024-05-02".equals(first.optString("day")) && db.count("SELECT COUNT(*) FROM nights") == nights,
                first.optString("day") + " detail " + first.optInt("stage_detail", -1));
        Analyzer.runAll(c, null);
        long nightsAll = db.count("SELECT COUNT(*) FROM nights"), daysAll = db.count("SELECT COUNT(*) FROM days");
        String oldestAll = Json.parse(db.nightsJson("2000-01-01", "2100-01-01", true).get(0)).optString("day");
        check("full re analysis keeps every night and day (#20)", nightsAll == nights && daysAll == days,
                nights + " to " + nightsAll + " nights, " + days + " to " + daysAll + " days");
        check("full re analysis keeps the oldest night", oldestAll.equals(oldest), oldestAll);
        // and after the full re analysis the oldest night keeps its date and the chosen detail
        first = Json.parse(db.nightsJson("2000-01-01", "2100-01-01", true).get(0));
        check("after the full re analysis the oldest night has the chosen detail", first.optInt("stage_detail", -1) == level
                && "2024-05-02".equals(first.optString("day")), first.optString("day") + " detail " + first.optInt("stage_detail", -1)
                + ", " + (System.currentTimeMillis() - t) + " ms");
        // issue #28: nights from the old filter rules (no "stage_filter" yet) are analysed again once, then the
        // analysis is incremental again. A deleted oldest night shows which way the next sync went.
        int version = app.openring.core.analysis.StageFilter.VERSION;
        check("the filter version is stored after an analysis", db.kvLong("stage_filter", -1) == version, db.kvLong("stage_filter", -1));
        db.getWritableDatabase().execSQL("DELETE FROM nights WHERE day = ?", new Object[] {"2024-05-02"});
        long without = db.count("SELECT COUNT(*) FROM nights");
        Analyzer.run(c, null);
        check("same filter version: the next sync stays incremental", without < nights && db.count("SELECT COUNT(*) FROM nights") == without,
                without + " to " + db.count("SELECT COUNT(*) FROM nights") + " nights");
        db.put("stage_filter", null); // a database from before 1.22
        Analyzer.run(c, null);
        JSONObject again = Json.parse(db.nightsJson("2000-01-01", "2100-01-01", true).get(0));
        check("older filter rules: every night is analysed again once (#28)", db.count("SELECT COUNT(*) FROM nights") == nights
                && "2024-05-02".equals(again.optString("day")) && db.kvLong("stage_filter", -1) == version,
                db.count("SELECT COUNT(*) FROM nights") + " nights, oldest " + again.optString("day"));
    }

    // ------------------------------------------------------------ issue #22, travel

    /** The zone history: rows extend and open, a gap counts for the later zone. */
    static void zoneTable() throws Exception {
        Db db = Db.get(freshDb("openring-zones"));
        check("no zone known before the first note", db.zoneAt(1000) == null, null);
        check("first note opens a row", Double.isNaN(db.noteZone(1000, "Europe/Zurich")), null);
        check("first row starts at 0", db.zones().size() == 1 && (Double) db.zones().get(0)[0] == 0 && (Double) db.zones().get(0)[1] == 1000, null);
        check("same zone extends the row", Double.isNaN(db.noteZone(2000, "Europe/Zurich")) && db.zones().size() == 1
                && (Double) db.zones().get(0)[1] == 2000, db.zones().size());
        check("a new zone opens a row and returns the end of the last", db.noteZone(3000, "America/New_York") == 2000
                && db.zones().size() == 2 && (Double) db.zones().get(1)[0] == 3000, db.zones().size());
        db.noteZone(1500, "America/New_York");
        check("an older note does not shorten a row", (Double) db.zones().get(1)[1] == 3000, db.zones().get(1)[1]);
        db.noteZone(2500, "Europe/Zurich");
        List<Object[]> z = db.zones();
        check("a clock set back never makes rows overlap", z.size() == 3 && (Double) z.get(2)[0] > (Double) z.get(1)[1], z.size());
        check("zone inside a row", "Europe/Zurich".equals(db.zoneAt(500)) && "Europe/Zurich".equals(db.zoneAt(2000)), db.zoneAt(500));
        check("zone in a gap is the later one", "America/New_York".equals(db.zoneAt(2500)), db.zoneAt(2500));
        check("zone after the newest row is the newest", "Europe/Zurich".equals(db.zoneAt(1e10)), db.zoneAt(1e10));
        check("an empty id is not noted", Double.isNaN(db.noteZone(5000, "")) && db.zones().size() == 3, null);
        // Analyzer.noteZone: a clock set far ahead is ignored, a zone with the same clock analyses nothing again
        double now = System.currentTimeMillis() / 1000.0;
        Db a = Db.get(freshDb("openring-zones-clamp"));
        Analyzer.noteZone(a, now - 86400 * 3, ZoneId.of("Europe/Zurich"), Double.NaN);
        check("a time two days after this phone's clock is ignored", !Analyzer.noteZone(a, now + 2 * 86400, ZoneId.of("America/New_York"), Double.NaN)
                && a.zones().size() == 1, a.zones().size());
        check("a time over a day after the newest event is ignored", !Analyzer.noteZone(a, now, ZoneId.of("America/New_York"), now - 3 * 86400)
                && a.zones().size() == 1, a.zones().size());
        a.put("analyzed_until", (long) now);
        check("Zurich to Paris: a new row, nothing analysed again", !Analyzer.noteZone(a, now - 86400, ZoneId.of("Europe/Paris"), Double.NaN)
                && a.zones().size() == 2 && a.kvLong("analyzed_until", 0) == (long) now, a.kvLong("analyzed_until", 0));
        check("Paris to New York: the nights since are analysed again", Analyzer.noteZone(a, now - 3600, ZoneId.of("America/New_York"), now)
                && a.zones().size() == 3 && a.kvLong("analyzed_until", 0) == (long) (now - 86400), a.kvLong("analyzed_until", 0));
        db = Db.get(freshDb("openring-zones-2"));
        db.noteZone(1000, "Europe/Zurich");
        db.noteZone(2000, "America/New_York");
        check("the zone before a gap", "Europe/Zurich".equals(db.zoneBeforeGap(1500)) && db.zoneBeforeGap(500) == null
                && db.zoneBeforeGap(3000) == null, db.zoneBeforeGap(1500));
        db.noteZone(4000, "Europe/Zurich");
        db.noteZone(5000, "Europe/Zurich");
        db.noteZone(6000, "Europe/Zurich");
        db.resetAnalysis();
        check("a full re analysis keeps the zone history", db.zones().size() == 3, db.zones().size());
        db.resetAll();
        check("delete all local data deletes it", db.zones().isEmpty(), db.zones().size());
    }

    /**
     * A trip (issue #22): 10 nights in Zurich, a flight, 5 nights in New York, analysed with the phone in
     * New York. Each night keeps the zone it was slept in, its day and its score, also after Re analyse all.
     */
    static void travelZones() throws Exception {
        java.util.TimeZone keep = java.util.TimeZone.getDefault();
        ZoneId zh = ZoneId.of("Europe/Zurich"), ny = ZoneId.of("America/New_York");
        long t0 = LocalDate.parse("2026-09-01").atStartOfDay(zh).toEpochSecond(), end0 = t0 + 11 * 86400L + 12 * 3600;
        long t1 = LocalDate.parse("2026-09-13").atStartOfDay(ny).toEpochSecond(), end1 = t1 + 6 * 86400L + 12 * 3600;
        List<RingEvent> home = app.openring.data.Demo.history(t0, 10, end0, zh, new Random(21));
        List<RingEvent> away = app.openring.data.Demo.history(t1, 5, end1, ny, new Random(22));
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Zurich"));
            // the same Zurich nights alone, analysed at home: the reference
            Context ref = freshDb("openring-home");
            Db rdb = Db.get(ref);
            putBoot(rdb, home, 1, t0, end0);
            Analyzer.run(ref, null);
            java.util.Map<Double, JSONObject> homeNights = new java.util.HashMap<Double, JSONObject>();
            for (String js : rdb.nightsJson("2000-01-01", "2100-01-01", false)) homeNights.put(Json.d(Json.parse(js), "start"), Json.parse(js));

            Context c = freshDb("openring-trip");
            Db db = Db.get(c);
            // the phone notes its zone each morning; the flight is on 12 September, the first note in New York
            // is the morning of the 14th, after the first night there
            for (int d = 2; d <= 12; d++) db.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(zh).toEpochSecond(), "Europe/Zurich");
            for (int d = 14; d <= 19; d++) db.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(ny).toEpochSecond(), "America/New_York");
            putBoot(db, home, 1, t0, end0);
            putBoot(db, away, 2, t1, end1);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            Analyzer.runAll(c, null);
            tripChecks(db, homeNights, "with the phone in New York");
            // #45: back home, Re analyse all again; nothing moves
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Zurich"));
            Analyzer.runAll(c, null);
            tripChecks(db, homeNights, "back home, after Re analyse all");

            // flying far west: nights between two notes count in Los Angeles but keep their Zurich date, so
            // no two nights share a day and none becomes a nap
            Context w = freshDb("openring-west");
            Db wdb = Db.get(w);
            for (int d = 2; d <= 8; d++) wdb.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(zh).toEpochSecond(), "Europe/Zurich");
            wdb.noteZone(LocalDate.parse("2026-09-13").atTime(8, 0).atZone(ZoneId.of("America/Los_Angeles")).toEpochSecond(), "America/Los_Angeles");
            putBoot(wdb, home, 1, t0, end0);
            Analyzer.runAll(w, null);
            java.util.Set<String> wdays = new java.util.HashSet<String>();
            int la = 0, collide = 0;
            for (String js : wdb.nightsJson("2000-01-01", "2100-01-01", false)) {
                JSONObject o = Json.parse(js);
                if ("America/Los_Angeles".equals(o.optString("zone"))) la++;
                if (!wdays.add(o.optString("day"))) collide++;
                JSONObject h = homeNights.get(Json.d(o, "start"));
                if (h == null || !h.optString("day").equals(o.optString("day"))) collide++;
            }
            check("west: nights between the notes count in Los Angeles", la >= 3, la + " nights");
            check("west: no night lands on the day before, none becomes a nap", collide == 0
                    && wdb.count("SELECT COUNT(*) FROM nights WHERE main = 1") == rdb.count("SELECT COUNT(*) FROM nights WHERE main = 1"), collide + " collisions");
        } finally {
            java.util.TimeZone.setDefault(keep);
        }
    }

    static void putBoot(Db db, List<RingEvent> ev, int epoch, long t0, long end) {
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), epoch, end);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(epoch, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10);
    }

    static void tripChecks(Db db, java.util.Map<Double, JSONObject> homeNights, String when) {
        ZoneId zh = ZoneId.of("Europe/Zurich"), ny = ZoneId.of("America/New_York");
        List<JSONObject> main = new ArrayList<JSONObject>();
        for (String js : db.nightsJson("2000-01-01", "2100-01-01", true)) main.add(Json.parse(js));
        int zurich = 0, newYork = 0, sameDay = 0, sameScore = 0, ownClock = 0;
        String firstNy = null;
        for (JSONObject o : main) {
            String z = o.optString("zone");
            ZoneId oz = "Europe/Zurich".equals(z) ? zh : ny;
            if ("Europe/Zurich".equals(z)) {
                zurich++;
                JSONObject h = homeNights.get(Json.d(o, "start"));
                if (h != null && h.optString("day").equals(o.optString("day"))) sameDay++;
                if (h != null && h.optJSONObject("score").optInt("value") == o.optJSONObject("score").optInt("value")) sameScore++;
            } else if ("America/New_York".equals(z)) {
                newYork++;
                if (firstNy == null) firstNy = o.optString("day");
            }
            double m = clockFromNoon(Json.d(o, "sleep_start"), oz);
            if (m > 9 * 60 && m < 13 * 60) ownClock++; // between 21:00 and 01:00 on its own clock
            if (!o.optString("day").equals(java.time.Instant.ofEpochSecond((long) Json.d(o, "end")).atZone(oz).toLocalDate().toString())) ownClock -= 100;
        }
        check("trip " + when + ": nights keep their zones", zurich >= 9 && newYork >= 4 && zurich + newYork == main.size(),
                zurich + " Zurich, " + newYork + " New York of " + main.size());
        check("trip " + when + ": Zurich nights keep their days and scores", sameDay == zurich && sameScore == zurich,
                sameDay + " days, " + sameScore + " scores of " + zurich);
        check("trip " + when + ": each night falls asleep in the evening on its own clock, its day in its own zone", ownClock == main.size(),
                ownClock + " of " + main.size());
        check("trip " + when + ": the night before the first note there counts in New York", "2026-09-14".equals(firstNy), firstNy);
        app.openring.data.SleepHistory h = app.openring.data.SleepHistory.of(main, ZoneId.systemDefault());
        List<JSONObject> bare = new ArrayList<JSONObject>();
        for (JSONObject o : main) {
            JSONObject x = Json.parse(o.toString());
            x.remove("zone");
            bare.add(x);
        }
        app.openring.data.SleepHistory hb = app.openring.data.SleepHistory.of(bare, ZoneId.systemDefault());
        check("trip " + when + ": typical bedtime uses each night's clock", h.bedtimeMin > 21 * 60 && h.bedtimeMin < 24 * 60
                && h.bedtimeSdMin < 60 && hb.bedtimeSdMin > 120, Math.round(h.bedtimeMin) + " min, spread " + Math.round(h.bedtimeSdMin)
                + " (without zones " + Math.round(hb.bedtimeSdMin) + ")");
        JSONObject nyDay = Json.parse(db.dayJson("2026-09-15"));
        check("trip " + when + ": a day in New York is analysed", nyDay != null && nyDay.has("readiness"), null);
        JSONObject prev = null, first = null;
        for (JSONObject o : main) {
            if ("2026-09-14".equals(o.optString("day"))) first = o;
            else if (first == null) prev = o;
        }
        String line = first == null || prev == null ? null : app.openring.core.analysis.ClockChange.travel(prev.optString("zone"),
                first.optString("zone"), (Json.d(first, "start") + Json.d(first, "end")) / 2);
        check("trip " + when + ": the first night away gets the orange line",
                "Times this night are in New York time, 6 hours behind where you slept the night before.".equals(line), line);
    }

    /** Schema 4 to 5 (#22): an old database and an old full backup still open, with all rows and an empty zone table. */
    static void zoneStorage(byte[] full) throws Exception {
        byte[] v5 = zipEntry(full, "openring.db");
        java.io.File f = Files.createTempFile("v4", ".db").toFile();
        Files.write(f.toPath(), v5);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("DROP TABLE zones");
            st.execute("PRAGMA user_version = 4");
        }
        byte[] v4 = Files.readAllBytes(f.toPath());
        // the old database file on the phone, opened by the new version
        Context c = freshDb("openring-v4");
        Files.write(c.getDatabasePath(Db.REAL).toPath(), v4);
        long[] before = counts(f);
        Db db = Db.get(c);
        long[] after = {db.count("SELECT COUNT(*) FROM events"), db.count("SELECT COUNT(*) FROM nights"), db.count("SELECT COUNT(*) FROM days"),
            db.count("SELECT COUNT(*) FROM screen"), db.count("SELECT COUNT(*) FROM kv")};
        check("a schema 4 database opens with all its rows", Arrays.equals(before, after) && before[0] > 0 && before[1] > 0,
                Arrays.toString(before) + " -> " + Arrays.toString(after));
        check("it becomes schema 5 with an empty zone table", db.getReadableDatabase().getVersion() == 5 && db.zones().isEmpty()
                && db.count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'zones'") == 1, db.getReadableDatabase().getVersion());
        Analyzer.run(c, null);
        check("the first run after the update notes the zone from 0", db.zones().size() == 1 && (Double) db.zones().get(0)[0] == 0
                && ZoneId.systemDefault().getId().equals(db.zones().get(0)[2]), db.zones().size());
        // a full backup made by 1.25 or earlier
        Context ph = freshDb("openring-v4-restore");
        app.openring.data.Backup.Result r = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(withDb(full, v4)));
        Db rd = Db.get(ph);
        check("a schema 4 backup still restores", r.ok && r.history && rd.count("SELECT COUNT(*) FROM events") == before[0]
                && rd.count("SELECT COUNT(*) FROM nights") == before[1] && rd.zones().isEmpty(), r.message);
        // and a backup from a newer schema is refused whole, as before
        app.openring.data.Backup.Result rn = app.openring.data.Backup.read(freshDb("openring-v6"), new java.io.ByteArrayInputStream(withDb(full, withVersion(v5, 6))));
        check("a backup from a newer schema is refused", !rn.ok, rn.message);
    }

    static long[] counts(java.io.File f) throws Exception {
        long[] out = new long[5];
        String[] t = {"events", "nights", "days", "screen", "kv"};
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            for (int i = 0; i < t.length; i++) {
                try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t[i])) {
                    rs.next();
                    out[i] = rs.getLong(1);
                }
            }
        }
        return out;
    }

    /** Clock minutes after 12:00 the day before (so 23:00 is 660, 07:00 is 1140). */
    static double clockFromNoon(double unix, ZoneId z) {
        java.time.LocalTime t = java.time.Instant.ofEpochSecond((long) unix).atZone(z).toLocalTime();
        double m = t.getHour() * 60 + t.getMinute() - 12 * 60;
        return m < 0 ? m + 1440 : m;
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<Double>(v);
        java.util.Collections.sort(s);
        return s.isEmpty() ? Double.NaN : s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
    }

    /**
     * Issue #22: demo bed and wake times keep their clock time across a switch (median of up to 14 nights on each side
     * moves by under 30 minutes, the old bug moved it by 60), and the switch night carries the marker.
     */
    static void dstChecks(Db db, String way, String switchDay, String switchUtc, int minutes, String from, String to) {
        ZoneId z = ZoneId.systemDefault();
        LocalDate sw = LocalDate.parse(switchDay);
        List<Double> bedBefore = new ArrayList<Double>(), bedAfter = new ArrayList<Double>(),
                wakeBefore = new ArrayList<Double>(), wakeAfter = new ArrayList<Double>();
        JSONObject switchNight = null;
        int marked = 0;
        for (String js : db.nightsJson("2000-01-01", "2100-01-01", true)) {
            JSONObject o = Json.parse(js);
            LocalDate d = LocalDate.parse(o.optString("day"));
            if (o.has("clock_change_at")) marked++;
            if (d.equals(sw)) switchNight = o;
            long back = sw.toEpochDay() - d.toEpochDay();
            if (back >= 1 && back <= 14) {
                bedBefore.add(clockFromNoon(Json.d(o, "sleep_start"), z));
                wakeBefore.add(clockFromNoon(Json.d(o, "sleep_end"), z));
            } else if (back <= -1 && back >= -14) {
                bedAfter.add(clockFromNoon(Json.d(o, "sleep_start"), z));
                wakeAfter.add(clockFromNoon(Json.d(o, "sleep_end"), z));
            }
        }
        double bedShift = median(bedAfter) - median(bedBefore), wakeShift = median(wakeAfter) - median(wakeBefore);
        check("demo bedtimes keep their clock time when the clocks go " + way, bedBefore.size() >= 10 && bedAfter.size() >= 10
                && Math.abs(bedShift) < 30, Math.round(bedShift) + " min");
        check("demo wake times keep their clock time when the clocks go " + way, Math.abs(wakeShift) < 30, Math.round(wakeShift) + " min");
        check("demo night of the switch marks the clocks going " + way, switchNight != null
                && switchNight.optInt("clock_change_min") == minutes
                && (long) Json.d(switchNight, "clock_change_at") == java.time.Instant.parse(switchUtc).getEpochSecond(),
                switchNight == null ? null : switchNight.optString("clock_change_min") + " at " + switchNight.optString("clock_change_at"));
        check("demo switch night keeps the local clock times", switchNight != null
                && from.equals(switchNight.optString("clock_change_from")) && to.equals(switchNight.optString("clock_change_to")),
                switchNight == null ? null : switchNight.optString("clock_change_from") + " to " + switchNight.optString("clock_change_to"));
        check("only that demo night is marked", marked == 1, marked);
    }

    // ------------------------------------------------- issue #29, demo started twice at once

    /** Events, main nights, days, screen sessions and app rows of a demo database. */
    static String demoCounts(Db db) {
        return db.count("SELECT COUNT(*) FROM events") + " events, " + db.count("SELECT COUNT(*) FROM nights WHERE main = 1") + " nights, "
                + db.count("SELECT COUNT(*) FROM days") + " days, " + db.count("SELECT COUNT(*) FROM screen") + " screen, "
                + db.count("SELECT COUNT(*) FROM apps") + " apps";
    }

    /** Counts the progress steps one call reports. */
    static final class Steps implements app.openring.data.Demo.Progress {
        final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void step(String what) {
            n.incrementAndGet();
        }
    }

    /**
     * Turning the phone while the demo is prepared used to start a second run that reset the
     * database under the first. Now a second call waits and finds the demo built; the "built" mark
     * also keeps 00:30 (newest day still yesterday) from rebuilding it.
     */
    static void demoTwice() throws Exception {
        ZoneId z = ZoneId.systemDefault();
        final LocalDate day = LocalDate.parse("2026-02-10");
        final long now = java.time.LocalDateTime.parse("2026-02-10T00:30:00").atZone(z).toEpochSecond();
        Context single = freshDb("openring-demo-one");
        app.openring.data.Demo.enter(single, null, day, now);
        String want = demoCounts(Db.get(single));
        app.openring.data.Demo.leave(single);

        final Context phone = freshDb("openring-demo-two");
        final Steps a = new Steps(), b = new Steps();
        final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        final Throwable[] err = new Throwable[2];
        Thread[] th = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int k = i;
            th[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        app.openring.data.Demo.enter(phone, k == 0 ? a : b, day, now);
                    } catch (Throwable t) {
                        err[k] = t;
                    }
                }
            });
            th[i].start();
        }
        for (Thread t : th) t.join(600000);
        check("two demo starts at once: no error", err[0] == null && err[1] == null, err[0] != null ? err[0] : err[1]);
        String got = demoCounts(Db.get(phone));
        check("two demo starts at once: same demo as one start", got.equals(want), got + " (one start: " + want + ")");
        check("two demo starts at once: only one builds", a.n.get() + b.n.get() == 2 && (a.n.get() == 0 || b.n.get() == 0),
                a.n.get() + " and " + b.n.get() + " steps");
        check("demo marked as built today", day.toString().equals(Db.get(phone).kv("demo_day", null)), Db.get(phone).kv("demo_day", null));
        check("at 00:30 the newest demo day is still yesterday", day.minusDays(1).toString().equals(Db.get(phone).latestDay()), Db.get(phone).latestDay());

        Steps again = new Steps();
        boolean rebuilt = app.openring.data.Demo.enter(phone, again, day, now + 600);
        check("demo started again the same day is not rebuilt", !rebuilt && again.n.get() == 0 && demoCounts(Db.get(phone)).equals(want), again.n.get() + " steps");
        Steps cut = new Steps();
        app.openring.data.Demo.complete(phone, cut, day.plusDays(1), now + 86400);
        check("a complete demo is kept after a restart", cut.n.get() == 0, cut.n.get() + " steps");

        Steps next = new Steps();
        long nextNow = java.time.LocalDateTime.parse("2026-02-11T12:00:00").atZone(z).toEpochSecond();
        app.openring.data.Demo.enter(phone, next, day.plusDays(1), nextNow);
        check("demo started on a later day is built again", next.n.get() == 2 && "2026-02-11".equals(Db.get(phone).latestDay()),
                next.n.get() + " steps, newest " + Db.get(phone).latestDay());

        // cut off half way: events written, no mark (the app was ended while preparing)
        Db.get(phone).put("demo_day", null);
        Steps resume = new Steps();
        boolean finished = app.openring.data.Demo.complete(phone, resume, day.plusDays(1), nextNow + 60);
        check("a demo cut off half way is finished", finished && resume.n.get() == 2 && "2026-02-11".equals(Db.get(phone).kv("demo_day", null)),
                resume.n.get() + " steps");
        Steps later = new Steps();
        boolean redone = app.openring.data.Demo.complete(phone, later);
        check("a complete demo from an earlier day is kept after a restart", !redone && later.n.get() == 0, later.n.get() + " steps");
        Db.get(phone).resetAll();
        Steps empty = new Steps();
        app.openring.data.Demo.enter(phone, empty, day.plusDays(1), nextNow + 120);
        check("an emptied demo is built again", empty.n.get() == 2 && Db.get(phone).count("SELECT COUNT(*) FROM events") > 0, empty.n.get() + " steps");
        app.openring.data.Demo.leave(phone);
    }

    // ---------------------------------------------------------------- issue #21, dating

    /** A context with a database of its own ({@code Db.get} keeps one open instance). */
    static Context freshDb(String name) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("instance");
        f.setAccessible(true);
        Db open = (Db) f.get(null);
        if (open != null) open.close();
        f.set(null, null);
        return new Context(Files.createTempDirectory(name).toFile());
    }

    /** id, seg and the exact bits of unix of every row. */
    static List<long[]> timeRows(Db db) {
        List<long[]> out = new ArrayList<long[]>();
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT id, seg, unix FROM events ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                out.add(new long[] {c.getLong(0), c.isNull(1) ? -1 : c.getLong(1), c.isNull(2) ? Long.MIN_VALUE : Double.doubleToLongBits(c.getDouble(2))});
            }
        } finally {
            c.close();
        }
        return out;
    }

    static void putTimeRows(Db db, List<long[]> rows) {
        android.database.sqlite.SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            for (long[] r : rows) {
                d.execSQL("UPDATE events SET seg = ?, unix = ? WHERE id = ?", new Object[] {r[1] < 0 ? null : r[1],
                        r[2] == Long.MIN_VALUE ? null : Double.longBitsToDouble(r[2]), r[0]});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    static int differences(List<long[]> a, List<long[]> b) {
        if (a.size() != b.size()) return Math.abs(a.size() - b.size()) + 1000000;
        int n = 0;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) n++;
        return n;
    }

    /**
     * The full rewrite that {@code Db.rebuildTime} did up to version 1.18, kept as the reference:
     * every row undated, then each segment dated by SQL, then the 2015 and future limits.
     */
    static Db.TimeReport oldRebuildTime(Db d, long nowUnix) {
        android.database.sqlite.SQLiteDatabase db = d.getWritableDatabase();
        Db.TimeReport rep = new Db.TimeReport();
        rep.datedBefore = d.count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL");
        android.database.Cursor c = db.rawQuery("SELECT id, epoch, tag, ds, CASE WHEN tag IN (66, 133) THEN body END FROM events ORDER BY id", null);
        int n = c.getCount();
        long[] id = new long[n], epoch = new long[n], ds = new long[n], anchor = new long[n];
        boolean[] phone = new boolean[n];
        try {
            int i = 0;
            while (c.moveToNext()) {
                id[i] = c.getLong(0);
                epoch[i] = c.getLong(1);
                int tag = c.getInt(2);
                ds[i] = c.getLong(3);
                anchor[i] = -1;
                if (!c.isNull(4)) {
                    byte[] body = c.getBlob(4);
                    anchor[i] = EventCodec.anchorUnix(tag, body);
                    phone[i] = tag == EventCodec.TIME_SYNC && EventCodec.isPhoneAnchor(body);
                }
                i++;
            }
        } finally {
            c.close();
        }
        rep.total = n;
        List<app.openring.core.TimeLine.Segment> segs = app.openring.core.TimeLine.build(id, epoch, ds, anchor, phone, nowUnix);
        rep.segs = segs;
        rep.segments = segs.size();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE events SET unix = NULL, seg = NULL");
            for (app.openring.core.TimeLine.Segment sg : segs) {
                db.execSQL("UPDATE events SET seg = ? WHERE id BETWEEN ? AND ?", new Object[] {sg.no, sg.firstId, sg.lastId});
                for (Long stray : sg.strayIds) db.execSQL("UPDATE events SET seg = ? WHERE id = ?", new Object[] {sg.no, stray});
                if (sg.anchors.isEmpty()) continue;
                rep.anchoredSegments++;
                for (long[] r : app.openring.core.TimeLine.regions(sg)) {
                    db.execSQL("UPDATE events SET unix = ? + (ds - ?) / 10.0 WHERE seg = ? AND ds >= ? AND ds < ?",
                            new Object[] {r[3], r[2], sg.no, r[0], r[1]});
                }
            }
            db.execSQL("UPDATE events SET unix = NULL WHERE unix > ? OR unix < 1420000000", new Object[] {nowUnix + 6 * 3600});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        rep.datedAfter = d.count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL");
        return rep;
    }

    static String report(Db.TimeReport r) {
        return r.datedBefore + " dated before, " + r.datedAfter + " after, " + r.total + " events, " + r.segments + " segments, " + r.anchoredSegments + " anchored";
    }

    /**
     * Run the new rebuildTime and the old full rewrite on the same starting state and compare
     * every row and the report. Returns the new method's report.
     */
    static Db.TimeReport sameAsOld(Db db, long now, String step) {
        List<long[]> start = timeRows(db);
        Db.TimeReport fresh = db.rebuildTime(now);
        List<long[]> mine = timeRows(db);
        putTimeRows(db, start);
        Db.TimeReport ref = oldRebuildTime(db, now);
        List<long[]> theirs = timeRows(db);
        int diff = differences(mine, theirs);
        check(step + ": seg and date of every row as the old full rewrite", diff == 0, diff + " rows differ of " + mine.size());
        check(step + ": same dating report", report(fresh).equals(report(ref)) && fresh.segs.size() == ref.segs.size(), report(fresh) + " / " + report(ref));
        long changedRows = differences(start, mine);
        check(step + ": rows written are the rows that changed", fresh.changed == changedRows, fresh.changed + " / " + changedRows);
        Db.TimeReport again = db.rebuildTime(now);
        check(step + ": a second run writes nothing", again.changed == 0 && differences(timeRows(db), theirs) == 0, again.changed);
        return fresh;
    }

    static byte[] timeSync(long unix) {
        byte[] b = new byte[4];
        Protocol.Le.putU32(b, 0, unix);
        return b;
    }

    /** Movement rows; {@code mark} keeps them apart from equal rows of another boot (rows are unique). */
    static List<RingEvent> motion(long ds0, int count, long step, int mark) {
        List<RingEvent> out = new ArrayList<RingEvent>();
        for (int k = 0; k < count; k++) out.add(new RingEvent(EventCodec.MOTION_EVENT, ds0 + k * step, new byte[] {1, 1, 2, (byte) mark}));
        return out;
    }

    /** Issue #21: rebuildTime writes only changed rows and gives exactly the old result. */
    static void rebuildSameAsOld() throws Exception {
        Context c = freshDb("openring-time");
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        long t0 = LocalDate.parse("2026-01-05").atStartOfDay(z).toEpochSecond();
        long now = t0 + 37 * 86400L;
        long ds0 = app.openring.data.Demo.DS0;
        List<RingEvent> ev = app.openring.data.Demo.history(t0, 36, now, z, new Random(11));
        long cut = ds0 + 35L * 86400 * 10;
        List<RingEvent> first = new ArrayList<RingEvent>(), lastDay = new ArrayList<RingEvent>();
        for (RingEvent e : ev) (e.ds < cut ? first : lastDay).add(e);
        int part1 = (int) (first.size() * 0.1), part2 = (int) (first.size() * 0.6);
        // an interrupted first sync: no anchor yet, nothing can be dated
        for (int i = 0; i < part1; i += 4000) db.insertBatch(first.subList(i, Math.min(part1, i + 4000)), 1, now);
        Db.TimeReport r = sameAsOld(db, now, "interrupted sync");
        check("interrupted sync leaves the rows undated", r.datedAfter == 0, r.datedAfter);
        // a second interrupted sync starts with a time record of the ring, whose clock is 30 s ahead
        List<RingEvent> second = new ArrayList<RingEvent>(first.subList(part1, part2));
        long tsDs = second.get(0).ds;
        second.add(0, new RingEvent(EventCodec.TIME_SYNC, tsDs, timeSync(t0 + (tsDs - ds0) / 10 + 30)));
        for (int i = 0; i < second.size(); i += 4000) db.insertBatch(second.subList(i, Math.min(second.size(), i + 4000)), 1, now);
        r = sameAsOld(db, now, "interrupted sync with a time record");
        check("every row dated from the ring's time record", r.datedAfter == r.total, r.datedAfter + "/" + r.total);
        // the next sync reaches the present: its phone anchor re dates the older rows nearer to it
        for (int i = part2; i < first.size(); i += 4000) db.insertBatch(first.subList(i, Math.min(first.size(), i + 4000)), 1, now);
        long lastDs = first.get(first.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - ds0) / 10);
        r = sameAsOld(db, now, "a later anchor re dates older rows");
        long added = first.size() - part2 + 1;
        check("older rows re dated, not only the new ones", r.changed > added, r.changed + " written, " + added + " new");
        // a day later, synced again
        db.insertBatch(lastDay, 1, now);
        long lastDs2 = lastDay.get(lastDay.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs2, t0 + (lastDs2 - ds0) / 10);
        sameAsOld(db, now, "one more day");
        // 12 hours later the phone clock had been set back by 7 hours: the rows nearer to the new
        // anchor are dated from it (no new segment, the ring counter did not jump)
        long backDs = lastDs2 + 72 * 6000;
        db.insertBatch(motion(lastDs2 + 6000, 72, 6000, 7), 1, now);
        db.insertPhoneAnchor(1, backDs, t0 + (backDs - ds0) / 10 - 7 * 3600);
        r = sameAsOld(db, now, "phone clock set back by 7 hours");
        String newRow = "SELECT CAST(unix AS INTEGER) FROM events WHERE epoch = 1 AND tag = " + EventCodec.MOTION_EVENT + " AND body = X'01010207' AND ds = ";
        long firstShift = t0 + (lastDs2 + 6000 - ds0) / 10 - db.count(newRow + (lastDs2 + 6000));
        long lastShift = t0 + (backDs - ds0) / 10 - db.count(newRow + backDs);
        check("rows near the set back anchor follow the phone clock", r.segments == 1 && Math.abs(firstShift) <= 1 && Math.abs(lastShift - 7 * 3600) <= 1,
                firstShift + " s and " + lastShift + " s earlier");
        // a reboot: the counter restarts near zero in a new sync epoch, with the ring's own time record
        long bootUnix = t0 + 36 * 86400L + 3600;
        List<RingEvent> boot = motion(600, 40, 600, 2);
        boot.add(0, new RingEvent(EventCodec.TIME_SYNC, 300, timeSync(bootUnix)));
        db.insertBatch(boot, 2, now);
        r = sameAsOld(db, now, "reboot");
        check("reboot is a segment of its own", r.segments == 2 && r.anchoredSegments == 2, report(r));
        // a phone anchor of the first boot written after the reboot's rows (a stray), then more reboot rows
        db.insertPhoneAnchor(1, lastDs2 + 50, t0 + (lastDs2 + 50 - ds0) / 10 + 30);
        db.insertBatch(motion(600 + 40 * 600, 10, 600, 2), 2, now);
        r = sameAsOld(db, now, "stray phone anchor");
        check("the stray anchor belongs to the first boot", !r.segs.get(0).strayIds.isEmpty(), r.segs.get(0).strayIds);
        // epoch 3: a segment with an anchor, then a reboot without one; a phone anchor of the first
        // part lands inside the rows of the second
        long e3 = 5000000;
        List<RingEvent> a3 = motion(e3, 20, 600, 3);
        a3.add(0, new RingEvent(EventCodec.TIME_SYNC, e3, timeSync(t0 + 30 * 86400L)));
        db.insertBatch(a3, 3, now);
        db.insertBatch(motion(100, 5, 600, 3), 3, now);
        db.insertPhoneAnchor(3, e3 + 20 * 600, t0 + 30 * 86400L + 1200);
        db.insertBatch(motion(100 + 5 * 600, 5, 600, 3), 3, now);
        r = sameAsOld(db, now, "phone anchor inside a segment without anchors");
        long undated = db.count("SELECT COUNT(*) FROM events WHERE epoch = 3 AND ds < 10000 AND unix IS NULL");
        check("a segment without a time record stays undated", undated == 10, undated);
        long inside = db.count("SELECT COUNT(*) FROM events p WHERE p.epoch = 3 AND p.tag = 66 AND p.ds > 1000000 AND p.unix IS NOT NULL"
                + " AND p.seg = (SELECT seg FROM events WHERE epoch = 3 AND ds = 100)");
        check("that phone anchor keeps its date inside the undated segment, as before", inside == 1, inside);
        // epoch 4: dates after the download; epoch 5: dates before 2015; epoch 6: an anchor in the future
        List<RingEvent> a4 = motion(1000, 10, 3600 * 10, 4);
        a4.add(0, new RingEvent(EventCodec.TIME_SYNC, 1000, timeSync(now - 3600)));
        db.insertBatch(a4, 4, now);
        List<RingEvent> a5 = motion(100, 10, 10000, 5);
        a5.add(new RingEvent(EventCodec.TIME_SYNC, 100000, timeSync(1420000100L)));
        db.insertBatch(a5, 5, now);
        List<RingEvent> a6 = motion(100, 10, 600, 6);
        a6.add(new RingEvent(EventCodec.TIME_SYNC, 200, timeSync(now + 86400)));
        db.insertBatch(a6, 6, now);
        r = sameAsOld(db, now, "dates out of range");
        long late = db.count("SELECT COUNT(*) FROM events WHERE epoch = 4 AND unix IS NULL");
        long early = db.count("SELECT COUNT(*) FROM events WHERE epoch = 5 AND unix IS NULL");
        long future = db.count("SELECT COUNT(*) FROM events WHERE epoch = 6 AND unix IS NULL");
        // one hour apart from an hour before now: the 9th and 10th lie more than 6 hours ahead
        check("events dated well after the download are cleared", late == 2, late);
        check("events dated before 2015 are cleared", early > 0 && early < a5.size(), early);
        check("an anchor in the future is not used", future == a6.size(), future);
        // the time record of epoch 4 goes away: its rows must lose their dates, not keep the old ones
        db.getWritableDatabase().execSQL("DELETE FROM events WHERE epoch = 4 AND tag = 66");
        sameAsOld(db, now, "anchor row deleted");
        long lost = db.count("SELECT COUNT(*) FROM events WHERE epoch = 4 AND unix IS NOT NULL");
        check("rows without their time record are undated again", lost == 0, lost);
        db.close();
    }

    /**
     * Issue #21: timing on 120 days of demo history (a year takes about 25 s more test time and
     * shows the same factor). Prints the numbers; the only check is that the new rebuildTime is
     * faster than the old full rewrite (machines differ a lot).
     */
    static void longHistoryTiming() throws Exception {
        Context c = freshDb("openring-long");
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        long t0 = LocalDate.parse("2025-01-06").atStartOfDay(z).toEpochSecond();
        int days = 120;
        long now = t0 + (days + 1) * 86400L;
        List<RingEvent> ev = app.openring.data.Demo.history(t0, days, now, z, new Random(7));
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), 1, now);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10);
        ev = null;
        db.rebuildTime(now);
        long t = System.nanoTime();
        oldRebuildTime(db, now);
        long oldMs = (System.nanoTime() - t) / 1000000;
        t = System.nanoTime();
        Db.TimeReport r = db.rebuildTime(now);
        long newMs = (System.nanoTime() - t) / 1000000;
        System.out.println("  " + days + " days of demo history (" + r.total + " events), no new events: rebuildTime " + oldMs + " ms before, " + newMs + " ms now");
        check("long history: the result of the old method needs no write", r.changed == 0, r.changed);
        check("long history: rebuildTime faster than the old full rewrite", newMs < oldMs, newMs + " < " + oldMs + " ms");
        // an incremental analysis (the last two days), as after a sync without new nights
        double[] range = db.datedRange();
        db.put("analyzed_until", (long) range[1]);
        db.put("dated_count", r.datedAfter);
        db.put("event_count", r.total);
        db.put("stage_detail", app.openring.data.Prefs.of(c).stageDetail());
        db.put("stage_filter", app.openring.core.analysis.StageFilter.VERSION);
        db.put("screen_awake", 1);
        t = System.nanoTime();
        Analyzer.run(c, null);
        long incMs = (System.nanoTime() - t) / 1000000;
        System.out.println("  " + days + " days: incremental analysis " + incMs + " ms (about " + (incMs - newMs + oldMs) + " ms with the old rewrite)");
        check("long history: incremental analysis stays incremental", db.kvLong("analyzed_until", 0) >= (long) range[1] && db.count("SELECT COUNT(*) FROM nights") < 10,
                db.count("SELECT COUNT(*) FROM nights") + " nights");
        db.close();
    }
}
