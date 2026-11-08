package app.openring.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.Locale;

import app.openring.ble.RingManager;
import app.openring.core.Drafts;
import app.openring.core.Hex;
import app.openring.core.InputLimit;
import app.openring.core.Protocol;
import app.openring.core.Backups;
import app.openring.core.Texts;
import org.json.JSONObject;

import app.openring.core.analysis.StageFilter;
import app.openring.data.Analyzer;
import app.openring.data.AutoBackup;
import app.openring.data.Backup;
import app.openring.data.BeforeUpdate;
import app.openring.data.Db;
import app.openring.data.Exporter;
import app.openring.data.Json;
import app.openring.data.Prefs;
import app.openring.data.ScreenDays;
import app.openring.data.ScreenUse;
import app.openring.sync.BackupJob;
import app.openring.sync.SyncJob;
import app.openring.sync.SyncService;

/** Ring, profile, key, data and advanced settings. */
public final class SettingsActivity extends Activity implements RingManager.Listener {
    private static final int EXPORT_CSV = 11, EXPORT_DB = 12, BACKUP_KEY = 13, BACKUP_FULL = 14, PICK_FOLDER = 15;
    private ScrollView scroll;
    private LinearLayout chips;
    private LinearLayout root;
    private TextView status;
    private final int[] featureModes = {-1, -1, -1};
    /** A measurement switch or read started on this page is running (code review of #149). */
    private boolean ringPending;
    /**
     * The Settings screen shown now (issue #59): a ring action's result goes there, also when the
     * phone was turned meanwhile and Android built a new screen. Attached in onResume, detached
     * only when the screen closes, so a turn hands over directly to the new one.
     */
    static final app.openring.core.OneRun<SettingsActivity> SHOWN = new app.openring.core.OneRun<SettingsActivity>();
    /** Detail level picked in the Sleep stage detail card, saved only with its button. */
    private int detailChoice = -1;
    private LinearLayout stagesCard;
    /** Where the Ring card's counts go once read (issue #198); a newer page has a newer one. */
    private LinearLayout countsBox;
    /**
     * The Ring and Watch blocks (owner's wish of 3 November): closed every time Settings opens, unless it is opened at one
     * of their cards; open or closed stays while this screen is rebuilt or the phone turns, nothing is stored.
     */
    private Block ringBlock, watchBlock, phoneBlock;
    private boolean ringOpen, watchOpen, phoneOpen;
    /** The Phone block's section (issue #329): steps from this phone, the last reading, the main device when it lives here. */
    private LinearLayout phoneCard;
    /**
     * The long jobs' lines and buttons (issue #222): saving a backup or export file (one at a time with the automatic
     * backup), and Re analyse all (one at a time with Data check's Repair). Null on the demo page.
     */
    private TextView saveNote, exportNote, reanalyseNote;
    private LinearLayout saveRow, exportRow;
    private View reanalyseButton;
    /** Whether this screen already jumped to its {@code section} card, or came back from a turn with its own scroll. */
    private boolean sectionDone;
    /** The Profile card, where the watch setup leads when the watch needs a sex or a profile (review of #332). */
    private LinearLayout profCard;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DELETE.attach(this);
        GO_BACK.attach(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView s = new ScrollView(this);
        scroll = s;
        s.setBackgroundColor(Ui.BG);
        root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 8), p, p);
        s.addView(root);
        setContentView(s);
        String section = getIntent().getStringExtra("section");
        ringOpen = app.openring.core.SettingsBlocks.startsOpen(app.openring.core.SettingsBlocks.RING, section);
        watchOpen = app.openring.core.SettingsBlocks.startsOpen(app.openring.core.SettingsBlocks.WATCH, section);
        phoneOpen = app.openring.core.SettingsBlocks.startsOpen(app.openring.core.SettingsBlocks.PHONE, section);
        sectionDone = !app.openring.core.SettingsBlocks.jumpOnBuild(section, b != null, false);
        if (b != null) { // the phone was turned: typed numbers and choices come back (issue #133)
            ringOpen = b.getBoolean("ring_open", ringOpen);
            watchOpen = b.getBoolean("watch_open", watchOpen);
            phoneOpen = b.getBoolean("phone_open", phoneOpen);
            drafts = Drafts.fromArray(b.getStringArray("drafts"));
            sexChoice = b.getInt("sex", -1);
            detailChoice = b.getInt("detail", -1);
            leftFrom = b.getLong("left_from", -1);
            leftTo = b.getLong("left_to", -1);
            int[] modes = b.getIntArray("modes");
            // what the ring said holds only for a turn of the phone, not after Android ended the app hours ago
            if (modes != null && modes.length == featureModes.length && Drafts.recent(b.getLong("modes_at", 0), System.currentTimeMillis()))
                System.arraycopy(modes, 0, featureModes, 0, modes.length);
        }
        build();
        if (b != null) { // back where the person was (turning there and back gives the same layout)
            final int y = b.getInt("scroll", 0);
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, y);
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        keepTyped();
        out.putStringArray("drafts", drafts.toArray());
        out.putInt("sex", sexChoice);
        out.putInt("detail", detailChoice);
        out.putBoolean("ring_open", ringOpen);
        out.putBoolean("watch_open", watchOpen);
        out.putBoolean("phone_open", phoneOpen);
        out.putLong("left_from", leftFrom);
        out.putLong("left_to", leftTo);
        // a switch or read still running would leave the saved states wrong: the new page shows "?" then
        if (!ringPending) {
            out.putIntArray("modes", featureModes);
            out.putLong("modes_at", System.currentTimeMillis());
        }
        out.putInt("scroll", scroll.getScrollY());
    }

    /** Typed but unsaved numbers, kept while the page is rebuilt or the phone turns (issue #133). */
    private Drafts drafts = new Drafts();
    private EditText ageIn, weightIn, heightIn, kcalIn, goalIn;

    /** Copies what the inputs show now into {@link #drafts}, before they are replaced. */
    void keepTyped() {
        if (ageIn != null) drafts.keep("age", ageIn.getText());
        if (weightIn != null) drafts.keep("weight", weightIn.getText());
        if (heightIn != null) drafts.keep("height", heightIn.getText());
        if (kcalIn != null) drafts.keep("kcal", kcalIn.getText());
        if (goalIn != null) drafts.keep("screen_goal", goalIn.getText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs p = Prefs.of(this);
        // the ring was factory reset while the app was in the background, or a turned screen stayed
        // behind: Settings needs a ring with a key, the main screen opens Setup (issue #59)
        if (!p.demo() && app.openring.core.KeyState.needsSetup(p.setupDone(), p.ringState())) {
            toSetup(getApplicationContext());
            finish();
            return;
        }
        SHOWN.attach(this);
        RingManager.get(this).addListener(this);
        // back from Android's usage access page: show the new state, and read the screen log the first time
        boolean allowedNow = ScreenUse.allowed(this);
        if (screenCard != null && allowedNow != screenAllowedShown) fillScreenCard(p); // a goal being typed stays (issue #133)
        if (gamesCard != null) app.openring.ui.games.GamesActivity.fill(this, gamesCard); // new best scores
        if (backupsCard != null) fillBackupsCard(p); // a copy made or checked meanwhile
        showJobState(); // a backup, export or re analysis that ended while this screen was covered (issue #222)
        if (watchCard != null) fillWatchCard(); // a watch added on the Watch check, or a sync that ended meanwhile (issue #251)
        // back from the app's settings with Physical activity allowed or taken back (issue #329)
        if (phoneCard != null) {
            app.openring.sync.PhoneJob.schedule(this);
            fillPhoneCard();
        }
        // back from Android's notification settings: the card shows whether the reminder can be shown now
        if (periodCard != null && notifyShown != notificationsOn()) fillPeriodCard();
        // back from Import from drip: its Undo line (issue #252, phase 1b)
        else if (periodCard != null && DripImportActivity.changed) fillPeriodCard();
        DripImportActivity.changed = false;
        if (!screenRead && !p.demo() && ScreenUse.supported() && allowedNow && !screenDaysKnown()) {
            screenRead = true;
            updateNights("Reading screen time");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        RingManager.get(this).removeListener(this);
        if (isFinishing()) SHOWN.detach(this); // a turn keeps it until the new screen attaches
    }

    @Override
    public void onRingStatus() {
        if (status != null) status.setText(RingManager.get(this).status());
        if (ringBlock != null) ringBlock.status.setText(ringStatus(Prefs.of(this))); // a sync that ended changes its last sync
    }

    /**
     * Cards in the order people use them (issue #14): updates and the ring first, things set once in
     * the middle, rare and risky actions last. Each card's main action is a big button, the rest small.
     */
    void build() {
        keepTyped();
        final boolean again = root.getChildCount() > 0;
        final int y = scroll.getScrollY();
        root.removeAllViews();
        buildPage();
        if (again) { // a ring action rebuilt the page: stay where the person was, after layout
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, y);
                }
            });
        }
    }

    private void buildPage() {
        final Prefs p = Prefs.of(this);
        previewGen++; // a sync, Delete or going back may have changed the nights: the stage preview is read again
        LinearLayout top = Ui.hbox(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
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
        top.addView(Ui.h1(this, "Settings"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        // jump chips: the page is long, these get you to a section in one tap
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = Ui.hbox(this);
        hs.addView(chips);
        root.addView(hs);
        root.addView(Ui.gap(this, 8));
        status = Ui.text(this, RingManager.get(this).status(), 12, Ui.TEXT_2, Ui.REGULAR);
        root.addView(status);
        root.addView(Ui.gap(this, 8));
        if (p.demo()) {
            hs.setVisibility(View.GONE);
            LinearLayout demo = Ui.card(this);
            demo.addView(Ui.caption(this, "Demo mode"));
            demo.addView(Ui.body(this, "You are looking at a made up person's data. Your own data (if any) is kept separately and untouched."));
            action(demo, p.hasKey() ? "Leave the demo, back to my ring" : "Leave the demo and connect a ring", Ui.SLEEP, new Runnable() {
                @Override
                public void run() {
                    if (!app.openring.data.Demo.leave(SettingsActivity.this)) {
                        // the demo history is being analysed again; switching now would break that run (review of #243)
                        problem(app.openring.core.SyncError.plain(app.openring.core.SyncError.REANALYSING, app.openring.core.SyncError.Task.ACTION));
                        return;
                    }
                    startActivity(new Intent(SettingsActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                }
            });
            root.addView(demo);
            root.addView(updatesCard());
            // the ring's block: the demo has no ring to sync or switch, only the stage detail (its preview is the demo's night)
            ringBlock = block(app.openring.core.SettingsBlocks.RING);
            LinearLayout demoRing = Ui.vbox(this);
            demoRing.addView(Ui.body(this, "Demo mode: a made up Ring 4. Leave the demo to connect and sync your own ring."));
            ringBlock.add(demoRing);
            stagesCard = stageDetailCard(p);
            ringBlock.add(stagesCard);
            root.addView(ringBlock.card);
            watchBlock = block(app.openring.core.SettingsBlocks.WATCH);
            watchCard = watchCard();
            watchBlock.add(watchCard);
            root.addView(watchBlock.card);
            phoneBlock = block(app.openring.core.SettingsBlocks.PHONE);
            phoneBlock.add(phoneCard());
            root.addView(phoneBlock.card);
            periodCard = Ui.card(this);
            fillPeriodCard();
            root.addView(periodCard);
            screenCard = Ui.card(this);
            fillScreenCard(p);
            root.addView(screenCard);
            gamesCard = app.openring.ui.games.GamesActivity.settingsCard(this);
            root.addView(gamesCard);
            backupsCard = Ui.card(this);
            fillBackupsCard(p);
            root.addView(backupsCard);
            advancedCard = Ui.card(this);
            fillAdvancedCard();
            root.addView(advancedCard);
            LinearLayout about = Ui.card(this);
            about.addView(Ui.caption(this, "About"));
            about.addView(Ui.body(this, "Open Ring " + version() + ". Local first companion for the Oura Ring. Not affiliated with Oura Health. Not a medical device."));
            root.addView(about);
            scrollToSection();
            return;
        }

        // ---- updates ----
        LinearLayout updates = updatesCard();
        root.addView(updates);

        // ---- the Ring block (owner's wish of 3 November): the ring, its stage detail, measurements, key and ring actions ----
        ringBlock = block(app.openring.core.SettingsBlocks.RING);
        LinearLayout feat = null, key = null;
        if (PhoneViews.noRing(this)) {
            // without a ring by choice (issue #329): no ring actions, the way to add one
            stagesCard = null;
            LinearLayout none = Ui.vbox(this);
            none.addView(Ui.body(this, app.openring.core.KeyState.NO_RING_BLOCK));
            LinearLayout.LayoutParams nl = Ui.matchWrap();
            nl.setMargins(0, Ui.dp(this, 10), 0, 0);
            none.addView(PhoneViews.addRingButton(this, true), nl);
            ringBlock.add(none);
        } else {
            LinearLayout ring = Ui.vbox(this); // the block's header says Ring
            ring.addView(line("Serial", nz(p.serial())));
            ring.addView(line("Model", model(p.hardware())));
            ring.addView(line("Firmware", nz(p.firmware())));
            ring.addView(line("Battery", p.battery() < 0 ? "--" : p.battery() + "%  (" + ago(p.batteryAt()) + ")"));
            ring.addView(line("Last sync", p.lastSync() == 0 ? "never" : ago(p.lastSync())));
            // counted in the background (issue #198: with years of history the undated count reads every event), then filled in
            final LinearLayout counts = Ui.vbox(this);
            counts.addView(line("Stored events", "counting..."));
            ring.addView(counts, Ui.matchWrap());
            fillCounts(ring, counts);
            action(ring, "Sync now", Ui.SLEEP, new Runnable() {
                @Override
                public void run() {
                    if (!Permissions.granted(SettingsActivity.this)) {
                        Permissions.request(SettingsActivity.this);
                        return;
                    }
                    // one sync at a time, as on the main screen: a second one would end under the first one's "Up to date" (issue #43)
                    if (RingManager.get(SettingsActivity.this).busy()) {
                        problem(Texts.SYNC_BUSY);
                        return;
                    }
                    SyncService.start(SettingsActivity.this);
                }
            });
            small(ring, "Find my ring", Ui.OXY, new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(SettingsActivity.this, FinderActivity.class));
                }
            });
            Switch sw = new Switch(this);
            sw.setText("Sync about every 3 hours when the ring is nearby");
            sw.setTextColor(Ui.TEXT);
            sw.setChecked(p.backgroundSync());
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    p.setBackgroundSync(on);
                    SyncJob.schedule(SettingsActivity.this);
                }
            });
            LinearLayout.LayoutParams swl = Ui.matchWrap();
            swl.setMargins(0, Ui.dp(this, 10), 0, 0);
            ring.addView(sw, swl);
            ringBlock.add(ring);

            // ---- sleep stage detail ----
            stagesCard = stageDetailCard(p);
            ringBlock.add(stagesCard);

            // ---- measurements ----
            feat = measurementsSection();
            ringBlock.add(feat);

            // ---- the ring key ----
            key = keySection(p);
            ringBlock.add(key);

            // ---- careful, with the ring ----
            LinearLayout ringDanger = Ui.vbox(this);
            ringDanger.addView(Ui.caption(this, "Careful"));
            ringDanger.addView(Ui.text(this, "Each of these asks before it does anything.", 12, Ui.TEXT_3, Ui.REGULAR));
            small(ringDanger, "Use a different key or ring", Ui.WARN, new Runnable() {
                @Override
                public void run() {
                    confirm("Forget this ring?", "The saved key is removed from this phone. Your synced history stays. If you restore a full backup later, the app asks before it replaces this history.", new Runnable() {
                        @Override
                        public void run() {
                            p.clearKey();
                            p.forgetPendingModes(); // modes kept for this ring no longer apply (issue #221)
                            p.setSetupDone(false);
                            startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
                            finish();
                        }
                    });
                }
            });
            small(ringDanger, "Factory reset the ring", Ui.WARN, new Runnable() {
                @Override
                public void run() {
                    factoryReset();
                }
            });
            ringBlock.add(ringDanger);
        }
        root.addView(ringBlock.card);

        // ---- the Watch block (issue #251) ----
        watchBlock = block(app.openring.core.SettingsBlocks.WATCH);
        watchCard = watchCard();
        watchBlock.add(watchCard);
        root.addView(watchBlock.card);

        // ---- the Phone block (issue #329): steps from this phone ----
        phoneBlock = block(app.openring.core.SettingsBlocks.PHONE);
        phoneBlock.add(phoneCard());
        root.addView(phoneBlock.card);

        // ---- profile ----
        LinearLayout prof = profCard = Ui.card(this);
        prof.addView(Ui.caption(this, "Profile and goals"));
        prof.addView(Ui.text(this, "Used for calories, goals and the sleep you need at your age.", 12, Ui.TEXT_3, Ui.REGULAR));
        LinearLayout body = Ui.hbox(this);
        body.setGravity(android.view.Gravity.BOTTOM); // inputs stay level when a label wraps
        // typed but unsaved numbers stay through a rebuild (issue #133)
        final EditText age = ageIn = num(column(body, 0), "Age", drafts.text("age", p.age()));
        final EditText weight = weightIn = num(column(body, 1), "Weight (kg)", drafts.text("weight", p.weightKg()));
        final EditText height = heightIn = num(column(body, 2), "Height (cm)", drafts.text("height", p.heightCm()));
        prof.addView(body, Ui.matchWrap());
        if (sexChoice < 0) sexChoice = p.sex();
        prof.addView(Ui.gap(this, 8));
        prof.addView(Ui.text(this, "Sex", 12, Ui.TEXT_3, Ui.REGULAR));
        final LinearLayout sexRow = Ui.hbox(this);
        fillSex(sexRow);
        LinearLayout.LayoutParams sexLp = Ui.matchWrap();
        sexLp.setMargins(0, Ui.dp(this, 4), 0, 0); // the space the chips' own top margins gave (issue #264)
        prof.addView(sexRow, sexLp);
        final EditText target = kcalIn = num(prof, "Daily active calorie goal (0 = automatic)", drafts.text("kcal", p.targetKcal()));
        action(prof, "Save and re analyse", Ui.ACTIVE, new Runnable() {
            @Override
            public void run() {
                p.setProfile(SetupActivity.num(age, InputLimit.AGE), sexChoice, SetupActivity.num(weight, InputLimit.WEIGHT_KG),
                        SetupActivity.num(height, InputLimit.HEIGHT_CM), SetupActivity.num(target, InputLimit.TARGET_KCAL));
                // show what was really saved (a number out of range is saved at its limit)
                age.setText(String.valueOf(p.age()));
                weight.setText(String.valueOf(p.weightKg()));
                height.setText(String.valueOf(p.heightCm()));
                target.setText(String.valueOf(p.targetKcal()));
                // the tracker follows the saved profile until its switch is touched (issue #252)
                periodSwitch(p);
                reanalyse(true);
            }
        });
        // the period tracker (issue #252): shown by itself when the profile says female; hiding deletes nothing
        periodSwitch = new Switch(this);
        periodSwitch.setText("Show the period tracker");
        periodSwitch.setTextColor(Ui.TEXT);
        periodSwitch(p);
        LinearLayout.LayoutParams psl = Ui.matchWrap();
        psl.setMargins(0, Ui.dp(this, 14), 0, 0);
        prof.addView(periodSwitch, psl);
        prof.addView(Ui.text(this, "Shown when your profile says female. Your logged days stay stored when it is hidden.", 12, Ui.TEXT_3, Ui.REGULAR));
        // badges (issue #265): on by default; nothing about them is stored, so switching off deletes nothing
        final Switch badgesSwitch = new Switch(this);
        badgesSwitch.setText(Explain.SHOW_BADGES);
        badgesSwitch.setTextColor(Ui.TEXT);
        badgesSwitch.setChecked(p.badgesOn());
        badgesSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.of(SettingsActivity.this).setBadgesOn(on);
            }
        });
        LinearLayout.LayoutParams bsl = Ui.matchWrap();
        bsl.setMargins(0, Ui.dp(this, 14), 0, 0);
        prof.addView(badgesSwitch, bsl);
        prof.addView(Ui.text(this, Explain.SHOW_BADGES_NOTE, 12, Ui.TEXT_3, Ui.REGULAR));
        TextView openBadges = Ui.smallButton(this, "Open badges", Ui.GOOD);
        openBadges.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, BadgesActivity.class));
            }
        });
        LinearLayout.LayoutParams obl = Ui.matchWrap();
        obl.setMargins(0, Ui.dp(this, 8), 0, 0);
        prof.addView(openBadges, obl);
        root.addView(prof);

        // ---- period tracker: the reminder and "My periods have stopped" (issue #252, phase 1a2) ----
        periodCard = Ui.card(this);
        fillPeriodCard();
        root.addView(periodCard);

        // ---- screen time ----
        screenCard = Ui.card(this);
        fillScreenCard(p);
        root.addView(screenCard);

        // ---- ring games (issue #15) ----
        gamesCard = app.openring.ui.games.GamesActivity.settingsCard(this);
        root.addView(gamesCard);

        // ---- a backup file of the app (the key, settings, with history if wanted); its key half is in the Ring block ----
        LinearLayout file = Ui.card(this);
        file.addView(Ui.caption(this, "Backup file"));
        file.addView(Ui.body(this, "A backup file restores the app on a new phone from the setup screen (Restore from a backup)."));
        file.addView(Ui.gap(this, 10));
        file.addView(Ui.text(this, "Save a backup file", 12, Ui.TEXT_3, Ui.REGULAR));
        saveRow = pair(file, "Key and settings only", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                create("open-ring-backup.zip", "application/zip", BACKUP_KEY);
            }
        }, "Everything, with history", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                create("open-ring-full-backup.zip", "application/zip", BACKUP_FULL);
            }
        });
        // while a file is saved (issue #222): what is saved, and the buttons faded
        saveNote = Ui.text(this, "", 13, Ui.OXY, Ui.MEDIUM);
        saveNote.setPadding(0, Ui.dp(this, 6), 0, 0);
        file.addView(saveNote);
        file.addView(Ui.gap(this, 6));
        if (p.keyState() == app.openring.core.KeyState.UNREADABLE) {
            // a saved key this phone cannot read: backups are still saved, without it (issue #288); the sentence about the
            // key in the file would not be true then
            file.addView(Ui.text(this, app.openring.core.KeyState.BACKUP_NO_KEY, 13, Ui.WARN, Ui.REGULAR));
            file.addView(Ui.text(this, app.openring.core.KeyState.BACKUP_SETTINGS_ONLY, 12, Ui.TEXT_3, Ui.REGULAR));
        } else {
            file.addView(Ui.text(this, "The backup holds your ring key in readable form. Keep it as private as the key itself. The full backup also holds your period log.", 12, Ui.TEXT_3, Ui.REGULAR));
        }
        root.addView(file);

        // ---- automatic backups (issue #242) ----
        backupsCard = Ui.card(this);
        fillBackupsCard(p);
        root.addView(backupsCard);

        // ---- data ----
        LinearLayout data = Ui.card(this);
        data.addView(Ui.caption(this, "Export"));
        data.addView(Ui.body(this, "For spreadsheets or your own analysis. These files hold no key. The full database also holds your period log."));
        exportRow = pair(data, "Daily summaries (CSV)", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                create("open-ring-daily.csv", "text/csv", EXPORT_CSV);
            }
        }, "Full database", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                create("open-ring.db", "application/octet-stream", EXPORT_DB);
            }
        });
        exportNote = Ui.text(this, "", 13, Ui.OXY, Ui.MEDIUM);
        exportNote.setPadding(0, Ui.dp(this, 6), 0, 0);
        data.addView(exportNote);
        root.addView(data);

        // ---- help ----
        LinearLayout help = Ui.card(this);
        help.addView(Ui.caption(this, "Help and diagnostics"));
        help.addView(Ui.body(this, "Data check follows your data from the ring to the screens and shows where it stops. Safe to share. "
                + "Re analyse works out all nights and days again from the stored ring data. With a long history this can take a few minutes."));
        LinearLayout helpRow = pair(help, "Data check and log", Ui.READY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, LogActivity.class));
            }
        }, "Re analyse all", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                reanalyse(false);
            }
        });
        reanalyseButton = helpRow.getChildAt(1);
        reanalyseNote = Ui.text(this, Texts.REANALYSING, 13, Ui.OXY, Ui.MEDIUM);
        reanalyseNote.setPadding(0, Ui.dp(this, 6), 0, 0);
        help.addView(reanalyseNote);
        root.addView(help);
        showJobState();

        // ---- advanced: a time someone else wore the ring (issue #254) ----
        advancedCard = Ui.card(this);
        fillAdvancedCard();
        root.addView(advancedCard);

        // ---- danger: what concerns this phone's data (the ring's own actions are in the Ring block) ----
        LinearLayout danger = Ui.card(this);
        danger.addView(Ui.caption(this, "Careful"));
        danger.addView(Ui.text(this, "This asks before it does anything.", 12, Ui.TEXT_3, Ui.REGULAR));
        small(danger, "Delete all local data", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                confirm("Delete everything?", "All synced events and results on this phone are erased, including your period log. The ring keeps its key. Copies in your backup folder stay.", new Runnable() {
                    @Override
                    public void run() {
                        deleteAll();
                    }
                });
            }
        });
        root.addView(danger);

        LinearLayout about = Ui.card(this);
        about.addView(Ui.caption(this, "About"));
        about.addView(Ui.body(this, "Open Ring " + version() + ". A local first companion for Oura Ring 3, 4 and 5. Protocol and algorithms ported from open_oura and open_health "
                + "(MIT, Thomas Marchand), with notes from ringverse, OpenStrap, BrightOura and the Gadgetbridge Ring 4 work. The Screen tab follows the idea of ScreenTime by Markus Fisch. Not affiliated with Oura Health. "
                + "Not a medical device."));
        root.addView(about);
        // a chip to a part of the Ring or Watch block opens that block first
        String[] sections = {"Updates", "Ring", "Sleep stages", "Measurements", "Ring key", "Watch", "Phone", "Profile", "Period", "Screen time", "Games", "Backup file", "Backups",
            "Export", "Help", "Advanced", "Careful", "About"};
        View[] targets = {updates, ringBlock.card, stagesCard, feat, key, watchBlock.card, phoneBlock.card, prof, periodCard, screenCard, gamesCard, file, backupsCard, data, help, advancedCard,
            danger, about};
        for (int i = 0; i < sections.length; i++) {
            final View t = targets[i];
            // without a ring (issue #329) its sections are not there
            if (t == null) continue;
            TextView chip = Ui.button(this, sections[i], Ui.TEXT_2, false);
            chip.setTextSize(13);
            int ph = Ui.dp(this, 14), pv = Ui.dp(this, 7);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    jumpTo(t, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            chips.addView(chip, lp);
        }
        scrollToSection();
    }

    /** Measurements on the ring: daytime heart rate, blood oxygen at night, resting heart rate, each switched on the ring. */
    LinearLayout measurementsSection() {
        LinearLayout feat = Ui.vbox(this);
        feat.addView(Ui.caption(this, "Measurements on the ring"));
        feat.addView(Ui.body(this, "A ring paired outside the official app starts with daytime heart rate and blood oxygen switched off. "
                + "The button shows whether each one is on now; tap it to switch. ? means not read yet: tap it, or Read from ring."));
        final String[] names = {"Daytime heart rate", "Blood oxygen (SpO2) at night", "Resting heart rate"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            row.addView(Ui.text(this, names[i], 14, Ui.TEXT, Ui.REGULAR), Ui.weight(1));
            String state = featureModes[i] < 0 ? "?" : featureModes[i] == 0 ? "Off" : "On";
            TextView t = Ui.smallButton(this, state, featureModes[i] > 0 ? Ui.READY : Ui.TEXT_2);
            t.setMinWidth(Ui.dp(this, 64));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (featureModes[k] < 0) {
                        readFromRing(); // state unknown: read it first instead of guessing
                        return;
                    }
                    final int mode = featureModes[k] > 0 ? Protocol.MODE_OFF : Protocol.MODE_AUTOMATIC;
                    ringPending = true;
                    RingManager.get(SettingsActivity.this).setFeature(RingManager.FEATURES[k], mode, new RingManager.Callback() {
                        @Override
                        public void done(boolean ok, String message) {
                            ringPending = false;
                            int[] modes = featureModes.clone(); // what this page knew, for a page built by a turn
                            modes[k] = mode;
                            ringDone(ok, message, ok ? modes : null, true);
                        }
                    });
                }
            });
            row.addView(t);
            feat.addView(row, Ui.matchWrap());
        }
        small(feat, "Read from ring", Ui.READY, new Runnable() {
            @Override
            public void run() {
                readFromRing();
            }
        });
        return feat;
    }

    /** The ring key: Show key, Copy key, a saved key this phone cannot read, a key kept from a pairing that did not finish. */
    LinearLayout keySection(final Prefs p) {
        LinearLayout key = Ui.vbox(this);
        key.addView(Ui.caption(this, "Ring key"));
        key.addView(Ui.body(this, "Keep a copy of the key. Without it you would have to factory reset the ring to connect again."));
        key.addView(Ui.gap(this, 6));
        if (!p.demo() && p.keyState() == app.openring.core.KeyState.UNREADABLE) {
            // a saved key this phone cannot read shows no empty key; it stays until a new one works (issue #199)
            key.addView(Ui.text(this, app.openring.core.KeyState.SETTINGS_TEXT, 14, Ui.WARN, Ui.REGULAR));
            small(key, app.openring.core.KeyState.BUTTON, Ui.SLEEP, new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
                }
            });
        } else {
            final TextView kt = Ui.text(this, "•••• tap Show key ••••", 15, Ui.TEXT, android.graphics.Typeface.MONOSPACE);
            kt.setTextIsSelectable(true);
            key.addView(kt);
            pair(key, "Show key", Ui.TEXT_2, new Runnable() {
                @Override
                public void run() {
                    kt.setText(Hex.encode(p.loadKey()));
                }
            }, "Copy key", Ui.TEXT_2, new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Ring key", Hex.encode(p.loadKey())));
                    say("Copied");
                }
            });
        }
        // a key kept from a pairing that did not finish, for a ring that may hold it (issue #218)
        final byte[] keptKey = p.demo() ? null : p.pendingKey();
        if (keptKey != null && !java.util.Arrays.equals(keptKey, p.loadKey())) {
            key.addView(Ui.gap(this, 10));
            key.addView(Ui.caption(this, app.openring.core.Pairing.keptFor(p.pendingKeySerial())));
            key.addView(Ui.text(this, app.openring.core.Pairing.KEPT_SETTINGS, 12, Ui.TEXT_3, Ui.REGULAR));
            TextView kk = Ui.text(this, Hex.encode(keptKey), 15, Ui.TEXT, android.graphics.Typeface.MONOSPACE);
            kk.setTextIsSelectable(true);
            key.addView(kk);
            small(key, "Copy kept key", Ui.TEXT_2, new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Kept ring key", Hex.encode(keptKey)));
                    say("Copied");
                }
            });
        }
        return key;
    }

    private LinearLayout screenCard, gamesCard, backupsCard, advancedCard, watchCard, periodCard;
    /** The request code of the notification permission, asked when a reminder is first switched on. */
    static final int ASK_NOTIFY = 21;
    /** Whether Android lets the app notify, as the Period tracker card shows it (refilled when it changed). */
    private boolean notifyShown;

    boolean notificationsOn() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    /**
     * The Period tracker card (issue #252, phase 1a2): "Remind me before my period" (Off, 1, 2, 3 or 5 days, a time of day),
     * the next reminder or why there is none, and "My periods have stopped". The notification permission is asked when a
     * reminder is switched on and Android does not have it yet; the reminder is planned again after each change.
     */
    void fillPeriodCard() {
        final LinearLayout card = periodCard;
        if (card == null) return;
        final Prefs p = Prefs.of(this);
        card.removeAllViews();
        card.addView(Ui.caption(this, "Period tracker"));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, "Remind me before my period", 14, Ui.TEXT, Ui.REGULAR));
        LinearLayout row = Ui.hbox(this);
        final int days = p.periodRemindDays();
        int[] choices = app.openring.core.PeriodRemind.DAYS;
        for (int i = 0; i < choices.length; i++) {
            final int k = choices[i];
            TextView chip = Ui.smallButton(this, app.openring.core.PeriodRemind.chip(k), k == days ? Ui.PERIOD : Ui.TEXT_3);
            chip.setPadding(Ui.dp(this, 4), chip.getPaddingTop(), Ui.dp(this, 4), chip.getPaddingBottom());
            if (k == days) chip.setBackground(Ui.rounded(Ui.alpha(Ui.PERIOD, 0.18f), Ui.dp(this, 24))); // filled, not only colored
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    p.setPeriodRemindDays(k);
                    if (k > 0) askNotifications();
                    app.openring.sync.PeriodReminder.planLater(SettingsActivity.this);
                    fillPeriodCard();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(i == 0 ? 0 : Ui.dp(this, 3), 0, i == choices.length - 1 ? 0 : Ui.dp(this, 3), 0);
            row.addView(chip, lp);
        }
        LinearLayout.LayoutParams rl = Ui.matchWrap();
        rl.setMargins(0, Ui.dp(this, 6), 0, 0);
        card.addView(row, rl);
        card.addView(Ui.text(this, "Days before the earliest day of the expected range", 12, Ui.TEXT_3, Ui.REGULAR));
        final int minute = p.periodRemindMinute();
        small(card, app.openring.core.PeriodRemind.time(minute), Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                new android.app.TimePickerDialog(SettingsActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker view, int h, int m) {
                        p.setPeriodRemindMinute(h * 60 + m);
                        app.openring.sync.PeriodReminder.planLater(SettingsActivity.this);
                        fillPeriodCard();
                    }
                }, minute / 60, minute % 60, true).show();
            }
        });
        notifyShown = notificationsOn();
        if (days > 0 && !notifyShown) {
            TextView off = Ui.text(this, "Notifications are off for Open Ring. Tap to open Android settings.", 13, Ui.WARN, Ui.REGULAR);
            off.setPadding(0, Ui.dp(this, 8), 0, 0);
            off.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (RuntimeException e) {
                        Permissions.openSettings(SettingsActivity.this);
                    }
                }
            });
            card.addView(off);
        }
        final TextView next = Ui.text(this, days > 0 ? "Working out the next reminder" : "Reminders are off.", 13, Ui.TEXT_2, Ui.REGULAR);
        next.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(next);
        if (days > 0) {
            final Context ctx = getApplicationContext();
            new AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... v) {
                    try {
                        long now = System.currentTimeMillis();
                        return app.openring.core.PeriodRemind.status(app.openring.sync.PeriodReminder.next(ctx, now), java.time.ZoneId.systemDefault(), now, Locale.getDefault());
                    } catch (RuntimeException e) {
                        // the error's kind only (the log can be shared), never a date
                        RingManager.get(ctx).log("period reminder: " + e.getClass().getSimpleName());
                        return "Your period log could not be read for the reminder just now. Try again later.";
                    }
                }

                @Override
                protected void onPostExecute(String s) {
                    next.setText(s);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        card.addView(Ui.text(this, Explain.PERIOD_REMIND, 12, Ui.TEXT_3, Ui.REGULAR));
        Switch stop = new Switch(this);
        stop.setText("My periods have stopped");
        stop.setTextColor(Ui.TEXT);
        stop.setChecked(p.periodStopped() != null);
        stop.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                p.setPeriodStopped(on ? java.time.LocalDate.now().toString() : null);
                app.openring.sync.PeriodReminder.planLater(SettingsActivity.this);
                fillPeriodCard();
            }
        });
        LinearLayout.LayoutParams swl = Ui.matchWrap();
        swl.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(stop, swl);
        card.addView(Ui.text(this, Explain.PERIOD_STOPPED, 12, Ui.TEXT_3, Ui.REGULAR));
        dripImports(card);
    }

    /**
     * Import from drip (issue #252, phase 1b) at the end of the Period tracker card: the button that opens the import, and
     * one "Undo the drip import of 30 Oct" per import in force, read in the background (in demo mode the demo's file).
     */
    void dripImports(final LinearLayout card) {
        card.addView(Ui.gap(this, 14));
        card.addView(Ui.text(this, "Your history from drip", 14, Ui.TEXT, Ui.REGULAR));
        card.addView(Ui.text(this, Explain.DRIP_SETTINGS, 12, Ui.TEXT_3, Ui.REGULAR));
        small(card, "Import from drip", Ui.PERIOD, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, DripImportActivity.class));
            }
        });
        final LinearLayout box = Ui.vbox(this);
        card.addView(box, Ui.matchWrap());
        final Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, java.util.List<app.openring.data.Periods.Import>>() {
            @Override
            protected java.util.List<app.openring.data.Periods.Import> doInBackground(Void... v) {
                try {
                    return app.openring.data.Periods.imports(app.openring.data.Db.get(ctx));
                } catch (RuntimeException e) {
                    RingManager.get(ctx).log("drip imports not read: " + e.getClass().getSimpleName());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(java.util.List<app.openring.data.Periods.Import> list) {
                if (isFinishing() || list == null || list.isEmpty()) return;
                java.util.Set<String> days = new java.util.HashSet<String>(), twice = new java.util.HashSet<String>();
                for (app.openring.data.Periods.Import im : list) if (!days.add(importDay(im, false))) twice.add(importDay(im, false));
                for (final app.openring.data.Periods.Import im : list) {
                    final String when = importDay(im, twice.contains(importDay(im, false)));
                    small(box, "Undo the drip import of " + when, Ui.TEXT_2, new Runnable() {
                        @Override
                        public void run() {
                            confirm("Undo the drip import of " + when + "?", Explain.dripAdded(im.days, im.temps) + " " + Explain.DRIP_UNDO, new Runnable() {
                                @Override
                                public void run() {
                                    undoImport(im.id);
                                }
                            });
                        }
                    });
                    box.addView(Ui.text(SettingsActivity.this, Explain.dripAdded(im.days, im.temps) + " " + Explain.DRIP_UNDO, 12, Ui.TEXT_3, Ui.REGULAR));
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** "30 Oct" (with the year when not this one), with the time when two imports share the day. */
    static String importDay(app.openring.data.Periods.Import im, boolean withTime) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(im.id).atZone(java.time.ZoneId.systemDefault());
        String d = PeriodCard.day(t.toLocalDate());
        return withTime ? d + ", " + String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()) : d;
    }

    /** Undoes one drip import in the background (one transaction), then the card and the reminder follow. */
    void undoImport(final long id) {
        final Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    boolean ok = app.openring.data.Periods.undoImport(app.openring.data.Db.get(ctx), id, System.currentTimeMillis() / 1000.0);
                    return ok ? "Import undone" : "This import was already undone.";
                } catch (RuntimeException e) {
                    RingManager.get(ctx).log("drip import not undone: " + e.getClass().getSimpleName());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String r) {
                if (r == null) problem("The import could not be undone, nothing was changed. Please try again.");
                else say(r);
                app.openring.sync.PeriodReminder.planLater(ctx);
                fillPeriodCard();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Asks for the notification permission in context, when a reminder is switched on (Android 13 and newer). */
    void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFY);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == ASK_NOTIFY) fillPeriodCard();
        // Physical activity (issue #329): the job follows the answer, a first reading comes at once
        if (code == PhoneViews.REQUEST) {
            app.openring.sync.PhoneJob.schedule(this);
            if (app.openring.sync.PhoneReader.counting(this)) app.openring.sync.PhoneReader.readLater(this, "allowed", 0, null);
            fillPhoneCard();
        }
    }

    /** The Watch card (issue #251): the watch this app syncs, its state, Sync the watch now, how often, the Watch check. */
    LinearLayout watchCard() {
        LinearLayout card = Ui.vbox(this); // a section of the Watch block, whose header says Watch
        watchCard = card;
        fillWatchCard();
        return card;
    }

    /** A watch sync started from this card runs; its sentence shows when it ends. */
    private static volatile boolean watchSyncing;

    void fillWatchCard() {
        final LinearLayout card = watchCard;
        if (card == null) return;
        final Prefs p = Prefs.of(this);
        card.removeAllViews();
        if (watchBlock != null) watchBlock.status.setText(watchStatus(p)); // the header follows a sync, a forget or a new watch
        // the watch setup (issue #251): find, pair, sign in, send the setup; the alarm and the hands
        action(card, p.watchSetupAt() > 0 || p.demo() ? "Watch setup, alarm and hands" : "Set up the watch", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, WatchSetupActivity.class));
            }
        });
        // the main device (issue #251, one set of numbers): only once a watch was added; with steps from this phone it lives in
        // the Phone block instead (issue #329), so it is shown once
        if ((p.demo() || p.watchAddress() != null) && !phoneThere(p)) mainDeviceChoice(card, p);
        else if (p.demo() || p.watchAddress() != null) {
            int shown = app.openring.core.MainDevice.shown(p.mainDeviceStored(), ringThere(p), true, true);
            card.addView(line("Main device", app.openring.core.MainDevice.mainLine(shown)));
            card.addView(Ui.text(this, "Choose it in the Phone block below.", 12, Ui.TEXT_3, Ui.REGULAR));
            card.addView(Ui.gap(this, 10));
        }
        if (p.demo()) {
            card.addView(Ui.body(this, "Demo mode: a made up Withings Steel HR. Turn demo mode off to sync a real watch."));
            watchPageButton(card);
            small(card, "Check a Withings watch", Ui.OXY, new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(SettingsActivity.this, WatchActivity.class));
                }
            });
            return;
        }
        final String address = p.watchAddress();
        if (address == null) {
            card.addView(Ui.body(this, Explain.WATCH_ADD));
            action(card, "Check a Withings watch", Ui.OXY, new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(SettingsActivity.this, WatchActivity.class));
                }
            });
            return;
        }
        card.addView(line("Watch", nz(p.watchName())));
        card.addView(line("Paired with this phone", watchBonded(address)));
        card.addView(line("Last sync", p.watchLastOk() == 0 ? "never" : ago(p.watchLastOk())));
        final LinearLayout bat = Ui.vbox(this);
        bat.addView(line("Battery", "reading..."));
        card.addView(bat, Ui.matchWrap());
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                long[] b = null;
                try {
                    b = app.openring.data.WatchStore.newestBattery(app.openring.data.Db.get(appCtx));
                } catch (RuntimeException e) {
                    RingManager.get(appCtx).log("watch battery not read (" + e.getClass().getSimpleName() + ")");
                }
                final long[] got = b;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bat.removeAllViews();
                        bat.addView(line("Battery", got == null ? "--" : got[0] + "%  (" + ago(got[1]) + ")"));
                    }
                });
            }
        }, "watch battery").start();
        if (watchSyncing || app.openring.ble.WatchSync.running()) {
            card.addView(Ui.text(this, "Syncing the watch, this takes up to two minutes.", 13, Ui.TEXT_2, Ui.REGULAR));
        } else if (p.watchError() != null) {
            card.addView(Ui.text(this, p.watchError(), 13, Ui.WARN, Ui.REGULAR));
        }
        action(card, "Sync the watch now", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                if (!Permissions.granted(SettingsActivity.this)) {
                    Permissions.request(SettingsActivity.this);
                    return;
                }
                if (watchSyncing || app.openring.ble.WatchSync.running()) {
                    say(app.openring.ble.WatchSync.ALREADY);
                    return;
                }
                watchSyncing = true;
                fillWatchCard();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = app.openring.ble.WatchSync.run(appCtx);
                        watchSyncing = false;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                say(r == null ? "Watch synced" : r);
                                fillWatchCard();
                            }
                        });
                    }
                }, "watch sync").start();
            }
        });
        card.addView(Ui.gap(this, 10));
        card.addView(Ui.text(this, "Sync in the background", 12, Ui.TEXT_3, Ui.REGULAR));
        final int per = p.watchPerDay();
        for (int row = 0; row < 2; row++) {
            LinearLayout r = Ui.hbox(this);
            for (int k = row * 2; k < row * 2 + 2; k++) {
                final int value = app.openring.core.withings.WatchDue.PER_DAY[k];
                boolean on = value == per;
                TextView chip = Ui.smallButton(this, app.openring.core.withings.WatchDue.LABELS[k], on ? Ui.OXY : Ui.TEXT_3);
                if (on) chip.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.18f), Ui.dp(this, 24)));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        p.setWatchPerDay(value);
                        SyncJob.schedule(SettingsActivity.this);
                        fillWatchCard();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(k % 2 == 0 ? 0 : Ui.dp(this, 4), Ui.dp(this, 4), k % 2 == 0 ? Ui.dp(this, 4) : 0, 0);
                r.addView(chip, lp);
            }
            card.addView(r, Ui.matchWrap());
        }
        card.addView(Ui.text(this, app.openring.core.withings.WatchDue.explain(per), 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, Explain.WATCH_WITHINGS_APP, 12, Ui.TEXT_3, Ui.REGULAR));
        watchPageButton(card);
        small(card, "Check a Withings watch", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, WatchActivity.class));
            }
        });
        small(card, "Forget the watch", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                confirm("Forget this watch?", Explain.WATCH_FORGET, new Runnable() {
                    @Override
                    public void run() {
                        p.forgetWatch();
                        SyncJob.schedule(SettingsActivity.this);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    app.openring.data.WatchStore.removeDevice(app.openring.data.Db.get(appCtx), address, System.currentTimeMillis() / 1000.0);
                                } catch (RuntimeException e) {
                                    RingManager.get(appCtx).log("watch: forgetting not noted (" + e.getClass().getSimpleName() + ")");
                                }
                            }
                        }, "watch forget").start();
                        fillWatchCard();
                    }
                });
            }
        });
    }

    /**
     * Main device: Ring or Watch (issue #251). The other one fills the time the main one was not worn; only what is shown
     * changes, so the tabs load again when the person goes back.
     */
    void mainDeviceChoice(LinearLayout card, final Prefs p) {
        card.addView(Ui.text(this, "Main device", 14, Ui.TEXT, Ui.MEDIUM));
        // only the devices present (issue #329): Ring, Watch, Phone; the one shown is the one the screens use
        boolean ring = ringThere(p), watch = p.demo() || p.watchAddress() != null, phone = phoneThere(p);
        final int main = app.openring.core.MainDevice.shown(p.mainDeviceStored(), ring, watch, phone);
        LinearLayout r = Ui.hbox(this);
        int[] values = app.openring.core.MainDevice.present(ring, watch, phone);
        for (int k = 0; k < values.length; k++) {
            final int value = values[k];
            boolean on = value == main;
            TextView chip = Ui.smallButton(this, app.openring.core.MainDevice.label(value), on ? Ui.OXY : Ui.TEXT_3);
            if (on) chip.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.18f), Ui.dp(this, 24)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (value == main) return;
                    p.setMainDevice(value);
                    fillWatchCard();
                    fillPhoneCard();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(k == 0 ? 0 : Ui.dp(this, 4), Ui.dp(this, 4), k == 0 ? Ui.dp(this, 4) : 0, 0);
            r.addView(chip, lp);
        }
        card.addView(r, Ui.matchWrap());
        if (main == app.openring.core.MainDevice.PHONE) card.addView(Ui.text(this, app.openring.core.MainDevice.mainLine(main), 13, Ui.TEXT_2, Ui.REGULAR));
        card.addView(Ui.text(this, Explain.MAIN_DEVICE, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 10));
    }

    /** A ring is there for the main device choice: paired (a key stored), or the demo's. */
    static boolean ringThere(Prefs p) {
        return p.demo() || app.openring.core.KeyState.hasRing(p.ringState());
    }

    /** The phone counts steps (or the demo's made up phone): it is a main device choice and has its block's line. */
    boolean phoneThere(Prefs p) {
        return p.demo() || app.openring.sync.PhoneReader.counting(this);
    }

    /** The Phone block's section (issue #329). */
    LinearLayout phoneCard() {
        phoneCard = Ui.vbox(this); // a section of the Phone block, whose header says Phone
        fillPhoneCard();
        return phoneCard;
    }

    /**
     * Steps from this phone: on, or why not (no step counter, Physical activity not allowed, with Allow), the last reading,
     * how it counts, and the main device choice when two or more devices are there.
     */
    void fillPhoneCard() {
        final LinearLayout card = phoneCard;
        if (card == null) return;
        final Prefs p = Prefs.of(this);
        card.removeAllViews();
        boolean demo = p.demo(), sensor = app.openring.sync.PhoneReader.hasSensor(this), allowed = app.openring.sync.PhoneReader.allowed(this);
        if (phoneBlock != null) phoneBlock.status.setText(phoneStatus(p, Double.NaN));
        String state = demo ? "On, made up demo steps" : !sensor ? "No, this phone has no step counter" : !allowed ? "Off, Physical activity was not allowed" : "On";
        card.addView(line(app.openring.core.PhoneSteps.TITLE, state));
        if (!demo && sensor && !allowed) {
            action(card, app.openring.core.PhoneSteps.ALLOW, Ui.OXY, new Runnable() {
                @Override
                public void run() {
                    // asked once in context: Android asks again; refused before: only the app's settings can switch it on
                    if (!p.phoneAsked()) PhoneViews.ask(SettingsActivity.this);
                    else Permissions.openSettings(SettingsActivity.this);
                }
            });
        }
        final LinearLayout last = Ui.vbox(this);
        last.addView(line("Last reading", "reading..."));
        card.addView(last, Ui.matchWrap());
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                double t = 0;
                try {
                    t = app.openring.data.PhoneStore.lastReading(app.openring.data.Db.get(appCtx));
                } catch (RuntimeException e) {
                    RingManager.get(appCtx).log("phone steps: last reading not read (" + e.getClass().getSimpleName() + ")");
                }
                final double got = t;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        last.removeAllViews();
                        last.addView(line("Last reading", got <= 0 ? "none yet" : ago((long) got)));
                        if (phoneBlock != null) phoneBlock.status.setText(phoneStatus(Prefs.of(SettingsActivity.this), got));
                    }
                });
            }
        }, "phone last reading").start();
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, app.openring.core.PhoneSteps.ABOUT, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(this, Explain.PHONE_STEPS, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 10));
        boolean ring = ringThere(p), watch = demo || p.watchAddress() != null, phone = phoneThere(p);
        if (phone && app.openring.core.MainDevice.present(ring, watch, true).length >= 2) mainDeviceChoice(card, p);
    }

    /** The Phone header's line; {@code last} the newest reading (unix seconds, 0 none, NaN not read yet). */
    String phoneStatus(Prefs p, double last) {
        boolean known = !Double.isNaN(last) && last > 0;
        return app.openring.core.SettingsBlocks.phoneStatus(p.demo(), app.openring.sync.PhoneReader.hasSensor(this), app.openring.sync.PhoneReader.allowed(this),
                known ? last : 0, known ? ago((long) last) : null);
    }

    void watchPageButton(LinearLayout card) {
        small(card, "Open the watch page", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, WatchPageActivity.class));
            }
        });
    }

    /** Whether Android keeps the watch paired with this phone (the Withings app paired it). */
    String watchBonded(String address) {
        try {
            android.bluetooth.BluetoothAdapter a = app.openring.ble.RingScanner.adapter(this);
            if (a == null) return "--";
            return a.getRemoteDevice(address).getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED ? "yes" : "no";
        } catch (RuntimeException e) {
            return "--"; // no Bluetooth permission yet
        }
    }

    /** Usage access as shown in the Screen time card, to refresh it only when it changed. */
    private boolean screenAllowedShown;
    /** The first read of the screen log was started (once per app run, even if the log was empty). */
    private static boolean screenRead;

    /**
     * Screen time (issue #13): usage access, the daily goal and whether phone use counts as awake
     * in the sleep analysis. Filled again when coming back from Android's usage access page.
     */
    void fillScreenCard(final Prefs p) {
        final LinearLayout card = screenCard;
        if (card == null) return;
        keepTyped(); // a goal being typed stays when the card is filled again (issue #133)
        card.removeAllViews();
        card.addView(Ui.caption(this, "Screen time"));
        card.addView(Ui.body(this, "When you used this phone (from unlocking until the screen goes off). It has its own tab (Screen) and helps the sleep analysis. "
                + "Android only shares this after you switch on Open Ring under usage access in Android's settings. "
                + "It stays on this phone like everything else."));
        if (!p.demo() && !ScreenUse.supported()) {
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, "Needs Android 9 or newer.", 13, Ui.WARN, Ui.REGULAR));
            return;
        }
        if (p.demo()) {
            card.addView(line("Usage access", "demo data"));
        } else {
            boolean ok = ScreenUse.allowed(this);
            screenAllowedShown = ok;
            card.addView(line("Usage access", ok ? "allowed" : "not allowed yet"));
            small(card, ok ? "Change usage access" : "Allow usage access", Ui.OXY, new Runnable() {
                @Override
                public void run() {
                    ScreenUse.openAccessSettings(SettingsActivity.this);
                }
            });
        }
        final EditText goal = goalIn = num(card, "Daily goal in minutes (0 for no goal, the score then uses 3 hours)", drafts.text("screen_goal", p.screenGoalMin()));
        small(card, "Save goal", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                p.setScreenGoalMin(SetupActivity.num(goal, InputLimit.SCREEN_GOAL_MIN));
                goal.setText(String.valueOf(p.screenGoalMin())); // what was really saved
                say("Goal saved");
            }
        });
        Switch sw = new Switch(this);
        sw.setText("Count phone use as awake");
        sw.setTextColor(Ui.TEXT);
        sw.setChecked(p.screenAwake());
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                p.setScreenAwake(on);
                updateNights("Updating the nights with phone use");
            }
        });
        LinearLayout.LayoutParams swl = Ui.matchWrap();
        swl.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(sw, swl);
        card.addView(Ui.text(this, "When the screen is on during a night, that time counts as awake, and a night does not start while "
                + "you use the phone. Switch it off if you often fall asleep with a video playing.", 12, Ui.TEXT_3, Ui.REGULAR));
    }

    /** Reads new screen time and analyses the nights it touches, in the background. */
    void updateNights(final String message) {
        if (message != null) say(message);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                try {
                    ScreenUse.collect(SettingsActivity.this, true);
                    Analyzer.run(SettingsActivity.this, null);
                } catch (Throwable e) {
                    // the analysis rolled back and stores its own error for the data check, also out of memory (issue #230);
                    // its class and place only, so the full text goes to the Log tab (issue #289)
                    logFailure("analysis failed", e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void r) {
                // the nights now have their new phone minutes: the Settings screen shown now (also after
                // the phone was turned meanwhile) redraws its preview (issue #204)
                SettingsActivity s = shownNow();
                if (s != null) s.redrawStageDetail();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Redraws only the stage detail card, so typed values in the other cards stay (issue #133), and
     * keeps what the person looks at still when the card above changes height (issue #204).
     */
    void redrawStageDetail() {
        previewGen++; // the nights changed: read the preview again
        redrawStageCard();
    }

    private void redrawStageCard() {
        final LinearLayout card = stagesCard;
        if (card == null) return;
        keepScroll(card);
        fillStageDetail(card, Prefs.of(this));
    }

    /**
     * Call before changing what {@code card} (a card on the page) holds: once it is laid out with its new height, the
     * scroll follows (issue #204). Nothing to keep while the card was never laid out (the page is just being built).
     */
    void keepScroll(final View card) {
        if (card.getHeight() == 0 || !card.isShown()) return; // in a closed block: nothing moves on the page
        final int before = card.getHeight();
        // the new height is known once the card is laid out; the scroll then follows in post() (CLAUDE.md lesson)
        card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                final int top = topOnPage(v), after = b - t; // a section inside a block: its top on the page
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        // from where the page is now, not where it was when the read began (review of #280): a jump
                        // to a card (the settings extra, scrollToSection) or another card's correction in between stays
                        scroll.scrollTo(0, app.openring.core.ScrollKeep.after(scroll.getScrollY(), top, before, after));
                    }
                });
            }
        });
    }

    /** Reads which measurements are switched on and shows them. */
    void readFromRing() {
        ringPending = true;
        RingManager.get(this).readFeatures(new RingManager.Callback() {
            @Override
            public void done(boolean ok, String message) {
                ringPending = false;
                if (!ok) {
                    ringDone(false, message, null, false);
                    return;
                }
                int[] modes = featureModes.clone();
                for (String part : message.split(",")) {
                    String[] kv = part.split(":");
                    int f = Integer.parseInt(kv[0]), m = Integer.parseInt(kv[1]);
                    for (int i = 0; i < 3; i++) if (RingManager.FEATURES[i] == f) modes[i] = m;
                }
                ringDone(true, null, modes, false);
            }
        });
    }

    /** Sex picked in the profile card (0 male, 1 female, 2 not set), saved with its button. */
    private int sexChoice = -1;

    /** "Show the period tracker" in the Profile card (issue #252). */
    private Switch periodSwitch;

    /** Shows the tracker's state on its switch without counting that as a choice; a tap stores the choice. */
    void periodSwitch(final Prefs p) {
        periodSwitch.setOnCheckedChangeListener(null);
        periodSwitch.setChecked(app.openring.core.analysis.Cycles.shown(p.periodChoice(), p.sex(), false));
        periodSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                p.setPeriodChoice(on ? 1 : 0);
            }
        });
    }
    static final String[] SEXES = {"Male", "Female", "Not set"};

    void fillSex(final LinearLayout row) {
        row.removeAllViews();
        for (int i = 0; i < SEXES.length; i++) {
            final int k = i;
            TextView chip = Ui.smallButton(this, SEXES[i], i == sexChoice ? Ui.ACTIVE : Ui.TEXT_3);
            if (i == sexChoice) chip.setBackground(Ui.rounded(Ui.alpha(Ui.ACTIVE, 0.18f), Ui.dp(this, 24))); // filled, not only colored
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sexChoice = k;
                    fillSex(row);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
            // no vertical margins: a top margin alone pushed the outline's bottom out of the row (issue #264, as in review of #259)
            lp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), 0, i == SEXES.length - 1 ? 0 : Ui.dp(this, 4), 0);
            row.addView(chip, lp);
        }
    }

    /** One of three equal columns in a row of inputs. */
    LinearLayout column(LinearLayout row, int index) {
        LinearLayout col = Ui.vbox(this);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.setMargins(index == 0 ? 0 : Ui.dp(this, 4), 0, index == 2 ? 0 : Ui.dp(this, 4), 0);
        row.addView(col, lp);
        return col;
    }

    /** Small card at the top: installed version and a button that opens the releases page (issues #7, #14). */
    LinearLayout updatesCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, "Updates"));
        card.addView(Ui.gap(this, 4));
        addUpdateRow(card);
        return card;
    }

    /** One choice of a chip row. */
    interface Pick {
        void pick(int i);
    }

    /** A row of equal chips, the chosen one filled (the profile's style). */
    void chipRow(LinearLayout parent, String[] labels, int chosen, int color, final Pick pick) {
        LinearLayout row = Ui.hbox(this);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView chip = Ui.smallButton(this, labels[i], i == chosen ? color : Ui.TEXT_3);
            if (i == chosen) chip.setBackground(Ui.rounded(Ui.alpha(color, 0.18f), Ui.dp(this, 24)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.pick(k);
                }
            });
            // equal heights, no vertical margins: a top margin alone pushed the outline's bottom out of the row (review of #259)
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), 0, i == labels.length - 1 ? 0 : Ui.dp(this, 4), 0);
            row.addView(chip, lp);
        }
        LinearLayout.LayoutParams rp = Ui.matchWrap();
        rp.setMargins(0, Ui.dp(this, 4), 0, 0);
        parent.addView(row, rp);
    }

    static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    /**
     * Automatic backups (issue #242): the copies before updates on this phone with "Go back to this copy", and the
     * folder that gets regular copies (how often, how many, the last result). Demo mode shows an example only.
     */
    void fillBackupsCard(final Prefs p) {
        final LinearLayout card = backupsCard;
        if (card == null) return;
        card.removeAllViews();
        final boolean demo = p.demo();
        final Runnable notInDemo = new Runnable() {
            @Override
            public void run() {
                toast(Backups.NOT_IN_DEMO);
            }
        };
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        long now = System.currentTimeMillis() / 1000;
        card.addView(Ui.caption(this, Backups.TITLE));
        if (demo) card.addView(Ui.text(this, Backups.DEMO, 12, Ui.OXY, Ui.REGULAR));
        String folder = demo ? "Backups" : p.backupFolderName();
        if (!demo && p.backupFolder() == null) {
            card.addView(Ui.body(this, Backups.OFF_TEXT));
            action(card, Backups.CHOOSE, Ui.SLEEP, new Runnable() {
                @Override
                public void run() {
                    pickFolder();
                }
            });
        } else {
            card.addView(Ui.gap(this, 4));
            card.addView(Ui.text(this, "Folder: " + folder, 14, Ui.TEXT, Ui.REGULAR));
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, Backups.HOW_OFTEN, 12, Ui.TEXT_3, Ui.REGULAR));
            chipRow(card, Backups.DAY_NAMES, indexOf(Backups.DAYS, demo ? Backups.DEFAULT_DAYS : p.backupDays()), Ui.SLEEP, new Pick() {
                @Override
                public void pick(int i) {
                    if (demo) {
                        notInDemo.run();
                        return;
                    }
                    // the one daily job stays as it is; each run decides whether a copy is due (issue #193)
                    p.setBackupDays(Backups.DAYS[i]);
                    fillBackupsCard(p);
                }
            });
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, Backups.HOW_MANY, 12, Ui.TEXT_3, Ui.REGULAR));
            String[] keep = new String[Backups.KEEP.length];
            for (int i = 0; i < keep.length; i++) keep[i] = String.valueOf(Backups.KEEP[i]);
            chipRow(card, keep, indexOf(Backups.KEEP, demo ? Backups.DEFAULT_KEEP : p.backupKeep()), Ui.SLEEP, new Pick() {
                @Override
                public void pick(int i) {
                    if (demo) {
                        notInDemo.run();
                        return;
                    }
                    p.setBackupKeep(Backups.KEEP[i]);
                    fillBackupsCard(p);
                }
            });
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, Backups.ALSO_UPDATE, 12, Ui.TEXT_2, Ui.REGULAR));
            card.addView(Ui.gap(this, 8));
            long lastOk = demo ? java.time.LocalDate.now().minusDays(1).atTime(3, 12).atZone(z).toEpochSecond() : p.autoLastOk();
            long size = demo ? 210L * 1024 * 1024 : p.autoLastSize();
            int kept = demo ? 5 : p.autoKept();
            String error = demo ? null : p.autoError();
            card.addView(Ui.text(this, Backups.status(lastOk, size, kept, error, z), 14, error != null ? Ui.WARN : Ui.TEXT, Ui.REGULAR));
            if (error == null && lastOk > 0) card.addView(Ui.text(this, Backups.statusExplain(size, kept), 12, Ui.TEXT_3, Ui.REGULAR));
            // a saved key this phone cannot read: the copies are made without it (issue #288)
            if (!demo && p.keyState() == app.openring.core.KeyState.UNREADABLE) {
                card.addView(Ui.gap(this, 4));
                card.addView(Ui.text(this, app.openring.core.KeyState.BACKUP_NO_KEY, 13, Ui.WARN, Ui.REGULAR));
            }
            if (Backups.GONE.equals(error)) {
                small(card, Backups.CHOOSE_AGAIN, Ui.SLEEP, new Runnable() {
                    @Override
                    public void run() {
                        pickFolder();
                    }
                });
            }
            small(card, Backups.CHOOSE_OTHER, Ui.TEXT_2, demo ? notInDemo : new Runnable() {
                @Override
                public void run() {
                    pickFolder();
                }
            });
            small(card, Backups.STOP, Ui.WARN, demo ? notInDemo : new Runnable() {
                @Override
                public void run() {
                    app.openring.sync.SafFolder.release(getApplicationContext(), p.backupFolder());
                    p.setBackupFolder(null, null);
                    BackupJob.schedule(getApplicationContext());
                    toast(Backups.STOPPED);
                    fillBackupsCard(p);
                }
            });
        }
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, Backups.PRIVATE, 12, Ui.TEXT_3, Ui.REGULAR));
        // the copies made before updates, on this phone
        java.util.List<BeforeUpdate.Entry> copies = demo ? java.util.Collections.<BeforeUpdate.Entry>emptyList() : BeforeUpdate.list(this);
        if (demo || !copies.isEmpty()) {
            card.addView(Ui.gap(this, 12));
            card.addView(Ui.divider(this));
        }
        // going back runs: what it does or waits for now (a sync, Live or an analysis; review of #259)
        if (!demo && GO_BACK.running()) {
            card.addView(Ui.gap(this, 10));
            card.addView(Ui.text(this, goBackLine, 13, Ui.OXY, Ui.MEDIUM));
        }
        if (demo) {
            long made = java.time.LocalDate.now().minusDays(2).atTime(20, 41).atZone(z).toEpochSecond();
            card.addView(Ui.gap(this, 10));
            card.addView(Ui.text(this, Backups.copyLine(version(), made, 208L * 1024 * 1024, z), 14, Ui.TEXT, Ui.REGULAR));
            small(card, Backups.GO_BACK, Ui.TEXT_2, notInDemo);
        }
        for (final BeforeUpdate.Entry e : copies) {
            card.addView(Ui.gap(this, 10));
            String line = e.copy.back ? Backups.backLine(e.time, e.bytes, z) : e.usable() ? Backups.copyLine(e.copy.version, e.time, e.bytes, z)
                    : Backups.badLine(e.copy.version, e.time, z);
            card.addView(Ui.text(this, line, 14, e.usable() ? Ui.TEXT : Ui.TEXT_3, Ui.REGULAR));
            if (!e.usable()) continue;
            small(card, Backups.GO_BACK, Ui.TEXT_2, new Runnable() {
                @Override
                public void run() {
                    confirm(Backups.GO_BACK_TITLE, Backups.goBackText(e.time, java.time.ZoneId.systemDefault()), new Runnable() {
                        @Override
                        public void run() {
                            goBack(e.copy.file);
                        }
                    });
                }
            });
        }
        if (demo || !copies.isEmpty()) {
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, Backups.COPY_EXPLAIN, 12, Ui.TEXT_3, Ui.REGULAR));
        }
    }

    // ------------------------------------------------------------------- left out times (issue #254)

    /** The time picked in the Advanced card, unix seconds; -1 until the card sets its default (yesterday, midnight to midnight). */
    private long leftFrom = -1, leftTo = -1;
    private TextView leftPreview, leftFromDay, leftFromTime, leftToDay, leftToTime;
    /** A left out time is being added or removed (one at a time). */
    private static boolean leftBusy;

    private static final java.time.format.DateTimeFormatter PICK_DAY = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH),
            PICK_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /**
     * Advanced (issue #254): leave out a time when someone else wore the ring, with a preview of what goes, and the list of
     * left out times with Remove. Also in demo mode (the demo has its own history file).
     */
    void fillAdvancedCard() {
        final LinearLayout card = advancedCard;
        if (card == null) return;
        card.removeAllViews();
        final java.time.ZoneId z = java.time.ZoneId.systemDefault();
        if (leftFrom < 0 || leftTo <= 0) {
            java.time.LocalDate today = java.time.LocalDate.now(z);
            leftFrom = today.minusDays(1).atStartOfDay(z).toEpochSecond();
            leftTo = today.atStartOfDay(z).toEpochSecond();
        }
        card.addView(Ui.caption(this, "Advanced"));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, "Leave out a time when someone else wore the ring", 15, Ui.TEXT, Ui.MEDIUM));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.body(this, "Their nights and values are left out of your charts, scores and usual values. The ring's data stays stored, "
                + "and you can bring it back at any time."));
        leftFromDay = Ui.smallButton(this, "", Ui.TEXT_2);
        leftFromTime = Ui.smallButton(this, "", Ui.TEXT_2);
        leftToDay = Ui.smallButton(this, "", Ui.TEXT_2);
        leftToTime = Ui.smallButton(this, "", Ui.TEXT_2);
        pickRow(card, "From", leftFromDay, leftFromTime, true);
        pickRow(card, "To", leftToDay, leftToTime, false);
        card.addView(Ui.gap(this, 8));
        leftPreview = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        card.addView(leftPreview);
        showPicked();
        action(card, "Leave out this time", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                leaveOut();
            }
        });
        card.addView(Ui.gap(this, 12));
        card.addView(Ui.divider(this));
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, "Left out times", 12, Ui.TEXT_3, Ui.REGULAR));
        // the list is read in the background like the other cards' data (review of #263), then filled in
        final LinearLayout list = Ui.vbox(this);
        card.addView(list, Ui.matchWrap());
        final android.content.Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, java.util.List<double[]>>() {
            @Override
            protected java.util.List<double[]> doInBackground(Void... v) {
                try {
                    return Db.get(ctx).excluded();
                } catch (RuntimeException e) {
                    return new java.util.ArrayList<double[]>();
                }
            }

            @Override
            protected void onPostExecute(java.util.List<double[]> rows) {
                if (isFinishing() || isDestroyed() || advancedCard != card) return; // the card was built again meanwhile
                fillLeftList(list, rows);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, Explain.LEFT_OUT, 12, Ui.TEXT_3, Ui.REGULAR));
    }

    /** The left out times, each with Remove, or "Nothing is left out." */
    void fillLeftList(LinearLayout list, java.util.List<double[]> rows) {
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        list.removeAllViews();
        if (rows.isEmpty()) {
            list.addView(Ui.gap(this, 4));
            list.addView(Ui.text(this, "Nothing is left out.", 14, Ui.TEXT, Ui.REGULAR));
        }
        for (final double[] r : rows) {
            list.addView(Ui.gap(this, 6));
            list.addView(Ui.text(this, app.openring.core.analysis.LeftOut.listLine(r[0], r[1], z), 14, Ui.TEXT, Ui.REGULAR));
            small(list, "Remove", Ui.WARN, new Runnable() {
                @Override
                public void run() {
                    confirm("Bring this time back?", app.openring.core.analysis.LeftOut.bringBackText(r[0], java.time.ZoneId.systemDefault()), new Runnable() {
                        @Override
                        public void run() {
                            changeLeftOut(Double.NaN, Double.NaN, r[2]);
                        }
                    });
                }
            });
        }
    }

    /** "From" or "To" with a date and a time button, each opening Android's picker. */
    void pickRow(LinearLayout card, String label, final TextView day, final TextView time, final boolean isFrom) {
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, label, 12, Ui.TEXT_3, Ui.REGULAR));
        // laid out like pair(): the buttons fill the row's height, the row keeps the gap (the outline is not cut)
        LinearLayout row = Ui.hbox(this);
        row.setGravity(android.view.Gravity.TOP);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 3);
        la.setMargins(0, 0, Ui.dp(this, 4), 0);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 2);
        lb.setMargins(Ui.dp(this, 4), 0, 0, 0);
        row.addView(day, la);
        row.addView(time, lb);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 4), 0, 0);
        card.addView(row, lp);
        day.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final java.time.ZoneId z = java.time.ZoneId.systemDefault();
                final java.time.ZonedDateTime at = java.time.Instant.ofEpochSecond(isFrom ? leftFrom : leftTo).atZone(z);
                new android.app.DatePickerDialog(SettingsActivity.this, new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(android.widget.DatePicker p, int y, int m, int d) {
                        long t = java.time.LocalDate.of(y, m + 1, d).atTime(at.toLocalTime()).atZone(z).toEpochSecond();
                        if (isFrom) leftFrom = t;
                        else leftTo = t;
                        showPicked();
                    }
                }, at.getYear(), at.getMonthValue() - 1, at.getDayOfMonth()).show();
            }
        });
        time.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final java.time.ZoneId z = java.time.ZoneId.systemDefault();
                final java.time.ZonedDateTime at = java.time.Instant.ofEpochSecond(isFrom ? leftFrom : leftTo).atZone(z);
                new android.app.TimePickerDialog(SettingsActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                        long t = at.toLocalDate().atTime(h, m).atZone(z).toEpochSecond();
                        if (isFrom) leftFrom = t;
                        else leftTo = t;
                        showPicked();
                    }
                }, at.getHour(), at.getMinute(), true).show();
            }
        });
    }

    /** The picked times on their buttons, and the preview worked out in the background (it counts nights and hours). */
    void showPicked() {
        if (leftPreview == null) return;
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        leftFromDay.setText(java.time.Instant.ofEpochSecond(leftFrom).atZone(z).format(PICK_DAY));
        leftFromTime.setText(java.time.Instant.ofEpochSecond(leftFrom).atZone(z).format(PICK_TIME));
        leftToDay.setText(java.time.Instant.ofEpochSecond(leftTo).atZone(z).format(PICK_DAY));
        leftToTime.setText(java.time.Instant.ofEpochSecond(leftTo).atZone(z).format(PICK_TIME));
        final long a = leftFrom, b = leftTo;
        final android.content.Context app = getApplicationContext();
        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... v) {
                return leftText(app, a, b);
            }

            @Override
            protected void onPostExecute(String[] r) {
                SettingsActivity s = shownNow();
                if (s == null || s.leftPreview == null || s.leftFrom != a || s.leftTo != b) return; // another time was picked meanwhile
                s.leftPreview.setText(r[1] != null ? r[1] : r[0]);
                s.leftPreview.setTextColor(r[1] != null ? Ui.WARN : Ui.TEXT_2);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** {preview, problem} for leaving out [a, b); the problem is null when it can be saved. Reads the history. */
    static String[] leftText(android.content.Context c, long a, long b) {
        try {
            Db db = Db.get(c);
            java.time.ZoneId z = java.time.ZoneId.systemDefault();
            double now = System.currentTimeMillis() / 1000.0;
            String problem = db.leftOut().problem(a, b, now);
            if (problem != null) return new String[] {null, problem};
            long inside = db.nightsInside(a, b);
            return new String[] {app.openring.core.analysis.LeftOut.preview(a, b, inside, db.nightsOverlapping(a, b) - inside, db.hoursWithReadings(a, b),
                    java.time.LocalDate.now(z), now, z, Analyzer.LONG_RUN_DAYS), null};
        } catch (RuntimeException e) {
            return new String[] {"", null};
        }
    }

    /** "Leave out this time": checked again, then the row and the analysis in the background. */
    void leaveOut() {
        final long a = leftFrom, b = leftTo;
        final android.content.Context app = getApplicationContext();
        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... v) {
                return leftText(app, a, b);
            }

            @Override
            protected void onPostExecute(String[] r) {
                SettingsActivity s = shownNow();
                if (s == null) return;
                if (r[1] != null) s.problem(r[1]);
                else s.changeLeftOut(a, b, Double.NaN);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Adds [a, b) ({@code made} NaN) or removes the left out time {@code made}, then analyses (Analyzer.leaveOut, bringBack). The
     * result goes to the Settings screen shown now, also after a turn of the phone.
     */
    void changeLeftOut(final double a, final double b, final double made) {
        if (leftBusy) {
            toast("Working, please wait");
            return;
        }
        leftBusy = true;
        final boolean add = Double.isNaN(made);
        final android.content.Context app = getApplicationContext();
        toast(add ? "Leaving out this time" : "Bringing this time back");
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    if (add) Analyzer.leaveOut(app, a, b, null);
                    else Analyzer.bringBack(app, made, null);
                    RingManager.get(app).log(add ? "a time was left out" : "a left out time was brought back");
                    return true;
                } catch (Throwable e) {
                    // the change is stored and the analysis rolled back; the next analysis finishes it (issue #230)
                    RingManager.get(app).log("left out time: " + e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                leftBusy = false;
                SettingsActivity s = shownNow();
                if (s == null) {
                    if (ok) Toast.makeText(app, add ? "Left out" : "Brought back", Toast.LENGTH_LONG).show();
                    return;
                }
                if (ok) s.say(add ? "Left out. Your charts and scores are updated." : "Brought back. Your charts and scores are updated.");
                else s.problem(Texts.ANALYSIS_FAILED);
                s.fillAdvancedCard();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Android's folder picker; the app keeps its permission for the folder (no storage permission needed). */
    void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, PICK_FOLDER);
        } catch (android.content.ActivityNotFoundException e) {
            problem(Backups.FOLDER_PICK_FAILED);
        }
    }

    void folderPicked(Uri tree, int resultFlags) {
        Prefs p = Prefs.of(this);
        String name = app.openring.sync.SafFolder.keep(this, tree, resultFlags);
        if (name == null) {
            problem(Backups.FOLDER_PICK_FAILED);
            return;
        }
        String old = p.backupFolder();
        if (old != null && !old.equals(tree.toString())) app.openring.sync.SafFolder.release(getApplicationContext(), old);
        AutoBackup.folderChosen(this, tree.toString(), name);
        BackupJob.schedule(getApplicationContext());
        fillBackupsCard(p);
    }

    /** A running "Go back to this copy": one at a time, its result goes to the Settings screen shown now (pattern of #130). */
    static final app.openring.core.OneRun<SettingsActivity> GO_BACK = new app.openring.core.OneRun<SettingsActivity>();
    /** The line of the running go back, shown in the card (also on a screen rebuilt by turning the phone). */
    static volatile String goBackLine = Backups.GOING_BACK;

    void goBack(final String file) {
        if (!GO_BACK.start()) {
            toast("Going back to the copy, please wait");
            return;
        }
        final android.content.Context app = getApplicationContext();
        goBackLine = Backups.GOING_BACK;
        fillBackupsCard(Prefs.of(this));
        // the lines of a restore's wait ("Waiting for the sync with your ring to finish", ...) as Setup shows them (#219)
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final Backup.Progress progress = new Backup.Progress() {
            @Override
            public void step(final String what) {
                goBackLine = Backup.RESTORING.equals(what) ? Backups.GOING_BACK : what;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        SettingsActivity s = GO_BACK.attached();
                        if (s != null && !s.isFinishing() && !s.isDestroyed()) s.fillBackupsCard(Prefs.of(s));
                    }
                });
            }
        };
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    Backup.Result r = AutoBackup.goBack(app, file, progress);
                    RingManager.get(app).log("go back to " + file + ": " + r.message);
                    return r.message;
                } catch (Exception e) {
                    RingManager.get(app).log("go back failed: " + android.util.Log.getStackTraceString(e));
                    return "Going back did not work, nothing was changed.";
                }
            }

            @Override
            protected void onPostExecute(String m) {
                SettingsActivity s = GO_BACK.finish();
                if (s != null && !s.isFinishing() && !s.isDestroyed()) {
                    s.say(m);
                    s.preview = null; // another history: its own last night, read again
                    s.build();
                } else {
                    Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Opens at a card when started with the extra {@code section} ("blocks", "profile" (from the watch setup), "period", "stages", "watch", "screen", "games",
     * "backups" or "advanced"), used by the screen test. A card inside the Ring or Watch block was opened with its block in
     * onCreate ({@link app.openring.core.SettingsBlocks#startsOpen}); "blocks" shows both blocks closed at the top.
     * Only then the page gets an empty space at its end, so a card near the end is not stopped by the scroll limit.
     */
    void scrollToSection() {
        String section = getIntent().getStringExtra("section");
        final View card = "blocks".equals(section) ? ringBlock.card : "profile".equals(section) ? profCard : "period".equals(section) ? periodCard : "stages".equals(section) ? stagesCard
                : "watch".equals(section) ? watchCard : "phone".equals(section) ? phoneCard : "screen".equals(section) ? screenCard : "games".equals(section) ? gamesCard
                : "backups".equals(section) ? backupsCard : "advanced".equals(section) ? advancedCard : null;
        if (card == null) return;
        // the demo page is short: room below the last card, so every card can reach the top (issue #160); kept on every
        // build, so a restored scroll position finds the same page height
        root.addView(new View(this), new LinearLayout.LayoutParams(1, getResources().getDisplayMetrics().heightPixels));
        // only the first build jumps: a rebuild or a turn keeps where the person scrolled to (review of #330)
        if (sectionDone) return;
        sectionDone = true;
        jumpTo(card, false);
    }

    // ------------------------------------------------------------------- the Ring and Watch blocks (owner's wish, 3 November)

    /** One device block: a card with a header (title, a one line status, a chevron) that opens and closes its sections. */
    static final class Block {
        final int which;
        final LinearLayout card, head, body;
        final TextView status;
        final android.widget.ImageView chevron;
        boolean open;

        Block(int which, LinearLayout card, LinearLayout head, LinearLayout body, TextView status, android.widget.ImageView chevron) {
            this.which = which;
            this.card = card;
            this.head = head;
            this.body = body;
            this.status = status;
            this.chevron = chevron;
        }

        /** Adds a section below the ones there, with a line between them. */
        void add(View section) {
            if (body.getChildCount() > 0) body.addView(Ui.divider(card.getContext()));
            body.addView(section, Ui.matchWrap());
        }

        /** Whether {@code v} is this block's card or inside it. */
        boolean holds(View v) {
            for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) if (c == card) return true;
            return false;
        }
    }

    /** The Ring or Watch block, open or closed as this screen had it (closed when Settings opens). */
    Block block(final int which) {
        boolean ringOne = which == app.openring.core.SettingsBlocks.RING;
        boolean phoneOne = which == app.openring.core.SettingsBlocks.PHONE;
        Prefs p = Prefs.of(this);
        LinearLayout card = Ui.card(this);
        LinearLayout head = Ui.hbox(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.vbox(this);
        words.addView(Ui.text(this, ringOne ? app.openring.core.SettingsBlocks.RING_TITLE : phoneOne ? app.openring.core.SettingsBlocks.PHONE_TITLE
                : app.openring.core.SettingsBlocks.WATCH_TITLE, 17, Ui.TEXT, Ui.MEDIUM));
        TextView st = Ui.text(this, ringOne ? ringStatus(p) : phoneOne ? phoneStatus(p, Double.NaN) : watchStatus(p), 13, Ui.TEXT_2, Ui.REGULAR);
        words.addView(st);
        head.addView(words, Ui.weight(1));
        android.widget.ImageView chev = new android.widget.ImageView(this);
        chev.setImageResource(app.openring.R.drawable.ic_chevron_right);
        chev.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        head.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        card.addView(head, Ui.matchWrap());
        LinearLayout body = Ui.vbox(this);
        body.setPadding(0, Ui.dp(this, 14), 0, 0);
        card.addView(body, Ui.matchWrap());
        final Block b = new Block(which, card, head, body, st, chev);
        show(b, ringOne ? ringOpen : phoneOne ? phoneOpen : watchOpen);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(b, !b.open);
            }
        });
        return b;
    }

    /** Opens or closes a block; only this screen remembers it (closed again the next time Settings opens). */
    void show(Block b, boolean open) {
        b.open = open;
        b.body.setVisibility(open ? View.VISIBLE : View.GONE);
        b.chevron.setRotation(open ? 270 : 90); // down when closed, up when open
        b.head.setContentDescription((b.which == app.openring.core.SettingsBlocks.RING ? "Ring" : b.which == app.openring.core.SettingsBlocks.PHONE ? "Phone" : "Watch")
                + (open ? ", open, tap to close" : ", closed, tap to open"));
        if (b.which == app.openring.core.SettingsBlocks.RING) ringOpen = open;
        else if (b.which == app.openring.core.SettingsBlocks.PHONE) phoneOpen = open;
        else watchOpen = open;
    }

    String ringStatus(Prefs p) {
        if (PhoneViews.noRing(this)) return app.openring.core.KeyState.NO_RING_STATUS;
        return app.openring.core.SettingsBlocks.ringStatus(p.demo(), p.hardware(), p.lastSync(), p.lastSync() == 0 ? null : ago(p.lastSync()));
    }

    String watchStatus(Prefs p) {
        boolean syncing = watchSyncing || app.openring.ble.WatchSync.running();
        return app.openring.core.SettingsBlocks.watchStatus(p.demo(), p.watchAddress() != null, p.watchName(), p.watchLastOk(),
                p.watchLastOk() == 0 ? null : ago(p.watchLastOk()), syncing, p.watchError() != null);
    }

    /**
     * Scrolls to {@code target} (a card, or a section inside a block, which opens first). After the next layout, so the
     * opened block has its height (CLAUDE.md lesson: restore the scroll in post(), after layout).
     */
    void jumpTo(final View target, final boolean smooth) {
        for (Block b : new Block[] {ringBlock, watchBlock, phoneBlock}) if (b != null && !b.open && b.holds(target) && target != b.card) show(b, true);
        final android.view.ViewTreeObserver vto = scroll.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        int y = topOnPage(target) - Ui.dp(SettingsActivity.this, 8);
                        if (smooth) scroll.smoothScrollTo(0, y);
                        else scroll.scrollTo(0, y);
                    }
                });
            }
        });
        scroll.requestLayout();
    }

    /** Where {@code v} starts on the page (in the scrolled content), also for a section inside a block's card. */
    int topOnPage(View v) {
        int y = 0;
        for (View c = v; c != null && c != root; c = c.getParent() instanceof View ? (View) c.getParent() : null) y += c.getTop();
        return y;
    }

    /**
     * Sleep stage detail (issue #9): three levels, a preview of the last night with the picked level
     * (from the unfiltered stages kept with each night) and a button that saves and re analyses.
     */
    LinearLayout stageDetailCard(final Prefs p) {
        LinearLayout card = Ui.vbox(this); // a section of the Ring block
        if (detailChoice < 0) detailChoice = p.stageDetail();
        fillStageDetail(card, p);
        return card;
    }

    void fillStageDetail(LinearLayout card, final Prefs p) {
        card.removeAllViews();
        card.addView(Ui.caption(this, "Sleep stage detail"));
        card.addView(Ui.body(this, "How much detail the sleep depth chart keeps. This changes how finely the night is drawn and counted, "
                + "not how accurate the stages are."));
        card.addView(Ui.gap(this, 10));
        LinearLayout row = Ui.hbox(this);
        for (int i = 0; i < StageFilter.NAMES.length; i++) {
            final int k = i;
            TextView chip = Ui.button(this, StageFilter.NAMES[i], i == detailChoice ? Ui.SLEEP : Ui.TEXT_3, false);
            chip.setTextSize(13);
            int ph = Ui.dp(this, 10), pv = Ui.dp(this, 7);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    detailChoice = k;
                    refreshStageDetail(p);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            row.addView(chip, lp);
        }
        card.addView(row);
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.body(this, Explain.STAGE_DETAIL[detailChoice]));
        // read in the background (issue #198); until a newer read is in, the last one stays on the card
        if (!previewCurrent(p)) readPreview(p);
        Preview pv = preview;
        JSONObject night = pv == null ? null : pv.night;
        card.addView(Ui.gap(this, 12));
        if (pv == null) {
            card.addView(Ui.text(this, "Reading your last night...", 12, Ui.TEXT_3, Ui.REGULAR));
        } else if (night == null) {
            card.addView(Ui.text(this, "A preview of your last night appears here after the first night with the ring.", 12, Ui.TEXT_3, Ui.REGULAR));
        } else {
            // the same stages saving produces: filter, then phone use and the ring's own wake ups marked awake again (issue #30)
            int[] st = pv.stages[detailChoice];
            card.addView(Ui.text(this, "Your last night with this setting", 11, Ui.TEXT_3, Ui.MEDIUM));
            card.addView(Ui.gap(this, 6));
            card.addView(new Charts.Hypnogram(this, st, Json.d(night, "start"), Json.d(night, "end")).zone(Json.zone(night, java.time.ZoneId.systemDefault())), Ui.sized(this, -1, 150));
            card.addView(Ui.gap(this, 4));
            // wake ups counted exactly as on the Sleep page (issue #70)
            double stepS = st.length == 0 ? 30 : (Json.d(night, "end") - Json.d(night, "start")) / st.length;
            int wakes = app.openring.core.analysis.WakeUps.count(st, stepS);
            int brief = app.openring.core.analysis.WakeUps.shortOnes(st, stepS);
            card.addView(Ui.text(this, Texts.count(StageFilter.changes(st), "stage change", "stage changes") + ", "
                    + Texts.count(wakes, "wake up", "wake ups") + " on this chart.", 13, Ui.TEXT, Ui.MEDIUM));
            card.addView(Ui.text(this, "Stage changes: how often the chart switches between deep, light, REM and awake. "
                    + "Wake ups: awake for a minute or more between falling asleep and waking up, counted as on the Sleep page."
                    + (brief > 0 ? " Awake under a minute (" + Texts.count(brief, "time", "times") + " here) is drawn but not counted." : ""),
                    11, Ui.TEXT_3, Ui.REGULAR));
            String phone = p.screenAwake() ? Explain.previewPhone(Json.d(night, "phone_min")) : null;
            if (phone != null) card.addView(Ui.text(this, phone, 11, Ui.TEXT_3, Ui.REGULAR));
        }
        final boolean changed = detailChoice != p.stageDetail();
        action(card, changed ? "Save and update all nights" : "Saved: " + StageFilter.NAMES[p.stageDetail()],
                changed ? Ui.SLEEP : Ui.TEXT_3, new Runnable() {
            @Override
            public void run() {
                if (detailChoice == p.stageDetail()) return;
                p.setStageDetail(detailChoice);
                refreshStageDetail(p);
                reanalyse(true);
            }
        });
    }

    void refreshStageDetail(Prefs p) {
        if (stagesCard != null) fillStageDetail(stagesCard, p);
    }

    /** The last night and its preview stages for every detail level, so a chip tap needs no database read (issue #198). */
    static final class Preview {
        JSONObject night; // null: no night yet
        int[][] stages;
        boolean phone; // Count phone use as awake, as read
        int gen;
    }

    private Preview preview;
    /** Raised when the nights may have changed (a page build, new phone minutes): the preview is read again. */
    private int previewGen;
    private boolean previewReading;

    boolean previewCurrent(Prefs p) {
        return preview != null && preview.gen == previewGen && preview.phone == p.screenAwake();
    }

    /** Reads the preview in the background, then redraws the card (a newer request meanwhile reads again then). */
    void readPreview(Prefs p) {
        if (previewReading) return;
        previewReading = true;
        final int gen = previewGen;
        final boolean phone = p.screenAwake();
        final android.content.Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, Preview>() {
            @Override
            protected Preview doInBackground(Void... v) {
                Preview r = new Preview();
                r.gen = gen;
                r.phone = phone;
                r.night = lastNight();
                if (r.night != null) {
                    try {
                        r.stages = new int[StageFilter.NAMES.length][];
                        for (int i = 0; i < r.stages.length; i++) r.stages[i] = Analyzer.preview(Db.get(ctx), phone, r.night, i);
                    } catch (RuntimeException e) {
                        r.night = null; // the history cannot be read (review of #266): no preview, as without a night
                    }
                }
                return r;
            }

            @Override
            protected void onPostExecute(Preview r) {
                previewReading = false;
                if (isFinishing() || isDestroyed()) return;
                // a read from before Delete or going back to a copy (preview dropped since) is not shown: the redraw reads again
                if (r.gen == previewGen || preview != null) preview = r;
                redrawStageCard();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The Ring card's event counts, read in the background (issue #198), then shown in place of "counting...". */
    void fillCounts(LinearLayout card, LinearLayout counts) {
        countsCard = card;
        countsBox = counts;
        if (countsReading) { // one count at a time (review of #280): this page's count follows the running one
            countsAgain = true;
            return;
        }
        readCounts();
    }

    private boolean countsReading, countsAgain;
    private LinearLayout countsCard;

    private void readCounts() {
        countsReading = true;
        countsAgain = false;
        final LinearLayout card = countsCard, counts = countsBox;
        final android.content.Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... v) {
                try {
                    return Db.get(ctx).counts();
                } catch (RuntimeException e) {
                    return e; // shown as before, below
                }
            }

            @Override
            protected void onPostExecute(Object r) {
                countsReading = false;
                if (isFinishing() || isDestroyed()) return;
                if (countsAgain || countsBox != counts) { // the page was built again meanwhile (after a sync or Delete): count again
                    readCounts();
                    return;
                }
                // one more line (or the warning): what is below stays still; one line keeps the card's height
                if (!(r instanceof Db.Counts) || ((Db.Counts) r).undated > 0) keepScroll(card);
                counts.removeAllViews();
                if (!(r instanceof Db.Counts)) {
                    // a damaged history is kept (issue #255): Settings still opens, so a copy can be brought back (review of #266)
                    counts.addView(Ui.text(SettingsActivity.this, Backups.unreadable(r.toString()), 13, Ui.WARN, Ui.REGULAR));
                    return;
                }
                Db.Counts c = (Db.Counts) r;
                counts.addView(line("Stored events", String.format(Locale.getDefault(), "%,d", c.events)));
                // events Find my ring downloaded since the last sync are dated by the next one: nothing is missing (issue #192)
                if (c.undated > 0) counts.addView(line(c.allNew ? "Dated at the next sync" : "Waiting for a time anchor",
                        String.format(Locale.getDefault(), "%,d", c.undated)));
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The newest main night, or null (also when the history cannot be read, review of #266). */
    JSONObject lastNight() {
        try {
            Db db = Db.get(this);
            String day = db.latestDay();
            if (day == null) return null;
            java.util.List<String> n = db.nightsJson(java.time.LocalDate.parse(day).minusDays(2).toString(), day, true);
            return n.isEmpty() ? null : Json.parse(n.get(n.size() - 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when screen time is stored already; also when the history cannot be read, so nothing is read into it (review of #266). */
    boolean screenDaysKnown() {
        try {
            return ScreenDays.any(Db.get(this));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Version line and a button that opens the releases page in the browser (issue #7): the latest stable release, or for a nightly the list of all releases. */
    void addUpdateRow(LinearLayout parent) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout t = Ui.vbox(this);
        t.addView(Ui.text(this, "Installed version", 12, Ui.TEXT_3, Ui.REGULAR));
        t.addView(Ui.text(this, "Open Ring " + version(), 18, Ui.TEXT, Ui.MEDIUM));
        LinearLayout.LayoutParams tl = Ui.weight(1);
        tl.setMargins(0, 0, Ui.dp(this, 8), 0);
        row.addView(t, tl);
        final String url = app.openring.core.Updates.url(version());
        TextView check = Ui.smallButton(this, "Check for updates", Ui.SLEEP);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (android.content.ActivityNotFoundException e) {
                    say("No browser found. The releases are at " + url);
                }
            }
        });
        row.addView(check);
        parent.addView(row);
        parent.addView(Ui.gap(this, 6));
        parent.addView(Ui.text(this, app.openring.core.Updates.note(version()), 12, Ui.TEXT_3, Ui.REGULAR));
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    View line(String k, String v) {
        LinearLayout r = Ui.hbox(this);
        r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        r.addView(Ui.text(this, k, 14, Ui.TEXT_2, Ui.REGULAR), Ui.weight(1));
        r.addView(Ui.text(this, v, 14, Ui.TEXT, Ui.REGULAR));
        return r;
    }

    EditText num(LinearLayout parent, String label, String text) {
        parent.addView(Ui.gap(this, 8));
        parent.addView(Ui.text(this, label, 12, Ui.TEXT_3, Ui.REGULAR));
        EditText e = new EditText(this);
        e.setText(text);
        e.setTextColor(Ui.TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setBackground(Ui.input(this));
        int pd = Ui.dp(this, 10);
        e.setPadding(pd, pd, pd, pd);
        parent.addView(e, Ui.matchWrap());
        return e;
    }

    void action(LinearLayout parent, String label, int color, final Runnable r) {
        TextView b = Ui.button(this, label, color, false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 10), 0, 0);
        parent.addView(b, lp);
    }

    /** A small full width button for secondary actions. */
    void small(LinearLayout parent, String label, int color, final Runnable r) {
        TextView b = Ui.smallButton(this, label, color);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        parent.addView(b, lp);
    }

    /** Two small buttons side by side with equal widths; long labels wrap instead of overlapping. Returns their row. */
    LinearLayout pair(LinearLayout parent, String l1, int c1, final Runnable r1, String l2, int c2, final Runnable r2) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(android.view.Gravity.TOP);
        TextView a = Ui.smallButton(this, l1, c1), b = Ui.smallButton(this, l2, c2);
        a.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r1.run();
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r2.run();
            }
        });
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        la.setMargins(0, 0, Ui.dp(this, 4), 0);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lb.setMargins(Ui.dp(this, 4), 0, 0, 0);
        row.addView(a, la);
        row.addView(b, lb);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        parent.addView(row, lp);
        return row;
    }

    static String nz(String s) {
        return s == null ? "--" : s;
    }

    static String model(String hw) {
        return app.openring.core.SettingsBlocks.model(hw);
    }

    static String ago(long unix) {
        if (unix <= 0) return "--";
        return DateUtils.getRelativeTimeSpanString(unix * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /**
     * A toast, with the application context so it still shows when Settings was closed or turned meanwhile.
     * Only for short texts: Android 12 and newer cut toasts after two lines (issue #89), use {@link #say}.
     */
    void toast(String m) {
        Toast.makeText(getApplicationContext(), m, Toast.LENGTH_LONG).show();
    }

    /** The full text and stack trace of a failure in the Log tab; the data check gets only its class and place (issue #289). Never throws. */
    void logFailure(String what, Throwable e) {
        try {
            RingManager.get(getApplicationContext()).log(what + ": " + android.util.Log.getStackTraceString(e));
        } catch (Throwable ignored) {
            // the stored note for the data check is written anyway
        }
    }

    /** A note: a toast when it fits in two lines, otherwise the small dialog so nothing is cut (issue #89). */
    void say(String m) {
        if (Texts.fitsToast(m)) toast(m);
        else dialog(null, m);
    }

    /** A failed action: its plain sentence says what to do, which a toast would cut after two lines (issues #60, #89). */
    void problem(String m) {
        dialog("Not done", m);
    }

    void dialog(String title, String m) {
        if (isFinishing() || isDestroyed()) {
            toast(m); // Settings was closed or recreated meanwhile: a toast rather than nothing (code review of #82)
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null);
        if (title != null) b.setTitle(title);
        b.show();
    }

    void confirm(String title, String msg, final Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        yes.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /**
     * The running Delete all local data (issue #43). Static, so a turned phone (a new Settings screen)
     * gets the result and is rebuilt, and a second tap cannot start a second delete (pattern of #130).
     */
    static final app.openring.core.OneRun<SettingsActivity> DELETE = new app.openring.core.OneRun<SettingsActivity>();

    @Override
    protected void onDestroy() {
        DELETE.detach(this);
        GO_BACK.detach(this);
        super.onDestroy();
    }

    /**
     * Delete all local data, confirmed by the person. Waits for a running analysis and holds the ring
     * lock, so nothing is written back afterwards; refused while a sync or another ring task runs
     * (issue #43). Off the screen thread: a Re analyse all can take minutes to finish first.
     */
    void deleteAll() {
        if (!DELETE.start()) {
            toast("Deleting, please wait");
            return;
        }
        final android.content.Context app = getApplicationContext();
        toast("Deleting");
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    return RingManager.get(app).whileRingFree(new Runnable() {
                        @Override
                        public void run() {
                            Analyzer.deleteAll(app);
                            // the copies before updates hold the same history; the folder's copies stay (issue #242)
                            BeforeUpdate.deleteAll(app);
                        }
                    });
                } catch (RuntimeException e) {
                    // one transaction: nothing was deleted
                    RingManager.get(app).log("delete failed: " + android.util.Log.getStackTraceString(e));
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                // the Settings screen shown now, also after the phone was turned meanwhile
                SettingsActivity s = DELETE.finish();
                boolean shown = s != null && !s.isFinishing() && !s.isDestroyed();
                if (ok == null || !ok) {
                    // the full text in the dialog; the short one when Settings is gone, a toast is cut after two lines
                    if (shown) s.problem(ok == null ? Texts.DELETE_FAILED : Texts.DELETE_BUSY);
                    else Toast.makeText(app, ok == null ? Texts.DELETE_FAILED_TOAST : Texts.DELETE_BUSY_TOAST, Toast.LENGTH_LONG).show();
                    return;
                }
                RingManager.get(app).log("all local data deleted");
                Toast.makeText(app, "Deleted", Toast.LENGTH_LONG).show();
                if (shown) {
                    s.preview = null; // the deleted night is not shown again while the preview is read
                    s.build();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Re analyse all (issue #222): the same job as Data check's Repair, one at a time through {@link LogActivity#REPAIR},
     * holding only the application; its result goes to the Settings screen shown now. {@code setting}: a saved setting
     * that needs it (profile, stage detail); when the job runs already it runs once more after that, instead of a
     * second run beside it. A tap on Re analyse all while it runs starts nothing.
     */
    void reanalyse(boolean setting) {
        boolean started = setting ? LogActivity.REPAIR.startOrAgain() : LogActivity.REPAIR.start();
        if (!started) {
            say(setting ? Texts.REANALYSE_AGAIN : Texts.REANALYSE_RUNS);
            showJobState();
            return;
        }
        // a sync or Live storing data finishes first (issue #230); say so instead of waiting without a word, in full (issue #274)
        say(RingManager.get(this).busy() ? Texts.REANALYSE_WAITS : Texts.REANALYSING);
        showJobState();
        LogActivity.analyseAll(getApplicationContext(), true);
    }

    /**
     * The lines and buttons of the long jobs as they are now (issue #222): a backup or export being saved (by this screen
     * or the automatic backup) fades the four file buttons and says what is saved; Re analyse all (also a Repair started
     * in Data check) fades its button and says it runs. The buttons still answer a tap, with why nothing starts.
     */
    void showJobState() {
        boolean saving = AutoBackup.RUN.running();
        String line = saving ? (savingLine != null ? savingLine : Texts.SAVING_AUTO) : null;
        for (TextView note : new TextView[] {saveNote, exportNote}) {
            if (note == null) continue;
            note.setText(line == null ? "" : line);
            note.setVisibility(line == null ? View.GONE : View.VISIBLE);
        }
        for (View row : new View[] {saveRow, exportRow}) if (row != null) row.setAlpha(saving ? 0.4f : 1f);
        // the automatic backup does not tell this screen when it ends: look again every few seconds while it holds the lock
        if (saving && savingLine == null && saveNote != null && !recheckPosted) {
            recheckPosted = true;
            saveNote.postDelayed(new Runnable() {
                @Override
                public void run() {
                    recheckPosted = false;
                    if (!isFinishing() && !isDestroyed()) showJobState();
                }
            }, 5000);
        }
        boolean analysing = LogActivity.REPAIR.running();
        if (reanalyseNote != null) reanalyseNote.setVisibility(analysing ? View.VISIBLE : View.GONE);
        if (reanalyseButton != null) reanalyseButton.setAlpha(analysing ? 0.4f : 1f);
    }

    void factoryReset() {
        final Prefs p = Prefs.of(this);
        final EditText e = new EditText(this);
        e.setHint(nz(p.serial()));
        new AlertDialog.Builder(this).setTitle("Factory reset the ring")
                .setMessage("This erases the ring's key, its Bluetooth bonds and any data not yet synced. Sync first. Type the ring serial ("
                        + nz(p.serial()) + ") to confirm.")
                .setView(e)
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        RingManager.get(SettingsActivity.this).factoryReset(e.getText().toString(), new RingManager.Callback() {
                            @Override
                            public void done(boolean ok, String message) {
                                if (!ok) {
                                    ringDone(false, message, null, false);
                                    return;
                                }
                                // Settings closes now, so a dialog would vanish: a short toast, Setup is where it is paired again.
                                // Not this page's own startActivity and finish: after a turn of the phone it is gone and the
                                // new Settings screen would stay under Setup (issue #59)
                                toast(message);
                                SettingsActivity s = shownNow();
                                if (s != null) {
                                    SHOWN.detach(s); // not kept as a closed screen
                                    s.finish();
                                }
                                toSetup(getApplicationContext());
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The Settings screen shown now, null when none is (closed, or closing). */
    static SettingsActivity shownNow() {
        SettingsActivity s = SHOWN.attached();
        return s != null && !s.isFinishing() && !s.isDestroyed() ? s : null;
    }

    /**
     * A measurement switch or read ended (issue #59): the Settings screen shown now takes the states
     * ({@code modes}, from the page that started it) and the message, also when the phone was turned
     * meanwhile. With Settings closed only a toast is left. {@code sayIt}: show a success message too.
     */
    void ringDone(boolean ok, String message, int[] modes, boolean sayIt) {
        SettingsActivity s = shownNow();
        if (s == null) {
            if (message != null && (!ok || sayIt)) toast(message);
            return;
        }
        if (!ok) {
            s.problem(message);
            return;
        }
        if (sayIt) s.say(message);
        System.arraycopy(modes, 0, s.featureModes, 0, s.featureModes.length);
        s.build();
    }

    /**
     * Back to the main screen with every screen above it closed (Settings, also one built by turning
     * the phone); without a key its onResume opens Setup. From the application context, so it does
     * not depend on a screen that may be gone. Android ignores it while the app is in the background;
     * then onResume here or on the main screen does it on return.
     */
    static void toSetup(Context app) {
        app.startActivity(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    void create(String name, String mime, int code) {
        // one file at a time, also with the automatic backup (issue #222): asked before Android makes a file
        if (AutoBackup.RUN.running()) {
            say(Texts.SAVE_BUSY);
            showJobState();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(final int code, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (code == PICK_FOLDER) {
            folderPicked(data.getData(), data.getFlags());
            return;
        }
        Uri uri = data.getData();
        boolean backup = code == BACKUP_KEY || code == BACKUP_FULL;
        // the automatic backup may have started while the file was picked: the empty file goes, nothing else starts
        boolean free = AutoBackup.RUN.start();
        if (free) savingLine = backup ? Texts.SAVING_BACKUP : Texts.SAVING_EXPORT;
        showJobState();
        new SaveTask(getApplicationContext(), uri, code, !free).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** A look again at the automatic backup's line is waiting (see {@link #showJobState}). */
    private boolean recheckPosted;

    /** What the running save of this screen writes, for its line; null when the automatic backup holds {@link AutoBackup#RUN}. */
    static volatile String savingLine;

    /**
     * Saving a backup or export file (issue #222). Holds only the application, never a screen, so turning the phone or
     * leaving Settings does not cut it, and its result goes to the Settings screen shown now. It holds
     * {@link AutoBackup#RUN} (started by the screen), so a second save or the automatic backup waits. A file that fails on
     * the way is removed ({@link app.openring.core.FileSave}), or called incomplete when it cannot be.
     */
    private static final class SaveTask extends AsyncTask<Void, Void, String> {
        private final Context ctx;
        private final Uri uri;
        private final int code;
        private final boolean backup;
        /** The automatic backup ran when the file came back: only the empty file is removed. */
        private final boolean busy;
        private boolean failed;
        private String shortText;

        SaveTask(Context ctx, Uri uri, int code, boolean busy) {
            this.ctx = ctx;
            this.uri = uri;
            this.code = code;
            this.backup = code == BACKUP_KEY || code == BACKUP_FULL;
            this.busy = busy;
        }

        @Override
        protected String doInBackground(Void... v) {
            if (busy) {
                // only a new, empty file goes: a file the person picked to replace is not opened and stays as it was
                removeIfEmpty();
                return Texts.SAVE_BUSY;
            }
            try {
                return save();
            } finally {
                savingLine = null;
                AutoBackup.RUN.finish();
            }
        }

        /**
         * Removes the picked file only when it is empty (the new file Android made for the picker): a file the person
         * picked to replace, which was never opened, stays as it was (review of #340).
         */
        private void removeIfEmpty() {
            android.database.Cursor cur = null;
            try {
                cur = ctx.getContentResolver().query(uri, new String[] {android.provider.DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
                if (cur == null || !cur.moveToFirst() || cur.isNull(0) || cur.getLong(0) != 0) {
                    RingManager.get(ctx).log("the picked file was not opened and stays as it was");
                    return;
                }
                android.provider.DocumentsContract.deleteDocument(ctx.getContentResolver(), uri);
            } catch (Exception x) {
                RingManager.get(ctx).log("the empty file could not be removed: " + x);
            } finally {
                if (cur != null) cur.close();
            }
        }

        private String save() {
            String what = backup ? "backup" : "export";
            // the checked copy of the history first, before the picked file is opened (review of #340): a copy that fails
            // its check leaves that file as it was
            Backup.Begun begun = null;
            java.io.File snap = null;
            try {
                if (backup) begun = Backup.begin(ctx, code == BACKUP_FULL);
                else if (code == EXPORT_DB) snap = Exporter.snapshot(ctx);
            } catch (Exception e) {
                Backup.release(begun);
                failed = true;
                removeIfEmpty();
                if (e instanceof Exporter.NotSaved) {
                    // the copy of the history did not pass its check (issue #115): nothing was written
                    RingManager.get(ctx).log(what + " not saved: " + e.getMessage());
                    shortText = Texts.saveFailedToast(backup, false);
                    return Texts.notChecked(backup);
                }
                RingManager.get(ctx).log(what + " failed before the file was opened: " + android.util.Log.getStackTraceString(e));
                shortText = Texts.saveFailedToast(backup, false);
                return Texts.saveFailed(backup, false);
            }
            final Backup.Begun b = begun;
            final java.io.File copy = snap;
            final boolean[] withKey = {true};
            app.openring.core.FileSave.Outcome o;
            try {
                o = app.openring.core.FileSave.save(new app.openring.core.FileSave.Target() {
                    @Override
                    public OutputStream open() throws Exception {
                        return ctx.getContentResolver().openOutputStream(uri);
                    }

                    @Override
                    public boolean delete() throws Exception {
                        return android.provider.DocumentsContract.deleteDocument(ctx.getContentResolver(), uri);
                    }
                }, new app.openring.core.FileSave.Body() {
                    @Override
                    public void write(OutputStream os) throws Exception {
                        if (code == EXPORT_CSV) Exporter.dailyCsv(ctx, os);
                        else if (b != null) withKey[0] = Backup.write(b, os);
                        else Exporter.stream(copy, os);
                    }
                });
            } finally {
                Backup.release(b);
                Exporter.release(copy);
            }
            if (o.ok()) {
                if (!withKey[0]) RingManager.get(ctx).log("backup saved without the ring key: this phone cannot read it (" + Prefs.keyError() + ")");
                return backup ? (withKey[0] ? "Backup saved" : app.openring.core.KeyState.BACKUP_SAVED_NO_KEY) : "Exported";
            }
            failed = true;
            if (o.removeError != null) RingManager.get(ctx).log("the " + what + " file that was started could not be removed: " + o.removeError);
            // the raw reason goes to the log, the screen gets a sentence that says what to do (issue #89)
            RingManager.get(ctx).log(what + " failed after " + o.bytes + " bytes" + (o.removed ? ", the file was removed" : o.leftOver ? ", the incomplete file stays" : "")
                    + ": " + android.util.Log.getStackTraceString(o.error));
            shortText = Texts.saveFailedToast(backup, o.leftOver);
            return Texts.saveFailed(backup, o.leftOver);
        }

        @Override
        protected void onPostExecute(String m) {
            SettingsActivity s = shownNow();
            if (s == null) {
                // Settings was closed or is being rebuilt: a toast, cut after two lines, so the short text (issue #89)
                Toast.makeText(ctx, shortText != null ? shortText : m, Toast.LENGTH_LONG).show();
                return;
            }
            s.showJobState();
            if (failed) s.problem(m);
            else s.say(m);
        }
    }
}
