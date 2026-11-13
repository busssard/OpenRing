package app.openring.core.analysis.cycleforecast;

/**
 * Learns from every cycle (issue #383): a Bayesian normal model of the person's own mean cycle length and spread that
 * starts from a large study and moves toward her own numbers with each cycle.
 *
 * <ul>
 * <li><b>Start</b> (Bull et al. 2019, 612,613 cycles): mean {@link #MU0} days; people's own means spread by
 * {@link #TAU0} days (the total SD 5.2 without the within person 2.6); one person's cycles vary by {@link #SIGMA0} days,
 * held loosely ({@link #NU0} cycles' worth, since that varies a lot between people). The start counts as
 * (2.6 / 4.5)² ≈ 0.33 of a cycle, so the first own cycle already moves the mean most of the way.</li>
 * <li><b>Each cycle</b> updates the mean and the spread (normal with an unknown variance, the conjugate update).</li>
 * <li><b>A probably missed period</b> (Li, Urteaga et al., Clue data): before a cycle is used, the model compares "one
 * cycle" with "two cycles in one" (a period not logged), each with its chance and a Student t spread (heavier
 * tails than the normal, so an irregular person's long cycle stays one cycle). The chance of a missed log starts at
 * {@link #SKIP_A} in {@link #SKIP_A} + {@link #SKIP_B} (their starting values) and is learnt from the cycles seen.
 * When two cycles explain it better, it counts as two cycles of half its length. Only once it has seen {@link #SKIP_FROM} of
 * her cycles, and only when each half fits her own mean within {@link #CLIP} spreads: before that the mean is still mostly
 * the study's 29.3 days, and a person with steady 50 day cycles would have each split into two of 25 (code review of
 * dev dadbb71).</li>
 * <li><b>Any other far cycle</b> (more than {@link #CLIP} spreads from what the model expected) is pulled to that edge, so
 * one odd cycle cannot drag the mean. Also only from {@link #SKIP_FROM} cycles on; her first cycles count as they are.</li>
 * <li><b>The range</b> holds about 4 in 5: the Student t quantile for the cycles seen times the spread of the next cycle
 * (her own spread plus the uncertainty of her mean), so it is wider while it knows little.</li>
 * </ul>
 */
final class BayesMethod implements Method {
    static final double MU0 = 29.3, TAU0 = 4.5, SIGMA0 = 2.6, NU0 = 3;
    static final double KAPPA0 = (SIGMA0 / TAU0) * (SIGMA0 / TAU0);
    static final double SKIP_A = 2, SKIP_B = 20;
    static final double CLIP = 3;
    /** A cycle may count as two (a missed log) only after this many of her own cycles. */
    static final int SKIP_FROM = app.openring.core.analysis.Cycles.FEW;

    @Override
    public int id() {
        return Methods.BAYES;
    }

    /** The model after some cycles: its mean, the weight behind it, degrees of freedom and the sum of squares. */
    static final class State {
        double mu = MU0, kappa = KAPPA0, nu = NU0, ss = NU0 * SIGMA0 * SIGMA0;
        int seen, skipped;
        /** The cycles counted as two (a probably missed period), by index. */
        final java.util.List<Integer> asTwo = new java.util.ArrayList<Integer>();

        double sigma2() {
            return ss / nu;
        }

        /** The spread of the next cycle: her own plus the uncertainty of her mean. */
        double next() {
            return Math.sqrt(sigma2() * (1 + 1 / kappa));
        }

        double skipChance() {
            return (SKIP_A + skipped) / (SKIP_A + SKIP_B + seen);
        }

        /** The conjugate update with {@code x}, a mean of {@code w} cycles. */
        void add(double x, double w) {
            double k = kappa + w;
            ss += kappa * w / k * (x - mu) * (x - mu);
            mu = (kappa * mu + w * x) / k;
            kappa = k;
            nu += 1;
        }
    }

    static State learn(int[] lengths) {
        State s = new State();
        for (int i = 0; i < lengths.length; i++) {
            double l = lengths[i], s2 = s.sigma2(), p = s.skipChance();
            double v1 = s2 * (1 + 1 / s.kappa), v2 = s2 * (2 + 4 / s.kappa);
            // Student t, not normal: with few cycles or an irregular person a long cycle is still one cycle more often
            double one = (1 - p) * student(l, s.mu, v1, s.nu), two = p * student(l, 2 * s.mu, v2, s.nu);
            // her own mean, not the study's start: enough cycles, and the halves look like her cycles
            boolean halvesFit = Math.abs(l / 2 - s.mu) <= CLIP * Math.sqrt(v1);
            if (s.seen >= SKIP_FROM && halvesFit && two > one) {
                s.add(l / 2, 2);
                s.skipped++;
                s.asTwo.add(i);
            } else if (s.seen >= SKIP_FROM) {
                double edge = CLIP * Math.sqrt(v1);
                s.add(Math.max(s.mu - edge, Math.min(s.mu + edge, l)), 1);
            } else {
                s.add(l, 1); // her first cycles as they are: far from the study's start is not far from her

            }
            s.seen++;
        }
        return s;
    }

    @Override
    public Forecast predict(int[] lengths) {
        State s = learn(lengths);
        double spread = s.next(), range = t80(s.nu) * spread;
        return new Forecast(Methods.BAYES, s.mu, spread, range, Forecast.days(range), lengths.length, false);
    }

    /** The Student t density with {@code nu} degrees of freedom, centre {@code mu} and scale² {@code var}, up to a factor that both sides share. */
    static double student(double x, double mu, double var, double nu) {
        double z2 = (x - mu) * (x - mu) / var;
        return Math.pow(1 + z2 / nu, -(nu + 1) / 2) / Math.sqrt(var);
    }

    /** The two sided 80 % quantile of the Student t with {@code nu} degrees of freedom (Cornish Fisher, close from 3 on). */
    static double t80(double nu) {
        double z = app.openring.core.analysis.Cycles.Z80;
        return z + (z * z * z + z) / (4 * nu) + (5 * Math.pow(z, 5) + 16 * z * z * z + 3 * z) / (96 * nu * nu);
    }
}
