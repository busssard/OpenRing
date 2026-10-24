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

    public void clear() {
        ibis.clear();
    }
}
