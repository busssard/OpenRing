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
import java.util.Random;

/**
 * Steps counted by the phone itself (issue #329, phase 1b). Android's step counter ({@code Sensor.TYPE_STEP_COUNTER})
 * gives the steps since the phone started; the app reads it about every hour and stores each reading raw: the boot count
 * ({@code Settings.Global.BOOT_COUNT}), the time since the start ({@code elapsedRealtime}), the counter and the wall clock.
 * The steps of each interval between two readings are worked out from them here, plain Java so the JVM tests cover every
 * case.
 *
 * <ul>
 * <li>A restart is a changed boot count, never a falling counter alone (a counter that is already above the old one after
 * a restart would look like steps). Intervals are ordered and measured by (boot count, elapsed); the wall clock only dates
 * them: an interval ends at its second reading's wall time and is exactly as long as the elapsed time between the two, so a
 * clock set back or forward moves the interval with the clock but never stretches, shrinks or drops it.</li>
 * <li>The very first reading of a phone is a baseline: it gives no interval (the counter may hold weeks of steps from before
 * Open Ring read anything). The first reading after a restart counts the steps since the start, from the start's time (wall
 * minus elapsed), only when the restart was seen: a reading of the same phone from an earlier boot exists, older than this
 * boot's start, and both boots have real boot counts ({@link #sinceRestart}).</li>
 * <li>Each phone's readings are its own ({@code device}, a random id made once on the phone): a restored backup from another
 * phone, or a phone reset to factory settings, can repeat boot counts and elapsed times; intervals are worked out per device and
 * boot, never across two phones.</li>
 * <li>A counter that falls without a new boot count, or that rose faster than anyone walks ({@link #MAX_PER_S}), gives no
 * difference: the interval is left out and counted for the Data check, nothing is guessed.</li>
 * <li>Steps of one interval are spread evenly over it when a day or a quarter hour is split ({@link #share}).</li>
 * </ul>
 */
public final class PhoneSteps {
    private PhoneSteps() {}

    /** The rule version of {@link #intervals}; a change works the stored intervals out again from the readings. */
    public static final int RULE = 1;
    /** More steps per second than this between two readings is not walking (a fast run is about 3.3): left out. */
    public static final double MAX_PER_S = 4;
    /** How long a reading waits for the sensor's first value before it gives up (the design: at most 5 s). */
    public static final long WAIT_MS = 5000;
    /** About every hour, Android picks the moment within the last 15 minutes of each period (its own job id). */
    public static final long INTERVAL_MS = 60 * 60 * 1000L, FLEX_MS = 15 * 60 * 1000L;
    /** A reading when the app opens is skipped when the last one is younger than this (a turned phone, a quick look back). */
    public static final long OPEN_SKIP_S = 60;
    /** A gap between two readings longer than this is counted for the Data check (Doze, a battery saver). */
    public static final double GAP_S = 2 * 3600;

    /** The device of readings made without one (tests). */
    public static final String THIS = "this";
    /** The device of the demo's made up readings. */
    public static final String DEMO = "demo";

    /** One raw reading, as stored. */
    public static final class Reading {
        /** The phone it was read on: a random id made once on that phone, never taken from a backup. */
        public final String device;
        /** The phone's boot count; a negative value is a stand in made from the start time (see {@link #bootKey}). */
        public final long boot;
        /** Milliseconds since the phone started ({@code SystemClock.elapsedRealtime}). */
        public final long elapsed;
        /** The step counter: steps since the phone started. */
        public final long counter;
        /** The wall clock, unix seconds. */
        public final double wall;

        public Reading(long boot, long elapsed, long counter, double wall) {
            this(THIS, boot, elapsed, counter, wall);
        }

        public Reading(String device, long boot, long elapsed, long counter, double wall) {
            this.device = device == null ? THIS : device;
            this.boot = boot;
            this.elapsed = elapsed;
            this.counter = counter;
            this.wall = wall;
        }
    }

