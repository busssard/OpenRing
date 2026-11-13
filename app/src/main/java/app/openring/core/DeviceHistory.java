package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import app.openring.core.analysis.DayHeart;
import app.openring.core.analysis.Night;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.Wellness;
import app.openring.core.withings.Records;
import app.openring.core.withings.WatchDays;

/**
 * The days of the watch, or of the phone, over time: what the 7 and 30 day charts, the recent nights strip, sleep debt and
 * Further analysis show for a person without a ring (owner's report of 13 November: with only the watch the statistics at
 * the bottom of each tab were missing). Each day's scores are {@link DeviceScores#score}, the very function the dials use,
 * so a day's point in a chart is its dial's value. Nothing here is stored: the screens work it out on each load from the
 * stored rows. The ring's charts do not pass through here.
 */
public final class DeviceHistory {
    /** Days in the 30 day charts, and in the 7 day charts. */
    public static final int MONTH = 30, WEEK = 7;
    /** Nights after the shown day that the recent nights strip also holds (as the ring's, issue #98). */
    public static final int AFTER = 30;
    /**
     * Days before the shown day with scores: the recent nights strip starts this many days before it (as the ring's), so its
     * oldest night has its score too (code review of #389: it showed "--" while its dial had one). The 30 day charts read the
     * last {@link #MONTH} of them.
     */
    public static final int BEFORE = 30;
    /** Nights before the first day the history holds: sleep debt on any day reads 14 days and 90 nights before each (the ring's rule). */
    public static final int DEBT_BACK = 13 + 90;

    /** The zone of each day: the one its dial uses ({@code db.zoneAt} at the day's noon), so a day's point is its dial after a trip too. */
    public interface DayZones {
        ZoneId of(LocalDate day);
    }

    /** One zone for every day. */
    public static DayZones fixed(final ZoneId zone) {
        return new DayZones() {
            @Override
            public ZoneId of(LocalDate day) {
                return zone;
            }
        };
    }

    /**
     * The days the day load works out for the shown {@code date}: from {@link #BEFORE} days before it (the strip's oldest night) to
     * {@link #AFTER} after it, at most {@code today} (but never before the date itself). {first, last}.
     */
    public static LocalDate[] range(LocalDate date, LocalDate today) {
        LocalDate last = date.plusDays(AFTER);
        if (last.isAfter(today)) last = today.isBefore(date) ? date : today;
        return new LocalDate[] {date.minusDays(BEFORE), last};
    }

    /**
     * The device whose history the tabs show: the watch, else the phone, and only without a ring ({@code -1} with a ring, whose
     * own charts stay as they are, main device rule #319, or with no device at all).
     */
    public static int device(boolean ring, boolean watch, boolean phone) {
        if (ring) return -1;
        return DeviceScores.device(false, watch, phone);
    }

    /** One watch night with what the charts and Further analysis show of it. */
    public static final class NightRow {
        public final LocalDate day;
        public final WatchDays.Night night;
        /** The night as the ring's night fields ({@link DeviceScores#night}): sleep start and end, latency, wake ups, efficiency. */
        public final Night fields;
        /** Its sleep score ({@link DeviceScores#sleepScore}), NaN for none. */
        public double score = Double.NaN;
        /** The watch's lowest heart rate reading in it and when, -1 for none; the average of its readings. */
        public int lowest = -1;
        public long lowestAt;
        public double average = Double.NaN;
        /** The zone of its day (the clock its times show in). */
        public final ZoneId zone;

        NightRow(LocalDate day, WatchDays.Night night, boolean rem, ZoneId zone) {
            this.day = day;
            this.night = night;
            this.fields = DeviceScores.night(night, rem);
            this.zone = zone;
        }

        void heart(WatchDays.NightHeart nh) {
            lowest = nh.lowest;
            lowestAt = nh.lowestAt;
            average = nh.average;
        }
    }

