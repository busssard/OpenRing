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
import app.openring.core.Hex;
import app.openring.core.Protocol;
import org.json.JSONObject;

import app.openring.core.analysis.StageFilter;
import app.openring.data.Analyzer;
import app.openring.data.Db;
import app.openring.data.Exporter;
import app.openring.data.Json;
import app.openring.data.Prefs;
import app.openring.data.ScreenDays;
import app.openring.data.ScreenUse;
import app.openring.sync.SyncJob;
import app.openring.sync.SyncService;

/** Ring, profile, key, data and advanced settings. */
public final class SettingsActivity extends Activity implements RingManager.Listener {
    private static final int EXPORT_CSV = 11, EXPORT_DB = 12, BACKUP_KEY = 13, BACKUP_FULL = 14;
    private ScrollView scroll;
    private LinearLayout chips;
    private LinearLayout root;
    private TextView status;
    private final int[] featureModes = {-1, -1, -1};
    /** Detail level picked in the Sleep stage detail card, saved only with its button. */
    private int detailChoice = -1;
    private LinearLayout stagesCard;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RingManager.get(this).addListener(this);
        // back from Android's usage access page: show the new state, and read the screen log the first time
        Prefs p = Prefs.of(this);
        boolean allowedNow = ScreenUse.allowed(this);
        if (screenCard != null && allowedNow != screenAllowedShown) fillScreenCard(p); // keeps a goal being typed otherwise
        if (gamesCard != null) app.openring.ui.games.GamesActivity.fill(this, gamesCard); // new best scores
        if (!screenRead && !p.demo() && ScreenUse.supported() && allowedNow && !ScreenDays.any(Db.get(this))) {
            screenRead = true;
            updateNights("Reading screen time");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        RingManager.get(this).removeListener(this);
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
        root.removeAllViews();
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
                    app.openring.data.Demo.leave(SettingsActivity.this);
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
        if (undated > 0) ring.addView(line("Waiting for a time anchor", String.format(Locale.getDefault(), "%,d", undated)));
        action(ring, "Sync now", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                if (!Permissions.granted(SettingsActivity.this)) {
                    Permissions.request(SettingsActivity.this);
                    return;
                }
                SyncService.start(SettingsActivity.this);
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
        final EditText age = num(column(body, 0), "Age", p.age());
        final EditText weight = num(column(body, 1), "Weight (kg)", p.weightKg());
        final EditText height = num(column(body, 2), "Height (cm)", p.heightCm());
        prof.addView(body, Ui.matchWrap());
        if (sexChoice < 0) sexChoice = p.sex();
        prof.addView(Ui.gap(this, 8));
        prof.addView(Ui.text(this, "Sex", 12, Ui.TEXT_3, Ui.REGULAR));
        final LinearLayout sexRow = Ui.hbox(this);
        fillSex(sexRow);
        prof.addView(sexRow, Ui.matchWrap());
        final EditText target = num(prof, "Daily active calorie goal (0 = automatic)", p.targetKcal());
        action(prof, "Save and re analyse", Ui.ACTIVE, new Runnable() {
            @Override
            public void run() {
                p.setProfile(SetupActivity.num(age, 35, 10, 100), sexChoice, SetupActivity.num(weight, 72, 30, 250),
                        SetupActivity.num(height, 175, 100, 230), SetupActivity.num(target, 0, 0, 3000));
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
                    RingManager.get(SettingsActivity.this).setFeature(RingManager.FEATURES[k], mode, new RingManager.Callback() {
                        @Override
                        public void done(boolean ok, String message) {
                            if (!ok) {
                                problem(message);
                                return;
                            }
                            toast(message);
                            featureModes[k] = mode;
                            build();
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
                toast("Copied");
            }
        });
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
                confirm("Delete everything?", "All synced events and results on this phone are erased. The ring keeps its key.", new Runnable() {
                    @Override
                    public void run() {
                        Db.get(SettingsActivity.this).resetAll();
                        toast("Deleted");
                        build();
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
        String[] sections = {"Updates", "Ring", "Profile", "Sleep stages", "Screen time", "Games", "Measurements", "Key and backup", "Export", "Help", "Careful", "About"};
        View[] targets = {updates, ring, prof, stagesCard, screenCard, gamesCard, feat, key, data, help, danger, about};
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

    private LinearLayout screenCard, gamesCard;
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
        final EditText goal = num(card, "Daily goal in minutes (0 for no goal, the score then uses 3 hours)", p.screenGoalMin());
        small(card, "Save goal", Ui.OXY, new Runnable() {
            @Override
            public void run() {
                p.setScreenGoalMin(SetupActivity.num(goal, 180, 0, 1440));
                toast("Goal saved");
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
        if (message != null) toast(message);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                try {
                    ScreenUse.collect(SettingsActivity.this, true);
                    Analyzer.run(SettingsActivity.this, null);
                } catch (RuntimeException ignored) {
                    // the analysis stores its own error for the data check
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Reads which measurements are switched on and shows them. */
    void readFromRing() {
        RingManager.get(this).readFeatures(new RingManager.Callback() {
            @Override
            public void done(boolean ok, String message) {
                if (!ok) {
                    problem(message);
                    return;
                }
                for (String part : message.split(",")) {
                    String[] kv = part.split(":");
                    int f = Integer.parseInt(kv[0]), m = Integer.parseInt(kv[1]);
                    for (int i = 0; i < 3; i++) if (RingManager.FEATURES[i] == f) featureModes[i] = m;
                }
                build();
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

    /** Opens at a card when started with the extra {@code section} ("stages", "screen" or "games"), used by the screen test. */
    void scrollToSection() {
        String section = getIntent().getStringExtra("section");
        final View card = "stages".equals(section) ? stagesCard : "screen".equals(section) ? screenCard : "games".equals(section) ? gamesCard : null;
        if (card == null) return;
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
        card.addView(Ui.body(this, DETAIL_TEXTS[detailChoice]));
        JSONObject night = lastNight();
        card.addView(Ui.gap(this, 12));
        if (night == null) {
            card.addView(Ui.text(this, "A preview of your last night appears here after the first night with the ring.", 12, Ui.TEXT_3, Ui.REGULAR));
        } else {
            int[] st = StageFilter.filter(Json.stagesRaw(night), detailChoice);
            card.addView(Ui.text(this, "Your last night with this setting", 11, Ui.TEXT_3, Ui.MEDIUM));
            card.addView(Ui.gap(this, 6));
            card.addView(new Charts.Hypnogram(this, st, Json.d(night, "start"), Json.d(night, "end")).zone(Json.zone(night, java.time.ZoneId.systemDefault())), Ui.sized(this, -1, 150));
            card.addView(Ui.gap(this, 4));
            int wakes = StageFilter.wakeRuns(st);
            card.addView(Ui.text(this, String.format(Locale.getDefault(), "%d stage changes, awake %d %s on this chart.",
                    StageFilter.changes(st), wakes, wakes == 1 ? "time" : "times"), 13, Ui.TEXT, Ui.MEDIUM));
            card.addView(Ui.text(this, "Stage changes: how often the chart switches between deep, light, REM and awake. "
                    + "Awake: separate times awake between falling asleep and waking up, of any length.", 11, Ui.TEXT_3, Ui.REGULAR));
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

    static final String[] DETAIL_TEXTS = {
        "Detailed: every stage change the estimate finds, even short ones. Many thin lines.",
        "Balanced (recommended): sleep stages shorter than 3 minutes join the sleep stage next to them. "
            + "Being awake for 30 seconds between sleep joins the sleep around it, 1 minute or more always stays, "
            + "and short sleep is never turned into awake.",
        "Calm: sleep stages shorter than 5 minutes join the sleep stage next to them, being awake needs 2 minutes to stay. "
            + "Long, calm blocks, similar in style to the official app. Wake ups shorter than 2 minutes are no longer shown or counted. "
            + "Short sleep is never turned into awake."
    };

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
                    toast("No browser found. The releases are at " + RELEASES);
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

    EditText num(LinearLayout parent, String label, int value) {
        parent.addView(Ui.gap(this, 8));
        parent.addView(Ui.text(this, label, 12, Ui.TEXT_3, Ui.REGULAR));
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
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

    void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    /** A failed ring action: its plain sentence says what to do, which a toast would cut after two lines (issue #60). */
    void problem(String m) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Not done").setMessage(m).setPositiveButton("OK", null).show();
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

    void reanalyse() {
        toast("Re analysing all nights. With a long history this can take a few minutes.");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    Analyzer.runAll(SettingsActivity.this, null);
                    return "Analysis updated";
                } catch (RuntimeException e) {
                    return "The analysis failed. Data check and log (in Help and diagnostics) shows why.";
                }
            }

            @Override
            protected void onPostExecute(String r) {
                toast(r);
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
                                    problem(message);
                                    return;
                                }
                                toast(message);
                                startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
                                finish();
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
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
        final Uri uri = data.getData();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try {
                        if (code == EXPORT_CSV) Exporter.dailyCsv(SettingsActivity.this, os);
                        else if (code == BACKUP_KEY || code == BACKUP_FULL) app.openring.data.Backup.write(SettingsActivity.this, os, code == BACKUP_FULL);
                        else Exporter.database(SettingsActivity.this, os);
                    } finally {
                        os.close();
                    }
                    return code == BACKUP_KEY || code == BACKUP_FULL ? "Backup saved" : "Exported";
                } catch (Exception e) {
                    return "Export failed: " + e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String r) {
                toast(r);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
