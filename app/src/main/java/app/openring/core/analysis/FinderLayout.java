package app.openring.core.analysis;

/**
 * How Find my ring shares the screen's height (issue #191): everything on one screen without
 * scrolling, the map as the main view. The screen passes its height in dp and the font scale; the
 * plan gives the gauge size, the height of the signal trail strip, the card padding and the map's
 * minimum, and the map takes whatever is left.
 *
 * The fixed parts (bars, paddings, buttons) and the text (scaled with the font size) are estimates
 * of what {@code ui/FinderActivity} builds; when even the compact plan does not fit (a very large
 * font) the page scrolls instead of cutting anything ({@link Plan#fits} is false).
 */
public final class FinderLayout {
    private FinderLayout() {}

    /** Card padding and the gap under each card, regular and compact (dp). */
    public static final int PAD = 12, GAP = 8, PAD_COMPACT = 10, GAP_COMPACT = 6;
    /** Root padding top and bottom together, and the top bar with the gap under it (dp). */
    static final int ROOT = 20, ROOT_COMPACT = 14, TOP = 46, TOP_COMPACT = 42;
    /** Three cards: the last worn strip, the instruments and the map. */
    static final int CARDS = 3;
    /** Inside the cards apart from text: the gap above the strip's caption, the gaps above the status and the buttons, the button's padding. */
    static final int INNER = 10 + 14 + 24;
    /**
     * Text at font scale 1 (dp): the last worn strip (two lines of 14 sp), the strip's caption, the
     * status (three lines of 13 sp, 12 sp when compact) and the button's label.
     */
    static final double TEXT = 38 + 15 + 54 + 20, TEXT_COMPACT = 38 + 15 + 50 + 20;
    /** The band label and the signal line next to the gauge (dp at font scale 1): the gauge row is never lower. */
    public static final double ROW_TEXT = 74;

    public static final int GAUGE_MIN = 72, GAUGE_MID = 96, GAUGE_MAX = 104;
    public static final int STRIP_MIN = 40, STRIP_MID = 56, STRIP_MAX = 72;
    public static final int MAP_MIN = 140, MAP_MID = 200, MAP_COMFORT = 260;

    public static final class Plan {
        /** Smaller paddings, gaps and status text, for short screens. */
        public boolean compact;
        /** False when even the compact plan with every minimum is taller than the screen: the page then scrolls. */
        public boolean fits;
        public int pad, gap, statusSp;
        /** The gauge's side, the trail strip's height and the map's minimum (dp). */
        public int gauge, strip, mapMin;
        /** The map's planned height (dp), at least {@link #mapMin}. */
        public double map;

        /** Same sizes (the screen applies a plan only when it changed). */
        public boolean same(Plan o) {
            return o != null && compact == o.compact && gauge == o.gauge && strip == o.strip && mapMin == o.mapMin;
        }
    }

    /** Height of everything but the gauge row, the strip and the map (dp). */
    static double fixed(boolean compact, double fontScale) {
        int pad = compact ? PAD_COMPACT : PAD, gap = compact ? GAP_COMPACT : GAP;
        return (compact ? ROOT_COMPACT : ROOT) + (compact ? TOP_COMPACT : TOP) + CARDS * (2 * pad + gap) + INNER
                + (compact ? TEXT_COMPACT : TEXT) * fontScale;
    }

    /** The gauge row's height: the gauge, or the text next to it when that is taller. */
    static double row(double gauge, double fontScale) {
        return Math.max(gauge, ROW_TEXT * fontScale);
    }

    /** The plan for a screen {@code heightDp} tall (below the status bar) at {@code fontScale} (1 is the phone's normal size). */
    public static Plan plan(double heightDp, double fontScale) {
        if (!(fontScale > 0)) fontScale = 1;
        Plan p = new Plan();
        p.compact = false;
        double rest = heightDp - fixed(false, fontScale);
        double need = MAP_MIN + row(GAUGE_MIN, fontScale) + STRIP_MIN;
        if (rest < need) {
            p.compact = true;
            rest = heightDp - fixed(true, fontScale);
        }
        p.fits = rest >= need;
        p.pad = p.compact ? PAD_COMPACT : PAD;
        p.gap = p.compact ? GAP_COMPACT : GAP;
        p.statusSp = p.compact ? 12 : 13;
        p.mapMin = MAP_MIN;
        p.gauge = GAUGE_MIN;
        p.strip = STRIP_MIN;
        double map = MAP_MIN;
        double extra = Math.max(0, rest - need);
        // the map first, so a short screen keeps a usable map; then the instruments, then the rest to the map
        double add = Math.min(extra, MAP_MID - map);
        map += add;
        extra -= add;
        double before = row(p.gauge, fontScale);
        while (p.gauge < GAUGE_MID && row(p.gauge + 1, fontScale) - before <= extra) {
            p.gauge++;
            extra -= row(p.gauge, fontScale) - before;
            before = row(p.gauge, fontScale);
        }
        add = Math.min(extra, STRIP_MID - p.strip);
        p.strip += (int) add;
        extra -= (int) add;
        add = Math.min(extra, MAP_COMFORT - map);
        map += add;
        extra -= add;
        while (p.gauge < GAUGE_MAX && row(p.gauge + 1, fontScale) - before <= extra) {
            p.gauge++;
            extra -= row(p.gauge, fontScale) - before;
            before = row(p.gauge, fontScale);
        }
        add = Math.min(extra, STRIP_MAX - p.strip);
        p.strip += (int) add;
        extra -= (int) add;
        p.map = map + extra;
        return p;
    }

    /** The planned height of the whole screen (dp): what a test compares with the screen's height. */
    public static double total(Plan p, double fontScale) {
        if (!(fontScale > 0)) fontScale = 1;
        return fixed(p.compact, fontScale) + row(p.gauge, fontScale) + p.strip + p.map;
    }
}
