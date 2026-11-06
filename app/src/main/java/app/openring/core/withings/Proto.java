package app.openring.core.withings;

import java.nio.charset.StandardCharsets;

/**
 * The Withings Steel HR's message and part types and the requests this app sends (issue #251, phase 1b). The numbers
 * are protocol facts from public discussions, written down in our own words in docs/ARCHITECTURE.md; nothing here is
 * copied code. Besides reading, signing in and setting the time, the setup sends what the watch needs to track (the
 * user, the step goal, units, language, display screens, "setup finished"), and the alarm and the hand calibration
 * have their own messages. Phone notifications, workout screens and live values are not sent (a later phase).
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
    /** Movement records since a time (steps, distance, climb), the third data request (with a version part). */
    public static final int MOVEMENT = 0x0506;
    /** Distance or clock unit, one per message ({@link #UNIT_DISTANCE}, {@link #UNIT_CLOCK}). */
    public static final int SET_UNIT = 0x0112;
    /** Ends the first setup; the watch answers with the same type. */
    public static final int SETUP_FINISHED = 0x0113;
    /** The watch's language (two letters). */
    public static final int SET_LOCALE = 0x011a;
    /** The user: height, weight, sex, birth date (sent at the setup and every sync). */
    public static final int SET_USER = 0x0502;
    /** The daily step goal (the activity hand shows how much of it is reached). */
    public static final int SET_GOAL = 0x050a;
    /** Which screens the watch's small display shows, in which order. */
    public static final int SET_SCREENS = 0x050c;
    /** The hand calibration: start (the hands go to their home), move one hand, stop. */
    public static final int HANDS_START = 0x011e, HANDS_STOP = 0x011f, MOVE_HAND = 0x011c;
    /** The vibration alarm, and switching it on or off. */
    public static final int SET_ALARM = 0x0145, ALARM_ON = 0x091b;
    /** The watch asks for a sync on its own (its own message); the answer is {@link #SYNC_ASK_REPLY}, empty. */
    public static final int SYNC_ASK = 0x0141;
    /** The type of our answer to {@link #SYNC_ASK}: an answer to one of the watch's own messages carries {@code 0x4000}. */
    public static final int SYNC_ASK_REPLY = 0x4141;

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
    public static final int USER = 0x0503;
    public static final int UNIT = 0x0119;
    public static final int LOCALE = 0x0121;
    public static final int GOAL = 0x0511;
    public static final int SCREEN = 0x0516;
    public static final int HAND = 0x050c;
    public static final int ALARM = 0x0512;
    public static final int ALARM_STATE = 0x0919;

    /** {@link #SET_UNIT}: what is set and its value. */
    public static final int UNIT_DISTANCE = 2, UNIT_CLOCK = 4, KM = 24, MILES = 25, H24 = 26, H12 = 27;
    /** {@link #MOVE_HAND}: which hand. */
    public static final int HAND_MINUTES = 0, HAND_HOURS = 1, HAND_ACTIVITY = 2;
    /** The user number in {@link #user} and the screens: any number works (Gadgetbridge sends this one). */
    static final int USER_ID = 123456;
    /** The display screens as id and place, the set Gadgetbridge sends; which id is which screen is not documented. */
    static final int[][] SCREENS = {{0xff, 6}, {0x3d, 1}, {0x33, 4}, {0x2d, 2}, {0x2a, 3}, {0x26, 7}, {0x39, 9}};

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

    public static byte[] movement(long since) {
        return Frame.message(MOVEMENT, since(since), Frame.part(REC_VERSION, new byte[] {1}));
    }

    /** One unit: two zero bytes, what is set (2 bytes) and its value (2 bytes). */
    public static byte[] unit(int what, int value) {
        byte[] v = new byte[6];
        Frame.put16(v, 2, what);
        Frame.put16(v, 4, value);
        return Frame.message(SET_UNIT, Frame.part(UNIT, v));
    }

    /** The language as two lower case letters with a length byte first. */
    public static byte[] locale(String lang) {
        byte[] l = lang.getBytes(StandardCharsets.US_ASCII);
        byte[] v = new byte[1 + l.length];
        v[0] = (byte) l.length;
        System.arraycopy(l, 0, v, 1, l.length);
        return Frame.message(SET_LOCALE, Frame.part(LOCALE, v));
    }

    /**
     * The user: a user number (4 bytes), weight in kg (4), height in cm (4), sex (1 byte: 0 male, 1 female), birth date (4,
     * unix seconds), then the name with a length byte first (we send none).
     */
    public static byte[] user(int weightKg, int heightCm, int sex, long birth) {
        byte[] v = new byte[18];
        Frame.put32(v, 0, USER_ID);
        Frame.put32(v, 4, weightKg);
        Frame.put32(v, 8, heightCm);
        v[12] = (byte) (sex == 1 ? 1 : 0);
        Frame.put32(v, 13, birth);
        v[17] = 0;
        return Frame.message(SET_USER, Frame.part(USER, v));
    }

    /** The daily step goal, 8 bytes. */
    public static byte[] goal(long steps) {
        byte[] v = new byte[8];
        Frame.put32(v, 0, steps >>> 32);
        Frame.put32(v, 4, steps);
        return Frame.message(SET_GOAL, Frame.part(GOAL, v));
    }

    /**
     * The display screens: one part per screen (its id, the user number, two zero numbers, its place, then 1), the end
     * part last.
     */
    public static byte[] screens() {
        byte[][] parts = new byte[SCREENS.length + 1][];
        for (int i = 0; i < SCREENS.length; i++) {
            byte[] v = new byte[18];
            Frame.put32(v, 0, SCREENS[i][0]);
            Frame.put32(v, 4, USER_ID);
            v[16] = (byte) SCREENS[i][1];
            v[17] = 1;
            parts[i] = Frame.part(SCREEN, v);
        }
        parts[SCREENS.length] = Frame.part(END, new byte[0]);
        return Frame.message(SET_SCREENS, parts);
    }

    public static byte[] setupFinished() {
        return Frame.message(SETUP_FINISHED);
    }

    public static byte[] handsStart() {
        return Frame.message(HANDS_START);
    }

    public static byte[] handsStop() {
        return Frame.message(HANDS_STOP);
    }

    /** Moves one hand ({@link #HAND_HOURS}, {@link #HAND_MINUTES}, {@link #HAND_ACTIVITY}) by {@code amount} steps (plus is clockwise). */
    public static byte[] moveHand(int hand, int amount) {
        byte[] v = new byte[3];
        v[0] = (byte) hand;
        Frame.put16(v, 1, Math.max(-32768, Math.min(32767, amount)));
        return Frame.message(MOVE_HAND, Frame.part(HAND, v));
    }

    /**
     * The alarm: hour, minute, days of the week, then day of month, month and year (0 for a repeating alarm) and the smart
     * wake window in minutes (0 for none). {@code days} uses bit 0 for Monday up to bit 6 for Sunday; the watch's own bits
     * are Sunday 0x01, Monday 0x02 up to Saturday 0x40, always with 0x80.
     */
    public static byte[] alarm(int hour, int minute, int days, int smartMin) {
        byte[] v = new byte[7];
        v[0] = (byte) hour;
        v[1] = (byte) minute;
        v[2] = (byte) watchDays(days);
        v[6] = (byte) Math.max(0, Math.min(255, smartMin));
        return Frame.message(SET_ALARM, Frame.part(ALARM, v));
    }

    /** Our days (bit 0 Monday .. bit 6 Sunday) as the watch's bits (Sunday 0x01, Monday 0x02 .. Saturday 0x40, plus 0x80). */
    public static int watchDays(int days) {
        int out = 0x80;
        for (int d = 0; d < 6; d++) if ((days & (1 << d)) != 0) out |= 1 << (d + 1);
        if ((days & (1 << 6)) != 0) out |= 1;
        return out;
    }

    public static byte[] alarmOn(boolean on) {
        return Frame.message(ALARM_ON, Frame.part(ALARM_STATE, new byte[] {(byte) (on ? 1 : 0)}));
    }

    /** Our empty answer to the watch's own sync ask. */
    public static byte[] syncAskReply() {
        return Frame.message(SYNC_ASK_REPLY);
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
