package app.openring.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import app.openring.R;
import app.openring.ble.RingManager;
import app.openring.ui.MainActivity;

/** Foreground service that keeps a user started sync alive while the app is in the background. */
public final class SyncService extends Service implements RingManager.Listener {
    static final String CHANNEL = "sync";
    static final int ID = 7;
    private volatile boolean running;

    public static void start(Context c) {
        Intent i = new Intent(c, SyncService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ring sync", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while data is copied from your ring");
            nm.createNotificationChannel(ch);
        }
    }

    Notification build(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle("Syncing your ring")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel(this);
        Notification n = build("Starting");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(ID, n);
            }
        } catch (RuntimeException e) {
            // e.g. Bluetooth permission revoked: a connected device service may not start
            RingManager.get(this).log("could not start the sync service: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            RingManager.get(this).addListener(this);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "openring:sync");
                    wl.acquire(3 * 60 * 60 * 1000L);
                    try {
                        RingManager.get(SyncService.this).syncBlocking();
                    } finally {
                        if (wl.isHeld()) wl.release();
                        running = false;
                        RingManager.get(SyncService.this).removeListener(SyncService.this);
                        stopForeground(true);
                        stopSelf();
                    }
                }
            }, "ring-sync").start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRingStatus() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(ID, build(RingManager.get(this).status()));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
