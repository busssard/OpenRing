package app.openring.data;

import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import app.openring.core.EventCodec;
import app.openring.core.Protocol;
import app.openring.core.RingEvent;

/**
 * Demo mode: a made up person's ring history, encoded exactly the way a ring stores it, so the
 * whole real pipeline (dating, sleep detection, staging, scores) runs on it. It lives in its own
 * database file; switching demo off brings back the real data untouched.
 */
public final class Demo {
    private Demo() {}

    public interface Progress {
        void step(String what);
    }

    /** The made up navigation app, left out of the demo's screen time to show how that works. */
    public static final String MAPS = "demo.maps";
    static final String[] APPS = {"demo.messages", "demo.browser", "demo.social", "demo.mail", "demo.music", MAPS, "demo.video"};
    /** How often each of {@link #APPS} is picked for a daytime session. */
    static final double[] SHARE = {0.30, 0.18, 0.22, 0.12, 0.07, 0.06, 0.05};

    public static final long DS0 = 3L * 86400 * 10; // the made up ring booted 3 days before the history starts

    /** Switch to the demo database and fill it if it is empty. Blocks; call from a background thread. */
    public static void enter(Context c, Progress p) {
        enter(c, p, LocalDate.now(), System.currentTimeMillis() / 1000);
    }

