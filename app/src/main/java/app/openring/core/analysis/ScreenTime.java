package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screen time of the phone (issue #13): screen sessions from Android's on and off log, totals per
 * day and hour, and how phone use corrects a sleep window. A session is {start, end} in unix
 * seconds. Pure Java so it runs in the JVM tests.
 */
public final class ScreenTime {
    private ScreenTime() {}

    /** Kinds of log entries, mapped from Android's usage events by the reader. */
    public static final int ON = 1, OFF = 2, SHUTDOWN = 3;
    /** The phone was in use (an app came to the front, the person unlocked or touched it): the screen was on. */
    public static final int ALIVE = 4;
    /** The phone started: a session still open ended before it (the phone died or restarted with the screen on). */
    public static final int STARTUP = 5;
    /** An app came to the front (with its package name). */
    public static final int APP_ON = 6;
    /** An app went to the back (with its package name). */
    public static final int APP_OFF = 7;
    /** A log that starts with "off" counts as on since the start of the read only when the "off" follows within this time. */
    public static final double LEAD_MAX_S = 2 * 3600;
    /** Longer sessions are most likely a missing "off" (or a video left playing) and are not used for sleep. */
    public static final double MAX_NIGHT_SESSION_S = 4 * 3600;

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
     * {@code now}. "Startup" closes a session left open at the last sign of use before it (the
     * phone died or restarted with the screen on). When the log starts with "off" within
     * {@link #LEAD_MAX_S} of {@code from}, the screen was on since {@code from}; a later first
     * "off" is ignored, because the log does not tell how long the screen had been on.
     */
    public static List<double[]> sessions(double[] times, int[] kinds, double from, double now) {
        List<double[]> out = new ArrayList<double[]>();
        double open = Double.NaN, lastUse = Double.NaN;
        boolean seen = false;
        for (int i = 0; i < times.length; i++) {
            int k = kinds[i];
            if (k == ALIVE) {
                if (!Double.isNaN(open)) lastUse = times[i];
                continue;
            }
            if (k == STARTUP) {
                if (!Double.isNaN(open) && !Double.isNaN(lastUse) && lastUse > open) out.add(new double[] {open, lastUse});
                open = Double.NaN;
                seen = true;
                continue;
            }
            if (k == ON) {
                if (Double.isNaN(open)) {
                    open = times[i];
                    lastUse = times[i];
                }
            } else if (k == OFF || k == SHUTDOWN) {
                if (Double.isNaN(open) && !seen && k == OFF && times[i] > from && times[i] - from <= LEAD_MAX_S) open = from;
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
     * another length). Returns how many steps had phone use (also those already awake); stages are
     * changed in place.
     */
    public static int markAwake(int[] stages, double start, double end, List<double[]> sessions) {
        if (stages.length == 0 || end <= start || sessions.isEmpty()) return 0;
        double step = (end - start) / stages.length;
        double need = AWAKE_MIN_S * step / 30.0;
        int changed = 0;
        for (int i = 0; i < stages.length; i++) {
            double a = start + i * step, b = a + step, on = 0;
            for (double[] x : sessions) on += overlap(x[0], x[1], a, b);
            if (on >= need) {
                stages[i] = 4;
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ apps

    /**
     * Time each app was in front, from log entries sorted by time: {@link #APP_ON} opens the app's
     * range, {@link #APP_OFF} closes it, a screen {@link #OFF}, {@link #SHUTDOWN} or {@link #STARTUP}
     * closes every open app. A second "on" of an open app is ignored (another screen of the same app).
     * An "off" with no "on" before it within {@link #LEAD_MAX_S} of {@code from} means the app was in
     * front since {@code from}. Ranges still open end at {@code now}. Package names may be null only
     * for screen entries.
     */
    public static Map<String, List<double[]>> appSessions(double[] times, int[] kinds, String[] pkgs, double from, double now) {
        Map<String, List<double[]>> out = new LinkedHashMap<String, List<double[]>>();
        Map<String, Double> open = new LinkedHashMap<String, Double>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < times.length; i++) {
            int k = kinds[i];
            double t = times[i];
            if (k == OFF || k == SHUTDOWN || k == STARTUP) {
                for (Map.Entry<String, Double> e : open.entrySet()) add(out, e.getKey(), e.getValue(), t);
                seen.addAll(open.keySet());
                open.clear();
                continue;
            }
            String p = pkgs[i];
            if (p == null) continue;
            if (k == APP_ON) {
                if (!open.containsKey(p)) open.put(p, t);
                seen.add(p);
            } else if (k == APP_OFF) {
                Double o = open.remove(p);
                if (o != null) add(out, p, o, t);
                else if (!seen.contains(p) && t > from && t - from <= LEAD_MAX_S) add(out, p, from, t);
                seen.add(p);
            }
        }
        for (Map.Entry<String, Double> e : open.entrySet()) add(out, e.getKey(), e.getValue(), now);
        return out;
    }

    private static void add(Map<String, List<double[]>> m, String p, double a, double b) {
        if (b <= a) return;
        List<double[]> l = m.get(p);
        if (l == null) m.put(p, l = new ArrayList<double[]>());
        l.add(new double[] {a, b});
    }

    /** The sessions with every part covered by one of {@code cuts} taken out; both lists may overlap within themselves. */
    public static List<double[]> subtract(List<double[]> sessions, List<double[]> cuts) {
        List<double[]> cs = new ArrayList<double[]>(cuts);
        Collections.sort(cs, BY_START);
        List<double[]> out = new ArrayList<double[]>();
        for (double[] s : sessions) {
            double a = s[0];
            for (double[] c : cs) {
                if (c[1] <= a) continue;
                if (c[0] >= s[1]) break;
                if (c[0] > a) out.add(new double[] {a, c[0]});
                a = Math.max(a, c[1]);
                if (a >= s[1]) break;
            }
            if (a < s[1]) out.add(new double[] {a, s[1]});
        }
        return out;
    }

    static final Comparator<double[]> BY_START = new Comparator<double[]>() {
        @Override
        public int compare(double[] x, double[] y) {
            return Double.compare(x[0], y[0]);
        }
    };

    /** All ranges of the given apps in one list (the apps not in {@code which} are left out). */
    public static List<double[]> ranges(Map<String, List<double[]>> apps, Set<String> which) {
        List<double[]> out = new ArrayList<double[]>();
        for (Map.Entry<String, List<double[]>> e : apps.entrySet()) if (which.contains(e.getKey())) out.addAll(e.getValue());
        return out;
    }

    /**
     * Minutes each app was in front while the screen was on within [from, to), most used first;
     * apps under half a minute are left out.
     */
    public static List<Map.Entry<String, Double>> perApp(Map<String, List<double[]>> apps, List<double[]> screen, double from, double to) {
        List<Map.Entry<String, Double>> out = new ArrayList<Map.Entry<String, Double>>();
        for (Map.Entry<String, List<double[]>> e : apps.entrySet()) {
            double s = 0;
            for (double[] a : e.getValue()) {
                double a0 = Math.max(a[0], from), a1 = Math.min(a[1], to);
                if (a1 <= a0) continue;
                for (double[] x : screen) s += overlap(x[0], x[1], a0, a1);
            }
            if (s >= 30) out.add(new java.util.AbstractMap.SimpleEntry<String, Double>(e.getKey(), s / 60.0));
        }
        Collections.sort(out, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> x, Map.Entry<String, Double> y) {
                int c = Double.compare(y.getValue(), x.getValue());
                return c != 0 ? c : x.getKey().compareTo(y.getKey());
            }
        });
        return out;
    }

    /** Start of the first session that starts at or after {@code t} and before {@code to}, or NaN. */
    public static double firstAfter(List<double[]> sessions, double t, double to) {
        double best = Double.NaN;
        for (double[] x : sessions) if (x[0] >= t && x[0] < to && (Double.isNaN(best) || x[0] < best)) best = x[0];
        return best;
    }

    /**
     * Fraction of each of {@code n} equal steps of [from, to) the screen was on (0 to 1), for the
     * clock dial and the day rows.
     */
    public static double[] fill(List<double[]> sessions, double from, double to, int n) {
        double[] out = new double[n];
        double step = (to - from) / n;
        if (step <= 0) return out;
        for (double[] x : sessions) {
            double a = Math.max(x[0], from), b = Math.min(x[1], to);
            if (b <= a) continue;
            int i0 = (int) Math.floor((a - from) / step), i1 = Math.min(n - 1, (int) Math.floor((b - from) / step));
            for (int i = Math.max(0, i0); i <= i1; i++) out[i] += overlap(a, b, from + i * step, from + (i + 1) * step) / step;
        }
        for (int i = 0; i < n; i++) out[i] = Math.min(1, out[i]);
        return out;
    }

    /**
     * A readable name from a package name when Android does not give one: the last part that is not
     * a common word, capitalised ("com.google.android.apps.maps" gives "Maps", "org.mozilla.firefox"
     * gives "Firefox").
     */
    public static String shortName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Unknown app";
        String[] parts = pkg.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            String w = parts[i];
            if (w.isEmpty() || i == 0 && parts.length > 1) continue;
            switch (w.toLowerCase(java.util.Locale.ROOT)) {
                case "android": case "app": case "apps": case "mobile": case "client": case "launcher3x": case "free": case "lite":
                    continue;
                default:
                    return Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
        }
        return pkg;
    }
}
