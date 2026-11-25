import app.openring.core.SyncProgress;
import app.openring.core.withings.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * What the screens show of a running sync (issue #478, {@code core/SyncProgress}): the ring's line with its rough
 * percent, the watch's step n of N with the data steps' percent, how often the screen is told. And that it is display
 * only: the made up watch of WatchTest gets the same bytes in the same order with and without the progress, and source
 * checks pin the ring download's storing lines and where the watch sync calls the progress. Run with tests/run_tests.sh
 * (after WatchTest, whose made up watch it borrows).
 */
public class SyncProgressTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  " + detail);
        check(name, ok);
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    public static void main(String[] args) throws Exception {
        ring();
        names();
        watch();
        dataPercent();
        throttle();
        never();
        sameSession();
        sources();
        System.out.println("sync progress: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void ring() {
        // the fraction: 1 - left / first left, the first batch counts as 0, under 1 while something is left
        eq("ring: no total before the first batch", SyncProgress.ringFraction(-1, -1, -1), -1.0);
        eq("ring: nothing left on the first batch, no total", SyncProgress.ringFraction(0, -1, -1), -1.0);
        eq("ring: the first batch sets the total", SyncProgress.ringFraction(1000, 1000, -1), 0.0);
        eq("ring: 40 % fetched", SyncProgress.ringFraction(600, 1000, 0), 0.4);
        eq("ring: below 1 % left stays at 99 %", SyncProgress.ringFraction(1, 1000, 0.4), 0.99);
        eq("ring: done once the ring says nothing is left", SyncProgress.ringFraction(0, 1000, 0.99), 1.0);
        eq("ring: more left than before never moves back", SyncProgress.ringFraction(900, 1000, 0.4), 0.4);
        eq("ring: more left than at first counts as 0, the shown value stays", SyncProgress.ringFraction(1500, 1000, 0.2), 0.2);
        eq("ring: a shown 100 % with something left again shows 99 %", SyncProgress.ringFraction(10, 1000, 1), 0.99);
        eq("ring: an unknown total keeps what was shown", SyncProgress.ringFraction(-1, 1000, 0.3), 0.3);
        eq("ring: a nonsense shown value is ignored", SyncProgress.ringFraction(600, 1000, 7), 0.4);
        // the same formula as before in the middle: 1 - left / first left
        for (long left = 1; left < 1000; left += 37) {
            double old = Math.max(0, Math.min(1, 1 - left / 1000.0));
            check("ring: the bar's formula as before at " + left, SyncProgress.ringFraction(left, 1000, -1) == Math.min(0.99, old));
        }
        // the texts
        eq("ring: before the first batch", SyncProgress.ringLine(0, -1, false), "Syncing your ring");
        eq("ring: with a percent", SyncProgress.ringLine(1234, 0.4, false), "Syncing your ring, about 40 % (1234 events so far)");
        eq("ring: rounded down", SyncProgress.ringLine(2, 0.999, false), "Syncing your ring, about 99 % (2 events so far)");
        eq("ring: one event", SyncProgress.ringLine(1, 0, false), "Syncing your ring, about 0 % (1 event so far)");
        eq("ring: done, no percent", SyncProgress.ringLine(1234, 1, false), "Syncing your ring (1234 events so far)");
        eq("ring: no total, no percent", SyncProgress.ringLine(12, -1, false), "Syncing your ring (12 events so far)");
        eq("ring: the background job's", SyncProgress.ringLine(1234, 0.4, true), "Syncing your ring in the background, about 40 % (1234 events so far)");
        eq("ring: the background job's, before the first batch", SyncProgress.ringLine(0, -1, true), "Syncing your ring in the background");
        eq("percent: about", SyncProgress.about(0.4), "about 40 %");
        eq("percent: never over 100", SyncProgress.about(3), "about 100 %");
        eq("percent: never under 0", SyncProgress.about(-1), "about 0 %");
    }

    static void names() {
        String[] keys = {Session.HELLO, Session.SIGN_IN, WatchSetup.CONNECT, WatchSetup.BATTERY, WatchSetup.TIME, WatchSetup.USER, WatchSetup.GOAL,
            WatchSetup.DISTANCE, WatchSetup.CLOCK, Session.ACTIVITY, Session.MOVEMENT, Session.HEART, WatchSetup.ALARM, WatchSetup.ALARM_ON,
            WatchSetup.HANDS_STOP, WatchSetup.LANGUAGE, WatchSetup.SCREENS, WatchSetup.FINISHED};
        for (String k : keys) {
            String n = SyncProgress.stepName(k);
            check("names: " + k + " has a plain name", n != null && !n.isEmpty() && n.equals(n.toLowerCase(Locale.ROOT)) && !n.contains(" - ") && !n.contains("_"));
        }
        eq("names: the hello", SyncProgress.stepName(Session.HELLO), "greeting the watch");
        eq("names: heart rate", SyncProgress.stepName(Session.HEART), "heart rate");
        eq("names: both alarm steps are the alarm", SyncProgress.stepName(WatchSetup.ALARM_ON), "alarm");
        eq("names: an unknown key as it is", SyncProgress.stepName("something new"), "something new");
        eq("names: none", SyncProgress.stepName(null), "");
        // every step of a sync (with the user and the alarm, and after a hand setting left open) is one of the named keys
        Session.Plan plan = WatchTest.plan();
        List<String> named = Arrays.asList(keys);
        for (Session.Step s : WatchSetup.closeCalibrationFirst(WatchSetup.sync(plan, profile(true), alarm(true), 0), plan)) {
            check("names: the sync's step " + s.key + " has its name", named.contains(s.key));
        }
        String[] texts = {SyncProgress.WATCH_EXPLAIN, SyncProgress.ringLine(5, 0.5, true)};
        for (String t : texts) check("texts: no dash as punctuation: " + t, !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
    }

    static WatchSetup.Profile profile(boolean complete) {
        WatchSetup.Profile p = new WatchSetup.Profile();
        p.age = 40;
        p.sex = complete ? 1 : 2;
        p.weightKg = 70;
        p.heightCm = 170;
        p.year = 2026;
        return p;
    }

    static WatchSetup.Alarm alarm(boolean on) {
        WatchSetup.Alarm a = new WatchSetup.Alarm();
        a.on = on;
        return a;
    }

    /** Runs a session's step reports through {@code w} the way WatchSync's shownSteps does. */
    static Session.Progress steps(final SyncProgress.Watch w, final long[] clock) {
        return new Session.Progress() {
            @Override
            public void step(String key, int state) {
                w.step(key, state, clock[0]);
            }
        };
    }

    static void watch() {
        SyncProgress.Watch w = new SyncProgress.Watch();
        eq("watch: nothing before a sync", w.line(), null);
        eq("watch: no bar before a sync", w.fraction(), -1.0);
        check("watch: a start tells the screen", w.start(false));
        eq("watch: connecting", w.line(), "Syncing your watch: connecting");
        eq("watch: no bar while the steps are not known", w.fraction(), -1.0);
        Session.Plan plan = WatchTest.plan();
        List<Session.Step> steps = WatchSetup.sync(plan, profile(true), null, 0);
        eq("watch: a sync has 10 steps after the hello", steps.size(), 10);
        w.plan(steps.size(), 10000, 1000, 1000, 1000);
        check("watch: the hello tells the screen", w.step(Session.HELLO, Session.STARTED, 0));
        eq("watch: step 1, the hello", w.line(), "Syncing your watch: step 1 of 11, greeting the watch");
        eq("watch: the bar at the start", w.fraction(), 0.0);
        check("watch: the sign in tells the screen", w.step(Session.SIGN_IN, Session.STARTED, 10));
        eq("watch: the sign in stays step 1", w.line(), "Syncing your watch: step 1 of 11, signing in");
        check("watch: a done step changes nothing", !w.step(Session.HELLO, Session.DONE, 20));
        check("watch: a failed step changes nothing", !w.step(Session.SIGN_IN, Session.FAILED, 20));
        int n = 1;
        for (Session.Step s : steps) {
            n++;
            w.step(s.key, Session.STARTED, 100 * n);
            check("watch: step " + n + " of 11 is " + s.key, w.line().startsWith("Syncing your watch: step " + n + " of 11, " + SyncProgress.stepName(s.key)));
        }
        eq("watch: the last step", w.line(), "Syncing your watch: step 11 of 11, heart rate");
        check("watch: the bar at the last step", Math.abs(w.fraction() - 10 / 11.0) < 1e-9);
        w.step("one too many", Session.STARTED, 5000);
        check("watch: never past the last step", w.line().startsWith("Syncing your watch: step 11 of 11, "));
        check("watch: saving tells the screen", w.saving());
        eq("watch: saving", w.line(), "Syncing your watch: saving what came");
        eq("watch: the bar full while saving", w.fraction(), 1.0);
        check("watch: the end tells the screen", w.end());
        eq("watch: no line after the end", w.line(), null);
        eq("watch: no bar after the end", w.fraction(), -1.0);
        check("watch: steps after the end are not shown", !w.step(WatchSetup.TIME, Session.STARTED, 9000) && w.line() == null);
        check("watch: saving after the end is not shown", !w.saving() && w.line() == null);

        // a new sync starts afresh; the background job's says so
        w.start(true);
        eq("watch: the background job's", w.line(), "Syncing your watch in the background: connecting");
        w.plan(WatchSetup.sync(plan, profile(false), alarm(true), 0).size(), 10000, 1000, 1000, 1000);
        w.step(Session.HELLO, Session.STARTED, 0);
        eq("watch: without the user, with the alarm: 12 steps", w.line(), "Syncing your watch in the background: step 1 of 12, greeting the watch");
        w.start(false);
        w.plan(WatchSetup.sync(plan, profile(true), alarm(false), 0).size(), 10000, 1000, 1000, 1000);
        w.step(Session.HELLO, Session.STARTED, 0);
        eq("watch: with the user and the alarm off: 12 steps", w.line(), "Syncing your watch: step 1 of 12, greeting the watch");
        w.start(false);
        w.plan(WatchSetup.sync(plan, profile(true), alarm(true), 0).size(), 10000, 1000, 1000, 1000);
        w.step(Session.HELLO, Session.STARTED, 0);
        eq("watch: with the user and the alarm on: 13 steps", w.line(), "Syncing your watch: step 1 of 13, greeting the watch");
        // a step reported before the plan (never in WatchSync, but safe): no count
        w.start(false);
        w.step(Session.HELLO, Session.STARTED, 0);
        eq("watch: no count without the plan", w.line(), "Syncing your watch: greeting the watch");
        eq("watch: no bar without the plan", w.fraction(), -1.0);
        w.end();
    }

    static List<byte[]> records(int type, long from, int count, long gap) {
        List<byte[][]> recs = new ArrayList<byte[][]>();
        for (int i = 0; i < count; i++) recs.add(new byte[][] {WatchDemo.time(from + i * gap), WatchDemo.heart(60)});
        return WatchDemo.answer(type, recs, 5);
    }

    static void dataPercent() {
        SyncProgress.Watch w = new SyncProgress.Watch();
        w.start(false);
        w.plan(3, 11000, 1000, 2000, 6000);
        w.step(Session.HELLO, Session.STARTED, 0);
        w.step(WatchSetup.CONNECT, Session.STARTED, 0);
        check("data: a message outside a data step is not shown", !w.message(records(Proto.ACTIVITY, 9000, 1, 1).get(0), 10000));
        eq("data: no percent outside a data step", w.line(), "Syncing your watch: step 2 of 4, opening the session");
        w.step(Session.ACTIVITY, Session.STARTED, 1000);
        eq("data: no percent before a record", w.line(), "Syncing your watch: step 3 of 4, activity records");
        // 20 records from 1000 to 4800 (every 200 s), oldest first, 5 to a message
        List<byte[]> msgs = records(Proto.ACTIVITY, 1000, 20, 200);
        long t = 2000;
        for (byte[] m : msgs) {
            w.message(m, t);
            t += 1000;
        }
        // newest 4800: (4800 - 1000) / (11000 - 1000) = 38 %
        eq("data: the percent from the newest record time", w.line(), "Syncing your watch: step 3 of 4, activity records (about 38 %)");
        check("data: the bar adds the step's share", Math.abs(w.fraction() - (2 + 0.38) / 4) < 1e-9);
        // an older message later (a second answer) never moves it back
        w.message(records(Proto.ACTIVITY, 1500, 1, 1).get(0), t += 1000);
        eq("data: never back", w.line(), "Syncing your watch: step 3 of 4, activity records (about 38 %)");
        // a record past now shows at most 99 %
        w.message(records(Proto.ACTIVITY, 20000, 1, 1).get(0), t += 1000);
        eq("data: at most 99 %", w.line(), "Syncing your watch: step 3 of 4, activity records (about 99 %)");
        // the next data step starts at 0, from its own start
        w.step(Session.MOVEMENT, Session.STARTED, t += 1000);
        eq("data: the next step starts without a percent", w.line(), "Syncing your watch: step 4 of 4, movement records");
        check("data: the bar never goes back", w.fraction() >= (2 + 0.99) / 4 - 1e-9);
        w.message(records(Proto.MOVEMENT, 6500, 1, 1).get(0), t += 1000);
        eq("data: from the movement's own start", w.line(), "Syncing your watch: step 4 of 4, movement records (about 50 %)");
        // the heart rate's own start; newest first (the real order is not confirmed): the first message gives the most
        w.start(false);
        w.plan(1, 11000, 1000, 2000, 6000);
        w.step(Session.HELLO, Session.STARTED, 0);
        w.step(Session.HEART, Session.STARTED, 0);
        List<byte[][]> recs = new ArrayList<byte[][]>();
        for (int i = 0; i < 10; i++) recs.add(new byte[][] {WatchDemo.time(10000 - i * 100), WatchDemo.heart(60)});
        w.message(WatchDemo.answer(Proto.HEART_RATE, recs, 10).get(0), 1000);
        eq("data: newest first jumps to its end (a known limit)", w.line(), "Syncing your watch: step 2 of 2, heart rate (about 80 %)");
        // the watch's own messages and damaged bytes do not count
        w.start(false);
        w.plan(1, 11000, 1000, 2000, 6000);
        w.step(Session.HELLO, Session.STARTED, 0);
        w.step(Session.HEART, Session.STARTED, 0);
        byte[] own = records(Proto.HEART_RATE, 10000, 1, 1).get(0).clone();
        own[1] |= 0x40;
        w.message(own, 1000);
        byte[] cut = Arrays.copyOf(records(Proto.HEART_RATE, 10000, 1, 1).get(0), 8);
        w.message(cut, 2000);
        eq("data: the watch's own message and cut bytes give no percent", w.line(), "Syncing your watch: step 2 of 2, heart rate");
        // a start at or after now gives no percent
        w.start(false);
        w.plan(1, 1000, 1000, 1000, 1000);
        w.step(Session.HELLO, Session.STARTED, 0);
        w.step(Session.HEART, Session.STARTED, 0);
        w.message(records(Proto.HEART_RATE, 999, 1, 1).get(0), 1000);
        eq("data: no percent when the start is now", w.line(), "Syncing your watch: step 2 of 2, heart rate");
        w.end();
    }

    static void throttle() {
        SyncProgress.Watch w = new SyncProgress.Watch();
        w.start(false);
        w.plan(3, 11000, 1000, 1000, 1000);
        check("throttle: a step at once", w.step(Session.HELLO, Session.STARTED, 0));
        check("throttle: the next step at once too", w.step(Session.ACTIVITY, Session.STARTED, 10));
        List<byte[]> msgs = records(Proto.ACTIVITY, 1000, 100, 50);
        check("throttle: a message 90 ms after the step waits", !w.message(msgs.get(0), 100));
        check("throttle: a message 250 ms after it is shown", w.message(msgs.get(1), 260));
        check("throttle: one 40 ms later waits", !w.message(msgs.get(2), 300));
        check("throttle: one 250 ms later is shown", w.message(msgs.get(3), 510));
        // a burst of 1000 messages over 2 s tells the screen at most 9 times (4 a second)
        int told = 0;
        for (int i = 0; i < 1000; i++) if (w.message(msgs.get(i % msgs.size()), 1000 + i * 2)) told++;
        check("throttle: at most 4 a second", told <= 9, told);
        check("throttle: but the screen hears of it", told >= 7, told);
        // a clock that went back (another boot clock) does not block it for long
        check("throttle: a clock going back still shows", w.message(msgs.get(0), 0));
        check("throttle: SHOW_MS is a quarter second", SyncProgress.SHOW_MS == 250);
        w.end();
    }

    /** No input throws: the progress can never end a session. */
    static void never() {
        SyncProgress.Watch w = new SyncProgress.Watch();
        Random rnd = new Random(478);
        boolean ok = true;
        try {
            w.message(null, 0);
            w.step(null, Session.STARTED, 0);
            w.start(false);
            w.plan(-5, -1, 5, 5, 5);
            w.step(Session.HELLO, Session.STARTED, 0);
            w.step(Session.HEART, Session.STARTED, 0);
            for (int i = 0; i < 3000; i++) {
                byte[] b = new byte[rnd.nextInt(40)];
                rnd.nextBytes(b);
                if (b.length > 0 && rnd.nextBoolean()) b[0] = 1;
                w.message(b, i);
                w.line();
                w.fraction();
            }
            w.message(new byte[0], 1);
            w.message(Frame.message(Proto.HEART_RATE, Frame.part(Proto.REC_TIME, new byte[] {1, 2})), 2);
            w.step("x", 99, 3);
            SyncProgress.ringFraction(Long.MIN_VALUE, Long.MAX_VALUE, Double.NaN);
            SyncProgress.ringFraction(Long.MAX_VALUE, 1, Double.POSITIVE_INFINITY);
            SyncProgress.ringLine(Integer.MAX_VALUE, Double.NaN, true);
            SyncProgress.about(Double.NaN);
        } catch (RuntimeException e) {
            ok = false;
            System.out.println("  " + e);
        }
        check("never: no input throws", ok);
        eq("never: the line after garbage", w.line(), "Syncing your watch: step 1 of 1, heart rate");
    }

    /** One session against WatchTest's made up watch: what it was sent, what was stored, how it ended. */
    static final class Run {
        List<byte[]> sent;
        List<byte[]> stored;
        Session.Result r;
        List<String> log;
    }

    static Run session(boolean shown, final boolean breakIt) throws Exception {
        WatchTest.FakeWatch fw = new WatchTest.FakeWatch();
        final SyncProgress.Watch w = new SyncProgress.Watch();
        final long[] clock = {0};
        Session.Plan plan = WatchTest.plan();
        plan.steps = WatchSetup.sync(plan, profile(true), alarm(true), 0);
        final WatchTest.Store store = new WatchTest.Store();
        Session.Sink sink = store;
        if (shown) {
            w.start(false);
            w.plan(plan.steps.size(), plan.now, plan.activitySince, 0, plan.heartSince);
            plan.progress = steps(w, clock);
            sink = new Session.Sink() {
                @Override
                public void received(byte[] msg) throws IOException {
                    store.received(msg);
                    clock[0] += 7;
                    w.message(breakIt && msg.length > 6 ? Arrays.copyOf(msg, 6) : msg, clock[0]);
                }

                @Override
                public void damaged(byte[] bytes, int type) throws IOException {
                    store.damaged(bytes, type);
                }
            };
        }
        WatchTest.Lines log = new WatchTest.Lines();
        Run out = new Run();
        out.r = Session.run(fw, plan, new WatchTest.Gate(), sink, log);
        out.sent = fw.sent;
        out.stored = store.got;
        out.log = log.lines;
        if (shown) {
            w.saving();
            w.end();
        }
        return out;
    }

    static boolean same(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** The session with the progress gives the same writes, stores and result as without it. */
    static void sameSession() throws Exception {
        Run plain = session(false, false), with = session(true, false), broken = session(true, true);
        check("same: the made up watch synced", plain.r.ended == null && plain.r.heartDone && plain.sent.size() > 10, plain.r.ended);
        for (Run x : new Run[] {with, broken}) {
            String n = x == with ? "with the progress" : "with garbage handed to the progress";
            check("same, " + n + ": the same bytes sent in the same order", same(plain.sent, x.sent));
            check("same, " + n + ": the same messages stored", same(plain.stored, x.stored));
            eq("same, " + n + ": the same end", x.r.ended, plain.r.ended);
            eq("same, " + n + ": the same steps done", new ArrayList<String>(x.r.done), new ArrayList<String>(plain.r.done));
            eq("same, " + n + ": the same counts", x.r.received + "/" + x.r.bytes + "/" + x.r.doneSent, plain.r.received + "/" + plain.r.bytes + "/" + plain.r.doneSent);
            eq("same, " + n + ": the same log", x.log, plain.log);
        }
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** Lines without their indent, from the line holding {@code from} to the line holding {@code to}, both included. */
    static List<String> block(String text, String from, String to) {
        List<String> out = new ArrayList<String>();
        boolean in = false;
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (!in && t.contains(from)) in = true;
            if (in) out.add(t);
            if (in && t.contains(to)) return out;
        }
        return null;
    }

    static int count(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }

    static void sources() throws IOException {
        String base = "app/src/main/java/app/openring/";
        String rm = read(base + "ble/RingManager.java");
        // the ring download's sink stores, moves the cursor and anchors as on dev 07821b9, word for word; only the two
        // display lines after "if (quiet) return true;" changed
        List<String> store = block(rm, "public boolean onBatch(List<RingEvent> events, long next, long left, int total) {", "if (firstLeft < 0 && left > 0) firstLeft = left;");
        List<String> want = Arrays.asList(
                "public boolean onBatch(List<RingEvent> events, long next, long left, int total) {",
                "try {",
                "inserted[0] += db.insertBatch(events, epochF[0], captured);",
                "if (observer != null) observer.onEvents(events);",
                "for (RingEvent e : events) minDs[0] = Math.min(minDs[0], e.ds);",
                "db.put(\"cursor\", next);",
                "if (left == 0 && !anchored && next > 0 && total > 0) {",
                "// drained up to the present: the newest event happened about now. This is",
                "// the time anchor of last resort, and it is saved even if a later step fails.",
                "db.insertPhoneAnchor(epochF[0], next - 1, System.currentTimeMillis() / 1000);",
                "anchored = true;",
                "}",
                "} catch (RuntimeException e) {",
                "log(\"storage error: \" + e);",
                "return false;",
                "}",
                "if (leftCb != null) leftCb.left(left);",
                "if (quiet) return true;",
                "if (firstLeft < 0 && left > 0) firstLeft = left;");
        eq("source: the ring sink's storing lines as on dev", store, want);
        List<String> shown = block(rm, "if (firstLeft < 0 && left > 0) firstLeft = left;", "return true;");
        eq("source: after them only the display lines", shown, Arrays.asList(
                "if (firstLeft < 0 && left > 0) firstLeft = left;",
                "// display only (issue #478): the line and the bar, from what the ring reported with this batch",
                "progress = app.openring.core.SyncProgress.ringFraction(left, firstLeft, progress);",
                "status = app.openring.core.SyncProgress.ringLine(total, progress, shownBackground);",
                "notifyListeners();",
                "return true;"));
        // the drain calls after the sink, as on dev: the same requests in the same order, the same rebase
        List<String> drain = block(rm, "RingClient.DrainResult r;", "return r.eventsSynced;");
        eq("source: the drain and its rebase as on dev", drain, Arrays.asList(
                "RingClient.DrainResult r;",
                "try {",
                "r = c.drain(cursor, sink);",
                "} catch (HistoryBatch.BatchException e) {",
                "if (cursor > 0 && e.resultCode == 0xff) {",
                "log(\"ring rejected the saved cursor; starting a new boot epoch\");",
                "epochF[0] = ++epoch;",
                "db.put(\"epoch\", epoch);",
                "db.put(\"cursor\", 0);",
                "cursor = 0;",
                "r = c.drain(0, sink);",
                "} else {",
                "throw e;",
                "}",
                "}",
                "if (r.eventsSynced == 0 && cursor > 0 && !c.cursorMarkerPresent(cursor)) {",
                "log(\"saved cursor is not on the ring any more (reboot or reset); rebasing\");",
                "epochF[0] = ++epoch;",
                "db.put(\"epoch\", epoch);",
                "db.put(\"cursor\", 0);",
                "r = c.drain(0, sink);",
                "}",
                "log(String.format(Locale.US, \"synced %d events (%d new), cursor %d\", r.eventsSynced, inserted[0], r.nextCursor));",
                "return r.eventsSynced;"));
        check("source: the background flag is only read for the line", count(rm, "shownBackground") == 4);
        check("source: showChanged only posts to the listeners", rm.contains("public void showChanged() {\n        notifyListeners();\n    }"));

        String ws = read(base + "ble/WatchSync.java");
        // every call into the shown progress sits in an apply() that shown() guards
        String[] lines = ws.split("\n");
        int calls = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (!t.matches(".*SHOWN\\.(start|step|message|plan|saving|end)\\(.*")) continue;
            calls++;
            String before = lines[i - 1].trim(), two = lines[i - 2].trim();
            check("source: WatchSync line " + (i + 1) + " is inside a guarded apply()", before.equals("public boolean apply() {") && two.equals("@Override"));
        }
        eq("source: six places tell the progress", calls, 6);
        check("source: shown() swallows any error", ws.contains("if (s.apply()) ring.showChanged();\n        } catch (RuntimeException e) {"));
        int addRaw = ws.indexOf("WatchStore.addRaw(db, session, seq[0]++, address, msg, captured);");
        int shownMsg = ws.indexOf("return SHOWN.message(msg, SystemClock.elapsedRealtime());");
        check("source: a message is stored before it is shown", addRaw > 0 && shownMsg > addRaw);
        check("source: the setup, alarm and hands keep their progress", ws.contains("plan.progress = mode == SYNC ? shownSteps(ring, progress) : mode != HANDS ? progress : new Session.Progress() {"));
        check("source: shownSteps hands on first, as the plan's progress did", ws.contains("if (also != null) also.step(key, state);\n                shown(ring, new Show() {"));
        check("source: the watch's damaged bytes are not shown", block(ws, "public void damaged(byte[] bytes, int type) throws IOException {", "}").toString().indexOf("SHOWN") < 0);
        // the progress never decides anything: its answers are only used to tell the screens
        check("source: no answer of the progress is used but to tell the screens", count(ws, "SHOWN.") == count(ws, "SHOWN.start(") + count(ws, "SHOWN.step(")
                + count(ws, "SHOWN.message(") + count(ws, "SHOWN.plan(") + count(ws, "SHOWN.saving(") + count(ws, "SHOWN.end("));
        check("source: core/SyncProgress knows no Android", !read(base + "core/SyncProgress.java").contains("import android"));
    }
}
