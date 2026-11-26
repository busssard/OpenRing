package app.openring.core.analysis;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import app.openring.core.Texts;
import app.openring.core.analysis.cycleforecast.Backtest;

/**
 * The life factors logged in each cycle, next to how close the estimate of the period that ended it was (issue #383, phase 2;
 * design comment of 25 November). Pure Java, nothing stored: worked out from the period log and the logged days on each load.
 * <ul>
 * <li><b>Window:</b> a scored period's cycle, from the first day of its period up to the day before the next start
 * ({@link Cycles.Cycle#start} to {@link Cycles.Cycle#next} minus 1). Calendar days only: both the period log and the tags are
 * stored as dates, so no clock or time zone moves a factor into another cycle. A factor on the day the period came belongs to
 * the next cycle; every day to exactly one.</li>
 * <li><b>With, without, unknown:</b> a cycle is {@link #WITH} a factor logged on any day of its window. It is {@link #WITHOUT}
 * it only when the whole window lies after the factor could be logged ({@link FactorEffect.Factor#from}: the first day
 * anything was logged, an own tag from the day it was made); else {@link #UNKNOWN}, not "without".</li>
 * <li><b>Only describes:</b> what was logged next to each period, never what moved it. The estimate does not use it.</li>
 * </ul>
 */
public final class CycleFactors {
    /** How a cycle stands to one factor. */
    public static final int WITH = 0, WITHOUT = 1, UNKNOWN = 2;
    /**
     * The touch readout's lines are wrapped at this many characters: 40 at 11.5 dp fit the chart on a 360 dp phone (the
     * Design review said about 40; 50 was too wide, review of #488).
     */
    public static final int WRAP = 40;

    public static final String MARK = "Mark a life factor";
    public static final String CHANCE = "This shows what was logged next to each period, not what moved it. With few cycles a difference can be chance.";
    public static final String LEGEND_FADED = "faded: before you logged it";
    public static final String NOTHING = "Nothing logged that cycle.";
    public static final String EVERY = "Logged in every cycle since you started, so there are no cycles without it to set it against.";
    /** How it works of "How close the estimates were". */
    public static final String HOW = "Life factors: a factor counts for a cycle when you logged it on any day from the first day of its period to the day "
            + "before the next one started, by the calendar day you logged it, wherever you were. Cycles before you could log it are left out "
            + "of the comparison and drawn faded. Days you did not log count as without the factor. This only shows what was logged next "
            + "to each period; the estimate does not use it.";

    /** One scored period: the cycle it ended, how many days later than expected it came, and the factors logged in that cycle. */
    public static final class Period {
        public final Cycles.Cycle cycle;
        public final int late;
        /** Each factor logged in the window, in the factors' order, with its days, oldest first. */
        public final Map<String, List<LocalDate>> logged;

        Period(Cycles.Cycle cycle, int late, Map<String, List<LocalDate>> logged) {
            this.cycle = cycle;
            this.late = late;
            this.logged = Collections.unmodifiableMap(logged);
        }
    }

    /** The scored periods, in the backtest's order (the same as {@link Backtest.Score#index}). */
    public final List<Period> periods;
    /** The factors logged in at least one scored cycle, in the order given (All factors: fixed tags first, then own ones). */
    public final List<FactorEffect.Factor> chips;
    /** The first day any factor could be logged, null without any factor. */
    public final LocalDate first;
    private final Map<String, FactorEffect.Factor> byKey = new LinkedHashMap<String, FactorEffect.Factor>();
    private final Cycles cycles;

    private CycleFactors(Cycles cycles, List<Period> periods, List<FactorEffect.Factor> chips, LocalDate first, List<FactorEffect.Factor> all) {
        this.cycles = cycles;
        this.periods = Collections.unmodifiableList(periods);
        this.chips = Collections.unmodifiableList(chips);
        this.first = first;
        for (FactorEffect.Factor f : all) byKey.put(f.key, f);
    }

