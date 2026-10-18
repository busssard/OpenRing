package app.openring.core;

import java.util.Locale;

/**
 * Turns the technical text of a failed sync into one plain sentence that says what to do (issue #47). The raw text
 * stays in the Bluetooth log and in the data check; only the screens show the sentence.
 */
public final class SyncError {
    private SyncError() {}

    public enum Kind { BLUETOOTH_OFF, NO_BLUETOOTH, NOT_FOUND, NOT_REACHED, BROKE_OFF, CONNECTION_SETUP, PAIRING, NOT_A_RING,
        KEY_REFUSED, RING_RESET, NO_KEY, BUSY, STOPPED, DEMO, UNKNOWN }

    public static final String SETUP_AGAIN = "Set it up again in Settings, Careful, Use a different key or ring.";
    public static final String DETAILS = "Details are in Settings, Help and diagnostics, Data check and log.";

    /** Sorts a raw error text (from GattLink, RingScanner, RingManager, RingClient, Auth, HistoryBatch) into a class. */
    public static Kind kind(String raw) {
        if (raw == null) return Kind.UNKNOWN;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty() || s.startsWith("unexpected error")) return Kind.UNKNOWN;
        if (s.startsWith("demo mode")) return Kind.DEMO;
        if (s.startsWith("no ring key saved")) return Kind.NO_KEY;
        if (s.contains("ring is busy")) return Kind.BUSY;
        if (s.equals("sync stopped") || s.equals("interrupted") || s.equals("cancelled")) return Kind.STOPPED;
        if (s.contains("has no bluetooth")) return Kind.NO_BLUETOOTH;
        if (s.contains("bluetooth is off") || s.contains("scanner unavailable")) return Kind.BLUETOOTH_OFF;
        if (s.startsWith("no ring found")) return Kind.NOT_FOUND;
        if (s.contains("factory reset and has no key")) return Kind.RING_RESET;
        if (s.startsWith("authentication failed")) return Kind.KEY_REFUSED;
        if (s.contains("could not raise the bluetooth mtu")) return Kind.CONNECTION_SETUP;
        if (s.contains("bonding") || s.contains("could not subscribe")) return Kind.PAIRING;
        if (s.contains("no oura ring service") || s.contains("characteristics missing")) return Kind.NOT_A_RING;
        if (s.startsWith("connection failed") || s.contains("did not accept the connection") || s.contains("refused to open a gatt")
                || s.startsWith("service discovery") || s.contains("scan failed")) return Kind.NOT_REACHED;
        if (s.equals("ring disconnected") || s.equals("the ring disconnected") || s.startsWith("write failed") || s.contains("never acknowledged") || s.contains("refused the write")
                || s.contains("without a summary packet") || (s.startsWith("no ") && s.contains(" response")) || s.startsWith("storage failed")
                || s.contains("bytes left but the batch was empty") || s.contains("history request failed")) return Kind.BROKE_OFF;
        return Kind.UNKNOWN;
    }

    /** The sentence for the screen. */
    public static String plain(String raw) {
        switch (kind(raw)) {
            case BLUETOOTH_OFF: return "Bluetooth is off or not ready. Turn it on and sync again.";
            case NO_BLUETOOTH: return "This phone has no Bluetooth, so it cannot reach the ring.";
            case NOT_FOUND: return "Your ring was not found. Put it on its charger next to the phone, close the Oura app if it is installed, and sync again.";
            case NOT_REACHED: return "The ring could not be reached. Keep it close to the phone (on its charger is best) and sync again.";
            case BROKE_OFF: return "The connection to the ring broke off during the sync. Keep the ring close and sync again, the next sync continues where this one stopped.";
            case CONNECTION_SETUP: return "This phone's Bluetooth could not set up the connection the ring needs. Turn Bluetooth off and on, then sync again.";
            case PAIRING: return "The phone and the ring could not pair. Sync again and accept the pairing request when it appears.";
            case NOT_A_RING: return "The device that answered is not an Oura ring. Sync again with your ring close to the phone.";
            case KEY_REFUSED: return "Your ring did not accept the key saved on this phone (it may have been paired again with another app). " + SETUP_AGAIN;
            case RING_RESET: return "Your ring was factory reset and has no key yet. " + SETUP_AGAIN;
            case NO_KEY: return "No ring key is saved on this phone. Finish the setup first.";
            case BUSY: return "The ring is busy with live heart rate or a game. Sync again in a minute.";
            case STOPPED: return "The sync was stopped. Sync again when you like.";
            case DEMO: return "Syncing is off in demo mode.";
            default: return "The sync did not finish because of an unexpected problem. Sync again. " + DETAILS;
        }
    }
}
