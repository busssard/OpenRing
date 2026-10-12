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

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (RingManager.get(this).busy()) return false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                RingManager.SyncResult r = RingManager.get(SyncJob.this).syncBlocking();
                jobFinished(params, false);
            }
        }, "ring-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        RingManager.get(this).cancel();
        return false;
    }
}
