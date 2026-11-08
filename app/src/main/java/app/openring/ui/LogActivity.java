package app.openring.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import app.openring.ble.RingManager;
import app.openring.core.EventCodec;
import app.openring.core.OneRun;
import app.openring.core.Texts;
import app.openring.data.Analyzer;
import app.openring.data.DataCheck;
import app.openring.data.Db;

/**
 * Diagnostics: the data check (where the pipeline stops, safe to share), the session log and the
 * newest raw ring events.
 */
public final class LogActivity extends Activity {
    /**
     * The repair runs on after the phone is turned or the screen is left, and only once at a time
     * (issue #130). Static, so a turned or reopened screen sees it; it ends with the app. Settings' Re analyse all is
     * the same job and runs through it too (issue #222, {@link #analyseAll}).
     */
    static final OneRun<LogActivity> REPAIR = new OneRun<LogActivity>();
    /** Shown while a repair runs, as a toast when it starts and as a line above Repair. */
    private static final String REPAIRING = "Repairing, this can take a few minutes.";
    /** The line above Repair while Settings' Re analyse all runs the same job: nothing is being repaired (issue #361). */
    private static final String ANALYSING_ALL = "Analysing all nights again, this can take a few minutes.";
    /**
     * Which job {@link #REPAIR} runs now: true for Re analyse all from Settings, false for Repair (issue #361). Set by
     * {@link #analyseAll}, which both start through once {@link #REPAIR} is theirs; read only while it runs.
     */
    private static volatile boolean fromSettingsNow;

