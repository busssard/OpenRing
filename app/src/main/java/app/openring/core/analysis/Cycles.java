package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import app.openring.core.Texts;

/**
 * The period tracker's estimates from the logged days (issue #252, phase 1a1), the way "Design round: plan" and the
 * Design review on the issue settled them. Pure Java on calendar dates, so it runs in the JVM tests and travel never
 * moves a day. Nothing here is stored: the screens build it from the log each time, and logging starts no analysis.
 *
 * <ul>
 * <li><b>Periods</b>: days logged light, medium or heavy, with gaps of at most {@link #GAP_DAYS} unlogged days. An end
 * mark (Ended) on a day closes the period there: its last day is the last logged day on or before the mark, and the
 * next logged day starts a new one. Without a mark a period is over after {@link #OPEN_DAYS} days without a log.
 * Spotting never starts or extends a period, and a removal mark ("Nothing", flow -1) counts as no entry.</li>
 * <li><b>Cycles</b>: start to next start. A cycle more than {@link #FAR} days from the median of all cycles is left out
 * of the typical length and the spread ("maybe a period was not logged").</li>
 * <li><b>Typical length</b>: of the last {@link #WINDOW} cycles kept, the median while there are fewer than
 * {@link #TRIM_FROM}, from then on the mean without the shortest and the longest. Spread: their standard deviation.</li>
 * <li><b>Next start</b>: the last start plus the typical length, give or take max(1, round(1.28 × SD)), which holds
 * about 4 in 5 starts. Without a cycle 29 days, with 1 or 2 the own median, both give or take {@link #WIDE}.</li>
 * <li><b>Late</b>: after the range, "later than expected by N days" (N from the expected day). After twice the
 * typical length the estimate waits for the next start.</li>
 * <li><b>Ovulation</b>: the next start minus {@link #LUTEAL} days, give or take max(3, round(1.28 × √(SD² + 2.4²)))
 * (2.4 days: the spread of the luteal phase in Bull et al. 2019; under 3 cycles SD is their 2.6 days). The fertile
 * window is the 5 days before the estimate plus that day. A calendar estimate, never a measurement.</li>
 * </ul>
 */
public final class Cycles {
    /** Amounts as stored ({@code data/Periods}): removed by the person, spotting, light, medium, heavy. */
    public static final int REMOVED = -1, SPOTTING = 0, LIGHT = 1, MEDIUM = 2, HEAVY = 3;
    /** Unlogged days a period may have between two logged days. */
    public static final int GAP_DAYS = 2;
    /** Without an end mark, a period is over after this many days without a log. */
    public static final int OPEN_DAYS = 3;
    /** A cycle more than this many days from the median is left out of the estimate. */
    public static final int FAR = 10;
    /** The estimate uses at most this many of the newest cycles. */
    public static final int WINDOW = 12;
    /** From this many cycles on, the typical length is the trimmed mean instead of the median. */
    public static final int TRIM_FROM = 5;
    /** Under this many cycles the range is {@link #WIDE} days. */
    public static final int FEW = 3;
    /** The typical cycle without any cycle of the person's own (Bull et al. 2019: 29.3 days). */
    public static final int DEFAULT_LENGTH = 29;
    /** The range with fewer than {@link #FEW} cycles, in days each way. */
    public static final int WIDE = 5;
    /** 1.28 standard deviations each way hold about 4 in 5 of a normal spread. */
    public static final double Z80 = 1.28;
    /** Ovulation is this many days before the next start (the owner's decision; Bull 2019 measured 12.4 on average). */
    public static final int LUTEAL = 14;
    /** The spread of the luteal phase, and of cycles within one person (Bull et al. 2019), in days. */
    public static final double LUTEAL_SD = 2.4, PERSON_SD = 2.6;
    /** The ovulation range is at least this many days each way. */
    public static final int OVULATION_MIN = 3;
    /** The fertile window: this many days before the ovulation estimate, plus that day. */
    public static final int FERTILE_BEFORE = 5;
    /** A cycle shorter or longer than these is unusual whatever the person's own spread. */
    public static final int SHORT = 21, LONG = 35;

    /** One period: its first and last bleeding day. */
    public static final class Period {
        public final LocalDate start, last;
        /** Closed by an end mark (Ended), not by days without a log. */
        public final boolean marked;

