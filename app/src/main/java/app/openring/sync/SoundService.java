package app.openring.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.time.LocalDate;
import java.time.ZoneId;

import app.openring.R;
import app.openring.core.SleepSounds;
import app.openring.core.analysis.SoundDetector;
import app.openring.data.Prefs;
import app.openring.data.SoundNight;
import app.openring.data.Sounds;
import app.openring.ui.MainActivity;

/**
 * Listening for sleep sounds through the night (issue #380, phase 1b): a foreground service of type {@code microphone} with its
 * notification ("Listening for sleep sounds", Stop). It only reads the microphone and hands each piece of samples to
 * {@link SoundNight}, which finds the sounds, saves the clips and writes the rows (JVM tested).
 *
 * <ul>
 * <li>Started only from a screen in front ({@link #start}): Android does not let a microphone service start from the background,
 * so the bedtime notification's Start opens the app first.</li>
 * <li>{@code AudioRecord} at 16 kHz mono, the source {@code UNPROCESSED} where the phone offers it (no automatic level
 * adjustment on most phones), else {@code VOICE_RECOGNITION}.</li>
 * <li>A partial wake lock while the loop runs, released in its {@code finally}, which every stop passes through: Stop, the stop
 * time (the usual wake up plus an hour), 12 hours, a microphone error, Android ending the service, and Delete all (which
 * closes the night, so its next piece is refused).</li>
 * <li>After the night the retention and the sweep run on the real clip folder.</li>
 * </ul>
 * Demo mode never records: the screens do not start it then, and the service refuses to.
 */
public final class SoundService extends Service {
    static final String CHANNEL = "sounds";
    static final int ID = 380, STOP_CODE = 3801, OPEN_CODE = 3802;
    static final String ACTION_STOP = "app.openring.SOUNDS_STOP";
    /** Samples read at once: 100 ms. */
    static final int PIECE = SoundDetector.RATE / 10;

    private volatile boolean looping, stopAsked, destroyed;

