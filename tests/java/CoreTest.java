import app.openring.core.*;
import app.openring.core.analysis.*;

import java.io.IOException;
import java.util.*;

/**
 * Plain JVM tests for the protocol and analysis core. Vectors are the ones open_oura uses in its
 * Rust tests (captured from real rings). Run with tests/run_tests.sh.
 */
public class CoreTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object a, Object b) {
        boolean ok = a == null ? b == null : a.equals(b);
        if (!ok) System.out.println("  expected " + b + " got " + a);
        check(name, ok);
    }

    /** Scripted transport: each request hex maps to response frames. */
    static class Mock implements RingClient.Link {
        final Map<String, List<byte[]>> script = new HashMap<String, List<byte[]>>();
        final List<String> writes = new ArrayList<String>();
        final Deque<byte[]> inbox = new ArrayDeque<byte[]>();

        void on(String req, String... frames) {
            List<byte[]> l = new ArrayList<byte[]>();
            for (String f : frames) l.add(Hex.decode(f));
            script.put(req, l);
        }

        public void write(byte[] data) {
            String h = Hex.encode(data);
            writes.add(h);
            List<byte[]> r = script.get(h);
            if (r != null) inbox.addAll(r);
        }

        public byte[] poll(long timeoutMs) {
            return inbox.poll();
        }

        public void clearInbox() {
            inbox.clear();
        }
    }

    /** Builds a stage array from (code, epochs) pairs. */
    static int[] runs(int... pairs) {
        int n = 0;
        for (int i = 1; i < pairs.length; i += 2) n += pairs[i];
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < pairs.length; i += 2) for (int j = 0; j < pairs[i + 1]; j++) out[k++] = pairs[i];
        return out;
    }

    static void stageFilter() {
        int D = Stager.DEEP, L = Stager.LIGHT, R = Stager.REM, W = Stager.WAKE;
        int B = StageFilter.BALANCED, C = StageFilter.CALM;
        // short REM blips inside light merge into light
        int[] remBlips = runs(L, 40, R, 3, L, 40, R, 4, L, 40);
        eq("REM blips merge into light", Arrays.toString(StageFilter.filter(remBlips, B)), Arrays.toString(runs(L, 127)));
        // a 2 minute awakening survives Balanced and Calm; 90 seconds survives Balanced only
        int[] wake2 = runs(L, 40, W, 4, L, 40);
        eq("2 minute awakening survives Balanced", Arrays.toString(StageFilter.filter(wake2, B)), Arrays.toString(wake2));
        eq("2 minute awakening survives Calm", Arrays.toString(StageFilter.filter(wake2, C)), Arrays.toString(wake2));
        int[] wake90 = runs(L, 40, W, 3, L, 40);
        eq("90 s awakening survives Balanced", Arrays.toString(StageFilter.filter(wake90, B)), Arrays.toString(wake90));
        eq("90 s awakening merges under Calm", Arrays.toString(StageFilter.filter(wake90, C)), Arrays.toString(runs(L, 83)));
        // a 30 s awakening merges under Balanced, into the neighbour closest in depth (REM before light)
        int[] wake30 = runs(L, 40, W, 1, R, 40);
        eq("30 s awakening merges under Balanced", Arrays.toString(StageFilter.filter(wake30, B)), Arrays.toString(runs(L, 40, R, 41)));
        // a short awake run is kept even though it is shorter than the stage minimum
        int[] wake1min = runs(D, 30, W, 2, D, 30);
        eq("1 minute awakening kept under Balanced", Arrays.toString(StageFilter.filter(wake1min, B)), Arrays.toString(wake1min));
        // short deep between light and REM: light is closer in depth
        int[] deepBlip = runs(L, 20, D, 2, R, 20);
        eq("short deep joins light", Arrays.toString(StageFilter.filter(deepBlip, B)), Arrays.toString(runs(L, 22, R, 20)));
        // tie in depth goes to the longer neighbour
        int[] tie = runs(D, 10, L, 3, D, 30);
        eq("tie goes to the longer neighbour", Arrays.toString(StageFilter.filter(tie, B)), Arrays.toString(runs(D, 43)));
        // unknown epochs are never changed and never absorb anything
        int[] unk = runs(0, 5, R, 2, L, 30, 0, 3, L, 30);
        int[] fu = StageFilter.filter(unk, C);
        eq("unknown epochs stay", Arrays.toString(fu), Arrays.toString(runs(0, 5, L, 32, 0, 3, L, 30)));
        int[] lonely = runs(0, 5, R, 2, 0, 5);
        eq("run between unknowns stays", Arrays.toString(StageFilter.filter(lonely, C)), Arrays.toString(lonely));
        // awake at the edges of the night stays, so falling asleep and waking up do not move
        int[] edges = runs(W, 3, L, 18, W, 3);
        eq("awake at the night's edges stays under Calm", Arrays.toString(StageFilter.filter(edges, C)), Arrays.toString(edges));
        int[] edgeUnknown = runs(0, 2, W, 1, L, 30, W, 1, 0, 2);
        eq("awake next to missing data stays", Arrays.toString(StageFilter.filter(edgeUnknown, B)), Arrays.toString(edgeUnknown));
        // Detailed returns the input unchanged, every level keeps the length
        int[] busy = new int[960];
        Random rnd = new Random(3);
        for (int i = 0; i < busy.length; i++) busy[i] = rnd.nextInt(10) == 0 ? 0 : 1 + rnd.nextInt(4);
        eq("Detailed is unchanged", Arrays.toString(StageFilter.filter(busy, StageFilter.DETAILED)), Arrays.toString(busy));
        for (int lv = 0; lv < 3; lv++) {
            int[] f = StageFilter.filter(busy, lv);
            boolean sameUnknown = f.length == busy.length;
            for (int i = 0; i < busy.length && sameUnknown; i++) sameUnknown = (busy[i] == 0) == (f[i] == 0);
            check("level " + lv + " keeps length and unknown epochs", sameUnknown);
        }
        // after filtering no run of known stages is shorter than its minimum (unless boxed in by unknowns)
        int[] f = StageFilter.filter(Stager.smooth(busy, 5), C);
        boolean ok = true;
        for (int i = 0; i < f.length; ) {
            int j = i;
            while (j < f.length && f[j] == f[i]) j++;
            int min = f[i] == W ? 4 : 10;
            boolean boxed = (i == 0 || f[i - 1] == 0) && (j == f.length || f[j] == 0)
                    || f[i] == W && (i == 0 || f[i - 1] == 0 || j == f.length || f[j] == 0);
            if (f[i] != 0 && j - i < min && !boxed) ok = false;
            i = j;
        }
        check("Calm leaves no short runs", ok);
        check("filter reduces stage changes", StageFilter.changes(f) < StageFilter.changes(busy));
        eq("stage changes count", StageFilter.changes(runs(L, 5, D, 5, 0, 2, D, 3, W, 2)), 2);
        eq("awake runs inside the night", StageFilter.wakeRuns(runs(W, 10, L, 5, W, 1, L, 5, W, 3, R, 2, W, 20)), 2);

        // NightAnalyzer: metrics and score come from the filtered stages, the raw ones are kept
        int[] remFlicker = new int[8 * 120];
        Arrays.fill(remFlicker, L);
        for (int i = 100; i < 900; i += 40) remFlicker[i] = R; // 30 s REM blips
        remFlicker[500] = W;
        Night raw = metricsOnly(remFlicker, StageFilter.DETAILED);
        Night bal = metricsOnly(remFlicker, B);
        eq("raw stages kept", Arrays.toString(bal.stagesRaw), Arrays.toString(remFlicker));
        check("REM minutes come from filtered stages", raw.remMin > 0 && bal.remMin == 0);
        eq("detail level recorded", bal.stageDetail, B);
    }

    static Night metricsOnly(int[] stages, int level) {
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = 1_700_000_000;
        in.end = in.start + stages.length * 30;
        in.ringStages = stages; // taken as the ring's own hypnogram, so the stager does not run
        in.stageDetail = level;
        return NightAnalyzer.analyze(in);
    }

    static void pattern() {
        app.openring.core.analysis.SleepPattern p = new app.openring.core.analysis.SleepPattern();
        // night 1: 23:00 to 07:00 all light; night 2: 00:00 to 06:00 all deep
        int[] a = new int[8 * 120];
        java.util.Arrays.fill(a, 2);
        int[] b = new int[6 * 120];
        java.util.Arrays.fill(b, 1);
        p.add(23 * 60, a, 30);
        p.add(0, b, 30);
        int bin2am = (26 * 60 - app.openring.core.analysis.SleepPattern.ANCHOR_MIN) / app.openring.core.analysis.SleepPattern.BIN_MIN;
        int bin2330 = (23 * 60 + 30 - app.openring.core.analysis.SleepPattern.ANCHOR_MIN) / app.openring.core.analysis.SleepPattern.BIN_MIN;
        check("pattern: both nights in bed at 02:00", Math.abs(p.inBed(bin2am) - 1.0) < 1e-6);
        check("pattern: half deep, half light at 02:00", Math.abs(p.share(bin2am, 1) - 0.5) < 1e-6 && Math.abs(p.share(bin2am, 2) - 0.5) < 1e-6);
        check("pattern: one of two nights in bed at 23:30", Math.abs(p.inBed(bin2330) - 0.5) < 1e-6);
        int[] r = p.range(0.05);
        check("pattern: range from 22:00 to 08:00", app.openring.core.analysis.SleepPattern.clockMin(r[0]) == 22 * 60
                && app.openring.core.analysis.SleepPattern.clockMin(r[1]) == 8 * 60);
        p.add(13 * 60, a, 30); // an afternoon start lands after 12:00 and is ignored by the bins, but counts as a night
        check("pattern: counts nights", p.nights() == 3);
    }

    static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    /** Event decoders, byte for byte, including the ways bad input is rejected. */
    static void decoders() {
        int[][] h = EventCodec.hrv(b(58, 42, 61, 39));
        check("hrv: two 5 minute pairs", h != null && h[0][0] == 58 && h[1][0] == 42 && h[0][1] == 61 && h[1][1] == 39);
        check("hrv: odd length rejected", EventCodec.hrv(b(58, 42, 61)) == null);
        check("hrv: empty rejected", EventCodec.hrv(new byte[0]) == null);

        // ibi 1000 ms = 125 << 3, quality 1 in bits 3..4 of the second byte
        int[][] g = EventCodec.greenIbi(b(125, 1 << 3, 100, (2 << 3) | 5));
        check("green ibi: value and quality", g != null && g[0][0] == 1000 && g[1][0] == 1 && g[0][1] == 805 && g[1][1] == 2);
        check("green ibi: too short", EventCodec.greenIbi(b(1)) == null);

        int[] ia = EventCodec.ibiAmplitude(b(100, 101, 102, 103, 104, 105, 1, 0, 1, 0, 1, 0, 0, 0));
        check("ibi amplitude: 6 packed values", ia != null && ia.length == 6 && ia[0] == 801 && ia[1] == 808 && ia[2] == 817);
        check("ibi amplitude: wrong size", EventCodec.ibiAmplitude(new byte[13]) == null);

        double[] t = EventCodec.temps(b(0xca, 0x0d, 0x10, 0x0b));  // 3530, 2832 centi degrees
        check("temps: centi degrees", t != null && Math.abs(t[0] - 35.30) < 1e-9 && Math.abs(t[1] - 28.32) < 1e-9);
        check("temps: impossible value rejected", EventCodec.temps(b(0xff, 0x7f)) == null);
        check("temps: odd length rejected", EventCodec.temps(b(1, 2, 3)) == null);

        double[] met = EventCodec.activityMet(b(1, 13, 70, 0x80, 0x85));
        check("activity: MET per minute", met != null && met.length == 4 && Math.abs(met[0] - 1.3) < 1e-9
                && Math.abs(met[1] - 7.0) < 1e-9 && Math.abs(met[2] - 12.8) < 1e-9 && Math.abs(met[3] - 13.8) < 1e-9);
        check("activity: header only", EventCodec.activityMet(b(1)) == null);

        int[] sp = EventCodec.spo2(b(0, 97, 96, 0xff));
        check("spo2: samples, sentinel dropped", sp != null && sp.length == 2 && sp[0] == 97 && sp[1] == 96);
        check("spo2: only header and sentinel", EventCodec.spo2(b(0, 0xff)) == null);

        double[] r = EventCodec.spo2R(b(0, 0x20, 0x00, 5, 0x40, 0x00, 6));
        check("spo2 R: two samples", r != null && Math.abs(r[0] - 0.5) < 1e-9 && Math.abs(r[1] - 1.0) < 1e-9);
        check("spo2 R: bad length", EventCodec.spo2R(b(0, 1, 2)) == null);
        check("spo2 from R: typical value", Math.abs(EventCodec.spo2FromR(0.5) - (105.2 - 2.55 - 3.35)) < 1e-9);
        check("spo2 from R: clamped to 85..100", EventCodec.spo2FromR(5) == 85 && EventCodec.spo2FromR(0) == 100);

        int[] ph = EventCodec.sleepPhases(b(7, 0b00011011));
        check("sleep phases: 2 bit codes, MSB first", ph != null && Arrays.equals(ph, new int[] {1, 2, 3, 4}));
        check("sleep phases: header only", EventCodec.sleepPhases(b(7)) == null);

        eq("motion: seconds in the window", EventCodec.motionSeconds(b(0x35, 0, 0, 0)), 0x15);
        eq("motion: invalid flag", EventCodec.motionSeconds(b(5, 0, 0, 0, 0x40)), -1);
        eq("motion: too short", EventCodec.motionSeconds(b(5, 0)), -1);

        long[] bt = EventCodec.bedtime(b(0x10, 0, 0, 0, 0x20, 0, 0, 0));
        check("bedtime: start and end", bt != null && bt[0] == 16 && bt[1] == 32);
        check("bedtime: too short", EventCodec.bedtime(b(1, 2, 3)) == null);

        EventCodec.SleepPeriod s = EventCodec.sleepPeriod(b(116, 16, 0, 0, 112, 8, 3, 1, 0, 0x80));
        check("sleep period: HR, breath, state", s != null && Math.abs(s.avgHr - 58) < 1e-9 && Math.abs(s.hrTrend - 1) < 1e-9
                && Math.abs(s.breath - 14) < 1e-9 && s.motionCount == 3 && s.sleepState == 1 && Math.abs(s.cv - 0.5) < 1e-9);
        check("sleep period: impossible state rejected", EventCodec.sleepPeriod(b(116, 0, 0, 0, 112, 8, 3, 5, 0, 0)) == null);
        check("sleep period: impossible motion rejected", EventCodec.sleepPeriod(b(116, 0, 0, 0, 112, 8, 130, 1, 0, 0)) == null);

        int[][] a = EventCodec.aohr(b(0, 0, 2, 61, 1, 62, 0));
        check("always on HR: bpm and quality", a != null && a[0][0] == 61 && a[1][0] == 1 && a[0][1] == 62 && a[1][1] == 0);
        check("always on HR: count mismatch", EventCodec.aohr(b(0, 0, 3, 61, 1)) == null);

        byte[] anchor = TimeLine.phoneAnchorBody(1791781944L);
        eq("anchor: phone anchor unix", EventCodec.anchorUnix(EventCodec.TIME_SYNC, anchor), 1791781944L);
        check("anchor: recognised as phone", EventCodec.isPhoneAnchor(anchor));
        check("anchor: ring time sync is not a phone anchor", !EventCodec.isPhoneAnchor(b(1, 2, 3, 4)));
        eq("anchor: rtc beacon too short", EventCodec.anchorUnix(EventCodec.RTC_BEACON, b(1, 2, 3, 4)), -1L);
        eq("anchor: other tags", EventCodec.anchorUnix(EventCodec.SPO2_EVENT, anchor), -1L);

        int[] bat = EventCodec.debugBattery(b(0x24, 93, 0x10, 0x0f));
        check("battery: percent and millivolts", bat != null && bat[0] == 93 && bat[1] == 3856);
        check("battery: text is not battery", EventCodec.debugBattery("$abc".getBytes()) == null);
        int[] ui = EventCodec.userInfo(b(35, 63, 1, 168));
        check("user info: four fields", ui != null && ui[0] == 35 && ui[3] == 168);

        // every known tag has a name, and describe never throws on garbage
        int named = 0;
        for (int tag = 0x41; tag <= 0x8f; tag++) {
            if (!EventCodec.name(tag).startsWith("0x") && !EventCodec.name(tag).startsWith("tag")) named++;
            for (byte[] junk : new byte[][] {new byte[0], b(1), b(0x24, 1, 2, 3), new byte[20]}) {
                String d = EventCodec.describe(tag, junk);
                if (d == null) { check("describe returns text for 0x" + Integer.toHexString(tag), false); break; }
            }
        }
        check("names for the known tags", named >= 30);
        eq("name: hrv", EventCodec.name(EventCodec.HRV_EVENT), "hrv_event");
    }

    static String hex(byte[] v) {
        return Hex.encode(v);
    }

    /** Request bytes sent to the ring (a wrong byte here means the ring ignores or misreads us). */
    static void requests() {
        eq("firmware request", hex(Protocol.firmware()), "0803000000");
        eq("battery request", hex(Protocol.battery()), "0c00");
        eq("auth nonce request", hex(Protocol.authNonce()), "2f012b");
        byte[] enc = new byte[16];
        Arrays.fill(enc, (byte) 0xab);
        eq("authenticate frame", hex(Protocol.authenticate(enc)), "2f112d" + "abababababababababababababababab");
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) i;
        eq("set key frame", hex(Protocol.setAuthKey(key)), "2410000102030405060708090a0b0c0d0e0f");
        eq("time sync", hex(Protocol.syncTime(0x0102030405L, 4)), "1209" + "0504030201000000" + "04");
        eq("forced time sync", hex(Protocol.syncTimeForced(0x0102030405L, 4)), "120a" + "0504030201000000" + "0401");
        eq("legacy get event", hex(Protocol.getEvent(0x01020304L, 50, -1)), "1009" + "04030201" + "32" + "ffffffff");
        eq("extended get event", hex(Protocol.extGetEvent(0x0102L, 300, 0)), "2f0c" + "41" + "00" + "0201000000000000" + "2c01");
        eq("data flush", hex(Protocol.dataFlush()), "280100");
        eq("check sleep analysis", hex(Protocol.checkSleepAnalysis(true)), "280101");
        eq("feature status", hex(Protocol.featureStatus(4)), "2f022004");
        eq("feature latest", hex(Protocol.featureLatest(2)), "2f022402");
        eq("set feature mode", hex(Protocol.setFeatureMode(2, Protocol.MODE_CONNECTED_LIVE)), "2f03220203");
        eq("feature subscription", hex(Protocol.setFeatureSubscription(2, 1)), "2f03260201");
        eq("realtime on demand", hex(Protocol.realtime(Protocol.REALTIME_ON_DEMAND, 2)), "0607" + "00020000" + "0200" + "00");
        eq("realtime off", hex(Protocol.realtimeOff()), "060400000000");
        eq("notification flags", hex(Protocol.setNotification(0x3f)), "1c013f");
        eq("capabilities", hex(Protocol.capabilities(1)), "2f020101");
        eq("factory reset", hex(Protocol.factoryReset()), "1a00");
        check("auth required reply recognised", Protocol.isAuthRequired(new Packet(0x2f, b(0x2f, 0x01))));
        check("other reply is not auth required", !Protocol.isAuthRequired(new Packet(0x2f, b(0x2f, 0x00))));
    }

    /** Nonce encryption must be AES with the nonce padded to one block, as the ring expects. */
    static void auth() {
        try {
            byte[] key = Hex.parseKey("4431967d8bacc2659743142b68391d9a");
            byte[] nonce = new byte[15];
            for (int i = 0; i < 15; i++) nonce[i] = (byte) (i * 7);
            byte[] enc = Auth.encryptNonce(key, nonce);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
            byte[] plain = c.doFinal(enc);
            check("auth: one AES block", enc.length == 16);
            check("auth: decrypts to the nonce", Arrays.equals(Arrays.copyOf(plain, 15), nonce));
            check("auth: PKCS5 style padding byte", plain[15] == 1);
            byte[] k1 = Auth.newKey(), k2 = Auth.newKey();
            check("auth: new keys are random", k1.length == 16 && !Arrays.equals(k1, k2));
            check("auth: states explained", Auth.describe(Auth.WRONG_KEY).contains("rejected") && Auth.describe(0x7f).contains("0x7f"));
        } catch (Exception e) {
            check("auth: " + e, false);
        }
    }

    public static void main(String[] args) throws Exception {
        protocol();
        history();
        client();
        analysis();
        timeline();
        live();
        motion();
        pattern();
        stageFilter();
        screenTime();
        decoders();
        requests();
        auth();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /** Screen time (issue #13): sessions from the on and off log, totals, and phone use in a sleep window. */
    static void screenTime() {
        final int ON = ScreenTime.ON, OFF = ScreenTime.OFF, SD = ScreenTime.SHUTDOWN;
        // on, off; a second "on" while on is ignored; shutdown closes; still on at the end runs to now
        List<double[]> s = ScreenTime.sessions(new double[] {100, 160, 200, 230, 260, 300, 400}, new int[] {ON, OFF, ON, ON, OFF, ON, SD}, 0, 1000);
        eq("screen: sessions", s.size(), 3);
        check("screen: plain session", near(s.get(0)[0], 100) && near(s.get(0)[1], 160));
        check("screen: double on keeps one session", near(s.get(1)[0], 200) && near(s.get(1)[1], 260));
        check("screen: shutdown closes", near(s.get(2)[1], 400));
        List<double[]> open = ScreenTime.sessions(new double[] {500}, new int[] {ON}, 0, 900);
        check("screen: still on ends now", open.size() == 1 && near(open.get(0)[1], 900));
        List<double[]> lead = ScreenTime.sessions(new double[] {50, 70, 80}, new int[] {OFF, ON, OFF}, 10, 900);
        check("screen: log starting with off was on since the start", lead.size() == 2 && near(lead.get(0)[0], 10) && near(lead.get(0)[1], 50));
        List<double[]> late = ScreenTime.sessions(new double[] {10 + 3 * 3600, 20000, 20100}, new int[] {OFF, ON, OFF}, 10, 30000);
        check("screen: a first off long after the start is not a made up day long session", late.size() == 1 && near(late.get(0)[0], 20000));
        final int AL = ScreenTime.ALIVE, UP = ScreenTime.STARTUP;
        List<double[]> died = ScreenTime.sessions(new double[] {1000, 1200, 1300, 30000, 30100, 30400}, new int[] {ON, AL, AL, UP, ON, OFF}, 0, 40000);
        check("screen: phone died with the screen on, session ends at its last use", died.size() == 2 && near(died.get(0)[1], 1300)
                && near(died.get(1)[0], 30100));
        List<double[]> idle = ScreenTime.sessions(new double[] {500, 600, 700}, new int[] {AL, ON, OFF}, 0, 1000);
        check("screen: use while off does not open a session", idle.size() == 1 && near(idle.get(0)[0], 600));
        List<double[]> stray = ScreenTime.sessions(new double[] {50, 60}, new int[] {ON, OFF}, 10, 900);
        eq("screen: no made up session before a normal start", stray.size(), 1);
        eq("screen: empty log", ScreenTime.sessions(new double[0], new int[0], 0, 100).size(), 0);

        // totals: one session across a border is split, longest is cut at the borders
        List<double[]> d = new ArrayList<double[]>();
        d.add(new double[] {3000, 4200});    // 20 min across the 3600 border
        d.add(new double[] {5000, 5060});    // 1 min
        d.add(new double[] {7000, 9400});    // 40 min across 7200
        check("screen: minutes in range", near(ScreenTime.minutes(d, 3600, 7200), 10 + 1 + 3.3333333333));
        double[] bins = ScreenTime.perBin(d, new double[] {0, 3600, 7200, 10800});
        check("screen: per hour", near(bins[0], 10) && near(bins[1], 10 + 1 + 200 / 60.0) && near(bins[2], 2200 / 60.0));
        eq("screen: turned on counts starts in range", ScreenTime.turnedOn(d, 3600, 7200), 2);
        check("screen: longest cut at the border", near(ScreenTime.longest(d, 3600, 7200), 10));
        ScreenTime.Day day = ScreenTime.day(d, new double[] {0, 3600, 7200, 10800});
        check("screen: day", near(day.totalMin, 61) && day.turnedOn == 3 && near(day.longestMin, 40) && day.hourly.length == 3);

        // trimming a sleep window [10000, 40000)
        List<double[]> t = new ArrayList<double[]>();
        t.add(new double[] {9000, 10900});   // phone in hand when the "night" starts
        t.add(new double[] {11200, 11500});  // more within 10 minutes: start moves again
        t.add(new double[] {20000, 20300});  // a phone check in the middle: not an edge
        double[] w = ScreenTime.trim(10000, 40000, t);
        check("screen: start moves past phone use, repeated", near(w[0], 11500) && near(w[1], 40000));
        List<double[]> tail = new ArrayList<double[]>();
        tail.add(new double[] {39500, 41000});
        double[] w2 = ScreenTime.trim(10000, 40000, tail);
        check("screen: end moves before phone use", near(w2[0], 10000) && near(w2[1], 39500));
        List<double[]> shortLook = new ArrayList<double[]>();
        shortLook.add(new double[] {10100, 10130}); // 30 s look at the clock does not move the edge
        check("screen: short look keeps the edge", near(ScreenTime.trim(10000, 40000, shortLook)[0], 10000));
        List<double[]> couch = new ArrayList<double[]>();
        couch.add(new double[] {9000, 11000});
        couch.add(new double[] {11300, 13000});
        double[] w3 = ScreenTime.trim(10000, 13200, couch);
        check("screen: window used up by the phone", w3[1] - w3[0] < ScreenTime.MIN_WINDOW_S);
        double[] same = ScreenTime.trim(10000, 40000, new ArrayList<double[]>());
        check("screen: no phone use, no change", near(same[0], 10000) && near(same[1], 40000));

        // awake marking: 30 s steps from 0; on for 20 s of step 2, 10 s of step 4, all of 6 and 7
        int[] st = {2, 2, 2, 2, 1, 1, 3, 3, 2, 2};
        List<double[]> m = new ArrayList<double[]>();
        m.add(new double[] {65, 85});
        m.add(new double[] {130, 140});
        m.add(new double[] {180, 240});
        int changed = ScreenTime.markAwake(st, 0, 300, m);
        eq("screen: awake marking", Arrays.toString(st), "[2, 2, 4, 2, 1, 1, 4, 4, 2, 2]");
        eq("screen: marked count", changed, 3);
        int[] already = {4, 4};
        eq("screen: no phone use in range, nothing counted", ScreenTime.markAwake(already, 0, 60, m), 0);
        int[] awakeAlready = {4, 2, 2};
        eq("screen: phone use while already awake still counts", ScreenTime.markAwake(awakeAlready, 60, 150, m), 1);
    }

    static void protocol() {
        byte[] key = Hex.decode("4431967d8bacc2659743142b68391d9a");
        byte[] nonce = Hex.decode("0e2d6a0a08c99b4365f458e6e97382");
        eq("auth known answer", Hex.encode(Auth.encryptNonce(key, nonce)), "a38a8772d3acb6db5c2b516dd56987c8");
        eq("firmware request", Hex.encode(Protocol.firmware()), "0803000000");
        eq("get event", Hex.encode(Protocol.getEvent(0, 8, -1)), "10090000000008ffffffff");
        eq("ext get event", Hex.encode(Protocol.extGetEvent(123400, 0xffff, 0)), "2f0c410008e2010000000000ffff");
        eq("data flush", Hex.encode(Protocol.dataFlush()), "280100");
        eq("set feature", Hex.encode(Protocol.setFeatureMode(2, 3)), "2f03220203");
        eq("event subscribe", Hex.encode(Protocol.eventSubscribe(0x14, 0x1000)), "1803140010");
        List<Packet> many = Packet.parseMany(Hex.decode("0d0639000000360f290100"));
        eq("parse many count", many.size(), 2);
        eq("parse many tags", many.get(1).tag, 0x29);
        Packet padded = Packet.parse(new byte[] {0x25, 0x01, 0x00, 0x77});
        eq("parse padding", padded.payload.length, 1);
        int[] live = RingClient.parseLiveHr(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x00, 0x02, 0x00, 0x00, 0x59, 0x13});
        eq("live hr bpm", live[0], 70);
        eq("live hr ibi", live[1], 857);
        int[][] hrv = EventCodec.hrv(new byte[] {50, 40, 52, 44});
        eq("hrv hr", hrv[0][1], 52);
        eq("hrv rmssd", hrv[1][1], 44);
        double[] met = EventCodec.activityMet(new byte[] {0, 10, (byte) 0x81});
        check("met scale", Math.abs(met[0] - 1.0) < 1e-9 && Math.abs(met[1] - 13.0) < 1e-9);
        int[] ph = EventCodec.sleepPhases(new byte[] {0, (byte) 0x1b});
        eq("phases", Arrays.toString(ph), "[1, 2, 3, 4]");
        int[] bat = EventCodec.debugBattery(new byte[] {0x24, 95, 0x68, 0x10});
        eq("battery debug", bat[1], 4200);
        double[] temps = EventCodec.temps(new byte[] {(byte) 0x4c, 0x0d});
        check("temp", Math.abs(temps[0] - 34.04) < 1e-9);
    }

    static void history() throws Exception {
        List<Packet> legacy = Arrays.asList(Packet.parse(Hex.decode("43086400000074657374")), Packet.parse(Hex.decode("1106000000000000")));
        HistoryBatch b = HistoryBatch.decode(legacy);
        eq("legacy events", b.events.size(), 1);
        eq("legacy ts", b.events.get(0).ds, 100L);
        List<Packet> ext = Arrays.asList(Packet.parse(Hex.decode("2f09430600aa430364bbcc")), Packet.parse(Hex.decode("2f0a42010000000000000000")));
        b = HistoryBatch.decode(ext);
        eq("ext events", b.events.size(), 1);
        eq("ext ts", b.events.get(0).ds, 1L);
        eq("ext body", Hex.encode(b.events.get(0).body), "bbcc");
        try {
            HistoryBatch.decode(Arrays.asList(Packet.parse(Hex.decode("2f0a420000000000000000ff"))));
            check("result code error", false);
        } catch (HistoryBatch.BatchException e) {
            eq("result code", e.resultCode, 0xff);
        }
        try {
            HistoryBatch.decode(Arrays.asList(Packet.parse(Hex.decode("43086400000074657374"))));
            check("unterminated", false);
        } catch (HistoryBatch.BatchException e) {
            check("unterminated", true);
        }
    }

    static void client() throws Exception {
        Mock m = new Mock();
        m.on("2f012b", "2f102c0e2d6a0a08c99b4365f458e6e97382");
        m.on("2f112da38a8772d3acb6db5c2b516dd56987c8", "2f022e00");
        RingClient c = new RingClient(m).quiet(5);
        eq("auth ok", c.authenticate(Hex.decode("4431967d8bacc2659743142b68391d9a")), 0);

        m = new Mock();
        m.on("0803000000", "091202000003040301000105000cffeeddccbbaa");
        c = new RingClient(m).quiet(5);
        RingClient.DeviceInfo info = c.firmware();
        eq("fw", info.firmware, "3.4.3");
        eq("mac", info.mac, "AA:BB:CC:DD:EE:FF");

        // extended drain, then an empty confirming pass
        m = new Mock();
        m.on("280100", "290100");
        m.on("2f0c410000000000000000000010", "2f09430600aa430364bbcc2f0a42010000000000000000");
        m.on("2f0c4100c8000000000000000010", "2f0a42000000000000000000");
        c = new RingClient(m).quiet(5);
        final List<RingEvent> got = new ArrayList<RingEvent>();
        RingClient.DrainResult r = c.drain(0, new RingClient.BatchSink() {
            public boolean onBatch(List<RingEvent> events, long next, long left, int total) {
                got.addAll(events);
                return true;
            }
        });
        eq("drain synced", r.eventsSynced, 1);
        eq("drain cursor", r.nextCursor, 2L);
        boolean noLegacy = true;
        for (String w : m.writes) if (w.startsWith("10")) noLegacy = false;
        check("no legacy ack", noLegacy);

        // legacy fallback and replayed tail
        m = new Mock();
        m.on("280100", "290100");
        m.on("2f0c410000000000000000000010", "2f020041");
        m.on("100900000000ffffffffff", "430801000000746573741106000000000000");
        m.on("2f0c4100c8000000000000000010", "2f020041");
        m.on("100902000000ffffffffff", "430801000000746573741106000000000000");
        c = new RingClient(m).quiet(5);
        r = c.drain(0, new RingClient.BatchSink() {
            public boolean onBatch(List<RingEvent> events, long next, long left, int total) {
                return true;
            }
        });
        eq("legacy synced", r.eventsSynced, 1);
        eq("legacy cursor", r.nextCursor, 2L);
    }

    static void analysis() {
        // A synthetic 8 hour night: HR dips early, HRV rises late, little motion.
        double start = 1_760_000_000;
        double end = start + 8 * 3600;
        TS hr = new TS(), hrv = new TS(), motion = new TS(), state = new TS(), temp = new TS(), spo2 = new TS();
        Random rnd = new Random(7);
        for (double t = start; t < end; t += 300) {
            double f = (t - start) / (end - start);
            hr.add(t, 62 - 10 * Math.sin(Math.PI * Math.min(1, f * 1.6)) + rnd.nextGaussian());
            hrv.add(t, 45 + 25 * f + 6 * Math.sin(f * 30) + rnd.nextGaussian() * 3);
        }
        for (double t = start; t < end; t += 60) {
            double f = (t - start) / (end - start);
            double mv = (f < 0.04 || f > 0.97) ? 10 : (rnd.nextDouble() < 0.03 ? 6 : 0);
            motion.add(t, mv);
            state.add(t, (f < 0.04 || f > 0.97) ? 0 : 1);
            temp.add(t, 35.2 + 0.2 * Math.sin(f * 6) + rnd.nextGaussian() * 0.05);
            spo2.add(t, 96 + rnd.nextGaussian());
        }
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = start;
        in.end = end;
        in.hr5 = hr;
        in.hrv5 = hrv;
        in.motion = motion;
        in.sleepState = state;
        in.temp = temp;
        in.spo2 = spo2;
        Night n = NightAnalyzer.analyze(in);
        System.out.printf("  synthetic night: source=%s asleep=%.0f deep=%.0f rem=%.0f light=%.0f awake=%.0f eff=%.0f lat=%.0f lowHR=%.1f hrv=%.0f temp=%.2f spo2=%.1f%n",
            n.stageSource, n.asleepMin, n.deepMin, n.remMin, n.lightMin, n.awakeMin, n.efficiency, n.latencyMin, n.lowestHr, n.avgHrv, n.temp, n.spo2);
        eq("staged by signals", n.stageSource, "signals");
        check("asleep plausible", n.asleepMin > 400 && n.asleepMin < 480);
        check("deep found", n.deepMin > 20);
        check("rem found", n.remMin > 20);
        check("temp computed", !Double.isNaN(n.temp));
        Night.Score s = Scores.sleep(n, 0, 40);
        System.out.println("  sleep score " + s.value);
        check("sleep score range", s.value >= 60 && s.value <= 100);

        List<double[]> bed = new ArrayList<double[]>();
        bed.add(new double[] {start + 600, start + 3 * 3600});
        bed.add(new double[] {start + 3 * 3600 + 900, start + 7 * 3600});
        List<NightFinder.Period> ps = NightFinder.find(bed, new ArrayList<NightFinder.RingRun>(), state, motion);
        eq("finder merges", ps.size(), 1);
        check("finder extends", ps.get(0).end > start + 7.4 * 3600);

        Wellness.Radar radar = Wellness.radar(new double[] {0.9, 60, 30, 18}, new double[][] {
            {0.0, 0.1, -0.1, 0.05, 0.0, -0.05, 0.1, 0.0}, {50, 51, 49, 50, 52, 48, 50, 51}, {60, 62, 58, 61, 59, 63, 60, 57}, {14, 14.5, 13.8, 14.2, 14.1, 13.9, 14.3, 14}});
        eq("radar major", radar.decision, 2);
        double[] debt = Wellness.sleepDebt(new int[] {6 * 3600, 6 * 3600, 6 * 3600, 6 * 3600, 6 * 3600}, new int[] {8 * 3600, 8 * 3600, 8 * 3600, 8 * 3600, 8 * 3600});
        check("sleep debt", debt[0] > 0 && debt[1] == 1);
        eq("nightly temp short", Double.isNaN(NightAnalyzer.nightlyTemperature(new double[] {35, 35})), true);
    }

    static void timeline() {
        long now = 1_760_000_000L;
        // boot A: ds 1,000,000..1,200,000 with a ring time_sync at 1,100,000
        // boot B (reboot): ds 1,000..50,000, no ring record, phone anchor appended after the drain
        // boot C: an old restart with no time record at all
        List<long[]> rows = new ArrayList<long[]>(); // id, epoch, ds, anchor, phone
        long id = 1;
        for (long ds = 1_000_000; ds <= 1_200_000; ds += 10_000) rows.add(new long[] {id++, 1, ds, ds == 1_100_000 ? now - 86400 : -1, 0});
        for (long ds = 1_000; ds <= 50_000; ds += 1_000) rows.add(new long[] {id++, 1, ds, -1, 0});
        rows.add(new long[] {id++, 1, 50_000, now, 1}); // phone anchor for the newest event
        for (long ds = 500; ds <= 900; ds += 100) rows.add(new long[] {id++, 2, ds, -1, 0}); // new sync epoch, undated
        int n = rows.size();
        long[] ids = new long[n], ep = new long[n], ds = new long[n], an = new long[n];
        boolean[] ph = new boolean[n];
        for (int i = 0; i < n; i++) {
            long[] r = rows.get(i);
            ids[i] = r[0]; ep[i] = r[1]; ds[i] = r[2]; an[i] = r[3]; ph[i] = r[4] == 1;
        }
        List<TimeLine.Segment> segs = TimeLine.build(ids, ep, ds, an, ph, now);
        eq("segments", segs.size(), 3);
        eq("boot A anchors", segs.get(0).anchors.size(), 1);
        eq("boot B gets phone anchor", segs.get(1).anchors.size(), 1);
        eq("boot C undated", segs.get(2).anchors.size(), 0);
        check("boot A dated", Math.abs(TimeLine.unixAt(segs.get(0), 1_200_000) - (now - 86400 + 10_000)) < 1e-6);
        check("boot B dated", Math.abs(TimeLine.unixAt(segs.get(1), 1_000) - (now - 4900)) < 1e-6);
        check("boot C NaN", Double.isNaN(TimeLine.unixAt(segs.get(2), 600)));
        // a future or ancient anchor is ignored
        boolean[] noPhone = new boolean[1];
        List<TimeLine.Segment> bad = TimeLine.build(new long[] {1}, new long[] {1}, new long[] {5}, new long[] {now + 86400}, noPhone, now);
        eq("future anchor ignored", bad.get(0).anchors.size(), 0);
        byte[] body = TimeLine.phoneAnchorBody(now);
        check("phone body", EventCodec.isPhoneAnchor(body) && EventCodec.anchorUnix(EventCodec.TIME_SYNC, body) == now);
    }

    static void live() {
        // 2f 08 28 02 01 01 0000 5913 -> feature 2, status measuring, ibi 857 valid
        RingClient.LiveIndication li = RingClient.parseLive(Packet.parse(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x01, 0x01, 0x00, 0x00, 0x59, 0x13}));
        eq("live bpm", li.bpm, 70);
        eq("live status", li.status, 1);
        RingClient.LiveIndication searching = RingClient.parseLive(Packet.parse(new byte[] {0x2f, 0x04, 0x28, 0x02, 0x02, 0x01}));
        eq("live searching", searching.bpm, -1);
        eq("live searching status", RingClient.statusText(searching.status), "searching for your pulse");
        // two packets in one notification
        List<Packet> two = Packet.parseMany(Hex.decode("2f0828020101000059132f082802010100005013"));
        eq("concatenated live", RingClient.parseLive(two.get(1)).ibi, 0x350);
    }

    static void motion() {
        // open_oura's captured ACM layout: 33 0c 32 01 | 1,2,3 | 4,5,6
        List<int[]> f = Tilt.parseFrame(new byte[] {0x33, 0x0c, 0x32, 0x01, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0});
        eq("acm samples", f.size(), 2);
        eq("acm second", Arrays.toString(f.get(1)), "[4, 5, 6]");
        Tilt t = new Tilt();
        t.alpha = 1;
        t.feed(0, 1000, 0); // neutral: gravity along y
        t.startCalibration();
        t.calibrateSample();
        t.finishCalibration();
        double[] s0 = t.stick();
        check("neutral is centred", Math.abs(s0[0]) < 1e-9 && Math.abs(s0[1]) < 1e-9);
        // tilt 20 degrees about z: gravity gains an x component
        t.feed((int) (1000 * Math.sin(Math.toRadians(20))), (int) (1000 * Math.cos(Math.toRadians(20))), 0);
        double[] s1 = t.stick();
        check("tilt moves one axis", Math.abs(Math.abs(s1[0]) + Math.abs(s1[1]) - (20 - 4) / 25.0) < 0.02);
        t.feed(0, 1000, 0);
        t.feed((int) (1000 * Math.sin(Math.toRadians(60))), (int) (1000 * Math.cos(Math.toRadians(60))), 0);
        double[] s2 = t.stick();
        check("full deflection clamps", Math.abs(s2[0]) == 1 || Math.abs(s2[1]) == 1);
        app.openring.core.analysis.LiveHrv h = new app.openring.core.analysis.LiveHrv(30);
        int[] ib = {800, 820, 790, 810, 805, 830, 795, 815, 800, 1600, 810, 790};
        for (int x : ib) h.add(x);
        double r = h.rmssd();
        check("live rmssd ignores the doubled beat", r > 10 && r < 30);
    }
}
