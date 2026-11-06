package app.openring.ui;

import app.openring.core.analysis.Badges;

/** Plain language explanations of the scores and their contributors. */
public final class Explain {
    private Explain() {}

    public static final String READINESS = "Readiness shows how recovered your body is today and how much you can take on. "
            + "It compares last night's resting heart rate, heart rate variability and body temperature with your own normal, "
            + "and adds your recent sleep and activity. 85 or more: ready for a demanding day. 70 to 84: good, keep a normal pace. "
            + "Below 70: your body is asking for rest.";

    public static final String SLEEP = "The sleep score sums up how long and how well you slept: total sleep, how much of the "
            + "time in bed you were asleep, how calm the night was, your deep and REM sleep, how fast you fell asleep and whether "
            + "your sleep fits your body clock.";

    public static final String ACTIVITY = "The activity score looks at how much you moved, how long you sat still, whether you "
            + "reached your daily goal and your training over the last week. Balance matters: both too little and too much lower it. "
            + "Days without the ring are left out of the weekly parts.";

    /**
     * What each sleep stage detail level does (Settings, issues #9, #28), indexed like {@code StageFilter.NAMES}.
     * Balanced and Calm name what always stays awake however short (issue #30): phone use and the ring's own
     * wake ups are marked awake again after the filter, and awake next to missing data is never folded.
     */
    public static final String[] STAGE_DETAIL = {
        "Detailed: every stage change the estimate finds, even short ones. Many thin lines.",
        "Balanced (recommended): sleep stages shorter than 3 minutes join the sleep stage next to them. "
            + "Being awake for 30 seconds between sleep joins the sleep around it, 1 minute or more always stays, "
            + "and short sleep is never turned into awake. Phone use, a wake up your ring reports itself and being awake "
            + "next to time without ring data always stay awake, even when short.",
        "Calm: sleep stages shorter than 5 minutes join the sleep stage next to them, being awake needs 2 minutes to stay. "
            + "Long, calm blocks, similar in style to the official app. Shorter wake ups are no longer shown or counted, "
            + "except phone use, a wake up your ring reports itself and being awake next to time without ring data: these always stay. "
            + "Short sleep is never turned into awake."
    };

    /**
     * The line under the stage detail preview when the night had phone use counted as awake (issue #30),
     * or null without any. {@code phoneMin} is the night's stored phone minutes, as on the Sleep page.
     */
    public static String previewPhone(double phoneMin) {
        if (!(phoneMin > 0)) return null;
        String min = phoneMin < 1 ? "under a minute" : Math.round(phoneMin) + " min";
        return "Includes " + min + " on the phone, shown as awake at every detail level.";
    }

    /** The note above the activity contributors while fewer than 7 days of the last week were worn (issue #44). */
    public static String activityDays(int n) {
        String days = n == 1 ? "1 day" : n + " days";
        String rule = " with the ring in the last 7 days (a day counts with at least an hour of wear). ";
        if (n >= app.openring.core.analysis.Scores.MIN_WEEK_DAYS) return "Based on " + days + rule + "Daily targets and training are steadier after a full week.";
        return "Only " + days + rule + "Daily targets and training count once you have worn the ring on "
                + app.openring.core.analysis.Scores.MIN_WEEK_DAYS + " of these 7 days.";
    }

    /**
     * The line under Total burn (issue #155): a day still running when it was analysed counts its resting burn only up
     * to {@code until} (unix seconds, NaN for a day that has ended), shown as a clock time in {@code zone}.
     */
    public static String totalBurn(double until, java.time.ZoneId zone) {
        if (Double.isNaN(until)) return "Active burn plus what your body uses at rest";
        return "So far, up to " + app.openring.core.analysis.WakeUps.clock(until, zone) + " (active plus resting burn)";
    }

    /** What VO2 max and its unit mean, under the Cardio capacity card (issue #116). */
    public static final String VO2MAX = "VO2 max is the most oxygen your body can use per minute in hard exercise, "
            + "for each kg of body weight (ml/kg/min). Higher means fitter heart and lungs.";

