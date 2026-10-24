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
     * (issue #130). Static, so a turned or reopened screen sees it; it ends with the app.
     */
    static final OneRun<LogActivity> REPAIR = new OneRun<LogActivity>();
    /** Shown while a repair runs, as a toast when it starts and as a line above Repair. */
    private static final String REPAIRING = "Repairing, this can take a few minutes.";

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
        repairButton = Ui.button(this, "Repair: re-date and re-analyse everything", Ui.READY, true);
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
        repairNote.setVisibility(on ? View.VISIBLE : View.GONE);
        repairButton.setEnabled(!on);
        repairButton.setAlpha(on ? 0.4f : 1f);
    }

    void repair() {
        if (!REPAIR.start()) {
            // one repair at a time (issue #130); this screen was covered while another one started it
            Toast.makeText(this, "A repair is already running.", Toast.LENGTH_LONG).show();
            showRepairState();
            return;
        }
        Toast.makeText(this, REPAIRING, Toast.LENGTH_LONG).show();
        showRepairState();
        new RepairTask(getApplicationContext()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Holds only the application, never a screen, so a turned screen can go (issue #130). */
    private static final class RepairTask extends AsyncTask<Void, Void, String> {
        private final Context ctx;

        RepairTask(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        protected String doInBackground(Void... v) {
            try {
                Analyzer.runAll(ctx, new Analyzer.Log() {
                    @Override
                    public void log(String m) {
                        RingManager.get(ctx).log(m);
                    }
                });
                return "Repair done.";
            } catch (RuntimeException e) {
                RingManager.get(ctx).log("repair failed: " + android.util.Log.getStackTraceString(e));
                return Texts.REPAIR_FAILED; // raw text logged above (issue #89); may show on any screen
            }
        }

        @Override
        protected void onPostExecute(String s) {
            LogActivity a = REPAIR.finish();
            // the application's toast shows on whatever screen is open now
            Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
            if (a == null || a.isDestroyed()) return;
            a.view = s == Texts.REPAIR_FAILED ? 1 : 0; // after a failure show the Log the toast points to
            a.refresh();
        }
    }

    TextView mono(String s) {
        TextView t = Ui.text(this, s, 11, Ui.TEXT_2, android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }
}
