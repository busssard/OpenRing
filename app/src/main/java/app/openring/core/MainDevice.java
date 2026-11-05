package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.DayHeart;
import app.openring.core.analysis.TS;
import app.openring.core.analysis.Wellness;
import app.openring.core.withings.Records;
import app.openring.core.withings.WatchDays;

/**
 * One set of numbers from the ring and the watch (issue #251, the owner's decision of 3 November): the person picks the
 * main device once in Settings, and the other one only fills the time when the main one was not worn. Worked out on each
 * load from the stored day summary and watch rows; nothing here is stored, so switching the main device changes only
 * what is shown.
 *
 * <p>Not worn is decided per quarter hour of the day ({@link #SLOT_S}, from its midnight in the day's zone, so 92 or 100
 * on a clock change day). The ring was worn in a quarter hour when its day summary has a movement value there
 * ({@code met_profile}, by clock time), a heart rate point ({@code hr_day}), or the quarter hour lies inside one of its
 * nights or naps: the rule of Not worn on Activity, so charging, off the finger and a time left out (#254) all count as
 * not worn. The watch sends no wear signal and measures heart rate only about every 30 minutes, so it was worn in a
 * quarter hour when any of its records lies within {@link #WATCH_REACH_S} of it.
 *
 * <p>With only one device the numbers are that device's, exactly as stored (the same doubles, the same line).
 *
 * <p>Each device is one {@link Source} (issue #329, phase 1a, moved here from {@code core/withings}): the step sum and the
 * gap filling exist once, in {@link #numbers}, for whichever device is the main one.
 */
public final class MainDevice {
    private MainDevice() {}

    /** The stored codes (Prefs and backups): never change them. */
    public static final int RING = 0, WATCH = 1;
    /** Reserved for the phone's own step counter (issue #329, phase 1b); not a choice yet, {@link #valid} still makes it the ring. */
    public static final int PHONE = 2;
    /** The devices that can be picked as main device, in the order Settings shows them (a copy: {@link #choices}). */
    private static final int[] CHOICES = {RING, WATCH};

    /** The devices that can be picked as main device, in the order Settings shows them; a new array on each call. */
    public static int[] choices() {
        return CHOICES.clone();
    }

    /** The name on a device's chip in Settings ("Ring", "Watch"; "Phone" for the reserved code, not shown yet). */
    public static String label(int device) {
        switch (device) {
            case WATCH: return "Watch";
            case PHONE: return "Phone";
            default: return "Ring";
        }
    }
    /** Width of one quarter hour, the unit gaps are found in. */
    public static final double SLOT_S = 900;
    /** A watch record counts the watch as worn this long before and after it (its heart rate comes about every 30 minutes). */
    public static final long WATCH_REACH_S = 1800;

    /** A stored choice that is not one of the two is the ring, the default (also {@link #PHONE} while it is reserved). */
    public static int valid(int main) {
        return main == WATCH ? WATCH : RING;
    }

    /** A value that is one of the choices (a restore takes only such a value from a backup); reads the private array, so no caller can change it. */
    public static boolean known(int main) {
        for (int c : CHOICES) if (c == main) return true;
        return false;
    }

    /** The other device's name in a sentence ("your watch"). */
    public static String name(int device) {
        return device == WATCH ? "your watch" : "your ring";
    }

    /** What the ring's stored results give for one day, read by the caller from its day summary and nights. */
    public static final class Ring {
        /** The day summary's steps and distance in metres, NaN without a summary. */
        public double steps = Double.NaN, metres = Double.NaN;
        /** Movement (MET) per quarter hour of the clock, 96 values, NaN where the ring has none. */
        public final double[] met = new double[96];
        /** The day's heart rate line ({@code hr_day}, quarter hour averages). */
        public TS hr = new TS();
        /** The ring's nights and naps that reach this day, each {start, end}. */
        public final List<double[]> sleeps = new ArrayList<double[]>();
        /** The ring has a main night for this day. */
        public boolean night;

        public Ring() {
            java.util.Arrays.fill(met, Double.NaN);
        }
    }

    /** The watch's rows around one day (more is fine, only this day's are used). */
    public static final class Watch {
        public final List<Records.Steps> steps = new ArrayList<Records.Steps>();
        public final List<Records.Heart> heart = new ArrayList<Records.Heart>();
        public final List<Records.Sleep> sleep = new ArrayList<Records.Sleep>();
        public final List<Records.Workout> workouts = new ArrayList<Records.Workout>();

