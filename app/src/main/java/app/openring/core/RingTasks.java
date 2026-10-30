package app.openring.core;

/**
 * The decisions behind the one ring lock (issues #43 and #153), kept apart from Bluetooth so the JVM
 * tests cover them: which task a stop reaches, when Live rejoins a running session, and which sync
 * shows the final status line, and that demo mode never reaches the ring (issue #185). {@code ble/RingManager} applies them.
 */
public final class RingTasks {
    private RingTasks() {
    }

    /**
     * Which sync holds the ring for its download right now. Since 1.19 a sync gives the ring back
     * before it analyses (issue #21), so a stop meant for it must not reach Live, a game or another
     * sync that took the ring afterwards (issue #43).
     */
    public static final class Owner {
        private Object holder;

        /** {@code task} got the ring lock. */
        public synchronized void take(Object task) {
            holder = task;
        }

        /** {@code task} gives the ring back; does nothing when another task holds it. */
        public synchronized void release(Object task) {
            if (holder == task) holder = null;
        }

        public synchronized boolean holds(Object task) {
            return task != null && holder == task;
        }

        /**
         * Runs {@code stop} only while {@code task} holds the ring, under the same monitor as
         * {@link #release}, so the ring cannot change hands between the check and the stop.
         * Returns true when it ran.
         */
        public synchronized boolean stopIf(Object task, Runnable stop) {
            if (task == null || holder != task) return false;
            stop.run();
            return true;
        }
    }

    /**
     * Whether a new Live screen joins the session that runs (issue #153): only when one runs
     * ({@code runningToken} is not 0), it is still the current one (a stopped session that is still
     * cleaning up has an older token) and it measures the same things.
     */
    public static boolean rejoinLive(int runningToken, int currentToken, boolean runningSpo2, boolean wantSpo2) {
        return runningToken != 0 && runningToken == currentToken && runningSpo2 == wantSpo2;
    }

    /**
     * The status line a sync leaves behind when it ends (issue #43): nothing while another sync still
     * runs (that one shows its own), otherwise its own line, or for a sync that never got the ring
     * ({@code own} null) the line an earlier sync left behind ({@code leftBehind}, may be null).
     */
    public static String syncEndStatus(int syncsLeft, String own, String leftBehind) {
        if (syncsLeft > 0) return null;
        return own != null ? own : leftBehind;
    }

    /**
     * Null when a ring task may connect to the ring and store what it reads: demo mode is off and the database open
     * now is the real file. Otherwise the raw text {@link SyncError#DEMO_RING} (issue #185): demo mode lives in its own
     * file and never uses the ring, also when demo mode was turned on or off while a session ran ({@code demoFile}).
     */
    public static String demoRefusal(boolean demo, boolean demoFile) {
        return demo || demoFile ? SyncError.DEMO_RING : null;
    }
}
