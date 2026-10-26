package app.openring.core.analysis;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling heart rate variability (RMSSD) from a live stream of inter beat intervals. Beats that
 * jump more than 20 % from the previous one are treated as artifacts (a missed or doubled beat)
 * and break the chain instead of inflating the value.
 */
public final class LiveHrv {
    private final int window;
    private final Deque<Integer> ibis = new ArrayDeque<Integer>();

    public LiveHrv(int window) {
        this.window = window;
    }

    public void add(int ibiMs) {
        // the one beat range (issue #148), only to drop impossible values; artefacts (a missed or doubled
        // beat) are handled by the 20 % rule in rmssd(), so no stricter floor of its own here. The two
        // halves of a doubled beat still form one pair with almost no difference, which reads RMSSD a
        // little low (about 0.5 ms for one in 24 beats); the old 300 ms floor had the same effect
        if (!app.openring.core.EventCodec.plausibleIbi(ibiMs)) return;
        ibis.addLast(ibiMs);
        while (ibis.size() > window) ibis.removeFirst();
    }

    /** RMSSD in ms over the window, or NaN with fewer than 8 clean successive pairs. */
    public double rmssd() {
        double sum = 0;
        int n = 0;
        Integer prev = null;
        for (Integer cur : ibis) {
            if (prev != null && Math.abs(cur - prev) <= prev * 0.2) {
                double d = cur - prev;
                sum += d * d;
                n++;
            }
            prev = cur;
        }
        return n < 8 ? Double.NaN : Math.sqrt(sum / n);
    }

    /** Beat to beat intervals in the window now. */
    public int size() {
        return ibis.size();
    }

    /** Successive pairs in the window that {@link #rmssd} counts (a jump of at most 20 %). */
    public int cleanPairs() {
        int n = 0;
        Integer prev = null;
        for (Integer cur : ibis) {
            if (prev != null && Math.abs(cur - prev) <= prev * 0.2) n++;
            prev = cur;
        }
        return n;
    }

    public void clear() {
        ibis.clear();
    }

    /** Fewest clean beats for a value: {@link #rmssd} needs 8 clean successive pairs. */
    public static final int MIN_BEATS = 9;

    /**
     * The one line under the live tiles that says what the HRV tile shows (issue #152). HRV comes only
     * from real beat to beat intervals; always on heart rate values are averages, so they never count.
     *
     * @param beats      beat to beat intervals in the window ({@link #size})
     * @param cleanPairs pairs of them that count ({@link #cleanPairs}); 8 are needed
     * @param heartRates always on heart rate values received this session
     * @param rmssd      {@link #rmssd}, NaN when there is no value yet
     */
    public static String note(int beats, int cleanPairs, int heartRates, double rmssd) {
        if (!Double.isNaN(rmssd)) {
            return "HRV: the typical change from one heartbeat to the next, from the last " + beats + " heartbeats.";
        }
        if (beats >= MIN_BEATS) {
            // enough beats, but too many big jumps between them (a moving hand, missed beats)
            return "HRV not available yet: the heartbeats so far jump too much to count. Keep your hand still.";
        }
        if (beats > 0) return "HRV not available yet: it needs at least " + MIN_BEATS + " clean heartbeats, " + (cleanPairs > 0 ? cleanPairs + 1 : beats) + " so far.";
        if (heartRates > 0) {
            return "HRV not available yet: the ring sent heart rate averages only, and HRV needs single heartbeats. "
                    + "Your HRV while asleep is on the Sleep tab.";
        }
        return "HRV appears once the ring sends single heartbeats.";
    }
}