    /**
     * The line under the Cardio capacity card for the day's stored VO2 max {@code stored} (issue #116):
     * where the number comes from, why there is none (no night, or a night without a usable lowest heart
     * rate), or why an impossible one is not shown. {@code hadNight}: the day has a main night.
     */
    public static String vo2max(double stored, boolean hadNight) {
        if (Double.isNaN(stored)) {
            if (hadNight) return "No usable heart rate in that night's sleep, so no estimate. It needs the lowest heart rate of the night (Uth method).";
            return "Needs a night with the ring. It comes from your age and the lowest heart rate in that night's sleep (Uth method).";
        }
        if (Double.isNaN(app.openring.core.analysis.Wellness.vo2maxShown(stored))) {
            return "Not shown for this day: that night's lowest heart rate gave a value outside what a person can have ("
                    + Math.round(app.openring.core.analysis.Wellness.VO2MAX_LOW) + " to " + Math.round(app.openring.core.analysis.Wellness.VO2MAX_HIGH)
                    + " ml/kg/min), so that reading was probably off.";
        }
        return "From your age and the lowest heart rate in that night's sleep (Uth method). A rough guide, not a lab test.";
    }

    public static final String SCREEN = "The screen score looks at your phone use: how long the screen was on compared with your "
            + "daily goal (half), phone use in the hour before you fell asleep (about a third) and during the night (the rest). "
            + "Days without a known night only count the screen time, so the same screen time scores lower there. It is a simple rule of thumb made for Open Ring, not a medical measure. "
            + "85 or more: calm phone use. Below 70: the phone took a lot of your day or night.";

    public static final String STAGES = "Your sleep moves in cycles of about 90 minutes. Deep sleep (bottom) restores the body "
            + "and comes mostly early in the night, REM sleep (dreaming) grows towards the morning, short wake ups are normal.";

    public static final String NIGHT_SIGNALS = "Skin temperature, blood oxygen and breathing rate are measured while you sleep, "
            + "the same way the official app does it. Every night you wear the ring adds a point to the trends below after the morning sync.";

    /** Find my ring, the open last worn strip: where the line above comes from (issue #74, #94). */
    public static final String FINDER_LAST = "From the ring's stored skin temperature (27 °C or more is a finger) and heart rate, as far as the last sync reaches. "
            + "When the ring stores daytime heart rate, warm without one for over an hour (charger, sun, duvet) does not count as worn.";

    /** Find my ring, How it works: the gauge, the trail strip and their limits (issue #191). */
    public static final String FINDER_SIGNAL = "Gauge and trail: the ring's Bluetooth signal in dBm, closer to 0 is stronger. "
            + "The gauge fills and the clicks get faster as you get closer; the trail shows the last 2 minutes, touch it to read a moment. "
            + "The signal jumps with walls, blankets, your body and the ring's angle. A ring under a duvet sounds farther away than it is. "
            + "Walk slowly and follow the trend.";

    /** Find my ring, How it works: the clicks chip in the top bar (issue #191). */
    public static final String FINDER_CLICKS = "Clicks (top right): a click and a short buzz, faster as the signal gets stronger. Tap to switch them off or on.";

    /** Under Daily movement when the ring was off for a while (issue #212). */
    public static final String NOT_WORN = "Not worn is time outside your sleep without movement values from the ring, for example while "
            + "it was charging. It is not part of the percentages.";

    /**
     * What a left out time does (issue #254): Settings, Advanced, and the Left out cards on Today, Sleep and Activity.
     */
    public static final String LEFT_OUT = "A left out time is a stretch when someone else wore your ring. Its nights, heart rate, temperature "
            + "and activity are left out of your charts, scores and usual values (resting heart rate, HRV, skin temperature and your "
            + "sleep pattern). Its minutes are not in your daily movement. The ring's data itself stays stored on this phone, and it counts again "
            + "as soon as you remove the left out time in Settings, Advanced. The app cannot tell who wore the ring; you pick the time.";

    // ---------------------------------------------------------------- period tracker (issue #252)

    /** "your last 8 cycles", "your 2 cycles". */
    static String yourCycles(int n) {
        return n == 1 ? "your 1 cycle" : n < app.openring.core.analysis.Cycles.TRIM_FROM ? "your " + n + " cycles" : "your last " + n + " cycles";
    }

