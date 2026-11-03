package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import app.openring.core.Texts;

/**
 * Badges for days and nights that were good for this person (issue #265, phase 1a), the way "Design round: decisions"
 * on the issue settled them. Pure Java on calendar dates: the caller hands one {@link Day} per date with the few stored
 * numbers the rules need, and gets back the badges per date, each with the numbers that earned it and its text.
 * Nothing here is stored and no number is changed: badges are worked out again on every load.
 *
 * <p>Every comparison is with the person's own history, never with a made up target (the activity goal is the one from
 * the analysis). A day with any left out time (issue #254) counts for no badge and in no average or count. A badge
 * that needs a history waits until there is enough of it. Ties never earn a badge.
 *
 * <ul>
 * <li><b>Best night</b>: the night's sleep score is higher than every other night of the last {@link #BEST_DAYS}
 * calendar days, with at least {@link #MIN_NIGHTS} nights in them.</li>
 * <li><b>Restful week</b>: the mean sleep score of the last 7 days is at least {@link #RESTFUL_GAP} points above the
 * 4 weeks before, with at least {@link #RESTFUL_WEEK_NIGHTS} nights in the week and {@link #MIN_NIGHTS} in the 4 weeks;
 * at most once in 7 days, and on a date with a night.</li>
 * <li><b>Recovered</b>: readiness back to its mean of the 4 weeks before the dip's first day, after at least
 * {@link #LOW_DAYS} days in a row below that mean minus one standard deviation; readiness on all of these days, at least
 * {@link #MIN_DAYS} days with readiness in the 4 weeks.</li>
 * <li><b>Phone free evening</b>: at most {@link #PHONE_FREE_MAX} minutes on the phone in the hour before the main
 * night's start; for {@link #PHONE_FREE_OF} such evenings in 7 days (at most once in 7 days) or for the first one after
 * 7 evenings that were all read and had more.</li>
 * <li><b>Above your usual</b>: steps at least {@link #STEPS_ABOVE} above the mean of the last {@link #USUAL_DAYS} worn
 * days (at least {@link #MIN_DAYS} of them); the day itself worn. Worn: {@code covered_min + rest_min} of at least
 * {@link #WORN_MIN}.</li>
 * <li><b>Goal reached</b>: active burn reached the day's activity target on {@link #GOAL_OF} of the last 7 days (at most
 * once in 7 days), or the first goal day after 7 days that all had a summary and no goal day. Never one for every
 * single goal day, so it does not reward activity every day.</li>
 * <li><b>Calm screen day</b>: screen time (left out apps not counted, the day fully read) at least {@link #CALM_UNDER}
 * under its mean of the last 28 days, with at least {@link #MIN_DAYS} fully read days in them.</li>
 * </ul>
 *
 * <p>Night badges (best night, restful week, recovered, phone free evening) go to the date of the main night (the day it
 * ends on); readiness and the evening belong to that night too. Day badges (above your usual, goal, calm screen) need
 * the day to have ended: a day still running ({@link Day#running}) gets none and counts in no day average. The caller
 * passes only nights that were analysed. Every rule looks back a fixed number of days, so a history of years is one
 * pass over its dates.
 */
public final class Badges {
    private Badges() {}

    /** The kinds, in the order the screens list them. */
    public static final int BEST_NIGHT = 0, RESTFUL_WEEK = 1, RECOVERED = 2, PHONE_FREE = 3, ABOVE_USUAL = 4, GOAL = 5, CALM_SCREEN = 6;
    public static final int KINDS = 7;
    public static final String[] NAMES = {"Best night", "Restful week", "Recovered", "Phone free evening", "Above your usual",
        "Goal reached", "Calm screen day"};

