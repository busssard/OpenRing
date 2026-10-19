package app.openring.core.analysis;

import java.util.Locale;

/**
 * Oura style 0 to 100 scores. The contributor sets and combiner weights are the ones open_health
 * recovered from Oura's ecore engine by regression (Sleep R2 0.999: 35/15/10/10/10/10/10;
 * Readiness and Activity weights from score-weights.md). Oura's contributor curves are not public,
 * so each curve here is a transparent piecewise linear map anchored on published sleep science
 * (NSF 2015/2017, Boulos 2019) and Oura's own descriptions of each contributor. Missing inputs drop
 * out and the remaining weights renormalise, so a night is never punished for what the ring did
 * not record.
 */
public final class Scores {
    private Scores() {}

    public static String rating(double s) {
        if (Double.isNaN(s)) return "No data";
        if (s >= 85) return "Optimal";
        if (s >= 70) return "Good";
        if (s >= 60) return "Fair";
        return "Pay attention";
    }

    public static String verdict(int score) {
        if (score >= 85) return "Optimal";
        if (score >= 70) return "Good";
        return "Pay attention";
    }

    private static void add(Night.Score sc, String key, String label, double score, double weight, String detail) {
        if (Double.isNaN(score)) return;
        sc.contributors.add(new Night.Contributor(key, label, Math.round(Stats.clamp(score, 0, 100)), weight, detail));
    }

    private static Night.Score finish(Night.Score sc) {
        double w = 0, s = 0;
        for (Night.Contributor c : sc.contributors) {
            w += c.weight;
            s += c.weight * c.score;
        }
        sc.value = w > 0 ? (int) Math.round(s / w) : -1;
        return sc;
    }

    // ------------------------------------------------------------------------------- sleep

    public static Night.Score sleep(Night n, int tzOffsetS, double age) {
        Night.Score sc = new Night.Score();
        double h = n.asleepMin / 60.0;
        double shift = age >= 65 ? -0.5 : (age < 18 ? 1.0 : 0.0);
        add(sc, "total", "Total sleep", Stats.curve(h - shift, new double[][] {
            {3, 10}, {5, 40}, {6, 62}, {7, 85}, {7.5, 95}, {8, 100}, {9, 100}, {10, 92}, {11, 80}}),
            35, hm(n.asleepMin));
        add(sc, "efficiency", "Efficiency", Stats.curve(n.efficiency, new double[][] {
            {60, 20}, {70, 45}, {80, 70}, {85, 85}, {90, 95}, {95, 100}}),
            10, Double.isNaN(n.efficiency) ? "" : Math.round(n.efficiency) + "%");

        double waso = Stats.curve(n.wasoMin, new double[][] {{0, 100}, {20, 92}, {45, 72}, {90, 40}, {150, 15}});
        double wakes = Stats.curve(n.awakenings, new double[][] {{0, 100}, {3, 92}, {6, 78}, {10, 58}, {15, 35}});
        double restless = Stats.curve(n.restlessPct, new double[][] {{0, 100}, {10, 92}, {25, 72}, {40, 48}, {60, 20}});
        double rest = Stats.mean(new double[] {waso, wakes, restless});
        add(sc, "restfulness", "Restfulness", rest, 15,
            Double.isNaN(n.wasoMin) ? "" : String.format(Locale.US, "%d wake ups, %s awake", n.awakenings, hm(n.wasoMin)));

        boolean staged = "ring".equals(n.stageSource) || "signals".equals(n.stageSource);
        if (staged) {
            add(sc, "rem", "REM sleep", Stats.curve(n.remMin, new double[][] {{0, 0}, {30, 30}, {60, 65}, {90, 92}, {110, 100}}), 10, hm(n.remMin));
            add(sc, "deep", "Deep sleep", Stats.curve(n.deepMin, new double[][] {{0, 0}, {20, 30}, {40, 62}, {60, 88}, {75, 100}}), 10, hm(n.deepMin));
        }
        add(sc, "latency", "Latency", Stats.curve(n.latencyMin, new double[][] {
            {0, 55}, {5, 80}, {10, 95}, {15, 100}, {20, 100}, {30, 85}, {45, 60}, {60, 40}, {90, 20}}),
            10, Double.isNaN(n.latencyMin) ? "" : Math.round(n.latencyMin) + " min");
        if (!Double.isNaN(n.midpoint)) {
            double local = ((n.midpoint + tzOffsetS) % 86400 + 86400) % 86400 / 3600.0;
            double dist = Math.abs(local - 3.0);
            if (dist > 12) dist = 24 - dist;
            add(sc, "timing", "Timing", Stats.curve(dist, new double[][] {{0, 100}, {1, 100}, {1.5, 93}, {2.5, 76}, {4, 45}, {6, 20}}),
                10, "midpoint " + clock(n.midpoint, tzOffsetS));
        }
        return finish(sc);
    }

