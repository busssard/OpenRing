package app.openring.core;

import java.util.Locale;

/**
 * Small helpers for screen texts: a number with its word in singular or plural (issue #92), the
 * length a toast can show in full, the sentence for a file that could not be saved (issue #89), and
 * one way to write the same quantity everywhere (issues #99, #163).
 */
public final class Texts {
    private Texts() {}

    /**
     * Android 12 and newer cut a text toast after two lines. Two lines of 30 characters fit even with a
     * larger font; longer texts go into a small dialog instead (issue #89).
     */
    public static final int TOAST_MAX = 60;

    /** The toast after a factory reset (RingManager); Settings closes right after, so it must fit a toast. */
    public static final String RING_RESET_DONE = "The ring was reset, its old key no longer works.";

    /** Settings, Sync now while a sync or another ring task runs (issue #43). */
    public static final String SYNC_BUSY = "A sync, Live or a game is using the ring. Try again when it is done.";

    /** Settings, Delete all local data refused while a sync or another ring task runs (issue #43). */
    public static final String DELETE_BUSY = "Nothing was deleted, a sync, Live or a game is using the ring. Try again when it is done.";

    /** Settings, Delete all local data failed part way; it is one transaction, so nothing was deleted. */
    public static final String DELETE_FAILED = "Nothing was deleted, something went wrong. Diagnostics, Log tab, shows why.";

    /**
     * The same two answers as a toast, when Settings was closed before Delete all local data finished: Android 12 and
     * newer cut a toast after two lines, so the full texts above only go into the dialog (review of #277).
     */
    public static final String DELETE_BUSY_TOAST = "Nothing was deleted, the ring was busy. Try again later.";
    public static final String DELETE_FAILED_TOAST = "Nothing was deleted. Diagnostics, Log tab, shows why.";

    /** Settings, Re analyse all failed: it is one transaction, so the stored results are the old ones (issue #230). */
    public static final String ANALYSIS_FAILED = "The analysis failed, your nights and days are as before. " + SyncError.DETAILS;

    /** Settings, Re analyse all while a sync or Live uses the ring: it waits for that task first (review of #243). */
    public static final String REANALYSE_WAITS = "Waiting for the ring to finish its current task, then re analysing all nights. This can take a few minutes.";

    /**
     * Re analyse all and Data check's Repair are one job, run one at a time (issue #222): the toast when it starts, and
     * the line under Re analyse all while it runs (also one started in Data check).
     */
    public static final String REANALYSING = "Re analysing all nights, this can take a few minutes.";

    /** Re analyse all tapped while that job runs: no second run is started (issue #222). */
    public static final String REANALYSE_RUNS = "Re analysing already, please wait.";

    /**
     * A setting that needs a new analysis was saved while one runs (issue #222): that run may have read the old setting,
     * so the job runs once more after it instead of a second run beside it.
     */
    public static final String REANALYSE_AGAIN = "Saved. Re analysing already runs, it runs once more after that to use your change.";

    /** Re analyse all ended. */
    public static final String ANALYSIS_UPDATED = "Analysis updated";

    /** Re analyse all failed while Settings was not shown: a toast, so it must fit (issue #222). */
    public static final String ANALYSIS_FAILED_TOAST = "The analysis failed. Data check and log shows why.";

    /** Settings, the line under the backup and export buttons while a file is saved (issue #222). */
    public static final String SAVING_BACKUP = "Saving the backup, this can take a minute.";
    public static final String SAVING_EXPORT = "Saving the export, this can take a minute.";
    /** The same while the automatic backup writes its copy (one file at a time, issue #222). */
    public static final String SAVING_AUTO = "An automatic backup is being saved, this can take a minute.";

    /** A backup or export asked for while another one, or the automatic backup, is saved (issue #222). */
    public static final String SAVE_BUSY = "A file is being saved. Try again when it is done.";

    /** Live could not get the ring within 10 seconds: a sync, a game or Delete all local data holds it (issue #43). */
    public static final String LIVE_BUSY = "The ring is busy right now (a sync or another task). Try again in a moment.";

