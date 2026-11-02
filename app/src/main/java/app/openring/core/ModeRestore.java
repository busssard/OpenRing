package app.openring.core;

import java.io.IOException;

/**
 * Puts the ring's measurement modes back after Live or the finder's blink changed them (issue #221). Before a mode is
 * changed, the mode to go back to is kept on the phone ({@link Store}, Prefs); it is cleared only once the ring accepted
 * the restore. A session that ended without a link (out of reach, the app ended) leaves it pending, and the next ring task
 * (sync, Live, Find my ring, Settings) sends it. Kept apart from Android so the JVM tests cover it; {@code ble/RingManager}
 * applies it with the existing feature mode request.
 */
public final class ModeRestore {
    /** No restore pending for a feature. */
    public static final int NONE = -1;
    /** The measurements Live or the finder change: daytime heart rate and blood oxygen. */
    public static final int[] FEATURES = {Protocol.FEATURE_DAYTIME_HR, Protocol.FEATURE_SPO2};

    /** Sends one mode to the ring; true when the ring accepted it. */
    public interface Ring {
        boolean setMode(int feature, int mode) throws IOException, InterruptedException;
    }

    /** The pending modes, kept on the phone. */
    public interface Store {
        int pending(int feature);

        /** {@link #NONE} clears it. */
        void setPending(int feature, int mode);
    }

    /** One line for the Bluetooth log. */
    public interface Log {
        void log(String line);
    }

    private ModeRestore() {
    }

    /**
     * The person's mode from what the ring reports ({@code status[0]}, or null when it was not readable). Settings only
     * ever sends Off or automatic; requested and live are Live's own modes, so a ring found in one of them was left there
     * and counts as automatic. Not readable counts as automatic, as before.
     */
    public static int chosen(int[] status) {
        if (status == null || status.length == 0) return Protocol.MODE_AUTOMATIC;
        return status[0] == Protocol.MODE_OFF ? Protocol.MODE_OFF : Protocol.MODE_AUTOMATIC;
    }

    /** The mode to go back to: a restore still pending wins over what the ring reports now (it may be a stuck mode). */
    public static int previous(int pending, int[] status) {
        return pending >= 0 ? pending : chosen(status);
    }

    /**
     * Keeps the mode to go back to for {@code feature} before it is changed, and returns it: the pending one when there is
     * one, otherwise the person's mode from {@code status}.
     */
    public static int remember(Store store, int feature, int[] status) {
        int prev = previous(store.pending(feature), status);
        store.setPending(feature, prev);
        return prev;
    }

    /**
     * Sends every pending restore, each on its own, so one that throws or is refused does not stop the next; clears only
     * those the ring accepted. Returns how many are still pending. Nothing is sent when nothing is pending.
     */
    public static int restorePending(Ring ring, Store store, Log log) throws InterruptedException {
        int left = 0;
        for (int f : FEATURES) {
            int mode = store.pending(f);
            if (mode < 0) continue;
            boolean ok = false;
            String why = "the ring did not accept it";
            try {
                ok = ring.setMode(f, mode);
            } catch (IOException e) {
                why = e.getMessage() == null ? e.toString() : e.getMessage();
            } catch (RuntimeException e) {
                why = e.toString();
            }
            if (ok) {
                store.setPending(f, NONE);
                log.log(name(f) + " put back to " + modeName(mode));
            } else {
                left++;
                log.log(name(f) + " not put back to " + modeName(mode) + " yet (" + why + "), the next connection tries again");
            }
        }
        return left;
    }

    /**
     * The ring accepted a new mode for {@code feature} that replaces a pending restore: the person's switch in Settings,
     * or the automatic modes a new pairing sends.
     */
    public static void replaced(Store store, int feature) {
        if (store.pending(feature) >= 0) store.setPending(feature, NONE);
    }

    /** A new pairing or a factory reset: the pending modes belonged to the ring as it was before. */
    public static void forget(Store store) {
        for (int f : FEATURES) store.setPending(f, NONE);
    }

    /** The measurement in plain words, for the log. */
    public static String name(int feature) {
        if (feature == Protocol.FEATURE_DAYTIME_HR) return "daytime heart rate";
        if (feature == Protocol.FEATURE_SPO2) return "blood oxygen";
        return "measurement " + feature;
    }

    /** The mode in plain words, for the log. */
    public static String modeName(int mode) {
        switch (mode) {
            case Protocol.MODE_OFF:
                return "off";
            case Protocol.MODE_AUTOMATIC:
                return "automatic";
            case Protocol.MODE_REQUESTED:
                return "requested";
            case Protocol.MODE_CONNECTED_LIVE:
                return "live";
            default:
                return "mode " + mode;
        }
    }
}
