package app.openring.core;

import java.nio.charset.StandardCharsets;

/**
 * Decoders for history event bodies. Layouts are ported from open_oura {@code events.rs}
 * (recovered from the native libringeventparser.so) with additions from OpenStrap and the
 * ringverse Ring 4 notes. Bodies are stored raw, so improving a decoder later re decodes old data.
 */
public final class EventCodec {
    private EventCodec() {}

    public static final int RING_START = 0x41;
    public static final int TIME_SYNC = 0x42;
    public static final int DEBUG_EVENT = 0x43;
    public static final int STATE_CHANGE = 0x45;
    public static final int TEMP_EVENT = 0x46;
    public static final int MOTION_EVENT = 0x47;
    public static final int SLEEP_PHASE_INFO = 0x4b;
    public static final int SLEEP_PHASE_DETAILS = 0x4e;
    public static final int ACTIVITY_INFO = 0x50;
    public static final int WEAR_EVENT = 0x53;
    public static final int SLEEP_PHASE_DATA = 0x5a;
    public static final int USER_INFO = 0x5c;
    public static final int HRV_EVENT = 0x5d;
    public static final int IBI_AMPLITUDE = 0x60;
    public static final int DEBUG_DATA = 0x61;
    public static final int TEMP_PERIOD = 0x69;
    public static final int SLEEP_PERIOD_INFO_2 = 0x6a;
    public static final int MOTION_PERIOD = 0x6b;
    public static final int FEATURE_SESSION = 0x6c;
    public static final int SPO2_EVENT = 0x6f;
    public static final int SLEEP_ACM_PERIOD = 0x72;
    public static final int SLEEP_TEMP_EVENT = 0x75;
    public static final int BEDTIME_PERIOD = 0x76;
    public static final int GREEN_IBI_QUALITY = 0x80;
    public static final int RTC_BEACON = 0x85;
    public static final int AOHR_EVENT = 0x86;
    public static final int SPO2_R_PI = 0x8b;

    public static String name(int tag) {
        switch (tag) {
            case 0x41: return "ring_start";
            case 0x42: return "time_sync";
            case 0x43: return "debug_event";
            case 0x44: return "ibi_event";
            case 0x45: return "state_change";
            case 0x46: return "temp_event";
            case 0x47: return "motion_event";
            case 0x48: return "sleep_period_information";
            case 0x49: return "sleep_summary_1";
            case 0x4a: return "ppg_amplitude";
            case 0x4b: return "sleep_phase_information";
            case 0x4c: return "sleep_summary_2";
            case 0x4d: return "ring_sleep_feature_information";
            case 0x4e: return "sleep_phase_details";
            case 0x4f: return "sleep_summary_3";
            case 0x50: return "activity_information";
            case 0x51: return "activity_summary_1";
            case 0x52: return "activity_summary_2";
            case 0x53: return "wear_event";
            case 0x54: return "recovery_summary";
            case 0x55: return "sleep_heart_rate";
            case 0x56: return "alert_event";
            case 0x57: return "ring_sleep_feature_information_2";
            case 0x58: return "sleep_summary_4";
            case 0x59: return "eda_event";
            case 0x5a: return "sleep_phase_data";
            case 0x5b: return "ble_connection";
            case 0x5c: return "user_information";
            case 0x5d: return "hrv_event";
            case 0x5e: return "self_test_event";
            case 0x5f: return "raw_acm_event";
            case 0x60: return "ibi_and_amplitude_event";
            case 0x61: return "debug_data";
            case 0x62: return "on_demand_meas";
            case 0x63: return "ppg_peak_event";
            case 0x64: return "raw_ppg_event";
            case 0x65: return "on_demand_session";
            case 0x66: return "on_demand_motion";
            case 0x67: return "raw_ppg_summary";
            case 0x68: return "raw_ppg_data";
            case 0x69: return "temp_period";
            case 0x6a: return "sleep_period_information_2";
            case 0x6b: return "motion_period";
            case 0x6c: return "feature_session";
            case 0x6d: return "meas_quality_event";
            case 0x6e: return "spo2_ibi_and_amplitude_event";
            case 0x6f: return "spo2_event";
            case 0x70: return "spo2_smoothed_event";
            case 0x71: return "green_ibi_and_amplitude_event";
            case 0x72: return "sleep_acm_period";
            case 0x73: return "ehr_trace_event";
            case 0x74: return "ehr_acm_intensity_event";
            case 0x75: return "sleep_temp_event";
            case 0x76: return "bedtime_period";
            case 0x77: return "spo2_dc_event";
            case 0x79: return "self_test_data_event";
            case 0x7a: return "tag_event";
            case 0x7b: return "spo2_stable";
            case 0x7c: return "spo2_combo";
            case 0x7e: return "real_step_event_feature_1";
            case 0x7f: return "real_step_event_feature_2";
            case 0x80: return "green_ibi_quality_event";
            case 0x81: return "cva_raw_ppg_data";
            case 0x82: return "scan_start";
            case 0x83: return "scan_end";
            case 0x84: return "ambient_event";
            case 0x85: return "rtc_beacon";
            case 0x86: return "aohr_event";
            case 0x87: return "atlas_metadata";
            case 0x88: return "atlas_raw_bioz";
            case 0x8b: return "spo2_r_pi_event";
            case 0xfe: return "app_event";
            default: return String.format("unknown_0x%02x", tag);
        }
    }

