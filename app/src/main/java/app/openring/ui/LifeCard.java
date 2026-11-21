package app.openring.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.LifeTags;
import app.openring.data.Db;
import app.openring.data.LifeLog;
import app.openring.data.Prefs;

/**
 * Life factors (issue #292, phase 1): Today's row "What was today like?" with one tap chips for the day shown and its More
 * (All factors, Several days, Add your own, Choose the chips on the card), the line beside the night on Sleep and on the
 * Readiness morning, and Body's card with the month view. Every write is one statement or one transaction in the background,
 * then the page loads again; nothing starts an analysis or changes a score. The log gets an error's kind only, never a tag,
 * a name or a date; nothing here ever reaches a notification.
 */
final class LifeCard {
    private LifeCard() {}

    /** Today's More is open, kept while the page is rebuilt (extra {@code life_more} opens it for the screen test). */
    static final boolean[] moreOpen = {false};
    /** How it works on Today's row and on Body's card. */
    static final boolean[] howOpen = {false}, bodyHowOpen = {false};
    /** The last change, until Undo, the next change or 10 minutes. */
    static Undo undo;
    /** The month Body's calendar shows, kept while the page is rebuilt; it follows the shown date when that changes. */
    static YearMonth month;
    private static LocalDate monthFor;
    /**
     * Screen test (extra {@code life_read}, demo mode only): Body's month view opens with the day this many days before today
     * read, as a touch on it would (the month it lies in, its frame, its line). A computed day, so the shot reads a past day
     * with tags whatever the weekday or month CI runs on. -1: none.
     */
    static int readPending = -1;

    /**
     * What one change wrote, so Undo can put every row back in the same file. It names the database it wrote into: after a
     * restore or going back to a copy (a new {@code Db}), or Delete all (which clears it, {@link #forget}), Undo writes nothing.
     */
    static final class Undo {
        final boolean demo;
        final Db db;
        final String tag;
        final List<String> days;
        /** Each day's row before, null where there was none. */
        final List<LifeLog.Row> before;
        final String text;
        final long at = System.currentTimeMillis();

        Undo(Db db, boolean demo, String tag, List<String> days, List<LifeLog.Row> before, String text) {
            this.db = db;
            this.demo = demo;
            this.tag = tag;
            this.days = days;
            this.before = before;
            this.text = text;
        }
    }

    /** Delete all, a restore or going back to a copy: the last change belongs to a file that is gone, so no Undo for it. */
    static void forget() {
        undo = null;
    }

    static int year() {
        return LocalDate.now().getYear();
    }

    /** The day the row logs for: the shown day, never one still to come. */
    static LocalDate dayOf(DayData d) {
        LocalDate today = LocalDate.now();
        return d.date.isAfter(today) ? today : d.date;
    }

    // ------------------------------------------------------------------------------ Today

