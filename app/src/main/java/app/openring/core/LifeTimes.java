package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.TS;

/**
 * When and how long (issue #435): a life factor that happens at a moment (Sauna, Meditation, Hard workout, Intimacy, Friends,
 * Alcohol, Caffeine late, own tags) can carry a start time on its day ({@code life_days.time}, "HH:MM", the local clock time of
 * that day) and a length ({@code life_days.minutes}, null for none). Both are optional: a tap still logs only the day. This
 * class holds what needs no Android: which tags take a time, the lengths offered per tag, the start the sheet suggests, the
 * band a tagged time is on a chart (across midnight and clock changes), the heart rate measured during it and every sentence.
 * <p>
 * Nothing here says what a factor did: the band and the line under it show only what the sensors measured while the person
 * said it happened. Display only: nothing is stored from it and no score changes.
 */
public final class LifeTimes {
    private LifeTimes() {}

    // ------------------------------------------------------------------------------------------------ texts

    public static final String TITLE = "When and how long?", FROM = "From", HOW_LONG = "How long", SAVE = "Save", WITHOUT = "Without a time";
    /** All factors, under its chips. */
    public static final String HOLD_HINT = "Hold a chip to add when and how long. That shows it on the day's charts.";
    /** The sheet, for Alcohol and Caffeine late (a moment, no length). */
    public static final String TIME_ONLY = "For this factor only the time is kept.";
    /** Under the band, when the ring had no value during the logged time. */
    public static final String NOT_WORN = "Not worn during";

    // ------------------------------------------------------------------------------------------------ which tags, which lengths

    /** The fixed tags that happen at a moment (own tags too, {@link #timed}). Stress, Holiday, Illness and Travel are days. */
    private static final List<String> TIMED = Collections.unmodifiableList(Arrays.asList(LifeTags.MEDITATION, LifeTags.ALCOHOL, LifeTags.CAFFEINE,
            LifeTags.WORKOUT, LifeTags.SAUNA, LifeTags.FRIENDS, LifeTags.INTIMACY));

    /** Whether {@code key} can take a time: the fixed tags of a moment and every own tag. */
    public static boolean timed(String key) {
        return TIMED.contains(key) || LifeTags.isOwn(key);
    }

    /** The lengths the sheet offers for {@code key}, in minutes; empty for a tag of a moment only (Alcohol, Caffeine late) or a day. */
    public static int[] lengths(String key) {
        if (LifeTags.SAUNA.equals(key)) return new int[] {15, 30, 45, 60};
        if (LifeTags.MEDITATION.equals(key)) return new int[] {10, 20, 30};
        if (LifeTags.WORKOUT.equals(key)) return new int[] {30, 60, 90};
        if (LifeTags.INTIMACY.equals(key)) return new int[] {15, 30, 60};
        if (LifeTags.FRIENDS.equals(key)) return new int[] {60, 120, 180, 300};
        if (LifeTags.isOwn(key)) return new int[] {15, 30, 60, 120};
        return new int[0];
    }

    /** The usual length of {@code key} (the one the sheet picks first), 0 for a tag without a length. */
    public static int usual(String key) {
        if (LifeTags.SAUNA.equals(key)) return 30;
        if (LifeTags.MEDITATION.equals(key)) return 20;
        if (LifeTags.WORKOUT.equals(key)) return 60;
        if (LifeTags.INTIMACY.equals(key)) return 30;
        if (LifeTags.FRIENDS.equals(key)) return 120;
        if (LifeTags.isOwn(key)) return 30;
        return 0;
    }

    /** "15 min", "1 h", "5 h", "90 min" (a length that is not whole hours stays in minutes). */
    public static String lengthLabel(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " h";
        return minutes + " min";
    }

    /** The longest length that can be stored, a day (more is refused). */
    public static final int MAX_MINUTES = 24 * 60;

    /**
     * The start the sheet shows first, as minutes of the day: {@code now} minus the tag's usual length, down to 5 minutes, never
     * before midnight of the same day (a factor logged just after midnight starts at 00:00 and can be moved).
     */
    public static int startFor(String key, int nowMinute) {
        int m = Math.max(0, Math.min(MAX_MINUTES - 1, nowMinute) - usual(key));
        return m - m % 5;
    }