    /**
     * The scored periods of {@code use} (the backtest of the method in use on {@code cy}) with the logged {@code factors} (tags
     * only; the period tracker's own factors are left out). Neither input is changed.
     */
    public static CycleFactors of(Cycles cy, Backtest.Score use, List<FactorEffect.Factor> factors) {
        List<FactorEffect.Factor> tags = new ArrayList<FactorEffect.Factor>();
        if (factors != null) for (FactorEffect.Factor f : factors) if (f != null && f.kind == FactorEffect.TAG && !f.days.isEmpty()) tags.add(f);
        LocalDate first = null;
        List<TreeSet<LocalDate>> sorted = new ArrayList<TreeSet<LocalDate>>();
        for (FactorEffect.Factor f : tags) {
            TreeSet<LocalDate> s = new TreeSet<LocalDate>(f.days);
            sorted.add(s);
            LocalDate from = f.from != null ? f.from : s.first();
            if (first == null || from.isBefore(first)) first = from;
        }
        List<Period> out = new ArrayList<Period>();
        boolean[] seen = new boolean[tags.size()];
        int n = use == null ? 0 : use.n;
        for (int i = 0; i < n; i++) {
            Cycles.Cycle k = cy.cycles.get(use.index[i]);
            Map<String, List<LocalDate>> logged = new LinkedHashMap<String, List<LocalDate>>();
            for (int j = 0; j < tags.size(); j++) {
                // the window by calendar day: the period's first day up to the day before the next start
                java.util.SortedSet<LocalDate> in = sorted.get(j).subSet(k.start, k.next);
                if (in.isEmpty()) continue;
                logged.put(tags.get(j).key, new ArrayList<LocalDate>(in));
                seen[j] = true;
            }
            out.add(new Period(k, use.late(i), logged));
        }
        List<FactorEffect.Factor> chips = new ArrayList<FactorEffect.Factor>();
        for (int j = 0; j < tags.size(); j++) if (seen[j]) chips.add(tags.get(j));
        return new CycleFactors(cy, out, chips, first, tags);
    }

