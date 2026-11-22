package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.Night;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.Wellness;

/**
 * A night entered by hand (issue #427): the person slept without the ring and the watch and tells when they went to bed, when
 * they got up and, if they remember, when they woke up in between. Plain Java, so the JVM tests cover the bar's times (the snap,
 * midnight, the clock change nights), the wake marks, the default times, the night in the ring's night fields and its partial
 * score.
 *
 * <p>The night of a day is entered on a bar from 18:00 the evening before to 14:00 that day, in the day's zone (19, 20 or 21
 * hours on a clock change night). Every time snaps to {@link #SNAP_S} (5 minutes). A night belongs to the day whose bar holds
 * its start ({@link #dayOf}). Nothing is made up: there are no stages, no heart rate, no HRV and no temperature, so those stay
 * empty, and the sleep score uses only the parts that need no device (total sleep, timing, wake ups).
 */
public final class HandNight {
    private HandNight() {}

    /** The stage source of a night by hand, in its night fields ({@code source}). Never a ring's or a watch's. */
    public static final String SOURCE = "hand";
    /** Every time on the bar snaps to 5 minutes. */
    public static final long SNAP_S = 300;
    /** The bar starts at 18:00 the evening before and ends at 14:00 on the day. */
    public static final int BAR_FROM_H = 18, BAR_TO_H = 14;
    /** The shortest night: In bed to Up at least 30 minutes. */
    public static final long MIN_NIGHT_S = 1800;
    /** A new wake mark is 15 minutes long. */
    public static final long MARK_S = 900;
    /** The default times come from the median of this many shown nights before. */
    public static final int HISTORY = 14;
    /** Without history: In bed 23:00, Up 07:00 (minutes after 18:00). */
    public static final int DEFAULT_BED_MIN = 5 * 60, DEFAULT_UP_MIN = 13 * 60;
    /** The Sleep tab offers to enter a night for days up to this many days back. */
    public static final int BACK_DAYS = 30;
    /** Today asks only when a device synced in the last 2 days. */
    public static final long ASK_SYNC_S = 2 * 86400L;
    /** ... and after the night: the sync was today from 04:00 on. */
    public static final int ASK_FROM_H = 4;

    // ------------------------------------------------------------------------------------- texts (plain, no dashes)

    public static final String ENTER = "Enter the night by hand";
    public static final String IN_BED = "In bed", UP = "Up";
    public static final String WOKE = "I woke up in between";
    public static final String ASLEEP = "Asleep (by hand)";
    public static final String ENTERED = "Entered by hand";
    public static final String SCORE_LINE = "From a night by hand, without heart rate or stages";
    public static final String ASK = "No night for last night. Did you sleep without your ring and watch?";
    public static final String ASK_ENTER = "Enter by hand", NOT_NOW = "Not now";
    public static final String SAVE = "Save", CHANGE = "Change", DELETE = "Delete";
    /** The buttons of In bed, Up and each wake mark on the entry screen (the way without dragging). */
    public static final String EARLIER = "5 min earlier", LATER = "5 min later", LONGER = "5 min longer", SHORTER = "5 min shorter",
            REMOVE = "Remove";
    /** The recent nights strip: what a night by hand's column says to touch and screen readers. */
    public static final String STRIP = "Night by hand";
    public static final String NOT_MEASURED = "Not measured this night: efficiency, time to fall asleep, REM and deep sleep. A night by hand has no heart rate or stages.";
    public static final String MARK_HINT = "Drag a wake mark along the bar, or drag its ends to make it longer or shorter. Tap a mark to remove it.";
    public static final String BAR_HINT = "Drag In bed and Up, or use the buttons below. Touch the bar to read the time.";
    public static final String NO_ROOM = "No room for another wake mark. Make the night longer or a mark shorter.";
    public static final String DELETED = "Night by hand deleted.", SAVED = "Night by hand saved.", CHANGED = "Night by hand changed.";
    public static final String UNREADABLE = "Your nights by hand could not be read. Nothing was changed.";

