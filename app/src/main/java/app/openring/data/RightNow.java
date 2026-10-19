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

    /** The newest plausible heart rate in one stored event, NaN if none. */
    static double bpm(Db.Row row) {
        if (row.tag == EventCodec.GREEN_IBI_QUALITY) {
            int[][] g = EventCodec.greenIbi(row.body);
            if (g != null) for (int i = g[0].length - 1; i >= 0; i--) if (g[1][i] == 1 && g[0][i] >= 300 && g[0][i] <= 2000) return 60000.0 / g[0][i];
        } else if (row.tag == EventCodec.AOHR_EVENT) {
            int[][] a = EventCodec.aohr(row.body);
            if (a != null) for (int i = a[0].length - 1; i >= 0; i--) if (a[0][i] >= 30 && a[0][i] <= 220) return a[0][i];
        } else {
            int[][] h = EventCodec.hrv(row.body);
            if (h != null) for (int i = h[0].length - 1; i >= 0; i--) if (h[0][i] >= 30) return h[0][i];
        }
        return Double.NaN;
    }

    /**
     * When the ring came off the finger (issue #74), from the last 7 days of stored skin
     * temperature and heart rate before {@code nowUnix}.
     */
    public static app.openring.core.analysis.LastWorn.Result lastWorn(Db db, double nowUnix) {
        double since = nowUnix - 7 * 86400;
        List<Db.Row> temps = db.events(TEMP_TAGS, since, nowUnix + 3600);
        double[] tt = new double[temps.size()], tc = new double[temps.size()];
        int n = 0;
        for (Db.Row row : temps) {
            double[] t = EventCodec.temps(row.body);
            if (t == null || t.length == 0) continue;
            tt[n] = row.unix;
            tc[n++] = t[0];
        }
        List<Db.Row> beats = db.events(HR_TAGS, since, nowUnix + 3600);
        double[] ht = new double[beats.size()];
        int m = 0;
        for (Db.Row row : beats) if (!Double.isNaN(bpm(row))) ht[m++] = row.unix;
        return app.openring.core.analysis.LastWorn.find(java.util.Arrays.copyOf(tt, n), java.util.Arrays.copyOf(tc, n), java.util.Arrays.copyOf(ht, m));
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
        for (Db.Row row : db.latest(HR_TAGS, since, 60)) {
            double v = bpm(row);
            if (!Double.isNaN(v)) {
                r.hr = Math.round(v);
                r.hrAt = row.unix;
                break;
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
