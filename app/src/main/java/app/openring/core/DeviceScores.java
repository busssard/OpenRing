package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.Night;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.WakeUps;
import app.openring.core.analysis.Wellness;
import app.openring.core.withings.Records;
import app.openring.core.withings.WatchDays;

/**
 * The Activity, Sleep and Readiness scores without a ring (issue #379): from the watch, or (activity only) from the phone's
 * steps. Each score is the ring's own function in {@link Scores} (the same curves and weights); only its inputs come from
 * the other device, and a part the device cannot give is left out, its weight shared out by the rule {@code Scores} already
 * has for missing inputs. Nothing here is stored: the screens work it out on each load from the stored rows. The ring's
 * scores do not pass through here.
 *
 * <p>Activity goes by quarter hours of the device's own steps ({@link MainDevice#watch}, {@link MainDevice#phone}): one with
 * fewer than {@link #INACTIVE_STEPS} while worn and awake counts as 15 inactive minutes, one with {@link #BRISK_STEPS} or
 * more counts steps / {@link #BRISK_PER_MIN} brisk minutes (100 steps a minute is about 3 MET, moderate intensity), and the
 * step goal stands in for the ring's calorie goal.
 */
public final class DeviceScores {
    private DeviceScores() {}

    /** A quarter hour with fewer steps than this, worn and awake, counts as 15 inactive minutes (4 steps a minute). */
    public static final int INACTIVE_STEPS = 60;
    /** A quarter hour with at least this many steps counts as brisk (about 67 a minute or more). */
    public static final int BRISK_STEPS = 1000;
    /** Steps per brisk minute: a quarter hour of 1,200 steps counts 12 minutes. */
    public static final double BRISK_PER_MIN = 100;
    /** Days of rows the scores read: the day and 34 before it (activity balance needs 28 before the day). */
    public static final int DAYS = 35;
    /** The phone knows no night: only its quarter hours from 07:00 to 23:00 count as awake. */
    public static final int PHONE_AWAKE_FROM_MIN = 7 * 60, PHONE_AWAKE_TO_MIN = 23 * 60;
    /** Quarter hours worn for a day to count: an hour, as {@link Scores#WORN_DAY_MIN}. */
    public static final int WORN_QUARTERS = Scores.WORN_DAY_MIN / 15;

    /** The device the scores come from: the watch whenever one is there, else the phone without a ring; -1 for none. */
    public static int device(boolean ring, boolean watch, boolean phone) {
        if (watch) return MainDevice.WATCH;
        return !ring && phone ? MainDevice.PHONE : -1;
    }

    /** A part of a score the device could not give, and why. */
    public static final class Left {
        public final String key, label, why;

        Left(String key, String label, String why) {
            this.key = key;
            this.label = label;
            this.why = why;
        }
    }

    /** The three scores of one day; a score is null when the device has not enough for it, with the line that says why. */
    public static final class Result {
        public final int device;
        public Night.Score activity, sleep, readiness;
        public final List<Left> activityLeft = new ArrayList<Left>(), sleepLeft = new ArrayList<Left>(), readinessLeft = new ArrayList<Left>();
        public String activityNone, sleepNone, readinessNone;
        /** The day's own steps from the device (NaN for none) and the goal they are measured against. */
        public double steps = Double.NaN;
        public int goal;
        /** Days in the last 7 (the day included) with an hour of the device (the weekly parts need {@link Scores#MIN_WEEK_DAYS}). */
        public int days7;

        Result(int device) {
            this.device = device;
        }

        public double value(String key) {
            Night.Score s = "sleep".equals(key) ? sleep : "readiness".equals(key) ? readiness : "activity_score".equals(key) ? activity : null;
            return s == null ? Double.NaN : s.value;
        }

        public List<Left> left(String key) {
            return "sleep".equals(key) ? sleepLeft : "readiness".equals(key) ? readinessLeft : activityLeft;
        }
    }

