package app.openring.core;

import java.util.HashMap;
import java.util.Map;

/**
 * The edit mode of the tiles (owner's wish of 20 November): Settings, Layout of the tabs, Rearrange tiles opens the tabs in an
 * edit session. Every move and fold in the session goes into this draft, for all tabs, and reaches the saved layout only with
 * Save; Reset puts the draft back to the standard layout (still to be saved); leaving without Save keeps the saved layout.
 * Plain Java, so the JVM tests run it.
 */
public final class LayoutDraft {
    private final Map<String, String> savedOrder = new HashMap<String, String>(), savedFolds = new HashMap<String, String>();
    private final Map<String, String> order = new HashMap<String, String>(), folds = new HashMap<String, String>();

    /** Starts from the saved layout of {@code tab} ({@code core/Layout} values, null for the standard). */
    public void load(String tab, String savedOrder, String savedFolds) {
        this.savedOrder.put(tab, empty(savedOrder));
        this.savedFolds.put(tab, empty(savedFolds));
        order.put(tab, empty(savedOrder));
        folds.put(tab, empty(savedFolds));
    }

    public String order(String tab) { return order.get(tab); }
    public String folds(String tab) { return folds.get(tab); }
    public void setOrder(String tab, String o) { order.put(tab, empty(o)); }
    public void setFolds(String tab, String f) { folds.put(tab, empty(f)); }

    /** Reset in the session: every tab in the standard order and open state; nothing is saved until Save. */
    public void reset() {
        for (String t : Layout.TABS) {
            order.put(t, null);
            folds.put(t, null);
        }
    }

    /** Whether Save would change anything (asked before leaving without saving); the order of the fold entries does not count. */
    public boolean changed() {
        for (String t : Layout.TABS) {
            if (!Layout.parse(order.get(t)).equals(Layout.parse(savedOrder.get(t)))) return true;
            if (!Layout.folds(folds.get(t)).equals(Layout.folds(savedFolds.get(t)))) return true;
        }
        return false;
    }

    /** Whether every tab of the draft is in the standard layout (Reset has nothing to do then). */
    public boolean standard() {
        for (String t : Layout.TABS) if (!Layout.standard(order.get(t), folds.get(t))) return false;
        return true;
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
