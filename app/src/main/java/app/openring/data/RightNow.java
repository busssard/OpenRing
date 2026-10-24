package app.openring.data;

import android.content.Context;

import java.util.List;

import app.openring.core.EventCodec;

/**
 * The most recent readings the ring stored (from the last sync), and whether it looks worn.
 * Skin temperature tells a worn ring from one on a table or charger: a finger reads about
 * 28 to 36 C, a ring lying in a room reads room temperature.
 */
public final class RightNow {
    public double skinTemp = Double.NaN, skinTempAt = Double.NaN;
    public double hr = Double.NaN, hrAt = Double.NaN;
    public double spo2 = Double.NaN, spo2At = Double.NaN;
    /** 1 worn, 0 not worn, -1 unknown. */
    public int worn = -1;

    public static final double WORN_MIN_C = 27.0;

    static final int[] HR_TAGS = {EventCodec.GREEN_IBI_QUALITY, EventCodec.AOHR_EVENT, EventCodec.HRV_EVENT};
    static final int[] TEMP_TAGS = {EventCodec.TEMP_EVENT, EventCodec.SLEEP_TEMP_EVENT};

    /**
     * The newest plausible heart rate in one stored event and when it was measured, {bpm, unix}, or null if
     * none. Dated like {@code Analyzer}: an HRV event holds six values 5 minutes apart, an AOHR event values
     * 1.92 s apart, a green IBI event counts at its start (issues #94, #97). One plausibility range for all
     * ({@link EventCodec#plausibleHr}, for beat intervals {@link EventCodec#plausibleIbi}, issue #148).
     */
    static double[] newest(Db.Row row) {
        if (row.tag == EventCodec.GREEN_IBI_QUALITY) {
            int[][] g = EventCodec.greenIbi(row.body);
            if (g != null) for (int i = g[0].length - 1; i >= 0; i--) if (g[1][i] == 1 && EventCodec.plausibleIbi(g[0][i])) return new double[] {60000.0 / g[0][i], row.unix};
        } else if (row.tag == EventCodec.AOHR_EVENT) {
            int[][] a = EventCodec.aohr(row.body);
            if (a != null) for (int i = a[0].length - 1; i >= 0; i--) if (EventCodec.plausibleHr(a[0][i])) return new double[] {a[0][i], row.unix + i * EventCodec.AOHR_INTERVAL_S};
        } else {
            int[][] h = EventCodec.hrv(row.body);
            if (h != null) for (int i = h[0].length - 1; i >= 0; i--) if (EventCodec.plausibleHr(h[0][i])) return new double[] {h[0][i], row.unix + i * EventCodec.HRV_INTERVAL_S};
        }
        return null;
    }

    /**
     * The times of the plausible heart rates in one stored event, dated and checked like {@link #newest}
     * (issues #94, #97). Each time gets the event's number {@code id} in {@code ids}, so values of one
     * event never vouch for each other.
     */
    static void beatTimes(Db.Row row, int id, List<Double> out, List<Integer> ids) {
        int before = out.size();
        if (row.tag == EventCodec.GREEN_IBI_QUALITY) {
            if (newest(row) != null) out.add(row.unix);
        } else if (row.tag == EventCodec.AOHR_EVENT) {
            int[][] a = EventCodec.aohr(row.body);
            if (a != null) for (int i = 0; i < a[0].length; i++) if (EventCodec.plausibleHr(a[0][i])) out.add(row.unix + i * EventCodec.AOHR_INTERVAL_S);
        } else {
            int[][] h = EventCodec.hrv(row.body);
            if (h != null) for (int i = 0; i < h[0].length; i++) if (EventCodec.plausibleHr(h[0][i])) out.add(row.unix + i * EventCodec.HRV_INTERVAL_S);
        }
        for (int i = before; i < out.size(); i++) ids.add(id);
    }

    /**
     * When the ring came off the finger (issue #74), from 7 days of stored skin temperature and heart
     * rate up to the newest temperature reading (not up to now: a ring last synced 10 days ago still
     * has its history, issue #94).
     */
    public static app.openring.core.analysis.LastWorn.Result lastWorn(Db db, double nowUnix) {
        double end = nowUnix + 3600;
        // the newest reading up to now (a reading dated in the future by a broken clock does not count)
        double newest = db.newestBefore(TEMP_TAGS, end);
        if (Double.isNaN(newest)) return new app.openring.core.analysis.LastWorn.Result();
        double since = newest - 7 * 86400;
        List<Db.Row> temps = db.events(TEMP_TAGS, since, end);
        double[] tt = new double[temps.size()], tc = new double[temps.size()];
        int n = 0;
        for (Db.Row row : temps) {
            double[] t = EventCodec.temps(row.body);
            if (t == null || t.length == 0) continue;
            tt[n] = row.unix;
            tc[n++] = t[0];
        }
        List<Db.Row> beats = db.events(HR_TAGS, since, end);
        // HRV events come from sleep; the others from the daytime measurement, which tells whether
        // missing heart rate while warm means anything (LastWorn.hrRule)
        List<Double> night = new java.util.ArrayList<Double>(), day = new java.util.ArrayList<Double>();
        List<Integer> nightId = new java.util.ArrayList<Integer>(), dayId = new java.util.ArrayList<Integer>();
        int id = 0;
        for (Db.Row row : beats) {
            boolean asleep = row.tag == EventCodec.HRV_EVENT;
            beatTimes(row, id++, asleep ? night : day, asleep ? nightId : dayId);
        }
        return app.openring.core.analysis.LastWorn.find(java.util.Arrays.copyOf(tt, n), java.util.Arrays.copyOf(tc, n),
                array(night), ints(nightId), array(day), ints(dayId));
    }

    static double[] array(List<Double> l) {
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    static int[] ints(List<Integer> l) {
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    public static RightNow load(Context c) {
        Db db = Db.get(c);
        RightNow r = new RightNow();
        double since = System.currentTimeMillis() / 1000.0 - 36 * 3600;
        List<Db.Row> temps = db.latest(TEMP_TAGS, since, 1);
        if (!temps.isEmpty()) {
            double[] t = EventCodec.temps(temps.get(0).body);
            if (t != null && t.length > 0) {
                r.skinTemp = t[0];
                r.skinTempAt = temps.get(0).unix;
                // only trust the wear verdict for a reading from the last 20 minutes
                if (System.currentTimeMillis() / 1000.0 - r.skinTempAt < 20 * 60) r.worn = t[0] >= WORN_MIN_C ? 1 : 0;
            }
        }
        // the newest value by its own time, not its event's start (an HRV event's last value is 25 minutes
        // after it, issue #97), so an event that began earlier but reaches later can win
        for (Db.Row row : db.latest(HR_TAGS, since, 60)) {
            double[] v = newest(row);
            if (v != null && (Double.isNaN(r.hrAt) || v[1] > r.hrAt)) {
                r.hr = Math.round(v[0]);
                r.hrAt = v[1];
            }
        }
        for (Db.Row row : db.latest(new int[] {EventCodec.SPO2_EVENT}, since, 30)) {
            int[] s = EventCodec.spo2(row.body);
            if (s == null) continue;
            double sum = 0;
            int n = 0;
            for (int v : s) if (v >= 70 && v <= 100) { sum += v; n++; }
            if (n > 0) {
                r.spo2 = Math.round(sum / n);
                r.spo2At = row.unix;
                break;
            }
        }
        return r;
    }
}