    /** The steps between two readings (or since the start), worked out from the readings. */
    public static final class Interval {
        /** Its time on the wall clock of its end reading, unix seconds; {@code stop - start} is the elapsed time. */
        public final double start, stop;
        public final long steps;
        /** The key of its end reading. */
        public final long boot, elapsed;
        public final String device;

        public Interval(double start, double stop, long steps, long boot, long elapsed) {
            this(start, stop, steps, THIS, boot, elapsed);
        }

        public Interval(double start, double stop, long steps, String device, long boot, long elapsed) {
            this.device = device == null ? THIS : device;
            this.start = start;
            this.stop = stop;
            this.steps = steps;
            this.boot = boot;
            this.elapsed = elapsed;
        }
    }

    /**
     * The boot key of a reading: Android's boot count when it can be read ({@code count >= 0}, Android 7 and later), else a
     * stand in from the start time rounded to 10 minutes (negative, so it never meets a real count). The stand in moves
     * with a clock change: then one interval is lost, never steps made up.
     */
    public static long bootKey(int count, double wall, long elapsedMs) {
        if (count >= 0) return count;
        return -1 - Math.round((wall - elapsedMs / 1000.0) / 600.0);
    }

    /** Readings in the order the intervals are measured: by device, boot key, then elapsed time. */
    public static List<Reading> sorted(List<Reading> readings) {
        List<Reading> out = new ArrayList<Reading>(readings);
        Collections.sort(out, new Comparator<Reading>() {
            @Override
            public int compare(Reading a, Reading b) {
                int d = a.device.compareTo(b.device);
                if (d != 0) return d;
                return a.boot != b.boot ? Long.compare(a.boot, b.boot) : Long.compare(a.elapsed, b.elapsed);
            }
        });
        return out;
    }

    /** How far the start of a boot may lie before the last reading of the boot before it (clock drift), seconds. */
    static final double START_SLACK_S = 600;

    /**
     * Whether {@code first}, the first reading of its boot, counts its steps since the start: a restart was seen ({@code prev}
     * is the newest reading of the same phone's boot before, and it is older than this boot's start), and both boot keys are
     * real boot counts (a stand in moves with a clock correction, which would count steps twice).
     */
    static boolean sinceRestart(Reading prev, Reading first) {
        if (prev == null || !prev.device.equals(first.device) || prev.boot == first.boot) return false;
        if (prev.boot < 0 || first.boot < 0) return false;
        return prev.wall <= first.wall - first.elapsed / 1000.0 + START_SLACK_S;
    }

    /** {@link #sinceRestart} for the JVM tests (the tests live outside the package). */
    public static boolean sinceRestartForTest(Reading prev, Reading first) {
        return sinceRestart(prev, first);
    }

    /** What {@link #intervals} left out, for the Data check. */
    public static final class Left {
        /** The counter fell without a new boot count. */
        public int fell;
        /** More steps than anyone walks in the time ({@link #MAX_PER_S}). */
        public int implausible;
    }

    /** The intervals of all readings (any order). */
    public static List<Interval> intervals(List<Reading> readings) {
        return intervals(readings, null);
    }

    /** As {@link #intervals(List)}, counting what was left out into {@code left} (may be null). */
    public static List<Interval> intervals(List<Reading> readings, Left left) {
        List<Interval> out = new ArrayList<Interval>();
        Reading prev = null;
        for (Reading r : sorted(readings)) {
            if (prev == null || !prev.device.equals(r.device) || prev.boot != r.boot) {
                // the first reading of a boot: a baseline, or the steps since the start after a restart that was seen
                if (sinceRestart(prev, r)) {
                    Interval i = since(r, left);
                    if (i != null) out.add(i);
                }
            } else {
                Interval i = between(prev, r, left);
                if (i != null) out.add(i);
            }
            prev = r;
        }
        return out;
    }