    /**
     * Under "Expected ...": how the typical length and the range ("about 4 in 5") are worked out, and from how many cycles.
     * {@code cycles} is the number the estimate rests on, {@code sd} their spread in days.
     */
    public static String periodRange(int cycles, int length, int range, double sd) {
        String from = cycles == 0 ? "Typical cycle " + length + " days, the average in a large study of real cycles, until you have your own. "
                : cycles < app.openring.core.analysis.Cycles.TRIM_FROM ? "Typical cycle " + length + " days, the middle value of " + yourCycles(cycles) + ". "
                : "Typical cycle " + length + " days, the average of " + yourCycles(cycles) + " without the shortest and the longest. ";
        if (cycles < app.openring.core.analysis.Cycles.FEW) return from + "The next start is your last start plus that, give or take " + range + " days while there are fewer than "
                + app.openring.core.analysis.Cycles.FEW + " cycles.";
        return from + "About 4 in 5: the range is give or take 1.28 times how much your cycles vary (" + String.format(java.util.Locale.getDefault(), "%.1f", sd)
                + " days), which holds about 4 in 5 starts when your cycles vary as before. At least 1 day.";
    }

    /** Under the ovulation line: how "give or take N days" is worked out. */
    public static String periodOvulation(int cycles, int range) {
        String spread = cycles < app.openring.core.analysis.Cycles.FEW ? "a typical spread of 2.6 days (in place of your own until you have "
                + app.openring.core.analysis.Cycles.FEW + " cycles)" : "how much " + yourCycles(cycles) + " vary";
        return "Give or take " + range + " days: " + spread + ", together with how much the time from ovulation to the next period varies "
                + "(about 2.4 days in a large study), times 1.28 for about 4 in 5. At least 3 days.";
    }

    /** Under "Your cycles vary by about N days". */
    public static String periodVary(int cycles) {
        return "Vary by: the standard deviation of " + yourCycles(cycles) + ", how far a cycle usually lies from their average. "
                + "Cycles far from the rest (more than 10 days from the middle value) are left out, maybe a period was not logged.";
    }

    // ------------------------------------------------- period tracker, "Your cycles" (issue #252, phase 1a2)

    /** Under Cycle day. */
    public static final String PERIOD_CYCLE_DAY = "Cycle day: the days since your last period started, that day counted as 1.";

    /** Under Next period: what the days count to, or why there is no number. */
    public static String periodNext(Integer days, boolean stopped, int late) {
        if (stopped) return "No estimate: you said your periods have stopped.";
        if (days == null) return "Appears once you log a period. After twice your typical cycle without one, it waits for the next.";
        if (late > 0) return "Days to the expected day of the estimate above. It has passed, cycles vary.";
        return "Days to the expected day of the estimate above, the middle of its range.";
    }

    /** Under the cycle length numbers: what they count, from how many cycles, and what is left out. */
    public static String periodCycles(int cycles, int leftOut) {
        String s = "From one period start to the next, over " + (cycles == 1 ? "your 1 cycle" : "all your " + cycles + " cycles") + ". "
                + "Average and spread (the standard deviation, how far a cycle usually lies from the average), shortest and longest. ";
        if (leftOut > 0) s += (leftOut == 1 ? "1 cycle" : leftOut + " cycles") + " more than 10 days from the middle value "
                + (leftOut == 1 ? "is" : "are") + " left out, maybe a period was not logged. ";
        return s + "The estimate above uses only your newest " + app.openring.core.analysis.Cycles.WINDOW + " cycles.";
    }

    /** Under the period length numbers. */
    public static String periodLengths(int periods) {
        return "From the first to the last bleeding day of each period, both counted, over " + (periods == 1 ? "your 1 period" : "your " + periods + " periods")
                + " that are over. Days without a log inside a period count, spotting does not.";
    }

    /** Under the amount numbers: a relative score, not millilitres (the issue's wording). */
    public static final String PERIOD_AMOUNT = "Light 1, medium 2, heavy 3 for each logged day, added up for each period. "
            + "A relative number from what you logged, not a measured amount, so it only compares your own periods.";

    /** Under the cycle length dots ({@code cycles}) or the period length and amount dots. */
    public static String periodDots(boolean cycles) {
        if (cycles) return "Each dot is one cycle at the day it started, touch it to read. The line is your typical length, the band the "
                + "range the estimate gives. A faded dot is left out of the estimate.";
        return "Each dot is one period at the day it started, touch it to read. The line is the average.";
    }

