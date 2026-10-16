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
            check("with every app counted nothing is left out", everyApp.excludedMin < 0.01 && Math.abs(everyApp.day.totalMin - di.rawMin) < 0.01, everyApp.excludedMin);
        }
        app.openring.data.ScreenDays.Info old = app.openring.data.ScreenDays.info(demo, LocalDate.now().minusDays(60), z, 180, dp.screenExcluded());
        check("days before the screen data are unknown", old.day == null && old.score == null && Double.isNaN(old.rows[13].totalMin), null);
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
        dstChecks(Db.get(phone3), "back", "2025-10-26", "2025-10-26T01:00:00Z", -60);
        List<String> fallMain = Db.get(phone3).nightsJson("2000-01-01", "2100-01-01", true);
        JSONObject fallNewest = Json.parse(fallMain.get(fallMain.size() - 1));
        check("newest demo night has a phone check at 00:30", Json.d(fallNewest, "phone_min") >= 1, fallNewest.optString("day"));
        check("at 00:30 the newest demo night is last night", "2025-11-08".equals(fallNewest.optString("day")), fallNewest.optString("day"));
        // clocks forward on 29 March 2026, at noon
        long springNow = java.time.LocalDateTime.parse("2026-04-12T12:00:00").atZone(zz).toEpochSecond();
        app.openring.data.Demo.enter(phone3, null, LocalDate.parse("2026-04-12"), springNow);
        dstChecks(Db.get(phone3), "forward", "2026-03-29", "2026-03-29T01:00:00Z", 60);
        List<String> springMain = Db.get(phone3).nightsJson("2000-01-01", "2100-01-01", true);
        JSONObject springNewest = Json.parse(springMain.get(springMain.size() - 1));
        check("newest demo night has a phone check at noon", Json.d(springNewest, "phone_min") >= 1
                && "2026-04-12".equals(springNewest.optString("day")), springNewest.optString("day"));
        app.openring.data.Demo.leave(phone3);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
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
    static void dstChecks(Db db, String way, String switchDay, String switchUtc, int minutes) {
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
        check("only that demo night is marked", marked == 1, marked);
    }
}
