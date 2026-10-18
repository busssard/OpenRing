package app.openring.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.openring.core.EventCodec;
import app.openring.core.analysis.ClockChange;
import app.openring.core.analysis.Night;
import app.openring.core.analysis.NightAnalyzer;
import app.openring.core.analysis.NightFinder;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.ScreenTime;
import app.openring.core.analysis.StageFilter;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.TS;
import app.openring.core.analysis.Wellness;

/**
 * Runs the on phone analysis after every sync: finds the sleeps, stages and measures them, builds
 * each day's activity, then scores Sleep, Readiness and Activity against your own accumulated
 * baselines. Results are cached in the nights and days tables.
 */
public final class Analyzer {
    public interface Log {
        void log(String msg);
    }

    private final Db db;
    private final Prefs prefs;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Log log;

    private Analyzer(Context c, Log log) {
        db = Db.get(c);
        prefs = Prefs.of(c);
        this.log = log == null ? new Log() {
            @Override
            public void log(String m) {}
        } : log;
    }

    /** Incremental: re analyse from a little before the last analysed point up to now. */
    public static synchronized void run(Context c, Log log) {
        Analyzer a = new Analyzer(c, log);
        try {
            a.runInner();
            a.db.put("analysis_error", null);
        } catch (RuntimeException e) {
            a.db.put("analysis_error", e.toString());
            throw e;
        }
    }

    void runInner() {
        long now = System.currentTimeMillis() / 1000;
        Db.TimeReport t = db.rebuildTime(now);
        log.log(String.format(java.util.Locale.US, "time: %d of %d events dated, %d boot segments (%d with a time anchor), %d written",
                t.datedAfter, t.total, t.segments, t.anchoredSegments, t.changed));
        long lastDated = db.kvLong("dated_count", -1), lastTotal = db.kvLong("event_count", -1);
        // older events gained (or lost) a date: their days must be analysed again
        if (lastDated < 0 || t.datedAfter > lastDated + (t.total - lastTotal) || t.datedAfter < lastDated) db.put("analyzed_until", 0);
        // the nights were staged with another detail level (changed in Settings, maybe while in demo
        // mode, or analysed by a version before 1.11): analyse everything again
        int detail = prefs.stageDetail();
        if (db.kvLong("stage_detail", -1) != detail) db.put("analyzed_until", 0);
        // the detail filter's rules changed (issue #28): nights from older rules are analysed again once;
        // databases from before the version existed read as version 1
        if (db.kvLong("stage_filter", 1) != StageFilter.VERSION) db.put("analyzed_until", 0);
        // "count phone use as awake" switched (issue #13): only nights since the first screen data change
        long screenAwake = prefs.screenAwake() ? 1 : 0;
        double firstScreen = db.screenStart(true);
        if (db.kvLong("screen_awake", screenAwake) != screenAwake && firstScreen >= 0) db.reanalyseFrom(firstScreen);
        db.put("dated_count", t.datedAfter);
        db.put("event_count", t.total);
        double[] range = db.datedRange();
        if (range == null) {
            log.log("no dated events yet, nothing to analyse");
            return;
        }
        double until = db.kvLong("analyzed_until", 0);
        // no age limit (issue #20): a full re analysis must rebuild every night resetAnalysis deleted;
        // the week by week chunks keep memory flat however long the history is
        double from = until > 0 ? Math.max(range[0], until - 2 * 86400) : range[0];
        analyze(from, range[1]);
        db.put("analyzed_until", (long) range[1]);
        db.put("stage_detail", detail);
        db.put("stage_filter", StageFilter.VERSION);
        db.put("screen_awake", screenAwake);
        db.put("last_analysis", now);
    }

    /** Rebuild everything from the raw events. */
    public static synchronized void runAll(Context c, Log log) {
        Analyzer a = new Analyzer(c, log);
        try {
            a.db.resetAnalysis();
        } catch (RuntimeException e) {
            a.db.put("analysis_error", e.toString());
            throw e;
        }
        run(c, log);
    }

    String date(double unix) {
        return Instant.ofEpochSecond((long) unix).atZone(zone).toLocalDate().toString();
    }

    String clock(double unix) {
        return Instant.ofEpochSecond((long) unix).atZone(zone).toLocalTime().withNano(0).toString();
    }

