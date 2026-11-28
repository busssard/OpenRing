package app.openring.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A short tour (issue #453, owner's wish of 23 November: demo mode is an overview, a tutorial helps more than a copy of every
 * function): seven steps over the main screen, each a dim layer with a hole around one real part of the app and one or two
 * sentences. Plain Java so the JVM tests cover the steps, their texts and every decision; {@code ui/TourOverlay} only draws
 * what this class says. A step whose part is not on screen is skipped, never shown pointing at nothing.
 */
public final class Tour {
    private Tour() {}

    /** The parts a step points at; the screens mark the view of each where they build it. */
    public static final String SYNC = "sync", SCORES = "scores", TABS = "tabs", CHART = "chart", TILE = "tile", LIFE = "life",
            SETTINGS = "settings";

    /** A step's tab: any (the part is in the header or the tab bar), Today or Sleep (the numbers of {@code ui/Pages.TODAY, SLEEP}). */
    public static final int ANY = -1, TODAY = 0, SLEEP = 1;

    /** One step: the part it points at, the tab it needs, its text and how a tap on the part counts. */
    public static final class Step {
        public final String key;
        public final int tab;
        public final String text;
        /**
         * A tap on the part also moves the tour on (false where trying it is the point: the chart, a tile's title, and the tabs,
         * where a tap opens the tab tapped and the tour stays there until Next).
         */
        public final boolean tapMovesOn;
        /** A part inside a folded tile is shown by its tile; false: the step is skipped then (the chart needs to be open). */
        public final boolean foldedOk;

        Step(String key, int tab, String text, boolean tapMovesOn, boolean foldedOk) {
            this.key = key;
            this.tab = tab;
            this.text = text;
            this.tapMovesOn = tapMovesOn;
            this.foldedOk = foldedOk;
        }
    }

    public static final String SYNC_TEXT = "Sync reads your ring or watch over Bluetooth. Nothing leaves your phone.";
    /** Step 1 when the tour starts after the first sync with a real device. */
    public static final String SYNC_FIRST = "Your first sync worked: Sync reads your ring or watch over Bluetooth. Nothing leaves your phone.";

    /** The steps in their order. */
    public static final List<Step> STEPS;

    static {
        List<Step> s = new ArrayList<Step>();
        s.add(new Step(SYNC, ANY, SYNC_TEXT, true, true));
        s.add(new Step(SCORES, TODAY, "Your scores for the day. Tap one to see why.", true, true));
        s.add(new Step(TABS, ANY, "Sleep, Ready, Activity, Body and Screen show the details. Tap one to open it.", false, true));
        s.add(new Step(CHART, SLEEP, "Touch and slide on any chart to read the exact time and value. Try it here.", false, false));
        s.add(new Step(TILE, SLEEP, "Tap a title to fold a tile, tap it again to open it. In Settings, Rearrange tiles puts them in your order.", false, true));
        s.add(new Step(LIFE, TODAY, "Log what shaped your day, like Meditation or Stress. Sleep, Ready and Body show it next to your nights.", true, true));
        s.add(new Step(SETTINGS, ANY, "Your devices, backups and what the app is allowed to use are here.", true, true));
        STEPS = Collections.unmodifiableList(s);
    }

    /** The number of steps. */
    public static final int COUNT = 7;
    /** The demo's ending after the last step ("That was the demo."). */
    public static final int END = COUNT;
    /** The tour is closed (skipped, done, or Back on its first step). */
    public static final int CLOSED = -1;

    /** The labels of the bubble. */
    public static final String NEXT = "Next", DONE = "Done", SKIP = "Skip the tour", AGAIN = "Show the tour again";
    /** The demo's ending. */
    public static final String END_TEXT = "That was the demo. Add your own ring or watch to see your own data, or keep looking around.",
            END_BACK = "That was the demo. Go back to your own data, or keep looking around.", ADD_DEVICE = "Add my device", BACK_TO_MINE = "Back to my data", KEEP_LOOKING = "Keep looking";

    /** The ending's text: add a device when none is set up, else back to the person's own data (as {@link #endButton}). */
    public static String endText(boolean hasDevice) {
        return hasDevice ? END_BACK : END_TEXT;
    }