    /** Under the night temperature line (the plan's text, with the marked rise of phase 2). */
    public static final String PERIOD_TEMP = "Your ring's nightly skin temperature compared with your usual. In many people it rises a little "
            + "after ovulation and stays higher until the next period. Where the app found that rise, the dashed line is the baseline "
            + "(the highest of the 6 nights before), the ringed dots are the high nights and the thin line the likely ovulation, the day "
            + "before the first high night. Red bands are period days, green the ovulation range of the estimate. Nights you left out are "
            + "not shown, and illness, alcohol, travel or a warm room move it too. Touch the line to read a night.";

    // ------------------------------------------ period tracker, ovulation from the temperature (issue #252, phase 2)

    /**
     * How the rise is found, with the third night's height for the ring's own noise ({@code ringNoise}, °C) and, when there
     * are temperatures by mouth, for theirs ({@code mouthNoise}, NaN without).
     */
    public static String periodRise(double ringNoise, double mouthNoise) {
        java.util.Locale l = java.util.Locale.getDefault();
        String third = String.format(l, "%.2f °C for your ring", app.openring.core.analysis.TempRise.thirdNeed(ringNoise));
        if (!Double.isNaN(mouthNoise)) third += String.format(l, ", %.2f °C by mouth", app.openring.core.analysis.TempRise.thirdNeed(mouthNoise));
        return "Three over six, the classic rule: the baseline is the highest of 6 nights, then 3 nights in a row must lie above it, "
                + "the third at least " + third + " (0.2 °C for a steady signal, less for one that varies more from night to night: yours "
                + "varies by about " + String.format(l, "%.2f", ringNoise) + " °C). A night may be missing, and one night that dips or a third "
                + "that falls short needs a fourth high night. The rise must last until the next period, and nights far from the ones around "
                + "them (a fever, a warm room) are left out. The likely ovulation is the day before the first high night. The ring and the "
                + "temperatures by mouth are checked apart; when both find it within 2 days, it is clear. In made up cycles with a rise of "
                + "0.3 °C and night to night noise of 0.1 °C, the rule found 7 in 10 rises and a clear one was within 1 day of the real "
                + "ovulation 4 times in 5. With noise of 0.2 °C it finds about a third. Illness, alcohol, travel and a warm room move the "
                + "temperature too. An estimate, not birth control and not a medical diagnosis.";
    }

    /** Under "Found in 4 of 6 cycles". */
    public static String periodFound(int over, int found) {
        return "Cycles that are over and had a lasting rise, out of " + (over == 1 ? "your 1 cycle" : "your " + over + " cycles")
                + " that are over. Not every cycle shows one: too few nights with the ring, a small rise, or a cycle without ovulation.";
    }

    /** Under the own luteal phase: from how many cycles, how much it varies, and what it changes. */
    public static String periodLuteal(int n, int days, double sd) {
        if (n < app.openring.core.analysis.TempRise.LUTEAL_FROM) {
            return "The days from ovulation to the next period, from cycles with a rise. Until " + app.openring.core.analysis.TempRise.LUTEAL_FROM
                    + " cycles have one (" + n + " so far), the estimate uses the usual " + app.openring.core.analysis.Cycles.LUTEAL + " days.";
        }
        return "The days from ovulation to the next period: the middle value of your " + n + " newest cycles with a rise, which vary by about "
                + String.format(java.util.Locale.getDefault(), "%.1f", Double.isNaN(sd) ? 0 : sd) + " days. It replaces the usual "
                + app.openring.core.analysis.Cycles.LUTEAL + " days for your ovulation estimate, and once this cycle's rise shows, for your next period.";
    }

    /** Under the ovulation line, for whichever way the estimate was made. */
    public static String periodOvulation(app.openring.core.analysis.Cycles cy) {
        if (cy.method == app.openring.core.analysis.Cycles.MEASURED) {
            boolean clear = cy.rise.current.confidence == app.openring.core.analysis.TempRise.CLEAR;
            return "Give or take " + cy.ovulationRange + (cy.ovulationRange == 1 ? " day" : " days") + ": " + (clear ? "a clear rise" : "a weaker rise")
                    + ", found within that many days of the real day about 4 times in 5 in made up cycles. Measured means the day before the "
                    + "temperature rose, not a test. How it is found is under Your cycles.";
        }
        if (cy.method == app.openring.core.analysis.Cycles.OWN_LUTEAL) {
            return "Give or take " + cy.ovulationRange + " days: how much " + yourCycles(cy.used.size()) + " vary, together with how much your own "
                    + "time from ovulation to the period varies, times 1.28 for about 4 in 5. At least 3 days.";
        }
        return periodOvulation(cy.used.size(), cy.ovulationRange);
    }

