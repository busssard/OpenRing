package app.openring.data;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The hand off between the thread that reads the microphone and the worker that feeds the night (issue #380, phase 1b; moved
 * here from {@code sync/SoundService} by the review of #450 so the pipeline tests run it). The reading thread only offers pieces;
 * the worker takes them in order and feeds them to {@link Feed} (the night: detector, clip encoding, rows), so a slow encode or
 * write never holds up the reading.
 *
 * <ul>
 * <li>A piece the queue has no room for ({@link #QUEUE} pieces, 60 s, the worker held up) is dropped; the night sees the clock
 * jump and stores the missing time as a gap.</li>
 * <li>The worker stops early when the night refuses a piece ({@link #closed}: Delete all closed it) or an error ends it
 * ({@link #failed}); {@link #stopped} tells the reading thread to stop reading.</li>
 * <li>{@link #end} never blocks for good: when the worker stopped early, what is waiting is dropped before the end mark goes in,
 * and the end mark waits at most the given time for room.</li>
 * </ul>
 */
public final class SoundQueue {
    /** Takes one piece of samples; false once the night is closed (nothing done). */
    public interface Feed {
        boolean feed(short[] s, int off, int len, double now);
    }

    /** Pieces waiting for the worker: 60 s of 100 ms pieces. */
    public static final int QUEUE = 600;

    /** A piece of samples and the clock when its last sample came; {@link #END} ends the worker. */
    private static final Object[] END = new Object[0];

    private final ArrayBlockingQueue<Object[]> queue;
    private final AtomicBoolean closed = new AtomicBoolean(), failed = new AtomicBoolean();
    private volatile Throwable error;
    private volatile int droppedAtEnd;
    private final Thread worker;

    /** A queue of {@code capacity} pieces feeding {@code feed}; its worker starts at once. */
    public SoundQueue(int capacity, final Feed feed) {
        queue = new ArrayBlockingQueue<Object[]>(capacity);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                work(feed);
            }
        }, "sleep-sounds-night");
        worker.start();
    }

    private void work(Feed feed) {
        try {
            while (true) {
                Object[] piece = queue.take();
                if (piece == END) return;
                short[] s = (short[]) piece[0];
                if (!feed.feed(s, 0, s.length, (Double) piece[1])) {
                    // Delete all closed the night: nothing of it is written any more
                    closed.set(true);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            error = e;
            failed.set(true);
        }
    }

    /**
     * Hands a copy of the first {@code n} samples of {@code buf} to the worker, {@code now} the clock at its last sample. False
     * when the queue is full (the piece is dropped) or the worker stopped.
     */
    public boolean offer(short[] buf, int n, double now) {
        if (stopped()) return false;
        return queue.offer(new Object[] {Arrays.copyOf(buf, n), now});
    }

    /** Whether the night refused a piece (Delete all closed it). */
    public boolean closed() {
        return closed.get();
    }

    /** Whether an error ended the worker; {@link #error} tells which. */
    public boolean failed() {
        return failed.get();
    }

    /** The error that ended the worker, null for none. */
    public Throwable error() {
        return error;
    }

    /** Whether the worker stopped early (closed or failed): the reading thread stops then. */
    public boolean stopped() {
        return closed.get() || failed.get();
    }

    /**
     * Ends the worker: it feeds what is waiting, then stops; waits at most {@code waitMs} for room for the end mark and again for
     * the worker. When the worker stopped already, what is waiting is dropped (nobody takes it any more), so this never blocks
     * for good. Returns whether the worker ended in time.
     */
    public boolean end(long waitMs) throws InterruptedException {
        if (stopped() || !worker.isAlive()) queue.clear();
        if (!queue.offer(END, waitMs, TimeUnit.MILLISECONDS)) {
            // the worker took nothing for that long: what waits is dropped, the end mark goes in
            droppedAtEnd = queue.size();
            queue.clear();
            queue.offer(END);
        }
        worker.join(waitMs);
        return !worker.isAlive();
    }

    /** Pieces {@link #end} dropped because the worker took nothing for the whole wait (0 for none), for the log. */
    public int droppedAtEnd() {
        return droppedAtEnd;
    }

    /** Pieces waiting now (for the tests). */
    public int waiting() {
        return queue.size();
    }
}
