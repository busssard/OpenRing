import app.openring.core.analysis.Baselines;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The readiness baselines in {@code core/analysis/Baselines} (issue #292, phase 1.5): bit for bit what the inline code of
 * {@code data/Analyzer} gave before the move (a verbatim copy of it is below, {@link #old}), on 2000 seeded made up histories
 * with missing values, fewer than 3 nights, exactly 3, 14 and 90 and more, and nights without a value tonight; the windows and
 * the minimum of 3 at their edges; and {@link Baselines#usual}, the usual value phase 2 compares each night with. Made up
 * numbers only. Run with tests/run_tests.sh.
 */
public class BaselinesTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + (detail == null ? "" : "  (" + detail + ")"));
        }
    }

    static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /** One stored main night as Analyzer reads it: NaN for a field the night does not have. */
    static final class N {
        double lowestHr = Double.NaN, hrv = Double.NaN, temp = Double.NaN;
    }

    /** {@code Analyzer.lastValues} on plain nights. */
    static double[] lastValues(List<N> list, int field, int n) {
        int from = Math.max(0, list.size() - n);
        double[] out = new double[list.size() - from];
        for (int i = from; i < list.size(); i++) {
            N o = list.get(i);
            out[i - from] = field == 0 ? o.lowestHr : field == 1 ? o.hrv : o.temp;
        }
        return out;
    }

    /**
     * The readiness block of {@code Analyzer.analyzeDay} as it was on dev 8791631 (lines 1106 to 1137), copied verbatim
     * with {@code Json.d(mainNight, ...)} replaced by the arguments and {@code lastValues(prevNights, ...)} by the list.
     */
    static Scores.ReadinessInput old(List<N> prevNights, double hrvTonightIn, double tempIn, double lowIn, double s0In, double s1In, double[] tempDevOut) {
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        double tempDev = Double.NaN;
        double[] rhrHist = lastValues(prevNights, 0, 14);
        if (Stats.countFinite(rhrHist) >= 3) {
            ri.rhrBaseline = Stats.mean(rhrHist);
            ri.rhrSd = Stats.sd(rhrHist);
        }
        double hrvTonight = hrvTonightIn;
        double[] hrv14 = lastValues(prevNights, 1, 13);
        double[] hrv90 = lastValues(prevNights, 1, 90);
        if (!Double.isNaN(hrvTonight) && Stats.countFinite(hrv90) >= 3) {
            // two week weighted average (newest heaviest) vs the long term average
            double ws = 0, w = 0;
            double[] recent = new double[hrv14.length + 1];
            System.arraycopy(hrv14, 0, recent, 0, hrv14.length);
            recent[hrv14.length] = hrvTonight;
            for (int i = 0; i < recent.length; i++) {
                if (Double.isNaN(recent[i])) continue;
                double wi = 1 + i;
                ws += wi * recent[i];
                w += wi;
            }
            ri.hrvRecent = ws / w;
            ri.hrvBaseline = Stats.mean(hrv90);
        }
        double temp = tempIn;
        double[] tempHist = lastValues(prevNights, 2, 30);
        if (!Double.isNaN(temp) && Stats.countFinite(tempHist) >= 3) {
            tempDev = temp - Stats.median(tempHist);
            ri.tempDev = tempDev;
        }
        double low = lowIn, s0 = s0In, s1 = s1In;
        if (!Double.isNaN(low) && s1 > s0) ri.recoveryFraction = Stats.clamp((low - s0) / (s1 - s0), 0, 1);
        tempDevOut[0] = tempDev;
        return ri;
    }

    /** The new way, as Analyzer now calls it: the last 90 values of each field, then {@link Baselines#readiness}. */
    static Scores.ReadinessInput now(List<N> prevNights, double hrv, double temp, double low, double s0, double s1, double[] tempDevOut) {
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        int n = Baselines.HRV_LONG_NIGHTS;
        Baselines.Result b = Baselines.readiness(lastValues(prevNights, 0, n), lastValues(prevNights, 1, n), lastValues(prevNights, 2, n), hrv, temp, low, s0, s1);
        b.into(ri);
        tempDevOut[0] = b.tempDev;
        return ri;
    }

    /** A value that is missing with the given chance; now and then one far off (a bad night), never anything but finite or NaN. */
    static double value(Random r, double mean, double sd, double missing) {
        if (r.nextDouble() < missing) return Double.NaN;
        double v = mean + r.nextGaussian() * sd;
        if (r.nextDouble() < 0.02) v += r.nextGaussian() * sd * 8;
        return v;
    }

    static final int[] SIZES = {0, 1, 2, 3, 4, 13, 14, 15, 29, 30, 31, 89, 90, 91, 120};

    static String differs(Scores.ReadinessInput a, double ta, Scores.ReadinessInput b, double tb) {
        if (!same(a.rhrBaseline, b.rhrBaseline)) return "rhrBaseline " + a.rhrBaseline + " " + b.rhrBaseline;
        if (!same(a.rhrSd, b.rhrSd)) return "rhrSd " + a.rhrSd + " " + b.rhrSd;
        if (!same(a.hrvRecent, b.hrvRecent)) return "hrvRecent " + a.hrvRecent + " " + b.hrvRecent;
        if (!same(a.hrvBaseline, b.hrvBaseline)) return "hrvBaseline " + a.hrvBaseline + " " + b.hrvBaseline;
        if (!same(a.tempDev, b.tempDev)) return "ri.tempDev " + a.tempDev + " " + b.tempDev;
        if (!same(ta, tb)) return "tempDev " + ta + " " + tb;
        if (!same(a.recoveryFraction, b.recoveryFraction)) return "recoveryFraction " + a.recoveryFraction + " " + b.recoveryFraction;
        // the fields the baselines never touch stay at their defaults
        if (!same(b.rhr, Double.NaN) || !same(b.sleepScore, Double.NaN) || !same(b.sleepBalance, Double.NaN)) return "other fields";
        return null;
    }

    /** 2000 seeded histories: the new code gives the old values bit for bit; counts how many of each case were covered. */
    static void sameAsBefore() {
        Random r = new Random(292);
        int runs = 2000, same = 0;
        int few = 0, three = 0, fourteen = 0, ninety = 0, nanTonight = 0, withRhr = 0, withHrv = 0, withTemp = 0, withRecovery = 0;
        String first = null;
        for (int k = 0; k < runs; k++) {
            int size = k < SIZES.length * 40 ? SIZES[k % SIZES.length] : r.nextInt(130);
            // some histories have most values missing (so exactly 2, 3 or 4 finite ones in a window happen often)
            double missing = k % 5 == 0 ? 0.85 : k % 5 == 1 ? 0.3 : k % 5 == 2 ? 0.05 : 0;
            double rhrMean = 45 + r.nextDouble() * 25, hrvMean = 20 + r.nextDouble() * 80, tempMean = 35 + r.nextDouble() * 1.5;
            List<N> nights = new ArrayList<N>();
            for (int i = 0; i < size; i++) {
                N o = new N();
                o.lowestHr = value(r, rhrMean, 4, missing);
                o.hrv = value(r, hrvMean, hrvMean * 0.2, missing);
                o.temp = value(r, tempMean, 0.3, missing);
                nights.add(o);
            }
            double hrv = k % 7 == 0 ? Double.NaN : value(r, hrvMean, hrvMean * 0.2, 0);
            double temp = k % 11 == 0 ? Double.NaN : value(r, tempMean, 0.3, 0);
            double s0 = 1.7e9 + r.nextInt(86400 * 365);
            double s1 = k % 13 == 0 ? s0 - r.nextInt(3600) : s0 + 3600 * (2 + r.nextDouble() * 8);
            double low = k % 9 == 0 ? Double.NaN : s0 - 1800 + r.nextDouble() * (s1 - s0 + 3600);
            double[] ta = new double[1], tb = new double[1];
            Scores.ReadinessInput a = old(nights, hrv, temp, low, s0, s1, ta);
            Scores.ReadinessInput b = now(nights, hrv, temp, low, s0, s1, tb);
            String d = differs(a, ta[0], b, tb[0]);
            if (d == null) same++;
            else if (first == null) first = "history " + k + " (" + size + " nights): " + d;
            int finiteRhr = Stats.countFinite(lastValues(nights, 0, 14));
            if (finiteRhr < 3) few++;
            if (finiteRhr == 3) three++;
            if (size == 14) fourteen++;
            if (size >= 90) ninety++;
            if (Double.isNaN(hrv)) nanTonight++;
            if (!Double.isNaN(a.rhrBaseline)) withRhr++;
            if (!Double.isNaN(a.hrvRecent)) withHrv++;
            if (!Double.isNaN(a.tempDev)) withTemp++;
            if (!Double.isNaN(a.recoveryFraction)) withRecovery++;
        }
        System.out.println("  " + runs + " histories: " + few + " under 3 nights, " + three + " with exactly 3, " + fourteen + " of 14, " + ninety
                + " of 90 or more, " + nanTonight + " without HRV tonight; values set: rhr " + withRhr + ", hrv " + withHrv + ", temp " + withTemp
                + ", recovery " + withRecovery);
        check("2000 made up histories: every baseline bit for bit as the old Analyzer code", same == runs, first);
        check("the histories cover every case (under 3, exactly 3, 14, 90, NaN tonight, each baseline set and unset)",
                few > 100 && three > 20 && fourteen > 20 && ninety > 100 && nanTonight > 100 && withRhr > 500 && withRhr < runs
                        && withHrv > 500 && withHrv < runs && withTemp > 500 && withTemp < runs && withRecovery > 500 && withRecovery < runs, null);
    }

    static double[] of(double... v) {
        return v;
    }

    static double[] filled(int n, double v) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, v);
        return out;
    }

    /** The windows and the minimum of 3 at their edges, by hand. */
    static void windows() {
        double nan = Double.NaN;
        // lowest heart rate: only the last 14 count, 3 of them needed
        double[] rhr = new double[20];
        for (int i = 0; i < 20; i++) rhr[i] = i < 6 ? 100 : 50;
        Baselines.Result r = Baselines.readiness(rhr, new double[0], new double[0], nan, nan, nan, 0, 0);
        check("rhr: the mean of the last 14 nights only", r.rhrBaseline == 50 && r.rhrSd == 0, r.rhrBaseline);
        r = Baselines.readiness(of(50, nan, 52), new double[0], new double[0], nan, nan, nan, 0, 0);
        check("rhr: 2 nights with a value are too few", Double.isNaN(r.rhrBaseline) && Double.isNaN(r.rhrSd), r.rhrBaseline);
        r = Baselines.readiness(of(50, 51, 52), new double[0], new double[0], nan, nan, nan, 0, 0);
        check("rhr: 3 nights are enough", r.rhrBaseline == 51, r.rhrBaseline);
        // a night 15 back is outside: 3 values but only 2 in the window
        double[] edge = filled(15, nan);
        edge[0] = 40;
        edge[13] = 50;
        edge[14] = 52;
        check("rhr: a value 15 nights back is outside the window", Double.isNaN(Baselines.readiness(edge, new double[0], new double[0], nan, nan, nan, 0, 0)
                .rhrBaseline), null);

        // HRV: newest heaviest over 13 nights and tonight, against the mean of 90
        r = Baselines.readiness(new double[0], of(10, 20, 30), new double[0], 40, nan, nan, 0, 0);
        check("hrv: weights 1, 2, 3 and 4 for tonight", r.hrvRecent == (10 + 40 + 90 + 160) / 10.0 && r.hrvBaseline == 20, r.hrvRecent);
        r = Baselines.readiness(new double[0], of(10, 20, 30), new double[0], nan, nan, nan, 0, 0);
        check("hrv: none without a value tonight", Double.isNaN(r.hrvRecent) && Double.isNaN(r.hrvBaseline), r.hrvRecent);
        r = Baselines.readiness(new double[0], of(10, nan, 30), new double[0], 40, nan, nan, 0, 0);
        check("hrv: 2 earlier nights with a value are too few", Double.isNaN(r.hrvRecent), r.hrvRecent);
        double[] hrv = filled(100, 100);
        for (int i = 0; i < 10; i++) hrv[i] = 0; // outside the 90
        for (int i = 87; i < 100; i++) hrv[i] = 50; // the 13 recent ones
        r = Baselines.readiness(new double[0], hrv, new double[0], 50, nan, nan, 0, 0);
        check("hrv: the recent average reads 13 nights, the baseline 90", r.hrvRecent == 50 && r.hrvBaseline == (77 * 100 + 13 * 50) / 90.0,
                r.hrvRecent + " " + r.hrvBaseline);

        // temperature against the median of 30
        double[] temp = filled(31, 36.0);
        temp[0] = 40; // outside
        r = Baselines.readiness(new double[0], new double[0], temp, nan, 36.5, nan, 0, 0);
        check("temp: tonight minus the median of the last 30 nights", r.tempDev == 0.5, r.tempDev);
        r = Baselines.readiness(new double[0], new double[0], of(36, nan, 36.2), nan, 36.5, nan, 0, 0);
        check("temp: 2 nights are too few", Double.isNaN(r.tempDev), r.tempDev);
        r = Baselines.readiness(new double[0], new double[0], of(36, 36.2, 36.1), nan, nan, nan, 0, 0);
        check("temp: none without a value tonight", Double.isNaN(r.tempDev), r.tempDev);

        // where the lowest heart rate came, clamped to the night
        r = Baselines.readiness(new double[0], new double[0], new double[0], nan, nan, 1300, 1000, 2000);
        check("recovery: 0.3 of the way through the night", Math.abs(r.recoveryFraction - 0.3) < 1e-12, r.recoveryFraction);
        r = Baselines.readiness(new double[0], new double[0], new double[0], nan, nan, 2500, 1000, 2000);
        check("recovery: after the wake up counts as 1", r.recoveryFraction == 1, r.recoveryFraction);
        r = Baselines.readiness(new double[0], new double[0], new double[0], nan, nan, 1500, 2000, 2000);
        check("recovery: none for a night without length", Double.isNaN(r.recoveryFraction), r.recoveryFraction);

        // into() fills the readiness input's fields of the same names, and nothing else
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        ri.rhr = 48;
        Baselines.Result full = Baselines.readiness(of(50, 51, 52), of(10, 20, 30), of(36, 36.2, 36.1), 40, 36.5, 1300, 1000, 2000);
        full.into(ri);
        check("into: the six baselines and the rest untouched", ri.rhrBaseline == 51 && ri.hrvBaseline == 20 && ri.hrvRecent == full.hrvRecent
                && ri.tempDev == full.tempDev && ri.recoveryFraction == full.recoveryFraction && ri.rhrSd == full.rhrSd && ri.rhr == 48
                && Double.isNaN(ri.sleepBalance), null);
    }

    /** Baselines.usual, phase 2's "your usual": median of the last 30 nights, at least 3 with a value. */
    static void usual() {
        double nan = Double.NaN;
        check("usual: none from no nights", Double.isNaN(Baselines.usual(new double[0])), null);
        check("usual: none from 2 nights with a value", Double.isNaN(Baselines.usual(of(1, nan, 3, nan))), null);
        check("usual: the median of 3", Baselines.usual(of(5, 1, 3)) == 3, Baselines.usual(of(5, 1, 3)));
        check("usual: the median of 4 is the middle two's mean", Baselines.usual(of(4, 1, 3, 2)) == 2.5, null);
        double[] v = filled(40, 10);
        for (int i = 30; i < 40; i++) v[i] = nan;
        check("usual: a night without the value still counts as one of the 30 (here 20 values in the window)", Baselines.usual(v) == 10, null);
        check("usual: values more than 30 nights back do not count", Double.isNaN(Baselines.usual(filledTail(1000, 2, 30))), null);
        double[] t = new double[45];
        for (int i = 0; i < t.length; i++) t[i] = 36 + (i % 7) * 0.1;
        check("usual: is the temperature baseline of readiness, bit for bit", same(36.4 - Baselines.usual(t),
                Baselines.readiness(new double[0], new double[0], t, nan, 36.4, nan, 0, 0).tempDev), null);
        double[] input = of(3, 1, 2);
        Baselines.usual(input);
        check("usual: leaves the array it reads as it was", input[0] == 3 && input[1] == 1 && input[2] == 2, null);
    }

    /** {@code head} values, then {@code tail} missing nights: the values lie just outside a window of {@code tail}. */
    static double[] filledTail(double value, int head, int tail) {
        double[] out = new double[head + tail];
        for (int i = 0; i < out.length; i++) out[i] = i < head ? value : Double.NaN;
        return out;
    }

    public static void main(String[] args) {
        sameAsBefore();
        windows();
        usual();
        System.out.println("BaselinesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
