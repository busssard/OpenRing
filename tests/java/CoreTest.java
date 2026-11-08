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

    /** {@link #check}, printing {@code detail} when it fails. */
    static void check(String name, boolean ok, Object detail) {
        if (!ok) System.out.println("  detail: " + detail);
        check(name, ok);
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
        check("filter reduces stage changes", StageFilter.changes(f) < StageFilter.changes(Stager.smooth(busy, 5)));
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

    /** Issue #70: the Settings preview, the Sleep page and the night timeline count wake ups the same way. */
    static void wakeUps() {
        int L = Stager.LIGHT, W = Stager.WAKE;
        int B = StageFilter.BALANCED, C = StageFilter.CALM;
        // the issue's example: two 30 s pieces and one 1.5 minute wake up
        int[] ex = runs(L, 20, W, 1, L, 20, W, 1, L, 20, W, 3, L, 20);
        eq("example Detailed: 1 wake up", WakeUps.count(ex, 30), 1);
        eq("example Detailed: 2 pieces under a minute", WakeUps.shortOnes(ex, 30), 2);
        eq("example Detailed: wake up listed at its steps", Arrays.toString(WakeUps.list(ex, 30).get(0)), "[62, 65]");
        eq("example Balanced: 1 wake up, no short pieces", WakeUps.count(StageFilter.filter(ex, B), 30) * 10
                + WakeUps.shortOnes(StageFilter.filter(ex, B), 30), 10);
        eq("example Calm: the 1.5 minute wake up is folded", WakeUps.count(StageFilter.filter(ex, C), 30)
                + WakeUps.shortOnes(StageFilter.filter(ex, C), 30), 0);
        for (int lv = 0; lv < 3; lv++) {
            eq("example level " + lv + ": preview count equals the night's wake ups",
                    WakeUps.count(StageFilter.filter(ex, lv), 30), metricsOnly(ex, lv).awakenings);
        }
        // awake before falling asleep and after waking up is not a wake up, nor a short piece
        int[] edges = runs(W, 10, L, 20, W, 1, L, 20, W, 10);
        eq("edges are not wake ups", WakeUps.count(edges, 30), 0);
        eq("awake at the edges is not a short piece either, only the 30 s inside counts", WakeUps.shortOnes(edges, 30), 1);
        eq("no sleep, no wake ups", WakeUps.count(runs(W, 10, 0, 5), 30) + WakeUps.shortOnes(runs(W, 10), 30), 0);
        eq("empty night", WakeUps.count(new int[0], 30), 0);
        // missing data splits awake runs; each part is judged on its own
        eq("missing data inside awake", WakeUps.count(runs(L, 5, W, 1, 0, 2, W, 2, L, 5), 30), 1);
        // other step lengths: a minute is 1 step of 60 s, 4 steps of 15 s; rounding in the step does not add one
        eq("60 s steps: one step is a wake up", WakeUps.count(runs(L, 5, W, 1, L, 5), 60), 1);
        eq("15 s steps: 3 steps are not", WakeUps.count(runs(L, 5, W, 3, L, 5), 15), 0);
        eq("15 s steps: 4 steps are", WakeUps.count(runs(L, 5, W, 4, L, 5), 15), 1);
        eq("a step a little under 30 s still needs 2", WakeUps.minSteps(29.99999999), 2);
        eq("a step a little over 30 s still needs 2", WakeUps.minSteps(30.00000001), 2);
        eq("odd step length", WakeUps.minSteps(25), 3);
        eq("no step length", WakeUps.minSteps(0), 1);

        // shown times (display only): clock times and minutes come from the same whole minutes
        double t0 = 1_700_000_000 - 1_700_000_000 % 60; // a whole minute
        eq("rounds down under half a minute", WakeUps.roundMin(t0 + 29), t0);
        eq("rounds up from half a minute", WakeUps.roundMin(t0 + 30), t0 + 60);
        // the review's demo case: 23:41:40 to 23:44:10 showed "Awake for 4 min, back asleep 23:44" after "23:41"
        double a = t0 + 100, b = t0 + 250;
        eq("minutes match the rounded clocks", WakeUps.shownMinutes(a, b), (long) ((WakeUps.roundMin(b) - WakeUps.roundMin(a)) / 60));
        eq("demo case shows 2 min (23:42 to 23:44)", WakeUps.shownMinutes(a, b), 2L);
        eq("a one minute wake up never shows 0 min", WakeUps.shownMinutes(t0 + 29, t0 + 89), 1L);
        eq("under a minute may show 0", WakeUps.shownMinutes(t0 + 31, t0 + 89), 0L);
        Random rr = new Random(83);
        int unordered = 0, mismatch = 0;
        for (int k = 0; k < 20000; k++) {
            double x = t0 + rr.nextDouble() * 86400, y = x + rr.nextDouble() * 7200;
            if (WakeUps.roundMin(y) < WakeUps.roundMin(x)) unordered++;
            long m = WakeUps.shownMinutes(x, y), diff = Math.round((WakeUps.roundMin(y) - WakeUps.roundMin(x)) / 60);
            if (m != diff || y - x >= 60 && m < 1) mismatch++;
        }
        eq("random spans: rounded times keep their order", unordered, 0);
        eq("random spans: shown minutes equal the clock difference", mismatch, 0);
        // the level's rule in the texts: only Calm folds wake ups of a minute or more
        eq("Detailed adds nothing", WakeUps.levelNote(StageFilter.DETAILED), null);
        eq("Balanced adds nothing", WakeUps.levelNote(B), null);
        eq("Calm names its rule and what always stays (#30)", WakeUps.levelNote(C), "Calm stage detail folds being awake under 2 minutes into sleep, "
                + "except phone use, wake ups your ring reports itself and being awake next to time without ring data.");
        eq("shortest kept: Balanced 1 min, Calm 2 min", WakeUps.shortestKeptMin(B) * 10 + WakeUps.shortestKeptMin(C), 12);
        // issue #128: the line under a wake up count names the level's rule
        eq("rule: Detailed", WakeUps.rule(StageFilter.DETAILED), "a minute or more");
        eq("rule: Balanced", WakeUps.rule(B), "a minute or more");
        eq("rule: Calm, with the wake ups it keeps from a minute (#30)", WakeUps.rule(C), "2 min or more with Calm (1 min on the phone or next to missing ring data)");
        eq("average line: Calm", WakeUps.averageLine(C), "Wake ups of 2 min or more with Calm (1 min on the phone or next to missing ring data)");
        eq("average line: Balanced", WakeUps.averageLine(B), "Wake ups of a minute or more");
        eq("average line: mixed levels still name the rule", WakeUps.averageLine(-1),
                "Wake ups of a minute or more (2 min with Calm, 1 min on the phone or next to missing ring data), each night at its own detail level");
        eq("rule: out of range levels are clamped", WakeUps.rule(-3) + " / " + WakeUps.rule(9), "a minute or more / 2 min or more with Calm (1 min on the phone or next to missing ring data)");

        // random nights: for every level, preview count = night count = timeline rows, and the old rule agrees
        Random rnd = new Random(70);
        int previewDiffers = 0, listDiffers = 0, oldRuleDiffers = 0, partsMissing = 0, nights = 20000;
        for (int t = 0; t < nights; t++) {
            int len = 20 + rnd.nextInt(200);
            int[] s = new int[len];
            for (int i = 0; i < len; ) {
                int code = rnd.nextInt(12) == 0 ? 0 : 1 + rnd.nextInt(4);
                int run = 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 12);
                for (int k = 0; k < run && i < len; k++) s[i++] = code;
            }
            for (int lv = 0; lv < 3; lv++) {
                Night n = metricsOnly(s, lv);
                // what Settings draws: the stored raw stages with this level
                int[] f = StageFilter.filter(n.stagesRaw, lv);
                if (WakeUps.count(f, 30) != n.awakenings) previewDiffers++;
                if (WakeUps.list(n.stages, 30).size() != n.awakenings) listDiffers++;
                if (oldRule(f) != n.awakenings) oldRuleDiffers++;
                if (WakeUps.count(f, 30) + WakeUps.shortOnes(f, 30) != StageFilter.wakeRuns(f)) partsMissing++;
            }
        }
        eq("random nights: preview count equals the night's wake ups at every level", previewDiffers, 0);
        eq("random nights: timeline lists exactly the counted wake ups", listDiffers, 0);
        eq("random nights: same count as the rule before", oldRuleDiffers, 0);
        eq("random nights: wake ups and short pieces are all awake runs", partsMissing, 0);
    }

    /** Issue #30: the stage detail preview is the night that saving produces, also with phone use. */
    static void previewSameAsSaved() {
        int L = Stager.LIGHT, W = Stager.WAKE;
        int B = StageFilter.BALANCED, C = StageFilter.CALM;
        // the issue's case: an hour of light sleep with a 90 second phone check, Calm
        int[] light = runs(L, 120);
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = 1_700_000_000;
        in.end = in.start + 3600;
        in.ringStages = light;
        in.stageDetail = C;
        in.screen.add(new double[] {in.start + 1800, in.start + 1890});
        Night n = NightAnalyzer.analyze(in);
        eq("90 s on the phone: raw stages have it awake", WakeUps.count(n.stagesRaw, 30), 1);
        eq("90 s on the phone: the saved Calm night keeps the wake up", n.awakenings, 1);
        eq("90 s on the phone: the old preview path (filter only) lost it", WakeUps.count(StageFilter.filter(n.stagesRaw, C), 30), 0);
        int[] raw = n.stagesRaw.clone();
        int[] pv = NightAnalyzer.detailStages(n.stagesRaw, C, in.start, in.end, in.screen, n.ringAwake);
        check("90 s on the phone: the preview equals the saved night", Arrays.equals(pv, n.stages));
        eq("90 s on the phone: the preview counts the wake up", WakeUps.count(pv, 30), 1);
        check("the preview does not change the raw stages", Arrays.equals(raw, n.stagesRaw));
        // Balanced folds 30 s of awake between sleep, but not a 25 s phone check
        NightAnalyzer.Input inB = new NightAnalyzer.Input();
        inB.start = in.start;
        inB.end = in.end;
        inB.ringStages = light;
        inB.stageDetail = B;
        inB.screen.add(new double[] {in.start + 1800, in.start + 1825});
        Night nb = NightAnalyzer.analyze(inB);
        int[] pb = NightAnalyzer.detailStages(nb.stagesRaw, B, inB.start, inB.end, inB.screen, nb.ringAwake);
        check("25 s on the phone, Balanced: the step stays awake", pb[60] == W && nb.stages[60] == W);
        eq("25 s on the phone, Balanced: a moment under a minute, not a wake up", WakeUps.count(pb, 30) * 10 + WakeUps.shortOnes(pb, 30), 1);
        check("25 s on the phone, Balanced: the preview equals the saved night", Arrays.equals(pb, nb.stages));
        check("Balanced without phone use would fold it", StageFilter.filter(nb.stagesRaw, B)[60] == L);
        // a short wake up the ring reports itself (issue #42) stays too, and is kept with the night
        NightAnalyzer.Input inR = new NightAnalyzer.Input();
        inR.start = in.start;
        inR.end = in.end;
        inR.ringStages = light;
        inR.stageDetail = C;
        inR.ringAwake.add(new double[] {in.start + 1800, in.start + 1860});
        inR.ringAwake.add(new double[] {in.start - 900, in.start - 600}); // outside the window: not kept
        Night nr = NightAnalyzer.analyze(inR);
        eq("ring's own wake up: only the one inside the window is kept", nr.ringAwake.size(), 1);
        eq("ring's own wake up: the saved Calm night counts it", nr.awakenings, 1);
        int[] pr = NightAnalyzer.detailStages(nr.stagesRaw, C, inR.start, inR.end, null, nr.ringAwake);
        check("ring's own wake up: the preview equals the saved night", Arrays.equals(pr, nr.stages));
        eq("ring's own wake up: a night stored without it (older version) folds it in the preview",
                WakeUps.count(NightAnalyzer.detailStages(nr.stagesRaw, C, inR.start, inR.end, null, new ArrayList<double[]>()), 30), 0);
        // awake next to missing data stays at Calm (StageFilter), the texts name it: light 40, awake 3, unknown 4, light 40
        Night nm = metricsOnly(runs(L, 40, W, 3, 0, 4, L, 40), C);
        eq("awake next to missing data: Calm counts 1.5 min as a wake up", nm.awakenings, 1);

        // the phone use the analysis takes: overlapping the window, at most 4 hours
        List<double[]> all = new ArrayList<double[]>();
        double s0 = 1_700_000_000, e0 = s0 + 8 * 3600;
        all.add(new double[] {s0 - 600, s0});                                  // ends as the window starts: out
        all.add(new double[] {s0 - 600, s0 + 60});                             // overlaps the start: in
        all.add(new double[] {s0 + 3600, s0 + 3600 + ScreenTime.MAX_NIGHT_SESSION_S});      // exactly 4 hours: in
        all.add(new double[] {s0 + 3600, s0 + 3600 + ScreenTime.MAX_NIGHT_SESSION_S + 1});  // longer: out
        all.add(new double[] {e0, e0 + 60});                                   // starts as it ends: out
        List<double[]> fn = ScreenTime.forNight(all, s0, e0);
        eq("phone use for a night: overlapping, up to 4 hours", fn.size() == 2 && fn.get(0) == all.get(1) && fn.get(1) == all.get(2), true);

        // random nights with phone use and ring wake ups: at every level the preview is the saved night
        Random rnd = new Random(30);
        int differs = 0, countDiffers = 0, oldDiffers = 0, nights = 20000;
        for (int t = 0; t < nights; t++) {
            int len = 20 + rnd.nextInt(200);
            int[] s = new int[len];
            for (int i = 0; i < len; ) {
                int code = rnd.nextInt(12) == 0 ? 0 : 1 + rnd.nextInt(4);
                int run = 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 12);
                for (int k = 0; k < run && i < len; k++) s[i++] = code;
            }
            double start = 1_700_000_000 + rnd.nextInt(1000), end = start + len * 30;
            List<double[]> sessions = new ArrayList<double[]>();
            for (int k = rnd.nextInt(5); k > 0; k--) {
                double a = start - 300 + rnd.nextDouble() * (end - start + 600);
                sessions.add(new double[] {a, a + 5 + rnd.nextDouble() * 200});
            }
            if (rnd.nextInt(20) == 0) sessions.add(new double[] {start, start + ScreenTime.MAX_NIGHT_SESSION_S + 60});
            List<double[]> ring = new ArrayList<double[]>();
            for (int k = rnd.nextInt(3); k > 0; k--) {
                double a = start + rnd.nextDouble() * (end - start);
                ring.add(new double[] {a, a + 20 + rnd.nextDouble() * 280});
            }
            List<double[]> phone = ScreenTime.forNight(sessions, start, end);
            for (int lv = 0; lv < 3; lv++) {
                NightAnalyzer.Input r = new NightAnalyzer.Input();
                r.start = start;
                r.end = end;
                r.ringStages = s;
                r.stageDetail = lv;
                r.screen.addAll(phone);
                r.ringAwake.addAll(ring);
                Night rn = NightAnalyzer.analyze(r);
                int[] p = NightAnalyzer.detailStages(rn.stagesRaw, lv, start, end, phone, rn.ringAwake);
                if (!Arrays.equals(p, rn.stages)) differs++;
                if (WakeUps.count(p, 30) != rn.awakenings) countDiffers++;
                if (!Arrays.equals(StageFilter.filter(rn.stagesRaw, lv), rn.stages)) oldDiffers++;
            }
        }
        eq("random nights with phone use: the preview is the saved night at every level", differs, 0);
        eq("random nights with phone use: the preview counts the saved wake ups", countDiffers, 0);
        check("random nights with phone use: the filter alone would differ in some (" + oldDiffers + ")", oldDiffers > 0);

        // the texts say what always stays awake
        String[] tx = app.openring.ui.Explain.STAGE_DETAIL;
        eq("stage detail texts: one per level", tx.length, StageFilter.NAMES.length);
        for (int lv = 0; lv < tx.length; lv++) {
            check("stage detail text " + lv + " starts with its name", tx[lv].startsWith(StageFilter.NAMES[lv]));
            check("stage detail text " + lv + " has no dash as punctuation", !tx[lv].contains(" - ") && !tx[lv].contains("–") && !tx[lv].contains("—"));
        }
        for (int lv = B; lv <= C; lv++) {
            check("stage detail text " + lv + " names phone use, the ring's own wake ups and missing data",
                    tx[lv].toLowerCase(Locale.ROOT).contains("phone use") && tx[lv].contains("a wake up your ring reports itself")
                    && tx[lv].contains("next to time without ring data"));
        }
        check("Calm text no longer says every shorter wake up goes", !tx[C].contains("Wake ups shorter than 2 minutes are no longer shown or counted."));
        eq("preview phone line: none without phone data", app.openring.ui.Explain.previewPhone(Double.NaN), null);
        eq("preview phone line: none at 0", app.openring.ui.Explain.previewPhone(0), null);
        eq("preview phone line: half a minute", app.openring.ui.Explain.previewPhone(0.5),
                "Includes under a minute on the phone, shown as awake at every detail level.");
        eq("preview phone line: minutes", app.openring.ui.Explain.previewPhone(4.2),
                "Includes 4 min on the phone, shown as awake at every detail level.");
    }

    /**
     * Issue #116: the Cardio capacity card shows only the heart rate estimate (Uth) its text names,
     * only inside 10 to 90 ml/kg/min, and says why when there is no number.
     */
    static void vo2max() {
        double uth = app.openring.core.analysis.Wellness.vo2maxUth(35, 55);
        check("vo2max: Uth for 35 years and 55 bpm is 51.0", Math.abs(uth - 51.0) < 0.05);
        check("vo2max: no estimate without a heart rate", Double.isNaN(app.openring.core.analysis.Wellness.vo2maxUth(35, Double.NaN)));
        double[][] cases = {{9.9, Double.NaN}, {10, 10}, {51, 51}, {90, 90}, {90.1, Double.NaN}, {0, Double.NaN}, {-3, Double.NaN},
            {Double.NaN, Double.NaN}, {Double.POSITIVE_INFINITY, Double.NaN}};
        for (double[] cs : cases) {
            double got = app.openring.core.analysis.Wellness.vo2maxShown(cs[0]);
            check("vo2max shown for " + cs[0], Double.isNaN(cs[1]) ? Double.isNaN(got) : got == cs[1]);
        }
        // the highest Uth can give (lowest heart rate 30 bpm, age 18) is outside the range, so it is not shown
        check("vo2max: an impossible Uth value is not shown",
                Double.isNaN(app.openring.core.analysis.Wellness.vo2maxShown(app.openring.core.analysis.Wellness.vo2maxUth(18, 30))));
        String withNight = "From your age and the lowest heart rate in that night's sleep (Uth method). A rough guide, not a lab test.";
        eq("vo2max line: a value", app.openring.ui.Explain.vo2max(51, true), withNight);
        eq("vo2max line: no night", app.openring.ui.Explain.vo2max(Double.NaN, false),
                "Needs a night with the ring. It comes from your age and the lowest heart rate in that night's sleep (Uth method).");
        // a night was found, but its lowest heart rate was missing or under 30 bpm, so Analyzer stored no vo2max
        eq("vo2max line: a night without a usable heart rate", app.openring.ui.Explain.vo2max(Double.NaN, true),
                "No usable heart rate in that night's sleep, so no estimate. It needs the lowest heart rate of the night (Uth method).");
        eq("vo2max line: impossible value", app.openring.ui.Explain.vo2max(99.7, true),
                "Not shown for this day: that night's lowest heart rate gave a value outside what a person can have (10 to 90 ml/kg/min), so that reading was probably off.");
        eq("vo2max line: zero is impossible too", app.openring.ui.Explain.vo2max(0, true), app.openring.ui.Explain.vo2max(99.7, true));
        check("vo2max explanation names the unit", app.openring.ui.Explain.VO2MAX.contains("ml/kg/min") && app.openring.ui.Explain.VO2MAX.startsWith("VO2 max is "));
        for (String tx : new String[] {app.openring.ui.Explain.VO2MAX, app.openring.ui.Explain.vo2max(51, true), app.openring.ui.Explain.vo2max(Double.NaN, false),
            app.openring.ui.Explain.vo2max(Double.NaN, true), app.openring.ui.Explain.vo2max(0, true)}) {
            check("vo2max texts use no dash as punctuation: " + tx, !tx.contains(" - ") && !tx.contains("\u2013") && !tx.contains("\u2014"));
        }
    }

    /** The wake up count before issue #70 (NightAnalyzer.countBouts with 2 steps), as the reference. */
    static int oldRule(int[] st) {
        int onset = -1, last = -1;
        for (int i = 0; i < st.length; i++) if (st[i] >= 1 && st[i] <= 3) { if (onset < 0) onset = i; last = i; }
        if (onset < 0) return 0;
        int count = 0, run = 0;
        for (int i = onset; i <= last; i++) {
            if (st[i] == Stager.WAKE) run++;
            else { if (run >= 2) count++; run = 0; }
        }
        if (run >= 2) count++;
        return count;
    }

    static Night metricsOnly(int[] stages, int level) {
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = 1_700_000_000;
        in.end = in.start + stages.length * 30;
        in.ringStages = stages; // taken as the ring's own hypnogram, so the stager does not run
        in.stageDetail = level;
        return NightAnalyzer.analyze(in);
    }

    // ------------------------------------------------------------------ Setup and Sync once Android no longer asks (issue #186)

    static void permissionSettings() {
        final int G = PermissionAsk.GRANTED, D = PermissionAsk.DENIED;
        String[] need = {"SCAN", "CONNECT"};
        String[] asked = {"SCAN", "CONNECT", "NOTIFY"};
        boolean[] none = {false, false}, both = {true, true};
        int[] refused = {D, D, D};
        // Setup's "Allow", tapped three times on a fresh install, refused each time
        PermissionAsk.Answer a = PermissionAsk.answer(false, asked, refused, need, both, false, false);
        check("#186: the first refusal keeps today's toast (Android still asks)", !a.blocked && a.denied && !a.quiet);
        a = PermissionAsk.answer(false, asked, refused, need, none, a.denied, a.quiet);
        check("#186: refused again, Android no longer explains it: the way to the app's settings", a.blocked);
        a = PermissionAsk.answer(false, asked, refused, need, none, a.denied, a.quiet);
        check("#186: and every tap after that stays there", a.blocked);
        // Sync on the main screen after the permission was removed in system settings (refused earlier, remembered)
        check("#186: Sync after a remembered refusal: the window on the first tap",
                PermissionAsk.answer(false, asked, refused, need, none, true, false).blocked);
        // refusals from before the app remembered them: two quiet answers in a row
        a = PermissionAsk.answer(false, asked, refused, need, none, false, false);
        check("#186: one quiet answer without a remembered refusal is not enough (a dialog closed with Back)", !a.blocked && a.quiet);
        check("#186: the second quiet answer opens the way to the settings",
                PermissionAsk.answer(false, asked, refused, need, none, a.denied, a.quiet).blocked);
        a = PermissionAsk.answer(true, asked, new int[] {G, G, G}, need, none, true, true);
        check("#186: allowed in the settings: setup goes on, the refusal is forgotten", !a.blocked && !a.denied);
        check("#186: an interrupted request (empty answer) never blocks",
                !PermissionAsk.answer(false, new String[0], new int[0], need, none, true, true).blocked);
        String newer = PermissionAsk.settingsText(31), older = PermissionAsk.settingsText(30);
        check("#186: texts name the permission as Android shows it", newer.endsWith("allow Nearby devices.") && older.endsWith("allow Location.")
                && PermissionAsk.settingsText(34).equals(newer));
        for (String x : new String[] {newer, older, PermissionAsk.SETTINGS_BUTTON}) {
            check("#186: plain text without dashes: " + x, !x.contains(" - ") && !x.contains("\u2013") && !x.contains("\u2014") && x.length() < 100);
        }
        check("#186: the button says what it does", "Open app settings".equals(PermissionAsk.SETTINGS_BUTTON));
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
        p.add(13 * 60, a, 30); // an afternoon start is kept too now, the whole clock has bins (issue #164)
        check("pattern: counts nights", p.nights() == 3);
        // only the 13:00 to 21:00 night covers 15:00, the other two do not: 1 of 3 nights
        check("pattern: an afternoon night is in bed at 15:00", Math.abs(p.inBed(binAt(15 * 60)) - 1 / 3.0) < 1e-9);
        patternClock();
        clockAverage();
    }

    static int binAt(int clockMin) {
        return Math.floorMod(clockMin - SleepPattern.ANCHOR_MIN, 1440) / SleepPattern.BIN_MIN;
    }

    /** First and last bin with someone in bed, as clock minutes {start of first, end of last}. */
    static int[] inBedSpan(SleepPattern p) {
        int[] r = p.range(0.05);
        int first = -1, last = -1;
        for (int i = r[0]; i < r[1]; i++) {
            if (p.inBed(i) <= 0) continue;
            if (first < 0) first = i;
            last = i;
        }
        return first < 0 ? new int[] {-1, -1} : new int[] {SleepPattern.clockMin(first), SleepPattern.clockMin(last + 1)};
    }

    static long local(String t, java.time.ZoneId z) {
        return java.time.LocalDateTime.parse(t).atZone(z).toEpochSecond();
    }

    /** Issue #137: each stage cell at its own clock time; the window round the whole clock (issue #164). */
    static void patternClock() {
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        // a normal night through the zone gives the same bins as by its start's clock minute
        SleepPattern byStart = new SleepPattern(), byZone = new SleepPattern();
        int[] st = new int[8 * 120];
        for (int i = 0; i < st.length; i++) st[i] = 1 + (i / 37) % 4;
        byStart.add(23 * 60, st, 30);
        byZone.add(local("2026-10-10T23:00:00", z), st, 30, z);
        boolean same = true;
        for (int b = 0; b < SleepPattern.BINS; b++)
            for (int k = 1; k <= 4; k++) same &= Math.abs(byStart.share(b, k) - byZone.share(b, k)) < 1e-9;
        check("pattern clock: a normal night gives the same bins as before", same);
        int[] r = byZone.range(0.05);
        check("pattern clock: normal night window 22:00 to 08:00", SleepPattern.clockMin(r[0]) == 22 * 60 && SleepPattern.clockMin(r[1]) == 8 * 60);

        // autumn 2026-10-25: 23:48 to 08:03 is 9h15m of real time
        SleepPattern fall = new SleepPattern();
        double fs = local("2026-10-24T23:48:00", z), fe = local("2026-10-25T08:03:00", z);
        int[] lightF = new int[(int) ((fe - fs) / 30)];
        Arrays.fill(lightF, 2);
        fall.add(fs, lightF, 30, z);
        int[] fspan = inBedSpan(fall);
        check("pattern clock: autumn night ends at 08:10 bin (08:03), not 09:00", fspan[1] == 8 * 60 + 10 && fspan[0] == 23 * 60 + 40);
        check("pattern clock: the repeated hour never reads more than 100%", fall.inBed(binAt(2 * 60 + 30)) <= 1 + 1e-9
                && Math.abs(fall.share(binAt(2 * 60 + 30), 2) - 1) < 1e-9);
        // a mixed repeated hour: first pass deep, second pass REM, so the bin shows half of each
        SleepPattern mix = new SleepPattern();
        double ms = local("2026-10-25T02:00:00", z); // the first pass of 02:00 (java.time takes the earlier offset)
        int[] two = new int[240];
        for (int i = 0; i < 240; i++) two[i] = i < 120 ? 1 : 3;
        mix.add(ms, two, 30, z);
        int b0230 = binAt(2 * 60 + 30);
        check("pattern clock: both passes of the repeated hour share its bins", Math.abs(mix.share(b0230, 1) - 0.5) < 1e-9
                && Math.abs(mix.share(b0230, 3) - 0.5) < 1e-9 && Math.abs(mix.inBed(b0230) - 1) < 1e-9 && mix.inBed(binAt(3 * 60)) == 0);

        // review of #205: the switch night in bed 01:30 to 08:00, six other nights 04:00 to 08:00;
        // at 02:30 only 1 of 7 nights is in bed, even though that night has 20 minutes in the bin
        SleepPattern week = new SleepPattern();
        double ws = local("2026-10-25T01:30:00", z), we = local("2026-10-25T08:00:00", z);
        int[] sw = new int[(int) ((we - ws) / 30)];
        for (int i = 0; i < sw.length; i++) sw[i] = i % 2 == 0 ? 1 : 2;
        week.add(ws, sw, 30, z);
        int[] four = new int[4 * 120];
        Arrays.fill(four, 2);
        for (int d = 0; d < 6; d++) week.add(local("2026-10-1" + d + "T04:00:00", z), four, 30, z);
        int w0230 = binAt(2 * 60 + 30);
        check("pattern clock: repeated hour counts one night once (1 of 7 in bed at 02:30)", Math.abs(week.inBed(w0230) - 1 / 7.0) < 1e-9
                && Math.abs(week.share(w0230, 1) - 0.5 / 7) < 1e-9 && Math.abs(week.inBed(binAt(5 * 60)) - 1) < 1e-9);

        // spring 2027-03-28: 22:03 to 07:54 is 8h51m of real time; 02:00 to 03:00 never shows on the clock
        SleepPattern spring = new SleepPattern();
        double ss = local("2027-03-27T22:03:00", z), se = local("2027-03-28T07:54:00", z);
        int[] lightS = new int[(int) ((se - ss) / 30)];
        Arrays.fill(lightS, 2);
        spring.add(ss, lightS, 30, z);
        int[] sspan = inBedSpan(spring);
        check("pattern clock: spring night ends at the 07:50 bin (07:54), not 06:50", sspan[1] == 8 * 60 && sspan[0] == 22 * 60);
        check("pattern clock: the skipped spring hour stays empty", spring.inBed(binAt(2 * 60 + 30)) == 0
                && spring.inBed(binAt(1 * 60 + 50)) == 1 && spring.inBed(binAt(3 * 60)) == 1);
        // the same night by the start's clock minute (the old way) ends an hour early
        SleepPattern oldWay = new SleepPattern();
        oldWay.add(22 * 60 + 3, lightS, 30);
        check("pattern clock: counted on from the start it would end at 06:50", inBedSpan(oldWay)[1] == 7 * 60);
        // Santiago, clocks forward at midnight 2026-09-06
        java.time.ZoneId sz = java.time.ZoneId.of("America/Santiago");
        SleepPattern sant = new SleepPattern();
        double as = local("2026-09-05T23:48:00", sz), ae = local("2026-09-06T08:03:00", sz);
        int[] lightA = new int[(int) ((ae - as) / 30)];
        Arrays.fill(lightA, 2);
        sant.add(as, lightA, 30, sz);
        check("pattern clock: Santiago night ends at 08:03 too", inBedSpan(sant)[1] == 8 * 60 + 10);

        // a day sleeper 08:00 to 16:00 is kept whole, the window follows it
        SleepPattern day = new SleepPattern();
        int[] eight = new int[8 * 120];
        Arrays.fill(eight, 2);
        day.add(local("2026-10-10T08:00:00", z), eight, 30, z);
        int[] dr = day.range(0.05);
        check("pattern clock: day sleep window 07:00 to 17:00", SleepPattern.clockMin(dr[0]) == 7 * 60 && SleepPattern.clockMin(dr[1]) == 17 * 60
                && dr[1] - dr[0] == 10 * 6);
        check("pattern clock: day sleep kept to 16:00", day.inBed(binAt(15 * 60 + 50)) == 1 && day.inBed(binAt(16 * 60)) == 0);
        // a start before 16:00 was dropped whole before
        SleepPattern early = new SleepPattern();
        early.add(15 * 60, eight, 30);
        check("pattern clock: a 15:00 start is kept", early.inBed(binAt(15 * 60)) == 1 && early.inBed(binAt(22 * 60 + 50)) == 1);
        // an evening night 18:00 to 02:00: the window passes 20:00 and goes on
        SleepPattern eve = new SleepPattern();
        eve.add(18 * 60, eight, 30);
        int[] er = eve.range(0.05);
        check("pattern clock: evening window 17:00 to 03:00", SleepPattern.clockMin(er[0]) == 17 * 60 && SleepPattern.clockMin(er[1]) == 3 * 60
                && er[0] >= 0 && er[1] - er[0] == 10 * 6 && eve.inBed(er[0] + 6) == 1);
        // a day sleeper plus one normal night: the window starts after the longest empty stretch
        SleepPattern both = new SleepPattern();
        both.add(8 * 60, eight, 30);
        both.add(23 * 60, eight, 30);
        int[] br = both.range(0.05);
        check("pattern clock: two sleeps, one window 22:00 to 17:00", SleepPattern.clockMin(br[0]) == 22 * 60 && SleepPattern.clockMin(br[1]) == 17 * 60
                && br[1] - br[0] == 19 * 6);
        // in bed round the clock: the whole clock from 20:00; nobody in bed: the same
        SleepPattern all = new SleepPattern();
        int[] full = new int[24 * 120];
        Arrays.fill(full, 2);
        all.add(0, full, 30);
        int[] ar = all.range(0.05);
        check("pattern clock: round the clock draws 24 hours", ar[0] == 0 && ar[1] == SleepPattern.BINS);
        SleepPattern awakeOnly = new SleepPattern();
        awakeOnly.add(0, new int[] {0, 0, 0}, 30);
        int[] nr = awakeOnly.range(0.05);
        check("pattern clock: no stage data draws the whole clock", nr[0] == 0 && nr[1] == SleepPattern.BINS && awakeOnly.nights() == 1);
        // a long stretch with a short gap: never more than 24 hours drawn
        SleepPattern longOne = new SleepPattern();
        int[] twentyTwo = new int[22 * 120 + 60];
        Arrays.fill(twentyTwo, 2);
        longOne.add(20 * 60, twentyTwo, 30);
        int[] lr = longOne.range(0.05);
        check("pattern clock: at most 24 hours drawn", lr[1] - lr[0] == SleepPattern.BINS && lr[0] >= 0);
        check("pattern clock: bins past the clock wrap", SleepPattern.clockMin(SleepPattern.BINS + 6) == 21 * 60
                && Math.abs(longOne.share(SleepPattern.BINS + 6, 2) - longOne.share(6, 2)) < 1e-12);
        SleepPattern none = new SleepPattern();
        none.add(0, new int[0], 30);
        none.add(0, (int[]) null, 30);
        none.add(0.0, new int[0], 30, z);
        none.add(0.0, st, 0, z);
        check("pattern clock: empty input adds no night", none.nights() == 0 && none.share(0, 1) == 0);
    }

    /** The old bedtime maths: minutes after 20:00 wrapped at 16:00, plain mean and spread. */
    static double[] oldClockMean(double[] clock) {
        double[] r = new double[clock.length];
        for (int i = 0; i < clock.length; i++) {
            double x = clock[i] - 20 * 60;
            if (x < -4 * 60) x += 1440;
            r[i] = Double.isNaN(clock[i]) ? Double.NaN : x;
        }
        double m = Stats.mean(r);
        return new double[] {((m + 20 * 60) % 1440 + 1440) % 1440, Stats.countFinite(r) >= 2 ? Stats.sd(r) : Double.NaN};
    }

    /** Issue #164: clock times averaged on the clock face. */
    static void clockAverage() {
        double[] w = ClockAverage.of(new double[] {15 * 60 + 50, 16 * 60 + 10});
        check("clock average: 15:50 and 16:10 give 16:00", Math.abs(w[0] - 16 * 60) < 1e-6 && Math.abs(w[1] - 10) < 1e-6);
        double[] w3 = ClockAverage.of(new double[] {15 * 60 + 30, 16 * 60, 16 * 60 + 30});
        check("clock average: 15:30, 16:00 and 16:30 give 16:00, spread 24 min", Math.abs(w3[0] - 16 * 60) < 1e-6 && Math.round(w3[1]) == 24);
        // the old maths (oldClockMean) gave 04:00 there
        double[] mid = ClockAverage.of(new double[] {23 * 60 + 50, 10});
        check("clock average: 23:50 and 00:10 give 00:00", Math.abs(mid[0]) < 1e-6 || Math.abs(mid[0] - 1440) < 1e-6);
        // normal nights: exactly the old numbers
        Random rnd = new Random(5);
        boolean same = true;
        for (int k = 0; k < 200; k++) {
            int n = 1 + rnd.nextInt(30);
            double[] bed = new double[n], wake = new double[n];
            for (int i = 0; i < n; i++) {
                bed[i] = Math.floorMod(21 * 60 + rnd.nextInt(5 * 60), 1440) + rnd.nextDouble(); // 21:00 to 02:00
                wake[i] = 5 * 60 + rnd.nextInt(5 * 60) + rnd.nextDouble(); // 05:00 to 10:00
                if (rnd.nextInt(10) == 0) bed[i] = Double.NaN;
            }
            double[] nb = ClockAverage.of(bed), ob = oldClockMean(bed), nw = ClockAverage.of(wake), ow = oldClockMean(wake);
            same &= close(nb[0], ob[0]) && close(nb[1], ob[1]) && close(nw[0], ow[0]) && close(nw[1], ow[1]);
        }
        check("clock average: normal nights give exactly the old numbers", same);
        double[] fixed = ClockAverage.of(new double[] {23 * 60, 23 * 60 + 30});
        check("clock average: 23:00 and 23:30 give 23:15, spread 15", close(fixed[0], 23 * 60 + 15) && close(fixed[1], 15));
        double[] one = ClockAverage.of(new double[] {Double.NaN, 7 * 60 + 10});
        check("clock average: one time has no spread", close(one[0], 7 * 60 + 10) && Double.isNaN(one[1]));
        double[] empty = ClockAverage.of(new double[] {Double.NaN});
        check("clock average: no times give no value", Double.isNaN(empty[0]) && Double.isNaN(empty[1]));
        double[] opposite = ClockAverage.of(new double[] {4 * 60, 16 * 60});
        // no centre: counted from 04:00 like the old window, so 22:00 as before, and the spread says irregular
        check("clock average: opposite times count from 04:00, large spread", close(opposite[0], 22 * 60) && close(opposite[1], 360)
                && close(opposite[0], oldClockMean(new double[] {4 * 60, 16 * 60})[0]));
    }

    static boolean close(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.isNaN(a) && Double.isNaN(b);
        double d = Math.abs(a - b);
        return Math.min(d, 1440 - d) < 1e-6;
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

    /** Issue #199: a saved key this phone cannot read is not "no key", says so plainly and is never retried. */
    static void keyState199() {
        eq("key state: nothing stored", KeyState.of(false, false), KeyState.NONE);
        eq("key state: nothing stored, nothing read", KeyState.of(false, true), KeyState.NONE);
        eq("key state: stored and read", KeyState.of(true, true), KeyState.READABLE);
        eq("key state: stored, not read", KeyState.of(true, false), KeyState.UNREADABLE);
        // the main screen opens Setup by itself as before, never for an unreadable key
        check("key state: not set up opens Setup", KeyState.opensSetup(false, false, KeyState.READABLE, false));
        check("key state: no key opens Setup", KeyState.opensSetup(false, true, KeyState.NONE, false));
        check("key state: a key page not left opens Setup", KeyState.opensSetup(false, true, KeyState.READABLE, true));
        check("key state: a readable key stays", !KeyState.opensSetup(false, true, KeyState.READABLE, false));
        check("key state: an unreadable key stays on the main screen", !KeyState.opensSetup(false, true, KeyState.UNREADABLE, false));
        check("key state: an unreadable key never opens the key page", !KeyState.opensSetup(false, true, KeyState.UNREADABLE, true));
        check("key state: not set up with an unreadable key opens Setup", KeyState.opensSetup(false, false, KeyState.UNREADABLE, false));
        check("key state: demo never opens Setup", !KeyState.opensSetup(true, false, KeyState.NONE, true));
        check("key state: only a readable key syncs", KeyState.syncs(KeyState.READABLE) && !KeyState.syncs(KeyState.NONE) && !KeyState.syncs(KeyState.UNREADABLE));
        eq("key state: no key text as before", KeyState.missing(false), "No ring key saved. Finish setup first.");
        eq("key state: unreadable text", KeyState.missing(true), KeyState.UNREADABLE_RAW);
        eq("key state: no key kind", SyncError.kind(KeyState.missing(false)), SyncError.Kind.NO_KEY);
        eq("key state: unreadable kind", SyncError.kind(KeyState.missing(true)), SyncError.Kind.KEY_UNREADABLE);
        eq("key state: unreadable plain for a sync", SyncError.plain(KeyState.missing(true)), KeyState.PLAIN);
        eq("key state: unreadable plain for an action", SyncError.plain(KeyState.missing(true), SyncError.Task.ACTION), KeyState.PLAIN);
        check("key state: an unreadable key is not retried", !SyncError.retryable(KeyState.missing(true), true));
        check("key state: a failed sync with an unreadable key gets a notice", KeyState.PLAIN.equals(SyncError.failureNotice(false, KeyState.missing(true))));
        for (String x : KeyState.texts()) {
            check("key state: plain, no dash as punctuation: " + x, !x.isEmpty() && !x.contains(" - ") && !x.contains("\u2013") && !x.contains("\u2014"));
        }
        eq("key state: the backup reason (review of #281)", Backups.reason(Backups.KEY), "this phone cannot read the saved ring key, enter it again in Setup");
        check("key state: the backup reason is not the folder's", !Backups.reason(Backups.KEY).equals(Backups.reason(Backups.WRITE)));
        check("#288 the backup cards say the key is left out and what to do", KeyState.BACKUP_NO_KEY.contains("saved without it") && KeyState.BACKUP_NO_KEY.contains("Setup"));
        check("#288 the saved note says the key is not in it, and fits a toast", KeyState.BACKUP_SAVED_NO_KEY.startsWith("Backup saved, without the ring key")
                && Texts.fitsToast(KeyState.BACKUP_SAVED_NO_KEY));
        check("key state: the plain sentences say what to do", KeyState.PLAIN.contains("enter the key again") && KeyState.STATUS.contains("Tap here")
                && KeyState.SETUP_TEXT.contains("saved key stays") && KeyState.SYNC_TEXT.contains("saved key stays"));
    }

    /** Issue #329, phase 1a: one "no ring by choice" state decides every place that sends a person to Setup. */
    static void keyStateNoRing329() {
        int[] all = {KeyState.NONE, KeyState.READABLE, KeyState.UNREADABLE, KeyState.NO_RING};
        check("#329 no ring: a state of its own", KeyState.NO_RING != KeyState.NONE && KeyState.NO_RING != KeyState.READABLE && KeyState.NO_RING != KeyState.UNREADABLE);
        // nothing sets it in phase 1a: of() never gives it
        for (boolean stored : new boolean[] {false, true}) {
            for (boolean read : new boolean[] {false, true}) check("#329 no ring: not from a stored key " + stored + " " + read, KeyState.of(stored, read) != KeyState.NO_RING);
        }
        eq("#329 without reading the key: nothing stored", KeyState.ofStored(false), KeyState.NONE);
        eq("#329 without reading the key: a stored key", KeyState.ofStored(true), KeyState.READABLE);
        for (boolean stored : new boolean[] {false, true}) {
            int st = KeyState.ofStored(stored);
            check("#329 without reading the key: never unreadable or no ring (" + stored + ")", st != KeyState.UNREADABLE && st != KeyState.NO_RING);
        }
        // the main screen (MainActivity.onResume): never Setup in a loop
        for (boolean done : new boolean[] {false, true}) {
            for (boolean unseen : new boolean[] {false, true}) {
                check("#329 no ring: the main screen never opens Setup (set up " + done + ", key page " + unseen + ")", !KeyState.opensSetup(false, done, KeyState.NO_RING, unseen));
                check("#329 no ring: demo neither", !KeyState.opensSetup(true, done, KeyState.NO_RING, unseen));
            }
        }
        // Settings.onResume and the main screen after a failed demo: Setup only when not set up or no key, as before
        for (int st : all) {
            for (boolean done : new boolean[] {false, true}) {
                boolean before = !done || st == KeyState.NONE; // !setupDone() || !hasKey(), the old test
                boolean want = st == KeyState.NO_RING ? false : before;
                eq("#329 needs Setup: state " + st + ", set up " + done, KeyState.needsSetup(done, st), want);
            }
        }
        // the old checks of the two screens read hasKey(): a stored key, readable or not; ofStored keeps them exact
        for (boolean stored : new boolean[] {false, true}) {
            for (boolean done : new boolean[] {false, true}) {
                eq("#329 needs Setup as before: stored " + stored + ", set up " + done, KeyState.needsSetup(done, KeyState.ofStored(stored)), !done || !stored);
                eq("#329 needs Setup as before, read state: stored " + stored + ", set up " + done, KeyState.needsSetup(done, KeyState.of(stored, false)), !done || !stored);
            }
        }
        // opensSetup is unchanged for the old states
        for (int st : new int[] {KeyState.NONE, KeyState.READABLE, KeyState.UNREADABLE}) {
            for (boolean demo : new boolean[] {false, true}) {
                for (boolean done : new boolean[] {false, true}) {
                    for (boolean unseen : new boolean[] {false, true}) {
                        boolean old = !demo && (!done || st == KeyState.NONE || unseen && st == KeyState.READABLE);
                        eq("#329 opens Setup as before: " + st + " " + demo + " " + done + " " + unseen, KeyState.opensSetup(demo, done, st, unseen), old);
                    }
                }
            }
        }
        // Find my ring talks to a paired ring only with a stored key (was hasKey())
        check("#329 has a ring: only with a stored key", KeyState.hasRing(KeyState.READABLE) && KeyState.hasRing(KeyState.UNREADABLE) && !KeyState.hasRing(KeyState.NONE)
                && !KeyState.hasRing(KeyState.NO_RING));
        check("#329 has a ring as before: hasKey()", KeyState.hasRing(KeyState.ofStored(true)) && !KeyState.hasRing(KeyState.ofStored(false)));
        check("#329 no ring: never syncs", !KeyState.syncs(KeyState.NO_RING));
    }

    /** Issue #329, phase 1a: the background job's decision, as SyncJob made it before (busy, ring part, watch part, recent). */
    static void backgroundJobDue329() {
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Zurich");
        long now = java.time.ZonedDateTime.of(2026, 11, 5, 15, 0, 0, 0, zone).toEpochSecond();
        int count = 0;
        for (boolean busy : new boolean[] {false, true}) {
            for (boolean bg : new boolean[] {false, true}) {
                for (int ks : new int[] {KeyState.NONE, KeyState.READABLE, KeyState.UNREADABLE, KeyState.NO_RING}) {
                    for (boolean demo : new boolean[] {false, true}) {
                        for (boolean added : new boolean[] {false, true}) {
                            for (boolean watchDue : new boolean[] {false, true}) {
                                for (long last : new long[] {0, now - 10 * 60, now - 30 * 60, now - 31 * 60, now + 3600}) {
                                    BackgroundJob.Inputs in = new BackgroundJob.Inputs();
                                    in.ringBusy = busy;
                                    in.backgroundSync = bg;
                                    in.keyState = ks;
                                    in.demo = demo;
                                    in.watchAdded = added;
                                    in.watchPerDay = 1;
                                    // once a day: due when the last good sync is before today's clock time
                                    in.watchLastOk = watchDue ? now - 3 * 86400 : now - 60;
                                    in.watchLastAttempt = 0;
                                    in.lastSync = last;
                                    in.lastAttempt = 0;
                                    in.now = now;
                                    in.zone = zone;
                                    BackgroundJob.Due d = BackgroundJob.due(in);
                                    // the old SyncJob.onStartJob, line by line
                                    boolean oRun, oRing, oWatch;
                                    String oLog = null;
                                    if (busy) {
                                        oRun = oRing = oWatch = false;
                                    } else {
                                        boolean ringPart = bg && KeyState.syncs(ks);
                                        boolean watchPart = !demo && added && app.openring.core.withings.WatchDue.due(now, 1, in.watchLastOk, 0, zone);
                                        boolean ringRecent = BackgroundJob.recent(now, last, 0);
                                        if (ringPart && ringRecent) oLog = "background sync skipped: the last sync was less than 30 minutes ago";
                                        oRun = !((!ringPart || ringRecent) && !watchPart);
                                        oRing = oRun && ringPart && !ringRecent;
                                        oWatch = oRun && watchPart;
                                    }
                                    String name = "#329 job due: busy " + busy + ", on " + bg + ", key " + ks + ", demo " + demo + ", watch " + added + " due " + watchDue
                                            + ", last " + (now - last) + " s ago";
                                    check(name, d.run() == oRun && d.ring == oRing && d.watch == oWatch && (oLog == null ? d.log == null : oLog.equals(d.log)));
                                    count++;
                                }
                            }
                        }
                    }
                }
            }
        }
        eq("#329 job due: every combination", count, 2 * 2 * 4 * 2 * 2 * 2 * 5);
        // the cases by name
        BackgroundJob.Inputs in = new BackgroundJob.Inputs();
        in.now = now;
        in.zone = zone;
        in.backgroundSync = true;
        in.keyState = KeyState.READABLE;
        BackgroundJob.Due d = BackgroundJob.due(in);
        check("#329 job due: the ring on, nothing recent: the ring syncs", d.run() && d.ring && !d.watch && d.log == null);
        in.lastSync = now - 5 * 60;
        d = BackgroundJob.due(in);
        check("#329 job due: a sync 5 minutes ago: nothing runs, the skip is logged", !d.run() && !d.ring && BackgroundJob.SKIPPED_RECENT.equals(d.log));
        in.watchAdded = true;
        in.watchPerDay = 1;
        d = BackgroundJob.due(in);
        check("#329 job due: recent ring, a watch due: only the watch, the skip logged", d.run() && !d.ring && d.watch && d.log != null);
        in.demo = true;
        d = BackgroundJob.due(in);
        check("#329 job due: demo: never the watch", !d.watch && !d.run());
        in.demo = false;
        in.ringBusy = true;
        d = BackgroundJob.due(in);
        check("#329 job due: the ring busy: nothing, not even the watch, nothing logged", !d.run() && !d.ring && !d.watch && d.log == null);
        in.ringBusy = false;
        in.lastSync = 0;
        in.keyState = KeyState.UNREADABLE;
        in.watchAdded = false;
        d = BackgroundJob.due(in);
        check("#329 job due: an unreadable key: no ring part, no skip line", !d.run() && d.log == null);
        in.keyState = KeyState.NO_RING;
        d = BackgroundJob.due(in);
        check("#329 job due: no ring by choice: no ring part", !d.run() && !d.ring);
        check("#329 job due: the skip line is the one SyncJob logged", BackgroundJob.SKIPPED_RECENT.equals("background sync skipped: the last sync was less than 30 minutes ago"));
        BackgroundJob.Inputs fresh = new BackgroundJob.Inputs();
        check("#329 job due: by default nothing is due", !BackgroundJob.due(fresh).run() && fresh.keyState == KeyState.NONE && fresh.zone != null);
    }

    public static void main(String[] args) throws Exception {
        permissionSettings();
        keyState199();
        keyStateNoRing329();
        inputLimit();
        monthGrid();
        protocol();
        history();
        bundles();
        client();
        drainGuards();
        analysis();
        dayHeart();
        vo2max();
        longWake();
        nightNumbers();
        stageRuns189();
        timeline();
        activityWeek();
        activityDay();
        activityRestAndBurn();
        live();
        motion();
        pattern();
        clockChange();
        dialMath();
        stageFilter();
        wakeUps();
        previewSameAsSaved();
        stageShare();
        sleepMinutes();
        syncError();
        syncStart334();
        texts();
        unitTexts();
        scoreLadder();
        readinessUnworn240();
        movementShares();
        drafts();
        oneRun();
        fileSave222();
        backups242();
        keptHeight();
        scrollKeep();
        settingsBlocks();
        screenTime();
        screenAwakeGrid();
        screenApps();
        screenUnlock();
        screenAppReads();
        screenScore();
        decoders();
        ringTasks();
        liveSessions();
        demoRing();
        liveScreen();
        backgroundJob();
        backgroundJobDue329();
        phoneSteps329();
        phoneDays329();
        phoneJob329();
        noRingWalk329();
        dating220();
        recent194();
        demoLive();
        liveFeed();
        requests();
        auth();
        mutationGaps();
        pairing();
        pairingRun();
        ringGate();
        setupTasks();
        leftOut254();
        watchCheck251();
        modeRestore221();
        cycles252();
        updatesLink();
        cycles252b();
        remind252();
        drip252();
        rise252();
        how252();
        badges265();
        badgeScreens265();
        recent265();
        connectPlan195();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ issue #252: the period tracker's estimates

    static final java.time.LocalDate C0 = java.time.LocalDate.of(2025, 1, 6);
    static final Locale EN = Locale.ENGLISH;

    /** A log with one period of {@code days} medium days at each start (C0 plus the running sum of {@code lengths}). */
    static Map<java.time.LocalDate, Integer> log252(int days, int... lengths) {
        Map<java.time.LocalDate, Integer> m = new HashMap<java.time.LocalDate, Integer>();
        java.time.LocalDate s = C0;
        for (int i = 0; i <= lengths.length; i++) {
            for (int k = 0; k < days; k++) m.put(s.plusDays(k), Cycles.MEDIUM);
            if (i < lengths.length) s = s.plusDays(lengths[i]);
        }
        return m;
    }

    /** The start of the newest period of {@link #log252}. */
    static java.time.LocalDate lastStart252(int... lengths) {
        java.time.LocalDate s = C0;
        for (int l : lengths) s = s.plusDays(l);
        return s;
    }

    static Cycles cyc252(Map<java.time.LocalDate, Integer> log, java.time.LocalDate today, java.time.LocalDate... ends) {
        return Cycles.of(log, Arrays.asList(ends), today);
    }

    static java.time.LocalDate d252(int day) {
        return C0.plusDays(day - 1);
    }

    static boolean plain252(String s) {
        return s == null || (!s.contains(" - ") && !s.contains("–") && !s.contains("—") && !s.contains("±"));
    }

    // --------------------------------------------- issue #252, phase 2: ovulation from the temperature rise

    /**
     * Made up nights for the cycles of {@code lengths} from {@code from}: base 35.0 °C, noise {@code sigma}, {@code missing}
     * of the nights without a value, {@code warm} of them 0.6 °C warmer (a warm room, a fever night), and after each
     * ovulation (the next start minus {@code luteal[i]}, none when 0) {@code rise} more, half of it on the first night.
     * Adds each cycle's {start, next, ovulation or null} to {@code out}.
     */
    static TreeMap<java.time.LocalDate, Double> nights252(Random r, java.time.LocalDate from, int[] lengths, int[] luteal, double sigma, double rise,
            double missing, double warm, List<java.time.LocalDate[]> out) {
        TreeMap<java.time.LocalDate, Double> v = new TreeMap<java.time.LocalDate, Double>();
        java.time.LocalDate d = from;
        for (int k = 0; k < lengths.length; k++) {
            java.time.LocalDate next = d.plusDays(lengths[k]);
            java.time.LocalDate ov = luteal[k] == 0 ? null : next.minusDays(luteal[k]);
            for (java.time.LocalDate x = d; x.isBefore(next); x = x.plusDays(1)) {
                if (r.nextDouble() < missing) continue;
                double t = 35.0 + r.nextGaussian() * sigma;
                long after = ov == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(ov, x);
                if (after >= 1) t += after == 1 ? rise / 2 : rise;
                if (r.nextDouble() < warm) t += 0.6;
                v.put(x, t);
            }
            out.add(new java.time.LocalDate[] {d, next, ov});
            d = next;
        }
        return v;
    }

    /** {found, clear, clear within 1 day, possible within 2 days, cycles, summed error} over many made up people. */
    static double[] accuracy252(double sigma, double rise, long seed) {
        Random r = new Random(seed);
        double[] a = new double[6];
        for (int person = 0; person < 150; person++) {
            int own = (int) Math.round(12.4 + r.nextGaussian() * 1.2);
            int[] len = new int[8], lut = new int[8];
            for (int k = 0; k < 8; k++) {
                len[k] = (int) Math.round(29.3 + r.nextGaussian() * 2.6);
                lut[k] = rise == 0 ? 0 : Math.max(8, Math.min(17, own + (int) Math.round(r.nextGaussian())));
            }
            List<java.time.LocalDate[]> cyc = new ArrayList<java.time.LocalDate[]>();
            TreeMap<java.time.LocalDate, Double> v = nights252(r, C0, len, lut, sigma, rise, 0.15, 0.03, cyc);
            double noise = TempRise.noise(v, TempRise.NOISE_RING);
            for (java.time.LocalDate[] c : cyc) {
                a[4]++;
                TempRise.Rise x = TempRise.find(v, c[0], c[1], null, noise);
                if (!x.found()) continue;
                a[0]++;
                if (c[2] == null) continue;
                long e = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(c[2], x.ovulation));
                a[5] += e;
                if (x.confidence == TempRise.CLEAR) {
                    a[1]++;
                    if (e <= TempRise.CLEAR_RANGE) a[2]++;
                } else if (e <= TempRise.POSSIBLE_RANGE) a[3]++;
            }
        }
        return a;
    }

    /** A night series from day {@code first}: one value per day, NaN for a missing night. */
    static TreeMap<java.time.LocalDate, Double> series252(java.time.LocalDate first, double... v) {
        TreeMap<java.time.LocalDate, Double> m = new TreeMap<java.time.LocalDate, Double>();
        for (int i = 0; i < v.length; i++) if (!Double.isNaN(v[i])) m.put(first.plusDays(i), v[i]);
        return m;
    }

    /** {@code n} nights of {@code low}, then nights of {@code high} up to {@code total}. */
    static double[] step252(int n, int total, double low, double high) {
        double[] v = new double[total];
        for (int i = 0; i < total; i++) v[i] = i < n ? low + (i % 3 - 1) * 0.02 : high + (i % 3 - 1) * 0.02;
        return v;
    }

    static void rise252() {
        final double N = Double.NaN;
        // ---- the rule on one cycle, by hand ----
        java.time.LocalDate s = C0;
        // a 28 day cycle, 15 low nights, the first high night on day 16 (index 15): ovulation on day 15
        TreeMap<java.time.LocalDate, Double> v = series252(s, step252(15, 28, 36.0, 36.3));
        TempRise.Rise x = TempRise.find(v, s, s.plusDays(28), null, 0.05);
        check("#252e a clean step: clear, the day before the first high night, six low and three high nights", x.confidence == TempRise.CLEAR
                && x.ovulation.equals(s.plusDays(14)) && x.first.equals(s.plusDays(15)) && x.low.size() == 6 && x.high.size() == 3
                && x.low.get(5).equals(s.plusDays(14)) && x.high.get(2).equals(s.plusDays(17)) && Math.abs(x.baseline - 36.02) < 1e-9);
        check("#252e a clean step: the rise is about 0.3 and lasts", Math.abs(x.size() - 0.3) < 0.03 && x.why == TempRise.FOUND && x.toString().startsWith("clear "));
        double[] gap = step252(15, 28, 36.0, 36.3);
        gap[16] = N;
        x = TempRise.find(series252(s, gap), s, s.plusDays(28), null, 0.05);
        check("#252e one missing night between the high ones: still found on the same day", x.found() && x.ovulation.equals(s.plusDays(14)) && x.high.get(1).equals(s.plusDays(17)));
        gap[17] = N;
        x = TempRise.find(series252(s, gap), s, s.plusDays(28), null, 0.05);
        check("#252e two missing nights in a row after the first high one: the rule waits for nights that follow each other", !x.found() || !x.ovulation.equals(s.plusDays(14)), x);
        double[] lowGap = step252(15, 28, 36.0, 36.3);
        lowGap[11] = N;
        lowGap[13] = N;
        x = TempRise.find(series252(s, lowGap), s, s.plusDays(28), null, 0.05);
        check("#252e two missing nights among the eight before: the six baseline nights still found", x.found() && x.ovulation.equals(s.plusDays(14))
                && x.low.size() == 6 && x.low.get(0).equals(s.plusDays(7)));
        double[] fewLow = step252(15, 28, 36.0, 36.3);
        for (int i = 0; i < 15; i++) if (i != 1 && i != 4 && i != 8) fewLow[i] = N;
        x = TempRise.find(series252(s, fewLow), s, s.plusDays(28), null, 0.05);
        check("#252e too few nights before the rise: no baseline, not found", !x.found() && x.why == TempRise.FLAT && x.nights == 16, x.nights);
        x = TempRise.find(series252(s, N, N, 36.0, 36.0, N, 36.1, N, 36.3), s, s.plusDays(28), null, 0.05);
        check("#252e fewer than 9 nights in the cycle: too few to look", !x.found() && x.why == TempRise.FEW && x.nights == 4 && x.toString().equals("few nights"));
        x = TempRise.find(null, s, s.plusDays(28), null, 0.05);
        check("#252e no nights at all", !x.found() && x.nights == 0 && Double.isNaN(x.size()));

        // the third night falls short of the third night's height: a fourth high night confirms it, as possible
        double[] shy = step252(15, 28, 36.0, 36.3);
        shy[15] = 36.1;
        shy[16] = 36.1;
        shy[17] = 36.1;
        x = TempRise.find(series252(s, shy), s, s.plusDays(28), null, 0.05);
        check("#252e a third night that falls short: a fourth confirms it, possible", x.confidence == TempRise.POSSIBLE && x.high.size() == 4
                && x.ovulation.equals(s.plusDays(14)), x);
        // one night dips to the baseline: a fourth high night is needed, the dipped night is named
        double[] dip = step252(15, 28, 36.0, 36.3);
        dip[16] = 36.0;
        x = TempRise.find(series252(s, dip), s, s.plusDays(28), null, 0.05);
        check("#252e one night that dips: four high nights, the dip named, possible", x.confidence == TempRise.POSSIBLE && x.high.size() == 4
                && s.plusDays(16).equals(x.dipped) && x.ovulation.equals(s.plusDays(14)), x);
        dip[17] = 36.0;
        x = TempRise.find(series252(s, dip), s, s.plusDays(28), null, 0.05);
        check("#252e two nights that dip: not that day", !x.found() || !x.ovulation.equals(s.plusDays(14)), x);

        // a fever night among the baseline nights is left out and named; the rise is still found
        double[] fever = step252(15, 28, 36.0, 36.3);
        fever[12] = 37.0;
        x = TempRise.find(series252(s, fever), s, s.plusDays(28), null, 0.05);
        check("#252e a fever night before the rise: left out as far from its neighbours, the rise found", x.found() && x.ovulation.equals(s.plusDays(14))
                && x.odd.contains(s.plusDays(12)) && !x.low.contains(s.plusDays(12)), x.odd);
        // three warm nights early in the cycle, then flat: three high nights but no lasting rise
        double[] spell = step252(28, 28, 36.0, 36.0);
        spell[9] = spell[10] = spell[11] = 36.4;
        x = TempRise.find(series252(s, spell), s, s.plusDays(28), null, 0.05);
        check("#252e three warm nights that do not last (a cold, a warm room): not found", !x.found() && x.why == TempRise.FLAT, x);
        x = TempRise.find(series252(s, step252(28, 28, 36.0, 36.0)), s, s.plusDays(28), null, 0.05);
        check("#252e a flat cycle (no ovulation): not found", !x.found() && x.why == TempRise.FLAT && x.toString().equals("no rise"));

        // short and long cycles, and the time from ovulation to the next start
        x = TempRise.find(series252(s, step252(9, 21, 36.0, 36.3)), s, s.plusDays(21), null, 0.05);
        check("#252e a 21 day cycle: ovulation on day 9", x.found() && x.ovulation.equals(s.plusDays(8)), x);
        x = TempRise.find(series252(s, step252(27, 40, 36.0, 36.3)), s, s.plusDays(40), null, 0.05);
        check("#252e a 40 day cycle: ovulation on day 27", x.found() && x.ovulation.equals(s.plusDays(26)), x);
        x = TempRise.find(series252(s, step252(24, 28, 36.0, 36.3)), s, s.plusDays(28), null, 0.05);
        check("#252e a rise only 4 days before the next start: too late for an ovulation, not found", !x.found(), x);
        x = TempRise.find(series252(s, step252(6, 28, 36.0, 36.3)), s, s.plusDays(28), null, 0.05);
        check("#252e a rise on day 7 of a 28 day cycle: more than 19 days before the next start, not found", !x.found(), x);

        // the cycle running today: three high nights are needed, then the rise is found as it lasts
        x = TempRise.find(v, s, null, s.plusDays(16), 0.05);
        check("#252e this cycle with two high nights so far: not yet", !x.found() && x.why == TempRise.FLAT);
        x = TempRise.find(v, s, null, s.plusDays(17), 0.05);
        check("#252e this cycle with three high nights: found", x.confidence == TempRise.CLEAR && x.ovulation.equals(s.plusDays(14)));

        // the noise: measured from the nights, the third night's height from it
        Random nr = new Random(3);
        TreeMap<java.time.LocalDate, Double> noisy = new TreeMap<java.time.LocalDate, Double>();
        for (int i = 0; i < 300; i++) noisy.put(s.plusDays(i), 35 + nr.nextGaussian() * 0.12);
        double nz = TempRise.noise(noisy, 9);
        check("#252e noise: measured from night to night steps, about the made up 0.12", nz > 0.105 && nz < 0.135, nz);
        check("#252e noise: with too few steps the fallback", TempRise.noise(series252(s, 36, 36.1, N, 36.2), 0.15) == 0.15 && TempRise.noise(null, 0.1) == 0.1);
        check("#252e the third night's height: 0.2 for a steady signal, the noise in between, at least 0.1",
                TempRise.thirdNeed(0.3) == 0.2 && Math.abs(TempRise.thirdNeed(0.14) - 0.14) < 1e-12 && TempRise.thirdNeed(0.05) == 0.1);
        check("#252e a short night is no value for the rise", Double.isNaN(TempRise.ringNight(35.2, 120)) && TempRise.ringNight(35.2, 400) == 35.2
                && TempRise.ringNight(35.2, Double.NaN) == 35.2 && Double.isNaN(TempRise.ringNight(Double.NaN, 400)));

        // ---- accuracy on made up cycles (the Experiment view's setting: rise 0.3, noise 0.1 to 0.3, missing nights) ----
        double[] a1 = accuracy252(0.1, 0.3, 42), a2 = accuracy252(0.2, 0.3, 43), f1 = accuracy252(0.1, 0, 44), f3 = accuracy252(0.3, 0, 45);
        System.out.printf(Locale.ROOT, "    #252e noise 0.1: found %.0f %%, clear %.0f %% (within 1 day %.0f %%), possible within 2 days %.0f %%, mean error %.2f days%n",
                100 * a1[0] / a1[4], 100 * a1[1] / a1[4], 100 * a1[2] / a1[1], 100 * a1[3] / (a1[0] - a1[1]), a1[5] / a1[0]);
        System.out.printf(Locale.ROOT, "    #252e noise 0.2: found %.0f %%, mean error %.2f days; flat noise 0.1: %.1f %% found, 0.3: %.1f %%%n",
                100 * a2[0] / a2[4], a2[5] / a2[0], 100 * f1[0] / f1[4], 100 * f3[0] / f3[4]);
        check("#252e noise 0.1: about 7 in 10 rises found", a1[0] / a1[4] > 0.6, a1[0] / a1[4]);
        check("#252e noise 0.1: a clear rise within 1 day at least 4 times in 5", a1[2] / a1[1] >= 0.8, a1[2] / a1[1]);
        check("#252e noise 0.1: a possible one within 2 days at least 3 times in 4", a1[3] / (a1[0] - a1[1]) >= 0.75, a1[3] / (a1[0] - a1[1]));
        check("#252e noise 0.1: mean error about a day", a1[5] / a1[0] < 1.2, a1[5] / a1[0]);
        check("#252e noise 0.2: about a third found, mean error under 2 days", a2[0] / a2[4] > 0.25 && a2[0] / a2[4] < 0.5 && a2[5] / a2[0] < 2, a2[0] / a2[4]);
        check("#252e flat noise (no ovulation): hardly ever a rise, never a clear one at noise 0.1", f1[0] / f1[4] < 0.03 && f3[0] / f3[4] < 0.06 && f1[1] == 0,
                f1[0] / f1[4] + " " + f3[0] / f3[4]);

        // ---- each cycle with both signals, the own luteal length, the estimate ----
        int[] len = {28, 28, 28, 28, 28, 28};
        Map<java.time.LocalDate, Integer> log = log252(5, len);
        java.time.LocalDate last = lastStart252(len);
        // the ring rises on day 17 of each 28 day cycle: ovulation on day 16, 13 days before the next start
        TreeMap<java.time.LocalDate, Double> ring = new TreeMap<java.time.LocalDate, Double>();
        for (int k = 0; k <= len.length; k++) {
            java.time.LocalDate cs = C0.plusDays(28L * k);
            double[] st = step252(16, 28, 35.2, 35.5);
            for (int i = 0; i < 28; i++) ring.put(cs.plusDays(i), st[i]);
        }
        java.time.LocalDate today = last.plusDays(14);
        Cycles cal = cyc252(log, today);
        Cycles m = cal.measured(ring, null);
        TempRise.Analysis an = m.rise;
        check("#252e every cycle has its rise, ovulation 13 days before each next start", an.cycles.size() == 7 && an.over() == 6 && an.overFound() == 6
                && an.cycles.get(0).ovulation.equals(C0.plusDays(15)) && an.cycles.get(0).luteal() == 13 && an.cycles.get(0).source == TempRise.RING);
        check("#252e the own luteal length: 13 days from 6 cycles", an.ownLuteal() && an.lutealDays() == 13 && an.lutealN == 6 && an.lutealSd == 0);
        check("#252e this cycle on day 15: no rise yet, the calendar with the own 13 days", m.method == Cycles.OWN_LUTEAL && m.next.equals(cal.next)
                && m.range == cal.range && m.ovulation.equals(cal.next.minusDays(13)) && m.ovulationRange == 3 && m.luteal == 13, m.ovulation);
        String ol = m.ovulationLine(EN);
        check("#252e the line says it is the calendar with the own time", ol.contains("A calendar estimate from your own 13 days from ovulation to the period in 6 cycles")
                && ol.endsWith("No lasting temperature rise in this cycle yet."), ol);
        check("#252e this cycle is in the analysis, not found yet", an.current != null && an.current.next == null && !an.current.found()
                && TempRise.line(an.current, m, EN).equals("No lasting rise yet in this cycle."), an.current);
        // three days later the rise of this cycle shows: measured
        today = last.plusDays(18);
        cal = cyc252(log, today);
        m = cal.measured(ring, null);
        check("#252e this cycle's rise found: the measured day, the next start that day plus 13", m.method == Cycles.MEASURED
                && m.ovulation.equals(last.plusDays(15)) && m.next.equals(last.plusDays(28)) && m.ovulationRange == 1 && m.range == 1
                && m.calendarNext.equals(cal.next) && m.calendarRange == cal.range, m.next + " " + m.range);
        eq("#252e the card's line (the owner's example)", m.ovulationLine(EN), "Ovulation likely on " + m.date(last.plusDays(15), EN)
                + ", measured by your ring's temperature: next period expected " + m.span(last.plusDays(27), last.plusDays(29), EN) + ".");
        check("#252e the expected line says the method and the calendar's answer", m.expectedLine(EN).contains("From the ovulation your temperature shows plus your own 13 days to the period (from 6 cycles with a temperature rise). The calendar alone says ")
                , m.expectedLine(EN));
        check("#252e fertile days once measured: in the past, and the rise cannot tell them ahead", m.fertileLine(EN).startsWith("Likely fertile days were ")
                && m.fertileLine(EN).endsWith("so it cannot tell them ahead."));
        check("#252e the calendar band narrows to the measured day", m.inOvulation(last.plusDays(16)) && !m.inOvulation(last.plusDays(17))
                && cal.inOvulation(last.plusDays(17)));
        String rd = m.describe(last.plusDays(15), EN);
        check("#252e the calendar readout says why the day is in the band", rd.contains("the day before the temperature rose, give or take 1 day."), rd);
        rd = m.describe(last.plusDays(28), EN);
        check("#252e the calendar readout of the expected day: the measured ovulation plus 13 days", rd.contains("the measured ovulation plus 13 days, give or take 1 day."), rd);
        PeriodRemind.Plan plan = PeriodRemind.plan(m, 2, 540, java.time.ZoneOffset.UTC, today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), null);
        check("#252e the reminder follows the measured estimate", plan.planned() && plan.at == last.plusDays(25).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), plan.at);
        String tip = m.tempTip(last.plusDays(16), 0.3, EN);
        check("#252e touching a high night reads it", tip.contains("\nRing: high night 1 of 3, 0.28 °C above the line"), tip);
        tip = m.tempTip(last.plusDays(15), 0.0, EN);
        check("#252e touching the ovulation day and a baseline night", tip.contains("\nRing: likely ovulation, the day before the rise")
                && m.tempTip(last.plusDays(14), 0.0, EN).contains("\nRing: baseline night 5 of 6, the highest: the line"), tip);
        check("#252e a night outside the rise reads as before", !m.tempTip(last.plusDays(3), 0.0, EN).contains("Ring:"));
        // with only 2 cycles of rise: the usual 14 days
        TreeMap<java.time.LocalDate, Double> two = new TreeMap<java.time.LocalDate, Double>(ring.tailMap(C0.plusDays(28 * 4)));
        m = cal.measured(two, null);
        check("#252e two cycles with a rise: not enough for the own time, the usual 14 days", !m.rise.ownLuteal() && m.rise.lutealN == 2 && m.luteal == 14
                && m.method == Cycles.MEASURED && m.next.equals(last.plusDays(29)) && m.range == 3, m.range);
        check("#252e the line says the usual 14 days", m.expectedLine(EN).contains("plus about 14 days, the usual time from ovulation to the period, until 3 cycles with a rise give your own"));
        // fallback: no temperature at all is the calendar estimate, the same numbers
        m = cal.measured(null, new TreeMap<java.time.LocalDate, Double>());
        check("#252e no temperatures: the calendar estimate, unchanged", m.method == Cycles.CALENDAR && m.next.equals(cal.next) && m.range == cal.range
                && m.ovulation.equals(cal.ovulation) && m.ovulationRange == cal.ovulationRange && m.ovulationLine(EN).equals(cal.ovulationLine(EN) + " No temperatures in this cycle to measure it.")
                && cal.method == Cycles.CALENDAR && cal.rise == null);
        check("#252e no temperatures: each cycle says so", TempRise.line(m.rise.cycles.get(0), m, EN).equals("No temperatures in this cycle."));
        // the mouth alone, and both: agree (clear), disagree (possible, not for the own time)
        TreeMap<java.time.LocalDate, Double> mouth = new TreeMap<java.time.LocalDate, Double>();
        for (Map.Entry<java.time.LocalDate, Double> e : ring.entrySet()) mouth.put(e.getKey(), e.getValue() + 1.3);
        m = cal.measured(null, mouth);
        check("#252e the mouth alone: found, from the mouth", m.method == Cycles.MEASURED && m.rise.cycles.get(0).source == TempRise.MOUTH
                && m.ovulationLine(EN).contains("measured by your temperatures by mouth"));
        m = cal.measured(ring, mouth);
        check("#252e ring and mouth agree: clear, from both", m.rise.cycles.get(0).source == TempRise.BOTH && m.rise.cycles.get(0).confidence == TempRise.CLEAR
                && !m.rise.cycles.get(0).disagree && TempRise.line(m.rise.cycles.get(0), m, EN).endsWith(", a clear rise in your ring's and your mouth temperatures, 13 days before the next period."));
        TreeMap<java.time.LocalDate, Double> late = new TreeMap<java.time.LocalDate, Double>();
        for (int i = 0; i < 28; i++) late.put(C0.plusDays(i), 36.4 + (i >= 20 ? 0.3 : 0) + (i % 3 - 1) * 0.02);
        m = cal.measured(ring, late);
        TempRise.Result dis = m.rise.cycles.get(0);
        check("#252e ring and mouth 4 days apart: possible, said so, left out of the own time", dis.disagree && dis.confidence == TempRise.POSSIBLE
                && dis.ovulation.equals(C0.plusDays(15)) && m.rise.lutealN == 5
                && TempRise.line(dis, m, EN).contains(": ring and mouth disagree by 4 days"), dis + " " + m.rise.lutealN);
        // "My periods have stopped": no estimate, also measured
        m = Cycles.of(log, null, today, today.minusDays(1)).measured(ring, null);
        check("#252e periods stopped: no estimate even with a rise", !m.estimates() && m.ovulationLine(EN) == null);
        // a left out cycle (maybe a period was not logged) has no result and no luteal length
        Map<java.time.LocalDate, Integer> miss = log252(5, 28, 28, 28, 56, 28);
        m = Cycles.of(miss, null, lastStart252(28, 28, 28, 56, 28).plusDays(5)).measured(ring, null);
        check("#252e a left out cycle: no result for it", m.cycles.get(3).leftOut && m.rise.at(m.cycles.get(3).start) == null && m.rise.cycles.size() == 5);
        // a few nights only
        m = cal.measured(series252(last, 35.2, 35.2, 35.3), null);
        check("#252e this cycle with a few nights: too few yet", TempRise.line(m.rise.current, m, EN).equals("Too few nights yet to look for a rise (9 needed)."));
        check("#252e a cycle that is over with a few nights", TempRise.line(m.rise.cycles.get(0), m, EN).equals("No temperatures in this cycle.")
                && TempRise.line(Cycles.of(log, null, today).measured(series252(C0, 35.2, 35.2, 35.3), null).rise.cycles.get(0), m, EN).equals("Too few nights to look for a rise."));
        TreeMap<java.time.LocalDate, Double> flat = new TreeMap<java.time.LocalDate, Double>();
        for (int i = 0; i < 28; i++) flat.put(C0.plusDays(i), 35.2 + (i % 3 - 1) * 0.02);
        check("#252e a cycle that is over without a rise: said gently", TempRise.line(cal.measured(flat, null).rise.cycles.get(0), m, EN)
                .equals("No clear rise found. That happens, also in cycles with an ovulation."));

        // ---- the demo: its made up mouth temperatures rise after each made up ovulation ----
        java.time.LocalDate dt = java.time.LocalDate.of(2026, 11, 2);
        TreeMap<java.time.LocalDate, Double> dm = new TreeMap<java.time.LocalDate, Double>();
        for (Map.Entry<java.time.LocalDate, DripCsv.Temp> e : DripCsv.demoMouth(dt, Cycles.demoWarmNights(dt)).entrySet()) if (!e.getValue().excluded) dm.put(e.getKey(), e.getValue().c);
        Cycles demo = Cycles.of(Cycles.demo(dt), null, dt).measured(null, dm);
        int demoFound = 0;
        boolean onDay = true;
        for (TempRise.Result r : demo.rise.cycles) {
            if (!r.found()) continue;
            demoFound++;
            java.time.LocalDate want = r.next != null ? r.next.minusDays(Cycles.LUTEAL) : demo.calendarNext.minusDays(Cycles.LUTEAL);
            onDay &= Math.abs(java.time.temporal.ChronoUnit.DAYS.between(want, r.ovulation)) <= 1;
        }
        check("#252e demo: the mouth temperatures show the rise in the cycles they cover and in this one, within a day of the made up ovulation",
                demoFound >= 4 && onDay && demo.rise.current.found() && demo.method == Cycles.MEASURED && demo.rise.ownLuteal(), demoFound + " " + demo.rise.cycles);

        // ---- texts: plain, explained ----
        m = cal.measured(ring, null);
        List<String> tx = new ArrayList<String>(Arrays.asList(m.ovulationLine(EN), m.expectedLine(EN), m.fertileLine(EN), m.describe(last.plusDays(16), EN),
                app.openring.ui.Explain.periodRise(0.12, 0.08), app.openring.ui.Explain.periodRise(0.2, Double.NaN), app.openring.ui.Explain.periodFound(6, 4),
                app.openring.ui.Explain.periodFound(1, 0), app.openring.ui.Explain.periodLuteal(2, 14, Double.NaN), app.openring.ui.Explain.periodLuteal(6, 12, 0.8),
                app.openring.ui.Explain.periodOvulation(m), app.openring.ui.Explain.periodRange(m), app.openring.ui.Explain.PERIOD_TEMP));
        for (TempRise.Result r : m.rise.cycles) tx.add(TempRise.line(r, m, EN));
        boolean plain = true;
        for (String t : tx) plain &= t != null && plain252(t);
        check("#252e texts use no dashes as punctuation", plain);
        String rule = app.openring.ui.Explain.periodRise(0.12, 0.08);
        check("#252e explain: the rule with its numbers, the limits, not birth control", rule.startsWith("Ovulation from your temperature: three over six, the classic rule.") && rule.contains("the third at least 0.12 °C for your ring, 0.10 °C by mouth")
                && rule.contains("varies by about 0.12 °C") && rule.contains("Illness, alcohol, travel and a warm room move the temperature too")
                && rule.contains("not birth control and not a medical diagnosis"), rule);
        check("#252e explain: the own luteal length and how many cycles", app.openring.ui.Explain.periodLuteal(6, 12, 0.8).contains("your 6 newest cycles with a rise, which vary by about 0.8 days")
                && app.openring.ui.Explain.periodLuteal(2, 14, Double.NaN).contains("(2 so far), the estimate uses the usual 14 days."));
        check("#252e explain: the measured ranges", app.openring.ui.Explain.periodOvulation(m).startsWith("Ovulation day: give or take 1 day, from a clear rise")
                && app.openring.ui.Explain.periodRange(m).startsWith("Next period: measured, the ovulation day plus 13 days, your own time")
                && app.openring.ui.Explain.periodRange(cal).equals(app.openring.ui.Explain.periodRange(cal.used.size(), cal.length, cal.range, cal.sd))
                && app.openring.ui.Explain.periodOvulation(cal).equals(app.openring.ui.Explain.periodOvulation(cal.used.size(), cal.ovulationRange)));
        m = Cycles.of(log, null, last.plusDays(14)).measured(ring, null);
        check("#252e explain: the calendar with the own time", app.openring.ui.Explain.periodOvulation(m).contains("together with how much your own time from ovulation"));
    }

    /**
     * Issue #252, owner's feedback of 3 November: the texts on how it works move into a closed "How it works" at the bottom
     * of each period screen. Each list holds exactly the texts that left the screen, for the parts the screen shows; the
     * short lines that stay are one or two sentences with the person's own counts.
     */
    static void how252() {
        java.time.LocalDate dt = java.time.LocalDate.of(2026, 11, 2);
        Cycles demo = Cycles.of(Cycles.demo(dt), null, dt);
        List<String> card = app.openring.ui.Explain.periodCardHow(demo);
        check("#252h Period card: how the range, the ovulation range and vary by are worked out, in the card's order",
                card.equals(Arrays.asList(app.openring.ui.Explain.periodRange(demo), app.openring.ui.Explain.periodOvulation(demo),
                        app.openring.ui.Explain.periodVary(demo.used.size()))), String.valueOf(card));
        List<String> cyc = app.openring.ui.Explain.periodCyclesHow(demo);
        check("#252h Your cycles: vary by, the cycle chart, the estimate's window, the period charts, then not birth control; no rise without temperatures",
                cyc.equals(Arrays.asList(app.openring.ui.Explain.periodVary(demo.used.size()), app.openring.ui.Explain.PERIOD_DOTS_CYCLES,
                        app.openring.ui.Explain.PERIOD_WINDOW, app.openring.ui.Explain.PERIOD_DOTS_PERIODS, app.openring.ui.Explain.PERIOD_LENGTH_DAYS,
                        Cycles.NOT_ADVICE)), String.valueOf(cyc));
        // with the demo's mouth temperatures: the measured texts and the rule with both noises
        TreeMap<java.time.LocalDate, Double> dm = new TreeMap<java.time.LocalDate, Double>();
        for (Map.Entry<java.time.LocalDate, DripCsv.Temp> e : DripCsv.demoMouth(dt, Cycles.demoWarmNights(dt)).entrySet()) if (!e.getValue().excluded) dm.put(e.getKey(), e.getValue().c);
        Cycles m = demo.measured(null, dm);
        card = app.openring.ui.Explain.periodCardHow(m);
        cyc = app.openring.ui.Explain.periodCyclesHow(m);
        check("#252h measured: the card explains the measured range, Your cycles holds the rule with the mouth's noise",
                m.method == Cycles.MEASURED && card.get(0).startsWith("Next period: measured, the ovulation day plus") && card.get(1).startsWith("Ovulation day: give or take")
                        && cyc.contains(app.openring.ui.Explain.periodRise(m.rise.ringNoise, m.rise.mouthNoise)) && cyc.indexOf(app.openring.ui.Explain.PERIOD_WINDOW) < cyc.indexOf(app.openring.ui.Explain.PERIOD_DOTS_PERIODS)
                        && cyc.get(cyc.size() - 1).equals(Cycles.NOT_ADVICE), String.valueOf(cyc));
        boolean labelled = true;
        for (Cycles x : new Cycles[] {demo, m}) {
            List<String> k = app.openring.ui.Explain.periodCardHow(x);
            labelled &= k.get(0).startsWith("Next period: ") && k.get(1).startsWith("Ovulation day: ") && k.get(2).startsWith("Vary by: ");
        }
        check("#252h every paragraph says what it is about: Next period, Ovulation day, Vary by, Ovulation from your temperature", labelled
                && app.openring.ui.Explain.periodRange(0, 29, 5, Double.NaN).startsWith("Next period: typical cycle 29 days")
                && app.openring.ui.Explain.periodOvulation(2, 5).startsWith("Ovulation day: give or take 5 days, from a typical spread")
                && app.openring.ui.Explain.periodRise(0.12, Double.NaN).startsWith("Ovulation from your temperature: "));
        // nothing logged: Your cycles shows only its empty line, so no How it works there
        Cycles none = Cycles.of(new HashMap<java.time.LocalDate, Integer>(), null, dt);
        check("#252h nothing logged: no How it works in Your cycles", app.openring.ui.Explain.periodCyclesHow(none).isEmpty()
                && app.openring.ui.Explain.periodCyclesHow(null).isEmpty() && app.openring.ui.Explain.periodCardHow(null).isEmpty());
        check("#252h nothing logged: the card explains only what it shows", app.openring.ui.Explain.periodCardHow(none).contains(app.openring.ui.Explain.periodRange(none)) == (none.nextLine() != null)
                && !app.openring.ui.Explain.periodCardHow(none).contains(app.openring.ui.Explain.periodVary(0)));
        // one period that is over: no cycle yet, so no cycle chart texts; its length is explained
        Cycles one = Cycles.of(log252(5), null, C0.plusDays(12));
        cyc = app.openring.ui.Explain.periodCyclesHow(one);
        check("#252h one period: the period charts only, then not birth control", one.cycles.isEmpty() && !cyc.contains(app.openring.ui.Explain.PERIOD_DOTS_CYCLES)
                && !cyc.contains(app.openring.ui.Explain.PERIOD_WINDOW) && cyc.contains(app.openring.ui.Explain.PERIOD_DOTS_PERIODS) && cyc.contains(app.openring.ui.Explain.PERIOD_LENGTH_DAYS)
                && cyc.get(cyc.size() - 1).equals(Cycles.NOT_ADVICE), String.valueOf(cyc));
        // periods stopped: no next period line on the card, so its range is not explained
        Map<java.time.LocalDate, Integer> log = log252(5, 28, 28, 28, 28);
        java.time.LocalDate today = lastStart252(28, 28, 28, 28).plusDays(10);
        Cycles stopped = Cycles.of(log, null, today, today.minusDays(1));
        check("#252h periods stopped: no range or ovulation text", stopped.nextLine() == null && !app.openring.ui.Explain.periodCardHow(stopped).contains(app.openring.ui.Explain.periodRange(stopped)));
        // the temperature card and the drip import
        check("#252h night temperature: the long text, the mouth line only with drip temperatures",
                app.openring.ui.Explain.periodTempHow(false).equals(Arrays.asList(app.openring.ui.Explain.PERIOD_TEMP))
                        && app.openring.ui.Explain.periodTempHow(true).equals(Arrays.asList(app.openring.ui.Explain.PERIOD_TEMP, app.openring.ui.Explain.PERIOD_MOUTH)));
        check("#252h drip: how periods are counted, then the parts shown",
                app.openring.ui.Explain.dripHow(false, false).equals(Arrays.asList(app.openring.ui.Explain.DRIP_COUNTS))
                        && app.openring.ui.Explain.dripHow(true, true).equals(Arrays.asList(app.openring.ui.Explain.DRIP_COUNTS, app.openring.ui.Explain.DRIP_OTHER, app.openring.ui.Explain.DRIP_SHORT)));
        // what stays in view: short, with the person's own counts
        String[] stay = {app.openring.ui.Explain.periodDots(true), app.openring.ui.Explain.periodDots(false), app.openring.ui.Explain.PERIOD_TEMP_LINE,
                app.openring.ui.Explain.periodCycles(8, 0), app.openring.ui.Explain.periodLengths(7)};
        boolean shortLines = true;
        for (String x : stay) shortLines &= x.length() <= 120 && x.split("\\. ").length <= 2;
        check("#252h the lines in view are one or two short sentences", shortLines);
        check("#252h the lines in view keep the counts", app.openring.ui.Explain.periodCycles(8, 0).contains("all your 8 cycles")
                && app.openring.ui.Explain.periodLengths(7).contains("your 7 periods that are over") && app.openring.ui.Explain.periodCycles(9, 2).contains("2 cycles more than 10 days"));
        List<String> all = new ArrayList<String>(Arrays.asList(stay));
        all.addAll(app.openring.ui.Explain.periodCyclesHow(m));
        all.addAll(app.openring.ui.Explain.periodCardHow(m));
        all.addAll(app.openring.ui.Explain.periodTempHow(true));
        all.addAll(app.openring.ui.Explain.dripHow(true, true));
        boolean plain = true;
        for (String x : all) plain &= x != null && !x.isEmpty() && plain252(x);
        check("#252h texts use no dashes as punctuation", plain);
    }

    // ---------------------------------------------------------------- issue #252, phase 1b: import from drip

    static java.time.LocalDate ld(String s) {
        return java.time.LocalDate.parse(s);
    }

    static DripCsv.File drip(String text) {
        try {
            return DripCsv.parse(text, ld("2026-11-03"));
        } catch (DripCsv.Refused e) {
            throw new AssertionError(e.getMessage());
        }
    }

    static String refused(String text) {
        try {
            DripCsv.parse(text, ld("2026-11-03"));
            return null;
        } catch (DripCsv.Refused e) {
            return e.sentence();
        }
    }

    static void drip252() {
        java.time.LocalDate today = ld("2026-11-03");
        // ---- a file with every column, as public converters write drip's file ----
        String all = "date,temperature.value,temperature.exclude,temperature.time,temperature.note,bleeding.value,bleeding.exclude,mucus.feeling,"
                + "mucus.texture,mucus.value,mucus.exclude,cervix.opening,cervix.firmness,cervix.position,cervix.exclude,note.value,desire.value,"
                + "sex.solo,sex.partner,sex.condom,sex.pill,sex.iud,sex.patch,sex.ring,sex.implant,sex.diaphragm,sex.none,sex.other,sex.note,"
                + "pain.cramps,pain.ovulationPain,pain.headache,pain.backache,pain.nausea,pain.tenderBreasts,pain.migraine,pain.other,pain.note,"
                + "mood.happy,mood.sad,mood.stressed,mood.balanced,mood.fine,mood.anxious,mood.energetic,mood.fatigue,mood.angry,mood.other,mood.note\n"
                + "2025-03-11,36.55,false,07:05,,1,false,0,1,2,false,,,,,\"Long walk, felt fine\",,,,,,,,,,,,,,true,,,,,,,,,true,,,,,,,,,,\n"
                + "2025-03-12,36.60,false,7:10,,1,false,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\n"
                + "2025-03-20,97.7,true,06:50:12,,0,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\n"
                + "2025-03-22,,,,,2,true,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,true,,,,,,,,,,\n";
        DripCsv.File f = drip(all);
        eq("#252d every column: bleeding days by date with their amounts", f.flows.toString(), "{2025-03-11=1, 2025-03-12=1, 2025-03-20=0, 2025-03-22=0}");
        check("#252d every column: bleeding drip did not count is kept as spotting and counted", f.flows.get(ld("2025-03-22")) == 0 && f.excludedBleeding == 1);
        check("#252d every column: temperatures in °C with time and exclude", f.temps.size() == 3 && f.temps.get(ld("2025-03-11")).c == 36.55
                && "07:05".equals(f.temps.get(ld("2025-03-11")).time) && "07:10".equals(f.temps.get(ld("2025-03-12")).time) && !f.temps.get(ld("2025-03-11")).excluded);
        DripCsv.Temp fahr = f.temps.get(ld("2025-03-20"));
        check("#252d °F above 45 is converted: 97.7 °F is 36.5 °C, its time cut to minutes, drip's exclude kept", fahr.c == 36.5 && "06:50".equals(fahr.time)
                && fahr.excluded && f.fahrenheit == 1);
        eq("#252d other columns are counted per kind, not stored", f.other.toString(), "{mood=2, mucus=1, note=1, pain=1}");
        eq("#252d the summary names them", DripCsv.notImported(f), "Not imported: 2 mood entries, 1 mucus entry, 1 note entry, 1 pain entry.");
        check("#252d 4 rows, nothing left out", f.rows == 4 && f.duplicates == 0 && f.unreadable == 0 && f.future == 0 && f.badValues == 0);

        // ---- by name, not by position: reordered, missing and extra columns ----
        DripCsv.File g = drip("bleeding.value,extra.thing,date\n3,x,2025-01-02\n2,,2025-01-01\n");
        eq("#252d reordered columns, missing temperature columns, an extra column", g.flows.toString(), "{2025-01-01=2, 2025-01-02=3}");
        check("#252d no temperature columns: no temperatures", g.temps.isEmpty() && g.other.get("extra") == 1);
        DripCsv.File h = drip(" Date , BLEEDING.VALUE \n2025-01-01,1\n");
        check("#252d column names with spaces and capitals", h.flows.size() == 1);
        DripCsv.File shortRow = drip("date,bleeding.value,temperature.value\n2025-01-01\n2025-01-02,1\n");
        check("#252d a row shorter than the header (missing cells) is read with what it has", shortRow.rows == 2 && shortRow.flows.size() == 1);

        // ---- BOM, Windows and old Mac line ends, quotes, empty lines ----
        DripCsv.File w = drip("﻿date,bleeding.value,note.value\r\n\r\n2025-02-01,1,\"a, b\r\nc \"\"quoted\"\"\"\r\n,,\r\n2025-02-02,2,\r\n");
        check("#252d a BOM and Windows line ends, a quoted note with a comma, a line break and quotes, empty lines", w.flows.size() == 2 && w.rows == 2
                && w.other.get("note") == 1 && w.unreadable == 0);
        DripCsv.File mac = drip("date,bleeding.value\r2025-02-01,1\r2025-02-02,1");
        check("#252d old Mac line ends and no line end at the end", mac.flows.size() == 2);
        List<List<String>> rec = DripCsv.records("a,\"b\"\"c\",\"d,e\"\n\"open");
        check("#252d CSV records: doubled quotes, a quoted comma, an unclosed quote running to the end", rec.size() == 2 && rec.get(0).get(1).equals("b\"c")
                && rec.get(0).get(2).equals("d,e") && rec.get(1).get(0).equals("open"));

        // ---- rows in any order, duplicates (the last wins), unreadable, future, bad values ----
        DripCsv.File d = drip("date,bleeding.value,temperature.value\n2025-05-03,1,36.4\n2025-05-01,3,\n2025-05-03,2,\n2025-5-4,1,\nnot a date,1,\n2027-01-01,1,\n"
                + "2025-05-05,7,\n2025-05-06,1.5,\n2025-05-07,x,\n2025-05-08,,12\n2025-05-09,,36,7\n2025-05-10,,\"36,8\"\n2025-02-30,1,\n");
        eq("#252d rows in any order, the last row of a date wins as a whole (its temperature too)", d.flows.toString(), "{2025-05-01=3, 2025-05-03=2}");
        check("#252d the duplicate is counted", d.duplicates == 1);
        check("#252d unreadable dates (2025-5-4, text, 30 Feb) and a day after today are counted and left out", d.unreadable == 3 && d.future == 1);
        check("#252d amounts 7, 1.5 and x and a temperature of 12 are not readings, left out and counted", d.badValues == 4);
        check("#252d a comma as decimal point in a quoted cell is read, a bare one splits the row", d.temps.containsKey(ld("2025-05-10")) && d.temps.get(ld("2025-05-10")).c == 36.8
                && d.temps.get(ld("2025-05-09")).c == 36.0 && !d.temps.containsKey(ld("2025-05-03")));
        List<String> notes = DripCsv.notes(d);
        check("#252d the notes say what was left out, each with its number", notes.contains("1 date came twice, the last row of each counts.")
                && notes.contains("3 rows have no readable date and are left out.") && notes.contains("1 row is dated after today and left out.")
                && notes.contains("4 values are not a bleeding amount or temperature this app can read, left out."));
        check("#252d the °F note", DripCsv.notes(f).contains("1 temperature was in °F and is shown in °C."));
        check("#252d a time that is not a time is dropped, the reading kept", drip("date,bleeding.value,temperature.value,temperature.time\n2025-01-01,,36.5,25:00\n").temps.get(ld("2025-01-01")).time == null);
        check("#252d values in range edges: 34 and 43 °C kept, 33.9 and 109.5 °F (43.1 °C) left out",
                drip("date,bleeding.value,temperature.value\n2025-01-01,,34\n2025-01-02,,43\n2025-01-03,,33.9\n2025-01-04,,109.5\n").temps.size() == 2);

        // ---- refused files: a plain sentence, nothing read ----
        eq("#252d a file without a date column is refused", refused("day,bleeding.value\n2025-01-01,1\n"), DripCsv.NOT_DRIP);
        eq("#252d a file without bleeding.value is refused", refused("date,temperature.value\n2025-01-01,36.5\n"), DripCsv.NOT_DRIP);
        eq("#252d an empty file is refused", refused(""), DripCsv.EMPTY);
        eq("#252d a file of empty lines is refused", refused("﻿\r\n\r\n ,\n"), DripCsv.EMPTY);
        eq("#252d a binary file is refused", refused("PK\u0003\u0004\0\0"), DripCsv.NOT_TEXT);
        eq("#252d no text at all is refused", refused(null), DripCsv.EMPTY);
        check("#252d a header only file is read with nothing in it", drip("date,bleeding.value\n").rows == 0);

        // ---- the preview: periods by Cycles' rules, the short ones flagged ----
        java.util.TreeMap<java.time.LocalDate, Integer> file = new java.util.TreeMap<java.time.LocalDate, Integer>();
        for (int k = 0; k < 5; k++) file.put(ld("2025-01-05").plusDays(k), k < 2 ? 3 : 2);
        file.put(ld("2025-02-02"), 2); // a 1 day period
        file.put(ld("2025-03-11"), 1);
        file.put(ld("2025-03-12"), 1); // a 2 day period
        file.put(ld("2025-03-15"), 0); // spotting never makes it longer
        for (int k = 0; k < 4; k++) file.put(ld("2025-04-08").plusDays(k), 2);
        java.util.TreeMap<java.time.LocalDate, DripCsv.Temp> temps = new java.util.TreeMap<java.time.LocalDate, DripCsv.Temp>();
        temps.put(ld("2025-01-01"), new DripCsv.Temp(36.4, "07:00", false));
        temps.put(ld("2025-04-20"), new DripCsv.Temp(36.7, null, true));
        java.util.Map<java.time.LocalDate, Integer> none = new java.util.HashMap<java.time.LocalDate, Integer>();
        java.util.Map<java.time.LocalDate, DripCsv.Temp> noT = new java.util.HashMap<java.time.LocalDate, DripCsv.Temp>();
        java.util.Map<java.time.LocalDate, Boolean> noG = new java.util.HashMap<java.time.LocalDate, Boolean>();
        DripImport.Preview pv = DripImport.preview(file, temps, none, noT, noG, today);
        check("#252d preview: 4 periods by Cycles' rules, 12 bleeding days, 1 spotting day, 2 temperatures", pv.cycles.periods.size() == 4
                && pv.bleedingDays == 12 && pv.spottingDays == 1 && pv.tempCount == 2);
        eq("#252d preview summary: the range runs from the first to the last day with an entry or a temperature", pv.summary(EN),
                "From 1 Jan 2025 to 20 Apr 2025: 4 periods, 12 bleeding days, 2 temperatures.");
        check("#252d preview: the 1 and the 2 day period are flagged, each on its own line", pv.shorts.size() == 2);
        eq("#252d the 1 day line", pv.shorts.get(0).line(EN), "Period of 1 day starting 2 Feb 2025 (medium)");
        eq("#252d the 2 day line, as the owner wrote it", pv.shorts.get(1).line(EN), "Period of 2 days starting 11 Mar 2025 (light, light)");
        eq("#252d spotting line", pv.spottingLine(0), "Also 1 spotting day.");
        eq("#252d spotting line with bleeding drip did not count", pv.spottingLine(1), "Also 1 spotting day (1 of them bleeding drip did not count as a period).");
        check("#252d nothing on the phone: every day and temperature is added", pv.addDays == 13 && pv.addTemps == 2 && pv.differ.isEmpty() && pv.anything(false)
                && pv.differLine(false) == null && pv.tempsLine() == null && pv.sameLine() == null);
        eq("#252d what Import does", pv.willDo(false), "Import adds 13 days and 2 temperatures.");
        // a gap of 3 days with a day filled in joins two periods (Correct)
        java.util.TreeMap<java.time.LocalDate, Integer> fixed = new java.util.TreeMap<java.time.LocalDate, Integer>(file);
        fixed.put(ld("2025-03-13"), 1);
        fixed.put(ld("2025-03-14"), 1);
        DripImport.Preview pf = DripImport.preview(fixed, temps, none, noT, noG, today);
        check("#252d Correct: days added to the 2 day period make it 4 days, only the 1 day period stays flagged", pf.shorts.size() == 1
                && pf.shorts.get(0).period.start.equals(ld("2025-02-02")) && pf.cycles.periods.size() == 4);
        fixed.remove(ld("2025-02-02"));
        check("#252d Correct: Nothing on the single day takes it out of the import, no flag left", DripImport.preview(fixed, temps, none, noT, noG, today).shorts.isEmpty());
        eq("#252d Correct names the nearest period, here the next one", DripImport.nearestLine(pv.cycles, pv.shorts.get(1).period, EN),
                "The next period starts 8 Apr 2025, 26 days after this one. Fill the days between to join them.");
        eq("#252d Correct names the period before when it is nearer", DripImport.nearestLine(pv.cycles, pv.shorts.get(0).period, EN),
                "The period before ends 9 Jan 2025, 23 days before this one. Fill the days between to join them.");
        java.util.TreeMap<java.time.LocalDate, Integer> two = new java.util.TreeMap<java.time.LocalDate, Integer>();
        two.put(ld("2025-06-01"), 1);
        two.put(ld("2025-06-06"), 2);
        DripImport.Preview p2 = DripImport.preview(two, noT, none, noT, noG, today);
        eq("#252d Correct: the next period, the days between", DripImport.nearestLine(p2.cycles, p2.cycles.periods.get(0), EN),
                "The next period starts 6 Jun 2025, 4 days after this one. Fill the days between to join them.");
        check("#252d no other period: no line", DripImport.nearestLine(Cycles.of(two.headMap(ld("2025-06-02")), null, today), p2.cycles.periods.get(0), EN) == null);
        // #320: dates like the period cards, the year only when it is not the year of today (2026)
        java.util.TreeMap<java.time.LocalDate, Integer> span = new java.util.TreeMap<java.time.LocalDate, Integer>();
        span.put(ld("2025-12-30"), 2); // a 1 day period in the year before
        span.put(ld("2026-01-27"), 2);
        span.put(ld("2026-01-28"), 1); // a 2 day period this year
        span.put(ld("2026-03-02"), 2); // a 1 day period this year
        java.util.Map<java.time.LocalDate, Integer> haveS = new java.util.HashMap<java.time.LocalDate, Integer>();
        haveS.put(ld("2026-01-27"), 3);
        DripImport.Preview ps = DripImport.preview(span, noT, haveS, noT, noG, today);
        eq("#320 summary across two years: the year only on the date of the other year", ps.summary(EN),
                "From 30 Dec 2025 to 2 Mar: 3 periods, 4 bleeding days, 0 temperatures.");
        eq("#320 a short period this year has no year", ps.shorts.get(1).line(EN), "Period of 2 days starting 27 Jan (medium, light)");
        eq("#320 a short period of the year before has its year", ps.shorts.get(0).line(EN), "Period of 1 day starting 30 Dec 2025 (medium)");
        eq("#320 a differing day this year has no year", ps.differ.get(0).line(EN), "27 Jan: Open Ring Heavy, file Medium");
        eq("#320 Correct: the next period this year, no year", DripImport.nearestLine(ps.cycles, ps.shorts.get(0).period, EN),
                "The next period starts 27 Jan, 27 days after this one. Fill the days between to join them.");
        eq("#320 Correct: the period before ends in the other year, with its year", DripImport.nearestLine(ps.cycles, ps.shorts.get(1).period, EN),
                "The period before ends 30 Dec 2025, 27 days before this one. Fill the days between to join them.");
        eq("#320 Correct: the period before ends this year, no year", DripImport.nearestLine(ps.cycles, ps.shorts.get(2).period, EN),
                "The period before ends 28 Jan, 32 days before this one. Fill the days between to join them.");
        for (String t : Arrays.asList(ps.summary(EN), ps.shorts.get(0).line(EN), ps.differ.get(0).line(EN))) {
            check("#320 no ISO date left in the import lines: " + t, !t.matches(".*\\d{4}-\\d{2}-\\d{2}.*"));
        }

        // ---- against the phone's log: Open Ring wins, differences listed, the same file twice ----
        java.util.Map<java.time.LocalDate, Integer> have = new java.util.HashMap<java.time.LocalDate, Integer>();
        have.put(ld("2025-01-05"), 3); // the same
        have.put(ld("2025-01-06"), 1); // differs
        have.put(ld("2025-01-07"), Cycles.REMOVED); // "Nothing" is no entry: the file's day is added
        have.put(ld("2025-04-11"), 0); // spotting in Open Ring, medium in the file: differs
        java.util.Map<java.time.LocalDate, DripCsv.Temp> haveT = new java.util.HashMap<java.time.LocalDate, DripCsv.Temp>();
        haveT.put(ld("2025-01-01"), new DripCsv.Temp(36.4, "07:00", false)); // the same
        haveT.put(ld("2025-04-20"), new DripCsv.Temp(36.9, null, true)); // differs, from an earlier import
        DripImport.Preview ph = DripImport.preview(file, temps, have, haveT, noG, today);
        check("#252d against the log: 1 the same, 2 differ, 10 added", ph.sameDays == 1 && ph.differ.size() == 2 && ph.addDays == 10);
        eq("#252d the differences in order", ph.differ.get(0).line(EN) + " / " + ph.differ.get(1).line(EN),
                "6 Jan 2025: Open Ring Light, file Heavy / 11 Apr 2025: Open Ring Spotting, file Medium");
        eq("#252d differ line, Open Ring kept", ph.differLine(false), "2 days differ, Open Ring's entries are kept.");
        eq("#252d differ line, the file's values taken", ph.differLine(true), "2 days differ, the file's values replace Open Ring's. Undoing the import puts Open Ring's back.");
        check("#252d with the file's values 12 days are written, without 10", ph.writes(true) == 12 && ph.writes(false) == 10);
        eq("#252d what Import does with the file's values", ph.willDo(true), "Import adds 12 days and 0 temperatures, 2 of the days replacing Open Ring's.");
        eq("#252d same line", ph.sameLine(), "1 day is the same in Open Ring and in the file.");
        eq("#252d temperatures already there are never replaced", ph.tempsLine(), "1 temperature is already in Open Ring. 1 temperature differs from an earlier import, those are kept.");
        java.util.Map<java.time.LocalDate, Boolean> gone = new java.util.HashMap<java.time.LocalDate, Boolean>();
        gone.put(ld("2025-04-20"), true);
        check("#252d a temperature of an undone import is no reading: the file's is added", DripImport.preview(file, temps, have, haveT, gone, today).addTemps == 1);
        // the same file twice: the phone holds what the first import wrote
        java.util.Map<java.time.LocalDate, Integer> after = new java.util.HashMap<java.time.LocalDate, Integer>(file);
        DripImport.Preview twice = DripImport.preview(file, temps, after, new java.util.HashMap<java.time.LocalDate, DripCsv.Temp>(temps), noG, today);
        check("#252d the same file twice: nothing to write, nothing differs", !twice.anything(false) && !twice.anything(true) && twice.sameDays == 13
                && twice.differ.isEmpty() && twice.sameTemps == 2);
        eq("#252d and it says so", twice.willDo(true), DripImport.NOTHING_NEW);
        check("#252d the decisions one by one", DripImport.day(2, null) == DripImport.ADD && DripImport.day(2, -1) == DripImport.ADD && DripImport.day(2, 2) == DripImport.SAME
                && DripImport.day(2, 0) == DripImport.DIFFER && DripImport.temp(temps.get(ld("2025-01-01")), null, false) == DripImport.ADD);
        check("#252d a temperature with another time or exclude mark differs", !new DripCsv.Temp(36.4, "07:00", false).same(new DripCsv.Temp(36.4, "07:01", false))
                && !new DripCsv.Temp(36.4, null, false).same(new DripCsv.Temp(36.4, null, true)) && new DripCsv.Temp(36.4, null, false).same(new DripCsv.Temp(36.401, null, false))
                && !new DripCsv.Temp(36.4, null, false).same(null));
        check("#252d an empty file previews as empty", DripImport.preview(none, noT, none, noT, noG, today).summary(EN).equals("This file holds no bleeding day and no temperature."));
        java.util.Map<java.time.LocalDate, Integer> odd = new java.util.HashMap<java.time.LocalDate, Integer>();
        odd.put(ld("2025-01-01"), 9);
        odd.put(ld("2025-01-02"), null);
        check("#252d amounts that are no amount are not in the preview", DripImport.preview(odd, noT, none, noT, noG, today).flows.isEmpty());

        // ---- the demo's made up file (screens.sh) ----
        DripCsv.File demo = drip(DripCsv.demo(today));
        java.util.Map<java.time.LocalDate, Integer> demoLog = new java.util.HashMap<java.time.LocalDate, Integer>(Cycles.demo(today));
        DripImport.Preview dp = DripImport.preview(demo.flows, demo.temps, demoLog, noT, noG, today);
        check("#252d demo file: a BOM, Windows line ends, newest row first, read by name", DripCsv.demo(today).startsWith("﻿date,") && DripCsv.demo(today).contains("\r\n")
                && demo.rows > 150 && demo.unreadable == 0 && demo.badValues == 0);
        check("#252d demo file: a 1 and a 2 day period flagged, one day that differs from the demo log, four the same", dp.shorts.size() == 2 && dp.differ.size() == 1
                && dp.sameDays == 4 && dp.shorts.get(0).period.days() == 2 && dp.shorts.get(1).period.days() == 1);
        check("#252d demo file: bleeding drip did not count, one °F, one temperature left out, mood, note and mucus counted", demo.excludedBleeding == 1 && demo.fahrenheit == 1
                && demo.other.containsKey("mood") && demo.other.containsKey("note") && demo.other.containsKey("mucus") && !demo.other.containsKey("cervix")
                && demo.temps.size() == demo.rows);
        int excl = 0;
        for (DripCsv.Temp t : demo.temps.values()) if (t.excluded) excl++;
        check("#252d demo file: one temperature left out in drip", excl == 1);
        java.util.TreeMap<java.time.LocalDate, DripCsv.Temp> mouth = DripCsv.demoMouth(today, Cycles.demoWarmNights(today));
        check("#252d demo mouth temperatures: the last 100 days with some missing, warmer after the made up ovulation", mouth.size() > 90 && mouth.size() < 100
                && mouth.lastKey().equals(today) && mouth.firstKey().equals(today.minusDays(99)));

        // ---- the night temperature line's touch text with the mouth reading ----
        Cycles cy = Cycles.of(Cycles.demo(today), null, today);
        java.time.LocalDate t1 = today.minusDays(3);
        String tip = cy.tempTip(t1, 0.2, 36.62, "07:10", false, EN);
        check("#252d touch: date and cycle day, then the ring and the mouth reading on a second line", tip.startsWith(t1.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", EN)) + ", cycle day ")
                && tip.endsWith("\nRing +0.2 °C, mouth 36.62 °C at 07:10"));
        check("#252d touch: no ring night, a reading drip left out, no time", cy.tempTip(t1, Double.NaN, 36.5, null, true, EN).endsWith("\nNo ring night, mouth 36.50 °C, left out in drip")
                && cy.tempTip(t1, 0.1, Double.NaN, null, false, EN).endsWith("\nRing +0.1 °C, no mouth reading"));
        check("#252d touch without drip readings stays as before", cy.tempTip(t1, 0.2, EN).startsWith(t1.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", EN)) + ": +0.2 °C, cycle day " + cy.cycleDay(t1))
                && !cy.tempTip(t1, 0.2, EN).contains("\n"));

        // ---- ten years in one file ----
        StringBuilder big = new StringBuilder("date,bleeding.value,temperature.value,temperature.time,mood.happy,note.value\r\n");
        java.time.LocalDate s0 = ld("2016-01-01");
        int n = 0;
        for (java.time.LocalDate x = s0; x.isBefore(ld("2026-01-01")); x = x.plusDays(1), n++) {
            big.append(x).append(',').append(n % 28 < 5 ? String.valueOf(1 + n % 3) : "").append(",36.").append(40 + n % 30).append(",07:00,").append(n % 5 == 0 ? "true" : "")
                    .append(',').append(n % 50 == 0 ? "\"tired, slept late\"" : "").append("\r\n");
        }
        long t0 = System.nanoTime();
        DripCsv.File ten = drip(big.toString());
        DripImport.Preview tp = DripImport.preview(ten.flows, ten.temps, none, noT, noG, today);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("    #252d a 10 year drip file (%d rows): read and previewed in %.2f s%n", n, sec);
        check("#252d a 10 year file: every row, about 130 periods, read and previewed in seconds", ten.rows == n && ten.temps.size() == n && tp.cycles.periods.size() == (n + 27) / 28
                && tp.bleedingDays == ten.flows.size() && sec < 10);

        // ---- plain texts ----
        List<String> tx = new ArrayList<String>(Arrays.asList(DripCsv.EMPTY, DripCsv.NOT_TEXT, DripCsv.NOT_DRIP, DripImport.NOTHING_NEW, pv.summary(EN), ph.differLine(true),
                ph.differLine(false), ph.tempsLine(), ph.willDo(true), pv.shorts.get(1).line(EN), DripCsv.notImported(f), tip,
                app.openring.ui.Explain.DRIP_SETTINGS, app.openring.ui.Explain.DRIP_INTRO, app.openring.ui.Explain.DRIP_DEMO, app.openring.ui.Explain.DRIP_COUNTS,
                app.openring.ui.Explain.DRIP_OTHER, app.openring.ui.Explain.DRIP_SHORT, app.openring.ui.Explain.DRIP_DIFFER, app.openring.ui.Explain.DRIP_CORRECT,
                app.openring.ui.Explain.DRIP_UNDO, app.openring.ui.Explain.PERIOD_MOUTH, app.openring.ui.Explain.dripAdded(1, 1), app.openring.ui.Explain.dripAdded(186, 312)));
        tx.addAll(DripCsv.notes(d));
        boolean plain = true;
        for (String t : tx) plain &= t != null && plain252(t);
        check("#252d texts use no dashes as punctuation", plain);
        eq("#252d the Settings line", app.openring.ui.Explain.dripAdded(1, 186), "Added 1 day and 186 temperatures.");
        check("#252d the intro says nothing is saved before Import (#320: the note about estimates stays on the period cards)", app.openring.ui.Explain.DRIP_INTRO.contains("Nothing is saved until you tap Import"));
    }

    // ---------------------------------------------------------------- issue #265: badges

    static final java.time.LocalDate D265 = java.time.LocalDate.of(2026, 9, 1);

    /** {@code n} empty dates from {@link #D265} on. */
    static List<Badges.Day> days265(int n) {
        List<Badges.Day> out = new ArrayList<Badges.Day>();
        for (int i = 0; i < n; i++) out.add(new Badges.Day(D265.plusDays(i)));
        return out;
    }

    /** The badges of one kind, as "index: text". */
    static List<String> kind265(List<Badges.Day> days, int kind) {
        List<String> out = new ArrayList<String>();
        for (Badges.Badge b : Badges.of(days)) {
            if (b.kind == kind) out.add(java.time.temporal.ChronoUnit.DAYS.between(D265, b.date) + ": " + b.text);
        }
        return out;
    }

    static List<Integer> at265(List<Badges.Day> days, int kind) {
        List<Integer> out = new ArrayList<Integer>();
        for (Badges.Badge b : Badges.of(days)) if (b.kind == kind) out.add((int) java.time.temporal.ChronoUnit.DAYS.between(D265, b.date));
        return out;
    }

    static void worn265(Badges.Day d, double steps) {
        d.steps = steps;
        d.coveredMin = 900;
        d.restMin = 450;
    }

    static boolean plain265(String t) {
        return t != null && !t.isEmpty() && !t.contains(" - ") && !t.contains("–") && !t.contains("—") && !t.contains("--");
    }

    static void badges265() {
        int B = Badges.BEST_NIGHT, R = Badges.RESTFUL_WEEK, C = Badges.RECOVERED, P = Badges.PHONE_FREE, A = Badges.ABOVE_USUAL,
                G = Badges.GOAL, S = Badges.CALM_SCREEN;
        eq("#265 nothing in, nothing out", Badges.of(new ArrayList<Badges.Day>()).size(), 0);

        // ---- best night: highest of the last 30 calendar days, at least 14 nights, ties do not count ----
        List<Badges.Day> d = days265(40);
        for (int i = 0; i < 13; i++) d.get(i).sleepScore = 80;
        d.get(12).sleepScore = 90;
        eq("#265 best night: 13 nights are not enough", at265(d, B), Collections.emptyList());
        d.get(13).sleepScore = 91;
        eq("#265 best night: the 14th night higher than all before earns it, with the earlier best and its date", kind265(d, B),
                Arrays.asList("13: Best night in 30 days: score 91 (before: 90 on 13 Sep)"));
        d.get(14).sleepScore = 91;
        eq("#265 best night: equal to the best before is no badge", at265(d, B), Arrays.asList(13));
        d.get(14).sleepScore = 92;
        eq("#265 best night: one point more is", at265(d, B), Arrays.asList(13, 14));
        // the window: a better night 30 days back (outside) does not block, 29 days back (inside) does
        d = days265(60);
        for (int i = 0; i < 40; i++) d.get(i).sleepScore = 80;
        d.get(9).sleepScore = 95;
        d.get(39).sleepScore = 90;
        eq("#265 best night: a better night 30 days back is outside the window", kind265(d, B),
                Arrays.asList("39: Best night in 30 days: score 90 (before: 80 on 9 Oct)"));
        d.get(10).sleepScore = 95;
        eq("#265 best night: a better night 29 days back is inside it", at265(d, B), Collections.emptyList());
        // the earlier best in another year names its year
        List<Badges.Day> ny = new ArrayList<Badges.Day>();
        java.time.LocalDate dec = java.time.LocalDate.of(2025, 12, 12);
        for (int i = 0; i < 30; i++) {
            Badges.Day x = new Badges.Day(dec.plusDays(i));
            x.sleepScore = i == 0 ? 85 : i == 29 ? 88 : 70;
            ny.add(x);
        }
        List<Badges.Badge> nyB = Badges.of(ny);
        eq("#265 best night: the text of the design, with the year when it differs", nyB.size() == 1 ? nyB.get(0).text : nyB.toString(),
                "Best night in 30 days: score 88 (before: 85 on 12 Dec 2025)");
        check("#265 best night: its numbers", nyB.size() == 1 && nyB.get(0).value == 88 && nyB.get(0).usual == 85 && nyB.get(0).count == 30
                && dec.equals(nyB.get(0).before));

        // ---- restful week: 7 days at least 5 above the 4 weeks before, 5 and 14 nights, once in 7 days ----
        d = days265(50);
        for (int i = 0; i < 28; i++) d.get(i).sleepScore = 80;
        for (int i = 28; i < 35; i++) d.get(i).sleepScore = 84;
        eq("#265 restful week: 4 points above is no badge", at265(d, R), Collections.emptyList());
        for (int i = 28; i < 35; i++) d.get(i).sleepScore = 85;
        eq("#265 restful week: 5 points above is", kind265(d, R),
                Arrays.asList("34: Sleep score 85 on average over the last 7 days (7 nights), your usual 80 (the 4 weeks before)"));
        d.get(29).sleepScore = Double.NaN;
        d.get(30).sleepScore = Double.NaN;
        d.get(31).sleepScore = Double.NaN;
        eq("#265 restful week: 4 nights in the week are not enough", at265(d, R), Collections.emptyList());
        d.get(31).sleepScore = 85;
        eq("#265 restful week: 5 are", kind265(d, R),
                Arrays.asList("34: Sleep score 85 on average over the last 7 days (5 nights), your usual 80 (the 4 weeks before)"));
        d.get(34).sleepScore = Double.NaN;
        d.get(33).sleepScore = 85;
        d.get(32).sleepScore = 85;
        d.get(31).sleepScore = 85;
        d.get(30).sleepScore = 85;
        eq("#265 restful week: only on a date with a night", at265(d, R).contains(34), false);
        d = days265(50);
        for (int i = 14; i < 28; i++) d.get(i).sleepScore = 80;
        for (int i = 28; i < 35; i++) d.get(i).sleepScore = 90;
        eq("#265 restful week: 14 nights in the 4 weeks before are enough", at265(d, R).isEmpty(), false);
        d.get(14).sleepScore = Double.NaN;
        d.get(28).sleepScore = 80; // keeps the week at 6 nights and above
        List<Integer> thirteen = at265(d, R);
        check("#265 restful week: 13 nights in the 4 weeks before are not " + thirteen, !thirteen.contains(34) && !thirteen.contains(29));
        // the pause: a better fortnight earns it once, then again 7 days later
        d = days265(50);
        for (int i = 0; i < 28; i++) d.get(i).sleepScore = 70;
        for (int i = 28; i < 42; i++) d.get(i).sleepScore = 90;
        eq("#265 restful week: at most once in 7 days", at265(d, R), Arrays.asList(29, 36));

        // ---- recovered: back to the 4 week mean after 2 days below mean minus one SD, readiness on all of them ----
        d = days265(40);
        for (int i = 0; i < 28; i++) d.get(i).readiness = i % 2 == 0 ? 80 : 84; // mean 82, SD 2
        d.get(28).readiness = 70;
        d.get(29).readiness = 72;
        d.get(30).readiness = 83;
        eq("#265 recovered: two low days, then back to the usual", kind265(d, C),
                Arrays.asList("30: Readiness 83, back to your usual 82 (the 4 weeks before) after 2 low days in a row (70 and 72)"));
        d.get(30).readiness = 81;
        eq("#265 recovered: not yet back to the usual is no badge", at265(d, C), Collections.emptyList());
        d.get(30).readiness = 82;
        eq("#265 recovered: exactly the usual is", at265(d, C), Arrays.asList(30));
        d.get(28).readiness = 80;
        eq("#265 recovered: a day at mean minus one SD is not low", at265(d, C), Collections.emptyList());
        d.get(28).readiness = 79.9;
        eq("#265 recovered: just under it is", at265(d, C), Arrays.asList(30));
        d.get(28).readiness = Double.NaN;
        eq("#265 recovered: readiness needed on all three days", at265(d, C), Collections.emptyList());
        d.get(28).readiness = 70;
        d.get(28).leftOut = true;
        eq("#265 recovered: a left out day breaks it", at265(d, C), Collections.emptyList());
        d.get(28).leftOut = false;
        d.get(27).readiness = 60;
        eq("#265 recovered: three low days in a row say so", kind265(d, C).toString().contains("after 3 low days in a row (lowest 60)"), true);
        d = days265(40);
        for (int i = 0; i < 28; i++) d.get(i).readiness = i < 15 ? Double.NaN : i % 2 == 0 ? 80 : 84;
        d.get(28).readiness = 60;
        d.get(29).readiness = 60;
        d.get(30).readiness = 90;
        eq("#265 recovered: 13 days with readiness in the 4 weeks are not enough", at265(d, C), Collections.emptyList());
        d.get(14).readiness = 84;
        eq("#265 recovered: 14 are", at265(d, C), Arrays.asList(30));
        // a longer dip: the usual comes from the 4 weeks before its first day, never from the dip itself
        for (int len : new int[] {3, 4}) {
            d = days265(40);
            for (int i = 0; i < 28; i++) d.get(i).readiness = i % 2 == 0 ? 80 : 84;
            for (int i = 28; i < 28 + len; i++) d.get(i).readiness = 70;
            d.get(28 + len).readiness = 82;
            eq("#265 recovered: a " + len + " day dip, the usual from the 4 weeks before it", kind265(d, C),
                    Arrays.asList((28 + len) + ": Readiness 82, back to your usual 82 (the 4 weeks before) after " + len + " low days in a row (lowest 70)"));
        }
        // the usual's window: 28 days before the dip's first day (58), so day 30 is in it and day 29 is not
        for (int out : new int[] {29, 30}) {
            d = days265(70);
            for (int i = 0; i < 58; i++) d.get(i).readiness = i % 2 == 0 ? 80 : 84;
            d.get(out).readiness = 40;
            d.get(58).readiness = 75;
            d.get(59).readiness = 75;
            d.get(60).readiness = 83;
            eq("#265 recovered: a very low day " + (58 - out) + " days before the dip " + (out == 29 ? "is outside" : "is inside") + " its usual",
                    at265(d, C), out == 29 ? Arrays.asList(60) : Collections.<Integer>emptyList());
        }

        // ---- phone free evening: at most 2 min; 3 in 7 days or the first after 7 evenings with more ----
        d = days265(30);
        for (Badges.Day x : d) x.eveningMin = 20;
        d.get(10).eveningMin = 3;
        eq("#265 phone free: 3 min is not phone free", at265(d, P), Collections.emptyList());
        d.get(10).eveningMin = 2.5;
        eq("#265 phone free: 2.5 min (shown as 3 min) is not phone free", at265(d, P), Collections.emptyList());
        d.get(10).eveningMin = 2.4;
        eq("#265 phone free: 2.4 min (shown as 2 min) is", at265(d, P), Arrays.asList(10));
        d.get(10).eveningMin = 2;
        eq("#265 phone free: 2 min is, the first after 7 evenings", kind265(d, P),
                Arrays.asList("10: Phone free evening after 7 evenings with more: 2 min on the phone in the hour before sleep"));
        d.get(5).eveningMin = Double.NaN;
        eq("#265 phone free: not the first when one of the 7 evenings before is unknown", at265(d, P), Collections.emptyList());
        d.get(5).eveningMin = 20;
        d.get(5).leftOut = true;
        eq("#265 phone free: nor when one of them is left out", at265(d, P), Collections.emptyList());
        d.get(5).leftOut = false;
        d.get(10).eveningMin = Double.NaN;
        eq("#265 phone free: an evening not read is no badge", at265(d, P), Collections.emptyList());
        d.get(10).eveningMin = 0;
        d.get(12).eveningMin = 1;
        d.get(13).eveningMin = 0.4;
        eq("#265 phone free: 3 in 7 days, but at most one badge in 7 days", at265(d, P), Arrays.asList(10));
        d.get(18).eveningMin = 1;
        eq("#265 phone free: 3 in 7 days earns it once the week is free of one", kind265(d, P),
                Arrays.asList("10: Phone free evening after 7 evenings with more: 0 min on the phone in the hour before sleep",
                        "18: 3 phone free evenings in 7 days: this one 1 min on the phone in the hour before sleep"));
        d = days265(8);
        for (Badges.Day x : d) x.eveningMin = 20;
        d.get(6).eveningMin = 0;
        eq("#265 phone free: the first one needs 7 evenings before it", at265(d, P), Collections.emptyList());

        // ---- above your usual: steps 40 % above the mean of the last 28 worn days ----
        d = days265(40);
        for (int i = 0; i < 28; i++) worn265(d.get(i), 10000);
        worn265(d.get(28), 13900);
        eq("#265 above your usual: 39 % more is no badge", at265(d, A), Collections.emptyList());
        worn265(d.get(28), 14100);
        eq("#265 above your usual: 41 % more is", kind265(d, A),
                Arrays.asList("28: 14100 steps, 41% more than your usual 10000 (your last 28 days with the ring)"));
        worn265(d.get(28), 14000);
        eq("#265 above your usual: exactly 40 % more is", at265(d, A), Arrays.asList(28));
        d.get(28).restMin = 719 - d.get(28).coveredMin;
        eq("#265 above your usual: a day worn 719 minutes gets none", at265(d, A), Collections.emptyList());
        d.get(28).restMin = 720 - d.get(28).coveredMin;
        eq("#265 above your usual: 720 minutes is worn", at265(d, A), Arrays.asList(28));
        check("#265 worn: 719 no, 720 yes", !Badges.worn(dayWorn265(700, 19)) && Badges.worn(dayWorn265(700, 20)));
        // days not worn, left out, still running or older than the last 28 worn days are not in the usual
        d = days265(50);
        for (int i = 0; i < 10; i++) worn265(d.get(i), 40000);
        for (int i = 10; i < 41; i++) worn265(d.get(i), 10000);
        worn265(d.get(15), 50000);
        d.get(15).coveredMin = 100;
        worn265(d.get(16), 50000);
        d.get(16).leftOut = true;
        worn265(d.get(17), 50000);
        d.get(17).running = true;
        worn265(d.get(41), 14000);
        eq("#265 above your usual: not worn, left out, running and old days are not in the usual", kind265(d, A),
                Arrays.asList("41: 14000 steps, 40% more than your usual 10000 (your last 28 days with the ring)"));
        worn265(d.get(10), 40000);
        eq("#265 above your usual: the 28th worn day back is", at265(d, A), Collections.emptyList());
        worn265(d.get(10), 10000);
        d.get(41).leftOut = true;
        eq("#265 above your usual: a left out day earns none", at265(d, A), Collections.emptyList());
        d.get(41).leftOut = false;
        d.get(41).running = true;
        eq("#265 above your usual: a day still running earns none", at265(d, A), Collections.emptyList());
        d = days265(20);
        for (int i = 0; i < 13; i++) worn265(d.get(i), 10000);
        worn265(d.get(13), 30000);
        eq("#265 above your usual: 13 worn days are not enough", at265(d, A), Collections.emptyList());
        worn265(d.get(13), 10000);
        worn265(d.get(14), 30000);
        eq("#265 above your usual: 14 are, and the text says so", kind265(d, A),
                Arrays.asList("14: 30000 steps, 200% more than your usual 10000 (your last 14 days with the ring)"));
        // the usual is shown to the nearest 100 like the step counts (12005 reads 12000), the share uses the exact mean
        d = days265(40);
        for (int i = 0; i < 28; i++) worn265(d.get(i), i % 2 == 0 ? 12000 : 12010);
        worn265(d.get(28), 20700);
        eq("#265 above your usual: the usual reads to the nearest 100", kind265(d, A),
                Arrays.asList("28: 20700 steps, 72% more than your usual 12000 (your last 28 days with the ring)"));

        // ---- goal: 4 of 7 days (once in 7 days) or the first goal day after 7 without one ----
        d = days265(30);
        for (Badges.Day x : d) {
            x.activeKcal = 200;
            x.targetKcal = 300;
        }
        for (int i : new int[] {0, 2, 4}) d.get(i).activeKcal = 300;
        eq("#265 goal: 3 of 7 days is no badge", at265(d, G), Collections.emptyList());
        d.get(6).activeKcal = 420;
        eq("#265 goal: 4 of 7 is, on the 4th", kind265(d, G), Arrays.asList("6: Activity goal reached on 4 of the last 7 days (this day 420 of 300 kcal)"));
        d.get(4).goalLowered = true;
        eq("#265 goal: a lowered goal among them says so, in the same brackets", kind265(d, G),
                Arrays.asList("6: Activity goal reached on 4 of the last 7 days (this day 420 of 300 kcal; on 1 of them the goal was lowered after a low readiness day)"));
        d = days265(30);
        for (Badges.Day x : d) {
            x.activeKcal = 200;
            x.targetKcal = 300;
        }
        d.get(7).activeKcal = 310;
        eq("#265 goal: the first goal day after 7 without one", kind265(d, G),
                Arrays.asList("7: Activity goal reached after 7 days without it: 310 of 300 kcal"));
        d.get(7).goalLowered = true;
        eq("#265 goal: with a lowered goal the text says so", kind265(d, G),
                Arrays.asList("7: Activity goal reached after 7 days without it: 310 of 300 kcal (goal lowered after a low readiness day)"));
        d.get(3).targetKcal = Double.NaN;
        eq("#265 goal: not the first when a day before has no summary", at265(d, G), Collections.emptyList());
        d.get(3).targetKcal = 300;
        d.get(3).leftOut = true;
        eq("#265 goal: nor when a day before is left out", at265(d, G), Collections.emptyList());
        d.get(3).leftOut = false;
        d.get(7).running = true;
        eq("#265 goal: a day still running earns none", at265(d, G), Collections.emptyList());
        d = days265(15);
        for (Badges.Day x : d) {
            x.activeKcal = 500;
            x.targetKcal = 300;
            x.goalLowered = true;
        }
        eq("#265 goal: every day reached gives one badge a week, not one a day", kind265(d, G),
                Arrays.asList("3: Activity goal reached on 4 of the last 7 days (this day 500 of 300 kcal; each goal lowered after a low readiness day)",
                        "10: Activity goal reached on 7 of the last 7 days (this day 500 of 300 kcal; each goal lowered after a low readiness day)"));
        check("#265 goal lowered: under Good readiness, unless the stored target is the own target", Badges.goalLowered(0, 280, 69)
                && !Badges.goalLowered(400, 400, 60) && Badges.goalLowered(400, 320, 60) && !Badges.goalLowered(0, 280, Scores.GOOD)
                && !Badges.goalLowered(0, 280, Double.NaN));

        // ---- calm screen day: 30 % under the 28 day mean of fully read days ----
        d = days265(40);
        for (int i = 0; i < 28; i++) d.get(i).screenMin = 100;
        d.get(28).screenMin = 71;
        eq("#265 calm screen: 29 % less is no badge", at265(d, S), Collections.emptyList());
        d.get(28).screenMin = 69;
        eq("#265 calm screen: 31 % less is, counted like the Screen tab", kind265(d, S),
                Arrays.asList("28: 1h 9m on the phone, 31 min less than your usual 1h 40m (last 4 weeks)"));
        d.get(28).screenMin = 70;
        eq("#265 calm screen: exactly 30 % less is", at265(d, S), Arrays.asList(28));
        d.get(28).screenMin = Double.NaN;
        eq("#265 calm screen: a day not fully read gets none", at265(d, S), Collections.emptyList());
        d.get(28).screenMin = 40;
        d.get(28).running = true;
        eq("#265 calm screen: nor a day still running", at265(d, S), Collections.emptyList());
        d.get(28).running = false;
        for (int i = 0; i < 15; i++) d.get(i).screenMin = Double.NaN;
        eq("#265 calm screen: 13 fully read days are not enough", at265(d, S), Collections.emptyList());
        d.get(14).screenMin = 100;
        eq("#265 calm screen: 14 are", at265(d, S), Arrays.asList(28));
        for (int back : new int[] {28, 29}) {
            List<Badges.Day> w = days265(40);
            for (int i = 0; i < 30; i++) w.get(i).screenMin = 100;
            w.get(30 - back).screenMin = 0;
            w.get(30).screenMin = 69;
            eq("#265 calm screen: a day " + back + " days back is " + (back == 28 ? "in" : "not in") + " the usual", at265(w, S),
                    back == 28 ? Collections.<Integer>emptyList() : Arrays.asList(30));
        }
        d.get(10).screenMin = 1000;
        d.get(10).leftOut = true;
        d.get(9).screenMin = 1000;
        d.get(9).running = true;
        eq("#265 calm screen: left out and running days are not in the usual", kind265(d, S),
                Arrays.asList("28: 40 min on the phone, 1h 0m less than your usual 1h 40m (last 4 weeks)"));

        // ---- left out days: skipped as the day and in every average ----
        d = days265(40);
        for (int i = 0; i < 30; i++) d.get(i).sleepScore = 80;
        d.get(20).sleepScore = 99;
        d.get(20).leftOut = true;
        d.get(29).sleepScore = 90;
        eq("#265 left out: a better night on a left out day neither earns nor blocks", at265(d, B), Arrays.asList(29));
        d.get(29).leftOut = true;
        eq("#265 left out: the night of a left out day earns nothing", at265(d, B), Collections.emptyList());

        // ---- one per kind and date, a later entry for a date replaces an earlier one ----
        List<Badges.Day> twice = days265(20);
        for (Badges.Day x : twice) x.sleepScore = 80;
        Badges.Day again = new Badges.Day(D265.plusDays(19));
        again.sleepScore = 95;
        twice.add(again);
        twice.add(null);
        twice.add(new Badges.Day(null));
        eq("#265 a second entry for a date replaces the first", kind265(twice, B),
                Arrays.asList("19: Best night in 30 days: score 95 (before: 80 on 19 Sep)"));

        // ---- English month names also on a phone in another language ----
        Locale was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            List<Badges.Badge> de = Badges.of(ny);
            eq("#265 the text stays English with a German phone", de.size() == 1 ? de.get(0).text : de.toString(),
                    "Best night in 30 days: score 88 (before: 85 on 12 Dec 2025)");
            List<Badges.Day> oct = days265(40);
            for (Badges.Day x : oct) x.sleepScore = 80;
            oct.get(35).sleepScore = 90;
            eq("#265 an English month name with a German phone", kind265(oct, B), Arrays.asList("35: Best night in 30 days: score 90 (before: 80 on 5 Oct)"));
        } finally {
            Locale.setDefault(was);
        }

        // ---- a stray date far away does not blow up the arrays: year 1 and year 9999 are left out ----
        List<Badges.Day> stray = days265(40);
        for (Badges.Day x : stray) x.sleepScore = 80;
        stray.get(35).sleepScore = 90;
        List<String> plainOnes = kind265(stray, B);
        Badges.Day y1 = new Badges.Day(java.time.LocalDate.of(1, 1, 1)), y9999 = new Badges.Day(java.time.LocalDate.of(9999, 12, 31));
        y1.sleepScore = 99;
        y9999.sleepScore = 99;
        stray.add(y1);
        stray.add(y9999);
        long st = System.nanoTime();
        List<String> withStray = kind265(stray, B);
        long stMs = (System.nanoTime() - st) / 1000000;
        eq("#265 dates in year 1 and 9999 are left out, the rest as before", withStray, plainOnes);
        check("#265 ... and quickly (" + stMs + " ms)", stMs < 1000);
        eq("#265 a date after today is left out", Badges.of(Arrays.asList(y9999), D265).size(), 0);

        // ---- a made up history of 3 years: fast, one per kind and date, every text plain ----
        Random rnd = new Random(265);
        List<Badges.Day> years = new ArrayList<Badges.Day>();
        for (int i = 0; i < 3 * 365; i++) {
            if (rnd.nextDouble() < 0.05) continue; // a day without the ring
            Badges.Day x = new Badges.Day(D265.minusDays(3 * 365).plusDays(i));
            x.sleepScore = Math.round(78 + rnd.nextGaussian() * 8);
            x.readiness = Math.round(80 + rnd.nextGaussian() * 7);
            worn265(x, Math.round(8000 + rnd.nextGaussian() * 2500));
            if (rnd.nextDouble() < 0.1) x.restMin = 0;
            x.activeKcal = Math.round(350 + rnd.nextGaussian() * 120);
            x.targetKcal = 380;
            x.goalLowered = x.readiness < 70;
            x.screenMin = rnd.nextDouble() < 0.05 ? Double.NaN : Math.max(0, 150 + rnd.nextGaussian() * 45);
            x.eveningMin = rnd.nextDouble() < 0.05 ? Double.NaN : Math.max(0, rnd.nextGaussian() * 6 + 6);
            x.leftOut = rnd.nextDouble() < 0.02;
            years.add(x);
        }
        long t0 = System.nanoTime();
        List<Badges.Badge> all = Badges.of(years);
        long ms = (System.nanoTime() - t0) / 1000000;
        check("#265 3 years of days in one pass, well under 2 s (" + ms + " ms)", ms < 2000);
        Set<String> seen = new HashSet<String>();
        boolean once = true, plain = true;
        int[] per = new int[Badges.KINDS];
        java.time.LocalDate prev = null;
        boolean ordered = true;
        for (Badges.Badge b : all) {
            once &= seen.add(b.date + " " + b.kind);
            plain &= plain265(b.text);
            per[b.kind]++;
            if (prev != null && b.date.isBefore(prev)) ordered = false;
            prev = b.date;
        }
        check("#265 never two badges of one kind for one date", once);
        check("#265 badges come by date", ordered);
        check("#265 every badge text is plain, no dash as punctuation", plain);
        boolean everyKind = true;
        for (int k = 0; k < Badges.KINDS; k++) everyKind &= per[k] > 0;
        check("#265 the made up years earn every kind " + Arrays.toString(per), everyKind);
        check("#265 a left out day never has a badge", noneOnLeftOut265(years, all));

        // ---- the Explain lines: one per rule, with the rule's numbers, plain ----
        boolean explained = plain265(app.openring.ui.Explain.BADGES);
        for (int k = 0; k < Badges.KINDS; k++) explained &= plain265(app.openring.ui.Explain.badge(k)) && app.openring.ui.Explain.badge(k).startsWith(Badges.NAMES[k] + ": ");
        check("#265 one plain Explain line per badge rule, starting with its name", explained && app.openring.ui.Explain.badge(Badges.KINDS) == null);
        check("#265 the Explain lines name the rules' numbers",
                app.openring.ui.Explain.badge(B).contains("last 30 days") && app.openring.ui.Explain.badge(B).contains("at least 14 nights")
                && app.openring.ui.Explain.badge(R).contains("at least 5 points") && app.openring.ui.Explain.badge(R).contains("at most once in 7 days")
                && app.openring.ui.Explain.badge(C).contains("at least 2 days in a row") && app.openring.ui.Explain.badge(C).contains("one standard deviation")
                && app.openring.ui.Explain.badge(P).contains("at most 2 min") && app.openring.ui.Explain.badge(P).contains("Reading in bed")
                && app.openring.ui.Explain.badge(A).contains("at least 40% more steps") && app.openring.ui.Explain.badge(A).contains("last 28 days with the ring")
                && app.openring.ui.Explain.badge(A).contains("12 hours")
                && app.openring.ui.Explain.badge(G).contains("4 of the last 7 days") && app.openring.ui.Explain.badge(G).contains("first goal day after 7 days")
                && app.openring.ui.Explain.badge(S).contains("at least 30% less") && app.openring.ui.Explain.badge(S).contains("last 4 weeks")
                && app.openring.ui.Explain.BADGES.contains("left out"));
    }

    /** Phase 1b (issue #265): the screens' texts and the small helpers they use. */
    static void badgeScreens265() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 3);
        List<String> texts = new ArrayList<String>(Arrays.asList(app.openring.ui.Explain.BADGES_ROW, app.openring.ui.Explain.BADGES_ROW_NOTE,
                app.openring.ui.Explain.BADGES_NEW, app.openring.ui.Explain.BADGES_ALL, app.openring.ui.Explain.BADGES_CALENDAR,
                app.openring.ui.Explain.BADGES_COUNT_NOTE, app.openring.ui.Explain.BADGES_SCREEN_ACCESS, app.openring.ui.Explain.BADGES_SCREEN_OLD,
                app.openring.ui.Explain.SHOW_BADGES, app.openring.ui.Explain.SHOW_BADGES_NOTE, app.openring.ui.Explain.BADGES_FAILED,
                app.openring.ui.Explain.badgesWait(0), app.openring.ui.Explain.badgesWait(1), app.openring.ui.Explain.badgesWait(13),
                app.openring.ui.Explain.BADGES_OPEN));
        boolean rules = app.openring.ui.Explain.badgeRule(Badges.KINDS) == null;
        for (int k = 0; k < Badges.KINDS; k++) {
            String r = app.openring.ui.Explain.badgeRule(k);
            texts.add(r);
            rules &= !r.startsWith(Badges.NAMES[k]) && Character.isUpperCase(r.charAt(0)) && app.openring.ui.Explain.badge(k).toLowerCase(Locale.ROOT).endsWith(r.toLowerCase(Locale.ROOT));
        }
        check("#265 under a badge's title its rule without the name again", rules && app.openring.ui.Explain.badgeRule(Badges.BEST_NIGHT).startsWith("Your highest sleep score"));
        check("#265 the switch's note does not say nothing is stored (the newest date seen is)", !app.openring.ui.Explain.SHOW_BADGES_NOTE.contains("stored"));
        boolean plain = true;
        for (String t : texts) plain &= plain265(t);
        check("#265 the badge screens' texts are plain, no dash as punctuation", plain);
        eq("#265 under 14 nights: most comparisons wait", app.openring.ui.Explain.badgesWait(5),
                "Most badges compare with your own last 2 to 4 weeks, so they come after about 14 nights with the ring. You have 5 nights so far.");
        check("#265 from 14 nights on no waiting line", app.openring.ui.Explain.badgesWait(14) == null);
        eq("#265 when: today, yesterday, a weekday and date in English", Arrays.asList(app.openring.ui.Explain.badgeWhen(today, today),
                app.openring.ui.Explain.badgeWhen(today.minusDays(1), today), app.openring.ui.Explain.badgeWhen(today.minusDays(3), today),
                app.openring.ui.Explain.badgeWhen(java.time.LocalDate.of(2025, 12, 30), today)), Arrays.asList("Today", "Yesterday", "Sat 31 Oct", "Tue 30 Dec 2025"));
        eq("#265 how often: none, once, several", Arrays.asList(app.openring.ui.Explain.badgeCount(0, null, today),
                app.openring.ui.Explain.badgeCount(1, today.minusDays(3), today), app.openring.ui.Explain.badgeCount(3, today.minusDays(3), today)),
                Arrays.asList("Not yet", "1 time, last on 31 Oct", "3 times, last on 31 Oct"));
        List<Badges.Day> d = days265(40);
        for (Badges.Day x : d) x.sleepScore = 80;
        d.get(35).sleepScore = 90;
        List<Badges.Badge> got = Badges.of(d);
        eq("#265 a touched day reads its badges with their numbers", app.openring.ui.Explain.badgeDay(D265.plusDays(35), got, today),
                "Tue 6 Oct\nBest night: Best night in 30 days: score 90 (before: 80 on 5 Oct)");
        eq("#265 a touched day without a badge says so", app.openring.ui.Explain.badgeDay(today, new ArrayList<Badges.Badge>(), today), "Today: no badge");
        check("#265 New: after the newest date seen, every one when none was seen", Badges.isNew(got.get(0), null)
                && Badges.isNew(got.get(0), D265.plusDays(34)) && !Badges.isNew(got.get(0), D265.plusDays(35)));
        eq("#265 the newest badge date", Badges.newest(got), D265.plusDays(35));
        check("#265 no badge, no newest date", Badges.newest(new ArrayList<Badges.Badge>()) == null);
        d.get(20).sleepScore = 95; // a second best night, earlier: the newest date is still the later one, in any order
        d.get(35).sleepScore = 99;
        List<Badges.Badge> two = Badges.of(d), backwards = new ArrayList<Badges.Badge>(two);
        Collections.reverse(backwards);
        check("#265 the newest of several badges, in any order", two.size() == 2 && D265.plusDays(35).equals(Badges.newest(two))
                && D265.plusDays(35).equals(Badges.newest(backwards)));
        check("#265 night and day badges", Badges.fromNight(Badges.BEST_NIGHT) && Badges.fromNight(Badges.RESTFUL_WEEK) && Badges.fromNight(Badges.RECOVERED)
                && Badges.fromNight(Badges.PHONE_FREE) && !Badges.fromNight(Badges.ABOVE_USUAL) && !Badges.fromNight(Badges.GOAL) && !Badges.fromNight(Badges.CALM_SCREEN));
    }

    /**
     * Review of #309: the Today row reads only the weeks it needs, yet its badges must be the whole history's for those
     * dates, also inside a goal streak and a phone free streak of 300 days, where "at most once in 7 days" chains back to
     * the streak's first day, and after a long gap for "Above your usual".
     */
    static void recent265() {
        final List<Badges.Day> all = new ArrayList<Badges.Day>();
        java.time.LocalDate start = java.time.LocalDate.of(2025, 1, 1);
        Random rnd = new Random(309);
        for (int i = 0; i < 520; i++) {
            if (i >= 400 && i < 460) continue; // two months without the ring
            Badges.Day x = new Badges.Day(start.plusDays(i));
            x.sleepScore = Math.round(78 + rnd.nextGaussian() * 6);
            x.readiness = Math.round(80 + rnd.nextGaussian() * 6);
            worn265(x, Math.round(8000 + rnd.nextGaussian() * 2000));
            x.targetKcal = 300;
            // a goal streak from day 20 to day 322 (303 days), then now and then
            x.activeKcal = i >= 20 && i <= 322 || rnd.nextDouble() < 0.4 ? 350 : 200;
            // phone free evenings every day from day 40 to day 341, then about one in three
            x.eveningMin = i >= 40 && i <= 341 || rnd.nextDouble() < 0.3 ? 0 : 20;
            x.screenMin = 150 + rnd.nextGaussian() * 40;
            all.add(x);
        }
        final java.time.LocalDate today = start.plusDays(520);
        List<Badges.Badge> full = Badges.of(all, today);
        final int[] reads = {0};
        Badges.Reader reader = new Badges.Reader() {
            @Override
            public List<Badges.Day> from(java.time.LocalDate s) {
                reads[0]++;
                List<Badges.Day> out = new ArrayList<Badges.Day>();
                for (Badges.Day x : all) if (!x.date.isBefore(s)) out.add(x);
                return out;
            }
        };
        boolean same = true;
        String first = null;
        int shown = 0, chained = 0;
        for (int end = 60; end < 520; end += 7) {
            java.time.LocalDate to = start.plusDays(end), from = to.minusDays(6);
            List<String> want = new ArrayList<String>(), got = new ArrayList<String>();
            for (Badges.Badge x : full) if (!x.date.isBefore(from) && !x.date.isAfter(to)) want.add(x.date + " " + x.kind + " " + x.text);
            for (Badges.Badge x : Badges.recent(reader, from, to, start, today)) got.add(x.date + " " + x.kind + " " + x.text);
            for (String w : want) if (w.contains(" " + Badges.GOAL + " ") || w.contains(" " + Badges.PHONE_FREE + " ")) chained++;
            if (!want.equals(got)) {
                same = false;
                if (first == null) first = to + ": " + want + " / " + got;
            }
            shown++;
        }
        check("#265 the Today row's badges (dates and texts) are the whole history's for " + shown + " shown weeks, inside 300 day streaks and after a gap"
                + (first == null ? "" : " " + first), same && chained > 30);
        java.time.LocalDate to = start.plusDays(200);
        List<String> withStart = new ArrayList<String>(), withoutStart = new ArrayList<String>();
        for (Badges.Badge x : Badges.recent(reader, to.minusDays(6), to, start, today)) withStart.add(x.date + " " + x.text);
        for (Badges.Badge x : Badges.recent(reader, to.minusDays(6), to, null, today)) withoutStart.add(x.date + " " + x.text);
        check("#265 without the first stored date the reads go back until the whole history is in", withStart.equals(withoutStart) && !withStart.isEmpty());
        // a read that starts inside the streak cannot tell; a read with a quiet week of each chained kind and 28 worn days can
        List<Badges.Day> inStreak = reader.from(start.plusDays(100));
        java.time.LocalDate settled = Badges.exactFrom(inStreak, today);
        check("#265 a read starting inside a streak is exact only after the streak ended " + settled, settled != null && settled.isAfter(start.plusDays(341)));
        check("#265 a read inside the streak that ends in it cannot tell", Badges.exactFrom(inStreak.subList(0, 150), today) == null);
        check("#265 nothing read, nothing to tell", Badges.exactFrom(new ArrayList<Badges.Day>(), today) == null);
        List<Badges.Day> quiet = days265(120);
        for (Badges.Day x : quiet) {
            worn265(x, 8000);
            x.targetKcal = 300;
            x.activeKcal = 200;
            x.eveningMin = 20;
        }
        eq("#265 a quiet read is exact after the rules' reach", Badges.exactFrom(quiet, D265.plusDays(200)), D265.plusDays(Badges.REACH));
        check("#265 fewer than 28 worn days cannot tell the usual steps", Badges.exactFrom(quiet.subList(0, 27), D265.plusDays(200)) == null);
    }

    static Badges.Day dayWorn265(double covered, double rest) {
        Badges.Day x = new Badges.Day(D265);
        x.coveredMin = covered;
        x.restMin = rest;
        return x;
    }

    static boolean noneOnLeftOut265(List<Badges.Day> days, List<Badges.Badge> got) {
        Set<java.time.LocalDate> left = new HashSet<java.time.LocalDate>();
        for (Badges.Day x : days) if (x.leftOut) left.add(x.date);
        for (Badges.Badge b : got) if (left.contains(b.date)) return false;
        return !left.isEmpty();
    }

    static void cycles252() {
        // ---- 12 cycles of 28 days: 28, give or take 1 ----
        int[] l12 = new int[12];
        Arrays.fill(l12, 28);
        java.time.LocalDate s12 = lastStart252(l12);
        Cycles c = cyc252(log252(5, l12), s12.plusDays(10));
        check("#252 12 x 28: 13 periods, 12 cycles, all used", c.periods.size() == 13 && c.cycles.size() == 12 && c.used.size() == 12);
        check("#252 12 x 28: typical 28, spread 0, give or take 1", c.length == 28 && c.typical == 28 && c.sd == 0 && c.range == 1);
        eq("#252 12 x 28: next start is the last start plus 28", c.next, s12.plusDays(28));
        eq("#252 12 x 28: cycle day 11", c.cycleDay(s12.plusDays(10)), 11);
        eq("#252 12 x 28: status", c.status(EN), "Cycle day 11");
        eq("#252 12 x 28: next line", c.nextLine(), "Next period in about 18 days");
        check("#252 12 x 28: about 4 in 5", c.expectedLine(EN).endsWith("About 4 in 5 periods start within such a range.")
                && c.expectedLine(EN).startsWith("Expected "));
        eq("#252 12 x 28: very regular", c.regularityLine(), "Your cycles vary by less than a day.");
        // ovulation: 14 days before, give or take max(3, round(1.28 x 2.4)) = 3; fertile the 5 days before and the day
        eq("#252 12 x 28: ovulation 14 days before the next start", c.ovulation, s12.plusDays(14));
        eq("#252 12 x 28: ovulation range 3", c.ovulationRange, 3);
        check("#252 fertile window: 5 days before the estimate and the day", c.inFertile(s12.plusDays(9)) && c.inFertile(s12.plusDays(14))
                && !c.inFertile(s12.plusDays(8)) && !c.inFertile(s12.plusDays(15)));
        check("#252 ovulation band: 3 days each way", c.inOvulation(s12.plusDays(11)) && c.inOvulation(s12.plusDays(17))
                && !c.inOvulation(s12.plusDays(10)) && !c.inOvulation(s12.plusDays(18)));
        check("#252 expected band: one day each way", c.inExpected(s12.plusDays(27)) && c.inExpected(s12.plusDays(29))
                && !c.inExpected(s12.plusDays(26)) && !c.inExpected(s12.plusDays(30)));
        check("#252 a regular history: nothing unusual", c.unusualLine(EN) == null && c.unusualLately() == 0);
        check("#252 13 cycles: only the newest 12 are used", cyc252(log252(5, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
                d252(400)).used.size() == 12);

        // ---- a mixed list, worked out by hand ----
        int[] mix = {26, 30, 28, 31, 27, 29, 33, 25};
        java.time.LocalDate sm = lastStart252(mix);
        c = cyc252(log252(4, mix), sm.plusDays(3));
        // median 28.5, nothing left out; trimmed mean of 26..31 = 171 / 6 = 28.5, rounds to 29; sample SD sqrt(49.875 / 7) = 2.669
        check("#252 mixed: trimmed mean 28.5, typical 29", Math.abs(c.typical - 28.5) < 1e-9 && c.length == 29);
        check("#252 mixed: SD 2.669, give or take round(3.42) = 3", Math.abs(c.sd - Math.sqrt(49.875 / 7)) < 1e-9 && c.range == 3);
        eq("#252 mixed: ovulation give or take round(1.28 x sqrt(7.125 + 5.76)) = 5", c.ovulationRange, 5);
        eq("#252 mixed: next start", c.next, sm.plusDays(29));
        eq("#252 mixed: regularity", c.regularityLine(), "Your cycles vary by about 3 days.");
        eq("#252 mixed: day 4 of the period while it runs", c.status(EN), "Day 4 of your period");

        // ---- one period: 29 give or take 5, said so ----
        Map<java.time.LocalDate, Integer> one = log252(5);
        c = cyc252(one, d252(10));
        check("#252 one period: no cycle, 29 days, give or take 5", c.cycles.isEmpty() && c.length == 29 && c.range == 5 && c.next.equals(d252(30)));
        check("#252 one period: the text says it uses 29 days and a wider range", c.expectedLine(EN).contains("Until you have logged 3 cycles, this uses a typical cycle of 29 days and a wider range."));
        eq("#252 one period: ovulation give or take 5 (the typical 2.6 days spread)", c.ovulationRange, 5);
        check("#252 one period: no regularity line", c.regularityLine() == null && c.unusualLine(EN) == null);

        // ---- 2 and 4 cycles: own median ----
        c = cyc252(log252(5, 27, 31), d252(70));
        check("#252 2 cycles: median 29, give or take 5", c.length == 29 && c.range == 5 && c.expectedLine(EN).contains("uses your own 2 cycles and a wider range"));
        c = cyc252(log252(5, 27), d252(30));
        check("#252 1 cycle: own 27 days, give or take 5", c.length == 27 && c.range == 5 && c.expectedLine(EN).contains("your own 1 cycle and"));
        c = cyc252(log252(5, 27, 31, 28, 26), d252(115));
        // median 27.5 rounds to 28; SD sqrt(14 / 3) = 2.16, 1.28 x 2.16 = 2.77, give or take 3
        check("#252 4 cycles: median 27.5, typical 28, give or take 3", c.typical == 27.5 && c.length == 28 && c.range == 3);
        check("#252 4 cycles: about 4 in 5 from 3 cycles on", c.expectedLine(EN).endsWith("About 4 in 5 periods start within such a range."));

        // ---- the switch at 5 cycles: median, then the mean without shortest and longest ----
        c = cyc252(log252(5, 27, 27, 27, 33), d252(130));
        check("#252 4 cycles 27 27 27 33: median 27", c.typical == 27);
        c = cyc252(log252(5, 27, 27, 27, 33, 33), d252(160));
        check("#252 5 cycles 27 27 27 33 33: mean of 27 27 33 = 29", c.typical == 29 && c.length == 29);

        // ---- a 58 day cycle (a period not logged) is left out ----
        c = cyc252(log252(5, 28, 28, 58, 28, 28, 28), d252(200 + 5));
        check("#252 58 days: left out, the rest gives 28 give or take 1", c.cycles.get(2).leftOut && c.used.size() == 5 && c.length == 28 && c.range == 1);
        c = cyc252(log252(5, 28, 28, 28, 28, 58), d252(171 + 3));
        check("#252 58 days as the newest cycle: said gently", c.unusualLine(EN).equals("The cycle from " + c.date(c.cycles.get(4).start, EN)
                + " was 58 days. It is left out of the estimate, maybe a period was not logged."));

        // ---- SD 6 gives 8 days each way ----
        c = cyc252(log252(5, 22, 34, 28), d252(90));
        check("#252 SD 6: give or take round(7.68) = 8", Math.abs(c.sd - 6) < 1e-9 && c.range == 8 && c.length == 28);
        eq("#252 SD 6: ovulation give or take round(1.28 x sqrt(36 + 5.76)) = 8", c.ovulationRange, 8);
        eq("#252 SD 6: vary by about 6 days", c.regularityLine(), "Your cycles vary by about 6 days.");

        // ---- late, any day now, today, tomorrow, waiting ----
        Map<java.time.LocalDate, Integer> l = log252(5, l12);
        java.time.LocalDate next = s12.plusDays(28);
        eq("#252 tomorrow", cyc252(l, next.minusDays(1)).nextLine(), "Next period expected tomorrow");
        eq("#252 today", cyc252(l, next).nextLine(), "Next period expected today");
        Cycles inRange = cyc252(l, next.plusDays(1));
        check("#252 inside the range: any day now, not late", inRange.lateBy() == 0 && inRange.nextLine().equals("Next period expected any day now"));
        c = cyc252(l, next.plusDays(4));
        check("#252 4 days after the expected day: late by 4", c.lateBy() == 4
                && c.nextLine().equals("Your period is 4 days later than expected. Cycles vary, this happens."));
        check("#252 late: the range stays", c.inExpected(next) && c.estimates());
        eq("#252 with a range of 1 day, late counts from 2 days after the expected day", cyc252(log252(5, 27, 27, 27, 27, 27), lastStart252(27, 27, 27, 27, 27).plusDays(27 + 1 + 1)).lateBy(), 2);
        c = cyc252(l, s12.plusDays(56));
        check("#252 twice the typical length: still late, not yet waiting", !c.waiting && c.lateBy() == 28);
        c = cyc252(l, s12.plusDays(57));
        check("#252 after twice the typical length: waits for the next start", c.waiting && !c.estimates() && c.nextLine() == null
                && c.expectedLine(EN) == null && c.ovulationLine(EN) == null && c.fertileLine(EN) == null && !c.inExpected(next) && c.lateBy() == 0);
        eq("#252 waiting: the status names the last start", c.status(EN), "No period logged since " + c.date(s12, EN));
        eq("#252 waiting: the note", c.statusNote(EN), "The estimate starts again with the next period you log.");

        // ---- spotting never starts or extends a period ----
        Map<java.time.LocalDate, Integer> sp = new HashMap<java.time.LocalDate, Integer>();
        sp.put(d252(1), Cycles.HEAVY);
        for (int i = 2; i <= 4; i++) sp.put(d252(i), Cycles.SPOTTING);
        sp.put(d252(5), Cycles.LIGHT);
        c = cyc252(sp, d252(40));
        check("#252 spotting between: two periods, the spotting days are not bleeding", c.periods.size() == 2 && c.periods.get(0).last.equals(d252(1)));
        sp.clear();
        sp.put(d252(3), Cycles.SPOTTING);
        c = cyc252(sp, d252(4));
        check("#252 spotting alone starts no period", c.periods.isEmpty() && c.next == null && !c.estimates() && c.status(EN).equals("No period logged yet")
                && c.statusNote(EN).startsWith("Tap Heavy, Medium or Light on the first day"));
        sp.put(d252(1), Cycles.HEAVY);
        sp.put(d252(2), Cycles.HEAVY);
        c = cyc252(sp, d252(20));
        check("#252 spotting after a period does not extend it", c.periods.size() == 1 && c.periods.get(0).days() == 2);
        check("#252 spotting is kept for the calendar", c.flows.get(d252(3)) == Cycles.SPOTTING && Cycles.amount(c.flows.get(d252(3))).equals("Spotting"));

        // ---- gaps: 2 unlogged days join, 3 do not ----
        Map<java.time.LocalDate, Integer> g = new HashMap<java.time.LocalDate, Integer>();
        g.put(d252(1), Cycles.HEAVY);
        g.put(d252(4), Cycles.LIGHT);
        check("#252 a gap of 2 days: one period", cyc252(g, d252(30)).periods.size() == 1 && cyc252(g, d252(30)).periods.get(0).days() == 4);
        g.put(d252(8), Cycles.LIGHT);
        check("#252 a gap of 3 days: a new period", cyc252(g, d252(30)).periods.size() == 2);

        // ---- Ended today versus yesterday, and the 3 days without a log ----
        Map<java.time.LocalDate, Integer> e = new HashMap<java.time.LocalDate, Integer>();
        for (int i = 1; i <= 3; i++) e.put(d252(i), Cycles.MEDIUM);
        c = cyc252(e, d252(4));
        check("#252 without Ended: day 4 of the period, Ended can be logged", c.running != null && c.status(EN).equals("Day 4 of your period")
                && c.canEnd(d252(4)) && c.canEnd(d252(1)) && !c.canEnd(C0.minusDays(1)) && c.statusNote(EN).contains("Ended"));
        check("#252 running: a day of the open period reads as in it", c.periodOn(d252(4)) == c.running && c.periodOn(d252(5)) == null);
        check("#252 3 days without a log: still running on the third", cyc252(e, d252(6)).running != null);
        c = cyc252(e, d252(7));
        check("#252 after 3 days without a log the period is over on its last logged day", c.running == null && c.periods.get(0).last.equals(d252(3))
                && !c.periods.get(0).marked && !c.canEnd(d252(7)) && c.periodOn(d252(5)) == null && c.periodOn(d252(2)) != null);
        c = cyc252(e, d252(4), d252(4));
        check("#252 Ended today (not logged): the period ends on its last logged day", c.running == null && c.periods.get(0).last.equals(d252(3))
                && c.periods.get(0).marked);
        c = cyc252(e, d252(4), d252(3));
        check("#252 Ended yesterday (logged): it ends that day", c.running == null && c.periods.get(0).last.equals(d252(3)) && c.periods.get(0).marked);
        e.put(d252(5), Cycles.LIGHT);
        c = cyc252(e, d252(5), d252(4));
        check("#252 a day logged after Ended starts a new period", c.periods.size() == 2 && c.periods.get(1).start.equals(d252(5)) && c.running == c.periods.get(1));
        c = cyc252(e, d252(5), d252(2));
        check("#252 Ended on a past day of the period splits it there", c.periods.size() == 2 && c.periods.get(0).last.equals(d252(2))
                && c.periods.get(1).start.equals(d252(3)));
        Map<java.time.LocalDate, Integer> far = log252(4, 28);
        c = cyc252(far, d252(40), d252(2));
        check("#252 a mark inside an older period closes it, the next period is unmarked", c.periods.get(0).marked && !c.periods.get(1).marked
                && c.periods.get(0).last.equals(d252(2)) && c.periods.size() == 3);

        // ---- removal marks count as no entry ----
        Map<java.time.LocalDate, Integer> r = new HashMap<java.time.LocalDate, Integer>();
        r.put(d252(1), Cycles.HEAVY);
        r.put(d252(2), Cycles.REMOVED);
        r.put(d252(3), Cycles.MEDIUM);
        c = cyc252(r, d252(20));
        check("#252 a removed day inside: still one period", c.periods.size() == 1 && c.periods.get(0).days() == 3 && !c.flows.containsKey(d252(2))
                && Cycles.amount(c.flows.get(d252(2))).equals("No entry"));
        r.put(d252(1), Cycles.REMOVED);
        r.put(d252(3), Cycles.REMOVED);
        check("#252 every day removed: no period", cyc252(r, d252(20)).periods.isEmpty());
        r.put(d252(4), 7);
        r.put(null, Cycles.HEAVY);
        r.put(d252(5), null);
        check("#252 amounts out of range, no date or no amount: no entry", cyc252(r, d252(20)).flows.isEmpty());
        check("#252 nothing at all", Cycles.of(null, null, d252(1)).periods.isEmpty() && Cycles.of(null, Arrays.asList((java.time.LocalDate) null), d252(1)).ends.isEmpty());

        // ---- unusual cycles: once, twice lately, beyond 2 SD ----
        c = cyc252(log252(5, 28, 28, 28, 28, 20), d252(160));
        eq("#252 a 20 day cycle once", c.unusualLine(EN), "Your last cycle was 20 days, shorter than your usual 28. One unusual cycle is common.");
        c = cyc252(log252(5, 28, 28, 28, 28, 20, 28, 20), d252(220));
        check("#252 twice in the last 3: the doctor line", c.unusualLately() == 2 && c.unusualLine(EN).endsWith(
                "One unusual cycle is common. This has happened twice lately. You could mention it to a doctor."));
        c = cyc252(log252(5, 28, 29, 28, 29, 28, 29, 28, 33), d252(260));
        check("#252 33 days with cycles that vary by 1.7: beyond 2 SD, unusual", c.unusual(c.cycles.get(7)) && !c.unusual(c.cycles.get(6))
                && c.unusualLine(EN).startsWith("Your last cycle was 33 days, longer than your usual 29."));
        c = cyc252(log252(5, 36, 36), d252(100));
        check("#252 over 35 days is unusual also with few cycles", c.unusual(c.cycles.get(1)));

        // ---- texts: dates, the readout of a day, the always line ----
        c = cyc252(log252(5, l12), s12.plusDays(10));
        eq("#252 a date this year", c.date(java.time.LocalDate.of(2026, 3, 8), EN), "8 Mar 2026");
        eq("#252 a span in one month", c.span(java.time.LocalDate.of(2026, 11, 6), java.time.LocalDate.of(2026, 11, 12), EN), "6 to 12 Nov 2026");
        Cycles thisYear = cyc252(log252(5), java.time.LocalDate.of(2025, 1, 20));
        eq("#252 a span across months, this year without the year", thisYear.span(java.time.LocalDate.of(2025, 1, 29), java.time.LocalDate.of(2025, 2, 4), EN), "29 Jan to 4 Feb");
        String ov = c.ovulationLine(EN);
        check("#252 ovulation line", ov.startsWith("Ovulation likely around ") && ov.contains("(give or take 3 days)") && ov.endsWith("not a measurement."));
        check("#252 fertile line", c.fertileLine(EN).startsWith("Likely fertile days: ") && c.fertileLine(EN).endsWith(", from the calendar only."));
        String rd = c.describe(s12.plusDays(1), EN);
        check("#252 readout of a period day: date, amount, day of the period", rd.contains(": Medium") && rd.contains("\nDay 2 of the period from "));
        rd = c.describe(next, EN);
        check("#252 readout of the expected day: why it is in the band", rd.contains(": No entry") && rd.contains("\nCycle day 29")
                && rd.contains("In the expected next period") && rd.contains("your typical cycle of 28 days, give or take 1 day."));
        rd = c.describe(s12.plusDays(13), EN);
        check("#252 readout in the ovulation range and the fertile days", rd.contains("In the likely ovulation range") && rd.contains("about 14 days before")
                && rd.contains("In the likely fertile days") && rd.contains("the 5 days before the ovulation estimate and that day."));
        rd = cyc252(one, d252(10)).describe(d252(30), EN);
        check("#252 readout with no cycle yet: a typical cycle of 29 days", rd.contains("your last start plus a typical cycle of 29 days, give or take 5 days."));
        rd = cyc252(e, d252(5), d252(4)).describe(d252(4), EN);
        check("#252 readout of an end mark", rd.contains("No entry, marked as the end of a period"));
        check("#252 readout before the first period: the date only", !cyc252(one, d252(10)).describe(C0.minusDays(3), EN).contains("\n"));
        check("#252 the always line", Cycles.NOT_ADVICE.contains("not birth control and not a medical diagnosis"));
        Map<java.time.LocalDate, Integer> fut = log252(5);
        c = cyc252(fut, C0.minusDays(4));
        check("#252 a period logged for a later day: said so, nothing running", c.running == null && c.status(EN).equals("Next period logged for " + c.date(C0, EN))
                && c.statusNote(EN).isEmpty() && c.cycleDay(C0.minusDays(4)) == 0);
        check("#252 amounts named", Cycles.amount(3).equals("Heavy") && Cycles.amount(2).equals("Medium") && Cycles.amount(1).equals("Light")
                && Cycles.amount(-1).equals("No entry") && Cycles.amount(null).equals("No entry"));
        check("#252 before the first period no day lies in a period", c.periodOn(C0.minusDays(1)) == null);

        // ---- shown: the profile, the switch, demo mode ----
        check("#252 shown when the profile says female and the switch was never touched", Cycles.shown(-1, 1, false) && !Cycles.shown(-1, 0, false) && !Cycles.shown(-1, 2, false));
        check("#252 the switch wins over the profile", Cycles.shown(1, 0, false) && !Cycles.shown(0, 1, false));
        check("#252 demo mode always shows it", Cycles.shown(0, 0, true));

        // ---- the demo's made up log ----
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 2);
        TreeMap<java.time.LocalDate, Integer> demo = Cycles.demo(today);
        c = Cycles.of(demo, null, today);
        int spot = 0;
        for (int f : demo.values()) if (f == Cycles.SPOTTING) spot++;
        boolean lengths = true, days = true;
        for (Cycles.Cycle x : c.cycles) lengths &= x.length >= 27 && x.length <= 31 && !x.leftOut;
        for (Cycles.Period x : c.periods) days &= x.days() >= 4 && x.days() <= 6;
        check("#252 demo: 8 cycles of 27 to 31 days, periods of 4 to 6, one spotting day", c.cycles.size() == 8 && lengths && days && spot == 1);
        check("#252 demo: today on cycle day 20, nothing logged after today", c.cycleDay(today) == Cycles.DEMO_DAY && Cycles.DEMO_DAY == 20 && demo.lastKey().isBefore(today) && c.estimates()
                && c.running == null && c.lateBy() == 0);

        // ---- plain texts, no dashes as punctuation ----
        List<String> texts = new ArrayList<String>(Arrays.asList(Cycles.NOT_ADVICE, c.status(EN), c.statusNote(EN), c.nextLine(), c.expectedLine(EN),
                c.ovulationLine(EN), c.fertileLine(EN), c.regularityLine(), c.describe(today.plusDays(10), EN),
                app.openring.ui.Explain.periodRange(0, 29, 5, Double.NaN), app.openring.ui.Explain.periodRange(2, 28, 5, Double.NaN),
                app.openring.ui.Explain.periodRange(4, 28, 3, 2.2), app.openring.ui.Explain.periodRange(8, 29, 3, 2.7),
                app.openring.ui.Explain.periodOvulation(1, 5), app.openring.ui.Explain.periodOvulation(8, 4), app.openring.ui.Explain.periodVary(8)));
        boolean plain = true;
        for (String t : texts) plain &= plain252(t);
        check("#252 texts use no dashes as punctuation", plain);
        check("#252 explain: how the range is worked out and from how many cycles",
                app.openring.ui.Explain.periodRange(8, 29, 3, 2.7).contains("the average of your last 8 cycles without the shortest and the longest")
                        && app.openring.ui.Explain.periodRange(8, 29, 3, 2.7).contains("About 4 in 5: the range is give or take 1.28 times how much your cycles vary")
                        && app.openring.ui.Explain.periodRange(4, 28, 3, 2.2).contains("the middle value of your 4 cycles")
                        && app.openring.ui.Explain.periodRange(1, 28, 5, Double.NaN).contains("the middle value of your 1 cycle")
                        && app.openring.ui.Explain.periodRange(0, 29, 5, Double.NaN).contains("give or take 5 days while there are fewer than 3 cycles"));
        check("#252 explain: give or take and vary by", app.openring.ui.Explain.periodOvulation(8, 4).startsWith("Ovulation day: give or take 4 days, from how much your last 8 cycles vary")
                && app.openring.ui.Explain.periodOvulation(2, 5).contains("a typical spread of 2.6 days")
                && app.openring.ui.Explain.periodVary(4).startsWith("Vary by: the standard deviation of your 4 cycles"));
    }

    /** Issue #252, phase 1a2: "Your cycles" over time, the night temperature line's window and touch text, "My periods have stopped". */
    static void cycles252b() {
        // ---- cycle length over every kept cycle; a far cycle is counted as left out ----
        int[] mix = {26, 30, 28, 31, 27, 29, 33, 25};
        java.time.LocalDate sm = lastStart252(mix);
        Cycles c = cyc252(log252(4, mix), sm.plusDays(10));
        Cycles.Summary s = c.cycleSummary();
        check("#252b cycle summary: 8 cycles, average 28.625, shortest 25, longest 33", s.n == 8 && Math.abs(s.mean - 28.625) < 1e-9 && s.min == 25 && s.max == 33);
        check("#252b cycle summary: the spread is the sample SD of all of them", Math.abs(s.sd - Math.sqrt(49.875 / 7)) < 1e-9);
        c = cyc252(log252(5, 28, 28, 58, 28, 28, 28), d252(205));
        s = c.cycleSummary();
        check("#252b a 58 day cycle is left out of the numbers and counted apart", s.n == 5 && s.mean == 28 && s.sd == 0 && c.leftOutCount() == 1 && c.kept().size() == 5);
        eq("#252b its row says why", c.cycleRow(c.cycles.get(2), EN), c.span(c.cycles.get(2).start, c.cycles.get(2).next.minusDays(1), EN)
                + ": 58 days, left out of the estimate, maybe a period was not logged");
        check("#252b its tip says left out", c.cycleTip(c.cycles.get(2), EN).endsWith(": 58 days, left out"));
        eq("#252b a usual cycle's tip", c.cycleTip(c.cycles.get(0), EN), "Cycle from " + c.date(C0, EN) + ": 28 days");
        c = cyc252(log252(5, 28, 28, 28, 28, 20), d252(160));
        check("#252b an unusual cycle's tip and row say so", c.cycleTip(c.cycles.get(4), EN).endsWith(": 20 days, unusual") && c.cycleRow(c.cycles.get(4), EN).endsWith(", unusual for you"));
        Cycles none = cyc252(log252(5), d252(3));
        s = none.cycleSummary();
        check("#252b no cycle: an empty summary", s.n == 0 && Double.isNaN(s.mean) && Double.isNaN(s.sd) && s.min == 0 && s.max == 0);

        // ---- period length and amount over the periods that are over ----
        Map<java.time.LocalDate, Integer> m = new HashMap<java.time.LocalDate, Integer>();
        // period 1: heavy, heavy, medium, (no log), light = 5 days, score 3 + 3 + 2 + 1 = 9
        m.put(d252(1), Cycles.HEAVY);
        m.put(d252(2), Cycles.HEAVY);
        m.put(d252(3), Cycles.MEDIUM);
        m.put(d252(5), Cycles.LIGHT);
        // period 2 from day 29: medium, light, light (3 days, score 4), spotting after it
        m.put(d252(29), Cycles.MEDIUM);
        m.put(d252(30), Cycles.LIGHT);
        m.put(d252(31), Cycles.LIGHT);
        m.put(d252(33), Cycles.SPOTTING);
        // period 3 from day 57, running on day 58
        m.put(d252(57), Cycles.HEAVY);
        c = cyc252(m, d252(58));
        check("#252b finished: the running period is not counted", c.periods.size() == 3 && c.finished().size() == 2 && c.running == c.periods.get(2));
        Cycles.Amount a = c.amountOf(c.periods.get(0));
        check("#252b amount: 2 heavy, 1 medium, 1 light, score 9 (a day without a log counts nothing)", a.heavy == 2 && a.medium == 1 && a.light == 1
                && a.spotting == 0 && a.score == 9);
        check("#252b amount: spotting after a period is not in it", c.amountOf(c.periods.get(1)).spotting == 0 && c.amountOf(c.periods.get(1)).score == 4);
        s = c.periodSummary();
        check("#252b period length: 5 and 3 days, average 4", s.n == 2 && s.mean == 4 && s.min == 3 && s.max == 5 && Math.abs(s.sd - Math.sqrt(2)) < 1e-9);
        s = c.amountSummary();
        check("#252b amount score: 9 and 4, average 6.5", s.n == 2 && s.mean == 6.5 && s.min == 4 && s.max == 9);
        eq("#252b amount tip", c.amountTip(c.periods.get(0), EN), c.date(d252(1), EN) + ": score 9 (2 heavy, 1 medium, 1 light)");
        eq("#252b amount tip, only light and medium", c.amountTip(c.periods.get(1), EN), c.date(d252(29), EN) + ": score 4 (1 medium, 2 light)");
        eq("#252b period tip", c.periodTip(c.periods.get(0), EN), "Period from " + c.date(d252(1), EN) + ": 5 days");
        Map<java.time.LocalDate, Integer> sp = new HashMap<java.time.LocalDate, Integer>();
        sp.put(d252(1), Cycles.LIGHT);
        sp.put(d252(2), Cycles.SPOTTING);
        sp.put(d252(3), Cycles.LIGHT);
        Cycles spc = cyc252(sp, d252(20));
        check("#252b spotting inside a period counts as spotting, score 0", spc.amountOf(spc.periods.get(0)).spotting == 1 && spc.amountOf(spc.periods.get(0)).score == 2
                && spc.amountTip(spc.periods.get(0), EN).endsWith("(2 light)"));
        Cycles fut = cyc252(log252(5), C0.minusDays(3));
        check("#252b a period logged for a later day is not over", fut.finished().isEmpty() && fut.periodSummary().n == 0);

        // ---- days to the next start ----
        int[] l12 = new int[12];
        Arrays.fill(l12, 28);
        java.time.LocalDate s12 = lastStart252(l12);
        Map<java.time.LocalDate, Integer> reg = log252(5, l12);
        eq("#252b 18 days to the next start on cycle day 11", cyc252(reg, s12.plusDays(10)).daysToNext(), 18);
        eq("#252b the expected day itself: 0", cyc252(reg, s12.plusDays(28)).daysToNext(), 0);
        eq("#252b late by 4: below 0", cyc252(reg, s12.plusDays(32)).daysToNext(), -4);
        check("#252b no estimate: null", cyc252(reg, s12.plusDays(57)).daysToNext() == null && Cycles.of(null, null, C0).daysToNext() == null);

        // ---- the night temperature line: its window and what touch reads ----
        c = cyc252(reg, s12.plusDays(10));
        eq("#252b temperature from the start of the period before the last", c.tempFrom(), s12.minusDays(28));
        eq("#252b one period: from its start", cyc252(log252(5), d252(10)).tempFrom(), C0);
        eq("#252b no period: the last 30 days", Cycles.of(null, null, d252(40)).tempFrom(), d252(11));
        eq("#252b a long gap: at most 100 days back", cyc252(log252(5, 28), d252(400)).tempFrom(), d252(400).minusDays(99));
        eq("#252b a period logged for later: not after today", fut.tempFrom(), C0.minusDays(3));
        String tip = c.tempTip(s12.plusDays(1), 0.24, EN);
        check("#252b temperature tip on a period day", tip.endsWith(": +0.2 °C, period day 2") && tip.startsWith(s12.plusDays(1).getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, EN)));
        check("#252b temperature tip on cycle day 10, no night", c.tempTip(s12.plusDays(9), Double.NaN, EN).endsWith(": no night, cycle day 10"));
        check("#252b temperature tip in the ovulation range", c.tempTip(s12.plusDays(12), -0.31, EN).endsWith(": -0.3 °C, cycle day 13, ovulation range"));
        Cycles lateC = cyc252(reg, s12.plusDays(28));
        check("#252b temperature tip on the expected day", lateC.tempTip(s12.plusDays(28), 0.0, EN).endsWith(": 0.0 °C, cycle day 29, expected period"));
        check("#252b before the first period: the date and the value only", c.tempTip(C0.minusDays(2), 0.1, EN).endsWith(": +0.1 °C"));

        // ---- "My periods have stopped" ----
        java.time.LocalDate last = s12.plusDays(4); // the last bleeding day
        Cycles st = Cycles.of(reg, null, s12.plusDays(80), s12.plusDays(40));
        check("#252b stopped: no estimate, no late line, no band", st.stopped && !st.estimates() && st.nextLine() == null && st.lateBy() == 0
                && !st.inExpected(s12.plusDays(56)) && !st.inOvulation(s12.plusDays(42)) && st.expectedLine(EN) == null && st.daysToNext() == null);
        eq("#252b stopped: the status names the last start", st.status(EN), "No period logged since " + st.date(s12, EN));
        eq("#252b stopped: the note", st.statusNote(EN), Cycles.STOPPED_NOTE);
        check("#252b stopped: the past cycles stay", st.cycles.size() == 12 && st.cycleSummary().n == 12 && st.periodSummary().n == 13);
        Cycles early = Cycles.of(reg, null, s12.plusDays(10), s12.plusDays(5));
        check("#252b stopped soon after a period: no estimate either (not yet waiting)", early.stopped && !early.waiting && !early.estimates()
                && early.status(EN).startsWith("No period logged since"));
        Map<java.time.LocalDate, Integer> again = new HashMap<java.time.LocalDate, Integer>(reg);
        again.put(s12.plusDays(40), Cycles.LIGHT);
        check("#252b a period day on the day it was said ends it by itself", !Cycles.of(again, null, s12.plusDays(41), s12.plusDays(40)).stopped);
        again.put(s12.plusDays(40), Cycles.SPOTTING);
        check("#252b spotting does not end it", Cycles.of(again, null, s12.plusDays(41), s12.plusDays(40)).stopped);
        Map<java.time.LocalDate, Integer> forgot = new HashMap<java.time.LocalDate, Integer>(reg);
        forgot.put(s12.plusDays(30), Cycles.MEDIUM);
        check("#252b a forgotten period day logged later for a day before it keeps it, one on that day ends it",
                Cycles.of(forgot, null, s12.plusDays(80), s12.plusDays(40)).stopped && !Cycles.of(forgot, null, s12.plusDays(80), s12.plusDays(30)).stopped);
        check("#252b while a period runs it is not in force", !Cycles.of(reg, null, s12.plusDays(5), s12.plusDays(5)).stopped);
        check("#252b without any period it is in force (the empty texts stay)", Cycles.of(null, null, C0, C0).stopped
                && Cycles.of(null, null, C0, C0).status(EN).equals("No period logged yet"));
        check("#252b not said: as before", !cyc252(reg, s12.plusDays(80)).stopped && cyc252(reg, s12.plusDays(80)).stoppedSince == null);
        check("#252b a year: no line before 365 days", Cycles.of(reg, null, last.plusDays(364), s12.plusDays(40)).yearLine(EN) == null);
        eq("#252b a year without a period, said plainly", Cycles.of(reg, null, last.plusDays(365), s12.plusDays(40)).yearLine(EN),
                "A year without a period now (the last one ended " + Cycles.of(reg, null, last.plusDays(365), null).date(last, EN)
                        + "). Twelve months without a period is how menopause is usually defined.");
        check("#252b a year without a period but not said: no line", cyc252(reg, last.plusDays(400)).yearLine(EN) == null);
        check("#252b day(): a date, else null", Cycles.day("2026-03-03").equals(java.time.LocalDate.of(2026, 3, 3)) && Cycles.day(null) == null
                && Cycles.day("3 Mar") == null && Cycles.day("") == null);

        // ---- the gentle question ----
        java.time.LocalDate t74 = last.plusDays(74);
        c = cyc252(reg, t74);
        check("#252b asked at 40 after 74 days without a period", c.waiting && c.daysWithout() == 74 && c.askStopped(40, null) && c.askStopped(67, null));
        eq("#252b the question", c.askLine(), "No period for 74 days. Have your periods stopped?");
        check("#252b never under 40", !c.askStopped(39, null) && !c.askStopped(0, null));
        check("#252b not when it is said already", !Cycles.of(reg, null, t74, t74.minusDays(3)).askStopped(50, null));
        check("#252b not yet waiting (twice the typical cycle) or under 60 days: not asked", !cyc252(reg, s12.plusDays(56)).askStopped(50, null)
                && !cyc252(reg, last.plusDays(59)).askStopped(50, null) && cyc252(reg, last.plusDays(60)).askStopped(50, null));
        check("#252b Not yet asks again only after 60 days", !c.askStopped(50, t74.minusDays(59)) && c.askStopped(50, t74.minusDays(60)));
        check("#252b no period at all: not asked", !Cycles.of(null, null, C0).askStopped(60, null) && Cycles.of(null, null, C0).daysWithout() == -1);
        Map<java.time.LocalDate, Integer> shortC = log252(4, 22, 22, 22, 22, 22);
        Cycles sc = cyc252(shortC, lastStart252(22, 22, 22, 22, 22).plusDays(50));
        check("#252b short cycles: waiting after 44 days, but asked only from 60 days", sc.waiting && sc.daysWithout() == 47 && !sc.askStopped(45, null));
        check("#252b the answers' note", Cycles.ASK_NOTE.contains("Not yet: the app asks again in 60 days."));

        // ---- the demo's warmer nights after ovulation ----
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 2);
        TreeSet<java.time.LocalDate> warm = Cycles.demoWarmNights(today);
        Cycles demo = Cycles.of(Cycles.demo(today), null, today);
        boolean inside = true;
        for (java.time.LocalDate d : warm) {
            java.time.LocalDate nextStart = null;
            for (Cycles.Period p : demo.periods) if (p.start.isAfter(d)) { nextStart = p.start; break; }
            if (nextStart == null) nextStart = demo.next;
            long before = java.time.temporal.ChronoUnit.DAYS.between(d, nextStart);
            inside &= before >= 1 && before <= Cycles.LUTEAL - 1 && !d.isAfter(today);
        }
        check("#252b demo: warm nights lie in the 13 days before a start, never after today", inside && !warm.isEmpty());
        check("#252b demo: 13 warm nights before each of the 8 starts, and the current cycle's after its ovulation estimate",
                warm.size() == 8 * 13 + (int) java.time.temporal.ChronoUnit.DAYS.between(demo.ovulation, today) && warm.contains(today) && !warm.contains(demo.ovulation));
        check("#252b demo: no warm night during a period", !warm.contains(demo.periods.get(demo.periods.size() - 1).start));

        // ---- plain texts ----
        List<String> texts = new ArrayList<String>(Arrays.asList(Cycles.STOPPED_NOTE, Cycles.ASK_NOTE, c.askLine(), st.yearLine(EN),
                app.openring.ui.Explain.PERIOD_CYCLE_DAY, app.openring.ui.Explain.periodNext(3, false, 0), app.openring.ui.Explain.periodNext(null, false, 0),
                app.openring.ui.Explain.periodNext(-2, false, 2), app.openring.ui.Explain.periodNext(null, true, 0),
                app.openring.ui.Explain.periodCycles(8, 0), app.openring.ui.Explain.periodCycles(1, 1), app.openring.ui.Explain.periodCycles(9, 2),
                app.openring.ui.Explain.periodLengths(1), app.openring.ui.Explain.periodLengths(9), app.openring.ui.Explain.PERIOD_AMOUNT,
                app.openring.ui.Explain.periodDots(true), app.openring.ui.Explain.periodDots(false), app.openring.ui.Explain.PERIOD_TEMP,
                app.openring.ui.Explain.PERIOD_REMIND, app.openring.ui.Explain.PERIOD_STOPPED));
        boolean plain = true;
        for (String t : texts) plain &= t == null || plain252(t);
        check("#252b texts use no dashes as punctuation", plain);
        check("#252b explain: from how many cycles, what is left out, the window of the estimate",
                app.openring.ui.Explain.periodCycles(9, 2).contains("over all your 9 cycles") && app.openring.ui.Explain.periodCycles(9, 2).contains("2 cycles more than 10 days from the middle value are left out")
                        && app.openring.ui.Explain.periodCycles(1, 1).contains("your 1 cycle") && app.openring.ui.Explain.periodCycles(1, 1).contains("1 cycle more than 10 days from the middle value is left out")
                        && app.openring.ui.Explain.periodCycles(8, 0).endsWith("Spread: how far a cycle usually lies from the average.") && !app.openring.ui.Explain.periodCycles(8, 0).contains("left out")
                        && app.openring.ui.Explain.PERIOD_WINDOW.contains("uses only your newest 12 cycles"));
        check("#252b explain: the amount is a relative score, not millilitres", app.openring.ui.Explain.PERIOD_AMOUNT.contains("not a measured amount")
                && app.openring.ui.Explain.PERIOD_TEMP.contains("the likely ovulation, the day before the first high night") && app.openring.ui.Explain.periodLengths(1).contains("your 1 period ")
                && app.openring.ui.Explain.periodNext(null, true, 0).contains("stopped"));
    }

    /** Issue #252, phase 1a2: the reminder before the expected period. */
    static void remind252() {
        java.time.ZoneId zurich = java.time.ZoneId.of("Europe/Zurich");
        check("#252r choices: Off, 1, 2, 3, 5", PeriodRemind.validDays(0) && PeriodRemind.validDays(5) && !PeriodRemind.validDays(4) && !PeriodRemind.validDays(-1) && !PeriodRemind.validDays(7));
        check("#252r time: within a day, else 9:00", PeriodRemind.minute(0) == 0 && PeriodRemind.minute(1439) == 1439 && PeriodRemind.minute(1440) == 540
                && PeriodRemind.minute(-5) == 540 && PeriodRemind.DEFAULT_MINUTE == 540);
        eq("#252r chips", PeriodRemind.chip(0) + "|" + PeriodRemind.chip(1) + "|" + PeriodRemind.chip(3), "Off|1 day|3 days");
        eq("#252r time chip", PeriodRemind.time(540) + "|" + PeriodRemind.time(7 * 60 + 5), "At 9:00|At 7:05");

        int[] l12 = new int[12];
        Arrays.fill(l12, 28);
        java.time.LocalDate s12 = lastStart252(l12); // next start s12 + 28, give or take 1: the range begins on s12 + 27
        Map<java.time.LocalDate, Integer> reg = log252(5, l12);
        java.time.LocalDate today = s12.plusDays(10);
        Cycles c = cyc252(reg, today);
        long now = today.atTime(12, 0).atZone(zurich).toInstant().toEpochMilli();
        PeriodRemind.Plan p = PeriodRemind.plan(c, 2, 540, zurich, now, null);
        long want = s12.plusDays(25).atTime(9, 0).atZone(zurich).toInstant().toEpochMilli();
        check("#252r 2 days before the range's first day, at 9:00", p.planned() && p.at == want && p.key.equals(s12.toString()));
        eq("#252r its line in Settings", PeriodRemind.status(p, zurich, now, EN), "Next reminder: "
                + s12.plusDays(25).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", EN)) + " at 9:00.");
        PeriodRemind.Plan p5 = PeriodRemind.plan(c, 5, 7 * 60 + 30, zurich, now, null);
        check("#252r 5 days before at 7:30", p5.at == s12.plusDays(22).atTime(7, 30).atZone(zurich).toInstant().toEpochMilli());
        check("#252r not due before its moment, due from 15 minutes before it", !PeriodRemind.due(p, want - 16 * 60000L) && PeriodRemind.due(p, want - 15 * 60000L)
                && PeriodRemind.due(p, want + 3600000L));
        // the moment has passed but the range is still to come: sent now
        long late = s12.plusDays(26).atTime(8, 0).atZone(zurich).toInstant().toEpochMilli();
        PeriodRemind.Plan pl = PeriodRemind.plan(cyc252(reg, s12.plusDays(26)), 2, 540, zurich, late, null);
        check("#252r a moment that has passed while the range is still to come: now", pl.planned() && pl.at == late && PeriodRemind.due(pl, late));
        eq("#252r sent now: the Settings line", PeriodRemind.status(pl, zurich, late, EN), "Next reminder: in a few minutes.");
        // never twice for one cycle
        PeriodRemind.Plan ps = PeriodRemind.plan(c, 2, 540, zurich, now, s12.toString());
        check("#252r never twice for one cycle", !ps.planned() && ps.why == PeriodRemind.SENT && !PeriodRemind.due(ps, now)
                && PeriodRemind.status(ps, zurich, now, EN).equals("The reminder for this period was sent."));
        check("#252r a key of an older cycle does not stop the new one", PeriodRemind.plan(c, 2, 540, zurich, now, s12.minusDays(28).toString()).planned());
        // the range has begun: none
        PeriodRemind.Plan pb = PeriodRemind.plan(cyc252(reg, s12.plusDays(27)), 2, 540, zurich, s12.plusDays(27).atTime(6, 0).atZone(zurich).toInstant().toEpochMilli(), null);
        check("#252r once the range has begun: none", pb.why == PeriodRemind.BEGUN && PeriodRemind.status(pb, zurich, now, EN).startsWith("No reminder for this period"));
        // off, no log, running, waiting, stopped
        check("#252r off", PeriodRemind.plan(c, 0, 540, zurich, now, null).why == PeriodRemind.OFF
                && PeriodRemind.status(PeriodRemind.plan(c, 0, 540, zurich, now, null), zurich, now, EN).equals("Reminders are off."));
        check("#252r no log", PeriodRemind.plan(null, 2, 540, zurich, now, null).why == PeriodRemind.NO_LOG
                && PeriodRemind.plan(Cycles.of(null, null, today), 2, 540, zurich, now, null).why == PeriodRemind.NO_LOG);
        PeriodRemind.Plan pr = PeriodRemind.plan(cyc252(reg, s12.plusDays(2)), 2, 540, zurich, now, null);
        check("#252r never while a period is logged", pr.why == PeriodRemind.RUNNING && PeriodRemind.status(pr, zurich, now, EN).equals("No reminder while a period is logged."));
        check("#252r waiting for the next start: none", PeriodRemind.plan(cyc252(reg, s12.plusDays(57)), 2, 540, zurich, now, null).why == PeriodRemind.WAITING);
        PeriodRemind.Plan pst = PeriodRemind.plan(Cycles.of(reg, null, today, today), 2, 540, zurich, now, null);
        check("#252r stopped: none", pst.why == PeriodRemind.STOPPED && PeriodRemind.status(pst, zurich, now, EN).contains("stopped"));
        // a new period logged early: the next cycle gets its own reminder
        Map<java.time.LocalDate, Integer> early = new HashMap<java.time.LocalDate, Integer>(reg);
        for (int k = 0; k < 4; k++) early.put(s12.plusDays(24 + k), Cycles.MEDIUM);
        java.time.LocalDate after = s12.plusDays(32);
        PeriodRemind.Plan pn = PeriodRemind.plan(cyc252(early, after), 2, 540, zurich, after.atTime(12, 0).atZone(zurich).toInstant().toEpochMilli(), s12.toString());
        check("#252r the next cycle after an early period: planned again", pn.planned() && pn.key.equals(s12.plusDays(24).toString()));
        // clock changes: spring forward in Zurich on 29 March 2026 (02:00 to 03:00). 13 periods 28 days apart, the last on
        // 3 March: next start 31 March give or take 1, so 1 day before the range is 29 March, and 02:30 does not exist then
        Map<java.time.LocalDate, Integer> dst = new HashMap<java.time.LocalDate, Integer>();
        java.time.LocalDate st = java.time.LocalDate.of(2026, 3, 3);
        for (int i = 0; i <= 12; i++) for (int k = 0; k < 4; k++) dst.put(st.minusDays(28 * i).plusDays(k), Cycles.MEDIUM);
        Cycles cd = Cycles.of(dst, null, java.time.LocalDate.of(2026, 3, 10));
        long nowD = java.time.LocalDate.of(2026, 3, 10).atTime(12, 0).atZone(zurich).toInstant().toEpochMilli();
        PeriodRemind.Plan pd = PeriodRemind.plan(cd, 1, 2 * 60 + 30, zurich, nowD, null);
        check("#252r the clocks spring forward: 02:30 on 29 March does not exist, the reminder comes at 03:30 that day",
                cd.next.equals(java.time.LocalDate.of(2026, 3, 31)) && cd.range == 1
                        && pd.at == java.time.Instant.parse("2026-03-29T01:30:00Z").toEpochMilli());
        PeriodRemind.Plan pd9 = PeriodRemind.plan(cd, 1, 540, zurich, nowD, null);
        check("#252r the clocks spring forward: 9:00 stays 9:00 on the new clock", pd9.at == java.time.Instant.parse("2026-03-29T07:00:00Z").toEpochMilli());
        // the same plan in another zone: 9:00 on that zone's clock (planned again after a time zone change)
        java.time.ZoneId ny = java.time.ZoneId.of("America/New_York");
        PeriodRemind.Plan pz = PeriodRemind.plan(c, 2, 540, ny, now, null);
        check("#252r after a time zone change: 9:00 on the new clock", pz.at == s12.plusDays(25).atTime(9, 0).atZone(ny).toInstant().toEpochMilli() && pz.at != p.at);
        // the text
        eq("#252r text 2 days before", PeriodRemind.text(c, s12.plusDays(25), EN), "Your next period may start in about 2 days (expected "
                + c.span(s12.plusDays(27), s12.plusDays(29), EN) + ").");
        check("#252r text the day before", PeriodRemind.text(c, s12.plusDays(26), EN).startsWith("Your next period may start tomorrow (expected "));
        check("#252r text once the range began", PeriodRemind.text(c, s12.plusDays(27), EN).startsWith("Your next period may start any day now"));
        check("#252r titles: the lock screen shows only the app's reminder", PeriodRemind.TITLE.equals("Period expected soon") && PeriodRemind.PUBLIC_TITLE.equals("Open Ring reminder"));
        boolean plain = plain252(PeriodRemind.text(c, s12.plusDays(25), EN));
        for (int why = 0; why <= 7; why++) plain &= plain252(PeriodRemind.status(new PeriodRemind.Plan(why, now + 86400000L, "k"), zurich, now, EN));
        check("#252r texts use no dashes as punctuation", plain);
    }

    /** Issue #251, phase 1a: the Watch check's report, names, the 2 byte rule, the length limit, the demo and the ring lock. */
    static void updatesLink() {
        eq("updates: stable opens the latest release", Updates.url("1.93"), Updates.LATEST);
        eq("updates: nightly opens all releases", Updates.url("1.93 nightly abc1234"), Updates.ALL);
        eq("updates: unknown version opens the latest release", Updates.url(""), Updates.LATEST);
        eq("updates: no version name opens the latest release", Updates.url(null), Updates.LATEST);
        check("updates: nightly found in any case", Updates.nightly("1.93 Nightly abc1234"));
        check("updates: a stable version is not a nightly", !Updates.nightly("1.93"));
        check("updates: the nightly note names both", Updates.note("1.93 nightly abc1234").contains("nightly at the top")
                && Updates.note("1.93 nightly abc1234").contains("stable release below"));
        check("updates: the stable note says no internet", Updates.note("1.93").contains("no internet access"));
        for (String v : new String[] {"1.93", "1.93 nightly abc1234"}) {
            String n = Updates.note(v);
            check("updates: no dash in the note " + v, !n.contains(" - ") && !n.contains("\u2013") && !n.contains("\u2014"));
        }
    }

    static void watchCheck251() throws Exception {
        String base = "-0000-1000-8000-00805f9b34fb";
        // property names
        eq("watch: no properties", WatchCheck.properties(0), "none");
        eq("watch: read and notify", WatchCheck.properties(0x12), "read, notify");
        eq("watch: every property named once", WatchCheck.properties(0xff),
                "broadcast, read, write without answer, write, notify, indicate, signed write, extended");
        eq("watch: unknown higher bits are ignored", WatchCheck.properties(0x108), "write");
        check("watch: notify and indicate send, write does not", WatchCheck.sends(0x10) && WatchCheck.sends(0x20) && !WatchCheck.sends(0x0e));

        // UUID names: known standard ones named, other standard ones short, unknown kept as they are
        eq("watch: device information named", WatchCheck.name("0000180A" + base.toUpperCase()), "0x180a Device Information");
        eq("watch: model number named", WatchCheck.name("00002a24" + base), "0x2a24 Model Number");
        eq("watch: battery level named", WatchCheck.name("00002a19" + base), "0x2a19 Battery Level");
        eq("watch: an unnamed standard UUID stays short", WatchCheck.name("0000fff1" + base), "0xfff1");
        String odd = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
        eq("watch: an unknown UUID is kept as it is", WatchCheck.name(odd), odd);
        eq("watch: the Withings looking UUID is marked", WatchCheck.name(WatchCheck.WITHINGS_SERVICE), WatchCheck.WITHINGS_SERVICE + " (looks like Withings)");
        check("watch: WITH in ASCII is 5749 5448", "WITH".equals(new String(Hex.decode("57495448"), java.nio.charset.StandardCharsets.US_ASCII)));
        check("watch: standard only with the base UUID", WatchCheck.standard("00002a37" + base) && !WatchCheck.standard(odd)
                && !WatchCheck.standard(WatchCheck.WITHINGS_SERVICE) && !WatchCheck.standard(null) && WatchCheck.shortId("12342a37" + base) == null);
        eq("watch: no UUID", WatchCheck.name(null), "?");

        // only named devices are listed, and an unnamed one that advertises the Withings service (owner, 3 Nov)
        WatchCheck.Found unnamed = new WatchCheck.Found("a", null, -60), blank = new WatchCheck.Found("b", "  ", -61),
                named = new WatchCheck.Found("c", "FnR 6ATAG72R", -62), withingsNoName = new WatchCheck.Found("d", null, -63);
        withingsNoName.service("00000020-5749-5448-0037-000000000000");
        List<WatchCheck.Found> listed = WatchCheck.shown(Arrays.asList(unnamed, blank, named, withingsNoName));
        eq("watch check: unnamed devices are hidden, a Withings without a name stays", listed.size() + " " + listed.get(0).address + listed.get(1).address, "2 cd");
        // scan list: strongest first, unknown signal (127) last, then by name
        List<WatchCheck.Found> in = new ArrayList<WatchCheck.Found>();
        in.add(new WatchCheck.Found("a", "Far", -90));
        in.add(new WatchCheck.Found("b", "Unknown", WatchCheck.NO_RSSI));
        in.add(new WatchCheck.Found("c", "Near", -40));
        in.add(new WatchCheck.Found("d", null, -60));
        in.add(new WatchCheck.Found("e", "Also near", -40));
        List<WatchCheck.Found> sorted = WatchCheck.strongestFirst(in);
        StringBuilder order = new StringBuilder();
        for (WatchCheck.Found f : sorted) order.append(f.address);
        eq("watch: strongest first, unknown signal last", order.toString(), "ecdab");
        eq("watch: a device without a name", WatchCheck.title(in.get(3)), "(no name)");
        eq("watch: the input list is left as it was", in.get(0).address, "a");
        // while scanning, shown rows keep their place and new ones come after them, strongest first (review of #270)
        List<String> shown = new ArrayList<String>();
        shown.add("a");
        shown.add("d");
        StringBuilder stable = new StringBuilder();
        for (WatchCheck.Found f : WatchCheck.stableOrder(shown, in)) stable.append(f.address);
        eq("watch: shown rows stay in place during a scan, new ones follow strongest first", stable.toString(), "adecb");
        StringBuilder fresh = new StringBuilder();
        for (WatchCheck.Found f : WatchCheck.stableOrder(new ArrayList<String>(), in)) fresh.append(f.address);
        eq("watch: the first rows of a scan are strongest first", fresh.toString(), "ecdab");
        shown.add("gone");
        eq("watch: an address no longer heard is left out", WatchCheck.stableOrder(shown, in).size(), 5);
        WatchCheck.Found w = new WatchCheck.Found("x", null, -55).service(WatchCheck.WITHINGS_SERVICE).service(WatchCheck.WITHINGS_SERVICE.toUpperCase());
        eq("watch: a service is listed once", w.services.size(), 1);
        check("watch: the Withings service makes it likely a watch", WatchCheck.likelyWatch(w));
        check("watch: a Steel HR name makes it likely a watch", WatchCheck.likelyWatch(new WatchCheck.Found("y", "Steel HR", -60)));
        check("watch: headphones are not", !WatchCheck.likelyWatch(new WatchCheck.Found("z", "Headphones", -60)));
        eq("watch: scan row detail", WatchCheck.detail(new WatchCheck.Found("z", "Headphones", -61)), "Signal -61 dBm, no services advertised");
        check("watch: scan row with the watch's service", WatchCheck.detail(w).startsWith("Signal -55 dBm, services: " + WatchCheck.WITHINGS_SERVICE)
                && WatchCheck.detail(w).endsWith("Looks like a Withings watch"));
        eq("watch: unknown signal", WatchCheck.detail(new WatchCheck.Found("u", "U", WatchCheck.NO_RSSI)), "Signal unknown, no services advertised");

        // a report from a constructed layout
        WatchCheck.Report r = new WatchCheck.Report();
        r.name = "Watch";
        r.rssi = -50;
        r.connected = true;
        r.model = "M1";
        r.firmware = "F2";
        r.battery = 77;
        r.connectMs = 1234;
        r.listenMs = 10000;
        WatchCheck.Service info = r.add("0000180a" + base);
        info.add("00002a24" + base, 0x02);
        WatchCheck.Service hr = r.add("0000180d" + base);
        hr.add("00002a37" + base, 0x10); // standard heart rate: never listened to
        WatchCheck.Service ws = r.add(WatchCheck.WITHINGS_SERVICE);
        WatchCheck.Characteristic quiet = ws.add("00000024-5749-5448-0037-000000000000", 0x10);
        quiet.listening = true;
        WatchCheck.Characteristic busy = ws.add("00000025-5749-5448-0037-000000000000", 0x20);
        busy.listening = true;
        // the bytes after the first 2 are values that must never reach the default report
        busy.message(Hex.decode("0105a1b2c3d4e5"));
        busy.message(Hex.decode("07"));
        busy.message(Hex.decode("0203f9e8"));
        WatchCheck.Characteristic refused = ws.add("00000026-5749-5448-0037-000000000000", 0x18);
        refused.listening = false;
        String t = WatchCheck.text(r, false);
        check("watch: report names model, firmware, battery", t.contains("Model: M1\n") && t.contains("Firmware: F2\n") && t.contains("Battery: 77%\n"));
        check("watch: report has the connection times", t.contains("Connected in 1.2 s, listened 10.0 s"));
        check("watch: report names services and characteristics with properties",
                t.contains("Service 0x180a Device Information\n  0x2a24 Model Number: read\n"));
        check("watch: the standard heart rate is not listened to", t.contains("0x2a37 Heart Rate Measurement: notify (standard, not listened to)"));
        check("watch: a quiet characteristic says 0 messages", t.contains("00000024-5749-5448-0037-000000000000: notify (listened)\n    0 messages\n"));
        check("watch: counts, lengths and first bytes, not of a message of 2 bytes or less", t.contains("3 messages, lengths 7 1 4\n    first bytes: message 1 0105, message 3 0203\n"));
        check("watch: a message of 1 byte is not in the default report", !t.contains(" 07"));
        check("watch: a refused switch is named", t.contains("write, notify (did not switch on)"));
        for (String value : new String[] {"a1", "b2", "c3", "d4", "e5", "f9", "e8"}) {
            check("watch: the default report holds no byte beyond the first 2 (" + value + ")", !t.contains(value));
        }
        String full = WatchCheck.text(r, true);
        check("watch: the private copy holds the whole messages", full.contains("bytes 0105a1b2c3d4e5 07 0203f9e8") && full.startsWith("Open Ring watch check (with message bytes, private)"));
        check("watch: the bonded line is there", t.contains("Paired with this phone before: no"));
        check("watch: the bond after the check is reported", t.contains("Paired now: no") && !WatchCheck.pairedDuring(r));
        r.bondedAfter = true;
        check("watch: a bond made during the check is named, with how to remove it", WatchCheck.text(r, false).contains("Paired now: yes")
                && WatchCheck.pairedDuring(r) && WatchCheck.PAIRED_NOW.contains("Forget"));
        r.bondedBefore = true;
        check("watch: a bond from before is not blamed on the check", !WatchCheck.pairedDuring(r) && !WatchCheck.pairedDuring(null));
        r.bondedBefore = r.bondedAfter = false;

        // the 2 byte rule on random messages: every hex group in the default report is at most 4 digits
        Random rnd = new Random(251);
        WatchCheck.Report rr = new WatchCheck.Report();
        rr.connected = true;
        WatchCheck.Characteristic rc = rr.add(WatchCheck.WITHINGS_SERVICE).add("00000030-5749-5448-0037-000000000000", 0x10);
        rc.listening = true;
        for (int i = 0; i < 100; i++) {
            byte[] m = new byte[rnd.nextInt(30)];
            rnd.nextBytes(m);
            rc.message(m);
        }
        String rt = WatchCheck.text(rr, false);
        String firstLine = rt.substring(rt.indexOf("first bytes: ") + 13);
        firstLine = firstLine.substring(0, firstLine.indexOf('\n'));
        boolean twoBytes = true;
        int groups = 0;
        for (String g : firstLine.split(", ")) {
            groups++;
            String hex = g.substring(g.lastIndexOf(' ') + 1);
            if (hex.length() != 2 * WatchCheck.TYPE_BYTES) twoBytes = false;
        }
        int wanted = 0;
        for (int i = 0; i < WatchCheck.TYPE_MESSAGES; i++) if (rc.messages.get(i).length > WatchCheck.TYPE_BYTES) wanted++;
        check("watch: random messages, 2 bytes each of the first 3 longer ones only", twoBytes && groups == wanted && groups <= WatchCheck.TYPE_MESSAGES);

        // continuation pieces (review of #270): from the 4th message on only lengths, also for many long pieces
        WatchCheck.Report pieces = new WatchCheck.Report();
        pieces.connected = true;
        WatchCheck.Characteristic pc = pieces.add(WatchCheck.WITHINGS_SERVICE).add("00000033-5749-5448-0037-000000000000", 0x10);
        pc.listening = true;
        pc.message(Hex.decode("0101aaaa"));
        pc.message(Hex.decode("bbcc")); // a piece of 2 bytes: all value
        pc.message(Hex.decode("0102dddd"));
        for (int i = 0; i < 50; i++) pc.message(Hex.decode("eeff1122"));
        String pt = WatchCheck.text(pieces, false);
        check("watch: first bytes only of the first 3 messages longer than 2 bytes", pt.contains("\n    first bytes: message 1 0101, message 3 0102\n"));
        check("watch: no bytes of a 2 byte piece or of later pieces", !pt.contains("bb") && !pt.contains("cc") && !pt.contains("dd") && !pt.contains("ee")
                && !pt.contains("ff") && !pt.contains("1122"));
        check("watch: later pieces still give their lengths", pt.contains("53 messages, lengths 4 2 4 4"));
        check("watch: the private copy has the pieces whole", WatchCheck.text(pieces, true).contains("bytes 0101aaaa bbcc 0102dddd eeff1122"));
        WatchCheck.Report tiny = new WatchCheck.Report();
        tiny.connected = true;
        WatchCheck.Characteristic tc = tiny.add(WatchCheck.WITHINGS_SERVICE).add("00000034-5749-5448-0037-000000000000", 0x10);
        tc.listening = true;
        tc.message(Hex.decode("4a"));
        tc.message(Hex.decode("4b5c"));
        String tt = WatchCheck.text(tiny, false);
        check("watch: only short messages give lengths and no first bytes", tt.contains("2 messages, lengths 1 2\n") && !tt.contains("first bytes") && !tt.contains("4a")
                && !tt.contains("4b"));

        // memory and length limits
        WatchCheck.Report big = new WatchCheck.Report();
        big.connected = true;
        WatchCheck.Characteristic bc = big.add(WatchCheck.WITHINGS_SERVICE).add("00000031-5749-5448-0037-000000000000", 0x10);
        bc.listening = true;
        byte[] long1 = new byte[240];
        for (int i = 0; i < WatchCheck.KEPT_MESSAGES + 50; i++) bc.message(long1);
        eq("watch: messages kept per characteristic", bc.messages.size(), WatchCheck.KEPT_MESSAGES);
        eq("watch: the rest are counted", bc.dropped, 50);
        check("watch: the count names all messages", WatchCheck.text(big, false).contains((WatchCheck.KEPT_MESSAGES + 50) + " messages (the first " + WatchCheck.KEPT_MESSAGES + " kept)"));
        String bt = WatchCheck.text(big, true);
        check("watch: the private copy is cut at the limit", bt.length() <= WatchCheck.REPORT_MAX && bt.endsWith(WatchCheck.CUT));
        for (int i = 0; i < 40; i++) {
            WatchCheck.Service s = big.add(String.format(Locale.ROOT, "%08x-5749-5448-0037-000000000000", 0x100 + i));
            for (int k = 0; k < 20; k++) s.add(String.format(Locale.ROOT, "%08x-1111-2222-3333-444444444444", k), 0xff);
        }
        String lt = WatchCheck.text(big, false);
        check("watch: the default report is never longer than the copy limit", lt.length() <= WatchCheck.REPORT_MAX && lt.endsWith("\n" + WatchCheck.CUT));
        String shortOne = WatchCheck.text(r, false);
        check("watch: a short report is not cut", !shortOne.contains(WatchCheck.CUT) && WatchCheck.cut(shortOne).equals(shortOne));
        StringBuilder oneLine = new StringBuilder();
        for (int i = 0; i < WatchCheck.REPORT_MAX + 10; i++) oneLine.append('x');
        String cutLine = WatchCheck.cut(oneLine.toString());
        check("watch: a report of one long line is cut too", cutLine.length() <= WatchCheck.REPORT_MAX && cutLine.endsWith(WatchCheck.CUT));
        check("watch: no message is kept from null", bcNull());

        // a watch that did not answer
        WatchCheck.Report none = new WatchCheck.Report();
        none.name = "";
        none.detail = "no answer within 10 s";
        String nt = WatchCheck.text(none, false);
        check("watch: no answer says so", nt.contains("Name: (no name)\n") && nt.contains("Connected: no\n") && nt.contains("Detail: no answer within 10 s")
                && nt.contains("Model: not shown") && nt.contains("Battery: not shown") && !nt.contains("Signal:") && !nt.contains("Ended early"));
        eq("watch: no answer on the screen", WatchCheck.outcome(none), WatchCheck.NO_ANSWER);
        none.problem = WatchCheck.ENDED_REFUSED;
        eq("watch: a refused connection on the screen", WatchCheck.outcome(none), WatchCheck.ENDED_REFUSED);

        // the screen line in plain words (review of #270): status codes and error names only in the report
        WatchCheck.Report off = new WatchCheck.Report();
        off.connected = true;
        off.problem = WatchCheck.ENDED_BLUETOOTH;
        off.detail = "SecurityException";
        String screen = WatchCheck.outcome(off);
        eq("watch: Bluetooth off on the screen", screen, "The check ended early. Bluetooth was switched off or the permission was taken back. What it found so far is below.");
        check("watch: the error name stays in the report", WatchCheck.text(off, false).contains("Ended early: " + WatchCheck.ENDED_BLUETOOTH + "\nDetail: SecurityException\n"));
        off.problem = null;
        off.detail = null;
        eq("watch: a check that ran through", WatchCheck.outcome(off), "Done. The watch was let go again.");
        for (String e : new String[] {WatchCheck.ENDED_STOPPED, WatchCheck.ENDED_REFUSED, WatchCheck.ENDED_LAYOUT, WatchCheck.ENDED_BY_WATCH, WatchCheck.ENDED_BLUETOOTH}) {
            off.connected = true;
            off.problem = e;
            String o = WatchCheck.outcome(off);
            check("watch: screen line without brackets or class names: " + e, !o.contains("(") && !o.contains(")") && !o.contains("Exception")
                    && Character.isUpperCase(e.charAt(0)) && e.endsWith("."));
        }
        WatchCheck.Report empty = new WatchCheck.Report();
        empty.connected = true;
        check("watch: a layout without services says so", WatchCheck.text(empty, false).contains("No services found") && WatchCheck.text(empty, false).contains("listened ?"));

        // demo mode: the made up scan and report go through the same builders
        List<WatchCheck.Found> demo = WatchCheck.strongestFirst(WatchCheck.demoFound());
        check("watch: demo, the made up watch is the strongest and likely a watch", demo.get(0).name.contains("example") && WatchCheck.likelyWatch(demo.get(0)));
        check("watch: demo, the watch is the second of the made up list (the screen picks it)", WatchCheck.likelyWatch(WatchCheck.demoFound().get(1)));
        String dt = WatchCheck.text(WatchCheck.demoReport(), false);
        check("watch: demo report built like a real one", dt.startsWith("Open Ring watch check\nName: Steel HR (example)\n") && dt.contains("(looks like Withings)")
                && dt.contains("2 messages, lengths 5 6\n    first bytes: message 1 0101, message 2 0105\n") && dt.contains("Battery: 80%"));
        check("watch: demo report has no byte beyond the first 2", !dt.contains("010100") && !dt.contains("0105000200"));

        // texts: plain, no dashes as punctuation, toasts short enough
        for (String s : new String[] {WatchCheck.INTRO, WatchCheck.SIGNAL, WatchCheck.PAIR_NOTE, WatchCheck.REPORT_NOTE, WatchCheck.PRIVATE, WatchCheck.SEND_BACK,
            WatchCheck.NO_ANSWER, WatchCheck.BUSY, WatchCheck.DEMO, WatchCheck.NOTHING_FOUND, WatchCheck.CUT, WatchCheck.RUNNING, WatchCheck.ENDED_STOPPED,
            WatchCheck.ENDED_REFUSED, WatchCheck.ENDED_LAYOUT, WatchCheck.ENDED_BY_WATCH, WatchCheck.ENDED_BLUETOOTH, screen, t, dt}) {
            check("watch: no dash as punctuation in " + s.substring(0, Math.min(30, s.length())), !s.contains(" - ") && !s.contains("–") && !s.contains("—"));
        }
        check("watch: what to send back matches the decisions on #251", WatchCheck.SEND_BACK.contains("comment on issue 251 on GitHub")
                && WatchCheck.SEND_BACK.contains("model name") && WatchCheck.SEND_BACK.contains("firmware") && WatchCheck.SEND_BACK.contains("battery % on 3 mornings"));
        check("watch: the report note names the first 3 messages", WatchCheck.REPORT_NOTE.contains("first 3 messages") && WatchCheck.TYPE_MESSAGES == 3);
        check("watch: the check takes at most 30 s, connect 10 s, listen 10 s", WatchCheck.SESSION_MS == 30000 && WatchCheck.CONNECT_MS == 10000
                && WatchCheck.LISTEN_MS == 10000 && WatchCheck.CONNECT_MS + WatchCheck.LISTEN_MS < WatchCheck.SESSION_MS);

        // the ring always wins: the check takes the ring lock only when it is free, never while a sync is counted
        RingGate g = new RingGate();
        check("watch: a free ring lock is taken", g.takeIfFree() && g.op.isHeldByCurrentThread());
        g.op.unlock();
        g.syncs.incrementAndGet();
        check("watch: a sync under way keeps the check out", !g.takeIfFree() && !g.op.isLocked());
        g.syncs.decrementAndGet();
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), go = new java.util.concurrent.CountDownLatch(1);
        Thread ring = holdRing(g, held, go);
        held.await();
        long t0 = System.currentTimeMillis();
        check("watch: a ring task holding the lock keeps the check out, without a wait", !g.takeIfFree() && System.currentTimeMillis() - t0 < 1000);
        go.countDown();
        ring.join();
        check("watch: once the ring task ended the check may run", g.takeIfFree());
        g.op.unlock();
    }

    static boolean bcNull() {
        WatchCheck.Characteristic c = new WatchCheck.Characteristic("00000032-5749-5448-0037-000000000000", 0x10);
        c.message(null);
        byte[] v = {1, 2, 3};
        c.message(v);
        v[0] = 9;
        return c.messages.size() == 1 && c.messages.get(0)[0] == 1;
    }

    /** Issue #133: typed but unsaved numbers in Settings survive a rebuild and a turn of the phone. */
    static void drafts() {
        Drafts d = new Drafts();
        eq("drafts: nothing kept gives the saved number", d.text("age", 35), "35");
        check("drafts: nothing kept is not kept", !d.has("age"));
        d.keep("age", "41");
        eq("drafts: a kept text wins over the saved number", d.text("age", 35), "41");
        eq("drafts: other inputs keep their saved number", d.text("weight", 72), "72");
        d.keep("weight", new StringBuilder(""));
        eq("drafts: an emptied input stays empty", d.text("weight", 72), "");
        check("drafts: an empty text is kept", d.has("weight"));
        d.keep("age", "42");
        eq("drafts: keeping again replaces", d.text("age", 35), "42");
        d.keep("age", null);
        eq("drafts: no text keeps the last one", d.text("age", 35), "42");
        d.keep(null, "7");
        eq("drafts: no name keeps nothing", d.toArray().length, 4);
        Drafts back = Drafts.fromArray(d.toArray());
        eq("drafts: round trip age", back.text("age", 0), "42");
        eq("drafts: round trip empty weight", back.text("weight", 72), "");
        eq("drafts: round trip keeps the order", Arrays.toString(back.toArray()), "[age, 42, weight, ]");
        check("drafts: no saved state gives none", !Drafts.fromArray(null).has("age"));
        Drafts broken = Drafts.fromArray(new String[] {"age", "42", "weight"});
        check("drafts: a broken list gives none", !broken.has("age") && broken.toArray().length == 0);
        long t = 1_800_000_000_000L;
        check("drafts: saved a second ago is recent", Drafts.recent(t, t + 1000));
        check("drafts: saved just under 10 minutes ago is recent", Drafts.recent(t, t + Drafts.RECENT_MS - 1));
        check("drafts: saved 10 minutes ago is old", !Drafts.recent(t, t + Drafts.RECENT_MS));
        check("drafts: saved hours ago is old", !Drafts.recent(t, t + 5 * 3600_000L));
        check("drafts: no save time is old", !Drafts.recent(0, t));
        check("drafts: a clock set back counts as old", !Drafts.recent(t, t - 1000));
        eq("drafts: a null text in the list counts as not kept", Drafts.fromArray(new String[] {"kcal", null}).text("kcal", 0), "0");
    }

    /** Issue #130: one repair at a time, reported to the screen shown now (a turned phone builds a new one). */
    /** Issue #242: the rules and texts of the automatic backups. */
    static void backups242() {
        // the copy before an update
        eq("backups: same version, nothing", Backups.beforeUpdate(43, 43, true), Backups.SAME);
        eq("backups: another version with a history, copy first", Backups.beforeUpdate(42, 43, true), Backups.COPY);
        eq("backups: nothing noted yet but a history (updating from a version that noted nothing), copy first", Backups.beforeUpdate(0, 43, true), Backups.COPY);
        eq("backups: a new installation, note only", Backups.beforeUpdate(0, 43, false), Backups.NOTE);
        eq("backups: another version without a history, note only", Backups.beforeUpdate(42, 43, false), Backups.NOTE);
        eq("backups: a lower version (reinstalled older app), copy first", Backups.beforeUpdate(44, 43, true), Backups.COPY);
        eq("backups: the version cannot be read, nothing", Backups.beforeUpdate(42, 0, true), Backups.SAME);
        long mb = 1024 * 1024;
        check("backups: room for the copy and 50 MB", Backups.enoughSpace(260 * mb, 210 * mb));
        check("backups: 1 byte short of the 50 MB, no copy", !Backups.enoughSpace(260 * mb - 1, 210 * mb));
        check("backups: free space unknown (0), no copy", !Backups.enoughSpace(0, 1));
        // names of the phone copies
        eq("backups: copy name", Backups.copyName(43, "1.42", Backups.RAW), "before-43-1.42.raw");
        eq("backups: odd characters left out of a version name", Backups.copyName(43, "1.42-beta /x", Backups.CHECKED), "before-43-1.42betax.db");
        eq("backups: no version name", Backups.copyName(43, null, Backups.CHECKED), "before-43-unknown.db");
        Backups.Copy cp = Backups.parse("before-43-1.42.raw");
        check("backups: a copy parses back", cp != null && cp.code == 43 && "1.42".equals(cp.version) && Backups.RAW.equals(cp.state) && !cp.back);
        eq("backups: the same copy after its check", cp.named(Backups.CHECKED), "before-43-1.42.db");
        Backups.Copy bk = Backups.parse("back-1793000000.db");
        check("backups: a copy before going back parses", bk != null && bk.back && bk.code == 1793000000L && bk.version == null);
        eq("backups: the copy before going back after its check", bk.named(Backups.BAD), "back-1793000000.bad");
        for (String n : new String[] {"before-43-1.42.raw-wal", "before-43-1.42.db-shm", "notes.txt", "before-x-1.42.db", "back-1.raw", null,
            "before-99999999999-1.42.db"}) check("backups: not a copy: " + n, Backups.parse(n) == null);
        // which phone copies go
        List<String> fs = Arrays.asList("before-40-1.39.db", "before-41-1.40.db", "before-42-1.41.bad", "before-43-1.42.raw", "before-44-1.43.partial",
            "back-10.db", "back-20.db", "back-30.partial", "notes.txt");
        eq("backups: two checked copies newer than 40 (41 and none else): nothing goes yet", Backups.phoneToDelete(Arrays.asList("before-40-1.39.db", "before-41-1.40.db",
            "before-42-1.41.bad", "before-43-1.42.raw")), Arrays.asList());
        eq("backups: the older copy before going back goes, nothing of the copies before updates", Backups.phoneToDelete(fs), Arrays.asList("back-10.db"));
        eq("backups: once two newer passed, older checked and bad copies go", Backups.phoneToDelete(Arrays.asList("before-40-1.39.db", "before-41-1.40.bad",
            "before-42-1.41.db", "before-43-1.42.db", "before-44-1.43.raw")), Arrays.asList("before-41-1.40.bad", "before-40-1.39.db"));
        // the job and when a copy is due
        BackgroundJob.Spec job = Backups.jobSpec("app.openring/.sync.BackupJob");
        check("backups: one daily job, flex 6 hours, persisted, battery not low, while charging", job.periodic && job.interval == 24 * 3600 * 1000L
                && job.flex == 6 * 3600 * 1000L && job.persisted && job.batteryNotLow && job.charging);
        eq("backups: the same job pending is kept", BackgroundJob.decide(true, true, Backups.jobSpec("app.openring/.sync.BackupJob"), job), BackgroundJob.Action.KEEP);
        eq("backups: the sync's job is not the backup job (charging differs)", BackgroundJob.decide(true, true,
            new BackgroundJob.Spec("app.openring/.sync.BackupJob", true, 24 * 3600 * 1000L, 6 * 3600 * 1000L, true, true), job), BackgroundJob.Action.SCHEDULE);
        check("backups: a spec says it charges", job.toString().contains("charging") && !BackgroundJob.wanted("s").toString().contains("charging"));
        check("backups: specs equal by charging too", !job.equals(new BackgroundJob.Spec(job.service, true, job.interval, job.flex, true, true, false))
                && job.hashCode() == Backups.jobSpec(job.service).hashCode());
        long now = 1793000000L, h = 3600, d = 86400;
        check("backups: no copy yet, due", Backups.due(now, 0, 7));
        for (int days : Backups.DAYS) {
            check("backups: every " + days + " days, 6 hours before the full interval: due", Backups.due(now, now - days * d + 6 * h, days));
            check("backups: every " + days + " days, a minute earlier: not due", !Backups.due(now, now - days * d + 6 * h + 60, days));
        }
        check("backups: the last copy lies in the future (clock set back), due", Backups.due(now, now + d, 30));
        check("backups: choices", Backups.validDays(1) && Backups.validDays(7) && Backups.validDays(30) && !Backups.validDays(2) && !Backups.validDays(0)
                && Backups.validKeep(3) && Backups.validKeep(5) && Backups.validKeep(10) && !Backups.validKeep(4) && Backups.validDays(Backups.DEFAULT_DAYS)
                && Backups.validKeep(Backups.DEFAULT_KEEP));
        // folder names and which files go
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 10, 29, 3, 12);
        eq("backups: folder name", Backups.folderName(t, null), "openring-backup-2026-10-29-0312.zip");
        eq("backups: folder name of an update copy", Backups.folderName(t, "1.42"), "openring-backup-2026-10-29-0312-before-1.42.zip");
        check("backups: our names", Backups.folderFile("openring-backup-2026-10-29-0312.zip") && Backups.folderFile("openring-backup-2026-10-29-0312-before-1.42.zip")
                && !Backups.folderFile("openring-backup-2026-10-29-0312.zip.partial") && !Backups.folderFile("openring-backup-2026-10-29.zip")
                && !Backups.folderFile("open-ring-full-backup.zip") && !Backups.folderFile(null));
        String a = "openring-backup-2026-10-01-0300.zip", b = "openring-backup-2026-10-08-0300.zip", c2 = "openring-backup-2026-10-15-0300-before-1.42.zip",
            e = "openring-backup-2026-10-22-0300.zip", nw = "openring-backup-2026-10-29-0300.zip", foreign = "openring-backup-2020-01-01-0000.zip";
        List<String> present = Arrays.asList(a, b, c2, e, nw, foreign, "notes.txt", a + ".partial", foreign + ".partial", "openring-backup-2026-10-02-0300.zip.partial");
        List<String> listed = Arrays.asList(a, b, c2, e, nw, "openring-backup-2026-10-02-0300.zip");
        eq("backups: keep 3, the oldest listed ones go, our partials too, foreign files stay", new java.util.TreeSet<String>(Backups.folderToDelete(present, listed, nw, 3)),
            new java.util.TreeSet<String>(Arrays.asList(a, b, a + ".partial", "openring-backup-2026-10-02-0300.zip.partial")));
        eq("backups: keep 10, only our partials go", Backups.folderToDelete(present, listed, nw, 10), Arrays.asList(a + ".partial", "openring-backup-2026-10-02-0300.zip.partial"));
        eq("backups: the new file is not there, nothing complete goes", Backups.folderToDelete(Arrays.asList(a, b, c2, e), listed, nw, 3), Arrays.asList());
        eq("backups: the new file is not the newest by name (clock set back), it is kept with the 2 newest others", new java.util.TreeSet<String>(
            Backups.folderToDelete(Arrays.asList(a, b, c2, e, nw), listed, a, 3)), new java.util.TreeSet<String>(Arrays.asList(b, c2)));
        // review of #340: copies without the key (issue #288) are named so, and never push out the last copy with the key
        eq("backups: a copy without the key", Backups.withoutKey(Backups.folderName(t, null)), "openring-backup-2026-10-29-0312-nokey.zip");
        eq("backups: an update copy without the key", Backups.withoutKey(Backups.folderName(t, "1.42")), "openring-backup-2026-10-29-0312-before-1.42-nokey.zip");
        check("backups: copies without the key are ours and known as such", Backups.folderFile("openring-backup-2026-10-29-0312-nokey.zip")
                && Backups.folderFile("openring-backup-2026-10-29-0312-before-1.42-nokey.zip") && Backups.keyless("openring-backup-2026-10-29-0312-nokey.zip")
                && !Backups.keyless("openring-backup-2026-10-29-0312.zip") && Backups.withoutKey("openring-backup-2026-10-29-0312-nokey.zip").equals("openring-backup-2026-10-29-0312-nokey.zip"));
        {
            List<String> keyed = Arrays.asList("openring-backup-2026-10-01-0300.zip", "openring-backup-2026-10-02-0300.zip", "openring-backup-2026-10-03-0300-before-1.40.zip");
            List<String> all = new ArrayList<String>(keyed);
            for (int k = 4; k <= 9; k++) all.add("openring-backup-2026-10-0" + k + "-0300-nokey.zip");
            String newest = "openring-backup-2026-10-09-0300-nokey.zip";
            eq("backups: without the key the newest copy with it stays beside the 3 newest, older ones go", new java.util.TreeSet<String>(Backups.folderToDelete(all, all, newest, 3)),
                    new java.util.TreeSet<String>(Arrays.asList("openring-backup-2026-10-01-0300.zip", "openring-backup-2026-10-02-0300.zip",
                            "openring-backup-2026-10-04-0300-nokey.zip", "openring-backup-2026-10-05-0300-nokey.zip", "openring-backup-2026-10-06-0300-nokey.zip")));
            List<String> back = new ArrayList<String>(all);
            back.add("openring-backup-2026-10-10-0300.zip");
            check("backups: a copy with the key again: the rotation is as before", Backups.folderToDelete(back, back, "openring-backup-2026-10-10-0300.zip", 3)
                    .contains("openring-backup-2026-10-03-0300-before-1.40.zip"));
            check("backups: a copy with the key among the newest: nothing extra kept", Backups.folderToDelete(all.subList(0, 4), all, "openring-backup-2026-10-04-0300-nokey.zip", 3)
                    .equals(Arrays.asList("openring-backup-2026-10-01-0300.zip")));
        }
        eq("backups: the partial of the new file itself is not ours to delete", Backups.folderToDelete(Arrays.asList(nw + ".partial", nw), listed, nw, 3), Arrays.asList());
        // the folder's permission, from the picker's result (review of #259)
        eq("backups: read and write given: both kept", Backups.keepFlags(Backups.GRANT_READ | Backups.GRANT_WRITE | 64 | 128), 3);
        eq("backups: the Android values", Backups.GRANT_READ + 10 * Backups.GRANT_WRITE, 21);
        eq("backups: read only: not kept", Backups.keepFlags(Backups.GRANT_READ | 64), -1);
        eq("backups: no flags: not kept", Backups.keepFlags(0), -1);
        // why a copy failed
        // review of #266: a kept damaged file fails every read; the screens say so in one line instead of crashing
        check("backups: a damaged history file is recognised", Backups.damaged("android.database.sqlite.SQLiteDatabaseCorruptException: database disk image is malformed (code 11)")
                && Backups.damaged("file is not a database (code 26 SQLITE_NOTADB)") && !Backups.damaged("database is locked") && !Backups.damaged(null));
        check("backups: the damaged line points to the copies, other failures to Data check",
                Backups.unreadable("SQLiteDatabaseCorruptException").equals(Backups.DAMAGED) && Backups.DAMAGED.contains("Automatic backups")
                        && Backups.unreadable("disk I/O error").equals(Backups.UNREADABLE) && Backups.unreadable(null).equals(Backups.UNREADABLE)
                        && Backups.DAMAGED.contains(Backups.TITLE));
        check("backups: a full disk is recognised", Backups.noSpace("write failed: ENOSPC (No space left on device)") && Backups.noSpace("SQLITE_FULL")
                && Backups.noSpace("database or disk is full (code 13)") && !Backups.noSpace("EACCES") && !Backups.noSpace(null));
        // texts, word for word
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        long when = java.time.LocalDateTime.of(2026, 10, 29, 3, 12).atZone(z).toEpochSecond();
        eq("backups: copy line", Backups.copyLine("1.42", when, 210 * mb, z), "Copy from before the update to 1.42 (29 Oct, 210 MB)");
        eq("backups: copy before going back", Backups.backLine(when, 212 * mb, z), "Copy from before going back (29 Oct, 212 MB)");
        eq("backups: a small copy is at least 1 MB", Backups.mb(1000), "1 MB");
        eq("backups: an empty file", Backups.mb(0), "0 MB");
        eq("backups: going back asks, and says the whole history is kept and can be put back, and the phone's period log stays (#252)", Backups.goBackText(when, z),
            "Your history is replaced by this copy from 29 Oct. First your whole history as it is now is kept as \"Copy from before going back\", "
            + "so you can put it back from this card. Period days you logged or removed on this phone stay as they are.");
        check("backups: the going back question names the copy's line", Backups.goBackText(when, z).contains(Backups.backLine(when, 1, z).substring(0, 26)));
        eq("backups: status after a copy", Backups.status(when, 210 * mb, 5, null, z), "Last copy: 29 Oct 03:12, 210 MB, 5 kept");
        eq("backups: status before the first copy", Backups.status(0, 0, 0, null, z), "No copy yet. The first one is made the next time the phone charges.");
        eq("backups: status after a failure", Backups.status(when, 210 * mb, 5, Backups.GONE, z),
            "The last copy failed: the folder is gone or Open Ring may no longer use it.");
        eq("backups: the numbers explained", Backups.statusExplain(210 * mb, 5),
            "210 MB is the size of the last copy, 5 kept is how many copies are in the folder now. Copies are made while the phone charges.");
        eq("backups: data check, copies", Backups.checkCopies(Collections.singletonList(new String[] {"1.42", "29 Oct, 210 MB, checked"}), null),
            "Copies before updates: 1.42 (29 Oct, 210 MB, checked)");
        eq("backups: data check, no copies and why", Backups.checkCopies(new ArrayList<String[]>(), "no copy, not enough free space"),
            "Copies before updates: none. Last update: no copy, not enough free space");
        eq("backups: data check, folder copies", Backups.checkAuto(true, 7, 5, when, 210 * mb, when, null, z),
            "Automatic backups: every week, keep 5, last copy 29 Oct 03:12 (210 MB), last try ok");
        eq("backups: data check, a failed try", Backups.checkAuto(true, 30, 10, 0, 0, when, Backups.FULL, z),
            "Automatic backups: every month, keep 10, no copy yet, last try failed: the folder is full");
        eq("backups: data check, never tried", Backups.checkAuto(true, 1, 3, 0, 0, 0, null, z), "Automatic backups: every day, keep 3, no copy yet");
        eq("backups: data check, off", Backups.checkAuto(false, 7, 5, 0, 0, 0, null, z), "Automatic backups: off");
        eq("backups: another interval", Backups.every(14), "every 14 days");
        List<String> texts = new ArrayList<String>(Arrays.asList(Backups.TITLE, Backups.OFF_TEXT, Backups.CHOOSE, Backups.CHOOSE_OTHER, Backups.CHOOSE_AGAIN,
            Backups.HOW_OFTEN, Backups.HOW_MANY, Backups.ALSO_UPDATE, Backups.STOP, Backups.STOPPED, Backups.PRIVATE, Backups.NONE_YET, Backups.GO_BACK,
            Backups.GO_BACK_TITLE, Backups.GOING_BACK, Backups.goBackText(when, z), Backups.WENT_BACK, Backups.DEMO, Backups.NOT_IN_DEMO, Backups.NOTICE_SPACE, Backups.NOTICE_OTHER, Backups.COPY_EXPLAIN,
            Backups.FOLDER_PICK_FAILED, Backups.badLine("1.42", when, z)));
        texts.addAll(Arrays.asList(Backups.DAY_NAMES));
        for (String r : new String[] {Backups.GONE, Backups.FULL, Backups.CHECK, Backups.SPACE, Backups.WRITE}) texts.add(Backups.status(when, 1, 1, r, z));
        for (String x : texts) check("backups: plain, no dash as punctuation: " + x, !x.contains(" - ") && !x.contains("\u2013") && !x.contains("\u2014") && !x.isEmpty());
        eq("backups: the five reasons", Arrays.asList(Backups.reason(Backups.GONE), Backups.reason(Backups.FULL), Backups.reason(Backups.CHECK), Backups.reason(Backups.SPACE),
            Backups.reason(Backups.WRITE)), Arrays.asList("the folder is gone or Open Ring may no longer use it", "the folder is full", "the copy did not pass its check",
            "not enough free space on this phone for the copy", "the file could not be written"));
        check("backups: short notes fit a toast", Texts.fitsToast(Backups.STOPPED) && Texts.fitsToast(Backups.NOT_IN_DEMO) && Texts.fitsToast(Backups.WENT_BACK));
    }

    /** A picked file that fails on the way is removed, or called incomplete when it cannot be (issue #222). */
    static void fileSave222() {
        final java.io.ByteArrayOutputStream[] file = {null};
        final boolean[] deleted = {false};
        final int[] failAfter = {-1};
        final boolean[] deleteWorks = {true};
        FileSave.Target target = new FileSave.Target() {
            @Override
            public java.io.OutputStream open() {
                file[0] = new java.io.ByteArrayOutputStream() {
                    @Override
                    public synchronized void write(byte[] b, int off, int len) {
                        if (failAfter[0] >= 0 && size() + len > failAfter[0]) {
                            int room = Math.max(0, failAfter[0] - size());
                            super.write(b, off, room);
                            throw new RuntimeException(new IOException("No space left on device"));
                        }
                        super.write(b, off, len);
                    }
                };
                deleted[0] = false;
                return file[0];
            }

            @Override
            public boolean delete() {
                if (!deleteWorks[0]) return false;
                deleted[0] = true;
                return true;
            }
        };
        final byte[] content = new byte[100000];
        new Random(222).nextBytes(content);
        FileSave.Body body = new FileSave.Body() {
            @Override
            public void write(java.io.OutputStream os) throws IOException {
                for (int i = 0; i < content.length; i += 8192) os.write(content, i, Math.min(8192, content.length - i));
            }
        };
        FileSave.Outcome ok = FileSave.save(target, body);
        check("#222 a save that works is complete and kept", ok.ok() && ok.bytes == content.length && !deleted[0] && Arrays.equals(file[0].toByteArray(), content));
        failAfter[0] = 30000;
        FileSave.Outcome cut = FileSave.save(target, body);
        check("#222 a save that fails after 30000 bytes removes the started file", !cut.ok() && cut.bytes == 24576 && cut.removed && deleted[0] && !cut.leftOver);
        failAfter[0] = 0;
        FileSave.Outcome none = FileSave.save(target, body);
        check("#222 a save that fails before the first byte removes the empty file, as before", !none.ok() && none.bytes == 0 && none.removed);
        deleteWorks[0] = false;
        failAfter[0] = 30000;
        FileSave.Outcome left = FileSave.save(target, body);
        check("#222 a cut file that cannot be removed is called incomplete", !left.ok() && left.leftOver && !left.removed && left.removeError != null);
        failAfter[0] = 0;
        FileSave.Outcome empty = FileSave.save(target, body);
        check("#222 an empty file that cannot be removed is not called incomplete", !empty.ok() && !empty.leftOver);
        deleteWorks[0] = true;
        failAfter[0] = -1;
        FileSave.Outcome refused = FileSave.save(target, new FileSave.Body() {
            @Override
            public void write(java.io.OutputStream os) throws IOException {
                throw new IOException("the copy did not pass its check");
            }
        });
        check("#222 a body that refuses before writing removes the file and keeps its reason", !refused.ok() && refused.removed
                && "the copy did not pass its check".equals(refused.error.getMessage()));
        // the last bytes fail at close (a file app that writes on close): not complete either
        FileSave.Outcome atClose = FileSave.save(new FileSave.Target() {
            @Override
            public java.io.OutputStream open() {
                return new java.io.ByteArrayOutputStream() {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("close failed");
                    }
                };
            }

            @Override
            public boolean delete() {
                deleted[0] = true;
                return true;
            }
        }, body);
        check("#222 a file that fails when it is closed is removed", !atClose.ok() && atClose.removed && "close failed".equals(atClose.error.getMessage()));
        FileSave.Outcome noFile = FileSave.save(new FileSave.Target() {
            @Override
            public java.io.OutputStream open() {
                return null;
            }

            @Override
            public boolean delete() {
                throw new IllegalStateException("nothing to delete");
            }
        }, body);
        check("#222 no file from Android: failed, nothing to remove", !noFile.ok() && !noFile.removed && !noFile.leftOver && noFile.bytes == 0);
        // an Error (out of memory) on the way: the started file is removed first, then the Error goes on (review of #340)
        deleted[0] = false;
        Error thrown = null;
        try {
            FileSave.save(target, new FileSave.Body() {
                @Override
                public void write(java.io.OutputStream os) throws IOException {
                    os.write(content, 0, 1000);
                    throw new OutOfMemoryError("test");
                }
            });
        } catch (OutOfMemoryError e) {
            thrown = e;
        }
        check("#222 an Error removes the started file and is thrown on", thrown != null && deleted[0]);
    }

    static void oneRun() {
        OneRun<String> r = new OneRun<String>();
        check("one run: idle at first", !r.running());
        r.attach("first screen");
        check("one run: first start", r.start());
        check("one run: running", r.running());
        check("one run: second start while running is refused", !r.start());
        r.attach("turned screen");
        r.detach("first screen"); // Back from the screen that started it: the one below attaches before this one goes
        check("one run: still running after turning", r.running() && !r.start());
        eq("one run: tells the screen shown now", r.finish(), "turned screen");
        check("one run: ended", !r.running());
        check("one run: a new start after the end", r.start());
        r.detach("turned screen");
        check("one run: no screen left", r.finish() == null);
        r.detach("never attached");
        check("one run: detaching a stranger changes nothing", !r.running() && r.finish() == null);
        // a turned phone: Android destroys the old screen before it builds the new one
        r.attach("old screen");
        check("one run: start before turning", r.start());
        r.detach("old screen");
        r.attach("new screen");
        eq("one run: the rebuilt screen is told", r.finish(), "new screen");
        // Settings ring actions (issue #59): only the screen shown now, no running state of their own
        OneRun<String> shown = new OneRun<String>();
        check("shown screen: none at first", shown.attached() == null);
        shown.attach("settings");
        eq("shown screen: the one resumed", shown.attached(), "settings");
        shown.attach("turned settings");
        shown.detach("settings"); // the old screen closing late changes nothing
        eq("shown screen: the turned one after a turn", shown.attached(), "turned settings");
        check("shown screen: asking for a job starts it", !shown.running() && shown.start());
        eq("shown screen: asking ends nothing", shown.attached(), "turned settings");
        check("shown screen: the job still runs", shown.running());
        shown.detach("turned settings");
        check("shown screen: none after Back", shown.attached() == null);
        // issue #222: Re analyse all and Repair are one job; a second tap is refused, a saved setting runs it once more
        OneRun<String> all = new OneRun<String>();
        check("#222 a first start runs", all.startOrAgain() && all.running() && !all.againAsked());
        check("#222 a second tap on Re analyse all is refused", !all.start() && !all.againAsked());
        check("#222 Repair while it runs is refused too", !all.start());
        check("#222 a saved setting while it runs does not start a second run", !all.startOrAgain() && all.running());
        check("#222 ... but asks for one more pass", all.againAsked());
        check("#222 two settings saved ask for one pass, not two", !all.startOrAgain() && all.againAsked());
        check("#222 the pass that ends takes the request and keeps running", !all.finishUnlessAgain() && all.running() && !all.againAsked());
        check("#222 the next pass ends the job", all.finishUnlessAgain() && !all.running());
        check("#222 after the end a new start runs", all.start());
        check("#222 a plain finish drops a request", !all.startOrAgain() && all.finish() == null && !all.running() && !all.againAsked());
        check("#222 and a new start does not inherit it", all.start() && all.finishUnlessAgain() && !all.running());
        check("#222 ending twice is harmless", all.finishUnlessAgain() && !all.running());
    }

    /** Issue #127: the space kept for Further analysis belongs to one screen layout. */
    static void keptHeight() {
        String portrait = KeptHeight.layout(411, 1.0f, 420);
        String landscape = KeptHeight.layout(914, 1.0f, 420);
        String bigFont = KeptHeight.layout(411, 1.3f, 420);
        String otherDensity = KeptHeight.layout(411, 1.0f, 480);
        KeptHeight k = new KeptHeight();
        eq("kept height: nothing known keeps no space", k.get(portrait), 0);
        k.remember(portrait, 1800);
        eq("kept height: same layout", k.get(portrait), 1800);
        eq("kept height: same values make the same layout", k.get(KeptHeight.layout(411, 1.0f, 420)), 1800);
        eq("kept height: turned phone keeps no space", k.get(landscape), 0);
        eq("kept height: larger font keeps no space", k.get(bigFont), 0);
        eq("kept height: other density keeps no space", k.get(otherDensity), 0);
        k.remember(landscape, 1100);
        eq("kept height: landscape has its own", k.get(landscape), 1100);
        eq("kept height: portrait kept after turning back", k.get(portrait), 1800);
        k.remember(portrait, 1500);
        eq("kept height: newer result replaces older", k.get(portrait), 1500);
        eq("kept height: other layout untouched", k.get(landscape), 1100);
    }

    /** Owner's wish of 3 November: the ring and the watch each in one block of Settings, closed until tapped. */
    static void settingsBlocks() {
        eq("settings blocks: stages is in the Ring block", SettingsBlocks.of("stages"), SettingsBlocks.RING);
        eq("settings blocks: watch is in the Watch block", SettingsBlocks.of("watch"), SettingsBlocks.WATCH);
        for (String s : new String[] {"top", "blocks", "period", "screen", "games", "backups", "advanced", "nonsense", ""})
            eq("settings blocks: " + s + " is outside the blocks", SettingsBlocks.of(s), SettingsBlocks.NONE);
        eq("settings blocks: no extra is outside the blocks", SettingsBlocks.of(null), SettingsBlocks.NONE);
        check("settings blocks: both closed without an extra", !SettingsBlocks.startsOpen(SettingsBlocks.RING, null) && !SettingsBlocks.startsOpen(SettingsBlocks.WATCH, null));
        check("settings blocks: both closed at the top", !SettingsBlocks.startsOpen(SettingsBlocks.RING, "top") && !SettingsBlocks.startsOpen(SettingsBlocks.WATCH, "top"));
        check("settings blocks: both closed for the blocks shot", !SettingsBlocks.startsOpen(SettingsBlocks.RING, "blocks") && !SettingsBlocks.startsOpen(SettingsBlocks.WATCH, "blocks"));
        check("settings blocks: stages opens the Ring block only", SettingsBlocks.startsOpen(SettingsBlocks.RING, "stages") && !SettingsBlocks.startsOpen(SettingsBlocks.WATCH, "stages"));
        check("settings blocks: watch opens the Watch block only", SettingsBlocks.startsOpen(SettingsBlocks.WATCH, "watch") && !SettingsBlocks.startsOpen(SettingsBlocks.RING, "watch"));
        check("settings blocks: NONE never starts open", !SettingsBlocks.startsOpen(SettingsBlocks.NONE, "period") && !SettingsBlocks.startsOpen(SettingsBlocks.NONE, null));
        // the Model line is as before the move from SettingsActivity
        eq("settings blocks: model Ring 4", SettingsBlocks.model("ORE_07"), "Ring 4 (ORE_07)");
        eq("settings blocks: model Ring 4 by suffix", SettingsBlocks.model("XYZ_06"), "Ring 4 (XYZ_06)");
        eq("settings blocks: model Ring 5", SettingsBlocks.model("XYZ_05"), "Ring 5 (XYZ_05)");
        eq("settings blocks: model Gen 3", SettingsBlocks.model("BLB_02"), "Gen 3 Horizon (BLB_02)");
        eq("settings blocks: model unknown code kept", SettingsBlocks.model("ABC"), "ABC");
        eq("settings blocks: model not known", SettingsBlocks.model(null), "--");
        eq("settings blocks: short model", SettingsBlocks.shortModel("ORE_07"), "Ring 4");
        eq("settings blocks: short model unknown code kept", SettingsBlocks.shortModel("ABC"), "ABC");
        eq("settings blocks: short model not known", SettingsBlocks.shortModel(null), "Oura ring");
        eq("settings blocks: ring synced", SettingsBlocks.ringStatus(false, "ORE_07", 1700000000L, "2 hours ago"), "Ring 4, last sync 2 hours ago");
        eq("settings blocks: ring never synced", SettingsBlocks.ringStatus(false, "ORE_07", 0, "--"), "Ring 4, not synced yet");
        eq("settings blocks: ring synced but its time not worded yet", SettingsBlocks.ringStatus(false, "ORE_07", 1700000000L, null), "Ring 4, not synced yet");
        check("settings blocks: first build jumps to its card", SettingsBlocks.jumpOnBuild("period", false, false));
        check("settings blocks: a rebuild keeps the scroll (review of #330)", !SettingsBlocks.jumpOnBuild("period", false, true));
        check("settings blocks: a turned phone keeps the scroll", !SettingsBlocks.jumpOnBuild("period", true, false));
        check("settings blocks: no extra, no jump", !SettingsBlocks.jumpOnBuild(null, false, false));
        eq("settings blocks: ring demo", SettingsBlocks.ringStatus(true, "ORE_07", 1700000000L, "2 hours ago"), "Made up Ring 4, demo data");
        eq("settings blocks: no watch", SettingsBlocks.watchStatus(false, false, null, 0, null, false, false), "No watch added yet");
        eq("settings blocks: watch synced", SettingsBlocks.watchStatus(false, true, "Steel HR", 1700000000L, "5 minutes ago", false, false), "Steel HR, last sync 5 minutes ago");
        eq("settings blocks: watch never synced", SettingsBlocks.watchStatus(false, true, "Steel HR", 0, "--", false, false), "Steel HR, not synced yet");
        eq("settings blocks: watch syncing", SettingsBlocks.watchStatus(false, true, "Steel HR", 1700000000L, "5 minutes ago", true, true), "Steel HR, syncing now");
        eq("settings blocks: watch failed", SettingsBlocks.watchStatus(false, true, "Steel HR", 1700000000L, "5 minutes ago", false, true), "Steel HR, the last sync did not work");
        eq("settings blocks: watch without name", SettingsBlocks.watchStatus(false, true, null, 0, null, false, false), "Withings watch, not synced yet");
        eq("settings blocks: watch demo", SettingsBlocks.watchStatus(true, false, null, 0, null, false, false), "Made up Steel HR, demo data");
        for (String t : new String[] {SettingsBlocks.ringStatus(false, "ORE_07", 1, "1 minute ago"), SettingsBlocks.watchStatus(false, true, "Steel HR", 0, null, false, true),
                SettingsBlocks.ringStatus(true, null, 0, null), SettingsBlocks.watchStatus(true, true, null, 0, null, false, false)})
            check("settings blocks: no dash as punctuation in " + t, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
    }

    /** Issue #204: the page stays still when the stage detail card above the switch is redrawn. */
    static void scrollKeep() {
        // card from 100 to 600 (500 px), the phone line adds 40 px
        eq("scroll keep: card fully above, it grows, the page moves with it", ScrollKeep.after(900, 100, 500, 540), 940);
        eq("scroll keep: card fully above, it shrinks, the page moves with it", ScrollKeep.after(900, 100, 540, 500), 860);
        eq("scroll keep: card ends exactly at the top edge counts as above", ScrollKeep.after(600, 100, 500, 540), 640);
        eq("scroll keep: card partly on screen stays", ScrollKeep.after(599, 100, 500, 540), 599);
        eq("scroll keep: card top on screen stays", ScrollKeep.after(50, 100, 500, 540), 50);
        eq("scroll keep: top of the page stays", ScrollKeep.after(0, 100, 500, 460), 0);
        eq("scroll keep: same height stays", ScrollKeep.after(900, 100, 500, 500), 900);
        eq("scroll keep: card above shrinks to nothing", ScrollKeep.after(20, 0, 20, 0), 0);
        eq("scroll keep: a negative start is not kept", ScrollKeep.after(-5, 100, 500, 540), 0);
    }

    /** One score ladder: the Today sentence agrees with the label and colour of every score (issue #200). */
    static void scoreLadder() {
        eq("ladder: 85, 70, 60", Scores.OPTIMAL + "," + Scores.GOOD + "," + Scores.FAIR, "85,70,60");
        boolean allAgree = true, verdictAgrees = true;
        for (int s = 0; s <= 100; s++) {
            String label = Scores.rating(s);
            boolean low = label.equals("Fair") || label.equals("Pay attention");
            String line = Texts.contributorLine("HRV balance", "Resting heart rate", s, Locale.US);
            if (Scores.needsAttention(s) != low || line.contains("needs attention") != low) allAgree = false;
            String v = Scores.verdict(s);
            if ((label.equals("Optimal") || label.equals("Good")) ? !v.equals(label) : !v.equals("Pay attention")) verdictAgrees = false;
        }
        check("ladder: needs attention exactly for Fair and Pay attention, 0 to 100", allAgree);
        check("ladder: verdict agrees with the label", verdictAgrees);
        for (int s = 70; s <= 74; s++) check("ladder: " + s + " is Good and needs no attention", Scores.rating(s).equals("Good") && !Scores.needsAttention(s));
        check("ladder: 69 needs attention", Scores.needsAttention(69) && Scores.rating(69).equals("Fair"));
        check("ladder: no data never needs attention", !Scores.needsAttention(Double.NaN));
        eq("today line: with the weakest", Texts.contributorLine("HRV balance", "Resting heart rate", 69, Locale.US),
                "HRV balance is your strongest contributor, while resting heart rate needs attention.");
        eq("today line: 72 is Good, no attention (issue #200)", Texts.contributorLine("HRV balance", "Resting heart rate", 72, Locale.US),
                "HRV balance is your strongest contributor.");
        eq("today line: an abbreviation keeps its capitals", Texts.contributorLine("Sleep", "HRV balance", 60, Locale.US),
                "Sleep is your strongest contributor, while HRV balance needs attention.");
        eq("today line: only the first letter is lowered", Texts.contributorLine("HRV balance", "Previous day activity", 50, Locale.US),
                "HRV balance is your strongest contributor, while previous day activity needs attention.");
        eq("today line: a one letter label", Texts.contributorLine("Sleep", "X", 50, Locale.US), "Sleep is your strongest contributor, while x needs attention.");
        eq("today line: no contributor", Texts.contributorLine(null, null, Double.NaN, Locale.US), "");
    }

    /** The line under Daily movement says what its percentages are shares of (issue #212). */
    static void movementShares() {
        eq("movement: ring on all day", Texts.movementShares(1440, 0, null, false), "The percentages are of the time the ring was on (24h 0m).");
        eq("movement: not worn not stored", Texts.movementShares(1440, Double.NaN, null, false), "The percentages are of the time the ring was on (24h 0m).");
        eq("movement: under a minute off", Texts.movementShares(1440, 0.4, null, false), "The percentages are of the time the ring was on (24h 0m).");
        eq("movement: ring off for a while", Texts.movementShares(1270, 170, null, false),
                "The percentages are of the time the ring was on (21h 10m). Not worn: 2h 50m.");
        eq("movement: short times", Texts.movementShares(45, 15, null, false), "The percentages are of the time the ring was on (45 min). Not worn: 15 min.");
        eq("movement: a day still running", Texts.movementShares(370, 60, "14:05", false),
                "The percentages are of the time the ring was on up to 14:05 (6h 10m). Not worn: 1h 0m.");
        eq("movement: with a left out time", Texts.movementShares(1270, 170, null, true),
                "The percentages are of the time the ring was on (21h 10m). Not worn or left out: 2h 50m.");
        // a day analysed at 14:05: the 595 minutes after it were stored as without values, they are not "not worn"
        double end = 86400, until = 86400 - 595 * 60;
        eq("not worn so far: future minutes dropped", Texts.notWornSoFar(655, until, 0, end), 60.0);
        eq("not worn so far: an ended day keeps its minutes", Texts.notWornSoFar(170, Double.NaN, 0, end), 170.0);
        eq("not worn so far: analysed after the end", Texts.notWornSoFar(170, end + 5, 0, end), 170.0);
        eq("not worn so far: never below 0", Texts.notWornSoFar(100, until, 0, end), 0.0);
        check("not worn so far: not stored stays unknown", Double.isNaN(Texts.notWornSoFar(Double.NaN, until, 0, end)));
        // analysed at 14:05:20 with a value at 30 s into every minute before: the minute it was in has no value yet,
        // still no Not worn (review of #282)
        double start = 1_793_570_400, at = start + 845 * 60 + 20;
        TS full = new TS();
        for (double t = start + 30; t < at; t += 60) full.add(t, 1.2);
        Wellness.Day run = Wellness.day("2026-11-01", start, start + 86400, java.time.ZoneOffset.UTC, full, new ArrayList<double[]>(), 70, 1600, at);
        eq("not worn so far: the minute of the analysis is stored without a value", (int) Math.round(run.nonWearMin), 595);
        eq("not worn so far: unaligned analysis moment, ring on all the time", Texts.notWornSoFar(run.nonWearMin, run.totalUntil, start, start + 86400), 0.0);
        eq("not worn so far: unaligned, the line has no Not worn",
                Texts.movementShares(run.inactiveMin, Texts.notWornSoFar(run.nonWearMin, run.totalUntil, start, start + 86400), "14:05", false),
                "The percentages are of the time the ring was on up to 14:05 (14h 5m).");
        // the rows plus not worn are the day, minute by minute (Wellness, issues #112, #212)
        TS met = new TS();
        for (int m = 0; m < 600; m++) met.add(m * 60 + 10, m < 300 ? 1.2 : 2.0);
        Wellness.Day w = Wellness.day("2026-11-01", 0, 86400, java.time.ZoneOffset.UTC, met, new ArrayList<double[]>(), 70, 1600, 1e12);
        eq("movement: rows plus not worn are the whole day", (int) Math.round(w.highMin + w.mediumMin + w.lowMin + w.inactiveMin + w.restMin + w.nonWearMin), 1440);
        eq("movement: not worn from the minutes without values", (int) Math.round(w.nonWearMin), 840);
    }

    /** One way to write the same quantity everywhere (issues #99, #163, #165). */
    static void unitTexts() {
        Locale de = Locale.GERMANY;
        // temperature change: near zero is "0.0", never "-0.0" or "+0.0"; the stored value rounded first (issue #163)
        eq("signed: -0.04 is 0.0", Texts.signed(-0.04, Locale.US), "0.0");
        eq("signed: +0.04 is 0.0", Texts.signed(0.04, Locale.US), "0.0");
        eq("signed: -0.03 is 0.0", Texts.signed(-0.03, Locale.US), "0.0");
        eq("signed: 0.05 is +0.1", Texts.signed(0.05, Locale.US), "+0.1");
        eq("signed: -0.05 is -0.1", Texts.signed(-0.05, Locale.US), "-0.1");
        eq("signed: 0.0496 is stored as 0.05, so +0.1 as the card shows", Texts.signed(0.0496, Locale.US), "+0.1");
        eq("signed: 0.0494 is 0.0", Texts.signed(0.0494, Locale.US), "0.0");
        eq("signed: -0.31", Texts.signed(-0.31, Locale.US), "-0.3");
        eq("signed: phone's decimal sign", Texts.signed(0.26, de), "+0,3");
        eq("signed: near zero with the phone's decimal sign", Texts.signed(-0.04, de), "0,0");
        eq("signed: unknown", Texts.signed(Double.NaN, Locale.US), "--");
        // line chart numbers: today's plain format for every chart, the signed one for the temperature charts (issue #226)
        eq("chart value: whole number", Texts.chartValue(12, Locale.US), "12");
        eq("chart value: one decimal under 10", Texts.chartValue(0.3, Locale.US), "0.3");
        eq("chart value: no sign rule, -0.04 is -0.0", Texts.chartValue(-0.04, Locale.US), "-0.0");
        eq("chart value: rounded from 10 on", Texts.chartValue(25.6, Locale.US), "26");
        eq("chart value: whole number under 10", Texts.chartValue(-1.5 * 2, Locale.US), "-3");
        eq("chart value: phone's decimal sign", Texts.chartValue(0.3, de), "0,3");
        eq("chart value: unknown reads 0 as before", Texts.chartValue(Double.NaN, Locale.US), "0");
        eq("chart value: plain when not signed", Texts.chartValue(-0.04, false, Locale.US), "-0.0");
        eq("chart value signed: -0.04 is 0.0", Texts.chartValue(-0.04, true, Locale.US), "0.0");
        eq("chart value signed: 0.0 is 0.0", Texts.chartValue(0.0, true, Locale.US), "0.0");
        eq("chart value signed: 0.25 is +0.3", Texts.chartValue(0.25, true, Locale.US), "+0.3");
        eq("chart value signed: guide labels", Texts.chartValue(1.5, true, Locale.US) + " " + Texts.chartValue(-1.5, true, Locale.US), "+1.5 -1.5");
        eq("chart value signed: as the card above", Texts.chartValue(0.31, true, Locale.US) + " \u00b0C", Texts.radar("Skin temperature", 0.31, Locale.US));
        // the efficiency explanation says it plainly, as the Sleep page note (issue #227)
        String effText = app.openring.ui.Explain.of("efficiency");
        check("efficiency explanation: time without a stage", effText.endsWith("Time without a sleep stage counts as time in bed, not as sleep."));
        check("efficiency explanation: no longer says the ring gave the stages", !effText.contains("the ring gave"));
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        for (double v : new double[] {-0.04, 0.04, 0.05, -0.03, 0.0496, 0.6}) {
            ri.tempDev = v;
            eq("readiness: temperature detail " + v + " as the card", part(Scores.readiness(ri), "temperature").detail, Texts.signed(v, Locale.US) + " \u00b0C");
        }
        ri.tempDev = -0.04;

        // minutes on the Screen tab: one way under an hour (issue #99)
        eq("minutes: 30", Texts.minutes(30), "30 min");
        eq("minutes: rounded", Texts.minutes(14.6), "15 min");
        eq("minutes: zero", Texts.minutes(0), "0 min");
        eq("minutes: just under an hour", Texts.minutes(59.4), "59 min");
        eq("minutes: rounds up to an hour", Texts.minutes(59.5), "1h 0m");
        eq("minutes: above an hour", Texts.minutes(65), "1h 5m");
        eq("minutes: unknown", Texts.minutes(Double.NaN), "--");

        // symptom radar values with a unit and the precision of Today (issue #99)
        eq("radar: HRV", Texts.radar("HRV", 59.3, Locale.US), "59 ms");
        eq("radar: resting heart rate", Texts.radar("Resting heart rate", 52.6, Locale.US), "53 bpm");
        eq("radar: breathing", Texts.radar("Respiratory rate", 13.94, Locale.US), "13.9 /min");
        eq("radar: skin temperature as on Today", Texts.radar("Skin temperature", -0.17, Locale.US), "-0.2 \u00b0C");
        eq("radar: skin temperature near zero", Texts.radar("Skin temperature", -0.04, Locale.US), "0.0 \u00b0C");
        eq("radar: phone's decimal sign", Texts.radar("Respiratory rate", 13.94, de), "13,9 /min");
        eq("radar: unknown marker keeps one decimal", Texts.radar("Other", 1.25, Locale.US), "1.3");
        eq("radar: no value", Texts.radar("HRV", Double.NaN, Locale.US), "--");
        // the radar stores its markers at 2 decimals, the day's temp_dev at 3: the radar shows temp_dev (review of #181)
        eq("radar: skin temperature from temp_dev, 0.046 is 0.0 as on Today", Texts.radar("Skin temperature", 0.05, 0.046, Locale.US), "0.0 \u00b0C");
        eq("radar: skin temperature, 0.046 stored at 2 decimals alone would read +0.1", Texts.radar("Skin temperature", 0.05, Locale.US), "+0.1 \u00b0C");
        eq("radar: skin temperature falls back to the marker", Texts.radar("Skin temperature", -0.17, Double.NaN, Locale.US), "-0.2 \u00b0C");
        eq("radar: temp_dev only for skin temperature", Texts.radar("HRV", 59.3, 0.046, Locale.US), "59 ms");
        Wellness.Radar wr = Wellness.radar(new double[] {0.1, 50, 60, 14}, new double[][] {
            {0, 0.1, -0.1, 0.2, -0.2, 0.05, 0}, {49, 50, 51, 52, 48, 50, 50}, {55, 60, 65, 58, 62, 61, 59}, {13, 14, 15, 14, 13.5, 14.5, 14}});
        for (String k : wr.markers.keySet()) check("radar: every marker has a unit: " + k, !Texts.radar(k, 1, Locale.US).equals("1.0"));
        // "1 time" on the Screen tab (issue #165)
    }

    /** Issues #89 and #92: counted words agree with the number, toasts stay short, raw errors never reach the screen. */
    static void texts() throws IOException {
        // the data check's line about a failed part of a day: class and place, never the message (issue #235)
        NumberFormatException nfe = new NumberFormatException("For input string: \"61.5\"");
        nfe.setStackTrace(new StackTraceElement[] {new StackTraceElement("java.lang.Double", "parseDouble", "Double.java", 538),
            new StackTraceElement("app.openring.data.ScreenDays", "info", "ScreenDays.java", 182),
            new StackTraceElement("app.openring.ui.DayData", "load", "DayData.java", 90)});
        eq("load note: class and first own frame", LoadNote.error(nfe), "NumberFormatException at ScreenDays.java:182");
        String ln = LoadNote.line("2026-11-01", "screen time", nfe, "2026-11-02 08:14");
        eq("load note: the line", ln, "day 2026-11-01, screen time: NumberFormatException at ScreenDays.java:182 (noted 2026-11-02 08:14)");
        check("load note: no value of the message", !ln.contains("61") && !ln.contains("input"));
        RuntimeException outside = new RuntimeException("Value 61.5 at hr");
        outside.setStackTrace(new StackTraceElement[] {new StackTraceElement("org.json.JSONObject", "get", "JSONObject.java", 10)});
        eq("load note: no own frame, the class only", LoadNote.error(outside), "RuntimeException");
        RuntimeException noFile = new RuntimeException("x");
        noFile.setStackTrace(new StackTraceElement[] {new StackTraceElement("app.openring.data.Db", "kv", null, -1)});
        eq("load note: an own frame without file", LoadNote.error(noFile), "RuntimeException");
        RuntimeException noLine = new RuntimeException("x");
        noLine.setStackTrace(new StackTraceElement[] {new StackTraceElement("app.openring.data.Db", "kv", "Db.java", -1)});
        eq("load note: an own frame without line", LoadNote.error(noLine), "RuntimeException at Db.java");
        eq("load note: none", LoadNote.error(null), "unknown error");
        // issue #289: the safe form is recognised, a full text an older version stored never is
        check("load note: the class and place are safe", LoadNote.safe(LoadNote.error(nfe)) && LoadNote.safe("NumberFormatException at Analyzer.java:412")
                && LoadNote.safe("RuntimeException at Db.java") && LoadNote.safe("OutOfMemoryError") && LoadNote.safe(LoadNote.error(outside)));
        check("load note: a full text is not safe", !LoadNote.safe("java.lang.NumberFormatException: For input string: \"61.5\"")
                && !LoadNote.safe("org.json.JSONException: Value 61.5 at hr of type java.lang.Double cannot be converted to JSONObject")
                && !LoadNote.safe("java.lang.OutOfMemoryError") && !LoadNote.safe("NumberFormatException: 61.5")
                && !LoadNote.safe("NumberFormatException at Analyzer.java:412 61.5") && !LoadNote.safe("") && !LoadNote.safe(null)
                && !LoadNote.safe(LoadNote.error(null)));
        Throwable anon = new RuntimeException("61.5") {};
        check("load note: an anonymous error class is named by its binary name, no value", LoadNote.error(anon).startsWith("CoreTest$")
                && !LoadNote.error(anon).contains("61.5") && LoadNote.safe(LoadNote.error(anon)), LoadNote.error(anon));
        String later = LoadNote.line("2026-11-01", "screen time", nfe, "2026-11-02 09:30");
        check("load note: the same failure later is not written again", !LoadNote.differs(ln, later));
        check("load note: another part, date or error is written",
                LoadNote.differs(ln, LoadNote.line("2026-11-01", "latest readings", nfe, "2026-11-02 08:14"))
                && LoadNote.differs(ln, LoadNote.line("2026-10-31", "screen time", nfe, "2026-11-02 08:14"))
                && LoadNote.differs(ln, LoadNote.line("2026-11-01", "screen time", outside, "2026-11-02 08:14"))
                && LoadNote.differs(null, ln) && !LoadNote.differs(null, null));
        check("load note: a good load of that date clears it", LoadNote.clearedBy(ln, "2026-11-01"));
        check("load note: a good load of another date keeps it", !LoadNote.clearedBy(ln, "2026-11-02") && !LoadNote.clearedBy(ln, "2026-11-0")
                && !LoadNote.clearedBy(null, "2026-11-01") && !LoadNote.clearedBy(ln, null));

        eq("count: one", Texts.count(1, "wake up", "wake ups"), "1 wake up");
        eq("count: zero is plural", Texts.count(0, "wake up", "wake ups"), "0 wake ups");
        eq("count: two", Texts.count(2, "second", "seconds"), "2 seconds");
        eq("count: minus one is plural", Texts.count(-1, "day", "days"), "-1 days");
        eq("count: long values", Texts.count(12345678901L, "event", "events"), "12345678901 events");
        eq("word: one", Texts.word(1, "event", "events"), "event");
        eq("word: many", Texts.word(5353, "event", "events"), "events");

        String sixty = new String(new char[Texts.TOAST_MAX]).replace('\0', 'a');
        check("toast: 60 characters fit", Texts.fitsToast(sixty));
        check("toast: 61 characters go into the dialog", !Texts.fitsToast(sixty + "a"));
        check("toast: no text is not a toast", !Texts.fitsToast(null));
        check("toast: short confirmations fit", Texts.fitsToast("Backup saved") && Texts.fitsToast("Exported"));
        // passed to the toast through a variable (RingManager, then Settings), so the source scan below cannot see it
        check("toast: factory reset text fits", Texts.fitsToast(Texts.RING_RESET_DONE));
        check("toast: repair failed text fits", Texts.fitsToast(Texts.REPAIR_FAILED));
        check("repair failed: says where to look, not above", Texts.REPAIR_FAILED.contains("Data check and log") && !Texts.REPAIR_FAILED.contains("above"));

        for (boolean backup : new boolean[] {false, true}) {
            String t = Texts.saveFailed(backup);
            check("save failed " + backup + ": names what failed", t.startsWith(backup ? "The backup could not be saved." : "The export could not be saved."));
            check("save failed " + backup + ": says what to do", t.contains("Pick another place") && t.contains("try again"));
            check("save failed " + backup + ": points to the log", t.endsWith(SyncError.DETAILS));
            check("save failed " + backup + ": no raw text", !t.contains("null") && !t.contains("Exception") && !t.contains("failed:"));
            check("save failed " + backup + ": no dash as punctuation", !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
        }
        // issue #115: a copy of the history that did not pass its check
        eq("not checked: backup", Texts.notChecked(true),
                "The backup was not saved, because the copy of your history did not pass its check. Nothing on this phone was changed. Try again in a minute.");
        eq("not checked: export", Texts.notChecked(false),
                "The export was not saved, because the copy of the history did not pass its check. Nothing on this phone was changed. Try again in a minute.");
        for (boolean backup : new boolean[] {false, true}) {
            String t = Texts.notChecked(backup);
            check("not checked " + backup + ": goes into the dialog, no dash as punctuation", !Texts.fitsToast(t) && !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
        }
        // issue #33: a key saved by Windows Notepad starts with a byte order mark, some editors put in a no break space
        byte[] key = Hex.parseKey("4431967d8bacc2659743142b68391d9a");
        check("key: a byte order mark is ignored", Arrays.equals(Hex.parseKey("\uFEFF4431967d8bacc2659743142b68391d9a\r\n"), key));
        check("key: a no break space is ignored", Arrays.equals(Hex.parseKey("4431967d 8bacc265 9743142b 68391d9a"), key));
        check("key: colons and 0x still work", Arrays.equals(Hex.parseKey("0x44:31:96:7d:8b:ac:c2:65:97:43:14:2b:68:39:1d:9a"), key));
        check("key: other characters are still refused", Hex.parseKey("​4431967d8bacc2659743142b68391d9a") == null
                && Hex.parseKey("4431967d8bacc2659743142b68391d9") == null);

        // Restfulness and Move every hour (Scores)
        Night n = new Night();
        n.wasoMin = 3;
        n.awakenings = 1;
        eq("restfulness: one wake up", part(Scores.sleep(n, 0, 40), "restfulness").detail, "1 wake up, 0h 3m awake");
        n.awakenings = 2;
        eq("restfulness: two wake ups", part(Scores.sleep(n, 0, 40), "restfulness").detail, "2 wake ups, 0h 3m awake");
        n.awakenings = 0;
        eq("restfulness: no wake ups", part(Scores.sleep(n, 0, 40), "restfulness").detail, "0 wake ups, 0h 3m awake");
        Scores.ActivityInput in = activityIn(7, 5, 2, 800);
        eq("move every hour: one long sit", part(Scores.activity(in), "move_hourly").detail, "1 long sitting period");
        in.longInactiveStreaks = 3;
        eq("move every hour: three long sits", part(Scores.activity(in), "move_hourly").detail, "3 long sitting periods");

        // every toast in the app: literal texts fit in two lines, no raw error text on any message (issue #89)
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        int toasts = 0, toastConsts = 0;
        // a constant of any class (Texts.X, Pairing.ALREADY) or of the file itself (REPAIRING), not a call
        java.util.regex.Pattern toastConst = java.util.regex.Pattern.compile("(?<![\\w.])(?:([A-Z]\\w*)\\.)?([A-Z][A-Z0-9_]+)\\b(?!\\s*[.(])");
        List<java.io.File> files = new ArrayList<java.io.File>();
        javaFiles(new java.io.File("app/src/main/java/app/openring"), files);
        check("toast scan: app sources found", files.size() > 0);
        for (java.io.File f : files) {
            String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            for (String call : new String[] {"toast(", "Toast.makeText(", "say(", "problem("}) {
                for (int i = src.indexOf(call); i >= 0; i = src.indexOf(call, i + 1)) {
                    if (i > 0 && Character.isJavaIdentifierPart(src.charAt(i - 1)) && !call.startsWith("Toast")) continue;
                    String args = callArgs(src, i + call.length());
                    String where = f.getName() + " " + call + args;
                    check("no raw error text: " + where, !args.contains("getMessage()") && !args.matches("(?s).*\\+\\s*e\\s*$")
                            && !args.contains("failed: \""));
                    if (call.equals("say(") || call.equals("problem(")) continue;
                    // each literal is measured alone, so a toast built from parts ("..." + x + "...") could be longer
                    // than all of them: such texts must be built before and go through say(), or be a constant measured below
                    check("toast text is not built from parts: " + where, literal.matcher(args).replaceAll("\"\"").indexOf('+') < 0);
                    java.util.regex.Matcher m = literal.matcher(args);
                    while (m.find()) {
                        toasts++;
                        check("toast fits in two lines: " + m.group(1), Texts.fitsToast(m.group(1)));
                    }
                    // a constant of core's text classes is measured by its value, so no long one slips past (issue #274)
                    java.util.regex.Matcher c = toastConst.matcher(literal.matcher(args).replaceAll("\"\""));
                    while (c.find()) {
                        String cls = c.group(1), name = c.group(2);
                        if ("Toast".equals(cls)) continue; // Toast.LENGTH_LONG
                        toastConsts++;
                        Object v = toastConstant(cls, name, f, files);
                        String what = (cls == null ? f.getName().replace(".java", "") : cls) + "." + name;
                        check("toast constant is a text: " + where, v instanceof String);
                        check("toast constant fits in two lines: " + what, v instanceof String && Texts.fitsToast((String) v));
                    }
                }
            }
        }
        check("toast scan: toast texts found", toasts > 0);
        check("toast scan: toast constants found", toastConsts > 0);

        // raw exception text built anywhere on a screen, also into a variable shown later (as the export and
        // repair messages did before #89): only log lines and the SyncError mapping may use it
        java.util.regex.Pattern rawE = java.util.regex.Pattern.compile("\"\\s*\\+\\s*e(\\.toString\\(\\))?\\s*[;),]");
        int uiFiles = 0, rawLines = 0;
        for (java.io.File f : files) {
            if (!f.getPath().replace('\\', '/').contains("/ui/")) continue;
            uiFiles++;
            String[] lines = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.contains("log(") || l.contains("SyncError.")) continue;
                if (l.contains("getMessage()") || rawE.matcher(l).find()) {
                    rawLines++;
                    System.out.println("  raw error text on screen: " + f.getName() + ":" + (i + 1) + " " + l.trim());
                }
            }
        }
        check("raw error scan: screen sources found", uiFiles > 0);
        eq("no raw error text built on any screen", rawLines, 0);
    }

    /**
     * The value of a constant passed to a toast: a class of core by reflection, otherwise the declaration
     * {@code static final String NAME = "...";} in that class's source (or in {@code self} when unqualified),
     * made of string literals only. Null when it cannot be found or is not such a text.
     */
    static Object toastConstant(String cls, String name, java.io.File self, List<java.io.File> files) throws IOException {
        if (cls != null) {
            try {
                return Class.forName("app.openring.core." + cls).getField(name).get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        java.io.File src = null;
        for (java.io.File f : files) if (f.getName().equals((cls == null ? self.getName().replace(".java", "") : cls) + ".java")) src = f;
        if (src == null) return null;
        String text = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("static\\s+final\\s+String\\s+" + name
                + "\\s*=\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+);").matcher(text);
        if (!d.find()) return null;
        StringBuilder v = new StringBuilder();
        java.util.regex.Matcher l = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(d.group(1));
        while (l.find()) v.append(l.group(1));
        return v.toString();
    }

    static void javaFiles(java.io.File dir, List<java.io.File> out) {
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        for (java.io.File f : list) {
            if (f.isDirectory()) javaFiles(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    /** The argument text of a call whose opening bracket ends at {@code from}, up to the matching closing bracket. */
    static String callArgs(String src, int from) {
        int depth = 1;
        boolean str = false;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return src.substring(from, i);
        }
        return src.substring(from);
    }

    /** Stage percentages: REM, light and deep of time asleep add up to 100, awake of time in bed (issue #32). */
    static void stageShare() {
        int[] a = StageShare.sleepShares(43, 262, 113);
        eq("shares: night like the issue's", Arrays.toString(a), "[10, 63, 27]");
        int[] third = StageShare.sleepShares(100, 100, 100);
        eq("shares: thirds tie goes to the first stage", Arrays.toString(third), "[34, 33, 33]");
        int[] up = StageShare.sleepShares(45, 245, 210);
        // exact 9, 49, 42 with no remainder
        eq("shares: exact values stay", Arrays.toString(up), "[9, 49, 42]");
        int[] over = StageShare.sleepShares(5.5, 5.5, 89); // 5.5 + 5.5 + 89, plain rounding gives 6 + 6 + 89 = 101
        eq("shares: 101 case", Arrays.toString(over), "[6, 5, 89]");
        for (int t = 0; t < 200; t++) {
            int[] r = StageShare.sleepShares(t * 1.37 % 97, t * 2.11 % 300, t * 0.73 % 140 + 1);
            if (r[0] + r[1] + r[2] != 100) { check("shares: always 100 (" + t + ")", false); break; }
        }
        check("shares: no sleep gives null", StageShare.sleepShares(0, 0, 0) == null && StageShare.sleepShares(0.2, 0.3, 0.1) == null);
        check("shares: a missing stage gives null", StageShare.sleepShares(Double.NaN, 300, 50) == null
                && StageShare.sleepShares(40, Double.NaN, 50) == null && StageShare.sleepShares(40, 300, Double.NaN) == null);
        eq("shares: negative minutes count as 0", Arrays.toString(StageShare.sleepShares(0, 300, -5)), "[0, 100, 0]");
        eq("share: awake of time in bed", StageShare.share(54, 543), 10.0);
        check("share: no base", Double.isNaN(StageShare.share(10, 0)) && Double.isNaN(StageShare.share(10, Double.NaN))
                && Double.isNaN(StageShare.share(Double.NaN, 400)));
        eq("share: negative part is 0", StageShare.share(-3, 400), 0.0);
        eq("note: with range", StageShare.note(10, "sleep", StageShare.USUAL_DEEP), "10% of sleep (usual 13 to 23%)");
        eq("note: rem range", StageShare.note(27, "sleep", StageShare.USUAL_REM), "27% of sleep (usual about 20%)");
        eq("note: without range", StageShare.note(63, "sleep", null), "63% of sleep");
        eq("note: awake", StageShare.note(9, "time in bed", null), "9% of time in bed");
        eq("note: unknown with range", StageShare.note(Double.NaN, "sleep", StageShare.USUAL_DEEP), "Usual 13 to 23% of sleep");
        eq("note: unknown without range", StageShare.note(Double.NaN, "sleep", null), "No data yet");
    }

    /** Issues #111, #138, #119: stage minutes that add up to the totals shown, one rounding for night times, tab label size. */
    static void sleepMinutes() {
        // #111, the issue's demo night: rounded alone 0h 48m + 4h 20m + 2h 23m = 7h 31m next to Total sleep 7h 30m
        double deep = 47.6, light = 259.6, rem = 142.6, asleep = deep + light + rem;
        // alone the rows round to 48 + 260 + 143 = 451, total sleep shows 450
        long[] t = StageShare.roundTogether(new double[] {deep, light, rem}, 450);
        eq("together they add up to 450, ties to the earlier stage", Arrays.toString(t), "[48, 260, 142]");
        // with awake and the shown window 22:25 to 06:47
        double start = 1761431120, end = start + 501.8333 * 60; // 22:25:20 to 06:47:10 UTC
        long inBedShown = WakeUps.shownMinutes(start, end);
        eq("shown time in bed is the minutes between the shown clocks", inBedShown, 502L);
        double inBed = (end - start) / 60, awake = inBed - asleep;
        // alone the rows with awake round to 503
        double[] m = StageShare.shownMinutes(deep, light, rem, awake, 0, asleep, inBed, inBedShown);
        eq("sleep page rows", Arrays.toString(m), "[48.0, 260.0, 142.0, 52.0, 0.0]");
        // No data (issue #107) joins awake in the time in bed group
        double[] nd = StageShare.shownMinutes(40.4, 250.4, 120.4, 30.4, 19.6, 411.2, 461.2, 461);
        eq("no data row: rows add up to time in bed", nd[0] + nd[1] + nd[2] + nd[3] + nd[4], 461.0);
        eq("no data row: sleep adds up to total sleep", nd[0] + nd[1] + nd[2], 411.0);
        eq("no data row: no data keeps its 20 min", nd[4], 20.0);
        // the shown time in bed may be one minute off the exact one (rounded clocks): awake takes it
        double[] off = StageShare.shownMinutes(60, 300, 100, 40.2, 0, 460, 500.2, 501);
        eq("one minute more from the rounded clocks goes to awake", off[3], 41.0);
        // parts that do not add up to their total are rounded to their own sum, never stretched
        double[] mix = StageShare.shownMinutes(40.6, 250.6, 120.6, 30, 0, 420, 450, 450);
        eq("mixed night: sleep rows to their own sum", Arrays.toString(mix), "[41.0, 251.0, 120.0, 30.0, 0.0]");
        // Further analysis: no time in bed shown, awake alone
        double[] fa = StageShare.shownMinutes(43.4, 281.4, 124.4, 51.6, 0, 449.2, 500.8, -1);
        eq("further analysis: sleep rows add up to asleep", fa[0] + fa[1] + fa[2], 449.0);
        eq("further analysis: awake rounded alone", fa[3], 52.0);
        // a missing stage stays unknown, the others round alone
        double[] miss = StageShare.shownMinutes(Double.NaN, 250.6, 120.6, 30.4, 0, 371.2, 401.6, 402);
        check("missing stage stays NaN", Double.isNaN(miss[0]));
        eq("missing stage: others round alone", miss[1] + "," + miss[2], "251.0,121.0");
        eq("missing stage: awake rounded alone", miss[3], 30.0);
        check("missing awake stays NaN", Double.isNaN(StageShare.shownMinutes(40, 250, 120, Double.NaN, 0, 410, 450, 450)[3]));
        // roundTogether edge cases
        check("total out of reach gives null", StageShare.roundTogether(new double[] {1.2, 1.2}, 5) == null
                && StageShare.roundTogether(new double[] {1.2, 1.2}, -1) == null && StageShare.roundTogether(new double[] {0.2, 5.2}, 3) == null);
        eq("total below the floors: smallest remainder gives a minute", Arrays.toString(StageShare.roundTogether(new double[] {28.002, 10.0009}, 37)), "[28, 9]");
        eq("total below the floors: never below 0", Arrays.toString(StageShare.roundTogether(new double[] {0.1, 10.5}, 9)), "[0, 9]");
        // the rounded clocks can make the shown time in bed a minute short of the exact rows: the smallest remainder gives it
        double[] shortBed = StageShare.shownMinutes(73.506, 66.506, 30.503, 28.0024, 10.0009, 170.515, 208.518, 208);
        eq("shown time in bed a minute short: rows still add up", Arrays.toString(shortBed), "[74.0, 67.0, 30.0, 28.0, 9.0]");
        check("NaN part gives null", StageShare.roundTogether(new double[] {Double.NaN, 1}, 1) == null);
        eq("negative part counts as 0", Arrays.toString(StageShare.roundTogether(new double[] {-3, 2.5}, 3)), "[0, 3]");
        eq("exact parts stay", Arrays.toString(StageShare.roundTogether(new double[] {10, 20, 30}, 60)), "[10, 20, 30]");
        // review of #201: a part that is exactly 0 never gets a minute
        eq("zero part gets no minute", Arrays.toString(StageShare.roundTogether(new double[] {10.9, 10.9, 0}, 23)), "[12, 11, 0]");
        eq("zero part: one other part may take two", Arrays.toString(StageShare.roundTogether(new double[] {8.96, 0}, 10)), "[10, 0]");
        check("only zero parts cannot take a minute", StageShare.roundTogether(new double[] {0, -2}, 1) == null);
        double[] noMissing = StageShare.shownMinutes(31.6, 236.6, 71.6, 8.96, 0, 339.8, 348.76, 350);
        eq("no missing data: No data stays 0 when awake takes two", noMissing[4] + "," + (noMissing[3] + noMissing[0] + noMissing[1] + noMissing[2]), "0.0,350.0");
        // review of #201 (Must fix): a one minute awakening never shows 0 next to its wake up. Here the rounded clocks
        // make the shown time in bed (154) a minute short and awake (1.0012) had the smallest remainder
        double ws = 1761400001.82, we = 1761409283.35, wIb = (we - ws) / 60, wAwake = 1.0012, wAsleep = wIb - wAwake;
        double wDeep = 55.2, wLight = 72.9, wRem = wAsleep - wDeep - wLight;
        eq("short awakening: shown time in bed is a minute short", WakeUps.shownMinutes(ws, we), 154L);
        double[] wake = StageShare.shownMinutes(wDeep, wLight, wRem, wAwake, 0, wAsleep, wIb, WakeUps.shownMinutes(ws, we));
        eq("short awakening: awake keeps its minute", wake[3], 1.0);
        eq("short awakening: sleep rows still add up to total sleep", wake[0] + wake[1] + wake[2], (double) Math.round(wAsleep));
        check("roundTogether: a part shown as 1 from 0.5 or more does not give its minute",
                StageShare.roundTogether(new double[] {1.0012, 0}, 0) == null);
        eq("roundTogether: another part gives the minute instead", Arrays.toString(StageShare.roundTogether(new double[] {1.0012, 5.4}, 5)), "[1, 4]");
        eq("roundTogether: a part of half a minute or more gets a minute first", Arrays.toString(StageShare.roundTogether(new double[] {0.6, 50.9}, 51)), "[1, 50]");
        check("roundTogether: not enough minutes for every part of half a minute gives null",
                StageShare.roundTogether(new double[] {0.6, 0.6, 0.6}, 1) == null);
        // review of #201 (Should fix): the note says All of it when all the time without stage data was before sleep,
        // also when the row was rounded up to a minute more than the exact before minutes
        eq("note before: all of it follows the shown row", StageShare.shownBefore(20.4, 20.4, 21), 21.0);
        eq("note before: a part stays exact", StageShare.shownBefore(20.4, 5.2, 21), 5.2);
        eq("note before: never more than the row", StageShare.shownBefore(20.6, 20.55, 20), 20.0);
        check("note before: all of it reads All of it", StageShare.noDataNote(21, StageShare.shownBefore(20.4, 20.4, 21), true)
                .endsWith(" All of it was before you fell asleep, so latency includes it."));
        check("note before: unknown stays unknown", Double.isNaN(StageShare.shownBefore(20, Double.NaN, 20)));
        // many nights: rows always add up and each stays within a minute of its exact value
        Random rnd = new Random(111);
        int bad = 0;
        for (int i = 0; i < 2000; i++) {
            double step = 0.5, len = 400 + rnd.nextInt(800);
            int dN = rnd.nextInt((int) len / 4), lN = rnd.nextInt((int) len / 2), rN = rnd.nextInt((int) len / 4);
            int aN = rnd.nextInt((int) len / 8), nN = rnd.nextInt(30);
            double s0 = 1761400000 + rnd.nextInt(60) + rnd.nextDouble(), e0 = s0 + (dN + lN + rN + aN + nN) * step * 60 * (1 + rnd.nextDouble() * 1e-4);
            double ib = (e0 - s0) / 60, ep = ib / (dN + lN + rN + aN + nN);
            double dd = dN * ep, ll = lN * ep, rr = rN * ep, aa = aN * ep, nn = nN * ep;
            long shownIb = WakeUps.shownMinutes(s0, e0);
            double[] r = StageShare.shownMinutes(dd, ll, rr, aa, nn, dd + ll + rr, ib, shownIb);
            double[] ex = {dd, ll, rr, aa, nn};
            // a part of half a minute to under two minutes keeps its one minute even when its group then cannot add
            // up (it would otherwise show 0): only then may the rows be a minute off their totals
            boolean tiny = false;
            for (double v : ex) if (v >= 0.5 && v < 2) tiny = true;
            boolean ok = tiny || (r[0] + r[1] + r[2] == Math.round(dd + ll + rr) && r[0] + r[1] + r[2] + r[3] + r[4] == shownIb);
            for (int k = 0; k < 5; k++) if (Math.abs(r[k] - ex[k]) >= 2) ok = false;
            for (int k = 0; k < 3; k++) if (Math.abs(r[k] - ex[k]) >= 1) ok = false;
            // a part of half a minute or more never shows 0, a part that is 0 never shows a minute (review of #201)
            for (int k = 0; k < 5; k++) if ((ex[k] >= 0.5 && r[k] < 1) || (ex[k] == 0 && r[k] != 0)) ok = false;
            if (!ok && bad++ < 3) System.out.println("  night " + Arrays.toString(ex) + " in bed " + ib + " shown " + shownIb + " got " + Arrays.toString(r));
        }
        eq("2000 nights: rows add up (unless a part of under two minutes keeps its minute), sleep stages within a minute, awake and no data within two, nothing hidden", bad, 0);
        // nights without missing data never get a No data minute, nights without deep never a deep minute
        int ghost = 0;
        for (int i = 0; i < 200000; i++) {
            double ep = 0.5 * (1 + rnd.nextDouble() * 1e-4);
            int dN = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(200), lN = 1 + rnd.nextInt(600), rN = rnd.nextInt(200), aN = rnd.nextInt(100);
            double s0 = 1761400000 + rnd.nextInt(60) + rnd.nextDouble(), e0 = s0 + (dN + lN + rN + aN) * ep * 60;
            double ib = (e0 - s0) / 60, st = ib / (dN + lN + rN + aN);
            double[] r = StageShare.shownMinutes(dN * st, lN * st, rN * st, aN * st, 0, (dN + lN + rN) * st, ib, WakeUps.shownMinutes(s0, e0));
            if (r[4] != 0 || (dN == 0 && r[0] != 0)) ghost++;
        }
        eq("200000 nights: no No data or deep minute that is not there", ghost, 0);
        // short awakenings (1 to 2.5 min) with clock times anywhere in the second: awake never shows 0 (review of #201)
        int hidden = 0, notSum = 0;
        for (int i = 0; i < 200000; i++) {
            double aw = 0.5 + rnd.nextDouble() * 2, dd = rnd.nextDouble() * 100, ll = 10 + rnd.nextDouble() * 300, rr = rnd.nextDouble() * 100;
            double nn = rnd.nextInt(3) == 0 ? rnd.nextDouble() * 3 : 0, ib = dd + ll + rr + aw + nn;
            double s0 = 1761400000 + rnd.nextInt(60) + rnd.nextDouble(), e0 = s0 + ib * 60;
            double[] r = StageShare.shownMinutes(dd, ll, rr, aw, nn, dd + ll + rr, ib, WakeUps.shownMinutes(s0, e0));
            double[] ex = {dd, ll, rr, aw, nn};
            for (int k = 0; k < 5; k++) if ((ex[k] >= 0.5 && r[k] < 1) || (ex[k] == 0 && r[k] != 0)) { hidden++; break; }
            boolean tiny = (dd >= 0.5 && dd < 2) || (rr >= 0.5 && rr < 2);
            if (!tiny && r[0] + r[1] + r[2] != Math.round(dd + ll + rr)) notSum++;
        }
        eq("200000 nights with a short awakening: no part of half a minute or more shows 0", hidden, 0);
        eq("200000 nights with a short awakening: sleep rows add up to total sleep (unless a stage of under two minutes keeps its minute)", notSum, 0);

        // #138: one rounding for night times
        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        double t0 = java.time.Instant.parse("2026-10-20T22:42:00Z").getEpochSecond();
        eq("clock: 29 s rounds down", WakeUps.clock(t0 + 29, utc), "22:42");
        eq("clock: 30 s rounds up like the timeline", WakeUps.clock(t0 + 30, utc), "22:43");
        eq("clock: 22:42:40 is 22:43 on the score card too", WakeUps.clock(t0 + 40, utc), "22:43");
        eq("clock: unknown", WakeUps.clock(Double.NaN, utc), "--");
        eq("clock: next day", WakeUps.clock(java.time.Instant.parse("2026-10-20T23:59:45Z").getEpochSecond(), utc), "00:00");
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        double n0 = java.time.Instant.parse("2026-10-14T00:15:40Z").getEpochSecond(); // 02:15:40 summer time
        eq("rounded stage tip: times and length as shown", ClockChange.roundedStageTip(z, n0 + 400, "Deep", n0, n0 + 1500 - 50),
                "02:22  Deep, 02:16 to 02:40 (24 min)");
        double s1 = java.time.Instant.parse("2026-10-25T00:00:00Z").getEpochSecond();
        eq("rounded stage tip: repeated hour keeps its note", ClockChange.roundedStageTip(z, s1 + 3600 + 22 * 60 + 10, "Deep", s1 + 3600 + 15 * 60 - 20,
                s1 + 3600 + 40 * 60 + 25), "02:22 (second time)  Deep, 02:15 to 02:40 (25 min)");

        // #119: tab label size
        eq("fit: fits, size kept", Texts.fitSize(11, 45, 56, 8), 11f);
        eq("fit: too wide, shrinks by the same factor", Texts.fitSize(11, 70, 56, 8), 8.8f);
        eq("fit: never below the minimum", Texts.fitSize(11, 140, 56, 8), 8f);
        eq("fit: nothing measured, size kept", Texts.fitSize(11, 0, 56, 8), 11f);
    }

    /** Stand ins with Android's class names: LoadNote keeps only the simple name (issue #334). */
    static final class SQLiteFullException extends RuntimeException {
        SQLiteFullException(String m) { super(m); }
    }

    static final class SQLiteDiskIOException extends RuntimeException {
        SQLiteDiskIOException(String m) { super(m); }
    }

    static StackTraceElement[] dbTrace334() {
        return new StackTraceElement[] {new StackTraceElement("android.database.sqlite.SQLiteConnection", "nativeExecute", "SQLiteConnection.java", 1),
            new StackTraceElement("app.openring.data.Db", "get", "Db.java", 45),
            new StackTraceElement("app.openring.ble.RingManager", "syncBlocking", "RingManager.java", 480)};
    }

    /** A sync whose start fails ends with a safe text, the ring lock free and the database released once (issue #334). */
    static void syncStart334() {
        // the stored text: class and place, never the message (issue #289)
        IllegalStateException ise = new IllegalStateException("cannot open file at line 41234 of [a1b2c3]");
        ise.setStackTrace(dbTrace334());
        String raw = SyncError.startFailed(ise);
        eq("#334 start failed: class and place", raw, "Sync could not start: IllegalStateException at Db.java:45");
        check("#334 start failed: the message and its numbers are dropped", !raw.contains("41234") && !raw.contains("cannot open"), raw);
        check("#334 start failed: the part after the prefix is a safe load note", LoadNote.safe(raw.substring(SyncError.START_FAILED.length())), raw);
        eq("#334 start failed: no error", SyncError.startFailed(null), "Sync could not start: unknown error");
        eq("#334 start failed: stored as it is", SyncError.stored(false, raw), raw);
        eq("#334 start failed: an other error is unexpected", SyncError.kind(raw), SyncError.Kind.UNKNOWN);
        eq("#334 start failed: the unexpected sentence with the details", SyncError.plain(raw),
                "The sync did not finish because of an unexpected problem. Sync again. " + SyncError.DETAILS);
        eq("#334 start failed: the notice says the same", SyncError.failureNotice(false, raw), SyncError.plain(raw));

        // full or unwritable storage: a sentence that says what to do
        SQLiteFullException full = new SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)");
        full.setStackTrace(dbTrace334());
        String fullRaw = SyncError.startFailed(full);
        eq("#334 storage: class and place", fullRaw, "Sync could not start: SQLiteFullException at Db.java:45");
        check("#334 storage: the code number is dropped", !fullRaw.contains("13"), fullRaw);
        eq("#334 storage: full is its kind", SyncError.kind(fullRaw), SyncError.Kind.STORAGE);
        SQLiteDiskIOException io = new SQLiteDiskIOException("disk I/O error (code 266)");
        io.setStackTrace(dbTrace334());
        eq("#334 storage: a disk error is its kind", SyncError.kind(SyncError.startFailed(io)), SyncError.Kind.STORAGE);
        String sentence = "This phone's storage is full or could not be written, so the sync could not start. Free some storage or restart the phone, then sync again.";
        eq("#334 storage: the sentence", SyncError.plain(fullRaw), sentence);
        eq("#334 storage: the notice", SyncError.failureNotice(false, fullRaw), sentence);
        eq("#334 storage: stored as it is", SyncError.stored(false, fullRaw), fullRaw);
        eq("#334 storage: other tasks", SyncError.plain(fullRaw, SyncError.Task.ACTION),
                "This phone's storage is full or could not be written. Free some storage or restart the phone, then try again.");
        check("#334 storage: not tried again at once", !SyncError.retryable(fullRaw, true));
        check("#334 storage: no dashes as punctuation", !sentence.contains(" - ") && !sentence.contains("\u2013") && !sentence.contains("\u2014"), sentence);
        check("#334 storage: a ring text is not storage", SyncError.kind("storage failed: no space") != SyncError.Kind.STORAGE);

        java.util.concurrent.locks.ReentrantLock op = new java.util.concurrent.locks.ReentrantLock();
        final int[] released = new int[1];
        Runnable release = new Runnable() {
            @Override
            public void run() {
                released[0]++;
            }
        };

        // an error before the lock (waiting for the analysis)
        SyncStart.Result r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                throw new SQLiteFullException("database or disk is full");
            }
        }, release);
        check("#334 guard, before the lock: the error is kept", r.error instanceof SQLiteFullException, r.error);
        check("#334 guard, before the lock: the message is set", r.message != null && r.message.startsWith(SyncError.START_FAILED), r.message);
        check("#334 guard, before the lock: the lock is free", !op.isLocked());
        eq("#334 guard, before the lock: nothing released", released[0], 0);

        // an error after the lock, before the database was held (opening the database)
        final java.util.concurrent.locks.ReentrantLock lock = op;
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                throw new IllegalStateException("cannot open 4711");
            }
        }, release);
        check("#334 guard, after the lock: the lock is free again", !op.isLocked(), op.getHoldCount());
        eq("#334 guard, after the lock: the database was not held, nothing released", released[0], 0);
        check("#334 guard, after the lock: no message of the error", r.message != null && !r.message.contains("4711"), r.message);

        // an error after the database was held: released once, the lock given back
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                h.ring = true;
                throw new IllegalStateException("late");
            }
        }, release);
        check("#334 guard, after the hold: the lock is free again", !op.isLocked());
        eq("#334 guard, after the hold: released once", released[0], 1);

        // an Error, not only a RuntimeException, with the lock taken twice
        released[0] = 0;
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                lock.lock();
                throw new OutOfMemoryError("Failed to allocate 1048576 bytes");
            }
        }, release);
        check("#334 guard, an Error: caught", r.error instanceof OutOfMemoryError, r.error);
        check("#334 guard, an Error: class and place, no message", r.message != null && r.message.startsWith("Sync could not start: OutOfMemoryError")
                && !r.message.contains("1048576"), r.message);
        check("#334 guard, an Error: the lock is free again", !op.isLocked(), op.getHoldCount());
        eq("#334 guard, an Error: unexpected sentence", SyncError.plain(r.message),
                "The sync did not finish because of an unexpected problem. Sync again. " + SyncError.DETAILS);

        // a release that fails too: the lock is still given back, the first error kept
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                h.ring = true;
                throw new IllegalStateException("first");
            }
        }, new Runnable() {
            @Override
            public void run() {
                throw new IllegalArgumentException("second");
            }
        });
        check("#334 guard, failed release: the lock is free again", !op.isLocked());
        check("#334 guard, failed release: the first error kept", r.error instanceof IllegalStateException && r.error.getSuppressed().length == 1, r.error);

        // a hold taken before the start stays (only the steps' holds are given back)
        op.lock();
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                throw new IllegalStateException("x");
            }
        }, release);
        eq("#334 guard: an earlier hold stays", op.getHoldCount(), 1);
        op.unlock();

        // the normal path: the lock stays held for the download, nothing released
        released[0] = 0;
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                lock.lock();
                h.ring = true;
                return null;
            }
        }, release);
        check("#334 guard, normal: no message, no error", r.message == null && r.error == null);
        check("#334 guard, normal: the lock is held for the download", op.isHeldByCurrentThread());
        eq("#334 guard, normal: nothing released", released[0], 0);
        op.unlock();

        // a refusal (busy, stopped, demo) passes through unchanged, nothing held
        r = SyncStart.run(op, new SyncStart.Steps() {
            @Override
            public String run(SyncStart.Held h) {
                return "The ring is busy with another task";
            }
        }, release);
        eq("#334 guard, refusal: its text", r.message, "The ring is busy with another task");
        check("#334 guard, refusal: no error, lock free", r.error == null && !op.isLocked());
    }

    /** Plain sentences for failed syncs; the raw texts come from the code (issue #47). */
    static void syncError() throws java.io.IOException {
        Object[][] cases = {
            {"Bluetooth is off", SyncError.Kind.BLUETOOTH_OFF},
            {"Bluetooth LE scanner unavailable", SyncError.Kind.BLUETOOTH_OFF},
            {"This phone has no Bluetooth", SyncError.Kind.NO_BLUETOOTH},
            {"No ring found. Put it on its charger next to the phone, and close the Oura app if it is installed.", SyncError.Kind.NOT_FOUND},
            {"connection failed (GATT status 133)", SyncError.Kind.NOT_REACHED},
            {"ring did not accept the connection in time", SyncError.Kind.NOT_REACHED},
            {"Bluetooth refused to open a GATT connection", SyncError.Kind.NOT_REACHED},
            {"service discovery could not start", SyncError.Kind.NOT_REACHED},
            {"service discovery failed", SyncError.Kind.NOT_REACHED},
            {"Bluetooth scan failed (error 2)", SyncError.Kind.NOT_REACHED},
            {"ring disconnected", SyncError.Kind.BROKE_OFF},
            {"the ring disconnected", SyncError.Kind.BROKE_OFF},
            {"write failed (GATT status 133)", SyncError.Kind.BROKE_OFF},
            {"write was never acknowledged", SyncError.Kind.BROKE_OFF},
            {"Bluetooth stack refused the write", SyncError.Kind.BROKE_OFF},
            {"event batch ended without a summary packet (3 packets); the link was probably lost mid batch", SyncError.Kind.BROKE_OFF},
            {"extended history request failed with result code 0x02", SyncError.Kind.BROKE_OFF},
            {"no nonce response from the ring (12 packets)", SyncError.Kind.BROKE_OFF},
            {"no authenticate response (0 packets)", SyncError.Kind.BROKE_OFF},
            {"no serial response", SyncError.Kind.BROKE_OFF},
            {"storage failed; batch not acknowledged", SyncError.Kind.BROKE_OFF},
            {"ring reports 4096 bytes left but the batch was empty (cursor 12)", SyncError.Kind.BROKE_OFF},
            {"the phone could not raise the Bluetooth MTU (23), data would be cut off", SyncError.Kind.CONNECTION_SETUP},
            {"Android refused to start bonding", SyncError.Kind.PAIRING},
            {"bonding was declined or failed", SyncError.Kind.PAIRING},
            {"bonding timed out (accept the pairing request when it appears)", SyncError.Kind.PAIRING},
            {"could not subscribe to ring notifications even after bonding", SyncError.Kind.PAIRING},
            {"this device has no Oura ring service", SyncError.Kind.NOT_A_RING},
            {"ring characteristics missing", SyncError.Kind.NOT_A_RING},
            {"authentication failed: " + Auth.describe(Auth.WRONG_KEY), SyncError.Kind.KEY_REFUSED},
            {"authentication failed: " + Auth.describe(Auth.NOT_ORIGINAL_DEVICE), SyncError.Kind.KEY_REFUSED},
            {"authentication failed: " + Auth.describe(0x7f), SyncError.Kind.UNKNOWN},
            {"authentication failed: " + Auth.describe(Auth.IN_FACTORY_RESET), SyncError.Kind.RING_RESET},
            {"No ring key saved. Finish setup first.", SyncError.Kind.NO_KEY},
            {"The ring is busy with another task", SyncError.Kind.BUSY},
            {"Sync stopped", SyncError.Kind.STOPPED},
            {"Interrupted", SyncError.Kind.STOPPED},
            {"cancelled", SyncError.Kind.STOPPED},
            {"Demo mode: syncing is off", SyncError.Kind.DEMO},
            {SyncError.REANALYSING, SyncError.Kind.REANALYSING},
            {"The ring already has a key. Factory reset it first, or use the existing key.", SyncError.Kind.KEY_EXISTS},
            {"The ring reports unfinished factory tests (status 5).", SyncError.Kind.KEY_NOT_TAKEN},
            {"The ring refused the key (status 0x03)", SyncError.Kind.KEY_NOT_TAKEN},
            {"no set key response, is the ring factory reset?", SyncError.Kind.KEY_NOT_TAKEN},
            {"the ring did not accept the change", SyncError.Kind.NOT_SAVED},
            {"Refusing: the ring that answered is 3A1B2C, not 3A1B2D", SyncError.Kind.SERIAL_MISMATCH},
            {SyncError.OTHER_RING_PREFIX + " (3A1B2C), not the saved 3A1B2D", SyncError.Kind.OTHER_RING},
            {"interrupted", SyncError.Kind.STOPPED},
            {"no firmware response", SyncError.Kind.BROKE_OFF},
            {"no battery response", SyncError.Kind.BROKE_OFF},
            {"Unexpected error: java.lang.NullPointerException: no response", SyncError.Kind.UNKNOWN},
            {"something new", SyncError.Kind.UNKNOWN},
            {"", SyncError.Kind.UNKNOWN},
            {null, SyncError.Kind.UNKNOWN},
        };
        for (Object[] k : cases) eq("sync error: " + k[0], SyncError.kind((String) k[0]), k[1]);
        check("sync error: unknown names the details", SyncError.plain("Unexpected error: java.lang.IllegalStateException").endsWith(SyncError.DETAILS)
                && SyncError.plain(null).endsWith(SyncError.DETAILS));
        check("sync error: key refused says how to set up again", SyncError.plain("authentication failed: x").endsWith(SyncError.SETUP_AGAIN));
        // issue #230: a sync waits for a long analysis, Live and Settings actions on the ring say why they do not start
        eq("re analysing: the status line of a waiting sync", SyncError.SYNC_WAITS, "Re analysing your history, this takes a few minutes. The sync starts after it.");
        eq("re analysing: Live", SyncError.plain(SyncError.REANALYSING, SyncError.Task.LIVE), "Re analysing your history, this takes a few minutes. Try Live again then.");
        eq("re analysing: a Settings action", SyncError.plain(SyncError.REANALYSING, SyncError.Task.ACTION), "Re analysing your history, this takes a few minutes. Try again then.");
        eq("re analysing: a sync", SyncError.plain(SyncError.REANALYSING), "Re analysing your history, this takes a few minutes. Sync again then.");
        eq("re analysing: Re analyse all waits for the ring task", Texts.REANALYSE_WAITS,
                "Waiting for the ring to finish its current task, then re analysing all nights. This can take a few minutes.");
        eq("re analysing: Re analyse all failed", Texts.ANALYSIS_FAILED, "The analysis failed, your nights and days are as before. " + SyncError.DETAILS);
        // issue #222: the texts of the one Re analyse all job and of saving a file
        for (String t : new String[] {Texts.REANALYSING, Texts.REANALYSE_RUNS, Texts.REANALYSE_AGAIN, Texts.ANALYSIS_UPDATED, Texts.ANALYSIS_FAILED_TOAST,
                Texts.SAVING_BACKUP, Texts.SAVING_EXPORT, Texts.SAVING_AUTO, Texts.SAVE_BUSY, Texts.saveFailed(true, true), Texts.saveFailed(false, true)}) {
            check("#222 plain, no dash as punctuation: " + t, !t.isEmpty() && !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
        }
        check("#222 the toasts fit in two lines", Texts.fitsToast(Texts.REANALYSE_RUNS) && Texts.fitsToast(Texts.ANALYSIS_UPDATED)
                && Texts.fitsToast(Texts.ANALYSIS_FAILED_TOAST) && Texts.fitsToast(Texts.REANALYSING) && Texts.fitsToast(Texts.SAVE_BUSY));
        eq("#222 a save that failed without a left over file reads as before", Texts.saveFailed(true, false), Texts.saveFailed(true));
        for (boolean backup : new boolean[] {false, true}) {
            for (boolean left : new boolean[] {false, true}) {
                String t = Texts.saveFailedToast(backup, left);
                check("#222 the short failure fits a toast: " + t, Texts.fitsToast(t) && !t.contains(" - ") && (left == t.contains("incomplete")));
            }
        }
        check("#222 a left over file is called incomplete", Texts.saveFailed(true, true).contains("incomplete, delete it")
                && !Texts.saveFailed(true, false).contains("incomplete"));
        ringError(cases);
    }

    /** The same raw texts after pairing, a Settings action, live heart rate or game motion failed (issue #60). */
    static void ringError(Object[][] cases) throws java.io.IOException {
        SyncError.Task P = SyncError.Task.PAIR, A = SyncError.Task.ACTION, L = SyncError.Task.LIVE;
        // every kind and task: plain, no jargon or raw codes, a sync is never mentioned outside a sync
        Set<String> plainSeen = new HashSet<String>();
        for (Object[] k : cases) {
            for (SyncError.Task task : SyncError.Task.values()) {
                String t = SyncError.plain((String) k[0], task);
                if (!plainSeen.add(task + " " + k[1] + " " + t)) continue; // same sentence as another raw text
                String low = t.toLowerCase(Locale.ROOT);
                check("ring error: plain " + task + " " + k[0], !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014")
                        && !low.contains("gatt") && !low.contains("mtu") && !low.contains("exception") && !low.contains("0x")
                        && !low.contains("status ") && !low.contains("refusing") && t.endsWith(".")
                        && (task == SyncError.Task.SYNC || k[1] == SyncError.Kind.DEMO || !low.contains("sync again")));
            }
        }
        // the texts the issue lists
        eq("ring error: serial mismatch", SyncError.plain("Refusing: the ring that answered is 3A1B2C, not 3A1B2D", A),
                "Nothing was erased: the ring that answered is 3A1B2C, but you typed 3A1B2D. Start the reset again and type the serial the reset message shows.");
        eq("ring error: serial mismatch, nothing typed", SyncError.plain("Refusing: the ring that answered is 3A1B2C, not ", A),
                "Nothing was erased: the ring that answered is 3A1B2C, but you typed nothing. Start the reset again and type the serial the reset message shows.");
        eq("ring error: serial mismatch, typed with spaces", SyncError.plain("Refusing: the ring that answered is 3A1B2C, not  3a1b2d ", A),
                "Nothing was erased: the ring that answered is 3A1B2C, but you typed 3a1b2d. Start the reset again and type the serial the reset message shows.");
        eq("ring error: serial mismatch without a typed part", SyncError.plain("Refusing: the ring that answered is ", A),
                "Nothing was erased: the ring that answered is another one, but you typed nothing. Start the reset again and type the serial the reset message shows.");
        String reach = "The ring could not be reached. Keep it close to the phone (on its charger is best) and try again.";
        eq("ring error: GATT 133 in Settings", SyncError.plain("connection failed (GATT status 133)", A), reach);
        eq("ring error: not in time in Settings", SyncError.plain("ring did not accept the connection in time", A), reach);
        eq("ring error: GATT 133 in Setup", SyncError.plain("connection failed (GATT status 133)", P), reach);
        eq("ring error: GATT 133 in live", SyncError.plain("connection failed (GATT status 133)", L),
                "The ring could not be reached. Keep it close to the phone and try again.");
        eq("ring error: key refused in Setup", SyncError.plain("authentication failed: " + Auth.describe(Auth.WRONG_KEY), P),
                "Your ring did not accept this key. Check that the key is complete and belongs to this ring, or factory reset the ring and use Pair and create a new key.");
        check("ring error: key refused in Settings points to setup", SyncError.plain("authentication failed: " + Auth.describe(Auth.WRONG_KEY), A)
                .endsWith(SyncError.SETUP_AGAIN));
        eq("ring error: reset ring in Setup", SyncError.plain("authentication failed: " + Auth.describe(Auth.IN_FACTORY_RESET), P),
                "Your ring was factory reset and has no key yet. Use Pair and create a new key, or tick Install this key on a factory reset ring.");
        eq("ring error: key status in Setup", SyncError.plain("The ring refused the key (status 0x03)", P),
                "The ring did not take the new key. Check that it was factory reset (blinking blue on the dock means done), put it on its charger next to the phone and try again.");
        eq("ring error: factory tests in Setup", SyncError.plain("The ring reports unfinished factory tests (status 5).", P),
                SyncError.plain("The ring refused the key (status 0x03)", P));
        eq("ring error: key exists kept", SyncError.plain("The ring already has a key. Factory reset it first, or use the existing key.", P),
                "The ring already has a key. Factory reset it first, or use the existing key.");
        eq("ring error: change not saved", SyncError.plain("the ring did not accept the change", A),
                "The ring did not save the change. Keep it close to the phone and try again.");
        eq("ring error: busy in Settings", SyncError.plain("The ring is busy with another task", A),
                "The ring is busy with a sync, live heart rate or a game. Try again in a minute.");
        eq("ring error: broke off in Setup", SyncError.plain("no serial response", P),
                "The connection to the ring broke off. Keep the ring close and try again.");
        eq("ring error: not found in live", SyncError.plain("No ring found. Put it on its charger next to the phone, and close the Oura app if it is installed.", L),
                "Your ring was not found. Keep it close to the phone, close the Oura app if it is installed, and try again.");
        eq("ring error: bluetooth off in Setup", SyncError.plain("Bluetooth is off", P), "Bluetooth is off or not ready. Turn it on and try again.");
        eq("ring error: pairing in Settings", SyncError.plain("bonding was declined or failed", A),
                "The phone and the ring could not pair. Try again and accept the pairing request when it appears.");
        eq("ring error: stopped in live", SyncError.plain("interrupted", L), "This was stopped before it finished. Try again when you like.");
        eq("ring error: unknown in Setup", SyncError.plain("java.lang.NullPointerException", P),
                "Pairing did not finish because of an unexpected problem. Try again.");
        check("ring error: unknown in Settings and live names the details", SyncError.plain("java.lang.NullPointerException", A).endsWith(SyncError.DETAILS)
                && SyncError.plain(null, L).endsWith(SyncError.DETAILS));
        eq("ring error: unknown auth state is no reason to set up again", SyncError.plain("authentication failed: " + Auth.describe(0x7f)),
                "The sync did not finish because of an unexpected problem. Sync again. " + SyncError.DETAILS);
        eq("ring error: a different ring answered", SyncError.plain(SyncError.OTHER_RING_PREFIX + " (3A1B2C), not the saved 3A1B2D", A),
                "Nothing was erased: a different ring (3A1B2C) answered. Move other rings away from the phone and try again.");
        eq("ring error: a different ring without serial", SyncError.plain(SyncError.OTHER_RING_PREFIX, A),
                "Nothing was erased: a different ring answered. Move other rings away from the phone and try again.");
        // the sentences on the main screen stay as #47 wrote them, pinned word for word
        String[][] sync = {
            {"Bluetooth is off", "Bluetooth is off or not ready. Turn it on and sync again."},
            {"This phone has no Bluetooth", "This phone has no Bluetooth, so it cannot reach the ring."},
            {"No ring found. Put it on its charger next to the phone, and close the Oura app if it is installed.",
                "Your ring was not found. Put it on its charger next to the phone, close the Oura app if it is installed, and sync again."},
            {"connection failed (GATT status 133)", "The ring could not be reached. Keep it close to the phone (on its charger is best) and sync again."},
            {"the ring disconnected", "The connection to the ring broke off during the sync. Keep the ring close and sync again, the next sync continues where this one stopped."},
            {"the phone could not raise the Bluetooth MTU (23), data would be cut off",
                "This phone's Bluetooth could not set up the connection the ring needs. Turn Bluetooth off and on, then sync again."},
            {"bonding was declined or failed", "The phone and the ring could not pair. Sync again and accept the pairing request when it appears."},
            {"ring characteristics missing", "The device that answered is not an Oura ring. Sync again with your ring close to the phone."},
            {"authentication failed: " + Auth.describe(Auth.WRONG_KEY),
                "Your ring did not accept the key saved on this phone (it may have been paired again with another app). Set it up again in Settings, Careful, Use a different key or ring."},
            {"authentication failed: " + Auth.describe(Auth.IN_FACTORY_RESET),
                "Your ring was factory reset and has no key yet. Set it up again in Settings, Careful, Use a different key or ring."},
            {"No ring key saved. Finish setup first.", "No ring key is saved on this phone. Finish the setup first."},
            {"The ring is busy with another task", "The ring is busy with live heart rate or a game. Sync again in a minute."},
            {"Sync stopped", "The sync was stopped. Sync again when you like."},
            {"Demo mode: syncing is off", "Syncing is off in demo mode."},
            {"Unexpected error: java.lang.IllegalStateException",
                "The sync did not finish because of an unexpected problem. Sync again. Details are in Settings, Help and diagnostics, Data check and log."},
        };
        for (String[] k : sync) {
            eq("ring error: sync sentence for " + k[0], SyncError.plain(k[0]), k[1]);
            eq("ring error: sync task same for " + k[0], SyncError.plain(k[0], SyncError.Task.SYNC), k[1]);
        }
        // games: the reason and that touch steering goes on, never cut by the three line drawing
        eq("ring error: game text", SyncError.game("authentication failed: " + Auth.describe(Auth.WRONG_KEY)),
                "Ring motion stopped. Your ring did not accept the key saved on this phone. Touch steering works meanwhile.");
        eq("ring error: game text unknown", SyncError.game(null), "Ring motion stopped. Something unexpected went wrong. Touch steering works meanwhile.");
        Set<String> gameSeen = new HashSet<String>();
        for (Object[] k : cases) {
            String g = SyncError.game((String) k[0]);
            if (!gameSeen.add(g)) continue; // same text as another raw text
            List<String> lines = SyncError.wrap(g, SyncError.GAME_LINE);
            boolean fits = lines.size() <= SyncError.GAME_LINES;
            for (String line : lines) fits &= line.length() <= SyncError.GAME_LINE;
            check("ring error: game text fits for " + k[0] + " (" + g + ")", fits && !g.contains("(") && String.join(" ", lines).equals(g));
        }
        eq("ring error: wrap", SyncError.wrap("aaa bbb  ccc dddddddd", 7), Arrays.asList("aaa bbb", "ccc", "dddddddd"));
        // the "no motion data" text of the games fits the three drawn lines and still says touch steering works (issue #90)
        check("game text: no motion fits", gameFits(SyncError.GAME_NO_MOTION));
        check("game text: no motion names touch steering", SyncError.GAME_NO_MOTION.endsWith("Touch steering works."));
        check("game text: old no motion text did not fit", !gameFits("Your ring sent no motion data, so it probably cannot stream motion on this firmware. "
                + "The ring is free again; touch steering works."));
        // every text the games are given: one literal that fits, a SyncError constant, or SyncError.game (tested above)
        java.util.regex.Pattern lit = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        List<java.io.File> src = new ArrayList<java.io.File>();
        javaFiles(new java.io.File("app/src/main/java/app/openring"), src);
        int states = 0;
        for (java.io.File f : src) {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            for (int i = s.indexOf("motionState("); i >= 0; i = s.indexOf("motionState(", i + 1)) {
                if (s.startsWith("void ", i - 5)) continue; // the method itself
                String args = callArgs(s, i + "motionState(".length());
                String text = args.substring(0, Math.max(0, args.lastIndexOf(','))).trim();
                states++;
                String where = f.getName() + " motionState(" + args;
                java.util.regex.Matcher m = lit.matcher(text);
                if (m.matches()) check("game text fits: " + where, gameFits(m.group(1)));
                else check("game text is a SyncError text: " + where, text.startsWith("SyncError.game(") || text.matches("SyncError\\.GAME_[A-Z_]+"));
            }
        }
        check("game text scan: motion states found", states >= 5);

        // a failed sync always stores some error text (issue #91)
        eq("sync error: raw of an exception without message", SyncError.raw(new java.io.IOException()), "java.io.IOException");
        eq("sync error: raw of a blank message", SyncError.raw(new java.io.IOException(" ")), "java.io.IOException");
        eq("sync error: raw keeps the message", SyncError.raw(new java.io.IOException("ring disconnected")), "ring disconnected");
        eq("sync error: raw of no exception", SyncError.raw(null), SyncError.NO_REASON);
        check("sync error: worked stores nothing", SyncError.stored(true, "Sync complete") == null);
        eq("sync error: failed without text stores the fallback", SyncError.stored(false, null), SyncError.NO_REASON);
        eq("sync error: failed with a blank text stores the fallback", SyncError.stored(false, "  "), SyncError.NO_REASON);
        eq("sync error: failed keeps its text", SyncError.stored(false, "ring disconnected"), "ring disconnected");
        String unknown = "The sync did not finish because of an unexpected problem. Sync again. " + SyncError.DETAILS;
        eq("sync error: fallback reads as the unknown sentence", SyncError.plain(SyncError.stored(false, null)), unknown);
        eq("sync error: exception name reads as the unknown sentence", SyncError.plain(SyncError.raw(new java.io.IOException())), unknown);

        // a sync stops early only when another attempt cannot help (issue #157)
        String[] retry = {"Bluetooth stack refused the write", "Bluetooth refused to open a GATT connection", "Bluetooth scan failed (error 2)",
            "connection failed (GATT status 133)", "ring disconnected", "write was never acknowledged", "service discovery failed", null, ""};
        for (String r : retry) check("sync retry: retried after " + r, SyncError.retryable(r, true));
        String[] stop = {"Bluetooth is off", "Bluetooth LE scanner unavailable", "This phone has no Bluetooth", "interrupted", "Sync stopped",
            "No ring found. Put it on its charger next to the phone, and close the Oura app if it is installed."};
        for (String r : stop) check("sync retry: stops after " + r, !SyncError.retryable(r, true));
        for (String r : retry) check("sync retry: stops with Bluetooth off after " + r, !SyncError.retryable(r, false));
        // a key problem cannot pass on another attempt (independent review of #178)
        String[] keys = {"authentication failed: " + Auth.describe(Auth.WRONG_KEY), "authentication failed: " + Auth.describe(Auth.IN_FACTORY_RESET),
            "No ring key saved. Finish setup first."};
        for (String r : keys) check("sync retry: stops after the key problem " + r, !SyncError.retryable(r, true));
        check("sync retry: an unknown auth state is retried", SyncError.retryable("authentication failed: unknown auth state 0x7f", true));
        // Bluetooth turned off in the middle of a sync: the attempt keeps "Bluetooth is off", not the disconnect it caused
        eq("sync attempt: Bluetooth off replaces the disconnect", SyncError.attemptError("ring disconnected", false), SyncError.BLUETOOTH_OFF_TEXT);
        eq("sync attempt: Bluetooth on keeps the text", SyncError.attemptError("ring disconnected", true), "ring disconnected");
        eq("sync attempt: Bluetooth off text reads as Bluetooth off", SyncError.kind(SyncError.BLUETOOTH_OFF_TEXT), SyncError.Kind.BLUETOOTH_OFF);
        check("sync attempt: Bluetooth off ends the sync", !SyncError.retryable(SyncError.attemptError("write failed (GATT status 133)", false), true));
        // Bluetooth switched off while the scanner looks for the ring (issue #273): Android throws, which reached the
        // sync's broad catch as an unexpected problem; the scanner now gives "Bluetooth is off" like its own check
        RuntimeException off = new IllegalStateException("BT Adapter is not turned ON");
        eq("scanner off: before, the broad catch's text read as unexpected", SyncError.plain("Unexpected error: " + off), unknown);
        eq("scanner off: the scanner's exception is Bluetooth off", SyncError.scannerError(off, false), SyncError.BLUETOOTH_OFF_TEXT);
        eq("scanner off: the plain Bluetooth off sentence", SyncError.plain(SyncError.scannerError(off, false)), "Bluetooth is off or not ready. Turn it on and sync again.");
        check("scanner off: no further attempts", !SyncError.retryable(SyncError.scannerError(off, false), true));
        eq("scanner off: the same sentence for Live", SyncError.plain(SyncError.scannerError(off, false), SyncError.Task.LIVE), "Bluetooth is off or not ready. Turn it on and try again.");
        check("scanner off: other exceptions stay unexpected", SyncError.scannerError(new IllegalArgumentException("bad filter"), false) == null
                && SyncError.scannerError(new SecurityException("no permission"), false) == null && SyncError.scannerError(null, false) == null);
        // review of #277: the scanner's IllegalStateException with Bluetooth on is another fault, not "Bluetooth is off"
        check("scanner off: with Bluetooth on the same exception stays unexpected", SyncError.scannerError(off, true) == null);
        // the notice after a failed sync the person started: the whole plain sentence, none after a stop or success
        check("sync notice: none after a sync that worked", SyncError.failureNotice(true, "Sync complete") == null);
        check("sync notice: none after a stop", SyncError.failureNotice(false, "Sync stopped") == null && SyncError.failureNotice(false, "Interrupted") == null);
        check("sync notice: none in demo mode", SyncError.failureNotice(false, "Demo mode: syncing is off") == null);
        eq("sync notice: the plain sentence", SyncError.failureNotice(false, "connection failed (GATT status 133)"),
                SyncError.plain("connection failed (GATT status 133)"));
        eq("sync notice: also without a reason", SyncError.failureNotice(false, null), unknown);
    }

    /** The games draw at most {@link SyncError#GAME_LINES} lines of {@link SyncError#GAME_LINE} characters. */
    static boolean gameFits(String t) {
        List<String> lines = SyncError.wrap(t, SyncError.GAME_LINE);
        boolean fits = lines.size() <= SyncError.GAME_LINES;
        for (String line : lines) fits &= line.length() <= SyncError.GAME_LINE;
        return fits;
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

    // ------------------------------------------------------- issue #71, apps read more than once

    /**
     * Reads a log the way ScreenUse.collect does (with the same {@link ScreenTime.LogSplit}), once at each of {@code reads}: screen sessions from
     * the newest stored session start (a session with a known start replaces the stored one), apps
     * from {@link ScreenTime#appsReadFrom}, stored with {@link ScreenTime#store}. Returns {apps, screen}.
     */
    @SuppressWarnings("unchecked")
    static Object[] readLog(double[] t, int[] type, String[] pkg, double[] reads, double back) {
        Map<String, List<double[]>> apps = new LinkedHashMap<String, List<double[]>>();
        TreeMap<Double, double[]> screen = new TreeMap<Double, double[]>();
        boolean first = true;
        for (double now : reads) {
            double screenFrom = screen.isEmpty() ? back : screen.lastKey();
            double newest = -1;
            for (List<double[]> l : apps.values()) for (double[] r : l) newest = Math.max(newest, r[0]);
            double appFrom = ScreenTime.appsReadFrom(first, back, screenFrom, newest, now);
            ScreenTime.LogSplit log = new ScreenTime.LogSplit(screenFrom, appFrom);
            for (int i = 0; i < t.length && t[i] < now; i++) log.add(t[i], type[i], pkg[i]);
            ScreenTime.store(apps, ScreenTime.appSessions(log.appTimes(), log.appKinds(), log.appPackages(), appFrom, now));
            for (double[] s : ScreenTime.sessions(log.screenTimes(), log.screenKinds(), screenFrom, now)) screen.put(s[0], s);
            first = false;
        }
        return new Object[] {apps, new ArrayList<double[]>(screen.values())};
    }

    static double appMin(Map<String, List<double[]>> apps, List<double[]> screen, String p) {
        for (Map.Entry<String, Double> e : ScreenTime.perApp(apps, screen, -1e12, 1e12, 0)) if (e.getKey().equals(p)) return e.getValue();
        return 0;
    }

    static boolean sameApps(Map<String, List<double[]>> a, Map<String, List<double[]>> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String p : a.keySet()) {
            List<double[]> x = a.get(p), y = b.get(p);
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!near(x.get(i)[0], y.get(i)[0]) || !near(x.get(i)[1], y.get(i)[1])) return false;
        }
        return true;
    }

    /** Issue #71: reading the log twice gives the apps the same time as reading it once; rows add up. */
    @SuppressWarnings("unchecked")
    static void screenAppReads() {
        // Android's usage event types, as data/ScreenUse reads them (the same functions)
        StringBuilder sk = new StringBuilder(), ak = new StringBuilder();
        for (int type = 0; type <= 30; type++) {
            if (ScreenTime.screenKind(type) != 0) sk.append(type).append('=').append(ScreenTime.screenKind(type)).append(' ');
            if (ScreenTime.appKind(type) != 0) ak.append(type).append('=').append(ScreenTime.appKind(type)).append(' ');
        }
        eq("usage events: screen kinds (1 and 7 in use, 15 on, 16 off, 17 lock, 18 unlock, 26 shutdown, 27 start)", sk.toString(),
                "1=" + ScreenTime.ALIVE + " 7=" + ScreenTime.ALIVE + " 15=" + ScreenTime.ON + " 16=" + ScreenTime.OFF + " 17=" + ScreenTime.LOCKED
                + " 18=" + ScreenTime.UNLOCKED + " 26=" + ScreenTime.SHUTDOWN + " 27=" + ScreenTime.STARTUP + " ");
        eq("usage events: app kinds (1 front, 2 back; off, shutdown and start close every app)", ak.toString(),
                "1=" + ScreenTime.APP_ON + " 2=" + ScreenTime.APP_OFF + " 16=" + ScreenTime.OFF + " 26=" + ScreenTime.SHUTDOWN + " 27=" + ScreenTime.STARTUP + " ");
        ScreenTime.LogSplit split = new ScreenTime.LogSplit(100, 200);
        split.add(50, 15, "x");          // before both starts: dropped
        split.add(150, 1, "a.app");      // screen only (apps start at 200)
        split.add(250, 1, "b.app");      // both, the package kept for the app list
        split.add(260, 16, "android");   // both, no package for "off"
        split.add(270, 23, "c.app");     // a type neither list uses
        eq("log split: screen times from its own start", Arrays.toString(split.screenTimes()), "[150.0, 250.0, 260.0]");
        eq("log split: app times from theirs", Arrays.toString(split.appTimes()), "[250.0, 260.0]");
        eq("log split: packages only for apps in front", Arrays.toString(split.appPackages()), "[b.app, null]");
        eq("log split: kinds", Arrays.toString(split.screenKinds()) + Arrays.toString(split.appKinds()),
                "[" + ScreenTime.ALIVE + ", " + ScreenTime.ALIVE + ", " + ScreenTime.OFF + "][" + ScreenTime.APP_ON + ", " + ScreenTime.OFF + "]");
        // the issue's case: a call over the lock screen while a background sync reads the log at 1100
        double[] t = {900, 900.5, 1000, 1005, 1010, 1200, 1201, 1201.5};
        int[] ty = {16, 17, 15, 1, 18, 2, 16, 17};
        String d = "dialer";
        String[] pk = {null, null, null, d, null, d, null, null};
        Object[] once = readLog(t, ty, pk, new double[] {1300}, 890);
        Object[] twice = readLog(t, ty, pk, new double[] {1100, 1300}, 890);
        Map<String, List<double[]>> a1 = (Map<String, List<double[]>>) once[0], a2 = (Map<String, List<double[]>>) twice[0];
        List<double[]> s1 = (List<double[]>) once[1];
        check("issue 71: one read gives the dialer 3.2 min", Math.abs(appMin(a1, s1, d) - 190 / 60.0) < 1e-9);
        check("issue 71: two reads store one dialer row, the same as one read", sameApps(a1, a2) && a2.get(d).size() == 1
                && near(a2.get(d).get(0)[0], 1005) && near(a2.get(d).get(0)[1], 1200));
        check("issue 71: two reads give the dialer 3.2 min, not 4.7", Math.abs(appMin(a2, (List<double[]>) twice[1], d) - 190 / 60.0) < 1e-9);
        // the phone logs no "went to the back": screen off ends the dialer, nothing goes to "no app in front"
        double[] tn = {900, 900.5, 1000, 1005, 1010, 1201, 1201.5};
        int[] tyn = {16, 17, 15, 1, 18, 16, 17};
        String[] pkn = {null, null, null, d, null, null, null};
        Object[] twiceN = readLog(tn, tyn, pkn, new double[] {1100, 1300}, 890);
        check("issue 71: without its back entry the dialer keeps its time (not 1.5 min)",
                Math.abs(appMin((Map<String, List<double[]>>) twiceN[0], (List<double[]>) twiceN[1], d) - 191 / 60.0) < 1e-9);
        // rows stored twice by older versions count once: per app, and when left out of the total
        Map<String, List<double[]>> old = new LinkedHashMap<String, List<double[]>>();
        old.put(d, new ArrayList<double[]>(Arrays.asList(new double[] {1005, 1100}, new double[] {1010, 1200})));
        List<double[]> scr = new ArrayList<double[]>();
        scr.add(new double[] {1010, 1201});
        check("old doubled rows: the dialer counts 3.2 min", Math.abs(appMin(old, scr, d) - 190 / 60.0) < 1e-9);
        Set<String> ex = new HashSet<String>(Arrays.asList(d));
        check("old doubled rows: the total does not change by merging", near(ScreenTime.minutes(ScreenTime.subtract(scr, ScreenTime.ranges(old, ex)), 0, 2000),
                ScreenTime.minutes(ScreenTime.subtract(scr, ScreenTime.ranges(ScreenTime.merge(old), ex)), 0, 2000)));
        check("old doubled rows: merged into one", ScreenTime.merge(old).get(d).size() == 1 && near(ScreenTime.merge(old).get(d).get(0)[1], 1200));

        // the lead rule: only the first app entry of a read counts from the read start
        Map<String, List<double[]>> lead = ScreenTime.appSessions(new double[] {10, 20, 30}, new int[] {ScreenTime.APP_OFF, ScreenTime.APP_ON, ScreenTime.APP_OFF},
                new String[] {"a", "b", "c"}, 0, 100);
        check("lead: the first back entry counts from the read start", near(lead.get("a").get(0)[0], 0) && near(lead.get("a").get(0)[1], 10));
        check("lead: a later back entry without its front entry is ignored", lead.get("c") == null && near(lead.get("b").get(0)[1], 100));
        Map<String, List<double[]>> afterOff = ScreenTime.appSessions(new double[] {10, 30}, new int[] {ScreenTime.OFF, ScreenTime.APP_OFF},
                new String[] {null, "c"}, 0, 100);
        check("lead: not after the screen went off", afterOff.isEmpty());

        // where the next read starts
        check("read from: first read goes back", near(ScreenTime.appsReadFrom(true, 100, 500, 400, 1000), 100));
        check("read from: the newest app", near(ScreenTime.appsReadFrom(false, 100, 500, 400, 1000), 400));
        check("read from: never before back", near(ScreenTime.appsReadFrom(false, 100, 500, 50, 1000), 100));
        check("read from: no app yet", near(ScreenTime.appsReadFrom(false, 100, 500, -1, 1000), 500));
        check("read from: a stored start in the future does not stop reading", near(ScreenTime.appsReadFrom(false, 100, 500, 5000, 1000), 1000)
                && near(ScreenTime.appsReadFrom(false, 100, 2000, -1, 1000), 1000));

        // where a read of the usage log really starts, and which days the reads cover (issue #131)
        double day = 86400, now = 100 * day;
        check("read start: within the week Android surely keeps", near(ScreenTime.readStart(now - 3 * day, Double.NaN, now), now - 3 * day));
        check("read start: older, from the oldest event Android returned", near(ScreenTime.readStart(now - 20 * day, now - 9 * day, now), now - 9 * day));
        check("read start: older and nothing returned, a week back", near(ScreenTime.readStart(now - 20 * day, Double.NaN, now), now - 7 * day));
        check("read start: a late oldest event still trusts the week", near(ScreenTime.readStart(now - 20 * day, now - day, now), now - 7 * day));
        check("read start: never before the asked start", near(ScreenTime.readStart(now - 8 * day, now - 9 * day, now), now - 8 * day));
        double today = 12.6 * day; // the moment of the coverage checks: day 12 is today
        List<double[]> rd = Arrays.asList(new double[] {1.5 * day, 3.6 * day}, new double[] {9.9 * day, 12.5 * day});
        check("coverage: no reads is before the data", ScreenTime.coverage(new ArrayList<double[]>(), 0, day, today) == ScreenTime.BEFORE_READS);
        check("coverage: a day before the first read", ScreenTime.coverage(rd, 0, day, today) == ScreenTime.BEFORE_READS
                && ScreenTime.coverage(rd, 0.5 * day, 1.5 * day, today) == ScreenTime.BEFORE_READS);
        check("coverage: the first day counts from the first read", ScreenTime.coverage(rd, day, 2 * day, today) == ScreenTime.COVERED);
        check("coverage: a day inside a read", ScreenTime.coverage(rd, 2 * day, 3 * day, today) == ScreenTime.COVERED);
        check("coverage: a day a gap starts in", ScreenTime.coverage(rd, 3 * day, 4 * day, today) == ScreenTime.READ_GAP);
        check("coverage: a day inside the gap", ScreenTime.coverage(rd, 6 * day, 7 * day, today) == ScreenTime.READ_GAP);
        check("coverage: a day the gap ends in", ScreenTime.coverage(rd, 9 * day, 10 * day, today) == ScreenTime.READ_GAP);
        check("coverage: after the gap", ScreenTime.coverage(rd, 10 * day, 11 * day, today) == ScreenTime.COVERED);
        check("coverage: the last day counts up to the last read", ScreenTime.coverage(rd, 12 * day, 13 * day, today) == ScreenTime.COVERED);
        check("coverage: a past day read only part way at its end is not known (access switched off that day)",
                ScreenTime.coverage(rd, 12 * day, 13 * day, 14 * day) == ScreenTime.AFTER_READS
                && ScreenTime.coverage(rd, 11 * day, 12 * day, 14 * day) == ScreenTime.COVERED);
        check("coverage: after the last read", ScreenTime.coverage(rd, 12.5 * day, 13.5 * day, today) == ScreenTime.AFTER_READS);
        check("coverage: touching reads leave no gap", ScreenTime.coverage(Arrays.asList(new double[] {0, 5}, new double[] {5, 20}), 1, 10, today) == ScreenTime.COVERED);
        check("coverage: a read open to the end (no reads stored yet)",
                ScreenTime.coverage(Arrays.asList(new double[] {5, Double.POSITIVE_INFINITY}), 1e6, 2e6, today) == ScreenTime.COVERED);

        // the Screen tab's reason for a day without screen time; a failed read has its own (issue #235)
        String fail = ScreenTime.unknownText(ScreenTime.UNREADABLE, false);
        check("unknown text: a failed read says so and where to look",
                fail.equals("Screen time could not be read for this day. Settings, Help and diagnostics, Data check and log shows why "
                        + "(its Log tab has the details).")
                && fail.equals(ScreenTime.unknownText(ScreenTime.UNREADABLE, true)));
        check("unknown text: before the data keeps its text",
                ScreenTime.unknownText(ScreenTime.BEFORE_READS, false).contains("about a week before you allowed usage access"));
        check("unknown text: a gap and not yet read keep theirs",
                ScreenTime.unknownText(ScreenTime.READ_GAP, false).contains("did not read it in time")
                && ScreenTime.unknownText(ScreenTime.AFTER_READS, false).contains("not read in full yet")
                && ScreenTime.unknownText(ScreenTime.AFTER_READS, true).startsWith("The demo has no screen time"));
        java.util.Set<String> reasons = new java.util.HashSet<String>();
        for (int k : new int[] {ScreenTime.BEFORE_READS, ScreenTime.READ_GAP, ScreenTime.AFTER_READS, ScreenTime.UNREADABLE}) {
            reasons.add(ScreenTime.unknownText(k, false));
            check("unknown text " + k + ": plain, no dashes", !ScreenTime.unknownText(k, false).contains(" - ") && !ScreenTime.unknownText(k, true).contains(" - "));
        }
        check("unknown text: each state has its own reason", reasons.size() == 4);

        // merging and storing
        List<double[]> m = ScreenTime.mergeRanges(Arrays.asList(new double[] {30, 40}, new double[] {0, 10}, new double[] {10, 20}, new double[] {35, 50}, new double[] {5, 5}));
        check("merge: touching and overlapping ranges join, empty ones go", m.size() == 2 && near(m.get(0)[1], 20) && near(m.get(1)[0], 30) && near(m.get(1)[1], 50));
        Map<String, List<double[]>> st = new LinkedHashMap<String, List<double[]>>();
        Map<String, List<double[]>> add = new LinkedHashMap<String, List<double[]>>();
        add.put("a", new ArrayList<double[]>(Arrays.asList(new double[] {0, 10})));
        ScreenTime.store(st, add);
        ScreenTime.store(st, add);
        check("store: the same range twice is one row", st.get("a").size() == 1);
        add.put("a", new ArrayList<double[]>(Arrays.asList(new double[] {5, 15}, new double[] {20, 30})));
        ScreenTime.store(st, add);
        check("store: an overlapping range extends the row", st.get("a").size() == 2 && near(st.get("a").get(0)[1], 15));

        // one app in front at a time: the app that came to the front later holds the overlap
        Map<String, List<double[]>> ov = new LinkedHashMap<String, List<double[]>>();
        ov.put("a", new ArrayList<double[]>(Arrays.asList(new double[] {0, 100})));
        ov.put("b", new ArrayList<double[]>(Arrays.asList(new double[] {40, 60})));
        ov.put("c", new ArrayList<double[]>(Arrays.asList(new double[] {200, 210})));
        ov.put("e", new ArrayList<double[]>(Arrays.asList(new double[] {200, 205})));
        ov.put("f", new ArrayList<double[]>(Arrays.asList(new double[] {300, 300})));
        Map<String, List<double[]>> one = ScreenTime.oneAtATime(ov);
        check("one at a time: the earlier app keeps both sides", one.get("a").size() == 2 && near(one.get("a").get(0)[1], 40) && near(one.get("a").get(1)[0], 60)
                && near(one.get("a").get(1)[1], 100));
        check("one at a time: the later app holds its time", one.get("b").size() == 1 && near(one.get("b").get(0)[0], 40) && near(one.get("b").get(0)[1], 60));
        check("one at a time: same start, the first name wins, the other keeps the rest", near(one.get("c").get(0)[0], 200) && near(one.get("c").get(0)[1], 210)
                && one.get("e") == null && one.get("f") == null);

        // randomized: logs read 1 to 4 extra times at random moments give exactly the rows of one read
        Random rnd = new Random(71);
        int differ = 0, longer = 0, overRaw = 0;
        String[] names = {"a", "b", "c", "d", "dialer"};
        for (int run = 0; run < 2000; run++) {
            List<Double> lt = new ArrayList<Double>();
            List<Integer> lty = new ArrayList<Integer>();
            List<String> lp = new ArrayList<String>();
            double x = 50;
            int sessions = 2 + rnd.nextInt(10);
            for (int i = 0; i < sessions; i++) {
                x += 5 + rnd.nextDouble() * 2000;
                logAdd(lt, lty, lp, x, 15, null);
                String cur = null;
                if (rnd.nextDouble() < 0.2) {
                    cur = "dialer"; // a call or the camera over the lock screen
                    x += 0.5 + rnd.nextDouble();
                    logAdd(lt, lty, lp, x, 1, cur);
                }
                if (rnd.nextDouble() < 0.9) {
                    x += 0.5 + rnd.nextDouble() * 5;
                    logAdd(lt, lty, lp, x, 18, null);
                }
                int n = rnd.nextInt(4);
                for (int j = 0; j < n; j++) {
                    String next = names[rnd.nextInt(names.length)];
                    x += 1 + rnd.nextDouble() * 300;
                    if (cur != null && !next.equals(cur) && rnd.nextDouble() < 0.8) {
                        logAdd(lt, lty, lp, x, 2, cur);
                        x += 0.01 + rnd.nextDouble() * 0.5;
                    }
                    logAdd(lt, lty, lp, x, rnd.nextDouble() < 0.1 ? 7 : 1, next);
                    if (lty.get(lty.size() - 1) == 1) cur = next;
                }
                x += 1 + rnd.nextDouble() * 600;
                if (rnd.nextDouble() < 0.03) {
                    logAdd(lt, lty, lp, x, 26, null); // the phone died or restarted
                    x += 20 + rnd.nextDouble() * 60;
                    logAdd(lt, lty, lp, x, 27, null);
                    continue;
                }
                if (cur != null && rnd.nextDouble() < 0.7) {
                    logAdd(lt, lty, lp, x, 2, cur);
                    x += 0.01 + rnd.nextDouble() * 0.5;
                }
                logAdd(lt, lty, lp, x, 16, null);
                x += 0.1 + rnd.nextDouble();
                logAdd(lt, lty, lp, x, 17, null);
            }
            double end = x + 100;
            double[] rt = new double[lt.size()];
            int[] rty = new int[rt.length];
            for (int i = 0; i < rt.length; i++) {
                rt[i] = lt.get(i);
                rty[i] = lty.get(i);
            }
            String[] rp = lp.toArray(new String[0]);
            int k = 1 + rnd.nextInt(4);
            double[] reads = new double[k + 1];
            for (int i = 0; i < k; i++) reads[i] = 40 + rnd.nextDouble() * (end - 40);
            Arrays.sort(reads, 0, k);
            reads[k] = end;
            Object[] r1 = readLog(rt, rty, rp, new double[] {end}, 0);
            Object[] rk = readLog(rt, rty, rp, reads, 0);
            Map<String, List<double[]>> one1 = (Map<String, List<double[]>>) r1[0], many = (Map<String, List<double[]>>) rk[0];
            List<double[]> screen = (List<double[]>) r1[1];
            boolean same = sameApps(one1, many);
            double raw = ScreenTime.minutes(screen, -1e12, 1e12), sum = 0;
            for (String p : names) {
                double v1 = appMin(one1, screen, p), vk = appMin(many, screen, p);
                same &= Math.abs(v1 - vk) < 1e-9;
                if (vk > raw + 1e-9) longer++;
            }
            for (Map.Entry<String, Double> e : ScreenTime.perApp(ScreenTime.oneAtATime(many), screen, -1e12, 1e12, 0)) sum += e.getValue();
            if (sum > raw + 1e-9) overRaw++;
            if (!same) differ++;
        }
        eq("random logs: reading 2 to 5 times gives the same app rows and minutes as once (of 2000)", differ, 0);
        eq("random logs: no app has more minutes than the phone use", longer, 0);
        eq("random logs: apps together never more than the phone use", overRaw, 0);

        // rows that add up: the demo case of the issue, 52.35 min shown as 54m when rounded one by one
        List<Map.Entry<String, Double>> per = new ArrayList<Map.Entry<String, Double>>();
        double[] mins = {15.6, 15.55, 14.6, 2.6, 2.6, 1.4};
        for (int i = 0; i < mins.length; i++) per.add(new AbstractMap.SimpleEntry<String, Double>("app" + i, mins[i]));
        List<ScreenTime.AppRow> rows = ScreenTime.appRows(per, new HashSet<String>(), 52.35);
        long shown = 0, single = 0;
        for (ScreenTime.AppRow r : rows) shown += r.shownMin;
        for (double v : mins) single += Math.round(v);
        // one by one the rows would round to 54
        check("rounding: together they add up to the total of 52", shown == 52 && rows.size() == 6);
        check("rounding: largest remainder", Arrays.equals(ScreenTime.roundTogether(new double[] {1.4, 1.4, 1.2}), new long[] {2, 1, 1}));
        check("rounding: a tie goes to the earlier row", Arrays.equals(ScreenTime.roundTogether(new double[] {0.5, 0.5}), new long[] {1, 0}));
        check("rounding: down to a smaller target", Arrays.equals(ScreenTime.roundTogether(new double[] {2.0, 1.1}, 2), new long[] {1, 1}));
        check("rounding: negative counts as 0", Arrays.equals(ScreenTime.roundTogether(new double[] {-3, 2.6}), new long[] {0, 3}));

        // the list: 8 apps of their own, small and further apps in Other apps, apps left out always shown
        per.clear();
        for (int i = 0; i < 10; i++) per.add(new AbstractMap.SimpleEntry<String, Double>("p" + i, 20.0 - i));
        per.add(new AbstractMap.SimpleEntry<String, Double>("small", 0.3));
        per.add(new AbstractMap.SimpleEntry<String, Double>("out", 0.2));
        Set<String> leftOut = new HashSet<String>(Arrays.asList("p1", "out"));
        double counted = 0;
        for (Map.Entry<String, Double> e : per) if (!leftOut.contains(e.getKey())) counted += e.getValue();
        counted += 3.27; // time with no app in front
        rows = ScreenTime.appRows(per, leftOut, counted, 19.2);
        int own = 0, outRows = 0;
        long countedShown = 0, outShown = 0;
        ScreenTime.AppRow other = null, none = null;
        for (ScreenTime.AppRow r : rows) {
            if (r.kind == ScreenTime.AppRow.APP && !r.excluded) own++;
            if (r.excluded) {
                outRows++;
                outShown += r.shownMin;
            } else countedShown += r.shownMin;
            if (r.kind == ScreenTime.AppRow.OTHER) other = r;
            if (r.kind == ScreenTime.AppRow.NO_APP) none = r;
        }
        check("list: the 8 most used apps counted get a row", own == 8);
        check("list: apps left out always get a row, even a tiny one", outRows == 2);
        check("list: Other apps holds the 9th app counted and the small one", other != null && other.apps == 2 && Math.abs(other.exactMin - 11.3) < 1e-9);
        check("list: no app in front is the rest of the total", none != null && Math.abs(none.exactMin - 3.27) < 1e-9);
        check("list: counted rows add up to the day's total", countedShown == Math.round(counted));
        check("list: rows left out add up to Not counted", outShown == 19);
        check("list: order is apps, Other apps, No app in front, then the ones left out", rows.get(8) == other && rows.get(9) == none && rows.get(10).excluded);
        check("list: an empty Other apps and no app in front are left out",
                ScreenTime.appRows(per.subList(0, 3), new HashSet<String>(), 20 + 19 + 18).size() == 3);
        // review of #84: no own row shows 0m, the cases of an empty list, counted rows never above the total
        List<Map.Entry<String, Double>> tiny = new ArrayList<Map.Entry<String, Double>>();
        tiny.add(new AbstractMap.SimpleEntry<String, Double>("a", 20 / 60.0));
        check("empty list: one app of 20 s rounds away", ScreenTime.appRows(tiny, new HashSet<String>(), 20 / 60.0).isEmpty());
        eq("empty list: says apps were in front for less than a minute", ScreenTime.emptyListText(tiny), "Apps were in front for less than a minute in all on this day.");
        eq("empty list: no app at all", ScreenTime.emptyListText(new ArrayList<Map.Entry<String, Double>>()), "No app was in front on this day.");
        List<Map.Entry<String, Double>> three = new ArrayList<Map.Entry<String, Double>>();
        for (String p : new String[] {"a", "b", "c"}) three.add(new AbstractMap.SimpleEntry<String, Double>(p, 0.6));
        List<ScreenTime.AppRow> r3 = ScreenTime.appRows(three, new HashSet<String>(), 1.8);
        boolean zero = false;
        long sum3 = 0;
        for (ScreenTime.AppRow r : r3) {
            zero |= r.kind == ScreenTime.AppRow.APP && r.shownMin == 0;
            sum3 += r.shownMin;
        }
        check("an app that rounds to 0m joins Other apps, no own row shows 0m", !zero && r3.size() == 2 && sum3 == 2);
        List<Map.Entry<String, Double>> over = new ArrayList<Map.Entry<String, Double>>();
        over.add(new AbstractMap.SimpleEntry<String, Double>("a", 10.0));
        over.add(new AbstractMap.SimpleEntry<String, Double>("b", 8.0));
        List<ScreenTime.AppRow> ro = ScreenTime.appRows(over, new HashSet<String>(), 15.0);
        check("old overlapping rows: counted rows capped at the total", ro.size() == 2 && ro.get(0).shownMin + ro.get(1).shownMin == 15
                && Math.abs(ro.get(0).exactMin + ro.get(1).exactMin - 15) < 1e-9);
        List<Map.Entry<String, Double>> full = new ArrayList<Map.Entry<String, Double>>();
        full.add(new AbstractMap.SimpleEntry<String, Double>("launcher", 17.15));
        full.add(new AbstractMap.SimpleEntry<String, Double>("b", 17.15));
        full.add(new AbstractMap.SimpleEntry<String, Double>("c", 17.2));
        List<ScreenTime.AppRow> rf = ScreenTime.appRows(full, new HashSet<String>(), 17.15 + 17.15 + 17.2);
        long sumF = 0;
        for (ScreenTime.AppRow r : rf) sumF += r.shownMin;
        check("apps cover the whole total: no row for no app in front, rows add up to the total", rf.size() == 3 && sumF == 52);
        List<Map.Entry<String, Double>> leftTiny = new ArrayList<Map.Entry<String, Double>>(tiny);
        List<ScreenTime.AppRow> rl = ScreenTime.appRows(leftTiny, new HashSet<String>(Arrays.asList("a")), 0, 20 / 60.0);
        check("an app left out keeps its row also under a minute", rl.size() == 1 && rl.get(0).excluded && rl.get(0).shownMin == 0);
        check("list: unknown total gives no row for no app in front", ScreenTime.appRows(per.subList(0, 1), new HashSet<String>(), Double.NaN).size() == 1);
    }

    static void logAdd(List<Double> t, List<Integer> ty, List<String> p, double at, int type, String pkg) {
        t.add(at);
        ty.add(type);
        p.add(pkg);
    }

    /** Screen score (1.15): amount against the goal, before bed and during the night. */
    static void screenScore() {
        check("score: half the goal is best", near(ScreenScore.amount(90, 180), 100));
        check("score: the goal gives 70", near(ScreenScore.amount(180, 180), 70));
        check("score: 1.5 times the goal", near(ScreenScore.amount(270, 180), 35));
        check("score: twice the goal gives 0", near(ScreenScore.amount(360, 180), 0) && near(ScreenScore.amount(999, 180), 0));
        check("score: no goal uses 3 hours", near(ScreenScore.amount(180, 0), 70));
        check("score: before bed", near(ScreenScore.bedtime(5), 100) && near(ScreenScore.bedtime(25), 50) && near(ScreenScore.bedtime(60), 0));
        check("score: night", near(ScreenScore.night(0.5), 100) && near(ScreenScore.night(20), 0) && near(ScreenScore.night(10), 100 * 10 / 19.0)); // straight from 100 at 1 min to 0 at 20
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
        // the start moves to 11000, then past the second use to 13000; the end stays (13000 is not after the start)
        check("screen: window used up by the phone", near(w3[0], 13000) && near(w3[1], 13200) && w3[1] - w3[0] < ScreenTime.MIN_WINDOW_S);
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

    /** Issue #72: the Body tab's Lowest, Average and Highest are the line's own points. */
    static void dayHeart() {
        double ds = 1_760_000_000L - 1_760_000_000L % 86400, de = ds + 86400;
        TS readings = new TS();
        double run = ds + 18 * 3600, calm = ds + 4 * 3600;
        for (double t = ds; t < de; t += 2) {
            if (t >= run && t < run + 600) readings.add(t, 130);       // a 10 minute run
            else if (t >= calm && t < calm + 900) readings.add(t, 50); // a calm quarter hour
            else readings.add(t, 60);
        }
        TS line = DayHeart.curve(readings, ds, de);
        DayHeart d = DayHeart.of(line, ds, de);
        double oldHigh = Stats.percentile(readings.values(), 0.98);
        // 300 of 43 200 readings are the run (0.7 %), so the 98th percentile is one of the 60s
        eq("day heart: the old 98th percentile cut the run off", oldHigh, 60.0);
        eq("day heart: Highest is the line's highest point", d.high, line.max());
        // the quarter hour from 18:00: 10 minutes at 130 and 5 at 60, readings every 2 s
        check("day heart: Highest shows the run", near(d.high, (300 * 130 + 150 * 60) / 450.0) && near(d.highAt, ds + 18 * 3600 + 450));
        check("day heart: no drawn point above Highest", d.high >= line.max() && d.points == line.size() && d.points == 96);
        eq("day heart: Lowest is the line's lowest point", d.low, line.min());
        check("day heart: Lowest is the calm quarter hour", near(d.low, 50) && near(d.lowAt, ds + 4 * 3600 + 450));
        check("day heart: Highest at the run's quarter hour", d.highAt == ds + 18 * 3600 + 450);
        eq("day heart: Average counts each quarter hour once", d.avg, line.mean());

        // only the points the chart draws: inside the day, with a value
        TS edges = new TS();
        edges.add(ds - 1, 200);
        edges.add(ds + 450, 70);
        edges.add(ds + 1350, Double.NaN);
        edges.add(ds + 2250, 80);
        edges.add(de + 1, 20);
        DayHeart e = DayHeart.of(edges, ds, de);
        check("day heart: points outside the day and without a value left out", e.points == 2 && e.low == 70 && e.high == 80 && e.avg == 75);
        DayHeart none = DayHeart.of(new TS(), ds, de);
        check("day heart: no points gives no numbers", none.points == 0 && Double.isNaN(none.low) && Double.isNaN(none.avg)
                && Double.isNaN(none.high) && Double.isNaN(none.lowAt) && Double.isNaN(DayHeart.of(null, ds, de).high));

        // a clock change: 25 and 23 hour days keep one point per quarter hour
        eq("day heart: 25 hour day has 100 quarter hours", DayHeart.maxPoints(ds, ds + 25 * 3600), 100);
        eq("day heart: 23 hour day has 92 quarter hours", DayHeart.maxPoints(ds, ds + 23 * 3600), 92);
        TS long25 = new TS();
        for (double t = ds; t < ds + 25 * 3600; t += 60) long25.add(t, 60);
        eq("day heart: 25 hour day line", DayHeart.curve(long25, ds, ds + 25 * 3600).size(), 100);

        // issue #97: one plausibility range for heart rate from every ring source
        check("heart rate range: 30 and 220 count", EventCodec.plausibleHr(30) && EventCodec.plausibleHr(220) && EventCodec.plausibleHr(52));
        check("heart rate range: 29, 221, 250 and 0 do not", !EventCodec.plausibleHr(29) && !EventCodec.plausibleHr(221)
                && !EventCodec.plausibleHr(250) && !EventCodec.plausibleHr(0) && !EventCodec.plausibleHr(Double.NaN));

        // issue #148: beat intervals share that range, 60000 / 220 to 60000 / 30 ms
        check("beat range: 280 ms (214 bpm), 300 ms and 2000 ms (30 bpm) count", EventCodec.plausibleIbi(280)
                && EventCodec.plausibleIbi(300) && EventCodec.plausibleIbi(2000) && EventCodec.plausibleIbi(273));
        check("beat range: 270 ms (222 bpm), 272 ms, 2001 ms, 0 and NaN do not", !EventCodec.plausibleIbi(270) && !EventCodec.plausibleIbi(272)
                && !EventCodec.plausibleIbi(2001) && !EventCodec.plausibleIbi(0) && !EventCodec.plausibleIbi(Double.NaN));
        // 280 ms = 0x118: low byte 0x18, high nibble 1 with validity 1
        int[] fast = RingClient.parseLiveHr(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x00, 0x02, 0x00, 0x00, 0x18, 0x11});
        check("live beat of 280 ms gives 214 bpm (#148)", fast != null && fast[0] == 214 && fast[1] == 280);
        // 273 ms = 0x111 is 219.8 bpm: rounded to 220 on every live path (stored batch, live indication, live HR)
        int[] edge = RingClient.parseLiveHr(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x00, 0x02, 0x00, 0x00, 0x11, 0x11});
        check("live beat of 273 ms shows 220 bpm (#148)", edge != null && edge[0] == 220 && edge[1] == 273);
        eq("live indication with a 273 ms beat shows 220 bpm (#148)", RingClient.parseLive(Packet.parse(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x01, 0x01, 0x00, 0x00, 0x11, 0x11})).bpm, 220);
        check("live beat of 270 ms is dropped (#148)", RingClient.parseLiveHr(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x00, 0x02, 0x00, 0x00, 0x0e, 0x11}) == null);
        eq("live indication with a 280 ms beat (#148)", RingClient.parseLive(Packet.parse(new byte[] {0x2f, 0x08, 0x28, 0x02, 0x01, 0x01, 0x00, 0x00, 0x18, 0x11})).bpm, 214);
        app.openring.core.analysis.LiveHrv fastHrv = new app.openring.core.analysis.LiveHrv(30), tooFast = new app.openring.core.analysis.LiveHrv(30);
        for (int i = 0; i < 12; i++) {
            fastHrv.add(i % 2 == 0 ? 280 : 285);
            tooFast.add(i % 2 == 0 ? 265 : 270);
        }
        check("live HRV keeps 280 ms beats (#148)", Math.abs(fastHrv.rmssd() - 5) < 1e-9);
        check("live HRV drops 270 ms beats (#148)", Double.isNaN(tooFast.rmssd()));
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
        eq("get event", Hex.encode(Protocol.getEvent(0, 8, -1)), "10090000000008ffffffff");
        eq("ext get event", Hex.encode(Protocol.extGetEvent(123400, 0xffff, 0)), "2f0c410008e2010000000000ffff");
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
        int[] bat = EventCodec.debugBattery(new byte[] {0x24, 95, 0x68, 0x10});
        eq("battery debug", bat[1], 4200);
        double[] temps = EventCodec.temps(new byte[] {(byte) 0x4c, 0x0d});
        check("temp", Math.abs(temps[0] - 34.04) < 1e-9);
    }

    /** One extended envelope packet (2f .. 43) holding 0x46 temperature events at these varints. */
    // the frame length is one byte, so the bundle body must stay at 252 bytes or less
    static Packet bundle(long... varints) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (long v : varints) {
            java.io.ByteArrayOutputStream vb = new java.io.ByteArrayOutputStream();
            do {
                int x = (int) (v & 0x7f);
                v >>>= 7;
                vb.write(v != 0 ? x | 0x80 : x);
            } while (v != 0);
            b.write(0);
            b.write(0x46);
            b.write(vb.size() + 2);
            b.write(vb.toByteArray(), 0, vb.size());
            b.write(0x98);
            b.write(0x0e);
        }
        byte[] body = b.toByteArray();
        byte[] f = new byte[5 + body.length];
        f[0] = 0x2f;
        f[1] = (byte) (3 + body.length);
        f[2] = 0x43;
        f[3] = (byte) body.length;
        f[4] = (byte) (body.length >> 8);
        System.arraycopy(body, 0, f, 5, body.length);
        return Packet.parse(f);
    }

    static String bundleDs(ExtEnvelopeParser parser, Packet p) {
        StringBuilder sb = new StringBuilder();
        for (Packet e : parser.push(p)) sb.append(sb.length() > 0 ? " " : "").append(RingEvent.fromPacket(e).ds);
        return sb.toString();
    }

    static void bundles() throws Exception {
        // issue #31: every varint after the first is a delta, however large
        ExtEnvelopeParser ep = new ExtEnvelopeParser();
        eq("bundle: delta just under 27.8 h", bundleDs(ep, bundle(500_000_000L, 99_999_900L, 1000)), "5000000 5999999 6000009");
        eq("bundle: no long pause counted", ep.longPauses, 0);
        eq("bundle: delta of 27.8 h stays a delta", bundleDs(ep, bundle(500_000_000L, 100_000_000L, 1000)), "5000000 6000000 6000010");
        eq("bundle: long pause counted", ep.longPauses, 1);
        ep = new ExtEnvelopeParser();
        eq("bundle: delta of 55.6 h stays a delta", bundleDs(ep, bundle(500_000_000L, 200_000_000L, 1000)), "5000000 7000000 7000010");
        eq("bundle: one long pause", ep.longPauses, 1);
        // a pause longer than the time since the ring started (10 h, then 30 h) is still a pause
        ep = new ExtEnvelopeParser();
        eq("bundle: pause longer than uptime", bundleDs(ep, bundle(36_000_000L, 108_000_000L)), "360000 1440000");
        // each envelope starts with an absolute time, even a small one after a reboot
        eq("bundle: small absolute start after reboot", bundleDs(ep, bundle(200, 1000)), "2 12");
        // events in the same decisecond still move forward by one
        eq("bundle: same decisecond bumps", bundleDs(ep, bundle(500_000_000L, 1, 1)), "5000000 5000001 5000002");
        eq("bundle: count over several envelopes", ep.longPauses, 1);
        eq("bundle: longest pause", ep.longestPauseMs, 108_000_000L);

        // the batch carries the count, and the drain writes it to the log
        Packet summary = Packet.parse(Hex.decode("2f0a42010000000000000000"));
        HistoryBatch hb = HistoryBatch.decode(Arrays.asList(bundle(500_000_000L, 200_000_000L), bundle(1_000_000_000L, 150_000_000L, 1), summary));
        eq("batch: long pauses summed", hb.longPauses, 2);
        eq("batch: long pauses kept by validate", HistoryBatch.validate(hb, 0).longPauses, 2);
        eq("batch: longest pause", HistoryBatch.validate(hb, 0).longestPauseMs, 200_000_000L);
        eq("log: several pauses", RingClient.longPauseText(2, 200_000_000L),
                "history: 2 pauses of more than 27 hours between stored events, kept as real time (longest 55.6 h)");

        Mock m = new Mock();
        m.on("280100", "290100");
        m.on(Hex.encode(Protocol.extGetEvent(0, 4096, 0)), Hex.encode(bundle(500_000_000L, 200_000_000L).encode()), "2f0a42020000000000000000");
        m.on(Hex.encode(Protocol.extGetEvent(7000001L * 100, 4096, 0)), "2f0a42000000000000000000");
        RingClient c = new RingClient(m).quiet(5);
        final List<String> log = new ArrayList<String>();
        c.setLog(new RingClient.Log() {
            public void log(String msg) {
                log.add(msg);
            }
        });
        RingClient.DrainResult r = c.drain(0, new RingClient.BatchSink() {
            public boolean onBatch(List<RingEvent> events, long next, long left, int total) {
                return true;
            }
        });
        eq("drain: events after a long pause", r.eventsSynced, 2);
        eq("drain: cursor after the long pause", r.nextCursor, 7000001L);
        check("drain: long pause logged", log.contains("history: 1 pause of more than 27 hours between stored events, kept as real time (longest 55.6 h)"));
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
            check("unterminated batch is refused", false);
        } catch (HistoryBatch.BatchException e) {
            // refused, as it should be
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

    /** Extended GetEvent request from ring counter {@code ds} (the request takes ms). */
    static String ext(long ds) {
        return Hex.encode(Protocol.extGetEvent(ds * 100, 4096, 0));
    }

    /** Extended batch summary: {@code bytesLeft} still on the ring, result code {@code rc}. */
    static String summary(long bytesLeft, int rc) {
        byte[] p = {0x2f, 0x0a, 0x42, 0x01, 0, 0, 0, 0, 0, 0, 0, (byte) rc};
        Protocol.Le.putU32(p, 6, bytesLeft);
        return Hex.encode(p);
    }

    /** Sink that keeps what it stored and the cursor it acknowledged; refuses from batch {@code failAt} on. */
    static final class Store implements RingClient.BatchSink {
        final int failAt;
        final List<Long> stored = new ArrayList<Long>();
        final List<String> seen = new ArrayList<String>();
        long saved;
        int calls;

        Store(long cursor, int failAt) {
            this.saved = cursor;
            this.failAt = failAt;
        }

        public boolean onBatch(List<RingEvent> events, long next, long left, int total) {
            calls++;
            seen.add(events.size() + " events, next " + next + ", left " + left);
            if (calls >= failAt) return false;
            for (RingEvent e : events) stored.add(e.ds);
            saved = next;
            return true;
        }
    }

    /** Runs a drain and returns the exception it threw, or null when it ended normally. */
    static Exception drainError(RingClient c, long cursor, RingClient.BatchSink sink) {
        try {
            c.drain(cursor, sink);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /** What {@code cursorMarkerPresent} answers: "true", "false", or the exception class and message. */
    static String probe(RingClient c, long cursor) {
        try {
            return String.valueOf(c.cursorMarkerPresent(cursor));
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The exception {@code cursorMarkerPresent} threw, or null when it answered true or false. */
    static Exception probeError(RingClient c, long cursor) {
        try {
            c.cursorMarkerPresent(cursor);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /** Issue #159: the drain paths that decide whether ring history is kept or lost. */
    static void drainGuards() throws Exception {
        // a) the sink fails to store the second batch: the drain stops, that batch is not acknowledged
        Mock m = new Mock();
        m.on("280100", "290100");
        m.on(ext(0), Hex.encode(bundle(100).encode()), summary(16, 0));
        m.on(ext(2), Hex.encode(bundle(500).encode()), summary(0, 0));
        m.on(ext(6), summary(0, 0));
        RingClient c = new RingClient(m).quiet(5);
        Store st = new Store(0, 2);
        Exception e = drainError(c, 0, st);
        check("drain a: failed store throws", e instanceof RingClient.RingException);
        eq("drain a: failed store message", e == null ? null : e.getMessage(), "storage failed; batch not acknowledged");
        eq("drain a: sink was offered the second batch", st.seen.toString(), "[1 events, next 2, left 16, 1 events, next 6, left 0]");
        eq("drain a: only the first batch is stored", st.stored.toString(), "[1]");
        eq("drain a: cursor stays after the stored batch", st.saved, 2L);
        check("drain a: no request past the batch that was not stored", !m.writes.contains(ext(6)));
        // the next sync starts from the acknowledged cursor and asks for the same position again
        m.writes.clear();
        Store retry = new Store(st.saved, Integer.MAX_VALUE);
        RingClient.DrainResult r = new RingClient(m).quiet(5).drain(retry.saved, retry);
        eq("drain a: retry asks for the same position", m.writes.indexOf(ext(2)), 1);
        eq("drain a: retry stores the batch that failed", retry.stored.toString(), "[5]");
        eq("drain a: retry cursor", r.nextCursor, 6L);
        // a failure on the very first batch acknowledges nothing
        m.writes.clear();
        Store first = new Store(0, 1);
        e = drainError(new RingClient(m).quiet(5), 0, first);
        check("drain a: first batch failed throws", e instanceof RingClient.RingException);
        eq("drain a: first batch failed keeps the cursor", first.saved, 0L);
        eq("drain a: first batch failed stops at once", m.writes.toString(), "[280100, " + ext(0) + "]");
        m.writes.clear();
        new RingClient(m).quiet(5).drain(first.saved, new Store(first.saved, Integer.MAX_VALUE));
        eq("drain a: after a failed first batch the next drain asks for 0 again", m.writes.indexOf(ext(0)), 1);

        // b) the ring reports bytes left but the batch is empty: an error, never "done"
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(0), summary(16, 0));
        e = drainError(new RingClient(m).quiet(5), 0, new Store(0, Integer.MAX_VALUE));
        check("drain b: empty batch with bytes left throws", e instanceof RingClient.RingException);
        check("drain b: names the bytes left", e != null && e.getMessage().contains("16 bytes"));
        // the same on legacy GetEvent, the path Ring 4 fw 2.6.7 uses
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(0), "2f020041");
        m.on(Hex.encode(Protocol.getEvent(0, 255, -1)), "1106000010000000");
        e = drainError(new RingClient(m).quiet(5), 0, new Store(0, Integer.MAX_VALUE));
        check("drain b: legacy empty batch with bytes left throws", e instanceof RingClient.RingException);
        check("drain b: legacy names the bytes left", e != null && e.getMessage().contains("16 bytes"));
        // an empty batch with nothing left is the normal end
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(0), summary(0, 0));
        r = new RingClient(m).quiet(5).drain(0, new Store(0, Integer.MAX_VALUE));
        eq("drain b: empty batch with nothing left ends", r.eventsSynced + " " + r.nextCursor, "0 0");

        // c) marker present: the event right before the cursor is still on the ring
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), Hex.encode(bundle(100).encode()), summary(0, 0));
        m.on(ext(2), summary(0, 0));
        eq("probe c: marker present", probe(new RingClient(m).quiet(5), 2), "true");
        check("probe c: asks for the event before the cursor", m.writes.contains(ext(1)));
        // the same on legacy GetEvent (Ring 4 fw 2.6.7 refuses the extended request)
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), "2f020041");
        m.on(Hex.encode(Protocol.getEvent(1, 255, -1)), "43080100000074657374", "1106000000000000");
        m.on(Hex.encode(Protocol.getEvent(2, 255, -1)), "1106000000000000");
        eq("probe c: legacy marker present", probe(new RingClient(m).quiet(5), 2), "true");

        // d) marker gone: an empty answer, or only events older than the marker (a replay)
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), summary(0, 0));
        eq("probe d: marker gone", probe(new RingClient(m).quiet(5), 2), "false");
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), Hex.encode(bundle(0).encode()), summary(0, 0));
        eq("probe d: only an older event is not the marker", probe(new RingClient(m).quiet(5), 2), "false");
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), "2f020041");
        m.on(Hex.encode(Protocol.getEvent(1, 255, -1)), "1106000000000000");
        eq("probe d: legacy marker gone", probe(new RingClient(m).quiet(5), 2), "false");

        // e) the ring rejects the cursor itself (result code 0xff)
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), summary(0, 0xff));
        eq("probe e: rejected cursor means gone", probe(new RingClient(m).quiet(5), 2), "false");

        // f) any other result code is an error, not an answer
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), summary(0, 0x05));
        e = probeError(new RingClient(m).quiet(5), 2);
        check("probe f: result code 0x05 throws", e instanceof RingClient.RingException);
        check("probe f: names the result code", e != null && e.getMessage().contains("0x05"));
        e = drainError(new RingClient(m).quiet(5), 1, new Store(1, Integer.MAX_VALUE));
        eq("drain f: result code kept", e instanceof HistoryBatch.BatchException ? ((HistoryBatch.BatchException) e).resultCode : null, 5);

        // g) the link is lost in the middle of the probe: an error, neither present nor gone
        m = new Mock();
        m.on("280100", "290100");
        m.on(ext(1), Hex.encode(bundle(100).encode()));
        check("probe g: batch without summary throws", probeError(new RingClient(m).quiet(5), 2) instanceof RingClient.RingException);
        Mock dropping = new Mock();
        dropping.on("280100", "290100");
        dropping.on(ext(1), Hex.encode(bundle(100).encode()));
        // passed on as it is or wrapped, as long as it is an error and not an answer
        check("probe g: lost link throws", probeError(new RingClient(new LosingLink(dropping, ext(1))).quiet(5), 2) instanceof IOException);

        // cursor 0 has nothing before it: present, without asking the ring
        m = new Mock();
        eq("probe: cursor 0 is present", probe(new RingClient(m).quiet(5), 0), "true");
        eq("probe: cursor 0 sends nothing", m.writes.size(), 0);

        // GattLink.open never leaves a half open link behind: any way out but the return closes it, an
        // unexpected RuntimeException from the Bluetooth stack too (nobody else holds the link to close it)
        String gatt = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("app/src/main/java/app/openring/ble/GattLink.java").toPath()), "UTF-8");
        int from = gatt.indexOf("public static GattLink open(");
        int to = from < 0 ? -1 : gatt.indexOf("private void connect(", from);
        check("GattLink.open found", from >= 0 && to > from);
        String open = from >= 0 && to > from ? gatt.substring(from, to) : "";
        int fin = open.indexOf("finally");
        check("GattLink.open closes the link in finally, whatever was thrown", fin >= 0 && open.indexOf("l.close()", fin) > fin, open);
        check("GattLink.open catches nothing it would not throw again", !open.contains("catch ("), open);
    }

    /** A link that is lost after {@code req}: once its frames are read, poll fails as {@code GattLink.poll} does. */
    static final class LosingLink implements RingClient.Link {
        final Mock m;
        final String req;
        boolean dead;

        LosingLink(Mock m, String req) {
            this.m = m;
            this.req = req;
        }

        public void write(byte[] data) {
            m.write(data);
            if (Hex.encode(data).equals(req)) dead = true;
        }

        public byte[] poll(long timeoutMs) throws IOException {
            byte[] f = m.poll(timeoutMs);
            if (f == null && dead) throw new IOException("ring disconnected");
            return f;
        }

        public void clearInbox() {
            m.clearInbox();
        }
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

    // ------------------------------------------------- issues #114, #154, #120: one day of activity

    static final java.time.ZoneId ZURICH = java.time.ZoneId.of("Europe/Zurich"), LORD_HOWE = java.time.ZoneId.of("Australia/Lord_Howe");

    static double at(String local, java.time.ZoneId z) {
        return java.time.LocalDateTime.parse(local).atZone(z).toEpochSecond();
    }

    /** Wellness.day the way Analyzer.analyzeDay calls it: the real day of {@code date} in {@code z}. */
    static Wellness.Day actDay(String date, java.time.ZoneId z, TS met, List<double[]> sleeps) {
        java.time.LocalDate d = java.time.LocalDate.parse(date);
        return Wellness.day(date, d.atStartOfDay(z).toEpochSecond(), d.plusDays(1).atStartOfDay(z).toEpochSecond(), z, met, sleeps, 70, 1600);
    }

    /** MET per minute from {@code from} to {@code to}: {@code base}, and {@code v} inside each [a, b) of {@code spans}. */
    static TS metRun(double from, double to, double base, double v, double... spans) {
        TS t = new TS();
        for (double m = from; m < to; m += 60) {
            double x = base;
            for (int k = 0; k + 1 < spans.length; k += 2) if (m >= spans[k] && m < spans[k + 1]) x = v;
            t.add(m, x);
        }
        return t;
    }

    /** The sum of every level and Not worn: each real minute in exactly one (issue #112). */
    static double levels(Wellness.Day w) {
        return w.highMin + w.mediumMin + w.lowMin + w.inactiveMin + w.restMin + w.nonWearMin;
    }

    /** Issue #112: the sleep counts as rest without activity values. Issue #155: Total burn of a day still running. */
    static void activityRestAndBurn() {
        String day = "2026-06-10";
        double ds = at(day + "T00:00:00", ZURICH), de = at("2026-06-11T00:00:00", ZURICH), wake = at(day + "T07:00:00", ZURICH);
        List<double[]> night = new ArrayList<double[]>();
        night.add(new double[] {ds - 2 * 3600, wake});
        // values from 07:00 to 23:00 only, as the demo sends: 07:00 to 08:00 a walk, the rest sitting
        TS awake = metRun(wake, at(day + "T23:00:00", ZURICH), 1.2, 4.0, wake, wake + 3600);
        Wellness.Day w = actDay(day, ZURICH, awake, night);
        eq("rest: a night without activity values counts as rest (#112)", w.restMin, 420.0);
        eq("rest: only 23:00 to midnight is not worn (#112)", w.nonWearMin, 60.0);
        eq("rest: every minute in one level or not worn (#112)", levels(w), 1440.0);
        eq("rest: minutes with a value stay 16 hours (#112, #44)", w.coveredMin, 960);
        // the same day with the rule before: nothing else changes
        Wellness.Day noSleep = actDay(day, ZURICH, awake, new ArrayList<double[]>());
        check("rest: active burn, steps, levels and the chart unchanged by the night (#112)", Math.abs(w.activeKcal - noSleep.activeKcal) < 1e-9
                && w.steps == noSleep.steps && w.mediumMin == noSleep.mediumMin && w.inactiveMin == noSleep.inactiveMin
                && w.longInactiveStreaks == noSleep.longInactiveStreaks && Double.isNaN(w.metProfile[0]) && Double.isNaN(w.metProfile[27])
                && Math.abs(w.avgMet - noSleep.avgMet) < 1e-9);
        eq("rest: without the night those minutes are not worn (#112)", noSleep.nonWearMin, 480.0);
        // a night with values (0.95 MET) gives the same rest and no active burn from it
        TS withNight = metRun(ds, at(day + "T23:00:00", ZURICH), 1.2, 4.0, wake, wake + 3600);
        TS nightVals = new TS();
        for (int i = 0; i < withNight.size(); i++) nightVals.add(withNight.t(i), withNight.t(i) < wake ? 0.95 : withNight.v(i));
        Wellness.Day wv = actDay(day, ZURICH, nightVals, night);
        check("rest: a night with values gives the same rest and active burn (#112)", wv.restMin == 420 && wv.nonWearMin == 60
                && Math.abs(wv.activeKcal - w.activeKcal) < 1e-9 && wv.coveredMin == 1380 && levels(wv) == 1440);
        // a nap without values in the afternoon is rest too, and the 25 hour day adds up to 1500
        List<double[]> naps = new ArrayList<double[]>(night);
        naps.add(new double[] {at(day + "T14:00:00", ZURICH), at(day + "T14:30:00", ZURICH)});
        TS holed = new TS();
        for (int i = 0; i < awake.size(); i++) if (awake.t(i) < at(day + "T14:00:00", ZURICH) || awake.t(i) >= at(day + "T14:30:00", ZURICH)) holed.add(awake.t(i), awake.v(i));
        Wellness.Day wn = actDay(day, ZURICH, holed, naps);
        check("rest: a nap without values counts as rest (#112)", wn.restMin == 450 && wn.nonWearMin == 60 && levels(wn) == 1440);
        double o25 = at("2026-10-25T00:00:00", ZURICH);
        List<double[]> n25 = new ArrayList<double[]>();
        n25.add(new double[] {o25 - 3600, at("2026-10-25T07:00:00", ZURICH)});
        Wellness.Day w25 = actDay("2026-10-25", ZURICH, metRun(at("2026-10-25T07:00:00", ZURICH), at("2026-10-26T00:00:00", ZURICH), 1.2, 1.2), n25);
        check("rest: 25 October, 8 real hours asleep, the day adds up to 1500 (#112, #114)", w25.restMin == 480 && w25.nonWearMin == 0 && levels(w25) == 1500);

        // #155: the resting part grows with the share of the day that has passed
        eq("resting burn: at midnight nothing yet (#155)", Wellness.restingKcal(1600, ds, de, ds), 0.0);
        eq("resting burn: at 06:00 a quarter (#155)", Wellness.restingKcal(1600, ds, de, ds + 6 * 3600), 400.0);
        eq("resting burn: at noon half (#155)", Wellness.restingKcal(1600, ds, de, ds + 12 * 3600), 800.0);
        eq("resting burn: after the day the whole day (#155)", Wellness.restingKcal(1600, ds, de, de), 1600.0);
        eq("resting burn: before the day 0 (#155)", Wellness.restingKcal(1600, ds, de, ds - 60), 0.0);
        eq("resting burn: without a time the whole day (#155)", Wellness.restingKcal(1600, ds, de, Double.NaN), 1600.0);
        eq("resting burn: 25 October at 12:00 by the clock, 13 of 25 real hours (#155, #114)",
                Wellness.restingKcal(1500, o25, at("2026-10-26T00:00:00", ZURICH), at("2026-10-25T12:00:00", ZURICH)), 780.0);
        double eight = at(day + "T08:00:00", ZURICH);
        Wellness.Day part = Wellness.day(day, ds, de, ZURICH, metRun(ds, eight, 1.2, 4.0, wake, eight), night, 70, 1560, eight);
        check("total burn at 08:00: active plus a third of the resting burn (#155)", Math.abs(part.restingKcal - 520) < 1e-9
                && Math.abs(part.totalKcal - (part.activeKcal + 520)) < 1e-9 && part.activeKcal > 100 && part.totalUntil == eight);
        Wellness.Day full = Wellness.day(day, ds, de, ZURICH, metRun(ds, eight, 1.2, 4.0, wake, eight), night, 70, 1560, de + 3600);
        check("total burn of a day that has ended: the whole resting burn, no 'so far' (#155)", full.restingKcal == 1560
                && Math.abs(full.totalKcal - (full.activeKcal + 1560)) < 1e-9 && Double.isNaN(full.totalUntil) && full.activeKcal == part.activeKcal);
        Wellness.Day old = actDay(day, ZURICH, awake, night);
        check("total burn: the call without a time counts the whole day, as before (#155)", old.restingKcal == 1600 && Double.isNaN(old.totalUntil));
        eq("total burn line, a day that has ended (#155)", app.openring.ui.Explain.totalBurn(Double.NaN, ZURICH), "Active burn plus what your body uses at rest");
        eq("total burn line, so far (#155)", app.openring.ui.Explain.totalBurn(at(day + "T08:07:20", ZURICH), ZURICH),
                "So far, up to 08:07 (active plus resting burn)");
    }

    static void activityDay() {
        List<double[]> none = new ArrayList<double[]>();
        // #114: every real minute counts once, 23, 23.5, 24, 24.5 and 25 hour days
        String[][] days = {{"2026-03-29", "Europe/Zurich", "1380"}, {"2026-10-04", "Australia/Lord_Howe", "1410"}, {"2026-06-10", "Europe/Zurich", "1440"},
            {"2026-04-05", "Australia/Lord_Howe", "1470"}, {"2026-10-25", "Europe/Zurich", "1500"}};
        for (String[] c : days) {
            java.time.ZoneId z = java.time.ZoneId.of(c[1]);
            java.time.LocalDate d = java.time.LocalDate.parse(c[0]);
            double a = d.minusDays(1).atStartOfDay(z).toEpochSecond(), b = d.plusDays(2).atStartOfDay(z).toEpochSecond();
            Wellness.Day w = actDay(c[0], z, metRun(a, b, 1.6, 1.6), none);
            eq("activity day: " + c[0] + " " + c[1] + " counts its real minutes (#114)", w.coveredMin, Integer.parseInt(c[2]));
            eq("activity day: " + c[0] + " real day length (#114)", w.dayMin, Integer.parseInt(c[2]));
        }
        // a walk late on the day the clocks go back belongs to that day
        double a = at("2026-10-24T00:00:00", ZURICH), b = at("2026-10-27T00:00:00", ZURICH);
        TS late = metRun(a, b, 1.6, 4.0, at("2026-10-25T23:15:00", ZURICH), at("2026-10-25T23:45:00", ZURICH));
        Wellness.Day o25 = actDay("2026-10-25", ZURICH, late, none), o26 = actDay("2026-10-26", ZURICH, late, none);
        check("activity day: a walk 23:15 to 23:45 on 25 October counts on 25 October (#114)", o25.mediumMin == 30 && o25.steps == 3150 && o26.mediumMin == 0);
        // a walk after midnight on 30 March counts once, on 30 March
        a = at("2026-03-28T00:00:00", ZURICH);
        b = at("2026-03-31T00:00:00", ZURICH);
        TS early = metRun(a, b, 1.6, 4.0, at("2026-03-30T00:15:00", ZURICH), at("2026-03-30T00:45:00", ZURICH));
        Wellness.Day s29 = actDay("2026-03-29", ZURICH, early, none), s30 = actDay("2026-03-30", ZURICH, early, none);
        check("activity day: a walk 00:15 to 00:45 on 30 March counts only on 30 March (#114)", s29.mediumMin == 0 && s30.mediumMin == 30 && s30.steps == 3150);
        check("activity day: the two days add up to the real minutes (#114)", s29.coveredMin + s30.coveredMin == 1380 + 1440);
        // the chart by clock time: a walk at 08:00 is in the 08:00 quarter hours on every day
        String[] dates = {"2026-03-29", "2026-06-10", "2026-10-25"};
        for (String date : dates) {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            double from = d.atStartOfDay(ZURICH).toEpochSecond(), to = d.plusDays(1).atStartOfDay(ZURICH).toEpochSecond();
            Wellness.Day w = actDay(date, ZURICH, metRun(from, to, 1.2, 4.0, at(date + "T08:00:00", ZURICH), at(date + "T08:30:00", ZURICH)), none);
            check("activity day: " + date + " walk at 08:00 drawn at 08:00 (#114)", Math.abs(w.metProfile[32] - 4) < 1e-9 && Math.abs(w.metProfile[33] - 4) < 1e-9
                    && Math.abs(w.metProfile[31] - 1.2) < 1e-9 && Math.abs(w.metProfile[34] - 1.2) < 1e-9);
        }
        // clocks forward: 02:00 to 03:00 has no quarter hour; back: both passes on the same ones
        Wellness.Day fw = actDay("2026-03-29", ZURICH, metRun(at("2026-03-29T00:00:00", ZURICH), at("2026-03-30T00:00:00", ZURICH), 1.2, 1.2), none);
        check("activity day: 29 March, 02:00 to 03:00 empty, 01:45 and 03:00 filled (#114)", Double.isNaN(fw.metProfile[8]) && Double.isNaN(fw.metProfile[11])
                && !Double.isNaN(fw.metProfile[7]) && !Double.isNaN(fw.metProfile[12]));
        double first = java.time.Instant.parse("2026-10-25T00:00:00Z").getEpochSecond(), second = java.time.Instant.parse("2026-10-25T01:00:00Z").getEpochSecond();
        Wellness.Day bk = actDay("2026-10-25", ZURICH, metRun(at("2026-10-25T00:00:00", ZURICH), at("2026-10-26T00:00:00", ZURICH), 1.2, 3.2, first, first + 3600), none);
        check("activity day: 25 October, 02:00 to 03:00 holds both passes, their average (#114)", Math.abs(bk.metProfile[8] - 2.2) < 1e-9
                && Math.abs(bk.metProfile[11] - 2.2) < 1e-9 && Math.abs(bk.metProfile[12] - 1.2) < 1e-9 && bk.mediumMin == 60);
        check("activity day: the second pass starts at 02:00 by the clock (#114)", Wellness.clockMin(second, 0, ZURICH) == 120 && Wellness.clockMin(first, 0, ZURICH) == 120);
        check("activity day: without a zone the clock counts from the start", Wellness.clockMin(1000 + 125 * 60, 1000, null) == 125 && Wellness.clockMin(1000 + 1500 * 60, 1000, null) == 1439);

        // #154: a stretch counts when 60 minutes or more of its sitting lie between 08:00 and 22:00
        String day = "2026-06-10";
        double ds = at(day + "T00:00:00", ZURICH), de = at("2026-06-11T00:00:00", ZURICH);
        String[][] sits = {
            {"A sit 22:30 to 24:00, day ends sitting", "22:30", "24:00", "0"},
            {"B sit 22:30 to 23:58", "22:30", "23:58", "0"},
            {"C sit 14:00 to 15:30", "14:00", "15:30", "1"},
            {"D sit 20:30 to 22:30, 90 min in the window", "20:30", "22:30", "1"},
            {"E sit 06:30 to 08:05, 5 min in the window", "06:30", "08:05", "0"},
            {"F sit 21:00 to 24:00, day ends sitting, 60 min in the window", "21:00", "24:00", "1"},
            {"G sit 21:01 to 24:00, 59 min in the window", "21:01", "24:00", "0"},
            {"H sit 07:00 to 09:00, 60 min in the window", "07:00", "09:00", "1"}};
        for (String[] c : sits) {
            double s0 = c[1].equals("24:00") ? de : at(day + "T" + c[1] + ":00", ZURICH), s1 = c[2].equals("24:00") ? de : at(day + "T" + c[2] + ":00", ZURICH);
            Wellness.Day w = actDay(day, ZURICH, metRun(ds, de, 1.6, 1.2, s0, s1), none);
            eq("long sitting " + c[0] + " (#154)", w.longInactiveStreaks, Integer.parseInt(c[3]));
        }
        // a stretch is ended by sleep and by a minute without data, like before
        TS gap = metRun(ds, de, 1.6, 1.2, at(day + "T14:00:00", ZURICH), at(day + "T16:00:00", ZURICH));
        TS cut = new TS();
        for (int i = 0; i < gap.size(); i++) if (Math.abs(gap.t(i) - at(day + "T15:00:00", ZURICH)) > 1) cut.add(gap.t(i), gap.v(i));
        eq("long sitting: a minute without data splits 14:00 to 16:00 into 60 and 59 minutes (#154)", actDay(day, ZURICH, cut, none).longInactiveStreaks, 1);
        List<double[]> nap = new ArrayList<double[]>();
        nap.add(new double[] {at(day + "T14:50:00", ZURICH), at(day + "T15:10:00", ZURICH)});
        eq("long sitting: a nap splits it into 50 and 50 minutes (#154)", actDay(day, ZURICH, gap, nap).longInactiveStreaks, 0);
        eq("long sitting: the same day without either has one (#154)", actDay(day, ZURICH, gap, none).longInactiveStreaks, 1);
        // the window by the clock on 25 October: sitting 07:00 to 09:00 local still has 60 minutes from 08:00
        eq("long sitting: 25 October 07:00 to 09:00 by the clock (#154, #114)", actDay("2026-10-25", ZURICH, metRun(at("2026-10-25T00:00:00", ZURICH),
                at("2026-10-26T00:00:00", ZURICH), 1.6, 1.2, at("2026-10-25T07:00:00", ZURICH), at("2026-10-25T09:00:00", ZURICH)), none).longInactiveStreaks, 1);
        eq("long sitting: 25 October 07:00 to 08:59, 59 minutes from 08:00 by the clock (#154, #114)", actDay("2026-10-25", ZURICH, metRun(at("2026-10-25T00:00:00", ZURICH),
                at("2026-10-26T00:00:00", ZURICH), 1.6, 1.2, at("2026-10-25T07:00:00", ZURICH), at("2026-10-25T08:59:00", ZURICH)), none).longInactiveStreaks, 0);

        // #120: a range for activity values
        check("MET range: 0, 1.2 and 25 count", EventCodec.plausibleMet(0) && EventCodec.plausibleMet(1.2) && EventCodec.plausibleMet(25));
        check("MET range: 25.2, 38.2, -0.1 and NaN do not", !EventCodec.plausibleMet(25.2) && !EventCodec.plausibleMet(38.2)
                && !EventCodec.plausibleMet(-0.1) && !EventCodec.plausibleMet(Double.NaN));
        check("MET range: the highest byte that counts is 0xbd (25.0)", EventCodec.plausibleMet(EventCodec.activityMet(b(0, 0xbd))[0])
                && !EventCodec.plausibleMet(EventCodec.activityMet(b(0, 0xbe))[0]));
        byte[] bad = new byte[61];
        for (int i = 1; i < bad.length; i++) bad[i] = (byte) 0xff;
        double[] raw = EventCodec.activityMet(bad);
        int kept = 0;
        for (double v : raw) if (EventCodec.plausibleMet(v)) kept++;
        check("MET range: an hour of 0xff decodes to 38.2 and none of it counts (#120)", raw.length == 60 && Math.abs(raw[0] - 38.2) < 1e-9 && kept == 0);
        // the day the way Analyzer builds it: the bad hour is left out, so it is missing, not 2604 kcal
        double h14 = at(day + "T14:00:00", ZURICH);
        TS kept14 = new TS(), control = new TS();
        for (double t = ds; t < de; t += 60) {
            if (t >= h14 && t < h14 + 3600) {
                double v = raw[(int) ((t - h14) / 60)];
                if (EventCodec.plausibleMet(v)) kept14.add(t, v);
                continue;
            }
            kept14.add(t, 1.6);
            control.add(t, 1.6);
        }
        Wellness.Day wb = actDay(day, ZURICH, kept14, none), wc = actDay(day, ZURICH, control, none);
        check("MET range: the day with the bad hour equals the day without that hour (#120)", wb.highMin == 0 && wb.coveredMin == 1380
                && wb.nonWearMin == 60 && Math.abs(wb.activeKcal - wc.activeKcal) < 1e-9 && wb.steps == wc.steps && Math.abs(wb.avgMet - 1.6) < 1e-9);

        // the explanation states the rule, and the line under the chart on clock change days
        eq("move every hour text (#154)", app.openring.ui.Explain.of("move_hourly"),
                "Times you sat still for an hour or more between 08:00 and 22:00. Only the minutes in that window count, sleep never does.");
        double z25 = at("2026-10-25T00:00:00", ZURICH), z26 = at("2026-10-26T00:00:00", ZURICH);
        eq("activity chart line, clocks back (#114)", ClockChange.activityText(ClockChange.onDay(ZURICH, z25, z26)),
                "The clocks went back one hour on this day (03:00 became 02:00), so 02:00 to 03:00 happened twice. The chart shows both at the same place, "
                        + "the numbers above count every real minute once.");
        eq("activity chart line, clocks forward (#114)", ClockChange.activityText(ClockChange.onDay(ZURICH, at("2026-03-29T00:00:00", ZURICH), at("2026-03-30T00:00:00", ZURICH))),
                "The clocks went forward one hour on this day (02:00 became 03:00), so 02:00 to 03:00 did not happen and the chart has nothing there.");
        check("activity chart line: none on other days", ClockChange.activityText(ClockChange.onDay(ZURICH, at("2026-06-10T00:00:00", ZURICH), at("2026-06-11T00:00:00", ZURICH))) == null
                && ClockChange.activityText(null) == null);
        check("activity chart line: Lord Howe's half hour", ClockChange.activityText(ClockChange.onDay(LORD_HOWE, at("2026-04-05T00:00:00", LORD_HOWE),
                at("2026-04-06T00:00:00", LORD_HOWE))).startsWith("The clocks went back 30 minutes on this day (02:00 became 01:30), so 01:30 to 02:00 happened twice."));
        // review of #202: only a day analysed with real minutes (stored day_min) gets the line
        check("activity chart line: a day stored before (no day_min) has none", ClockChange.activityText(ClockChange.onDay(ZURICH, z25, z26), 0) == null);
        eq("activity chart line: a day analysed now has it", ClockChange.activityText(ClockChange.onDay(ZURICH, z25, z26), 1500),
                ClockChange.activityText(ClockChange.onDay(ZURICH, z25, z26)));
        check("activity chart line: day_min alone gives no line on other days", ClockChange.activityText(null, 1440) == null);
    }

    /** Issue #44: the weekly parts of the activity score look at the worn days only. */
    static Night.Contributor part(Night.Score s, String key) {
        for (Night.Contributor c : s.contributors) if (c.key.equals(key)) return c;
        return null;
    }

    /** The rule before issue #240: yesterday's activity and the 7 against 28 day balance, every stored day counted. */
    static Scores.ReadinessInput oldReadinessActivity(double[] active, double[] target, boolean lastIsYesterday) {
        Scores.ReadinessInput in = new Scores.ReadinessInput();
        int n = active.length;
        if (lastIsYesterday && n > 0 && target[n - 1] > 0) in.prevDayActivity = active[n - 1] / target[n - 1];
        double[] a7 = Arrays.copyOfRange(active, Math.max(0, n - 7), n), a28 = Arrays.copyOfRange(active, Math.max(0, n - 28), n);
        if (Stats.countFinite(a7) >= 3 && Stats.countFinite(a28) >= 7 && Stats.mean(a28) > 0) in.activityBalance = Stats.mean(a7) / Stats.mean(a28);
        return in;
    }

    static double[] filled(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    /** Issue #240: a day without the ring is left out of readiness's activity parts, not counted as no activity. */
    static void readinessUnworn240() {
        eq("#240 worn day rule: 60 minutes", Scores.WORN_DAY_MIN, 60);
        check("#240 worn: 60 minutes counts, 59 and unknown do not", Scores.worn(60) && Scores.worn(900) && !Scores.worn(59.9) && !Scores.worn(0)
            && !Scores.worn(Double.NaN));
        // every day worn: exactly the rule before, for many made up histories (also missing values and short lists)
        java.util.Random r = new java.util.Random(240);
        int same = 0;
        for (int k = 0; k < 2000; k++) {
            int n = r.nextInt(62);
            double[] act = new double[n], tgt = new double[n], cov = new double[n];
            for (int i = 0; i < n; i++) {
                act[i] = r.nextInt(10) == 0 ? Double.NaN : r.nextInt(8) == 0 ? 0 : 50 + r.nextDouble() * 900;
                tgt[i] = r.nextInt(12) == 0 ? Double.NaN : r.nextInt(15) == 0 ? 0 : 300 + 10 * r.nextInt(20);
                cov[i] = 60 + r.nextInt(1380);
            }
            boolean y = r.nextBoolean();
            Scores.ReadinessInput mine = new Scores.ReadinessInput();
            boolean unworn = Scores.readinessActivity(mine, act, tgt, cov, y);
            Scores.ReadinessInput old = oldReadinessActivity(act, tgt, y);
            if (Double.compare(mine.prevDayActivity, old.prevDayActivity) == 0 && Double.compare(mine.activityBalance, old.activityBalance) == 0
                && unworn == !(y && n > 0)) same++;
        }
        eq("#240 every day worn: the same numbers as the rule before, 2000 histories", same, 2000);

        // 28 worn days of 400 kcal against a 400 goal, then yesterday on the charger (20 minutes, 0 kcal)
        double[] act = filled(29, 400), tgt = filled(29, 400), cov = filled(29, 900);
        act[28] = 0;
        cov[28] = 20;
        Scores.ReadinessInput in = new Scores.ReadinessInput();
        check("#240 yesterday unworn: reported", Scores.readinessActivity(in, act, tgt, cov, true));
        check("#240 yesterday unworn: previous day activity unknown", Double.isNaN(in.prevDayActivity), in.prevDayActivity);
        eq("#240 yesterday unworn: activity balance from the worn days only", in.activityBalance, 1.0);
        Scores.ReadinessInput before = oldReadinessActivity(act, tgt, true);
        eq("#240 the rule before: 55 for the charger day", Math.round(Scores.readiness(before).get("prev_activity").score), 55L);
        check("#240 the rule before: the balance pulled down", before.activityBalance < 0.9, before.activityBalance);
        // the readiness of such a day: the contributor is left out and the weights spread over the rest
        in.rhr = 55;
        in.sleepScore = 90;
        in.hrvRecent = 100;
        in.hrvBaseline = 100;
        Night.Score sc = Scores.readiness(in);
        check("#240 readiness without previous day activity", sc.get("prev_activity") == null && sc.get("activity_balance") != null, sc.contributors.size());
        double w = 0, s = 0;
        for (Night.Contributor c : sc.contributors) {
            w += c.weight;
            s += c.weight * c.score;
        }
        eq("#240 readiness: the other weights renormalised", (long) sc.value, Math.round(s / w));
        eq("#240 readiness: weights without previous day activity", w, 17.0 + 15 + 15 + 8);

        // yesterday worn just at the rule, missing, or not the day before
        cov[28] = 60;
        in = new Scores.ReadinessInput();
        check("#240 an hour of wear counts", !Scores.readinessActivity(in, act, tgt, cov, true) && in.prevDayActivity == 0, in.prevDayActivity);
        in = new Scores.ReadinessInput();
        check("#240 the newest stored day is not yesterday: reported, no previous day activity", Scores.readinessActivity(in, act, tgt, cov, false)
            && Double.isNaN(in.prevDayActivity), in.prevDayActivity);
        in = new Scores.ReadinessInput();
        check("#240 no days before: reported, both unknown", Scores.readinessActivity(in, new double[0], new double[0], new double[0], true)
            && Double.isNaN(in.prevDayActivity) && Double.isNaN(in.activityBalance), null);
        in = new Scores.ReadinessInput();
        check("#240 worn yesterday with no goal: not reported, unknown as before", !Scores.readinessActivity(in, new double[] {300}, new double[] {0},
            new double[] {600}, true) && Double.isNaN(in.prevDayActivity), in.prevDayActivity);

        // the minimum counts: 3 worn of the last 7 and 7 worn of the last 28
        act = filled(28, 400);
        tgt = filled(28, 400);
        cov = filled(28, 900);
        for (int i = 21; i < 28; i++) cov[i] = i < 26 ? 0 : 900; // last 7: 2 worn
        in = new Scores.ReadinessInput();
        Scores.readinessActivity(in, act, tgt, cov, true);
        check("#240 2 worn days of the last 7: no activity balance", Double.isNaN(in.activityBalance), in.activityBalance);
        cov[25] = 900; // 3 worn
        in = new Scores.ReadinessInput();
        Scores.readinessActivity(in, act, tgt, cov, true);
        eq("#240 3 worn days of the last 7: activity balance", in.activityBalance, 1.0);
        cov = filled(28, 0);
        for (int i = 22; i < 28; i++) cov[i] = 900; // 6 worn in 28
        in = new Scores.ReadinessInput();
        Scores.readinessActivity(in, act, tgt, cov, true);
        check("#240 6 worn days of the last 28: no activity balance", Double.isNaN(in.activityBalance) && !Double.isNaN(in.prevDayActivity), in.activityBalance);
        cov[3] = 900; // 7 worn in 28
        in = new Scores.ReadinessInput();
        Scores.readinessActivity(in, act, tgt, cov, true);
        eq("#240 7 worn days of the last 28: activity balance", in.activityBalance, 1.0);
        // an unworn day older than 28 days changes nothing
        act = filled(40, 400);
        tgt = filled(40, 400);
        cov = filled(40, 900);
        Scores.ReadinessInput all = new Scores.ReadinessInput();
        Scores.readinessActivity(all, act, tgt, cov, true);
        act[5] = 0;
        cov[5] = 0;
        in = new Scores.ReadinessInput();
        Scores.readinessActivity(in, act, tgt, cov, true);
        check("#240 an unworn day before the 28: the same numbers", in.activityBalance == all.activityBalance && in.prevDayActivity == all.prevDayActivity, null);

        // texts
        eq("#240 the note on the Readiness page", app.openring.ui.Explain.READINESS_UNWORN, "Previous day activity is left out: the ring was "
            + "on for less than an hour the day before, so there is no activity to compare with your goal.");
        check("#240 explanations name the rule", app.openring.ui.Explain.of("prev_activity").endsWith("A day with less than an hour of wear is left out.")
            && app.openring.ui.Explain.of("activity_balance").endsWith("Days with less than an hour of wear are left out."), null);
    }

    static Scores.ActivityInput activityIn(int days, double met, int train, double kcal) {
        Scores.ActivityInput in = new Scores.ActivityInput();
        in.inactiveMin = 7 * 60;
        in.longInactiveStreaks = 1;
        in.days7 = days;
        in.targetsMetFraction = met / days;
        in.trainingDays7 = train;
        in.trainingKcal7 = kcal;
        return in;
    }

    static void activityWeek() {
        // issue #128: the note above the contributors (ui/Explain has no Android imports, run_tests.sh compiles it)
        eq("activity note: 1 day", app.openring.ui.Explain.activityDays(1), "Only 1 day with the ring in the last 7 days (a day counts with "
                + "at least an hour of wear). Daily targets and training count once you have worn the ring on 3 of these 7 days.");
        eq("activity note: 4 days", app.openring.ui.Explain.activityDays(4), "Based on 4 days with the ring in the last 7 days (a day counts with "
                + "at least an hour of wear). Daily targets and training are steadier after a full week.");
        // a full week: exactly the numbers and texts from before the change
        Scores.ActivityInput full = activityIn(7, 5, 2, 800);
        eq("activity: default is a full week", new Scores.ActivityInput().days7, 7);
        Night.Score w = Scores.activity(full);
        double t = Stats.curve(5 / 7.0, new double[][] {{0, 20}, {0.15, 35}, {0.43, 62}, {0.71, 86}, {1, 100}});
        double f = Stats.curve(2, new double[][] {{0, 35}, {1, 60}, {2, 82}, {3, 100}});
        double v = Stats.curve(800, new double[][] {{0, 20}, {300, 42}, {700, 66}, {1200, 86}, {2000, 100}});
        double a = Stats.curve(7, new double[][] {{5, 100}, {7, 92}, {9, 72}, {11, 45}, {13, 20}}), m = 90;
        eq("activity week: targets unchanged", Math.round(part(w, "targets").score), Math.round(t));
        eq("activity week: targets text", part(w, "targets").detail, "5 of 7 days");
        eq("activity week: frequency unchanged", Math.round(part(w, "frequency").score), Math.round(f));
        eq("activity week: frequency text", part(w, "frequency").detail, "2 training days");
        eq("activity week: volume unchanged", Math.round(part(w, "volume").score), Math.round(v));
        eq("activity week: volume text", part(w, "volume").detail, "800 kcal this week");
        long all = Math.round((Math.round(a) * 17 + m * 33 + Math.round(t) * 24 + Math.round(f) * 12 + Math.round(v) * 14) / 100.0);
        eq("activity week: score unchanged", (long) w.value, all);

        // four worn days: share of them, training scaled up to a week
        Night.Score four = Scores.activity(activityIn(4, 3, 2, 800));
        eq("activity 4 days: targets of the worn days", Math.round(part(four, "targets").score),
            Math.round(Stats.curve(0.75, new double[][] {{0, 20}, {0.15, 35}, {0.43, 62}, {0.71, 86}, {1, 100}})));
        eq("activity 4 days: targets text", part(four, "targets").detail, "3 of 4 days");
        eq("activity 4 days: frequency scaled to a week", Math.round(part(four, "frequency").score), 100L); // 3.5 a week
        eq("activity 4 days: frequency text", part(four, "frequency").detail, "2 training days in 4 days");
        eq("activity 4 days: volume scaled to a week", Math.round(part(four, "volume").score),
            Math.round(Stats.curve(1400, new double[][] {{0, 20}, {300, 42}, {700, 66}, {1200, 86}, {2000, 100}})));
        eq("activity 4 days: volume text", part(four, "volume").detail, "800 kcal in 4 days");
        check("activity 4 days: higher than the same days counted against 7", four.value > Scores.activity(activityIn(7, 3, 2, 800)).value);

        // fewer than 3 worn days: the weekly parts are left out, the daily ones carry the score
        for (int n = 1; n < Scores.MIN_WEEK_DAYS; n++) {
            Night.Score few = Scores.activity(activityIn(n, n, 1, 400));
            eq("activity " + n + " days: two daily parts only", few.contributors.size(), 2);
            eq("activity " + n + " days: score from the daily parts", (long) few.value, Math.round((Math.round(a) * 17 + m * 33) / 50.0));
        }
        eq("activity: one training day is singular", part(Scores.activity(activityIn(3, 2, 1, 400)), "frequency").detail, "1 training day in 3 days");
        eq("activity 3 days: weekly parts join", Scores.activity(activityIn(3, 2, 1, 400)).contributors.size(), 5);
        eq("activity: day count above 7 is a week", part(Scores.activity(activityIn(9, 9, 2, 800)), "frequency").detail, "2 training days");
    }

    /** Ring sleep state every 5 minutes from {@code from} to {@code to} (hours after midnight UTC), awake in {@code awake} (hours), no samples in {@code off}. */
    static TS ringState(double from, double to, double[] awake, double[] off) {
        TS st = new TS();
        double day = 1_760_000_000L - 1_760_000_000L % 86400;
        for (double h = from; h < to; h += 5 / 60.0) {
            if (off != null && h >= off[0] && h < off[1]) continue;
            boolean aw = awake != null && h >= awake[0] && h < awake[1];
            st.add(day + h * 3600, aw ? 0 : 1);
        }
        return st;
    }

    static List<NightFinder.Period> findIn(TS st, NightFinder.LocalClock clock) {
        TS support = new TS();
        for (int i = 0; i < st.size(); i++) support.add(st.t(i), 1);
        return NightFinder.find(new ArrayList<double[]>(), new ArrayList<NightFinder.RingRun>(), st, support, clock);
    }

    static double awakeMin(NightFinder.Period p) {
        double s = 0;
        for (double[] a : p.awake) s += a[1] - a[0];
        return s / 60;
    }

    /** Issue #42: a long wake up in the middle of the night joins the two pieces, a nap does not. */
    static void longWake() {
        NightFinder.LocalClock utc = new NightFinder.LocalClock() {
            @Override
            public int minuteOfDay(double unix, double end) {
                return (int) (((long) unix % 86400) / 60);
            }
        };
        // night 23:00 to 07:00 (hours from midnight; -1 is 23:00 the evening before)
        List<NightFinder.Period> ps = findIn(ringState(-1, 7, new double[] {2.5, 3.75}, null), utc);
        eq("75 min wake up at 02:30 is one night", ps.size(), 1);
        check("75 min wake up returned as awake", ps.size() == 1 && Math.abs(awakeMin(ps.get(0)) - 75) <= 10);
        check("joined night keeps its edges", ps.size() == 1 && ps.get(0).end - ps.get(0).start <= 8 * 3600);
        ps = findIn(ringState(-1, 7, new double[] {2.5, 4.5}, null), utc);
        eq("120 min wake up is one night", ps.size(), 1);
        check("120 min wake up returned as awake", ps.size() == 1 && Math.abs(awakeMin(ps.get(0)) - 120) <= 10);
        eq("200 min wake up stays two sleeps", findIn(ringState(-1, 7, new double[] {2.5, 2.5 + 200 / 60.0}, null), utc).size(), 2);
        eq("without a clock the old rule splits it", findIn(ringState(-1, 7, new double[] {2.5, 3.75}, null), null).size(), 2);
        // sofa 19:00 to 21:00, awake 21:00 to 22:15, bed 22:15: the middle of the gap is 21:37
        eq("evening wake up keeps the sofa sleep apart", findIn(ringState(19, 30, new double[] {21, 22.25}, null), utc).size(), 2);
        // ring taken off 02:30 to 03:45: no sleep state in the gap, not a wake up
        eq("ring taken off is not joined", findIn(ringState(-1, 7, null, new double[] {2.5, 3.75}), utc).size(), 2);
        // a wake up ending 06:20 joins, one ending 06:50 does not
        eq("early morning wake up joins", findIn(ringState(-1, 8, new double[] {5, 6 + 20 / 60.0}, null), utc).size(), 1);
        eq("wake up past 06:30 does not join", findIn(ringState(-1, 9, new double[] {5.5, 6 + 50 / 60.0}, null), utc).size(), 2);
        // review of #42: sofa 20:30 to 22:30, awake to 00:30, bed: the sofa started before 21:00
        eq("sofa sleep from 20:30 stays apart", findIn(ringState(20.5, 31.5, new double[] {22.5, 24.5}, null), utc).size(), 2);
        // asleep 23:00 to 04:00, up 04:00 to 06:30, nap 06:30 to 08:00: the gap reaches 06:30
        eq("nap after getting up stays apart", findIn(ringState(-1, 8, new double[] {4, 6.5}, null), utc).size(), 2);
        // a bed at 21:30, asleep to 23:00, awake to 00:30 is one night
        eq("early bed with a long wake up joins", findIn(ringState(21.5, 31, new double[] {23, 24.5}, null), utc).size(), 1);
        // a staged ring piece 23:00 to 02:30 joined with an unstaged one 03:45 to 07:00: the ring's
        // codes keep their times, the gap is awake, the unstaged piece unknown
        {
            double day = 1_760_000_000L - 1_760_000_000L % 86400;
            double rs = day - 3600, re = day + 2.5 * 3600;
            int[] codes = new int[(int) ((re - rs) / 30)];
            for (int i = 0; i < codes.length; i++) codes[i] = rs + i * 30 < day ? 1 : 3; // deep before midnight, REM after
            List<NightFinder.RingRun> runs = new ArrayList<NightFinder.RingRun>();
            runs.add(new NightFinder.RingRun(rs, re, codes));
            TS st = ringState(-1, 7, new double[] {2.5, 3.75}, null);
            TS support = new TS();
            for (int i = 0; i < st.size(); i++) support.add(st.t(i), 1);
            List<NightFinder.Period> sp = NightFinder.find(new ArrayList<double[]>(), runs, st, support, utc);
            eq("staged and unstaged pieces join", sp.size(), 1);
            if (sp.size() == 1) {
                NightFinder.Period q = sp.get(0);
                int[] al = NightFinder.align(new NightFinder.RingRun(q.end - q.codes.length * 30.0, q.end, q.codes), q.start, q.end);
                int at2330 = al[(int) ((day - 1800 - q.start) / 30)], at0100 = al[(int) ((day + 3600 - q.start) / 30)];
                int at0300 = al[(int) ((day + 3 * 3600 - q.start) / 30)], at0500 = al[(int) ((day + 5 * 3600 - q.start) / 30)];
                check("stitched codes cover the whole night", Math.abs(q.codes.length * 30.0 - (q.end - q.start)) <= 30);
                check("ring codes keep their times", at2330 == 1 && at0100 == 3);
                check("gap is awake, unstaged piece unknown", at0300 == 4 && at0500 == 0);
            }
        }
        // a short doze inside the gap is not counted as awake
        List<double[]> asleep = new ArrayList<double[]>();
        asleep.add(new double[] {100, 200});
        List<double[]> parts = NightFinder.awakeParts(0, 300, asleep);
        check("awake parts leave sleep out", parts.size() == 2 && parts.get(0)[1] == 100 && parts.get(1)[0] == 200);
        // the analyser counts the ring's awake time even when heart rate and movement look calm
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        double day = 1_760_000_000L - 1_760_000_000L % 86400;
        in.start = day - 3600;
        in.end = day + 7 * 3600;
        for (double t = in.start; t < in.end; t += 300) {
            in.hr5.add(t, 55);
            in.hrv5.add(t, 60);
        }
        for (double t = in.start; t < in.end; t += 60) in.motion.add(t, 0);
        Night calm = NightAnalyzer.analyze(in);
        in.ringAwake.add(new double[] {day + 2.5 * 3600, day + 3.75 * 3600});
        Night woke = NightAnalyzer.analyze(in);
        // 02:30 to 03:45 is 75 minutes, on the 30 s steps from 23:00; the calm night has no awake time
        check("ring awake gap counts as awake", calm.awakeMin == 0 && woke.awakeMin == 75 && woke.awakenings == 1);
        check("ring awake gap lowers the score", Scores.sleep(woke, 0, 40).value < Scores.sleep(calm, 0, 40).value);
    }

    /** Ring stages from a digit string per 30 s step ("0" no data, 1 deep, 2 light, 3 REM, 4 awake), repeated. */
    static int[] digits(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i += 2) for (int k = 0; k < (Integer) parts[i + 1]; k++) sb.append(parts[i]);
        int[] out = new int[sb.length()];
        for (int i = 0; i < out.length; i++) out[i] = sb.charAt(i) - '0';
        return out;
    }

    static Night ringStaged(int[] stages) {
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = 1_760_000_000;
        in.end = in.start + stages.length * 30.0;
        in.ringStages = stages;
        return NightAnalyzer.analyze(in);
    }

    /** Issues #107, #118 and #110: efficiency with steps without stage data, no sleep, ring stages in a longer period. */
    static void nightNumbers() {
        // #107 lead: 20 min without data, 2 min awake, 200 min light
        Night lead = ringStaged(digits("0", 40, "4", 4, "2", 400));
        check("#107 lead: ring stages used", "ring".equals(lead.stageSource));
        check("#107 lead: efficiency is asleep over time in bed (90.1%)", Math.abs(lead.efficiency - 100.0 * 200 / 222) < 1e-6);
        check("#107 lead: latency counts the time without data (22 min)", near(lead.latencyMin, 22));
        double[] nd = StageShare.noData(lead.stages, lead.inBedMin);
        check("#107 lead: 20 min without data, all before falling asleep", near(nd[0], 20) && near(nd[1], 20));
        eq("#107 lead: the note", StageShare.noDataNote(nd[0], nd[1], true), "No data: there was no sleep stage for 20 min. It counts as time in bed, "
                + "not as asleep or awake, so it lowers efficiency. All of it was before you fell asleep, so latency includes it.");
        // #107 tail: 200 min light, then 100 min without data
        Night tail = ringStaged(digits("2", 400, "0", 200));
        check("#107 tail: efficiency 66.7%, not 100%", Math.abs(tail.efficiency - 100.0 * 200 / 300) < 1e-6);
        check("#107 tail: awake stays 0, the missing time is not made awake", near(tail.awakeMin, 0) && tail.awakenings == 0);
        nd = StageShare.noData(tail.stages, tail.inBedMin);
        check("#107 tail: 100 min without data, none before falling asleep", near(nd[0], 100) && near(nd[1], 0));
        check("#107 tail: stage minutes and no data add up to time in bed",
                near(tail.deepMin + tail.lightMin + tail.remMin + tail.awakeMin + nd[0], tail.inBedMin));
        eq("#107 tail: the note writes the time as the row does", StageShare.noDataNote(nd[0], nd[1], true), "No data: there was no sleep stage for 1h 40m. "
                + "It counts as time in bed, not as asleep or awake, so it lowers efficiency.");
        // review of #183: nights analysed before stored asleep over the time with data; the screens work it out again
        check("#107 shown efficiency of a new night is the stored one", Math.abs(StageShare.efficiency(tail.asleepMin, tail.inBedMin, tail.efficiency) - tail.efficiency) < 1e-9
                && Math.abs(StageShare.efficiency(lead.asleepMin, lead.inBedMin, lead.efficiency) - lead.efficiency) < 1e-9);
        check("#107 an older night's stored 100% shows as 66.7%", Math.abs(StageShare.efficiency(200, 300, 100) - 100.0 * 200 / 300) < 1e-9);
        check("#107 shown efficiency unknown without sleep or time in bed", Double.isNaN(StageShare.efficiency(0, 40, Double.NaN))
                && Double.isNaN(StageShare.efficiency(10, 0.5, 90)) && Double.isNaN(StageShare.efficiency(Double.NaN, 300, 90))
                && Double.isNaN(StageShare.efficiency(10, Double.NaN, 90)));
        check("#107 shown efficiency stays within 0 and 100", StageShare.efficiency(310, 300, 100) == 100 && StageShare.efficiency(-5, 300, 0) == 0);
        Night tailScore = tail;
        check("#107 tail: the efficiency contributor reads 67%", "67%".equals(part(Scores.sleep(tailScore, 0, 40), "efficiency").detail));
        // a night with no missing data keeps its efficiency
        Night full = ringStaged(digits("4", 20, "2", 400, "4", 20));
        check("#107 no missing data: efficiency as before", Math.abs(full.efficiency - 100.0 * 400 / 440) < 1e-6);
        check("#107 no missing data: no note", StageShare.noData(full.stages, full.inBedMin)[0] == 0
                && StageShare.noDataNote(0, 0, true) == null && StageShare.noDataNote(0.4, 0, true) == null);
        // part before falling asleep, part later
        nd = StageShare.noData(digits("0", 10, "2", 100, "0", 30, "2", 100), 120);
        check("#107 split: 20 min in all, 5 before falling asleep", near(nd[0], 20) && near(nd[1], 5));
        check("#107 split: the note names the part before", StageShare.noDataNote(nd[0], nd[1], true).endsWith(" 5 min of it was before you fell asleep, so latency includes that part."));
        check("#107 split: an hour or more before falling asleep as on the rows",
                StageShare.noDataNote(100, 70, true).endsWith(" 1h 10m of it was before you fell asleep, so latency includes that part."));
        nd = StageShare.noData(digits("0", 10, "4", 50), 30);
        check("#107 no sleep: no time before falling asleep", near(nd[0], 5) && near(nd[1], 0));
        eq("#107 no sleep: the note leaves out efficiency and latency (both unknown)", StageShare.noDataNote(nd[0], 5, false),
                "No data: there was no sleep stage for 5 min. It counts as time in bed, not as asleep or awake.");
        check("#107 no stages: nothing", StageShare.noData(null, 30)[0] == 0 && StageShare.noData(new int[0], 30)[0] == 0
                && StageShare.noData(new int[] {0}, Double.NaN)[0] == 0 && StageShare.noDataNote(Double.NaN, Double.NaN, true) == null);

        // #118: a 40 minute bedtime the ring marks awake all through
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = 1_760_000_000;
        in.end = in.start + 40 * 60;
        for (double t = in.start; t < in.end; t += 300) in.hr5.add(t, 68);
        for (double t = in.start; t < in.end; t += 60) {
            in.motion.add(t, 3);
            in.sleepState.add(t, 0);
        }
        Night sofa = NightAnalyzer.analyze(in);
        Night.Score ss = Scores.sleep(sofa, 3600, 35);
        check("#118 awake bedtime: nobody slept", sofa.asleepMin == 0 && Double.isNaN(sofa.wasoMin));
        check("#118 awake bedtime: no Restfulness", part(ss, "restfulness") == null);
        check("#118 awake bedtime: Total sleep still scored", part(ss, "total") != null && "0h 0m".equals(part(ss, "total").detail));
        Night allAwake = ringStaged(digits("4", 960));
        Night.Score sa = Scores.sleep(allAwake, 0, 35);
        check("#118 ring stages all awake: no Restfulness", allAwake.asleepMin == 0 && part(sa, "restfulness") == null);
        // left: Total sleep 10 (0 h, below 3 h) weight 35, REM 0 and deep 0 weight 10 each: 350 / 55 = 6.4
        eq("#118 ring stages all awake: score from the rest", sa.value, 6);
        // a real night with many wake ups keeps it, wake ups counted (CLAUDE.md: never hide real awakenings)
        Night waker = ringStaged(digits("2", 100, "4", 10, "2", 100, "4", 10, "2", 100, "4", 10, "2", 100));
        Night.Contributor wr = part(Scores.sleep(waker, 0, 35), "restfulness");
        // 3 wake ups of 10 steps (15 min awake): 94 for the wake time, 92 for 3 wake ups, no motion data: (94 + 92) / 2
        check("#118 night with wake ups keeps Restfulness", wr != null && wr.detail.equals("3 wake ups, 0h 15m awake") && near(wr.score, 93));

        // #110: a ring run of 8 h, deep for its first 5 minutes, then light, made longer by a bedtime period
        double r0 = 1_760_000_000L - 1_760_000_000L % 86400 - 3600, r1 = r0 + 8 * 3600;
        int[] codes = new int[960];
        for (int i = 0; i < codes.length; i++) codes[i] = i < 10 ? 1 : 2;
        String[] names = {"A (bedtime 10 min before to 30 min after)", "C (bedtime of 40 min, 20 min after the run)"};
        double[][] beds = {{r0 - 600, r1 + 1800}, {r1 + 1200, r1 + 3600}};
        for (int k = 0; k < 2; k++) {
            List<double[]> bedtimes = new ArrayList<double[]>();
            bedtimes.add(beds[k]);
            List<NightFinder.RingRun> runs = new ArrayList<NightFinder.RingRun>();
            runs.add(new NightFinder.RingRun(r0, r1, codes));
            List<NightFinder.Period> ps = NightFinder.find(bedtimes, runs, new TS(), new TS());
            eq("#110 " + names[k] + ": one period", ps.size(), 1);
            if (ps.size() != 1) continue;
            NightFinder.Period q = ps.get(0);
            check("#110 " + names[k] + ": period grew, staged, the run keeps its end", q.staged && q.end > r1 && near(q.codesEnd, r1));
            int[] al = NightFinder.align(q.ringRun(), q.start, q.end);
            int first = -1, lastCode = -1;
            for (int i = 0; i < al.length; i++) {
                if (al[i] == 1 && first < 0) first = i;
                if (al[i] != 0) lastCode = i;
            }
            check("#110 " + names[k] + ": deep at the run's minute 0", near(q.start + first * 30.0, r0));
            check("#110 " + names[k] + ": stages end with the run, the extra time has no data", near(q.start + (lastCode + 1) * 30.0, r1));
            Night n = NightAnalyzer.analyze(nightInput(q, al));
            check("#110 " + names[k] + ": ring stages used", "ring".equals(n.stageSource));
            check("#110 " + names[k] + ": 5 min deep, sleep starts at the run", near(n.deepMin, 5) && near(n.sleepStart, r0) && near(n.sleepEnd, r1));
        }
        // a staged run inside a bedtime period that then joins a second piece across a night wake (issue #42):
        // the stitched codes keep the run's own times
        {
            NightFinder.LocalClock utc = new NightFinder.LocalClock() {
                @Override
                public int minuteOfDay(double unix, double end) {
                    return (int) (((long) unix % 86400) / 60);
                }
            };
            double day = 1_760_000_000L - 1_760_000_000L % 86400;
            double rs = day - 3600, re = day + 2 * 3600; // ring run 23:00 to 02:00, bedtime to 02:30
            int[] c2 = new int[(int) ((re - rs) / 30)];
            for (int i = 0; i < c2.length; i++) c2[i] = rs + i * 30 < day ? 1 : 3;
            List<NightFinder.RingRun> runs = new ArrayList<NightFinder.RingRun>();
            runs.add(new NightFinder.RingRun(rs, re, c2));
            List<double[]> bedtimes = new ArrayList<double[]>();
            bedtimes.add(new double[] {rs, day + 2.5 * 3600});
            TS st = ringState(-1, 7, new double[] {2.5, 3.75}, null);
            TS support = new TS();
            for (int i = 0; i < st.size(); i++) support.add(st.t(i), 1);
            List<NightFinder.Period> sp = NightFinder.find(bedtimes, runs, st, support, utc);
            eq("#110 bedtime, run and night wake: one night", sp.size(), 1);
            if (sp.size() == 1) {
                NightFinder.Period q = sp.get(0);
                int[] al = NightFinder.align(q.ringRun(), q.start, q.end);
                check("#110 stitched: deep before midnight, REM after, at their real times",
                        al[(int) ((day - 1500 - q.start) / 30)] == 1 && al[(int) ((day + 900 - q.start) / 30)] == 3);
                check("#110 stitched: nothing from the ring after its run ended", al[(int) ((day + 2.25 * 3600 - q.start) / 30)] == 0);
                check("#110 stitched: the night wake is awake", al[(int) ((day + 3 * 3600 - q.start) / 30)] == 4);
            }
        }
        // a period made by hand without codesEnd keeps the old meaning (codes end at its end)
        NightFinder.Period plain = new NightFinder.Period(0, 600);
        check("#110 period without codes: no ring run", plain.ringRun() == null);
        plain.codes = new int[] {1, 2};
        check("#110 period without codesEnd: codes end at its end", plain.ringRun().end == 600 && plain.ringRun().start == 540);
    }

    /** Counts of each stage code (0 to 4) in {@code a}. */
    static int[] codeCounts(int[] a) {
        int[] n = new int[5];
        for (int x : a) if (x >= 0 && x < 5) n[x]++;
        return n;
    }

    /** {@code cells} steps of 30 s, all {@code code}. */
    static int[] fill(int cells, int code) {
        int[] a = new int[cells];
        java.util.Arrays.fill(a, code);
        return a;
    }

    /**
     * Issue #189: two ring stage runs with a break of an hour or less between them are one night, and both runs keep
     * their stages at their own times (before, the second run's stages were dropped). The break is awake where the
     * ring's own sleep state says awake and has no stage data otherwise; it is never made asleep.
     */
    static void stageRuns189() {
        double day = 1_760_000_000L - 1_760_000_000L % 86400;
        double a0 = day - 3600, a1 = day + 2 * 3600, b0 = day + 2.5 * 3600, b1 = day + 6 * 3600; // 23:00 to 02:00, 02:30 to 06:00
        // the issue's case: A 3 hours light, a break of 30 minutes, B 3.5 hours deep, no sleep state
        List<NightFinder.RingRun> runs = new ArrayList<NightFinder.RingRun>();
        runs.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        runs.add(new NightFinder.RingRun(b0, b1, fill(420, 1)));
        List<NightFinder.Period> ps = NightFinder.find(new ArrayList<double[]>(), runs, new TS(), new TS());
        eq("#189 two runs 30 min apart: one night", ps.size(), 1);
        if (ps.size() == 1) {
            NightFinder.Period q = ps.get(0);
            check("#189 the night runs from the first run's start to the second run's end", near(q.start, a0) && near(q.end, b1), q.start + " " + q.end);
            int[] al = NightFinder.align(q.ringRun(), q.start, q.end);
            int[] n = codeCounts(al);
            check("#189 both runs' stages kept: 3 h light and 3.5 h deep", n[2] == 360 && n[1] == 420, java.util.Arrays.toString(n));
            check("#189 without sleep state the break has no stage data (not awake, not asleep)", n[0] == 60 && n[4] == 0, java.util.Arrays.toString(n));
            check("#189 light at the first run's times, deep at the second's", al[(int) ((a0 + 60 - q.start) / 30)] == 2
                    && al[(int) ((a1 - 60 - q.start) / 30)] == 2 && al[(int) ((b0 + 60 - q.start) / 30)] == 1 && al[(int) ((b1 - 60 - q.start) / 30)] == 1);
            check("#189 the break has no stages from the ring", al[(int) ((day + 2.25 * 3600 - q.start) / 30)] == 0);
            check("#189 no wake up added without sleep state", q.awake.isEmpty(), q.awake.size());
            Night night = NightAnalyzer.analyze(nightInput(q, al));
            check("#189 the ring's stages are used for the whole night (they cover 93%)", "ring".equals(night.stageSource), night.stageSource);
            check("#189 180 min light, 210 min deep", near(night.lightMin, 180) && near(night.deepMin, 210), night.lightMin + " " + night.deepMin);
        }

        // with the ring's sleep state: asleep through both runs, awake 02:05 to 02:20 inside the break
        TS st = new TS();
        for (int m = -60; m < 6 * 60; m += 5) st.add(day + m * 60, m >= 125 && m < 140 ? 0 : 1);
        TS support = new TS();
        for (int i = 0; i < st.size(); i++) support.add(st.t(i), 1);
        List<NightFinder.Period> ws = NightFinder.find(new ArrayList<double[]>(), runs, st, support);
        eq("#189 with sleep state: one night", ws.size(), 1);
        if (ws.size() == 1) {
            NightFinder.Period q = ws.get(0);
            int[] al = NightFinder.align(q.ringRun(), q.start, q.end);
            int[] n = codeCounts(al);
            check("#189 with sleep state: both runs kept", n[2] == 360 && n[1] == 420, java.util.Arrays.toString(n));
            check("#189 with sleep state: the 15 min the ring says awake are awake", n[4] == 30
                    && al[(int) ((day + 2 * 3600 + 6 * 60 - q.start) / 30)] == 4 && al[(int) ((day + 2 * 3600 + 19 * 60 - q.start) / 30)] == 4, java.util.Arrays.toString(n));
            check("#189 with sleep state: the rest of the break is asleep without a stage, so it stays without data", n[0] == 30
                    && al[(int) ((day + 2 * 3600 + 60 - q.start) / 30)] == 0 && al[(int) ((day + 2 * 3600 + 25 * 60 - q.start) / 30)] == 0, java.util.Arrays.toString(n));
            check("#189 with sleep state: the wake up is the ring's own (kept by every detail level)", near(awakeMin(q), 15)
                    && near(q.awake.get(0)[0], day + 2 * 3600 + 5 * 60), awakeMin(q));
            NightAnalyzer.Input in = nightInput(q, al);
            in.ringAwake.addAll(q.awake);
            Night night = NightAnalyzer.analyze(in);
            check("#189 with sleep state: one wake up of 15 min counted", "ring".equals(night.stageSource) && night.awakenings == 1 && near(night.wasoMin, 15),
                    night.awakenings + " " + night.wasoMin);
        }

        // three runs, each 20 min apart: all three kept
        List<NightFinder.RingRun> three = new ArrayList<NightFinder.RingRun>();
        three.add(new NightFinder.RingRun(a0, a0 + 7200, fill(240, 2)));
        three.add(new NightFinder.RingRun(a0 + 8400, a0 + 15600, fill(240, 1)));
        three.add(new NightFinder.RingRun(a0 + 16800, a0 + 24000, fill(240, 3)));
        List<NightFinder.Period> ts = NightFinder.find(new ArrayList<double[]>(), three, new TS(), new TS());
        eq("#189 three runs 20 min apart: one night", ts.size(), 1);
        if (ts.size() == 1) {
            int[] n = codeCounts(NightFinder.align(ts.get(0).ringRun(), ts.get(0).start, ts.get(0).end));
            check("#189 three runs: every run's stages kept, the two breaks without data", n[2] == 240 && n[1] == 240 && n[3] == 240 && n[0] == 80,
                    java.util.Arrays.toString(n));
        }

        // a ring run inside a bedtime period, then a second run 30 min after it: both kept, the bedtime's edges without data
        List<double[]> beds = new ArrayList<double[]>();
        beds.add(new double[] {a0 - 600, a1 + 600});
        List<NightFinder.Period> bs = NightFinder.find(beds, runs, new TS(), new TS());
        eq("#189 bedtime, run and a second run: one night", bs.size(), 1);
        if (bs.size() == 1) {
            NightFinder.Period q = bs.get(0);
            int[] al = NightFinder.align(q.ringRun(), q.start, q.end);
            int[] n = codeCounts(al);
            check("#189 bedtime and two runs: both runs at their times (#110 kept)", n[2] == 360 && n[1] == 420 && al[(int) ((a0 + 60 - q.start) / 30)] == 2
                    && al[(int) ((a0 - 300 - q.start) / 30)] == 0 && near(q.start, a0 - 600) && near(q.end, b1), java.util.Arrays.toString(n));
        }

        // a run the ring sent again (overlapping the first one): unchanged, the first run's stages stay (issue #68 decides odd pages)
        List<NightFinder.RingRun> again = new ArrayList<NightFinder.RingRun>();
        again.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        again.add(new NightFinder.RingRun(a0 + 3600, a1 + 3600, fill(360, 1)));
        List<NightFinder.Period> os = NightFinder.find(new ArrayList<double[]>(), again, new TS(), new TS());
        if (os.size() == 1) {
            int[] n = codeCounts(NightFinder.align(os.get(0).ringRun(), os.get(0).start, os.get(0).end));
            check("#189 overlapping runs: unchanged, the first run's stages only", n[2] == 360 && n[1] == 0, java.util.Arrays.toString(n));
        } else {
            check("#189 overlapping runs: one night", false, os.size());
        }

        // the two runs with a break of over an hour stay two (outside the night window, no clock): unchanged
        List<NightFinder.RingRun> far = new ArrayList<NightFinder.RingRun>();
        far.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        far.add(new NightFinder.RingRun(a1 + 3700, a1 + 3700 + 12600, fill(420, 1)));
        eq("#189 a break of over an hour without a night wake: still two sleeps", NightFinder.find(new ArrayList<double[]>(), far, new TS(), new TS()).size(), 2);

        // the edges of the join: a break of exactly an hour is joined, a 20 s overlap is stitched, a 60 s overlap keeps the first run only
        List<NightFinder.RingRun> hour = new ArrayList<NightFinder.RingRun>();
        hour.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        hour.add(new NightFinder.RingRun(a1 + 3600, a1 + 3600 + 12600, fill(420, 1)));
        List<NightFinder.Period> hs = NightFinder.find(new ArrayList<double[]>(), hour, new TS(), new TS());
        eq("#189 a break of exactly an hour: one night", hs.size(), 1);
        if (hs.size() == 1) {
            int[] n = codeCounts(NightFinder.align(hs.get(0).ringRun(), hs.get(0).start, hs.get(0).end));
            check("#189 a break of exactly an hour: both runs kept, the hour without data", n[2] == 360 && n[1] == 420 && n[0] == 120,
                    java.util.Arrays.toString(n));
        }
        List<NightFinder.RingRun> touch = new ArrayList<NightFinder.RingRun>();
        touch.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        touch.add(new NightFinder.RingRun(a1 - 20, a1 - 20 + 12600, fill(420, 1)));
        List<NightFinder.Period> tc = NightFinder.find(new ArrayList<double[]>(), touch, new TS(), new TS());
        eq("#189 a 20 s overlap: one night", tc.size(), 1);
        if (tc.size() == 1) {
            int[] n = codeCounts(NightFinder.align(tc.get(0).ringRun(), tc.get(0).start, tc.get(0).end));
            check("#189 a 20 s overlap: stitched, both runs kept (the second wins the shared step)", n[1] == 420 && n[2] >= 359 && n[0] == 0,
                    java.util.Arrays.toString(n));
        }
        List<NightFinder.RingRun> over = new ArrayList<NightFinder.RingRun>();
        over.add(new NightFinder.RingRun(a0, a1, fill(360, 2)));
        over.add(new NightFinder.RingRun(a1 - 60, a1 - 60 + 12600, fill(420, 1)));
        List<NightFinder.Period> ov = NightFinder.find(new ArrayList<double[]>(), over, new TS(), new TS());
        eq("#189 a 60 s overlap: one night", ov.size(), 1);
        if (ov.size() == 1) {
            int[] n = codeCounts(NightFinder.align(ov.get(0).ringRun(), ov.get(0).start, ov.get(0).end));
            check("#189 a 60 s overlap: sent again, the first run's stages only (#68)", n[2] == 360 && n[1] == 0, java.util.Arrays.toString(n));
        }

        // the awake parts from the ring's sleep state: samples cover up to the next one, at most 5 minutes
        TS s2 = new TS();
        s2.add(100, 1);
        s2.add(400, 0);
        s2.add(700, 0);
        s2.add(1000, 1);
        s2.add(2000, 0);
        List<double[]> aw = NightFinder.stateAwake(s2, 0, 2100);
        check("#189 state awake: 400 to 1000 and 2000 to 2100", aw.size() == 2 && near(aw.get(0)[0], 400) && near(aw.get(0)[1], 1000)
                && near(aw.get(1)[0], 2000) && near(aw.get(1)[1], 2100), aw.size());
        check("#189 state awake: clipped to the break", NightFinder.stateAwake(s2, 500, 600).size() == 1 && near(NightFinder.stateAwake(s2, 500, 600).get(0)[0], 500));
        TS lone = new TS();
        lone.add(1000, 0);
        lone.add(2200, 1);
        List<double[]> la = NightFinder.stateAwake(lone, 0, 3000);
        check("#189 state awake: an awake sample with the next one 20 min later covers exactly 5 min", la.size() == 1 && near(la.get(0)[0], 1000)
                && near(la.get(0)[1], 1300), la.isEmpty() ? "none" : la.get(0)[0] + " " + la.get(0)[1]);
        TS last = new TS();
        last.add(1000, 0);
        List<double[]> ll = NightFinder.stateAwake(last, 0, 3000);
        check("#189 state awake: the last sample covers 5 min", ll.size() == 1 && near(ll.get(0)[1], 1300), ll.size());
        check("#189 state awake: none without state or in an empty break", NightFinder.stateAwake(new TS(), 0, 100).isEmpty() && NightFinder.stateAwake(s2, 600, 600).isEmpty());
    }

    static NightAnalyzer.Input nightInput(NightFinder.Period q, int[] stages) {
        NightAnalyzer.Input in = new NightAnalyzer.Input();
        in.start = q.start;
        in.end = q.end;
        in.ringStages = stages;
        return in;
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
        eq("anchor before 2015 ignored", TimeLine.build(new long[] {1}, new long[] {1}, new long[] {5}, new long[] {1_400_000_000L}, noPhone, now).get(0).anchors.size(), 0);
        eq("anchor 5 h ahead (a phone clock a little fast) kept", TimeLine.build(new long[] {1}, new long[] {1}, new long[] {5}, new long[] {now + 5 * 3600}, noPhone, now).get(0).anchors.size(), 1);
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
        // 9 pairs within 20 %: 20, -30, 20, -5, 25, -35, 20, -15 and -20 ms (sum of squares 4600); 800 to 1600 and 1600 to 810 are left out
        check("live rmssd ignores the doubled beat", near(r, Math.sqrt(4600 / 9.0)));
    }
    /** Issues #43 and #153: which task a stop reaches, when Live rejoins, which sync shows the final line. */
    static void ringTasks() {
        RingTasks.Owner o = new RingTasks.Owner();
        final int[] stops = {0};
        Runnable stop = new Runnable() {
            public void run() {
                stops[0]++;
            }
        };
        Object job = new Object(), mine = new Object();
        check("owner: a stop before the job holds the ring reaches nothing", !o.stopIf(job, stop) && stops[0] == 0);
        o.take(job);
        check("owner: the job holds the ring during its download", o.holds(job) && !o.holds(mine) && !o.holds(null));
        check("owner: a stop for the downloading job runs once", o.stopIf(job, stop) && stops[0] == 1);
        o.release(mine);
        check("owner: release by another task changes nothing", o.holds(job));
        o.release(job);
        check("owner: after its download the job holds nothing", !o.holds(job));
        // the job analyses, the person's sync (or Live, which never takes the owner) gets the ring
        o.take(mine);
        check("owner: a stop for the analysing job does not reach the person's sync (#43)", !o.stopIf(job, stop) && stops[0] == 1 && o.holds(mine));
        check("owner: a stop without a ticket reaches nothing", !o.stopIf(null, stop) && stops[0] == 1);
        o.release(mine);

        // #153: a stopped session still cleaning up (older token) is never rejoined
        int running = 1, token = 1;
        check("live: same session, same choice is rejoined", RingTasks.rejoinLive(running, token, false, false));
        token = 2; // stopLive of the old screen (a turned phone, or Done)
        check("live: a stopped session that is still cleaning up is not rejoined (#153)", !RingTasks.rejoinLive(running, token, false, false));
        check("live: no session running starts one", !RingTasks.rejoinLive(0, token, false, false) && !RingTasks.rejoinLive(0, 0, false, false));
        check("live: a blood oxygen switch starts a new session", !RingTasks.rejoinLive(1, 1, false, true) && !RingTasks.rejoinLive(1, 1, true, false));

        // #43 C: only the last sync shows its line
        eq("sync end: another sync runs, nothing shown", RingTasks.syncEndStatus(1, "Up to date", null), null);
        eq("sync end: the last sync shows its own line", RingTasks.syncEndStatus(0, "Sync stopped", "Up to date"), "Sync stopped");
        eq("sync end: a sync that never got the ring shows the line left behind", RingTasks.syncEndStatus(0, null, "Up to date"), "Up to date");
        eq("sync end: nothing to show", RingTasks.syncEndStatus(0, null, null), null);
        check("texts: busy and delete messages fit the rules", !Texts.SYNC_BUSY.contains(" - ") && !Texts.DELETE_BUSY.contains(" - ")
                && Texts.DELETE_BUSY.startsWith("Nothing was deleted") && Texts.DELETE_FAILED.startsWith("Nothing was deleted")
                && !Texts.LIVE_BUSY.contains(" - ") && !Texts.LIVE_BUSY.contains("syncing") && !Texts.SYNC_BUSY.contains("ring task"));
        // review of #277: the full delete texts are too long for a toast, the short ones for a closed Settings fit
        check("texts: delete texts go into the dialog", !Texts.fitsToast(Texts.DELETE_BUSY) && !Texts.fitsToast(Texts.DELETE_FAILED));
        for (String t : new String[] {Texts.DELETE_BUSY_TOAST, Texts.DELETE_FAILED_TOAST}) {
            check("texts: delete toast fits and says nothing was deleted: " + t, Texts.fitsToast(t) && t.startsWith("Nothing was deleted")
                    && !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
        }
    }

    /**
     * Issue #153 (left open by #217): the live session bookkeeping of RingManager. A stopped session that
     * is still cleaning up is never rejoined, and an older session ending never clears a newer one.
     */
    static void liveSessions() throws InterruptedException {
        LiveSessions s = new LiveSessions();
        eq("live sessions: nothing runs at first", s.running(), 0);
        int first = s.start(false);
        eq("live sessions: the first start gets token 1", first, 1);
        eq("live sessions: it is marked running", s.running(), 1);
        check("live sessions: a queued current session is not stale", !s.stale(first) && s.running() == 1);
        eq("live sessions: a second screen with the same choice joins (0)", s.start(false), 0);
        check("live sessions: joining keeps the session current", s.isCurrent(first) && s.running() == 1 && !s.spo2());

        // a turned phone or Done and back: the old screen stops, the new one starts while the old session cleans up
        s.stop();
        check("live sessions: a stopped session is no longer current", !s.isCurrent(first));
        eq("live sessions: a start after a stop gets a new session, not the cleaning up one", s.start(false), 3);
        eq("live sessions: the new session is marked running", s.running(), 3);
        s.ended(first);
        eq("live sessions: the old session ending does not clear the newer one", s.running(), 3);
        eq("live sessions: the newer session can be joined after the old one ended", s.start(false), 0);

        // a blood oxygen switch replaces the running session
        eq("live sessions: a blood oxygen switch starts a new session", s.start(true), 4);
        check("live sessions: the switch is remembered", s.spo2() && s.isCurrent(4) && !s.isCurrent(3));
        check("live sessions: the replaced session, queued, finds itself stale", s.stale(3));
        eq("live sessions: a stale session clears only itself", s.running(), 4);
        eq("live sessions: switching back starts again", s.start(false), 5);
        check("live sessions: switching back is remembered", !s.spo2());

        // stopped while queued: it clears its own mark, so the next screen starts a fresh session
        s.stop();
        check("live sessions: a session stopped while queued is stale", s.stale(5));
        eq("live sessions: and leaves nothing running", s.running(), 0);
        eq("live sessions: nothing running, a start gets a new session", s.start(false), 7);
        s.ended(7); // could not get the ring, or done
        eq("live sessions: a session that ends clears its mark", s.running(), 0);
        eq("live sessions: after it ended a start does not join it", s.start(false), 8);

        // two screens start at the same moment many times: tokens are distinct and the mark is the newest one
        final LiveSessions c = new LiveSessions();
        final List<Integer> tokens = Collections.synchronizedList(new ArrayList<Integer>());
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            final boolean spo2 = i % 2 == 0;
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    for (int k = 0; k < 20000; k++) {
                        int t = c.start(spo2);
                        if (t != 0) tokens.add(t);
                    }
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        // distinct tokens come from the counter itself and cannot fail, so only the mark is checked (issue #216)
        check("live sessions: concurrent starts made new sessions", !tokens.isEmpty());
        eq("live sessions: after concurrent starts the newest token is the one marked running", c.running(), Collections.max(tokens));
        check("live sessions: and it is the current one", c.isCurrent(c.running()));
    }

    /** Issue #185: demo mode never connects to the ring and never stores what a ring sends. */
    static void demoRing() {
        check("demo ring: real mode with the real file may use the ring", RingTasks.demoRefusal(false, false) == null);
        eq("demo ring: demo mode is refused", RingTasks.demoRefusal(true, true), SyncError.DEMO_RING);
        // switching over: the setting and the open file can differ for a moment, either one refuses
        eq("demo ring: demo turned on, the real file still open", RingTasks.demoRefusal(true, false), SyncError.DEMO_RING);
        eq("demo ring: demo turned off, the demo file still open", RingTasks.demoRefusal(false, true), SyncError.DEMO_RING);
        eq("demo ring: reads as demo", SyncError.kind(SyncError.DEMO_RING), SyncError.Kind.DEMO);
        eq("demo ring: a sync still says syncing is off", SyncError.plain(SyncError.DEMO_RING), "Syncing is off in demo mode.");
        String other = "The ring is not used in demo mode. Leave the demo in Settings to use your ring.";
        for (SyncError.Task t : new SyncError.Task[] {SyncError.Task.LIVE, SyncError.Task.ACTION, SyncError.Task.PAIR}) {
            eq("demo ring: plain sentence for " + t, SyncError.plain(SyncError.DEMO_RING, t), other);
            eq("demo ring: the sync's own text reads the same for " + t, SyncError.plain("Demo mode: syncing is off", t), other);
        }
        check("demo ring: not tried again", !SyncError.retryable(SyncError.DEMO_RING, true));
        check("demo ring: no failure notice", SyncError.failureNotice(false, SyncError.DEMO_RING) == null);
        String g = SyncError.game(SyncError.DEMO_RING);
        eq("demo ring: game text", g, "Ring motion stopped. The ring is not used in demo mode. Touch steering works meanwhile.");
        check("demo ring: game text fits", gameFits(g));
    }

    /** Issue #193: opening the app keeps a pending or running background job instead of replacing (and so stopping) it. */
    static void backgroundJob() {
        String svc = "app.openring/.sync.SyncJob";
        BackgroundJob.Spec want = BackgroundJob.wanted(svc);
        eq("background job: every 3 hours", want.interval, 3 * 60 * 60 * 1000L);
        eq("background job: within the last hour", want.flex, 60 * 60 * 1000L);
        check("background job: periodic, persisted, battery not low", want.periodic && want.persisted && want.batteryNotLow);
        BackgroundJob.Spec same = new BackgroundJob.Spec(svc, true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true);
        check("background job: an equal job is equal", same.equals(want) && same.hashCode() == want.hashCode());
        for (int i = 1; i <= 3; i++) {
            eq("background job: resume " + i + " with the same job pending or running keeps it", BackgroundJob.decide(true, true, same, want), BackgroundJob.Action.KEEP);
        }
        eq("background job: none pending schedules", BackgroundJob.decide(true, true, null, want), BackgroundJob.Action.SCHEDULE);
        BackgroundJob.Spec[] differ = {
            new BackgroundJob.Spec(svc, true, 2 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true),
            new BackgroundJob.Spec(svc, true, 3 * 60 * 60 * 1000L, 30 * 60 * 1000L, true, true),
            new BackgroundJob.Spec(svc, true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, false, true),
            new BackgroundJob.Spec(svc, true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, false),
            new BackgroundJob.Spec("other/.Job", true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true),
            new BackgroundJob.Spec(null, true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true),
            new BackgroundJob.Spec(svc, false, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true),
        };
        for (int i = 0; i < differ.length; i++) {
            check("background job: differing job " + i + " is not equal", !differ[i].equals(want) && !want.equals(differ[i]));
            eq("background job: differing job " + i + " is replaced", BackgroundJob.decide(true, true, differ[i], want), BackgroundJob.Action.SCHEDULE);
        }
        check("background job: null service equals only null service", differ[5].equals(new BackgroundJob.Spec(null, true, 3 * 60 * 60 * 1000L, 60 * 60 * 1000L, true, true)));
        check("background job: not equal to other things", !want.equals(null) && !want.equals(svc));
        eq("background job: off with a job pending cancels it", BackgroundJob.decide(false, true, same, want), BackgroundJob.Action.CANCEL);
        eq("background job: off with none pending does nothing", BackgroundJob.decide(false, true, null, want), BackgroundJob.Action.KEEP);
        eq("background job: pending unreadable, on schedules as before", BackgroundJob.decide(true, false, null, want), BackgroundJob.Action.SCHEDULE);
        eq("background job: pending unreadable, off cancels as before", BackgroundJob.decide(false, false, null, want), BackgroundJob.Action.CANCEL);
        check("background job: the log text names the period", want.toString().contains("every 180 min (flex 60)") && want.toString().contains("battery not low"));
        check("background job: a one off job says so", differ[6].toString().contains(" once") && !differ[2].toString().contains("persisted"));
    }

    /** Issues #185 and #187: Live measures only in front, and in demo mode only made up readings. */
    static void liveScreen() {
        LiveScreen l = new LiveScreen();
        check("live screen: nothing measures before it is in front", l.running() == null && l.restart() == null && l.pause() == null);
        eq("live screen: in front it starts the ring", l.resume(false), LiveScreen.Source.RING);
        eq("live screen: first start text", l.startText(), "Starting");
        check("live screen: a second resume starts nothing", l.resume(false) == null && l.running() == LiveScreen.Source.RING);
        eq("live screen: the blood oxygen switch restarts the ring in front", l.restart(), LiveScreen.Source.RING);
        eq("live screen: leaving the front stops the ring", l.pause(), LiveScreen.Source.RING);
        check("live screen: a second pause stops nothing", l.pause() == null && l.running() == null);
        check("live screen: the switch while hidden restarts nothing", l.restart() == null);
        eq("live screen: back in front starts the ring again", l.resume(false), LiveScreen.Source.RING);
        eq("live screen: and says why it starts again", l.startText(), LiveScreen.AGAIN);
        check("live screen: the text names the sync", LiveScreen.AGAIN.contains("sync can use the ring"));

        LiveScreen d = new LiveScreen();
        for (int i = 0; i < 3; i++) {
            eq("live screen: demo mode starts demo readings, round " + i, d.resume(true), LiveScreen.Source.DEMO);
            eq("live screen: demo text, round " + i, d.startText(), LiveScreen.DEMO_STATE);
            eq("live screen: the switch in demo restarts demo readings, round " + i, d.restart(), LiveScreen.Source.DEMO);
            eq("live screen: demo stops demo readings, round " + i, d.pause(), LiveScreen.Source.DEMO);
        }
        check("live screen: demo text says no ring is connected", LiveScreen.DEMO_STATE.contains("no ring is connected"));

        // the whole sequence with the session tokens (issue #187 on top of #153): Live in front, Home, back within seconds
        LiveScreen sc = new LiveScreen();
        LiveSessions s = new LiveSessions();
        check("live pause: in front", sc.resume(false) == LiveScreen.Source.RING);
        int first = s.start(false);
        eq("live pause: the first session", first, 1);
        check("live pause: Home stops the ring session", sc.pause() == LiveScreen.Source.RING);
        s.stop();
        check("live pause: the session is no longer current, so it leaves its loop and frees the ring", !s.isCurrent(first));
        check("live pause: back in front", sc.resume(false) == LiveScreen.Source.RING);
        int again = s.start(false);
        check("live pause: a new session is queued behind the one cleaning up, not joined", again != 0 && again != first && s.isCurrent(again));
        s.ended(first);
        eq("live pause: the old session ending keeps the new one", s.running(), again);
        // left while the new session still waited for the ring: it is stale and clears itself
        check("live pause: left again", sc.pause() == LiveScreen.Source.RING);
        s.stop();
        check("live pause: the queued session is stale and does not take the ring", s.stale(again) && s.running() == 0);
        // review of #244: a session paused while it waited for the ring (behind a sync) finds itself stale once it has
        // the lock, before it connects, and gives the ring back untouched
        check("live pause: back in front once more", sc.resume(false) == LiveScreen.Source.RING);
        int waiting = s.start(false);
        check("live pause: queued, current before it gets the ring", waiting != 0 && !s.stale(waiting) && s.running() == waiting);
        check("live pause: left while it waits", sc.pause() == LiveScreen.Source.RING);
        s.stop();
        check("live pause: stale right after the lock, nothing left running", s.stale(waiting) && s.running() == 0);
        // paused while connecting, checking the key or downloading: no longer current at the next step, so it ends
        // there without the live mode; the next start gets a fresh session that the ending one does not clear
        check("live pause: in front again", sc.resume(false) == LiveScreen.Source.RING);
        int connecting = s.start(false);
        check("live pause: connecting session is current", s.isCurrent(connecting) && !s.stale(connecting));
        sc.pause();
        s.stop();
        check("live pause: paused while connecting, the next step sees it", !s.isCurrent(connecting));
        sc.resume(false);
        int next = s.start(false);
        s.ended(connecting);
        check("live pause: the early end keeps the next session", next != 0 && next != connecting && s.running() == next && s.isCurrent(next));
    }

    /** Issue #185: the made up Live readings of demo mode. */
    static void demoLive() {
        DemoLive a = new DemoLive(185), b = new DemoLive(185);
        List<Integer> beats = new ArrayList<Integer>();
        double sum = 0;
        boolean same = true, plausible = true, spo2Early = false, spo2Range = true, tempRange = true;
        int spo2Count = 0;
        for (int k = 1; k <= 30; k++) {
            double t = k * DemoLive.STEP_S;
            DemoLive.Batch x = a.next(t, true), y = b.next(t, true);
            same &= Arrays.equals(x.beats, y.beats) && Arrays.equals(x.spo2, y.spo2) && x.temp == y.temp;
            for (int v : x.beats) {
                beats.add(v);
                sum += v / 1000.0;
                plausible &= EventCodec.plausibleIbi(v);
            }
            if (t <= DemoLive.SPO2_AFTER_S) spo2Early |= x.spo2.length > 0;
            for (int v : x.spo2) spo2Range &= v >= 95 && v <= 99;
            spo2Count += x.spo2.length;
            tempRange &= x.temp >= 33.5 && x.temp <= 34;
        }
        check("demo live: the same seed gives the same readings", same);
        check("demo live: every beat is a plausible beat", plausible && !beats.isEmpty());
        // beats follow each other without gaps: together they reach the time asked, by less than one beat more
        check("demo live: beats cover the 120 s asked", sum >= 120 && sum < 120 + 1.3);
        LiveHrv h = new LiveHrv(40);
        for (int v : beats) h.add(v);
        double r = h.rmssd();
        check("demo live: HRV through LiveHrv is 30 to 60 ms (" + r + ")", r >= 30 && r <= 60);
        double bpm = 60.0 * beats.size() / sum;
        check("demo live: heart rate 55 to 72 bpm (" + bpm + ")", bpm >= 55 && bpm <= 72);
        check("demo live: no blood oxygen in the first 20 s", !spo2Early);
        eq("demo live: then one blood oxygen value a second", spo2Count, 100);
        check("demo live: blood oxygen 95 to 99 %", spo2Range);
        check("demo live: temperature 33.5 to 34 C", tempRange);
        check("demo live: nothing new for the same time", a.next(120, true).beats.length == 0 && a.next(120, true).spo2.length == 0);

        // without the switch no blood oxygen; switched on later, it waits its 20 s again
        DemoLive c = new DemoLive(7);
        int off = c.next(40, false).spo2.length;
        int soon = c.next(56, true).spo2.length;
        int later = c.next(64, true).spo2.length;
        check("demo live: no blood oxygen without the switch", off == 0);
        check("demo live: switched on at 40 s, nothing before 60 s", soon == 0);
        eq("demo live: switched on at 40 s, values from 60 s", later, 4);

        // paused (screen not in front) from 64 to 300 s: that time has no readings, the beats go on after it
        c.skipTo(300);
        DemoLive.Batch p = c.next(304, true);
        double span = 0;
        for (int v : p.beats) span += v / 1000.0;
        check("demo live: after a pause only the new seconds have beats", span >= 4 && span < 4 + 1.3);
        check("demo live: after a pause blood oxygen needs its still hand again", p.spo2.length == 0);
    }

    /** Issue #152: always on heart rate never becomes a beat interval, so it never feeds live HRV. */
    static void liveFeed() {
        LiveFeed f = new LiveFeed();
        List<RingEvent> ev = new ArrayList<RingEvent>();
        // green IBI: 800 ms clean, 812 ms clean, 800 ms quality 2, 200 ms clean (too fast to be a beat)
        ev.add(new RingEvent(EventCodec.GREEN_IBI_QUALITY, 10, b(100, 8, 101, 12, 100, 2 << 3, 25, 8)));
        // always on heart rate: 70, 72 bpm and an impossible 250
        ev.add(new RingEvent(EventCodec.AOHR_EVENT, 11, b(0, 0, 3, 70, 1, 72, 1, 250, 1)));
        ev.add(new RingEvent(EventCodec.TEMP_EVENT, 12, b(0x2c, 0x0d)));
        ev.add(new RingEvent(EventCodec.SPO2_EVENT, 13, b(0, 97, 60, 98, 0xff)));
        f.addAll(ev);
        eq("live feed: only clean green IBI beats are beats", f.beats, Arrays.asList(800, 812));
        eq("live feed: always on heart rate stays heart rate", f.heartRates, Arrays.asList(70, 72));
        check("live feed: temperature and blood oxygen", Math.abs(f.temp - 33.72) < 1e-9 && f.spo2.equals(Arrays.asList(97, 98)) && f.lastSpo2 == 98);
        f.clear();
        check("live feed: a new refresh starts empty, the newest temperature stays", f.beats.isEmpty() && f.heartRates.isEmpty()
                && f.spo2.isEmpty() && Math.abs(f.temp - 33.72) < 1e-9);
        // heart rate only: HRV gets nothing, the screen says why
        LiveHrv h = new LiveHrv(40);
        for (int i = 0; i < 30; i++) f.add(new RingEvent(EventCodec.AOHR_EVENT, 20 + i, b(0, 0, 2, 70 + i % 3, 1, 71, 1)));
        for (int x : f.beats) h.add(x);
        check("live HRV: heart rate values alone give no HRV (#152)", f.heartRates.size() == 60 && h.size() == 0 && Double.isNaN(h.rmssd()));
        String only = LiveHrv.note(h.size(), h.cleanPairs(), f.heartRates.size(), h.rmssd());
        check("live HRV note: heart rate only says HRV needs single heartbeats", only.startsWith("HRV not available yet") && only.contains("single heartbeats"));
        check("live HRV note: nothing yet", LiveHrv.note(0, 0, 0, Double.NaN).equals("HRV appears once the ring sends single heartbeats."));
        for (int i = 0; i < 5; i++) h.add(800 + 10 * (i % 2));
        eq("live HRV note: a few beats", LiveHrv.note(h.size(), h.cleanPairs(), 60, h.rmssd()), "HRV not available yet: it needs at least 9 clean heartbeats, 5 so far.");
        for (int i = 0; i < 5; i++) h.add(800 + 10 * (i % 2));
        check("live HRV: 10 clean beats give a value", Math.abs(h.rmssd() - Math.sqrt(800.0 / 9)) < 1e-9); // 8 pairs of 10 ms, one of 0
        eq("live HRV note: with a value", LiveHrv.note(h.size(), h.cleanPairs(), 60, h.rmssd()), "HRV: the typical change from one heartbeat to the next, from the last 10 heartbeats.");
        for (String t : new String[] {only, LiveHrv.note(0, 0, 0, Double.NaN), LiveHrv.note(3, 2, 0, Double.NaN), LiveHrv.note(40, 39, 0, 20), LiveHrv.note(20, 0, 0, Double.NaN)}) {
            check("live HRV note without dashes: " + t, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014"));
        }
        check("live HRV: needs 9 beats as the note says", LiveHrv.MIN_BEATS == 9);
        // review of PR #174: 20 uneven beats (600 and 900 ms alternate, every jump over 20 %) give no value,
        // and the line says they jump too much instead of "needs 9, 20 so far"
        LiveHrv uneven = new LiveHrv(40);
        for (int i = 0; i < 20; i++) uneven.add(i % 2 == 0 ? 600 : 900);
        String jumpy = LiveHrv.note(uneven.size(), uneven.cleanPairs(), 0, uneven.rmssd());
        check("live HRV: alternating 600 and 900 ms give no value", Double.isNaN(uneven.rmssd()) && uneven.cleanPairs() == 0 && uneven.size() == 20);
        eq("live HRV note: enough beats but too uneven", jumpy, "HRV not available yet: the heartbeats so far jump too much to count. Keep your hand still.");
        LiveHrv few = new LiveHrv(40);
        for (int x : new int[] {800, 810, 1200, 805}) few.add(x);
        eq("live HRV note: a few beats count only the clean ones", LiveHrv.note(few.size(), few.cleanPairs(), 0, few.rmssd()),
                "HRV not available yet: it needs at least 9 clean heartbeats, 2 so far.");
        LiveHrv eight = new LiveHrv(40);
        for (int i = 0; i < LiveHrv.MIN_BEATS - 1; i++) eight.add(800 + 10 * (i % 2));
        check("live HRV: 8 beats are not enough, 9 are", Double.isNaN(eight.rmssd()) && addOne(eight));
    }

    static boolean addOne(LiveHrv h) {
        h.add(800);
        return !Double.isNaN(h.rmssd());
    }

    static void dialMath() {
        // issue #46: the screen time clock places moments by their clock time
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        for (String day : new String[] {"2026-03-29", "2026-10-25", "2026-10-14"}) {
            java.time.LocalDate d = java.time.LocalDate.parse(day);
            double from = d.atStartOfDay(z).toEpochSecond(), to = d.plusDays(1).atStartOfDay(z).toEpochSecond();
            check("dial " + day + ": 03:00, 06:00 and 12:00 at their ticks", DialMath.fraction(zurich(day + "T03:00:00"), from, to, z) == 0.125
                    && DialMath.fraction(zurich(day + "T06:00:00"), from, to, z) == 0.25 && DialMath.fraction(zurich(day + "T12:00:00"), from, to, z) == 0.5);
            check("dial " + day + ": angle of 06:00 is three o'clock", DialMath.angle(zurich(day + "T06:00:00"), from, to, z) == 0);
            check("dial " + day + ": midnight at both ends", DialMath.fraction(from, from, to, z) == 0 && DialMath.fraction(to, from, to, z) == 1
                    && DialMath.fraction(from - 100, from, to, z) == 0);
            // touch and back: every 5 minutes of the clock maps back to itself (except the skipped hour)
            int bad = 0;
            for (int k = 0; k < 288; k++) {
                double f = k / 288.0;
                double[] at = DialMath.instants(f, d, z);
                boolean skipped = day.equals("2026-03-29") && k >= 24 && k < 36;
                for (double x : at) if (!skipped && Math.abs(DialMath.fraction(x, from, to, z) - f) > 1e-9) bad++;
            }
            check("dial " + day + ": touch maps back to the same clock time", bad == 0);
        }
        java.time.LocalDate back = java.time.LocalDate.parse("2026-10-25"), fwd = java.time.LocalDate.parse("2026-03-29");
        double bFrom = back.atStartOfDay(z).toEpochSecond(), bTo = back.plusDays(1).atStartOfDay(z).toEpochSecond();
        double fFrom = fwd.atStartOfDay(z).toEpochSecond(), fTo = fwd.plusDays(1).atStartOfDay(z).toEpochSecond();
        check("25 and 23 hour days", bTo - bFrom == 25 * 3600 && fTo - fFrom == 23 * 3600);
        double first0215 = java.time.Instant.parse("2026-10-25T00:15:00Z").getEpochSecond(), second0215 = first0215 + 3600;
        List<double[]> twice = new ArrayList<double[]>();
        twice.add(new double[] {first0215, first0215 + 150});
        twice.add(new double[] {second0215, second0215 + 300});
        double[] fb = DialMath.fill(twice, bFrom, bTo, z, 288);
        int lit = 0;
        for (double v : fb) if (v > 0) lit++;
        check("clocks back: both 02:15 uses on the 02:15 line, the fuller wins", fb[27] == 1 && lit == 1);
        List<double[]> spring = new ArrayList<double[]>();
        spring.add(new double[] {fFrom, fTo});
        double[] ff = DialMath.fill(spring, fFrom, fTo, z, 288);
        boolean emptyHour = true, restFull = true;
        for (int k = 0; k < 288; k++) {
            if (k >= 24 && k < 36) emptyHour &= ff[k] == 0;
            else restFull &= ff[k] == 1;
        }
        check("clocks forward: 02:00 to 03:00 stays empty, the rest is filled", emptyHour && restFull);
        List<double[]> at0310 = new ArrayList<double[]>();
        at0310.add(new double[] {zurich("2026-03-29T03:10:00"), zurich("2026-03-29T03:15:00")});
        double[] f3 = DialMath.fill(at0310, fFrom, fTo, z, 288);
        check("clocks forward: a use at 03:10 is on step 38", f3[38] == 1 && f3[37] == 0 && f3[39] == 0);
        double[] rows = DialMath.fill(at0310, fFrom, fTo, z, 96);
        check("day rows: 15 minute clock steps", rows[12] == 1.0 / 3);
        double[] two = DialMath.instants(2.5 / 24, back, z);
        check("touching 02:30 on the long day gives both moments", two.length == 2 && two[1] - two[0] == 3600
                && two[0] == java.time.Instant.parse("2026-10-25T00:30:00Z").getEpochSecond());
        double[] gap = DialMath.instants(2.5 / 24, fwd, z);
        check("touching the skipped hour gives the switch", gap.length == 1 && gap[0] == java.time.Instant.parse("2026-03-29T01:00:00Z").getEpochSecond());
        check("touching the end of the day gives midnight", DialMath.instants(1, back, z)[0] == bTo && DialMath.instants(0, back, z)[0] == bFrom);
        double[] arcB = DialMath.changeArc(ClockChange.find(z, bFrom, bTo)), arcF = DialMath.changeArc(ClockChange.find(z, fFrom, fTo));
        check("orange arc over 02:00 to 03:00 on both days", arcB[0] == 2 / 24.0 && arcB[1] == 1 / 24.0 && arcF[0] == 2 / 24.0 && arcF[1] == 1 / 24.0);
        check("no arc on a normal day", DialMath.changeArc(ClockChange.find(z, zurich("2026-10-14T00:00:00"), zurich("2026-10-15T00:00:00"))) == null);
        eq("dial text back", ClockChange.dialText(ClockChange.find(z, bFrom, bTo)), "The clocks went back one hour on this day (03:00 became 02:00). "
                + "The hour from 02:00 to 03:00 happened twice, phone use in both shows on the same part of the clock, marked in orange.");
        eq("dial text forward", ClockChange.dialText(ClockChange.find(z, fFrom, fTo)), "The clocks went forward one hour on this day (02:00 became 03:00), "
                + "so the part of the clock from 02:00 to 03:00, marked in orange, stays empty.");
        check("no dial text without a change", ClockChange.dialText(null) == null);
        // issue #97: a time in the repeated hour says which pass it is in
        eq("repeat note: 02:22 summer time is the first time", ClockChange.repeatNote(z, java.time.Instant.parse("2026-10-25T00:22:00Z").getEpochSecond()), " (first time)");
        eq("repeat note: 02:22 winter time is the second time", ClockChange.repeatNote(z, java.time.Instant.parse("2026-10-25T01:22:00Z").getEpochSecond() + 0.7), " (second time)");
        eq("repeat note: 01:59 has none", ClockChange.repeatNote(z, zurich("2026-10-25T01:59:00")), "");
        eq("repeat note: 03:00 has none", ClockChange.repeatNote(z, java.time.Instant.parse("2026-10-25T02:00:00Z").getEpochSecond()), "");
        eq("repeat note: last second of the second pass", ClockChange.repeatNote(z, java.time.Instant.parse("2026-10-25T01:59:59Z").getEpochSecond()), " (second time)");
        eq("repeat note: none on the spring day and a normal day", ClockChange.repeatNote(z, zurich("2026-03-29T03:30:00"))
                + ClockChange.repeatNote(z, zurich("2026-10-14T02:22:00")), "");
        check("repeat note: none without a zone or time", ClockChange.repeatNote(null, bFrom).isEmpty() && ClockChange.repeatNote(z, Double.NaN).isEmpty());
        // the sleep stages readout (review of #104): note on the touched time, on an end only when its pass differs
        double s1 = java.time.Instant.parse("2026-10-25T00:00:00Z").getEpochSecond();
        eq("stage tip: normal night unchanged", ClockChange.stageTip(z, zurich("2026-10-14T02:22:00"), "Deep", zurich("2026-10-14T02:15:00"),
                zurich("2026-10-14T02:40:00")), "02:22  Deep, 02:15 to 02:40 (25 min)");
        eq("stage tip: inside the second pass the touched time says it once", ClockChange.stageTip(z, s1 + 3600 + 22 * 60, "Deep", s1 + 3600 + 15 * 60,
                s1 + 3600 + 40 * 60), "02:22 (second time)  Deep, 02:15 to 02:40 (25 min)");
        eq("stage tip: a stretch across the change marks the other pass", ClockChange.stageTip(z, s1 + 50 * 60, "Light", s1 + 40 * 60, s1 + 65 * 60),
                "02:50 (first time)  Light, 02:40 to 02:05 (second time) (25 min)");
        eq("stage tip: touched after the hour, start in it", ClockChange.stageTip(z, s1 + 2 * 3600 + 600, "REM", s1 + 3600 + 50 * 60, s1 + 2 * 3600 + 1200),
                "03:10  REM, 02:50 (second time) to 03:20 (30 min)");
        eq("repeat text back", ClockChange.repeatText(ClockChange.find(z, bFrom, bTo)), "The clocks went back one hour on this day (03:00 became 02:00), "
                + "so 02:00 to 03:00 happened twice. Times in that hour show (first time) or (second time).");
        check("no repeat text going forward or without a change", ClockChange.repeatText(ClockChange.find(z, fFrom, fTo)) == null && ClockChange.repeatText(null) == null);
        java.time.ZoneId lh = java.time.ZoneId.of("Australia/Lord_Howe");
        java.time.LocalDate lhd = java.time.LocalDate.parse("2026-04-05");
        ClockChange.Change half = ClockChange.find(lh, lhd.atStartOfDay(lh).toEpochSecond(), lhd.plusDays(1).atStartOfDay(lh).toEpochSecond());
        check("30 minute change: half an hour arc", DialMath.changeArc(half)[1] == 30 / 1440.0);
        // issue #129: a 30 minute change repeats a half hour, not an hour
        eq("30 minute change: dial text", ClockChange.dialText(half), "The clocks went back 30 minutes on this day (02:00 became 01:30). "
                + "The half hour from 01:30 to 02:00 happened twice, phone use in both shows on the same part of the clock, marked in orange.");
        eq("30 minute change: repeat text", ClockChange.repeatText(half), "The clocks went back 30 minutes on this day (02:00 became 01:30), "
                + "so 01:30 to 02:00 happened twice. Times in that half hour show (first time) or (second time).");
        ClockChange.Change lhFwd = ClockChange.find(lh, java.time.LocalDate.parse("2026-10-04").atStartOfDay(lh).toEpochSecond(),
                java.time.LocalDate.parse("2026-10-05").atStartOfDay(lh).toEpochSecond());
        eq("30 minute change forward: dial text names no length", ClockChange.dialText(lhFwd), "The clocks went forward 30 minutes on this day "
                + "(02:00 became 02:30), so the part of the clock from 02:00 to 02:30, marked in orange, stays empty.");
        java.time.ZoneId troll = java.time.ZoneId.of("Antarctica/Troll");
        ClockChange.Change twoH = ClockChange.find(troll, java.time.LocalDate.parse("2026-10-25").atStartOfDay(troll).toEpochSecond(),
                java.time.LocalDate.parse("2026-10-26").atStartOfDay(troll).toEpochSecond());
        check("2 hour change: a period, not an hour", twoH != null && twoH.minutes == -120 && ClockChange.dialText(twoH).contains(". The period from ")
                && ClockChange.repeatText(twoH).endsWith("Times in that period show (first time) or (second time)."));
        // a night inside the repeated hour, 02:40 summer time to 02:20 winter time, keeps its 40 minutes
        List<double[]> inside = new ArrayList<double[]>();
        inside.add(new double[] {java.time.Instant.parse("2026-10-25T00:40:00Z").getEpochSecond(), java.time.Instant.parse("2026-10-25T01:20:00Z").getEpochSecond()});
        double[] fi = DialMath.fill(inside, bFrom, bTo, z, 288);
        int on = 0;
        for (double v : fi) if (v > 0) on++;
        check("a night inside the repeated hour keeps its width", on == 8 && fi[24] == 1 && fi[27] == 1 && fi[32] == 1 && fi[35] == 1 && fi[28] == 0);
        check("empty day fills nothing", DialMath.fill(twice, bFrom, bFrom, z, 288)[0] == 0);
        dialTouch();
        clockChangeOnDay();
    }

    /** Issue #109: touching a lit line of the screen clock finds the use drawn there. */
    static void dialTouch() {
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        java.time.LocalDate d = java.time.LocalDate.parse("2026-10-14");
        double from = d.atStartOfDay(z).toEpochSecond(), to = d.plusDays(1).atStartOfDay(z).toEpochSecond();
        double t1255 = zurich("2026-10-14T12:55:00"), f1257 = (12 * 60 + 57) / 1440.0;
        List<double[]> uses = new ArrayList<double[]>();
        uses.add(new double[] {t1255 + 10, t1255 + 40});
        List<double[]> none = new ArrayList<double[]>();
        double[] lit = DialMath.fill(uses, from, to, z, 288);
        check("touch: the 12:55 line is lit by a 30 s use", lit[155] > 0 && lit[156] == 0);
        DialMath.Touch tc = DialMath.touch(f1257, d, from, to, z, 288, uses, uses, none);
        check("touch: 12:57 on the lit line finds the use and moves into it", tc.session == 0 && tc.at == t1255 + 39 && !tc.asleep);
        check("touch: 12:54 on the dark line before reads no use", DialMath.touch((12 * 60 + 54) / 1440.0, d, from, to, z, 288, uses, uses, none).session == -1);
        check("touch: 13:00 on the dark line after reads no use", DialMath.touch(13 / 24.0, d, from, to, z, 288, uses, uses, none).session == -1);
        check("touch: inside the use stays at the touched second", DialMath.touch((t1255 + 20 - from) / 86400.0, d, from, to, z, 288, uses, uses, none).at == t1255 + 20);
        // two uses on one line: the nearer one
        List<double[]> two = new ArrayList<double[]>();
        two.add(new double[] {t1255 + 10, t1255 + 40});
        two.add(new double[] {t1255 + 250, t1255 + 280});
        DialMath.Touch near = DialMath.touch(f1257 + 60 / 86400.0, d, from, to, z, 288, two, two, none);
        check("touch: 12:58 takes the nearer use of the line (12:59:10)", near.session == 1 && near.at == t1255 + 250);
        DialMath.Touch before = DialMath.touch((t1255 + 5 - from) / 86400.0, d, from, to, z, 288, uses, null, null);
        check("touch: 12:55:05 just before the use moves forward into it (no drawn list, no nights)", before.session == 0 && before.at == t1255 + 10);
        check("touch: a dark line without nights is not asleep", !DialMath.touch(0.25, d, from, to, z, 288, uses, null, null).asleep);
        check("touch: 12:56 takes the earlier one", DialMath.touch(f1257 - 60 / 86400.0, d, from, to, z, 288, two, two, none).session == 0);
        // drawn at least 5 minutes: a 10 s use at 12:58:50 lights 12:55 and 13:00, both find it
        List<double[]> real = new ArrayList<double[]>();
        real.add(new double[] {t1255 + 230, t1255 + 240});
        List<double[]> wide = ScreenTime.widen(real, 300);
        double[] wl = DialMath.fill(wide, from, to, z, 288);
        check("touch: a widened use lights two lines", wl[155] > 0 && wl[156] > 0 && wl[157] == 0);
        DialMath.Touch w1 = DialMath.touch((13 * 60 + 2) / 1440.0, d, from, to, z, 288, real, wide, none);
        check("touch: 13:02 on the widened line finds the use, the hand at its real time", w1.session == 0 && w1.at == t1255 + 239);
        DialMath.Touch w2 = DialMath.touch((13 * 60 + 4.9) / 1440.0, d, from, to, z, 288, real, wide, none);
        check("touch: 13:04 at the end of the widened line too", w2.session == 0 && w2.at == t1255 + 239);
        check("touch: without the widening 13:02 reads no use", DialMath.touch((13 * 60 + 2) / 1440.0, d, from, to, z, 288, real, real, none).session == -1);
        // a drawn list that does not match the real one (another size) is not used: the widened line reads no use
        List<double[]> other = new ArrayList<double[]>(wide);
        other.add(new double[] {t1255 - 3600, t1255 - 3500});
        check("touch: a shown list of another size counts as the real one",
                DialMath.touch((13 * 60 + 2) / 1440.0, d, from, to, z, 288, real, other, none).session == -1);
        // a use at 23:58 drawn 5 minutes long lights 00:00 of the next day
        List<double[]> late = new ArrayList<double[]>();
        late.add(new double[] {from - 120, from - 100});
        List<double[]> lateW = ScreenTime.widen(late, 300);
        // drawn from 23:58 to 00:03: 3 of the first 5 minute piece
        check("touch: a use before midnight drawn over it lights 00:00", near(DialMath.fill(lateW, from, to, z, 288)[0], 0.6));
        DialMath.Touch lt = DialMath.touch(2 / 1440.0, d, from, to, z, 288, late, lateW, none);
        check("touch: 00:02 finds it, the hand stays on this day", lt.session == 0 && lt.at == from);
        // asleep and the repeated hour (clocks back, 2026-10-25)
        java.time.LocalDate back = java.time.LocalDate.parse("2026-10-25");
        double bFrom = back.atStartOfDay(z).toEpochSecond(), bTo = back.plusDays(1).atStartOfDay(z).toEpochSecond();
        double second0215 = java.time.Instant.parse("2026-10-25T01:15:00Z").getEpochSecond();
        List<double[]> second = new ArrayList<double[]>();
        second.add(new double[] {second0215 + 10, second0215 + 40});
        DialMath.Touch rp = DialMath.touch((2 * 60 + 17) / 1440.0, back, bFrom, bTo, z, 288, second, second, none);
        check("touch: 02:17 on the long day finds the use of the second pass", rp.session == 0 && rp.at == second0215 + 39);
        List<double[]> night2 = new ArrayList<double[]>();
        night2.add(new double[] {second0215 - 600, second0215 + 3600});
        DialMath.Touch sl = DialMath.touch((2 * 60 + 40) / 1440.0, back, bFrom, bTo, z, 288, none, none, night2);
        check("touch: asleep only in the second pass of 02:40 still reads asleep", sl.session == -1 && sl.asleep && sl.at == second0215 + 25 * 60);
        DialMath.Touch aw = DialMath.touch(14 / 24.0, back, bFrom, bTo, z, 288, none, none, night2);
        check("touch: 14:00 is not asleep", !aw.asleep && aw.at == zurich("2026-10-25T14:00:00"));
        // the hour the clocks skip (2026-03-29): the switch moment, inside a use across it, else no use
        java.time.LocalDate fwd = java.time.LocalDate.parse("2026-03-29");
        double fFrom = fwd.atStartOfDay(z).toEpochSecond(), fTo = fwd.plusDays(1).atStartOfDay(z).toEpochSecond();
        List<double[]> around = new ArrayList<double[]>();
        around.add(new double[] {zurich("2026-03-29T01:59:00"), zurich("2026-03-29T03:00:30")});
        check("touch: the skipped hour is dark and reads no use, also with a use across the switch", DialMath.fill(around, fFrom, fTo, z, 288)[30] == 0
                && DialMath.touch(2.5 / 24, fwd, fFrom, fTo, z, 288, around, around, none).session == -1
                && DialMath.touch(2.5 / 24, fwd, fFrom, fTo, z, 288, none, none, none).session == -1);
        check("touch: the lines next to the skipped hour find that use", DialMath.touch(1.99 / 24, fwd, fFrom, fTo, z, 288, around, around, none).session == 0
                && DialMath.touch(3.01 / 24, fwd, fFrom, fTo, z, 288, around, around, none).session == 0);
        // a widened use from before does not hide a real one on the touched line
        List<double[]> pair = new ArrayList<double[]>();
        pair.add(new double[] {t1255 - 200, t1255 - 190});
        pair.add(new double[] {t1255 + 240, t1255 + 250});
        List<double[]> pairW = ScreenTime.widen(pair, 900);
        DialMath.Touch pr = DialMath.touch((12 * 60 + 56) / 1440.0, d, from, to, z, 288, pair, pairW, none);
        check("touch: 12:56 inside a widened earlier use takes the real use on its line", pr.session == 1 && pr.at == t1255 + 240);
        // a day that starts at 01:00 (Cairo, the clocks skip midnight): the hand at midnight shows 01:00
        java.time.ZoneId cairo = java.time.ZoneId.of("Africa/Cairo");
        java.time.LocalDate cd = java.time.LocalDate.parse("2024-04-26");
        double cFrom = cd.atStartOfDay(cairo).toEpochSecond(), cTo = cd.plusDays(1).atStartOfDay(cairo).toEpochSecond();
        check("dial: a day starting at 01:00 has its start at the 01:00 tick", cTo - cFrom == 23 * 3600 && DialMath.fraction(cFrom, cFrom, cTo, cairo) == 1 / 24.0
                && DialMath.fraction(cFrom - 50, cFrom, cTo, cairo) == 1 / 24.0 && DialMath.fraction(cFrom + 1800, cFrom, cTo, cairo) == 1.5 / 24);
        check("touch: end of the day and empty day", DialMath.touch(1, d, from, to, z, 288, uses, uses, none).at == to - 1
                && DialMath.touch(0.5, d, from, from, z, 288, uses, uses, none).session == -1);
        // every lit line of a busy day finds a use, every dark line none: a normal day, both clock change days,
        // the half hour changes of Lord Howe and the days of Cairo that start at 01:00 or repeat 23:00
        touchSweep(z, "2026-10-14", 109);
        touchSweep(z, "2026-10-25", 110);
        touchSweep(z, "2026-03-29", 111);
        touchSweep(java.time.ZoneId.of("Australia/Lord_Howe"), "2026-04-05", 112);
        touchSweep(java.time.ZoneId.of("Australia/Lord_Howe"), "2026-10-04", 113);
        touchSweep(java.time.ZoneId.of("Africa/Cairo"), "2024-04-26", 114);
        touchSweep(java.time.ZoneId.of("Africa/Cairo"), "2024-10-31", 115);
    }

    static void touchSweep(java.time.ZoneId z, String date, long seed) {
        java.time.LocalDate d = java.time.LocalDate.parse(date);
        double from = d.atStartOfDay(z).toEpochSecond(), to = d.plusDays(1).atStartOfDay(z).toEpochSecond();
        List<double[]> none = new ArrayList<double[]>();
        java.util.Random rnd = new java.util.Random(seed);
        List<double[]> many = new ArrayList<double[]>();
        for (double t = from - 200; t < to; t += 60 + rnd.nextInt(2400)) many.add(new double[] {t, t + 1 + rnd.nextInt(200)});
        for (double minS : new double[] {0, 120, 900}) {
            List<double[]> sh = ScreenTime.widen(many, minS);
            double[] fl = DialMath.fill(sh, from, to, z, 288);
            int bad = 0, lit = 0;
            for (int k = 0; k < 288; k++) {
                if (fl[k] > 0) lit++;
                for (double part : new double[] {0.05, 0.5, 0.95}) {
                    DialMath.Touch x = DialMath.touch((k + part) / 288, d, from, to, z, 288, many, sh, none);
                    if ((fl[k] > 0) != (x.session >= 0)) bad++;
                    double[] r = x.session >= 0 ? many.get(x.session) : null;
                    // the hand on the use, or at midnight for a use of the evening before drawn over it
                    if (r != null && !(x.at >= r[0] && x.at < Math.max(r[1], r[0] + 1) || r[1] <= from && x.at == from)) bad++;
                }
            }
            check("touch " + z + " " + date + ": lit lines find a use, dark ones none, at least " + (int) minS + " s"
                    + (bad == 0 ? "" : " (" + bad + " wrong)"), bad == 0 && lit > 50);
        }
    }

    /** Issue #158: a change forward at midnight belongs to the day whose clock time it skips. */
    static void clockChangeOnDay() {
        for (String id : new String[] {"America/Nuuk", "America/Scoresbysund"}) {
            java.time.ZoneId z = java.time.ZoneId.of(id);
            java.time.LocalDate sat = java.time.LocalDate.parse("2027-03-27"), sun = sat.plusDays(1);
            double s0 = sat.atStartOfDay(z).toEpochSecond(), s1 = sun.atStartOfDay(z).toEpochSecond(), s2 = sun.plusDays(1).atStartOfDay(z).toEpochSecond();
            check(id + ": 2027-03-27 is the 23 hour day", s1 - s0 == 23 * 3600 && s2 - s1 == 24 * 3600);
            ClockChange.Change c = ClockChange.onDay(z, s0, s1);
            check(id + ": the change is on 2027-03-27, 23:00 became 00:00", c != null && c.minutes == 60 && c.from.equals("23:00") && c.to.equals("00:00"));
            double[] arc = DialMath.changeArc(c);
            check(id + ": orange arc from 23:00 for an hour", arc != null && arc[0] == 23 / 24.0 && arc[1] == 1 / 24.0);
            check(id + ": 2027-03-28 has none", ClockChange.onDay(z, s1, s2) == null && ClockChange.dialText(ClockChange.onDay(z, s1, s2)) == null);
            eq(id + ": dial text on 2027-03-27", ClockChange.dialText(c), "The clocks went forward one hour on this day (23:00 became 00:00), "
                    + "so the part of the clock from 23:00 to 00:00, marked in orange, stays empty.");
            // nights keep find: a change inside the night, wherever its days are
            ClockChange.Change inNight = ClockChange.find(z, s1 - 3 * 3600, s1 + 5 * 3600);
            check(id + ": a night over Saturday's midnight still has it", inNight != null && inNight.at == s1 && inNight.minutes == 60);
            java.time.LocalDate oct = java.time.LocalDate.parse("2027-10-30");
            double o0 = oct.atStartOfDay(z).toEpochSecond(), o1 = oct.plusDays(1).atStartOfDay(z).toEpochSecond(), o2 = oct.plusDays(2).atStartOfDay(z).toEpochSecond();
            ClockChange.Change b = ClockChange.onDay(z, o0, o1);
            check(id + ": autumn stays on the 25 hour day 2027-10-30", o1 - o0 == 25 * 3600 && b != null && b.minutes == -60 && ClockChange.onDay(z, o1, o2) == null);
        }
        java.time.ZoneId zh = java.time.ZoneId.of("Europe/Zurich");
        check("Zurich 2026: onDay and find agree", ClockChange.onDay(zh, zurich("2026-03-29T00:00:00"), zurich("2026-03-30T00:00:00")).at
                == ClockChange.find(zh, zurich("2026-03-29T00:00:00"), zurich("2026-03-30T00:00:00")).at
                && ClockChange.onDay(zh, zurich("2026-10-25T00:00:00"), zurich("2026-10-26T00:00:00")).minutes == -60
                && ClockChange.onDay(zh, zurich("2026-10-14T00:00:00"), zurich("2026-10-15T00:00:00")) == null);
        check("onDay without a zone or with an empty range", ClockChange.onDay(null, 0, 1) == null && ClockChange.onDay(zh, 5, 5) == null
                && ClockChange.onDay(zh, Double.NaN, 5) == null);
        // every zone, 2024 to 2036: each change lands on one day, the one whose length differs from 24 h by it
        int changes = 0, wrong = 0;
        String firstWrong = null;
        java.time.Instant end = java.time.LocalDate.parse("2037-01-01").atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        for (String id : java.time.ZoneId.getAvailableZoneIds()) {
            java.time.ZoneId z = java.time.ZoneId.of(id);
            java.time.zone.ZoneOffsetTransition t = z.getRules().nextTransition(java.time.LocalDate.parse("2024-01-03").atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            for (; t != null && t.getInstant().isBefore(end); t = z.getRules().nextTransition(t.getInstant())) {
                int min = (t.getOffsetAfter().getTotalSeconds() - t.getOffsetBefore().getTotalSeconds()) / 60;
                if (min == 0) continue;
                changes++;
                java.time.LocalDate mid = t.getInstant().atZone(z).toLocalDate();
                int hits = 0;
                for (java.time.LocalDate day = mid.minusDays(2); !day.isAfter(mid.plusDays(2)); day = day.plusDays(1)) {
                    double a = day.atStartOfDay(z).toEpochSecond(), b = day.plusDays(1).atStartOfDay(z).toEpochSecond();
                    ClockChange.Change c = ClockChange.onDay(z, a, b);
                    boolean mine = c != null && c.at == t.getInstant().getEpochSecond();
                    if (mine) hits++;
                    String bad = mine && b - a != 86400 - min * 60 ? day + " is " + (b - a) / 3600.0 + " h"
                            : b - a != 86400 && c == null ? day + " (" + (b - a) / 3600.0 + " h) has no change" : null;
                    if (bad != null) {
                        wrong++;
                        if (firstWrong == null) firstWrong = id + " " + bad;
                    }
                }
                if (hits != 1) {
                    wrong++;
                    if (firstWrong == null) firstWrong = id + " " + t + " on " + hits + " days";
                }
            }
        }
        check("every zone 2024 to 2036: each change on the day whose length differs by it (" + changes + " changes"
                + (wrong == 0 ? "" : ", " + wrong + " wrong, first " + firstWrong) + ")", changes > 1000 && wrong == 0);
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
        ClockChange.Change atSwitch = ClockChange.find(z, sw, sw + 3600);
        check("night starting at the switch has it", atSwitch != null && atSwitch.at == sw && atSwitch.minutes == -60);
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
        travel();
        sleptBefore();
    }

    /** Issues #161 and #162: where a sleep between two zone notes was slept, from the clock its end reads on. */
    static void sleptBefore() {
        java.time.ZoneId zh = java.time.ZoneId.of("Europe/Zurich"), ny = java.time.ZoneId.of("America/New_York"),
                la = java.time.ZoneId.of("America/Los_Angeles"), ak = java.time.ZoneId.of("Pacific/Auckland"),
                tk = java.time.ZoneId.of("Asia/Tokyo"), hn = java.time.ZoneId.of("Pacific/Honolulu");
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 11);
        check("Zurich night before flying to Los Angeles (06:45 there, 21:45 here)",
                ClockChange.sleptBefore(d.atTime(6, 45).atZone(zh).toEpochSecond(), zh, la));
        check("Zurich night before flying to New York (05:30 there, 23:30 here)",
                ClockChange.sleptBefore(d.atTime(5, 30).atZone(zh).toEpochSecond(), zh, ny));
        check("first night in New York after Zurich (06:45 here, 12:45 there)",
                !ClockChange.sleptBefore(d.atTime(6, 45).atZone(ny).toEpochSecond(), zh, ny));
        check("first night in Los Angeles after Auckland (06:45 here, 02:45 there)",
                !ClockChange.sleptBefore(d.atTime(6, 45).atZone(la).toEpochSecond(), ak, la));
        check("Auckland night before flying to Los Angeles (06:45 there, 10:45 here)",
                ClockChange.sleptBefore(d.atTime(6, 45).atZone(ak).toEpochSecond(), ak, la));
        check("Tokyo night before flying to Honolulu (06:45 there, 11:45 here)",
                ClockChange.sleptBefore(d.atTime(6, 45).atZone(tk).toEpochSecond(), tk, hn));
        check("first night in Honolulu after Tokyo (06:45 here, 01:45 there)",
                !ClockChange.sleptBefore(d.atTime(6, 45).atZone(hn).toEpochSecond(), tk, hn));
        // Zurich and Paris share the clock: a tie goes to the later zone
        check("same clock: the later zone", !ClockChange.sleptBefore(d.atTime(7, 0).atZone(zh).toEpochSecond(), zh, java.time.ZoneId.of("Europe/Paris")));
        check("no zone before: the later zone", !ClockChange.sleptBefore(d.atTime(7, 0).atZone(zh).toEpochSecond(), null, la)
                && !ClockChange.sleptBefore(Double.NaN, zh, la) && !ClockChange.sleptBefore(0, zh, null));
        eq("07:00 is the morning", ClockChange.fromMorning(java.time.LocalTime.of(7, 0)), 0);
        eq("19:00 is the farthest from it", ClockChange.fromMorning(java.time.LocalTime.of(19, 0)), 720);
        eq("distance across midnight", ClockChange.fromMorning(java.time.LocalTime.of(23, 30)), 450);
    }

    static void travel() {
        // issue #22 part 4: the line under the chart when a night is in another zone than the night before
        double oct = java.time.Instant.parse("2026-10-14T03:00:00Z").getEpochSecond();
        eq("travel west", ClockChange.travel("Europe/Zurich", "America/New_York", oct),
                "Times this night are in New York time, 6 hours behind where you slept the night before.");
        eq("travel east", ClockChange.travel("America/New_York", "Europe/Zurich", oct),
                "Times this night are in Zurich time, 6 hours ahead of where you slept the night before.");
        // 20 March 2026: America is on summer time already, Europe not yet
        eq("travel while the switches differ", ClockChange.travel("Europe/Zurich", "America/New_York",
                java.time.Instant.parse("2026-03-20T03:00:00Z").getEpochSecond()),
                "Times this night are in New York time, 5 hours behind where you slept the night before.");
        eq("travel half hours", ClockChange.travel("Europe/Zurich", "Asia/Kolkata", java.time.Instant.parse("2026-01-10T03:00:00Z").getEpochSecond()),
                "Times this night are in Kolkata time, 4 hours 30 minutes ahead of where you slept the night before.");
        eq("travel one hour", ClockChange.travel("Europe/London", "Europe/Zurich", oct),
                "Times this night are in Zurich time, one hour ahead of where you slept the night before.");
        check("same clock, other zone: no line", ClockChange.travel("Europe/Zurich", "Europe/Berlin", oct) == null);
        check("same zone: no line", ClockChange.travel("Europe/Zurich", "Europe/Zurich", oct) == null);
        check("missing or unknown zone: no line", ClockChange.travel(null, "Europe/Zurich", oct) == null
                && ClockChange.travel("Europe/Zurich", "", oct) == null && ClockChange.travel("Mars/Olympus", "Europe/Zurich", oct) == null
                && ClockChange.travel("Europe/Zurich", "America/New_York", Double.NaN) == null);
        eq("zone name with two parts", ClockChange.zoneName("America/Argentina/Buenos_Aires"), "Buenos Aires time");
        eq("zone name UTC", ClockChange.zoneName("UTC"), "UTC");
        eq("zone name Etc", ClockChange.zoneName("Etc/GMT-5"), "UTC+5");
        eq("zone name offset", ClockChange.zoneName("GMT-03:30"), "UTC-3:30");
        eq("zone name unknown", ClockChange.zoneName("Mars"), "Mars");
        eq("travel to a zone without a city", ClockChange.travel("Europe/Zurich", "Etc/GMT-5", oct),
                "Times this night are in UTC+5, 3 hours ahead of where you slept the night before.");
        check("unknown zone id is null", ClockChange.zone("Nowhere/Place") == null && ClockChange.zone(null) == null
                && ClockChange.zone("Asia/Tokyo") != null);
        eq("minutes alone", ClockChange.travel("Asia/Kolkata", "Asia/Kathmandu", oct),
                "Times this night are in Kathmandu time, 15 minutes ahead of where you slept the night before.");
    }

    /** Run length text of stage codes, "code x count" per run, for pinning a whole night in one line. */
    static String rle(int[] st) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.length; ) {
            int j = i;
            while (j < st.length && st[j] == st[i]) j++;
            sb.append(sb.length() > 0 ? " " : "").append(st[i]).append('x').append(j - i);
            i = j;
        }
        return sb.toString();
    }

    /**
     * Checks a mutation run asked for: each one fails when the line it names is changed in a small way
     * (a comparison, a sign, a constant) that no other check noticed. Expected values are worked out by
     * hand from the inputs in the comments.
     */
    static void mutationGaps() {
        // TS.filter: the value range and the time window both apply. 5 is below the range, 25 above it,
        // 15 at 900 is after the window: only (200, 15) is left
        TS ts = new TS();
        ts.add(100, 5);
        ts.add(200, 15);
        ts.add(300, 25);
        ts.add(900, 15);
        TS fl = ts.filter(10, 20, 0, 500);
        check("filter keeps only values in range and inside the window", fl.size() == 1 && fl.t(0) == 200 && fl.v(0) == 15);
        // TS.interp on the last segment: halfway from (10, 10) to (20, 40) is 25
        TS line = new TS();
        line.add(0, 0);
        line.add(10, 10);
        line.add(20, 40);
        eq("interpolation on the last segment", line.interp(15), 25.0);

        // NightAnalyzer: REM runs of 2 and 10 steps of 30 s, 100 steps apart (more than the 15 minute merge gap
        // of 30 steps); only the 10 step run reaches the 3 minute minimum (6 steps), so 1 cycle
        NightAnalyzer.Input cy = new NightAnalyzer.Input();
        cy.start = 1_800_000_000;
        cy.ringStages = runs(Stager.LIGHT, 100, Stager.REM, 2, Stager.LIGHT, 100, Stager.REM, 10, Stager.LIGHT, 100);
        cy.end = cy.start + cy.ringStages.length * 30;
        Night cyn = NightAnalyzer.analyze(cy);
        eq("a REM run shorter than 3 minutes is not a cycle", cyn.cycles, 1);
        // an empty ring hypnogram covers nothing, so the night falls back to the plain window (1 hour = 120 steps)
        NightAnalyzer.Input em = new NightAnalyzer.Input();
        em.start = 1_800_000_000;
        em.end = em.start + 3600;
        em.ringStages = new int[0];
        Night emn = NightAnalyzer.analyze(em);
        check("an empty ring hypnogram is not used", "window".equals(emn.stageSource) && emn.stages.length == 120);

        // NightFinder: 30 minutes the ring calls asleep is shorter than a derived sleep's 45 minutes: no sleep
        double t0 = 1_800_000_000;
        TS shortSleep = new TS();
        for (int i = 0; i <= 30; i += 5) shortSleep.add(t0 + i * 60, 1);
        eq("30 minutes of ring sleep alone is no night", NightFinder.find(new ArrayList<double[]>(), new ArrayList<NightFinder.RingRun>(),
                shortSleep, new TS()).size(), 0);
        // a bedtime of an hour is carried on by an event 380 s after it, inside the ring's asleep stretch
        // (samples each minute from 3000 to 4800 s, awake only at 4020 and 4080). The nearest sample to the
        // event at 3980 is 3960 (20 s, asleep), not 4020 (40 s, awake), so the night ends at 3980
        TS st = new TS();
        for (int s = 3000; s <= 4800; s += 60) st.add(t0 + s, s == 4020 || s == 4080 ? 0 : 1);
        TS sup = new TS();
        sup.add(t0 + 3980, 1);
        List<double[]> bt = new ArrayList<double[]>();
        bt.add(new double[] {t0, t0 + 3600});
        List<NightFinder.Period> ext = NightFinder.find(bt, new ArrayList<NightFinder.RingRun>(), st, sup);
        check("the nearest sleep sample before an event counts", ext.size() == 1 && ext.get(0).end == t0 + 3980);

        // Scores.clock on a real time: 1,800,000,000 s is 20833 days and 28,800 s, so 08:00 UTC and 10:00 at +2 h
        eq("clock of a real unix time", Scores.clock(1_800_000_000.0, 0), "08:00");
        eq("clock of a real unix time with an offset", Scores.clock(1_800_000_000.0, 7200), "10:00");

        // Wellness.day: a minute at exactly 3.0 MET is medium, one at exactly 6.0 is high
        double wd = 1_800_000_000;
        TS met = new TS();
        met.add(wd + 10, 3.0);
        met.add(wd + 70, 6.0);
        Wellness.Day band = Wellness.day("d", wd, wd + 120, null, met, new ArrayList<double[]>(), 70, 1600, wd + 86400);
        check("3.0 MET is medium and 6.0 MET is high", band.lowMin == 0 && band.mediumMin == 1 && band.highMin == 1);
        // Wellness.sleepDebt: a day without sleep (0) is skipped. 6 h of 8 h on days 0, 2, 3, 4 with weights
        // 1, 0.625, 0.4375, 0.25 (decay 0.1875 a day): 7200 s x 2.3125 = 16650 s, rounded to 45 minutes 16200 s;
        // 4 valid days is not enough (5)
        double[] debt0 = Wellness.sleepDebt(new int[] {21600, 0, 21600, 21600, 21600}, new int[] {28800, 28800, 28800, 28800, 28800});
        eq("sleep debt skips a day without sleep", Arrays.toString(debt0), "[16200.0, 0.0]");
        // Wellness.radar: history 9,9,9,10,10,10,11,11,11 has median 10 and MAD 1 (sd 1.4826)
        double[] hist = {9, 10, 11, 9, 10, 11, 9, 10, 11};
        double sd = 1.4826;
        double[][] h4 = {hist, hist, hist, hist};
        eq("two signs, one strong, is major", Wellness.radar(new double[] {10 + 3.5 * sd, 10 + 2.5 * sd, 10, 10}, h4).decision, 2);
        eq("two signs, none strong, is minor", Wellness.radar(new double[] {10 + 2.5 * sd, 10 + 2.5 * sd, 10, 10}, h4).decision, 1);

        // Proximity.strongest: no plausible reading, the highest raw one is the first
        eq("strongest raw reading can be the first", Proximity.strongest(new int[] {127, -127}), 0);

        // DialMath.touch: a use ending at the second the next one starts; touching that second is in the second use
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        java.time.LocalDate d = java.time.LocalDate.parse("2026-10-14");
        double from = d.atStartOfDay(z).toEpochSecond(), to = d.plusDays(1).atStartOfDay(z).toEpochSecond();
        double t1255 = zurich("2026-10-14T12:55:00");
        List<double[]> back = new ArrayList<double[]>();
        back.add(new double[] {t1255 + 10, t1255 + 40});
        back.add(new double[] {t1255 + 40, t1255 + 70});
        DialMath.Touch edge = DialMath.touch((t1255 + 40 - from) / 86400.0, d, from, to, z, 288, back, back, null);
        check("touching the end second of a use reads the use starting there", edge.session == 1 && edge.at == t1255 + 40);

        // StageShare.shownMinutes: asleep shown as 0 minutes, so light 0.3 and REM 0.3 both show 0
        eq("a shown total of 0 rounds the stages to 0", Arrays.toString(StageShare.shownMinutes(0, 0.3, 0.3, 0, 0, 0.4, 0.6, -1)),
                "[0.0, 0.0, 0.0, 0.0, 0.0]");

        // ScreenTime.oneAtATime: two apps come to the front at the same second; the alphabetically first holds
        // the shared time, the other keeps the rest
        Map<String, List<double[]>> same = new LinkedHashMap<String, List<double[]>>();
        same.put("b.app", new ArrayList<double[]>(Arrays.asList(new double[] {1000, 1100})));
        same.put("a.app", new ArrayList<double[]>(Arrays.asList(new double[] {1000, 1050})));
        Map<String, List<double[]>> one = ScreenTime.oneAtATime(same);
        check("same start: the alphabetically first app wins", one.containsKey("a.app") && one.containsKey("b.app")
                && Arrays.toString(one.get("a.app").get(0)).equals("[1000.0, 1050.0]")
                && Arrays.toString(one.get("b.app").get(0)).equals("[1050.0, 1100.0]"));

        // Tilt: no calibration sample before the first gravity reading, and no crash
        Tilt tl = new Tilt();
        tl.alpha = 1;
        boolean noCrash = true;
        try {
            tl.startCalibration();
            tl.calibrateSample();
        } catch (RuntimeException e) {
            noCrash = false;
        }
        check("calibrating before any gravity reading does nothing", noCrash);
        // neutral pose gravity (2, 3, 6) / 7. By hand: the right axis comes from x, (15, -2, -4) / (7 sqrt 5), and the
        // forward axis is neutral cross right, (0, 2, -1) / sqrt 5. The calibration sample is taken before the hand moves on
        double[] u0 = {2 / 7.0, 3 / 7.0, 6 / 7.0};
        double r5 = Math.sqrt(5);
        double[] bR = {15 / (7 * r5), -2 / (7 * r5), -4 / (7 * r5)}, bF = {0, 2 / r5, -1 / r5};
        tl.feed(200, 300, 600);
        tl.startCalibration();
        tl.calibrateSample();
        tl.feed(0, 1000, 0); // the hand moves before calibration ends; the sample taken counts
        tl.finishCalibration();
        tl.feed(200, 300, 600);
        double[] c0 = tl.stick();
        check("tilt: the calibrated pose is centred", c0[0] == 0 && c0[1] == 0);
        double c20 = Math.cos(Math.toRadians(20)), s20 = Math.sin(Math.toRadians(20));
        // 20 degrees towards the right axis: steering (20 - 4) / 25 = 0.64 to the right, no forward part
        tl.feed((int) Math.round(1000 * (c20 * u0[0] + s20 * bR[0])), (int) Math.round(1000 * (c20 * u0[1] + s20 * bR[1])),
                (int) Math.round(1000 * (c20 * u0[2] + s20 * bR[2])));
        double[] right = tl.stick();
        check("tilt right steers right", Math.abs(right[0] - 0.64) < 0.01 && right[1] == 0);
        tl.feed((int) Math.round(1000 * (c20 * u0[0] - s20 * bR[0])), (int) Math.round(1000 * (c20 * u0[1] - s20 * bR[1])),
                (int) Math.round(1000 * (c20 * u0[2] - s20 * bR[2])));
        double[] left = tl.stick();
        check("tilt left steers left", Math.abs(left[0] + 0.64) < 0.01 && left[1] == 0);
        tl.feed((int) Math.round(1000 * (c20 * u0[0] + s20 * bF[0])), (int) Math.round(1000 * (c20 * u0[1] + s20 * bF[1])),
                (int) Math.round(1000 * (c20 * u0[2] + s20 * bF[2])));
        double[] fwd = tl.stick();
        check("tilt forward moves only the forward axis", fwd[0] == 0 && Math.abs(fwd[1] - 0.64) < 0.01);

        // Stager: this pins today's output for eight fixed synthetic nights (seeds picked so that small changes
        // in the smoothing, percentiles, window sizes and deep and REM placement each change at least one night).
        // The other stage checks test properties and ranges only, so such changes went unnoticed. Any change in
        // staging now fails here and must be deliberate: update the expected text in the same commit and say why.
        // Each night is written as runs of "stage x steps" (1 deep, 2 light, 3 REM, 4 awake, 30 s steps).
        StringBuilder pinned = new StringBuilder();
        for (int seed : new int[] {22, 73, 145, 152, 294, 1131, 1607, 2338}) {
            Random r = new Random(seed);
            double hours = 2 + r.nextInt(8), ss = 1_760_000_000, se = ss + hours * 3600;
            TS shr = new TS(), shrv = new TS(), smo = new TS();
            double depth = r.nextDouble() * 14, period = 70 + r.nextInt(50), base = 20 + r.nextInt(50), noise = r.nextDouble() * 3;
            double quietEdge = r.nextBoolean() ? 0 : 0.02 + r.nextDouble() * 0.05, busy = r.nextDouble() * 0.06;
            for (double t = ss; t < se; t += 300) {
                double f = (t - ss) / (se - ss), m = (t - ss) / 60;
                double cyc = StrictMath.sin(2 * Math.PI * m / period);
                shr.add(t, 60 - depth * Math.max(0, cyc) * (1.2 - f) - 4 * f + r.nextGaussian() * noise);
                shrv.add(t, base + 20 * f + 12 * Math.max(0, -cyc) + r.nextGaussian() * 2 * noise);
            }
            for (double t = ss; t < se; t += 60) {
                double f = (t - ss) / (se - ss);
                smo.add(t, (f < quietEdge || f > 1 - quietEdge) ? 10 : (r.nextDouble() < busy ? 1 + r.nextInt(9) : 0));
            }
            pinned.append(pinned.length() > 0 ? " / " : "").append(rle(Stager.stageFromSignals(ss, se, shr, shrv, smo)));
        }
        eq("stager output for eight fixed synthetic nights is unchanged", pinned.toString(),
                "2x27 4x3 2x7 1x36 2x4 4x3 2x21 4x3 2x106 1x26 2x39 4x3 2x53 3x4 2x19 3x6 2x9 4x5 2x7 4x3 2x34 1x22 2x87 3x63 2x56 3x7 2x19 3x15 2x26 3x126 2x1" + " / "
                + "2x51 1x35 2x178 1x17 2x4 4x3 2x16 3x31 2x76 1x15 2x53 3x64 2x14 4x3 2x45 4x3 2x39 4x3 3x89 4x3 2x41 4x3 2x7 4x3 2x7 3x36 2x1" + " / "
                + "2x57 1x56 2x58 4x3 2x83 1x21 2x102 3x45 2x72 4x3 2x91 3x96 2x102 3x50 2x1" + " / "
                + "4x27 2x38 4x3 2x93 1x8 2x83 4x6 2x20 1x16 2x9 4x3 2x35 4x3 2x34 1x22 2x91 3x54 2x98 4x3 3x68 2x52 3x39 4x3 3x5 4x27" + " / "
                + "2x68 1x56 2x25 4x3 2x146 3x40 2x32 1x22 2x92 3x58 2x108 3x74 2x79 3x1 4x4 3x55 2x4 3x16 4x3 3x53 2x21" + " / "
                + "2x2 1x52 2x73 3x23 2x25 4x5 2x20 1x16 2x79 3x40 2x10 4x3 2x68 1x19 2x29 3x60 2x115 3x56 4x3 3x9 4x3 3x30 2x63 3x36 2x1" + " / "
                + "2x112 3x12 2x26 1x26 2x88 3x37 2x3 1x22 2x106 3x43 2x89 3x31 4x5 3x87 2x33" + " / "
                + "4x7 2x14 4x5 2x132 1x32 2x93 4x3 2x109 4x3 2x7 4x3 2x24 1x22 2x91 4x3 2x54 3x37 2x35 3x18 2x35 4x3 2x48 3x71 4x3 3x21 2x113 3x93 2x1");

        // Scores: curve breakpoints hit exactly. 90 minutes awake gives 40; with 1 wake up (97.33) restfulness is
        // the mean 68.67, shown 69; with 2 wake ups (94.67) 67.33, shown 67 (no restlessness data)
        Night rw = new Night();
        rw.wasoMin = 90;
        rw.awakenings = 1;
        eq("restfulness at 90 minutes awake and 1 wake up", Scores.sleep(rw, 0, 40).get("restfulness").score, 69.0);
        rw.awakenings = 2;
        eq("restfulness at 90 minutes awake and 2 wake ups", Scores.sleep(rw, 0, 40).get("restfulness").score, 67.0);
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        ri.hrvRecent = 85;
        ri.hrvBaseline = 100;
        ri.prevDayActivity = 0.25;
        Night.Score rs = Scores.readiness(ri);
        eq("HRV balance at 85% of usual is 70", rs.get("hrv").score, 70.0);
        eq("previous day activity at a quarter of the target is 72", rs.get("prev_activity").score, 72.0);
    }

    /** A made up ring for {@link #pairing}: holds a key (null after a factory reset) and fails where it is told. */
    static final class FakeRing implements Pairing.Ring {
        byte[] key;
        /** Where the link breaks: "serial", "write" (before the ring takes it), "afterWrite" (it took it), "auth", "after". */
        String breakAt = "";
        /** Status of a key write the ring refuses for its own reason (5 unfinished tests), or -1. */
        int refuse = -1;
        int writes, auths;
        String id = "2X0001";
        List<String> lines = new ArrayList<String>();

        void fail(String step) throws IOException {
            if (breakAt.equals(step)) throw new IOException("ring disconnected");
        }

        public String serial() throws IOException {
            fail("serial");
            return id;
        }

        public int setAuthKey(byte[] k) throws IOException {
            writes++;
            fail("write");
            if (refuse >= 0) return refuse;
            if (key != null) return 1;
            key = k.clone();
            fail("afterWrite");
            return 0;
        }

        public void authenticate(byte[] k) throws IOException {
            auths++;
            fail("auth");
            if (key == null) throw new RingClient.AuthException(Auth.IN_FACTORY_RESET, "authentication failed: " + Auth.describe(Auth.IN_FACTORY_RESET));
            if (!Arrays.equals(key, k)) throw new RingClient.AuthException(Auth.WRONG_KEY, "authentication failed: " + Auth.describe(Auth.WRONG_KEY));
        }

        public void status(String line) {
            lines.add(line);
        }

        public boolean afterPairing(boolean installed) throws InterruptedException {
            if (breakAt.equals("after")) return !installed;
            if (breakAt.equals("afterCrash")) throw new IllegalStateException("crash");
            if (breakAt.equals("afterStop")) throw new InterruptedException();
            return true;
        }
    }

    /** A made up phone store: what Prefs keeps on disk. */
    static final class FakeStore implements Pairing.Store {
        byte[] kept, saved;
        String serial, unseen, keptSerial;
        int keeps;
        boolean setupDone, keepFails, doneFails;
        int resets;
        String oldSerial;

        public byte[] kept() {
            return kept == null ? null : kept.clone();
        }

        public String keptSerial() {
            return keptSerial;
        }

        public boolean keep(byte[] k, String s) {
            if (keepFails) return false;
            check("pairing store: a kept key is never overwritten", kept == null);
            keeps++;
            kept = k.clone();
            keptSerial = s;
            return true;
        }

        public void forgetKept() {
            kept = null;
            keptSerial = null;
        }

        public void ringIs(String s) {
            if (serial != null && !serial.equals(s)) resets++;
        }

        public boolean done(byte[] k, String s, String message, boolean dropKept) {
            if (doneFails) return false;
            saved = k.clone();
            serial = s;
            setupDone = true;
            unseen = message;
            if (dropKept) forgetKept();
            return true;
        }
    }

    static String pairRun(FakeRing r, FakeStore st, byte[] typed, boolean install) {
        try {
            return Pairing.pair(typed, install, r, st);
        } catch (Exception e) {
            return "FAIL " + e.getMessage();
        }
    }

    /** The owner's rule (issue #218): the ring never holds a key written here that the phone does not hold. */
    static void keyInvariant(String name, FakeRing r, FakeStore st, byte[] before) {
        boolean written = r.key != null && (before == null || !Arrays.equals(before, r.key));
        check(name + ": the phone holds the key the ring took",
                !written || Arrays.equals(r.key, st.kept) || Arrays.equals(r.key, st.saved));
        check(name + ": a saved key comes with set up", st.saved == null || st.setupDone && st.unseen != null);
    }

    /** Issue #218: pairing stores the key before the ring can take it, and saves key and set up together. */
    static void pairing() {
        // a reset ring, new key, all fine
        FakeRing r = new FakeRing();
        FakeStore st = new FakeStore();
        String m = pairRun(r, st, null, true);
        eq("pairing: new key pairs", m, "Paired with 2X0001");
        check("pairing: the ring holds the saved key", Arrays.equals(r.key, st.saved) && st.kept == null);
        check("pairing: set up and the key page not shown yet", st.setupDone && "Paired with 2X0001".equals(st.unseen));
        eq("pairing: status lines", r.lines.toString(), "[Installing the key, Verifying the key]");
        keyInvariant("pairing: fine", r, st, null);

        // the link breaks at each step of a new key
        String[] steps = {"serial", "write", "afterWrite", "auth"};
        for (String step : steps) {
            r = new FakeRing();
            r.breakAt = step;
            st = new FakeStore();
            m = pairRun(r, st, null, true);
            check("pairing: broken at " + step + " fails", m.startsWith("FAIL"));
            check("pairing: broken at " + step + " is not set up", !st.setupDone && st.saved == null);
            keyInvariant("pairing: broken at " + step, r, st, null);
            // a try again with the link back: the kept key is used again, the ring ends up with the saved key
            byte[] keptBefore = st.kept;
            r.breakAt = "";
            String again = pairRun(r, st, null, true);
            eq("pairing: try again after " + step, again, "Paired with 2X0001");
            check("pairing: try again after " + step + " saves the ring's key", Arrays.equals(r.key, st.saved) && st.kept == null);
            if (keptBefore != null) check("pairing: try again after " + step + " uses the kept key", Arrays.equals(keptBefore, st.saved));
            keyInvariant("pairing: try again after " + step, r, st, null);
        }

        // the ring took the key but the check failed: retry reuses it (status 1, same key accepted)
        r = new FakeRing();
        r.breakAt = "auth";
        st = new FakeStore();
        pairRun(r, st, null, true);
        check("pairing: the ring took the key, the phone kept it", r.key != null && Arrays.equals(r.key, st.kept));
        r.breakAt = "";
        int writes = r.writes;
        eq("pairing: status 1 with the kept key pairs", pairRun(r, st, null, true), "Paired with 2X0001");
        eq("pairing: the retry wrote once more", r.writes, writes + 1);

        // the phone cannot store the key: nothing goes to the ring
        r = new FakeRing();
        st = new FakeStore();
        st.keepFails = true;
        m = pairRun(r, st, null, true);
        eq("pairing: key not kept", m, "FAIL " + Pairing.KEY_NOT_KEPT);
        check("pairing: key not kept writes nothing", r.writes == 0 && r.key == null);
        eq("pairing: key not kept sentence", SyncError.plain(Pairing.KEY_NOT_KEPT, SyncError.Task.PAIR),
                "This phone could not save the key, so nothing was written to the ring. Free some storage and try again.");

        // the ring took the key, saving failed: the kept key still holds it
        r = new FakeRing();
        st = new FakeStore();
        st.doneFails = true;
        m = pairRun(r, st, null, true);
        eq("pairing: key not saved", m, "FAIL " + Pairing.KEY_NOT_SAVED);
        keyInvariant("pairing: key not saved", r, st, null);
        check("pairing: key not saved keeps the kept key", Arrays.equals(r.key, st.kept) && !st.setupDone);
        eq("pairing: key not saved sentence", SyncError.plain(Pairing.KEY_NOT_SAVED, SyncError.Task.PAIR),
                "The ring took the key, but this phone could not save it. Free some storage and try again.");

        // a ring with someone else's key: the kept key goes, the sentence stays the one of today
        byte[] other = Hex.parseKey("00112233445566778899aabbccddeeff");
        r = new FakeRing();
        r.key = other.clone();
        st = new FakeStore();
        st.kept = Hex.parseKey("ffeeddccbbaa99887766554433221100");
        st.keptSerial = "2X0001";
        m = pairRun(r, st, null, true);
        eq("pairing: ring with another key", m, "FAIL " + Pairing.KEY_EXISTS);
        check("pairing: another key drops the kept key", st.kept == null && st.saved == null);
        check("pairing: another key stays on the ring", Arrays.equals(r.key, other));
        eq("pairing: already has a key sentence", SyncError.plain(Pairing.KEY_EXISTS, SyncError.Task.PAIR), Pairing.KEY_EXISTS);
        // a typed key the ring already has, with Install ticked: verified and paired
        r = new FakeRing();
        r.key = other.clone();
        st = new FakeStore();
        eq("pairing: install a key the ring already has", pairRun(r, st, other, true), "Paired with 2X0001");
        check("pairing: that key is saved", Arrays.equals(st.saved, other));
        // a typed key without install: no key is kept, nothing is written
        r = new FakeRing();
        r.key = other.clone();
        st = new FakeStore();
        eq("pairing: existing key", pairRun(r, st, other, false), "Paired with 2X0001");
        check("pairing: existing key writes and keeps nothing", r.writes == 0 && st.kept == null && Arrays.equals(st.saved, other));
        // a wrong typed key: no change on the phone
        st = new FakeStore();
        m = pairRun(r, st, Hex.parseKey("ffeeddccbbaa99887766554433221100"), false);
        check("pairing: wrong typed key fails", m.startsWith("FAIL authentication failed"));
        check("pairing: wrong typed key saves nothing", st.saved == null && st.kept == null && !st.setupDone);
        eq("pairing: wrong typed key sentence starts", SyncError.kind(m.substring(5)), SyncError.Kind.KEY_REFUSED);
        // a reset ring with a typed key and no install
        r = new FakeRing();
        st = new FakeStore();
        m = pairRun(r, st, other, false);
        eq("pairing: reset ring without install", SyncError.kind(m.substring(5)), SyncError.Kind.RING_RESET);
        // a key kept from before stays when the ring refuses for its own reason, and is used again
        r = new FakeRing();
        r.refuse = 5;
        st = new FakeStore();
        st.kept = other.clone();
        st.keptSerial = "2X0001";
        m = pairRun(r, st, null, true);
        eq("pairing: unfinished factory tests", m, "FAIL The ring reports unfinished factory tests (status 5).");
        check("pairing: refused keeps the kept key", Arrays.equals(st.kept, other));
        eq("pairing: refused other status", Pairing.refusal(7), "The ring refused the key (status 0x07)");
        eq("pairing: refusal sentence", SyncError.kind(Pairing.refusal(7)), SyncError.Kind.KEY_NOT_TAKEN);
        r.refuse = -1;
        eq("pairing: a reset ring takes the kept key", pairRun(r, st, null, true), "Paired with 2X0001");
        check("pairing: the kept key became the saved key", Arrays.equals(st.saved, other) && Arrays.equals(r.key, other));

        // after the key is saved, a lost link no longer fails the pairing
        r = new FakeRing();
        r.breakAt = "after";
        st = new FakeStore();
        m = pairRun(r, st, null, true);
        eq("pairing: measurements not switched on", m, "Paired with 2X0001" + Pairing.MEASUREMENTS_OFF);
        check("pairing: still set up", st.setupDone && Arrays.equals(r.key, st.saved));
        r = new FakeRing();
        r.breakAt = "afterCrash";
        st = new FakeStore();
        eq("pairing: a crash after saving still pairs", pairRun(r, st, null, true), "Paired with 2X0001" + Pairing.MEASUREMENTS_OFF);
        r = new FakeRing();
        r.breakAt = "afterStop";
        st = new FakeStore();
        eq("pairing: a stop after saving still pairs", pairRun(r, st, null, true), "Paired with 2X0001" + Pairing.MEASUREMENTS_OFF);
        check("pairing: the stop is passed on", Thread.interrupted() && st.setupDone);
        r = new FakeRing();
        r.key = other.clone();
        r.breakAt = "afterCrash";
        st = new FakeStore();
        eq("pairing: existing key, nothing to switch on", pairRun(r, st, other, false), "Paired with 2X0001");

        // a different ring than the saved one starts a fresh history before the key is saved
        r = new FakeRing();
        st = new FakeStore();
        st.serial = "2X9999";
        pairRun(r, st, null, true);
        eq("pairing: a different ring resets once", st.resets, 1);
        r = new FakeRing();
        st = new FakeStore();
        st.serial = "2X0001";
        pairRun(r, st, null, true);
        eq("pairing: the same ring keeps its history", st.resets, 0);

        // decisions
        eq("pairing: status 0", Pairing.afterSetKey(0), Pairing.Next.VERIFY);
        eq("pairing: status 1", Pairing.afterSetKey(1), Pairing.Next.VERIFY_OR_TAKEN);
        eq("pairing: status 5", Pairing.afterSetKey(5), Pairing.Next.REFUSED);
        eq("pairing: refusal 1", Pairing.refusal(1), Pairing.KEY_EXISTS);
        byte[] a = {1}, b = {2}, c = {3};
        check("pairing: typed key wins", Pairing.keyFor(a, b, c) == a);
        check("pairing: kept key before a new one", Pairing.keyFor(null, b, c) == b);
        check("pairing: new key without a kept one", Pairing.keyFor(null, null, c) == c);
        // a new key is a new random key each time
        r = new FakeRing();
        st = new FakeStore();
        pairRun(r, st, null, true);
        FakeRing r2 = new FakeRing();
        FakeStore st2 = new FakeStore();
        pairRun(r2, st2, null, true);
        check("pairing: two new keys differ", st.saved != null && st2.saved != null && !Arrays.equals(st.saved, st2.saved));
        pairingKeptRings();
        check("pairing: user texts have no dashes", !Pairing.KEPT_TEXT.contains(" - ") && !Pairing.KEPT_NOTE.contains(" - ")
                && !Pairing.MEASUREMENTS_OFF.contains(" - "));
    }

    /** A ring holds a key the app made: the phone must hold it (kept or saved). {@code typed} is the person's own key. */
    static void keyInvariantAll(String name, FakeRing[] rings, byte[][] before, FakeStore st, byte[] typed) {
        for (int i = 0; i < rings.length; i++) {
            FakeRing r = rings[i];
            boolean madeHere = r.key != null && !Arrays.equals(r.key, before[i]) && !Arrays.equals(r.key, typed);
            check(name + ": the phone holds the key ring " + r.id + " took",
                    !madeHere || Arrays.equals(r.key, st.kept) || Arrays.equals(r.key, st.saved));
        }
        check(name + ": a saved key comes with set up", st.saved == null || st.setupDone && st.unseen != null);
    }

    /** Review of #247: the kept key is never overwritten, and only the ring it was meant for can drop it. */
    static void pairingKeptRings() {
        byte[] typed = Hex.parseKey("0707070707070707070707070707a0a0");
        byte[] stranger = Hex.parseKey("5555555555555555555555555555aaaa");
        String[] steps = {"", "serial", "write", "afterWrite", "auth", "after"};
        for (String step : steps) {
            // a cut off try left key K on ring A and kept on the phone; then the person types a key and ticks Install
            FakeRing a = new FakeRing();
            a.breakAt = "auth";
            FakeStore st = new FakeStore();
            pairRun(a, st, null, true);
            byte[] k = st.kept;
            check("kept rings: the cut off try kept K for ring A", k != null && Arrays.equals(a.key, k) && "2X0001".equals(st.keptSerial));
            a.breakAt = step;
            String m = pairRun(a, st, typed, true);
            String name = "kept rings: typed install on A, broken at '" + step + "'";
            keyInvariantAll(name, new FakeRing[] {a}, new byte[][] {null}, st, typed);
            check(name + ": K still on the phone", Arrays.equals(st.kept, k) || Arrays.equals(st.saved, k));
            if (step.isEmpty() || step.equals("after")) {
                check(name + ": paired with the kept key the ring holds", m.startsWith("Paired with") && Arrays.equals(st.saved, k));
                check(name + ": the kept key went with the pairing of its ring", st.kept == null);
            }

            // ring B (another serial) answers instead, it holds someone else's key: K for A stays
            a = new FakeRing();
            a.breakAt = "auth";
            st = new FakeStore();
            pairRun(a, st, null, true);
            k = st.kept;
            FakeRing b = new FakeRing();
            b.id = "2X0002";
            b.key = stranger.clone();
            b.breakAt = step;
            m = pairRun(b, st, null, true);
            name = "kept rings: new key on ring B with another key, broken at '" + step + "'";
            keyInvariantAll(name, new FakeRing[] {a, b}, new byte[][] {null, stranger}, st, null);
            check(name + ": K for A stays kept", Arrays.equals(st.kept, k) && "2X0001".equals(st.keptSerial));
            check(name + ": fails " + m, m.startsWith("FAIL"));

            // a typed key with Install on ring B: K for A stays kept
            b = new FakeRing();
            b.id = "2X0002";
            b.key = stranger.clone();
            b.breakAt = step;
            m = pairRun(b, st, typed, true);
            name = "kept rings: typed install on ring B, broken at '" + step + "'";
            keyInvariantAll(name, new FakeRing[] {a, b}, new byte[][] {null, stranger}, st, typed);
            check(name + ": K for A stays kept", Arrays.equals(st.kept, k) && "2X0001".equals(st.keptSerial));

            // a typed key ring B already holds, without install: paired, K for A stays kept
            b = new FakeRing();
            b.id = "2X0002";
            b.key = typed.clone();
            b.breakAt = step;
            m = pairRun(b, st, typed, false);
            name = "kept rings: typed key on ring B, broken at '" + step + "'";
            keyInvariantAll(name, new FakeRing[] {a, b}, new byte[][] {null, typed}, st, typed);
            check(name + ": K for A stays kept", Arrays.equals(st.kept, k) && "2X0001".equals(st.keptSerial));

            // a new key on a reset ring B reuses K, so both rings share it; K for A stays kept
            FakeStore st2 = new FakeStore();
            FakeRing a2 = new FakeRing();
            a2.breakAt = "auth";
            pairRun(a2, st2, null, true);
            byte[] k2 = st2.kept;
            FakeRing b2 = new FakeRing();
            b2.id = "2X0002";
            b2.breakAt = step;
            m = pairRun(b2, st2, null, true);
            name = "kept rings: new key on reset ring B, broken at '" + step + "'";
            keyInvariantAll(name, new FakeRing[] {a2, b2}, new byte[][] {null, null}, st2, null);
            check(name + ": K for A stays kept", Arrays.equals(st2.kept, k2) && "2X0001".equals(st2.keptSerial));
            if (b2.key != null) check(name + ": ring B got K", Arrays.equals(b2.key, k2));
            eq(name + ": nothing was kept twice", st2.keeps, 1);
        }
        // the ring the kept key was meant for holds another key: it goes (the ring said so itself)
        FakeRing a = new FakeRing();
        a.breakAt = "auth";
        FakeStore st = new FakeStore();
        pairRun(a, st, null, true);
        a.key = stranger.clone(); // reset and keyed elsewhere meanwhile
        a.breakAt = "";
        eq("kept rings: ring A with another key", pairRun(a, st, typed, true), "FAIL " + Pairing.KEY_EXISTS);
        check("kept rings: A's wrong key answer drops the kept key", st.kept == null && st.keptSerial == null);
        check("kept rings: a typed key is not kept while another key is", st.keeps == 1);
        // a clear refusal keeps the kept key: it may still be on the ring from before
        a = new FakeRing();
        a.breakAt = "auth";
        st = new FakeStore();
        pairRun(a, st, null, true);
        a.breakAt = "";
        a.refuse = 5;
        pairRun(a, st, null, true);
        check("kept rings: a refusal keeps the kept key", st.kept != null);
        eq("kept rings: caption with the ring", Pairing.keptFor("2X0001"), "Kept key, meant for ring 2X0001");
        eq("kept rings: caption without one", Pairing.keptFor(null), "Kept key");
        eq("kept rings: sentence adds a full stop", Pairing.sentence("Paired with 2X0001"), "Paired with 2X0001.");
        eq("kept rings: sentence keeps one", Pairing.sentence("Paired with 2X0001" + Pairing.MEASUREMENTS_OFF),
                "Paired with 2X0001" + Pairing.MEASUREMENTS_OFF);
        check("kept rings: measurements note ends with a full stop", Pairing.MEASUREMENTS_OFF.endsWith("."));
        check("kept rings: the card says may have", Pairing.KEPT_TEXT.startsWith("Your last try may have written"));
    }

    /** Issue #218: the pairing result reaches the Setup screen shown now, or waits for the next one. */
    static void pairingRun() {
        Pairing.Run<String> run = new Pairing.Run<String>();
        check("pairing run: idle at first", !run.running() && run.take() == null);
        run.attach("setup");
        check("pairing run: start", run.start(1));
        check("pairing run: a second start is refused", !run.start(0));
        eq("pairing run: the card that started it", run.card(), 1);
        // the phone is turned: Android destroys the old screen first, then builds the new one
        run.detach("setup", false);
        eq("pairing run: ends with no screen attached", run.finish(true, "Paired with 2X0001"), null);
        check("pairing run: ended", !run.running());
        run.attach("turned setup");
        Pairing.Result res = run.take();
        check("pairing run: the new screen gets the result", res != null && res.ok && "Paired with 2X0001".equals(res.message));
        check("pairing run: only once", run.take() == null);
        // the new screen attaches before the old one goes (Back from a screen above)
        check("pairing run: start again", run.start(0));
        run.attach("newer setup");
        run.detach("turned setup", false);
        eq("pairing run: tells the screen attached now", run.finish(false, "The ring could not be reached."), "newer setup");
        res = run.take();
        check("pairing run: a failure is handed over", res != null && !res.ok && res.message.startsWith("The ring could not"));
        eq("pairing run: card of the last run", run.card(), 0);
        // Setup closed for good while the result waited: it is dropped (a success is saved on the phone anyway)
        run.start(1);
        run.finish(false, "x");
        run.detach("newer setup", true);
        check("pairing run: closing for good drops a waiting result", run.take() == null);
        // a stranger detaching changes nothing
        run.attach("setup");
        run.start(1);
        run.finish(true, "y");
        run.detach("stranger", true);
        check("pairing run: a stranger leaves the result", run.take() != null);
        // a new start drops a result nobody took
        run.finish(false, "old");
        run.start(0);
        check("pairing run: a new start drops an old result", run.take() == null);
    }

    /** Holds {@code g}'s ring lock on its own thread until {@code go} opens (a Live session, the finder). */
    static Thread holdRing(final RingGate g, final java.util.concurrent.CountDownLatch held, final java.util.concurrent.CountDownLatch go) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                g.op.lock();
                try {
                    held.countDown();
                    go.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                } finally {
                    g.op.unlock();
                }
            }
        });
        t.start();
        return t;
    }

    /** Issue #219: a swap of the database runs only while no ring task can store, waits holding nothing, never forever. */
    static void ringGate() throws Exception {
        final RingGate g = new RingGate();
        final int[] ran = {0};
        final List<Integer> why = new ArrayList<Integer>();
        Runnable swap = new Runnable() {
            @Override
            public void run() {
                ran[0]++;
                check("ring gate: the swap holds the ring", g.op.isHeldByCurrentThread());
            }
        };
        RingGate.Wait note = new RingGate.Wait() {
            @Override
            public void waiting(int w) {
                synchronized (why) {
                    why.add(w);
                }
            }
        };
        check("ring gate: free, the swap runs at once", free(g, swap, 0, null, note) && ran[0] == 1 && why.isEmpty() && !g.op.isLocked());

        // a ring task holds the ring: the swap waits, says why once, and runs after it gave the ring back
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), go = new java.util.concurrent.CountDownLatch(1);
        Thread live = holdRing(g, held, go);
        held.await();
        final boolean[] result = {false};
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                result[0] = free(g, new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, null, new RingGate.Wait() {
                    @Override
                    public void waiting(int w) {
                        synchronized (why) {
                            why.add(w);
                        }
                    }
                });
            }
        });
        waiter.start();
        Thread.sleep(250);
        check("ring gate: a held ring makes the swap wait", ran[0] == 1 && waiter.isAlive());
        go.countDown();
        waiter.join(10000);
        live.join(10000);
        check("ring gate: after the ring task the swap runs", result[0] && ran[0] == 2 && !g.op.isLocked());
        eq("ring gate: the wait was told once, for the ring task (not a sync)", why.toString(), "[" + RingGate.FOR_RING + "]");

        // a counted sync (also one that waits for the ring or analyses after its download): the swap waits for its end
        why.clear();
        g.syncs.incrementAndGet();
        final boolean[] syncResult = {false};
        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                syncResult[0] = free(g, new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, null, null);
            }
        });
        waiter.start();
        Thread.sleep(250);
        check("ring gate: a sync under way makes the swap wait, the ring stays free for it", ran[0] == 2 && waiter.isAlive() && !g.op.isLocked());
        g.syncs.decrementAndGet();
        waiter.join(10000);
        check("ring gate: after the sync the swap runs", syncResult[0] && ran[0] == 3);

        // an analysis runs: the swap waits for it without the ring, so a ring task can take it meanwhile
        why.clear();
        final boolean[] analysing = {true};
        RingGate.Busy analysis = new RingGate.Busy() {
            @Override
            public boolean busy() {
                return analysing[0];
            }
        };
        final RingGate.Busy analysisF = analysis;
        final RingGate.Wait noteF = note;
        final boolean[] otherResult = {false};
        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                otherResult[0] = free(g, new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, analysisF, noteF);
            }
        });
        waiter.start();
        Thread.sleep(250);
        boolean free = g.op.tryLock();
        if (free) g.op.unlock();
        check("ring gate: waiting for an analysis holds no ring lock", free && ran[0] == 3 && waiter.isAlive());
        analysing[0] = false;
        waiter.join(10000);
        check("ring gate: after the analysis the swap runs", otherResult[0] && ran[0] == 4);
        eq("ring gate: the wait was told once, for the analysis", why.toString(), "[" + RingGate.FOR_OTHER + "]");

        // the reason changes: told again
        why.clear();
        analysing[0] = true;
        g.syncs.incrementAndGet();
        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                free(g, new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, analysisF, noteF);
            }
        });
        waiter.start();
        Thread.sleep(250);
        g.syncs.decrementAndGet();
        Thread.sleep(250);
        analysing[0] = false;
        waiter.join(10000);
        eq("ring gate: a sync, then the analysis after it", why.toString(), "[" + RingGate.FOR_SYNC + ", " + RingGate.FOR_OTHER + "]");
        check("ring gate: then the swap ran", ran[0] == 5);

        // nothing waits forever: the time runs out, the swap does not run, the ring stays with its task
        held = new java.util.concurrent.CountDownLatch(1);
        go = new java.util.concurrent.CountDownLatch(1);
        live = holdRing(g, held, go);
        held.await();
        long t0 = System.currentTimeMillis();
        boolean late = free(g, swap, 300, null, null);
        long took = System.currentTimeMillis() - t0;
        check("ring gate: the time ran out, the swap did not run", !late && ran[0] == 5 && took >= 290 && took < 5000);
        check("ring gate: no time to wait, a held ring refuses at once", !free(g, swap, 0, null, null) && ran[0] == 5);
        g.syncs.incrementAndGet();
        check("ring gate: no time to wait, a sync refuses at once", !free(g, swap, 0, null, null) && ran[0] == 5);
        g.syncs.decrementAndGet();

        // interrupted while waiting: refused, the interrupt kept
        final boolean[] interrupted = {false, true};
        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                interrupted[1] = free(g, new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, null, null);
                interrupted[0] = Thread.currentThread().isInterrupted();
            }
        });
        waiter.start();
        Thread.sleep(150);
        waiter.interrupt();
        waiter.join(10000);
        check("ring gate: interrupted while waiting, the swap does not run and the interrupt stays", interrupted[0] && !interrupted[1] && ran[0] == 5);
        go.countDown();
        live.join(10000);

        // a swap that fails gives the ring back, its error reaches the caller
        String error = null;
        try {
            free(g, new Runnable() {
                @Override
                public void run() {
                    throw new IllegalStateException("copy failed");
                }
            }, 0, null, null);
        } catch (IllegalStateException e) {
            error = e.getMessage();
        }
        check("ring gate: a failed swap reaches the caller and frees the ring", "copy failed".equals(error) && !g.op.isLocked());

        // review of #248: why it gave up, so the sentence names what held it up
        held = new java.util.concurrent.CountDownLatch(1);
        go = new java.util.concurrent.CountDownLatch(1);
        live = holdRing(g, held, go);
        held.await();
        eq("ring gate: Live or the finder held the ring, not a sync", g.whenFree(swap, 150, null, null, null), RingGate.FOR_RING);
        go.countDown();
        live.join(10000);
        g.syncs.incrementAndGet();
        eq("ring gate: a sync held it up", g.whenFree(swap, 150, null, null, null), RingGate.FOR_SYNC);
        g.syncs.decrementAndGet();
        analysing[0] = true;
        eq("ring gate: an analysis held it up", g.whenFree(swap, 150, analysisF, null, null), RingGate.FOR_OTHER);
        analysing[0] = false;
        eq("ring gate: free again, it runs", g.whenFree(swap, 0, null, null, null), RingGate.RAN);
        check("ring gate: and ran the swap", ran[0] == 6);

        // review of #248: the person stops the wait (Back on the demo dialog, Setup left): nothing runs, nothing held
        final boolean[] stopNow = {false};
        final RingGate.Stop stopper = new RingGate.Stop() {
            @Override
            public boolean stopped() {
                return stopNow[0];
            }
        };
        g.syncs.incrementAndGet();
        final int[] stopResult = {-1};
        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                stopResult[0] = g.whenFree(new Runnable() {
                    @Override
                    public void run() {
                        ran[0]++;
                    }
                }, 30000, null, null, stopper);
            }
        });
        waiter.start();
        Thread.sleep(200);
        check("ring gate: a stoppable wait waits like any other", waiter.isAlive() && ran[0] == 6);
        stopNow[0] = true;
        waiter.join(5000);
        g.syncs.decrementAndGet();
        check("ring gate: stopped, the swap did not run", !waiter.isAlive() && stopResult[0] == RingGate.STOPPED && ran[0] == 6 && !g.op.isLocked());
        eq("ring gate: a stop before anything waits refuses too", g.whenFree(swap, 30000, null, null, stopper), RingGate.STOPPED);
        stopNow[0] = false;
        eq("ring gate: a stop that is not set changes nothing", g.whenFree(swap, 0, null, null, stopper), RingGate.RAN);
        // a stop is looked at only before the swap: one that comes while it runs does not cut it off
        final int[] during = {0};
        eq("ring gate: a stop during the swap does not cut it off", g.whenFree(new Runnable() {
            @Override
            public void run() {
                stopNow[0] = true;
                during[0]++;
            }
        }, 0, null, null, stopper), RingGate.RAN);
        check("ring gate: the swap ran to its end once", during[0] == 1);

        // the texts: one per reason, plain, no dashes; the demo's fit a toast (issue #89)
        eq("ring gate: wait line for a sync", RingGate.waitLine(RingGate.FOR_SYNC), "Waiting for the sync with your ring to finish");
        eq("ring gate: wait line for Live or the finder does not say sync", RingGate.waitLine(RingGate.FOR_RING), "Waiting for the ring to finish its current task");
        eq("ring gate: wait line for an analysis", RingGate.waitLine(RingGate.FOR_OTHER), RingGate.WAIT_ANALYSIS);
        java.util.Set<String> restoreTexts = new java.util.HashSet<String>(), demoTexts = new java.util.HashSet<String>();
        for (int w : new int[] {RingGate.FOR_SYNC, RingGate.FOR_RING, RingGate.FOR_OTHER, RingGate.STOPPED}) {
            String rt = RingGate.restoreRefused(w), dt = RingGate.demoRefused(w);
            restoreTexts.add(rt);
            demoTexts.add(dt);
            for (String t : new String[] {rt, dt, RingGate.waitLine(w)}) {
                check("ring gate: no dash as punctuation in \"" + t + "\"", !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
            }
            check("ring gate: the restore refusal says nothing was restored: " + rt, rt.contains("nothing was restored"));
            check("ring gate: the demo refusal fits a toast: " + dt, Texts.fitsToast(dt) && dt.contains("demo mode stays off"));
        }
        check("ring gate: each reason has its own sentence", restoreTexts.size() == 4 && demoTexts.size() == 4);
        check("ring gate: only the sync's sentence names a sync", RingGate.restoreRefused(RingGate.FOR_SYNC).contains("sync")
                && !RingGate.restoreRefused(RingGate.FOR_RING).contains("sync") && !RingGate.restoreRefused(RingGate.FOR_OTHER).contains("sync")
                && !RingGate.demoRefused(RingGate.FOR_RING).contains("sync") && !RingGate.demoRefused(RingGate.FOR_OTHER).contains("sync"));
        check("ring gate: the app has one gate", RingGate.RING == RingGate.RING && RingGate.SWAP_WAIT_MS == 600000L);
    }

    static boolean free(RingGate g, Runnable r, long ms, RingGate.Busy other, RingGate.Wait wait) {
        return g.whenFree(r, ms, other, wait, null) == RingGate.RAN;
    }

    /** Review of #248: Setup's restore and pairing never run at once, a second restore waits for the first. */
    static void setupTasks() {
        SetupTasks t = new SetupTasks();
        check("setup tasks: nothing runs, pairing may start", t.pairRefusal() == null && !t.restoring());
        eq("setup tasks: a restore while pairing runs is refused", t.startRestore(true), SetupTasks.RESTORE_REFUSED);
        check("setup tasks: the refused restore does not count", !t.restoring() && t.pairRefusal() == null);
        check("setup tasks: a restore starts", t.startRestore(false) == null && t.restoring());
        eq("setup tasks: pairing during the restore (also while it waits for a sync) is refused", t.pairRefusal(), SetupTasks.PAIR_REFUSED);
        eq("setup tasks: a second restore is refused", t.startRestore(false), SetupTasks.RESTORE_RUNS);
        t.restoreEnded();
        check("setup tasks: after the restore pairing may start", t.pairRefusal() == null && !t.restoring());
        t.restoreEnded();
        check("setup tasks: ending twice is harmless", !t.restoring() && t.startRestore(false) == null);
        t.restoreEnded();
        for (String s : new String[] {SetupTasks.PAIR_REFUSED, SetupTasks.RESTORE_REFUSED, SetupTasks.RESTORE_RUNS}) {
            check("setup tasks: plain, no dash, fits a toast: " + s, Texts.fitsToast(s) && !s.contains(" - "));
        }
    }
    // ---------------------------------------------------------------------------- issues #220 and #194

    /** A made up events table for the dating tests: the raw columns and the stored segment and date. */
    static final class Table220 {
        final List<long[]> rows = new ArrayList<long[]>(); // {id, epoch, ds, anchor, phone, time_sync}
        final List<Integer> seg = new ArrayList<Integer>();
        final List<Double> unix = new ArrayList<Double>();
        Dating.State state;
        long nextId = 1;

        void add(long epoch, long ds, long anchor, boolean phone, boolean timeSync) {
            rows.add(new long[] {nextId++, epoch, ds, anchor, phone ? 1 : 0, timeSync ? 1 : 0});
            seg.add(-1);
            unix.add(Double.NaN);
        }

        Dating.Rows rows(long fromId, long toId, boolean timeSyncOnly) {
            Dating.Rows r = new Dating.Rows(4);
            for (long[] x : rows) {
                if (x[0] < fromId || x[0] > toId || (timeSyncOnly && x[5] == 0)) continue;
                r.add(x[0], x[1], x[2], x[3], x[4] == 1);
            }
            return r;
        }

        /** Dating everything: {seg, unix bits} per row. */
        List<long[]> all(long now) {
            Dating.Dated d = Dating.all(rows(0, Long.MAX_VALUE, false), now);
            List<long[]> out = new ArrayList<long[]>();
            for (int k = 0; k < rows.size(); k++) out.add(new long[] {d.seg[k], Double.doubleToLongBits(d.unix[k])});
            return out;
        }

        List<long[]> stored() {
            List<long[]> out = new ArrayList<long[]>();
            for (int k = 0; k < rows.size(); k++) out.add(new long[] {seg.get(k), Double.doubleToLongBits(unix.get(k))});
            return out;
        }

        /** What Db.rebuildTime does with this table: the step when it can, else everything. Null, or why everything. */
        String date(long now) {
            String why = state == null ? "no state" : null;
            Dating.Step st = null;
            if (why == null) {
                long firstLast = state.segs.isEmpty() ? 0 : state.segs.get(state.segs.size() - 1).firstId;
                Dating.Rows fresh = rows(state.lastId + 1, Long.MAX_VALUE, false);
                st = Dating.step(state, now, fresh, rows(firstLast, state.lastId, true));
                why = st.full;
            }
            if (why != null) {
                Dating.Dated d = Dating.all(rows(0, Long.MAX_VALUE, false), now);
                for (int k = 0; k < rows.size(); k++) {
                    seg.set(k, d.seg[k]);
                    unix.set(k, d.unix[k]);
                }
                state = d.state;
                return why;
            }
            for (int k = 0; k < rows.size(); k++) {
                long[] x = rows.get(k);
                if (x[0] > state.lastId || seg.get(k) != st.lastNo || x[2] < st.fromDs || st.fromDs == Long.MAX_VALUE) continue;
                unix.set(k, st.count(unix.get(k), false, st.dateOld(x[2])));
            }
            int i = 0;
            for (int k = 0; k < rows.size(); k++) {
                if (rows.get(k)[0] <= state.lastId) continue;
                seg.set(k, st.newSeg[i]);
                unix.set(k, st.count(unix.get(k), true, st.newUnix[i]));
                i++;
            }
            double[] range = null;
            if (st.needsRange()) {
                range = new double[] {Double.NaN, Double.NaN};
                for (double u : unix) {
                    if (Double.isNaN(u)) continue;
                    if (!(u >= range[0])) range[0] = u;
                    if (!(u <= range[1])) range[1] = u;
                }
            }
            state = st.next(nextId - 1, 0, range);
            return null;
        }
    }

    /**
     * Issue #220: dating only the new part gives every row the segment and date bits of dating everything, on many
     * made up histories (syncs of random length, reboots with and without a new epoch, late, future and pre 2015 time
     * records, a phone clock set back, phone anchors of an earlier boot, rows stored without dating, time passing).
     */
    static void dating220() {
        int steps = 0, fast = 0, wrong = 0, wrongState = 0, wrongRange = 0;
        Map<String, Integer> why = new TreeMap<String, Integer>();
        for (int seed = 1; seed <= 1500; seed++) {
            Random r = new Random(seed * 7919L);
            Table220 t = new Table220();
            long now = 1_780_000_000L + r.nextInt(86400);
            long epoch = 1, ds = 1000 + r.nextInt(100000), base = now - ds / 10, bootFirst = ds;
            List<long[]> boots = new ArrayList<long[]>();
            for (int k = 0; k < 14; k++) {
                int what = r.nextInt(16);
                boolean date = true;
                if (what == 0 || what == 1) {
                    boots.add(new long[] {epoch, bootFirst, ds});
                    if (what == 0) epoch++;
                    ds = 100 + r.nextInt(5000);
                    bootFirst = ds;
                    base = now - ds / 10;
                } else if (what == 2 && ds - bootFirst > 100) {
                    long at = bootFirst + r.nextInt((int) Math.min(1 << 30, ds - bootFirst));
                    t.add(epoch, at, base + at / 10 + 3, false, true);
                } else if (what == 3) {
                    t.add(epoch, ds, now + 3600L * (5 + r.nextInt(8)), false, true);
                } else if (what == 4) {
                    now -= 3600L * (1 + r.nextInt(8));
                } else if (what == 5 && !boots.isEmpty()) {
                    long[] b = boots.get(r.nextInt(boots.size()));
                    t.add(b[0], b[2] - r.nextInt(50), now, true, true);
                } else if (what == 6) {
                    t.add(epoch, ds, 1_400_000_000L, false, true);
                } else if (what == 7) {
                    long dt = r.nextBoolean() ? -300 : 600 + r.nextInt(20 * 3600);
                    now += dt;
                    if (dt > 0) ds += dt * 10;
                } else if (what == 8) {
                    date = false;
                } else if (what == 9) {
                    // a rtc beacon of the ring now and then
                    t.add(epoch, ds, base + ds / 10, false, false);
                }
                if (what != 4 && what != 5 && what != 7) {
                    if (r.nextInt(5) > 0) {
                        long gap = r.nextInt(3) == 0 ? 0 : 1 + r.nextInt(3 * 3600);
                        ds += gap * 10;
                        now += gap;
                    }
                    if (r.nextInt(4) > 0) t.add(epoch, ds, base + ds / 10 + (r.nextBoolean() ? 0 : 30), false, true);
                    int n = 1 + r.nextInt(40);
                    long dt = 60 + r.nextInt(20000);
                    for (int i = 1; i <= n; i++) t.add(epoch, ds + i * dt * 10 / n, -1, false, false);
                    if (r.nextInt(8) == 0) t.add(epoch, Math.max(bootFirst, ds - r.nextInt(30000)), -1, false, false); // an older counter
                    ds += dt * 10;
                    now += dt;
                    if (what != 10 && what != 8) t.add(epoch, r.nextInt(6) == 0 ? ds - r.nextInt(2000) : ds, now, true, true);
                }
                if (!date) continue;
                String w = t.date(now);
                steps++;
                if (w == null) fast++;
                else why.put(w, why.containsKey(w) ? why.get(w) + 1 : 1);
                List<long[]> all = t.all(now), got = t.stored();
                for (int i = 0; i < all.size(); i++) if (!Arrays.equals(all.get(i), got.get(i))) {
                    wrong++;
                    if (wrong < 4) System.out.println("  #220 seed " + seed + " step " + k + " row " + i + " " + Arrays.toString(got.get(i))
                            + " should be " + Arrays.toString(all.get(i)) + (w == null ? " (step)" : " (" + w + ")"));
                    break;
                }
                // the state of the step is the state of dating everything (bounds aside), and survives being stored
                Dating.State ref = Dating.all(t.rows(0, Long.MAX_VALUE, false), now).state;
                Dating.State back = Dating.State.decode(t.state.encode());
                if (back == null || !back.encode().equals(t.state.encode())) wrongState++;
                if (ref.dated != t.state.dated || ref.total != t.state.total || ref.lastId != t.state.lastId || ref.segs.size() != t.state.segs.size()
                        || Double.compare(ref.dmin, t.state.dmin) != 0 || Double.compare(ref.dmax, t.state.dmax) != 0) wrongRange++;
                else for (int g = 0; g < ref.segs.size(); g++) {
                    Dating.Summary a = ref.segs.get(g), b = t.state.segs.get(g);
                    if (a.no != b.no || a.firstId != b.firstId || a.lastId != b.lastId || a.epoch != b.epoch || a.minDs != b.minDs || a.maxDs != b.maxDs
                            || a.events != b.events || a.anchored != b.anchored) {
                        wrongState++;
                        break;
                    }
                }
                List<String> ta = new ArrayList<String>(), tb = new ArrayList<String>();
                for (long[] x : ref.tail) ta.add(Arrays.toString(x));
                for (long[] x : t.state.tail) tb.add(Arrays.toString(x));
                if (!ta.equals(tb)) wrongState++;
            }
        }
        System.out.println("  #220 core: " + steps + " datings, " + fast + " of the new part only; everything dated again: " + why.size() + " reasons");
        check("#220 dating the new part gives every row the segment and date of dating everything (" + steps + " datings)", wrong == 0);
        check("#220 the state after a step is the one dating everything leaves: segments, last records", wrongState == 0);
        check("#220 the state after a step: same counts and earliest and latest date", wrongRange == 0);
        check("#220 most datings of these histories read the new part only", fast * 2 > steps);
        // stored state: damaged, other version or empty texts read as none
        // a stored phone anchor too far ahead of its boot's counters, inside its rows: the boot grows and takes it as a record
        Table220 g = new Table220();
        long gnow = 1_800_000_000L, gbase = gnow - 300_000;
        g.add(1, 1_000_000, gbase + 100_000, false, true);
        for (int i = 0; i < 10; i++) g.add(1, 1_000_000 + i * 1000, -1, false, false);
        long far = 1_009_000 + TimeLine.RESET_SLACK_DS + 5000;
        g.add(1, far, gbase + far / 10 + 50, true, true);
        g.add(1, 1_009_500, -1, false, false);
        eq("#220 stored phone anchor out of its boot's reach: first dating does everything", g.date(gnow), "no state");
        for (int i = 0; i < 4; i++) g.add(1, 1_010_000 + i * 8000, -1, false, false);
        String gw = g.date(gnow);
        List<long[]> gall = g.all(gnow), ggot = g.stored();
        boolean gsame = gall.size() == ggot.size();
        for (int i = 0; gsame && i < gall.size(); i++) gsame = Arrays.equals(gall.get(i), ggot.get(i));
        check("#220 the boot grew and took the stored phone anchor: the new part only, every row as dating everything", gw == null && gsame);
        for (String bad : new String[] {null, "", "x", "2 1 2 3", "1 1 2", "1 1 5 100 3 9 0 NaN NaN 1 1 1 0 0", "1 1 1 1 1 1 1 1 1 1 1 1 3 1 1 1 1 1 1 1 1 1 0", "1 2 5 100 3 0 0 NaN NaN 9223372036854775807 -9223372036854775808 Infinity 0 0"}) {
            check("#220 a damaged saved state reads as none: " + bad, Dating.State.decode(bad) == null);
        }
        check("#220 an empty history's state reads back", Dating.State.decode("1 1 5 100 3 0 0 NaN NaN 9223372036854775807 -9223372036854775808 Infinity 0 0") != null);
        Dating.State st = Dating.State.decode("1 1 5 100 3 0 0 NaN NaN 9223372036854775807 -9223372036854775808 Infinity 0 0");
        check("#220 a history without boot segments dates everything", Dating.step(st, 200, new Dating.Rows(0), new Dating.Rows(0)).full != null);
        st.futMin = 1_800_000_000L;
        check("#220 a time record that was ahead and is due now: everything again", st.changesAt(1_800_000_000L) != null && st.changesAt(1_799_970_000L) == null);
        st.futMin = Long.MAX_VALUE;
        st.validMax = 1_800_000_000L;
        check("#220 a time record in use that is ahead after the phone clock went back: everything again", st.changesAt(1_799_970_000L) != null
                && st.changesAt(1_800_000_000L - 6 * 3600) == null);
        st.validMax = Long.MIN_VALUE;
        st.clampMin = 1_800_000_000.5;
        check("#220 a date cleared as ahead that is due now: everything again", st.changesAt(1_799_978_401L) != null && st.changesAt(1_799_978_400L) == null);
        st.clampMin = Double.POSITIVE_INFINITY;
        st.dmax = 1_800_000_000.5;
        check("#220 a stored date ahead of the phone clock now: everything again", st.changesAt(1_799_978_400L) != null && st.changesAt(1_799_978_401L) == null);
        eq("#220 dates before 2015 are cleared", Double.isNaN(Dating.limit(1_419_999_999.9, 1_800_000_000L)), true);
        eq("#220 a date 6 hours ahead is kept", Dating.limit(1_800_021_600.0, 1_800_000_000L), 1_800_021_600.0);
    }

    /**
     * Issue #195: the background job connects with a short, filtered, balanced scan and one round fewer; everything else
     * keeps the plan it had before, and the app's code uses the plan, not numbers of its own.
     */
    static void connectPlan195() throws IOException {
        ConnectPlan p = ConnectPlan.of(false), b = ConnectPlan.of(true);
        check("#195 a person's sync uses the person plan", p == ConnectPlan.PERSON && !p.background);
        check("#195 the background job uses the background plan", b == ConnectPlan.BACKGROUND && b.background);
        // the person plan is exactly the one before #195
        eq("#195 person: 2 direct connects", p.directTries, 2);
        eq("#195 person: 12 s each", p.directMs, 12000L);
        eq("#195 person: 1.5 s between them", p.directGapMs, 1500L);
        eq("#195 person: 40 s scan", p.scanMs, 40000L);
        check("#195 person: low latency, unfiltered second half", p.lowLatency && p.widen);
        eq("#195 person: 20 s connect after a scan hit", p.connectMs, 20000L);
        eq("#195 person: 3 rounds", p.rounds, 3);
        eq("#195 person: 2.5 s between rounds", p.roundGapMs, 2500L);
        // the background plan
        eq("#195 background: 2 direct connects", b.directTries, 2);
        eq("#195 background: 12 s each", b.directMs, 12000L);
        eq("#195 background: 10 s scan", b.scanMs, 10000L);
        check("#195 background: balanced and filtered only (an unfiltered scan hears nothing with the screen off)", !b.lowLatency && !b.widen);
        eq("#195 background: 20 s connect after a scan hit", b.connectMs, 20000L);
        eq("#195 background: 2 rounds", b.rounds, 2);
        // rounds: a ring not reached gets the plan's rounds, a link that broke off after connecting keeps 3 (#157)
        eq("#195 both plans: 3 rounds after connecting", b.connectedRounds + "," + p.connectedRounds, "3,3");
        eq("#195 most rounds: 3 in both plans", b.maxRounds() + "," + p.maxRounds(), "3,3");
        check("#195 person, not reached: rounds 2 and 3, then no more", p.another(1, false) && p.another(2, false) && !p.another(3, false));
        check("#195 person, broke off after connecting: rounds 2 and 3, then no more", p.another(1, true) && p.another(2, true) && !p.another(3, true));
        check("#195 background, not reached: round 2, then no more", b.another(1, false) && !b.another(2, false));
        check("#195 background, broke off after connecting: rounds 2 and 3 as before #195", b.another(1, true) && b.another(2, true) && !b.another(3, true));
        check("#195 background: a round not reached after one that connected ends the sync", !b.another(2, false) && !b.another(3, false));
        // radio time with the ring away and in the worst case
        eq("#195 person: ring away 65.5 s", p.awayMs(), 65500L);
        eq("#195 background: ring away 35.5 s", b.awayMs(), 35500L);
        eq("#195 person: worst case 261.5 s", p.worstMs(), 261500L);
        eq("#195 background: worst case 113.5 s", b.worstMs(), 113500L);
        check("#195 background: no part longer than the person's", b.directTries <= p.directTries && b.directMs <= p.directMs && b.scanMs <= p.scanMs
                && b.connectMs <= p.connectMs && b.rounds <= p.rounds && (p.lowLatency || !b.lowLatency) && (p.widen || !b.widen));
        eq("#195 one direct connect: no gap counted", new ConnectPlan(true, 1, 12000, 1500, 10000, false, false, 20000, 1, 2500).awayMs(), 22000L);
        eq("#195 log line names the background plan", b.toString(), "direct 2 x 12 s, scan 10 s balanced filtered, 2 rounds");
        eq("#195 log line names the person plan", p.toString(), "direct 2 x 12 s, scan 40 s low latency then unfiltered, 3 rounds");
        // the app uses the plan: the job asks for it, the connection and the rounds read it, no old numbers remain
        String root = "app/src/main/java/app/openring/";
        String job = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "sync/SyncJob.java").toPath()), "UTF-8");
        String svc = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "sync/SyncService.java").toPath()), "UTF-8");
        String rm = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "ble/RingManager.java").toPath()), "UTF-8");
        check("#195 the background job syncs with a background ticket", job.contains("RingManager.SyncTicket.background()") && !job.contains("new RingManager.SyncTicket()"));
        check("#195 the sync the person starts keeps the person plan", svc.contains(".syncBlocking()") && !svc.contains("background()"));
        check("#195 the sync passes the ticket's plan", rm.contains("download(res, key, prefs, ConnectPlan.of(ticket.background))") && rm.contains("s = open(false, exclude, plan);"));
        check("#195 every other connection keeps the person plan", rm.contains("return open(anyRing, exclude, ConnectPlan.PERSON);"));
        check("#195 the connection reads the plan", rm.contains("plan.scanMs, plan.lowLatency, plan.widen") && rm.contains("GattLink.open(ctx, d, glog, plan.directMs)")
                && rm.contains("GattLink.open(ctx, d, glog, plan.connectMs)") && rm.contains("attempt <= plan.maxRounds()"));
        check("#195 both retried failures ask the plan whether another round runs", rm.split("if \\(!plan.another\\(attempt, s != null\\)\\) break;", -1).length == 3);
        int o = rm.indexOf("private Session open(boolean anyRing, Set<String> exclude, ConnectPlan plan)"), oe = rm.indexOf("private void close(Session s)", o);
        int d = rm.indexOf("private void download(SyncResult res"), de = rm.indexOf("private boolean bluetoothOn()", d);
        check("#195 open() and download() found", o > 0 && oe > o && d > 0 && de > d);
        String openBody = rm.substring(o, oe), dlBody = rm.substring(d, de);
        check("#195 open() types no timeout of its own", !openBody.contains("12000") && !openBody.contains("20000") && !openBody.contains("40000") && !openBody.contains("sleep(1500)"));
        check("#195 download() types no round count of its own", !dlBody.contains("attempt <= 3") && !dlBody.contains("attempt < 3"));
        String sc = new String(java.nio.file.Files.readAllBytes(new java.io.File(root + "ble/RingScanner.java").toPath()), "UTF-8");
        check("#195 the filtered only scan also matches the saved ring's address (review of a1fa895)",
                sc.contains("if (!widen && preferred != null && BluetoothAdapter.checkBluetoothAddress(preferred))")
                && sc.contains("filters.add(new ScanFilter.Builder().setDeviceAddress(preferred).build());")
                && sc.contains("if (!addr.equals(preferred) && !isOura(r)) return;"));
        check("#195 one find left, every caller chooses the plan", sc.split("public static BluetoothDevice find\\(", -1).length == 2);
    }

    /** Issue #194: the rule for skipping a sync shortly after the last one, and screen sessions read again unchanged. */
    static void recent194() {
        long now = 1_800_000_000L;
        check("#194 a sync 10 minutes ago is recent", BackgroundJob.recent(now, now - 600, 0));
        check("#194 an attempt 10 minutes ago is recent (a sync a day ago)", BackgroundJob.recent(now, now - 86400, now - 600));
        check("#194 30 minutes ago is still recent, 30 minutes and a second not", BackgroundJob.recent(now, now - 1800, 0) && !BackgroundJob.recent(now, now - 1801, 0));
        check("#194 never synced is not recent", !BackgroundJob.recent(now, 0, 0));
        check("#194 a last sync after now (phone clock set back) is not recent", !BackgroundJob.recent(now, now + 3600, 0));
        List<double[]> stored = Arrays.asList(new double[] {100, 200}, new double[] {300, 400});
        check("#194 sessions read again unchanged: nothing changed", Double.isNaN(ScreenTime.firstChanged(stored, Arrays.asList(new double[] {300, 400}))));
        eq("#194 the newest session went on: changed from its start", ScreenTime.firstChanged(stored, Arrays.asList(new double[] {300, 450})), 300.0);
        eq("#194 a new session after an unchanged one: from the new one", ScreenTime.firstChanged(stored,
                Arrays.asList(new double[] {300, 400}, new double[] {500, 600})), 500.0);
        eq("#194 nothing stored yet: from the first read", ScreenTime.firstChanged(new ArrayList<double[]>(),
                Arrays.asList(new double[] {500, 600}, new double[] {700, 800})), 500.0);
    }

    static List<double[]> ranges(double... v) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i + 1 < v.length; i += 2) out.add(new double[] {v[i], v[i + 1]});
        return out;
    }

    /** Issue #254: the left out times, merged; the sleep window without them; pieces across midnight and a clock change; the texts. */
    static void leftOut254() {
        LeftOut none = LeftOut.of(new ArrayList<double[]>());
        check("left out: none is empty and the same as NONE", none.isEmpty() && none == LeftOut.NONE && LeftOut.of(null).isEmpty()
                && !none.contains(5) && none.key().isEmpty() && none.size() == 0);
        double[] w = none.window(100, 5000);
        check("left out: none leaves a window as it is", w[0] == 100 && w[1] == 5000);
        // merge: overlapping, touching, inside, out of order, broken rows skipped
        LeftOut l = LeftOut.of(ranges(5000, 6000, 1000, 2000, 1500, 3000, 3000, 3500, 1200, 1300, 9000, 8000, 7000, 7000));
        eq("left out: merged ranges", l.ranges().size() + " " + l.key(), "2 1000-3500,5000-6000");
        check("left out: contains its start, not its stop", l.contains(1000) && l.contains(3499.9) && !l.contains(3500) && !l.contains(999.9)
                && l.contains(5000) && !l.contains(4000) && !l.contains(6000) && !l.contains(10000) && !l.contains(0));
        check("left out: overlaps and covers", l.overlaps(3400, 3600) && !l.overlaps(3500, 5000) && l.covers(1100, 3500) && !l.covers(900, 2000)
                && !l.covers(3000, 5500) && !l.covers(4000, 4500));
        eq("left out: seconds of a range", l.seconds(0, 10000), 3500.0);
        eq("left out: seconds clipped", l.seconds(3000, 5500), 1000.0);
        List<double[]> p = l.pieces(2000, 5500);
        check("left out: pieces clipped to the range", p.size() == 2 && p.get(0)[0] == 2000 && p.get(0)[1] == 3500 && p.get(1)[0] == 5000 && p.get(1)[1] == 5500);
        // window: at the start, the end, the middle, under 15 minutes
        LeftOut n = LeftOut.of(ranges(0, 3600));
        w = n.window(1800, 30000);
        check("left out at the start of a window: the rest", w != null && w[0] == 3600 && w[1] == 30000);
        n = LeftOut.of(ranges(20000, 40000));
        w = n.window(1800, 30000);
        check("left out at the end of a window: the part before", w != null && w[0] == 1800 && w[1] == 20000);
        n = LeftOut.of(ranges(10000, 12000));
        w = n.window(1800, 30000);
        check("left out in the middle: the longer piece", w != null && w[0] == 12000 && w[1] == 30000);
        w = n.window(0, 22000);
        check("left out in the middle, two as long: the earlier piece", w != null && w[0] == 0 && w[1] == 10000);
        check("left out: a window under 15 minutes is dropped", n.window(9500, 12500) == null && n.window(10500, 11000) == null);
        w = n.window(9100, 12000);
        check("left out: exactly 15 minutes stays", w != null && w[0] == 9100 && w[1] == 10000);
        check("left out: a window inside is dropped", LeftOut.of(ranges(0, 100000)).window(1000, 50000) == null);
        // problems
        double now = 50000;
        eq("left out: end before start", l.problem(2000, 2000, now), "The end must be after the start.");
        eq("left out: the start in the future", l.problem(60000, 70000, now), "The start cannot be in the future.");
        eq("left out: the same time again", l.problem(1000, 3500, now), "This time is already left out.");
        eq("left out: part of it is new", l.problem(3000, 4000, now), null);
        // texts in Zurich: 3 Oct 2026 18:00 to 4 Oct 09:00
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        double a = java.time.LocalDateTime.parse("2026-10-03T18:00").atZone(z).toEpochSecond();
        double b = java.time.LocalDateTime.parse("2026-10-04T09:00").atZone(z).toEpochSecond();
        eq("left out: list line", LeftOut.listLine(a, b, z), "3 Oct 18:00 to 4 Oct 09:00 (15 h)");
        eq("left out: card", LeftOut.card(a, b, z), "From 3 Oct 18:00 to 4 Oct 09:00 is left out (someone else wore the ring). To bring it back: Settings, Advanced.");
        eq("left out: timeline row", LeftOut.timelineRow(a, b, z), "Left out from 18:00 to 09:00 (someone else wore the ring)");
        eq("left out: timeline row of more than a day", LeftOut.timelineRow(a, b + 86400, z), "Left out from 3 Oct 18:00 to 5 Oct 09:00 (someone else wore the ring)");
        double mid = java.time.LocalDate.parse("2026-10-04").atStartOfDay(z).toEpochSecond();
        eq("left out: the band's readout, cut at midnight", LeftOut.bandTip(a, mid, z), "Left out, 18:00 to 00:00 (someone else wore the ring)");
        eq("left out: activity line", LeftOut.activityLine(3 * 3600), "3 h of this day are left out, so they are not in these minutes.");
        eq("left out: activity line, one hour", LeftOut.activityLine(3600), "1 h of this day is left out, so it is not in these minutes.");
        eq("left out: lengths", LeftOut.length(45 * 60) + ", " + LeftOut.length(15.5 * 3600) + ", " + LeftOut.length(3 * 86400 + 4 * 3600) + ", "
                + LeftOut.length(2 * 86400), "45 min, 15 h 30 min, 3 days 4 h, 2 days");
        java.time.LocalDate today = java.time.LocalDate.parse("2026-10-30");
        double now30 = java.time.LocalDateTime.parse("2026-10-30T10:00").atZone(z).toEpochSecond();
        eq("left out: the preview across two days", LeftOut.preview(a, b, 0, 2, 14, today, now30, z, 28), "Left out: 3 Oct 18:00 to 4 Oct 09:00 (15 h), "
                + "with heart rate or temperature in 14 of those hours. 2 nights that reach into it are cut at its edges. The ring's data stays stored "
                + "and comes back if you remove this. Your days from 1 Oct to today are worked out again (30 days). This takes a few minutes.");
        double d28 = java.time.LocalDate.parse("2026-10-28").atStartOfDay(z).toEpochSecond();
        String recent = LeftOut.preview(d28 + 13 * 3600, d28 + 17 * 3600, 0, 0, 4, today, now30, z, 28);
        eq("left out: a few hours of one day", recent, "Left out: 13:00 to 17:00 on 28 Oct (4 h), with heart rate or temperature in 4 of those hours. "
                + "The ring's data stays stored and comes back if you remove this. Your days from 26 Oct to today are worked out again (5 days).");
        double d29 = java.time.LocalDate.parse("2026-10-29").atStartOfDay(z).toEpochSecond(), d30 = java.time.LocalDate.parse("2026-10-30").atStartOfDay(z).toEpochSecond();
        eq("left out: a whole day, one night inside, two cut", LeftOut.preview(d29, d30, 1, 2, 24, today, now30, z, 28), "Left out: 29 Oct (24 h), with heart "
                + "rate or temperature in 24 of those hours. 1 night inside it is left out. 2 nights that reach into it are cut at its edges. The ring's data "
                + "stays stored and comes back if you remove this. Your days from 27 Oct to today are worked out again (4 days).");
        eq("left out: whole days, one night cut, no readings, an end after now", LeftOut.preview(d28, d30 + 86400, 0, 1, 0, today, now30, z, 28),
                "Left out: 28 Oct to 30 Oct (3 days), with no heart rate or temperature in it. 1 night that reaches into it is cut at its edge. The ring's data "
                + "stays stored and comes back if you remove this. Your days from 26 Oct to today are worked out again (5 days). This also leaves out what "
                + "the ring records until 31 Oct 00:00.");
        eq("left out: what, the 25 hour day of the clock change", LeftOut.what(java.time.LocalDate.parse("2026-10-25").atStartOfDay(z).toEpochSecond(),
                java.time.LocalDate.parse("2026-10-26").atStartOfDay(z).toEpochSecond(), z), "25 Oct (25 h)");
        eq("left out: bring back", LeftOut.bringBackText(a, z), "It counts again in your charts, scores and usual values. Your days from 1 Oct on are worked out again.");
        // calendar days and pieces across midnight and the clock change of 25 Oct 2026 (the night has 25 hours)
        double e24 = java.time.LocalDateTime.parse("2026-10-24T22:00").atZone(z).toEpochSecond();
        double m25 = java.time.LocalDate.parse("2026-10-25").atStartOfDay(z).toEpochSecond(), m26 = java.time.LocalDate.parse("2026-10-26").atStartOfDay(z).toEpochSecond();
        double e25 = java.time.LocalDateTime.parse("2026-10-25T09:00").atZone(z).toEpochSecond();
        LeftOut dst = LeftOut.of(ranges(e24, e25));
        eq("left out across the clock change: 12 real hours (22:00 to 09:00 with an hour twice)", dst.seconds(0, 1e10), 12 * 3600.0);
        eq("left out across midnight: 2 calendar days", LeftOut.days(e24, e25, z), 2L);
        eq("left out to midnight: 1 calendar day", LeftOut.days(e24, m25, z), 1L);
        eq("left out on the 25 hour day: 10 real hours of it", dst.seconds(m25, m26), 10 * 3600.0);
        eq("left out on the evening before: 2 hours", dst.seconds(m25 - 86400, m25), 2 * 3600.0);
        eq("left out across the clock change: list line", LeftOut.listLine(e24, e25, z), "24 Oct 22:00 to 25 Oct 09:00 (12 h)");
        w = dst.window(java.time.LocalDateTime.parse("2026-10-24T23:00").atZone(z).toEpochSecond(), java.time.LocalDateTime.parse("2026-10-25T10:00").atZone(z).toEpochSecond());
        check("left out across the clock change: the hour after it is the window", w != null && w[0] == e25 && w[1] - w[0] == 3600);
        for (String t : new String[] {LeftOut.preview(a, b, 1, 1, 14, today, a, z, 28), LeftOut.card(a, b, z), LeftOut.bandTip(a, b, z), LeftOut.timelineRow(a, b, z),
            LeftOut.activityLine(5400), LeftOut.bringBackText(a, z), app.openring.ui.Explain.LEFT_OUT, l.problem(1, 0, 0), l.problem(9e9, 1e10, 0), l.problem(1000, 2000, 1e10)}) {
            check("left out texts: no dash as punctuation: " + t, !t.contains(" - ") && !t.contains("–") && !t.contains("—") && !t.isEmpty());
        }
    }

    /** Issue #221: the phone's pending measurement modes, as Prefs keeps them. */
    static final class ModeStore implements ModeRestore.Store {
        final Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        int writes;

        public int pending(int feature) {
            Integer v = m.get(feature);
            return v == null ? ModeRestore.NONE : v;
        }

        public void setPending(int feature, int mode) {
            writes++;
            if (mode < 0) m.remove(feature);
            else m.put(feature, mode);
        }
    }

    /** Issue #221: a ring that keeps its modes, and can drop the link or refuse. */
    static final class ModeRing implements ModeRestore.Ring {
        final Map<Integer, Integer> mode = new HashMap<Integer, Integer>();
        final Set<Integer> refuse = new HashSet<Integer>(), throwOn = new HashSet<Integer>();
        boolean linkDown;
        final List<String> sent = new ArrayList<String>();

        public boolean setMode(int feature, int m) throws IOException {
            sent.add(feature + ":" + m);
            if (linkDown || throwOn.contains(feature)) throw new IOException("the ring disconnected");
            if (refuse.contains(feature)) return false;
            mode.put(feature, m);
            return true;
        }

        int[] status(int feature) {
            Integer m = mode.get(feature);
            return m == null ? null : new int[] {m, 0, 0, 0};
        }
    }

    /** Live's start as RingManager does it: keep the previous modes, then switch for the session. */
    static void liveStart(ModeRing ring, ModeStore store, boolean spo2) throws Exception {
        int[] fs = ring.status(Protocol.FEATURE_DAYTIME_HR);
        ModeRestore.remember(store, Protocol.FEATURE_DAYTIME_HR, fs);
        if (fs != null && fs[0] == Protocol.MODE_OFF) ring.setMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_AUTOMATIC);
        ring.setMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_CONNECTED_LIVE);
        if (spo2) {
            ModeRestore.remember(store, Protocol.FEATURE_SPO2, ring.status(Protocol.FEATURE_SPO2));
            ring.setMode(Protocol.FEATURE_SPO2, Protocol.MODE_REQUESTED); // live refused on Ring 4, requested works
        }
    }

    static void modeRestore221() throws Exception {
        final List<String> log = new ArrayList<String>();
        ModeRestore.Log lg = new ModeRestore.Log() {
            public void log(String line) {
                log.add(line);
            }
        };
        int HR = Protocol.FEATURE_DAYTIME_HR, SPO2 = Protocol.FEATURE_SPO2;
        // the person's mode: Settings only sends Off or automatic; requested and live are Live's leftovers
        eq("221 chosen: off stays off", ModeRestore.chosen(new int[] {0, 0, 0, 0}), Protocol.MODE_OFF);
        eq("221 chosen: automatic", ModeRestore.chosen(new int[] {1, 0, 0, 0}), Protocol.MODE_AUTOMATIC);
        eq("221 chosen: a stuck requested counts as automatic", ModeRestore.chosen(new int[] {2, 0, 0, 0}), Protocol.MODE_AUTOMATIC);
        eq("221 chosen: live counts as automatic", ModeRestore.chosen(new int[] {3, 1, 0, 0}), Protocol.MODE_AUTOMATIC);
        eq("221 chosen: unreadable counts as automatic", ModeRestore.chosen(null), Protocol.MODE_AUTOMATIC);
        eq("221 chosen: empty status counts as automatic", ModeRestore.chosen(new int[0]), Protocol.MODE_AUTOMATIC);
        eq("221 previous: a pending mode wins over a stuck requested", ModeRestore.previous(Protocol.MODE_OFF, new int[] {2, 0, 0, 0}), Protocol.MODE_OFF);
        eq("221 previous: no pending gives the chosen mode", ModeRestore.previous(ModeRestore.NONE, new int[] {0, 0, 0, 0}), Protocol.MODE_OFF);

        // nothing pending: nothing is sent, nothing written
        ModeRing ring = new ModeRing();
        ModeStore store = new ModeStore();
        eq("221 none pending: none left", ModeRestore.restorePending(ring, store, lg), 0);
        eq("221 none pending: nothing sent", ring.sent.size(), 0);
        eq("221 none pending: nothing written", store.writes, 0);

        // a clean session: daytime heart rate Off comes back Off (not automatic), blood oxygen back to automatic
        ring.mode.put(HR, Protocol.MODE_OFF);
        ring.mode.put(SPO2, Protocol.MODE_AUTOMATIC);
        liveStart(ring, store, true);
        eq("221 clean: kept daytime heart rate off before the change", store.pending(HR), Protocol.MODE_OFF);
        eq("221 clean: kept blood oxygen automatic", store.pending(SPO2), Protocol.MODE_AUTOMATIC);
        eq("221 clean: ring in live and requested while measuring", ring.mode.get(HR) + "/" + ring.mode.get(SPO2), "3/2");
        eq("221 clean: none left", ModeRestore.restorePending(ring, store, lg), 0);
        eq("221 clean: daytime heart rate off again", ring.mode.get(HR), Protocol.MODE_OFF);
        eq("221 clean: blood oxygen automatic again", ring.mode.get(SPO2), Protocol.MODE_AUTOMATIC);
        eq("221 clean: nothing pending", store.m.size(), 0);
        check("221 clean: the log says what was put back", log.contains("blood oxygen put back to automatic") && log.contains("daytime heart rate put back to off"));

        // without blood oxygen only daytime heart rate is kept
        ring = new ModeRing();
        store = new ModeStore();
        ring.mode.put(HR, Protocol.MODE_AUTOMATIC);
        ring.mode.put(SPO2, Protocol.MODE_OFF);
        liveStart(ring, store, false);
        eq("221 no blood oxygen: SpO2 not kept", store.pending(SPO2), ModeRestore.NONE);
        ModeRestore.restorePending(ring, store, lg);
        eq("221 no blood oxygen: SpO2 never sent", ring.sent.contains(SPO2 + ":" + Protocol.MODE_OFF) || ring.sent.contains(SPO2 + ":" + Protocol.MODE_AUTOMATIC), false);
        eq("221 no blood oxygen: SpO2 left as it was", ring.mode.get(SPO2), Protocol.MODE_OFF);

        // the link drops: everything stays pending, the next connection (sync) puts it back
        ring = new ModeRing();
        store = new ModeStore();
        ring.mode.put(HR, Protocol.MODE_AUTOMATIC);
        ring.mode.put(SPO2, Protocol.MODE_OFF);
        liveStart(ring, store, true);
        ring.linkDown = true;
        log.clear();
        eq("221 link drop: both left", ModeRestore.restorePending(ring, store, lg), 2);
        eq("221 link drop: blood oxygen still pending off", store.pending(SPO2), Protocol.MODE_OFF);
        check("221 link drop: the log says the next connection tries again", log.size() == 2 && log.get(1).contains("next connection tries again")
                && log.get(1).contains("the ring disconnected"));
        // out of reach the ring falls back from live to automatic by itself, SpO2 stays requested
        ring.mode.put(HR, Protocol.MODE_AUTOMATIC);
        ring.linkDown = false;
        ring.sent.clear();
        eq("221 next sync: none left", ModeRestore.restorePending(ring, store, lg), 0);
        eq("221 next sync: blood oxygen off again", ring.mode.get(SPO2), Protocol.MODE_OFF);
        eq("221 next sync: daytime heart rate automatic", ring.mode.get(HR), Protocol.MODE_AUTOMATIC);
        eq("221 next sync: nothing pending", store.m.size(), 0);
        eq("221 next sync: one request each", ring.sent.size(), 2);
        eq("221 a later sync sends nothing", ModeRestore.restorePending(ring, store, lg) + "/" + ring.sent.size(), "0/2");

        // a restore that throws for daytime heart rate does not stop the blood oxygen restore
        ring = new ModeRing();
        store = new ModeStore();
        store.setPending(HR, Protocol.MODE_OFF);
        store.setPending(SPO2, Protocol.MODE_AUTOMATIC);
        ring.throwOn.add(HR);
        ring.mode.put(SPO2, Protocol.MODE_REQUESTED);
        eq("221 throws: one left", ModeRestore.restorePending(ring, store, lg), 1);
        eq("221 throws: blood oxygen still put back", ring.mode.get(SPO2), Protocol.MODE_AUTOMATIC);
        eq("221 throws: blood oxygen cleared", store.pending(SPO2), ModeRestore.NONE);
        eq("221 throws: daytime heart rate stays pending", store.pending(HR), Protocol.MODE_OFF);

        // a refused restore stays pending too, and a runtime error in the ring code counts as not put back
        ring = new ModeRing();
        store = new ModeStore();
        store.setPending(SPO2, Protocol.MODE_OFF);
        ring.refuse.add(SPO2);
        log.clear();
        eq("221 refused: left", ModeRestore.restorePending(ring, store, lg), 1);
        check("221 refused: logged", log.get(0).contains("did not accept"));
        eq("221 refused: still pending", store.pending(SPO2), Protocol.MODE_OFF);
        eq("221 runtime error: left", ModeRestore.restorePending(new ModeRestore.Ring() {
            public boolean setMode(int feature, int mode) {
                throw new IllegalStateException("closed");
            }
        }, store, lg), 1);
        eq("221 runtime error: still pending", store.pending(SPO2), Protocol.MODE_OFF);

        // the next Live session finds a stuck requested mode with a pending restore that failed: the pending one wins
        ring = new ModeRing();
        store = new ModeStore();
        ring.mode.put(HR, Protocol.MODE_AUTOMATIC);
        ring.mode.put(SPO2, Protocol.MODE_OFF);
        liveStart(ring, store, true);
        ring.linkDown = true;
        ModeRestore.restorePending(ring, store, lg);
        ring.linkDown = false;
        ring.refuse.add(SPO2); // the start restore of the next session is refused
        ModeRestore.restorePending(ring, store, lg);
        eq("221 stuck: the ring still in requested", ring.mode.get(SPO2), Protocol.MODE_REQUESTED);
        ring.refuse.clear();
        liveStart(ring, store, true);
        eq("221 stuck: requested not taken as the previous mode", store.pending(SPO2), Protocol.MODE_OFF);
        ModeRestore.restorePending(ring, store, lg);
        eq("221 stuck: back to off at the end", ring.mode.get(SPO2), Protocol.MODE_OFF);
        // an older version left requested with nothing pending: back to automatic, never left in requested
        store = new ModeStore();
        ring.mode.put(SPO2, Protocol.MODE_REQUESTED);
        liveStart(ring, store, true);
        ModeRestore.restorePending(ring, store, lg);
        eq("221 older version: requested goes back to automatic", ring.mode.get(SPO2), Protocol.MODE_AUTOMATIC);

        // Settings: the person's choice wins over a pending restore, only for that measurement
        store = new ModeStore();
        store.setPending(HR, Protocol.MODE_OFF);
        store.setPending(SPO2, Protocol.MODE_AUTOMATIC);
        ModeRestore.replaced(store, HR);
        eq("221 settings: daytime heart rate no longer pending", store.pending(HR), ModeRestore.NONE);
        eq("221 settings: blood oxygen still pending", store.pending(SPO2), Protocol.MODE_AUTOMATIC);
        int w = store.writes;
        ModeRestore.replaced(store, HR);
        eq("221 settings: nothing written when nothing pending", store.writes, w);
        // a factory reset (or a new pairing) drops them all
        ModeRestore.forget(store);
        eq("221 forget: nothing pending", store.m.size(), 0);
        eq("221 features: daytime heart rate and blood oxygen", Arrays.toString(ModeRestore.FEATURES), "[2, 4]");
        eq("221 names", ModeRestore.modeName(Protocol.MODE_CONNECTED_LIVE) + "," + ModeRestore.modeName(9) + "," + ModeRestore.name(8), "live,mode 9,measurement 8");
    }

    /** What Setup and Settings did with typed text before the limits got names: trim, whole number, clamp, else the default. */
    static int typedBefore(String text, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(text.trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (NumberFormatException x) {
            return def;
        }
    }

    /** The day under a point, as both month calendars worked it out before MonthGrid (BadgeCalendar and PeriodCalendar). */
    static java.time.LocalDate dayAtBefore(java.time.YearMonth month, java.time.DayOfWeek firstDay, int width, float head, float cellH, float x, float y) {
        float cw = width / 7f;
        int col = (int) Math.max(0, Math.min(6, Math.floor(x / cw)));
        int row = (int) Math.floor((y - head) / cellH);
        if (row < 0 || row >= 6) return null;
        int off = (month.atDay(1).getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
        int dom = row * 7 + col - off + 1;
        return dom >= 1 && dom <= month.lengthOfMonth() ? month.atDay(dom) : null;
    }

    /** The month calendars' grid and touch decision give exactly what the two copies in the views gave. */
    static void monthGrid() {
        int same = 0, all = 0, sameOff = 0, offs = 0;
        int[] widths = {0, 1, 700, 1080, 1439};
        float[][] sizes = {{22, 42}, {57.75f, 110.25f}, {66, 126}};
        for (java.time.DayOfWeek first : java.time.DayOfWeek.values()) {
            for (int m = 0; m < 30; m++) {
                java.time.YearMonth month = java.time.YearMonth.of(2023, 11).plusMonths(m); // includes February 2024 (29 days)
                offs++;
                if (MonthGrid.offset(month, first) == (month.atDay(1).getDayOfWeek().getValue() - first.getValue() + 7) % 7) sameOff++;
                for (int width : widths) {
                    for (float[] hc : sizes) {
                        float head = hc[0], cellH = hc[1];
                        for (float x = -40; x <= width + 40; x += Math.max(1, width / 37f)) {
                            for (float y = -10; y <= head + 7 * cellH; y += cellH / 5.5f) {
                                all++;
                                java.time.LocalDate a = MonthGrid.dayAt(month, first, width, head, cellH, x, y), b = dayAtBefore(month, first, width, head, cellH, x, y);
                                if (a == null ? b == null : a.equals(b)) same++;
                            }
                        }
                    }
                }
            }
        }
        eq("month grid: the first of the month in the same column for every first weekday", sameOff, offs);
        eq("month grid: every point gives the same day as before", same, all);
        check("month grid: enough points compared", all > 100000, all);
        java.time.YearMonth nov = java.time.YearMonth.of(2026, 11); // 1 November 2026 is a Sunday
        eq("month grid: Monday first puts Sunday the 1st last", MonthGrid.offset(nov, java.time.DayOfWeek.MONDAY), 6);
        eq("month grid: Sunday first puts it first", MonthGrid.offset(nov, java.time.DayOfWeek.SUNDAY), 0);
        eq("month grid: first cell, Sunday first", MonthGrid.dayAt(nov, java.time.DayOfWeek.SUNDAY, 700, 22, 42, 5, 30), java.time.LocalDate.of(2026, 11, 1));
        eq("month grid: first cell before the 1st is no day", MonthGrid.dayAt(nov, java.time.DayOfWeek.MONDAY, 700, 22, 42, 5, 30), null);
        eq("month grid: left of the grid counts as the first column", MonthGrid.dayAt(nov, java.time.DayOfWeek.SUNDAY, 700, 22, 42, -50, 30), java.time.LocalDate.of(2026, 11, 1));
        eq("month grid: above the rows is no day", MonthGrid.dayAt(nov, java.time.DayOfWeek.SUNDAY, 700, 22, 42, 5, 21.9f), null);
        eq("month grid: below the sixth row is no day", MonthGrid.dayAt(nov, java.time.DayOfWeek.SUNDAY, 700, 22, 42, 5, 22 + 6 * 42), null);
        eq("month grid: the 30th in the fifth row", MonthGrid.dayAt(nov, java.time.DayOfWeek.SUNDAY, 700, 22, 42, 150, 22 + 4 * 42 + 1), java.time.LocalDate.of(2026, 11, 30));
        int sameG = 0, gs = 0;
        float[] ds = {0, 1, 7.5f, 8, 8.01f, 9, 15, 16, 16.5f, 40};
        for (int slop : new int[] {0, 8, 16, 24}) {
            for (float dx : ds) {
                for (float dy : ds) {
                    gs++;
                    int before = dx > slop && dx > dy ? MonthGrid.SLIDE : dy > slop ? MonthGrid.SCROLL : MonthGrid.WAIT;
                    if (MonthGrid.gesture(dx, dy, slop) == before) sameG++;
                }
            }
        }
        eq("month grid: every move is a slide, a scroll or undecided as before", sameG, gs);
        eq("month grid: sideways past the slop slides", MonthGrid.gesture(9, 3, 8), MonthGrid.SLIDE);
        eq("month grid: as much down as sideways scrolls", MonthGrid.gesture(9, 9, 8), MonthGrid.SCROLL);
        eq("month grid: within the slop waits", MonthGrid.gesture(8, 8, 8), MonthGrid.WAIT);
    }

    /** The named profile and screen goal limits give exactly the numbers that were typed in Setup, Settings and the restore. */
    static void inputLimit() {
        InputLimit[] limits = {InputLimit.AGE, InputLimit.WEIGHT_KG, InputLimit.HEIGHT_CM, InputLimit.TARGET_KCAL, InputLimit.SCREEN_GOAL_MIN};
        int[][] typed = {{35, 10, 100}, {72, 30, 250}, {175, 100, 230}, {0, 0, 3000}, {180, 0, 1440}};
        String[] names = {"age", "weight", "height", "calorie goal", "screen goal"};
        String[] texts = {"", " ", "abc", "41", " 41 ", "\t63\n", "0", "-1", "-0", "+12", "9", "10", "100", "101", "29", "30", "250", "251",
                "99", "231", "3000", "3001", "1440", "1441", "1e3", "12.5", "12,5", "2147483647", "2147483648", "-2147483648",
                "99999999999", "\u0661\u0662", "4 2", "0x10"};
        int[] values = {Integer.MIN_VALUE, -100000, -1, 0, 1, 9, 10, 29, 30, 99, 100, 101, 230, 231, 250, 251, 1440, 1441, 3000, 3001, Integer.MAX_VALUE};
        for (int i = 0; i < limits.length; i++) {
            InputLimit l = limits[i];
            int[] t = typed[i];
            eq("input limit " + names[i] + ": default, low, high", l.def + " " + l.lo + " " + l.hi, t[0] + " " + t[1] + " " + t[2]);
            int same = 0;
            for (String x : texts) if (l.parse(x) == typedBefore(x, t[0], t[1], t[2])) same++;
            eq("input limit " + names[i] + ": typed text gives the same number as before", same, texts.length);
            same = 0;
            for (int v : values) if (l.clamp(v) == Math.max(t[1], Math.min(t[2], v))) same++;
            eq("input limit " + names[i] + ": a restored value is kept in the same range", same, values.length);
        }
        eq("input limit: empty text is the default age", InputLimit.AGE.parse(""), 35);
        eq("input limit: an age typed with spaces", InputLimit.AGE.parse(" 41 "), 41);
        eq("input limit: too young is the lowest age", InputLimit.AGE.parse("3"), 10);
        eq("input limit: a number too long for an int is the default", InputLimit.WEIGHT_KG.parse("99999999999"), 72);
        eq("input limit: a decimal height is the default", InputLimit.HEIGHT_CM.parse("180.5"), 175);
        eq("input limit: a screen goal over a day is a whole day", InputLimit.SCREEN_GOAL_MIN.parse("2000"), 1440);
    }

    // ------------------------------------------------------------------ issue #329, phase 1b: steps from this phone

    static PhoneSteps.Reading rd(long boot, double elapsedS, long counter, double wall) {
        return new PhoneSteps.Reading(boot, Math.round(elapsedS * 1000), counter, wall);
    }

    static long stepsOf(List<PhoneSteps.Interval> l) {
        long n = 0;
        for (PhoneSteps.Interval i : l) n += i.steps;
        return n;
    }

    /** The differences between readings: restarts by boot count, clock changes, a falling or impossible counter. */
    static void phoneSteps329() {
        double w0 = 1_780_000_000; // some day in 2026
        // the plain case: hourly readings, one boot that started 2 hours before the first
        List<PhoneSteps.Reading> plain = new ArrayList<PhoneSteps.Reading>();
        plain.add(rd(7, 7200, 900, w0));
        plain.add(rd(7, 10800, 1400, w0 + 3600));
        plain.add(rd(7, 14400, 2100, w0 + 7200));
        List<PhoneSteps.Interval> iv = PhoneSteps.intervals(plain);
        check("#329b phone: the first reading counts since the phone started, then the differences", iv.size() == 3 && iv.get(0).steps == 900
                && iv.get(0).start == w0 - 7200 && iv.get(0).stop == w0 && iv.get(1).steps == 500 && iv.get(2).steps == 700 && stepsOf(iv) == 2100, iv.size());
        check("#329b phone: any order of readings gives the same intervals", stepsOf(PhoneSteps.intervals(Arrays.asList(plain.get(2), plain.get(0), plain.get(1)))) == 2100);
        // the clock set back an hour between two readings: the interval keeps its real length, dated by the later clock
        List<PhoneSteps.Reading> back = new ArrayList<PhoneSteps.Reading>(plain.subList(0, 2));
        back.add(rd(7, 14400, 2100, w0 + 7200 - 3600));
        List<PhoneSteps.Interval> ib = PhoneSteps.intervals(back);
        PhoneSteps.Interval last = ib.get(ib.size() - 1);
        check("#329b phone: clock set back an hour, nothing lost, one hour long, dated by the new clock", stepsOf(ib) == 2100 && last.stop - last.start == 3600
                && last.stop == w0 + 3600 && last.steps == 700, last.start + " " + last.stop);
        // the clock set forward a day: the same steps, one hour long
        List<PhoneSteps.Reading> fwd = new ArrayList<PhoneSteps.Reading>(plain.subList(0, 2));
        fwd.add(rd(7, 14400, 2100, w0 + 7200 + 86400));
        List<PhoneSteps.Interval> iff = PhoneSteps.intervals(fwd);
        PhoneSteps.Interval lf = iff.get(iff.size() - 1);
        check("#329b phone: clock set forward a day, nothing made up, one hour long", stepsOf(iff) == 2100 && lf.stop - lf.start == 3600 && lf.steps == 700);
        // a restart: a new boot count, the counter starts again; no difference across it, the new boot counts from its start
        List<PhoneSteps.Reading> rs = new ArrayList<PhoneSteps.Reading>(plain);
        rs.add(rd(8, 600, 80, w0 + 7200 + 3600)); // started 10 minutes before this reading
        rs.add(rd(8, 4200, 380, w0 + 7200 + 7200));
        List<PhoneSteps.Interval> ir = PhoneSteps.intervals(rs);
        check("#329b phone: a restart starts again from 0, nothing taken across it", ir.size() == 5 && stepsOf(ir) == 2100 + 380 && ir.get(3).steps == 80
                && ir.get(3).start == w0 + 7200 + 3000 && ir.get(3).boot == 8, ir.size());
        // a restart whose new counter is already above the old one: the boot count tells, a falling counter alone would not
        List<PhoneSteps.Reading> above = new ArrayList<PhoneSteps.Reading>(plain);
        above.add(rd(8, 9000, 2500, w0 + 7200 + 9000));
        List<PhoneSteps.Interval> ia = PhoneSteps.intervals(above);
        check("#329b phone: a restart with a higher counter is not 400 steps since the last reading", ia.size() == 4 && ia.get(3).steps == 2500
                && ia.get(3).stop - ia.get(3).start == 9000 && stepsOf(ia) == 2100 + 2500, stepsOf(ia));
        // a restart in the middle of an interval: the steps after the last reading before it are lost, the rest is exact
        List<PhoneSteps.Reading> mid = new ArrayList<PhoneSteps.Reading>(plain.subList(0, 2)); // 1400 at w0 + 3600
        mid.add(rd(8, 1800, 150, w0 + 7200)); // the phone died at about w0 + 4800 (steps 1400 to its death lost), started at w0 + 5400
        List<PhoneSteps.Interval> im = PhoneSteps.intervals(mid);
        check("#329b phone: a restart in the middle of an interval keeps both sides, loses only the time in between", im.size() == 3 && stepsOf(im) == 1400 + 150
                && im.get(2).start == w0 + 5400 && im.get(1).stop == w0 + 3600, stepsOf(im));
        // a counter that falls without a new boot count: no difference, left out and counted; the next one counts again
        List<PhoneSteps.Reading> fell = new ArrayList<PhoneSteps.Reading>(plain);
        fell.add(rd(7, 18000, 1000, w0 + 10800));
        fell.add(rd(7, 21600, 1300, w0 + 14400));
        PhoneSteps.Left left = new PhoneSteps.Left();
        List<PhoneSteps.Interval> ifl = PhoneSteps.intervals(fell, left);
        check("#329b phone: a counter that falls without a restart gives no difference", ifl.size() == 4 && stepsOf(ifl) == 2100 + 300 && left.fell == 1, stepsOf(ifl));
        // more steps than anyone walks: left out
        List<PhoneSteps.Reading> fast = new ArrayList<PhoneSteps.Reading>(plain);
        fast.add(rd(7, 14460, 2100 + 1000, w0 + 7260));
        PhoneSteps.Left lf2 = new PhoneSteps.Left();
        check("#329b phone: 1,000 steps in a minute is left out", stepsOf(PhoneSteps.intervals(fast, lf2)) == 2100 && lf2.implausible == 1);
        PhoneSteps.Left lf3 = new PhoneSteps.Left();
        check("#329b phone: a first reading above what fits since the start is left out (a counter that did not reset)",
                PhoneSteps.intervals(Arrays.asList(rd(9, 60, 500000, w0)), lf3).isEmpty() && lf3.implausible == 1);
        check("#329b phone: two readings at the same moment give nothing", PhoneSteps.intervals(Arrays.asList(rd(7, 100, 10, w0), rd(7, 100, 12, w0))).size() == 1);
        // the boot key: Android's count when readable, else a stand in from the start time (never a real count)
        check("#329b phone: the boot key is the boot count", PhoneSteps.bootKey(42, w0, 5000) == 42 && PhoneSteps.bootKey(0, w0, 5000) == 0);
        long k1 = PhoneSteps.bootKey(-1, w0, 3_600_000), k2 = PhoneSteps.bootKey(-1, w0 + 3600, 7_200_000), k3 = PhoneSteps.bootKey(-1, w0 + 3600, 1_000);
        check("#329b phone: without a boot count, the start time stands in (same start, same key; another start, another key; never a count)",
                k1 == k2 && k1 != k3 && k1 < 0 && k3 < 0, k1 + " " + k2 + " " + k3);
        // the counts for the Data check
        List<PhoneSteps.Reading> gaps = new ArrayList<PhoneSteps.Reading>(rs);
        gaps.add(rd(8, 4200 + 5 * 3600, 900, w0 + 7200 + 7200 + 5 * 3600));
        PhoneSteps.Counts cn = PhoneSteps.counts(gaps);
        check("#329b phone: counts of readings, starts, gaps", cn.readings == 6 && cn.boots == 2 && cn.gaps == 1 && Math.round(cn.longest) == 5 * 3600 && cn.intervals == 6,
                cn.readings + " " + cn.boots + " " + cn.gaps + " " + cn.longest);
        // the readout
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        java.time.LocalDate d = java.time.LocalDate.of(2026, 6, 10);
        double a = d.atStartOfDay(z).toEpochSecond(), b = d.plusDays(1).atStartOfDay(z).toEpochSecond();
        PhoneSteps.Interval in = new PhoneSteps.Interval(a + 14 * 3600 + 120, a + 15 * 3600 + 240, 412, 7, 1);
        eq("#329b phone: the readout of an interval inside the day", PhoneSteps.tip(in, a, b, z, Locale.US), "14:02 to 15:04, 412 steps from your phone");
        PhoneSteps.Interval over = new PhoneSteps.Interval(b - 3600, b + 3600, 412, 7, 2);
        eq("#329b phone: the readout of an interval split at midnight says it is spread", PhoneSteps.tip(over, a, b, z, Locale.US), "about 206 steps (spread over 23:00 to 01:00)");
        boolean plainTexts = true;
        for (String t : PhoneSteps.texts()) plainTexts &= !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014") && !t.contains("--");
        for (String t : KeyState.texts()) plainTexts &= !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014");
        plainTexts &= !app.openring.ui.Explain.PHONE_STEPS.contains(" - ");
        check("#329b phone texts: plain, no dashes as punctuation", plainTexts);
        eq("#329b phone texts: the plan's words", PhoneSteps.TITLE + " / " + PhoneSteps.ABOUT + " / " + PhoneSteps.MAIN_LINE + " / " + PhoneSteps.NEED_DEVICE,
                "Steps from this phone / Counted by the phone's step sensor about every hour. Steps while the phone lies on a table are not counted. / Phone (steps only)"
                        + " / Sleep and readiness need a ring or a watch. Add one in Settings.");
        check("#329b Explain.PHONE_STEPS: the plan's text", app.openring.ui.Explain.PHONE_STEPS.startsWith("Your phone counts steps in its own sensor.")
                && app.openring.ui.Explain.PHONE_STEPS.contains("Nothing leaves the phone."));
    }

    /** Spreading steps over days: a 23 and a 25 hour day, a trip, and the Data check lines (numbers only). */
    static void phoneDays329() {
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Zurich");
        // the night of the spring change (23 hours): 22:00 to 04:00 on the clock, 5 real hours, 500 steps
        java.time.LocalDate spring = java.time.LocalDate.of(2026, 3, 29);
        double s0 = spring.atStartOfDay(z).toEpochSecond();
        PhoneSteps.Interval sp = new PhoneSteps.Interval(s0 - 2 * 3600, s0 + 3 * 3600, 500, 1, 1);
        double[] days = PhoneSteps.days(Arrays.asList(sp), spring.minusDays(1), spring, z);
        check("#329b days: over the spring change the steps follow real time (2 of 5 hours before, 3 after)", Math.abs(days[0] - 200) < 1e-9 && Math.abs(days[1] - 300) < 1e-9,
                days[0] + " " + days[1]);
        check("#329b days: the spring day is 23 hours", spring.plusDays(1).atStartOfDay(z).toEpochSecond() - s0 == 23 * 3600);
        // the autumn change (25 hours): an interval over the whole day lands in it whole, by real time
        java.time.LocalDate autumn = java.time.LocalDate.of(2026, 10, 25);
        double f0 = autumn.atStartOfDay(z).toEpochSecond(), f1 = autumn.plusDays(1).atStartOfDay(z).toEpochSecond();
        PhoneSteps.Interval all = new PhoneSteps.Interval(f0 - 25 * 3600, f1 + 25 * 3600, 7500, 1, 2);
        double[] ad = PhoneSteps.days(Arrays.asList(all), autumn.minusDays(1), autumn.plusDays(1), z);
        check("#329b days: the 25 hour day gets 25 of 75 hours, its neighbours 24 and 24 (the rest lies outside)", Math.abs(ad[1] - 2500) < 1e-9 && Math.abs(ad[0] - 2400) < 1e-9
                && Math.abs(ad[2] - 2400) < 1e-9, ad[0] + " " + ad[1] + " " + ad[2]);
        // the sum over days is the sum of the intervals
        List<PhoneSteps.Interval> many = new ArrayList<PhoneSteps.Interval>();
        Random r = new Random(329);
        double t = f0 - 10 * 86400;
        long sum = 0;
        while (t < f1 + 10 * 86400) {
            double len = 600 + r.nextInt(5 * 3600);
            long st = r.nextInt(3000);
            many.add(new PhoneSteps.Interval(t, t + len, st, 1, (long) t));
            sum += st;
            t += len + (r.nextInt(4) == 0 ? r.nextInt(3600) : 0);
        }
        double[] md = PhoneSteps.days(many, autumn.minusDays(12), autumn.plusDays(12), z);
        double got = 0;
        for (double v : md) got += Double.isNaN(v) ? 0 : v;
        check("#329b days: spread over many days, nothing is lost or counted twice", Math.abs(got - sum) < 1e-6, got + " " + sum);
        // a trip: a day in Zurich, the next in New York (the day zone of each day, Json.dayZone in the app); every step lands
        // in exactly one of the windows that touch, the hours both share are not counted twice
        java.time.ZoneId ny = java.time.ZoneId.of("America/New_York");
        java.time.LocalDate trip = java.time.LocalDate.of(2026, 6, 10);
        double z0 = trip.atStartOfDay(z).toEpochSecond(), z1 = trip.plusDays(1).atStartOfDay(z).toEpochSecond();
        double n0 = trip.plusDays(1).atStartOfDay(ny).toEpochSecond(), n1 = trip.plusDays(2).atStartOfDay(ny).toEpochSecond();
        PhoneSteps.Interval flight = new PhoneSteps.Interval(z1 - 4 * 3600, n0 + 2 * 3600, 1200, 1, 3);
        double zurichDay = PhoneSteps.total(Arrays.asList(flight), z0, z1), gap = PhoneSteps.total(Arrays.asList(flight), z1, n0),
                nyDay = PhoneSteps.total(Arrays.asList(flight), n0, n1);
        check("#329b days: a trip, the Zurich day and the New York day each get their own hours, the six between neither", Math.abs(zurichDay + gap + nyDay - 1200) < 1e-9
                && Math.abs(zurichDay - 400) < 1e-9 && Math.abs(nyDay - 200) < 1e-9 && n0 - z1 == 6 * 3600, zurichDay + " " + gap + " " + nyDay);
        check("#329b days: a day no interval reaches is NaN, not 0", Double.isNaN(PhoneSteps.total(Arrays.asList(flight), n1, n1 + 86400)));
        // the Data check: counts, dates, day totals; never a clock time, never "min"
        long now = (long) z1;
        List<PhoneSteps.Reading> demo = PhoneSteps.demo(trip, 20, now, z, new Random(1));
        String lines = PhoneSteps.checkLines(demo, PhoneSteps.intervals(demo), 2, now - 3600, trip, 14, z);
        check("#329b data check: counts and day totals, no clock time", lines.startsWith("Phone steps: ") && lines.contains("phone starts") && lines.contains("Steps per day")
                && lines.contains("No sensor value in time: 2 times, the last on " + trip) && !java.util.regex.Pattern.compile("\\d{1,2}:\\d{2}").matcher(lines).find()
                && !java.util.regex.Pattern.compile("\\d+ ?min\\b").matcher(lines).find(), lines);
        eq("#329b data check: nothing stored", PhoneSteps.checkLines(new ArrayList<PhoneSteps.Reading>(), new ArrayList<PhoneSteps.Interval>(), 0, 0, trip, 14, z),
                "Phone steps: no readings stored\n");
        // the demo: realistic days (some thousand steps), one restart, readings about hourly by day
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 8);
        long dnow = today.atStartOfDay(z).toEpochSecond() + 15 * 3600;
        List<PhoneSteps.Reading> dm = PhoneSteps.demo(today, 35, dnow, z, new Random(329));
        PhoneSteps.Counts dc = PhoneSteps.counts(dm);
        double[] dd = PhoneSteps.days(PhoneSteps.intervals(dm), today.minusDays(34), today.minusDays(1), z);
        double lo = 1e9, hi = 0;
        for (double v : dd) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        check("#329b demo: two phone starts, readings about hourly, nothing left out", dc.boots == 2 && dc.readings > 35 * 15 && dc.fell == 0 && dc.implausible == 0, dc.readings);
        check("#329b demo: every day between 4,000 and 14,000 steps", lo > 4000 && hi < 14000, lo + " " + hi);
    }

    /** The phone's own job: a busy ring never skips it, and it is a job of its own. */
    static void phoneJob329() {
        BackgroundJob.Inputs in = new BackgroundJob.Inputs();
        in.phoneSteps = true;
        in.ringBusy = true;
        BackgroundJob.Due d = BackgroundJob.due(in);
        check("#329b job: a busy ring does not skip the phone", d.phone && !d.ring && !d.watch && !d.run());
        in.ringBusy = false;
        in.demo = true;
        check("#329b job: demo mode does not skip the phone (its readings go to the real file)", BackgroundJob.due(in).phone);
        in.phoneSteps = false;
        check("#329b job: nothing when the phone does not count steps", !BackgroundJob.due(in).phone);
        // every combination of the ring and watch inputs: the phone part follows only phoneSteps, the rest is as before
        boolean ok = true;
        for (boolean busy : new boolean[] {false, true}) {
            for (boolean bg : new boolean[] {false, true}) {
                for (int ks : new int[] {KeyState.NONE, KeyState.READABLE, KeyState.UNREADABLE, KeyState.NO_RING}) {
                    for (boolean phone : new boolean[] {false, true}) {
                        BackgroundJob.Inputs x = new BackgroundJob.Inputs();
                        x.ringBusy = busy;
                        x.backgroundSync = bg;
                        x.keyState = ks;
                        x.now = 1_780_000_000L;
                        x.phoneSteps = phone;
                        BackgroundJob.Inputs y = new BackgroundJob.Inputs();
                        y.ringBusy = busy;
                        y.backgroundSync = bg;
                        y.keyState = ks;
                        y.now = 1_780_000_000L;
                        BackgroundJob.Due dx = BackgroundJob.due(x), dy = BackgroundJob.due(y);
                        ok &= dx.phone == phone && dx.ring == dy.ring && dx.watch == dy.watch && (dx.log == null ? dy.log == null : dx.log.equals(dy.log));
                        ok &= !(ks == KeyState.NO_RING && dx.ring);
                    }
                }
            }
        }
        check("#329b job: the phone part changes nothing of the ring and watch parts; no ring never syncs", ok);
        BackgroundJob.Spec p = BackgroundJob.phoneWanted("app.openring/.sync.PhoneJob"), s = BackgroundJob.wanted("app.openring/.sync.SyncJob");
        check("#329b job: about every hour with 15 minutes flex, kept after a restart, no battery condition", p.periodic && p.interval == 3_600_000L && p.flex == 900_000L
                && p.persisted && !p.batteryNotLow && !p.charging && !p.equals(s), p);
        check("#329b job: an equal pending job is kept, none is scheduled, off is cancelled", BackgroundJob.decide(true, true, p, p) == BackgroundJob.Action.KEEP
                && BackgroundJob.decide(true, true, null, p) == BackgroundJob.Action.SCHEDULE && BackgroundJob.decide(false, true, p, p) == BackgroundJob.Action.CANCEL);
        // the Settings block
        check("#329b Settings: the Phone block and its card", SettingsBlocks.of("phone") == SettingsBlocks.PHONE && SettingsBlocks.startsOpen(SettingsBlocks.PHONE, "phone")
                && !SettingsBlocks.startsOpen(SettingsBlocks.PHONE, "watch") && "Phone".equals(SettingsBlocks.PHONE_TITLE));
        eq("#329b Settings: the Phone header", SettingsBlocks.phoneStatus(false, true, true, 1, "20 minutes ago") + " / " + SettingsBlocks.phoneStatus(false, true, true, 0, null)
                + " / " + SettingsBlocks.phoneStatus(false, true, false, 0, null) + " / " + SettingsBlocks.phoneStatus(false, false, false, 0, null)
                + " / " + SettingsBlocks.phoneStatus(true, false, false, 0, null),
                "Counting steps, last reading 20 minutes ago / Counting steps, no reading yet / Steps from this phone are off / This phone has no step counter"
                        + " / Made up phone steps, demo data");
    }

    /**
     * The walk without a ring at the core decision level: Setup, "Use without a ring", the main screen, Settings and back,
     * and Setup never opens again; pairing a ring later ends it.
     */
    static void noRingWalk329() {
        // a fresh phone: no key, not set up, no choice yet
        boolean stored = false, noRing = false, setupDone = false, unseen = false;
        int st = KeyState.of(stored, false, noRing);
        check("#329b walk: a fresh phone opens Setup", KeyState.opensSetup(false, setupDone, st, unseen) && st == KeyState.NONE);
        // Setup, "Use without a ring" (Prefs.setNoRing): the main screen
        noRing = true;
        st = KeyState.of(stored, false, noRing);
        check("#329b walk: Use without a ring gives no ring by choice", st == KeyState.NO_RING && KeyState.ofStored(stored, noRing) == KeyState.NO_RING);
        for (int round = 0; round < 3; round++) {
            check("#329b walk: the main screen does not open Setup (" + round + ")", !KeyState.opensSetup(false, setupDone, st, unseen));
            check("#329b walk: Settings does not send to Setup (" + round + ")", !KeyState.needsSetup(setupDone, KeyState.ofStored(stored, noRing)));
            check("#329b walk: the main screen after a failed demo does not either (" + round + ")", !KeyState.needsSetup(setupDone, KeyState.ofStored(stored, noRing)));
            check("#329b walk: nothing syncs, ring actions show Add a ring (" + round + ")", !KeyState.syncs(st) && KeyState.addRingInstead(false, st)
                    && !KeyState.hasRing(KeyState.ofStored(stored, noRing)));
        }
        check("#329b walk: in demo mode the made up ring works as before", !KeyState.addRingInstead(true, st) && !KeyState.opensSetup(true, setupDone, st, unseen));
        // Add a ring, paired: the key wins (Prefs.pairDone also forgets the choice)
        stored = true;
        setupDone = true;
        unseen = true;
        st = KeyState.of(stored, true, noRing);
        check("#329b walk: a ring paired later is a ring again, its key page shows once", st == KeyState.READABLE && KeyState.opensSetup(false, setupDone, st, unseen)
                && KeyState.syncs(st) && !KeyState.addRingInstead(false, st));
        unseen = false;
        check("#329b walk: then the main screen stays", !KeyState.opensSetup(false, setupDone, st, false));
        // an unreadable key is still not "no ring"
        check("#329b walk: an unreadable key wins over the choice", KeyState.of(true, false, true) == KeyState.UNREADABLE && KeyState.ofStored(true, true) == KeyState.READABLE);
        check("#329b walk: the old two argument forms never give no ring", KeyState.of(false, false) == KeyState.NONE && KeyState.ofStored(false) == KeyState.NONE);
    }
}
