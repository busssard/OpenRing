package app.openring.core;

/**
 * What the Live screen measures while it is in front, kept apart from Android so the JVM tests cover it. It measures
 * only while in front: leaving it (Home, another app, the screen off) gives the ring back at once, so a background sync
 * can use it (issue #187), and coming back starts a new session ({@link LiveSessions}, the restart of issue #153). In
 * demo mode it only ever shows made up readings ({@link DemoLive}) and never uses the ring (issue #185).
 * {@code ui/LiveActivity} applies it.
 */
public final class LiveScreen {
    /** Where the readings come from: the ring, or made up demo readings. */
    public enum Source { RING, DEMO }

    /** The status line in demo mode. */
    public static final String DEMO_STATE = "Demo: made up readings, no ring is connected. Leave the demo in Settings to measure with your ring.";
    /** The line under it in demo mode, where a ring session says where its readings come from. */
    public static final String DEMO_SOURCE = "Made up beats, sent in small batches every few seconds like a Ring 4 does";
    /** The status line when Live comes back to the front after it paused. */
    public static final String AGAIN = "Starting again. Live pauses while it is not on screen, so a sync can use the ring.";

    private Source running;
    private int starts;

    /**
     * The screen came to the front: what to start, or null when it measures already. Demo mode gives
     * {@link Source#DEMO}, never the ring.
     */
    public synchronized Source resume(boolean demo) {
        if (running != null) return null;
        running = demo ? Source.DEMO : Source.RING;
        starts++;
        return running;
    }

    /** The screen left the front: what to stop (the ring is given back at once), or null when nothing measures. */
    public synchronized Source pause() {
        Source s = running;
        running = null;
        return s;
    }

    /**
     * The blood oxygen switch changed: what to start again with the new choice, or null when the screen is not in
     * front (the next {@link #resume} starts with it).
     */
    public synchronized Source restart() {
        return running;
    }

    /** What measures now, null while the screen is not in front. */
    public synchronized Source running() {
        return running;
    }

    /** The status line as a measurement starts: demo text, "Starting", or {@link #AGAIN} after a pause. */
    public synchronized String startText() {
        if (running == Source.DEMO) return DEMO_STATE;
        return starts > 1 ? AGAIN : "Starting";
    }
}
