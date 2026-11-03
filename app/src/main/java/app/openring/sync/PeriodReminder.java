package app.openring.sync;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import app.openring.R;
import app.openring.core.PeriodRemind;
import app.openring.core.analysis.Cycles;
import app.openring.data.Periods;
import app.openring.data.Prefs;

/**
 * The reminder before the expected period (issue #252, phase 1a2): one inexact alarm ({@code setAndAllowWhileIdle}, no
 * exact alarm permission; minutes late is fine) and a local notification. The rules are in {@code core/PeriodRemind}.
 *
 * Planned again at every app start, every change of the log or of the reminder's settings, and after a reboot, a change
 * of the time zone or the clock, and an update of the app (this receiver). The log is always the person's own, read
 * through {@link Periods#readReal} (read only, without opening or swapping the app's database, also while demo mode is
 * on), so the demo's made up log never schedules one. When the log cannot be read, the alarm already set stays.
 * Nothing here writes a date or an amount to the log.
 */
public final class PeriodReminder extends BroadcastReceiver {
    static final String FIRE = "app.openring.PERIOD_REMINDER";
    static final String CHANNEL = "period";
    static final int ALARM = 4714, OPEN = 4715, NOTE = 4716;

    @Override
    public void onReceive(Context c, Intent i) {
        final String action = i == null ? null : i.getAction();
        final Context app = c.getApplicationContext();
        final PendingResult r = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (FIRE.equals(action)) fire(app);
                    else plan(app);
                } catch (Throwable e) {
                    // the alarm set before stays; the error's kind only, never a date
                    android.util.Log.w("OpenRing", "period reminder: " + e.getClass().getSimpleName());
                } finally {
                    r.finish();
                }
            }
        }, "period-reminder").start();
    }

    /** Plans the reminder again in the background (the screens call this after a change). */
    public static void planLater(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    plan(app);
                } catch (Throwable e) {
                    android.util.Log.w("OpenRing", "period reminder: " + e.getClass().getSimpleName());
                }
            }
        }, "period-reminder").start();
    }

    /** The next reminder as of now, from the person's own log. Throws when the log cannot be read. */
    public static PeriodRemind.Plan next(Context c, long now) {
        Prefs p = Prefs.of(c);
        int days = p.periodRemindDays();
        if (days <= 0) return PeriodRemind.plan(null, 0, 0, ZoneId.systemDefault(), now, null);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return PeriodRemind.plan(own(c, today), days, p.periodRemindMinute(), zone, now, p.periodRemindSent());
    }

    /**
     * The person's own log: the read only helper; outside demo mode, should that fail (an older Android may not open a
     * write ahead log read only), the app's database, which then holds the real file. In demo mode only the helper.
     */
    static Cycles own(Context c, LocalDate today) {
        Prefs p = Prefs.of(c);
        LocalDate stopped = Cycles.day(p.periodStoppedReal());
        try {
            return Periods.readReal(c, today, stopped);
        } catch (RuntimeException e) {
            if (p.demo()) throw e;
            return Periods.cycles(app.openring.data.Db.get(c), today, stopped);
        }
    }

    /** Sets or cancels the one alarm. Blocks; not on the main thread. */
    static synchronized void plan(Context c) {
        PeriodRemind.Plan p = next(c, System.currentTimeMillis());
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarm(c);
        if (p.planned()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi);
        else am.cancel(pi);
    }

    static PendingIntent alarm(Context c) {
        Intent i = new Intent(c, PeriodReminder.class).setAction(FIRE);
        return PendingIntent.getBroadcast(c, ALARM, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The alarm came: sends when it is still due (the log may have changed since), then plans the next one. */
    static synchronized void fire(Context c) {
        long now = System.currentTimeMillis();
        PeriodRemind.Plan p = next(c, now);
        if (PeriodRemind.due(p, now)) {
            // noted first, so a failure to post can never make the next plan send the same reminder again and again
            Prefs.of(c).setPeriodRemindSent(p.key);
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            Cycles cy = own(c, today);
            if (cy != null && cy.estimates()) post(c, PeriodRemind.text(cy, today, Locale.getDefault()));
        }
        plan(c);
    }

    static void post(Context c, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Period reminders", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Before the expected start of your period, when you switched it on in Settings");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, app.openring.ui.MainActivity.class).putExtra("tab", 4)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, OPEN, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // the lock screen shows only this
        Notification shown = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(PeriodRemind.PUBLIC_TITLE)
                .build();
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(PeriodRemind.TITLE)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(shown)
                .build();
        nm.notify(NOTE, n);
    }
}