    int tzOffset(double unix) {
        return zone.getRules().getOffset(Instant.ofEpochSecond((long) unix)).getTotalSeconds();
    }

    double dayStart(LocalDate d) {
        return d.atStartOfDay(zone).toEpochSecond();
    }

    // ------------------------------------------------------------------------------ main

    void analyze(double from, double to) {
        log.log("analysing " + date(from) + " to " + date(to));
        // start at a local midnight so every day's sleeps are decided within one chunk
        LocalDate d0 = LocalDate.parse(date(from - 36 * 3600));
        LocalDate d1 = LocalDate.parse(date(to));
        // a week at a time keeps memory flat on a long first sync
        for (LocalDate c = d0; !c.isAfter(d1); c = c.plusDays(7)) {
            analyzeNights(dayStart(c), Math.min(to + 1, dayStart(c.plusDays(7))));
        }
        for (LocalDate d = d0; !d.isAfter(d1); d = d.plusDays(1)) analyzeDay(d);
    }

    // ---------------------------------------------------------------------------- nights

    void analyzeNights(double from, double to) {
        // ring hypnogram pages: only sleep_phase_data (0x5a) is validated as numbered pages
        int[] tags = {EventCodec.BEDTIME_PERIOD, EventCodec.SLEEP_PHASE_DATA,
            EventCodec.SLEEP_PERIOD_INFO_2, EventCodec.HRV_EVENT, EventCodec.SLEEP_TEMP_EVENT, EventCodec.SLEEP_ACM_PERIOD};
        List<Db.Row> rows = db.events(tags, from - 12 * 3600, to + 3600);
        List<double[]> bedtimes = new ArrayList<double[]>();
        List<double[]> pageTimes = new ArrayList<double[]>();
        List<int[]> pages = new ArrayList<int[]>();
        TS state = new TS(), support = new TS();
        for (Db.Row r : rows) {
            switch (r.tag) {
                case EventCodec.BEDTIME_PERIOD: {
                    long[] bt = EventCodec.bedtime(r.body);
                    if (bt == null) break;
                    double s = r.unix + (bt[0] - r.ds) / 10.0, e = r.unix + (bt[1] - r.ds) / 10.0;
                    if (e > s && e - s < 20 * 3600 && s > r.unix - 48 * 3600 && e <= r.unix + 3600) bedtimes.add(new double[] {s, e});
                    break;
                }
                case EventCodec.SLEEP_PHASE_DATA: {
                    int[] codes = EventCodec.sleepPhases(r.body);
                    if (codes == null) break;
                    int[] pc = new int[codes.length + 1];
                    pc[0] = r.body[0] & 0xff;
                    System.arraycopy(codes, 0, pc, 1, codes.length);
                    pageTimes.add(new double[] {r.unix});
                    pages.add(pc);
                    break;
                }
                case EventCodec.SLEEP_PERIOD_INFO_2: {
                    EventCodec.SleepPeriod sp = EventCodec.sleepPeriod(r.body);
                    if (sp != null) {
                        state.add(r.unix, sp.sleepState);
                        support.add(r.unix, 1);
                    }
                    break;
                }
                default:
                    support.add(r.unix, 1);
            }
        }
        List<NightFinder.RingRun> runs = NightFinder.ringRuns(pageTimes, pages);
        List<NightFinder.Period> periods = NightFinder.find(bedtimes, runs, state, support);
        // phone use (issue #13): someone using the phone is awake, whatever the ring thinks
        List<double[]> screen = new ArrayList<double[]>();
        if (prefs.screenAwake()) {
            // a session of many hours is most likely a lost "off" (or a video left playing): not used for sleep
            for (double[] s : db.screen(from - 36 * 3600, to + 3600)) if (s[1] - s[0] <= ScreenTime.MAX_NIGHT_SESSION_S) screen.add(s);
        }

        List<Night> nights = new ArrayList<Night>();
        for (NightFinder.Period p : periods) {
            if (p.end < from) continue;
            double start = p.start, end = p.end;
            if (!screen.isEmpty()) {
                double[] w = ScreenTime.trim(p.start, p.end, screen);
                if (w[1] - w[0] < ScreenTime.MIN_WINDOW_S) {
                    log.log("skipped a sleep window " + clock(p.start) + " to " + clock(p.end) + ": the phone was in use");
                    continue;
                }
                start = w[0];
                end = w[1];
            }
            Night n = analyzeNight(p, start, end, screen);
            if (n == null) continue;
            // a ring lying on a table or charger can report "asleep": skip windows that read
            // room temperature or a heart rate no sleeping person has
            if (!Double.isNaN(n.temp) && n.temp < app.openring.data.RightNow.WORN_MIN_C) {
                log.log("skipped a sleep window at room temperature (ring not worn)");
                continue;
            }
            if (!Double.isNaN(n.avgHr) && n.avgHr > 100) {
                log.log("skipped a sleep window with an implausible heart rate");
                continue;
            }
            nights.add(n);
        }
        // main sleep per day = the longest one
        Map<String, Night> main = new HashMap<String, Night>();
        for (Night n : nights) {
            String d = date(n.end);
            Night m = main.get(d);
            if (m == null || n.asleepMin > m.asleepMin) main.put(d, n);
        }
        List<Object[]> out = new ArrayList<Object[]>();
        for (Night n : nights) {
            String d = date(n.end);
            n.main = main.get(d) == n;
            n.score = Scores.sleep(n, tzOffset(n.midpoint > 0 ? n.midpoint : n.end), prefs.age());
            JSONObject js = Json.night(n, d);
            // daylight saving during the night (issue #22): the Sleep page marks the moment
            ClockChange.Change cc = ClockChange.find(zone, n.start, n.end);
            if (cc != null) {
                Json.put(js, "clock_change_at", cc.at);
                Json.put(js, "clock_change_min", cc.minutes);
                Json.put(js, "clock_change_from", cc.from);
                Json.put(js, "clock_change_to", cc.to);
            }
            out.add(new Object[] {n.start, n.end, d, n.main ? 1 : 0, n.score.value, js.toString()});
            log.log(String.format(java.util.Locale.US, "sleep for %s%s: %s to %s, %.0f min asleep, score %d, stages from %s",
                    d, n.main ? "" : " (nap)", clock(n.start), clock(n.end), n.asleepMin, n.score.value, n.stageSource));
        }
        db.replaceNights(from, Double.MAX_VALUE, out);
        log.log(nights.size() + " sleep periods");
    }