    // ------------------------------------------------------------------------------------------------ one day of steps

    /** One day of the device as the activity parts read it. */
    public static final class Day {
        /** The device's own steps of the day (0 without any). */
        public double steps;
        /** Quarter hours the device was worn (the phone: counting). */
        public int wornQ;
        /** Inactive minutes and the sitting stretches of an hour or more between 08:00 and 22:00. */
        public double inactiveMin;
        public int longSits;
        /** Brisk and workout minutes; the watch's calories in them (NaN for the phone, it counts none). */
        public double trainingMin, trainingKcal;

        public boolean worn() {
            return wornQ >= WORN_QUARTERS;
        }
    }

    /** The quarter hour's mid point lies in a piece of the watch's sleep (any stage: in bed counts as rest, as the ring's nights). */
    private static boolean asleep(List<Records.Sleep> sleep, double mid) {
        for (Records.Sleep s : sleep) if (mid >= s.start && mid < s.start + Math.max(0, s.dur)) return true;
        return false;
    }

    /** One day from {@code from} to {@code to} (its midnights in {@code zone}) of the watch's or the phone's rows. */
    public static Day day(int device, double from, double to, ZoneId zone, MainDevice.Watch w, MainDevice.Phone p) {
        MainDevice.Source src = device == MainDevice.WATCH ? MainDevice.watch(w, from, to) : MainDevice.phone(p, from, to);
        int n = MainDevice.slots(from, to);
        double[] q = new double[n];
        for (double[] part : src.parts) {
            int k = (int) part[0];
            if (k >= 0 && k < n) q[k] += part[1];
        }
        double[] kcal = new double[n], workoutMin = new double[n];
        List<Records.Sleep> sleep = new ArrayList<Records.Sleep>();
        double workoutKcal = 0;
        if (device == MainDevice.WATCH) {
            for (Records.Steps s : w.steps) {
                if (s.start >= from && s.start < to) kcal[MainDevice.slot(s.start, from)] += s.kcal;
            }
            for (Records.Sleep s : w.sleep) if (s.start + Math.max(0, s.dur) > from && s.start < to) sleep.add(s);
            for (Records.Workout x : w.workouts) {
                if (x.start < from || x.start >= to) continue;
                workoutKcal += x.kcal;
                double a = x.start, b = x.start + Math.max(0, x.dur);
                for (int k = Math.max(0, MainDevice.slot(a, from)); k < n; k++) {
                    double qa = from + k * MainDevice.SLOT_S, qb = qa + MainDevice.SLOT_S;
                    if (qa >= b) break;
                    workoutMin[k] += Math.max(0, Math.min(b, qb) - Math.max(a, qa)) / 60.0;
                }
            }
        }
        Day d = new Day();
        d.steps = Double.isNaN(src.steps) ? 0 : src.steps;
        double brisk = 0;
        int sitting = 0; // sitting minutes of the current stretch between 08:00 and 22:00, as Wellness.day counts them
        for (int k = 0; k < n; k++) {
            if (src.worn[k]) d.wornQ++;
            double a = from + k * MainDevice.SLOT_S;
            int clock = Wellness.clockMin(a, from, zone);
            boolean awake = device == MainDevice.WATCH ? !asleep(sleep, a + MainDevice.SLOT_S / 2)
                    : clock >= PHONE_AWAKE_FROM_MIN && clock < PHONE_AWAKE_TO_MIN;
            double bm = q[k] >= BRISK_STEPS ? Math.min(15, q[k] / BRISK_PER_MIN) : 0;
            double train = Math.max(bm, Math.min(15, workoutMin[k]));
            if (src.worn[k] || workoutMin[k] > 0) {
                d.trainingMin += train;
                if (bm > 0) brisk += kcal[k];
            }
            boolean counted = src.worn[k] && awake;
            boolean sedentary = counted && q[k] < INACTIVE_STEPS && workoutMin[k] <= 0;
            if (sedentary) d.inactiveMin += 15;
            boolean daytime = clock >= Wellness.DAYTIME_FROM_MIN && clock < Wellness.DAYTIME_TO_MIN;
            if (sedentary) {
                if (daytime) sitting += 15;
            } else {
                if (sitting >= 60) d.longSits++;
                sitting = 0;
            }
        }
        if (sitting >= 60) d.longSits++;
        d.trainingKcal = device == MainDevice.WATCH ? brisk + workoutKcal : Double.NaN;
        return d;
    }

