import app.openring.core.LifeTags;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Life factors (issue #292, phase 1, {@code core/LifeTags}): the fixed keys and names, the default chips (Intimacy not one),
 * own tags and their names, the stored choices read back as the chips, several days turned into single days across months, a
 * leap day and a clock change, every sentence plain and without dashes, the CSV columns of the export choice, and the demo's
 * made up tags (never Intimacy). Made up days only. Run with tests/run_tests.sh.
 */
public class LifeTest {
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

    static final Locale EN = Locale.UK;
    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    public static void main(String[] args) {
        keys();
        names();
        known();
        spans();
        texts();
        csv();
        demo();
        System.out.println("life: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void keys() {
        eq("nine fixed tags, in the order of the plan", LifeTags.BUILT_IN, Arrays.asList("meditation", "alcohol", "caffeine_late", "stress",
                "hard_workout", "intimacy", "holiday", "illness", "travel"));
        eq("the default chips: Meditation, Alcohol, Caffeine late, Stress, Hard workout", LifeTags.DEFAULT_CHIPS,
                Arrays.asList(LifeTags.MEDITATION, LifeTags.ALCOHOL, LifeTags.CAFFEINE, LifeTags.STRESS, LifeTags.WORKOUT));
        check("Intimacy is offered but not a default chip", LifeTags.isBuiltIn(LifeTags.INTIMACY) && !LifeTags.DEFAULT_CHIPS.contains(LifeTags.INTIMACY));
        eq("names", LifeTags.builtInName(LifeTags.CAFFEINE) + "|" + LifeTags.builtInName(LifeTags.WORKOUT) + "|" + LifeTags.builtInName("x"),
                "Caffeine late|Hard workout|null");
        check("an own key is own: followed by a number", LifeTags.isOwn("own:1731850000000") && LifeTags.valid("own:1"));
        for (String bad : new String[] {null, "", "own:", "own:abc", "own:-1", "own:1 ", "Meditation", "own:12345678901234567890", "x"}) {
            check("not a tag key: " + bad, !LifeTags.valid(bad));
        }
        eq("own key of a time", LifeTags.ownKey(1731850000000L), "own:1731850000000");
        eq("own key of a negative time is 0", LifeTags.ownKey(-5), "own:0");
    }

    static void names() {
        eq("a name is trimmed, inner spaces made one", LifeTags.cleanName("  late   dinner "), "late dinner");
        eq("line breaks and tabs become spaces", LifeTags.cleanName("late\ndinner\t"), "late dinner");
        eq("a name in another script stays", LifeTags.cleanName("Sauna \u00e0 deux"), "Sauna \u00e0 deux");
        check("empty is no name", LifeTags.cleanName("   ") == null && LifeTags.cleanName(null) == null);
        String max = "abcdefghijabcdefghijabcdefghij";
        eq("30 characters is a name", LifeTags.cleanName(max), max);
        check("31 characters is too long", LifeTags.cleanName(max + "k") == null);
        check("a fixed tag's name, any case, is not a new own one", LifeTags.cleanName("alcohol") == null && LifeTags.cleanName(" HOLIDAY ") == null);
    }

    static void known() {
        LifeTags.Known none = new LifeTags.Known(null);
        eq("without choices: the default chips in their order", keys(none.chips()), LifeTags.DEFAULT_CHIPS);
        eq("All factors lists the nine fixed ones", keys(none.loggable()), LifeTags.BUILT_IN);
        List<LifeTags.Choice> ch = new ArrayList<LifeTags.Choice>();
        ch.add(new LifeTags.Choice("intimacy", null, 1, null, false));
        ch.add(new LifeTags.Choice("stress", null, 0, null, false));
        ch.add(new LifeTags.Choice("own:2000", "Sauna", null, null, false));
        ch.add(new LifeTags.Choice("own:1000", "Late dinner", 1, null, false));
        ch.add(new LifeTags.Choice("own:3000", "Old one", 1, null, true));
        ch.add(new LifeTags.Choice("bogus", "x", 1, null, false));
        ch.add(new LifeTags.Choice("own:4000", null, 1, null, false));
        LifeTags.Known k = new LifeTags.Known(ch);
        eq("chips: Intimacy added by the person, Stress taken off, own tags on by default, a removed one never",
                keys(k.chips()), Arrays.asList("meditation", "alcohol", "caffeine_late", "hard_workout", "intimacy", "own:1000", "own:2000"));
        eq("own tags in the order they were made, removed ones kept for their days", new ArrayList<String>(k.all.keySet()).subList(9, 12),
                Arrays.asList("own:1000", "own:2000", "own:3000"));
        eq("a removed own tag cannot be logged, nor removed again", keys(k.own()), Arrays.asList("own:1000", "own:2000"));
        check("a removed own tag is not in All factors", !keys(k.loggable()).contains("own:3000") && k.loggable().size() == 11);
        eq("names in the list order", k.names(Arrays.asList("own:2000", "alcohol", "own:9")), Arrays.asList("Alcohol", "Sauna", "Own factor"));
        eq("the name of an own tag nobody knows", k.name("own:9"), "Own factor");
        // the stored order wins
        List<LifeTags.Choice> ord = new ArrayList<LifeTags.Choice>();
        ord.add(new LifeTags.Choice("travel", null, 1, 0, false));
        ord.add(new LifeTags.Choice("meditation", null, 1, 2, false));
        ord.add(new LifeTags.Choice("alcohol", null, 1, 1, false));
        ord.add(new LifeTags.Choice("caffeine_late", null, 0, null, false));
        ord.add(new LifeTags.Choice("stress", null, 0, null, false));
        ord.add(new LifeTags.Choice("hard_workout", null, 0, null, false));
        eq("chips in the order the person chose", keys(new LifeTags.Known(ord).chips()), Arrays.asList("travel", "alcohol", "meditation"));
        List<LifeTags.Choice> off = new ArrayList<LifeTags.Choice>();
        for (String key : LifeTags.BUILT_IN) off.add(new LifeTags.Choice(key, null, 0, null, false));
        check("no chip at all is a choice too", new LifeTags.Known(off).chips().isEmpty());
    }

    static List<String> keys(List<LifeTags.Tag> tags) {
        List<String> out = new ArrayList<String>();
        for (LifeTags.Tag t : tags) out.add(t.key);
        return out;
    }

    static void spans() {
        List<LocalDate> m = LifeTags.span(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2));
        eq("a span across a month end: every date once", m.toString(), "[2026-10-30, 2026-10-31, 2026-11-01, 2026-11-02]");
        eq("across a year end", LifeTags.span(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)).size(), 2);
        eq("a leap day", LifeTags.span(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1)).toString(), "[2028-02-28, 2028-02-29, 2028-03-01]");
        // the clocks go back in Zurich on 25 Oct 2026 (a 25 hour day): still one date each, nothing doubled or skipped
        ZoneId z = ZoneId.of("Europe/Zurich");
        List<LocalDate> dst = LifeTags.span(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26));
        check("the day the clocks change has 25 hours", z.getRules().isDaylightSavings(LocalDate.of(2026, 10, 24).atStartOfDay(z).toInstant())
                && !z.getRules().isDaylightSavings(LocalDate.of(2026, 10, 26).atStartOfDay(z).toInstant()));
        eq("a span over the clock change: three dates", dst.toString(), "[2026-10-24, 2026-10-25, 2026-10-26]");
        List<LocalDate> spring = LifeTags.span(LocalDate.of(2027, 3, 27), LocalDate.of(2027, 3, 29));
        eq("and over the spring change (a 23 hour day)", spring.toString(), "[2027-03-27, 2027-03-28, 2027-03-29]");
        eq("one day", LifeTags.span(TODAY, TODAY).size(), 1);
        check("an end before the start is nothing", LifeTags.span(TODAY, TODAY.minusDays(1)).isEmpty() && LifeTags.span(null, TODAY).isEmpty());
        eq("a whole year", LifeTags.span(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)).size(), 365);
    }

    static void texts() {
        int y = TODAY.getYear();
        eq("several days", LifeTags.spanLine("Holiday", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10), y, EN), "Holiday from 3 Nov to 10 Nov");
        eq("several days, one day", LifeTags.spanLine("Illness", TODAY, TODAY, y, EN), "Illness on 17 Nov");
        eq("several days of another year", LifeTags.spanLine("Holiday", LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2), y, EN),
                "Holiday from 30 Dec 2025 to 2 Jan");
        eq("after a span", LifeTags.spanDone("Holiday", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10), y, EN), "Holiday logged for 8 days, 3 Nov to 10 Nov.");
        eq("after a one day span", LifeTags.spanDone("Travel", TODAY, TODAY, y, EN), "Travel logged for 1 day, 17 Nov to 17 Nov.");
        eq("after a tap on", LifeTags.tapLine("Meditation", true, LocalDate.of(2026, 11, 8), y, EN), "Meditation added to 8 Nov.");
        eq("after a tap off", LifeTags.tapLine("Meditation", false, LocalDate.of(2026, 11, 8), y, EN), "Meditation taken away from 8 Nov.");
        eq("a past day", LifeTags.logFor(LocalDate.of(2026, 10, 27), y, EN), "Log for 27 Oct");
        eq("beside the night", LifeTags.beforeNight(Arrays.asList("Alcohol", "Meditation")), "Before this night: Alcohol, Meditation.");
        check("beside the night: nothing without tags", LifeTags.beforeNight(new ArrayList<String>()) == null && LifeTags.beforeNight(null) == null);
        eq("the morning: yesterday", LifeTags.morning(Arrays.asList("Alcohol"), TODAY, TODAY, EN), "Logged yesterday: Alcohol.");
        eq("the morning of a past day", LifeTags.morning(Arrays.asList("Alcohol", "Stress"), LocalDate.of(2026, 10, 27), TODAY, EN),
                "Logged on 26 Oct: Alcohol, Stress.");
        check("the morning: nothing without tags", LifeTags.morning(new ArrayList<String>(), TODAY, TODAY, EN) == null);
        eq("a day read on the month view", LifeTags.dayLine(LocalDate.of(2026, 11, 3), Arrays.asList("Holiday", "Meditation"), TODAY, EN),
                "Tue 3 Nov: Holiday, Meditation.");
        eq("a day without tags", LifeTags.dayLine(LocalDate.of(2026, 11, 4), null, TODAY, EN), "Wed 4 Nov: nothing logged.");
        eq("a day to come", LifeTags.dayLine(LocalDate.of(2026, 11, 20), null, TODAY, EN), "Fri 20 Nov: still to come.");
        eq("a day of another year", LifeTags.dayLine(LocalDate.of(2025, 11, 4), null, TODAY, EN), "Tue 4 Nov 2025: nothing logged.");
        Map<LocalDate, List<String>> days = new TreeMap<LocalDate, List<String>>();
        days.put(LocalDate.of(2026, 11, 1), Arrays.asList("alcohol", "meditation"));
        days.put(LocalDate.of(2026, 11, 2), Arrays.asList("meditation"));
        days.put(LocalDate.of(2026, 11, 3), Arrays.asList("meditation", "stress"));
        days.put(LocalDate.of(2026, 10, 31), Arrays.asList("travel"));
        LifeTags.Known k = new LifeTags.Known(null);
        eq("the month's counts, most days first, then the list order", LifeTags.monthLine(days, YearMonth.of(2026, 11), k),
                "This month: Meditation on 3 days, Alcohol on 1 day, Stress on 1 day.");
        eq("a month without tags", LifeTags.monthLine(days, YearMonth.of(2026, 9), k), "Nothing logged this month.");
        List<String> all = new ArrayList<String>(Arrays.asList(LifeTags.TITLE, LifeTags.TITLE_PAST, LifeTags.NOTE, LifeTags.ALL, LifeTags.SEVERAL,
                LifeTags.ADD_OWN, LifeTags.CHOOSE, LifeTags.REMOVE_OWN, LifeTags.OWN_HINT, LifeTags.BODY_TITLE, LifeTags.MONTH_HINT,
                LifeTags.NONE_YET, LifeTags.NO_EFFECT_YET, LifeTags.NOT_SAVED, LifeTags.NOT_UNDONE, LifeTags.UNREADABLE, LifeTags.CSV_CHOICE,
                LifeTags.CSV_INTIMACY, LifeTags.CSV_NOTE, LifeTags.SPAN_FUTURE, LifeTags.SPAN_BACKWARDS, LifeTags.OWN_REFUSED, LifeTags.monthLine(days, YearMonth.of(2026, 11), k)));
        all.addAll(Arrays.asList(app.openring.ui.Explain.lifeTexts()));
        for (String t : all) {
            check("plain, no dash as punctuation: " + t, !t.contains(" - ") && !t.contains("–") && !t.contains("—") && !t.contains("--"));
            check("no cause claimed: " + t, !t.toLowerCase(Locale.ROOT).contains("causes") && !t.toLowerCase(Locale.ROOT).contains("because of"));
        }
        check("the texts never say a factor changes a score", app.openring.ui.Explain.LIFE_NO_EFFECT.startsWith("Nothing here changes a score."));
        for (String t : new String[] {LifeTags.SPAN_FUTURE, LifeTags.SPAN_BACKWARDS, LifeTags.OWN_REFUSED, LifeTags.NOT_SAVED, LifeTags.NOT_UNDONE}) {
            check("a toast fits in two lines: " + t, app.openring.core.Texts.fitsToast(t));
        }
        eq("the card's question", LifeTags.TITLE, "What was today like?");
        eq("the card's note", LifeTags.NOTE, "Tap what fits. Stored only on this phone.");
    }

    static void csv() {
        List<LifeTags.Choice> ch = new ArrayList<LifeTags.Choice>();
        ch.add(new LifeTags.Choice("own:1000", "Late dinner!", null, null, false));
        ch.add(new LifeTags.Choice("own:2000", "late-dinner", null, null, false));
        ch.add(new LifeTags.Choice("own:2500", "Late dinner?", null, null, false));
        ch.add(new LifeTags.Choice("own:3000", "Sauna \u00e0 deux", null, null, true));
        LifeTags.Known k = new LifeTags.Known(ch);
        check("without the choice: no column", LifeTags.csvColumns(k, false, true).isEmpty());
        Map<String, String> on = LifeTags.csvColumns(k, true, false);
        eq("with the choice: every fixed tag but Intimacy, then own ones (removed ones too)", new ArrayList<String>(on.values()),
                Arrays.asList("life_meditation", "life_alcohol", "life_caffeine_late", "life_stress", "life_hard_workout", "life_holiday", "life_illness",
                        "life_travel", "life_own_late_dinner_", "life_own_late_dinner", "life_own_late_dinner__2", "life_own_sauna_\u00e0_deux"));
        Map<String, String> both = LifeTags.csvColumns(k, true, true);
        check("Intimacy only with its own tick", both.containsKey(LifeTags.INTIMACY) && !on.containsKey(LifeTags.INTIMACY) && both.size() == on.size() + 1);
        boolean safe = true;
        for (String h : both.values()) safe &= h.matches("[\\p{L}\\p{N}_]+");
        check("every header is letters, digits and _ only (no comma, quote or line break)", safe);
    }

    static void demo() {
        int days = 35, cold = 15;
        TreeMap<LocalDate, List<String>> d = LifeTags.demo(TODAY, days, cold);
        boolean intimacy = false, known = true;
        for (List<String> l : d.values()) {
            intimacy |= l.contains(LifeTags.INTIMACY);
            for (String t : l) known &= LifeTags.isBuiltIn(t);
        }
        check("the demo never has Intimacy", !intimacy);
        check("the demo has fixed tags only", known);
        check("the demo stays inside its five weeks", d.firstKey().isAfter(TODAY.minusDays(days)) && !d.lastKey().isAfter(TODAY));
        eq("today: Meditation", d.get(TODAY), Arrays.asList(LifeTags.MEDITATION));
        check("yesterday: Alcohol and Meditation (the Sleep and Readiness lines)", d.get(TODAY.minusDays(1)).containsAll(Arrays.asList(LifeTags.ALCOHOL, LifeTags.MEDITATION)));
        check("the cold of the story is an illness of two days", d.get(TODAY.minusDays(cold)).contains(LifeTags.ILLNESS)
                && d.get(TODAY.minusDays(cold + 1)).contains(LifeTags.ILLNESS)
                && (!d.containsKey(TODAY.minusDays(cold + 2)) || !d.get(TODAY.minusDays(cold + 2)).contains(LifeTags.ILLNESS)));
        int illness = 0, holiday = 0;
        Set<String> kinds = new HashSet<String>();
        for (List<String> l : d.values()) {
            if (l.contains(LifeTags.ILLNESS)) illness++;
            if (l.contains(LifeTags.HOLIDAY)) holiday++;
            kinds.addAll(l);
        }
        eq("illness on 2 days, holiday on 4", illness + " " + holiday, "2 4");
        eq("every fixed tag but Intimacy shows up", kinds, new HashSet<String>(Arrays.asList(LifeTags.MEDITATION, LifeTags.ALCOHOL, LifeTags.CAFFEINE,
                LifeTags.STRESS, LifeTags.WORKOUT, LifeTags.HOLIDAY, LifeTags.ILLNESS, LifeTags.TRAVEL)));
        int spanDays = 0;
        for (LocalDate[] s : LifeTags.demoSpans(TODAY, cold)) spanDays += LifeTags.span(s[0], s[1]).size();
        eq("the demo's two spans cover the illness and holiday days", spanDays, illness + holiday);
        eq("the same day gives the same tags", LifeTags.demo(TODAY, days, cold), d);
    }
}
