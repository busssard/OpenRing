package app.openring.core.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;

/**
 * Where moments of a day lie on a 24 hour clock face (issue #46): by their local clock time, so
 * 03:00 is always at the 03:00 tick, also on the days the clocks change (23 or 25 hours).
 */
public final class DialMath {
    private DialMath() {}

    /** The local clock time of {@code t} as a share of 24 hours; 0 at or before {@code from}, 1 at or after {@code to}. */
    public static double fraction(double t, double from, double to, ZoneId zone) {
        if (!(t > from)) return 0;
        if (t >= to) return 1;
        ZonedDateTime z = Instant.ofEpochSecond((long) Math.floor(t)).atZone(zone);
        double s = z.toLocalTime().toSecondOfDay() + (t - Math.floor(t));
        return s / 86400.0;
    }

    /** Degrees for a canvas (0 is three o'clock): midnight at the top, clockwise. */
    public static float angle(double t, double from, double to, ZoneId zone) {
        return (float) (-90 + 360 * fraction(t, from, to, zone));
    }

    /**
     * How much of each of {@code steps} equal parts of the clock face (5 minutes for 288) is in
     * {@code ranges}, 0 to 1. The day [from, to) is cut into real pieces of that length; each lands on
     * the step of its clock time and a step keeps the largest of its pieces. When the clocks go back
     * both repeated hours land on the same steps; when they go forward the missing hour stays empty.
     */
    public static double[] fill(List<double[]> ranges, double from, double to, ZoneId zone, int steps) {
        double[] out = new double[steps];
        double len = 86400.0 / steps;
        int pieces = (int) Math.ceil((to - from) / len - 1e-9);
        if (pieces <= 0) return out;
        double[] real = new double[pieces];
        for (double[] x : ranges) {
            double a = Math.max(x[0], from), b = Math.min(x[1], to);
            if (b <= a) continue;
            int i0 = (int) Math.floor((a - from) / len), i1 = Math.min(pieces - 1, (int) Math.floor((b - from) / len));
            for (int i = Math.max(0, i0); i <= i1; i++) {
                double s = from + i * len, e = Math.min(to, s + len);
                real[i] += Math.max(0, Math.min(b, e) - Math.max(a, s)) / len;
            }
        }
        for (int i = 0; i < pieces; i++) {
            if (real[i] <= 0) continue;
            int k = Math.min(steps - 1, (int) Math.floor(fraction(from + i * len + 1e-3, from, to, zone) * steps + 1e-6));
            out[k] = Math.max(out[k], Math.min(1, real[i]));
        }
        return out;
    }

    /** What a touch on the face reads (issue #109): the moment, the use there (its index, -1 for none) and whether it was asleep. */
    public static final class Touch {
        public final double at;
        public final int session;
        public final boolean asleep;

        Touch(double at, int session, boolean asleep) {
            this.at = at;
            this.session = session;
            this.asleep = asleep;
        }
    }