    // ------------------------------------------------------------------------------------------------ the three scores

    /**
     * The scores of {@code date} from {@code device} ({@link MainDevice#WATCH} or {@link MainDevice#PHONE}), with the rows of the
     * {@link #DAYS} days up to it ({@code watch} or {@code phone}, null for none), its days in {@code zone}, the step goal and the
     * person's age (as the ring's sleep score uses it).
     */
    public static Result of(int device, LocalDate date, final ZoneId zone, MainDevice.Watch watch, MainDevice.Phone phone, int goal, double age) {
        Result r = new Result(device);
        r.goal = goal > 0 ? goal : 10000;
        if (watch == null) watch = new MainDevice.Watch();
        if (phone == null) phone = new MainDevice.Phone();
        boolean isWatch = device == MainDevice.WATCH;
        String dev = isWatch ? "the watch" : "this phone";
        // the days, oldest first; the last is the day itself
        Day[] days = new Day[DAYS];
        for (int i = 0; i < DAYS; i++) {
            LocalDate di = date.minusDays(DAYS - 1 - i);
            double from = di.atStartOfDay(zone).toEpochSecond(), to = di.plusDays(1).atStartOfDay(zone).toEpochSecond();
            days[i] = day(device, from, to, zone, watch, phone);
        }
        Day today = days[DAYS - 1];
        r.steps = today.wornQ > 0 || today.steps > 0 ? today.steps : Double.NaN;
        activity(r, days, dev);
        if (!isWatch) {
            r.sleepNone = PHONE_NO_NIGHT;
            r.readinessNone = PHONE_NO_NIGHT;
            return r;
        }
        WatchDays.Zones z = new WatchDays.Zones() {
            @Override
            public ZoneId at(double unix) {
                return zone;
            }
        };
        WatchDays.Night[] nights = new WatchDays.Night[DAYS];
        for (int i = 0; i < DAYS; i++) nights[i] = watch.sleep.isEmpty() ? null : WatchDays.nightFor(watch.sleep, date.minusDays(DAYS - 1 - i), z);
        WatchDays.Night wn = nights[DAYS - 1];
        if (wn == null) {
            r.sleepNone = WATCH_NO_NIGHT;
            r.readinessNone = WATCH_NO_READINESS;
            return r;
        }
        Night n = night(wn);
        r.sleep = Scores.sleep(n, zone.getRules().getOffset(Instant.ofEpochSecond((long) Math.floor(Double.isNaN(n.midpoint) ? wn.end : n.midpoint))).getTotalSeconds(), age);
        if (r.sleep.value < 0) {
            r.sleep = null;
            r.sleepNone = WATCH_NO_NIGHT;
            r.readinessNone = WATCH_NO_READINESS;
            return r;
        }
        r.sleepLeft.add(new Left("restless", "Restless movement (part of Restfulness)", "The watch sends no movement during the night."));
        leftOut(r.sleep, SLEEP_PARTS, r.sleepLeft, dev);
        readiness(r, days, nights, watch, dev);
        return r;
    }

