package app.openring.data;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

import app.openring.core.analysis.ScreenTime;

/**
 * Copies the phone's screen on and off times from Android's usage log into the database (issue
 * #13). Android keeps that log for about a week, so this runs whenever the app opens and after
 * every sync. Needs "usage access", which the person allows once in Android's settings.
 */
public final class ScreenUse {
    private ScreenUse() {}

    /** The screen on and off events exist from Android 9. */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** True when the person allowed usage access for this app. */
    @SuppressWarnings("deprecation")
    public static boolean allowed(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens Android's usage access page (this app's own page where the phone supports it). */
    public static void openAccessSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.fromParts("package", c.getPackageName(), null));
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            c.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    /**
     * Reads the log from the start of the newest stored session (so a session that was still running
     * gets its end) or from 10 days back the first time. Returns how many sessions were stored; 0 in
     * demo mode, without access or on Android 8.
     */
    public static int collect(Context c) {
        return collect(c, false);
    }

    private static long lastRun;

    /** As {@link #collect(Context)}; without {@code force} it reads at most once a minute (stepping through days). */
    public static synchronized int collect(Context c, boolean force) {
        Prefs p = Prefs.of(c);
        if (p.demo() || !supported() || !allowed(c)) return 0;
        long now = System.currentTimeMillis();
        if (!force && now - lastRun < 60 * 1000) return 0;
        lastRun = now;
        Db db = Db.get(c);
        double last = db.screenStart(false);
        long from = last > 0 ? (long) Math.floor(last * 1000) : now - 10L * 86400 * 1000;
        List<Double> times = new ArrayList<Double>();
        List<Integer> kinds = new ArrayList<Integer>();
        try {
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int kind = kind(e.getEventType());
                if (kind == 0) continue;
                times.add(e.getTimeStamp() / 1000.0);
                kinds.add(kind);
            }
        } catch (RuntimeException e) {
            return 0; // access withdrawn meanwhile, or the phone's log is not readable
        }
        double[] t = new double[times.size()];
        int[] k = new int[kinds.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = times.get(i);
            k[i] = kinds.get(i);
        }
        List<double[]> sessions = ScreenTime.sessions(t, k, from / 1000.0, now / 1000.0);
        if (sessions.isEmpty()) return 0;
        db.putScreen(sessions);
        // phone use for nights already analysed: the next analysis looks at them again
        if (p.screenAwake()) db.reanalyseFrom(sessions.get(0)[0]);
        return sessions.size();
    }

    /** Android usage event types: 15 screen interactive, 16 screen non interactive, 26 device shutdown. */
    static int kind(int type) {
        switch (type) {
            case 15: return ScreenTime.ON;
            case 16: return ScreenTime.OFF;
            case 26: return ScreenTime.SHUTDOWN;
            default: return 0;
        }
    }
}
