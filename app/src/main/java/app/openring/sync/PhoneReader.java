package app.openring.sync;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.openring.core.PhoneSteps;
import app.openring.data.PhoneStore;
import app.openring.data.Prefs;

/**
 * One reading of the phone's step counter (issue #329, phase 1b): register for {@code Sensor.TYPE_STEP_COUNTER}, wait at
 * most {@link PhoneSteps#WAIT_MS} for its first value, unregister, store the reading in the real history
 * ({@code data/PhoneStore.record}). No value in time: nothing is stored, the Data check counts it. No network, no Bluetooth,
 * never the ring's lock. Blocks; call it off the main thread.
 */
public final class PhoneReader {
    private PhoneReader() {}

    /** {@link #read} did not read: no sensor, Physical activity not allowed, or another reading runs right now. */
    public static final int NOT_COUNTING = -1, NO_VALUE = -2, RUNNING = -3;

    /** One reading at a time (the job, the app opening and a restart can meet). */
    private static final AtomicBoolean READING = new AtomicBoolean();

    /** The phone has a step counter. */
    public static boolean hasSensor(Context c) {
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Physical activity is allowed (Android 10 and later ask for it; before, the step counter needs no permission). */
    public static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Steps from this phone are on: a step counter and Physical activity allowed. */
    public static boolean counting(Context c) {
        return hasSensor(c) && allowed(c);
    }

    /**
     * Reads the counter once and stores it (see the class). {@code why} goes to the log ("job", "app opened", "restart");
     * {@code minGapS} &gt; 0 skips the store when the last reading of this boot is younger. Returns a
     * {@code PhoneStore} result or one of {@link #NOT_COUNTING}, {@link #NO_VALUE}, {@link #RUNNING}.
     */
    public static int read(Context c, String why, long minGapS) {
        if (!counting(c)) return NOT_COUNTING;
        if (!READING.compareAndSet(false, true)) return RUNNING;
        try {
            return readLocked(c.getApplicationContext(), why, minGapS);
        } finally {
            READING.set(false);
        }
    }

    private static int readLocked(Context c, String why, long minGapS) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) return NOT_COUNTING;
        final CountDownLatch got = new CountDownLatch(1);
        final Object lock = new Object();
        final double[] value = {Double.NaN, 0, 0};
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                synchronized (lock) {
                    if (!Double.isNaN(value[0]) || e.values == null || e.values.length == 0) return;
                    // the time it arrived: the counter's value then, on the clock that never jumps and on the wall clock
                    value[0] = e.values[0];
                    value[1] = SystemClock.elapsedRealtime();
                    value[2] = System.currentTimeMillis() / 1000.0;
                }
                got.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        HandlerThread ht = new HandlerThread("phone steps");
        ht.start();
        long t0 = SystemClock.elapsedRealtime();
        try {
            if (sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, new Handler(ht.getLooper()))) {
                // values the sensor holds back in its batch come now
                sm.flush(l);
                got.await(PhoneSteps.WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log(c, "phone steps: the sensor could not be read (" + e.getClass().getSimpleName() + ")");
        } finally {
            sm.unregisterListener(l);
            ht.quitSafely();
        }
        long held = SystemClock.elapsedRealtime() - t0;
        double v, elapsed, wall;
        synchronized (lock) {
            v = value[0];
            elapsed = value[1];
            wall = value[2];
        }
        if (Double.isNaN(v) || v < 0) {
            Prefs.of(c).notePhoneNoReading(System.currentTimeMillis() / 1000);
            log(c, "phone steps (" + why + "): no value from the sensor within " + held + " ms, nothing stored");
            return NO_VALUE;
        }
        int bootCount = -1;
        try {
            bootCount = android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            // the stand in from the start time is used (core/PhoneSteps.bootKey)
        }
        PhoneSteps.Reading r = new PhoneSteps.Reading(PhoneSteps.bootKey(bootCount, wall, (long) elapsed), (long) elapsed, (long) v, wall);
        int res = PhoneStore.BUSY;
        // an analysis holds the history's write for its run: a few tries, then the next reading covers this one
        for (int i = 0; i < 6; i++) {
            try {
                res = PhoneStore.record(c, r, minGapS);
            } catch (RuntimeException e) {
                log(c, "phone steps (" + why + "): not stored (" + e.getClass().getSimpleName() + ")");
                return PhoneStore.BUSY;
            }
            if (res != PhoneStore.BUSY) break;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // the log names what happened and how long the sensor was on, never the count
        log(c, "phone steps (" + why + "): " + (res == PhoneStore.STORED ? "reading stored" : res == PhoneStore.KNOWN ? "reading known"
                : res == PhoneStore.TOO_SOON ? "skipped, the last reading is a minute old" : "skipped, an analysis runs") + ", sensor on " + held + " ms");
        return res;
    }

    /** {@link #read} on its own thread; {@code done} (may be null) runs on that thread with the result. */
    public static void readLater(Context c, final String why, final long minGapS, final Done done) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int r = NOT_COUNTING;
                try {
                    r = read(app, why, minGapS);
                } catch (Throwable e) {
                    log(app, "phone steps (" + why + ") failed: " + e.getClass().getSimpleName());
                }
                if (done != null) done.done(r);
            }
        }, "phone steps").start();
    }

    /** The result of {@link #readLater}. */
    public interface Done {
        void done(int result);
    }

    static void log(Context c, String line) {
        try {
            app.openring.ble.RingManager.get(c).log(line);
        } catch (Throwable e) {
            android.util.Log.i("OpenRing", line);
        }
    }
}
