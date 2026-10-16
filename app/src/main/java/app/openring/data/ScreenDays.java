package app.openring.data;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.analysis.ScreenTime;

/** Screen time per day from the stored screen sessions, for the Activity page (issue #13). */
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
}
