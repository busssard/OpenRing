package app.openring.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Made up Live readings for demo mode (issue #185), so the demo shows the Live screen without ever using a ring. They
 * arrive like a Ring 4 sends them (batches every few seconds): heartbeats around a sitting heart rate of about 63 bpm
 * that drifts slowly, with a breathing rhythm and some noise (live HRV about 35 to 50 ms), the sensor temperature of a
 * ring warming on a finger, and blood oxygen once a second from {@link #SPO2_AFTER_S} seconds after it is switched on. A fixed seed gives the same
 * readings every time, so screenshots stay the same.
 */
public final class DemoLive {
    /** Seconds between two batches, as the ring's stored history is read during Live. */
    public static final double STEP_S = 4;
    /** Blood oxygen needs a still hand for a while: the first made up value comes this many seconds after it was asked for. */
    public static final double SPO2_AFTER_S = 20;

    /** The readings of one batch. */
    public static final class Batch {
        /** Beat to beat intervals in ms, oldest first. */
        public int[] beats;
        /** Blood oxygen in percent, one a second, oldest first; empty without the switch or in its first {@link #SPO2_AFTER_S} seconds. */
        public int[] spo2;
        /** The ring's sensor temperature in °C. */
        public double temp;
    }

    private final Random rnd;
    /** Seconds after the start up to which beats and blood oxygen were made, and of the last call. */
    private double beatClock, spo2Clock, last;
    /** Blood oxygen is measured since the previous call. */
    private boolean spo2On;

    public DemoLive(long seed) {
        rnd = new Random(seed);
    }

    /**
     * The readings from the last call up to {@code t} seconds after the start (nothing new when {@code t} is not later).
     * Blood oxygen only with {@code spo2}.
     */
    public Batch next(double t, boolean spo2) {
        List<Integer> beats = new ArrayList<Integer>();
        while (beatClock < t) {
            double bpm = 63 + 2.5 * Math.sin(2 * Math.PI * beatClock / 70);
            double ibi = 60000 / bpm + 35 * Math.sin(2 * Math.PI * 0.24 * beatClock) + 12 * rnd.nextGaussian();
            int ms = (int) Math.round(Math.max(700, Math.min(1300, ibi)));
            beats.add(ms);
            beatClock += ms / 1000.0;
        }
        List<Integer> ox = new ArrayList<Integer>();
        // switched on (or the start with it on): the first value after a still hand for SPO2_AFTER_S
        if (spo2 && !spo2On) spo2Clock = Math.max(spo2Clock, last + SPO2_AFTER_S);
        spo2On = spo2;
        last = Math.max(last, t);
        while (spo2 && spo2Clock + 1 <= t) {
            spo2Clock += 1;
            ox.add((int) Math.max(95, Math.min(99, Math.round(97 + 0.7 * rnd.nextGaussian()))));
        }
        Batch b = new Batch();
        b.beats = ints(beats);
        b.spo2 = ints(ox);
        b.temp = 33.6 + 0.2 * (1 - Math.exp(-Math.max(0, t) / 120));
        return b;
    }

    /** The screen was not in front until {@code t}: that time gets no readings, as a paused session has none. */
    public void skipTo(double t) {
        beatClock = Math.max(beatClock, t);
        spo2Clock = Math.max(spo2Clock, t);
        last = Math.max(last, t);
        spo2On = false; // a new session: blood oxygen needs its still hand again
    }

    private static int[] ints(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
