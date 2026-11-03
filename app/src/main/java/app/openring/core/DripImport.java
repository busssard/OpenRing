package app.openring.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import app.openring.core.analysis.Cycles;

/**
 * The drip import's preview and its rules (issue #252, phase 1b): what a file would add to the period log, worked out
 * before anything is saved, and the one decision per day that the import itself ({@code data/Periods.importDrip}) applies
 * again inside its transaction, so the preview and the import can never disagree. Pure Java.
 *
 * <ul>
 * <li><b>Days already logged in Open Ring win</b>: a day with an amount (spotting to heavy) that differs from the file's
 * is kept unless the person picks "take the file's values" ({@link #DIFFER}); one with the same amount is left alone
 * ({@link #SAME}, so importing the same file twice changes nothing); a day without a row, or with a removal mark
 * ("Nothing" or an undone import, no entry) gets the file's amount ({@link #ADD}).</li>
 * <li><b>Temperatures</b> already there (from an earlier import) are never replaced: an equal one is {@link #SAME}, a
 * different one {@link #DIFFER} and kept (no value to go back to on undo); one undone or missing is {@link #ADD}.</li>
 * <li><b>Periods</b> are counted with {@link Cycles}' own rules on the file's days; a period under {@link #SHORT} days
 * is flagged, each on its own line.</li>
 * </ul>
 */
public final class DripImport {
    private DripImport() {}

    public static final int ADD = 0, SAME = 1, DIFFER = 2;
    /** A period of fewer days than this is flagged in the preview. */
    public static final int SHORT = 3;

    /** What happens to a day of the file ({@code file}: its amount) against the phone's row ({@code have}: null without one). */
    public static int day(int file, Integer have) {
        if (have == null || have == Cycles.REMOVED) return ADD;
        return have == file ? SAME : DIFFER;
    }

    /** What happens to a temperature of the file against the phone's ({@code have} null without one, {@code gone} undone). */
    public static int temp(DripCsv.Temp file, DripCsv.Temp have, boolean gone) {
        if (have == null || gone) return ADD;
        return file.same(have) ? SAME : DIFFER;
    }

    /** A flagged period: under {@link #SHORT} days. */
    public static final class Short {
        public final Cycles.Period period;
        /** The amount of each day from its first to its last day (null for a day without an entry). */
        public final List<Integer> amounts;
        /** The preview's periods, for writing the date like the period cards do. */
        private final Cycles cycles;

        Short(Cycles.Period p, List<Integer> amounts, Cycles cycles) {
            this.period = p;
            this.amounts = amounts;
            this.cycles = cycles;
        }

        /** "Period of 2 days starting 6 Oct 2025 (light, light)", the year only when it is not this year. */
        public String line(Locale loc) {
            StringBuilder sb = new StringBuilder("Period of ").append(Texts.count(period.days(), "day", "days")).append(" starting ")
                    .append(cycles.date(period.start, loc)).append(" (");
            for (int i = 0; i < amounts.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(Cycles.amount(amounts.get(i)).toLowerCase(Locale.ROOT));
            }
            return sb.append(')').toString();
        }
    }

    /** A day where the phone and the file differ. */
    public static final class Diff {
        public final LocalDate day;
        public final int have, file;
        /** The preview's periods, for writing the date like the period cards do. */
        private final Cycles cycles;

        Diff(LocalDate day, int have, int file, Cycles cycles) {
            this.day = day;
            this.have = have;
            this.file = file;
            this.cycles = cycles;
        }

        /** "12 Sep 2025: Open Ring Heavy, file Medium", the year only when it is not this year. */
        public String line(Locale loc) {
            return cycles.date(day, loc) + ": Open Ring " + Cycles.amount(have) + ", file " + Cycles.amount(file);
        }
    }

    /** The preview of one file against the phone's log. Nothing of it is stored. */
    public static final class Preview {
        /** The file's days as they would be imported (with the person's corrections), and its temperatures. */
        public final TreeMap<LocalDate, Integer> flows;
        public final TreeMap<LocalDate, DripCsv.Temp> temps;
        /** The file's days on their own, by Cycles' rules (no end marks: drip has none). */
        public final Cycles cycles;
        /** First and last day with a bleeding entry or a temperature; null for a file without either. */
        public final LocalDate from, to;
        public final int bleedingDays, spottingDays, tempCount;
        /** Every period under {@link #SHORT} days, oldest first. */
        public final List<Short> shorts;
        /** Days the phone holds with another amount, oldest first. */
        public final List<Diff> differ;
        /** Days the import adds (empty on the phone), days equal on both. */
        public final int addDays, sameDays;
        /** Temperatures the import adds, equal ones, and different ones that are kept. */
        public final int addTemps, sameTemps, differTemps;

