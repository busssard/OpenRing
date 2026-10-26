package app.openring.ui;

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
            case "efficiency": return "Share of your time in bed spent asleep. 85% or more is good.";
            case "restfulness": return "Wake ups, time awake and restless movement during the night.";
            case "rem": return "Dreaming sleep, important for memory, learning and mood. Usually about a fifth of sleep.";
            case "deep": return "The most restorative stage, mostly in the first half of the night. Usually 13 to 23% of sleep.";
            case "latency": return "How long it took to fall asleep. 10 to 20 minutes is ideal; falling asleep instantly can mean sleep debt.";
            case "timing": return "Whether the middle of your sleep falls around 2 to 4 a.m., in step with your body clock.";
            // activity
            case "stay_active": return "Hours spent sitting or inactive while awake.";
            case "move_hourly": return "Long sitting periods of an hour or more during the day.";
            case "targets": return "How often you reached your daily activity goal in the last 7 days. Days without the ring are left out.";
            case "frequency": return "Days in the last 7 with at least 30 minutes of medium or high activity. With fewer than 7 days of wear, it is scaled up to a week.";
            case "volume": return "Energy burned in medium and high activity in the last 7 days. With fewer than 7 days of wear, it is scaled up to a week.";
            default: return null;
        }
    }
}