    /**
     * The text of step {@code i} (0 based); {@code firstSync}: the tour started after the first sync with a real device. Empty
     * for the ending, whose text depends on a device ({@link #endText}).
     */
    public static String text(int i, boolean firstSync) {
        if (i < 0 || i >= COUNT) return "";
        return i == 0 && firstSync ? SYNC_FIRST : STEPS.get(i).text;
    }

    /** "3 of 7" for step {@code i} (0 based); the fixed numbers, so a skipped step leaves its number out. Empty for the ending. */
    public static String count(int i) {
        return i >= 0 && i < COUNT ? (i + 1) + " of " + COUNT : "";
    }

    /**
     * The step to try after {@code from} going one way ({@code dir} 1 Next, -1 Back): the next number, after the last step the
     * demo's ending (or closed with a real device), before the first closed. The overlay tries it and, when its part is not on
     * screen, calls this again from there in the same direction. {@code from} may be {@link #END} (Back from the ending).
     */
    public static int step(int from, int dir, boolean demo) {
        int to = from + (dir < 0 ? -1 : 1);
        if (to < 0) return CLOSED;
        if (to >= COUNT) return dir < 0 ? COUNT - 1 : demo ? END : CLOSED;
        return to;
    }

    /**
     * The step to try when the part of {@code missing} is not on screen (no night, a folded chart, only the phone): the next one
     * the same way ({@link #step}), never a hole on nothing. {@code misses} counts the parts missed in a row; past two rounds
     * of the steps the tour closes (a page that keeps changing under it must not keep it walking).
     */
    public static int afterMissing(int missing, int dir, boolean demo, int misses) {
        return misses > 2 * COUNT ? CLOSED : step(missing, dir, demo);
    }

    /** The label of the bubble's forward button on step {@code i}: Done on the last step with a real device. */
    public static String nextLabel(int i, boolean demo) {
        return i == COUNT - 1 && !demo ? DONE : NEXT;
    }

    /** The ending's first button: add a device when none is set up, else back to the person's own data. */
    public static String endButton(boolean hasDevice) {
        return hasDevice ? BACK_TO_MINE : ADD_DEVICE;
    }

    /** What starts the tour by itself: nothing, the demo's first opening, the first sync with a real device. */
    public static final int NONE = 0, DEMO = 1, FIRST_SYNC = 2;

    /**
     * Whether the tour starts by itself now. {@code seen}: it was done or skipped once (one flag for demo and real); never
     * while the tiles are rearranged. In demo mode it starts at the first look. With a real device after the first successful
     * sync: {@code afterSetup} the saved mark that setup ended before any sync ({@link #waitsForFirstSync}, issue #476, so a
     * main screen created again after that sync still starts it), else {@code syncedBefore} the newest good sync (ring or
     * watch) this screen knew, 0 for none; {@code syncedNow} the newest now (a failed sync sets none).
     */
    public static int autoStart(boolean demo, boolean seen, boolean editing, boolean afterSetup, long syncedBefore, long syncedNow) {
        if (seen || editing) return NONE;
        if (demo) return DEMO;
        return syncedNow > 0 && (afterSetup || syncedBefore <= 0) ? FIRST_SYNC : NONE;
    }

    /**
     * Whether setup, ending now, marks the tour to start after the first sync (issue #476): only when no good sync (ring or
     * watch) exists yet, so a new key for a ring that synced before, or a ring added by a person with a watch, starts nothing.
     */
    public static boolean waitsForFirstSync(long syncedNow) {
        return syncedNow <= 0;
    }

    /** Every text of the tour, for the tests. */
    public static List<String> texts() {
        List<String> out = new ArrayList<String>();
        for (Step s : STEPS) out.add(s.text);
        out.add(SYNC_FIRST);
        out.add(END_TEXT);
        out.add(END_BACK);
        out.add(NEXT);
        out.add(DONE);
        out.add(SKIP);
        out.add(AGAIN);
        out.add(ADD_DEVICE);
        out.add(BACK_TO_MINE);
        out.add(KEEP_LOOKING);
        return out;
    }
}
