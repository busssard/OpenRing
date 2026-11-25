import android.content.Context;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import app.openring.core.LifeTags;
import app.openring.core.analysis.CycleFactors;
import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.FactorEffect;
import app.openring.core.analysis.cycleforecast.Methods;
import app.openring.data.Db;
import app.openring.data.Demo;
import app.openring.data.LifeEffects;
import app.openring.data.LifeLog;
import app.openring.data.Periods;

/**
 * Life factors phase 2 (issue #292) on the JVM: how the nights went after each factor, read from a database by
 * {@code data/LifeEffects}. The demo shows a clear factor, a number that is not clear yet, too few days and the period tracker's
 * factors; reading changes no stored row; the same history gives the same texts; three years under a time limit; a tag or a left
 * out time works it out again. Made up data only. Run by tests/run_pipeline.sh after ExportPipelineTest.
 */
public class EffectPipelineTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object info) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (info == null ? "" : "  (" + info + ")"));
    }

    static Context freshDb(String name) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("instance");
        f.setAccessible(true);
        Db open = (Db) f.get(null);
        if (open != null) open.close();
        f.set(null, null);
        return new Context(Files.createTempDirectory(name).toFile());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Life factors and your nights (issue #292, phase 2)");
        demo();
        threeYears();
        cacheKey();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static List<FactorEffect.Result> read(Db db, LocalDate today, ZoneId z, boolean withCycles) {
        Cycles cy = null;
        if (withCycles) cy = Periods.measured(db, Periods.cycles(db, today), z);
        return LifeEffects.read(db, today, z, LifeLog.known(db), LifeLog.all(db), cy);
    }

    static FactorEffect.Result find(List<FactorEffect.Result> l, String key) {
        for (FactorEffect.Result r : l) if (r.factor.key.equals(key)) return r;
        return null;
    }

    /** Every stored night, day and life row, to show reading changes none. */
    static String stored(Db db) {
        StringBuilder b = new StringBuilder();
        for (String s : db.nightsJson("1900-01-01", "2999-01-01", false)) b.append(s.hashCode()).append(',');
        for (String[] r : db.daysJson("1900-01-01", "2999-01-01")) b.append(r[0]).append(r[1].hashCode()).append(',');
        b.append(db.count("SELECT COUNT(*) FROM life_days")).append(',').append(db.count("SELECT COUNT(*) FROM events"));
        return b.toString();
    }

    static void demo() throws Exception {
        Context c = freshDb("effect-demo");
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-10-25");
        long now = today.atTime(9, 41).atZone(z).toEpochSecond();
        Demo.enter(c, null, today, now);
        Db db = Db.get(c);
        LocalDate last = Demo.yearLast(today);
        check("#292 demo: made up tags over the made up year, in the demo file", Db.DEMO.equals(db.fileName)
                && db.count("SELECT COUNT(*) FROM life_days WHERE day <= '" + last + "'") > 200
                && db.count("SELECT COUNT(*) FROM life_days WHERE tag = 'intimacy'") == 0, db.count("SELECT COUNT(*) FROM life_days"));
        String before = stored(db);
        LifeEffects.forget();
        long t0 = System.nanoTime();
        List<FactorEffect.Result> l = read(db, today, z, true);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println("    demo read and worked out in " + Math.round(ms) + " ms");
        for (FactorEffect.Result r : l) System.out.println("    " + r.factor.name + ": " + FactorEffect.status(r, Locale.US) + " | " + FactorEffect.headline(r, Locale.US)
                + (FactorEffect.overlapLine(r) == null ? "" : " | " + FactorEffect.overlapLine(r)));
        check("#292 demo: reading changes no stored night, day, tag or event", stored(db).equals(before), null);
        FactorEffect.Result alc = find(l, LifeTags.ALCOHOL), med = find(l, LifeTags.MEDITATION);
        check("#292 demo: Alcohol has a clear effect, HRV lower", alc != null && alc.clear() && alc.headline == FactorEffect.HRV && alc.m[FactorEffect.HRV].diff < 0,
                alc == null ? null : FactorEffect.status(alc, Locale.US));
        check("#292 demo: Meditation has a number that is not clear yet", med != null && med.state == FactorEffect.NUMBER && !med.clear(),
                med == null ? null : FactorEffect.status(med, Locale.US));
        int few = 0;
        for (FactorEffect.Result r : l) if (r.state == FactorEffect.FEW_WITH) few++;
        check("#292 demo: some factors have too few days yet (the count shows)", few >= 1, few);
        check("#292 demo: the period tracker's two factors", find(l, FactorEffect.KEY_PERIOD) != null && find(l, FactorEffect.KEY_OVULATION) != null, null);
        check("#292 demo: clear ones first", l.get(0).clear(), l.get(0).factor.name);
        check("#292 demo: no Intimacy", find(l, LifeTags.INTIMACY) == null, null);
        check("#292 demo: worked out in under a second", ms < 1000, ms);
        // kept while nothing changed; the same history gives the same texts when worked out again
        long t1 = System.nanoTime();
        List<FactorEffect.Result> again = read(db, today, z, true);
        double kept = (System.nanoTime() - t1) / 1e6;
        check("#292 demo: a second read while nothing changed is the kept result", again == l, kept);
        LifeEffects.forget();
        List<FactorEffect.Result> fresh = read(db, today, z, true);
        boolean same = fresh.size() == l.size();
        for (int i = 0; same && i < l.size(); i++) same = FactorEffect.headline(l.get(i), Locale.US).equals(FactorEffect.headline(fresh.get(i), Locale.US));
        check("#292 demo: the same history gives the same texts", same, null);
        // a tag changed: worked out again
        LifeLog.set(db, today.minusDays(2).toString(), LifeTags.MEDITATION, !LifeLog.on(db, today.minusDays(2).toString()).contains(LifeTags.MEDITATION), now);
        List<FactorEffect.Result> changed = read(db, today, z, true);
        check("#292 demo: a tag tapped works it out again", changed != fresh && find(changed, LifeTags.MEDITATION).nWith != find(fresh, LifeTags.MEDITATION).nWith,
                find(changed, LifeTags.MEDITATION).nWith + " " + find(fresh, LifeTags.MEDITATION).nWith);
        // a left out time over one of Alcohol's nights: that night is skipped
        FactorEffect.Result a0 = find(changed, LifeTags.ALCOHOL);
        LocalDate tagDay = null;
        for (int i = a0.points.size() - 1; i >= 0 && tagDay == null; i--) if (a0.with.get(i)) tagDay = a0.points.get(i).tagDay;
        double s = tagDay.atTime(23, 0).atZone(z).toEpochSecond();
        db.addExcluded(s, s + 3600, 1234567);
        FactorEffect.Result a1 = find(read(db, today, z, true), LifeTags.ALCOHOL);
        check("#292 demo: a night in a left out time is skipped", a1.nWith == a0.nWith - 1, a0.nWith + " " + a1.nWith);
        periodFactors(db, today, z);
        Demo.leave(c);
    }

    /** Three years of day summaries and five tags: read and worked out well under a second, the second read from memory. */
    static void threeYears() throws Exception {
        Context c = freshDb("effect-3y");
        Db db = Db.get(c);
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-11-20");
        Random r = new Random(292);
        StringBuilder profile = new StringBuilder("[");
        for (int i = 0; i < 96; i++) profile.append(i == 0 ? "" : ",").append(Math.round(r.nextDouble() * 400) / 100.0);
        profile.append(']');
        db.getWritableDatabase().beginTransaction();
        try {
            for (int i = 3 * 365; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                String js = "{\"date\":\"" + d + "\",\"steps\":" + (6000 + r.nextInt(6000)) + ",\"met_profile\":" + profile
                        + ",\"readiness\":{\"value\":" + (60 + r.nextInt(30)) + ",\"contributors\":[{\"label\":\"Resting heart rate\",\"value\":80}]},\"rhr\":"
                        + (50 + r.nextInt(6)) + ",\"hrv\":" + (40 + r.nextInt(20)) + ",\"temp\":" + (35.6 + r.nextInt(40) / 100.0) + ",\"sleep_score\":"
                        + (70 + r.nextInt(20)) + ",\"asleep_min\":" + (380 + r.nextInt(80)) + "}";
                db.putDay(d.toString(), js);
            }
            db.getWritableDatabase().setTransactionSuccessful();
        } finally {
            db.getWritableDatabase().endTransaction();
        }
        String[] tags = {LifeTags.MEDITATION, LifeTags.ALCOHOL, LifeTags.STRESS, LifeTags.WORKOUT, LifeTags.SAUNA};
        for (int i = 3 * 365; i >= 1; i--) {
            for (int k = 0; k < tags.length; k++) if (r.nextInt(5 + k) == 0) LifeLog.set(db, today.minusDays(i).toString(), tags[k], true, 1000);
        }
        String before = stored(db);
        LifeEffects.forget();
        long t0 = System.nanoTime();
        List<FactorEffect.Result> l = read(db, today, z, false);
        double ms = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        read(db, today, z, false);
        double kept = (System.nanoTime() - t1) / 1e6;
        System.out.println("    3 years, 5 factors: " + Math.round(ms) + " ms, kept " + Math.round(kept) + " ms");
        check("#292 3 years: every factor worked out", l.size() == 5, l.size());
        check("#292 3 years: well under a second", ms < 1000, ms);
        check("#292 3 years: the kept result in under 50 ms", kept < 50, kept);
        check("#292 3 years: only the 365 days are compared", l.get(0).nWith + l.get(0).nWithout <= FactorEffect.DAYS, l.get(0).nWith + l.get(0).nWithout);
        check("#292 3 years: reading changes nothing stored", stored(db).equals(before), null);
        int clear = 0;
        for (FactorEffect.Result x : l) if (x.clear()) clear++;
        // random values and 5 factors at 99 %: one clear by chance can happen (about 1 time in 20), two hardly (1 in 1000)
        check("#292 3 years: random values, at most one factor clear by chance", clear <= 1, clear);
    }

    /** Code review of 418df41: the kept result's key tells sets of days apart whose hash is the same (3 and 6 against 4 and 5). */
    static void cacheKey() {
        java.util.Set<LocalDate> a = new java.util.HashSet<LocalDate>(java.util.Arrays.asList(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-06"))),
                b = new java.util.HashSet<LocalDate>(java.util.Arrays.asList(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05")));
        check("#292 cache key: two sets with the same hash give different keys", a.hashCode() == b.hashCode() && !LifeEffects.days(a).equals(LifeEffects.days(b)),
                LifeEffects.days(a) + " " + LifeEffects.days(b));
        check("#292 cache key: the same days give the same key", LifeEffects.days(a).equals(LifeEffects.days(new java.util.TreeSet<LocalDate>(a))), null);
    }

    /**
     * Issue #383, phase 2: the demo's made up tags beside each period of "How close the estimates were", as {@code DayData} works
     * them out: Illness only on the cycle with the made up ill week, Holiday and Travel on the newest period, the cycles before the
     * made up year unknown, no Intimacy; reading changes nothing stored.
     */
    static void periodFactors(Db db, LocalDate today, ZoneId z) {
        String before = stored(db);
        Cycles cy = Periods.measured(db, Periods.cycles(db, today).withMethod(Methods.BEST), z);
        app.openring.core.analysis.cycleforecast.Backtest.Score use = cy.backtest().of(cy.methodId);
        long t0 = System.nanoTime();
        CycleFactors cf = CycleFactors.of(cy, use, LifeEffects.tagFactors(LifeLog.known(db), LifeLog.all(db), z));
        double ms = (System.nanoTime() - t0) / 1e6;
        StringBuilder chips = new StringBuilder();
        for (FactorEffect.Factor f : cf.chips) {
            chips.append(f.key).append(' ');
            System.out.println("    #383 demo, " + f.name + ": " + cf.line(f.key, Locale.US));
        }
        int last = cf.periods.size() - 1;
        System.out.println("    #383 demo, the newest period's readout: " + cf.tip(last, null, Locale.US).replace("\n", " / ") + " (" + Math.round(ms) + " ms)");
        check("#383 demo: one dot for each scored period", cf.periods.size() == use.n && use.n > 20, use.n);
        int ill = 0;
        boolean illRight = true;
        LocalDate illFrom = Demo.illFrom(today);
        for (int i = 0; i < cf.periods.size(); i++) {
            if (cf.state(i, LifeTags.ILLNESS) != CycleFactors.WITH) continue;
            ill++;
            Cycles.Cycle k = cf.periods.get(i).cycle;
            illRight &= !illFrom.isBefore(k.start) && illFrom.isBefore(k.next);
        }
        check("#383 demo: Illness on exactly the cycle with the made up ill week", ill == 1 && illRight, ill);
        check("#383 demo: Holiday and Travel on the newest period", cf.state(last, LifeTags.HOLIDAY) == CycleFactors.WITH
                && cf.state(last, LifeTags.TRAVEL) == CycleFactors.WITH && cf.newest(LifeTags.TRAVEL) == last, cf.periods.get(last).logged.keySet());
        check("#383 demo: the cycles before the made up year are unknown, their readout says so", cf.state(0, LifeTags.ILLNESS) == CycleFactors.UNKNOWN
                && cf.tip(0, null, Locale.US).startsWith("Before you started logging life factors"), cf.tip(0, null, Locale.US));
        check("#383 demo: the chips hold Illness, Travel and Meditation, never Intimacy", cf.offered(LifeTags.ILLNESS) && cf.offered(LifeTags.TRAVEL)
                && cf.offered(LifeTags.MEDITATION) && !cf.offered(LifeTags.INTIMACY), chips);
        check("#383 demo: Illness reads as the decisions' sentence", cf.line(LifeTags.ILLNESS, Locale.US).startsWith("The period on ")
                && cf.line(LifeTags.ILLNESS, Locale.US).endsWith("; you logged Illness that cycle (" + Demo.ILL_DAYS + " days)."), cf.line(LifeTags.ILLNESS, Locale.US));
        check("#383 demo: reading changes nothing stored", stored(db).equals(before), null);
        check("#383 demo: worked out in under 100 ms", ms < 100, ms);
    }
}