    public final int device;
    /** The first and last day with values (the strip's and the charts' days). */
    public final LocalDate first, last;
    /** The zone of the last day; each day's own is {@link #zone(LocalDate)}. */
    public final ZoneId zone;
    private final DayZones zones;
    /** The step goal the scores and the step chart use. */
    public final int goal;
    /** Which nights tell REM from light sleep ({@link WatchDays.Rem}); the others show "Light and REM" as one stage. */
    public final WatchDays.Rem rem;
    /** One value per day from {@link #first} to {@link #last}, NaN where there is none. */
    public final double[] steps, kcal, workoutMin, activity, sleep, readiness, asleepMin, rhr, dayHr;
    /** Workouts started on each day. */
    public final int[] workouts;
    /** The scores of each day, as {@link DeviceScores#of} gives them. */
    private final DeviceScores.Result[] results;
    /** Every night from {@link #DEBT_BACK} days before {@link #first} to {@link #last}, null where none. */
    private final NightRow[] nights;
    private final LocalDate nightsFirst;

    private DeviceHistory(int device, LocalDate first, LocalDate last, DayZones zones, int goal, WatchDays.Rem rem) {
        this.rem = rem;
        this.device = device;
        this.first = first;
        this.last = last;
        this.zones = zones;
        this.zone = zones.of(last);
        this.goal = goal > 0 ? goal : 10000;
        int n = (int) ChronoUnit.DAYS.between(first, last) + 1;
        steps = nan(n);
        kcal = nan(n);
        workoutMin = nan(n);
        activity = nan(n);
        sleep = nan(n);
        readiness = nan(n);
        asleepMin = nan(n);
        rhr = nan(n);
        dayHr = nan(n);
        workouts = new int[n];
        results = new DeviceScores.Result[n];
        nightsFirst = first.minusDays(DEBT_BACK);
        nights = new NightRow[n + DEBT_BACK];
    }

    /** The zone of {@code day}, as its dial has it. */
    public ZoneId zone(LocalDate day) {
        return zones.of(day);
    }

