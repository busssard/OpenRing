package app.openring.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.time.LocalDate;
import java.util.Locale;

import app.openring.core.DeviceHistory;
import app.openring.core.MainDevice;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.TS;

/**
 * The statistics at the bottom of Sleep, Ready, Activity and Body without a ring (owner's report of 13 November): the 7 and
 * 30 day charts, sleep debt, the night's details and sleep times from the watch, or the steps from this phone, worked out by
 * {@code core/DeviceHistory} on each load. Every chart reads by touch. With a ring none of this shows: the ring's own charts
 * stay as they are (main device rule, #319).
 */
final class HistoryViews {
    private HistoryViews() {}

    /** The tab shows the history from the watch or the phone: no ring, and the history could be read. */
    static boolean on(DayData d) {
        return !d.has.ring && d.deviceHistory != null;
    }

    /** As {@link #on}, from the watch. */
    static boolean watch(DayData d) {
        return on(d) && d.deviceHistory.device == MainDevice.WATCH;
    }

    /** "From your watch" or "From this phone's steps", the line under each chart. */
    static String from(DayData d) {
        return d.deviceHistory.device == MainDevice.WATCH ? DeviceHistory.FROM_WATCH : DeviceHistory.FROM_PHONE;
    }

    interface Fmt {
        String of(double v);
    }