        public boolean isEmpty() {
            return steps.isEmpty() && heart.isEmpty() && sleep.isEmpty() && workouts.isEmpty();
        }
    }

    /** The day as shown. */
    public static final class Day {
        public int main;
        /** Steps and distance in metres, NaN when no device has any. */
        public double steps = Double.NaN, metres = Double.NaN;
        /** The part of {@link #steps} that came from the other device (0 for none). */
        public double fillSteps;
        /** Where the other device's steps were used, merged quarter hours {start, end}. */
        public final List<double[]> stepFill = new ArrayList<double[]>();
        /** The heart rate line over the day, quarter hour points. */
        public TS hr = new TS();
        /** Where the other device's heart rate was used. */
        public final List<double[]> hrFill = new ArrayList<double[]>();
        /** The main device has no quarter hour on this day: its numbers are all the other device's. */
        public boolean whole;
        /** Withings' own night ending on this day when it is the one shown, else null (the ring's night is shown). */
        public WatchDays.Night watchNight;
        /** The ring also has a night for this day (shown or not). */
        public boolean ringNight;
        /** The watch has rows around this day (else nothing is named: someone with only a ring sees the ring as before). */
        public boolean watchRows;
        /** The watch's workouts that start on this day, oldest first (only the watch records them). */
        public final List<Records.Workout> workouts = new ArrayList<Records.Workout>();

        /** The other device filled some of the day. */
        public boolean filled() {
            return whole || !stepFill.isEmpty() || !hrFill.isEmpty() || nightFromOther();
        }

        /** The night shown comes from the device that is not the main one. */
        public boolean nightFromOther() {
            return main == RING ? watchNight != null : watchNight == null && ringNight && watchRows;
        }

        /** Anything to show: steps, a heart rate line, a night or a workout (for a day without the ring's summary). */
        public boolean any() {
            return !Double.isNaN(steps) || hr.size() > 0 || watchNight != null || !workouts.isEmpty();
        }

        /** The other device. */
        public int other() {
            return main == WATCH ? RING : WATCH;
        }
    }

    /** Quarter hours of the day from {@code from} to {@code to} (92, 96 or 100). */
    public static int slots(double from, double to) {
        return (int) Math.max(0, Math.ceil((to - from) / SLOT_S - 1e-9));
    }

    static int slot(double t, double from) {
        return (int) Math.floor((t - from) / SLOT_S);
    }

    /** The clock quarter hour (0 to 95) of a time, where the ring's movement profile keeps it. */
    public static int clockQuarter(double t, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond((long) Math.floor(t)).atZone(zone);
        return Math.min(95, z.getHour() * 4 + z.getMinute() / 15);
    }

    /** Which quarter hours the ring was worn in. */
    static boolean[] ringWorn(Ring r, double from, double to, ZoneId zone) {
        int n = slots(from, to);
        boolean[] w = new boolean[n];
        for (int s = 0; s < n; s++) {
            double a = from + s * SLOT_S, mid = a + SLOT_S / 2;
            if (!Double.isNaN(r.met[clockQuarter(a, zone)])) w[s] = true;
            for (double[] sl : r.sleeps) if (mid >= sl[0] && mid < sl[1]) w[s] = true;
        }
        for (int i = 0; i < r.hr.size(); i++) {
            int s = slot(r.hr.t(i), from);
            if (s >= 0 && s < n && !Double.isNaN(r.hr.v(i))) w[s] = true;
        }
        return w;
    }

    /** Which quarter hours the watch was worn in: any record within {@link #WATCH_REACH_S}. */
    static boolean[] watchWorn(Watch w, double from, double to) {
        int n = slots(from, to);
        boolean[] on = new boolean[n];
        for (Records.Steps s : w.steps) mark(on, s.start, s.start + Math.max(0, s.dur), from);
        for (Records.Heart h : w.heart) mark(on, h.t, h.t, from);
        for (Records.Sleep s : w.sleep) mark(on, s.start, s.start + Math.max(0, s.dur), from);
        for (Records.Workout x : w.workouts) mark(on, x.start, x.start + Math.max(0, x.dur), from);
        return on;
    }

    private static void mark(boolean[] on, double a, double b, double from) {
        int lo = Math.max(0, slot(a - WATCH_REACH_S, from)), hi = Math.min(on.length - 1, slot(b + WATCH_REACH_S, from));
        for (int s = lo; s <= hi; s++) on[s] = true;
    }

    /** Steps the ring's per minute rule ({@code Wellness.day}) gives a quarter hour of this average movement. */
    public static double ringSlotSteps(double met) {
        if (Double.isNaN(met)) return 0;
        double perMin = met >= 7 ? 150 : met >= 2.5 ? 105 : met >= 1.8 ? 25 : 0;
        return perMin * SLOT_S / 60;
    }

