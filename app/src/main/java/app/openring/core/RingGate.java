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

    /**
     * What {@link #whenFree} returns: {@link #RAN}, or why it gave up (the last thing it waited for, or a stop). The same
     * values tell {@link Wait} why it waits: a sync under way, another ring task holding the ring (Live, Find my ring,
     * a Settings action), an analysis.
     */
    public static final int RAN = 0, FOR_SYNC = 1, FOR_RING = 2, FOR_OTHER = 3, STOPPED = 4;

    /**
     * Longest wait of a swap for the ring: a download after a few weeks away and its analysis take a few minutes. The
     * bound only makes sure nothing waits forever; the person can try again (or stop the wait, review of #248).
     */
    public static final long SWAP_WAIT_MS = 10 * 60 * 1000L;

    /** The status line while a swap waits for a sync. */
    public static final String WAIT_SYNC = "Waiting for the sync with your ring to finish";
    /** The status line while a swap waits for another ring task (Live, Find my ring, a Settings action). */
    public static final String WAIT_RING = "Waiting for the ring to finish its current task";
    /** The status line while a swap waits for an analysis (review of #243). */
    public static final String WAIT_ANALYSIS = "Waiting for the re analysis of your history to finish";

    /** Something else a swap waits for first, without the ring held (an analysis). */
    public interface Busy {
        boolean busy();
    }

    /** Told once each time the reason to wait changes ({@link #FOR_SYNC}, {@link #FOR_RING} or {@link #FOR_OTHER}). */
    public interface Wait {
        void waiting(int why);
    }

    /** The person stopped the wait (Back on the demo dialog, Setup left for good; review of #248). */
    public interface Stop {
        boolean stopped();
    }

    /** The line while a swap waits for {@code why}. */
    public static String waitLine(int why) {
        return why == FOR_SYNC ? WAIT_SYNC : why == FOR_OTHER ? WAIT_ANALYSIS : WAIT_RING;
    }

    /** The sentence after a restore gave up for {@code why}: nothing was changed. */
    public static String restoreRefused(int why) {
        switch (why) {
            case FOR_SYNC:
                return "The ring was still syncing, nothing was restored. Try again once the sync has finished.";
            case FOR_OTHER:
                return "The re analysis of your history was still running, nothing was restored. Try again later.";
            case STOPPED:
                return "Stopped, nothing was restored.";
            default:
                return "The ring was still busy with another task, nothing was restored. Try again then.";
        }
    }

    /** The toast after entering demo mode gave up for {@code why}; it fits two lines (issue #89). */
    public static String demoRefused(int why) {
        switch (why) {
            case FOR_SYNC:
                return "A sync was still running, demo mode stays off. Try again.";
            case FOR_OTHER:
                return "A re analysis was running, demo mode stays off. Try again.";
            case STOPPED:
                return "Stopped, demo mode stays off.";
            default:
                return "The ring was busy, demo mode stays off. Try again.";
        }
    }

    /**
     * Runs {@code r} with {@link #op} held once no sync is under way, no ring task holds the ring and {@code other} is
     * not busy. While it waits it holds nothing, so ring tasks and the analysis go on and end. Returns {@link #RAN} when
     * {@code r} ran (also when it threw: the exception reaches the caller and the ring is free again). Otherwise
     * {@code r} did not run and nothing was held: the reason it last waited for when {@code maxMs} passed, or
     * {@link #STOPPED} when {@code stop} said so or the thread was interrupted (its interrupt flag is kept). A stop is
     * only looked at before {@code r} starts; a running swap is never cut off.
     */
    public int whenFree(Runnable r, long maxMs, Busy other, Wait wait, Stop stop) {
        long until = System.currentTimeMillis() + Math.max(0, maxMs);
        int told = RAN;
        while (true) {
            if (stop != null && stop.stopped()) return STOPPED;
            int why;
            if (syncs.get() > 0) {
                why = FOR_SYNC;
            } else if (other != null && other.busy()) {
                why = FOR_OTHER;
            } else if (op.tryLock()) {
                try {
                    // a sync counted while the lock was taken waits for it: leave the ring to it
                    if (syncs.get() == 0) {
                        r.run();
                        return RAN;
                    }
                } finally {
                    op.unlock();
                }
                why = FOR_SYNC;
            } else {
                why = syncs.get() > 0 ? FOR_SYNC : FOR_RING;
            }
            if (why != told) {
                told = why;
                if (wait != null) wait.waiting(why);
            }
            long left = until - System.currentTimeMillis();
            if (left <= 0) return why;
            try {
                Thread.sleep(Math.min(left, 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return STOPPED;
            }
        }
    }
}
