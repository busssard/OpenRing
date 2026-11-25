package app.openring.data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.openring.core.LifeTags;
import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.FactorEffect;
import app.openring.core.analysis.LeftOut;

/**
 * Life factors phase 2 (issue #292): what {@code core/analysis/FactorEffect} needs, read once: the day summaries of the 365
 * days up to today and the 90 before them (their main night's HRV, lowest heart rate, temperature and sleep score, top level
 * fields only), the left out times, and the factors from the logged days (tags read as one factor per name, removed own tags
 * not listed) and, with the period tracker, its two factors. Reads only; nothing is stored. Blocks: call it in the background.
 * <p>
 * The result is kept in memory while nothing it reads changed (the file, the last analysis, the stored days, the left out times,
 * every factor's days, today), so moving between days on the main screen does not work it out again.
 */
public final class LifeEffects {
    private LifeEffects() {}

    private static String lastStamp;
    private static List<FactorEffect.Result> last;

    /** Each factor's result in the card's order; empty without factors. */
    public static synchronized List<FactorEffect.Result> read(Db db, LocalDate today, ZoneId zone, LifeTags.Known known,
            Map<LocalDate, List<String>> lifeDays, Cycles cycles) {
        LeftOut left = db.leftOut();
        List<FactorEffect.Factor> factors = factors(known, lifeDays, cycles, today, zone);
        String stamp = stamp(db, today, left, factors);
        if (stamp.equals(lastStamp) && last != null) return last;
        List<FactorEffect.Result> out = factors.isEmpty() ? new ArrayList<FactorEffect.Result>()
                : FactorEffect.of(nights(db, today, zone, left), factors, today);
        last = out;
        lastStamp = stamp;
        return out;
    }

    /** Forgets the kept result (tests). */
    public static synchronized void forget() {
        last = null;
        lastStamp = null;
    }

    static String stamp(Db db, LocalDate today, LeftOut left, List<FactorEffect.Factor> factors) {
        StringBuilder b = new StringBuilder(db.fileName).append('|').append(today).append('|').append(db.kv("last_analysis", "")).append('|')
                .append(db.kv("analyzed_until", "")).append('|').append(db.count("SELECT COUNT(*) FROM days")).append('|').append(left.key());
        for (FactorEffect.Factor f : factors) {
            b.append('|').append(f.key).append('=').append(f.name).append(',').append(f.from).append(',').append(days(f.days)).append(',')
                    .append(days(f.unknown));
        }
        return b.toString();
    }

    /**
     * A short text for a set of days: how many, the first and last, and the sums of their day numbers and of the squares
     * (code review of 418df41: the set's hash alone is the same for 3 and 6 as for 4 and 5 of one month).
     */
    public static String days(java.util.Set<LocalDate> s) {
        long sum = 0, sq = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (LocalDate d : s) {
            long e = d.toEpochDay();
            sum += e;
            sq += e * e;
            min = Math.min(min, e);
            max = Math.max(max, e);
        }
        return s.size() + ":" + (s.isEmpty() ? "" : min + "-" + max) + ":" + sum + ":" + sq + ":" + s.hashCode();
    }

    /**
     * The main nights by their morning from 455 days before {@code today} (the 365 compared and the 90 before for the usual),
     * from the day summaries: a day with a main night has its {@code asleep_min}, {@code rhr}, {@code hrv} or {@code temp}. A
     * night whose 18:00 the evening before to 12:00 that morning touches a left out time is marked, as for the period tracker
     * ({@code Periods.ringNights}).
     */
    static List<FactorEffect.Night> nights(Db db, LocalDate today, ZoneId zone, LeftOut left) {
        LocalDate from = today.minusDays(FactorEffect.DAYS + FactorEffect.USUAL_DAYS);
        List<FactorEffect.Night> out = new ArrayList<FactorEffect.Night>();
        for (String[] r : db.daysJson(from.toString(), today.toString())) {
            Map<String, String> o = TrendDays.fields(r[1]);
            if (o == null) continue;
            if (!o.containsKey("asleep_min") && !o.containsKey("rhr") && !o.containsKey("hrv") && !o.containsKey("temp")) continue;
            LocalDate day = BadgeDays.parse(r[0]);
            if (day == null || day.equals(LocalDate.MAX)) continue; // a damaged date (only a damaged row) is not read
            boolean out1 = !left.isEmpty() && left.overlaps(day.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond(),
                    day.atTime(12, 0).atZone(zone).toEpochSecond());
            out.add(new FactorEffect.Night(day, TrendDays.num(o.get("hrv")), TrendDays.num(o.get("rhr")), TrendDays.num(o.get("temp")),
                    TrendDays.num(o.get("sleep_score")), out1));
        }
        return out;
    }

    /**
     * The factors: every tag in force with a logged day, fixed tags first in the list order, then own tags (removed ones left
     * out, their days are kept but they are not logged any more); then the period tracker's two when {@code cycles} has a period.
     */
    static List<FactorEffect.Factor> factors(LifeTags.Known known, Map<LocalDate, List<String>> lifeDays, Cycles cycles, LocalDate today, ZoneId zone) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        Map<String, LocalDate> made = new java.util.HashMap<String, LocalDate>();
        if (known != null) {
            for (LifeTags.Tag t : known.all.values()) {
                if (t.removed) continue;
                names.put(t.key, t.name);
                if (LifeTags.isOwn(t.key)) {
                    try {
                        made.put(t.key, Instant.ofEpochMilli(Long.parseLong(t.key.substring(LifeTags.OWN.length()))).atZone(zone).toLocalDate());
                    } catch (RuntimeException e) {
                        // a key that holds no moment: counted from the first logged day
                    }
                }
            }
        }
        List<FactorEffect.Factor> out = FactorEffect.tags(lifeDays == null ? new TreeMap<LocalDate, List<String>>() : lifeDays, names, made);
        out.addAll(FactorEffect.cycle(cycles, today));
        return out;
    }
}