    // ---- individual decoders ------------------------------------------------------------

    /** hrv_event: pairs of (avg HR bpm, avg RMSSD ms), one per 5 minutes. Returns {hr[], rmssd[]}. */
    public static int[][] hrv(byte[] b) {
        if (b.length == 0 || b.length % 2 != 0) return null;
        int n = b.length / 2;
        int[] hr = new int[n], rmssd = new int[n];
        for (int i = 0; i < n; i++) {
            hr[i] = b[2 * i] & 0xff;
            rmssd[i] = b[2 * i + 1] & 0xff;
        }
        return new int[][] {hr, rmssd};
    }

    public static final int HRV_INTERVAL_S = 300;
    /** Seconds between the values of an always on HR event. */
    public static final double AOHR_INTERVAL_S = 1.92;

    /**
     * The one plausibility range for a heart rate from the ring (HRV, always on HR, green IBI beats through
     * {@link #plausibleIbi}, sleep period average, issues #97 and #148): 30 to 220 bpm. Nobody's heart beats
     * faster than 220 (the top of the "220 minus age" rule) or, awake or asleep, slower than 30; values outside
     * are bytes the ring got wrong. The night's own numbers use a narrower range of their own
     * ({@code NightAnalyzer}).
     */
    public static final int HR_MIN = 30, HR_MAX = 220;

    /** True for a heart rate inside {@link #HR_MIN} to {@link #HR_MAX}. */
    public static boolean plausibleHr(double bpm) {
        return bpm >= HR_MIN && bpm <= HR_MAX;
    }

    /**
     * The same range for a beat interval (green IBI, live beats; issue #148): 60000 / 220 to 60000 / 30 ms,
     * about 273 to 2000 ms, so a beat counts exactly when its heart rate would.
     */
    public static final double IBI_MIN_MS = 60000.0 / HR_MAX, IBI_MAX_MS = 60000.0 / HR_MIN;

    /** True for a beat interval in ms inside {@link #IBI_MIN_MS} to {@link #IBI_MAX_MS}. */
    public static boolean plausibleIbi(double ms) {
        return ms >= IBI_MIN_MS && ms <= IBI_MAX_MS;
    }

    /** green_ibi_quality_event: ibi = b0<<3 | b1&7, quality = (b1>>3)&3 (1 = clean beat). */
    public static int[][] greenIbi(byte[] b) {
        if (b.length < 2) return null;
        int n = b.length / 2;
        int[] ibi = new int[n], q = new int[n];
        for (int i = 0; i < n; i++) {
            int b0 = b[2 * i] & 0xff, b1 = b[2 * i + 1] & 0xff;
            ibi[i] = (b1 & 0x07) | (b0 << 3);
            q[i] = (b1 >> 3) & 0x03;
        }
        return new int[][] {ibi, q};
    }

