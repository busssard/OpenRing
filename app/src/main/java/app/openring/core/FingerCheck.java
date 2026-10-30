package app.openring.core;

import app.openring.core.analysis.FingerNow;
import app.openring.core.analysis.LastWorn;

/**
 * What Find my ring does about "is it on a finger now?" (issue #192), kept apart from Bluetooth so the JVM tests cover
 * it: when the screen asks the ring by itself, what the last worn strip says, what its chip offers ("Make it blink",
 * "Stop", "Ask again"), when the blink ends and what the instruments say while the finder holds the ring.
 * {@code ui/FinderActivity} keeps one per screen and {@code ble/RingManager.askFinger} does the asking.
 *
 * <p>The ring is asked once per visit of the screen, as soon as it is heard, Bluetooth is on, no walk is being recorded
 * and no other task holds the ring; a sync, Live or a game, and a long re analysis (issue #230), make it wait and ask
 * by itself once they are over. A failure is not retried by itself: the chip offers "Ask again".
 */
public final class FingerCheck {
    public enum Phase { IDLE, WAITING, ASKING, ANSWERED, FAILED, BLINKING }

    /** What a tap on the chip does. */
    public enum Action { NONE, STOP, ASK, BLINK }

    /** How long the ring measures (and so blinks) after "Make it blink". */
    public static final long BLINK_MS = 30000;
    /** An answer this fresh (ms) is kept when the screen comes back; an older one is asked again. */
    public static final long KEEP_MS = 120000;
    /** After the ring was refused (busy, re analysis), the next automatic ask waits at least this long (ms). */
    public static final long RETRY_MS = 5000;

    public static final String ASKING = "Asking the ring if it is on a finger now.";
    public static final String BLINK_CONNECTING = "Connecting to make the ring blink.";
    /** A sync, Live, a game or a Settings action holds the ring (review of #245: not only a sync). */
    public static final String WAIT_RING = "Asks the ring once it is free.";
    public static final String WAIT_ANALYSIS = "Asks the ring after the re analysis.";
    public static final String NOT_ASKED = "Could not ask the ring now.";
    public static final String STOPPED = "Stopped before the ring answered.";

    /** The instruments card while the finder holds the ring: hot and cold hears nothing then. */
    public static final String BLIND_ASKING = "Asking the ring if it is on a finger. While connected it sends no signal, so hot and cold pauses for a few seconds.";
    public static final String BLIND_BLINKING = "Hot and cold is blind while the ring blinks (30 seconds). On a finger its green light flashes; "
            + "off a finger it may stay dark, not tested on Ring 4 yet.";
    public static final String BLIND_DEMO = "In the demo nothing blinks. With your ring, hot and cold is blind for these 30 seconds; "
            + "on a finger its green light flashes, off a finger it may stay dark.";
    /** Under the evidence: what the chip does. */
    public static final String BLINK_NOTE = "Make it blink measures heart rate for 30 seconds, so on a finger the ring's green light flashes and "
            + "whoever wears it notices. Off a finger it may stay dark (not tested on Ring 4 yet). Hot and cold is blind meanwhile.";

    /** The inputs of one look at the screen. */
    public static final class Now {
        public long ms;
        public boolean demo, paired, bluetoothOn, heard, recording, ringHeld, longRun;
    }

    private Phase phase = Phase.IDLE;
    private FingerNow answer;
    private long answeredAt, blinkStart, notBefore;
    private boolean asked, blinkWanted;
    private String why;
    private long left = -1;

    public Phase phase() {
        return phase;
    }

    public FingerNow answer() {
        return answer;
    }

    /** The plain reason of the last failure or wait, null when none. */
    public String why() {
        return why;
    }

    /** The finder holds the ring now (asking or blinking): hot and cold hears nothing. */
    public boolean holding() {
        return phase == Phase.ASKING || phase == Phase.BLINKING;
    }

    /**
     * Whether the screen asks the ring by itself now. Sets the waiting phase when another task or a long re analysis
     * holds the ring. Never in demo mode (the screen shows {@link FingerNow#demo} instead).
     */
    public boolean autoAsk(Now n) {
        if (n.demo || !n.paired || holding()) return false;
        if (asked && phase != Phase.WAITING) return false;
        if (!n.bluetoothOn || !n.heard || n.recording) return false;
        if (n.ringHeld) {
            waitFor(WAIT_RING);
            return false;
        }
        if (n.longRun) {
            waitFor(WAIT_ANALYSIS);
            return false;
        }
        return n.ms >= notBefore;
    }

    private void waitFor(String text) {
        phase = Phase.WAITING;
        why = text;
    }

    /** The demo's answer, at once and without the ring. */
    public void demoAnswer(long ms, double unix) {
        asked = true;
        answer = FingerNow.demo(unix);
        answeredAt = ms;
        phase = Phase.ANSWERED;
    }

    /** The screen started asking ({@code blink}: and then to blink). */
    public void start(long ms, boolean blink) {
        asked = true;
        blinkWanted = blink;
        phase = Phase.ASKING;
        why = null;
        left = -1;
    }

    /** Bytes the download still has to fetch, as the ring reported it (-1 unknown). */
    public void downloading(long bytes) {
        left = bytes;
    }

    public void answered(long ms, FingerNow a) {
        if (a == null) return;
        answer = a;
        answeredAt = ms;
        left = -1;
    }

    /** The ring measures now: the blink runs for {@link #BLINK_MS}. */
    public void blinking(long ms) {
        phase = Phase.BLINKING;
        blinkStart = ms;
    }