    /** Best night: the window in calendar days, the badge's night included. */
    public static final int BEST_DAYS = 30;
    /** Nights needed in a best night window and in the 4 weeks before a restful week. */
    public static final int MIN_NIGHTS = 14;
    /** One week, and the pause after a week badge. */
    public static final int WEEK = 7;
    /** "Your usual": the 4 weeks (28 days) a day or week is compared with. */
    public static final int MONTH = 28;
    /** {@link #MONTH} in weeks, for the texts. */
    public static final int WEEKS = MONTH / 7;
    /** Restful week: points the week's mean is above the 4 weeks before, and nights needed in the week. */
    public static final int RESTFUL_GAP = 5, RESTFUL_WEEK_NIGHTS = 5;
    /** Days needed in a "your usual" average (readiness, steps, screen time). */
    public static final int MIN_DAYS = 14;
    /** Recovered: days in a row below the usual minus one standard deviation. */
    public static final int LOW_DAYS = 2;
    /** Phone free evening: minutes allowed (a look at the clock), and evenings needed in 7 days. */
    public static final int PHONE_FREE_MAX = 2, PHONE_FREE_OF = 3;
    /** Above your usual: steps this share above the mean of the last {@link #USUAL_DAYS} worn days. */
    public static final double STEPS_ABOVE = 0.40;
    public static final int USUAL_DAYS = 28;
    /** A worn day: {@code covered_min + rest_min} at least this (12 hours). */
    public static final int WORN_MIN = 720;
    /** Goal reached: goal days needed in 7. */
    public static final int GOAL_OF = 4;
    /** Calm screen day: this share under the 28 day mean. */
    public static final double CALM_UNDER = 0.30;

    /**
     * One date: the numbers of its day summary and of its main night, NaN when not known. Fill in what is there; a
     * missing date is simply not passed.
     */
    public static final class Day {
        public LocalDate date;
        /** The main night's sleep score (day {@code sleep_score} or night {@code score.value}). */
        public double sleepScore = Double.NaN;
        /** The day's readiness ({@code readiness.value}). */
        public double readiness = Double.NaN;
        /** Day fields {@code steps}, {@code covered_min}, {@code rest_min}, {@code active_kcal}, {@code target_kcal}. */
        public double steps = Double.NaN, coveredMin = Double.NaN, restMin = Double.NaN, activeKcal = Double.NaN, targetKcal = Double.NaN;
        /** The analysis lowered the target after a low readiness day ({@link #goalLowered(boolean, double)}). */
        public boolean goalLowered;
        /** Screen time of the day as the Screen tab counts it (left out apps not counted), NaN when not fully read. */
        public double screenMin = Double.NaN;
        /** Phone minutes in the hour before the main night's start, NaN when that hour was not read or there is no night. */
        public double eveningMin = Double.NaN;
        /** The day has a left out time (issue #254): it counts for nothing. */
        public boolean leftOut;
        /** The day had not ended when it was analysed (its summary has {@code total_until}): no day badge. */
        public boolean running;

        public Day(LocalDate date) {
            this.date = date;
        }
    }

    /** One badge: its date and kind, the numbers behind it and the sentence the screens show. */
    public static final class Badge {
        public final LocalDate date;
        public final int kind;
        /** The badge's own number (score, readiness, minutes, steps, goal days in 7). */
        public final double value;
        /** What it was compared with (the earlier best, your usual, the target), NaN when nothing. */
        public final double usual;
        /** How many days or nights the comparison rests on, or how many of the last 7 qualified. */
        public final int count;
        /** Best night: the date of the earlier best. Null otherwise. */
        public final LocalDate before;
        public final String text;

        Badge(LocalDate date, int kind, double value, double usual, int count, LocalDate before, String text) {
            this.date = date;
            this.kind = kind;
            this.value = value;
            this.usual = usual;
            this.count = count;
            this.before = before;
            this.text = text;
        }

        @Override
        public String toString() {
            return date + " " + NAMES[kind] + ": " + text;
        }
    }

    /**
     * Whether the analysis lowered the day's activity target (Analyzer: without an own target in the profile the target is
     * lowered by a fifth after readiness under Good). The day stores the target it used ({@code target_kcal}) but not
     * why, so: readiness under Good, and the stored target is not the person's own target ({@code ownTarget}, 0 without
     * one). A day analysed while the profile had another own target than now looks lowered on a low readiness day; that
     * is rare and only changes the note in the text, never the badge.
     */
    public static boolean goalLowered(int ownTarget, double usedTarget, double readiness) {
        return readiness < Scores.GOOD && !(ownTarget > 0 && usedTarget == ownTarget);
    }

    /** A worn day: the ring on for at least {@link #WORN_MIN} minutes, 12 hours ({@code covered_min + rest_min}). */
    public static boolean worn(Day d) {
        return d.coveredMin + d.restMin >= WORN_MIN;
    }

    /** Dates more than this many days before the newest one (or after today) are left out: about 20 years. */
    public static final int MAX_SPAN = 20 * 366;

