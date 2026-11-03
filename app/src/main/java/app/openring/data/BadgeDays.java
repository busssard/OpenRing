package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.openring.core.analysis.Badges;
import app.openring.core.analysis.ClockChange;
import app.openring.core.analysis.LeftOut;
import app.openring.core.analysis.ScreenScore;
import app.openring.core.analysis.ScreenTime;

/**
 * Reads what the badges need for a range of dates (issue #265, phase 1b) with one query per table: one {@link Db#daysJson},
 * one {@link Db#nightsJson} of main nights, one read of the screen sessions and one of the apps in front, the read times of
 * the usage log, the time zones and the left out times. Never {@code ScreenDays.info} or a day load per date. Blocks; call
 * it from a background thread. Nothing is stored.
 *
 * <p>Screen minutes and the evening before a night are counted with the Screen tab's own code
 * ({@code ScreenDays.counted}, {@code span}, {@code nightRead}, {@code bedMin}), each day in the zone the Screen tab
 * shows it in (its main night's, else the zone noted then, else the phone's), so a badge says what the Screen tab says
 * (pipeline test, every demo day). To stay linear over years of sessions, each day only takes the sessions and the
 * left out app times that reach its window; the minutes are the same, since a cut outside the window only splits a
 * session outside it.
 */
public final class BadgeDays {
    private BadgeDays() {}

    /**
     * The badges of the dates from {@code from} to {@code to}, exactly the ones the whole history gives those dates (the
     * Badges page): it reads {@link Badges#REACH} days before {@code from}, and further back as long as
     * {@link Badges#exactFrom} says an "at most once in 7 days" chain or the 28 worn days of "Above your usual" reach
     * further ({@link Badges#recent}). {@code ownTarget}: the profile's activity target (0 without), {@code excluded}:
     * the apps not counted in screen time.
     */
    public static List<Badges.Badge> badges(final Db db, LocalDate from, final LocalDate to, final int ownTarget, final Set<String> excluded,
            final LocalDate today, final double now) {
        final ZoneId phone = ZoneId.systemDefault();
        return Badges.recent(new Badges.Reader() {
            @Override
            public List<Badges.Day> from(LocalDate start) {
                return read(db, start, to, ownTarget, excluded, today, now, phone);
            }
        }, from, to, first(db, today), today);
    }

    /** The Badges page's history: every badge, and how many nights with a sleep score there are. */
    public static final class History {
        public final List<Badges.Badge> badges;
        public final int nights;

        History(List<Badges.Badge> badges, int nights) {
            this.badges = badges;
            this.nights = nights;
        }
    }

    /** Every badge of the whole history (the Badges page), from the first stored day or the first read of the usage log. */
    public static History all(Db db, int ownTarget, Set<String> excluded, LocalDate today, double now) {
        List<Badges.Day> days = read(db, first(db, today), today, ownTarget, excluded, today, now, ZoneId.systemDefault());
        return new History(Badges.of(days, today), nights(days));
    }

    /** The first date with a day summary or a read of the usage log (in the phone's zone); {@code today} without any. */
    static LocalDate first(Db db, LocalDate today) {
        LocalDate f = today;
        String d = db.firstDay();
        if (d != null) f = min(f, parse(d));
        List<double[]> reads = ScreenDays.reads(db);
        if (!reads.isEmpty() && reads.get(0)[0] > 0) {
            LocalDate r = java.time.Instant.ofEpochSecond((long) Math.floor(reads.get(0)[0])).atZone(ZoneId.systemDefault()).toLocalDate();
            f = min(f, r);
        }
        // never more than the badges can use (Badges.MAX_SPAN), however old a stray row is
        return f.isBefore(today.minusDays(Badges.MAX_SPAN)) ? today.minusDays(Badges.MAX_SPAN) : f;
    }

    /** Dates with a sleep score: how many nights the comparisons have. */
    public static int nights(List<Badges.Day> days) {
        int n = 0;
        for (Badges.Day d : days) if (!Double.isNaN(d.sleepScore) && !d.leftOut) n++;
        return n;
    }

