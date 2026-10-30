package app.openring.core.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Times the person left out because someone else wore the ring (issue #254). The ranges are merged (overlapping or
 * touching ones become one), so a moment is either left out or not, whichever rows hold it. Times are unix seconds,
 * each range from its start (included) to its stop (not included). The analysis drops every reading whose own time
 * lies inside a range; nothing is stored about it, so removing a range gives back exactly the results from before.
 * Also the texts the screens show about it.
 */
public final class LeftOut {
    /** A sleep window shorter than this after taking the left out time away is dropped, as after phone use. */
    public static final double MIN_WINDOW_S = ScreenTime.MIN_WINDOW_S;
    /** No time left out: every check answers at once and changes nothing. */
    public static final LeftOut NONE = new LeftOut(new double[0], new double[0]);

    private final double[] start, stop;

    private LeftOut(double[] start, double[] stop) {
        this.start = start;
        this.stop = stop;
    }

    /** The merged ranges of {@code rows}, each {start, stop, ...}; rows without a positive length are skipped. */
    public static LeftOut of(List<double[]> rows) {
        List<double[]> ok = new ArrayList<double[]>();
        if (rows != null) for (double[] r : rows) if (r != null && r.length >= 2 && r[1] > r[0]) ok.add(new double[] {r[0], r[1]});
        if (ok.isEmpty()) return NONE;
        java.util.Collections.sort(ok, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        double[] s = new double[ok.size()], e = new double[ok.size()];
        int n = 0;
        for (double[] r : ok) {
            if (n > 0 && r[0] <= e[n - 1]) {
                if (r[1] > e[n - 1]) e[n - 1] = r[1];
                continue;
            }
            s[n] = r[0];
            e[n++] = r[1];
        }
        return new LeftOut(Arrays.copyOf(s, n), Arrays.copyOf(e, n));
    }

    public boolean isEmpty() {
        return start.length == 0;
    }

    /** How many merged ranges. */
    public int size() {
        return start.length;
    }

    /** The merged ranges {start, stop}, oldest first. */
    public List<double[]> ranges() {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < start.length; i++) out.add(new double[] {start[i], stop[i]});
        return out;
    }

