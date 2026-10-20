import app.openring.core.analysis.*;

import java.util.*;

/**
 * JVM tests for the ring finder (issue #74): signal smoothing and bands, the turn on the spot
 * direction and when the ring came off. Run with tests/run_tests.sh, after CoreTest.
 */
public class FinderTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  got " + detail);
        check(name, ok);
    }

    public static void main(String[] args) {
        smoothing();
        bands();
        clicks();
        chartAxis();
        headings();
        turns();
        turnRules();
        lastWorn();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ signal smoothing

    static void smoothing() {
        Random r = new Random(1);
        Proximity p = new Proximity();
        check("nothing heard yet", Double.isNaN(p.value()) && p.band() == Proximity.NOT_HEARD && p.sinceLast(0) == -1);
        // steady -70 with 4 dB noise and spikes of 20 dB now and then, 5 readings a second
        long t = 0;
        double worst = 0;
        for (int i = 0; i < 300; i++) {
            double v = -70 + r.nextGaussian() * 4;
            if (i % 37 == 0) v += 20; // a single spike
            if (i % 41 == 0) v -= 25; // a single dip
            p.add(t, v);
            if (i > 10) worst = Math.max(worst, Math.abs(p.value() + 70));
            t += 200;
        }
        check("smoothed stays near the true value despite spikes", worst < 5, worst);
        double before = p.value();
        p.add(t, -20); // one wild reading moves the median little
        check("one spike moves the value less than 3 dB", Math.abs(p.value() - before) < 3, p.value() - before);
        // walking closer: the value follows within about a second
        for (int i = 0; i < 8; i++) {
            t += 200;
            p.add(t, -55);
        }
        check("follows a real change within 8 readings", Math.abs(p.value() + 55) < 1.5, p.value());
        check("since last reading", p.sinceLast(t + 300) == 300);
        // a dropout: still heard after 9 s, not heard after 10 s, and starts over afterwards
        p.tick(t + 9000);
        check("still heard after 9 s", p.band() != Proximity.NOT_HEARD);
        p.tick(t + 10000);
        check("not heard after 10 s", p.band() == Proximity.NOT_HEARD && Double.isNaN(p.value()));
        p.add(t + 12000, -85);
        check("starts over after a dropout (no old value mixed in)", p.value() == -85, p.value());
        // a reading after a long gap without tick also starts over
        p.add(t + 30000, -60);
        check("long gap without tick starts over", p.value() == -60, p.value());
        // median of an even count
        Proximity q = new Proximity();
        q.add(0, -60);
        q.add(100, -70);
        check("two readings: average of both as median, then 35 % of the way", Math.abs(q.value() + 61.75) < 1e-9, q.value());
    }

    static void bands() {
        check("plain bands", Proximity.bandOf(-50) == Proximity.CLOSE && Proximity.bandOf(-60) == Proximity.CLOSE
                && Proximity.bandOf(-65) == Proximity.ROOM && Proximity.bandOf(-80) == Proximity.NEARBY
                && Proximity.bandOf(-90) == Proximity.FAR && Proximity.bandOf(Double.NaN) == Proximity.NOT_HEARD);
        // hysteresis: wandering 2 dB around the -72 border never flips the label
        int band = Proximity.bandOf(-71);
        int flips = 0;
        for (int i = 0; i < 200; i++) {
            double v = -72 + 2 * Math.sin(i * 0.7);
            int nb = Proximity.bandFor(v, band);
            if (nb != band) flips++;
            band = nb;
        }
        check("no flicker at a border", flips == 0, flips);
        check("closer only 4 dB past the border", Proximity.bandFor(-68.5, Proximity.NEARBY) == Proximity.NEARBY
                && Proximity.bandFor(-68, Proximity.NEARBY) == Proximity.ROOM);
        check("farther only 4 dB past the border", Proximity.bandFor(-76, Proximity.ROOM) == Proximity.ROOM
                && Proximity.bandFor(-76.5, Proximity.ROOM) == Proximity.NEARBY);
        check("a big jump crosses several bands", Proximity.bandFor(-50, Proximity.FAR) == Proximity.CLOSE
                && Proximity.bandFor(-95, Proximity.CLOSE) == Proximity.FAR);
        check("a jump just past the far border stops in between", Proximity.bandFor(-58, Proximity.FAR) == Proximity.ROOM, Proximity.bandFor(-58, Proximity.FAR));
        check("from not heard: plain band", Proximity.bandFor(-80, Proximity.NOT_HEARD) == Proximity.NEARBY);
        check("lost: not heard", Proximity.bandFor(Double.NaN, Proximity.ROOM) == Proximity.NOT_HEARD);
        // the same through the smoother: a noisy walk across -72 with 4 dB noise changes the label rarely
        Random r = new Random(7);
        Proximity p = new Proximity();
        int last = -1, changes = 0;
        for (int i = 0; i < 500; i++) {
            p.add(i * 200L, -72 + r.nextGaussian() * 4);
            if (last >= 0 && p.band() != last) changes++;
            last = p.band();
        }
        System.out.println("  label changes in 100 s at a border with 4 dB noise: " + changes);
        check("noisy signal at a border: label changes at most 3 times in 100 s", changes <= 3, changes);
        String[] labels = {Proximity.label(Proximity.CLOSE), Proximity.label(Proximity.ROOM), Proximity.label(Proximity.NEARBY),
                Proximity.label(Proximity.FAR), Proximity.label(Proximity.NOT_HEARD)};
        check("labels from the plan", labels[0].equals("Very close (within an arm)") && labels[1].equals("In this room")
                && labels[2].equals("Nearby") && labels[3].equals("Far or behind walls") && labels[4].equals("Not heard for 10 seconds"));
    }

    static void chartAxis() {
        check("ago: now", Proximity.ago(0).equals("now") && Proximity.ago(2.4).equals("now") && Proximity.ago(-5).equals("now"));
        check("ago: seconds", Proximity.ago(35).equals("35 s ago") && Proximity.ago(59.4).equals("59 s ago"), Proximity.ago(35));
        check("ago: minutes", Proximity.ago(60).equals("1 min ago") && Proximity.ago(120).equals("2 min ago") && Proximity.ago(90).equals("1 min 30 s ago"), Proximity.ago(90));
        double[] narrow = Proximity.chartRange(-62, -60, Proximity.CHART_MIN_SPAN);
        check("chart range: at least 20 dB around the middle", narrow[0] == -71 && narrow[1] == -51, Arrays.toString(narrow));
        double[] wide = Proximity.chartRange(-90, -50, Proximity.CHART_MIN_SPAN);
        check("chart range: a wide signal keeps its own range with room", wide[0] == -96 && wide[1] == -44, Arrays.toString(wide));
        double[] one = Proximity.chartRange(-60, -60, Proximity.CHART_MIN_SPAN);
        check("chart range: one value", one[1] - one[0] == 20 && one[0] == -70, Arrays.toString(one));
    }

    static void clicks() {
        check("fill ends", Proximity.fill(-100) == 0 && Proximity.fill(-45) == 1 && Proximity.fill(-120) == 0
                && Proximity.fill(-30) == 1 && Proximity.fill(Double.NaN) == 0);
        check("click interval ends", Proximity.clickIntervalMs(-100) == Proximity.CLICK_SLOW_MS
                && Proximity.clickIntervalMs(-45) == Proximity.CLICK_FAST_MS && Proximity.clickIntervalMs(Double.NaN) == -1);
        long prev = Long.MAX_VALUE;
        boolean falling = true;
        for (double v = -100; v <= -45; v += 1) {
            long iv = Proximity.clickIntervalMs(v);
            if (iv >= prev) falling = false;
            prev = iv;
        }
        check("clicks faster with every dB closer", falling);
    }

    // ------------------------------------------------------------------ turn on the spot

    /** Rotation matrix (row major, phone to world) of a phone upright on the chest facing {@code heading}, tilted forward by {@code tilt}. */
    static float[] upright(double heading, double tilt) {
        double h = Math.toRadians(heading), a = Math.toRadians(tilt);
        // the back of the phone (-Z) points to the heading, tilted down by a
        double bx = Math.sin(h) * Math.cos(a), by = Math.cos(h) * Math.cos(a), bz = -Math.sin(a);
        // phone Z = -back
        double zx = -bx, zy = -by, zz = -bz;
        // phone X = right hand side: horizontal, perpendicular to the heading
        double xx = Math.cos(h), xy = -Math.sin(h), xz = 0;
        // phone Y = Z x X
        double yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
        return new float[] {(float) xx, (float) yx, (float) zx, (float) xy, (float) yy, (float) zy, (float) xz, (float) yz, (float) zz};
    }

    static void headings() {
        for (double h : new double[] {0, 45, 90, 200, 359}) {
            double[] o = SearchWalk.headingOf(upright(h, 0));
            check("heading " + h, SearchWalk.diff(o[0], h) < 0.01 && o[1] < 0.01, Arrays.toString(o));
        }
        double[] tilted = SearchWalk.headingOf(upright(120, 30));
        check("tilted 30 degrees keeps the heading", SearchWalk.diff(tilted[0], 120) < 0.01 && Math.abs(tilted[1] - 30) < 0.01, Arrays.toString(tilted));
        // lying flat, screen up: the back points down, tilt 90
        double[] flat = SearchWalk.headingOf(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        check("flat phone reads as tilted 90", Math.abs(flat[1] - 90) < 0.01, Arrays.toString(flat));
        check("empty matrix", Double.isNaN(SearchWalk.headingOf(new float[9])[0]));
        check("norm and diff", SearchWalk.norm(-30) == 330 && SearchWalk.norm(720) == 0 && SearchWalk.diff(350, 10) == 20 && SearchWalk.diff(10, 190) == 180);
    }

    /**
     * A simulated turn: 20 s once around at 50 orientation samples a second (heading noise 5
     * degrees), 8 signal readings a second. Signal -66 dBm facing the ring, the body takes up to
     * 10 dB away from behind, 4 dB noise, 20 % of readings lost and half of them from behind.
     */
    static SearchWalk turn(double ringAt, long seed, double startAt) {
        return turn(ringAt, seed, startAt, 180);
    }

    /** The same turn, but the ring is heard only within {@code heardWithin} degrees of facing it (the body hides it otherwise). */
    static SearchWalk turn(double ringAt, long seed, double startAt, double heardWithin) {
        Random r = new Random(seed);
        SearchWalk w = new SearchWalk();
        long dur = 20000;
        double nextRead = 0;
        for (long t = 0; t <= dur; t += 20) {
            double faced = startAt + 360.0 * t / dur + r.nextGaussian() * 5;
            w.heading(t, faced, true);
            if (t >= nextRead) {
                nextRead += 125;
                double off = Math.toRadians(SearchWalk.diff(faced, ringAt));
                double behind = (1 - Math.cos(off)) / 2; // 0 facing, 1 behind
                if (SearchWalk.diff(faced, ringAt) > heardWithin) continue;
                if (r.nextDouble() < 0.2) continue;
                if (behind > 0.75 && r.nextDouble() < 0.5) continue;
                w.rssi(t, -66 - 10 * behind + r.nextGaussian() * 4);
            }
        }
        return w;
    }

    static void turns() {
        for (double ring : new double[] {0, 90, 200}) {
            double sum = 0, worst = 0;
            int unclear = 0, runs = 40;
            for (int s = 0; s < runs; s++) {
                SearchWalk w = turn(ring, 1000 + s, 0);
                SearchWalk.Direction d = w.direction();
                if (d == null) {
                    worst = 999;
                    continue;
                }
                double e = SearchWalk.diff(d.degrees, ring);
                sum += e;
                worst = Math.max(worst, e);
                if (!d.clear) unclear++;
            }
            System.out.printf(Locale.US, "  turn, ring at %3.0f: mean error %.1f, worst %.1f degrees over %d turns%n", ring, sum / runs, worst, runs);
            check("turn: ring at " + ring + " found within 30 degrees every time", worst <= 30, worst);
            check("turn: ring at " + ring + " mean error under 10 degrees", sum / runs < 10, sum / runs);
            check("turn: ring at " + ring + " reads as a clear direction", unclear == 0, unclear);
        }
        // silent from behind: the ring is heard only from the front, the faced but silent directions count as weak
        for (double within : new double[] {50, 70, 100}) {
            for (double ring : new double[] {0, 90, 200}) {
                double sum = 0, worst = 0;
                int unclear = 0, runs = 40;
                for (int s = 0; s < runs; s++) {
                    SearchWalk.Direction d = turn(ring, 2000 + s, 0, within).direction();
                    if (d == null) {
                        worst = 999;
                        continue;
                    }
                    double e = SearchWalk.diff(d.degrees, ring);
                    sum += e;
                    worst = Math.max(worst, e);
                    if (!d.clear) unclear++;
                }
                System.out.printf(Locale.US, "  silent from behind, heard within %3.0f, ring at %3.0f: mean error %.1f, worst %.1f degrees%n", within, ring, sum / runs, worst);
                check("silent from behind (heard within " + within + "), ring at " + ring + ": within 30 degrees every time", worst <= 30, worst);
                check("silent from behind (heard within " + within + "), ring at " + ring + ": clear every time", unclear == 0, unclear);
            }
        }
        // starting the turn facing another way changes nothing: headings are relative to the start
        SearchWalk.Direction d = turn(90, 5, 170).direction();
        check("turn starting at 170 degrees", d != null && SearchWalk.diff(d.degrees, 90) <= 30, d == null ? null : d.degrees);
        // no body shadow and a flat signal: no clear direction
        Random r = new Random(3);
        SearchWalk flat = new SearchWalk();
        for (long t = 0; t <= 20000; t += 20) {
            flat.heading(t, 360.0 * t / 20000, true);
            if (t % 120 == 0) flat.rssi(t, -60 + r.nextGaussian() * 1.5);
        }
        SearchWalk.Direction fd = flat.direction();
        check("flat signal: no clear direction", fd != null && !fd.clear && fd.spread < SearchWalk.CLEAR_DB, fd == null ? null : fd.spread);
    }

    static void turnRules() {
        SearchWalk w = new SearchWalk();
        check("no heading yet: reading not counted", !w.rssi(0, -60) && w.samples().isEmpty() && Double.isNaN(w.heading()));
        w.heading(0, 10, true);
        check("fresh heading: counted", w.rssi(100, -60) && w.samples().size() == 1 && w.samples().get(0).heading == 10);
        check("old heading: not counted", !w.rssi(1000, -60) && w.samples().size() == 1);
        w.heading(1000, 40, false);
        check("tilted: kept but not counted", !w.rssi(1100, -50) && w.samples().size() == 2 && !w.upright() && !w.samples().get(1).upright);
        check("tilted heading does not count as faced", w.sectorsFaced() == 1 && w.coveredDegrees() == 30);
        check("not ready after one direction", !w.ready() && w.direction() == null);
        // facing 11 of 12 directions, but readings from only 2: not ready
        SearchWalk few = new SearchWalk();
        for (int s = 0; s < 11; s++) few.heading(s * 1000, s * 30, true);
        few.heading(20000, 0, true);
        few.rssi(20000, -60);
        few.heading(20100, 30, true);
        few.rssi(20100, -61);
        check("readings from 2 directions: not ready", few.sectorsFaced() == 11 && !few.ready());
        few.heading(20200, 90, true);
        few.rssi(20200, -62);
        check("readings from 3 directions: ready", few.ready());
        SearchWalk.Direction fdir = few.direction();
        // heard only at 0, 30 and 90, silent in the 8 other faced directions: the arrow points to the heard side
        check("three heard directions, the rest silent: a clear direction to the heard side", fdir != null && SearchWalk.diff(fdir.degrees, 40) <= 20 && fdir.clear, fdir == null ? null : fdir.degrees);
        // all faced directions heard (a flat 3 direction turn): the readings themselves are fitted
        SearchWalk three = new SearchWalk();
        double[][] pts = {{0, -60}, {30, -61}, {90, -62}};
        long tt = 0;
        for (double[] pt : pts) for (int k = 0; k < 3; k++) {
            three.heading(tt, pt[0], true);
            three.rssi(tt, pt[1]);
            tt += 100;
        }
        check("three directions faced and heard: not ready (10 needed)", !three.ready() && three.direction() == null);
        int[] counts = few.sectorCounts();
        double[] means = few.sectorMeans();
        check("sector counts and means", counts[0] == 1 && counts[1] == 1 && counts[2] == 0 && means[3] == -62 && Double.isNaN(means[2]));
        check("sector borders: 14 is north, 16 is the next", SearchWalk.sector(14) == 0 && SearchWalk.sector(16) == 1 && SearchWalk.sector(350) == 0);
    }

    // ------------------------------------------------------------------ last worn

    static final double H = 3600, BASE = 1_760_000_000;

    /** Temperature readings every 5 minutes: worn (34 C) in the given ranges of hours, 21 C otherwise. */
    static double[][] temps(double hours, double[]... worn) {
        int n = (int) (hours * 12);
        double[] t = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = BASE + i * 300;
            double h = i / 12.0;
            c[i] = 21;
            for (double[] w : worn) if (h >= w[0] && h < w[1]) c[i] = 34;
        }
        return new double[][] {t, c};
    }

    /** Heart rate readings every 5 minutes inside the ranges. */
    static double[] beats(double[]... worn) {
        List<Double> l = new ArrayList<Double>();
        for (double[] w : worn) for (double h = w[0]; h < w[1]; h += 1 / 12.0) l.add(BASE + h * H + 60);
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    static void lastWorn() {
        LastWorn.Result none = LastWorn.find(new double[0], new double[0], new double[0]);
        check("no data", none.state == LastWorn.NO_DATA && Double.isNaN(none.at));

        double[][] wornNow = temps(10, new double[] {0, 10});
        LastWorn.Result w = LastWorn.find(wornNow[0], wornNow[1], beats(new double[] {0, 10}));
        check("worn now", w.state == LastWorn.WORN && w.skinC == 34 && w.at >= wornNow[0][wornNow[0].length - 1], w.at);

        // worn 0 to 14 h, taken off at 14 h, lying at 21 C for 6 hours
        double[][] off = temps(20, new double[] {0, 14});
        LastWorn.Result o = LastWorn.find(off[0], off[1], beats(new double[] {0, 14}));
        check("taken off in the evening", o.state == LastWorn.OFF && Math.abs(o.at - (BASE + 14 * H)) <= 300, (o.at - BASE) / H);
        check("room temperature since", o.roomC == 21, o.roomC);
        check("newest reading", o.newest == off[0][off[0].length - 1]);

        // shower break: off 30 minutes at 8 h, back on until now
        double[][] shower = temps(12, new double[] {0, 8}, new double[] {8.5, 12});
        LastWorn.Result s = LastWorn.find(shower[0], shower[1], beats(new double[] {0, 8}, new double[] {8.5, 12}));
        check("shower break and put back: worn", s.state == LastWorn.WORN, s.state);
        // shower break, then taken off for the night at 22 h: the later time counts
        double[][] shower2 = temps(24, new double[] {0, 8}, new double[] {8.5, 22});
        LastWorn.Result s2 = LastWorn.find(shower2[0], shower2[1], beats(new double[] {0, 8}, new double[] {8.5, 22}));
        check("shower, then off at 22 h: the 22 h counts", s2.state == LastWorn.OFF && Math.abs(s2.at - (BASE + 22 * H)) <= 300, (s2.at - BASE) / H);

        // cold fingers: cool readings from 10 h, but the heart keeps beating until now
        double[][] cold = temps(12, new double[] {0, 10});
        LastWorn.Result cf = LastWorn.find(cold[0], cold[1], beats(new double[] {0, 12}));
        check("cold fingers with heart beats: still worn, no skin value from an older time", cf.state == LastWorn.WORN && Double.isNaN(cf.skinC) && cf.at > BASE + 11.9 * H, cf.state);
        // cold fingers until 11 h, then really taken off
        LastWorn.Result cf2 = LastWorn.find(cold[0], cold[1], beats(new double[] {0, 11}));
        check("cold fingers, then off at 11 h", cf2.state == LastWorn.OFF && Math.abs(cf2.at - (BASE + 11 * H)) <= 300, (cf2.at - BASE) / H);

        // the heart rate stops 2 minutes after the last warm reading: that later time counts
        double[][] edge = temps(5, new double[] {0, 3});
        LastWorn.Result e = LastWorn.find(edge[0], edge[1], new double[] {BASE + 3 * H - 300 + 120});
        check("heart rate just after the last warm reading", e.state == LastWorn.OFF && e.at == BASE + 3 * H - 180, (e.at - BASE) / H);

        // never worn in the history, no heart rate: off, time unknown
        double[][] table = temps(6);
        LastWorn.Result tb = LastWorn.find(table[0], table[1], new double[0]);
        check("never worn: off with no time", tb.state == LastWorn.OFF && Double.isNaN(tb.at) && tb.roomC == 21);
        // never warm but a heart rate in the first minutes: off since then
        LastWorn.Result tb2 = LastWorn.find(table[0], table[1], new double[] {BASE + 100});
        check("only a heart rate at the start", tb2.state == LastWorn.OFF && tb2.at == BASE, tb2.at - BASE);

        // readings out of order are sorted, and an even number of cool readings gives a middle value
        LastWorn.Result un = LastWorn.find(new double[] {BASE + 600, BASE, BASE + 300, BASE + 1200, BASE + 900}, new double[] {20, 33, 22, 24, 23}, new double[0]);
        check("unsorted input", un.state == LastWorn.OFF && un.at == BASE && un.roomC == 22.5, un.roomC);
        // only heart rate tells it was worn (all temperatures cool, beats until the end)
        LastWorn.Result hr = LastWorn.find(new double[] {BASE, BASE + 300}, new double[] {25, 25}, new double[] {BASE + 1000});
        check("cool all along but beats to the end: worn, no skin value", hr.state == LastWorn.WORN && Double.isNaN(hr.skinC) && hr.at == BASE + 1000);

        check("charging: up", LastWorn.charging(40, 54) == 1);
        check("charging: down", LastWorn.charging(60, 54) == 0);
        check("charging: about the same is unknown", LastWorn.charging(100, 100) == -1 && LastWorn.charging(55, 54) == -1);
        check("charging: no earlier read", LastWorn.charging(-1, 54) == -1);
    }
}
