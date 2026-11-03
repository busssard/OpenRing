package app.openring.core.withings;

import java.nio.charset.StandardCharsets;

/**
 * The Withings Steel HR's message and part types and the requests this app sends (issue #251, phase 1b). The numbers
 * are protocol facts from public discussions, written down in our own words in docs/ARCHITECTURE.md; nothing here is
 * copied code. Only requests that read, sign in or set the time are sent: no alarms, screens, user data or notifications, so
 * the watch's set up from the Withings app stays as it is.
 */
public final class Proto {
    private Proto() {}

    // ---- message types (the request and its answer share one) ----
    /** Hello: the app says who it is, the watch answers with its name and firmware. */
    public static final int HELLO = 0x0101;
    /** Connect, sent once after the hello (the Withings app does the same). */
    public static final int CONNECT = 0x0111;
    /** The sign in: the watch asks the app to prove it knows a secret, and the app answers with the same type ({@link SignIn}). */
    public static final int CHALLENGE = 0x0128;
    /** Sync done: always the last message of a session, the notes say the watch stops answering without it. */
    public static final int SYNC_DONE = 0x0115;
    public static final int SET_TIME = 0x0501;
    public static final int BATTERY = 0x0504;
    /** Activity records since a time: steps, sleep stages, workouts, some heart rates. */
    public static final int ACTIVITY = 0x0978;
    /** Heart rate records since a time. */
    public static final int HEART_RATE = 0x0928;

    // ---- part types ----
    public static final int END = 0x0100;
    public static final int HELLO_REPLY = 0x0101;
    public static final int HELLO_APP = 0x012a;
    public static final int HELLO_OS = 0x0928;
    /** A challenge: address text and random bytes, each with a length byte first (the watch's, and ours back). */
    public static final int CHALLENGE_PART = 0x0122;
    /** The proof for a challenge: a length byte, then the SHA-1 (ours in the answer, the watch's in its hello answer). */
    public static final int CHALLENGE_ANSWER = 0x0123;
    public static final int TIME = 0x0501;
    public static final int BATTERY_PART = 0x0504;
    public static final int SINCE = 0x0506;
    public static final int REC_TIME = 0x0601;
    public static final int REC_DURATION = 0x0602;
    public static final int REC_STEPS = 0x0603;
    public static final int REC_WALK = 0x0604;
    public static final int REC_RUN = 0x0605;
    public static final int REC_SLEEP = 0x0607;
    public static final int REC_CALORIES = 0x0608;
    public static final int REC_CALORIES_2 = 0x060a;
    public static final int REC_SWIM = 0x060d;
    public static final int REC_HEART_RATE = 0x0929;
    public static final int REC_VERSION = 0x0961;
    public static final int REC_WORKOUT = 0x0969;

    /**
     * The app version the hello names. This is the value the Withings app sent in a public recording of a Steel HR
     * pairing (Gadgetbridge issue 854 on Codeberg).
     */
    public static final long APP_VERSION = 0x003d7e96L;

    /** Hello: platform 1 and app 1 with {@link #APP_VERSION}, then the phone's Android version. */
    public static byte[] hello(int androidSdk) {
        byte[] app = new byte[6];
        app[0] = 1;
        app[1] = 1;
        Frame.put32(app, 2, APP_VERSION);
        byte[] os = new byte[2];
        Frame.put16(os, 0, androidSdk);
        return Frame.message(HELLO, Frame.part(HELLO_APP, app), Frame.part(HELLO_OS, os));
    }

    public static byte[] connect() {
        return Frame.message(CONNECT);
    }

    public static byte[] battery() {
        return Frame.message(BATTERY);
    }

    public static byte[] syncDone() {
        return Frame.message(SYNC_DONE);
    }