    /** The factor of {@code key}, null when it is not one of the logged factors. */
    public FactorEffect.Factor factor(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** Whether {@code key} is one of the {@link #chips}. */
    public boolean offered(String key) {
        for (FactorEffect.Factor f : chips) if (f.key.equals(key)) return true;
        return false;
    }

    /** {@link #WITH}, {@link #WITHOUT} or {@link #UNKNOWN}: how scored period {@code i}'s cycle stands to the factor {@code key}. */
    public int state(int i, String key) {
        Period p = periods.get(i);
        if (p.logged.containsKey(key)) return WITH;
        FactorEffect.Factor f = byKey.get(key);
        if (f == null) return UNKNOWN;
        LocalDate from = f.from != null ? f.from : first;
        return from != null && p.cycle.start.isBefore(from) ? UNKNOWN : WITHOUT;
    }

    /** The newest scored period whose cycle had {@code key}, -1 when none. */
    public int newest(String key) {
        for (int i = periods.size() - 1; i >= 0; i--) if (periods.get(i).logged.containsKey(key)) return i;
        return -1;
    }

    /** Whether any scored period's cycle is {@link #UNKNOWN} for {@code key} (the legend then names the faded dots). */
    public boolean anyUnknown(String key) {
        for (int i = 0; i < periods.size(); i++) if (state(i, key) == UNKNOWN) return true;
        return false;
    }

    // ------------------------------------------------------------------ texts

    /**
     * The lines the touch readout of scored period {@code i} adds under its first line, wrapped at {@link #WRAP} characters:
     * the factors logged that cycle ({@code chosen} first, then in the list order), each with its days; or why there are none.
     * Empty without any logged factor at all.
     */
    public String tip(int i, String chosen, Locale loc) {
        if (first == null) return "";
        Period p = periods.get(i);
        if (p.logged.isEmpty()) {
            if (p.cycle.next.isAfter(first) && !p.cycle.start.isBefore(first)) return NOTHING;
            if (!p.cycle.next.isAfter(first)) return words("Before you started logging life factors (" + cycles.date(first, loc) + ").");
            return words("Nothing logged that cycle since you started on " + cycles.date(first, loc) + ".");
        }
        List<String> keys = new ArrayList<String>();
        if (chosen != null && p.logged.containsKey(chosen)) keys.add(chosen);
        for (String k : p.logged.keySet()) if (!k.equals(chosen)) keys.add(k);
        List<String> items = new ArrayList<String>();
        for (String k : keys) items.add(item(p, k));
        return wrap("Logged that cycle: ", items);
    }

    /** "Illness 5 days (days 9 to 13)": a factor on one stretch of days gives its cycle days, scattered days the count only. */
    String item(Period p, String key) {
        List<LocalDate> days = p.logged.get(key);
        FactorEffect.Factor f = byKey.get(key);
        String name = f == null ? key : f.name;
        String s = name + " " + Texts.count(days.size(), "day", "days");
        LocalDate a = days.get(0), b = days.get(days.size() - 1);
        if (ChronoUnit.DAYS.between(a, b) == days.size() - 1) {
            long da = ChronoUnit.DAYS.between(p.cycle.start, a) + 1, db = ChronoUnit.DAYS.between(p.cycle.start, b) + 1;
            s += da == db ? " (day " + da + ")" : " (days " + da + " to " + db + ")";
        }
        return s;
    }

    /** {@code s} broken at its spaces so no line passes {@link #WRAP} (a word longer than that stays whole on its line). */
    static String words(String s) {
        StringBuilder all = new StringBuilder(), line = new StringBuilder();
        for (String w : s.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > WRAP) {
                all.append(line).append('\n');
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        return all.append(line).toString();
    }

    /**
     * {@code prefix} and the items joined with commas, a new line before an item that would pass {@link #WRAP}; the prefix
     * stands on its own line when the first item does not fit after it (review of #488).
     */
    static String wrap(String prefix, List<String> items) {
        StringBuilder all = new StringBuilder();
        StringBuilder line = new StringBuilder(prefix);
        boolean firstOnLine = true;
        for (int i = 0; i < items.size(); i++) {
            String piece = items.get(i) + (i + 1 < items.size() ? "," : "");
            if (i == 0 && line.length() > 0 && line.length() + piece.length() > WRAP) {
                all.append(line.toString().trim()).append('\n');
                line = new StringBuilder(piece);
            } else if (!firstOnLine && line.length() + 1 + piece.length() > WRAP) {
                all.append(line).append('\n');
                line = new StringBuilder(piece);
            } else {
                if (!firstOnLine) line.append(' ');
                line.append(piece);
            }
            firstOnLine = false;
        }
        return all.append(line).toString();
    }

    /** "came 3 days later than expected", "came 2 days earlier than expected", "came on the expected day". */
    static String came(int late) {
        if (late == 0) return "came on the expected day";
        return "came " + Texts.count(Math.abs(late), "day", "days") + (late > 0 ? " later" : " earlier") + " than expected";
    }

    /** An average of days late: "1.4 days later than expected on average", or about on the expected day below half a day. */
    static String cameOnAverage(double late, Locale loc) {
        if (Math.abs(late) < 0.5) return "about on the expected day on average";
        return String.format(loc, "%.1f", Math.abs(late)) + " days " + (late > 0 ? "later" : "earlier") + " than expected on average";
    }

    /**
     * The line under the chart while {@code key} is marked: with one cycle the period's own sentence; from two the averages of
     * the cycles with it and without it; null when it was logged in no scored cycle. {@link #CHANCE} goes under it.
     */
    public String line(String key, Locale loc) {
        FactorEffect.Factor f = byKey.get(key);
        if (f == null) return null;
        List<Integer> with = new ArrayList<Integer>(), without = new ArrayList<Integer>();
        for (int i = 0; i < periods.size(); i++) {
            int s = state(i, key);
            if (s == WITH) with.add(i);
            else if (s == WITHOUT) without.add(i);
        }
        if (with.isEmpty()) return null;
        if (with.size() == 1) {
            Period p = periods.get(with.get(0));
            return "The period on " + cycles.date(p.cycle.next, loc) + " " + came(p.late) + "; you logged " + f.name + " that cycle ("
                    + Texts.count(p.logged.get(key).size(), "day", "days") + ").";
        }
        if (without.isEmpty()) return EVERY;
        String s = f.name + " was logged in " + with.size() + " of " + (with.size() + without.size()) + " cycles. Those periods came "
                + cameOnAverage(mean(with), loc) + ", the other " + (without.size() == 1 ? "one " : without.size() + " ");
        double o = mean(without);
        s += Math.abs(o) < 0.5 ? "came about on the expected day." : "came " + String.format(loc, "%.1f", Math.abs(o)) + " days " + (o > 0 ? "later." : "earlier.");
        return s;
    }

    private double mean(List<Integer> idx) {
        double s = 0;
        for (int i : idx) s += periods.get(i).late;
        return s / idx.size();
    }

    /** The legend's ring: "Illness logged that cycle". */
    public static String legendRing(String name) {
        return name + " logged that cycle";
    }
}