    /** The steps from the start of the phone to its first reading, or null when the counter does not fit that time. */
    static Interval since(Reading r, Left left) {
        double dt = r.elapsed / 1000.0;
        if (dt <= 0 || r.counter < 0) return null;
        if (r.counter > MAX_PER_S * dt) {
            if (left != null) left.implausible++;
            return null;
        }
        return new Interval(r.wall - dt, r.wall, r.counter, r.device, r.boot, r.elapsed);
    }

    /** The steps between two readings of the same boot, or null (no time between them, a falling or impossible counter). */
    static Interval between(Reading a, Reading b, Left left) {
        double dt = (b.elapsed - a.elapsed) / 1000.0;
        if (dt <= 0) return null;
        long diff = b.counter - a.counter;
        if (diff < 0) {
            if (left != null) left.fell++;
            return null;
        }
        if (diff > MAX_PER_S * dt) {
            if (left != null) left.implausible++;
            return null;
        }
        return new Interval(b.wall - dt, b.wall, diff, b.device, b.boot, b.elapsed);
    }

    // ------------------------------------------------------------------------------------- spreading

    /** The steps of {@code i} that fall from {@code a} to {@code b}, spread evenly over the interval. */
    public static double share(Interval i, double a, double b) {
        return share(i.start, i.stop, i.steps, a, b);
    }

    /** As {@link #share(Interval, double, double)} for an interval given by its parts. */
    public static double share(double start, double stop, double steps, double a, double b) {
        if (stop <= start) return start >= a && start < b ? steps : 0;
        double lo = Math.max(a, start), hi = Math.min(b, stop);
        if (hi <= lo) return 0;
        return steps * (hi - lo) / (stop - start);
    }

    /** The steps of all intervals from {@code a} to {@code b}, each spread evenly; NaN when no interval reaches into it. */
    public static double total(List<Interval> all, double a, double b) {
        double sum = 0;
        boolean any = false;
        for (Interval i : all) {
            if (i.stop <= a || i.start >= b) continue;
            any = true;
            sum += share(i, a, b);
        }
        return any ? sum : Double.NaN;
    }

    /** The interval lies wholly inside {@code a} to {@code b}: its steps are counted there, not spread. */
    public static boolean inside(Interval i, double a, double b) {
        return i.start >= a && i.stop <= b;
    }