    /** ibi_and_amplitude_event: 14 byte packet with 6 bit packed IBIs (ms). */
    public static int[] ibiAmplitude(byte[] b) {
        if (b.length != 14) return null;
        int b12 = b[12] & 0xff, b13 = b[13] & 0xff;
        return new int[] {
            (b[6] & 1) | ((b[0] & 0xff) << 3) | ((b12 >> 5) & 6),
            (b[7] & 1) | ((b[1] & 0xff) << 3) | ((b12 >> 3) & 6),
            (b[8] & 1) | ((b[2] & 0xff) << 3) | ((b12 >> 1) & 6),
            (b[9] & 1) | ((b[3] & 0xff) << 3) | ((b12 & 3) << 1),
            (b[10] & 1) | ((b[4] & 0xff) << 3) | ((b13 >> 5) & 6),
            (b[11] & 1) | ((b[5] & 0xff) << 3) | ((b13 >> 3) & 6),
        };
    }

    /** temp_event / temp_period / sleep_temp_event: int16 LE centi degrees C. */
    public static double[] temps(byte[] b) {
        if (b.length == 0 || b.length % 2 != 0) return null;
        double[] out = new double[b.length / 2];
        for (int i = 0; i < out.length; i++) {
            double c = Protocol.Le.i16(b, 2 * i) / 100.0;
            if (c < -40 || c > 85) return null;
            out[i] = c;
        }
        return out;
    }

    /**
     * The plausibility range for an activity value from the ring (issue #120): 0 to 25 MET. The most demanding
     * activity in the Compendium of Physical Activities (Ainsworth and others, 2011) is running at 14 mph
     * (22.5 km/h), 23 MET; 25 MET is a VO2 max of about 88 ml/kg/min (1 MET is 3.5 ml/kg/min), near the most a person
     * can have ({@code Wellness.VO2MAX_HIGH}), and a ring value is the average of a whole minute. A byte decodes to
     * up to 38.2; values above the range are bytes the ring got wrong and count as missing minutes, never clamped.
     */
    public static final double MET_MIN = 0, MET_MAX = 25;

    /** True for an activity value inside {@link #MET_MIN} to {@link #MET_MAX}. */
    public static boolean plausibleMet(double met) {
        return met >= MET_MIN && met <= MET_MAX;
    }

    /** activity_information: state byte then one MET level per minute (raw; {@link #plausibleMet} decides what counts). */
    public static double[] activityMet(byte[] b) {
        if (b.length < 2) return null;
        double[] met = new double[b.length - 1];
        for (int i = 1; i < b.length; i++) {
            int v = b[i] & 0xff;
            met[i - 1] = v < 0x80 ? v * 0.1 : 12.8 + (v - 128) * 0.2;
        }
        return met;
    }

    /** spo2_event: header byte then one SpO2 percent per second; trailing 0xff is a sentinel. */
    public static int[] spo2(byte[] b) {
        if (b.length < 2) return null;
        int end = b.length;
        if ((b[end - 1] & 0xff) == 0xff) end--;
        if (end <= 1) return null;
        int[] out = new int[end - 1];
        for (int i = 1; i < end; i++) out[i - 1] = b[i] & 0xff;
        return out;
    }

    /** spo2_r_pi_event: header then 3 byte samples (R as u16 BE / 16384, PI). Returns R values. */
    public static double[] spo2R(byte[] b) {
        if (b.length < 4 || (b.length - 1) % 3 != 0) return null;
        double[] r = new double[(b.length - 1) / 3];
        for (int i = 0; i < r.length; i++) {
            int o = 1 + 3 * i;
            r[i] = (((b[o] & 0xff) << 8) | (b[o + 1] & 0xff)) / 16384.0;
        }
        return r;
    }

    /** Oura gen4 "SpO2 Simple" quadratic calibration (open_health), clamped to 85..100. */
    public static double spo2FromR(double r) {
        double v = 105.2 - 5.1 * r - 13.4 * r * r;
        return Math.max(85, Math.min(100, v));
    }

