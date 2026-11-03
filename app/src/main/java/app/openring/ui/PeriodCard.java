package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import app.openring.core.analysis.Cycles;
import app.openring.data.Db;
import app.openring.data.Periods;
import app.openring.data.Prefs;

/**
 * The period tracker's cards (issue #252, phase 1a1): the full card on Body (status, one tap logging, the next period,
 * the calendar estimates and the month calendar) and the compact one near the bottom of Today. Logging writes one row in
 * the background and loads the page again; it starts no analysis. Nothing here writes a date or a count to the log or the
 * Data check.
 */
final class PeriodCard {
    private PeriodCard() {}

    /** The month the calendar shows, kept while the page is rebuilt; it follows the shown date when that changes. */
    static YearMonth month;
    private static LocalDate monthFor;
    /** Screen test (extra {@code period_edit}, demo mode only): open the day editor once the Body page is built. */
    static boolean editPending;
    /** Screen test (extra {@code period_ask}, demo mode only): show "Have your periods stopped?" until it is answered. */
    static boolean askPending;
    /** The last change, until Undo, the next change or 10 minutes. */
    static Undo undo;

    static final int FLOW = 0, END = 1, UNEND = 2;

    /** What one tap changed, so Undo can put it back in the same file. */
    static final class Undo {
        final boolean demo;
        final String day;
        final int kind;
        /** FLOW: the amount the day held before, null without a row. END: whether the day had an end mark before. */
        final Integer before;
        final boolean hadEnd;
        final String text;
        final long at = System.currentTimeMillis();

        Undo(boolean demo, String day, int kind, Integer before, boolean hadEnd, String text) {
            this.demo = demo;
            this.day = day;
            this.kind = kind;
            this.before = before;
            this.hadEnd = hadEnd;
            this.text = text;
        }
    }

