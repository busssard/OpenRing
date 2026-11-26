package app.openring.core;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

import app.openring.core.analysis.Wellness;

/**
 * Phone steps, phase 2 (issue #329): calories from steps without a ring or a watch, clearly an estimate, and the lines that let
 * a person hold this phone's day totals next to the phone's own health app. Pure Java; nothing here is stored, the screens work
 * it out on each load from the day's steps and the profile's weight.
 *
 * <p><b>The estimate.</b> Walking on level ground costs about {@link #KCAL_PER_KG_KM} kcal per kg of body weight for each km
 * above what the body uses at rest: the walking equation of the American College of Sports Medicine (ACSM's Guidelines for
 * Exercise Testing and Prescription, metabolic equations) has 0.1 ml of oxygen per kg for each metre walked on the flat, and a
 * litre of oxygen is about 5 kcal. The distance is the one the Activity tab shows for phone steps
 * ({@link Wellness#stepsToMeters}, 0.762 m a step), so the estimate and the distance never disagree. Its error: the equation
 * holds for walking at 50 to 100 m a minute (within about a tenth), a stride differs from 0.762 m by up to about a fifth from
 * person to person, so for walking the estimate is right within about a quarter. Running (about twice the calories per km),
 * hills, stairs, cycling and steps while the phone is not carried are missing, so it is usually lower than a ring's or a
 * watch's active burn. It is shown only for a person with neither (the ring has its own active burn, the watch its own calories).
 */
public final class StepCalories {
    private StepCalories() {}

    /** Net cost of walking on level ground, kcal per kg per km (ACSM: 0.1 ml O2 per kg per metre, about 5 kcal per litre of O2). */
    public static final double KCAL_PER_KG_KM = 0.5;
    /** The share the estimate can be off by for walking (shown in the text as "about a quarter"). */
    public static final double ERROR = 0.25;
    /** The estimate is shown rounded to this many kcal, so it does not look more exact than it is. */
    public static final int ROUND_TO = 10;

    /**
     * Active calories from {@code steps} for a person of {@code weightKg}: {@link #KCAL_PER_KG_KM} x weight x km walked. NaN when
     * the steps are not known (NaN or below 0) or the weight is outside the profile's range (30 to 250 kg).
     */
    public static double kcal(double steps, double weightKg) {
        if (!(steps >= 0) || !(weightKg >= InputLimit.WEIGHT_KG.lo && weightKg <= InputLimit.WEIGHT_KG.hi)) return Double.NaN;
        return KCAL_PER_KG_KM * weightKg * Wellness.stepsToMeters(steps) / 1000.0;
    }

    /** {@code kcal} as the screens show it: rounded to {@link #ROUND_TO}, NaN stays NaN. */
    public static double shown(double kcal) {
        if (Double.isNaN(kcal)) return Double.NaN;
        return Math.round(kcal / ROUND_TO) * (double) ROUND_TO;
    }