    /** As {@link #of(Collection, LocalDate)} with today's date. */
    public static List<Badge> of(Collection<Day> days) {
        return of(days, LocalDate.now());
    }

    /**
     * Every badge the dates earned, by date and then kind. {@code days} in any order; a second entry for the same
     * date replaces the first. Never two badges of one kind for one date. Dates after {@code today} plus one day (a
     * wrong clock) and more than {@link #MAX_SPAN} days before the newest date left are ignored, so one stray date
     * cannot make the arrays below huge. The texts are English like the rest of the app, also the month names.
     */
    public static List<Badge> of(Collection<Day> days, LocalDate today) {
        List<Badge> out = new ArrayList<Badge>();
        Day[] at = grid(days, today);
        Badge[][] got = run(at, true);
        for (int i = 0; i < at.length; i++) for (int k = 0; k < KINDS; k++) if (got[i][k] != null) out.add(got[i][k]);
        return out;
    }

    /** The dates of {@code days} as one slot per calendar day from the first to the last kept (empty slots null). */
    private static Day[] grid(Collection<Day> days, LocalDate today) {
        TreeMap<LocalDate, Day> byDate = new TreeMap<LocalDate, Day>();
        LocalDate latest = today.plusDays(1);
        for (Day d : days) if (d != null && d.date != null && !d.date.isAfter(latest)) byDate.put(d.date, d);
        if (byDate.isEmpty()) return new Day[0];
        byDate.headMap(byDate.lastKey().minusDays(MAX_SPAN)).clear();
        LocalDate first = byDate.firstKey();
        int n = (int) ChronoUnit.DAYS.between(first, byDate.lastKey()) + 1;
        Day[] at = new Day[n];
        for (Day d : byDate.values()) at[(int) ChronoUnit.DAYS.between(first, d.date)] = d;
        return at;
    }

    /** Every rule over the grid; {@code pause}: with "at most once in 7 days" (without it: where a rule would fire at all). */
    private static Badge[][] run(Day[] at, boolean pause) {
        int n = at.length;
        Badge[][] got = new Badge[n][KINDS];
        // above your usual: the steps of the newest worn days before i (at most 28), kept as the dates move on
        double[] usual = new double[USUAL_DAYS];
        int usualN = 0, usualHead = 0;
        for (int i = 0; i < n; i++) {
            Day d = at[i];
            if (d != null && !d.leftOut) {
                bestNight(at, i, got);
                restfulWeek(at, i, got, pause);
                recovered(at, i, got);
                phoneFree(at, i, got, pause);
                if (!d.running) {
                    if (usualN >= MIN_DAYS) aboveUsual(d, i, Stats.mean(java.util.Arrays.copyOf(usual, usualN)), usualN, got);
                    goal(at, i, got, pause);
                    calmScreen(at, i, got);
                }
            }
            if (stepsDay(d)) {
                // the order inside does not matter for the mean: the oldest is replaced once 28 are kept
                if (usualN == USUAL_DAYS) {
                    usual[usualHead] = d.steps;
                    usualHead = (usualHead + 1) % USUAL_DAYS;
                } else {
                    usual[usualN++] = d.steps;
                }
            }
        }
        return got;
    }

    /** Days every rule looks back at most (Recovered: a dip of up to 28 days and the 28 days before it, and one more). */
    public static final int REACH = 70;
    /** The kinds with "at most once in 7 days": a badge depends on the ones before it, a chain back through the history. */
    static final int[] CHAINED = {RESTFUL_WEEK, PHONE_FREE, GOAL};

    /**
     * For a history read from its first date on (not from the person's first day): the first date from which every badge
     * is exactly what the whole history gives, or null when this read cannot tell. That needs the rules' reach
     * ({@link #REACH} days), for each chained kind 7 days in a row where it could not come even without the pause (so the
     * chain before them cannot reach past them), and the 28 worn days "Above your usual" compares with.
     */
    public static LocalDate exactFrom(Collection<Day> days, LocalDate today) {
        Day[] at = grid(days, today);
        if (at.length == 0) return null;
        LocalDate first = null;
        for (Day d : at) if (d != null) {
            first = d.date;
            break;
        }
        int settled = REACH;
        Badge[][] free = run(at, false);
        for (int k : CHAINED) {
            int quiet = 0, s = -1;
            for (int i = RESTFUL_FROM; i < at.length && s < 0; i++) {
                quiet = free[i][k] == null ? quiet + 1 : 0;
                if (quiet == WEEK) s = i + 1;
            }
            if (s < 0) return null;
            settled = Math.max(settled, s);
        }
        int worn = 0, s = -1;
        for (int i = 0; i < at.length && s < 0; i++) if (stepsDay(at[i]) && ++worn == USUAL_DAYS) s = i + 1;
        if (s < 0) return null;
        settled = Math.max(settled, s);
        return first.plusDays(settled);
    }

