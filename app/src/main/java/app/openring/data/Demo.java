package app.openring.data;

import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import app.openring.core.EventCodec;
import app.openring.core.Protocol;
import app.openring.core.RingEvent;

/**
 * Demo mode: a made up person's ring history, encoded exactly the way a ring stores it, so the
 * whole real pipeline (dating, sleep detection, staging, scores) runs on it. It lives in its own
 * database file; switching demo off brings back the real data untouched.
 */
public final class Demo {
    private Demo() {}

    public interface Progress {
        void step(String what);
    }

    /** The made up navigation app, left out of the demo's screen time to show how that works. */
    public static final String MAPS = "demo.maps";
    static final String[] APPS = {"demo.messages", "demo.browser", "demo.social", "demo.mail", "demo.music", MAPS, "demo.video"};
    /** How often each of {@link #APPS} is picked for a daytime session. */
    static final double[] SHARE = {0.30, 0.18, 0.22, 0.12, 0.07, 0.06, 0.05};

    public static final long DS0 = 3L * 86400 * 10; // the made up ring booted 3 days before the history starts

    /**
     * The story of demo mode's five weeks (issue #265), so the made up person earns each kind of badge on any day the demo
     * is built: the 4 weeks before the last one are busy ({@code BUSY_ONSET_MIN} more minutes to fall asleep, at
     * least one wake up a night), the last week is easy (no long wake up, and none of the late nights that
     * otherwise come about one night in eight, so its bedtimes are a little earlier). Otherwise bed and wake times follow
     * the same rule as before, so the clock change checks keep their meaning. A short cold {@code COLD_BACK} days before the end (two nights {@code COLD_C} °C warmer with
     * {@code COLD_BPM} beats more, HRV {@code COLD_HRV} ms lower) is followed by a calm night; the night {@code GREAT_BACK} days before the end is
     * the best (heart rate {@code GREAT_BPM} lower, HRV {@code GREAT_HRV} ms higher, 16 minutes to fall asleep). The phone
     * free evenings and quiet days: {@link #phoneFree} and {@link #quiet}.
     */
    static final double COLD_C = 0.8;
    static final int BUSY_ONSET_MIN = 20, COLD_BACK = 15, COLD_BPM = 3, COLD_HRV = 10, GREAT_BACK = 4, GREAT_BPM = 4, GREAT_HRV = 12;

    /**
     * Switch to the demo database and build it unless that was done today. Returns whether it was
     * built now. Blocks; call from a background thread.
     */
    public static boolean enter(Context c, Progress p) {
        return enter(c, p, LocalDate.now(), System.currentTimeMillis() / 1000);
    }

    /** Key in {@code kv}: the day the demo was last built completely (written last, cleared by {@code resetAll}). */
    static final String BUILT = "demo_day";

    /**
     * As above with a chosen "today" and "now" (both in the past), so tests can cover any date and hour.
     * Only one run at a time (issue #29): a second caller waits and then finds the demo built.
     */
    public static boolean enter(Context c, Progress p, LocalDate today, long now) {
        synchronized (Demo.class) {
            return build(c, p, today, now);
        }
    }

    /**
     * Finish a demo that was cut off half way (Android ended the app while it was prepared); a demo
     * built completely on any day stays as it is. Returns whether it was built now. Blocks; call
     * from a background thread.
     */
    public static boolean complete(Context c, Progress p) {
        return complete(c, p, LocalDate.now(), System.currentTimeMillis() / 1000);
    }

    /**
     * Whether a new main screen has to call {@link #complete} (issue #69): demo mode is on and no
     * preparation runs. Android may have ended the app half way, and opening it again from the
     * launcher brings no saved screen, so this does not depend on one; a finished demo returns at
     * once. {@code handedOver}: a turned phone handed this screen the preparation state of the same
     * process, which already checked.
     */
    public static boolean finishOnStart(boolean demoOn, boolean preparing, boolean handedOver) {
        return demoOn && !preparing && !handedOver;
    }

    /** As above with a chosen "today" and "now", for the tests. */
    public static boolean complete(Context c, Progress p, LocalDate today, long now) {
        synchronized (Demo.class) {
            Db db = Db.get(c);
            if (db.eventCount() > 0 && db.kv(BUILT, null) != null) return false;
            return build(c, p, today, now);
        }
    }

    /**
     * Demo mode could not be turned on: a sync or another ring task still stored into the real history after
     * {@link app.openring.core.RingGate#SWAP_WAIT_MS} (issue #219), or the person stopped the wait (a
     * {@link Progress} that also implements {@code RingGate.Stop}, review of #248). Nothing changed; the message says so.
     */
    public static final class NotNow extends RuntimeException {
        /** What held it up, {@code RingGate.FOR_SYNC} and so on, or {@code STOPPED}: the toast is {@code RingGate.demoRefused(why)}. */
        public final int why;

        NotNow(int why) {
            super(app.openring.core.RingGate.demoRefused(why));
            this.why = why;
        }
    }

