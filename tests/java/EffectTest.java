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
        return new FactorEffect.Factor(key, key.substring(0, 1).toUpperCase(L) + key.substring(1), FactorEffect.TAG, days, from, null);
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
        check("strong: the clear sentence", h.startsWith("After a day with Alcohol, your HRV was ") && h.contains("% lower that night (") && h.contains(" nights after Alcohol, ")
                && h.contains(" without), somewhere between ")
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
        // at 99 % about 6 of some 600 checks; 2 in 100 leaves room for chance (12 or more happens about 1 time in 100 then)
        check("ten factors without an effect: at most 2 in 100 headline checks clear", clear <= Math.max(2, checks * 0.02), clear + " of " + checks);
        check("ten factors without an effect: most histories have none clear", historiesWithOne <= 12, historiesWithOne);
        // code review of 418df41: a factor on almost every day, its 40 nights without in 4 stretches of 10 days, no effect. The
        // range must take its t and correction from the side with fewer weeks (here the nights without), or it is too narrow
        int n = 300, almost = 0;
        for (int h = 0; h < n; h++) {
            List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 20000 + h);
            Set<LocalDate> on = new HashSet<LocalDate>();
            for (int i = 1; i <= 365; i++) on.add(TODAY.minusDays(i));
            Random r = new Random(30000 + h);
            for (int st = 0; st < 4; st++) {
                int start = 5 + st * 85 + r.nextInt(70);
                for (int i = 0; i < 10; i++) on.remove(TODAY.minusDays(start + i));
            }
            FactorEffect.Result res = one(nights, tag("daily", on, TODAY.minusDays(365)));
            if (res.state == FactorEffect.NUMBER && res.clear()) almost++;
        }
        System.out.println("  almost every day, no effect: " + almost + " of " + n + " clear");
        // at 99 % about 3 of 300; 8 or more would happen about 1 time in 100 by chance (before the fix it was 9 to 14)
        check("a factor on almost every day without an effect: at most 7 of 300 clear", almost <= 7, almost);
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
        fs.set(1, new FactorEffect.Factor(FactorEffect.KEY_OVULATION, FactorEffect.OVULATION_NAME, FactorEffect.AFTER_OVULATION, a, START, null));
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
        check("missing: its sentence", FactorEffect.headline(r, L).startsWith("After a day with X, your lowest heart rate was "), FactorEffect.headline(r, L));
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
        List<FactorEffect.Factor> fs = FactorEffect.tags(days, names, made);
        check("first day: only factors with a logged day", fs.size() == 2 && fs.get(0).key.equals("meditation") && fs.get(1).key.equals("own:1"), fs.size());
        check("first day: a fixed tag counts from the first day anything was logged", fs.get(0).from.equals(first), fs.get(0).from);
        check("first day: an own tag from the day it was made", fs.get(1).from.equals(TODAY.minusDays(50)), fs.get(1).from);
        check("first day: one pattern for every tag, the name as shown",
                FactorEffect.lead(fs.get(0)).equals("After a day with Meditation") && FactorEffect.lead(fs.get(1)).equals("After a day with Late dinner"), null);
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 51);
        FactorEffect.Result r = one(nights, fs.get(0));
        check("first day: nights before the first logged day are not without", r.nWithout <= 100, r.nWithout);
        // an own tag whose key says it was made after a logged day (a clock set wrong): that day still counts
        made.put("own:1", TODAY.minusDays(10));
        fs = FactorEffect.tags(days, names, made);
        check("first day: never after a logged day of the tag", fs.get(1).from.equals(TODAY.minusDays(40)), fs.get(1).from);
        check("first day: no factor without a logged day", FactorEffect.tags(new TreeMap<LocalDate, List<String>>(), names, made).isEmpty(), null);
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
        LocalDate window = today.minusDays(FactorEffect.DAYS + 1);
        int inWindow = 0;
        for (Cycles.Period p : c.periods) if (!p.start.isBefore(window)) inWindow++;
        check("cycle: about 14 days after ovulation in each cycle that is over, in the 365 days", ov.days.size() >= 10 * (inWindow - 1)
                && ov.days.size() <= 16 * (inWindow + 1), ov.days.size() + " in " + inWindow);
        boolean early = false;
        for (LocalDate d : ov.days) early |= d.isBefore(window);
        for (LocalDate d : per.days) early |= d.isBefore(window);
        check("cycle: no day worked out before the 365 days and the day before them (code review of 418df41)", !early, null);
        LocalDate lastStart = c.periods.get(c.periods.size() - 1).start;
        boolean runningUnsure = true;
        for (LocalDate d = lastStart; !d.isAfter(today); d = d.plusDays(1)) {
            if (c.periodOn(d) == null && !c.phase(d).endsWith("(measured)")) runningUnsure &= ov.unknown.contains(d);
        }
        check("cycle: the running cycle without a measured rise is unknown", runningUnsure, null);
        LocalDate first = c.periods.get(0).start;
        check("cycle: both count from the first period, at the earliest the day before the 365 days",
                per.from.equals(first.isBefore(window) ? window : first) && ov.from.equals(per.from), per.from);
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
        check("cycle: its sentence has no that night", res.state != FactorEffect.NUMBER || !FactorEffect.headline(res, L).contains("that night"), FactorEffect.headline(res, L));
    }

    /** Code review of 418df41: a factor whose days all lie before the 365 days shows no row ("0 of 10 days so far" said nothing). */
    static void oldFactors() {
        List<FactorEffect.Night> nights = history(455, new HashSet<LocalDate>(), 0, 95);
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        fs.add(tag("old", every(3, 10, 370), START));
        fs.add(tag("edge", every(3, 1, 365), START));
        fs.add(tag("today", every(1, 1, 0), START));
        List<FactorEffect.Result> l = FactorEffect.of(nights, fs, TODAY);
        check("old factors: only logged before the 365 days, no row", find(l, "old") == null, l.size());
        check("old factors: a day 365 days ago still counts as in the window", find(l, "edge") != null, null);
        check("old factors: logged only today has its row (its night is still to come)", find(l, "today") != null && find(l, "today").nWith == 0, null);
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
        fs.addAll(FactorEffect.cycle(Cycles.of(Cycles.demoHistory(TODAY), null, TODAY), TODAY));
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
        check("texts: the touch readout", p.startsWith("Night after ") && (p.contains(", after a day with Alcohol\nHRV ") || p.contains(", after a day without Alcohol\nHRV ")) && p.contains(" ms, your usual "), p);
        List<FactorEffect.Result> all2 = FactorEffect.of(nights, fs, TODAY);
        FactorEffect.Result ov = find(all2, FactorEffect.KEY_OVULATION), per = find(all2, FactorEffect.KEY_PERIOD);
        boolean ovOk = true, perOk = true;
        for (int i = 0; i < ov.points.size(); i++) {
            String pt = FactorEffect.pointText(ov, i, FactorEffect.TEMP, TODAY, L);
            ovOk &= pt.contains(ov.with.get(i) ? ", after ovulation\n" : ", before ovulation or a period day\n") && !pt.contains("After ovulation");
        }
        for (int i = 0; i < per.points.size(); i++) {
            String pt = FactorEffect.pointText(per, i, FactorEffect.HRV, TODAY, L);
            perOk &= pt.contains(per.with.get(i) ? ", a period day\n" : ", not a period day\n") && !pt.contains("Period days");
        }
        check("texts: the touch readout of the cycle factors in words of their own", ovOk && perOk, null);
        String hp = FactorEffect.headline(per, L), ho = FactorEffect.headline(ov, L);
        check("texts: the cycle factors' counts", per.state != FactorEffect.NUMBER || hp.startsWith("On period days, your ") && hp.contains(" period days, ") && hp.contains(" other days)"), hp);
        check("texts: after ovulation's counts", ov.state != FactorEffect.NUMBER || ho.startsWith("In the nights after ovulation, your ") && ho.contains(" nights after ovulation, ")
                && ho.contains(" other nights)") && !ho.contains("that night"), ho);
        check("texts: amounts", FactorEffect.amount(FactorEffect.HRV, -12.4, L).equals("12 %") && FactorEffect.amount(FactorEffect.RHR, 2.06, L).equals("2.1 bpm")
                && FactorEffect.amount(FactorEffect.TEMP, 0.051, L).equals("0.05 °C") && FactorEffect.amount(FactorEffect.SCORE, 1, L).equals("1 point"), null);
        check("texts: the axis is signed", FactorEffect.axis(FactorEffect.HRV, 12, L).equals("+12 %") && FactorEffect.axis(FactorEffect.SCORE, -3, L).equals("−3"),
                FactorEffect.axis(FactorEffect.SCORE, -3, L));
    }

    /**
     * Issue #474: an end of a not clear range that shows as 0 is "about the same" (it said "no change to 2 % higher", which read
     * like an increase), both ends at 0 say so once, and the touch readout's usual has its unit.
     */
    static void rangeTexts() {
        int H = FactorEffect.HRV, R = FactorEffect.RHR, T = FactorEffect.TEMP, S = FactorEffect.SCORE;
        String a = FactorEffect.unclear(H, -0.3, 2.2, L);
        check("range 0: the low end is about the same", a.equals("It could be anything from about the same to 2 % higher, so this is not clear yet."), a);
        String b = FactorEffect.unclear(R, -1.24, 0.04, L);
        check("range 0: the high end is about the same", b.equals("It could be anything from 1.2 bpm lower to about the same, so this is not clear yet."), b);
        String c = FactorEffect.unclear(H, -1.4, 2.2, L);
        check("range: both ends with a size stay as they were", c.equals("It could be anything from 1 % lower to 2 % higher, so this is not clear yet."), c);
        String d = FactorEffect.unclear(S, -0.4, 0.3, L);
        check("range: both ends at 0 say it once", d.equals("Its whole range is about the same, so this is not clear yet."), d);
        String e = FactorEffect.unclearDetail(T, -0.002, 0.12, L);
        check("range 0: the detail line", e.equals(", could be about the same to 0.12 warmer. Not clear yet."), e);
        String f = FactorEffect.unclearDetail(R, -0.8, 1.24, L);
        check("range: the detail line with both ends", f.equals(", could be 0.8 lower to 1.2 higher. Not clear yet."), f);
        String g = FactorEffect.unclearDetail(H, 0.2, -0.1, L);
        check("range: the detail line with both ends at 0", g.equals(", about the same at both ends. Not clear yet."), g);
        boolean no = true;
        for (String s : new String[] {a, b, c, d, e, f, g}) no &= !s.contains("no change");
        check("range: never \"no change\"", no, null);
        check("usual: with its unit", FactorEffect.usual(H, 53.4, L).equals("53 ms") && FactorEffect.usual(R, 52.36, L).equals("52.4 bpm")
                && FactorEffect.usual(T, 35.8, L).equals("35.80 °C") && FactorEffect.usual(S, 78.4, L).equals("78"),
                FactorEffect.usual(H, 53.4, L) + " " + FactorEffect.usual(R, 52.36, L) + " " + FactorEffect.usual(T, 35.8, L));
        // the readouts of a real comparison: every usual has its unit
        Set<LocalDate> t = random(455, 0.2, 81);
        FactorEffect.Result r = one(history(455, t, -0.15, 82), tag("alcohol", t, START));
        boolean hrv = true, rhr = true, temp = true;
        int n = 0;
        for (int i = 0; i < r.points.size(); i++) {
            if (Double.isNaN(r.points.get(i).usual[H])) continue;
            n++;
            hrv &= FactorEffect.pointText(r, i, H, TODAY, L).matches("(?s).*, your usual \\d+ ms \\(.*");
            rhr &= FactorEffect.pointText(r, i, R, TODAY, L).matches("(?s).*, your usual \\d+\\.\\d bpm \\(.*");
            temp &= FactorEffect.pointText(r, i, T, TODAY, L).matches("(?s).*, your usual \\d+\\.\\d\\d °C \\(.*");
        }
        check("usual: the touch readout of " + n + " nights says ms, bpm and °C", n > 300 && hrv && rhr && temp, FactorEffect.pointText(r, r.points.size() - 1, H, TODAY, L));
    }

    /**
     * Phase 3 (issue #292, option A): the lines beside readiness and beside the night, {@code FactorEffect.beside}: each measure's
     * sentence, the 0 case, last night with and without a value, "That night" for an earlier day, own tag names, no line for a
     * not clear factor, a tag without a result or a cycle factor, the order of the logged line, null input.
     */
    static void beside() {
        int H = FactorEffect.HRV, R = FactorEffect.RHR, T = FactorEffect.TEMP, S = FactorEffect.SCORE;
        String a = FactorEffect.besideLine("Alcohol", H, -12.4, 26, -9.2, true, L);
        check("beside: HRV", "After Alcohol your HRV is usually 12 % lower that night (clear, 26 nights). Last night: 9 % lower than your usual.".equals(a), a);
        String b = FactorEffect.besideLine("Hard workout", R, 2.06, 14, 1.04, true, L);
        check("beside: lowest heart rate", "After Hard workout your lowest heart rate is usually 2.1 bpm higher that night (clear, 14 nights). Last night: 1.0 bpm higher than your usual.".equals(b), b);
        String c = FactorEffect.besideLine("Sauna", T, -0.12, 11, 0.051, false, L);
        check("beside: temperature cooler, That night warmer", "After Sauna your temperature is usually 0.12 °C cooler that night (clear, 11 nights). That night: 0.05 °C warmer than your usual.".equals(c), c);
        String d = FactorEffect.besideLine("late dinner", S, -3.4, 12, Double.NaN, true, L);
        check("beside: sleep score, own name as typed, no last night value", "After late dinner your sleep score is usually 3 points lower that night (clear, 12 nights).".equals(d), d);
        String e = FactorEffect.besideLine("Alcohol", H, -12, 26, 0.3, true, L);
        check("beside: last night about the same", e != null && e.endsWith(" Last night: about the same as your usual."), e);
        check("beside: a difference that shows as 0 gives no line", FactorEffect.besideLine("Alcohol", H, 0.4, 26, -9, true, L) == null
                && FactorEffect.besideLine("Alcohol", T, -0.004, 26, -9, true, L) == null && FactorEffect.besideLine("Alcohol", H, Double.NaN, 26, -9, true, L) == null, null);

        // made up histories: two clear factors (disjoint days), one without an effect, the period tracker's
        LocalDate before = TODAY.minusDays(1);
        Set<LocalDate> t1 = random(455, 0.2, 101), t2 = random(455, 0.2, 102), none = random(455, 0.2, 103);
        t1.add(before);
        t2.removeAll(t1);
        Set<LocalDate> both = new HashSet<LocalDate>(t1);
        both.addAll(t2);
        List<FactorEffect.Night> nights = history(455, both, -0.2, 104);
        List<FactorEffect.Factor> fs = new ArrayList<FactorEffect.Factor>();
        fs.add(tag("alcohol", t1, START));
        fs.add(new FactorEffect.Factor("own:1731850000000", "late dinner", FactorEffect.TAG, t2, START, null));
        fs.add(tag("meditation", none, START));
        fs.addAll(FactorEffect.cycle(Cycles.of(Cycles.demoHistory(TODAY), null, TODAY), TODAY));
        List<FactorEffect.Result> rs = FactorEffect.of(nights, fs, TODAY);
        FactorEffect.Result alc = find(rs, "alcohol"), own = find(rs, "own:1731850000000"), med = find(rs, "meditation");
        check("beside: the made up factors are clear and not clear", alc.clear() && own.clear() && !med.clear(), FactorEffect.status(med, L));
        List<String> one = FactorEffect.beside(rs, Arrays.asList("alcohol"), before, true, L);
        String head = "After Alcohol your HRV is usually " + FactorEffect.amount(H, alc.m[H].diff, L) + " lower that night (clear, " + alc.m[H].nWith + " nights).";
        check("beside: the clear factor's line with the headline's nights", one.size() == 1 && one.get(0).startsWith(head + " Last night: ")
                && one.get(0).endsWith(" your usual."), one);
        FactorEffect.Point last = alc.points.get(alc.points.size() - 1);
        check("beside: last night's difference from its point", last.tagDay.equals(before)
                && one.get(0).equals(FactorEffect.besideLine("Alcohol", H, alc.m[H].diff, alc.m[H].nWith, last.diff[H], true, L)) && !Double.isNaN(last.diff[H]), one);
        check("beside: a not clear factor gives no line", FactorEffect.beside(rs, Arrays.asList("meditation"), before, true, L).isEmpty(), null);
        check("beside: a tag without a result gives no line", FactorEffect.beside(rs, Arrays.asList("sauna", "own:9"), before, true, L).isEmpty(), null);
        check("beside: the cycle factors get no line", FactorEffect.beside(rs, Arrays.asList(FactorEffect.KEY_PERIOD, FactorEffect.KEY_OVULATION), before, true, L).isEmpty(), null);
        List<String> ab = FactorEffect.beside(rs, Arrays.asList("meditation", "alcohol", "sauna", "own:1731850000000"), before, true, L),
                ba = FactorEffect.beside(rs, Arrays.asList("own:1731850000000", "alcohol"), before, true, L);
        check("beside: in the order of the logged line, own names as typed", ab.size() == 2 && ab.get(0).startsWith("After Alcohol ") && ab.get(1).startsWith("After late dinner your HRV ")
                && ba.size() == 2 && ba.get(0).equals(ab.get(1)) && ba.get(1).equals(ab.get(0)), ab);
        List<String> earlier = FactorEffect.beside(rs, Arrays.asList("alcohol"), before, false, L);
        check("beside: That night for an earlier day", earlier.size() == 1 && earlier.get(0).startsWith(head + " That night: ") && !earlier.get(0).contains("Last night"), earlier);
        List<String> future = FactorEffect.beside(rs, Arrays.asList("alcohol"), TODAY, true, L);
        check("beside: no night yet, only the pattern", future.size() == 1 && future.get(0).equals(head), future);
        // last night without an HRV value (the heart rate still there, so the night counts): the pattern alone
        List<FactorEffect.Night> noHrv = new ArrayList<FactorEffect.Night>(nights);
        FactorEffect.Night n = noHrv.remove(noHrv.size() - 1);
        noHrv.add(new FactorEffect.Night(n.day, Double.NaN, n.v[R], n.v[T], n.v[S], false));
        List<FactorEffect.Result> rs2 = FactorEffect.of(noHrv, fs, TODAY);
        List<String> nv = FactorEffect.beside(rs2, Arrays.asList("alcohol"), before, true, L);
        check("beside: last night without a value, the pattern alone", nv.size() == 1 && nv.get(0).startsWith("After Alcohol your HRV is usually ")
                && nv.get(0).endsWith(" nights).") && find(rs2, "alcohol").points.get(find(rs2, "alcohol").points.size() - 1).tagDay.equals(before), nv);
        // last night with an HRV value but without a usual (no HRV in the 90 days before it): the pattern alone
        List<FactorEffect.Night> noUsual = new ArrayList<FactorEffect.Night>();
        for (FactorEffect.Night x : nights) {
            boolean gap = !x.day.isBefore(TODAY.minusDays(FactorEffect.USUAL_DAYS)) && x.day.isBefore(TODAY);
            noUsual.add(gap ? new FactorEffect.Night(x.day, Double.NaN, x.v[R], x.v[T], x.v[S], false) : x);
        }
        FactorEffect.Result alc3 = find(FactorEffect.of(noUsual, fs, TODAY), "alcohol");
        FactorEffect.Point p3 = alc3.points.get(alc3.points.size() - 1);
        List<String> nu = FactorEffect.beside(FactorEffect.of(noUsual, fs, TODAY), Arrays.asList("alcohol"), before, true, L);
        check("beside: last night without a usual, the pattern alone", p3.tagDay.equals(before) && !Double.isNaN(p3.value[H]) && Double.isNaN(p3.usual[H])
                && alc3.clear() && alc3.headline == H && nu.size() == 1 && nu.get(0).endsWith(" nights)."), nu);
        // last night left out (#254): not a point, the pattern alone
        List<FactorEffect.Night> left = new ArrayList<FactorEffect.Night>(nights);
        FactorEffect.Night ln = left.remove(left.size() - 1);
        left.add(new FactorEffect.Night(ln.day, ln.v[H], ln.v[R], ln.v[T], ln.v[S], true));
        List<String> lo = FactorEffect.beside(FactorEffect.of(left, fs, TODAY), Arrays.asList("alcohol"), before, true, L);
        check("beside: last night left out, the pattern alone", lo.size() == 1 && lo.get(0).endsWith(" nights)."), lo);
        check("beside: null results or keys give no line", FactorEffect.beside(null, Arrays.asList("alcohol"), before, true, L).isEmpty()
                && FactorEffect.beside(rs, null, before, true, L).isEmpty() && FactorEffect.beside(new ArrayList<FactorEffect.Result>(), Arrays.asList("alcohol"), before, true, L).isEmpty(), null);
        boolean plain = true;
        List<String> all = new ArrayList<String>(Arrays.asList(a, b, c, d, e));
        all.addAll(ab);
        all.addAll(earlier);
        for (String x : all) plain &= !x.contains(" - ") && !x.contains("—") && !x.contains("–") && !x.contains("--");
        check("beside: no dashes as punctuation", plain, null);
    }

    /** Issue #475: when the life factors cannot be read, Body's month card says so and the effect card is left out (not the same error twice). */
    static void unreadableOnce() {
        String card;
        try {
            card = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/main/java/app/openring/ui/LifeCard.java")), "UTF-8");
        } catch (java.io.IOException e) {
            check("unreadable once: LifeCard.java read", false, e);
            return;
        }
        int from = card.indexOf("static View effects(");
        int to = card.indexOf("static void fillEffect(", from);
        String effects = from < 0 || to < 0 ? "" : card.substring(from, to);
        check("unreadable once: the effect card is left out when the factors cannot be read",
                effects.contains("if (!d.has.ring || !d.lifeOk) return null;") && !effects.contains("LifeTags.UNREADABLE") && effects.contains("FactorEffect.UNREADABLE"),
                effects.length());
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
        seed();
        weeks();
        overlap();
        severalDays();
        leftOutAndMissing();
        usual();
        firstDay();
        cycle();
        oldFactors();
        order();
        texts();
        rangeTexts();
        beside();
        unreadableOnce();
        demoYear();
        time();
        System.out.println("EffectTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