    /** "Replaced by your ring's night" (or watch): a device's night is shown for the day, the one by hand stays stored. */
    public static String replaced(int device) {
        return "Replaced by " + MainDevice.name(device) + "'s night";
    }

    /** The longer line under {@link #replaced}. */
    public static String replacedNote(int device) {
        return "The night from " + MainDevice.name(device) + " is shown. The night you entered by hand stays stored until you delete it.";
    }

    /** The length in big letters: "7 h 40 min", "45 min", "8 h". */
    public static String length(double minutes) {
        if (Double.isNaN(minutes) || minutes < 0) return "--";
        long m = Math.round(minutes);
        if (m < 60) return m + " min";
        return m % 60 == 0 ? (m / 60) + " h" : (m / 60) + " h " + (m % 60) + " min";
    }

    /** "23:05" in {@code zone}. */
    public static String clock(double unix, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond(Math.round(unix)).atZone(zone);
        return String.format(Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }

    /** "Awake 03:10 to 03:25" for a wake mark. */
    public static String markText(double[] m, ZoneId zone) {
        return "Awake " + clock(m[0], zone) + " to " + clock(m[1], zone) + " (" + length((m[1] - m[0]) / 60) + ")";
    }

    /** The night's title on the entry screen: "The night before Wednesday 19 November". */
    public static String title(LocalDate day, Locale l) {
        return "The night before " + day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, l) + " " + day.getDayOfMonth() + " "
                + day.getMonth().getDisplayName(java.time.format.TextStyle.FULL, l);
    }

    // ------------------------------------------------------------------------------------- the bar

    /** The bar of {@code day}'s night: {start, end} in unix seconds, 18:00 the evening before to 14:00 on the day in {@code zone}. */
    public static double[] bar(LocalDate day, ZoneId zone) {
        double a = day.minusDays(1).atTime(BAR_FROM_H, 0).atZone(zone).toEpochSecond();
        double b = day.atTime(BAR_TO_H, 0).atZone(zone).toEpochSecond();
        return new double[] {a, b};
    }

