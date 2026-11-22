package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.SoundDetector;
import app.openring.core.analysis.Stats;

/**
 * Sleep sounds on the screens (issue #380, phase 1b): the texts, the microphone test's numbers (the owner's wish of 21 November),
 * when the bedtime notification comes and when a night stops, the retention choices, the WAV file of a clip whose encoder failed,
 * and the demo's made up clips (tones made in code, never a voice). Plain Java, so the JVM tests cover all of it.
 */
public final class SleepSounds {
    private SleepSounds() {}

    // ------------------------------------------------------------------------------------- texts

    public static final String TITLE = "Sleep sounds";
    public static final String ABOUT = "Find out if you talk, snore or cough in your sleep. The phone listens through the night and keeps only short clips of loud moments.";
    public static final String SET_UP = "Set up sleep sounds", LISTEN = "Listen tonight", STOP = "Stop listening", ALL_CLIPS = "All clips of this night";
    public static final String NOTHING = "Nothing recorded this night.";
    public static final String QUIET = "Listened, no sound was loud enough to keep this night.";
    public static final String COUNTS_NOTE = "Sounds: each loud moment the phone kept a mark of. Snoring minutes: from the first to the last snore of each stretch.";
    public static final String BATTERY = "Listening all night uses battery. Best with the phone on its charger.";
    public static final String NO_DIAGNOSIS = "Snoring minutes are not a test for sleep apnea. If breathing pauses worry you, ask a doctor.";
    public static final String DEMO_NO_RECORD = "Demo mode never records. Leave the demo to listen.";
    public static final String MIC_OFF = "The microphone is not allowed, so nothing can be recorded.";
    public static final String MIC_ALLOW = "Allow the microphone";
    public static final String MIC_SETTINGS = "Android no longer asks for this. Open the app's settings and allow the Microphone.";
    public static final String MIC_REFUSED = "The microphone was not allowed. Sleep sounds need it to listen.";
    public static final String NOT_STARTED = "Not started. Another app may be using the microphone.";
    public static final String STARTED = "Listening. Clips stay on this phone.";

    public static final String CONSENT_TITLE = "Before the first night";
    public static final String CONSENT = "The phone listens through the night and keeps short clips (up to 20 s) around loud moments: snoring, coughs, talking. "
            + "Everything else is never stored. Clips stay on this phone and are deleted after 7 days unless you keep them (Settings, Phone). "
            + "Times and kinds of sounds are in backups, the sounds themselves never. "
            + "If someone sleeps next to you, they will be recorded too: tell them and agree first. "
            + "In some places recording others without their knowledge is not allowed.";
    public static final String AGREE = "I agree, ask for the microphone", NOT_NOW = "Not now";

    public static final String NOTE_TITLE = "Listening for sleep sounds", NOTE_TEXT = "Clips stay on this phone.", NOTE_STOP = "Stop";
    public static final String REMIND_TITLE = "Ready for the night?", REMIND_TEXT = "Tap Start to listen for sleep sounds.", REMIND_START = "Start";

    public static final String PLAY = "Play", PLAYING = "Playing", KEEP = "Keep", KEPT = "Kept", DELETE_SOUND = "Delete sound";
    public static final String DELETE_NIGHT = "Delete all clips of this night";
    public static final String DELETE_NIGHT_ASK = "All marks and clips of this night are deleted from this phone.";
    public static final String GONE = "(sound deleted)";
    public static final String KEPT_NOTE = "Kept clips are never deleted by the time you chose in Settings.";

    /** The kinds as the screens name them ({@code SoundDetector} stores snore, cough, talk). */
    public static String kindName(String kind) {
        if (SoundDetector.SNORE.equals(kind)) return "Snoring";
        if (SoundDetector.COUGH.equals(kind)) return "Cough like sound";
        return "Talking or other sound";
    }

    // ------------------------------------------------------------------------------------- why a night stopped

    /** Why a night stopped ({@code sound_nights.ended}); {@code data/Sounds} uses the same values. */
    public static final String ENDED_HAND = "hand", ENDED_TIME = "time", ENDED_LIMIT = "12 h", ENDED_SYSTEM = "system";

    /** The line for a night that stopped early (Android ended it), null for one stopped by hand or at its time. */
    public static String endedLine(String ended, double stop, ZoneId zone) {
        if (!ENDED_SYSTEM.equals(ended) || Double.isNaN(stop)) return null;
        return "Stopped at " + clock(stop, zone) + ": Android ended the listening.";
    }

