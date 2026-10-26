package app.openring.core;

/**
 * One background job at a time that outlives the screen which started it (issue #130). Screens
 * attach themselves when they are built and detach when they are destroyed; the job tells whichever
 * screen is attached when it ends. Plain Java so the JVM tests cover it.
 */
public final class OneRun<T> {
    private boolean running;
    private T owner;

    /** Marks the job as running; false (and nothing changes) when one is already running. */
    public synchronized boolean start() {
        if (running) return false;
        running = true;
        return true;
    }

    public synchronized boolean running() {
        return running;
    }

    /** The screen shown now; replaces the one before (a turned phone builds a new screen). */
    public synchronized void attach(T o) {
        owner = o;
    }

    /** A screen goes away; only clears when it is still the one attached. */
    public synchronized void detach(T o) {
        if (owner == o) owner = null;
    }

    /**
     * The screen attached now, without ending anything: for results that need no "running" state
     * of their own, like Settings ring actions, which the ring lock already keeps to one (issue #59).
     */
    public synchronized T attached() {
        return owner;
    }

    /** The job ended: returns the screen to tell (null when none is attached), a new start is allowed. */
    public synchronized T finish() {
        running = false;
        return owner;
    }
}
