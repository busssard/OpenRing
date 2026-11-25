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
import app.openring.core.LifeTimes;
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
     * Screen test (extra {@code life_time}, demo mode only): "When and how long?" opens once Today's row is built, for Sauna (or
     * the first chip that takes a time) on the day shown. Nothing is saved without a tap.
     */
    static boolean timePending;

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
        for (LifeTags.Tag t : d.life.chips()) grid.addView(cell(c, nav, d, day, t, on.contains(t.key)));
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
        if (timePending) {
            timePending = false;
            LifeTags.Tag pick = null;
            for (LifeTags.Tag t : d.life.chips()) {
                if (LifeTags.SAUNA.equals(t.key)) pick = t;
                else if (pick == null && LifeTimes.timed(t.key)) pick = t;
            }
            if (pick != null) {
                final LifeTags.Tag t = pick;
                card.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c instanceof android.app.Activity && ((android.app.Activity) c).isFinishing()) return;
                        sheet(c, nav, d, day, t);
                    }
                });
            }
        }
        return card;
    }

    /**
     * One chip: filled in the life colour when the day has the tag; a tap adds it or takes it away. A tag of a moment (issue
     * #435) also opens "When and how long?" on a long press; a logged one shows its time on a second line.
     */
    static TextView chip(final Context c, final Pages.Nav nav, final DayData d, final LocalDate day, final LifeTags.Tag t, final boolean on) {
        final boolean timed = LifeTimes.timed(t.key);
        LifeTimes.Entry at = on && timed ? LifeTimes.find(d.lifeTimes, day, t.key, d.life) : null;
        String when = at == null ? null : LifeTimes.chipLine(at.time, at.minutes);
        TextView b = chipView(c, when == null ? t.name : t.name + "\n" + when, on);
        b.setContentDescription(t.name + (when == null ? "" : ", " + when) + (on ? ", logged, tap to take away" : ", tap to log")
                + (timed ? ", hold to add when and how long" : ""));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(c, nav, day, t, !on);
            }
        });
        if (timed) {
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    sheet(c, nav, d, day, t);
                    return true;
                }
            });
        }
        return b;
    }

    /**
     * A chip in the grid: a logged tag of a moment gets the small clock in its corner (issue #435), a tap on it opens "When and
     * how long?" (the long press does the same); every other chip as it was.
     */
    static View cell(final Context c, final Pages.Nav nav, final DayData d, final LocalDate day, final LifeTags.Tag t, boolean on) {
        TextView b = chip(c, nav, d, day, t, on);
        if (!on || !LifeTimes.timed(t.key)) return b;
        // room for the clock on the right, so a long name never runs under it
        b.setPadding(b.getPaddingLeft(), b.getPaddingTop(), b.getPaddingLeft() + Ui.dp(c, 14), b.getPaddingBottom());
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.addView(b, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Clock clock = new Clock(c);
        clock.setContentDescription("When and how long for " + t.name);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet(c, nav, d, day, t);
            }
        });
        f.addView(clock, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.TOP | Gravity.END));
        return f;
    }

    /** The small clock in a logged chip's corner: a circle with two hands, in the chip's text colour. */
    static final class Clock extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Clock(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 1.3f));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(0xFF0A0E17);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float r = Ui.dp(getContext(), 6);
            float cx = getWidth() - Ui.dp(getContext(), 11), cy = Ui.dp(getContext(), 11);
            cv.drawCircle(cx, cy, r, p);
            cv.drawLine(cx, cy, cx, cy - r * 0.65f, p);
            cv.drawLine(cx, cy, cx + r * 0.5f, cy, p);
        }
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
        for (LifeTags.Tag t : d.life.loggable()) all.addView(cell(c, nav, d, day, t, on.contains(t.key)));
        LinearLayout.LayoutParams al = Ui.matchWrap();
        al.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(all, al);
        TextView hold = Ui.text(c, LifeTimes.HOLD_HINT, 12, Ui.TEXT_3, Ui.REGULAR);
        hold.setPadding(0, Ui.dp(c, 6), 0, 0);
        card.addView(hold);
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
        how.add(Explain.LIFE_TIMES);
        how.add(Explain.LIFE_PRIVATE);
        how.add(Explain.LIFE_NO_EFFECT);
        card.addView(Ui.howItWorks(c, howOpen, how), Ui.matchWrap());
    }

    // ------------------------------------------------------------------------------ when and how long (issue #435)

    /**
     * "When and how long?": From (an hour and a minute wheel, in 5 minutes, starting at the time stored, or now minus the tag's
     * usual length) and How long (the tag's usual lengths; a second tap on the picked one leaves no length; Alcohol and Caffeine
     * late have a time only). Save logs the day with them, Without a time keeps the day logged without either. Nothing is
     * saved without a tap; Undo puts the row back as it was.
     */
    static void sheet(final Context c, final Pages.Nav nav, DayData d, final LocalDate day, final LifeTags.Tag t) {
        LifeTimes.Entry at = LifeTimes.find(d.lifeTimes, day, t.key, d.life);
        java.time.LocalTime now = java.time.LocalTime.now();
        int start = at != null ? LifeTimes.parse(at.time) : LifeTimes.startFor(t.key, now.getHour() * 60 + now.getMinute());
        final int[] lengths = LifeTimes.lengths(t.key);
        final Integer[] picked = {at != null ? at.minutes : lengths.length > 0 ? (Integer) LifeTimes.usual(t.key) : null};
        LinearLayout box = Ui.vbox(c);
        int m = Ui.dp(c, 20);
        box.setPadding(m, Ui.dp(c, 4), m, 0);
        box.addView(Ui.text(c, LifeTags.spanLine(t.name, day, day, year(), Locale.getDefault()), 14, Ui.TEXT_2, Ui.REGULAR));
        box.addView(Ui.gap(c, 10));
        box.addView(Ui.text(c, LifeTimes.FROM, 13, Ui.TEXT_2, Ui.MEDIUM));
        final android.widget.NumberPicker hour = new android.widget.NumberPicker(c), minute = new android.widget.NumberPicker(c);
        String[] hours = new String[24], minutes = new String[12];
        for (int i = 0; i < 24; i++) hours[i] = String.format(Locale.ROOT, "%02d", i);
        for (int i = 0; i < 12; i++) minutes[i] = String.format(Locale.ROOT, "%02d", i * 5);
        hour.setMinValue(0);
        hour.setMaxValue(23);
        hour.setDisplayedValues(hours);
        hour.setValue(Math.max(0, start) / 60);
        hour.setContentDescription("Hour");
        // wheels only: typed text would reach the picker only when it loses focus, after Save read the old value (review of #444)
        hour.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        minute.setMinValue(0);
        minute.setMaxValue(11);
        minute.setDisplayedValues(minutes);
        minute.setValue(Math.max(0, start) % 60 / 5);
        minute.setContentDescription("Minutes");
        minute.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout wheels = Ui.hbox(c);
        wheels.setGravity(Gravity.CENTER);
        wheels.addView(hour);
        TextView colon = Ui.text(c, ":", 20, Ui.TEXT, Ui.MEDIUM);
        colon.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        wheels.addView(colon);
        wheels.addView(minute);
        box.addView(wheels, Ui.matchWrap());
        box.addView(Ui.gap(c, 10));
        box.addView(Ui.text(c, LifeTimes.HOW_LONG, 13, Ui.TEXT_2, Ui.MEDIUM));
        if (lengths.length == 0) {
            box.addView(Ui.text(c, LifeTimes.TIME_ONLY, 13, Ui.TEXT_3, Ui.REGULAR));
        } else {
            final EqualGrid grid = new EqualGrid(c, 4, 6);
            final List<TextView> chips = new ArrayList<TextView>();
            for (int i = 0; i < lengths.length; i++) {
                final int len = lengths[i];
                TextView ch = chipView(c, LifeTimes.lengthLabel(len), picked[0] != null && picked[0] == len);
                ch.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // a second tap on the picked length leaves the factor without one
                        picked[0] = picked[0] != null && picked[0] == len ? null : (Integer) len;
                        for (int k = 0; k < chips.size(); k++) {
                            boolean sel = picked[0] != null && picked[0] == lengths[k];
                            chips.get(k).setTextColor(sel ? 0xFF0A0E17 : Ui.TEXT_2);
                            chips.get(k).setBackground(chipView(c, "", sel).getBackground());
                        }
                    }
                });
                chips.add(ch);
                grid.addView(ch);
            }
            LinearLayout.LayoutParams gl = Ui.matchWrap();
            gl.setMargins(0, Ui.dp(c, 6), 0, 0);
            box.addView(grid, gl);
        }
        new android.app.AlertDialog.Builder(c).setTitle(LifeTimes.TITLE).setView(box)
                .setPositiveButton(LifeTimes.SAVE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        String time = LifeTimes.clock(hour.getValue() * 60 + minute.getValue() * 5);
                        saveTime(c, nav, day, t, time, lengths.length == 0 ? null : picked[0]);
                    }
                })
                .setNeutralButton(LifeTimes.WITHOUT, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        saveTime(c, nav, day, t, null, null);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Stores the time and length of {@code t} on {@code day} (both null: without a time), the day logged too; with Undo. */
    static void saveTime(final Context c, final Pages.Nav nav, final LocalDate day, final LifeTags.Tag t, final String time, final Integer minutes) {
        run(c, nav, new Job() {
            @Override
            public String run(Db db, boolean demo) {
                String key = day.toString();
                List<LifeLog.Row> before = new ArrayList<LifeLog.Row>();
                before.add(LifeLog.row(db, key, t.key));
                LifeLog.setTime(db, key, t.key, time, minutes, System.currentTimeMillis() / 1000.0);
                List<String> days = new ArrayList<String>();
                days.add(key);
                undo = new Undo(db, demo, t.key, days, before, LifeTimes.savedLine(t.name, day, time, minutes, year(), Locale.getDefault()));
                return null;
            }
        });
    }

    /** The bands of the logged times that reach into {@code from} to {@code to} (issue #435), in the day's zone; none when unreadable. */
    static List<LifeTimes.Band> bands(DayData d, double from, double to) {
        return bands(d, from, to, d.zone);
    }

    /**
     * As {@link #bands(DayData, double, double)}, placed in {@code zone}: give the zone the chart reads its times in (a night's
     * zone on the night's charts), so the band and its readout tell the same clock time (code review of #444).
     */
    static List<LifeTimes.Band> bands(DayData d, double from, double to, java.time.ZoneId zone) {
        if (!d.lifeOk || d.life == null) return new ArrayList<LifeTimes.Band>();
        return LifeTimes.bands(d.lifeTimes, from, to, zone, d.life);
    }

    /**
     * Under the day's heart rate chart, one line per band with a length: what the line measured during it next to the day's
     * average ({@code dayAvg}, the chart's Average), or "Not worn during"; then what the numbers are. Null without such a band.
     */
    static View during(Context c, DayData d, app.openring.core.analysis.TS line, List<LifeTimes.Band> bands, double dayAvg) {
        LinearLayout box = null;
        for (LifeTimes.Band b : bands) {
            if (!b.hasLength) continue;
            if (box == null) {
                box = Ui.vbox(c);
                box.addView(Ui.gap(c, 6));
            }
            double v = LifeTimes.during(line, b, app.openring.core.analysis.DayHeart.STEP_S);
            box.addView(Ui.text(c, LifeTimes.duringLine(b, v, dayAvg, d.zone), 13, Ui.LIFE, Ui.REGULAR));
        }
        if (box != null) box.addView(Ui.text(c, Explain.LIFE_DURING, 12, Ui.TEXT_3, Ui.REGULAR));
        return box;
    }

    /** The bands of the shown day over its bars by the hour (Activity's intensity, issue #435). */
    static void onHours(Charts.BarChart chart, DayData d) {
        List<double[]> hours = new ArrayList<double[]>();
        List<String> names = new ArrayList<String>(), tips = new ArrayList<String>();
        double ds = d.date.atStartOfDay(d.zone).toEpochSecond(), de = d.date.plusDays(1).atStartOfDay(d.zone).toEpochSecond();
        for (LifeTimes.Band b : bands(d, ds, de)) {
            double[] h = LifeTimes.hours(b, d.date, d.zone);
            if (h == null) continue;
            hours.add(h);
            names.add(b.name);
            tips.add(LifeTimes.tip(b, d.zone));
        }
        if (!hours.isEmpty()) chart.life(hours, names, tips);
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