    /**
     * A touch at the clock time {@code fraction} on the face of {@code day} [from, to) with {@code steps}
     * lines (issue #109). {@code sessions} are the real uses, {@code shown} the same uses as drawn (each at
     * least as long as the chip says, same order and size). A touched moment inside a use picks it (a real
     * use before one only drawn over it; in the hour that repeats, the pass with use). Otherwise, when the touched line is lit, the shown use
     * nearest to the touched moment among those {@link #fill} put on that line, so every lit line finds its
     * use. The moment is then moved into the real use, so the hand and the times agree. Without a use,
     * {@code asleep} says whether a touched moment (either pass of the repeated hour) lies in {@code nights}.
     */
    public static Touch touch(double fraction, LocalDate day, double from, double to, ZoneId zone, int steps,
            List<double[]> sessions, List<double[]> shown, List<double[]> nights) {
        if (shown == null || shown.size() != sessions.size()) shown = sessions;
        double f = Math.max(0, Math.min(fraction, 1));
        double[] at = instants(f, day, zone);
        int best = -1;
        double bestAt = at[0];
        // inside a real use first (widened uses can cover the next one), then inside a use as drawn
        for (List<double[]> list : java.util.Arrays.asList(sessions, shown)) {
            for (int j = 0; j < at.length && best < 0; j++) {
                for (int i = 0; i < list.size(); i++) {
                    double[] s = list.get(i);
                    if (at[j] >= s[0] && at[j] < s[1]) {
                        best = i;
                        bestAt = at[j];
                        break;
                    }
                }
            }
        }
        if (best < 0 && to > from) {
            // the real pieces that land on the touched line, with the maths of fill
            int k = Math.min(steps - 1, (int) Math.floor(f * steps));
            double len = 86400.0 / steps, bestD = Double.POSITIVE_INFINITY;
            int pieces = (int) Math.ceil((to - from) / len - 1e-9);
            for (int p = 0; p < pieces; p++) {
                double ps = from + p * len, pe = Math.min(to, ps + len);
                if (Math.min(steps - 1, (int) Math.floor(fraction(ps + 1e-3, from, to, zone) * steps + 1e-6)) != k) continue;
                for (int i = 0; i < shown.size(); i++) {
                    double[] s = shown.get(i);
                    double a = Math.max(Math.max(s[0], from), ps), b = Math.min(Math.min(s[1], to), pe);
                    if (b <= a) continue;
                    for (double x : at) {
                        double d = x < a ? a - x : x >= b ? x - b : 0;
                        // ties go to the first found: the earlier piece, then the earlier use
                        if (d < bestD) {
                            bestD = d;
                            best = i;
                            bestAt = x;
                        }
                    }
                }
            }
        }
        if (best >= 0) {
            double[] r = sessions.get(best);
            double x = Math.max(r[0], Math.min(bestAt, Math.max(r[0], r[1] - 1)));
            return new Touch(Math.max(from, Math.min(to - 1, x)), best, false);
        }
        double pick = at[0];
        boolean asleep = false;
        for (double x : at) {
            if (asleep) break;
            if (nights != null) for (double[] n : nights) if (x >= n[0] && x < n[1]) {
                asleep = true;
                pick = x;
            }
        }
        return new Touch(Math.max(from, Math.min(to - 1, pick)), -1, asleep);
    }

    /**
     * The moments of {@code day} at the clock time {@code fraction} (a touch on the face): one, two in
     * the hour that repeats when the clocks go back (earlier first), or the moment of the switch when
     * the clock time does not exist that day (the hour the clocks skip).
     */
    public static double[] instants(double fraction, LocalDate day, ZoneId zone) {
        double f = Math.max(0, Math.min(fraction, 1));
        long sec = Math.round(f * 86400);
        if (sec >= 86400) return new double[] {day.plusDays(1).atStartOfDay(zone).toEpochSecond()};
        LocalDateTime ldt = day.atStartOfDay().plusSeconds(sec);
        List<ZoneOffset> offs = zone.getRules().getValidOffsets(ldt);
        if (offs.isEmpty()) {
            ZoneOffsetTransition tr = zone.getRules().getTransition(ldt);
            return new double[] {tr.getInstant().getEpochSecond()};
        }
        double[] out = new double[offs.size()];
        for (int i = 0; i < out.length; i++) out[i] = ldt.toEpochSecond(offs.get(i));
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * The part of the face that repeats (clocks back) or is missing (clocks forward) on a day with
     * {@code c}: {start, length} as shares of 24 hours, or null without a change.
     */
    public static double[] changeArc(ClockChange.Change c) {
        if (c == null || c.minutes == 0) return null;
        String start = c.minutes < 0 ? c.to : c.from;
        int h = Integer.parseInt(start.substring(0, 2)), m = Integer.parseInt(start.substring(3, 5));
        return new double[] {(h * 60 + m) / 1440.0, Math.abs(c.minutes) / 1440.0};
    }
}
