package app.openring.core.analysis;

/**
 * Screen score, 0 to 100 (higher is better): how much the screen was on against the daily goal,
 * phone use in the hour before falling asleep and during the night. Our own simple formula, not a
 * clinical measure. Pure Java so it runs in the JVM tests.
 */
public final class ScreenScore {
    private ScreenScore() {}

    public static final double W_AMOUNT = 0.5, W_BEDTIME = 0.3, W_NIGHT = 0.2;
    /** The goal used for the score when the person set none. */
    public static final int DEFAULT_GOAL_MIN = 180;
    /** Before bed: up to this many minutes give 100, from {@link #BEDTIME_ZERO_MIN} on 0. */
    public static final double BEDTIME_FREE_MIN = 5, BEDTIME_ZERO_MIN = 45;
    /** During the night: up to this many minutes give 100, from {@link #NIGHT_ZERO_MIN} on 0. */
    public static final double NIGHT_FREE_MIN = 1, NIGHT_ZERO_MIN = 20;
    /** The window before falling asleep that counts as "before bed". */
    public static final double BEDTIME_WINDOW_S = 3600;

    public static final class Result {
        public int value;
        /** Part scores 0 to 100; NaN when that part is not known (no night). */
        public double amount, bedtime = Double.NaN, night = Double.NaN;
    }

    /** Part score for the amount: half the goal or less 100, the goal 70, twice the goal 0. */
    public static double amount(double minutes, int goalMin) {
        double g = goalMin > 0 ? goalMin : DEFAULT_GOAL_MIN;
        double f = minutes / g;
        if (f <= 0.5) return 100;
        if (f <= 1) return 100 - 30 * (f - 0.5) / 0.5;
        if (f <= 2) return 70 - 70 * (f - 1);
        return 0;
    }

    /** 100 up to {@code free} minutes, falling in a straight line to 0 at {@code zero} minutes. */
    static double falling(double minutes, double free, double zero) {
        if (minutes <= free) return 100;
        if (minutes >= zero) return 0;
        return 100 * (zero - minutes) / (zero - free);
    }

    public static double bedtime(double minutes) {
        return falling(minutes, BEDTIME_FREE_MIN, BEDTIME_ZERO_MIN);
    }

    public static double night(double minutes) {
        return falling(minutes, NIGHT_FREE_MIN, NIGHT_ZERO_MIN);
    }

    /**
     * The score of a day. {@code bedMin} and {@code nightMin} are NaN when no night is known; the
     * amount alone then makes the score.
     */
    public static Result of(double totalMin, int goalMin, double bedMin, double nightMin) {
        Result r = new Result();
        r.amount = amount(totalMin, goalMin);
        if (Double.isNaN(bedMin) || Double.isNaN(nightMin)) {
            r.value = (int) Math.round(r.amount);
            return r;
        }
        r.bedtime = bedtime(bedMin);
        r.night = night(nightMin);
        r.value = (int) Math.round(W_AMOUNT * r.amount + W_BEDTIME * r.bedtime + W_NIGHT * r.night);
        return r;
    }
}