    /** Withings' night as the ring's night fields, by the ring's rules ({@code NightAnalyzer}) on whole minutes. */
    static Night night(WatchDays.Night wn) {
        Night n = new Night();
        n.start = wn.start;
        n.end = wn.end;
        n.stageSource = "watch";
        int[] st = wn.stages;
        n.inBedMin = st.length;
        n.deepMin = wn.deepMin;
        n.lightMin = wn.lightMin;
        n.remMin = wn.remMin;
        n.awakeMin = wn.awakeMin;
        n.asleepMin = wn.asleepMin();
        int onset = -1, last = -1;
        for (int i = 0; i < st.length; i++) {
            if (st[i] >= 1 && st[i] <= 3) {
                if (onset < 0) onset = i;
                last = i;
            }
        }
        if (onset < 0) return n; // no sleep at all: no efficiency, wake time or latency, as on the ring
        n.efficiency = 100.0 * n.asleepMin / st.length;
        // latency only when the watch marked time awake before the first sleep: a night that starts asleep has no bedtime
        if (st[0] == 4) n.latencyMin = onset;
        n.sleepStart = n.start + onset * 60.0;
        n.sleepEnd = n.start + (last + 1) * 60.0;
        n.midpoint = (n.sleepStart + n.sleepEnd) / 2;
        int waso = 0;
        for (int i = onset; i <= last; i++) if (st[i] == 4) waso++;
        n.wasoMin = waso;
        n.awakenings = WakeUps.count(st, 60);
        return n;
    }

    private static void activity(Result r, Day[] days, String dev) {
        Day today = days[days.length - 1];
        if (!today.worn()) {
            r.activityNone = r.device == MainDevice.WATCH ? WATCH_NO_ACTIVITY : PHONE_NO_ACTIVITY;
            return;
        }
        Scores.ActivityInput ai = new Scores.ActivityInput();
        ai.inactiveMin = today.inactiveMin;
        ai.longInactiveStreaks = today.longSits;
        int n7 = 0, met = 0, train = 0;
        double kcal = 0;
        for (int i = days.length - 7; i < days.length; i++) {
            Day d = days[i];
            if (!d.worn()) continue;
            n7++;
            if (d.steps >= r.goal) met++;
            if (d.trainingMin >= 30) train++;
            kcal += Double.isNaN(d.trainingKcal) ? 0 : d.trainingKcal;
        }
        r.days7 = n7;
        ai.days7 = n7;
        ai.targetsMetFraction = met / (double) n7;
        ai.trainingDays7 = train;
        ai.trainingKcal7 = r.device == MainDevice.WATCH ? kcal : Double.NaN;
        r.activity = Scores.activity(ai);
        // the step goal stands in for the ring's calorie goal: the detail says which
        Night.Contributor t = r.activity.get("targets");
        if (t != null) replace(r.activity, t, t.detail + String.format(Locale.US, " at %,d steps", r.goal));
        if (r.device == MainDevice.PHONE) r.activityLeft.add(new Left("volume", "Training volume", "This phone counts no calories."));
        leftOut(r.activity, ACTIVITY_PARTS, r.activityLeft, dev);
    }