    /** Analyses the sleep window [start, end) of period {@code p} (its edges may have moved past phone use). */
    Night analyzeNight(NightFinder.Period p, double start, double end, List<double[]> screen) {
        int[] tags = {EventCodec.HRV_EVENT, EventCodec.MOTION_EVENT, EventCodec.SLEEP_PERIOD_INFO_2, EventCodec.GREEN_IBI_QUALITY,
            EventCodec.SLEEP_TEMP_EVENT, EventCodec.SPO2_EVENT, EventCodec.SPO2_R_PI, EventCodec.AOHR_EVENT};
        List<Db.Row> rows = db.events(tags, start - 900, end + 900);
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = start;
        in.end = end;
        for (double[] s : screen) if (s[1] > start && s[0] < end) in.screen.add(s);
        TS spHr = new TS();
        TS motionFromSp = new TS();
        for (Db.Row r : rows) {
            switch (r.tag) {
                case EventCodec.HRV_EVENT: {
                    int[][] h = EventCodec.hrv(r.body);
                    if (h == null) break;
                    for (int i = 0; i < h[0].length; i++) {
                        double t = r.unix + i * EventCodec.HRV_INTERVAL_S;
                        if (h[0][i] > 0) in.hr5.add(t, h[0][i]);
                        if (h[1][i] > 0) in.hrv5.add(t, h[1][i]);
                    }
                    break;
                }
                case EventCodec.MOTION_EVENT: {
                    int m = EventCodec.motionSeconds(r.body);
                    if (m >= 0) in.motion.add(r.unix, m);
                    break;
                }
                case EventCodec.SLEEP_PERIOD_INFO_2: {
                    EventCodec.SleepPeriod sp = EventCodec.sleepPeriod(r.body);
                    if (sp == null) break;
                    in.sleepState.add(r.unix, sp.sleepState);
                    if (sp.avgHr > 0) spHr.add(r.unix, sp.avgHr);
                    if (sp.sleepState == 1 && sp.breath > 0) in.breath.add(r.unix, sp.breath);
                    motionFromSp.add(r.unix, Math.min(30, sp.motionCount));
                    break;
                }
                case EventCodec.GREEN_IBI_QUALITY: {
                    int[][] g = EventCodec.greenIbi(r.body);
                    if (g == null) break;
                    for (int i = 0; i < g[0].length; i++) {
                        if (g[1][i] == 1 && g[0][i] >= 300 && g[0][i] <= 2000) in.beats.add(r.unix, 60000.0 / g[0][i]);
                    }
                    break;
                }
                case EventCodec.AOHR_EVENT: {
                    int[][] a = EventCodec.aohr(r.body);
                    if (a == null) break;
                    for (int i = 0; i < a[0].length; i++) if (a[0][i] >= 30 && a[0][i] <= 220) in.beats.add(r.unix + i * 1.92, a[0][i]);
                    break;
                }
                case EventCodec.SLEEP_TEMP_EVENT: {
                    // only the nocturnal stream is calibrated skin temperature; temp_event mixes
                    // device and ambient channels (open_health)
                    double[] t = EventCodec.temps(r.body);
                    if (t != null) for (double v : t) in.temp.add(r.unix, v);
                    break;
                }
                case EventCodec.SPO2_EVENT: {
                    int[] s = EventCodec.spo2(r.body);
                    if (s != null) for (int i = 0; i < s.length; i++) if (s[i] >= 70 && s[i] <= 100) in.spo2.add(r.unix + i, s[i]);
                    break;
                }
                case EventCodec.SPO2_R_PI: {
                    double[] rr = EventCodec.spo2R(r.body);
                    if (rr != null) for (int i = 0; i < rr.length; i++) if (rr[i] > 0.2 && rr[i] < 1.6) in.spo2.add(r.unix + i, EventCodec.spo2FromR(rr[i]));
                    break;
                }
                default:
                    break;
            }
        }
        if (in.hr5.isEmpty() && !spHr.isEmpty()) in.hr5 = NightAnalyzer.bin(spHr, start, end, 300);
        if (in.motion.isEmpty()) in.motion = motionFromSp;
        if (p.staged && p.codes != null) {
            // the ring's hypnogram ends at the period's own end, even when the window was shortened
            in.ringStages = NightFinder.align(new NightFinder.RingRun(p.end - p.codes.length * 30.0, p.end, p.codes), start, end);
        }
        in.stageDetail = prefs.stageDetail();
        return NightAnalyzer.analyze(in);
    }

