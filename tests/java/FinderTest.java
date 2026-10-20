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
        headings();
        turns();
        turnRules();
        stepDetection();
        paths();
        walks();
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
        // a walk with a pause: the pause adds nothing, the second run starts after a confirming step
        StepDetector pause = new StepDetector();
        int a = trace(pause, r, 0, 10000, 1.5, 1.5, 0.2, 0);
        int b = trace(pause, r, 10000, 5000, 0.3, 0.05, 0.15, 0);
        int c = trace(pause, r, 15000, 10000, 1.5, 1.5, 0.2, 0);
        check("walk, pause, walk", Math.abs(a - 15) <= 2 && b == 0 && Math.abs(c - 15) <= 2, a + " " + b + " " + c);
        // a gap in the samples (sensor paused) starts over without a step
        StepDetector gap = new StepDetector();
        check("first sample and a sample after a long gap add nothing", gap.add(0, 0, 9.81, 0) == 0 && gap.add(5000, 0, 15, 0) == 0);
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
        check("a square comes back to the start", Math.hypot(w.x(), w.y()) < 1e-9 && Math.abs(w.reach() - Math.sqrt(18)) < 1e-9 && Math.abs(w.distance() - 13) < 1e-9);
        double[] c = w.cover();
        check("a 3 m square covers 3 m or more in two directions and is 3 m wide", c[0] >= 3 && Math.abs(c[1] - 3) < 0.01, Arrays.toString(c));
        check("readings keep where they were taken", w.samples().get(6).y == 3 && w.samples().get(6).x == 0 && w.samples().get(7).x == 0.5);
        check("a covered square is ready for the map", w.mapReady());
        // tilted: the step keeps the last upright heading
        SearchWalk tl = new SearchWalk().stepLength(1);
        tl.heading(0, 90, true);
        tl.heading(10, 200, false);
        tl.steps(20, 1);
        check("a step while tilted keeps the last upright heading", Math.abs(tl.x() - 1) < 1e-9 && Math.abs(tl.y()) < 1e-9);
        // a long straight line, even diagonal, is not enough
        SearchWalk line = new SearchWalk().stepLength(0.7);
        walkLeg(line, 0, 45, 30);
        double[] lc = line.cover();
        check("a long diagonal line: no map", !line.mapReady() && lc[1] < 0.1 && line.locate() == null, Arrays.toString(lc));
        // a short L (1.5 by 1.5 m) is not enough
        SearchWalk l = new SearchWalk().stepLength(0.5);
        walkLeg(l, walkLeg(l, 0, 0, 3), 90, 3);
        check("a short L: not enough", !l.mapReady() && l.cover()[0] < SearchWalk.COVER_M);
        // enough walking but too few readings
        SearchWalk quiet = new SearchWalk().stepLength(1);
        for (int i = 0; i < 4; i++) {
            quiet.heading(i * 1000, i * 90, true);
            quiet.steps(i * 1000 + 500, 3);
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
     * 6 degrees a minute, plus 3 degrees of noise. Returns {error m, radius m, 1 if inside, 1 if a spot, 1 if clear}.
     */
    static double[] searchWalk(long seed, double[][] legs, double noise, double shadowDb, boolean drift) {
        Random r = new Random(seed);
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
                for (int i = 0; i < 4; i++) fade += 1.5 * Math.sin(kx[i] * px + ky[i] * py + ph[i]);
                double v = p0 - 10 * nTrue * Math.log10(d) - shadowDb * behind + fade + r.nextGaussian() * noise;
                if (r.nextDouble() > 0.2 && !(behind > 0.75 && r.nextDouble() < 0.5)) w.rssi(t, v);
            }
            t += 20;
        }
        SearchWalk.Spot s = w.locate();
        if (s == null) return new double[] {Double.NaN, 0, 0, 0, 0};
        // the map starts at the first waypoint facing ahead (+y), like the room
        double e = Math.hypot(s.x - (rx - wp[0][0]), s.y - (ry - wp[0][1]));
        return new double[] {e, s.radius, e <= s.radius ? 1 : 0, 1, s.clear ? 1 : 0};
    }

    /** Around the room 0.8 m from the walls, then across it. */
    static final double[][] AROUND = {{0.8, 0.8}, {0.8, 4.2}, {4.2, 4.2}, {4.2, 0.8}, {0.8, 0.8}, {2.5, 2.5}, {4.2, 4.2}};
    /** An L over part of the room: 3.5 m along one wall, 3 m along the next. */
    static final double[][] PART = {{0.8, 0.8}, {0.8, 4.3}, {3.8, 4.3}};

    /** Runs {@code runs} walks; prints and returns {median, worst, share inside, share with a spot}. */
    static double[] walkStats(String name, double[][] legs, double noise, double shadow, boolean drift, int runs) {
        List<Double> errs = new ArrayList<Double>();
        double inside = 0;
        int unclear = 0;
        for (int i = 0; i < runs; i++) {
            double[] o = searchWalk(7400 + i, legs, noise, shadow, drift);
            if (o[3] == 0) continue;
            if (o[4] == 0) unclear++;
            errs.add(o[0]);
            inside += o[2];
        }
        Collections.sort(errs);
        int n = errs.size();
        double median = n == 0 ? 99 : errs.get(n / 2), worst = n == 0 ? 99 : errs.get(n - 1);
        System.out.printf(Locale.US, "  search walk, %s, noise %.0f dB, shadow %.0f dB%s: median error %.2f m, worst %.2f m, circle held the ring in %.0f %%, a spot in %d of %d (%d not clear)%n",
                name, noise, shadow, drift ? ", drift" : "", median, worst, n == 0 ? 0 : 100 * inside / n, n, runs, unclear);
        return new double[] {median, worst, n == 0 ? 0 : inside / n, n / (double) runs, unclear / (double) runs};
    }

    static void walks() {
        double worstMedian = 0, worstAll = 0, leastInside = 1;
        for (double noise : new double[] {4, 6}) {
            for (double shadow : new double[] {0, 10}) {
                double[] s = walkStats("around and across", AROUND, noise, shadow, true, 60);
                worstMedian = Math.max(worstMedian, s[0]);
                worstAll = Math.max(worstAll, s[1]);
                leastInside = Math.min(leastInside, s[2]);
                check("walk around the room (noise " + noise + ", shadow " + shadow + "): a spot every time", s[3] == 1, s[3]);
                check("walk around the room (noise " + noise + ", shadow " + shadow + "): a clear circle in at least 9 of 10", s[4] <= 0.1, s[4]);
            }
        }
        double[] clean = walkStats("around and across", AROUND, 4, 10, false, 60);
        // the promise in the pull request comes from these numbers
        check("walk around the room: median error under 1 m", worstMedian < 1.0, worstMedian);
        check("walk around the room: worst error under 3.5 m", worstAll < 3.5, worstAll);
        check("walk around the room: the circle holds the ring in at least 8 of 10 walks", leastInside >= 0.8, leastInside);
        check("no drift: median under 1 m", clean[0] < 1.0, clean[0]);
        double[] part = walkStats("L over part of the room", PART, 5, 10, true, 60);
        check("L walk: a spot every time", part[3] == 1, part[3]);
        check("L walk: median error under 2 m", part[0] < 2.0, part[0]);
        check("L walk: worst error under 4 m", part[1] < 4.0, part[1]);
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
        // the demo walk (screenshots) shows a clear circle over its made up ring
        List<double[]> ev = DemoWalk.events();
        SearchWalk demo = new SearchWalk().stepLength(0.72);
        int half = DemoWalk.feed(demo, ev, 0, 5000);
        check("demo walk: fed in parts", half > 0 && half < ev.size() && demo.locate() == null);
        check("demo walk: all fed", DemoWalk.feed(demo, ev, half, Long.MAX_VALUE) == ev.size());
        SearchWalk.Spot ds = demo.locate();
        check("demo walk: a clear circle holding its ring", ds != null && ds.clear && Math.hypot(ds.x - DemoWalk.RING_X, ds.y - DemoWalk.RING_Y) <= ds.radius
                && ev.get(ev.size() - 1)[0] < 70000, ds == null ? null : ds.x + "," + ds.y + " r " + ds.radius + " t " + ev.get(ev.size() - 1)[0]);
        // turning on the spot: no steps, no map; the phase 1 direction still works
        SearchWalk spin = turn(90, 3, 0);
        check("turning on the spot: no map, a direction", spin.reach() == 0 && !spin.mapReady() && spin.locate() == null && spin.direction() != null);
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