    /**
     * sleep phase pages (0x4b/0x4e/0x5a): header (page index) then 2 bit codes, MSB first.
     * Returned codes use the app convention 1 deep, 2 light, 3 REM, 4 awake.
     */
    public static int[] sleepPhases(byte[] b) {
        if (b.length < 2) return null;
        int[] out = new int[(b.length - 1) * 4];
        int k = 0;
        for (int i = 1; i < b.length; i++) {
            int v = b[i] & 0xff;
            for (int s = 6; s >= 0; s -= 2) out[k++] = ((v >> s) & 3) + 1;
        }
        return out;
    }

    /** motion_event: low 5 bits of byte 0 are motion seconds in the window. -1 if invalid. */
    public static int motionSeconds(byte[] b) {
        if (b.length < 4) return -1;
        if (b.length >= 5 && (b[4] & 0x40) != 0) return -1;
        if (b.length >= 6 && (b[5] & 0x40) != 0) return -1;
        return b[0] & 0x1f;
    }

    /** bedtime_period: start and end ring timestamps (ds). */
    public static long[] bedtime(byte[] b) {
        if (b.length < 8) return null;
        return new long[] {Protocol.Le.u32(b, 0), Protocol.Le.u32(b, 4)};
    }

    /** sleep_period_information_2: one per window sleep sample. */
    public static final class SleepPeriod {
        public double avgHr, hrTrend, breath, breathVar, cv;
        public int motionCount, sleepState;
    }

    public static SleepPeriod sleepPeriod(byte[] b) {
        if (b.length < 10) return null;
        int motion = b[6] & 0xff;
        int state = b[7];
        if (motion >= 120 || state < 0 || state >= 3) return null;
        SleepPeriod s = new SleepPeriod();
        s.avgHr = (b[0] & 0xff) * 0.5;
        s.hrTrend = b[1] * 0.0625;
        s.breath = (b[4] & 0xff) / 8.0;
        s.breathVar = (b[5] & 0xff) / 8.0;
        s.motionCount = motion;
        s.sleepState = state;
        s.cv = Protocol.Le.u16(b, 8) / 65536.0;
        return s;
    }

    /** aohr_event (always on HR): [flag][offset][n] then n x (bpm, quality), 1920 ms apart. */
    public static int[][] aohr(byte[] b) {
        if (b.length < 3) return null;
        int n = b[2] & 0xff;
        if (b.length != n * 2 + 3) return null;
        int[] bpm = new int[n], q = new int[n];
        for (int i = 0; i < n; i++) {
            bpm[i] = b[3 + 2 * i] & 0xff;
            q[i] = b[4 + 2 * i] & 0xff;
        }
        return new int[][] {bpm, q};
    }

    /** time_sync and rtc_beacon: u32 LE unix seconds at the event's ring time, or -1. */
    public static long anchorUnix(int tag, byte[] b) {
        if (tag == TIME_SYNC && b.length >= 4) return Protocol.Le.u32(b, 0);
        if (tag == RTC_BEACON && b.length >= 10) return Protocol.Le.u32(b, 0);
        return -1;
    }

    public static boolean isPhoneAnchor(byte[] b) {
        return b.length >= 9 && b[4] == 'p' && b[5] == 'h' && b[6] == 'o' && b[7] == 'n' && b[8] == 'e';
    }

