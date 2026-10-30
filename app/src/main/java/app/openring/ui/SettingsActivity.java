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
        if (b != null) { // the phone was turned: typed numbers and choices come back (issue #133)
            drafts = Drafts.fromArray(b.getStringArray("drafts"));
            sexChoice = b.getInt("sex", -1);
            detailChoice = b.getInt("detail", -1);
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
        if (!p.demo() && (!p.setupDone() || !p.hasKey())) {
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
        if (!screenRead && !p.demo() && ScreenUse.supported() && allowedNow && !ScreenDays.any(Db.get(this))) {
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
            stagesCard = stageDetailCard(p);
            root.addView(stagesCard);
            screenCard = Ui.card(this);
            fillScreenCard(p);
            root.addView(screenCard);
            gamesCard = app.openring.ui.games.GamesActivity.settingsCard(this);
            root.addView(gamesCard);
            backupsCard = Ui.card(this);
            fillBackupsCard(p);
            root.addView(backupsCard);
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

        // ---- ring ----
        LinearLayout ring = Ui.card(this);
        ring.addView(Ui.caption(this, "Ring"));
        ring.addView(line("Serial", nz(p.serial())));
        ring.addView(line("Model", model(p.hardware())));
        ring.addView(line("Firmware", nz(p.firmware())));
        ring.addView(line("Battery", p.battery() < 0 ? "--" : p.battery() + "%  (" + ago(p.batteryAt()) + ")"));
        ring.addView(line("Last sync", p.lastSync() == 0 ? "never" : ago(p.lastSync())));
        Db db = Db.get(this);
        ring.addView(line("Stored events", String.format(Locale.getDefault(), "%,d", db.eventCount())));
        long undated = db.undatedCount();
        // events Find my ring downloaded since the last sync are dated by the next one: nothing is missing (issue #192)
        if (undated > 0) ring.addView(line(db.undatedAllNew() ? "Dated at the next sync" : "Waiting for a time anchor",
                String.format(Locale.getDefault(), "%,d", undated)));
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
        root.addView(ring);

        // ---- profile ----
        LinearLayout prof = Ui.card(this);
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
        prof.addView(sexRow, Ui.matchWrap());
        final EditText target = kcalIn = num(prof, "Daily active calorie goal (0 = automatic)", drafts.text("kcal", p.targetKcal()));
        action(prof, "Save and re analyse", Ui.ACTIVE, new Runnable() {
            @Override
            public void run() {
                p.setProfile(SetupActivity.num(age, 35, 10, 100), sexChoice, SetupActivity.num(weight, 72, 30, 250),
                        SetupActivity.num(height, 175, 100, 230), SetupActivity.num(target, 0, 0, 3000));
                // show what was really saved (a number out of range is saved at its limit)
                age.setText(String.valueOf(p.age()));
                weight.setText(String.valueOf(p.weightKg()));
                height.setText(String.valueOf(p.heightCm()));
                target.setText(String.valueOf(p.targetKcal()));
                reanalyse();
            }
        });
        root.addView(prof);

        // ---- sleep stage detail ----
        stagesCard = stageDetailCard(p);
        root.addView(stagesCard);

        // ---- screen time ----
        screenCard = Ui.card(this);
        fillScreenCard(p);
        root.addView(screenCard);

        // ---- ring games (issue #15) ----
        gamesCard = app.openring.ui.games.GamesActivity.settingsCard(this);
        root.addView(gamesCard);

        // ---- measurements ----
        LinearLayout feat = Ui.card(this);
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
        root.addView(feat);

        // ---- key ----
        LinearLayout key = Ui.card(this);
        key.addView(Ui.caption(this, "Key and backup"));
        key.addView(Ui.body(this, "Keep a copy of the key. Without it you would have to factory reset the ring to connect again. "
                + "A backup file restores the app on a new phone from the setup screen (Restore from a backup)."));
        key.addView(Ui.gap(this, 6));
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
        key.addView(Ui.gap(this, 10));
        key.addView(Ui.text(this, "Save a backup file", 12, Ui.TEXT_3, Ui.REGULAR));
        pair(key, "Key and settings only", Ui.SLEEP, new Runnable() {
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
        key.addView(Ui.gap(this, 6));
        key.addView(Ui.text(this, "The backup holds your ring key in readable form. Keep it as private as the key itself.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(key);

        // ---- automatic backups (issue #242) ----
        backupsCard = Ui.card(this);
        fillBackupsCard(p);
        root.addView(backupsCard);

        // ---- data ----
        LinearLayout data = Ui.card(this);
        data.addView(Ui.caption(this, "Export"));
        data.addView(Ui.body(this, "For spreadsheets or your own analysis. These files hold no key."));
        pair(data, "Daily summaries (CSV)", Ui.TEXT_2, new Runnable() {
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
        root.addView(data);

        // ---- help ----
        LinearLayout help = Ui.card(this);
        help.addView(Ui.caption(this, "Help and diagnostics"));
        help.addView(Ui.body(this, "Data check follows your data from the ring to the screens and shows where it stops. Safe to share. "
                + "Re analyse works out all nights and days again from the stored ring data. With a long history this can take a few minutes."));
        pair(help, "Data check and log", Ui.READY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, LogActivity.class));
            }
        }, "Re analyse all", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                reanalyse();
            }
        });
        root.addView(help);

        // ---- danger ----
        LinearLayout danger = Ui.card(this);
        danger.addView(Ui.caption(this, "Careful"));
        danger.addView(Ui.text(this, "Each of these asks before it does anything.", 12, Ui.TEXT_3, Ui.REGULAR));
        small(danger, "Use a different key or ring", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                confirm("Forget this ring?", "The saved key is removed from this phone. Your synced history stays. If you restore a full backup later, the app asks before it replaces this history.", new Runnable() {
                    @Override
                    public void run() {
                        p.clearKey();
                        p.setSetupDone(false);
                        startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
                        finish();
                    }
                });
            }
        });
        small(danger, "Factory reset the ring", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                factoryReset();
            }
        });
        small(danger, "Delete all local data", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                confirm("Delete everything?", "All synced events and results on this phone are erased. The ring keeps its key. Copies in your backup folder stay.", new Runnable() {
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
        String[] sections = {"Updates", "Ring", "Profile", "Sleep stages", "Screen time", "Games", "Measurements", "Key and backup", "Backups", "Export", "Help",
            "Careful", "About"};
        View[] targets = {updates, ring, prof, stagesCard, screenCard, gamesCard, feat, key, backupsCard, data, help, danger, about};
        for (int i = 0; i < sections.length; i++) {
            final View t = targets[i];
            TextView chip = Ui.button(this, sections[i], Ui.TEXT_2, false);
            chip.setTextSize(13);
            int ph = Ui.dp(this, 14), pv = Ui.dp(this, 7);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scroll.smoothScrollTo(0, t.getTop() - Ui.dp(SettingsActivity.this, 8));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            chips.addView(chip, lp);
        }
        scrollToSection();
    }

    private LinearLayout screenCard, gamesCard, backupsCard;
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
                p.setScreenGoalMin(SetupActivity.num(goal, 180, 0, 1440));
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
                } catch (Throwable ignored) {
                    // the analysis rolled back and stores its own error for the data check, also out of memory (issue #230)
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
        final LinearLayout card = stagesCard;
        if (card == null) return;
        final int y = scroll.getScrollY(), top = card.getTop(), before = card.getHeight();
        // the new height is known once the card is laid out; the scroll then follows in post() (CLAUDE.md lesson)
        card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                final int to = app.openring.core.ScrollKeep.after(y, top, before, b - t);
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.scrollTo(0, to);
                    }
                });
            }
        });
        fillStageDetail(card, Prefs.of(this));
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
            lp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), Ui.dp(this, 4), i == SEXES.length - 1 ? 0 : Ui.dp(this, 4), 0);
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
                    s.build();
                } else {
                    Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Opens at a card when started with the extra {@code section} ("stages", "screen", "games" or "backups"), used by the screen test.
     * Only then the page gets an empty space at its end, so a card near the end is not stopped by the scroll limit.
     */
    void scrollToSection() {
        String section = getIntent().getStringExtra("section");
        final View card = "stages".equals(section) ? stagesCard : "screen".equals(section) ? screenCard : "games".equals(section) ? gamesCard
                : "backups".equals(section) ? backupsCard : null;
        if (card == null) return;
        // the demo page is short: room below the last card, so every card can reach the top (issue #160)
        root.addView(new View(this), new LinearLayout.LayoutParams(1, getResources().getDisplayMetrics().heightPixels));
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, card.getTop() - Ui.dp(SettingsActivity.this, 8));
            }
        });
    }

    /**
     * Sleep stage detail (issue #9): three levels, a preview of the last night with the picked level
     * (from the unfiltered stages kept with each night) and a button that saves and re analyses.
     */
    LinearLayout stageDetailCard(final Prefs p) {
        LinearLayout card = Ui.card(this);
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
        JSONObject night = lastNight();
        card.addView(Ui.gap(this, 12));
        if (night == null) {
            card.addView(Ui.text(this, "A preview of your last night appears here after the first night with the ring.", 12, Ui.TEXT_3, Ui.REGULAR));
        } else {
            // the same stages saving produces: filter, then phone use and the ring's own wake ups marked awake again (issue #30)
            int[] st = Analyzer.preview(Db.get(this), p.screenAwake(), night, detailChoice);
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
                reanalyse();
            }
        });
    }

    void refreshStageDetail(Prefs p) {
        if (stagesCard != null) fillStageDetail(stagesCard, p);
    }

    /** The newest main night, or null. */
    JSONObject lastNight() {
        Db db = Db.get(this);
        String day = db.latestDay();
        if (day == null) return null;
        java.util.List<String> n = db.nightsJson(java.time.LocalDate.parse(day).minusDays(2).toString(), day, true);
        return n.isEmpty() ? null : Json.parse(n.get(n.size() - 1));
    }

    /** Version line and a button that opens the releases page in the browser (issue #7). */
    void addUpdateRow(LinearLayout parent) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout t = Ui.vbox(this);
        t.addView(Ui.text(this, "Installed version", 12, Ui.TEXT_3, Ui.REGULAR));
        t.addView(Ui.text(this, "Open Ring " + version(), 18, Ui.TEXT, Ui.MEDIUM));
        LinearLayout.LayoutParams tl = Ui.weight(1);
        tl.setMargins(0, 0, Ui.dp(this, 8), 0);
        row.addView(t, tl);
        TextView check = Ui.smallButton(this, "Check for updates", Ui.SLEEP);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)));
                } catch (android.content.ActivityNotFoundException e) {
                    say("No browser found. The releases are at " + RELEASES);
                }
            }
        });
        row.addView(check);
        parent.addView(row);
        parent.addView(Ui.gap(this, 6));
        parent.addView(Ui.text(this, "Opens the releases page in your browser, because this app has no internet access. "
                + "A higher version number there means an update; installing it keeps your data.", 12, Ui.TEXT_3, Ui.REGULAR));
    }

    static final String RELEASES = "https://github.com/busssard/OpenRing/releases/latest";

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

    /** Two small buttons side by side with equal widths; long labels wrap instead of overlapping. */
    void pair(LinearLayout parent, String l1, int c1, final Runnable r1, String l2, int c2, final Runnable r2) {
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
    }

    static String nz(String s) {
        return s == null ? "--" : s;
    }

    static String model(String hw) {
        if (hw == null) return "--";
        if (hw.startsWith("BLB")) return "Gen 3 Horizon (" + hw + ")";
        if (hw.startsWith("ORE") || hw.endsWith("_04") || hw.endsWith("_06")) return "Ring 4 (" + hw + ")";
        if (hw.endsWith("_05")) return "Ring 5 (" + hw + ")";
        return hw;
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
                    String m = ok == null ? Texts.DELETE_FAILED : Texts.DELETE_BUSY;
                    if (shown) s.problem(m);
                    else Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                    return;
                }
                RingManager.get(app).log("all local data deleted");
                Toast.makeText(app, "Deleted", Toast.LENGTH_LONG).show();
                if (shown) s.build();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void reanalyse() {
        // a sync or Live storing data finishes first (issue #230); say so instead of waiting without a word
        toast(RingManager.get(this).busy() ? Texts.REANALYSE_WAITS : "Re analysing all nights, this can take a few minutes.");
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    Analyzer.runAll(SettingsActivity.this, null);
                    return true;
                } catch (Throwable e) {
                    // rolled back, also out of memory; the analysis stores its own error for the data check (issue #230)
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (ok) toast("Analysis updated");
                else problem(Texts.ANALYSIS_FAILED);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
        final Uri uri = data.getData();
        final boolean backup = code == BACKUP_KEY || code == BACKUP_FULL;
        // set when the copy of the history did not pass its check (issue #115): the file got nothing
        final boolean[] notChecked = {false};
        // bytes that reached the chosen file; a failure before the first one leaves an empty file
        final long[] written = {0};
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                boolean opened = false;
                try {
                    OutputStream raw = getContentResolver().openOutputStream(uri);
                    if (raw == null) throw new java.io.IOException("Android gave no file to write to");
                    opened = true;
                    OutputStream os = new java.io.FilterOutputStream(raw) {
                        @Override
                        public void write(int b) throws java.io.IOException {
                            out.write(b);
                            written[0]++;
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws java.io.IOException {
                            out.write(b, off, len);
                            written[0] += len;
                        }
                    };
                    try {
                        if (code == EXPORT_CSV) Exporter.dailyCsv(SettingsActivity.this, os);
                        else if (code == BACKUP_KEY || code == BACKUP_FULL) app.openring.data.Backup.write(SettingsActivity.this, os, code == BACKUP_FULL);
                        else Exporter.database(SettingsActivity.this, os);
                    } finally {
                        os.close();
                    }
                    return backup ? "Backup saved" : "Exported";
                } catch (Exporter.NotSaved e) {
                    RingManager.get(SettingsActivity.this).log((backup ? "backup" : "export") + " not saved: " + e.getMessage());
                    notChecked[0] = true;
                    removeEmpty(opened);
                    return null;
                } catch (Exception e) {
                    // the raw reason goes to the log, the screen gets a sentence that says what to do (issue #89)
                    RingManager.get(SettingsActivity.this).log((backup ? "backup" : "export") + " failed: " + android.util.Log.getStackTraceString(e));
                    removeEmpty(opened);
                    return null;
                }
            }

            /** Nothing reached the file: remove the empty file Android created, so it is not taken for a backup later. */
            void removeEmpty(boolean opened) {
                if (!opened || written[0] > 0) return;
                try {
                    android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
                } catch (Exception x) {
                    RingManager.get(SettingsActivity.this).log("the empty file could not be removed: " + x);
                }
            }

            @Override
            protected void onPostExecute(String r) {
                if (r == null) problem(notChecked[0] ? Texts.notChecked(backup) : Texts.saveFailed(backup));
                else say(r);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
