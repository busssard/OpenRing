package app.openring.core;

/**
 * The start of the app (issue #373): Getting started, then the choice of a ring, a watch or just the phone, and what each
 * tab shows with the devices that are there. Plain Java so the JVM tests cover every decision; the screens only ask here.
 */
public final class Start {
    private Start() {}

    /** The path picked on "What do you want to use?" ({@code Prefs.startChoice}): none yet, a ring, a watch, just this phone. */
    public static final int NONE = 0, RING = 1, WATCH = 2, PHONE = 3;

    /** A stored or restored choice that is one of the four, else {@link #NONE}. */
    public static int valid(int choice) {
        return choice == RING || choice == WATCH || choice == PHONE ? choice : NONE;
    }

    /**
     * Whether the choice means "without a ring for now" ({@code KeyState.NO_RING} while no key is stored): the watch and the
     * phone paths. Stored at the tap, so a watch setup left half way never sends the person back to the ring setup.
     */
    public static boolean withoutRing(int choice) {
        return choice == WATCH || choice == PHONE;
    }

    /**
     * The main screen opens Setup (Getting started, or the key page of a pairing): {@link KeyState#opensSetup} as before, and
     * never while a watch is added, whatever the rest says (a person with only a watch never sees the ring setup again).
     * {@code keyState} comes from {@code Prefs.keyState}, which already counts an added watch as no ring.
     */
    public static boolean opensSetup(boolean demo, boolean setupDone, int keyState, boolean pairUnseen, boolean watchAdded) {
        if (watchAdded && keyState == KeyState.NONE) return false;
        return KeyState.opensSetup(demo, setupDone, keyState, pairUnseen);
    }

    /** Whether the person counts as "no ring" for the key state: the choice without a ring, or a watch added. */
    public static boolean noRing(boolean noRingChosen, boolean watchAdded) {
        return noRingChosen || watchAdded;
    }

    // ------------------------------------------------------------------------------------------- what is there

    /** Demo previews for the screen test (extra {@code devices}, demo mode only): none, only the watch, only the phone. */
    public static final int PREVIEW_NONE = 0, PREVIEW_WATCH = 1, PREVIEW_PHONE = 2;

    /** The devices the tabs are built for. */
    public static final class Has {
        /** A ring is paired (a key is stored), or demo mode's made up ring. */
        public final boolean ring;
        /** A watch is added, or demo mode's made up watch. */
        public final boolean watch;
        /** The watch was chosen at the start, but none is added yet (its setup was left half way). */
        public final boolean watchPending;

        public Has(boolean ring, boolean watch, boolean watchPending) {
            this.ring = ring;
            this.watch = watch;
            this.watchPending = watchPending;
        }

        /** Neither a ring nor a watch: only the phone. */
        public boolean phoneOnly() {
            return !ring && !watch;
        }

        /** A watch and no ring. */
        public boolean watchOnly() {
            return watch && !ring;
        }
    }

    /**
     * What is there: {@code hasRing} from {@code KeyState.hasRing}, {@code watchAdded} a saved watch address, {@code choice}
     * the start choice. Demo mode has all its made up devices, or what {@code preview} says.
     */
    public static Has of(boolean demo, boolean hasRing, boolean watchAdded, int choice, int preview) {
        if (demo) {
            if (preview == PREVIEW_WATCH) return new Has(false, true, false);
            if (preview == PREVIEW_PHONE) return new Has(false, false, false);
            return new Has(true, true, false);
        }
        return new Has(hasRing, watchAdded, !hasRing && !watchAdded && choice == WATCH);
    }

    // ------------------------------------------------------------------------------------------- cards per tab