    /** debug_data battery subtype (0x24): {percent, millivolts} or null. */
    public static int[] debugBattery(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xff) == 0x24 && !printable(b)) {
            return new int[] {b[1] & 0xff, Protocol.Le.u16(b, 2)};
        }
        return null;
    }

    /** user_information: {age, weight kg, sex code, height cm} (field meanings inferred). */
    public static int[] userInfo(byte[] b) {
        if (b.length < 4) return null;
        return new int[] {b[0] & 0xff, b[1] & 0xff, b[2] & 0xff, b[3] & 0xff};
    }

    static boolean printable(byte[] b) {
        for (byte x : b) {
            int v = x & 0xff;
            if (!(v == 0 || (v >= 0x20 && v < 0x7f))) return false;
        }
        return true;
    }

    static String ascii(byte[] b, int from) {
        if (from >= b.length) return "";
        return new String(b, from, b.length - from, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
    }

    /** One line human readable description for the raw event viewer and exports. */
    public static String describe(int tag, byte[] b) {
        try {
            switch (tag) {
                case RING_START:
                    if (b.length >= 14) return String.format("boot, firmware %d.%d.%d", b[5] & 0xff, b[6] & 0xff, b[7] & 0xff);
                    break;
                case TIME_SYNC:
                case RTC_BEACON: {
                    long u = anchorUnix(tag, b);
                    if (u > 0) return "unix " + u + (isPhoneAnchor(b) ? " (phone anchor)" : "");
                    break;
                }
                case DEBUG_EVENT:
                    return ascii(b, 0);
                case DEBUG_DATA: {
                    int[] bat = debugBattery(b);
                    if (bat != null) return "battery " + bat[0] + "% " + bat[1] + " mV";
                    if (printable(b)) return ascii(b, 0);
                    break;
                }
                case STATE_CHANGE:
                case WEAR_EVENT:
                    if (b.length > 0) return "state " + (b[0] & 0xff) + " " + ascii(b, 1);
                    break;
                case TEMP_EVENT:
                case TEMP_PERIOD:
                case SLEEP_TEMP_EVENT: {
                    double[] t = temps(b);
                    if (t != null) return "temps " + join(t, 2) + " C";
                    break;
                }
                case HRV_EVENT: {
                    int[][] h = hrv(b);
                    if (h != null) return "hr " + join(h[0]) + " rmssd " + join(h[1]);
                    break;
                }
                case GREEN_IBI_QUALITY: {
                    int[][] g = greenIbi(b);
                    if (g != null) return "ibi " + join(g[0]) + " q " + join(g[1]);
                    break;
                }
                case IBI_AMPLITUDE: {
                    int[] i = ibiAmplitude(b);
                    if (i != null) return "ibi " + join(i);
                    break;
                }
                case ACTIVITY_INFO: {
                    double[] m = activityMet(b);
                    if (m != null) return "met " + join(m, 1);
                    break;
                }
                case SPO2_EVENT: {
                    int[] s = spo2(b);
                    if (s != null) return "spo2 " + join(s);
                    break;
                }
                case SPO2_R_PI: {
                    double[] r = spo2R(b);
                    if (r != null) return "R " + join(r, 3);
                    break;
                }
                case SLEEP_PHASE_INFO:
                case SLEEP_PHASE_DETAILS:
                case SLEEP_PHASE_DATA: {
                    int[] p = sleepPhases(b);
                    if (p != null) return "page " + (b[0] & 0xff) + " stages " + join(p);
                    break;
                }
                case MOTION_EVENT:
                    if (b.length >= 4) return "motion " + motionSeconds(b) + " s";
                    break;
                case BEDTIME_PERIOD: {
                    long[] bt = bedtime(b);
                    if (bt != null) return "bedtime " + bt[0] + " to " + bt[1] + " ds";
                    break;
                }
                case SLEEP_PERIOD_INFO_2: {
                    SleepPeriod s = sleepPeriod(b);
                    if (s != null) return String.format("hr %.1f breath %.1f motion %d state %d", s.avgHr, s.breath, s.motionCount, s.sleepState);
                    break;
                }
                case AOHR_EVENT: {
                    int[][] a = aohr(b);
                    if (a != null) return "bpm " + join(a[0]) + " q " + join(a[1]);
                    break;
                }
                case USER_INFO: {
                    int[] u = userInfo(b);
                    if (u != null) return "age " + u[0] + " weight " + u[1] + " sex " + u[2] + " height " + u[3];
                    break;
                }
                default:
                    break;
            }
        } catch (RuntimeException ignored) {
            // fall through to hex
        }
        return Hex.encode(b);
    }

    static String join(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    static String join(double[] v, int dp) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%." + dp + "f", v[i]));
        }
        return sb.toString();
    }
}
