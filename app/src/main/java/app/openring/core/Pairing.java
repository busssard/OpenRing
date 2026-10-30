package app.openring.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Pairing a ring in Setup (issue #218): the order of the steps, so a key the app writes to a ring is never lost, and
 * the hand over of the result to the Setup screen shown now. {@code RingManager.pair} plugs in the real ring and the
 * phone's settings; the JVM tests plug in made up ones that fail at every step.
 *
 * <p>The rule: a key goes to the ring only after it is stored on the phone (the kept key), and the saved key, the
 * ring's serial and "set up" are written together once the ring accepted it. What runs after that (measurements on,
 * clock) is best effort and never turns a pairing into a failure.
 */
public final class Pairing {
    private Pairing() {}

    /** Raw text when the phone could not store the key before writing it; {@link SyncError} makes it plain. */
    public static final String KEY_NOT_KEPT = "This phone could not keep the key, nothing was written to the ring";
    /** Raw text when the ring accepted the key but the phone could not save it (a kept or typed key still has it). */
    public static final String KEY_NOT_SAVED = "This phone could not save the key the ring accepted";

    /** Raw text of a ring that already holds another key; {@link SyncError} keeps it as it is. */
    public static final String KEY_EXISTS = "The ring already has a key. Factory reset it first, or use the existing key.";

    /** Added to the message when the measurements could not be switched on after pairing. */
    public static final String MEASUREMENTS_OFF = ". Daytime heart rate and blood oxygen could not be switched on yet, "
            + "you can do it in Settings, Measurements on the ring.";

    /** Status lines while pairing runs. */
    public static final String WORKING = "Working, accept the pairing request if Android shows one";
    public static final String ALREADY = "Pairing is already running.";

    /** The card on "Connect your ring" while a kept key exists. */
    public static final String KEPT_TITLE = "Key from your last try";
    public static final String KEPT_TEXT = "Your last try may have written this key to the ring and did not finish, so the ring may need it now. "
            + "Copy it and keep it somewhere safe. Pair and create a new key uses this key again instead of making another one, "
            + "so you can simply try again.";
    /** Added to the sentence of a failed try that leaves a kept key. */
    public static final String KEPT_NOTE = "The key kept from a try that did not finish is shown at the top of this page.";

    /** The message as a sentence for the key page: a full stop added unless it has one. */
    public static String sentence(String message) {
        String m = message == null ? "" : message.trim();
        return m.endsWith(".") ? m : m + ".";
    }

    /** Under a kept key in Settings, Key and backup. */
    public static final String KEPT_SETTINGS = "A pairing that did not finish kept this key, so the ring it was meant for may need it. "
            + "It goes once that ring is paired again.";

    /** The caption over a kept key: which ring it was meant for. */
    public static String keptFor(String serial) {
        return serial == null || serial.isEmpty() ? "Kept key" : "Kept key, meant for ring " + serial;
    }

    /** The ring that is being paired (one open connection). */
    public interface Ring {
        String serial() throws IOException, InterruptedException;

        /** Writes the key; returns the ring's status byte (0 ok, 1 it already has a key). */
        int setAuthKey(byte[] key) throws IOException, InterruptedException;

        /** Throws {@link RingClient.AuthException} when the ring does not accept the key. */
        void authenticate(byte[] key) throws IOException, InterruptedException;

        /** A line for the status shown while pairing runs. */
        void status(String line);

        /**
         * After the key is saved, best effort: firmware, and on a ring keyed here the measurements on and the clock.
         * Returns false when the measurements could not be switched on.
         */
        boolean afterPairing(boolean installed) throws InterruptedException;
    }

    /** Where the phone keeps the key (Prefs on the phone). */
    public interface Store {
        /** The key kept from an interrupted try, or null. */
        byte[] kept();

        /** The serial of the ring the kept key was meant for, or null. */
        String keptSerial();

        /**
         * Stores the key on disk, with the serial of the ring it goes to, before it goes to the ring; false when that
         * failed. Only called while no key is kept, so a kept key is never overwritten.
         */
        boolean keep(byte[] key, String serial);

        /** Removes the kept key (the ring it was meant for answered that it holds another one). */
        void forgetKept();

        /** Called before the key is saved: a different ring than the saved one starts a fresh history. */
        void ringIs(String serial) throws IOException;

        /**
         * Saves key, serial and "set up" in one write to disk, notes that the key page was not shown yet, and removes the
         * kept key when {@code dropKept} (it was meant for this ring, which now holds the saved key). False when the
         * write failed (the kept key then stays).
         */
        boolean done(byte[] key, String serial, String message, boolean dropKept);
    }

    /** What to do after the ring answered the key write with {@code status}. */
    public enum Next { VERIFY, VERIFY_OR_TAKEN, REFUSED }

    public static Next afterSetKey(int status) {
        if (status == 0) return Next.VERIFY;
        // the ring already has a key: after an interrupted try it may be exactly this one
        if (status == 1) return Next.VERIFY_OR_TAKEN;
        return Next.REFUSED;
    }