    /** Under "Expected ...", for whichever way the estimate was made. */
    public static String periodRange(app.openring.core.analysis.Cycles cy) {
        if (cy.method != app.openring.core.analysis.Cycles.MEASURED) return periodRange(cy.used.size(), cy.length, cy.range, cy.sd);
        boolean own = cy.rise.ownLuteal();
        return "Measured: the ovulation day plus " + cy.luteal + " days, " + (own ? "your own time from ovulation to the period" : "the usual time from ovulation to the period")
                + ". Give or take " + cy.range + (cy.range == 1 ? " day" : " days") + ": how much " + (own ? "your own time varies" : "that time varies (about 2.4 days in a large study)")
                + " together with the error of the measured day, times 1.28 for about 4 in 5. The calendar estimate from your typical cycle is shown for comparison.";
    }

    /** Settings, under "Remind me before my period". */
    public static final String PERIOD_REMIND = "A notification on this phone before the earliest day of the expected range, at the time you pick. "
            + "It moves when the estimate changes and comes once per period, not while a period is logged. Android may deliver it a few "
            + "minutes late. It always follows your own log, never the demo.";

    /** Settings, under "My periods have stopped". */
    public static final String PERIOD_STOPPED = "For example after menopause, during pregnancy or breastfeeding, or with some medicines. "
            + "The tracker then shows no next period and no late line, and sends no reminders. Your logged days and past cycles stay. "
            + "Logging a period day turns this off by itself.";

    // -------------------------------------------- period tracker, import from drip (issue #252, phase 1b)

    /** Settings, under "Import from drip". */
    public static final String DRIP_SETTINGS = "Reads the file drip exports (in drip: Settings, Export data). You see what it holds "
            + "before anything is saved, and you can undo an import as a whole. Nothing leaves the phone.";

    /** The import screen's first lines. */
    public static final String DRIP_INTRO = "Bleeding days, spotting and morning temperatures from drip. Days you already logged in "
            + "Open Ring are kept. Nothing is saved until you tap Import.";

    /** Demo mode: the made up file, and that nothing is imported. */
    public static final String DRIP_DEMO = "Demo mode: this is a made up drip file, compared with the demo's made up log. "
            + "Nothing is imported in demo mode.";

    /** Under the file's numbers. */
    public static final String DRIP_COUNTS = "Periods are counted the way Open Ring counts them: light, medium or heavy days with at most "
            + "2 days without an entry between them. Spotting is counted apart and never starts a period. Temperatures are drip's "
            + "morning temperatures by mouth, at most one a day, in °C.";

    /** Under "Not imported: 40 mood entries". */
    public static final String DRIP_OTHER = "Rows of each kind with an entry. They are counted so you know what the file holds, but the "
            + "period log does not keep them.";

    /** Under the flagged short periods. */
    public static final String DRIP_SHORT = "A period of 1 or 2 days is often a day that was not logged, or spotting logged as bleeding. "
            + "Correct opens those days so you can add the missing ones or join the period with a close one. Ignore keeps it as it is.";

    /** Under the days that differ. */
    public static final String DRIP_DIFFER = "Days with another amount in Open Ring keep Open Ring's entry. Switch this on to take the "
            + "file's values instead; undoing the import puts Open Ring's back. Temperatures already there are never replaced.";

    /** The Correct screen. */
    public static final String DRIP_CORRECT = "Tap a day to add it or change it. These changes are for this import only, nothing is saved "
            + "until you tap Import.";

    /** Settings, under each "Undo the drip import of 30 Oct". */
    public static final String DRIP_UNDO = "Undo removes exactly what this import added and puts back the days it replaced. Days you "
            + "logged or changed in Open Ring since then stay.";

    /** "Added 186 days and 312 temperatures." for Settings. */
    public static String dripAdded(int days, int temps) {
        return "Added " + days + (days == 1 ? " day" : " days") + " and " + temps + (temps == 1 ? " temperature." : " temperatures.");
    }

    /** Under the night temperature line when drip temperatures are on it. */
    public static final String PERIOD_MOUTH = "Mouth: the morning temperatures imported from drip, in °C on the right scale. They are a "
            + "different measurement from the ring's skin temperature at night, so each line has its own scale. A hollow dot is a "
            + "reading drip left out.";

