package app.openring.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import app.openring.core.analysis.Scores;

/**
 * Today's "Your day" line (owner's wish of 16 November): a decision tree over the facts of the day picks one branch, the
 * card shows a short sentence with the facts behind it, then one line from that branch's list in {@link DayLineBank}. The
 * line changes every day (seeded by the date and the branch) and none comes back within {@link #NO_REPEAT_DAYS} days
 * ({@link Memory}, display memory only). Works with a ring, a watch or only the phone: a fact that is not there is NaN and
 * its branch is skipped. Never a diagnosis: the strain branch says "take it easy", nothing more. Plain Java, tested in
 * {@code DayLineTest}.
 */
public final class DayLine {
    private DayLine() {}

    /** The branches, in the order the tree checks them; the first that fits wins. */
    public static final int STALE = 0, STRAIN = 1, DEBT = 2, RECOVERED = 3, BADGE = 4, NIGHTS = 5, ACTIVE = 6, SCREEN = 7, REST = 8,
            PERIOD = 9, LOW = 10, GOOD = 11, WEEKEND = 12, ORDINARY = 13;
    public static final int BRANCHES = 14;
    /** The list for any day ({@link DayLineBank#ANY}), used once a branch's own lines were all shown lately. */
    public static final int ANY = -1;
    /** Keys for the line ids and the memory. */
    static final String[] KEYS = {"stale", "strain", "debt", "recovered", "badge", "nights", "active", "screen", "rest", "period", "low", "good",
        "weekend", "ordinary"};
    /** The names the "Why this line" list shows. */
    public static final String[] NAMES = {"Data out of date", "Signs of strain", "Short on sleep", "Recovered after low days", "Badge earned",
        "Good nights in a row", "Goal reached days in a row", "Late phone use", "Rest after hard training", "Period", "A lower day", "A strong day",
        "Weekend", "An ordinary day"};

    // ------------------------------------------------------------------------------- the rules (numbers in one place)

    /** No sync for this many hours: the day's numbers are not in yet (only when the day shown is today). */
    public static final double STALE_HOURS = 24;
    /** Strain: the night's temperature at least this far above the usual (°C) ... */
    public static final double TEMP_UP = 0.5;
    /** ... with the resting heart rate at least this many bpm above the usual, or HRV this share under it. */
    public static final double RHR_UP = 3, HRV_DOWN = 0.15;
    /** A usual value needs this many earlier nights (their median). */
    public static final int USUAL_MIN = 7;
    /** Short on sleep: this much sleep debt (minutes), or last night this many minutes under the need. */
    public static final double DEBT_MIN = 240, SHORT_MIN = 90;
    /** Recovered: readiness Good today after at least this many days under Good right before. */
    public static final int LOW_BEFORE = 2;
    /** A streak: this many good nights (sleep score Good or more) in a row, or goal days in a row. */
    public static final int NIGHT_STREAK = 4, GOAL_STREAK = 3;
    /** Late phone use: minutes in the hour before sleep, or during the night. */
    public static final double BED_SCREEN_MIN = 30, NIGHT_SCREEN_MIN = 15;
    /** Hard training yesterday: this many minutes of medium and high activity (the watch: workouts). */
    public static final double HARD_MIN = 60;
    /** A line is not shown again within this many days. */
    public static final int NO_REPEAT_DAYS = 30;

    /** The device the facts come from ({@link MainDevice} numbers). */
    public static final int RING = MainDevice.RING, WATCH = MainDevice.WATCH, PHONE = MainDevice.PHONE;

