package app.openring.core.withings;

import java.io.IOException;

/**
 * One sync with the watch over an open link (issue #251, phase 1b), kept apart from Android so the JVM tests run it
 * against a made up watch. The order: hello, connect, battery, set the time, activity records since a time, heart rate
 * records since a time, and always "sync done" at the end while the link is up. Each request waits for its answer
 * before the next one (the watch drops what comes while it is busy). Every message the watch sends is handed to the
 * {@link Sink} as it arrives, so what came is stored even when the session ends early. Between messages it asks the
 * {@link Gate}: when a ring task wants the ring, the session ends at once (the ring always wins) and the rest follows
 * at the next sync. The log names message types and lengths only, never a value.
 */
public final class Session {
    private Session() {}

    /** The link to the watch: whole messages in and out (the Bluetooth side cuts and joins the pieces). */
    public interface Link {
        void send(byte[] msg) throws IOException, InterruptedException;

        /** The next whole message from the watch, null when none came within {@code ms}; IOException when the link is gone. */
        byte[] next(long ms) throws IOException, InterruptedException;
    }

    /** Asked between messages. */
    public interface Gate {
        /** A ring task waits for the ring, or a ring sync started: end now. */
        boolean ringWanted();

        /** The person or Android stopped the sync. */
        boolean stopped();
    }

    /** Stores one message at once; an IOException ends the session (nothing more is asked). */
    public interface Sink {
        void received(byte[] msg) throws IOException;
    }

    public interface Log {
        void log(String line);
    }

    /** Longest wait for a simple answer, and for the next message of a data answer. */
    public static final long ANSWER_MS = 5000, DATA_SILENCE_MS = 20000;
    /** Longest wait for the answer to "sync done" (the session ends either way). */
    public static final long DONE_MS = 2000;
    /** A wait for the watch is cut into slices this long, and the gate is asked between them (the ring always wins). */
    static final long SLICE_MS = 250;
    /** Messages of one data answer that the log names one by one; the rest are summed up. */
    static final int LOGGED = 5;

    /** What to ask for. */
    public static final class Plan {
        public int androidSdk;
        /** Now (unix seconds), the zone's offset from UTC now, the next clock change (0 for none) and the offset after it. */
        public long now, nextChange;
        public int offset, offsetAfter;
        /** Records from these times on (unix seconds). */
        public long activitySince, heartSince;
        /** Longest session (ms); the session ends between messages once it is over. */
        public long limitMs = 120000;
        /** Longest wait for a simple answer and between the messages of a data answer (ms; the tests make them short). */
        public long answerMs = ANSWER_MS, silenceMs = DATA_SILENCE_MS;
    }

    /** How the session went. {@code ended} is null when everything was asked and answered, else a plain sentence. */
    public static final class Result {
        public String ended;
        public int received;
        public long bytes;
        public boolean activityDone, heartDone, doneSent;
    }

    // plain sentences for the screen; the log says more
    public static final String NO_ANSWER = "The watch did not answer. Keep it next to the phone, force stop the Withings app and try again.";
    public static final String CHALLENGE = "The watch asked for a sign in that this version cannot answer yet. Nothing was changed on the watch. Please send the log.";
    public static final String FOR_RING = "Stopped to leave Bluetooth to the ring. The rest follows at the next sync.";
    public static final String STOPPED = "The watch sync was stopped. What arrived is stored.";
    public static final String LINK_LOST = "The connection to the watch was lost. What arrived is stored, the rest follows at the next sync.";
    public static final String TOO_LONG = "The watch sent more than fits in one sync. What arrived is stored, the rest follows at the next sync.";
    public static final String NOT_STORED = "What the watch sent could not be stored. Nothing was lost on the watch. Please send the log.";

    private static final class Ended extends Exception {
        final String why;

        Ended(String why) {
            super(why);
            this.why = why;
        }
    }

    /** Runs one session over {@code link}; never throws for a watch that misbehaves, the result says what happened. */
    public static Result run(Link link, Plan plan, Gate gate, Sink sink, Log log) throws InterruptedException {
        Result r = new Result();
        long end = System.currentTimeMillis() + plan.limitMs;
        boolean linkUp = true;
        try {
            ask(link, Proto.hello(plan.androidSdk), Proto.HELLO, "hello", r, gate, sink, log, end, plan.answerMs);
            ask(link, Proto.connect(), Proto.CONNECT, "connect", r, gate, sink, log, end, plan.answerMs);
            ask(link, Proto.battery(), Proto.BATTERY, "battery", r, gate, sink, log, end, plan.answerMs);
            ask(link, Proto.setTime(plan.now, plan.offset, plan.nextChange, plan.offsetAfter), Proto.SET_TIME, "set time", r, gate, sink, log, end, plan.answerMs);
            r.activityDone = data(link, Proto.activity(plan.activitySince), Proto.ACTIVITY, "activity", r, gate, sink, log, end, plan.silenceMs);
            r.heartDone = data(link, Proto.heartRate(plan.heartSince), Proto.HEART_RATE, "heart rate", r, gate, sink, log, end, plan.silenceMs);
        } catch (Ended e) {
            r.ended = e.why;
            log.log("watch: ended early: " + e.why);
        } catch (IOException e) {
            r.ended = LINK_LOST;
            linkUp = false;
            log.log("watch: link lost (" + e.getClass().getSimpleName() + ")");
        }
        if (linkUp) {
            // always, also after a failure: the notes say the watch stops answering when a session ends without it
            try {
                byte[] done = Proto.syncDone();
                link.send(done);
                r.doneSent = true;
                log.log("watch: sent " + Frame.hexType(Proto.SYNC_DONE) + " sync done, " + done.length + " bytes");
                long until = System.currentTimeMillis() + DONE_MS;
                while (true) {
                    byte[] m = link.next(Math.max(1, until - System.currentTimeMillis()));
                    if (m == null) {
                        log.log("watch: no answer to sync done");
                        break;
                    }
                    take(m, r, sink, log);
                    if (Frame.type(m) == Proto.SYNC_DONE) break;
                }
            } catch (IOException e) {
                log.log("watch: link lost at sync done (" + e.getClass().getSimpleName() + ")");
            } catch (Ended e) {
                if (r.ended == null) r.ended = e.why;
            }
        }
        log.log("watch: session " + (r.ended == null ? "complete" : "ended") + ", " + r.received + " messages, " + r.bytes + " bytes");
        return r;
    }

