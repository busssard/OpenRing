package app.openring.core.analysis;

/**
 * Average and spread of clock times, taken on the clock face (issue #164): 15:50 and 16:10 average to
 * 16:00, 23:50 and 00:10 to 00:00. The centre is the circular mean (the direction of the average of
 * the points on the clock face); each time is then counted in minutes within 12 hours of it, and the
 * result is the plain mean and standard deviation of those minutes. For times that all lie within 12
 * hours of the centre and on the same side of 16:00 (every normal night) that is exactly the old
 * "minutes after 20:00, wrapped at 16:00" result.
 */
public final class ClockAverage {
    private ClockAverage() {}

    /** Where counting starts when the times have no centre (spread evenly round the clock): 04:00. */
    static final double FALLBACK_CENTRE_MIN = 4 * 60;

    /**
     * {mean clock minute (0 to 1440), standard deviation in minutes} of clock minutes after midnight;
     * NaN values are skipped. Mean NaN when there is none, spread NaN with fewer than two.
     */
    public static double[] of(double[] clockMin) {
        double sx = 0, sy = 0;
        int n = 0;
        for (double v : clockMin) {
            if (Double.isNaN(v) || Double.isInfinite(v)) continue;
            double a = v / 1440.0 * 2 * Math.PI;
            sx += Math.cos(a);
            sy += Math.sin(a);
            n++;
        }
        if (n == 0) return new double[] {Double.NaN, Double.NaN};
        double centre = Math.hypot(sx, sy) < 1e-9 * n ? FALLBACK_CENTRE_MIN
                : Math.atan2(sy, sx) / (2 * Math.PI) * 1440.0;
        double[] u = new double[clockMin.length];
        for (int i = 0; i < clockMin.length; i++) {
            double v = clockMin[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                u[i] = Double.NaN;
                continue;
            }
            double d = ((v - centre + 720) % 1440 + 1440) % 1440 - 720; // -720 to below 720
            u[i] = centre + d;
        }
        double mean = Stats.mean(u);
        double sd = n >= 2 ? Stats.sd(u) : Double.NaN;
        return new double[] {((mean % 1440) + 1440) % 1440, sd};
    }
}
