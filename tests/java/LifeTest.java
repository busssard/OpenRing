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
        sameName();
        restoreText();
        times435();
        chips435();
        bands435();
        during435();
        demoTimes435();
        System.out.println("life: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void keys() {
        eq("eleven fixed tags, in the order of the plan, Sauna and Friends after Hard workout (#435)", LifeTags.BUILT_IN, Arrays.asList("meditation",
                "alcohol", "caffeine_late", "stress", "hard_workout", "sauna", "friends", "intimacy", "holiday", "illness", "travel"));
        eq("the default chips (#435): Meditation, Sauna, Friends, Alcohol, Stress, Hard workout", LifeTags.DEFAULT_CHIPS,
                Arrays.asList(LifeTags.MEDITATION, LifeTags.SAUNA, LifeTags.FRIENDS, LifeTags.ALCOHOL, LifeTags.STRESS, LifeTags.WORKOUT));
        eq("the names of Sauna and Friends", LifeTags.builtInName(LifeTags.SAUNA) + "|" + LifeTags.builtInName(LifeTags.FRIENDS), "Sauna|Friends");
        check("Holiday is not a default chip (it is days, not a moment)", !LifeTags.DEFAULT_CHIPS.contains(LifeTags.HOLIDAY));
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
        eq("All factors lists the eleven fixed ones", keys(none.loggable()), LifeTags.BUILT_IN);
        List<LifeTags.Choice> ch = new ArrayList<LifeTags.Choice>();
        ch.add(new LifeTags.Choice("intimacy", null, 1, null, false));
        ch.add(new LifeTags.Choice("stress", null, 0, null, false));
        ch.add(new LifeTags.Choice("own:2000", "Sauna", null, null, false));
        ch.add(new LifeTags.Choice("own:1000", "Late dinner", 1, null, false));
        ch.add(new LifeTags.Choice("own:3000", "Old one", 1, null, true));
        ch.add(new LifeTags.Choice("bogus", "x", 1, null, false));
        ch.add(new LifeTags.Choice("own:4000", null, 1, null, false));
        LifeTags.Known k = new LifeTags.Known(ch);
        eq("chips after a choice (#435): Intimacy put on, Stress off, a fixed tag the choice did not store stays off, own tags on by default, a removed one never",
                keys(k.chips()), Arrays.asList("intimacy", "own:1000", "own:2000"));
        eq("own tags in the order they were made, removed ones kept for their days", new ArrayList<String>(k.all.keySet()).subList(11, 14),
                Arrays.asList("own:1000", "own:2000", "own:3000"));
        eq("a removed own tag cannot be logged, nor removed again", keys(k.own()), Arrays.asList("own:1000", "own:2000"));
        check("a removed own tag is not in All factors", !keys(k.loggable()).contains("own:3000") && k.loggable().size() == 13);
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
        eq("before several days are logged (#421)", LifeTags.spanDone(5), "One entry on each of the 5 days. To take a day away, open that day and tap its chip.");
        eq("before one day is logged as several (#421)", LifeTags.spanDone(1), "One entry on 1 day. To take a day away, open that day and tap its chip.");
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
                LifeTags.CSV_INTIMACY, LifeTags.CSV_NOTE, LifeTags.SPAN_FUTURE, LifeTags.SPAN_BACKWARDS, LifeTags.OWN_REFUSED, LifeTags.monthLine(days, YearMonth.of(2026, 11), k),
                LifeTags.spanDone(1), LifeTags.spanDone(5), RESTORE_TEXT));
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
                Arrays.asList("life_meditation", "life_alcohol", "life_caffeine_late", "life_stress", "life_hard_workout", "life_sauna", "life_friends",
                        "life_holiday", "life_illness", "life_travel", "life_own_late_dinner_", "life_own_late_dinner", "life_own_late_dinner__2",
                        "life_own_sauna_\u00e0_deux"));
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
                LifeTags.STRESS, LifeTags.WORKOUT, LifeTags.SAUNA, LifeTags.FRIENDS, LifeTags.HOLIDAY, LifeTags.ILLNESS, LifeTags.TRAVEL)));
        int spanDays = 0;
        for (LocalDate[] s : LifeTags.demoSpans(TODAY, cold)) spanDays += LifeTags.span(s[0], s[1]).size();
        eq("the demo's two spans cover the illness and holiday days", spanDays, illness + holiday);
        eq("the same day gives the same tags", LifeTags.demo(TODAY, days, cold), d);
    }

    /**
     * Issue #422: own tags with the same name (cleaned, any case) and both in force, or both removed, are one factor under the
     * older key; the newer key is an alias. A removed one never joins one in force; other spellings stay apart.
     */
    static void sameName() {
        eq("the same name: cleaned, any case", LifeTags.sameName("  SAUNA ") + "|" + LifeTags.sameName("sauna") + "|" + LifeTags.sameName("Sauna\tvisit"),
                "sauna|sauna|sauna visit");
        check("no name, no same name", LifeTags.sameName(null) == null);
        List<LifeTags.Choice> ch = new ArrayList<LifeTags.Choice>();
        // the backup's Sauna (older, taken off the card long ago), the one made again after Delete all (on the card), a third
        // spelled in capitals, a removed Sauna and another removed one, a different spelling, and a Sauna with no name
        ch.add(new LifeTags.Choice("own:5000", "Sauna", 1, null, false));
        ch.add(new LifeTags.Choice("own:1000", "Sauna", 0, 3, false));
        ch.add(new LifeTags.Choice("own:7000", " SAUNA", 0, null, false));
        ch.add(new LifeTags.Choice("own:500", "sauna", 1, null, true));
        ch.add(new LifeTags.Choice("own:600", "Sauna ", 1, null, true));
        ch.add(new LifeTags.Choice("own:2000", "Sauna visit", null, null, false));
        ch.add(new LifeTags.Choice("own:8000", null, 1, null, false));
        LifeTags.Known k = new LifeTags.Known(ch);
        eq("one factor per name: the older key, the removed ones apart, another spelling apart", new ArrayList<String>(k.all.keySet()).subList(11, k.all.size()),
                Arrays.asList("own:500", "own:1000", "own:2000"));
        eq("the older key's name and its removal state", k.all.get("own:1000").toString() + " | " + k.all.get("own:500").toString(),
                "own:1000=Sauna chip | own:500=sauna removed");
        eq("an alias is read as the older key", k.key("own:5000") + " " + k.key("own:7000") + " " + k.key("own:600") + " " + k.key("own:2000") + " "
                + k.key("alcohol") + " " + k.key("own:9"), "own:1000 own:1000 own:500 own:2000 alcohol own:9");
        eq("the aliases, oldest first", k.aliases("own:1000"), Arrays.asList("own:5000", "own:7000"));
        eq("a removed factor's aliases", k.aliases("own:500"), Arrays.asList("own:600"));
        check("no aliases for a key alone with its name or a fixed tag", k.aliases("own:2000").isEmpty() && k.aliases("alcohol").isEmpty() && k.aliases("own:5000").isEmpty());
        eq("the name of an alias is its factor's", k.name("own:7000") + "|" + k.name("own:600"), "Sauna|sauna");
        eq("one chip, on the card when any key of it is, in the older key's place", keys(k.chips()),
                Arrays.asList("own:1000", "meditation", "sauna", "friends", "alcohol", "stress", "hard_workout", "own:2000"));
        eq("one entry in All factors and in Remove one of your own", keys(k.own()), Arrays.asList("own:1000", "own:2000"));
        eq("names: a day with both keys names it once", k.names(Arrays.asList("own:5000", "own:1000", "alcohol")), Arrays.asList("Alcohol", "Sauna"));
        Map<LocalDate, List<String>> days = new TreeMap<LocalDate, List<String>>();
        days.put(LocalDate.of(2026, 11, 1), Arrays.asList("own:1000"));
        days.put(LocalDate.of(2026, 11, 2), Arrays.asList("alcohol", "own:5000"));
        days.put(LocalDate.of(2026, 11, 3), Arrays.asList("own:1000", "own:5000", "own:7000", "stress"));
        days.put(LocalDate.of(2026, 11, 4), Arrays.asList("own:600", "own:2000"));
        TreeMap<LocalDate, List<String>> f = k.fold(days);
        eq("fold: each day's factors once, in key order", f.toString(),
                "{2026-11-01=[own:1000], 2026-11-02=[alcohol, own:1000], 2026-11-03=[own:1000, stress], 2026-11-04=[own:2000, own:500]}");
        eq("fold leaves its input as it was", days.get(LocalDate.of(2026, 11, 3)), Arrays.asList("own:1000", "own:5000", "own:7000", "stress"));
        check("fold of nothing", k.fold(null).isEmpty());
        eq("the month's counts: the days of both keys, a day once", LifeTags.monthLine(f, YearMonth.of(2026, 11), k),
                "This month: Sauna on 3 days, Alcohol on 1 day, Stress on 1 day, sauna on 1 day, Sauna visit on 1 day.");
        eq("CSV: one column per factor", new ArrayList<String>(LifeTags.csvColumns(k, true, false).values()).subList(10, 13),
                Arrays.asList("life_own_sauna", "life_own_sauna_2", "life_own_sauna_visit"));
        // alone, each is its own factor as before
        List<LifeTags.Choice> one = new ArrayList<LifeTags.Choice>();
        one.add(new LifeTags.Choice("own:5000", "Sauna", 0, null, false));
        LifeTags.Known k1 = new LifeTags.Known(one);
        check("an own tag alone with its name: its own key, its own choice", k1.key("own:5000").equals("own:5000") && !k1.all.get("own:5000").chip
                && k1.aliases("own:5000").isEmpty());
    }

    // ---------------------------------------------------------------- issue #435: when and how long

    /** Which tags take a time, the lengths per tag, the start the sheet suggests, the stored "HH:MM", the texts. */
    static void times435() {
        for (String k : new String[] {LifeTags.SAUNA, LifeTags.MEDITATION, LifeTags.WORKOUT, LifeTags.INTIMACY, LifeTags.FRIENDS, LifeTags.ALCOHOL,
            LifeTags.CAFFEINE, "own:1700000000000"}) {
            check("#435 takes a time: " + k, app.openring.core.LifeTimes.timed(k));
        }
        for (String k : new String[] {LifeTags.STRESS, LifeTags.HOLIDAY, LifeTags.ILLNESS, LifeTags.TRAVEL, "wine", null}) {
            check("#435 a day, no time: " + k, !app.openring.core.LifeTimes.timed(k));
        }
        eq("#435 the lengths per tag", Arrays.toString(app.openring.core.LifeTimes.lengths(LifeTags.SAUNA)) + Arrays.toString(app.openring.core.LifeTimes.lengths(LifeTags.MEDITATION))
                + Arrays.toString(app.openring.core.LifeTimes.lengths(LifeTags.WORKOUT)) + Arrays.toString(app.openring.core.LifeTimes.lengths(LifeTags.INTIMACY))
                + Arrays.toString(app.openring.core.LifeTimes.lengths(LifeTags.FRIENDS)) + Arrays.toString(app.openring.core.LifeTimes.lengths("own:5")),
                "[15, 30, 45, 60][10, 20, 30][30, 60, 90][15, 30, 60][60, 120, 180, 300][15, 30, 60, 120]");
        check("#435 Alcohol and Caffeine late: a time only, no length", app.openring.core.LifeTimes.lengths(LifeTags.ALCOHOL).length == 0
                && app.openring.core.LifeTimes.lengths(LifeTags.CAFFEINE).length == 0 && app.openring.core.LifeTimes.usual(LifeTags.ALCOHOL) == 0);
        boolean usualOffered = true;
        for (String k : new String[] {LifeTags.SAUNA, LifeTags.MEDITATION, LifeTags.WORKOUT, LifeTags.INTIMACY, LifeTags.FRIENDS, "own:5"}) {
            boolean in = false;
            for (int m : app.openring.core.LifeTimes.lengths(k)) in |= m == app.openring.core.LifeTimes.usual(k);
            usualOffered &= in;
        }
        check("#435 the usual length of each tag is one of its chips", usualOffered);
        eq("#435 length labels", app.openring.core.LifeTimes.lengthLabel(15) + "|" + app.openring.core.LifeTimes.lengthLabel(60) + "|"
                + app.openring.core.LifeTimes.lengthLabel(90) + "|" + app.openring.core.LifeTimes.lengthLabel(300), "15 min|1 h|90 min|5 h");
        eq("#435 the sheet starts at now minus the usual length, down to 5 minutes", app.openring.core.LifeTimes.startFor(LifeTags.SAUNA, 19 * 60 + 47), 19 * 60 + 15);
        eq("#435 Friends: two hours back", app.openring.core.LifeTimes.startFor(LifeTags.FRIENDS, 22 * 60 + 3), 20 * 60);
        eq("#435 Alcohol: now", app.openring.core.LifeTimes.startFor(LifeTags.ALCOHOL, 21 * 60 + 34), 21 * 60 + 30);
        eq("#435 just after midnight: never before 00:00 of the day", app.openring.core.LifeTimes.startFor(LifeTags.WORKOUT, 20), 0);
        eq("#435 stored as HH:MM", app.openring.core.LifeTimes.clock(19 * 60 + 5) + " " + app.openring.core.LifeTimes.clock(0) + " " + app.openring.core.LifeTimes.clock(1439), "19:05 00:00 23:59");
        eq("#435 read back", app.openring.core.LifeTimes.parse("19:05") + " " + app.openring.core.LifeTimes.parse("00:00") + " " + app.openring.core.LifeTimes.parse("23:59"), "1145 0 1439");
        for (String bad : new String[] {null, "", "24:00", "7:05", "19:60", "19:05:00", "ab:cd", " 19:05"}) {
            check("#435 not a time: " + bad, app.openring.core.LifeTimes.parse(bad) == -1);
        }
        check("#435 lengths that can be stored: none, 1 minute to a day", app.openring.core.LifeTimes.validLength(null) && app.openring.core.LifeTimes.validLength(1)
                && app.openring.core.LifeTimes.validLength(1440) && !app.openring.core.LifeTimes.validLength(0) && !app.openring.core.LifeTimes.validLength(1441)
                && !app.openring.core.LifeTimes.validLength(-30));
        eq("#435 the chip's second line", app.openring.core.LifeTimes.chipLine("19:00", 45) + "|" + app.openring.core.LifeTimes.chipLine("20:30", null) + "|"
                + app.openring.core.LifeTimes.chipLine(null, 45), "19:00, 45 min|20:30|null");
        eq("#435 after Save", app.openring.core.LifeTimes.savedLine("Sauna", LocalDate.of(2026, 11, 21), "19:00", 45, 2026, EN), "Sauna on 21 Nov: from 19:00 for 45 min.");
        eq("#435 after Save, a time only", app.openring.core.LifeTimes.savedLine("Alcohol", LocalDate.of(2026, 11, 21), "20:30", null, 2026, EN), "Alcohol on 21 Nov: at 20:30.");
        eq("#435 after Without a time", app.openring.core.LifeTimes.savedLine("Sauna", LocalDate.of(2026, 11, 21), null, null, 2026, EN), "Sauna on 21 Nov: without a time.");
        eq("#435 the sheet's texts", app.openring.core.LifeTimes.TITLE + "|" + app.openring.core.LifeTimes.FROM + "|" + app.openring.core.LifeTimes.HOW_LONG + "|"
                + app.openring.core.LifeTimes.SAVE + "|" + app.openring.core.LifeTimes.WITHOUT, "When and how long?|From|How long|Save|Without a time");
        eq("#435 the All factors hint", app.openring.core.LifeTimes.HOLD_HINT, "Hold a chip to add when and how long. That shows it on the day's charts.");
        eq("#435 not worn", app.openring.core.LifeTimes.NOT_WORN, "Not worn during");
        for (String t : new String[] {app.openring.core.LifeTimes.TITLE, app.openring.core.LifeTimes.HOLD_HINT, app.openring.core.LifeTimes.TIME_ONLY,
            app.openring.core.LifeTimes.NOT_WORN, app.openring.core.LifeTimes.savedLine("Sauna", TODAY, "19:00", 45, 2026, EN)}) {
            check("#435 plain, no dash as punctuation: " + t, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014") && !t.contains("--"));
        }
    }

    /**
     * The default chips for a new person; a person who chose keeps exactly the chosen chips (Sauna and Friends, which the choice
     * did not know, stay off); an own "Sauna" made before stays next to the built in one, a new one with that name is refused.
     */
    static void chips435() {
        eq("#435 a new person: Meditation, Sauna, Friends, Alcohol, Stress, Hard workout, in this order", keys(new LifeTags.Known(null).chips()),
                Arrays.asList("meditation", "sauna", "friends", "alcohol", "stress", "hard_workout"));
        // what Choose the chips on the card stored before #435: every fixed tag of then, the chosen ones with their place
        List<LifeTags.Choice> chose = new ArrayList<LifeTags.Choice>();
        String[] old = {"meditation", "alcohol", "caffeine_late", "stress", "hard_workout", "intimacy", "holiday", "illness", "travel"};
        for (String k : old) {
            Integer ord = k.equals("caffeine_late") ? (Integer) 0 : k.equals("travel") ? (Integer) 1 : null;
            chose.add(new LifeTags.Choice(k, null, ord != null ? 1 : 0, ord, false));
        }
        eq("#435 a person who chose: exactly the chosen chips in their order, Sauna and Friends not added", keys(new LifeTags.Known(chose).chips()),
                Arrays.asList("caffeine_late", "travel"));
        // the old defaults kept as they were (never chosen): the new defaults
        List<LifeTags.Choice> own = new ArrayList<LifeTags.Choice>();
        own.add(new LifeTags.Choice("own:1600000000000", "Sauna", null, null, false));
        own.add(new LifeTags.Choice("own:1600000000001", "friends", 0, null, false));
        LifeTags.Known k = new LifeTags.Known(own);
        check("#435 an own Sauna made before stays, next to the built in one: two keys, both named Sauna, both in All factors", k.all.containsKey("sauna")
                && k.all.containsKey("own:1600000000000") && k.name("sauna").equals("Sauna") && k.name("own:1600000000000").equals("Sauna")
                && keys(k.loggable()).contains("sauna") && keys(k.loggable()).contains("own:1600000000000") && k.key("own:1600000000000").equals("own:1600000000000"));
        eq("#435 own tags never join a built in one: the chips", keys(k.chips()),
                Arrays.asList("meditation", "sauna", "friends", "alcohol", "stress", "hard_workout", "own:1600000000000"));
        check("#435 a new own tag named Sauna or Friends (any case) is refused, the built in one is there", LifeTags.cleanName("Sauna") == null
                && LifeTags.cleanName(" FRIENDS ") == null && LifeTags.cleanName("Sauna visit").equals("Sauna visit"));
    }

    static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    /** The band of a logged time: across midnight, across both clock changes, by the hour of a day, and what a touch reads. */
    static void bands435() {
        LocalDate d = LocalDate.of(2026, 11, 20);
        app.openring.core.LifeTimes.Band b = app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "19:00", 45, ZURICH);
        eq("#435 a sauna at 19:00 for 45 minutes: from 19:00 to 19:45 that day", (long) b.start + " " + (long) b.stop,
                d.atTime(19, 0).atZone(ZURICH).toEpochSecond() + " " + d.atTime(19, 45).atZone(ZURICH).toEpochSecond());
        eq("#435 the touch reads", app.openring.core.LifeTimes.tip(b, ZURICH), "Sauna 19:00 to 19:45");
        app.openring.core.LifeTimes.Band late = app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "23:30", 90, ZURICH);
        eq("#435 23:30 for 90 minutes reaches into the next day, to 01:00", app.openring.core.LifeTimes.tip(late, ZURICH) + " "
                + ((long) late.stop == d.plusDays(1).atTime(1, 0).atZone(ZURICH).toEpochSecond()), "Sauna 23:30 to 01:00 true");
        double ds = d.atStartOfDay(ZURICH).toEpochSecond(), de = d.plusDays(1).atStartOfDay(ZURICH).toEpochSecond(), de2 = d.plusDays(2).atStartOfDay(ZURICH).toEpochSecond();
        List<app.openring.core.LifeTimes.Entry> es = new ArrayList<app.openring.core.LifeTimes.Entry>();
        es.add(new app.openring.core.LifeTimes.Entry(d, LifeTags.SAUNA, "23:30", 90));
        es.add(new app.openring.core.LifeTimes.Entry(d, LifeTags.ALCOHOL, "20:30", null));
        es.add(new app.openring.core.LifeTimes.Entry(d.minusDays(1), LifeTags.MEDITATION, "07:00", 20));
        eq("#435 the day's bands, by start", app.openring.core.LifeTimes.bands(es, ds, de, ZURICH, null).toString().replaceAll("[0-9]", ""), "[Alcohol  , Sauna  ]");
        eq("#435 the next day: the sauna's last hour too", app.openring.core.LifeTimes.bands(es, de, de2, ZURICH, null).size(), 1);
        eq("#435 by the hour: 23.5 to 24 that day, 0 to 1 the next", Arrays.toString(app.openring.core.LifeTimes.hours(late, d, ZURICH)) + " "
                + Arrays.toString(app.openring.core.LifeTimes.hours(late, d.plusDays(1), ZURICH)) + " " + app.openring.core.LifeTimes.hours(late, d.plusDays(2), ZURICH),
                "[23.5, 24.0] [0.0, 1.0] null");
        app.openring.core.LifeTimes.Band mark = app.openring.core.LifeTimes.band(d, LifeTags.ALCOHOL, "Alcohol", "20:30", null, ZURICH);
        eq("#435 a time without a length: a mark, read as at", app.openring.core.LifeTimes.tip(mark, ZURICH) + " " + (mark.start == mark.stop) + " "
                + Arrays.toString(app.openring.core.LifeTimes.hours(mark, d, ZURICH)), "Alcohol at 20:30 true [20.5, 20.5]");
        check("#435 a mark holds a moment near it, a band its own time", mark.holds(mark.start + 100, 120) && !mark.holds(mark.start + 200, 120)
                && b.holds(b.start + 600, 0) && !b.holds(b.stop + 1, 0));
        // an own tag with the same name as an older one: drawn and named as that one
        List<LifeTags.Choice> ch = new ArrayList<LifeTags.Choice>();
        ch.add(new LifeTags.Choice("own:1000", "Cold plunge", 1, null, false));
        ch.add(new LifeTags.Choice("own:2000", "cold plunge", 1, null, false));
        List<app.openring.core.LifeTimes.Entry> alias = new ArrayList<app.openring.core.LifeTimes.Entry>();
        alias.add(new app.openring.core.LifeTimes.Entry(d, "own:2000", "07:10", 15));
        LifeTags.Known k = new LifeTags.Known(ch);
        app.openring.core.LifeTimes.Band ab = app.openring.core.LifeTimes.bands(alias, ds, de, ZURICH, k).get(0);
        check("#435 an alias's time is drawn as its factor (the older key, its name)", ab.key.equals("own:1000") && ab.name.equals("Cold plunge")
                && app.openring.core.LifeTimes.find(alias, d, "own:1000", k) != null && app.openring.core.LifeTimes.find(alias, d, "own:1000", null) == null);
        // the clocks go back in Zurich on 25 Oct 2026 (03:00 becomes 02:00): 4 real hours from 23:30 end at 02:30
        LocalDate back = LocalDate.of(2026, 10, 24);
        app.openring.core.LifeTimes.Band over = app.openring.core.LifeTimes.band(back, LifeTags.FRIENDS, "Friends", "23:30", 240, ZURICH);
        eq("#435 across the night the clocks go back: 4 real hours, 23:30 to 02:30", app.openring.core.LifeTimes.tip(over, ZURICH) + " " + (over.stop - over.start), "Friends 23:30 to 02:30 14400.0");
        app.openring.core.LifeTimes.Band eve = app.openring.core.LifeTimes.band(back.plusDays(1), LifeTags.SAUNA, "Sauna", "19:00", 45, ZURICH);
        eq("#435 on the 25 hour day the evening is drawn at its local time", (long) eve.start, LocalDate.of(2026, 10, 25).atTime(19, 0).atZone(ZURICH).toEpochSecond());
        eq("#435 by the hour on the 25 hour day: the clock hours", Arrays.toString(app.openring.core.LifeTimes.hours(eve, back.plusDays(1), ZURICH)), "[19.0, 19.75]");
        // the clocks go forward on 28 Mar 2027 (02:00 becomes 03:00): 60 real minutes from 01:30 end at 03:30; 02:30 does not exist
        LocalDate fwd = LocalDate.of(2027, 3, 28);
        app.openring.core.LifeTimes.Band spring = app.openring.core.LifeTimes.band(fwd, LifeTags.INTIMACY, "Intimacy", "01:30", 60, ZURICH);
        eq("#435 across the night the clocks go forward: 60 real minutes, 01:30 to 03:30", app.openring.core.LifeTimes.tip(spring, ZURICH), "Intimacy 01:30 to 03:30");
        app.openring.core.LifeTimes.Band skipped = app.openring.core.LifeTimes.band(fwd, LifeTags.SAUNA, "Sauna", "02:30", 30, ZURICH);
        eq("#435 a clock time the clocks skip is read after the skip, as the clock shows it", app.openring.core.LifeTimes.tip(skipped, ZURICH), "Sauna 03:30 to 04:00");
        app.openring.core.LifeTimes.Band twice = app.openring.core.LifeTimes.band(LocalDate.of(2026, 10, 25), LifeTags.SAUNA, "Sauna", "02:30", 30, ZURICH);
        eq("#435 a clock time that happens twice is the first one", (long) twice.start, java.time.ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZURICH).withEarlierOffsetAtOverlap().toEpochSecond());
        check("#435 no band for a time that is not one", app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "25:00", 30, ZURICH) == null
                && app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "19:00", 0, ZURICH) == null && app.openring.core.LifeTimes.band(null, LifeTags.SAUNA, "Sauna", "19:00", 30, ZURICH) == null);
    }

    /** What the heart rate line measured during a band, and the line under the chart. */
    static void during435() {
        LocalDate d = LocalDate.of(2026, 11, 20);
        double ds = d.atStartOfDay(ZURICH).toEpochSecond();
        app.openring.core.analysis.TS line = new app.openring.core.analysis.TS();
        // quarter hour points in the middle of their quarter hour, 72 all day, 104 and 110 at 19:07:30 and 19:22:30
        for (int q = 0; q < 96; q++) {
            double t = ds + q * 900 + 450;
            line.add(t, q == 76 ? 104 : q == 77 ? 110 : q == 78 ? 98 : 72);
        }
        app.openring.core.LifeTimes.Band b = app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "19:00", 45, ZURICH);
        double v = app.openring.core.LifeTimes.during(line, b, 900);
        eq("#435 the three quarter hours of 19:00 to 19:45", Math.round(v), 104L);
        eq("#435 the line under the chart", app.openring.core.LifeTimes.duringLine(b, v, 72.4, ZURICH), "Sauna 19:00 to 19:45. Heart rate during: 104 on average (your day: 72).");
        app.openring.core.LifeTimes.Band short15 = app.openring.core.LifeTimes.band(d, LifeTags.SAUNA, "Sauna", "19:20", 15, ZURICH);
        eq("#435 a band shorter than the points: the points it reaches into (19:15 and 19:30)", Math.round(app.openring.core.LifeTimes.during(line, short15, 900)), 104L);
        app.openring.core.LifeTimes.Band mark = app.openring.core.LifeTimes.band(d, LifeTags.ALCOHOL, "Alcohol", "19:20", null, ZURICH);
        eq("#435 a time without a length: the point whose quarter hour holds it", app.openring.core.LifeTimes.during(line, mark, 900), 110.0);
        app.openring.core.analysis.TS gap = new app.openring.core.analysis.TS();
        gap.add(ds + 8 * 3600, 70);
        gap.add(ds + 22 * 3600, 66);
        double none = app.openring.core.LifeTimes.during(gap, b, 900);
        check("#435 no point during the band: not worn", Double.isNaN(none) && Double.isNaN(app.openring.core.LifeTimes.during(null, b, 900)));
        eq("#435 not worn during", app.openring.core.LifeTimes.duringLine(b, none, 68, ZURICH), "Sauna 19:00 to 19:45. Not worn during.");
        eq("#435 without the day's average: no bracket", app.openring.core.LifeTimes.duringLine(b, 100.4, Double.NaN, ZURICH), "Sauna 19:00 to 19:45. Heart rate during: 100 on average.");
        app.openring.core.analysis.TS withNaN = new app.openring.core.analysis.TS();
        withNaN.add(ds + 19 * 3600 + 450, Double.NaN);
        check("#435 a point without a value does not count", Double.isNaN(app.openring.core.LifeTimes.during(withNaN, b, 900)));
        for (String t : new String[] {app.openring.core.LifeTimes.duringLine(b, v, 72, ZURICH), app.openring.core.LifeTimes.duringLine(b, none, 72, ZURICH),
            app.openring.ui.Explain.LIFE_TIMES, app.openring.ui.Explain.LIFE_DURING}) {
            check("#435 plain, no dash, no cause: " + t, !t.contains(" - ") && !t.contains("\u2013") && !t.contains("\u2014") && !t.contains("--")
                    && !t.toLowerCase(Locale.ROOT).contains("caused") && !t.toLowerCase(Locale.ROOT).contains("because"));
        }
    }

    /** The demo's times: each on a day the demo gives that tag, a time and a length the sheet offers, never Intimacy. */
    static void demoTimes435() {
        TreeMap<LocalDate, List<String>> days = LifeTags.demo(TODAY, 35, 15);
        boolean ok = true;
        for (app.openring.core.LifeTimes.Entry e : app.openring.core.LifeTimes.demo(TODAY)) {
            ok &= days.containsKey(e.day) && days.get(e.day).contains(e.key) && app.openring.core.LifeTimes.timed(e.key)
                    && app.openring.core.LifeTimes.parse(e.time) >= 0 && e.day.isBefore(TODAY) && !e.key.equals(LifeTags.INTIMACY);
            if (e.minutes != null) {
                boolean offered = false;
                for (int m : app.openring.core.LifeTimes.lengths(e.key)) offered |= m == e.minutes;
                ok &= offered;
            } else {
                ok &= app.openring.core.LifeTimes.lengths(e.key).length == 0;
            }
        }
        check("#435 the demo's times lie on its own tagged days, past days only, with lengths the sheet offers", ok);
        eq("#435 yesterday's sauna in the evening", app.openring.core.LifeTimes.find(app.openring.core.LifeTimes.demo(TODAY), TODAY.minusDays(1), LifeTags.SAUNA, null).toString(),
                TODAY.minusDays(1) + " sauna 19:00 45");
    }

    static final String RESTORE_TEXT = "Period days and life factors you logged or removed on this phone stay as they are.";

    /** Issue #421: the restore card says that the life factors on this phone stay (ui/SetupActivity, Android, read as source). */
    static void restoreText() {
        String src;
        try {
            src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/main/java/app/openring/ui/SetupActivity.java")), "UTF-8");
        } catch (java.io.IOException e) {
            src = "";
        }
        check("the restore card names the life factors that stay", src.contains("\"" + RESTORE_TEXT + "\""));
        check("the restore card's old text is gone", !src.contains("\"Period days you logged or removed on this phone stay as they are.\""));
        String card;
        try {
            card = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/main/java/app/openring/ui/LifeCard.java")), "UTF-8");
        } catch (java.io.IOException e) {
            card = "";
        }
        check("the several days question comes from LifeTags.spanDone (#421)", card.contains("LifeTags.spanDone(n)") && !card.contains("One entry on"));
    }
}
