import app.openring.core.CardSpecs;
import app.openring.core.Layout;
import app.openring.core.SettingsBlocks;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The tiles of every tab (owner's wish of 18 November, core/Layout): stable keys, the merge of a saved order with the page's
 * standard order (new tiles at their standard place, removed tiles skipped but kept), duplicates, empty or broken saved values,
 * the folds that stay as the person left them, the reset, the drag's landing place, and that the screens use it. Run with
 * tests/run_tests.sh.
 */
public class LayoutTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) System.out.println("  got: " + got + " want: " + want);
        check(name, ok);
    }

    public static void main(String[] args) throws Exception {
        keys();
        merge();
        remember();
        moves();
        folds();
        target();
        randomized();
        texts();
        draft();
        sources();
        specs();
        System.out.println("layout: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static List<String> l(String... s) {
        return new ArrayList<String>(Arrays.asList(s));
    }

    static void keys() {
        eq("key from a caption", Layout.key("Resting heart rate, last 30 days"), "resting heart rate last 30 days");
        eq("key from a caption in capitals", Layout.key("KEY METRICS"), "key metrics");
        eq("key: punctuation and spaces are one space", Layout.key("  Last night's   body signals "), "last night s body signals");
        eq("key: a TodayCards key", Layout.key("left_out"), "left out");
        eq("key: degrees and accents dropped", Layout.key("Temperature (°C) été"), "temperature c t");
        eq("key: never longer than the limit", Layout.key("Intensity through the day, a very long caption that goes on").length() <= Layout.KEY_MAX, true);
        eq("key: nothing for a title without letters", Layout.key("--"), "");
        eq("key: null", Layout.key(null), "");
        check("valid key", Layout.valid("key metrics"));
        check("a separator is never valid", !Layout.valid("a|b"));
        check("upper case is not a key", !Layout.valid("Key"));
        check("empty is not a key", !Layout.valid(""));
        eq("the same caption twice gets its own key", Layout.unique(l("contributors", "score", "contributors", "contributors")),
                l("contributors", "score", "contributors 2", "contributors 3"));
        eq("an empty key becomes tile", Layout.unique(l("", "", "x")), l("tile", "tile 2", "x"));
        eq("a raw caption is made a key", Layout.unique(l("Key Metrics")), l("key metrics"));
        eq("tab names", Layout.tab(0) + "," + Layout.tab(5), "today,screen");
        eq("no tab name outside the tabs", Layout.tab(6), null);
        eq("no tab name below", Layout.tab(-1), null);
    }

    static void merge() {
        List<String> std = l("score", "life", "signals", "contributors", "hrv");
        eq("nothing saved: the standard order", Layout.order(l(), std), std);
        eq("nothing saved (null)", Layout.order(null, std), std);
        eq("the saved order", Layout.order(l("hrv", "score", "life", "signals", "contributors"), std), l("hrv", "score", "life", "signals", "contributors"));
        // a new version adds "temperature" after "contributors": it comes right after it in the person's order
        List<String> std2 = l("score", "life", "signals", "contributors", "temperature", "hrv");
        eq("a new tile comes after its standard predecessor", Layout.order(l("contributors", "hrv", "score", "life", "signals"), std2),
                l("contributors", "temperature", "hrv", "score", "life", "signals"));
        eq("a new first tile comes at the top", Layout.order(l("life", "score"), l("banner", "score", "life")), l("banner", "life", "score"));
        eq("two new tiles keep their standard order", Layout.order(l("b", "a"), l("a", "n1", "n2", "b")), l("b", "a", "n1", "n2"));
        // a tile that is not on the page today (no workouts) is skipped, the others keep their order
        eq("a removed tile is skipped", Layout.order(l("hrv", "workouts", "score"), l("score", "hrv")), l("hrv", "score"));
        eq("duplicates in the saved order count once", Layout.order(l("b", "a", "b"), l("a", "b")), l("b", "a"));
        eq("an empty page", Layout.order(l("a"), l()), l());
        // every tile of the page exactly once, whatever was saved
        List<String> got = Layout.order(l("x", "c", "y", "a"), l("a", "b", "c", "d"));
        eq("every tile once", new HashSet<String>(got), new HashSet<String>(l("a", "b", "c", "d")));
        eq("every tile once, no more", got.size(), 4);
        eq("the saved ones first in their order, the new ones after their predecessor", got, l("c", "d", "a", "b"));
    }

    static void remember() {
        eq("nothing saved before", Layout.remember(null, l("b", "a")), l("b", "a"));
        eq("a tile not on the page stays after the one it followed", Layout.remember(l("a", "workouts", "b", "c"), l("c", "a", "b")),
                l("c", "a", "workouts", "b"));
        eq("a first tile not on the page stays first", Layout.remember(l("x", "a", "b"), l("b", "a")), l("x", "b", "a"));
        eq("and comes back at its place", Layout.order(Layout.remember(l("a", "workouts", "b", "c"), l("c", "a", "b")), l("a", "b", "workouts", "c")),
                l("c", "a", "workouts", "b"));
        eq("keys that are not keys are dropped", Layout.remember(l("A|B", "a"), l("a", "Bad")), l("a"));
    }

    static void moves() {
        List<String> k = l("a", "b", "c", "d");
        eq("move down", Layout.move(k, 0, 2), l("b", "c", "a", "d"));
        eq("move up", Layout.move(k, 3, 1), l("a", "d", "b", "c"));
        eq("move to the top", Layout.move(k, 2, 0), l("c", "a", "b", "d"));
        eq("move to the bottom", Layout.move(k, 1, 3), l("a", "c", "d", "b"));
        eq("move past the end stays inside", Layout.move(k, 1, 9), l("a", "c", "d", "b"));
        eq("move before the start stays inside", Layout.move(k, 2, -4), l("c", "a", "b", "d"));
        eq("a move from nowhere changes nothing", Layout.move(k, 7, 0), k);
        eq("the list given is not changed", k, l("a", "b", "c", "d"));
        // saving and reading back
        String saved = Layout.join(Layout.move(k, 3, 0));
        eq("saved as keys between bars", saved, "d|a|b|c");
        eq("read back", Layout.parse(saved), l("d", "a", "b", "c"));
        eq("an empty value", Layout.parse(""), l());
        eq("no value", Layout.parse(null), l());
        eq("a broken value keeps what are keys", Layout.parse("||a|B|c d|c d|ü|  e  |x|y"), l("a", "c d", "e", "x", "y"));
        eq("join leaves out what is not a key", Layout.join(l("a", "Bad", "b|c", "d")), "a|d");
        check("the standard layout: nothing saved", Layout.standard(null, null) && Layout.standard("", "") && Layout.standard("|||", "x"));
        check("a saved order is not the standard", !Layout.standard("a|b", null));
        check("a saved fold is not the standard", !Layout.standard(null, "-a"));
    }

    static void folds() {
        Map<String, Boolean> none = Layout.folds(null);
        check("nothing saved: the standard state", Layout.open(none, "a", true) && !Layout.open(none, "further analysis", false));
        String s = Layout.fold(null, "key metrics", false, true);
        eq("folded is saved", s, "-key metrics");
        check("and stays folded", !Layout.open(Layout.folds(s), "key metrics", true));
        s = Layout.fold(s, "further analysis", true, false);
        check("a tile folded by default stays open once opened", Layout.open(Layout.folds(s), "further analysis", false));
        eq("opened: saved, as it is not its standard", s, "-key metrics|+further analysis");
        s = Layout.fold(s, "key metrics", true, true);
        check("opened again", Layout.open(Layout.folds(s), "key metrics", true));
        // review of #415: one rule for "standard", a tile in its standard state saves nothing, so Settings agrees with the tab
        eq("opened again is its standard: nothing saved for it", s, "+further analysis");
        eq("a broken value keeps the good entries", Layout.folds("+a|-b|xc|-|+|-B|+c d").toString(), "{a=true, b=false, c d=true}");
        eq("a key that is not a key is not saved", Layout.fold("-a", "Bad Key", false, true), "-a");
        // fold then open is standard
        String f = Layout.fold(Layout.fold(null, "scores", false, true), "scores", true, true);
        check("fold then open is standard (" + f + ")", Layout.standard(null, f));
        String g = Layout.fold(Layout.fold(null, "further analysis", true, false), "further analysis", false, false);
        check("a tile folded by default: open then fold is standard (" + g + ")", Layout.standard(null, g));
        // an order moved back to the standard saves nothing; one with a tile from another day keeps that tile's place
        List<String> page = l("a", "b", "c");
        eq("moved down and back up: no order saved", Layout.orderToSave(null, l("a", "b", "c"), page), null);
        eq("back to the standard after an earlier move: no order saved", Layout.orderToSave(l("c", "a", "b"), l("a", "b", "c"), page), null);
        eq("a moved order is saved", Layout.orderToSave(null, l("b", "a", "c"), page), "b|a|c");
        eq("a tile not on the page keeps its saved place", Layout.orderToSave(l("workouts", "a", "b", "c"), l("a", "b", "c"), page), "workouts|a|b|c");
        check("fold then open, move and move back: Settings counts no tab", Layout.standard(Layout.orderToSave(null, page, page), f));
        // the folded line of a score dial (review of #415, with #411's big line)
        // owner's wish of 21 November: the folded score card shows a small dial with the number, the line keeps the rest
        eq("next to the dial: the word and the big line", Layout.dialSummary(96, "7h 28m asleep", "23:10 to 07:05, 7h 55m in bed"), "Optimal, 7h 28m asleep");
        eq("next to the dial without a big line: the word alone", Layout.dialSummary(72, null, "Lowest heart rate 52"), "Good");
        eq("a watch night without a score: the big line first", Layout.dialSummary(Double.NaN, "6h 2m asleep", "23:40 to 06:30, 6h 50m in bed"), "6h 2m asleep");
        eq("no score, no big line: the line under it", Layout.dialSummary(Double.NaN, null, "No main sleep recorded for this day"), "No main sleep recorded for this day");
        // the folded tile's small dials: only the scores that are there, in order, with their colours and names
        String[] four = {"Readiness", "Sleep", "Activity", "Screen"};
        int[] colours = {1, 2, 3, 4};
        double nan = Double.NaN;
        check("all scores missing: no dials", Layout.dials(new double[] {nan, nan, nan, nan}, colours, four) == null);
        eq("all scores missing: no line", Layout.scoresLine(new double[] {nan, nan, nan, nan}, four), null);
        Layout.Dials some = Layout.dials(new double[] {95, nan, 88, nan}, colours, four);
        check("some missing: only the ones there", some != null && some.values.length == 2 && some.values[0] == 95 && some.values[1] == 88);
        check("some missing: names and colours follow their scores", some != null && "Activity".equals(some.names[1]) && some.colors[1] == 3 && some.colors[0] == 1);
        eq("some missing: TalkBack says each with its name", some == null ? null : some.spoken(), "Readiness 95, activity 88");
        Layout.Dials all = Layout.dials(new double[] {95, 96, 89.5, 87.2}, colours, four);
        check("89.5 shows as 90, as on the big dial", all != null && all.shown(2) == 90 && all.shown(3) == 87);
        eq("all four: TalkBack says them all", all == null ? null : all.spoken(), "Readiness 95, sleep 96, activity 90, screen 87");
        eq("Today's folded line without dials is the same text", Layout.scoresLine(new double[] {95, 96, 89.5, 87.2}, four), "Readiness 95, sleep 96, activity 90, screen 87");
        eq("a missing first score: the next one starts the line", Layout.scoresLine(new double[] {nan, 96, nan, nan}, four), "Sleep 96");
        Layout.Dials one = Layout.dials(new double[] {89.5}, new int[] {7}, new String[] {"Readiness"});
        check("one score: one dial", one != null && one.values.length == 1 && one.colors[0] == 7);
        eq("one score: TalkBack says the number after the title", one == null ? null : one.spoken(), "90");
        eq("a folded score card is read with its number", Layout.parts("Readiness score", one == null ? null : one.spoken(), Layout.dialSummary(89.5, null, "sub")),
                "Readiness score, 90, Optimal");
        // a folded line leaves out values that are missing (review of #415: no "Steps --")
        eq("summary parts: both", Layout.parts("Steps 7432", "active burn 412 kcal"), "Steps 7432, active burn 412 kcal");
        eq("summary parts: the first missing", Layout.parts(null, "active burn 412 kcal"), "Active burn 412 kcal");
        eq("summary parts: none, no line", Layout.parts(null, null), "");
        // a note without a caption: its first words up to the first number, so a count or date in it does not move its place
        eq("note key without numbers", Layout.noteKey("Your baselines are still forming."), "note your baselines are still forming");
        eq("note key stops at a number", Layout.noteKey("Only 3 days with the ring in the last 7 days."), "note only");
        eq("note key: the same on another day", Layout.noteKey("Only 5 days with the ring in the last 7 days."), Layout.noteKey("Only 3 days with the ring"));
        eq("note key: a number first", Layout.noteKey("12 November: synced"), "note");
        eq("note key: no text", Layout.noteKey(null), "note");
        eq("written back the same", Layout.joinFolds(Layout.folds("+a|-b")), "+a|-b");
        eq("nothing saved writes nothing", Layout.joinFolds(Layout.folds(null)), "");
    }

    static void target() {
        // four tiles of 100 px with 10 px between them
        int[] tops = {0, 110, 220, 330}, h = {100, 100, 100, 100};
        eq("not moved", Layout.target(tops, h, 1, 0), 1);
        eq("a little down: still its place", Layout.target(tops, h, 1, 40), 1);
        eq("past the middle of the next", Layout.target(tops, h, 1, 120), 2);
        eq("to the end", Layout.target(tops, h, 0, 500), 3);
        eq("up past the one above", Layout.target(tops, h, 2, -120), 1);
        eq("to the top", Layout.target(tops, h, 3, -500), 0);
        eq("outside the page", Layout.target(tops, h, 9, 10), 9);
        // a tall tile below a short one
        int[] tops2 = {0, 60, 470}, h2 = {50, 400, 100};
        eq("a short tile passes a tall one only past its middle", Layout.target(tops2, h2, 0, 200), 0);
        eq("and then it does", Layout.target(tops2, h2, 0, 260), 1);
    }

    /** Many random pages and saved orders: every tile shows exactly once, and an order saved from the page reads back the same. */
    static void randomized() {
        Random r = new Random(18);
        boolean once = true, back = true, kept = true;
        for (int round = 0; round < 2000; round++) {
            List<String> all = new ArrayList<String>();
            for (int i = 0; i < 12; i++) all.add("t" + i);
            List<String> page = new ArrayList<String>();
            for (String k : all) if (r.nextInt(4) > 0) page.add(k);
            List<String> saved = new ArrayList<String>(all);
            java.util.Collections.shuffle(saved, r);
            saved = saved.subList(0, r.nextInt(saved.size() + 1));
            List<String> shown = Layout.order(saved, page);
            once &= shown.size() == page.size() && new HashSet<String>(shown).equals(new HashSet<String>(page));
            if (page.size() > 1) {
                List<String> moved = Layout.move(shown, r.nextInt(shown.size()), r.nextInt(shown.size()));
                List<String> next = Layout.remember(saved, moved);
                back &= Layout.order(Layout.parse(Layout.join(next)), page).equals(moved);
                // every key saved before is still saved
                kept &= next.containsAll(saved);
            }
        }
        check("random pages: every tile exactly once", once);
        check("random pages: a moved order reads back as shown", back);
        check("random pages: no saved key is lost by a move", kept);
    }

    static void texts() {
        String[] t = app.openring.ui.Explain.layoutTexts();
        for (String s : t) {
            check("no dash as punctuation: " + s, !s.contains(" - ") && !s.contains("–") && !s.contains("—"));
            check("not empty", s.length() > 10);
        }
        check("the edit hint names Save", app.openring.ui.Explain.EDIT_HINT.contains("Save"));
        eq("the owner's question before Reset layout", app.openring.ui.Explain.LAYOUT_RESET_ALL, "Put every tab's tiles back in their standard order and open state?");
        eq("status: standard", SettingsBlocks.layoutStatus(0), "Standard on every tab");
        eq("status: one tab", SettingsBlocks.layoutStatus(1), "Changed on 1 tab");
        eq("status: tabs", SettingsBlocks.layoutStatus(3), "Changed on 3 tabs");
    }

    /** The edit session (owner's wish of 20 November): changes stay in the draft until Save, Reset is standard, Leave keeps all. */
    static void draft() {
        app.openring.core.LayoutDraft d = new app.openring.core.LayoutDraft();
        for (String t : Layout.TABS) d.load(t, null, null);
        d.load("today", "day|scores", "-scores");
        d.load("sleep", "", null);
        check("draft: starts as saved, nothing changed", !d.changed());
        eq("draft: the saved order", d.order("today"), "day|scores");
        eq("draft: an empty saved value is the standard", d.order("sleep"), null);
        check("draft: not standard with a saved order", !d.standard());
        d.setOrder("sleep", "score|day");
        check("draft: a move is a change", d.changed());
        d.setOrder("sleep", "");
        check("draft: moved back, no change", !d.changed());
        d.setFolds("today", Layout.fold(d.folds("today"), "scores", true, true));
        check("draft: a fold is a change", d.changed());
        d.setFolds("today", Layout.fold(d.folds("today"), "scores", false, true));
        check("draft: folded back as saved, no change", !d.changed());
        app.openring.core.LayoutDraft two = new app.openring.core.LayoutDraft();
        for (String t : Layout.TABS) two.load(t, null, null);
        two.load("today", null, "-scores|-day");
        two.setFolds("today", Layout.fold(Layout.fold(two.folds("today"), "scores", true, true), "scores", false, true));
        check("draft: open and fold again, no change (the order of the entries does not count): " + two.folds("today"), !two.changed());
        app.openring.core.LayoutDraft s = new app.openring.core.LayoutDraft();
        for (String t : Layout.TABS) s.load(t, null, null);
        s.setFolds("sleep", Layout.fold(Layout.fold(s.folds("sleep"), "score", false, true), "score", true, true));
        s.setOrder("sleep", Layout.orderToSave(Layout.parse(s.order("sleep")), l("score", "contributors"), l("score", "contributors")));
        check("draft: fold then open, move and move back is standard and no change", s.standard() && !s.changed());
        d.reset();
        check("draft: reset is the standard everywhere", d.standard() && d.order("today") == null && d.folds("today") == null);
        check("draft: reset of a changed layout is a change to save", d.changed());
        app.openring.core.LayoutDraft e = new app.openring.core.LayoutDraft();
        for (String t : Layout.TABS) e.load(t, null, null);
        e.reset();
        check("draft: reset of a standard layout changes nothing", !e.changed() && e.standard());
    }

    static String src(String f) throws Exception {
        return new String(Files.readAllBytes(Paths.get("app/src/main/java/app/openring/" + f)), "UTF-8");
    }

    /** The screens use it: every tab is arranged, the tile header is the shared fold, demo keeps its own, the backup holds it. */
    static void sources() throws Exception {
        String main = src("ui/MainActivity.java"), tiles = src("ui/Tiles.java"), prefs = src("data/Prefs.java"), backup = src("data/Backup.java");
        String settings = src("ui/SettingsActivity.java"), pages = src("ui/Pages.java");
        check("every tab is arranged after it is built", main.contains("Tiles.arrange(this, scroll, tab, (LinearLayout) content"));
        check("a page is not replaced under a held tile", main.contains("Tiles.waitForDrop("));
        check("the tile header is the shared fold", tiles.contains("Fold.inline(c, headContent,"));
        check("drags start only from the handle in the edit session", tiles.contains("handle.setOnTouchListener") && !tiles.contains("setOnLongClickListener"));
        check("the handle opens the menu for TalkBack", tiles.contains("menu(t); // TalkBack"));
        check("moves and folds go to the draft while editing", tiles.contains("if (draft != null) draft.setOrder(tab, order);") && tiles.contains("setOrderOf(c, tabName,"));
        check("Settings opens the edit session", settings.contains("\"Rearrange tiles\"") && settings.contains("putExtra(\"edit_layout\", true)"));
        check("the edit header has Save and Reset", main.contains("\"Save\"") && main.contains("Tiles.resetDraft()") && main.contains("Tiles.save(MainActivity.this)"));
        check("Back with unsaved changes asks first", main.contains("Tiles.draftChanged()") && main.contains("Explain.EDIT_LEAVE"));
        check("the menu moves up and down", tiles.contains("\"Move up\"") && tiles.contains("\"Move down\""));
        check("demo mode keeps its own layout", prefs.contains("(demo ? \"_demo\" : \"\")") && prefs.contains("tileKey(\"order\", tab, demo())"));
        check("the backup holds the person's layout, never the demo's", backup.contains("p.tileOrderReal(tab)") && backup.contains("p.setTilesReal(tab, order, folds)"));
        check("an older backup without a layout keeps the phone's", backup.contains("if (layout != null)"));
        check("Settings has Reset layout with a question", settings.contains("\"Reset layout\"") && settings.contains("Tiles.confirmReset(SettingsActivity.this, new Runnable()"));
        check("Settings' Layout section is a fold", settings.contains("fold(app.openring.core.SettingsBlocks.LAYOUT,"));
        check("the screen test can start from the standard layout", main.contains("\"layout_reset\"") && main.contains("resetTiles(null)"));
        check("Further analysis folds as a tile", pages.contains("s.standardOpen = false;") && pages.contains("Tiles.open(c, SLEEP, ANALYSIS_KEY, false)"));
        check("a score dial is a tile", pages.contains("Tiles.name(card, \"score\", label + \" score\""));
        check("a folded dial says its big line (#411's time asleep)", pages.contains("Layout.dialSummary(score, big, sub)"));
        check("a folded Goal progress leaves out missing values", pages.contains("Tiles.summary(goal, app.openring.core.Layout.parts("));
        check("Activity's note with a count of days has its own name", pages.contains("Tiles.name(note, ACTIVITY_DAYS_KEY, null, null)"));
        check("a tile in its standard state saves nothing", tiles.contains("Layout.fold(foldsOf(c, tabName), t.key, open, t.standardOpen)")
                && tiles.contains("Layout.orderToSave(Layout.parse(orderOf(c, tabName)), keys, standard)"));
        check("a note's key leaves out numbers", tiles.contains("k = Layout.noteKey(firstText)"));
        check("a tile takes its card's spec, so the spec no longer keeps the page", tiles.contains("Spec s = SPECS.take(card);")
                && !tiles.contains("SPECS.get(card);\n            t.spec") && tiles.contains("new CardSpecs<View, Spec>()"));
        check("a page that is not arranged leaves no spec behind", tiles.contains("SPECS.take(root.getChildAt(i));"));
        check("Further analysis' listener holds its body (the reason a spec must be taken)",
                pages.contains("s.listener = new Tiles.Listener()") && pages.contains("fillAnalysis(c, d, body);"));
        String screens = new String(Files.readAllBytes(Paths.get(".github/scripts/screens.sh")), "UTF-8");
        check("screens.sh starts its steps in the standard layout", screens.contains("--ez layout_reset true"));
    }

    /** Collects garbage until {@code gone} is cleared or 50 tries passed; true when it was cleared. */
    static boolean collected(WeakReference<?> gone) throws InterruptedException {
        for (int i = 0; i < 50 && gone.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        return gone.get() == null;
    }

    /**
     * What a page says about its cards until the tiles take it (core/CardSpecs, ui/Tiles): the value is read once, then gone;
     * a value that reaches its own card (Further analysis' listener and body) kept the card and the page for good while it was
     * only read, and lets it go once taken; a card that is never arranged still goes by itself.
     */
    static void specs() throws Exception {
        CardSpecs<Object, Object[]> m = new CardSpecs<Object, Object[]>();
        Object a = new Object();
        Object[] spec = {"further analysis"};
        check("specs: nothing said about a card", m.get(a) == null && m.take(a) == null);
        m.put(a, spec);
        check("specs: read while the page is built", m.get(a) == spec && m.get(a) == spec && m.size() == 1);
        check("specs: the tile takes it", m.take(a) == spec);
        check("specs: taken once, then gone", m.take(a) == null && m.get(a) == null && m.size() == 0);

        // a value that reaches its own card, as Further analysis' listener does through its body
        Object card = new Object();
        WeakReference<Object> ref = new WeakReference<Object>(card);
        m.put(card, new Object[] {card});
        card = null;
        check("specs: only read, a value that reaches its card keeps it (the leak before)", !collected(ref) && m.size() == 1);
        m.take(ref.get());
        check("specs: taken, the card and its page can go", collected(ref) && m.size() == 0);

        // a card that is never arranged keeps nothing either
        Object lone = new Object();
        WeakReference<Object> loneRef = new WeakReference<Object>(lone);
        m.put(lone, new Object[] {"note"});
        lone = null;
        check("specs: a card never arranged goes by itself", collected(loneRef) && m.size() == 0);
    }
}