    // --------------------------------------------------------------------------- readiness

    /** Inputs for readiness. NaN where unknown. */
    public static final class ReadinessInput {
        public double sleepScore = Double.NaN;
        public double rhr = Double.NaN, rhrBaseline = Double.NaN, rhrSd = Double.NaN;
        public double hrvRecent = Double.NaN, hrvBaseline = Double.NaN;
        public double tempDev = Double.NaN;
        /** 0 = RHR minimum at sleep start, 1 = at wake. */
        public double recoveryFraction = Double.NaN;
        /** two week asleep average divided by sleep need. */
        public double sleepBalance = Double.NaN;
        /** previous day active kcal divided by target. */
        public double prevDayActivity = Double.NaN;
        /** last 7 days active kcal average divided by the 4 week average. */
        public double activityBalance = Double.NaN;
        public int historyNights;
    }

    public static Night.Score readiness(ReadinessInput in) {
        Night.Score sc = new Night.Score();
        sc.provisional = in.historyNights < 14;
        double rhrScore = Double.NaN;
        if (!Double.isNaN(in.rhr)) {
            if (!Double.isNaN(in.rhrBaseline)) {
                double z = (in.rhr - in.rhrBaseline) / Math.max(1.5, Double.isNaN(in.rhrSd) ? 1.5 : in.rhrSd);
                rhrScore = Stats.curve(z, new double[][] {{-0.5, 100}, {0, 92}, {1, 72}, {2, 45}, {3, 20}});
            } else {
                rhrScore = Stats.curve(in.rhr, new double[][] {{40, 100}, {55, 92}, {65, 80}, {75, 62}, {90, 35}});
            }
        }
        add(sc, "rhr", "Resting heart rate", rhrScore, 17,
            Double.isNaN(in.rhr) ? "" : Math.round(in.rhr) + " bpm" + (Double.isNaN(in.rhrBaseline) ? "" : String.format(Locale.US, " (usual %.0f)", in.rhrBaseline)));
        double hrvScore = Double.NaN;
        if (!Double.isNaN(in.hrvRecent) && !Double.isNaN(in.hrvBaseline) && in.hrvBaseline > 0) {
            double r = in.hrvRecent / in.hrvBaseline;
            hrvScore = Stats.curve(r, new double[][] {{0.5, 10}, {0.7, 40}, {0.85, 70}, {0.95, 90}, {1.0, 100}});
        }
        add(sc, "hrv", "HRV balance", hrvScore, 15,
            Double.isNaN(in.hrvRecent) ? "" : String.format(Locale.US, "%.0f ms vs usual %.0f ms", in.hrvRecent, in.hrvBaseline));
        double tempScore = Double.isNaN(in.tempDev) ? Double.NaN
            : Stats.curve(Math.abs(in.tempDev) * (in.tempDev < 0 ? 0.7 : 1.0), new double[][] {{0.1, 100}, {0.3, 90}, {0.5, 72}, {1.0, 40}, {1.5, 12}});
        add(sc, "temperature", "Body temperature", tempScore, 13,
            Double.isNaN(in.tempDev) ? "" : String.format(Locale.US, "%+.1f °C", in.tempDev));
        add(sc, "recovery", "Recovery index",
            Stats.curve(in.recoveryFraction, new double[][] {{0.5, 100}, {0.6, 88}, {0.75, 62}, {0.9, 38}, {1.0, 22}}), 10,
            Double.isNaN(in.recoveryFraction) ? "" : (in.recoveryFraction <= 0.5 ? "lowest HR in first half" : "lowest HR late in the night"));
        add(sc, "sleep", "Sleep", in.sleepScore, 15, Double.isNaN(in.sleepScore) ? "" : "sleep score " + Math.round(in.sleepScore));
        add(sc, "sleep_balance", "Sleep balance",
            Stats.curve(in.sleepBalance, new double[][] {{0.6, 20}, {0.7, 40}, {0.8, 65}, {0.9, 85}, {1.0, 100}}), 12,
            Double.isNaN(in.sleepBalance) ? "" : Math.round(in.sleepBalance * 100) + "% of need");
        add(sc, "prev_activity", "Previous day activity",
            Stats.curve(in.prevDayActivity, new double[][] {{0, 55}, {0.25, 72}, {0.5, 92}, {0.8, 100}, {1.3, 100}, {2.0, 70}, {3.0, 45}}), 10, "");
        add(sc, "activity_balance", "Activity balance",
            Stats.curve(in.activityBalance, new double[][] {{0.3, 55}, {0.6, 80}, {0.85, 100}, {1.25, 100}, {1.6, 75}, {2.2, 45}}), 8, "");
        return finish(sc);
    }