    /** First index where a restful week can be judged from the read alone (its 7 days and the 4 weeks before). */
    private static final int RESTFUL_FROM = WEEK + MONTH;

    /** Reads the history from a date on (the Today row reads only what it needs, issue #265). */
    public interface Reader {
        List<Day> from(LocalDate start);
    }

    /**
     * The badges of the dates from {@code from} to {@code to}, exactly as the whole history gives them, reading as little
     * of it as that needs: {@link #REACH} days before {@code from} first, then twice as far each time
     * {@link #exactFrom} says the read was too short, at most back to {@code historyStart} (the first stored date; null
     * when unknown, then the reads go back until they reach it by being empty before it).
     */
    public static List<Badge> recent(Reader reader, LocalDate from, LocalDate to, LocalDate historyStart, LocalDate today) {
        int back = REACH;
        while (true) {
            LocalDate start = from.minusDays(back);
            boolean whole = historyStart != null && !start.isAfter(historyStart) || back > MAX_SPAN;
            if (whole && historyStart != null && historyStart.isBefore(start)) start = historyStart;
            List<Day> days = reader.from(start);
            LocalDate exact = whole ? null : exactFrom(days, today);
            if (whole || exact != null && !exact.isAfter(from)) {
                List<Badge> out = new ArrayList<Badge>();
                for (Badge b : of(days, today)) if (!b.date.isBefore(from) && !b.date.isAfter(to)) out.add(b);
                return out;
            }
            back *= 2;
        }
    }

    // ---------------------------------------------------------------- the rules

    private static boolean ok(Day d) {
        return d != null && !d.leftOut;
    }

    private static boolean night(Day d) {
        return ok(d) && !Double.isNaN(d.sleepScore);
    }

    private static boolean stepsDay(Day d) {
        return ok(d) && !d.running && !Double.isNaN(d.steps) && worn(d);
    }

    private static boolean recentBadge(Badge[][] got, int i, int kind) {
        for (int j = Math.max(0, i - WEEK + 1); j < i; j++) if (got[j][kind] != null) return true;
        return false;
    }

    private static void bestNight(Day[] at, int i, Badge[][] got) {
        Day d = at[i];
        if (!night(d)) return;
        int nights = 1;
        double best = Double.NaN;
        LocalDate bestOn = null;
        for (int j = Math.max(0, i - BEST_DAYS + 1); j < i; j++) {
            if (!night(at[j])) continue;
            nights++;
            if (Double.isNaN(best) || at[j].sleepScore >= best) {
                best = at[j].sleepScore;
                bestOn = at[j].date;
            }
        }
        if (nights < MIN_NIGHTS || !(d.sleepScore > best)) return;
        String text = "Best night in " + BEST_DAYS + " days: score " + Math.round(d.sleepScore) + " (before: " + Math.round(best) + " on "
                + date(bestOn, d.date) + ")";
        got[i][BEST_NIGHT] = new Badge(d.date, BEST_NIGHT, d.sleepScore, best, nights, bestOn, text);
    }

    private static void restfulWeek(Day[] at, int i, Badge[][] got, boolean pause) {
        Day d = at[i];
        if (!night(d) || pause && recentBadge(got, i, RESTFUL_WEEK)) return;
        double week = 0, month = 0;
        int wn = 0, mn = 0;
        for (int j = Math.max(0, i - WEEK + 1); j <= i; j++) {
            if (night(at[j])) {
                week += at[j].sleepScore;
                wn++;
            }
        }
        for (int j = Math.max(0, i - WEEK - MONTH + 1); j <= i - WEEK; j++) {
            if (night(at[j])) {
                month += at[j].sleepScore;
                mn++;
            }
        }
        if (wn < RESTFUL_WEEK_NIGHTS || mn < MIN_NIGHTS) return;
        week /= wn;
        month /= mn;
        if (week - month < RESTFUL_GAP) return;
        String text = "Sleep score " + Math.round(week) + " on average over the last " + WEEK + " days (" + Texts.count(wn, "night", "nights")
                + "), your usual " + Math.round(month) + " (the " + WEEKS + " weeks before)";
        got[i][RESTFUL_WEEK] = new Badge(d.date, RESTFUL_WEEK, week, month, wn, null, text);
    }

