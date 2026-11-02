package app.openring.core;

/**
 * The default and the allowed range of a number the person types in (profile and screen time goal).
 * Setup, Settings, the stored settings and a restored backup all use these, so they can never keep
 * different numbers for the same field. Plain Java so the JVM tests cover it.
 */
public final class InputLimit {
    /** Age in years. */
    public static final InputLimit AGE = new InputLimit(35, 10, 100);
    /** Weight in kg. */
    public static final InputLimit WEIGHT_KG = new InputLimit(72, 30, 250);
    /** Height in cm. */
    public static final InputLimit HEIGHT_CM = new InputLimit(175, 100, 230);
    /** Daily active calorie goal in kcal, 0 for automatic. */
    public static final InputLimit TARGET_KCAL = new InputLimit(0, 0, 3000);
    /** Daily screen time goal in minutes, 0 for no goal (at most a whole day). */
    public static final InputLimit SCREEN_GOAL_MIN = new InputLimit(180, 0, 1440);

    /** Used when nothing is stored, and for typed text that is not a whole number. */
    public final int def;
    public final int lo, hi;

    InputLimit(int def, int lo, int hi) {
        this.def = def;
        this.lo = lo;
        this.hi = hi;
    }

    /** {@code v} moved into the range. */
    public int clamp(int v) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Typed text as a number in the range; the default when it is not a whole number (also empty or too long). */
    public int parse(String text) {
        try {
            return clamp(Integer.parseInt(text.trim()));
        } catch (NumberFormatException x) {
            return def;
        }
    }
}