    /**
     * The toast when a repair in Data check fails; it can show on any screen of the app (issue #130),
     * so it names where to look instead of "above".
     */
    public static final String REPAIR_FAILED = "Repair did not finish, see Log in Data check and log.";

    /** "1 wake up", "0 wake ups", "3 wake ups": the word agrees with the number. */
    public static String count(long n, String one, String many) {
        return n + " " + word(n, one, many);
    }

    /** Only the word for {@code n}, when the number is written with its own format (for example 5,353 events). */
    public static String word(long n, String one, String many) {
        return n == 1 ? one : many;
    }

    /**
     * A change such as the temperature against your usual, with its sign and one decimal: "+0.3", "-0.2",
     * and "0.0" near zero, never "-0.0" or "+0.0" (issues #32, #163). The value is first rounded to the
     * three decimals a day stores, so a score detail built from the unrounded value reads the same as the
     * card that shows the stored one.
     */
    public static String signed(double v, Locale locale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        double r = Math.round(v * 1000) / 1000.0;
        if (Math.abs(r) < 0.05) return String.format(locale, "%.1f", 0.0);
        return String.format(locale, "%+.1f", r);
    }

    /**
     * A number on a line chart (guide labels, low mark, touch readout): one decimal under 10 unless
     * whole ("0.3", "12", "26"), with no sign rule, so -0.04 reads "-0.0".
     */
    public static String chartValue(double v, Locale locale) {
        return Math.abs(v) < 10 && v != Math.rint(v) ? String.format(locale, "%.1f", v) : String.valueOf(Math.round(v));
    }

    /**
     * The number a line chart shows: {@link #signed} for a chart of changes (the temperature charts,
     * issue #226, so the touch readout matches the card above), otherwise {@link #chartValue}.
     */
    public static String chartValue(double v, boolean signed, Locale locale) {
        return signed ? signed(v, locale) : chartValue(v, locale);
    }