        Preview(Map<LocalDate, Integer> fileFlows, Map<LocalDate, DripCsv.Temp> fileTemps, Map<LocalDate, Integer> haveFlows,
                Map<LocalDate, DripCsv.Temp> haveTemps, Map<LocalDate, Boolean> haveGone, LocalDate today) {
            flows = new TreeMap<LocalDate, Integer>();
            for (Map.Entry<LocalDate, Integer> e : fileFlows.entrySet()) {
                Integer f = e.getValue();
                if (f != null && f >= Cycles.SPOTTING && f <= Cycles.HEAVY) flows.put(e.getKey(), f);
            }
            temps = new TreeMap<LocalDate, DripCsv.Temp>(fileTemps);
            cycles = Cycles.of(flows, null, today);
            int bleed = 0, spot = 0;
            for (int f : flows.values()) {
                if (f >= Cycles.LIGHT) bleed++;
                else spot++;
            }
            bleedingDays = bleed;
            spottingDays = spot;
            tempCount = temps.size();
            LocalDate a = null, b = null;
            if (!flows.isEmpty()) {
                a = flows.firstKey();
                b = flows.lastKey();
            }
            if (!temps.isEmpty()) {
                if (a == null || temps.firstKey().isBefore(a)) a = temps.firstKey();
                if (b == null || temps.lastKey().isAfter(b)) b = temps.lastKey();
            }
            from = a;
            to = b;
            List<Short> s = new ArrayList<Short>();
            for (Cycles.Period p : cycles.periods) {
                if (p.days() >= SHORT) continue;
                List<Integer> am = new ArrayList<Integer>();
                for (LocalDate d = p.start; !d.isAfter(p.last); d = d.plusDays(1)) am.add(flows.get(d));
                s.add(new Short(p, Collections.unmodifiableList(am), cycles));
            }
            shorts = Collections.unmodifiableList(s);
            List<Diff> diffs = new ArrayList<Diff>();
            int add = 0, same = 0;
            for (Map.Entry<LocalDate, Integer> e : flows.entrySet()) {
                Integer have = haveFlows.get(e.getKey());
                int k = day(e.getValue(), have);
                if (k == ADD) add++;
                else if (k == SAME) same++;
                else diffs.add(new Diff(e.getKey(), have, e.getValue(), cycles));
            }
            differ = Collections.unmodifiableList(diffs);
            addDays = add;
            sameDays = same;
            int at = 0, st = 0, dt = 0;
            for (Map.Entry<LocalDate, DripCsv.Temp> e : temps.entrySet()) {
                Boolean g = haveGone.get(e.getKey());
                int k = temp(e.getValue(), haveTemps.get(e.getKey()), g != null && g);
                if (k == ADD) at++;
                else if (k == SAME) st++;
                else dt++;
            }
            addTemps = at;
            sameTemps = st;
            differTemps = dt;
        }

        /** Days the import writes: the new ones, and with {@code takeFile} the different ones too. */
        public int writes(boolean takeFile) {
            return addDays + (takeFile ? differ.size() : 0);
        }

        /** Whether "Import" has anything to save. */
        public boolean anything(boolean takeFile) {
            return writes(takeFile) + addTemps > 0;
        }

        /** "From 8 Sep 2025 to 2 Mar: 7 periods, 34 bleeding days, 12 temperatures.", the year only when it is not this year. */
        public String summary(Locale loc) {
            if (from == null) return "This file holds no bleeding day and no temperature.";
            return "From " + cycles.date(from, loc) + " to " + cycles.date(to, loc) + ": " + Texts.count(cycles.periods.size(), "period", "periods") + ", "
                    + Texts.count(bleedingDays, "bleeding day", "bleeding days") + ", " + Texts.count(tempCount, "temperature", "temperatures") + ".";
        }

