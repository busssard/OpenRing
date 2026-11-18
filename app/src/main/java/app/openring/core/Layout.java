package app.openring.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The order of the tiles on each tab and which of them are folded (owner's wish of 18 November: hold a tile to move it, fold it
 * and it stays folded, everything can move and fold, and a way back to the standard layout). Display only: no number and no
 * stored health data depends on it.
 * <p>
 * Every tile has a stable key (from its title or an explicit name, never its position). The saved order is a list of keys; the
 * page builds its tiles in the standard order and {@link #order} merges the two: tiles the person placed come in the person's
 * order, a tile this version adds (or one that was not on the page when the person moved others) comes right after the tile
 * it follows in the standard order, and keys of tiles that are not on the page are skipped but kept, so a tile that shows only
 * on some days (workouts, a left out time) keeps its place. Fold states are kept per key as open or folded; a tile without a
 * saved state has its standard state.
 */
public final class Layout {
    private Layout() {}

    /** The six tabs, in the order of the tab bar (Pages.TODAY .. Pages.SCREEN); the names are part of the preference keys. */
    public static final String[] TABS = {"today", "sleep", "ready", "activity", "body", "screen"};
    /** A key never gets longer than this; the title it comes from can be longer. */
    public static final int KEY_MAX = 40;
    private static final char SEP = '|';

    /** The tab's name for {@code tab} (0 Today .. 5 Screen); null for any other number. */
    public static String tab(int tab) {
        return tab >= 0 && tab < TABS.length ? TABS[tab] : null;
    }

    /**
     * A stable key from a title: lower case letters and digits, any other run of characters one space, at most {@link #KEY_MAX}
     * characters. "Resting heart rate, last 30 days" gives "resting heart rate last 30 days". Empty for a title without letters
     * or digits.
     */
    public static String key(String title) {
        if (title == null) return "";
        String s = title.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < KEY_MAX; i++) {
            char ch = s.charAt(i);
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                if (space && b.length() > 0) b.append(' ');
                b.append(ch);
                space = false;
            } else {
                space = true;
            }
        }
        return b.toString().trim();
    }

    /** Whether {@code k} is a key as {@link #key} makes them (a saved value from elsewhere is checked with it). */
    public static boolean valid(String k) {
        return k != null && !k.isEmpty() && k.equals(key(k));
    }

    /**
     * The keys of a page made unique: the second "contributors" on a page becomes "contributors 2", the third "contributors 3".
     * An empty key becomes "tile", so every tile has one.
     */
    public static List<String> unique(List<String> keys) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String k : keys) {
            String base = valid(k) ? k : key(k);
            if (base.isEmpty()) base = "tile";
            String u = base;
            for (int n = 2; seen.contains(u); n++) u = base + " " + n;
            seen.add(u);
            out.add(u);
        }
        return out;
    }

    /** A saved order: its keys, each once, in order; anything that is not a key is left out. Null or empty gives an empty list. */
    public static List<String> parse(String saved) {
        List<String> out = new ArrayList<String>();
        if (saved == null || saved.isEmpty()) return out;
        Set<String> seen = new HashSet<String>();
        for (String k : split(saved)) if (valid(k) && seen.add(k)) out.add(k);
        return out;
    }

    /** The order as it is saved: the keys with {@code |} between them. */
    public static String join(List<String> keys) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            if (!valid(k)) continue;
            if (b.length() > 0) b.append(SEP);
            b.append(k);
        }
        return b.toString();
    }

    /**
     * The order to show: the tiles of {@code standard} (the page's keys in its own order), those in {@code saved} in the saved
     * order, each other one right after the tile before it in the standard order (at the top when it is the first). Every tile
     * of the page comes exactly once; saved keys that are not on the page are skipped.
     */
    public static List<String> order(List<String> saved, List<String> standard) {
        List<String> out = new ArrayList<String>();
        Set<String> onPage = new HashSet<String>(standard);
        Set<String> placed = new HashSet<String>();
        if (saved != null) for (String k : saved) if (onPage.contains(k) && placed.add(k)) out.add(k);
        for (int i = 0; i < standard.size(); i++) {
            String k = standard.get(i);
            if (placed.contains(k)) continue;
            // after the nearest tile before it in the standard order that is already placed
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int p = out.indexOf(standard.get(j));
                if (p >= 0) {
                    at = p + 1;
                    break;
                }
            }
            out.add(at, k);
            placed.add(k);
        }
        return out;
    }

    /**
     * The order to save after the person moved tiles: {@code shown} (the page's keys in their new order), with each saved key
     * that is not on the page kept right after the key it followed in the saved order, so it comes back at its place.
     */
    public static List<String> remember(List<String> saved, List<String> shown) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String k : shown) if (valid(k) && seen.add(k)) out.add(k);
        if (saved == null) return out;
        for (int i = 0; i < saved.size(); i++) {
            String k = saved.get(i);
            if (!valid(k) || seen.contains(k)) continue;
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int p = out.indexOf(saved.get(j));
                if (p >= 0) {
                    at = p + 1;
                    break;
                }
            }
            out.add(at, k);
            seen.add(k);
        }
        return out;
    }

    /** {@code keys} with the one at {@code from} moved to {@code to} (both kept inside the list); a new list. */
    public static List<String> move(List<String> keys, int from, int to) {
        List<String> out = new ArrayList<String>(keys);
        if (from < 0 || from >= out.size()) return out;
        to = Math.max(0, Math.min(out.size() - 1, to));
        out.add(to, out.remove(from));
        return out;
    }

    /**
     * Where a dragged tile lands: {@code tops} and {@code heights} of the page's tiles before the drag (in pixels, in order),
     * the dragged one at {@code from} moved by {@code dy}. It takes the place of every tile whose middle its middle passed.
     */
    public static int target(int[] tops, int[] heights, int from, float dy) {
        if (from < 0 || from >= tops.length) return from;
        float mid = tops[from] + heights[from] / 2f + dy;
        int to = from;
        for (int i = from + 1; i < tops.length; i++) if (mid > tops[i] + heights[i] / 2f) to = i;
        for (int i = from - 1; i >= 0; i--) if (mid < tops[i] + heights[i] / 2f) to = i;
        return to;
    }

    // ------------------------------------------------------------------------------------------------ folds

    /** Saved fold states: key to open (true) or folded (false); entries are "+key" (open) or "-key" (folded) between {@code |}. */
    public static Map<String, Boolean> folds(String saved) {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        if (saved == null || saved.isEmpty()) return out;
        for (String e : split(saved)) {
            if (e.length() < 2) continue;
            char sign = e.charAt(0);
            String k = e.substring(1);
            if ((sign == '+' || sign == '-') && valid(k)) out.put(k, sign == '+');
        }
        return out;
    }

    /** The fold states as they are saved. */
    public static String joinFolds(Map<String, Boolean> folds) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Boolean> e : folds.entrySet()) {
            if (!valid(e.getKey()) || e.getValue() == null) continue;
            if (b.length() > 0) b.append(SEP);
            b.append(e.getValue() ? '+' : '-').append(e.getKey());
        }
        return b.toString();
    }

    /** Whether the tile {@code key} shows open: its saved state, else {@code standardOpen}. */
    public static boolean open(Map<String, Boolean> folds, String key, boolean standardOpen) {
        Boolean b = folds == null ? null : folds.get(key);
        return b == null ? standardOpen : b;
    }

    /** {@code saved} fold states with the tile {@code key} left open or folded by the person; it stays so until changed. */
    public static String fold(String saved, String key, boolean open) {
        Map<String, Boolean> m = folds(saved);
        if (valid(key)) {
            m.remove(key); // the newest change last, so the saved value reads in the order things happened
            m.put(key, open);
        }
        return joinFolds(m);
    }

    /** Whether a tab with this saved order and these fold states looks as in the standard layout (nothing to reset). */
    public static boolean standard(String order, String folds) {
        return parse(order).isEmpty() && folds(folds).isEmpty();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == SEP) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        return out;
    }
}
