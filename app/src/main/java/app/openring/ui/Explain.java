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

    /** The note above the readiness contributors when the day before was not worn, so Previous day activity is left out (issue #240). */
    public static final String READINESS_UNWORN = "Previous day activity is left out: the ring was on for less than an hour "
            + "the day before, so there is no activity to compare with your goal.";

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
        String from = cycles == 0 ? "Next period: typical cycle " + length + " days, the average in a large study of real cycles, until you have your own. "
                : cycles < app.openring.core.analysis.Cycles.TRIM_FROM ? "Next period: typical cycle " + length + " days, the middle value of " + yourCycles(cycles) + ". "
                : "Next period: typical cycle " + length + " days, the average of " + yourCycles(cycles) + " without the shortest and the longest. ";
        if (cycles < app.openring.core.analysis.Cycles.FEW) return from + "The next start is your last start plus that, give or take " + range + " days while there are fewer than "
                + app.openring.core.analysis.Cycles.FEW + " cycles.";
        return from + "About 4 in 5: the range is give or take 1.28 times how much your cycles vary (" + String.format(java.util.Locale.getDefault(), "%.1f", sd)
                + " days), which holds about 4 in 5 starts when your cycles vary as before. At least 1 day.";
    }

    /** Under the ovulation line: how "give or take N days" is worked out. */
    public static String periodOvulation(int cycles, int range) {
        String spread = cycles < app.openring.core.analysis.Cycles.FEW ? "a typical spread of 2.6 days (in place of your own until you have "
                + app.openring.core.analysis.Cycles.FEW + " cycles)" : "how much " + yourCycles(cycles) + " vary";
        return "Ovulation day: give or take " + range + " days, from " + spread + ", together with how much the time from ovulation to the next period varies "
                + "(about 2.4 days in a large study), times 1.28 for about 4 in 5. At least 3 days.";
    }

    /** How it works on the Period card: why one day is the most likely and the days around it less so. */
    public static final String PERIOD_LIKELY = "Most likely day: the expected day itself. The days around it are less likely the further "
            + "they lie from it, as in a normal spread, so the calendar shades the range darkest on that day and marks it with a "
            + "dashed ring. The same for the ovulation day.";

    /**
     * How it works on the Period card: the months ahead, with the ranges of the next cycle, six and twelve cycles ahead
     * ({@code Cycles.ahead}), worked out from the person's own numbers.
     */
    public static String periodAhead(app.openring.core.analysis.Cycles cy) {
        java.util.List<app.openring.core.analysis.Cycles.Ahead> a = cy.ahead(cy.today.plusDays(13L * Math.max(1, cy.step)));
        String r1 = a.get(0).range + (a.get(0).range == 1 ? " day" : " days");
        String more = a.size() >= 12 ? ", " + a.get(5).range + " six cycles ahead and " + a.get(11).range + " twelve cycles ahead" : "";
        return "Months ahead: each later period is one expected cycle (" + cy.step + " days) after the one before, counted from today "
                + "while the next one is due. Its range grows with the square root of how many cycles ahead it is, from the same "
                + "spread, because the variation of each cycle adds up: give or take " + r1 + " for the next" + more
                + ". Page with the arrows above the calendar.";
    }

    /** Under "Your cycles vary by about N days". */
    public static String periodVary(int cycles) {
        return "Vary by: the standard deviation of " + yourCycles(cycles) + ", how far a cycle usually lies from their average. "
                + "Cycles far from the rest (more than 10 days from the middle value) are left out, maybe a period was not logged, unless half of them or more are.";
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

    /**
     * Under the cycle length numbers, in view: what they count, from how many cycles, what Spread is, and how many are left
     * out (about the person's own numbers, so not hidden). How the estimate uses them is in {@link #periodCyclesHow}.
     */
    public static String periodCycles(int cycles, int leftOut) {
        String s = "From one period start to the next, over " + (cycles == 1 ? "your 1 cycle" : "all your " + cycles + " cycles") + ". "
                + "Spread: how far a cycle usually lies from the average.";
        if (leftOut > 0) s += " " + (leftOut == 1 ? "1 cycle" : leftOut + " cycles") + " more than 10 days from the middle value "
                + (leftOut == 1 ? "is" : "are") + " left out, maybe a period was not logged.";
        return s;
    }

    /** Under the period length numbers, in view. */
    public static String periodLengths(int periods) {
        return "From the first to the last bleeding day of each period, both counted, over " + (periods == 1 ? "your 1 period" : "your " + periods + " periods")
                + " that are over.";
    }

    /** Under the amount numbers: a relative score, not millilitres (the issue's wording). */
    public static final String PERIOD_AMOUNT = "Light 1, medium 2, heavy 3 for each logged day, added up for each period. "
            + "A relative number from what you logged, not a measured amount, so it only compares your own periods.";

    /** Under the cycle length dots ({@code cycles}) or the period length and amount dots, in view. */
    public static String periodDots(boolean cycles) {
        return cycles ? "Each dot is one cycle, touch it to read." : "Each dot is one period, touch it to read.";
    }

    /** Under the night temperature line, in view; the rest is in its How it works ({@link #periodTempHow}). */
    public static final String PERIOD_TEMP_LINE = "Each night's skin temperature compared with your usual, your cycle behind it. "
            + "Touch to read a day and its phase.";

    /** Under the night temperature line (the plan's text, with the marked rise of phase 2). */
    public static final String PERIOD_TEMP = "Your ring's nightly skin temperature compared with your usual. In many people it rises a little "
            + "after ovulation and stays higher until the next period. Where the app found that rise, the dashed line is the baseline "
            + "(the highest of the 6 nights before), the ringed dots are the high nights and the thin line the likely ovulation, the day "
            + "before the first high night. Red bands are period days, green the ovulation range of the estimate and light red the "
            + "expected period, both darkest on their most likely day; the dashed red line is the expected first day and the green "
            + "bar at the bottom the likely fertile days. The chart goes on to the end of the expected period, a thin line marks "
            + "today. Nights you left out are not shown, and illness, alcohol, travel or a warm room move it too.";

    // ------------------------------------------ period tracker, ovulation from the temperature (issue #252, phase 2)

    /**
     * How the rise is found, with the third night's height for the ring's own noise ({@code ringNoise}, °C) and, when there
     * are temperatures by mouth, for theirs ({@code mouthNoise}, NaN without).
     */
    public static String periodRise(double ringNoise, double mouthNoise) {
        java.util.Locale l = java.util.Locale.getDefault();
        String third = String.format(l, "%.2f °C for your ring", app.openring.core.analysis.TempRise.thirdNeed(ringNoise));
        if (!Double.isNaN(mouthNoise)) third += String.format(l, ", %.2f °C by mouth", app.openring.core.analysis.TempRise.thirdNeed(mouthNoise));
        return "Ovulation from your temperature: three over six, the classic rule. The baseline is the highest of 6 nights, then 3 nights in a row must lie above it, "
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
            return "Ovulation day: give or take " + cy.ovulationRange + (cy.ovulationRange == 1 ? " day" : " days") + ", from " + (clear ? "a clear rise" : "a weaker rise")
                    + ", found within that many days of the real day about 4 times in 5 in made up cycles. Measured means the day before the "
                    + "temperature rose, not a test. How it is found is under How it works in Your cycles.";
        }
        if (cy.method == app.openring.core.analysis.Cycles.OWN_LUTEAL) {
            return "Ovulation day: give or take " + cy.ovulationRange + " days, from how much " + yourCycles(cy.used.size()) + " vary, together with how much your own "
                    + "time from ovulation to the period varies, times 1.28 for about 4 in 5. At least 3 days.";
        }
        if (cy.methodId == app.openring.core.analysis.cycleforecast.Methods.DRIP && !cy.forecast.fallback) {
            return "Ovulation day: give or take " + cy.ovulationRange + " days, from drip style's range for the period (" + cy.range + (cy.range == 1 ? " day" : " days")
                    + " each side) together with how much the time from ovulation to the next period varies (about 2.4 days in a large study). At least 3 days.";
        }
        return periodOvulation(cy.used.size(), cy.ovulationRange);
    }

    /** Under "Expected ...", for whichever way the estimate was made. */
    public static String periodRange(app.openring.core.analysis.Cycles cy) {
        if (cy.method != app.openring.core.analysis.Cycles.MEASURED && cy.methodId != app.openring.core.analysis.cycleforecast.Methods.OPEN_RING) return periodMethodRange(cy);
        if (cy.method != app.openring.core.analysis.Cycles.MEASURED) return periodRange(cy.used.size(), cy.length, cy.range, cy.sd);
        boolean own = cy.rise.ownLuteal();
        return "Next period: measured, the ovulation day plus " + cy.luteal + " days, " + (own ? "your own time from ovulation to the period" : "the usual time from ovulation to the period")
                + ". Give or take " + cy.range + (cy.range == 1 ? " day" : " days") + ": how much " + (own ? "your own time varies" : "that time varies (about 2.4 days in a large study)")
                + " together with the error of the measured day, times 1.28 for about 4 in 5. The calendar estimate from your typical cycle is shown for comparison.";
    }

    // ------------------------------------------------------------- period prediction methods (issue #383)

    /** Settings, under Prediction. */
    public static final String PERIOD_METHOD_SETTINGS = "How the next period and the months ahead are worked out. Only how it is shown: "
            + "your log stays as it is. How close each method was on your own cycles is on Body, card How close the estimates were.";

    /**
     * Under "Expected ..." for a method other than Open Ring: the last start plus its expected length, its range, and how
     * it did on the person's own cycles.
     */
    public static String periodMethodRange(app.openring.core.analysis.Cycles cy) {
        StringBuilder sb = new StringBuilder("Next period: your last start plus ").append(cy.step).append(" days from ")
                .append(app.openring.core.analysis.cycleforecast.Methods.name(cy.methodId)).append(cy.choice == app.openring.core.analysis.cycleforecast.Methods.BEST ? " (picked for you)" : "").append(". ");
        if (cy.forecast.fallback) sb.append("With few cycles of your own it uses Open Ring's rule for now. ");
        else sb.append("Give or take ").append(cy.range).append(cy.range == 1 ? " day: " : " days: ").append(app.openring.core.analysis.cycleforecast.Methods.rangeWords(cy.methodId)).append(". ");
        app.openring.core.analysis.cycleforecast.Backtest.Score sc = cy.backtest().of(cy.methodId);
        if (sc != null && sc.n > 0) sb.append(backtestWords(sc));
        return sb.toString().trim();
    }

    /** "On 33 of your past periods it was 1.3 days off on average, and the start fell in its range 31 times." */
    static String backtestWords(app.openring.core.analysis.cycleforecast.Backtest.Score sc) {
        return "On " + yourPeriods(sc.n) + " it was " + fmt1(sc.mae) + " days off on average, and the start fell in its range " + times(sc.hits) + ".";
    }

    static String yourPeriods(int n) {
        return n == 1 ? "1 of your past periods" : n + " of your past periods";
    }

    static String times(int n) {
        return n == 1 ? "once" : n + " times";
    }

    static String fmt1(double v) {
        return String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    /** Settings, under the choices: which method Best for you uses now and why (from the backtest of the open log). */
    public static String periodBestNow(app.openring.core.analysis.Cycles cy) {
        app.openring.core.analysis.cycleforecast.Backtest bt = cy.backtest();
        int best = bt.best();
        String s;
        if (bt.scored() < app.openring.core.analysis.cycleforecast.Backtest.MIN_SCORED) {
            s = "Best for you uses Open Ring until " + app.openring.core.analysis.cycleforecast.Backtest.MIN_SCORED
                    + " of your periods can be checked (from the 5th period on; now " + bt.scored() + ").";
        } else {
            app.openring.core.analysis.cycleforecast.Backtest.Score b = bt.of(best), o = bt.of(app.openring.core.analysis.cycleforecast.Methods.OPEN_RING);
            s = "Best for you uses " + app.openring.core.analysis.cycleforecast.Methods.name(best) + " now: " + fmt1(b.mae) + " days off on average over " + yourPeriods(b.n)
                    + (best == app.openring.core.analysis.cycleforecast.Methods.OPEN_RING ? "." : ", Open Ring " + fmt1(o.mae) + ".");
        }
        if (cy.rise != null && !cy.riseUsed && app.openring.core.analysis.cycleforecast.Methods.usesTemperature(cy.methodId)) s += " " + cy.riseOff();
        return s;
    }

    /** Under the rows of "How close the estimates were". */
    public static final String PERIOD_ACC_ROWS = "Days off: how far each method's expected day was from the day you logged, on average. "
            + "In range: how often the start fell inside the range it showed. A wider range is hit more often, so read both.";

    /** Under the chart of each period's error. */
    public static final String PERIOD_ACC_DOTS = "Each dot is one period: above the line it came later than this method expected, below earlier. "
            + "The band is its average range. Touch a dot to read it.";

    /** Under the temperature numbers. */
    public static String periodAccTemp(app.openring.core.analysis.RiseBacktest rb) {
        return "From the first day the rise showed in each of " + (rb.n() == 1 ? "1 past cycle" : rb.n() + " past cycles")
                + ", compared with the calendar on that same day. In range: temperature " + rb.hits + ", calendar " + rb.calendarHits + " of " + rb.n() + ".";
    }

    /** How it works of "How close the estimates were": the test, the pick, each method, the temperature, the research. */
    public static java.util.List<String> periodAccuracyHow() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add("How it is tested: for each of your periods from the 5th on, every method gets only the cycles before it, as if it were "
                + "that day, and says when the period will start. The difference to the day you logged is its error. Nothing after that "
                + "day is used. A cycle more than 10 days from the middle value of the ones before it (probably a period that was not "
                + "logged) is not counted, every method misses those.");
        out.add("Best for you: the method with the smallest average error, on a tie Open Ring. Until " + app.openring.core.analysis.cycleforecast.Backtest.MIN_SCORED
                + " periods can be checked it stays with Open Ring. A method ahead by a fraction of a day may just have been lucky, so look at "
                + "the numbers too. You can pick another in Settings, Period tracker, Prediction.");
        for (int id : app.openring.core.analysis.cycleforecast.Methods.CHOICES) if (id != app.openring.core.analysis.cycleforecast.Methods.BEST) out.add(app.openring.core.analysis.cycleforecast.Methods.how(id));
        out.add("With your temperature: each past cycle with a rise is replayed with only the log and the temperatures up to the first day the "
                + "app would have shown the estimate from it (the ovulation day plus your own days to the period), and compared with the "
                + "calendar on that same day. It is used with every method but drip style. Best for you leaves it out only when at least "
                + app.openring.core.analysis.RiseBacktest.MIN + " cycles say it was further off.");
        out.add("From research: one person's cycles vary by about 2.6 days on average (Bull and others, 2019, 612,613 cycles), so even a perfect "
                + "method misses by about 2 days for most people. In large app data, neural networks were no better than the average of the "
                + "person's own cycles (Li, Urteaga and others, Clue data), and a temperature rise found ovulation within about 1.3 days, the "
                + "calendar 3.4 (an Oura study, 2025). With about 12 cycles a year, machine learning on one person mostly learns noise: a small "
                + "learned model (ridge regression on the last 3 cycles) was tried on made up histories and was never more than 0.03 days better "
                + "than Follows your last cycle, so it is not offered.");
        out.add(app.openring.core.analysis.Cycles.NOT_ADVICE);
        return out;
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

    // ------------------------------------- period tracker, "How it works" (issue #252, owner's feedback of 3 November)

    /** How it works, Your cycles: the dot charts' lines and bands. */
    public static final String PERIOD_DOTS_CYCLES = "Cycle length chart: each dot sits at the day its cycle started. The dashed line is the "
            + "length the estimate expects, the band the range it gives. A faded dot lies more than 10 days from the middle value.";
    /** How it works, Your cycles: the period length and amount charts. */
    public static final String PERIOD_DOTS_PERIODS = "Period length and amount charts: each dot sits at the day its period started, the dashed line is the average.";
    /** How it works, Your cycles: which cycles the estimate uses. */
    public static final String PERIOD_WINDOW = "The numbers here count all your cycles; the estimate on the Period card uses only your newest "
            + app.openring.core.analysis.Cycles.WINDOW + " cycles, so it follows how your cycles are now.";
    /** How it works, Your cycles: what a period's length counts. */
    public static final String PERIOD_LENGTH_DAYS = "Period length: days without a log inside a period count, spotting does not.";

    /**
     * The Period card's How it works: how the expected range, the ovulation range and "vary by" are worked out, each only
     * when its line is on the card. Empty when the card shows no estimate.
     */
    public static java.util.List<String> periodCardHow(app.openring.core.analysis.Cycles cy) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (cy == null) return out;
        if (cy.nextLine() != null) {
            out.add(periodRange(cy));
            out.add(periodOvulation(cy));
        }
        if (cy.regularityLine() != null) out.add(periodVary(cy.used.size()));
        if (cy.nextLine() != null) {
            // the most likely day and the months ahead (owner's feedback of 11 November)
            out.add(PERIOD_LIKELY);
            out.add(periodAhead(cy));
        }
        return out;
    }

    /** Your cycles' How it works: the texts for the parts the card shows, then that it is not birth control. Empty without a period. */
    public static java.util.List<String> periodCyclesHow(app.openring.core.analysis.Cycles cy) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (cy == null || cy.periods.isEmpty()) return out;
        if (cy.regularityLine() != null) out.add(periodVary(cy.used.size()));
        if (!cy.cycles.isEmpty()) {
            out.add(PERIOD_DOTS_CYCLES);
            if (cy.cycleSummary().n > 0) out.add(PERIOD_WINDOW);
        }
        if (cy.rise != null) {
            boolean mouth = false;
            for (app.openring.core.analysis.TempRise.Result r : cy.rise.cycles) mouth |= r.mouth.nights > 0;
            out.add(periodRise(cy.rise.ringNoise, mouth ? cy.rise.mouthNoise : Double.NaN));
        }
        if (!cy.finished().isEmpty()) {
            out.add(PERIOD_DOTS_PERIODS);
            out.add(PERIOD_LENGTH_DAYS);
        }
        out.add(app.openring.core.analysis.Cycles.NOT_ADVICE);
        return out;
    }

    /** The night temperature card's How it works; the drip line only when drip temperatures are on it. */
    public static java.util.List<String> periodTempHow(boolean mouth) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add(PERIOD_TEMP);
        if (mouth) out.add(PERIOD_MOUTH);
        return out;
    }

    /** Import from drip's How it works: how periods are counted, then the parts the screen shows. */
    public static java.util.List<String> dripHow(boolean other, boolean shorts) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add(DRIP_COUNTS);
        if (other) out.add(DRIP_OTHER);
        if (shorts) out.add(DRIP_SHORT);
        return out;
    }

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
    public static final String BADGES_CALENDAR = "Each badge of a day is a dot in its colour, the colour of its tab. "
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
                        + "one standard deviation), on the first day back, at most " + Badges.BACK_WITHIN + " days after the low days. Needs readiness on each "
                        + "of these days and on " + Badges.MIN_DAYS + " days of the " + weeks + ".";
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
            case "prev_activity": return "Yesterday's activity compared with your goal. Both very little and a lot of activity lower readiness. "
                + "A day with less than an hour of wear is left out.";
            case "activity_balance": return "Your last week of activity compared with your usual month, so sudden jumps in training load show up. "
                + "Days with less than an hour of wear are left out.";
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
            + "Time asleep adds light, deep and REM. Awake is time the watch marked as awake inside the night. "
            + "Time without a record from the watch, or with a code we do not know yet, shows as no data.";

    /** The watch night's No data row (owner's report of 11 November). */
    public static final String WATCH_NO_RECORD = "No data is time inside the night the watch sent no sleep record for. It is not counted as asleep or awake.";

    /** The watch night's heart rate under its stages (owner's wish of 11 November). */
    public static final String WATCH_NIGHT_HEART = "Heart rate: the watch's own readings between falling asleep and waking up. "
            + "The watch measures only every few minutes, so each dot is one reading; dots further apart than an hour are not joined. "
            + "Lowest is the lowest reading, average the average of the readings.";

    /** Activity's steps through the day from the watch when another device filled its gaps (issue #251). */
    public static final String WATCH_DAY_FILLED = "The steps another device added where the watch was not worn are in the day's total above, not in these bars.";

    /** The Watch page's steps per hour (issue #251). */
    public static final String WATCH_HOURS = "Steps per hour as the watch counted them; each record counts in the hour it started. Touch to see the hour and its steps.";

    /** The watch's distance (owner's report of 11 November: the watch sends centimetres). */
    public static final String WATCH_DISTANCE = "Distance is the watch's own estimate from your steps. It sends it in centimetres; shown here in km.";

    /** Sleep with only the watch and no night from it (issues #373, #379). */
    public static final String SLEEP_WATCH_ONLY = "With only the watch, Sleep shows Withings' own night: when you fell asleep and woke up, light, deep and REM "
            + "sleep, the heart rate through the night and a sleep score worked out from them. Wear the watch in bed and sync it in the "
            + "morning (Settings, Watch). HRV, blood oxygen and breathing need a ring.";

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

    // ---- scores without a ring (issue #379, core/DeviceScores) ----

    /** Under the watch's night when it has a score. */
    public static final String WATCH_NIGHT_SCORE = "Stages from Withings' own analysis on the watch. The sleep score is worked out from them on this phone "
            + "with the same formula as for the ring, see Contributors.";

    public static final String SLEEP_DEVICE = "The sleep score from your watch uses the ring's formula on the watch's night: total sleep, how much of "
            + "the night you were asleep, wake ups and time awake, deep and REM sleep, and timing. Parts the watch cannot give are left out "
            + "and the others count for more.";

    public static final String READINESS_DEVICE = "Readiness from your watch uses the ring's formula with the signals the watch has: your lowest "
            + "heart rate last night against your usual, last night's sleep score, your sleep over two weeks, and your steps yesterday and over "
            + "the last weeks. HRV and body temperature, the strongest signs of recovery, need a ring, so this readiness rests on fewer signals. "
            + "85 or more: ready for a demanding day. 70 to 84: good. Below 70: your body is asking for rest.";

    public static final String ACTIVITY_DEVICE = "The activity score from steps uses the ring's formula: how long you sat still, whether you moved "
            + "every hour, how often you reached your step goal and your training over the last week. Steps stand in for the ring's movement, "
            + "so standing or cycling without a workout counts as sitting. Days with less than an hour of data are left out.";

    /** The note above the device's activity contributors while fewer than 7 days of the last week have data. */
    public static String activityDaysDevice(int n, boolean watch) {
        String days = n == 1 ? "1 day" : n + " days";
        String rule = " with " + (watch ? "the watch" : "steps from this phone") + " in the last 7 days (a day counts with at least an hour of data). ";
        if (n >= app.openring.core.analysis.Scores.MIN_WEEK_DAYS) return "Based on " + days + rule + "Daily targets and training are steadier after a full week.";
        return "Only " + days + rule + "Daily targets and training count from " + app.openring.core.analysis.Scores.MIN_WEEK_DAYS + " days on.";
    }

    /**
     * A contributor's text for a score from the watch or the phone (issue #379), null where the ring's text ({@link #of}) fits
     * as it is.
     */
    public static String ofDevice(String key, boolean watch) {
        if (key == null) return null;
        String dev = watch ? "the watch was on" : "the phone counted";
        switch (key) {
            case "stay_active": return "Time awake while " + dev + ", in quarter hours with fewer than 60 steps. Each counts as 15 minutes sitting.";
            case "move_hourly": return "Times you took fewer than 60 steps per quarter hour for an hour or more between 08:00 and 22:00.";
            case "targets": return "How often you reached your step goal in the last 7 days. Days with less than an hour of data are left out.";
            case "frequency": return "Days in the last 7 with at least 30 brisk minutes: a quarter hour with 1,000 steps or more (100 steps a minute "
                + "is a brisk walk), or a workout the watch recorded.";
            case "volume": return "Calories the watch counted in brisk quarter hours and in its workouts over the last 7 days.";
            case "efficiency": return "Share of the watch's night spent asleep. Time without a record from the watch counts as in bed, not as sleep.";
            case "restfulness": return "Wake ups and time awake between falling asleep and waking up, from the watch's stages. Restless movement "
                + "is left out, the watch sends none.";
            case "rem": return "Dreaming sleep, from Withings' own stages. Usually about a fifth of sleep.";
            case "deep": return "The most restorative stage, from Withings' own stages. Usually 13 to 23% of sleep.";
            case "latency": return "Time awake at the start of the watch's night before the first sleep.";
            case "rhr": return "The lowest of the watch's heart rate readings last night against your usual over the last 14 watch nights. "
                + "The watch reads every 10 to 30 minutes, so the true lowest can be a little lower.";
            case "sleep": return "Last night's sleep score from the watch's night.";
            case "sleep_balance": return "Your sleep from the watch over the last two weeks compared with your sleep need.";
            case "prev_activity": return "Yesterday's steps compared with your step goal. Both very little and a lot lower readiness. "
                + "A day with less than an hour of data is left out.";
            case "activity_balance": return "Your steps over the last week compared with the last four weeks, so sudden jumps show up. "
                + "Days with less than an hour of data are left out.";
            default: return null;
        }
    }

    // ---- steps from this phone (issue #329, phase 1b) ----

    public static final String PHONE_STEPS = "Your phone counts steps in its own sensor. Open Ring reads the count about once an hour and stores the difference. "
            + "Nothing leaves the phone. On some phones the sensor only counts while an app such as the phone's own health app keeps it on. "
            + "If the phone restarts because the battery ran out, the steps since the last reading are lost.";
}