    // ---------------------------------------------------------------------------- activity

    public static final class ActivityInput {
        public double inactiveMin = Double.NaN;   // awake, MET below 1.5
        public int longInactiveStreaks = -1;      // >= 60 min sedentary streaks between 8:00 and 22:00
        public double targetsMetFraction = Double.NaN; // share of the last 7 days with the target met
        public int trainingDays7 = -1;            // days in 7 with >= 30 min of medium or high activity
        public double trainingKcal7 = Double.NaN; // medium + high kcal over 7 days
        public double recoveryDays7 = Double.NaN; // easy days in the last week (not scored, shown)
        public int days7 = 7;                     // days in the last 7 (today included) with an hour of wear (issue #44)
    }

    /** Fewer worn days than this in the last 7: the weekly parts are left out (issue #44). */
    public static final int MIN_WEEK_DAYS = 3;

    public static Night.Score activity(ActivityInput in) {
        Night.Score sc = new Night.Score();
        add(sc, "stay_active", "Stay active", Stats.curve(in.inactiveMin / 60.0, new double[][] {{5, 100}, {7, 92}, {9, 72}, {11, 45}, {13, 20}}), 17,
            Double.isNaN(in.inactiveMin) ? "" : hm(in.inactiveMin) + " inactive");
        if (in.longInactiveStreaks >= 0) {
            add(sc, "move_hourly", "Move every hour", Stats.curve(in.longInactiveStreaks, new double[][] {{0, 100}, {1, 90}, {2, 75}, {3, 55}, {5, 25}}), 33,
                in.longInactiveStreaks + " long sitting periods");
        }
        // the weekly parts look at the worn days only: targets as a share of them, training scaled
        // up to a week; with fewer than MIN_WEEK_DAYS they are left out (issue #44)
        int n = Math.max(1, Math.min(7, in.days7));
        if (n < MIN_WEEK_DAYS) return finish(sc);
        double week = 7.0 / n;
        String span = n == 7 ? "" : " in " + n + " days";
        add(sc, "targets", "Meet daily targets", Stats.curve(in.targetsMetFraction, new double[][] {{0, 20}, {0.15, 35}, {0.43, 62}, {0.71, 86}, {1, 100}}), 24,
            Double.isNaN(in.targetsMetFraction) ? "" : Math.round(in.targetsMetFraction * n) + " of " + n + " days");
        if (in.trainingDays7 >= 0) {
            add(sc, "frequency", "Training frequency", Stats.curve(in.trainingDays7 * week, new double[][] {{0, 35}, {1, 60}, {2, 82}, {3, 100}}), 12,
                in.trainingDays7 + (in.trainingDays7 == 1 ? " training day" : " training days") + span);
        }
        add(sc, "volume", "Training volume", Stats.curve(in.trainingKcal7 * week, new double[][] {{0, 20}, {300, 42}, {700, 66}, {1200, 86}, {2000, 100}}), 14,
            Double.isNaN(in.trainingKcal7) ? "" : Math.round(in.trainingKcal7) + (n == 7 ? " kcal this week" : " kcal" + span));
        return finish(sc);
    }

    // ----------------------------------------------------------------------------- helpers

    public static String hm(double minutes) {
        if (Double.isNaN(minutes)) return "";
        long m = Math.round(minutes);
        return (m / 60) + "h " + (m % 60) + "m";
    }

    public static String clock(double unix, int tzOffsetS) {
        long s = (((long) Math.floor(unix) + tzOffsetS) % 86400 + 86400) % 86400;
        return String.format(Locale.US, "%02d:%02d", s / 3600, (s % 3600) / 60);
    }
}
