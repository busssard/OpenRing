package app.openring.core.analysis;

/**
 * The last few seconds of a night's audio (issue #380, phase 1b): the listening service keeps only this much, and saves a clip
 * from it once the detector says the clip's window has passed ({@link SoundDetector#takeReady}). Samples are counted from the
 * first one added (index 0); older samples than {@link #capacity} behind the newest are gone. Plain Java, no Android.
 */
public final class AudioRing {
    private final short[] buf;
    private long total;

    /** A ring that holds the last {@code capacity} samples (at least 1). */
    public AudioRing(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        buf = new short[capacity];
    }

    public int capacity() {
        return buf.length;
    }

    /** Samples added so far (the index of the next one). */
    public long total() {
        return total;
    }

    /** The index of the oldest sample still held. */
    public long oldest() {
        return Math.max(0, total - buf.length);
    }

    /** Adds {@code len} samples of {@code s} from {@code off}. */
    public void add(short[] s, int off, int len) {
        for (int i = 0; i < len; i++) {
            buf[(int) (total % buf.length)] = s[off + i];
            total++;
        }
    }

    /**
     * The samples from index {@code from} up to (not including) {@code to}, as far as they are still held: the part before
     * {@link #oldest} and after {@link #total} is left out (an empty array when nothing is left).
     */
    public short[] copy(long from, long to) {
        long a = Math.max(from, oldest()), b = Math.min(to, total);
        if (b <= a) return new short[0];
        short[] out = new short[(int) (b - a)];
        for (long i = a; i < b; i++) out[(int) (i - a)] = buf[(int) (i % buf.length)];
        return out;
    }

    /** Forgets everything (a new detector starts at index 0). */
    public void clear() {
        total = 0;
    }
}