    // ---------------------------------------------------------------- badges (issue #265)

    /** What every badge has in common: own history only, left out days, when a day or night gets its badges. */
    public static final String BADGES = "Badges compare a day or night only with your own history, never with a made up target. "
            + "A day without a badge says nothing. Days with a left out time count for no badge and in no average. "
            + "A day gets its badges once it has ended, a night once it is analysed. A tie never earns a badge.";

    // the badge screens (issue #265, phase 1b)
    public static final String BADGES_ROW = "Recent badges";
    public static final String BADGES_ROW_NOTE = "Good days and nights of the last " + Badges.WEEK + " days, compared with your own usual. "
            + "Touch one to read it.";
    public static final String BADGES_OPEN = "Open that day";
    public static final String BADGES_NEW = "New";
    public static final String BADGES_ALL = "All badges";
    public static final String BADGES_CALENDAR = "A dot marks a day with at least one badge, a bigger dot two or more. "
            + "Touch and slide across the days to read them, tap a day to open it.";
    public static final String BADGES_COUNT_NOTE = "The number counts the days with this badge in all your history. It is not a score to beat: "
            + "a day without a badge says nothing.";
    public static final String BADGES_SCREEN_ACCESS = "Phone free evening and Calm screen day come from your screen time. For that, switch on "
            + "Open Ring in Android's usage access list (the button takes you there). It stays on this phone.";
    public static final String BADGES_SCREEN_OLD = "Phone free evening and Calm screen day come from your screen time, which needs Android 9 or newer.";
    public static final String SHOW_BADGES = "Show badges";
    public static final String SHOW_BADGES_NOTE = "Shows Recent badges on Today. Badges are worked out from your data each time, "
            + "so switching this off deletes nothing.";
    public static final String BADGES_FAILED = "The badges could not be read this time. Your data is as before. Diagnostics, Log tab, shows why.";

    /** Under 14 nights: most comparisons still wait. Null from then on. */
    public static String badgesWait(int nights) {
        if (nights >= Badges.MIN_NIGHTS) return null;
        return "Most badges compare with your own last " + Badges.MIN_DAYS / 7 + " to " + Badges.WEEKS + " weeks, so they come after about " + Badges.MIN_NIGHTS
                + " nights with the ring. You have " + (nights == 1 ? "1 night" : nights + " nights") + " so far.";
    }

    /** "Today", "Yesterday", "Sat 31 Oct" (with the year when it is not this year). */
    public static String badgeWhen(java.time.LocalDate d, java.time.LocalDate today) {
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + Badges.date(d, today);
    }

    /** A kind's line on the Badges page: "Not yet", "1 time, last on 31 Oct", "3 times, last on 31 Oct". */
    public static String badgeCount(int n, java.time.LocalDate last, java.time.LocalDate today) {
        if (n == 0 || last == null) return "Not yet";
        return (n == 1 ? "1 time" : n + " times") + ", last on " + Badges.date(last, today);
    }

    /** What touching a day of the badge calendar shows: the date, then each badge with its numbers, or that it has none. */
    public static String badgeDay(java.time.LocalDate d, java.util.List<Badges.Badge> on, java.time.LocalDate today) {
        StringBuilder sb = new StringBuilder(badgeWhen(d, today));
        if (on.isEmpty()) return sb.append(": no badge").toString();
        for (Badges.Badge b : on) sb.append('\n').append(Badges.NAMES[b.kind]).append(": ").append(b.text);
        return sb.toString();
    }

    /** {@link #badge} without the name in front, for under the badge's own title: "Your highest sleep score ...". */
    public static String badgeRule(int kind) {
        String line = badge(kind);
        if (line == null) return null;
        String rule = line.substring(Badges.NAMES[kind].length() + 2);
        return Character.toUpperCase(rule.charAt(0)) + rule.substring(1);
    }