    /** The index of the last range starting at or before {@code t}, -1 when none does. */
    private int before(double t) {
        int lo = 0, hi = start.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] <= t) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** True when the moment {@code t} is left out. */
    public boolean contains(double t) {
        if (start.length == 0) return false;
        int i = before(t);
        return i >= 0 && t < stop[i];
    }

    /** True when some time in [a, b) is left out. */
    public boolean overlaps(double a, double b) {
        return !pieces(a, b).isEmpty();
    }

    /** True when all of [a, b) is left out already (one merged range holds it). */
    public boolean covers(double a, double b) {
        int i = before(a);
        return i >= 0 && a < stop[i] && b <= stop[i];
    }

    /** The left out parts of [a, b), clipped to it, oldest first. */
    public List<double[]> pieces(double a, double b) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < start.length; i++) {
            double s = Math.max(a, start[i]), e = Math.min(b, stop[i]);
            if (e > s) out.add(new double[] {s, e});
        }
        return out;
    }

    /** Seconds of [a, b) that are left out. */
    public double seconds(double a, double b) {
        double sum = 0;
        for (double[] p : pieces(a, b)) sum += p[1] - p[0];
        return sum;
    }

    /**
     * A sleep window [a, b) without the left out time: the longest piece outside it (the earlier one of two as long),
     * the window itself when nothing of it is left out, null when that piece is shorter than {@link #MIN_WINDOW_S}.
     */
    public double[] window(double a, double b) {
        List<double[]> in = pieces(a, b);
        if (in.isEmpty()) return new double[] {a, b};
        double bestA = 0, bestB = 0, from = a;
        for (int k = 0; k <= in.size(); k++) {
            double to = k < in.size() ? in.get(k)[0] : b;
            if (to - from > bestB - bestA) {
                bestA = from;
                bestB = to;
            }
            if (k < in.size()) from = in.get(k)[1];
        }
        return bestB - bestA < MIN_WINDOW_S ? null : new double[] {bestA, bestB};
    }

    /** What the analysis depends on, for its stored inputs (issue #194): the merged ranges in whole seconds. */
    public String key() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < start.length; i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append((long) Math.floor(start[i])).append('-').append((long) Math.ceil(stop[i]));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------- texts

    /** Why a new left out time cannot be saved, null when it can. {@code now}: unix seconds now. */
    public String problem(double a, double b, double now) {
        if (!(b > a)) return "The end must be after the start.";
        if (a > now) return "The start cannot be in the future.";
        if (covers(a, b)) return "This time is already left out.";
        return null;
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH),
            TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    static String dayTime(double t, ZoneId z) {
        return Instant.ofEpochSecond((long) Math.floor(t)).atZone(z).format(DAY_TIME);
    }

    static String clock(double t, ZoneId z) {
        return Instant.ofEpochSecond((long) Math.floor(t)).atZone(z).format(TIME);
    }

    /** "3 Oct 18:00 to 4 Oct 09:00". */
    public static String span(double a, double b, ZoneId z) {
        return dayTime(a, z) + " to " + dayTime(b, z);
    }

    /** A length: "45 min", "15 h", "15 h 30 min", "3 days", "3 days 4 h" (minutes rounded). */
    public static String length(double seconds) {
        long min = Math.round(seconds / 60);
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        if (h >= 48) {
            long d = h / 24, rh = h % 24;
            return d + " days" + (rh > 0 ? " " + rh + " h" : "");
        }
        return h + " h" + (m > 0 ? " " + m + " min" : "");
    }

    /** One line of the list in Settings: "3 Oct 18:00 to 4 Oct 09:00 (15 h)". */
    public static String listLine(double a, double b, ZoneId z) {
        return span(a, b, z) + " (" + length(b - a) + ")";
    }

    /** The calendar days in zone {@code z} that [a, b) touches. */
    public static long days(double a, double b, ZoneId z) {
        if (!(b > a)) return 0;
        LocalDate d0 = date(a, z), d1 = date(Math.max(a, b - 1), z);
        return ChronoUnit.DAYS.between(d0, d1) + 1;
    }

    static LocalDate date(double t, ZoneId z) {
        return Instant.ofEpochSecond((long) Math.floor(t)).atZone(z).toLocalDate();
    }

    /** The first day the analysis works out again after a change at {@code a}: two days before it. */
    public static LocalDate againFrom(double a, ZoneId z) {
        return date(a, z).minusDays(2);
    }

    /**
     * What [a, b) is, in words (review of #263): a whole day "28 Oct (24 h)", whole days "28 Oct to 30 Oct (3 days)", within
     * one day "13:00 to 17:00 on 28 Oct (4 h)", else "3 Oct 18:00 to 4 Oct 09:00 (15 h)".
     */
    public static String what(double a, double b, ZoneId z) {
        LocalDate d0 = date(a, z), dl = date(Math.max(a, b - 1), z);
        boolean midA = d0.atStartOfDay(z).toEpochSecond() == a, midB = dl.plusDays(1).atStartOfDay(z).toEpochSecond() == b;
        if (midA && midB) {
            long n = ChronoUnit.DAYS.between(d0, dl) + 1;
            return n == 1 ? d0.format(DAY) + " (" + length(b - a) + ")" : d0.format(DAY) + " to " + dl.format(DAY) + " (" + count(n, "day", "days") + ")";
        }
        if (d0.equals(dl)) return clock(a, z) + " to " + clock(b, z) + " on " + d0.format(DAY) + " (" + length(b - a) + ")";
        return span(a, b, z) + " (" + length(b - a) + ")";
    }

    /**
     * The preview in Settings before leaving out [a, b) (reworded after the review of #263): what goes and how many of its
     * hours hold heart rate or temperature, the stored nights inside it (left out) and those that reach into it (cut at its
     * edges), that the ring's data stays, which days are worked out again (from two days before the start to {@code today};
     * past {@code longDays} days it says that takes a few minutes), and when the end lies after {@code now}, that what the ring
     * records until then is left out too.
     */
    public static String preview(double a, double b, long inside, long cut, long hours, LocalDate today, double now, ZoneId z, int longDays) {
        LocalDate from = againFrom(a, z);
        long again = Math.max(1, ChronoUnit.DAYS.between(from, today) + 1);
        StringBuilder s = new StringBuilder("Left out: ").append(what(a, b, z));
        if (hours == 0) s.append(", with no heart rate or temperature in it.");
        else s.append(", with heart rate or temperature in ").append(hours).append(" of those hours.");
        if (inside > 0) s.append(' ').append(count(inside, "night", "nights")).append(inside == 1 ? " inside it is left out." : " inside it are left out.");
        if (cut > 0) s.append(' ').append(count(cut, "night", "nights")).append(cut == 1 ? " that reaches into it is cut at its edge." : " that reach into it are cut at its edges.");
        s.append(" The ring's data stays stored and comes back if you remove this. Your days from ").append(from.format(DAY))
                .append(" to today are worked out again (").append(count(again, "day", "days")).append(").");
        if (again > longDays) s.append(" This takes a few minutes.");
        if (b > now) s.append(" This also leaves out what the ring records until ").append(dayTime(b, z)).append('.');
        return s.toString();
    }

    /** The question's text before bringing [a, b) back. */
    public static String bringBackText(double a, ZoneId z) {
        return "It counts again in your charts, scores and usual values. Your days from " + againFrom(a, z).format(DAY) + " on are worked out again.";
    }

    /** The card on Today, Sleep and Activity: "From 3 Oct 18:00 to 4 Oct 09:00 is left out (someone else wore the ring). ..." */
    public static String card(double a, double b, ZoneId z) {
        return "From " + span(a, b, z) + " is left out (someone else wore the ring). To bring it back: Settings, Advanced.";
    }

    /** The touch readout of a grey band on a day chart: "Left out, 18:00 to 00:00 (someone else wore the ring)". */
    public static String bandTip(double a, double b, ZoneId z) {
        return "Left out, " + clock(a, z) + " to " + clock(b, z) + " (someone else wore the ring)";
    }

    /** A row of the night timeline: "Left out from 03:00 to 09:00 (someone else wore the ring)"; dates when a day or longer. */
    public static String timelineRow(double a, double b, ZoneId z) {
        String when = b - a < 86400 ? clock(a, z) + " to " + clock(b, z) : span(a, b, z);
        return "Left out from " + when + " (someone else wore the ring)";
    }

    /** The line under Daily movement on Activity: "3 h of this day are left out, so they are not in these minutes." */
    public static String activityLine(double seconds) {
        long min = Math.round(seconds / 60);
        boolean one = min == 60 || min == 1;
        return length(seconds) + " of this day " + (one ? "is left out, so it is" : "are left out, so they are") + " not in these minutes.";
    }

    static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
