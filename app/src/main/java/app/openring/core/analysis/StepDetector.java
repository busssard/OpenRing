package app.openring.core.analysis;

/**
 * Steps from the phone's accelerometer (issue #74, the search walk): our own detection, so the app
 * needs no "physical activity" permission. Feed it every accelerometer sample (with gravity, in
 * m/s², as {@code TYPE_ACCELEROMETER} gives it); it reports each step it is sure about.
 *
 * How: the length of the acceleration vector minus gravity (a slow average follows gravity, so the
 * phone's angle does not matter), lightly smoothed. A step is a rise above {@link #RISE} after the
 * signal fell below {@link #FALL} since the step before. Walking has a steady rhythm, shaking does
 * not: a step counts only with a gap of {@link #MIN_GAP_MS} to {@link #MAX_GAP_MS} to the one before,
 * and a swing above {@link #MAX_PEAK} (a shaken phone) throws the run away. The first step of a run
 * is only counted once the second one confirms the rhythm, then both count.
 */
public final class StepDetector {
    /** Rise above the gravity average that makes a step (m/s²). Slow walking with the phone on the chest swings about 1 to 3. */
    public static final double RISE = 0.7;
    /** The signal has to fall below this (m/s²) between two steps. */
    public static final double FALL = -0.3;
    /** Steps closer than this are not walking (more than 3.3 steps a second). */
    public static final long MIN_GAP_MS = 300;
    /** A longer pause ends the run; the next step waits for a confirming one again. */
    public static final long MAX_GAP_MS = 1500;
    /** A swing above this (m/s²) is shaking or a bump, not a step: the run starts over. */
    public static final double MAX_PEAK = 7;
    /** Smoothing of the signal (time constant, ms) and of the gravity average (ms). */
    static final double SMOOTH_MS = 60, GRAVITY_MS = 1500;

    private double gravity = Double.NaN, smooth;
    private long lastT = Long.MIN_VALUE, lastStep = Long.MIN_VALUE, pending = Long.MIN_VALUE;
    private boolean armed = true;
    private int steps;

    /**
     * One accelerometer sample. Returns how many steps it adds (0, 1, or 2 when it confirms the
     * first step of a run).
     */
    public int add(long tMs, double ax, double ay, double az) {
        double m = Math.sqrt(ax * ax + ay * ay + az * az);
        if (Double.isNaN(gravity) || tMs - lastT > 1000 || tMs < lastT) {
            gravity = m;
            smooth = 0;
            lastT = tMs;
            armed = true;
            pending = Long.MIN_VALUE;
            lastStep = Long.MIN_VALUE;
            return 0;
        }
        double dt = tMs - lastT;
        lastT = tMs;
        gravity += (m - gravity) * Math.min(1, dt / GRAVITY_MS);
        smooth += (m - gravity - smooth) * Math.min(1, dt / SMOOTH_MS);
        double v = smooth;
        if (Math.abs(v) > MAX_PEAK) {
            // shaking: forget the run
            pending = Long.MIN_VALUE;
            lastStep = Long.MIN_VALUE;
            armed = false;
            return 0;
        }
        if (v < FALL) armed = true;
        if (!armed || v < RISE) return 0;
        armed = false;
        long prev = lastStep != Long.MIN_VALUE ? lastStep : pending;
        long gap = prev == Long.MIN_VALUE ? -1 : tMs - prev;
        if (gap >= 0 && gap < MIN_GAP_MS) {
            // too fast for walking: the run starts over from here
            pending = tMs;
            lastStep = Long.MIN_VALUE;
            return 0;
        }
        if (gap < 0 || gap > MAX_GAP_MS) {
            pending = tMs;
            lastStep = Long.MIN_VALUE;
            return 0;
        }
        int add = lastStep == Long.MIN_VALUE ? 2 : 1;
        lastStep = tMs;
        pending = Long.MIN_VALUE;
        steps += add;
        return add;
    }

    /** All steps counted so far. */
    public int steps() {
        return steps;
    }

    /** Step length from height: about 0.41 × height (a usual rule for walking). */
    public static double stepLength(double heightCm) {
        return 0.41 * heightCm / 100.0;
    }
}
