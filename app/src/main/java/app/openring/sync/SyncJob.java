package app.openring.sync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import app.openring.ble.RingManager;
import app.openring.data.Prefs;

/**
 * Periodic background sync (about every 3 hours, only while Bluetooth is usable). Like the
 * official app it is opportunistic: if the ring is out of range the job simply ends.
 */
public final class SyncJob extends JobService {
    static final int JOB_ID = 4711;

    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!Prefs.of(c).backgroundSync() || !Prefs.of(c).hasKey()) {
            js.cancel(JOB_ID);
            return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJob.class))
                .setPeriodic(3 * 60 * 60 * 1000L, 60 * 60 * 1000L)
                .setPersisted(true)
                .setRequiresBatteryNotLow(true)
                .build();
        js.schedule(info);
    }

    /** This job's sync, so a stop by Android ends only it (issue #43). */
    private volatile RingManager.SyncTicket ticket;

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (RingManager.get(this).busy()) return false;
        final RingManager.SyncTicket t = new RingManager.SyncTicket();
        ticket = t;
        new Thread(new Runnable() {
            @Override
            public void run() {
                RingManager.SyncResult res = RingManager.get(SyncJob.this).syncBlocking(t);
                // a background sync that worked removes the notice of an earlier failed one (issue #91)
                if (res.ok) {
                    try {
                        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(SyncService.FAILED_ID);
                    } catch (RuntimeException e) {
                        RingManager.get(SyncJob.this).log("old sync notice not removed: " + e);
                    }
                }
                jobFinished(params, false);
            }
        }, "ring-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // stops this job's download only; once it has given the ring back (it then analyses), Live, a
        // game or a sync the person started keep running (issue #43)
        RingManager.get(this).cancelSync(ticket);
        return false;
    }
}
