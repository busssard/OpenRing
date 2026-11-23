import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.FactorEffect;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Life factors phase 2 (issue #292): how the nights went after a factor, {@code core/analysis/FactorEffect}. Made up histories
 * only (night to night noise with carry over, as the Experiment view's simulation): a strong effect is clear, no effect is not,
 * ten factors without an effect are rarely called clear, the minimums at their edges, the seed, whole weeks, the overlap,
 * several days, left out nights, missing values, the usual's minimum, the first day that counts, the period tracker's factors,
 * the texts, and the time on three years. Run with tests/run_tests.sh.
 */
public class EffectTest {
    static int passed, failed;
    static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    static final Locale L = Locale.US;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + (detail == null ? "" : "  (" + detail + ")"));
        }
    }

    // ------------------------------------------------------------------ made up histories

    /**
     * Nights for {@code days} mornings up to today: HRV around 50 ms with 20 % night to night noise and carry over (0.3 of the
     * night before), the lowest heart rate around 52, temperature 35.8, sleep score 80; a night after a day in {@code tagged}
     * gets {@code effect} (a share, -0.15 is 15 % lower HRV, and the heart rate up by 3 bpm times the share's size / 0.15).
     */
    static List<FactorEffect.Night> history(int days, Set<LocalDate> tagged, double effect, long seed) {
        Random r = new Random(seed);
        List<FactorEffect.Night> out = new ArrayList<FactorEffect.Night>();
        double carry = 0;
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = TODAY.minusDays(i);
            carry = 0.3 * carry + r.nextGaussian() * 0.2;
            boolean after = tagged.contains(day.minusDays(1));
            double hrv = 50 * (1 + carry) * (after ? 1 + effect : 1);
            double rhr = 52 + r.nextGaussian() * 2 - (after ? effect / 0.15 * 3 : 0);
            double temp = 35.8 + r.nextGaussian() * 0.15;
            double score = 80 + r.nextGaussian() * 6;
            out.add(new FactorEffect.Night(day, hrv, rhr, temp, score, false));
        }
        return out;
    }

    /** Random days with probability {@code p} over the history (the day before each morning). */
    static Set<LocalDate> random(int days, double p, long seed) {
        Random r = new Random(seed);
        Set<LocalDate> s = new HashSet<LocalDate>();
        for (int i = days; i >= 1; i--) if (r.nextDouble() < p) s.add(TODAY.minusDays(i));
        return s;
    }

    static FactorEffect.Factor tag(String key, Set<LocalDate> days, LocalDate from) {
        return new FactorEffect.Factor(key, key.substring(0, 1).toUpperCase(L) + key.substring(1), FactorEffect.TAG, true, days, from, null);
    }

    static FactorEffect.Result one(List<FactorEffect.Night> nights, FactorEffect.Factor f) {
        List<FactorEffect.Factor> l = new ArrayList<FactorEffect.Factor>();
        l.add(f);
        return FactorEffect.of(nights, l, TODAY).get(0);
    }

    static final LocalDate START = TODAY.minusDays(500);

    // ------------------------------------------------------------------ the tests

    static void strongEffect() {
        Set<LocalDate> t = random(455, 0.2, 1);
        FactorEffect.Result r = one(history(455, t, -0.15, 2), tag("alcohol", t, START));
        FactorEffect.Measure m = r.m[FactorEffect.HRV];
        check("strong: a number", r.state == FactorEffect.NUMBER, r.state);
        check("strong: HRV is the headline", r.headline == FactorEffect.HRV, r.headline);
        check("strong: clear", r.clear(), m.lo + " " + m.hi);
        check("strong: about 15 % lower", m.diff < -9 && m.diff > -21, m.diff);
        check("strong: the range holds the difference", m.lo < m.diff && m.diff < m.hi, null);
        check("strong: the lowest heart rate higher and clear", r.m[FactorEffect.RHR].clear() && r.m[FactorEffect.RHR].diff > 1.5, r.m[FactorEffect.RHR].diff);
        check("strong: temperature and sleep score not clear (no effect made up there)",
                !r.m[FactorEffect.TEMP].clear() && !r.m[FactorEffect.SCORE].clear(), null);
        String h = FactorEffect.headline(r, L);
        check("strong: the clear sentence", h.startsWith("After alcohol your HRV was ") && h.contains("% lower the next night (") && h.contains(", somewhere between ")
                && h.endsWith("This is a pattern in your own data, not a general rule."), h);
        check("strong: the status", FactorEffect.status(r, L).startsWith("HRV ") && FactorEffect.status(r, L).contains("lower, clear ("), FactorEffect.status(r, L));
        check("strong: the detail line", FactorEffect.detail(r, FactorEffect.RHR, L).startsWith("Lowest heart rate: ")
                && FactorEffect.detail(r, FactorEffect.RHR, L).endsWith("higher. Clear."), FactorEffect.detail(r, FactorEffect.RHR, L));
        check("strong: nights only from the 365 days", r.nWith + r.nWithout <= FactorEffect.DAYS, r.nWith + r.nWithout);
    }

    static void noEffect() {
        Set<LocalDate> t = random(455, 0.2, 3);
        FactorEffect.Result r = one(history(455, t, 0, 4), tag("meditation", t, START));
        check("none: a number", r.state == FactorEffect.NUMBER, r.state);
        check("none: not clear", !r.clear(), r.m[0].lo + " " + r.m[0].hi);
        String h = FactorEffect.headline(r, L);
        check("none: the not clear sentence", h.contains("It could be anything from ") && h.endsWith(", so this is not clear yet."), h);
        check("none: the status", FactorEffect.status(r, L).endsWith(")") && FactorEffect.status(r, L).contains("not clear yet"), FactorEffect.status(r, L));
    }

    /** The Design review: ten factors without an effect, how often one is wrongly clear (at 99 %, about 1 check in 100). */
    static void falseAlarms() {
        int checks = 0, clear = 0, historiesWithOne = 0;
        for (int h = 0; h < 60; h++) {
            List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 1000 + h);
            List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
            for (int k = 0; k < 10; k++) fs.add(tag("f" + k, random(455, 0.05 + 0.03 * k, 5000 + h * 10 + k), START));
            boolean any = false;
            for (FactorEffect.Result r : FactorEffect.of(nights, fs, TODAY)) {
                if (r.state != FactorEffect.NUMBER) continue;
                checks++;
                if (r.clear()) {
                    clear++;
                    any = true;
                }
            }
            if (any) historiesWithOne++;
        }
        System.out.println("  no effect: " + clear + " of " + checks + " headline checks clear, " + historiesWithOne + " of 60 histories with one");
        check("ten factors without an effect: at most about 3 in 100 headline checks clear", clear <= Math.max(2, checks * 0.03), clear + " of " + checks);
        check("ten factors without an effect: most histories have none clear", historiesWithOne <= 15, historiesWithOne);
    }

    /** A 10 % effect with about 60 tagged nights is found most of the time (the Experiment view: 86 % at 95 %). */
    static void power() {
        int found = 0, n = 40;
        for (int h = 0; h < n; h++) {
            Set<LocalDate> t = random(455, 0.17, 700 + h);
            if (one(history(455, t, -0.10, 800 + h), tag("x", t, START)).clear()) found++;
        }
        System.out.println("  a 10 % effect: found " + found + " of " + n);
        check("power: a 10 % effect with about 60 nights is found more often than not", found > n / 2, found);
    }

    static Set<LocalDate> every(int step, int count, int firstBack) {
        Set<LocalDate> s = new HashSet<LocalDate>();
        for (int i = 0; i < count; i++) s.add(TODAY.minusDays(firstBack + i * step));
        return s;
    }

    static void minimums() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 9);
        FactorEffect.Result r9 = one(nights, tag("a", every(7, 9, 2), START));
        check("min: 9 nights with: the count only", r9.state == FactorEffect.FEW_WITH && r9.nWith == 9 && r9.headline == -1, r9.state + " " + r9.nWith);
        check("min: its text", FactorEffect.headline(r9, L).equals("9 of 10 days logged with A that have a ring night after them. The effect shows from 10."), FactorEffect.headline(r9, L));
        check("min: its status", FactorEffect.status(r9, L).equals("9 of 10 days so far"), FactorEffect.status(r9, L));
        FactorEffect.Result r10 = one(nights, tag("a", every(7, 10, 2), START));
        check("min: 10 nights with: a number", r10.state == FactorEffect.NUMBER && r10.nWith == 10, r10.state);
        // the without side: a factor on every day but 29 (or 30) of the counted days
        LocalDate from = TODAY.minusDays(200);
        Set<LocalDate> almost = new HashSet<LocalDate>();
        for (LocalDate d = from; d.isBefore(TODAY); d = d.plusDays(1)) almost.add(d);
        Set<LocalDate> a29 = new HashSet<LocalDate>(almost), a30 = new HashSet<LocalDate>(almost);
        for (int i = 0; i < 30; i++) {
            if (i < 29) a29.remove(from.plusDays(3 + i * 6));
            a30.remove(from.plusDays(3 + i * 6));
        }
        FactorEffect.Result w29 = one(nights, tag("b", a29, from)), w30 = one(nights, tag("b", a30, from));
        check("min: 29 nights without: the count only", w29.state == FactorEffect.FEW_WITHOUT && w29.nWithout == 29, w29.state + " " + w29.nWithout);
        check("min: its text", FactorEffect.headline(w29, L).equals("B is on almost every day: 29 of the 30 nights without it that are needed so far."), FactorEffect.headline(w29, L));
        check("min: 30 nights without: a number", w30.state == FactorEffect.NUMBER && w30.nWithout == 30, w30.state + " " + w30.nWithout);
        // ten nights in one calendar week cannot be: a week has 7; 7 nights of one week plus none elsewhere is too few, so the
        // one week rule is tested with 12 nights in two weeks against 10 in one (two weeks of a holiday, Monday to Sunday)
        LocalDate monday = TODAY.minusDays(60);
        while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) monday = monday.minusDays(1);
        Set<LocalDate> oneWeek = new HashSet<LocalDate>();
        for (int i = 0; i < 7; i++) oneWeek.add(monday.plusDays(i));
        FactorEffect.Result ow = one(nights, tag("c", oneWeek, START));
        check("min: 7 nights of one week: too few", ow.state == FactorEffect.FEW_WITH, ow.state);
        check("min: one week counted", ow.weeksWith == 1, ow.weeksWith);
    }

    /** A week has 7 nights, so 10 with always lie in 2 weeks; the range itself still needs 2 (one week of 7 gives none). */
    static void oneWeekRule() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 19);
        LocalDate monday = TODAY.minusDays(40);
        while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) monday = monday.minusDays(1);
        Set<LocalDate> w = new HashSet<LocalDate>();
        for (int i = 0; i < 7; i++) w.add(monday.plusDays(i));
        FactorEffect.Result r = one(nights, tag("d", w, START));
        check("one week: no range from one week, the difference only", !r.m[0].enough() && !Double.isNaN(r.m[0].diff), r.m[0].lo);
        check("one week: 10 nights with always lie in at least 2 weeks", FactorEffect.MIN_WITH > 7 && FactorEffect.MIN_WEEKS == 2, null);
    }

    static void seed() {
        Set<LocalDate> t = random(455, 0.2, 11);
        List<FactorEffect.Night> nights = history(455, t, -0.05, 12);
        FactorEffect.Result a = one(nights, tag("x", t, START)), b = one(nights, tag("x", t, START));
        check("seed: the same history gives the same range, bit for bit", Double.doubleToLongBits(a.m[0].lo) == Double.doubleToLongBits(b.m[0].lo)
                && Double.doubleToLongBits(a.m[0].hi) == Double.doubleToLongBits(b.m[0].hi), null);
        check("seed: the same text", FactorEffect.headline(a, L).equals(FactorEffect.headline(b, L)), null);
        List<FactorEffect.Night> changed = new ArrayList<FactorEffect.Night>(nights);
        FactorEffect.Night last = changed.get(changed.size() - 5);
        changed.set(changed.size() - 5, new FactorEffect.Night(last.day, last.v[0] + 0.001, last.v[1], last.v[2], last.v[3], false));
        FactorEffect.Result c = one(changed, tag("x", t, START));
        check("seed: a changed value draws other weeks", Double.doubleToLongBits(a.m[0].lo - a.m[0].diff) != Double.doubleToLongBits(c.m[0].lo - c.m[0].diff), null);
        FactorEffect.Result d = one(nights, tag("y", t, START));
        check("seed: another factor with the same days draws its own weeks", Double.doubleToLongBits(a.m[0].lo) != Double.doubleToLongBits(d.m[0].lo), null);
    }

    /** Whole weeks: 14 nights as two whole weeks give a wider range than 14 nights spread over the year. */
    static void weeks() {
        double wideSum = 0, spreadSum = 0;
        int n = 20;
        for (int h = 0; h < n; h++) {
            List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 3000 + h);
            LocalDate monday = TODAY.minusDays(100 + h);
            while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) monday = monday.minusDays(1);
            Set<LocalDate> holiday = new HashSet<LocalDate>();
            for (int i = 0; i < 14; i++) holiday.add(monday.plusDays(i));
            FactorEffect.Result w = one(nights, tag("holiday", holiday, START)), s = one(nights, tag("spread", every(23, 14, 5), START));
            wideSum += w.m[0].hi - w.m[0].lo;
            spreadSum += s.m[0].hi - s.m[0].lo;
        }
        check("weeks: two whole weeks give a much wider range than nights spread out (t for 1 week of freedom)", wideSum > 3 * spreadSum, wideSum / n + " " + spreadSum / n);
    }

    static void overlap() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 21);
        Set<LocalDate> a = every(9, 20, 2);
        List<LocalDate> al = new ArrayList<LocalDate>(new java.util.TreeSet<LocalDate>(a));
        Set<LocalDate> half = new HashSet<LocalDate>(al.subList(0, 10)), more = new HashSet<LocalDate>(al.subList(0, 11));
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        fs.add(tag("alcohol", a, START));
        fs.add(tag("friends", more, START));
        FactorEffect.Result r = find(FactorEffect.of(nights, fs, TODAY), "alcohol");
        check("overlap: a tag on 11 of 20 nights is named", "Friends".equals(r.overlap), r.overlap);
        check("overlap: its line", "Often logged together with Friends, so the effect may belong to either.".equals(FactorEffect.overlapLine(r)), FactorEffect.overlapLine(r));
        fs.set(1, tag("friends", half, START));
        r = find(FactorEffect.of(nights, fs, TODAY), "alcohol");
        check("overlap: on exactly half, none", r.overlap == null && FactorEffect.overlapLine(r) == null, r.overlap);
        // the period tracker's factors are not part of the check
        fs.set(1, new FactorEffect.Factor(FactorEffect.KEY_OVULATION, FactorEffect.OVULATION_NAME, FactorEffect.AFTER_OVULATION, false, a, START, null));
        r = find(FactorEffect.of(nights, fs, TODAY), "alcohol");
        check("overlap: a cycle factor on every one of its nights is not named", r.overlap == null, r.overlap);
    }

    static FactorEffect.Result find(List<FactorEffect.Result> l, String key) {
        for (FactorEffect.Result r : l) if (r.factor.key.equals(key)) return r;
        return null;
    }

    static void severalDays() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 31);
        Set<LocalDate> s = new HashSet<LocalDate>();
        for (int i = 0; i < 8; i++) s.add(TODAY.minusDays(50 + i));
        for (int i = 0; i < 6; i++) s.add(TODAY.minusDays(150 + i));
        FactorEffect.Result r = one(nights, tag("holiday", s, START));
        check("several days: every day of both stretches counts", r.nWith == 14, r.nWith);
        check("several days: in at least 2 weeks", r.weeksWith >= 2, r.weeksWith);
        // a tag of today has no night after it yet
        Set<LocalDate> today = new HashSet<LocalDate>(s);
        today.add(TODAY);
        check("several days: today's tag waits for tonight", one(nights, tag("holiday", today, START)).nWith == 14, null);
    }

    static void leftOutAndMissing() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 41);
        Set<LocalDate> t = every(5, 40, 2);
        FactorEffect.Result before = one(nights, tag("x", t, START));
        // the night after the newest tagged day is left out: it is no point, the others keep their usual
        List<FactorEffect.Night> out = new ArrayList<FactorEffect.Night>();
        LocalDate gone = TODAY.minusDays(1);
        for (FactorEffect.Night n : nights) out.add(n.day.equals(gone) ? new FactorEffect.Night(n.day, n.v[0], n.v[1], n.v[2], n.v[3], true) : n);
        FactorEffect.Result after = one(out, tag("x", t, START));
        check("left out: the night is skipped", after.nWith == before.nWith - 1, before.nWith + " " + after.nWith);
        boolean listed = false;
        for (FactorEffect.Point p : after.points) listed |= p.tagDay.equals(gone.minusDays(1));
        check("left out: not among the points", !listed, null);
        // missing values: no HRV at all, the lowest heart rate is the headline
        List<FactorEffect.Night> noHrv = new ArrayList<FactorEffect.Night>();
        for (FactorEffect.Night n : nights) noHrv.add(new FactorEffect.Night(n.day, Double.NaN, n.v[1], n.v[2], n.v[3], false));
        FactorEffect.Result r = one(noHrv, tag("x", t, START));
        check("missing: without HRV the lowest heart rate is the headline", r.headline == FactorEffect.RHR, r.headline);
        check("missing: its detail line", FactorEffect.detail(r, FactorEffect.HRV, L).equals("HRV: too few nights with a value."), FactorEffect.detail(r, FactorEffect.HRV, L));
        check("missing: its sentence", FactorEffect.headline(r, L).startsWith("After x your lowest heart rate was "), FactorEffect.headline(r, L));
        // nothing at all: too few values
        List<FactorEffect.Night> none = new ArrayList<FactorEffect.Night>();
        for (FactorEffect.Night n : nights) none.add(new FactorEffect.Night(n.day, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false));
        FactorEffect.Result e = one(none, tag("x", t, START));
        check("missing: nights without any value count for nothing", e.nWith == 0 && e.state == FactorEffect.FEW_WITH, e.state);
    }

    static void usual() {
        List<FactorEffect.Night> n = new ArrayList<FactorEffect.Night>();
        for (int i = 0; i < 5; i++) n.add(new FactorEffect.Night(TODAY.minusDays(4 - i), 40 + 10 * i, 50, 35.8, 80, false));
        List<FactorEffect.Point> p = FactorEffect.points(n, TODAY);
        check("usual: none for the first 3 nights (fewer than 3 before)", Double.isNaN(p.get(0).usual[0]) && Double.isNaN(p.get(2).usual[0]), null);
        check("usual: the median of the 3 before for the 4th", p.get(3).usual[0] == 50, p.get(3).usual[0]);
        check("usual: HRV in % of it", Math.abs(p.get(3).diff[0] - 40) < 1e-9, p.get(3).diff[0]);
        check("usual: the others in their unit", p.get(4).diff[FactorEffect.RHR] == 0 && Math.abs(p.get(4).diff[FactorEffect.TEMP]) < 1e-9, null);
        // only main nights of the 90 days before count
        List<FactorEffect.Night> far = new ArrayList<FactorEffect.Night>();
        for (int i = 0; i < 3; i++) far.add(new FactorEffect.Night(TODAY.minusDays(200 + i), 30, 50, 35.8, 80, false));
        far.add(new FactorEffect.Night(TODAY, 60, 50, 35.8, 80, false));
        check("usual: nights more than 90 days before do not count", Double.isNaN(FactorEffect.points(far, TODAY).get(0).usual[0]), null);
        check("usual: the minimum is FactorEffect.USUAL_MIN", FactorEffect.USUAL_MIN == 3, null);
        // a value against a usual of 0 or missing is no difference
        check("usual: no HRV % against 0", Double.isNaN(FactorEffect.difference(FactorEffect.HRV, 40, 0)), null);
        check("usual: none without a value", Double.isNaN(FactorEffect.difference(FactorEffect.RHR, Double.NaN, 50)), null);
    }

    static void firstDay() {
        Map<LocalDate, List<String>> days = new TreeMap<LocalDate, List<String>>();
        LocalDate first = TODAY.minusDays(100);
        days.put(first, Arrays.asList("meditation"));
        days.put(TODAY.minusDays(40), Arrays.asList("own:1", "meditation"));
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("meditation", "Meditation");
        names.put("alcohol", "Alcohol");
        names.put("own:1", "Late dinner");
        Map<String, LocalDate> made = new java.util.HashMap<String, LocalDate>();
        made.put("own:1", TODAY.minusDays(50));
        List<FactorEffect.Factor> fs = FactorEffect.tags(days, names, made, Arrays.asList("meditation", "alcohol"));
        check("first day: only factors with a logged day", fs.size() == 2 && fs.get(0).key.equals("meditation") && fs.get(1).key.equals("own:1"), fs.size());
        check("first day: a fixed tag counts from the first day anything was logged", fs.get(0).from.equals(first), fs.get(0).from);
        check("first day: an own tag from the day it was made", fs.get(1).from.equals(TODAY.minusDays(50)), fs.get(1).from);
        check("first day: fixed names in small letters in a sentence, own names as typed",
                FactorEffect.lead(fs.get(0)).equals("After meditation") && FactorEffect.lead(fs.get(1)).equals("After Late dinner"), null);
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 51);
        FactorEffect.Result r = one(nights, fs.get(0));
        check("first day: nights before the first logged day are not without", r.nWithout <= 100, r.nWithout);
        // an own tag whose key says it was made after a logged day (a clock set wrong): that day still counts
        made.put("own:1", TODAY.minusDays(10));
        fs = FactorEffect.tags(days, names, made, Arrays.asList("meditation"));
        check("first day: never after a logged day of the tag", fs.get(1).from.equals(TODAY.minusDays(40)), fs.get(1).from);
        check("first day: no factor without a logged day", FactorEffect.tags(new TreeMap<LocalDate, List<String>>(), names, made, null).isEmpty(), null);
    }

    static void cycle() {
        LocalDate today = TODAY;
        Cycles c = Cycles.of(Cycles.demoHistory(today), null, today);
        List<FactorEffect.Factor> fs = FactorEffect.cycle(c, today);
        check("cycle: two factors", fs.size() == 2 && fs.get(0).kind == FactorEffect.PERIOD && fs.get(1).kind == FactorEffect.AFTER_OVULATION, fs.size());
        FactorEffect.Factor per = fs.get(0), ov = fs.get(1);
        check("cycle: period days are the logged ones", !per.days.isEmpty() && c.periodOn(per.days.iterator().next()) != null, per.days.size());
        boolean apart = true;
        for (LocalDate d : ov.days) apart &= !per.days.contains(d);
        check("cycle: no day both a period day and after ovulation", apart, null);
        check("cycle: about 14 days after ovulation in each cycle that is over", ov.days.size() >= 10 * (c.periods.size() - 1) && ov.days.size() <= 16 * c.periods.size(),
                ov.days.size() + " in " + c.periods.size());
        LocalDate lastStart = c.periods.get(c.periods.size() - 1).start;
        boolean runningUnsure = true;
        for (LocalDate d = lastStart; !d.isAfter(today); d = d.plusDays(1)) {
            if (c.periodOn(d) == null && !c.phase(d).endsWith("(measured)")) runningUnsure &= ov.unknown.contains(d);
        }
        check("cycle: the running cycle without a measured rise is unknown", runningUnsure, null);
        check("cycle: both count from the first period", per.from.equals(c.periods.get(0).start) && ov.from.equals(per.from), null);
        check("cycle: none without a period", FactorEffect.cycle(null, today).isEmpty(), null);
        check("cycle: leads", FactorEffect.lead(ov).equals("In the nights after ovulation") && FactorEffect.lead(per).equals("On period days"), null);
        // a warmer second half (made up) shows on the temperature
        List<FactorEffect.Night> nights = new ArrayList<FactorEffect.Night>();
        Random r = new Random(61);
        for (int i = 454; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            double temp = 35.8 + r.nextGaussian() * 0.08 + (ov.days.contains(day.minusDays(1)) ? 0.3 : 0);
            nights.add(new FactorEffect.Night(day, 50 * (1 + r.nextGaussian() * 0.2), 52 + r.nextGaussian() * 2, temp, 80 + r.nextGaussian() * 6, false));
        }
        FactorEffect.Result res = find(FactorEffect.of(nights, fs, today), FactorEffect.KEY_OVULATION);
        check("cycle: the warmer nights after ovulation are clear on the temperature", res.m[FactorEffect.TEMP].clear() && res.m[FactorEffect.TEMP].diff > 0.15,
                res.m[FactorEffect.TEMP].diff + " " + res.m[FactorEffect.TEMP].lo);
        check("cycle: its sentence has no next night", res.state != FactorEffect.NUMBER || !FactorEffect.headline(res, L).contains("next night"), FactorEffect.headline(res, L));
    }

    static void order() {
        List<FactorEffect.Night> nights;
        Set<LocalDate> strong = random(455, 0.2, 71);
        nights = history(455, strong, -0.2, 72);
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        fs.add(tag("few", every(30, 4, 3), START));
        fs.add(tag("none", random(455, 0.2, 73), START));
        fs.add(tag("strong", strong, START));
        List<FactorEffect.Result> l = FactorEffect.of(nights, fs, TODAY);
        check("order: clear, then not clear yet, then too few", l.get(0).factor.key.equals("strong") && l.get(1).factor.key.equals("none") && l.get(2).factor.key.equals("few"),
                l.get(0).factor.key + " " + l.get(1).factor.key + " " + l.get(2).factor.key);
    }

    static void texts() {
        List<String> all = new ArrayList<String>(Arrays.asList(FactorEffect.texts()));
        Set<LocalDate> t = random(455, 0.2, 81);
        List<FactorEffect.Night> nights = history(455, t, -0.15, 82);
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        fs.add(tag("alcohol", t, START));
        fs.add(tag("meditation", random(455, 0.2, 83), START));
        fs.add(tag("rare", every(40, 3, 3), START));
        for (FactorEffect.Result r : FactorEffect.of(nights, fs, TODAY)) {
            all.add(FactorEffect.headline(r, L));
            all.add(FactorEffect.status(r, L));
            for (int k = 0; k < FactorEffect.MEASURES; k++) {
                all.add(FactorEffect.detail(r, k, L));
                for (int i = 0; i < Math.min(5, r.points.size()); i++) all.add(FactorEffect.pointText(r, i, k, TODAY, L));
            }
        }
        boolean plain = true;
        for (String s : all) plain &= !s.contains(" - ") && !s.contains("—") && !s.contains("–") && !s.contains("--");
        check("texts: no dashes as punctuation in " + all.size() + " texts", plain, null);
        FactorEffect.Result r = find(FactorEffect.of(nights, fs, TODAY), "alcohol");
        String p = FactorEffect.pointText(r, r.points.size() - 1, FactorEffect.HRV, TODAY, L);
        check("texts: the touch readout", p.startsWith("Night after ") && p.contains("\nHRV ") && p.contains(" ms, your usual "), p);
        check("texts: amounts", FactorEffect.amount(FactorEffect.HRV, -12.4, L).equals("12 %") && FactorEffect.amount(FactorEffect.RHR, 2.06, L).equals("2.1 bpm")
                && FactorEffect.amount(FactorEffect.TEMP, 0.051, L).equals("0.05 °C") && FactorEffect.amount(FactorEffect.SCORE, 1, L).equals("1 point"), null);
        check("texts: the axis is signed", FactorEffect.axis(FactorEffect.HRV, 12, L).equals("+12 %") && FactorEffect.axis(FactorEffect.SCORE, -3, L).equals("−3"),
                FactorEffect.axis(FactorEffect.SCORE, -3, L));
    }

    /** Three years of nights and 15 factors (11 fixed, 2 own, 2 of the cycle) in well under a second, pure Java. */
    static void time() {
        int days = 3 * 365;
        List<FactorEffect.Night> nights = history(days, new HashSet<LocalDate>(), 0, 91);
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        for (int k = 0; k < 15; k++) fs.add(tag("t" + k, random(days, 0.05 + 0.04 * k, 900 + k), TODAY.minusDays(days)));
        FactorEffect.of(nights, fs, TODAY); // warm up
        long t0 = System.nanoTime();
        List<FactorEffect.Result> r = FactorEffect.of(nights, fs, TODAY);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println("  3 years, 15 factors: " + Math.round(ms) + " ms");
        check("time: 3 years and 15 factors under 500 ms", ms < 500, ms);
        check("time: every factor has a result", r.size() == 15, r.size());
    }

    /** The demo's made up year of tags (LifeTags.demoYear): never Intimacy, Alcohol only on Friday and Saturday, the illness week as Illness. */
    static void demoYear() {
        LocalDate from = TODAY.minusDays(370), to = TODAY.minusDays(37);
        Map<LocalDate, Double> h = new java.util.HashMap<LocalDate, Double>();
        Random r = new Random(5);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) h.put(d, r.nextGaussian() * 8);
        Set<LocalDate> ill = new HashSet<LocalDate>();
        for (int i = 0; i < 7; i++) ill.add(TODAY.minusDays(200 + i));
        TreeMap<LocalDate, List<String>> y = app.openring.core.LifeTags.demoYear(from, to, h, ill);
        boolean ok = true, illOk = true;
        int alcohol = 0;
        for (Map.Entry<LocalDate, List<String>> e : y.entrySet()) {
            ok &= !e.getValue().contains(app.openring.core.LifeTags.INTIMACY) && !e.getKey().isBefore(from) && !e.getKey().isAfter(to);
            if (e.getValue().contains(app.openring.core.LifeTags.ALCOHOL)) {
                alcohol++;
                int dow = e.getKey().getDayOfWeek().getValue();
                ok &= (dow == 5 || dow == 6) && !ill.contains(e.getKey());
            }
            illOk &= e.getValue().contains(app.openring.core.LifeTags.ILLNESS) == ill.contains(e.getKey());
        }
        check("demo year: never Intimacy, inside the year, Alcohol only on Friday and Saturday evenings", ok && alcohol >= 20, alcohol);
        check("demo year: Illness exactly on the week of illness", illOk, null);
        check("demo year: the same input gives the same tags", y.equals(app.openring.core.LifeTags.demoYear(from, to, h, ill)), null);
    }

    public static void main(String[] args) {
        strongEffect();
        noEffect();
        falseAlarms();
        power();
        minimums();
        oneWeekRule();
        seed();
        weeks();
        overlap();
        severalDays();
        leftOutAndMissing();
        usual();
        firstDay();
        cycle();
        order();
        texts();
        demoYear();
        time();
        System.out.println("EffectTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
