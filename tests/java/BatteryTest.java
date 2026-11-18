import app.openring.core.DeviceBattery;
import app.openring.core.DeviceBattery.Reading;
import app.openring.core.DeviceBattery.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The battery rows on Today (owner's wish of 15 November, {@code core/DeviceBattery}): the age text, the low warning, old
 * readings, charging, and the days left estimate only when the readings since the last charge fell steadily. Made up readings
 * only. Run with tests/run_tests.sh.
 */
public class BatteryTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) System.out.println("  got: " + got + " want: " + want);
        check(name, ok);
    }

    static final long NOW = 1_800_000_000L, H = 3600, D = 86400;

    /** Readings every {@code step} seconds, the last {@code lastAge} before NOW, falling {@code perDay} from {@code start}. */
    static List<Reading> falling(int start, double perDay, int n, long step, long lastAge) {
        List<Reading> out = new ArrayList<Reading>();
        long t0 = NOW - lastAge - (n - 1) * step;
        for (int i = 0; i < n; i++) {
            long t = t0 + i * step;
            out.add(new Reading(t, (int) Math.round(start - perDay * (t - t0) / D), -1));
        }
        return out;
    }

    public static void main(String[] args) {
        ago();
        days();
        rows();
        estimate();
        demo();
        System.out.println("battery: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void ago() {
        eq("ago: under 2 minutes is just now", DeviceBattery.ago(119), "just now");
        eq("ago: a reading ahead of the clock is just now", DeviceBattery.ago(-500), "just now");
        eq("ago: minutes", DeviceBattery.ago(5 * 60 + 59), "5 min ago");
        eq("ago: hours, rounded down", DeviceBattery.ago(2 * H + 59 * 60), "2 h ago");
        eq("ago: 23 hours", DeviceBattery.ago(D - 1), "23 h ago");
        eq("ago: one day", DeviceBattery.ago(D + 5 * H), "1 day ago");
        eq("ago: days", DeviceBattery.ago(3 * D + H), "3 days ago");
    }

    static void days() {
        eq("days: under one", DeviceBattery.daysText(0.4), "less than a day left");
        eq("days: one", DeviceBattery.daysText(1.3), "about 1 day left");
        eq("days: rounded", DeviceBattery.daysText(2.6), "about 3 days left");
    }

    static void rows() {
        Status none = DeviceBattery.of(DeviceBattery.RING, Collections.<Reading>emptyList(), NOW);
        eq("no reading: says so", none.value(), "No reading yet");
        eq("no reading: the ring syncs", none.note(), "sync to read it");
        check("no reading: no warning, no estimate", !none.warn() && Double.isNaN(none.daysLeft) && none.newest == null);
        eq("no reading: the watch's own words", DeviceBattery.of(DeviceBattery.WATCH, Collections.<Reading>emptyList(), NOW).note(),
                "sync the watch to read it");
        eq("names", DeviceBattery.of(DeviceBattery.WATCH, Collections.<Reading>emptyList(), NOW).name(), "Watch");

        Status one = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 2 * H, 72, -1)), NOW);
        eq("one reading: level and age", one.value(), "72 %, 2 h ago");
        check("one reading: no estimate, nothing more to say", Double.isNaN(one.daysLeft) && one.note() == null && !one.warn());

        // the newest wins, in any order
        Status order = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - H, 50, -1), new Reading(NOW - 5 * H, 60, -1)), NOW);
        eq("order: the newest reading", order.value(), "50 %, 1 h ago");

        Status low = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 600, DeviceBattery.LOW, -1)), NOW);
        check("low: at the threshold warns", low.low && low.warn());
        eq("low: charge soon", low.note(), "charge soon");
        Status justAbove = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 600, DeviceBattery.LOW + 1, -1)), NOW);
        check("low: one above does not warn", !justAbove.low && !justAbove.warn());

        Status old = DeviceBattery.of(DeviceBattery.RING, falling(80, 12, 10, 3 * H, 3 * D), NOW);
        check("old: over 2 days", old.old && Double.isNaN(old.daysLeft));
        eq("old: says the level may be lower", old.note(), "may be lower now");
        eq("old: its age honestly", old.value().endsWith("3 days ago"), true);
        Status edge = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - DeviceBattery.OLD_S, 50, -1)), NOW);
        check("old: exactly 2 days is not old yet", !edge.old);
        Status oldLow = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 4 * D, 15, -1)), NOW);
        eq("old and low: both said", oldLow.note(), "charge soon, may be lower now");
        check("old and low: warns", oldLow.warn());

        // the watch says it charges (state 0): only in a fresh reading
        Status ch = DeviceBattery.of(DeviceBattery.WATCH, Arrays.asList(new Reading(NOW - 30 * 60, 15, 1)), NOW);
        check("charging: said, no warning, no estimate", ch.charging && !ch.warn() && Double.isNaN(ch.daysLeft));
        eq("charging: the word", ch.note(), "charging");
        Status chOld = DeviceBattery.of(DeviceBattery.WATCH, Arrays.asList(new Reading(NOW - 2 * H, 40, 1)), NOW);
        check("charging: an older reading does not claim it", !chOld.charging && chOld.note() == null);

        // the ring does not say: a rise since the reading before, in a fresh reading
        List<Reading> rise = Arrays.asList(new Reading(NOW - 4 * H, 30, -1), new Reading(NOW - 20 * 60, 30 + DeviceBattery.CHARGE_RISE, -1));
        Status r = DeviceBattery.of(DeviceBattery.RING, rise, NOW);
        check("rising: fresh rise", r.rising && !r.charging && !r.warn());
        eq("rising: honest words", r.note(), "charging or just charged");
        Status rOld = DeviceBattery.of(DeviceBattery.RING, rise, NOW + 2 * H);
        check("rising: not after an hour", !rOld.rising);
        Status small = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 4 * H, 30, -1), new Reading(NOW - 600, 32, -1)), NOW);
        check("rising: 2 points is noise", !small.rising);
        Status notCharging = DeviceBattery.of(DeviceBattery.WATCH, Arrays.asList(new Reading(NOW - 4 * H, 30, 0), new Reading(NOW - 600, 40, 0)), NOW);
        check("rising: the watch said it does not charge", !notCharging.rising);
        Status lowRise = DeviceBattery.of(DeviceBattery.RING, Arrays.asList(new Reading(NOW - 4 * H, 5, -1), new Reading(NOW - 600, 12, -1)), NOW);
        check("rising: low but on the charger does not warn", lowRise.low && !lowRise.warn());
    }

    static void estimate() {
        // the ring: every 3 hours, 12 points a day, from 100 over two days
        List<Reading> ring = falling(100, 12, 17, 3 * H, H);
        double left = DeviceBattery.daysLeft(ring, NOW);
        // last reading 100 - 24 = 76 an hour ago, 0.5 points since: about 6.3 days
        check("estimate: steady ring", Math.abs(left - 75.5 / 12) < 0.2);
        Status s = DeviceBattery.of(DeviceBattery.RING, ring, NOW);
        eq("estimate: the words", s.note(), "about 6 days left");

        // only the readings since the last charge count
        List<Reading> withCharge = new ArrayList<Reading>(falling(40, 30, 8, 3 * H, 3 * D));
        withCharge.addAll(ring);
        check("estimate: the drop before a charge is left out", Math.abs(DeviceBattery.daysLeft(withCharge, NOW) - left) < 1e-9);
        // a reading the watch marked as charging ends the run too
        List<Reading> marked = new ArrayList<Reading>();
        marked.add(new Reading(ring.get(0).t - H, 99, 1));
        marked.addAll(ring);
        check("estimate: a charging reading ends the run", Math.abs(DeviceBattery.daysLeft(marked, NOW) - left) < 1e-9);
        // only the last 7 days
        List<Reading> month = falling(100, 2, 30, D, H);
        List<Reading> week = month.subList(month.size() - 8, month.size());
        check("estimate: at most 7 days back", Math.abs(DeviceBattery.daysLeft(month, NOW) - DeviceBattery.daysLeft(week, NOW)) < 1e-9);

        // too few, too short, too slow, not steady, empty
        check("estimate: two readings are too few", Double.isNaN(DeviceBattery.daysLeft(falling(90, 12, 2, D, H), NOW)));
        check("estimate: under half a day is too short", Double.isNaN(DeviceBattery.daysLeft(falling(90, 12, 4, H, H), NOW)));
        check("estimate: flat is too slow", Double.isNaN(DeviceBattery.daysLeft(falling(90, 0.5, 8, D / 2, H), NOW)));
        List<Reading> jumpy = new ArrayList<Reading>();
        int[] pcts = {80, 60, 79, 58, 77, 56, 76};
        for (int i = 0; i < pcts.length; i++) jumpy.add(new Reading(NOW - (pcts.length - i) * 6 * H, pcts[i], -1));
        // rises of 19 are charges: only the last reading remains; with 2 point steps the noise is not steady
        check("estimate: jumps up and down give none", Double.isNaN(DeviceBattery.daysLeft(jumpy, NOW)));
        List<Reading> noisy = new ArrayList<Reading>();
        int[] np = {80, 78, 80, 77, 79, 76, 78, 75};
        for (int i = 0; i < np.length; i++) noisy.add(new Reading(NOW - (np.length - i) * 6 * H, np[i], -1));
        check("estimate: wobbling readings are not steady", Double.isNaN(DeviceBattery.daysLeft(noisy, NOW)));
        check("estimate: none without readings", Double.isNaN(DeviceBattery.daysLeft(Collections.<Reading>emptyList(), NOW)));
        List<Reading> same = new ArrayList<Reading>();
        for (int i = 0; i < 4; i++) same.add(new Reading(NOW - D, 50 - i, -1));
        check("estimate: all at one time gives none", Double.isNaN(DeviceBattery.daysLeft(same, NOW)));

        // a reading long ago that the line would put below empty: zero, not negative
        List<Reading> past = falling(30, 12, 6, 3 * H, H);
        check("estimate: never below zero", DeviceBattery.daysLeft(past, NOW + 10 * D) == 0);
        Status lowLeft = DeviceBattery.of(DeviceBattery.RING, falling(22, 12, 6, 3 * H, H), NOW);
        eq("estimate: low with days left", lowLeft.note(), "charge soon, about 1 day left");

        // the watch: one reading a day, 3 points a day
        Status watch = DeviceBattery.of(DeviceBattery.WATCH, falling(100, 3, 8, D, 600), NOW);
        eq("estimate: watch", watch.note(), "about 26 days left");
    }

    static void demo() {
        long from = NOW - 20 * H, to = from + 2 * H;
        List<Reading> r = DeviceBattery.demoRing(NOW, 35, from, to);
        check("demo: one every 3 hours over 35 days", r.size() >= 35 * 8 - 1 && r.size() <= 35 * 8 + 1);
        boolean inRange = true, rising = false;
        for (int i = 0; i < r.size(); i++) {
            Reading x = r.get(i);
            inRange &= x.pct >= 1 && x.pct <= 100 && x.t <= NOW - 600 && x.charging == -1;
            if (i > 0 && x.pct - r.get(i - 1).pct >= DeviceBattery.CHARGE_RISE) rising = true;
        }
        check("demo: levels between 1 and 100, none in the last 10 minutes", inRange);
        check("demo: charged at least once", rising);
        eq("demo: full at the end of the charge", DeviceBattery.demoLevel(to, from, to), 100);
        eq("demo: 12 points a day after it", DeviceBattery.demoLevel(to + D, from, to), 88);
        check("demo: half way up while charging", DeviceBattery.demoLevel(from + H, from, to) > DeviceBattery.demoLevel(from, from, to));
        check("demo: the charge 6 days before too", DeviceBattery.demoLevel(to - 6 * D, from, to) == 100);
        check("demo: never empty", DeviceBattery.demoLevel(to + 20 * D, from, to) == 1);
        Status s = DeviceBattery.of(DeviceBattery.RING, r, NOW);
        check("demo: a steady ring gives days left", !Double.isNaN(s.daysLeft) && !s.warn());
    }
}