    /** One line per badge rule ({@code Badges.BEST_NIGHT} and so on), with the numbers the rule uses. */
    public static String badge(int kind) {
        String week = Badges.WEEK + " days", weeks = Badges.WEEKS + " weeks";
        switch (kind) {
            case Badges.BEST_NIGHT:
                return "Best night: your highest sleep score of the last " + Badges.BEST_DAYS + " days, with at least "
                        + Badges.MIN_NIGHTS + " nights in them. Equal to an earlier night does not count.";
            case Badges.RESTFUL_WEEK:
                return "Restful week: your sleep score over the last " + week + " is on average at least " + Badges.RESTFUL_GAP
                        + " points above your usual (the " + weeks + " before). Needs " + Badges.RESTFUL_WEEK_NIGHTS + " nights in the week and "
                        + Badges.MIN_NIGHTS + " in the " + weeks + ", and comes at most once in " + week + ".";
            case Badges.RECOVERED:
                return "Recovered: readiness back to your usual (its average over the " + weeks + " before the low days) after at least "
                        + Badges.LOW_DAYS + " days in a row clearly below it (lower than the average minus its usual day to day spread, "
                        + "one standard deviation). Needs readiness on each of these days and on " + Badges.MIN_DAYS + " days of the " + weeks + ".";
            case Badges.PHONE_FREE:
                return "Phone free evening: at most " + Badges.PHONE_FREE_MAX + " min on the phone in the hour before your night started "
                        + "(a look at the clock is fine). Comes for " + Badges.PHONE_FREE_OF + " such evenings in " + week + ", at most once in "
                        + week + ", or for the first one after " + Badges.WEEK + " evenings with more. "
                        + "Reading in bed moves the start of your night to after it, so it does not count as phone free.";
            case Badges.ABOVE_USUAL:
                return "Above your usual: at least " + Math.round(Badges.STEPS_ABOVE * 100) + "% more steps than your average on your last "
                        + Badges.USUAL_DAYS + " days with the ring (at least " + Badges.MIN_DAYS + " of them). Only days with the ring on for "
                        + Badges.WORN_MIN / 60 + " hours or more count, sleep included.";
            case Badges.GOAL:
                return "Goal reached: your daily activity goal reached on " + Badges.GOAL_OF + " of the last " + week + " (at most once in "
                        + week + "), or the first goal day after " + week + " without one. There is no badge for every single day, "
                        + "rest days matter too. Without your own goal in the profile the app lowers it after a low readiness day.";
            case Badges.CALM_SCREEN:
                return "Calm screen day: at least " + Math.round(Badges.CALM_UNDER * 100) + "% less screen time than your usual (your average "
                        + "over the last " + weeks + ", at least " + Badges.MIN_DAYS + " fully read days). Counted as on the Screen tab: apps "
                        + "you left out do not count, and only days whose phone log was read completely.";
            default:
                return null;
        }
    }

    public static String of(String key) {
        if (key == null) return null;
        switch (key) {
            // readiness
            case "rhr": return "Your lowest heart rate last night compared with your usual. Higher than usual often follows stress, alcohol, a late meal, hard training or a coming illness.";
            case "hrv": return "Heart rate variability over the last two weeks compared with your long term average. More variability means a calmer, better recovered nervous system.";
            case "temperature": return "How far last night's skin temperature was from your normal. Bigger shifts can come with illness, the menstrual cycle, alcohol or a warm bedroom.";
            case "recovery": return "When your heart rate reached its lowest point in the night. Earlier is better; a late low means your body was still busy recovering from the evening.";
            case "sleep": return "Last night's sleep score.";
            case "sleep_balance": return "Your sleep over the last two weeks compared with your sleep need. Short nights add up.";
            case "prev_activity": return "Yesterday's activity compared with your goal. Both very little and a lot of activity lower readiness.";
            case "activity_balance": return "Your last week of activity compared with your usual month, so sudden jumps in training load show up.";
            // sleep
            case "total": return "Time actually asleep. Most adults need 7 to 9 hours.";
            case "efficiency": return "Share of your time in bed spent asleep. 85% or more is good. Time without a sleep stage counts as time in bed, not as sleep.";
            case "restfulness": return "Wake ups, time awake and restless movement during the night.";
            case "rem": return "Dreaming sleep, important for memory, learning and mood. Usually about a fifth of sleep.";
            case "deep": return "The most restorative stage, mostly in the first half of the night. Usually 13 to 23% of sleep.";
            case "latency": return "How long it took to fall asleep. 10 to 20 minutes is ideal; falling asleep instantly can mean sleep debt.";
            case "timing": return "Whether the middle of your sleep falls around 2 to 4 a.m., in step with your body clock.";
            // activity
            case "stay_active": return "Hours spent sitting or inactive while awake.";
            case "move_hourly": return "Times you sat still for an hour or more between 08:00 and 22:00. Only the minutes in that window count, sleep never does.";
            case "targets": return "How often you reached your daily activity goal in the last 7 days. Days without the ring are left out.";
            case "frequency": return "Days in the last 7 with at least 30 minutes of medium or high activity. With fewer than 7 days of wear, it is scaled up to a week.";
            case "volume": return "Energy burned in medium and high activity in the last 7 days. With fewer than 7 days of wear, it is scaled up to a week.";
            default: return null;
        }
    }