    /** The day whose bar holds {@code start}: the date of {@code start} plus 6 hours (18:00 is the next day's midnight). */
    public static LocalDate dayOf(double start, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochSecond((long) Math.floor(start)), zone);
        return t.plusHours(24 - BAR_FROM_H).toLocalDate();
    }

    /** {@code t} snapped to the nearest 5 minutes (unix seconds; every zone's offset is a multiple of 5 minutes). */
    public static double snap(double t) {
        return Math.round(t / SNAP_S) * (double) SNAP_S;
    }

    /**
     * In bed and Up kept on the bar of {@code day}: snapped, inside the bar, Up at least {@link #MIN_NIGHT_S} after In bed.
     * {@code movedUp}: Up was moved last, so In bed gives way (else Up does).
     */
    public static double[] times(LocalDate day, ZoneId zone, double inBed, double up, boolean movedUp) {
        double[] b = bar(day, zone);
        double s = Math.max(b[0], Math.min(b[1] - MIN_NIGHT_S, snap(inBed)));
        double e = Math.max(b[0] + MIN_NIGHT_S, Math.min(b[1], snap(up)));
        if (e - s < MIN_NIGHT_S) {
            if (movedUp) s = e - MIN_NIGHT_S;
            else e = s + MIN_NIGHT_S;
        }
        return new double[] {s, e};
    }

    /**
     * As {@link #times}, with the night's wake marks ({@code marks}, kept inside as {@link #marks} keeps them): In bed stops 5
     * minutes before the first mark and Up 5 minutes after the last, so moving a handle never drops a wake up.
     */
    public static double[] times(LocalDate day, ZoneId zone, double inBed, double up, boolean movedUp, List<double[]> marks) {
        double[] t = times(day, zone, inBed, up, movedUp);
        if (marks == null || marks.isEmpty()) return t;
        double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        for (double[] m : marks) {
            first = Math.min(first, Math.min(m[0], m[1]));
            last = Math.max(last, Math.max(m[0], m[1]));
        }
        double s = Math.min(t[0], snap(first) - SNAP_S), e = Math.max(t[1], snap(last) + SNAP_S);
        if (e - s < MIN_NIGHT_S) {
            if (movedUp) s = e - MIN_NIGHT_S;
            else e = s + MIN_NIGHT_S;
        }
        return new double[] {s, e};
    }

    /**
     * The mark of {@code marks} (as {@link #marks} keeps them, in order) a touch at {@code t} takes: one that holds {@code t}
     * within {@code reachS} seconds on each side, the nearest when there are more; -1 for none. A mark is taken before the
     * handles, so a wake up near In bed or Up can still be moved.
     */
    public static int markAt(List<double[]> marks, double t, double reachS) {
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < marks.size(); i++) {
            double[] m = marks.get(i);
            if (t < m[0] - reachS || t > m[1] + reachS) continue;
            double d = t >= m[0] && t <= m[1] ? 0 : Math.min(Math.abs(t - m[0]), Math.abs(t - m[1]));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Which part of mark {@code m} a touch at {@code t} holds, by thirds: -1 its start, 1 its end, 0 the whole mark. */
    public static int markPart(double[] m, double t) {
        double third = (m[1] - m[0]) / 3;
        if (t < m[0] + third) return -1;
        if (t > m[1] - third) return 1;
        return 0;
    }

    /**
     * {@code marks} (as {@link #marks} keeps them) with mark {@code i} moved to start at {@code newStart}, snapped, the same
     * length, inside the night and stopped at its neighbours (touching them at most, never over them). Not merged: a merge of
     * touching marks waits for {@link #marks} (the finger lifted). The list is unchanged when {@code i} is no mark.
     */
    public static List<double[]> moveMark(double start, double stop, List<double[]> marks, int i, double newStart) {
        List<double[]> l = copy(marks);
        if (i < 0 || i >= l.size()) return l;
        double[] m = l.get(i);
        double len = m[1] - m[0];
        double lo = Math.max(start + SNAP_S, i > 0 ? l.get(i - 1)[1] : Double.NEGATIVE_INFINITY);
        double hi = Math.min(stop - SNAP_S, i + 1 < l.size() ? l.get(i + 1)[0] : Double.POSITIVE_INFINITY) - len;
        if (hi < lo) return l;
        double a = Math.max(lo, Math.min(hi, snap(newStart)));
        m[0] = a;
        m[1] = a + len;
        return l;
    }

    /**
     * {@code marks} with the start ({@code end} false) or the end of mark {@code i} moved to {@code t}, snapped, at least 5
     * minutes long, inside the night and stopped at its neighbours. Not merged (see {@link #moveMark}).
     */
    public static List<double[]> resizeMark(double start, double stop, List<double[]> marks, int i, boolean end, double t) {
        List<double[]> l = copy(marks);
        if (i < 0 || i >= l.size()) return l;
        double[] m = l.get(i);
        if (end) {
            double hi = Math.min(stop - SNAP_S, i + 1 < l.size() ? l.get(i + 1)[0] : Double.POSITIVE_INFINITY);
            m[1] = Math.max(m[0] + SNAP_S, Math.min(hi, snap(t)));
        } else {
            double lo = Math.max(start + SNAP_S, i > 0 ? l.get(i - 1)[1] : Double.NEGATIVE_INFINITY);
            m[0] = Math.min(m[1] - SNAP_S, Math.max(lo, snap(t)));
        }
        return l;
    }

    private static List<double[]> copy(List<double[]> marks) {
        List<double[]> l = new ArrayList<double[]>();
        for (double[] m : marks) l.add(new double[] {m[0], m[1]});
        return l;
    }

    /**
     * The default In bed and Up of {@code day}: the median clock times of the last {@link #HISTORY} {@code nights} (each {start,
     * end}, oldest first), else 23:00 to 07:00; on the day's bar, snapped.
     */
    public static double[] defaults(LocalDate day, ZoneId zone, List<double[]> nights) {
        int from = Math.max(0, nights.size() - HISTORY);
        List<double[]> use = nights.subList(from, nights.size());
        double bed = DEFAULT_BED_MIN, up = DEFAULT_UP_MIN;
        if (!use.isEmpty()) {
            double[] s = new double[use.size()], e = new double[use.size()];
            for (int i = 0; i < use.size(); i++) {
                s[i] = barMinute(use.get(i)[0], zone);
                e[i] = barMinute(use.get(i)[1], zone);
            }
            double ms = Stats.median(s), me = Stats.median(e);
            if (!Double.isNaN(ms) && !Double.isNaN(me) && me - ms >= MIN_NIGHT_S / 60.0) {
                bed = ms;
                up = me;
            }
        }
        LocalDateTime b0 = day.minusDays(1).atTime(BAR_FROM_H, 0);
        double s = b0.plusMinutes(Math.round(bed)).atZone(zone).toEpochSecond();
        double e = b0.plusMinutes(Math.round(up)).atZone(zone).toEpochSecond();
        return times(day, zone, s, e, false);
    }

    /**
     * As above, for a night that may not have ended yet (code review of #429: today asks from 04:00, the usual Up may be 07:00):
     * Up is at most {@code now} (unix seconds) snapped down to 5 minutes, In bed then at least {@link #MIN_NIGHT_S} before it.
     * A past day's night is not changed.
     */
    public static double[] defaults(LocalDate day, ZoneId zone, List<double[]> nights, double now) {
        double[] t = defaults(day, zone, nights);
        if (Double.isNaN(now) || now >= t[1]) return t;
        double up = Math.floor(now / SNAP_S) * SNAP_S;
        return times(day, zone, Math.min(t[0], up - MIN_NIGHT_S), up, true);
    }

    /** Minutes after 18:00 by the clock in {@code zone} (0 to 1439): 23:00 is 300, 07:00 is 780. */
    public static double barMinute(double unix, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone);
        int m = t.getHour() * 60 + t.getMinute();
        return ((m - BAR_FROM_H * 60) % 1440 + 1440) % 1440;
    }

    // ------------------------------------------------------------------------------------- wake marks

    /**
     * The wake marks kept inside the night: each snapped, inside In bed and Up with sleep on both sides (5 minutes at least),
     * at least 5 minutes long, sorted, and merged where they touch or overlap. A new list; {@code marks} is not changed.
     */
    public static List<double[]> marks(double start, double stop, List<double[]> marks) {
        List<double[]> l = new ArrayList<double[]>();
        double lo = start + SNAP_S, hi = stop - SNAP_S;
        for (double[] m : marks) {
            if (m == null || m.length < 2 || Double.isNaN(m[0]) || Double.isNaN(m[1])) continue;
            double a = Math.max(lo, snap(Math.min(m[0], m[1]))), b = Math.min(hi, snap(Math.max(m[0], m[1])));
            if (b - a >= SNAP_S) l.add(new double[] {a, b});
        }
        Collections.sort(l, new Comparator<double[]>() {
            @Override
            public int compare(double[] x, double[] y) {
                return Double.compare(x[0], y[0]);
            }
        });
        List<double[]> out = new ArrayList<double[]>();
        for (double[] m : l) {
            double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && m[0] <= last[1]) last[1] = Math.max(last[1], m[1]);
            else out.add(m);
        }
        return out;
    }

    /** The sleep stretches between In bed, the marks and Up, each {start, end}. */
    public static List<double[]> stretches(double start, double stop, List<double[]> marks) {
        List<double[]> out = new ArrayList<double[]>();
        double at = start;
        for (double[] m : marks(start, stop, marks)) {
            if (m[0] > at) out.add(new double[] {at, m[0]});
            at = m[1];
        }
        if (stop > at) out.add(new double[] {at, stop});
        return out;
    }

    /**
     * {@code marks} with a new 15 minute mark in the middle of the longest sleep stretch, kept inside the night; null when no
     * stretch has room for it with sleep on both sides.
     */
    public static List<double[]> addMark(double start, double stop, List<double[]> marks) {
        double[] best = null;
        for (double[] s : stretches(start, stop, marks)) if (best == null || s[1] - s[0] > best[1] - best[0]) best = s;
        if (best == null || best[1] - best[0] < MARK_S + 2 * SNAP_S) return null;
        double a = snap((best[0] + best[1]) / 2 - MARK_S / 2.0);
        a = Math.max(best[0] + SNAP_S, Math.min(best[1] - SNAP_S - MARK_S, a));
        List<double[]> l = new ArrayList<double[]>(marks(start, stop, marks));
        l.add(new double[] {a, a + MARK_S});
        return marks(start, stop, l);
    }

    /** Minutes awake in the marks. */
    public static double awakeMin(double start, double stop, List<double[]> marks) {
        double s = 0;
        for (double[] m : marks(start, stop, marks)) s += m[1] - m[0];
        return s / 60.0;
    }

    /** Minutes asleep: In bed to Up without the marks. */
    public static double asleepMin(double start, double stop, List<double[]> marks) {
        return (stop - start) / 60.0 - awakeMin(start, stop, marks);
    }

    // ------------------------------------------------------------------------------------- the stored marks

    /** The marks as stored: a small JSON list of start and stop times, "[[1700000000,1700000900]]". */
    public static String wakesText(List<double[]> marks) {
        StringBuilder b = new StringBuilder("[");
        for (double[] m : marks) {
            if (b.length() > 1) b.append(',');
            b.append('[').append(Math.round(m[0])).append(',').append(Math.round(m[1])).append(']');
        }
        return b.append(']').toString();
    }

    /** The stored marks read back; a damaged or empty text gives none (the night stays, without marks). */
    public static List<double[]> wakes(String text) {
        List<double[]> out = new ArrayList<double[]>();
        if (text == null) return out;
        String t = text.replace("[", " ").replace("]", " ").replace(",", " ").trim();
        if (t.isEmpty()) return out;
        String[] p = t.split("\\s+");
        if (p.length % 2 != 0) return new ArrayList<double[]>();
        try {
            for (int i = 0; i + 1 < p.length; i += 2) out.add(new double[] {Double.parseDouble(p[i]), Double.parseDouble(p[i + 1])});
        } catch (NumberFormatException e) {
            return new ArrayList<double[]>();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- the night and its score

    /**
     * The night in the ring's night fields: In bed to Up, asleep without the marks, each mark an awakening, wake time the marks,
     * the midpoint; no stages ({@link #SOURCE}), efficiency, latency, heart rate and HRV empty. Its score from total sleep,
     * timing and wake ups ({@link Scores#sleep} leaves out what is missing); {@code age} as the ring's (NaN for none).
     */
    public static Night night(double start, double stop, List<double[]> marks, ZoneId zone, double age) {
        List<double[]> m = marks(start, stop, marks);
        Night n = new Night();
        n.start = start;
        n.end = stop;
        n.main = true;
        n.stageSource = SOURCE;
        n.inBedMin = (stop - start) / 60.0;
        n.awakeMin = awakeMin(start, stop, m);
        n.asleepMin = n.inBedMin - n.awakeMin;
        n.wasoMin = n.awakeMin;
        n.awakenings = m.size();
        n.deepMin = n.lightMin = n.remMin = Double.NaN;
        n.sleepStart = start;
        n.sleepEnd = stop;
        n.midpoint = (start + stop) / 2;
        int off = zone.getRules().getOffset(Instant.ofEpochSecond((long) n.midpoint)).getTotalSeconds();
        n.score = Scores.sleep(n, off, age);
        return n;
    }

    /** The contributors a score from a night by hand can have: total sleep, restfulness (wake ups) and timing. */
    public static final List<String> SCORED = Collections.unmodifiableList(Arrays.asList("total", "restfulness", "timing"));

    // ------------------------------------------------------------------------------------- which night a day shows

    /** True when {start, stop} overlaps any of {@code nights} (each {start, end}). */
    public static boolean overlaps(double start, double stop, List<double[]> nights) {
        for (double[] n : nights) if (n[0] < stop && n[1] > start) return true;
        return false;
    }

    /**
     * Today asks "No night for last night ..." when the day shown is today, it has no night from any device or by hand, a
     * device is there, the person did not say Not now for today ({@code notNowDay}), and a device synced today from 04:00 on
     * and within the last 2 days ({@code lastSync}, unix seconds, 0 never).
     */
    public static boolean ask(LocalDate shown, LocalDate today, boolean night, boolean device, long lastSync, long now, ZoneId zone, String notNowDay) {
        if (!shown.equals(today) || night || !device) return false;
        if (today.toString().equals(notNowDay)) return false;
        long from = today.atTime(ASK_FROM_H, 0).atZone(zone).toEpochSecond();
        return lastSync >= from && now - lastSync <= ASK_SYNC_S && lastSync <= now + 60;
    }

    /** The Sleep tab of {@code day} offers to enter a night by hand: not after today, at most {@link #BACK_DAYS} days back. */
    public static boolean offer(LocalDate day, LocalDate today) {
        return !day.isAfter(today) && !day.isBefore(today.minusDays(BACK_DAYS));
    }

    // ------------------------------------------------------------------------------------- readiness

    /** Readiness of a day whose night is by hand, and whether Previous day activity was left out (yesterday not worn). */
    public static final class Ready {
        public Night.Score score;
        public boolean prevUnworn;
    }

    /**
     * Readiness for a day whose night is by hand: its sleep score and sleep balance, the activity parts from the stored days
     * before (oldest first, as the analysis reads them), and no heart rate, HRV, temperature or recovery, as for a day without
     * the ring (#341). {@code asleep14S}: seconds asleep of the 14 days ending on the day (NaN without a night); {@code needS}
     * the sleep need. Not provisional: no baseline is used.
     */
    public static Ready readiness(double sleepScore, double[] active, double[] target, double[] covered, boolean lastIsYesterday,
            double[] asleep14S, int needS) {
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        ri.sleepScore = sleepScore;
        ri.historyNights = 14;
        if (asleep14S != null && Stats.countFinite(asleep14S) >= 3 && needS > 0) ri.sleepBalance = Stats.mean(asleep14S) / needS;
        Ready r = new Ready();
        r.prevUnworn = Scores.readinessActivity(ri, active, target, covered, lastIsYesterday);
        r.score = Scores.readiness(ri);
        return r;
    }

    /** The sleep need from the seconds asleep of the days before (the analysis' rule, 8 hours with fewer than 14). */
    public static int need(double[] priorS) {
        return Wellness.sleepNeedS(priorS);
    }

    // ------------------------------------------------------------------------------------- demo

    /**
     * The demo's made up night by hand, for the day {@code back} days before {@code today}: In bed 23:10, Up 06:50, awake
     * 02:20 to 02:35 and 04:40 to 04:50. {start, stop} then the marks.
     */
    public static List<double[]> demo(LocalDate today, int back, ZoneId zone) {
        LocalDate day = today.minusDays(back);
        LocalDate eve = day.minusDays(1);
        List<double[]> out = new ArrayList<double[]>();
        out.add(new double[] {eve.atTime(23, 10).atZone(zone).toEpochSecond(), day.atTime(6, 50).atZone(zone).toEpochSecond()});
        out.add(new double[] {day.atTime(2, 20).atZone(zone).toEpochSecond(), day.atTime(2, 35).atZone(zone).toEpochSecond()});
        out.add(new double[] {day.atTime(4, 40).atZone(zone).toEpochSecond(), day.atTime(4, 50).atZone(zone).toEpochSecond()});
        return out;
    }

    /** The demo's night by hand is on the day before yesterday. */
    public static final int DEMO_BACK = 2;
}
