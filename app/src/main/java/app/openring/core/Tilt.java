package app.openring.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the ring's accelerometer stream into a drift free analog stick, the way open_health's
 * Ring Runner does: smooth the gravity vector, capture a neutral pose while the hand rests, then
 * read pitch and roll relative to it. The in plane axes are seeded from the body axis least
 * aligned with gravity so the controls never flip between calibrations.
 */
public final class Tilt {
    private double[] g;          // smoothed gravity
    private double[] u0, bR, bF; // neutral gravity and the two in plane axes
    private double[] calib = new double[3];
    private int calibN;
    public double alpha = 0.15;  // smoothing
    public double deadZoneDeg = 4;
    public double rangeDeg = 25;  // tilt for full deflection
    public boolean flipX, flipY;

    /** Live accelerometer frame (tag 0x33): up to two x/y/z samples, i16 LE (open_oura). */
    public static List<int[]> parseFrame(byte[] f) {
        List<int[]> out = new ArrayList<int[]>();
        if (f == null || f.length < 10 || (f[0] & 0xff) != 0x33) return out;
        out.add(new int[] {Protocol.Le.i16(f, 4), Protocol.Le.i16(f, 6), Protocol.Le.i16(f, 8)});
        if (f.length >= 16) out.add(new int[] {Protocol.Le.i16(f, 10), Protocol.Le.i16(f, 12), Protocol.Le.i16(f, 14)});
        return out;
    }

    public void feed(int x, int y, int z) {
        double[] raw = {x, y, z};
        if (g == null) g = raw;
        else for (int i = 0; i < 3; i++) g[i] = g[i] * (1 - alpha) + raw[i] * alpha;
    }

    public boolean hasSignal() {
        return g != null && norm(g) > 1e-6;
    }

    /** Collect the neutral pose: call while the hand rests, then {@link #finishCalibration()}. */
    public void calibrateSample() {
        if (g == null) return;
        double[] u = unit(g);
        for (int i = 0; i < 3; i++) calib[i] += u[i];
        calibN++;
    }

    public void startCalibration() {
        calib = new double[3];
        calibN = 0;
    }

    public void finishCalibration() {
        u0 = unit(calibN > 0 ? calib : (g != null ? g : new double[] {0, 1, 0}));
        double a0 = Math.abs(u0[0]), a1 = Math.abs(u0[1]), a2 = Math.abs(u0[2]);
        double[] seed = (a0 <= a1 && a0 <= a2) ? new double[] {1, 0, 0} : (a1 <= a2) ? new double[] {0, 1, 0} : new double[] {0, 0, 1};
        double d = dot(seed, u0);
        bR = unit(new double[] {seed[0] - u0[0] * d, seed[1] - u0[1] * d, seed[2] - u0[2] * d});
        bF = cross(u0, bR);
    }

    public boolean calibrated() {
        return u0 != null;
    }

    /** Stick position {x, y}, each in [-1, 1]. */
    public double[] stick() {
        if (u0 == null || g == null) return new double[] {0, 0};
        double[] u = unit(g);
        double h = Math.toDegrees(Math.asin(clamp(dot(u, bR), -1, 1)));
        double v = Math.toDegrees(Math.asin(clamp(dot(u, bF), -1, 1)));
        return new double[] {(flipX ? -1 : 1) * axis(h), (flipY ? -1 : 1) * axis(v)};
    }

    double axis(double deg) {
        double a = Math.abs(deg);
        if (a < deadZoneDeg) return 0;
        return clamp(Math.signum(deg) * (a - deadZoneDeg) / rangeDeg, -1, 1);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] unit(double[] a) {
        double n = norm(a);
        return n < 1e-9 ? new double[] {0, 1, 0} : new double[] {a[0] / n, a[1] / n, a[2] / n};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