        Period(LocalDate start, LocalDate last, boolean marked) {
            this.start = start;
            this.last = last;
            this.marked = marked;
        }

        /** Days from the first to the last bleeding day, both counted. */
        public int days() {
            return (int) ChronoUnit.DAYS.between(start, last) + 1;
        }

        public boolean holds(LocalDate d) {
            return !d.isBefore(start) && !d.isAfter(last);
        }

        @Override
        public String toString() {
            return start + ".." + last + (marked ? " ended" : "");
        }
    }

    /** One cycle: from a period's start to the next one's. */
    public static final class Cycle {
        public final LocalDate start, next;
        public final int length;
        /** More than {@link #FAR} days from the median: not in the estimate. */
        public final boolean leftOut;

        Cycle(LocalDate start, LocalDate next, boolean leftOut) {
            this.start = start;
            this.next = next;
            this.length = (int) ChronoUnit.DAYS.between(start, next);
            this.leftOut = leftOut;
        }

        @Override
        public String toString() {
            return start + " " + length + (leftOut ? " left out" : "");
        }
    }

    /** The day the estimate is made on. */
    public final LocalDate today;
    /** Every logged day with its amount (spotting included, removal marks not), oldest first. */
    public final TreeMap<LocalDate, Integer> flows;
    /** The end marks in force. */
    public final TreeSet<LocalDate> ends;
    /** Every period, oldest first. */
    public final List<Period> periods;
    /** Every cycle, oldest first. */
    public final List<Cycle> cycles;
    /** The cycles the estimate rests on (kept, the newest {@link #WINDOW}), oldest first. */
    public final List<Cycle> used;
    /** The typical cycle length in days, unrounded; {@link #length} rounded. */
    public final double typical;
    public final int length;
    /** The standard deviation of the {@link #used} cycles, NaN with fewer than 2. */
    public final double sd;
    /** The next start's range, in days each way. */
    public final int range;
    /** The expected next start, null without any period. */
    public final LocalDate next;
    /** The period running today, else null. */
    public final Period running;
    /** More than twice the typical length since the last start: the estimate waits for the next start. */
    public final boolean waiting;
    /** The calendar ovulation estimate (null without {@link #next}) and its range in days each way. */
    public final LocalDate ovulation;
    public final int ovulationRange;
    /**
     * "My periods have stopped" (phase 1a2): the day the person said so, else null. In force ({@link #stopped}) while no
     * period runs and every bleeding day lies before that day, so a period logged on it or later ends it by itself.
     */
    public final LocalDate stoppedSince;
    public final boolean stopped;

    private Cycles(Map<LocalDate, Integer> logged, Collection<LocalDate> marks, LocalDate today, LocalDate stoppedSince) {
        this.today = today;
        flows = new TreeMap<LocalDate, Integer>();
        if (logged != null) {
            for (Map.Entry<LocalDate, Integer> e : logged.entrySet()) {
                Integer f = e.getValue();
                if (e.getKey() != null && f != null && f >= SPOTTING && f <= HEAVY) flows.put(e.getKey(), f);
            }
        }
        ends = new TreeSet<LocalDate>();
        if (marks != null) for (LocalDate m : marks) if (m != null) ends.add(m);
        periods = Collections.unmodifiableList(findPeriods(flows, ends));
        List<Cycle> all = new ArrayList<Cycle>();
        int[] lengths = new int[Math.max(0, periods.size() - 1)];
        for (int i = 0; i + 1 < periods.size(); i++) lengths[i] = (int) ChronoUnit.DAYS.between(periods.get(i).start, periods.get(i + 1).start);
        double median = median(lengths);
        for (int i = 0; i + 1 < periods.size(); i++) all.add(new Cycle(periods.get(i).start, periods.get(i + 1).start, Math.abs(lengths[i] - median) > FAR));
        cycles = Collections.unmodifiableList(all);
        List<Cycle> kept = new ArrayList<Cycle>();
        for (Cycle c : all) if (!c.leftOut) kept.add(c);
        used = Collections.unmodifiableList(new ArrayList<Cycle>(kept.subList(Math.max(0, kept.size() - WINDOW), kept.size())));
        int[] u = new int[used.size()];
        for (int i = 0; i < u.length; i++) u[i] = used.get(i).length;
        typical = u.length == 0 ? DEFAULT_LENGTH : u.length < TRIM_FROM ? median(u) : trimmedMean(u);
        length = (int) Math.round(typical);
        sd = sd(u);
        range = u.length < FEW ? WIDE : Math.max(1, (int) Math.round(Z80 * sd));
        Period last = periods.isEmpty() ? null : periods.get(periods.size() - 1);
        next = last == null ? null : last.start.plusDays(length);
        running = last != null && !last.marked && !last.start.isAfter(today) && ChronoUnit.DAYS.between(last.last, today) <= OPEN_DAYS ? last : null;
        waiting = last != null && running == null && ChronoUnit.DAYS.between(last.start, today) > 2L * length;
        double s = u.length < FEW ? PERSON_SD : sd;
        ovulation = next == null ? null : next.minusDays(LUTEAL);
        ovulationRange = Math.max(OVULATION_MIN, (int) Math.round(Z80 * Math.sqrt(s * s + LUTEAL_SD * LUTEAL_SD)));
        this.stoppedSince = stoppedSince;
        stopped = stoppedSince != null && running == null && (last == null || last.last.isBefore(stoppedSince));
    }