    /** A duration: "30 min" under an hour, "1h 5m" from an hour on, "--" when unknown (issue #99). */
    public static String minutes(double min) {
        if (Double.isNaN(min) || Double.isInfinite(min)) return "--";
        long m = Math.round(min);
        if (m < 60) return m + " min";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    /**
     * The Today summary's readiness sentence (issue #200): "HRV balance is your strongest contributor, while resting
     * heart rate needs attention." The "while" part only for a score under Good ({@link app.openring.core.analysis.Scores#needsAttention}),
     * the same ladder as the labels and colours on the Readiness page. Empty without a strongest contributor.
     */
    public static String contributorLine(String best, String worst, double worstScore, Locale locale) {
        if (best == null || best.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(best).append(" is your strongest contributor");
        if (worst != null && !worst.isEmpty() && app.openring.core.analysis.Scores.needsAttention(worstScore)) {
            sb.append(", while ").append(lowerFirst(worst, locale)).append(" needs attention");
        }
        return sb.append('.').toString();
    }

    /** A label inside a sentence: "Resting heart rate" becomes "resting heart rate", "HRV balance" stays (review of #282). */
    static String lowerFirst(String label, Locale locale) {
        if (label.isEmpty() || (label.length() > 1 && Character.isUpperCase(label.charAt(1)))) return label;
        return label.substring(0, 1).toLowerCase(locale) + label.substring(1);
    }

    /**
     * The line under Daily movement (issue #212): what its percentages are shares of. {@code wornMin} is the sum of
     * the rows, {@code notWornMin} the stored minutes without values outside sleep (NaN when not stored), {@code until}
     * the clock time a day still running was analysed up to (null for a day that has ended, the minutes after it are
     * not yet known and do not count as not worn), {@code leftOut} whether a left out time touches the day (its
     * minutes have no values, so they are in the not worn minutes too, issue #254).
     */
    public static String movementShares(double wornMin, double notWornMin, String until, boolean leftOut) {
        String s = "The percentages are of the time the ring was on" + (until == null ? "" : " up to " + until) + " (" + minutes(wornMin) + ").";
        if (Double.isNaN(notWornMin) || Math.round(notWornMin) < 1) return s;
        return s + (leftOut ? " Not worn or left out: " : " Not worn: ") + minutes(notWornMin) + ".";
    }

    /**
     * The stored not worn minutes of a day without the ones still to come: a day analysed at {@code until} (unix
     * seconds, NaN for an ended day) counted the minute it was in and every minute after it up to {@code dayEnd} as
     * without values (the minutes of {@code Wellness.day}, counted from {@code dayStart}).
     */
    public static double notWornSoFar(double notWornMin, double until, double dayStart, double dayEnd) {
        if (Double.isNaN(notWornMin)) return Double.NaN;
        if (Double.isNaN(until) || until >= dayEnd) return notWornMin;
        long dayMin = Math.max(0, Math.round((dayEnd - dayStart) / 60));
        long passed = (long) Math.max(0, Math.floor((until - dayStart) / 60));
        return Math.max(0, notWornMin - Math.max(0, dayMin - passed));
    }

    /**
     * A symptom radar value with its unit and the precision the Today and Sleep cards use (issue #99):
     * skin temperature as a change ("-0.2 \u00b0C"), resting heart rate in bpm, HRV in ms, breathing per minute.
     */
    public static String radar(String label, double v, Locale locale) {
        return radar(label, v, Double.NaN, locale);
    }

    /**
     * As {@link #radar(String, double, Locale)}, but skin temperature shows the day's stored change
     * {@code tempDev} (3 decimals) when known, so it reads as on Today; the radar's own marker is stored at
     * 2 decimals and could round differently (0.046 is "0.0" on Today, its marker 0.05 would be "+0.1").
     */
    public static String radar(String label, double v, double tempDev, Locale locale) {
        if (label.startsWith("Skin") && !Double.isNaN(tempDev) && !Double.isInfinite(tempDev)) v = tempDev;
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        if (label.startsWith("Skin")) return signed(v, locale) + " \u00b0C";
        if (label.startsWith("Resting")) return String.format(locale, "%.0f bpm", v);
        if (label.startsWith("HRV")) return String.format(locale, "%.0f ms", v);
        if (label.startsWith("Respiratory")) return String.format(locale, "%.1f /min", v);
        return String.format(locale, "%.1f", v);
    }

    /** True when a toast shows {@code text} in full. */
    public static boolean fitsToast(String text) {
        return text != null && text.length() <= TOAST_MAX;
    }

    /** The sentence after an export or backup file could not be written; the raw reason goes to the log. */
    public static String saveFailed(boolean backup) {
        return saveFailed(backup, false);
    }

    /**
     * As above; {@code leftOver}: the file that was started could not be removed and holds part of it (issue #222), so
     * the person knows not to keep it as a backup.
     */
    public static String saveFailed(boolean backup, boolean leftOver) {
        return "The " + (backup ? "backup" : "export") + " could not be saved. "
                + (leftOver ? "The file it started is incomplete, delete it. " : "")
                + "Pick another place, or free some space, and try again. " + SyncError.DETAILS;
    }

    /** The short form of a failed save for a toast, when Settings was closed meanwhile (issue #222). */
    public static String saveFailedToast(boolean backup, boolean leftOver) {
        String what = backup ? "The backup" : "The export";
        return leftOver ? what + " was not saved, delete the incomplete file." : what + " was not saved. Data check and log shows why.";
    }

    /**
     * The sentence when the copy of the history made for an export or full backup did not pass its
     * check (issue #115): nothing was written, the phone's history was only read.
     */
    public static String notChecked(boolean backup) {
        return backup ? "The backup was not saved, because the copy of your history did not pass its check. Nothing on this phone was changed. Try again in a minute."
                : "The export was not saved, because the copy of the history did not pass its check. Nothing on this phone was changed. Try again in a minute.";
    }

    /**
     * The text size that lets the widest of several labels fit its room (the tab bar, issue #119): {@code wanted}
     * when the widest label, measured at that size, fits; else smaller by the same factor, but not below
     * {@code min}. Widths and room in the same unit (pixels).
     */
    public static float fitSize(float wanted, float widest, float room, float min) {
        if (!(widest > 0) || !(room > 0) || widest <= room) return wanted;
        return Math.max(min, wanted * room / widest);
    }
}
