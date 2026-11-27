package app.openring.core.analysis;

import app.openring.core.PermissionAsk;

/**
 * What the ring finder shows under its meter, four times a second: the label (the band, or why it is not
 * listening) and either a box with an issue (and maybe a button) or a line under the label. Moved out of
 * FinderActivity so every step of the order has a JVM test (FinderTest); the screen only sets the strings
 * and maps the {@link Action} to an Android intent. The order of the checks is the screen's: the first that
 * applies wins.
 */
public final class FinderSignal {
    private FinderSignal() {}

    /** The line next to the gauge: what its number means. */
    public static final String SIGNAL_LINE = "dBm: closer to 0 is stronger. It tells warmer or colder, not metres.";

    /** The ring is received but with no usable signal strength (issue #95). */
    public static final String UNUSABLE = "This phone receives the ring, so it is within Bluetooth range (about 10 m), "
            + "but the phone gives no usable signal strength for it right now.";

    /** What the issue box's button does; the screen turns it into an intent. */
    public enum Action {
        /** No button. */
        NONE,
        /** Open the app's settings (Android no longer asks for the permission). */
        APP_SETTINGS,
        /** Ask for the permission. */
        ALLOW,
        /** Open the Location settings (Android 11 and older). */
        LOCATION_SETTINGS,
        /** Ask to switch Bluetooth on. */
        BLUETOOTH_ON
    }

    /**
     * Answers that need Android, asked only when the order reaches them (each makes a system call, and
     * {@link #linked} also a rate limited Bluetooth query), so a tick asks no more than before.
     */
    public interface Ask {
        /** The Bluetooth permissions are granted. */
        boolean granted();

        /** Location is on (asked only on Android 11 and older). */
        boolean locationOn();

        /** The ring is connected to this phone right now (this app or another one). */
        boolean linked();
    }

    /** What the screen knows this tick, without asking Android. */
    public static final class Input {
        public boolean demo;
        /** A ring is paired with this app (its address is known). */
        public boolean paired;
        /** Android no longer shows the permission dialog. */
        public boolean permissionBlocked;
        public int sdk;
        public boolean bluetoothOn;
        /** The finder itself holds the ring ({@code FingerCheck.blind}), or null. */
        public String blind;
        /** {@link Proximity}'s band. */
        public int band;
        /** The scan failed and starts again by itself ({@link ScanRetry#waiting}). */
        public boolean retryWaiting;
        /** Android's error code of that failure. */
        public int scanError;
        /** The scan is running. */
        public boolean scanning;
        /** Milliseconds since the ring was last heard, negative for never ({@link Proximity#sinceLast}). */
        public long heardAgo;
        /** Milliseconds since the screen started listening. */
        public long listening;
        /** The paired ring was received with no usable signal strength in the last {@link Proximity#LOST_MS}. */
        public boolean unusable;
        /** Another Oura ring was heard in the last {@link Proximity#LOST_MS}. */
        public boolean otherHeard;
    }

    /** The label under the meter, and the issue box or the line under the label. */
    public static final class State {
        public final String label;
        /** The issue box's text, or the line under the label when {@link #issue} is false. */
        public final String text;
        /** Whether {@link #text} goes into the issue box. */
        public final boolean issue;
        /** The issue box's button text, or null for none. */
        public final String button;
        public final Action action;

        State(String label, String text, boolean issue, String button, Action action) {
            this.label = label;
            this.text = text;
            this.issue = issue;
            this.button = button;
            this.action = action;
        }

        static State issue(String label, String text) {
            return new State(label, text, true, null, Action.NONE);
        }

        static State issue(String label, String text, String button, Action action) {
            return new State(label, text, true, button, action);
        }

        static State line(String label, String line) {
            return new State(label, line, false, null, Action.NONE);
        }
    }

    /** The first check that applies, in the screen's order. */
    public static State of(Input in, Ask ask) {
        if (!in.demo && !in.paired) {
            return State.issue("No ring paired", "No ring is paired with this app yet, so there is no signal to listen for.");
        }
        if (!in.demo && !ask.granted()) {
            String what = PermissionAsk.neededText(in.sdk);
            if (in.permissionBlocked) {
                return State.issue("Not listening", "To hear the ring, this app needs " + what + ". Android no longer asks, so switch it on in the app's settings.",
                        "Open app settings", Action.APP_SETTINGS);
            }
            return State.issue("Not listening", "To hear the ring, this app needs " + what + ".", "Allow", Action.ALLOW);
        }
        if (!in.demo && in.sdk < 31 && !ask.locationOn()) {
            return State.issue("Not listening", "Location is off. Android 11 and older only find Bluetooth devices while Location is on.",
                    "Open Location settings", Action.LOCATION_SETTINGS);
        }
        if (!in.demo && !in.bluetoothOn) {
            return State.issue("Not listening", "Bluetooth is off. Switch it on to listen for the ring.", "Switch on", Action.BLUETOOTH_ON);
        }
        // the finder itself holds the ring (issue #192): it sends no signal meanwhile, say why and for how long
        if (in.blind != null) return State.issue("Connected", in.blind);
        if (in.band != Proximity.NOT_HEARD) return State.line(Proximity.label(in.band), SIGNAL_LINE);
        if (ask.linked()) {
            return State.issue("Connected", "The ring is connected to this phone right now (a sync, Live or another app), so it is within Bluetooth range, "
                    + "about 10 m. While connected it stops sending the signal this meter listens to; the meter carries on when the connection ends.");
        }
        if (in.retryWaiting) {
            return State.issue("Not listening", in.scanError == 6 ? "Android paused the Bluetooth scan for a moment. It starts again by itself within 30 s."
                    : "The Bluetooth scan stopped (error " + in.scanError + "). It starts again by itself within 30 s.");
        }
        if (!in.demo && !in.scanning) {
            // the start waits for Android's scan budget (shared with a sync): not listening yet, so "not heard" would mislead;
            // RingScanner allows 4 starts in 30 s, so the wait is at most 30.2 s (longer only if a sync starts more meanwhile)
            return State.line("Not listening yet", "It starts listening by itself within about half a minute.");
        }
        if (in.heardAgo < 0 && in.listening < Proximity.LOST_MS) return State.line("Listening", "Waiting for the ring's signal");
        // label and line from the same check, so they never disagree (issue #125)
        String why = "The ring is out of range, its battery is empty, or a thick wall or metal is in the way.";
        if (in.otherHeard) why = "This phone hears an Oura ring, but not the one paired with this app.";
        if (in.unusable) why = UNUSABLE;
        return State.line(Proximity.label(Proximity.NOT_HEARD, in.unusable), why);
    }
}