    private static void readiness(Result r, Day[] days, WatchDays.Night[] nights, MainDevice.Watch watch, String dev) {
        int last = days.length - 1;
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        ri.sleepScore = r.sleep.value >= 0 ? r.sleep.value : Double.NaN;
        double[] lowest = new double[nights.length];
        int before = 0;
        for (int i = 0; i < nights.length; i++) {
            lowest[i] = Double.NaN;
            if (nights[i] == null) continue;
            if (i < last) before++;
            WatchDays.NightHeart h = WatchDays.heartIn(watch.heart, nights[i].start, nights[i].end);
            if (h.lowest > 0) lowest[i] = h.lowest;
        }
        ri.historyNights = before;
        ri.rhr = lowest[last];
        double[] rhr14 = java.util.Arrays.copyOfRange(lowest, last - 14, last);
        if (Stats.countFinite(rhr14) >= 3) {
            ri.rhrBaseline = Stats.mean(rhr14);
            ri.rhrSd = Stats.sd(rhr14);
        }
        // sleep balance: the last 14 days of the watch's sleep against the need from the days before each (Analyzer's rule)
        double[] asleep14 = new double[14];
        for (int i = 0; i < 14; i++) asleep14[i] = nights[last - i] == null ? Double.NaN : nights[last - i].asleepMin() * 60.0;
        double[] prior = new double[90];
        java.util.Arrays.fill(prior, Double.NaN);
        for (int k = 0; k < 90 && last - 1 - k >= 0; k++) prior[k] = nights[last - 1 - k] == null ? Double.NaN : nights[last - 1 - k].asleepMin() * 60.0;
        double avg = Stats.mean(asleep14);
        if (!Double.isNaN(avg) && Stats.countFinite(asleep14) >= 3) ri.sleepBalance = avg / Wellness.sleepNeedS(prior);
        // the activity parts: steps against the step goal in place of the ring's calories against its goal
        double[] steps = new double[last], goal = new double[last], covered = new double[last];
        for (int i = 0; i < last; i++) {
            steps[i] = days[i].steps;
            goal[i] = r.goal;
            covered[i] = days[i].wornQ * 15.0;
        }
        Scores.readinessActivity(ri, steps, goal, covered, true);
        r.readiness = Scores.readiness(ri);
        // the ring leaves these two details empty; here they show the steps they compare (the evidence)
        Night.Contributor prev = r.readiness.get("prev_activity"), bal = r.readiness.get("activity_balance");
        if (prev != null) replace(r.readiness, prev, String.format(Locale.US, "%,d steps of %,d", Math.round(steps[last - 1]), r.goal));
        if (bal != null) replace(r.readiness, bal, Math.round(ri.activityBalance * 100) + "% of your usual steps");
        r.readinessLeft.add(new Left("hrv", "HRV balance", "The watch has no sensor for heart rate variability."));
        r.readinessLeft.add(new Left("temperature", "Body temperature", "The watch has no temperature sensor."));
        r.readinessLeft.add(new Left("recovery", "Recovery index", "The watch reads heart rate only every 10 to 30 minutes, too rough for when it was lowest."));
        leftOut(r.readiness, READINESS_PARTS, r.readinessLeft, dev);
    }

    /** The contributor {@code c} of {@code sc} with another detail, in its place (score and weight as they were). */
    private static void replace(Night.Score sc, Night.Contributor c, String detail) {
        int i = sc.contributors.indexOf(c);
        sc.contributors.set(i, new Night.Contributor(c.key, c.label, c.score, c.weight, detail));
    }

    /** Adds every part of {@code parts} the score lacks and {@code out} does not name yet, with why it is missing. */
    private static void leftOut(Night.Score sc, String[][] parts, List<Left> out, String dev) {
        for (String[] p : parts) {
            if (sc.get(p[0]) != null) continue;
            boolean named = false;
            for (Left l : out) named |= l.key.equals(p[0]);
            if (!named) out.add(new Left(p[0], p[1], String.format(Locale.US, p[2], dev)));
        }
    }

    /** Each score's parts as the ring has them: key, label, why it can be missing here ({@code %s} names the device). */
    static final String[][] ACTIVITY_PARTS = {
        {"stay_active", "Stay active", "Needs an hour with %s today."},
        {"move_hourly", "Move every hour", "Needs an hour with %s today."},
        {"targets", "Meet daily targets", "Needs 3 days with %s in the last 7."},
        {"frequency", "Training frequency", "Needs 3 days with %s in the last 7."},
        {"volume", "Training volume", "Needs 3 days with %s in the last 7."}};
    static final String[][] SLEEP_PARTS = {
        {"total", "Total sleep", "No sleep from %s."},
        {"efficiency", "Efficiency", "No time asleep in the night."},
        {"restfulness", "Restfulness", "No time asleep in the night."},
        {"rem", "REM sleep", "No stages from %s."},
        {"deep", "Deep sleep", "No stages from %s."},
        {"latency", "Latency", "The night starts asleep, so %s did not mark when you went to bed."},
        {"timing", "Timing", "No time asleep in the night."}};
    static final String[][] READINESS_PARTS = {
        {"rhr", "Resting heart rate", "No heart rate from %s this night."},
        {"sleep", "Sleep", "No sleep score this night."},
        {"sleep_balance", "Sleep balance", "Needs 3 nights with %s in the last 2 weeks."},
        {"prev_activity", "Previous day activity", "Needs an hour with %s yesterday."},
        {"activity_balance", "Activity balance", "Needs 3 days with %s in the last week and 7 in the last 4."}};