    static String day(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == LocalDate.now().getYear() ? "d MMM" : "d MMM yyyy", Locale.getDefault()));
    }

    // ------------------------------------------------------------------------------ Body

    static View body(final Context c, final DayData d, final Pages.Nav nav) {
        final Cycles cy = d.periods;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Period"));
        if (cy == null) {
            card.addView(Ui.body(c, "The period log could not be read. Your logged days are kept; try again later."));
            return card;
        }
        Locale loc = Locale.getDefault();
        if (cy.running != null) card.setBackground(Ui.rounded(Ui.blend(Ui.CARD, Ui.PERIOD, 0.2f), Ui.dp(c, 18)));
        status(c, card, cy, loc);
        ask(c, card, d, nav);
        buttons(c, card, cy, d, nav);
        String next = cy.nextLine();
        if (next != null) {
            card.addView(Ui.gap(c, 14));
            card.addView(Ui.text(c, next, 16, Ui.TEXT, Ui.MEDIUM));
            card.addView(Ui.text(c, cy.expectedLine(loc), 13, Ui.TEXT_2, Ui.REGULAR));
            card.addView(note(c, Explain.periodRange(cy)));
            card.addView(Ui.gap(c, 10));
            card.addView(Ui.text(c, cy.ovulationLine(loc), 13, Ui.TEXT_2, Ui.REGULAR));
            card.addView(Ui.text(c, cy.fertileLine(loc), 13, Ui.TEXT_2, Ui.REGULAR));
            card.addView(note(c, Explain.periodOvulation(cy)));
        }
        String vary = cy.regularityLine();
        if (vary != null) {
            card.addView(Ui.gap(c, 10));
            card.addView(Ui.text(c, vary, 13, Ui.TEXT_2, Ui.REGULAR));
            card.addView(note(c, Explain.periodVary(cy.used.size())));
        }
        String odd = cy.unusualLine(loc);
        if (odd != null) {
            card.addView(Ui.gap(c, 10));
            card.addView(Ui.text(c, odd, 13, Ui.TEXT_2, Ui.REGULAR));
        }
        card.addView(Ui.gap(c, 14));
        calendar(c, card, cy, d, nav);
        card.addView(Ui.gap(c, 10));
        card.addView(note(c, Cycles.NOT_ADVICE));
        // the card's settings: reminders and "My periods have stopped" live in Settings, card Period tracker
        TextView set = Ui.smallButton(c, "Reminders and settings", Ui.TEXT_2);
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new android.content.Intent(c, SettingsActivity.class).putExtra("section", "period"));
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(set, sl);
        if (editPending) {
            editPending = false;
            card.post(new Runnable() {
                @Override
                public void run() {
                    if (c instanceof android.app.Activity && ((android.app.Activity) c).isFinishing()) return;
                    // a day with an amount, so the shot shows which one is picked
                    edit(c, cy, cy.periods.isEmpty() ? cy.today : cy.periods.get(cy.periods.size() - 1).start.plusDays(1), nav);
                }
            });
        }
        return card;
    }

    static TextView note(Context c, String s) {
        TextView t = Ui.text(c, s, 12, Ui.TEXT_3, Ui.REGULAR);
        t.setPadding(0, Ui.dp(c, 2), 0, 0);
        return t;
    }

    static void status(Context c, LinearLayout card, Cycles cy, Locale loc) {
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, cy.status(loc), 20, Ui.TEXT, Ui.LIGHT));
        String n = cy.statusNote(loc);
        if (!n.isEmpty()) card.addView(Ui.text(c, n, 13, Ui.TEXT_2, Ui.REGULAR));
        String year = cy.yearLine(loc);
        if (year != null) {
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.text(c, year, 13, Ui.TEXT_2, Ui.REGULAR));
        }
    }

    /**
     * "No period for 74 days. Have your periods stopped?" with Yes and Not yet (phase 1a2): asked from the profile age of 40
     * on, after more than twice the typical cycle and at least 60 days without a period; Not yet asks again after 60 days.
     */
    static void ask(final Context c, LinearLayout card, DayData d, final Pages.Nav nav) {
        if (!d.periodAsk && !(askPending && Prefs.of(c).demo())) return;
        final Cycles cy = d.periods;
        LinearLayout box = Ui.vbox(c);
        int pad = Ui.dp(c, 12);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(Ui.rounded(Ui.blend(Ui.CARD, Ui.PERIOD, 0.12f), Ui.dp(c, 14)));
        box.addView(Ui.text(c, cy.askLine(), 15, Ui.TEXT, Ui.MEDIUM));
        box.addView(note(c, Cycles.ASK_NOTE));
        LinearLayout row = Ui.hbox(c);
        TextView yes = Ui.smallButton(c, "Yes", Ui.PERIOD), later = Ui.smallButton(c, "Not yet", Ui.TEXT_2);
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPending = false;
                Prefs.of(c).setPeriodStopped(LocalDate.now().toString());
                app.openring.sync.PeriodReminder.planLater(c);
                nav.reload();
            }
        });
        later.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPending = false;
                Prefs.of(c).setPeriodAskLater(LocalDate.now().toString());
                nav.reload();
            }
        });
        row.addView(yes, cell(c, 0));
        row.addView(later, cell(c, 1));
        LinearLayout.LayoutParams rl = Ui.matchWrap();
        rl.setMargins(0, Ui.dp(c, 8), 0, 0);
        box.addView(row, rl);
        LinearLayout.LayoutParams bl = Ui.matchWrap();
        bl.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(box, bl);
    }

    /** Heavy, Medium, Light (and Ended while a period runs) for the day shown, with "Log for 27 Oct" on a past day, and Undo. */
    static void buttons(final Context c, LinearLayout card, Cycles cy, DayData d, final Pages.Nav nav) {
        final LocalDate day = d.date.isAfter(cy.today) ? cy.today : d.date;
        if (!day.equals(cy.today)) {
            card.addView(Ui.gap(c, 8));
            card.addView(Ui.text(c, "Log for " + day(day), 12, Ui.TEXT_2, Ui.MEDIUM));
        }
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.TOP);
        String[] names = {"Heavy", "Medium", "Light"};
        int[] flows = {Cycles.HEAVY, Cycles.MEDIUM, Cycles.LIGHT};
        boolean end = cy.canEnd(day);
        for (int i = 0; i < 3; i++) {
            final int f = flows[i];
            TextView b = button(c, names[i], new Drops(c, f));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    write(c, nav, day.toString(), FLOW, f);
                }
            });
            row.addView(b, cell(c, i));
        }
        if (end) {
            TextView b = button(c, "Ended", new Drops(c, 0));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    write(c, nav, day.toString(), END, 0);
                }
            });
            row.addView(b, cell(c, 3));
        }
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(row, lp);
        final Undo u = undo;
        if (u != null && u.demo == Prefs.of(c).demo() && System.currentTimeMillis() - u.at < 10 * 60 * 1000L) {
            LinearLayout ur = Ui.hbox(c);
            ur.addView(Ui.text(c, u.text, 13, Ui.TEXT_2, Ui.REGULAR), Ui.weight(1));
            TextView ub = Ui.smallButton(c, "Undo", Ui.TEXT_2);
            ub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    undo(c, nav, u);
                }
            });
            ur.addView(ub);
            LinearLayout.LayoutParams ul = Ui.matchWrap();
            ul.setMargins(0, Ui.dp(c, 8), 0, 0);
            card.addView(ur, ul);
        }
    }

    static LinearLayout.LayoutParams cell(Context c, int i) {
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.setMargins(i == 0 ? 0 : Ui.dp(c, 6), 0, 0, 0);
        return lp;
    }

    static TextView button(Context c, String label, Drawable icon) {
        TextView b = Ui.smallButton(c, label, Ui.PERIOD);
        b.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
        b.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null);
        b.setCompoundDrawablePadding(Ui.dp(c, 4));
        return b;
    }

    // ----------------------------------------------------------------------------- Today

    /** The compact card near the bottom of Today: status, buttons for the day shown, the next period; a tap opens Body. */
    static View today(final Context c, DayData d, final Pages.Nav nav) {
        Cycles cy = d.periods;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Period"));
        if (cy == null) {
            card.addView(Ui.body(c, "The period log could not be read. Your logged days are kept; try again later."));
            return card;
        }
        Locale loc = Locale.getDefault();
        if (cy.running != null) card.setBackground(Ui.rounded(Ui.blend(Ui.CARD, Ui.PERIOD, 0.2f), Ui.dp(c, 18)));
        status(c, card, cy, loc);
        ask(c, card, d, nav);
        buttons(c, card, cy, d, nav);
        String next = cy.nextLine();
        if (next != null) {
            card.addView(Ui.gap(c, 12));
            card.addView(Ui.text(c, next, 15, Ui.TEXT, Ui.MEDIUM));
            card.addView(Ui.text(c, cy.expectedLine(loc), 12, Ui.TEXT_2, Ui.REGULAR));
        }
        card.addView(note(c, "Estimates from the periods you logged, not birth control. Tap here for the calendar and how they are worked out."));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.go(Pages.BODY);
            }
        });
        return card;
    }

    // -------------------------------------------------------------------------- calendar

    static void calendar(final Context c, LinearLayout card, final Cycles cy, DayData d, final Pages.Nav nav) {
        if (monthFor == null || !monthFor.equals(d.date) || month == null) {
            month = YearMonth.from(d.date);
            monthFor = d.date;
        }
        final TextView title = Ui.text(c, "", 15, Ui.TEXT, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        final TextView readout = Ui.text(c, "", 12, Ui.TEXT_2, Ui.REGULAR);
        final PeriodCalendar cal = new PeriodCalendar(c, cy, month, new PeriodCalendar.Listener() {
            @Override
            public void read(LocalDate day) {
                readout.setText(cy.describe(day, Locale.getDefault()));
            }

            @Override
            public void tap(LocalDate day) {
                readout.setText(cy.describe(day, Locale.getDefault()));
                edit(c, cy, day, nav);
            }
        });
        final String hint = "Touch and slide across the days to read them. Tap a day to change it.";
        readout.setText(hint);
        LinearLayout head = Ui.hbox(c);
        TextView prev = Ui.smallButton(c, "‹", Ui.TEXT_2), next = Ui.smallButton(c, "›", Ui.TEXT_2);
        prev.setContentDescription("Month before");
        next.setContentDescription("Month after");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.minusMonths(1), cal, title, readout, hint);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.plusMonths(1), cal, title, readout, hint);
            }
        });
        title.setText(monthTitle(month));
        head.addView(prev);
        head.addView(title, Ui.weight(1));
        head.addView(next);
        card.addView(head, Ui.matchWrap());
        card.addView(Ui.gap(c, 6));
        card.addView(cal, Ui.matchWrap());
        card.addView(Ui.gap(c, 6));
        readout.setMinLines(3);
        card.addView(readout);
    }

    static void showMonth(YearMonth m, PeriodCalendar cal, TextView title, TextView readout, String hint) {
        month = m;
        cal.month(m);
        title.setText(monthTitle(m));
        readout.setText(hint);
    }

    static String monthTitle(YearMonth m) {
        return m.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()));
    }

    /** The day editor: Heavy, Medium, Light, Spotting, Nothing; "Not the end" when the day holds an end mark. Future days are refused. */
    static void edit(final Context c, Cycles cy, final LocalDate day, final Pages.Nav nav) {
        editor(c, cy, day, new Pick() {
            @Override
            public void flow(int flow) {
                write(c, nav, day.toString(), FLOW, flow);
            }

            @Override
            public void unend() {
                write(c, nav, day.toString(), UNEND, 0);
            }
        });
    }

    /** What the day editor's choice does: write it to the log (Body), or change the drip import's preview (phase 1b). */
    interface Pick {
        void flow(int flow);

        void unend();
    }

    /**
     * The day editor with its choices handed to {@code pick} (the same dialog for the log and for the drip import's
     * preview); "Not the end" only when {@code cy} holds an end mark on the day. Future days are refused.
     */
    static void editor(final Context c, Cycles cy, final LocalDate day, final Pick pick) {
        if (day.isAfter(LocalDate.now())) {
            Toast.makeText(c, "This day is still to come.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = {"Heavy", "Medium", "Light", "Spotting", "Nothing"};
        final int[] flows = {Cycles.HEAVY, Cycles.MEDIUM, Cycles.LIGHT, Cycles.SPOTTING, Periods.REMOVED};
        Integer now = cy.flows.get(day);
        int checked = -1;
        for (int i = 0; i < flows.length; i++) if (now != null && flows[i] == now) checked = i;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(c)
                .setTitle(day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())))
                .setSingleChoiceItems(names, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int which) {
                        dlg.dismiss();
                        pick.flow(flows[which]);
                    }
                })
                .setNegativeButton("Cancel", null);
        if (cy.ends.contains(day)) {
            b.setNeutralButton("Not the end", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dlg, int which) {
                    pick.unend();
                }
            });
        }
        b.show();
    }

    // ---------------------------------------------------------------------------- writes

    /** One change in the background (one statement), then the page loads again. Starts no analysis. */
    static void write(Context c, final Pages.Nav nav, final String day, final int kind, final int flow) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        new AsyncTask<Void, Void, Undo>() {
            @Override
            protected Undo doInBackground(Void... v) {
                try {
                    // the database each time, never kept: a restore swaps the file (Periods javadoc)
                    Db db = Db.get(ctx);
                    boolean demo = Prefs.of(ctx).demo();
                    String when = day(LocalDate.parse(day));
                    if (kind == FLOW) {
                        Periods.Day before = Periods.day(db, day);
                        Periods.setFlow(db, day, flow);
                        // a period day logged on or after "My periods have stopped" ends it (phase 1a2), so the switch says so too
                        Prefs p = Prefs.of(ctx);
                        LocalDate stopped = Cycles.day(p.periodStopped());
                        if (flow >= Cycles.LIGHT && stopped != null && !LocalDate.parse(day).isBefore(stopped)) p.setPeriodStopped(null);
                        String what = flow == Periods.REMOVED ? "Entry taken away from " + when + "." : Cycles.amount(flow) + " logged for " + when + ".";
                        return new Undo(demo, day, FLOW, before == null ? null : before.flow, false, what);
                    }
                    boolean had = !Periods.ends(db, day, day).isEmpty();
                    if (kind == END) {
                        Periods.setEnd(db, day);
                        return new Undo(demo, day, END, null, had, "Ended logged for " + when + ".");
                    }
                    Periods.clearEnd(db, day);
                    return new Undo(demo, day, UNEND, null, had, "End mark taken away from " + when + ".");
                } catch (RuntimeException e) {
                    // the error's kind only, never a date or an amount (the log can be shared)
                    app.openring.ble.RingManager.get(ctx).log("period log not saved: " + e.getClass().getSimpleName());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Undo u) {
                if (u == null) Toast.makeText(screen, "Could not save. Please try again.", Toast.LENGTH_LONG).show();
                else undo = u;
                // the estimate may have moved: the reminder follows (it reads the person's own log only)
                app.openring.sync.PeriodReminder.planLater(ctx);
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Puts back what {@code u} changed, in the file it changed (never across demo mode). */
    static void undo(Context c, final Pages.Nav nav, final Undo u) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        undo = null;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    if (Prefs.of(ctx).demo() != u.demo) return false;
                    Db db = Db.get(ctx);
                    if (u.kind == FLOW) Periods.setFlow(db, u.day, u.before == null ? Periods.REMOVED : u.before);
                    else if (u.kind == END && !u.hadEnd) Periods.clearEnd(db, u.day);
                    else if (u.kind == UNEND && u.hadEnd) Periods.setEnd(db, u.day);
                    return true;
                } catch (RuntimeException e) {
                    app.openring.ble.RingManager.get(ctx).log("period log not saved: " + e.getClass().getSimpleName());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) Toast.makeText(screen, "Could not undo. Please try again.", Toast.LENGTH_LONG).show();
                app.openring.sync.PeriodReminder.planLater(ctx);
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // ------------------------------------------------------------------------------ drops

    /** One, two or three drops over a button (light, medium, heavy), or a small cross for Ended ({@code n} 0). */
    static final class Drops extends Drawable {
        private final int n;
        private final float r, gap, h;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        Drops(Context c, int n) {
            this.n = n;
            r = Ui.dp(c, 3.2f);
            gap = Ui.dp(c, 3);
            h = Ui.dp(c, 11);
            p.setColor(Ui.PERIOD);
            p.setStrokeWidth(Ui.dp(c, 1.6f));
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            int k = Math.max(1, n);
            return (int) Math.ceil(k * 2 * r + (k - 1) * gap);
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) Math.ceil(h);
        }

        @Override
        public void draw(Canvas cv) {
            android.graphics.Rect b = getBounds();
            if (n == 0) {
                float cx = b.exactCenterX(), cy = b.exactCenterY(), s = h * 0.35f;
                p.setStyle(Paint.Style.STROKE);
                cv.drawLine(cx - s, cy - s, cx + s, cy + s, p);
                cv.drawLine(cx - s, cy + s, cx + s, cy - s, p);
                return;
            }
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float cx = b.left + r + i * (2 * r + gap), bottom = b.bottom;
                path.reset();
                path.moveTo(cx, b.top);
                oval.set(cx - r, bottom - 2 * r, cx + r, bottom);
                path.lineTo(cx + r * 0.87f, bottom - r * 1.5f);
                path.arcTo(oval, -30, 240, false);
                path.close();
                cv.drawPath(path, p);
            }
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