    /**
     * Set the watch's clock: now (unix seconds), the offset of the phone's zone from UTC now (seconds), the next clock
     * change (unix seconds, 0 for none) and the offset after it.
     */
    public static byte[] setTime(long now, int offset, long nextChange, int offsetAfter) {
        byte[] v = new byte[16];
        Frame.put32(v, 0, now);
        Frame.put32(v, 4, offset);
        Frame.put32(v, 8, nextChange);
        Frame.put32(v, 12, offsetAfter);
        return Frame.message(SET_TIME, Frame.part(TIME, v));
    }

    /** The "since" part: records from {@code since} (unix seconds) on, any number of them (0). */
    static byte[] since(long since) {
        byte[] v = new byte[6];
        Frame.put32(v, 0, Math.max(0, since));
        return Frame.part(SINCE, v);
    }

    public static byte[] activity(long since) {
        return Frame.message(ACTIVITY, since(since));
    }

    public static byte[] heartRate(long since) {
        return Frame.message(HEART_RATE, since(since), Frame.part(REC_VERSION, new byte[] {1}));
    }

    /** What the hello's answer names: the watch's name and firmware number, either null when not there. */
    public static final class Hello {
        public String name;
        public long firmware = -1;
    }

    /**
     * Reads the hello's answer part: a number, the name, the address as text, another text, a number, a model text, a
     * number, the firmware number and a number; each text has a length byte first. Whatever is missing stays null or -1.
     */
    public static Hello hello(byte[] msg) {
        Hello h = new Hello();
        Frame.Part p = Frame.first(msg, HELLO_REPLY);
        if (p == null) return h;
        byte[] b = p.value;
        int[] at = {4};
        if (b.length < 4) return h;
        h.name = text(b, at);
        if (h.name == null) return h;
        if (text(b, at) == null || text(b, at) == null) return h;
        at[0] += 4;
        if (text(b, at) == null) return h;
        at[0] += 4;
        if (at[0] + 4 <= b.length) h.firmware = Frame.u32(b, at[0]);
        return h;
    }

    private static String text(byte[] b, int[] at) {
        if (at[0] >= b.length) return null;
        int n = b[at[0]] & 0xff;
        if (at[0] + 1 + n > b.length) return null;
        String s = new String(b, at[0] + 1, n, StandardCharsets.UTF_8);
        at[0] += 1 + n;
        return s;
    }

    /** The battery part: percent, state (0 charging, 1 low, other normal) and voltage; null when missing or short. */
    public static int[] battery(byte[] msg) {
        Frame.Part p = Frame.first(msg, BATTERY_PART);
        if (p == null || p.value.length < 6) return null;
        return new int[] {p.value[0] & 0xff, p.value[1] & 0xff, (int) Frame.u32(p.value, 2)};
    }

    /** The plain name of a workout type code (from the list of codes in the public notes), "Workout" for others. */
    public static String workoutName(int code) {
        switch (code) {
            case 1: return "Walking";
            case 2: return "Running";
            case 3: return "Hiking";
            case 6: return "Cycling";
            case 7: return "Swimming";
            case 8: return "Surfing";
            case 9: return "Kitesurfing";
            case 10: return "Windsurfing";
            case 12: return "Tennis";
            case 13: return "Table tennis";
            case 14: return "Squash";
            case 15: return "Badminton";
            case 16: return "Weights";
            case 17: return "Gymnastics";
            case 18: return "Elliptical trainer";
            case 19: return "Pilates";
            case 20: return "Basketball";
            case 21: return "Football";
            case 22: return "American football";
            case 23: return "Rugby";
            case 24: return "Volleyball";
            case 26: return "Riding";
            case 28: return "Yoga";
            case 29: return "Dancing";
            case 30: return "Boxing";
            case 34: return "Skiing";
            case 35: return "Snowboarding";
            case 36: return "Other sport";
            case 188: return "Zumba";
            case 191: return "Baseball";
            case 192: return "Handball";
            case 193: return "Hockey";
            case 194: return "Ice hockey";
            case 195: return "Climbing";
            case 196: return "Ice skating";
            case 227: return "Golf";
            default: return "Workout";
        }
    }
}
