package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

/** Everything computed for one sleep period. Times are unix seconds. NaN means unknown. */
public final class Night {
    public double start, end;
    /** 30 second stage codes over [start, end): 1 deep, 2 light, 3 REM, 4 awake, 0 unknown. */
    public int[] stages = new int[0];
    public String stageSource = "none";

    public double inBedMin, asleepMin, deepMin, lightMin, remMin, awakeMin;
    public double efficiency = Double.NaN, latencyMin = Double.NaN, wasoMin = Double.NaN;
    public double remLatencyMin = Double.NaN;
    public int awakenings, cycles;
    public double restlessPct = Double.NaN;
    public double sleepStart = Double.NaN, sleepEnd = Double.NaN, midpoint = Double.NaN;

    public double lowestHr = Double.NaN, lowestHrTime = Double.NaN, avgHr = Double.NaN;
    public double avgHrv = Double.NaN, maxHrv = Double.NaN;
    public double breath = Double.NaN, temp = Double.NaN, spo2 = Double.NaN;
    public double spo2Low = Double.NaN;
    public double hrvDeep = Double.NaN, hrvRem = Double.NaN;

    /** Chart series, unix seconds. */
    public TS hr = new TS(), hrv = new TS(), motion = new TS(), spo2Series = new TS(), tempSeries = new TS();

    public boolean main;
    public Score score;

    public double durationH() {
        return (end - start) / 3600.0;
    }

    /** A 0 to 100 score with its contributors. */
    public static final class Score {
        public int value;
        public final List<Contributor> contributors = new ArrayList<Contributor>();
        public boolean provisional;

        public Contributor get(String key) {
            for (Contributor c : contributors) if (c.key.equals(key)) return c;
            return null;
        }
    }

    public static final class Contributor {
        public final String key, label;
        public final double score, weight;
        public final String detail;

        public Contributor(String key, String label, double score, double weight, String detail) {
            this.key = key;
            this.label = label;
            this.score = score;
            this.weight = weight;
            this.detail = detail;
        }

        /** Oura style verbal rating of a contributor score. */
        public String rating() {
            return Scores.rating(score);
        }
    }
}
