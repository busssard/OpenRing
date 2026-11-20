import app.openring.core.Layout;
import app.openring.core.SettingsBlocks;

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
        String s = Layout.fold(null, "key metrics", false);
        eq("folded is saved", s, "-key metrics");
        check("and stays folded", !Layout.open(Layout.folds(s), "key metrics", true));
        s = Layout.fold(s, "further analysis", true);
        check("a tile folded by default stays open once opened", Layout.open(Layout.folds(s), "further analysis", false));
        s = Layout.fold(s, "key metrics", true);
        check("opened again", Layout.open(Layout.folds(s), "key metrics", true));
        eq("the newest change last, each key once", s, "+further analysis|+key metrics");
        check("open again is kept as open, also if the standard changes later", Layout.open(Layout.folds(s), "key metrics", false));
        eq("a broken value keeps the good entries", Layout.folds("+a|-b|xc|-|+|-B|+c d").toString(), "{a=true, b=false, c d=true}");
        eq("a key that is not a key is not saved", Layout.fold("-a", "Bad Key", false), "-a");
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
        d.setFolds("today", Layout.fold(d.folds("today"), "scores", true));
        check("draft: a fold is a change", d.changed());
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
        String screens = new String(Files.readAllBytes(Paths.get(".github/scripts/screens.sh")), "UTF-8");
        check("screens.sh starts its steps in the standard layout", screens.contains("--ez layout_reset true"));
    }
}
