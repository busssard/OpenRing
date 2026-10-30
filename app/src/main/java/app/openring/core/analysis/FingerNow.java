package app.openring.core.analysis;

import java.util.List;
import java.util.Locale;

import app.openring.core.EventCodec;
import app.openring.core.RingEvent;

/**
 * Whether the ring is on a finger right now (issue #192): Find my ring asks the ring once and reads the answer from
 * what it just downloaded. A finger reads 27 °C or more and has a heart rate near it (within {@link #NEAR_S} of the
 * newest temperature, or of now); warmth alone can also be the charger (issue #94), and a heart rate stored just before
 * the ring was taken off and laid on a warm charger must not vouch for it (review of #245). A heart rate in the last
 * {@link #NEAR_S} means a finger also with cool skin (cold hands, like {@link LastWorn}); an older one with cool skin
 * leaves it open (cold hands or just taken off). Only readings of the last {@link #FRESH_S} count, the window
 * {@code data/RightNow} uses.
 */
public final class FingerNow {
    public static final double WORN_MIN_C = LastWorn.WORN_MIN_C;
    /** A reading this old (seconds) still counts as now. */
    public static final double FRESH_S = 20 * 60;
    /**
     * A heart rate counts for a warm reading this close to it, or to now (seconds); with cool skin only one this recent
     * means a finger (cold hands).
     */
    public static final double NEAR_S = LastWorn.HR_GRACE_S;

    /**
     * ON a finger; OFF not; WARM without a heart rate near it (finger or charger); CHARGER warm and the battery went up
     * (probably the charger); COOL with a heart rate 5 to 20 minutes old (cold hands or just taken off); NO_READING no
     * temperature of the last 20 minutes.
     */
    public static final int NO_READING = 0, ON = 1, OFF = 2, WARM = 3, CHARGER = 4, COOL = 5;

    /** The newest skin temperature (°C) and heart rate (bpm), with their ages in seconds when asked; NaN when none. */
    public double skinC = Double.NaN, skinAge = Double.NaN, hr = Double.NaN, hrAge = Double.NaN;
    /** When the ring was asked (unix seconds). */
    public double at = Double.NaN;
    /** A made up answer of demo mode. */
    public boolean demo;
    /**
     * The battery read with this answer against the read before ({@link LastWorn#charging}): 1 it went up (charging),
     * 0 it went down, -1 unknown. Decides a warm ring without a heart rate near it.
     */
    public int charging = -1;

    public static FingerNow of(double skinC, double skinAge, double hr, double hrAge, double at) {
        FingerNow f = new FingerNow();
        f.skinC = skinC;
        f.skinAge = Double.isNaN(skinC) ? Double.NaN : Math.max(0, skinAge);
        f.hr = hr;
        f.hrAge = Double.isNaN(hr) ? Double.NaN : Math.max(0, hrAge);
        f.at = at;
        return f;
    }

    /**
     * The demo's made up answer: the ring lies on the floor of the room the demo walk finds it in ({@link DemoWalk}),
     * so it reads the room's temperature and has no heart rate.
     */
    public static FingerNow demo(double at) {
        FingerNow f = of(22.6, 70, Double.NaN, Double.NaN, at);
        f.demo = true;
        return f;
    }

    static boolean within(double age, double limit) {
        return !Double.isNaN(age) && age <= limit;
    }

    public int state() {
        boolean temp = within(skinAge, FRESH_S), hrNow = within(hrAge, NEAR_S);
        if (temp && skinC >= WORN_MIN_C) {
            // the heart rate must belong to this warmth: near the newest temperature or measured just now
            if (hrNow || within(hrAge, FRESH_S) && Math.abs(hrAge - skinAge) <= NEAR_S) return ON;
            return charging == 1 ? CHARGER : WARM;
        }
        if (hrNow) return ON;
        if (!temp) return NO_READING;
        return within(hrAge, FRESH_S) ? COOL : OFF;
    }

