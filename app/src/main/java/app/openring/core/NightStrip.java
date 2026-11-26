package app.openring.core;

import java.time.ZoneId;
import java.util.Locale;

import app.openring.core.analysis.WakeUps;

/**
 * The thin strips under the night's stages (code health of issue #380, the Experiment view's point 2): the Movement strip,
 * readable by touch, and the axis it shares with the Sounds strip. Plain Java, so the JVM tests run it.
 */
public final class NightStrip {
    private NightStrip() {}

    /** The bar width used when a series has fewer than two points (the night's movement comes in 5 minute bins). */
    public static final double DEFAULT_STEP_S = 300;

    /** Where {@code t} lies on an axis from {@code from} to {@code to}: 0 at the start, 1 at the end (outside: below 0 or above 1). */
    public static double frac(double t, double from, double to) {
        return to > from ? (t - from) / (to - from) : 0;
    }

    /** The series' own step: the smallest distance between two of its times (sorted), {@link #DEFAULT_STEP_S} for fewer than two. */
    public static double step(double[] times) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < times.length; i++) {
            double d = times[i] - times[i - 1];
            if (d > 0 && d < best) best = d;
        }
        return Double.isInfinite(best) ? DEFAULT_STEP_S : best;
    }

    /** The index of the point nearest to {@code t} within half a {@code step} of it (so inside its bar), or -1. */
    public static int nearest(double[] times, double t, double step) {
        int best = -1;
        for (int i = 0; i < times.length; i++) {
            double d = Math.abs(times[i] - t);
            if (d <= step / 2 && (best < 0 || d < Math.abs(times[best] - t))) best = i;
        }
        return best;
    }

    /**
     * What the movement strip shows, for the line right under it. The ring sends many short reports of how many seconds it
     * felt you move; how often it sends them is not known (ARCHITECTURE.md), so the text does not name a rate (review of #482).
     */
    public static final String MOVEMENT_NOTE = "Movement: taller bars mean more. The ring sends many short reports of how many seconds you moved;"
            + " each bar shows their average over its 5 minutes.";

    /**
     * The Movement strip's touch readout for the bar around {@code at} (from {@code at} minus half a {@code step} to plus
     * half): "02:10 to 02:15, on average 4.0 s of motion per ring report" ({@link #MOVEMENT_NOTE} explains the reports),
     * "…, no movement" for 0. Times rounded to whole minutes as the timeline shows them; the seconds with one decimal under 10
     * in {@code loc}.
     */
    public static String movementTip(double at, double step, double seconds, ZoneId zone, Locale loc) {
        String span = WakeUps.clock(at - step / 2, zone) + " to " + WakeUps.clock(at + step / 2, zone);
        if (Double.isNaN(seconds)) return span + ", no movement reading";
        if (seconds <= 0) return span + ", no movement";
        String v = seconds < 9.95 ? String.format(loc, "%.1f", seconds) : String.format(loc, "%.0f", seconds);
        return span + ", on average " + v + " s of motion per ring report";
    }

    /** The readout where the movement strip has no reading: "02:10, no movement reading". */
    public static String noReading(double at, ZoneId zone) {
        return WakeUps.clock(at, zone) + ", no movement reading";
    }
}
