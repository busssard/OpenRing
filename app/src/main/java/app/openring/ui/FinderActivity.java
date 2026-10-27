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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import app.openring.core.PermissionAsk;
import app.openring.core.Texts;
import app.openring.core.analysis.DemoWalk;
import app.openring.core.analysis.FinderLayout;
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
 * on a mini map and the circle where the ring probably is. All on one screen (issue #191): a last worn
 * strip that opens to the full text, the instruments (gauge, band, signal trail) and the map, which
 * takes the height left over ({@link FinderLayout}). Everything that listens (scan, sensors, sound) runs only while the screen
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
    /** The last request on this screen came back refused without Android's explanation (issue #140). */
    private boolean quietDenial;
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

    /** Before Start: what to do, in the status line under the map (issue #191). */
    static final String START_HINT = "Hold the phone upright against your chest and tap Start. Walk slowly through the room, "
            + "or turn once on the spot for only a direction.";
    /** The line next to the gauge: what its number means. */
    static final String SIGNAL_LINE = "dBm: closer to 0 is stronger. It tells warmer or colder, not metres.";

    private TextView lastText, heardText, batteryText, bandText, signalText, issueText, turnText, turnButton, soundHint;
    private LinearLayout issueBox;
    private TextView issueButton;
    private Meter meter;
    private Dial dial;
    // the one screen layout (issue #191): last worn strip, instruments, map
    private ScrollView scroll;
    private LinearLayout root, lastCard, instCard, mapCard, lastDetails, helpBox;
    private View titleGap;
    private android.widget.FrameLayout mapFrame;
    private TextView lastBrief, soundChip, helpButton, trailCaption;
    /** The free place in the last worn strip for the "on a finger?" status of issue #192 (hidden until then). */
    private TextView fingerSlot;
    private android.widget.ImageView lastMore;
    private boolean detailsOpen, helpOpen;
    private LastWorn.Result lastResult;
    private FinderLayout.Plan plan;

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
        boolean demoFresh = demo && getIntent().getBooleanExtra("demo_start", false);
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // one screen: the map card takes the height left over. The ScrollView only scrolls when even the
        // compact plan does not fit (a very large font) or the details are open; nothing is ever cut.
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        root = Ui.vbox(this);
        scroll.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -2));

        // ---- top bar: back, title, the clicks chip ----
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
        TextView title = Ui.text(this, "Find my ring", 22, Ui.TEXT, Ui.LIGHT);
        title.setSingleLine(true);
        top.addView(title, Ui.weight(1));
        soundChip = Ui.smallButton(this, "", Ui.ACTIVE);
        soundChip.setMinHeight(Ui.dp(this, 34));
        soundChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        soundChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs pr = Prefs.of(FinderActivity.this);
                pr.setFinderSound(!pr.finderSound());
                showSoundChip();
                showSoundHint();
            }
        });
        top.addView(soundChip);
        root.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
        titleGap = Ui.gap(this, 6);
        root.addView(titleGap);

        // ---- last worn strip: one or two lines, tap for the full text ----
        lastCard = Ui.card(this);
        LinearLayout lastRow = Ui.hbox(this);
        lastBrief = Ui.text(this, "Reading the ring's history", 14, Ui.TEXT, Ui.REGULAR);
        lastBrief.setLineSpacing(0, 1.1f);
        lastRow.addView(lastBrief, Ui.weight(1));
        fingerSlot = Ui.text(this, "", 12, Ui.TEXT_2, Ui.MEDIUM);
        fingerSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.setMargins(Ui.dp(this, 8), 0, 0, 0);
        lastRow.addView(fingerSlot, fl);
        lastMore = new android.widget.ImageView(this);
        lastMore.setImageResource(app.openring.R.drawable.ic_chevron_right);
        lastMore.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_3));
        lastMore.setRotation(90);
        LinearLayout.LayoutParams ml0 = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
        ml0.setMargins(Ui.dp(this, 6), 0, 0, 0);
        lastRow.addView(lastMore, ml0);
        lastCard.addView(lastRow);
        lastDetails = Ui.vbox(this);
        lastDetails.addView(Ui.gap(this, 8));
        lastText = Ui.text(this, "", 14, Ui.TEXT, Ui.REGULAR);
        lastText.setLineSpacing(0, 1.15f);
        lastDetails.addView(lastText);
        lastDetails.addView(Ui.gap(this, 6));
        heardText = Ui.body(this, "");
        lastDetails.addView(heardText);
        batteryText = Ui.body(this, "");
        lastDetails.addView(batteryText);
        lastDetails.addView(Ui.gap(this, 6));
        lastDetails.addView(Ui.text(this, Explain.FINDER_LAST, 12, Ui.TEXT_3, Ui.REGULAR));
        lastDetails.setVisibility(View.GONE);
        lastCard.addView(lastDetails);
        lastCard.setClickable(true);
        lastCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetails(!detailsOpen);
            }
        });
        root.addView(lastCard, cardParams(false));

        // ---- instruments: gauge with band and signal, then the trail strip ----
        instCard = Ui.card(this);
        LinearLayout row = Ui.hbox(this);
        meter = new Meter(this);
        row.addView(meter, new LinearLayout.LayoutParams(Ui.dp(this, FinderLayout.GAUGE_MIN), Ui.dp(this, FinderLayout.GAUGE_MIN)));
        LinearLayout col = Ui.vbox(this);
        bandText = Ui.text(this, "", 16, Ui.TEXT, Ui.REGULAR);
        col.addView(bandText, Ui.matchWrap());
        signalText = Ui.text(this, "", 12, Ui.TEXT_3, Ui.REGULAR);
        LinearLayout.LayoutParams sgl = Ui.matchWrap();
        sgl.setMargins(0, Ui.dp(this, 2), 0, 0);
        col.addView(signalText, sgl);
        LinearLayout.LayoutParams cl = Ui.weight(1);
        cl.setMargins(Ui.dp(this, 12), 0, 0, 0);
        row.addView(col, cl);
        instCard.addView(row);
        issueBox = Ui.vbox(this);
        issueText = Ui.text(this, "", 13, Ui.WARN, Ui.REGULAR);
        issueText.setLineSpacing(0, 1.15f);
        issueBox.addView(issueText);
        issueButton = Ui.button(this, "Allow", Ui.OXY, true);
        LinearLayout.LayoutParams il = Ui.matchWrap();
        il.setMargins(0, Ui.dp(this, 8), 0, 0);
        issueBox.addView(issueButton, il);
        LinearLayout.LayoutParams ibl = Ui.matchWrap();
        ibl.setMargins(0, Ui.dp(this, 8), 0, 0);
        instCard.addView(issueBox, ibl);
        issueBox.setVisibility(View.GONE);
        soundHint = Ui.text(this, "", 12, Ui.WARN, Ui.REGULAR);
        soundHint.setVisibility(View.GONE);
        instCard.addView(soundHint);
        trailCaption = Ui.caption(this, "Signal in dBm, last 2 minutes");
        LinearLayout.LayoutParams tcl = Ui.matchWrap();
        tcl.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        instCard.addView(trailCaption, tcl);
        double now0 = System.currentTimeMillis() / 1000.0;
        trailChart = new Charts.LineChart(this, new TS(), now0 - 120, now0, Ui.OXY).noFill().unit(" dBm").empty("No signal yet").ago(Proximity.CHART_MIN_SPAN);
        instCard.addView(trailChart, Ui.sized(this, -1, FinderLayout.STRIP_MIN));
        root.addView(instCard, cardParams(false));

        // ---- the map: the dial with you in the middle before walking, the walked map after 1 m ----
        mapCard = Ui.card(this);
        helpBox = Ui.vbox(this);
        helpBox.addView(Ui.text(this, "1. " + HOLD + "\n" + "2. " + WALK + "\n" + "3. Tap Stop when you are done.", 13, Ui.TEXT_2, Ui.REGULAR));
        helpBox.addView(Ui.gap(this, 6));
        walkNote = Ui.text(this, TURN_NOTE, 12, Ui.TEXT_3, Ui.REGULAR);
        helpBox.addView(walkNote);
        stepText = Ui.text(this, "", 12, Ui.TEXT_3, Ui.REGULAR);
        LinearLayout.LayoutParams stl = Ui.matchWrap();
        stl.setMargins(0, Ui.dp(this, 4), 0, 0);
        helpBox.addView(stepText, stl);
        helpBox.addView(Ui.gap(this, 6));
        helpBox.addView(Ui.text(this, Explain.FINDER_SIGNAL, 12, Ui.TEXT_3, Ui.REGULAR));
        helpBox.addView(Ui.gap(this, 6));
        helpBox.addView(Ui.text(this, Explain.FINDER_CLICKS, 12, Ui.TEXT_3, Ui.REGULAR));
        helpBox.addView(Ui.gap(this, 10));
        helpBox.setVisibility(View.GONE);
        mapCard.addView(helpBox);
        mapFrame = new android.widget.FrameLayout(this);
        dial = new Dial(this);
        mapFrame.addView(dial, new android.widget.FrameLayout.LayoutParams(-1, -1));
        map = new MiniMap(this);
        mapFrame.addView(map, new android.widget.FrameLayout.LayoutParams(-1, -1));
        map.setVisibility(View.GONE);
        mapFrame.setMinimumHeight(Ui.dp(this, FinderLayout.MAP_MIN));
        mapCard.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        turnText = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        turnText.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams ttl = Ui.matchWrap();
        ttl.setMargins(0, Ui.dp(this, 6), 0, 0);
        mapCard.addView(turnText, ttl);
        LinearLayout buttons = Ui.hbox(this);
        turnButton = Ui.button(this, "Start", Ui.ACTIVE, true);
        turnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTurn();
            }
        });
        buttons.addView(turnButton, Ui.weight(1));
        helpButton = Ui.smallButton(this, "How it works", Ui.TEXT_2);
        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHelp(!helpOpen);
            }
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.setMargins(Ui.dp(this, 8), 0, 0, 0);
        buttons.addView(helpButton, hl);
        LinearLayout.LayoutParams bl = Ui.matchWrap();
        bl.setMargins(0, Ui.dp(this, 8), 0, 0);
        mapCard.addView(buttons, bl);
        root.addView(mapCard, cardParams(true));
        setContentView(scroll);

        // share the height once it is known, and again when it changes (split screen, keyboard)
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        applyPlan(FinderLayout.plan(dm.heightPixels / dm.density - 24, fontScale()));
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                if (bt - t == ob - ot || bt - t <= 0) return;
                final FinderLayout.Plan np = FinderLayout.plan((bt - t) / getResources().getDisplayMetrics().density, fontScale());
                if (np.same(plan)) return;
                // not inside a layout pass: apply after it
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        applyPlan(np);
                    }
                });
            }
        });

        if (demo && demoTurnOnly) demoTurn();
        else if (demo && !demoFresh) demoWalk();
        if (demo) demoTrail();
        showSoundChip();
        loadLast();
        showTurn();
        if (demo && getIntent().getBooleanExtra("demo_details", false)) {
            openDetails(true);
            openHelp(true);
        }
    }

    float fontScale() {
        float f = getResources().getConfiguration().fontScale;
        return f > 0 ? f : 1;
    }

    /** A card's place in the column: the map card takes the height left over. */
    LinearLayout.LayoutParams cardParams(boolean fill) {
        LinearLayout.LayoutParams lp = fill ? new LinearLayout.LayoutParams(-1, 0, 1) : Ui.matchWrap();
        lp.setMargins(0, 0, 0, Ui.dp(this, FinderLayout.GAP));
        return lp;
    }

    /** Sizes from {@link FinderLayout}: gauge, trail strip, paddings, the map's minimum (issue #191). */
    void applyPlan(FinderLayout.Plan np) {
        plan = np;
        int side = Ui.dp(this, 16);
        root.setPadding(side, Ui.dp(this, np.compact ? 6 : 8), side, Ui.dp(this, np.compact ? 8 : 12) - Ui.dp(this, np.gap));
        titleGap.getLayoutParams().height = Ui.dp(this, np.compact ? 2 : 6);
        titleGap.setLayoutParams(titleGap.getLayoutParams());
        int pad = Ui.dp(this, np.pad);
        for (LinearLayout c : new LinearLayout[] {lastCard, instCard, mapCard}) {
            c.setPadding(pad, pad, pad, pad);
            ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = Ui.dp(this, np.gap);
            c.setLayoutParams(c.getLayoutParams());
        }
        meter.getLayoutParams().width = Ui.dp(this, np.gauge);
        meter.getLayoutParams().height = Ui.dp(this, np.gauge);
        meter.setLayoutParams(meter.getLayoutParams());
        trailChart.getLayoutParams().height = Ui.dp(this, np.strip);
        trailChart.setLayoutParams(trailChart.getLayoutParams());
        mapFrame.setMinimumHeight(Ui.dp(this, np.mapMin));
        turnText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, np.statusSp);
        root.requestLayout();
    }

    /** The full last worn text under the strip, or only the strip (Show the evidence: it stays one tap away). */
    void openDetails(boolean open) {
        detailsOpen = open;
        lastDetails.setVisibility(open ? View.VISIBLE : View.GONE);
        lastMore.setRotation(open ? -90 : 90);
    }

    /** The steps, the notes and the limits above the map. */
    void openHelp(boolean open) {
        helpOpen = open;
        helpBox.setVisibility(open ? View.VISIBLE : View.GONE);
        helpButton.setText(open ? "Close help" : "How it works");
    }

    void showSoundChip() {
        boolean on = Prefs.of(this).finderSound();
        soundChip.setText(on ? "Clicks on" : "Clicks off");
        Ui.outline(this, soundChip, on ? Ui.ACTIVE : Ui.TEXT_3);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        listenSince = SystemClock.elapsedRealtime();
        if (!demo) {
            // granted in the app's settings meanwhile: forget the refusal, Android asks afresh if it takes it back (#140)
            if (Permissions.granted(this)) permissionBlocked = quietDenial = false;
            Permissions.remember(this);
        }
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
            if (walk != null) walk.finish();
            spotDirty = true;
            showTurn();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // refused again after an explicit refusal, Android no longer asks: only the app's settings can switch it
        // on. A dialog closed without choosing (or an interrupted request) still asks next time (issue #140).
        PermissionAsk.Answer a = PermissionAsk.answer(Permissions.granted(this), perms, results, Permissions.needed(),
                Permissions.rationale(this), Prefs.of(this).permissionDenied(), quietDenial);
        if (askedPermission) {
            permissionBlocked = a.blocked;
            quietDenial = a.quiet;
        }
        Permissions.store(this, a.denied);
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

    static final LastWorn.Clock CLOCK = new LastWorn.Clock() {
        @Override
        public String when(double unix) {
            return FinderActivity.when(unix);
        }
    };

    void showLast(LastWorn.Result r) {
        lastResult = r;
        lastText.setText(LastWorn.text(r, System.currentTimeMillis() / 1000.0, CLOCK));
        Prefs p = Prefs.of(this);
        if (demo) {
            heardText.setText("In the demo the signal is made up.");
            batteryText.setVisibility(View.GONE);
            showBrief();
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
        } else {
            long heard = Prefs.of(this).lastHeard();
            heardText.setText(heard == 0 ? "This phone has not noted the ring yet (it starts noting this with this version)."
                    : "This phone last heard it " + when(heard) + ".");
        }
        showBrief();
    }

    /** The strip's one or two lines (issue #191): the short last worn sentence, then when this phone heard it. */
    void showBrief() {
        if (lastResult == null) return;
        String s = LastWorn.brief(lastResult, System.currentTimeMillis() / 1000.0, CLOCK);
        if (demo) s += " The signal is made up (demo).";
        else if (prox.band() != Proximity.NOT_HEARD) s += " Heard right now.";
        else if (Prefs.of(this).lastHeard() != 0) s += " Last heard " + when(Prefs.of(this).lastHeard()) + ".";
        if (!s.contentEquals(lastBrief.getText())) lastBrief.setText(s);
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
            signalText.setText(SIGNAL_LINE);
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
            // RingScanner allows 4 starts in 30 s, so the wait is at most 30.2 s (longer only if a sync starts more meanwhile)
            signalText.setText("It starts listening by itself within about half a minute.");
            return;
        }
        long heardAgo = prox.sinceLast(now);
        if (heardAgo < 0 && now - listenSince < Proximity.LOST_MS) {
            bandText.setText("Listening");
            signalText.setText("Waiting for the ring's signal");
            return;
        }
        // label and line from the same check, so they never disagree (issue #125)
        boolean unusable = unusableAt != Long.MIN_VALUE && now - unusableAt < Proximity.LOST_MS;
        bandText.setText(Proximity.label(Proximity.NOT_HEARD, unusable));
        String why = "The ring is out of range, its battery is empty, or a thick wall or metal is in the way.";
        if (otherAt != Long.MIN_VALUE && now - otherAt < Proximity.LOST_MS) why = "This phone hears an Oura ring, but not the one paired with this app.";
        if (unusable) why = UNUSABLE;
        signalText.setText(why);
    }

    void showSoundHint() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean silent = am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        soundHint.setText(silent && Prefs.of(this).finderSound() ? "Media volume is off, so you only feel the clicks." : "");
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
            long at = e.timestamp / 1000000L;
            int n = stepper.add(at, e.values[0], e.values[1], e.values[2]);
            if (n > 0) {
                // each step at its own time on the walk's clock: the first of a run is reported with the second (issue #108)
                for (long t : stepper.lastTimes()) walk.step(walkTime(t));
                spotDirty = true;
            }
            return;
        }
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        double[] o = SearchWalk.headingOf(rot);
        facing = o[0];
        upright = o[1] <= SearchWalk.MAX_TILT_DEG;
        if (recording && walk != null) walk.heading(walkTime(e.timestamp / 1000000L), facing, upright);
        dial.invalidate();
    }

    /** Sensor time (ms) minus the walk's clock (elapsed realtime), the largest seen since Start (the event Android delivered fastest), so delivery delays are left out. */
    private long sensorOffset = Long.MAX_VALUE;

    /**
     * A sensor time stamp on the walk's clock, the same for headings and steps (review of #182): both
     * sensors' events are placed by when they were measured, not by when Android delivered them.
     */
    long walkTime(long sensorMs) {
        long off = sensorMs - SystemClock.elapsedRealtime();
        if (sensorOffset == Long.MAX_VALUE || off > sensorOffset) sensorOffset = off;
        return sensorMs - sensorOffset;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    void toggleTurn() {
        if (recording) {
            recording = false;
            if (sm != null && accel != null) sm.unregisterListener(this, accel);
            // steps still waiting for a turn to end move now, unless they were a turn on the spot (issue #108)
            if (walk != null) walk.finish();
            spotDirty = true;
        } else {
            if (rotation == null && !demo) {
                turnText.setText("This phone has no rotation sensor, so the search walk does not work on it. The gauge and the trail above still work.");
                return;
            }
            walk = new SearchWalk().stepLength(stepLength());
            stepper = new StepDetector();
            sensorOffset = Long.MAX_VALUE;
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
            turnText.setText(rotation == null && !demo ? "This phone has no rotation sensor, so the search walk does not work on it." : START_HINT);
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
        appendWaiting(b);
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
            if (spot.beyond) b.append(" The circle lies outside where you walked, so the ring may also be farther that way (the signal cannot tell how far).");
        } else if (spot.edge) {
            b.append("\nThe signal gets stronger towards the edge of where you walked (the dashed circle, ").append(SearchWalk.where(spot.x, spot.y))
                    .append("), so the ring is probably farther that way. "
                    + "Walk on in that direction and look again.");
        } else {
            b.append("\nThe signal gave no clear spot yet (");
            if (spot.explained < spot.needed) {
                // issue #136: on a short walk plain noise fits some spot, so more is needed there
                long[] pc = SearchWalk.percents(spot.explained, spot.needed);
                b.append("its ups and downs could be plain noise: the model explains ").append(pc[0])
                        .append(" % of them, a walk this short needs ").append(pc[1]).append(" %");
            } else {
                b.append("the circle would be wider than ").append(Math.round(SearchWalk.CLEAR_RADIUS_M)).append(" m");
            }
            b.append("). Walk more of the room; if that does not help, the ring may be in another room, "
                    + "upstairs or downstairs, or under something thick.");
        }
        appendWaiting(b);
        if (recording && !upright) b.append("\n").append(HOLD).append(" Readings while it is tilted are not counted, steps keep your last direction.");
        if (recording && prox.band() == Proximity.NOT_HEARD) b.append("\nNo signal right now; the walk needs the ring to be heard.");
        turnText.setText(b.toString());
    }

    /**
     * Steps counted while turning wait until the turn ends; say so, so the step count and the map agree (issue #108).
     * Not for the one step that waits while the body sways on a straight walk ({@link SearchWalk#waitingToShow}).
     */
    void appendWaiting(StringBuilder b) {
        int n = walk.waitingToShow();
        if (n == 0) return;
        b.append("\n").append(Texts.count(n, "step", "steps")).append(" counted while you turned ").append(n == 1 ? "waits" : "wait")
                .append(": they move once you walk on, or stay if it was a turn on the spot.");
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
        walk.finish();
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
        private final Paint you = new Paint(Paint.ANTI_ALIAS_FLAG), youDot = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            // like "you" on the map: a white dot with a tick the way you face
            you.setColor(Ui.TEXT);
            you.setStrokeWidth(Ui.dp(c, 2));
            you.setStrokeCap(Paint.Cap.ROUND);
            youDot.setColor(Ui.TEXT);
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
            if (walk != null) drawTurn(cv, cx, cy, rad, hh);
            // you in the middle, facing up: the map's centre before you walk (issue #191)
            float len = Ui.dp(getContext(), 14);
            cv.drawLine(cx, cy, cx, cy - len, you);
            cv.drawCircle(cx, cy, Ui.dp(getContext(), 5), youDot);
        }

        void drawTurn(Canvas cv, float cx, float cy, float rad, float hh) {
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
