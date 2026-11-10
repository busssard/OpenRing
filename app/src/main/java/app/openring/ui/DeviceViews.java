package app.openring.ui;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.openring.core.Start;

/**
 * The tabs without a ring (issue #373): what a person with only the watch or only the phone sees in place of the ring's
 * cards, each with a short line that says what needs a ring and a way to add one. Which card shows is decided in
 * {@code core/Start.cards}.
 */
final class DeviceViews {
    private DeviceViews() {}

    /** Opens the watch setup (Back returns here). */
    static void addWatch(Context c) {
        c.startActivity(new Intent(c, WatchSetupActivity.class));
    }

    /** "Add a ring" and, when {@code watchToo}, "Add a watch" under it, full width. */
    static void addButtons(final Context c, LinearLayout card, boolean watchToo) {
        card.addView(Ui.gap(c, 12));
        card.addView(PhoneViews.addRingButton(c, false), Ui.matchWrap());
        if (!watchToo) return;
        TextView w = Ui.button(c, Start.ADD_WATCH, Ui.OXY, false);
        w.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addWatch(c);
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(w, lp);
    }

    /** A card with a title, a text and the ways to add a device. */
    static View needCard(Context c, String title, String text, boolean watchToo) {
        LinearLayout card = Ui.card(c);
        if (title != null) {
            card.addView(Ui.h2(c, title));
            card.addView(Ui.gap(c, 6));
        }
        card.addView(Ui.body(c, text));
        addButtons(c, card, watchToo);
        return card;
    }

    /**
     * Today's top without a ring: the Screen ring next to the line that the scores need a ring, and the button(s) to add
     * one (a watch too when there is none).
     */
    static View scoresWithout(Context c, DayData d, Pages.Nav nav) {
        LinearLayout card = Ui.card(c);
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        double sc = d.score("screen");
        row.addView(Pages.ringButton(c, sc, "Screen", Ui.OXY, nav, Pages.SCREEN, Double.isNaN(sc) && !Pages.screenReady(c) ? "Tap to set up" : null), Ui.weight(1));
        TextView t = Ui.body(c, d.has.watch ? Start.SCORES_NEED_RING : Start.SCORES_NEED_DEVICE);
        LinearLayout.LayoutParams tl = Ui.weight(2);
        tl.setMargins(Ui.dp(c, 12), 0, 0, 0);
        row.addView(t, tl);
        card.addView(row);
        addButtons(c, card, !d.has.watch);
        return card;
    }

    /** Today: the watch was chosen at the start but is not added yet. */
    static View finishWatch(final Context c) {
        LinearLayout card = Ui.card(c);
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48));
        ip.setMargins(0, 0, Ui.dp(c, 12), 0);
        row.addView(new DeviceIcon(c, DeviceIcon.WATCH), ip);
        row.addView(Ui.h2(c, Start.FINISH_TITLE), Ui.weight(1));
        card.addView(row);
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.body(c, Start.FINISH_TEXT));
        card.addView(Ui.gap(c, 12));
        TextView b = Ui.button(c, Start.FINISH_BUTTON, Ui.OXY, true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addWatch(c);
            }
        });
        card.addView(b, Ui.matchWrap());
        return card;
    }

    /** Today with only the watch: its steps, last night, the day's heart rate and its workouts, each with its line. */
    static View watchDay(final Context c, DayData d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, Start.WATCH_DAY_TITLE));
        app.openring.core.MainDevice.Day m = d.merged;
        app.openring.core.withings.WatchDays.Night wn = m == null ? null : m.watchNight;
        card.addView(Ui.statRow(c,
                steps(c, d, Start.STEPS_WATCH),
                Ui.stat(c, "Total sleep", wn == null ? "--" : Ui.hm(wn.asleepMin()), "", Ui.SLEEP, Start.SLEEP_WATCH)));
        double ds = d.dayStart(), de = d.dayEnd();
        app.openring.core.analysis.DayHeart dh = app.openring.core.analysis.DayHeart.of(m == null ? new app.openring.core.analysis.TS() : m.hr, ds, de);
        int workouts = m == null ? 0 : m.workouts.size();
        card.addView(Ui.statRow(c,
                Ui.stat(c, "Lowest heart rate", Ui.fmt(dh.low, 0), "bpm", Ui.HEART, Start.HR_LOW),
                Ui.stat(c, "Average heart rate", Ui.fmt(dh.avg, 0), "bpm", Ui.HEART, Start.HR_AVG)));
        card.addView(Ui.statRow(c, Ui.stat(c, "Workouts", String.valueOf(workouts), "", Ui.ACTIVE, Start.WORKOUTS)));
        String fill = Pages.stepsLine(d);
        if (fill != null) card.addView(Ui.text(c, fill, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(c, 10));
        TextView b = Ui.button(c, Start.WATCH_PAGE, Ui.OXY, false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new Intent(c, WatchPageActivity.class));
            }
        });
        card.addView(b, Ui.matchWrap());
        return card;
    }

    /** Today with only the phone: its steps and today's screen time, each with its line. */
    static View phoneDay(Context c, DayData d) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, Start.PHONE_DAY_TITLE));
        app.openring.data.ScreenDays.Info in = d.screen;
        double screen = in == null || in.day == null ? Double.NaN : in.day.totalMin;
        card.addView(Ui.statRow(c,
                steps(c, d, Start.STEPS_PHONE),
                Ui.stat(c, "Screen time", Double.isNaN(screen) ? "--" : Ui.hm(screen), "", Ui.OXY, Start.SCREEN_TODAY)));
        if (Pages.phoneCounted(d)) card.addView(Ui.text(c, app.openring.core.PhoneSteps.SPREAD, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    /** The day's steps as counted (no "est.": the watch and the phone count steps), with the note of a filled time. */
    static View steps(Context c, DayData d, String note) {
        app.openring.core.MainDevice.Day m = d.merged;
        String filled = m == null ? null : app.openring.core.MainDevice.stepsNote(m);
        return Ui.stat(c, "Steps", Ui.fmt(m == null ? Double.NaN : m.steps, 0), "", Ui.ACTIVE, filled != null ? filled : note);
    }

    /** Sleep and Ready with neither a ring nor a watch. */
    static View needDevice(Context c) {
        LinearLayout root = Ui.vbox(c);
        root.addView(needCard(c, Start.ADD_DEVICE_TITLE, Start.NEED_DEVICE_TEXT + " " + Start.ADD_DEVICE_TEXT, true));
        return root;
    }
}