    /**
     * The estimate on {@code today} from the logged days ({@code logged}: day to amount, {@link #REMOVED} to
     * {@link #HEAVY}; anything else is no entry) and the end marks.
     */
    public static Cycles of(Map<LocalDate, Integer> logged, Collection<LocalDate> ends, LocalDate today) {
        return new Cycles(logged, ends, today, null);
    }

    /** As above, with the day the person said "My periods have stopped" ({@code stoppedSince}, null when not). */
    public static Cycles of(Map<LocalDate, Integer> logged, Collection<LocalDate> ends, LocalDate today, LocalDate stoppedSince) {
        return new Cycles(logged, ends, today, stoppedSince);
    }

    /** {@code day} (yyyy-mm-dd) as a date, null when it is null or not a date (a setting from an older or damaged file). */
    public static LocalDate day(String day) {
        if (day == null) return null;
        try {
            return LocalDate.parse(day);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Whether the tracker shows: demo mode always, else the Settings switch (1 on, 0 off), unset (-1) when the profile says female (1). */
    public static boolean shown(int choice, int sex, boolean demo) {
        return demo || choice == 1 || (choice < 0 && sex == 1);
    }

    static List<Period> findPeriods(TreeMap<LocalDate, Integer> flows, TreeSet<LocalDate> ends) {
        List<Period> out = new ArrayList<Period>();
        LocalDate start = null, last = null;
        for (Map.Entry<LocalDate, Integer> e : flows.entrySet()) {
            if (e.getValue() < LIGHT) continue; // spotting never starts or extends a period
            LocalDate d = e.getKey();
            if (last != null && ChronoUnit.DAYS.between(last, d) <= GAP_DAYS + 1 && markIn(ends, last, d) == null) {
                last = d;
                continue;
            }
            if (last != null) out.add(new Period(start, last, markIn(ends, last, d) != null));
            start = last = d;
        }
        if (last != null) out.add(new Period(start, last, ends.ceiling(last) != null));
        return out;
    }

    /** The first end mark on or after {@code from} and before {@code to}, else null. */
    static LocalDate markIn(TreeSet<LocalDate> ends, LocalDate from, LocalDate to) {
        LocalDate m = ends.ceiling(from);
        return m != null && m.isBefore(to) ? m : null;
    }

    static double median(int[] v) {
        if (v.length == 0) return Double.NaN;
        int[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    /** The mean without one shortest and one longest value (at least 3 values). */
    static double trimmedMean(int[] v) {
        int[] s = v.clone();
        Arrays.sort(s);
        double sum = 0;
        for (int i = 1; i < s.length - 1; i++) sum += s[i];
        return sum / (s.length - 2);
    }

    /** The sample standard deviation, NaN with fewer than 2 values. */
    static double sd(int[] v) {
        if (v.length < 2) return Double.NaN;
        double m = 0;
        for (int x : v) m += x;
        m /= v.length;
        double q = 0;
        for (int x : v) q += (x - m) * (x - m);
        return Math.sqrt(q / (v.length - 1));
    }

    // ------------------------------------------------------------------ questions the screens ask

    /** The newest period that started on or before {@code d}, else null. */
    public Period periodBefore(LocalDate d) {
        Period p = null;
        for (Period x : periods) if (!x.start.isAfter(d)) p = x;
        return p;
    }

    /** The period {@code d} lies in (first to last bleeding day, or up to today for the running one), else null. */
    public Period periodOn(LocalDate d) {
        Period p = periodBefore(d);
        if (p == null) return null;
        if (p.holds(d)) return p;
        return p == running && !d.isAfter(today) ? p : null;
    }

    /** The day of the cycle on {@code d} (the start is day 1), 0 before the first period. */
    public int cycleDay(LocalDate d) {
        Period p = periodBefore(d);
        return p == null ? 0 : (int) ChronoUnit.DAYS.between(p.start, d) + 1;
    }

    /** Whether Ended may be logged on {@code d}: a period runs today and {@code d} lies from its start to today. */
    public boolean canEnd(LocalDate d) {
        return running != null && !d.isBefore(running.start) && !d.isAfter(today);
    }

    /**
     * Whether there is an estimate to show: a period was logged, the last start is not more than twice the typical length
     * ago, and the person did not say the periods have stopped.
     */
    public boolean estimates() {
        return next != null && !waiting && !stopped;
    }

    /** Days the period is later than expected (from the expected day), once today is past the range; else 0. */
    public int lateBy() {
        if (!estimates() || running != null || !today.isAfter(next.plusDays(range))) return 0;
        return (int) ChronoUnit.DAYS.between(next, today);
    }

    /** {@code d} lies in the expected next period (its range). */
    public boolean inExpected(LocalDate d) {
        return estimates() && !d.isBefore(next.minusDays(range)) && !d.isAfter(next.plusDays(range));
    }

    /** {@code d} lies in the ovulation range. */
    public boolean inOvulation(LocalDate d) {
        return estimates() && !d.isBefore(ovulation.minusDays(ovulationRange)) && !d.isAfter(ovulation.plusDays(ovulationRange));
    }

    /** {@code d} lies in the fertile window (the 5 days before the ovulation estimate, and that day). */
    public boolean inFertile(LocalDate d) {
        return estimates() && !d.isBefore(ovulation.minusDays(FERTILE_BEFORE)) && !d.isAfter(ovulation);
    }

    /** A cycle under 21 or over 35 days, or (from 3 cycles) more than 2 standard deviations from the typical length. */
    public boolean unusual(Cycle c) {
        if (c.length < SHORT || c.length > LONG || c.leftOut) return true;
        return used.size() >= FEW && sd > 0 && Math.abs(c.length - typical) > 2 * sd;
    }

    /** How many of the newest 3 cycles were unusual. */
    public int unusualLately() {
        int n = 0;
        for (int i = Math.max(0, cycles.size() - 3); i < cycles.size(); i++) if (unusual(cycles.get(i))) n++;
        return n;
    }

    // ------------------------------------------------------------------------------- texts

    /** The name of an amount: "Heavy", "Medium", "Light", "Spotting", or "No entry". */
    public static String amount(Integer flow) {
        if (flow == null) return "No entry";
        switch (flow) {
            case HEAVY: return "Heavy";
            case MEDIUM: return "Medium";
            case LIGHT: return "Light";
            case SPOTTING: return "Spotting";
            default: return "No entry";
        }
    }

    /** "8 Nov", with the year when it is not the year of {@link #today}. */
    public String date(LocalDate d, Locale loc) {
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == today.getYear() ? "d MMM" : "d MMM yyyy", loc));
    }

    /** "6 to 12 Nov", "29 Oct to 4 Nov". */
    public String span(LocalDate a, LocalDate b, Locale loc) {
        if (a.getYear() == b.getYear() && a.getMonth() == b.getMonth()) {
            return a.getDayOfMonth() + " to " + date(b, loc);
        }
        return date(a, loc) + " to " + date(b, loc);
    }

    /** The card's big line: "Day 3 of your period", "Cycle day 18", "No period logged since 3 Mar", "No period logged yet". */
    public String status(Locale loc) {
        if (periods.isEmpty()) return "No period logged yet";
        if (running != null) return "Day " + (ChronoUnit.DAYS.between(running.start, today) + 1) + " of your period";
        Period last = periods.get(periods.size() - 1);
        if (waiting || stopped) return "No period logged since " + date(last.start, loc);
        if (last.start.isAfter(today)) return "Next period logged for " + date(last.start, loc);
        return "Cycle day " + cycleDay(today);
    }

    /** The line under the status. */
    public String statusNote(Locale loc) {
        if (periods.isEmpty()) return "Tap Heavy, Medium or Light on the first day of your period. The app then estimates when the next one starts.";
        if (running != null) return "Tap the amount for each day, or Ended when it is over.";
        Period last = periods.get(periods.size() - 1);
        if (stopped) return STOPPED_NOTE;
        if (waiting) return "The estimate starts again with the next period you log.";
        if (last.start.isAfter(today)) return "";
        return "Days since your last period started on " + date(last.start, loc) + ", that day counted as 1.";
    }

    /** "Next period in about 9 days", or the late line; null without an estimate. */
    public String nextLine() {
        if (!estimates()) return null;
        int late = lateBy();
        if (late > 0) return "Your period is " + Texts.count(late, "day", "days") + " later than expected. Cycles vary, this happens.";
        long in = ChronoUnit.DAYS.between(today, next);
        if (in > 1) return "Next period in about " + in + " days";
        if (in == 1) return "Next period expected tomorrow";
        if (in == 0) return "Next period expected today";
        return "Next period expected any day now";
    }

    /** "Expected 6 to 12 Nov." with how sure it is; null without an estimate. */
    public String expectedLine(Locale loc) {
        if (!estimates()) return null;
        String when = "Expected " + span(next.minusDays(range), next.plusDays(range), loc) + ". ";
        if (used.isEmpty()) return when + "Until you have logged " + FEW + " cycles, this uses a typical cycle of " + DEFAULT_LENGTH + " days and a wider range.";
        if (used.size() < FEW) return when + "Until you have logged " + FEW + " cycles, this uses your own " + Texts.count(used.size(), "cycle", "cycles")
                + " and a wider range.";
        return when + "About 4 in 5 periods start within such a range.";
    }

    /** "Ovulation likely around 1 Nov (give or take 4 days). ..."; null without an estimate. */
    public String ovulationLine(Locale loc) {
        if (!estimates()) return null;
        return "Ovulation likely around " + date(ovulation, loc) + " (give or take " + Texts.count(ovulationRange, "day", "days") + "). A calendar estimate, about "
                + LUTEAL + " days before the next expected period, not a measurement.";
    }

    /** "Likely fertile days: 27 Oct to 1 Nov, from the calendar only."; null without an estimate. */
    public String fertileLine(Locale loc) {
        if (!estimates()) return null;
        return "Likely fertile days: " + span(ovulation.minusDays(FERTILE_BEFORE), ovulation, loc) + ", from the calendar only.";
    }

    /** "Your cycles vary by about 3 days.", from 3 cycles; else null. */
    public String regularityLine() {
        if (used.size() < FEW) return null;
        long v = Math.round(sd);
        return v < 1 ? "Your cycles vary by less than a day." : "Your cycles vary by about " + Texts.count(v, "day", "days") + ".";
    }

    /** About the newest finished cycle when it was unusual or left out, else null. */
    public String unusualLine(Locale loc) {
        if (cycles.isEmpty()) return null;
        Cycle c = cycles.get(cycles.size() - 1);
        if (c.leftOut) return "The cycle from " + date(c.start, loc) + " was " + c.length + " days. It is left out of the estimate, maybe a period was not logged.";
        if (!unusual(c)) return null;
        String s = "Your last cycle was " + c.length + " days, " + (c.length > typical ? "longer" : "shorter") + " than your usual " + length + ". One unusual cycle is common.";
        if (unusualLately() >= 2) s += " This has happened twice lately. You could mention it to a doctor.";
        return s;
    }

    /** Always shown with the estimates. */
    public static final String NOT_ADVICE = "These are estimates from the periods you logged and get better with each cycle. "
            + "They are not birth control and not a medical diagnosis.";

    /**
     * What touching a day in the calendar reads: the date and amount, the day of the cycle, and why it lies in a band.
     * One line each.
     */
    public String describe(LocalDate d, Locale loc) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", loc))).append(": ").append(amount(flows.get(d)));
        if (ends.contains(d)) sb.append(", marked as the end of a period");
        Period p = periodOn(d);
        int cd = cycleDay(d);
        if (p != null) sb.append("\nDay ").append(ChronoUnit.DAYS.between(p.start, d) + 1).append(" of the period from ").append(date(p.start, loc));
        else if (cd > 0) sb.append("\nCycle day ").append(cd);
        if (inExpected(d)) {
            sb.append("\nIn the expected next period (").append(span(next.minusDays(range), next.plusDays(range), loc)).append("): your last start plus ");
            sb.append(used.isEmpty() ? "a typical cycle of " + length + " days" : "your typical cycle of " + length + " days");
            sb.append(", give or take ").append(Texts.count(range, "day", "days")).append('.');
        }
        if (inOvulation(d)) {
            sb.append("\nIn the likely ovulation range (").append(span(ovulation.minusDays(ovulationRange), ovulation.plusDays(ovulationRange), loc))
                    .append("): about ").append(LUTEAL).append(" days before the expected period, give or take ")
                    .append(Texts.count(ovulationRange, "day", "days")).append('.');
        }
        if (inFertile(d)) {
            sb.append("\nIn the likely fertile days (").append(span(ovulation.minusDays(FERTILE_BEFORE), ovulation, loc))
                    .append("): the ").append(FERTILE_BEFORE).append(" days before the ovulation estimate and that day.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------- "Your cycles" (phase 1a2): the analysis over time

    /** Average, spread (sample standard deviation), shortest and longest of some whole numbers of days or points. */
    public static final class Summary {
        public final int n;
        /** NaN without values; {@link #sd} NaN with fewer than 2. */
        public final double mean, sd;
        /** 0 without values. */
        public final int min, max;

        Summary(int[] v) {
            n = v.length;
            double s = 0;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int x : v) {
                s += x;
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            mean = n == 0 ? Double.NaN : s / n;
            sd = Cycles.sd(v);
            min = n == 0 ? 0 : lo;
            max = n == 0 ? 0 : hi;
        }
    }

    /** The logged days of one period by amount, and its score: light 1, medium 2, heavy 3 per day (spotting 0). */
    public static final class Amount {
        public final int light, medium, heavy, spotting, score;

        Amount(int light, int medium, int heavy, int spotting) {
            this.light = light;
            this.medium = medium;
            this.heavy = heavy;
            this.spotting = spotting;
            score = light * LIGHT + medium * MEDIUM + heavy * HEAVY;
        }
    }

    /** The periods that are over: every period except the one running now and one logged for a later day. */
    public List<Period> finished() {
        List<Period> out = new ArrayList<Period>();
        for (Period p : periods) if (p != running && !p.start.isAfter(today)) out.add(p);
        return out;
    }

    /** The cycles kept (not left out), oldest first: every one of them, not only the newest the estimate uses. */
    public List<Cycle> kept() {
        List<Cycle> out = new ArrayList<Cycle>();
        for (Cycle c : cycles) if (!c.leftOut) out.add(c);
        return out;
    }

    /** How many cycles are left out (more than {@link #FAR} days from the middle value). */
    public int leftOutCount() {
        return cycles.size() - kept().size();
    }

    /** Cycle length over every kept cycle. */
    public Summary cycleSummary() {
        List<Cycle> k = kept();
        int[] v = new int[k.size()];
        for (int i = 0; i < v.length; i++) v[i] = k.get(i).length;
        return new Summary(v);
    }

    /** Period length (first to last bleeding day, both counted) over the periods that are over. */
    public Summary periodSummary() {
        List<Period> f = finished();
        int[] v = new int[f.size()];
        for (int i = 0; i < v.length; i++) v[i] = f.get(i).days();
        return new Summary(v);
    }

    /** The amount score over the periods that are over. */
    public Summary amountSummary() {
        List<Period> f = finished();
        int[] v = new int[f.size()];
        for (int i = 0; i < v.length; i++) v[i] = amountOf(f.get(i)).score;
        return new Summary(v);
    }

    /** The logged days of {@code p} by amount (days without a log in between count nothing). */
    public Amount amountOf(Period p) {
        int l = 0, m = 0, h = 0, s = 0;
        for (Map.Entry<LocalDate, Integer> e : flows.subMap(p.start, true, p.last, true).entrySet()) {
            int f = e.getValue();
            if (f == HEAVY) h++;
            else if (f == MEDIUM) m++;
            else if (f == LIGHT) l++;
            else s++;
        }
        return new Amount(l, m, h, s);
    }

    /** Days from today to the expected start (0 today, below 0 once it passed), null without an estimate. */
    public Integer daysToNext() {
        return estimates() ? (int) ChronoUnit.DAYS.between(today, next) : null;
    }

    /** What touching a cycle's dot reads: "Cycle from 3 Oct: 29 days", with "left out" or "unusual" when so. */
    public String cycleTip(Cycle c, Locale loc) {
        String s = "Cycle from " + date(c.start, loc) + ": " + Texts.count(c.length, "day", "days");
        if (c.leftOut) return s + ", left out";
        return unusual(c) ? s + ", unusual" : s;
    }

    /** What touching a period's dot reads: "Period from 3 Oct: 5 days". */
    public String periodTip(Period p, Locale loc) {
        return "Period from " + date(p.start, loc) + ": " + Texts.count(p.days(), "day", "days");
    }

    /** What touching a period's amount reads: "3 Oct: score 11 (2 heavy, 2 medium, 1 light)". */
    public String amountTip(Period p, Locale loc) {
        Amount a = amountOf(p);
        StringBuilder sb = new StringBuilder(date(p.start, loc)).append(": score ").append(a.score).append(" (");
        boolean first = true;
        int[] n = {a.heavy, a.medium, a.light};
        String[] w = {"heavy", "medium", "light"};
        for (int i = 0; i < 3; i++) {
            if (n[i] == 0) continue;
            if (!first) sb.append(", ");
            sb.append(n[i]).append(' ').append(w[i]);
            first = false;
        }
        if (first) sb.append("nothing logged");
        return sb.append(')').toString();
    }

    /** One line of the list under the cycle chart: "3 Oct to 1 Nov: 29 days", with why it is marked. */
    public String cycleRow(Cycle c, Locale loc) {
        String s = span(c.start, c.next.minusDays(1), loc) + ": " + Texts.count(c.length, "day", "days");
        if (c.leftOut) return s + ", left out of the estimate, maybe a period was not logged";
        return unusual(c) ? s + ", unusual for you" : s;
    }

    /** The night temperature line goes back at most this many days. */
    public static final int TEMP_DAYS = 100;

    /**
     * The first day of the night temperature line: the start of the period before the last one (a whole cycle and the
     * one running now), else the last start, at most {@link #TEMP_DAYS} days back; without any period the last 30 days.
     */
    public LocalDate tempFrom() {
        LocalDate from = periods.isEmpty() ? today.minusDays(29) : periods.get(Math.max(0, periods.size() - 2)).start;
        LocalDate limit = today.minusDays(TEMP_DAYS - 1);
        return from.isBefore(limit) ? limit : from.isAfter(today) ? today : from;
    }

    /**
     * What touching a day of the night temperature line reads: "Tue 28 Oct: +0.2 °C, cycle day 14", with the period or
     * the estimate's band the day lies in. {@code dev} is the night's difference from the usual, NaN without a night.
     */
    public String tempTip(LocalDate d, double dev, Locale loc) {
        StringBuilder sb = new StringBuilder(d.format(DateTimeFormatter.ofPattern("EEE d MMM", loc))).append(": ");
        sb.append(Double.isNaN(dev) ? "no night" : Texts.signed(dev, loc) + " °C");
        Period p = periodOn(d);
        int cd = cycleDay(d);
        if (p != null) sb.append(", period day ").append(ChronoUnit.DAYS.between(p.start, d) + 1);
        else if (cd > 0) sb.append(", cycle day ").append(cd);
        if (inOvulation(d)) sb.append(", ovulation range");
        else if (inExpected(d)) sb.append(", expected period");
        return sb.toString();
    }

    // --------------------------------------------- "My periods have stopped" (phase 1a2, the owner's wish of 30 Oct)

    /** The gentle question is asked from this age on, never below. */
    public static final int ASK_AGE = 40;
    /** It needs at least this many days without a logged bleeding day, and "Not yet" waits this long before asking again. */
    public static final int ASK_DAYS = 60;
    /** A year without a period: how menopause is usually defined. */
    public static final int YEAR_DAYS = 365;

    /** Days since the last logged bleeding day, -1 without any. */
    public int daysWithout() {
        if (periods.isEmpty()) return -1;
        return (int) ChronoUnit.DAYS.between(periods.get(periods.size() - 1).last, today);
    }

    /**
     * Whether the card asks "Have your periods stopped?": the profile age is {@link #ASK_AGE} or more, the person has not
     * said so yet, no period for more than twice the typical cycle ({@link #waiting}) and at least {@link #ASK_DAYS} days
     * since the last bleeding day, and "Not yet" ({@code notYet}, null when never answered) was at least
     * {@link #ASK_DAYS} days ago.
     */
    public boolean askStopped(int age, LocalDate notYet) {
        if (age < ASK_AGE || stoppedSince != null || !waiting || daysWithout() < ASK_DAYS) return false;
        return notYet == null || ChronoUnit.DAYS.between(notYet, today) >= ASK_DAYS;
    }

    /** "No period for 74 days. Have your periods stopped?" */
    public String askLine() {
        return "No period for " + Texts.count(Math.max(0, daysWithout()), "day", "days") + ". Have your periods stopped?";
    }

    /** What the question's two answers do, under it. */
    public static final String ASK_NOTE = "Yes: no more estimates, late lines or reminders, your past cycles stay. "
            + "Not yet: the app asks again in " + ASK_DAYS + " days.";

    /** Under the status while the person said the periods have stopped. */
    public static final String STOPPED_NOTE = "You said your periods have stopped, so there is no estimate and no reminder. "
            + "Your past cycles stay below. Logging a period day turns this off by itself.";

    /** After a year without a period while it is said to have stopped, plainly; else null. */
    public String yearLine(Locale loc) {
        if (!stopped || daysWithout() < YEAR_DAYS) return null;
        return "A year without a period now (the last one ended " + date(periods.get(periods.size() - 1).last, loc)
                + "). Twelve months without a period is how menopause is usually defined.";
    }

    // -------------------------------------------------------------------------------- demo

    /**
     * The demo's made up log (issue #252): 8 cycles of 27 to 31 days before the newest period, periods of 4 to 6 days
     * (heavy first, then lighter), one spotting day between two periods, and today on about cycle day 18. Day to amount.
     */
    /** The demo's nights after ovulation are this much warmer (°C), as in many people (issue #252, phase 1a2). */
    public static final double DEMO_RISE = 0.3;

    /**
     * The demo's warmer nights (each by the day its morning falls on): after the calendar ovulation of each made up cycle
     * (its next start minus {@link #LUTEAL}) up to the night before that start, and in the cycle running today after the
     * estimate's ovulation day. Only the demo's made up log, never a real one.
     */
    public static TreeSet<LocalDate> demoWarmNights(LocalDate today) {
        Cycles c = of(demo(today), null, today);
        List<LocalDate> starts = new ArrayList<LocalDate>();
        for (Period p : c.periods) starts.add(p.start);
        if (c.next != null) starts.add(c.next);
        TreeSet<LocalDate> out = new TreeSet<LocalDate>();
        for (int i = 1; i < starts.size(); i++) {
            LocalDate s = starts.get(i);
            for (LocalDate d = s.minusDays(LUTEAL - 1); d.isBefore(s) && !d.isAfter(today); d = d.plusDays(1)) out.add(d);
        }
        return out;
    }

    public static TreeMap<LocalDate, Integer> demo(LocalDate today) {
        int[] cycle = {29, 27, 31, 28, 30, 28, 29, 31};
        int[] days = {5, 4, 6, 5, 4, 5, 6, 5, 5};
        TreeMap<LocalDate, Integer> out = new TreeMap<LocalDate, Integer>();
        LocalDate start = today.minusDays(17);
        for (int c : cycle) start = start.minusDays(c);
        for (int i = 0; i <= cycle.length; i++) {
            for (int k = 0; k < days[i]; k++) {
                int f = k == 0 ? (i % 3 == 0 ? MEDIUM : HEAVY) : k == 1 ? HEAVY : k < days[i] - 2 ? MEDIUM : LIGHT;
                out.put(start.plusDays(k), f);
            }
            if (i == 4) out.put(start.plusDays(14), SPOTTING);
            if (i < cycle.length) start = start.plusDays(cycle[i]);
        }
        return out;
    }
}
