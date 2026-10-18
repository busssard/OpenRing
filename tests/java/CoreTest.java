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
            boolean prevSleep = i > 0 && f[i - 1] != 0 && f[i - 1] != W, nextSleep = j < f.length && f[j] != 0 && f[j] != W;
            boolean boxed = (i == 0 || f[i - 1] == 0) && (j == f.length || f[j] == 0)
                    || f[i] == W && (i == 0 || f[i - 1] == 0 || j == f.length || f[j] == 0)
                    // sleep never merges into awake (issue #28): with no sleep neighbour it may stay short
                    || f[i] != W && !prevSleep && !nextSleep;
            if (f[i] != 0 && j - i < min && !boxed) ok = false;
            i = j;
        }
        check("Calm leaves no short runs", ok);
        check("filter reduces stage changes", StageFilter.changes(f) < StageFilter.changes(busy));
        eq("stage changes count", StageFilter.changes(runs(L, 5, D, 5, 0, 2, D, 3, W, 2)), 2);
        eq("awake runs inside the night", StageFilter.wakeRuns(runs(W, 10, L, 5, W, 1, L, 5, W, 3, R, 2, W, 20)), 2);

        stageFilterKeepsSleep();

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

    /** Issue #28: the detail filter never turns sleep into awake and never adds wake ups. */
    static void stageFilterKeepsSleep() {
        int D = Stager.DEEP, L = Stager.LIGHT, R = Stager.REM, W = Stager.WAKE;
        int B = StageFilter.BALANCED, C = StageFilter.CALM;
        // case 1: a 30 s REM next to a 30 s awake joins deep, then the 30 s awake joins deep too
        int[] c1 = runs(D, 40, R, 1, W, 1, D, 40);
        eq("short REM next to awake joins deep, not awake", Arrays.toString(StageFilter.filter(c1, B)), Arrays.toString(runs(D, 82)));
        Night d1 = metricsOnly(c1, StageFilter.DETAILED), b1 = metricsOnly(c1, B);
        eq("case 1 Detailed: no wake up", d1.awakenings, 0);
        eq("case 1 Balanced: no wake up added", b1.awakenings, 0);
        check("case 1 Balanced: no awake time added", b1.awakeMin == 0 && b1.efficiency >= d1.efficiency);
        // case 2: 2 minutes of REM before a 1 minute wake up join deep, the wake up stays as it is
        int[] c2 = runs(D, 40, R, 4, W, 2, D, 40);
        eq("short REM before a wake up joins deep", Arrays.toString(StageFilter.filter(c2, B)), Arrays.toString(runs(D, 44, W, 2, D, 40)));
        Night d2 = metricsOnly(c2, StageFilter.DETAILED), b2 = metricsOnly(c2, B);
        eq("case 2 Balanced: same wake ups as Detailed", b2.awakenings, d2.awakenings);
        check("case 2 Balanced: awake minutes and efficiency as Detailed", near(b2.awakeMin, 1.0) && near(b2.awakeMin, d2.awakeMin)
                && near(b2.efficiency, d2.efficiency));
        // case 3: REM between two 1 minute wake ups has no sleep neighbour, so it stays
        int[] c3 = runs(L, 40, W, 2, R, 3, W, 2, L, 40);
        eq("sleep between two wake ups stays under Balanced", Arrays.toString(StageFilter.filter(c3, B)), Arrays.toString(c3));
        Night b3 = metricsOnly(c3, B);
        check("case 3 Balanced: 2 wake ups, 2 minutes awake", b3.awakenings == 2 && near(b3.awakeMin, 2.0));
        // Calm hides the two 1 minute wake ups (documented), after which the REM has sleep around it and joins light
        eq("Calm: short wake ups join sleep, then the REM joins light", Arrays.toString(StageFilter.filter(c3, C)), Arrays.toString(runs(L, 87)));
        // Calm keeps 2 minute wake ups; the short REM between them stays, it was sleep
        int[] c4 = runs(L, 40, W, 4, R, 3, W, 4, L, 40);
        eq("sleep between two long wake ups stays under Calm", Arrays.toString(StageFilter.filter(c4, C)), Arrays.toString(c4));
        // short sleep between awake and the night's edge or missing data stays
        int[] c5 = runs(0, 3, L, 2, W, 6, L, 40);
        eq("short sleep next to awake and missing data stays", Arrays.toString(StageFilter.filter(c5, B)), Arrays.toString(c5));

        // property test: random nights with short and long runs and missing data
        Random rnd = new Random(28);
        int notSubset = 0, moreWakes = 0, moreWakeRuns = 0, moreAwake = 0, moved = 0, balancedDiffers = 0, nights = 20000;
        for (int t = 0; t < nights; t++) {
            int len = 20 + rnd.nextInt(200);
            int[] s = new int[len];
            for (int i = 0; i < len; ) {
                int code = rnd.nextInt(12) == 0 ? 0 : 1 + rnd.nextInt(4);
                int run = 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 12);
                for (int k = 0; k < run && i < len; k++) s[i++] = code;
            }
            Night det = metricsOnly(s, StageFilter.DETAILED);
            for (int lv = B; lv <= C; lv++) {
                int[] f = StageFilter.filter(s, lv);
                for (int i = 0; i < len; i++) if (f[i] == W && s[i] != W) { notSubset++; break; }
                Night n = metricsOnly(s, lv);
                if (n.awakenings > det.awakenings) moreWakes++;
                if (StageFilter.wakeRuns(f) > StageFilter.wakeRuns(s)) moreWakeRuns++;
                if (n.awakeMin > det.awakeMin + 1e-9) moreAwake++;
                if (!(Double.isNaN(det.sleepStart) ? Double.isNaN(n.sleepStart) : n.sleepStart == det.sleepStart && n.sleepEnd == det.sleepEnd)) moved++;
                if (lv == B && n.awakenings != det.awakenings) balancedDiffers++;
            }
        }
        eq("random nights: awake in the result was awake in the input", notSubset, 0);
        eq("random nights: wake ups never above Detailed", moreWakes, 0);
        eq("random nights: awake runs of any length never above the input", moreWakeRuns, 0);
        eq("random nights: awake minutes never above Detailed", moreAwake, 0);
        eq("random nights: falling asleep and waking up do not move", moved, 0);
        eq("random nights: Balanced counts the same wake ups as Detailed", balancedDiffers, 0);
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
        clockChange();
        stageFilter();
        screenTime();
        screenAwakeGrid();
        screenApps();
        screenUnlock();
        screenScore();
        decoders();
        requests();
        auth();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /** Apps in front and apps left out of screen time (1.15). */
    static void screenApps() {
        final int ON = ScreenTime.APP_ON, OFF = ScreenTime.APP_OFF, SOFF = ScreenTime.OFF;
        String m = "com.google.android.apps.maps", c = "com.chat";
        // maps 100 to 200; chat opens twice (second screen of the same app ignored) and is closed by the screen going off
        java.util.Map<String, List<double[]>> a = ScreenTime.appSessions(
                new double[] {100, 200, 210, 220, 300, 400},
                new int[] {ON, OFF, ON, ON, SOFF, ON},
                new String[] {m, m, c, c, null, m}, 0, 500);
        check("apps: maps range", a.get(m).size() == 2 && near(a.get(m).get(0)[0], 100) && near(a.get(m).get(0)[1], 200));
        check("apps: still open runs to now", near(a.get(m).get(1)[0], 400) && near(a.get(m).get(1)[1], 500));
        check("apps: second screen of an app ignored, screen off closes", a.get(c).size() == 1 && near(a.get(c).get(0)[0], 210) && near(a.get(c).get(0)[1], 300));
        // an "off" right after the start of the read: in front since then; a late one is ignored
        java.util.Map<String, List<double[]>> lead = ScreenTime.appSessions(new double[] {60, 9000}, new int[] {OFF, OFF}, new String[] {m, c}, 10, 10000);
        check("apps: lead in from the read start", lead.get(m) != null && near(lead.get(m).get(0)[0], 10) && near(lead.get(m).get(0)[1], 60));
        check("apps: late first off ignored", lead.get(c) == null);
        java.util.Map<String, List<double[]>> boot = ScreenTime.appSessions(new double[] {10, 50}, new int[] {ON, ScreenTime.STARTUP}, new String[] {c, null}, 0, 100);
        check("apps: a restart closes apps", boot.get(c).size() == 1 && near(boot.get(c).get(0)[1], 50));
        check("apps: unknown package is skipped", ScreenTime.appSessions(new double[] {1}, new int[] {ON}, new String[] {null}, 0, 9).isEmpty());

        List<double[]> screen = new ArrayList<double[]>();
        screen.add(new double[] {0, 100});
        screen.add(new double[] {200, 300});
        List<double[]> cuts = new ArrayList<double[]>();
        cuts.add(new double[] {250, 400});
        cuts.add(new double[] {20, 30});
        cuts.add(new double[] {25, 40});
        List<double[]> left = ScreenTime.subtract(screen, cuts);
        eq("subtract: pieces", left.size(), 3);
        check("subtract: overlapping cuts merge", near(left.get(0)[1], 20) && near(left.get(1)[0], 40) && near(left.get(1)[1], 100));
        check("subtract: cut at the end", near(left.get(2)[0], 200) && near(left.get(2)[1], 250));
        check("subtract: nothing to cut", ScreenTime.subtract(screen, new ArrayList<double[]>()).size() == 2);
        List<double[]> all = new ArrayList<double[]>();
        all.add(new double[] {-10, 1000});
        check("subtract: all cut", ScreenTime.subtract(screen, all).isEmpty());
        List<double[]> touch = new ArrayList<double[]>();
        touch.add(new double[] {100, 200});
        check("subtract: touching edges keep everything", near(ScreenTime.minutes(ScreenTime.subtract(screen, touch), 0, 400), 200 / 60.0));

        java.util.Set<String> only = new java.util.HashSet<String>(java.util.Arrays.asList(m));
        eq("ranges: only the picked apps", ScreenTime.ranges(a, only).size(), 2);
        List<java.util.Map.Entry<String, Double>> per = ScreenTime.perApp(a, screen, 0, 1000);
        // maps 100..200 lies between the screen sessions (0 s), 400..500 outside; chat 210..300 is 90 s on screen
        check("per app: only time with the screen on", per.size() == 1 && per.get(0).getKey().equals(c) && near(per.get(0).getValue(), 1.5));

        check("first after", near(ScreenTime.firstAfter(screen, 50, 400), 200) && Double.isNaN(ScreenTime.firstAfter(screen, 250, 400)));
        double[] f = ScreenTime.fill(screen, 0, 400, 4);
        check("fill per step", near(f[0], 1) && near(f[1], 0) && near(f[2], 1) && near(f[3], 0));
        double[] half = ScreenTime.fill(screen, 0, 400, 8);
        check("fill: half steps", near(half[0], 1) && near(half[1], 1) && near(half[2], 0));
        check("fill: empty range", ScreenTime.fill(screen, 10, 10, 3)[0] == 0);

        eq("name: maps", ScreenTime.shortName(m), "Maps");
        eq("name: firefox", ScreenTime.shortName("org.mozilla.firefox"), "Firefox");
        eq("name: whatsapp", ScreenTime.shortName("com.whatsapp"), "Whatsapp");
        eq("name: demo", ScreenTime.shortName("demo.maps"), "Maps");
        eq("name: one word", ScreenTime.shortName("launcher"), "Launcher");
        eq("name: nothing", ScreenTime.shortName(""), "Unknown app");
    }

    /** Phone use from unlocking (1.20): the lock screen lighting up does not count. */
    static void screenUnlock() {
        final int ON = ScreenTime.ON, OFF = ScreenTime.OFF, L = ScreenTime.LOCKED, U = ScreenTime.UNLOCKED, A = ScreenTime.ALIVE;
        // locked earlier (50); a message lights the lock screen 100..110: nothing; then on 200, unlock 205, off 400
        List<double[]> s = ScreenTime.sessions(new double[] {50, 100, 110, 200, 205, 400}, new int[] {L, ON, OFF, ON, U, OFF}, -10000, 1000);
        eq("unlock: lock screen glance not counted", s.size(), 1);
        check("unlock: from unlocking to off", near(s.get(0)[0], 205) && near(s.get(0)[1], 400));
        // a phone without a lock screen never logs one: screen on counts
        List<double[]> plain = ScreenTime.sessions(new double[] {100, 110, 200, 400}, new int[] {ON, OFF, ON, OFF}, 0, 1000);
        check("no lock screen: screen on counts", plain.size() == 2 && near(plain.get(1)[0], 200));
        // locking with the screen still on ends the session (a screen saver behind the lock stays out)
        List<double[]> lock = ScreenTime.sessions(new double[] {10, 50, 60, 5000}, new int[] {U, L, A, OFF}, 0, 9000);
        check("unlock: locking ends it", lock.size() == 1 && near(lock.get(0)[0], 10) && near(lock.get(0)[1], 50));
        // screen back on within the lock delay (no lock logged): counts
        List<double[]> delay = ScreenTime.sessions(new double[] {10, 20, 22, 30}, new int[] {U, OFF, ON, OFF}, 0, 99);
        check("unlock: lock delay use counts", delay.size() == 2 && near(delay.get(1)[0], 22));
        // a phone that shows its lock screen only when the screen comes on: the glance is dropped
        List<double[]> late = ScreenTime.sessions(new double[] {10, 20, 30, 31, 35}, new int[] {U, OFF, ON, L, OFF}, 0, 99);
        check("unlock: lock screen shown at screen on is a glance", late.size() == 1 && near(late.get(0)[1], 20));
        // face unlock logged before the screen on: the session starts with the screen
        List<double[]> face = ScreenTime.sessions(new double[] {5, 100, 101, 200}, new int[] {L, U, ON, OFF}, -10000, 999);
        check("unlock: unlock while dark starts with the screen", face.size() == 1 && near(face.get(0)[0], 101));
        // an unlock while the screen is off never counts dark time
        List<double[]> dark = ScreenTime.sessions(new double[] {5, 10, 20, 500, 600}, new int[] {L, ON, OFF, U, L}, -10000, 999);
        check("unlock: no dark time", dark.isEmpty());
        // switching the lock screen off later: screen on counts again right after the last unlock
        List<double[]> none = ScreenTime.sessions(new double[] {10, 20, 30, 40}, new int[] {U, OFF, ON, OFF}, 0, 99);
        check("lock screen switched off: still counted", none.size() == 2);
        // the read starts while in use: locked soon after the start means in use since then
        List<double[]> lead = ScreenTime.sessions(new double[] {60}, new int[] {L}, 10, 99);
        check("unlock: lead in until locked", lead.size() == 1 && near(lead.get(0)[0], 10) && near(lead.get(0)[1], 60));
        List<double[]> open = ScreenTime.sessions(new double[] {70}, new int[] {U}, 0, 99);
        check("unlock: still in use runs to now", open.size() == 1 && near(open.get(0)[1], 99));
        // widen only for the display, at finds the session
        List<double[]> w = ScreenTime.widen(s, 600);
        check("widen: at least the minimum", near(w.get(0)[1], 805) && near(s.get(0)[1], 400));
        check("widen: zero keeps the list", ScreenTime.widen(s, 0) == s);
        check("at: inside and outside", ScreenTime.at(s, 300) != null && ScreenTime.at(s, 500) == null);
        // apps: another app in front closes the one whose "went to the back" is missing
        String a = "a.app", b = "b.app";
        java.util.Map<String, List<double[]>> m = ScreenTime.appSessions(new double[] {10, 50, 80}, new int[] {ScreenTime.APP_ON, ScreenTime.APP_ON, ScreenTime.APP_OFF},
                new String[] {a, b, b}, 0, 1000);
        check("apps: next app closes the previous one", near(m.get(a).get(0)[1], 50) && m.get(a).size() == 1 && near(m.get(b).get(0)[0], 50));
    }

    /** Screen score (1.15): amount against the goal, before bed and during the night. */
    static void screenScore() {
        check("score: half the goal is best", near(ScreenScore.amount(90, 180), 100));
        check("score: the goal gives 70", near(ScreenScore.amount(180, 180), 70));
        check("score: 1.5 times the goal", near(ScreenScore.amount(270, 180), 35));
        check("score: twice the goal gives 0", near(ScreenScore.amount(360, 180), 0) && near(ScreenScore.amount(999, 180), 0));
        check("score: no goal uses 3 hours", near(ScreenScore.amount(180, 0), 70));
        check("score: before bed", near(ScreenScore.bedtime(5), 100) && near(ScreenScore.bedtime(25), 50) && near(ScreenScore.bedtime(60), 0));
        check("score: night", near(ScreenScore.night(0.5), 100) && near(ScreenScore.night(20), 0) && ScreenScore.night(10) > 40 && ScreenScore.night(10) < 60);
        ScreenScore.Result r = ScreenScore.of(180, 180, 25, 0);
        eq("score: weighted", r.value, (int) Math.round(0.5 * 70 + 0.3 * 50 + 0.2 * 100));
        ScreenScore.Result n = ScreenScore.of(90, 180, Double.NaN, Double.NaN);
        check("score: without a night only the amount", n.value == 100 && Double.isNaN(n.bedtime) && Double.isNaN(n.night));
        check("score: worst day", ScreenScore.of(600, 120, 60, 60).value == 0);
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

    /** The awake rule before issue #41: a step is awake when the screen was on for 15 s of it. */
    static boolean[] oldAwake(int n, double start, double end, List<double[]> sessions) {
        boolean[] out = new boolean[n];
        double step = (end - start) / n;
        for (int i = 0; i < n; i++) {
            double a = start + i * step, b = a + step, on = 0;
            for (double[] x : sessions) on += Math.max(0, Math.min(x[1], b) - Math.max(x[0], a));
            out[i] = on >= ScreenTime.AWAKE_MIN_S * step / 30.0;
        }
        return out;
    }

    static int awakeCount(int[] st) {
        int c = 0;
        for (int x : st) if (x == 4) c++;
        return c;
    }

    /** Issue #41: a short phone check counts wherever it falls on the 30 second grid. */
    static void screenAwakeGrid() {
        // one check slid over every start time in a step (tenths of a second, the borders included)
        double[] lengths = {15, 20, 25, 29, 29.9};
        boolean always = true, atMostTwo = true, mostIsMarked = true;
        String miss = "";
        for (double len : lengths) {
            for (int k = 0; k <= 300; k++) {
                double s0 = 600 + k / 10.0;
                int[] st = new int[120];
                Arrays.fill(st, 2);
                List<double[]> one = new ArrayList<double[]>();
                one.add(new double[] {s0, s0 + len});
                int r = ScreenTime.markAwake(st, 0, 3600, one);
                if (awakeCount(st) < 1 || r != awakeCount(st)) {
                    always = false;
                    miss = " (missed " + len + " s at " + s0 + ")";
                }
                atMostTwo &= awakeCount(st) <= 2;
                // the step holding most of the check is marked (the earlier one on a tie)
                int first = (int) Math.floor(s0 / 30);
                double inFirst = Math.min(s0 + len, (first + 1) * 30) - s0;
                int expect = inFirst >= len - inFirst - 1e-9 ? first : first + 1;
                mostIsMarked &= st[expect] == 4;
            }
        }
        check("screen #41: every check of 15 s or more marks a step, wherever it starts" + miss, always);
        check("screen #41: a short check marks at most two steps", atMostTwo);
        check("screen #41: the step holding most of the check is marked", mostIsMarked);

        // the cases from the issue
        int[] a = new int[120];
        List<double[]> s = new ArrayList<double[]>();
        s.add(new double[] {617.5, 642.5});
        eq("screen #41: a 25 s check split half and half marks one step", ScreenTime.markAwake(a, 0, 3600, s), 1);
        eq("screen #41: a tie goes to the earlier step", a[20], 4);
        int[] b = new int[120];
        s.clear();
        s.add(new double[] {22, 37});
        eq("screen #41: a 15 s check from 22 s to 37 s marks one step", ScreenTime.markAwake(b, 0, 3600, s), 1);
        eq("screen #41: the step with 8 of its 15 s", b[0], 4);

        // a 14 s check never marks a step
        boolean never = true;
        for (int k = 0; k <= 300; k++) {
            double s0 = 600 + k / 10.0;
            int[] st = new int[120];
            List<double[]> one = new ArrayList<double[]>();
            one.add(new double[] {s0, s0 + 14});
            never &= ScreenTime.markAwake(st, 0, 3600, one) == 0 && awakeCount(st) == 0;
        }
        check("screen #41: a 14 s check never marks a step", never);

        // longer sessions behave exactly as before
        boolean same = true;
        for (double len = 30; len <= 600; len += 7.3) {
            for (int k = 0; k <= 30; k++) {
                double s0 = 900 + k;
                int[] st = new int[120];
                List<double[]> one = new ArrayList<double[]>();
                one.add(new double[] {s0, s0 + len});
                ScreenTime.markAwake(st, 0, 3600, one);
                boolean[] old = oldAwake(120, 0, 3600, one);
                for (int i = 0; i < 120; i++) same &= (st[i] == 4) == old[i];
            }
        }
        check("screen #41: sessions of 30 s or more mark the same steps as before", same);

        // two short checks: in one step together 15 s still count, on both sides of a border not
        int[] two = new int[10];
        List<double[]> t = new ArrayList<double[]>();
        t.add(new double[] {32, 42});
        t.add(new double[] {45, 55});
        eq("screen #41: two 10 s checks in one step still count", ScreenTime.markAwake(two, 0, 300, t), 1);
        eq("screen #41: two 10 s checks in one step mark it", two[1], 4);
        int[] apart = new int[10];
        t.clear();
        t.add(new double[] {45, 55});
        t.add(new double[] {62, 72});
        eq("screen #41: 10 s checks on both sides of a border do not count", ScreenTime.markAwake(apart, 0, 300, t), 0);
        // a short check over a border, one of its steps already marked with another check: nothing extra
        int[] next = new int[10];
        t.clear();
        t.add(new double[] {50, 70}); // 10 s in step 1, 10 s in step 2
        t.add(new double[] {72, 78}); // step 2 now has 16 s
        eq("screen #41: a check with a step already marked adds no step", ScreenTime.markAwake(next, 0, 300, t), 1);
        eq("screen #41: that step is the one marked", next[2], 4);
        // the part outside the window does not count
        int[] edge = new int[10];
        t.clear();
        t.add(new double[] {-10, 10});
        eq("screen #41: only 10 s inside the window, nothing marked", ScreenTime.markAwake(edge, 0, 300, t), 0);
        t.clear();
        t.add(new double[] {-10, 16});
        eq("screen #41: 16 s inside the window marks the first step", ScreenTime.markAwake(edge, 0, 300, t), 1);

        // random nights: every step the old rule marked is still marked, extra steps only from
        // checks under 30 s with 15 s inside the window, at most one each
        Random rnd = new Random(41);
        boolean superset = true, fewExtra = true;
        for (int night = 0; night < 3000; night++) {
            int n = 60 + rnd.nextInt(60);
            double start = rnd.nextInt(1000), end = start + n * 30;
            List<double[]> ss = new ArrayList<double[]>();
            double cur = start - 40;
            int shortOnes = 0;
            while (cur < end) {
                cur += 1 + rnd.nextDouble() * 200;
                double len = rnd.nextInt(4) == 0 ? 30 + rnd.nextDouble() * 300 : rnd.nextDouble() * 32;
                ss.add(new double[] {cur, cur + len});
                double in = Math.min(cur + len, end) - Math.max(cur, start);
                if (len < 30 && in >= 15) shortOnes++;
                cur += len;
            }
            int[] st = new int[n];
            Arrays.fill(st, 1);
            ScreenTime.markAwake(st, start, end, ss);
            boolean[] old = oldAwake(n, start, end, ss);
            int extra = 0;
            for (int i = 0; i < n; i++) {
                if (old[i] && st[i] != 4) superset = false;
                if (!old[i] && st[i] == 4) extra++;
            }
            fewExtra &= extra <= shortOnes;
        }
        check("screen #41: random nights keep every step the old rule marked", superset);
        check("screen #41: extra steps only from short checks, at most one each", fewExtra);
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
    static double zurich(String local) {
        return java.time.LocalDateTime.parse(local).atZone(java.time.ZoneId.of("Europe/Zurich")).toEpochSecond();
    }

    static void clockChange() {
        // issue #22: daylight saving inside a night
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        ClockChange.Change back = ClockChange.find(z, zurich("2026-10-24T22:30:00"), zurich("2026-10-25T07:00:00"));
        check("clocks back found", back != null);
        eq("clocks back at 01:00 UTC", (long) back.at, java.time.Instant.parse("2026-10-25T01:00:00Z").getEpochSecond());
        eq("clocks back is minus 60", back.minutes, -60);
        eq("clocks back local times", back.from + " to " + back.to, "03:00 to 02:00");
        ClockChange.Change fwd = ClockChange.find(z, zurich("2026-03-28T23:00:00"), zurich("2026-03-29T07:00:00"));
        check("clocks forward found", fwd != null);
        eq("clocks forward at 01:00 UTC", (long) fwd.at, java.time.Instant.parse("2026-03-29T01:00:00Z").getEpochSecond());
        eq("clocks forward is plus 60", fwd.minutes, 60);
        eq("clocks forward local times", fwd.from + " to " + fwd.to, "02:00 to 03:00");
        check("normal night has no change", ClockChange.find(z, zurich("2026-10-20T22:30:00"), zurich("2026-10-21T07:00:00")) == null);
        check("UTC has no change", ClockChange.find(java.time.ZoneOffset.UTC, 1790000000, 1790030000) == null);
        double sw = java.time.Instant.parse("2026-10-25T01:00:00Z").getEpochSecond();
        check("night ending at the switch has none", ClockChange.find(z, sw - 8 * 3600, sw) == null);
        check("night starting at the switch has it", ClockChange.find(z, sw, sw + 3600) != null);
        check("bad window has none", ClockChange.find(z, sw, sw) == null && ClockChange.find(null, 0, 1) == null
                && ClockChange.find(z, Double.NaN, sw) == null);
        java.time.ZoneId lh = java.time.ZoneId.of("Australia/Lord_Howe");
        ClockChange.Change half = ClockChange.find(lh, java.time.LocalDateTime.parse("2026-04-04T22:00:00").atZone(lh).toEpochSecond(),
                java.time.LocalDateTime.parse("2026-04-05T07:00:00").atZone(lh).toEpochSecond());
        eq("Lord Howe goes back 30 minutes", half == null ? 0 : half.minutes, -30);
        eq("text back", ClockChange.text(-60, "03:00", "02:00"), "The clocks went back one hour that night (03:00 became 02:00). "
                + "Sleep times count the real hours, so you had one hour more in bed than the clock shows.");
        eq("text forward", ClockChange.text(60, "02:00", "03:00"), "The clocks went forward one hour that night (02:00 became 03:00). "
                + "Sleep times count the real hours, so the night was one hour shorter than the clock shows.");
        check("text 30 minutes", ClockChange.text(-30, "02:00", "01:30").startsWith("The clocks went back 30 minutes that night (02:00 became 01:30)"));
        check("text two hours", ClockChange.text(120, "02:00", "04:00").contains("forward 2 hours"));
        check("text without stored times", ClockChange.text(-60, "", null).startsWith("The clocks went back one hour that night. Sleep"));
    }
}
