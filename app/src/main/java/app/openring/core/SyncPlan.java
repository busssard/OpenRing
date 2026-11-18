package app.openring.core;

/**
 * What the Sync button on the main screen does (owner's report, 11 November): with a watch added, Sync always syncs
 * the watch, whatever state the ring key is in. Before, a stored ring key (readable or not, for example a ring that is
 * no longer used, or a key a reset phone cannot read) sent Sync to the ring only: it looked for the ring or asked for
 * its key, and the watch never synced from that button.
 *
 * With a watch and a readable ring key both sync, the watch first and the ring right after it (the two never share
 * Bluetooth at the same time and the ring wins, issue #251, so a watch sync started during a ring sync would stop; the
 * ring also syncs by itself whenever the app opens). With a key this phone cannot read, only the watch syncs and a short
 * line says how to fix the ring (no dialog in the way).
 */
public final class SyncPlan {
    /** Sync the ring; sync the watch (first, when both); ask for the ring key again; offer to add a ring. All false: demo. */
    public final boolean ring, watch, keyDialog, addRing;
    /** A short line after a watch only sync when a ring key is stored but cannot be used; null otherwise. */
    public final String note;

    private SyncPlan(boolean ring, boolean watch, boolean keyDialog, boolean addRing, String note) {
        this.ring = ring;
        this.watch = watch;
        this.keyDialog = keyDialog;
        this.addRing = addRing;
        this.note = note;
    }

    public static final String KEY_NOTE = "Watch synced. The ring was skipped: this phone cannot read its saved key. "
            + "Enter it again or forget the ring in Settings, Ring.";

    /**
     * Whether the main screen asks for Bluetooth access when it opens (code review of the device choice): with a ring, and
     * with a watch added, also without a ring ({@code keyState} {@link KeyState#NO_RING} counts an added watch as no ring).
     * Just the phone needs no Bluetooth (issue #329); demo mode never asks.
     */
    public static boolean asksBluetooth(boolean demo, int keyState, boolean watchAdded) {
        return !demo && (keyState != KeyState.NO_RING || watchAdded);
    }

    /** What follows an answer to the Bluetooth dialog: nothing, the Sync that asked goes on, or the way to the app settings. */
    public static final int NOTHING = 0, GO_ON = 1, TO_SETTINGS = 2;

    /**
     * @param fromSync the dialog came from Sync (ring or watch), not from the screen opening
     * @param granted access is allowed now
     * @param blocked Android no longer shows its dialog ({@code PermissionAsk.Answer.blocked})
     */
    public static int afterAnswer(boolean fromSync, boolean granted, boolean blocked) {
        if (!fromSync) return NOTHING;
        if (granted) return GO_ON;
        return blocked ? TO_SETTINGS : NOTHING;
    }

    /** The first sentence of the dialog that leads to the app settings: what Sync needs Bluetooth for. */
    public static String accessText(SyncPlan p) {
        String what = p.watch && p.ring ? "your watch and your ring" : p.watch ? "your watch" : "your ring";
        return "Sync needs Bluetooth access to reach " + what + ".";
    }

    /**
     * @param demo demo mode (nothing syncs)
     * @param keyState {@link KeyState} of the ring key, read
     * @param watchAdded a watch is added for syncing
     */
    public static SyncPlan of(boolean demo, int keyState, boolean watchAdded) {
        if (demo) return new SyncPlan(false, false, false, false, null);
        boolean ringReady = keyState == KeyState.READABLE;
        if (watchAdded) {
            if (ringReady) return new SyncPlan(true, true, false, false, null);
            return new SyncPlan(false, true, false, false, keyState == KeyState.UNREADABLE ? KEY_NOTE : null);
        }
        if (ringReady) return new SyncPlan(true, false, false, false, null);
        if (keyState == KeyState.UNREADABLE) return new SyncPlan(false, false, true, false, null);
        // no key: with no ring by choice the way to add one; without any choice Setup opens before Sync can be pressed
        return new SyncPlan(false, false, false, true, null);
    }
}