    /** The facts of one day. NaN or null where a device has no such number; arrays oldest first, ending the day before. */
    public static final class Facts {
        public LocalDate date;
        /** The day shown is today (only then can the data be stale and "so far" be said). */
        public boolean isToday;
        public int device = RING;
        /** Hours since the main device's last sync; NaN when unknown, never for the phone. */
        public double sinceSyncH = Double.NaN;
        /** True when the device never synced (a fresh setup): the card says so instead of hours. */
        public boolean neverSynced;
        public double readiness = Double.NaN, sleepScore = Double.NaN, asleepMin = Double.NaN, needMin = Double.NaN, debtMin = Double.NaN;
        public double rhr = Double.NaN, rhrUsual = Double.NaN, hrv = Double.NaN, hrvUsual = Double.NaN, tempDev = Double.NaN;
        /** Readiness and sleep scores of the days before, oldest first. */
        public double[] readinessBefore = new double[0], sleepBefore = new double[0];
        /** Whether the goal was reached on the days before (oldest first), and today (null: not known). */
        public boolean[] goalBefore = new boolean[0];
        public Boolean goalToday;
        /** "Activity goal" for the ring (active burn against its target), "Step goal" for a watch or the phone. */
        public String goalName = "Activity goal";
        /** Minutes of medium and high activity yesterday (the watch: workout minutes). */
        public double hardYesterdayMin = Double.NaN;
        /** Phone use in the hour before the night's sleep and during it (minutes). */
        public double bedScreenMin = Double.NaN, nightScreenMin = Double.NaN;
        /** Steps and screen minutes of the day, for the plain day's sentence. */
        public double steps = Double.NaN, screenMin = Double.NaN;
        /** The name of a badge earned on this day, else null. */
        public String badge;
        /** The period tracker's phase of this day ({@code Cycles.phase}) when the tracker shows, else null. */
        public String periodPhase;
    }

    /** One step of the tree as the "Why this line" list shows it. */
    public static final class Check {
        public final int branch;
        public final boolean fits;
        /** The facts it looked at, "no data" when none. */
        public final String facts;

        Check(int branch, boolean fits, String facts) {
            this.branch = branch;
            this.fits = fits;
            this.facts = facts;
        }

        /** "Short on sleep: yes, 5h 10m asleep, need 6h 50m" */
        public String line() {
            return NAMES[branch] + ": " + (fits ? "yes" : "no") + (facts == null || facts.isEmpty() ? "" : ", " + facts);
        }
    }

    /** The branch, the sentence with its facts and every check made on the way, top to bottom. */
    public static final class Decision {
        public int branch;
        public String evidence;
        public final List<Check> checks = new ArrayList<Check>();
    }

    /** What the card shows. */
    public static final class Result {
        public final Decision decision;
        public final String line, lineId;

        Result(Decision decision, String line, String lineId) {
            this.decision = decision;
            this.line = line;
            this.lineId = lineId;
        }

        /** The "Why this line" texts: each check, then which list the line came from. */
        public List<String> why() {
            List<String> out = new ArrayList<String>();
            for (Check c : decision.checks) out.add(c.line());
            out.add(lineId.startsWith(KEYS[decision.branch] + ".") ? "The line comes from the list for " + NAMES[decision.branch].toLowerCase(Locale.ROOT)
                    + ", picked by the date, and none repeats within " + NO_REPEAT_DAYS + " days."
                    : "The lines for " + NAMES[decision.branch].toLowerCase(Locale.ROOT) + " were all shown lately, so this one is from the list for any day.");
            return out;
        }
    }

    /** The decision and a line, picked with {@code memory} (and noted there when {@code note}, for today). */
    public static Result of(Facts f, Memory memory, boolean note, Locale loc) {
        Decision d = decide(f, loc);
        String id = pick(d.branch, f.date, memory, note);
        return new Result(d, line(id), id);
    }

    // ------------------------------------------------------------------------------- the tree