    private static boolean build(final Context c, final Progress p, final LocalDate today, final long now) {
        if (Prefs.of(c).demo()) return buildWhenIdle(c, p, today, now);
        // the switch closes the real database: never while a ring task stores into it (a sync download keeps its own
        // Db object, and its analysis would run on the demo file afterwards; issue #219). The gate waits for them,
        // holding nothing, and holds the ring while the demo is switched on and built; once it is on, no ring task
        // connects or stores (issue #185)
        final boolean[] built = {false};
        int why = app.openring.core.RingGate.RING.whenFree(new Runnable() {
            @Override
            public void run() {
                built[0] = buildWhenIdle(c, p, today, now);
            }
        }, app.openring.core.RingGate.SWAP_WAIT_MS, Analyzer.RUNNING, p == null ? null : new app.openring.core.RingGate.Wait() {
            @Override
            public void waiting(int why) {
                p.step(app.openring.core.RingGate.waitLine(why));
            }
        }, p instanceof app.openring.core.RingGate.Stop ? (app.openring.core.RingGate.Stop) p : null);
        if (why != app.openring.core.RingGate.RAN) throw new NotNow(why);
        return built[0];
    }

    private static boolean buildWhenIdle(Context c, Progress p, LocalDate today, long now) {
        // the switch closes the real database: never under an analysis's open transaction (review of #243), so a
        // running one is waited for, and none starts until the demo is built
        if (Analyzer.running() && p != null) p.step(WAITING);
        synchronized (Analyzer.class) {
            return buildLocked(c, p, today, now);
        }
    }

    /** The demo's progress line while a re analysis of the person's history ends first (review of #243). */
    public static final String WAITING = app.openring.core.RingGate.WAIT_ANALYSIS;

