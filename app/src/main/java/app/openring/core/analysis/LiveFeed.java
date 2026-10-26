package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

import app.openring.core.EventCodec;
import app.openring.core.RingEvent;

/**
 * Sorts the events of one live refresh (the ring's stored history, read every few seconds while Live
 * runs). Real beat to beat intervals (clean green IBI beats) and always on heart rate stay apart
 * (issue #152): always on heart rate is an average over a couple of seconds, so it gives the heart
 * rate number and chart but never HRV.
 */
public final class LiveFeed {
    /** Clean beat to beat intervals in ms (green IBI, quality 1, inside the one beat range). */
    public final List<Integer> beats = new ArrayList<Integer>();
    /** Always on heart rate in bpm (inside the one heart rate range). */
    public final List<Integer> heartRates = new ArrayList<Integer>();
    /** Blood oxygen in percent, one per second. */
    public final List<Integer> spo2 = new ArrayList<Integer>();
    /** Newest sensor temperature (C) and newest blood oxygen, NaN until one arrives; kept across refreshes. */
    public double temp = Double.NaN, lastSpo2 = Double.NaN;

    /** Seconds between two always on heart rate values of one event (as {@code Analyzer} dates them). */
    public static final double HR_STEP_S = 1.92;

    /** Starts a new refresh: the lists empty, the newest temperature and blood oxygen stay. */
    public void clear() {
        beats.clear();
        heartRates.clear();
        spo2.clear();
    }

    public void add(RingEvent e) {
        if (e.tag == EventCodec.TEMP_EVENT || e.tag == EventCodec.SLEEP_TEMP_EVENT) {
            double[] t = EventCodec.temps(e.body);
            if (t != null && t.length > 0) temp = t[0];
        } else if (e.tag == EventCodec.SPO2_EVENT) {
            int[] v = EventCodec.spo2(e.body);
            if (v != null) for (int x : v) if (x >= 70 && x <= 100) {
                lastSpo2 = x;
                spo2.add(x);
            }
        } else if (e.tag == EventCodec.GREEN_IBI_QUALITY) {
            int[][] g = EventCodec.greenIbi(e.body);
            if (g != null) for (int i = 0; i < g[0].length; i++) if (g[1][i] == 1 && EventCodec.plausibleIbi(g[0][i])) beats.add(g[0][i]);
        } else if (e.tag == EventCodec.AOHR_EVENT) {
            int[][] a = EventCodec.aohr(e.body);
            if (a != null) for (int i = 0; i < a[0].length; i++) if (EventCodec.plausibleHr(a[0][i])) heartRates.add(a[0][i]);
        }
    }

    public void addAll(List<RingEvent> events) {
        for (RingEvent e : events) add(e);
    }
}
