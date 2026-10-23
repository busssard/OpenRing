package app.openring.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the technical text of a failed ring task into one plain sentence that says what to do: a sync (issue #47),
 * pairing in Setup, the ring actions in Settings, live heart rate and game motion (issue #60). The raw text stays in
 * the Bluetooth log and in the data check; only the screens show the sentence.
 */
public final class SyncError {
    private SyncError() {}

    public enum Kind { BLUETOOTH_OFF, NO_BLUETOOTH, NOT_FOUND, NOT_REACHED, BROKE_OFF, CONNECTION_SETUP, PAIRING, NOT_A_RING,
        KEY_REFUSED, RING_RESET, NO_KEY, BUSY, STOPPED, DEMO, KEY_EXISTS, KEY_NOT_TAKEN, NOT_SAVED, SERIAL_MISMATCH, OTHER_RING, UNKNOWN }

    /** What the ring was doing: a sync, pairing in Setup, an action in Settings, live heart rate or game motion. */
    public enum Task { SYNC, PAIR, ACTION, LIVE }

    public static final String SETUP_AGAIN = "Set it up again in Settings, Careful, Use a different key or ring.";
    public static final String DETAILS = "Details are in Settings, Help and diagnostics, Data check and log.";

    static final String SERIAL_PREFIX = "Refusing: the ring that answered is";
    /** Factory reset: the serial typed is the saved one, but another ring answered (RingManager). */
    public static final String OTHER_RING_PREFIX = "Refusing: a different ring answered";

    /** The games draw the ring state in at most this many lines of this many characters. */
    public static final int GAME_LINES = 3, GAME_LINE = 44;

    /** Sorts a raw error text (from GattLink, RingScanner, RingManager, RingClient, Auth, HistoryBatch) into a class. */
    public static Kind kind(String raw) {
        if (raw == null) return Kind.UNKNOWN;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty() || s.startsWith("unexpected error")) return Kind.UNKNOWN;
        if (s.startsWith(SERIAL_PREFIX.toLowerCase(Locale.ROOT))) return Kind.SERIAL_MISMATCH;
        if (s.startsWith(OTHER_RING_PREFIX.toLowerCase(Locale.ROOT))) return Kind.OTHER_RING;
        if (s.startsWith("the ring already has a key")) return Kind.KEY_EXISTS;
        if (s.startsWith("the ring refused the key") || s.contains("unfinished factory tests") || s.startsWith("no set key response"))
            return Kind.KEY_NOT_TAKEN;
        if (s.equals("the ring did not accept the change")) return Kind.NOT_SAVED;
        if (s.startsWith("demo mode")) return Kind.DEMO;
        if (s.startsWith("no ring key saved")) return Kind.NO_KEY;
        if (s.contains("ring is busy")) return Kind.BUSY;
        if (s.equals("sync stopped") || s.equals("interrupted") || s.equals("cancelled")) return Kind.STOPPED;
        if (s.contains("has no bluetooth")) return Kind.NO_BLUETOOTH;
        if (s.contains("bluetooth is off") || s.contains("scanner unavailable")) return Kind.BLUETOOTH_OFF;
        if (s.startsWith("no ring found")) return Kind.NOT_FOUND;
        if (s.contains("factory reset and has no key")) return Kind.RING_RESET;
        // an unknown and maybe passing state is no reason to set the ring up again (code review of #64)
        if (s.startsWith("authentication failed") && s.contains("unknown auth state")) return Kind.UNKNOWN;
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

    /** The sentence for the screen after a failed sync. */
    public static String plain(String raw) {
        return plain(raw, Task.SYNC);
    }

    /** The sentence for the screen after {@code task} failed. */
    public static String plain(String raw, Task task) {
        boolean sync = task == Task.SYNC, pair = task == Task.PAIR, live = task == Task.LIVE;
        String again = sync ? "sync again" : "try again";
        String againCap = sync ? "Sync again" : "Try again";
        switch (kind(raw)) {
            case BLUETOOTH_OFF: return "Bluetooth is off or not ready. Turn it on and " + again + ".";
            case NO_BLUETOOTH: return "This phone has no Bluetooth, so it cannot reach the ring.";
            case NOT_FOUND: return "Your ring was not found. " + (live ? "Keep it close to the phone" : "Put it on its charger next to the phone")
                    + ", close the Oura app if it is installed, and " + again + ".";
            case NOT_REACHED: return "The ring could not be reached. Keep it close to the phone" + (live ? "" : " (on its charger is best)") + " and " + again + ".";
            case BROKE_OFF: return sync ? "The connection to the ring broke off during the sync. Keep the ring close and sync again, the next sync continues where this one stopped."
                    : "The connection to the ring broke off. Keep the ring close and try again.";
            case CONNECTION_SETUP: return "This phone's Bluetooth could not set up the connection the ring needs. Turn Bluetooth off and on, then " + again + ".";
            case PAIRING: return "The phone and the ring could not pair. " + againCap + " and accept the pairing request when it appears.";
            case NOT_A_RING: return "The device that answered is not an Oura ring. " + againCap + " with your ring close to the phone.";
            case KEY_REFUSED: return pair ? "Your ring did not accept this key. Check that the key is complete and belongs to this ring, "
                    + "or factory reset the ring and use Pair and create a new key."
                    : "Your ring did not accept the key saved on this phone (it may have been paired again with another app). " + SETUP_AGAIN;
            case RING_RESET: return pair ? "Your ring was factory reset and has no key yet. Use Pair and create a new key, or tick Install this key on a factory reset ring."
                    : "Your ring was factory reset and has no key yet. " + SETUP_AGAIN;
            case NO_KEY: return "No ring key is saved on this phone. Finish the setup first.";
            case BUSY: return sync ? "The ring is busy with live heart rate or a game. Sync again in a minute."
                    : "The ring is busy with a sync, live heart rate or a game. Try again in a minute.";
            case STOPPED: return sync ? "The sync was stopped. Sync again when you like." : "This was stopped before it finished. Try again when you like.";
            case DEMO: return "Syncing is off in demo mode.";
            case KEY_EXISTS: return "The ring already has a key. Factory reset it first, or use the existing key.";
            case KEY_NOT_TAKEN: return "The ring did not take the new key. Check that it was factory reset (blinking blue on the dock means done), "
                    + "put it on its charger next to the phone and try again.";
            case NOT_SAVED: return "The ring did not save the change. Keep it close to the phone and try again.";
            case SERIAL_MISMATCH: return serialMismatch(raw);
            case OTHER_RING: return otherRing(raw);
            default:
                if (sync) return "The sync did not finish because of an unexpected problem. Sync again. " + DETAILS;
                if (pair) return "Pairing did not finish because of an unexpected problem. Try again.";
                if (live) return "Something unexpected went wrong. Try again. " + DETAILS;
                return "The ring did not finish this because of an unexpected problem. Try again. " + DETAILS;
        }
    }

    /** "Refusing: a different ring answered (X), ..." as a sentence: the serial was right, another ring was closer. */
    static String otherRing(String raw) {
        int a = raw.indexOf('('), b = a < 0 ? -1 : raw.indexOf(')', a);
        String other = b > a + 1 ? raw.substring(a + 1, b).trim() : "";
        return "Nothing was erased: a different ring" + (other.isEmpty() ? "" : " (" + other + ")")
                + " answered. Move other rings away from the phone and try again.";
    }

    /**
     * The short ring state of the games when motion stopped: the reason without its bracket, and that touch
     * steering goes on. Fits {@link #GAME_LINES} lines of {@link #GAME_LINE} characters (issue #60).
     */
    public static String game(String raw) {
        String t = plain(raw, Task.LIVE);
        int dot = t.indexOf(". ");
        String first = (dot < 0 ? t : t.substring(0, dot + 1)).replaceAll(" \\([^)]*\\)", "");
        return "Ring motion stopped. " + first + " Touch steering works meanwhile.";
    }

    /** Splits a text at spaces into lines of at most {@code max} characters (a longer word gets a line of its own). */
    public static List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String wd : text.trim().split(" +")) {
            if (cur.length() > 0 && cur.length() + 1 + wd.length() > max) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(wd);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /** "Refusing: the ring that answered is X, not Y" (factory reset in Settings) as a sentence that keeps both serials. */
    static String serialMismatch(String raw) {
        String rest = raw.trim().substring(SERIAL_PREFIX.length());
        int cut = rest.lastIndexOf(", not");
        String answered = (cut < 0 ? rest : rest.substring(0, cut)).trim();
        String typed = cut < 0 ? "" : rest.substring(cut + 5).trim();
        return "Nothing was erased: the ring that answered is " + (answered.isEmpty() ? "another one" : answered)
                + ", but you typed " + (typed.isEmpty() ? "nothing" : typed) + ". Start the reset again and type the serial the reset message shows.";
    }
}
