package app.openring.data;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

import app.openring.core.analysis.ScreenScore;
import app.openring.core.analysis.ScreenTime;

/** Screen time per day from the stored screen sessions and apps in front, for the Screen tab and Today. */
public final class ScreenDays {
    private ScreenDays() {}

    /** Local hour borders of a day: midnight, 01:00, ... next midnight (23 or 25 hours on clock change days). */
    public static double[] hourEdges(LocalDate day, ZoneId zone) {
        List<Double> e = new ArrayList<Double>();
        ZonedDateTime t = day.atStartOfDay(zone);
        double end = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
        while (t.toEpochSecond() < end) {
            e.add((double) t.toEpochSecond());
            t = t.plusHours(1);
        }
        e.add(end);
        double[] out = new double[e.size()];
        for (int i = 0; i < out.length; i++) out[i] = e.get(i);
        return out;
    }

    /** Local hour of the day at each bin start of {@link #hourEdges}, for chart labels. */
    public static int[] hours(double[] edges, ZoneId zone) {
        int[] h = new int[Math.max(0, edges.length - 1)];
        for (int i = 0; i < h.length; i++) h[i] = java.time.Instant.ofEpochSecond((long) edges[i]).atZone(zone).getHour();
        return h;
    }

    /** One day's screen time, or null for a day before the first stored session (nothing known). */
    public static ScreenTime.Day day(Db db, LocalDate day, ZoneId zone) {
        double[] edges = hourEdges(day, zone);
        double first = db.screenStart(true);
        if (first < 0 || edges[edges.length - 1] <= first) return null;
        return ScreenTime.day(db.screen(edges[0], edges[edges.length - 1]), edges);
    }