    private LinearLayout content;
    private TextView repairButton, repairNote;
    /** Counts refresh calls; a result from an older call is dropped, so it cannot cover a newer one. */
    private int refreshGen;
    private String copyText = "";
    private int view; // 0 check, 1 log, 2 raw

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        REPAIR.attach(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 20), p, p);
        root.addView(Ui.h1(this, "Diagnostics"));
        LinearLayout row = Ui.hbox(this);
        String[] names = {"Data check", "Log", "Raw events"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = Ui.button(this, names[i], Ui.TEXT_2, false);
            t.setTextSize(13);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    view = k;
                    refresh();
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10));
            row.addView(t, lp);
        }
        root.addView(row);
        ScrollView s = new ScrollView(this);
        content = Ui.vbox(this);
        s.addView(content);
        root.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView copy = Ui.button(this, "Copy this page", Ui.SLEEP, false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Open Ring diagnostics", copyText));
                Toast.makeText(LogActivity.this, view == 2 ? "Copied. Note: raw events contain health values" : "Copied", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams clp = Ui.matchWrap();
        clp.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(copy, clp);
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // two Data check screens can be open (Settings and the main screen both open one): the one
        // shown now is told when the repair ends, and catches up if it ended while it was covered
        REPAIR.attach(this);
        if (repairNote != null && repairNote.getVisibility() == View.VISIBLE && !REPAIR.running()) {
            view = 0;
            refresh();
        } else {
            showRepairState();
        }
    }

    @Override
    protected void onDestroy() {
        REPAIR.detach(this);
        super.onDestroy();
    }

    void refresh() {
        final int gen = ++refreshGen;
        content.removeAllViews();
        repairButton = null;
        repairNote = null;
        if (view == 1) {
            StringBuilder sb = new StringBuilder();
            for (String l : RingManager.get(this).logLines()) sb.append(l).append('\n');
            copyText = sb.length() == 0 ? "Nothing logged since the app started." : sb.toString();
            content.addView(mono(copyText));
            return;
        }
        content.addView(Ui.body(this, "Working"));
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... v) {
                if (view == 0) return DataCheck.run(LogActivity.this);
                List<Db.Row> rows = Db.get(LogActivity.this).recentEvents(400);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
                StringBuilder sb = new StringBuilder();
                for (Db.Row r : rows) {
                    sb.append(Double.isNaN(r.unix) ? "undated       " : f.format(Instant.ofEpochSecond((long) r.unix)))
                            .append("  ").append(EventCodec.name(r.tag)).append("  ").append(EventCodec.describe(r.tag, r.body)).append('\n');
                }
                return sb.length() == 0 ? "No events stored yet." : sb.toString();
            }

            @Override
            protected void onPostExecute(Object o) {
                if (isFinishing() || isDestroyed() || gen != refreshGen) return;
                content.removeAllViews();
                if (o instanceof String) {
                    copyText = (String) o;
                    content.addView(mono(copyText));
                    return;
                }
                showCheck((DataCheck.Report) o);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void showCheck(DataCheck.Report r) {
        copyText = r.text;
        content.addView(Ui.body(this, "Follows your data from the ring to the screen and shows where it stops. "
                + "This page holds counts and dates only, no health values, so it is safe to copy and share."));
        content.addView(Ui.gap(this, 10));
        for (DataCheck.Step s : r.steps) {
            LinearLayout card = Ui.card(this);
            int col = s.state == 0 ? Ui.GOOD : s.state == 1 ? Ui.TEMP : Ui.WARN;
            card.addView(Ui.text(this, (s.state == 0 ? "OK   " : s.state == 1 ? "NOTE   " : "PROBLEM   ") + s.title, 15, col, Ui.MEDIUM));
            card.addView(Ui.gap(this, 4));
            card.addView(Ui.body(this, s.detail));
            content.addView(card);
        }
        repairNote = Ui.text(this, REPAIRING, 14, Ui.TEMP, Ui.MEDIUM);
        repairNote.setPadding(0, 0, 0, Ui.dp(this, 6));
        content.addView(repairNote);
        repairButton = Ui.button(this, "Repair: date and analyse everything again", Ui.READY, true);
        repairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repair();
            }
        });
        content.addView(repairButton, Ui.matchWrap());
        showRepairState();
        content.addView(Ui.gap(this, 14));
        content.addView(mono(r.text));
    }

    /** While a repair runs (also one started before the phone was turned) Repair is faded and off. */
    void showRepairState() {
        if (repairButton == null) return;
        boolean on = REPAIR.running();
        repairNote.setText(fromSettingsNow ? ANALYSING_ALL : REPAIRING);
        repairNote.setVisibility(on ? View.VISIBLE : View.GONE);
        repairButton.setEnabled(!on);
        repairButton.setAlpha(on ? 0.4f : 1f);
    }

    void repair() {
        if (!REPAIR.start()) {
            // one at a time (issue #130); this screen was covered while another one started it, or Settings' Re analyse
            // all runs (the same job, issue #222)
            Toast.makeText(this, Texts.REANALYSE_RUNS, Toast.LENGTH_LONG).show();
            showRepairState();
            return;
        }
        Toast.makeText(this, REPAIRING, Toast.LENGTH_LONG).show();
        // first, so the line above Repair names this job, not the one before (issue #361)
        analyseAll(getApplicationContext(), false);
        showRepairState();
    }

    /**
     * Runs the job {@link #REPAIR} that the caller has started: Repair here, or Re analyse all in Settings
     * ({@code fromSettings}, issue #222). A setting saved meanwhile runs it once more ({@link OneRun#finishUnlessAgain}).
     */
    static void analyseAll(Context app, boolean fromSettings) {
        fromSettingsNow = fromSettings;
        new RepairTask(app, fromSettings).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Holds only the application, never a screen, so a turned screen can go (issue #130). */
    private static final class RepairTask extends AsyncTask<Void, Void, Boolean> {
        private final Context ctx;
        private final boolean fromSettings;

        RepairTask(Context ctx, boolean fromSettings) {
            this.ctx = ctx;
            this.fromSettings = fromSettings;
        }

        @Override
        protected Boolean doInBackground(Void... v) {
            boolean ok;
            do {
                try {
                    Analyzer.runAll(ctx, new Analyzer.Log() {
                        @Override
                        public void log(String m) {
                            RingManager.get(ctx).log(m);
                        }
                    });
                    ok = true;
                } catch (Throwable e) {
                    // also out of memory: the analysis rolled back to the old nights and stored its class and place for the
                    // data check (issues #230, #289); the full text goes only to the Log tab, and logging never stops the job
                    ok = false;
                    try {
                        RingManager.get(ctx).log((fromSettings ? "re analyse all" : "repair") + " failed: " + android.util.Log.getStackTraceString(e));
                    } catch (Throwable ignored) {
                        // the stored note for the data check is written anyway
                    }
                }
                // one lock with the start: a setting saved after this point starts a new run of its own
            } while (!REPAIR.finishUnlessAgain());
            return ok;
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            // the job ended in doInBackground; the screens shown now are told
            LogActivity a = REPAIR.attached();
            SettingsActivity s = SettingsActivity.shownNow();
            if (s != null) s.showJobState();
            if (fromSettings) {
                // Settings shown: its own sentence in full; else the application's toast, short (issue #89)
                if (s != null && ok) s.toast(Texts.ANALYSIS_UPDATED);
                else if (s != null) s.problem(Texts.ANALYSIS_FAILED);
                else Toast.makeText(ctx, ok ? Texts.ANALYSIS_UPDATED : Texts.ANALYSIS_FAILED_TOAST, Toast.LENGTH_LONG).show();
            } else {
                // the application's toast shows on whatever screen is open now; raw text logged above (issue #89)
                Toast.makeText(ctx, ok ? "Repair done." : Texts.REPAIR_FAILED, Toast.LENGTH_LONG).show();
            }
            if (a == null || a.isDestroyed()) return;
            a.view = ok ? 0 : 1; // after a failure show the Log the toast points to
            a.refresh();
        }
    }

    TextView mono(String s) {
        TextView t = Ui.text(this, s, 11, Ui.TEXT_2, android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }
}
