package app.openring.core.withings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What the Watch page shows, worked out from the decoded rows (issue #251, phase 1b). Nothing here is stored: the
 * screens work it out on each load, so it always matches the stored rows. A row belongs to the calendar day of its
 * start in the zone the phone was in then ({@link Zones}, from the {@code zones} table), so a trip does not move
 * earlier days.
 */
public final class WatchDays {
    private WatchDays() {}

    /** The zone at a time (the {@code zones} table; the phone's zone where it has none). */
    public interface Zones {
        ZoneId at(double unix);
    }

    /**
     * The zone id at {@code unix} from the rows of the {@code zones} table ({start, stop, id}, ordered by start), by the
     * rule of {@code Db.zoneAt}: the first row that has not stopped before it (so between two rows the later one), after the
     * newest row the newest; null without rows.
     */
    public static String zoneIn(List<Object[]> rows, double unix) {
        if (rows == null || rows.isEmpty()) return null;
        for (Object[] r : rows) if (((Number) r[1]).doubleValue() >= unix) return (String) r[2];
        return (String) rows.get(rows.size() - 1)[2];
    }

    /** {@link Zones} from the rows of the {@code zones} table, the phone's zone {@code fallback} where they say nothing. */
    public static Zones zones(final List<Object[]> rows, final ZoneId fallback) {
        return new Zones() {
            @Override
            public ZoneId at(double unix) {
                String id = zoneIn(rows, unix);
                try {
                    return id == null ? fallback : ZoneId.of(id);
                } catch (RuntimeException e) {
                    return fallback;
                }
            }
        };
    }

    public static LocalDate day(long t, Zones z) {
        return Instant.ofEpochSecond(t).atZone(z.at(t)).toLocalDate();
    }

    /** The start of {@code day} in the zone of its noon, unix seconds. */
    public static long dayStart(LocalDate day, Zones z) {
        ZoneId zone = z.at(day.atTime(12, 0).atZone(ZoneId.of("UTC")).toEpochSecond());
        return day.atStartOfDay(zone).toEpochSecond();
    }

    /** Steps per day (only steps records; workouts are listed on their own, see {@link #workoutsOn}). */
    public static TreeMap<LocalDate, Integer> stepsPerDay(List<Records.Steps> rows, Zones z) {
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        for (Records.Steps s : rows) {
            LocalDate d = day(s.start, z);
            Integer v = out.get(d);
            out.put(d, (v == null ? 0 : v) + s.steps);
        }
        return out;
    }

    /** Distance per day in metres, from the same records as {@link #stepsPerDay}. */
    public static TreeMap<LocalDate, Integer> metresPerDay(List<Records.Steps> rows, Zones z) {
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        for (Records.Steps s : rows) {
            LocalDate d = day(s.start, z);
            Integer v = out.get(d);
            out.put(d, (v == null ? 0 : v) + s.metres);
        }
        return out;
    }

    /** Calories of movement per day, from the same records as {@link #stepsPerDay} (workouts are listed on their own). */
    public static TreeMap<LocalDate, Integer> kcalPerDay(List<Records.Steps> rows, Zones z) {
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        for (Records.Steps s : rows) {
            LocalDate d = day(s.start, z);
            Integer v = out.get(d);
            out.put(d, (v == null ? 0 : v) + s.kcal);
        }
        return out;
    }

    /** Metres climbed per day, from the records' climb ({@link Records.Moves}); days without any are missing. */
    public static TreeMap<LocalDate, Integer> climbPerDay(List<Records.Moves> rows, Zones z) {
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        for (Records.Moves m : rows) {
            if (m.up <= 0) continue;
            LocalDate d = day(m.start, z);
            Integer v = out.get(d);
            out.put(d, (v == null ? 0 : v) + m.up);
        }
        return out;
    }

    /** The workouts that start on {@code day}, oldest first. */
    public static List<Records.Workout> workoutsOn(List<Records.Workout> rows, LocalDate day, Zones z) {
        List<Records.Workout> out = new ArrayList<Records.Workout>();
        for (Records.Workout w : rows) if (day(w.start, z).equals(day)) out.add(w);
        Collections.sort(out, new Comparator<Records.Workout>() {
            @Override
            public int compare(Records.Workout a, Records.Workout b) {
                return Long.compare(a.start, b.start);
            }
        });
        return out;
    }

    /** Heart rates of {@code day} as {time, bpm}, oldest first. */
    public static List<long[]> heartOn(List<Records.Heart> rows, LocalDate day, Zones z) {
        List<long[]> out = new ArrayList<long[]>();
        for (Records.Heart h : rows) if (day(h.t, z).equals(day)) out.add(new long[] {h.t, h.bpm});
        Collections.sort(out, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        return out;
    }

    /** Withings' own sleep of a night: its start and end and one stage per minute in the app's digits. */
    public static final class Night {
        public long start, end;
        /** Per minute: 4 awake, 3 REM, 2 light, 1 deep, 0 no record (a gap between pieces, or a code not known yet). */
        public int[] stages;
        /** Minutes of each, counted from {@link #stages}, so they add up to the whole night as the chart shows it. */
        public int deepMin, lightMin, remMin, awakeMin, otherMin;

        public int asleepMin() {
            return deepMin + lightMin + remMin;
        }

        /** The whole night, from the first piece to the end of the last: every minute of {@link #stages}. */
        public int inBedMin() {
            return deepMin + lightMin + remMin + awakeMin + otherMin;
        }
    }

    /** The app's stage digit of a watch sleep code (the digits of the ring's stage strings, 4 awake). */
    public static int stage(int code) {
        switch (code) {
            case Records.AWAKE: return 4;
            case Records.DEEP: return 1;
            case Records.LIGHT: return 2;
            case Records.REM: return 3;
            default: return 0;
        }
    }

    /**
     * Whether the watch tells REM sleep from light sleep (owner's report of 13 November: the Steel HR sends awake, light and deep
     * only). The rule: true as soon as any of its stored sleep records is REM. A watch that never sent one shows "Light and REM"
     * as one stage, without a REM row, REM warnings or a REM part in the sleep score; a later watch that does send REM is
     * shown with it from its first such record on, nothing to switch.
     */
    public static boolean tellsRem(List<Records.Sleep> rows) {
        for (Records.Sleep s : rows) if (s.stage == Records.REM) return true;
        return false;
    }

    /**
     * Which nights tell REM from light sleep, decided per night (code review of #389: one REM record anywhere used to switch REM
     * on for the whole history, so every old Steel HR night got a REM part of 0). Each watch that ever sent a REM record tells
     * REM from its first REM record to the end of its last sleep piece; a night tells REM when it reaches into such a span.
     * So the nights of a Steel HR keep "Light and REM" whether it was worn before or after a watch with REM, and a watch
     * with REM is shown with it from its first such record on.
     */
    public static final class Rem {
        /** Every night tells REM (the ring's rule, and a person without any watch sleep row). */
        public static final Rem ALWAYS = new Rem(new long[][] {{Long.MIN_VALUE, Long.MAX_VALUE}});
        /** No night tells REM. */
        public static final Rem NEVER = new Rem(new long[0][]);
        private final long[][] spans;

        /** From spans {from, to} (unix seconds), one per watch that sent REM. */
        public Rem(long[][] spans) {
            this.spans = spans.clone();
        }

        /** From each watch's own sleep rows: a span from its first REM record to the end of its last piece, none without REM. */
        @SafeVarargs
        public static Rem of(List<Records.Sleep>... devices) {
            List<long[]> out = new ArrayList<long[]>();
            for (List<Records.Sleep> rows : devices) {
                long first = Long.MAX_VALUE, end = Long.MIN_VALUE;
                for (Records.Sleep s : rows) {
                    if (s.stage == Records.REM) first = Math.min(first, s.start);
                    end = Math.max(end, s.start + Math.max(0, s.dur));
                }
                if (first != Long.MAX_VALUE) out.add(new long[] {first, Math.max(first + 1, end)});
            }
            return new Rem(out.toArray(new long[0][]));
        }

        /** {@link #ALWAYS} for true, {@link #NEVER} for false (one answer for every night, as before the review). */
        public static Rem of(boolean rem) {
            return rem ? ALWAYS : NEVER;
        }

        /** The night tells REM from light sleep: it reaches into the span of a watch that sent REM. */
        public boolean tells(Night n) {
            return n != null && tells(n.start, n.end);
        }

        /** A night from {@code start} to {@code end} (unix seconds) tells REM. */
        public boolean tells(long start, long end) {
            for (long[] s : spans) if (start < s[1] && end > s[0]) return true;
            return false;
        }

        /** Any watch sent REM. */
        public boolean any() {
            return spans.length > 0;
        }
    }

    /** Gaps up to this long between sleep pieces join them into one night (seconds). */
    public static final long JOIN_S = 2 * 3600;

    /**
     * The night that ends on {@code day}: the sleep pieces joined across gaps of up to {@link #JOIN_S}, the stretch whose
     * end falls on that day (in its zone) between 00:00 and 18:00; of several, the longest. Null when there is none.
     */
    public static Night nightFor(List<Records.Sleep> rows, LocalDate day, Zones z) {
        List<Records.Sleep> s = new ArrayList<Records.Sleep>(rows);
        Collections.sort(s, new Comparator<Records.Sleep>() {
            @Override
            public int compare(Records.Sleep a, Records.Sleep b) {
                return Long.compare(a.start, b.start);
            }
        });
        Night best = null;
        int i = 0;
        while (i < s.size()) {
            int j = i;
            long end = s.get(i).start + Math.max(0, s.get(i).dur);
            while (j + 1 < s.size() && s.get(j + 1).start - end <= JOIN_S) {
                j++;
                end = Math.max(end, s.get(j).start + Math.max(0, s.get(j).dur));
            }
            long start = s.get(i).start;
            java.time.ZonedDateTime e = Instant.ofEpochSecond(end).atZone(z.at(end));
            if (e.toLocalDate().equals(day) && e.getHour() < 18 && end > start && (best == null || end - start > best.end - best.start)) {
                best = night(s.subList(i, j + 1), start, end);
            }
            i = j + 1;
        }
        return best;
    }

    /**
     * The nights of every day from {@code first} to {@code last}, each exactly what {@link #nightFor} gives for that day, but with
     * the rows sorted and joined once instead of once per day (a year of nights for Further analysis). Days without a night are
     * missing.
     */
    public static TreeMap<LocalDate, Night> nights(List<Records.Sleep> rows, LocalDate first, LocalDate last, Zones z) {
        List<Records.Sleep> s = new ArrayList<Records.Sleep>(rows);
        Collections.sort(s, new Comparator<Records.Sleep>() {
            @Override
            public int compare(Records.Sleep a, Records.Sleep b) {
                return Long.compare(a.start, b.start);
            }
        });
        TreeMap<LocalDate, int[]> best = new TreeMap<LocalDate, int[]>();
        TreeMap<LocalDate, long[]> span = new TreeMap<LocalDate, long[]>();
        int i = 0;
        while (i < s.size()) {
            int j = i;
            long end = s.get(i).start + Math.max(0, s.get(i).dur);
            while (j + 1 < s.size() && s.get(j + 1).start - end <= JOIN_S) {
                j++;
                end = Math.max(end, s.get(j).start + Math.max(0, s.get(j).dur));
            }
            long start = s.get(i).start;
            java.time.ZonedDateTime e = Instant.ofEpochSecond(end).atZone(z.at(end));
            LocalDate day = e.toLocalDate();
            long[] b = span.get(day);
            // the rule of nightFor: before 18:00, longer than none, and only a longer stretch replaces the first one found
            if (!day.isBefore(first) && !day.isAfter(last) && e.getHour() < 18 && end > start && (b == null || end - start > b[1] - b[0])) {
                span.put(day, new long[] {start, end});
                best.put(day, new int[] {i, j});
            }
            i = j + 1;
        }
        TreeMap<LocalDate, Night> out = new TreeMap<LocalDate, Night>();
        for (Map.Entry<LocalDate, int[]> e : best.entrySet()) {
            long[] b = span.get(e.getKey());
            out.put(e.getKey(), night(s.subList(e.getValue()[0], e.getValue()[1] + 1), b[0], b[1]));
        }
        return out;
    }

    private static Night night(List<Records.Sleep> pieces, long start, long end) {
        Night n = new Night();
        n.start = start;
        n.end = end;
        int mins = (int) Math.max(1, (end - start + 59) / 60);
        n.stages = new int[mins];
        for (Records.Sleep p : pieces) {
            int a = (int) ((p.start - start) / 60), b = (int) Math.min(mins, (p.start + Math.max(0, p.dur) - start + 59) / 60);
            for (int k = Math.max(0, a); k < b; k++) n.stages[k] = stage(p.stage);
        }
        for (int st : n.stages) {
            if (st == 1) n.deepMin++;
            else if (st == 2) n.lightMin++;
            else if (st == 3) n.remMin++;
            else if (st == 4) n.awakeMin++;
            else n.otherMin++;
        }
        return n;
    }

    /** The watch's heart rate during a night: its readings, the lowest (and when) and the average of the readings. */
    public static final class NightHeart {
        /** {time, bpm}, oldest first. */
        public final List<long[]> points = new ArrayList<long[]>();
        public int lowest = -1;
        public long lowestAt;
        public double average = Double.NaN;
    }

    /**
     * The heart rates from {@code start} to {@code end} (unix seconds, both included), oldest first, with the lowest and the
     * average of the readings. The watch measures only now and then, so these are its readings, nothing filled in between.
     */
    public static NightHeart heartIn(List<Records.Heart> rows, long start, long end) {
        return heartScan(rows, start, end);
    }

    /**
     * The heart rates sorted by time once, so each night takes its readings by a binary search instead of going through every
     * row (code review of #389: Further analysis over two years went through every heart rate for each of 730 nights). The
     * sort is stable, so readings at the same moment keep their order and {@link #in} gives what {@link #heartIn} gave.
     */
    public static final class HeartIndex {
        private final List<Records.Heart> rows;
        private final long[] t;

        public HeartIndex(List<Records.Heart> heart) {
            rows = new ArrayList<Records.Heart>(heart);
            Collections.sort(rows, new Comparator<Records.Heart>() {
                @Override
                public int compare(Records.Heart a, Records.Heart b) {
                    return Long.compare(a.t, b.t);
                }
            });
            t = new long[rows.size()];
            for (int i = 0; i < t.length; i++) t[i] = rows.get(i).t;
        }

        /** As {@link WatchDays#heartIn}: the readings from {@code start} to {@code end}, both included. */
        public NightHeart in(long start, long end) {
            NightHeart h = new NightHeart();
            int lo = 0, hi = t.length;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (t[m] < start) lo = m + 1;
                else hi = m;
            }
            long sum = 0;
            int n = 0;
            for (int i = lo; i < t.length && t[i] <= end; i++) {
                Records.Heart x = rows.get(i);
                h.points.add(new long[] {x.t, x.bpm});
                sum += x.bpm;
                n++;
                if (h.lowest < 0 || x.bpm < h.lowest) {
                    h.lowest = x.bpm;
                    h.lowestAt = x.t;
                }
            }
            if (n > 0) h.average = sum / (double) n;
            return h;
        }
    }

    /** One night's readings by going through every row: fine for a few nights, {@link HeartIndex} for many. */
    private static NightHeart heartScan(List<Records.Heart> rows, long start, long end) {
        NightHeart h = new NightHeart();
        List<Records.Heart> s = new ArrayList<Records.Heart>();
        for (Records.Heart x : rows) if (x.t >= start && x.t <= end) s.add(x);
        Collections.sort(s, new Comparator<Records.Heart>() {
            @Override
            public int compare(Records.Heart a, Records.Heart b) {
                return Long.compare(a.t, b.t);
            }
        });
        long sum = 0;
        for (Records.Heart x : s) {
            h.points.add(new long[] {x.t, x.bpm});
            sum += x.bpm;
            if (h.lowest < 0 || x.bpm < h.lowest) {
                h.lowest = x.bpm;
                h.lowestAt = x.t;
            }
        }
        if (!s.isEmpty()) h.average = sum / (double) s.size();
        return h;
    }

    /**
     * Steps per hour from {@code from} to {@code to} (a day's midnights, unix seconds; 23 or 25 hours on a clock change
     * day): each record counts in the hour it starts in, as the day total counts it on the day it starts, so the hours add
     * up to exactly that total.
     */
    public static double[] stepsPerHour(List<Records.Steps> rows, long from, long to) {
        int hours = (int) Math.max(1, (to - from + 3599) / 3600);
        double[] out = new double[hours];
        for (Records.Steps s : rows) {
            if (s.start < from || s.start >= to) continue;
            out[(int) Math.min(hours - 1, (s.start - from) / 3600)] += s.steps;
        }
        return out;
    }

    /** The last battery reading of each day. */
    public static TreeMap<LocalDate, Integer> batteryPerDay(List<Records.Battery> rows, Zones z) {
        List<Records.Battery> s = sorted(rows);
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        for (Records.Battery b : s) out.put(day(b.t, z), b.pct);
        return out;
    }

    /**
     * How much the battery dropped per day over the readings from {@code from} on (unix seconds): every fall between
     * two readings in a row added up (a rise is a charge and counts as nothing), divided by the days between the first
     * and the last reading. NaN with fewer than two readings or less than half a day between them.
     */
    public static double dropPerDay(List<Records.Battery> rows, long from) {
        List<Records.Battery> s = new ArrayList<Records.Battery>();
        for (Records.Battery b : sorted(rows)) if (b.t >= from) s.add(b);
        if (s.size() < 2) return Double.NaN;
        double days = (s.get(s.size() - 1).t - s.get(0).t) / 86400.0;
        if (days < 0.5) return Double.NaN;
        int fall = 0;
        for (int i = 1; i < s.size(); i++) fall += Math.max(0, s.get(i - 1).pct - s.get(i).pct);
        return fall / days;
    }

    private static List<Records.Battery> sorted(List<Records.Battery> rows) {
        List<Records.Battery> s = new ArrayList<Records.Battery>(rows);
        Collections.sort(s, new Comparator<Records.Battery>() {
            @Override
            public int compare(Records.Battery a, Records.Battery b) {
                return Long.compare(a.t, b.t);
            }
        });
        return s;
    }

    /** The newest battery reading, or null. */
    public static Records.Battery newestBattery(List<Records.Battery> rows) {
        List<Records.Battery> s = sorted(rows);
        return s.isEmpty() ? null : s.get(s.size() - 1);
    }

    /** "1 h 05 min" style text of seconds, for workouts. */
    public static String duration(int seconds) {
        int m = Math.max(0, Math.round(seconds / 60f));
        return m >= 60 ? (m / 60) + " h " + (m % 60 < 10 ? "0" : "") + (m % 60) + " min" : m + " min";
    }

    /** "3.2 km" or "850 m". */
    public static String distance(int metres) {
        if (metres >= 1000) return String.format(java.util.Locale.US, "%.1f km", metres / 1000.0);
        return metres + " m";
    }

    /** The line of one workout on the Watch page: its name, clock time, length, and what the watch counted. */
    public static String workoutLine(Records.Workout w, Zones z) {
        java.time.ZonedDateTime t = Instant.ofEpochSecond(w.start).atZone(z.at(w.start));
        StringBuilder b = new StringBuilder(Proto.workoutName(w.kind)).append(" at ")
                .append(String.format(java.util.Locale.US, "%02d:%02d", t.getHour(), t.getMinute())).append(", ").append(duration(w.dur));
        if (w.metres > 0) b.append(", ").append(distance(w.metres));
        if (w.steps > 0) b.append(", ").append(w.steps).append(w.steps == 1 ? " step" : " steps");
        if (w.kcal > 0) b.append(", ").append(w.kcal).append(" kcal");
        return b.toString();
    }

    /**
     * Everything the Watch page reads per day, worked out once from the stored rows (on the loading thread), so going to
     * another day only looks it up. Each answer is exactly what the function of the same name above gives for the same
     * rows and zones; it only does the work once instead of on every tap of "Day before" or "Next day", where each tap
     * went through the whole stored history about ten times on the main thread, with a zone lookup per row.
     */
    public static final class Index {
        /** Per day maps, read only: they are shared for as long as the page is open. */
        public final SortedMap<LocalDate, Integer> steps, metres, kcal, climb, battery;
        /** The newest battery reading, or null ({@link #newestBattery}). */
        public final Records.Battery newestBattery;
        /** Which nights tell REM from light sleep ({@link Rem}, per night). */
        public final Rem rem;
        private final TreeMap<LocalDate, List<Records.Heart>> heart = new TreeMap<LocalDate, List<Records.Heart>>();
        private final TreeMap<LocalDate, List<Records.Workout>> workouts = new TreeMap<LocalDate, List<Records.Workout>>();
        private final List<Records.Sleep> sleep;
        private final List<Records.Battery> batteryRows;
        private final List<Records.Steps> stepRows;
        private final HeartIndex heartIndex;
        private final Zones zones;

        public Index(List<Records.Steps> steps, List<Records.Moves> moves, List<Records.Heart> heart, List<Records.Workout> workouts,
                List<Records.Sleep> sleep, List<Records.Battery> battery, Zones z) {
            this(steps, moves, heart, workouts, sleep, battery, z, Rem.of(sleep));
        }

        /** As above, with the REM rule of every stored watch ({@code WatchStore.rem}, which knows the rows of each watch). */
        public Index(List<Records.Steps> steps, List<Records.Moves> moves, List<Records.Heart> heart, List<Records.Workout> workouts,
                List<Records.Sleep> sleep, List<Records.Battery> battery, Zones z, Rem rem) {
            this.zones = z;
            this.steps = Collections.unmodifiableSortedMap(stepsPerDay(steps, z));
            this.metres = Collections.unmodifiableSortedMap(metresPerDay(steps, z));
            this.kcal = Collections.unmodifiableSortedMap(kcalPerDay(steps, z));
            this.climb = Collections.unmodifiableSortedMap(climbPerDay(moves, z));
            this.battery = Collections.unmodifiableSortedMap(batteryPerDay(battery, z));
            this.newestBattery = newestBattery(battery);
            this.rem = rem;
            this.sleep = new ArrayList<Records.Sleep>(sleep);
            this.stepRows = new ArrayList<Records.Steps>(steps);
            this.heartIndex = new HeartIndex(heart);
            this.batteryRows = new ArrayList<Records.Battery>(battery);
            // the rows themselves (no copies), grouped in their order, then sorted per day by time like heartOn and
            // workoutsOn (a stable sort, so rows with the same time keep the same order)
            for (Records.Heart h : heart) {
                LocalDate d = day(h.t, z);
                List<Records.Heart> l = this.heart.get(d);
                if (l == null) this.heart.put(d, l = new ArrayList<Records.Heart>());
                l.add(h);
            }
            for (List<Records.Heart> l : this.heart.values()) {
                Collections.sort(l, new Comparator<Records.Heart>() {
                    @Override
                    public int compare(Records.Heart a, Records.Heart b) {
                        return Long.compare(a.t, b.t);
                    }
                });
            }
            for (Records.Workout w : workouts) {
                LocalDate d = day(w.start, z);
                List<Records.Workout> l = this.workouts.get(d);
                if (l == null) this.workouts.put(d, l = new ArrayList<Records.Workout>());
                l.add(w);
            }
            for (List<Records.Workout> l : this.workouts.values()) {
                Collections.sort(l, new Comparator<Records.Workout>() {
                    @Override
                    public int compare(Records.Workout a, Records.Workout b) {
                        return Long.compare(a.start, b.start);
                    }
                });
            }
        }

        /** As {@link WatchDays#heartOn}, a new list. */
        public List<long[]> heartOn(LocalDate day) {
            List<long[]> out = new ArrayList<long[]>();
            List<Records.Heart> l = heart.get(day);
            if (l != null) for (Records.Heart h : l) out.add(new long[] {h.t, h.bpm});
            return out;
        }

        /** As {@link WatchDays#workoutsOn}, a new list. */
        public List<Records.Workout> workoutsOn(LocalDate day) {
            List<Records.Workout> l = workouts.get(day);
            return l == null ? new ArrayList<Records.Workout>() : new ArrayList<Records.Workout>(l);
        }

        /** As {@link WatchDays#nightFor} (the sleep rows are few, so it is worked out per day). */
        public Night nightFor(LocalDate day) {
            return WatchDays.nightFor(sleep, day, zones);
        }

        /** As {@link WatchDays#stepsPerHour} for {@code day} (its midnights in its zone). */
        public double[] stepsPerHour(LocalDate day) {
            return WatchDays.stepsPerHour(stepRows, dayStart(day, zones), dayStart(day.plusDays(1), zones));
        }

        /** As {@link WatchDays#heartIn} for a night. */
        public NightHeart heartIn(Night n) {
            return heartIndex.in(n.start, n.end);
        }

        /** As {@link WatchDays#dropPerDay} on the battery rows. */
        public double dropPerDay(long from) {
            return WatchDays.dropPerDay(batteryRows, from);
        }
    }

    /** Map helper for the page: the value of a day, NaN when missing. */
    public static double of(Map<LocalDate, Integer> m, LocalDate d) {
        Integer v = m.get(d);
        return v == null ? Double.NaN : v;
    }
}