    private static boolean buildLocked(Context c, Progress p, LocalDate today, long now) {
        Prefs.of(c).setDemo(true);
        Db db = Db.get(c);
        // built completely today: nothing to do. Not "the newest day is today", which is still
        // yesterday until about 04:00 and missing while another run is half way (issue #29)
        if (db.eventCount() > 0 && today.toString().equals(db.kv(BUILT, null)) && WatchStore.any(db) && PhoneStore.any(db)
                && db.count("SELECT COUNT(*) FROM battery") > 0 && LifeLog.any(db)) return false;
        if (p != null) p.step("Making up 5 weeks of nights and days");
        db.resetAll(Db.DEMO_BUILD);
        ZoneId zone = ZoneId.systemDefault();
        int days = 35;
        long t0 = today.minusDays(days).atStartOfDay(zone).toEpochSecond();
        List<double[]> screen = new ArrayList<double[]>();
        java.util.Map<String, List<double[]>> apps = new java.util.LinkedHashMap<String, List<double[]>>();
        List<RingEvent> ev = warmNights(history(t0, days, now, zone, new Random(7), screen, apps, true), t0, zone, app.openring.core.analysis.Cycles.demoWarmNights(today));
        // the ring on its charger yesterday afternoon, so the made up watch fills that time (issue #251, main device)
        ev = charging(ev, t0, at(today.minusDays(1), CHARGE_FROM_H, zone), at(today.minusDays(1), CHARGE_TO_H, zone));
        long lastDs = ev.get(ev.size() - 1).ds;
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), 1, now);
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - DS0) / 10);
        db.putScreen(screen);
        // the made up usage log was "read" from the first demo day to now, so no day is a gap (issue #131)
        db.putScreenRead(t0, now);
        db.putApps(apps);
        periods(db, today, now);
        // made up life factors (issue #292): never Intimacy, only in this file
        LifeLog.demo(db, today, now, days, COLD_BACK);
        watch(db, today, now, zone);
        phone(db, today, now, zone);
        ringBattery(db, today, now, zone);
        if (p != null) p.step("Analysing the demo nights on this phone");
        Analyzer.runAll(c, null);
        // the year before the ring's five weeks, as results only (Trends, issue #374); a second or two, no step of its own
        olderYear(db, today, zone);
        db.put(BUILT, today.toString());
        return true;
    }

    // ------------------------------------------------------------------ the year before (Trends, issue #374)

    /** Days of made up results before the five weeks: about 11 months. */
    public static final int YEAR_DAYS = 334;
    /**
     * Key in {@code kv} (only in the demo file): the last day of the made up results, which Re analyse all keeps
     * ({@code Db.clearResults}): they have no raw events to be worked out from again.
     */
    public static final String YEAR_UNTIL = "demo_year_until";
    /** The made up story of the year: from this many months before today the nights are about 25 minutes longer. */
    public static final int LONGER_FROM_MONTHS = 4;
    /** The made up week of illness ends this many days before the year's last day. */
    public static final int ILL_BACK = 150, ILL_DAYS = 7;

    /** The first day of the made up week of illness. */
    public static LocalDate illFrom(LocalDate today) {
        return yearLast(today).minusDays(ILL_BACK + ILL_DAYS - 1);
    }

    /** The last day of the made up year: two days before the five weeks begin, so no night of the year reaches them. */
    public static LocalDate yearLast(LocalDate today) {
        return today.minusDays(35 + 2);
    }

    /**
     * About 11 months of made up day and night results before the five weeks (issue #374), so Trends has a year to show: copies
     * of the demo's own analysed nights and days (each with its stages, series and score, so an older night opens as a full
     * night), moved to their new date at the same clock times. Copies are picked so the year has a story: nights after
     * ovulation from the demo's warm nights (the period tracker sees each cycle's rise), the nights from
     * {@link #LONGER_FROM_MONTHS} months ago from the longer ones (about 25 minutes more sleep), more steps in summer, and one
     * week of illness ({@link #ILL_DAYS} copies of the nights of the demo's cold: warmer, a higher heart rate, a lower HRV; few
     * steps). Results only,
     * no raw events (like a history from before the ring); {@link #history} and the five weeks stay exactly as they are.
     * Only into {@code db}, the demo file.
     */
    static void olderYear(final Db db, LocalDate today, final ZoneId zone) {
        LocalDate last = yearLast(today), first = last.minusDays(YEAR_DAYS - 1);
        // the sources: the analysed demo days with their main night, not the first (no baselines yet) nor today (not over)
        List<org.json.JSONObject[]> warm = new ArrayList<org.json.JSONObject[]>(), cool = new ArrayList<org.json.JSONObject[]>(),
                ill = new ArrayList<org.json.JSONObject[]>();
        java.util.Set<LocalDate> warmDays = app.openring.core.analysis.Cycles.demoWarmNights(today);
        for (String[] r : db.daysJson(today.minusDays(33).toString(), today.minusDays(1).toString())) {
            org.json.JSONObject day = Json.parse(r[1]);
            List<String> n = db.nightsJson(r[0], r[0], true);
            if (day == null || n.size() != 1 || !day.has("temp_dev") || !day.has("readiness")) continue;
            org.json.JSONObject night = Json.parse(n.get(0));
            if (night == null) continue;
            org.json.JSONObject[] pair = {night, day};
            if (Json.d(day, "temp_dev") >= COLD_C / 2) ill.add(pair);
            else if (warmDays.contains(LocalDate.parse(r[0]))) warm.add(pair);
            else cool.add(pair);
        }
        if (warm.isEmpty() || cool.isEmpty() || ill.isEmpty()) return;
        Comparator<org.json.JSONObject[]> byAsleep = new Comparator<org.json.JSONObject[]>() {
            @Override
            public int compare(org.json.JSONObject[] a, org.json.JSONObject[] b) {
                return Double.compare(Json.d(a[0], "asleep_min"), Json.d(b[0], "asleep_min"));
            }
        };
        Collections.sort(warm, byAsleep);
        Collections.sort(cool, byAsleep);
        java.util.Set<LocalDate> luteal = lutealDays(today);
        LocalDate longer = today.minusMonths(LONGER_FROM_MONTHS), illFrom = illFrom(today);
        final List<Object[]> nights = new ArrayList<Object[]>();
        final List<String[]> days = new ArrayList<String[]>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Random r = new Random(374L * 100000 + d.toEpochDay());
            boolean sick = !d.isBefore(illFrom) && d.isBefore(illFrom.plusDays(ILL_DAYS));
            org.json.JSONObject[] src;
            if (sick) {
                src = ill.get(r.nextInt(ill.size()));
            } else {
                List<org.json.JSONObject[]> pool = luteal.contains(d) ? warm : cool;
                // the shorter 75 % before the change, the longer 75 % from it: about 25 minutes more sleep
                int k = Math.max(1, (int) Math.ceil(pool.size() * 0.75));
                int i = r.nextInt(k);
                src = pool.get(d.isBefore(longer) ? i : pool.size() - k + i);
            }
            LocalDate from = LocalDate.parse(src[1].optString("date", src[0].optString("day")));
            org.json.JSONObject night = moved(src[0], from, d, zone), day = moved(src[1], from, d, zone);
            // a clock change day has 23 or 25 hours: the hour more or less is inactive, as the analysis counts every real minute
            double extra = minutes(d, zone) - minutes(from, zone);
            if (extra != 0 && day.has("inactive_min")) {
                Json.put(day, "inactive_min", Math.max(0, Json.d(day, "inactive_min") + extra));
                if (day.has("day_min")) Json.put(day, "day_min", minutes(d, zone));
            }
            double steps = Json.d(day, "steps");
            if (!Double.isNaN(steps)) {
                // more steps in summer (the most in mid July), few while ill
                double f = sick ? 0.35 : 1 + 0.3 * Math.max(0, Math.cos(2 * Math.PI * (d.getDayOfYear() - 196) / 365.0));
                double active = Json.d(day, "active_kcal");
                Json.put(day, "steps", (Object) Long.valueOf(Math.round(steps * f)));
                if (day.has("distance_m")) Json.put(day, "distance_m", Math.round(Json.d(day, "distance_m") * f * 10) / 10.0);
                if (!Double.isNaN(active)) {
                    Json.put(day, "active_kcal", Math.round(active * f * 100) / 100.0);
                    if (day.has("total_kcal")) Json.put(day, "total_kcal", Math.round((Json.d(day, "total_kcal") + active * (f - 1)) * 1000) / 1000.0);
                }
            }
            org.json.JSONObject sc = night.optJSONObject("score");
            nights.add(new Object[] {Json.d(night, "start"), Json.d(night, "end"), d.toString(), 1,
                sc != null && sc.has("value") ? sc.optInt("value") : null, night.toString()});
            days.add(new String[] {d.toString(), day.toString()});
        }
        final double a = first.minusDays(1).atStartOfDay(zone).toEpochSecond(), b = last.plusDays(1).atStartOfDay(zone).toEpochSecond();
        final String until = last.toString();
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                db.replaceNights(a, b, nights);
                for (String[] x : days) db.putDay(x[0], x[1]);
                db.put(YEAR_UNTIL, until);
            }
        });
    }

    /** Real minutes of {@code d} in {@code zone}: 1440, or 1380 and 1500 on the days the clocks change. */
    static double minutes(LocalDate d, ZoneId zone) {
        return (d.plusDays(1).atStartOfDay(zone).toEpochSecond() - d.atStartOfDay(zone).toEpochSecond()) / 60.0;
    }

    /**
     * The days after ovulation of every cycle of the demo's whole period log ({@code Cycles.demoHistory}), as
     * {@code Cycles.demoWarmNights} gives them for its newest cycles: the {@code Cycles.LUTEAL} days before each period.
     */
    static java.util.Set<LocalDate> lutealDays(LocalDate today) {
        app.openring.core.analysis.Cycles c = app.openring.core.analysis.Cycles.of(app.openring.core.analysis.Cycles.demoHistory(today), null, today);
        java.util.Set<LocalDate> out = new java.util.HashSet<LocalDate>();
        List<LocalDate> starts = new ArrayList<LocalDate>();
        for (app.openring.core.analysis.Cycles.Period p : c.periods) starts.add(p.start);
        if (c.next != null) starts.add(c.next);
        for (int i = 1; i < starts.size(); i++) {
            LocalDate s = starts.get(i);
            for (LocalDate d = s.minusDays(app.openring.core.analysis.Cycles.LUTEAL - 1); d.isBefore(s); d = d.plusDays(1)) out.add(d);
        }
        return out;
    }

    /**
     * A copy of a stored night or day of {@code from} moved to {@code to}: every time in it (unix seconds within three days of
     * {@code from}) at the same clock time {@code to} days later in {@code zone}, its {@code day} and {@code date} the new date.
     */
    public static org.json.JSONObject moved(org.json.JSONObject o, LocalDate from, LocalDate to, ZoneId zone) {
        double lo = from.minusDays(3).atStartOfDay(zone).toEpochSecond(), hi = from.plusDays(4).atStartOfDay(zone).toEpochSecond();
        long days = to.toEpochDay() - from.toEpochDay();
        org.json.JSONObject out = (org.json.JSONObject) move(o, lo, hi, days, zone);
        if (out.has("day")) Json.put(out, "day", to.toString());
        if (out.has("date")) Json.put(out, "date", to.toString());
        return out;
    }

    static Object move(Object v, double lo, double hi, long days, ZoneId zone) {
        if (v instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) v, out = new org.json.JSONObject();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Json.put(out, k, move(o.opt(k), lo, hi, days, zone));
            }
            return out;
        }
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v, out = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) out.put(move(a.opt(i), lo, hi, days, zone));
            return out;
        }
        if (v instanceof Number) {
            double x = ((Number) v).doubleValue();
            if (x >= lo && x < hi) {
                long s = (long) Math.floor(x);
                double shifted = java.time.Instant.ofEpochSecond(s).atZone(zone).plusDays(days).toEpochSecond() + (x - s);
                return v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal ? (Object) shifted : (Object) (long) shifted;
            }
        }
        return v;
    }

    /**
     * The demo ring's battery readings (Today's battery row, owner's wish of 15 November): one every 3 hours, charged yesterday
     * in its charger time ({@code core/DeviceBattery.demoRing}). Only into the demo file, as every demo row.
     */
    static void ringBattery(final Db db, LocalDate today, long now, ZoneId zone) {
        final List<app.openring.core.DeviceBattery.Reading> rows = app.openring.core.DeviceBattery.demoRing(now, 35,
                (long) at(today.minusDays(1), CHARGE_FROM_H, zone), (long) at(today.minusDays(1), CHARGE_TO_H, zone));
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                for (app.openring.core.DeviceBattery.Reading r : rows) db.addBattery(r.t, r.pct);
            }
        });
    }

    /** The demo ring's charging time yesterday, clock hours (issue #251): no movement, no heart rate, no temperature. */
    public static final double CHARGE_FROM_H = 14, CHARGE_TO_H = 16;

    /**
     * Leaves out the daytime readings (movement, heart beats, temperature) from {@code from} to {@code to}, as a ring on its
     * charger has none (issue #251: the demo's gap the made up watch fills). Time records and every other event stay, so the
     * dating is the same. Only for {@link #enter}; {@link #history} itself stays as it is.
     */
    static List<RingEvent> charging(List<RingEvent> ev, long t0, double from, double to) {
        List<RingEvent> out = new ArrayList<RingEvent>(ev.size());
        for (RingEvent e : ev) {
            boolean reading = e.tag == EventCodec.ACTIVITY_INFO || e.tag == EventCodec.GREEN_IBI_QUALITY || e.tag == EventCodec.TEMP_EVENT;
            double unix = t0 + (e.ds - DS0) / 10.0;
            // a movement or heart beat event spans the 10 minutes after its time
            if (reading && unix + 600 > from && unix < to) continue;
            out.add(e);
        }
        return out;
    }

    /**
     * The demo's made up period log (issue #252): 37 cycles over about 3 years before the newest period, so the prediction
     * methods can be compared (issue #383), today on cycle day 20 ({@code Cycles.demoHistory}). Written into {@code db}, the demo file (resetAll above emptied it), never into the real one.
     */
    static void periods(final Db db, LocalDate today, final long now) {
        final java.util.Map<LocalDate, Integer> log = app.openring.core.analysis.Cycles.demoHistory(today);
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                for (java.util.Map.Entry<LocalDate, Integer> e : log.entrySet()) Periods.setFlow(db, e.getKey().toString(), e.getValue(), now);
            }
        });
        // made up morning temperatures by mouth, as a drip import would bring them (phase 1b): the night temperature card
        // shows its second line and Settings an import to undo, all in the demo file
        Periods.importDrip(db, new java.util.TreeMap<LocalDate, Integer>(),
                app.openring.core.DripCsv.demoMouth(today, app.openring.core.analysis.Cycles.demoWarmNights(today)), false, now * 1000L, now);
    }

    /**
     * The demo's nights after ovulation (issue #252, phase 1a2): every night skin temperature reading of a night in
     * {@code warm} (by the day its morning falls on, the reading's time plus 12 hours) gets {@code Cycles.DEMO_RISE} more,
     * so the period tracker's temperature line shows the rise many people have. Only for {@link #enter}; the other events
     * and {@link #history} itself stay as they are.
     */
    public static List<RingEvent> warmNights(List<RingEvent> ev, long t0, ZoneId zone, java.util.Set<LocalDate> warm) {
        List<RingEvent> out = new ArrayList<RingEvent>(ev.size());
        int add = (int) Math.round(app.openring.core.analysis.Cycles.DEMO_RISE * 100);
        for (RingEvent e : ev) {
            if (e.tag == EventCodec.SLEEP_TEMP_EVENT && e.body.length >= 2) {
                long unix = t0 + (e.ds - DS0) / 10;
                LocalDate night = java.time.Instant.ofEpochSecond(unix + 12 * 3600).atZone(zone).toLocalDate();
                if (warm.contains(night)) {
                    int c = (e.body[0] & 0xff) | (e.body[1] & 0xff) << 8;
                    byte[] b = e.body.clone();
                    b[0] = (byte) (c + add);
                    b[1] = (byte) ((c + add) >> 8);
                    e = new RingEvent(e.tag, e.ds, b);
                }
            }
            out.add(e);
        }
        return out;
    }

    /**
     * The demo's made up watch (issue #251): sessions of made up messages ({@code core/withings/WatchDemo}) stored as raw
     * messages and decoded by the same code as a real watch's. Written into {@code db}, the demo file, never the real one.
     */
    static void watch(final Db db, LocalDate today, final long now, ZoneId zone) {
        final List<app.openring.core.withings.WatchDemo.Msg> msgs = app.openring.core.withings.WatchDemo.history(today, 35, now, zone, new Random(251), false);
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                WatchStore.noteDevice(db, app.openring.core.withings.WatchDemo.ADDRESS, app.openring.core.withings.WatchDemo.NAME, "1971", now - 35 * 86400.0);
                for (app.openring.core.withings.WatchDemo.Msg m : msgs) {
                    WatchStore.addRaw(db, m.session, m.seq, app.openring.core.withings.WatchDemo.ADDRESS, m.body, m.captured);
                }
                WatchStore.decodeAll(db.getWritableDatabase());
            }
        });
    }

    /**
     * The demo's made up phone steps (issue #329, phase 1b): hourly readings of a phone carried by day, one restart
     * ({@code core/PhoneSteps.demo}), stored like real readings, in {@code db}, the demo file, never the real one.
     */
    static void phone(Db db, LocalDate today, long now, ZoneId zone) {
        PhoneStore.addAll(db, app.openring.core.PhoneSteps.demo(today, 35, now, zone, new Random(329)));
    }

    /**
     * Back to the person's own data. False, and nothing changes, while an analysis runs or waits (review of #243):
     * the switch closes the database that analysis writes in one transaction, so the caller says to try again.
     */
    public static boolean leave(final Context c) {
        return Analyzer.whenIdle(new Runnable() {
            @Override
            public void run() {
                Prefs.of(c).setDemo(false);
                Db.get(c); // reopens the real database
            }
        });
    }

    /**
     * Unix seconds of a clock time on {@code day}: {@code hours} after its local midnight on the
     * clock (negative for the evening before). Keeps the made up person on clock time across a
     * daylight saving switch (issue #22).
     */
    static double at(LocalDate day, double hours, ZoneId zone) {
        long s = (long) Math.floor(hours * 3600);
        double frac = hours * 3600 - s;
        return day.atStartOfDay().plusSeconds(s).atZone(zone).toEpochSecond() + frac;
    }

    /** Clock hours of {@code unix} after the local midnight that starts {@code day} (can pass 24). */
    static double clockHours(LocalDate day, double unix, ZoneId zone) {
        java.time.LocalDateTime t = java.time.Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone).toLocalDateTime();
        return java.time.Duration.between(day.atStartOfDay(), t).getSeconds() / 3600.0 + (unix - Math.floor(unix)) / 3600;
    }

    static long ds(long t0, double unix) {
        return DS0 + Math.round((unix - t0) * 10);
    }

    static byte[] le16(int... v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            b[2 * i] = (byte) v[i];
            b[2 * i + 1] = (byte) (v[i] >> 8);
        }
        return b;
    }

    /** Made up ring events for {@code days} nights after {@code t0} (public for the pipeline test). */
    public static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r) {
        return history(t0, days, now, zone, r, null, null);
    }

    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen) {
        return history(t0, days, now, zone, r, screen, null);
    }

    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen,
            java.util.Map<String, List<double[]>> apps) {
        return history(t0, days, now, zone, r, screen, apps, false);
    }

    /**
     * As above; when {@code screen} is not null it also gets made up phone screen sessions (issue #13). {@code story}: the
     * five weeks of demo mode, with the busy month, the easy week, the cold, the best night, the quiet days and the phone
     * free evenings that earn each kind of badge (issue #265), and the period tracker's cycle below. Without it the history
     * is exactly the one before #265, so the tests built on it keep their results.
     */
    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen,
            java.util.Map<String, List<double[]>> apps, boolean story) {
        return history(t0, days, now, zone, r, screen, apps, story, story);
    }

    /**
     * As above. With {@code cycle} (only for {@link #enter}, issue #252 phase 2) the nights' skin temperature varies less from
     * night to night (0.07 °C instead of 0.12) and its one warm night (0.6 °C, a fever or a warm room) lies 9 days back instead
     * of the warm spell of 2 nights lately, so the period tracker's rise rule finds the made up rise and shows such a night
     * left out. The random draws are the same either way, so everything else is as before.
     */
    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen,
            java.util.Map<String, List<double[]>> apps, boolean story, boolean cycle) {
        List<RingEvent> ev = new ArrayList<RingEvent>();
        List<Object[]> kept = new ArrayList<Object[]>();
        Random cycleNoise = new Random(252);
        LocalDate first = java.time.Instant.ofEpochSecond(t0).atZone(zone).toLocalDate();
        for (int d = 1; d <= days; d++) {
            // every time is a clock time on this day, so a daylight saving switch does not move it (issue #22)
            LocalDate day = first.plusDays(d);
            int dow = day.getDayOfWeek().getValue(); // 6, 7 weekend mornings
            boolean weekend = dow >= 6;
            // the story (issue #265): a busy month (longer to fall asleep, woken at night), then an easier last
            // week with calm nights; after a short cold one calm night to get well; the best night in the easy week
            boolean great = story && d == days - GREAT_BACK;
            boolean easy = story && easy(d, days);
            boolean busy = story && !easy;
            boolean late = r.nextDouble() < 0.12 && !easy;
            double bed = at(day, -(1.4 - (late ? 1.6 : 0) - (weekend ? 0.4 : 0)), zone) + r.nextGaussian() * 1500;
            double wake = at(day, weekend ? 8.0 : 6.7, zone) + r.nextGaussian() * 1200;
            if (wake > now - 1200) wake = now - 1200;
            if (wake - bed < 3 * 3600) continue;
            // the story's short cold two weeks back: two warm nights with a higher heart rate, readiness drops and comes back
            boolean cold = story && (d == days - COLD_BACK || d == days - COLD_BACK + 1);
            double g = r.nextGaussian();
            if (cycle) g = cycleNoise.nextGaussian(); // its own draws, so the story's extra draws (issue #265) do not move the rise
            double tempOffset = (cycle ? g * 0.07 + (d == days - 9 ? 0.6 : 0) : g * 0.12 + (d > days - 4 && d < days - 1 ? 0.35 : 0)) // a warm spell lately
                    + (cold ? COLD_C : 0); // the cold's fever also with the cycle: the badge Recovered needs readiness to dip (#265)
            int sick = cold ? COLD_BPM : great ? -GREAT_BPM : 0;
            double fitness = d * 0.06; // resting HR slowly improves
            // a few wake ups per night, longer on restless nights
            int bouts = r.nextInt(3) + (r.nextDouble() < 0.25 ? 2 : 0);
            if (story) bouts = easy ? 0 : Math.max(bouts, 1); // the busy month wakes you, the easy week does not
            double[][] wakeBouts = new double[bouts][2];
            for (int k = 0; k < bouts; k++) {
                double at = bed + (0.15 + 0.75 * r.nextDouble()) * (wake - bed);
                wakeBouts[k][0] = at;
                wakeBouts[k][1] = at + (3 + r.nextInt(14)) * 60;
            }
            double onset = 8 * 60 + r.nextInt(20 * 60);
            if (busy) onset += BUSY_ONSET_MIN * 60;
            if (great) onset = 16 * 60;
            // ---- night streams ----
            for (double t = bed; t < wake; t += 30) {
                double f = (t - bed) / (wake - bed);
                boolean awake = t < bed + onset || t > wake - 300 || inside(wakeBouts, t);
                double cyc = Math.sin(2 * Math.PI * (t - bed - onset) / (92 * 60));
                if (((long) (t - bed) / 30) % 10 == 0) {
                    int hr = (int) Math.round(58 + sick - fitness - 8 * Math.sin(Math.PI * Math.min(1, f * 1.5)) + (awake ? 9 : 0)
                            + 2 * cyc + r.nextGaussian());
                    byte[] spi = new byte[10];
                    spi[0] = (byte) (hr * 2);
                    spi[4] = (byte) Math.round((13.8 + r.nextGaussian() * 0.5 + (awake ? 1.5 : 0)) * 8);
                    spi[6] = (byte) (awake ? 15 + r.nextInt(10) : (r.nextDouble() < 0.06 ? 4 : 0));
                    spi[7] = (byte) (awake ? 0 : 1);
                    spi[9] = 0x10;
                    ev.add(new RingEvent(EventCodec.SLEEP_PERIOD_INFO_2, ds(t0, t), spi));
                }
                if (((long) (t - bed) / 30) % 2 == 0) {
                    int ms = awake ? 10 + r.nextInt(15) : (r.nextDouble() < (cyc > 0.6 ? 0.08 : 0.02) ? 3 + r.nextInt(4) : 0);
                    ev.add(new RingEvent(EventCodec.MOTION_EVENT, ds(t0, t), new byte[] {(byte) ms, 1, 2, 3}));
                    ev.add(new RingEvent(EventCodec.SLEEP_TEMP_EVENT, ds(t0, t),
                            le16((int) Math.round((35.25 + tempOffset + 0.2 * Math.sin(Math.PI * f) + r.nextGaussian() * 0.03) * 100))));
                }
                if (((long) (t - bed) / 30) % 4 == 0 && !awake) {
                    byte[] sp = new byte[6];
                    for (int k = 1; k < 6; k++) sp[k] = (byte) Math.max(90, Math.min(99, Math.round(96.5 + r.nextGaussian() * 0.9)));
                    ev.add(new RingEvent(EventCodec.SPO2_EVENT, ds(t0, t), sp));
                }
            }
            for (double t = bed; t < wake; t += 1800) {
                byte[] hrv = new byte[12];
                for (int k = 0; k < 6; k++) {
                    double tt = t + k * 300, f = (tt - bed) / (wake - bed);
                    boolean awake = tt < bed + onset || inside(wakeBouts, tt);
                    hrv[2 * k] = (byte) Math.round(58 + sick - fitness - 8 * Math.sin(Math.PI * Math.min(1, f * 1.5)) + (awake ? 9 : 0));
                    hrv[2 * k + 1] = (byte) Math.max(15, Math.round(42 + 22 * f + fitness * 3 - (awake ? 12 : 0) + (great ? GREAT_HRV : cold ? -COLD_HRV : 0)
                            + r.nextGaussian() * 6));
                }
                ev.add(new RingEvent(EventCodec.HRV_EVENT, ds(t0, t), hrv));
            }
            kept.add(new Object[] {d, day, bed, wake, wakeBouts});
            byte[] bt = new byte[8];
            Protocol.Le.putU32(bt, 0, ds(t0, bed));
            Protocol.Le.putU32(bt, 4, ds(t0, wake));
            ev.add(new RingEvent(EventCodec.BEDTIME_PERIOD, ds(t0, wake + 600), bt));

            // ---- daytime: activity per minute in 10 minute events, a heart beat now and then ----
            boolean workout = r.nextDouble() < 0.45;
            double workoutAt = 17 + r.nextDouble() * 2;
            double dayEnd = Math.min(now - 60, at(day, 22.3, zone));
            for (double t = wake + 600; t < dayEnd; t += 600) {
                double hour = clockHours(day, t, zone);
                byte[] met = new byte[11];
                met[0] = 1;
                for (int k = 1; k <= 10; k++) {
                    double m = 1.2 + r.nextDouble() * 0.5;
                    if (hour > 7.8 && hour < 8.3 && !weekend) m = 3.3; // walk to work
                    if (hour > 12.2 && hour < 12.6) m = 3.0;
                    if (workout && hour > workoutAt && hour < workoutAt + 0.75) m = 7 + r.nextDouble();
                    if (weekend && hour > 10 && hour < 12) m = 2.5 + r.nextDouble();
                    met[k] = (byte) Math.round(m * 10);
                }
                ev.add(new RingEvent(EventCodec.ACTIVITY_INFO, ds(t0, t), met));
                byte[] ibi = new byte[4];
                int i1 = (int) (workout && hour > workoutAt && hour < workoutAt + 0.75 ? 450 + r.nextInt(60) : 760 + r.nextInt(120));
                ibi[0] = (byte) (i1 >> 3);
                ibi[1] = (byte) ((i1 & 7) | (1 << 3));
                ibi[2] = (byte) (i1 >> 3);
                ibi[3] = (byte) ((i1 & 7) | (1 << 3));
                ev.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, ds(t0, t), ibi));
                if (t > now - 3600) ev.add(new RingEvent(EventCodec.TEMP_EVENT, ds(t0, t), le16(3312, 3300, 2900)));
            }
        }
        // phone use after the loop, so "newest" is the newest night that exists: before about 02:00
        // tonight's night is still too short and skipped, then last night gets the phone check
        if (screen != null) {
            for (int i = 0; i < kept.size(); i++) {
                Object[] k = kept.get(i);
                int d = (Integer) k[0];
                double bed = (Double) k[2], wake = (Double) k[3];
                int before = screen.size();
                // before a phone free evening the day's uses end over an hour before bed
                Object[] next = i + 1 < kept.size() ? kept.get(i + 1) : null;
                double until = story && next != null && phoneFree((Integer) next[0], days) ? (Double) next[2] - 75 * 60 : Double.POSITIVE_INFINITY;
                screenDay(screen, new Random(1000 + d), (LocalDate) k[1], zone, bed, wake, (double[][]) k[4], now, i == kept.size() - 1,
                        story && phoneFree(d, days), story && quiet(d, days), story && !easy(d, days), until);
                if (apps != null) appsFor(screen.subList(before, screen.size()), new Random(2000 + d), bed, wake, apps);
            }
        }
        Collections.sort(ev, new Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        return ev;
    }

    /**
     * Made up phone use around one night and the day after it: scrolling that ends 5 to 20 minutes
     * before bed, now and then a phone check during a longer wake up (always in the newest night), a look in the morning and
     * about 35 short to long uses during the day (about 3 hours in all). Its own random numbers, so
     * the nights stay the same as without screen time.
     *
     * <p>Issue #265, for demo mode's story: {@code phoneFree}: the phone was put away in the evening before this night (no scrolling before bed),
     * {@code quiet}: a quiet day after it, with about a third of the uses, {@code busy}: a night of the busy month, whose
     * scrolling goes on until 2 to 6 minutes before bed (it takes longer to fall asleep then, so the hour before sleep
     * still holds some of it), {@code until}: the day's uses end by then (before a phone free evening). Each day has its own
     * random numbers, so the other days stay as they were; without the story all four leave the day as before.
     */
    static void screenDay(List<double[]> out, Random r, LocalDate date, ZoneId zone, double bed, double wake, double[][] wakeBouts, long now,
            boolean newest, boolean phoneFree, boolean quiet, boolean busy, double until) {
        List<double[]> day = new ArrayList<double[]>();
        int gap = r.nextInt(16);
        double eveEnd = bed - (busy ? 2 + gap / 4.0 : 5 + gap) * 60;
        double eveStart = eveEnd - (10 + r.nextInt(26)) * 60;
        if (!phoneFree) day.add(new double[] {eveStart, eveEnd});
        boolean check = false;
        for (double[] b : wakeBouts) {
            if (b[1] - b[0] >= 6 * 60 && r.nextDouble() < 0.5) {
                day.add(new double[] {b[0] + 60, b[0] + 60 + (2 + r.nextInt(3)) * 60});
                check = true;
            }
        }
        // the newest night always has one, so the screenshots show how phone use counts as awake
        if (newest && !check) {
            double at = bed + 0.55 * (wake - bed);
            day.add(new double[] {at, at + 3 * 60});
        }
        double morning = wake + (3 + r.nextInt(6)) * 60;
        day.add(new double[] {morning, morning + (4 + r.nextInt(7)) * 60});
        double from = wake + 20 * 60, to = Math.min(at(date, 21, zone), until);
        int n = 30 + r.nextInt(12);
        if (quiet) n /= 3;
        double[] starts = new double[n];
        for (int i = 0; i < n; i++) starts[i] = from + r.nextDouble() * (to - from);
        java.util.Arrays.sort(starts);
        for (int i = 0; i < n; i++) {
            double k = r.nextDouble();
            double len = k < 0.7 ? 30 + r.nextDouble() * 150 : k < 0.95 ? 180 + r.nextDouble() * 540 : 900 + r.nextDouble() * 1500;
            double end = Math.min(starts[i] + len, i + 1 < n ? starts[i + 1] - 30 : to);
            if (end > starts[i] + 10) day.add(new double[] {starts[i], end});
        }
        for (double[] x : day) if (x[1] < now - 60) out.add(x);
    }

    /**
     * Made up apps for one day's sessions: social or video before bed, messages at night, mail or
     * messages in the morning, a mix during the day, and every few days a long trip with the maps
     * app. A session sometimes switches app part way. Its own random numbers, so the sessions stay
     * the same.
     */
    static void appsFor(List<double[]> sessions, Random r, double bed, double wake, java.util.Map<String, List<double[]>> apps) {
        double[] longest = null;
        for (double[] s : sessions) if (s[0] > wake + 3600 && (longest == null || s[1] - s[0] > longest[1] - longest[0])) longest = s;
        boolean trip = r.nextDouble() < 0.4;
        for (double[] s : sessions) {
            String a;
            if (s[1] <= bed) a = r.nextDouble() < 0.6 ? "demo.social" : "demo.video";
            else if (s[0] < wake) a = "demo.messages";
            else if (s[0] < wake + 3600) a = r.nextDouble() < 0.5 ? "demo.mail" : "demo.messages";
            else if (trip && s == longest) a = MAPS;
            else a = pick(r);
            double split = r.nextDouble() < 0.3 && s[1] - s[0] > 120 ? s[0] + (0.4 + 0.4 * r.nextDouble()) * (s[1] - s[0]) : s[1];
            put(apps, a, s[0], split);
            if (split < s[1]) put(apps, pick(r), split, s[1]);
        }
    }

    static String pick(Random r) {
        double x = r.nextDouble();
        for (int i = 0; i < APPS.length; i++) {
            x -= SHARE[i];
            if (x < 0) return APPS[i];
        }
        return APPS[0];
    }

    static void put(java.util.Map<String, List<double[]>> m, String a, double from, double to) {
        List<double[]> l = m.get(a);
        if (l == null) m.put(a, l = new ArrayList<double[]>());
        l.add(new double[] {from, to});
    }

    /** Nights (day numbers of {@link #history}) with the phone put away in the evening before, and the last but one (issue #265). */
    static final int[] PHONE_FREE_NIGHTS = {10, 15, 17, 18};

    /** The easy last week of the story, and the calm night after the cold. */
    static boolean easy(int d, int days) {
        return d > days - 7 || d == days - COLD_BACK + 2;
    }

    static boolean phoneFree(int d, int days) {
        if (d == days - 2) return true;
        for (int x : PHONE_FREE_NIGHTS) if (x == d) return true;
        return false;
    }

    /** A quiet day with few phone uses: the days before the last of {@link #PHONE_FREE_NIGHTS} and before the last but one night. */
    static boolean quiet(int d, int days) {
        return d == PHONE_FREE_NIGHTS[PHONE_FREE_NIGHTS.length - 1] - 1 || d == days - 3;
    }

    static boolean inside(double[][] bouts, double t) {
        for (double[] b : bouts) if (t >= b[0] && t < b[1]) return true;
        return false;
    }
}
