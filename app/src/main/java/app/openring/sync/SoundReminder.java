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

import app.openring.R;
import app.openring.core.SleepSounds;
import app.openring.data.Prefs;
import app.openring.data.SoundNight;

/**
 * The bedtime notification for sleep sounds (issue #380, phase 1b): "Ready for the night?" with Start, 30 minutes before the
 * usual bedtime of the last 14 nights (22:30 until there are 7), once a day. One inexact alarm ({@code setAndAllowWhileIdle},
 * no exact alarm permission), as the period reminder. Only after the person agreed to sleep sounds and while "Remind me at
 * bedtime" is on (Settings, Phone). Start opens the app, which starts listening: Android does not let a microphone service
 * start from the background. Planned again at every app start, after a change of the setting, and after a reboot, a change of
 * the time zone or the clock and an update (this receiver).
 */
public final class SoundReminder extends BroadcastReceiver {
    static final String FIRE = "app.openring.SOUNDS_REMINDER";
    static final String CHANNEL = "sounds_remind";
    static final int ALARM = 3803, OPEN = 3804, START = 3805, NOTE = 3806;

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
                    android.util.Log.w("OpenRing", "sleep sounds reminder: " + e.getClass().getSimpleName());
                } finally {
                    r.finish();
                }
            }
        }, "sounds-reminder").start();
    }

    /** Plans the notification again in the background (the screens call this after a change). */
    public static void planLater(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    plan(app);
                } catch (Throwable e) {
                    android.util.Log.w("OpenRing", "sleep sounds reminder: " + e.getClass().getSimpleName());
                }
            }
        }, "sounds-reminder").start();
    }

    static boolean wanted(Context c) {
        Prefs p = Prefs.of(c);
        return p.soundConsent() && p.soundRemind();
    }

    /** Sets or cancels the one alarm. Blocks; not on the main thread. */
    static synchronized void plan(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarm(c);
        if (!wanted(c)) {
            am.cancel(pi);
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        SleepSounds.Usual u = SoundNight.usual(c, LocalDate.now(zone), zone);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, SleepSounds.nextAt(u.remindMinute(), zone, System.currentTimeMillis()), pi);
    }

    static PendingIntent alarm(Context c) {
        Intent i = new Intent(c, SoundReminder.class).setAction(FIRE);
        return PendingIntent.getBroadcast(c, ALARM, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The alarm came: the notification once a day, not while listening, then the next alarm. */
    static synchronized void fire(Context c) {
        String today = LocalDate.now().toString();
        Prefs p = Prefs.of(c);
        if (wanted(c) && !SoundNight.isRunning() && !today.equals(p.soundRemindSent())) {
            // noted first, so a failure to post never sends it again and again
            p.setSoundRemindSent(today);
            post(c);
        }
        plan(c);
    }

    static void post(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sleep sounds at bedtime", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Before your usual bedtime, when you set up sleep sounds");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, app.openring.ui.MainActivity.class).putExtra("tab", 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, OPEN, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent start = new Intent(c, app.openring.ui.MainActivity.class).putExtra("tab", 1).putExtra("sounds_start", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent ps = PendingIntent.getActivity(c, START, start, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(SleepSounds.REMIND_TITLE)
                .setContentText(SleepSounds.REMIND_TEXT)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, SleepSounds.REMIND_START, ps).build())
                .setAutoCancel(true)
                .build();
        nm.notify(NOTE, n);
    }

    /** Removes the bedtime notification (listening started). */
    public static void clear(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTE);
    }
}
