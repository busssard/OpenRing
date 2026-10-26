package app.openring.core;

/**
 * Small helpers for screen texts: a number with its word in singular or plural (issue #92), the
 * length a toast can show in full, and the sentence for a file that could not be saved (issue #89).
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
}
