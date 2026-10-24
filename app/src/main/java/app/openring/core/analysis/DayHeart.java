package app.openring.core.analysis;

/**
 * The Body tab's heart rate of one day (issue #72): the line is the quarter hour averages of every reading from
 * midnight to midnight, and Lowest, Average and Highest are taken from that same line, so the numbers always match
 * what is drawn. The night's lowest heart rate ({@link NightAnalyzer}, 5 minute averages inside the sleep) is a
 * different measurement and stays separate.
 */
public final class DayHeart {
    /** Width of one point of the line: a quarter hour. */
    public static final double STEP_S = 900;
    /**
     * Rules version of the stored line (kv {@code day_heart}): 2 since issue #97 (one plausibility range, values
     * after midnight from an event begun before it), 3 since issue #148 (green IBI beats in the same range, 273 to
     * 2000 ms instead of 300 to 2000). A database from older rules has its days analysed again once.
     */
    public static final int VERSION = 3;
    /** How far before midnight the day reads events: an HRV event's six values span 25 minutes after its start. */
    public static final double LOOK_BACK_S = 1800;

    /** Lowest, average and highest point of the line, NaN when it has no point. */
    public double low = Double.NaN, avg = Double.NaN, high = Double.NaN;
    /** When the lowest and highest point are (unix seconds, the middle of their quarter hour), NaN without points. */
    public double lowAt = Double.NaN, highAt = Double.NaN;
    /** Points of the line between the bounds. */
    public int points;

    private DayHeart() {}

    /** The line: the average of the readings in each quarter hour from {@code from} to {@code to}; empty quarter hours are left out. */
    public static TS curve(TS readings, double from, double to) {
        return NightAnalyzer.bin(readings.sort(), from, to, STEP_S);
    }

    /** How many points a day from {@code from} to {@code to} can have (100 on a 25 hour day), so storing it never merges any. */
    public static int maxPoints(double from, double to) {
        return (int) Math.max(1, Math.ceil((to - from) / STEP_S));
    }

    /**
     * Lowest, average (each point counts once) and highest of the points of {@code line} from {@code from} to
     * {@code to}, the same points the chart draws. Points without a value are skipped.
     */
    public static DayHeart of(TS line, double from, double to) {
        DayHeart d = new DayHeart();
        double sum = 0;
        for (int i = 0; line != null && i < line.size(); i++) {
            double t = line.t(i), v = line.v(i);
            if (t < from || t > to || Double.isNaN(v)) continue;
            if (d.points == 0 || v < d.low) {
                d.low = v;
                d.lowAt = t;
            }
            if (d.points == 0 || v > d.high) {
                d.high = v;
                d.highAt = t;
            }
            sum += v;
            d.points++;
        }
        if (d.points > 0) d.avg = sum / d.points;
        return d;
    }
}
