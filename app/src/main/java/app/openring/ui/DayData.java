package app.openring.ui;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.openring.data.Db;
import app.openring.data.Json;

/** Everything one screen needs for a date: the day summary, its main sleep, naps and history. */
public final class DayData {
    public LocalDate date;
    public JSONObject day;
    public JSONObject night;
    public final List<JSONObject> naps = new ArrayList<JSONObject>();
    /** Day summaries for the 30 days ending at {@link #date}, keyed by ISO date. */
    public final Map<String, JSONObject> history = new HashMap<String, JSONObject>();
    /** Newest readings from the last sync (for the Body page). */
    public app.openring.data.RightNow now;
    /** Main nights from 30 days before the selected date to 30 days after it (at most up to tomorrow), oldest first (issue #98). */
    public final List<JSONObject> recent = new ArrayList<JSONObject>();
    /** True when any main night is stored, also outside {@link #recent} (Further analysis covers all time). */
    public boolean anyNight;
    /** Screen time of the selected date and the days before (Screen tab, Today ring); null if it could not be read. */
    public app.openring.data.ScreenDays.Info screen;
    /** The zone this day is shown in: its main night's, else the zone noted then, else the phone's (issue #22). */
    public java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    /** Sleep sounds (issue #380, phase 1b): the night shown on this day's Sleep tab, null for none; its clip folder. */
    public app.openring.data.Sounds.Night soundNight;
    public java.io.File soundFolder;
    /** False when the sleep sounds could not be read (the card says so). */
    public boolean soundsOk = true;
    /**
     * The times left out because someone else wore the ring (issue #254) that reach this day or the day before (its night),
     * merged, oldest first, each {start, stop}. Empty for nearly everyone.
     */
    public final List<double[]> leftOut = new ArrayList<double[]>();
    /** The period tracker shows (issue #252): the profile says female, the Settings switch, or demo mode. */
    public boolean periodShown;
    /** The period tracker's estimate from the whole log and the temperature rise (phase 2), today; null when hidden or when the log could not be read. */
    public app.openring.core.analysis.Cycles periods;
    /**
     * The badges of the 7 days ending at {@link #date} (issue #265), oldest first; empty when the switch "Show badges" is
     * off, when there are none, or when they could not be read.
     */
    public final List<app.openring.core.analysis.Badges.Badge> badges = new ArrayList<app.openring.core.analysis.Badges.Badge>();
    /** The newest badge date already seen (kv {@code badges_seen}); later ones are marked New. Null when none was seen. */
    public LocalDate badgesSeen;
    /** Life factors (issue #292): the known tags and chips, null when they could not be read. */
    public app.openring.core.LifeTags.Known life;
    /** Every day with a life factor in force, its tags by key. A few thousand rows even after years, read in the background. */
    public final java.util.TreeMap<LocalDate, List<String>> lifeDays = new java.util.TreeMap<LocalDate, List<String>>();
    /** False when the life factors could not be read (the cards say so; the screens' other numbers are unaffected). */
    public boolean lifeOk = true;
    /** The card asks "Have your periods stopped?" (phase 1a2): the profile age, the log and "Not yet" say so. */
    public boolean periodAsk;
    /** The first and last day of Body's temperature chart with the cycle on it ({@code Cycles.tempWindow}), null without the tracker. */
    public LocalDate periodFrom, periodTo;
    /**
     * The nights' skin temperature compared with the usual ({@code temp_dev} of each day summary) from {@link #periodFrom} to
     * today, for Body's temperature chart with the cycle on it. Left out nights have no summary value.
     */
    public final java.util.TreeMap<LocalDate, Double> periodTemps = new java.util.TreeMap<LocalDate, Double>();
    /** The morning temperatures by mouth imported from drip (phase 1b) over the same days, read only while the tracker shows. */
    public final java.util.TreeMap<LocalDate, app.openring.core.DripCsv.Temp> periodMouth = new java.util.TreeMap<LocalDate, app.openring.core.DripCsv.Temp>();
    /**
     * The day as shown with the main device (issue #251, {@code core/MainDevice}): steps, distance, the heart rate
     * line, the night when the ring has none, the watch's workouts. With only a ring it holds the ring's own numbers. Never
     * null after {@link #load}.
     */
    public app.openring.core.MainDevice.Day merged;
    /** False when a part above could not be read and kept its fallback (issue #235); {@link #failed} noted why. */
    public boolean ok = true;
    /**
     * Steps from this phone (issue #329, phase 1b): the phone has a step counter, Physical activity is allowed, and any
     * reading is stored in the file shown (the demo has its own). Read in the load's background task.
     */
    public boolean phoneSensor, phoneAllowed, phoneAny;
    /**
     * The scores from the watch, or from the phone without a ring (issue #379, {@code core/DeviceScores}): worked out on each
     * load from the stored rows, nothing stored. Null with only a ring, or when the rows could not be read. Without a ring
     * {@link #score} and {@link #scoreObj} show them; with a ring they are only the "Watch version" line.
     */
    public app.openring.core.DeviceScores.Result devices;
    /**
     * Without a ring, the watch's or the phone's days over time for the 7 and 30 day charts, sleep debt and the recent nights
     * (owner's report of 13 November, {@code core/DeviceHistory}): from 30 days before the date to 30 days after it (at most
     * today). Null with a ring (its own charts show), without a device, or when the rows could not be read.
     */
    public app.openring.core.DeviceHistory deviceHistory;
    /**
     * Which watch nights tell REM from light sleep (owner's report of 13 November; per night since the code review of #389,
     * {@code WatchStore.rem}): the others show "Light and REM" as one stage and their score leaves REM out. Ring nights never change.
     */
    public app.openring.core.withings.WatchDays.Rem watchRem = app.openring.core.withings.WatchDays.Rem.ALWAYS;
    /** The watch's nights of {@link #deviceHistory} around the date as the ring's night objects, for the recent nights strip. */
    public final List<JSONObject> watchRecent = new ArrayList<JSONObject>();
    /** The devices the tabs are built for (issue #373, {@code core/Start}); never null after {@link #load}. */
    public app.openring.core.Start.Has has = new app.openring.core.Start.Has(true, false, false);
    /**
     * The battery of each added device, ring first (Today, right under the scores; owner's wish of 15 November). Empty with only
     * the phone or when the readings cannot be read.
     */
    public final List<app.openring.core.DeviceBattery.Status> batteries = new ArrayList<app.openring.core.DeviceBattery.Status>();
    /**
     * The main device and the devices whose results show (owner's report of 14 November): the one source of the nights of the
     * Screen tab and the badges ({@code data/ShownNights}), the main device of {@link #merged}. Never null after {@link #load}.
     */
    public app.openring.data.ShownNights.Devices shown = app.openring.data.ShownNights.Devices.RING;
    /** The phone is a source of steps (it counts or has any stored); worked out with {@link #shown}. */
    boolean phoneThere;
    /**
     * Screen test, demo mode only (extra {@code devices}, issue #373): show the demo as for a person with only the watch
     * ({@code Start.PREVIEW_WATCH}) or only the phone. Nothing stored changes; the ring's (and the watch's) results of the
     * demo file are left out of what is shown.
     */
    static volatile int preview = app.openring.core.Start.PREVIEW_NONE;