    /** Minutes of screen time for the {@code n} days ending at {@code last}, oldest first (NaN when unknown). */
    public static double[] lastDays(Db db, LocalDate last, int n, ZoneId zone) {
        double from = last.minusDays(n - 1).atStartOfDay(zone).toEpochSecond();
        double to = last.plusDays(1).atStartOfDay(zone).toEpochSecond();
        List<double[]> s = db.screen(from, to);
        double first = db.screenStart(true);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDate d = last.minusDays(n - 1 - i);
            double a = d.atStartOfDay(zone).toEpochSecond(), b = d.plusDays(1).atStartOfDay(zone).toEpochSecond();
            // days before the first stored session are unknown, not zero
            out[i] = first < 0 || b <= first ? Double.NaN : ScreenTime.minutes(s, a, b);
        }
        return out;
    }

    /** True when any screen session is stored. */
    public static boolean any(Db db) {
        return db.screenStart(true) >= 0;
    }

    /** Steps of the clock dial (5 minutes each) and of a day row (15 minutes each). */
    public static final int DIAL_STEPS = 288, ROW_STEPS = 96;
    /** Days in the list on the Screen tab. */
    public static final int ROWS = 14;

    /** One day row of the list: what was on when, the nights in it, the first look after waking. */
    public static final class Row {
        public LocalDate date;
        public double from, to;
        /** Counted screen time, NaN before the first stored session (unknown, not zero). */
        public double totalMin = Double.NaN;
        public double[] fill = new double[ROW_STEPS];
        /** Main nights {sleep start, sleep end} that touch this day. */
        public final List<double[]> nights = new ArrayList<double[]>();
        public double firstLook = Double.NaN;
    }

    /** Everything the Screen tab and the Today ring show for one date. */
    public static final class Info {
        public LocalDate date;
        public double from, to;
        /** Counted screen time of the day (apps not counted are left out); null when nothing is known. */
        public ScreenTime.Day day;
        /** Screen on time with every app counted, and the part left out. */
        public double rawMin = Double.NaN, excludedMin;
        public ScreenScore.Result score;
        public int goalMin;
        /** The night that ended on this day: sleep start and end (NaN when none). */
        public double sleepStart = Double.NaN, sleepEnd = Double.NaN;
        public double bedMin = Double.NaN, nightMin = Double.NaN, firstLook = Double.NaN;
        public double[] dial = new double[DIAL_STEPS];
        /** Nights {start, end} that touch this day, for the dial. */
        public final List<double[]> nights = new ArrayList<double[]>();
        /** Minutes per app this day (screen on and app in front), most used first; empty when the split is unknown. */
        public List<Map.Entry<String, Double>> apps = Collections.emptyList();
        /** Whether app times are stored for this day (they start with version 1.15, about a week back). */
        public boolean appsKnown;
        public Row[] rows = new Row[0];
    }

    /** Builds the Screen tab for {@code date}: the day, its score and the {@link #ROWS} days ending there. */
    public static Info info(Db db, LocalDate date, ZoneId zone, int goalMin, Set<String> excluded) {
        Info in = new Info();
        in.date = date;
        in.goalMin = goalMin;
        in.from = date.atStartOfDay(zone).toEpochSecond();
        in.to = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        double first = db.screenStart(true);
        LocalDate firstRow = date.minusDays(ROWS - 1);
        double qFrom = firstRow.atStartOfDay(zone).toEpochSecond() - 2 * 3600, qTo = in.to;
        // main nights touching the rows, by the day they belong to
        List<double[]> nights = new ArrayList<double[]>();
        java.util.Map<String, double[]> byDay = new java.util.HashMap<String, double[]>();
        for (String js : db.nightsJson(firstRow.toString(), date.plusDays(1).toString(), true)) {
            JSONObject o = Json.parse(js);
            if (o == null) continue;
            double a = Json.d(o, "sleep_start"), b = Json.d(o, "sleep_end");
            if (Double.isNaN(a)) a = Json.d(o, "start");
            if (Double.isNaN(b)) b = Json.d(o, "end");
            if (Double.isNaN(a) || !(b > a)) continue;
            double[] n = {a, b};
            nights.add(n);
            byDay.put(o.optString("day"), n);
            qFrom = Math.min(qFrom, a - ScreenScore.BEDTIME_WINDOW_S);
        }
        List<double[]> raw = db.screen(qFrom, qTo);
        Map<String, List<double[]>> apps = db.apps(qFrom, qTo);
        List<double[]> counted = excluded.isEmpty() ? raw : ScreenTime.subtract(raw, ScreenTime.ranges(apps, excluded));
        double appsFrom = db.appsStart();

        // the day itself
        double[] night = byDay.get(date.toString());
        if (night != null) {
            in.sleepStart = night[0];
            in.sleepEnd = night[1];
        }
        for (double[] n : nights) if (n[1] > in.from && n[0] < in.to) in.nights.add(n);
        boolean known = first >= 0 && in.to > first;
        if (known) {
            in.day = ScreenTime.day(counted, hourEdges(date, zone));
            in.rawMin = ScreenTime.minutes(raw, in.from, in.to);
            in.excludedMin = Math.max(0, in.rawMin - in.day.totalMin);
            in.dial = ScreenTime.fill(counted, in.from, in.to, DIAL_STEPS);
            in.firstLook = ScreenTime.firstAfter(counted, night != null ? Math.max(in.from, night[1]) : in.from, in.to);
            if (night != null && night[0] - ScreenScore.BEDTIME_WINDOW_S >= first) {
                in.bedMin = ScreenTime.minutes(counted, night[0] - ScreenScore.BEDTIME_WINDOW_S, night[0]);
                in.nightMin = ScreenTime.minutes(counted, night[0], night[1]);
            }
            in.score = ScreenScore.of(in.day.totalMin, goalMin, in.bedMin, in.nightMin);
            in.appsKnown = appsFrom >= 0 && in.to > appsFrom;
            if (in.appsKnown) in.apps = ScreenTime.perApp(apps, raw, in.from, in.to);
        }

        // the list of days, oldest first
        in.rows = new Row[ROWS];
        for (int i = 0; i < ROWS; i++) {
            Row r = new Row();
            r.date = firstRow.plusDays(i);
            r.from = r.date.atStartOfDay(zone).toEpochSecond();
            r.to = r.date.plusDays(1).atStartOfDay(zone).toEpochSecond();
            for (double[] n : nights) if (n[1] > r.from && n[0] < r.to) r.nights.add(n);
            if (first >= 0 && r.to > first) {
                r.totalMin = ScreenTime.minutes(counted, r.from, r.to);
                r.fill = ScreenTime.fill(counted, r.from, r.to, ROW_STEPS);
                double[] n = byDay.get(r.date.toString());
                r.firstLook = ScreenTime.firstAfter(counted, n != null ? Math.max(r.from, n[1]) : r.from, r.to);
            }
            in.rows[i] = r;
        }
        return in;
    }

    /** Apps seen in front during the last {@code days} days, most used first (for the list of apps not to count). */
    public static List<Map.Entry<String, Double>> recentApps(Db db, int days) {
        double to = System.currentTimeMillis() / 1000.0, from = to - days * 86400.0;
        return ScreenTime.perApp(db.apps(from, to), db.screen(from, to), from, to);
    }
}