    /** Stores and counts one message from the watch. */
    private static void take(byte[] m, Result r, Sink sink, Log log) throws Ended {
        r.received++;
        r.bytes += m.length;
        try {
            sink.received(m);
        } catch (IOException e) {
            log.log("watch: storing a message failed (" + e.getClass().getSimpleName() + ")");
            throw new Ended(NOT_STORED);
        }
    }

    private static void check(Gate gate, long end) throws Ended {
        if (gate.stopped()) throw new Ended(STOPPED);
        if (gate.ringWanted()) throw new Ended(FOR_RING);
        if (System.currentTimeMillis() > end) throw new Ended(TOO_LONG);
    }

    /**
     * The next message within {@code ms}, or null; the wait is cut into {@link #SLICE_MS} slices and between them the gate is
     * asked, so a ring task waits at most a slice for the ring, never a whole answer's wait.
     */
    private static byte[] next(Link link, long ms, Gate gate) throws IOException, InterruptedException, Ended {
        long until = System.currentTimeMillis() + ms;
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return null;
            byte[] m = link.next(Math.min(SLICE_MS, left));
            if (m != null) return m;
            if (gate.stopped()) throw new Ended(STOPPED);
            if (gate.ringWanted()) throw new Ended(FOR_RING);
        }
    }

    /** Sends {@code req} and waits for its answer of {@code type}; other messages are stored and logged on the way. */
    private static byte[] ask(Link link, byte[] req, int type, String what, Result r, Gate gate, Sink sink, Log log, long end, long answerMs)
            throws IOException, InterruptedException, Ended {
        check(gate, end);
        link.send(req);
        log.log("watch: sent " + Frame.hexType(type) + " " + what + ", " + req.length + " bytes");
        long until = System.currentTimeMillis() + answerMs;
        while (true) {
            long left = until - System.currentTimeMillis();
            byte[] m = left > 0 ? next(link, left, gate) : null;
            if (m == null) {
                log.log("watch: no answer to " + what + " within " + Math.max(1, answerMs / 1000) + " s");
                throw new Ended(NO_ANSWER);
            }
            take(m, r, sink, log);
            int t = Frame.type(m);
            log.log("watch: got " + Frame.hexType(t) + (Frame.fromWatch(m) ? " (the watch's own)" : "") + ", " + m.length + " bytes, "
                    + Frame.parts(m).size() + " parts");
            if (t == Proto.CHALLENGE || Frame.has(m, Proto.CHALLENGE_PART)) throw new Ended(CHALLENGE);
            if (t == type && !Frame.fromWatch(m)) return m;
            check(gate, end);
        }
    }

    /** Sends a data request and takes its answers until one holds the end part; true when the end came. */
    private static boolean data(Link link, byte[] req, int type, String what, Result r, Gate gate, Sink sink, Log log, long end, long silenceMs)
            throws IOException, InterruptedException, Ended {
        check(gate, end);
        link.send(req);
        log.log("watch: sent " + Frame.hexType(type) + " " + what + ", " + req.length + " bytes");
        int n = 0;
        long bytes = 0;
        try {
            while (true) {
                byte[] m = next(link, silenceMs, gate);
                if (m == null) {
                    log.log("watch: " + what + ": nothing more within " + Math.max(1, silenceMs / 1000) + " s, no end part");
                    throw new Ended(NO_ANSWER);
                }
                take(m, r, sink, log);
                int t = Frame.type(m);
                if (t == Proto.CHALLENGE || Frame.has(m, Proto.CHALLENGE_PART)) {
                    log.log("watch: got " + Frame.hexType(t) + ", " + m.length + " bytes");
                    throw new Ended(CHALLENGE);
                }
                if (t != type || Frame.fromWatch(m)) {
                    log.log("watch: got " + Frame.hexType(t) + (Frame.fromWatch(m) ? " (the watch's own)" : "") + " during " + what + ", " + m.length + " bytes");
                    check(gate, end);
                    continue;
                }
                n++;
                bytes += m.length;
                if (n <= LOGGED) log.log("watch: got " + Frame.hexType(t) + " " + what + " " + n + ", " + m.length + " bytes, " + Frame.parts(m).size() + " parts");
                if (Frame.has(m, Proto.END)) {
                    log.log("watch: " + what + ": " + n + " messages, " + bytes + " bytes, end reached");
                    return true;
                }
                check(gate, end);
            }
        } catch (Ended e) {
            if (n > 0) log.log("watch: " + what + ": " + n + " messages, " + bytes + " bytes, no end");
            throw e;
        }
    }
}