    /** The line of the last worn strip: one short sentence (it shares the strip with a chip). */
    public String brief() {
        switch (state()) {
            case ON:
                if (!within(skinAge, FRESH_S)) return "On a finger now (heart rate " + bpm(hr) + ").";
                if (skinC < WORN_MIN_C) return "On a finger now (heart rate " + bpm(hr) + ", cool skin " + one(skinC) + " °C).";
                return "On a finger now (" + one(skinC) + " °C, heart rate " + bpm(hr) + ").";
            case OFF:
                return "Not on a finger now (" + one(skinC) + " °C).";
            case WARM:
                return "Warm (" + one(skinC) + " °C), no heart rate: finger or charger.";
            case CHARGER:
                return "Warm (" + one(skinC) + " °C), battery rising: probably the charger.";
            case COOL:
                return "Cool (" + one(skinC) + " °C), heart rate " + (long) Math.floor(hrAge / 60) + " minutes ago: cold hands or just taken off.";
            default:
                return "No reading from the last 20 minutes to tell.";
        }
    }

    /** The evidence and the rule, for the opened strip. */
    public String detail(LastWorn.Clock clock) {
        StringBuilder b = new StringBuilder();
        b.append("Asked the ring ").append(clock.when(at)).append(". ");
        b.append(Double.isNaN(skinC) ? "No skin temperature" : "Newest skin temperature " + one(skinC) + " °C (" + ago(skinAge) + ")");
        b.append(", ");
        b.append(Double.isNaN(hr) ? "no heart rate" : "newest heart rate " + bpm(hr) + " bpm (" + ago(hrAge) + ")");
        b.append(". ");
        switch (state()) {
            case ON:
                b.append(skinC < WORN_MIN_C && within(skinAge, FRESH_S) ? "The skin reads cool, but a heart rate in the last 5 minutes means a finger (cold hands)."
                        : "A finger reads 27 °C or more and has a heart rate within 5 minutes of it.");
                break;
            case OFF:
                b.append("Under 27 °C without any heart rate in the last 20 minutes is a ring lying somewhere, at about the room's temperature.");
                break;
            case WARM:
                b.append("Warm without a heart rate within 5 minutes of it can be a finger (the ring measures heart rate only now and then) or the charger. "
                        + "Make it blink measures one.");
                break;
            case CHARGER:
                b.append("Warm without a heart rate within 5 minutes of it, and the battery went up since the read before, so it is probably on the charger. "
                        + "Make it blink measures a heart rate if it is on a finger.");
                break;
            case COOL:
                b.append("Under 27 °C with a heart rate 5 to 20 minutes before: cold hands, or it was taken off since. "
                        + "Make it blink tells: on a finger it measures a heart rate.");
                break;
            default:
                b.append("The ring stored no temperature in the last 20 minutes, so it cannot tell whether it is on a finger now.");
        }
        if (demo) b.append(" In the demo this answer is made up.");
        return b.toString();
    }

    /** "just before", "a minute before", "3 minutes before", "2 hours before". */
    static String ago(double s) {
        if (s < 60) return "just before";
        if (s < 120) return "a minute before";
        if (s < 90 * 60) return (long) Math.floor(s / 60) + " minutes before";
        return Math.round(s / 3600) + " hours before";
    }