    private static double[] nan(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    /** Days from {@link #first} to {@link #last}. */
    public int size() {
        return steps.length;
    }

    /** The index of {@code day} in the series, -1 outside. */
    public int index(LocalDate day) {
        if (day == null || day.isBefore(first) || day.isAfter(last)) return -1;
        return (int) ChronoUnit.DAYS.between(first, day);
    }

    /** The scores of {@code day}, null outside. */
    public DeviceScores.Result result(LocalDate day) {
        int i = index(day);
        return i < 0 ? null : results[i];
    }

    /** The {@code k} values of {@code series} for the days ending at {@code end}, oldest first, NaN outside. */
    public double[] last(double[] series, LocalDate end, int k) {
        double[] out = nan(k);
        for (int j = 0; j < k; j++) {
            int i = index(end.minusDays(k - 1 - j));
            if (i >= 0) out[j] = series[i];
        }
        return out;
    }

    /** The night that ends on {@code day}, null for none (also for the weeks before {@link #first}, which the scores read). */
    public NightRow night(LocalDate day) {
        if (day.isBefore(nightsFirst) || day.isAfter(last)) return null;
        return nights[(int) ChronoUnit.DAYS.between(nightsFirst, day)];
    }

    /** The recent nights strip of the shown {@code date}: its nights from {@link #BEFORE} days before it to {@link #last}, each with its score. */
    public List<NightRow> strip(LocalDate date) {
        return nights(date.minusDays(BEFORE), last);
    }

    /** The nights from {@code a} to {@code b}, oldest first. */
    public List<NightRow> nights(LocalDate a, LocalDate b) {
        List<NightRow> out = new ArrayList<NightRow>();
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            NightRow r = night(d);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** The usual resting heart rate before {@code day}: the average of the 14 nights before it, NaN with fewer than 3 (as the ring's). */
    public double usualRhr(LocalDate day) {
        double[] prior = new double[14];
        for (int k = 0; k < 14; k++) {
            NightRow r = night(day.minusDays(14 - k));
            prior[k] = r == null || r.lowest <= 0 ? Double.NaN : r.lowest;
        }
        return Stats.countFinite(prior) >= 3 ? Stats.mean(prior) : Double.NaN;
    }

    /**
     * Sleep debt on {@code day} by the ring's rule ({@code Analyzer}: {@link Wellness#sleepDebt} over the last 14 days, each against
     * the need from the nights before it), on the watch's nights: {debt in minutes, 1 when valid (5 nights) else 0, need in hours}.
     */
    public double[] debt(LocalDate day) {
        int[] actual = new int[14], need = new int[14];
        for (int i = 0; i < 14; i++) {
            LocalDate di = day.minusDays(i);
            NightRow r = night(di);
            actual[i] = r == null ? 0 : r.night.asleepMin() * 60;
            double[] prior = new double[90];
            for (int k = 0; k < 90; k++) {
                NightRow p = night(di.minusDays(k + 1));
                prior[k] = p == null ? Double.NaN : p.night.asleepMin() * 60.0;
            }
            need[i] = Wellness.sleepNeedS(prior);
        }
        double[] d = Wellness.sleepDebt(actual, need);
        return new double[] {d[0] / 60, d[1], need[0] / 3600.0};
    }

    /** Cardio capacity from the age and the watch's lowest reading of the night ending on {@code day} (the ring's Uth method), NaN for none. */
    public double vo2max(LocalDate day, double age) {
        NightRow r = night(day);
        return r == null || r.lowest <= 0 ? Double.NaN : Wellness.vo2maxUth(age, r.lowest);
    }

    /** Clock minutes after midnight of a moment (NaN stays NaN). */
    public static double clockMin(double unix, ZoneId zone) {
        if (Double.isNaN(unix)) return Double.NaN;
        ZonedDateTime z = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone);
        return z.getHour() * 60 + z.getMinute() + z.getSecond() / 60.0;
    }

    /**
     * The days from {@code first} to {@code last} of {@code device} ({@link MainDevice#WATCH} or {@link MainDevice#PHONE}) with
     * its rows ({@code watch} or {@code phone}, null for none) from {@link DeviceScores#DAYS} days before {@code first} on (as
     * {@link #rowsFrom} says; the watch's sleep pieces from {@link #sleepFrom} on, for sleep debt), the days in {@code zone}, the
     * step goal and the age. The scores of each day are exactly {@link DeviceScores#of} on the rows up to that day's end, as the
     * day load reads them for the dials.
     */
    public static DeviceHistory of(int device, LocalDate first, LocalDate last, ZoneId zone, MainDevice.Watch watch, MainDevice.Phone phone,
            int goal, double age) {
        return of(device, first, last, fixed(zone), watch, phone, goal, age, WatchDays.Rem.ALWAYS);
    }

    /** As above; {@code rem}: one answer for every night ({@link WatchDays.Rem#of(boolean)}). */
    public static DeviceHistory of(int device, LocalDate first, LocalDate last, ZoneId zone, MainDevice.Watch watch, MainDevice.Phone phone,
            int goal, double age, boolean rem) {
        return of(device, first, last, fixed(zone), watch, phone, goal, age, WatchDays.Rem.of(rem));
    }

    /** The days and the nights of one zone: every day of the history as a dial in that zone reads it. */
    private static final class InZone {
        final ZoneId zone;
        final double[] from, to;
        final DeviceScores.Day[] days;
        final WatchDays.Night[] nights;
        double[] lowest;

        InZone(ZoneId zone, int all) {
            this.zone = zone;
            from = new double[all];
            to = new double[all];
            days = new DeviceScores.Day[all];
            nights = new WatchDays.Night[all];
        }
    }

    /**
     * As above, each day in its own zone ({@code zones}, as its dial: code review of #389, after a trip the days in another zone
     * kept the shown day's borders) and {@code rem}, which nights tell REM ({@link WatchDays.Rem}, per night).
     */
    public static DeviceHistory of(int device, LocalDate first, LocalDate last, DayZones zones, MainDevice.Watch watch, MainDevice.Phone phone,
            int goal, double age, WatchDays.Rem rem) {
        final DeviceHistory h = new DeviceHistory(device, first, last, zones, goal, rem);
        if (watch == null) watch = new MainDevice.Watch();
        if (phone == null) phone = new MainDevice.Phone();
        final Sorted rows = new Sorted(watch);
        final boolean isWatch = device == MainDevice.WATCH;
        final int all = h.nights.length, off = DeviceScores.DAYS - 1, back = DEBT_BACK;
        final WatchDays.HeartIndex heart = new WatchDays.HeartIndex(watch.heart);
        final List<Records.Sleep> sleep = watch.sleep;
        final MainDevice.Watch w = watch;
        final MainDevice.Phone p = phone;
        ZoneId[] dayZone = new ZoneId[all];
        for (int i = 0; i < all; i++) dayZone[i] = zones.of(h.nightsFirst.plusDays(i));
        // the nights of each zone (cheap), and the days of each zone a scored day has (worked out once per zone, usually one)
        final HashMap<ZoneId, TreeMap<LocalDate, WatchDays.Night>> nightMaps = new HashMap<ZoneId, TreeMap<LocalDate, WatchDays.Night>>();
        HashMap<ZoneId, InZone> inZone = new HashMap<ZoneId, InZone>();
        for (int i = 0; i < all; i++) {
            ZoneId z = dayZone[i];
            TreeMap<LocalDate, WatchDays.Night> map = nightMaps.get(z);
            if (map == null) {
                map = !isWatch || sleep.isEmpty() ? new TreeMap<LocalDate, WatchDays.Night>()
                        : WatchDays.nights(sleep, h.nightsFirst, last, DeviceScores.zones(z));
                nightMaps.put(z, map);
            }
            WatchDays.Night n = map.get(h.nightsFirst.plusDays(i));
            if (n == null) continue;
            NightRow r = new NightRow(h.nightsFirst.plusDays(i), n, rem.tells(n), z);
            r.heart(heart.in(n.start, n.end));
            h.nights[i] = r;
        }
        for (int k = 0; k < h.size(); k++) {
            int i = k + back;
            ZoneId zone = dayZone[i];
            InZone zd = inZone.get(zone);
            if (zd == null) {
                // every day the scores of this zone's days read, from 34 days before the first to the last
                zd = new InZone(zone, all);
                TreeMap<LocalDate, WatchDays.Night> map = nightMaps.get(zone);
                for (int j = back - off; j < all; j++) {
                    LocalDate dj = h.nightsFirst.plusDays(j);
                    zd.from[j] = dj.atStartOfDay(zone).toEpochSecond();
                    zd.to[j] = dj.plusDays(1).atStartOfDay(zone).toEpochSecond();
                    zd.days[j] = DeviceScores.day(device, zd.from[j], zd.to[j], zone, isWatch ? rows.near(zd.from[j], zd.to[j], Double.POSITIVE_INFINITY) : w, p);
                    zd.nights[j] = map.get(dj);
                }
                zd.lowest = isWatch ? DeviceScores.lowest(zd.nights, heart) : null;
                inZone.put(zone, zd);
            }
            DeviceScores.Day[] window = Arrays.copyOfRange(zd.days, i - off, i + 1);
            // the day itself as the day load reads it: its rows end at its midnight (a reading just after it does not count)
            if (isWatch) window[off] = DeviceScores.day(device, zd.from[i], zd.to[i], zone, rows.near(zd.from[i], zd.to[i], zd.to[i]), p);
            DeviceScores.Result r = DeviceScores.score(device, zone, window, isWatch ? Arrays.copyOfRange(zd.nights, i - off, i + 1) : null,
                    isWatch ? Arrays.copyOfRange(zd.lowest, i - off, i + 1) : null, goal, age, rem);
            h.results[k] = r;
            h.steps[k] = r.steps;
            h.activity[k] = r.value("activity_score");
            h.sleep[k] = r.value("sleep");
            h.readiness[k] = r.value("readiness");
            if (h.nights[i] != null) {
                h.nights[i].score = h.sleep[k];
                h.asleepMin[k] = h.nights[i].night.asleepMin();
                h.rhr[k] = h.nights[i].lowest > 0 ? h.nights[i].lowest : Double.NaN;
            }
            if (!isWatch) continue;
            double from = zd.from[i], to = zd.to[i];
            double kc = 0;
            boolean any = false;
            MainDevice.Watch near = rows.near(from, to, Double.POSITIVE_INFINITY);
            for (Records.Steps s : near.steps) {
                if (s.start < from || s.start >= to) continue;
                kc += s.kcal;
                any = true;
            }
            if (any) h.kcal[k] = kc;
            double min = 0;
            for (Records.Workout x : near.workouts) {
                if (x.start < from || x.start >= to) continue;
                h.workouts[k]++;
                min += Math.max(0, x.dur) / 60.0;
            }
            // a day the watch was worn has its workout minutes, also 0; a day without it has none
            if (zd.days[i].wornQ > 0 || h.workouts[k] > 0) h.workoutMin[k] = min;
            DayHeart dh = DayHeart.of(MainDevice.watch(near, from, to).line(), from, to);
            h.dayHr[k] = dh.avg;
        }
        return h;
    }

    /** The first moment the rows for {@link #of} must reach back to: the day load's rule for the dials (35 days, an hour and a day before). */
    public static double rowsFrom(LocalDate first, ZoneId zone) {
        return first.atStartOfDay(zone).toEpochSecond() - (DeviceScores.DAYS - 1) * 86400.0 - 3600 - 86400;
    }

    /** As above with each day in its own zone: the earliest moment any day from {@code first} to {@code last} reads back to. */
    public static double rowsFrom(LocalDate first, LocalDate last, DayZones zones) {
        double min = Double.POSITIVE_INFINITY;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) min = Math.min(min, rowsFrom(d, zones.of(d)));
        return min;
    }

    /**
     * The first moment the watch's sleep pieces for {@link #of} must reach back to: sleep debt reads {@link #DEBT_BACK} nights before
     * the first day (the evening before them, a day more for any zone).
     */
    public static double sleepFrom(LocalDate first, DayZones zones) {
        LocalDate d = first.minusDays(DEBT_BACK);
        return d.atStartOfDay(zones.of(d)).toEpochSecond() - 2 * 86400.0;
    }

    /** A day's look at the watch's rows reaches this far (seconds) before its start and after its end; it needs 30 minutes. */
    static final double NEAR_S = 86400;

    /**
     * The watch's rows sorted by start, so each day takes only those near it ({@link #near}): every rule of a day
     * ({@link DeviceScores#day}: the day's records, worn within 30 minutes of one, sleep pieces that reach into it) looks no
     * further than {@link #NEAR_S}, so the day comes out the same as with all rows, without going through all of them for
     * each of the some 90 days.
     */
    static final class Sorted {
        private final List<Records.Steps> steps;
        private final List<Records.Heart> heart;
        private final List<Records.Workout> workouts;
        private final List<Records.Sleep> sleep;
        private final long[] stepT, heartT, workT;

        Sorted(MainDevice.Watch w) {
            steps = new ArrayList<Records.Steps>(w.steps);
            heart = new ArrayList<Records.Heart>(w.heart);
            workouts = new ArrayList<Records.Workout>(w.workouts);
            sleep = w.sleep;
            java.util.Collections.sort(steps, new java.util.Comparator<Records.Steps>() {
                @Override
                public int compare(Records.Steps a, Records.Steps b) {
                    return Long.compare(a.start, b.start);
                }
            });
            java.util.Collections.sort(heart, new java.util.Comparator<Records.Heart>() {
                @Override
                public int compare(Records.Heart a, Records.Heart b) {
                    return Long.compare(a.t, b.t);
                }
            });
            java.util.Collections.sort(workouts, new java.util.Comparator<Records.Workout>() {
                @Override
                public int compare(Records.Workout a, Records.Workout b) {
                    return Long.compare(a.start, b.start);
                }
            });
            stepT = new long[steps.size()];
            for (int i = 0; i < stepT.length; i++) stepT[i] = steps.get(i).start;
            heartT = new long[heart.size()];
            for (int i = 0; i < heartT.length; i++) heartT[i] = heart.get(i).t;
            workT = new long[workouts.size()];
            for (int i = 0; i < workT.length; i++) workT[i] = workouts.get(i).start;
        }

        /** First index with a time at or after {@code t}. */
        private static int lower(long[] a, double t) {
            int lo = 0, hi = a.length;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (a[m] < t) lo = m + 1;
                else hi = m;
            }
            return lo;
        }

        /**
         * The rows from {@link #NEAR_S} before {@code from} to {@link #NEAR_S} after {@code to}, and only those that start before
         * {@code cut} ({@code POSITIVE_INFINITY} for all): the day load reads a day's rows up to its own end, so a reading just
         * after midnight does not count for the day itself (only for the days after it).
         */
        MainDevice.Watch near(double from, double to, double cut) {
            MainDevice.Watch out = new MainDevice.Watch();
            double a = from - NEAR_S, b = Math.min(to + NEAR_S, cut);
            for (int i = lower(stepT, a); i < stepT.length && stepT[i] < b; i++) out.steps.add(steps.get(i));
            for (int i = lower(heartT, a); i < heartT.length && heartT[i] < b; i++) out.heart.add(heart.get(i));
            for (int i = lower(workT, a); i < workT.length && workT[i] < b; i++) out.workouts.add(workouts.get(i));
            for (Records.Sleep s : sleep) if (s.start < b && s.start + Math.max(0, s.dur) > a) out.sleep.add(s);
            return out;
        }
    }

