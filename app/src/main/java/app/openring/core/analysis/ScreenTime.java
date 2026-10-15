package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen time of the phone (issue #13): screen sessions from Android's on and off log, totals per
 * day and hour, and how phone use corrects a sleep window. A session is {start, end} in unix
 * seconds. Pure Java so it runs in the JVM tests.
 */
public final class ScreenTime {
    private ScreenTime() {}

    /** Kinds of log entries, mapped from Android's usage events by the reader. */
    public static final int ON = 1, OFF = 2, SHUTDOWN = 3;

    /** Phone use that overlaps the first or last 10 minutes of a sleep window moves its edge. */
    public static final double EDGE_S = 10 * 60;
    /** Only screen sessions of at least a minute move the edges of a sleep window. */
    public static final double EDGE_MIN_SESSION_S = 60;
    /** A 30 second step counts as awake when the screen was on for at least this long in it. */
    public static final double AWAKE_MIN_S = 15;
    /** A sleep window shorter than this after trimming is dropped (same limit as NightFinder). */
    public static final double MIN_WINDOW_S = 15 * 60;

    /**
     * Sessions from log entries sorted by time. A second "on" while on is ignored (the screen was
     * already on), "off" or "shutdown" closes the session, a session still open ends at
     * {@code now}. When the log starts with "off", the screen was on since {@code from}.
     */
    public static List<double[]> sessions(double[] times, int[] kinds, double from, double now) {
        List<double[]> out = new ArrayList<double[]>();
        double open = Double.NaN;
        boolean seen = false;
        for (int i = 0; i < times.length; i++) {
            int k = kinds[i];
            if (k == ON) {
                if (Double.isNaN(open)) open = times[i];
            } else if (k == OFF || k == SHUTDOWN) {
                if (Double.isNaN(open) && !seen && k == OFF && times[i] > from) open = from;
                if (!Double.isNaN(open)) {
                    if (times[i] > open) out.add(new double[] {open, times[i]});
                    open = Double.NaN;
                }
            } else {
                continue;
            }
            seen = true;
        }
        if (!Double.isNaN(open) && now > open) out.add(new double[] {open, now});
        return out;
    }

    /** Seconds two ranges overlap. */
    static double overlap(double a0, double a1, double b0, double b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
    }

    /** Minutes the screen was on within [from, to). */
    public static double minutes(List<double[]> sessions, double from, double to) {
        double s = 0;
        for (double[] x : sessions) s += overlap(x[0], x[1], from, to);
        return s / 60.0;
    }

    /** Minutes on per bin; {@code edges} holds n + 1 increasing bin borders (local hours of a day). */
    public static double[] perBin(List<double[]> sessions, double[] edges) {
        double[] out = new double[Math.max(0, edges.length - 1)];
        for (int i = 0; i < out.length; i++) out[i] = minutes(sessions, edges[i], edges[i + 1]);
        return out;
    }

    /** How often the screen was turned on within [from, to). */
    public static int turnedOn(List<double[]> sessions, double from, double to) {
        int n = 0;
        for (double[] x : sessions) if (x[0] >= from && x[0] < to) n++;
        return n;
    }

    /** Longest time on in one go within [from, to), in minutes (a session is cut at the borders). */
    public static double longest(List<double[]> sessions, double from, double to) {
        double m = 0;
        for (double[] x : sessions) m = Math.max(m, overlap(x[0], x[1], from, to));
        return m / 60.0;
    }

    /** One day of screen use for the Activity page. */
    public static final class Day {
        public double totalMin, longestMin;
        public int turnedOn;
        public double[] hourly = new double[0];
    }

    /** Totals of the day whose local hours start at {@code hourEdges} (first = midnight, last = next midnight). */
    public static Day day(List<double[]> sessions, double[] hourEdges) {
        Day d = new Day();
        double from = hourEdges[0], to = hourEdges[hourEdges.length - 1];
        d.totalMin = minutes(sessions, from, to);
        d.turnedOn = turnedOn(sessions, from, to);
        d.longestMin = longest(sessions, from, to);
        d.hourly = perBin(sessions, hourEdges);
        return d;
    }

    /**
     * Moves the edges of a sleep window past phone use: a session of a minute or more that overlaps
     * the first (last) {@link #EDGE_S} seconds pushes the start to its end (the end to its start),
     * repeated while more phone use follows. Returns {start, end}; end - start can become small or
     * negative when the phone was in use the whole time.
     */
    public static double[] trim(double start, double end, List<double[]> sessions) {
        boolean moved = true;
        while (moved && end > start) {
            moved = false;
            for (double[] x : sessions) {
                if (x[1] - x[0] < EDGE_MIN_SESSION_S) continue;
                if (x[1] > start && x[0] < start + EDGE_S && x[0] < end) {
                    start = x[1];
                    moved = true;
                }
                if (x[0] < end && x[1] > end - EDGE_S && x[1] > start) {
                    end = x[0];
                    moved = true;
                }
            }
        }
        return new double[] {start, end};
    }

    /**
     * Marks every step of {@code stages} (equal steps over [start, end)) as awake (4) when the
     * screen was on for at least {@link #AWAKE_MIN_S} seconds of a 30 second step (half of a step of
     * another length). Returns how many steps were changed; stages are changed in place.
     */
    public static int markAwake(int[] stages, double start, double end, List<double[]> sessions) {
        if (stages.length == 0 || end <= start || sessions.isEmpty()) return 0;
        double step = (end - start) / stages.length;
        double need = AWAKE_MIN_S * step / 30.0;
        int changed = 0;
        for (int i = 0; i < stages.length; i++) {
            double a = start + i * step, b = a + step, on = 0;
            for (double[] x : sessions) on += overlap(x[0], x[1], a, b);
            if (on >= need && stages[i] != 4) {
                stages[i] = 4;
                changed++;
            }
        }
        return changed;
    }
}
