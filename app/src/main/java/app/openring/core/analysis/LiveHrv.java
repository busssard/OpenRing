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
        if (ibiMs < 300 || ibiMs > 2000) return;
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
