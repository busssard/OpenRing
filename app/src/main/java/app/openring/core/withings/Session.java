package app.openring.core.withings;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One session with the watch over an open link (issue #251), kept apart from Android so the JVM tests run it against a
 * made up watch. It always starts with the hello (and the sign in the watch asks for right after it), then sends the
 * plan's {@link Step steps} one after the other ({@link WatchSetup} builds them: the first setup, each sync, the alarm,
 * the hand calibration), then, when the plan has {@link More}, the steps a screen hands it while it is open, and always
 * "sync done" at the end while the link is up. Each request waits for its answer before the next one (the watch drops
 * what comes while it is busy). Every message the watch sends is handed to the {@link Sink} as it arrives, so what came
 * is stored even when the session ends early. Between messages it asks the {@link Gate}: when a ring task wants the
 * ring, the session ends at once (the ring always wins). The log names message types, lengths and part counts, never a
 * value.
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

        /**
         * Stores bytes the link could not put together into a whole message (a Bluetooth piece lost on the way), as they
         * came, under {@code type}: the request whose answer they came in, so that answer counts as cut off also when it is
         * read again from the stored messages; -1 when no data answer was running.
         */
        void damaged(byte[] bytes, int type) throws IOException;
    }

    public interface Log {
        void log(String line);
    }

    /** Told when a step starts, is done or failed (the setup screen shows each step's state). */
    public interface Progress {
        void step(String key, int state);
    }

    /** {@link Progress} states. */
    public static final int STARTED = 0, DONE = 1, FAILED = 2;

    /** Steps a screen hands the open session (the hand calibration); {@link #FINISH} ends it. */
    public interface More {
        /** The next step, null when none came within {@code ms}. */
        Step next(long ms) throws InterruptedException;
    }

    /**
     * One request. {@link #ASK} waits for the answer of its type (5 s, else the session ends); {@link #DATA} takes answers
     * until one holds the end part; {@link #SOFT} waits a moment for its answer and goes on without one (the hand moves,
     * whose answer is not documented).
     */
    public static final class Step {
        public static final int ASK = 0, DATA = 1, SOFT = 2;
        /** A short name for the log and the screen, for example "activity". */
        public final String key;
        public final int kind, type;
        /** The message as it was when the step was built (what the tests compare); {@link #bytes()} is what is sent. */
        public final byte[] msg;
        /** Makes the message again when it is sent (the time, so it is right to the second also late in a session), or null. */
        final Maker maker;

        public Step(String key, int kind, byte[] msg) {
            this(key, kind, msg, null);
        }

        public Step(String key, int kind, byte[] msg, Maker maker) {
            this.key = key;
            this.kind = kind;
            this.msg = msg;
            this.maker = maker;
            this.type = Frame.type(msg);
        }

        /** The message to send now. */
        public byte[] bytes() {
            return maker != null ? maker.make() : msg;
        }
    }

    /** Builds a step's message at the moment it is sent. */
    public interface Maker {
        byte[] make();
    }

    /** The phone's clock in unix seconds (the tests move it). */
    public interface Clock {
        long now();
    }

    /** Handed by {@link More} to end the session (sync done follows). */
    public static final Step FINISH = new Step("finish", Step.SOFT, Frame.message(0));

    /** Step keys of the data requests. */
    public static final String ACTIVITY = "activity", MOVEMENT = "movement", HEART = "heart rate", SIGN_IN = "sign in", HELLO = "hello";

    /** Longest wait for a simple answer, and for the next message of a data answer. */
    public static final long ANSWER_MS = 5000, DATA_SILENCE_MS = 20000;
    /** Longest wait for the answer to "sync done" (the session ends either way). */
    public static final long DONE_MS = 2000;
    /** Longest wait for the answer to a {@link Step#SOFT} step. */
    public static final long SOFT_MS = 1500;
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
        /** Records from these times on (unix seconds); used by the steps {@link #steps} makes when none are given. */
        public long activitySince, heartSince;
        /** The steps after the hello; null: the plain sync ({@link WatchSetup#basicSync}). */
        public List<Step> steps;
        /** Steps handed while the session is open (the hand calibration), null for none. */
        public More more;
        /**
         * Steps sent last, before sync done, also when the session ends early while the link is up (then each once, without
         * waiting long): the hand calibration's end, so the watch never stays in calibration mode. Null for none.
         */
        public List<Step> last;
        /** Told about each step, may be null. */
        public Progress progress;
        /** Longest session (ms); the session ends between messages once it is over. */
        public long limitMs = 120000;
        /** Longest wait for a simple answer and between the messages of a data answer (ms; the tests make them short). */
        public long answerMs = ANSWER_MS, silenceMs = DATA_SILENCE_MS;
        /** The address Android connected to; used for the sign in only when the watch's challenge names none. */
        public String address;
        /** Our own challenge for the sign in ({@link SignIn#OURS} bytes); null makes a random one (the tests fix it). */
        public byte[] ours;
        /**
         * The phone's clock and zone: a step that sets the watch's time reads them when it is sent, so the time that ends a
         * hand calibration of some minutes is still right (review of #332). Null: {@link #now} and the offsets above.
         */
        public Clock clock;
        public java.time.ZoneId zone;

        /** Sets {@link #now}, the offset now, the next clock change and the offset after it, from {@code zone}. */
        public void at(long now, java.time.ZoneId zone) {
            this.now = now;
            java.time.Instant t = java.time.Instant.ofEpochSecond(now);
            offset = zone.getRules().getOffset(t).getTotalSeconds();
            java.time.zone.ZoneOffsetTransition next = zone.getRules().nextTransition(t);
            nextChange = next != null ? next.getInstant().getEpochSecond() : 0;
            offsetAfter = next != null ? next.getOffsetAfter().getTotalSeconds() : 0;
        }

        List<Step> steps() {
            return steps != null ? steps : WatchSetup.basicSync(this);
        }
    }

    /** {@link Result#signIn}: the watch did not ask, we answered and wait, the watch went on after our answer. */
    public static final int SIGN_IN_NONE = 0, SIGN_IN_SENT = 1, SIGN_IN_ACCEPTED = 2;

    /** How the session went. {@code ended} is null when everything was asked and answered, else a plain sentence. */
    public static final class Result {
        public String ended;
        public int received;
        public long bytes;
        public boolean activityDone, movementDone, heartDone, doneSent;
        /** The keys of the steps that got their answer (data steps: their end), in order. */
        public final Set<String> done = new LinkedHashSet<String>();
        /**
         * The keys of the data steps whose end came but which lost a piece on the way: not done, their start does not move
         * (review of dadbb71).
         */
        public final Set<String> cut = new LinkedHashSet<String>();
        /** Bytes handed on that were no whole message (each stored as it came). */
        public int damaged;
        /** The key of the step the session ended in, null when none or all were done. */
        public String failedStep;
        /** How the sign in went ({@link #SIGN_IN_NONE}, {@link #SIGN_IN_SENT}, {@link #SIGN_IN_ACCEPTED}). */
        public int signIn = SIGN_IN_NONE;
        /** True once a step after the hello was sent: from then on the watch may have taken something. */
        public boolean wrote;
        /** Our challenge and the address of the last answered sign in, to check the watch's own proof; never logged. */
        byte[] ours;
        String signAddress;
        boolean askAnswered;
    }

    // plain sentences for the screen; the log says more
    public static final String NO_ANSWER = "The watch did not answer. Keep it next to the phone, force stop the Withings app and try again.";
    public static final String CHALLENGE = "The watch asked for a sign in of a kind this version does not know. Nothing was changed on the watch. Please send the log.";
    public static final String SIGN_IN_REFUSED = "The watch did not accept the sign in. Nothing was changed on the watch. Please send the log.";
    /** A refusal after the watch was already told something (review of #317). */
    public static final String SIGN_IN_REFUSED_LATE = "The watch did not accept the sign in. What arrived is stored. Please send the log.";
    public static final String WATCH_PROOF = "The watch answered the sign in wrongly, so nothing more was asked. Please send the log.";
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
        String current = HELLO;
        try {
            progress(plan, HELLO, STARTED);
            ask(link, new Step(HELLO, Step.ASK, Proto.hello(plan.androidSdk)), r, gate, sink, log, end, plan);
            done(plan, r, HELLO);
            for (Step s : plan.steps()) {
                current = s.key;
                run(link, s, r, gate, sink, log, end, plan);
            }
            if (plan.more != null) {
                while (true) {
                    check(gate, end);
                    Step s = plan.more.next(SLICE_MS);
                    if (s == FINISH) break;
                    if (s == null) {
                        // keep reading: the watch's own messages are stored meanwhile
                        byte[] m = link.next(1);
                        if (m != null && !damaged(m, -1, null, r, sink, log)) {
                            take(m, r, sink, log);
                            own(link, m, r, log);
                        }
                        continue;
                    }
                    current = s.key;
                    run(link, s, r, gate, sink, log, end, plan);
                }
            }
            if (plan.last != null) {
                for (Step s : plan.last) {
                    current = s.key;
                    run(link, s, r, gate, sink, log, end, plan);
                }
            }
            current = null;
        } catch (Ended e) {
            r.ended = e.why;
            r.failedStep = current;
            log.log("watch: ended early" + (current != null ? " at " + current : "") + ": " + e.why);
        } catch (IOException e) {
            // the watch closing the link right after our answer most likely means it refused it
            r.ended = r.signIn == SIGN_IN_SENT ? refused(r) : LINK_LOST;
            r.failedStep = r.signIn == SIGN_IN_SENT ? SIGN_IN : current;
            linkUp = false;
            log.log("watch: link lost" + (r.signIn == SIGN_IN_SENT ? " after the sign in answer" : "") + " (" + e.getClass().getSimpleName() + ")");
        }
        if (r.failedStep != null) progress(plan, r.failedStep, FAILED);
        if (linkUp && r.ended != null && plan.last != null) {
            // the session ended early: the last steps still go out once, so the watch is left as it should be
            for (Step s : plan.last) {
                if (r.done.contains(s.key)) continue;
                try {
                    soft(link, s, r, OPEN, sink, log, Long.MAX_VALUE, plan);
                    done(plan, r, s.key);
                } catch (IOException e) {
                    linkUp = false;
                    log.log("watch: link lost at " + s.key + " (" + e.getClass().getSimpleName() + ")");
                    break;
                } catch (Ended e) {
                    log.log("watch: " + s.key + " not sent: " + e.why);
                    break;
                }
            }
        }
        r.activityDone = r.done.contains(ACTIVITY);
        r.movementDone = r.done.contains(MOVEMENT);
        r.heartDone = r.done.contains(HEART);
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
                    if (damaged(m, -1, null, r, sink, log)) continue;
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

    /** A gate that never stops: for the last steps after an early end. */
    private static final Gate OPEN = new Gate() {
        @Override
        public boolean ringWanted() {
            return false;
        }

        @Override
        public boolean stopped() {
            return false;
        }
    };

    private static void progress(Plan plan, String key, int state) {
        if (plan.progress != null) plan.progress.step(key, state);
    }

    private static void done(Plan plan, Result r, String key) {
        r.done.add(key);
        progress(plan, key, DONE);
    }

    private static String refused(Result r) {
        return r.wrote ? SIGN_IN_REFUSED_LATE : SIGN_IN_REFUSED;
    }

    private static void run(Link link, Step s, Result r, Gate gate, Sink sink, Log log, long end, Plan plan) throws IOException, InterruptedException, Ended {
        progress(plan, s.key, STARTED);
        if (s.kind == Step.DATA) {
            if (data(link, s, r, gate, sink, log, end, plan)) done(plan, r, s.key);
        } else if (s.kind == Step.SOFT) {
            soft(link, s, r, gate, sink, log, end, plan);
            done(plan, r, s.key);
        } else {
            ask(link, s, r, gate, sink, log, end, plan);
            done(plan, r, s.key);
        }
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

    /**
     * True when {@code m} is no whole message but bytes the link had to drop (a piece lost on the way): they are stored as
     * they came, under {@code type} (the running data request, -1 for none), and counted. The log names the step and the
     * length, never a value.
     */
    private static boolean damaged(byte[] m, int type, String key, Result r, Sink sink, Log log) throws Ended {
        if (Frame.whole(m)) return false;
        r.received++;
        r.bytes += m.length;
        r.damaged++;
        try {
            sink.damaged(m, type);
        } catch (IOException e) {
            log.log("watch: storing a message failed (" + e.getClass().getSimpleName() + ")");
            throw new Ended(NOT_STORED);
        }
        log.log("watch: " + m.length + " bytes lost on the way" + (key != null ? " during " + key + ", the answer counts as cut off" : "") + ", stored as they came");
        return true;
    }

    /** The watch's own sync ask gets the empty answer once per session (as Gadgetbridge does); other own messages are only stored. */
    private static void own(Link link, byte[] m, Result r, Log log) throws IOException, InterruptedException {
        if (!Frame.fromWatch(m) || Frame.type(m) != Proto.SYNC_ASK || r.askAnswered) return;
        byte[] reply = Proto.syncAskReply();
        link.send(reply);
        r.askAnswered = true;
        log.log("watch: the watch asked for a sync itself, sent the answer, " + reply.length + " bytes");
    }

    /** True when {@code m} is the watch asking for a sign in. */
    private static boolean asksSignIn(byte[] m) {
        return Frame.type(m) == Proto.CHALLENGE || Frame.has(m, Proto.CHALLENGE_PART);
    }

    /**
     * Answers the watch's sign in {@code m}. A second ask before the watch went on after the first answer means it
     * refused that answer; a challenge of an unknown layout cannot be answered. Both end the session.
     */
    private static void signIn(Link link, byte[] m, Plan plan, Result r, Log log) throws IOException, InterruptedException, Ended {
        if (r.signIn == SIGN_IN_SENT) {
            log.log("watch: asked for a sign in again, the first answer was not accepted");
            progress(plan, SIGN_IN, FAILED);
            throw new Ended(refused(r));
        }
        progress(plan, SIGN_IN, STARTED);
        byte[] ours = plan.ours;
        if (ours == null) {
            ours = new byte[SignIn.OURS];
            new SecureRandom().nextBytes(ours);
        }
        SignIn.Challenge c = SignIn.read(m, plan.address);
        byte[] answer = SignIn.answer(c, ours);
        if (answer == null) {
            log.log("watch: the sign in is of a kind not known, " + m.length + " bytes, " + Frame.parts(m).size() + " parts");
            progress(plan, SIGN_IN, FAILED);
            throw new Ended(CHALLENGE);
        }
        link.send(answer);
        r.signIn = SIGN_IN_SENT;
        r.ours = ours;
        r.signAddress = c.address;
        log.log("watch: sent " + Frame.hexType(Proto.CHALLENGE) + " sign in answer, " + answer.length + " bytes"
                + (plan.address != null ? ", address in the challenge is the connected one: " + (c.address.equalsIgnoreCase(plan.address) ? "yes" : "no") : ""));
    }

    /** The awaited answer came after our sign in answer: the watch accepted it; its own proof, when it sends one, must be right. */
    private static void accepted(byte[] m, Result r, Log log, Plan plan) throws Ended {
        if (r.signIn != SIGN_IN_SENT) return;
        Boolean proof = SignIn.watchProof(m, r.ours, r.signAddress);
        log.log("watch: the watch's own sign in proof: " + (proof == null ? "none sent" : proof ? "right" : "wrong"));
        if (Boolean.FALSE.equals(proof)) {
            progress(plan, SIGN_IN, FAILED);
            throw new Ended(WATCH_PROOF);
        }
        r.signIn = SIGN_IN_ACCEPTED;
        done(plan, r, SIGN_IN);
        log.log("watch: sign in accepted");
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

    private static void send(Link link, Step s, Result r, Log log) throws IOException, InterruptedException {
        byte[] msg = s.bytes();
        link.send(msg);
        if (s.type != Proto.HELLO) r.wrote = true;
        log.log("watch: sent " + Frame.hexType(s.type) + " " + s.key + ", " + msg.length + " bytes");
    }

    /** Sends {@code s} and waits for its answer of its type; other messages are stored and logged on the way. */
    private static byte[] ask(Link link, Step s, Result r, Gate gate, Sink sink, Log log, long end, Plan plan)
            throws IOException, InterruptedException, Ended {
        long answerMs = plan.answerMs;
        check(gate, end);
        send(link, s, r, log);
        long until = System.currentTimeMillis() + answerMs;
        while (true) {
            long left = until - System.currentTimeMillis();
            byte[] m = left > 0 ? next(link, left, gate) : null;
            if (m == null) {
                if (r.signIn == SIGN_IN_SENT) {
                    log.log("watch: no answer to " + s.key + " within " + Math.max(1, answerMs / 1000) + " s after the sign in answer");
                    progress(plan, SIGN_IN, FAILED);
                    throw new Ended(refused(r));
                }
                log.log("watch: no answer to " + s.key + " within " + Math.max(1, answerMs / 1000) + " s");
                throw new Ended(NO_ANSWER);
            }
            if (damaged(m, -1, null, r, sink, log)) {
                check(gate, end);
                continue;
            }
            take(m, r, sink, log);
            int t = Frame.type(m);
            log.log("watch: got " + Frame.hexType(t) + (Frame.fromWatch(m) ? " (the watch's own)" : "") + ", " + m.length + " bytes, "
                    + Frame.parts(m).size() + " parts");
            if (asksSignIn(m)) {
                signIn(link, m, plan, r, log);
                // the watch answers the request once it accepted the sign in: a fresh wait for it
                until = System.currentTimeMillis() + answerMs;
                check(gate, end);
                continue;
            }
            own(link, m, r, log);
            if (t == s.type && !Frame.fromWatch(m)) {
                accepted(m, r, log, plan);
                return m;
            }
            check(gate, end);
        }
    }

    /** Sends {@code s}, waits a moment for its answer and goes on without one. */
    private static void soft(Link link, Step s, Result r, Gate gate, Sink sink, Log log, long end, Plan plan)
            throws IOException, InterruptedException, Ended {
        check(gate, end);
        send(link, s, r, log);
        long until = System.currentTimeMillis() + Math.min(SOFT_MS, plan.answerMs);
        while (true) {
            long left = until - System.currentTimeMillis();
            byte[] m = left > 0 ? next(link, left, gate) : null;
            if (m == null) {
                log.log("watch: no answer to " + s.key + ", going on");
                return;
            }
            if (damaged(m, -1, null, r, sink, log)) continue;
            take(m, r, sink, log);
            own(link, m, r, log);
            if (Frame.type(m) == s.type && !Frame.fromWatch(m)) return;
        }
    }

    /**
     * Sends a data request and takes its answers until one holds the end part; true when the end came whole. When a piece
     * of the answer was lost on the way, its end still ends the step, but the step is not done ({@link Result#cut}): the
     * session goes on and the request starts from the same point next time.
     */
    private static boolean data(Link link, Step s, Result r, Gate gate, Sink sink, Log log, long end, Plan plan)
            throws IOException, InterruptedException, Ended {
        long silenceMs = plan.silenceMs;
        check(gate, end);
        send(link, s, r, log);
        int n = 0;
        long bytes = 0;
        boolean lost = false;
        // the part types of the answers and how often each came: names the kinds of records, never a value
        Map<Integer, Integer> parts = new TreeMap<Integer, Integer>();
        try {
            while (true) {
                byte[] m = next(link, silenceMs, gate);
                if (m == null) {
                    if (r.signIn == SIGN_IN_SENT) {
                        // review of #317: the same silence as in ask() after a sign in answer is a refusal
                        log.log("watch: " + s.key + ": nothing within " + Math.max(1, silenceMs / 1000) + " s after the sign in answer");
                        progress(plan, SIGN_IN, FAILED);
                        throw new Ended(refused(r));
                    }
                    log.log("watch: " + s.key + ": nothing more within " + Math.max(1, silenceMs / 1000) + " s, no end part");
                    throw new Ended(NO_ANSWER);
                }
                if (damaged(m, s.type, s.key, r, sink, log)) {
                    lost = true;
                    check(gate, end);
                    continue;
                }
                take(m, r, sink, log);
                int t = Frame.type(m);
                if (asksSignIn(m)) {
                    log.log("watch: got " + Frame.hexType(t) + " during " + s.key + ", " + m.length + " bytes");
                    signIn(link, m, plan, r, log);
                    check(gate, end);
                    continue;
                }
                if (t != s.type || Frame.fromWatch(m)) {
                    log.log("watch: got " + Frame.hexType(t) + (Frame.fromWatch(m) ? " (the watch's own)" : "") + " during " + s.key + ", " + m.length + " bytes");
                    own(link, m, r, log);
                    check(gate, end);
                    continue;
                }
                accepted(m, r, log, plan);
                n++;
                bytes += m.length;
                for (Frame.Part p : Frame.parts(m)) {
                    Integer k = parts.get(p.type);
                    parts.put(p.type, k == null ? 1 : k + 1);
                }
                if (n <= LOGGED) log.log("watch: got " + Frame.hexType(t) + " " + s.key + " " + n + ", " + m.length + " bytes, " + Frame.parts(m).size() + " parts");
                if (Frame.has(m, Proto.END)) {
                    log.log("watch: " + s.key + ": " + n + " messages, " + bytes + " bytes, end reached");
                    log.log("watch: " + s.key + " part types: " + partList(parts));
                    if (lost) {
                        log.log("watch: " + s.key + ": a piece was lost on the way, the answer counts as cut off and is asked again from the same start");
                        r.cut.add(s.key);
                        progress(plan, s.key, FAILED);
                        return false;
                    }
                    return true;
                }
                check(gate, end);
            }
        } catch (Ended e) {
            if (n > 0) {
                log.log("watch: " + s.key + ": " + n + " messages, " + bytes + " bytes, no end");
                log.log("watch: " + s.key + " part types: " + partList(parts));
            }
            throw e;
        }
    }

    /** "0x0601 40, 0x0602 40": part types and how often each came. */
    static String partList(Map<Integer, Integer> parts) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<Integer, Integer> e : parts.entrySet()) out.add(Frame.hexType(e.getKey()) + " " + e.getValue());
        return out.isEmpty() ? "none" : String.join(", ", out);
    }
}
