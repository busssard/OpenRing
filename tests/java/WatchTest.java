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
 * against a made up watch, the day sums and when a sync is due. Every message here is made up; none comes from a real
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
        days();
        due();
        demo();
        gate();
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
    static final class FakeWatch implements Session.Link {
        final List<byte[]> sent = new ArrayList<byte[]>();
        final Deque<byte[]> inbox = new ArrayDeque<byte[]>();
        final Map<Integer, List<byte[]>> answers = new HashMap<Integer, List<byte[]>>();
        final Set<Integer> silent = new HashSet<Integer>();
        Integer dropAt;
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
            answer(Proto.SYNC_DONE, WatchDemo.empty(Proto.SYNC_DONE));
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
        return p;
    }

    static void session() throws Exception {
        FakeWatch w = new FakeWatch();
        Store s = new Store();
        Lines log = new Lines();
        Session.Result r = Session.run(w, plan(), new Gate(), s, log);
        List<Integer> order = Arrays.asList(Proto.HELLO, Proto.CONNECT, Proto.BATTERY, Proto.SET_TIME, Proto.ACTIVITY, Proto.HEART_RATE, Proto.SYNC_DONE);
        check("session: the requests in order, sync done last", w.types().equals(order), w.types());
        check("session: complete, both data requests reached their end", r.ended == null && r.activityDone && r.heartDone && r.doneSent, r.ended);
        check("session: one request at a time (each answer read before the next request)", w.pending == 0, w.pending);
        int expected = 5 + w.answers.get(Proto.ACTIVITY).size() + w.answers.get(Proto.HEART_RATE).size();
        check("session: every message from the watch stored as it came", s.got.size() == expected && r.received == expected, s.got.size() + " of " + expected);
        Records.Decoded d = new Records.Decoded();
        for (byte[] m : s.got) Records.decode(m, 1, d);
        check("session: what was stored decodes to every record", d.steps.size() == 30 && d.heart.size() == 10 && d.battery.size() == 1, d.steps.size());
        check("session: the log names types and lengths only, never a value", noValues(log.lines), log.lines);
        check("session: the log says the end was reached", joined(log.lines).contains("activity: 5 messages") && joined(log.lines).contains("end reached"), log.lines);

        // the watch asks for a sign in: the session ends, says so, and still sends sync done
        w = new FakeWatch();
        w.answer(Proto.HELLO, Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_PART, new byte[] {3, 'a', 'b', 'c', 2, 9, 9})));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: a challenge ends it with its sentence, sync done still sent", Session.CHALLENGE.equals(r.ended) && last(w) == Proto.SYNC_DONE
                && w.types().size() == 2, w.types());
        // a challenge part inside the hello answer counts too
        w = new FakeWatch();
        w.answer(Proto.HELLO, Frame.message(Proto.HELLO, Frame.part(Proto.CHALLENGE_PART, new byte[] {0})));
        r = Session.run(w, plan(), new Gate(), new Store(), new Lines());
        check("session: a challenge part in an answer ends it too", Session.CHALLENGE.equals(r.ended));

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
}
