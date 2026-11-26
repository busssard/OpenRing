import android.content.Context;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import app.openring.core.LifeTags;
import app.openring.core.analysis.Cycles;
import app.openring.core.analysis.FactorEffect;
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
        // phase 3 (option A): the lines beside readiness and the night, from the same results; nothing stored, no score changed
        LifeTags.Known known = LifeLog.known(db);
        java.util.Map<LocalDate, List<String>> days = LifeLog.all(db);
        LocalDate y = today.minusDays(1);
        List<String> keys = known.keys(days.get(y));
        List<String> lines = FactorEffect.beside(l, keys, y, true, Locale.US);
        for (String s : lines) System.out.println("    beside: " + s);
        check("#292 phase 3 demo: yesterday's tags in the order of the logged line", keys.contains(LifeTags.ALCOHOL)
                && known.names(days.get(y)).size() == keys.size() && known.names(days.get(y)).indexOf("Alcohol") == keys.indexOf(LifeTags.ALCOHOL), keys);
        check("#292 phase 3 demo: Alcohol's line beside today's readiness, with last night", lines.size() >= 1 && lines.get(0).startsWith("After a day with Alcohol, your HRV is usually ")
                && lines.get(0).contains(" lower that night (clear, from " + alc.m[FactorEffect.HRV].nWith + " nights after it). Last night: "), lines);
        check("#292 phase 3 demo: no line for Meditation (not clear yet)", keys.contains(LifeTags.MEDITATION) && lines.size() == FactorEffect.beside(l,
                java.util.Arrays.asList(LifeTags.ALCOHOL), y, true, Locale.US).size() + FactorEffect.beside(l, java.util.Arrays.asList(LifeTags.SAUNA), y, true, Locale.US).size(), lines);
        LocalDate bare = null;
        for (int back = 2; back < 30 && bare == null; back++) {
            List<String> t = days.get(today.minusDays(back + 1));
            if (t == null || t.isEmpty()) bare = today.minusDays(back);
        }
        check("#292 phase 3 demo: a day after a day without tags gets no line", bare != null
                && FactorEffect.beside(l, known.keys(days.get(bare.minusDays(1))), bare.minusDays(1), false, Locale.US).isEmpty(), bare);
        LocalDate earlier = null;
        for (int back = 2; back < 60 && earlier == null; back++) {
            List<String> t = days.get(today.minusDays(back + 1));
            if (t != null && t.contains(LifeTags.ALCOHOL)) earlier = today.minusDays(back);
        }
        List<String> that = earlier == null ? null : FactorEffect.beside(l, known.keys(days.get(earlier.minusDays(1))), earlier.minusDays(1), false, Locale.US);
        check("#292 phase 3 demo: an earlier day after Alcohol says That night", that != null && that.size() >= 1 && that.get(0).startsWith("After a day with Alcohol, ")
                && that.get(0).contains(" That night: "), earlier + " " + that);
        check("#292 phase 3 demo: the lines change nothing stored (readiness and every score as before)", stored(db).equals(before), null);
        // screens.sh scrolls to these words to find each open row (issue #473): change both together
        check("#292 demo: the open rows' sentences start as screens.sh looks for them, After a day with Alcohol and After a day with Meditation",
                alc != null && med != null && FactorEffect.headline(alc, Locale.US).startsWith("After a day with Alcohol")
                        && FactorEffect.headline(med, Locale.US).startsWith("After a day with Meditation"),
                alc == null || med == null ? null : FactorEffect.headline(alc, Locale.US) + " | " + FactorEffect.headline(med, Locale.US));
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
}
