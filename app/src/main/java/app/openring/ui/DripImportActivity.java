package app.openring.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import app.openring.ble.RingManager;
import app.openring.core.DripCsv;
import app.openring.core.DripImport;
import app.openring.core.Texts;
import app.openring.core.analysis.Cycles;
import app.openring.data.Db;
import app.openring.data.Periods;
import app.openring.data.Prefs;

/**
 * Import from drip (issue #252, phase 1b, the owner's additions of 30 Oct): Android's file picker (no permission, no
 * network), then a preview of what the file holds against the period log, with the short periods flagged (Correct, Ignore,
 * Ignore all) and the days that differ (Open Ring's entries win unless the person takes the file's values). Nothing is
 * saved until Import, which writes everything in one transaction ({@link Periods#importDrip}); Settings, Period tracker
 * can undo it as a whole. Demo mode never imports: it shows the preview of a made up file ({@link DripCsv#demo}) against
 * the demo's log. Nothing here writes a date, an amount or a count to the log or the Data check; errors go to the log as
 * their kind only. The screen is not rebuilt on a turn (manifest), so the preview and its corrections stay.
 */
public final class DripImportActivity extends Activity {
    static final int PICK = 31;
    /** Larger than any drip export (ten years are under 2 MB); a bigger pick is another kind of file. */
    static final int MAX_BYTES = 32 * 1024 * 1024;
    /** Set after an import, so Settings shows its Undo line when it comes back. */
    static volatile boolean changed;

