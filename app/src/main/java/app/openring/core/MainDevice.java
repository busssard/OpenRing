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
 *
 * <p>The phone (issue #329, phase 1b) is the third device: steps only, from its step counter ({@link PhoneSteps}). It
 * covers the quarter hours its counted intervals reach, lends each interval's steps spread evenly over it (an estimate), and
 * fills last: only where neither the main device nor the device before it was worn. It has no heart rate and no night.
 */
public final class MainDevice {
    private MainDevice() {}

    /** The stored codes (Prefs and backups): never change them. */
    public static final int RING = 0, WATCH = 1;
    /** The phone's own step counter (issue #329, phase 1b): steps only. */
    public static final int PHONE = 2;
    /** The devices that can be picked as main device, in the order Settings shows them (a copy: {@link #choices}). */
    private static final int[] CHOICES = {RING, WATCH, PHONE};

    /** The devices that can be picked as main device, in the order Settings shows them; a new array on each call. */
    public static int[] choices() {
        return CHOICES.clone();
    }

    /** The name on a device's chip in Settings ("Ring", "Watch", "Phone"). */
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

    /** A stored choice that is not one of the three is the ring, the default before the phone existed. */
    public static int valid(int main) {
        return main == WATCH || main == PHONE ? main : RING;
    }

    /**
     * The main device when the person never picked one (issue #329): the ring when one is paired, else the watch when one is
     * added, else the phone when it counts steps, else the ring (as before).
     */
    public static int defaultMain(boolean ring, boolean watch, boolean phone) {
        if (ring) return RING;
        if (watch) return WATCH;
        return phone ? PHONE : RING;
    }

    /**
     * The main device shown (issue #329): {@code stored} is the saved choice, -1 for none. None: {@link #defaultMain}. A
     * stored phone ({@code 2}, which a version of phase 1a read as the ring) is the phone only while the phone counts steps
     * ({@code phone}), otherwise the ring as before; the ring and the watch stay as stored (also a watch that was forgotten,
     * as before).
     */
    public static int shown(int stored, boolean ring, boolean watch, boolean phone) {
        if (stored < 0) return defaultMain(ring, watch, phone);
        if (stored == PHONE) return phone ? PHONE : RING;
        return valid(stored);
    }

    /** The devices Settings offers as main device: those present, in the order of {@link #choices}. */
    public static int[] present(boolean ring, boolean watch, boolean phone) {
        int n = (ring ? 1 : 0) + (watch ? 1 : 0) + (phone ? 1 : 0);
        int[] out = new int[n];
        int k = 0;
        if (ring) out[k++] = RING;
        if (watch) out[k++] = WATCH;
        if (phone) out[k] = PHONE;
        return out;
    }

    /** The line Settings shows for the main device ("Phone (steps only)" for the phone, else its label). */
    public static String mainLine(int device) {
        return device == PHONE ? PhoneSteps.MAIN_LINE : label(device);
    }

    /** A value that is one of the choices (a restore takes only such a value from a backup); reads the private array, so no caller can change it. */
    public static boolean known(int main) {
        for (int c : CHOICES) if (c == main) return true;
        return false;
    }

    /** A device's name in a sentence ("your watch"). */
    public static String name(int device) {
        return device == WATCH ? "your watch" : device == PHONE ? "your phone" : "your ring";
    }

    /** The order the other devices fill the main one's gaps in: ring, watch, then the phone (steps only, last). */
    static int priority(int kind) {
        return kind == RING ? 0 : kind == WATCH ? 1 : 2;
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

    /**
     * The phone's step intervals around one day (issue #329, phase 1b), each {start, stop, steps}; more is fine, only the parts
     * inside the day count, spread evenly over each interval.
     */
    public static final class Phone {
        public final List<double[]> intervals = new ArrayList<double[]>();

        public boolean isEmpty() {
            return intervals.isEmpty();
        }
    }

    /** What one other device added to the day: its steps and the quarter hours it filled. */
    public static final class Fill {
        public final int kind;
        public final double steps;
        public final List<double[]> ranges = new ArrayList<double[]>();

        Fill(int kind, double steps) {
            this.kind = kind;
            this.steps = steps;
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
        /** The watch's heart rate readings during {@link #watchNight}, null without that night (the owner's wish of 11 November). */
        public WatchDays.NightHeart watchNightHeart;
        /** The ring also has a night for this day (shown or not). */
        public boolean ringNight;
        /** The watch has rows around this day (else nothing is named: someone with only a ring sees the ring as before). */
        public boolean watchRows;
        /** The watch's workouts that start on this day, oldest first (only the watch records them). */
        public final List<Records.Workout> workouts = new ArrayList<Records.Workout>();
        /**
         * Every other device that added steps, in the order they filled (issue #329): the first one's are also in
         * {@link #fillSteps} and {@link #stepFill} as before, the phone's come last.
         */
        public final List<Fill> fills = new ArrayList<Fill>();
        /** The device that fills first (the old "other"), -1 before {@link #numbers}. */
        public int otherKind = -1;
        /** The device the main device's gaps are measured from: the main one, or the other's own day when the main one has none. */
        public int base = -1;
        /** With {@link #whole}: the device whose numbers they are. */
        public int wholeFrom = -1;
        /** The device whose heart rate fills the line, -1 for none. */
        public int hrFrom = -1;
        /** The phone's intervals that reach into this day, each {start, stop, steps} (the evidence on Activity), empty for none. */
        public final List<double[]> phone = new ArrayList<double[]>();
        /** The steps the {@link #base} device lends per quarter hour ({quarter hour, steps, metres}), and where it was worn. */
        public final List<double[]> baseParts = new ArrayList<double[]>();
        public boolean[] baseWorn = new boolean[0];
        /** The quarter hours some device counted steps in after the filling (the main device, then each other in turn). */
        public boolean[] covered = new boolean[0];

        /** The steps the phone added, 0 for none. */
        public double phoneSteps() {
            for (Fill f : fills) if (f.kind == PHONE) return f.steps;
            return 0;
        }

        /** The other device filled some of the day. */
        public boolean filled() {
            return whole || !stepFill.isEmpty() || !hrFill.isEmpty() || nightFromOther() || !fills.isEmpty();
        }

        /** The night shown comes from the device that is not the main one (the phone has none of its own). */
        public boolean nightFromOther() {
            if (main == PHONE) return watchNight != null || ringNight;
            return main == RING ? watchNight != null : watchNight == null && ringNight && watchRows;
        }

        /** Anything to show: steps, a heart rate line, a night or a workout (for a day without the ring's summary). */
        public boolean any() {
            return !Double.isNaN(steps) || hr.size() > 0 || watchNight != null || !workouts.isEmpty();
        }

        /** The other device (the first one that fills the main one's gaps). */
        public int other() {
            if (otherKind >= 0) return otherKind;
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
        /** {@link #RING}, {@link #WATCH} or {@link #PHONE}. */
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
         * Its parts are an estimate (the ring's per minute rule, the phone's even spread): their sum is rounded to
         * {@link #roundTo}, the distance follows from the steps, and only a sum above 0 counts. (Phase 1a let this flag also
         * pick the fallback below; the #347 review asked to split the two jobs before the phone came.)
         */
        public final boolean estimate;
        /** The unit {@link #estimate} parts are rounded to: tens for the ring, whole steps for the phone (0: not rounded). */
        public final double roundTo;
        /**
         * A main device with nothing on the day shows this device's own day as stored instead of its estimate (the ring:
         * its day summary). False for the watch and the phone, whose own day is the sum of what they lend.
         */
        public final boolean ownDay;
        /**
         * The device is there around this day, so "it has no data for this day" may be said when it is the main device: the
         * ring always (as before), the watch when it has rows around the day ({@code Day.watchRows}), the phone always.
         */
        public final boolean rows;
        /** Its heart rate points: the ring's line as stored, or the watch's readings of the day (made a line by {@link #curve}). */
        final TS heart;
        /** {@link #heart} holds single readings that become a line with {@code DayHeart.curve}. */
        final boolean curve;
        private final double from, to;

        Source(int kind, boolean[] worn, boolean present, double steps, double metres, boolean estimate, double roundTo, boolean ownDay, boolean rows,
                TS heart, boolean curve, double from, double to) {
            this.kind = kind;
            this.worn = worn;
            this.present = present;
            this.steps = steps;
            this.metres = metres;
            this.estimate = estimate;
            this.roundTo = roundTo;
            this.ownDay = ownDay;
            this.rows = rows;
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
        Source s = new Source(RING, rw, any(rw), r.steps, r.metres, true, 10, true, true, r.hr, false, from, to);
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
        Source s = new Source(WATCH, ww, anyWatchIn(w, from, to) && any(ww), got ? steps : Double.NaN, got ? metres : Double.NaN, false, 0, false,
                !w.isEmpty(), readings, true, from, to);
        s.parts.addAll(parts);
        return s;
    }

    /**
     * The phone as a source (issue #329, phase 1b): it covers every quarter hour one of its counted intervals reaches (a
     * phone that lay on a table counted 0 there), its total is the day's share of every interval, spread evenly, and it
     * lends each quarter hour's share above 0. The distance follows from the steps. No heart rate.
     */
    public static Source phone(Phone p, double from, double to) {
        int n = slots(from, to);
        boolean[] on = new boolean[n];
        double[] per = new double[n];
        double steps = 0;
        boolean got = false;
        for (double[] i : p.intervals) {
            double a = i[0], b = i[1];
            if (b <= from || a >= to) continue;
            got = true;
            steps += PhoneSteps.share(a, b, i[2], from, to);
            int lo = Math.max(0, slot(Math.max(a, from), from)), hi = Math.min(n - 1, (int) Math.ceil((Math.min(b, to) - from) / SLOT_S - 1e-9) - 1);
            for (int k = lo; k <= hi; k++) {
                double sa = from + k * SLOT_S, sb = Math.min(to, sa + SLOT_S);
                double part = PhoneSteps.share(a, b, i[2], sa, sb);
                if (b > sa && a < sb) on[k] = true;
                per[k] += part;
            }
        }
        Source s = new Source(PHONE, on, got, got ? steps : Double.NaN, got ? Wellness.stepsToMeters(steps) : Double.NaN, true, 1, false, true,
                new TS(), false, from, to);
        for (int k = 0; k < n; k++) if (per[k] > 0) s.parts.add(new double[] {k, per[k], 0});
        return s;
    }

    /**
     * The day {@code date} from {@code from} to {@code to} (its midnights in {@code zone}) as shown with {@code main} as the
     * main device. {@code watch} null or empty: the ring's numbers exactly as given. Without the phone: a stored phone
     * ({@code 2}) is the ring, as in phase 1a.
     */
    public static Day of(int main, LocalDate date, double from, double to, final ZoneId zone, Ring ring, Watch watch) {
        return of(main, date, from, to, zone, ring, watch, null);
    }

    /**
     * As above with the phone's intervals around the day (issue #329, phase 1b); {@code phone} null: no phone source at all,
     * and the phone as main device falls back to the ring. An empty {@code phone} is a source with nothing on this day.
     */
    public static Day of(int main, LocalDate date, double from, double to, final ZoneId zone, Ring ring, Watch watch, Phone phone) {
        Day d = new Day();
        d.main = valid(main);
        if (d.main == PHONE && phone == null) d.main = RING;
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
        if (d.watchNight != null) d.watchNightHeart = WatchDays.heartIn(watch.heart, d.watchNight.start, d.watchNight.end);
        List<Source> sources = new ArrayList<Source>();
        sources.add(ring(ring, from, to, zone));
        sources.add(watch(watch, from, to));
        if (phone != null) {
            sources.add(phone(phone, from, to));
            for (double[] i : phone.intervals) if (i[1] > from && i[0] < to) d.phone.add(i);
        }
        numbers(d, sources, from, to);
        return d;
    }

    /**
     * Steps, distance and the heart rate line of {@code d} (its {@code main} and {@code watchRows} set): the main device's
     * own numbers, and the others' only in the quarter hours the main one did not cover. One path for every set of devices.
     * {@code sources} holds one source per device, the main one among them, and at least one other; the others fill in the
     * order ring, watch, phone, each only where no device before it was worn. With the ring and the watch alone this is
     * exactly the path of phase 1a (the same doubles, JVM test).
     */
    public static void numbers(Day d, List<Source> sources, double from, double to) {
        Source main = null;
        List<Source> others = new ArrayList<Source>();
        boolean[] seen = new boolean[3];
        for (Source s : sources) {
            int p = priority(s.kind);
            if (seen[p]) throw new IllegalArgumentException("one source per device");
            seen[p] = true;
            if (s.kind == d.main) main = s;
            else others.add(s);
        }
        if (main == null || others.isEmpty()) throw new IllegalArgumentException("the main device and one other are needed");
        Collections.sort(others, new Comparator<Source>() {
            @Override
            public int compare(Source a, Source b) {
                return priority(a.kind) - priority(b.kind);
            }
        });
        int n = slots(from, to);
        Source first = others.get(0);
        d.otherKind = first.kind;
        Source present = null;
        for (Source o : others) {
            if (o.present) {
                present = o;
                break;
            }
        }
        // named only when the main device is there around this day (the watch: it has rows at all; a day before it was added)
        d.whole = !main.present && present != null && main.rows;
        if (d.whole) d.wholeFrom = present.kind;
        // the main device has nothing on this day: the first other one's own day as stored, not its estimate (the ring)
        Source base = !main.present && first.ownDay ? first : main;
        d.base = base.kind;
        d.steps = base.steps;
        d.metres = base.metres;
        d.baseParts.addAll(base.parts);
        d.baseWorn = base.worn.clone();
        boolean[] covered = base.worn.clone();
        for (Source f : others) {
            if (f == base) continue;
            boolean[] used = new boolean[n];
            double steps = 0, metres = 0;
            boolean got = false;
            for (double[] p : f.parts) {
                int k = (int) p[0];
                if (covered[k]) continue;
                steps += p[1];
                metres += p[2];
                used[k] = true;
                got = true;
            }
            if (f.estimate) {
                if (f.roundTo > 0) steps = Math.round(steps / f.roundTo) * f.roundTo;
                metres = Wellness.stepsToMeters(steps);
                got = steps > 0;
            }
            if (got) {
                Fill fill = new Fill(f.kind, steps);
                fill.ranges.addAll(stretches(not(covered), used, from, to));
                d.fills.add(fill);
                if (f == first) {
                    d.fillSteps = steps;
                    d.stepFill.addAll(fill.ranges);
                }
                d.steps = (Double.isNaN(d.steps) ? 0 : d.steps) + steps;
                d.metres = (Double.isNaN(d.metres) ? 0 : d.metres) + metres;
            }
            // where this device was worn counts as counted for the next one (a watch worn with 0 steps is not a gap)
            for (int k = 0; k < n; k++) covered[k] |= f.worn[k];
        }
        d.covered = covered;
        TS line = base.line();
        if (base != main) {
            // the other device's own day: its own line (phase 1a); only a device after it may still fill
            d.hrFrom = line.size() > 0 ? base.kind : -1;
        }
        boolean[] hrCovered = base.kind == PHONE ? new boolean[n] : base.worn.clone();
        for (Source o : others) {
            if (o == base) continue;
            TS add = o.heartIn(hrCovered);
            if (add.size() > 0) {
                line = joined(line, add);
                if (o == first || d.hrFrom < 0) d.hrFrom = o.kind;
                d.hrFill.addAll(stretches(not(hrCovered), slotsOf(add, from, n), from, to));
            }
            if (o.kind != PHONE) for (int k = 0; k < n; k++) hrCovered[k] |= o.worn[k];
        }
        d.hr = line;
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

    /**
     * The line under Steps, null when the main device counted them all. One sentence per device that filled (a ring's or the
     * phone's an estimate, so "about"; the phone's last); with only the ring and the watch exactly the line of phase 1a.
     */
    public static String stepsLine(Day d, ZoneId zone, Locale l) {
        StringBuilder b = new StringBuilder();
        String main = name(d.main);
        if (d.whole && !Double.isNaN(d.steps)) b.append("From ").append(name(d.wholeFrom >= 0 ? d.wholeFrom : d.other())).append(": ").append(main).append(" has no data for this day.");
        String gapOf = name(d.base >= 0 ? d.base : d.main);
        for (Fill f : d.fills) {
            // the whole day from one device is said above; only a device after it gets its own sentence
            if (d.whole && f.kind == d.wholeFrom) continue;
            if (b.length() > 0) b.append(' ');
            boolean about = f.kind == PHONE || f.kind == RING;
            b.append("Includes ").append(about ? "about " : "").append(String.format(l, "%,d", Math.round(f.steps))).append(Math.round(f.steps) == 1 ? " step" : " steps")
                    .append(" from ").append(name(f.kind)).append(", ").append(clockRanges(f.ranges, zone)).append(", when ").append(gapOf).append(" was not worn.");
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** The line under the heart rate of the day, null when the main device measured it all. */
    public static String heartLine(Day d, ZoneId zone) {
        if (d.main == PHONE) {
            // the phone measures no heart rate: the line is all another device's
            return d.hr.size() > 0 && d.hrFrom >= 0 ? "From " + name(d.hrFrom) + ", your phone measures no heart rate." : null;
        }
        String other = name(d.other()), main = name(d.main);
        if (d.whole && d.hr.size() > 0) return "From " + other + ": " + main + " has no data for this day.";
        if (d.hrFill.isEmpty()) return null;
        return "From " + other + " " + clockRanges(d.hrFill, zone) + ", when " + main + " was not worn.";
    }

    /** The line under the night, null when it is the main device's. */
    public static String nightLine(Day d) {
        if (!d.nightFromOther()) return null;
        int from = d.main == PHONE ? (d.watchNight != null ? WATCH : RING) : d.other();
        return "From " + name(from) + ": " + name(d.main) + " has no night for this day.";
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
        boolean phone = false, ring = false, watch = false;
        for (Fill f : d.fills) {
            // a fill of 0 steps (a watch record without steps) is not named here, as in phase 1a
            if (d.whole && f.kind == d.wholeFrom || !(f.steps > 0)) continue;
            phone |= f.kind == PHONE;
            ring |= f.kind == RING;
            watch |= f.kind == WATCH;
        }
        if (d.whole) {
            int from = d.wholeFrom >= 0 ? d.wholeFrom : d.other();
            String head = from == RING ? null : "Counted by " + name(from);
            if (!phone || from == PHONE) return head;
            return (head == null ? "Estimated from your movement" : head) + ", some counted by your phone";
        }
        if (d.main == PHONE) {
            String some = ring && watch ? ", some estimated by your ring and some counted by your watch" : ring ? ", some estimated by your ring"
                    : watch ? ", some counted by your watch" : "";
            return "Counted by your phone" + some;
        }
        if (d.main == WATCH) {
            String some = ring && phone ? ", some estimated by your ring and some counted by your phone" : ring ? ", some estimated by your ring"
                    : phone ? ", some counted by your phone" : "";
            return "Counted by your watch" + some;
        }
        if (watch && phone) return "Estimated from your movement, some counted by your watch and phone";
        if (watch) return "Estimated from your movement, some counted by your watch";
        return phone ? "Estimated from your movement, some counted by your phone" : null;
    }
}
