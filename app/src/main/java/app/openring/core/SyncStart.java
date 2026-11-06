package app.openring.core;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The start of a sync, before its download (issue #334): waiting for a long analysis, taking the ring lock, opening the
 * database, the demo check and holding the database for the ring. Any error there, also an {@code Error} such as out of
 * memory, used to end the whole app and could leave the ring lock held. {@link #run} gives back what the steps took and
 * turns the error into the safe text of {@link SyncError#startFailed}. {@code ble/RingManager.syncBlocking} runs its
 * start through it.
 */
public final class SyncStart {
    private SyncStart() {}

    /** What the steps hold: {@link #ring} is true once the database was held for the ring ({@code Db.holdForRing} returned true). */
    public static final class Held {
        public boolean ring;
    }

    /** The start steps. */
    public interface Steps {
        /**
         * Null when the download may begin (the ring lock and the database held, {@code held.ring} true). Otherwise the raw
         * text why the sync does not start, with nothing held.
         */
        String run(Held held) throws Exception;
    }

    /** What {@link #run} gives: the raw text why the sync does not start (null when it may), and the error behind it. */
    public static final class Result {
        public final String message;
        public final Throwable error;

        Result(String message, Throwable error) {
            this.message = message;
            this.error = error;
        }
    }

    /**
     * Runs {@code steps}. On any error it gives the database back first ({@code releaseRing}, only when the steps held it,
     * so never twice), then every hold of {@code op} the steps took (not one taken before), and returns
     * {@link SyncError#startFailed} with the error, for the full stack trace in the log. Without an error it returns what
     * the steps returned; the locks stay as the steps left them.
     */
    public static Result run(ReentrantLock op, Steps steps, Runnable releaseRing) {
        int before = op.getHoldCount();
        Held held = new Held();
        try {
            return new Result(steps.run(held), null);
        } catch (Throwable e) {
            if (held.ring && releaseRing != null) {
                try {
                    releaseRing.run();
                } catch (Throwable r) {
                    e.addSuppressed(r);
                }
            }
            while (op.getHoldCount() > before) op.unlock();
            return new Result(SyncError.startFailed(e), e);
        }
    }
}