    private boolean demo, busy;
    private LinearLayout content;
    private LocalDate today;
    /** The file as read, the person's corrected days, and the phone's log to compare with (read once with the file). */
    private DripCsv.File file;
    private TreeMap<LocalDate, Integer> flows;
    private Map<LocalDate, Integer> haveFlows;
    private final Map<LocalDate, DripCsv.Temp> haveTemps = new HashMap<LocalDate, DripCsv.Temp>();
    private final Map<LocalDate, Boolean> haveGone = new HashMap<LocalDate, Boolean>();
    private DripImport.Preview preview;
    /** Flagged periods kept as they are (by start). */
    private final Set<LocalDate> ignored = new HashSet<LocalDate>();
    private boolean takeFile, correctPending;
    /** The message instead of a preview (refused file, failed read), or the result after Import. */
    private String problem, done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        demo = Prefs.of(this).demo();
        today = LocalDate.now();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);
        LinearLayout top = Ui.hbox(this);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Import from drip"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.body(this, Explain.DRIP_INTRO));
        if (demo) {
            intro.addView(Ui.gap(this, 8));
            intro.addView(Ui.text(this, Explain.DRIP_DEMO, 13, Ui.WARN, Ui.REGULAR));
        }
        root.addView(intro);
        content = Ui.vbox(this);
        root.addView(content, Ui.matchWrap());
        setContentView(scroll);
        Intent i = getIntent();
        if (b != null) {
            // Android ended the app while the picker was open: the picked file still comes to onActivityResult
            render();
        } else if (demo && i.getBooleanExtra("demo_file", false)) {
            // screen test and demo: a made up file, never a real one
            correctPending = i.getBooleanExtra("demo_correct", false);
            read(null, DripCsv.demo(today));
        } else {
            pick();
        }
    }

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/csv", "text/*"});
        try {
            startActivityForResult(i, PICK);
        } catch (ActivityNotFoundException e) {
            problem = "This phone has no file picker, so no file can be opened.";
            render();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            // nothing picked: back to where the person came from, unless a preview is already shown
            if (file == null && problem == null) finish();
            return;
        }
        read(data.getData(), null);
    }

    /** Reads the picked file ({@code uri}) or the demo's made up text, and the log to compare with, in the background. */
    void read(final Uri uri, final String text) {
        busy = true;
        problem = null;
        done = null;
        file = null;
        renderLine("Reading the file");
        final Context ctx = getApplicationContext();
        new AsyncTask<Void, Void, String>() {
            DripCsv.File f;
            Map<LocalDate, Integer> have;
            List<Periods.Temp> temps;

            @Override
            protected String doInBackground(Void... v) {
                try {
                    String s = text != null ? text : readText(ctx, uri);
                    if (s == null) return "This file is too large for a drip export.";
                    f = DripCsv.parse(s, today);
                    // the database each time, never kept (Periods javadoc); in demo mode the demo's log
                    Db db = Db.get(ctx);
                    have = Periods.flowsForImport(db);
                    temps = Periods.allTemps(db);
                    return null;
                } catch (DripCsv.Refused e) {
                    return e.sentence();
                } catch (Exception e) {
                    // the error's kind only, never a date or a value (the log can be shared)
                    RingManager.get(ctx).log("drip import: the file could not be read: " + e.getClass().getSimpleName());
                    return "The file could not be read. Please try again.";
                }
            }

            @Override
            protected void onPostExecute(String err) {
                busy = false;
                if (isFinishing()) return;
                if (err != null) {
                    problem = err;
                    render();
                    return;
                }
                file = f;
                flows = new TreeMap<LocalDate, Integer>(f.flows);
                haveFlows = have;
                haveTemps.clear();
                haveGone.clear();
                for (Periods.Temp t : temps) {
                    LocalDate d = LocalDate.parse(t.day);
                    haveTemps.put(d, t.t);
                    haveGone.put(d, t.gone);
                }
                ignored.clear();
                takeFile = false;
                update();
                if (correctPending && !preview.shorts.isEmpty()) {
                    correctPending = false;
                    content.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) correct(preview.shorts.get(0));
                        }
                    });
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The file's text (UTF-8), or null when it is larger than {@link #MAX_BYTES}. */
    static String readText(Context c, Uri uri) throws java.io.IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("no stream");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) return null;
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** Works the preview out again from the corrected days, then shows it. */
    void update() {
        preview = DripImport.preview(flows, file.temps, haveFlows, haveTemps, haveGone, today);
        render();
    }

    void renderLine(String s) {
        content.removeAllViews();
        LinearLayout card = Ui.card(this);
        card.addView(Ui.body(this, s));
        content.addView(card);
    }

    void render() {
        content.removeAllViews();
        if (busy) {
            renderLine("Working");
            return;
        }
        if (done != null) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.text(this, done, 16, Ui.TEXT, Ui.MEDIUM));
            TextView ok = Ui.button(this, "Done", Ui.PERIOD, true);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.setMargins(0, Ui.dp(this, 12), 0, 0);
            card.addView(ok, lp);
            content.addView(card);
            return;
        }
        if (problem != null || file == null) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.text(this, problem != null ? problem : "Pick the file drip exported.", 15, problem != null ? Ui.WARN : Ui.TEXT, Ui.REGULAR));
            TextView again = Ui.button(this, "Pick a file", Ui.PERIOD, false);
            again.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick();
                }
            });
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.setMargins(0, Ui.dp(this, 12), 0, 0);
            card.addView(again, lp);
            content.addView(card);
            return;
        }
        final DripImport.Preview pv = preview;
        // ---- what the file holds ----
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.caption(this, "In this file"));
        sum.addView(Ui.gap(this, 4));
        sum.addView(Ui.text(this, pv.summary(Locale.getDefault()), 16, Ui.TEXT, Ui.MEDIUM));
        String spot = pv.spottingLine(file.excludedBleeding);
        if (spot != null) sum.addView(Ui.text(this, spot, 13, Ui.TEXT_2, Ui.REGULAR));
        sum.addView(PeriodCard.note(this, Explain.DRIP_COUNTS));
        String other = DripCsv.notImported(file);
        if (other != null) {
            sum.addView(Ui.gap(this, 8));
            sum.addView(Ui.text(this, other, 13, Ui.TEXT_2, Ui.REGULAR));
            sum.addView(PeriodCard.note(this, Explain.DRIP_OTHER));
        }
        List<String> notes = DripCsv.notes(file);
        if (!notes.isEmpty()) sum.addView(Ui.gap(this, 8));
        for (String n : notes) sum.addView(Ui.text(this, n, 13, Ui.TEXT_2, Ui.REGULAR));
        content.addView(sum);

        // ---- short periods ----
        int open = 0;
        for (DripImport.Short s : pv.shorts) if (!ignored.contains(s.period.start)) open++;
        if (!pv.shorts.isEmpty()) {
            LinearLayout sh = Ui.card(this);
            sh.addView(Ui.caption(this, "Short periods"));
            sh.addView(Ui.gap(this, 4));
            for (final DripImport.Short s : pv.shorts) {
                if (ignored.contains(s.period.start)) continue;
                sh.addView(Ui.text(this, s.line(Locale.getDefault()), 14, Ui.TEXT, Ui.REGULAR));
                LinearLayout row = Ui.hbox(this);
                TextView fix = Ui.smallButton(this, "Correct", Ui.PERIOD), skip = Ui.smallButton(this, "Ignore", Ui.TEXT_2);
                fix.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        correct(s);
                    }
                });
                skip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ignored.add(s.period.start);
                        render();
                    }
                });
                row.addView(fix, PeriodCard.cell(this, 0));
                row.addView(skip, PeriodCard.cell(this, 1));
                LinearLayout.LayoutParams rl = Ui.matchWrap();
                rl.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
                sh.addView(row, rl);
            }
            if (open > 0) {
                TextView all = Ui.smallButton(this, "Ignore all", Ui.TEXT_2);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        for (DripImport.Short s : pv.shorts) ignored.add(s.period.start);
                        render();
                    }
                });
                sh.addView(all, new LinearLayout.LayoutParams(-2, -2));
            }
            int kept = pv.shorts.size() - open;
            if (kept > 0) {
                sh.addView(Ui.gap(this, 6));
                sh.addView(Ui.text(this, (kept == 1 ? "1 short period is" : kept + " short periods are") + " imported as they are.", 13, Ui.TEXT_2, Ui.REGULAR));
            }
            sh.addView(PeriodCard.note(this, Explain.DRIP_SHORT));
            content.addView(sh);
        }

        // ---- days already in Open Ring ----
        String diff = pv.differLine(takeFile), temps = pv.tempsLine();
        if (diff != null || temps != null || pv.sameDays > 0) {
            LinearLayout dc = Ui.card(this);
            dc.addView(Ui.caption(this, "Already in Open Ring"));
            dc.addView(Ui.gap(this, 4));
            if (pv.sameLine() != null) dc.addView(Ui.text(this, pv.sameLine(), 13, Ui.TEXT_2, Ui.REGULAR));
            if (diff != null) {
                dc.addView(Ui.text(this, diff, 14, Ui.TEXT, Ui.MEDIUM));
                StringBuilder sb = new StringBuilder();
                for (DripImport.Diff x : pv.differ) sb.append(sb.length() == 0 ? "" : "\n").append(x.line(Locale.getDefault()));
                TextView list = Ui.text(this, sb.toString(), 12, Ui.TEXT_2, Ui.REGULAR);
                list.setPadding(0, Ui.dp(this, 4), 0, 0);
                dc.addView(list);
                Switch take = new Switch(this);
                take.setText("Take the file's values for these days");
                take.setTextColor(Ui.TEXT);
                take.setChecked(takeFile);
                take.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        takeFile = on;
                        render();
                    }
                });
                LinearLayout.LayoutParams tl = Ui.matchWrap();
                tl.setMargins(0, Ui.dp(this, 8), 0, 0);
                dc.addView(take, tl);
                dc.addView(PeriodCard.note(this, Explain.DRIP_DIFFER));
            }
            if (temps != null) {
                dc.addView(Ui.gap(this, 6));
                dc.addView(Ui.text(this, temps, 13, Ui.TEXT_2, Ui.REGULAR));
            }
            content.addView(dc);
        }

        // ---- import ----
        LinearLayout act = Ui.card(this);
        boolean any = pv.anything(takeFile);
        act.addView(Ui.text(this, pv.willDo(takeFile), 15, Ui.TEXT, Ui.MEDIUM));
        TextView imp = Ui.button(this, "Import", Ui.PERIOD, true);
        imp.setEnabled(any && !demo);
        imp.setAlpha(any && !demo ? 1f : 0.4f);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        LinearLayout.LayoutParams il = Ui.matchWrap();
        il.setMargins(0, Ui.dp(this, 12), 0, 0);
        act.addView(imp, il);
        if (demo) act.addView(Ui.text(this, Explain.DRIP_DEMO, 12, Ui.WARN, Ui.REGULAR));
        TextView cancel = Ui.smallButton(this, "Cancel", Ui.TEXT_2);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.setMargins(0, Ui.dp(this, 8), 0, 0);
        act.addView(cancel, cl);
        act.addView(PeriodCard.note(this, "Nothing is saved until you tap Import. You can undo an import later in Settings, Period tracker."));
        content.addView(act);
    }

    /**
     * Correct: the flagged period's days in the day editor of the preview (the calendar of the Body card, without the
     * estimate's bands), so missing days can be added or the period joined with a close one. Changes only the preview.
     */
    void correct(final DripImport.Short s) {
        final LinearLayout box = Ui.vbox(this);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final YearMonth[] month = {YearMonth.from(s.period.start)};
        fillCorrect(box, s, month);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Period starting " + preview.cycles.date(s.period.start, Locale.getDefault()))
                .setView(sv)
                .setPositiveButton("Done", null)
                .show();
    }

    void fillCorrect(final LinearLayout box, final DripImport.Short s, final YearMonth[] month) {
        box.removeAllViews();
        final Cycles cy = preview.cycles;
        box.addView(Ui.text(this, Explain.DRIP_CORRECT, 13, Ui.TEXT_2, Ui.REGULAR));
        Cycles.Period now = null;
        for (Cycles.Period p : cy.periods) if (!p.start.isAfter(s.period.last) && !p.last.isBefore(s.period.start)) now = p;
        String near = now == null ? null : DripImport.nearestLine(cy, now, Locale.getDefault());
        if (near != null) box.addView(Ui.text(this, near, 13, Ui.TEXT_2, Ui.REGULAR));
        if (now != null && now.days() >= DripImport.SHORT) {
            box.addView(Ui.text(this, "Now a period of " + Texts.count(now.days(), "day", "days") + " starting " + cy.date(now.start, Locale.getDefault()) + ".", 13, Ui.GOOD, Ui.MEDIUM));
        }
        box.addView(Ui.gap(this, 8));
        final TextView title = Ui.text(this, PeriodCard.monthTitle(month[0]), 15, Ui.TEXT, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        final TextView readout = Ui.text(this, "", 12, Ui.TEXT_2, Ui.REGULAR);
        final PeriodCalendar cal = new PeriodCalendar(this, cy, month[0], new PeriodCalendar.Listener() {
            @Override
            public void read(LocalDate day) {
                readout.setText(cy.describe(day, Locale.getDefault()));
            }

            @Override
            public void tap(final LocalDate day) {
                PeriodCard.editor(DripImportActivity.this, cy, day, new PeriodCard.Pick() {
                    @Override
                    public void flow(int f) {
                        if (f == Periods.REMOVED) flows.remove(day);
                        else flows.put(day, f);
                        update();
                        fillCorrect(box, s, month);
                    }

                    @Override
                    public void unend() {}
                });
            }
        });
        cal.bands = false;
        LinearLayout head = Ui.hbox(this);
        TextView prev = Ui.smallButton(this, "‹", Ui.TEXT_2), next = Ui.smallButton(this, "›", Ui.TEXT_2);
        prev.setContentDescription("Month before");
        next.setContentDescription("Month after");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month[0] = month[0].minusMonths(1);
                cal.month(month[0]);
                title.setText(PeriodCard.monthTitle(month[0]));
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month[0] = month[0].plusMonths(1);
                cal.month(month[0]);
                title.setText(PeriodCard.monthTitle(month[0]));
            }
        });
        head.addView(prev);
        head.addView(title, Ui.weight(1));
        head.addView(next);
        box.addView(head, Ui.matchWrap());
        box.addView(cal, Ui.matchWrap());
        readout.setText("Touch and slide across the days to read them. Tap a day to change it.");
        readout.setMinLines(2);
        box.addView(readout);
    }

    /** Import: one transaction in the background; never in demo mode, never into another file than the real one. */
    void save() {
        if (busy || demo) return;
        busy = true;
        render();
        final Context ctx = getApplicationContext();
        final TreeMap<LocalDate, Integer> f = new TreeMap<LocalDate, Integer>(flows);
        final TreeMap<LocalDate, DripCsv.Temp> t = new TreeMap<LocalDate, DripCsv.Temp>(file.temps);
        final boolean take = takeFile;
        new AsyncTask<Void, Void, String>() {
            boolean ok;

            @Override
            protected String doInBackground(Void... v) {
                try {
                    if (Prefs.of(ctx).demo()) return "Demo mode is on, so nothing was imported.";
                    Db db = Db.get(ctx);
                    if (!Db.REAL.equals(db.fileName)) return "Demo mode is on, so nothing was imported.";
                    long now = System.currentTimeMillis();
                    Periods.Imported r = Periods.importDrip(db, f, t, take, now, now / 1000.0);
                    ok = true;
                    if (r.id == 0) return DripImport.NOTHING_NEW;
                    return "Imported " + app.openring.core.Texts.count(r.days, "day", "days") + " and "
                            + app.openring.core.Texts.count(r.temps, "temperature", "temperatures") + ". You can undo it in Settings, Period tracker.";
                } catch (RuntimeException e) {
                    RingManager.get(ctx).log("drip import not saved: " + e.getClass().getSimpleName());
                    return "The import did not finish, so nothing was saved. Please try again.";
                }
            }

            @Override
            protected void onPostExecute(String s) {
                busy = false;
                if (ok) {
                    changed = true;
                    // the estimate may have moved: the reminder follows (it reads the person's own log only)
                    app.openring.sync.PeriodReminder.planLater(ctx);
                    done = s;
                } else {
                    android.widget.Toast.makeText(DripImportActivity.this, s, android.widget.Toast.LENGTH_LONG).show();
                }
                if (!isFinishing()) render();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

}
