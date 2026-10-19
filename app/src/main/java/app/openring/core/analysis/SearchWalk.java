package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A search for the ring with the phone held upright against the chest (issue #74): every signal
 * reading is recorded with the heading the person faced at that moment. Phase 1 uses it for
 * "turn on the spot": the body blocks the signal from behind, so the readings are strongest while
 * the person faces the ring. Phase 2 adds steps to the same record for the walked mini map.
 *
 * Headings come from the phone's gyroscope based orientation ({@code GAME_ROTATION_VECTOR}), so
 * they are relative to where the person started, not to north.
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

    /** One signal reading with where the person faced. */
    public static final class Sample {
        public final long t;
        public final double heading, rssi;
        public final boolean upright;

        Sample(long t, double heading, double rssi, boolean upright) {
            this.t = t;
            this.heading = heading;
            this.rssi = rssi;
            this.upright = upright;
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
    private final boolean[] faced = new boolean[SECTORS];
    private double heading = Double.NaN;
    private boolean upright;
    private long headingAt = Long.MIN_VALUE;

    /** The newest orientation: heading in degrees and whether the phone is upright enough. */
    public void heading(long atMs, double degrees, boolean upright) {
        heading = norm(degrees);
        this.upright = upright;
        headingAt = atMs;
        if (upright) faced[sector(heading)] = true;
    }

    /** Records a signal reading; returns whether it counts (a fresh heading, phone upright). */
    public boolean rssi(long atMs, double dBm) {
        if (Double.isNaN(heading) || atMs - headingAt > HEADING_MAX_AGE_MS || atMs < headingAt - HEADING_MAX_AGE_MS) return false;
        samples.add(new Sample(atMs, heading, dBm, upright));
        return upright;
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