    /** The mean estimate a day of the days in {@code steps} that have steps (NaN left out), NaN without any. */
    public static double dailyMean(double[] steps, double weightKg) {
        double sum = 0;
        int n = 0;
        for (double s : steps) {
            double k = kcal(s, weightKg);
            if (Double.isNaN(k)) continue;
            sum += k;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ------------------------------------------------------------------------------------------- texts

    /** The stat's label and its one line note (Activity and Today with only the phone). */
    public static final String LABEL = "Calories from walking";
    public static final String NOTE = "Estimate from your steps and weight";

    /** The line under the estimate on Activity, with a weight the person entered. */
    public static String about(double weightKg) {
        return about(weightKg, true);
    }

    /**
     * The line under the estimate on Activity: how it is worked out, from what, and how far off it can be. {@code own}: the weight
     * was entered; otherwise it is the app's usual weight and the line says where to set it.
     */
    public static String about(double weightKg, boolean own) {
        String weight = own ? "your weight of " + Math.round(weightKg) + " kg (Settings, Profile and goals)"
                : "the usual weight of " + Math.round(weightKg) + " kg (set yours in Settings, Profile and goals)";
        return "Estimate: about " + fmt(KCAL_PER_KG_KM) + " kcal for each kg of body weight and each km walked on level ground "
                + "(the walking equation of the American College of Sports Medicine), from the distance above and "
                + weight + ". For walking it is right within about a quarter. Running, hills, "
                + "stairs, cycling and steps while the phone is not on you are missing, so a ring or a watch usually counts more. "
                + "It leaves out what your body burns at rest.";
    }

    /**
     * The line under the estimate, for the 7 days {@link #weekEnd} gives: "Last 7 full days (days with steps): about 190 kcal a day
     * from walking." while {@code shown} is today, "7 days up to this day (days with steps): ..." for a day before {@code today}
     * (review of #467: there the 7 days end at the shown day); null without any day with steps.
     */
    public static String week(double meanKcal, LocalDate shown, LocalDate today) {
        if (Double.isNaN(meanKcal)) return null;
        return (shown.isBefore(today) ? "7 days up to this day" : "Last 7 full days") + " (days with steps): about "
                + Math.round(shown(meanKcal)) + " kcal a day from walking.";
    }

    /**
     * The last day of the 7 full days the average takes (review of #467): the shown day when it has ended, else the day before
     * {@code today}, so a day still running (a part day) never pulls the average down or makes it change through the day.
     */
    public static LocalDate weekEnd(LocalDate shown, LocalDate today) {
        return shown.isBefore(today) ? shown : today.minusDays(1);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------- compare with the phone's health app

    /** The card on Activity with only the phone. */
    public static final String COMPARE_TITLE = "Compare with your phone's health app";
    public static final String COMPARE_INTRO = "Your phone's own health app counts from the same step sensor. Open Ring cannot read "
            + "other apps' numbers, so hold these next to the same days there.";
    public static final String COMPARE_NOTE = "A few percent apart is normal: Open Ring spreads the steps evenly over the time between "
            + "two readings, so steps near midnight can land on the day before or after. A day far apart usually had a restart with an "
            + "empty battery or hours without a reading (Settings, Help and diagnostics, Data check lists them).";

    /**
     * One line per day of the {@code steps} ending at {@code end} (oldest first, as {@code DeviceHistory.last} gives them),
     * newest first: "Mon 17 Nov: 8,412 steps", "Today so far: 3,120 steps" for {@code today}, "no steps counted" for a day
     * without any.
     */
    public static String[] compareLines(double[] steps, LocalDate end, LocalDate today, Locale l) {
        String[] out = new String[steps.length];
        for (int i = 0; i < steps.length; i++) {
            int back = steps.length - 1 - i;
            LocalDate d = end.minusDays(back);
            String day = d.equals(today) ? "Today so far" : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                    + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            double v = steps[i];
            out[back] = day + ": " + (Double.isNaN(v) ? "no steps counted" : String.format(l, "%,d", Math.round(v)) + (Math.round(v) == 1 ? " step" : " steps"));
        }
        return out;
    }

    // ------------------------------------------------------------------- the step goal without a watch

    /** Settings, Phone, without a watch: the step goal the watch setup otherwise sets (the same setting). */
    public static final String GOAL_LABEL = "Daily step goal";
    public static final String GOAL_NOTE = "Used by the activity score, the step charts and the Goal reached badge without a ring. "
            + "With a watch, its setup sets it.";

    /** What TalkBack reads for a goal chip: "10,000 steps, picked". */
    public static String goalChip(int goal, boolean picked) {
        return String.format(Locale.US, "%,d steps", goal) + (picked ? ", picked" : ", tap to pick");
    }

    /** Every fixed text, for the tests (plain, no dashes as punctuation). */
    public static String[] texts() {
        return new String[] {LABEL, NOTE, about(72), about(72, false), week(190, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24)),
            week(190, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24)), COMPARE_TITLE, COMPARE_INTRO, COMPARE_NOTE, GOAL_LABEL, GOAL_NOTE, goalChip(8000, true)};
    }
}