    /** As above with a chosen "today" and "now" (both in the past), so tests can cover any date and hour. */
    public static void enter(Context c, Progress p, LocalDate today, long now) {
        Prefs.of(c).setDemo(true);
        Db db = Db.get(c);
        if (db.eventCount() > 0 && db.latestDay() != null && db.latestDay().equals(today.toString())) return;
        if (p != null) p.step("Making up 5 weeks of nights and days");
        db.resetAll();
        ZoneId zone = ZoneId.systemDefault();
        int days = 35;
        long t0 = today.minusDays(days).atStartOfDay(zone).toEpochSecond();
        List<double[]> screen = new ArrayList<double[]>();
        java.util.Map<String, List<double[]>> apps = new java.util.LinkedHashMap<String, List<double[]>>();
        List<RingEvent> ev = history(t0, days, now, zone, new Random(7), screen, apps);
        long lastDs = ev.get(ev.size() - 1).ds;
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), 1, now);
        db.insertPhoneAnchor(1, lastDs, t0 + (lastDs - DS0) / 10);
        db.putScreen(screen);
        db.putApps(apps);
        if (p != null) p.step("Analysing the demo nights on this phone");
        Analyzer.runAll(c, null);
    }

    public static void leave(Context c) {
        Prefs.of(c).setDemo(false);
        Db.get(c); // reopens the real database
    }

    /**
     * Unix seconds of a clock time on {@code day}: {@code hours} after its local midnight on the
     * clock (negative for the evening before). Keeps the made up person on clock time across a
     * daylight saving switch (issue #22).
     */
    static double at(LocalDate day, double hours, ZoneId zone) {
        long s = (long) Math.floor(hours * 3600);
        double frac = hours * 3600 - s;
        return day.atStartOfDay().plusSeconds(s).atZone(zone).toEpochSecond() + frac;
    }

    /** Clock hours of {@code unix} after the local midnight that starts {@code day} (can pass 24). */
    static double clockHours(LocalDate day, double unix, ZoneId zone) {
        java.time.LocalDateTime t = java.time.Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone).toLocalDateTime();
        return java.time.Duration.between(day.atStartOfDay(), t).getSeconds() / 3600.0 + (unix - Math.floor(unix)) / 3600;
    }

    static long ds(long t0, double unix) {
        return DS0 + Math.round((unix - t0) * 10);
    }

    static byte[] le16(int... v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            b[2 * i] = (byte) v[i];
            b[2 * i + 1] = (byte) (v[i] >> 8);
        }
        return b;
    }

    /** Made up ring events for {@code days} nights after {@code t0} (public for the pipeline test). */
    public static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r) {
        return history(t0, days, now, zone, r, null, null);
    }

    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen) {
        return history(t0, days, now, zone, r, screen, null);
    }

    /** As above; when {@code screen} is not null it also gets made up phone screen sessions (issue #13). */
    static List<RingEvent> history(long t0, int days, long now, ZoneId zone, Random r, List<double[]> screen,
            java.util.Map<String, List<double[]>> apps) {
        List<RingEvent> ev = new ArrayList<RingEvent>();
        List<Object[]> kept = new ArrayList<Object[]>();
        LocalDate first = java.time.Instant.ofEpochSecond(t0).atZone(zone).toLocalDate();
        for (int d = 1; d <= days; d++) {
            // every time is a clock time on this day, so a daylight saving switch does not move it (issue #22)
            LocalDate day = first.plusDays(d);
            int dow = day.getDayOfWeek().getValue(); // 6, 7 weekend mornings
            boolean weekend = dow >= 6;
            boolean late = r.nextDouble() < 0.12;
            double bed = at(day, -(1.4 - (late ? 1.6 : 0) - (weekend ? 0.4 : 0)), zone) + r.nextGaussian() * 1500;
            double wake = at(day, weekend ? 8.0 : 6.7, zone) + r.nextGaussian() * 1200;
            if (wake > now - 1200) wake = now - 1200;
            if (wake - bed < 3 * 3600) continue;
            double tempOffset = r.nextGaussian() * 0.12 + (d > days - 4 && d < days - 1 ? 0.35 : 0); // a warm spell lately
            double fitness = d * 0.06; // resting HR slowly improves
            // a few wake ups per night, longer on restless nights
            int bouts = r.nextInt(3) + (r.nextDouble() < 0.25 ? 2 : 0);
            double[][] wakeBouts = new double[bouts][2];
            for (int k = 0; k < bouts; k++) {
                double at = bed + (0.15 + 0.75 * r.nextDouble()) * (wake - bed);
                wakeBouts[k][0] = at;
                wakeBouts[k][1] = at + (3 + r.nextInt(14)) * 60;
            }
            double onset = 8 * 60 + r.nextInt(20 * 60);
            // ---- night streams ----
            for (double t = bed; t < wake; t += 30) {
                double f = (t - bed) / (wake - bed);
                boolean awake = t < bed + onset || t > wake - 300 || inside(wakeBouts, t);
                double cyc = Math.sin(2 * Math.PI * (t - bed - onset) / (92 * 60));
                if (((long) (t - bed) / 30) % 10 == 0) {
                    int hr = (int) Math.round(58 - fitness - 8 * Math.sin(Math.PI * Math.min(1, f * 1.5)) + (awake ? 9 : 0)
                            + 2 * cyc + r.nextGaussian());
                    byte[] spi = new byte[10];
                    spi[0] = (byte) (hr * 2);
                    spi[4] = (byte) Math.round((13.8 + r.nextGaussian() * 0.5 + (awake ? 1.5 : 0)) * 8);
                    spi[6] = (byte) (awake ? 15 + r.nextInt(10) : (r.nextDouble() < 0.06 ? 4 : 0));
                    spi[7] = (byte) (awake ? 0 : 1);
                    spi[9] = 0x10;
                    ev.add(new RingEvent(EventCodec.SLEEP_PERIOD_INFO_2, ds(t0, t), spi));
                }
                if (((long) (t - bed) / 30) % 2 == 0) {
                    int ms = awake ? 10 + r.nextInt(15) : (r.nextDouble() < (cyc > 0.6 ? 0.08 : 0.02) ? 3 + r.nextInt(4) : 0);
                    ev.add(new RingEvent(EventCodec.MOTION_EVENT, ds(t0, t), new byte[] {(byte) ms, 1, 2, 3}));
                    ev.add(new RingEvent(EventCodec.SLEEP_TEMP_EVENT, ds(t0, t),
                            le16((int) Math.round((35.25 + tempOffset + 0.2 * Math.sin(Math.PI * f) + r.nextGaussian() * 0.03) * 100))));
                }
                if (((long) (t - bed) / 30) % 4 == 0 && !awake) {
                    byte[] sp = new byte[6];
                    for (int k = 1; k < 6; k++) sp[k] = (byte) Math.max(90, Math.min(99, Math.round(96.5 + r.nextGaussian() * 0.9)));
                    ev.add(new RingEvent(EventCodec.SPO2_EVENT, ds(t0, t), sp));
                }
            }
            for (double t = bed; t < wake; t += 1800) {
                byte[] hrv = new byte[12];
                for (int k = 0; k < 6; k++) {
                    double tt = t + k * 300, f = (tt - bed) / (wake - bed);
                    boolean awake = tt < bed + onset || inside(wakeBouts, tt);
                    hrv[2 * k] = (byte) Math.round(58 - fitness - 8 * Math.sin(Math.PI * Math.min(1, f * 1.5)) + (awake ? 9 : 0));
                    hrv[2 * k + 1] = (byte) Math.max(15, Math.round(42 + 22 * f + fitness * 3 - (awake ? 12 : 0) + r.nextGaussian() * 6));
                }
                ev.add(new RingEvent(EventCodec.HRV_EVENT, ds(t0, t), hrv));
            }
            kept.add(new Object[] {d, day, bed, wake, wakeBouts});
            byte[] bt = new byte[8];
            Protocol.Le.putU32(bt, 0, ds(t0, bed));
            Protocol.Le.putU32(bt, 4, ds(t0, wake));
            ev.add(new RingEvent(EventCodec.BEDTIME_PERIOD, ds(t0, wake + 600), bt));

            // ---- daytime: activity per minute in 10 minute events, a heart beat now and then ----
            boolean workout = r.nextDouble() < 0.45;
            double workoutAt = 17 + r.nextDouble() * 2;
            double dayEnd = Math.min(now - 60, at(day, 22.3, zone));
            for (double t = wake + 600; t < dayEnd; t += 600) {
                double hour = clockHours(day, t, zone);
                byte[] met = new byte[11];
                met[0] = 1;
                for (int k = 1; k <= 10; k++) {
                    double m = 1.2 + r.nextDouble() * 0.5;
                    if (hour > 7.8 && hour < 8.3 && !weekend) m = 3.3; // walk to work
                    if (hour > 12.2 && hour < 12.6) m = 3.0;
                    if (workout && hour > workoutAt && hour < workoutAt + 0.75) m = 7 + r.nextDouble();
                    if (weekend && hour > 10 && hour < 12) m = 2.5 + r.nextDouble();
                    met[k] = (byte) Math.round(m * 10);
                }
                ev.add(new RingEvent(EventCodec.ACTIVITY_INFO, ds(t0, t), met));
                byte[] ibi = new byte[4];
                int i1 = (int) (workout && hour > workoutAt && hour < workoutAt + 0.75 ? 450 + r.nextInt(60) : 760 + r.nextInt(120));
                ibi[0] = (byte) (i1 >> 3);
                ibi[1] = (byte) ((i1 & 7) | (1 << 3));
                ibi[2] = (byte) (i1 >> 3);
                ibi[3] = (byte) ((i1 & 7) | (1 << 3));
                ev.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, ds(t0, t), ibi));
                if (t > now - 3600) ev.add(new RingEvent(EventCodec.TEMP_EVENT, ds(t0, t), le16(3312, 3300, 2900)));
            }
        }
        // phone use after the loop, so "newest" is the newest night that exists: before about 02:00
        // tonight's night is still too short and skipped, then last night gets the phone check
        if (screen != null) {
            for (int i = 0; i < kept.size(); i++) {
                Object[] k = kept.get(i);
                int d = (Integer) k[0];
                double bed = (Double) k[2], wake = (Double) k[3];
                int before = screen.size();
                screenDay(screen, new Random(1000 + d), (LocalDate) k[1], zone, bed, wake, (double[][]) k[4], now, i == kept.size() - 1);
                if (apps != null) appsFor(screen.subList(before, screen.size()), new Random(2000 + d), bed, wake, apps);
            }
        }
        Collections.sort(ev, new Comparator<RingEvent>() {
            @Override
            public int compare(RingEvent a, RingEvent b) {
                return Long.compare(a.ds, b.ds);
            }
        });
        return ev;
    }

    /**
     * Made up phone use around one night and the day after it: scrolling that ends 5 to 20 minutes
     * before bed, now and then a phone check during a longer wake up (always in the newest night), a look in the morning and
     * about 35 short to long uses during the day (about 3 hours in all). Its own random numbers, so
     * the nights stay the same as without screen time.
     */
    static void screenDay(List<double[]> out, Random r, LocalDate date, ZoneId zone, double bed, double wake, double[][] wakeBouts, long now,
            boolean newest) {
        List<double[]> day = new ArrayList<double[]>();
        double eveEnd = bed - (5 + r.nextInt(16)) * 60;
        day.add(new double[] {eveEnd - (10 + r.nextInt(26)) * 60, eveEnd});
        boolean check = false;
        for (double[] b : wakeBouts) {
            if (b[1] - b[0] >= 6 * 60 && r.nextDouble() < 0.5) {
                day.add(new double[] {b[0] + 60, b[0] + 60 + (2 + r.nextInt(3)) * 60});
                check = true;
            }
        }
        // the newest night always has one, so the screenshots show how phone use counts as awake
        if (newest && !check) {
            double at = bed + 0.55 * (wake - bed);
            day.add(new double[] {at, at + 3 * 60});
        }
        double morning = wake + (3 + r.nextInt(6)) * 60;
        day.add(new double[] {morning, morning + (4 + r.nextInt(7)) * 60});
        double from = wake + 20 * 60, to = at(date, 21, zone);
        int n = 30 + r.nextInt(12);
        double[] starts = new double[n];
        for (int i = 0; i < n; i++) starts[i] = from + r.nextDouble() * (to - from);
        java.util.Arrays.sort(starts);
        for (int i = 0; i < n; i++) {
            double k = r.nextDouble();
            double len = k < 0.7 ? 30 + r.nextDouble() * 150 : k < 0.95 ? 180 + r.nextDouble() * 540 : 900 + r.nextDouble() * 1500;
            double end = Math.min(starts[i] + len, i + 1 < n ? starts[i + 1] - 30 : to);
            if (end > starts[i] + 10) day.add(new double[] {starts[i], end});
        }
        for (double[] x : day) if (x[1] < now - 60) out.add(x);
    }

    /**
     * Made up apps for one day's sessions: social or video before bed, messages at night, mail or
     * messages in the morning, a mix during the day, and every few days a long trip with the maps
     * app. A session sometimes switches app part way. Its own random numbers, so the sessions stay
     * the same.
     */
    static void appsFor(List<double[]> sessions, Random r, double bed, double wake, java.util.Map<String, List<double[]>> apps) {
        double[] longest = null;
        for (double[] s : sessions) if (s[0] > wake + 3600 && (longest == null || s[1] - s[0] > longest[1] - longest[0])) longest = s;
        boolean trip = r.nextDouble() < 0.4;
        for (double[] s : sessions) {
            String a;
            if (s[1] <= bed) a = r.nextDouble() < 0.6 ? "demo.social" : "demo.video";
            else if (s[0] < wake) a = "demo.messages";
            else if (s[0] < wake + 3600) a = r.nextDouble() < 0.5 ? "demo.mail" : "demo.messages";
            else if (trip && s == longest) a = MAPS;
            else a = pick(r);
            double split = r.nextDouble() < 0.3 && s[1] - s[0] > 120 ? s[0] + (0.4 + 0.4 * r.nextDouble()) * (s[1] - s[0]) : s[1];
            put(apps, a, s[0], split);
            if (split < s[1]) put(apps, pick(r), split, s[1]);
        }
    }

    static String pick(Random r) {
        double x = r.nextDouble();
        for (int i = 0; i < APPS.length; i++) {
            x -= SHARE[i];
            if (x < 0) return APPS[i];
        }
        return APPS[0];
    }

    static void put(java.util.Map<String, List<double[]>> m, String a, double from, double to) {
        List<double[]> l = m.get(a);
        if (l == null) m.put(a, l = new ArrayList<double[]>());
        l.add(new double[] {from, to});
    }

    static boolean inside(double[][] bouts, double t) {
        for (double[] b : bouts) if (t >= b[0] && t < b[1]) return true;
        return false;
    }
}
