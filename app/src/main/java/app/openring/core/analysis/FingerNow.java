package app.openring.core.analysis;

import java.util.List;
import java.util.Locale;

import app.openring.core.EventCodec;
import app.openring.core.RingEvent;

/**
 * Whether the ring is on a finger right now (issue #192): Find my ring asks the ring once and reads the answer from
 * what it just downloaded. A finger reads 27 °C or more and has a heart rate; warmth alone can also be the charger
 * (issue #94), and a heart rate in the last few minutes means a finger also with cool skin (cold hands, like
 * {@link LastWorn}). Only readings of the last {@link #FRESH_S} count, the window {@code data/RightNow} uses.
 */
public final class FingerNow {
    public static final double WORN_MIN_C = LastWorn.WORN_MIN_C;
    /** A reading this old (seconds) still counts as now. */
    public static final double FRESH_S = 20 * 60;
    /** With cool skin, a heart rate this recent (seconds) still means a finger (cold hands). */
    public static final double COOL_HR_S = LastWorn.HR_GRACE_S;

    public static final int NO_READING = 0, ON = 1, OFF = 2, WARM = 3;

    /** The newest skin temperature (°C) and heart rate (bpm), with their ages in seconds when asked; NaN when none. */
    public double skinC = Double.NaN, skinAge = Double.NaN, hr = Double.NaN, hrAge = Double.NaN;
    /** When the ring was asked (unix seconds). */
    public double at = Double.NaN;
    /** A made up answer of demo mode. */
    public boolean demo;

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
        boolean temp = within(skinAge, FRESH_S);
        if (temp && skinC >= WORN_MIN_C) return within(hrAge, FRESH_S) ? ON : WARM;
        if (within(hrAge, COOL_HR_S)) return ON;
        return temp ? OFF : NO_READING;
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
                        : "A finger reads 27 °C or more and has a heart rate.");
                break;
            case OFF:
                b.append("Under 27 °C without a heart rate in the last 5 minutes is a ring lying somewhere, at about the room's temperature.");
                break;
            case WARM:
                b.append("Warm without a heart rate in the last 20 minutes can be a finger (the ring measures heart rate only now and then) or the charger. "
                        + "Make it blink measures one.");
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
         * The answer at {@code at} (unix seconds): what the download held, and for what it did not hold the stored,
         * dated readings ({@code storedSkinAt} and {@code storedHrAt} in unix seconds, NaN when none).
         */
        public FingerNow result(double at, double storedSkinC, double storedSkinAt, double storedHr, double storedHrAt) {
            boolean t = !Double.isNaN(temp), h = !Double.isNaN(hr);
            double skinC = t ? temp : storedSkinC, skinAge = t ? (lastDs - tempDs) / 10.0 : at - storedSkinAt;
            double bpm = h ? hr : storedHr, hrAge = h ? (lastDs - hrDs) / 10.0 : at - storedHrAt;
            return of(skinC, skinAge, bpm, hrAge, at);
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
