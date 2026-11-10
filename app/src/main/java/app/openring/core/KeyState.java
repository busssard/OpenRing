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
    /**
     * No ring by choice (issue #329): the person uses Open Ring without a ring (phone steps, screen time, the period
     * tracker). Not a missing key, so it never opens Setup and never syncs. Setup's "Use without a ring" sets it (phase 1b,
     * {@code Prefs.setNoRing}); a key, once stored, wins over it. Every place that decides about Setup or a paired ring asks
     * {@link #needsSetup} or {@link #hasRing}, so none can send the person back to Setup in a loop.
     */
    public static final int NO_RING = 3;

    /** The state from whether a key is stored and whether it could be read. */
    public static int of(boolean stored, boolean read) {
        if (!stored) return NONE;
        return read ? READABLE : UNREADABLE;
    }

    /**
     * As {@link #of(boolean, boolean)}, with the person's choice to use the app without a ring ({@code noRing}, issue #329):
     * it counts only while no key is stored, so pairing a ring later ends it by itself.
     */
    public static int of(boolean stored, boolean read, boolean noRing) {
        if (!stored && noRing) return NO_RING;
        return of(stored, read);
    }

    /**
     * The state as far as it is known without reading the key: it never decrypts, so it gives {@link #NONE} or
     * {@link #READABLE}, also for a stored key this phone cannot read (never {@link #UNREADABLE} or {@link #NO_RING}).
     * Use it only for {@link #needsSetup} and {@link #hasRing}, which treat a readable and an unreadable key alike; never
     * for {@link #opensSetup} or {@link #syncs}, which need {@link #of} with the key actually read.
     */
    public static int ofStored(boolean stored) {
        return of(stored, true);
    }

    /** As {@link #ofStored(boolean)} with the choice to use the app without a ring: {@link #NO_RING} while no key is stored. */
    public static int ofStored(boolean stored, boolean noRing) {
        return of(stored, true, noRing);
    }

    /**
     * Setup is not finished or there is no ring key (Settings and the main screen then go to Setup, as before). No ring
     * by choice ({@link #NO_RING}) never needs Setup.
     */
    public static boolean needsSetup(boolean setupDone, int state) {
        if (state == NO_RING) return false;
        return !setupDone || state == NONE;
    }

    /** A ring is set up on this phone: a key is stored, readable or not (Find my ring talks to it only then). */
    public static boolean hasRing(int state) {
        return state == READABLE || state == UNREADABLE;
    }

    /**
     * Whether the main screen opens Setup by itself: no ring set up or no key, as before, and a pairing whose key page
     * was not left yet (issue #218) only when that key can be shown. An unreadable key keeps the main screen, which
     * says so and leads to Setup on a tap; no ring by choice keeps it too.
     */
    public static boolean opensSetup(boolean demo, boolean setupDone, int state, boolean pairUnseen) {
        if (demo) return false;
        return needsSetup(setupDone, state) || pairUnseen && state == READABLE;
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

    /**
     * Settings, the backup cards, while the key cannot be read (issue #288): backups are still saved, without the key.
     * Before #288 no backup could be saved in this state.
     */
    public static final String BACKUP_NO_KEY = "This phone cannot read the saved ring key, so backups are saved without it. "
            + "After restoring one, enter your key in Setup.";

    /** Under the backup buttons in that state: what "Key and settings only" then holds (review of #340). */
    public static final String BACKUP_SETTINGS_ONLY = "Key and settings only then saves your settings alone, without the key.";

    /** A backup file saved in Settings while the key cannot be read (issue #288). */
    public static final String BACKUP_SAVED_NO_KEY = "Backup saved, without the ring key this phone cannot read.";

    // ---- no ring by choice (issue #329, phase 1b) ----

    /** Setup's second button on its first screen: sets {@link #NO_RING} and goes to the main screen. */
    public static final String USE_WITHOUT = "Use without a ring";
    /** Under it: what works without a ring. */
    public static final String WITHOUT_TEXT = "Steps from this phone, screen time and the period tracker work without a ring. Add a ring later in Settings.";
    /** The button every ring only action shows instead (Sync now, Find my ring, Live, the games, the Ring block); it opens Setup. */
    public static final String ADD_RING = "Add a ring";
    /** The window or line those actions show without a ring. */
    public static final String NO_RING_TEXT = "No ring is added. Add a ring to sync, find it or measure live.";
    /** The Ring block's header line and text without a ring. */
    public static final String NO_RING_STATUS = "No ring added";
    public static final String NO_RING_BLOCK = "You use Open Ring without a ring. Add one to see sleep, readiness and your heart rate.";
    /** The ring games card without a ring. */
    public static final String NO_RING_GAMES = "The ring games use your ring as a controller. Add a ring to play.";

    /**
     * What a ring only action does now (issue #329): {@code true} when it must show {@link #ADD_RING} instead of running
     * (no ring by choice, outside demo mode; demo mode has its made up ring).
     */
    public static boolean addRingInstead(boolean demo, int state) {
        return !demo && state == NO_RING;
    }

    /** Every text above, for the tests (plain, no dashes as punctuation). */
    public static String[] texts() {
        return new String[]{NO_KEY_RAW, UNREADABLE_RAW, PLAIN, STATUS, SYNC_TITLE, SYNC_TEXT, BUTTON, SETUP_TITLE, SETUP_TEXT, SETTINGS_TEXT, BACKUP_NO_KEY,
            BACKUP_SETTINGS_ONLY, BACKUP_SAVED_NO_KEY, USE_WITHOUT, WITHOUT_TEXT, ADD_RING, NO_RING_TEXT, NO_RING_STATUS, NO_RING_BLOCK, NO_RING_GAMES};
    }
}
