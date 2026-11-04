import app.openring.core.Hex;
import app.openring.core.RingGate;
import app.openring.core.withings.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * JVM tests for the Withings watch sync (issue #251, phase 1b): the frame, the requests, the decoder, the session
 * against a made up watch, the sign in, the day sums and when a sync is due. Every message here is made up; none comes from a real
 * watch. Run with tests/run_tests.sh, after CoreTest.
 */
public class WatchTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  got " + detail);
        check(name, ok);
    }

    public static void main(String[] args) throws Exception {
        frame();
        assembler();
        requests();
        records();
        session();
        signIn();
        signInSession();
        setupMessages();
        setupSession();
        days();
        due();
        demo();
        gate();
        mainDevice();
        mainDeviceEdges();
        System.out.println("watch: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------------------------ frame

    static void frame() {
        byte[] m = Frame.message(0x0504, Frame.part(0x0504, new byte[] {80, 2, 0, 0, 0x0b, (byte) 0xb8}));
        check("frame: start byte, type and length are big endian", Hex.encode(m).equals("01050400" + "0a" + "05040006" + "500200000bb8"), Hex.encode(m));
        check("frame: the type reads back", Frame.type(m) == 0x0504);
        check("frame: an answer is not the watch's own message", !Frame.fromWatch(m));
        List<Frame.Part> ps = Frame.parts(m);
        check("frame: one part with its value", ps.size() == 1 && ps.get(0).type == 0x0504 && ps.get(0).value.length == 6 && ps.get(0).value[0] == 80);
        byte[] own = Hex.decode("0141410000");
        check("frame: a top bit marks the watch's own message, the type without it", Frame.fromWatch(own) && Frame.type(own) == 0x0141, Frame.type(own));
        byte[] own2 = Hex.decode("0181150000");
        check("frame: the other top bit too", Frame.fromWatch(own2) && Frame.type(own2) == 0x0115);
        check("frame: too short has no type", Frame.type(new byte[] {1, 2}) == -1 && Frame.parts(new byte[] {1}).isEmpty() && !Frame.fromWatch(null));
        // a part that claims more than is there: the parts before it are kept, nothing throws
        byte[] cut = Frame.message(0x0978, Frame.part(0x0601, Frame.be32(1000)), Frame.part(0x0602, new byte[] {0, 60}));
        cut[HEADER_LEN_AT() + 4 + 4 + 3] = 9; // the second part says 9 bytes
        List<Frame.Part> cp = Frame.parts(cut);
        check("frame: a damaged part ends the list, the parts before it stay", cp.size() == 1 && cp.get(0).type == 0x0601, cp.size());
        check("frame: has and first", Frame.has(m, 0x0504) && !Frame.has(m, 0x0100) && Frame.first(m, 0x0100) == null);
        check("frame: an empty message", Frame.parts(Frame.message(0x0111)).isEmpty() && Frame.message(0x0111).length == 5);
        check("frame: the log's type text", Frame.hexType(0x0504).equals("0x0504") && Frame.hexType(0x41).equals("0x0041"));
        check("frame: numbers", Frame.u32(Frame.be32(4000000000L), 0) == 4000000000L && Frame.s32(Frame.be32(-3600), 0) == -3600);
        List<byte[]> pcs = Frame.pieces(new byte[45], 20);
        check("frame: pieces of at most the size, in order", pcs.size() == 3 && pcs.get(0).length == 20 && pcs.get(2).length == 5);
        boolean threw = false;
        try {
            Frame.pieces(new byte[3], 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("frame: a piece size of 0 is refused", threw);
        threw = false;
        try {
            Frame.message(1, new byte[70000]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("frame: a message longer than its length field is refused", threw);
    }

    static int HEADER_LEN_AT() {
        return Frame.HEADER;
    }

    static void assembler() {
        Random r = new Random(251);
        byte[] a = WatchDemo.helloReply("Steel HR test", 1234);
        byte[] b = Frame.message(Proto.ACTIVITY, WatchDemo.time(1000), WatchDemo.duration(60), WatchDemo.steps(42, 30), WatchDemo.end());
        boolean all = true;
        for (int round = 0; round < 200; round++) {
            byte[] joined = new byte[a.length + b.length];
            System.arraycopy(a, 0, joined, 0, a.length);
            System.arraycopy(b, 0, joined, a.length, b.length);
            // pieces never span two messages (the watch sends each message on its own), any split inside
            List<byte[]> got = new ArrayList<byte[]>();
            Frame.Assembler as = new Frame.Assembler();
            for (byte[] msg : new byte[][] {a, b}) {
                int i = 0;
                while (i < msg.length) {
                    int n = 1 + r.nextInt(Math.min(20, msg.length - i));
                    byte[] p = Arrays.copyOfRange(msg, i, i + n);
                    byte[] done = as.feed(p);
                    if (done != null) got.add(done);
                    i += n;
                }
            }
            all &= got.size() == 2 && Arrays.equals(got.get(0), a) && Arrays.equals(got.get(1), b) && !as.pending();
        }
        check("assembler: two messages in any split come out whole and in order", all);
        Frame.Assembler as = new Frame.Assembler();
        check("assembler: a piece without a start byte while nothing is open is dropped", as.feed(new byte[] {5, 6}) == null && as.dropped == 1);
        check("assembler: an empty piece does nothing", as.feed(new byte[0]) == null && as.feed(null) == null && as.dropped == 1);
        byte[] first = Arrays.copyOfRange(a, 0, 3);
        check("assembler: a message waits for its length", as.feed(first) == null && as.pending());
        byte[] rest = Arrays.copyOfRange(a, 3, a.length);
        byte[] over = Arrays.copyOf(rest, rest.length + 2);
        check("assembler: a message longer than its length is dropped, not shifted into the next", as.feed(over) == null && as.dropped == 2 && !as.pending());
        check("assembler: the next message comes out whole after that", Arrays.equals(as.feed(b), b));
    }

    // --------------------------------------------------------------------------------- requests

    static void requests() {
        byte[] h = Proto.hello(28);
        // the same bytes as the public recording of a pairing (Gadgetbridge issue 854), which had Android 9 (28)
        check("hello: the bytes of the public recording", Hex.encode(h).equals("0101010010012a00060101003d7e9609280002001c"), Hex.encode(h));
        check("connect, battery and sync done are empty messages", Hex.encode(Proto.connect()).equals("0101110000") && Hex.encode(Proto.battery()).equals("0105040000")
                && Hex.encode(Proto.syncDone()).equals("0101150000"));
        byte[] t = Proto.setTime(1700000000L, 3600, 1711846800L, 7200);
        Frame.Part tp = Frame.first(t, Proto.TIME);
        check("set time: now, offset, next change, offset after", Frame.type(t) == Proto.SET_TIME && tp != null && tp.value.length == 16
                && Frame.u32(tp.value, 0) == 1700000000L && Frame.s32(tp.value, 4) == 3600 && Frame.u32(tp.value, 8) == 1711846800L && Frame.s32(tp.value, 12) == 7200);
        byte[] west = Proto.setTime(1700000000L, -18000, 0, 0);
        check("set time: an offset west of UTC is negative", Frame.s32(Frame.first(west, Proto.TIME).value, 4) == -18000);
        byte[] act = Proto.activity(1700000000L);
        Frame.Part sp = Frame.first(act, Proto.SINCE);
        check("activity: since and a count of 0 (all)", Frame.type(act) == Proto.ACTIVITY && sp.value.length == 6 && Frame.u32(sp.value, 0) == 1700000000L
                && Frame.u16(sp.value, 4) == 0);
        check("activity: a negative since asks from 0", Frame.u32(Frame.first(Proto.activity(-5), Proto.SINCE).value, 0) == 0);
        byte[] hr = Proto.heartRate(5);
        check("heart rate: since and version 1", Frame.type(hr) == Proto.HEART_RATE && Frame.first(hr, Proto.REC_VERSION).value[0] == 1
                && Frame.u32(Frame.first(hr, Proto.SINCE).value, 0) == 5);
        Proto.Hello hi = Proto.hello(WatchDemo.helloReply("Steel HR 99", 2345));
        check("hello answer: name and firmware", "Steel HR 99".equals(hi.name) && hi.firmware == 2345, hi.name + " " + hi.firmware);
        Proto.Hello none = Proto.hello(Frame.message(Proto.HELLO));
        check("hello answer without its part: nothing", none.name == null && none.firmware == -1);
        byte[] shortReply = Frame.message(Proto.HELLO, Frame.part(Proto.HELLO_REPLY, new byte[] {0, 0, 0, 1, 20, 'a'}));
        check("hello answer cut short: no name, no throw", Proto.hello(shortReply).name == null);
        int[] bat = Proto.battery(WatchDemo.batteryReply(77, 2, 3850));
        check("battery: percent, state, voltage", bat != null && bat[0] == 77 && bat[1] == 2 && bat[2] == 3850);
        check("battery: missing part gives null", Proto.battery(Frame.message(Proto.BATTERY)) == null
                && Proto.battery(Frame.message(Proto.BATTERY, Frame.part(Proto.BATTERY_PART, new byte[] {1}))) == null);
        check("workout names", Proto.workoutName(2).equals("Running") && Proto.workoutName(6).equals("Cycling") && Proto.workoutName(999).equals("Workout"));
        int[] codes = {1, 2, 3, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 26, 28, 29, 30, 34, 35, 36, 188, 191, 192, 193, 194, 195, 196, 227};
        Set<String> names = new HashSet<String>();
        boolean named = true;
        for (int k : codes) {
            String n = Proto.workoutName(k);
            named &= !n.equals("Workout") && !n.contains("-") && names.add(n);
        }
        check("workout names: every code of the public list has its own plain name", named && names.size() == codes.length, names.size());
    }

    // ---------------------------------------------------------------------------------- records

    static void records() {
        byte[] act = Frame.message(Proto.ACTIVITY,
                WatchDemo.time(1000), WatchDemo.duration(900), WatchDemo.steps(420, 300), WatchDemo.calories(17),
                WatchDemo.time(2000), WatchDemo.duration(1500), WatchDemo.sleep(Records.DEEP),
                WatchDemo.time(3000), WatchDemo.duration(2100), WatchDemo.workout(2), WatchDemo.steps(5000, 5500), WatchDemo.calories(380),
                WatchDemo.time(4000), WatchDemo.heart(55),
                WatchDemo.time(5000), Frame.part(Proto.REC_RUN, new byte[0]), WatchDemo.duration(60),
                WatchDemo.time(6000), Frame.part(Proto.REC_SWIM, new byte[0]),
                WatchDemo.time(7000), Frame.part(0x0777, new byte[] {1, 2}),
                WatchDemo.time(8000), WatchDemo.heart(250),
                WatchDemo.end());
        Records.Decoded d = new Records.Decoded();
        Records.decode(act, 9000, d);
        check("records: a steps record", d.steps.size() == 1 && d.steps.get(0).start == 1000 && d.steps.get(0).dur == 900 && d.steps.get(0).steps == 420
                && d.steps.get(0).metres == 300 && d.steps.get(0).kcal == 17, d.steps.size());
        check("records: a sleep piece", d.sleep.size() == 1 && d.sleep.get(0).start == 2000 && d.sleep.get(0).dur == 1500 && d.sleep.get(0).stage == Records.DEEP);
        check("records: a workout with its own steps, not in the steps list", d.workouts.size() == 3 && d.workouts.get(0).kind == 2 && d.workouts.get(0).steps == 5000
                && d.workouts.get(0).kcal == 380 && d.workouts.get(0).dur == 2100, d.workouts.size());
        check("records: the watch's running and swimming marks are workouts", d.workouts.get(1).kind == Records.RUN && d.workouts.get(2).kind == Records.SWIM);
        check("records: a heart rate in an otherwise empty record (how the watch sends sleep heart rate)", d.heart.size() == 1 && d.heart.get(0).t == 4000
                && d.heart.get(0).bpm == 55, d.heart.size());
        check("records: a heart rate out of range and a record of unknown parts are counted, not stored", d.unknown == 2, d.unknown);
        byte[] hr = Frame.message(Proto.HEART_RATE, WatchDemo.time(100), WatchDemo.heart(61), WatchDemo.time(200), WatchDemo.heart(29), WatchDemo.end());
        Records.decode(hr, 300, d);
        check("records: heart rate answers, 30 to 220 bpm only", d.heart.size() == 2 && d.heart.get(1).bpm == 61, d.heart.size());
        Records.decode(WatchDemo.batteryReply(64, 2, 3700), 12345, d);
        Records.decode(WatchDemo.batteryReply(101, 2, 3700), 12346, d);
        check("records: a battery reading at the time it came, over 100 % dropped", d.battery.size() == 1 && d.battery.get(0).t == 12345 && d.battery.get(0).pct == 64);
        Records.decode(WatchDemo.helloReply("Steel HR 5", 7), 1, d);
        check("records: the hello's name and firmware", "Steel HR 5".equals(d.name) && d.firmware == 7);
        Records.Decoded e = new Records.Decoded();
        Records.decode(Frame.message(Proto.ACTIVITY, WatchDemo.duration(5), WatchDemo.steps(3, 3)), 1, e);
        check("records: parts before any time part belong to no record", e.steps.isEmpty() && e.unknown == 0);
        Records.decode(Frame.message(Proto.ACTIVITY, Frame.part(Proto.REC_TIME, new byte[] {1}), WatchDemo.steps(3, 3)), 1, e);
        check("records: a time part cut short starts no record", e.steps.isEmpty());
        Records.decode(Frame.message(0x0999, WatchDemo.time(5), WatchDemo.steps(3, 3)), 1, e);
        check("records: other message types give no rows", e.steps.isEmpty());
        // the same messages give the same rows (decoding again after a better decoder starts from the raw messages)
        List<byte[]> msgs = Arrays.asList(act, hr);
        List<Long> caps = Arrays.asList(9000L, 300L);
        Records.Decoded x = Records.decode(msgs, caps), y = Records.decode(msgs, caps);
        check("records: decoding is the same every time", x.steps.size() == y.steps.size() && x.heart.size() == y.heart.size() && x.sleep.size() == y.sleep.size()
                && x.workouts.size() == y.workouts.size());
    }

    // ---------------------------------------------------------------------------------- session

    /** A made up watch: answers each request with scripted messages, records every write, can stay silent or drop the link. */
    static class FakeWatch implements Session.Link {
        final List<byte[]> sent = new ArrayList<byte[]>();
        final Deque<byte[]> inbox = new ArrayDeque<byte[]>();
        final Map<Integer, List<byte[]>> answers = new HashMap<Integer, List<byte[]>>();
        final Set<Integer> silent = new HashSet<Integer>();
        Integer dropAt;
        /** The watch takes a message of this type, then closes the link. */
        Integer closeAfter;
        boolean down;
        int pending; // messages a request queued that were not read yet when the next request came

        FakeWatch() {
            answer(Proto.HELLO, WatchDemo.helloReply("Steel HR test", 1971));
            answer(Proto.CONNECT, WatchDemo.empty(Proto.CONNECT));
            answer(Proto.BATTERY, WatchDemo.batteryReply(80, 2, 3900));
            answer(Proto.SET_TIME, WatchDemo.empty(Proto.SET_TIME));
            List<byte[][]> recs = new ArrayList<byte[][]>();
            for (int i = 0; i < 30; i++) recs.add(new byte[][] {WatchDemo.time(1000 + i * 900), WatchDemo.duration(900), WatchDemo.steps(100 + i, 70)});
            answers.put(Proto.ACTIVITY, WatchDemo.answer(Proto.ACTIVITY, recs, 7));
            List<byte[][]> hrs = new ArrayList<byte[][]>();
            for (int i = 0; i < 10; i++) hrs.add(new byte[][] {WatchDemo.time(1000 + i * 1800), WatchDemo.heart(60 + i)});
            answers.put(Proto.HEART_RATE, WatchDemo.answer(Proto.HEART_RATE, hrs, 4));
            List<byte[][]> mov = new ArrayList<byte[][]>();
            for (int i = 0; i < 5; i++) mov.add(new byte[][] {WatchDemo.time(1000 + i * 900), WatchDemo.duration(900), WatchDemo.steps(100 + i, 70, 3, 2)});
            answers.put(Proto.MOVEMENT, WatchDemo.answer(Proto.MOVEMENT, mov, 3));
            answer(Proto.SYNC_DONE, WatchDemo.empty(Proto.SYNC_DONE));
            // the setup's messages, the alarm and the hands: an empty answer of the same type
            for (int t : new int[] {Proto.SET_UNIT, Proto.SETUP_FINISHED, Proto.SET_LOCALE, Proto.SET_USER, Proto.SET_GOAL, Proto.SET_SCREENS, Proto.HANDS_START,
                Proto.HANDS_STOP, Proto.MOVE_HAND, Proto.SET_ALARM, Proto.ALARM_ON}) answer(t, WatchDemo.empty(t));
        }

        void answer(int type, byte[]... msgs) {
            answers.put(type, new ArrayList<byte[]>(Arrays.asList(msgs)));
        }

        @Override
        public void send(byte[] msg) throws IOException {
            if (down) throw new IOException("link gone");
            pending += inbox.size();
            sent.add(msg);
            int t = Frame.type(msg);
            if (dropAt != null && dropAt == t) {
                down = true;
                throw new IOException("link gone");
            }
            if (closeAfter != null && closeAfter == t) {
                down = true;
                return;
            }
            if (silent.contains(t)) return;
            List<byte[]> a = answers.get(t);
            if (a != null) inbox.addAll(a);
        }

        @Override
        public byte[] next(long ms) throws IOException {
            if (down) throw new IOException("link gone");
            return inbox.poll();
        }

        List<Integer> types() {
            List<Integer> out = new ArrayList<Integer>();
            for (byte[] m : sent) out.add(Frame.type(m));
            return out;
        }
    }

    static final class Gate implements Session.Gate {
        boolean ring, stop;
        int askedUntilRing = -1, asked;

        @Override
        public boolean ringWanted() {
            asked++;
            return ring || askedUntilRing >= 0 && asked > askedUntilRing;
        }

        @Override
        public boolean stopped() {
            return stop;
        }
    }

    static final class Store implements Session.Sink {
        final List<byte[]> got = new ArrayList<byte[]>();
        int failAt = -1;

        @Override
        public void received(byte[] msg) throws IOException {
            if (failAt >= 0 && got.size() == failAt) throw new IOException("disk full");
            got.add(msg);
        }
    }

    static final class Lines implements Session.Log {
        final List<String> lines = new ArrayList<String>();

        @Override
        public void log(String line) {
            lines.add(line);
        }
    }

    static Session.Plan plan() {
        Session.Plan p = new Session.Plan();
        p.androidSdk = 33;
        p.now = 1700000000L;
        p.offset = 3600;
        p.activitySince = 0;
        p.heartSince = 0;
        // short waits: the made up watch answers at once or never
        p.answerMs = 300;
        p.silenceMs = 600;
        return p;
    }

    static void session() throws Exception {
        FakeWatch w = new FakeWatch();
        Store s = new Store();
        Lines log = new Lines();
        Session.Result r = Session.run(w, plan(), new Gate(), s, log);
        List<Integer> order = Arrays.asList(Proto.HELLO, Proto.CONNECT, Proto.BATTERY, Proto.SET_TIME, Proto.ACTIVITY, Proto.MOVEMENT, Proto.HEART_RATE, Proto.SYNC_DONE);
        check("session: the requests in order, sync done last", w.types().equals(order), w.types());
        check("session: complete, both data requests reached their end", r.ended == null && r.activityDone && r.heartDone && r.doneSent, r.ended);
        check("session: one request at a time (each answer read before the next request)", w.pending == 0, w.pending);
        int expected = 5 + w.answers.get(Proto.ACTIVITY).size() + w.answers.get(Proto.MOVEMENT).size() + w.answers.get(Proto.HEART_RATE).size();
        check("session: every message from the watch stored as it came", s.got.size() == expected && r.received == expected, s.got.size() + " of " + expected);
        Records.Decoded d = new Records.Decoded();
        for (byte[] m : s.got) Records.decode(m, 1, d);
        Set<Long> starts = new HashSet<Long>();
        for (Records.Steps x : d.steps) starts.add(x.start);
        // the movement answer repeats 5 of the activity records' times: stored by device and start, they count once
        check("session: what was stored decodes to every record", d.steps.size() == 35 && starts.size() == 30 && d.heart.size() == 10 && d.battery.size() == 1
                && d.moves.size() == 5 && d.moves.get(0).up == 3 && d.moves.get(0).down == 2, d.steps.size());
        check("session: the log names types and lengths only, never a value", noValues(log.lines), log.lines);
        check("session: the log says the end was reached", joined(log.lines).contains("activity: 5 messages") && joined(log.lines).contains("end reached"), log.lines);

        // a sign in of an unknown layout: the session ends, says so, and still sends sync done (more in signInSession)
        w = new FakeWatch();
        w.answer(Proto.HELLO, Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_PART, new byte[] {3, 'a', 'b'})));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: a challenge of an unknown layout ends it with its sentence, sync done still sent", Session.CHALLENGE.equals(r.ended)
                && last(w) == Proto.SYNC_DONE && w.types().size() == 2, w.types());
        // a challenge part inside the hello answer counts too
        w = new FakeWatch();
        w.answer(Proto.HELLO, Frame.message(Proto.HELLO, Frame.part(Proto.CHALLENGE_PART, new byte[] {0})));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: a challenge part in an answer is a sign in too", Session.CHALLENGE.equals(r.ended));

        // silent watch
        w = new FakeWatch();
        w.silent.add(Proto.HELLO);
        Lines sl = new Lines();
        long t0 = System.currentTimeMillis();
        r = Session.run(w, plan(), new Gate(), new Store(), sl);
        check("session: no answer to the hello: its sentence, sync done sent", Session.NO_ANSWER.equals(r.ended) && last(w) == Proto.SYNC_DONE, r.ended);
        check("session: the log says which request had no answer", joined(sl.lines).contains("no answer to hello"), sl.lines);
        check("session: the fake answers at once (the test does not wait for timeouts)", System.currentTimeMillis() - t0 < 20000);

        // a data answer that never ends
        w = new FakeWatch();
        List<byte[]> act = w.answers.get(Proto.ACTIVITY);
        act.remove(act.size() - 1);
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: activity without its end: not done, no heart rate asked, sync done sent", Session.NO_ANSWER.equals(r.ended) && !r.activityDone
                && !w.types().contains(Proto.HEART_RATE) && last(w) == Proto.SYNC_DONE, w.types());

        // the ring wants Bluetooth half way through the activity answer
        w = new FakeWatch();
        Gate g = new Gate();
        g.askedUntilRing = 7;
        Store st = new Store();
        r = Session.run(w, plan(), g, st, new Lines());
        check("session: the ring wants Bluetooth: the session ends at once, sync done sent", Session.FOR_RING.equals(r.ended) && last(w) == Proto.SYNC_DONE
                && !r.activityDone, w.types());
        check("session: what came before the ring is stored", st.got.size() >= 5, st.got.size());
        g = new Gate();
        g.ring = true;
        w = new FakeWatch();
        r = Session.run(w, plan(), g, new Store(), new Lines());
        check("session: a ring task waiting at the start: nothing asked but sync done", Session.FOR_RING.equals(r.ended) && w.types().equals(Arrays.asList(Proto.SYNC_DONE)));
        g = new Gate();
        g.stop = true;
        w = new FakeWatch();
        r = Session.run(w, plan(), g, new Store(), new Lines());
        check("session: stopped: its sentence", Session.STOPPED.equals(r.ended));

        // the link drops: no sync done can be sent, the result says so
        w = new FakeWatch();
        w.dropAt = Proto.HEART_RATE;
        st = new Store();
        r = Session.run(w, plan(), new Gate(), st, new Lines());
        check("session: a lost link: its sentence, activity kept", Session.LINK_LOST.equals(r.ended) && r.activityDone && !r.doneSent && st.got.size() > 5, r.ended);

        // storing fails: the session ends, sync done still sent
        w = new FakeWatch();
        st = new Store();
        st.failAt = 3;
        r = Session.run(w, plan(), new Gate(), st, new Lines());
        check("session: a message that cannot be stored ends the session, sync done sent", Session.NOT_STORED.equals(r.ended) && last(w) == Proto.SYNC_DONE, r.ended);

        // the ring asks while the session waits for a silent watch: it ends within a slice, not after the whole wait
        final long[] since = {0};
        Session.Gate later = new Session.Gate() {
            @Override
            public boolean ringWanted() {
                return since[0] > 0 && System.currentTimeMillis() - since[0] > 100;
            }

            @Override
            public boolean stopped() {
                return false;
            }
        };
        Session.Plan slow = plan();
        slow.silenceMs = 20000;
        Session.Link waiting = new Session.Link() {
            final FakeWatch inner = new FakeWatch();
            {
                List<byte[]> a = inner.answers.get(Proto.ACTIVITY);
                a.remove(a.size() - 1);
            }

            @Override
            public void send(byte[] msg) throws IOException {
                inner.send(msg);
            }

            @Override
            public byte[] next(long ms) throws IOException, InterruptedException {
                byte[] m = inner.next(ms);
                if (m == null) {
                    if (since[0] == 0) since[0] = System.currentTimeMillis();
                    Thread.sleep(Math.min(ms, 50));
                }
                return m;
            }
        };
        long w0 = System.currentTimeMillis();
        r = Session.run(waiting, slow, later, new Store(), new Lines());
        check("session: the ring asks during a long wait: the session ends within a slice", Session.FOR_RING.equals(r.ended)
                && System.currentTimeMillis() - w0 < 3000, (System.currentTimeMillis() - w0) + " ms");

        // the link drops just when sync done is sent, or right after it: the session's result stays as it was
        w = new FakeWatch();
        w.dropAt = Proto.SYNC_DONE;
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: the link drops at sync done: still complete, sync done not sent", r.ended == null && !r.doneSent && r.heartDone, r.ended);
        w = new FakeWatch();
        w.silent.add(Proto.SYNC_DONE);
        Lines dl = new Lines();
        r = Session.run(w, plan(), new Gate(), new Store(), dl);
        check("session: no answer to sync done: complete all the same, the log says so", r.ended == null && r.doneSent && joined(dl.lines).contains("no answer to sync done"));
        w = new FakeWatch();
        st = new Store();
        st.failAt = 5 + w.answers.get(Proto.ACTIVITY).size() + w.answers.get(Proto.MOVEMENT).size() + w.answers.get(Proto.HEART_RATE).size() - 1;
        r = Session.run(w, plan(), new Gate(), st, new Lines());
        check("session: the answer to sync done cannot be stored: its sentence", Session.NOT_STORED.equals(r.ended) && r.doneSent, r.ended);
        // a challenge of an unknown layout in the middle of a data answer
        w = new FakeWatch();
        w.answers.get(Proto.HEART_RATE).add(1, Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_PART, new byte[] {0})));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: an unknown challenge during a data answer ends it", Session.CHALLENGE.equals(r.ended) && r.activityDone && !r.heartDone && last(w) == Proto.SYNC_DONE);

        // the watch's own messages on the way are stored and do not count as the answer
        w = new FakeWatch();
        byte[] own = Hex.decode("0141410000");
        w.answer(Proto.CONNECT, own, WatchDemo.empty(Proto.CONNECT));
        List<byte[]> a2 = w.answers.get(Proto.ACTIVITY);
        a2.add(0, own);
        st = new Store();
        r = Session.run(w, plan(), new Gate(), st, new Lines());
        check("session: the watch's own messages are stored and skipped", r.ended == null && r.activityDone && st.got.contains(own), r.ended);

        // a session over its time limit ends between messages
        w = new FakeWatch();
        Session.Plan p = plan();
        p.limitMs = -1;
        r = Session.run(w, p, new Gate(), new Store(), new Lines());
        check("session: over its time: its sentence, sync done sent", Session.TOO_LONG.equals(r.ended) && last(w) == Proto.SYNC_DONE);

        // the since times go into the requests
        w = new FakeWatch();
        p = plan();
        p.activitySince = 1234;
        p.heartSince = 5678;
        Session.run(w, p, new Gate(), new Store(), new Lines());
        long as = -1, hs = -1;
        for (byte[] m : w.sent) {
            if (Frame.type(m) == Proto.ACTIVITY) as = Frame.u32(Frame.first(m, Proto.SINCE).value, 0);
            if (Frame.type(m) == Proto.HEART_RATE) hs = Frame.u32(Frame.first(m, Proto.SINCE).value, 0);
        }
        check("session: activity and heart rate ask from their own times", as == 1234 && hs == 5678, as + " " + hs);
    }


    // ---------------------------------------------------------------------------- watch setup

    /** Every new message byte for byte, from the layouts in docs/ARCHITECTURE.md (made up values). */
    static void setupMessages() {
        check("setup: distance in km", Hex.encode(Proto.unit(Proto.UNIT_DISTANCE, Proto.KM)).equals("010112000a01190006000000020018"),
                Hex.encode(Proto.unit(Proto.UNIT_DISTANCE, Proto.KM)));
        check("setup: a 12 hour clock", Hex.encode(Proto.unit(Proto.UNIT_CLOCK, Proto.H12)).equals("010112000a01190006000000040" + "01b"), Hex.encode(Proto.unit(Proto.UNIT_CLOCK, Proto.H12)));
        check("setup: the language with its length byte", Hex.encode(Proto.locale("de")).equals("01011a000701210003026465"), Hex.encode(Proto.locale("de")));
        byte[] u = Proto.user(64, 168, 1, 576806400L);
        check("setup: the user: number, kg, cm, sex, birth date, no name", Hex.encode(u).equals("010502" + "0016" + "05030012" + "0001e240" + "00000040" + "000000a8" + "01"
                + "22615e00" + "00"), Hex.encode(u));
        check("setup: male is 0, a sex not given is never sent as female", Frame.first(Proto.user(70, 180, 0, 0), Proto.USER).value[12] == 0
                && Frame.first(Proto.user(70, 180, 2, 0), Proto.USER).value[12] == 0);
        check("setup: the step goal in 8 bytes", Hex.encode(Proto.goal(10000)).equals("01050a000c051100080000000000002710"), Hex.encode(Proto.goal(10000)));
        byte[] sc = Proto.screens();
        List<Frame.Part> sp = Frame.parts(sc);
        boolean layout = sp.size() == 8 && sp.get(7).type == Proto.END && sp.get(7).value.length == 0;
        for (int i = 0; i < 7 && layout; i++) {
            byte[] v = sp.get(i).value;
            layout = sp.get(i).type == Proto.SCREEN && v.length == 18 && Frame.u32(v, 4) == 123456 && Frame.u32(v, 8) == 0 && Frame.u32(v, 12) == 0 && v[17] == 1;
        }
        check("setup: the screens: 7 parts of id, user, zeros, place and 1, then the end part", Frame.type(sc) == Proto.SET_SCREENS && layout
                && Frame.u32(sp.get(0).value, 0) == 0xff && sp.get(0).value[16] == 6 && Frame.u32(sp.get(1).value, 0) == 0x3d && sp.get(1).value[16] == 1, sp.size());
        check("setup: setup finished, hands start and stop are empty", Hex.encode(Proto.setupFinished()).equals("0101130000") && Hex.encode(Proto.handsStart()).equals("01011e0000")
                && Hex.encode(Proto.handsStop()).equals("01011f0000"));
        check("hands: hour hand 10 steps back", Hex.encode(Proto.moveHand(Proto.HAND_HOURS, -10)).equals("01011c0007050c000301fff6"), Hex.encode(Proto.moveHand(Proto.HAND_HOURS, -10)));
        check("hands: a move beyond 2 bytes is held at the limit", Frame.u16(Frame.first(Proto.moveHand(0, 99999), Proto.HAND).value, 1) == 32767);
        byte[] al = Proto.alarm(6, 45, 0x1f, 15);
        check("alarm: hour, minute, days Mon to Fri, zeros, smart window", Hex.encode(al).equals("010145000b05120007062dbe0000000f"), Hex.encode(al));
        check("alarm: the watch's day bits", Proto.watchDays(0) == 0x80 && Proto.watchDays(1 << 6) == 0x81 && Proto.watchDays(0x7f) == 0xff && Proto.watchDays(1) == 0x82
                && Proto.watchDays(1 << 5) == 0xc0);
        check("alarm: on and off", Hex.encode(Proto.alarmOn(true)).equals("01091b0005091900" + "0101") && Frame.first(Proto.alarmOn(false), Proto.ALARM_STATE).value[0] == 0,
                Hex.encode(Proto.alarmOn(true)));
        byte[] mv = Proto.movement(1700000000L);
        check("movement: since, count 0 and version 1", Frame.type(mv) == Proto.MOVEMENT && Frame.u32(Frame.first(mv, Proto.SINCE).value, 0) == 1700000000L
                && Frame.first(mv, Proto.REC_VERSION).value[0] == 1);
        check("activity: no version part (as Gadgetbridge asks it)", !Frame.has(Proto.activity(5), Proto.REC_VERSION));
        byte[] reply = Proto.syncAskReply();
        check("the answer to the watch's own sync ask: type 0x4141, empty", Hex.encode(reply).equals("0141410000"), Hex.encode(reply));

        // the profile
        WatchSetup.Profile p = profile();
        check("profile: born on 1 July of the year the age points to", p.birth() == java.time.LocalDate.of(1988, 7, 1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
        p.language = "pt";
        check("profile: a language the watch does not know is English", p.watchLanguage().equals("en"));
        p.language = "FR";
        check("profile: a known language", p.watchLanguage().equals("fr"));
        p.sex = 2;
        check("profile: no sex given is not complete", !p.complete());

        // the texts the setup screen shows
        WatchSetup.Alarm a = new WatchSetup.Alarm();
        a.on = true;
        a.hour = 6;
        a.minute = 5;
        a.smart = 15;
        List<String> lines = WatchSetup.shown(profile(), "Europe/Zurich", a);
        String all = String.join("\n", lines);
        check("setup screen: what is sent, every value named", all.contains("female, 168 cm, 64 kg, born about 1988") && all.contains("10,000 steps")
                && all.contains("Distance in km, 24 hour clock") && all.contains("Alarm: 06:05, Mon to Fri, smart wake 15 min") && all.contains("Europe/Zurich"), all);
        p = profile();
        p.sex = 2;
        check("setup screen: without a sex the user is said not to be sent", String.join("\n", WatchSetup.shown(p, "UTC", null)).contains("not sent until"));
        WatchSetup.Profile mp = profile();
        mp.sex = 0;
        mp.metric = false;
        mp.h24 = false;
        mp.language = null;
        WatchSetup.Alarm noSmart = new WatchSetup.Alarm();
        noSmart.on = true;
        noSmart.days = 0x7f;
        String ms = String.join("\n", WatchSetup.shown(mp, "UTC", noSmart));
        WatchSetup.Alarm offA = new WatchSetup.Alarm();
        check("setup screen: male, miles, 12 hour clock, English, an alarm without smart wake, an alarm off", mp.complete() && ms.contains("You: male")
                && ms.contains("Distance in miles, 12 hour clock") && ms.contains("Language: English") && ms.contains("Alarm: 07:00, every day\n")
                && String.join("\n", WatchSetup.shown(mp, "UTC", offA)).contains("Alarm: off"), ms);
        check("setup: the person's steps for miles and 12 hours", Frame.u16(Frame.first(WatchSetup.person(mp).get(2).msg, Proto.UNIT).value, 4) == Proto.MILES
                && Frame.u16(Frame.first(WatchSetup.person(mp).get(3).msg, Proto.UNIT).value, 4) == Proto.H12);
        boolean dashes = false;
        for (String t : lines) dashes |= t.contains(" - ") || t.contains("–") || t.contains("—");
        for (String t : WatchSetup.HAND_TEXT) dashes |= t.contains(" - ") || t.contains("–") || t.contains("—");
        check("setup screen: plain texts without dashes", !dashes);
        check("alarm days in words", WatchSetup.days(0x7f).equals("every day") && WatchSetup.days(0x1f).equals("Mon to Fri") && WatchSetup.days(0x60).equals("Sat, Sun")
                && WatchSetup.days(0).equals("no day") && WatchSetup.days(0x05).equals("Mon, Wed"));

        // the decoder: intensity, climb, movement answers
        byte[] m = Frame.message(Proto.MOVEMENT, WatchDemo.time(100), WatchDemo.duration(900), WatchDemo.steps(500, 380, 12, 9), WatchDemo.calories(20, 33),
                WatchDemo.time(1000), WatchDemo.duration(900), WatchDemo.steps(50, 30), WatchDemo.end());
        Records.Decoded d = new Records.Decoded();
        Records.decode(m, 2000, d);
        check("records: movement answers decode, with intensity and climb", d.steps.size() == 2 && d.steps.get(0).kcal == 20 && d.moves.size() == 1
                && d.moves.get(0).start == 100 && d.moves.get(0).met == 33 && d.moves.get(0).up == 12 && d.moves.get(0).down == 9, d.moves.size());
        check("records: decoder version 2", Records.VERSION == 2);
        WatchDays.Zones utc = WatchDays.zones(new ArrayList<Object[]>(), ZoneId.of("UTC"));
        List<Records.Steps> rows = Arrays.asList(new Records.Steps(100, 900, 500, 380, 20), new Records.Steps(1000, 900, 50, 30, 3), new Records.Steps(90000, 900, 5, 3, 7));
        List<Records.Moves> mv2 = Arrays.asList(new Records.Moves(100, 33, 12, 9), new Records.Moves(1000, 10, 0, 4), new Records.Moves(90000, 5, 6, 0));
        LocalDate d0 = LocalDate.of(1970, 1, 1), d1 = LocalDate.of(1970, 1, 2);
        check("days: calories per day", WatchDays.kcalPerDay(rows, utc).get(d0) == 23 && WatchDays.kcalPerDay(rows, utc).get(d1) == 7);
        check("days: metres climbed per day, descents left out", WatchDays.climbPerDay(mv2, utc).get(d0) == 12 && WatchDays.climbPerDay(mv2, utc).get(d1) == 6
                && WatchDays.climbPerDay(new ArrayList<Records.Moves>(), utc).isEmpty());
    }

    static WatchSetup.Profile profile() {
        WatchSetup.Profile p = new WatchSetup.Profile();
        p.age = 38;
        p.sex = 1;
        p.weightKg = 64;
        p.heightCm = 168;
        p.year = 2026;
        return p;
    }

    static final class Heard implements Session.Progress {
        final List<String> events = new ArrayList<String>();

        @Override
        public void step(String key, int state) {
            events.add(key + " " + state);
        }
    }

    /** The setup, each sync, the alarm and the hands against the made up watch, in order. */
    static void setupSession() throws Exception {
        WatchSetup.Alarm a = new WatchSetup.Alarm();
        a.on = true;
        Session.Plan plan = plan();
        plan.steps = WatchSetup.setup(plan, profile(), a, 77);
        FakeWatch w = new FakeWatch();
        Heard heard = new Heard();
        plan.progress = heard;
        Lines log = new Lines();
        Session.Result r = Session.run(w, plan, new Gate(), new Store(), log);
        List<Integer> order = Arrays.asList(Proto.HELLO, Proto.CONNECT, Proto.SET_LOCALE, Proto.SET_TIME, Proto.SET_USER, Proto.SET_GOAL, Proto.SET_UNIT, Proto.SET_UNIT,
                Proto.SET_SCREENS, Proto.SET_ALARM, Proto.ALARM_ON, Proto.BATTERY, Proto.SETUP_FINISHED, Proto.ACTIVITY, Proto.MOVEMENT, Proto.HEART_RATE, Proto.SYNC_DONE);
        check("setup session: every step in order, the first sync after setup finished, sync done last", r.ended == null && w.types().equals(order), w.types());
        check("setup session: each step started and done, setup finished among them", heard.events.contains(WatchSetup.FINISHED + " " + Session.DONE)
                && heard.events.contains(Session.HEART + " " + Session.DONE) && heard.events.indexOf(Session.HELLO + " " + Session.STARTED) == 0
                && r.done.contains(WatchSetup.ALARM_ON) && r.movementDone, heard.events);
        long ms = -1;
        for (byte[] m : w.sent) if (Frame.type(m) == Proto.MOVEMENT) ms = Frame.u32(Frame.first(m, Proto.SINCE).value, 0);
        check("setup session: the movement request asks from its own time", ms == 77, ms);
        check("setup session: the log names types, lengths and part counts, never a value", noValues(log.lines) && joined(log.lines).contains("activity part types: 0x0100 1, 0x0601 30"),
                log.lines);

        // no sex given: everything but the user
        Session.Plan np = plan();
        WatchSetup.Profile ns = profile();
        ns.sex = 2;
        np.steps = WatchSetup.setup(np, ns, null, 0);
        w = new FakeWatch();
        r = Session.run(w, np, new Gate(), new Store(), new Lines());
        check("setup session: without a sex the user is not sent, the rest is", r.ended == null && !w.types().contains(Proto.SET_USER) && w.types().contains(Proto.SETUP_FINISHED)
                && !w.types().contains(Proto.SET_ALARM), w.types());

        // each sync: the person every time, the alarm only when it is pending
        Session.Plan sp = plan();
        sp.steps = WatchSetup.sync(sp, profile(), null, 5);
        w = new FakeWatch();
        r = Session.run(w, sp, new Gate(), new Store(), new Lines());
        check("sync: the person and units every time, no alarm when none is pending, no setup finished", r.ended == null && w.types().equals(Arrays.asList(Proto.HELLO,
                Proto.CONNECT, Proto.BATTERY, Proto.SET_TIME, Proto.SET_USER, Proto.SET_GOAL, Proto.SET_UNIT, Proto.SET_UNIT, Proto.ACTIVITY, Proto.MOVEMENT, Proto.HEART_RATE,
                Proto.SYNC_DONE)), w.types());
        WatchSetup.Alarm off = new WatchSetup.Alarm();
        sp.steps = WatchSetup.sync(sp, profile(), off, 5);
        w = new FakeWatch();
        Session.run(w, sp, new Gate(), new Store(), new Lines());
        check("sync: a pending alarm that was switched off sends only off", w.types().contains(Proto.ALARM_ON) && !w.types().contains(Proto.SET_ALARM));

        // a watch that does not know a setup message: the session ends at it and says which
        w = new FakeWatch();
        w.silent.add(Proto.SET_SCREENS);
        Session.Plan bp = plan();
        bp.steps = WatchSetup.setup(bp, profile(), null, 0);
        heard = new Heard();
        bp.progress = heard;
        r = Session.run(w, bp, new Gate(), new Store(), new Lines());
        check("setup session: no answer to a setup step: ends there, the step failed, sync done sent", Session.NO_ANSWER.equals(r.ended)
                && WatchSetup.SCREENS.equals(r.failedStep) && heard.events.contains(WatchSetup.SCREENS + " " + Session.FAILED) && last(w) == Proto.SYNC_DONE
                && !w.types().contains(Proto.SETUP_FINISHED), r.failedStep);

        // the alarm alone
        Session.Plan ap = plan();
        ap.steps = WatchSetup.alarmOnly(a);
        w = new FakeWatch();
        r = Session.run(w, ap, new Gate(), new Store(), new Lines());
        check("alarm: hello, connect, the alarm, on, sync done", r.ended == null && w.types().equals(Arrays.asList(Proto.HELLO, Proto.CONNECT, Proto.SET_ALARM, Proto.ALARM_ON,
                Proto.SYNC_DONE)), w.types());

        // the hands: moves while the screen is open, then calibration off and the time
        final java.util.concurrent.LinkedBlockingQueue<Session.Step> q = new java.util.concurrent.LinkedBlockingQueue<Session.Step>();
        q.add(WatchSetup.move(Proto.HAND_HOURS, 1));
        q.add(WatchSetup.move(Proto.HAND_MINUTES, -10));
        q.add(Session.FINISH);
        Session.Plan hp = plan();
        hp.steps = WatchSetup.handsStart();
        hp.last = WatchSetup.handsEnd(hp);
        hp.more = new Session.More() {
            @Override
            public Session.Step next(long ms) throws InterruptedException {
                return q.poll(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        };
        w = new FakeWatch();
        r = Session.run(w, hp, new Gate(), new Store(), new Lines());
        check("hands: start, the moves, stop, the phone's time, sync done", r.ended == null && w.types().equals(Arrays.asList(Proto.HELLO, Proto.CONNECT, Proto.HANDS_START,
                Proto.MOVE_HAND, Proto.MOVE_HAND, Proto.HANDS_STOP, Proto.SET_TIME, Proto.SYNC_DONE)), w.types());
        // the ring wants Bluetooth while calibrating: calibration mode is still switched off before sync done
        final java.util.concurrent.LinkedBlockingQueue<Session.Step> q2 = new java.util.concurrent.LinkedBlockingQueue<Session.Step>();
        Session.Plan hp2 = plan();
        hp2.steps = WatchSetup.handsStart();
        hp2.last = WatchSetup.handsEnd(hp2);
        hp2.more = new Session.More() {
            @Override
            public Session.Step next(long ms) throws InterruptedException {
                return q2.poll(Math.min(ms, 20), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        };
        Gate g = new Gate();
        g.askedUntilRing = 6;
        w = new FakeWatch();
        w.silent.add(Proto.MOVE_HAND);
        r = Session.run(w, hp2, g, new Store(), new Lines());
        List<Integer> t = w.types();
        check("hands: an early end still switches calibration off and sends the time, then sync done", Session.FOR_RING.equals(r.ended)
                && t.indexOf(Proto.HANDS_STOP) > 0 && t.indexOf(Proto.HANDS_STOP) < t.indexOf(Proto.SYNC_DONE) && last(w) == Proto.SYNC_DONE, t);
        // a silent hand move does not end the session
        q.clear();
        q.add(WatchSetup.move(Proto.HAND_ACTIVITY, 1));
        q.add(Session.FINISH);
        w = new FakeWatch();
        w.silent.add(Proto.MOVE_HAND);
        Session.Plan hp3 = plan();
        hp3.steps = WatchSetup.handsStart();
        hp3.last = WatchSetup.handsEnd(hp3);
        hp3.more = hp.more;
        r = Session.run(w, hp3, new Gate(), new Store(), new Lines());
        check("hands: a move without an answer goes on", r.ended == null && w.types().contains(Proto.HANDS_STOP), r.ended);

        // while the calibration waits for a tap, the watch's own messages are read, stored and answered
        final int[] polls = {0};
        Session.Plan hp4 = plan();
        hp4.steps = WatchSetup.handsStart();
        hp4.last = WatchSetup.handsEnd(hp4);
        hp4.more = new Session.More() {
            @Override
            public Session.Step next(long ms) {
                return ++polls[0] < 4 ? null : Session.FINISH;
            }
        };
        w = new FakeWatch();
        byte[] own2 = Hex.decode("0141410000");
        w.answer(Proto.HANDS_START, WatchDemo.empty(Proto.HANDS_START), own2);
        Store st4 = new Store();
        r = Session.run(w, hp4, new Gate(), st4, new Lines());
        check("hands: the watch's own message while waiting for a tap is stored and answered", r.ended == null && st4.got.contains(own2)
                && w.types().contains(Proto.SYNC_ASK), w.types());
        // an early end, and the link drops at the calibration's end: no sync done, the result says why it ended
        Gate g5 = new Gate();
        g5.ring = true;
        Session.Plan hp5 = plan();
        hp5.steps = WatchSetup.handsStart();
        hp5.last = WatchSetup.handsEnd(hp5);
        w = new FakeWatch();
        w.dropAt = Proto.HANDS_STOP;
        Lines l5 = new Lines();
        r = Session.run(w, hp5, g5, new Store(), l5);
        check("hands: the link drops at the calibration's end after an early end: no sync done", Session.FOR_RING.equals(r.ended) && !r.doneSent
                && joined(l5.lines).contains("link lost at hands stop"), l5.lines);
        // an early end, and the answer to the calibration's end cannot be stored: the rest is left, sync done still sent
        Session.Plan hp6 = plan();
        hp6.steps = WatchSetup.handsStart();
        hp6.last = WatchSetup.handsEnd(hp6);
        w = new FakeWatch();
        Store st6 = new Store();
        st6.failAt = 0;
        Lines l6 = new Lines();
        r = Session.run(w, hp6, g5, st6, l6);
        check("hands: the end's answer cannot be stored: noted, sync done sent", Session.FOR_RING.equals(r.ended) && r.doneSent && joined(l6.lines).contains("hands stop not sent"),
                l6.lines);
        check("setup: the language names", WatchSetup.languageName("de").equals("German") && WatchSetup.languageName("es").equals("Spanish")
                && WatchSetup.languageName("fr").equals("French") && WatchSetup.languageName("it").equals("Italian") && WatchSetup.languageName("en").equals("English"));

        // the watch asks for a sync itself: answered once, as Gadgetbridge does
        w = new FakeWatch();
        byte[] ask = Hex.decode("0141410000");
        w.answer(Proto.CONNECT, ask, ask, WatchDemo.empty(Proto.CONNECT));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        int replies = 0;
        for (byte[] m : w.sent) if (Hex.encode(m).equals("0141410000")) replies++;
        check("the watch's own sync ask: answered once with the empty answer, the sync goes on", r.ended == null && replies == 1, replies);

        // the first sync asks a month back, later ones from the newest record
        check("first sync asks 30 days back", WatchSetup.FIRST_SINCE_S == 30L * 86400);
    }

    // ---------------------------------------------------------------------------------- sign in

    /** A made up watch address and challenge; nothing here comes from a real watch. */
    static final String MAC = "00:24:E4:12:34:56";
    static final byte[] THEIRS = Hex.decode("0102030405060708090a0b0c0d0e0f10");
    static final byte[] OURS = Hex.decode("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
    /** SHA-1 of THEIRS, then MAC, then the secret, worked out outside this repository (Python hashlib). */
    static final String PROOF_THEIRS = "6c86e76407725e16b23bc9e296dac9b7ac136a4e";
    /** SHA-1 of OURS, then MAC, then the secret, worked out the same way. */
    static final String PROOF_OURS = "08ab04c4ad23a9da37a840bb8b6232aa707e5f66";

    /** The watch's challenge message: its address text and 16 bytes, each with a length byte, in one part. */
    static byte[] challenge(String mac, byte[] bytes) {
        byte[] a = mac.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] v = new byte[2 + a.length + bytes.length];
        v[0] = (byte) a.length;
        System.arraycopy(a, 0, v, 1, a.length);
        v[1 + a.length] = (byte) bytes.length;
        System.arraycopy(bytes, 0, v, 2 + a.length, bytes.length);
        return Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_PART, v));
    }

    /** {@code msg} with one more part at its end. */
    static byte[] withPart(byte[] msg, int type, byte[] value) {
        List<byte[]> parts = new ArrayList<byte[]>();
        for (Frame.Part p : Frame.parts(msg)) parts.add(Frame.part(p.type, p.value));
        parts.add(Frame.part(type, value));
        return Frame.message(Frame.type(msg), parts.toArray(new byte[0][]));
    }

    /** A proof part's value: a length byte, then the 20 bytes. */
    static byte[] proofPart(String hex) {
        byte[] p = Hex.decode(hex);
        byte[] v = new byte[1 + p.length];
        v[0] = (byte) p.length;
        System.arraycopy(p, 0, v, 1, p.length);
        return v;
    }

    static void signIn() {
        byte[] c = challenge(MAC, THEIRS);
        check("sign in: a made up challenge has the 44 bytes and 1 part the real watch sent", c.length == 44 && Frame.parts(c).size() == 1, c.length);
        SignIn.Challenge read = SignIn.read(c, null);
        check("sign in: the address and the 16 bytes are read", read != null && MAC.equals(read.address) && Arrays.equals(THEIRS, read.bytes));
        check("sign in: the proof is SHA-1 of challenge, address and secret", PROOF_THEIRS.equals(Hex.encode(SignIn.proof(THEIRS, MAC)).toLowerCase()),
                Hex.encode(SignIn.proof(THEIRS, MAC)));

        byte[] a = SignIn.answer(c, null, OURS);
        // put together by hand: start, type 0128, length 0x40; part 0123 (21 bytes: 20, proof); part 0122 (35 bytes: 17, address, 16, ours)
        String want = "01" + "0128" + "0040" + "0123" + "0015" + "14" + PROOF_THEIRS + "0122" + "0023" + "11"
                + Hex.encode(MAC.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) + "10" + Hex.encode(OURS);
        check("sign in: the answer byte for byte", a != null && want.equalsIgnoreCase(Hex.encode(a)), a == null ? "null" : Hex.encode(a));
        check("sign in: the answer is 69 bytes, type 0x0128, proof part first", a != null && a.length == 69 && Frame.type(a) == Proto.CHALLENGE
                && Frame.parts(a).size() == 2 && Frame.parts(a).get(0).type == Proto.CHALLENGE_ANSWER && Frame.parts(a).get(1).type == Proto.CHALLENGE_PART);
        check("sign in: the answer's pieces join again (MTU 115)", a != null && joinsAgain(a, 112));
        check("sign in: our own challenge goes back with the watch's address", a != null && SignIn.read(a, null) != null
                && Arrays.equals(OURS, SignIn.read(a, null).bytes) && MAC.equals(SignIn.read(a, null).address));
        check("sign in: a different challenge gives a different answer", !Arrays.equals(a, SignIn.answer(challenge(MAC, OURS), null, OURS)));

        // layouts not known: no answer
        check("sign in: no challenge part, no answer", SignIn.answer(Frame.message(Proto.CHALLENGE), MAC, OURS) == null);
        check("sign in: a cut challenge, no answer", SignIn.answer(Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_PART, new byte[] {17, '0', '0'})), MAC, OURS) == null);
        check("sign in: a challenge without bytes, no answer", SignIn.answer(challenge(MAC, new byte[0]), MAC, OURS) == null);
        check("sign in: no address in the challenge and none connected, no answer", SignIn.answer(challenge("", THEIRS), null, OURS) == null);
        SignIn.Challenge fb = SignIn.read(challenge("", THEIRS), "00:24:e4:12:34:56");
        check("sign in: no address in the challenge: the connected one, in capitals", fb != null && MAC.equals(fb.address));
        check("sign in: no own challenge, no answer", SignIn.answer(c, null, new byte[0]) == null);

        // the watch's own proof in its hello answer
        byte[] hello = WatchDemo.helloReply("Steel HR test", 1971);
        check("sign in: the watch's proof for our challenge is right", Boolean.TRUE.equals(SignIn.watchProof(withPart(hello, Proto.CHALLENGE_ANSWER, proofPart(PROOF_OURS)), OURS, MAC)));
        check("sign in: a wrong proof is found", Boolean.FALSE.equals(SignIn.watchProof(withPart(hello, Proto.CHALLENGE_ANSWER, proofPart(PROOF_THEIRS)), OURS, MAC)));
        check("sign in: a cut proof is wrong", Boolean.FALSE.equals(SignIn.watchProof(withPart(hello, Proto.CHALLENGE_ANSWER, new byte[] {20, 1}), OURS, MAC)));
        check("sign in: no proof part: nothing to check", SignIn.watchProof(hello, OURS, MAC) == null);
    }

    static boolean joinsAgain(byte[] msg, int size) {
        Frame.Assembler as = new Frame.Assembler();
        byte[] got = null;
        for (byte[] p : Frame.pieces(msg, size)) got = as.feed(p);
        return Arrays.equals(msg, got);
    }

    /** A made up watch that asks for a sign in after the hello, and answers the hello once the right proof came. */
    static FakeWatch signingWatch(String expectProof, byte[] helloAfter) {
        final String want = expectProof;
        final byte[] after = helloAfter;
        FakeWatch w = new FakeWatch() {
            @Override
            public void send(byte[] msg) throws IOException {
                if (Frame.type(msg) == Proto.CHALLENGE) {
                    Frame.Part p = Frame.first(msg, Proto.CHALLENGE_ANSWER);
                    boolean right = p != null && p.value.length == 21 && want.equalsIgnoreCase(Hex.encode(Arrays.copyOfRange(p.value, 1, 21)));
                    // a wrong proof: the watch asks again (one way a refusal could look)
                    answer(Proto.CHALLENGE, right ? after : challenge(MAC, THEIRS));
                }
                super.send(msg);
            }
        };
        w.answer(Proto.HELLO, challenge(MAC, THEIRS));
        return w;
    }

    static Session.Plan signPlan() {
        Session.Plan p = plan();
        p.address = MAC;
        p.ours = OURS;
        return p;
    }

    static void signInSession() throws Exception {
        byte[] helloOk = withPart(WatchDemo.helloReply("Steel HR test", 1971), Proto.CHALLENGE_ANSWER, proofPart(PROOF_OURS));
        FakeWatch w = signingWatch(PROOF_THEIRS, helloOk);
        Store st = new Store();
        Lines log = new Lines();
        Session.Result r = Session.run(w, signPlan(), new Gate(), st, log);
        List<Integer> order = Arrays.asList(Proto.HELLO, Proto.CHALLENGE, Proto.CONNECT, Proto.BATTERY, Proto.SET_TIME, Proto.ACTIVITY, Proto.MOVEMENT, Proto.HEART_RATE, Proto.SYNC_DONE);
        check("sign in session: answered, then the sync goes on to the end", r.ended == null && w.types().equals(order) && r.activityDone && r.heartDone, r.ended + " " + w.types());
        check("sign in session: accepted", r.signIn == Session.SIGN_IN_ACCEPTED, r.signIn);
        check("sign in session: the challenge and the hello answer are stored like every message", st.got.size() > 1
                && Frame.type(st.got.get(0)) == Proto.CHALLENGE && Frame.type(st.got.get(1)) == Proto.HELLO, st.got.size());
        check("sign in session: the log names types and lengths only", noValues(log.lines) && !joined(log.lines).contains(MAC)
                && !joined(log.lines).contains("2EM5"), log.lines);
        check("sign in session: the log says the answer went and was accepted", joined(log.lines).contains("sent 0x0128 sign in answer, 69 bytes")
                && joined(log.lines).contains("proof: right") && joined(log.lines).contains("sign in accepted"), log.lines);
        Records.Decoded d = new Records.Decoded();
        for (byte[] m : st.got) Records.decode(m, 1, d);
        check("sign in session: the name and firmware still come from the hello answer", "Steel HR test".equals(d.name) && d.firmware == 1971, d.name);

        // no proof from the watch: accepted all the same (the watch need not prove itself)
        w = signingWatch(PROOF_THEIRS, WatchDemo.helloReply("Steel HR test", 1971));
        log = new Lines();
        r = Session.run(w, signPlan(), new Gate(), new Store(), log);
        check("sign in session: no proof from the watch: the sync goes on", r.ended == null && r.heartDone && joined(log.lines).contains("proof: none sent"), r.ended);

        // a random own challenge when none is given
        w = signingWatch(PROOF_THEIRS, WatchDemo.helloReply("Steel HR test", 1971));
        Session.Plan rp = signPlan();
        rp.ours = null;
        r = Session.run(w, rp, new Gate(), new Store(), new Lines());
        byte[] sentAnswer = w.sent.get(1);
        SignIn.Challenge back = SignIn.read(sentAnswer, null);
        check("sign in session: a random own challenge of 16 bytes", r.ended == null && back != null && back.bytes.length == 16 && !Arrays.equals(OURS, back.bytes));

        // the watch's proof is wrong: nothing more is asked
        w = signingWatch(PROOF_THEIRS, withPart(WatchDemo.helloReply("Steel HR test", 1971), Proto.CHALLENGE_ANSWER, proofPart(PROOF_THEIRS)));
        r = Session.run(w, signPlan(), new Gate(), new Store(), new Lines());
        check("sign in session: a wrong proof from the watch ends it, sync done sent", Session.WATCH_PROOF.equals(r.ended)
                && w.types().equals(Arrays.asList(Proto.HELLO, Proto.CHALLENGE, Proto.SYNC_DONE)), r.ended + " " + w.types());

        // the watch refuses: it asks again
        w = signingWatch("00", helloOk);
        log = new Lines();
        r = Session.run(w, signPlan(), new Gate(), new Store(), log);
        check("sign in session: asked again after our answer: refused, sync done sent, nothing else asked", Session.SIGN_IN_REFUSED.equals(r.ended)
                && w.types().equals(Arrays.asList(Proto.HELLO, Proto.CHALLENGE, Proto.SYNC_DONE)) && r.signIn == Session.SIGN_IN_SENT, r.ended + " " + w.types());
        check("sign in session: the log says it was asked again", joined(log.lines).contains("asked for a sign in again") && noValues(log.lines), log.lines);

        // the watch refuses: it stays silent
        w = signingWatch(PROOF_THEIRS, helloOk);
        w.silent.add(Proto.CHALLENGE);
        log = new Lines();
        r = Session.run(w, signPlan(), new Gate(), new Store(), log);
        check("sign in session: silent after our answer: refused", Session.SIGN_IN_REFUSED.equals(r.ended) && last(w) == Proto.SYNC_DONE
                && joined(log.lines).contains("after the sign in answer"), r.ended);

        // the watch refuses: it closes the link
        w = signingWatch(PROOF_THEIRS, helloOk);
        w.closeAfter = Proto.CHALLENGE;
        r = Session.run(w, signPlan(), new Gate(), new Store(), new Lines());
        check("sign in session: link closed after our answer: refused, no sync done on a closed link", Session.SIGN_IN_REFUSED.equals(r.ended) && !r.doneSent, r.ended);

        // the link is lost while the answer is sent: lost, not refused
        w = signingWatch(PROOF_THEIRS, helloOk);
        w.dropAt = Proto.CHALLENGE;
        r = Session.run(w, signPlan(), new Gate(), new Store(), new Lines());
        check("sign in session: link lost while sending the answer: its own sentence", Session.LINK_LOST.equals(r.ended), r.ended);

        // a sign in in the middle of a data answer is answered and the data goes on
        w = new FakeWatch();
        w.answers.get(Proto.HEART_RATE).add(1, challenge(MAC, THEIRS));
        log = new Lines();
        r = Session.run(w, signPlan(), new Gate(), new Store(), log);
        check("sign in session: a sign in during a data answer is answered, the data goes on", r.ended == null && r.heartDone
                && r.signIn == Session.SIGN_IN_ACCEPTED && w.types().contains(Proto.CHALLENGE) && last(w) == Proto.SYNC_DONE, r.ended + " " + w.types());

        // the ring asks right after our answer: the ring still wins
        w = signingWatch(PROOF_THEIRS, helloOk);
        Gate g = new Gate();
        g.askedUntilRing = 1;
        r = Session.run(w, signPlan(), g, new Store(), new Lines());
        check("sign in session: the ring wants Bluetooth right after our answer: ends for the ring", Session.FOR_RING.equals(r.ended)
                && w.types().equals(Arrays.asList(Proto.HELLO, Proto.CHALLENGE, Proto.SYNC_DONE)), r.ended + " " + w.types());

        // review of #317: a sign in during a data answer, then silence: a refusal, with the text for a watch already told something
        w = new FakeWatch();
        List<byte[]> hra = w.answers.get(Proto.HEART_RATE);
        while (hra.size() > 1) hra.remove(hra.size() - 1);
        hra.add(challenge(MAC, THEIRS));
        log = new Lines();
        r = Session.run(w, signPlan(), new Gate(), new Store(), log);
        check("sign in session: silence after a sign in answered during a data answer is a refusal, and says what arrived is stored",
                Session.SIGN_IN_REFUSED_LATE.equals(r.ended) && !r.heartDone && last(w) == Proto.SYNC_DONE && joined(log.lines).contains("after the sign in answer"), r.ended);
        // and the same refusal before anything was sent keeps the first text
        w = signingWatch(PROOF_THEIRS, helloOk);
        w.silent.add(Proto.CHALLENGE);
        r = Session.run(w, signPlan(), new Gate(), new Store(), new Lines());
        check("sign in session: refused right after the hello: nothing was changed on the watch", Session.SIGN_IN_REFUSED.equals(r.ended) && !r.wrote, r.ended);

        // the plain sentences have no dashes
        for (String t : new String[] {Session.CHALLENGE, Session.SIGN_IN_REFUSED, Session.SIGN_IN_REFUSED_LATE, Session.WATCH_PROOF})
            check("sign in: plain sentence without dashes: " + t, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
    }

    static int last(FakeWatch w) {
        return Frame.type(w.sent.get(w.sent.size() - 1));
    }

    static String joined(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('\n');
        return b.toString();
    }

    /** Every log line names only types (0x....), counts, lengths and seconds: no bpm, steps, percent or hex beyond a type. */
    static boolean noValues(List<String> lines) {
        for (String l : lines) {
            String rest = l.replaceAll("0x[0-9a-f]{4}", "");
            if (rest.matches(".*[0-9a-f]{6,}.*") || rest.contains("bpm") || rest.contains("%") || rest.contains("Steel")) return false;
            if (!l.startsWith("watch: ")) return false;
        }
        return !lines.isEmpty();
    }

    // ------------------------------------------------------------------------------------- days

    static void days() {
        final ZoneId zurich = ZoneId.of("Europe/Zurich");
        WatchDays.Zones z = new WatchDays.Zones() {
            @Override
            public ZoneId at(double unix) {
                return zurich;
            }
        };
        LocalDate d = LocalDate.of(2026, 3, 29); // the clocks go forward in Zurich
        long midnight = d.atStartOfDay(zurich).toEpochSecond();
        List<Records.Steps> steps = Arrays.asList(new Records.Steps(midnight - 60, 60, 100, 70, 4), new Records.Steps(midnight + 60, 60, 200, 140, 8),
                new Records.Steps(midnight + 23 * 3600 - 60, 60, 300, 210, 12), new Records.Steps(midnight + 23 * 3600 + 60, 60, 400, 280, 16));
        TreeMap<LocalDate, Integer> per = WatchDays.stepsPerDay(steps, z);
        // the day has 23 hours: midnight + 23 h is the next day
        check("days: steps by the calendar day of their start, a clock change day of 23 hours", per.get(d) == 500 && per.get(d.minusDays(1)) == 100
                && per.get(d.plusDays(1)) == 400, per);
        check("days: distance per day", WatchDays.metresPerDay(steps, z).get(d) == 350);
        check("days: a missing day is NaN, a day with data its value", Double.isNaN(WatchDays.of(per, d.minusDays(5))) && WatchDays.of(per, d) == 500);
        // a trip: the same moment belongs to another day in another zone
        WatchDays.Zones trip = new WatchDays.Zones() {
            @Override
            public ZoneId at(double unix) {
                return unix < 2000000000L ? ZoneId.of("America/New_York") : zurich;
            }
        };
        check("days: the zone of the time decides the day (a trip)", WatchDays.day(midnight + 60, trip).equals(d.minusDays(1)));
        List<Object[]> rows = Arrays.asList(new Object[] {0.0, 100.0, "Europe/Zurich"}, new Object[] {200.0, 300.0, "America/New_York"});
        check("days: zones from the table, the later row between two, the newest after the last", "Europe/Zurich".equals(WatchDays.zoneIn(rows, 50))
                && "America/New_York".equals(WatchDays.zoneIn(rows, 150)) && "America/New_York".equals(WatchDays.zoneIn(rows, 999))
                && WatchDays.zoneIn(new ArrayList<Object[]>(), 1) == null && WatchDays.zoneIn(null, 1) == null);
        WatchDays.Zones fromRows = WatchDays.zones(Collections.singletonList(new Object[] {0.0, 1.0, "Not/AZone"}), zurich);
        check("days: zones without a table or with a bad id fall back to the phone's zone", WatchDays.zones(null, zurich).at(5).equals(zurich)
                && fromRows.at(0).equals(zurich) && WatchDays.zones(rows, zurich).at(150).equals(ZoneId.of("America/New_York")));
        check("days: the day starts at its midnight", WatchDays.dayStart(d, z) == midnight);
        List<Records.Workout> ws = Arrays.asList(new Records.Workout(d.atTime(18, 0).atZone(zurich).toEpochSecond(), 2100, 2, 5000, 5500, 380), new Records.Workout(midnight + 7 * 3600, 600, 6, 0, 3000, 90),
                new Records.Workout(midnight - 3600, 600, 1, 900, 600, 30));
        List<Records.Workout> on = WatchDays.workoutsOn(ws, d, z);
        check("days: the day's workouts, oldest first", on.size() == 2 && on.get(0).kind == 6 && on.get(1).kind == 2, on.size());
        String line = WatchDays.workoutLine(on.get(1), z);
        check("days: a workout's line", line.equals("Running at 18:00, 35 min, 5.5 km, 5000 steps, 380 kcal"), line);
        check("days: lengths and distances", WatchDays.duration(3900).equals("1 h 05 min") && WatchDays.distance(850).equals("850 m") && WatchDays.duration(59).equals("1 min"));
        List<Records.Heart> hr = Arrays.asList(new Records.Heart(midnight + 3600, 60), new Records.Heart(midnight - 1, 55), new Records.Heart(midnight + 600, 58));
        List<long[]> ho = WatchDays.heartOn(hr, d, z);
        check("days: the day's heart rates in time order", ho.size() == 2 && ho.get(0)[1] == 58 && ho.get(1)[1] == 60);

        // a night of three pieces with a gap, ending on the morning of d
        long bed = midnight - 3600;
        List<Records.Sleep> sl = new ArrayList<Records.Sleep>(Arrays.asList(new Records.Sleep(bed, 1800, Records.LIGHT), new Records.Sleep(bed + 1800, 1200, Records.DEEP),
                new Records.Sleep(bed + 3600, 900, Records.REM), new Records.Sleep(bed + 5400, 600, 1)));
        // a nap in the afternoon of d: its end is after 18:00? no, at 15:30; shorter than the night, so the night wins
        sl.add(new Records.Sleep(midnight + 14 * 3600, 1800, Records.LIGHT));
        WatchDays.Night n = WatchDays.nightFor(sl, d, z);
        check("days: the night joins pieces across a short gap", n != null && n.start == bed && n.end == bed + 6000, n == null ? null : n.end - n.start);
        check("days: minutes per stage", n.deepMin == 20 && n.lightMin == 30 && n.remMin == 15 && n.otherMin == 10 && n.asleepMin() == 65, n.deepMin + " " + n.lightMin);
        check("days: per minute stages in the app's digits, the gap and the unknown code as 0", n.stages.length == 100 && n.stages[0] == 2 && n.stages[30] == 1
                && n.stages[52] == 0 && n.stages[60] == 3 && n.stages[95] == 0, n.stages.length);
        check("days: no night on a day without sleep", WatchDays.nightFor(sl, d.plusDays(3), z) == null);
        check("days: stage codes", WatchDays.stage(Records.DEEP) == 1 && WatchDays.stage(Records.LIGHT) == 2 && WatchDays.stage(Records.REM) == 3 && WatchDays.stage(1) == 0);
        // battery: the last reading of each day and the drop per day without the charge
        List<Records.Battery> bat = Arrays.asList(new Records.Battery(midnight + 7 * 3600, 90, 2, 0), new Records.Battery(midnight + 86400 + 7 * 3600, 86, 2, 0),
                new Records.Battery(midnight + 86400 + 19 * 3600, 100, 0, 0), new Records.Battery(midnight + 2 * 86400 + 7 * 3600, 98, 2, 0));
        TreeMap<LocalDate, Integer> bd = WatchDays.batteryPerDay(bat, z);
        check("days: the last battery reading of each day", bd.get(d) == 90 && bd.get(d.plusDays(1)) == 100 && bd.get(d.plusDays(2)) == 98, bd);
        double drop = WatchDays.dropPerDay(bat, 0);
        check("days: the drop per day leaves the charge out", Math.abs(drop - 3.0) < 1e-9, drop);
        check("days: too few readings for a drop", Double.isNaN(WatchDays.dropPerDay(bat.subList(0, 1), 0))
                && Double.isNaN(WatchDays.dropPerDay(Arrays.asList(bat.get(0), new Records.Battery(bat.get(0).t + 3600, 89, 2, 0)), 0)));
        check("days: the newest battery reading", WatchDays.newestBattery(bat).pct == 98 && WatchDays.newestBattery(new ArrayList<Records.Battery>()) == null);
    }

    // -------------------------------------------------------------------------------------- due

    static long at(LocalDate d, int h, int m, ZoneId z) {
        return ZonedDateTime.of(d.atTime(h, m), z).toEpochSecond();
    }

    static void due() {
        ZoneId z = ZoneId.of("Europe/Zurich");
        LocalDate d = LocalDate.of(2026, 11, 3);
        check("due: by hand never", !WatchDue.due(at(d, 12, 0, z), 0, 0, 0, z) && WatchDue.lastSlot(at(d, 12, 0, z), 0, z) == 0);
        check("due: once a day, before 07:00 the slot of yesterday", WatchDue.lastSlot(at(d, 6, 0, z), 1, z) == at(d.minusDays(1), 7, 0, z));
        check("due: once a day at 07:00", WatchDue.lastSlot(at(d, 7, 0, z), 1, z) == at(d, 7, 0, z));
        long seven = at(d, 7, 0, z);
        check("due: the first run after 07:00 syncs", WatchDue.due(at(d, 8, 10, z), 1, at(d.minusDays(1), 8, 0, z), 0, z));
        check("due: synced after 07:00, nothing more today", !WatchDue.due(at(d, 11, 10, z), 1, at(d, 8, 10, z), at(d, 8, 10, z), z));
        check("due: a failed attempt after 07:00 waits 2.5 hours", !WatchDue.due(at(d, 9, 0, z), 1, 0, at(d, 8, 10, z), z)
                && WatchDue.due(at(d, 10, 45, z), 1, 0, at(d, 8, 10, z), z));
        check("due: an attempt before the slot does not hold it off", WatchDue.due(at(d, 7, 10, z), 1, 0, at(d, 6, 50, z), z));
        check("due: twice a day, the evening slot", WatchDue.lastSlot(at(d, 20, 0, z), 2, z) == at(d, 19, 0, z)
                && WatchDue.due(at(d, 20, 0, z), 2, at(d, 8, 0, z), at(d, 8, 0, z), z));
        check("due: 4 times a day across midnight", WatchDue.lastSlot(at(d, 0, 30, z), 4, z) == at(d.minusDays(1), 19, 0, z)
                && WatchDue.lastSlot(at(d, 1, 30, z), 4, z) == at(d, 1, 0, z));
        check("due: a last sync in the future (clock set back) does not hold it off", WatchDue.due(at(d, 8, 0, z), 1, seven + 86400 * 10, 0, z));
        // the clocks go forward at 02:00 on 29 March 2026: 01:00 exists, 07:00 is 07:00 local, one hour less after midnight
        LocalDate dst = LocalDate.of(2026, 3, 29);
        check("due: on a clock change day the slot is the clock time", WatchDue.lastSlot(at(dst, 8, 0, z), 1, z) == at(dst, 7, 0, z)
                && at(dst, 7, 0, z) - dst.atStartOfDay(z).toEpochSecond() == 6 * 3600);
        // a zone where 01:00 does not exist on its change day: moves on by the change
        ZoneId havana = ZoneId.of("America/Havana"); // changes at 00:00 to 01:00
        LocalDate hd = LocalDate.of(2026, 3, 8);
        long slot = WatchDue.lastSlot(at(hd, 12, 0, havana), 4, havana);
        check("due: every choice has a slot on any day", slot > 0, slot);
        check("due: choices", WatchDue.valid(0) && WatchDue.valid(4) && !WatchDue.valid(3) && WatchDue.hours(3).length == 0 && WatchDue.LABELS.length == WatchDue.PER_DAY.length);
        for (int k : WatchDue.PER_DAY) check("due: every choice has a plain line without dashes " + k, !WatchDue.explain(k).contains(" - ") && !WatchDue.explain(k).isEmpty());
    }

    // ------------------------------------------------------------------------------------- demo

    static void demo() {
        ZoneId z = ZoneId.of("Europe/Zurich");
        LocalDate today = LocalDate.of(2026, 11, 3);
        long now = at(today, 10, 0, z);
        List<WatchDemo.Msg> msgs = WatchDemo.history(today, 35, now, z, new Random(251));
        List<byte[]> bodies = new ArrayList<byte[]>();
        List<Long> caps = new ArrayList<Long>();
        Set<String> keys = new HashSet<String>();
        long newest = 0;
        for (WatchDemo.Msg m : msgs) {
            bodies.add(m.body);
            caps.add(m.captured);
            keys.add(m.session + "/" + m.seq);
            newest = Math.max(newest, m.captured);
        }
        check("demo: every message has its own key", keys.size() == msgs.size(), keys.size() + " of " + msgs.size());
        check("demo: nothing after now", newest <= now, newest - now);
        Records.Decoded d = Records.decode(bodies, caps);
        final ZoneId zz = z;
        WatchDays.Zones zones = new WatchDays.Zones() {
            @Override
            public ZoneId at(double unix) {
                return zz;
            }
        };
        TreeMap<LocalDate, Integer> steps = WatchDays.stepsPerDay(d.steps, zones);
        check("demo: steps on every day of five weeks, today so far too", steps.size() >= 35 && steps.containsKey(today) && steps.get(today.minusDays(1)) > 5000, steps.size());
        check("demo: workouts every third day", d.workouts.size() >= 10, d.workouts.size());
        check("demo: a night every morning", WatchDays.nightFor(d.sleep, today, zones) != null && WatchDays.nightFor(d.sleep, today.minusDays(10), zones) != null);
        check("demo: heart rate about every 30 minutes", d.heart.size() > 35 * 40, d.heart.size());
        double drop = WatchDays.dropPerDay(d.battery, now - 7 * 86400);
        check("demo: the battery drops about 3 % a day", drop > 2 && drop < 5, drop);
        check("demo: the made up watch's name", WatchDemo.NAME.equals(d.name));
        // the same seed gives the same demo
        List<WatchDemo.Msg> again = WatchDemo.history(today, 35, now, z, new Random(251));
        boolean same = again.size() == msgs.size();
        for (int i = 0; same && i < msgs.size(); i++) same = Arrays.equals(again.get(i).body, msgs.get(i).body);
        check("demo: the same every time", same);
    }

    // ------------------------------------------------------------------------------------- gate

    static void gate() throws Exception {
        final RingGate g = new RingGate();
        check("gate: nothing waits, no sync: the ring is not wanted", !g.ringWanted());
        g.syncs.incrementAndGet();
        check("gate: a ring sync counted: wanted", g.ringWanted());
        g.syncs.decrementAndGet();
        check("gate: the watch takes the ring lock without waiting", g.takeIfFree());
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        Thread ring = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    if (g.op.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) g.op.unlock();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ring.start();
        started.await();
        long until = System.currentTimeMillis() + 3000;
        while (!g.ringWanted() && System.currentTimeMillis() < until) Thread.sleep(10);
        check("gate: a ring task waiting for the lock is seen at once", g.ringWanted());
        g.op.unlock();
        ring.join(5000);
        check("gate: once the watch gave the lock back the ring task got it", !ring.isAlive() && !g.ringWanted());
        check("gate: one watch sync at a time", g.watch.tryLock() && !otherThreadGets(g.watch));
        g.watch.unlock();
    }

    static boolean otherThreadGets(final java.util.concurrent.locks.ReentrantLock l) throws InterruptedException {
        final boolean[] got = {false};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                got[0] = l.tryLock();
                if (got[0]) l.unlock();
            }
        });
        t.start();
        t.join();
        return got[0];
    }

    // ------------------------------------------------------------------- main device (one set of numbers)

    static final ZoneId MD_ZONE = ZoneId.of("Europe/Zurich");
    static final LocalDate MD_DAY = LocalDate.of(2026, 6, 10);

    static double mdFrom(LocalDate d, ZoneId z) {
        return d.atStartOfDay(z).toEpochSecond();
    }

    static double mdAt(LocalDate d, double hours, ZoneId z) {
        return d.atStartOfDay(z).toEpochSecond() + hours * 3600;
    }

    /** A ring worn the whole day (movement 1.3 every quarter hour, a heart rate point in each) except from {@code offFrom} to {@code offTo} hours. */
    static MainDevice.Ring mdRing(LocalDate d, ZoneId z, double offFrom, double offTo, double steps) {
        MainDevice.Ring r = new MainDevice.Ring();
        r.steps = steps;
        r.metres = app.openring.core.analysis.Wellness.stepsToMeters(steps);
        double from = mdFrom(d, z), to = mdFrom(d.plusDays(1), z);
        for (double t = from; t < to; t += 900) {
            double h = (t - from) / 3600;
            if (h >= offFrom && h < offTo) continue;
            int q = MainDevice.clockQuarter(t, z);
            r.met[q] = h >= 12 && h < 13 ? 3.0 : 1.3;
            r.hr.add(t + 450, 60 + (int) h);
        }
        return r;
    }

    /** A watch worn the whole day except from {@code offFrom} to {@code offTo} hours: 100 steps (72 m) a quarter hour, heart rate every 30 minutes. */
    static MainDevice.Watch mdWatch(LocalDate d, ZoneId z, double offFrom, double offTo) {
        MainDevice.Watch w = new MainDevice.Watch();
        double from = mdFrom(d, z), to = mdFrom(d.plusDays(1), z);
        for (double t = from; t < to; t += 900) {
            double h = (t - from) / 3600;
            if (h >= offFrom && h < offTo) continue;
            w.steps.add(new Records.Steps((long) t, 900, 100, 72, 4));
            if (((long) (t - from)) % 1800 == 0) w.heart.add(new Records.Heart((long) t, 70));
        }
        return w;
    }

    static boolean sameTs(app.openring.core.analysis.TS a, app.openring.core.analysis.TS b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (Double.doubleToLongBits(a.t(i)) != Double.doubleToLongBits(b.t(i)) || Double.doubleToLongBits(a.v(i)) != Double.doubleToLongBits(b.v(i))) return false;
        }
        return true;
    }

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static boolean plain(String t) {
        return t == null || !t.contains(" - ") && !t.contains("–") && !t.contains("—") && !t.contains("--");
    }

    static void mainDevice() {
        ZoneId z = MD_ZONE;
        LocalDate day = MD_DAY;
        double from = mdFrom(day, z), to = mdFrom(day.plusDays(1), z);
        check("main device: a stored value that is no choice is the ring", MainDevice.valid(7) == MainDevice.RING && MainDevice.valid(-1) == MainDevice.RING
                && MainDevice.valid(MainDevice.WATCH) == MainDevice.WATCH);

        // ---- only a ring: its numbers exactly as stored, nothing named
        MainDevice.Ring ring = mdRing(day, z, 14, 16, 6543.0);
        ring.night = true;
        for (MainDevice.Watch none : new MainDevice.Watch[] {null, new MainDevice.Watch()}) {
            for (int main : new int[] {MainDevice.RING, MainDevice.WATCH}) {
                MainDevice.Day d = MainDevice.of(main, day, from, to, z, ring, none);
                check("main device: ring only gives the ring's steps, distance and line byte for byte (main " + main + ", watch " + (none == null ? "null" : "empty") + ")",
                        bits(d.steps, 6543.0) && bits(d.metres, ring.metres) && d.hr == ring.hr && d.fillSteps == 0 && d.stepFill.isEmpty() && d.hrFill.isEmpty()
                                && d.watchNight == null && d.workouts.isEmpty(), d.steps);
                check("main device: ring only names nothing", MainDevice.stepsLine(d, z, Locale.US) == null && MainDevice.heartLine(d, z) == null
                        && MainDevice.nightLine(d) == null && MainDevice.ringTooLine(d, 80) == null && (main == MainDevice.WATCH || MainDevice.stepsNote(d) == null)
                        && !d.filled(), MainDevice.nightLine(d));
            }
        }
        MainDevice.Ring nothing = new MainDevice.Ring();
        MainDevice.Day empty = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, null);
        check("main device: a day without the ring's summary and without a watch has nothing to show", !empty.any() && Double.isNaN(empty.steps) && Double.isNaN(empty.metres));

        // ---- both, the ring worn all day: the ring's numbers, the watch only lists its workout
        MainDevice.Ring full = mdRing(day, z, 99, 99, 7000);
        MainDevice.Watch watch = mdWatch(day, z, 99, 99);
        watch.workouts.add(new Records.Workout((long) mdAt(day, 18, z), 1800, Records.RUN, 4000, 5000, 300));
        watch.workouts.add(new Records.Workout((long) mdAt(day, 7, z), 1200, 6, 0, 8000, 200));
        watch.workouts.add(new Records.Workout((long) mdAt(day, -2, z), 1200, 6, 0, 8000, 200));
        MainDevice.Day both = MainDevice.of(MainDevice.RING, day, from, to, z, full, watch);
        check("main device: ring main and worn all day: the ring's numbers byte for byte", bits(both.steps, 7000) && both.hr == full.hr && both.stepFill.isEmpty()
                && both.hrFill.isEmpty() && !both.whole, both.steps);
        check("main device: the watch's workouts of the day are listed, oldest first, the day before's not", both.workouts.size() == 2
                && both.workouts.get(0).start < both.workouts.get(1).start, both.workouts.size());

        // ---- ring main with a gap (charging 14:00 to 16:00): the watch fills it
        MainDevice.Day gap = MainDevice.of(MainDevice.RING, day, from, to, z, ring, watch);
        check("main device: the watch's steps in the ring's gap are added (8 quarter hours of 100)", bits(gap.fillSteps, 800) && bits(gap.steps, 6543 + 800)
                && bits(gap.metres, ring.metres + 8 * 72), gap.steps + " " + gap.fillSteps);
        check("main device: the steps' gap is one stretch, 14:00 to 16:00", gap.stepFill.size() == 1 && gap.stepFill.get(0)[0] == mdAt(day, 14, z)
                && gap.stepFill.get(0)[1] == mdAt(day, 16, z), gap.stepFill.size());
        int ringPts = ring.hr.size();
        check("main device: the watch's heart rate fills the gap, 4 readings every 30 minutes, the ring's points kept", gap.hr.size() == ringPts + 4
                && gap.hrFill.size() == 1 && gap.hrFill.get(0)[0] == mdAt(day, 14, z) && gap.hrFill.get(0)[1] == mdAt(day, 16, z), gap.hr.size());
        boolean ordered = true;
        for (int i = 1; i < gap.hr.size(); i++) ordered &= gap.hr.t(i) >= gap.hr.t(i - 1);
        check("main device: the joined line is in time order", ordered);
        String sl = MainDevice.stepsLine(gap, z, Locale.US);
        check("main device: the steps line names the watch, the count and the time", "Includes 800 steps from your watch, 14:00 to 16:00, when your ring was not worn.".equals(sl), sl);
        String hl = MainDevice.heartLine(gap, z);
        check("main device: the heart rate line names the watch and the time", "From your watch 14:00 to 16:00, when your ring was not worn.".equals(hl), hl);
        check("main device: the Steps tile says some came from the watch", "Estimated from your movement, some counted by your watch".equals(MainDevice.stepsNote(gap)));

        // ---- watch main, worn all day: the watch's numbers, nothing from the ring
        MainDevice.Day wm = MainDevice.of(MainDevice.WATCH, day, from, to, z, ring, watch);
        check("main device: watch main: its steps, distance and its own quarter hour line", bits(wm.steps, 96 * 100) && bits(wm.metres, 96 * 72)
                && wm.hr.size() == 48 && wm.fillSteps == 0 && wm.stepFill.isEmpty() && wm.hrFill.isEmpty(), wm.steps + " " + wm.hr.size());
        check("main device: watch main: the tile says counted by the watch", "Counted by your watch".equals(MainDevice.stepsNote(wm)) && MainDevice.stepsLine(wm, z, Locale.US) == null);

        // ---- watch main with a gap 11:00 to 15:00: the ring fills where the watch has no record within 30 minutes
        MainDevice.Watch wgap = mdWatch(day, z, 11, 15);
        MainDevice.Day wf = MainDevice.of(MainDevice.WATCH, day, from, to, z, full, wgap);
        // records up to the 10:45 one, which lasts to 11:00: worn up to 11:30 with its reach of 30 minutes; from 15:00 on, so worn from 14:30
        // ring estimate 11:45 to 14:30: 12:00 to 13:00 at movement 3.0 gives 105 steps a minute, the rest 1.3 gives none
        check("main device: watch main: the ring's estimate fills its gap, only quarter hours with enough movement count", bits(wf.fillSteps, 4 * 105 * 15)
                && bits(wf.steps, (96 - 16) * 100 + 4 * 105 * 15), wf.fillSteps + " " + wf.steps);
        check("main device: watch main: the gap is the stretch without the watch", wf.stepFill.size() == 1 && wf.stepFill.get(0)[0] == mdAt(day, 11.75, z)
                && wf.stepFill.get(0)[1] == mdAt(day, 14.5, z), wf.stepFill.isEmpty() ? null : MainDevice.clockRanges(wf.stepFill, z));
        check("main device: watch main: the ring's heart rate fills that stretch", wf.hrFill.size() == 1 && wf.hrFill.get(0)[0] == mdAt(day, 11.75, z), wf.hrFill.size());
        String wl = MainDevice.stepsLine(wf, z, Locale.US);
        check("main device: watch main: the line says about, from the ring", "Includes about 6,300 steps from your ring, 11:45 to 14:30, when your watch was not worn.".equals(wl), wl);
        check("main device: watch main: the tile says some are the ring's estimate", "Counted by your watch, some estimated by your ring".equals(MainDevice.stepsNote(wf)));
        check("main device: the ring's quarter hour steps follow its per minute rule", MainDevice.ringSlotSteps(7.5) == 150 * 15 && MainDevice.ringSlotSteps(2.5) == 105 * 15
                && MainDevice.ringSlotSteps(1.8) == 25 * 15 && MainDevice.ringSlotSteps(1.7) == 0 && MainDevice.ringSlotSteps(Double.NaN) == 0);
        // asleep in the ring's night: no estimate there
        MainDevice.Ring sleepy = mdRing(day, z, 99, 99, 7000);
        sleepy.sleeps.add(new double[] {mdAt(day, 11, z), mdAt(day, 15, z)});
        check("main device: watch main: no ring steps while the ring says asleep", MainDevice.of(MainDevice.WATCH, day, from, to, z, sleepy, wgap).fillSteps == 0);

        // ---- the whole day from the other device
        MainDevice.Day wo = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, watch);
        check("main device: watch only: every step and heart rate is the watch's", wo.whole && bits(wo.steps, 96 * 100) && wo.hr.size() == 48 && wo.any(), wo.steps);
        String wol = MainDevice.stepsLine(wo, z, Locale.US);
        check("main device: watch only: the line says the ring has nothing", "From your watch: your ring has no data for this day.".equals(wol)
                && "From your watch: your ring has no data for this day.".equals(MainDevice.heartLine(wo, z)) && "Counted by your watch".equals(MainDevice.stepsNote(wo)), wol);
        MainDevice.Day ro = MainDevice.of(MainDevice.WATCH, day, from, to, z, full, mdWatch(day.minusDays(3), z, 99, 99));
        check("main device: watch main, the watch has nothing on this day: the ring's day as stored", ro.whole && bits(ro.steps, 7000) && ro.hr == full.hr
                && "From your ring: your watch has no data for this day.".equals(MainDevice.stepsLine(ro, z, Locale.US)), ro.steps);

        // ---- day borders: by the record's start, in the day's zone
        MainDevice.Watch edge = new MainDevice.Watch();
        edge.steps.add(new Records.Steps((long) (from - 600), 900, 50, 30, 1));   // 23:50 the day before
        edge.steps.add(new Records.Steps((long) (from + 300), 600, 40, 30, 1));   // 00:05
        edge.steps.add(new Records.Steps((long) (to - 300), 900, 30, 20, 1));     // 23:55
        edge.heart.add(new Records.Heart((long) to, 99));                         // the next midnight
        MainDevice.Day ed = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, edge);
        check("main device: a record before midnight belongs to the day before, one at 23:55 to this day", bits(ed.steps, 70) && ed.hr.size() == 0, ed.steps);
        // the same instants in another zone fall on other days
        ZoneId ny = ZoneId.of("America/New_York");
        double nf = mdFrom(day, ny), nt = mdFrom(day.plusDays(1), ny);
        MainDevice.Day other = MainDevice.of(MainDevice.RING, day, nf, nt, ny, nothing, mdWatch(day, z, 99, 99));
        check("main device: in New York the Zurich day's records from 06:00 on fall on this day", bits(other.steps, (96 - 24) * 100), other.steps);

        // ---- clock change days: 100 and 92 quarter hours
        LocalDate back = LocalDate.of(2026, 10, 25), fwd = LocalDate.of(2026, 3, 29);
        for (LocalDate cd : new LocalDate[] {back, fwd}) {
            double cf = mdFrom(cd, z), ct = mdFrom(cd.plusDays(1), z);
            int n = MainDevice.slots(cf, ct);
            MainDevice.Watch cw = new MainDevice.Watch();
            for (double t = cf; t < ct; t += 900) cw.steps.add(new Records.Steps((long) t, 900, 10, 7, 0));
            MainDevice.Day cday = MainDevice.of(MainDevice.RING, cd, cf, ct, z, nothing, cw);
            check("main device: clock change " + cd + ": " + n + " quarter hours, every one filled once", n == (cd.equals(back) ? 100 : 92) && bits(cday.steps, n * 10)
                    && cday.whole, n + " " + cday.steps);
            MainDevice.Ring cr = mdRing(cd, z, 99, 99, 5000);
            check("main device: clock change " + cd + ": a ring worn all day leaves no gap", MainDevice.of(MainDevice.RING, cd, cf, ct, z, cr, cw).stepFill.isEmpty());
        }

        // ---- nights and switching the main device on the same data
        MainDevice.Watch nw = mdWatch(day, z, 99, 99);
        for (long t = (long) mdAt(day, -1, z); t < mdAt(day, 7, z); t += 1800) nw.sleep.add(new Records.Sleep(t, 1800, Records.LIGHT));
        MainDevice.Ring rn = mdRing(day, z, 99, 99, 7000);
        rn.night = true;
        MainDevice.Day r1 = MainDevice.of(MainDevice.RING, day, from, to, z, rn, nw), w1 = MainDevice.of(MainDevice.WATCH, day, from, to, z, rn, nw);
        check("main device: ring main with its own night: the ring's night is shown", r1.watchNight == null && !r1.nightFromOther() && MainDevice.nightLine(r1) == null);
        check("main device: watch main: the watch's night, and a line naming the ring's", w1.watchNight != null && w1.watchNight.lightMin == 8 * 60 && !w1.nightFromOther()
                && MainDevice.ringTooLine(w1, 81).contains("sleep score of 81") && MainDevice.ringTooLine(w1, Double.NaN).startsWith("Your ring also recorded this night."),
                MainDevice.ringTooLine(w1, 81));
        check("main device: switching changes only what is shown, the inputs stay", bits(rn.steps, 7000) && r1.steps != w1.steps && rn.hr.size() == 96);
        rn.night = false;
        MainDevice.Day r2 = MainDevice.of(MainDevice.RING, day, from, to, z, rn, nw);
        check("main device: ring main without a night: the watch's, with its line", r2.watchNight != null && r2.nightFromOther()
                && "From your watch: your ring has no night for this day.".equals(MainDevice.nightLine(r2)), MainDevice.nightLine(r2));
        rn.night = true;
        MainDevice.Watch nonight = mdWatch(day, z, 99, 99);
        MainDevice.Day w2 = MainDevice.of(MainDevice.WATCH, day, from, to, z, rn, nonight);
        check("main device: watch main without a watch night: the ring's night, with its line", w2.watchNight == null && w2.nightFromOther()
                && "From your ring: your watch has no night for this day.".equals(MainDevice.nightLine(w2)));

        // ---- texts
        List<double[]> two = new ArrayList<double[]>();
        two.add(new double[] {mdAt(day, 14, z), mdAt(day, 16, z)});
        two.add(new double[] {mdAt(day, 22.5, z), mdAt(day, 23, z)});
        check("main device: two stretches read 'and'", "14:00 to 16:00 and 22:30 to 23:00".equals(MainDevice.clockRanges(two, z)), MainDevice.clockRanges(two, z));
        two.add(0, new double[] {mdAt(day, 7, z), mdAt(day, 7.25, z)});
        check("main device: three read with a comma and 'and'", "07:00 to 07:15, 14:00 to 16:00 and 22:30 to 23:00".equals(MainDevice.clockRanges(two, z)));
        check("main device: the last stretch ends at midnight", "23:45 to 00:00".equals(MainDevice.clockRanges(MainDevice.ranges(new boolean[] {false, true}, to - 1800, to), z)));
        MainDevice.Day one = MainDevice.of(MainDevice.RING, day, from, to, z, mdRing(day, z, 14, 14.25, 100), mdWatch(day, z, 99, 99));
        check("main device: one step reads 'step'", MainDevice.stepsLine(one, z, Locale.US).startsWith("Includes 100 steps"));
        boolean ok = true;
        for (String t : new String[] {sl, hl, wl, wol, MainDevice.nightLine(r2), MainDevice.nightLine(w2), MainDevice.ringTooLine(w1, 81), MainDevice.stepsNote(wf),
            MainDevice.stepsNote(gap), app.openring.ui.Explain.MAIN_DEVICE, app.openring.ui.Explain.MAIN_FILLED, app.openring.ui.Explain.MAIN_WATCH_NIGHT}) ok &= plain(t);
        check("main device: every text is plain, no dash as punctuation", ok);
    }

    /** The main device rule's edges: a watch with only one kind of record, records outside the day, gaps without the other device. */
    static void mainDeviceEdges() {
        ZoneId z = MD_ZONE;
        LocalDate day = MD_DAY;
        double from = mdFrom(day, z), to = mdFrom(day.plusDays(1), z);
        MainDevice.Ring nothing = new MainDevice.Ring();
        // a watch with only heart rate, only sleep, only a workout: each counts as rows, each fills a day without the ring
        MainDevice.Watch hrOnly = new MainDevice.Watch();
        hrOnly.heart.add(new Records.Heart((long) mdAt(day, 10, z), 66));
        hrOnly.heart.add(new Records.Heart((long) mdAt(day, -1, z), 55));   // the day before
        hrOnly.heart.add(new Records.Heart((long) mdAt(day, 25, z), 55));   // the day after
        MainDevice.Day h = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, hrOnly);
        check("edges: a watch with only heart rate fills only the line, steps stay empty", !hrOnly.isEmpty() && h.whole && Double.isNaN(h.steps) && h.hr.size() == 1
                && h.any() && h.filled() && MainDevice.stepsLine(h, z, Locale.US) == null && MainDevice.heartLine(h, z).startsWith("From your watch: "), h.hr.size());
        MainDevice.Day hw = MainDevice.of(MainDevice.WATCH, day, from, to, z, nothing, hrOnly);
        check("edges: watch main with only heart rate: no steps, its line", Double.isNaN(hw.steps) && hw.hr.size() == 1 && hw.fillSteps == 0 && !hw.whole
                && "Counted by your watch".equals(MainDevice.stepsNote(hw)), hw.steps);
        MainDevice.Watch sleepOnly = new MainDevice.Watch();
        sleepOnly.sleep.add(new Records.Sleep((long) mdAt(day, -1, z), 7 * 3600, Records.DEEP));
        MainDevice.Day so = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, sleepOnly);
        check("edges: a watch with only a night: the night, nothing else", so.watchNight != null && so.whole && so.any() && Double.isNaN(so.steps) && so.hr.size() == 0
                && MainDevice.heartLine(so, z) == null && MainDevice.stepsLine(so, z, Locale.US) == null, so.watchNight);
        MainDevice.Watch wkOnly = new MainDevice.Watch();
        wkOnly.workouts.add(new Records.Workout((long) mdAt(day, 9, z), 600, Records.SWIM, 0, 500, 80));
        MainDevice.Day wk = MainDevice.of(MainDevice.WATCH, day, from, to, z, nothing, wkOnly);
        check("edges: a watch with only a workout: listed, no numbers", wk.workouts.size() == 1 && wk.any() && Double.isNaN(wk.steps) && !wk.filled(), wk.steps);
        MainDevice.Watch elsewhere = new MainDevice.Watch();
        elsewhere.workouts.add(new Records.Workout((long) mdAt(day, 30, z), 600, Records.SWIM, 0, 500, 80));
        elsewhere.sleep.add(new Records.Sleep((long) mdAt(day, 30, z), 600, Records.LIGHT));
        elsewhere.heart.add(new Records.Heart((long) mdAt(day, 30, z), 60));
        elsewhere.steps.add(new Records.Steps((long) mdAt(day, 30, z), 900, 10, 7, 0));
        MainDevice.Day el = MainDevice.of(MainDevice.RING, day, from, to, z, nothing, elsewhere);
        check("edges: watch rows only on another day: nothing shown, nothing named", !el.whole && !el.any() && !el.filled() && el.workouts.isEmpty(), el.steps);
        MainDevice.Ring ringFull = mdRing(day, z, 99, 99, 4000);
        MainDevice.Day elw = MainDevice.of(MainDevice.WATCH, day, from, to, z, ringFull, elsewhere);
        check("edges: watch main, its rows only on another day: the ring's day, named", elw.whole && bits(elw.steps, 4000) && elw.hr == ringFull.hr
                && MainDevice.stepsNote(elw) == null && "From your ring: your watch has no data for this day.".equals(MainDevice.heartLine(elw, z)), elw.steps);
        MainDevice.Day noRing = MainDevice.of(MainDevice.WATCH, day, from, to, z, nothing, elsewhere);
        check("edges: watch main, neither device on this day: nothing named", !noRing.whole && !noRing.any() && MainDevice.stepsLine(noRing, z, Locale.US) == null
                && MainDevice.heartLine(noRing, z) == null, noRing.steps);

        // the ring's line with a point outside the day and one without a value: neither marks a quarter hour worn
        MainDevice.Ring odd = new MainDevice.Ring();
        odd.steps = 0;
        odd.hr.add(from - 450, 70);
        odd.hr.add(from + 450, Double.NaN);
        odd.hr.add(to + 450, 70);
        MainDevice.Day od = MainDevice.of(MainDevice.RING, day, from, to, z, odd, mdWatch(day, z, 99, 99));
        check("edges: a ring point outside the day or without a value leaves the quarter hour to the watch", od.whole && bits(od.steps, 96 * 100), od.steps);
        MainDevice.Day odw = MainDevice.of(MainDevice.WATCH, day, from, to, z, odd, mdWatch(day, z, 0, 2));
        check("edges: watch main: those ring points fill nothing", odw.hrFill.isEmpty(), odw.hrFill.size());

        // a ring gap where the watch is off too: not named; the other gap is
        MainDevice.Ring twoGaps = mdRing(day, z, 14, 16, 5000);
        for (int q = 80; q < 84; q++) twoGaps.met[q] = Double.NaN;  // 20:00 to 21:00
        app.openring.core.analysis.TS keep = new app.openring.core.analysis.TS();
        for (int i = 0; i < twoGaps.hr.size(); i++) {
            double t = twoGaps.hr.t(i);
            if (t < mdAt(day, 20, z) || t >= mdAt(day, 21, z)) keep.add(t, twoGaps.hr.v(i));
        }
        twoGaps.hr = keep;
        MainDevice.Watch offEvening = mdWatch(day, z, 19.5, 21.5);
        MainDevice.Day tg = MainDevice.of(MainDevice.RING, day, from, to, z, twoGaps, offEvening);
        check("edges: a ring gap the watch did not cover either is not named", tg.stepFill.size() == 1 && tg.hrFill.size() == 1
                && "14:00 to 16:00".equals(MainDevice.clockRanges(tg.stepFill, z)), MainDevice.clockRanges(tg.stepFill, z));
        // watch main, its gap inside the ring's night or with the ring off as well
        MainDevice.Ring night = mdRing(day, z, 99, 99, 5000);
        night.sleeps.add(new double[] {mdAt(day, 12, z), mdAt(day, 12.5, z)});
        night.sleeps.add(new double[] {mdAt(day, 30, z), mdAt(day, 31, z)});
        MainDevice.Day ng = MainDevice.of(MainDevice.WATCH, day, from, to, z, night, mdWatch(day, z, 11, 15));
        check("edges: watch main: the ring's nap inside the gap gives no steps there", bits(ng.fillSteps, 2 * 105 * 15), ng.fillSteps);
        MainDevice.Ring bothOff = mdRing(day, z, 11, 15, 5000);
        MainDevice.Day bo = MainDevice.of(MainDevice.WATCH, day, from, to, z, bothOff, mdWatch(day, z, 11, 15));
        check("edges: watch main: where neither was worn nothing is filled", bo.fillSteps == 0 && bo.stepFill.isEmpty() && bo.hrFill.isEmpty(), bo.fillSteps);
        // watch main, the watch with heart rate only and the ring with movement in its gap: the ring's estimate alone
        MainDevice.Watch hrMorning = new MainDevice.Watch();
        for (double hh = 0; hh < 10; hh += 0.5) hrMorning.heart.add(new Records.Heart((long) mdAt(day, hh, z), 60));
        MainDevice.Day hm = MainDevice.of(MainDevice.WATCH, day, from, to, z, ringFull, hrMorning);
        check("edges: watch main without its steps: the ring's estimate is the day's steps", bits(hm.steps, hm.fillSteps) && hm.fillSteps == 4 * 105 * 15
                && bits(hm.metres, app.openring.core.analysis.Wellness.stepsToMeters(hm.fillSteps)), hm.steps);

        // one step, a night line with the watch main and no ring night
        MainDevice.Watch oneStep = new MainDevice.Watch();
        oneStep.steps.add(new Records.Steps((long) mdAt(day, 15, z), 900, 1, 1, 0));
        MainDevice.Day os = MainDevice.of(MainDevice.RING, day, from, to, z, mdRing(day, z, 14, 16, 3000), oneStep);
        check("edges: one step reads 'step'", "Includes 1 step from your watch, 14:00 to 16:00, when your ring was not worn.".equals(MainDevice.stepsLine(os, z, Locale.US)),
                MainDevice.stepsLine(os, z, Locale.US));
        MainDevice.Watch wn = new MainDevice.Watch();
        wn.sleep.add(new Records.Sleep((long) mdAt(day, -1, z), 7 * 3600, Records.REM));
        MainDevice.Ring noNight = mdRing(day, z, 99, 99, 3000);
        MainDevice.Day nn = MainDevice.of(MainDevice.WATCH, day, from, to, z, noNight, wn);
        check("edges: watch main, the ring without a night: no line about the ring's night", MainDevice.ringTooLine(nn, 70) == null && nn.watchNight != null
                && MainDevice.nightLine(nn) == null, MainDevice.ringTooLine(nn, 70));
        MainDevice.Day rn = MainDevice.of(MainDevice.RING, day, from, to, z, noNight, wn);
        check("edges: ring main: never the line about the ring's night", MainDevice.ringTooLine(rn, 70) == null && rn.nightFromOther() && rn.filled());
        MainDevice.Ring withNight = mdRing(day, z, 99, 99, 3000);
        withNight.night = true;
        MainDevice.Day wnn = MainDevice.of(MainDevice.WATCH, day, from, to, z, withNight, mdWatch(day, z, 99, 99));
        check("edges: watch main, no watch night: the ring's night named, no score line", MainDevice.ringTooLine(wnn, 70) == null && wnn.nightFromOther() && wnn.filled());
        check("edges: the devices' names", "your watch".equals(MainDevice.name(MainDevice.WATCH)) && "your ring".equals(MainDevice.name(MainDevice.RING)));
    }
}
