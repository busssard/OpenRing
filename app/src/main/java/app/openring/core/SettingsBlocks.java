package app.openring.core;

/**
 * The two device blocks of Settings (owner's wish of 3 November): everything about the ring in one block, everything about
 * the watch in another, each closed until its header is tapped. Which block a card of the {@code settings} extra lives in
 * (so opening Settings at that card opens its block first), and the one line status each header shows. Plain Java so the
 * JVM tests cover it.
 */
public final class SettingsBlocks {
    private SettingsBlocks() {}

    /** Not in a block, the Ring block, the Watch block. */
    public static final int NONE = 0, RING = 1, WATCH = 2;

    /** Titles of the blocks' headers. */
    public static final String RING_TITLE = "Ring", WATCH_TITLE = "Watch";

    /**
     * The block that holds the card named by the {@code settings} extra ({@code top}, {@code blocks}, {@code period},
     * {@code stages}, {@code watch}, {@code screen}, {@code games}, {@code backups} or {@code advanced}); NONE for a card
     * outside the blocks, an unknown name or null.
     */
    public static int of(String section) {
        if ("stages".equals(section)) return RING;
        if ("watch".equals(section)) return WATCH;
        return NONE;
    }

    /** Whether {@code block} starts open: only when Settings is opened at one of its cards, otherwise closed. */
    public static boolean startsOpen(int block, String section) {
        return block != NONE && of(section) == block;
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
}