    /** Today: the readiness, sleep and activity rings; Find my ring; the ring's Key metrics; Live heart rate. */
    public static final String SCORES = "scores", FIND = "find", RING_METRICS = "ring_metrics", LIVE = "live";
    /** Today without a ring: the Screen ring with the line that the scores need a ring; the watch's day; the phone's day. */
    public static final String NEED_SCORES = "need_scores", WATCH_DAY = "watch_day", PHONE_DAY = "phone_day";
    /** Today: the watch was chosen but is not added yet. */
    public static final String FINISH_WATCH = "finish_watch";
    /** Sleep and Ready with neither device: one card with both ways to add one. */
    public static final String NEED_DEVICE = "need_device";
    /** Ready with only the watch: readiness needs a ring. */
    public static final String READY_NEEDS_RING = "ready_needs_ring";
    /** Body: the ring's latest readings, the live buttons, radar, cardio capacity and night charts; else one card instead. */
    public static final String RING_BODY = "ring_body", NIGHT_NEEDS_RING = "night_needs_ring";
    /** Body: the heart rate of the day (ring or watch); with neither one line instead. */
    public static final String HEART = "heart", HEART_NEEDS_DEVICE = "heart_needs_device";

    /** Tabs, as {@code ui/Pages}. */
    public static final int TODAY = 0, SLEEP = 1, READY = 2, ACTIVITY = 3, BODY = 4, SCREEN = 5;

