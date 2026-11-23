import app.openring.core.Tour;

import java.util.*;

/**
 * The short tour (issue #453, {@code core/Tour}): the steps in their order with their parts and tabs, which taps move on,
 * Next and Back past steps whose part is not on screen, the demo's ending, when the tour starts by itself (the demo's first
 * look, the first sync with a real device, never again once done or skipped, never while tiles are rearranged), and every
 * text plain. Run with tests/run_tests.sh.
 */
public class TourTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    public static void main(String[] args) {
        steps();
        walk();
        skipping();
        start();
        labels();
        texts();
        System.out.println("tour: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void steps() {
        eq("seven steps", Tour.STEPS.size(), Tour.COUNT);
        StringBuilder keys = new StringBuilder();
        for (Tour.Step s : Tour.STEPS) keys.append(s.key).append(' ');
        eq("the issue's order", keys.toString().trim(), "sync scores tabs chart tile life settings");
        eq("Sync, the tab bar and the gear are on every tab", Tour.STEPS.get(0).tab + " " + Tour.STEPS.get(2).tab + " " + Tour.STEPS.get(6).tab,
                Tour.ANY + " " + Tour.ANY + " " + Tour.ANY);
        eq("the scores and the life factors on Today", Tour.STEPS.get(1).tab + " " + Tour.STEPS.get(5).tab, Tour.TODAY + " " + Tour.TODAY);
        eq("the chart and its tile's title on Sleep", Tour.STEPS.get(3).tab + " " + Tour.STEPS.get(4).tab, Tour.SLEEP + " " + Tour.SLEEP);
        eq("Today and Sleep are the tabs of ui/Pages", Tour.TODAY + " " + Tour.SLEEP, "0 1");
        for (Tour.Step s : Tour.STEPS) {
            boolean trying = Tour.CHART.equals(s.key) || Tour.TILE.equals(s.key);
            check("a tap moves on except where trying it is the point: " + s.key, s.tapMovesOn != trying);
        }
        check("the chart is skipped when its tile is folded", !Tour.STEPS.get(3).foldedOk);
        check("the scores and the life factors show by their tile when folded", Tour.STEPS.get(1).foldedOk && Tour.STEPS.get(5).foldedOk);
        Set<String> unique = new HashSet<String>();
        for (Tour.Step s : Tour.STEPS) unique.add(s.key);
        eq("each part once", unique.size(), Tour.COUNT);
    }

    static void walk() {
        eq("Next from the first step", Tour.step(0, 1, true), 1);
        eq("Back from the third step", Tour.step(2, -1, true), 1);
        eq("Back on the first step closes", Tour.step(0, -1, true), Tour.CLOSED);
        eq("Back on the first step closes with a real device too", Tour.step(0, -1, false), Tour.CLOSED);
        eq("after the last step: the demo's ending", Tour.step(Tour.COUNT - 1, 1, true), Tour.END);
        eq("after the last step with a real device: done", Tour.step(Tour.COUNT - 1, 1, false), Tour.CLOSED);
        eq("Back from the demo's ending: the last step", Tour.step(Tour.END, -1, true), Tour.COUNT - 1);
        eq("Next past the ending stays on it (its own buttons end it)", Tour.step(Tour.END, 1, true), Tour.END);
        // the whole walk forward in demo mode
        int s = -1, n = 0;
        List<Integer> seen = new ArrayList<Integer>();
        while (n++ < 20) {
            s = Tour.step(s, 1, true);
            seen.add(s);
            if (s == Tour.END) break;
        }
        eq("the demo walk: every step, then the ending", seen.toString(), "[0, 1, 2, 3, 4, 5, 6, 7]");
    }

    static boolean[] all(boolean v) {
        boolean[] f = new boolean[Tour.COUNT];
        Arrays.fill(f, v);
        return f;
    }

