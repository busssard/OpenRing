package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.openring.core.Texts;
import app.openring.data.Prefs;
import app.openring.ui.Pages.Nav;

/** The Screen tab (issue #293, phase 0: moved out of {@link Pages} unchanged). */
final class ScreenPage {
    private ScreenPage() {}

    /** True when screen time can be read: demo mode, or Android 9 or newer with usage access. */
    static boolean screenReady(Context c) {
        return Prefs.of(c).demo() || app.openring.data.ScreenUse.supported() && app.openring.data.ScreenUse.allowed(c);
    }

    /**
     * The Screen tab: a score, the day as a clock dial, the numbers, the last 14 days as rows and the
     * apps, with a list of apps not to count (for example maps while navigating).
     */
    static View view(final Context c, DayData d, final Nav nav) {
        LinearLayout root = Ui.vbox(c);
        boolean demo = Prefs.of(c).demo();
        if (!demo && !app.openring.data.ScreenUse.supported()) {
            root.addView(PageParts.infoCard(c, "Screen time", "Screen time needs Android 9 or newer."));
            return root;
        }
        if (!demo && !app.openring.data.ScreenUse.allowed(c)) {
            LinearLayout card = Ui.card(c);
            card.addView(Ui.h2(c, "See when you use your phone"));
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.body(c, "Open Ring can show how long and when this phone's screen was on, next to your sleep, "
                    + "and give it a score. For this, switch on Open Ring in Android's usage access list (the button takes you there). "
                    + "Like everything else it stays on this phone."));
            card.addView(Ui.gap(c, 12));
            TextView allow = Ui.button(c, "Allow usage access", Ui.OXY, true);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    app.openring.data.ScreenUse.openAccessSettings(c);
                }
            });
            card.addView(allow, Ui.matchWrap());
            card.addView(Ui.gap(c, 8));
            card.addView(Ui.text(c, "Android asks for this itself, apps cannot switch it on. You can switch it off there at any time.",
                    12, Ui.TEXT_3, Ui.REGULAR));
            root.addView(card);
            return root;
        }
        final app.openring.data.ScreenDays.Info in = d.screen;
        boolean today = d.date.equals(java.time.LocalDate.now());
        if (in == null || in.day == null) {
            root.addView(PageParts.infoCard(c, "Screen time", app.openring.core.analysis.ScreenTime.unknownText(in == null ? app.openring.core.analysis.ScreenTime.UNREADABLE : in.coverage, demo)));
            if (in != null) root.addView(screenRows(c, in, nav));
            return root;
        }
        int goal = in.goalMin;
        String sub = span(in.day.totalMin) + (goal > 0 ? " of " + hours(goal) + " goal" : "") + (today ? ", so far today" : "");
        root.addView(PageParts.hero(c, in.score == null ? Double.NaN : in.score.value, "Screen", Ui.OXY, sub));

        // the day as a clock
        LinearLayout dial = Ui.card(c);
        dial.addView(Ui.caption(c, "When you used the phone"));
        dial.addView(Ui.gap(c, 8));
        final java.util.Map<String, String> names = new java.util.HashMap<String, String>();
        dial.addView(new Charts.ScreenDial(c, in.dial, in.nights, in.sessions, in.dayApps, new Charts.ScreenDial.Names() {
            @Override
            public String of(String pkg) {
                String n = names.get(pkg);
                if (n == null) names.put(pkg, n = app.openring.data.ScreenUse.label(c, pkg));
                return n;
            }
        }, in.from, in.to, System.currentTimeMillis() / 1000.0, span(in.day.totalMin), today ? "today" : "on this day")
                .shownAs(in.shown).zone(in.zone), Ui.sized(c, -1, 300));
        dial.addView(Ui.gap(c, 8));
        int minShow = Prefs.of(c).screenMinShowMin();
        dial.addView(Ui.text(c, "The day as a clock, midnight at the top. Each blue line is 5 minutes of the day with phone use, "
                + "brighter the more of them was used. Purple: your sleep. Touch the clock and slide around it to see the times; "
                + "tap the middle for the total again.", 12, Ui.TEXT_2, Ui.REGULAR));
        String change = app.openring.core.analysis.ClockChange.dialText(app.openring.core.analysis.ClockChange.onDay(in.zone, in.from, in.to));
        if (change != null) {
            // a day the clocks changed (issue #46): the orange arc on the clock is explained here
            dial.addView(Ui.gap(c, 4));
            dial.addView(Ui.text(c, change, 12, Ui.ACTIVE, Ui.REGULAR));
        }
        dial.addView(Ui.gap(c, 10));
        dial.addView(Ui.text(c, "Each use shows as at least", 12, Ui.TEXT_3, Ui.MEDIUM));
        dial.addView(minShowChips(c, minShow, nav));
        dial.addView(Ui.text(c, "Only for the clock and the days below, so short looks do not flicker. The times and the score always count the real minutes.",
                12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(dial);

        // the numbers
        LinearLayout num = Ui.card(c);
        num.addView(Ui.caption(c, "This day"));
        num.addView(Ui.statRow(c, Ui.stat(c, "Uses", String.valueOf(in.day.turnedOn), Texts.word(in.day.turnedOn, "time", "times"), 0),
                Ui.stat(c, "Longest", span(in.day.longestMin), "", 0)));
        num.addView(Ui.statRow(c, Ui.stat(c, "First look", PageParts.clock(in.firstLook, in.zone), "", 0),
                Ui.stat(c, "Not counted", span(in.excludedMin), "", 0)));
        num.addView(Ui.statRow(c, Ui.stat(c, "Before bed", span(in.bedMin), "", 0),
                Ui.stat(c, "In the night", span(in.nightMin), "", 0)));
        num.addView(Ui.text(c, "Uses: how often you started using the phone (unlocked it, or turned the screen on if it has no lock screen). Longest: the longest use in one go. "
                + "First look: the first time after waking up. Not counted: time in apps you left out (below). "
                + "Before bed: the hour before you fell asleep last night. In the night: between falling asleep and waking up. "
                + "Phone use counts while the phone is unlocked and the screen is on; the lock screen lighting up for a "
                + "message does not count.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(num);

        // what makes the score
        if (in.score != null) {
            LinearLayout sc = Ui.card(c);
            sc.addView(Ui.caption(c, "Contributors"));
            int g = goal > 0 ? goal : app.openring.core.analysis.ScreenScore.DEFAULT_GOAL_MIN;
            sc.addView(Ui.contributor(c, "Screen time", span(in.day.totalMin) + " of " + hours(g), in.score.amount, Ui.OXY,
                    "Half your goal or less is best, the goal itself still good, twice the goal or more lowers this to zero."));
            if (!Double.isNaN(in.score.bedtime)) {
                sc.addView(Ui.contributor(c, "Before bed", span(in.bedMin), in.score.bedtime, Ui.OXY,
                        "Screen light and content in the last hour keep the brain awake. 5 minutes or less is best, 45 or more lowers this to zero."));
                sc.addView(Ui.contributor(c, "In the night", span(in.nightMin), in.score.night, Ui.OXY,
                        "Checking the phone after waking up at night makes it harder to fall asleep again. 1 minute or less is best, 20 or more lowers this to zero."));
            } else {
                // a night is known, but the phone's log was not read for the hour before it or the night itself (issue #131)
                sc.addView(Ui.text(c, Double.isNaN(in.sleepStart) ? "No night known for this day, so only the screen time counts."
                        : "Screen time before bed or in the night was not read, so only the screen time counts.", 12, Ui.TEXT_3, Ui.REGULAR));
            }
            root.addView(sc);
        }
        root.addView(PageParts.infoCard(c, "About the screen score", Explain.SCREEN));

        root.addView(screenRows(c, in, nav));
        root.addView(screenApps(c, in, nav));
        TextView more = Ui.text(c, "Daily goal and whether phone use at night counts as awake: Settings, Screen time.", 12, Ui.TEXT_3, Ui.REGULAR);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 16));
        root.addView(more);
        return root;
    }

    /** Choice of how long each use shows at least on the clock and the day rows (display only). */
    static View minShowChips(final Context c, int current, final Nav nav) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        int[] opts = {0, 5, 10, 15};
        for (final int m : opts) {
            boolean on = m == current;
            TextView chip = Ui.button(c, m == 0 ? "Exact" : m + " min", on ? Ui.OXY : Ui.TEXT_2, on);
            chip.setTextSize(13);
            int ph = Ui.dp(c, 12), pv = Ui.dp(c, 6);
            chip.setPadding(ph, pv, ph, pv);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.of(c).setScreenMinShowMin(m);
                    nav.reload();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(0, 0, Ui.dp(c, 6), 0);
            row.addView(chip, lp);
        }
        return row;
    }

    /** "37 min" under an hour, "1h 5m" above: one way on the whole Screen tab (issue #99). */
    static String span(double min) {
        return Texts.minutes(min);
    }

    static View screenRows(Context c, app.openring.data.ScreenDays.Info in, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Last " + in.rows.length + " days"));
        card.addView(Ui.gap(c, 8));
        card.addView(new Charts.ScreenRows(c, in.rows, in.date, new Charts.ScreenRows.Pick() {
            @Override
            public void day(java.time.LocalDate day) {
                nav.showDay(day);
            }
        }), Ui.sized(c, -1, Charts.ScreenRows.heightDp(in.rows.length)));
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, "One row per day, midnight to midnight, newest on top. Blue: phone in use. Purple: sleep. "
                + "Green dot: first look after waking up. Right: the day's screen time, or -- when it is not known "
                + "(the day itself says why). Tap a day to open it.", 12, Ui.TEXT_2, Ui.REGULAR));
        // where the sleep came from when the watch gave a night (owner's report of 14 November); nothing new with only the ring
        if (in.watchNights) card.addView(Ui.text(c, in.ringNights ? Explain.SCREEN_WATCH_SOME : Explain.SCREEN_WATCH_NIGHTS, 12, Ui.TEXT_3, Ui.REGULAR));
        // one key whatever the number of days in the caption, so the tile keeps its place (owner's wish of 18 November)
        Tiles.spec(card).key = "last days";
        return card;
    }

    static View screenApps(final Context c, app.openring.data.ScreenDays.Info in, final Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Apps on this day"));
        card.addView(Ui.gap(c, 4));
        java.util.Set<String> ex = Prefs.of(c).screenExcluded();
        if (!in.appsKnown) {
            card.addView(Ui.body(c, "Times per app start with version 1.15, from about a week before it was installed."));
        } else if (in.appRows.isEmpty()) {
            card.addView(Ui.body(c, app.openring.core.analysis.ScreenTime.emptyListText(in.apps)));
        } else {
            // whole minutes rounded together, so the rows add up to the total and to Not counted (issue #71)
            for (app.openring.core.analysis.ScreenTime.AppRow r : in.appRows) {
                boolean out = r.excluded;
                String name = r.kind == app.openring.core.analysis.ScreenTime.AppRow.APP ? app.openring.data.ScreenUse.label(c, r.pkg)
                        : r.kind == app.openring.core.analysis.ScreenTime.AppRow.OTHER ? "Other apps (" + r.apps + ")" : "No app in front";
                boolean plain = r.kind == app.openring.core.analysis.ScreenTime.AppRow.APP && !out;
                LinearLayout row = Ui.hbox(c);
                row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
                row.addView(Ui.text(c, name, 14, out ? Ui.TEXT_3 : plain ? Ui.TEXT : Ui.TEXT_2, Ui.REGULAR), Ui.weight(1));
                row.addView(Ui.text(c, (out ? "not counted, " : "") + (r.shownMin == 0 ? "less than 1 min" : span(r.shownMin)), 14, out ? Ui.TEXT_3 : Ui.TEXT_2, Ui.REGULAR));
                card.addView(row);
            }
        }
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, "Time each app was in front while you used the phone, rounded so the rows add up to the day's total; "
                + "apps not counted add up to Not counted. Other apps: apps used less than half a minute that day and those "
                + "beyond the eight most used. No app in front: time when Android does not say which app was in front, "
                + "for example some system screens. "
                + "Apps you leave out (for example maps while you navigate) do not count for the total, the clock, the days or the "
                + "score. Phone use at night still counts as awake for your sleep.", 12, Ui.TEXT_2, Ui.REGULAR));
        TextView pick = Ui.smallButton(c, ex.isEmpty() ? "Choose apps not to count" : "Apps not counted: " + ex.size(), Ui.OXY);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickExcluded(c, nav);
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(pick, lp);
        return card;
    }

    /** A list of the apps seen in the last 10 days (and those already left out) with tick boxes. */
    static void pickExcluded(final Context c, final Nav nav) {
        // one list at a time: a second tap while the first is read opens nothing more
        if (picking) return;
        picking = true;
        final Prefs p = Prefs.of(c);
        final java.util.Set<String> ex = p.screenExcluded();
        // the stored app times and the app names are read in the background, so the tap never holds the screen (issue #223)
        new android.os.AsyncTask<Void, Void, List<List<String>>>() {
            @Override
            protected List<List<String>> doInBackground(Void... v) {
                List<String> pkgs = new ArrayList<String>(), labels = new ArrayList<String>();
                app.openring.data.Db db = app.openring.data.Db.get(c);
                // per day over the days app times are known (fewer than 10 right after installing 1.15)
                double since = db.appsStart();
                double days = since < 0 ? 10 : Math.max(1, Math.min(10, (System.currentTimeMillis() / 1000.0 - since) / 86400));
                for (java.util.Map.Entry<String, Double> e : app.openring.data.ScreenDays.recentApps(db, 10)) {
                    pkgs.add(e.getKey());
                    labels.add(app.openring.data.ScreenUse.label(c, e.getKey()) + "  (" + span(e.getValue() / days) + " a day)");
                }
                for (String x : ex) {
                    if (pkgs.contains(x)) continue;
                    pkgs.add(x);
                    labels.add(app.openring.data.ScreenUse.label(c, x) + "  (not used lately)");
                }
                List<List<String>> r = new ArrayList<List<String>>();
                r.add(pkgs);
                r.add(labels);
                return r;
            }

            @Override
            protected void onPostExecute(List<List<String>> r) {
                picking = false;
                // the screen went away meanwhile (turned, closed): no dialog on a screen that is gone
                if (c instanceof android.app.Activity && (((android.app.Activity) c).isFinishing() || ((android.app.Activity) c).isDestroyed())) return;
                showExcluded(c, nav, p, ex, r.get(0), r.get(1));
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** True while {@link #pickExcluded} reads the apps (main thread only). */
    private static boolean picking;

    /** The tick box list of {@link #pickExcluded}, on the main thread. */
    static void showExcluded(final Context c, final Nav nav, final Prefs p, java.util.Set<String> ex, final List<String> pkgs, List<String> labels) {
        if (pkgs.isEmpty()) {
            android.widget.Toast.makeText(c, "No apps known yet. Use the phone a little and try again.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = ex.contains(pkgs.get(i));
        new android.app.AlertDialog.Builder(c)
                .setTitle("Apps not to count")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int which, boolean on) {
                        checked[which] = on;
                    }
                })
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        List<String> out = new ArrayList<String>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) out.add(pkgs.get(i));
                        p.setScreenExcluded(out);
                        nav.reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "3h", "2h 30m" or "45 min" for a goal in minutes. */
    static String hours(int min) {
        return min >= 60 && min % 60 == 0 ? (min / 60) + "h" : span(min);
    }
}
