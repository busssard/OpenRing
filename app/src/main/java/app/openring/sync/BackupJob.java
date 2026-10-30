package app.openring.sync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import app.openring.ble.RingManager;
import app.openring.core.BackgroundJob;
import app.openring.core.Backups;
import app.openring.data.Analyzer;
import app.openring.data.AutoBackup;
import app.openring.data.BeforeUpdate;
import app.openring.data.Prefs;

/**
 * The automatic backups (issue #242). One periodic job (about daily, while the phone charges and the battery is not
 * low) whenever a folder is chosen: each run decides whether a copy is due ({@code core/Backups.due}), so changing how
 * often never replaces a job (issue #193), a failed monthly copy is tried again the next day and missed copies become
 * one. A one off job checks a copy made before an update (and sends it to the folder) soon after the update.
 */
public final class BackupJob extends JobService {
    static final int JOB_ID = 4712, CHECK_ID = 4713;

    /** Called on every resume of the main screen and after the folder or its settings changed. */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        ComponentName service = new ComponentName(c, BackupJob.class);
        BackgroundJob.Spec wanted = Backups.jobSpec(service.flattenToString());
        boolean known = true;
        BackgroundJob.Spec pending = null;
        JobInfo check = null;
        try {
            JobInfo p = js.getPendingJob(JOB_ID);
            if (p != null) {
                pending = new BackgroundJob.Spec(p.getService() == null ? null : p.getService().flattenToString(), p.isPeriodic(), p.getIntervalMillis(),
                        p.getFlexMillis(), p.isPersisted(), p.isRequireBatteryNotLow(), p.isRequireCharging());
            }
            check = js.getPendingJob(CHECK_ID);
        } catch (RuntimeException e) {
            known = false;
        }
        Prefs prefs = Prefs.of(c);
        boolean on = prefs.backupFolder() != null && prefs.hasKey() && !prefs.demo();
        switch (BackgroundJob.decide(on, known, pending, wanted)) {
            case CANCEL:
                js.cancel(JOB_ID);
                RingManager.get(c).log("automatic backups off");
                break;
            case SCHEDULE:
                js.schedule(new JobInfo.Builder(JOB_ID, service)
                        .setPeriodic(Backups.JOB_INTERVAL_MS, Backups.JOB_FLEX_MS)
                        .setPersisted(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresCharging(true)
                        .build());
                RingManager.get(c).log("automatic backups scheduled" + (pending != null ? " (replaced " + pending + ")" : ""));
                break;
            default:
                break;
        }
        // a copy made before an update waits for its check: once, soon, never replacing one that is pending
        if (check == null && known && !prefs.demo() && BeforeUpdate.pending(c)) {
            js.schedule(new JobInfo.Builder(CHECK_ID, service)
                    .setRequiresBatteryNotLow(true)
                    .setMinimumLatency(60 * 1000L)
                    .setOverrideDeadline(6 * 60 * 60 * 1000L)
                    .build());
            RingManager.get(c).log("check of the copy before the update scheduled");
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final boolean onlyCheck = params.getJobId() == CHECK_ID;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AutoBackup.Outcome o = AutoBackup.run(app, SafFolder.saved(app), System.currentTimeMillis() / 1000, onlyCheck, new Analyzer.Log() {
                        @Override
                        public void log(String m) {
                            RingManager.get(app).log(m);
                        }
                    });
                    if (o.skipped != null) RingManager.get(app).log("automatic backup: " + o.skipped);
                } catch (Throwable e) {
                    RingManager.get(app).log("automatic backup failed: " + android.util.Log.getStackTraceString(e));
                }
                jobFinished(params, false);
            }
        }, "backup-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // the run goes on in its thread (a copy is never cut off half way: a .partial is never counted anyway);
        // the one off check is tried again if it was stopped before it ran
        return params.getJobId() == CHECK_ID;
    }
}