        /** "Also 14 spotting days (3 of them bleeding drip did not count as a period)."; null without. */
        public String spottingLine(int excludedBleeding) {
            if (spottingDays == 0) return null;
            String s = "Also " + Texts.count(spottingDays, "spotting day", "spotting days");
            if (excludedBleeding > 0) s += " (" + excludedBleeding + " of them bleeding drip did not count as a period)";
            return s + ".";
        }

        /** "12 days differ, Open Ring's entries are kept." or, with {@code takeFile}, that the file's replace them; null when none. */
        public String differLine(boolean takeFile) {
            if (differ.isEmpty()) return null;
            String n = Texts.count(differ.size(), "day differs", "days differ");
            return takeFile ? n + ", the file's values replace Open Ring's. Undoing the import puts Open Ring's back."
                    : n + ", Open Ring's entries are kept.";
        }

        /** "12 days are the same in Open Ring and in the file."; null when none. */
        public String sameLine() {
            if (sameDays == 0) return null;
            return Texts.count(sameDays, "day is", "days are") + " the same in Open Ring and in the file.";
        }

        /** About temperatures already there; null when none. */
        public String tempsLine() {
            if (sameTemps + differTemps == 0) return null;
            String s = "";
            if (sameTemps > 0) s += Texts.count(sameTemps, "temperature is", "temperatures are") + " already in Open Ring. ";
            if (differTemps > 0) s += Texts.count(differTemps, "temperature differs", "temperatures differ") + " from an earlier import, those are kept.";
            return s.trim();
        }

        /** What the import would do: "Adds 186 days and 312 temperatures.", or that there is nothing new. */
        public String willDo(boolean takeFile) {
            if (!anything(takeFile)) return NOTHING_NEW;
            int w = writes(takeFile);
            return "Import adds " + Texts.count(w, "day", "days") + " and " + Texts.count(addTemps, "temperature", "temperatures")
                    + (takeFile && !differ.isEmpty() ? ", " + differ.size() + " of the days replacing Open Ring's" : "") + ".";
        }
    }

    public static final String NOTHING_NEW = "Everything in this file is already in Open Ring, so there is nothing to import.";

    /**
     * The preview of {@code fileFlows} and {@code fileTemps} against the phone's days ({@code haveFlows}, removal marks
     * included as {@link Cycles#REMOVED}), its temperatures ({@code haveTemps}) and which of those are undone
     * ({@code haveGone}), on {@code today}.
     */
    public static Preview preview(Map<LocalDate, Integer> fileFlows, Map<LocalDate, DripCsv.Temp> fileTemps, Map<LocalDate, Integer> haveFlows,
            Map<LocalDate, DripCsv.Temp> haveTemps, Map<LocalDate, Boolean> haveGone, LocalDate today) {
        return new Preview(fileFlows, fileTemps, haveFlows, haveTemps, haveGone, today);
    }

    /** The other period nearest to {@code p} (by days between them), or null; the Correct screen names it. */
    public static Cycles.Period nearest(Cycles c, Cycles.Period p) {
        Cycles.Period best = null;
        long gap = Long.MAX_VALUE;
        for (Cycles.Period q : c.periods) {
            if (q.start.equals(p.start)) continue;
            long g = q.start.isAfter(p.last) ? ChronoUnit.DAYS.between(p.last, q.start) : ChronoUnit.DAYS.between(q.last, p.start);
            if (g < gap) {
                gap = g;
                best = q;
            }
        }
        return best;
    }

    /**
     * "The next period starts 16 Mar 2025, 3 days after this one. Fill the days between to join them." (or "The period
     * before ends ...") for the Correct screen, dates written by {@code c} like the period cards; null without one.
     */
    public static String nearestLine(Cycles c, Cycles.Period p, Locale loc) {
        Cycles.Period q = nearest(c, p);
        if (q == null) return null;
        if (q.start.isAfter(p.last)) {
            long g = ChronoUnit.DAYS.between(p.last, q.start) - 1;
            return "The next period starts " + c.date(q.start, loc) + ", " + Texts.count(g, "day", "days") + " after this one. Fill the days between to join them.";
        }
        long g = ChronoUnit.DAYS.between(q.last, p.start) - 1;
        return "The period before ends " + c.date(q.last, loc) + ", " + Texts.count(g, "day", "days") + " before this one. Fill the days between to join them.";
    }
}
