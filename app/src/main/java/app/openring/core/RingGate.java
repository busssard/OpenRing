package app.openring.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ring lock and the count of syncs under way, shared by the ring tasks ({@code ble/RingManager}) and the code that
 * swaps the database file: a restore of a backup's history and entering demo mode (issue #219). Every ring task that
 * stores what it reads (a sync download, Live, the finder's ask, Settings actions) holds {@link #op} while it stores, and
 * a sync counts in {@link #syncs} from its start to the end of its analysis. A swap runs through {@link #whenFree}, so no
 * download writes into the file that is swapped out or into the one that comes in.
 */
public final class RingGate {
    /** The one gate of the app (one ring, one database). */
    public static final RingGate RING = new RingGate();

    /** Only one task uses the ring; a swap holds it too. */
    public final ReentrantLock op = new ReentrantLock();
    /** Syncs under way, counted from their start (also while they wait for the ring) to the end of their analysis. */
    public final AtomicInteger syncs = new AtomicInteger();

    /** Why {@link #whenFree} waits. */
    public static final int FOR_RING = 1, FOR_OTHER = 2;

    /**
     * Longest wait of a swap for the ring: a download after a few weeks away and its analysis take a few minutes. The
     * bound only makes sure nothing waits forever; the person can try again.
     */
    public static final long SWAP_WAIT_MS = 10 * 60 * 1000L;

    /** The status line while a swap waits for a ring task. */
    public static final String WAIT_RING = "Waiting for the sync with your ring to finish";
    /** A restore that gave up waiting: nothing was changed. */
    public static final String RESTORE_REFUSED = "The ring was still syncing, nothing was restored. Try again once the sync has finished.";
    /** Entering demo mode gave up waiting: the real data stays as it is. */
    public static final String DEMO_REFUSED = "The ring was still syncing, demo mode was not turned on. Try again once the sync has finished.";

    /** Something else a swap waits for first, without the ring held (an analysis). */
    public interface Busy {
        boolean busy();
    }

    /** Told once each time the reason to wait changes ({@link #FOR_RING} or {@link #FOR_OTHER}). */
    public interface Wait {
        void waiting(int why);
    }

    /**
     * Runs {@code r} with {@link #op} held once no sync is under way, no ring task holds the ring and {@code other} is
     * not busy. While it waits it holds nothing, so ring tasks and the analysis go on and end. True when {@code r} ran
     * (also when it threw: the exception reaches the caller and the ring is free again); false, and {@code r} did not
     * run, when {@code maxMs} passed or the thread was interrupted (its interrupt flag is kept).
     */
    public boolean whenFree(Runnable r, long maxMs, Busy other, Wait wait) {
        long until = System.currentTimeMillis() + Math.max(0, maxMs);
        int told = 0;
        while (true) {
            int why;
            if (syncs.get() > 0) {
                why = FOR_RING;
            } else if (other != null && other.busy()) {
                why = FOR_OTHER;
            } else if (op.tryLock()) {
                try {
                    // a sync counted while the lock was taken waits for it: leave the ring to it
                    if (syncs.get() == 0) {
                        r.run();
                        return true;
                    }
                } finally {
                    op.unlock();
                }
                why = FOR_RING;
            } else {
                why = FOR_RING;
            }
            if (why != told) {
                told = why;
                if (wait != null) wait.waiting(why);
            }
            long left = until - System.currentTimeMillis();
            if (left <= 0) return false;
            try {
                Thread.sleep(Math.min(left, 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