    /** Whole seconds of the blink left (0 when over or not blinking). */
    public long blinkLeft(long ms) {
        if (phase != Phase.BLINKING) return 0;
        return Math.max(0, (blinkStart + BLINK_MS - ms + 999) / 1000);
    }

    /** The blink's time is over (the demo ends it here; the ring task ends by itself). */
    public boolean blinkOver(long ms) {
        return phase == Phase.BLINKING && ms >= blinkStart + BLINK_MS;
    }

    /**
     * The ring task ended. {@code raw} is its raw error text, null when it worked. A busy ring or a long re analysis
     * of an automatic ask waits and asks again by itself; any other failure waits for "Ask again".
     */
    public void done(long ms, String raw) {
        left = -1;
        if (raw == null) {
            phase = answer != null ? Phase.ANSWERED : Phase.FAILED;
            if (answer == null) why = NOT_ASKED;
            return;
        }
        SyncError.Kind k = SyncError.kind(raw);
        if ((k == SyncError.Kind.BUSY || k == SyncError.Kind.REANALYSING) && !blinkWanted) {
            asked = false;
            notBefore = ms + RETRY_MS;
            waitFor(k == SyncError.Kind.BUSY ? WAIT_RING : WAIT_ANALYSIS);
            return;
        }
        phase = Phase.FAILED;
        why = k == SyncError.Kind.STOPPED ? STOPPED : SyncError.plain(raw, SyncError.Task.LIVE);
    }

    /** The person tapped Stop: the screen stops the ring task, whose end then reports through {@link #done}. */
    public void stopped(long ms) {
        if (phase == Phase.BLINKING) phase = answer != null ? Phase.ANSWERED : Phase.FAILED;
        else if (phase == Phase.ASKING) done(ms, "cancelled");
    }

    /**
     * Start of a walk or turn: true when the finder holds the ring, which the screen then stops first (the ring sends
     * no signal while connected, so the walk would hear nothing, review of #245).
     */
    public boolean walkStarts(long ms) {
        if (!holding()) return false;
        stopped(ms);
        return true;
    }

    /** The screen left the front: the ring task stops, and the next visit asks again. */
    public void paused(long ms) {
        if (holding()) phase = answer != null ? Phase.ANSWERED : Phase.IDLE;
        if (phase == Phase.WAITING) phase = Phase.IDLE;
        asked = false;
    }

    /** The screen is in front again: a fresh answer stays, an older one is dropped and asked again. */
    public void resumed(long ms) {
        if (answer != null && ms - answeredAt < KEEP_MS && !answer.demo) asked = true;
        else if (answer != null && !answer.demo) {
            answer = null;
            phase = Phase.IDLE;
        }
    }

    /** The strip's line: the answer, what is going on, or the stored last worn line. */
    public String brief(String stored) {
        switch (phase) {
            case ASKING:
                if (answer != null) return answer.brief();
                String s = blinkWanted ? BLINK_CONNECTING : ASKING;
                if (left > 1024) s = s.substring(0, s.length() - 1) + ", " + Math.round(left / 1024.0) + " KB left.";
                return s;
            case WAITING:
                return why;
            case FAILED:
                return answer != null ? answer.brief() : STOPPED.equals(why) ? STOPPED : NOT_ASKED;
            case ANSWERED:
            case BLINKING:
                return answer.brief();
            default:
                return stored;
        }
    }

    /** The chip at the right end of the strip, null for none. */
    public String chip(long ms, boolean paired, boolean heard, boolean recording) {
        switch (action(paired, heard, recording)) {
            case STOP: return phase == Phase.BLINKING ? "Stop, " + blinkLeft(ms) + " s" : "Stop";
            case ASK: return "Ask again";
            case BLINK: return "Make it blink";
            default: return null;
        }
    }

    /**
     * What a tap on the chip does. Nothing while a walk or turn is recorded: the ring sends no signal while connected,
     * which would spoil the walk (review of #245).
     */
    public Action action(boolean paired, boolean heard, boolean recording) {
        if (recording) return Action.NONE;
        if (!paired && (answer == null || !answer.demo)) return Action.NONE;
        switch (phase) {
            case ASKING:
            case BLINKING:
                return Action.STOP;
            case WAITING:
                return Action.NONE;
            case FAILED:
                return answer == null ? Action.ASK : Action.BLINK;
            case ANSWERED:
                return Action.BLINK;
            default:
                return heard ? Action.BLINK : Action.NONE;
        }
    }

    /** The opened strip: the evidence, the failure if any, and what Make it blink does. Null before any answer or failure. */
    public String detail(LastWorn.Clock clock) {
        StringBuilder b = new StringBuilder();
        if (answer != null) b.append(answer.detail(clock));
        if ((phase == Phase.FAILED || phase == Phase.WAITING) && why != null && !why.equals(NOT_ASKED)) {
            if (b.length() > 0) b.append(' ');
            b.append(why.equals(STOPPED) || phase == Phase.WAITING ? why : "Could not ask the ring. " + why);
        }
        if (b.length() == 0) return null;
        return b.append('\n').append(BLINK_NOTE).toString();
    }

    /** The instruments card while the finder holds the ring, null otherwise. */
    public String blind(boolean demo) {
        if (phase == Phase.BLINKING) return demo ? BLIND_DEMO : BLIND_BLINKING;
        if (phase == Phase.ASKING) return BLIND_ASKING;
        return null;
    }
}
