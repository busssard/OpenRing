package app.openring.core;

/**
 * Whether this phone has a ring key it can use (issue #199). A key can be stored but unreadable: after moving the
 * app to a new phone with Android's phone to phone transfer the settings arrive, the Keystore lock that encrypts the
 * key does not. That state is not "no key": the stored key is never deleted or overwritten for it, only a pairing the
 * ring accepted, a restore or "Use a different key or ring" (each chosen by the person) replace it.
 */
public final class KeyState {
    private KeyState() {}

    /** No key stored; a stored key this phone can read; a stored key this phone cannot read. */
    public static final int NONE = 0, READABLE = 1, UNREADABLE = 2;

    /** The state from whether a key is stored and whether it could be read. */
    public static int of(boolean stored, boolean read) {
        if (!stored) return NONE;
        return read ? READABLE : UNREADABLE;
    }

    /**
     * Whether the main screen opens Setup by itself: no ring set up or no key, as before, and a pairing whose key page
     * was not left yet (issue #218) only when that key can be shown. An unreadable key keeps the main screen, which
     * says so and leads to Setup on a tap.
     */
    public static boolean opensSetup(boolean demo, boolean setupDone, int state, boolean pairUnseen) {
        if (demo) return false;
        return !setupDone || state == NONE || pairUnseen && state == READABLE;
    }

    /** Whether a sync can start, also the background one: only with a key this phone can read. */
    public static boolean syncs(int state) {
        return state == READABLE;
    }

    /** Raw text of a ring task without a usable key ({@link SyncError} makes it plain). */
    public static final String NO_KEY_RAW = "No ring key saved. Finish setup first.";
    public static final String UNREADABLE_RAW = "This phone cannot read the saved ring key";

    /** The raw text for a ring task that found no key it can use; {@code stored}: a key is stored. */
    public static String missing(boolean stored) {
        return stored ? UNREADABLE_RAW : NO_KEY_RAW;
    }

    /** The plain sentence of {@link #UNREADABLE_RAW} after a sync or ring action. */
    public static final String PLAIN = "This phone cannot read the saved ring key (this happens after moving to a new phone). "
            + "Tap the message on the main screen to enter the key again.";

    /** The main screen's status line in this state (tapping it opens Setup). */
    public static final String STATUS = "This phone cannot read the saved ring key, so it cannot sync. Tap here to enter the key again.";

    /** The window the Sync button opens in this state. */
    public static final String SYNC_TITLE = "Ring key";
    public static final String SYNC_TEXT = "This phone cannot read the saved ring key. This happens after moving the app to a new phone: "
            + "Android does not move the lock that protects the key. Enter the key again (from your notes or a backup file) to sync again. "
            + "The saved key stays until a new one works with your ring.";
    public static final String BUTTON = "Enter the key again";

    /** The card at the top of Setup's "Connect your ring" in this state. */
    public static final String SETUP_TITLE = "This phone cannot read the saved key";
    public static final String SETUP_TEXT = "A ring key is saved, but this phone cannot unlock it. This happens after moving the app to a new phone. "
            + "Use your existing key below (from your notes or a backup), or restore a backup file. "
            + "The saved key stays until a new key works with your ring. Your history stays.";

    /** Settings, Key and backup, in place of the key. */
    public static final String SETTINGS_TEXT = "This phone cannot read the saved key. Enter it again to sync, your history stays.";

    /** Every text above, for the tests (plain, no dashes as punctuation). */
    public static String[] texts() {
        return new String[]{NO_KEY_RAW, UNREADABLE_RAW, PLAIN, STATUS, SYNC_TITLE, SYNC_TEXT, BUTTON, SETUP_TITLE, SETUP_TEXT, SETTINGS_TEXT};
    }
}
