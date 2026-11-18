package app.openring.core;

/**
 * The two device blocks of Settings (owner's wish of 3 November): everything about the ring in one block, everything about
 * the watch in another, each closed until its header is tapped. Which block a card of the {@code settings} extra lives in
 * (so opening Settings at that card opens its block first), and the one line status each header shows. Plain Java so the
 * JVM tests cover it.
 */
public final class SettingsBlocks {
    private SettingsBlocks() {}

    /** Not in a block, the Ring block, the Watch block, the Phone block (issue #329, phase 1b). */
    public static final int NONE = 0, RING = 1, WATCH = 2, PHONE = 3;

    /** Titles of the blocks' headers. */
    public static final String RING_TITLE = "Ring", WATCH_TITLE = "Watch", PHONE_TITLE = "Phone";

    /**
     * The block that holds the card named by the {@code settings} extra ({@code top}, {@code blocks}, {@code period},
     * {@code ring} (the Ring block itself, from Today's battery row), {@code stages}, {@code watch}, {@code screen}, {@code games}, {@code backups} or {@code advanced}); NONE for a card
     * outside the blocks, an unknown name or null.
     */
    public static int of(String section) {
        if ("stages".equals(section) || "ring".equals(section)) return RING;
        if ("watch".equals(section)) return WATCH;
        if ("phone".equals(section)) return PHONE;
        return NONE;
    }

    /** Whether {@code block} starts open: only when Settings is opened at one of its cards, otherwise closed. */
    public static boolean startsOpen(int block, String section) {
        return block != NONE && of(section) == block;
    }

    /**
     * Whether a build of the page scrolls to the card named by the {@code settings} extra: only the first build of a fresh
     * screen. A rebuild (a ring action, Go back, Delete all) or a turned phone keeps where the person scrolled to (review of #330).
     */
    public static boolean jumpOnBuild(String section, boolean restored, boolean jumpedBefore) {
        return section != null && !restored && !jumpedBefore;
    }

    /** The ring's model as Settings' Model line shows it, for example "Ring 4 (ORE_07)"; "--" when not known. */
    public static String model(String hw) {
        if (hw == null) return "--";
        if (hw.startsWith("BLB")) return "Gen 3 Horizon (" + hw + ")";
        if (hw.startsWith("ORE") || hw.endsWith("_04") || hw.endsWith("_06")) return "Ring 4 (" + hw + ")";
        if (hw.endsWith("_05")) return "Ring 5 (" + hw + ")";
        return hw;
    }

    /** The model without its hardware code, for the header: "Ring 4"; "Oura ring" when not known. */
    public static String shortModel(String hw) {
        if (hw == null || hw.isEmpty()) return "Oura ring";
        String m = model(hw);
        int cut = m.indexOf(" (");
        return cut > 0 ? m.substring(0, cut) : m;
    }

    /**
     * The Ring header's line: "Ring 4, last sync 2 hours ago" or "Ring 4, not synced yet"; in demo mode the made up ring.
     * {@code ago} is the time since the last sync as Android words it ("2 hours ago").
     */
    public static String ringStatus(boolean demo, String hw, long lastSync, String ago) {
        if (demo) return "Made up Ring 4, demo data";
        String name = shortModel(hw);
        if (lastSync <= 0 || ago == null) return name + ", not synced yet";
        return name + ", last sync " + ago;
    }

    /**
     * The Phone header's line (issue #329): "Counting steps, last reading 20 minutes ago", "Counting steps, no reading yet",
     * "Steps from this phone are off", "This phone has no step counter"; in demo mode the made up phone steps. {@code ago} is
     * the time since the last reading as Android words it.
     */
    public static String phoneStatus(boolean demo, boolean sensor, boolean allowed, double lastReading, String ago) {
        if (demo) return "Made up phone steps, demo data";
        if (!sensor) return "This phone has no step counter";
        if (!allowed) return "Steps from this phone are off";
        if (lastReading <= 0 || ago == null) return "Counting steps, no reading yet";
        return "Counting steps, last reading " + ago;
    }

    /**
     * The Watch header's line: "No watch added yet", "Steel HR, syncing now", "Steel HR, the last sync did not work",
     * "Steel HR, not synced yet" or "Steel HR, last sync 2 hours ago"; in demo mode the made up watch.
     */
    public static String watchStatus(boolean demo, boolean added, String name, long lastOk, String ago, boolean syncing, boolean failed) {
        if (demo) return "Made up Steel HR, demo data";
        if (!added) return "No watch added yet";
        String n = name == null || name.isEmpty() ? "Withings watch" : name;
        if (syncing) return n + ", syncing now";
        if (failed) return n + ", the last sync did not work";
        if (lastOk <= 0 || ago == null) return n + ", not synced yet";
        return n + ", last sync " + ago;
    }