    /** The line for the time the microphone gave only silence (another app held it), null for none. */
    public static String gapLine(double silentSeconds) {
        if (!(silentSeconds >= 60)) return null;
        return "Not listening for " + Math.round(silentSeconds / 60) + " min: another app used the microphone.";
    }

    // ------------------------------------------------------------------------------------- marks and the night's line

    /** One mark as the screens show it. */
    public static final class Mark {
        public final double start, stop;
        public final String kind;
        /** Its clip file is there to play. */
        public final boolean sound;
        public final boolean kept;

        public Mark(double start, double stop, String kind, boolean sound, boolean kept) {
            this.start = start;
            this.stop = stop;
            this.kind = kind;
            this.sound = sound;
            this.kept = kept;
        }
    }

    /** "5 sounds: snoring 14 min, 1 cough like sound, 2 talking or other sounds." ({@link #QUIET} for none). */
    public static String summary(List<Mark> marks) {
        if (marks.isEmpty()) return QUIET;
        int coughs = 0, talks = 0, stretches = 0;
        double snoreMin = 0;
        for (Mark m : marks) {
            if (SoundDetector.SNORE.equals(m.kind)) {
                stretches++;
                snoreMin += (m.stop - m.start) / 60.0;
            } else if (SoundDetector.COUGH.equals(m.kind)) {
                coughs++;
            } else {
                talks++;
            }
        }
        List<String> parts = new ArrayList<String>();
        if (stretches > 0) parts.add(Math.round(snoreMin) < 1 ? "snoring under 1 min" : "snoring " + Math.round(snoreMin) + " min");
        if (coughs > 0) parts.add(Texts.count(coughs, "cough like sound", "cough like sounds"));
        if (talks > 0) parts.add(Texts.count(talks, "talking or other sound", "talking or other sounds"));
        StringBuilder b = new StringBuilder(Texts.count(marks.size(), "sound", "sounds")).append(": ");
        for (int i = 0; i < parts.size(); i++) b.append(i == 0 ? "" : ", ").append(parts.get(i));
        return b.append('.').toString();
    }

    /** "02:12 Snoring, 14 min", "00:41 Talking or other sound, 3 s", with " (sound deleted)" when the sound is gone. */
    public static String row(Mark m, ZoneId zone) {
        return clock(m.start, zone) + " " + kindName(m.kind) + ", " + length(m.stop - m.start) + (m.sound ? "" : " " + GONE);
    }

    /** A length: "3 s" under a minute, else "14 min". */
    public static String length(double seconds) {
        if (seconds < 59.5) return Math.max(1, Math.round(seconds)) + " s";
        return Math.round(seconds / 60) + " min";
    }

    /** "Listening since 23:12, 3 sounds so far." */
    public static String listeningLine(double start, int sounds, ZoneId zone) {
        return "Listening since " + clock(start, zone) + ", " + Texts.count(sounds, "sound", "sounds") + " so far.";
    }

    /** "12 clips, 0.6 MB" (the space the clip files take); "No clips" for none. */
    public static String spaceLine(int files, long bytes) {
        if (files <= 0) return "No clips";
        double mb = bytes / (1024.0 * 1024.0);
        return Texts.count(files, "clip", "clips") + ", " + (mb < 0.1 ? "under 0.1 MB" : String.format(Locale.US, "%.1f MB", mb));
    }

    /** HH:mm of {@code unix} in {@code zone}. */
    public static String clock(double unix, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone);
        return String.format(Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }

    /** The day whose Sleep tab shows a night that started at {@code start}: the date 12 hours after the start (23:10 Friday is Saturday's). */
    public static LocalDate nightDay(double start, ZoneId zone) {
        return Instant.ofEpochSecond((long) Math.floor(start) + 12 * 3600L).atZone(zone).toLocalDate();
    }

    // ------------------------------------------------------------------------------------- how long clips keep their sound

    /** Days a clip keeps its sound: 7 (the default, decided on #380), 30, or 0 until deleted. */
    public static final int KEEP_WEEK = 7, KEEP_MONTH = 30, KEEP_ALWAYS = 0, KEEP_DEFAULT = KEEP_WEEK;
    public static final int[] KEEP_CHOICES = {KEEP_WEEK, KEEP_MONTH, KEEP_ALWAYS};
    public static final String[] KEEP_NAMES = {"7 days", "30 days", "Until deleted"};

    public static boolean validKeep(int days) {
        for (int k : KEEP_CHOICES) if (k == days) return true;
        return false;
    }

