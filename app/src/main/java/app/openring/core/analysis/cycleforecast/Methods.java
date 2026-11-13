package app.openring.core.analysis.cycleforecast;

import java.util.Arrays;

import app.openring.core.analysis.Cycles;

/**
 * The period prediction methods (issue #383), their ids as Settings stores them ({@code Prefs.periodMethod}), names and
 * short texts, and the small statistics they share. {@link #BEST} is no method of its own: the backtest picks one
 * ({@link Backtest#best}).
 */
public final class Methods {
    private Methods() {}

    /** Settings' choices. Stored, so the numbers never change; a new method gets the next number. */
    public static final int BEST = 0, OPEN_RING = 1, DRIP = 2, SMOOTH = 3, TREND = 4, BAYES = 5;

    static final Method OPEN = new OpenRingMethod();
    /** Every method, in the order Settings and the card list them. */
    public static final Method[] ALL = {OPEN, new DripMethod(), new SmoothMethod(), new TrendMethod(), new BayesMethod()};
    /** Settings' order: Best for you first. */
    public static final int[] CHOICES = {BEST, OPEN_RING, DRIP, SMOOTH, TREND, BAYES};

    /** The method with {@code id}; Open Ring for {@link #BEST} or an unknown id (a setting from a newer version). */
    public static Method get(int id) {
        for (Method m : ALL) if (m.id() == id) return m;
        return OPEN;
    }

    /** Whether {@code id} is one of Settings' choices (a backup from a newer version may hold another). */
    public static boolean valid(int id) {
        for (int c : CHOICES) if (c == id) return true;
        return false;
    }

    /** Whether the method uses this cycle's temperature rise on top: every one but drip style, which uses start days only. */
    public static boolean usesTemperature(int id) {
        return id != DRIP;
    }

    /** The name on Settings and the card. */
    public static String name(int id) {
        switch (id) {
            case BEST: return "Best for you";
            case OPEN_RING: return "Open Ring";
            case DRIP: return "drip style";
            case SMOOTH: return "Recent cycles count more";
            case TREND: return "Follows your last cycle";
            case BAYES: return "Learns from every cycle";
            default: return "Open Ring";
        }
    }

    /** One line under the name in Settings. */
    public static String line(int id) {
        switch (id) {
            case BEST: return "The method that was closest on your own cycles";
            case OPEN_RING: return "Typical of your newest 12 cycles, far ones left out";
            case DRIP: return "Average of your newest 12 cycles, like drip, without temperature";
            case SMOOTH: return "Newer cycles weigh more (exponential smoothing)";
            case TREND: return "A long cycle tends to follow a long one, if yours do (autoregressive)";
            case BAYES: return "Starts from a large study and moves to yours (Bayesian), notices a missed period";
            default: return "";
        }
    }

    /**
     * How it works, one text per method: what it does with the person's cycles (plain, no formulas). The research and the
     * sources are in the pull request of issue #383.
     */
    public static String how(int id) {
        switch (id) {
            case OPEN_RING:
                return "Open Ring: of your newest " + Cycles.WINDOW + " cycles, those more than " + Cycles.FAR + " days from the middle value are left "
                        + "out (maybe a period was not logged). The typical length is their middle value while there are fewer than "
                        + Cycles.TRIM_FROM + ", then their average without the shortest and the longest. Give or take 1.28 times how much they vary.";
            case DRIP:
                return "drip style: the average of your newest " + Cycles.WINDOW + " cycles, none left out, give or take 1 day when they vary by "
                        + "less than 1.5 days, else 2, as drip's FAQ describes it. Start days only, so it never uses your temperature.";
            case SMOOTH:
                return "Recent cycles count more: each new cycle moves the estimate part of the way toward it (exponential smoothing). How "
                        + "far is fitted to your own history: the share that would have missed your past cycles least, so cycles that drift get "
                        + "followed faster. Give or take 1.28 times its usual miss.";
            case TREND:
                return "Follows your last cycle: if in your history a long cycle tended to follow a long one (or a short one), the next is "
                        + "moved that way from your average (an autoregressive model). The link is shrunk to none unless your cycles clearly show it, "
                        + "so without a link it is your average. Needs " + TrendMethod.FROM + " cycles.";
            case BAYES:
                return "Learns from every cycle: starts from a study of 612,613 cycles (on average 29.3 days, one person's cycles varying by "
                        + "about 2.6 days) and moves toward your own numbers with each cycle (Bayesian). A cycle about twice your usual length is "
                        + "counted as two, a period that was probably not logged, as research on period apps suggests. Its range is wider "
                        + "while it knows few of your cycles.";
            default:
                return "";
        }
    }

    /** What a method's range is, for "Give or take N days: ...". */
    public static String rangeWords(int id) {
        switch (id) {
            case DRIP: return "drip's rule, 1 day while your cycles vary by less than 1.5 days, else 2";
            case SMOOTH: return "1.28 times how far it usually missed your past cycles, about 4 in 5";
            case TREND: return "1.28 times how much your cycles vary once the link to the last one is taken out, about 4 in 5";
            case BAYES: return "about 4 in 5, from how much your cycles vary and how sure it is of your average, wider while it knows few cycles";
            default: return "1.28 times how much your cycles vary, about 4 in 5";
        }
    }

    // ------------------------------------------------------------------------- shared statistics

    static double mean(int[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (int x : v) s += x;
        return s / v.length;
    }

    static double median(int[] v) {
        if (v.length == 0) return Double.NaN;
        int[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    /** The mean without one shortest and one longest value (at least 3 values). */
    static double trimmedMean(int[] v) {
        int[] s = v.clone();
        Arrays.sort(s);
        double sum = 0;
        for (int i = 1; i < s.length - 1; i++) sum += s[i];
        return sum / (s.length - 2);
    }

    /** The sample standard deviation, NaN with fewer than 2 values. */
    static double sd(int[] v) {
        if (v.length < 2) return Double.NaN;
        double m = mean(v), q = 0;
        for (int x : v) q += (x - m) * (x - m);
        return Math.sqrt(q / (v.length - 1));
    }

    /** The cycles Open Ring keeps: within {@link Cycles#FAR} days of the middle value of all, oldest first. */
    public static int[] kept(int[] lengths) {
        double med = median(lengths);
        int n = 0;
        for (int l : lengths) if (!far(l, med)) n++;
        int[] out = new int[n];
        int k = 0;
        for (int l : lengths) if (!far(l, med)) out[k++] = l;
        return out;
    }

    /** More than {@link Cycles#FAR} days from {@code median}: maybe a period was not logged. */
    public static boolean far(int length, double median) {
        return Math.abs(length - median) > Cycles.FAR;
    }
}