    /** The time on a stored row: "19:05" for 1145 minutes. */
    public static String clock(int minuteOfDay) {
        int m = Math.max(0, Math.min(MAX_MINUTES - 1, minuteOfDay));
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    /** The minutes of the day of a stored "HH:MM" (00:00 to 23:59), or -1 for none or anything else. */
    public static int parse(String hhmm) {
        if (hhmm == null || !hhmm.matches("[0-9]{2}:[0-9]{2}")) return -1;
        int h = Integer.parseInt(hhmm.substring(0, 2)), m = Integer.parseInt(hhmm.substring(3));
        return h > 23 || m > 59 ? -1 : h * 60 + m;
    }

    /** A length that can be stored: null (none) or 1 to {@link #MAX_MINUTES} minutes. */
    public static boolean validLength(Integer minutes) {
        return minutes == null || minutes >= 1 && minutes <= MAX_MINUTES;
    }

    /** The chip's second line on Today: "19:00, 45 min", "21:00" without a length, null without a time. */
    public static String chipLine(String time, Integer minutes) {
        if (parse(time) < 0) return null;
        return minutes == null ? time : time + ", " + lengthLabel(minutes);
    }

    /**
     * After Save in the sheet, beside Undo: "Sauna on 21 Nov: from 19:00 for 45 min.", "Alcohol on 21 Nov: at 20:30.", or
     * "Sauna on 21 Nov: without a time."
     */
    public static String savedLine(String name, LocalDate day, String time, Integer minutes, int thisYear, Locale loc) {
        String head = LifeTags.spanLine(name, day, day, thisYear, loc) + ": ";
        if (parse(time) < 0) return head + "without a time.";
        return head + (minutes == null ? "at " + time : "from " + time + " for " + lengthLabel(minutes)) + ".";
    }

    // ------------------------------------------------------------------------------------------------ a stored time

    /** One logged day of one tag with a time (a row of {@code life_days} in force whose time is set). */
    public static final class Entry {
        public final LocalDate day;
        public final String key, time;
        /** Null for none. */
        public final Integer minutes;

        public Entry(LocalDate day, String key, String time, Integer minutes) {
            this.day = day;
            this.key = key;
            this.time = time;
            this.minutes = minutes;
        }

        @Override
        public String toString() {
            return day + " " + key + " " + time + " " + minutes;
        }
    }

    /** The entry of {@code key} on {@code day} in {@code entries} (an alias read as its factor, {@code known}), or null. */
    public static Entry find(List<Entry> entries, LocalDate day, String key, LifeTags.Known known) {
        if (entries == null) return null;
        for (Entry e : entries) {
            if (!e.day.equals(day)) continue;
            String k = known == null ? e.key : known.key(e.key);
            if (k.equals(key)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the band on a chart

    /** A tagged time on a chart: from {@code start} to {@code stop} (unix seconds; equal for a time without a length). */
    public static final class Band {
        public final String key, name;
        public final LocalDate day;
        public final double start, stop;
        public final boolean hasLength;

        Band(String key, String name, LocalDate day, double start, double stop, boolean hasLength) {
            this.key = key;
            this.name = name;
            this.day = day;
            this.start = start;
            this.stop = stop;
            this.hasLength = hasLength;
        }

        /** Whether {@code t} lies in the band, or within {@code near} seconds of a time without a length. */
        public boolean holds(double t, double near) {
            return hasLength ? t >= start && t <= stop : Math.abs(t - start) <= near;
        }

        @Override
        public String toString() {
            return name + " " + (long) start + " " + (long) stop;
        }
    }

    /**
     * The band of a time logged on {@code day}: the clock time {@code time} on that day in {@code zone}, for {@code minutes}
     * real minutes (so 90 minutes from 23:30 end at 01:00 the next day, and across a clock change night still 90 minutes). A
     * clock time the clocks skip (02:30 when they go forward) is read as the same moment after the skip, as the clock shows
     * it; one that happens twice (02:30 when they go back) as the first time. Null for a time that is not one.
     */
    public static Band band(LocalDate day, String key, String name, String time, Integer minutes, ZoneId zone) {
        int m = parse(time);
        if (day == null || m < 0 || !validLength(minutes)) return null;
        ZonedDateTime z = ZonedDateTime.of(day, LocalTime.of(m / 60, m % 60), zone == null ? ZoneId.systemDefault() : zone);
        double start = z.toEpochSecond();
        return new Band(key, name, day, start, minutes == null ? start : start + minutes * 60.0, minutes != null);
    }

    /**
     * The bands of {@code entries} that reach into {@code from} to {@code to} (unix seconds), oldest start first, named as
     * {@code known} names them (an alias as its factor). A time of {@code day} is read in {@code zone} (the zone of the day the
     * chart shows; a time logged on a trip is drawn at that clock time there).
     */
    public static List<Band> bands(List<Entry> entries, double from, double to, ZoneId zone, LifeTags.Known known) {
        List<Band> out = new ArrayList<Band>();
        if (entries == null) return out;
        for (Entry e : entries) {
            String name = known == null ? nameOf(e.key) : known.name(e.key);
            Band b = band(e.day, known == null ? e.key : known.key(e.key), name, e.time, e.minutes, zone);
            if (b == null) continue;
            if (b.hasLength ? b.stop > from && b.start < to : b.start >= from && b.start <= to) out.add(b);
        }
        Collections.sort(out, new java.util.Comparator<Band>() {
            @Override
            public int compare(Band a, Band b) {
                return Double.compare(a.start, b.start);
            }
        });
        return out;
    }

    private static String nameOf(String key) {
        String n = LifeTags.builtInName(key);
        return n != null ? n : "Own factor";
    }

    /** "19:05" of {@code unix} in {@code zone}. */
    static String clockAt(double unix, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone == null ? ZoneId.systemDefault() : zone);
        return String.format(Locale.ROOT, "%02d:%02d", z.getHour(), z.getMinute());
    }

    /** What a touch on the band reads: "Sauna 19:00 to 19:45", "Alcohol at 21:00" without a length. */
    public static String tip(Band b, ZoneId zone) {
        if (!b.hasLength) return b.name + " at " + clockAt(b.start, zone);
        return b.name + " " + clockAt(b.start, zone) + " to " + clockAt(b.stop, zone);
    }

    /**
     * The start and end of {@code b} as clock hours of {@code day} in {@code zone} (0 to 24, for the bars of a day by the hour),
     * cut at the day's midnights; null when the band does not touch the day. A time without a length gives a start and end
     * the same.
     */
    public static double[] hours(Band b, LocalDate day, ZoneId zone) {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        double ds = day.atStartOfDay(z).toEpochSecond(), de = day.plusDays(1).atStartOfDay(z).toEpochSecond();
        if (b.hasLength ? !(b.stop > ds && b.start < de) : !(b.start >= ds && b.start < de)) return null;
        double a = b.start <= ds ? 0 : clockHours(b.start, z), e = !b.hasLength ? a : b.stop >= de ? 24 : clockHours(b.stop, z);
        return new double[] {a, Math.max(a, e)};
    }

    private static double clockHours(double unix, ZoneId z) {
        ZonedDateTime t = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(z);
        return t.getHour() + t.getMinute() / 60.0 + t.getSecond() / 3600.0;
    }

    // ------------------------------------------------------------------------------------------------ measured during

    /**
     * The average of the points of {@code line} measured during {@code b}: each point stands for {@code slot} seconds around
     * its time (the day's heart rate line: a quarter hour, its point in the middle), and counts when that reaches into the band
     * (a time without a length: the point whose slot holds it). NaN when none does (the ring was not worn then, or was off).
     */
    public static double during(TS line, Band b, double slot) {
        if (line == null || b == null) return Double.NaN;
        double half = Math.max(0, slot) / 2, sum = 0;
        int n = 0;
        for (int i = 0; i < line.size(); i++) {
            double t = line.t(i), v = line.v(i);
            if (Double.isNaN(v)) continue;
            boolean in = b.hasLength ? t - half < b.stop && t + half > b.start : b.start >= t - half && b.start < t + half;
            if (in) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * The line under the day's heart rate chart for one band: "Sauna 19:00 to 19:45. Heart rate during: 104 on average (your day:
     * 72)." or "Sauna 19:00 to 19:45. Not worn during." when the ring has no value then. {@code day} is the day's average (the
     * chart's Average); without it the bracket is left out. Measured values only, never a cause.
     */
    public static String duringLine(Band b, double during, double day, ZoneId zone) {
        String head = tip(b, zone) + ". ";
        if (Double.isNaN(during)) return head + NOT_WORN + ".";
        return head + "Heart rate during: " + Math.round(during) + " on average" + (Double.isNaN(day) ? "." : " (your day: " + Math.round(day) + ").");
    }

    // ------------------------------------------------------------------------------------------------ demo

    /**
     * The demo's made up times (only ever written into the demo file, with {@link LifeTags#demo}'s days): yesterday a sauna at
     * 19:00 for 45 minutes, Alcohol at 20:30 and Meditation at 21:00 for 20 minutes, friends 3 days ago from 18:30 for 3 hours,
     * a sauna 8 days ago at 18:30 for 30 minutes. Each one is on a day {@link LifeTags#demo} gives that tag.
     */
    public static List<Entry> demo(LocalDate today) {
        List<Entry> out = new ArrayList<Entry>();
        out.add(new Entry(today.minusDays(8), LifeTags.SAUNA, "18:30", 30));
        out.add(new Entry(today.minusDays(3), LifeTags.FRIENDS, "18:30", 180));
        out.add(new Entry(today.minusDays(1), LifeTags.SAUNA, "19:00", 45));
        out.add(new Entry(today.minusDays(1), LifeTags.ALCOHOL, "20:30", null));
        out.add(new Entry(today.minusDays(1), LifeTags.MEDITATION, "21:00", 20));
        return out;
    }
}
