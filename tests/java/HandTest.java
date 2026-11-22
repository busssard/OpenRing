import app.openring.core.HandNight;
import app.openring.core.MainDevice;
import app.openring.core.Start;
import app.openring.core.TodayCards;
import app.openring.core.analysis.Night;
import app.openring.core.analysis.Scores;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Nights entered by hand (issue #427, {@code core/HandNight}): the night bar's times (the snap, midnight, the clock change
 * nights in Europe/Zurich), the wake marks (inside the night, merged, the new one in the longest stretch), the default times
 * with and without history, the stored marks, the night in the ring's night fields and its partial score, readiness without
 * heart rate, when Today asks, the choice device against by hand ({@code MainDevice.nightFrom}) and Today's order. Made up
 * nights only. Run with tests/run_tests.sh.
 */
public class HandTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    static final ZoneId Z = ZoneId.of("Europe/Zurich");
    static final LocalDate DAY = LocalDate.of(2026, 11, 19);

    static double at(LocalDate d, int h, int m) {
        return d.atTime(h, m).atZone(Z).toEpochSecond();
    }

    public static void main(String[] args) {
        bar();
        times();
        defaults();
        marks();
        markEdits();
        stored();
        night();
        choice();
        ask();
        readiness();
        texts();
        demo();
        System.out.println("hand: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void bar() {
        double[] b = HandNight.bar(DAY, Z);
        eq("the bar: 18:00 the evening before to 14:00", HandNight.clock(b[0], Z) + " " + HandNight.clock(b[1], Z), "18:00 14:00");
        eq("an ordinary bar is 20 hours", (b[1] - b[0]) / 3600, 20.0);
        eq("the spring clock change night (29 March 2026) has a bar of 19 hours", diffH(HandNight.bar(LocalDate.of(2026, 3, 29), Z)), 19.0);
        eq("the autumn clock change night (25 October 2026) has a bar of 21 hours", diffH(HandNight.bar(LocalDate.of(2026, 10, 25), Z)), 21.0);
        eq("a start at 23:00 belongs to the next day", HandNight.dayOf(at(DAY.minusDays(1), 23, 0), Z), DAY);
        eq("a start at 18:00 belongs to the next day", HandNight.dayOf(at(DAY.minusDays(1), 18, 0), Z), DAY);
        eq("a start after midnight belongs to that day", HandNight.dayOf(at(DAY, 1, 30), Z), DAY);
        eq("a start at 13:55 belongs to that day", HandNight.dayOf(at(DAY, 13, 55), Z), DAY);
        eq("a start at 17:55 belongs to that day still", HandNight.dayOf(at(DAY, 17, 55), Z), DAY);
        eq("the spring night: 01:30 belongs to its day", HandNight.dayOf(at(LocalDate.of(2026, 3, 29), 1, 30), Z), LocalDate.of(2026, 3, 29));
        eq("snap to 5 minutes: 23:02 to 23:00", HandNight.clock(HandNight.snap(at(DAY, 23, 2)), Z), "23:00");
        eq("snap to 5 minutes: 23:03 to 23:05", HandNight.clock(HandNight.snap(at(DAY, 23, 3)), Z), "23:05");
        eq("snap across midnight: 23:58 to 00:00", HandNight.clock(HandNight.snap(at(DAY, 23, 58)), Z), "00:00");
        ZoneId kathmandu = ZoneId.of("Asia/Kathmandu");
        eq("snap in a zone 5:45 ahead still gives whole 5 minutes", HandNight.clock(HandNight.snap(LocalDate.of(2026, 1, 1).atTime(23, 7).atZone(kathmandu).toEpochSecond()), kathmandu), "23:05");
        eq("the hours on the bar by the clock: minutes after 18:00", HandNight.barMinute(at(DAY, 23, 0), Z) + " " + HandNight.barMinute(at(DAY, 7, 0), Z), "300.0 780.0");
    }

    static double diffH(double[] b) {
        return (b[1] - b[0]) / 3600;
    }

    static void times() {
        double[] t = HandNight.times(DAY, Z, at(DAY.minusDays(1), 23, 2), at(DAY, 7, 4), false);
        eq("times snap", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:05");
        t = HandNight.times(DAY, Z, at(DAY.minusDays(1), 16, 0), at(DAY, 16, 0), false);
        eq("times stay on the bar", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "18:00 14:00");
        t = HandNight.times(DAY, Z, at(DAY, 3, 0), at(DAY, 2, 0), true);
        eq("Up moved before In bed: In bed gives way, 30 minutes at least", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "01:30 02:00");
        t = HandNight.times(DAY, Z, at(DAY, 3, 0), at(DAY, 2, 0), false);
        eq("In bed moved after Up: Up gives way", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "03:00 03:30");
        t = HandNight.times(DAY, Z, at(DAY, 13, 50), at(DAY, 13, 55), false);
        eq("at the bar's end the night keeps 30 minutes", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "13:30 14:00");
        // midnight: a night from 22:40 to 06:10 is 7 h 30 min
        t = HandNight.times(DAY, Z, at(DAY.minusDays(1), 22, 40), at(DAY, 6, 10), false);
        eq("a night over midnight: 7 h 30 min", HandNight.length(HandNight.asleepMin(t[0], t[1], new ArrayList<double[]>())), "7 h 30 min");
        // the clock change nights: the length is real time, the clock times stay the typed ones
        LocalDate spring = LocalDate.of(2026, 3, 29), autumn = LocalDate.of(2026, 10, 25);
        t = HandNight.times(spring, Z, at(spring.minusDays(1), 23, 0), at(spring, 7, 0), false);
        eq("spring night 23:00 to 07:00 is 7 hours asleep", HandNight.length(HandNight.asleepMin(t[0], t[1], new ArrayList<double[]>())), "7 h");
        t = HandNight.times(autumn, Z, at(autumn.minusDays(1), 23, 0), at(autumn, 7, 0), false);
        eq("autumn night 23:00 to 07:00 is 9 hours asleep", HandNight.length(HandNight.asleepMin(t[0], t[1], new ArrayList<double[]>())), "9 h");
        eq("autumn night keeps its clock times", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:00");
        eq("lengths", HandNight.length(460) + "|" + HandNight.length(45) + "|" + HandNight.length(480) + "|" + HandNight.length(Double.NaN), "7 h 40 min|45 min|8 h|--");
        check("offered for today and up to 30 days back, never ahead", HandNight.offer(DAY, DAY) && HandNight.offer(DAY.minusDays(30), DAY)
                && !HandNight.offer(DAY.minusDays(31), DAY) && !HandNight.offer(DAY.plusDays(1), DAY));
    }

    static void defaults() {
        double[] t = HandNight.defaults(DAY, Z, new ArrayList<double[]>());
        eq("no history: 23:00 to 07:00", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:00");
        List<double[]> n = new ArrayList<double[]>();
        // 20 nights: the first 6 far off (only the last 14 count), then bed 23:40, 00:10, 23:50 ... up 06:50 to 07:30
        for (int i = 0; i < 20; i++) {
            LocalDate d = DAY.minusDays(20 - i);
            if (i < 6) n.add(new double[] {at(d.minusDays(1), 20, 0), at(d, 4, 0)});
            else n.add(new double[] {at(d.minusDays(1), 23, 40) + (i % 3) * 900, at(d, 6, 50) + (i % 4) * 600});
        }
        t = HandNight.defaults(DAY, Z, n);
        // bed: 5 times 23:40, 5 times 23:55, 4 times 00:10 (median 23:55); up: 3 times 06:50, 3 times 07:00, 4 times 07:10, 4 times
        // 07:20 (median 07:10)
        eq("the median of the last 14 nights, over midnight, snapped", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:55 07:10");
        List<double[]> odd = new ArrayList<double[]>();
        odd.add(new double[] {at(DAY.minusDays(1), 3, 0), at(DAY.minusDays(1), 3, 10)});
        t = HandNight.defaults(DAY, Z, odd);
        eq("a history whose median is no night falls back to 23:00 to 07:00", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:00");
        // code review of #429: today's night may not have ended; Up is at most now, snapped down to 5 minutes
        t = HandNight.defaults(DAY, Z, new ArrayList<double[]>(), at(DAY, 5, 33) + 20);
        eq("today at 05:33: Up capped at 05:30 (down, never later than now), In bed stays 23:00", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 05:30");
        t = HandNight.defaults(DAY, Z, n, at(DAY, 6, 59));
        eq("today at 06:59 with the usual 23:55 to 07:10: Up 06:55", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:55 06:55");
        t = HandNight.defaults(DAY, Z, new ArrayList<double[]>(), at(DAY, 0, 12));
        eq("today just after midnight: Up 00:10, In bed moved to keep the shortest night (30 min)", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z)
                + " " + (t[1] - t[0]) / 60, "23:00 00:10 70.0");
        t = HandNight.defaults(DAY, Z, new ArrayList<double[]>(), at(DAY.minusDays(1), 23, 14));
        eq("still the evening before at 23:14: Up 23:10, In bed 22:40", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "22:40 23:10");
        t = HandNight.defaults(DAY, Z, new ArrayList<double[]>(), at(DAY, 9, 0));
        eq("after the usual Up (09:00): 23:00 to 07:00 unchanged", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:00");
        t = HandNight.defaults(DAY, Z, new ArrayList<double[]>(), Double.NaN);
        eq("without a time: unchanged", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "23:00 07:00");
        double[] capped = HandNight.defaults(DAY, Z, n, at(DAY, 3, 1));
        check("a capped night is never after now and on the 5 minute grid", capped[1] <= at(DAY, 3, 1) && capped[1] % HandNight.SNAP_S == 0
                && capped[0] % HandNight.SNAP_S == 0 && capped[1] - capped[0] >= HandNight.MIN_NIGHT_S);
        LocalDate spring = LocalDate.of(2026, 3, 29);
        t = HandNight.defaults(spring, Z, new ArrayList<double[]>());
        eq("the spring night's defaults are 23:00 to 07:00 by the clock, 7 hours long", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z) + " "
                + (t[1] - t[0]) / 3600, "23:00 07:00 7.0");
        LocalDate autumn = LocalDate.of(2026, 10, 25);
        t = HandNight.defaults(autumn, Z, new ArrayList<double[]>());
        eq("the autumn night's defaults are 23:00 to 07:00 by the clock, 9 hours long", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z) + " "
                + (t[1] - t[0]) / 3600, "23:00 07:00 9.0");
    }

    static void marks() {
        double s = at(DAY.minusDays(1), 23, 0), e = at(DAY, 7, 0);
        List<double[]> none = new ArrayList<double[]>();
        List<double[]> m = HandNight.addMark(s, e, none);
        eq("the first mark: 15 minutes in the middle of the night (02:52:30 snapped)", texts(m), "[02:55-03:10]");
        m = HandNight.addMark(s, e, m);
        eq("the second mark: in the middle of the longer stretch (before the first)", texts(m), "[00:50-01:05, 02:55-03:10]");
        eq("two marks: 6 h 30 min asleep, 30 min awake", HandNight.length(HandNight.asleepMin(s, e, m)) + " " + HandNight.awakeMin(s, e, m), "7 h 30 min 30.0");
        List<double[]> raw = new ArrayList<double[]>();
        raw.add(new double[] {at(DAY, 2, 2), at(DAY, 2, 21)});
        raw.add(new double[] {at(DAY, 2, 20), at(DAY, 2, 40)});
        raw.add(new double[] {at(DAY, 4, 0), at(DAY, 4, 0)});
        raw.add(new double[] {at(DAY.minusDays(1), 22, 0), at(DAY.minusDays(1), 23, 20)});
        raw.add(new double[] {at(DAY, 6, 50), at(DAY, 8, 0)});
        raw.add(new double[] {at(DAY, 5, 30), at(DAY, 5, 0)});
        raw.add(new double[] {Double.NaN, 1});
        eq("marks: snapped, merged when they touch, kept inside with sleep on both sides, empty ones dropped, turned round",
                texts(HandNight.marks(s, e, raw)), "[23:05-23:20, 02:00-02:40, 05:00-05:30, 06:50-06:55]");
        eq("stretches between the marks", HandNight.stretches(s, e, HandNight.marks(s, e, raw)).size(), 5);
        List<double[]> full = new ArrayList<double[]>();
        double ss = at(DAY, 1, 0), ee = at(DAY, 1, 30);
        List<double[]> one = HandNight.addMark(ss, ee, full);
        eq("the shortest night (30 minutes) takes one 15 minute mark with sleep on both sides", texts(one), "[01:10-01:25]");
        check("but not a second one", HandNight.addMark(ss, ee, one) == null);
        List<double[]> some = HandNight.addMark(at(DAY, 1, 0), at(DAY, 1, 40), full);
        eq("a 40 minute night takes one", texts(some), "[01:15-01:30]");
        check("but not two", HandNight.addMark(at(DAY, 1, 0), at(DAY, 1, 40), some) == null);
        // the marks on the autumn clock change night: the hour from 02:00 to 03:00 twice
        LocalDate autumn = LocalDate.of(2026, 10, 25);
        double as = at(autumn.minusDays(1), 23, 0), ae = at(autumn, 7, 0);
        List<double[]> am = HandNight.addMark(as, ae, none);
        eq("autumn night: the mark in the middle of the 9 hours", am.size() == 1 ? (am.get(0)[0] - as) / 60 : -1, 265.0);
    }

    /** Review of #429: marks near the handles, stretching short marks, handles over marks, dragging over a neighbour, the buttons. */
    static void markEdits() {
        double s = at(DAY.minusDays(1), 23, 0), e = at(DAY, 7, 0);
        List<double[]> m = new ArrayList<double[]>();
        m.add(new double[] {at(DAY.minusDays(1), 23, 10), at(DAY.minusDays(1), 23, 25)});
        m.add(new double[] {at(DAY, 3, 0), at(DAY, 3, 15)});
        m.add(new double[] {at(DAY, 6, 40), at(DAY, 6, 55)});
        m = HandNight.marks(s, e, m);
        // a touch takes a mark before the handles: one right after In bed and one right before Up
        eq("a touch on the mark 10 minutes after In bed takes that mark", HandNight.markAt(m, at(DAY.minusDays(1), 23, 15), 120), 0);
        eq("a touch on the mark just before Up takes that mark", HandNight.markAt(m, at(DAY, 6, 50), 120), 2);
        eq("a touch just beside a mark (within reach) takes it", HandNight.markAt(m, at(DAY, 3, 16), 120), 1);
        eq("a touch away from every mark takes none (the handles may)", HandNight.markAt(m, at(DAY.minusDays(1), 23, 0), 120), -1);
        eq("in the middle of two reachable marks, the nearer", HandNight.markAt(Arrays.asList(new double[] {0, 600}, new double[] {700, 1300}), 640, 120), 0);
        // a 15 minute mark is stretched at its ends: thirds of 5 minutes
        double[] k = m.get(1);
        check("by thirds: the first third its start, the last its end, the middle the whole mark", HandNight.markPart(k, at(DAY, 3, 2)) == -1
                && HandNight.markPart(k, at(DAY, 3, 13)) == 1 && HandNight.markPart(k, at(DAY, 3, 7)) == 0);
        eq("its end stretched to 03:30", texts(HandNight.resizeMark(s, e, m, 1, true, at(DAY, 3, 31))).contains("03:00-03:30"), true);
        eq("its start stretched to 02:40", texts(HandNight.resizeMark(s, e, m, 1, false, at(DAY, 2, 41))).contains("02:40-03:15"), true);
        eq("never shorter than 5 minutes", texts(HandNight.resizeMark(s, e, m, 1, true, at(DAY, 2, 0))).contains("03:00-03:05"), true);
        eq("stretched up to its neighbour, never over it", texts(HandNight.resizeMark(s, e, m, 1, true, at(DAY, 6, 50))).contains("03:00-06:40"), true);
        eq("its start stops at the night's start plus 5 minutes", texts(HandNight.resizeMark(s, e, m, 0, false, at(DAY.minusDays(1), 22, 0))), "[23:05-23:25, 03:00-03:15, 06:40-06:55]");
        // dragged over a neighbour: it stops there, the marks are not merged while dragging
        List<double[]> moved = HandNight.moveMark(s, e, m, 1, at(DAY, 6, 45));
        eq("dragged onto the next mark: stops touching it, same length, three marks still", texts(moved), "[23:10-23:25, 06:25-06:40, 06:40-06:55]");
        eq("merged once the finger is lifted (they touch)", texts(HandNight.marks(s, e, moved)), "[23:10-23:25, 06:25-06:55]");
        eq("dragged before the night: stops 5 minutes after In bed's neighbour", texts(HandNight.moveMark(s, e, m, 1, at(DAY.minusDays(1), 20, 0))),
                "[23:10-23:25, 23:25-23:40, 06:40-06:55]");
        eq("moved 5 minutes later by its button", texts(HandNight.moveMark(s, e, m, 1, m.get(1)[0] + HandNight.SNAP_S)), "[23:10-23:25, 03:05-03:20, 06:40-06:55]");
        eq("no mark: the list as it was", texts(HandNight.moveMark(s, e, m, 5, 0)), texts(m));
        eq("no mark: the list as it was (resize)", texts(HandNight.resizeMark(s, e, m, -1, true, 0)), texts(m));
        List<double[]> tight = Arrays.asList(new double[] {at(DAY, 1, 0), at(DAY, 1, 15)}, new double[] {at(DAY, 1, 15), at(DAY, 1, 30)});
        eq("no room between neighbours: the mark stays", texts(HandNight.moveMark(at(DAY, 0, 50), at(DAY, 1, 40), tight, 0, at(DAY, 1, 20))), texts(tight));
        // a handle moved over a mark stops before it: no wake up is dropped
        double[] t = HandNight.times(DAY, Z, at(DAY, 1, 0), e, false, m);
        eq("In bed dragged past the first mark stops 5 minutes before it", HandNight.clock(t[0], Z) + " " + HandNight.marks(t[0], t[1], m).size(), "23:05 3");
        t = HandNight.times(DAY, Z, s, at(DAY, 5, 0), true, m);
        eq("Up dragged before the last mark stops 5 minutes after it", HandNight.clock(t[1], Z) + " " + HandNight.marks(t[0], t[1], m).size(), "07:00 3");
        t = HandNight.times(DAY, Z, at(DAY.minusDays(1), 22, 0), at(DAY, 8, 0), false, m);
        eq("away from the marks the handles move freely", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "22:00 08:00");
        List<double[]> one = Arrays.asList(new double[] {at(DAY, 2, 0), at(DAY, 2, 5)});
        t = HandNight.times(DAY, Z, at(DAY, 1, 55), at(DAY, 2, 10), false, one);
        eq("around a 5 minute mark the night keeps 30 minutes", (t[1] - t[0]) / 60 + " " + HandNight.marks(t[0], t[1], one).size(), "30.0 1");
        t = HandNight.times(DAY, Z, at(DAY, 1, 55), at(DAY, 2, 10), true, one);
        eq("the same with Up moved last: In bed gives way", HandNight.clock(t[0], Z) + " " + HandNight.clock(t[1], Z), "01:40 02:10");
        check("without marks as before", Arrays.equals(HandNight.times(DAY, Z, s, e, false, new ArrayList<double[]>()), HandNight.times(DAY, Z, s, e, false))
                && Arrays.equals(HandNight.times(DAY, Z, s, e, false, null), HandNight.times(DAY, Z, s, e, false)));
    }

    static String texts(List<double[]> m) {
        List<String> l = new ArrayList<String>();
        for (double[] x : m) l.add(HandNight.clock(x[0], Z) + "-" + HandNight.clock(x[1], Z));
        return l.toString();
    }

    static void stored() {
        List<double[]> m = new ArrayList<double[]>();
        m.add(new double[] {1700000000, 1700000900});
        m.add(new double[] {1700003600, 1700004200});
        String t = HandNight.wakesText(m);
        eq("the marks as stored", t, "[[1700000000,1700000900],[1700003600,1700004200]]");
        List<double[]> back = HandNight.wakes(t);
        check("read back the same", back.size() == 2 && back.get(1)[0] == 1700003600 && back.get(1)[1] == 1700004200);
        eq("none", HandNight.wakesText(new ArrayList<double[]>()), "[]");
        check("empty, null and damaged texts give no marks", HandNight.wakes("[]").isEmpty() && HandNight.wakes(null).isEmpty()
                && HandNight.wakes("[[1,2],[3").isEmpty() && HandNight.wakes("[[1,x]]").isEmpty() && HandNight.wakes("").isEmpty());
    }

    static void night() {
        double s = at(DAY.minusDays(1), 23, 0), e = at(DAY, 7, 0);
        List<double[]> m = new ArrayList<double[]>();
        m.add(new double[] {at(DAY, 2, 0), at(DAY, 2, 15)});
        m.add(new double[] {at(DAY, 4, 0), at(DAY, 4, 15)});
        Night n = HandNight.night(s, e, m, Z, 40);
        check("the night: In bed to Up, asleep without the marks", n.start == s && n.end == e && n.inBedMin == 480 && n.asleepMin == 450 && n.awakeMin == 30);
        check("each mark an awakening, wake time the marks", n.awakenings == 2 && n.wasoMin == 30);
        check("no stages, source hand", n.stages.length == 0 && HandNight.SOURCE.equals(n.stageSource) && Double.isNaN(n.deepMin) && Double.isNaN(n.remMin)
                && Double.isNaN(n.lightMin));
        check("efficiency, latency, heart rate and HRV empty", Double.isNaN(n.efficiency) && Double.isNaN(n.latencyMin) && Double.isNaN(n.lowestHr)
                && Double.isNaN(n.avgHrv) && Double.isNaN(n.restlessPct));
        check("the midpoint and sleep times", n.midpoint == (s + e) / 2 && n.sleepStart == s && n.sleepEnd == e && n.main);
        List<String> keys = new ArrayList<String>();
        for (Night.Contributor c : n.score.contributors) keys.add(c.key);
        eq("the score from total sleep, wake ups and timing only", keys, HandNight.SCORED);
        double w = 0, sum = 0;
        for (Night.Contributor c : n.score.contributors) {
            w += c.weight;
            sum += c.weight * c.score;
        }
        eq("the score is the weighted mean of those parts", n.score.value, (int) Math.round(sum / w));
        Night ring = new Night();
        ring.asleepMin = 450;
        ring.wasoMin = 30;
        ring.awakenings = 2;
        ring.midpoint = n.midpoint;
        ring.stageSource = "none";
        int off = Z.getRules().getOffset(java.time.Instant.ofEpochSecond((long) n.midpoint)).getTotalSeconds();
        eq("the same as Scores.sleep for a night with only these values", n.score.value, Scores.sleep(ring, off, 40).value);
        Night none = HandNight.night(s, e, new ArrayList<double[]>(), Z, 40);
        check("no wake ups: restfulness from 0 wake ups and 0 minutes awake", none.awakenings == 0 && none.wasoMin == 0 && none.score.get("restfulness") != null
                && none.score.value >= n.score.value);
        Night late = HandNight.night(at(DAY, 3, 0), at(DAY, 11, 0), new ArrayList<double[]>(), Z, 40);
        check("a late night scores lower timing", late.score.get("timing").score < none.score.get("timing").score);
        Night shortN = HandNight.night(at(DAY, 2, 0), at(DAY, 6, 0), new ArrayList<double[]>(), Z, 40);
        check("a short night scores lower total sleep", shortN.score.get("total").score < none.score.get("total").score);
        LocalDate spring = LocalDate.of(2026, 3, 29);
        Night sp = HandNight.night(at(spring.minusDays(1), 23, 0), at(spring, 7, 0), new ArrayList<double[]>(), Z, 40);
        check("the spring night: 7 hours asleep", sp.asleepMin == 420 && sp.inBedMin == 420);
        check("overlap", HandNight.overlaps(s, e, Arrays.asList(new double[] {e - 60, e + 3600})) && !HandNight.overlaps(s, e, Arrays.asList(new double[] {e, e + 3600})));
    }

    static void choice() {
        eq("ring night: the ring's, also with one by hand", MainDevice.nightFrom(MainDevice.RING, true, false, true), MainDevice.RING);
        eq("watch night, ring main without a night: the watch's", MainDevice.nightFrom(MainDevice.RING, false, true, true), MainDevice.WATCH);
        eq("watch main with both devices: the watch's", MainDevice.nightFrom(MainDevice.WATCH, true, true, true), MainDevice.WATCH);
        eq("phone main, ring night: the ring's", MainDevice.nightFrom(MainDevice.PHONE, true, false, true), MainDevice.RING);
        eq("no device night: the one by hand fills", MainDevice.nightFrom(MainDevice.RING, false, false, true), MainDevice.HAND);
        eq("no device night, watch main: by hand", MainDevice.nightFrom(MainDevice.WATCH, false, false, true), MainDevice.HAND);
        eq("nothing: none", MainDevice.nightFrom(MainDevice.RING, false, false, false), -1);
        for (int main : new int[] {MainDevice.RING, MainDevice.WATCH, MainDevice.PHONE}) {
            for (int bits = 0; bits < 4; bits++) {
                boolean r = (bits & 1) != 0, w = (bits & 2) != 0;
                eq("the three source rule keeps the two device rule (" + main + ", " + bits + ")", MainDevice.nightFrom(main, r, w, false), MainDevice.nightFrom(main, r, w));
                if (r || w) eq("a device night always wins (" + main + ", " + bits + ")", MainDevice.nightFrom(main, r, w, true), MainDevice.nightFrom(main, r, w));
            }
        }
        check("by hand is never a main device", !MainDevice.known(MainDevice.HAND) && MainDevice.valid(MainDevice.HAND) == MainDevice.RING);
        eq("replaced by the ring", HandNight.replaced(MainDevice.RING), "Replaced by your ring's night");
        eq("replaced by the watch", HandNight.replaced(MainDevice.WATCH), "Replaced by your watch's night");
        Start.Has ring = new Start.Has(true, false, false);
        List<String> l = TodayCards.of(ring, false, false, false, true);
        eq("Today: the question right after Your day, the first screen stays the scores and Your day", l.subList(0, 4).toString(), "[scores, day, hand_ask, life]");
        eq("without the question: as before", TodayCards.of(ring, true, true, true, false), TodayCards.of(ring, true, true, true));
        check("Your day still second", TodayCards.dayIndex(l) == 1);
    }

    static void ask() {
        long now = (long) at(DAY, 8, 30);
        long synced = (long) at(DAY, 8, 0);
        check("after a sync this morning, no night, a device: asks", HandNight.ask(DAY, DAY, false, true, synced, now, Z, null));
        check("a night shown: no", !HandNight.ask(DAY, DAY, true, true, synced, now, Z, null));
        check("not today: no", !HandNight.ask(DAY.minusDays(1), DAY, false, true, synced, now, Z, null));
        check("no device: no", !HandNight.ask(DAY, DAY, false, false, synced, now, Z, null));
        check("Not now for today: no", !HandNight.ask(DAY, DAY, false, true, synced, now, Z, DAY.toString()));
        check("Not now yesterday: asks again today", HandNight.ask(DAY, DAY, false, true, synced, now, Z, DAY.minusDays(1).toString()));
        check("last sync before the night ended (yesterday evening): no", !HandNight.ask(DAY, DAY, false, true, (long) at(DAY.minusDays(1), 22, 0), now, Z, null));
        check("never synced: no", !HandNight.ask(DAY, DAY, false, true, 0, now, Z, null));
        check("synced at 04:00: asks", HandNight.ask(DAY, DAY, false, true, (long) at(DAY, 4, 0), now, Z, null));
        check("a sync time in the future (a clock set back): no", !HandNight.ask(DAY, DAY, false, true, now + 3600, now, Z, null));
    }

    static void readiness() {
        double[] act = new double[28], tgt = new double[28], cov = new double[28];
        Arrays.fill(act, 500);
        Arrays.fill(tgt, 500);
        Arrays.fill(cov, 900);
        double[] a14 = new double[14];
        Arrays.fill(a14, 7.5 * 3600);
        HandNight.Ready r = HandNight.readiness(80, act, tgt, cov, true, a14, 8 * 3600);
        List<String> keys = new ArrayList<String>();
        for (Night.Contributor c : r.score.contributors) keys.add(c.key);
        eq("readiness from a night by hand: sleep, sleep balance and the activity parts, no heart rate, HRV, temperature or recovery", keys,
                Arrays.asList("sleep", "sleep_balance", "prev_activity", "activity_balance"));
        check("not provisional, yesterday worn", !r.score.provisional && !r.prevUnworn && r.score.value > 0);
        cov[27] = 10;
        r = HandNight.readiness(80, act, tgt, cov, true, a14, 8 * 3600);
        check("yesterday not worn: Previous day activity left out and said", r.prevUnworn && r.score.get("prev_activity") == null);
        double[] two = new double[14];
        Arrays.fill(two, Double.NaN);
        two[0] = two[1] = 7 * 3600;
        r = HandNight.readiness(Double.NaN, new double[0], new double[0], new double[0], false, two, 8 * 3600);
        check("no sleep score and fewer than 3 nights: nothing to score", r.score.contributors.isEmpty() && r.score.value == -1);
        eq("the sleep need without 14 nights is 8 hours", HandNight.need(new double[90]), 8 * 3600);
    }

    static void texts() {
        List<String> all = new ArrayList<String>(Arrays.asList(HandNight.ENTER, HandNight.IN_BED, HandNight.UP, HandNight.WOKE, HandNight.ASLEEP,
                HandNight.ENTERED, HandNight.SCORE_LINE, HandNight.ASK, HandNight.ASK_ENTER, HandNight.NOT_NOW, HandNight.SAVE, HandNight.CHANGE,
                HandNight.DELETE, HandNight.NOT_MEASURED, HandNight.MARK_HINT, HandNight.BAR_HINT, HandNight.NO_ROOM, HandNight.DELETED,
                HandNight.SAVED, HandNight.CHANGED, HandNight.UNREADABLE, HandNight.EARLIER, HandNight.LATER, HandNight.LONGER,
                HandNight.SHORTER, HandNight.REMOVE, HandNight.STRIP, HandNight.replaced(MainDevice.RING), HandNight.replacedNote(MainDevice.WATCH),
                HandNight.title(DAY, Locale.UK), HandNight.markText(new double[] {at(DAY, 2, 0), at(DAY, 2, 15)}, Z)));
        all.addAll(Arrays.asList(app.openring.ui.Explain.handTexts()));
        for (String t : all) {
            check("plain text without dashes: " + t, !t.contains(" - ") && !t.contains("–") && !t.contains("—") && !t.contains("--") && !t.trim().isEmpty());
        }
        eq("the texts of the design", HandNight.ENTER + "|" + HandNight.WOKE + "|" + HandNight.ASLEEP + "|" + HandNight.ENTERED + "|" + HandNight.SCORE_LINE,
                "Enter the night by hand|I woke up in between|Asleep (by hand)|Entered by hand|From a night by hand, without heart rate or stages");
        eq("Today's question", HandNight.ASK, "No night for last night. Did you sleep without your ring and watch?");
        eq("the title", HandNight.title(DAY, Locale.UK), "The night before Thursday 19 November");
        eq("a mark", HandNight.markText(new double[] {at(DAY, 2, 0), at(DAY, 2, 15)}, Z), "Awake 02:00 to 02:15 (15 min)");
    }

    static void demo() {
        List<double[]> d = HandNight.demo(DAY, HandNight.DEMO_BACK, Z);
        LocalDate day = DAY.minusDays(2);
        check("the demo's night: 23:10 to 06:50 the day before yesterday, two marks inside", d.size() == 3 && HandNight.dayOf(d.get(0)[0], Z).equals(day)
                && HandNight.clock(d.get(0)[0], Z).equals("23:10") && HandNight.clock(d.get(0)[1], Z).equals("06:50")
                && HandNight.marks(d.get(0)[0], d.get(0)[1], d.subList(1, 3)).size() == 2);
    }
}