    private static boolean readiness(Day d) {
        return ok(d) && !Double.isNaN(d.readiness);
    }

    /**
     * The dip is the run of days with readiness right before {@code i} that are below the usual minus one standard
     * deviation, the usual being the mean of the 28 days before the run's first day (so the dip never counts in its own
     * usual). The shortest run whose day before is not low (or has no readiness) is the dip; it needs {@link #LOW_DAYS}.
     */
    private static void recovered(Day[] at, int i, Badge[][] got) {
        Day d = at[i];
        if (!readiness(d)) return;
        for (int len = 1; len <= MONTH && i - len >= 0; len++) {
            int start = i - len;
            if (!readiness(at[start])) return;
            double[] v = new double[MONTH];
            int k = 0;
            for (int j = Math.max(0, start - MONTH); j < start; j++) if (readiness(at[j])) v[k++] = at[j].readiness;
            if (k < MIN_DAYS) continue;
            double[] have = java.util.Arrays.copyOf(v, k);
            double mean = Stats.mean(have), low = mean - Stats.sd(have);
            boolean allLow = true;
            double lowest = Double.NaN;
            for (int j = start; j < i; j++) {
                if (!(at[j].readiness < low)) allLow = false;
                if (Double.isNaN(lowest) || at[j].readiness < lowest) lowest = at[j].readiness;
            }
            if (!allLow) return; // a day of the run is not low: no dip of this length or longer either
            boolean before = start > 0 && readiness(at[start - 1]) && at[start - 1].readiness < low;
            if (before) continue; // the dip began earlier
            if (len < LOW_DAYS || d.readiness < mean) return;
            String dip = len == LOW_DAYS ? Math.round(at[i - 2].readiness) + " and " + Math.round(at[i - 1].readiness)
                    : "lowest " + Math.round(lowest);
            String text = "Readiness " + Math.round(d.readiness) + ", back to your usual " + Math.round(mean) + " (the " + WEEKS
                    + " weeks before) after " + len + " low days in a row (" + dip + ")";
            got[i][RECOVERED] = new Badge(d.date, RECOVERED, d.readiness, mean, len, null, text);
            return;
        }
    }

    private static boolean evening(Day d) {
        return ok(d) && !Double.isNaN(d.eveningMin);
    }

    /**
     * At most {@link #PHONE_FREE_MAX} minutes as the screens show them: rounded like {@code Texts.minutes}, so an evening
     * shown as "2 min" (2.4) counts and one shown as "3 min" (2.5) does not.
     */
    private static boolean phoneFreeEvening(Day d) {
        return evening(d) && Math.round(d.eveningMin) <= PHONE_FREE_MAX;
    }

    private static void phoneFree(Day[] at, int i, Badge[][] got, boolean pause) {
        Day d = at[i];
        if (!phoneFreeEvening(d)) return;
        String mins = Texts.minutes(d.eveningMin) + " on the phone in the hour before sleep";
        int free = 0;
        for (int j = Math.max(0, i - WEEK + 1); j <= i; j++) if (phoneFreeEvening(at[j])) free++;
        if (free >= PHONE_FREE_OF && !(pause && recentBadge(got, i, PHONE_FREE))) {
            got[i][PHONE_FREE] = new Badge(d.date, PHONE_FREE, d.eveningMin, Double.NaN, free, null,
                    free + " phone free evenings in " + WEEK + " days: this one " + mins);
            return;
        }
        if (i < WEEK) return;
        for (int j = i - WEEK; j < i; j++) if (!evening(at[j]) || phoneFreeEvening(at[j])) return;
        got[i][PHONE_FREE] = new Badge(d.date, PHONE_FREE, d.eveningMin, Double.NaN, 1, null,
                "Phone free evening after " + WEEK + " evenings with more: " + mins);
    }