    /** The raw text of a key write the ring refused (status other than 0 and 1). */
    public static String refusal(int status) {
        if (status == 1) return KEY_EXISTS;
        if (status == 5) return "The ring reports unfinished factory tests (status 5).";
        return String.format(Locale.US, "The ring refused the key (status 0x%02x)", status);
    }

    /** The key to use: the typed one; for a new key the kept one from an interrupted try, else {@code fresh}. */
    public static byte[] keyFor(byte[] typed, byte[] kept, byte[] fresh) {
        if (typed != null) return typed;
        return kept != null ? kept : fresh;
    }

    /**
     * Pairs: {@code typed} is the person's key (null for "Pair and create a new key", which always installs). Returns the
     * message for the key page; throws with a raw text on failure.
     *
     * <p>The kept key belongs to one ring (its serial) and is never overwritten: a new key reuses it (also on another
     * ring, so both rings and the phone share it), a typed key is not kept while another key is (the person has it
     * already), and it goes only when the ring it was meant for holds the saved key or answers "wrong key" to it.
     */
    public static String pair(byte[] typed, boolean install, Ring ring, Store store) throws IOException, InterruptedException {
        if (typed == null) install = true;
        String serial = ring.serial();
        byte[] kept = store.kept();
        String keptSerial = kept == null ? null : store.keptSerial();
        byte[] key = keyFor(typed, typed == null ? kept : null, typed == null ? Auth.newKey() : null);
        int status = -1;
        if (install) {
            // stored on the phone before the ring can take it, so the ring never holds a key the phone lost; a key
            // kept from before stays as it is (a typed key is the person's own)
            if (kept == null) {
                if (!store.keep(key, serial)) throw new IOException(KEY_NOT_KEPT);
                kept = key;
                keptSerial = serial;
            }
            ring.status("Installing the key");
            status = ring.setAuthKey(key);
            if (afterSetKey(status) == Next.REFUSED) throw new IOException(refusal(status));
        }
        ring.status("Verifying the key");
        try {
            ring.authenticate(key);
        } catch (RingClient.AuthException e) {
            if (status != 1 || e.state != Auth.WRONG_KEY) throw e;
            // the ring holds another key: after a cut off try it may be the kept one
            boolean keptWrong = kept != null && Arrays.equals(kept, key);
            if (kept != null && !keptWrong) {
                try {
                    ring.authenticate(kept);
                    key = kept;
                } catch (RingClient.AuthException k) {
                    if (k.state != Auth.WRONG_KEY) throw k;
                    keptWrong = true;
                }
            }
            if (keptWrong || !Arrays.equals(key, kept)) {
                // only the ring the kept key was meant for can say it is not its key
                if (keptWrong && serial.equals(keptSerial)) store.forgetKept();
                throw new IOException(KEY_EXISTS);
            }
        }
        store.ringIs(serial);
        String message = "Paired with " + serial;
        boolean dropKept = kept != null && serial.equals(keptSerial);
        if (!store.done(key, serial, message, dropKept)) throw new IOException(KEY_NOT_SAVED);
        // from here the pairing is done: what follows is best effort
        boolean on;
        try {
            on = ring.afterPairing(install);
        } catch (RuntimeException e) {
            on = !install;
        } catch (InterruptedException e) {
            // a stop now no longer undoes a pairing the phone already saved
            Thread.currentThread().interrupt();
            on = !install;
        }
        return on ? message : message + MEASUREMENTS_OFF;
    }

    /** The outcome of a pairing. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * One pairing at a time that outlives the Setup screen which started it (pattern of {@link OneRun}, issue #130): the
     * screen attached now is told when it ends; a result that ends while no screen is attached waits for the next one.
     */
    public static final class Run<T> {
        private boolean running;
        private int card;
        private T owner;
        private Result result;

        /** Starts a pairing from card {@code card}; false (nothing changes) while one runs. Drops a result nobody took. */
        public synchronized boolean start(int card) {
            if (running) return false;
            running = true;
            this.card = card;
            result = null;
            return true;
        }

        public synchronized boolean running() {
            return running;
        }

        /** The card that started the pairing running now or last. */
        public synchronized int card() {
            return card;
        }

        public synchronized void attach(T o) {
            owner = o;
        }

        /**
         * A screen goes away; only clears when it is still the one attached. {@code forGood} (closed, not turned) also
         * drops a result waiting for it: a success is in the phone's settings anyway, a failure is old news.
         */
        public synchronized void detach(T o, boolean forGood) {
            if (owner != o) return;
            owner = null;
            if (forGood) result = null;
        }

        /** The pairing ended: keeps the result for {@link #take} and returns the screen to tell (null when none). */
        public synchronized T finish(boolean ok, String message) {
            running = false;
            result = new Result(ok, message);
            return owner;
        }

        /** The result waiting for a screen, once; null when there is none. */
        public synchronized Result take() {
            Result r = result;
            result = null;
            return r;
        }
    }
}
