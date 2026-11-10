package app.openring.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Readings of the phone's step counter at a restart (issue #329, phase 1b): the first one after the phone started
 * ({@code BOOT_COMPLETED}), and a last one when it shuts down ({@code ACTION_SHUTDOWN}, best effort: since Android 9 the
 * system sends it only to receivers an app registered while it runs, so on most phones the reading after the start is the
 * one that counts). The job is planned again after the start. Not exported: only the system reaches it.
 */
public final class PhoneStepsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final String a = i == null ? null : i.getAction();
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a);
        if (!boot && !Intent.ACTION_SHUTDOWN.equals(a)) return;
        final Context app = c.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (boot) PhoneJob.schedule(app);
                    PhoneReader.read(app, boot ? "phone started" : "phone shuts down", 0);
                } catch (Throwable e) {
                    PhoneReader.log(app, "phone steps at a restart failed: " + e.getClass().getSimpleName());
                } finally {
                    pending.finish();
                }
            }
        }, "phone steps restart").start();
    }
}
