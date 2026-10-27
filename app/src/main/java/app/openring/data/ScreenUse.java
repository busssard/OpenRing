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
     * gets its end) or from 10 days back the first time, and notes the time really read (issue #131).
     * Returns how many sessions were stored; 0 in demo mode, without access or on Android 8.
     */
    public static int collect(Context c) {
        return collect(c, false);
    }

    private static long lastRun;
    /** Version of the counting rules; 2 counts from unlocking (1.20). */
    static final int RULE = 2;
    /**
     * Version of reading the apps in front; 2 reads each time from the newest stored app and merges
     * (issue #71). On the change the app log Android still keeps is read once more and merged in.
     */
    static final int APPS_RULE = 2;

    /** As {@link #collect(Context)}; without {@code force} it reads at most once a minute (stepping through days). */
    public static synchronized int collect(Context c, boolean force) {
        Prefs p = Prefs.of(c);
        if (p.demo() || !supported() || !allowed(c)) return 0;
        long now = System.currentTimeMillis();
        if (!force && now - lastRun < 60 * 1000) return 0;
        lastRun = now;
        Db db = Db.get(c);
        long back = now - 10L * 86400 * 1000;
        // 1.20 counts from unlocking instead of screen on, and one app in front at a time: what
        // Android still keeps (about a week) is read again with the new rules, older days stay as they were
        if (db.kvLong("screen_rule", 1) < RULE) {
            db.deleteScreenSince(back / 1000.0);
            db.put("apps_read", 0);
        }
        double last = db.screenStart(false);
        long screenFrom = last > 0 ? (long) Math.floor(last * 1000) : back;
        // the run that changes the rules starts right where the kept (older) sessions end, so the
        // newest kept one is not read again under the new rule and stored a second time
        if (db.kvLong("screen_rule", 1) < RULE) screenFrom = back;
        // apps read again once with the rules of issue #71: stored rows stay, the new ones merge in
        boolean appsRule = db.kvLong("apps_rule", 1) < APPS_RULE;
        // the first read of a version with apps (1.15) goes back as far as Android keeps the log, for
        // the apps only: screen sessions keep starting at the newest stored one, so none is stored twice
        boolean firstApps = appsRule || db.kvLong("apps_read", 0) == 0;
        // later reads start at the newest stored app, so the app still in front at the last read is
        // seen coming to the front again and its time is neither counted twice nor lost (issue #71)
        long appFrom = (long) Math.floor(ScreenTime.appsReadFrom(firstApps, back / 1000.0, screenFrom / 1000.0, db.appsNewest(), now / 1000.0) * 1000);
        long from = Math.min(screenFrom, appFrom);
        // the screen list and the apps in front (their own list: an app's "in front" also counts as a sign of use)
        ScreenTime.LogSplit read = new ScreenTime.LogSplit(screenFrom / 1000.0, appFrom / 1000.0);
        // the oldest event Android still returned: where its log really begins (issue #131)
        long oldest = Long.MAX_VALUE;
        try {
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                oldest = Math.min(oldest, e.getTimeStamp());
                read.add(e.getTimeStamp() / 1000.0, e.getEventType(), e.getPackageName());
            }
        } catch (RuntimeException e) {
            return 0; // access withdrawn meanwhile, or the phone's log is not readable
        }
        db.putApps(ScreenTime.appSessions(read.appTimes(), read.appKinds(), read.appPackages(), appFrom / 1000.0, now / 1000.0));
        if (firstApps) db.put("apps_read", 1);
        if (appsRule) db.put("apps_rule", APPS_RULE);
        List<double[]> sessions = ScreenTime.sessions(read.screenTimes(), read.screenKinds(), screenFrom / 1000.0, now / 1000.0);
        if (db.kvLong("screen_rule", 1) < RULE) {
            db.put("screen_rule", RULE);
            if (p.screenAwake()) db.reanalyseFrom(back / 1000.0);
        }
        // the time really read, noted after its sessions are stored; what Android no longer had stays
        // unknown instead of counting as 0 (issue #131)
        double readFrom = ScreenTime.readStart(screenFrom / 1000.0, oldest == Long.MAX_VALUE ? Double.NaN : oldest / 1000.0, now / 1000.0);
        if (sessions.isEmpty()) {
            db.putScreenRead(readFrom, now / 1000.0);
            return 0;
        }
        db.putScreen(sessions);
        db.putScreenRead(readFrom, now / 1000.0);
        // phone use for nights already analysed: the next analysis looks at them again
        if (p.screenAwake()) db.reanalyseFrom(sessions.get(0)[0]);
        return sessions.size();
    }

    /** The name Android shows for an app, or a readable guess from the package name. */
    public static String label(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0 && !l.toString().equals(pkg)) return l.toString();
        } catch (Exception ignored) {
            // not visible to this app, or uninstalled since
        }
        return ScreenTime.shortName(pkg);
    }

}
