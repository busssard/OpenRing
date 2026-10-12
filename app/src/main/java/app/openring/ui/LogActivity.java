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
import app.openring.data.Analyzer;
import app.openring.data.DataCheck;
import app.openring.data.Db;

/**
 * Diagnostics: the data check (where the pipeline stops, safe to share), the session log and the
 * newest raw ring events.
 */
public final class LogActivity extends Activity {
    private LinearLayout content;
    private String copyText = "";
    private int view; // 0 check, 1 log, 2 raw

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

    void refresh() {
        content.removeAllViews();
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
                if (isFinishing()) return;
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
        TextView repair = Ui.button(this, "Repair: re-date and re-analyse everything", Ui.READY, true);
        repair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repair();
            }
        });
        content.addView(repair, Ui.matchWrap());
        content.addView(Ui.gap(this, 14));
        content.addView(mono(r.text));
    }

    void repair() {
        Toast.makeText(this, "Repairing, this can take a minute", Toast.LENGTH_LONG).show();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    Analyzer.runAll(LogActivity.this, new Analyzer.Log() {
                        @Override
                        public void log(String m) {
                            RingManager.get(LogActivity.this).log(m);
                        }
                    });
                    return "Done";
                } catch (RuntimeException e) {
                    RingManager.get(LogActivity.this).log("repair failed: " + android.util.Log.getStackTraceString(e));
                    return "Repair failed: " + e;
                }
            }

            @Override
            protected void onPostExecute(String s) {
                Toast.makeText(LogActivity.this, s, Toast.LENGTH_LONG).show();
                view = 0;
                refresh();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    TextView mono(String s) {
        TextView t = Ui.text(this, s, 11, Ui.TEXT_2, android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }
}
