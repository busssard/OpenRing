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
            + "reached your daily goal and your training over the last week. Balance matters: both too little and too much lower it.";

    public static final String STAGES = "Your sleep moves in cycles of about 90 minutes. Deep sleep (bottom) restores the body "
            + "and comes mostly early in the night, REM sleep (dreaming) grows towards the morning, short wake ups are normal.";

    public static final String NIGHT_SIGNALS = "Skin temperature, blood oxygen and breathing rate are measured while you sleep, "
            + "the same way the official app does it. They appear after the first night you wear the ring and sync in the morning.";

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
            case "rem": return "Dreaming sleep, important for memory, learning and mood. Usually about a fifth of the night.";
            case "deep": return "The most restorative stage, mostly in the first half of the night. Usually 13 to 23% of sleep.";
            case "latency": return "How long it took to fall asleep. 10 to 20 minutes is ideal; falling asleep instantly can mean sleep debt.";
            case "timing": return "Whether the middle of your sleep falls around 2 to 4 a.m., in step with your body clock.";
            // activity
            case "stay_active": return "Hours spent sitting or inactive while awake.";
            case "move_hourly": return "Long sitting periods of an hour or more during the day.";
            case "targets": return "How often you reached your daily activity goal in the last week.";
            case "frequency": return "Days this week with at least 30 minutes of medium or high activity.";
            case "volume": return "Energy burned in medium and high activity this week.";
            default: return null;
        }
    }
}
