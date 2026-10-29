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

    /** Settings, Re analyse all failed: it is one transaction, so the stored results are the old ones (issue #230). */
    public static final String ANALYSIS_FAILED = "The analysis failed, your nights and days are as before. " + SyncError.DETAILS;

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

    /** A duration: "30 min" under an hour, "1h 5m" from an hour on, "--" when unknown (issue #99). */
    public static String minutes(double min) {
        if (Double.isNaN(min) || Double.isInfinite(min)) return "--";
        long m = Math.round(min);
        if (m < 60) return m + " min";
        return (m / 60) + "h " + (m % 60) + "m";
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
        return "The " + (backup ? "backup" : "export") + " could not be saved. Pick another place, or free some space, and try again. "
                + SyncError.DETAILS;
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