    static void skipping() {
        boolean[] f = all(true);
        f[3] = false; // no night: no chart
        eq("Next past a missing chart", Tour.skip(2, 1, f, true), 4);
        eq("Back past a missing chart", Tour.skip(4, -1, f, true), 2);
        f = all(true);
        f[3] = f[4] = f[5] = false;
        eq("Next past three missing steps in a row", Tour.skip(2, 1, f, false), 6);
        f = all(true);
        f[6] = false;
        eq("a missing last step: the demo's ending", Tour.skip(5, 1, f, true), Tour.END);
        eq("a missing last step with a real device: done", Tour.skip(5, 1, f, false), Tour.CLOSED);
        f = all(true);
        f[0] = false;
        eq("a missing first step: the tour begins at the second", Tour.skip(-1, 1, f, true), 1);
        eq("Back to a missing first step closes", Tour.skip(1, -1, f, true), Tour.CLOSED);
        f = all(false);
        eq("nothing on screen in demo mode: only the ending", Tour.skip(-1, 1, f, true), Tour.END);
        eq("nothing on screen with a real device: closed at once", Tour.skip(-1, 1, f, false), Tour.CLOSED);
        eq("Back from the ending past missing steps closes", Tour.skip(Tour.END, -1, f, true), Tour.CLOSED);
        f = all(true);
        f[6] = f[5] = false;
        eq("Back from the ending past missing last steps", Tour.skip(Tour.END, -1, f, true), 4);
        eq("the count keeps the fixed numbers", Tour.count(0) + ", " + Tour.count(4) + ", " + Tour.count(6), "1 of 7, 5 of 7, 7 of 7");
        eq("no count on the ending or when closed", Tour.count(Tour.END) + Tour.count(Tour.CLOSED), "");
    }

    static void start() {
        eq("the demo's first look starts it", Tour.autoStart(true, false, false, 0, 0), Tour.DEMO);
        eq("the demo again after it was done or skipped: no", Tour.autoStart(true, true, false, 0, 0), Tour.NONE);
        eq("the first sync with a real device starts it", Tour.autoStart(false, false, false, 0, 1763900000L), Tour.FIRST_SYNC);
        eq("a later sync does not", Tour.autoStart(false, false, false, 1763800000L, 1763900000L), Tour.NONE);
        eq("no sync yet (or a failed first sync) does not", Tour.autoStart(false, false, false, 0, 0), Tour.NONE);
        eq("the first sync after the demo's tour: not again", Tour.autoStart(false, true, false, 0, 1763900000L), Tour.NONE);
        eq("never while the tiles are rearranged (demo)", Tour.autoStart(true, false, true, 0, 0), Tour.NONE);
        eq("never while the tiles are rearranged (first sync)", Tour.autoStart(false, false, true, 0, 1763900000L), Tour.NONE);
    }

    static void labels() {
        eq("Next on the steps", Tour.nextLabel(0, true) + " " + Tour.nextLabel(5, false), "Next Next");
        eq("Next on the demo's last step (the ending follows)", Tour.nextLabel(6, true), "Next");
        eq("Done on the last step with a real device", Tour.nextLabel(6, false), "Done");
        eq("the ending: add a device when none is set up", Tour.endButton(false), "Add my device");
        eq("the ending: back to the person's own data", Tour.endButton(true), "Back to my data");
        eq("the first sync's step 1", Tour.text(0, true), Tour.SYNC_FIRST);
        eq("step 1 otherwise", Tour.text(0, false), Tour.SYNC_TEXT);
        eq("the first sync changes only step 1", Tour.text(1, true), Tour.STEPS.get(1).text);
        eq("the ending's text", Tour.text(Tour.END, false), Tour.END_TEXT);
        eq("closed has no text", Tour.text(Tour.CLOSED, false), "");
    }

    static void texts() {
        for (String t : Tour.texts()) {
            check("plain, no dash as punctuation: " + t, !t.isEmpty() && !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
            check("short (a bubble, under 110 characters): " + t, t.length() < 110);
            int sentences = 0;
            for (String part : t.split("[.!?](\\s|$)")) if (!part.trim().isEmpty()) sentences++;
            check("at most two sentences: " + t, sentences <= 2);
        }
        check("the issue's step texts", Tour.STEPS.get(1).text.equals("Your scores for the day. Tap one to see why.")
                && Tour.STEPS.get(6).text.equals("Your devices, backups and what the app may use are here."));
    }
}