    /**
     * Every watch night from {@code first} to {@code last} with its score and heart rate, for Further analysis over a week,
     * month, year or all time ({@code sleep} and {@code heart} rows from the evening before {@code first} on).
     */
    public static List<NightRow> nightRows(List<Records.Sleep> sleep, List<Records.Heart> heart, LocalDate first, LocalDate last, ZoneId zone, double age,
            boolean rem) {
        return nightRows(sleep, heart, first, last, fixed(zone), age, WatchDays.Rem.of(rem));
    }

    /**
     * As above, each night in the zone of its day ({@code zones}, as its dial and the charts) and with REM where it tells it
     * ({@code rem}). The heart rates are sorted once ({@link WatchDays.HeartIndex}): two years of nights no longer go through
     * every heart rate for each night (code review of #389).
     */
    public static List<NightRow> nightRows(List<Records.Sleep> sleep, List<Records.Heart> heart, LocalDate first, LocalDate last, DayZones zones,
            double age, WatchDays.Rem rem) {
        List<NightRow> out = new ArrayList<NightRow>();
        if (sleep.isEmpty()) return out;
        // All time starts in 2000: the days before the first piece have no night, so their zones are not asked for
        long earliest = Long.MAX_VALUE;
        for (Records.Sleep s : sleep) earliest = Math.min(earliest, s.start);
        LocalDate from = Instant.ofEpochSecond(earliest).atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(1);
        if (from.isBefore(first)) from = first;
        WatchDays.HeartIndex hi = new WatchDays.HeartIndex(heart);
        HashMap<ZoneId, TreeMap<LocalDate, WatchDays.Night>> maps = new HashMap<ZoneId, TreeMap<LocalDate, WatchDays.Night>>();
        for (LocalDate d = from; !d.isAfter(last); d = d.plusDays(1)) {
            ZoneId z = zones.of(d);
            TreeMap<LocalDate, WatchDays.Night> map = maps.get(z);
            if (map == null) maps.put(z, map = WatchDays.nights(sleep, from, last, DeviceScores.zones(z)));
            WatchDays.Night n = map.get(d);
            if (n == null) continue;
            boolean tells = rem.tells(n);
            NightRow r = new NightRow(d, n, tells, z);
            Night.Score s = DeviceScores.sleepScore(n, z, age, tells);
            if (s != null) r.score = s.value;
            r.heart(hi.in(n.start, n.end));
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ texts

    /** Under the charts with only the watch. */
    public static final String FROM_WATCH = "From your watch";
    /** Under the charts with only the phone. */
    public static final String FROM_PHONE = "From this phone's steps";

    /** The tip of a day in a 7 or 30 day chart: "Tue 4.11.  8,412 steps". */
    public static String tip(LocalDate day, String value, Locale l) {
        return day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, l) + " " + day.getDayOfMonth() + "." + day.getMonthValue() + ".  " + value;
    }

    /** "8,412 steps", "--" for none. */
    public static String stepsText(double v, Locale l) {
        return Double.isNaN(v) ? "--" : String.format(l, "%,d steps", Math.round(v));
    }

    /** "23:12 to 06:58, 7h 21m asleep" for a night's sleep times, "No night" without one. */
    public static String timesText(NightRow r, ZoneId zone) {
        if (r == null || Double.isNaN(r.fields.sleepStart)) return "No night from the watch";
        return app.openring.core.analysis.WakeUps.clock(r.fields.sleepStart, zone) + " to " + app.openring.core.analysis.WakeUps.clock(r.fields.sleepEnd, zone)
                + ", " + app.openring.core.analysis.Scores.hm(r.night.asleepMin()) + " asleep";
    }

    /** The average and the days with a value of a series: "Average 8,412 a day over 27 days" style parts. */
    public static double mean(double[] v) {
        return Stats.countFinite(v) == 0 ? Double.NaN : Stats.mean(v);
    }
}
