import app.openring.core.Grid;
import app.openring.core.SettingsBlocks;
import app.openring.core.Start;
import app.openring.core.TodayCards;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Today's first screen and the folds (owner's wishes of 17 November): the order of Today's cards for a ring, a watch and only
 * the phone (Your day right after the scores, nothing left out), the sizes of the equal badge grid, the one line status of each
 * Settings section, and that the screens use the one shared fold. Run with tests/run_tests.sh.
 */
public class TodayTest {
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
        order();
        grid();
        statuses();
        sources();
        System.out.println("today: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final Start.Has RING = new Start.Has(true, false, false), BOTH = new Start.Has(true, true, false),
            WATCH = new Start.Has(false, true, false), PHONE = new Start.Has(false, false, false), HALF = new Start.Has(false, false, true);

    static void order() {
        eq("ring, everything: Your day right after the scores, badges after it", TodayCards.of(RING, true, true, true).toString(),
                "[scores, day, left_out, badges, ring_metrics, live, period]");
        eq("ring, nothing extra", TodayCards.of(RING, false, false, false).toString(), "[scores, day, ring_metrics, live]");
        eq("ring and watch: as with the ring", TodayCards.of(BOTH, true, false, false), TodayCards.of(RING, true, false, false));
        eq("only a watch: the note on its scores under Your day, then badges and the watch's day",
                TodayCards.of(WATCH, true, false, true).toString(), "[scores, day, device_note, badges, watch_day, period]");
        eq("only the phone", TodayCards.of(PHONE, true, false, false).toString(), "[scores, day, device_note, badges, phone_day]");
        eq("watch chosen, not added: finish it after the note", TodayCards.of(HALF, false, false, false).toString(),
                "[scores, day, device_note, finish_watch, phone_day]");
        // the owner's rule: the sentence is on the first screen whatever the devices and the badges
        for (Start.Has h : new Start.Has[] {RING, BOTH, WATCH, PHONE, HALF}) {
            for (int bits = 0; bits < 8; bits++) {
                List<String> l = TodayCards.of(h, (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
                check("Your day is second, " + l, TodayCards.dayIndex(l) == 1 && l.get(0).equals(TodayCards.SCORES));
                check("badges never above Your day, " + l, !l.contains(TodayCards.BADGES) || l.indexOf(TodayCards.BADGES) > TodayCards.dayIndex(l));
                check("each card once, " + l, new java.util.HashSet<String>(l).size() == l.size());
            }
        }
        // nothing that was on Today is gone: every device card of Start is still there (Find my ring sits in the scores card)
        for (Start.Has h : new Start.Has[] {RING, WATCH, PHONE, HALF}) {
            List<String> l = TodayCards.of(h, false, false, false);
            for (String k : Start.cards(Start.TODAY, h)) {
                boolean inScores = k.equals(Start.SCORES) || k.equals(Start.DEVICE_SCORES) || k.equals(Start.FIND);
                check("Start's card " + k + " is still on Today", inScores || l.contains(k));
            }
        }
    }

    static void grid() {
        eq("cell width: the gaps first, the rest shared", Grid.cellWidth(1000, 2, 20), 490);
        eq("cell width: one column takes everything", Grid.cellWidth(1000, 1, 20), 1000);
        eq("cell width never below 0", Grid.cellWidth(10, 2, 40), 0);
        eq("cell width: no columns counts as one", Grid.cellWidth(300, 0, 8), 300);
        eq("rows: 7 badges in 2 columns", Grid.rows(7, 2), 4);
        eq("rows: none", Grid.rows(0, 2), 0);
        eq("rows: exactly full", Grid.rows(6, 3), 2);
        eq("every cell as tall as the tallest (a long name)", Grid.cellHeight(new int[] {110, 152, 110, 96}), 152);
        eq("no cells, no height", Grid.cellHeight(new int[0]), 0);
        eq("grid height: rows of the cell height with gaps between", Grid.height(5, 2, 100, 10), 3 * 100 + 2 * 10);
        eq("grid height: empty", Grid.height(0, 2, 100, 10), 0);
        eq("left: second column", Grid.left(3, 2, 490, 20), 510);
        eq("left: an odd last cell stays in the first column", Grid.left(6, 2, 490, 20), 0);
        eq("top: fourth row", Grid.top(6, 2, 100, 10), 330);
        // equal sizes: every cell ends where the next begins, the last one at the grid's width
        int w = 1003, gap = 21, cols = 2, cw = Grid.cellWidth(w, cols, gap);
        check("cells fit the width", Grid.left(cols - 1, cols, cw, gap) + cw <= w);
    }

    static void statuses() {
        eq("updates", SettingsBlocks.updatesStatus("2.0.19"), "Open Ring 2.0.19 installed");
        eq("updates without a version", SettingsBlocks.updatesStatus(""), "Open Ring");
        eq("about", SettingsBlocks.aboutStatus("2.0.19"), "Open Ring 2.0.19, not a medical device");
        eq("profile, female", SettingsBlocks.profileStatus(34, 1, 62, 168), "34 years, female, 62 kg, 168 cm");
        eq("profile, male", SettingsBlocks.profileStatus(51, 0, 80, 180), "51 years, male, 80 kg, 180 cm");
        eq("profile, not said", SettingsBlocks.profileStatus(30, 2, 70, 175), "30 years, 70 kg, 175 cm");
        eq("period reminder", SettingsBlocks.periodStatus(2), "Reminder 2 days before");
        eq("period reminder one day", SettingsBlocks.periodStatus(1), "Reminder 1 day before");
        eq("period no reminder", SettingsBlocks.periodStatus(0), "No reminder");
        eq("screen allowed with a goal", SettingsBlocks.screenStatus(false, true, true, 180), "Allowed, daily goal 3h");
        eq("screen goal with minutes", SettingsBlocks.screenStatus(false, true, true, 150), "Allowed, daily goal 2h 30m");
        eq("screen short goal", SettingsBlocks.screenStatus(false, true, true, 45), "Allowed, daily goal 45 min");
        eq("screen no goal", SettingsBlocks.screenStatus(false, true, true, 0), "Allowed, no goal");
        eq("screen not allowed", SettingsBlocks.screenStatus(false, true, false, 180), "Usage access not allowed yet");
        eq("screen old Android", SettingsBlocks.screenStatus(false, false, false, 180), "Needs Android 9 or newer");
        eq("screen demo", SettingsBlocks.screenStatus(true, false, false, 180), "Demo data, daily goal 3h");
        eq("backups off", SettingsBlocks.backupsStatus(false, false, 7, null), "Off, a copy only before updates");
        eq("backups weekly", SettingsBlocks.backupsStatus(false, true, 7, "Backups"), "Every week, to Backups");
        eq("backups daily, no folder name", SettingsBlocks.backupsStatus(false, true, 1, ""), "Every day");
        eq("backups demo", SettingsBlocks.backupsStatus(true, false, 7, null), "Demo, an example");
        // plain texts: no dash as punctuation, short enough for one line on a phone
        String[] all = {SettingsBlocks.GAMES_STATUS, SettingsBlocks.GAMES_NO_RING, SettingsBlocks.FILE_STATUS, SettingsBlocks.EXPORT_STATUS,
            SettingsBlocks.HELP_STATUS, SettingsBlocks.ADVANCED_STATUS, SettingsBlocks.CAREFUL_STATUS, SettingsBlocks.profileStatus(34, 1, 62, 168),
            SettingsBlocks.screenStatus(false, true, true, 150), SettingsBlocks.backupsStatus(false, true, 30, "Backups"),
            app.openring.core.DeviceScores.TODAY_WATCH_SHORT, app.openring.core.DeviceScores.TODAY_PHONE_SHORT};
        for (String t : all) {
            check("no dash as punctuation: " + t, !t.contains(" - ") && !t.contains("–") && !t.contains("—"));
            check("one short line: " + t, t.length() <= 50);
        }
    }

    static String src(String f) throws Exception {
        return new String(Files.readAllBytes(Paths.get("app/src/main/java/app/openring/" + f)), "UTF-8");
    }

    /** The screens use the one shared fold (read from the sources, which the JVM cannot run). */
    static void sources() throws Exception {
        String ui = src("ui/Ui.java"), settings = src("ui/SettingsActivity.java"), battery = src("ui/BatteryViews.java"), pages = src("ui/Pages.java");
        String devices = src("ui/DeviceViews.java"), badges = src("ui/BadgeViews.java"), badgesPage = src("ui/BadgesActivity.java");
        check("How it works is the shared fold", ui.contains("Fold f = Fold.inline(c, title, null, open, true);"));
        check("Why this line is the shared fold", pages.contains("Ui.howFold(c, dayWhyOpen, why, \"Why this line\")"));
        check("the battery lines are the shared fold, the explanation inside", battery.contains("Fold.inline(c, lines, said.toString(), open, true)")
                && battery.contains("f.note(Explain.BATTERY_TODAY);"));
        check("the note on scores without a ring is the shared fold", devices.contains("Fold.inline(c, d.has.watch ? app.openring.core.DeviceScores.TODAY_WATCH_SHORT"));
        check("Add a ring under Your day is a small text button", devices.contains("Ui.textButton(c, app.openring.core.KeyState.ADD_RING, Ui.SLEEP)"));
        check("the Settings blocks are folds", settings.contains("Fold block(final int which)") && !settings.contains("static final class Block"));
        for (String t : new String[] {"UPDATES", "PROFILE", "PERIOD", "SCREEN", "GAMES", "FILE", "BACKUPS", "EXPORT", "HELP", "ADVANCED", "CAREFUL", "ABOUT"}) {
            check("Settings section " + t + " is a fold", settings.contains("fold(app.openring.core.SettingsBlocks." + t + ","));
        }
        for (String t : new String[] {"\"Updates\"", "\"Profile and goals\"", "\"Period tracker\"", "\"Screen time\"", "\"Backup file\"", "\"Export\"",
            "\"Help and diagnostics\"", "\"Advanced\"", "\"About\"", "Backups.TITLE"}) {
            check("no section title left as a caption inside its fold: " + t, !settings.contains("Ui.caption(this, " + t + ")"));
        }
        check("the badge chips on Today sit in the equal grid", badges.contains("EqualGrid grid = new EqualGrid(c, 2, 8);"));
        check("the badges on the Badges page sit in the equal grid, as the same chip", badgesPage.contains("EqualGrid grid = new EqualGrid(this, 2, 8);")
                && badgesPage.contains("BadgeViews.chip(this, k, levels[k], counts[k], false)"));
        check("a badge name takes two lines at most", badges.contains("name.setMaxLines(2);"));
    }
}