    private static boolean any(boolean[] a) {
        for (boolean b : a) if (b) return true;
        return false;
    }

    /**
     * One device's day as the merge sees it (issue #329, phase 1a): which quarter hours it covers, its own day total, the
     * steps it can lend to another device's gaps (per quarter hour) and its heart rate. The ring and the watch are made by
     * {@link #ring} and {@link #watch}; {@link #numbers} merges any main device with the other one, so a new device is a
     * new source, not a third copy of the step sum and the gap filling.
     */
    public static final class Source {
        /** {@link #RING}, {@link #WATCH} (and {@link #PHONE} once it exists). */
        public final int kind;
        /** The quarter hours of the day it covers (was worn). */
        public final boolean[] worn;
        /** It has anything on this day. */
        public final boolean present;
        /** Its own day total when it is the main device, NaN for none. */
        public final double steps, metres;
        /** The steps it lends to the main device's gaps, each {quarter hour, steps, metres}, in the order they are summed. */
        public final List<double[]> parts = new ArrayList<double[]>();
        /**
         * Its parts are an estimate (the ring's per minute rule): their sum is rounded to tens, the distance follows from
         * the steps, and only a sum above 0 counts. A main device with nothing on the day then shows this device's own day
         * as stored instead of the estimate.
         */
        public final boolean estimate;
        /** Its heart rate points: the ring's line as stored, or the watch's readings of the day (made a line by {@link #curve}). */
        final TS heart;
        /** {@link #heart} holds single readings that become a line with {@code DayHeart.curve}. */
        final boolean curve;
        private final double from, to;

        Source(int kind, boolean[] worn, boolean present, double steps, double metres, boolean estimate, TS heart, boolean curve, double from, double to) {
            this.kind = kind;
            this.worn = worn;
            this.present = present;
            this.steps = steps;
            this.metres = metres;
            this.estimate = estimate;
            this.heart = heart;
            this.curve = curve;
            this.from = from;
            this.to = to;
        }

        /** The heart rate line it shows as the main device (the ring's own line object, unchanged). */
        public TS line() {
            return curve ? heartIn(null) : heart;
        }

        /** Its heart rate in the quarter hours {@code skip} leaves free (null: all), as a line. */
        public TS heartIn(boolean[] skip) {
            int n = slots(from, to);
            TS out = new TS();
            for (int i = 0; i < heart.size(); i++) {
                int k = slot(heart.t(i), from);
                if (k >= 0 && k < n && (skip == null || !skip[k]) && !Double.isNaN(heart.v(i))) out.add(heart.t(i), heart.v(i));
            }
            return curve ? DayHeart.curve(out, from, to) : out;
        }
    }

    /** The ring as a source: worn by its movement, heart rate and nights; it lends its movement estimate outside its nights. */
    public static Source ring(Ring r, double from, double to, ZoneId zone) {
        boolean[] rw = ringWorn(r, from, to, zone);
        Source s = new Source(RING, rw, any(rw), r.steps, r.metres, true, r.hr, false, from, to);
        for (int k = 0; k < rw.length; k++) {
            if (!rw[k]) continue;
            double a = from + k * SLOT_S, mid = a + SLOT_S / 2;
            boolean asleep = false;
            for (double[] sl : r.sleeps) if (mid >= sl[0] && mid < sl[1]) asleep = true;
            if (asleep) continue;
            double add = ringSlotSteps(r.met[clockQuarter(a, zone)]);
            if (add > 0) s.parts.add(new double[] {k, add, 0});
        }
        return s;
    }

    /** The watch as a source: worn near its records; it lends its step records, its total is their sum (NaN without any). */
    public static Source watch(Watch w, double from, double to) {
        boolean[] ww = watchWorn(w, from, to);
        double steps = 0, metres = 0;
        boolean got = false;
        List<double[]> parts = new ArrayList<double[]>();
        for (Records.Steps s : w.steps) {
            if (s.start < from || s.start >= to) continue;
            steps += s.steps;
            metres += s.metres;
            parts.add(new double[] {slot(s.start, from), s.steps, s.metres});
            got = true;
        }
        TS readings = new TS();
        for (Records.Heart h : w.heart) if (h.t >= from && h.t < to) readings.add(h.t, h.bpm);
        Source s = new Source(WATCH, ww, anyWatchIn(w, from, to) && any(ww), got ? steps : Double.NaN, got ? metres : Double.NaN, false, readings, true, from, to);
        s.parts.addAll(parts);
        return s;
    }

