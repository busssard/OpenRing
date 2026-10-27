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
        check("average line: mixed levels name the Calm exception like the rule (#30)",
                WakeUps.averageLine(-1).contains("1 min on the phone or next to missing ring data") && WakeUps.rule(C).contains("1 min on the phone or next to missing ring data"));
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
        check("pattern: an afternoon night is in bed at 15:00", p.inBed(binAt(15 * 60)) > 0.3);
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
        check("clock average: the old maths gave 04:00 there", Math.abs(oldClockMean(new double[] {15 * 60 + 50, 16 * 60 + 10})[0] - 4 * 60) < 1e-6);
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

    public static void main(String[] args) throws Exception {
        protocol();
        history();
        bundles();
        client();
        analysis();
        dayHeart();
        vo2max();
        longWake();
        nightNumbers();
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
        texts();
        unitTexts();
        drafts();
        oneRun();
        keptHeight();
        screenTime();
        screenAwakeGrid();
        screenApps();
        screenUnlock();
        screenAppReads();
        screenScore();
        decoders();
        ringTasks();
        liveFeed();
        requests();
        auth();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
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
        check("shown screen: asking starts nothing", !shown.running() && shown.start());
        eq("shown screen: asking ends nothing", shown.attached(), "turned settings");
        check("shown screen: the job still runs", shown.running());
        shown.detach("turned settings");
        check("shown screen: none after Back", shown.attached() == null);
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
        Scores.ReadinessInput ri = new Scores.ReadinessInput();
        for (double v : new double[] {-0.04, 0.04, 0.05, -0.03, 0.0496, 0.6}) {
            ri.tempDev = v;
            eq("readiness: temperature detail " + v + " as the card", part(Scores.readiness(ri), "temperature").detail, Texts.signed(v, Locale.US) + " \u00b0C");
        }
        ri.tempDev = -0.04;
        eq("readiness: -0.04 is 0.0 \u00b0C", part(Scores.readiness(ri), "temperature").detail, "0.0 \u00b0C");

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
        eq("uses: one", Texts.word(1, "time", "times"), "time");
    }

    /** Issues #89 and #92: counted words agree with the number, toasts stay short, raw errors never reach the screen. */
    static void texts() throws IOException {
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
            check("save failed " + backup + ": no dash as punctuation", !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
        }
        // issue #115: a copy of the history that did not pass its check
        eq("not checked: backup", Texts.notChecked(true),
                "The backup was not saved, because the copy of your history did not pass its check. Nothing on this phone was changed. Try again in a minute.");
        eq("not checked: export", Texts.notChecked(false),
                "The export was not saved, because the copy of the history did not pass its check. Nothing on this phone was changed. Try again in a minute.");
        for (boolean backup : new boolean[] {false, true}) {
            String t = Texts.notChecked(backup);
            check("not checked " + backup + ": goes into the dialog, no dash as punctuation", !Texts.fitsToast(t) && !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
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
        int toasts = 0;
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
                }
            }
        }
        check("toast scan: toast texts found", toasts > 0);

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
        eq("shares: thirds add up to 100, not 99", third[0] + third[1] + third[2], 100);
        eq("shares: thirds tie goes to the first stage", Arrays.toString(third), "[34, 33, 33]");
        int[] up = StageShare.sleepShares(45, 245, 210);
        // exact 9, 49, 42 with no remainder
        eq("shares: exact values stay", Arrays.toString(up), "[9, 49, 42]");
        int[] over = StageShare.sleepShares(5.5, 5.5, 89); // 5.5 + 5.5 + 89, plain rounding gives 6 + 6 + 89 = 101
        eq("shares: no 101", over[0] + over[1] + over[2], 100);
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
        eq("alone the rows add up to one more", Math.round(deep) + Math.round(light) + Math.round(rem), 451L);
        eq("total sleep shows 7h 30m", Math.round(asleep), 450L);
        long[] t = StageShare.roundTogether(new double[] {deep, light, rem}, 450);
        eq("together they add up to 450, ties to the earlier stage", Arrays.toString(t), "[48, 260, 142]");
        // with awake and the shown window 22:25 to 06:47
        double start = 1761431120, end = start + 501.8333 * 60; // 22:25:20 to 06:47:10 UTC
        long inBedShown = WakeUps.shownMinutes(start, end);
        eq("shown time in bed is the minutes between the shown clocks", inBedShown, 502L);
        double inBed = (end - start) / 60, awake = inBed - asleep;
        eq("alone the rows with awake add up to 503", Math.round(deep) + Math.round(light) + Math.round(rem) + Math.round(awake), 503L);
        double[] m = StageShare.shownMinutes(deep, light, rem, awake, 0, asleep, inBed, inBedShown);
        eq("sleep page rows", Arrays.toString(m), "[48.0, 260.0, 142.0, 52.0, 0.0]");
        eq("sleep rows add up to total sleep", m[0] + m[1] + m[2], 450.0);
        eq("all rows add up to the shown time in bed", m[0] + m[1] + m[2] + m[3] + m[4], 502.0);
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
            boolean ok = r[0] + r[1] + r[2] == Math.round(dd + ll + rr) && r[0] + r[1] + r[2] + r[3] + r[4] == shownIb;
            double[] ex = {dd, ll, rr, aa, nn};
            for (int k = 0; k < 5; k++) if (Math.abs(r[k] - ex[k]) >= 2) ok = false;
            for (int k = 0; k < 3; k++) if (Math.abs(r[k] - ex[k]) >= 1) ok = false;
            if (!ok && bad++ < 3) System.out.println("  night " + Arrays.toString(ex) + " in bed " + ib + " shown " + shownIb + " got " + Arrays.toString(r));
        }
        eq("2000 nights: rows add up, sleep stages within a minute, awake and no data within two", bad, 0);
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
        eq("sync error: GATT 133 sentence", SyncError.plain("connection failed (GATT status 133)"),
                "The ring could not be reached. Keep it close to the phone (on its charger is best) and sync again.");
        check("sync error: unknown names the details", SyncError.plain("Unexpected error: java.lang.IllegalStateException").endsWith(SyncError.DETAILS)
                && SyncError.plain(null).endsWith(SyncError.DETAILS));
        check("sync error: key refused says how to set up again", SyncError.plain("authentication failed: x").endsWith(SyncError.SETUP_AGAIN));
        for (SyncError.Kind kd : SyncError.Kind.values()) {
            String raw = null;
            for (Object[] k : cases) if (k[1] == kd) { raw = (String) k[0]; break; }
            String t = SyncError.plain(raw);
            String low = t.toLowerCase(Locale.ROOT);
            check("sync error: plain text for " + kd, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014")
                    && !low.contains("gatt") && !low.contains("mtu") && !low.contains("exception") && t.endsWith("."));
        }
        ringError(cases);
    }

    /** The same raw texts after pairing, a Settings action, live heart rate or game motion failed (issue #60). */
    static void ringError(Object[][] cases) throws java.io.IOException {
        SyncError.Task P = SyncError.Task.PAIR, A = SyncError.Task.ACTION, L = SyncError.Task.LIVE;
        // every kind and task: plain, no jargon or raw codes, a sync is never mentioned outside a sync
        for (Object[] k : cases) {
            for (SyncError.Task task : SyncError.Task.values()) {
                String t = SyncError.plain((String) k[0], task);
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
        for (Object[] k : cases) {
            String g = SyncError.game((String) k[0]);
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

    /** Android usage event types the way data/ScreenUse maps them (copied here, that class needs Android). */
    static int screenKind(int type) {
        switch (type) {
            case 15: return ScreenTime.ON;
            case 16: return ScreenTime.OFF;
            case 26: return ScreenTime.SHUTDOWN;
            case 27: return ScreenTime.STARTUP;
            case 17: return ScreenTime.LOCKED;
            case 18: return ScreenTime.UNLOCKED;
            case 1: case 7: return ScreenTime.ALIVE;
            default: return 0;
        }
    }

    static int appKind(int type) {
        switch (type) {
            case 1: return ScreenTime.APP_ON;
            case 2: return ScreenTime.APP_OFF;
            case 16: return ScreenTime.OFF;
            case 26: return ScreenTime.SHUTDOWN;
            case 27: return ScreenTime.STARTUP;
            default: return 0;
        }
    }

    /**
     * Reads a log the way ScreenUse.collect does, once at each of {@code reads}: screen sessions from
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
            List<Double> at = new ArrayList<Double>(), st = new ArrayList<Double>();
            List<Integer> ak = new ArrayList<Integer>(), sk = new ArrayList<Integer>();
            List<String> ap = new ArrayList<String>();
            for (int i = 0; i < t.length && t[i] < now; i++) {
                int a = appKind(type[i]), k = screenKind(type[i]);
                if (a != 0 && t[i] >= appFrom) {
                    at.add(t[i]);
                    ak.add(a);
                    ap.add(a == ScreenTime.APP_ON || a == ScreenTime.APP_OFF ? pkg[i] : null);
                }
                if (k != 0 && t[i] >= screenFrom) {
                    st.add(t[i]);
                    sk.add(k);
                }
            }
            double[] atA = new double[at.size()], stA = new double[st.size()];
            int[] akA = new int[ak.size()], skA = new int[sk.size()];
            for (int i = 0; i < atA.length; i++) {
                atA[i] = at.get(i);
                akA[i] = ak.get(i);
            }
            for (int i = 0; i < stA.length; i++) {
                stA[i] = st.get(i);
                skA[i] = sk.get(i);
            }
            ScreenTime.store(apps, ScreenTime.appSessions(atA, akA, ap.toArray(new String[0]), appFrom, now));
            for (double[] s : ScreenTime.sessions(stA, skA, screenFrom, now)) screen.put(s[0], s);
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
        check("rounding: one by one the rows add up to 54", single == 54);
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
        check("day heart: the old 98th percentile cut the run off", oldHigh < 70);
        eq("day heart: Highest is the line's highest point", d.high, line.max());
        check("day heart: Highest shows the run", d.high > 100);
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
    }

    /** Issue #44: the weekly parts of the activity score look at the worn days only. */
    static Night.Contributor part(Night.Score s, String key) {
        for (Night.Contributor c : s.contributors) if (c.key.equals(key)) return c;
        return null;
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
        check("ring awake gap counts as awake", woke.awakeMin - calm.awakeMin >= 70 && woke.awakenings >= 1);
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
        check("#118 ring stages all awake: score from the rest", sa.value >= 0 && sa.value < 40);
        // a real night with many wake ups keeps it, wake ups counted (CLAUDE.md: never hide real awakenings)
        Night waker = ringStaged(digits("2", 100, "4", 10, "2", 100, "4", 10, "2", 100, "4", 10, "2", 100));
        Night.Contributor wr = part(Scores.sleep(waker, 0, 35), "restfulness");
        check("#118 night with wake ups keeps Restfulness", wr != null && wr.detail.startsWith("3 wake ups") && wr.score < 100);

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
        // the sequence of RingManager.startLive: the new screen takes token 3, the old task ends and
        // clears only its own mark, so a third screen rejoins the new session
        java.util.concurrent.atomic.AtomicInteger session = new java.util.concurrent.atomic.AtomicInteger(1), current = new java.util.concurrent.atomic.AtomicInteger(1);
        current.incrementAndGet(); // stopLive
        boolean rejoined = RingTasks.rejoinLive(session.get(), current.get(), false, false);
        if (!rejoined) session.set(current.incrementAndGet());
        session.compareAndSet(1, 0); // the old session's cleanup ends
        check("live: after a turn the new screen has its own session", !rejoined && session.get() == 3);
        check("live: a screen opened next rejoins the new session", RingTasks.rejoinLive(session.get(), current.get(), false, false));

        // #43 C: only the last sync shows its line
        eq("sync end: another sync runs, nothing shown", RingTasks.syncEndStatus(1, "Up to date", null), null);
        eq("sync end: the last sync shows its own line", RingTasks.syncEndStatus(0, "Sync stopped", "Up to date"), "Sync stopped");
        eq("sync end: a sync that never got the ring shows the line left behind", RingTasks.syncEndStatus(0, null, "Up to date"), "Up to date");
        eq("sync end: nothing to show", RingTasks.syncEndStatus(0, null, null), null);
        check("texts: busy and delete messages fit the rules", !Texts.SYNC_BUSY.contains(" - ") && !Texts.DELETE_BUSY.contains(" - ")
                && Texts.DELETE_BUSY.startsWith("Nothing was deleted") && Texts.DELETE_FAILED.startsWith("Nothing was deleted")
                && !Texts.LIVE_BUSY.contains(" - ") && !Texts.LIVE_BUSY.contains("syncing") && !Texts.SYNC_BUSY.contains("ring task"));
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
        check("touch: a shown list of another size counts as the real one",
                DialMath.touch((13 * 60 + 2) / 1440.0, d, from, to, z, 288, real, new ArrayList<double[]>(), none).session == -1);
        // a use at 23:58 drawn 5 minutes long lights 00:00 of the next day
        List<double[]> late = new ArrayList<double[]>();
        late.add(new double[] {from - 120, from - 100});
        List<double[]> lateW = ScreenTime.widen(late, 300);
        check("touch: a use before midnight drawn over it lights 00:00", DialMath.fill(lateW, from, to, z, 288)[0] > 0);
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
            check(id + ": a night over Saturday's midnight still has it", ClockChange.find(z, s1 - 3 * 3600, s1 + 5 * 3600) != null);
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
}
