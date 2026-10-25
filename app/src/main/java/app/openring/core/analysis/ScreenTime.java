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

    /** The lock screen appeared (Android's "keyguard shown"). */
    public static final int LOCKED = 8;
    /** The person unlocked the phone ("keyguard hidden"). */
    public static final int UNLOCKED = 9;

    /** A session that the lock screen ends within this time of its start was only a glance at the lock screen. */
    public static final double GLANCE_S = 5;

    /** As below; the old overload with a lock screen flag gives the same result (the log itself tells). */
    public static List<double[]> sessions(double[] times, int[] kinds, double from, double now, boolean ignored) {
        return sessions(times, kinds, from, now);
    }

    /**
     * Phone use sessions from log entries sorted by time. Use is counted while the screen is on and the
     * phone is unlocked, so the lock screen lighting up for a message, a look at the clock or a screen
     * saver behind the lock screen do not count (close to the ScreenTime app, which counts from
     * unlocking). Whether the phone is locked comes only from the lock and unlock entries: a phone
     * without a lock screen never logs them and counts from screen on, and turning the screen back on
     * within the lock delay counts too. A session the lock screen ends within {@link #GLANCE_S} of
     * its start was the lock screen showing up and is dropped. An unlock while the screen is off (face
     * unlock can come first) only marks the phone unlocked, the session starts with the screen.
     * "Shutdown" closes a session; "startup" closes one left open at the last sign of use before it.
     * A session still open ends at {@code now}. When the log starts with "off" or "locked" within
     * {@link #LEAD_MAX_S} of {@code from}, the phone was in use since {@code from}.
     */
    public static List<double[]> sessions(double[] times, int[] kinds, double from, double now) {
        List<double[]> out = new ArrayList<double[]>();
        double open = Double.NaN, lastUse = Double.NaN;
        boolean seen = false, locked = false, screenOn = false;
        for (int i = 0; i < times.length; i++) {
            int k = kinds[i];
            double t = times[i];
            if (k == ALIVE) {
                if (!Double.isNaN(open)) lastUse = t;
                continue;
            }
            if (k == STARTUP) {
                if (!Double.isNaN(open) && !Double.isNaN(lastUse) && lastUse > open) out.add(new double[] {open, lastUse});
                open = Double.NaN;
                screenOn = false;
                seen = true;
                continue;
            }
            if (k == ON) {
                screenOn = true;
                if (!locked && Double.isNaN(open)) {
                    open = t;
                    lastUse = t;
                }
            } else if (k == UNLOCKED) {
                locked = false;
                if (!seen) screenOn = true; // the first entry of a read: the phone is being used
                if (screenOn && Double.isNaN(open)) {
                    open = t;
                    lastUse = t;
                }
            } else if (k == OFF || k == SHUTDOWN || k == LOCKED) {
                if (Double.isNaN(open) && !seen && k != SHUTDOWN && t > from && t - from <= LEAD_MAX_S) open = from;
                if (!Double.isNaN(open)) {
                    boolean glance = k == LOCKED && t - open < GLANCE_S && open > from;
                    if (t > open && !glance) out.add(new double[] {open, t});
                    open = Double.NaN;
                }
                if (k == LOCKED) locked = true;
                else screenOn = false;
            } else {
                continue;
            }
            seen = true;
        }
        if (!Double.isNaN(open) && now > open) out.add(new double[] {open, now});
        return out;
    }

    /**
     * Each session made at least {@code minS} seconds long, for the clock and the day rows only (a
     * short unlock otherwise shows as a faint flicker). Totals never use this.
     */
    public static List<double[]> widen(List<double[]> sessions, double minS) {
        if (minS <= 0) return sessions;
        List<double[]> out = new ArrayList<double[]>();
        for (double[] x : sessions) out.add(new double[] {x[0], Math.max(x[1], x[0] + minS)});
        return out;
    }

    /** The session that contains {@code t}, or null. */
    public static double[] at(List<double[]> sessions, double t) {
        for (double[] x : sessions) if (t >= x[0] && t < x[1]) return x;
        return null;
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
     * Version of the rule in {@link #markAwake}; nights marked with another version are analysed
     * again (2: a short check across a step border counts, issue #41).
     */
    public static final int AWAKE_RULE = 2;

    /**
     * The phone use a sleep window [start, end) counts as awake: the sessions that overlap it, without
     * those longer than {@link #MAX_NIGHT_SESSION_S}. The analysis and the Settings preview both choose
     * with this, so they mark the same steps (issue #30).
     */
    public static List<double[]> forNight(List<double[]> sessions, double start, double end) {
        List<double[]> out = new ArrayList<double[]>();
        for (double[] s : sessions) {
            if (s[1] - s[0] > MAX_NIGHT_SESSION_S) continue;
            if (s[1] > start && s[0] < end) out.add(s);
        }
        return out;
    }

    /**
     * Marks steps of {@code stages} (equal steps over [start, end)) as awake (4) where the phone was
     * in use. A step is awake when the screen was on for at least {@link #AWAKE_MIN_S} seconds of
     * it (all sessions added up; half of a step of another length). A session of at least that long
     * inside the window that marked no step this way (a short check split by a step border, issue
     * #41) marks the step holding most of it, the earlier one on a tie. Returns how many steps had
     * phone use (also those already awake); stages are changed in place.
     */
    public static int markAwake(int[] stages, double start, double end, List<double[]> sessions) {
        if (stages.length == 0 || end <= start || sessions.isEmpty()) return 0;
        int n = stages.length;
        double step = (end - start) / n;
        double need = AWAKE_MIN_S * step / 30.0;
        boolean[] phone = new boolean[n];
        for (int i = 0; i < n; i++) {
            double a = start + i * step, b = a + step, on = 0;
            for (double[] x : sessions) on += overlap(x[0], x[1], a, b);
            phone[i] = on >= need;
        }
        for (double[] x : sessions) {
            double a = Math.max(x[0], start), b = Math.min(x[1], end);
            if (b - a < need) continue;
            // the steps the session touches (one more on each side against rounding at a border)
            int i0 = Math.max(0, (int) Math.floor((a - start) / step) - 1);
            int i1 = Math.min(n - 1, (int) Math.floor((b - start) / step) + 1);
            int best = -1;
            double most = 0;
            boolean marked = false;
            for (int i = i0; i <= i1; i++) {
                double o = overlap(a, b, start + i * step, start + (i + 1) * step);
                if (o <= 0) continue;
                marked |= phone[i];
                if (o > most + 1e-9) {
                    most = o;
                    best = i;
                }
            }
            if (!marked && best >= 0) phone[best] = true;
        }
        int changed = 0;
        for (int i = 0; i < n; i++) {
            if (!phone[i]) continue;
            stages[i] = 4;
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------ apps

    /**
     * Time each app was in front, from log entries sorted by time: {@link #APP_ON} opens the app's
     * range, {@link #APP_OFF} closes it, a screen {@link #OFF}, {@link #SHUTDOWN} or {@link #STARTUP}
     * closes every open app. A second "on" of an open app is ignored (another screen of the same app).
     * When the very first app entry of the read is an "off" within {@link #LEAD_MAX_S} of
     * {@code from}, that app was in front since {@code from}; a later "off" without its "on" is
     * ignored (another app or the screen going off already ended that app, issue #71). Another app
     * coming to the front closes the others (one app is in front at a time). Ranges still open end
     * at {@code now}. Package names may be null only for screen entries.
     */
    public static Map<String, List<double[]>> appSessions(double[] times, int[] kinds, String[] pkgs, double from, double now) {
        Map<String, List<double[]>> out = new LinkedHashMap<String, List<double[]>>();
        Map<String, Double> open = new LinkedHashMap<String, Double>();
        boolean first = true;
        for (int i = 0; i < times.length; i++) {
            int k = kinds[i];
            double t = times[i];
            if (k == OFF || k == SHUTDOWN || k == STARTUP) {
                for (Map.Entry<String, Double> e : open.entrySet()) add(out, e.getKey(), e.getValue(), t);
                open.clear();
                first = false;
                continue;
            }
            String p = pkgs[i];
            if (p == null) continue;
            if (k == APP_ON) {
                // only one app is in front: another one coming to the front sends the others back,
                // also when the phone did not log their "went to the back"
                java.util.Iterator<Map.Entry<String, Double>> it = open.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Double> e = it.next();
                    if (e.getKey().equals(p)) continue;
                    add(out, e.getKey(), e.getValue(), t);
                    it.remove();
                }
                if (!open.containsKey(p)) open.put(p, t);
                first = false;
            } else if (k == APP_OFF) {
                Double o = open.remove(p);
                if (o != null) add(out, p, o, t);
                else if (first && t > from && t - from <= LEAD_MAX_S) add(out, p, from, t);
                first = false;
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

    // ------------------------------------------------- reading the log again (issue #71)

    /**
     * Where the app part of a read of the usage log starts: {@code back} on the first read, else the
     * start of the newest stored app range (never before {@code back}). The app still in front at
     * the last read is the one that came to the front last, so the read sees it come to the front
     * again and gives its whole range; reading time already stored again changes nothing because
     * ranges are merged when stored ({@link #store}). Without any stored range: {@code screenFrom}.
     * Never after {@code now}.
     */
    public static double appsReadFrom(boolean firstRead, double back, double screenFrom, double newestAppStart, double now) {
        if (firstRead) return Math.min(now, back);
        if (newestAppStart < 0) return Math.min(now, screenFrom);
        // a stored start in the future (the phone's clock was set back) must not stop reading the apps
        return Math.min(now, Math.max(back, newestAppStart));
    }

    /** The ranges sorted, with ranges that overlap or touch joined into one. */
    public static List<double[]> mergeRanges(List<double[]> ranges) {
        List<double[]> s = new ArrayList<double[]>();
        for (double[] r : ranges) if (r[1] > r[0]) s.add(new double[] {r[0], r[1]});
        Collections.sort(s, BY_START);
        List<double[]> out = new ArrayList<double[]>();
        for (double[] r : s) {
            double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
            else out.add(r);
        }
        return out;
    }

    /** Android's usage log surely still holds this much time back (it keeps about a week, issue #131). */
    public static final double LOG_SURE_S = 7 * 86400;

    /**
     * Where a read of the usage log asked to start at {@code from} really covers from (issue #131).
     * Within {@link #LOG_SURE_S} of {@code now} Android still has everything, so from {@code from}.
     * Earlier, only as far back as Android returned anything: its oldest event ({@code oldest}, NaN
     * when none came), or {@link #LOG_SURE_S} back when that is earlier, never before {@code from}.
     */
    public static double readStart(double from, double oldest, double now) {
        double sure = now - LOG_SURE_S;
        if (from >= sure) return from;
        double r = Double.isNaN(oldest) ? sure : Math.min(oldest, sure);
        return Math.max(from, r);
    }

    /** How much of a day the reads of the usage log cover, see {@link #coverage}. */
    public static final int COVERED = 0, BEFORE_READS = 1, READ_GAP = 2, AFTER_READS = 3;

    /**
     * Whether the time from {@code a} to {@code b} was read from the usage log (issue #131), given the
     * {@code reads} merged and oldest first ({@link #mergeRanges}). {@link #COVERED} when every moment
     * from the first read start (or {@code a}) to the last read end (or {@code b}) was read, so the first
     * day and today count up to where reading starts or stops; {@link #BEFORE_READS} when it ends at or
     * before the first read (or there is none), {@link #AFTER_READS} when it starts at or after the
     * last read end, {@link #READ_GAP} when time between two reads was never read. A day that is over at
     * {@code now} and was read only part way at its end is {@link #AFTER_READS} too (usage access
     * switched off in the middle of it); only today counts up to the last read.
     */
    public static int coverage(List<double[]> reads, double a, double b, double now) {
        if (reads.isEmpty() || b <= reads.get(0)[0]) return BEFORE_READS;
        double lastEnd = reads.get(reads.size() - 1)[1];
        if (a >= lastEnd || b <= now && b > lastEnd) return AFTER_READS;
        double t = Math.max(a, reads.get(0)[0]), end = Math.min(b, reads.get(reads.size() - 1)[1]);
        for (double[] r : reads) {
            if (t >= end) break;
            if (r[1] <= t) continue;
            if (r[0] > t) return READ_GAP;
            t = r[1];
        }
        return t >= end ? COVERED : READ_GAP;
    }

    /** Each app's ranges merged ({@link #mergeRanges}), so no moment counts twice for one app. */
    public static Map<String, List<double[]>> merge(Map<String, List<double[]>> apps) {
        Map<String, List<double[]>> out = new LinkedHashMap<String, List<double[]>>();
        for (Map.Entry<String, List<double[]>> e : apps.entrySet()) {
            List<double[]> m = mergeRanges(e.getValue());
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out;
    }

    /**
     * Adds the ranges of a read to the stored ones the way the database does: per app, a new range
     * joins every stored range it overlaps or touches. Changes and returns {@code stored}.
     */
    public static Map<String, List<double[]>> store(Map<String, List<double[]>> stored, Map<String, List<double[]>> add) {
        for (Map.Entry<String, List<double[]>> e : add.entrySet()) {
            List<double[]> all = new ArrayList<double[]>();
            List<double[]> old = stored.get(e.getKey());
            if (old != null) all.addAll(old);
            all.addAll(e.getValue());
            List<double[]> m = mergeRanges(all);
            if (!m.isEmpty()) stored.put(e.getKey(), m);
        }
        return stored;
    }

    /**
     * One app in front at a time: where ranges of different apps overlap (old stored rows, issue #71),
     * the app that came to the front later holds that time; the other keeps the rest of its range.
     * Each app's ranges come out merged and sorted. For the list of apps and the touch readout.
     */
    public static Map<String, List<double[]>> oneAtATime(Map<String, List<double[]>> apps) {
        Map<String, List<double[]>> merged = merge(apps);
        final List<Object[]> all = new ArrayList<Object[]>();
        java.util.TreeSet<Double> cuts = new java.util.TreeSet<Double>();
        for (Map.Entry<String, List<double[]>> e : merged.entrySet()) {
            for (double[] r : e.getValue()) {
                all.add(new Object[] {e.getKey(), r});
                cuts.add(r[0]);
                cuts.add(r[1]);
            }
        }
        Map<String, List<double[]>> out = new LinkedHashMap<String, List<double[]>>();
        for (String p : merged.keySet()) out.put(p, new ArrayList<double[]>());
        Collections.sort(all, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return Double.compare(((double[]) x[1])[0], ((double[]) y[1])[0]);
            }
        });
        // walk the pieces between all range edges; the covering range that started last holds each piece
        Double prev = null;
        int startIdx = 0;
        List<Object[]> active = new ArrayList<Object[]>();
        for (Double t : cuts) {
            if (prev != null) {
                Object[] best = null;
                for (Object[] a : active) {
                    double[] r = (double[]) a[1];
                    if (r[0] <= prev && r[1] >= t && (best == null || r[0] > ((double[]) best[1])[0]
                            || r[0] == ((double[]) best[1])[0] && ((String) a[0]).compareTo((String) best[0]) < 0)) best = a;
                }
                if (best != null) {
                    List<double[]> l = out.get((String) best[0]);
                    double[] last = l.isEmpty() ? null : l.get(l.size() - 1);
                    if (last != null && last[1] == prev) last[1] = t;
                    else l.add(new double[] {prev, t});
                }
            }
            // ranges that ended are dropped, ranges starting here join
            for (java.util.Iterator<Object[]> it = active.iterator(); it.hasNext();) if (((double[]) it.next()[1])[1] <= t) it.remove();
            while (startIdx < all.size() && ((double[]) all.get(startIdx)[1])[0] <= t) active.add(all.get(startIdx++));
            prev = t;
        }
        Map<String, List<double[]>> clean = new LinkedHashMap<String, List<double[]>>();
        for (Map.Entry<String, List<double[]>> e : out.entrySet()) if (!e.getValue().isEmpty()) clean.put(e.getKey(), e.getValue());
        return clean;
    }

    /**
     * Minutes each app was in front while the screen was on within [from, to), most used first;
     * apps under half a minute are left out (the list of apps to leave out).
     */
    public static List<Map.Entry<String, Double>> perApp(Map<String, List<double[]>> apps, List<double[]> screen, double from, double to) {
        return perApp(apps, screen, from, to, 30);
    }

    /**
     * As above with apps under {@code minS} seconds left out; with 0 every app with any time on
     * screen is in. An app's ranges are merged first, so it never counts a moment twice (issue #71).
     */
    public static List<Map.Entry<String, Double>> perApp(Map<String, List<double[]>> apps, List<double[]> screen, double from, double to, double minS) {
        List<Map.Entry<String, Double>> out = new ArrayList<Map.Entry<String, Double>>();
        List<double[]> scr = mergeRanges(screen);
        for (Map.Entry<String, List<double[]>> e : apps.entrySet()) {
            double s = 0;
            for (double[] a : mergeRanges(e.getValue())) {
                double a0 = Math.max(a[0], from), a1 = Math.min(a[1], to);
                if (a1 <= a0) continue;
                for (double[] x : scr) s += overlap(x[0], x[1], a0, a1);
            }
            if (s > 0 && s >= minS) out.add(new java.util.AbstractMap.SimpleEntry<String, Double>(e.getKey(), s / 60.0));
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

    /** Apps with a row of their own in the list (the most used ones counted; apps left out always get one). */
    public static final int LIST_APPS = 8;
    /** An app counted needs at least this much time that day for a row of its own; less goes to "Other apps". */
    public static final double LIST_MIN_S = 30;

    /** One row of the list of apps on the Screen tab. */
    public static final class AppRow {
        public static final int APP = 0, OTHER = 1, NO_APP = 2;
        public final int kind;
        /** Package name for {@link #APP}, null otherwise. */
        public final String pkg;
        /** Left out of screen time by the person. */
        public final boolean excluded;
        /** How many apps the "Other apps" row holds (1 for an app, 0 for no app in front). */
        public final int apps;
        /** Exact minutes (scaled down to the total in the rare case described at {@link ScreenTime#appRows}). */
        public final double exactMin;
        /** Whole minutes shown, rounded together with the other rows of its group so they add up. */
        public long shownMin;

        AppRow(int kind, String pkg, boolean excluded, int apps, double exactMin) {
            this.kind = kind;
            this.pkg = pkg;
            this.excluded = excluded;
            this.apps = apps;
            this.exactMin = exactMin;
        }
    }

    /**
     * The rows of the list of apps (issue #71): up to {@link #LIST_APPS} most used apps counted with
     * {@link #LIST_MIN_S} or more, then every app left out (always shown), then "Other apps" (the rest
     * together) and "No app in front" ({@code countedMin} minus all apps counted). Minutes are rounded
     * per group (largest remainder): the counted rows add up to the rounded {@code countedMin}, the
     * rows left out to their rounded sum. A counted app whose row would round to 0m joins "Other
     * apps"; "Other apps" and "No app in front" are dropped when they round to 0 (an app left out
     * keeps its row). When old overlapping rows make the apps counted more than {@code countedMin},
     * their minutes are scaled down to it. {@code perApp} holds every app's exact minutes, most used first.
     */
    public static List<AppRow> appRows(List<Map.Entry<String, Double>> perApp, Set<String> excluded, double countedMin) {
        return appRows(perApp, excluded, countedMin, Double.NaN);
    }

    /**
     * As above; when the rows left out add up to {@code excludedMin} (the "Not counted" number, always
     * so unless old stored rows overlap), they are rounded to exactly its rounded value.
     */
    public static List<AppRow> appRows(List<Map.Entry<String, Double>> perApp, Set<String> excluded, double countedMin, double excludedMin) {
        List<AppRow> out = new ArrayList<AppRow>();
        List<Map.Entry<String, Double>> mine = new ArrayList<Map.Entry<String, Double>>();
        double appsCounted = 0;
        for (Map.Entry<String, Double> e : perApp) {
            if (excluded.contains(e.getKey())) out.add(new AppRow(AppRow.APP, e.getKey(), true, 1, e.getValue()));
            else {
                mine.add(e);
                appsCounted += e.getValue();
            }
        }
        boolean known = !Double.isNaN(countedMin);
        // old overlapping rows of a counted and a left out app can make the apps counted more than the
        // total: they are scaled down to it, so the rows never add up to more than the total
        double cap = known && appsCounted > countedMin ? Math.max(0, countedMin) / appsCounted : 1;
        double none = known ? Math.max(0, countedMin - appsCounted) : 0;
        // the rows counted add up to the very number shown as the day's total
        long target = known ? Math.round(countedMin) : -1;
        java.util.HashSet<String> small = new java.util.HashSet<String>();
        List<AppRow> counted;
        while (true) {
            counted = new ArrayList<AppRow>();
            double other = 0;
            int others = 0, own = 0;
            for (Map.Entry<String, Double> e : mine) {
                double v = e.getValue() * cap;
                if (own < LIST_APPS && !small.contains(e.getKey()) && e.getValue() * 60 >= LIST_MIN_S - 1e-9) {
                    counted.add(new AppRow(AppRow.APP, e.getKey(), false, 1, v));
                    own++;
                } else {
                    other += v;
                    others++;
                }
            }
            if (others > 0) counted.add(new AppRow(AppRow.OTHER, null, false, others, other));
            if (none > 0) counted.add(new AppRow(AppRow.NO_APP, null, false, 0, none));
            roundRows(counted, target);
            // an app of its own that rounds to 0m joins "Other apps" instead
            boolean moved = false;
            for (AppRow r : counted) {
                if (r.kind == AppRow.APP && r.shownMin == 0) {
                    small.add(r.pkg);
                    moved = true;
                }
            }
            if (!moved) break;
        }
        double left = 0;
        for (AppRow r : out) left += r.exactMin;
        roundRows(out, !out.isEmpty() && Math.abs(left - excludedMin) < 0.01 ? Math.round(excludedMin) : -1);
        List<AppRow> rows = new ArrayList<AppRow>();
        for (AppRow r : counted) if (r.kind == AppRow.APP || r.shownMin > 0) rows.add(r);
        rows.addAll(out);
        return rows;
    }

    /**
     * The line shown instead of the list when it has no rows: no app at all, or apps that together
     * stayed under a minute (they round to 0m).
     */
    public static String emptyListText(List<Map.Entry<String, Double>> perApp) {
        return perApp.isEmpty() ? "No app was in front on this day." : "Apps were in front for less than a minute in all on this day.";
    }

    private static void roundRows(List<AppRow> rows, long target) {
        double[] v = new double[rows.size()];
        for (int i = 0; i < v.length; i++) v[i] = rows.get(i).exactMin;
        long[] r = target < 0 ? roundTogether(v) : roundTogether(v, target);
        for (int i = 0; i < v.length; i++) rows.get(i).shownMin = r[i];
    }

    /**
     * Whole numbers that add up to the rounded sum of {@code v} (largest remainder: each value rounded
     * down, the missing ones go to the largest remainders, the earlier value on a tie). Negative
     * values count as 0.
     */
    public static long[] roundTogether(double[] v) {
        double sum = 0;
        for (double x : v) sum += Math.max(0, x);
        return roundTogether(v, Math.round(sum));
    }

    /**
     * As above, adding up to {@code target} (which must be within one per value of their sum): missing
     * ones go to the largest remainders, extra ones come off the smallest remainders.
     */
    public static long[] roundTogether(double[] v, long target) {
        long[] out = new long[v.length];
        double[] rest = new double[v.length];
        long given = 0;
        for (int i = 0; i < v.length; i++) {
            double x = Math.max(0, v[i]);
            out[i] = (long) Math.floor(x);
            rest[i] = x - out[i];
            given += out[i];
        }
        boolean[] used = new boolean[v.length];
        while (given < target) {
            int best = -1;
            for (int i = 0; i < v.length; i++) if (!used[i] && (best < 0 || rest[i] > rest[best])) best = i;
            if (best < 0) break;
            out[best]++;
            used[best] = true;
            given++;
        }
        while (given > target) {
            int best = -1;
            for (int i = 0; i < v.length; i++) if (!used[i] && out[i] > 0 && (best < 0 || rest[i] < rest[best])) best = i;
            if (best < 0) break;
            out[best]--;
            used[best] = true;
            given--;
        }
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