    // ---- the Withings watch (issue #251, phase 1b) ----

    public static final String WATCH_ADD = "Withings Steel HR: Settings, Watch, Set up the watch. "
            + "Open Ring then reads steps, heart rate, sleep, workouts and battery from it, without the Withings account.";

    public static final String WATCH_WITHINGS_APP = "Open Ring sets up and syncs the watch itself, so use only one app with it: the Withings app "
            + "would send its own settings and take the watch's data first. Do not remove the watch in Android's Bluetooth settings: pairing it again may need a reset that erases the watch. "
            + "If a sync fails, force stop the Withings app and try again.";

    // ---- the watch setup (issue #251) ----

    public static final String WATCH_SETUP = "Open Ring sets up your Withings watch itself, without the Withings app: it finds and pairs the watch, "
            + "tells it who wears it and your settings, then syncs. Each step shows how it went; a failed one can be tried again.";

    public static final String WATCH_SETUP_SENT = "The watch uses your height, weight, sex and age to count distance and calories. "
            + "It is sent again at every sync, so a change in your profile reaches the watch. Nothing leaves the phone except to the watch.";

    public static final String WATCH_ALARM = "One silent alarm: the watch vibrates at this time on the chosen days. Smart wake lets it wake you a little "
            + "earlier when you sleep lightly. A change goes to the watch with Save on the watch or at the next sync.";

    public static final String WATCH_HANDS = "If the hands do not show the right time, set them here. The watch moves its hands to 12 o'clock, you move "
            + "each one until it points exactly there, and Done puts the phone's time back on the watch. Keep the watch next to the phone.";

    public static final String WATCH_CALORIES = "Calories the watch counted for your movement on each day, from your steps and profile. "
            + "It does not count the calories your body uses at rest.";

    public static final String WATCH_CLIMB = "Metres the watch counted as climbed on this day, from its own estimate.";

    public static final String WATCH_FORGET = "Open Ring stops syncing it. What it synced stays on this phone. Nothing changes on the watch or in the Withings app.";

    public static final String WATCH_PAGE = "What your watch measured, as it sent it. Our own analysis of the watch's data comes in a later version.";

    public static final String WATCH_STEPS = "Steps the watch counted on each day, from midnight to midnight. Steps of a workout are listed with the workout "
            + "and are not in this number, so compare it with the Withings app.";

    public static final String WATCH_WORKOUTS = "Workouts the watch recorded on this day: the type, when it started, how long it lasted, and the distance, steps "
            + "and calories the watch counted for it.";

    public static final String WATCH_HEART = "Heart rate as the watch stored it on this day. Outside workouts the watch measures about every 30 minutes, "
            + "in a workout more often. Touch the chart to read a time and its value.";

    public static final String WATCH_SLEEP = "Withings' own sleep stages for the night that ended on this day, as the watch worked them out. "
            + "Time asleep adds light, deep and REM. Parts with a code we do not know yet show as no data.";

    public static final String WATCH_BATTERY = "The watch's battery at the last sync of each day. A jump up is a charge.";

    public static final String WATCH_DROP = "How much the battery fell per day over the last 7 days, charges left out. "
            + "It shows what the syncs and your use cost; more syncs a day cost more.";

    // ---- the main device (issue #251, one set of numbers) ----

    public static final String MAIN_DEVICE = "Your numbers come from the main device. When it was not worn (charging, off your hand), "
            + "the other one fills that time, and the screen says so under the number. Switching changes only what is shown, nothing stored.";

    public static final String MAIN_FILLED = "Not worn means no movement and no heart rate from the main device in a quarter hour. "
            + "Readiness, the sleep score, burn and movement minutes stay the ring's. Settings, Watch, Open the watch page shows everything the watch sent.";

    public static final String MAIN_WATCH_NIGHT = "Stages from Withings' own analysis on the watch, not from this app. "
            + "The sleep score needs the ring's night, so there is none here.";
}
