import app.openring.core.EventCodec;
import app.openring.core.FingerCheck;
import app.openring.core.PermissionAsk;
import app.openring.core.RingEvent;
import app.openring.core.SyncError;
import app.openring.core.analysis.*;

import java.util.*;

/**
 * JVM tests for the ring finder (issue #74): signal smoothing and bands, the turn on the spot
 * direction, the search walk (steps, path, where the ring probably is) and when the ring came off. Run with tests/run_tests.sh, after CoreTest.
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
        stepDetection();
        paths();
        walks();
        turnSteps();
        noiseCircles();
        lastWorn();
        lastWornBrief();
        finderLayout();
        impossibleValues();
        strongestRing();
        scanRetry();
        permissionAsk();
        fingerNow();
        fingerCheck();
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
                // directions are relative to the first heading after Start
                double e = SearchWalk.diff(d.degrees, w.relative(ring));
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
                    SearchWalk tw = turn(ring, 2000 + s, 0, within);
                    SearchWalk.Direction d = tw.direction();
                    if (d == null) {
                        worst = 999;
                        continue;
                    }
                    double e = SearchWalk.diff(d.degrees, tw.relative(ring));
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
        SearchWalk t170 = turn(90, 5, 170);
        SearchWalk.Direction d = t170.direction();
        check("turn starting at 170 degrees: the start is 0", Math.abs(t170.start() - 170) < 20 && d != null
                && SearchWalk.diff(d.degrees, SearchWalk.norm(90 - t170.start())) <= 30, d == null ? null : d.degrees);
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
        check("fresh heading: counted", w.rssi(100, -60) && w.samples().size() == 1 && w.samples().get(0).heading == 0 && w.start() == 10);
        check("old heading: not counted", !w.rssi(1000, -60) && w.samples().size() == 1);
        w.heading(1000, 40, false);
        check("tilted: kept but not counted", !w.rssi(1100, -50) && w.samples().size() == 2 && !w.upright() && !w.samples().get(1).upright
                && w.samples().get(1).heading == 30);
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

    // ------------------------------------------------------------------ search walk: steps

    /**
     * Feeds a synthetic accelerometer trace at 50 samples a second: gravity split over two axes by
     * {@code lean} degrees, a vertical swing of {@code amp} m/s² at {@code hz} steps a second (with a
     * smaller second harmonic, like a real stride), and {@code noise} m/s² of hand tremor. Returns steps.
     */
    static int trace(StepDetector d, Random r, long from, long ms, double hz, double amp, double noise, double lean) {
        int n = 0;
        double g = 9.81, la = Math.toRadians(lean);
        for (long t = from; t < from + ms; t += 20) {
            double ph = 2 * Math.PI * hz * t / 1000.0;
            double v = amp * Math.sin(ph) + 0.3 * amp * Math.sin(2 * ph + 0.5) + r.nextGaussian() * noise;
            double side = r.nextGaussian() * noise;
            n += d.add(t, side, (g + v) * Math.cos(la), (g + v) * Math.sin(la));
        }
        return n;
    }

    static void stepDetection() {
        Random r = new Random(11);
        for (double[] w : new double[][] {{1.0, 1.0}, {1.4, 1.5}, {1.8, 2.5}, {2.2, 3.0}}) {
            StepDetector d = new StepDetector();
            int n = trace(d, r, 0, 30000, w[0], w[1], 0.2, 0);
            double want = 30 * w[0];
            check("walking " + w[0] + " steps a second, swing " + w[1] + ": steps counted within 2", Math.abs(n - want) <= 2 && d.steps() == n, n + " of " + want);
        }
        StepDetector lean = new StepDetector();
        int nl = trace(lean, r, 0, 20000, 1.5, 1.5, 0.2, 35);
        check("phone at an angle: same count", Math.abs(nl - 30) <= 2, nl);
        StepDetector still = new StepDetector();
        int ns = trace(still, r, 0, 30000, 0.3, 0.1, 0.15, 0);
        check("standing with a shaky hand: no steps", ns == 0, ns);
        StepDetector shake = new StepDetector();
        int nf = trace(shake, r, 0, 20000, 5, 8, 0.5, 0);
        check("shaking fast: no steps", nf <= 1, nf);
        StepDetector hard = new StepDetector();
        int nh = trace(hard, r, 0, 20000, 2.5, 12, 0.5, 0);
        check("shaking hard in a walking rhythm: no steps", nh <= 1, nh);
        for (double hz : new double[] {4.5, 6}) {
            StepDetector mid = new StepDetector();
            int nm = trace(mid, r, 0, 20000, hz, 3, 0.3, 0);
            check("shaking at " + hz + " Hz under the shake limit: not walking (the bump rule does not let it through)", nm <= 2, nm);
        }
        // a walk with a pause: the pause adds nothing, the second run starts after a confirming step
        StepDetector pause = new StepDetector();
        int a = trace(pause, r, 0, 10000, 1.5, 1.5, 0.2, 0);
        int b = trace(pause, r, 10000, 5000, 0.3, 0.05, 0.15, 0);
        int c = trace(pause, r, 15000, 10000, 1.5, 1.5, 0.2, 0);
        check("walk, pause, walk", Math.abs(a - 15) <= 2 && b == 0 && Math.abs(c - 15) <= 2, a + " " + b + " " + c);
        // a gap in the samples (sensor paused) starts over without a step
        StepDetector gap = new StepDetector();
        check("first sample and a sample after a long gap add nothing", gap.add(0, 0, 9.81, 0) == 0 && gap.add(5000, 0, 15, 0) == 0);
        // heel strike: each step lands with two bumps 150 ms apart (the second dips below the fall line in between)
        for (double hz : new double[] {1.2, 1.8}) {
            StepDetector heel = new StepDetector();
            Random hr = new Random(13);
            int nh2 = 0;
            for (long t = 0; t < 30000; t += 10) {
                double ph = (t * hz / 1000.0) % 1.0, v = 0;
                double t1 = ph / hz * 1000; // ms into this step
                v += 2.5 * Math.exp(-Math.pow((t1 - 60) / 35, 2));  // heel
                v += 2.5 * Math.exp(-Math.pow((t1 - 210) / 35, 2)); // toe, 150 ms later
                v -= 3.5 * Math.exp(-Math.pow((t1 - 135) / 25, 2)); // the dip between them
                v += 0.6 * Math.cos(2 * Math.PI * ph); // the body sways up at the footfall, down in between
                nh2 += heel.add(t, hr.nextGaussian() * 0.1, 9.81 + v, 0);
            }
            check("heel strike double bump at " + hz + " steps a second: one step per stride", Math.abs(nh2 - 30 * hz) <= 2, nh2 + " of " + 30 * hz);
        }
        check("step length 0.41 x height", Math.abs(StepDetector.stepLength(175) - 0.7175) < 1e-9);
    }

    // ------------------------------------------------------------------ search walk: path and coverage

    /** Walks straight ahead {@code n} steps at {@code heading}, one step a second from {@code t}. */
    static long walkLeg(SearchWalk w, long t, double heading, int n) {
        for (int i = 0; i < n; i++) {
            w.heading(t, heading, true);
            w.rssi(t + 100, -70);
            t += 1000;
            w.steps(t, 1);
        }
        return t;
    }

    static void paths() {
        SearchWalk w = new SearchWalk().stepLength(0.5);
        w.steps(0, 2);
        check("steps before any heading go nowhere", w.steps() == 2 && w.path().size() == 1 && w.x() == 0 && w.y() == 0);
        long t = walkLeg(w, 0, 0, 6);
        check("6 steps ahead: 3 m ahead", Math.abs(w.y() - 3) < 1e-9 && Math.abs(w.x()) < 1e-9, w.x() + "," + w.y());
        t = walkLeg(w, t, 90, 6);
        check("then 6 to the right: 3 m right", Math.abs(w.x() - 3) < 1e-9 && Math.abs(w.y() - 3) < 1e-9, w.x() + "," + w.y());
        t = walkLeg(w, t, 180, 6);
        t = walkLeg(w, t, 270, 6);
        check("a square comes back to the start", Math.hypot(w.x(), w.y()) < 1e-9 && Math.abs(w.reach() - Math.sqrt(18)) < 1e-9 && Math.abs(w.distance() - 12) < 1e-9 && w.steps() == 26);
        double[] c = w.cover();
        // its widest pair of directions at right angles are the two diagonals, 3 times the square root of 2 each; it is
        // narrowest along a side
        check("a 3 m square covers 3 m or more in two directions and is 3 m wide", Math.abs(c[0] - 3 * Math.sqrt(2)) < 1e-9 && Math.abs(c[1] - 3) < 1e-9, Arrays.toString(c));
        check("readings keep where they were taken", w.samples().get(6).y == 3 && w.samples().get(6).x == 0 && w.samples().get(8).x == 1.0);
        check("a covered square is ready for the map", w.mapReady());
        // tilted: the step keeps the last upright heading
        SearchWalk tl = new SearchWalk().stepLength(1);
        tl.heading(0, 90, true);
        tl.heading(5, 180, true);
        tl.heading(10, 300, false);
        tl.steps(20, 1);
        // the first step turned 90 degrees from the way the person faced at Start, so it waits for the next one (issue #108)
        check("a step that turned from the start waits", tl.waiting() == 1 && tl.x() == 0 && tl.steps() == 1);
        tl.steps(30, 1);
        check("a step while tilted keeps the last upright heading (relative to the start)", Math.abs(tl.x() - 2) < 1e-9 && Math.abs(tl.y()) < 1e-9, tl.x() + "," + tl.y());
        SearchWalk notYet = new SearchWalk();
        notYet.heading(0, 50, false);
        check("a tilted first heading does not set the start", Double.isNaN(notYet.start()) && Double.isNaN(notYet.heading()) && notYet.relative(50) == 50);
        // MUST from review: the sensor's zero is arbitrary; the first leg goes ahead whatever it reads at Start
        SearchWalk at70 = new SearchWalk().stepLength(0.5);
        long t70 = walkLeg(at70, 0, 70, 6);
        walkLeg(at70, t70, 160, 6);
        check("start facing 70 degrees: the first leg goes ahead, the second to the right", Math.abs(at70.path().get(6).y - 3) < 1e-9
                && Math.abs(at70.path().get(6).x) < 1e-9 && Math.abs(at70.x() - 3) < 1e-9, at70.x() + "," + at70.y());
        check("start facing 70: the text says ahead", SearchWalk.where(at70.path().get(6).x, at70.path().get(6).y).matches("about 3.0 m ahead of where you started") && SearchWalk.where(-1, -2).equals("about 2.0 m behind and 1.0 m to the left of where you started") && SearchWalk.where(0.1, 0.2).equals("right around where you started") && SearchWalk.where(2, 0).equals("about 2.0 m to the right of where you started"));
        // a long straight line, even diagonal, is not enough
        SearchWalk line = new SearchWalk().stepLength(0.7);
        walkLeg(line, 0, 45, 30);
        double[] lc = line.cover();
        check("a long diagonal line: no map", !line.mapReady() && lc[1] < 0.1 && line.locate() == null, Arrays.toString(lc));
        // a short L (1.5 by 1.5 m) is not enough
        SearchWalk l = new SearchWalk().stepLength(0.5);
        walkLeg(l, walkLeg(l, 0, 0, 3), 90, 3);
        // 1.5 m along each leg, and no pair of directions at right angles sees more of it
        check("a short L: not enough", !l.mapReady() && Math.abs(l.cover()[0] - 1.5) < 1e-9 && l.cover()[0] < SearchWalk.COVER_M, Arrays.toString(l.cover()));
        // enough walking but too few readings
        SearchWalk quiet = new SearchWalk().stepLength(1);
        for (int i = 0; i < 4; i++) {
            quiet.heading(i * 1000, i * 90, true);
            // one by one: a whole leg sent as one call of 3 steps would be judged as one turning step (issue #108)
            for (int k = 0; k < 3; k++) quiet.steps(i * 1000 + 500 + k * 100, 1);
        }
        check("covered but not heard: no map", quiet.cover()[0] >= 2.9 && !quiet.mapReady() && quiet.locate() == null);
        // the fit: level and shadow come out exactly from clean data
        double[] lg = {0, 0.3, 0.5, 0.7}, sh = {0, 1, 0.5, 0.2}, rr = new double[4];
        for (int i = 0; i < 4; i++) rr[i] = -60 - 25 * lg[i] - 8 * sh[i];
        double[] f = SearchWalk.fit(rr, lg, sh, 2.5);
        check("fit recovers level and shadow", Math.abs(f[0] + 60) < 1e-9 && Math.abs(f[1] - 8) < 1e-9 && f[2] < 1e-9, Arrays.toString(f));
        for (int i = 0; i < 4; i++) rr[i] = -60 - 25 * lg[i] + 8 * sh[i];
        check("a negative shadow is kept at 0", SearchWalk.fit(rr, lg, sh, 2.5)[1] == 0);
        double[] flat = SearchWalk.fit(new double[] {-60, -61}, new double[] {0, 0}, new double[] {0.5, 0.5}, 2);
        check("no shadow variation: shadow 0", flat[1] == 0 && Math.abs(flat[0] + 60.5) < 1e-9);
    }

    // ------------------------------------------------------------------ search walk: where the ring is

    /**
     * A simulated search walk in a 5 x 5 m room. The person starts near a corner facing along a
     * wall, walks slowly (a step every 1.2 s, {@code legs} as waypoints, each jittered by 20 cm) and the
     * phone hears the ring 8 times a second. The truth: ring at a random spot 0.3 m or more from the
     * walls and 0.3 to 1.3 m below the phone, signal -58 to -64 dBm at 1 m, loss exponent 2 to 3.5,
     * {@code noise} dB of noise per reading, a spatial ripple of up to 6 dB (multipath), up to
     * {@code shadowDb} body shadow, 20 % of readings lost and half of those from behind. With
     * {@code drift}, the true step is 0.85 to 1.15 times the assumed one and the heading drifts 2 to
     * 6 degrees a minute, plus 3 degrees of noise. Returns {error m, radius m, 1 if inside, 1 if a spot, 1 if clear, 1 if beyond}.
     */
    static double[] searchWalk(long seed, double[][] legs, double noise, double shadowDb, boolean drift) {
        return searchWalk(seed, legs, noise, shadowDb, drift, RING_IN, 1.5);
    }

    /** Where the simulated ring is: in the room, nowhere (a flat signal of -88 dBm, the ring in another room), or 2 to 5 m outside the walk. */
    static final int RING_IN = 0, NO_RING = 1, RING_OUTSIDE = 2;

    /** The same walk with the ring {@code where}, and a multipath ripple of up to 4 × {@code ripple} dB (1.5 above). */
    static double[] searchWalk(long seed, double[][] legs, double noise, double shadowDb, boolean drift, int where, double ripple) {
        // neighbouring seeds give java.util.Random nearly the same first numbers (the ring would sit in
        // almost the same spot in every walk); mix the seed first so the walks are really different
        Random r = new Random(mix(seed));
        double rx = 0.3 + r.nextDouble() * 4.4, ry = 0.3 + r.nextDouble() * 4.4;
        double hz = 0.3 + r.nextDouble(), nTrue = 2 + r.nextDouble() * 1.5, p0 = -58 - r.nextDouble() * 6;
        double[] kx = new double[4], ky = new double[4], ph = new double[4];
        for (int i = 0; i < 4; i++) {
            double a = r.nextDouble() * 2 * Math.PI, k = 2 * Math.PI / (0.6 + r.nextDouble() * 1.5);
            kx[i] = k * Math.cos(a);
            ky[i] = k * Math.sin(a);
            ph[i] = r.nextDouble() * 2 * Math.PI;
        }
        double len = 0.72, lenTrue = drift ? len * (0.85 + 0.3 * r.nextDouble()) : len;
        double bias = drift ? (r.nextBoolean() ? 1 : -1) * (2 + 4 * r.nextDouble()) / 60.0 : 0;
        double[][] wp = new double[legs.length][];
        for (int i = 0; i < legs.length; i++) wp[i] = new double[] {legs[i][0] + r.nextGaussian() * 0.2, legs[i][1] + r.nextGaussian() * 0.2};
        if (where == RING_OUTSIDE) {
            // 2 to 5 m past one side of the walk's bounding box (issue #136)
            double bx0 = 1e9, bx1 = -1e9, by0 = 1e9, by1 = -1e9;
            for (double[] p : wp) {
                bx0 = Math.min(bx0, p[0]);
                bx1 = Math.max(bx1, p[0]);
                by0 = Math.min(by0, p[1]);
                by1 = Math.max(by1, p[1]);
            }
            double out = 2 + 3 * r.nextDouble();
            int side = r.nextInt(4);
            double along = r.nextDouble();
            if (side == 0) {
                rx = bx1 + out;
                ry = by0 + along * (by1 - by0);
            } else if (side == 1) {
                rx = bx0 - out;
                ry = by0 + along * (by1 - by0);
            } else if (side == 2) {
                ry = by1 + out;
                rx = bx0 + along * (bx1 - bx0);
            } else {
                ry = by0 - out;
                rx = bx0 + along * (bx1 - bx0);
            }
        }
        SearchWalk w = new SearchWalk().stepLength(len);
        double px = wp[0][0], py = wp[0][1], hd = 0;
        long t = 0, nextStep = 0, nextRead = 0;
        int k = 1;
        while (k < wp.length && t < 600000) {
            double bx = wp[k][0] - px, by = wp[k][1] - py;
            double dh = ((Math.toDegrees(Math.atan2(bx, by)) - hd) % 360 + 540) % 360 - 180;
            boolean turning = Math.abs(dh) > 5;
            if (turning) hd += Math.signum(dh) * Math.min(Math.abs(dh), 90 * 0.02);
            w.heading(t, hd + bias * t / 1000.0 + r.nextGaussian() * 3, true);
            if (!turning && t >= nextStep) {
                nextStep = t + 1200;
                double step = Math.min(lenTrue, Math.hypot(bx, by));
                px += step * Math.sin(Math.toRadians(hd));
                py += step * Math.cos(Math.toRadians(hd));
                w.steps(t, 1);
                if (Math.hypot(wp[k][0] - px, wp[k][1] - py) < 0.3) k++;
            }
            if (t >= nextRead) {
                nextRead = t + 125;
                double dx = rx - px, dy = ry - py, flat = Math.hypot(dx, dy), d = Math.sqrt(flat * flat + hz * hz);
                double cos = flat < 1e-6 ? 1 : (dx * Math.sin(Math.toRadians(hd)) + dy * Math.cos(Math.toRadians(hd))) / flat;
                double behind = (1 - cos) / 2, fade = 0;
                for (int i = 0; i < 4; i++) fade += ripple * Math.sin(kx[i] * px + ky[i] * py + ph[i]);
                double v = where == NO_RING ? -88 + fade + r.nextGaussian() * noise
                        : p0 - 10 * nTrue * Math.log10(d) - shadowDb * behind + fade + r.nextGaussian() * noise;
                if (r.nextDouble() > 0.2 && !(behind > 0.75 && r.nextDouble() < 0.5)) w.rssi(t, v);
            }
            t += 20;
        }
        w.finish(); // Stop: steps still waiting for a turn to end move (issue #108)
        SearchWalk.Spot s = w.locate();
        if (s == null) return new double[] {Double.NaN, 0, 0, 0, 0};
        // the map starts at the first waypoint facing ahead (+y), like the room
        // the map's up is the first heading after Start: turn the truth into that frame
        double b = Math.toDegrees(Math.atan2(rx - wp[0][0], ry - wp[0][1])) - w.start(), dist = Math.hypot(rx - wp[0][0], ry - wp[0][1]);
        double e = Math.hypot(s.x - dist * Math.sin(Math.toRadians(b)), s.y - dist * Math.cos(Math.toRadians(b)));
        return new double[] {e, s.radius, e <= s.radius ? 1 : 0, 1, s.clear ? 1 : 0, s.beyond ? 1 : 0};
    }

    /** A 64 bit mixer (SplitMix64's finaliser). */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Around the room 0.8 m from the walls, then across it. */
    static final double[][] AROUND = {{0.8, 0.8}, {0.8, 4.2}, {4.2, 4.2}, {4.2, 0.8}, {0.8, 0.8}, {2.5, 2.5}, {4.2, 4.2}};
    /** An L over part of the room: 3.5 m along one wall, 3 m along the next. */
    static final double[][] PART = {{0.8, 0.8}, {0.8, 4.3}, {3.8, 4.3}};

    /**
     * Seeds for the walks below. RADIUS_K and the other numbers were set on other seeds (bases 100,
     * 500, 7400, 20000 and 55555); these walks were never used for tuning, so they measure the promise.
     */
    static final long FRESH = 300000;

    /** Runs {@code runs} walks; prints and returns {median, 90th percentile, worst, share inside, share with a spot, share not clear}. */
    static double[] walkStats(String name, double[][] legs, double noise, double shadow, boolean drift, int runs) {
        List<Double> errs = new ArrayList<Double>();
        double inside = 0;
        int unclear = 0;
        for (int i = 0; i < runs; i++) {
            double[] o = searchWalk(FRESH + i, legs, noise, shadow, drift);
            if (o[3] == 0) continue;
            if (o[4] == 0) unclear++;
            errs.add(o[0]);
            inside += o[2];
        }
        Collections.sort(errs);
        int n = errs.size();
        double median = n == 0 ? 99 : errs.get(n / 2), p90 = n == 0 ? 99 : errs.get((int) (n * 0.9)), worst = n == 0 ? 99 : errs.get(n - 1);
        System.out.printf(Locale.US, "  search walk, %s, noise %.0f dB, shadow %.0f dB%s: median error %.2f m, 90th percentile %.2f m, worst %.2f m, circle held the ring in %.0f %%, a spot in %d of %d (%d not clear)%n",
                name, noise, shadow, drift ? ", drift" : "", median, p90, worst, n == 0 ? 0 : 100 * inside / n, n, runs, unclear);
        return new double[] {median, p90, worst, n == 0 ? 0 : inside / n, n / (double) runs, unclear / (double) runs};
    }

    static void walks() {
        // limits written down before running the fresh seeds, from what the tuning seeds showed
        for (double noise : new double[] {4, 6}) {
            for (double shadow : new double[] {0, 10}) {
                String k = "walk around the room (noise " + noise + ", shadow " + shadow + "): ";
                double[] s = walkStats("around and across", AROUND, noise, shadow, true, 200);
                check(k + "a spot every time", s[4] == 1, s[4]);
                check(k + "median error under 1 m", s[0] < 1.0, s[0]);
                check(k + "90th percentile under 2.5 m", s[1] < 2.5, s[1]);
                check(k + "worst under 4.5 m", s[2] < 4.5, s[2]);
                check(k + "the circle holds the ring in at least 8 of 10 walks", s[3] >= 0.8, s[3]);
                check(k + "a clear circle in at least 9 of 10", s[5] <= 0.1, s[5]);
            }
        }
        double[] clean = walkStats("around and across", AROUND, 4, 10, false, 200);
        check("no drift: median under 1 m", clean[0] < 1.0, clean[0]);
        double[] part = walkStats("L over part of the room", PART, 5, 10, true, 200);
        check("L walk: a spot every time", part[4] == 1, part[4]);
        check("L walk: median error under 1.5 m", part[0] < 1.5, part[0]);
        check("L walk: worst under 6.5 m", part[2] < 6.5, part[2]);
        check("L walk: the circle holds the ring in at least 8 of 10", part[3] >= 0.8, part[3]);
        // a flat signal everywhere (ring in another room, the same faint signal): no clear spot
        Random r = new Random(5);
        SearchWalk flat = new SearchWalk().stepLength(0.7);
        long t = 0;
        for (int leg = 0; leg < 4; leg++) {
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 8; k++) {
                    flat.heading(t, leg * 90, true);
                    flat.rssi(t + 10, -88 + r.nextGaussian() * 4);
                    t += 150;
                }
                flat.steps(t, 1);
            }
        }
        SearchWalk.Spot fs = flat.locate();
        check("flat signal over a walk: no clear spot (the model explains too little)", fs != null && !fs.clear && fs.explained < SearchWalk.MIN_EXPLAINED, fs == null ? null : fs.explained);
        // the ring 5 m beyond a small walk (a 2.4 m square): the model cannot tell how far, but it points that way
        SearchWalk far = new SearchWalk().stepLength(0.6);
        t = 0;
        for (int leg = 0; leg < 4; leg++) {
            for (int i = 0; i < 4; i++) {
                for (int k = 0; k < 8; k++) {
                    far.heading(t, leg * 90, true);
                    double d = Math.sqrt((7.4 - far.x()) * (7.4 - far.x()) + (1 - far.y()) * (1 - far.y()) + 0.49);
                    far.rssi(t + 10, -60 - 25 * Math.log10(d) + r.nextGaussian() * 2);
                    t += 150;
                }
                far.steps(t, 1);
            }
        }
        SearchWalk.Spot fa = far.locate();
        System.out.printf(Locale.US, "  ring 5 m beyond a 2.4 m walk: circle at %.1f, %.1f (radius %.1f), %s%n", fa.x, fa.y, fa.radius, fa.edge ? "at the edge" : fa.beyond ? "beyond the walk" : "inside");
        double toward = Math.toDegrees(Math.atan2(fa.x - 1.2, fa.y - 1.2)), truth = Math.toDegrees(Math.atan2(7.4 - 1.2, 1 - 1.2));
        check("ring far beyond the walk: no circle, the edge points towards it (within 45 degrees of the walk's middle)", fa.edge && fa.beyond && !fa.clear
                && SearchWalk.diff(toward, truth) < 45, toward + " vs " + truth);
        // a long walk (12 m square): the grid gets coarser but still finds the ring
        SearchWalk big = new SearchWalk().stepLength(1);
        t = 0;
        double bx = 0, by = 0;
        for (int leg = 0; leg < 4; leg++) {
            for (int i = 0; i < 12; i++) {
                for (int k = 0; k < 8; k++) {
                    double h = leg * 90;
                    big.heading(t, h, true);
                    double d = Math.sqrt((9 - bx) * (9 - bx) + (4 - by) * (4 - by) + 0.49);
                    big.rssi(t + 10, -60 - 25 * Math.log10(d) + r.nextGaussian() * 2);
                    t += 150;
                }
                big.steps(t, 1);
                bx = big.x();
                by = big.y();
            }
        }
        SearchWalk.Spot bs = big.locate();
        check("a 12 m walk: ring found within 1.5 m", bs != null && Math.hypot(bs.x - 9, bs.y - 4) < 1.5 && bs.clear && bs.bins > 100, bs == null ? null : bs.x + "," + bs.y);
        // the circle is worked out on a copy of the record (another thread on the phone): same answer
        SearchWalk copy = big.snapshot();
        SearchWalk.Spot cs = copy.locate();
        check("a snapshot gives the same circle and keeps the record", cs != null && cs.x == bs.x && cs.y == bs.y && cs.radius == bs.radius
                && copy.steps() == big.steps() && copy.stepLength() == 1 && copy.x() == big.x() && copy.start() == big.start()
                && copy.heardSeconds() == big.heardSeconds() && copy.sectorsFaced() == big.sectorsFaced());
        // a long walk is merged to at most MAX_BINS points for the fit, keeping the averages
        List<double[]> many = new ArrayList<double[]>();
        double sum = 0;
        for (int i = 0; i < 1000; i++) {
            many.add(new double[] {i * 0.01, 0, 90, -60 - i % 7, i * 500});
            sum += -60 - i % 7;
        }
        List<double[]> fewer = SearchWalk.merged(many, SearchWalk.MAX_BINS);
        double sum2 = 0;
        for (double[] q : fewer) sum2 += q[3];
        check("long walks are merged for the fit", fewer.size() <= SearchWalk.MAX_BINS && fewer.size() >= SearchWalk.MAX_BINS / 2
                && Math.abs(sum2 / fewer.size() - sum / 1000) < 0.1 && Math.abs(fewer.get(0)[2] - 90) < 1e-6 && SearchWalk.merged(fewer, 1000) == fewer, fewer.size());
        // the demo walk (screenshots) shows a clear circle over its made up ring
        List<double[]> ev = DemoWalk.events();
        SearchWalk demo = new SearchWalk().stepLength(0.72);
        int half = DemoWalk.feed(demo, ev, 0, 5000);
        check("demo walk: fed in parts", half > 0 && half < ev.size() && demo.locate() == null);
        check("demo walk: all fed", DemoWalk.feed(demo, ev, half, Long.MAX_VALUE) == ev.size());
        SearchWalk.Spot ds = demo.locate();
        check("demo walk: a clear circle holding its ring", ds != null && ds.clear && Math.hypot(ds.x - DemoWalk.RING_X, ds.y - DemoWalk.RING_Y) <= ds.radius
                && ev.get(ev.size() - 1)[0] < 70000, ds == null ? null : ds.x + "," + ds.y + " r " + ds.radius + " t " + ev.get(ev.size() - 1)[0]);
        // review: turning on the spot while shuffling (8 steps in 20 s) stays a turn, not a walk
        SearchWalk shuffle = new SearchWalk().stepLength(0.72);
        Random sr = new Random(21);
        for (long st = 0; st <= 20000; st += 20) {
            double faced = 360.0 * st / 20000;
            shuffle.heading(st, faced + sr.nextGaussian() * 3, true);
            if (st % 120 == 0) shuffle.rssi(st, -66 - 10 * (1 - Math.cos(Math.toRadians(faced - 90))) / 2 + sr.nextGaussian() * 4);
            if (st > 0 && st % 2500 == 0) shuffle.steps(st, 1);
        }
        check("shuffling while turning: 8 steps counted, no map, still a direction", shuffle.steps() == 8 && shuffle.reach() < SearchWalk.SPOT_M
                && shuffle.direction() != null, shuffle.steps() + " steps, reach " + shuffle.reach());
        // a corner taken in one step still counts that step (it waits for the next straight one)
        SearchWalk corner = new SearchWalk().stepLength(1);
        walkLeg(corner, walkLeg(corner, 0, 0, 3), 90, 1);
        check("a corner step waits", corner.path().size() == 4 && Math.abs(corner.y() - 3) < 1e-9 && corner.x() == 0);
        corner.steps(9000, 1);
        check("and moves with the next straight step", Math.abs(corner.x() - 2) < 1e-9 && corner.distance() == 5);
        // turning on the spot: no steps, no map; the phase 1 direction still works
        SearchWalk spin = turn(90, 3, 0);
        check("turning on the spot: no map, a direction", spin.reach() == 0 && !spin.mapReady() && spin.locate() == null && spin.direction() != null);
    }

    // ------------------------------------------------------------------ turning on the spot with steps (issue #108)

    /**
     * The issue's turn: once around in 20 s (headings every 20 ms with 3 degrees of noise, readings
     * every 120 ms, the ring at 90 degrees with 10 dB of body shadow, steps of 0.72 m). Steps come
     * from {@code steps}: {time ms, n} calls of {@code walk.steps(t, n)}.
     */
    static SearchWalk spinWith(long[][] steps, long seed) {
        SearchWalk w = new SearchWalk().stepLength(0.72);
        Random r = new Random(seed);
        int next = 0;
        for (long t = 0; t <= 20000; t += 20) {
            double faced = 360.0 * t / 20000;
            w.heading(t, faced + r.nextGaussian() * 3, true);
            if (t % 120 == 0) w.rssi(t, -66 - 10 * (1 - Math.cos(Math.toRadians(faced - 90))) / 2 + r.nextGaussian() * 3);
            while (next < steps.length && steps[next][0] <= t) {
                w.steps(t, (int) steps[next][1]);
                next++;
            }
        }
        return w;
    }

    /**
     * The same turn (or a walk, with {@code headingAt}) through the accelerometer: one bump and dip
     * per footfall at the given times, fed to {@link StepDetector} at 50 samples a second, and each
     * counted step passed on at its own time as the app does.
     */
    static SearchWalk throughDetector(long[] footfalls, long ms, double[] headingAt, long seed, StepDetector d) {
        SearchWalk w = new SearchWalk().stepLength(0.72);
        Random r = new Random(seed);
        for (long t = 0; t <= ms; t += 20) {
            double faced = headingAt == null ? 360.0 * t / ms : headingAt[(int) (t / 20)];
            w.heading(t, faced + r.nextGaussian() * 3, true);
            if (t % 120 == 0) w.rssi(t, -66 - 10 * (1 - Math.cos(Math.toRadians(faced - 90))) / 2 + r.nextGaussian() * 3);
            double v = 0;
            for (long f : footfalls) {
                double dt = t - f;
                v += 2.2 * Math.exp(-dt * dt / (60.0 * 60)) - 1.4 * Math.exp(-(dt - 200) * (dt - 200) / (80.0 * 80));
            }
            int n = d.add(t, r.nextGaussian() * 0.1, 9.81 + v + r.nextGaussian() * 0.1, 0);
            if (n > 0) for (long at : d.lastTimes()) w.step(at);
        }
        return w;
    }

    /**
     * A walk of steps 700 ms apart with these true headings, the heading turning smoothly to the next
     * one before each step (3 degrees of noise), ended with Stop. Returns {steps, moved, waiting before Stop, error of the end m}.
     */
    static double[] shape(long seed, double[] hs) {
        SearchWalk w = new SearchWalk().stepLength(0.72);
        Random r = new Random(seed);
        long t = 0;
        double prev = hs[0], tx = 0, ty = 0;
        w.heading(-100, hs[0], true);
        for (double h : hs) {
            for (long u = t; u < t + 700; u += 20) {
                double f = Math.min(1, (u - t) / 600.0);
                w.heading(u, prev + SearchWalk.signed(h - prev) * f + r.nextGaussian() * 3, true);
                if (u % 120 == 0) w.rssi(u, -70);
            }
            t += 700;
            w.step(t);
            tx += 0.72 * Math.sin(Math.toRadians(h - w.start()));
            ty += 0.72 * Math.cos(Math.toRadians(h - w.start()));
            prev = h;
        }
        int waiting = w.waiting();
        w.finish();
        return new double[] {w.steps(), w.path().size() - 1, waiting, Math.hypot(w.x() - tx, w.y() - ty)};
    }

    /** A straight walk swaying {@code deg} each way with every step; how often the card would mention waiting steps. */
    static int swayShown(double deg, long gap, int n, long seed) {
        SearchWalk w = new SearchWalk().stepLength(0.72);
        Random r = new Random(seed);
        w.heading(-100, 0, true);
        int shown = 0;
        long t = 0;
        for (int i = 0; i < n; i++) {
            for (long u = t; u < t + gap; u += 20) {
                w.heading(u, (i % 2 == 0 ? deg : -deg) + r.nextGaussian() * 0.5, true);
                if (u % 120 == 0) w.rssi(u, -70);
            }
            t += gap;
            w.step(t - 10);
            if (w.waitingToShow() > 0) shown++;
        }
        return shown;
    }

    static long[][] every(long first, long gap, int calls, int n) {
        long[][] out = new long[calls][];
        for (int i = 0; i < calls; i++) out[i] = new long[] {first + i * gap, n};
        return out;
    }

    static void turnSteps() {
        // the issue's cases: A one step a second, B pairs every 2.5 s (what StepDetector reported), B1 single steps every 2.5 s
        SearchWalk a = spinWith(every(1000, 1000, 20, 1), 31);
        SearchWalk b = spinWith(every(2500, 2500, 8, 2), 32);
        SearchWalk b1 = spinWith(every(2500, 2500, 8, 1), 33);
        for (Object[] c : new Object[][] {{"A, a step a second", a, 20}, {"B, pairs every 2.5 s", b, 16}, {"B1, single steps every 2.5 s", b1, 8}}) {
            SearchWalk w = (SearchWalk) c[1];
            check("turn on the spot, " + c[0] + ": the steps are counted, no walk, the arrow stays", w.steps() == (Integer) c[2] && w.reach() < SearchWalk.SPOT_M
                    && w.distance() < SearchWalk.SPOT_M && w.direction() != null && w.direction().clear, w.steps() + " steps, reach " + w.reach() + ", distance " + w.distance());
            w.finish();
            check("turn on the spot, " + c[0] + ": Stop keeps it a turn", w.reach() < SearchWalk.SPOT_M && w.waiting() == 0, w.reach());
        }
        // through the accelerometer: two step shuffles (600 ms apart) every 2.5 s, and a steady step a second
        List<Long> pairs = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) {
            pairs.add(1500L + i * 2500);
            pairs.add(2100L + i * 2500);
        }
        long[] pf = new long[pairs.size()];
        for (int i = 0; i < pf.length; i++) pf[i] = pairs.get(i);
        StepDetector pd = new StepDetector();
        SearchWalk ps = throughDetector(pf, 20000, null, 34, pd);
        ps.finish();
        check("turn with two step shuffles through the step detector: 16 steps, no walk, the arrow stays", pd.steps() == 16 && ps.steps() == 16
                && ps.reach() < SearchWalk.SPOT_M && ps.direction() != null && ps.direction().clear, pd.steps() + " steps, reach " + ps.reach());
        long[] steady = new long[19];
        for (int i = 0; i < steady.length; i++) steady[i] = 1000 + i * 1000L;
        StepDetector sd = new StepDetector();
        SearchWalk ss = throughDetector(steady, 20000, null, 35, sd);
        ss.finish();
        check("turn with a step a second through the step detector: 19 steps, no walk, the arrow stays", sd.steps() == 19 && ss.reach() < SearchWalk.SPOT_M
                && ss.direction() != null && ss.direction().clear, sd.steps() + " steps, reach " + ss.reach());
        // the detector reports the first step of a run with the second, with both times
        StepDetector two = new StepDetector();
        long[] seen = null;
        Random tr = new Random(36);
        for (long t = 0; t <= 3000; t += 20) {
            double v = 0;
            for (long f : new long[] {1000, 1600}) {
                double dt = t - f;
                v += 2.2 * Math.exp(-dt * dt / 3600.0) - 1.4 * Math.exp(-(dt - 200) * (dt - 200) / 6400.0);
            }
            if (two.add(t, 0, 9.81 + v + tr.nextGaussian() * 0.05, 0) == 2) seen = two.lastTimes();
            else if (seen == null && two.lastTimes().length != 0) seen = new long[0];
        }
        check("the first two steps of a run come together, each with its own time", seen != null && seen.length == 2 && Math.abs(seen[0] - 1000) <= 60
                && Math.abs(seen[1] - 1600) <= 60, seen == null ? null : Arrays.toString(seen));

        // walking still moves: an L through the detector, the corner turned while stepping (45 degrees on each of two steps)
        int n = 20;
        long[] lf = new long[n];
        double[] truth = new double[n];
        for (int i = 0; i < n; i++) {
            lf[i] = 1000 + i * 900L;
            truth[i] = i < 9 ? 0 : i == 9 ? 45 : 90;
        }
        long ms = 1000 + n * 900L;
        double[] hd = new double[(int) (ms / 20) + 1];
        for (int k = 0; k < hd.length; k++) {
            long t = k * 20L;
            int i = 0;
            while (i < n - 1 && lf[i + 1] - 300 <= t) i++;
            hd[k] = truth[i];
        }
        StepDetector ld = new StepDetector();
        SearchWalk lw = throughDetector(lf, ms, hd, 37, ld);
        lw.finish();
        double tx = 0, ty = 0;
        for (double h : truth) {
            // in the walk's own frame: up is the (noisy) first heading
            tx += 0.72 * Math.sin(Math.toRadians(h - lw.start()));
            ty += 0.72 * Math.cos(Math.toRadians(h - lw.start()));
        }
        check("an L walked through the step detector: every step moves, along its own heading", ld.steps() == n && lw.path().size() == n + 1
                && Math.hypot(lw.x() - tx, lw.y() - ty) < 0.3, ld.steps() + " steps, " + (lw.path().size() - 1) + " moved, at " + lw.x() + "," + lw.y() + " truth " + tx + "," + ty);
        // swaying while walking straight (the body turns 8 degrees left and right with every step) is no turn
        SearchWalk sway = new SearchWalk().stepLength(0.72);
        sway.heading(-500, 0, true); // facing ahead at Start
        for (int i = 0; i < 12; i++) {
            long t = i * 1000L;
            for (long u = t; u < t + 1000; u += 20) {
                sway.heading(u, i % 2 == 0 ? 8 : -8, true);
                if (u % 120 == 0) sway.rssi(u, -70);
            }
            sway.step(t + 990);
        }
        check("swaying while walking: at most 2 steps wait, none is lost", sway.waiting() <= 2 && sway.path().size() - 1 + sway.waiting() == 12, sway.waiting());
        sway.finish();
        SearchWalk.Sample lastRead = sway.samples().get(sway.samples().size() - 1);
        check("swaying while walking: all 12 steps move ahead, the readings move with them", sway.path().size() == 13 && Math.abs(sway.y() - 12 * 0.72 * Math.cos(Math.toRadians(8))) < 1e-6
                && Math.abs(sway.x()) < 0.2 && lastRead.y > 10 * 0.72, sway.x() + "," + sway.y() + " last reading at " + lastRead.y);
        check("swaying while walking (8 degrees each way): the card never mentions a waiting step", swayShown(8, 1000, 40, 41) == 0, swayShown(8, 1000, 40, 41));
        // review of #182: the usual sway is about 3 degrees each way, 6 degrees between steps, over ROTATE_DEG
        int shown = 0;
        for (int seed = 0; seed < 20; seed++) shown += swayShown(3, 700, 40, 100 + seed);
        check("swaying straight walk (3 degrees each way, 20 walks of 40 steps): the card never mentions a waiting step", shown == 0, shown);
        SearchWalk spun = new SearchWalk().stepLength(0.72);
        spun.heading(-100, 0, true);
        int seenSpin = 0;
        for (int i = 1; i <= 6; i++) {
            spun.heading(i * 1000L - 500, 18 * i, true);
            spun.step(i * 1000L);
            if (i == 1) check("a turn on the spot: its first step is not mentioned yet", spun.waiting() == 1 && spun.waitingToShow() == 0, spun.waitingToShow());
            seenSpin = spun.waitingToShow();
        }
        check("a turn on the spot: from 2 waiting steps on the card says how many", seenSpin == 6, seenSpin);
        SearchWalk sharp = new SearchWalk().stepLength(0.72);
        sharp.heading(-100, 0, true);
        sharp.step(500);
        sharp.heading(900, 90, true);
        sharp.step(1500);
        check("a corner of 90 degrees in one step: the card mentions the 1 waiting step", sharp.waiting() == 1 && sharp.waitingToShow() == 1, sharp.waitingToShow());
        check("shares: explained rounded down, needed up, never equal while the circle fails", Arrays.equals(SearchWalk.percents(0.6449, 0.6351), new long[] {64, 64})
                && Arrays.equals(SearchWalk.percents(0.6351, 0.6449), new long[] {63, 65}) && Arrays.equals(SearchWalk.percents(0.07, 0.07), new long[] {7, 7})
                && Arrays.equals(SearchWalk.percents(0.29, 0.57), new long[] {29, 57}) && Arrays.equals(SearchWalk.percents(0.64, 0.640001), new long[] {64, 65})
                && Arrays.equals(SearchWalk.percents(0, 0.3), new long[] {0, 30}), Arrays.toString(SearchWalk.percents(0.64, 0.640001)));
        boolean apart = true;
        for (int e = 0; e <= 1000; e++) {
            for (int d = 1; d <= 7; d++) {
                double ex = e / 1000.0, nd = ex + d / 100000.0;
                long[] pc = SearchWalk.percents(ex, nd);
                apart &= pc[0] < pc[1];
            }
        }
        check("shares: a failing circle always shows less explained than needed", apart);
        // a gentle curve (90 degrees over 6 steps) moves along it; a turn on the spot then walking away keeps only the walk
        SearchWalk curve = new SearchWalk().stepLength(0.72);
        long t = 0;
        double[] curveHeadings = {0, 0, 15, 30, 45, 60, 75, 90, 90, 90, 90};
        double cx = 0, cy = 0;
        for (double h : curveHeadings) {
            curve.heading(t, h, true);
            t += 1000;
            curve.step(t);
            cx += 0.72 * Math.sin(Math.toRadians(h));
            cy += 0.72 * Math.cos(Math.toRadians(h));
        }
        check("a gentle curve is walked along its own headings", curve.path().size() == curveHeadings.length + 1 && Math.hypot(curve.x() - cx, curve.y() - cy) < 1e-6,
                curve.x() + "," + curve.y() + " vs " + cx + "," + cy);
        // review of #182: shapes of real walks, the heading turning smoothly before each step (a step every 700 ms)
        double[] bed = shape(31, new double[] {0, 0, 0, 45, 90, 90, 135, 180, 180, 180, 180});
        check("two corners the same way with one step between (the end of a bed): two corners, every step moves", bed[1] == 11 && bed[3] < 0.3, Arrays.toString(bed));
        double[] wide = new double[17];
        for (int i = 0; i < 17; i++) wide[i] = i < 2 ? 0 : i < 14 ? 15 * (i - 1) : 180;
        double[] curve180 = shape(32, wide);
        check("a wide half circle (15 degrees a step over 12 steps) is walked, not a turn on the spot", curve180[1] == 17 && curve180[3] < 0.3, Arrays.toString(curve180));
        // limit, pinned (review of #182): a tight half circle (round a table, about 2 m across) at 20 degrees a step
        // or more reads as a turn on the spot, so its turning steps stay
        for (int per : new int[] {20, 30, 36, 45}) {
            int k = (int) Math.round(180.0 / per);
            double[] hs = new double[k + 6];
            for (int i = 0; i < hs.length; i++) hs[i] = i < 3 ? 0 : Math.min(180, per * (i - 2));
            double[] tight = shape(40 + per, hs);
            check("limit: a tight half circle at " + per + " degrees a step counts as a turn on the spot, its " + k + " turning steps stay",
                    tight[0] == hs.length && tight[1] == hs.length - k, Arrays.toString(tight));
        }
        double[] u4 = shape(33, new double[] {0, 0, 0, 45, 90, 135, 180, 180, 180, 180});
        double[] u2 = shape(34, new double[] {0, 0, 0, 90, 180, 180, 180, 180});
        // decided: a U turn in 2 to 4 steps is a pivot, close to a turn on the spot; its steps stay (an error under a metre)
        check("a U turn in 4 steps: its 4 turning steps stay, the walk before and after moves", u4[1] == 6 && u4[0] == 10, Arrays.toString(u4));
        check("a U turn in 2 steps: its 2 turning steps stay", u2[1] == 5 && u2[0] == 8, Arrays.toString(u2));
        check("spin needs: 20 degrees a step at 150, 10 from a full turn on", SearchWalk.spinNeeds(150) == 20 && SearchWalk.spinNeeds(300) == 10 && SearchWalk.spinNeeds(400) == 10
                && Math.abs(SearchWalk.spinNeeds(225) - 15) < 1e-9 && SearchWalk.spinNeeds(100) == 20);
        // Stop in the middle of a corner: the waiting steps move with finish()
        SearchWalk stopped = new SearchWalk().stepLength(0.72);
        stopped.heading(-100, 0, true);
        for (int i = 0; i < 5; i++) {
            stopped.heading(i * 1000L, 0, true);
            stopped.step(i * 1000L + 900);
        }
        stopped.heading(5000, 30, true);
        stopped.step(5900);
        stopped.heading(6000, 60, true);
        stopped.step(6900);
        check("stopping in a corner: 2 steps wait", stopped.waiting() == 2 && stopped.path().size() == 6, stopped.waiting());
        stopped.finish();
        check("and move at Stop, along their own headings", stopped.waiting() == 0 && stopped.path().size() == 8
                && Math.abs(stopped.x() - 0.72 * (Math.sin(Math.toRadians(30)) + Math.sin(Math.toRadians(60)))) < 1e-6, stopped.x() + "," + stopped.y());
        // readings during a turn on the spot keep the place where it happened
        SearchWalk then = new SearchWalk().stepLength(0.72);
        then.heading(-100, 0, true);
        long tt = 0;
        for (int i = 0; i < 3; i++) {
            then.heading(tt, 0, true);
            then.rssi(tt + 10, -70);
            tt += 1000;
            then.step(tt);
        }
        int before = then.samples().size();
        for (int i = 1; i <= 20; i++) {
            for (long u = tt; u < tt + 1000; u += 20) {
                then.heading(u, 18 * (i - 1) + 18.0 * (u - tt) / 1000, true);
                if (u % 120 == 0) then.rssi(u, -70);
            }
            tt += 1000;
            then.step(tt);
        }
        then.finish();
        boolean kept = then.samples().size() > before + 100;
        for (int k = before; k < then.samples().size(); k++) kept &= Math.abs(then.samples().get(k).y - 3 * 0.72) < 1e-9 && Math.abs(then.samples().get(k).x) < 1e-9;
        check("readings during a turn on the spot keep their place (where the turn happened)", kept && Math.abs(then.y() - 3 * 0.72) < 1e-9 && then.steps() == 23, then.x() + "," + then.y());
        SearchWalk away = new SearchWalk().stepLength(0.72);
        away.heading(-500, 0, true);
        t = 0;
        for (int i = 1; i <= 12; i++) {
            away.heading(t, i * 30, true);
            t += 1000;
            away.step(t);
        }
        for (int i = 0; i < 4; i++) {
            away.heading(t, 0, true);
            t += 1000;
            away.step(t);
        }
        check("a turn on the spot, then 4 steps away: only the 4 steps move (the first one after the turn waits for the next)", away.steps() == 16 && Math.abs(away.distance() - 4 * 0.72) < 1e-6
                && Math.abs(away.y() - 4 * 0.72) < 1e-6, away.distance() + " m, at " + away.x() + "," + away.y());
        // a step reported late takes the heading of its own time
        SearchWalk late = new SearchWalk().stepLength(1);
        late.heading(0, 0, true);
        late.heading(500, 0, true);
        late.heading(1000, 90, true);
        check("a late step takes its own time's heading", late.headingAt(600) == 0 && late.headingAt(1000) == 90 && late.headingAt(-5) == 0);
        late.step(600);
        check("and moves that way", Math.abs(late.y() - 1) < 1e-9 && Math.abs(late.x()) < 1e-9, late.x() + "," + late.y());
        check("turn direction: 350 to 10 is 20 to the right, 10 to 350 is 20 to the left", Math.abs(SearchWalk.signed(10 - 350) - 20) < 1e-9 && Math.abs(SearchWalk.signed(350 - 10) + 20) < 1e-9);
    }

    // ------------------------------------------------------------------ circles from noise (issue #136)

    /** Runs walks with the ring {@code where}; returns {walks with a spot, clear circles, clear circles missing the ring, of those not flagged beyond}. */
    static int[] circles(double[][] legs, int where, double noise, double ripple, long base, int runs) {
        int[] c = new int[4];
        for (int i = 0; i < runs; i++) {
            double[] o = searchWalk(base + i, legs, noise, 10, true, where, ripple);
            if (o[3] == 0) continue;
            c[0]++;
            if (o[4] == 0) continue;
            c[1]++;
            if (where == NO_RING || o[2] == 1) continue;
            c[2]++;
            if (o[5] == 0) c[3]++;
        }
        return c;
    }

    static void noiseCircles() {
        check("needed share: grows for a short walk, 15 % for a long one", Math.abs(SearchWalk.neededShare(12) - 25.0 / 31) < 1e-9
                && Math.abs(SearchWalk.neededShare(20) - 25.0 / 39) < 1e-9 && SearchWalk.neededShare(148) == SearchWalk.MIN_EXPLAINED
                && SearchWalk.neededShare(240) == SearchWalk.MIN_EXPLAINED && SearchWalk.neededShare(6) == 1, SearchWalk.neededShare(12));
        // no ring signal at all (the same faint signal everywhere, plus noise): the short L walk must not get a circle
        // (with "15 % explained" it was 33 of 300 at 4 dB; limits written down from 300 other seeds before these ran)
        for (double[] nr : new double[][] {{4, 0}, {2, 0}, {6, 0}, {4, 1.5}, {2, 1.5}}) {
            int[] c = circles(PART, NO_RING, nr[0], nr[1], 400000, 300);
            System.out.printf(Locale.US, "  no ring, L walk, noise %.0f dB, ripple up to %.0f dB: %d clear circles in %d walks%n", nr[0], 4 * nr[1], c[1], c[0]);
            check("no ring, L walk (noise " + nr[0] + ", ripple " + 4 * nr[1] + " dB): a spot every time, a clear circle in under " + (nr[1] == 0 ? "2" : "5") + " %",
                    c[0] == 300 && c[1] < (nr[1] == 0 ? 0.02 : 0.05) * 300, c[1]);
        }
        int[] around = circles(AROUND, NO_RING, 4, 0, 400000, 200);
        check("no ring, walk around the room: a spot every time, no clear circle", around[0] == 200 && around[1] == 0, around[0] + " spots, " + around[1] + " clear");
        // the ring 2 to 5 m outside the L walk: few clear circles, and a circle that misses it is almost always flagged as maybe farther
        // (with "15 % explained": 114 clear in 300, 81 missing the ring, 13 of them not flagged)
        int[] out = circles(PART, RING_OUTSIDE, 5, 1.5, 400000, 300);
        System.out.printf(Locale.US, "  ring 2 to 5 m outside the L walk: %d clear circles in %d walks, %d miss the ring, %d of those not flagged beyond%n", out[1], out[0], out[2], out[3]);
        check("ring outside the L walk: a spot every time, a clear circle in under a third of walks", out[0] == 300 && out[1] < 100, out[0] + " spots, " + out[1] + " clear");
        check("ring outside the L walk: a clear circle that misses it and does not say it may be farther in under 5 % of walks", out[3] < 15, out[3]);
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
        // never warm, one lone heart rate in the first minutes: a stray since issue #94, so not worn
        LastWorn.Result tb2 = LastWorn.find(table[0], table[1], new double[] {BASE + 100});
        check("a lone heart rate at the start is a stray", tb2.state == LastWorn.OFF && Double.isNaN(tb2.at) && tb2.strayHrAt == BASE + 100, tb2.at - BASE);

        // readings out of order are sorted, and an even number of cool readings gives a middle value
        LastWorn.Result un = LastWorn.find(new double[] {BASE + 600, BASE, BASE + 300, BASE + 1200, BASE + 900}, new double[] {20, 33, 22, 24, 23}, new double[0]);
        check("unsorted input", un.state == LastWorn.OFF && un.at == BASE && un.roomC == 22.5, un.roomC);
        // only heart rate tells it was worn (all temperatures cool, beats until the end)
        LastWorn.Result hr = LastWorn.find(new double[] {BASE, BASE + 300}, new double[] {25, 25}, new double[] {BASE + 700, BASE + 1000});
        check("cool all along but beats to the end: worn, no skin value", hr.state == LastWorn.WORN && Double.isNaN(hr.skinC) && hr.at == BASE + 1000);

        lastWornWarmth();

        check("charging: up", LastWorn.charging(40, 54) == 1);
        check("charging: down", LastWorn.charging(60, 54) == 0);
        check("charging: about the same is unknown", LastWorn.charging(100, 100) == -1 && LastWorn.charging(55, 54) == -1);
        check("charging: no earlier read", LastWorn.charging(-1, 54) == -1);
    }

    /** Temperature every 5 minutes: {from hour, to hour, C} ranges, 21 C outside them (later ranges win). */
    static double[][] tempsC(double hours, double[]... ranges) {
        int n = (int) Math.round(hours * 12);
        double[] t = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = BASE + i * 300;
            double h = i / 12.0;
            c[i] = 21;
            for (double[] w : ranges) if (h >= w[0] - 1e-9 && h < w[1] - 1e-9) c[i] = w[2];
        }
        return new double[][] {t, c};
    }

    /** Warm readings need a heart rate near, single odd readings do not move the time (issue #94). */
    static void lastWornWarmth() {
        // A: worn with heart rate every 10 minutes until 22 h, then 8 h at 30 C without heart rate (charger)
        double[][] a = tempsC(30, new double[] {0, 22, 34}, new double[] {22, 30, 30});
        double[] hrA = new double[22 * 6];
        for (int i = 0; i < hrA.length; i++) hrA[i] = BASE + i * 600 + 60;
        LastWorn.Result ra = LastWorn.find(a[0], a[1], hrA);
        check("A: warm on the charger without heart rate is not worn, not sure since the last heart rate",
                ra.state == LastWorn.UNSURE && ra.at == hrA[hrA.length - 1] && ra.skinC == 30 && ra.newest == a[0][a[0].length - 1], ra.state + " " + (ra.at - BASE) / H);
        // A2: taken off at 22 h, 4 h at 21 C, then 28 C from 26 h (sunny window) until 30 h
        double[][] a2 = tempsC(30, new double[] {0, 22, 34}, new double[] {26, 30, 28});
        LastWorn.Result r2 = LastWorn.find(a2[0], a2[1], beats(new double[] {0, 22}));
        check("A2: warm again without heart rate, still taken off at 22 h", r2.state == LastWorn.OFF && Math.abs(r2.at - (BASE + 22 * H)) <= 300, r2.state + " " + (r2.at - BASE) / H);
        check("A2: the warm stretch is reported", r2.warmNoHrNow && r2.warmNoHrAt == BASE + 26 * H && r2.warmNoHrC == 28 && r2.warmNoHrCount == 48, r2.warmNoHrAt);
        check("A2: room temperature from the cool readings only", r2.roomC == 21, r2.roomC);
        // A2 half an hour into the warmth: still reads as worn (could be put back on), the honest limit
        double[][] a2early = tempsC(26.5, new double[] {0, 22, 34}, new double[] {26, 26.5, 28});
        LastWorn.Result r2e = LastWorn.find(a2early[0], a2early[1], beats(new double[] {0, 22}));
        check("A2 half an hour in: just put on", r2e.state == LastWorn.WORN && r2e.justOn, r2e.state);
        // E: taken off at 10 h (21 C), one 28 C reading at 15 h, cool until 20 h
        double[][] e = tempsC(20, new double[] {0, 10, 34}, new double[] {15, 15 + 1 / 12.0, 28});
        LastWorn.Result re = LastWorn.find(e[0], e[1], beats(new double[] {0, 10}));
        check("E: one warm reading does not move the time", re.state == LastWorn.OFF && Math.abs(re.at - (BASE + 10 * H)) <= 300, (re.at - BASE) / H);
        check("E: the warm reading is reported", !re.warmNoHrNow && re.warmNoHrCount == 1 && re.warmNoHrAt == BASE + 15 * H && re.warmNoHrC == 28, re.warmNoHrAt);
        // C: taken off at 10 h, one stray heart rate at 14 h
        double[][] cc = tempsC(20, new double[] {0, 10, 34});
        double[] hrC = beats(new double[] {0, 10});
        hrC = Arrays.copyOf(hrC, hrC.length + 1);
        hrC[hrC.length - 1] = BASE + 14 * H;
        LastWorn.Result rc = LastWorn.find(cc[0], cc[1], hrC);
        check("C: one stray heart rate does not move the time", rc.state == LastWorn.OFF && Math.abs(rc.at - (BASE + 10 * H)) <= 300, (rc.at - BASE) / H);
        check("C: the stray heart rate is reported", rc.strayHrAt == BASE + 14 * H && rc.warmNoHrCount == 0, rc.strayHrAt);
        // two heart rates 10 minutes apart while cool still mean cold fingers
        double[] hrC2 = Arrays.copyOf(hrC, hrC.length + 1);
        hrC2[hrC2.length - 1] = BASE + 14 * H + 600;
        LastWorn.Result rc2 = LastWorn.find(cc[0], cc[1], hrC2);
        check("two heart rates while cool are not strays", rc2.state == LastWorn.OFF && Math.abs(rc2.at - (BASE + 14 * H + 600)) <= 300 && Double.isNaN(rc2.strayHrAt), (rc2.at - BASE) / H);
        // worn, with a 50 minute gap in the heart rate (a walk), newest reading inside the gap: still worn
        double[][] g = tempsC(12, new double[] {0, 12, 33});
        LastWorn.Result rg = LastWorn.find(g[0], g[1], beats(new double[] {0, 11.1}));
        check("a heart rate gap under an hour while warm: worn", rg.state == LastWorn.WORN && !rg.justOn && rg.skinC == 33, rg.state);
        // the same gap in the middle of the day does not end the wearing
        LastWorn.Result rg2 = LastWorn.find(g[0], g[1], beats(new double[] {0, 5}, new double[] {6.5, 12}));
        check("a heart rate gap in the middle: still worn", rg2.state == LastWorn.WORN, rg2.state);
        // charger right after taking off (no cool reading between), then the table: one warm stretch, so the
        // time is when it left the charger (the honest limit; an active afternoon without heart rate looks the same)
        double[][] ch = tempsC(24, new double[] {0, 18, 34}, new double[] {18, 20, 30});
        LastWorn.Result rch = LastWorn.find(ch[0], ch[1], beats(new double[] {0, 18}));
        check("charger after wearing, then the table: the end of the warm stretch", rch.state == LastWorn.OFF
                && Math.abs(rch.at - (BASE + 20 * H)) <= 300 && rch.warmNoHrCount == 0, (rch.at - BASE) / H);
        // review of PR #103: worn all day, heart rate in the morning only (an active afternoon), off at 17 h, an hour on the table
        double[][] aa = tempsC(18, new double[] {0, 17, 33});
        LastWorn.Result raa = LastWorn.find(aa[0], aa[1], beats(new double[] {0, 12}));
        check("active afternoon without heart rate: off when taken off, not in the morning", raa.state == LastWorn.OFF
                && Math.abs(raa.at - (BASE + 17 * H)) <= 300 && raa.hrRule, (raa.at - BASE) / H);
        // no heart rate at all in the history: temperature decides on its own, as before
        double[][] nh = tempsC(30, new double[] {0, 22, 34}, new double[] {22, 30, 30});
        LastWorn.Result rnh = LastWorn.find(nh[0], nh[1], new double[0]);
        check("no heart rate at all: warm reads as worn", rnh.state == LastWorn.WORN && !rnh.hasHr && !rnh.justOn, rnh.state);
        // just put on: one warm reading after hours on the table, heart rate only from earlier
        double[][] po = tempsC(20.1, new double[] {0, 10, 34}, new double[] {20, 21, 32});
        LastWorn.Result rpo = LastWorn.find(po[0], po[1], beats(new double[] {0, 10}));
        check("just put on: worn", rpo.state == LastWorn.WORN && rpo.justOn && rpo.skinC == 32, rpo.state);
        // a lone heart rate after the newest temperature: a stray while the ring reads cool ...
        double[][] nw = tempsC(5, new double[] {0, 3, 34});
        LastWorn.Result rnw = LastWorn.find(nw[0], nw[1], new double[] {BASE + 5 * H + 120});
        check("a lone heart rate after a cool newest reading is a stray", rnw.state == LastWorn.OFF && Math.abs(rnw.at - (BASE + 3 * H)) <= 300
                && rnw.strayHrAt == BASE + 5 * H + 120, rnw.state);
        // ... but counts while it reads warm
        double[][] nw2 = tempsC(5, new double[] {0, 5, 34});
        LastWorn.Result rnw2 = LastWorn.find(nw2[0], nw2[1], new double[] {BASE + 5 * H + 120});
        check("a lone heart rate after a warm newest reading counts", rnw2.state == LastWorn.WORN && rnw2.at == BASE + 5 * H + 120 && rnw2.lastHr == BASE + 5 * H + 120, rnw2.state);
        // warm without heart rate where too few daytime heart rates exist to check: temperature decides
        LastWorn.Result rw = LastWorn.find(new double[] {BASE + 3 * H, BASE + 4 * H, BASE + 5 * H}, new double[] {30, 30, 30}, new double[] {BASE, BASE + 600});
        check("too little daytime heart rate to check: warm reads as worn", rw.state == LastWorn.WORN && !rw.hrRule && !rw.confirmed && !rw.justOn, rw.state + " " + rw.at);
        // review of PR #103: only night heart rate (HRV, 0 to 7 h), worn to 18 h, on the table to 22 h
        double[][] nt = tempsC(22, new double[] {0, 18, 33});
        LastWorn.Result rnt = LastWorn.find(nt[0], nt[1], beats(new double[] {0, 7}), new double[0]);
        check("night heart rate only: off at 18 h, not in the morning", rnt.state == LastWorn.OFF && !rnt.hrRule
                && Math.abs(rnt.at - (BASE + 18 * H)) <= 300, (rnt.at - BASE) / H);
        LastWorn.Result rnt2 = LastWorn.find(nt[0], nt[1], new double[0], beats(new double[] {0, 18}));
        check("daytime heart rate every 5 minutes: off at 18 h", rnt2.state == LastWorn.OFF && rnt2.hrRule
                && Math.abs(rnt2.at - (BASE + 18 * H)) <= 300, (rnt2.at - BASE) / H);
        // night heart rate only, still worn at 15 h: worn, not "not sure" all day
        double[][] nt3 = tempsC(15, new double[] {0, 15, 33});
        LastWorn.Result rnt3 = LastWorn.find(nt3[0], nt3[1], beats(new double[] {0, 7}), new double[0]);
        check("night heart rate only, worn all day: worn, but the night heart rate 8 hours ago confirms nothing", rnt3.state == LastWorn.WORN
                && !rnt3.hrRule && !rnt3.confirmed && !rnt3.justOn && rnt3.lastHr < BASE + 7.1 * H, rnt3.state);
        // review of PR #103: the newest heart rate exactly an hour before the newest reading still confirms, a second more does not
        double[][] nt4 = tempsC(15, new double[] {0, 15, 33});
        double lastT = nt4[0][nt4[0].length - 1];
        LastWorn.Result rc1 = LastWorn.find(nt4[0], nt4[1], new double[] {lastT - 3600 - 600, lastT - 3600}, new double[0]);
        LastWorn.Result rc0 = LastWorn.find(nt4[0], nt4[1], new double[] {lastT - 3601 - 600, lastT - 3601}, new double[0]);
        check("a heart rate exactly an hour before the newest reading confirms it", rc1.state == LastWorn.WORN && rc1.confirmed
                && !rc0.confirmed && rc0.state == LastWorn.WORN, rc1.confirmed + " " + rc0.confirmed);
        // sparse daytime heart rate (one every 2 hours): too little to check, temperature decides
        double[] sparse = new double[8];
        for (int i = 0; i < sparse.length; i++) sparse[i] = BASE + i * 2 * H + 60;
        LastWorn.Result rsp = LastWorn.find(nt3[0], nt3[1], new double[0], sparse);
        check("sparse daytime heart rate: worn", rsp.state == LastWorn.WORN && !rsp.hrRule, rsp.state);
        // a lone heart rate and a single warm reading do not confirm each other (off at 10 h, 14:00 and 28 C at 14:10)
        double[][] lc = tempsC(20, new double[] {0, 10, 34}, new double[] {14 + 2 / 12.0, 14 + 3 / 12.0, 28});
        double[] hrL = beats(new double[] {0, 10});
        hrL = Arrays.copyOf(hrL, hrL.length + 1);
        hrL[hrL.length - 1] = BASE + 14 * H;
        LastWorn.Result rlc = LastWorn.find(lc[0], lc[1], hrL);
        check("a lone heart rate does not confirm a lone warm reading", rlc.state == LastWorn.OFF && Math.abs(rlc.at - (BASE + 10 * H)) <= 300
                && rlc.strayHrAt == BASE + 14 * H && rlc.warmNoHrCount == 1, (rlc.at - BASE) / H);

        lastWornEvents();
        lastWornBoundaries();
        lastWornSparse();
        lastWornCoolGap();
        lastWornText();
    }

    /**
     * Issue #135: readings every 5 minutes, warm (33 C) from T0 to the time it came off with a daytime AOHR
     * burst (3 values 1.92 s apart, one event) at every reading, then {@code coolMin} minutes at 22 C, then
     * {@code warmMin} minutes at 31 C without heart rate (the charger). {@code extraHr}: more bursts.
     */
    static LastWorn.Result offThenCharger(double offMin, double coolMin, double warmMin, double... extraHr) {
        List<Double> t = new ArrayList<Double>(), c = new ArrayList<Double>();
        for (double m = 0; m <= offMin + coolMin + warmMin + 1e-9; m += 5) {
            t.add(BASE + m * 60);
            c.add(m <= offMin + 1e-9 ? 33.0 : m <= offMin + coolMin + 1e-9 ? 22.0 : 31.0);
        }
        List<Double> hr = new ArrayList<Double>();
        List<Integer> id = new ArrayList<Integer>();
        int ev = 0;
        for (double m = 0; m <= offMin + 1e-9; m += 5, ev++) for (int k = 0; k < 3; k++) {
            hr.add(BASE + m * 60 + k * 1.92);
            id.add(ev);
        }
        for (double x : extraHr) {
            for (int k = 0; k < 3; k++) {
                hr.add(x + k * 1.92);
                id.add(ev);
            }
            ev++;
        }
        double[] tt = new double[t.size()], cc = new double[t.size()], hh = new double[hr.size()];
        int[] ii = new int[hr.size()];
        for (int i = 0; i < tt.length; i++) {
            tt[i] = t.get(i);
            cc[i] = c.get(i);
        }
        for (int i = 0; i < hh.length; i++) {
            hh[i] = hr.get(i);
            ii[i] = id.get(i);
        }
        return LastWorn.find(tt, cc, new double[0], null, hh, ii);
    }

    /** Issue #135: a heart rate from before the ring came off does not vouch for a warm stretch after cool readings. */
    static void lastWornCoolGap() {
        // the issue's case: off at T60, the table for 10 min, the charger for 25 min
        LastWorn.Result r = offThenCharger(60, 10, 25);
        check("#135: off, table 10 min, charger 25 min: not confirmed, at most just put on", r.hrRule && r.state == LastWorn.WORN
                && !r.confirmed && r.justOn && r.lastHr < BASE + 61 * 60, r.state + " confirmed " + r.confirmed + " justOn " + r.justOn);
        // the test the issue asked for (off, table 10 min, charger 30 min) is one cell of the sweep below
        // the issue's sweep: cool 5 to 25 minutes, charger 10 to 45 minutes; never "from a finger, heart rate until ..."
        int bad = 0;
        String first = null;
        for (double cool : new double[] {5, 10, 15, 20, 25}) {
            for (double warm : new double[] {10, 20, 30, 45}) {
                LastWorn.Result s = offThenCharger(60, cool, warm);
                if (!s.hrRule || s.state != LastWorn.WORN || s.confirmed || !s.justOn) {
                    bad++;
                    if (first == null) first = "cool " + cool + " warm " + warm + ": " + s.state + " confirmed " + s.confirmed;
                }
            }
        }
        check("#135: sweep of 20 cool and charger lengths: all just put on, none confirmed", bad == 0, bad + " wrong, first " + first);
        // over an hour on the charger: taken off at T60, the warm readings named
        LastWorn.Result rl = offThenCharger(60, 10, 90);
        check("#135: off, table 10 min, charger 90 min: taken off at T60, the charger named", rl.state == LastWorn.OFF && rl.hrRule
                && Math.abs(rl.at - (BASE + 60 * 60)) <= 10 && rl.warmNoHrNow && rl.warmNoHrAt == BASE + 75 * 60, rl.state + " " + (rl.at - BASE) / 60);
        // a heart rate after the last cool reading still counts: put back on, beats before the first warm reading
        LastWorn.Result rb = offThenCharger(60, 10, 25, BASE + 72 * 60);
        check("#135: a heart rate between the last cool and the first warm reading confirms it", rb.state == LastWorn.WORN && rb.confirmed
                && !rb.justOn && rb.lastHr > BASE + 72 * 60, rb.state + " confirmed " + rb.confirmed);
        // a heart rate at the time of the last cool reading is not "between": it still counts
        LastWorn.Result re = offThenCharger(60, 10, 25, BASE + 70 * 60);
        check("#135: a heart rate at the time of the last cool reading counts", re.state == LastWorn.WORN && re.confirmed, re.state);
        // the documented limit stays: the charger right after taking it off, no cool reading between
        LastWorn.Result rn = offThenCharger(60, 0, 25);
        check("#135: the charger right after taking off (no cool reading): still reads worn and confirmed (the limit)",
                rn.state == LastWorn.WORN && rn.confirmed, rn.state);
        // review of PR #179: heart rate while the skin read cool (cold fingers), then warm again: the heart rate
        // measured during the cool readings still counts. 33 C with a value every 10 min until 09:41, 25 C from
        // 09:00 to 09:50, warm from 09:55 to 10:40
        double[][] cf = tempsC(10 + 45 / 60.0, new double[] {0, 9, 33}, new double[] {9, 9 + 55 / 60.0, 25}, new double[] {9 + 55 / 60.0, 11, 33});
        double[] cfHr = every(0, 9 + 42 / 60.0, 10); // the last at 09:41
        LastWorn.Result rcf = LastWorn.find(cf[0], cf[1], cfHr);
        check("#179 review: heart rate while cool, then warm: worn and confirmed", rcf.state == LastWorn.WORN && rcf.hrRule && rcf.confirmed
                && !rcf.justOn && rcf.lastHr == cfHr[cfHr.length - 1], rcf.state + " confirmed " + rcf.confirmed + " justOn " + rcf.justOn);
        // review of PR #179: a single cool reading in the middle of wearing, then over an hour warm without heart
        // rate: as before #135, not sure since the last heart rate (one reading cannot be told from 5 minutes on a table)
        LastWorn.Result r1 = offThenCharger(60, 5, 90);
        check("#179 review: one cool reading, then 90 min warm without heart rate: not sure since the last heart rate",
                r1.state == LastWorn.UNSURE && Math.abs(r1.at - (BASE + 60 * 60)) <= 10, r1.state + " " + (r1.at - BASE) / 60);
        // two cool readings are a real stay on the table: taken off (the 90 min case of the #135 checks)
        // the same with only night heart rate (an HRV event until 07:55, 15 min cool, then the charger): the rule
        // is off, so it reads worn, but the night heart rate before the cool readings confirms nothing
        double[][] nt = tempsC(9, new double[] {0, 8, 33}, new double[] {8, 8.25, 22}, new double[] {8.25, 9, 31});
        double[] hrv = burst(BASE + 7 * H + 30 * 60, 6, 300); // 07:30 to 07:55
        LastWorn.Result rn2 = LastWorn.find(nt[0], nt[1], hrv, same(6, 1), new double[0], null);
        check("#135: night heart rate until 07:55, 15 min cool, then the charger: worn, not confirmed", rn2.state == LastWorn.WORN
                && !rn2.hrRule && !rn2.confirmed && rn2.lastHr == hrv[5], rn2.state + " confirmed " + rn2.confirmed);
    }

    /** Daytime heart rate every 5 minutes in the ranges, each value an event of its own (ids from 0). */
    static int[] ids(int n, int from) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = from + i;
        return out;
    }

    /** {@code count} heart rates of one event from {@code at}, {@code step} seconds apart. */
    static double[] burst(double at, int count, double step) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = at + i * step;
        return out;
    }

    static int[] same(int n, int id) {
        int[] out = new int[n];
        Arrays.fill(out, id);
        return out;
    }

    /**
     * Review of PR #103: the values of one stored event (six HRV values 5 minutes apart, an AOHR burst
     * 1.92 s apart) are one measurement and do not vouch for each other. Daytime heart rate is stored,
     * so the heart rate rule applies.
     */
    static void lastWornEvents() {
        double[] day = beats(new double[] {0, 10});
        int[] dayId = ids(day.length, 0);
        // a stray HRV event at 14 h, in the middle of the cool readings
        double[][] cc = tempsC(20, new double[] {0, 10, 34});
        double[] hrv = burst(BASE + 14 * H, 6, 300);
        LastWorn.Result rh = LastWorn.find(cc[0], cc[1], hrv, same(6, 1000), day, dayId);
        check("a stray six value HRV event does not move the time", rh.state == LastWorn.OFF && rh.hrRule
                && Math.abs(rh.at - (BASE + 10 * H)) <= 300, rh.state + " " + (rh.at - BASE) / H);
        // the newest heart rate that did not count: the event's last value, 5 times 5 minutes after its first
        check("the stray HRV event is reported", rh.strayHrAt == BASE + 14 * H + 1500, (rh.strayHrAt - BASE) / H);
        // without event numbers its values would vouch for each other (the bug the review found)
        LastWorn.Result old = LastWorn.find(cc[0], cc[1], hrv, day);
        check("without event numbers the six values pair up (why the numbers are needed)", old.state == LastWorn.OFF
                && old.at > BASE + 14 * H, (old.at - BASE) / H);
        // the same HRV event at the end of the history (its values reach past the newest reading)
        double[][] ce = tempsC(14.5, new double[] {0, 10, 34});
        LastWorn.Result rhe = LastWorn.find(ce[0], ce[1], hrv, same(6, 1000), day, dayId);
        check("a stray HRV event at the end: not worn, off at 10 h", rhe.state == LastWorn.OFF
                && Math.abs(rhe.at - (BASE + 10 * H)) <= 300 && !Double.isNaN(rhe.strayHrAt), rhe.state + " " + (rhe.at - BASE) / H);
        // an AOHR burst (20 values 1.92 s apart) at 14 h, in the middle and at the end
        double[] aohr = burst(BASE + 14 * H, 20, 1.92);
        double[] dayA = Arrays.copyOf(day, day.length + aohr.length);
        System.arraycopy(aohr, 0, dayA, day.length, aohr.length);
        int[] dayAId = Arrays.copyOf(dayId, dayA.length);
        Arrays.fill(dayAId, day.length, dayA.length, 2000);
        LastWorn.Result ra = LastWorn.find(cc[0], cc[1], new double[0], null, dayA, dayAId);
        check("a stray AOHR burst does not move the time", ra.state == LastWorn.OFF && Math.abs(ra.at - (BASE + 10 * H)) <= 300
                && ra.strayHrAt == aohr[aohr.length - 1], ra.state + " " + (ra.at - BASE) / H);
        double[][] ca = tempsC(14, new double[] {0, 10, 34});
        LastWorn.Result rae = LastWorn.find(ca[0], ca[1], new double[0], null, dayA, dayAId);
        check("a stray AOHR burst after the newest cool reading: not worn", rae.state == LastWorn.OFF
                && Math.abs(rae.at - (BASE + 10 * H)) <= 300, rae.state + " " + (rae.at - BASE) / H);
        // two HRV events half an hour apart while cool still pair across events: cold fingers until the last value
        double[] two = Arrays.copyOf(hrv, 12);
        System.arraycopy(burst(BASE + 14.5 * H, 6, 300), 0, two, 6, 6);
        int[] twoId = same(12, 1000);
        Arrays.fill(twoId, 6, 12, 1001);
        LastWorn.Result rt = LastWorn.find(cc[0], cc[1], two, twoId, day, dayId);
        check("two HRV events while cool are not strays", rt.state == LastWorn.OFF && Double.isNaN(rt.strayHrAt)
                && Math.abs(rt.at - two[11]) <= 300, (rt.at - BASE) / H);
    }

    /** The borders "at most an hour" and "within 30 minutes" are inclusive (review of PR #103). */
    static void lastWornBoundaries() {
        double[] day = beats(new double[] {0, 10});
        // warm without heart rate for exactly an hour (readings 20:00 to 21:00): just put on
        double[][] w60 = tempsC(21 + 1 / 12.0, new double[] {0, 10, 34}, new double[] {20, 22, 30});
        LastWorn.Result r60 = LastWorn.find(w60[0], w60[1], day);
        check("warm for exactly 3600 s without heart rate: just put on", r60.state == LastWorn.WORN && r60.justOn && r60.hrRule
                && w60[0][w60[0].length - 1] - (BASE + 20 * H) == 3600, r60.state);
        // one reading more (3900 s): not on a finger, off at 10 h
        double[][] w65 = tempsC(21 + 2 / 12.0, new double[] {0, 10, 34}, new double[] {20, 22, 30});
        LastWorn.Result r65 = LastWorn.find(w65[0], w65[1], day);
        check("warm for 3900 s without heart rate: off at 10 h", r65.state == LastWorn.OFF && r65.warmNoHrNow
                && Math.abs(r65.at - (BASE + 10 * H)) <= 300, r65.state);
        // two heart rates (separate events) ending exactly 1800 s before a warm stretch of two hours: near it
        // (they lie in the cool readings before it, cold fingers, so issue #135 keeps them)
        double[][] w2 = tempsC(22, new double[] {0, 10, 34}, new double[] {20, 22, 30});
        double edge = BASE + 20 * H - 1800;
        double[] at = Arrays.copyOf(day, day.length + 2);
        at[day.length] = edge - 60;
        at[day.length + 1] = edge;
        LastWorn.Result rn = LastWorn.find(w2[0], w2[1], at);
        check("a heart rate exactly 1800 s from a warm reading is near it: not sure since then", rn.state == LastWorn.UNSURE && rn.at == edge, rn.state);
        at[day.length] = edge - 61;
        at[day.length + 1] = edge - 1;
        LastWorn.Result rf = LastWorn.find(w2[0], w2[1], at);
        check("1801 s away it is not: the warm stretch does not count", rf.state == LastWorn.OFF && rf.warmNoHrNow, rf.state);
        // the heart rate rule looks at the last 48 hours only: daytime heart rate that stopped 3 days ago does not switch it on
        double[][] longer = tempsC(4 * 24, new double[] {0, 96, 33});
        LastWorn.Result rl = LastWorn.find(longer[0], longer[1], new double[0], beats(new double[] {0, 20}));
        check("daytime heart rate that stopped 3 days ago: temperature decides", rl.state == LastWorn.WORN && !rl.hrRule, rl.state);
        LastWorn.Result rl2 = LastWorn.find(longer[0], longer[1], new double[0], beats(new double[] {0, 60}));
        check("daytime heart rate within the last 48 hours: the rule applies", rl2.hrRule && rl2.state == LastWorn.UNSURE, rl2.state);
    }

    /** Single value daytime heart rates every {@code minutes} from {@code fromH} to {@code toH}, each its own event. */
    static double[] every(double fromH, double toH, double minutes) {
        List<Double> l = new ArrayList<Double>();
        for (double x = BASE + fromH * H + 60; x < BASE + toH * H; x += minutes * 60) l.add(x);
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    /**
     * Issue #124: the heart rate rule only counts paired daytime heart rates (one with a heart rate of
     * another event within 30 minutes), the same evidence a warm stretch needs. Sparse daytime heart rate
     * switches it off, so temperature decides.
     */
    static void lastWornSparse() {
        // the issue's case: 1 h at 24 C, then 11 h at 33 C, one daytime value every 35, 45 or 25 minutes
        double[][] d = tempsC(12, new double[] {0, 1, 24}, new double[] {1, 12, 33});
        for (double every : new double[] {35, 45}) {
            double[] hr = every(1, 12, every);
            LastWorn.Result r = LastWorn.find(d[0], d[1], hr);
            check("worn with daytime heart rate every " + (int) every + " min: worn, temperature decides", r.state == LastWorn.WORN
                    && !r.hrRule && r.skinC == 33 && r.lastHr == hr[hr.length - 1], r.state + " rule " + r.hrRule);
            check("every " + (int) every + " min: the last heart rate under an hour old confirms it", r.confirmed && !r.justOn, r.confirmed);
        }
        double[] hr25 = every(1, 12, 25);
        LastWorn.Result r25 = LastWorn.find(d[0], d[1], hr25);
        check("worn with daytime heart rate every 25 min: worn, the rule applies", r25.state == LastWorn.WORN && r25.hrRule && r25.confirmed, r25.state + " rule " + r25.hrRule);
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            LastWorn.Result r35 = LastWorn.find(d[0], d[1], every(1, 12, 35));
            String t35 = LastWorn.text(r35, d[0][d[0].length - 1], new LastWorn.Clock() {
                @Override
                public String when(double unix) {
                    return "T" + Math.round((unix - BASE) / 60);
                }
            });
            check("card text every 35 min: from a finger, with heart rate", t35.equals("The newest reading (T715) was from a finger (skin 33.0 °C, heart rate until T691)."), t35);
        } finally {
            Locale.setDefault(before);
        }
        // the honest limit: with sparse daytime heart rate a warm charger cannot be told from a finger (as before #94)
        double[][] ch = tempsC(18, new double[] {0, 1, 24}, new double[] {1, 12, 33}, new double[] {12, 18, 30});
        LastWorn.Result rch = LastWorn.find(ch[0], ch[1], every(1, 12, 40));
        check("sparse daytime heart rate, then 6 h on a warm charger: reads worn, not confirmed", rch.state == LastWorn.WORN && !rch.hrRule
                && !rch.confirmed && rch.skinC == 30, rch.state + " rule " + rch.hrRule);
        // dense daytime heart rate, then the charger: unchanged (issue #94)
        LastWorn.Result rdc = LastWorn.find(ch[0], ch[1], every(1, 12, 10));
        check("dense daytime heart rate, then the charger: not sure", rdc.state == LastWorn.UNSURE && rdc.hrRule, rdc.state);

        // review of PR #141: a dense day, then a day with one value every 40 minutes. The rule must look at
        // today's sparse readings too, and lone values of different events vouch for a warm stretch.
        double[][] twoDays = tempsC(36, new double[] {0, 1, 21}, new double[] {1, 12, 33}, new double[] {12, 24, 21}, new double[] {24, 36, 33});
        double[] dense = every(1, 12, 10), sparse = every(24, 36, 40);
        double[] both = Arrays.copyOf(dense, dense.length + sparse.length);
        System.arraycopy(sparse, 0, both, dense.length, sparse.length);
        LastWorn.Result r2d = LastWorn.find(twoDays[0], twoDays[1], both);
        check("dense day, then a sparse day worn: worn, the rule judged on both days", r2d.state == LastWorn.WORN && !r2d.hrRule
                && r2d.confirmed && r2d.lastHr == sparse[sparse.length - 1], r2d.state + " rule " + r2d.hrRule);
        // a short sparse stretch after a long dense day: the rule stays on, the lone values of different events count
        double[][] shortS = tempsC(27, new double[] {0, 1, 21}, new double[] {1, 12, 33}, new double[] {12, 24, 21}, new double[] {24, 27, 33});
        double[] sparse3 = every(24, 27, 40);
        double[] both3 = Arrays.copyOf(dense, dense.length + sparse3.length);
        System.arraycopy(sparse3, 0, both3, dense.length, sparse3.length);
        LastWorn.Result r3 = LastWorn.find(shortS[0], shortS[1], both3);
        check("dense day, then 3 h worn with a value every 40 min: worn and confirmed", r3.state == LastWorn.WORN && r3.hrRule && r3.confirmed, r3.state + " rule " + r3.hrRule);
        // the charger after a dense day (cool between) with two stray values 2 hours apart: still not a finger
        double[][] chg = tempsC(30, new double[] {0, 1, 21}, new double[] {1, 12, 33}, new double[] {12, 24, 21}, new double[] {24, 30, 30});
        double[] strays = Arrays.copyOf(dense, dense.length + 2);
        strays[dense.length] = BASE + 25 * H;
        strays[dense.length + 1] = BASE + 27 * H;
        LastWorn.Result rcs = LastWorn.find(chg[0], chg[1], strays);
        check("charger with two stray values 2 h apart: off at 12 h, both named", rcs.state == LastWorn.OFF && rcs.hrRule && rcs.warmNoHrNow
                && rcs.warmLoneHr == 2 && Math.abs(rcs.at - (BASE + 12 * H)) <= 300, rcs.state + " " + (rcs.at - BASE) / H);
        // the honest limit: two separate values within an hour of each other on the charger read as a finger
        strays[dense.length + 1] = BASE + 25 * H + 40 * 60;
        LastWorn.Result rcl = LastWorn.find(chg[0], chg[1], strays);
        check("charger with two stray values 40 min apart: reads as a finger (the limit)", rcl.state != LastWorn.OFF, rcl.state);
        // the border of loneEvents: two values 60 minutes apart count, 61 minutes apart do not
        strays[dense.length] = BASE + 25 * H;
        strays[dense.length + 1] = BASE + 26 * H;
        LastWorn.Result r60 = LastWorn.find(chg[0], chg[1], strays);
        strays[dense.length + 1] = BASE + 26 * H + 60;
        LastWorn.Result r61 = LastWorn.find(chg[0], chg[1], strays);
        check("lone values of two events 60 min apart count, 61 min apart do not", r60.state != LastWorn.OFF && r61.state == LastWorn.OFF, r60.state + " " + r61.state);
        // review of afd266a: a lone finger value before the ring went cool and one phantom value on the charger
        // after it do not vouch for the charger together (the window stops at the cool readings); the phantom
        // stays a stray, and the newest heart rate that counts is the finger value (as on main)
        double[][] cb = tempsC(13, new double[] {0, 12, 33}, new double[] {12, 12 + 20 / 60.0, 21}, new double[] {12 + 20 / 60.0, 13, 30});
        double[] cbHr = every(0, 11, 10);
        cbHr = Arrays.copyOf(cbHr, cbHr.length + 2);
        cbHr[cbHr.length - 2] = BASE + 11 * H + 55 * 60;
        cbHr[cbHr.length - 1] = BASE + 12 * H + 50 * 60;
        LastWorn.Result rcb = LastWorn.find(cb[0], cb[1], cbHr);
        check("a finger value before a cool gap and a phantom on the charger: not confirmed by the phantom", rcb.hrRule
                && rcb.lastHr == cbHr[cbHr.length - 2] && rcb.at != cbHr[cbHr.length - 1], rcb.state + " at " + (rcb.at - BASE) / 60 + " last " + (rcb.lastHr - BASE) / 60);
        // one event (an AOHR burst) on the charger still does not vouch for itself
        double[] burstOn = Arrays.copyOf(dense, dense.length + 20);
        int[] burstId = Arrays.copyOf(ids(dense.length, 0), burstOn.length);
        for (int k = 0; k < 20; k++) {
            burstOn[dense.length + k] = BASE + 25 * H + k * 1.92;
            burstId[dense.length + k] = 5000;
        }
        LastWorn.Result rb = LastWorn.find(chg[0], chg[1], new double[0], null, burstOn, burstId);
        check("charger with one AOHR burst: off at 12 h", rb.state == LastWorn.OFF && Math.abs(rb.at - (BASE + 12 * H)) <= 300, rb.state);

        // MIN_DAY_READINGS: 12 warm readings up to the newest daytime heart rate switch the rule on, 11 do not
        double[][] w = tempsC(2, new double[] {0, 2, 33});
        LastWorn.Result r12 = LastWorn.find(w[0], w[1], new double[] {BASE + 10, BASE + 70, BASE + 11 * 300 - 50, BASE + 11 * 300 + 10});
        LastWorn.Result r11 = LastWorn.find(w[0], w[1], new double[] {BASE + 10, BASE + 70, BASE + 10 * 300 - 50, BASE + 10 * 300 + 10});
        check("12 daytime readings with paired heart rate: the rule applies, 11: it does not", r12.hrRule && !r11.hrRule, r12.hrRule + " " + r11.hrRule);
        check("MIN_DAY_READINGS is 12", LastWorn.MIN_DAY_READINGS == 12);
        // DAY_COVER: 100 warm readings, 70 of them within 30 minutes of a paired daytime heart rate, then 69
        double[][] hundred = tempsC(100 / 12.0, new double[] {0, 9, 33});
        check("100 readings", hundred[0].length == 100, hundred[0].length);
        for (int m : new int[] {56, 55}) {
            // heart rates at readings 0 to m cover readings 0 to m + 6, the pair at reading 99 covers 93 to 99
            double[] hr = new double[m + 3];
            for (int i = 0; i <= m; i++) hr[i] = BASE + i * 300 + 10;
            hr[m + 1] = BASE + 99 * 300 - 50;
            hr[m + 2] = BASE + 99 * 300 + 10;
            LastWorn.Result rc = LastWorn.find(hundred[0], hundred[1], hr);
            boolean want = m == 56;
            check((m + 14) + " % covered: the rule " + (want ? "applies" : "does not apply"), rc.hrRule == want, rc.hrRule);
        }

        // one HRV event reaching past uncounted warm readings is named once in the card, by event (not by time)
        double[] day = beats(new double[] {0, 10});
        int[] dayId = ids(day.length, 0);
        double[][] cc = tempsC(20, new double[] {0, 10, 34}, new double[] {14, 14.5, 30});
        double[] hrv = burst(BASE + 14 * H + 40 * 60, 6, 300); // 14:40 to 15:05, the warm readings end at 14:25
        LastWorn.Result ro = LastWorn.find(cc[0], cc[1], hrv, same(6, 1000), day, dayId);
        check("an HRV event past the warm readings: one lone heart rate, the stray is the same event", ro.state == LastWorn.OFF && ro.hrRule
                && ro.warmLoneHr == 1 && ro.strayHrAt == hrv[5] && ro.warmLoneHrAt == hrv[3] && ro.strayInWarm, ro.warmLoneHr + " " + ro.strayInWarm);
        LastWorn.Clock clock = new LastWorn.Clock() {
            @Override
            public String when(double unix) {
                return "T" + Math.round((unix - BASE) / 60);
            }
        };
        String to = LastWorn.text(ro, cc[0][cc[0].length - 1], clock);
        check("card text: the HRV event is named once", to.contains("had only a single heart rate (T895)") && !to.contains("A lone heart rate"), to);
        // a different event later is still a stray of its own
        double[] two = Arrays.copyOf(hrv, 7);
        two[6] = BASE + 17 * H;
        int[] twoId = same(7, 1000);
        twoId[6] = 1001;
        LastWorn.Result rt = LastWorn.find(cc[0], cc[1], two, twoId, day, dayId);
        String tt = LastWorn.text(rt, cc[0][cc[0].length - 1], clock);
        check("card text: a later stray event is named", !rt.strayInWarm && rt.strayHrAt == BASE + 17 * H
                && tt.endsWith(" A lone heart rate T1020 with no warm reading near it does not count."), tt);
    }

    /** Each sentence of the card (issues #74, #94), with a fixed time format: minutes after BASE. */
    static void lastWornText() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            LastWorn.Clock clock = new LastWorn.Clock() {
                @Override
                public String when(double unix) {
                    return "T" + Math.round((unix - BASE) / 60);
                }
            };
            double now = BASE + H;
            LastWorn.Result w = new LastWorn.Result();
            w.state = LastWorn.WORN;
            w.at = BASE + 600;
            w.newest = BASE + 600;
            w.skinC = 33.14;
            w.lastHr = BASE + 480;
            w.hasHr = true;
            w.confirmed = true;
            text("worn", w, now, clock, "The newest reading (T10) was from a finger (skin 33.1 °C, heart rate until T8).");
            w.lastHr = w.at = BASE + 1200;
            text("worn, heart rate newer than the skin reading", w, now, clock,
                    "The newest reading (T20) was from a finger (skin 33.1 °C T10, heart rate until T20).");
            w.confirmed = false;
            w.justOn = true;
            w.at = BASE + 600;
            text("just put on", w, now, clock, "The newest reading (T10) was from a finger (skin 33.1 °C, no heart rate stored yet, so it was probably just put on).");
            w.justOn = false;
            text("too little daytime heart rate", w, now, clock,
                    "The newest reading (T10) was from a finger (skin 33.1 °C, the ring stored too little daytime heart rate to confirm it).");
            w.hasHr = false;
            text("no heart rate at all", w, now, clock, "The newest reading (T10) was from a finger (skin 33.1 °C, no heart rate in the synced data to confirm it).");
            w.skinC = Double.NaN;
            text("cold fingers", w, now, clock, "The newest reading (T10) was from a finger (a heart rate, with cool skin).");

            LastWorn.Result u = new LastWorn.Result();
            u.state = LastWorn.UNSURE;
            u.at = BASE + 300;
            u.newest = BASE + 7200;
            u.skinC = 30;
            text("not sure", u, BASE + 7200, clock, "Not sure. It still read warm at its newest reading (30.0 °C, T120), but stored no heart rate after T5, "
                    + "so it was probably taken off around then and lay somewhere warm (the charger, under a duvet, in the sun).");

            LastWorn.Result o = new LastWorn.Result();
            o.state = LastWorn.OFF;
            o.at = BASE + 600;
            o.newest = BASE + 3000;
            o.roomC = 21.4;
            text("taken off", o, now, clock, "Taken off T10 (then it lay at room temperature, about 21 °C).");
            o.warmNoHrAt = BASE + 1800;
            o.warmNoHrC = 28;
            o.warmNoHrCount = 5;
            o.warmNoHrNow = true;
            text("warm again", o, now, clock, "Taken off T10 (then it lay at room temperature, about 21 °C). Since T30 it reads warm again (up to 28.0 °C) "
                    + "but without heart rate, so it is probably not on a finger (the charger, the sun or something warm).");
            o.warmNoHrNow = false;
            o.warmNoHrCount = 1;
            text("one warm reading without heart rate", o, now, clock,
                    "Taken off T10 (then it lay at room temperature, about 21 °C). A warm reading T30 (28.0 °C) had no heart rate, so it does not count.");
            o.warmNoHrCount = 3;
            o.strayHrAt = BASE + 2400;
            text("warm readings and a lone heart rate", o, now, clock, "Taken off T10 (then it lay at room temperature, about 21 °C). Warm readings from T30 "
                    + "(up to 28.0 °C) had no heart rate, so they do not count. A lone heart rate T40 with no warm reading near it does not count.");
            o.roomC = Double.NaN;
            o.warmNoHrCount = 0;
            o.strayHrAt = Double.NaN;
            text("taken off, no cool reading", o, now, clock, "Taken off T10.");

            LastWorn.Result nw = new LastWorn.Result();
            nw.state = LastWorn.OFF;
            nw.newest = BASE;
            nw.roomC = 20.6;
            text("not worn", nw, BASE + 600, clock, "Not worn in the last 7 days of synced data (it reads room temperature, about 21 °C).");
            nw.warmNoHrAt = BASE - 600;
            nw.warmNoHrC = 29;
            nw.warmNoHrCount = 3;
            nw.warmNoHrNow = true;
            text("not worn, warm now", nw, BASE + 600, clock, "Not worn in the last 7 days of synced data (it reads room temperature, about 21 °C). "
                    + "Since T-10 it reads warm (up to 29.0 °C) but without heart rate, so it is probably not on a finger (the charger, the sun or something warm).");

            text("no data", new LastWorn.Result(), now, clock, "No temperature readings yet. Sync once to fill this in.");
            // how old the newest reading is: from 6 hours on, hours below 2 days, then days
            text("6 hours old: no age line", o, o.newest + 6 * H, clock, "Taken off T10.");
            text("9 hours old", o, o.newest + 9 * H, clock, "Taken off T10.\nThe newest synced reading is 9 hours old, so this is how it was then.");
            text("10 days old", o, o.newest + 10 * 86400 + 5 * H, clock, "Taken off T10.\nThe newest synced reading is 10 days old, so this is how it was then.");
            w.lastHr = BASE + 20 * H;
            text("the age counts from the newest heart rate when it is newer", w, BASE + 25 * H, clock, "The newest reading (T10) was from a finger (a heart rate, with cool skin).");
            text("no age line without data", new LastWorn.Result(), BASE + 1000 * H, clock, "No temperature readings yet. Sync once to fill this in.");

            // the texts of the issue's cases, straight from find
            double[][] e = tempsC(20, new double[] {0, 10, 34}, new double[] {15, 15 + 1 / 12.0, 28});
            LastWorn.Result re = LastWorn.find(e[0], e[1], beats(new double[] {0, 10}));
            String te = LastWorn.text(re, e[0][e[0].length - 1], clock);
            check("E as text", te.startsWith("Taken off T" + Math.round((re.at - BASE) / 60) + " (then it lay at room temperature, about 21 °C). A warm reading T900 (28.0 °C) had no heart rate"), te);

            // review of 415e889: warm 57 h to 59 h with one daytime heart rate event at 58 h. The one event cannot
            // vouch for the warm readings, but the card must not say they had no heart rate.
            double[] day = beats(new double[] {0, 50});
            double[] dayL = Arrays.copyOf(day, day.length + 3);
            int[] dayLId = Arrays.copyOf(ids(day.length, 0), dayL.length);
            for (int k = 0; k < 3; k++) {
                dayL[day.length + k] = BASE + 58 * H + k * 1.92;
                dayLId[day.length + k] = 9000;
            }
            double[][] mid = tempsC(62, new double[] {0, 50, 34}, new double[] {57, 59, 30});
            LastWorn.Result rm = LastWorn.find(mid[0], mid[1], new double[0], null, dayL, dayLId);
            String tm = LastWorn.text(rm, mid[0][mid[0].length - 1], clock);
            check("one heart rate event inside warm readings: they do not count, off at 50 h", rm.state == LastWorn.OFF && rm.hrRule
                    && Math.abs(rm.at - (BASE + 50 * H)) <= 300 && rm.warmNoHrCount == 24 && !rm.warmNoHrNow && rm.warmLoneHr == 1
                    && rm.warmLoneHrAt == BASE + 58 * H + 2 * 1.92, rm.state + " " + (rm.at - BASE) / H + " " + rm.warmLoneHr);
            check("card text: warm readings with a single heart rate in the middle", tm.endsWith(" Warm readings from T3420 (up to 30.0 °C) had only a single heart rate (T3480), "
                    + "which is not enough to count them.") && !tm.contains("no heart rate") && !tm.contains("lone heart rate"), tm);
            double[][] end = tempsC(59, new double[] {0, 50, 34}, new double[] {57, 59, 30});
            LastWorn.Result rw = LastWorn.find(end[0], end[1], new double[0], null, dayL, dayLId);
            String tw = LastWorn.text(rw, end[0][end[0].length - 1], clock);
            check("card text: warm to the end with a single heart rate", rw.state == LastWorn.OFF && rw.warmNoHrNow && tw.endsWith(" Since T3420 it reads warm again "
                    + "(up to 30.0 °C) with only a single heart rate (T3480), which is not enough, so it is probably not on a finger (the charger, the sun or something warm).")
                    && !tw.contains("lone heart rate"), tw);
            LastWorn.Result o2 = new LastWorn.Result();
            o2.state = LastWorn.OFF;
            o2.at = BASE + 600;
            o2.newest = BASE + 3000;
            o2.warmNoHrAt = BASE + 1800;
            o2.warmNoHrC = 29;
            o2.warmNoHrCount = 1;
            o2.warmLoneHr = 1;
            o2.warmLoneHrAt = o2.strayHrAt = BASE + 1860;
            o2.strayInWarm = true;
            text("one warm reading with a single heart rate", o2, BASE + 3000, clock, "Taken off T10. A warm reading T30 (29.0 °C) had only a single heart rate (T31), which is not enough to count it.");
            o2.warmNoHrCount = 30;
            o2.warmLoneHr = 2;
            o2.strayHrAt = BASE + 2.5 * H;
            o2.strayInWarm = false;
            text("warm readings with two lone heart rates, and a stray after them", o2, BASE + 3 * H, clock, "Taken off T10. Warm readings from T30 (up to 29.0 °C) had only 2 lone heart rates, "
                    + "which is not enough to count them. A lone heart rate T150 with no warm reading near it does not count.");
        } finally {
            Locale.setDefault(before);
        }
    }

    static void text(String name, LastWorn.Result r, double now, LastWorn.Clock clock, String want) {
        String got = LastWorn.text(r, now, clock);
        check("card text: " + name, got.equals(want), got);
    }

    // ------------------------------------------------------------------ impossible signal values (issue #95)

    // ------------------------------------------------------------------ one screen (issue #191)

    static void lastWornBrief() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            LastWorn.Clock clock = new LastWorn.Clock() {
                @Override
                public String when(double unix) {
                    return "T" + Math.round((unix - BASE) / 60);
                }
            };
            LastWorn.Clock real = new LastWorn.Clock() {
                @Override
                public String when(double unix) {
                    return "yesterday at 22:14"; // the longest usual form apart from a weekday date
                }
            };
            double now = BASE + H;
            LastWorn.Result w = new LastWorn.Result();
            w.state = LastWorn.WORN;
            w.at = w.newest = BASE + 600;
            w.lastHr = BASE + 480;
            w.skinC = 33;
            w.hasHr = w.confirmed = true;
            briefIs("worn, confirmed", w, now, clock, "On a finger at the last reading, T10.");
            w.confirmed = false;
            briefIs("worn, not confirmed", w, now, clock, "Probably on a finger at the last reading, T10.");
            LastWorn.Result u = new LastWorn.Result();
            u.state = LastWorn.UNSURE;
            u.at = BASE + 300;
            u.newest = BASE + 3000;
            briefIs("not sure", u, now, clock, "Not sure: no heart rate since T5, probably taken off then.");
            LastWorn.Result o = new LastWorn.Result();
            o.state = LastWorn.OFF;
            o.at = BASE + 600;
            o.newest = BASE + 3000;
            o.roomC = 21;
            briefIs("taken off", o, now, clock, "Taken off T10.");
            o.warmNoHrNow = true;
            briefIs("taken off, warm now without heart rate", o, now, clock, "Taken off T10. Now it reads warm without a heart rate that counts, probably the charger or somewhere warm.");
            o.warmNoHrAt = BASE + 1200;
            o.warmLoneHr = 1;
            briefIs("taken off, warm since a time with a lone heart rate (review of #215)", o, now, clock,
                    "Taken off T10. Since T20 it reads warm without a heart rate that counts, probably the charger or somewhere warm.");
            o.warmNoHrAt = Double.NaN;
            o.warmLoneHr = 0;
            o.warmNoHrNow = false;
            o.at = Double.NaN;
            briefIs("not worn in 7 days", o, now, clock, "Not worn in the last 7 days of synced data.");
            briefIs("no data", new LastWorn.Result(), now, clock, "No temperature readings yet. Sync once to fill this in.");
            // old data says how old, as the full text does
            o.at = BASE + 600;
            briefIs("old data, hours", o, BASE + 3000 + 9 * H, clock, "Taken off T10 (synced data 9 hours old).");
            briefIs("old data, days", o, BASE + 3000 + 3.5 * 86400, clock, "Taken off T10 (synced data 3 days old).");
            briefIs("6 hours is not old yet", o, BASE + 3000 + 6 * H, clock, "Taken off T10.");
            w.lastHr = BASE + 20 * H;
            briefIs("a newer heart rate counts as newest", w, BASE + 21 * H, clock, "Probably on a finger at the last reading, T10.");
            // the strip shows one or two lines: the usual sentences stay short (about 45 characters a line on a phone)
            w.lastHr = BASE + 480;
            for (LastWorn.Result r : new LastWorn.Result[] {w, u, o, new LastWorn.Result()}) {
                String s = LastWorn.brief(r, BASE + 3000, real) + " Last heard yesterday at 22:14.";
                check("brief fits about two lines: " + s, s.length() <= 110, s.length());
            }
            check("brief and full text agree on the state", LastWorn.text(o, now, clock).startsWith(LastWorn.brief(o, now, clock).replace(".", "")));
        } finally {
            Locale.setDefault(before);
        }
    }

    static void briefIs(String name, LastWorn.Result r, double now, LastWorn.Clock clock, String want) {
        String got = LastWorn.brief(r, now, clock);
        check("brief: " + name, got.equals(want), got);
    }

    static void finderLayout() {
        // 360 x 800 dp with button navigation: about 728 dp below the status bar
        FinderLayout.Plan p = FinderLayout.plan(728, 1);
        check("800: fits without scrolling", p.fits && !p.compact, p.compact);
        check("800: the planned screen is exactly as tall as the screen", Math.abs(FinderLayout.total(p, 1) - 728) < 1e-6, FinderLayout.total(p, 1));
        check("800: a comfortable gauge and strip", p.gauge >= FinderLayout.GAUGE_MID && p.strip >= FinderLayout.STRIP_MID, p.gauge + " " + p.strip);
        check("800: the map is the main view", p.map >= 200 && p.map > p.gauge + p.strip, p.map);
        // 360 x 640: about 568 dp
        FinderLayout.Plan s = FinderLayout.plan(568, 1);
        check("640: fits, compact", s.fits && s.compact && s.pad == FinderLayout.PAD_COMPACT && s.statusSp == 12);
        check("640: nothing below its minimum", s.gauge >= FinderLayout.GAUGE_MIN && s.strip >= FinderLayout.STRIP_MIN && s.map >= FinderLayout.MAP_MIN && s.mapMin == FinderLayout.MAP_MIN);
        check("640: fits the height", FinderLayout.total(s, 1) <= 568 + 1e-6, FinderLayout.total(s, 1));
        check("640: the map first gets room", s.map >= 140 && s.strip == FinderLayout.STRIP_MIN, s.map + " " + s.strip);
        // the emulator of the screenshots (411 x 914 dp, about 842 dp)
        FinderLayout.Plan e = FinderLayout.plan(842, 1);
        check("emulator: full instruments and a big map", e.gauge == FinderLayout.GAUGE_MAX && e.strip == FinderLayout.STRIP_MAX && e.map > 300, e.map);
        // bigger screens never get smaller parts
        FinderLayout.Plan prev = FinderLayout.plan(300, 1);
        boolean grows = true;
        for (int h = 301; h <= 1400; h++) {
            FinderLayout.Plan q = FinderLayout.plan(h, 1);
            if (!(prev.compact && !q.compact) && (q.gauge < prev.gauge || q.strip < prev.strip || q.map < prev.map - 1e-9)) grows = false;
            if (q.fits && Math.abs(FinderLayout.total(q, 1) - h) > 1e-6) grows = false;
            prev = q;
        }
        check("taller screens never shrink a part, and every fitting plan fills the height exactly", grows);
        // larger fonts: the text takes more, the plan goes compact earlier, then the page scrolls
        FinderLayout.Plan f = FinderLayout.plan(728, 1.3);
        check("800 at font 1.3: still fits", f.fits && f.map >= FinderLayout.MAP_MIN, f.map);
        check("font 1.3 leaves less map than font 1", f.map < p.map);
        FinderLayout.Plan big = FinderLayout.plan(568, 2);
        check("640 at font 2: does not fit, the page scrolls, minimums stay", !big.fits && big.compact && big.map == FinderLayout.MAP_MIN
                && big.strip == FinderLayout.STRIP_MIN, big.gauge + " " + big.strip + " " + big.map);
        check("640 at font 2: the gauge only grows within its text's height", FinderLayout.total(big, 2) <= FinderLayout.total(FinderLayout.plan(-1, 2), 2) + 1e-6);
        FinderLayout.Plan tiny = FinderLayout.plan(-50, 1);
        check("no height yet: minimums, never negative", !tiny.fits && tiny.map == FinderLayout.MAP_MIN && tiny.gauge > 0);
        check("a bad font scale counts as 1", FinderLayout.plan(728, 0).same(p) && FinderLayout.plan(728, Double.NaN).same(p));
        // the gauge row is at least as tall as its text, so a gauge below the text costs nothing
        FinderLayout.Plan g = FinderLayout.plan(728, 1.6);
        check("font 1.6: the gauge grows into the text's height for free", g.gauge >= Math.min(FinderLayout.GAUGE_MID, 74 * 1.6) || !g.fits, g.gauge);
        check("same plan", p.same(FinderLayout.plan(728, 1)) && !p.same(s) && !p.same(null));
    }

    static void impossibleValues() {
        check("plausible range", Proximity.plausible(-1) && Proximity.plausible(-50) && Proximity.plausible(-120)
                && !Proximity.plausible(127) && !Proximity.plausible(0) && !Proximity.plausible(22) && !Proximity.plausible(-121)
                && !Proximity.plausible(-127) && !Proximity.plausible(Double.NaN));
        // the issue's sequence: before the fix the meter jumped to "Very close" with the fastest clicks
        Proximity p = new Proximity();
        long t = 0;
        double lo = 0, hi = -200;
        boolean close = false;
        for (double v : new double[] {-75, -76, 127, -74, 127, 127, -75}) {
            p.add(t += 200, v);
            lo = Math.min(lo, p.value());
            hi = Math.max(hi, p.value());
            close |= p.band() == Proximity.CLOSE;
        }
        check("127 among real readings: the value stays between -77 and -73 dBm", lo >= -77 && hi <= -73, lo + " to " + hi);
        check("127 among real readings: never very close, clicks as slow as at -73 dBm", !close && Proximity.clickIntervalMs(p.value()) >= Proximity.clickIntervalMs(-73), Proximity.clickIntervalMs(p.value()));
        check("an ignored reading keeps the time of the last real one", p.sinceLast(t) == 0 && p.sinceLast(t + 50) == 50);
        // a ring that only delivers 127 is not heard
        Proximity only = new Proximity();
        for (long u = 0; u < 12000; u += 200) {
            only.add(u, 127);
            only.tick(u);
        }
        check("only 127 for 12 s: not heard", only.band() == Proximity.NOT_HEARD && Double.isNaN(only.value()) && only.sinceLast(12000) == -1);
        Proximity late = new Proximity();
        late.add(0, -70);
        for (long u = 200; u <= 10000; u += 200) late.add(u, 127);
        late.tick(10000);
        check("only 127 after a real reading: not heard after 10 s", late.band() == Proximity.NOT_HEARD, late.value());
        // issue #125: when the phone receives the ring but only impossible values, the label says so
        check("label: no usable signal", Proximity.label(Proximity.NOT_HEARD, true).equals("No usable signal")
                && Proximity.label(Proximity.NOT_HEARD, false).equals("Not heard for 10 seconds"));
        check("label: a heard band is not changed by the flag", Proximity.label(Proximity.ROOM, true).equals("In this room")
                && Proximity.label(Proximity.FAR, true).equals(Proximity.label(Proximity.FAR)));

        // a walk with a clear circle keeps it when one 127 arrives (before the fix: no circle, +22 dBm)
        Random r = new Random(95);
        SearchWalk w = new SearchWalk().stepLength(0.5);
        t = 0;
        for (int leg = 0; leg < 4; leg++) {
            for (int i = 0; i < 8; i++) {
                for (int k = 0; k < 8; k++) {
                    w.heading(t, leg * 90, true);
                    double d = Math.sqrt((2.6 - w.x()) * (2.6 - w.x()) + (2.2 - w.y()) * (2.2 - w.y()) + 0.49);
                    w.rssi(t + 10, -60 - 25 * Math.log10(d) + r.nextGaussian() * 3);
                    t += 150;
                }
                w.steps(t, 1);
            }
        }
        SearchWalk.Spot before = w.locate();
        int n = w.samples().size();
        w.heading(t + 50, 0, true);
        boolean counted = w.rssi(t + 60, 127);
        SearchWalk.Spot after = w.locate();
        check("walk: a clear circle near the ring", before != null && before.clear && Math.hypot(before.x - 2.6, before.y - 2.2) < 1.5,
                before == null ? null : before.x + "," + before.y + " clear " + before.clear);
        check("walk: 127 is not recorded", !counted && w.samples().size() == n && !w.rssi(t + 70, 0) && !w.rssi(t + 80, Double.NaN));
        check("walk: the same circle after a 127", after != null && after.clear && after.x == before.x && after.y == before.y && after.radius == before.radius);
    }

    // ------------------------------------------------------------------ setup picks the closest ring (issue #105)

    static void strongestRing() {
        check("strongest: the partner's ring reporting 127 does not win", Proximity.strongest(new int[] {-60, 127}) == 0
                && Proximity.strongest(new int[] {127, -60}) == 1);
        check("strongest: 0 dBm and -127 do not win either", Proximity.strongest(new int[] {0, -80, -127}) == 1
                && Proximity.strongest(new int[] {-121, -90}) == 1);
        check("strongest: the closer of two real readings", Proximity.strongest(new int[] {-80, -55, -70}) == 1
                && Proximity.strongest(new int[] {-1, -120}) == 0);
        check("strongest: only impossible values, the highest raw one as before", Proximity.strongest(new int[] {0, 127, -127}) == 1
                && Proximity.strongest(new int[] {127}) == 0);
        check("strongest: one ring and none", Proximity.strongest(new int[] {-75}) == 0 && Proximity.strongest(new int[0]) == -1);
        check("strongest: a tie keeps the first", Proximity.strongest(new int[] {-70, -70}) == 0);
        check("kept reading: a real one is not replaced by 127", !Proximity.replaces(-70, 127) && !Proximity.replaces(-70, 0));
        check("kept reading: a real one replaces anything, 127 replaces 127", Proximity.replaces(-70, -80) && Proximity.replaces(127, -80)
                && Proximity.replaces(127, 127));
    }

    // ------------------------------------------------------------------ the permission dialog (issue #140)

    static void permissionAsk() {
        final int G = PermissionAsk.GRANTED, D = PermissionAsk.DENIED;
        String[] need = {"SCAN", "CONNECT"};
        String[] asked = {"SCAN", "CONNECT", "NOTIFY"};
        boolean[] none = {false, false}, both = {true, true};
        // the issue's case: never refused, the dialog closed with Back (denied, no explanation)
        check("#140: dialog closed without choosing, never refused: Android still asks",
                !PermissionAsk.blocked(asked, new int[] {D, D, D}, need, none, false));
        check("#140: an interrupted request (empty answer): Android still asks",
                !PermissionAsk.blocked(new String[0], new int[0], need, none, false) && !PermissionAsk.blocked(new String[0], new int[0], need, none, true)
                        && !PermissionAsk.blocked(null, null, need, none, true));
        check("#140: refused once (Android explains it now): Android still asks", !PermissionAsk.blocked(asked, new int[] {D, D, G}, need, both, false)
                && !PermissionAsk.blocked(asked, new int[] {D, D, G}, need, both, true));
        check("#140: refused again after an earlier refusal, no explanation: blocked", PermissionAsk.blocked(asked, new int[] {D, D, G}, need, none, true));
        check("#140: granted after an earlier refusal: not blocked", !PermissionAsk.blocked(asked, new int[] {G, G, G}, need, none, true));
        check("#140: only the notification refused: not blocked", !PermissionAsk.blocked(asked, new int[] {G, G, D}, need, none, true));
        check("#140: one needed permission refused without explanation: blocked", PermissionAsk.blocked(asked, new int[] {G, D, G}, need, none, true));
        check("#140: an answer shorter than the request counts only what it holds", !PermissionAsk.blocked(asked, new int[] {G}, need, none, true)
                && PermissionAsk.blocked(asked, new int[] {D}, need, none, true));
        check("#140: an explanation for one of them means it was refused before", PermissionAsk.deniedOnce(new boolean[] {false, true})
                && !PermissionAsk.deniedOnce(none) && !PermissionAsk.deniedOnce(new boolean[0]));
        check("#140: a quiet refusal is denied without explanation, Back or interrupted is not",
                PermissionAsk.quietDenial(asked, new int[] {D, D, D}, need, none) && !PermissionAsk.quietDenial(asked, new int[] {D, D, D}, need, both)
                        && !PermissionAsk.quietDenial(new String[0], new int[0], need, none));
        // the screen's sequence, through answer() as FinderActivity calls it: Back once still asks
        int[] dd = {D, D, D};
        PermissionAsk.Answer a = PermissionAsk.answer(false, asked, dd, need, none, false, false);
        check("#140 sequence: Back once, Allow stays", !a.blocked && a.quiet && !a.denied);
        // refused explicitly: Android explains it now, the app remembers that
        a = PermissionAsk.answer(false, asked, dd, need, both, a.denied, a.quiet);
        check("#140 sequence: a first real no, Allow stays, the refusal is remembered", !a.blocked && !a.quiet && a.denied);
        // Back on the second dialog: Android still explains it, still asks
        a = PermissionAsk.answer(false, asked, dd, need, both, a.denied, a.quiet);
        check("#140 sequence: Back after a real no, Allow stays", !a.blocked && a.denied);
        // refused again: no explanation any more, Android no longer asks
        PermissionAsk.Answer no2 = PermissionAsk.answer(false, asked, dd, need, none, a.denied, a.quiet);
        check("#140 sequence: a second real no, open the app's settings", no2.blocked && no2.denied);
        // granted in the app's settings: the refusal is forgotten (onResume calls remember)
        boolean stored = PermissionAsk.remember(true, none, no2.denied);
        check("#140 sequence: granted later, the refusal is forgotten", !stored);
        // Android resets the unused app's permission; the dialog is closed with Back: Allow stays (the bug would return otherwise)
        a = PermissionAsk.answer(false, asked, dd, need, none, PermissionAsk.remember(false, none, stored), false);
        check("#140 sequence: granted, then reset by Android, then Back: Allow stays", !a.blocked && a.quiet && !a.denied);
        // a grant in the answer itself clears everything
        a = PermissionAsk.answer(true, asked, new int[] {G, G, G}, need, none, true, true);
        check("#140: granted: not blocked, nothing remembered", !a.blocked && !a.quiet && !a.denied);
        // a phone refused twice before this version (nothing remembered): the first Allow shows no dialog and keeps
        // Allow, the second leads to the app's settings
        a = PermissionAsk.answer(false, asked, dd, need, none, false, false);
        PermissionAsk.Answer a2 = PermissionAsk.answer(false, asked, dd, need, none, a.denied, a.quiet);
        check("#140: blocked before this version: first tap Allow stays, second tap open the app's settings", !a.blocked && a2.blocked);
        check("#140: an interrupted request in between does not count as quiet", !PermissionAsk.answer(false, new String[0], new int[0], need, none, false, false).quiet);
    }

    // ------------------------------------------------------------------ scan retry (issue #96)

    static void scanRetry() {
        ScanRetry s = new ScanRetry();
        check("retry: fresh, starts when Bluetooth is on", s.shouldStart(0, true, false) && !s.waiting(0) && !s.shouldStart(0, false, false) && !s.shouldStart(0, true, true));
        check("retry: a failure waits 30 s", s.failed(1000, 6) == 30000 && s.error() == 6 && s.waiting(1000) && !s.shouldStart(2000, true, false));
        // the issue's case: the retry 30 s later finds Bluetooth off and gives up
        s.blocked();
        check("retry: Bluetooth off at the retry ends the wait (the screen says Bluetooth is off)", !s.waiting(31000) && !s.shouldStart(31250, false, false));
        check("retry: Bluetooth on again, the scan starts", s.shouldStart(40000, true, false));
        s.failed(50000, 2);
        check("retry: still waiting after 34.9 s", s.waiting(84900) && s.error() == 2);
        check("retry: the message never stays longer than 35 s", !s.waiting(85000) && s.shouldStart(85000, true, false));
        s.failed(90000, 6);
        s.started();
        check("retry: a started scan clears it", !s.waiting(90100) && !s.shouldStart(90100, true, true));
        ScanRetry z = new ScanRetry();
        z.failed(0, 6);
        check("retry: a failure at clock 0 still waits", z.waiting(0) && z.waiting(34999) && !z.waiting(35000));
    }

    // ------------------------------------------------------------------ on a finger now (issue #192)

    static byte[] temp(double c) {
        int v = (int) Math.round(c * 100);
        return new byte[] {(byte) v, (byte) (v >> 8)};
    }

    static byte[] ibi(int ms) {
        return new byte[] {(byte) (ms >> 3), (byte) ((ms & 7) | (1 << 3))};
    }

    static final LastWorn.Clock AT = new LastWorn.Clock() {
        @Override
        public String when(double unix) {
            return "today at 14:02";
        }
    };

    static void fingerNow() {
        // the rule at its borders
        check("finger: warm with a heart rate is a finger", FingerNow.of(36.1, 60, 64, 120, 0).state() == FingerNow.ON);
        check("finger: 27 °C exactly counts as warm", FingerNow.of(27.0, 60, 64, 60, 0).state() == FingerNow.ON);
        check("finger: warm, heart rate 20 minutes old near a temperature 15 minutes old counts", FingerNow.of(33, 900, 64, FingerNow.FRESH_S, 0).state() == FingerNow.ON);
        check("finger: warm, heart rate older than 20 minutes is finger or charger", FingerNow.of(33, 1000, 64, FingerNow.FRESH_S + 1, 0).state() == FingerNow.WARM);
        // review of #245: taken off at 13:58 (last heart rate 13:57), on a warm charger, asked at 14:12
        check("finger: warm now, heart rate 15 minutes before it, does not count (charger)", FingerNow.of(33, 0, 64, 900, 0).state() == FingerNow.WARM);
        check("finger: heart rate exactly 5 minutes from the warm reading counts", FingerNow.of(33, 600, 64, 900, 0).state() == FingerNow.ON);
        check("finger: heart rate 5 minutes and a second from the warm reading does not", FingerNow.of(33, 599, 64, 900, 0).state() == FingerNow.WARM);
        check("finger: a heart rate just now counts for an older warm reading", FingerNow.of(33, 900, 64, 200, 0).state() == FingerNow.ON);
        FingerNow charger = FingerNow.of(33.2, 0, 64, 900, 0);
        charger.charging = 1;
        check("finger: warm, no heart rate near, battery rising: the charger", charger.state() == FingerNow.CHARGER
                && charger.brief().equals("Warm (" + String.format(Locale.getDefault(), "%.1f", 33.2) + " °C), battery rising: probably the charger."), charger.brief());
        check("finger: the charger detail says why", charger.detail(AT).contains("the battery went up since the read before, so it is probably on the charger"));
        FingerNow worn = FingerNow.of(33.2, 0, 64, 60, 0);
        worn.charging = 1;
        check("finger: a heart rate near the warmth wins over a rising battery (charged earlier)", worn.state() == FingerNow.ON);
        FingerNow falling = FingerNow.of(33.2, 0, Double.NaN, Double.NaN, 0);
        falling.charging = 0;
        check("finger: battery going down leaves warm open", falling.state() == FingerNow.WARM);
        check("finger: warm without any heart rate is finger or charger", FingerNow.of(33.2, 60, Double.NaN, Double.NaN, 0).state() == FingerNow.WARM);
        check("finger: cool without a heart rate is not on a finger", FingerNow.of(24, 60, Double.NaN, Double.NaN, 0).state() == FingerNow.OFF);
        check("finger: 26.9 °C is cool", FingerNow.of(26.9, 60, Double.NaN, Double.NaN, 0).state() == FingerNow.OFF);
        check("finger: cool with a heart rate 5 minutes old is a finger (cold hands)", FingerNow.of(25.3, 60, 60, 300, 0).state() == FingerNow.ON);
        // review of #245: cold hands outdoors are not a confident "not on a finger"
        FingerNow cool = FingerNow.of(25.0, 60, 60, 9 * 60, 0);
        check("finger: cool with a heart rate 9 minutes old stays open", cool.state() == FingerNow.COOL
                && cool.brief().equals("Cool (" + String.format(Locale.getDefault(), "%.1f", 25.0) + " °C), heart rate 9 minutes ago: cold hands or just taken off."), cool.brief());
        check("finger: the open cool case says Make it blink tells", cool.detail(AT).contains("cold hands, or it was taken off since. Make it blink tells"));
        check("finger: cool with a heart rate 20 minutes old stays open", FingerNow.of(25.3, 60, 60, FingerNow.FRESH_S, 0).state() == FingerNow.COOL);
        check("finger: cool with a heart rate older than 20 minutes is not on a finger", FingerNow.of(25.3, 60, 60, FingerNow.FRESH_S + 1, 0).state() == FingerNow.OFF);
        check("finger: the off detail names the 20 minutes", FingerNow.of(24, 60, Double.NaN, Double.NaN, 0).detail(AT).contains("without any heart rate in the last 20 minutes"));
        check("finger: temperature 21 minutes old does not count", FingerNow.of(36, 21 * 60, Double.NaN, Double.NaN, 0).state() == FingerNow.NO_READING);
        check("finger: old temperature but a heart rate just now is a finger", FingerNow.of(36, 3 * 3600, 70, 30, 0).state() == FingerNow.ON);
        check("finger: nothing at all tells nothing", new FingerNow().state() == FingerNow.NO_READING);
        check("finger: a negative age counts as now", FingerNow.of(36, -5, 64, -1, 0).skinAge == 0 && FingerNow.of(36, -5, 64, -1, 0).hrAge == 0);

        // the strip's line, short enough to share the strip with the chip
        String on = FingerNow.of(36.1, 60, 64, 70, 0).brief();
        check("finger: on, line", on.equals("On a finger now (" + String.format(Locale.getDefault(), "%.1f", 36.1) + " °C, heart rate 64)."), on);
        check("finger: cool on, line", FingerNow.of(25.3, 60, 60, 30, 0).brief().startsWith("On a finger now (heart rate 60, cool skin "));
        check("finger: heart rate only, line", FingerNow.of(Double.NaN, Double.NaN, 70, 30, 0).brief().equals("On a finger now (heart rate 70)."));
        check("finger: off, line", FingerNow.of(24, 60, Double.NaN, Double.NaN, 0).brief().startsWith("Not on a finger now ("));
        check("finger: warm, line", FingerNow.of(33.2, 60, Double.NaN, Double.NaN, 0).brief().endsWith("no heart rate: finger or charger."));
        check("finger: nothing, line", new FingerNow().brief().equals("No reading from the last 20 minutes to tell."));
        double[][] cases = {{36.1, 60, 64, 70}, {25.3, 60, 60, 30}, {24, 60, Double.NaN, Double.NaN}, {33.2, 60, Double.NaN, Double.NaN}, {Double.NaN, Double.NaN, 70, 30},
                {25.3, 60, 60, 1199}};
        for (double[] c : cases) {
            FingerNow fn = FingerNow.of(c[0], c[1], c[2], c[3], 0);
            String b = fn.brief();
            // the answers fit two short lines next to the chip; the open cool case may take three
            check("finger: line is short (" + b + ")", b.length() <= (fn.state() == FingerNow.COOL ? 72 : 54), b.length());
            check("finger: no dash as punctuation (" + b + ")", !b.contains(" - ") && !b.contains("—"));
        }

        // the evidence
        String d = FingerNow.of(36.1, 50, 64, 200, 0).detail(AT);
        check("finger: detail names when and both readings with their age", d.startsWith("Asked the ring today at 14:02. Newest skin temperature ")
                && d.contains("(just before)") && d.contains("newest heart rate 64 bpm (3 minutes before)") && d.contains("27 °C or more"), d);
        check("finger: detail without heart rate", FingerNow.of(24, 90, Double.NaN, Double.NaN, 0).detail(AT).contains("(a minute before), no heart rate. Under 27 °C"));
        check("finger: detail of warm names the charger and the blink", FingerNow.of(33, 60, Double.NaN, Double.NaN, 0).detail(AT).contains("or the charger. Make it blink measures one."));
        check("finger: detail of cool skin names cold hands", FingerNow.of(25, 60, 60, 60, 0).detail(AT).contains("cold hands"));
        check("finger: detail of nothing", new FingerNow().detail(AT).contains("No skin temperature, no heart rate. The ring stored no temperature"));
        check("finger: hours ago", FingerNow.of(36, 60, 64, 3 * 3600, 0).detail(AT).contains("(3 hours before)"));
        FingerNow demo = FingerNow.demo(0);
        check("finger: the demo ring lies in the room (the demo walk finds it there)", demo.state() == FingerNow.OFF && demo.demo
                && demo.detail(AT).endsWith("In the demo this answer is made up."));

        // reading the download: the newest event is now, ages in ring time (deciseconds)
        FingerNow.Reader r = new FingerNow.Reader();
        r.add(new RingEvent(EventCodec.TEMP_EVENT, 10000, temp(35.5)));
        r.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 10600, ibi(1000)));
        r.add(new RingEvent(EventCodec.TEMP_EVENT, 11000, temp(36.2)));
        r.add(new RingEvent(EventCodec.MOTION_EVENT, 11600, new byte[] {1, 2}));
        FingerNow a = r.result(5000, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: newest temperature and its age", a.skinC == 36.2 && Math.abs(a.skinAge - 60) < 1e-9, a.skinC + " " + a.skinAge);
        check("reader: green beat at its start, 60000 / interval", Math.abs(a.hr - 60) < 1e-9 && Math.abs(a.hrAge - 100) < 1e-9, a.hr + " " + a.hrAge);
        check("reader: answer is a finger", a.state() == FingerNow.ON && a.at == 5000 && r.events() == 4);
        // an unclean beat or an impossible interval does not count; an older temperature after a newer one does not win
        FingerNow.Reader q = new FingerNow.Reader();
        q.add(new RingEvent(EventCodec.TEMP_EVENT, 5000, temp(24)));
        q.add(new RingEvent(EventCodec.TEMP_EVENT, 4990, temp(33)));
        q.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 5000, new byte[] {(byte) (1000 >> 3), (byte) (1000 & 7)}));
        q.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 5000, ibi(250)));
        q.add(new RingEvent(EventCodec.TEMP_EVENT, 5000, new byte[] {1}));
        FingerNow b = q.result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: the newest temperature by ring time, bad values and beats left out", b.skinC == 24 && Double.isNaN(b.hr) && b.state() == FingerNow.OFF, b.skinC + " " + b.hr);
        // HRV values 5 minutes apart and always on heart rate 1.92 s apart, implausible values skipped
        FingerNow.Reader h = new FingerNow.Reader();
        h.add(new RingEvent(EventCodec.HRV_EVENT, 0, new byte[] {55, 40, 54, 41, (byte) 250, 10}));
        h.add(new RingEvent(EventCodec.TEMP_EVENT, 6000, temp(35)));
        FingerNow hv = h.result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: HRV value 2 counts at start + 300 s (value 3 is impossible)", hv.hr == 54 && Math.abs(hv.hrAge - 300) < 1e-9, hv.hr + " " + hv.hrAge);
        FingerNow.Reader ao = new FingerNow.Reader();
        ao.add(new RingEvent(EventCodec.AOHR_EVENT, 1000, new byte[] {0, 0, 3, 70, 1, 72, 1, 10, 1}));
        ao.add(new RingEvent(EventCodec.TEMP_EVENT, 1100, temp(34)));
        FingerNow av = ao.result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: always on value 2 at start + 1.92 s, value 3 impossible", av.hr == 72 && Math.abs(av.hrAge - (100 - 19.2) / 10) < 1e-9, av.hr + " " + av.hrAge);
        // a reboot inside the download: the counter starts again, older readings have no common clock and go
        FingerNow.Reader rb = new FingerNow.Reader();
        rb.add(new RingEvent(EventCodec.TEMP_EVENT, 900000, temp(36)));
        rb.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 900000, ibi(900)));
        rb.add(new RingEvent(EventCodec.TEMP_EVENT, 300, temp(23)));
        FingerNow rbv = rb.result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: after a reboot only the new boot counts", rbv.skinC == 23 && Double.isNaN(rbv.hr) && rbv.state() == FingerNow.OFF, rbv.skinC + " " + rbv.hr);
        FingerNow.Reader small = new FingerNow.Reader();
        small.add(new RingEvent(EventCodec.TEMP_EVENT, 5000, temp(36)));
        small.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 4500, ibi(900)));
        check("reader: a small step back is not a reboot", small.result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN).state() == FingerNow.ON);
        // nothing new downloaded: the stored, dated readings answer
        FingerNow none = new FingerNow.Reader().result(10000, 35.0, 10000 - 120, 66, 10000 - 60);
        check("reader: nothing new, the stored readings with their ages", none.skinC == 35 && none.skinAge == 120 && none.hr == 66 && none.hrAge == 60 && none.state() == FingerNow.ON);
        FingerNow.Reader onlyTemp = new FingerNow.Reader();
        onlyTemp.add(new RingEvent(EventCodec.TEMP_EVENT, 100, temp(34)));
        FingerNow mixed = onlyTemp.result(10000, 20, 9000, 66, 10000 - 200);
        check("reader: what the download lacks comes from the stored readings", mixed.skinC == 34 && mixed.hr == 66 && mixed.hrAge == 200 && mixed.state() == FingerNow.ON);
        check("reader: no stored readings either", new FingerNow.Reader().result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN).state() == FingerNow.NO_READING);
        // review of #245: a reader kept for the next ask (its events are stored undated) ages by the phone time since
        FingerNow.Reader kept = new FingerNow.Reader();
        kept.add(new RingEvent(EventCodec.TEMP_EVENT, 20000, temp(35)));
        kept.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 20000, ibi(1000)));
        kept.mark(1000);
        FingerNow first = kept.result(1000, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: first ask, just now", first.skinAge == 0 && first.hrAge == 0 && first.state() == FingerNow.ON);
        FingerNow second = kept.result(1300, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: second ask without new events, 5 minutes older", second.skinAge == 300 && second.hrAge == 300 && second.skinC == 35, second.skinAge);
        FingerNow third = kept.result(1000 + 1500, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: 25 minutes later the kept readings no longer count", third.state() == FingerNow.NO_READING);
        kept.add(new RingEvent(EventCodec.TEMP_EVENT, 20000 + 6000, temp(24)));
        kept.mark(1600);
        FingerNow fourth = kept.result(1660, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check("reader: new events of the next ask age from their own download", fourth.skinC == 24 && fourth.skinAge == 60 && fourth.hrAge == 660, fourth.skinAge + " " + fourth.hrAge);
        FingerNow synced = kept.result(1660, 33, 1650, 70, 1655);
        check("reader: newer stored readings (a sync since) win over kept ones", synced.skinC == 33 && synced.skinAge == 10 && synced.hr == 70 && synced.hrAge == 5);
        FingerNow older = kept.result(1660, 33, 1000, 70, 900);
        check("reader: older stored readings do not", older.skinC == 24 && older.hr == 60);
    }

    static FingerCheck.Now now(long ms) {
        FingerCheck.Now n = new FingerCheck.Now();
        n.ms = ms;
        n.paired = n.bluetoothOn = n.heard = true;
        return n;
    }

    static void fingerCheck() {
        FingerCheck f = new FingerCheck();
        eq("check: idle at first", f.phase(), FingerCheck.Phase.IDLE);
        eq("check: the stored line before any answer", f.brief("Taken off yesterday at 22:14."), "Taken off yesterday at 22:14.");
        check("check: no chip while not heard", f.chip(0, true, false, false) == null && f.action(true, false, false) == FingerCheck.Action.NONE);
        eq("check: heard, the chip offers the blink", f.chip(0, true, true, false), "Make it blink");
        check("check: no detail before an answer", f.detail(AT) == null);
        // when it asks by itself
        FingerCheck.Now n = now(1000);
        n.demo = true;
        check("check: never in demo mode", !f.autoAsk(n));
        n = now(1000);
        n.paired = false;
        check("check: not without a paired ring", !f.autoAsk(n));
        check("check: no chip without a paired ring", f.chip(0, false, true, false) == null);
        n = now(1000);
        n.bluetoothOn = false;
        check("check: not with Bluetooth off", !f.autoAsk(n));
        n = now(1000);
        n.heard = false;
        check("check: not before the ring is heard", !f.autoAsk(n));
        n = now(1000);
        n.recording = true;
        check("check: not during a walk", !f.autoAsk(n));
        n = now(1000);
        n.ringHeld = true;
        check("check: not while another task holds the ring", !f.autoAsk(n) && f.phase() == FingerCheck.Phase.WAITING);
        eq("check: says it waits for the ring to be free", f.brief("x"), FingerCheck.WAIT_RING);
        check("check: nothing to tap while waiting", f.chip(0, true, true, false) == null);
        n = now(1000);
        n.longRun = true;
        check("check: not during a long re analysis", !f.autoAsk(n));
        eq("check: says it waits for the re analysis", f.brief("x"), FingerCheck.WAIT_ANALYSIS);
        check("check: the waiting reason in the detail", f.detail(AT).startsWith(FingerCheck.WAIT_ANALYSIS));
        check("check: asks once the ring is free", f.autoAsk(now(1000)));
        // asking
        f.start(1000, false);
        check("check: asking holds the ring", f.holding() && f.phase() == FingerCheck.Phase.ASKING);
        check("check: no second ask while asking", !f.autoAsk(now(1100)));
        eq("check: asking line", f.brief("x"), FingerCheck.ASKING);
        f.downloading(500);
        eq("check: a small download shows no KB", f.brief("x"), FingerCheck.ASKING);
        f.downloading(123 * 1024);
        eq("check: a long download shows what is left", f.brief("x"), "Asking the ring if it is on a finger now, 123 KB left.");
        eq("check: stop while asking", f.chip(0, true, true, false), "Stop");
        eq("check: the instruments say hot and cold pauses", f.blind(false), FingerCheck.BLIND_ASKING);
        f.answered(5000, null);
        check("check: no answer keeps asking", f.answer() == null);
        FingerNow ans = FingerNow.of(36.1, 60, 64, 70, 0);
        f.answered(5000, ans);
        eq("check: the answer as soon as it is there", f.brief("x"), ans.brief());
        f.done(6000, null);
        eq("check: answered", f.phase(), FingerCheck.Phase.ANSWERED);
        check("check: hot and cold back", !f.holding() && f.blind(false) == null);
        eq("check: answered, the chip offers the blink", f.chip(0, true, false, false), "Make it blink");
        check("check: detail is the evidence then the blink note", f.detail(AT).startsWith("Asked the ring today at 14:02.") && f.detail(AT).endsWith(FingerCheck.BLINK_NOTE));
        check("check: one automatic ask per visit", !f.autoAsk(now(9000)));
        // the blink
        f.start(10000, true);
        eq("check: while connecting for the blink the answer stays", f.brief("x"), ans.brief());
        f.blinking(11000);
        eq("check: blinking", f.phase(), FingerCheck.Phase.BLINKING);
        eq("check: countdown at the start", f.chip(11000, true, true, false), "Stop, 30 s");
        eq("check: countdown rounds up", f.chip(11000 + 5500, true, true, false), "Stop, 25 s");
        check("check: not over at 29.9 s", !f.blinkOver(11000 + FingerCheck.BLINK_MS - 1));
        check("check: over at 30 s", f.blinkOver(11000 + FingerCheck.BLINK_MS) && f.blinkLeft(11000 + FingerCheck.BLINK_MS + 5000) == 0);
        eq("check: the instruments say hot and cold is blind", f.blind(false), FingerCheck.BLIND_BLINKING);
        eq("check: the demo says nothing blinks", f.blind(true), FingerCheck.BLIND_DEMO);
        FingerNow later = FingerNow.of(35.8, 30, 66, 4, 0);
        f.answered(20000, later);
        eq("check: a heart rate measured while blinking updates the line", f.brief("x"), later.brief());
        f.done(41000, null);
        check("check: after the blink, answered again", f.phase() == FingerCheck.Phase.ANSWERED && f.blinkLeft(41000) == 0);
        // stop by the person
        f.start(50000, true);
        f.blinking(52000);
        f.stopped(53000);
        check("check: stop ends the blink, the answer stays", f.phase() == FingerCheck.Phase.ANSWERED && f.answer() == later);
        FingerCheck g = new FingerCheck();
        g.start(0, false);
        g.stopped(100);
        check("check: stop before an answer", g.phase() == FingerCheck.Phase.FAILED && g.brief("x").equals(FingerCheck.STOPPED));
        eq("check: then the chip asks again", g.chip(0, true, true, false), "Ask again");
        eq("check: the detail says it was stopped", g.detail(AT).split("\n")[0], FingerCheck.STOPPED);
        g.stopped(200);
        eq("check: a second stop changes nothing", g.phase(), FingerCheck.Phase.FAILED);
        // failures
        FingerCheck e = new FingerCheck();
        e.start(0, false);
        e.done(1000, "No ring found. Put it on its charger next to the phone");
        check("check: a failure waits for Ask again", e.phase() == FingerCheck.Phase.FAILED && e.brief("x").equals(FingerCheck.NOT_ASKED) && !e.autoAsk(now(99999)));
        check("check: the plain reason in the detail", e.detail(AT).startsWith("Could not ask the ring. Your ring was not found."), e.detail(AT));
        e.start(2000, false);
        e.done(3000, null);
        check("check: worked without an answer counts as not asked", e.phase() == FingerCheck.Phase.FAILED && e.brief("x").equals(FingerCheck.NOT_ASKED));
        check("check: no detail line for that", e.detail(AT) == null);
        FingerCheck busy = new FingerCheck();
        busy.start(0, false);
        busy.done(1000, "The ring is busy with another task");
        check("check: refused by a busy ring, it waits", busy.phase() == FingerCheck.Phase.WAITING && busy.brief("x").equals(FingerCheck.WAIT_RING));
        check("check: and asks again, not before 5 s", !busy.autoAsk(now(1000 + FingerCheck.RETRY_MS - 1)) && busy.autoAsk(now(1000 + FingerCheck.RETRY_MS)));
        FingerCheck re = new FingerCheck();
        re.start(0, false);
        re.done(1000, SyncError.REANALYSING);
        eq("check: refused during a re analysis, it waits for it", re.brief("x"), FingerCheck.WAIT_ANALYSIS);
        FingerCheck bb = new FingerCheck();
        bb.answered(0, ans);
        bb.start(0, true);
        bb.done(1000, "The ring is busy with another task");
        check("check: a blink refused by a busy ring says so and keeps the answer", bb.phase() == FingerCheck.Phase.FAILED
                && bb.brief("x").equals(ans.brief()) && bb.detail(AT).contains("The ring is busy") && bb.chip(0, true, true, false).equals("Make it blink"));
        FingerCheck dm = new FingerCheck();
        dm.start(0, false);
        dm.done(1000, SyncError.DEMO_RING);
        check("check: refused in demo mode", dm.phase() == FingerCheck.Phase.FAILED && dm.detail(AT).contains("demo mode"));
        // leaving the screen and coming back
        FingerCheck p = new FingerCheck();
        p.start(0, false);
        p.paused(500);
        check("check: leaving while asking lets go, the next visit asks", p.phase() == FingerCheck.Phase.IDLE && !p.holding());
        p.resumed(1000);
        check("check: back, asks again", p.autoAsk(now(1000)));
        p.start(1000, false);
        p.answered(2000, ans);
        p.done(2000, null);
        p.paused(3000);
        p.resumed(2000 + FingerCheck.KEEP_MS - 1);
        check("check: back within 2 minutes keeps the answer, no new ask", p.answer() == ans && !p.autoAsk(now(2000 + FingerCheck.KEEP_MS)));
        p.paused(2000 + FingerCheck.KEEP_MS);
        p.resumed(2000 + FingerCheck.KEEP_MS);
        check("check: back after 2 minutes asks again", p.answer() == null && p.phase() == FingerCheck.Phase.IDLE && p.autoAsk(now(2000 + FingerCheck.KEEP_MS)));
        FingerCheck pb = new FingerCheck();
        pb.answered(0, ans);
        pb.start(0, true);
        pb.blinking(100);
        pb.paused(200);
        check("check: leaving while blinking ends the blink, the answer stays", pb.phase() == FingerCheck.Phase.ANSWERED && !pb.holding());
        FingerCheck pw = new FingerCheck();
        FingerCheck.Now held = now(0);
        held.ringHeld = true;
        pw.autoAsk(held);
        pw.paused(10);
        eq("check: leaving while waiting forgets the wait", pw.phase(), FingerCheck.Phase.IDLE);
        // demo mode
        FingerCheck d = new FingerCheck();
        d.demoAnswer(0, 100);
        check("check: demo answers at once, made up", d.phase() == FingerCheck.Phase.ANSWERED && d.answer().demo && d.answer().state() == FingerNow.OFF);
        eq("check: demo offers the blink without a paired ring", d.chip(0, false, true, false), "Make it blink");
        d.resumed(10 * FingerCheck.KEEP_MS);
        check("check: the demo answer stays", d.answer() != null);
        d.start(0, true);
        d.blinking(0);
        eq("check: demo blink counts down", d.chip(1000, false, true, false), "Stop, 29 s");
        check("check: the demo never asks the ring", !d.autoAsk(now(0)));
        // review of #245: the ring sends no signal while connected, so no ask or blink during a walk or turn
        FingerCheck w = new FingerCheck();
        w.answered(0, ans);
        w.done(0, null);
        check("check: no chip while a walk is recorded", w.chip(0, true, true, true) == null && w.action(true, true, true) == FingerCheck.Action.NONE);
        eq("check: the blink is back after the walk", w.chip(0, true, true, false), "Make it blink");
        check("check: Start with nothing running stops nothing", !w.walkStarts(10) && w.phase() == FingerCheck.Phase.ANSWERED);
        w.start(20, false);
        check("check: Start during an ask stops it first", w.walkStarts(30) && !w.holding() && w.phase() == FingerCheck.Phase.FAILED);
        w.start(40, true);
        w.blinking(50);
        check("check: Start during a blink stops it first, the answer stays", w.walkStarts(60) && !w.holding() && w.phase() == FingerCheck.Phase.ANSWERED);
        FingerCheck fresh = new FingerCheck();
        fresh.start(0, false);
        check("check: Start during the first ask stops it, Ask again after the walk", fresh.walkStarts(5) && fresh.chip(0, true, true, false).equals("Ask again"));
        for (String t : new String[] {FingerCheck.ASKING, FingerCheck.BLINK_CONNECTING, FingerCheck.WAIT_RING, FingerCheck.WAIT_ANALYSIS, FingerCheck.NOT_ASKED, FingerCheck.STOPPED}) {
            check("check: short line (" + t + ")", t.length() <= 52);
        }
        for (String t : new String[] {FingerCheck.BLIND_ASKING, FingerCheck.BLIND_BLINKING, FingerCheck.BLIND_DEMO, FingerCheck.BLINK_NOTE, FingerCheck.ASKING}) {
            check("check: no dash as punctuation (" + t + ")", !t.contains(" - ") && !t.contains("—"));
        }
    }

    static void eq(String name, Object got, Object want) {
        check(name, want == null ? got == null : want.equals(got), got);
    }
}
