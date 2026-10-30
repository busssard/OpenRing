package app.openring.sync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import app.openring.ble.RingManager;
import app.openring.core.BackgroundJob;
import app.openring.data.Prefs;

/**
 * Periodic background sync (about every 3 hours, only while Bluetooth is usable). Like the
 * official app it is opportunistic: if the ring is out of range the job simply ends.
 */
public final class SyncJob extends JobService {
    static final int JOB_ID = 4711;

    /**
     * Schedules the job, keeps it or cancels it, as the settings say. Called on every resume of the main
     * screen, so an equal pending job is kept: {@code schedule} would replace it, and Android stops a job
     * that runs when it is replaced, which cut off background syncs whenever the app was opened (issue #193).
     */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        ComponentName service = new ComponentName(c, SyncJob.class);
        BackgroundJob.Spec wanted = BackgroundJob.wanted(service.flattenToString());
        boolean known = true;
        BackgroundJob.Spec pending = null;
        try {
            JobInfo p = js.getPendingJob(JOB_ID);
            if (p != null) {
                pending = new BackgroundJob.Spec(p.getService() == null ? null : p.getService().flattenToString(),
                        p.isPeriodic(), p.getIntervalMillis(), p.getFlexMillis(), p.isPersisted(), p.isRequireBatteryNotLow(), p.isRequireCharging());
            }
        } catch (RuntimeException e) {
            known = false;
        }
        Prefs prefs = Prefs.of(c);
        switch (BackgroundJob.decide(prefs.backgroundSync() && prefs.hasKey(), known, pending, wanted)) {
            case CANCEL:
                js.cancel(JOB_ID);
                RingManager.get(c).log("background sync off");
                break;
            case SCHEDULE:
                JobInfo info = new JobInfo.Builder(JOB_ID, service)
                        .setPeriodic(BackgroundJob.INTERVAL_MS, BackgroundJob.FLEX_MS)
                        .setPersisted(true)
                        .setRequiresBatteryNotLow(true)
                        .build();
                js.schedule(info);
                RingManager.get(c).log("background sync scheduled" + (pending != null ? " (replaced " + pending + ")" : ""));
                break;
            default:
                // the same job is pending or running: leave it, so a running sync goes on (issue #193)
                break;
        }
    }

    /** This job's sync, so a stop by Android ends only it (issue #43). */
    private volatile RingManager.SyncTicket ticket;

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (RingManager.get(this).busy()) return false;
        // a sync of the person's own, or an attempt, a few minutes ago: nothing new to fetch yet (issue #194)
        Prefs prefs = Prefs.of(this);
        if (BackgroundJob.recent(System.currentTimeMillis() / 1000, prefs.lastSync(), prefs.lastAttempt())) {
            RingManager.get(this).log("background sync skipped: the last sync was less than 30 minutes ago");
            return false;
        }
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
