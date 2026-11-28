package app.openring.core.analysis;

import java.util.Locale;

import app.openring.core.Texts;

/**
 * The sentences of the tabs that say how the day looks (issue #293, phase 0b), moved out of {@code ui/Pages} so they are
 * tested on the JVM for every kind of day: the Readiness headline, the readiness and sleep debt line under Today's day line,
 * and the "usual" line under each of last night's body signals with its colour. Plain values in, the screens read them from
 * the stored day; {@code tests/java/DaySummaryTest} checks them against the code they replaced.
 */
public final class DaySummary {
    private DaySummary() {}

    /** The headline without a readiness or sleep score. */
    public static final String NO_SCORE = "Keep wearing your ring to build your picture.";

    /** Said while the readiness baselines are still forming (the score is provisional). */
    public static final String PROVISIONAL = "Your baselines are still forming, so scores will become more personal over the next two weeks.";

    /** Where the digest has nothing else to say and stands on its own (not under the day's line). */
    public static final String NOTHING_LEFT = "Synced and analysed on this phone. Nothing left the device.";

    /** Sleep debt from this many minutes is said in the digest. */
    public static final double DEBT_SAID_MIN = 180;

    /** A body signal this share away from its usual (or one shown digit, whichever is more) is coloured. */
    public static final double SIGNAL_SHARE = 0.05;

    /** The colour of a body signal's line: as the other small lines, better than usual (green), worse (orange). */
    public static final int SAME = 0, BETTER = 1, WORSE = 2;

    /**
     * The sentence under the Readiness score: from the readiness score (NaN without one), else from the sleep score, else
     * {@link #NO_SCORE}. The steps are those of the score labels ({@link Scores#OPTIMAL}, {@link Scores#GOOD}).
     */
    public static String headline(double readiness, double sleep) {
        if (!Double.isNaN(readiness)) {
            if (readiness >= Scores.OPTIMAL) return "You're well recovered. A good day to push a little.";
            if (readiness >= Scores.GOOD) return "You're in good shape. Keep a steady pace today.";
            return "Your body is asking for rest. Take it easy today.";
        }
        if (!Double.isNaN(sleep)) return sleep >= Scores.OPTIMAL ? "You slept really well." : sleep >= Scores.GOOD ? "You slept reasonably well." : "Last night's sleep was short or restless.";
        return NO_SCORE;
    }

    /**
     * The sleep debt the digest may say, in minutes, NaN for none: the ring's stored debt when it is valid (a missing number
     * counts as 0), none when the ring's is not valid yet; without a stored debt at all, the watch's ({@code watch}: minutes
     * and nights from {@code DeviceHistory.debt}, only for a person with only the watch, else null) when it has a night.
     */
    public static double debtMin(boolean ringDebt, boolean ringValid, double ringMin, double[] watch) {
        if (ringDebt) return ringValid ? ringMin : Double.NaN;
        if (watch != null && watch[1] > 0) return watch[0];
        return Double.NaN;
    }

    /**
     * The readiness sentence and the sleep debt under the day's line. {@code labels} and {@code scores}: the readiness
     * contributors (null without a readiness score, empty for a score without contributors); the strongest is the first
     * with the highest score, the weakest the first with the lowest, and {@link Texts#contributorLine} says them (the
     * ladder of the Readiness page, issue #200). {@code debtMin} from {@link #debtMin}; {@code noDebt} when the day's line
     * already said the sleep debt. {@code forLine}: under the day's line, which has its own sentence, so no filler.
     */
    public static String digest(String[] labels, double[] scores, boolean provisional, double debtMin, boolean noDebt, boolean forLine,
            Locale locale) {
        StringBuilder sb = new StringBuilder();
        if (labels != null) {
            int worst = -1, best = -1;
            for (int i = 0; i < labels.length; i++) {
                if (worst < 0 || scores[i] < scores[worst]) worst = i;
                if (best < 0 || scores[i] > scores[best]) best = i;
            }
            String line = best < 0 ? "" : Texts.contributorLine(labels[best], labels[worst], scores[worst], locale);
            sb.append(line.isEmpty() ? ". " : line + " ");
            if (provisional) sb.append(PROVISIONAL).append(' ');
        }
        if (!noDebt && debtMin >= DEBT_SAID_MIN) sb.append("You carry about ").append(Texts.hm(debtMin)).append(" of sleep debt. ");
        if (sb.length() == 0 && !forLine) sb.append(NOTHING_LEFT);
        return sb.toString().trim();
    }

    /**
     * The line under a body signal: "usual 58", with ", higher" or ", lower" once the difference shows in {@code dp}
     * decimals; "usual value after a few nights" while either value is missing.
     */
    public static String signalLine(double value, double usual, int dp, Locale locale) {
        if (Double.isNaN(usual) || Double.isNaN(value)) return "usual value after a few nights";
        double diff = value - usual;
        return "usual " + String.format(locale, "%." + dp + "f", usual) + (Math.abs(diff) < Math.pow(10, -dp) ? "" : (diff > 0 ? ", higher" : ", lower"));
    }

    /**
     * The colour of that line: {@link #SAME} unless the value is at least {@link #SIGNAL_SHARE} of the usual away from it (and
     * at least one shown digit), then {@link #BETTER} or {@link #WORSE} ({@code lowerIsBetter} for heart rate and breathing).
     */
    public static int signalTone(double value, double usual, int dp, boolean lowerIsBetter) {
        if (Double.isNaN(usual) || Double.isNaN(value)) return SAME;
        double diff = value - usual;
        boolean better = lowerIsBetter ? diff < 0 : diff > 0;
        if (Math.abs(diff) >= Math.max(Math.abs(usual) * SIGNAL_SHARE, Math.pow(10, -dp))) return better ? BETTER : WORSE;
        return SAME;
    }
}
