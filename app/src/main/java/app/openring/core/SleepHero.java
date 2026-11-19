package app.openring.core;

import app.openring.core.analysis.WakeUps;

import java.time.ZoneId;

/**
 * The two lines under the Sleep score (issue #409, owner: "in sleep topic the total nightly sleeping time is important"): the time
 * asleep as the big line, "7h 12m asleep", and under it the night's times with the time in bed, "23:10 to 07:05, 7h 55m in bed".
 * Display only: the minutes are the night's stored time asleep and the same time in bed as the Details card, formatted as
 * {@link Texts#hm} and the clock times as the timeline shows them ({@link WakeUps#clock}). Used for the ring's and the watch's
 * night; plain Java so the JVM tests check the texts.
 */
public final class SleepHero {
    private SleepHero() {}

    /**
     * "7h 12m asleep"; null when the time asleep is unknown, so the hero shows no big line. Also null for a night with no time
     * asleep and no stage data at all ({@code stages} has no minute from 1 deep to 4 awake), where a big "0h 0m asleep" would
     * only say that nothing was measured (review of #411); a night with stages but no sleep (all awake) keeps "0h 0m asleep".
     */
    public static String asleep(double asleepMin, int[] stages) {
        if (Double.isNaN(asleepMin) || Double.isInfinite(asleepMin) || asleepMin < 0) return null;
        if (Math.round(asleepMin) == 0 && !hasStages(stages)) return null;
        return Texts.hm(asleepMin) + " asleep";
    }

    /** Whether a night's stages (one digit a minute: 0 no record, 1 deep, 2 light, 3 REM, 4 awake) hold any measured minute. */
    public static boolean hasStages(int[] stages) {
        if (stages == null) return false;
        for (int s : stages) if (s >= 1 && s <= 4) return true;
        return false;
    }

    /**
     * "23:10 to 07:05, 7h 55m in bed", the times in the night's zone ({@code zone} null is the phone's); without the time in
     * bed (unknown or negative, as {@code Pages.shownInBed} gives -1 for a night without an end) only "23:10 to 07:05".
     */
    public static String window(double start, double end, double inBedMin, ZoneId zone) {
        String times = WakeUps.clock(start, zone) + " to " + WakeUps.clock(end, zone);
        if (Double.isNaN(inBedMin) || Double.isInfinite(inBedMin) || inBedMin < 0) return times;
        return times + ", " + Texts.hm(inBedMin) + " in bed";
    }
}