    // ------------------------------------------------------------------------------ days

    static JSONObject nightJson(List<String> l) {
        return l.isEmpty() ? null : Json.parse(l.get(l.size() - 1));
    }

    void analyzeDay(LocalDate day) {
        String key = day.toString();
        double ds = dayStart(day), de = dayStart(day.plusDays(1));
        int weight = prefs.weightKg();
        double bmr = Wellness.bmr(prefs.age(), prefs.sex(), weight);

        // ---- activity ----
        TS met = new TS();
        for (Db.Row r : db.events(new int[] {EventCodec.ACTIVITY_INFO}, ds - 3 * 3600, de)) {
            double[] m = EventCodec.activityMet(r.body);
            if (m == null) continue;
            for (int i = 0; i < m.length; i++) met.add(r.unix + i * 60, m[i]);
        }
        List<double[]> sleeps = new ArrayList<double[]>();
        for (String s : db.nightsJson(day.minusDays(1).toString(), day.plusDays(1).toString(), false)) {
            JSONObject o = Json.parse(s);
            if (o != null) sleeps.add(new double[] {o.optDouble("start"), o.optDouble("end")});
        }
        Wellness.Day d = Wellness.day(key, ds, met, sleeps, weight, bmr);

        JSONObject mainNight = nightJson(db.nightsJson(key, key, true));
        boolean anyHr = db.count("SELECT COUNT(*) FROM events WHERE tag IN (93, 128, 134, 106) AND unix >= " + (long) ds + " AND unix < " + (long) de) > 0;
        if (d.coveredMin == 0 && mainNight == null && !anyHr) return;

        // history: previous 60 days of day summaries and main nights
        List<JSONObject> prevDays = new ArrayList<JSONObject>();
        for (String[] r : db.daysJson(day.minusDays(60).toString(), day.minusDays(1).toString())) {
            JSONObject o = Json.parse(r[1]);
            if (o != null) prevDays.add(o);
        }
        List<JSONObject> prevNights = new ArrayList<JSONObject>();
        for (String s : db.nightsJson(day.minusDays(90).toString(), day.minusDays(1).toString(), true)) {
            JSONObject o = Json.parse(s);
            if (o != null) prevNights.add(o);
        }

        JSONObject out = new JSONObject();
        Json.put(out, "date", key);

        // ---- readiness ----
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        ri.historyNights = prevNights.size();
        double tempDev = Double.NaN;
        if (mainNight != null) {
            JSONObject sc = mainNight.optJSONObject("score");
            if (sc != null && sc.has("value") && sc.optInt("value", -1) >= 0) ri.sleepScore = sc.optInt("value");
            ri.rhr = Json.d(mainNight, "lowest_hr");
            double[] rhrHist = lastValues(prevNights, "lowest_hr", 14);
            if (Stats.countFinite(rhrHist) >= 3) {
                ri.rhrBaseline = Stats.mean(rhrHist);
                ri.rhrSd = Stats.sd(rhrHist);
            }
            double hrvTonight = Json.d(mainNight, "avg_hrv");
            double[] hrv14 = lastValues(prevNights, "avg_hrv", 13);
            double[] hrv90 = lastValues(prevNights, "avg_hrv", 90);
            if (!Double.isNaN(hrvTonight) && Stats.countFinite(hrv90) >= 3) {
                // two week weighted average (newest heaviest) vs the long term average
                double ws = 0, w = 0;
                double[] recent = new double[hrv14.length + 1];
                System.arraycopy(hrv14, 0, recent, 0, hrv14.length);
                recent[hrv14.length] = hrvTonight;
                for (int i = 0; i < recent.length; i++) {
                    if (Double.isNaN(recent[i])) continue;
                    double wi = 1 + i;
                    ws += wi * recent[i];
                    w += wi;
                }
                ri.hrvRecent = ws / w;
                ri.hrvBaseline = Stats.mean(hrv90);
            }
            double temp = Json.d(mainNight, "temp");
            double[] tempHist = lastValues(prevNights, "temp", 30);
            if (!Double.isNaN(temp) && Stats.countFinite(tempHist) >= 3) {
                tempDev = temp - Stats.median(tempHist);
                ri.tempDev = tempDev;
            }
            double low = Json.d(mainNight, "lowest_hr_time"), s0 = Json.d(mainNight, "sleep_start"), s1 = Json.d(mainNight, "sleep_end");
            if (!Double.isNaN(low) && s1 > s0) ri.recoveryFraction = Stats.clamp((low - s0) / (s1 - s0), 0, 1);
        }
        // sleep balance and debt from total sleep per day
        double[] asleep14 = new double[14];
        int[] actual = new int[14], need = new int[14];
        Map<String, Double> asleepByDay = new HashMap<String, Double>();
        for (String s : db.nightsJson(day.minusDays(104).toString(), key, false)) {
            JSONObject o = Json.parse(s);
            if (o == null) continue;
            String dd = o.optString("day");
            Double prev = asleepByDay.get(dd);
            asleepByDay.put(dd, (prev == null ? 0 : prev) + o.optDouble("asleep_min", 0) * 60);
        }
        for (int i = 0; i < 14; i++) {
            LocalDate di = day.minusDays(i);
            Double a = asleepByDay.get(di.toString());
            actual[i] = a == null ? 0 : (int) Math.round(a);
            asleep14[i] = a == null ? Double.NaN : a;
            double[] prior = new double[90];
            for (int k = 0; k < 90; k++) {
                Double v = asleepByDay.get(di.minusDays(k + 1).toString());
                prior[k] = v == null ? Double.NaN : v;
            }
            need[i] = Wellness.sleepNeedS(prior);
        }
        double avgAsleep = Stats.mean(asleep14);
        if (!Double.isNaN(avgAsleep) && Stats.countFinite(asleep14) >= 3) ri.sleepBalance = avgAsleep / need[0];
        double[] debt = Wellness.sleepDebt(actual, need);
        JSONObject debtJ = new JSONObject();
        Json.put(debtJ, "debt_min", debt[0] / 60);
        Json.put(debtJ, "valid", debt[1] > 0);
        Json.put(debtJ, "need_h", need[0] / 3600.0);
        Json.put(out, "sleep_debt", debtJ);

        JSONObject yesterday = prevDays.isEmpty() ? null : prevDays.get(prevDays.size() - 1);
        if (yesterday != null && yesterday.optString("date").equals(day.minusDays(1).toString())) {
            double t = Json.d(yesterday, "target_kcal");
            if (t > 0) ri.prevDayActivity = Json.d(yesterday, "active_kcal") / t;
        }
        double[] act7 = lastValues(prevDays, "active_kcal", 7), act28 = lastValues(prevDays, "active_kcal", 28);
        if (Stats.countFinite(act7) >= 3 && Stats.countFinite(act28) >= 7 && Stats.mean(act28) > 0) {
            ri.activityBalance = Stats.mean(act7) / Stats.mean(act28);
        }
        Night.Score readiness = null;
        if (mainNight != null) {
            readiness = Scores.readiness(ri);
            Json.put(out, "readiness", Json.score(readiness));
        }

        // ---- activity score and target ----
        int target = prefs.targetKcal();
        if (target <= 0) {
            double base = Math.round(bmr * 0.22 / 10) * 10;
            if (readiness != null && readiness.value >= 85) base *= 1.15;
            else if (readiness != null && readiness.value < 70) base *= 0.8;
            target = (int) (Math.round(base / 10) * 10);
        }
        d.targetKcal = target;
        Scores.ActivityInput ai = new Scores.ActivityInput();
        if (d.coveredMin >= 60) {
            ai.inactiveMin = d.inactiveMin;
            ai.longInactiveStreaks = d.longInactiveStreaks;
            int met7 = d.activeKcal >= target ? 1 : 0, n7 = 1, train = (d.mediumMin + d.highMin >= 30) ? 1 : 0;
            double trainKcal = d.trainingKcal;
            for (JSONObject p : prevDays) {
                LocalDate pd = LocalDate.parse(p.optString("date", key));
                if (pd.isBefore(day.minusDays(6))) continue;
                if (p.optDouble("covered_min", 0) < 60) continue; // ring not worn: left out (issue #44)
                n7++;
                if (p.optDouble("active_kcal", 0) >= p.optDouble("target_kcal", 1e9)) met7++;
                if (p.optDouble("medium_min", 0) + p.optDouble("high_min", 0) >= 30) train++;
                trainKcal += p.optDouble("training_kcal", 0);
            }
            ai.days7 = n7;
            ai.targetsMetFraction = met7 / (double) n7;
            Json.put(out, "activity_days", n7);
            ai.trainingDays7 = train;
            ai.trainingKcal7 = trainKcal;
            Night.Score as = Scores.activity(ai);
            Json.put(out, "activity_score", Json.score(as));
        }
        Json.put(out, "steps", d.steps);
        Json.put(out, "active_kcal", d.activeKcal);
        Json.put(out, "total_kcal", d.totalKcal);
        Json.put(out, "distance_m", d.distanceM);
        Json.put(out, "low_min", d.lowMin);
        Json.put(out, "medium_min", d.mediumMin);
        Json.put(out, "high_min", d.highMin);
        Json.put(out, "inactive_min", d.inactiveMin);
        Json.put(out, "rest_min", d.restMin);
        Json.put(out, "non_wear_min", d.nonWearMin);
        Json.put(out, "training_kcal", d.trainingKcal);
        Json.put(out, "long_inactive", d.longInactiveStreaks);
        Json.put(out, "avg_met", d.avgMet);
        Json.put(out, "covered_min", d.coveredMin);
        Json.put(out, "target_kcal", d.targetKcal);
        JSONArray prof = new JSONArray();
        for (double v : d.metProfile) prof.put(Double.isNaN(v) ? JSONObject.NULL : (Object) (Math.round(v * 100) / 100.0));
        Json.put(out, "met_profile", prof);

        // ---- vitals for trends ----
        if (mainNight != null) {
            Json.put(out, "rhr", Json.d(mainNight, "lowest_hr"));
            Json.put(out, "hrv", Json.d(mainNight, "avg_hrv"));
            Json.put(out, "breath", Json.d(mainNight, "breath"));
            Json.put(out, "spo2", Json.d(mainNight, "spo2"));
            Json.put(out, "temp", Json.d(mainNight, "temp"));
            Json.put(out, "temp_dev", tempDev);
            Json.put(out, "asleep_min", Json.d(mainNight, "asleep_min"));
            JSONObject sc = mainNight.optJSONObject("score");
            if (sc != null && sc.has("value")) Json.put(out, "sleep_score", sc.optInt("value"));
            Json.put(out, "vo2max", Wellness.vo2maxUth(prefs.age(), Json.d(mainNight, "lowest_hr")));

            // symptom radar against the previous nights
            double[] tonight = {Json.d(mainNight, "temp"), Json.d(mainNight, "lowest_hr"), Json.d(mainNight, "avg_hrv"), Json.d(mainNight, "breath")};
            double[][] hist = {lastValues(prevNights, "temp", 30), lastValues(prevNights, "lowest_hr", 30),
                lastValues(prevNights, "avg_hrv", 30), lastValues(prevNights, "breath", 30)};
            Wellness.Radar radar = Wellness.radar(tonight, hist);
            JSONObject rj = new JSONObject();
            Json.put(rj, "available", radar.available);
            Json.put(rj, "status", radar.status);
            Json.put(rj, "decision", radar.decision);
            JSONObject mk = new JSONObject();
            for (Map.Entry<String, double[]> e : radar.markers.entrySet()) {
                JSONArray a = new JSONArray();
                for (double v : e.getValue()) a.put((Object) Double.valueOf(Double.isNaN(v) ? 0 : Math.round(v * 100) / 100.0));
                Json.put(mk, e.getKey(), a);
            }
            Json.put(rj, "markers", mk);
            Json.put(out, "radar", rj);
        }

        // ---- daytime heart rate ----
        TS beats = new TS();
        double low = Double.NaN, high = Double.NaN;
        for (Db.Row r : db.events(new int[] {EventCodec.GREEN_IBI_QUALITY, EventCodec.AOHR_EVENT, EventCodec.HRV_EVENT, EventCodec.SLEEP_PERIOD_INFO_2}, ds, de)) {
            if (r.tag == EventCodec.GREEN_IBI_QUALITY) {
                int[][] g = EventCodec.greenIbi(r.body);
                if (g != null) for (int i = 0; i < g[0].length; i++) if (g[1][i] == 1 && g[0][i] >= 300 && g[0][i] <= 2000) beats.add(r.unix, 60000.0 / g[0][i]);
            } else if (r.tag == EventCodec.AOHR_EVENT) {
                int[][] a = EventCodec.aohr(r.body);
                if (a != null) for (int i = 0; i < a[0].length; i++) if (a[0][i] >= 30 && a[0][i] <= 220) beats.add(r.unix + i * 1.92, a[0][i]);
            } else if (r.tag == EventCodec.HRV_EVENT) {
                int[][] h = EventCodec.hrv(r.body);
                if (h != null) for (int i = 0; i < h[0].length; i++) if (h[0][i] >= 30) beats.add(r.unix + i * 300, h[0][i]);
            } else {
                EventCodec.SleepPeriod sp = EventCodec.sleepPeriod(r.body);
                if (sp != null && sp.avgHr >= 30) beats.add(r.unix, sp.avgHr);
            }
        }
        if (beats.size() > 0) {
            TS binned = NightAnalyzer.bin(beats.sort(), ds, de, 900);
            Json.put(out, "hr_day", Json.series(binned, 96));
            low = Stats.percentile(beats.values(), 0.02);
            high = Stats.percentile(beats.values(), 0.98);
            Json.put(out, "hr_day_low", low);
            Json.put(out, "hr_day_high", high);
            Json.put(out, "hr_day_avg", beats.mean());
        }
        db.putDay(key, out.toString());
    }

    /** The last {@code n} values of a field from oldest to newest lists (NaN where missing). */
    static double[] lastValues(List<JSONObject> list, String field, int n) {
        int from = Math.max(0, list.size() - n);
        double[] out = new double[list.size() - from];
        for (int i = from; i < list.size(); i++) out[i - from] = Json.d(list.get(i), field);
        return out;
    }
}
