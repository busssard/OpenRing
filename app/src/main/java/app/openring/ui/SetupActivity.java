package app.openring.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.openring.ble.RingManager;
import app.openring.core.Hex;
import app.openring.data.Prefs;
import app.openring.sync.SyncJob;
import app.openring.sync.SyncService;

/** First run: welcome, permissions, profile, then connect the ring with a key. */
public final class SetupActivity extends Activity {
    private ScrollView scroll;
    private int step;
    private String pairedMessage = "Paired";
    private static final int RESTORE = 21;
    private TextView restoreStatus;

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != RESTORE || result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        if (restoreStatus != null) {
            restoreStatus.setTextColor(Ui.TEXT_2);
            restoreStatus.setText("Restoring");
        }
        new android.os.AsyncTask<Void, Void, app.openring.data.Backup.Result>() {
            @Override
            protected app.openring.data.Backup.Result doInBackground(Void... v) {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    try {
                        return app.openring.data.Backup.read(SetupActivity.this, in);
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    app.openring.data.Backup.Result r = new app.openring.data.Backup.Result();
                    r.message = "Could not read the file: " + e.getMessage();
                    return r;
                }
            }

            @Override
            protected void onPostExecute(app.openring.data.Backup.Result r) {
                if (isFinishing() || isDestroyed()) return;
                if (!r.ok) {
                    if (restoreStatus != null) {
                        restoreStatus.setTextColor(Ui.WARN);
                        restoreStatus.setText(r.message);
                    }
                    return;
                }
                paired(r.message);
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        setContentView(scroll);
        step = b == null ? 0 : b.getInt("step", 0);
        if (b != null) pairedMessage = b.getString("paired", pairedMessage);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // a pairing that finished while this screen was being recreated
        Prefs p = Prefs.of(this);
        if (step >= 3 && step != 5 && p.setupDone() && p.hasKey()) {
            step = 5;
            show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("paired", pairedMessage);
    }

    @Override
    public void onBackPressed() {
        if (step == 5) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        } else if (step > 0) {
            step--;
            show();
        } else {
            finishAffinity();
        }
    }

    LinearLayout page(String title, String body) {
        LinearLayout l = Ui.vbox(this);
        int p = Ui.dp(this, 24);
        l.setPadding(p, Ui.dp(this, 40), p, p);
        l.addView(Ui.h1(this, title));
        l.addView(Ui.gap(this, 12));
        if (body != null) l.addView(Ui.body(this, body));
        l.addView(Ui.gap(this, 20));
        scroll.removeAllViews();
        scroll.addView(l);
        return l;
    }

    TextView primary(LinearLayout parent, String label, View.OnClickListener l) {
        TextView b = Ui.button(this, label, Ui.SLEEP, true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        parent.addView(b, lp);
        return b;
    }

    TextView secondary(LinearLayout parent, String label, View.OnClickListener l) {
        TextView b = Ui.button(this, label, Ui.TEXT_2, false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        parent.addView(b, lp);
        return b;
    }

    EditText field(LinearLayout parent, String label, String value, boolean number) {
        parent.addView(Ui.caption(this, label));
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_3);
        e.setBackground(Ui.input(this));
        int p = Ui.dp(this, 14);
        e.setPadding(p, p, p, p);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        parent.addView(e, lp);
        return e;
    }

    void show() {
        switch (step) {
            case 0:
                welcome();
                break;
            case 1:
                permissions();
                break;
            case 2:
                profile();
                break;
            case 3:
                connect();
                break;
            case 5:
                paired(pairedMessage);
                break;
            default:
                break;
        }
    }

    void welcome() {
        LinearLayout l = page("Your ring, your phone",
                "Open Ring reads your Oura Ring directly over Bluetooth and computes sleep, readiness, activity and body signals on this phone. "
                        + "No account, no cloud, no subscription. Your data never leaves the device unless you export it.");
        Charts.ScoreRing r = new Charts.ScoreRing(this, 86, "Ready", Ui.READY, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 30));
        l.addView(r, lp);
        l.addView(Ui.text(this, "Built on the open_oura and open_health reverse engineering projects. Not affiliated with Oura. "
                + "Scores follow Oura's recovered contributor weights but are not identical to the official app, and nothing here is medical advice.", 12, Ui.TEXT_3, Ui.REGULAR));
        l.addView(Ui.gap(this, 16));
        primary(l, "Get started", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step = 1;
                show();
            }
        });
        secondary(l, "Just looking? Explore with demo data", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SetupActivity.this, MainActivity.class).putExtra("demo", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
        });
        l.addView(Ui.text(this, "The demo shows a made up person's five weeks, so you can see every screen without a ring.", 12, Ui.TEXT_3, Ui.REGULAR));
    }

    void permissions() {
        if (Permissions.granted(this)) {
            step = 2;
            show();
            return;
        }
        LinearLayout l = page("Bluetooth access", "The app needs permission to find and connect to your ring. On older Android versions this is called location access; "
                + "the app never reads your location.");
        primary(l, "Allow", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Permissions.request(SetupActivity.this);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (step == 1 && Permissions.granted(this)) {
            step = 2;
            show();
        } else if (step == 1) {
            Toast.makeText(this, "Bluetooth permission is required to talk to the ring", Toast.LENGTH_LONG).show();
        }
    }

    void profile() {
        final Prefs p = Prefs.of(this);
        LinearLayout l = page("About you", "Used for calorie burn, activity goals and age adjusted sleep targets. Stored only on this phone.");
        final EditText age = field(l, "Age", String.valueOf(p.age()), true);
        final EditText weight = field(l, "Weight (kg)", String.valueOf(p.weightKg()), true);
        final EditText height = field(l, "Height (cm)", String.valueOf(p.heightCm()), true);
        l.addView(Ui.caption(this, "Sex"));
        final int[] sex = {p.sex()};
        final LinearLayout row = Ui.hbox(this);
        final String[] names = {"Male", "Female", "Prefer not to say"};
        final TextView[] buttons = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            buttons[i] = Ui.button(this, names[i], i == sex[0] ? Ui.READY : Ui.TEXT_3, false);
            buttons[i].setTextSize(13);
            buttons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sex[0] = k;
                    for (int j = 0; j < 3; j++) {
                        TextView nb = Ui.button(SetupActivity.this, names[j], j == k ? Ui.READY : Ui.TEXT_3, false);
                        buttons[j].setBackground(nb.getBackground());
                        buttons[j].setTextColor(j == k ? Ui.READY : Ui.TEXT_3);
                    }
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 16));
            row.addView(buttons[i], lp);
        }
        l.addView(row);
        primary(l, "Continue", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.setProfile(num(age, 35, 10, 100), sex[0], num(weight, 72, 30, 250), num(height, 175, 100, 230), p.targetKcal());
                p.setProfileSet();
                step = 3;
                show();
            }
        });
    }

    static int num(EditText e, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (NumberFormatException x) {
            return def;
        }
    }

    void connect() {
        LinearLayout l = page("Connect your ring",
                "The ring only talks to apps that know its 16 byte pairing key. Choose how to get one. Put the ring on its charger next to the phone, "
                        + "and close the official Oura app so it does not hold the Bluetooth link.");

        LinearLayout a = Ui.card(this);
        a.addView(Ui.h2(this, "Use my existing key"));
        a.addView(Ui.gap(this, 4));
        a.addView(Ui.body(this, "Keeps the ring working with the official app too. The key can be read from the Oura app's data: on iPhone from an "
                + "unencrypted backup (assa.sqlite, table ringconfiguration, column auth_key); on a rooted Android phone with open_oura's "
                + "android_oura_key_extract.py. Or paste a key you backed up from this app."));
        a.addView(Ui.gap(this, 10));
        a.addView(Ui.caption(this, "Paste or type your key here"));
        LinearLayout keyRow = Ui.hbox(this);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText key = new EditText(this);
        key.setTextColor(Ui.TEXT);
        key.setHintTextColor(Ui.TEXT_3);
        key.setBackground(Ui.input(this));
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextSize(14);
        int kp = Ui.dp(this, 14);
        key.setPadding(kp, kp, kp, kp);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setHint("32 characters, 0 to 9 and a to f");
        keyRow.addView(key, Ui.weight(1));
        TextView paste = Ui.button(this, "Paste", Ui.SLEEP, false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm == null ? null : cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    Toast.makeText(SetupActivity.this, "The clipboard is empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                CharSequence t = clip.getItemAt(0).coerceToText(SetupActivity.this);
                key.setText(t == null ? "" : t.toString().trim());
                key.setSelection(key.getText().length());
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        keyRow.addView(paste, plp);
        LinearLayout.LayoutParams krl = Ui.matchWrap();
        krl.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        a.addView(keyRow, krl);
        final CheckBox install = new CheckBox(this);
        install.setText("Install this key on a factory reset ring");
        install.setTextColor(Ui.TEXT_2);
        install.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.SLEEP));
        a.addView(install);
        final TextView status = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        primary(a, "Connect with this key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                byte[] k = Hex.parseKey(key.getText().toString());
                if (k == null) {
                    status.setText("A key is exactly 32 hex characters (0 to 9, a to f).");
                    status.setTextColor(Ui.WARN);
                    return;
                }
                runPair(k, install.isChecked(), status);
            }
        });
        a.addView(status);
        l.addView(a);

        LinearLayout rcard = Ui.card(this);
        rcard.addView(Ui.h2(this, "Restore from a backup"));
        rcard.addView(Ui.gap(this, 4));
        rcard.addView(Ui.body(this, "Moving to a new phone or reinstalling? Pick an Open Ring backup file (saved in Settings, Key and backup). "
                + "It restores the key and your settings, and a full backup also brings back all your history. A text file that "
                + "only holds the key works too."));
        restoreStatus = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        secondary(rcard, "Choose backup file", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, RESTORE);
            }
        });
        rcard.addView(restoreStatus);
        l.addView(rcard);

        LinearLayout bcard = Ui.card(this);
        bcard.addView(Ui.h2(this, "Pair a factory reset ring"));
        bcard.addView(Ui.gap(this, 4));
        bcard.addView(Ui.body(this, "Generates a brand new key on this phone. The official app will then no longer connect unless you reset the ring again.\n\n"
                + "Reset on the Gen3 or Ring 4 dock (no button needed): take the ring off, put it back and wait 2 seconds, then flip the dock upside down until it "
                + "glows blue, upright until red, upside down until purple, upright until yellow. Yellow means the reset started; blinking blue means done.\n\n"
                + "If the ring is still paired with this app, you can reset it from Settings instead."));
        final TextView st2 = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        secondary(bcard, "Pair and create a new key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runPair(null, true, st2);
            }
        });
        bcard.addView(st2);
        l.addView(bcard);
    }

    void runPair(byte[] key, boolean install, final TextView status) {
        if (!Permissions.granted(this)) {
            Permissions.request(this);
            return;
        }
        status.setTextColor(Ui.TEXT_2);
        status.setText("Working, accept the pairing request if Android shows one");
        final RingManager.Listener l = new RingManager.Listener() {
            @Override
            public void onRingStatus() {
                String s = RingManager.get(SetupActivity.this).status();
                if (s != null && !s.isEmpty()) status.setText(s);
            }
        };
        RingManager.get(this).addListener(l);
        RingManager.get(this).pair(key, install, new RingManager.Callback() {
            @Override
            public void done(boolean ok, String message) {
                RingManager.get(SetupActivity.this).removeListener(l);
                if (isFinishing() || isDestroyed()) return;
                if (!ok) {
                    status.setTextColor(Ui.WARN);
                    status.setText(message);
                    return;
                }
                paired(message);
            }
        });
    }

    void paired(String message) {
        step = 5;
        pairedMessage = message;
        final Prefs p = Prefs.of(this);
        // the key is saved; never send the user back through setup for this ring
        p.setSetupDone(true);
        final String hex = Hex.encode(p.loadKey());
        LinearLayout l = page("You're connected", message + ". Back up your key somewhere safe. It cannot be read back from the ring, "
                + "and without it you would need to factory reset the ring to connect again.");
        LinearLayout k = Ui.card(this);
        k.addView(Ui.caption(this, "Your ring key"));
        TextView kt = Ui.text(this, hex, 17, Ui.TEXT, Typeface());
        kt.setTextIsSelectable(true);
        k.addView(kt);
        l.addView(k);
        secondary(l, "Copy key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Ring key", hex));
                Toast.makeText(SetupActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        primary(l, "Start syncing", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.setSetupDone(true);
                SyncJob.schedule(SetupActivity.this);
                SyncService.start(SetupActivity.this);
                startActivity(new Intent(SetupActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
        });
    }

    static android.graphics.Typeface Typeface() {
        return android.graphics.Typeface.MONOSPACE;
    }
}
