package app.openring.sync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import app.openring.core.BackgroundJob;

/**
 * The phone's step reading about every hour (issue #329, phase 1b): its own job id, separate from the ring's sync job, no
 * network, no Bluetooth, never the ring's lock (a busy ring never skips it, {@code core/BackgroundJob.due}). Kept after a
 * restart. Scheduled only while the phone counts steps (a step counter and Physical activity allowed), cancelled otherwise.
 */
public final class PhoneJob extends JobService {
    static final int JOB_ID = 4714;

    /** Schedules, keeps or cancels the job as the phone counts steps or not; an equal pending job is kept (issue #193). */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        ComponentName service = new ComponentName(c, PhoneJob.class);
        BackgroundJob.Spec wanted = BackgroundJob.phoneWanted(service.flattenToString());
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
        boolean on = PhoneReader.counting(c);
        switch (BackgroundJob.decide(on, known, pending, wanted)) {
            case CANCEL:
                js.cancel(JOB_ID);
                if (pending != null) PhoneReader.log(c, "phone steps job off");
                break;
            case SCHEDULE:
                try {
                    js.schedule(new JobInfo.Builder(JOB_ID, service)
                            .setPeriodic(app.openring.core.PhoneSteps.INTERVAL_MS, app.openring.core.PhoneSteps.FLEX_MS)
                            .setPersisted(true)
                            .build());
                    PhoneReader.log(c, "phone steps job scheduled" + (pending != null ? " (replaced " + pending + ")" : ""));
                } catch (RuntimeException e) {
                    PhoneReader.log(c, "phone steps job not scheduled (" + e.getClass().getSimpleName() + ")");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        BackgroundJob.Inputs in = new BackgroundJob.Inputs();
        in.phoneSteps = PhoneReader.counting(this);
        if (!BackgroundJob.due(in).phone) return false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PhoneReader.read(PhoneJob.this, "job", 0);
                } catch (Throwable e) {
                    PhoneReader.log(PhoneJob.this, "phone steps job failed: " + e.getClass().getSimpleName());
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "phone steps job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // a reading ends by itself within a few seconds; nothing to stop, nothing to try again
        return false;
    }
}
