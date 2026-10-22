package app.openring.ui;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import app.openring.ble.RingManager;
import app.openring.ble.RingScanner;
import app.openring.core.Texts;
import app.openring.core.analysis.DemoWalk;
import app.openring.core.analysis.LastWorn;
import app.openring.core.analysis.Proximity;
import app.openring.core.analysis.ScanRetry;
import app.openring.core.analysis.SearchWalk;
import app.openring.core.analysis.StepDetector;
import app.openring.core.analysis.TS;
import app.openring.data.Db;
import app.openring.data.Prefs;
import app.openring.data.RightNow;

/**
 * Find my ring (issue #74, phases 1 and 2): where it was last (from the stored history), hot and
 * cold from the ring's Bluetooth signal with clicks and vibration, and the search walk: turning on
 * the spot gives the strongest direction, walking through the room draws the path with the signal
 * on a mini map and the circle where the ring probably is. Everything that listens (scan, sensors, sound) runs only while the screen
 * is in front and stops in {@link #onPause}. The ring itself is never connected here, so a sync
 * can run meanwhile.
 */
public final class FinderActivity extends Activity implements SensorEventListener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Proximity prox = new Proximity();
    /** {unix seconds, smoothed dBm} of the last 2 minutes, for the chart. */
    private final ArrayDeque<double[]> trail = new ArrayDeque<double[]>();
    private SearchWalk walk;
    private boolean recording, resumed, demo;
    private String address;
    private RingScanner.Watch watch;
    /** When the scan starts again after Android stopped it (issue #96). */
    private final ScanRetry retry = new ScanRetry();
    private long otherAt = Long.MIN_VALUE, listenSince;
    /** When the paired ring was last received with no usable signal strength (127 and the like, issue #95). */
    private long unusableAt = Long.MIN_VALUE;
    private boolean systemLinked;
    private long linkCheckedAt;
    private SensorManager sm;
    private Sensor rotation;
    private final float[] rot = new float[9];
    private double facing = Double.NaN;
    private boolean upright = true;
    private ToneGenerator tone;
    private Vibrator vib;
    private long vibAt;
    private double demoHeading;
    private final Random demoNoise = new Random(74);
    private boolean askedPermission, permissionBlocked;
    private Charts.LineChart trailChart;
    // the search walk (phase 2): steps from the accelerometer, only while recording
    private Sensor accel;
    private StepDetector stepper;
    private SearchWalk.Spot spot;
    private boolean spotDirty, demoTurnOnly;
    private long spotAt;
    /** Turns the walk's times (elapsed realtime, or the demo's own clock) into wall clock times. */
    private long wallOffset;
    /** Walk milliseconds per real millisecond for the readout (0.5 while the demo replays at twice its speed). */
    private double timeScale = 1;
    /** The circle is being worked out on another thread. */
    private boolean locating;
    private List<double[]> demoEvents;
    private int demoIndex;
    private long demoStart;
    private MiniMap map;
    private TextView stepText, walkNote;

    static final String UNUSABLE = "This phone receives the ring, so it is within Bluetooth range (about 10 m), "
            + "but the phone gives no usable signal strength for it right now.";

    static final String HOLD = "Hold the phone upright, flat against your chest, screen towards you.";

    static final String WALK = "Tap Start. Walk slowly, cover the room, take your time. To get only a direction, turn once around on the spot instead.";
    static final String TURN_NOTE = "Your body blocks the signal from behind, so it is strongest while you face the ring. "
            + "The arrow turns with you and keeps pointing the same way. The petals show the signal you measured in each direction; touch one to read it.";
    static final String WALK_NOTE = "Steps and direction come from the phone's own sensors and drift by about 5 to 10 % of the distance walked: "
            + "fine in a flat, not across a house. The circle is where to start looking (a couple of metres), not a spot. Floors are not known: "
            + "a ring upstairs or downstairs shows as a weak trail everywhere. Dots: blue is weak, warm is strong; touch one to read it.";

    private TextView lastText, heardText, batteryText, bandText, signalText, issueText, turnText, turnButton, soundHint;
    private LinearLayout issueBox, trailBox;
    private TextView issueButton;
    private Meter meter;
    private Dial dial;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Prefs p = Prefs.of(this);
        demo = p.demo();
        address = p.address();
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotation = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        demoTurnOnly = demo && getIntent().getBooleanExtra("demo_turn", false);
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 8), pad, pad);
        sv.addView(root);

        LinearLayout top = Ui.hbox(this);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Find my ring"));
        root.addView(top);
        root.addView(Ui.gap(this, 10));

        // ---- where was it last ----
        LinearLayout last = Ui.card(this);
        last.addView(Ui.caption(this, "Where was it last"));
        last.addView(Ui.gap(this, 6));
        lastText = Ui.text(this, "Reading the ring's history", 16, Ui.TEXT, Ui.REGULAR);
        lastText.setLineSpacing(0, 1.15f);
        last.addView(lastText);
        last.addView(Ui.gap(this, 6));
        heardText = Ui.body(this, "");
        last.addView(heardText);
        batteryText = Ui.body(this, "");
        last.addView(batteryText);
        last.addView(Ui.gap(this, 6));
        last.addView(Ui.text(this, "From the ring's stored skin temperature and heart rate, as far as the last sync reaches.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(last);

        // ---- hot and cold ----
        LinearLayout hot = Ui.card(this);
        hot.addView(Ui.caption(this, "Hot and cold"));
        hot.addView(Ui.gap(this, 4));
        hot.addView(Ui.body(this, "Walk slowly around. The ring fills and the clicks get faster as you get closer."));
        meter = new Meter(this);
        LinearLayout.LayoutParams ml = Ui.sized(this, -1, 210);
        ml.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        hot.addView(meter, ml);
        bandText = Ui.text(this, "", 20, Ui.TEXT, Ui.LIGHT);
        bandText.setGravity(Gravity.CENTER);
        hot.addView(bandText, Ui.matchWrap());
        signalText = Ui.text(this, "", 12, Ui.TEXT_3, Ui.REGULAR);
        signalText.setGravity(Gravity.CENTER);
        hot.addView(signalText, Ui.matchWrap());
        issueBox = Ui.vbox(this);
        issueText = Ui.text(this, "", 14, Ui.WARN, Ui.REGULAR);
        issueText.setLineSpacing(0, 1.15f);
        issueBox.addView(issueText);
        issueButton = Ui.button(this, "Allow", Ui.OXY, true);
        LinearLayout.LayoutParams il = Ui.matchWrap();
        il.setMargins(0, Ui.dp(this, 8), 0, 0);
        issueBox.addView(issueButton, il);
        LinearLayout.LayoutParams ibl = Ui.matchWrap();
        ibl.setMargins(0, Ui.dp(this, 10), 0, 0);
        hot.addView(issueBox, ibl);
        Switch sound = new Switch(this);
        sound.setText("Clicks and vibration (faster when closer)");
        sound.setTextColor(Ui.TEXT);
        sound.setChecked(p.finderSound());
        sound.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                Prefs.of(FinderActivity.this).setFinderSound(on);
            }
        });
        LinearLayout.LayoutParams sl = Ui.matchWrap();
        sl.setMargins(0, Ui.dp(this, 12), 0, 0);
        hot.addView(sound, sl);
        soundHint = Ui.text(this, "", 12, Ui.WARN, Ui.REGULAR);
        hot.addView(soundHint);
        hot.addView(Ui.gap(this, 8));
        hot.addView(Ui.caption(this, "Signal, last 2 minutes"));
        trailBox = Ui.vbox(this);
        double now0 = System.currentTimeMillis() / 1000.0;
        trailChart = new Charts.LineChart(this, new TS(), now0 - 120, now0, Ui.OXY).noFill().unit(" dBm").empty("No signal yet").ago(Proximity.CHART_MIN_SPAN);
        trailBox.addView(trailChart, Ui.sized(this, -1, 130));
        hot.addView(trailBox, Ui.sized(this, -1, 130));
        hot.addView(Ui.gap(this, 6));
        hot.addView(Ui.text(this, "The signal jumps with walls, blankets, your body and the ring's angle. A ring under a duvet sounds "
                + "farther away than it is. Walk slowly and follow the trend.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(hot);

        // ---- search walk: turn on the spot or walk through the room ----
        LinearLayout turn = Ui.card(this);
        turn.addView(Ui.caption(this, "Search walk"));
        turn.addView(Ui.gap(this, 4));
        turn.addView(Ui.body(this, "1. " + HOLD + "\n"
                + "2. " + WALK + "\n"
                + "3. Tap Stop when you are done."));
        dial = new Dial(this);
        LinearLayout.LayoutParams dl = Ui.sized(this, -1, 250);
        dl.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        turn.addView(dial, dl);
        map = new MiniMap(this);
        LinearLayout.LayoutParams mpl = Ui.sized(this, -1, 320);
        mpl.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        turn.addView(map, mpl);
        map.setVisibility(View.GONE);
        turnText = Ui.text(this, "", 14, Ui.TEXT_2, Ui.REGULAR);
        turnText.setLineSpacing(0, 1.15f);
        turn.addView(turnText);
        stepText = Ui.text(this, "", 12, Ui.TEXT_3, Ui.REGULAR);
        LinearLayout.LayoutParams stl = Ui.matchWrap();
        stl.setMargins(0, Ui.dp(this, 4), 0, 0);
        turn.addView(stepText, stl);
        turnButton = Ui.button(this, "Start", Ui.ACTIVE, true);
        turnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTurn();
            }
        });
        LinearLayout.LayoutParams tl = Ui.matchWrap();
        tl.setMargins(0, Ui.dp(this, 10), 0, 0);
        turn.addView(turnButton, tl);
        turn.addView(Ui.gap(this, 6));
        walkNote = Ui.text(this, TURN_NOTE, 12, Ui.TEXT_3, Ui.REGULAR);
        turn.addView(walkNote);
        root.addView(turn);

        TextView done = Ui.button(this, "Done", Ui.TEXT_2, false);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(done, Ui.matchWrap());
        setContentView(sv);

        if (demo && demoTurnOnly) demoTurn();
        else if (demo) demoWalk();
        if (demo) demoTrail();
        loadLast();
        showTurn();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        listenSince = SystemClock.elapsedRealtime();
        if (rotation != null && !demo) sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (RuntimeException e) {
            tone = null; // no audio right now: vibration still works
        }
        startScan();
        h.post(tick);
        h.post(clicker);
        if (demo) h.post(demoSignal);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
        h.removeCallbacks(clicker);
        h.removeCallbacks(demoSignal);
        h.removeCallbacks(retryScan);
        stopScan();
        if (sm != null) sm.unregisterListener(this);
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (vib != null) vib.cancel();
        if (recording) {
            // a walk needs the screen; what was recorded stays and gets its circle (both sensors are released above)
            recording = false;
            spotDirty = true;
            showTurn();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (askedPermission && !Permissions.granted(this)) {
            // denied and Android will not ask again: only the app's settings can switch it on
            boolean canAsk = false;
            for (String p : Permissions.needed()) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(p)) canAsk = true;
            }
            permissionBlocked = !canAsk;
        }
        if (resumed) startScan();
    }

    boolean locationOn() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        if (android.os.Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
    }

    // ------------------------------------------------------------------ where was it last

    void loadLast() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LastWorn.Result r = RightNow.lastWorn(Db.get(app), System.currentTimeMillis() / 1000.0);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) showLast(r);
                    }
                });
            }
        }, "finder-last").start();
    }

    void showLast(LastWorn.Result r) {
        String s;
        if (r.state == LastWorn.WORN) {
            s = "The newest reading (" + when(r.at) + ") was from a finger ("
                    + (Double.isNaN(r.skinC) ? "a heart rate" : "skin " + Ui.fmt(r.skinC, 1) + " °C") + ").";
        } else if (r.state == LastWorn.OFF && !Double.isNaN(r.at)) {
            s = "Taken off " + when(r.at) + " (then it lay at room temperature, about " + Math.round(r.roomC) + " °C).";
        } else if (r.state == LastWorn.OFF) {
            s = "Not worn in the last 7 days of synced data (it reads room temperature, about " + Math.round(r.roomC) + " °C).";
        } else {
            s = "No temperature readings yet. Sync once to fill this in.";
        }
        lastText.setText(s);
        Prefs p = Prefs.of(this);
        if (demo) {
            heardText.setText("In the demo the signal below is made up.");
            batteryText.setVisibility(View.GONE);
            return;
        }
        showHeard();
        if (p.battery() < 0) {
            batteryText.setVisibility(View.GONE);
            return;
        }
        String bt = "Battery " + p.battery() + "% (read " + when(p.batteryAt()) + ").";
        int ch = LastWorn.charging(p.batteryBefore(), p.battery());
        if (ch == 1) bt += " It went up since the read before (" + p.batteryBefore() + "%), so it was probably on the charger then.";
        else if (ch == 0) bt += " It went down since the read before (" + p.batteryBefore() + "%), so it was not charging then.";
        batteryText.setText(bt);
    }

    /** The "last heard" line: right now while the meter hears the ring, else the saved time. */
    void showHeard() {
        if (demo || heardText == null) return;
        if (prox.band() != Proximity.NOT_HEARD) {
            heardText.setText("This phone hears it right now.");
            return;
        }
        long heard = Prefs.of(this).lastHeard();
        heardText.setText(heard == 0 ? "This phone has not noted the ring yet (it starts noting this with this version)."
                : "This phone last heard it " + when(heard) + ".");
    }

    /** "today at 22:14", "yesterday at 22:14" or "on Mon 13.10. at 22:14". */
    static String when(double unix) {
        ZonedDateTime z = Instant.ofEpochSecond((long) unix).atZone(ZoneId.systemDefault());
        String clock = String.format(Locale.getDefault(), "%02d:%02d", z.getHour(), z.getMinute());
        LocalDate d = z.toLocalDate(), today = LocalDate.now();
        if (d.equals(today)) return "today at " + clock;
        if (d.equals(today.minusDays(1))) return "yesterday at " + clock;
        return "on " + z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " "
                + d.getDayOfMonth() + "." + d.getMonthValue() + ". at " + clock;
    }

    // ------------------------------------------------------------------ scan

    void startScan() {
        if (!resumed || demo || watch != null || address == null) return;
        // not a failure to wait out any more: the screen says why, and tick starts the scan once it is solved
        if (!Permissions.granted(this)) {
            retry.blocked();
            return;
        }
        BluetoothAdapter a = RingScanner.adapter(this);
        if (a == null || !a.isEnabled()) {
            retry.blocked();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // the start budget is shared with a sync's own scan (RingScanner counts both)
        long wait = RingScanner.startWaitMs();
        if (wait > 0) {
            h.removeCallbacks(retryScan);
            h.postDelayed(retryScan, wait);
            return;
        }
        retry.started();
        try {
            watch = RingScanner.watch(this, address, new RingScanner.Heard() {
                @Override
                public void onHeard(final boolean ours, final int rssi, final long at) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            heard(ours, rssi, at);
                        }
                    });
                }

                @Override
                public void onScanFailed(final int code) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            scanFailed(code);
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            // a permission taken back or Bluetooth switching off at this moment
            watch = null;
            scanFailed(-1);
        }
        listenSince = now;
    }

    void stopScan() {
        if (watch != null) watch.stop();
        watch = null;
    }

    final Runnable retryScan = new Runnable() {
        @Override
        public void run() {
            startScan();
        }
    };

    void scanFailed(int code) {
        stopScan();
        // 6 is "scanning too frequently": Android lets it start again after 30 s
        long wait = retry.failed(SystemClock.elapsedRealtime(), code);
        h.removeCallbacks(retryScan);
        if (resumed) h.postDelayed(retryScan, wait);
    }

    void heard(boolean ours, int rssi, long at) {
        if (!resumed) return;
        if (!ours) {
            otherAt = at;
            return;
        }
        if (!Proximity.plausible(rssi)) {
            // 127 means "not available": not a reading, so the meter, chart and walk never see it
            unusableAt = at;
            return;
        }
        prox.add(at, rssi);
        double t = System.currentTimeMillis() / 1000.0;
        trail.addLast(new double[] {t, prox.value()});
        while (!trail.isEmpty() && trail.peekFirst()[0] < t - 125) trail.pollFirst();
        if (recording && walk != null && !demo) {
            walk.rssi(at, rssi);
            spotDirty = true;
        }
    }

    /** The ring is connected to this phone (this app or another one): it stops advertising then. */
    boolean linked() {
        if (demo || address == null) return false;
        if (RingManager.get(this).linked()) return true;
        long now = SystemClock.elapsedRealtime();
        if (now - linkCheckedAt > 2000) {
            linkCheckedAt = now;
            systemLinked = false;
            try {
                BluetoothManager m = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter a = m == null ? null : m.getAdapter();
                if (a != null && a.isEnabled()) systemLinked = m.getConnectionState(a.getRemoteDevice(address), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED;
            } catch (RuntimeException e) {
                systemLinked = false; // no permission to ask: say nothing about it
            }
        }
        return systemLinked;
    }

    // ------------------------------------------------------------------ screen updates

    private long trailAt;

    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtime();
            prox.tick(now);
            // Bluetooth switched on or off while the screen is open
            BluetoothAdapter a = demo ? null : RingScanner.adapter(FinderActivity.this);
            boolean on = a != null && a.isEnabled();
            if (!demo && !on) {
                if (watch != null) stopScan();
                // nothing to wait out while Bluetooth is off; the scan starts below once it is on again
                retry.blocked();
                h.removeCallbacks(retryScan);
            }
            if (!demo && retry.shouldStart(now, on, watch != null)) startScan();
            showSignal(now, on);
            showHeard();
            showSoundHint();
            if (now - trailAt >= 1000) {
                trailAt = now;
                drawTrail();
            }
            showTurn();
            h.postDelayed(this, 250);
        }
    };

    void showSignal(long now, boolean btOn) {
        double v = prox.value();
        int band = prox.band();
        meter.set(Proximity.fill(v), band, v);
        issueBox.setVisibility(View.GONE);
        signalText.setText("");
        if (!demo && address == null) {
            bandText.setText("No ring paired");
            issue("No ring is paired with this app yet, so there is no signal to listen for.", null, null);
            return;
        }
        if (!demo && !Permissions.granted(this)) {
            bandText.setText("Not listening");
            String what = android.os.Build.VERSION.SDK_INT >= 31 ? "the Nearby devices permission"
                    : "the Location permission (Android 11 and older ask for it to find Bluetooth devices; Open Ring has no internet and keeps no place)";
            if (permissionBlocked) {
                issue("To hear the ring, this app needs " + what + ". Android no longer asks, so switch it on in the app's settings.", "Open app settings", new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", getPackageName(), null)));
                    }
                });
            } else {
                issue("To hear the ring, this app needs " + what + ".", "Allow", new Runnable() {
                    @Override
                    public void run() {
                        askedPermission = true;
                        Permissions.request(FinderActivity.this);
                    }
                });
            }
            return;
        }
        if (!demo && android.os.Build.VERSION.SDK_INT < 31 && !locationOn()) {
            bandText.setText("Not listening");
            issue("Location is off. Android 11 and older only find Bluetooth devices while Location is on.", "Open Location settings", new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                }
            });
            return;
        }
        if (!demo && !btOn) {
            bandText.setText("Not listening");
            issue("Bluetooth is off. Switch it on to listen for the ring.", "Switch on", new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                    }
                }
            });
            return;
        }
        if (band != Proximity.NOT_HEARD) {
            bandText.setText(Proximity.label(band));
            signalText.setText("Signal " + Math.round(v) + " dBm. Closer to 0 is stronger; it tells warmer or colder, not metres.");
            return;
        }
        if (linked()) {
            bandText.setText("Connected");
            issue("The ring is connected to this phone right now (a sync, Live or another app), so it is within Bluetooth range, "
                    + "about 10 m. While connected it stops sending the signal this meter listens to; the meter carries on when the connection ends.", null, null);
            return;
        }
        if (retry.waiting(now)) {
            bandText.setText("Not listening");
            int scanError = retry.error();
            issue(scanError == 6 ? "Android paused the Bluetooth scan for a moment. It starts again by itself within 30 s."
                    : "The Bluetooth scan stopped (error " + scanError + "). It starts again by itself within 30 s.", null, null);
            return;
        }
        if (!demo && watch == null) {
            // the start waits for Android's scan budget (shared with a sync): not listening yet, so "not heard" would mislead
            bandText.setText("Not listening yet");
            signalText.setText("It starts listening by itself in a moment.");
            return;
        }
        long heardAgo = prox.sinceLast(now);
        if (heardAgo < 0 && now - listenSince < Proximity.LOST_MS) {
            bandText.setText("Listening");
            signalText.setText("Waiting for the ring's signal");
            return;
        }
        bandText.setText(Proximity.label(Proximity.NOT_HEARD));
        String why = "The ring is out of range, its battery is empty, or a thick wall or metal is in the way.";
        if (otherAt != Long.MIN_VALUE && now - otherAt < Proximity.LOST_MS) why = "This phone hears an Oura ring, but not the one paired with this app.";
        if (unusableAt != Long.MIN_VALUE && now - unusableAt < Proximity.LOST_MS) why = UNUSABLE;
        signalText.setText(why);
    }

    void showSoundHint() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean silent = am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        soundHint.setText(silent && Prefs.of(this).finderSound() ? "Media volume is off, so you only feel the clicks. Turn the volume up to hear them." : "");
        soundHint.setVisibility(soundHint.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    void issue(String text, String button, final Runnable r) {
        issueBox.setVisibility(View.VISIBLE);
        issueText.setText(text);
        issueButton.setVisibility(button == null ? View.GONE : View.VISIBLE);
        if (button != null) {
            issueButton.setText(button);
            issueButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.run();
                }
            });
        }
    }

    void drawTrail() {
        double t = System.currentTimeMillis() / 1000.0;
        TS ts = new TS();
        for (double[] p : trail) if (p[0] >= t - 120) ts.add(p[0], p[1]);
        trailChart.data(ts, t - 120, t);
    }

    // ------------------------------------------------------------------ sound

    final Runnable clicker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            long iv = Proximity.clickIntervalMs(prox.value());
            if (iv < 0 || !Prefs.of(FinderActivity.this).finderSound()) {
                h.postDelayed(this, 300);
                return;
            }
            if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 25);
            long now = SystemClock.elapsedRealtime();
            if (vib != null && now - vibAt >= 200) {
                vibAt = now;
                vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            h.postDelayed(this, iv);
        }
    };

    // ------------------------------------------------------------------ turn on the spot

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (!recording || walk == null || stepper == null) return;
            // the sensor's own time stamps keep the rhythm right when Android delivers samples in bursts
            int n = stepper.add(e.timestamp / 1000000L, e.values[0], e.values[1], e.values[2]);
            if (n > 0) {
                walk.steps(SystemClock.elapsedRealtime(), n);
                spotDirty = true;
            }
            return;
        }
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        double[] o = SearchWalk.headingOf(rot);
        facing = o[0];
        upright = o[1] <= SearchWalk.MAX_TILT_DEG;
        if (recording && walk != null) walk.heading(SystemClock.elapsedRealtime(), facing, upright);
        dial.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    void toggleTurn() {
        if (recording) {
            recording = false;
            if (sm != null && accel != null) sm.unregisterListener(this, accel);
            spotDirty = true;
        } else {
            if (rotation == null && !demo) {
                turnText.setText("This phone has no rotation sensor, so the search walk does not work on it. Hot and cold above still does.");
                return;
            }
            walk = new SearchWalk().stepLength(stepLength());
            stepper = new StepDetector();
            spot = null;
            spotDirty = false;
            recording = true;
            demoHeading = 0;
            wallOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            timeScale = 1;
            if (demo && !demoTurnOnly) {
                demoEvents = DemoWalk.events();
                demoIndex = 0;
                demoStart = SystemClock.elapsedRealtime();
                wallOffset = System.currentTimeMillis();
                timeScale = 0.5;
            }
            if (!demo && sm != null && accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        showTurn();
    }

    /** Metres per step: 0.41 × the height from the profile (175 cm when none was entered, and in the demo). */
    double stepLength() {
        return StepDetector.stepLength(demo ? 175 : Prefs.of(this).heightCm());
    }

    /**
     * The circle is worked out again every 3 s while walking, and at once when the walk ends. The fit
     * grows with the walk, so it runs on its own thread on a copy of the record and posts back.
     */
    void updateSpot() {
        if (walk == null || !spotDirty || locating) return;
        long now = SystemClock.elapsedRealtime();
        if (recording && now - spotAt < 3000) return;
        spotAt = now;
        spotDirty = false;
        locating = true;
        final SearchWalk of = walk, copy = walk.snapshot();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SearchWalk.Spot s = copy.locate();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        locating = false;
                        if (walk != of || isFinishing()) return;
                        spot = s;
                        showTurn();
                    }
                });
            }
        }, "finder-spot").start();
    }

    void showTurn() {
        turnButton.setText(recording ? "Stop" : walk == null ? "Start" : "Start again");
        boolean walked = walk != null && walk.reach() >= SearchWalk.SPOT_M;
        dial.setVisibility(walked ? View.GONE : View.VISIBLE);
        map.setVisibility(walked ? View.VISIBLE : View.GONE);
        walkNote.setText(walked ? WALK_NOTE : TURN_NOTE);
        showStepLength();
        dial.invalidate();
        if (walk == null) {
            turnText.setText(rotation == null && !demo ? "This phone has no rotation sensor, so the search walk does not work on it." : "");
            return;
        }
        if (walked) {
            updateSpot();
            map.set(walk, spot, walk.relative(facing), wallOffset, timeScale);
            showWalk();
            return;
        }
        StringBuilder b = new StringBuilder();
        if (!demo && accel == null) b.append("This phone has no accelerometer, so steps are not counted; turning on the spot still works.\n");
        SearchWalk.Direction d = walk.direction();
        if (d == null) {
            b.append("Covered ").append(walk.coveredDegrees()).append(" of 360 degrees (the directions you faced with the phone upright).");
            if (!recording) b.append(" Too little for a direction: tap Start again and turn once all the way around.");
        } else if (d.clear) {
            b.append("Strongest towards the arrow, ").append(Math.round(d.spread)).append(" dB more than the weakest side (more dB is a clearer direction).");
        } else {
            b.append("No clear direction (").append(Math.round(d.spread)).append(" dB between strongest and weakest side, under ")
                    .append(Math.round(SearchWalk.CLEAR_DB)).append("). The ring may be very close, above or below you, or the signal bounced off walls.");
        }
        if (recording && !upright) b.append("\n").append(HOLD).append(" Readings while it is tilted are not counted.");
        if (recording && prox.band() == Proximity.NOT_HEARD) b.append("\nNo signal right now; the turn needs the ring to be heard.");
        turnText.setText(b.toString());
    }

    /** The walk's text: how far, how much of the room, and where the ring probably is. */
    void showWalk() {
        StringBuilder b = new StringBuilder();
        long moved = Math.round(walk.distance() / walk.stepLength());
        b.append("Walked ").append(Ui.fmt(walk.distance(), 1)).append(" m (").append(moved).append(moved == 1 ? " step" : " steps")
                .append(" of ").append(Ui.fmt(walk.stepLength(), 2)).append(" m).");
        if (spot == null && walk.mapReady()) {
            b.append("\nWorking out where the ring probably is.");
        } else if (spot == null && walk.covered()) {
            // enough of the room, but the ring was hardly heard
            b.append("\nThe ring was heard for only ").append(Texts.count((long) Math.floor(walk.heardSeconds()), "second", "seconds")).append(" of the walk; the circle needs ")
                    .append(SearchWalk.MIN_BINS / 2).append(". While a sync, Live or another app is connected to the ring, it sends no signal.");
            if (linked()) b.append(" It is connected to this phone right now.");
            else if (prox.band() == Proximity.NOT_HEARD) b.append(" This phone does not hear it right now.");
        } else if (spot == null) {
            double[] c = walk.cover();
            double across = c[1] >= SearchWalk.WIDTH_M ? c[0] : c[1];
            b.append(" Across ").append(Ui.fmt(Math.min(across, SearchWalk.COVER_M), 1)).append(" of ").append(Math.round(SearchWalk.COVER_M))
                    .append(" m in two directions (the circle needs ").append(Math.round(SearchWalk.COVER_M)).append(" m both ways).");
            b.append("\nWalk a bit more, across the room.");
        } else if (spot.clear) {
            b.append("\nProbably within the circle: ").append(SearchWalk.where(spot.x, spot.y)).append(" (radius ").append(Ui.fmt(spot.radius, 1))
                    .append(" m). Start looking there.");
            if (spot.beyond) b.append(" It lies at the edge of where you walked, so the ring may also be a bit farther that way.");
        } else if (spot.edge) {
            b.append("\nThe signal gets stronger towards the edge of where you walked (the dashed circle, ").append(SearchWalk.where(spot.x, spot.y))
                    .append("), so the ring is probably farther that way. "
                    + "Walk on in that direction and look again.");
        } else {
            b.append("\nThe signal gave no clear spot yet (the circle would be wider than ").append(Math.round(SearchWalk.CLEAR_RADIUS_M))
                    .append(" m, or the signal hardly changed). Walk more of the room; if that does not help, the ring may be in another room, "
                    + "upstairs or downstairs, or under something thick.");
        }
        if (recording && !upright) b.append("\n").append(HOLD).append(" Readings while it is tilted are not counted, steps keep your last direction.");
        if (recording && prox.band() == Proximity.NOT_HEARD) b.append("\nNo signal right now; the walk needs the ring to be heard.");
        turnText.setText(b.toString());
    }

    void showStepLength() {
        if (walk == null || walk.reach() < SearchWalk.SPOT_M) {
            stepText.setVisibility(View.GONE);
            return;
        }
        stepText.setVisibility(View.VISIBLE);
        String len = "Steps of " + Ui.fmt(walk.stepLength(), 2) + " m: 0.41 × ";
        if (demo) stepText.setText(len + "175 cm, a usual height (demo).");
        else if (Prefs.of(this).hasHeight()) stepText.setText(len + "your height of " + Prefs.of(this).heightCm() + " cm from the profile.");
        else stepText.setText(len + "175 cm, a usual height. Enter yours in Settings, Profile and goals for a truer map.");
    }

    // ------------------------------------------------------------------ demo

    /** Demo: a made up signal wandering around -62 dBm, and a made up heading while turning. */
    final Runnable demoSignal = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtime();
            if (recording && !demoTurnOnly) {
                // replay the made up walk at twice its speed
                demoIndex = DemoWalk.feed(walk, demoEvents, demoIndex, (now - demoStart) * 2);
                facing = walk.heading();
                spotDirty = true;
            } else if (recording) {
                demoHeading += 360.0 * 0.15 / 20;
                facing = SearchWalk.norm(demoHeading);
                walk.heading(now, facing, true);
            } else if (Double.isNaN(facing)) {
                facing = 0;
            }
            double toward = Math.toRadians(SearchWalk.diff(facing, DEMO_RING_AT));
            double v = -60 + 3 * Math.sin(now / 7000.0) - 8 * (1 - Math.cos(toward)) / 2 + demoNoise.nextGaussian() * 2.5;
            heard(true, (int) Math.round(v), now);
            if (recording && demoTurnOnly) walk.rssi(now, Math.round(v));
            dial.invalidate();
            h.postDelayed(this, 150);
        }
    };

    static final double DEMO_RING_AT = 300;

    /** Demo: a finished made up walk around a room (ring at a known spot), so the map shows the path, dots and the circle. */
    void demoWalk() {
        walk = new SearchWalk().stepLength(stepLength());
        List<double[]> ev = DemoWalk.events();
        DemoWalk.feed(walk, ev, 0, Long.MAX_VALUE);
        // the walk ended a minute ago
        wallOffset = System.currentTimeMillis() - 60000 - (long) ev.get(ev.size() - 1)[0];
        spot = walk.locate();
        facing = walk.heading();
    }

    /** Demo: the last 2 minutes of the made up signal, so the chart shows a trail from the start. */
    void demoTrail() {
        Proximity p = new Proximity();
        Random r = new Random(75);
        double now = System.currentTimeMillis() / 1000.0;
        for (int i = 800; i > 0; i--) {
            double t = now - i * 0.15;
            double v = -60 + 3 * Math.sin(t / 7.0) - 8 * (1 - Math.cos(Math.toRadians(DEMO_RING_AT))) / 2 + r.nextGaussian() * 2.5;
            p.add((long) (t * 1000), Math.round(v));
            trail.addLast(new double[] {t, p.value()});
        }
        drawTrail();
    }

    /** Demo (extra demo_turn): a finished turn (the ring at 300 degrees) so the dial shows petals and an arrow. */
    void demoTurn() {
        walk = new SearchWalk();
        Random r = new Random(4);
        for (long t = 0; t <= 20000; t += 20) {
            double faced = 360.0 * t / 20000;
            walk.heading(t, faced, true);
            if (t % 140 == 0) {
                double behind = (1 - Math.cos(Math.toRadians(SearchWalk.diff(faced, DEMO_RING_AT)))) / 2;
                walk.rssi(t, -62 - 10 * behind + r.nextGaussian() * 2.5);
            }
        }
        facing = 0;
    }

    // ------------------------------------------------------------------ views

    static int bandColor(int band) {
        switch (band) {
            case Proximity.CLOSE: return Ui.HEART;
            case Proximity.ROOM: return Ui.ACTIVE;
            case Proximity.NEARBY: return Ui.TEMP;
            case Proximity.FAR: return Ui.OXY;
            default: return Ui.TEXT_3;
        }
    }

    /** The big ring that fills as the signal gets stronger, with the smoothed dBm in the middle. */
    static final class Meter extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), unit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private double fill, value = Double.NaN;
        private int band;

        Meter(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(Ui.LINE);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            num.setColor(Ui.TEXT);
            num.setTypeface(Ui.LIGHT);
            num.setTextAlign(Paint.Align.CENTER);
            unit.setColor(Ui.TEXT_2);
            unit.setTypeface(Ui.MEDIUM);
            unit.setTextAlign(Paint.Align.CENTER);
        }

        void set(double fill, int band, double value) {
            if (fill == this.fill && band == this.band && (value == this.value || Double.isNaN(value) && Double.isNaN(this.value))) return;
            this.fill = fill;
            this.band = band;
            this.value = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight(), size = Math.min(w, hh), stroke = size * 0.09f;
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            float pad = stroke / 2 + 2;
            oval.set((w - size) / 2 + pad, (hh - size) / 2 + pad, (w + size) / 2 - pad, (hh + size) / 2 - pad);
            cv.drawArc(oval, 135, 270, false, track);
            if (fill > 0) {
                arc.setColor(bandColor(band));
                cv.drawArc(oval, 135, (float) (270 * fill), false, arc);
            }
            num.setTextSize(size * 0.24f);
            cv.drawText(Double.isNaN(value) ? "--" : String.valueOf(Math.round(value)), w / 2, hh / 2 + num.getTextSize() * 0.3f, num);
            unit.setTextSize(size * 0.075f);
            cv.drawText("dBm", w / 2, hh / 2 + num.getTextSize() * 0.3f + unit.getTextSize() * 1.6f, unit);
        }
    }

    /**
     * The turn: one petal per direction (longer is stronger), drawn as seen by the person now (up is
     * where they face), and an arrow to the strongest direction. Touch a petal to read it.
     */
    final class Dial extends View {
        private final Paint petal = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float touchAngle = Float.NaN;

        Dial(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Ui.LINE);
            ring.setStrokeWidth(Ui.dp(c, 1));
            arrow.setColor(Ui.TEXT);
            arrow.setStrokeWidth(Ui.dp(c, 3));
            arrow.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(Ui.TEXT_3);
            txt.setTextSize(Ui.dp(c, 10));
            txt.setTextAlign(Paint.Align.CENTER);
            tipBg.setColor(Ui.BG);
        }

        /** Where the person faces now, in the walk's terms (relative to the start once a walk began). */
        float facingNow() {
            if (Double.isNaN(facing)) return 0;
            return (float) (walk == null ? facing : walk.relative(facing));
        }

        private float downX, downY;
        private boolean dragging;
        private final int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();

        /**
         * Like the other dials: a tap reads the petal under the finger, a sideways or circling drag
         * keeps reading, a vertical swipe scrolls the page, a tap in the middle clears the readout.
         */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f;
            double d = Math.sqrt(dx * dx + dy * dy), rad = Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 18);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (d > rad + Ui.dp(getContext(), 12)) return false;
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        float mx = Math.abs(e.getX() - downX), my = Math.abs(e.getY() - downY);
                        if (my > slop && my >= mx) return false; // a vertical swipe: the page scrolls
                        if (mx > slop) {
                            dragging = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if (dragging) read(dx, dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        if (d < rad * 0.25) touchAngle = Float.NaN;
                        else read(dx, dy);
                        invalidate();
                        performClick();
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    return true;
                default:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    return true;
            }
        }

        void read(float dx, float dy) {
            // clockwise from up, as seen by the person now
            touchAngle = (float) Math.toDegrees(Math.atan2(dx, -dy));
            invalidate();
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight(), cx = w / 2, cy = hh / 2;
            float rad = Math.min(w, hh) / 2 - Ui.dp(getContext(), 18);
            cv.drawCircle(cx, cy, rad, ring);
            cv.drawCircle(cx, cy, rad * 0.5f, ring);
            cv.drawText("You face this way", cx, Ui.dp(getContext(), 11), txt);
            if (walk == null) return;
            double[] means = walk.sectorMeans();
            int[] counts = walk.sectorCounts();
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double m : means) if (!Double.isNaN(m)) {
                lo = Math.min(lo, m);
                hi = Math.max(hi, m);
            }
            float face = facingNow();
            for (int i = 0; i < SearchWalk.SECTORS; i++) {
                if (Double.isNaN(means[i])) continue;
                double k = hi - lo < 1 ? 1 : 0.25 + 0.75 * (means[i] - lo) / (hi - lo);
                float pr = (float) (rad * k);
                float start = (float) (i * SearchWalk.SECTOR_DEG - SearchWalk.SECTOR_DEG / 2 - face - 90);
                r.set(cx - pr, cy - pr, cx + pr, cy + pr);
                petal.setColor(Ui.alpha(Ui.blend(Ui.OXY, Ui.HEART, (float) Math.min(1, Math.max(0, k))), 0.75f));
                cv.drawArc(r, start + 1.5f, (float) SearchWalk.SECTOR_DEG - 3, true, petal);
            }
            SearchWalk.Direction d = walk.direction();
            if (d != null && d.clear) {
                double a = Math.toRadians(d.degrees - face);
                float ex = (float) (cx + Math.sin(a) * rad * 0.95), ey = (float) (cy - Math.cos(a) * rad * 0.95);
                cv.drawLine(cx, cy, ex, ey, arrow);
                Path head = new Path();
                double back = rad * 0.16, spread = Math.toRadians(150);
                head.moveTo(ex, ey);
                head.lineTo((float) (ex + Math.sin(a + spread) * back), (float) (ey - Math.cos(a + spread) * back));
                head.lineTo((float) (ex + Math.sin(a - spread) * back), (float) (ey - Math.cos(a - spread) * back));
                head.close();
                cv.drawPath(head, arrow);
            }
            if (!Float.isNaN(touchAngle)) {
                int s = SearchWalk.sector(touchAngle + face);
                double rel = SearchWalk.norm(s * SearchWalk.SECTOR_DEG - face);
                String where = rel < 15 || rel > 345 ? "Straight ahead" : rel <= 180 ? Math.round(rel) + "° to your right" : Math.round(360 - rel) + "° to your left";
                String tip = Double.isNaN(means[s]) ? where + ": no reading" : where + ": " + Math.round(means[s]) + " dBm, " + counts[s] + (counts[s] == 1 ? " reading" : " readings");
                txt.setColor(Ui.TEXT);
                txt.setTextSize(Ui.dp(getContext(), 11.5f));
                float tw = txt.measureText(tip) + Ui.dp(getContext(), 12), th = Ui.dp(getContext(), 22);
                r.set(cx - tw / 2, hh - th, cx + tw / 2, hh);
                cv.drawRoundRect(r, th / 2, th / 2, tipBg);
                cv.drawText(tip, cx, hh - th / 2 + Ui.dp(getContext(), 4), txt);
                txt.setColor(Ui.TEXT_3);
                txt.setTextSize(Ui.dp(getContext(), 10));
            }
        }
    }
}