    /** Whether the microphone is allowed. */
    public static boolean allowed(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts listening (from a screen in front only). False when it cannot: demo mode, no consent, the microphone not allowed,
     * or Android refused the service.
     */
    public static boolean start(Context c) {
        Prefs p = Prefs.of(c);
        if (p.demo() || !p.soundConsent() || !allowed(c)) return false;
        try {
            c.startForegroundService(new Intent(c, SoundService.class));
            return true;
        } catch (RuntimeException e) {
            log(c, "sleep sounds: not started (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Stops listening by hand (the card's Stop listening, the notification's Stop). */
    public static void stop(Context c) {
        if (!SoundNight.isRunning()) return;
        try {
            c.startService(new Intent(c, SoundService.class).setAction(ACTION_STOP));
        } catch (RuntimeException e) {
            log(c, "sleep sounds: stop not delivered (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Whether the phone offers the unprocessed source (no automatic level adjustment), which the night and the test use. */
    public static boolean unprocessed(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return am != null && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
    }

    /** The microphone at 16 kHz mono 16 bit, as the night and the microphone test read it; null when Android gives none. */
    public static AudioRecord open(Context c) {
        if (!allowed(c)) return null;
        int source = unprocessed(c) ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int min = AudioRecord.getMinBufferSize(SoundDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // 10 s of buffer (review of #450): the reading thread never waits for an encode or a write, and this covers any hiccup
        int size = Math.max(min, SoundDetector.RATE * 2 * 10);
        try {
            AudioRecord r = new AudioRecord(source, SoundDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (RuntimeException e) {
            // no microphone for this app now (refused, or held by another app on some phones)
        }
        return null;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sleep sounds", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the phone listens for sleep sounds");
            nm.createNotificationChannel(ch);
        }
    }

    Notification build() {
        Intent open = new Intent(this, MainActivity.class).putExtra("tab", 1)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, OPEN_CODE, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, STOP_CODE, new Intent(this, SoundService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(SleepSounds.NOTE_TITLE)
                .setContentText(SleepSounds.NOTE_TEXT)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, SleepSounds.NOTE_STOP, stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAsked = true;
            if (!looping) stopSelf();
            return START_NOT_STICKY;
        }
        ensureChannel(this);
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, build());
        } catch (RuntimeException e) {
            // the microphone was taken back, or Android does not allow it from here
            log(this, "sleep sounds: could not start the service (" + e.getClass().getSimpleName() + ")");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!looping && !Prefs.of(this).demo() && !SoundNight.isRunning()) {
            looping = true;
            stopAsked = false;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    listen();
                }
            }, "sleep-sounds").start();
        } else if (!looping) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** A piece of samples and the clock when its last sample came; {@link #END} ends the worker. */
    private static final Object[] END = new Object[0];
    /** Pieces waiting for the worker: 60 s; when full (the worker held up), the piece is dropped and becomes a gap. */
    static final int QUEUE = 600;

    /**
     * The night's loop. This thread only reads the microphone and hands each piece, with the clock of its last sample, to a
     * worker thread that feeds {@link SoundNight} (detector, clip encoding, rows): a slow encode or write never holds up the
     * reading, so the microphone's buffer (10 s) does not overrun (review of #450). A piece the worker cannot take within 60 s
     * is dropped and the night stores the missing time as a gap. Every way out passes the finally, which ends the worker,
     * finishes the night and releases the microphone and the wake lock.
     */
    void listen() {
        final Context c = getApplicationContext();
        PowerManager.WakeLock wl = null;
        AudioRecord rec = null;
        SoundNight night = null;
        String why = Sounds.ENDED_SYSTEM;
        final boolean[] closed = {false};
        final java.util.concurrent.ArrayBlockingQueue<Object[]> queue = new java.util.concurrent.ArrayBlockingQueue<Object[]>(QUEUE);
        Thread worker = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openring:sounds");
            wl.acquire((long) (SleepSounds.LIMIT_S + 3600) * 1000L);
            rec = open(c);
            if (rec == null) {
                log(c, "sleep sounds: the microphone did not open");
                return;
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                log(c, "sleep sounds: the microphone did not start");
                return;
            }
            double start = System.currentTimeMillis() / 1000.0;
            ZoneId zone = ZoneId.systemDefault();
            SleepSounds.Usual usual = SoundNight.usual(c, LocalDate.now(zone), zone);
            SleepSounds.Stop stop = SleepSounds.stopAt(start, usual.stopMinute(), zone);
            night = SoundNight.start(c, start, Prefs.of(c).soundThreshold(), new ClipEncoder());
            log(c, "sleep sounds: listening (" + (unprocessed(c) ? "unprocessed" : "voice recognition") + " source)");
            final SoundNight fed = night;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Object[] piece = queue.take();
                            if (piece == END) return;
                            short[] s = (short[]) piece[0];
                            if (!fed.feed(s, 0, s.length, (Double) piece[1])) {
                                // Delete all closed the night: nothing of it is written any more
                                closed[0] = true;
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        log(c, "sleep sounds: the night stopped by an error (" + e.getClass().getSimpleName() + ")");
                        closed[0] = true;
                    }
                }
            }, "sleep-sounds-night");
            worker.start();
            short[] buf = new short[PIECE];
            while (true) {
                if (stopAsked) {
                    why = Sounds.ENDED_HAND;
                    break;
                }
                if (destroyed || closed[0]) break;
                double now = System.currentTimeMillis() / 1000.0;
                if (now >= stop.at) {
                    why = stop.limit ? Sounds.ENDED_LIMIT : Sounds.ENDED_TIME;
                    break;
                }
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) {
                    log(c, "sleep sounds: the microphone stopped (" + n + ")");
                    break;
                }
                if (n == 0) continue;
                // a full queue drops the piece: the night sees the clock jump and stores the time as a gap
                queue.offer(new Object[] {java.util.Arrays.copyOf(buf, n), System.currentTimeMillis() / 1000.0});
            }
        } catch (Throwable e) {
            log(c, "sleep sounds: stopped by an error (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                    // never started
                }
                rec.release();
            }
            if (worker != null) {
                // the worker feeds what is waiting, then ends
                try {
                    queue.put(END);
                    worker.join(120000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (night != null && !closed[0]) {
                try {
                    night.finish(why);
                    log(c, "sleep sounds: stopped (" + why + ")");
                } catch (Throwable e) {
                    log(c, "sleep sounds: the end of the night was not written (" + e.getClass().getSimpleName() + ")");
                }
            }
            try {
                SoundNight.tidyReal(c, Prefs.of(c).soundKeepDays());
            } catch (Throwable e) {
                log(c, "sleep sounds: tidy failed (" + e.getClass().getSimpleName() + ")");
            }
            if (wl != null && wl.isHeld()) wl.release();
            looping = false;
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        // Android ends the service: the loop stops and the night is closed as "system"
        destroyed = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void log(Context c, String line) {
        try {
            app.openring.ble.RingManager.get(c).log(line);
        } catch (Throwable ignored) {
            android.util.Log.w("OpenRing", line);
        }
    }
}