    /**
     * The day {@code date} from {@code from} to {@code to} (its midnights in {@code zone}) as shown with {@code main} as the
     * main device. {@code watch} null or empty: the ring's numbers exactly as given.
     */
    public static Day of(int main, LocalDate date, double from, double to, final ZoneId zone, Ring ring, Watch watch) {
        Day d = new Day();
        d.main = valid(main);
        d.ringNight = ring.night;
        if (watch == null) watch = new Watch();
        d.watchRows = !watch.isEmpty();
        WatchDays.Zones z = new WatchDays.Zones() {
            @Override
            public ZoneId at(double unix) {
                return zone;
            }
        };
        for (Records.Workout w : watch.workouts) if (w.start >= from && w.start < to) d.workouts.add(w);
        Collections.sort(d.workouts, new Comparator<Records.Workout>() {
            @Override
            public int compare(Records.Workout a, Records.Workout b) {
                return Long.compare(a.start, b.start);
            }
        });
        WatchDays.Night wn = watch.sleep.isEmpty() ? null : WatchDays.nightFor(watch.sleep, date, z);
        if (d.main == WATCH || !ring.night) d.watchNight = wn;
        List<Source> sources = new ArrayList<Source>();
        sources.add(ring(ring, from, to, zone));
        sources.add(watch(watch, from, to));
        numbers(d, sources, from, to);
        return d;
    }

    /**
     * Steps, distance and the heart rate line of {@code d} (its {@code main} and {@code watchRows} set): the main device's
     * own numbers, and the other device's only in the quarter hours the main one did not cover. One path for every pair
     * of devices. {@code sources} holds one source per device, the main one among them; until the phone arrives (issue
     * #329, phase 1b) at most one other.
     */
    public static void numbers(Day d, List<Source> sources, double from, double to) {
        Source main = null, other = null;
        for (Source s : sources) {
            if (s.kind == d.main && main == null) main = s;
            else if (other == null) other = s;
            else throw new IllegalArgumentException("one other device only, the phone comes with phase 1b of #329");
        }
        if (main == null || other == null) throw new IllegalArgumentException("the main device and one other are needed");
        int n = slots(from, to);
        // named only when the watch has rows at all (a day before it was added, a day off the wrist)
        d.whole = !main.present && other.present && d.watchRows;
        if (!main.present && other.estimate) {
            // the main device has nothing on this day: the other's day as stored, not its estimate
            d.steps = other.steps;
            d.metres = other.metres;
            d.hr = other.line();
            return;
        }
        d.steps = main.steps;
        d.metres = main.metres;
        boolean[] used = new boolean[n];
        double steps = 0, metres = 0;
        boolean got = false;
        for (double[] p : other.parts) {
            int k = (int) p[0];
            if (main.worn[k]) continue;
            steps += p[1];
            metres += p[2];
            used[k] = true;
            got = true;
        }
        if (other.estimate) {
            steps = Math.round(steps / 10.0) * 10;
            metres = Wellness.stepsToMeters(steps);
            got = steps > 0;
        }
        if (got) {
            d.fillSteps = steps;
            d.steps = (Double.isNaN(main.steps) ? 0 : main.steps) + steps;
            d.metres = (Double.isNaN(main.metres) ? 0 : main.metres) + metres;
            d.stepFill.addAll(stretches(not(main.worn), used, from, to));
        }
        TS line = main.line(), add = other.heartIn(main.worn);
        d.hr = add.size() > 0 ? joined(line, add) : line;
        if (add.size() > 0) d.hrFill.addAll(stretches(not(main.worn), slotsOf(add, from, n), from, to));
    }

    private static boolean anyWatchIn(Watch w, double from, double to) {
        for (Records.Steps s : w.steps) if (s.start >= from && s.start < to) return true;
        for (Records.Heart h : w.heart) if (h.t >= from && h.t < to) return true;
        for (Records.Sleep s : w.sleep) if (s.start + s.dur > from && s.start < to) return true;
        for (Records.Workout x : w.workouts) if (x.start >= from && x.start < to) return true;
        return false;
    }

    private static boolean[] slotsOf(TS ts, double from, int n) {
        boolean[] s = new boolean[n];
        // every point lies inside the day (the callers' rule)
        for (int i = 0; i < ts.size(); i++) s[slot(ts.t(i), from)] = true;
        return s;
    }

