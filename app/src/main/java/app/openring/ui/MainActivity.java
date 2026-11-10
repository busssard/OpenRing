package app.openring.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import app.openring.R;
import app.openring.ble.RingManager;
import app.openring.data.Prefs;
import app.openring.sync.SyncJob;
import app.openring.sync.SyncService;

/** Home: date navigation, the six tabs (the last one is screen time) and the sync status strip. */
public final class MainActivity extends Activity implements RingManager.Listener, Pages.Nav {
    private LocalDate date = LocalDate.now();
    private int tab = Pages.TODAY;
    private ScrollView scroll;
    private TextView dateLabel, statusText;
    private ProgressBar statusBar;
    private LinearLayout statusStrip;
    private LinearLayout nav;
    private boolean wasBusy;
    private DayData data;
    private int loadToken;
    private boolean askedPermissions;
    /** Sync asked for Bluetooth access; Android no longer asks; its last answer was a refusal without explanation (issue #186). */
    private boolean syncAsked, permissionBlocked, quietDenial;
    /** {@code core/KeyState} of the saved ring key, read in onResume (issue #199); readable in demo mode. */
    private int keyState = app.openring.core.KeyState.READABLE;
    /** Screen test, demo mode only: the status line of a saved key this phone cannot read (issue #199); never the ring. */
    private boolean previewUnreadable;
    /** True until the person picks a date: the screen then follows the newest day with data. */
    private boolean autoDate = true;

    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_sleep, R.drawable.ic_readiness, R.drawable.ic_activity, R.drawable.ic_heart, R.drawable.ic_screen};
    private static final String[] LABELS = {"Today", "Sleep", "Ready", "Activity", "Body", "Screen"};
    private static final int[] COLORS = {Ui.TEXT, Ui.SLEEP, Ui.READY, Ui.ACTIVE, Ui.HEART, Ui.OXY};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Object last = getLastNonConfigurationInstance();
        // handed over by a turned phone: the same process, which already checked the demo
        boolean handedOver = last instanceof DemoRun;
        if (handedOver) demoRun = (DemoRun) last;
        demoRun.owner = this;
        if (b != null) {
            tab = b.getInt("tab", Pages.TODAY);
            String d = b.getString("date");
            if (d != null) date = LocalDate.parse(d);
            autoDate = b.getBoolean("auto", true);
            // a turn of the phone while Android's dialog is open keeps the Sync tap's answer (review of #279)
            syncAsked = b.getBoolean("syncAsked", false);
            permissionBlocked = b.getBoolean("permissionBlocked", false);
            quietDenial = b.getBoolean("quietDenial", false);
        } else {
            // only on a fresh start: a turned screen keeps its tab and date and does not start
            // the demo or open Settings again (issue #29)
            handleExtras(getIntent());
        }
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.CARD);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        // header: date navigation and settings
        LinearLayout header = Ui.hbox(this);
        int p = Ui.dp(this, 12);
        header.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 4));
        header.addView(iconButton(R.drawable.ic_chevron_left, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepDay(date.minusDays(1));
            }
        }));
        dateLabel = Ui.text(this, "", 18, Ui.TEXT, Ui.MEDIUM);
        dateLabel.setGravity(Gravity.CENTER);
        dateLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoDate = true;
                setDate(LocalDate.now());
            }
        });
        header.addView(dateLabel, Ui.weight(1));
        header.addView(iconButton(R.drawable.ic_chevron_right, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!date.isBefore(LocalDate.now())) return;
                stepDay(date.plusDays(1));
            }
        }));
        header.addView(iconButton(R.drawable.ic_sync, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sync();
            }
        }));
        header.addView(iconButton(R.drawable.ic_settings, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        root.addView(header);

        // status strip
        statusStrip = Ui.vbox(this);
        statusStrip.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 6));
        statusText = Ui.text(this, "", 12, Ui.TEXT_2, Ui.REGULAR);
        statusText.setGravity(Gravity.CENTER);
        statusStrip.addView(statusText);
        statusBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        statusBar.setProgressTintList(ColorStateList.valueOf(Ui.SLEEP));
        statusBar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        statusBar.setMax(1000);
        statusStrip.addView(statusBar, Ui.sized(this, -1, 4));
        root.addView(statusStrip);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = Ui.hbox(this);
        nav.setBackgroundColor(Ui.CARD);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        navPx = -1;
        for (int i = 0; i < ICONS.length; i++) nav.addView(navItem(i), Ui.weight(1));
        root.addView(nav);
        setContentView(root);
        if (demoRun.running) {
            // a preparation started before the phone was turned: show its progress again
            preparingDemo = true;
            showDemoDialog();
        } else if (app.openring.data.Demo.finishOnStart(Prefs.of(this).demo(), demoRun.running, handedOver)) {
            // Android may have ended the app while the demo was prepared, also when it is opened again
            // from the launcher (no saved screen then): finish it, progress shows only if work is left (issue #69)
            startDemo(false);
        }
    }

    /**
     * Demo preparation that outlives a turned screen (issue #29), handed to the new screen like
     * SetupActivity's restore. The task reports to whichever screen is attached now.
     */
    static final class DemoRun {
        MainActivity owner;
        /** Message shown now, null while nothing needs to be shown. */
        String step;
        boolean running;
        /** Why the demo was not turned on (issue #219), shown once instead of the general failure. */
        String refused;
        /** The step shown is a wait for the ring or an analysis: Back may stop it (review of #248). */
        boolean waiting;
        /** Back stopped the wait; nothing changes. */
        volatile boolean stop;
    }

    private DemoRun demoRun = new DemoRun();
    private android.app.ProgressDialog demoDialog;

    @Override
    public Object onRetainNonConfigurationInstance() {
        return demoRun;
    }

    @Override
    protected void onDestroy() {
        if (demoDialog != null) {
            demoDialog.dismiss();
            demoDialog = null;
        }
        if (demoRun.owner == this) demoRun.owner = null;
        super.onDestroy();
    }

    private boolean preparingDemo;
    /** Scroll position to return to while stepping through days, -1 when none is pending. */
    private int wantedY = -1;

    void stepDay(LocalDate d) {
        if (wantedY < 0) wantedY = scroll.getScrollY();
        autoDate = false;
        setDate(d);
    }

    /**
     * Extras used by the setup screen and by the emulator screenshot test, read on a fresh start
     * and for a new intent, not when a turned screen is recreated:
     * demo (boolean) starts demo mode, tab (int) opens a tab, analysis (boolean) unfolds
     * Further analysis on the Sleep page, period (int) picks its range, day (yyyy-mm-dd) a date,
     * finder (boolean) opens Find my ring, finder_turn (boolean) with it shows the demo's turn on the
     * spot instead of its walk, finder_start (boolean) the screen before any walk, finder_details
     * (boolean) opens the full last worn text and How it works (issue #191), finder_blink (boolean) the demo's
     * Make it blink (issue #192; the demo never connects), live (boolean, demo mode
     * only) opens Live with its made up readings and live_spo2 (boolean) with blood oxygen on (issue #185), left_out
     * (boolean, demo mode only) leaves out the day before yesterday 13:00 to 17:00 in the demo file (issue #254), unworn
     * (boolean, demo mode only) leaves out the day before yesterday 00:00 to 21:30 the same way, a day off the finger (issue #240),
     * key_unreadable (boolean, demo mode only) shows the status line of a saved key this phone cannot read (issue #199),
     * period_edit (boolean, demo mode only, with tab 4) opens the period tracker's day editor on Body (issue #252),
     * badges (boolean) opens the Badges page (issue #265),
     * period_ask (boolean, demo mode only) shows the question "Have your periods stopped?" on the period cards until answered,
     * period_rise (boolean, demo mode only) opens Body's night temperature line touched on the first high night of the newest
     * rise it marks (phase 2),
     * period_how (boolean, demo mode only) opens How it works on the Period card, in Your cycles and on the night
     * temperature card (issue #363),
     * drip (boolean, demo mode only) opens Import from drip with a made up drip file (the preview only, nothing is imported in
     * demo mode) and drip_correct (boolean) with it opens Correct on its first short period,
     * main_device (int, demo mode only: 0 ring, 1 watch, 2 phone) picks the main device as Settings does (issue #251),
     * devices (int, demo mode only: 1 only the watch, 2 only the phone) shows the tabs as for a person with only that (issue #373),
     * phone_ask (boolean, demo mode only) shows Activity's card that asks for Physical activity instead of the demo's phone steps
     * (issue #329; nothing is asked without a tap, and demo mode never asks).
     */
    void handleExtras(Intent i) {
        if (i == null) return;
        if (i.hasExtra("tab")) tab = i.getIntExtra("tab", tab);
        if (i.getBooleanExtra("analysis", false)) Pages.analysisOpen = true;
        if (i.hasExtra("period")) Pages.analysisPeriod = i.getIntExtra("period", 1);
        String day = i.getStringExtra("day");
        if (day != null) {
            try {
                date = LocalDate.parse(day);
                autoDate = false;
            } catch (RuntimeException ignored) {
            }
        }
        if (i.getBooleanExtra("demo", false)) startDemo(true);
        // screen test: open Settings at a card ("stages")
        String section = i.getStringExtra("settings");
        if (section != null) startActivity(new Intent(this, SettingsActivity.class).putExtra("section", section));
        // screen test: open Live with its made up readings (issue #185); only in demo mode, so no other app can start a
        // ring session this way
        if (i.getBooleanExtra("live", false) && Prefs.of(this).demo()) live(i.getBooleanExtra("live_spo2", false));
        // screen test: a left out time in the demo (issue #254); Analyzer.leaveOutDemo writes only into the demo file
        if (i.getBooleanExtra("left_out", false) && Prefs.of(this).demo()) leftOutDemo(2, 13, 0, 17, 0);
        // screen test: the day before yesterday off the finger (issue #240), as a left out time 00:00 to 21:30 in the demo
        // file, so yesterday's readiness leaves Previous day activity out. The demo's day ends at 22:18 (Demo dayEnd), so
        // under an hour of ring data stays (about 57 minutes); a later demo day must keep that under Scores.WORN_DAY_MIN
        if (i.getBooleanExtra("unworn", false) && Prefs.of(this).demo()) leftOutDemo(2, 0, 0, 21, 30);
        // screen test: the status line of a saved key this phone cannot read (issue #199), demo mode only
        previewUnreadable = i.getBooleanExtra("key_unreadable", false) && Prefs.of(this).demo();
        // screen test: the period tracker's day editor on Body (issue #252), demo mode only; nothing is saved without a tap
        if (i.getBooleanExtra("period_edit", false) && Prefs.of(this).demo()) PeriodCard.editPending = true;
        // screen test: the gentle question of phase 1a2 on the period cards, demo mode only; nothing is saved without a tap
        PeriodCard.askPending = i.getBooleanExtra("period_ask", false) && Prefs.of(this).demo();
        // screen test: the marked temperature rise of phase 2, read by touch on its first high night, demo mode only
        PeriodAnalysis.risePending = i.getBooleanExtra("period_rise", false) && Prefs.of(this).demo();
        // screen test: How it works opened on the Period card, in Your cycles and on the night temperature card (owner's feedback
        // of 3 November, issue #363), demo mode only
        if (i.getBooleanExtra("period_how", false) && Prefs.of(this).demo()) {
            PeriodCard.howOpen[0] = PeriodAnalysis.howOpen[0] = PeriodAnalysis.tempHowOpen[0] = true;
        }
        // screen test: the drip import's preview of a made up file (issue #252, phase 1b), demo mode only; demo never imports
        if (i.getBooleanExtra("drip", false) && Prefs.of(this).demo()) {
            startActivity(new Intent(this, DripImportActivity.class).putExtra("demo_file", true)
                    .putExtra("demo_correct", i.getBooleanExtra("drip_correct", false)));
        }
        // screen test: the Watch check with its made up scan, and with watch_report its made up report (issue #251); only in
        // demo mode, which never touches Bluetooth
        if (i.getBooleanExtra("watch_data", false) && Prefs.of(this).demo()) startActivity(new Intent(this, WatchPageActivity.class));
        // screen test: the main device (issue #251), demo mode only; the same choice as the chips in Settings
        if (i.hasExtra("main_device") && Prefs.of(this).demo()) Prefs.of(this).setMainDevice(i.getIntExtra("main_device", 0));
        // screen test: the demo shown as with only the watch (1) or only the phone (2), issue #373; demo mode only, nothing stored
        DayData.preview = Prefs.of(this).demo() ? i.getIntExtra("devices", app.openring.core.Start.PREVIEW_NONE) : app.openring.core.Start.PREVIEW_NONE;
        // screen test: Activity's card that asks for Physical activity (issue #329), demo mode only; demo mode never asks
        PhoneViews.previewAsk = i.getBooleanExtra("phone_ask", false) && Prefs.of(this).demo();
        // screen test: the watch setup (issue #251), demo mode only: watch_setup_part 1 scrolls to the alarm, watch_setup_hands
        // shows the hand calibration's controls
        if (i.getBooleanExtra("watch_setup", false) && Prefs.of(this).demo()) {
            startActivity(new Intent(this, WatchSetupActivity.class).putExtra("part", i.getIntExtra("watch_setup_part", 0))
                    .putExtra("demo_hands", i.getBooleanExtra("watch_setup_hands", false)));
        }
        if (i.getBooleanExtra("watch", false) && Prefs.of(this).demo()) {
            startActivity(new Intent(this, WatchActivity.class).putExtra("demo_report", i.getBooleanExtra("watch_report", false)));
        }
        // screen test: the Badges page (issue #265); it only reads, so it needs no demo mode
        if (i.getBooleanExtra("badges", false)) startActivity(new Intent(this, BadgesActivity.class));
        // screen test: open Find my ring (issue #74)
        if (i.getBooleanExtra("finder", false)) {
            startActivity(new Intent(this, FinderActivity.class).putExtra("demo_turn", i.getBooleanExtra("finder_turn", false))
                    .putExtra("demo_start", i.getBooleanExtra("finder_start", false))
                    .putExtra("demo_details", i.getBooleanExtra("finder_details", false))
                    .putExtra("demo_blink", i.getBooleanExtra("finder_blink", false)));
        }
    }

    /**
     * The demo's example of a left out time (screen test, issue #254), {@code daysBack} days before today from the clock
     * time {@code h0:m0} to {@code h1:m1}, then the day is loaded again.
     */
    void leftOutDemo(final int daysBack, final int h0, final int m0, final int h1, final int m1) {
        final android.content.Context ctx = getApplicationContext();
        new android.os.AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    java.time.ZoneId z = java.time.ZoneId.systemDefault();
                    LocalDate d = LocalDate.now(z).minusDays(daysBack);
                    return app.openring.data.Analyzer.leaveOutDemo(ctx, d.atTime(h0, m0).atZone(z).toEpochSecond(), d.atTime(h1, m1).atZone(z).toEpochSecond());
                } catch (Throwable e) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (ok && !isFinishing() && !isDestroyed() && scroll != null && !preparingDemo) load();
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleExtras(i);
        // while the demo is prepared the database is half written; the task loads when it is done
        if (scroll != null && !preparingDemo) {
            scroll.scrollTo(0, 0);
            load();
        }
    }

    /**
     * Prepares the demo in the background. {@code fresh}: the person asked for it (build it unless
     * that was done today, with progress shown at once); otherwise only finish a demo that was cut
     * off, and show progress only if there is work to do.
     */
    void startDemo(boolean fresh) {
        preparingDemo = true;
        if (demoRun.running) return; // one preparation at a time (issue #29)
        demoRun.running = true;
        demoRun.step = fresh ? "Preparing demo data" : null;
        demoRun.waiting = false;
        demoRun.stop = false;
        showDemoDialog();
        new DemoTask(getApplicationContext(), demoRun, fresh).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Holds only the application and the hand over state, never a screen, so a turned screen can go. */
    private static final class DemoTask extends AsyncTask<Void, String, Integer> {
        static final int FAILED = -1, KEPT = 0, BUILT = 1;
        private final android.content.Context ctx;
        private final DemoRun run;
        private final boolean fresh;

        DemoTask(android.content.Context ctx, DemoRun run, boolean fresh) {
            this.ctx = ctx;
            this.run = run;
            this.fresh = fresh;
        }

        @Override
        protected Integer doInBackground(Void... v) {
            // Back stops a wait for the ring (review of #248); a build that has begun always ends
            final class Steps implements app.openring.data.Demo.Progress, app.openring.core.RingGate.Stop {
                @Override
                public void step(String what) {
                    publishProgress(what);
                }

                @Override
                public boolean stopped() {
                    return run.stop;
                }
            }
            app.openring.data.Demo.Progress p = new Steps();
            try {
                boolean built = fresh ? app.openring.data.Demo.enter(ctx, p) : app.openring.data.Demo.complete(ctx, p);
                // the emulator test looks for "demo: finish check, built now" after ending the app (issue #69)
                android.util.Log.i("OpenRing", "demo: " + (fresh ? "start" : "finish check") + (built ? ", built now" : ", nothing to do"));
                return built ? BUILT : KEPT;
            } catch (app.openring.data.Demo.NotNow e) {
                // a ring task or an analysis still ran, or Back stopped the wait (issue #219): nothing changed, demo mode stays off
                android.util.Log.i("OpenRing", "demo: not turned on, reason " + e.why);
                run.refused = app.openring.core.RingGate.demoRefused(e.why);
                return FAILED;
            } catch (RuntimeException e) {
                android.util.Log.w("OpenRing", "demo: preparing the demo data failed", e);
                // back to the person's own data (or Setup), so the demo can be started again
                try {
                    app.openring.data.Demo.leave(ctx);
                } catch (RuntimeException ignored) {
                }
                return FAILED;
            }
        }

        @Override
        protected void onProgressUpdate(String... s) {
            run.step = s[0];
            run.waiting = s[0].equals(app.openring.core.RingGate.WAIT_SYNC) || s[0].equals(app.openring.core.RingGate.WAIT_RING)
                    || s[0].equals(app.openring.core.RingGate.WAIT_ANALYSIS);
            if (run.owner != null) run.owner.showDemoDialog();
        }

        @Override
        protected void onPostExecute(Integer r) {
            run.running = false;
            run.step = null;
            MainActivity a = run.owner;
            if (a != null && !a.isDestroyed()) a.demoReady(r == FAILED, fresh || r == BUILT);
        }
    }

    void showDemoDialog() {
        if (demoRun.step == null || isDestroyed()) return;
        if (demoDialog == null) {
            demoDialog = new android.app.ProgressDialog(this);
            demoDialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(android.content.DialogInterface d) {
                    // the wait ends at its next look (a tenth of a second), nothing changed; demoReady says so
                    demoRun.stop = true;
                    demoDialog = null;
                }
            });
        }
        demoDialog.setCancelable(demoRun.waiting);
        demoDialog.setCanceledOnTouchOutside(false);
        demoDialog.setMessage(demoRun.step);
        if (!demoDialog.isShowing()) demoDialog.show();
    }

    /**
     * The preparation ended. {@code newest}: show the newest day (the demo was just asked for or
     * built); otherwise keep the date the screen had before Android ended the app.
     */
    void demoReady(boolean failed, boolean newest) {
        preparingDemo = false;
        if (demoDialog != null) {
            demoDialog.dismiss();
            demoDialog = null;
        }
        if (failed) {
            String why = demoRun.refused != null ? demoRun.refused : "Demo data could not be prepared. Please try again.";
            demoRun.refused = null;
            android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show();
            Prefs prefs = Prefs.of(this);
            if (app.openring.core.KeyState.needsSetup(prefs.setupDone(), prefs.ringState())) {
                startActivity(new Intent(this, SetupActivity.class));
                return;
            }
        }
        if (newest) {
            autoDate = true;
            date = LocalDate.now();
        }
        // the demo is on now: its status line (the key_unreadable screen test, review of #281)
        readKeyState(Prefs.of(this));
        updateStatus();
        load();
    }

    /** {@link #keyState}: one decrypt; in demo mode readable, or unreadable for the key_unreadable screen test (issue #199). */
    void readKeyState(Prefs prefs) {
        keyState = prefs.demo() ? (previewUnreadable ? app.openring.core.KeyState.UNREADABLE : app.openring.core.KeyState.READABLE) : prefs.keyState();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("date", date.toString());
        out.putBoolean("auto", autoDate);
        out.putBoolean("syncAsked", syncAsked);
        out.putBoolean("permissionBlocked", permissionBlocked);
        out.putBoolean("quietDenial", quietDenial);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs prefs = Prefs.of(this);
        // read once per resume, before a demo that is still prepared returns (review of #281)
        readKeyState(prefs);
        if (preparingDemo) return;
        // a pairing whose key page the person has not left yet opens there (issue #218); a saved key this phone cannot
        // read keeps this screen, the status line says so (issue #199)
        // nothing set up yet: Getting started (issue #373); never with a watch added, so a watch only phone stays here
        if (app.openring.core.Start.opensSetup(prefs.demo(), prefs.setupDone(), keyState, prefs.pairUnseen() != null, prefs.watchAddress() != null)) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        // a grant clears a remembered refusal, so a permission Android takes back later asks afresh (issue #140)
        if (!prefs.demo()) Permissions.remember(this);
        // allowed in the app's settings after Sync found it blocked: that sync starts now, also right after a recent one (#186)
        boolean allowedNow = !prefs.demo() && permissionBlocked && Permissions.granted(this);
        if (!prefs.demo() && Permissions.granted(this)) permissionBlocked = quietDenial = false;
        // without a ring by choice (issue #329) nothing needs Bluetooth: it is asked for when a ring is added
        if (!prefs.demo() && keyState != app.openring.core.KeyState.NO_RING && !Permissions.granted(this) && !askedPermissions) {
            askedPermissions = true;
            Permissions.request(this);
        }
        SyncJob.schedule(this, keyState);
        // steps from this phone (issue #329): its own hourly job, and a reading now (into the real history, also in demo mode);
        // a new reading loads the day again
        app.openring.sync.PhoneJob.schedule(this);
        app.openring.sync.PhoneReader.readLater(this, "app opened", app.openring.core.PhoneSteps.OPEN_SKIP_S, new app.openring.sync.PhoneReader.Done() {
            @Override
            public void done(final int result) {
                if (result != app.openring.data.PhoneStore.STORED) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed() && !Prefs.of(MainActivity.this).demo()) load();
                    }
                });
            }
        });
        // the automatic backups' daily job, and the check of a copy made before an update (issue #242)
        app.openring.sync.BackupJob.schedule(this);
        // the reminder before the expected period (issue #252): planned again from the person's own log, in the background
        app.openring.sync.PeriodReminder.planLater(this);
        RingManager.get(this).addListener(this);
        wasBusy = RingManager.get(this).busy();
        updateStatus();
        load();
        boolean recent = app.openring.core.BackgroundJob.recent(System.currentTimeMillis() / 1000, prefs.lastSync(), prefs.lastAttempt());
        if (!prefs.demo() && app.openring.core.KeyState.syncs(keyState) && (!recent || allowedNow) && !RingManager.get(this).busy() && Permissions.granted(this))
            SyncService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RingManager.get(this).removeListener(this);
    }

    void setDate(LocalDate d) {
        date = d;
        load();
    }

    void load() {
        dateLabel.setText(dateTitle(date));
        final int token = ++loadToken;
        final LocalDate d = date;
        new AsyncTask<Void, Void, DayData>() {
            String latest, problem;

            @Override
            protected DayData doInBackground(Void... v) {
                // while an analysis runs (a re analysis of the whole history takes minutes) the screen shows the stored
                // nights at once; the zone note, the screen log and their analysis wait for the next load (review of #243)
                boolean ok = true;
                try {
                    // the phone's time zone at every start (issue #22); a new zone analyses the nights since
                    // the last note again, so they count in the zone you woke up in. Demo mode notes nothing.
                    app.openring.data.Analyzer.beforeLoad(MainActivity.this);
                } catch (Throwable e) {
                    // a failed analysis rolled back and stored its error, also out of memory (issue #230); a failed
                    // zone note stored nothing, so both are noted for the data check (issue #235)
                    ok = DayData.failed(MainActivity.this, d, "time zone note or its analysis", e);
                }
                try {
                    // screen time is read on this phone (issue #13); the very first read also updates the
                    // nights of the week it brings in
                    boolean first = !app.openring.data.ScreenDays.any(app.openring.data.Db.get(MainActivity.this));
                    if (!app.openring.data.Analyzer.running() && app.openring.data.ScreenUse.collect(MainActivity.this) > 0 && first) app.openring.data.Analyzer.run(MainActivity.this, null);
                } catch (Throwable e) {
                    // as above (issues #230, #235)
                    ok = DayData.failed(MainActivity.this, d, "screen log read or its analysis", e);
                }
                try {
                    // the ring's newest day, or the phone's newest steps (issue #329): a phone reading after midnight moves here
                    latest = app.openring.data.Db.get(MainActivity.this).latestDayWithPhone();
                    DayData r = DayData.load(MainActivity.this, d);
                    if (ok && r.ok) DayData.allRead(MainActivity.this, d);
                    return r;
                } catch (RuntimeException e) {
                    // a damaged file is kept since issue #255, so it fails here on every start: one line on the
                    // screen instead of a crash, and Settings stays reachable to go back to a copy (review of #266)
                    RingManager.get(MainActivity.this).log("history could not be read: " + e);
                    problem = app.openring.core.Backups.unreadable(e.toString());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(DayData result) {
                // a screen gone with a turn of the phone is not finished: its load must not render or start another
                if (token != loadToken || isFinishing() || isDestroyed()) return;
                if (result == null) {
                    data = null;
                    showProblem(problem);
                    return;
                }
                // until the person picks a date, follow the newest day with data: today if it has
                // any, else the last one. A morning sync then moves the screen to last night.
                if (autoDate && latest != null && !latest.equals(d.toString())) {
                    LocalDate l = LocalDate.parse(latest);
                    boolean newer = l.isAfter(d) && !l.isAfter(LocalDate.now());
                    if (newer || (result.empty() && l.isBefore(d))) {
                        setDate(l);
                        return;
                    }
                }
                data = result;
                render();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The one line shown instead of the day when the history cannot be read (review of #266). */
    void showProblem(String text) {
        LinearLayout box = Ui.vbox(this);
        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.rounded(Ui.alpha(Ui.WARN, 0.14f), Ui.dp(this, 14)));
        card.addView(Ui.text(this, text, 15, Ui.TEXT, Ui.MEDIUM));
        box.addView(card);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 4), p, p);
        scroll.removeAllViews();
        scroll.addView(box);
    }

    static String dateTitle(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
    }

    void render() {
        if (data == null) return;
        View content;
        switch (tab) {
            case Pages.SLEEP:
                content = Pages.sleep(this, data, this);
                break;
            case Pages.READINESS:
                content = Pages.readiness(this, data);
                break;
            case Pages.ACTIVITY:
                content = Pages.activity(this, data);
                break;
            case Pages.BODY:
                content = Pages.body(this, data, this);
                break;
            case Pages.SCREEN:
                content = Pages.screen(this, data, this);
                break;
            default:
                content = Pages.today(this, data, this, syncLine());
        }
        // no copy could be made before this update (issue #242): said once on Today, until OK
        final Prefs np = Prefs.of(this);
        if (tab == Pages.TODAY && !np.demo() && np.beforeUpdateUnseen() && content instanceof LinearLayout) {
            final LinearLayout notice = Ui.card(this);
            notice.setBackground(Ui.rounded(Ui.alpha(Ui.WARN, 0.14f), Ui.dp(this, 14)));
            notice.addView(Ui.text(this, app.openring.core.Backups.SPACE.equals(np.beforeUpdateNote()) ? app.openring.core.Backups.NOTICE_SPACE
                    : app.openring.core.Backups.NOTICE_OTHER, 14, Ui.TEXT, Ui.MEDIUM));
            TextView ok = Ui.smallButton(this, "OK", Ui.TEXT_2);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    np.clearBeforeUpdateUnseen();
                    notice.setVisibility(View.GONE);
                }
            });
            LinearLayout.LayoutParams olp = Ui.matchWrap();
            olp.setMargins(0, Ui.dp(this, 8), 0, 0);
            notice.addView(ok, olp);
            ((LinearLayout) content).addView(notice, 0);
        }
        if (Prefs.of(this).demo() && content instanceof LinearLayout) {
            LinearLayout banner = Ui.card(this);
            banner.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.14f), Ui.dp(this, 14)));
            banner.addView(Ui.text(this, "Demo: a made up person's data", 14, Ui.TEXT, Ui.MEDIUM));
            String how = DayData.preview == app.openring.core.Start.PREVIEW_WATCH ? "Shown as with only a watch. Leave the demo in Settings."
                    : DayData.preview == app.openring.core.Start.PREVIEW_PHONE ? "Shown as with only this phone. Leave the demo in Settings."
                    : "Everything works like with a real ring. Leave the demo in Settings.";
            banner.addView(Ui.text(this, how, 12, Ui.TEXT_2, Ui.REGULAR));
            ((LinearLayout) content).addView(banner, 0);
        }
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 4), p, p);
        // keep the reading position when stepping through days (issue #6); the new content has no
        // height yet, so scroll after layout. A short day (no data) does not lose the position.
        final int y = wantedY >= 0 ? wantedY : scroll.getScrollY();
        scroll.removeAllViews();
        scroll.addView(content);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
                if (scroll.getScrollY() >= y) wantedY = -1;
            }
        });
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(i);
            int col = i == tab ? COLORS[i] : Ui.TEXT_3;
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) item.getChildAt(1)).setTextColor(col);
        }
    }

    String syncLine() {
        Prefs p = Prefs.of(this);
        StringBuilder sb = new StringBuilder();
        // without a ring (issue #329) there is no sync to name
        if (keyState == app.openring.core.KeyState.NO_RING && !p.demo() && p.watchAddress() != null) {
            // only a watch (issue #373): its own last sync
            long ok = p.watchLastOk();
            return (ok > 0 ? "Watch synced " + DateUtils.getRelativeTimeSpanString(ok * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    : "Watch not synced yet") + "\nAll analysis runs on this phone";
        }
        if (keyState == app.openring.core.KeyState.NO_RING && !p.demo() && p.lastSync() == 0) return "All analysis runs on this phone";
        if (p.lastSync() > 0) {
            sb.append("Synced ").append(DateUtils.getRelativeTimeSpanString(p.lastSync() * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            sb.append("Not synced yet");
        }
        if (p.battery() >= 0) sb.append("  ·  Ring battery ").append(p.battery()).append('%');
        sb.append("\nAll analysis runs on this phone");
        return sb.toString();
    }

    /** The tab label size worked out by {@link #navTextPx} for the first tab, -1 before. */
    float navPx = -1;

    View navItem(final int i) {
        LinearLayout item = Ui.vbox(this);
        item.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(ICONS[i]);
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        // centred under its icon in the full cell, one line, one size that lets the longest label fit (issue #119)
        TextView t = Ui.text(this, LABELS[i], 11, Ui.TEXT_3, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (navPx < 0) navPx = navTextPx(t.getPaint()); // measured once for all six tabs
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, navPx);
        item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(i);
            }
        });
        return item;
    }

    /**
     * The tab label size in pixels: 11 sp, or smaller (not below 8 sp) when the widest label would not fit a sixth of the
     * screen with 2 dp on each side (large font settings on a narrow phone; "Activity" needs about 45 of 60 dp at 11 sp).
     */
    float navTextPx(android.graphics.Paint paint) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float wanted = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11, dm);
        float min = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 8, dm);
        android.graphics.Paint p = new android.graphics.Paint(paint);
        p.setTextSize(wanted);
        float widest = 0;
        for (String l : LABELS) widest = Math.max(widest, p.measureText(l));
        float room = dm.widthPixels / (float) LABELS.length - Ui.dp(this, 4);
        return app.openring.core.Texts.fitSize(wanted, widest, room, min);
    }

    View iconButton(int res, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        int p = Ui.dp(this, 9);
        v.setPadding(p, p, p, p);
        v.setOnClickListener(l);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        return v;
    }

    /** The status line's tap while it says the saved key cannot be read: Setup (issue #199); never in demo mode. */
    private final View.OnClickListener enterKey = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!Prefs.of(MainActivity.this).demo() && !RingManager.get(MainActivity.this).busy()) enterKeyAgain();
        }
    };

    void updateStatus() {
        RingManager m = RingManager.get(this);
        // only the line about an unreadable key is a button (TalkBack announces it as one, review of #281)
        boolean tap = !m.busy() && keyState == app.openring.core.KeyState.UNREADABLE;
        statusStrip.setOnClickListener(tap ? enterKey : null);
        statusStrip.setClickable(tap);
        boolean busy = m.busy();
        String err = Prefs.of(this).lastSyncError();
        if (busy) {
            statusStrip.setVisibility(View.VISIBLE);
            statusText.setText(m.status());
            statusText.setTextColor(Ui.TEXT_2);
            double pr = m.progress();
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setIndeterminate(pr < 0);
            if (pr >= 0) statusBar.setProgress((int) (pr * 1000));
        } else if (keyState == app.openring.core.KeyState.UNREADABLE) {
            statusStrip.setVisibility(View.VISIBLE);
            statusText.setText(app.openring.core.KeyState.STATUS);
            statusText.setTextColor(Ui.WARN);
            statusBar.setVisibility(View.GONE);
        } else if (err != null) {
            statusStrip.setVisibility(View.VISIBLE);
            statusText.setText(app.openring.core.SyncError.plain(err)); // raw text stays in the data check (issue #47)
            statusText.setTextColor(Ui.WARN);
            statusBar.setVisibility(View.GONE);
        } else {
            statusStrip.setVisibility(View.GONE);
        }
        if (wasBusy && !busy) load();
        wasBusy = busy;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Physical activity, asked on Activity (issue #329): the job follows the answer, and a first reading comes at once
        if (code == PhoneViews.REQUEST) {
            app.openring.sync.PhoneJob.schedule(this);
            if (app.openring.sync.PhoneReader.counting(this)) {
                app.openring.sync.PhoneReader.readLater(this, "allowed", 0, new app.openring.sync.PhoneReader.Done() {
                    @Override
                    public void done(int result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) load();
                            }
                        });
                    }
                });
            }
            load();
            return;
        }
        if (Permissions.granted(this) && app.openring.core.KeyState.syncs(keyState) && !RingManager.get(this).busy() && Prefs.of(this).lastSync() == 0)
            SyncService.start(this);
        if (Prefs.of(this).demo()) return;
        // the same rule as Find my ring: a refusal after an earlier one, without explanation, means Android no longer asks (#140, #186)
        app.openring.core.PermissionAsk.Answer a = Permissions.answer(this, perms, results, quietDenial);
        boolean fromSync = syncAsked;
        syncAsked = false;
        if (!fromSync) return;
        quietDenial = a.quiet;
        permissionBlocked = a.blocked;
        if (a.blocked) askSettings();
    }

    /** Setup at "Connect your ring", for a saved key this phone cannot read (issue #199); the saved key stays until a new one works. */
    void enterKeyAgain() {
        startActivity(new Intent(this, SetupActivity.class));
    }

    /** Android no longer shows its dialog: the way to the app's settings, where Bluetooth access is switched on (issue #186). */
    void askSettings() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Bluetooth access")
                .setMessage("Sync needs Bluetooth access to reach your ring. " + app.openring.core.PermissionAsk.settingsText(android.os.Build.VERSION.SDK_INT))
                .setPositiveButton(app.openring.core.PermissionAsk.SETTINGS_BUTTON, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Permissions.openSettings(MainActivity.this);
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    public void onRingStatus() {
        updateStatus();
    }

    // ---- Pages.Nav ----
    @Override
    public void go(int t) {
        tab = t;
        wantedY = -1;
        scroll.scrollTo(0, 0);
        render();
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void showDay(LocalDate day) {
        if (day.isAfter(LocalDate.now())) day = LocalDate.now();
        stepDay(day);
    }

    @Override
    public void live(boolean spo2) {
        // without a ring by choice (issue #329): the way to add one instead
        if (PhoneViews.noRing(this)) {
            PhoneViews.addRingDialog(this);
            return;
        }
        // in demo mode Live shows made up readings and never uses the ring (issue #185)
        startActivity(new Intent(this, LiveActivity.class).putExtra("spo2", spo2));
    }

    @Override
    public void sync() {
        if (Prefs.of(this).demo()) {
            android.widget.Toast.makeText(this, "This is demo data. Leave the demo in Settings to sync.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // without a ring (issue #329): the watch syncs when there is one (issue #373), else the way to add a ring
        if (keyState == app.openring.core.KeyState.NO_RING) {
            if (Prefs.of(this).watchAddress() != null) syncWatch();
            else PhoneViews.addRingDialog(this);
            return;
        }
        if (keyState == app.openring.core.KeyState.UNREADABLE) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(app.openring.core.KeyState.SYNC_TITLE)
                    .setMessage(app.openring.core.KeyState.SYNC_TEXT)
                    .setPositiveButton(app.openring.core.KeyState.BUTTON, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            enterKeyAgain();
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        if (!Permissions.granted(this)) {
            if (permissionBlocked) {
                askSettings();
                return;
            }
            syncAsked = true;
            Permissions.request(this);
            return;
        }
        if (!RingManager.get(this).busy()) SyncService.start(this);
    }

    /** True while the top bar's Sync runs a watch sync (issue #373). */
    private static volatile boolean watchSyncing;

    /** Sync with only a watch (issue #373): the same as Settings, Watch, Sync the watch now; the day loads again after it. */
    void syncWatch() {
        if (!Permissions.granted(this)) {
            Permissions.request(this);
            return;
        }
        if (watchSyncing || app.openring.ble.WatchSync.running()) {
            android.widget.Toast.makeText(this, app.openring.ble.WatchSync.ALREADY, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        watchSyncing = true;
        android.widget.Toast.makeText(this, "Syncing the watch, this takes up to two minutes.", android.widget.Toast.LENGTH_LONG).show();
        final android.content.Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = app.openring.ble.WatchSync.run(appCtx);
                watchSyncing = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(appCtx, r == null ? "Watch synced" : r, android.widget.Toast.LENGTH_LONG).show();
                        if (!isFinishing() && !isDestroyed()) {
                            updateStatus();
                            load();
                        }
                    }
                });
            }
        }, "watch sync").start();
    }
}
