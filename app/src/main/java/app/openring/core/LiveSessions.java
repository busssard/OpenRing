package app.openring.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bookkeeping of live heart rate sessions (issue #153), kept apart from Bluetooth so the JVM tests
 * cover it. Every session gets a token. {@code current} is the newest token handed out (a stop also
 * moves it on), {@code running} the token of the newest session queued or running (0 when none).
 * A session's worker checks {@link #isCurrent} to know it should end, and clears only its own mark
 * when it ends, so an older session ending never clears a newer one. {@code ble/RingManager} uses it.
 */
public final class LiveSessions {
    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean spo2;

    /**
     * A Live screen wants a session. Returns 0 when the running session is joined (the rule of
     * {@link RingTasks#rejoinLive}), otherwise the token of a new session, which also ends any older
     * one at its next check. A blood oxygen switch starts a new session too.
     */
    public synchronized int start(boolean wantSpo2) {
        if (RingTasks.rejoinLive(running.get(), current.get(), spo2, wantSpo2)) return 0;
        spo2 = wantSpo2;
        int token = current.incrementAndGet();
        running.set(token);
        return token;
    }

    /** Stop the current session: it ends at its next check of {@link #isCurrent}. */
    public void stop() {
        current.incrementAndGet();
    }

    /** Whether the session {@code token} is still the current one (not stopped, not replaced). */
    public boolean isCurrent(int token) {
        return token == current.get();
    }

    /**
     * Called by a queued session before it takes the ring: true when it is no longer current (stopped
     * or replaced while it waited), and then its own mark is cleared and it must not start.
     */
    public boolean stale(int token) {
        if (isCurrent(token)) return false;
        ended(token);
        return true;
    }

    /**
     * The session {@code token} ends (it found itself stale while queued, could not get the ring, or
     * is done). Clears only its own mark: a newer session may be queued already.
     */
    public void ended(int token) {
        running.compareAndSet(token, 0);
    }

    /** Whether the newest session measures blood oxygen too. */
    public boolean spo2() {
        return spo2;
    }

    /** The token of the newest session queued or running, 0 when none. */
    public int running() {
        return running.get();
    }
}