    /** "Clips keep their sound for 7 days, then only the mark stays." */
    public static String keepLine(int days) {
        if (days == KEEP_ALWAYS) return "Clips keep their sound until you delete them.";
        return "Clips keep their sound for " + days + " days, then only the mark stays (unless you keep the clip).";
    }

    // ------------------------------------------------------------------------------------- the microphone test (owner's wish)

    public static final String TEST = "Test the microphone";
    public static final String TEST_ROOM = "Put your phone where it lies at night and lie down. Then tap Start and stay quiet for 10 seconds.";
    public static final String TEST_START = "Start";
    public static final String TEST_SAY = "Now say, in your normal voice:";
    public static final String TEST_SENTENCE = "I am testing the sleep sounds of Open Ring.";
    public static final String TEST_PLAYED = "You heard what the phone heard. It is not stored.";
    public static final String TEST_AGAIN = "Test again", TEST_DONE = "Done";
    public static final String TEST_PROCESSED = "This phone may adjust the microphone level on its own, so the result can change a little from night to night.";
    public static final String THRESHOLD_NOTE = "A sound is kept when it is this much louder than the room.";
    public static final String MARGIN_NOTE = "dB above the room: how much louder your voice reached the phone than the quiet room. 20 or more is good.";
    /** Seconds of quiet for the room, and the longest wait for the sentence. */
    public static final int ROOM_S = 10, VOICE_S = 8;
    /** The start threshold is half the margin, kept between these (dB above the floor). */
    public static final double MIN_THRESHOLD = 6, MAX_THRESHOLD = 15;
    /** The verdicts: no sound, too far or too loud a room, works, good. */
    public static final int NO_SOUND = 0, TOO_FAR = 1, WORKS = 2, GOOD = 3;
    /** Frames in the loudest second of the voice (32 ms frames). */
    static final int SECOND_FRAMES = (int) Math.round(1.0 / SoundDetector.FRAME_S);

    /** The level of each whole frame of {@code len} samples of {@code s} (dB of full scale, {@code SoundDetector.level}). */
    public static double[] frameLevels(short[] s, int len) {
        int n = Math.max(0, Math.min(len, s.length)) / SoundDetector.FRAME;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = SoundDetector.level(s, i * SoundDetector.FRAME, SoundDetector.FRAME);
        return out;
    }

    /** The room's level: the 20th percentile of its frames, as the detector's floor; digital silence left out. NaN for none. */
    public static double roomLevel(double[] frames) {
        double[] v = sound(frames);
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        int need = (int) Math.ceil(SoundDetector.FLOOR_PERCENTILE * v.length);
        return v[Math.max(0, need - 1)];
    }