    /** The night entered by hand on this date's bar (issue #427), shown or replaced; null for none. */
    public app.openring.data.HandNights.Row hand;
    /** {@link #hand} is the night shown ({@link #night} is its night JSON): neither device has a night for the day. */
    public boolean handShown;
    /** The device whose night replaced {@link #hand} ({@code MainDevice.RING} or {@code WATCH}), -1 when shown or none. */
    public int handReplacedBy = -1;
    /** Today asks for last night by hand ({@code core/HandNight.ask}). */
    public boolean handAsk;
    /** False when the nights by hand could not be read (the Sleep tab offers nothing then). */
    public boolean handOk = true;
    /**
     * Screen test (extra {@code hand_night}, demo mode only, issue #427): today and the day before yesterday show as without a
     * device night, so Today asks for last night and the demo's night by hand shows on its day. Nothing stored changes.
     */
    static volatile boolean previewNoNight;

    /**
     * Works out the night by hand of this date (issue #427): shown when neither device has a night for the day nor one that
     * overlaps it ({@code MainDevice.nightFrom}), then {@link #night} is its night JSON and, with a ring, readiness from its sleep
     * part; otherwise only noted as replaced. Adds the nights by hand around the date to the recent nights strip, and decides
     * Today's question. Reads only.
     */
    void hands(Context c, Db db) {
        // only the phone: no night at all (sleep needs a ring or a watch), as on every other route
        if (!has.ring && !has.watch) return;
        app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
        LocalDate today = LocalDate.now();
        boolean demo = p.demo();
        String back = today.minusDays(app.openring.core.HandNight.DEMO_BACK).toString();
        if (demo && previewNoNight) {
            if (date.equals(today) || date.toString().equals(back)) {
                night = null;
                if (merged != null) {
                    merged.watchNight = null;
                    merged.watchNightHeart = null;
                    merged.ringNight = false;
                }
            }
            for (int i = recent.size() - 1; i >= 0; i--) {
                String dd = recent.get(i).optString("day");
                if (dd.equals(today.toString()) || dd.equals(back)) recent.remove(i);
            }
        }
        boolean watchNight = merged != null && merged.watchNight != null;
        // the device nights around the date, each {start, end}: a night by hand they overlap is replaced
        List<double[]> ringSpans = new ArrayList<double[]>(), watchSpans = new ArrayList<double[]>();
        java.util.Set<String> deviceDays = new java.util.HashSet<String>();
        for (JSONObject o : recent) {
            ringSpans.add(new double[] {Json.d(o, "start"), Json.d(o, "end")});
            deviceDays.add(o.optString("day"));
        }
        for (JSONObject o : watchRecent) {
            watchSpans.add(new double[] {Json.d(o, "start"), Json.d(o, "end")});
            deviceDays.add(o.optString("day"));
        }
        if (night != null) ringSpans.add(new double[] {Json.d(night, "start"), Json.d(night, "end")});
        if (watchNight) watchSpans.add(new double[] {merged.watchNight.start, merged.watchNight.end});
        double age = p.age();
        hand = app.openring.data.HandNights.forDay(db, date, zone);
        if (hand != null) {
            int from = app.openring.core.MainDevice.nightFrom(shown.main, night != null, watchNight, true);
            if (from == app.openring.core.MainDevice.HAND) {
                if (app.openring.core.HandNight.overlaps(hand.start, hand.stop, ringSpans)) from = app.openring.core.MainDevice.RING;
                else if (app.openring.core.HandNight.overlaps(hand.start, hand.stop, watchSpans)) from = app.openring.core.MainDevice.WATCH;
            }
            if (from == app.openring.core.MainDevice.HAND) {
                night = app.openring.data.HandNights.nightJson(hand, date, zone, age);
                handShown = true;
                anyNight = true;
            } else {
                handReplacedBy = from;
            }
        }
        // the recent nights strip: the nights by hand of the days around the date that have no device night
        LocalDate last = date.plusDays(Db.RECENT_DAYS);
        if (last.isAfter(today)) last = today.isBefore(date) ? date : today;
        app.openring.core.DeviceHistory.DayZones zones = app.openring.data.ShownNights.with(
                app.openring.data.WatchHistory.dayZones(db, java.time.ZoneId.systemDefault()), date, zone);
        boolean added = false;
        for (Map.Entry<LocalDate, JSONObject> e : app.openring.data.HandNights.days(db, date.minusDays(Db.RECENT_DAYS), last, zones, age).entrySet()) {
            JSONObject o = e.getKey().equals(date) ? (handShown ? night : null) : e.getValue();
            if (o == null || deviceDays.contains(e.getKey().toString())) continue;
            double s = Json.d(o, "start"), en = Json.d(o, "end");
            if (app.openring.core.HandNight.overlaps(s, en, ringSpans) || app.openring.core.HandNight.overlaps(s, en, watchSpans)) continue;
            recent.add(o);
            if (!has.ring) watchRecent.add(o);
            added = true;
        }
        if (added) {
            byStart(recent);
            byStart(watchRecent);
        }
        if (handShown && has.ring && (day == null || !day.has("readiness"))) handReadiness();
        long now = System.currentTimeMillis() / 1000;
        long synced = demo ? now : Math.max(p.lastSync(), p.watchLastOk());
        handAsk = app.openring.core.HandNight.ask(date, today, night != null || watchNight, has.ring || has.watch, synced, now, zone, p.handNotNow());
    }