    /**
     * The days' totals from {@code first} to {@code last} (each day from its midnight to the next in {@code zone}, so 23 or
     * 25 hours on a clock change day), NaN for a day no interval reaches. For the Data check and the tests.
     */
    public static double[] days(List<Interval> all, LocalDate first, LocalDate last, ZoneId zone) {
        int n = (int) (last.toEpochDay() - first.toEpochDay()) + 1;
        double[] out = new double[Math.max(0, n)];
        for (int k = 0; k < out.length; k++) {
            LocalDate d = first.plusDays(k);
            out[k] = total(all, d.atStartOfDay(zone).toEpochSecond(), d.plusDays(1).atStartOfDay(zone).toEpochSecond());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- the Data check

    /** Counts of the readings for the Data check: numbers only, no step value. */
    public static final class Counts {
        public int readings, boots, intervals, gaps, fell, implausible;
        /** Phones the readings come from (more than one after restoring another phone's backup). */
        public int devices;
        /** The longest time between two readings of one boot, seconds (0 for none). */
        public double longest;
    }

    public static Counts counts(List<Reading> readings) {
        Counts c = new Counts();
        Left left = new Left();
        c.intervals = intervals(readings, left).size();
        c.fell = left.fell;
        c.implausible = left.implausible;
        Reading prev = null;
        for (Reading r : sorted(readings)) {
            c.readings++;
            if (prev == null || !prev.device.equals(r.device)) c.devices++;
            if (prev == null || !prev.device.equals(r.device) || prev.boot != r.boot) {
                c.boots++;
            } else {
                double gap = (r.elapsed - prev.elapsed) / 1000.0;
                if (gap > GAP_S) c.gaps++;
                c.longest = Math.max(c.longest, gap);
            }
            prev = r;
        }
        return c;
    }

    /**
     * The Data check's lines for the phone's steps (issue #329): counts, dates and the day totals of the last days (numbers
     * only, to hold next to the phone's own health app), never a clock time. {@code noReading}: readings where the sensor
     * sent no value in time, {@code noReadingAt} the last one (unix seconds, 0 none).
     */
    public static String checkLines(List<Reading> readings, List<Interval> all, int noReading, double noReadingAt, LocalDate today, int lastDays, ZoneId zone) {
        StringBuilder b = new StringBuilder();
        if (readings.isEmpty()) {
            b.append("Phone steps: no readings stored");
        } else {
            Counts c = counts(readings);
            double first = Double.NaN, last = Double.NaN;
            for (Reading r : readings) {
                if (Double.isNaN(first) || r.wall < first) first = r.wall;
                if (Double.isNaN(last) || r.wall > last) last = r.wall;
            }
            b.append("Phone steps: ").append(String.format(Locale.US, "%,d", c.readings)).append(c.readings == 1 ? " reading" : " readings")
                    .append(" from ").append(date(first, zone)).append(" to ").append(date(last, zone)).append(", ")
                    .append(c.devices > 1 ? "from " + c.devices + " phones, " : "")
                    .append(Texts.count(c.boots, "phone start", "phone starts")).append(", ")
                    .append(Texts.count(c.intervals, "interval", "intervals")).append('\n');
            long hours = Math.round(c.longest / 3600);
            b.append("  Gaps over 2 hours between readings: ").append(c.gaps).append(", longest about ").append(Texts.count(hours, "hour", "hours"))
                    .append(", left out (counter fell ").append(c.fell).append(", too fast ").append(c.implausible).append(')');
        }
        if (noReading > 0) {
            b.append("\n  No sensor value in time: ").append(Texts.count(noReading, "time", "times")).append(", the last on ").append(date(noReadingAt, zone));
        }
        b.append('\n');
        if (!all.isEmpty() && lastDays > 0) {
            b.append("  Steps per day (to compare with the phone's own health app):");
            LocalDate first = today.minusDays(lastDays - 1);
            double[] d = days(all, first, today, zone);
            for (int k = 0; k < d.length; k++) {
                if (Double.isNaN(d[k])) continue;
                b.append("\n    ").append(first.plusDays(k)).append(": ").append(String.format(Locale.US, "%,d", Math.round(d[k])));
            }
            b.append('\n');
        }
        return b.toString();
    }

    static String date(double unix, ZoneId zone) {
        if (Double.isNaN(unix) || unix <= 0) return "--";
        return Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone).toLocalDate().toString();
    }

    // ------------------------------------------------------------------------------------- texts

    /** The heading on Activity and in Settings. */
    public static final String TITLE = "Steps from this phone";
    public static final String ABOUT = "Counted by the phone's step sensor about every hour. Steps while the phone lies on a table are not counted.";
    /** The one button of the card that asks for Physical activity. */
    public static final String ASK = "Count steps with this phone";
    public static final String DECLINED = "Steps from this phone are off, Physical activity was not allowed.";
    public static final String ALLOW = "Allow";
    public static final String NO_SENSOR = "This phone has no step counter.";
    /** Allowed, but nothing read yet. */
    public static final String WAITING = "Counting has started. The first steps show after the next reading, within about an hour.";
    /** The main device line for the phone (Settings). */
    public static final String MAIN_LINE = "Phone (steps only)";
    /** The day total's line when phone steps are part of it. */
    public static final String SPREAD = "Phone steps are spread evenly over the time between two readings.";
    /** Sleep and Ready without a ring and without a watch. */
    public static final String NEED_DEVICE = "Sleep and readiness need a ring or a watch. Add one in Settings.";

    /** Every text above, for the tests (plain, no dashes as punctuation). */
    public static String[] texts() {
        return new String[] {TITLE, ABOUT, ASK, DECLINED, ALLOW, NO_SENSOR, WAITING, MAIN_LINE, SPREAD, NEED_DEVICE};
    }

    /** "14:02" in {@code zone}. */
    static String clock(double unix, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond(Math.round(unix)).atZone(zone);
        return String.format(Locale.US, "%02d:%02d", z.getHour(), z.getMinute());
    }

    /**
     * The touch readout of one interval on the day from {@code a} to {@code b}: "14:02 to 15:04, 412 steps from your phone"
     * when the whole interval lies in the day, else "about 206 steps (spread over 23:10 to 01:20)" with the day's share.
     */
    public static String tip(Interval i, double a, double b, ZoneId zone, Locale l) {
        String span = clock(i.start, zone) + " to " + clock(i.stop, zone);
        if (inside(i, a, b)) return span + ", " + String.format(l, "%,d", i.steps) + (i.steps == 1 ? " step" : " steps") + " from your phone";
        long part = Math.round(share(i, a, b));
        return "about " + String.format(l, "%,d", part) + (part == 1 ? " step" : " steps") + " (spread over " + span + ")";
    }

    // ------------------------------------------------------------------------------------- demo

    /**
     * Made up readings for demo mode (and tests): about every hour from {@code days} days before {@code today} until
     * {@code now}, longer gaps at night (Doze), a phone that is carried by day (some 6,000 to 10,000 steps), one restart
     * {@code 12} days ago at 03:00 (the counter starts again from 0). Readings are as the sensor would give them.
     */
    public static List<Reading> demo(LocalDate today, int days, long now, ZoneId zone, Random r) {
        List<Reading> out = new ArrayList<Reading>();
        double start = today.minusDays(days).atStartOfDay(zone).toEpochSecond();
        double restart = today.minusDays(12).atStartOfDay(zone).toEpochSecond() + 3 * 3600;
        long boot = 41;
        double bootWall = start - 6 * 3600 - r.nextInt(3600);
        double counter = 0;
        double t = start + 5 * 60;
        double lastStep = bootWall;
        boolean restarted = false;
        while (t <= now) {
            if (!restarted && t >= restart) {
                restarted = true;
                // the phone ran out of battery at 01:40 and started again at 03:00: the steps after the last reading are lost
                boot++;
                bootWall = restart;
                counter = 0;
                lastStep = restart;
                t = restart + 4 * 60;
            }
            counter += walked(lastStep, t, zone, r);
            lastStep = t;
            out.add(new Reading(DEMO, boot, Math.round((t - bootWall) * 1000), Math.round(counter), t));
            int h = Instant.ofEpochSecond((long) t).atZone(zone).getHour();
            // by night the phone lies still and Android lets the job wait longer
            double next = h >= 23 || h < 6 ? 3 * 3600 + r.nextInt(5400) : 3600 + r.nextInt(900) - 300;
            t += next;
        }
        return out;
    }

    /** Steps walked from {@code a} to {@code b} by the made up person, minute by minute (more at the usual walking times). */
    static double walked(double a, double b, ZoneId zone, Random r) {
        double sum = 0;
        for (double m = Math.ceil(a / 60) * 60; m < b; m += 60) {
            ZonedDateTime z = Instant.ofEpochSecond((long) m).atZone(zone);
            double hour = z.getHour() + z.getMinute() / 60.0;
            double rate;
            if (hour < 7 || hour >= 22.5) rate = 0;
            else if (hour >= 7.75 && hour < 8.25 || hour >= 12.25 && hour < 12.75 || hour >= 17.75 && hour < 18.5) rate = 50 + r.nextInt(30);
            else rate = r.nextInt(100) < 6 ? 20 + r.nextInt(40) : 0;
            sum += rate;
        }
        return sum;
    }
}