    // ------------------------------------------------------------------------------------------------ texts

    public static final String FROM_WATCH = "From your watch";
    public static final String FROM_PHONE = "From this phone's steps";
    public static final String FEWER = "From your watch, with fewer signals than with a ring";
    /** Today's line under the dials. */
    public static final String TODAY_WATCH = "Scores from your watch, with fewer signals than with a ring. Tap one to see what it used.";
    public static final String TODAY_PHONE = "Activity score from this phone's steps. Sleep and readiness need a ring or a watch.";
    public static final String WATCH_NO_NIGHT = Start.NO_WATCH_NIGHT;
    public static final String WATCH_NO_READINESS = "Readiness from your watch shows the morning after a night with the watch on.";
    public static final String PHONE_NO_NIGHT = "Sleep and readiness need a ring or a watch.";
    public static final String WATCH_NO_ACTIVITY = "The activity score needs an hour with your watch on this day.";
    public static final String PHONE_NO_ACTIVITY = "The activity score needs an hour of steps from this phone on this day.";
    public static final String LEFT_TITLE = "Left out";
    public static final String NOTHING_LEFT = "Nothing left out. Steps stand in for the ring's movement.";
    /** On Ready while fewer than 14 nights from the watch are known. */
    public static final String PROVISIONAL = "Your usual is still forming. Readiness compares each night with your own normal, which takes about "
            + "two weeks of nights with the watch to settle, so early scores move more.";
    /** On Ready with only the watch, over the button that adds a ring. */
    public static final String RING_ADDS = "A ring adds HRV, body temperature and the recovery index to readiness.";

    /** "Watch version: 78", the line under a ring's score when the watch has one too. */
    public static String version(double v) {
        return "Watch version: " + Math.round(v);
    }

    /** The line under a score's dial: where it comes from. */
    public static String from(int device, String key) {
        if (device == MainDevice.PHONE) return FROM_PHONE;
        return "readiness".equals(key) ? FEWER : FROM_WATCH;
    }

    /** The goal line on Activity: "8,412 of 10,000 steps goal". */
    public static String goalLine(double steps, int goal, Locale l) {
        return String.format(l, "%,d of %,d steps goal", Math.round(Double.isNaN(steps) ? 0 : steps), goal);
    }

    /** Every fixed text above and the reasons, for the tests (plain, no dashes as punctuation). */
    public static List<String> texts() {
        List<String> out = new ArrayList<String>();
        String[] fixed = {FROM_WATCH, FROM_PHONE, FEWER, TODAY_WATCH, TODAY_PHONE, WATCH_NO_NIGHT, WATCH_NO_READINESS, PHONE_NO_NIGHT,
            WATCH_NO_ACTIVITY, PHONE_NO_ACTIVITY, LEFT_TITLE, NOTHING_LEFT, RING_ADDS, PROVISIONAL, version(78), goalLine(8412, 10000, Locale.US)};
        for (String s : fixed) out.add(s);
        for (String[][] parts : new String[][][] {ACTIVITY_PARTS, SLEEP_PARTS, READINESS_PARTS}) {
            for (String[] p : parts) {
                out.add(p[1]);
                out.add(String.format(Locale.US, p[2], "the watch"));
                out.add(String.format(Locale.US, p[2], "this phone"));
            }
        }
        return out;
    }
}