    /** Goes down the tree; the first branch that fits wins, the plain day (weekend or ordinary) always fits. */
    public static Decision decide(Facts f, Locale loc) {
        Decision d = new Decision();
        // 1. stale data: nothing pretends to know the day
        boolean stale = f.isToday && f.device != PHONE && (f.neverSynced || f.sinceSyncH >= STALE_HOURS);
        String sync = f.device == PHONE ? "the phone counts by itself" : f.neverSynced ? "never synced" : Double.isNaN(f.sinceSyncH) ? null
                : "last sync " + ago(f.sinceSyncH);
        if (!f.isToday && f.device != PHONE) sync = "an earlier day";
        if (add(d, STALE, stale, sync == null ? "no data" : sync)) {
            String dev = f.device == WATCH ? "watch" : "ring";
            d.evidence = f.neverSynced ? "Your " + dev + " has not synced yet, so this day is not known."
                    : "Your " + dev + " last synced " + ago(f.sinceSyncH) + ", so today's numbers are not in yet.";
            return d;
        }
        // 2. signs of strain: temperature up, with the resting heart rate up or HRV down (both while after ovulation)
        double rhrUp = f.rhr - f.rhrUsual, hrvDown = f.hrvUsual > 0 ? (f.hrvUsual - f.hrv) / f.hrvUsual : Double.NaN;
        boolean tempUp = f.tempDev >= TEMP_UP, hrUp = rhrUp >= RHR_UP, hrvLow = hrvDown >= HRV_DOWN;
        boolean luteal = f.periodPhase != null && (f.periodPhase.startsWith("after ovulation") || f.periodPhase.startsWith("expected period"));
        boolean strain = tempUp && (luteal ? hrUp && hrvLow : hrUp || hrvLow);
        if (add(d, STRAIN, strain, strainFacts(f, rhrUp, hrvDown, luteal, loc))) {
            StringBuilder sb = new StringBuilder("Your temperature is " + Texts.signed(f.tempDev, loc) + " °C against your usual");
            if (hrUp) sb.append(hrvLow ? ", your resting heart rate " : " and your resting heart rate ").append(Math.round(rhrUp)).append(" bpm above it");
            if (hrvLow) sb.append(" and your HRV ").append(Math.round(hrvDown * 100)).append(" % under it");
            d.evidence = sb.append(". Take it easy today.").toString();
            return d;
        }
        // 3. short on sleep: a big debt or a short night
        double under = f.needMin - f.asleepMin;
        boolean shortNight = under >= SHORT_MIN, bigDebt = f.debtMin >= DEBT_MIN;
        if (add(d, DEBT, shortNight || bigDebt, debtFacts(f, under))) {
            if (shortNight) {
                d.evidence = "You slept " + Texts.minutes(f.asleepMin) + ", " + Texts.minutes(under) + " under your need"
                        + (bigDebt ? ", and carry about " + Texts.minutes(f.debtMin) + " of sleep debt." : ".");
            } else {
                d.evidence = "You carry about " + Texts.minutes(f.debtMin) + " of sleep debt from the last two weeks.";
            }
            return d;
        }
        // 4. recovered: readiness Good today after low days
        int low = run(f.readinessBefore, false);
        boolean back = f.readiness >= Scores.GOOD && low >= LOW_BEFORE;
        if (add(d, RECOVERED, back, Double.isNaN(f.readiness) ? "no data" : "readiness " + Math.round(f.readiness) + ", "
                + Texts.count(low, "day", "days") + " under " + Scores.GOOD + " before")) {
            d.evidence = "Readiness is " + Math.round(f.readiness) + " today, after " + Texts.count(low, "day", "days") + " under " + Scores.GOOD + ".";
            return d;
        }
        // 5. a badge earned on this day
        if (add(d, BADGE, f.badge != null, f.badge == null ? "none today" : f.badge)) {
            d.evidence = "You earned the badge " + f.badge + " today.";
            return d;
        }
        // 6. good nights in a row
        int nights = f.sleepScore >= Scores.GOOD ? 1 + run(f.sleepBefore, true) : 0;
        if (add(d, NIGHTS, nights >= NIGHT_STREAK, Double.isNaN(f.sleepScore) ? "no data" : Texts.count(nights, "good night", "good nights") + " in a row")) {
            d.evidence = nights + " good nights in a row (sleep score " + Scores.GOOD + " or more).";
            return d;
        }
        // 7. goal reached days in a row (today counts once it is reached)
        int goals = goalRun(f.goalBefore) + (Boolean.TRUE.equals(f.goalToday) ? 1 : 0);
        if (add(d, ACTIVE, goals >= GOAL_STREAK, f.goalBefore.length == 0 && f.goalToday == null ? "no data"
                : Texts.count(goals, "day", "days") + " in a row")) {
            d.evidence = f.goalName + " reached " + goals + " days in a row" + (Boolean.TRUE.equals(f.goalToday) ? ", today too." : ".");
            return d;
        }
        // 8. late phone use before sleep or in the night
        boolean bed = f.bedScreenMin >= BED_SCREEN_MIN, night = f.nightScreenMin >= NIGHT_SCREEN_MIN;
        if (add(d, SCREEN, bed || night, Double.isNaN(f.bedScreenMin) && Double.isNaN(f.nightScreenMin) ? "no data"
                : Texts.minutes(nz(f.bedScreenMin)) + " before sleep, " + Texts.minutes(nz(f.nightScreenMin)) + " in the night")) {
            d.evidence = bed ? "Your phone was on for " + Texts.minutes(f.bedScreenMin) + " in the hour before sleep"
                    + (night ? " and " + Texts.minutes(f.nightScreenMin) + " during the night." : ".")
                    : "Your phone was on for " + Texts.minutes(f.nightScreenMin) + " during the night.";
            return d;
        }
        // 9. the day after hard training, unless readiness says go
        boolean rest = f.hardYesterdayMin >= HARD_MIN && !(f.readiness >= Scores.OPTIMAL);
        if (add(d, REST, rest, Double.isNaN(f.hardYesterdayMin) ? "no data" : Texts.minutes(f.hardYesterdayMin) + " of hard activity yesterday")) {
            d.evidence = "Yesterday you were active at a hard pace for " + Texts.minutes(f.hardYesterdayMin)
                    + (Double.isNaN(f.readiness) ? "." : ", and readiness is " + Math.round(f.readiness) + " today.");
            return d;
        }
        // 10. a logged period day (only when the tracker shows; kind lines only)
        int pd = periodDay(f.periodPhase);
        if (add(d, PERIOD, pd > 0, f.periodPhase == null ? "not shown" : pd > 0 ? "day " + pd : "not a period day")) {
            d.evidence = "Day " + pd + " of your period, from your log.";
            return d;
        }
        // 11. a lower day: readiness under Good (without readiness, the sleep score)
        double score = !Double.isNaN(f.readiness) ? f.readiness : f.sleepScore;
        String what = !Double.isNaN(f.readiness) ? "Readiness" : "Sleep score";
        if (add(d, LOW, score < Scores.GOOD, Double.isNaN(score) ? "no data" : what.toLowerCase(Locale.ROOT) + " " + Math.round(score))) {
            d.evidence = what + " is " + Math.round(score) + " today, under " + Scores.GOOD + ".";
            return d;
        }
        // 12. a strong day
        if (add(d, GOOD, score >= Scores.OPTIMAL, Double.isNaN(score) ? "no data" : what.toLowerCase(Locale.ROOT) + " " + Math.round(score))) {
            d.evidence = plain(f, loc);
            return d;
        }
        // 13. and 14. a plain day, the weekend or a weekday
        DayOfWeek w = f.date.getDayOfWeek();
        boolean weekend = w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
        add(d, WEEKEND, weekend, w.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
        if (!weekend) add(d, ORDINARY, true, "nothing above fits");
        d.branch = weekend ? WEEKEND : ORDINARY;
        d.evidence = plain(f, loc);
        return d;
    }

    /** Notes a check; true when it fits (the tree stops there). */
    private static boolean add(Decision d, int branch, boolean fits, String facts) {
        d.checks.add(new Check(branch, fits, facts));
        if (fits) d.branch = branch;
        return fits;
    }

    private static String strainFacts(Facts f, double rhrUp, double hrvDown, boolean luteal, Locale loc) {
        if (Double.isNaN(f.tempDev)) return "no temperature";
        StringBuilder sb = new StringBuilder("temperature " + Texts.signed(f.tempDev, loc) + " °C");
        if (!Double.isNaN(rhrUp)) sb.append(", resting heart rate ").append(rhrUp >= 0 ? "+" : "").append(Math.round(rhrUp)).append(" bpm");
        if (!Double.isNaN(hrvDown)) sb.append(", HRV ").append(hrvDown > 0 ? "-" : "+").append(Math.round(Math.abs(hrvDown) * 100)).append(" %");
        sb.append(" against your usual");
        if (luteal) sb.append(" (after ovulation the temperature is up anyway, so both others must be off too)");
        return sb.toString();
    }

    private static String debtFacts(Facts f, double under) {
        List<String> p = new ArrayList<String>();
        if (!Double.isNaN(f.asleepMin)) p.add(Texts.minutes(f.asleepMin) + " asleep" + (Double.isNaN(under) ? "" : ", need " + Texts.minutes(f.needMin)));
        if (!Double.isNaN(f.debtMin)) p.add("sleep debt " + Texts.minutes(f.debtMin));
        return p.isEmpty() ? "no data" : join(p);
    }

    /** "Readiness 78, sleep score 81 and 7h 20m asleep." or, for the phone, "4,210 steps so far and 2h 5m on the screen." */
    static String plain(Facts f, Locale loc) {
        List<String> p = new ArrayList<String>();
        if (!Double.isNaN(f.readiness)) p.add("readiness " + Math.round(f.readiness));
        if (!Double.isNaN(f.sleepScore)) p.add("sleep score " + Math.round(f.sleepScore));
        if (!Double.isNaN(f.asleepMin)) p.add(Texts.minutes(f.asleepMin) + " asleep");
        if (p.size() < 2 && !Double.isNaN(f.steps)) p.add(String.format(loc, "%,d", Math.round(f.steps)) + " steps" + (f.isToday ? " so far" : ""));
        if (p.size() < 2 && !Double.isNaN(f.screenMin)) p.add(Texts.minutes(f.screenMin) + " on the screen");
        if (p.isEmpty()) return "Not much measured yet for this day.";
        String s = join(p);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    /** "a, b and c". */
    private static String join(List<String> p) {
        if (p.size() == 1) return p.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.size(); i++) sb.append(i == 0 ? "" : i == p.size() - 1 ? " and " : ", ").append(p.get(i));
        return sb.toString();
    }

    /** "3 hours ago", "2 days ago". */
    static String ago(double h) {
        if (h < 48) return Texts.count(Math.max(1, Math.round(h)), "hour", "hours") + " ago";
        return Texts.count(Math.round(h / 24), "day", "days") + " ago";
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    /** How many values at the end of {@code a} are Good or more ({@code good}) or known and under Good (not good). */
    static int run(double[] a, boolean good) {
        int n = 0;
        for (int i = a.length - 1; i >= 0; i--) {
            double v = a[i];
            if (Double.isNaN(v) || (good ? v < Scores.GOOD : v >= Scores.GOOD)) break;
            n++;
        }
        return n;
    }

    private static int goalRun(boolean[] a) {
        int n = 0;
        for (int i = a.length - 1; i >= 0 && a[i]; i--) n++;
        return n;
    }

    /** The day number of "period day N", else 0. */
    static int periodDay(String phase) {
        if (phase == null || !phase.startsWith("period day ")) return 0;
        try {
            return Integer.parseInt(phase.substring("period day ".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The usual value: the median of the finite values, NaN with fewer than {@link #USUAL_MIN}. */
    public static double usual(double[] values) {
        List<Double> v = new ArrayList<Double>();
        for (double x : values) if (!Double.isNaN(x) && !Double.isInfinite(x)) v.add(x);
        if (v.size() < USUAL_MIN) return Double.NaN;
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    // ------------------------------------------------------------------------------- the device's facts

    /**
     * The facts a watch or the phone has ({@code DeviceHistory}, no ring): scores, sleep, resting heart rate, steps against the
     * goal, workouts. The caller adds the sync age, the screen, the badge and the period phase.
     */
    public static Facts fromDevice(DeviceHistory h, LocalDate date, boolean isToday) {
        Facts f = new Facts();
        f.date = date;
        f.isToday = isToday;
        f.device = h.device;
        f.goalName = "Step goal";
        int i = h.index(date);
        if (i < 0) return f;
        f.readiness = h.readiness[i];
        f.sleepScore = h.sleep[i];
        f.asleepMin = h.asleepMin[i];
        f.rhr = h.rhr[i];
        f.steps = h.steps[i];
        int n = Math.min(i, 29);
        f.readinessBefore = java.util.Arrays.copyOfRange(h.readiness, i - n, i);
        f.sleepBefore = java.util.Arrays.copyOfRange(h.sleep, i - n, i);
        f.rhrUsual = usual(java.util.Arrays.copyOfRange(h.rhr, Math.max(0, i - 30), i));
        boolean[] g = new boolean[n];
        for (int k = 0; k < n; k++) g[k] = h.steps[i - n + k] >= h.goal;
        f.goalBefore = g;
        f.goalToday = Double.isNaN(h.steps[i]) ? null : h.steps[i] >= h.goal ? Boolean.TRUE : isToday ? null : Boolean.FALSE;
        if (i > 0) f.hardYesterdayMin = h.workoutMin[i - 1];
        if (h.device != PHONE) {
            double[] debt = h.debt(date);
            if (debt[1] > 0) {
                f.debtMin = debt[0];
                f.needMin = debt[2] * 60;
            }
        }
        return f;
    }

    // ------------------------------------------------------------------------------- the line and its memory

    /** The text of a line id ("debt.3", "any.12"), null when it is not in the bank. */
    public static String line(String id) {
        int dot = id == null ? -1 : id.lastIndexOf('.');
        if (dot < 0) return null;
        String key = id.substring(0, dot);
        int b = "any".equals(key) ? ANY : java.util.Arrays.asList(KEYS).indexOf(key);
        if (b == -1 && !"any".equals(key)) return null;
        String[] list = DayLineBank.of(b);
        try {
            int k = Integer.parseInt(id.substring(dot + 1));
            return k >= 0 && k < list.length ? list[k] : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every id of the bank, the branches first, then the lines for any day. */
    public static List<String> allIds() {
        List<String> out = new ArrayList<String>();
        for (int b = 0; b < BRANCHES; b++) out.addAll(ids(b));
        out.addAll(ids(ANY));
        return out;
    }

    static List<String> ids(int b) {
        List<String> out = new ArrayList<String>();
        String key = b == ANY ? "any" : KEYS[b];
        for (int k = 0; k < DayLineBank.of(b).length; k++) out.add(key + "." + k);
        return out;
    }

    /**
     * The line for {@code branch} on {@code date}: the one already noted for that date and branch (stable during the day),
     * else the first in the order seeded by date and branch that was not shown within {@link #NO_REPEAT_DAYS} days, the
     * branch's own lines first, then those for any day; if every one was, the one shown longest ago. Noted when {@code note}.
     */
    public static String pick(int branch, LocalDate date, Memory m, boolean note) {
        Memory.Entry today = m.on(date);
        if (today != null && today.branch == branch && line(today.id) != null) return today.id;
        List<String> order = new ArrayList<String>();
        long seed = date.toEpochDay() * 1000003L + branch * 7919L;
        List<String> own = ids(branch), any = ids(ANY);
        Collections.shuffle(own, new Random(seed));
        Collections.shuffle(any, new Random(seed ^ 0x5DEECE66DL));
        order.addAll(own);
        order.addAll(any);
        String best = null;
        long bestGap = -1;
        for (String id : order) {
            long gap = m.gap(id, date);
            if (gap >= NO_REPEAT_DAYS) {
                best = id;
                break;
            }
            if (gap > bestGap) {
                bestGap = gap;
                best = id;
            }
        }
        if (note) m.put(date, branch, best);
        return best;
    }

    /**
     * The lines shown lately: one entry per date (date, branch, line id), only for not showing a line again soon. Kept as
     * one string in the app's settings ("2026-11-16 debt debt.3;..."); entries older than the window are dropped.
     */
    public static final class Memory {
        static final class Entry {
            final LocalDate date;
            final int branch;
            final String id;

            Entry(LocalDate date, int branch, String id) {
                this.date = date;
                this.branch = branch;
                this.id = id;
            }
        }

        final List<Entry> entries = new ArrayList<Entry>();

        /** Reads the stored string; anything it cannot read is skipped (it is only display memory). */
        public static Memory parse(String s) {
            Memory m = new Memory();
            if (s == null) return m;
            for (String part : s.split(";")) {
                String[] p = part.trim().split(" ");
                if (p.length != 3) continue;
                try {
                    int b = java.util.Arrays.asList(KEYS).indexOf(p[1]);
                    if (b < 0) continue;
                    m.entries.add(new Entry(LocalDate.parse(p[0]), b, p[2]));
                } catch (RuntimeException ignored) {
                    // a broken entry is left out
                }
            }
            return m;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) sb.append(sb.length() == 0 ? "" : ";").append(e.date).append(' ').append(KEYS[e.branch]).append(' ').append(e.id);
            return sb.toString();
        }

        Entry on(LocalDate d) {
            for (Entry e : entries) if (e.date.equals(d)) return e;
            return null;
        }

        /** Days between {@code d} and the nearest other date {@code id} was shown on (either side); a large number when never. */
        long gap(String id, LocalDate d) {
            long g = Long.MAX_VALUE / 2;
            for (Entry e : entries) {
                if (!e.id.equals(id) || e.date.equals(d)) continue;
                g = Math.min(g, Math.abs(e.date.toEpochDay() - d.toEpochDay()));
            }
            return g;
        }

        /** Notes the line of date {@code d} (replacing that date's), dropping entries more than twice the window before it. */
        void put(LocalDate d, int branch, String id) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.date.equals(d) || e.date.isBefore(d.minusDays(2L * NO_REPEAT_DAYS))) entries.remove(i);
            }
            entries.add(new Entry(d, branch, id));
        }

        public int size() {
            return entries.size();
        }
    }
}