    /**
     * The device dependent cards a tab shows for {@code h}, in order. Sleep and Ready with neither device show only
     * {@link #NEED_DEVICE}; a night already stored still shows (the caller checks that before asking).
     */
    public static java.util.List<String> cards(int tab, Has h) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        switch (tab) {
            case TODAY:
                if (h.ring) {
                    out.add(SCORES);
                    out.add(FIND);
                    out.add(RING_METRICS);
                    out.add(LIVE);
                } else {
                    out.add(NEED_SCORES);
                    if (h.watchPending) out.add(FINISH_WATCH);
                    out.add(h.watch ? WATCH_DAY : PHONE_DAY);
                }
                break;
            case SLEEP:
                if (h.phoneOnly()) out.add(NEED_DEVICE);
                break;
            case READY:
                if (h.phoneOnly()) out.add(NEED_DEVICE);
                else if (!h.ring) out.add(READY_NEEDS_RING);
                break;
            case BODY:
                out.add(h.phoneOnly() ? HEART_NEEDS_DEVICE : HEART);
                out.add(h.ring ? RING_BODY : NIGHT_NEEDS_RING);
                break;
            default:
                break;
        }
        return out;
    }

    /** Whether {@code tab} shows {@code card} for {@code h}. */
    public static boolean shows(int tab, String card, Has h) {
        return cards(tab, h).contains(card);
    }

    // ------------------------------------------------------------------------------------------- texts

    public static final String TITLE = "Getting started";
    public static final String INTRO = "Open Ring reads your ring or watch directly over Bluetooth and works out sleep, readiness, activity and body "
            + "signals on this phone. No account, no cloud, no subscription. Your data never leaves the phone unless you export it.";
    public static final String GET_STARTED = "Get started";

    public static final String CHOICE_TITLE = "What do you want to use?";
    public static final String CHOICE_TEXT = "Pick one to start. You can add the other device later in Settings.";
    public static final String RING_TITLE = "Pair a ring";
    public static final String RING_TEXT = "Oura Ring. Sleep stages, readiness, heart rate variability (HRV), skin temperature and blood oxygen, "
            + "plus steps and heart rate.";
    public static final String WATCH_TITLE = "Pair a watch";
    public static final String WATCH_TEXT = "Withings Steel HR. Steps, heart rate through the day, the watch's own sleep, workouts and its battery. "
            + "No Withings account needed.";
    public static final String PHONE_TITLE = "Just this phone";
    public static final String PHONE_TEXT = "Steps from this phone, screen time and the period tracker. No device needed.";
    public static final String BOTH = "Both a ring and a watch? Start with one, then add the other in Settings. The main device gives the numbers, "
            + "the other fills the time it was not worn.";

    /** Bluetooth access, per path. */
    public static String bluetooth(int choice) {
        String what = choice == WATCH ? "your watch" : "your ring";
        return "The app needs permission to find and connect to " + what + ". On older Android versions this is called location access; "
                + "the app never reads your location.";
    }

    /** About you, per path. */
    public static String profile(int choice) {
        if (choice == WATCH) return "Used for calorie burn and activity goals, and sent to your watch so it can count distance and calories. "
                + "Stored only on this phone and the watch.";
        if (choice == PHONE) return "Used for calorie burn and activity goals. Stored only on this phone.";
        return "Used for calorie burn, activity goals and age adjusted sleep targets. Stored only on this phone.";
    }

    // tabs without a ring
    public static final String ADD_RING = KeyState.ADD_RING;
    public static final String ADD_WATCH = "Add a watch";
    public static final String SCORES_NEED_RING = "Readiness, sleep and activity scores need a ring.";
    public static final String SCORES_NEED_DEVICE = "Readiness, sleep and activity scores need a ring. Sleep also comes from a watch.";
    public static final String WATCH_DAY_TITLE = "From your watch";
    public static final String PHONE_DAY_TITLE = "From this phone";
    public static final String FINISH_TITLE = "Finish setting up your watch";
    public static final String FINISH_TEXT = "Your watch is not added yet. The watch setup finds it, pairs it and gets its first data.";
    public static final String FINISH_BUTTON = "Open the watch setup";
    public static final String WATCH_PAGE = "Open the watch page";
    public static final String NO_WATCH_NIGHT = "No sleep from your watch for this night. Wear the watch at night and sync it in the morning.";
    public static final String READY_TITLE = "Readiness needs a ring";
    public static final String READY_TEXT = "Readiness compares each night's heart rate, heart rate variability (HRV) and skin temperature with your "
            + "normal. The watch measures heart rate only, about every 30 minutes, so there is no readiness with only the watch yet. "
            + "Your sleep and steps from the watch are on Sleep and Activity.";
    public static final String NIGHT_TITLE = "Night measurements need a ring";
    public static final String NIGHT_TEXT = "Heart rate variability (HRV), breathing, blood oxygen, skin temperature, the symptom radar and cardio "
            + "capacity come from the ring's sensors at night. The watch measures heart rate only.";
    public static final String HEART_NEEDS = "Heart rate through the day needs a ring or a watch.";
    public static final String ACTIVITY_NEEDS_RING = "The activity score and calories need a ring. Steps and workouts are below.";
    public static final String NEED_DEVICE_TEXT = "Sleep and readiness need a ring or a watch.";
    public static final String ADD_DEVICE_TITLE = "Add a device";
    public static final String ADD_DEVICE_TEXT = "Pair a ring for sleep, readiness and night measurements, or a watch for steps, heart rate and its own sleep.";

    // one line for each number of the watch's and the phone's day
    public static final String STEPS_WATCH = "Counted by your watch";
    public static final String STEPS_PHONE = "Counted by this phone";
    public static final String SLEEP_WATCH = "Last night, as your watch counted it";
    public static final String HR_LOW = "Lowest of the day's readings";
    public static final String HR_AVG = "Average of the day's quarter hours";
    public static final String WORKOUTS = "Workouts your watch recorded";
    public static final String SCREEN_TODAY = "Time the screen was on";

    /** The workouts line: "1 workout" or "3 workouts". */
    public static String workouts(int n) {
        return n == 1 ? "1 workout" : n + " workouts";
    }

    /** Every text above, for the tests (plain, no dashes as punctuation). */
    public static String[] texts() {
        return new String[] {TITLE, INTRO, GET_STARTED, CHOICE_TITLE, CHOICE_TEXT, RING_TITLE, RING_TEXT, WATCH_TITLE, WATCH_TEXT, PHONE_TITLE,
            PHONE_TEXT, BOTH, bluetooth(RING), bluetooth(WATCH), profile(RING), profile(WATCH), profile(PHONE), ADD_RING, ADD_WATCH,
            SCORES_NEED_RING, SCORES_NEED_DEVICE, WATCH_DAY_TITLE, PHONE_DAY_TITLE, FINISH_TITLE, FINISH_TEXT, FINISH_BUTTON, WATCH_PAGE,
            NO_WATCH_NIGHT, READY_TITLE, READY_TEXT, NIGHT_TITLE, NIGHT_TEXT, HEART_NEEDS, ACTIVITY_NEEDS_RING, NEED_DEVICE_TEXT,
            ADD_DEVICE_TITLE, ADD_DEVICE_TEXT, STEPS_WATCH, STEPS_PHONE, SLEEP_WATCH, HR_LOW, HR_AVG, WORKOUTS, SCREEN_TODAY, workouts(1), workouts(3)};
    }
}