    /**
     * One {@link Badges.Day} per date from {@code from} to {@code to}, every date (empty ones stay NaN). {@code phone}: the
     * phone's zone, for days without a night or a noted zone.
     */
    public static List<Badges.Day> read(Db db, LocalDate from, LocalDate to, int ownTarget, Set<String> excluded, LocalDate today,
            double now, ZoneId phone) {
        List<Badges.Day> out = new ArrayList<Badges.Day>();
        if (to.isBefore(from)) return out;
        Map<String, JSONObject> days = new HashMap<String, JSONObject>();
        for (String[] r : db.daysJson(from.toString(), to.toString())) {
            JSONObject o = Json.parse(r[1]);
            if (o != null) days.put(r[0], o);
        }
        // the main night of each day, the newest one when a day has two, as DayData and the Screen tab take it
        Map<String, JSONObject> nights = new HashMap<String, JSONObject>();
        for (String js : db.nightsJson(from.toString(), to.toString(), true)) {
            JSONObject o = Json.parse(js);
            if (o != null) nights.put(o.optString("day"), o);
        }
        List<Object[]> zones = db.zones();
        LeftOut left = db.leftOut();
        List<double[]> reads = ScreenDays.reads(db);
        double qFrom = from.minusDays(1).atStartOfDay(phone).toEpochSecond() - 2 * 86400, qTo = to.plusDays(1).atStartOfDay(phone).toEpochSecond() + 2 * 86400;
        boolean screenKnown = !reads.isEmpty();
        Window sessions = null, cuts = null;
        if (screenKnown) {
            sessions = new Window(db.screen(qFrom, qTo));
            if (!excluded.isEmpty()) cuts = new Window(ScreenTime.ranges(db.apps(qFrom, qTo), excluded));
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String key = d.toString();
            Badges.Day b = new Badges.Day(d);
            JSONObject day = days.get(key), night = nights.get(key);
            ZoneId noted = ClockChange.zone(Db.zoneIn(zones, d.atTime(12, 0).atZone(phone).toEpochSecond()));
            ZoneId zone = Json.zone(night, noted == null ? phone : noted);
            double[] edges = ScreenDays.hourEdges(d, zone);
            double ds = edges[0], de = edges[edges.length - 1];
            if (day != null) {
                JSONObject rd = day.optJSONObject("readiness");
                if (rd != null && rd.has("value")) b.readiness = rd.optDouble("value");
                if (day.has("sleep_score")) b.sleepScore = day.optDouble("sleep_score");
                b.steps = Json.d(day, "steps");
                b.coveredMin = Json.d(day, "covered_min");
                b.restMin = Json.d(day, "rest_min");
                b.activeKcal = Json.d(day, "active_kcal");
                b.targetKcal = Json.d(day, "target_kcal");
                b.goalLowered = Badges.goalLowered(ownTarget, b.targetKcal, b.readiness);
            }
            // a day analysed while it ran keeps its numbers "so far" (total_until) until it is analysed again
            b.running = !d.isBefore(today) || day != null && day.has("total_until");
            b.leftOut = !left.isEmpty() && left.seconds(ds, de) > 0;
            if (screenKnown && ScreenTime.coverage(reads, ds, de, now) == ScreenTime.COVERED) {
                double[] span = ScreenDays.span(night);
                double a = span == null ? ds : Math.min(ds, span[0] - ScreenScore.BEDTIME_WINDOW_S);
                List<double[]> raw = sessions.reaching(a, de);
                List<double[]> counted = ScreenDays.counted(raw, cuts == null ? null : cuts.reaching(a, de));
                b.screenMin = ScreenTime.minutes(counted, ds, de);
                if (ScreenDays.nightRead(reads, span, now)) b.eveningMin = ScreenDays.bedMin(counted, span);
            }
            out.add(b);
        }
        return out;
    }

    /**
     * Ranges sorted by start, so the ranges reaching a window are found without a full scan: the usual ones (up to 2 days)
     * by a binary search back by the longest of them, the few longer ones (a session or a left out app time running for
     * days) from their own list, so one range of weeks does not make every day scan weeks of ranges.
     */
    static final class Window {
        static final double LONG_S = 2 * 86400;
        final double[][] r;
        final double longest;
        final int[] longOnes;

        Window(List<double[]> ranges) {
            r = ranges.toArray(new double[0][]);
            // stable, so ranges with the same start keep the order the Screen tab sees them in
            Arrays.sort(r, new Comparator<double[]>() {
                @Override
                public int compare(double[] x, double[] y) {
                    return Double.compare(x[0], y[0]);
                }
            });
            double l = 0;
            List<Integer> lo = new ArrayList<Integer>();
            for (int i = 0; i < r.length; i++) {
                double len = r[i][1] - r[i][0];
                if (len > LONG_S) lo.add(i);
                else l = Math.max(l, len);
            }
            longest = l;
            longOnes = new int[lo.size()];
            for (int i = 0; i < longOnes.length; i++) longOnes[i] = lo.get(i);
        }

        /** The ranges that overlap [a, b), in start order. */
        List<double[]> reaching(double a, double b) {
            if (r.length == 0) return Collections.emptyList();
            int lo = 0, hi = r.length;
            double from = a - longest;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (r[mid][0] < from) lo = mid + 1;
                else hi = mid;
            }
            List<double[]> out = new ArrayList<double[]>();
            // long ranges that start before the search point come first: their place in start order is before it
            for (int i : longOnes) if (i < lo && r[i][1] > a) out.add(r[i]);
            for (int i = lo; i < r.length && r[i][0] < b; i++) if (r[i][1] > a) out.add(r[i]);
            return out;
        }
    }

    static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return LocalDate.MAX;
        }
    }

    static LocalDate min(LocalDate a, LocalDate b) {
        return b.isBefore(a) ? b : a;
    }
}
