package app.openring.core.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A search for the ring with the phone held upright against the chest (issue #74): every signal
 * reading is recorded with the heading the person faced at that moment. Phase 1 uses it for
 * "turn on the spot": the body blocks the signal from behind, so the readings are strongest while
 * the person faces the ring. Phase 2 adds steps to the same record for the walked mini map: each
 * step moves the person one step length along the heading, every reading keeps where it was taken,
 * and {@link #locate()} fits a signal loss model to say where the ring probably is.
 *
 * Headings come from the phone's gyroscope based orientation ({@code GAME_ROTATION_VECTOR}), whose
 * zero is arbitrary: the first upright heading after Start becomes 0, so every heading, direction
 * and map position here is relative to the way the person faced at Start (0 ahead, 90 to the right).
 */
public final class SearchWalk {
    public static final int SECTORS = 12;
    public static final double SECTOR_DEG = 360.0 / SECTORS;
    /** The direction is worked out once the person faced this many of the 12 directions. */
    public static final int SECTORS_NEEDED = 10;
    /** ... and readings came from at least this many directions. */
    public static final int SECTORS_WITH_READINGS = 3;
    /** A direction faced without hearing the ring counts as this many dB below the weakest one heard. */
    public static final double SILENT_DB = 6;
    /** Under this difference between strongest and weakest direction there is no clear direction. */
    public static final double CLEAR_DB = 4;
    /** Readings while the phone tilts more than this from upright are kept but not counted. */
    public static final double MAX_TILT_DEG = 45;
    /** A reading takes the heading of the newest orientation sample, if it is at most this old. */
    public static final long HEADING_MAX_AGE_MS = 300;

    /** One signal reading with where the person faced and stood (metres from the start, y ahead, x right). */
    public static final class Sample {
        public final long t;
        public final double heading, rssi, x, y;
        public final boolean upright;

        Sample(long t, double heading, double rssi, boolean upright, double x, double y) {
            this.t = t;
            this.heading = heading;
            this.rssi = rssi;
            this.upright = upright;
            this.x = x;
            this.y = y;
        }
    }

    /** A point of the walked path: time, metres right of and ahead of the start. */
    public static final class Point {
        public final long t;
        public final double x, y;

        Point(long t, double x, double y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }

    /** The result of a turn. */
    public static final class Direction {
        /** Strongest direction in degrees, relative to the start heading. */
        public double degrees;
        /** Mean signal towards the strongest and the weakest direction (dBm) and their difference (dB). */
        public double strongest, weakest, spread;
        /** True when the spread is at least {@link #CLEAR_DB}. */
        public boolean clear;
    }

    private final List<Sample> samples = new ArrayList<Sample>();
    private final List<Point> path = new ArrayList<Point>();
    private final boolean[] faced = new boolean[SECTORS];
    private double heading = Double.NaN, walkHeading = Double.NaN, start = Double.NaN;
    /** Heading of the step before as judged ({@link #steadyAt}; NaN before the first step). */
    private double lastStepHeading = Double.NaN;
    /** Steps that wait while the heading rotates: {time ms, heading, steps, 1 if it rotated}. */
    private final List<double[]> held = new ArrayList<double[]>();
    /** Signed degrees the waiting steps rotated, the most reached in their direction, that direction (1 or -1). */
    private double heldTurn, heldMost;
    private int heldSign;
    /** The newest waiting step kept the rotation going no further (one such pause is allowed). */
    private boolean heldPause;
    /** Upright headings of the last {@link #HISTORY_MS}: {time ms, heading}, for steps reported late. */
    private final ArrayDeque<double[]> recent = new ArrayDeque<double[]>();
    private boolean upright;
    private long headingAt = Long.MIN_VALUE;
    private double stepLength = StepDetector.stepLength(175), x, y;
    private int steps;

    public SearchWalk() {
        path.add(new Point(0, 0, 0));
    }

    /** Metres per step (from the person's height, see {@link StepDetector#stepLength}). */
    public SearchWalk stepLength(double metres) {
        stepLength = metres;
        return this;
    }

    public double stepLength() {
        return stepLength;
    }

    /** The newest orientation: heading in degrees and whether the phone is upright enough. */
    public void heading(long atMs, double degrees, boolean upright) {
        if (Double.isNaN(start)) {
            if (!upright) return; // nothing counts before the first upright heading
            start = norm(degrees);
        }
        heading = norm(degrees - start);
        this.upright = upright;
        headingAt = atMs;
        if (upright) {
            faced[sector(heading)] = true;
            walkHeading = heading;
            recent.addLast(new double[] {atMs, heading});
            while (recent.size() > 1 && recent.peekFirst()[0] < atMs - HISTORY_MS) recent.pollFirst();
        }
    }

    /** Upright headings are kept this long (ms), so a step reported late still gets the heading of its own time. */
    public static final long HISTORY_MS = 5000;

    /** The upright heading at a time: the newest one at or before it (the oldest kept when it is older still). */
    public double headingAt(long atMs) {
        double h = walkHeading;
        for (Iterator<double[]> it = recent.descendingIterator(); it.hasNext(); ) {
            double[] e = it.next();
            h = e[1];
            if (e[0] <= atMs) break;
        }
        return h;
    }

    /** How far a step turned is judged on the mean heading of this many ms up to it, so the sensor's own jitter does not count as turning. */
    public static final long STEADY_MS = 200;

    /** The mean upright heading of the {@link #STEADY_MS} up to a time, or {@link #headingAt} when there is none in that time. */
    public double steadyAt(long atMs) {
        double c = 0, sn = 0;
        int k = 0;
        for (Iterator<double[]> it = recent.descendingIterator(); it.hasNext(); ) {
            double[] e = it.next();
            if (e[0] > atMs) continue;
            if (e[0] <= atMs - STEADY_MS) break;
            c += Math.cos(Math.toRadians(e[1]));
            sn += Math.sin(Math.toRadians(e[1]));
            k++;
        }
        return k == 0 ? headingAt(atMs) : norm(Math.toDegrees(Math.atan2(sn, c)));
    }

    /** Records a signal reading; returns whether it counts (a fresh heading, phone upright, a {@link Proximity#plausible} value). */
    public boolean rssi(long atMs, double dBm) {
        if (!Proximity.plausible(dBm)) return false; // 127 means "not available" (issue #95)
        if (Double.isNaN(heading) || atMs - headingAt > HEADING_MAX_AGE_MS || atMs < headingAt - HEADING_MAX_AGE_MS) return false;
        samples.add(new Sample(atMs, heading, dBm, upright, x, y));
        return upright;
    }

    /** A step that turned at least this much (degrees) from the step before starts a rotation, or keeps one going the same way. */
    public static final double ROTATE_DEG = 5;
    /**
     * Waiting steps that rotated the same way through this many degrees in all are a turn on the spot
     * and do not move the person: more than a room's corner (90) or the turn into a diagonal (135).
     */
    public static final double SPIN_DEG = 150;
    /** ... and at least this many of them rotated (one step alone is a corner, however sharp). */
    public static final int SPIN_STEPS = 2;

    /**
     * One step at its own time (ms, the clock of {@link #heading}), with the upright heading of that
     * moment: {@link StepDetector} reports the first step of a run only with the second, so that
     * step comes late and must not take the newer heading (issue #108).
     */
    public void step(long atMs) {
        steps++;
        if (Double.isNaN(walkHeading)) return;
        unit(atMs, headingAt(atMs), steadyAt(atMs), 1);
    }

    /**
     * {@code n} steps were taken now: the person moves one step length each along the newest
     * upright heading (while the phone tilts, the last upright heading is kept). Without any heading
     * yet the steps are counted but go nowhere. Steps that share one heading are judged as one, so
     * the second of a pair cannot confirm the first. Shuffling while turning on the spot is not
     * walking: a step that turned at least {@link #ROTATE_DEG} from the step before (the first one
     * from the way the person faced at Start) waits. Further steps rotating the same way wait with
     * it, one step that keeps the rotation no further is allowed in between. When the rotation
     * ends, the waiting steps move along their own headings (a corner or a curve), unless they
     * rotated {@link #SPIN_DEG} or more in all: then they were a turn on the spot and do not move.
     * A single waiting step (a corner taken in one step) moves with the next step that does not turn
     * on. The readings taken while steps waited get the places of those steps once they move.
     */
    public void steps(long atMs, int n) {
        if (n <= 0) return;
        steps += n;
        if (Double.isNaN(walkHeading)) return;
        unit(atMs, walkHeading, steadyAt(atMs), n);
    }

    /** {@code n} steps at one time and heading {@code h}, judged as one on the steadier heading {@code judge}. */
    private void unit(long atMs, double h, double judge, int n) {
        double turned = signed(judge - (Double.isNaN(lastStepHeading) ? 0 : lastStepHeading));
        lastStepHeading = judge;
        boolean rotating = Math.abs(turned) >= ROTATE_DEG;
        int sign = turned >= 0 ? 1 : -1;
        if (held.isEmpty()) {
            if (rotating) hold(atMs, h, n, turned);
            else move(atMs, h, n);
            return;
        }
        double sum = heldTurn + turned;
        if (rotating && sign == heldSign && sum * heldSign >= heldMost + ROTATE_DEG) {
            // the rotation goes on
            held.add(new double[] {atMs, h, n, 1});
            heldTurn = sum;
            heldMost = sum * heldSign;
            heldPause = false;
            return;
        }
        if (!heldPause && rotatingHeld() >= 2) {
            // one step that does not turn on is allowed (an uneven shuffle, the heading's own noise)
            held.add(new double[] {atMs, h, n, 0});
            heldTurn = sum;
            heldPause = true;
            return;
        }
        release();
        if (rotating) hold(atMs, h, n, turned);
        else move(atMs, h, n);
    }

    private void hold(long atMs, double h, int n, double turned) {
        held.add(new double[] {atMs, h, n, 1});
        heldTurn = turned;
        heldSign = turned >= 0 ? 1 : -1;
        heldMost = Math.abs(turned);
        heldPause = false;
    }

    private int rotatingHeld() {
        int k = 0;
        for (double[] e : held) if (e[3] == 1) k++;
        return k;
    }

    /** The rotation ended: the waiting steps move (a corner or curve) or stay (a turn on the spot; a step after it still moves). */
    private void release() {
        boolean spin = heldMost >= SPIN_DEG && rotatingHeld() >= SPIN_STEPS;
        int last = held.size() - 1;
        for (int i = 0; i <= last; i++) {
            double[] e = held.get(i);
            if (spin && !(heldPause && i == last)) continue;
            move((long) e[0], e[1], (int) e[2]);
        }
        held.clear();
        heldPause = false;
    }

    /**
     * The walk stopped (Stop, or the screen left): steps still waiting move along their headings,
     * unless they rotated {@link #SPIN_DEG} or more (a turn on the spot). Steps after this start a
     * new rotation check as usual.
     */
    public void finish() {
        if (!held.isEmpty()) release();
    }

    /** Steps waiting for the rotation to end (they count in {@link #steps()} but have not moved the person yet). */
    public int waiting() {
        int k = 0;
        for (double[] e : held) k += (int) e[2];
        return k;
    }

    private void move(long atMs, double h, int n) {
        double a = Math.toRadians(h);
        for (int i = 0; i < n; i++) {
            x += stepLength * Math.sin(a);
            y += stepLength * Math.cos(a);
            path.add(new Point(atMs, x, y));
        }
        // readings taken since the step (it came late, or waited) were placed where the person stood before it
        for (int k = samples.size() - 1; k >= 0 && samples.get(k).t > atMs; k--) {
            Sample s = samples.get(k);
            samples.set(k, new Sample(s.t, s.heading, s.rssi, s.upright, x, y));
        }
    }

    /** -180 to under 180: how far and which way (positive to the right) a heading change turned. */
    public static double signed(double deg) {
        double d = norm(deg);
        return d >= 180 ? d - 360 : d;
    }

    /** The first upright heading after Start in the sensor's own terms (NaN before); all headings here are relative to it. */
    public double start() {
        return start;
    }

    /** A heading in the sensor's own terms, made relative to the start (unchanged before the start is known). */
    public double relative(double degrees) {
        return Double.isNaN(start) ? degrees : norm(degrees - start);
    }

    public int steps() {
        return steps;
    }

    /** The walked path, starting at 0, 0 (metres, x right of the start heading, y ahead). */
    public List<Point> path() {
        return Collections.unmodifiableList(path);
    }

    /** Where the person is now (metres from the start). */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    /** Metres walked (the steps that moved the person, times step length). */
    public double distance() {
        return (path.size() - 1) * stepLength;
    }

    /** Seconds in which the ring was heard with the phone upright (half seconds with readings / 2). */
    public double heardSeconds() {
        return bins().size() / 2.0;
    }

    /** The walk spans enough of the room for the circle (whether or not the ring was heard enough). */
    public boolean covered() {
        double[] c = cover();
        return c[0] >= COVER_M && c[1] >= WIDTH_M;
    }

    /** The farthest the person got from the start (metres): under {@link #SPOT_M} it is a turn on the spot. */
    public double reach() {
        double r = 0;
        for (Point p : path) r = Math.max(r, Math.hypot(p.x, p.y));
        return r;
    }

    public List<Sample> samples() {
        return Collections.unmodifiableList(samples);
    }

    /** The newest heading in degrees, NaN before the first. */
    public double heading() {
        return heading;
    }

    public boolean upright() {
        return upright;
    }

    /** How many of the 12 directions the person faced with the phone upright. */
    public int sectorsFaced() {
        int n = 0;
        for (boolean f : faced) if (f) n++;
        return n;
    }

    /** Degrees covered so far, in steps of 30. */
    public int coveredDegrees() {
        return (int) Math.round(sectorsFaced() * SECTOR_DEG);
    }

    /** Counted readings per direction (12 sectors of 30 degrees, the first centred on 0). */
    public int[] sectorCounts() {
        int[] n = new int[SECTORS];
        for (Sample s : samples) if (s.upright) n[sector(s.heading)]++;
        return n;
    }

    /** Mean signal per direction, NaN where there is no counted reading (for the dial). */
    public double[] sectorMeans() {
        double[] sum = new double[SECTORS];
        int[] n = sectorCounts();
        for (Sample s : samples) if (s.upright) sum[sector(s.heading)] += s.rssi;
        double[] out = new double[SECTORS];
        for (int i = 0; i < SECTORS; i++) out[i] = n[i] == 0 ? Double.NaN : sum[i] / n[i];
        return out;
    }

    public boolean ready() {
        if (sectorsFaced() < SECTORS_NEEDED) return false;
        int with = 0;
        for (int c : sectorCounts()) if (c > 0) with++;
        return with >= SECTORS_WITH_READINGS;
    }

    /**
     * The strongest direction, or null until {@link #ready()}. The signal around the circle is fitted
     * with one smooth wave (strong on one side, weak on the other, as the body's shadow makes it),
     * each of the 12 directions weighted equally however many readings it has; the wave's peak is
     * the direction and its height from trough to peak the spread.
     *
     * A direction the person faced without hearing the ring at all is evidence too: the body hid
     * the ring there. It counts as {@link #SILENT_DB} below the weakest direction that was heard,
     * so a ring heard only from the front still gets an arrow to the front.
     */
    public Direction direction() {
        if (!ready()) return null;
        int[] n = sectorCounts();
        boolean silent = false;
        for (int i = 0; i < SECTORS; i++) if (faced[i] && n[i] == 0) silent = true;
        Direction r = silent ? sectorFit(n) : sampleFit(n);
        r.degrees = Math.round(norm(r.degrees)) % 360;
        r.clear = r.spread >= CLEAR_DB;
        return r;
    }

    /** Every faced direction was heard: fit the readings themselves (keeps their exact headings). */
    private Direction sampleFit(int[] n) {
        double wSum = 0, mean = 0;
        for (Sample s : samples) {
            if (!s.upright) continue;
            double w = 1.0 / n[sector(s.heading)];
            wSum += w;
            mean += w * s.rssi;
        }
        mean /= wSum;
        double c = 0, sn = 0;
        for (Sample s : samples) {
            if (!s.upright) continue;
            double w = 1.0 / n[sector(s.heading)], a = Math.toRadians(s.heading);
            c += w * (s.rssi - mean) * Math.cos(a);
            sn += w * (s.rssi - mean) * Math.sin(a);
        }
        return wave(mean, c, sn, wSum);
    }

    /** Some faced directions stayed silent: fit one value per faced direction, silent ones weak. */
    private Direction sectorFit(int[] n) {
        double[] means = sectorMeans();
        double weakest = Double.POSITIVE_INFINITY;
        for (double m : means) if (!Double.isNaN(m)) weakest = Math.min(weakest, m);
        double[] v = new double[SECTORS];
        double mean = 0;
        int k = 0;
        for (int i = 0; i < SECTORS; i++) {
            if (!faced[i] && n[i] == 0) continue;
            v[i] = n[i] == 0 ? weakest - SILENT_DB : means[i];
            mean += v[i];
            k++;
        }
        mean /= k;
        double c = 0, sn = 0;
        for (int i = 0; i < SECTORS; i++) {
            if (!faced[i] && n[i] == 0) continue;
            double a = Math.toRadians(i * SECTOR_DEG);
            c += (v[i] - mean) * Math.cos(a);
            sn += (v[i] - mean) * Math.sin(a);
        }
        return wave(mean, c, sn, k);
    }

    private static Direction wave(double mean, double c, double sn, double count) {
        // with values spread evenly, the sum of cos squared over the directions is half their count
        double amp = 2 * Math.sqrt(c * c + sn * sn) / count;
        Direction r = new Direction();
        r.degrees = Math.toDegrees(Math.atan2(sn, c));
        r.strongest = mean + amp;
        r.weakest = mean - amp;
        r.spread = 2 * amp;
        return r;
    }

    // ------------------------------------------------------------------ the walked map (phase 2)

    /** Under this distance from the start the walk counts as a turn on the spot (the arrow, not the map). */
    public static final double SPOT_M = 1.0;
    /** The circle needs the walk to stretch this many metres in two directions at right angles. */
    public static final double COVER_M = 2.0;
    /** ... and be at least this wide at its narrowest, so a long straight line does not count. */
    public static final double WIDTH_M = 1.0;
    /** ... and this many half seconds with counted readings (6 s). */
    public static final int MIN_BINS = 12;
    /** Height between phone and ring the model assumes (phone at the chest, ring on a table, bed or floor). */
    public static final double HEIGHT_M = 0.7;
    /** The ring is searched over the walked area plus this margin, in squares of {@link #GRID_M}. */
    public static final double MARGIN_M = 2.0, GRID_M = 0.2;
    /** Loss exponents tried: 2 is open space, 3.5 a room full of furniture and bodies. */
    public static final double N_MIN = 2.0, N_MAX = 3.5, N_STEP = 0.25;
    /** The body's shadow (signal lost with the ring behind the person) is fitted from 0 to this (dB). */
    public static final double SHADOW_MAX = 15;
    /** Readings are averaged per half second (the ring sends several, they are not independent). */
    public static final long BIN_MS = 500;
    /**
     * The circle's radius: this many times the spread of the likely spots, at least {@link #MIN_RADIUS_M}.
     * Set on simulated walks (FinderTest, tuning seeds); on fresh seeds the circle held the ring in 84 to 91 % of walks.
     */
    public static final double RADIUS_K = 1.8, MIN_RADIUS_M = 1.0;
    /** Best spots closer than this to the outer edge of the searched area count as "beyond the walk". */
    public static final double EDGE_M = 0.5;
    /**
     * The model has to explain at least this share of the signal's ups and downs along the walk; a
     * signal that stays the same everywhere (the ring in another room) points to no spot at all.
     */
    public static final double MIN_EXPLAINED = 0.15;
    /**
     * The values the fit chooses: the spot (two), the loss exponent, the level and the shadow. Out
     * of hundreds of spots the best one always explains some of plain noise, the more so the fewer
     * readings there are (issue #136), so the share needed grows for a short walk.
     */
    public static final int FIT_VALUES = 5;
    /**
     * How much better than a flat signal the fit has to be, as an F value (explained share per
     * fitted value over the share left per remaining reading). Set on simulated walks with no ring
     * signal at all (4 dB noise, other seeds than FinderTest's): on the short L walk 1 in 500 got a
     * circle, 1 in 9 with the 15 % alone. A circle lost this way held the ring in most of the walks
     * that had one (the price: more short walks end with "walk more").
     */
    public static final double MIN_F = 5;

    /**
     * The share of the signal's ups and downs the model must explain with this many half seconds of
     * readings: {@link #FIT_VALUES} × {@link #MIN_F} / (that + bins − {@link #FIT_VALUES} − 1), at
     * least {@link #MIN_EXPLAINED}. About 0.81 for 12 half seconds, 0.64 for 20, 0.32 for 60, 0.15 from 148 on.
     */
    public static double neededShare(int bins) {
        double pf = FIT_VALUES * MIN_F, rest = bins - FIT_VALUES - 1;
        if (rest <= 0) return 1;
        return Math.max(MIN_EXPLAINED, pf / (pf + rest));
    }

    /** A circle wider than this (radius, m) is no clear spot: the map then shows only the trail. */
    public static final double CLEAR_RADIUS_M = 3.0;

    /** Where the ring probably is (metres from the start, like the path), with the circle's radius. */
    public static final class Spot {
        public double x, y, radius;
        /** The best fitting values: signal at 1 m (dBm), loss exponent, body shadow (dB). */
        public double level, exponent, shadow;
        /** How far the readings are from the fitted model on average (dB). */
        public double rms;
        /** Half seconds with readings used. */
        public int bins;
        /** Share of the signal's ups and downs along the walk that the model explains (0 to 1). */
        public double explained;
        /** The share it needs for a clear circle with this many half seconds ({@link #neededShare}). */
        public double needed;
        /**
         * True when the best spots lie at the outer edge of the searched area ({@link #MARGIN_M} past
         * the walk): the signal grows towards there, so the ring is probably farther that way.
         */
        public boolean edge;
        /**
         * True when the middle of the circle lies outside the area the walk spans (its bounding box):
         * the ring may then also be farther that way, the model cannot tell far from a bit beyond.
         */
        public boolean beyond;
        /** A circle to show: not wider than {@link #CLEAR_RADIUS_M}, not at the edge, and the model explains at least {@link #needed}. */
        public boolean clear;
    }

    /**
     * How the walk spreads out, in metres: {across, width}. "Across" is the most it stretches in two
     * directions at right angles at once (the smaller of the two, for the best pair of directions);
     * "width" is its narrowest side, so a straight line (even a long diagonal one) has almost none.
     */
    public double[] cover() {
        double across = 0, width = Double.POSITIVE_INFINITY;
        double[] range = new double[36];
        for (int k = 0; k < 36; k++) {
            double a = Math.toRadians(k * 5), ux = Math.sin(a), uy = Math.cos(a);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (Point p : path) {
                double v = p.x * ux + p.y * uy;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            range[k] = hi - lo;
            width = Math.min(width, range[k]);
        }
        for (int k = 0; k < 18; k++) across = Math.max(across, Math.min(range[k], range[k + 18]));
        return new double[] {across, width};
    }

    /**
     * Enough walking for the circle: {@link #COVER_M} in two directions at right angles, at least
     * {@link #WIDTH_M} wide (not a straight line), and {@link #MIN_BINS} half seconds heard.
     */
    public boolean mapReady() {
        return covered() && bins().size() >= MIN_BINS;
    }

    /** A copy of the record so far, for working out the circle on another thread while the walk goes on. */
    public SearchWalk snapshot() {
        SearchWalk c = new SearchWalk();
        c.samples.addAll(samples);
        c.path.clear();
        c.path.addAll(path);
        c.stepLength = stepLength;
        c.x = x;
        c.y = y;
        c.steps = steps;
        c.start = start;
        c.heading = heading;
        c.walkHeading = walkHeading;
        System.arraycopy(faced, 0, c.faced, 0, SECTORS);
        return c;
    }

    /** The fit uses at most this many points; a longer walk merges neighbouring half seconds (time stays even). */
    public static final int MAX_BINS = 240;

    /** Merges consecutive bins in groups so at most {@code max} are left (headings averaged as directions). */
    public static List<double[]> merged(List<double[]> b, int max) {
        if (b.size() <= max) return b;
        int g = (b.size() + max - 1) / max;
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < b.size(); i += g) {
            double sx = 0, sy = 0, sc = 0, ss = 0, sr = 0, st = 0;
            int k = 0;
            for (int j = i; j < Math.min(b.size(), i + g); j++, k++) {
                double[] q = b.get(j);
                sx += q[0];
                sy += q[1];
                sc += Math.cos(Math.toRadians(q[2]));
                ss += Math.sin(Math.toRadians(q[2]));
                sr += q[3];
                st += q[4];
            }
            out.add(bin(sx, sy, sc, ss, sr, st, k));
        }
        return out;
    }

    /** Counted readings averaged per half second: {x, y, heading, dBm, time ms} (the map's dots). */
    public List<double[]> bins() {
        List<double[]> out = new ArrayList<double[]>();
        long bin = Long.MIN_VALUE;
        double sx = 0, sy = 0, sc = 0, ss = 0, sr = 0, st = 0;
        int k = 0;
        for (Sample s : samples) {
            if (!s.upright) continue;
            long b = Math.floorDiv(s.t, BIN_MS);
            if (b != bin && k > 0) {
                out.add(bin(sx, sy, sc, ss, sr, st, k));
                sx = sy = sc = ss = sr = st = 0;
                k = 0;
            }
            bin = b;
            sx += s.x;
            sy += s.y;
            sc += Math.cos(Math.toRadians(s.heading));
            ss += Math.sin(Math.toRadians(s.heading));
            sr += s.rssi;
            st += s.t;
            k++;
        }
        if (k > 0) out.add(bin(sx, sy, sc, ss, sr, st, k));
        return out;
    }

    private static double[] bin(double sx, double sy, double sc, double ss, double sr, double st, int k) {
        return new double[] {sx / k, sy / k, norm(Math.toDegrees(Math.atan2(ss, sc))), sr / k, st / k};
    }

    /**
     * Where the ring probably is, or null until {@link #mapReady()}. The model: signal = level at 1 m
     * minus 10 × exponent × log10(distance) minus the body's shadow (full when the ring is behind the
     * person, none when they face it). For every spot of a grid over the walked area plus a margin and
     * every exponent from 2 to 3.5, the level and shadow are fitted by least squares; how well each spot
     * fits gives it a weight. The answer is the weighted middle of the spots, the circle's radius
     * {@link #RADIUS_K} times their weighted spread (at least {@link #MIN_RADIUS_M}).
     */
    public Spot locate() {
        if (!mapReady()) return null;
        List<double[]> b = merged(bins(), MAX_BINS);
        int m = b.size();
        double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY, y0 = Double.POSITIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (Point p : path) {
            x0 = Math.min(x0, p.x);
            x1 = Math.max(x1, p.x);
            y0 = Math.min(y0, p.y);
            y1 = Math.max(y1, p.y);
        }
        x0 -= MARGIN_M;
        y0 -= MARGIN_M;
        x1 += MARGIN_M;
        y1 += MARGIN_M;
        double grid = Math.max(GRID_M, Math.max(x1 - x0, y1 - y0) / 60);
        int nx = (int) Math.ceil((x1 - x0) / grid) + 1, ny = (int) Math.ceil((y1 - y0) / grid) + 1;
        double[] r = new double[m], hx = new double[m], hy = new double[m];
        double rMean = 0;
        for (int i = 0; i < m; i++) {
            double[] q = b.get(i);
            r[i] = q[3];
            rMean += r[i];
            double a = Math.toRadians(q[2]);
            hx[i] = Math.sin(a);
            hy[i] = Math.cos(a);
        }
        rMean /= m;
        double[] lg = new double[m], sh = new double[m];
        double[][] sse = new double[nx][ny];
        double best = Double.POSITIVE_INFINITY;
        Spot out = new Spot();
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                double gx = x0 + ix * grid, gy = y0 + iy * grid;
                for (int i = 0; i < m; i++) {
                    double[] q = b.get(i);
                    double dx = gx - q[0], dy = gy - q[1], d = Math.sqrt(dx * dx + dy * dy + HEIGHT_M * HEIGHT_M);
                    lg[i] = Math.log10(d);
                    double flat = Math.hypot(dx, dy);
                    // 0 facing the ring, 1 with the ring behind; right above the ring there is no shadow
                    sh[i] = flat < 1e-6 ? 0 : (1 - (dx * hx[i] + dy * hy[i]) / flat) / 2;
                }
                double cellBest = Double.POSITIVE_INFINITY;
                for (double n = N_MIN; n <= N_MAX + 1e-9; n += N_STEP) {
                    double[] fit = fit(r, lg, sh, n);
                    if (fit[2] < cellBest) cellBest = fit[2];
                    if (fit[2] < best) {
                        best = fit[2];
                        out.level = fit[0];
                        out.shadow = fit[1];
                        out.exponent = n;
                    }
                }
                sse[ix][iy] = cellBest;
            }
        }
        // each spot weighs by how likely its fit is (Gaussian errors with the spread the best fit left)
        double var = Math.max(best / Math.max(1, m - 4), 1.0);
        double ws = 0, wx = 0, wy = 0;
        double[][] w = new double[nx][ny];
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                w[ix][iy] = Math.exp(-(sse[ix][iy] - best) / (2 * var));
                ws += w[ix][iy];
                wx += w[ix][iy] * (x0 + ix * grid);
                wy += w[ix][iy] * (y0 + iy * grid);
            }
        }
        out.x = wx / ws;
        out.y = wy / ws;
        double spread = 0;
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                double dx = x0 + ix * grid - out.x, dy = y0 + iy * grid - out.y;
                spread += w[ix][iy] * (dx * dx + dy * dy);
            }
        }
        out.radius = Math.max(MIN_RADIUS_M, RADIUS_K * Math.sqrt(spread / ws));
        out.beyond = out.x < x0 + MARGIN_M || out.x > x1 - MARGIN_M || out.y < y0 + MARGIN_M || out.y > y1 - MARGIN_M;
        double inX = Math.min(out.x - x0, x0 + (nx - 1) * grid - out.x), inY = Math.min(out.y - y0, y0 + (ny - 1) * grid - out.y);
        out.edge = Math.min(inX, inY) < EDGE_M;
        out.rms = Math.sqrt(best / m);
        double flat = 0;
        for (double v : r) flat += (v - rMean) * (v - rMean);
        out.explained = flat <= 0 ? 0 : Math.max(0, 1 - best / flat);
        out.needed = neededShare(m);
        out.clear = out.radius <= CLEAR_RADIUS_M && !out.edge && out.explained >= out.needed;
        out.bins = m;
        return out;
    }

    /**
     * Least squares for r = level − 10 n log10(d) − shadow × s with the shadow kept between 0 and
     * {@link #SHADOW_MAX}. Returns {level, shadow, sum of squared errors}.
     */
    public static double[] fit(double[] r, double[] lg, double[] s, double n) {
        int m = r.length;
        // y = r + 10 n log10 d = level − shadow × s
        double my = 0, ms = 0;
        for (int i = 0; i < m; i++) {
            my += r[i] + 10 * n * lg[i];
            ms += s[i];
        }
        my /= m;
        ms /= m;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < m; i++) {
            double yi = r[i] + 10 * n * lg[i] - my, si = s[i] - ms;
            sxy += si * yi;
            sxx += si * si;
        }
        double shadow = sxx < 1e-12 ? 0 : -sxy / sxx;
        shadow = Math.max(0, Math.min(SHADOW_MAX, shadow));
        double level = my + shadow * ms;
        double e = 0;
        for (int i = 0; i < m; i++) {
            double d = r[i] + 10 * n * lg[i] - level + shadow * s[i];
            e += d * d;
        }
        return new double[] {level, shadow, e};
    }

    /** "about 2.1 m ahead and 0.8 m to the right of where you started" (x right, y ahead, metres). */
    public static String where(double x, double y) {
        String ahead = Math.abs(y) < 0.3 ? null : String.format(java.util.Locale.getDefault(), "%.1f", Math.abs(y)) + " m " + (y > 0 ? "ahead" : "behind");
        String side = Math.abs(x) < 0.3 ? null : String.format(java.util.Locale.getDefault(), "%.1f", Math.abs(x)) + " m to the " + (x > 0 ? "right" : "left");
        if (ahead == null && side == null) return "right around where you started";
        if (ahead == null) return "about " + side + " of where you started";
        if (side == null) return "about " + ahead + " of where you started";
        return "about " + ahead + " and " + side + " of where you started";
    }

    /** The direction (0 to 11) a heading falls in. */
    public static int sector(double deg) {
        return (int) Math.floor(norm(deg + SECTOR_DEG / 2) / SECTOR_DEG) % SECTORS;
    }

    /** 0 to under 360. */
    public static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /** Smallest angle between two headings, 0 to 180. */
    public static double diff(double a, double b) {
        double d = Math.abs(norm(a) - norm(b));
        return d > 180 ? 360 - d : d;
    }

    /**
     * Heading and tilt of a phone held upright against the chest, screen towards the person, from
     * its rotation matrix (3 x 3, row major, phone to world, as Android's
     * {@code getRotationMatrixFromVector} gives it). The person faces where the back of the phone
     * points. Returns {heading degrees 0 to under 360, tilt from upright in degrees 0 to 90}.
     */
    public static double[] headingOf(float[] r) {
        // the back of the phone is its -Z axis; in world terms that is minus the third column
        double x = -r[2], y = -r[5], z = -r[8];
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) return new double[] {Double.NaN, 90};
        double tilt = Math.toDegrees(Math.asin(Math.min(1, Math.abs(z) / len)));
        double h = Math.toDegrees(Math.atan2(x, y));
        return new double[] {norm(h), tilt};
    }
}
