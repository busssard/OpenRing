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
import app.openring.data.Analyzer;
import app.openring.data.Db;
import app.openring.data.Exporter;
import app.openring.data.Prefs;
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
            LinearLayout about = Ui.card(this);
            about.addView(Ui.caption(this, "About"));
            addUpdateRow(about);
            about.addView(Ui.body(this, "Open Ring " + version() + ". Local first companion for the Oura Ring. Not affiliated with Oura Health. Not a medical device."));
            root.addView(about);
            return;
        }

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
        ring.addView(sw);

        root.addView(ring);

        // ---- measurements ----
        LinearLayout feat = Ui.card(this);
        feat.addView(Ui.caption(this, "Measurements on the ring"));
        feat.addView(Ui.body(this, "A ring paired outside the official app starts with daytime heart rate and blood oxygen switched off. Read the current state, then tap to switch."));
        final String[] names = {"Daytime heart rate", "Blood oxygen (SpO2) at night", "Resting heart rate"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            String state = featureModes[i] < 0 ? "unknown" : featureModes[i] == 0 ? "off" : "on";
            TextView t = Ui.button(this, names[i] + ": " + state, featureModes[i] > 0 ? Ui.READY : Ui.TEXT_2, false);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int mode = featureModes[k] > 0 ? Protocol.MODE_OFF : Protocol.MODE_AUTOMATIC;
                    RingManager.get(SettingsActivity.this).setFeature(RingManager.FEATURES[k], mode, new RingManager.Callback() {
                        @Override
                        public void done(boolean ok, String message) {
                            toast(message);
                            if (ok) {
                                featureModes[k] = mode;
                                build();
                            }
                        }
                    });
                }
            });
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.setMargins(0, Ui.dp(this, 6), 0, 0);
            feat.addView(t, lp);
        }
        action(feat, "Read from ring", Ui.READY, new Runnable() {
            @Override
            public void run() {
                RingManager.get(SettingsActivity.this).readFeatures(new RingManager.Callback() {
                    @Override
                    public void done(boolean ok, String message) {
                        if (!ok) {
                            toast(message);
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
        });
        root.addView(feat);

        // ---- profile ----
        LinearLayout prof = Ui.card(this);
        prof.addView(Ui.caption(this, "Profile and goals"));
        final EditText age = num(prof, "Age", p.age());
        final EditText weight = num(prof, "Weight (kg)", p.weightKg());
        final EditText height = num(prof, "Height (cm)", p.heightCm());
        final EditText sex = num(prof, "Sex (0 male, 1 female, 2 unspecified)", p.sex());
        final EditText target = num(prof, "Daily active calorie goal (0 = automatic)", p.targetKcal());
        action(prof, "Save and re analyse", Ui.ACTIVE, new Runnable() {
            @Override
            public void run() {
                p.setProfile(SetupActivity.num(age, 35, 10, 100), SetupActivity.num(sex, 2, 0, 2), SetupActivity.num(weight, 72, 30, 250),
                        SetupActivity.num(height, 175, 100, 230), SetupActivity.num(target, 0, 0, 3000));
                reanalyse();
            }
        });
        root.addView(prof);

        // ---- key ----
        LinearLayout key = Ui.card(this);
        key.addView(Ui.caption(this, "Key and backup"));
        key.addView(Ui.body(this, "Keep a copy of the key. Without it you would have to factory reset the ring to connect again. "
                + "A backup file restores the app on a new phone from the setup screen (Restore from a backup)."));
        final TextView kt = Ui.text(this, "•••• tap Show ••••", 15, Ui.TEXT, android.graphics.Typeface.MONOSPACE);
        kt.setTextIsSelectable(true);
        key.addView(kt);
        LinearLayout kr = Ui.hbox(this);
        TextView show = Ui.button(this, "Show", Ui.TEXT_2, false);
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                kt.setText(Hex.encode(p.loadKey()));
            }
        });
        TextView copy = Ui.button(this, "Copy", Ui.TEXT_2, false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Ring key", Hex.encode(p.loadKey())));
                toast("Copied");
            }
        });
        LinearLayout.LayoutParams w1 = Ui.weight(1);
        w1.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 6), 0);
        kr.addView(show, w1);
        LinearLayout.LayoutParams w2 = Ui.weight(1);
        w2.setMargins(Ui.dp(this, 6), Ui.dp(this, 8), 0, 0);
        kr.addView(copy, w2);
        key.addView(kr);
        action(key, "Save backup: key and settings", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                create("open-ring-backup.zip", "application/zip", BACKUP_KEY);
            }
        });
        action(key, "Save full backup with all history", Ui.SLEEP, new Runnable() {
            @Override
            public void run() {
                create("open-ring-full-backup.zip", "application/zip", BACKUP_FULL);
            }
        });
        key.addView(Ui.text(this, "The backup holds your ring key in readable form. Keep it as private as the key itself.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(key);

        // ---- data ----
        LinearLayout data = Ui.card(this);
        data.addView(Ui.caption(this, "Export"));
        data.addView(Ui.body(this, "For spreadsheets or your own analysis. These files hold no key."));
        action(data, "Export daily summaries (CSV)", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                create("open-ring-daily.csv", "text/csv", EXPORT_CSV);
            }
        });
        action(data, "Export full database", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                create("open-ring.db", "application/octet-stream", EXPORT_DB);
            }
        });
        root.addView(data);

        // ---- help ----
        LinearLayout help = Ui.card(this);
        help.addView(Ui.caption(this, "Help and diagnostics"));
        help.addView(Ui.body(this, "Data check follows your data from the ring to the screens and shows where it stops. Safe to share."));
        action(help, "Data check and log", Ui.READY, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, LogActivity.class));
            }
        });
        action(help, "Re analyse all history", Ui.TEXT_2, new Runnable() {
            @Override
            public void run() {
                reanalyse();
            }
        });
        root.addView(help);

        // ---- danger ----
        LinearLayout danger = Ui.card(this);
        danger.addView(Ui.caption(this, "Careful"));
        action(danger, "Use a different key or ring", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                confirm("Forget this ring?", "The saved key is removed from this phone. Your synced history stays.", new Runnable() {
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
        action(danger, "Factory reset the ring", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                factoryReset();
            }
        });
        action(danger, "Delete all local data", Ui.WARN, new Runnable() {
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
        about.addView(Ui.caption(this, "About and updates"));
        addUpdateRow(about);
        about.addView(Ui.body(this, "Open Ring " + version() + ". A local first companion for Oura Ring 3, 4 and 5. Protocol and algorithms ported from open_oura and open_health "
                + "(MIT, Thomas Marchand), with notes from ringverse, OpenStrap, BrightOura and the Gadgetbridge Ring 4 work. Not affiliated with Oura Health. "
                + "Not a medical device."));
        root.addView(about);
        String[] sections = {"Ring", "Measurements", "Profile", "Key and backup", "Export", "Help", "Careful", "Updates"};
        View[] targets = {ring, feat, prof, key, data, help, danger, about};
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
    }

    /** Version line and a button that opens the releases page in the browser (issue #7). */
    void addUpdateRow(LinearLayout parent) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout t = Ui.vbox(this);
        t.addView(Ui.text(this, "Installed version", 12, Ui.TEXT_3, Ui.REGULAR));
        t.addView(Ui.text(this, "Open Ring " + version(), 18, Ui.TEXT, Ui.MEDIUM));
        row.addView(t, Ui.weight(1));
        TextView check = Ui.button(this, "Check for updates", Ui.SLEEP, false);
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
        parent.addView(Ui.text(this, "This app has no internet access, so it cannot look for updates itself. The button opens "
                + "the releases page in your browser: a version number higher than yours means there is an update. Install it "
                + "over this one, your data stays.", 12, Ui.TEXT_3, Ui.REGULAR));
        parent.addView(Ui.gap(this, 10));
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
        toast("Re analysing, this can take a minute");
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                Analyzer.runAll(SettingsActivity.this, null);
                return null;
            }

            @Override
            protected void onPostExecute(Void r) {
                toast("Analysis updated");
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
                                toast(message);
                                if (ok) {
                                    startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
                                    finish();
                                }
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
