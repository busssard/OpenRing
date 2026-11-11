package app.openring.core.analysis.cycleforecast;

import java.util.Arrays;

import app.openring.core.analysis.Cycles;

/**
 * How close each method would have been on the person's own history (issue #383), walking forward: for every logged cycle
 * from the {@link #FROM}th + 1 on, each method gets only the lengths of the cycles before it and expects the start that
 * ends it; the error is the days between that expected day and the logged start, "in range" when it fell inside the range
 * the calendar would have shown. Cycles more than {@link Cycles#FAR} days from the middle value of the cycles before them
 * (probably a missed log; every method misses those) are not scored but still handed on as history, so each method deals with them as it
 * would in use. Nothing is stored; it is worked out from the log on each load.
 */
public final class Backtest {
    /** The first scored cycle has this many cycles before it (drip's minimum, Open Ring's narrow range). */
    public static final int FROM = Cycles.FEW;
    /** "Best for you" needs this many scored cycles; with fewer it stays with Open Ring. */
    public static final int MIN_SCORED = 3;

    /** One method's result. */
    public static final class Score {
        public final int method;
        /** Scored cycles, and how many fell inside the shown range. */
        public final int n, hits;
        /** The mean of the errors in days (NaN without a scored cycle), and the average range shown each way. */
        public final double mae, avgRange;
        /** For each scored cycle: its index in the history, the expected length, the logged one, the range shown. */
        public final int[] index, expected, actual, range;

        Score(int method, int[] index, int[] expected, int[] actual, int[] range) {
            this.method = method;
            this.index = index;
            this.expected = expected;
            this.actual = actual;
            this.range = range;
            n = index.length;
            int h = 0;
            double e = 0, r = 0;
            for (int i = 0; i < n; i++) {
                int err = Math.abs(actual[i] - expected[i]);
                e += err;
                r += range[i];
                if (err <= range[i]) h++;
            }
            hits = h;
            mae = n == 0 ? Double.NaN : e / n;
            avgRange = n == 0 ? Double.NaN : r / n;
        }

        /** Days the period came later than expected for scored cycle {@code i} (below 0: earlier). */
        public int late(int i) {
            return actual[i] - expected[i];
        }

        /** How often the start fell in the range, 0 to 1 (NaN without a scored cycle). */
        public double hitRate() {
            return n == 0 ? Double.NaN : (double) hits / n;
        }

        @Override
        public String toString() {
            return Methods.name(method) + ": " + n + " cycles, " + String.format(java.util.Locale.ROOT, "%.2f", mae) + " days off, " + hits + " in range";
        }
    }

    /** Each method's score, in {@link Methods#ALL}'s order. */
    public final Score[] scores;
    /** Cycles in the history, and those not scored because they lay far from the rest. */
    public final int cycles, farOut;

    private Backtest(Score[] scores, int cycles, int farOut) {
        this.scores = scores;
        this.cycles = cycles;
        this.farOut = farOut;
    }

    /** Every method in {@link Methods#ALL} on {@code lengths} (each cycle's length, oldest first). */
    public static Backtest run(int[] lengths) {
        return run(lengths, Methods.ALL);
    }

    public static Backtest run(int[] lengths, Method[] methods) {
        int far = 0;
        boolean[] scored = new boolean[lengths.length];
        int m = 0;
        for (int i = FROM; i < lengths.length; i++) {
            // far from the middle value of the cycles before it (not of all, so nothing later decides what is scored)
            if (Methods.far(lengths[i], Methods.median(Arrays.copyOf(lengths, i)))) far++;
            else {
                scored[i] = true;
                m++;
            }
        }
        Score[] out = new Score[methods.length];
        for (int k = 0; k < methods.length; k++) {
            int[] index = new int[m], expected = new int[m], actual = new int[m], range = new int[m];
            int j = 0;
            for (int i = FROM; i < lengths.length; i++) {
                if (!scored[i]) continue;
                // only the cycles before this one: the method never sees it or anything after it
                Forecast f = methods[k].predict(Arrays.copyOf(lengths, i));
                index[j] = i;
                expected[j] = f.days();
                actual[j] = lengths[i];
                range[j] = f.shown;
                j++;
            }
            out[k] = new Score(methods[k].id(), index, expected, actual, range);
        }
        return new Backtest(out, lengths.length, far);
    }

    /** The score of the method {@code id}, null when it was not run. */
    public Score of(int id) {
        for (Score s : scores) if (s.method == id) return s;
        return null;
    }

    /** How many cycles were scored (the same for every method). */
    public int scored() {
        return scores.length == 0 ? 0 : scores[0].n;
    }

    /**
     * "Best for you": the method with the smallest mean error; a tie goes to the one listed first (Open Ring, so nothing
     * moves without a reason). With fewer than {@link #MIN_SCORED} scored cycles Open Ring.
     */
    public int best() {
        if (scored() < MIN_SCORED) return Methods.OPEN_RING;
        Score b = null;
        for (Score s : scores) if (b == null || s.mae < b.mae - 1e-9) b = s;
        return b == null ? Methods.OPEN_RING : b.method;
    }
}