    static String one(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    static String bpm(double v) {
        return String.valueOf(Math.round(v));
    }

    /**
     * Reads the newest skin temperature and heart rate from downloaded events in the order the ring sent them. The
     * newest event of a finished download happened about now (the rule of the phone anchor), so a reading's age is its
     * distance in ring time (deciseconds) to that event. A jump back of the ring's counter by more than a minute is a
     * reboot: the readings before it have no common clock with the ones after and are dropped. Heart rates are dated
     * like {@code data/RightNow}: HRV value i at start + i × 300 s, always on heart rate at + i × 1.92 s, a green IBI
     * beat at its start; only plausible values count ({@link EventCodec#plausibleHr}, {@link EventCodec#plausibleIbi}).
     */
    public static final class Reader {
        /** A jump back of the counter by more than this (deciseconds) is a reboot. */
        static final long REBOOT_DS = 600;
        private long lastDs = Long.MIN_VALUE, tempDs = Long.MIN_VALUE;
        private double temp = Double.NaN, hr = Double.NaN, hrDs = Double.NaN;
        private int events;
        /** The phone's time (unix seconds) when the newest event was "now", NaN until a download finished. */
        private double markedAt = Double.NaN;

        /**
         * A download finished at {@code unix}: its newest event happened about then. A reader kept for the next ask (the
         * events of this one are stored undated until the next analysis, review of #245) then ages its readings by the
         * phone time that passed since.
         */
        public void mark(double unix) {
            markedAt = unix;
        }

        public void add(List<RingEvent> list) {
            for (RingEvent e : list) add(e);
        }

        public void add(RingEvent e) {
            if (lastDs != Long.MIN_VALUE && e.ds < lastDs - REBOOT_DS) {
                tempDs = Long.MIN_VALUE;
                temp = hr = hrDs = Double.NaN;
                lastDs = Long.MIN_VALUE;
            }
            lastDs = Math.max(lastDs, e.ds);
            events++;
            if (e.tag == EventCodec.TEMP_EVENT || e.tag == EventCodec.SLEEP_TEMP_EVENT) {
                double[] t = EventCodec.temps(e.body);
                if (t != null && t.length > 0 && e.ds >= tempDs) {
                    tempDs = e.ds;
                    temp = t[0];
                }
                return;
            }
            double[] v = newestHr(e);
            if (v != null && (Double.isNaN(hrDs) || v[1] >= hrDs)) {
                hr = v[0];
                hrDs = v[1];
            }
        }

        /** Events read since the start (also before a reboot). */
        public int events() {
            return events;
        }

        /**
         * The answer at {@code at} (unix seconds): of each reading the newer one of what the downloads held and the stored,
         * dated readings ({@code storedSkinAt} and {@code storedHrAt} in unix seconds, NaN when none; a sync since the last
         * ask can have stored newer ones).
         */
        public FingerNow result(double at, double storedSkinC, double storedSkinAt, double storedHr, double storedHrAt) {
            double since = Double.isNaN(markedAt) ? 0 : Math.max(0, at - markedAt);
            double tAge = Double.isNaN(temp) ? Double.NaN : (lastDs - tempDs) / 10.0 + since;
            double hAge = Double.isNaN(hr) ? Double.NaN : (lastDs - hrDs) / 10.0 + since;
            double sAge = Double.isNaN(storedSkinC) ? Double.NaN : at - storedSkinAt, shAge = Double.isNaN(storedHr) ? Double.NaN : at - storedHrAt;
            boolean t = !Double.isNaN(tAge) && !(sAge < tAge), h = !Double.isNaN(hAge) && !(shAge < hAge);
            return of(t ? temp : storedSkinC, t ? tAge : sAge, h ? hr : storedHr, h ? hAge : shAge, at);
        }
    }

    /** {bpm, ds} of the newest plausible heart rate in one event, or null. */
    static double[] newestHr(RingEvent e) {
        if (e.tag == EventCodec.GREEN_IBI_QUALITY) {
            int[][] g = EventCodec.greenIbi(e.body);
            if (g != null) for (int i = g[0].length - 1; i >= 0; i--) if (g[1][i] == 1 && EventCodec.plausibleIbi(g[0][i])) return new double[] {60000.0 / g[0][i], e.ds};
        } else if (e.tag == EventCodec.AOHR_EVENT) {
            int[][] a = EventCodec.aohr(e.body);
            if (a != null) for (int i = a[0].length - 1; i >= 0; i--) if (EventCodec.plausibleHr(a[0][i])) return new double[] {a[0][i], e.ds + i * EventCodec.AOHR_INTERVAL_S * 10};
        } else if (e.tag == EventCodec.HRV_EVENT) {
            int[][] h = EventCodec.hrv(e.body);
            if (h != null) for (int i = h[0].length - 1; i >= 0; i--) if (EventCodec.plausibleHr(h[0][i])) return new double[] {h[0][i], e.ds + i * EventCodec.HRV_INTERVAL_S * 10.0};
        }
        return null;
    }
}
