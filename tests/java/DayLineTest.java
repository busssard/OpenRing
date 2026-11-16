import app.openring.core.DayLine;
import app.openring.core.DayLineBank;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Today's "Your day" line (owner's wish of 16 November, {@code core/DayLine}): every branch of the decision tree with made up
 * days, the rules of the line bank (length, no dashes, no emoji, no duplicates, no medical words), and the variety (stable
 * during a day, changing every day, no line again within 30 days). Made up days only. Run with tests/run_tests.sh.
 */
public class DayLineTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  detail: " + detail);
        check(name, ok);
    }

    static void eq(String name, Object got, Object want) {
        check(name, Objects.equals(got, want), "got <" + got + "> want <" + want + ">");
    }

    static final Locale EN = Locale.UK;
    /** A Wednesday. */
    static final LocalDate WED = LocalDate.of(2026, 11, 18);

    /** An ordinary ring day: readiness 76, sleep score 74, 7h 20m asleep with a 7h 30m need, usual vitals, nothing else. */
    static DayLine.Facts day() {
        DayLine.Facts f = new DayLine.Facts();
        f.date = WED;
        f.isToday = true;
        f.sinceSyncH = 2;
        f.readiness = 76;
        f.sleepScore = 74;
        f.asleepMin = 440;
        f.needMin = 450;
        f.debtMin = 60;
        f.rhr = 53;
        f.rhrUsual = 53;
        f.hrv = 45;
        f.hrvUsual = 44;
        f.tempDev = 0.1;
        f.readinessBefore = new double[] {74, 78, 80, 72, 75, 77, 79};
        f.sleepBefore = new double[] {80, 66, 72, 68, 75, 60, 65};
        f.goalBefore = new boolean[] {true, false, true, false, true, true, false};
        f.hardYesterdayMin = 20;
        f.bedScreenMin = 10;
        f.nightScreenMin = 2;
        f.steps = 4210;
        f.screenMin = 125;
        return f;
    }

    static DayLine.Decision dec(DayLine.Facts f) {
        return DayLine.decide(f, EN);
    }

    static final String[] MEDICAL = {"diagnos", "disease", "infection", "illness", "ill", "sick", "fever", "covid", "flu", "virus", "cold",
        "pregnan", "symptom", "doctor", "medic", "cure", "treatment", "therapy", "disorder", "depress", "anxiety", "insomnia", "cancer",
        "heal", "fertile", "ovulat", "contracept", "pain", "pill"};

    /** A medical word in {@code s} (a word starting with one from the list), else null. */
    static String medical(String s) {
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            for (String m : MEDICAL) {
                if (m.equals("ill") || m.equals("flu") || m.equals("cold") || m.equals("pain") || m.equals("pill") || m.equals("heal")
                        || m.equals("cure")) {
                    if (w.equals(m) || w.equals(m + "s") || w.equals(m + "ed") || w.equals(m + "ing") || w.equals(m + "ful") || w.equals(m + "er")) return w;
                } else if (w.startsWith(m)) return w;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        bank();
        tree();
        variety();
        System.out.println("DayLineTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------------------------- the bank

    static void bank() {
        List<String> ids = DayLine.allIds();
        Set<String> seen = new HashSet<String>();
        List<String> bad = new ArrayList<String>();
        for (String id : ids) {
            String l = DayLine.line(id);
            if (l == null) {
                bad.add(id + ": no text");
                continue;
            }
            if (l.length() > 110) bad.add(id + ": " + l.length() + " characters");
            if (l.indexOf('-') >= 0 || l.indexOf('–') >= 0 || l.indexOf('—') >= 0) bad.add(id + ": a dash");
            for (char ch : l.toCharArray()) if (ch < 32 || ch > 126) bad.add(id + ": not plain text (" + (int) ch + ")");
            if (!seen.add(l.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", ""))) bad.add(id + ": twice");
            String m = medical(l);
            if (m != null) bad.add(id + ": medical word " + m);
            if (!l.trim().equals(l) || l.contains("  ")) bad.add(id + ": spaces");
            char last = l.charAt(l.length() - 1);
            if (last != '.' && last != '?' && last != '!') bad.add(id + ": no full stop");
        }
        check("day line bank: every line short, plain, no dash, no emoji, no medical word, none twice", bad.isEmpty(), bad);
        check("day line bank: more than 150 lines", ids.size() > 150, ids.size());
        int fewest = Integer.MAX_VALUE;
        for (int b = 0; b < DayLine.BRANCHES; b++) fewest = Math.min(fewest, DayLineBank.of(b).length);
        check("day line bank: every branch has 10 to 15 lines of its own", fewest >= 10, fewest);
        check("day line bank: 20 or more lines for any day", DayLineBank.ANY.length >= 20, DayLineBank.ANY.length);
        // a branch with the lines for any day always has more than 30 to choose from (the no repeat rule can always hold)
        check("day line bank: every branch with the lines for any day has more than 30", fewest + DayLineBank.ANY.length > DayLine.NO_REPEAT_DAYS);
        eq("day line: an unknown id has no text", DayLine.line("debt.99"), null);
        eq("day line: an unknown key has no text", DayLine.line("nope.1"), null);
        eq("day line: no id, no text", DayLine.line(null), null);
        eq("day line: a broken number, no text", DayLine.line("debt.x"), null);
        eq("day line: the first line for any day", DayLine.line("any.0"), DayLineBank.ANY[0]);
        eq("day line: the names match the branches", DayLine.NAMES.length, DayLine.BRANCHES);
    }

    // ---------------------------------------------------------------------------------- the tree

    static void branch(String name, DayLine.Facts f, int want, String evidence) {
        DayLine.Decision d = dec(f);
        check("day line tree: " + name + " picks " + DayLine.NAMES[want], d.branch == want, DayLine.NAMES[d.branch] + " | " + d.evidence);
        if (evidence != null) eq("day line tree: " + name + ", the sentence", d.evidence, evidence);
        // the why list: every check up to the one that fits, only the last fits
        boolean ok = !d.checks.isEmpty() && d.checks.get(d.checks.size() - 1).fits && d.checks.get(d.checks.size() - 1).branch == want;
        for (int i = 0; i < d.checks.size() - 1; i++) ok &= !d.checks.get(i).fits;
        check("day line tree: " + name + ", the checks stop at the first that fits", ok, d.checks.size());
        String m = medical(d.evidence);
        check("day line tree: " + name + ", the sentence has no medical word", m == null, m);
        check("day line tree: " + name + ", no dash as punctuation", !d.evidence.contains(" - ") && d.evidence.indexOf('–') < 0, d.evidence);
    }

    static void tree() {
        branch("an ordinary Wednesday", day(), DayLine.ORDINARY, "Readiness 76, sleep score 74 and 7h 20m asleep.");
        DayLine.Facts sat = day();
        sat.date = WED.with(DayOfWeek.SATURDAY);
        branch("an ordinary Saturday", sat, DayLine.WEEKEND, null);
        DayLine.Facts sun = day();
        sun.date = WED.with(DayOfWeek.SUNDAY);
        branch("an ordinary Sunday", sun, DayLine.WEEKEND, null);

        // 1. stale
        DayLine.Facts st = day();
        st.sinceSyncH = 30;
        branch("no sync for 30 hours", st, DayLine.STALE, "Your ring last synced 30 hours ago, so today's numbers are not in yet.");
        st.sinceSyncH = 75;
        branch("no sync for 3 days", st, DayLine.STALE, "Your ring last synced 3 days ago, so today's numbers are not in yet.");
        st.device = DayLine.WATCH;
        branch("a watch without a sync for 3 days", st, DayLine.STALE, "Your watch last synced 3 days ago, so today's numbers are not in yet.");
        DayLine.Facts nv = day();
        nv.neverSynced = true;
        nv.sinceSyncH = Double.NaN;
        branch("never synced", nv, DayLine.STALE, "Your ring has not synced yet, so this day is not known.");
        DayLine.Facts old = day();
        old.sinceSyncH = 100;
        old.isToday = false;
        branch("an earlier day is never out of date", old, DayLine.ORDINARY, null);
        DayLine.Facts ph = day();
        ph.device = DayLine.PHONE;
        ph.sinceSyncH = 100;
        check("day line tree: the phone counts by itself, never out of date", dec(ph).branch != DayLine.STALE);
        DayLine.Facts fresh = day();
        fresh.sinceSyncH = 23.9;
        check("day line tree: 23.9 hours is still fresh", dec(fresh).branch == DayLine.ORDINARY);
        DayLine.Facts unknown = day();
        unknown.sinceSyncH = Double.NaN;
        check("day line tree: an unknown sync age is not out of date", dec(unknown).branch == DayLine.ORDINARY);
        eq("day line why: an unknown sync age says so", dec(unknown).checks.get(0).line(), "Data out of date: no, no sync time known");

        // 2. strain
        DayLine.Facts s1 = day();
        s1.tempDev = 0.6;
        s1.rhr = 58;
        branch("temperature and resting heart rate up", s1, DayLine.STRAIN,
                "Your temperature is +0.6 °C against your usual and your resting heart rate 5 bpm above it. Take it easy today.");
        DayLine.Facts s2 = day();
        s2.tempDev = 0.5;
        s2.hrv = 35;
        s2.hrvUsual = 44;
        branch("temperature up, HRV down", s2, DayLine.STRAIN, "Your temperature is +0.5 °C against your usual and your HRV 20 % under it. Take it easy today.");
        DayLine.Facts s3 = day();
        s3.tempDev = 0.7;
        s3.rhr = 57;
        s3.hrv = 30;
        branch("all three off", s3, DayLine.STRAIN,
                "Your temperature is +0.7 °C against your usual, your resting heart rate 4 bpm above it and your HRV 32 % under it. Take it easy today.");
        DayLine.Facts t = day();
        t.tempDev = 0.9;
        branch("temperature alone is not strain", t, DayLine.ORDINARY, null);
        DayLine.Facts h = day();
        h.rhr = 62;
        h.hrv = 20;
        branch("heart alone is not strain (no temperature rise)", h, DayLine.ORDINARY, null);
        DayLine.Facts lut = day();
        lut.tempDev = 0.6;
        lut.rhr = 57;
        lut.periodPhase = "after ovulation (measured)";
        check("day line tree: after ovulation, temperature and resting heart rate up is not strain", dec(lut).branch != DayLine.STRAIN, dec(lut).branch);
        lut.hrv = 30;
        branch("after ovulation all three off", lut, DayLine.STRAIN, null);
        DayLine.Facts nousual = day();
        nousual.tempDev = 0.6;
        nousual.rhr = 70;
        nousual.rhrUsual = Double.NaN;
        nousual.hrvUsual = Double.NaN;
        check("day line tree: without a usual value no strain", dec(nousual).branch != DayLine.STRAIN);

        // 3. short on sleep
        DayLine.Facts d1 = day();
        d1.asleepMin = 310;
        d1.needMin = 410;
        branch("a short night", d1, DayLine.DEBT, "You slept 5h 10m, 1h 40m under your need.");
        d1.debtMin = 380;
        branch("a short night and a big debt", d1, DayLine.DEBT, "You slept 5h 10m, 1h 40m under your need, and carry about 6h 20m of sleep debt.");
        DayLine.Facts d2 = day();
        d2.debtMin = 245;
        branch("a big debt", d2, DayLine.DEBT, "You carry about 4h 5m of sleep debt from the last two weeks.");
        DayLine.Facts d3 = day();
        d3.asleepMin = 370;
        d3.needMin = 450;
        d3.debtMin = 200;
        branch("80 minutes under and 3h 20m of debt is not yet short", d3, DayLine.ORDINARY, null);
        DayLine.Facts both = day();
        both.tempDev = 0.6;
        both.rhr = 60;
        both.asleepMin = 200;
        check("day line tree: strain comes before a short night", dec(both).branch == DayLine.STRAIN);
        both.sinceSyncH = 40;
        check("day line tree: out of date comes before everything", dec(both).branch == DayLine.STALE);

        // 4. recovered
        DayLine.Facts r = day();
        r.readiness = 82;
        r.readinessBefore = new double[] {80, 75, 66, 62};
        branch("readiness 82 after two low days", r, DayLine.RECOVERED, "Readiness is 82 today, after 2 days under 70.");
        r.readinessBefore = new double[] {80, 75, 72, 62};
        check("day line tree: one low day before is not a recovery", dec(r).branch != DayLine.RECOVERED);
        r.readinessBefore = new double[] {66, Double.NaN, 62};
        check("day line tree: a missing day breaks the low days", dec(r).branch != DayLine.RECOVERED);
        DayLine.Facts r2 = day();
        r2.readiness = 65;
        r2.readinessBefore = new double[] {60, 62};
        check("day line tree: still low is not recovered", dec(r2).branch == DayLine.LOW);

        // 5. a badge
        DayLine.Facts b = day();
        b.badge = "Best night";
        branch("a badge today", b, DayLine.BADGE, "You earned the badge Best night today.");

        // 6. good nights in a row
        DayLine.Facts n = day();
        n.sleepScore = 80;
        n.sleepBefore = new double[] {75, 72, 90, 88};
        branch("5 good nights", n, DayLine.NIGHTS, "5 good nights in a row (sleep score 70 or more).");
        n.sleepBefore = new double[] {60, 72, 90, 75};
        branch("4 good nights exactly", n, DayLine.NIGHTS, "4 good nights in a row (sleep score 70 or more).");
        n.sleepBefore = new double[] {75, 60, 72};
        check("day line tree: 2 good nights is no streak", dec(n).branch != DayLine.NIGHTS);
        double[] month = new double[29];
        Arrays.fill(month, 80);
        n.sleepBefore = month;
        branch("every night of the month good", n, DayLine.NIGHTS, "At least 30 good nights in a row (sleep score 70 or more).");
        month[0] = 60;
        branch("29 good nights", n, DayLine.NIGHTS, "29 good nights in a row (sleep score 70 or more).");
        n.sleepScore = 65;
        n.sleepBefore = new double[] {75, 75, 75, 75};
        check("day line tree: a poor night today ends the streak", dec(n).branch != DayLine.NIGHTS);

        // 7. goal days in a row
        DayLine.Facts a = day();
        a.goalBefore = new boolean[] {false, true, true, true};
        branch("goal 3 days in a row", a, DayLine.ACTIVE, "Activity goal reached 3 days in a row.");
        a.goalBefore = new boolean[] {false, true, true};
        a.goalToday = Boolean.TRUE;
        branch("goal 2 days and today", a, DayLine.ACTIVE, "Activity goal reached 3 days in a row, today too.");
        a.goalToday = null;
        check("day line tree: 2 days and today not yet is no streak", dec(a).branch != DayLine.ACTIVE);

        // 8. late phone use
        DayLine.Facts sc = day();
        sc.bedScreenMin = 40;
        branch("40 minutes before sleep", sc, DayLine.SCREEN, "Your phone was on for 40 min in the hour before sleep.");
        sc.nightScreenMin = 18;
        branch("before sleep and in the night", sc, DayLine.SCREEN, "Your phone was on for 40 min in the hour before sleep and 18 min during the night.");
        sc.bedScreenMin = 5;
        branch("in the night", sc, DayLine.SCREEN, "Your phone was on for 18 min during the night.");

        // 9. rest after hard training
        DayLine.Facts rt = day();
        rt.hardYesterdayMin = 75;
        branch("75 hard minutes yesterday", rt, DayLine.REST, "Yesterday you were active at a hard pace for 1h 15m, and readiness is 76 today.");
        rt.readiness = 88;
        check("day line tree: hard yesterday with readiness 88 is a strong day, not rest", dec(rt).branch == DayLine.GOOD, dec(rt).branch);

        // 10. period day
        DayLine.Facts p = day();
        p.periodPhase = "period day 2";
        branch("a logged period day", p, DayLine.PERIOD, "Day 2 of your period, from your log.");
        p.periodPhase = "after ovulation (estimate)";
        check("day line tree: another phase is not a period day", dec(p).branch == DayLine.ORDINARY);
        p.periodPhase = "period day x";
        check("day line tree: a broken phase is not a period day", dec(p).branch == DayLine.ORDINARY);

        // 11. and 12. lower and strong days
        DayLine.Facts lo = day();
        lo.readiness = 64;
        branch("readiness 64", lo, DayLine.LOW, "Readiness is 64 today, under 70.");
        DayLine.Facts lo2 = day();
        lo2.readiness = Double.NaN;
        lo2.sleepScore = 58;
        branch("no readiness, sleep score 58", lo2, DayLine.LOW, "Sleep score is 58 today, under 70.");
        DayLine.Facts g = day();
        g.readiness = 88;
        g.sleepScore = 86;
        branch("readiness 88", g, DayLine.GOOD, "Readiness 88, sleep score 86 and 7h 20m asleep.");

        // the phone only: steps and screen time, never stale, never strain
        DayLine.Facts po = new DayLine.Facts();
        po.date = WED;
        po.isToday = true;
        po.device = DayLine.PHONE;
        po.goalName = "Step goal";
        po.steps = 4210;
        po.screenMin = 125;
        branch("the phone only", po, DayLine.ORDINARY, "4210 steps so far and 2h 5m on the screen.");
        po.isToday = false;
        eq("day line tree: an earlier day has no so far", dec(po).evidence, "4210 steps and 2h 5m on the screen.");
        po.goalBefore = new boolean[] {true, true, true};
        branch("the phone's step goal", po, DayLine.ACTIVE, "Step goal reached 3 days in a row.");
        DayLine.Facts none = new DayLine.Facts();
        none.date = WED;
        branch("nothing at all", none, DayLine.ORDINARY, "Not much measured yet for this day.");
        DayLine.Facts all = day();
        all.readiness = Double.NaN;
        all.sleepScore = Double.NaN;
        all.asleepMin = Double.NaN;
        eq("day line tree: the ring without scores says steps", dec(all).evidence, "4210 steps so far and 2h 5m on the screen.");

        // the why list: names, yes and no, the last text
        DayLine.Result res = DayLine.of(d1, new DayLine.Memory(), false, EN);
        List<String> why = res.why();
        eq("day line why: the first check", why.get(0), "Data out of date: no, last sync 2 hours ago");
        eq("day line why: the second", why.get(1), "Signs of strain: no, temperature +0.1 °C, resting heart rate +0 bpm, HRV +2 % against your usual");
        eq("day line why: the one that fits", why.get(2), "Short on sleep: yes, 5h 10m asleep, need 6h 50m and sleep debt 6h 20m");
        check("day line why: where the line is from", why.get(3).startsWith("The line comes from the list for short on sleep"), why.get(3));
        check("day line: the line is the bank's", Arrays.asList(DayLineBank.of(DayLine.DEBT)).contains(res.line), res.line);

        // usual: the median, only from 7 values
        eq("day line usual: the median", DayLine.usual(new double[] {50, 52, 51, Double.NaN, 70, 49, 53, 52}), 52.0);
        eq("day line usual: an even count", DayLine.usual(new double[] {50, 52, 51, 54, 49, 53, 52, 56}), 52.0);
        check("day line usual: fewer than 7 values give none", Double.isNaN(DayLine.usual(new double[] {50, 52, 51, 54, 49, 53})));
    }

    // ---------------------------------------------------------------------------------- variety

    static void variety() {
        DayLine.Memory empty = new DayLine.Memory();
        String a = DayLine.pick(DayLine.DEBT, WED, empty, false), b = DayLine.pick(DayLine.DEBT, WED, empty, false);
        eq("day line variety: the same date and branch give the same line", a, b);
        check("day line variety: nothing noted without note", empty.size() == 0);
        Set<String> days = new HashSet<String>();
        for (int i = 0; i < 30; i++) days.add(DayLine.pick(DayLine.ORDINARY, WED.plusDays(i), empty, false));
        check("day line variety: without memory, 30 days give many lines", days.size() >= 7, days.size());

        // stable during the day: once noted it stays, also after the memory is stored and read again
        DayLine.Memory m = new DayLine.Memory();
        String first = DayLine.pick(DayLine.GOOD, WED, m, true);
        DayLine.Memory again = DayLine.Memory.parse(m.toString());
        eq("day line variety: the same line all day", DayLine.pick(DayLine.GOOD, WED, again, true), first);
        eq("day line variety: and the memory holds one entry", again.size(), 1);
        String other = DayLine.pick(DayLine.LOW, WED, again, true);
        check("day line variety: a new branch the same day gets its own line", other.startsWith("low.") && again.size() == 1, other + " " + again);
        String next = DayLine.pick(DayLine.GOOD, WED.plusDays(1), again, true);
        check("day line variety: the next day another line", !next.equals(first), next);

        // the branch's own lines first, then those for any day
        DayLine.Memory own = new DayLine.Memory();
        boolean ownFirst = true, anyAfter = true;
        int n = DayLineBank.of(DayLine.DEBT).length;
        for (int i = 0; i < 30; i++) {
            String id = DayLine.pick(DayLine.DEBT, WED.plusDays(i), own, true);
            if (i < n) ownFirst &= id.startsWith("debt.");
            else anyAfter &= id.startsWith("any.");
        }
        check("day line variety: a branch's own lines come first", ownFirst);
        check("day line variety: then the lines for any day", anyAfter);

        // no line again within 30 days: 2 years of made up days, one branch for weeks, then random ones, stored every day
        Random rnd = new Random(16);
        String stored = null;
        Map<String, LocalDate> last = new HashMap<String, LocalDate>();
        List<String> repeats = new ArrayList<String>();
        int maxSize = 0;
        LocalDate d0 = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 730; i++) {
            LocalDate d = d0.plusDays(i);
            int br = i < 60 ? DayLine.ORDINARY : i < 120 ? DayLine.STRAIN : rnd.nextInt(DayLine.BRANCHES);
            DayLine.Memory mem = DayLine.Memory.parse(stored);
            String id = DayLine.pick(br, d, mem, true);
            // opened again later the same day: the same line
            DayLine.Memory later = DayLine.Memory.parse(mem.toString());
            if (!DayLine.pick(br, d, later, true).equals(id)) repeats.add(d + " changed during the day");
            stored = mem.toString();
            maxSize = Math.max(maxSize, mem.size());
            LocalDate before = last.get(id);
            if (before != null && d.toEpochDay() - before.toEpochDay() < DayLine.NO_REPEAT_DAYS) repeats.add(id + " on " + before + " and " + d);
            last.put(id, d);
            if (DayLine.line(id) == null) repeats.add(id + " has no text");
        }
        check("day line variety: no line again within 30 days over 2 years", repeats.isEmpty(), repeats);
        check("day line variety: the memory stays small", maxSize <= 2 * DayLine.NO_REPEAT_DAYS + 1, maxSize);

        // a memory it cannot read is only display memory: skipped, never a crash
        DayLine.Memory broken = DayLine.Memory.parse("2026-11-16 debt debt.3;nonsense;2026-13-01 debt debt.1;2026-11-15 nope x.1; ;2026-11-14 good good.2");
        eq("day line memory: broken entries are skipped", broken.size(), 2);
        eq("day line memory: written back", broken.toString(), "2026-11-16 debt debt.3;2026-11-14 good good.2");
        eq("day line memory: an empty one", DayLine.Memory.parse(null).size(), 0);
        // a noted line no longer in the bank is replaced
        DayLine.Memory gone = DayLine.Memory.parse("2026-11-18 debt debt.99");
        String re = DayLine.pick(DayLine.DEBT, WED, gone, true);
        check("day line memory: a noted line no longer in the bank is replaced", DayLine.line(re) != null && gone.size() == 1, re);
        // when every line was shown lately, the one shown longest ago
        DayLine.Memory full;
        StringBuilder sb = new StringBuilder();
        List<String> ids = new ArrayList<String>();
        for (int k = 0; k < DayLineBank.of(DayLine.REST).length; k++) ids.add("rest." + k);
        for (int k = 0; k < DayLineBank.ANY.length; k++) ids.add("any." + k);
        for (int k = 0; k < ids.size(); k++) sb.append(sb.length() == 0 ? "" : ";").append(WED.minusDays(k == ids.size() - 1 ? 29 : 1 + k % 20)).append(" rest ").append(ids.get(k));
        full = DayLine.Memory.parse(sb.toString());
        eq("day line memory: all shown lately, the oldest comes back", DayLine.pick(DayLine.REST, WED, full, false), ids.get(ids.size() - 1));
    }
}
