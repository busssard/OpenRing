package app.openring.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import app.openring.core.Texts;
import app.openring.data.Json;

/** The Activity tab (issue #293, phase 0: moved out of {@link Pages} unchanged). */
final class ActivityPage {
    private ActivityPage() {}

    static View view(Context c, DayData d) {
        LinearLayout root = Ui.vbox(c);
        JSONObject day = d.day;
        double active = Json.d(day, "active_kcal"), target = Json.d(day, "target_kcal");
        // without a ring (issue #379): the score from the watch's or the phone's steps, the steps as evidence, then its parts
        boolean noRing = !d.has.ring;
        boolean scored = d.fromDevice("activity_score");
        app.openring.core.DeviceScores.Result dv = d.devices;
        String sub = !noRing ? (day == null ? "No activity data for this day" : Ui.fmt(active, 0) + " of " + Ui.fmt(target, 0) + " kcal goal")
                : scored ? app.openring.core.DeviceScores.goalLine(dv.steps, dv.goal, Locale.getDefault())
                : dv != null && dv.activityNone != null ? dv.activityNone : app.openring.core.DeviceScores.PHONE_NO_ACTIVITY;
        root.addView(PageParts.hero(c, d.score("activity_score"), "Activity", Ui.ACTIVE, sub,
                scored ? app.openring.core.DeviceScores.from(dv.device, "activity_score") : d.watchVersion("activity_score")));
        View left = PageParts.leftOutCard(c, d);
        if (left != null) root.addView(left);
        if (day == null || noRing) {
            // no ring summary: the watch's steps and workouts, when it has any (issue #251)
            app.openring.core.MainDevice.Day m = d.merged;
            if (m != null && !Double.isNaN(m.steps)) {
                LinearLayout sc = Ui.card(c);
                sc.addView(Ui.caption(c, "Steps"));
                sc.addView(Ui.statRow(c, PageParts.stepsStat(c, d, 0, "Estimated from how much you moved, so \"est.\""), distanceStat(c, d)));
                String fill = PageParts.stepsLine(d);
                if (fill != null) sc.addView(Ui.text(c, fill, 12, Ui.TEXT_3, Ui.REGULAR));
                if (phoneCounted(d)) sc.addView(Ui.text(c, app.openring.core.PhoneSteps.SPREAD, 12, Ui.TEXT_3, Ui.REGULAR));
                // only the phone: calories from its steps, an estimate (issue #329, phase 2)
                PhoneViews.calories(c, sc, d);
                Tiles.summary(sc, "Steps " + Ui.fmt(m.steps, 0));
                root.addView(sc);
            }
            // the watch's steps per hour, touch readable (owner's report of 11 November)
            View hours = PhoneViews.watchDayCard(c, d);
            if (hours != null) root.addView(hours);
            // steps from this phone (issue #329): the evidence, or the card that asks for Physical activity
            View phone = PhoneViews.activityCard(c, d);
            if (phone != null) root.addView(phone);
            View w = workoutsCard(c, d);
            if (w != null) root.addView(w);
            if (scored) {
                boolean watch = dv.device == app.openring.core.MainDevice.WATCH;
                if (dv.days7 < 7) {
                    LinearLayout note = Ui.card(c);
                    note.addView(Ui.body(c, Explain.activityDaysDevice(dv.days7, watch)));
                    // its text holds the count of days: a name of its own keeps its place and fold from day to day
                    root.addView(Tiles.name(note, ACTIVITY_DAYS_KEY, null, null));
                }
                DeviceViews.deviceParts(c, root, d, "activity_score", Ui.ACTIVE);
                root.addView(PageParts.infoCard(c, "About the activity score", Explain.ACTIVITY_DEVICE));
            }
            // only the phone: its day totals to hold next to the phone's own health app (issue #329, phase 2)
            View compare = PhoneViews.compareCard(c, d);
            if (compare != null) root.addView(compare);
            // steps, the activity score, calories and workouts over 7 and 30 days (owner's report of 13 November)
            HistoryViews.activity(c, root, d);
            return root;
        }

        LinearLayout goal = Ui.card(c);
        goal.addView(Ui.caption(c, "Goal progress"));
        goal.addView(Ui.gap(c, 8));
        goal.addView(new Ui.Bar(c, target > 0 ? active / target : 0, Ui.ACTIVE), Ui.sized(c, -1, 8));
        goal.addView(Ui.statRow(c, Ui.stat(c, "Active burn", Ui.fmt(active, 0), "kcal", 0, "Calories burned by moving"),
                Ui.stat(c, "Total burn", Ui.fmt(Json.d(day, "total_kcal"), 0), "kcal", 0, Explain.totalBurn(Json.d(day, "total_until"), d.zone))));
        goal.addView(Ui.statRow(c, PageParts.stepsStat(c, d, 0, "Estimated from how much you moved, so \"est.\""), distanceStat(c, d)));
        String fill = PageParts.stepsLine(d);
        if (fill != null) {
            goal.addView(Ui.text(c, fill, 12, Ui.TEXT_3, Ui.REGULAR));
            goal.addView(Ui.text(c, Explain.MAIN_FILLED, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        // phone steps in the total are spread evenly over each interval between two readings (issue #329)
        if (phoneCounted(d)) goal.addView(Ui.text(c, app.openring.core.PhoneSteps.SPREAD, 12, Ui.TEXT_3, Ui.REGULAR));
        // folded (owner's wish of 18 November): the day's steps and active burn, as the tiles above show them
        double steps = d.merged == null ? Json.d(day, "steps") : d.merged.steps;
        // a missing value is left out, none at all gives no line (review of #415: never "Steps --")
        Tiles.summary(goal, app.openring.core.Layout.parts(Double.isNaN(steps) ? null : "Steps " + Ui.fmt(steps, 0),
                Double.isNaN(active) ? null : "active burn " + Ui.fmt(active, 0) + " kcal"));
        root.addView(goal);
        // the watch's steps per hour when its steps are the day's (owner's report of 11 November)
        View hours = PhoneViews.watchDayCard(c, d);
        if (hours != null) root.addView(hours);
        // steps from this phone (issue #329): the evidence under the main device's steps, or the card that asks
        View phone = PhoneViews.activityCard(c, d);
        if (phone != null) root.addView(phone);
        // the watch's workouts of the day: only the watch records them (issue #251)
        View workouts = workoutsCard(c, d);
        if (workouts != null) root.addView(workouts);

        LinearLayout intensity = Ui.card(c);
        intensity.addView(Ui.caption(c, "Daily movement"));
        intensity.addView(Ui.gap(c, 6));
        double high = Json.d(day, "high_min"), med = Json.d(day, "medium_min"), low = Json.d(day, "low_min"), inact = Json.d(day, "inactive_min"), rest = Json.d(day, "rest_min");
        double tot = Math.max(1, high + med + low + inact + rest);
        intensity.addView(PageParts.stageRow(c, "High", high, tot, 0xFFFF8A5B));
        intensity.addView(PageParts.stageRow(c, "Medium", med, tot, Ui.ACTIVE));
        intensity.addView(PageParts.stageRow(c, "Low", low, tot, 0xFFFFDDA8));
        intensity.addView(PageParts.stageRow(c, "Inactive", inact, tot, Ui.TEXT_3));
        intensity.addView(PageParts.stageRow(c, "Rest", rest, tot, Ui.STAGE_LIGHT));
        double leftS = d.leftOutSeconds();
        // what the percentages are shares of, and the time without the ring (issue #212), from the stored minutes
        double until = Json.d(day, "total_until");
        double notWorn = Texts.notWornSoFar(Json.d(day, "non_wear_min"), until, d.dayStart(), d.dayEnd());
        double onMin = high + med + low + inact + rest;
        if (!Double.isNaN(onMin)) {
            intensity.addView(Ui.gap(c, 4));
            intensity.addView(Ui.text(c, Texts.movementShares(onMin, notWorn,
                    Double.isNaN(until) ? null : app.openring.core.analysis.WakeUps.clock(until, d.zone), leftS >= 60), 12, Ui.TEXT_3, Ui.REGULAR));
            if (!Double.isNaN(notWorn) && Math.round(notWorn) >= 1) intensity.addView(Ui.text(c, Explain.NOT_WORN, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        if (leftS >= 60) {
            intensity.addView(Ui.gap(c, 4));
            intensity.addView(Ui.text(c, app.openring.core.analysis.LeftOut.activityLine(leftS), 12, Ui.TEXT_3, Ui.REGULAR));
        }
        root.addView(intensity);

        JSONArray prof = day.optJSONArray("met_profile");
        if (prof != null) {
            double[] hourly = new double[24];
            String[] labels = new String[24];
            for (int h = 0; h < 24; h++) {
                double s = 0;
                int n = 0;
                for (int q = 0; q < 4; q++) {
                    double v = prof.isNull(h * 4 + q) ? Double.NaN : prof.optDouble(h * 4 + q);
                    if (!Double.isNaN(v)) {
                        s += v;
                        n++;
                    }
                }
                hourly[h] = n == 0 ? Double.NaN : s / n;
                labels[h] = h % 6 == 0 ? String.valueOf(h) : "";
            }
            LinearLayout pc = Ui.card(c);
            pc.addView(Ui.caption(c, "Intensity through the day (MET)"));
            pc.addView(Ui.gap(c, 8));
            int peak = 0;
            for (int h = 0; h < 24; h++) if (!Double.isNaN(hourly[h]) && (Double.isNaN(hourly[peak]) || hourly[h] > hourly[peak])) peak = h;
            // touch reads each hour, and the logged times of life factors show as bands over their hours (issue #435)
            String[] hourTips = new String[24];
            for (int h = 0; h < 24; h++) {
                hourTips[h] = String.format(Locale.getDefault(), "%02d:00 to ", h) + (h == 23 ? "midnight  " : String.format(Locale.getDefault(), "%02d:00  ", h + 1))
                        + (Double.isNaN(hourly[h]) ? "no reading" : String.format(Locale.getDefault(), "%.1f MET", hourly[h]));
            }
            Charts.BarChart met = new Charts.BarChart(c, hourly, labels, Ui.ACTIVE).valueFormat("%.1f").highlight(peak).tips(hourTips);
            LifeCard.onHours(met, d);
            pc.addView(met, Ui.sized(c, -1, 120));
            pc.addView(Ui.gap(c, 6));
            // the bands of Wellness.day (issue #47)
            pc.addView(Ui.text(c, "MET is how hard your body works, 1 is resting. While awake, under 1.5 counts as inactive, 1.5 to 3 low, "
                    + "3 to 6 medium, 6 or more high (the bands of Daily movement); time asleep counts as rest, also when the ring recorded no movement then.", 12, Ui.TEXT_3, Ui.REGULAR));
            // a clock change day: the bars go by clock time, the numbers by real minutes (issue #114), only on a day
            // analysed that way (it has day_min; older days keep 1440 minute numbers until analysed again, review of #202)
            String change = app.openring.core.analysis.ClockChange.activityText(app.openring.core.analysis.ClockChange.onDay(d.zone,
                    d.date.atStartOfDay(d.zone).toEpochSecond(), d.date.plusDays(1).atStartOfDay(d.zone).toEpochSecond()),
                    day == null ? 0 : day.optInt("day_min", 0));
            if (change != null) pc.addView(Ui.text(c, change, 12, Ui.TEXT_3, Ui.REGULAR));
            root.addView(pc);
        }

        JSONObject as = day.optJSONObject("activity_score");
        if (as != null) {
            int worn = day.optInt("activity_days", 7);
            if (worn < 7) {
                LinearLayout note = Ui.card(c);
                note.addView(Ui.body(c, Explain.activityDays(worn)));
                // its text holds the count of days: a name of its own keeps its place and fold from day to day
                root.addView(Tiles.name(note, ACTIVITY_DAYS_KEY, null, null));
            }
            root.addView(PageParts.contributors(c, as, Ui.ACTIVE, "Contributors"));
            root.addView(PageParts.infoCard(c, "About the activity score", Explain.ACTIVITY));
        }
        else {
            LinearLayout n = Ui.card(c);
            n.addView(Ui.body(c, "The activity score needs at least an hour of wear time today."));
            root.addView(n);
        }

        LinearLayout week = Ui.card(c);
        week.addView(Ui.caption(c, "Active burn, last 7 days"));
        week.addView(Ui.gap(c, 8));
        double[] burn = d.chartSeries("active_kcal", 7);
        week.addView(new Charts.BarChart(c, burn, d.weekdayLabels(7), Ui.ACTIVE).target(target).highlight(d.pickedIndex(7))
                .tips(HistoryViews.tips(d, burn, new HistoryViews.Fmt() {
                    @Override
                    public String of(double v) {
                        return HistoryViews.whole(v) + " kcal active";
                    }
                })), Ui.sized(c, -1, 130));
        root.addView(week);
        return root;
    }

    /** The Distance tile: the main device's with the other one's filled time (issue #251); the ring's own as before. */
    static View distanceStat(Context c, DayData d) {
        app.openring.core.MainDevice.Day m = d.merged;
        String note = m == null ? null : app.openring.core.MainDevice.stepsNote(m);
        double metres = m == null ? Json.d(d.day, "distance_m") : m.metres;
        // the phone counts no distance: it is worked out from its steps (issue #329)
        boolean phone = m != null && (m.main == app.openring.core.MainDevice.PHONE || m.whole && m.wholeFrom == app.openring.core.MainDevice.PHONE);
        return Ui.stat(c, "Distance", Ui.fmt(metres / 1000.0, 1), "km", 0, note == null || phone ? "Estimated from the steps"
                : note.startsWith("Counted") && !note.contains("phone") ? "The watch's own estimate from your steps" : "Estimated from the steps of both");
    }

    /** Phone steps are part of the day's total: the phone is the main device with steps, or it filled a gap (issue #329). */
    static boolean phoneCounted(DayData d) {
        app.openring.core.MainDevice.Day m = d.merged;
        if (m == null || Double.isNaN(m.steps)) return false;
        return m.phoneSteps() > 0 || (m.base == app.openring.core.MainDevice.PHONE && m.steps > 0);
    }

    /** The watch's workouts of the day as one card, null without any (issue #251). */
    static View workoutsCard(Context c, DayData d) {
        if (d.merged == null || d.merged.workouts.isEmpty()) return null;
        final java.time.ZoneId z = d.zone;
        app.openring.core.withings.WatchDays.Zones zones = new app.openring.core.withings.WatchDays.Zones() {
            @Override
            public java.time.ZoneId at(double unix) {
                return z;
            }
        };
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Workouts from your watch"));
        card.addView(Ui.gap(c, 4));
        for (app.openring.core.withings.Records.Workout w : d.merged.workouts) {
            card.addView(Ui.text(c, app.openring.core.withings.WatchDays.workoutLine(w, zones), 14, Ui.TEXT, Ui.REGULAR));
        }
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, Explain.WATCH_WORKOUTS, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    /** The key of Activity's note on how many days count (its text changes with the count, so it needs a name). */
    static final String ACTIVITY_DAYS_KEY = "activity days";
}
