package app.openring.core.withings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        /** Per minute: 1 deep, 2 light, 3 REM, 0 a code not known yet or a gap (as the app's stage strings). */
        public int[] stages;
        public int deepMin, lightMin, remMin, otherMin;

        public int asleepMin() {
            return deepMin + lightMin + remMin;
        }
    }

    /** The app's stage digit of a watch sleep code. */
    public static int stage(int code) {
        switch (code) {
            case Records.DEEP: return 1;
            case Records.LIGHT: return 2;
            case Records.REM: return 3;
            default: return 0;
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
        for (Records.Sleep p : pieces) {
            int m = (int) Math.round(Math.max(0, p.dur) / 60.0);
            int st = stage(p.stage);
            if (st == 1) n.deepMin += m;
            else if (st == 2) n.lightMin += m;
            else if (st == 3) n.remMin += m;
            else n.otherMin += m;
        }
        return n;
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

    /** Map helper for the page: the value of a day, NaN when missing. */
    public static double of(Map<LocalDate, Integer> m, LocalDate d) {
        Integer v = m.get(d);
        return v == null ? Double.NaN : v;
    }
}