    // ------------------------------------------------------------------- every other section folds the same way (17 November)

    /**
     * The titles of Settings' other sections (owner's wish of 17 November: "The same folding open should be implemented in the
     * settings consistently"): each is a card with its title, a one line status and a chevron, closed until tapped.
     */
    public static final String UPDATES = "Updates", PROFILE = "Profile and goals", PERIOD = "Period tracker", SCREEN = "Screen time",
            GAMES = "Ring games", FILE = "Backup file", BACKUPS = Backups.TITLE, EXPORT = "Export", HELP = "Help and diagnostics",
            ADVANCED = "Advanced", CAREFUL = "Careful", ABOUT = "About";

    /** The fixed status lines, for sections whose header says what is inside. */
    public static final String GAMES_STATUS = "Your ring as a game controller", GAMES_NO_RING = "Needs a ring",
            FILE_STATUS = "Save the app to a file, for a new phone", EXPORT_STATUS = "Daily summaries or the whole database",
            HELP_STATUS = "Data check, log and Re analyse all", ADVANCED_STATUS = "Leave out a time someone else wore the ring",
            CAREFUL_STATUS = "Delete all local data";

    /**
     * Export (issue #292): {@link #EXPORT_STATUS}, or with the CSV choice ticked "Daily summaries with life factors, or the
     * database"; Intimacy is never named in the status, it is a line anyone looking at the screen can read.
     */
    public static String exportStatus(boolean life) {
        return life ? "Daily summaries with life factors, or the database" : EXPORT_STATUS;
    }

    /** Updates: "Open Ring 2.0.19 installed"; "Open Ring" when the version is not known. */
    public static String updatesStatus(String version) {
        return version == null || version.isEmpty() ? "Open Ring" : "Open Ring " + version + " installed";
    }

    /** About: "Open Ring 2.0.19, not a medical device". */
    public static String aboutStatus(String version) {
        return (version == null || version.isEmpty() ? "Open Ring" : "Open Ring " + version) + ", not a medical device";
    }

    /** Profile: "34 years, female, 62 kg, 168 cm" ({@code sex} 0 male, 1 female, else left out). */
    public static String profileStatus(int age, int sex, int weightKg, int heightCm) {
        return age + " years" + (sex == 0 ? ", male" : sex == 1 ? ", female" : "") + ", " + weightKg + " kg, " + heightCm + " cm";
    }

    /** Period tracker: "Reminder 2 days before" or "No reminder". */
    public static String periodStatus(int remindDays) {
        return remindDays <= 0 ? "No reminder" : "Reminder " + PeriodRemind.chip(remindDays) + " before";
    }

    /**
     * Screen time: "Needs Android 9 or newer", "Usage access not allowed yet", "Allowed, daily goal 3h" or "Allowed, no goal";
     * in demo mode the demo's data with the goal.
     */
    public static String screenStatus(boolean demo, boolean supported, boolean allowed, int goalMin) {
        String goal = goalMin > 0 ? "daily goal " + goal(goalMin) : "no goal";
        if (demo) return "Demo data, " + goal;
        if (!supported) return "Needs Android 9 or newer";
        if (!allowed) return "Usage access not allowed yet";
        return "Allowed, " + goal;
    }

    /** "3h", "2h 30m" or "45 min". */
    static String goal(int min) {
        if (min < 60) return min + " min";
        return min % 60 == 0 ? (min / 60) + "h" : (min / 60) + "h " + (min % 60) + "m";
    }

    /** Automatic backups: "Demo, an example", "Off, a copy only before updates" or "Every week, to Backups". */
    public static String backupsStatus(boolean demo, boolean folder, int days, String folderName) {
        if (demo) return "Demo, an example";
        if (!folder) return "Off, a copy only before updates";
        String how = "Regular copies";
        for (int i = 0; i < Backups.DAYS.length && i < Backups.DAY_NAMES.length; i++) if (Backups.DAYS[i] == days) how = Backups.DAY_NAMES[i];
        return folderName == null || folderName.isEmpty() ? how : how + ", to " + folderName;
    }
}
