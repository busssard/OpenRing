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
        schedule(c, Prefs.of(c).keyState());
    }

    /** As {@link #schedule(Context)}, with the {@code core/KeyState} the caller read already (one decrypt per resume, review of #281). */
    public static void schedule(Context c, int keyState) {
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
        // a key this phone cannot read could only fail every few hours; the job comes back once it is entered again (issue #199)
        boolean paused = prefs.backgroundSync() && keyState == app.openring.core.KeyState.UNREADABLE;
        // the watch syncs in the same job (issue #251): it needs the job too when it syncs on its own
        boolean watch = !prefs.demo() && prefs.watchAddress() != null && prefs.watchPerDay() > 0;
        switch (BackgroundJob.decide(prefs.backgroundSync() && app.openring.core.KeyState.syncs(keyState) || watch, known, pending, wanted)) {
            case CANCEL:
                js.cancel(JOB_ID);
                RingManager.get(c).log(paused ? "background sync paused: the saved key cannot be read" : "background sync off");
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
        // what is due is decided in core/BackgroundJob (issue #329): the ring first, as before; the watch after it when one
        // of its clock times passed (issue #251); a sync of the person's own a few minutes ago skips the ring (issue #194)
        BackgroundJob.Inputs in = new BackgroundJob.Inputs();
        in.ringBusy = RingManager.get(this).busy();
        if (!in.ringBusy) {
            Prefs prefs = Prefs.of(this);
            in.now = System.currentTimeMillis() / 1000;
            in.backgroundSync = prefs.backgroundSync();
            // the key is read (decrypted) only when background sync is on, as before
            if (in.backgroundSync) in.keyState = prefs.keyState();
            in.demo = prefs.demo();
            in.watchAdded = prefs.watchAddress() != null;
            in.watchPerDay = prefs.watchPerDay();
            in.watchLastOk = prefs.watchLastOk();
            in.watchLastAttempt = prefs.watchLastAttempt();
            in.lastSync = prefs.lastSync();
            in.lastAttempt = prefs.lastAttempt();
            in.zone = java.time.ZoneId.systemDefault();
        }
        BackgroundJob.Due due = BackgroundJob.due(in);
        if (due.log != null) RingManager.get(this).log(due.log);
        if (!due.run()) return false;
        final boolean ringPart = due.ring, watchPart = due.watch;
        // nobody watches this sync: it scans briefly and filtered only when the ring is away (issue #195)
        final RingManager.SyncTicket t = RingManager.SyncTicket.background();
        ticket = t;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // any error ends this run with a line in the log, never the app, and the job is always finished (issue #334)
                try {
                    RingManager.SyncResult res = new RingManager.SyncResult();
                    try {
                        if (ringPart) res = RingManager.get(SyncJob.this).syncBlocking(t);
                    } catch (Throwable e) {
                        // the watch part still runs
                        log("background sync failed: ", e);
                    }
                    if (watchPart && !t.stopped()) {
                        try {
                            // this job's ticket: a stop by Android ends this session only (review of dadbb71)
                            String w = app.openring.ble.WatchSync.run(SyncJob.this, t);
                            RingManager.get(SyncJob.this).log("background watch sync: " + (w == null ? "done" : w));
                        } catch (Throwable e) {
                            log("background watch sync failed: ", e);
                        }
                    }
                    // a background sync that worked removes the notice of an earlier failed one (issue #91)
                    if (res != null && res.ok) {
                        try {
                            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(SyncService.FAILED_ID);
                        } catch (RuntimeException e) {
                            RingManager.get(SyncJob.this).log("old sync notice not removed: " + e);
                        }
                    }
                } catch (Throwable e) {
                    log("background job failed: ", e);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "ring-job").start();
        return true;
    }

    /** Logs {@code e} with its stack trace; when even that fails, Android's own log keeps it (issue #334). */
    private void log(String what, Throwable e) {
        try {
            RingManager.get(this).log(what + android.util.Log.getStackTraceString(e));
        } catch (Throwable ignored) {
            android.util.Log.e("OpenRing", what, e);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // stops this job's download only; once it has given the ring back (it then analyses), Live, a
        // game or a sync the person started keep running (issue #43)
        // the same ticket ends this job's watch sync between two messages (issue #251), never a setup the person started
        RingManager.get(this).cancelSync(ticket);
        return false;
    }
}
