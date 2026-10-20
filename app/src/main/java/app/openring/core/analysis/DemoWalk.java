package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A made up search walk for demo mode and the screenshots (issue #74): around a 5 × 5 m room and
 * across it, with the ring lying at a known spot. Nothing here comes from a real ring.
 */
public final class DemoWalk {
    /** Where the made up ring lies, in metres from the start (right, ahead). */
    public static final double RING_X = 2.8, RING_Y = 2.3;

    /** One event: {time ms, kind, value}; kind 0 heading (degrees), 1 a step, 2 a signal reading (dBm). */
    public static final int HEADING = 0, STEP = 1, SIGNAL = 2;

    private DemoWalk() {}

    /** The events of the whole walk, in time order (about 50 s). */
    public static List<double[]> events() {
        List<double[]> out = new ArrayList<double[]>();
        Random r = new Random(74);
        // around the room 0.8 m from the walls and across it; the start is the first corner
        double[][] wp = {{0, 0}, {0, 3.3}, {3.4, 3.4}, {3.3, 0}, {0.1, 0.1}, {1.7, 1.8}, {3.3, 3.4}};
        double px = 0, py = 0, hd = 0;
        long t = 0, nextStep = 0, nextRead = 0;
        int k = 1;
        while (k < wp.length && t < 200000) {
            double bx = wp[k][0] - px, by = wp[k][1] - py;
            double dh = ((Math.toDegrees(Math.atan2(bx, by)) - hd) % 360 + 540) % 360 - 180;
            boolean turning = Math.abs(dh) > 5;
            if (turning) hd += Math.signum(dh) * Math.min(Math.abs(dh), 90 * 0.04);
            out.add(new double[] {t, HEADING, SearchWalk.norm(hd + r.nextGaussian() * 2)});
            if (!turning && t >= nextStep) {
                nextStep = t + 1200;
                double step = Math.min(0.72, Math.hypot(bx, by));
                px += step * Math.sin(Math.toRadians(hd));
                py += step * Math.cos(Math.toRadians(hd));
                out.add(new double[] {t + 1, STEP, 1});
                if (Math.hypot(wp[k][0] - px, wp[k][1] - py) < 0.3) k++;
            }
            if (t >= nextRead) {
                nextRead = t + 125;
                double dx = RING_X - px, dy = RING_Y - py, flat = Math.hypot(dx, dy), d = Math.sqrt(flat * flat + 0.5);
                double cos = flat < 1e-6 ? 1 : (dx * Math.sin(Math.toRadians(hd)) + dy * Math.cos(Math.toRadians(hd))) / flat;
                double v = -60 - 25 * Math.log10(d) - 8 * (1 - cos) / 2 + r.nextGaussian() * 3;
                if (r.nextDouble() > 0.2) out.add(new double[] {t + 2, SIGNAL, Math.round(v)});
            }
            t += 40;
        }
        return out;
    }

    /** Feeds events up to {@code untilMs} (from index {@code from}) into a walk; returns the next index. */
    public static int feed(SearchWalk w, List<double[]> events, int from, long untilMs) {
        int i = from;
        for (; i < events.size() && events.get(i)[0] <= untilMs; i++) {
            double[] e = events.get(i);
            long t = (long) e[0];
            if (e[1] == HEADING) w.heading(t, e[2], true);
            else if (e[1] == STEP) w.steps(t, 1);
            else w.rssi(t, e[2]);
        }
        return i;
    }
}
