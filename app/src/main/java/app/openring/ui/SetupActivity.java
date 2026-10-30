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
import app.openring.core.Pairing;
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

    /**
     * Restore state that survives this screen being recreated (rotation, dark mode, font or
     * language change). The background tasks report to whichever screen is attached now.
     */
    static final class Restore {
        SetupActivity owner;
        /** Checked backup waiting for the person's choice. */
        app.openring.data.Backup.Plan plan;
        /** Results that arrived while no screen was attached. */
        app.openring.data.Backup.Plan prepared;
        app.openring.data.Backup.Result result;
        boolean busy;
        /** What a running restore waits for (a sync, an analysis; issue #219), null while it just restores. */
        String waiting;
        /** The screen was closed for good: a prepare that finishes later is dropped, a restore still waiting stops. */
        volatile boolean abandoned;
        /** This restore counts in {@link #TASKS} (from the picked file to its end). */
        boolean counted;
    }

    /** Restore and pairing never at once (review of #248), across rebuilt screens. */
    static final app.openring.core.SetupTasks TASKS = new app.openring.core.SetupTasks();

    /** The restore of {@code st} ended in any way: a pairing may start again. */
    static void restoreEnded(Restore st) {
        if (!st.counted) return;
        st.counted = false;
        TASKS.restoreEnded();
    }

    private Restore restore;
    private android.app.AlertDialog dialog;

    /**
     * The pairing that runs now, reported to the Setup screen attached now (issue #218): a turned phone builds a new
     * screen, Android may end the screen, the result still arrives. Static, so a rebuilt screen sees it.
     */
    static final Pairing.Run<SetupActivity> PAIR = new Pairing.Run<SetupActivity>();
    /** Cards that can start a pairing: the typed key, a new key. */
    static final int CARD_KEY = 0, CARD_NEW = 1;
    /** The status lines of the two cards on "Connect your ring" (null while another page is shown). */
    private final TextView[] pairStatus = new TextView[2];
    /** The card that shows a key kept from a try that did not finish. */
    private LinearLayout keptCard;

    /** Shows the ring's status line in the card that started the pairing, also on a rebuilt screen. */
    private final RingManager.Listener ringStatus = new RingManager.Listener() {
        @Override
        public void onRingStatus() {
            if (!PAIR.running()) return;
            TextView t = pairStatus[PAIR.card()];
            String st = RingManager.get(SetupActivity.this).status();
            if (t != null && st != null && !st.isEmpty()) t.setText(st);
        }
    };

    /** Holds no screen: tells the one attached when the pairing ends, or leaves the result for the next one. */
    static final class PairDone implements RingManager.Callback {
        @Override
        public void done(boolean ok, String message) {
            SetupActivity o = PAIR.finish(ok, message);
            if (o != null && !o.isFinishing() && !o.isDestroyed()) o.pairResult();
        }
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return restore;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != RESTORE || result != RESULT_OK || data == null || data.getData() == null) return;
        if (restore.busy || restore.plan != null) return;
        // a pairing started meanwhile would write a key the restore then replaces (review of #248)
        String no = TASKS.startRestore(PAIR.running());
        if (no != null) {
            restoreStatus(no, Ui.WARN);
            return;
        }
        final android.net.Uri uri = data.getData();
        final Context appCtx = getApplicationContext();
        final Restore st = restore;
        st.counted = true;
        st.busy = true;
        restoreStatus("Restoring", Ui.TEXT_2);
        new android.os.AsyncTask<Void, Void, app.openring.data.Backup.Plan>() {
            @Override
            protected app.openring.data.Backup.Plan doInBackground(Void... v) {
                try {
                    java.io.InputStream in = appCtx.getContentResolver().openInputStream(uri);
                    try {
                        return app.openring.data.Backup.prepare(appCtx, in);
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    android.util.Log.w("OpenRing", "restore: reading the backup failed", e);
                    app.openring.data.Backup.Plan p = new app.openring.data.Backup.Plan();
                    p.error = new app.openring.data.Backup.Result();
                    p.error.message = "Could not read this file, nothing was changed.";
                    return p;
                }
            }

            @Override
            protected void onPostExecute(app.openring.data.Backup.Plan p) {
                st.busy = false;
                if (st.abandoned) {
                    app.openring.data.Backup.cancel(appCtx, p);
                    restoreEnded(st);
                    return;
                }
                if (st.owner == null) st.prepared = p;
                else st.owner.prepared(p);
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void prepared(app.openring.data.Backup.Plan p) {
        if (p.error != null) {
            restoreEnded(restore);
            restoreStatus(p.error.message, Ui.WARN);
            return;
        }
        if (p.ask) askReplace(p);
        else finishRestore(p, true);
    }

    /** Issue #26: the phone has history the backup lacks, so the person decides. */
    void askReplace(final app.openring.data.Backup.Plan p) {
        restore.plan = p;
        String msg;
        if (p.phoneNewer && !Double.isNaN(p.backupNewest)) {
            String b = day(p.backupNewest), ph = day(p.phoneNewest);
            if (b.equals(ph)) {
                // same calendar day: the time tells them apart
                b = dayTime(p.backupNewest);
                ph = dayTime(p.phoneNewest);
            }
            msg = "This backup is older than the history on this phone. The backup ends on " + b + ", this phone has history up to "
                    + ph + ". Replacing the history loses everything synced after " + b + ".";
        } else {
            msg = "The history on this phone holds data that is not in this backup. Replacing the history loses that data.";
        }
        dialog = new android.app.AlertDialog.Builder(this).setTitle("Keep the history on this phone?").setMessage(msg)
                .setPositiveButton("Key and settings only", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        finishRestore(p, false);
                    }
                })
                .setNeutralButton("Replace history", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        finishRestore(p, true);
                    }
                })
                .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        cancelRestore(p);
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        cancelRestore(p);
                    }
                }).show();
    }

    static String day(double unix) {
        return java.time.Instant.ofEpochSecond((long) unix).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
    }

    static String dayTime(double unix) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochSecond((long) unix).atZone(java.time.ZoneId.systemDefault());
        return day(unix) + ", " + t.toLocalTime().format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT));
    }

    void cancelRestore(app.openring.data.Backup.Plan p) {
        dialog = null;
        if (restore.plan == p) restore.plan = null;
        app.openring.data.Backup.cancel(this, p);
        restoreEnded(restore);
        restoreStatus("Nothing was restored.", Ui.TEXT_2);
    }

    void finishRestore(final app.openring.data.Backup.Plan p, final boolean replaceHistory) {
        dialog = null;
        if (restore.plan == p) restore.plan = null;
        final Context appCtx = getApplicationContext();
        final Restore st = restore;
        st.busy = true;
        st.waiting = null;
        restoreStatus("Restoring", Ui.TEXT_2);
        new android.os.AsyncTask<Void, String, app.openring.data.Backup.Result>() {
            @Override
            protected app.openring.data.Backup.Result doInBackground(Void... v) {
                try {
                    // the history is swapped only once no sync stores into it (issue #219); until then the line says why,
                    // and leaving Setup for good stops the wait (review of #248)
                    final class Line implements app.openring.data.Backup.Progress, app.openring.core.RingGate.Stop {
                        @Override
                        public void step(String what) {
                            publishProgress(what);
                        }

                        @Override
                        public boolean stopped() {
                            return st.abandoned;
                        }
                    }
                    return app.openring.data.Backup.finish(appCtx, p, replaceHistory, new Line());
                } catch (Exception e) {
                    android.util.Log.w("OpenRing", "restore: applying the backup failed", e);
                    app.openring.data.Backup.cancel(appCtx, p);
                    app.openring.data.Backup.Result r = new app.openring.data.Backup.Result();
                    r.message = "Could not restore this backup, nothing was changed.";
                    return r;
                }
            }

            @Override
            protected void onProgressUpdate(String... line) {
                st.waiting = line[0];
                if (!st.abandoned && st.owner != null) st.owner.restoreStatus(line[0], Ui.TEXT_2);
            }

            @Override
            protected void onPostExecute(app.openring.data.Backup.Result r) {
                st.busy = false;
                st.waiting = null;
                restoreEnded(st);
                if (st.abandoned) return;
                if (st.owner == null) st.result = r;
                else st.owner.restored(r);
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void restored(app.openring.data.Backup.Result r) {
        if (!r.ok) {
            restoreStatus(r.message, Ui.WARN);
            return;
        }
        paired(r.message);
    }

    /** Pick up a restore that was under way when this screen was recreated. */
    void resumeRestore() {
        if (restore.prepared != null) {
            app.openring.data.Backup.Plan p = restore.prepared;
            restore.prepared = null;
            prepared(p);
        } else if (restore.result != null) {
            app.openring.data.Backup.Result r = restore.result;
            restore.result = null;
            restored(r);
        } else if (restore.plan != null) {
            askReplace(restore.plan);
        } else if (restore.busy) {
            restoreStatus(restore.waiting != null ? restore.waiting : "Restoring", Ui.TEXT_2);
        }
    }

    void restoreStatus(String text, int color) {
        if (restoreStatus == null) return;
        restoreStatus.setTextColor(color);
        restoreStatus.setText(text);
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            // dismiss does not count as Cancel: the plan waits for the recreated screen
            dialog.dismiss();
            dialog = null;
        }
        restore.owner = null;
        PAIR.detach(this, isFinishing());
        RingManager.get(this).removeListener(ringStatus);
        if (isFinishing()) {
            restore.abandoned = true;
            app.openring.data.Backup.cancel(this, restore.plan);
            app.openring.data.Backup.cancel(this, restore.prepared);
            restore.plan = null;
            restore.prepared = null;
            // a task still running ends the count itself when it returns
            if (!restore.busy) restoreEnded(restore);
        }
        super.onDestroy();
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
        Object last = getLastNonConfigurationInstance();
        restore = last instanceof Restore ? (Restore) last : new Restore();
        restore.owner = this;
        if (!restore.busy) app.openring.data.Backup.cleanStale(this);
        PAIR.attach(this);
        RingManager.get(this).addListener(ringStatus);
        // a pairing whose key page was not left yet, also after Android ended the app (issue #218)
        Prefs pr = Prefs.of(this);
        if (step != 5 && pr.pairUnseen() != null && pr.hasKey()) {
            step = 5;
            pairedMessage = pr.pairUnseen();
        }
        show();
        resumeRestore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // a pairing that ended while this screen was being recreated or covered (issue #218)
        if (pairResult()) return;
        Prefs p = Prefs.of(this);
        String unseen = p.pairUnseen();
        if (step != 5 && p.hasKey() && (unseen != null || step >= 3 && p.setupDone())) {
            step = 5;
            if (unseen != null) pairedMessage = unseen;
            show();
        } else if (step == 3) {
            fillKept();
        }
    }

    /** Shows a pairing result waiting for this screen; false when there is none. */
    boolean pairResult() {
        Pairing.Result r = PAIR.take();
        if (r == null) return false;
        if (r.ok) {
            paired(r.message);
            return true;
        }
        String text = r.message;
        if (Prefs.of(this).pendingKey() != null) text += " " + Pairing.KEPT_NOTE;
        TextView t = step == 3 ? pairStatus[PAIR.card()] : null;
        if (t != null) {
            t.setTextColor(Ui.WARN);
            t.setText(text);
            fillKept();
        } else {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    /** "Key from your last try": shown while a key kept from a try that did not finish exists. */
    void fillKept() {
        if (keptCard == null) return;
        keptCard.removeAllViews();
        byte[] kept = Prefs.of(this).pendingKey();
        keptCard.setVisibility(kept == null ? View.GONE : View.VISIBLE);
        if (kept == null) return;
        final String hex = Hex.encode(kept);
        keptCard.addView(Ui.h2(this, Pairing.KEPT_TITLE));
        keptCard.addView(Ui.gap(this, 4));
        keptCard.addView(Ui.body(this, Pairing.KEPT_TEXT));
        keptCard.addView(Ui.gap(this, 10));
        keptCard.addView(Ui.caption(this, Pairing.keptFor(Prefs.of(this).pendingKeySerial())));
        TextView kt = Ui.text(this, hex, 17, Ui.TEXT, Typeface());
        kt.setTextIsSelectable(true);
        keptCard.addView(kt);
        secondary(keptCard, "Copy key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyKey(hex);
            }
        });
    }

    void copyKey(String hex) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Ring key", hex));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
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
            Prefs.of(this).clearPairUnseen();
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
        pairStatus[CARD_KEY] = null;
        pairStatus[CARD_NEW] = null;
        keptCard = null;
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
        lp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        l.addView(r, lp);
        TextView sample = Ui.text(this, "An example. Readiness, sleep and activity are scores from 0 to 100, higher is better.", 12, Ui.TEXT_3, Ui.REGULAR);
        sample.setGravity(Gravity.CENTER_HORIZONTAL);
        l.addView(sample);
        l.addView(Ui.gap(this, 22));
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

        keptCard = Ui.card(this);
        l.addView(keptCard);
        fillKept();

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
                runPair(k, install.isChecked(), CARD_KEY);
            }
        });
        a.addView(status);
        pairStatus[CARD_KEY] = status;
        l.addView(a);

        LinearLayout rcard = Ui.card(this);
        rcard.addView(Ui.h2(this, "Restore from a backup"));
        rcard.addView(Ui.gap(this, 4));
        rcard.addView(Ui.body(this, "Moving to a new phone or reinstalling? Pick an Open Ring backup file (saved in Settings, Key and backup). "
                + "It restores the key and your settings, and a full backup also brings back all your history. A text file that "
                + "only holds the key works too. If this phone already has newer history, the app asks before replacing it."));
        restoreStatus = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        secondary(rcard, "Choose backup file", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PAIR.running()) {
                    restoreStatus(app.openring.core.SetupTasks.RESTORE_REFUSED, Ui.WARN);
                    return;
                }
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
                runPair(null, true, CARD_NEW);
            }
        });
        bcard.addView(st2);
        pairStatus[CARD_NEW] = st2;
        l.addView(bcard);
        if (PAIR.running()) {
            // a pairing started before this screen was rebuilt
            TextView t = pairStatus[PAIR.card()];
            t.setTextColor(Ui.TEXT_2);
            t.setText(Pairing.WORKING);
        }
    }

    void runPair(byte[] key, boolean install, int card) {
        if (!Permissions.granted(this)) {
            Permissions.request(this);
            return;
        }
        // a restore under way would replace the key this pairing writes to the ring (review of #248)
        String no = TASKS.pairRefusal();
        if (no != null) {
            TextView t = pairStatus[card];
            if (t != null) {
                t.setTextColor(Ui.WARN);
                t.setText(no);
            } else {
                Toast.makeText(this, no, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!PAIR.start(card)) {
            Toast.makeText(this, Pairing.ALREADY, Toast.LENGTH_SHORT).show();
            return;
        }
        TextView status = pairStatus[card];
        if (status != null) {
            status.setTextColor(Ui.TEXT_2);
            status.setText(Pairing.WORKING);
        }
        // the callback holds no screen: the result reaches the one attached when it ends (issue #218)
        RingManager.get(this).pair(key, install, new PairDone());
    }

    void paired(String message) {
        step = 5;
        pairedMessage = message;
        final Prefs p = Prefs.of(this);
        // the key is saved; never send the user back through setup for this ring
        p.setSetupDone(true);
        final String hex = Hex.encode(p.loadKey());
        LinearLayout l = page("You're connected", Pairing.sentence(message) + " Back up your key somewhere safe. It cannot be read back from the ring, "
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
                p.clearPairUnseen();
                copyKey(hex);
            }
        });
        primary(l, "Start syncing", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.setSetupDone(true);
                p.clearPairUnseen();
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
