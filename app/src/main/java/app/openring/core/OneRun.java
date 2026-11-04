package app.openring.core;

/**
 * One background job at a time that outlives the screen which started it (issue #130). Screens
 * attach themselves when they are built and detach when they are destroyed; the job tells whichever
 * screen is attached when it ends. Plain Java so the JVM tests cover it.
 */
public final class OneRun<T> {
    private boolean running;
    /** A change came while the job ran (a saved setting): it runs once more before it ends (issue #222). */
    private boolean again;
    private T owner;

    /** Marks the job as running; false (and nothing changes) when one is already running. */
    public synchronized boolean start() {
        if (running) return false;
        running = true;
        again = false;
        return true;
    }

    /**
     * Like {@link #start}, for a start after a saved setting (issue #222): when the job runs already it may have read
     * the setting before it changed, so it is asked to run once more ({@link #finishUnlessAgain}). True when this call
     * started the job.
     */
    public synchronized boolean startOrAgain() {
        if (running) {
            again = true;
            return false;
        }
        running = true;
        again = false;
        return true;
    }

    /** Whether a run once more was asked for and not taken yet. */
    public synchronized boolean againAsked() {
        return running && again;
    }

    /**
     * One pass of the job ended (called by the job itself): false when it was asked to run once more meanwhile (the
     * request is taken, it stays running), else it ends as {@link #finish} does and returns true. One lock for both, so
     * a request can never fall between the last pass and the end.
     */
    public synchronized boolean finishUnlessAgain() {
        if (running && again) {
            again = false;
            return false;
        }
        running = false;
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
        again = false;
        return owner;
    }
}