    private static void aboveUsual(Day d, int i, double mean, int days, Badge[][] got) {
        if (!stepsDay(d) || !(mean > 0) || d.steps < mean * (1 + STEPS_ABOVE)) return;
        long more = Math.round((d.steps / mean - 1) * 100);
        String text = Math.round(d.steps) + " steps, " + more + "% more than your usual " + Math.round(mean) + " (your last " + days
                + " days with the ring)";
        got[i][ABOVE_USUAL] = new Badge(d.date, ABOVE_USUAL, d.steps, mean, days, null, text);
    }

    /** Whether the day has a summary with a target (it can say yes or no about the goal). */
    private static boolean goalKnown(Day d) {
        return ok(d) && !d.running && d.targetKcal > 0 && !Double.isNaN(d.activeKcal);
    }

    private static boolean goalDay(Day d) {
        return goalKnown(d) && d.activeKcal >= d.targetKcal;
    }

    private static void goal(Day[] at, int i, Badge[][] got, boolean pause) {
        Day d = at[i];
        if (!goalDay(d)) return;
        String kcal = Math.round(d.activeKcal) + " of " + Math.round(d.targetKcal) + " kcal";
        int days = 0, lowered = 0;
        for (int j = Math.max(0, i - WEEK + 1); j <= i; j++) {
            if (goalDay(at[j])) {
                days++;
                if (at[j].goalLowered) lowered++;
            }
        }
        if (days >= GOAL_OF && !(pause && recentBadge(got, i, GOAL))) {
            String note = lowered == 0 ? "" : lowered == days ? "; each goal lowered after a low readiness day"
                    : "; on " + lowered + " of them the goal was lowered after a low readiness day";
            String text = "Activity goal reached on " + days + " of the last " + WEEK + " days (this day " + kcal + note + ")";
            got[i][GOAL] = new Badge(d.date, GOAL, days, d.targetKcal, days, null, text);
            return;
        }
        if (i < WEEK) return;
        for (int j = i - WEEK; j < i; j++) if (!goalKnown(at[j]) || goalDay(at[j])) return;
        String text = "Activity goal reached after " + WEEK + " days without it: " + kcal;
        if (d.goalLowered) text += " (goal lowered after a low readiness day)";
        got[i][GOAL] = new Badge(d.date, GOAL, 1, d.targetKcal, 1, null, text);
    }

    private static boolean screenDay(Day d) {
        return ok(d) && !d.running && !Double.isNaN(d.screenMin);
    }

    private static void calmScreen(Day[] at, int i, Badge[][] got) {
        Day d = at[i];
        if (!screenDay(d)) return;
        double sum = 0;
        int k = 0;
        for (int j = Math.max(0, i - MONTH); j < i; j++) {
            if (screenDay(at[j])) {
                sum += at[j].screenMin;
                k++;
            }
        }
        if (k < MIN_DAYS) return;
        double mean = sum / k;
        if (!(mean > 0) || d.screenMin > mean * (1 - CALM_UNDER)) return;
        long less = Math.round(mean) - Math.round(d.screenMin);
        String text = Texts.minutes(d.screenMin) + " on the phone, " + Texts.minutes(less) + " less than your usual " + Texts.minutes(mean)
                + " (last " + WEEKS + " weeks)";
        got[i][CALM_SCREEN] = new Badge(d.date, CALM_SCREEN, d.screenMin, mean, k, null, text);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Badges from the night (sleep, readiness, the evening before), shown with the night; the others with the day. */
    public static boolean fromNight(int kind) {
        return kind == BEST_NIGHT || kind == RESTFUL_WEEK || kind == RECOVERED || kind == PHONE_FREE;
    }

    /** Whether a badge came after {@code seen}, the newest date already seen (kv {@code badges_seen}); every one without. */
    public static boolean isNew(Badge b, LocalDate seen) {
        return seen == null || b.date.isAfter(seen);
    }

    /** The newest date of {@code badges}, null without any. */
    public static LocalDate newest(List<Badge> badges) {
        LocalDate n = null;
        for (Badge b : badges) if (n == null || b.date.isAfter(n)) n = b.date;
        return n;
    }

    /** "12 Oct" in English like every text of the app, with the year when it differs from {@code ref}'s. */
    public static String date(LocalDate d, LocalDate ref) {
        return d.format(d.getYear() == ref.getYear() ? DAY : DAY_YEAR);
    }
}