    private static void byStart(List<JSONObject> l) {
        java.util.Collections.sort(l, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(Json.d(a, "start"), Json.d(b, "start"));
            }
        });
    }

    /**
     * Readiness of a day whose night is by hand (issue #427), with a ring and no stored readiness: its sleep score and sleep
     * balance and the activity parts from the stored days before; heart rate, HRV and temperature left out, as for a day without
     * the ring (#341). Shown only, never stored: {@link #day} becomes a copy with it. Sleep balance and the sleep need read only the
     * nights of the recent strip (30 days around the date, not the 104 the analysis reads), so this readiness can differ slightly
     * from one the analysis would store.
     */
    void handReadiness() {
        JSONObject sc = night.optJSONObject("score");
        double sleepScore = sc == null || !sc.has("value") ? Double.NaN : sc.optDouble("value");
        List<JSONObject> prev = new ArrayList<JSONObject>();
        for (int i = 28; i >= 1; i--) {
            JSONObject o = history.get(date.minusDays(i).toString());
            if (o != null) prev.add(o);
        }
        int np = prev.size();
        double[] active = new double[np], target = new double[np], covered = new double[np];
        for (int i = 0; i < np; i++) {
            active[i] = Json.d(prev.get(i), "active_kcal");
            target[i] = Json.d(prev.get(i), "target_kcal");
            covered[i] = prev.get(i).optDouble("covered_min", 0);
        }
        boolean lastIsYesterday = history.get(date.minusDays(1).toString()) != null;
        Map<String, Double> asleep = new HashMap<String, Double>();
        for (JSONObject o : recent) {
            String dd = o.optString("day");
            Double v = asleep.get(dd);
            asleep.put(dd, (v == null ? 0 : v) + o.optDouble("asleep_min", 0) * 60);
        }
        if (!recent.contains(night)) asleep.put(date.toString(), Json.d(night, "asleep_min") * 60);
        double[] a14 = new double[14], prior = new double[90];
        for (int i = 0; i < 14; i++) {
            Double v = asleep.get(date.minusDays(i).toString());
            a14[i] = v == null ? Double.NaN : v;
        }
        for (int k = 0; k < 90; k++) {
            Double v = asleep.get(date.minusDays(k + 1).toString());
            prior[k] = v == null ? Double.NaN : v;
        }
        app.openring.core.HandNight.Ready r = app.openring.core.HandNight.readiness(sleepScore, active, target, covered, lastIsYesterday, a14,
                app.openring.core.HandNight.need(prior));
        JSONObject copy = day == null ? new JSONObject() : Json.parse(day.toString());
        if (copy == null) return;
        JSONObject rs = Json.score(r.score);
        Json.put(rs, "hand", true);
        Json.put(copy, "readiness", rs);
        if (r.prevUnworn && np > 0) Json.put(copy, "prev_day_unworn", true);
        day = copy;
        history.put(date.toString(), copy);
    }

    /**
     * Notes a part of loading {@code date} that failed and kept its fallback (issue #235): a warning in Android's log
     * with the stack trace, a line with the full error in the app's Log tab, and for the data check
     * {@link app.openring.data.DataCheck#LOAD_ERROR}, a {@link app.openring.core.LoadNote} line without the error's
     * message (it can carry a value), so the data check stays safe to share. Written only when date, part or error
     * differ from the stored line. Runs in the load's background task; while an analysis runs its transaction would
     * hold the write for minutes, so then only the logs get it. Never throws; returns false for {@link #ok}.
     */
    static boolean failed(Context c, LocalDate date, String part, Throwable e) {
        try {
            android.util.Log.w("OpenRing", "day load: " + part + " of " + date + " failed", e);
            app.openring.ble.RingManager.get(c).log("day load: " + part + " of " + date + " failed: " + e);
            String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date());
            String line = app.openring.core.LoadNote.line(String.valueOf(date), part, e, when);
            if (!app.openring.data.Analyzer.running()) {
                Db db = Db.get(c);
                if (app.openring.core.LoadNote.differs(db.kv(app.openring.data.DataCheck.LOAD_ERROR, null), line)) {
                    db.put(app.openring.data.DataCheck.LOAD_ERROR, line);
                }
            }
        } catch (Throwable ignored) {
            // the fallback stays; nothing more can be done here
        }
        return false;
    }

    /**
     * After a load of {@code date} where every part worked: clears {@link app.openring.data.DataCheck#LOAD_ERROR} when
     * it is about that date, so looking at another day keeps it for the data check. Writes only then. Never throws.
     */
    static void allRead(Context c, LocalDate date) {
        try {
            Db db = Db.get(c);
            if (!app.openring.data.Analyzer.running()
                    && app.openring.core.LoadNote.clearedBy(db.kv(app.openring.data.DataCheck.LOAD_ERROR, null), String.valueOf(date))) {
                db.put(app.openring.data.DataCheck.LOAD_ERROR, null);
            }
        } catch (Throwable ignored) {
            // kept for the next good load
        }
    }

    public static DayData load(Context c, LocalDate date) {
        Db db = Db.get(c);
        DayData d = new DayData();
        d.date = date;
        String key = date.toString();
        d.day = Json.parse(db.dayJson(key));
        try {
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            d.has = app.openring.core.Start.of(p.demo(), app.openring.core.KeyState.hasRing(p.ringState()), p.watchAddress() != null,
                    p.startChoice(), p.demo() ? preview : app.openring.core.Start.PREVIEW_NONE);
        } catch (RuntimeException e) {
            d.ok = failed(c, date, "devices", e);
        }
        // the demo shown as without a ring (screen test, issue #373): its ring results are not shown
        boolean hideRing = !d.has.ring && app.openring.data.Prefs.of(c).demo();
        d.devices(c, db, hideRing);
        if (hideRing) d.day = null;
        for (String s : db.nightsJson(key, key, false)) {
            JSONObject o = Json.parse(s);
            if (o == null) continue;
            if (hideRing) continue;
            if (o.optBoolean("main")) d.night = o;
            else d.naps.add(o);
        }
        java.time.ZoneId phone = java.time.ZoneId.systemDefault();
        d.zone = Json.dayZone(d.night, db.zoneAt(Json.noon(date, phone)), phone);
        try {
            double ds = d.dayStart(), de = d.dayEnd();
            for (double[] r : db.leftOut().ranges()) if (r[1] > ds - 2 * 86400 && r[0] < de + 86400) d.leftOut.add(r);
        } catch (RuntimeException e) {
            // shown without the left out cards, the results never hold left out data anyway
            d.ok = failed(c, date, "left out times", e);
        }
        // a window around the date, so an old date loads as fast as a recent one (issue #98)
        for (String s : db.recentNights(date, LocalDate.now())) {
            JSONObject o = Json.parse(s);
            if (o != null && !hideRing) d.recent.add(o);
        }
        d.anyNight = !hideRing && (!d.recent.isEmpty() || db.anyMainNight());
        try {
            if (!hideRing) d.now = app.openring.data.RightNow.load(c);
        } catch (RuntimeException e) {
            // the Body card shows only its caption and the Today ring no verdict, as before (issue #235)
            d.ok = failed(c, date, "latest readings", e);
        }
        try {
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            // the nights of the main device, filled by the other (owner's report of 14 November): the watch's without a ring
            d.screen = app.openring.data.ScreenDays.info(db, date, d.zone, p.screenGoalMin(), p.screenExcluded(),
                    p.screenMinShowMin() * 60.0, d.shown);
        } catch (RuntimeException e) {
            // the Screen tab says it could not be read (issue #235)
            d.ok = failed(c, date, "screen time", e);
        }
        try {
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            d.periodShown = app.openring.core.analysis.Cycles.shown(p.periodChoice(), p.sex(), p.demo());
            if (d.periodShown) {
                LocalDate today = LocalDate.now();
                // the prediction method of Settings (issue #383); measured below keeps it
                d.periods = app.openring.data.Periods.cycles(db, today, app.openring.core.analysis.Cycles.day(p.periodStopped())).withMethod(p.periodMethod());
                d.periodAsk = d.periods.askStopped(p.age(), app.openring.core.analysis.Cycles.day(p.periodAskLater()));
                try {
                    // phase 2: the temperature rise of each cycle, from the ring's nights and the mouth temperatures
                    d.periods = app.openring.data.Periods.measured(db, d.periods, java.time.ZoneId.systemDefault());
                } catch (RuntimeException e) {
                    // the calendar estimate stays; the error's kind only, never a date or a value
                    app.openring.ble.RingManager.get(c).log("day load: the temperature rise for the period tracker: " + e.getClass().getSimpleName());
                }
                // "How close the estimates were" (issue #383): both backtests worked out here in the background, not while the
                // card is drawn (each is kept in the estimate once done)
                d.periods.backtest();
                d.periods.riseBacktest();
                // the day summaries hold no period data; read here for Body's temperature chart with the cycle on it, over
                // its whole time span up to today (owner's feedback of 11 November)
                LocalDate[] span = d.periods.tempWindow(date);
                d.periodFrom = span[0];
                d.periodTo = span[1];
                String last = (span[1].isAfter(today) ? today : span[1]).toString();
                try {
                    for (String[] r : db.daysJson(span[0].toString(), last)) {
                        double v = Json.d(Json.parse(r[1]), "temp_dev");
                        if (!Double.isNaN(v)) d.periodTemps.put(LocalDate.parse(r[0]), v);
                    }
                } catch (RuntimeException e) {
                    // the line says it has no nights; the cycles still show
                    d.periodTemps.clear();
                    app.openring.ble.RingManager.get(c).log("day load: night temperatures for the period tracker: " + e.getClass().getSimpleName());
                }
                try {
                    d.periodMouth.putAll(app.openring.data.Periods.temps(db, span[0].toString(), last));
                } catch (RuntimeException e) {
                    // the line shows the ring's nights only; the error's kind only, never a date or a value
                    d.periodMouth.clear();
                    app.openring.ble.RingManager.get(c).log("day load: drip temperatures for the period tracker: " + e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            // the card says it could not be read. Not through failed(): the Data check names nothing of the period log, and
            // the log gets the error's kind only, never a date or an amount (issue #252)
            app.openring.ble.RingManager.get(c).log("day load: the period log could not be read: " + e.getClass().getSimpleName());
        }
        try {
            // life factors (issue #292): the person's own tags, the same in every file; demo mode reads the demo's
            d.life = app.openring.data.LifeLog.known(db);
            d.lifeDays.putAll(app.openring.data.LifeLog.all(db));
        } catch (RuntimeException e) {
            // the cards say they could not be read. Not through failed(): the Data check names nothing of the tags, and the log
            // gets the error's kind only, never a tag, a name or a date
            d.life = null;
            d.lifeDays.clear();
            d.lifeOk = false;
            app.openring.ble.RingManager.get(c).log("day load: the life factors could not be read: " + e.getClass().getSimpleName());
        }
        try {
            // the Recent badges row on Today (issue #265): one read of the 7 days and the weeks before them
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            if (p.badgesOn()) {
                d.badges.addAll(app.openring.data.BadgeDays.badges(db, date.minusDays(app.openring.core.analysis.Badges.WEEK - 1), date,
                        p.targetKcal(), p.screenExcluded(), LocalDate.now(), System.currentTimeMillis() / 1000.0, d.shown));
                d.badgesSeen = BadgeViews.seen(db);
            }
        } catch (RuntimeException e) {
            // Today shows no badge row; the badges hold nothing that is not on the other tabs
            d.badges.clear();
            d.ok = failed(c, date, "badges", e);
        }
        for (String[] r : db.daysJson(date.minusDays(29).toString(), key)) {
            JSONObject o = Json.parse(r[1]);
            if (o != null && !hideRing) d.history.put(r[0], o);
        }
        d.merge(c, db);
        try {
            d.hands(c, db);
        } catch (RuntimeException e) {
            // the day shows as without a night by hand; the stored rows are not touched
            if (app.openring.data.HandNights.isHand(d.night)) d.night = null;
            d.hand = null;
            d.handShown = false;
            d.handReplacedBy = -1;
            d.handOk = false;
            d.ok = failed(c, date, "nights by hand", e);
        }
        try {
            // sleep sounds (issue #380): the night of this day in the file shown (the demo has its own), and its clip folder
            d.soundNight = app.openring.data.Sounds.forDay(db, date, d.zone);
            d.soundFolder = app.openring.data.Sounds.folder(c, db);
        } catch (RuntimeException e) {
            // the card says it could not be read; the log gets the error's kind only, never a time or a kind of sound
            d.soundNight = null;
            d.soundsOk = false;
            app.openring.ble.RingManager.get(c).log("day load: sleep sounds could not be read: " + e.getClass().getSimpleName());
        }
        try {
            d.batteries(c, db, System.currentTimeMillis() / 1000);
        } catch (RuntimeException e) {
            // Today shows no battery rows; the readings stay where they are (Settings, the Watch page)
            d.batteries.clear();
            d.ok = failed(c, date, "battery", e);
        }
        return d;
    }

    /**
     * Works out {@link #batteries} from the stored readings (nothing is written): the ring's {@code battery} table with Settings'
     * newer read of the ring when there is one (not in demo mode, whose ring is made up), and the watch's {@code watch_battery}.
     * A ring only when one is paired (or the demo's), a watch only when one is added (or the demo's).
     */
    void batteries(Context c, Db db, long now) {
        app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
        long span = app.openring.core.DeviceBattery.WINDOW_S + app.openring.core.DeviceBattery.OLD_S;
        if (has.ring && !app.openring.core.KeyState.addRingInstead(p.demo(), p.ringState())) {
            List<app.openring.core.DeviceBattery.Reading> rows = db.batteryRows(span);
            long newest = rows.isEmpty() ? 0 : rows.get(rows.size() - 1).t;
            // Settings reads the ring's battery without storing it: that read when it is newer
            if (!p.demo() && p.battery() >= 0 && p.batteryAt() > newest) rows.add(new app.openring.core.DeviceBattery.Reading(p.batteryAt(), p.battery(), -1));
            batteries.add(app.openring.core.DeviceBattery.of(app.openring.core.DeviceBattery.RING, rows, now));
        }
        if (has.watch) {
            batteries.add(app.openring.core.DeviceBattery.of(app.openring.core.DeviceBattery.WATCH, app.openring.data.WatchStore.batteryRows(db, span), now));
        }
    }

    /**
     * Works out {@link #merged}: the ring's stored results of this day, and the watch's rows around it when any watch
     * message is stored. Reads only. When the watch's rows cannot be read the ring's numbers are shown alone.
     */
    void merge(Context c, Db db) {
        double ds = dayStart(), de = dayEnd();
        List<JSONObject> nights = new ArrayList<JSONObject>(recent);
        nights.addAll(naps);
        if (night != null) nights.add(night);
        app.openring.core.MainDevice.Ring ring = app.openring.data.WatchStore.ringDay(day, night, nights, ds, de);
        int main = shown.main;
        app.openring.core.MainDevice.Watch watch = null;
        app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
        try {
            // a day earlier for the night that ends this morning
            // the demo shown as for only the phone (screen test, issue #373) leaves the watch's rows out too
            if (shown.watch) {
                watch = app.openring.data.WatchStore.window(db, ds - 86400, de);
                watchRem = app.openring.data.WatchStore.rem(db);
            }
        } catch (RuntimeException e) {
            watch = null;
            ok = failed(c, date, "watch data", e);
        }
        // the phone (issue #329, phase 1b): a source while it counts steps or has any stored; null leaves only ring and watch
        app.openring.core.MainDevice.Phone phone = null;
        try {
            if (phoneAny) phone = app.openring.data.PhoneStore.forDay(db, ds, de);
            else if (phoneThere) phone = new app.openring.core.MainDevice.Phone();
        } catch (RuntimeException e) {
            phone = null;
            ok = failed(c, date, "phone steps", e);
            // as before #251's routes: without the phone's rows the main device is worked out without the phone
            if (phoneThere) main = mainDevice(c, false);
        }
        merged = app.openring.core.MainDevice.of(main, date, ds, de, zone, ring, watch, phone);
        try {
            // the scores without a ring (issue #379): the watch's or the phone's rows of the last five weeks
            int dev = app.openring.core.DeviceScores.device(has.ring, has.watch, phone != null);
            if (dev >= 0) {
                double from = ds - (app.openring.core.DeviceScores.DAYS - 1) * 86400.0 - 3600;
                app.openring.core.MainDevice.Watch w = dev == app.openring.core.MainDevice.WATCH && app.openring.data.WatchStore.any(db)
                        ? app.openring.data.WatchStore.window(db, from - 86400, de) : null;
                app.openring.core.MainDevice.Phone ph = dev == app.openring.core.MainDevice.PHONE && phoneAny
                        ? app.openring.data.PhoneStore.window(db, from, de) : null;
                devices = app.openring.core.DeviceScores.of(dev, date, zone, w, ph, p.watchGoal(), p.age(), watchRem);
            }
        } catch (RuntimeException e) {
            // the tabs show the scores as without data; the ring's own are not touched
            devices = null;
            ok = failed(c, date, "scores without a ring", e);
        }
        try {
            // the statistics without a ring (owner's report of 13 November); with a ring its own charts show
            int dev = app.openring.core.DeviceHistory.device(has.ring, has.watch, phone != null);
            if (dev >= 0) {
                // from the strip's oldest night (30 before) to 30 after, at most today; each day in its own zone as its dial,
                // the shown day in the zone its dial has (code review of #389)
                LocalDate[] range = app.openring.core.DeviceHistory.range(date, LocalDate.now());
                LocalDate first = range[0], last = range[1];
                final app.openring.core.DeviceHistory.DayZones noted = app.openring.data.WatchHistory.dayZones(db, java.time.ZoneId.systemDefault());
                app.openring.core.DeviceHistory.DayZones zones = app.openring.data.ShownNights.with(noted, date, zone);
                double from = app.openring.core.DeviceHistory.rowsFrom(first, last, zones);
                double to = last.plusDays(1).atStartOfDay(zones.of(last)).toEpochSecond();
                app.openring.core.MainDevice.Watch w = null;
                if (dev == app.openring.core.MainDevice.WATCH && app.openring.data.WatchStore.any(db)) {
                    w = app.openring.data.WatchStore.window(db, from, to);
                    // sleep debt reads 90 nights before each of its 14 days (the ring's rule): only the sleep pieces reach that far
                    w.sleep.clear();
                    w.sleep.addAll(app.openring.data.WatchStore.sleepRows(db, app.openring.core.DeviceHistory.sleepFrom(first, zones), to));
                }
                // the phone's window as the scores read it: from 35 days before the first day (a day less than the watch's)
                app.openring.core.MainDevice.Phone ph = dev == app.openring.core.MainDevice.PHONE && phoneAny
                        ? app.openring.data.PhoneStore.window(db, from + 86400, to) : null;
                deviceHistory = app.openring.core.DeviceHistory.of(dev, first, last, zones, w, ph, p.watchGoal(), p.age(), watchRem);
                if (dev == app.openring.core.MainDevice.WATCH) watchRecent.addAll(app.openring.data.WatchHistory.json(deviceHistory.strip(date)));
            }
        } catch (RuntimeException e) {
            // the tabs show today's numbers without the charts below them
            deviceHistory = null;
            watchRecent.clear();
            ok = failed(c, date, "history without a ring", e);
        }
    }

    /**
     * Works out {@link #shown} (and the phone's flags): the main device as Settings picks it among the devices there, whether the
     * ring's results and the watch's rows show, and the device whose days stand in without a ring. Reads only; a part that cannot
     * be read keeps its fallback (the ring, no phone) and is noted ({@link #failed}).
     */
    void devices(Context c, Db db, boolean hideRing) {
        app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
        try {
            phoneSensor = app.openring.sync.PhoneReader.hasSensor(c);
            phoneAllowed = app.openring.sync.PhoneReader.allowed(c);
            phoneAny = app.openring.data.PhoneStore.any(db);
            phoneThere = phoneAny || p.demo() || phoneSensor && phoneAllowed;
        } catch (RuntimeException e) {
            phoneThere = false;
            ok = failed(c, date, "phone steps", e);
        }
        int main = mainDevice(c, phoneThere);
        boolean watchRows = false;
        try {
            // the demo shown as for only the phone (screen test, issue #373) leaves the watch's rows out
            watchRows = app.openring.data.WatchStore.any(db) && (has.watch || !p.demo());
        } catch (RuntimeException e) {
            ok = failed(c, date, "watch data", e);
        }
        shown = new app.openring.data.ShownNights.Devices(main, !hideRing, watchRows,
                app.openring.core.DeviceHistory.device(has.ring, has.watch, phoneThere), p.watchGoal(), p.age());
    }

    /**
     * {@link #shown} as {@link #load} works it out, without loading a day (the Badges page, owner's report of 14 November); only the
     * ring's results when the devices cannot be read.
     */
    static app.openring.data.ShownNights.Devices shownNow(Context c) {
        try {
            DayData d = new DayData();
            d.date = LocalDate.now();
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            d.has = app.openring.core.Start.of(p.demo(), app.openring.core.KeyState.hasRing(p.ringState()), p.watchAddress() != null,
                    p.startChoice(), p.demo() ? preview : app.openring.core.Start.PREVIEW_NONE);
            d.devices(c, Db.get(c), !d.has.ring && p.demo());
            return d.shown;
        } catch (RuntimeException e) {
            return app.openring.data.ShownNights.Devices.RING;
        }
    }

    /** The main device shown among the devices there ({@code phone}: the phone is a source of steps). */
    int mainDevice(Context c, boolean phone) {
        try {
            app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
            // without a ring (issue #373) a stored ring choice falls back to the device that is there
            int stored = p.mainDeviceStored();
            if (!has.ring && stored == app.openring.core.MainDevice.RING) stored = -1;
            return app.openring.core.MainDevice.shown(stored, has.ring, has.watch, phone);
        } catch (RuntimeException e) {
            ok = failed(c, date, "main device", e);
            return app.openring.core.MainDevice.RING;
        }
    }

    /** The score {@code key} comes from the watch or the phone: no ring, and the device has one (issue #379). */
    public boolean fromDevice(String key) {
        return !has.ring && devices != null && !Double.isNaN(devices.value(key));
    }

    /** With a ring, the line "Watch version: 78" under the ring's score {@code key} when the watch has one too; else null. */
    public String watchVersion(String key) {
        if (!has.ring || devices == null || devices.device != app.openring.core.MainDevice.WATCH) return null;
        double v = devices.value(key);
        return Double.isNaN(v) ? null : app.openring.core.DeviceScores.version(v);
    }

    /** Values of a day field for the {@code n} days ending at the selected date (NaN if missing). */
    public double[] series(String field, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = history.get(date.minusDays(n - 1 - i).toString());
            out[i] = Json.d(o, field);
        }
        return out;
    }

    /** Score value from a nested score object of a day summary, or NaN. */
    public double[] scoreSeries(String field, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = history.get(date.minusDays(n - 1 - i).toString());
            JSONObject s = o == null ? null : o.optJSONObject(field);
            out[i] = s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
        }
        return out;
    }

    public String[] weekdayLabels(int n) {
        String[] l = new String[n];
        for (int i = 0; i < n; i++) {
            String s = date.minusDays(n - 1 - i).getDayOfWeek().getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault());
            l[i] = s;
        }
        return l;
    }

    public double score(String key) {
        if ("screen".equals(key)) return screen == null || screen.score == null ? Double.NaN : screen.score.value;
        // without a ring the watch's or the phone's (issue #379); a ring's stored score is never replaced
        if (fromDevice(key)) return devices.value(key);
        if ("sleep".equals(key)) {
            JSONObject s = night == null ? null : night.optJSONObject("score");
            return s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
        }
        JSONObject s = day == null ? null : day.optJSONObject(key);
        return s == null || !s.has("value") ? Double.NaN : s.optDouble("value");
    }

    public JSONObject scoreObj(String key) {
        if (fromDevice(key)) {
            JSONObject o = Json.score("sleep".equals(key) ? devices.sleep : "readiness".equals(key) ? devices.readiness : devices.activity);
            Json.put(o, "device", devices.device);
            return o;
        }
        if ("sleep".equals(key)) return night == null ? null : night.optJSONObject("score");
        return day == null ? null : day.optJSONObject(key);
    }

    /** The zone a night is shown in: its own, else this day's (issue #22). */
    public java.time.ZoneId zoneOf(JSONObject night) {
        return Json.zone(night, zone);
    }

    /** The orange line after a trip: the main night's zone differs from the main night before it (issue #22). */
    public String travelLine() {
        return app.openring.data.SleepHistory.travelLine(night, recent);
    }

    /** Midnight starting this day in its zone. */
    public double dayStart() {
        return date.atStartOfDay(zone).toEpochSecond();
    }

    /** Midnight ending this day in its zone (23 or 25 hours after the start on a clock change). */
    public double dayEnd() {
        return date.plusDays(1).atStartOfDay(zone).toEpochSecond();
    }

    /** The left out times that overlap this day itself, whole (issue #254). */
    public List<double[]> leftOutToday() {
        List<double[]> out = new ArrayList<double[]>();
        double ds = dayStart(), de = dayEnd();
        for (double[] r : leftOut) if (r[1] > ds && r[0] < de) out.add(r);
        return out;
    }

    /** The left out parts of this day, cut at its midnights (issue #254). */
    public List<double[]> leftOutPieces() {
        return app.openring.core.analysis.LeftOut.of(leftOut).pieces(dayStart(), dayEnd());
    }

    /** Seconds of this day that are left out. */
    public double leftOutSeconds() {
        return app.openring.core.analysis.LeftOut.of(leftOut).seconds(dayStart(), dayEnd());
    }

    /** The whole day is left out: its screens show the Left out card instead of "No data for this day yet". */
    public boolean allLeftOut() {
        return !leftOut.isEmpty() && leftOutSeconds() >= dayEnd() - dayStart();
    }

    public boolean empty() {
        return day == null && night == null;
    }

    /** Nothing to show from either device (the ring's summary and night missing, the watch with nothing either). */
    public boolean emptyAll() {
        return empty() && (merged == null || !merged.any());
    }
}
