package app.openring.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the height of a view per screen layout, so a rebuilt view can keep that space while
 * its content loads (Further analysis, issues #93 and #127). Heights are in pixels and belong to
 * one layout: after the phone turns or the font size changes the text wraps differently, so a
 * height from another layout is never handed out.
 */
public final class KeptHeight {
    private final Map<String, Integer> heights = new HashMap<>();

    /**
     * The layout a height belongs to: window width in dp, font scale and screen density (from
     * Android's Configuration), which together decide how wide the view is and how tall its text.
     */
    public static String layout(int widthDp, float fontScale, int densityDpi) {
        return widthDp + "/" + fontScale + "/" + densityDpi;
    }

    /** Remembers {@code px} as the height in {@code layout}; replaces an older height there. */
    public synchronized void remember(String layout, int px) {
        heights.put(layout, px);
    }

    /** The height remembered for {@code layout}, or 0 when none is known for it (keep no space). */
    public synchronized int get(String layout) {
        Integer h = heights.get(layout);
        return h == null ? 0 : h;
    }
}