    private static TS joined(TS a, TS b) {
        TS out = new TS();
        for (int i = 0; i < a.size(); i++) out.add(a.t(i), a.v(i));
        for (int i = 0; i < b.size(); i++) out.add(b.t(i), b.v(i));
        return out.sort();
    }

    private static boolean[] not(boolean[] a) {
        boolean[] b = new boolean[a.length];
        for (int i = 0; i < a.length; i++) b[i] = !a[i];
        return b;
    }

    /**
     * The stretches of quarter hours in {@code gap} (the main device not worn) that hold at least one {@code used} one, as
     * {start, end}: the watch's heart rate every 30 minutes fills one stretch, not every other quarter hour.
     */
    static List<double[]> stretches(boolean[] gap, boolean[] used, double from, double to) {
        List<double[]> out = new ArrayList<double[]>();
        for (double[] r : ranges(gap, from, to)) {
            int a = slot(r[0], from), b = (int) Math.ceil((r[1] - from) / SLOT_S - 1e-9);
            boolean any = false;
            for (int k = Math.max(0, a); k < Math.min(used.length, b); k++) any |= used[k];
            if (any) out.add(r);
        }
        return out;
    }

    /** Marked quarter hours as merged {start, end} ranges, the last one cut at {@code to}. */
    public static List<double[]> ranges(boolean[] on, double from, double to) {
        List<double[]> out = new ArrayList<double[]>();
        int k = 0;
        while (k < on.length) {
            if (!on[k]) {
                k++;
                continue;
            }
            int j = k;
            while (j + 1 < on.length && on[j + 1]) j++;
            out.add(new double[] {from + k * SLOT_S, Math.min(to, from + (j + 1) * SLOT_S)});
            k = j + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ texts

    /** "14:00 to 16:00", several joined with commas and a last "and". */
    public static String clockRanges(List<double[]> r, ZoneId zone) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.size(); i++) {
            if (i > 0) b.append(i == r.size() - 1 ? " and " : ", ");
            b.append(clock(r.get(i)[0], zone)).append(" to ").append(clock(r.get(i)[1], zone));
        }
        return b.toString();
    }

    static String clock(double t, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond((long) Math.round(t)).atZone(zone);
        return String.format(Locale.US, "%02d:%02d", z.getHour(), z.getMinute());
    }

    /** The line under Steps, null when the main device counted them all. */
    public static String stepsLine(Day d, ZoneId zone, Locale l) {
        String other = name(d.other()), main = name(d.main);
        if (d.whole && !Double.isNaN(d.steps)) return "From " + other + ": " + main + " has no data for this day.";
        if (d.stepFill.isEmpty()) return null;
        return "Includes " + (d.main == WATCH ? "about " : "") + String.format(l, "%,d", Math.round(d.fillSteps)) + (Math.round(d.fillSteps) == 1 ? " step" : " steps")
                + " from " + other + ", " + clockRanges(d.stepFill, zone) + ", when " + main + " was not worn.";
    }

    /** The line under the heart rate of the day, null when the main device measured it all. */
    public static String heartLine(Day d, ZoneId zone) {
        String other = name(d.other()), main = name(d.main);
        if (d.whole && d.hr.size() > 0) return "From " + other + ": " + main + " has no data for this day.";
        if (d.hrFill.isEmpty()) return null;
        return "From " + other + " " + clockRanges(d.hrFill, zone) + ", when " + main + " was not worn.";
    }

    /** The line under the night, null when it is the main device's. */
    public static String nightLine(Day d) {
        if (!d.nightFromOther()) return null;
        return "From " + name(d.other()) + ": " + name(d.main) + " has no night for this day.";
    }

    /**
     * With the watch as main device and its night shown, the ring's night of the same day is not: this names its sleep
     * score and where to see it. Null otherwise.
     */
    public static String ringTooLine(Day d, double ringScore) {
        if (d.main != WATCH || d.watchNight == null || !d.ringNight) return null;
        return "Your ring also recorded this night" + (Double.isNaN(ringScore) ? "" : ", with a sleep score of " + Math.round(ringScore))
                + ". Readiness uses the ring's night. Choose Ring as main device in Settings to see its analysis here.";
    }

    /** The Steps tile's own line about where the number comes from; null for the ring's own estimate (its usual line). */
    public static String stepsNote(Day d) {
        if (d.whole) return d.main == RING ? "Counted by your watch" : null;
        if (d.main == WATCH) return d.fillSteps > 0 ? "Counted by your watch, some estimated by your ring" : "Counted by your watch";
        return d.fillSteps > 0 ? "Estimated from your movement, some counted by your watch" : null;
    }
}
