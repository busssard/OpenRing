package app.openring.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
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
import app.openring.core.analysis.DayHeart;
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
    /** The phone's zone now; each night and day uses the zone it was in (issue #22), this only when none was noted. */
    private final ZoneId zone = ZoneId.systemDefault();
    private final Log log;
    /** Earlier nights and days for the day summaries of one {@link #analyze} call (issue #139); null otherwise. */
    private History history;
    /** Tests only: false reads every day's history straight from the database, as before issue #139. */
    static volatile boolean cacheHistory = true;
    /** Tests only: runs after each day summary is written (a test throws from it to stop a run part way). */
    static volatile Runnable afterDay;
    /** Tests only: when above 0, the time (unix seconds) a day still running is analysed at (issue #155). */
    static volatile long testNow;

    private Analyzer(Context c, Log log) {
        db = Db.get(c);
        prefs = Prefs.of(c);
        this.log = log == null ? new Log() {
            @Override
            public void log(String m) {}
        } : log;
    }

    /** kv: Re analyse all was asked for and has not been stored yet; the next analysis does it (issue #230). */
    static final String FULL_PENDING = "full_pending";
    /**
     * An analysis of more than this many days is a long one (issue #230): ring tasks that store data wait for it
     * instead of hanging on its open transaction with the ring held. 28 days take up to about 10 seconds on a
     * phone; the analysis after a sync (the last two days) stays far below.
     */
    static final int LONG_RUN_DAYS = 28;

    /**
     * Incremental: re analyse from a little before the last analysed point up to now. Any failure, also running out
     * of memory, rolls the results back to the old ones and stores its text in {@code analysis_error} (issue #230).
     */
    public static void run(Context c, Log log) {
        RUNS.incrementAndGet();
        try {
            runLocked(c, log);
        } finally {
            RUNS.decrementAndGet();
        }
    }

    /**
     * Analyses running or waiting for the analysis lock, counted before they take it (review of #243): the main screen
     * and the demo switch ask this instead of waiting minutes for a long run.
     */
    private static final java.util.concurrent.atomic.AtomicInteger RUNS = new java.util.concurrent.atomic.AtomicInteger();

    /** kv: what the stored nights and days were worked out from ({@link #inputs}), stored with them (issue #194). */
    static final String INPUTS = "analysis_inputs";

    /**
     * True when the stored nights and days are what an analysis would give now (issue #194), so a sync that stored
     * nothing need not date and analyse again: no event since the last analysis (also none from Live or Find my
     * ring), nothing asked it to look again ({@code analyzed_until}: new screen use, a new zone), the same settings,
     * profile, rule versions, zone and date (so the first sync after midnight still finishes the day before), no
     * error or pending Re analyse all, and dates that the new now leaves as they are. Notes the phone's zone first,
     * as the analysis would.
     */
    public static boolean upToDate(Context c) {
        return upToDate(c, System.currentTimeMillis() / 1000);
    }

    static synchronized boolean upToDate(Context c, long nowUnix) {
        return new Analyzer(c, null).upToDate(nowUnix);
    }

    private boolean upToDate(long nowUnix) {
        if (db.kv("analysis_error", null) != null || db.kvLong(FULL_PENDING, 0) == 1) return false;
        String stored = db.kv(INPUTS, null);
        if (stored == null) return false;
        app.openring.core.Dating.State st = db.timeState(nowUnix);
        if (st == null || st.dated == 0) return false;
        if (!prefs.demo()) noteZone(db, nowUnix, zone, st.dmax);
        return stored.equals(inputs(st.lastId, db.kvLong("analyzed_until", 0), nowUnix));
    }

    /**
     * After a sync: true when nothing is new ({@link #upToDate}); then only the day still running is summarised again,
     * since its Total burn counts up to now ("So far (up to 08:07)", issue #155), in one transaction. False: the caller
     * runs the analysis.
     */
    public static boolean skipAfterSync(Context c, Log log) {
        return skipAfterSync(c, log, testNow > 0 ? testNow : System.currentTimeMillis() / 1000);
    }

    static synchronized boolean skipAfterSync(Context c, Log log, long nowUnix) {
        final Analyzer a = new Analyzer(c, log);
        if (!a.upToDate(nowUnix)) return false;
        final String today = a.date(nowUnix);
        String stored = a.db.dayJson(today);
        if (stored == null || !stored.contains("\"total_until\"")) return true;
        final double sure = a.db.undatedAfterNewest() ? a.db.timeState(nowUnix).dmax : Double.POSITIVE_INFINITY;
        try {
            a.db.inTransaction(new Runnable() {
                @Override
                public void run() {
                    a.analyzeDay(LocalDate.parse(today), sure);
                }
            });
        } catch (Throwable e) {
            // rolled back: the day keeps its last summary; the next analysis stores its error as usual
            a.log.log("the day so far was not updated: " + e);
            return true;
        }
        a.log.log("nothing new since the last analysis, only the day so far updated");
        return true;
    }

    /** What an analysis of the events up to id {@code lastId}, analysed up to {@code until}, on the day of {@code nowUnix} depends on. */
    private String inputs(long lastId, long until, long nowUnix) {
        return lastId + " " + until + " " + prefs.stageDetail() + " " + (prefs.screenAwake() ? 1 : 0) + " " + StageFilter.VERSION + " "
                + NightFinder.VERSION + " " + DayHeart.VERSION + " " + ScreenTime.AWAKE_RULE + " " + prefs.age() + " " + prefs.sex() + " "
                + prefs.weightKg() + " " + prefs.heightCm() + " " + prefs.targetKcal() + " " + zone.getId() + " " + date(nowUnix);
    }

    /** True while an analysis runs or waits to run. */
    public static boolean running() {
        return RUNS.get() > 0;
    }

    /** {@link #running} for the ring gate: a swap of the database waits for it without the ring held (issue #219). */
    static final app.openring.core.RingGate.Busy RUNNING = new app.openring.core.RingGate.Busy() {
        @Override
        public boolean busy() {
            return running();
        }
    };

    /**
     * What the main screen does before it loads a day (review of #243): note the phone's zone and, when a new zone
     * opened, analyse the nights since. While an analysis runs it does nothing and returns false at once, so the
     * screen shows the stored (old) nights instead of waiting for the run; the running analysis notes the zone itself.
     */
    public static boolean beforeLoad(Context c) {
        if (running()) return false;
        if (Prefs.of(c).demo()) return true;
        Db db = Db.get(c);
        if (noteZone(db, System.currentTimeMillis() / 1000.0, ZoneId.systemDefault(), Double.NaN) && db.eventCount() > 0) run(c, null);
        return true;
    }

    /**
     * Runs {@code r} (the switch into or out of demo mode, which closes the database in use) only when no analysis
     * runs or waits, holding the analysis lock so none starts meanwhile; false, and {@code r} does not run, otherwise
     * (review of #243: closing the database under a long run's transaction made the run fail).
     */
    static boolean whenIdle(Runnable r) {
        if (running()) return false;
        synchronized (Analyzer.class) {
            if (running()) return false;
            r.run();
            return true;
        }
    }

    private static synchronized void runLocked(Context c, Log log) {
        Analyzer a = new Analyzer(c, log);
        try {
            a.runInner();
            a.db.put("analysis_error", null);
        } catch (Throwable e) {
            a.failed(e);
            throw e;
        }
    }

    /** Stores the error of a failed run (after its roll back); never throws, also when the database cannot be written. */
    private void failed(Throwable e) {
        try {
            db.put("analysis_error", e.toString());
        } catch (Throwable ignored) {
            // nothing more can be done here; the caller gets the error itself
        }
    }

    void runInner() {
        final long now = System.currentTimeMillis() / 1000;
        final boolean all = db.kvLong(FULL_PENDING, 0) == 1;
        // Re analyse all (also Repair) dates every event again; otherwise only the new part (issue #220)
        final Db.TimeReport t = db.rebuildTime(now, all);
        log.log(String.format(java.util.Locale.US, "time: %d of %d events dated, %d boot segments (%d with a time anchor), %d written, %d read, %s",
                t.datedAfter, t.total, t.segments, t.anchoredSegments, t.changed, t.rowsRead,
                t.full == null ? "dated the new part only" : "all events dated again (" + t.full + ")"));
        // a date that changed lowered analyzed_until already, in the dating's own commit (issue #188)
        if (!Double.isNaN(t.changedFrom)) log.log("dates written from " + date(t.changedFrom) + " on");
        long lastDated = db.kvLong("dated_count", -1), lastTotal = db.kvLong("event_count", -1);
        // the checks below only lower analyzed_until ("analyse again from here"), so they are stored at once: a run that
        // fails after them is done again, never skipped (issue #230)
        // older events gained (or lost) a date: their days must be analysed again
        if (lastDated < 0 || t.datedAfter > lastDated + (t.total - lastTotal) || t.datedAfter < lastDated) db.put("analyzed_until", 0);
        // the nights were staged with another detail level (changed in Settings, maybe while in demo
        // mode, or analysed by a version before 1.11): analyse everything again
        final int detail = prefs.stageDetail();
        if (db.kvLong("stage_detail", -1) != detail) db.put("analyzed_until", 0);
        // the detail filter's rules changed (issue #28): nights from older rules are analysed again once;
        // databases from before the version existed read as version 1
        if (db.kvLong("stage_filter", 1) != StageFilter.VERSION) db.put("analyzed_until", 0);
        // the rules for finding nights changed (issue #42, long wake ups): nights from older rules are
        // analysed again once; databases from before the version existed read as version 1
        if (db.kvLong("night_finder", 1) != NightFinder.VERSION) db.put("analyzed_until", 0);
        // the day's heart rate line changed (issue #97, plausibility range, readings after midnight): days from
        // older rules are analysed again once; databases from before the version existed read as version 1
        if (db.kvLong("day_heart", 1) != DayHeart.VERSION) db.put("analyzed_until", 0);
        // "count phone use as awake" switched (issue #13): only nights since the first screen data change
        final long screenAwake = prefs.screenAwake() ? 1 : 0;
        double firstScreen = db.screenStart(true);
        if (db.kvLong("screen_awake", screenAwake) != screenAwake && firstScreen >= 0) db.reanalyseFrom(firstScreen);
        // the rule for which steps phone use marks awake changed (issue #41): nights with screen data
        // are analysed again once; databases from before the version existed read as rule 1
        if (screenAwake == 1 && db.kvLong("phone_awake", 1) != ScreenTime.AWAKE_RULE && firstScreen >= 0) db.reanalyseFrom(firstScreen);
        // the dating knows the range of the dated rows, no scan of the table for it (issue #220)
        final double[] range = t.range;
        // the phone's time zone, noted at every sync (issue #22), never later than a day after the newest
        // dated event (a phone clock set far ahead for a moment). Demo mode has no zone history.
        if (!prefs.demo()) noteZone(db, now, zone, range == null ? Double.NaN : range[1]);
        if (range == null) {
            // Re analyse all stays pending: the stored nights are kept until there are dated events to analyse
            db.put("dated_count", t.datedAfter);
            db.put("event_count", t.total);
            log.log("no dated events yet, nothing to analyse");
            return;
        }
        final long untilRead = db.kvLong("analyzed_until", 0);
        double until = untilRead;
        // no age limit (issue #20): a whole run rebuilds every night; the week by week chunks keep memory flat
        // however long the history is
        final double from = all || until <= 0 ? range[0] : Math.max(range[0], until - 2 * 86400);
        boolean longRun = all || range[1] - from > LONG_RUN_DAYS * 86400.0;
        // read before the run: a setting changed meanwhile is not taken as analysed (issue #194)
        final String inputs = inputs(t.lastId, (long) range[1], testNow > 0 ? testNow : now);
        if (longRun) {
            int waited = db.beginLongRun();
            if (waited > 0) log.log("a long analysis: waited for a ring task to store its data");
        }
        try {
            // one transaction for all results (issue #230): the old nights and days stay, also for the screens reading
            // meanwhile, until the new ones are all stored; any stop or failure rolls back to them
            db.inTransaction(new Runnable() {
                @Override
                public void run() {
                    if (all) db.clearResults();
                    // the dating these results are worked out from: stored with them, so a run that did not finish
                    // still sees the change next time
                    db.put("dated_count", t.datedAfter);
                    db.put("event_count", t.total);
                    analyze(from, range[1]);
                    // a dating since the mark was read (Live dates on its own, issue #188, review of #250) may have lowered
                    // it: those days are not in these results, so the lower mark stays and the next analysis does them
                    long mark = db.kvLong("analyzed_until", 0);
                    if (mark < untilRead) log.log("dates moved during the analysis, the days from " + date(mark) + " on are analysed again next time");
                    db.put("analyzed_until", mark < untilRead ? mark : (long) range[1]);
                    db.put("stage_detail", detail);
                    db.put("stage_filter", StageFilter.VERSION);
                    db.put("night_finder", NightFinder.VERSION);
                    db.put("day_heart", DayHeart.VERSION);
                    db.put("screen_awake", screenAwake);
                    db.put("phone_awake", ScreenTime.AWAKE_RULE);
                    if (all) db.put(FULL_PENDING, 0);
                    db.put("last_analysis", now);
                    db.put(INPUTS, inputs);
                }
            });
        } finally {
            if (longRun) db.endLongRun();
        }
    }

    /**
     * Delete all local data (Settings). Holds the analysis lock like {@link #run}, restore and pairing,
     * so an analysis that already read a week of nights finishes first and cannot write them back
     * into the emptied database afterwards (issue #43).
     */
    public static void deleteAll(Context c) {
        deleteAll(Db.get(c));
    }

    public static synchronized void deleteAll(Db db) {
        db.resetAll();
    }

    /**
     * Rebuild everything from the raw events. Only a mark is written first ({@link #FULL_PENDING}); the old nights and
     * days are replaced in the analysis's one transaction (issue #230), and a run that does not finish is done again
     * by the next analysis.
     */
    public static void runAll(Context c, Log log) {
        RUNS.incrementAndGet();
        try {
            runAllLocked(c, log);
        } finally {
            RUNS.decrementAndGet();
        }
    }

    private static synchronized void runAllLocked(Context c, Log log) {
        Analyzer a = new Analyzer(c, log);
        try {
            a.db.put(FULL_PENDING, 1);
        } catch (Throwable e) {
            a.failed(e);
            throw e;
        }
        runLocked(c, log);
    }

    /**
     * Note the phone's zone at {@code unix} (issue #22). A time more than a day after this phone's clock
     * or after {@code newest} (the newest dated event, NaN when not known) is ignored, so a clock set
     * far ahead for a moment cannot spoil the history. When a new zone with another clock opened, the
     * nights since the end of the zone before belong to it and are analysed again; returns true then.
     * Synchronized with the analysis, which notes the zone itself from inside {@link #run}.
     */
    public static synchronized boolean noteZone(Db db, double unix, ZoneId zone, double newest) {
        if (unix > System.currentTimeMillis() / 1000.0 + 86400) return false;
        if (!Double.isNaN(newest) && unix > newest + 86400) return false;
        String before = db.zoneAt(Double.MAX_VALUE);
        double prevEnd = db.noteZone(unix, zone.getId());
        if (Double.isNaN(prevEnd)) return false;
        ZoneId old = ClockChange.zone(before);
        // Zurich to Paris: the same clock, nothing to analyse again
        Instant at = Instant.ofEpochSecond((long) prevEnd);
        if (old != null && old.getRules().getOffset(at).equals(zone.getRules().getOffset(at))) return false;
        db.reanalyseFrom(prevEnd);
        return true;
    }

    /** The zone at {@code unix} from the zone history, the phone's zone when none is known. */
    ZoneId zoneAt(double unix) {
        ZoneId z = ClockChange.zone(db.zoneAt(unix));
        return z == null ? zone : z;
    }

    String date(double unix) {
        return date(unix, zone);
    }

    static String date(double unix, ZoneId z) {
        return Instant.ofEpochSecond((long) unix).atZone(z).toLocalDate().toString();
    }

    static String clock(double unix, ZoneId z) {
        return Instant.ofEpochSecond((long) unix).atZone(z).toLocalTime().withNano(0).toString();
    }

    static int tzOffset(double unix, ZoneId z) {
        return z.getRules().getOffset(Instant.ofEpochSecond((long) unix)).getTotalSeconds();
    }

    /** Midnight starting {@code d} in the zone valid around that day. */
    double dayStartThen(LocalDate d) {
        return d.atStartOfDay(zoneAt(d.atTime(12, 0).atZone(zone).toEpochSecond())).toEpochSecond();
    }

    /** A day's zone: its main night's ({@code main}, null when none), else the zone history's, else the phone's. */
    ZoneId dayZone(LocalDate d, JSONObject main) {
        ZoneId z = main == null ? null : ClockChange.zone(main.optString("zone", null));
        return z != null ? z : zoneAt(d.atTime(12, 0).atZone(zone).toEpochSecond());
    }

    // ------------------------------------------------------------------------------ main

    void analyze(double from, double to) {
        log.log("analysing " + date(from) + " to " + date(to));
        // start at a local midnight so every day's sleeps are decided within one chunk
        LocalDate d0 = LocalDate.parse(date(from - 36 * 3600));
        // one day more: a night in a zone ahead of the phone's can end on the next date
        LocalDate d1 = LocalDate.parse(date(to)).plusDays(1);
        // a week at a time keeps memory flat on a long first sync; each week starts at midnight in
        // the zone valid then, so a day's nights are decided together also after a trip (issue #22)
        LocalDate first = d0;
        for (LocalDate c = d0; !c.isAfter(d1); c = c.plusDays(7)) {
            double a = dayStartThen(c);
            if (a > to) break;
            String changed = analyzeNights(a, Math.min(to + 1, dayStartThen(c.plusDays(7))));
            // a sleep across the first midnight is rebuilt as a whole (issue #132), its day too
            if (changed != null && changed.compareTo(first.toString()) < 0) first = LocalDate.parse(changed);
        }
        // a day with no data left loses its stored summary (issue #58), but only a day that ends by the newest
        // dated event while later events still wait for a time record: they may belong to the days after it
        double sure = db.undatedAfterNewest() ? to : Double.POSITIVE_INFINITY;
        // the nights are all written now: each earlier night and day is read once for every day's baselines
        // (issue #139); the store lives only for these days, also when one of them fails
        history = new History(db, cacheHistory);
        try {
            int n = 0;
            for (LocalDate d = first; !d.isAfter(d1); d = d.plusDays(1)) {
                analyzeDay(d, sure);
                history.forget(d);
                n++;
            }
            // stored days after the range (its newest event moved earlier, issue #58) are analysed too, so a day
            // that kept a summary without data behind it goes
            List<String> later = db.daysAfter(d1.toString());
            if (sure < Double.POSITIVE_INFINITY) {
                if (!later.isEmpty()) log.log(later.size() + " stored day" + (later.size() == 1 ? "" : "s") + " after " + d1 + " kept: events after it wait for a time record");
            } else {
                for (String k : later) {
                    LocalDate d = LocalDate.parse(k);
                    analyzeDay(d, sure);
                    history.forget(d);
                    n++;
                }
            }
            if (history.cached) log.log(n + " day" + (n == 1 ? "" : "s") + " summarised, " + history.nightsRead + " stored nights and "
                    + history.daysRead + " stored days read for them, at most " + history.maxKept + " kept at once");
        } finally {
            history = null;
        }
    }

    // ---------------------------------------------------------------------------- nights

    /**
     * Finds, analyses and stores the sleeps from {@code from} on (from earlier when a sleep crosses it,
     * see {@link #boundary}); returns the earliest day of a night it deleted or stored, null when none.
     */
    String analyzeNights(double from, double to) {
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
        // the clock tells a wake up in the middle of the night from a nap (issue #42), the clock of the zone
        // the sleep was slept in, not the phone's zone now (issue #161)
        NightFinder.LocalClock clock = new NightFinder.LocalClock() {
            double lastEnd = Double.NaN;
            ZoneId lastZone;

            @Override
            public int minuteOfDay(double unix, double end) {
                if (lastZone == null || end != lastEnd) {
                    lastZone = sleptIn(end);
                    lastEnd = end;
                }
                java.time.LocalTime lt = Instant.ofEpochSecond((long) unix).atZone(lastZone).toLocalTime();
                return lt.getHour() * 60 + lt.getMinute();
            }
        };
        List<NightFinder.Period> periods = NightFinder.find(bedtimes, runs, state, support, clock);
        // phone use (issue #13): someone using the phone is awake, whatever the ring thinks
        List<double[]> screen = new ArrayList<double[]>();
        if (prefs.screenAwake()) {
            // a session of many hours is most likely a lost "off" (or a video left playing): not used for sleep
            screen = ScreenTime.forNight(db.screen(from - 36 * 3600, to + 3600), from - 36 * 3600, to + 3600);
        }

        // the stored nights from lo on are replaced; lo is moved back so no sleep crosses it (issue #132)
        double lo = boundary(from, periods);
        List<Night> nights = new ArrayList<Night>();
        for (NightFinder.Period p : periods) {
            if (p.end < lo) continue;
            double start = p.start, end = p.end;
            if (!screen.isEmpty()) {
                double[] w = ScreenTime.trim(p.start, p.end, screen);
                if (w[1] - w[0] < ScreenTime.MIN_WINDOW_S) {
                    ZoneId pz = zoneAt(p.end);
                    log.log("skipped a sleep window " + clock(p.start, pz) + " to " + clock(p.end, pz) + ": the phone was in use");
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
        // each night in the zone it ended in (issue #22): the later zone counts for the whole night
        Map<Night, String> zoneIds = new HashMap<Night, String>();
        Map<Night, String> days = new HashMap<Night, String>();
        for (Night n : nights) {
            String zid = db.zoneAt(n.end);
            zoneIds.put(n, zid);
            ZoneId nz = zoneOf(zid);
            String d = date(n.end, nz);
            // a night between two notes counts in the later zone, but a night slept before the flight is never
            // on an earlier date than in the zone before (flying west, waking at 05:30 in Zurich is 23:30 in New
            // York the day before, which would make the night before a nap). A night slept after landing keeps
            // its own date (issue #162: across the date line 06:45 in Los Angeles is already the next day in Auckland)
            // the same rule as the night wake clock above (sleptIn), so the two cannot drift apart
            ZoneId slept = sleptIn(n.end);
            if (!slept.equals(nz)) {
                String earlier = date(n.end, slept);
                if (earlier.compareTo(d) > 0) d = earlier;
            }
            days.put(n, d);
        }
        // main sleep per day = the longest one, also against stored sleeps of the same day that stay (they
        // end before lo, issue #132), so a day keeps exactly one main sleep; earlier sleeps win a tie. The
        // days of stored sleeps deleted now count too: one whose sleep is not stored again (phone in use)
        // gets its longest remaining sleep as the main one
        Map<String, Night> main = new HashMap<String, Night>();
        Map<String, double[]> keptBest = new HashMap<String, double[]>();
        Map<String, List<double[]>> kept = new HashMap<String, List<double[]>>();
        java.util.Set<String> touched = new java.util.TreeSet<String>(days.values());
        touched.addAll(db.nightDaysFrom(lo));
        for (String d : touched) {
            List<double[]> k = db.nightsOfDayBefore(d, lo);
            kept.put(d, k);
            for (double[] x : k) if (keptBest.get(d) == null || x[1] > keptBest.get(d)[1]) keptBest.put(d, x);
        }
        for (Night n : nights) {
            String d = days.get(n);
            Night m = main.get(d);
            double[] kb = keptBest.get(d);
            if ((m == null || n.asleepMin > m.asleepMin) && (kb == null || n.asleepMin > kb[1])) main.put(d, n);
        }
        Map<Double, Boolean> keptMain = new HashMap<Double, Boolean>();
        for (Map.Entry<String, List<double[]>> e : kept.entrySet()) {
            for (double[] x : e.getValue()) {
                boolean m = main.get(e.getKey()) == null && keptBest.get(e.getKey()) == x;
                if ((x[2] == 1) != m) keptMain.put(x[0], m);
            }
        }
        List<Object[]> out = new ArrayList<Object[]>();
        for (Night n : nights) {
            String zid = zoneIds.get(n);
            ZoneId nz = zoneOf(zid);
            String d = days.get(n);
            n.main = main.get(d) == n;
            n.score = Scores.sleep(n, tzOffset(n.midpoint > 0 ? n.midpoint : n.end, nz), prefs.age());
            JSONObject js = Json.night(n, d);
            if (ClockChange.zone(zid) != null) Json.put(js, "zone", zid);
            // daylight saving during the night (issue #22): the Sleep page marks the moment
            ClockChange.Change cc = ClockChange.find(nz, n.start, n.end);
            if (cc != null) {
                Json.put(js, "clock_change_at", cc.at);
                Json.put(js, "clock_change_min", cc.minutes);
                Json.put(js, "clock_change_from", cc.from);
                Json.put(js, "clock_change_to", cc.to);
            }
            out.add(new Object[] {n.start, n.end, d, n.main ? 1 : 0, n.score.value, js.toString()});
            log.log(String.format(java.util.Locale.US, "sleep for %s%s: %s to %s, %.0f min asleep, score %d, stages from %s",
                    d, n.main ? "" : " (nap)", clock(n.start, nz), clock(n.end, nz), n.asleepMin, n.score.value, n.stageSource));
        }
        String changed = db.firstNightDay(lo);
        for (Object[] r : out) if (changed == null || ((String) r[2]).compareTo(changed) < 0) changed = (String) r[2];
        // sleeps the old rule left in place (ending before from) that are deleted now: rare, worth a line
        if (lo < from) {
            int rebuilt = 0, removed = 0;
            for (double[] x : db.nightSpans(lo, from)) {
                boolean again = false;
                for (Object[] r : out) again |= (Double) r[0] < x[1] && (Double) r[1] > x[0];
                if (again) rebuilt++;
                else removed++;
            }
            String at = clock(from, zoneAt(from)).substring(0, 5);
            if (rebuilt > 0) log.log("rebuilt " + rebuilt + " stored sleep" + (rebuilt == 1 ? "" : "s") + " that ended just before " + at + " (it ran past midnight)");
            if (removed > 0) log.log("removed " + removed + " stored sleep" + (removed == 1 ? "" : "s") + " that ended just before " + at + " (no sleep found there now)");
        }
        db.replaceNights(lo, Double.MAX_VALUE, out, keptMain);
        log.log(nights.size() + " sleep periods");
        return changed;
    }

    /**
     * Where this run replaces the stored nights (issue #132): {@code from}, moved back to the start of
     * any sleep period found now or any stored night that begins before it and ends at or after it,
     * until none does. A sleep moved past phone use is stored with its shortened end, so a night ending
     * just after {@code from} could be stored ending before it; it is analysed again here, and without
     * this its old row stayed next to the new one when the new start differed. No sleep crosses the
     * result, so every stored night it deletes is analysed again from the same events, and nights
     * before it (a nap ending before the night) are left alone. Each step moves it earlier, so it ends.
     */
    double boundary(double from, List<NightFinder.Period> periods) {
        double lo = from;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (NightFinder.Period p : periods) {
                if (p.start < lo && p.end >= lo) {
                    lo = p.start;
                    moved = true;
                }
            }
            double s = db.nightAcross(lo);
            if (s < lo) {
                lo = s;
                moved = true;
            }
        }
        return lo;
    }

    /**
     * The zone a sleep ending at {@code end} was slept in (issues #161, #162): the zone history's at its end
     * (between two notes the later one, "the last zone counts", #22), or the zone noted before the gap when
     * the sleep ends in its morning ({@link ClockChange#sleptBefore}); the phone's zone without any history.
     */
    ZoneId sleptIn(double end) {
        ZoneId later = zoneOf(db.zoneAt(end));
        ZoneId before = ClockChange.zone(db.zoneBeforeGap(end));
        return ClockChange.sleptBefore(end, before, later) ? before : later;
    }

    /** The zone of a noted id, the phone's zone when there is none (or the phone does not know it). */
    ZoneId zoneOf(String id) {
        ZoneId z = ClockChange.zone(id);
        return z == null ? zone : z;
    }

    /**
     * The stages a stored night gets with detail {@code level} (the Settings preview, issue #30): its raw
     * stages through the same {@link NightAnalyzer#detailStages} as the analysis, with the phone use the
     * analysis would take now ({@code phoneAwake}: Count phone use as awake, {@link ScreenTime#forNight})
     * and the ring's own wake ups stored with the night (none for nights analysed before this was stored).
     */
    public static int[] preview(Db db, boolean phoneAwake, JSONObject night, int level) {
        double start = Json.d(night, "start"), end = Json.d(night, "end");
        List<double[]> screen = new ArrayList<double[]>();
        if (phoneAwake && end > start) screen = ScreenTime.forNight(db.screen(start, end), start, end);
        return NightAnalyzer.detailStages(Json.stagesRaw(night), level, start, end, screen, Json.ringAwake(night));
    }

    /** Analyses the sleep window [start, end) of period {@code p} (its edges may have moved past phone use). */
    Night analyzeNight(NightFinder.Period p, double start, double end, List<double[]> screen) {
        int[] tags = {EventCodec.HRV_EVENT, EventCodec.MOTION_EVENT, EventCodec.SLEEP_PERIOD_INFO_2, EventCodec.GREEN_IBI_QUALITY,
            EventCodec.SLEEP_TEMP_EVENT, EventCodec.SPO2_EVENT, EventCodec.SPO2_R_PI, EventCodec.AOHR_EVENT};
        List<Db.Row> rows = db.events(tags, start - 900, end + 900);
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = start;
        in.end = end;
        in.screen.addAll(ScreenTime.forNight(screen, start, end));
        in.ringAwake.addAll(p.awake);
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
                        if (g[1][i] == 1 && EventCodec.plausibleIbi(g[0][i])) in.beats.add(r.unix, 60000.0 / g[0][i]);
                    }
                    break;
                }
                case EventCodec.AOHR_EVENT: {
                    int[][] a = EventCodec.aohr(r.body);
                    if (a == null) break;
                    for (int i = 0; i < a[0].length; i++) if (EventCodec.plausibleHr(a[0][i])) in.beats.add(r.unix + i * EventCodec.AOHR_INTERVAL_S, a[0][i]);
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
            // the ring's hypnogram at its own times: it ends where the ring's run ended, also when a bedtime
            // period made the period longer (issue #110) or phone use shortened the window
            in.ringStages = NightFinder.align(p.ringRun(), start, end);
        }
        in.stageDetail = prefs.stageDetail();
        return NightAnalyzer.analyze(in);
    }

    // ------------------------------------------------------------------------------ days

    static JSONObject nightJson(List<String> l) {
        return l.isEmpty() ? null : Json.parse(l.get(l.size() - 1));
    }

    /**
     * Analyses and stores one day. A day with no data behind it (no activity, heart rate or sleep) has its
     * stored summary removed (issue #58) when it ends at or before {@code sure}; a later one keeps it.
     */
    void analyzeDay(LocalDate day, double sure) {
        String key = day.toString();
        History past = history != null ? history : new History(db, false);
        // the day's own 24 hours, in the zone of its main night (issue #22)
        JSONObject mainNight = nightJson(db.nightsJson(key, key, true));
        ZoneId dz = dayZone(day, mainNight);
        double ds = day.atStartOfDay(dz).toEpochSecond(), de = day.plusDays(1).atStartOfDay(dz).toEpochSecond();
        int weight = prefs.weightKg();
        double bmr = Wellness.bmr(prefs.age(), prefs.sex(), weight);

        // ---- activity ----
        TS met = new TS();
        for (Db.Row r : db.events(new int[] {EventCodec.ACTIVITY_INFO}, ds - 3 * 3600, de)) {
            double[] m = EventCodec.activityMet(r.body);
            if (m == null) continue;
            // impossible values (a damaged byte) count as missing minutes (issue #120)
            for (int i = 0; i < m.length; i++) if (EventCodec.plausibleMet(m[i])) met.add(r.unix + i * 60, m[i]);
        }
        List<double[]> sleeps = new ArrayList<double[]>();
        for (JSONObject o : past.nights(day.minusDays(1), day.plusDays(1), false)) sleeps.add(new double[] {o.optDouble("start"), o.optDouble("end")});
        // every real minute of the day once, by its clock time in the day's zone (issue #114)
        // a day still running counts its resting burn only up to now (issue #155)
        long now = testNow > 0 ? testNow : System.currentTimeMillis() / 1000;
        Wellness.Day d = Wellness.day(key, ds, de, dz, met, sleeps, weight, bmr, now);

        boolean anyHr = db.count("SELECT COUNT(*) FROM events WHERE tag IN (93, 128, 134, 106) AND unix >= " + (long) ds + " AND unix < " + (long) de) > 0;
        if (d.coveredMin == 0 && mainNight == null && !anyHr) {
            // nothing behind this day any more (its events were dated again, issue #58): the summary goes like
            // its nights did. A nap still stored for it keeps the summary, as before.
            if (!db.nightsJson(key, key, false).isEmpty()) return;
            if (de <= sure) {
                past.dayWritten(key);
                if (db.deleteDay(key)) log.log("no data left for " + key + ", its summary was removed");
            } else if (db.dayJson(key) != null) {
                log.log("no data left for " + key + ", its summary was kept: events after it wait for a time record");
            }
            return;
        }

        // history: previous 60 days of day summaries and 90 days of main nights
        // Every field read from past.nights and past.days must be in History.NIGHT_FIELDS or DAY_FIELDS
        // (the store keeps only those; the pipeline test fails on any other, issue #228)
        List<JSONObject> prevDays = past.days(day.minusDays(60), day.minusDays(1));
        List<JSONObject> prevNights = past.nights(day.minusDays(90), day.minusDays(1), true);

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
        for (JSONObject o : past.nights(day.minusDays(104), day, false)) {
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
        // only on a day still running: up to when its resting burn counts (issue #155)
        if (!Double.isNaN(d.totalUntil)) Json.put(out, "total_until", (long) d.totalUntil);
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
        Json.put(out, "day_min", d.dayMin); // real minutes of the day, marks the rule of issue #114 (review of #202)
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
        // one plausibility range for every source (issue #97). Events from up to 30 minutes before midnight
        // are read too: an HRV event holds six values 5 minutes apart, so one starting at 23:50 has three after
        // midnight; the quarter hours keep only the values inside the day.
        TS beats = new TS();
        for (Db.Row r : db.events(new int[] {EventCodec.GREEN_IBI_QUALITY, EventCodec.AOHR_EVENT, EventCodec.HRV_EVENT, EventCodec.SLEEP_PERIOD_INFO_2},
                ds - DayHeart.LOOK_BACK_S, de)) {
            if (r.tag == EventCodec.GREEN_IBI_QUALITY) {
                int[][] g = EventCodec.greenIbi(r.body);
                if (g != null) for (int i = 0; i < g[0].length; i++) if (g[1][i] == 1 && EventCodec.plausibleIbi(g[0][i])) beats.add(r.unix, 60000.0 / g[0][i]);
            } else if (r.tag == EventCodec.AOHR_EVENT) {
                int[][] a = EventCodec.aohr(r.body);
                if (a != null) for (int i = 0; i < a[0].length; i++) if (EventCodec.plausibleHr(a[0][i])) beats.add(r.unix + i * EventCodec.AOHR_INTERVAL_S, a[0][i]);
            } else if (r.tag == EventCodec.HRV_EVENT) {
                int[][] h = EventCodec.hrv(r.body);
                if (h != null) for (int i = 0; i < h[0].length; i++) if (EventCodec.plausibleHr(h[0][i])) beats.add(r.unix + i * EventCodec.HRV_INTERVAL_S, h[0][i]);
            } else {
                EventCodec.SleepPeriod sp = EventCodec.sleepPeriod(r.body);
                if (sp != null && EventCodec.plausibleHr(sp.avgHr)) beats.add(r.unix, sp.avgHr);
            }
        }
        if (beats.size() > 0) {
            // the line: one point per quarter hour (100 on a 25 hour day, none merged). The Body tab takes
            // Lowest, Average and Highest from this stored line (issue #72), so they are not stored apart.
            Json.put(out, "hr_day", Json.series(DayHeart.curve(beats, ds, de), DayHeart.maxPoints(ds, de)));
        }
        past.dayWritten(key);
        db.putDay(key, out.toString());
        if (afterDay != null) afterDay.run();
    }

    /**
     * The earlier nights and days each day summary reads for its baselines (issue #139). Before, every day parsed
     * the full stored JSON of about 200 of them again. Now each is read and parsed once per analysis and kept as a
     * copy holding only the fields the summaries use, with the very values the parser gave, so every lookup
     * returns what parsing the stored row returns. Lists keep the database order: nights by their stored start
     * (the primary key, so no two are equal), days by date. Nights are not written while the days are analysed;
     * a day summary written or removed is read again from the database. Entries older than a day looks back are
     * dropped as the days move on, so memory stays flat; a day not kept is simply read again. Nothing is stored.
     * With {@code cached} false every lookup is the database query used before (the reference in the tests).
     */
    static final class History {
        static final String[] NIGHT_FIELDS = {"day", "start", "end", "asleep_min", "lowest_hr", "avg_hrv", "temp", "breath"};
        static final String[] DAY_FIELDS = {"date", "target_kcal", "active_kcal", "covered_min", "medium_min", "high_min", "training_kcal"};
        /** How far back a day looks: every night of 104 days (sleep balance), day summaries of 60 days. */
        static final int NIGHTS_BACK = 104, DAYS_BACK = 60;
        /** Tests only (issue #228): the copies throw when a field outside their list is read. Off in the app. */
        static volatile boolean strict;
        private static final JSONObject NONE = new JSONObject();
        private static final java.util.Comparator<Object[]> BY_START = new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[0], (Double) b[0]);
            }
        };

        final boolean cached;
        private final Db db;
        /** Day to its nights, each {start (Double), main (Boolean), fields (JSONObject)}, ordered by start. */
        private final java.util.TreeMap<String, List<Object[]>> nights = new java.util.TreeMap<String, List<Object[]>>();
        /** Day to its summary's fields, NONE when it has no readable summary. */
        private final java.util.TreeMap<String, JSONObject> days = new java.util.TreeMap<String, JSONObject>();
        int nightsRead, daysRead, maxKept;
        private int kept;

        History(Db db, boolean cached) {
            this.db = db;
            this.cached = cached;
        }

        /** The nights of the days from {@code from} to {@code to} ({@code mainOnly}: main nights), ordered by start. */
        List<JSONObject> nights(LocalDate from, LocalDate to, boolean mainOnly) {
            List<JSONObject> out = new ArrayList<JSONObject>();
            if (!cached) {
                for (String s : db.nightsJson(from.toString(), to.toString(), mainOnly)) {
                    JSONObject o = Json.parse(s);
                    if (o != null) out.add(o);
                }
                return out;
            }
            List<Object[]> rows = new ArrayList<Object[]>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                for (Object[] r : nightsOf(d.toString())) if (!mainOnly || (Boolean) r[1]) rows.add(r);
            }
            java.util.Collections.sort(rows, BY_START);
            for (Object[] r : rows) out.add((JSONObject) r[2]);
            return out;
        }

        /** The day summaries from {@code from} to {@code to}, oldest first. */
        List<JSONObject> days(LocalDate from, LocalDate to) {
            List<JSONObject> out = new ArrayList<JSONObject>();
            if (!cached) {
                for (String[] r : db.daysJson(from.toString(), to.toString())) {
                    JSONObject o = Json.parse(r[1]);
                    if (o != null) out.add(o);
                }
                return out;
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                String k = d.toString();
                JSONObject o = days.get(k);
                if (o == null) {
                    String js = db.dayJson(k);
                    if (js != null) daysRead++;
                    JSONObject p = Json.parse(js);
                    o = p == null ? NONE : copy(p, DAY_FIELDS);
                    days.put(k, o);
                    if (o != NONE) count(1);
                }
                if (o != NONE) out.add(o);
            }
            return out;
        }

        /** The summary of {@code day} is about to be written or removed: read it from the database next time. */
        void dayWritten(String day) {
            JSONObject o = days.remove(day);
            if (o != null && o != NONE) kept--;
        }

        /** {@code day} is done: drops what the days after it no longer read. */
        void forget(LocalDate day) {
            java.util.SortedMap<String, List<Object[]>> old = nights.headMap(day.minusDays(NIGHTS_BACK).toString());
            for (List<Object[]> l : old.values()) kept -= l.size();
            old.clear();
            java.util.SortedMap<String, JSONObject> oldDays = days.headMap(day.minusDays(DAYS_BACK).toString());
            for (JSONObject o : oldDays.values()) if (o != NONE) kept--;
            oldDays.clear();
        }

        private List<Object[]> nightsOf(String day) {
            List<Object[]> l = nights.get(day);
            if (l != null) return l;
            l = new ArrayList<Object[]>();
            for (Object[] r : db.nightsOfDay(day)) {
                nightsRead++;
                JSONObject p = Json.parse((String) r[2]);
                if (p != null) l.add(new Object[] {r[0], r[1], copy(p, NIGHT_FIELDS)});
            }
            nights.put(day, l);
            count(l.size());
            return l;
        }

        private void count(int n) {
            kept += n;
            maxKept = Math.max(maxKept, kept);
        }

        /** The given fields of {@code o}, the same value objects (absent ones stay absent). */
        static JSONObject copy(JSONObject o, String[] fields) {
            JSONObject c = strict ? new Strict(fields) : new JSONObject();
            for (String f : fields) {
                Object v = o.opt(f);
                if (v != null) Json.put(c, f, v);
            }
            return c;
        }

        /**
         * Tests only (issue #228): a copy that throws when code reads a name outside its list, so a new read the
         * store does not keep fails the pipeline test instead of giving NaN. Overrides every read by name that
         * Android's JSONObject and the org.json of the tests share.
         */
        static final class Strict extends JSONObject {
            private final String[] fields;

            Strict(String[] fields) {
                this.fields = fields;
            }

            private void check(String name) {
                for (String f : fields) if (f.equals(name)) return;
                throw new IllegalStateException("history field " + name + " is not in History." + (fields == NIGHT_FIELDS ? "NIGHT_FIELDS" : "DAY_FIELDS"));
            }

            @Override
            public Object opt(String name) {
                check(name);
                return super.opt(name);
            }

            @Override
            public boolean has(String name) {
                check(name);
                return super.has(name);
            }

            @Override
            public boolean isNull(String name) {
                check(name);
                return super.isNull(name);
            }

            @Override
            public double optDouble(String name) {
                check(name);
                return super.optDouble(name);
            }

            @Override
            public double optDouble(String name, double fallback) {
                check(name);
                return super.optDouble(name, fallback);
            }

            @Override
            public String optString(String name) {
                check(name);
                return super.optString(name);
            }

            @Override
            public String optString(String name, String fallback) {
                check(name);
                return super.optString(name, fallback);
            }

            @Override
            public int optInt(String name) {
                check(name);
                return super.optInt(name);
            }

            @Override
            public int optInt(String name, int fallback) {
                check(name);
                return super.optInt(name, fallback);
            }

            @Override
            public long optLong(String name) {
                check(name);
                return super.optLong(name);
            }

            @Override
            public long optLong(String name, long fallback) {
                check(name);
                return super.optLong(name, fallback);
            }

            @Override
            public boolean optBoolean(String name) {
                check(name);
                return super.optBoolean(name);
            }

            @Override
            public boolean optBoolean(String name, boolean fallback) {
                check(name);
                return super.optBoolean(name, fallback);
            }

            @Override
            public JSONArray optJSONArray(String name) {
                check(name);
                return super.optJSONArray(name);
            }

            @Override
            public JSONObject optJSONObject(String name) {
                check(name);
                return super.optJSONObject(name);
            }

            @Override
            public Object get(String name) throws JSONException {
                check(name);
                return super.get(name);
            }

            @Override
            public double getDouble(String name) throws JSONException {
                check(name);
                return super.getDouble(name);
            }

            @Override
            public String getString(String name) throws JSONException {
                check(name);
                return super.getString(name);
            }

            @Override
            public int getInt(String name) throws JSONException {
                check(name);
                return super.getInt(name);
            }

            @Override
            public long getLong(String name) throws JSONException {
                check(name);
                return super.getLong(name);
            }

            @Override
            public boolean getBoolean(String name) throws JSONException {
                check(name);
                return super.getBoolean(name);
            }
        }
    }

    /** The last {@code n} values of a field from oldest to newest lists (NaN where missing). */
    static double[] lastValues(List<JSONObject> list, String field, int n) {
        int from = Math.max(0, list.size() - n);
        double[] out = new double[list.size() - from];
        for (int i = from; i < list.size(); i++) out[i - from] = Json.d(list.get(i), field);
        return out;
    }
}