    /** "What was today like?": the chips of the card for the day shown, More, the note, and Undo. */
    static View today(final Context c, final DayData d, final Pages.Nav nav) {
        final LocalDate day = dayOf(d);
        boolean isToday = day.equals(LocalDate.now());
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, isToday ? LifeTags.TITLE : LifeTags.TITLE_PAST));
        if (!d.lifeOk || d.life == null) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.body(c, LifeTags.UNREADABLE));
            return card;
        }
        if (!isToday) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.text(c, LifeTags.logFor(day, year(), Locale.getDefault()), 12, Ui.TEXT_2, Ui.MEDIUM));
        }
        List<String> on = d.lifeDays.get(day);
        if (on == null) on = new ArrayList<String>();
        EqualGrid grid = new EqualGrid(c, 3, 6);
        for (LifeTags.Tag t : d.life.chips()) grid.addView(chip(c, nav, day, t, on.contains(t.key)));
        final TextView more = chipView(c, moreOpen[0] ? LifeTags.LESS : LifeTags.MORE, false);
        more.setContentDescription(moreOpen[0] ? "Fewer life factors" : "More life factors and choices");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moreOpen[0] = !moreOpen[0];
                nav.reload();
            }
        });
        grid.addView(more);
        LinearLayout.LayoutParams gl = Ui.matchWrap();
        gl.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(grid, gl);
        TextView note = Ui.text(c, LifeTags.NOTE, 12, Ui.TEXT_3, Ui.REGULAR);
        note.setPadding(0, Ui.dp(c, 8), 0, 0);
        card.addView(note);
        undoRow(c, card, nav);
        if (moreOpen[0]) more(c, card, d, day, on, nav);
        return card;
    }

    /** One chip: filled in the life colour when the day has the tag; a tap adds it or takes it away. */
    static TextView chip(final Context c, final Pages.Nav nav, final LocalDate day, final LifeTags.Tag t, final boolean on) {
        TextView b = chipView(c, t.name, on);
        b.setContentDescription(t.name + (on ? ", logged, tap to take away" : ", tap to log"));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(c, nav, day, t, !on);
            }
        });
        return b;
    }

    static TextView chipView(Context c, String label, boolean on) {
        TextView b = Ui.button(c, label, on ? Ui.LIFE : Ui.TEXT_2, on);
        b.setTextSize(13);
        int ph = Ui.dp(c, 6), pv = Ui.dp(c, 8);
        b.setPadding(ph, pv, ph, pv);
        b.setMaxLines(2);
        b.setMinHeight(Ui.dp(c, 40));
        return b;
    }

    /** All factors, the three ways on, and How it works. */
    static void more(final Context c, LinearLayout card, final DayData d, final LocalDate day, List<String> on, final Pages.Nav nav) {
        card.addView(Ui.divider(c));
        card.addView(Ui.text(c, LifeTags.ALL, 13, Ui.TEXT_2, Ui.MEDIUM));
        EqualGrid all = new EqualGrid(c, 3, 6);
        for (LifeTags.Tag t : d.life.loggable()) all.addView(chip(c, nav, day, t, on.contains(t.key)));
        LinearLayout.LayoutParams al = Ui.matchWrap();
        al.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(all, al);
        EqualGrid ways = new EqualGrid(c, 2, 6);
        ways.addView(way(c, LifeTags.SEVERAL, new Runnable() {
            @Override
            public void run() {
                several(c, d, nav);
            }
        }));
        ways.addView(way(c, LifeTags.ADD_OWN, new Runnable() {
            @Override
            public void run() {
                addOwn(c, nav);
            }
        }));
        ways.addView(way(c, LifeTags.CHOOSE, new Runnable() {
            @Override
            public void run() {
                choose(c, d, nav);
            }
        }));
        if (!d.life.own().isEmpty()) {
            ways.addView(way(c, LifeTags.REMOVE_OWN, new Runnable() {
                @Override
                public void run() {
                    removeOwn(c, d, nav);
                }
            }));
        }
        LinearLayout.LayoutParams wl = Ui.matchWrap();
        wl.setMargins(0, Ui.dp(c, 12), 0, 0);
        card.addView(ways, wl);
        List<String> how = new ArrayList<String>();
        how.add(Explain.LIFE_ROW);
        how.add(Explain.LIFE_MORE);
        how.add(Explain.LIFE_PRIVATE);
        how.add(Explain.LIFE_NO_EFFECT);
        card.addView(Ui.howItWorks(c, howOpen, how), Ui.matchWrap());
    }

    static TextView way(Context c, String label, final Runnable r) {
        TextView b = Ui.smallButton(c, label, Ui.TEXT_2);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    static void undoRow(final Context c, LinearLayout card, final Pages.Nav nav) {
        final Undo u = undo;
        if (u == null || u.demo != Prefs.of(c).demo() || System.currentTimeMillis() - u.at >= 10 * 60 * 1000L) return;
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

    // ------------------------------------------------------------------------------ the ways on

    /** Several days: the factor, the first day, the last day, then one line to confirm ("Holiday from 3 Nov to 10 Nov"). */
    static void several(final Context c, final DayData d, final Pages.Nav nav) {
        final List<LifeTags.Tag> tags = d.life.loggable();
        String[] names = new String[tags.size()];
        for (int i = 0; i < names.length; i++) names[i] = tags.get(i).name;
        new android.app.AlertDialog.Builder(c).setTitle(LifeTags.SEVERAL)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        final LifeTags.Tag t = tags.get(which);
                        final LocalDate start = dayOf(d);
                        pickDay(c, "First day of " + t.name, start, new Picked() {
                            @Override
                            public void day(final LocalDate from) {
                                pickDay(c, "Last day of " + t.name, from, new Picked() {
                                    @Override
                                    public void day(LocalDate to) {
                                        confirmSpan(c, nav, t, from, to);
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    interface Picked {
        void day(LocalDate day);
    }

    static void pickDay(Context c, String title, LocalDate at, final Picked p) {
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(c, new android.app.DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(android.widget.DatePicker v, int y, int m, int dom) {
                p.day(LocalDate.of(y, m + 1, dom));
            }
        }, at.getYear(), at.getMonthValue() - 1, at.getDayOfMonth());
        // days still to come cannot be picked (several days end today at the latest)
        dlg.getDatePicker().setMaxDate(System.currentTimeMillis());
        dlg.setTitle(title);
        dlg.show();
    }

    static void confirmSpan(final Context c, final Pages.Nav nav, final LifeTags.Tag t, final LocalDate from, final LocalDate to) {
        if (to.isBefore(from)) {
            Toast.makeText(c, LifeTags.SPAN_BACKWARDS, Toast.LENGTH_LONG).show();
            return;
        }
        if (to.isAfter(LocalDate.now())) {
            Toast.makeText(c, LifeTags.SPAN_FUTURE, Toast.LENGTH_LONG).show();
            return;
        }
        final int n = LifeTags.span(from, to).size();
        new android.app.AlertDialog.Builder(c).setTitle(LifeTags.spanLine(t.name, from, to, year(), Locale.getDefault()))
                .setMessage(LifeTags.spanDone(n))
                .setPositiveButton("Log", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        final List<String> days = new ArrayList<String>();
                        for (LocalDate x : LifeTags.span(from, to)) days.add(x.toString());
                        run(c, nav, new Job() {
                            @Override
                            public String run(Db db, boolean demo) {
                                List<LifeLog.Row> before = new ArrayList<LifeLog.Row>();
                                for (String x : days) before.add(LifeLog.row(db, x, t.key));
                                LifeLog.setSpan(db, from, to, t.key, System.currentTimeMillis(), System.currentTimeMillis() / 1000.0);
                                String text = LifeTags.spanDone(t.name, from, to, year(), Locale.getDefault());
                                undo = new Undo(db, demo, t.key, days, before, text);
                                return null;
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Add your own: a name (hint "For example: late dinner"), then it is on the card. */
    static void addOwn(final Context c, final Pages.Nav nav) {
        final EditText e = new EditText(c);
        e.setHint(LifeTags.OWN_HINT);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(LifeTags.NAME_MAX)});
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_3);
        e.setBackground(Ui.input(c));
        int p = Ui.dp(c, 12);
        e.setPadding(p, p, p, p);
        LinearLayout box = Ui.vbox(c);
        int m = Ui.dp(c, 20);
        box.setPadding(m, Ui.dp(c, 8), m, 0);
        box.addView(e, Ui.matchWrap());
        new android.app.AlertDialog.Builder(c).setTitle(LifeTags.ADD_OWN).setView(box)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        final String typed = e.getText().toString();
                        run(c, nav, new Job() {
                            @Override
                            public String run(Db db, boolean demo) {
                                String key = LifeLog.addOwn(db, typed, System.currentTimeMillis());
                                // a new own tag shows as a chip on the card at once; only a refusal needs a sentence
                                return key == null ? LifeTags.OWN_REFUSED : null;
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Choose the chips on the card: every factor with a tick; the ones ticked before keep their place, new ones go last. */
    static void choose(final Context c, final DayData d, final Pages.Nav nav) {
        final List<LifeTags.Tag> tags = d.life.loggable();
        final List<String> was = new ArrayList<String>();
        for (LifeTags.Tag t : d.life.chips()) was.add(t.key);
        String[] names = new String[tags.size()];
        final boolean[] ticked = new boolean[tags.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = tags.get(i).name;
            ticked[i] = was.contains(tags.get(i).key);
        }
        new android.app.AlertDialog.Builder(c).setTitle(LifeTags.CHOOSE)
                .setMultiChoiceItems(names, ticked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which, boolean on) {
                        ticked[which] = on;
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        final List<String> keys = new ArrayList<String>();
                        for (String k : was) {
                            for (int i = 0; i < ticked.length; i++) if (ticked[i] && tags.get(i).key.equals(k)) keys.add(k);
                        }
                        for (int i = 0; i < ticked.length; i++) if (ticked[i] && !keys.contains(tags.get(i).key)) keys.add(tags.get(i).key);
                        run(c, nav, new Job() {
                            @Override
                            public String run(Db db, boolean demo) {
                                LifeLog.setChips(db, keys, System.currentTimeMillis() / 1000.0);
                                return null;
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Remove one of your own: the days logged with it stay (Body still shows them). */
    static void removeOwn(final Context c, final DayData d, final Pages.Nav nav) {
        final List<LifeTags.Tag> own = d.life.own();
        String[] names = new String[own.size()];
        for (int i = 0; i < names.length; i++) names[i] = own.get(i).name;
        new android.app.AlertDialog.Builder(c).setTitle(LifeTags.REMOVE_OWN)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        final LifeTags.Tag t = own.get(which);
                        new android.app.AlertDialog.Builder(c).setTitle("Remove " + t.name + "?")
                                .setMessage("It goes from the card and the list. The days you logged with it stay.")
                                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w) {
                                        run(c, nav, new Job() {
                                            @Override
                                            public String run(Db db, boolean demo) {
                                                LifeLog.removeOwn(db, t.key, System.currentTimeMillis() / 1000.0);
                                                return null;
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------------------ writes

    /** One write in the background; returns a sentence to show (a constant of {@code LifeTags}, so its length is tested), or null. */
    interface Job {
        String run(Db db, boolean demo);
    }

    /** A tap on a chip: the tag on or off for {@code day}, one statement, with Undo. */
    static void tap(final Context c, final Pages.Nav nav, final LocalDate day, final LifeTags.Tag t, final boolean on) {
        run(c, nav, new Job() {
            @Override
            public String run(Db db, boolean demo) {
                String key = day.toString();
                // the rows the tap writes, as they are now: the tag's, and taking it off, those of its aliases (#422)
                List<LifeLog.Row> rows = LifeLog.before(db, key, t.key, on);
                LifeLog.set(db, key, t.key, on);
                List<String> days = new ArrayList<String>();
                for (int i = 0; i < rows.size(); i++) days.add(key);
                undo = new Undo(db, demo, t.key, days, rows, LifeTags.tapLine(t.name, on, day, year(), Locale.getDefault()));
                return null;
            }
        });
    }

    /**
     * Runs {@code job} on a background thread with the database of this moment (never kept: a restore swaps the file), then
     * loads the page again. Starts no analysis. A failure shows a short sentence; the log gets the error's kind only.
     */
    static void run(Context c, final Pages.Nav nav, final Job job) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... v) {
                try {
                    return new String[] {job.run(Db.get(ctx), Prefs.of(ctx).demo())};
                } catch (RuntimeException e) {
                    app.openring.ble.RingManager.get(ctx).log("life factors not saved: " + e.getClass().getSimpleName());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String[] said) {
                if (said == null) Toast.makeText(screen, LifeTags.NOT_SAVED, Toast.LENGTH_LONG).show();
                else if (said[0] != null) Toast.makeText(screen, said[0], Toast.LENGTH_LONG).show();
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Puts back every row {@code u} changed, in the file it changed (never across demo mode), in one transaction. */
    static void undo(Context c, final Pages.Nav nav, final Undo u) {
        final Context ctx = c.getApplicationContext();
        final Context screen = c;
        undo = null;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    if (Prefs.of(ctx).demo() != u.demo) return false;
                    final Db db = Db.get(ctx);
                    // the file was swapped since (a restore, going back to a copy): its rows are not the ones changed
                    if (db != u.db) return false;
                    final double now = System.currentTimeMillis() / 1000.0;
                    db.inTransaction(new Runnable() {
                        @Override
                        public void run() {
                            for (int i = 0; i < u.days.size(); i++) LifeLog.restore(db, u.days.get(i), u.tag, u.before.get(i), now);
                        }
                    });
                    return true;
                } catch (RuntimeException e) {
                    app.openring.ble.RingManager.get(ctx).log("life factors not undone: " + e.getClass().getSimpleName());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) Toast.makeText(screen, LifeTags.NOT_UNDONE, Toast.LENGTH_LONG).show();
                nav.reload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // ------------------------------------------------------------------------------ Sleep and Readiness

    /** The names of the tags of the day before {@code d}'s date, in the list order; empty when none or unreadable. */
    static List<String> dayBefore(DayData d) {
        if (!d.lifeOk || d.life == null) return new ArrayList<String>();
        return d.life.names(d.lifeDays.get(d.date.minusDays(1)));
    }

    /** Sleep: "Before this night: Alcohol, Meditation." under the night, null without tags. A tap opens Body's month. */
    static View night(Context c, DayData d, Pages.Nav nav) {
        return line(c, LifeTags.beforeNight(dayBefore(d)), nav);
    }

    /** Readiness: "Logged yesterday: Alcohol." the morning after, null without tags. */
    static View morning(Context c, DayData d, Pages.Nav nav) {
        return line(c, LifeTags.morning(dayBefore(d), d.date, LocalDate.now(), Locale.getDefault()), nav);
    }

    static View line(Context c, String text, final Pages.Nav nav) {
        if (text == null) return null;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, LifeTags.BODY_TITLE));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, text, 15, Ui.TEXT, Ui.REGULAR));
        card.addView(Ui.text(c, "As you logged them on Today. Tap for the month on Body.", 12, Ui.TEXT_3, Ui.REGULAR));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.go(Pages.BODY);
            }
        });
        return card;
    }

    // ------------------------------------------------------------------------------ Body

    /** Body's card: the month view of what was logged, touch reads a day, the month's counts under it. */
    static View body(final Context c, final DayData d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, LifeTags.BODY_TITLE));
        if (!d.lifeOk || d.life == null) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.body(c, LifeTags.UNREADABLE));
            return card;
        }
        if (d.lifeDays.isEmpty()) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.body(c, LifeTags.NONE_YET));
        }
        if (monthFor == null || !monthFor.equals(d.date) || month == null) {
            month = YearMonth.from(d.date);
            monthFor = d.date;
        }
        final LocalDate today = LocalDate.now();
        LocalDate readNow = null;
        if (readPending >= 0) {
            readNow = today.minusDays(readPending);
            readPending = -1;
            month = YearMonth.from(readNow);
        }
        final TextView title = Ui.text(c, "", 15, Ui.TEXT, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        final TextView readout = Ui.text(c, LifeTags.MONTH_HINT, 12, Ui.TEXT_2, Ui.REGULAR);
        readout.setMinLines(2);
        final TextView counts = Ui.text(c, "", 13, Ui.TEXT_2, Ui.REGULAR);
        final LifeCalendar cal = new LifeCalendar(c, d.lifeDays, today, month, new LifeCalendar.Listener() {
            @Override
            public void read(LocalDate day) {
                readout.setText(LifeTags.dayLine(day, d.life.names(d.lifeDays.get(day)), today, Locale.getDefault()));
            }

            @Override
            public void tap(LocalDate day) {
                // the tap already read the day (readOnTap); logging stays on Today, for the day shown there
            }
        });
        LinearLayout head = Ui.hbox(c);
        TextView prev = Ui.smallButton(c, "‹", Ui.TEXT_2), next = Ui.smallButton(c, "›", Ui.TEXT_2);
        prev.setContentDescription("Month before");
        next.setContentDescription("Month after");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.minusMonths(1), cal, title, readout, counts, d);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.plusMonths(1), cal, title, readout, counts, d);
            }
        });
        title.setText(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())));
        counts.setText(LifeTags.monthLine(d.lifeDays, month, d.life));
        LinearLayout.LayoutParams hl = Ui.matchWrap();
        hl.setMargins(0, Ui.dp(c, 8), 0, 0);
        head.addView(prev);
        head.addView(title, Ui.weight(1));
        head.addView(next);
        card.addView(head, hl);
        card.addView(Ui.gap(c, 6));
        card.addView(cal, Ui.matchWrap());
        card.addView(Ui.gap(c, 6));
        card.addView(readout);
        card.addView(Ui.gap(c, 8));
        card.addView(counts);
        if (readNow != null) {
            cal.read(readNow);
            readout.setText(LifeTags.dayLine(readNow, d.life.names(d.lifeDays.get(readNow)), today, Locale.getDefault()));
        }
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, LifeTags.NO_EFFECT_YET, 12, Ui.TEXT_3, Ui.REGULAR));
        List<String> how = new ArrayList<String>();
        how.add(Explain.LIFE_MONTH);
        how.add(Explain.LIFE_NO_EFFECT);
        how.add(Explain.LIFE_PRIVATE);
        card.addView(Ui.howItWorks(c, bodyHowOpen, how), Ui.matchWrap());
        return card;
    }

    static void showMonth(YearMonth m, LifeCalendar cal, TextView title, TextView readout, TextView counts, DayData d) {
        month = m;
        cal.month(m);
        title.setText(m.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())));
        readout.setText(LifeTags.MONTH_HINT);
        counts.setText(LifeTags.monthLine(d.lifeDays, m, d.life));
    }
}
