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
        return history(days, java.util.Collections.<Integer>emptySet(), java.util.Collections.<Integer>emptySet());
    }

    /** As above, without any event on the days in {@code off} (the ring stayed in a drawer). */
    static List<RingEvent> history(int days, java.util.Set<Integer> off, java.util.Set<Integer> rest) {
        List<RingEvent> ev = new ArrayList<RingEvent>();
        for (int d = 0; d < days; d++) {
            if (off.contains(d)) continue;
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
                    double m = (hour > 17.5 && hour < 18.5 && !rest.contains(d)) ? 6.5 : (hour > 12 && hour < 12.5 && !rest.contains(d) ? 3.5 : 1.3 + rnd.nextDouble() * 0.6);
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
        // the word agrees with the number (issue #92)
        check("data check: one boot segment is singular", r1.steps.get(2).detail.contains("(0 time records in 1 boot segment)"), r1.steps.get(2).detail);

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
        check("data check: one of one boot segment is singular", r2.steps.get(2).detail.endsWith("1 of 1 boot segment with a time record"), r2.steps.get(2).detail);
        check("data check: events keep their grouping", r2.steps.get(0).detail.equals("5,353 events copied from the ring"), r2.steps.get(0).detail);
        check("data check: plurals for several nights", r2.steps.get(3).detail.startsWith("3 sleep periods (3 main nights), 4 days computed"), r2.steps.get(3).detail);
        dataCheckPrivate("synthetic history, nights older than 14 days", r2.text, db);

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
            // issue #44: the weekly parts count only the days so far, and wait for 3 of them
            JSONObject as = dayJ.optJSONObject("activity_score");
            check(key + " activity counts " + (d + 1) + " worn days", dayJ.optInt("activity_days", -1) == d + 1, dayJ.opt("activity_days"));
            check(key + " weekly parts " + (d < 2 ? "left out" : "included"), as != null && (activityPart(as, "targets") == null) == (d < 2), as);
            if (d == 2) check(key + " targets of 3 days", activityPart(as, "targets").optString("detail").endsWith(" of 3 days"), activityPart(as, "targets"));
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

            // issue #30: the stage detail preview is the night saving produces, phone use included. A 90 s
            // check is added that Calm (2 minutes) would fold into sleep without marking it awake again.
            List<double[]> check90 = new ArrayList<double[]>();
            check90.add(new double[] {s0 + 6 * 3600, s0 + 6 * 3600 + 90});
            db.putScreen(check90);
            db.reanalyseFrom(check90.get(0)[0]);
            Analyzer.run(ctx, null);
            int lostByFilter = 0;
            for (int lv = 0; lv < 3; lv++) {
                JSONObject cur = Json.parse(db.nightsJson(key, key, true).get(0));
                int[] pv = Analyzer.preview(db, sp.screenAwake(), cur, lv);
                if (!Arrays.equals(app.openring.core.analysis.StageFilter.filter(Json.stagesRaw(cur), lv), pv)) lostByFilter++;
                sp.setStageDetail(lv);
                Analyzer.run(ctx, null);
                JSONObject saved = Json.parse(db.nightsJson(key, key, true).get(0));
                double stepS = (saved.optDouble("end") - saved.optDouble("start")) / Json.stages(saved).length;
                check("#30 level " + lv + ": the preview is the night saving produces", Arrays.equals(pv, Json.stages(saved))
                        && app.openring.core.analysis.WakeUps.count(pv, stepS) == saved.optInt("awakenings"), saved.opt("awakenings"));
                int[] after90 = Json.stages(saved);
                int i90 = (int) ((s0 + 6 * 3600 + 30 - saved.optDouble("start")) / stepS);
                check("#30 level " + lv + ": the 90 s check stays awake", after90[i90] == 4 && after90[i90 + 1] == 4, after90[i90] + " " + after90[i90 + 1]);
            }
            check("#30 the filter alone would hide phone use at some level", lostByFilter > 0, lostByFilter);
            sp.setStageDetail(1);
            db.getWritableDatabase().execSQL("DELETE FROM screen WHERE start = ?", new Object[] {check90.get(0)[0]});
            db.reanalyseFrom(check90.get(0)[0]);
            Analyzer.run(ctx, null);

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
            check("#30 switch off: the preview leaves phone use out like the night", Arrays.equals(Analyzer.preview(db, false, off, 1), Json.stages(off))
                    && Arrays.equals(Analyzer.preview(db, sp.screenAwake(), off, 1), Json.stages(off)), null);
            sp.setScreenAwake(true);
            Analyzer.run(ctx, null);
            JSONObject on = Json.parse(db.nightsJson(key, key, true).get(0));
            check("switch on again: phone use counts again", Math.abs(on.optDouble("start") - (s0 + 900)) < 1, null);

            // issue #41: a 20 s check split 10 s and 10 s over a step border counts as awake
            int[] onRaw = Json.stagesRaw(on);
            double oStart = on.optDouble("start"), oStep = (on.optDouble("end") - oStart) / onRaw.length;
            int j = (int) (3 * 3600 / oStep);
            double border = oStart + (j + 1) * oStep;
            List<double[]> split = new ArrayList<double[]>();
            split.add(new double[] {border - 10, border + 10});
            db.putScreen(split);
            db.reanalyseFrom(border - 10);
            Analyzer.run(ctx, null);
            JSONObject withSplit = Json.parse(db.nightsJson(key, key, true).get(0));
            check("a 20 s check over a step border counts as awake (#41)", Json.stagesRaw(withSplit)[j] == 4
                    && Math.abs(withSplit.optDouble("phone_min") - on.optDouble("phone_min") - oStep / 60) < 0.01,
                    on.opt("phone_min") + " to " + withSplit.opt("phone_min"));
            check("the awake rule version is stored", db.kvLong("phone_awake", -1) == ScreenTime.AWAKE_RULE, db.kvLong("phone_awake", -1));
            // a database from before the rule version: the nights with screen data are analysed again once.
            // The night is deleted and the analysis set to redo only the days after it, so a night
            // that comes back shows the re analysis.
            db.getWritableDatabase().execSQL("DELETE FROM nights WHERE day = ?", new Object[] {key});
            db.put("analyzed_until", (long) (e0 + 5 * 86400));
            Analyzer.run(ctx, null);
            check("same awake rule: the next sync stays incremental", db.nightsJson(key, key, true).isEmpty(), null);
            db.getWritableDatabase().execSQL("DELETE FROM nights WHERE day = ?", new Object[] {key});
            db.put("analyzed_until", (long) (e0 + 5 * 86400));
            db.put("phone_awake", null);
            Analyzer.run(ctx, null);
            check("older awake rule: nights with screen data are analysed again (#41)", db.nightsJson(key, key, true).size() == 1
                    && db.kvLong("phone_awake", -1) == ScreenTime.AWAKE_RULE, null);
            // the extra check is gone again for the tests below
            db.getWritableDatabase().execSQL("DELETE FROM screen WHERE start = ?", new Object[] {border - 10});
            db.reanalyseFrom(border - 10);
            Analyzer.run(ctx, null);

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
        // ring finder (issue #74): three hours at room temperature read as taken off
        app.openring.core.analysis.LastWorn.Result off = app.openring.data.RightNow.lastWorn(db, realNow);
        check("ring finder: ring on a table reads as taken off", off.state == app.openring.core.analysis.LastWorn.OFF
                && Math.abs(off.roomC - 19.94) < 0.01, off.state + " " + off.roomC);

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
        app.openring.core.analysis.LastWorn.Result on = app.openring.data.RightNow.lastWorn(db, realNow);
        check("ring finder: worn again reads as worn", on.state == app.openring.core.analysis.LastWorn.WORN
                && Math.abs(on.skinC - 33.10) < 0.01, on.state + " " + on.skinC);
        app.openring.data.Prefs fp = app.openring.data.Prefs.of(ctx);
        fp.setBattery(40, 1000);
        fp.setBattery(41, 1300); // 5 minutes later: the read before stays unset
        check("ring finder: no earlier battery read within 10 minutes", fp.batteryBefore() == -1 && fp.battery() == 41, fp.batteryBefore());
        fp.setBattery(55, 4000);
        check("ring finder: the battery read before is kept", fp.batteryBefore() == 41 && fp.batteryBeforeAt() == 1300 && fp.battery() == 55, fp.batteryBefore());
        fp.setLastHeard(4100);
        check("ring finder: last heard saved", fp.lastHeard() == 4100, fp.lastHeard());
        boolean soundDefault = fp.finderSound();
        fp.setFinderSound(false);
        check("ring finder: clicks on by default, can be switched off", soundDefault && !fp.finderSound(), null);
        fp.setFinderSound(true);
        boolean heightBefore = fp.hasHeight();

        // ---- sleep history: average night over all analysed nights ----
        app.openring.data.SleepHistory sh = app.openring.data.SleepHistory.load(db, "2000-01-01", "2100-01-01", java.time.ZoneId.of("Europe/Zurich"));
        check("history finds the nights", sh.nights >= 1, sh.nights + " nights");
        double peak = 0;
        for (int i = 0; i < app.openring.core.analysis.SleepPattern.BINS; i++) peak = Math.max(peak, sh.pattern.inBed(i));
        check("average night has an in bed peak", peak > 0.5 && peak <= 1.0001, peak);
        check("typical asleep minutes", sh.asleepMin > 120 && sh.asleepMin < 720, sh.asleepMin);
        check("typical wake time in the morning", sh.wakeMin > 4 * 60 && sh.wakeMin < 11 * 60, sh.wakeMin);
        // issue #128: the Wake ups line names the detail level the nights were counted with
        List<JSONObject> calm = new ArrayList<JSONObject>(), mixed = new ArrayList<JSONObject>(), noLevel = new ArrayList<JSONObject>();
        for (int i = 0; i < 3; i++) {
            calm.add(Json.parse("{\"day\":\"2026-10-0" + (i + 1) + "\",\"awakenings\":" + (4 + i) + ",\"stage_detail\":2}"));
            mixed.add(Json.parse("{\"day\":\"2026-10-0" + (i + 1) + "\",\"awakenings\":5,\"stage_detail\":" + (i == 2 ? 1 : 2) + "}"));
            noLevel.add(Json.parse("{\"day\":\"2026-10-0" + (i + 1) + "\",\"awakenings\":5}"));
        }
        app.openring.data.SleepHistory hc = app.openring.data.SleepHistory.of(calm, ZoneId.of("UTC"));
        check("history: nights all at Calm say Calm, wake ups unchanged", hc.stageDetail == 2 && Math.abs(hc.wakeUps - 5) < 1e-9, hc.stageDetail + " " + hc.wakeUps);
        check("history: mixed levels say so", app.openring.data.SleepHistory.of(mixed, ZoneId.of("UTC")).stageDetail == -1, null);
        List<JSONObject> sameRule = new ArrayList<JSONObject>();
        for (int i = 0; i < 3; i++) sameRule.add(Json.parse("{\"day\":\"2026-10-0" + (i + 1) + "\",\"awakenings\":5,\"stage_detail\":" + (i == 1 ? 1 : 0) + "}"));
        int sr = app.openring.data.SleepHistory.of(sameRule, ZoneId.of("UTC")).stageDetail;
        check("history: Detailed and Balanced count the same wake ups, not a mix", sr >= 0 && app.openring.core.analysis.WakeUps.averageLine(sr).equals("Wake ups of a minute or more"), sr);
        check("history: nights without a level count as Detailed", app.openring.data.SleepHistory.of(noLevel, ZoneId.of("UTC")).stageDetail == 0, null);
        check("history: nights stored by the analysis carry the chosen level", sh.stageDetail == app.openring.data.Prefs.of(ctx).stageDetail(), sh.stageDetail);

        // ---- backup: save everything, wipe, restore on a "new phone" ----
        app.openring.data.Prefs pr = app.openring.data.Prefs.of(ctx);
        byte[] keyBytes = app.openring.core.Hex.parseKey("4431967d8bacc2659743142b68391d9a");
        pr.saveKey(keyBytes);
        pr.setProfile(41, 1, 63, 168, 0);
        check("ring finder: knows when a height was entered (step length)", !heightBefore && pr.hasHeight() && pr.heightCm() == 168, heightBefore);
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
        app.openring.core.analysis.LastWorn.Result demoLast = app.openring.data.RightNow.lastWorn(demo, System.currentTimeMillis() / 1000.0);
        check("ring finder: the demo ring reads as worn, confirmed by heart rate (#94)", demoLast.state == app.openring.core.analysis.LastWorn.WORN
                && !Double.isNaN(demoLast.skinC) && !demoLast.justOn && !Double.isNaN(demoLast.lastHr), demoLast.state + " " + demoLast.skinC + " " + demoLast.lastHr);
        long demoNights = demo.count("SELECT COUNT(*) FROM nights WHERE main = 1");
        check("demo has about five weeks of nights", demoNights >= 28 && demoNights <= 36, demoNights + " nights in " + (System.currentTimeMillis() - t) + " ms");
        app.openring.data.SleepHistory dh = app.openring.data.SleepHistory.load(demo, "2000-01-01", "2100-01-01", java.time.ZoneId.systemDefault());
        check("demo nights look like real nights", dh.asleepMin > 330 && dh.asleepMin < 560 && dh.awakeMin > 10 && dh.deepMin > 20 && dh.remMin > 20,
                String.format(java.util.Locale.US, "asleep %.0f, awake %.0f, deep %.0f, rem %.0f, score %.0f", dh.asleepMin, dh.awakeMin, dh.deepMin, dh.remMin, dh.score));
        check("demo notes no time zones, so its nights look as before (#22)", demo.zones().isEmpty()
                && demo.count("SELECT COUNT(*) FROM nights WHERE json LIKE '%\"zone\"%'") == 0, demo.zones().size());
        check("demo days have readiness", demo.count("SELECT COUNT(*) FROM days WHERE json LIKE '%readiness%'") >= 20, "");
        dayHeartChecks(demo);
        dataCheckPrivate("demo", DataCheck.run(phone2).text, demo);
        // issue #44: the first demo days count the worn days so far, later ones a full week as before
        int firstWorn = -1, fullWeek = 0, weekDays = 0;
        boolean weekTexts = true;
        for (String[] r : demo.daysJson("2000-01-01", "2100-01-01")) {
            JSONObject o = Json.parse(r[1]);
            if (o == null || o.optJSONObject("activity_score") == null) continue;
            if (firstWorn < 0) firstWorn = o.optInt("activity_days", -1);
            if (o.optInt("activity_days", -1) != 7) continue;
            weekDays++;
            JSONObject tp = activityPart(o.optJSONObject("activity_score"), "targets"), vp = activityPart(o.optJSONObject("activity_score"), "volume");
            weekTexts &= tp != null && tp.optString("detail").endsWith(" of 7 days") && vp != null && vp.optString("detail").endsWith(" kcal this week");
        }
        check("first demo day counts 1 worn day", firstWorn == 1, firstWorn);
        check("demo weeks are full weeks with the old texts", weekDays >= 25 && weekTexts, weekDays + " days");
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
            // a dial step is 5 minutes of clock time (#46); on the day the clocks go back the repeated hour
            // shares its steps, so the dial can show less than the total then, but at most 60 minutes less (#129)
            for (double v : di.dial) dialMin += v * 5;
            boolean longDay = di.to - di.from > 86400 + 1;
            check("dial adds up to the day", longDay ? dialMin <= di.day.totalMin + 1 && dialMin >= di.day.totalMin - 60
                    : Math.abs(dialMin - di.day.totalMin) < 1,
                    dialMin + " vs " + di.day.totalMin);
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
            long noApp = 0;
            for (ScreenTime.AppRow r : wide.appRows) if (r.kind == ScreenTime.AppRow.NO_APP) noApp += r.shownMin;
            check("no app in front is small in the demo", noApp < 5, noApp);
            long rowsCounted = 0, rowsLeft = 0;
            for (ScreenTime.AppRow r : di.appRows) {
                if (r.excluded) rowsLeft += r.shownMin;
                else rowsCounted += r.shownMin;
            }
            check("demo app rows add up to the total and to Not counted (#71)", rowsCounted == Math.round(di.day.totalMin)
                    && rowsLeft == Math.round(di.excludedMin), rowsCounted + " / " + rowsLeft);
            check("with every app counted nothing is left out", everyApp.excludedMin < 0.01 && Math.abs(everyApp.day.totalMin - di.rawMin) < 0.01, everyApp.excludedMin);
        }
        app.openring.data.ScreenDays.Info old = app.openring.data.ScreenDays.info(demo, LocalDate.now().minusDays(60), z, 180, dp.screenExcluded());
        check("days before the screen data are unknown", old.day == null && old.score == null && Double.isNaN(old.rows[13].totalMin), null);
        check("the demo's usage log counts as read from its first day, so no day is a gap (#131)", demo.screenReads().size() == 1
                && ScreenTime.coverage(demo.screenReads(), LocalDate.now().minusDays(30).atStartOfDay(z).toEpochSecond(),
                LocalDate.now().atStartOfDay(z).toEpochSecond(), System.currentTimeMillis() / 1000.0) == ScreenTime.COVERED, demo.screenReads().size());
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
        dayHeartLine(Db.get(phone3), "2025-10-26", 25);
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

        longWakeChecks();
        previewRingWake();
        freshDb("openring30-done");
        nightTwice();
        nightAcrossChecks();
        staleDays();
        demoTwice();
        deleteDuringAnalysis();
        oldNights();
        lastWornOldSync();
        lastWornStrayEvents();
        dayHeartLimits();
        dayHeartFastBeat();
        dayHeartVersion();
        todayHeartTime();
        restoreSafety(full.toByteArray(), keyBytes);
        rebuildSameAsOld();
        longHistoryTiming();
        dialClockChange();
        appRowsStored();
        activityGap();
        zoneTable();
        travelZones();
        zoneStorage(full.toByteArray());
        screenGaps(full.toByteArray());
        sqlForOldPhones();
        oneEventCheck();
        nightSummaryCases();
        backupGaps(full.toByteArray());
        historyCache();
        recentWindow();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
    /**
     * Issue #40: the copied data check is meant for public bug reports, so it holds counts and dates
     * only. No clock times, minutes asleep, scores, stages or battery level of any night, but still the
     * number of sleep periods per day as the database has them.
     */
    static void dataCheckPrivate(String label, String text, Db db) throws Exception {
        String from = LocalDate.now().minusDays(DataCheck.NIGHT_DAYS - 1).toString();
        List<String> ns = db.nightsJson(from, "9999-12-31", false);
        if (ns.isEmpty()) {
            check("data check (" + label + "): no recent nights, says none", text.contains("\nSleep periods of the last 14 days (counts and dates only)\n  none\n"), text);
            return;
        }
        check("data check (" + label + "): no clock time", !java.util.regex.Pattern.compile("\\d{1,2}:\\d{2}").matcher(text).find(), text);
        check("data check (" + label + "): no minutes", !java.util.regex.Pattern.compile("\\d+ ?min\\b").matcher(text).find(), text);
        check("data check (" + label + "): no battery level, no per night list",
                !text.contains("battery") && !text.contains("from, to, asleep"), text);
        // a score is a whole number next to the word; the only one left is the count "with a sleep score: n of m"
        check("data check (" + label + "): no score value", !java.util.regex.Pattern.compile("score\\D{0,3}\\d++(?! of )").matcher(text).find(), text);
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        boolean leak = false;
        int main = 0, scored = 0;
        java.util.Map<String, int[]> perDay = new java.util.TreeMap<String, int[]>();
        for (String js : ns) {
            JSONObject o = Json.parse(js);
            String stages = o.optString("stages", "");
            leak |= text.contains(hm.format(new java.util.Date((long) (o.optDouble("start") * 1000))))
                    || text.contains(hm.format(new java.util.Date((long) (o.optDouble("end") * 1000))))
                    || stages.length() >= 10 && text.contains(stages.substring(0, 10));
            if (o.optBoolean("main")) main++;
            if (o.optJSONObject("score") != null && o.optJSONObject("score").has("value")) scored++;
            int[] n = perDay.get(o.optString("day"));
            if (n == null) perDay.put(o.optString("day"), n = new int[2]);
            n[o.optBoolean("main") ? 0 : 1]++;
        }
        check("data check (" + label + "): no start, end or stages of any night", !leak, text);
        check("data check (" + label + "): counts the sleep periods", text.contains(app.openring.core.Texts.count(ns.size(), "sleep period", "sleep periods")
                + " (" + app.openring.core.Texts.count(main, "main night", "main nights") + ", "
                + app.openring.core.Texts.count(ns.size() - main, "nap", "naps") + "), newest on "), text);
        check("data check (" + label + "): counts the scored nights", text.contains("with a sleep score: " + scored + " of " + ns.size()), text);
        boolean days = true;
        for (java.util.Map.Entry<String, int[]> e : perDay.entrySet()) {
            String want = e.getValue()[1] == 0 ? app.openring.core.Texts.count(e.getValue()[0], "main night", "main nights")
                    : e.getValue()[0] == 0 ? app.openring.core.Texts.count(e.getValue()[1], "nap", "naps")
                    : app.openring.core.Texts.count(e.getValue()[0], "main night", "main nights") + ", " + app.openring.core.Texts.count(e.getValue()[1], "nap", "naps");
            days &= text.contains("    " + e.getKey() + "  " + want + "\n");
        }
        check("data check (" + label + "): each day with its count", days, text);
        check("data check (" + label + "): names the stage sources", text.contains("  Stages from: "), text);
        // exactly 14 days listed, today and the 13 before, as the header says
        int listed = 0;
        for (String line : text.split("\n")) if (line.matches("    \\d{4}-\\d{2}-\\d{2}  .*")) listed++;
        check("data check (" + label + "): lists as many days as the header says", listed == DataCheck.NIGHT_DAYS, listed);
    }

    /** Issue #40, review of PR #173: each branch of the night summary, with made up nights. */
    static void nightSummaryCases() {
        LocalDate today = LocalDate.parse("2026-10-26");
        List<String> ns = new ArrayList<String>();
        ns.add("{\"day\":\"2026-10-24\",\"main\":true,\"source\":\"ring\",\"phone_min\":0,\"score\":{\"value\":81},\"start\":1,\"end\":2}");
        ns.add("{\"day\":\"2026-10-24\",\"main\":false,\"source\":\"signals\",\"score\":{}}");
        ns.add("{\"day\":\"2026-10-25\",\"main\":false,\"source\":\"signals\"}");
        ns.add("{\"day\":\"2026-10-26\",\"main\":true,\"source\":\"window\",\"phone_min\":12.5,\"score\":{\"value\":64}}");
        ns.add("{\"day\":\"2026-10-26\",\"main\":true}");
        ns.add("not json {");
        String t = DataCheck.nightSummary(ns, today, 3);
        System.out.println(t);
        check("night summary: header", t.startsWith("\nSleep periods of the last 3 days (counts and dates only)\n"), t);
        check("night summary: totals, unparsable line skipped", t.contains("  5 sleep periods (3 main nights, 2 naps), newest on 2026-10-26\n"), t);
        check("night summary: several sources, missing one counted as unknown", t.contains("  Stages from: ring 1, signals 2, unknown 1, window 1\n"), t);
        check("night summary: phone data and scores counted", t.contains("  With phone use data: 2 of 5, with a sleep score: 2 of 5\n"), t);
        check("night summary: main night and nap", t.contains("    2026-10-24  1 main night, 1 nap\n"), t);
        check("night summary: nap only", t.contains("    2026-10-25  1 nap\n"), t);
        check("night summary: two main nights", t.contains("    2026-10-26  2 main nights\n"), t);
        check("night summary: no score values", !t.contains("81") && !t.contains("64") && !t.contains("12.5"), t);
        String gap = DataCheck.nightSummary(java.util.Collections.singletonList("{\"day\":\"2026-10-26\",\"main\":true,\"source\":\"ring\"}"), today, 3);
        check("night summary: empty days say none", gap.contains("    2026-10-24  none\n    2026-10-25  none\n    2026-10-26  1 main night\n")
                && !gap.contains("2026-10-23"), gap);
        check("night summary: no nights at all", DataCheck.nightSummary(new ArrayList<String>(), today, 3)
                .equals("\nSleep periods of the last 3 days (counts and dates only)\n  none\n"), "");
        check("night summary: only unparsable lines", DataCheck.nightSummary(java.util.Collections.singletonList("{"), today, 3).endsWith("  none\n"), "");
    }

    /** Issue #92, code review of PR #100: a history of a single event says "1 event", also in the timing line. */
    static void oneEventCheck() throws Exception {
        long now = T0 + 3600;
        Context c = freshDb("openring-one");
        Db db = Db.get(c);
        List<RingEvent> one = new ArrayList<RingEvent>();
        one.add(new RingEvent(EventCodec.MOTION_EVENT, DS0 + 600, new byte[] {1, 1, 2, 3}));
        db.insertBatch(one, 1, now);
        Analyzer.run(c, null);
        DataCheck.Report r = DataCheck.run(c);
        check("data check: one event copied", r.steps.get(0).detail.equals("1 event copied from the ring"), r.steps.get(0).detail);
        check("data check: zero of one event dated", r.steps.get(2).detail.startsWith("0 of 1 event could be dated (0 time records in 1 boot segment)"),
                r.steps.get(2).detail);

        c = freshDb("openring-one-dated");
        db = Db.get(c);
        one.clear();
        // the ring's own time record (u32 seconds, no "phone" mark) is the only event and dates itself
        one.add(new RingEvent(EventCodec.TIME_SYNC, DS0 + 600, Arrays.copyOf(app.openring.core.TimeLine.phoneAnchorBody(now), 4)));
        db.insertBatch(one, 1, now);
        Analyzer.run(c, null);
        r = DataCheck.run(c);
        check("data check: one of one event dated", r.steps.get(2).detail.startsWith("1 of 1 event dated (100%), 1 of 1 boot segment"), r.steps.get(2).detail);
    }

    static JSONObject activityPart(JSONObject score, String key) {
        org.json.JSONArray cs = score == null ? null : score.optJSONArray("contributors");
        for (int i = 0; cs != null && i < cs.length(); i++) if (key.equals(cs.optJSONObject(i).optString("key"))) return cs.optJSONObject(i);
        return null;
    }

    /**
     * Issue #44: ten days with the ring left off on days 3 to 6, and an evening workout only on
     * days 0 and 7 (and the lunch walk only then too), so the days before and after the gap have the same pattern (1 training day of
     * 3 worn days) and the weekly parts stay below 100. Days without the ring are left out
     * of the weekly parts of the activity score instead of counting as missed targets.
     */
    static void activityGap() throws Exception {
        LocalDate day0 = java.time.Instant.ofEpochSecond(T0).atZone(ZONE).toLocalDate(); // T0 is shared with main
        java.util.Set<Integer> off = new java.util.HashSet<Integer>(Arrays.asList(3, 4, 5, 6));
        List<RingEvent> ev = history(10, off, new java.util.HashSet<Integer>(Arrays.asList(1, 2, 8, 9)));
        long now = T0 + 10 * 86400 + 7 * 3600 + 1800;
        Context c = new Context(Files.createTempDirectory("openring-gap").toFile());
        Db db = Db.get(c);
        for (int i = 0; i < ev.size(); i += 4096) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4096)), 1, now);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, T0 + (lastDs - DS0) / 10);
        Analyzer.run(c, null);
        int[] worn = new int[10];
        int[] score = new int[10];
        JSONObject[] targets = new JSONObject[10], freq = new JSONObject[10], vol = new JSONObject[10];
        for (int d = 0; d < 10; d++) {
            JSONObject o = Json.parse(db.dayJson(day0.plusDays(d).toString()));
            JSONObject as = o == null ? null : o.optJSONObject("activity_score");
            worn[d] = o == null ? -1 : o.optInt("activity_days", -1);
            score[d] = as == null ? -1 : as.optInt("value", -1);
            targets[d] = activityPart(as, "targets");
            freq[d] = activityPart(as, "frequency");
            vol[d] = activityPart(as, "volume");
        }
        System.out.println("  gap history: worn days " + Arrays.toString(worn) + ", activity " + Arrays.toString(score));
        check("gap: days without the ring have no activity score", score[3] < 0 && score[4] < 0 && score[5] < 0 && score[6] < 0, Arrays.toString(score));
        check("gap: the day after counts 3 worn days in the last 7", worn[7] == 3 && worn[8] == 3 && worn[9] == 3, Arrays.toString(worn));
        check("gap: targets count only the worn days", targets[7] != null && targets[7].optString("detail").endsWith(" of 3 days"), targets[7]);
        int met7 = targets[7] == null ? -1 : Integer.parseInt(targets[7].optString("detail").split(" ")[0]);
        double expect = app.openring.core.analysis.Stats.curve(met7 / 3.0, new double[][] {{0, 20}, {0.15, 35}, {0.43, 62}, {0.71, 86}, {1, 100}});
        check("gap: days without the ring are not missed targets", met7 >= 0 && targets[7].optLong("score") == Math.round(expect), targets[7] + " expected " + expect);
        check("gap: before the gap the same count", worn[2] == 3 && worn[1] == 2, Arrays.toString(worn));
        check("gap: the same kind of day scores about the same after the gap", score[9] > 0 && Math.abs(score[9] - score[2]) <= 3, score[2] + " / " + score[9]);
        // day 2 (days 0 to 2) and day 9 (days 7 to 9, the gap left out) both hold 1 training day in 3 worn days
        check("gap: training frequency below 100 and the same before and after", freq[2] != null && freq[9] != null
                && freq[2].optLong("score") < 100 && freq[2].optLong("score") == freq[9].optLong("score")
                && "1 training day in 3 days".equals(freq[9].optString("detail")), freq[2] + " / " + freq[9]);
        long fExpect = Math.round(app.openring.core.analysis.Stats.curve(7 / 3.0, new double[][] {{0, 35}, {1, 60}, {2, 82}, {3, 100}}));
        check("gap: one training day in 3 worn days is scaled to a week", freq[9] != null && freq[9].optLong("score") == fExpect, freq[9] + " expected " + fExpect);
        check("gap: training volume below 100 and about the same before and after", vol[2] != null && vol[9] != null
                && vol[9].optLong("score") < 100 && Math.abs(vol[2].optLong("score") - vol[9].optLong("score")) <= 3, vol[2] + " / " + vol[9]);
        check("gap: the day right after the gap is not pulled down", score[7] > 0 && Math.abs(score[7] - score[2]) <= 3, score[2] + " / " + score[7]);
        db.close();
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

    /**
     * Issues #33, #115 and #156: a backup holds every setting and restores only values Settings
     * allows, a key file may start with a byte order mark, a broken file gets a plain sentence; an
     * export is a consistent copy of everything committed, checked before it is called saved; a
     * restore refuses a history whose schema lacks a table or column, and the phone keeps its own.
     */
    static void backupGaps(byte[] full) throws Exception {
        byte[] dbBytes = zipEntry(full, "openring.db");
        // ---- #115: an export while an old reader blocks the checkpoint holds every committed row ----
        Context ec = freshDb("openring-b115");
        app.openring.data.Backup.read(ec, new java.io.ByteArrayInputStream(full));
        Db edb = Db.get(ec);
        wal(edb);
        java.sql.Connection reader = java.sql.DriverManager.getConnection("jdbc:sqlite:" + ec.getDatabasePath(Db.REAL).getPath());
        java.sql.Statement rs = reader.createStatement();
        rs.execute("BEGIN");
        rs.executeQuery("SELECT COUNT(*) FROM kv").close();
        for (int i = 0; i < 500; i++) edb.put("export_test_" + i, i);
        long kvRows = edb.count("SELECT COUNT(*) FROM kv"), evRows = edb.eventCount();
        java.io.ByteArrayOutputStream ex = new java.io.ByteArrayOutputStream();
        String exErr = null;
        try {
            app.openring.data.Exporter.database(ec, ex);
        } catch (java.io.IOException x) {
            exErr = x.getMessage();
        }
        long[] got = exErr == null ? countsKv(ex.toByteArray()) : new long[] {-1, -1};
        check("an export while another reader is open holds every committed row (#115)", exErr == null && got[0] == evRows && got[1] == kvRows,
                exErr != null ? exErr : got[0] + "/" + evRows + " events, " + got[1] + "/" + kvRows + " kv rows");
        for (boolean vacuum : new boolean[] {true, false}) {
            String way = vacuum ? "VACUUM INTO" : "copy under the write lock (Android 8 to 10)";
            java.io.File snap = snapshotWith(ec, vacuum);
            long[] sg = countsKv(Files.readAllBytes(snap.toPath()));
            check("snapshot by " + way + " with an old reader open holds every row", sg[0] == evRows && sg[1] == kvRows && quickCheck(snap)
                    && !new java.io.File(snap.getPath() + "-wal").exists(), sg[0] + " events, " + sg[1] + " kv rows");
            app.openring.data.Exporter.release(snap);
        }
        rs.execute("COMMIT");
        reader.close();
        exportLeft(ec, "after exports with an old reader");

        // ---- #115: an export while another connection keeps writing is one consistent moment ----
        for (boolean vacuum : new boolean[] {true, false}) {
            String way = vacuum ? "VACUUM INTO" : "copy under the write lock";
            for (int run = 0; run < 3; run++) {
                final String path = ec.getDatabasePath(Db.REAL).getPath();
                final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                final java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();
                final java.util.concurrent.atomic.AtomicReference<String> wErr = new java.util.concurrent.atomic.AtomicReference<String>();
                final int base = 1000000 * (run + 1) + (vacuum ? 0 : 500000);
                Thread w = new Thread(new Runnable() {
                    public void run() {
                        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
                             java.sql.Statement st = cn.createStatement()) {
                            st.execute("PRAGMA busy_timeout = 30000");
                            st.executeQuery("PRAGMA wal_autocheckpoint = 100").close();
                            for (int k = 0; !stop.get() || k < 200; k++) {
                                // one event and its marker in one transaction: a torn copy would hold one without the other
                                st.execute("BEGIN IMMEDIATE");
                                st.execute("INSERT INTO events(epoch, tag, ds, body, captured) VALUES (77, 70, " + (base + k) + ", x'0102" + String.format("%08x", k) + "', 1)");
                                st.execute("INSERT INTO kv(k, v) VALUES ('w_" + (base + k) + "', '" + k + "')");
                                st.execute("COMMIT");
                                written.incrementAndGet();
                            }
                        } catch (Exception x) {
                            wErr.set(x.toString());
                        }
                    }
                });
                w.start();
                while (written.get() < 100 && wErr.get() == null) Thread.sleep(1);
                long evBefore = Db.get(ec).eventCount();
                java.io.File snap = snapshotWith(ec, vacuum);
                stop.set(true);
                w.join();
                long writerEv = countIn(snap, "SELECT COUNT(*) FROM events WHERE epoch = 77"), writerKv = countIn(snap, "SELECT COUNT(*) FROM kv WHERE k LIKE 'w_%'");
                long allEv = countIn(snap, "SELECT COUNT(*) FROM events");
                check("snapshot by " + way + " while another connection writes (run " + (run + 1) + "): passes quick_check, one consistent moment",
                        wErr.get() == null && quickCheck(snap) && writerEv == writerKv && allEv >= evBefore && allEv < Db.get(ec).eventCount(),
                        wErr.get() != null ? wErr.get() : writerEv + " writer events, " + writerKv + " markers, " + allEv + " of " + evBefore + " before");
                app.openring.data.Exporter.release(snap);
            }
        }
        // a full backup made while another connection writes restores on a new phone
        final String bpath = ec.getDatabasePath(Db.REAL).getPath();
        final java.util.concurrent.atomic.AtomicBoolean bstop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread bw = new Thread(new Runnable() {
            public void run() {
                try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + bpath);
                     java.sql.Statement st = cn.createStatement()) {
                    st.execute("PRAGMA busy_timeout = 30000");
                    for (int k = 0; !bstop.get(); k++) st.execute("INSERT INTO kv(k, v) VALUES ('b_" + k + "', 'x')");
                } catch (Exception x) {
                    // the check below finds a backup that is not complete
                }
            }
        });
        bw.start();
        Thread.sleep(50);
        long bEvents = Db.get(ec).eventCount();
        java.io.ByteArrayOutputStream slow = new java.io.ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] b, int off, int len) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
                super.write(b, off, len);
            }
        };
        app.openring.data.Prefs.of(ec).saveKey(app.openring.core.Hex.parseKey("4431967d8bacc2659743142b68391d9a"));
        app.openring.data.Backup.write(ec, slow, true);
        bstop.set(true);
        bw.join();
        exportLeft(ec, "after a backup while another connection writes");
        Context nb = freshDb("openring-b115-new");
        app.openring.data.Backup.Result nbr = app.openring.data.Backup.read(nb, new java.io.ByteArrayInputStream(slow.toByteArray()));
        check("a full backup made while another connection writes restores with every event", nbr.ok && nbr.history && Db.get(nb).eventCount() == bEvents,
                nbr.message + ", " + Db.get(nb).eventCount() + " of " + bEvents);

        // ---- #115: a copy that fails its check is not saved, and nothing is written ----
        Context nc = freshDb("openring-b115-bad");
        app.openring.data.Backup.read(nc, new java.io.ByteArrayInputStream(full));
        Db.get(nc).getWritableDatabase().execSQL("DROP TABLE zones");
        for (int k = 0; k < 2; k++) {
            java.io.ByteArrayOutputStream none = new java.io.ByteArrayOutputStream();
            String why = null;
            try {
                if (k == 0) app.openring.data.Exporter.database(nc, none);
                else app.openring.data.Backup.write(nc, none, true);
            } catch (app.openring.data.Exporter.NotSaved x) {
                why = x.getMessage();
            }
            check((k == 0 ? "an export" : "a full backup") + " whose copy fails its check is not saved, nothing written (#115)",
                    why != null && why.contains("zones") && none.size() == 0, why + ", " + none.size() + " bytes");
        }
        exportLeft(nc, "after a copy that failed its check");
        java.nio.file.Files.write(new java.io.File(nc.getCacheDir(), "export-123.db").toPath(), new byte[] {1});
        java.nio.file.Files.write(new java.io.File(nc.getCacheDir(), "export-123.db-wal").toPath(), new byte[] {1});
        Db.get(nc).getWritableDatabase().execSQL("CREATE TABLE zones (start REAL PRIMARY KEY, stop REAL NOT NULL, id TEXT NOT NULL)");
        java.io.ByteArrayOutputStream fine = new java.io.ByteArrayOutputStream();
        app.openring.data.Exporter.database(nc, fine);
        check("an export after the history is whole again is saved", countsKv(fine.toByteArray())[0] == Db.get(nc).eventCount(), fine.size());
        exportLeft(nc, "after an export, with copies left by a stopped app");

        // ---- #33: stage detail and the finder sound travel with the backup ----
        Context a = freshDb("openring-b33a");
        app.openring.data.Backup.Result ra = app.openring.data.Backup.read(a, new java.io.ByteArrayInputStream(full));
        check("backup gaps: the main backup restores", ra.ok && ra.history, ra.message);
        app.openring.data.Prefs pa = app.openring.data.Prefs.of(a);
        pa.setStageDetail(app.openring.core.analysis.StageFilter.CALM);
        pa.setFinderSound(false);
        java.io.ByteArrayOutputStream ka = new java.io.ByteArrayOutputStream();
        app.openring.data.Backup.write(a, ka, false);
        Context b = freshDb("openring-b33b");
        app.openring.data.Backup.Result rb = app.openring.data.Backup.read(b, new java.io.ByteArrayInputStream(ka.toByteArray()));
        app.openring.data.Prefs pb = app.openring.data.Prefs.of(b);
        check("a backup keeps the stage detail setting (#33)", rb.ok && pb.stageDetail() == app.openring.core.analysis.StageFilter.CALM, pb.stageDetail());
        check("a backup keeps the ring finder sound switch", !pb.finderSound(), null);

        // a key and settings restore over a phone that keeps its own nights keeps its level (another one
        // would analyse its whole history again); with the history replaced the level comes along
        Context keep = freshDb("openring-b33keep");
        app.openring.data.Backup.read(keep, new java.io.ByteArrayInputStream(full));
        app.openring.data.Prefs pk = app.openring.data.Prefs.of(keep);
        int keptLevel = pk.stageDetail();
        check("a full restore: the stored stage detail matches the setting, so no full re analysis follows",
                Db.get(keep).kvLong("stage_detail", -1) == keptLevel, Db.get(keep).kvLong("stage_detail", -1) + " vs " + keptLevel);
        app.openring.data.Backup.Result kr = app.openring.data.Backup.read(keep, new java.io.ByteArrayInputStream(ka.toByteArray()));
        check("key and settings only over a phone with nights keeps its stage detail", kr.ok && pk.stageDetail() == keptLevel
                && !pk.finderSound(), pk.stageDetail() + " vs " + keptLevel);
        // a phone history that cannot be read (here: a file that is not a database) keeps the phone's level too
        Context unread = freshDb("openring-b33unread");
        java.nio.file.Files.write(unread.getDatabasePath(Db.REAL).toPath(), "not a database at all, but a file".getBytes("UTF-8"));
        app.openring.data.Prefs pu = app.openring.data.Prefs.of(unread);
        pu.setStageDetail(app.openring.core.analysis.StageFilter.DETAILED);
        app.openring.data.Backup.Result ur = app.openring.data.Backup.read(unread, new java.io.ByteArrayInputStream(ka.toByteArray()));
        check("key and settings only over a phone history that cannot be read keeps its stage detail", ur.ok
                && pu.stageDetail() == app.openring.core.analysis.StageFilter.DETAILED, ur.message + ", " + pu.stageDetail());
        java.io.ByteArrayOutputStream fa = new java.io.ByteArrayOutputStream();
        app.openring.data.Backup.write(a, fa, true);
        Context fb = freshDb("openring-b33full");
        app.openring.data.Backup.read(fb, new java.io.ByteArrayInputStream(fa.toByteArray()));
        check("a full restore takes the backup's stage detail", app.openring.data.Prefs.of(fb).stageDetail() == app.openring.core.analysis.StageFilter.CALM,
                app.openring.data.Prefs.of(fb).stageDetail());

        // ---- #33: a key file saved with a byte order mark (Windows Notepad) ----
        byte[] key = app.openring.core.Hex.parseKey("4431967d8bacc2659743142b68391d9a");
        Context c = freshDb("openring-b33c");
        app.openring.data.Backup.Result bom = app.openring.data.Backup.read(c, new java.io.ByteArrayInputStream("\uFEFF4431967d8bacc2659743142b68391d9a\r\n".getBytes("UTF-8")));
        check("a key file with a byte order mark is accepted (#33)", bom.ok && java.util.Arrays.equals(app.openring.data.Prefs.of(c).loadKey(), key), bom.message);
        app.openring.data.Backup.Result bomJson = app.openring.data.Backup.read(c, new java.io.ByteArrayInputStream(
                zipWithJson("\uFEFF{\"key\":\"4431967d8bacc2659743142b68391d9a\",\"screen_goal\":90}")));
        check("a backup JSON with a byte order mark is read as JSON", bomJson.ok && app.openring.data.Prefs.of(c).screenGoalMin() == 90, bomJson.message);

        // ---- #33: restored values stay inside what Settings allows ----
        Context d = freshDb("openring-b33d");
        app.openring.data.Backup.Result rr = app.openring.data.Backup.read(d, new java.io.ByteArrayInputStream(zipWithJson(
                "{\"key\":\"4431967d8bacc2659743142b68391d9a\",\"profile\":{\"age\":500,\"sex\":7,\"weight\":0,\"height\":1000,\"target_kcal\":99999},"
                + "\"screen_goal\":-5,\"stage_detail\":9,\"screen_min_show\":7}")));
        app.openring.data.Prefs pd = app.openring.data.Prefs.of(d);
        check("restored values are kept in range (#33)", rr.ok && pd.age() == 100 && pd.weightKg() == 30 && pd.heightCm() == 230 && pd.targetKcal() == 3000
                && pd.sex() == 2 && pd.screenGoalMin() == 0 && pd.screenMinShowMin() == 5
                && pd.stageDetail() == app.openring.core.analysis.StageFilter.BALANCED,
                pd.age() + " " + pd.sex() + " " + pd.weightKg() + " " + pd.heightCm() + " " + pd.targetKcal() + " " + pd.screenGoalMin() + " " + pd.stageDetail());

        // ---- #33: broken files get a plain sentence, not Java's text ----
        String cut = null, big = null;
        try {
            cut = app.openring.data.Backup.read(d, new java.io.ByteArrayInputStream(java.util.Arrays.copyOf(full, full.length / 2))).message;
        } catch (java.io.IOException e) {
            cut = "thrown: " + e.getMessage();
        }
        try {
            byte[] huge = new byte[(1 << 20) + 10];
            java.util.Arrays.fill(huge, (byte) 'a');
            big = app.openring.data.Backup.read(d, new java.io.ByteArrayInputStream(huge)).message;
        } catch (java.io.IOException e) {
            big = "thrown: " + e.getMessage();
        }
        check("a cut off backup file gets a plain sentence (#33)", PLAIN_BROKEN.equals(cut), cut);
        check("a file over 1 MB gets a plain sentence (#33)", PLAIN_BROKEN.equals(big), big);
        noTemp(d, "after broken files");

        // ---- #156: backups whose schema lacks a table or column are refused whole ----
        Context ph = freshDb("openring-b156");
        app.openring.data.Backup.Result r0 = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(full));
        Db pdb = Db.get(ph);
        long events = pdb.eventCount(), nights = pdb.count("SELECT COUNT(*) FROM nights");
        check("schema check: the phone has a history", r0.ok && events > 1000 && nights > 0, events + " events, " + nights + " nights");
        String evNoSeg = "CREATE TABLE ev2 (id INTEGER PRIMARY KEY, epoch INTEGER NOT NULL, tag INTEGER NOT NULL, ds INTEGER NOT NULL, body BLOB NOT NULL,"
                + " captured INTEGER NOT NULL, unix REAL, UNIQUE(tag, ds, body) ON CONFLICT IGNORE)";
        String evExtra = "CREATE TABLE ev2 (id INTEGER PRIMARY KEY, epoch INTEGER NOT NULL, tag INTEGER NOT NULL, ds INTEGER NOT NULL, body BLOB NOT NULL,"
                + " captured INTEGER NOT NULL, unix REAL, seg INTEGER, extra INTEGER NOT NULL, UNIQUE(tag, ds, body) ON CONFLICT IGNORE)";
        Object[][] broken = {
            {"zones dropped (v6)", alter(dbBytes, 6, "DROP TABLE zones")},
            {"screen_reads dropped (v6)", alter(dbBytes, 6, "DROP TABLE screen_reads")},
            {"zones dropped (v5)", alter(dbBytes, 5, "DROP TABLE screen_reads", "DROP TABLE zones")},
            {"screen and apps dropped (v5)", alter(dbBytes, 5, "DROP TABLE screen_reads", "DROP TABLE screen", "DROP TABLE apps")},
            {"only events, nights, days and kv (v5)", alter(dbBytes, 5, "DROP TABLE screen_reads", "DROP TABLE screen", "DROP TABLE apps",
                    "DROP TABLE zones", "DROP TABLE anchors", "DROP TABLE battery")},
            {"events without seg (v5)", alter(dbBytes, 5, "DROP TABLE screen_reads", evNoSeg,
                    "INSERT INTO ev2 SELECT id, epoch, tag, ds, body, captured, unix FROM events", "DROP TABLE events", "ALTER TABLE ev2 RENAME TO events")},
            {"events with an extra NOT NULL column (v6)", alter(dbBytes, 6, evExtra,
                    "INSERT INTO ev2 SELECT id, epoch, tag, ds, body, captured, unix, seg, 0 FROM events", "DROP TABLE events", "ALTER TABLE ev2 RENAME TO events")},
            {"v3 without anchors and battery", alter(dbBytes, 3, "DROP TABLE screen_reads", "DROP TABLE zones", "DROP TABLE apps",
                    "DROP TABLE anchors", "DROP TABLE battery")},
            {"kv without its value column (v6)", alter(dbBytes, 6, "DROP TABLE kv", "CREATE TABLE kv (k TEXT PRIMARY KEY)")},
            {"zones as a view (v6)", alter(dbBytes, 6, "DROP TABLE zones", "CREATE VIEW zones AS SELECT start, stop, '' AS id FROM screen")},
        };
        for (Object[] x : broken) {
            app.openring.data.Backup.Plan p = app.openring.data.Backup.prepare(ph, new java.io.ByteArrayInputStream(withDb(full, (byte[]) x[1])));
            app.openring.data.Backup.Result r = app.openring.data.Backup.finish(ph, p, true);
            check("schema check: " + x[0] + " is refused as damaged (#156)", !r.ok && r.message.contains("damaged, nothing was changed"), r.message);
            Db now = Db.get(ph);
            check("schema check: the phone keeps its events and nights after " + x[0], now.eventCount() == events
                    && now.count("SELECT COUNT(*) FROM nights") == nights, now.eventCount() + " events, " + now.count("SELECT COUNT(*) FROM nights") + " nights");
            noTemp(ph, "after " + x[0]);
        }
        // a swap that bypasses the check: the opened file lacks a table, so the old history comes back
        java.io.File noZones = fileOf(alter(dbBytes, 6, "DROP TABLE zones"));
        String swapErr = null;
        try {
            Db.replaceWith(ph, noZones, events);
        } catch (java.io.IOException x) {
            swapErr = x.getMessage();
        }
        check("a swap whose opened history lacks a table is rolled back with every event and night (#156)", swapErr != null
                && Db.get(ph).eventCount() == events && Db.get(ph).count("SELECT COUNT(*) FROM nights") == nights
                && !new java.io.File(ph.getDatabasePath(Db.REAL).getPath() + ".old").exists(), swapErr);
        // analysis, the data check and Delete all still work on the kept history
        Analyzer.run(ph, null);
        DataCheck.run(ph);
        check("schema check: the kept history still analyses", Db.get(ph).kv("analysis_error", null) == null, Db.get(ph).kv("analysis_error", null));

        // ---- restores of a backup made by this version and by older schema versions ----
        Object[][] good = {
            {"this version (v6)", dbBytes},
            {"v5", alter(dbBytes, 5, "DROP TABLE screen_reads")},
            {"v4", alter(dbBytes, 4, "DROP TABLE screen_reads", "DROP TABLE zones")},
            {"v3", alter(dbBytes, 3, "DROP TABLE screen_reads", "DROP TABLE zones", "DROP TABLE apps")},
            {"v2", alter(dbBytes, 2, "DROP TABLE screen_reads", "DROP TABLE zones", "DROP TABLE apps", "DROP TABLE screen")},
        };
        long[] want = counts(fileOf(dbBytes));
        for (Object[] x : good) {
            Context g = freshDb("openring-good");
            app.openring.data.Backup.Result r = app.openring.data.Backup.read(g, new java.io.ByteArrayInputStream(withDb(full, (byte[]) x[1])));
            Db gd = Db.get(g);
            boolean same = gd.eventCount() == want[0] && gd.count("SELECT COUNT(*) FROM nights") == want[1] && gd.count("SELECT COUNT(*) FROM days") == want[2]
                    && gd.count(EVENTS_SUM) == countIn(fileOf(dbBytes), EVENTS_SUM) && gd.count(NIGHTS_SUM) == countIn(fileOf(dbBytes), NIGHTS_SUM);
            check("a backup of " + x[0] + " restores with every event, night and day", r.ok && r.history && same && gd.getReadableDatabase().getVersion() == 6,
                    r.message + ", " + gd.eventCount() + " events");
            Analyzer.run(g, null);
            check("a restored backup of " + x[0] + " analyses and keeps its events", gd.kv("analysis_error", null) == null && gd.eventCount() == want[0],
                    gd.kv("analysis_error", null));
            gd.resetAll();
        }

    }

    /** {@code Exporter.snapshot} with the way forced (package private in the app). */
    static java.io.File snapshotWith(Context c, boolean vacuum) throws Exception {
        java.lang.reflect.Method m = app.openring.data.Exporter.class.getDeclaredMethod("snapshot", Context.class, Boolean.class);
        m.setAccessible(true);
        try {
            return (java.io.File) m.invoke(null, c, vacuum);
        } catch (java.lang.reflect.InvocationTargetException x) {
            throw (Exception) x.getCause();
        }
    }

    static boolean quickCheck(java.io.File f) throws Exception {
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement(); java.sql.ResultSet r = st.executeQuery("PRAGMA quick_check")) {
            return r.next() && "ok".equals(r.getString(1));
        }
    }

    static long countIn(java.io.File f, String sql) throws Exception {
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement(); java.sql.ResultSet r = st.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    static void exportLeft(Context c, String when) {
        String[] left = c.getCacheDir().list(new java.io.FilenameFilter() {
            public boolean accept(java.io.File d, String n) {
                return n.startsWith("export-");
            }
        });
        check("no export copy left " + when, left != null && left.length == 0, left == null ? null : java.util.Arrays.toString(left));
    }

    /** Fingerprints of every event's counter and body and of every stored night. */
    static final String EVENTS_SUM = "SELECT SUM(ds) + SUM(LENGTH(body)) + SUM(tag) FROM events",
            NIGHTS_SUM = "SELECT SUM(LENGTH(json)) + SUM(CAST(start AS INTEGER)) FROM nights";

    static final String PLAIN_BROKEN = "This file is damaged or is not an Open Ring backup, nothing was changed.";

    /** A copy of a history file changed by {@code sql}, at schema {@code version}. */
    static byte[] alter(byte[] db, int version, String... sql) throws Exception {
        java.io.File f = fileOf(db);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            for (String s : sql) st.execute(s);
            st.execute("PRAGMA user_version = " + version);
        }
        return Files.readAllBytes(f.toPath());
    }

    static java.io.File fileOf(byte[] db) throws Exception {
        java.io.File f = Files.createTempFile("alter", ".db").toFile();
        Files.write(f.toPath(), db);
        return f;
    }

    /** Events and kv rows in a database file given as bytes. */
    static long[] countsKv(byte[] db) throws Exception {
        java.io.File f = fileOf(db);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            long[] out = new long[2];
            try (java.sql.ResultSet r = st.executeQuery("SELECT COUNT(*) FROM events")) {
                r.next();
                out[0] = r.getLong(1);
            }
            try (java.sql.ResultSet r = st.executeQuery("SELECT COUNT(*) FROM kv")) {
                r.next();
                out[1] = r.getLong(1);
            }
            return out;
        }
    }

    /** Write ahead logging on, as on the phone (the stand in helper leaves it off). */
    static void wal(Db db) {
        android.database.Cursor cur = db.getWritableDatabase().rawQuery("PRAGMA journal_mode=WAL", null);
        cur.moveToFirst();
        cur.close();
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

    /** Issue #46: on the day the clocks go back a use at 03:30 is drawn at 03:30 on the clock and the row. */
    static void dialClockChange() throws Exception {
        Db db = Db.get(freshDb("openring-dial"));
        ZoneId z = ZoneId.of("Europe/Zurich");
        double t = java.time.LocalDateTime.parse("2026-10-25T03:30:00").atZone(z).toEpochSecond();
        List<double[]> s = new ArrayList<double[]>();
        s.add(new double[] {t, t + 300});
        db.putScreen(s);
        app.openring.data.ScreenDays.Info in = app.openring.data.ScreenDays.info(db, LocalDate.parse("2026-10-25"), z, 180, new java.util.HashSet<String>());
        int lit = -1;
        for (int i = 0; i < in.dial.length; i++) if (in.dial[i] > 0) lit = lit == -1 ? i : -2;
        check("clocks back: a use at 03:30 is on the 03:30 line of the clock", lit == 42 && in.day.totalMin > 4.9, lit);
        app.openring.data.ScreenDays.Row row = in.rows[in.rows.length - 1];
        check("and on the 03:30 part of the day row", row.fill[14] > 0 && row.fill[13] == 0 && row.zone.equals(z), null);
        // issue #129: uses in both passes of the repeated hour share the dial there, so it shows less than the
        // total, but never more than an hour less (an empty dial must fail)
        List<double[]> both = new ArrayList<double[]>();
        both.add(new double[] {java.time.Instant.parse("2026-10-25T00:10:00Z").getEpochSecond(), java.time.Instant.parse("2026-10-25T00:20:00Z").getEpochSecond()});
        both.add(new double[] {java.time.Instant.parse("2026-10-25T01:10:00Z").getEpochSecond(), java.time.Instant.parse("2026-10-25T01:20:00Z").getEpochSecond()});
        both.add(new double[] {java.time.LocalDateTime.parse("2026-10-25T09:00:00").atZone(z).toEpochSecond(), java.time.LocalDateTime.parse("2026-10-25T10:00:00").atZone(z).toEpochSecond()});
        db.putScreen(both);
        app.openring.data.ScreenDays.Info lng = app.openring.data.ScreenDays.info(db, LocalDate.parse("2026-10-25"), z, 180, new java.util.HashSet<String>());
        double dialMin = 0;
        for (double v : lng.dial) dialMin += v * 5;
        check("clocks back: the dial shows the day less the shared steps, at most an hour less",
                lng.to - lng.from > 86400 + 1 && dialMin >= lng.day.totalMin - 60 && dialMin < lng.day.totalMin - 1 && Math.abs(lng.day.totalMin - 85) < 0.01,
                dialMin + " vs " + lng.day.totalMin);
        // a night through the repeated hour, 02:40 summer time to 02:20 winter time: the row keeps its width
        db.replaceNights(0, Double.MAX_VALUE, java.util.Collections.singletonList(new Object[] {
            (double) java.time.Instant.parse("2026-10-25T00:40:00Z").getEpochSecond(), (double) java.time.Instant.parse("2026-10-25T01:20:00Z").getEpochSecond(),
            "2026-10-25", 1, 80, "{\"start\":" + java.time.Instant.parse("2026-10-25T00:40:00Z").getEpochSecond() + ",\"end\":"
                + java.time.Instant.parse("2026-10-25T01:20:00Z").getEpochSecond() + ",\"sleep_start\":" + java.time.Instant.parse("2026-10-25T00:40:00Z").getEpochSecond()
                + ",\"sleep_end\":" + java.time.Instant.parse("2026-10-25T01:20:00Z").getEpochSecond() + "}"}));
        in = app.openring.data.ScreenDays.info(db, LocalDate.parse("2026-10-25"), z, 180, new java.util.HashSet<String>());
        row = in.rows[in.rows.length - 1];
        int covered = 0;
        for (double v : row.nightFill) if (v > 0) covered++;
        check("a night inside the repeated hour keeps its width on the row", row.nights.size() == 1 && covered == 8, covered + " steps of 5 minutes");
    }

    /** Issue #71: app rows of one app merge when stored and when read, the list adds up, totals stay. */
    static void appRowsStored() throws Exception {
        Db db = Db.get(freshDb("openring-apps"));
        ZoneId z = ZoneId.of("UTC");
        LocalDate day = LocalDate.parse("2026-10-14");
        double t0 = day.atStartOfDay(z).toEpochSecond() + 10 * 3600;
        String d = "com.dialer", m = "com.maps";
        // the two reads of the issue: [1005, 1100] then [1010, 1200]
        java.util.Map<String, List<double[]>> r1 = new java.util.LinkedHashMap<String, List<double[]>>();
        r1.put(d, new ArrayList<double[]>(java.util.Arrays.asList(new double[] {t0 + 1005, t0 + 1100})));
        java.util.Map<String, List<double[]>> r2 = new java.util.LinkedHashMap<String, List<double[]>>();
        r2.put(d, new ArrayList<double[]>(java.util.Arrays.asList(new double[] {t0 + 1010, t0 + 1200})));
        db.putApps(r1);
        db.putApps(r2);
        db.putApps(r2);
        java.util.Map<String, List<double[]>> core = ScreenTime.store(ScreenTime.store(new java.util.LinkedHashMap<String, List<double[]>>(), r1), r2);
        check("stored twice: one dialer row, as the core stores it", db.count("SELECT COUNT(*) FROM apps") == 1
                && db.count("SELECT MIN(start) FROM apps") == (long) (t0 + 1005) && db.count("SELECT MAX(stop) FROM apps") == (long) (t0 + 1200)
                && core.get(d).size() == 1 && core.get(d).get(0)[1] == t0 + 1200, db.count("SELECT COUNT(*) FROM apps"));
        check("newest app start for the next read", db.appsNewest() == t0 + 1005, db.appsNewest());
        android.database.Cursor plan = db.getReadableDatabase().rawQuery("EXPLAIN QUERY PLAN SELECT start, stop FROM apps INDEXED BY apps_stop WHERE stop >= ? AND pkg = ? AND start <= ?",
                new String[] {"1", d, "2"});
        String how = "";
        while (plan.moveToNext()) how += plan.getString(3);
        plan.close();
        check("merging looks only at rows since the new range starts (index on stop)", how.contains("apps_stop") && how.contains("stop>?"), how);
        // rows stored doubled by an older version: read merged, stored rows untouched until new ones join
        db.getWritableDatabase().execSQL("DELETE FROM apps");
        db.getWritableDatabase().execSQL("INSERT INTO apps (pkg, start, stop) VALUES (?, ?, ?)", new Object[] {d, t0 + 1005, t0 + 1100});
        db.getWritableDatabase().execSQL("INSERT INTO apps (pkg, start, stop) VALUES (?, ?, ?)", new Object[] {d, t0 + 1010, t0 + 1200});
        db.getWritableDatabase().execSQL("INSERT INTO apps (pkg, start, stop) VALUES (?, ?, ?)", new Object[] {m, t0 + 1300, t0 + 1400});
        List<double[]> s = new ArrayList<double[]>();
        s.add(new double[] {t0 + 1010, t0 + 1201});
        s.add(new double[] {t0 + 1300, t0 + 1400});
        db.putScreen(s);
        check("old doubled rows are read as one", db.apps(0, Double.MAX_VALUE).get(d).size() == 1 && db.count("SELECT COUNT(*) FROM apps") == 3, null);
        java.util.Set<String> none = new java.util.HashSet<String>(), outD = new java.util.HashSet<String>(java.util.Arrays.asList(d));
        app.openring.data.ScreenDays.Info a = app.openring.data.ScreenDays.info(db, day, z, 180, none);
        app.openring.data.ScreenDays.Info b = app.openring.data.ScreenDays.info(db, day, z, 180, outD);
        double dial = -1;
        for (java.util.Map.Entry<String, Double> e : a.apps) if (e.getKey().equals(d)) dial = e.getValue();
        check("the dialer shows 3.2 min, not more than the phone use", Math.abs(dial - 190 / 60.0) < 1e-6 && dial <= a.rawMin, dial);
        long shown = 0;
        for (ScreenTime.AppRow r : a.appRows) shown += r.shownMin;
        check("the rows add up to the total", shown == Math.round(a.day.totalMin), shown + " of " + a.day.totalMin);
        long counted = 0, left = 0;
        for (ScreenTime.AppRow r : b.appRows) {
            if (r.excluded) left += r.shownMin;
            else counted += r.shownMin;
        }
        check("left out: the rows add up to the total and to Not counted", counted == Math.round(b.day.totalMin) && left == Math.round(b.excludedMin)
                && left == 3, counted + " / " + left);
        // a new read that joins the old rows: totals, score and the list stay the same
        java.util.Map<String, List<double[]>> r3 = new java.util.LinkedHashMap<String, List<double[]>>();
        r3.put(d, new ArrayList<double[]>(java.util.Arrays.asList(new double[] {t0 + 1150, t0 + 1160})));
        db.putApps(r3);
        check("a new range joins every old row it reaches", db.count("SELECT COUNT(*) FROM apps WHERE pkg = '" + d + "'") == 1, null);
        app.openring.data.ScreenDays.Info a2 = app.openring.data.ScreenDays.info(db, day, z, 180, none);
        app.openring.data.ScreenDays.Info b2 = app.openring.data.ScreenDays.info(db, day, z, 180, outD);
        check("totals and score stay the same after merging", a2.day.totalMin == a.day.totalMin && b2.day.totalMin == b.day.totalMin
                && b2.excludedMin == b.excludedMin && a2.score.value == a.score.value && b2.score.value == b.score.value
                && a2.rawMin == a.rawMin, b.day.totalMin + " / " + b2.day.totalMin);
        check("with no app left out the total is all phone use", Math.abs(a.day.totalMin - a.rawMin) < 1e-9 && Math.abs(a.day.totalMin - 291 / 60.0) < 1e-9, a.day.totalMin);
    }

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
        check("trip " + when + ": all nights at one detail level", h.stageDetail == main.get(0).optInt("stage_detail", -9) && h.stageDetail >= 0, h.stageDetail);
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

    /**
     * The pipeline runs SQLite 3.41, but the app's minSdk 26 (Android 8.0) ships SQLite 3.18 and Android 13
     * still 3.32 (code review of PR #145). Fail on SQL in the app's code that older phones refuse: HAVING
     * without GROUP BY (3.39), UPSERT (3.24), window functions and RENAME COLUMN (3.25), NULLS FIRST or LAST
     * (3.30), generated columns (3.31), IIF (3.32), RETURNING and DROP COLUMN (3.35), STRICT tables (3.37),
     * JSON arrows and unixepoch (3.38).
     */
    static void sqlForOldPhones() throws Exception {
        List<String> found = new ArrayList<String>();
        List<java.nio.file.Path> files = new ArrayList<java.nio.file.Path>();
        try (java.util.stream.Stream<java.nio.file.Path> w = Files.walk(java.nio.file.Paths.get("app/src/main/java"))) {
            w.filter(x -> x.toString().endsWith(".java")).forEach(files::add);
        }
        for (java.nio.file.Path f : files) {
            found.addAll(oldSqlProblems(f.getFileName().toString(), new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8)));
        }
        check("SQL in the app runs on Android 8 to 13 (SQLite 3.18 and later)", found.isEmpty() && files.size() > 20, found);
        // the check finds the statement the review found, split over two lines as in the code
        List<String> bad = oldSqlProblems("x", "db.execSQL(\"INSERT OR REPLACE INTO screen_reads(start, stop) SELECT MIN(start), MAX(stop)\"\n"
                + "    + \" FROM screen HAVING COUNT(*) > 0\");\nc = db.rawQuery(\"INSERT INTO t(a) VALUES (1) ON CONFLICT(a) DO NOTHING\", null);");
        check("the check finds HAVING without GROUP BY and an UPSERT", bad.size() == 2, bad);
        check("the check finds VACUUM INTO without the version check (#115)", oldSqlProblems("x", "d.execSQL(\"VACUUM INTO ?\", a);").size() == 1
                && oldSqlProblems("x", "// only from SQLite 3.27\nd.execSQL(\"VACUUM INTO ?\", a);").isEmpty(), null);
        check("HAVING with GROUP BY is fine", oldSqlProblems("x", "db.rawQuery(\"SELECT a FROM t GROUP BY a HAVING COUNT(*) > 1\", null);").isEmpty(), null);
    }

    static List<String> oldSqlProblems(String name, String src) {
        String[] newer = {"DO UPDATE", "DO NOTHING", " OVER (", " OVER(", "RENAME COLUMN", "NULLS FIRST", "NULLS LAST", "GENERATED ALWAYS",
            "IIF(", "RETURNING", "DROP COLUMN", ") STRICT", "->>", "UNIXEPOCH("};
        List<String> found = new ArrayList<String>();
        // each Java statement on its own, so SQL split over concatenated lines is read as one
        for (String st : src.split(";")) {
            if (!st.contains("\"")) continue;
            String u = st.toUpperCase(java.util.Locale.ROOT);
            if (!u.contains("EXECSQL") && !u.contains("RAWQUERY") && !u.contains("SELECT ") && !u.contains("INSERT ")) continue;
            if (u.contains("HAVING ") && !u.contains("GROUP BY")) found.add(name + ": HAVING without GROUP BY");
            for (String n : newer) if (u.contains(n)) found.add(name + ": " + n.trim());
            // VACUUM INTO came with SQLite 3.27 (Android 11): only behind the version check, which the statement names
            if (u.contains("VACUUM INTO") && !st.contains("3.27")) found.add(name + ": VACUUM INTO without the 3.27 check");
        }
        return found;
    }

    /**
     * Issue #131: days the phone's usage log was not read for (the app not opened for longer than Android
     * keeps it) are unknown, not 0, with no score; a database from before the change keeps its days.
     */
    static void screenGaps(byte[] full) throws Exception {
        ZoneId z = ZONE;
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        Db db = Db.get(freshDb("openring-screen-gaps"));
        // 20 minutes at 12:00 on days 1 to 3 and 10 to 12
        List<double[]> s = new ArrayList<double[]>();
        for (int k : new int[] {1, 2, 3, 10, 11, 12}) {
            double noon = d1.plusDays(k - 1).atStartOfDay(z).toEpochSecond() + 12 * 3600;
            s.add(new double[] {noon, noon + 1200});
        }
        db.putScreen(s);
        double day1 = d1.atStartOfDay(z).toEpochSecond();
        // read from day 1 08:00 to day 4 10:00, then not before day 9 23:00 (Android no longer had the days between)
        db.putScreenRead(day1 + 8 * 3600, day1 + 3 * 86400 + 10 * 3600);
        db.putScreenRead(day1 + 8 * 86400 + 23 * 3600, day1 + 10 * 86400);
        db.putScreenRead(day1 + 10 * 86400, day1 + 11 * 86400 + 20 * 3600); // touches the read before: one range
        check("reads that touch are one range", db.screenReads().size() == 2, db.screenReads().size());
        // looked at on the evening of day 12, so day 12 is today and counts up to the last read
        double eve = day1 + 11 * 86400 + 21 * 3600;
        double[] w = app.openring.data.ScreenDays.lastDays(db, d1.plusDays(11), 12, z, eve);
        boolean gap = true, known = true;
        for (int i = 3; i <= 8; i++) gap &= Double.isNaN(w[i]);
        for (int i : new int[] {0, 1, 2, 9, 10, 11}) known &= Math.abs(w[i] - 20) < 0.01;
        check("days 4 to 9, not read, are unknown, not 0", gap, Arrays.toString(w));
        check("days 1 to 3 and 10 to 12 keep their minutes (first and last day up to the reads)", known, Arrays.toString(w));
        check("a day in the gap has no screen day", app.openring.data.ScreenDays.day(db, d1.plusDays(5), z, eve) == null
                && app.openring.data.ScreenDays.day(db, d1.plusDays(1), z, eve) != null, null);
        app.openring.data.ScreenDays.Info in = app.openring.data.ScreenDays.info(db, d1.plusDays(5), z, 180, new java.util.HashSet<String>(), 0, eve);
        check("a gap day has no score and says why", in.day == null && in.score == null && in.coverage == ScreenTime.READ_GAP, in.coverage);
        int nanRows = 0;
        for (app.openring.data.ScreenDays.Row r : in.rows) if (Double.isNaN(r.totalMin)) nanRows++;
        // rows: 14 days ending on day 6, so 8 days before day 1 and days 4 to 6
        check("the day rows show the gap as unknown", nanRows == 8 + 3 && Math.abs(in.rows[13 - 4].totalMin - 20) < 0.01, nanRows);
        app.openring.data.ScreenDays.Info later = app.openring.data.ScreenDays.info(db, d1.plusDays(13), z, 180, new java.util.HashSet<String>(), 0, eve);
        check("a day after the last read is not read yet", later.day == null && later.score == null && later.coverage == ScreenTime.AFTER_READS, later.coverage);
        app.openring.data.ScreenDays.Info ok = app.openring.data.ScreenDays.info(db, d1.plusDays(10), z, 180, new java.util.HashSet<String>(), 0, eve);
        check("a read day still has its score", ok.day != null && ok.score != null && ok.coverage == ScreenTime.COVERED, ok.coverage);
        // a day later, with usage access switched off since: day 12, read only until 20:00, gets no number or score
        app.openring.data.ScreenDays.Info part = app.openring.data.ScreenDays.info(db, d1.plusDays(11), z, 180, new java.util.HashSet<String>(), 0, eve + 86400);
        check("a past day read only part way is not known", part.day == null && part.score == null && part.coverage == ScreenTime.AFTER_READS
                && Double.isNaN(app.openring.data.ScreenDays.lastDays(db, d1.plusDays(11), 1, z, eve + 86400)[0]), part.coverage);
        check("the same day while it is today counts up to the last read",
                Math.abs(app.openring.data.ScreenDays.info(db, d1.plusDays(11), z, 180, new java.util.HashSet<String>(), 0, eve).day.totalMin - 20) < 0.01, null);
        db.resetAll();
        check("delete all local data deletes the reads", db.screenReads().isEmpty(), db.screenReads().size());

        // a database from before the change (schema 5): its stored days stay known
        byte[] cur = zipEntry(full, "openring.db");
        java.io.File f = Files.createTempFile("v5", ".db").toFile();
        Files.write(f.toPath(), cur);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS screen_reads");
            st.execute("DELETE FROM screen");
            for (double[] x : s) st.execute("INSERT INTO screen(start, stop) VALUES (" + x[0] + ", " + x[1] + ")");
            st.execute("PRAGMA user_version = 5");
        }
        Context c = freshDb("openring-v5");
        Files.write(c.getDatabasePath(Db.REAL).toPath(), Files.readAllBytes(f.toPath()));
        Db old = Db.get(c);
        List<double[]> seeded = old.screenReads();
        check("a schema 5 database gets one read from its oldest to its newest session", old.getReadableDatabase().getVersion() == 6
                && seeded.size() == 1 && seeded.get(0)[0] == s.get(0)[0] && seeded.get(0)[1] == s.get(s.size() - 1)[1], seeded.size());
        double[] ow = app.openring.data.ScreenDays.lastDays(old, d1.plusDays(11), 12, z, eve);
        boolean oldKnown = !Double.isNaN(ow[0]) && Math.abs(ow[5]) < 0.01 && Math.abs(ow[11] - 20) < 0.01;
        check("its days are shown as before (old gaps cannot be found any more)", oldKnown, Arrays.toString(ow));
        // the first read after the update, two weeks later: Android has only the last 9 days
        double now = d1.plusDays(25).atStartOfDay(z).toEpochSecond() + 10 * 3600;
        double start = ScreenTime.readStart(s.get(s.size() - 1)[0], now - 9 * 86400, now);
        old.putScreenRead(start, now);
        double[] nw = app.openring.data.ScreenDays.lastDays(old, d1.plusDays(25), 26, z, now);
        boolean between = true;
        for (int i = 11; i <= 16; i++) between &= Double.isNaN(nw[i]);
        check("time between the old data and what Android still had is unknown", between && Math.abs(nw[10] - 20) < 0.01
                && Math.abs(nw[17]) < 0.01 && Math.abs(nw[25]) < 0.01, Arrays.toString(nw));
    }

    /** Schema 4 to 5 (#22): an old database and an old full backup still open, with all rows and an empty zone table. */
    static void zoneStorage(byte[] full) throws Exception {
        byte[] v5 = zipEntry(full, "openring.db");
        java.io.File f = Files.createTempFile("v4", ".db").toFile();
        Files.write(f.toPath(), v5);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("DROP TABLE zones");
            st.execute("DROP TABLE IF EXISTS screen_reads");
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
        check("it becomes the current schema with an empty zone table", db.getReadableDatabase().getVersion() == 6 && db.zones().isEmpty()
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
        app.openring.data.Backup.Result rn = app.openring.data.Backup.read(freshDb("openring-v6"), new java.io.ByteArrayInputStream(withDb(full, withVersion(v5, 7))));
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

    /**
     * Issue #72: on every demo day the Body tab's Lowest, Average and Highest are the stored line's own lowest point,
     * mean and highest point, with every stored point inside the day the chart draws, and the day's lowest stays near
     * the night's lowest (5 minute averages).
     */
    static void dayHeartChecks(Db db) {
        int days = 0, matched = 0, nearNight = 0, withNight = 0;
        String bad = "";
        for (String[] r : db.daysJson("2000-01-01", "2100-01-01")) {
            JSONObject o = Json.parse(r[1]);
            if (o == null || o.optJSONArray("hr_day") == null) continue;
            days++;
            app.openring.core.analysis.TS line = Json.series(o.optJSONArray("hr_day"));
            LocalDate day = LocalDate.parse(r[0]);
            // what the Body tab shows: worked out from the stored line over the day the chart draws
            app.openring.core.analysis.DayHeart dh = app.openring.core.analysis.DayHeart.of(line,
                    day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond());
            double low = dh.low, avg = dh.avg, high = dh.high;
            boolean ok = dh.points == line.size() && high >= line.max() - 1e-9 && Math.abs(high - line.max()) < 0.01 && Math.abs(low - line.min()) < 0.01
                    && Math.abs(avg - line.mean()) < 0.01;
            for (int i = 0; i < line.size(); i++) if (line.v(i) > high + 1e-9 || line.v(i) < low - 1e-9) ok = false;
            if (ok) matched++;
            else bad += r[0] + " ";
            double night = Json.d(o, "rhr");
            if (!Double.isNaN(night)) {
                withNight++;
                if (Math.abs(low - night) <= 3) nearNight++;
            }
        }
        check("demo days: Highest and Lowest are the drawn line's highest and lowest point, Average its mean (#72)",
                days >= 28 && matched == days, matched + " of " + days + " " + bad);
        check("demo days: the day's lowest within 3 bpm of the night's lowest (#72)", withNight >= 28 && nearNight == withNight,
                nearNight + " of " + withNight);
    }

    /** Issue #72: a day of {@code hours} keeps one point per quarter hour (none merged), so nothing is cut off. */
    static void dayHeartLine(Db db, String day, int hours) {
        JSONObject o = Json.parse(db.dayJson(day));
        app.openring.core.analysis.TS line = Json.series(o == null ? null : o.optJSONArray("hr_day"));
        double ds = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        boolean quarter = line.size() > 0;
        for (int i = 0; i < line.size(); i++) if (Math.abs((line.t(i) - ds) % 900 - 450) > 1) quarter = false;
        double last = line.size() == 0 ? 0 : line.t(line.size() - 1);
        check(day + ": heart rate line in quarter hours over " + hours + " hours (#72)", quarter && last < ds + hours * 3600
                && (hours <= 24 || last > ds + 24 * 3600), line.size() + " points, last at " + (last - ds) / 3600 + " h");
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

    /**
     * Issue #43 B: Delete all local data while an analysis runs. The analysis already holds a week of
     * nights when Delete starts; Delete must wait for it, so afterwards every table is empty (no ghost
     * nights, no kv values written back).
     */
    static void deleteDuringAnalysis() throws Exception {
        final Context c = freshDb("openring-delete");
        final Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        long t0 = LocalDate.parse("2026-03-01").atStartOfDay(z).toEpochSecond();
        long end = t0 + 21 * 86400L + 12 * 3600;
        List<RingEvent> ev = app.openring.data.Demo.history(t0, 20, end, z, new Random(43));
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), 1, end);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10);
        Analyzer.run(c, null);
        db.addBattery(end, 80);
        long nightsBefore = db.count("SELECT COUNT(*) FROM nights");
        check("delete test: the history has nights before Delete", nightsBefore >= 15, nightsBefore + " nights");

        final java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch deleteStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch deleteDone = new java.util.concurrent.CountDownLatch(1);
        final boolean[] deletedEarly = {false};
        final Throwable[] err = new Throwable[2];
        Thread analysis = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Analyzer.runAll(c, new Analyzer.Log() {
                        @Override
                        public void log(String m) {
                            // the first week's nights are worked out and about to be stored: Delete starts now
                            if (!m.startsWith("sleep for") || paused.getCount() == 0) return;
                            paused.countDown();
                            try {
                                deleteStarted.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                Thread.sleep(400); // long enough for a Delete that does not wait to finish
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            deletedEarly[0] = deleteDone.getCount() == 0;
                        }
                    });
                } catch (Throwable t) {
                    err[0] = t;
                }
            }
        });
        analysis.start();
        check("delete test: the analysis reached its first week", paused.await(120, java.util.concurrent.TimeUnit.SECONDS), "");
        Thread delete = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    deleteStarted.countDown();
                    Analyzer.deleteAll(c);
                } catch (Throwable t) {
                    err[1] = t;
                } finally {
                    deleteDone.countDown();
                }
            }
        });
        delete.start();
        analysis.join(300000);
        delete.join(300000);
        check("delete during an analysis: no error", err[0] == null && err[1] == null, err[0] != null ? err[0] : err[1]);
        check("delete during an analysis waits for it to end", !deletedEarly[0], "");
        StringBuilder left = new StringBuilder();
        int tables = 0;
        android.database.Cursor cur = db.getReadableDatabase().rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null);
        List<String> names = new ArrayList<String>();
        if (cur.moveToFirst()) do names.add(cur.getString(0)); while (cur.moveToNext());
        cur.close();
        for (String t : names) {
            tables++;
            long n = db.count("SELECT COUNT(*) FROM " + t);
            if (n > 0) left.append(t).append(' ').append(n).append(", ");
        }
        check("delete during an analysis: every table is empty afterwards (no ghost nights)", tables >= 10 && left.length() == 0,
                tables + " tables, left: " + left);
        // the next analysis on the empty history finds nothing and stores nothing
        Analyzer.run(c, null);
        check("after Delete the next analysis stores no nights", db.count("SELECT COUNT(*) FROM nights") == 0
                && db.count("SELECT COUNT(*) FROM days") == 0, db.count("SELECT COUNT(*) FROM nights") + " nights");
        deleteOneTransaction(c);
    }

    /**
     * Review of PR #174: Delete all local data is one transaction. A delete that fails part way (here a
     * trigger stops the last table) leaves every table as it was, and the error reaches the caller,
     * so Settings can say that nothing was deleted.
     */
    static void deleteOneTransaction(Context c) throws Exception {
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        long t0 = LocalDate.parse("2026-04-01").atStartOfDay(z).toEpochSecond();
        long end = t0 + 8 * 86400L + 12 * 3600;
        List<RingEvent> ev = app.openring.data.Demo.history(t0, 7, end, z, new Random(174));
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), 1, end);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10);
        Analyzer.run(c, null);
        db.getWritableDatabase().execSQL("INSERT INTO screen_reads(start, stop) VALUES (?, ?)", new Object[] {(double) t0, (double) end});
        String[] tables = {"events", "nights", "days", "kv", "zones", "screen_reads"};
        long[] before = new long[tables.length];
        for (int i = 0; i < tables.length; i++) before[i] = db.count("SELECT COUNT(*) FROM " + tables[i]);
        db.getWritableDatabase().execSQL("CREATE TRIGGER stop_delete BEFORE DELETE ON screen_reads BEGIN SELECT RAISE(ABORT, 'test stop'); END");
        Throwable thrown = null;
        try {
            Analyzer.deleteAll(c);
        } catch (RuntimeException e) {
            thrown = e;
        }
        db.getWritableDatabase().execSQL("DROP TRIGGER stop_delete");
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < tables.length; i++) {
            long n = db.count("SELECT COUNT(*) FROM " + tables[i]);
            if (n != before[i]) changed.append(tables[i]).append(' ').append(before[i]).append(" to ").append(n).append(", ");
        }
        check("delete failing part way: the error reaches the caller", thrown != null && String.valueOf(thrown).contains("test stop"), thrown);
        check("delete failing part way: every table as it was (one transaction)", before[0] > 0 && before[1] > 0 && before[3] > 0
                && changed.length() == 0, before[0] + " events, " + before[1] + " nights, " + before[3] + " kv; changed: " + changed);
        Analyzer.deleteAll(c);
        check("delete after the failure empties everything", db.count("SELECT COUNT(*) FROM events") == 0
                && db.count("SELECT COUNT(*) FROM nights") == 0 && db.count("SELECT COUNT(*) FROM screen_reads") == 0, "");
    }

    // ---------------------------------------------------------------- issue #21, dating

    /**
     * Issue #94: a ring last synced 10 days ago (worn 12 hours with heart rate every 30 minutes as HRV
     * events, then 12 hours at room temperature) still has its history: the window counts back from
     * the newest stored reading, and HRV heart rates are dated by their 5 minute offsets.
     */
    static void lastWornOldSync() throws Exception {
        Db db = Db.get(freshDb("openring-lastworn"));
        long now = System.currentTimeMillis() / 1000;
        long start = now - 10 * 86400;
        long base = 1000;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        byte[] ts = new byte[4];
        Protocol.Le.putU32(ts, 0, start);
        ev.add(new RingEvent(EventCodec.TIME_SYNC, base, ts));
        for (int k = 0; k < 24 * 12; k++) { // every 5 minutes for a day
            long d = base + 1 + k * 3000L;
            boolean worn = k < 12 * 12;
            ev.add(new RingEvent(EventCodec.TEMP_EVENT, d, worn ? le16(3400, 3300, 2900) : le16(2100, 2100, 2000)));
            if (worn && k % 6 == 0) ev.add(new RingEvent(EventCodec.HRV_EVENT, d + 1, new byte[] {60, 40, 61, 40, 62, 40, 61, 40, 60, 40, 59, 40}));
        }
        db.insertBatch(ev, 1, start + 86400);
        db.rebuildTime(start + 86400);
        app.openring.core.analysis.LastWorn.Result r = app.openring.data.RightNow.lastWorn(db, now);
        double lastHrv = start + (base + 1 + (12 * 12 - 6) * 3000L + 1 - base) / 10.0 + 5 * 300;
        check("ring finder: a sync 10 days old still reads as taken off (#94)", r.state == app.openring.core.analysis.LastWorn.OFF
                && Math.abs(r.at - (start + 12 * 3600)) <= 300 && Math.abs(r.roomC - 21) < 0.01, r.state + " " + (r.at - start) / 3600.0);
        check("ring finder: its newest reading is from the sync 10 days ago (9 days and 5 minutes old)", Math.abs(now - r.newest - (9 * 86400 + 300)) <= 600, (now - r.newest) / 86400.0);
        check("ring finder: night heart rate only, so temperature decides", !r.hrRule && r.hasHr, r.hrRule);
        check("ring finder: HRV heart rates dated by their 5 minute steps (#94)", Math.abs(r.lastHr - lastHrv) <= 1, r.lastHr - lastHrv);
        // a 10 C reading 8 days before the newest is outside the 7 days, so the room temperature and the time stay
        List<RingEvent> older = new ArrayList<RingEvent>();
        byte[] ts2 = new byte[4];
        Protocol.Le.putU32(ts2, 0, start - 8 * 86400);
        older.add(new RingEvent(EventCodec.TIME_SYNC, 500000, ts2));
        older.add(new RingEvent(EventCodec.TEMP_EVENT, 500001, le16(1000, 1000, 1000)));
        db.insertBatch(older, 2, start - 8 * 86400);
        db.rebuildTime(start + 86400);
        check("ring finder: the reading 8 days earlier is dated", db.count("SELECT COUNT(*) FROM events WHERE unix < " + (start - 7 * 86400)) == 2,
                db.count("SELECT COUNT(*) FROM events WHERE unix < " + (start - 7 * 86400)));
        app.openring.core.analysis.LastWorn.Result r8 = app.openring.data.RightNow.lastWorn(db, now);
        check("ring finder: the 7 days count back from the newest reading (one 8 days before it is left out)", r8.state == app.openring.core.analysis.LastWorn.OFF
                && Math.abs(r8.at - r.at) < 1 && Math.abs(r8.roomC - 21) < 0.01, r8.roomC);
        // a reading dated more than an hour in the future (a broken clock) does not become the newest
        List<RingEvent> future = new ArrayList<RingEvent>();
        future.add(new RingEvent(EventCodec.TEMP_EVENT, 900001, le16(3400, 3300, 2900)));
        db.insertBatch(future, 3, now);
        db.getWritableDatabase().execSQL("UPDATE events SET unix = " + (now + 5 * 86400) + " WHERE epoch = 3 AND ds = 900001");
        check("ring finder: the future reading is dated in the future", db.count("SELECT COUNT(*) FROM events WHERE tag = " + EventCodec.TEMP_EVENT
                + " AND unix > " + (now + 3600)) == 1, null);
        app.openring.core.analysis.LastWorn.Result rf = app.openring.data.RightNow.lastWorn(db, now);
        check("ring finder: a reading dated in the future is not the newest", rf.state == app.openring.core.analysis.LastWorn.OFF
                && Math.abs(rf.newest - r.newest) < 1, (rf.newest - now) / 86400.0);
        // more than 50 readings dated in the future (review of #103: the newest one before them was missed)
        List<RingEvent> many = new ArrayList<RingEvent>();
        for (int i = 0; i < 60; i++) many.add(new RingEvent(EventCodec.TEMP_EVENT, 910001 + i, le16(3400, 3300, 2900)));
        db.insertBatch(many, 4, now);
        db.getWritableDatabase().execSQL("UPDATE events SET unix = " + (now + 6 * 86400) + " + ds WHERE epoch = 4");
        app.openring.core.analysis.LastWorn.Result rm = app.openring.data.RightNow.lastWorn(db, now);
        check("ring finder: 60 readings dated in the future do not hide the history", rm.state == app.openring.core.analysis.LastWorn.OFF
                && Math.abs(rm.newest - r.newest) < 1 && db.count("SELECT COUNT(*) FROM events WHERE unix > " + (now + 3600)) >= 60, rm.state + " " + (rm.newest - now) / 86400.0);
        check("ring finder: an empty history has no data", app.openring.data.RightNow.lastWorn(Db.get(freshDb("openring-lastworn-empty")), now).state
                == app.openring.core.analysis.LastWorn.NO_DATA, null);
    }

    /** The synthetic history plus {@code extra}, analysed in a database of its own (TZ Europe/Zurich, as {@code main}). */
    static Context analysedCtx;

    static Db analysed(String name, List<RingEvent> base, RingEvent... extra) throws Exception {
        Context ctx = analysedCtx = freshDb(name);
        Db db = Db.get(ctx);
        List<RingEvent> ev = new ArrayList<RingEvent>(base);
        ev.addAll(Arrays.asList(extra));
        java.util.Collections.sort(ev, new java.util.Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        long lastDs = ev.get(ev.size() - 1).ds, lastUnix = T0 + (lastDs - DS0) / 10;
        db.insertBatch(ev, 1, lastUnix + 600);
        db.insertPhoneAnchor(1, lastDs, lastUnix);
        Analyzer.run(ctx, null);
        return db;
    }

    /** The stored heart rate line of {@code day}. */
    static app.openring.core.analysis.TS dayLine(Db db, String day) {
        JSONObject o = Json.parse(db.dayJson(day));
        return Json.series(o == null ? null : o.optJSONArray("hr_day"));
    }

    /** The value of the line's point at {@code t} (the middle of its quarter hour), NaN without one. */
    static double pointAt(app.openring.core.analysis.TS line, double t) {
        for (int i = 0; i < line.size(); i++) if (Math.abs(line.t(i) - t) < 1) return line.v(i);
        return Double.NaN;
    }

    /**
     * Issue #97, reproduced with the synthetic history: one HRV value of 250 does not become the day's Highest,
     * the values after midnight of an HRV event begun at 23:50 count for the new day, and a database analysed with
     * the older rules has its days analysed again once.
     */
    static void dayHeartLimits() throws Exception {
        List<RingEvent> base = history(3);
        double at0330 = java.time.LocalDateTime.parse("2026-10-08T03:30:00").atZone(ZONE).toEpochSecond();
        double at2350 = java.time.LocalDateTime.parse("2026-10-08T23:50:00").atZone(ZONE).toEpochSecond();
        Db control = analysed("openring-dayheart-control", base,
                new RingEvent(EventCodec.HRV_EVENT, ds(at0330), new byte[] {52, 40, 52, 40, 52, 40, 52, 40, 52, 40, 52, 40}));
        double d8 = LocalDate.parse("2026-10-08").atStartOfDay(ZONE).toEpochSecond(), d9 = LocalDate.parse("2026-10-09").atStartOfDay(ZONE).toEpochSecond();
        app.openring.core.analysis.TS c8 = dayLine(control, "2026-10-08"), c9 = dayLine(control, "2026-10-09");
        app.openring.core.analysis.DayHeart ch = app.openring.core.analysis.DayHeart.of(c8, d8, d9);
        double c0007 = pointAt(c9, d9 + 450), c2352 = pointAt(c8, d9 - 450);
        check("day heart control: Highest in the evening, not in the night (#97)", ch.points > 0 && ch.high < 100, ch.high);

        Db bad = analysed("openring-dayheart-250", base,
                new RingEvent(EventCodec.HRV_EVENT, ds(at0330), new byte[] {(byte) 250, 40, 52, 40, 52, 40, 52, 40, 52, 40, 52, 40}));
        app.openring.core.analysis.TS b8 = dayLine(bad, "2026-10-08");
        app.openring.core.analysis.DayHeart bh = app.openring.core.analysis.DayHeart.of(b8, d8, d9);
        check("one HRV value of 250 is not the day's Highest (#97)", Math.abs(bh.high - ch.high) < 0.01 && Math.abs(bh.highAt - ch.highAt) < 1,
                bh.high + " at " + hm(bh.highAt) + ", control " + ch.high + " at " + hm(ch.highAt));
        check("no point of the line above 220 (#97)", b8.max() <= 220, b8.max());

        Db late = analysed("openring-dayheart-midnight", base,
                new RingEvent(EventCodec.HRV_EVENT, ds(at2350), new byte[] {100, 40, 100, 40, 100, 40, 100, 40, 100, 40, 100, 40}));
        double l0007 = pointAt(dayLine(late, "2026-10-09"), d9 + 450), l2352 = pointAt(dayLine(late, "2026-10-08"), d9 - 450);
        check("HRV values after midnight count for the new day (#97)", !Double.isNaN(l0007) && l0007 > (Double.isNaN(c0007) ? 0 : c0007) + 5,
                l0007 + ", without the event " + c0007);
        check("and its values before midnight still for the old day (#97)", !Double.isNaN(l2352) && l2352 > (Double.isNaN(c2352) ? 0 : c2352) + 5,
                l2352 + ", without the event " + c2352);
    }

    /**
     * Issue #148: a green IBI beat of 280 ms (214 bpm) reaches the day's heart rate line like any heart rate up to
     * 220 bpm; one of 270 ms (222 bpm) does not. One beat at 15:05 on the synthetic history, against a control run.
     */
    static void dayHeartFastBeat() throws Exception {
        List<RingEvent> base = history(3);
        double at1505 = java.time.LocalDateTime.parse("2026-10-08T15:05:00").atZone(ZONE).toEpochSecond();
        double mid = java.time.LocalDateTime.parse("2026-10-08T15:07:30").atZone(ZONE).toEpochSecond();
        double control = pointAt(dayLine(analysed("openring-dayheart-ibi-control", base), "2026-10-08"), mid);
        int[] ibis = {280, 270};
        double[] got = new double[2];
        for (int k = 0; k < 2; k++) {
            int ibi = ibis[k];
            got[k] = pointAt(dayLine(analysed("openring-dayheart-ibi-" + ibi, base, new RingEvent(EventCodec.GREEN_IBI_QUALITY, ds(at1505),
                    new byte[] {(byte) (ibi >> 3), (byte) ((ibi & 7) | (1 << 3))})), "2026-10-08"), mid);
        }
        check("a green IBI beat of 280 ms (214 bpm) reaches the day's heart rate line (#148)", !Double.isNaN(control) && got[0] > control + 5,
                got[0] + ", without the beat " + control);
        check("a green IBI beat of 270 ms (222 bpm) does not (#148)", Math.abs(got[1] - control) < 1e-9, got[1] + ", without the beat " + control);
    }

    /**
     * Issue #97: a database from the older heart rate rules has its days analysed again once. Six days, so the first
     * lies outside the window a normal run analyses again (the last two days); a blanked first day stays blank with
     * the current rules version and is filled again with the old one.
     */
    static void dayHeartVersion() throws Exception {
        Db db = analysed("openring-dayheart-version", history(6));
        boolean filled = dayLine(db, "2026-10-07").size() > 0;
        db.putDay("2026-10-07", "{}");
        Analyzer.run(analysedCtx, null);
        check("day heart: a normal run leaves the first of six days alone", filled && dayLine(db, "2026-10-07").size() == 0,
                filled + " " + dayLine(db, "2026-10-07").size());
        db.put("day_heart", 1);
        Analyzer.run(analysedCtx, null);
        check("days analysed again once for the new heart rate rules (#97)", dayLine(db, "2026-10-07").size() > 0
                && db.kvLong("day_heart", 0) == app.openring.core.analysis.DayHeart.VERSION, db.kvLong("day_heart", 0));
    }

    static String hm(double unix) {
        return Double.isNaN(unix) ? "--" : java.time.Instant.ofEpochSecond((long) unix).atZone(ZONE).toLocalTime().toString();
    }

    /**
     * Issue #97: Today's heart rate time is the shown value's own time (an HRV event's fifth value is 20 minutes
     * after its start), the newest by that time wins over an event that began later, and a 250 is skipped.
     */
    static void todayHeartTime() throws Exception {
        Context ctx = freshDb("openring-today-hr");
        Db db = Db.get(ctx);
        long now = System.currentTimeMillis() / 1000, start = now - 3600, base = 1000;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        byte[] ts = new byte[4];
        Protocol.Le.putU32(ts, 0, start);
        ev.add(new RingEvent(EventCodec.TIME_SYNC, base, ts));
        // HRV at start + 10 minutes: 60 61 62 63 64 then 250 (dropped), so 64 at start + 30 minutes
        ev.add(new RingEvent(EventCodec.HRV_EVENT, base + 6000, new byte[] {60, 40, 61, 40, 62, 40, 63, 40, 64, 40, (byte) 250, 40}));
        // a clean beat (1000 ms, 60 bpm) at start + 20 minutes: begins later, but measured before the 64
        int ibi = 1000;
        ev.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, base + 12000, new byte[] {(byte) (ibi >> 3), (byte) ((ibi & 7) | (1 << 3))}));
        db.insertBatch(ev, 1, now);
        db.rebuildTime(now);
        app.openring.data.RightNow r = app.openring.data.RightNow.load(ctx);
        check("Today's heart rate: the newest plausible value (#97)", r.hr == 64, r.hr);
        check("Today's heart rate: at its own time, not the event start (#97)", Math.abs(r.hrAt - (start + 1800)) <= 1, (r.hrAt - start) / 60 + " min after start");
    }

    /**
     * Review of PR #103: one stored event with several heart rates (six HRV values 5 minutes apart, an
     * AOHR burst 1.92 s apart) is one measurement, so its values do not vouch for each other. A ring
     * worn 10 hours with daytime heart rate (one AOHR value every 5 minutes), then on a table, with one
     * stray HRV event and one stray AOHR burst, in the middle of the cool readings or at their end.
     */
    static void lastWornStrayEvents() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        long start = now - 2 * 86400;
        String[][] cases = {{"(HRV and AOHR) in the middle", "20", "14", "16"}, {"(HRV) at the end", "14.5", "14", ""}, {"(AOHR burst) at the end", "14", "", "14"}};
        for (String[] c : cases) {
            Db db = Db.get(freshDb("openring-lastworn-stray"));
            long base = 1000;
            List<RingEvent> ev = new ArrayList<RingEvent>();
            byte[] ts = new byte[4];
            Protocol.Le.putU32(ts, 0, start);
            ev.add(new RingEvent(EventCodec.TIME_SYNC, base, ts));
            int steps = (int) Math.round(Double.parseDouble(c[1]) * 12);
            for (int k = 0; k < steps; k++) {
                long d = base + 1 + k * 3000L;
                boolean worn = k < 10 * 12;
                ev.add(new RingEvent(EventCodec.TEMP_EVENT, d, worn ? le16(3400, 3300, 2900) : le16(2100, 2100, 2000)));
                if (worn) ev.add(new RingEvent(EventCodec.AOHR_EVENT, d + 2, new byte[] {0, 0, 1, 70, 1}));
            }
            if (!c[2].isEmpty()) {
                long d = base + 1 + (long) (Double.parseDouble(c[2]) * 12) * 3000L + 5;
                ev.add(new RingEvent(EventCodec.HRV_EVENT, d, new byte[] {60, 40, 61, 40, 62, 40, 61, 40, 60, 40, 59, 40}));
            }
            if (!c[3].isEmpty()) {
                long d = base + 1 + (long) (Double.parseDouble(c[3]) * 12) * 3000L + 6;
                byte[] burst = new byte[3 + 2 * 20];
                burst[2] = 20;
                for (int i = 0; i < 20; i++) {
                    burst[3 + 2 * i] = 72;
                    burst[4 + 2 * i] = 1;
                }
                ev.add(new RingEvent(EventCodec.AOHR_EVENT, d, burst));
            }
            db.insertBatch(ev, 1, start + 86400);
            db.rebuildTime(start + 86400);
            app.openring.core.analysis.LastWorn.Result r = app.openring.data.RightNow.lastWorn(db, now);
            check("ring finder: a stray multi value heart rate event " + c[0] + " does not move the time off at 10 h (review of #103)",
                    r.state == app.openring.core.analysis.LastWorn.OFF && r.hrRule && Math.abs(r.at - (start + 10 * 3600)) <= 300
                    && !Double.isNaN(r.strayHrAt), r.state + " " + (r.at - start) / 3600.0 + " rule " + r.hrRule);
        }
    }

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
        db.put("night_finder", app.openring.core.analysis.NightFinder.VERSION);
        db.put("phone_awake", app.openring.core.analysis.ScreenTime.AWAKE_RULE);
        db.put("day_heart", app.openring.core.analysis.DayHeart.VERSION);
        t = System.nanoTime();
        Analyzer.run(c, null);
        long incMs = (System.nanoTime() - t) / 1000000;
        System.out.println("  " + days + " days: incremental analysis " + incMs + " ms (about " + (incMs - newMs + oldMs) + " ms with the old rewrite)");
        check("long history: incremental analysis stays incremental", db.kvLong("analyzed_until", 0) >= (long) range[1] && db.count("SELECT COUNT(*) FROM nights") < 10,
                db.count("SELECT COUNT(*) FROM nights") + " nights");
        // issue #115: the checked copy of a long history, both ways, holds every event and night
        wal(db);
        long allEvents = db.eventCount(), allNights = db.count("SELECT COUNT(*) FROM nights");
        for (boolean vacuum : new boolean[] {true, false}) {
            t = System.nanoTime();
            java.io.File snap = snapshotWith(c, vacuum);
            long ms = (System.nanoTime() - t) / 1000000;
            System.out.println("  " + days + " days: checked copy by " + (vacuum ? "VACUUM INTO" : "copy under the write lock") + " " + ms + " ms, "
                    + snap.length() / 1024 + " KB");
            check("long history: the checked copy " + (vacuum ? "(VACUUM INTO)" : "(copy under the write lock)") + " holds every event and night",
                    countIn(snap, "SELECT COUNT(*) FROM events") == allEvents && countIn(snap, "SELECT COUNT(*) FROM nights") == allNights
                    && countIn(snap, EVENTS_SUM) == db.count(EVENTS_SUM), allEvents + " events, " + allNights + " nights");
            app.openring.data.Exporter.release(snap);
        }
        db.close();
    }

    // ---------------------------------------------------------------- long wake ups (issue #42)

    /** Local clock time on 2026-10-1x as unix seconds; hour may pass 24 for the next morning. */
    static double at(LocalDate d, double hour) {
        return d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + hour * 3600;
    }

    /**
     * One worn stretch {from, to} with the ring's streams: sleep_state asleep (awake inside
     * {@code awake} stretches and in the first and last 10 minutes), heart rate, HRV, motion, temperature.
     */
    static void wornSleep(List<RingEvent> ev, double from, double to, List<double[]> awake, long t0, long ds0) {
        for (double t = from; t < to; t += 60) {
            boolean aw = t < from + 600 || t >= to - 600;
            for (double[] a : awake) if (t >= a[0] && t < a[1]) aw = true;
            long d = ds0 + Math.round((t - t0) * 10);
            if (((long) (t - from)) % 300 == 0) {
                int hr = aw ? 68 + rnd.nextInt(4) : 54 + rnd.nextInt(4);
                byte[] spi = new byte[10];
                spi[0] = (byte) (hr * 2);
                spi[4] = (byte) Math.round((14 + rnd.nextGaussian() * 0.5) * 8);
                spi[6] = (byte) (aw ? 20 : 0);
                spi[7] = (byte) (aw ? 0 : 1);
                spi[9] = 0x10;
                ev.add(new RingEvent(EventCodec.SLEEP_PERIOD_INFO_2, d, spi));
                byte[] hrv = new byte[2];
                hrv[0] = (byte) hr;
                hrv[1] = (byte) (aw ? 30 : 55 + rnd.nextInt(10));
                ev.add(new RingEvent(EventCodec.HRV_EVENT, d, hrv));
            }
            ev.add(new RingEvent(EventCodec.MOTION_EVENT, d, new byte[] {(byte) (aw ? 14 : (rnd.nextDouble() < 0.03 ? 5 : 0)), 1, 2, 3}));
            ev.add(new RingEvent(EventCodec.SLEEP_TEMP_EVENT, d, le16((int) Math.round((35.3 + rnd.nextGaussian() * 0.04) * 100))));
        }
    }

    /** Runs one synthetic history through the real Db and Analyzer; returns the database. */
    static Db runSynthetic(List<RingEvent> ev, long t0, long ds0, double now) throws Exception {
        Context c = freshDb("openring42"); // each scenario on its own empty phone
        Db db = Db.get(c);
        java.util.Collections.sort(ev, new java.util.Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        db.insertBatch(ev, 1, (long) now);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - ds0) / 10);
        Analyzer.run(c, null);
        lastSynthetic = c;
        return db;
    }

    /** The phone of the last {@link #runSynthetic}, for tests that analyse it again. */
    static Context lastSynthetic;

    /**
     * Issue #30: a night joined across a wake up the ring reports itself stores that wake up, and the
     * stage detail preview marks it awake again like the analysis. Data safety: nights stored by an older
     * version have no ring_awake; their preview still works, and analysing again keeps every event and
     * the same nights and adds the field, also when run twice.
     */
    static void previewRingWake() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 12);
        Db db = longWakeNight(d, 75, false, false);
        Context c = lastSynthetic;
        app.openring.data.Prefs pr = app.openring.data.Prefs.of(c);
        JSONObject n = mainNight(db, d);
        check("#30 a joined night keeps the ring's own wake up", n != null && !Json.ringAwake(n).isEmpty(), n == null ? null : n.opt("ring_awake"));
        if (n == null) return;
        List<double[]> odd = Json.ringAwake(Json.parse("{\"ring_awake\":[[1,2],[3],\"x\",[5,4],[6,7]]}"));
        check("#30 ring_awake read safely: odd entries left out", odd.size() == 2 && odd.get(1)[0] == 6, odd.size());
        check("#30 ring_awake of no night is empty", Json.ringAwake(null).isEmpty() && Json.ringAwake(new JSONObject()).isEmpty(), null);
        double w0 = at(d, 2.5);
        boolean inside = false;
        for (double[] w : Json.ringAwake(n)) inside |= w[0] < w0 + 75 * 60 && w[1] > w0 && w[1] > n.optDouble("start") && w[0] < n.optDouble("end");
        check("#30 the stored wake up lies inside the night", inside, n.opt("ring_awake"));
        for (int lv = 0; lv < 3; lv++) {
            JSONObject cur = mainNight(db, d);
            int[] pv = Analyzer.preview(db, pr.screenAwake(), cur, lv);
            pr.setStageDetail(lv);
            Analyzer.run(c, null);
            JSONObject saved = mainNight(db, d);
            check("#30 joined night, level " + lv + ": the preview is the night saving produces", Arrays.equals(pv, Json.stages(saved)), null);
        }
        pr.setStageDetail(1);
        Analyzer.run(c, null);

        // a database from an older version: the same nights without ring_awake
        long events = db.count("SELECT COUNT(*) FROM events");
        List<String> before = nightRows(db);
        List<double[]> keys = storedSleeps(db, "0000", "9999");
        for (double[] k : keys) {
            android.database.Cursor cu = db.getReadableDatabase().rawQuery("SELECT json FROM nights WHERE start = ?", new String[] {String.valueOf(k[0])});
            String js;
            try {
                cu.moveToNext();
                js = cu.getString(0);
            } finally {
                cu.close();
            }
            JSONObject o = Json.parse(js);
            o.remove("ring_awake");
            db.getWritableDatabase().execSQL("UPDATE nights SET json = ? WHERE start = ?", new Object[] {o.toString(), k[0]});
        }
        check("#30 older nights: no ring_awake left", db.count("SELECT COUNT(*) FROM nights WHERE json LIKE '%ring_awake%'") == 0, null);
        boolean previewOk = true;
        for (String js : db.nightsJson("0000", "9999", false)) {
            JSONObject o = Json.parse(js);
            int[] pv = Analyzer.preview(db, true, o, 1);
            previewOk &= pv.length == Json.stagesRaw(o).length;
        }
        check("#30 older nights: the preview works without ring_awake", previewOk, null);
        for (int run = 1; run <= 2; run++) {
            db.put("analyzed_until", 0);
            Analyzer.run(c, null);
            check("#30 older nights, analysis " + run + ": every event kept", db.count("SELECT COUNT(*) FROM events") == events, events);
            check("#30 older nights, analysis " + run + ": the same nights and stages", nightRows(db).equals(before), nightRows(db).size() + " vs " + before.size());
            check("#30 older nights, analysis " + run + ": ring_awake stored again", !Json.ringAwake(mainNight(db, d)).isEmpty(), null);
        }
    }

    /** Each stored sleep as start, stop, day, stages and raw stages, for before and after comparisons. */
    static List<String> nightRows(Db db) {
        List<String> out = new ArrayList<String>();
        for (String js : db.nightsJson("0000", "9999", false)) {
            JSONObject o = Json.parse(js);
            out.add(o.optDouble("start") + " " + o.optDouble("end") + " " + o.optString("day") + " " + o.optString("stages")
                    + " " + o.optString("stages_raw") + " " + o.optInt("awakenings"));
        }
        return out;
    }

    /**
     * A night 23:00 to 07:00 with one wake up of {@code wakeMin} minutes from 02:30, optionally a
     * bedtime marker, a nap 13:30 to 15:00 the day after and the next night as well.
     */
    static Db longWakeNight(LocalDate d, int wakeMin, boolean marker, boolean napAndNextNight) throws Exception {
        long t0 = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long ds0 = 9L * 86400 * 10;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        double bed = at(d, -1), up = at(d, 7), w0 = at(d, 2.5), w1 = w0 + wakeMin * 60;
        List<double[]> awake = new ArrayList<double[]>();
        if (wakeMin > 0) awake.add(new double[] {w0, w1});
        wornSleep(ev, bed, up, awake, t0, ds0);
        if (marker) {
            byte[] bt = new byte[8];
            Protocol.Le.putU32(bt, 0, ds0 + Math.round((bed - t0) * 10));
            Protocol.Le.putU32(bt, 4, ds0 + Math.round((up - t0) * 10));
            ev.add(new RingEvent(EventCodec.BEDTIME_PERIOD, ds0 + Math.round((up + 600 - t0) * 10), bt));
        }
        double now = up + 1800;
        if (napAndNextNight) {
            wornSleep(ev, at(d, 13.5), at(d, 15), new ArrayList<double[]>(), t0, ds0);
            wornSleep(ev, at(d, 23), at(d, 31), new ArrayList<double[]>(), t0, ds0);
            now = at(d, 31.5);
        }
        return runSynthetic(ev, t0, ds0, now);
    }

    static JSONObject mainNight(Db db, LocalDate d) {
        List<String> m = db.nightsJson(d.toString(), d.toString(), true);
        return m.size() == 1 ? Json.parse(m.get(0)) : null;
    }

    static String describe(JSONObject n) {
        if (n == null) return "no single main night";
        JSONObject sc = n.optJSONObject("score");
        return String.format(java.util.Locale.US, "%.0f min asleep, %d wake ups, %.0f min awake, score %s",
                n.optDouble("asleep_min"), n.optInt("awakenings"), n.optDouble("waso_min"), sc == null ? "-" : sc.opt("value"));
    }

    static void longWakeChecks() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 12);
        int[] score = new int[4];
        String[] cases = {"sleeping through", "75 min", "120 min", "200 min"};
        int[] wakes = {0, 75, 120, 200};
        for (int k = 0; k < 4; k++) {
            for (boolean marker : new boolean[] {false, true}) {
                Db db = longWakeNight(d, wakes[k], marker, false);
                List<String> all = db.nightsJson(d.toString(), d.toString(), false);
                JSONObject n = mainNight(db, d);
                String how = "#42 " + cases[k] + (marker ? " with" : " without") + " bedtime marker";
                System.out.println("    " + how + ": " + all.size() + " sleeps, main " + describe(n));
                if (k == 1 && !marker) check("#42 the rules version is noted, so a new one re analyses all nights",
                        db.kvLong("night_finder", 0) == app.openring.core.analysis.NightFinder.VERSION, db.kvLong("night_finder", 0));
                if (n == null) {
                    check(how + " has a main night", false, all.size());
                    continue;
                }
                double asleep = n.optDouble("asleep_min"), waso = n.optDouble("waso_min");
                int sc = n.optJSONObject("score").optInt("value");
                if (k == 0) {
                    check(how + ": one night of 7 hours or more", all.size() == 1 && asleep >= 420, describe(n));
                    if (!marker) score[0] = sc;
                } else if (k < 3) {
                    double sleep = 480 - wakes[k] - 20;
                    check(how + ": one night, the wake up inside it", all.size() == 1 && n.optInt("awakenings") >= 1
                            && Math.abs(waso - wakes[k]) <= 15 && Math.abs(asleep - sleep) <= 25, describe(n));
                    check(how + ": scores lower than sleeping through", sc < score[0] - 5, sc + " vs " + score[0]);
                    if (!marker) score[k] = sc;
                    check(how + ": latency " + (marker ? "from the marker" : "stays 0 without a marker"),
                            marker || n.optDouble("latency_min") <= 1, n.opt("latency_min"));
                } else if (marker) {
                    check(how + ": the marker keeps one night", all.size() == 1, describe(n));
                } else {
                    check(how + ": over 3 hours stays two sleeps, the longer one is the night", all.size() == 2
                            && asleep > 150 && asleep < 220, all.size() + " sleeps, " + describe(n));
                }
            }
        }
        check("#42 a longer wake up scores lower", score[2] < score[1], score[1] + " then " + score[2]);
        // a nap in the afternoon stays a nap, and the next night is its own night
        Db db = longWakeNight(d, 75, false, true);
        List<String> today = db.nightsJson(d.toString(), d.toString(), false);
        JSONObject next = mainNight(db, d.plusDays(1));
        check("#42 afternoon nap stays a nap next to the joined night", today.size() == 2 && mainNight(db, d) != null
                && Math.abs(mainNight(db, d).optDouble("asleep_min") - 385) <= 25, today.size() + " sleeps, " + describe(mainNight(db, d)));
        check("#42 the next night stays its own night", next != null && next.optDouble("asleep_min") >= 420
                && db.nightsJson(d.plusDays(1).toString(), d.plusDays(1).toString(), false).size() == 1, describe(next));
        // falling asleep on the sofa in the evening (19:30 to 21:00), bed at 23:30: the gap's middle is
        // before 23:00, so the sofa stays a nap
        long t0 = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long ds0 = 9L * 86400 * 10;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        List<double[]> none = new ArrayList<double[]>();
        wornSleep(ev, at(d, 19.5), at(d, 21), none, t0, ds0);
        wornSleep(ev, at(d, 21), at(d, 23.5), java.util.Collections.singletonList(new double[] {at(d, 21), at(d, 23.5)}), t0, ds0);
        wornSleep(ev, at(d, 23.5), at(d, 31), none, t0, ds0);
        db = runSynthetic(ev, t0, ds0, at(d, 31.5));
        List<String> evening = db.nightsJson(d.toString(), d.plusDays(1).toString(), false);
        JSONObject night = mainNight(db, d.plusDays(1));
        check("#42 evening sofa sleep stays a nap", evening.size() == 2 && night != null && night.optDouble("asleep_min") < 440
                && night.optDouble("asleep_min") > 380, evening.size() + " sleeps, " + describe(night));
        // review of #42: sofa 20:30 to 22:30, awake to 00:30, bed to 07:30: the sofa stays its own sleep
        ev = new ArrayList<RingEvent>();
        wornSleep(ev, at(d, 20.5), at(d, 31.5), java.util.Collections.singletonList(new double[] {at(d, 22.5), at(d, 24.5)}), t0, ds0);
        db = runSynthetic(ev, t0, ds0, at(d, 32));
        JSONObject sofa = mainNight(db, d), bedNight = mainNight(db, d.plusDays(1));
        check("#42 sofa sleep from 20:30 and a wake up to 00:30 stay two sleeps", sofa != null && bedNight != null
                && sofa.optDouble("asleep_min") < 130 && bedNight.optDouble("asleep_min") < 420
                && db.nightsJson(d.plusDays(1).toString(), d.plusDays(1).toString(), false).size() == 1,
                describe(sofa) + " / " + describe(bedNight));
        // asleep 23:00 to 04:00, up 04:00 to 06:30, nap 06:30 to 08:00: the night is not stretched past getting up
        ev = new ArrayList<RingEvent>();
        wornSleep(ev, at(d, -1), at(d, 8), java.util.Collections.singletonList(new double[] {at(d, 4), at(d, 6.5)}), t0, ds0);
        db = runSynthetic(ev, t0, ds0, at(d, 8.5));
        List<String> morning = db.nightsJson(d.toString(), d.toString(), false);
        JSONObject early = mainNight(db, d);
        check("#42 nap after getting up at 04:00 stays a nap", morning.size() == 2 && early != null
                && early.optDouble("asleep_min") > 250 && early.optDouble("asleep_min") < 310, morning.size() + " sleeps, " + describe(early));
        freshDb("openring42-done"); // the next test opens its own phone
    }

    /** Every sleep stored for days [from, to] as {start, stop} from the table (not the JSON). */
    static List<double[]> storedSleeps(Db db, String from, String to) {
        List<double[]> out = new ArrayList<double[]>();
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT start, stop FROM nights WHERE day >= ? AND day <= ? ORDER BY start",
                new String[] {from, to});
        try {
            while (c.moveToNext()) out.add(new double[] {c.getDouble(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        return out;
    }

    static String when(double unix) {
        return java.time.Instant.ofEpochSecond((long) unix).atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
    }

    static String spans(List<double[]> l) {
        StringBuilder b = new StringBuilder();
        for (double[] x : l) b.append(b.length() == 0 ? "" : ", ").append(when(x[0])).append(" to ").append(when(x[1]));
        return l.size() + " sleeps: " + b;
    }

    /**
     * Issue #132: a sleep that ends just after midnight, with phone use from just before it, is
     * stored ending before midnight. A later run whose chunk starts at that midnight analyses it
     * again; with another start it must replace the stored row, not add a second one.
     */
    static void nightTwice() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 12);
        Context c = freshDb("openring132");
        Db db = Db.get(c);
        long t0 = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long ds0 = 9L * 86400 * 10;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        List<double[]> none = new ArrayList<double[]>();
        wornSleep(ev, at(d, 16), at(d, 17.5), none, t0, ds0);      // an afternoon nap
        wornSleep(ev, at(d, 19), at(d, 24.5), none, t0, ds0);      // asleep on the sofa until 00:30
        for (int k = 2; k <= 4; k++) wornSleep(ev, at(d.plusDays(k), -1), at(d.plusDays(k), 7), none, t0, ds0);
        java.util.Collections.sort(ev, new java.util.Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        double now = at(d.plusDays(4), 7.5);
        db.insertBatch(ev, 1, (long) now);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - ds0) / 10);
        List<double[]> phone = new ArrayList<double[]>();
        phone.add(new double[] {at(d, 23 + 50 / 60.0), at(d, 24.75)}); // phone from 23:50, into the end of the sleep
        db.putScreen(phone);
        Analyzer.run(c, null);
        String day = d.toString();
        List<double[]> first = storedSleeps(db, day, day);
        double midnight = at(d, 24);
        double[] evening = first.isEmpty() ? null : first.get(first.size() - 1);
        boolean setUp = first.size() == 2 && evening != null && evening[1] < midnight && Math.abs(evening[1] - at(d, 23 + 50 / 60.0)) < 1;
        check("#132 set up: nap and an evening sleep that ends at 23:50 (phone use)", setUp, spans(first));
        if (!setUp) return;
        double napStart = first.get(0)[0], napStop = first.get(0)[1];
        double asleepBefore = Json.parse(db.dayJson(day)).optDouble("asleep_min");

        // the next run sees phone use at the start it did not see before, and its first chunk starts at
        // the midnight inside the sleep (an incremental run two days later)
        List<double[]> start = new ArrayList<double[]>();
        start.add(new double[] {at(d, 18.9), at(d, 19.5)});
        db.putScreen(start);
        double from = at(d.plusDays(2), 18);
        db.put("analyzed_until", (long) (from + 2 * 86400));
        Analyzer.run(c, null);
        List<double[]> second = storedSleeps(db, day, day);
        check("#132 the evening sleep is stored once, with its new start", second.size() == 2
                && Math.abs(second.get(1)[0] - at(d, 19.5)) < 1 && Math.abs(second.get(1)[1] - evening[1]) < 1, spans(second));
        check("#132 the nap before it stays as it was", second.size() >= 1 && second.get(0)[0] == napStart && second.get(0)[1] == napStop, spans(second));
        JSONObject dayJ = Json.parse(db.dayJson(day));
        List<String> mainN = db.nightsJson(day, day, true);
        double asleepAfter = mainN.isEmpty() ? Double.NaN : Json.parse(mainN.get(mainN.size() - 1)).optDouble("asleep_min");
        check("#132 the day is computed again from the new sleep", dayJ != null && mainN.size() == 1 && asleepAfter < asleepBefore - 10
                && Math.abs(dayJ.optDouble("asleep_min") - asleepAfter) < 0.01,
                asleepBefore + " before, day " + (dayJ == null ? null : dayJ.opt("asleep_min")) + ", main nights " + mainN.size() + ", new " + asleepAfter);

        // an older run had joined the nap and the evening sleep (#62); the new run splits them again
        // at the same boundary: the joined row goes, both pieces stay
        List<Object[]> joined = new ArrayList<Object[]>();
        joined.add(new Object[] {napStart, evening[1], day, 1, 50, "{\"start\":" + napStart + ",\"end\":" + evening[1] + ",\"day\":\"" + day + "\"}"});
        db.getWritableDatabase().execSQL("DELETE FROM nights WHERE day = ?", new Object[] {day});
        db.replaceNights(napStart, napStart + 1, joined);
        db.put("analyzed_until", (long) (from + 2 * 86400));
        Analyzer.run(c, null);
        List<double[]> third = storedSleeps(db, day, day);
        check("#132 a joined row split again: both pieces stored, no overlap", third.size() == 2
                && third.get(0)[0] == napStart && third.get(0)[1] == napStop
                && Math.abs(third.get(1)[0] - at(d, 19.5)) < 1 && third.get(0)[1] <= third.get(1)[0], spans(third));
        // the nights after midnight are as before: one each
        for (int k = 2; k <= 4; k++) {
            String dk = d.plusDays(k).toString();
            check("#132 night " + (k - 1) + " after it stored once", storedSleeps(db, dk, dk).size() == 1, spans(storedSleeps(db, dk, dk)));
        }
        freshDb("openring132-done"); // the next test opens its own phone
    }

    /** A fresh phone with {@code sleeps} ({from, to} worn and asleep) and {@code phone} use, analysed once. */
    static Db sleepsWithPhone(String name, LocalDate d, List<double[]> sleeps, List<double[]> phone) throws Exception {
        nightCtx = freshDb(name);
        Db db = Db.get(nightCtx);
        long t0 = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long ds0 = 9L * 86400 * 10;
        List<RingEvent> ev = new ArrayList<RingEvent>();
        double last = 0;
        for (double[] x : sleeps) {
            wornSleep(ev, x[0], x[1], new ArrayList<double[]>(), t0, ds0);
            last = Math.max(last, x[1]);
        }
        java.util.Collections.sort(ev, new java.util.Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        db.insertBatch(ev, 1, (long) (last + 1800));
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - ds0) / 10);
        if (!phone.isEmpty()) db.putScreen(phone);
        Analyzer.run(nightCtx, null);
        return db;
    }

    static Context nightCtx;

    /** Runs the analysis again as an incremental run whose first chunk starts at {@code midnight}; returns its log. */
    static String runFromMidnight(Db db, double midnight) {
        final StringBuilder log = new StringBuilder();
        // from = until - 2 days and the first chunk starts at midnight of the day 36 hours before from
        db.put("analyzed_until", (long) (midnight + 36 * 3600 + 18 * 3600 + 2 * 86400));
        Analyzer.run(nightCtx, new Analyzer.Log() {
            @Override
            public void log(String m) {
                log.append(m).append('\n');
            }
        });
        return log.toString();
    }

    static int mains(Db db, String day) {
        return db.nightsJson(day, day, true).size();
    }

    /**
     * Review of #132: a stored night alone moving the boundary, one main sleep per day when the boundary
     * moves into the day before, and the log line only when a stored sleep the old rule kept is replaced.
     */
    static void nightAcrossChecks() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 12);
        String day = d.toString(), next = d.plusDays(1).toString();
        double midnight = at(d, 24);
        List<double[]> sleeps = new ArrayList<double[]>();
        sleeps.add(new double[] {at(d, 20), at(d, 23.5)});            // asleep 20:00 to 23:30, before midnight
        for (int k = 2; k <= 4; k++) sleeps.add(new double[] {at(d.plusDays(k), -1), at(d.plusDays(k), 7)});
        Db db = sleepsWithPhone("openring132b", d, sleeps, new ArrayList<double[]>());
        List<double[]> first = storedSleeps(db, day, day);
        check("#132 review set up: one sleep before midnight", first.size() == 1 && first.get(0)[1] < midnight, spans(first));
        // an older run stored it longer, past midnight (day after); no sleep found now crosses midnight
        double s0 = first.get(0)[0];
        db.getWritableDatabase().execSQL("DELETE FROM nights");
        List<Object[]> old = new ArrayList<Object[]>();
        old.add(new Object[] {s0 - 600, midnight + 900, next, 1, 60, "{\"start\":" + (s0 - 600) + ",\"end\":" + (midnight + 900) + ",\"main\":true}"});
        db.replaceNights(0, Double.MAX_VALUE, old);
        String log = runFromMidnight(db, midnight);
        List<double[]> now = storedSleeps(db, day, next);
        check("#132 a stored night alone across the start moves it: the sleep is rebuilt, the old row gone", now.size() == 1
                && now.get(0)[0] == first.get(0)[0] && now.get(0)[1] == first.get(0)[1] && mains(db, day) == 1, spans(now));
        check("#132 the day the old row was on has no sleep left", storedSleeps(db, next, next).isEmpty() && mains(db, next) == 0, spans(storedSleeps(db, next, next)));
        check("#132 that run logs nothing about replaced sleeps (only the stored night crossed)", !log.contains("replaced "), log);

        // a long nap 11:30 to 16:00 and an evening sleep to 00:30 with phone use from 23:50 (both day d);
        // phone use at first also 19:00 to 21:30, so the nap is the main sleep
        sleeps = new ArrayList<double[]>();
        sleeps.add(new double[] {at(d, 11.5), at(d, 16)});
        sleeps.add(new double[] {at(d, 19), at(d, 24.5)});
        for (int k = 2; k <= 4; k++) sleeps.add(new double[] {at(d.plusDays(k), -1), at(d.plusDays(k), 7)});
        List<double[]> phone = new ArrayList<double[]>();
        phone.add(new double[] {at(d, 18.95), at(d, 21.5)});
        phone.add(new double[] {at(d, 23 + 50 / 60.0), at(d, 24.75)});
        db = sleepsWithPhone("openring132c", d, sleeps, phone);
        List<String> mainBefore = db.nightsJson(day, day, true);
        double napStart = storedSleeps(db, day, day).get(0)[0];
        check("#132 review set up: the long nap is the main sleep", storedSleeps(db, day, day).size() == 2 && mainBefore.size() == 1
                && Json.parse(mainBefore.get(0)).optDouble("start") == napStart, spans(storedSleeps(db, day, day)));
        // the evening phone use turns out to be someone else's phone (deleted): the evening sleep is longer
        // now and the next run, starting at midnight, rebuilds it; the nap ending before stays and loses main
        db.getWritableDatabase().execSQL("DELETE FROM screen WHERE start = ?", new Object[] {at(d, 18.95)});
        log = runFromMidnight(db, midnight);
        List<String> mainAfter = db.nightsJson(day, day, true);
        JSONObject napJ = null;
        for (String js : db.nightsJson(day, day, false)) if (Json.parse(js).optDouble("start") == napStart) napJ = Json.parse(js);
        check("#132 one main sleep for the day, the longer evening sleep", mainAfter.size() == 1
                && Json.parse(mainAfter.get(0)).optDouble("start") > napStart && storedSleeps(db, day, day).size() == 2,
                mainAfter.size() + " main, " + spans(storedSleeps(db, day, day)));
        check("#132 the stored nap stays, now marked as a nap in its row and its JSON", napJ != null && !napJ.optBoolean("main"), napJ == null ? null : napJ.opt("main"));
        check("#132 replacing the sleep the old rule left in place is logged", log.contains("rebuilt 1 stored sleep that ended just before 00:00 (it ran past midnight)"), log);
        // and back: with the phone use again the nap is the main sleep again
        List<double[]> again = new ArrayList<double[]>();
        again.add(new double[] {at(d, 18.95), at(d, 21.5)});
        db.putScreen(again);
        runFromMidnight(db, midnight);
        mainAfter = db.nightsJson(day, day, true);
        check("#132 the nap is the main sleep again once the evening is shorter", mainAfter.size() == 1
                && Json.parse(mainAfter.get(0)).optDouble("start") == napStart, mainAfter.size());

        // code review of f2410ca: the rebuilt sleep is not stored again (phone in use nearly all evening):
        // the nap that stays becomes the day's main sleep, so the day keeps a sleep total
        sleeps = new ArrayList<double[]>();
        sleeps.add(new double[] {at(d, 16), at(d, 17.5)});
        sleeps.add(new double[] {at(d, 19), at(d, 24.5)});
        for (int k = 2; k <= 4; k++) sleeps.add(new double[] {at(d.plusDays(k), -1), at(d.plusDays(k), 7)});
        phone = new ArrayList<double[]>();
        phone.add(new double[] {at(d, 23 + 50 / 60.0), at(d, 24.75)});
        db = sleepsWithPhone("openring132d", d, sleeps, phone);
        check("#132 review set up: nap and evening sleep, the evening one main", storedSleeps(db, day, day).size() == 2
                && mains(db, day) == 1 && Json.parse(db.nightsJson(day, day, true).get(0)).optDouble("start") > at(d, 18), spans(storedSleeps(db, day, day)));
        List<double[]> evening = new ArrayList<double[]>();
        evening.add(new double[] {at(d, 18.9), at(d, 21.4)}); // two sessions (one of over 4 hours is not used)
        evening.add(new double[] {at(d, 21.4), at(d, 23.8)});
        db.putScreen(evening);
        log = runFromMidnight(db, midnight);
        JSONObject dayJ = Json.parse(db.dayJson(day));
        check("#132 the evening sleep gone, the nap that stays is the main sleep", storedSleeps(db, day, day).size() == 1 && mains(db, day) == 1,
                spans(storedSleeps(db, day, day)) + ", " + mains(db, day) + " main");
        check("#132 the day keeps a sleep total from the nap", dayJ != null && dayJ.optDouble("asleep_min", 0) > 0, dayJ == null ? null : dayJ.opt("asleep_min"));
        check("#132 the log says the stored sleep was removed", log.contains("removed 1 stored sleep that ended just before 00:00"), log);
        freshDb("openring132-review-done");
    }

    // ------------------------------------------------------------------ issue #58, stale day rows

    static final ZoneId Z58 = ZoneId.systemDefault();
    static final long T58 = LocalDate.parse("2026-10-01").atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

    /** A fresh phone with {@code days} of demo history from {@code t0} in boot 1. */
    static Context history58(String name, long t0, int days) throws Exception {
        Context c = freshDb(name);
        add58(Db.get(c), 1, t0, days, 0);
        return c;
    }

    /** Three days in boot 1 and, after a ring restart, one more day in boot 2 whose phone anchor is {@code shift} seconds off. */
    static Context twoBoots58(String name, long shift) throws Exception {
        Context c = history58(name, T58, 3);
        add58(Db.get(c), 2, T2_58, 1, shift);
        return c;
    }

    static final long T2_58 = T58 + 4 * 86400L, T3_58 = T58 + 6 * 86400L;

    static String date58(double unix) {
        return java.time.Instant.ofEpochSecond((long) unix).atZone(Z58).toLocalDate().toString();
    }

    /** Zone history: the phone's zone, then Tokyo from 12 hours before boot 2. */
    static void tokyo58(Db db) {
        db.noteZone(T58 - 86400, Z58.getId());
        db.noteZone(T2_58 - 12 * 3600, "Asia/Tokyo");
    }

    static void add58(Db db, int epoch, long t0, int days, long shift) {
        long end = t0 + days * 86400L + 12 * 3600;
        List<RingEvent> ev = app.openring.data.Demo.history(t0, days, end, Z58, new Random(58 + epoch));
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), epoch, end);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(epoch, lastDs, t0 + (lastDs - app.openring.data.Demo.DS0) / 10 + shift);
    }

    /** Sets the time of the phone anchor of boot {@code epoch} (a stand in for time records that date the events again). */
    static void moveAnchor(Db db, int epoch, long t0, long shift) {
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT id, ds FROM events WHERE tag = 66 AND epoch = ?", new String[] {String.valueOf(epoch)});
        try {
            while (c.moveToNext()) {
                long ds = c.getLong(1);
                db.getWritableDatabase().execSQL("UPDATE events SET body = ? WHERE id = ?",
                        new Object[] {app.openring.core.TimeLine.phoneAnchorBody(t0 + (ds - app.openring.data.Demo.DS0) / 10 + shift), c.getLong(0)});
            }
        } finally {
            c.close();
        }
    }

    /** day to JSON of every stored day. */
    static java.util.TreeMap<String, String> days58(Db db) {
        java.util.TreeMap<String, String> out = new java.util.TreeMap<String, String>();
        for (String[] r : db.daysJson("2000-01-01", "2100-01-01")) out.put(r[0], r[1]);
        return out;
    }

    /** Every stored night: start, stop, day, main and its JSON. */
    static List<String> nights58(Db db) {
        List<String> out = new ArrayList<String>();
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT start, stop, day, main, json FROM nights ORDER BY start", null);
        try {
            while (c.moveToNext()) out.add(c.getDouble(0) + " " + c.getDouble(1) + " " + c.getString(2) + " " + c.getInt(3) + " " + c.getString(4));
        } finally {
            c.close();
        }
        return out;
    }

    /** id, tag, ds, body and the exact date of every raw event. */
    static List<String> events58(Db db) {
        List<String> out = new ArrayList<String>();
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT id, tag, ds, body, unix FROM events ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                out.add(c.getLong(0) + " " + c.getInt(1) + " " + c.getLong(2) + " " + java.util.Arrays.hashCode(c.getBlob(3))
                        + " " + (c.isNull(4) ? "-" : Long.toString(Double.doubleToLongBits(c.getDouble(4)))));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** A copy of the stored day {@code from} under the date {@code to}, as an older version could leave it. */
    static void staleCopy(Db db, String from, String to) {
        JSONObject o = Json.parse(db.dayJson(from));
        Json.put(o, "date", to);
        db.putDay(to, o.toString());
    }

    static String keys(java.util.Map<String, String> m) {
        return m.keySet().toString();
    }

    /** The days that differ between {@code a} and {@code b} (missing on one side or with another JSON). */
    static String diff(java.util.Map<String, String> a, java.util.Map<String, String> b) {
        java.util.TreeSet<String> all = new java.util.TreeSet<String>(a.keySet());
        all.addAll(b.keySet());
        StringBuilder sb = new StringBuilder();
        for (String k : all) if (a.get(k) == null || !a.get(k).equals(b.get(k))) sb.append(sb.length() == 0 ? "" : ", ").append(k);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static Analyzer.Log collect(final StringBuilder sb) {
        return new Analyzer.Log() {
            @Override
            public void log(String m) {
                sb.append(m).append('\n');
            }
        };
    }

    /** Thrown by a log that stops the analysis part way (a phone that kills the app). */
    static final class Stop extends RuntimeException {
        Stop() {
            super("stopped for the test");
        }
    }

    static Analyzer.Log stopAt(final String text) {
        return new Analyzer.Log() {
            @Override
            public void log(String m) {
                if (m.contains(text)) throw new Stop();
            }
        };
    }

    /**
     * Issue #58: a day that has no data left after the events were dated again loses its stored row,
     * also after the newest dated event; a day whose events only wait for a time record keeps it.
     */
    static void staleDays() throws Exception {
        // the reference: the same history dated right from the start
        Context c = twoBoots58("openring58-ref", 0);
        Analyzer.run(c, null);
        Db db = Db.get(c);
        java.util.TreeMap<String, String> refDays = days58(db);
        List<String> refNights = nights58(db);
        String refLatest = db.latestDay();
        check("#58 set up: reference history analysed", refDays.size() >= 5 && refNights.size() >= 4, keys(refDays));

        // 1. the newest boot dated one or two days too late (its phone anchor was off), then dated right by
        // its time record: the old newest day lies inside the analysed range, or after the newest dated event
        for (int shift = 1; shift <= 2; shift++) {
            c = twoBoots58("openring58-shift" + shift, shift * 86400L);
            Analyzer.run(c, null);
            db = Db.get(c);
            String wrong = db.latestDay();
            check("#58 set up: dated " + shift + " day(s) too late, newest day " + wrong,
                    LocalDate.parse(refLatest).plusDays(shift).toString().equals(wrong), keys(days58(db)));
            moveAnchor(db, 2, T2_58, 0);
            StringBuilder log = new StringBuilder();
            Analyzer.run(c, collect(log));
            String where = shift == 1 ? "inside the analysed range" : "after the newest dated event";
            check("#58 dated again (" + where + "): the stale days are removed, the others as dated right at once", days58(db).equals(refDays),
                    keys(days58(db)) + ", differ: " + diff(days58(db), refDays));
            check("#58 dated again (" + where + "): the main screen opens on the real newest day", refLatest.equals(db.latestDay()), db.latestDay());
            check("#58 dated again (" + where + "): the nights are the same as dated right at once", nights58(db).equals(refNights), nights58(db).size() + " nights");
            check("#58 dated again (" + where + "): the log names the removed day", log.indexOf("no data left for " + wrong + ", its summary was removed") >= 0, log);
        }

        // 1b. a sleep still stored for that day (written after the sleeps were found) keeps its summary, as before
        c = twoBoots58("openring58-nap", 2 * 86400L);
        Analyzer.run(c, null);
        final Db napDb = Db.get(c);
        final String napDay = napDb.latestDay();
        final double napAt = LocalDate.parse(napDay).atTime(14, 0).atZone(Z58).toEpochSecond();
        moveAnchor(napDb, 2, T2_58, 0);
        Analyzer.run(c, new Analyzer.Log() {
            @Override
            public void log(String m) {
                if (!m.endsWith(" sleep periods")) return;
                List<Object[]> nap = new ArrayList<Object[]>();
                nap.add(new Object[] {napAt, napAt + 1800, napDay, 0, 50, "{\"start\":" + napAt + ",\"end\":" + (napAt + 1800) + ",\"day\":\"" + napDay + "\"}"});
                napDb.replaceNights(napAt, napAt + 1, nap);
            }
        });
        check("#58 a day with a sleep still stored keeps its summary", napDb.dayJson(napDay) != null, napDay);

        // 2. stopped part way, then run again: no day with data is lost, and the second run finishes the job
        c = twoBoots58("openring58-stop", 2 * 86400L);
        Analyzer.run(c, null);
        db = Db.get(c);
        java.util.TreeMap<String, String> before = days58(db);
        moveAnchor(db, 2, T2_58, 0);
        boolean stopped = false;
        try {
            Analyzer.run(c, stopAt("sleep periods"));
        } catch (Stop e) {
            stopped = true;
        }
        check("#58 stopped while finding sleeps: every stored day is still there", stopped && days58(db).equals(before), diff(days58(db), before));
        stopped = false;
        try {
            Analyzer.run(c, stopAt("its summary was removed"));
        } catch (Stop e) {
            stopped = true;
        }
        java.util.TreeMap<String, String> mid = days58(db);
        boolean kept = true;
        for (String k : refDays.keySet()) kept &= mid.containsKey(k);
        check("#58 stopped after removing the first stale day: every day with data is still there", stopped && kept && mid.size() > refDays.size(), keys(mid));
        check("#58 the stop is recorded as an analysis error", db.kv("analysis_error", null) != null, db.kv("analysis_error", null));
        Analyzer.run(c, null);
        check("#58 run again after the stop: the same days and nights as dated right at once",
                days58(db).equals(refDays) && nights58(db).equals(refNights), diff(days58(db), refDays));
        Analyzer.run(c, null);
        check("#58 a second run changes nothing", days58(db).equals(refDays) && nights58(db).equals(refNights), diff(days58(db), refDays));

        // 3. dating not complete yet: the newest boot's time record cannot be used for now (a sync cut
        // off before its phone anchor), so its events wait undated. Their days are not removed.
        c = twoBoots58("openring58-wait", 0);
        Analyzer.run(c, null);
        db = Db.get(c);
        // review of #176: an empty day before the newest dated event still goes while boot 2 waits
        String early = LocalDate.parse(refDays.firstKey()).minusDays(1).toString(); // the first day the analysis visits
        staleCopy(db, refDays.firstKey(), early);
        moveAnchor(db, 2, T2_58, 1300000000L - T2_58); // dated in 2011: not usable, so boot 2 is undated
        StringBuilder log = new StringBuilder();
        Analyzer.run(c, collect(log));
        java.util.TreeMap<String, String> waiting = days58(db);
        boolean all = true;
        for (String k : refDays.keySet()) all &= waiting.containsKey(k) && waiting.get(k).equals(refDays.get(k));
        check("#58 events waiting for a time record: no stored day is removed or changed", all && db.undatedCount() > 0,
                keys(waiting) + ", " + db.undatedCount() + " undated");
        check("#58 the log says why they are kept", log.indexOf("kept: events after it wait for a time record") >= 0, log);
        check("#58 while boot 2 waits, an empty day before the newest dated event is still removed", !waiting.containsKey(early),
                early + " in " + keys(waiting));
        moveAnchor(db, 2, T2_58, 0);
        Analyzer.run(c, null);
        check("#58 once dated again: the same days and nights as dated right at once", days58(db).equals(refDays) && nights58(db).equals(refNights),
                diff(days58(db), refDays));
        // a phone anchor that fits no boot stays undated for good; it must not keep stale days forever
        db.insertPhoneAnchor(99, 5, T2_58 + 86400);
        String after = LocalDate.parse(refLatest).plusDays(3).toString();
        staleCopy(db, refLatest, after);
        db.put("analyzed_until", 0);
        Analyzer.run(c, null);
        check("#58 an undated phone anchor that fits no boot does not keep a stale day", db.undatedCount() == 1 && days58(db).equals(refDays),
                db.undatedCount() + " undated, differ: " + diff(days58(db), refDays));

        // review of #176: an undated boot between two dated ones does not hold the fix back; its days have no
        // data now and go, and come back once it is dated again
        c = twoBoots58("openring58-three-ref", 0);
        add58(Db.get(c), 3, T3_58, 1, 0);
        Analyzer.run(c, null);
        db = Db.get(c);
        java.util.TreeMap<String, String> ref3 = days58(db);
        List<String> ref3Nights = nights58(db);
        c = twoBoots58("openring58-three", 0);
        add58(Db.get(c), 3, T3_58, 1, 0);
        Analyzer.run(c, null);
        db = Db.get(c);
        String newest3 = db.latestDay(), after3 = LocalDate.parse(newest3).plusDays(3).toString();
        staleCopy(db, newest3, after3);
        moveAnchor(db, 2, T2_58, 1300000000L - T2_58);
        Analyzer.run(c, null);
        java.util.TreeMap<String, String> mid3 = days58(db);
        boolean outer = !mid3.containsKey(after3) && newest3.equals(db.latestDay());
        for (String k : refDays.keySet()) if (k.compareTo(date58(T2_58)) < 0) outer &= ref3.get(k).equals(mid3.get(k));
        check("#58 undated boot between two dated ones: the stale later day goes, the dated boots' days stay", outer, keys(mid3));
        moveAnchor(db, 2, T2_58, 0);
        Analyzer.run(c, null);
        check("#58 the middle boot dated again: the same days and nights as dated right at once", days58(db).equals(ref3) && nights58(db).equals(ref3Nights),
                diff(days58(db), ref3));

        // review of #176: the newest boot in another zone than the phone's (Tokyo, 7 or 8 hours ahead)
        c = twoBoots58("openring58-zone-ref", 0);
        tokyo58(Db.get(c));
        Analyzer.run(c, null);
        db = Db.get(c);
        java.util.TreeMap<String, String> refZ = days58(db);
        List<String> refZNights = nights58(db);
        String refZLatest = db.latestDay();
        c = twoBoots58("openring58-zone", 86400L);
        tokyo58(Db.get(c));
        Analyzer.run(c, null);
        db = Db.get(c);
        String wrongZ = db.latestDay();
        moveAnchor(db, 2, T2_58, 0);
        Analyzer.run(c, null);
        check("#58 in another zone: dated again, the same days and nights as dated right at once", !wrongZ.equals(refZLatest)
                && days58(db).equals(refZ) && nights58(db).equals(refZNights) && refZLatest.equals(db.latestDay()),
                wrongZ + " to " + db.latestDay() + ", differ: " + diff(days58(db), refZ));

        // 4. an older database with history and stale days left by an older version: same events,
        // nights and days after the update, except the stale ones
        c = history58("openring58-old", T58, 3);
        Analyzer.run(c, null);
        db = Db.get(c);
        refDays = days58(db);
        refNights = nights58(db);
        refLatest = db.latestDay();
        String gapDay = LocalDate.parse(refLatest).plusDays(1).toString(), farDay = LocalDate.parse(refLatest).plusDays(4).toString();
        staleCopy(db, refLatest, gapDay);
        staleCopy(db, refLatest, farDay);
        app.openring.data.Prefs.of(c).saveKey(app.openring.core.Hex.parseKey("4431967d8bacc2659743142b68391d9a"));
        java.io.ByteArrayOutputStream backupNow = new java.io.ByteArrayOutputStream();
        app.openring.data.Backup.write(c, backupNow, true);
        List<String> oldEvents = events58(db);
        db.close();
        java.io.File f = c.getDatabasePath(Db.REAL);
        try (java.sql.Connection cn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
             java.sql.Statement st = cn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            st.execute("DROP TABLE zones");
            st.execute("DROP TABLE IF EXISTS screen_reads");
            st.execute("PRAGMA user_version = 4");
        }
        byte[] v4 = Files.readAllBytes(f.toPath());
        Context up = freshDb("openring58-up");
        Files.write(up.getDatabasePath(Db.REAL).toPath(), v4);
        db = Db.get(up);
        check("#58 an older database opens with its stale days", days58(db).containsKey(farDay) && days58(db).containsKey(gapDay), keys(days58(db)));
        Analyzer.run(up, null);
        check("#58 after the update: every raw event the same, row by row", events58(db).equals(oldEvents), events58(db).size() + " events");
        check("#58 after the update: the nights unchanged", nights58(db).equals(refNights), nights58(db).size() + " nights");
        check("#58 after the update: the days unchanged, only the stale ones removed", days58(db).equals(refDays), diff(days58(db), refDays));

        // 5. restoring a backup with stale days, made by this version and by an older one
        byte[] zip = backupNow.toByteArray();
        for (int k = 0; k < 2; k++) {
            Context ph = freshDb("openring58-restore" + k);
            byte[] in = k == 0 ? zip : withDb(zip, v4);
            app.openring.data.Backup.Result r = app.openring.data.Backup.read(ph, new java.io.ByteArrayInputStream(in));
            Db rdb = Db.get(ph);
            String which = k == 0 ? "this version" : "an older version";
            check("#58 restore of a backup from " + which + " with stale days", r.ok && days58(rdb).containsKey(farDay), r.message);
            Analyzer.run(ph, null);
            check("#58 restored from " + which + ": events, nights and days kept, only the stale days removed",
                    events58(rdb).equals(oldEvents) && nights58(rdb).equals(refNights) && days58(rdb).equals(refDays), diff(days58(rdb), refDays));
        }
        staleLong();
        freshDb("openring58-done");
    }

    /** Issue #58 with a long history: a whole history analysed again removes only the stale days, and fast enough. */
    static void staleLong() throws Exception {
        int n = 60;
        Context c = history58("openring58-long", LocalDate.parse("2026-07-01").atStartOfDay(Z58).toEpochSecond(), n);
        Analyzer.run(c, null);
        Db db = Db.get(c);
        java.util.TreeMap<String, String> ref = days58(db);
        List<String> refN = nights58(db);
        String newest = db.latestDay();
        // stale rows after the newest day, as a phone clock once set ahead could leave them
        for (int k = 2; k <= 11; k++) staleCopy(db, newest, LocalDate.parse(newest).plusDays(k).toString());
        db.put("analyzed_until", 0); // the whole history, as after the dating changed
        long t = System.nanoTime();
        Analyzer.run(c, null);
        long ms = (System.nanoTime() - t) / 1000000;
        System.out.println("  #58: " + n + " days analysed again with 10 stale days in " + ms + " ms");
        check("#58 long history: only the 10 stale days removed, every other day and night the same",
                days58(db).equals(ref) && nights58(db).equals(refN), diff(days58(db), ref));
    }
    // ------------------------------------------------------- issue #139, earlier nights read once per run

    interface Phone139 {
        Context make(String name) throws Exception;
    }

    interface Runs139 {
        void run(Context c) throws Exception;
    }

    static void setStatic(String field, Object v) throws Exception {
        java.lang.reflect.Field f = Analyzer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    /** Every column of every stored night and day, start and stop by their exact bits (issue #139). */
    static List<String> results139(Db db) {
        List<String> out = new ArrayList<String>();
        android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT start, stop, day, main, score, json FROM nights ORDER BY start", null);
        try {
            while (c.moveToNext()) out.add("night " + Double.doubleToLongBits(c.getDouble(0)) + " " + Double.doubleToLongBits(c.getDouble(1)) + " "
                    + c.getString(2) + " " + c.getInt(3) + " " + (c.isNull(4) ? "null" : String.valueOf(c.getLong(4))) + " " + c.getString(5));
        } finally {
            c.close();
        }
        for (String[] r : db.daysJson("0000-01-01", "9999-12-31")) out.add("day " + r[0] + " " + r[1]);
        return out;
    }

    static long nightRows(List<String> rows) {
        long n = 0;
        for (String r : rows) if (r.startsWith("night ")) n++;
        return n;
    }

    static String firstDiff(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) return "row " + i + ": " + a.get(i).substring(0, Math.min(120, a.get(i).length()))
                    + " | " + b.get(i).substring(0, Math.min(120, b.get(i).length()));
        }
        return a.size() + " and " + b.size() + " rows";
    }

    /** Set up the phone, run the steps with the history store on or off, return its rows; the store is on again after. */
    static List<String> analysed139(String name, Phone139 p, Runs139 s, boolean cache) throws Exception {
        setStatic("cacheHistory", cache);
        try {
            Context c = p.make(name);
            s.run(c);
            return results139(Db.get(c));
        } finally {
            setStatic("cacheHistory", true);
        }
    }

    static long ms139;

    /** A full re analysis, timed into {@link #ms139}. */
    static void timedRunAll(Context c, Analyzer.Log log) {
        long t = System.nanoTime();
        Analyzer.runAll(c, log);
        ms139 = (System.nanoTime() - t) / 1000000;
    }

    static final int DAYS139 = 130;
    static final long T139 = LocalDate.parse("2025-02-03").atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

    /** 130 days of demo history across the spring clock change, the last 12 days with the phone in Tokyo. */
    static Context demo139(String name) throws Exception {
        Context c = freshDb(name);
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        long end = T139 + (DAYS139 + 1) * 86400L + 12 * 3600;
        db.noteZone(T139 - 86400, z.getId());
        db.noteZone(T139 + (DAYS139 - 12) * 86400L, "Asia/Tokyo");
        putBoot(db, app.openring.data.Demo.history(T139, DAYS139, end, z, new Random(139)), 1, T139, end);
        return c;
    }

    /**
     * Issue #139: the history store gives byte identical nights and days (Re analyse all, a sync after, other
     * histories), an older database analysed without it stays the same, a stopped run leaves nothing behind,
     * and a full re analysis is at least twice as fast.
     */
    static void historyCache() throws Exception {
        final StringBuilder log = new StringBuilder();
        final long[] ms = new long[2];
        Phone139 demo = new Phone139() {
            @Override
            public Context make(String name) throws Exception {
                return demo139(name);
            }
        };
        List<String> off = analysed139("openring139-off", demo, new Runs139() {
            @Override
            public void run(Context c) {
                timedRunAll(c, null);
                ms[0] = ms139;
                Analyzer.run(c, null);
            }
        }, false);
        List<String> on = analysed139("openring139-on", demo, new Runs139() {
            @Override
            public void run(Context c) {
                timedRunAll(c, collect(log));
                ms[1] = ms139;
                Analyzer.run(c, null);
            }
        }, true);
        check("#139 set up: " + DAYS139 + " days of demo history analysed", nightRows(off) >= DAYS139 - 5 && off.size() - nightRows(off) >= DAYS139 - 5,
                nightRows(off) + " nights, " + (off.size() - nightRows(off)) + " days");
        check("#139 Re analyse all and a sync: every night and day the same with the store, byte for byte", on.equals(off), firstDiff(on, off));
        System.out.println("  #139: Re analyse all of " + DAYS139 + " days: " + ms[0] + " ms reading every day's history from the database, "
                + ms[1] + " ms with the store");
        // the timing is logged above; the check is lenient (a busy test machine), the read counts below are exact
        check("#139 Re analyse all faster with the store", ms[1] < ms[0], ms[1] + " ms, before " + ms[0] + " ms");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) days summarised, (\\d+) stored nights and (\\d+) stored days read for them, at most (\\d+) kept").matcher(log);
        boolean found = m.find();
        int maxKept = found ? Integer.parseInt(m.group(4)) : -1, nightsRead = found ? Integer.parseInt(m.group(2)) : -1;
        // a day looks back 104 days for nights and 60 for summaries: the store stays below that whatever the history length
        // every night once; a day summary once when a later day reads it (the newest is read by none)
        check("#139 each stored night and day read once", found && nightsRead == nightRows(on)
                && Integer.parseInt(m.group(3)) <= on.size() - nightRows(on) && Integer.parseInt(m.group(3)) >= on.size() - nightRows(on) - 2,
                found ? m.group() : log);
        historyBound();

        // an older database: analysed without the store (as by the version before), then the sync analysis with it
        setStatic("cacheHistory", false);
        Context old = demo139("openring139-old");
        Analyzer.run(old, null);
        Db odb = Db.get(old);
        List<String> before = results139(odb), events = events58(odb);
        setStatic("cacheHistory", true);
        Analyzer.run(old, null);
        check("#139 older database: after the analysis with the store every event, night and day is the same",
                results139(odb).equals(before) && events58(odb).equals(events) && before.equals(off), firstDiff(results139(odb), before));
        odb.put("analyzed_until", 0); // dating changed: everything again, incremental path
        Analyzer.run(old, null);
        check("#139 older database analysed again as a whole: the same", results139(odb).equals(before), firstDiff(results139(odb), before));

        // a run stopped part way (the app killed): nothing of the store survives, the next run gives the same rows
        final int[] count = {0};
        List<String> stopped = analysed139("openring139-stop", demo, new Runs139() {
            @Override
            public void run(Context c) throws Exception {
                Analyzer.run(c, null);
                setStatic("afterDay", new Runnable() {
                    @Override
                    public void run() {
                        if (++count[0] == 40) throw new Stop();
                    }
                });
                try {
                    Analyzer.runAll(c, null);
                } catch (Stop expected) {
                } finally {
                    setStatic("afterDay", null);
                }
                Db db = Db.get(c);
                check("#139 set up: the run stopped after 40 days", count[0] == 40 && db.count("SELECT COUNT(*) FROM days") < DAYS139 - 40
                        && db.kvLong("analyzed_until", -1) == 0, count[0] + " days, " + db.count("SELECT COUNT(*) FROM days") + " stored");
                Analyzer.run(c, null);
            }
        }, true);
        check("#139 stopped run, then the next analysis: the same rows as a run never stopped", stopped.equals(off), firstDiff(stopped, off));

        // other histories: the synthetic one of this test with days off and rest days, the trip of #22, #58 dated again
        final List<RingEvent> gap = history(14, new java.util.HashSet<Integer>(Arrays.asList(4, 5, 6, 10)), new java.util.HashSet<Integer>(Arrays.asList(2, 3, 11)));
        Phone139 synth = new Phone139() {
            @Override
            public Context make(String name) throws Exception {
                Context c = freshDb(name);
                Db db = Db.get(c);
                long now = T0 + 14 * 86400 + 7 * 3600 + 1800; // T0 is shared with main; the history must end before the real now
                for (int i = 0; i < gap.size(); i += 4096) db.insertBatch(gap.subList(i, Math.min(gap.size(), i + 4096)), 1, now);
                long lastDs = gap.get(gap.size() - 1).ds;
                db.insertPhoneAnchor(1, lastDs, T0 + (lastDs - DS0) / 10);
                return c;
            }
        };
        Runs139 runs = new Runs139() {
            @Override
            public void run(Context c) {
                Analyzer.run(c, null);
                Analyzer.runAll(c, null);
            }
        };
        off = analysed139("openring139-synth-off", synth, runs, false);
        on = analysed139("openring139-synth-on", synth, runs, true);
        check("#139 synthetic history with days off: the same with the store", on.equals(off) && nightRows(off) >= 9, nightRows(off) + " nights, " + firstDiff(on, off));

        Phone139 trip = new Phone139() {
            @Override
            public Context make(String name) throws Exception {
                ZoneId zh = ZoneId.of("Europe/Zurich"), ny = ZoneId.of("America/New_York");
                long t0 = LocalDate.parse("2026-09-01").atStartOfDay(zh).toEpochSecond(), end0 = t0 + 11 * 86400L + 12 * 3600;
                long t1 = LocalDate.parse("2026-09-13").atStartOfDay(ny).toEpochSecond(), end1 = t1 + 6 * 86400L + 12 * 3600;
                Context c = freshDb(name);
                Db db = Db.get(c);
                for (int d = 2; d <= 12; d++) db.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(zh).toEpochSecond(), "Europe/Zurich");
                for (int d = 14; d <= 19; d++) db.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(ny).toEpochSecond(), "America/New_York");
                putBoot(db, app.openring.data.Demo.history(t0, 10, end0, zh, new Random(21)), 1, t0, end0);
                putBoot(db, app.openring.data.Demo.history(t1, 5, end1, ny, new Random(22)), 2, t1, end1);
                return c;
            }
        };
        off = analysed139("openring139-trip-off", trip, runs, false);
        on = analysed139("openring139-trip-on", trip, runs, true);
        check("#139 trip in two zones: the same with the store", on.equals(off) && nightRows(off) >= 14, firstDiff(on, off));

        Phone139 shifted = new Phone139() {
            @Override
            public Context make(String name) throws Exception {
                return twoBoots58(name, 86400L);
            }
        };
        Runs139 again = new Runs139() {
            @Override
            public void run(Context c) {
                Analyzer.run(c, null);
                moveAnchor(Db.get(c), 2, T2_58, 0);
                Analyzer.run(c, null);
            }
        };
        off = analysed139("openring139-58-off", shifted, again, false);
        on = analysed139("openring139-58-on", shifted, again, true);
        check("#139 history dated again, a stale day removed (#58): the same with the store", on.equals(off) && nightRows(off) >= 4, firstDiff(on, off));
        freshDb("openring139-done");
    }

    /**
     * Issue #139, review of #180: over a history longer than the 104 days a day looks back, the store keeps at most
     * the nights of 107 days (104 back, the day itself and the two around it) and the summaries of 61 (what is older
     * is dropped as the days move on).
     */
    static void historyBound() throws Exception {
        Context c = freshDb("openring139-long");
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        int days = 250;
        long t0 = LocalDate.parse("2025-01-06").atStartOfDay(z).toEpochSecond(), end = t0 + (days + 1) * 86400L + 12 * 3600;
        putBoot(db, app.openring.data.Demo.history(t0, days, end, z, new Random(250)), 1, t0, end);
        StringBuilder log = new StringBuilder();
        long t = System.nanoTime();
        Analyzer.run(c, collect(log));
        long ms = (System.nanoTime() - t) / 1000000;
        // the most nights of any 107 days in a row, plus 61 day summaries: the bound the window allows
        List<String> starts = new ArrayList<String>();
        android.database.Cursor cur = db.getReadableDatabase().rawQuery("SELECT day FROM nights ORDER BY day", null);
        try {
            while (cur.moveToNext()) starts.add(cur.getString(0));
        } finally {
            cur.close();
        }
        int most = 0;
        for (int i = 0; i < starts.size(); i++) {
            String last = LocalDate.parse(starts.get(i)).plusDays(106).toString();
            int k = i;
            while (k < starts.size() && starts.get(k).compareTo(last) <= 0) k++;
            most = Math.max(most, k - i);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("at most (\\d+) kept").matcher(log);
        int kept = m.find() ? Integer.parseInt(m.group(1)) : -1;
        long total = db.count("SELECT COUNT(*) FROM nights") + db.count("SELECT COUNT(*) FROM days");
        System.out.println("  #139: " + days + " days analysed in " + ms + " ms, at most " + kept + " of " + total + " nights and days kept");
        check("#139 a history longer than the window: the store stays within 107 days of nights and 61 of summaries", kept > 0
                && kept <= most + 61 && kept < total / 2, kept + " kept, bound " + (most + 61) + ", " + total + " stored");
    }

    // ------------------------------------------------------------ issue #98, the recent nights strip

    /** Issue #98: the strip of an old date loads a window of nights, not every night up to today. */
    static void recentWindow() throws Exception {
        Context c = freshDb("openring98");
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-10-26"), first = today.minusDays(399);
        List<Object[]> rows = new ArrayList<Object[]>();
        for (int k = 0; k < 400; k++) {
            LocalDate d = first.plusDays(k);
            double end = d.atTime(6, 45).atZone(z).toEpochSecond(), start = end - 8 * 3600;
            JSONObject js = new JSONObject();
            Json.put(js, "day", d.toString());
            Json.put(js, "start", start);
            Json.put(js, "end", end);
            Json.put(js, "main", true);
            rows.add(new Object[] {start, end, d.toString(), 1, 80, js.toString()});
            // a nap on every fifth day: not in the strip
            if (k % 5 == 0) rows.add(new Object[] {end + 6 * 3600, end + 7 * 3600, d.toString(), 0, 50, js.toString()});
        }
        db.replaceNights(0, Double.MAX_VALUE, rows);
        int yearBack = db.recentNights(today.minusDays(365), today).size(), mid = db.recentNights(today.minusDays(200), today).size();
        int old = db.nightsJson(today.minusDays(365 + 30).toString(), today.plusDays(1).toString(), true).size();
        check("#98 a date a year back loads as many nights as one 200 days back", yearBack == mid && yearBack == 2 * Db.RECENT_DAYS + 1,
                yearBack + " and " + mid + " nights, " + old + " before");
        check("#98 today loads the same nights as before", db.recentNights(today, today).equals(
                db.nightsJson(today.minusDays(30).toString(), today.plusDays(1).toString(), true)), db.recentNights(today, today).size());
        check("#98 ten days back: the 30 days before it up to today", db.recentNights(today.minusDays(10), today).size() == 41,
                db.recentNights(today.minusDays(10), today).size());
        List<String> first98 = db.recentNights(first, today);
        check("#98 the oldest date: its night and the 30 after it", first98.size() == 31
                && first.toString().equals(Json.parse(first98.get(0)).optString("day")), first98.size());
        check("#98 a date in a long gap: no nights in the strip, Further analysis still offered",
                db.recentNights(first.minusDays(100), today).isEmpty() && db.anyMainNight(), db.recentNights(first.minusDays(100), today).size());
        check("#98 no main night stored: no Further analysis", !Db.get(freshDb("openring98-empty")).anyMainNight(), "");
        freshDb("openring98-done");
    }
}