    /** The voice's level: the loudest second (power mean of 31 frames, or of all when fewer). NaN when every frame is silence. */
    public static double voiceLevel(double[] frames) {
        if (sound(frames).length == 0) return Double.NaN;
        int w = Math.min(SECOND_FRAMES, frames.length);
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + w <= frames.length; i++) {
            double sum = 0;
            for (int k = i; k < i + w; k++) sum += frames[k] <= SoundDetector.MIN_DB ? 0 : Math.pow(10, frames[k] / 10.0);
            if (sum > 0) best = Math.max(best, 10 * Math.log10(sum / w));
        }
        return best == Double.NEGATIVE_INFINITY ? Double.NaN : best;
    }

    private static double[] sound(double[] frames) {
        int n = 0;
        for (double f : frames) if (f > SoundDetector.MIN_DB) n++;
        double[] v = new double[n];
        int i = 0;
        for (double f : frames) if (f > SoundDetector.MIN_DB) v[i++] = f;
        return v;
    }

    /** The voice above the room in dB, NaN when either had no sound. */
    public static double margin(double room, double voice) {
        if (Double.isNaN(room) || Double.isNaN(voice)) return Double.NaN;
        return voice - room;
    }

    /** The start threshold from a test's margin: half of it, kept between 6 and 15 dB; the default 10 dB without sound. */
    public static double threshold(double margin) {
        if (Double.isNaN(margin) || Double.isInfinite(margin)) return SoundDetector.ABOVE_DB;
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, margin / 2));
    }

    /** Whether a stored threshold can be used (the setting refuses anything else and keeps the default). */
    public static boolean validThreshold(double db) {
        return db >= MIN_THRESHOLD - 1e-9 && db <= MAX_THRESHOLD + 1e-9;
    }

    public static int verdict(double margin) {
        if (Double.isNaN(margin)) return NO_SOUND;
        if (margin >= 20) return GOOD;
        if (margin >= 10) return WORKS;
        return TOO_FAR;
    }

    public static String verdictText(int verdict) {
        switch (verdict) {
            case GOOD: return "Good, quiet talking and snoring will be found.";
            case WORKS: return "Works, but quiet sounds may be missed. Put the phone closer to your head.";
            case TOO_FAR: return "Too far or the room is too loud (a fan?). Move the phone closer and test again.";
            default: return "The microphone gave no sound. Another app may be using it.";
        }
    }

    /** "Your voice was 22 dB above the room." */
    public static String marginLine(double margin) {
        return "Your voice was " + Math.round(margin) + " dB above the room.";
    }

    /** "A sound is kept from 11 dB above the room." */
    public static String thresholdLine(double threshold) {
        return "A sound is kept from " + fmtDb(threshold) + " dB above the room.";
    }

    /** Settings' line: "Microphone tested on 22 Nov: your voice was 22 dB above the room", or not tested yet. */
    public static String testedLine(long tested, double margin, double threshold, ZoneId zone) {
        if (tested <= 0 || Double.isNaN(margin)) return "Microphone not tested yet, so a sound is kept from " + fmtDb(threshold) + " dB above the room.";
        ZonedDateTime t = Instant.ofEpochSecond(tested).atZone(zone);
        String month = t.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        return "Microphone tested on " + t.getDayOfMonth() + " " + month + ": your voice was " + Math.round(margin) + " dB above the room.";
    }

    static String fmtDb(double v) {
        return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    // ------------------------------------------------------------------------------------- bedtime and the night's stop time

    /** Nights the usual times come from, and how many are needed before they count. */
    public static final int HISTORY = 14, MIN_NIGHTS = 7;
    /** Without enough nights: the notification at 22:30, the stop at 9:00 (clock minutes). */
    public static final int DEFAULT_REMIND_MIN = 22 * 60 + 30, DEFAULT_STOP_MIN = 9 * 60;
    /** The notification this long before the usual bedtime, the stop this long after the usual wake up (minutes). */
    public static final int BEFORE_BED_MIN = 30, AFTER_WAKE_MIN = 60;
    /** A night never listens longer than this. */
    public static final double LIMIT_S = 12 * 3600;
    private static final int BAR_FROM_MIN = 18 * 60;

    /** The usual bedtime and wake up of {@code nights} (each {start, end} unix seconds, oldest first; the last 14 count). */
    public static final class Usual {
        /** Clock minutes after midnight; NaN with fewer than {@link #MIN_NIGHTS} nights. */
        public final double bed, wake;
        public final int nights;

        Usual(double bed, double wake, int nights) {
            this.bed = bed;
            this.wake = wake;
            this.nights = nights;
        }

        public boolean known() {
            return !Double.isNaN(bed);
        }

        /** When the bedtime notification comes (clock minutes): 30 minutes before the usual bedtime, else 22:30. */
        public int remindMinute() {
            return known() ? wrap((int) Math.round(bed) - BEFORE_BED_MIN) : DEFAULT_REMIND_MIN;
        }

        /** When a night stops listening (clock minutes): an hour after the usual wake up, else 9:00. */
        public int stopMinute() {
            return known() ? wrap((int) Math.round(wake) + AFTER_WAKE_MIN) : DEFAULT_STOP_MIN;
        }
    }

    /**
     * The median clock times of the last {@link #HISTORY} nights, counted from 18:00 like the nights by hand ({@code HandNight}),
     * so a bedtime after midnight is later than one before it.
     */
    public static Usual usual(List<double[]> nights, ZoneId zone) {
        int from = Math.max(0, nights.size() - HISTORY);
        List<double[]> use = nights.subList(from, nights.size());
        if (use.size() < MIN_NIGHTS) return new Usual(Double.NaN, Double.NaN, use.size());
        double[] s = new double[use.size()], e = new double[use.size()];
        for (int i = 0; i < use.size(); i++) {
            s[i] = fromSix(use.get(i)[0], zone);
            e[i] = fromSix(use.get(i)[1], zone);
        }
        return new Usual(wrapD(Stats.median(s) + BAR_FROM_MIN), wrapD(Stats.median(e) + BAR_FROM_MIN), use.size());
    }

    private static double fromSix(double unix, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond((long) Math.floor(unix)).atZone(zone);
        return wrap(t.getHour() * 60 + t.getMinute() - BAR_FROM_MIN);
    }

    static int wrap(int m) {
        return ((m % 1440) + 1440) % 1440;
    }

    private static double wrapD(double m) {
        return ((m % 1440) + 1440) % 1440;
    }

    /** The next time (unix ms) the clock shows {@code minute} in {@code zone}, after {@code nowMs}. */
    public static long nextAt(int minute, ZoneId zone, long nowMs) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        ZonedDateTime t = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(zone);
        if (!t.toInstant().isAfter(now.toInstant())) t = now.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(zone);
        return t.toInstant().toEpochMilli();
    }

    /** "Next at 22:30 (30 min before your usual bedtime)" or "(until 7 nights are recorded)". */
    public static String remindLine(Usual u) {
        int m = u.remindMinute();
        String at = String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
        return "Next at " + at + (u.known() ? " (" + BEFORE_BED_MIN + " min before your usual bedtime)" : " (until " + MIN_NIGHTS + " nights are recorded)");
    }

    /** When a night that started at {@code start} stops by itself, and whether that is the 12 hour limit. */
    public static final class Stop {
        public final double at;
        public final boolean limit;

        Stop(double at, boolean limit) {
            this.at = at;
            this.limit = limit;
        }
    }

    /**
     * The stop of a night started at {@code start} (unix seconds): the first {@code stopMinute} on the clock at least an hour after
     * the start, or 12 hours after the start when that comes first.
     */
    public static Stop stopAt(double start, int stopMinute, ZoneId zone) {
        long from = (long) Math.floor((start + 3600) * 1000);
        double at = (nextAt(stopMinute, zone, from - 1) / 1000.0);
        double limit = start + LIMIT_S;
        return at < limit ? new Stop(at, false) : new Stop(limit, true);
    }

    // ------------------------------------------------------------------------------------- WAV (the encoder's fallback)

    /** A 16 bit mono PCM WAV file of {@code len} samples of {@code s} from {@code off}, at {@code rate} samples a second. */
    public static byte[] wav(short[] s, int off, int len, int rate) {
        int data = len * 2;
        byte[] b = new byte[44 + data];
        put(b, 0, "RIFF");
        le32(b, 4, 36 + data);
        put(b, 8, "WAVE");
        put(b, 12, "fmt ");
        le32(b, 16, 16);
        le16(b, 20, 1); // PCM
        le16(b, 22, 1); // mono
        le32(b, 24, rate);
        le32(b, 28, rate * 2);
        le16(b, 32, 2);
        le16(b, 34, 16);
        put(b, 36, "data");
        le32(b, 40, data);
        for (int i = 0; i < len; i++) le16(b, 44 + 2 * i, s[off + i]);
        return b;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int at, int v) {
        le16(b, at, v);
        le16(b, at + 2, v >> 16);
    }

    // ------------------------------------------------------------------------------------- the demo's made up clips

    /**
     * A demo clip of {@code seconds} at 16 kHz: tones made in code, never a recorded voice (Design review of #380). A low hum for
     * the room, and in the middle: for talking a 220 Hz tone in short syllables, for snoring three slow breaths of a 95 Hz tone
     * with its overtone, for a cough two short falling bursts of 1100 Hz. Deterministic.
     */
    public static short[] demoTone(String kind, double seconds) {
        int rate = SoundDetector.RATE;
        int n = (int) Math.round(seconds * rate);
        short[] out = new short[n];
        double mid = seconds / 2;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double v = 25 * Math.sin(2 * Math.PI * 50 * t);
            if (SoundDetector.SNORE.equals(kind)) {
                for (int k = -1; k <= 1; k++) {
                    double c = mid + 2.6 * k, d = t - c;
                    if (Math.abs(d) < 0.7) {
                        double env = Math.cos(d / 0.7 * Math.PI / 2);
                        v += 5000 * env * (Math.sin(2 * Math.PI * 95 * t) + 0.4 * Math.sin(2 * Math.PI * 190 * t));
                    }
                }
            } else if (SoundDetector.COUGH.equals(kind)) {
                for (int k = 0; k < 2; k++) {
                    double d = t - (mid - 0.2 + 0.45 * k);
                    if (d >= 0 && d < 0.3) v += 9000 * Math.exp(-d * 14) * Math.sin(2 * Math.PI * (1100 - 600 * d) * t);
                }
            } else {
                double d = t - (mid - 1.5);
                if (d >= 0 && d < 3) {
                    double syllable = Math.max(0, Math.sin(Math.PI * d / 0.25));
                    v += 4000 * syllable * Math.sin(2 * Math.PI * (220 + 30 * Math.sin(2 * Math.PI * 3 * d)) * t);
                }
            }
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return out;
    }
}