    /** The readout of each of the {@code k} days ending at the shown day. */
    static String[] tips(DayData d, double[] v, Fmt f) {
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) {
            LocalDate day = d.date.minusDays(v.length - 1 - i);
            out[i] = DeviceHistory.tip(day, Double.isNaN(v[i]) ? Explain.NO_VALUE : f.of(v[i]), Locale.getDefault());
        }
        return out;
    }

    /** One value per day at noon, for the 30 day line charts ({@code LineChart.daily}), as the ring's {@code Pages.daysSeries}. */
    static TS series(DayData d, double[] v) {
        TS ts = new TS();
        for (int i = 0; i < v.length; i++) {
            if (Double.isNaN(v[i])) continue;
            ts.add(d.date.minusDays(v.length - 1 - i).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() + 43200, v[i]);
        }
        return ts;
    }

    /** A 7 day bar chart card as the ring's ("Total sleep, last 7 days"), readable by touch, with a line under it. */
    static View week(Context c, DayData d, String caption, double[] v, int color, String format, double target, Fmt tip, String foot) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, caption));
        card.addView(Ui.gap(c, 8));
        Charts.BarChart chart = new Charts.BarChart(c, v, d.weekdayLabels(v.length), color).valueFormat(format).tips(tips(d, v, tip));
        if (!Double.isNaN(target)) chart.target(target);
        chart.setContentDescription(caption);
        card.addView(chart, Ui.sized(c, -1, 130));
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.text(c, foot, 12, Ui.TEXT_2, Ui.REGULAR));
        return card;
    }

    /** A 30 day line chart card with dots (one value per day), readable by touch, with a line under it. */
    static View month(Context c, DayData d, String caption, double[] v, int color, String unit, String empty, String foot) {
        Charts.LineChart chart = new Charts.LineChart(c, series(d, v), Double.NaN, Double.NaN, color).daily(d.date, DeviceHistory.MONTH).unit(unit).empty(empty);
        chart.setContentDescription(caption);
        return Pages.chartCard(c, caption, chart, foot);
    }

    /** "Average 8,412 steps a day." style first part of a foot line, empty without values. */
    static String average(double[] v, String unit, int dp) {
        if (Stats.countFinite(v) == 0) return "";
        double m = Stats.mean(v);
        // whole numbers with the phone's grouping ("10,702 steps"), as the readouts write them
        return "Average " + (dp == 0 ? whole(m) : Ui.fmt(m, dp)) + unit + ". ";
    }

    /** "1,234" with the phone's grouping. */
    static String whole(double v) {
        return String.format(Locale.getDefault(), "%,d", Math.round(v));
    }

    // ======================================================================== SLEEP

    /**
     * Sleep without a ring, after the night and its score: the night's details, sleep debt, total sleep of the last 7 days, sleep
     * times and the sleep score of the last 30 days, and one card for what needs a ring. The night timeline and the recent
     * nights strip are added by {@code Pages} where the ring's are.
     */
    static void sleep(Context c, LinearLayout root, final DayData d) {
        if (!watch(d)) return;
        final DeviceHistory h = d.deviceHistory;
        DeviceHistory.NightRow r = h.night(d.date);
        if (r != null) {
            LinearLayout stats = Ui.card(c);
            stats.addView(Ui.caption(c, "Details"));
            stats.addView(Ui.statRow(c, Ui.stat(c, "Time in bed", Ui.hm(r.night.inBedMin()), "", 0, Explain.WATCH_IN_BED),
                    Ui.stat(c, "Efficiency", Ui.fmt(r.fields.efficiency, 0), "%", 0, "Share of the night asleep, 85% or more is good")));
            stats.addView(Ui.statRow(c, Ui.stat(c, "Latency", Ui.fmt(r.fields.latencyMin, 0), "min", 0, Explain.WATCH_LATENCY),
                    Ui.stat(c, "Wake ups", Double.isNaN(r.fields.sleepStart) ? Ui.fmt(Double.NaN, 0) : String.valueOf(r.fields.awakenings), "", 0, Explain.WATCH_WAKE_UPS)));
            root.addView(stats);
        }
        double[] debt = h.debt(d.date);
        LinearLayout dc = Ui.card(c);
        dc.addView(Ui.caption(c, "Sleep debt"));
        dc.addView(Ui.gap(c, 4));
        boolean valid = debt[1] > 0;
        double m = debt[0];
        dc.addView(Ui.text(c, !valid ? "Building" : m < 180 ? "None" : m < 360 ? "Low" : m < 540 ? "Moderate" : "High", 20, Ui.TEXT, Ui.LIGHT));
        dc.addView(Ui.body(c, (valid ? Ui.hm(m) + " over the last two weeks. " : Explain.WATCH_DEBT_BUILDING + " ")
                + "Your sleep need is " + Ui.fmt(debt[2], 1) + " h."));
        dc.addView(Ui.text(c, Explain.WATCH_DEBT, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(dc);

        double[] asleep = h.last(h.asleepMin, d.date, DeviceHistory.WEEK);
        for (int i = 0; i < asleep.length; i++) asleep[i] /= 60.0;
        root.addView(week(c, d, "Total sleep, last 7 days", asleep, Ui.SLEEP, "%.1f h", 8, new Fmt() {
            @Override
            public String of(double v) {
                return Ui.hm(v * 60) + " asleep";
            }
        }, average(asleep, " h", 1) + "Time asleep each night, as your watch counted it. The line is 8 hours."));

        // sleep times: one bar per night from falling asleep to waking up
        int k = DeviceHistory.MONTH;
        double[] bed = new double[k], wake = new double[k];
        String[] tips = new String[k], dates = new String[k];
        int nights = 0;
        for (int i = 0; i < k; i++) {
            LocalDate day = d.date.minusDays(k - 1 - i);
            DeviceHistory.NightRow n = h.night(day);
            bed[i] = n == null ? Double.NaN : n.fields.sleepStart;
            wake[i] = n == null ? Double.NaN : n.fields.sleepEnd;
            if (!Double.isNaN(bed[i])) nights++;
            tips[i] = DeviceHistory.tip(day, DeviceHistory.timesText(n, h.zone(day)), Locale.getDefault());
            dates[i] = day.getDayOfMonth() + "." + day.getMonthValue() + ".";
        }
        LinearLayout times = Ui.card(c);
        times.addView(Ui.caption(c, "Sleep times, last 30 days"));
        times.addView(Ui.gap(c, 8));
        Charts.SleepTimes st = new Charts.SleepTimes(c, bed, wake, tips, dates, d.zone);
        st.setContentDescription("Sleep times, last 30 days");
        times.addView(st, Ui.sized(c, -1, 190));
        times.addView(Ui.gap(c, 6));
        times.addView(Ui.text(c, Explain.WATCH_SLEEP_TIMES + (nights > 0 ? " " + nights + (nights == 1 ? " night." : " nights.") : ""), 12, Ui.TEXT_2, Ui.REGULAR));
        root.addView(times);

        double[] score = h.last(h.sleep, d.date, DeviceHistory.MONTH);
        root.addView(month(c, d, "Sleep score, last 30 days", score, Ui.SLEEP, "", Explain.WATCH_NO_NIGHTS,
                average(score, "", 0) + Explain.WATCH_SCORE_MONTH));
        root.addView(DeviceViews.needCard(c, Explain.NEEDS_RING_TITLE, Explain.SLEEP_NEEDS_RING, false));
    }

    // ===================================================================== READINESS

    /** Ready with only the watch, after the score's parts: last night's heart rate next to the usual, the 30 and 7 day charts. */
    static void ready(Context c, LinearLayout root, DayData d) {
        if (!watch(d)) return;
        DeviceHistory h = d.deviceHistory;
        DeviceHistory.NightRow r = h.night(d.date);
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Last night's heart rate"));
        card.addView(Ui.gap(c, 4));
        card.addView(Pages.signalRow(c, "Resting heart rate", r == null || r.lowest <= 0 ? Double.NaN : r.lowest, h.usualRhr(d.date), "bpm", 0, true));
        if (r != null && r.lowest > 0) {
            double s0 = r.fields.sleepStart, s1 = r.fields.sleepEnd;
            String rel = s1 > s0 ? ((r.lowestAt - s0) / (s1 - s0) <= 0.5 ? ", in the first half of the night" : ", late in the night") : "";
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.body(c, "Lowest reading at " + Pages.minute(r.lowestAt, r.zone) + rel + "."));
        }
        card.addView(Ui.text(c, Explain.WATCH_RHR, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(card);
        double[] rhr = h.last(h.rhr, d.date, DeviceHistory.MONTH);
        root.addView(month(c, d, "Resting heart rate, last 30 days", rhr, Ui.HEART, " bpm", Explain.WATCH_NO_NIGHTS,
                average(rhr, " bpm", 0) + "A downward trend usually means improving fitness and recovery."));
        double[] ready = h.last(h.readiness, d.date, DeviceHistory.WEEK);
        root.addView(week(c, d, "Readiness, last 7 days", ready, Ui.READY, "%.0f", Double.NaN, new Fmt() {
            @Override
            public String of(double v) {
                return "readiness " + Math.round(v);
            }
        }, average(ready, "", 0) + Explain.WATCH_READY_WEEK));
    }

    // ====================================================================== ACTIVITY

    /** Activity without a ring: steps of the last 7 and 30 days, the activity score, and with the watch calories and workouts. */
    static void activity(Context c, LinearLayout root, DayData d) {
        if (!on(d)) return;
        DeviceHistory h = d.deviceHistory;
        boolean w = h.device == MainDevice.WATCH;
        double[] steps7 = h.last(h.steps, d.date, DeviceHistory.WEEK);
        root.addView(week(c, d, "Steps, last 7 days", steps7, Ui.ACTIVE, "%,.0f", h.goal, new Fmt() {
            @Override
            public String of(double v) {
                return DeviceHistory.stepsText(v, Locale.getDefault());
            }
        }, average(steps7, " steps", 0) + "The line is your goal of " + whole(h.goal) + " steps (Settings, Watch). " + from(d) + "."));
        double[] steps30 = h.last(h.steps, d.date, DeviceHistory.MONTH);
        root.addView(month(c, d, "Steps, last 30 days", steps30, Ui.ACTIVE, " steps", Explain.NO_STEPS_YET,
                average(steps30, " steps", 0) + from(d) + "."));
        double[] score = h.last(h.activity, d.date, DeviceHistory.WEEK);
        root.addView(week(c, d, "Activity score, last 7 days", score, Ui.ACTIVE, "%.0f", Double.NaN, new Fmt() {
            @Override
            public String of(double v) {
                return "activity " + Math.round(v);
            }
        }, average(score, "", 0) + Explain.ACTIVITY_WEEK_DEVICE));
        if (w) {
            double[] kcal = h.last(h.kcal, d.date, DeviceHistory.WEEK);
            root.addView(week(c, d, "Calories from movement, last 7 days", kcal, Ui.ACTIVE, "%.0f", Double.NaN, new Fmt() {
                @Override
                public String of(double v) {
                    return whole(v) + " kcal";
                }
            }, average(kcal, " kcal", 0) + Explain.WATCH_KCAL));
            final double[] min = h.last(h.workoutMin, d.date, DeviceHistory.MONTH);
            final int[] count = new int[min.length];
            int total = 0;
            for (int i = 0; i < min.length; i++) {
                int j = h.index(d.date.minusDays(min.length - 1 - i));
                count[i] = j < 0 ? 0 : h.workouts[j];
                total += count[i];
            }
            String[] tips = new String[min.length], labels = new String[min.length];
            for (int i = 0; i < min.length; i++) {
                LocalDate day = d.date.minusDays(min.length - 1 - i);
                tips[i] = DeviceHistory.tip(day, Double.isNaN(min[i]) ? Explain.NO_VALUE : count[i] == 0 ? "No workout"
                        : app.openring.core.Start.workouts(count[i]) + ", " + Math.round(min[i]) + " min", Locale.getDefault());
                // a date every week, the newest 3 days in from the right end so no label is cut off
                labels[i] = (min.length - 1 - i) % 7 == 3 ? day.getDayOfMonth() + "." + day.getMonthValue() + "." : "";
            }
            LinearLayout card = Ui.card(c);
            card.addView(Ui.caption(c, "Workouts, last 30 days"));
            card.addView(Ui.gap(c, 8));
            // no value over a bar (30 are too narrow for it): touch reads each day
            Charts.BarChart bars = new Charts.BarChart(c, min, labels, Ui.ACTIVE).highlight(-1).tips(tips);
            bars.setContentDescription("Workouts, last 30 days");
            card.addView(bars, Ui.sized(c, -1, 130));
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.text(c, app.openring.core.Start.workouts(total) + " in 30 days. " + Explain.WATCH_WORKOUTS_MONTH, 12, Ui.TEXT_2, Ui.REGULAR));
            root.addView(card);
        }
        root.addView(DeviceViews.needCard(c, Explain.NEEDS_RING_TITLE, Explain.ACTIVITY_NEEDS_RING, !w));
    }

    // ========================================================================== BODY

    /** Body with only the watch, under the heart rate of the day: resting and day heart rate of 30 days, cardio capacity. */
    static void body(Context c, LinearLayout root, DayData d, double age) {
        if (!watch(d)) return;
        DeviceHistory h = d.deviceHistory;
        double[] rhr = h.last(h.rhr, d.date, DeviceHistory.MONTH);
        root.addView(month(c, d, "Resting heart rate, 30 days", rhr, Ui.HEART, " bpm", Explain.WATCH_NO_NIGHTS, average(rhr, " bpm", 0) + Explain.WATCH_RHR));
        double[] day = h.last(h.dayHr, d.date, DeviceHistory.MONTH);
        root.addView(month(c, d, "Heart rate through the day, 30 days", day, Ui.HEART, " bpm", Explain.WATCH_NO_HEART, average(day, " bpm", 0) + Explain.WATCH_DAY_HR));
        LinearLayout vo = Ui.card(c);
        vo.addView(Ui.caption(c, "Cardio capacity (estimate)"));
        double v = h.vo2max(d.date, age), shown = app.openring.core.analysis.Wellness.vo2maxShown(v);
        if (!Double.isNaN(shown)) vo.addView(Ui.text(c, Ui.fmt(shown, 0) + " ml/kg/min", 24, Ui.TEXT, Ui.LIGHT));
        vo.addView(Ui.body(c, Explain.vo2maxWatch(v, h.night(d.date) != null)));
        vo.addView(Ui.gap(c, 4));
        vo.addView(Ui.text(c, Explain.VO2MAX, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(vo);
    }
}
