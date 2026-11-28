import app.openring.core.Texts;
import app.openring.core.analysis.DaySummary;
import app.openring.core.analysis.Scores;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * The sentences of the tabs, {@code core/analysis/DaySummary} (issue #293, phase 0b): the Readiness headline, the readiness
 * and sleep debt line under Today's day line, and each body signal's "usual" line with its colour. Each against a copy of the
 * code ui/Pages ran before the move, on many thousand seeded made up days, and pinned sentences for every kind of day. Run with
 * tests/run_tests.sh.
 */
public class DaySummaryTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    public static void main(String[] args) {
        headline();
        digest();
        signal();
        sources();
        System.out.println("DaySummaryTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ the code before the move (ui/Pages, 25 November)

    /** A stand in for the stored JSON the old code read: a missing number is NaN (or the default), a missing text "". */
    static final class J {
        final Map<String, Object> m = new HashMap<String, Object>();

        J put(String k, Object v) {
            m.put(k, v);
            return this;
        }

        String optString(String k) {
            Object v = m.get(k);
            return v == null ? "" : (String) v;
        }

        double optDouble(String k) {
            return optDouble(k, Double.NaN);
        }

        double optDouble(String k, double def) {
            Object v = m.get(k);
            return v == null ? def : (Double) v;
        }

        boolean optBoolean(String k) {
            Object v = m.get(k);
            return v != null && (Boolean) v;
        }

        @SuppressWarnings("unchecked")
        List<J> optJSONArray(String k) {
            return (List<J>) m.get(k);
        }

        J optJSONObject(String k) {
            return (J) m.get(k);
        }
    }

    /** Pages.headline before the move, with d.score("readiness") and d.score("sleep") as values. */
    static String oldHeadline(double readiness, double sleep) {
        double r = readiness;
        if (!Double.isNaN(r)) {
            if (r >= Scores.OPTIMAL) return "You're well recovered. A good day to push a little.";
            if (r >= Scores.GOOD) return "You're in good shape. Keep a steady pace today.";
            return "Your body is asking for rest. Take it easy today.";
        }
        double s = sleep;
        if (!Double.isNaN(s)) return s >= Scores.OPTIMAL ? "You slept really well." : s >= Scores.GOOD ? "You slept reasonably well." : "Last night's sleep was short or restless.";
        return "Keep wearing your ring to build your picture.";
    }

    /**
     * Pages.digest before the move: {@code rd} the readiness score object, {@code day} the stored day, {@code watch} for
     * HistoryViews.watch(d) and {@code w} for d.deviceHistory.debt(d.date); Ui.hm is Texts.hm, Locale.getDefault() is
     * {@code locale}.
     */
    static String oldDigest(J rd, J day, boolean watch, double[] w, boolean forLine, boolean noDebt, Locale locale) {
        StringBuilder sb = new StringBuilder();
        if (rd != null) {
            List<J> cs = rd.optJSONArray("contributors");
            J worst = null, best = null;
            for (int i = 0; cs != null && i < cs.size(); i++) {
                J o = cs.get(i);
                if (worst == null || o.optDouble("score") < worst.optDouble("score")) worst = o;
                if (best == null || o.optDouble("score") > best.optDouble("score")) best = o;
            }
            String line = best == null ? "" : Texts.contributorLine(best.optString("label"), worst.optString("label"),
                    worst.optDouble("score"), locale);
            sb.append(line.isEmpty() ? ". " : line + " ");
            if (rd.optBoolean("provisional")) sb.append("Your baselines are still forming, so scores will become more personal over the next two weeks. ");
        }
        J debt = day == null ? null : day.optJSONObject("sleep_debt");
        if (noDebt) {
            // the day's sentence above already gives the sleep debt
        } else if (debt != null && debt.optBoolean("valid")) {
            double m = debt.optDouble("debt_min", 0);
            if (m >= 180) sb.append("You carry about ").append(Texts.hm(m)).append(" of sleep debt. ");
        } else if (debt == null && watch) {
            if (w[1] > 0 && w[0] >= 180) sb.append("You carry about ").append(Texts.hm(w[0])).append(" of sleep debt. ");
        }
        if (sb.length() == 0 && !forLine) sb.append("Synced and analysed on this phone. Nothing left the device.");
        return sb.toString().trim();
    }

    /** Pages.signalRow's line and colour before the move (TEXT_3, GOOD or WARN), Locale.getDefault() as {@code locale}. */
    static String[] oldSignal(double value, double usual, int dp, boolean lowerIsBetter, Locale locale) {
        String u;
        String col = "TEXT_3";
        if (Double.isNaN(usual) || Double.isNaN(value)) {
            u = "usual value after a few nights";
        } else {
            double diff = value - usual;
            boolean better = lowerIsBetter ? diff < 0 : diff > 0;
            u = "usual " + String.format(locale, "%." + dp + "f", usual) + (Math.abs(diff) < Math.pow(10, -dp) ? "" : (diff > 0 ? ", higher" : ", lower"));
            if (Math.abs(diff) >= Math.max(Math.abs(usual) * 0.05, Math.pow(10, -dp))) col = better ? "GOOD" : "WARN";
        }
        return new String[] {u, col};
    }

    // ------------------------------------------------------------------ the new path, as ui/TodayPage and ReadinessPage call it

    /** TodayPage.digest now: the stored values read as there, the sentence from core. */
    static String newDigest(J rd, J day, boolean watch, double[] w, boolean forLine, boolean noDebt, Locale locale) {
        String[] labels = null;
        double[] scores = null;
        if (rd != null) {
            List<J> cs = rd.optJSONArray("contributors");
            int n = cs == null ? 0 : cs.size();
            labels = new String[n];
            scores = new double[n];
            for (int i = 0; i < n; i++) {
                J o = cs.get(i);
                labels[i] = o.optString("label");
                scores[i] = o.optDouble("score");
            }
        }
        J debt = day == null ? null : day.optJSONObject("sleep_debt");
        double[] watchDebt = !noDebt && debt == null && watch ? w : null;
        double debtMin = DaySummary.debtMin(debt != null, debt != null && debt.optBoolean("valid"),
                debt == null ? Double.NaN : debt.optDouble("debt_min", 0), watchDebt);
        return DaySummary.digest(labels, scores, rd != null && rd.optBoolean("provisional"), debtMin, noDebt, forLine, locale);
    }

    static String[] newSignal(double value, double usual, int dp, boolean lowerIsBetter, Locale locale) {
        int tone = DaySummary.signalTone(value, usual, dp, lowerIsBetter);
        return new String[] {DaySummary.signalLine(value, usual, dp, locale),
            tone == DaySummary.BETTER ? "GOOD" : tone == DaySummary.WORSE ? "WARN" : "TEXT_3"};
    }

    // ------------------------------------------------------------------ made up days

    static final Locale[] LOCALES = {Locale.US, Locale.GERMANY, Locale.ROOT, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-EG")};
    static final String[] LABELS = {"Resting heart rate", "HRV balance", "Body temperature", "Recovery index", "Sleep", "Previous day activity",
        "Sleep balance", "Activity balance", "", "Ignored"};
    static final double[] EDGES = {Double.NaN, 0, 59.5, 69, 69.999, 70, 70.0001, 84.9, 85, 100, -1};

    static double score(Random r) {
        int k = r.nextInt(5);
        if (k == 0) return EDGES[r.nextInt(EDGES.length)];
        if (k == 1) return r.nextInt(101);
        return r.nextDouble() * 100;
    }

    static J randomReadiness(Random r) {
        if (r.nextInt(6) == 0) return null;
        J rd = new J();
        if (r.nextInt(8) != 0) {
            List<J> cs = new ArrayList<J>();
            int n = r.nextInt(8);
            double tie = score(r);
            for (int i = 0; i < n; i++) {
                J o = new J();
                if (r.nextInt(10) != 0) o.put("label", LABELS[r.nextInt(LABELS.length)]);
                if (r.nextInt(12) != 0) o.put("score", r.nextInt(4) == 0 ? tie : score(r));
                cs.add(o);
            }
            rd.put("contributors", cs);
        }
        if (r.nextBoolean()) rd.put("provisional", r.nextBoolean());
        return rd;
    }

    static J randomDay(Random r) {
        if (r.nextInt(8) == 0) return null;
        J day = new J();
        if (r.nextInt(3) != 0) {
            J debt = new J();
            if (r.nextInt(4) != 0) debt.put("valid", r.nextInt(4) != 0);
            if (r.nextInt(6) != 0) {
                double[] near = {179, 179.4999, 179.5, 179.9999, 180, 180.0001, 0, Double.NaN};
                debt.put("debt_min", r.nextBoolean() ? near[r.nextInt(near.length)] : r.nextDouble() * 900);
            }
            day.put("sleep_debt", debt);
        }
        return day;
    }

    static double[] randomWatchDebt(Random r) {
        double[] near = {179.9, 180, 181, 0, 600};
        return new double[] {r.nextBoolean() ? near[r.nextInt(near.length)] : r.nextDouble() * 900, r.nextInt(4) == 0 ? 0 : r.nextInt(15)};
    }

    // ------------------------------------------------------------------ the checks

    static void headline() {
        Random r = new Random(293);
        int differs = 0, n = 20000;
        for (int i = 0; i < n; i++) {
            double a = r.nextInt(4) == 0 ? Double.NaN : score(r), b = r.nextInt(4) == 0 ? Double.NaN : score(r);
            if (!oldHeadline(a, b).equals(DaySummary.headline(a, b))) differs++;
        }
        eq("headline: the same sentence as before on " + n + " made up days", differs, 0);
        eq("headline: no score", DaySummary.headline(Double.NaN, Double.NaN), "Keep wearing your ring to build your picture.");
        eq("headline: readiness 85 is optimal", DaySummary.headline(85, Double.NaN), "You're well recovered. A good day to push a little.");
        eq("headline: readiness 84.9 is good", DaySummary.headline(84.9, 95), "You're in good shape. Keep a steady pace today.");
        eq("headline: readiness 70 is good", DaySummary.headline(70, Double.NaN), "You're in good shape. Keep a steady pace today.");
        eq("headline: readiness 69 asks for rest", DaySummary.headline(69, 100), "Your body is asking for rest. Take it easy today.");
        eq("headline: readiness wins over sleep", DaySummary.headline(30, 90), "Your body is asking for rest. Take it easy today.");
        eq("headline: only sleep, 90", DaySummary.headline(Double.NaN, 90), "You slept really well.");
        eq("headline: only sleep, 75", DaySummary.headline(Double.NaN, 75), "You slept reasonably well.");
        eq("headline: only sleep, 50", DaySummary.headline(Double.NaN, 50), "Last night's sleep was short or restless.");
    }

    static void digest() {
        Random r = new Random(1793);
        int differs = 0, n = 50000, said = 0, filler = 0, empty = 0;
        String example = null;
        for (int i = 0; i < n; i++) {
            J rd = randomReadiness(r), day = randomDay(r);
            boolean watch = r.nextInt(3) == 0, forLine = r.nextBoolean(), noDebt = r.nextInt(4) == 0;
            double[] w = randomWatchDebt(r);
            Locale l = LOCALES[r.nextInt(LOCALES.length)];
            String before = oldDigest(rd, day, watch, w, forLine, noDebt, l), now = newDigest(rd, day, watch, w, forLine, noDebt, l);
            if (!before.equals(now)) {
                differs++;
                if (example == null) example = before + " | " + now;
            }
            if (before.contains("sleep debt")) said++;
            if (before.equals(DaySummary.NOTHING_LEFT)) filler++;
            if (before.isEmpty()) empty++;
        }
        if (example != null) System.out.println("  first difference: " + example);
        eq("digest: the same text as before on " + n + " made up days", differs, 0);
        check("digest: the made up days reach every branch (" + said + " with sleep debt, " + filler + " filler, " + empty + " empty)",
                said > 1000 && filler > 100 && empty > 100);

        Locale us = Locale.US;
        String[] labels = {"Resting heart rate", "HRV balance", "Recovery index"};
        eq("digest: nothing known, standing alone", DaySummary.digest(null, null, false, Double.NaN, false, false, us), DaySummary.NOTHING_LEFT);
        eq("digest: nothing known, under the day's line", DaySummary.digest(null, null, false, Double.NaN, false, true, us), "");
        eq("digest: a score without contributors", DaySummary.digest(new String[0], new double[0], false, Double.NaN, false, true, us), ".");
        eq("digest: all good, only the strongest", DaySummary.digest(labels, new double[] {80, 95, 70}, false, Double.NaN, false, true, us),
                "HRV balance is your strongest contributor.");
        eq("digest: one under Good needs attention, lower case in the sentence",
                DaySummary.digest(labels, new double[] {69, 95, 80}, false, Double.NaN, false, true, us),
                "HRV balance is your strongest contributor, while resting heart rate needs attention.");
        eq("digest: a tie takes the first", DaySummary.digest(labels, new double[] {50, 90, 50}, false, Double.NaN, false, true, us),
                "HRV balance is your strongest contributor, while resting heart rate needs attention.");
        eq("digest: provisional", DaySummary.digest(labels, new double[] {80, 95, 70}, true, Double.NaN, false, true, us),
                "HRV balance is your strongest contributor. " + DaySummary.PROVISIONAL);
        eq("digest: sleep debt from 180 min", DaySummary.digest(null, null, false, 180, false, true, us), "You carry about 3h 0m of sleep debt.");
        eq("digest: no sleep debt under 180 min", DaySummary.digest(null, null, false, 179.9, false, false, us), DaySummary.NOTHING_LEFT);
        eq("digest: the day's line said the sleep debt", DaySummary.digest(null, null, false, 400, true, true, us), "");
        eq("digest: everything at once", DaySummary.digest(labels, new double[] {60, 95, 80}, true, 245, false, true, us),
                "HRV balance is your strongest contributor, while resting heart rate needs attention. " + DaySummary.PROVISIONAL
                        + " You carry about 4h 5m of sleep debt.");
        eq("debt: the ring's valid debt", DaySummary.debtMin(true, true, 200, new double[] {500, 9}), 200.0);
        eq("debt: the ring's debt not valid yet says none, also with a watch", DaySummary.debtMin(true, false, 500, new double[] {500, 9}), Double.NaN);
        eq("debt: only the watch, with nights", DaySummary.debtMin(false, false, Double.NaN, new double[] {300, 4}), 300.0);
        eq("debt: only the watch, no night", DaySummary.debtMin(false, false, Double.NaN, new double[] {300, 0}), Double.NaN);
        eq("debt: neither", DaySummary.debtMin(false, false, Double.NaN, null), Double.NaN);
    }

    static void signal() {
        Random r = new Random(905);
        int differs = 0, n = 100000, good = 0, warn = 0, plain = 0;
        String example = null;
        double[][] kinds = {{40, 90}, {10, 150}, {10, 25}, {88, 100}, {-2, 2}};
        for (int i = 0; i < n; i++) {
            double[] k = kinds[r.nextInt(kinds.length)];
            int dp = r.nextInt(3) == 0 ? 1 : 0;
            double usual = r.nextInt(15) == 0 ? Double.NaN : r.nextInt(20) == 0 ? 0 : k[0] + r.nextDouble() * (k[1] - k[0]);
            double value;
            int e = r.nextInt(8);
            double step = Math.pow(10, -dp);
            if (e == 0) value = Double.NaN;
            else if (e == 1) value = usual * (r.nextBoolean() ? 1.05 : 0.95); // the 5 % edge
            else if (e == 2) value = usual + (r.nextBoolean() ? step : -step) * (r.nextBoolean() ? 1 : 0.999); // one shown digit
            else if (e == 3) value = usual;
            else if (e == 4) value = Math.round(usual * (0.9 + r.nextDouble() * 0.2) / step) * step;
            else value = k[0] + r.nextDouble() * (k[1] - k[0]);
            boolean lower = r.nextBoolean();
            Locale l = LOCALES[r.nextInt(LOCALES.length)];
            String[] before = oldSignal(value, usual, dp, lower, l), now = newSignal(value, usual, dp, lower, l);
            if (!before[0].equals(now[0]) || !before[1].equals(now[1])) {
                differs++;
                if (example == null) example = before[0] + " " + before[1] + " | " + now[0] + " " + now[1];
            }
            if ("GOOD".equals(before[1])) good++;
            else if ("WARN".equals(before[1])) warn++;
            else plain++;
        }
        if (example != null) System.out.println("  first difference: " + example);
        eq("signal: the same line and colour as before on " + n + " made up nights", differs, 0);
        check("signal: the made up nights reach every colour (" + good + " better, " + warn + " worse, " + plain + " plain)",
                good > 5000 && warn > 5000 && plain > 5000);

        Locale us = Locale.US;
        eq("signal: no usual yet", DaySummary.signalLine(55, Double.NaN, 0, us), "usual value after a few nights");
        eq("signal: no value", DaySummary.signalLine(Double.NaN, 55, 0, us), "usual value after a few nights");
        eq("signal: missing values are plain", DaySummary.signalTone(Double.NaN, 55, 0, true), DaySummary.SAME);
        eq("signal: the same", DaySummary.signalLine(55, 55, 0, us), "usual 55");
        eq("signal: under one shown digit is the same", DaySummary.signalLine(55.4, 55, 0, us), "usual 55");
        eq("signal: one digit higher", DaySummary.signalLine(56, 55, 0, us), "usual 55, higher");
        eq("signal: one digit lower", DaySummary.signalLine(54, 55, 0, us), "usual 55, lower");
        eq("signal: one decimal", DaySummary.signalLine(14.8, 14.5, 1, us), "usual 14.5, higher");
        eq("signal: the phone's decimal sign", DaySummary.signalLine(14.8, 14.5, 1, Locale.GERMANY), "usual 14,5, higher");
        eq("signal: 4 % away is plain", DaySummary.signalTone(52, 50, 0, true), DaySummary.SAME);
        eq("signal: 5 % away counts", DaySummary.signalTone(52.5, 50, 0, true), DaySummary.WORSE);
        eq("signal: 5 % lower heart rate is better", DaySummary.signalTone(47.5, 50, 0, true), DaySummary.BETTER);
        eq("signal: 5 % higher HRV is better", DaySummary.signalTone(42, 40, 0, false), DaySummary.BETTER);
        eq("signal: 5 % lower HRV is worse", DaySummary.signalTone(38, 40, 0, false), DaySummary.WORSE);
        eq("signal: under 5 % stays plain", DaySummary.signalTone(10.4, 10, 1, true), DaySummary.SAME);
        eq("signal: past 5 % but under one shown digit stays plain", DaySummary.signalTone(1.06, 1, 1, true), DaySummary.SAME);
        eq("signal: one shown digit is enough when 5 % is less", DaySummary.signalTone(1.1, 1, 1, true), DaySummary.WORSE);
        eq("signal: usual 0", DaySummary.signalTone(0.5, 0, 0, false), DaySummary.SAME);
        eq("signal: usual 0, one digit away", DaySummary.signalTone(1, 0, 0, false), DaySummary.BETTER);
    }

    /** The screens call core now and keep no copy of the rules (read from the sources, which the JVM cannot run). */
    static void sources() {
        try {
            String root = "app/src/main/java/app/openring/ui/";
            String today = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "TodayPage.java").toPath()), "UTF-8");
            String ready = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "ReadinessPage.java").toPath()), "UTF-8");
            check("Today's digest comes from core", today.contains("return DaySummary.digest(") && !today.contains("of sleep debt"));
            // the wrapper's inputs, verbatim (review of db73119): the watch condition and the debtMin arguments
            check("Today's digest: the watch's sleep debt only without the ring's", today.contains(
                    "double[] watch = !noDebt && debt == null && HistoryViews.watch(d) ? d.deviceHistory.debt(d.date) : null;"));
            check("Today's digest: the debtMin arguments", today.contains("double debtMin = DaySummary.debtMin(debt != null, debt != null && debt.optBoolean(\"valid\"),")
                    && today.contains("debt == null ? Double.NaN : debt.optDouble(\"debt_min\", 0), watch);"));
            check("Today's digest: the call with them", today.contains(
                    "return DaySummary.digest(labels, scores, rd != null && rd.optBoolean(\"provisional\"), debtMin, noDebt, forLine, Locale.getDefault());"));
            check("Readiness's headline comes from core", ready.contains("return DaySummary.headline(d.score(\"readiness\"), d.score(\"sleep\"));")
                    && !ready.contains("A good day to push a little"));
            check("the body signals' line and colour come from core", ready.contains("DaySummary.signalLine(value, usual, dp, Locale.getDefault())")
                    && ready.contains("DaySummary.signalTone(value, usual, dp, lowerIsBetter)") && !ready.contains("* 0.05"));
        } catch (java.io.IOException ex) {
            check("read the ui sources " + ex, false);
        }
    }
}
