package app.openring.core.withings;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the app sends the watch (issue #251, watch setup): the first setup once after pairing, the steps of each sync,
 * the alarm and the hand calibration, all built from the person's profile and the phone's settings. Pure, so the JVM
 * tests check every step and every byte. The layouts are protocol facts written down in docs/ARCHITECTURE.md (from
 * Gadgetbridge's public Steel HR support, facts only); the order follows what Gadgetbridge sends.
 */
public final class WatchSetup {
    private WatchSetup() {}

    /** Step keys (the setup screen names them, the log too). */
    public static final String CONNECT = "connect", LANGUAGE = "language", TIME = "time", DISTANCE = "distance unit", CLOCK = "clock",
            USER = "user", GOAL = "step goal", SCREENS = "screens", BATTERY = "battery", FINISHED = "setup finished", ALARM = "alarm",
            ALARM_ON = "alarm on", HANDS_START = "hands start", HANDS_STOP = "hands stop", MOVE = "move hand";

    /** The step goals the screen offers; the default is 10,000. */
    public static final int[] GOALS = {6000, 8000, 10000, 12000};
    public static final int DEFAULT_GOAL = 10000;
    /** Smart wake windows the screen offers (minutes before the alarm the watch may wake you in light sleep). */
    public static final int[] SMART = {0, 15, 30};
    /** The languages the watch knows; any other phone language gets English. */
    static final String[] LANGUAGES = {"de", "en", "es", "fr", "it"};
    /** The first sync asks this far back (Gadgetbridge asks 10 hours; a month keeps what the watch still holds). */
    public static final long FIRST_SINCE_S = 30L * 86400;

    /** What the watch is told about the person and the phone. */
    public static final class Profile {
        /** From the app's profile; sex 0 male, 1 female, 2 not given (the watch knows only 0 and 1, so the screen asks). */
        public int age, sex = 2, weightKg, heightCm;
        public int goal = DEFAULT_GOAL;
        public boolean metric = true, h24 = true;
        /** The phone's language, two letters. */
        public String language = "en";
        /** The year "now" falls in (for the birth date worked out from the age). */
        public int year;

        /** True when the watch can be told who wears it: sex given (the profile's other values always have one). */
        public boolean complete() {
            return sex == 0 || sex == 1;
        }

        /** The birth date sent: 1 July of the year the age points to, midnight UTC, unix seconds. */
        public long birth() {
            return LocalDate.of(year - age, 7, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        }

        /** The watch's language: the phone's when the watch knows it, else English. */
        public String watchLanguage() {
            String l = language == null ? "" : language.toLowerCase(Locale.ROOT);
            for (String k : LANGUAGES) if (k.equals(l)) return k;
            return "en";
        }
    }

    /** The vibration alarm: hour, minute, days (bit 0 Monday .. bit 6 Sunday), smart wake window, on or off. */
    public static final class Alarm {
        public int hour = 7, minute = 0, days = 0x1f, smart = 0;
        public boolean on;

        /** On with at least one day: what the watch is sent as on. On without a day is sent as off (its meaning is not documented). */
        public boolean active() {
            return on && (days & 0x7f) != 0;
        }
    }

    private static Session.Step ask(String key, byte[] msg) {
        return new Session.Step(key, Session.Step.ASK, msg);
    }

    private static Session.Step data(String key, byte[] msg) {
        return new Session.Step(key, Session.Step.DATA, msg);
    }

    private static Session.Step soft(String key, byte[] msg) {
        return new Session.Step(key, Session.Step.SOFT, msg);
    }

    /**
     * Sets the watch's time. With the plan's clock and zone the message is made again when it is sent (a hand calibration
     * can last minutes, review of #332); without them it holds the plan's time.
     */
    static Session.Step time(final Session.Plan plan) {
        byte[] first = Proto.setTime(plan.now, plan.offset, plan.nextChange, plan.offsetAfter);
        if (plan.clock == null || plan.zone == null) return ask(TIME, first);
        return new Session.Step(TIME, Session.Step.ASK, first, new Session.Maker() {
            @Override
            public byte[] make() {
                Session.Plan t = new Session.Plan();
                t.at(plan.clock.now(), plan.zone);
                return Proto.setTime(t.now, t.offset, t.nextChange, t.offsetAfter);
            }
        });
    }

    /** The steps that tell the watch who wears it: the user, the step goal, distance and clock units. */
    public static List<Session.Step> person(Profile p) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        if (p.complete()) out.add(ask(USER, Proto.user(p.weightKg, p.heightCm, p.sex, p.birth())));
        out.add(ask(GOAL, Proto.goal(p.goal)));
        out.add(ask(DISTANCE, Proto.unit(Proto.UNIT_DISTANCE, p.metric ? Proto.KM : Proto.MILES)));
        out.add(ask(CLOCK, Proto.unit(Proto.UNIT_CLOCK, p.h24 ? Proto.H24 : Proto.H12)));
        return out;
    }

    /** The alarm's two steps: the alarm itself and on, or only off (also for on without a day). */
    public static List<Session.Step> alarm(Alarm a) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        if (a.active()) out.add(ask(ALARM, Proto.alarm(a.hour, a.minute, a.days, a.smart)));
        out.add(ask(ALARM_ON, Proto.alarmOn(a.active())));
        return out;
    }

    /** The three data requests, each until its end part, from {@code since} on. */
    static List<Session.Step> download(long activitySince, long movementSince, long heartSince) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(data(Session.ACTIVITY, Proto.activity(activitySince)));
        out.add(data(Session.MOVEMENT, Proto.movement(movementSince)));
        out.add(data(Session.HEART, Proto.heartRate(heartSince)));
        return out;
    }

    /** The plain sync without a profile (the tests' default plan): connect, battery, time, the three data requests. */
    static List<Session.Step> basicSync(Session.Plan plan) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(ask(CONNECT, Proto.connect()));
        out.add(ask(BATTERY, Proto.battery()));
        out.add(time(plan));
        out.addAll(download(plan.activitySince, plan.activitySince, plan.heartSince));
        return out;
    }

    /**
     * Each sync, after the hello and sign in: connect, battery, the time (with the next clock change, so the hands follow a
     * daylight saving change), the user, the goal and the units, the alarm when it changed and was not sent yet
     * ({@code alarm} null otherwise), then activity, movement and heart rate records.
     */
    public static List<Session.Step> sync(Session.Plan plan, Profile p, Alarm alarm, long movementSince) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(ask(CONNECT, Proto.connect()));
        out.add(ask(BATTERY, Proto.battery()));
        out.add(time(plan));
        out.addAll(person(p));
        if (alarm != null) out.addAll(alarm(alarm));
        out.addAll(download(plan.activitySince, movementSince, plan.heartSince));
        return out;
    }

    /**
     * The first setup, after the hello and sign in: connect, language, time, the person, the display screens, the alarm
     * (null to leave it), battery and "setup finished"; then the first sync's data requests in the same connection.
     */
    public static List<Session.Step> setup(Session.Plan plan, Profile p, Alarm alarm, long movementSince) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(ask(CONNECT, Proto.connect()));
        out.add(ask(LANGUAGE, Proto.locale(p.watchLanguage())));
        out.add(time(plan));
        out.addAll(person(p));
        out.add(ask(SCREENS, Proto.screens()));
        if (alarm != null) out.addAll(alarm(alarm));
        out.add(ask(BATTERY, Proto.battery()));
        out.add(ask(FINISHED, Proto.setupFinished()));
        out.addAll(download(plan.activitySince, movementSince, plan.heartSince));
        return out;
    }

    /** The alarm on its own: connect, then the alarm. */
    public static List<Session.Step> alarmOnly(Alarm a) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(ask(CONNECT, Proto.connect()));
        out.addAll(alarm(a));
        return out;
    }

    /** The hand calibration's start: connect, then calibration mode (the hands go to their home). */
    public static List<Session.Step> handsStart() {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(ask(CONNECT, Proto.connect()));
        out.add(soft(HANDS_START, Proto.handsStart()));
        return out;
    }

    /** One hand move while calibrating. */
    public static Session.Step move(int hand, int amount) {
        return soft(MOVE, Proto.moveHand(hand, amount));
    }

    /** The end of the calibration: calibration mode off, then the phone's time so the hands show it. */
    public static List<Session.Step> handsEnd(Session.Plan plan) {
        List<Session.Step> out = new ArrayList<Session.Step>();
        out.add(soft(HANDS_STOP, Proto.handsStop()));
        out.add(time(plan));
        return out;
    }

    /**
     * A hand calibration whose link was lost left the watch in calibration mode (review of #332): the next session
     * switches it off first, right after connect, and sets the time there when its steps do not already. A new list.
     */
    public static List<Session.Step> closeCalibrationFirst(List<Session.Step> steps, Session.Plan plan) {
        List<Session.Step> out = new ArrayList<Session.Step>(steps);
        int at = !out.isEmpty() && CONNECT.equals(out.get(0).key) ? 1 : 0;
        boolean time = false;
        for (Session.Step s : out) time |= TIME.equals(s.key);
        out.add(at, soft(HANDS_STOP, Proto.handsStop()));
        if (!time) out.add(at + 1, time(plan));
        return out;
    }

    /** Hand calibration texts: an early end after calibration mode was switched off, and one where the link was lost before. */
    public static final String HANDS_EARLY = "Setting the hands ended early. The watch left hand setting and shows the phone's time. Tap Start to go on.";
    public static final String HANDS_OPEN = "The connection was lost while setting the hands. The next connection to the watch ends hand setting and sets its time.";

    /**
     * What the hand calibration screen says after its session: null when all went well; the session's own sentence when
     * it ended before the hands were started (the sign in, for example); else one of the hands' own texts, since the
     * sync's "the rest follows at the next sync" does not hold for the hands.
     */
    public static String handsResult(String ended, boolean started, boolean stopped) {
        if (ended == null) return null;
        if (!started) return ended;
        return stopped ? HANDS_EARLY : HANDS_OPEN;
    }

    // ---------------------------------------------------------------------------- what the screen shows

    /** Day names in our order (bit 0 Monday). */
    public static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    /** The alarm's days in words: "every day", "Mon to Fri", "Sat, Sun", "no day". */
    public static String days(int days) {
        days &= 0x7f;
        if (days == 0x7f) return "every day";
        if (days == 0x1f) return "Mon to Fri";
        if (days == 0) return "no day";
        List<String> out = new ArrayList<String>();
        for (int d = 0; d < 7; d++) if ((days & (1 << d)) != 0) out.add(DAYS[d]);
        return String.join(", ", out);
    }

    /** "07:05". */
    public static String clock(int hour, int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    /** The lines "what is sent" on the setup screen: one per value, plain words, in the order sent. */
    public static List<String> shown(Profile p, String zone, Alarm a) {
        List<String> out = new ArrayList<String>();
        out.add("Language: " + languageName(p.watchLanguage()));
        out.add("Time and zone: the phone's (" + zone + "), with its next clock change");
        out.add(p.complete() ? "You: " + (p.sex == 1 ? "female" : "male") + ", " + p.heightCm + " cm, " + p.weightKg + " kg, born about " + (p.year - p.age)
                + " (from your age)" : "You: not sent until your profile says male or female");
        out.add("Daily step goal: " + String.format(Locale.US, "%,d", p.goal) + " steps");
        out.add("Distance in " + (p.metric ? "km" : "miles") + ", " + (p.h24 ? "24" : "12") + " hour clock");
        out.add("The watch's screens: the same 7 Gadgetbridge sends");
        out.add(a == null || !a.on ? "Alarm: off" : !a.active() ? "Alarm: off (no day picked)" : "Alarm: " + clock(a.hour, a.minute) + ", " + days(a.days) + (a.smart > 0 ? ", smart wake " + a.smart + " min" : ""));
        out.add("Then: setup finished, and the first sync");
        return out;
    }

    public static String languageName(String l) {
        switch (l) {
            case "de": return "German";
            case "es": return "Spanish";
            case "fr": return "French";
            case "it": return "Italian";
            default: return "English";
        }
    }

    /** The hands in the order the calibration goes through them, and what to do with each. */
    public static final int[] HANDS = {Proto.HAND_HOURS, Proto.HAND_MINUTES, Proto.HAND_ACTIVITY};
    public static final String[] HAND_TEXT = {"Move the hour hand until it points exactly at 12.", "Now move the minute hand until it points exactly at 12.",
        "Last, move the small activity hand to 100 %. It only moves clockwise, so go slowly."};
}
