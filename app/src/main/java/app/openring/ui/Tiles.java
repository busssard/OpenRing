package app.openring.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.openring.core.Layout;
import app.openring.data.Prefs;

/**
 * The tiles of a tab (owner's wish of 18 November): every card on Today, Sleep, Ready, Activity, Body and Screen gets the shared
 * {@link Fold} header (its title, a one line summary while folded, the chevron), can be folded with a tap on that header and
 * moved by holding it: it lifts, follows the finger, the others make room, and letting go keeps the new place. Holding and
 * letting go without moving opens a small menu (Move up, Move down, to the top, to the bottom, Fold or Open), which is also
 * how TalkBack moves a tile. The order and the folds stay per tab ({@code core/Layout}, saved in {@link Prefs}, demo mode has
 * its own), until changed or put back with "Back to the standard layout" at the end of the tab or in Settings.
 * <p>
 * Display only: the pages build their cards exactly as before, in the standard order; {@link #arrange} only wraps and orders
 * them after the page is built. Drags start only from a header, so charts keep their touch readout and the page its scrolling.
 */
final class Tiles {
    private Tiles() {}

    /** The one line at the end of each tab. */
    static final String HINT = Explain.TILES_HINT;
    static final String RESET = "Back to the standard layout";

    /** What a page says about one of its cards; a card without it gets key and title from its caption. */
    static final class Spec {
        String key, title, summary;
        boolean standardOpen = true;
        Listener listener;
    }

    /** Told when the person opens or folds the tile (Further analysis calculates only while open). */
    interface Listener {
        void folded(boolean open);
    }

    private static final Map<View, Spec> SPECS = new WeakHashMap<View, Spec>();
    /** Tiles opened by a screen test extra ("tab:key"), shown open once without being saved. */
    static final Set<String> openOnce = new HashSet<String>();
    /** The page being dragged on, null when none: a load waits for the drop instead of replacing the page under the finger. */
    private static Page busy;
    private static Runnable afterDrop;

    /** The spec of {@code card}, made on first use. */
    static Spec spec(View card) {
        Spec s = SPECS.get(card);
        if (s == null) SPECS.put(card, s = new Spec());
        return s;
    }

    /** Names a card that has no caption, or whose caption changes (a stable key), with the summary shown while it is folded. */
    static <T extends View> T name(T card, String key, String title, String summary) {
        Spec s = spec(card);
        s.key = key;
        s.title = title;
        s.summary = summary;
        return card;
    }

    /** The summary shown while the card is folded (its key number), in place of the one read from the card. */
    static <T extends View> T summary(T card, String summary) {
        spec(card).summary = summary;
        return card;
    }

    /** Whether the tile {@code key} of {@code tab} shows open: as the person left it, else {@code standardOpen}. */
    static boolean open(Context c, int tab, String key, boolean standardOpen) {
        String t = Layout.tab(tab);
        if (t == null) return standardOpen;
        if (openOnce.contains(t + ":" + key)) return true;
        return Layout.open(Layout.folds(Prefs.of(c).tileFolds(t)), key, standardOpen);
    }

    /** A load asked for while a tile is dragged: runs after the drop. Returns false when nothing is dragged (load now). */
    static boolean waitForDrop(Runnable r) {
        if (busy == null) return false;
        afterDrop = r;
        return true;
    }

    /** Whether any tab of the current mode (demo or the person's) is not in the standard layout (Settings' status line). */
    static int changedTabs(Context c) {
        Prefs p = Prefs.of(c);
        int n = 0;
        for (String t : Layout.TABS) if (!Layout.standard(p.tileOrder(t), p.tileFolds(t))) n++;
        return n;
    }

    /** Asks, then puts every tab (null) or one tab back to the standard layout and runs {@code done}. */
    static void confirmReset(final Context c, final String tab, final Runnable done) {
        new AlertDialog.Builder(c).setTitle(tab == null ? "Reset layout?" : RESET + "?")
                .setMessage(tab == null ? Explain.LAYOUT_RESET_ALL : Explain.LAYOUT_RESET_TAB)
                .setPositiveButton(tab == null ? "Reset" : "Put back", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.of(c).resetTiles(tab);
                        if (done != null) done.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------------------------------- arranging

    /** One tile on the page: the card in its {@link Fold}, inside a surface that looks like the card did. */
    static final class Tile {
        String key, title;
        boolean standardOpen;
        Spec spec;
        View view;
        Fold fold;
        TextView summary;
        /** The space under the tile's content while open (the card's own); folded, the header needs less. */
        int bottom;
    }

    /**
     * Wraps the cards of {@code root} (a page as Pages built it) into tiles and puts them in the saved order, with their saved
     * folds, then adds the hint and "Back to the standard layout" at the end. Children that are not cards (the sync line on
     * Today, the note at the end of Screen) keep their places. {@code reload} builds the page again (after a reset).
     */
    static void arrange(Context c, ScrollView scroll, int tab, LinearLayout root, Runnable reload) {
        String tabName = Layout.tab(tab);
        if (tabName == null || root.getBackground() != null) return;
        Page page = new Page(c, scroll, tab, tabName, root, reload);
        List<Integer> slots = new ArrayList<Integer>();
        List<View> cards = new ArrayList<View>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View ch = root.getChildAt(i);
            if (ch instanceof LinearLayout && ch.getBackground() != null) {
                slots.add(i);
                cards.add(ch);
            }
        }
        if (cards.isEmpty()) return;
        // the cards leave the page first: each goes into its tile, the tiles come back in the saved order
        for (int i = slots.size() - 1; i >= 0; i--) root.removeViewAt(slots.get(i));
        List<String> raw = new ArrayList<String>();
        List<Tile> made = new ArrayList<Tile>();
        for (View card : cards) {
            Tile t = page.wrap((LinearLayout) card);
            made.add(t);
            raw.add(t.key);
        }
        List<String> keys = Layout.unique(raw);
        for (int i = 0; i < made.size(); i++) made.get(i).key = keys.get(i);
        page.standard = keys;
        Prefs p = Prefs.of(c);
        List<String> order = Layout.order(Layout.parse(p.tileOrder(tabName)), keys);
        Map<String, Boolean> folds = Layout.folds(p.tileFolds(tabName));
        for (String k : order) page.tiles.add(made.get(keys.indexOf(k)));
        for (int i = 0; i < slots.size(); i++) root.addView(page.tiles.get(i).view, slots.get(i));
        for (Tile t : page.tiles) {
            boolean once = openOnce.remove(tabName + ":" + t.key);
            page.show(t, once || Layout.open(folds, t.key, t.standardOpen));
        }
        page.footer();
    }

    /** One arranged page and its drag. */
    static final class Page {
        final Context c;
        final ScrollView scroll;
        final int tab;
        final String tabName;
        final LinearLayout root;
        final Runnable reload;
        final List<Tile> tiles = new ArrayList<Tile>();
        List<String> standard;
        TextView resetButton;
        // the drag
        Tile dragged;
        boolean touching, moved, scrolling;
        int from, to, startScroll;
        float startRaw, lastRaw;
        int[] tops, heights, shift;

        Page(Context c, ScrollView scroll, int tab, String tabName, LinearLayout root, Runnable reload) {
            this.c = c;
            this.scroll = scroll;
            this.tab = tab;
            this.tabName = tabName;
            this.root = root;
            this.reload = reload;
        }

        /** The card as a tile: its caption (or the page's title for it) becomes the header of a shared Fold, the rest its body. */
        Tile wrap(LinearLayout card) {
            final Tile t = new Tile();
            Spec s = SPECS.get(card);
            t.spec = s;
            t.standardOpen = s == null || s.standardOpen;
            View first = card.getChildCount() > 0 ? card.getChildAt(0) : null;
            TextView title = null;
            if (isTitle(first) && (s == null || s.title == null)) {
                title = (TextView) first;
                card.removeView(first);
            }
            String firstText = firstText(card);
            if (title == null) {
                String name = s != null && s.title != null ? s.title : "Note";
                title = Ui.caption(c, name);
            }
            t.title = s != null && s.title != null ? s.title : sentence(title.getText().toString());
            String k = s != null && s.key != null ? s.key : Layout.key(title.getText().toString());
            if (s == null || s.key == null) {
                // a card without its own title (a note): its first words, so two notes on one page keep their own places
                if ("note".equals(k) && firstText != null) k = Layout.key("note " + firstText);
            }
            t.key = k;
            String sum = s != null && s.summary != null ? s.summary : autoSummary(card);

            LinearLayout words = Ui.vbox(c);
            words.addView(title);
            t.summary = Fold.oneLine(Ui.text(c, sum == null ? "" : sum, 13, Ui.TEXT_2, Ui.REGULAR));
            t.summary.setPadding(0, Ui.dp(c, 2), 0, 0);
            words.addView(t.summary);

            // the surface: the card's own background and padding, so an open tile looks as the card did
            LinearLayout outer = Ui.vbox(c);
            outer.setBackground(card.getBackground());
            card.setBackground(null);
            int top = Math.min(card.getPaddingTop(), Ui.dp(c, 4));
            outer.setPadding(card.getPaddingLeft(), top, card.getPaddingRight(), card.getPaddingBottom());
            t.bottom = card.getPaddingBottom();
            card.setPadding(0, 0, 0, 0);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            outer.setLayoutParams(lp != null ? lp : Ui.matchWrap());
            card.setLayoutParams(Ui.matchWrap());

            final boolean[] open = {true};
            t.fold = Fold.inline(c, words, t.title + (sum == null ? "" : ", " + sum), open, false);
            t.fold.head.setMinimumHeight(Ui.dp(c, 40));
            t.fold.body.addView(card, Ui.matchWrap());
            outer.addView(t.fold.view, Ui.matchWrap());
            t.view = outer;
            t.fold.onChange(new Runnable() {
                @Override
                public void run() {
                    folded(t);
                }
            });
            final View head = t.fold.head;
            head.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return lift(t);
                }
            });
            head.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    touch(t, e);
                    return false; // the header still gets every event: its tap folds, its hold lifts, its ripple ends
                }
            });
            head.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Move or fold"));
                }
            });
            return t;
        }

        /** Shows the tile open or folded; after {@link #arrange} has set the saved states, a change is saved (the person did it). */
        void show(Tile t, boolean open) {
            if (t.fold.isOpen() != open) t.fold.show(open); // calls folded()
            look(t);
        }

        /** Folded: the one line summary, and only a little space under the header, so a folded tab stays compact. */
        void look(Tile t) {
            boolean open = t.fold.isOpen();
            t.summary.setVisibility(!open && t.summary.getText().length() > 0 ? View.VISIBLE : View.GONE);
            View v = t.view;
            int b = open ? t.bottom : Math.min(t.bottom, Ui.dp(c, 6));
            if (v.getPaddingBottom() != b) v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), b);
        }

        /** After a tap on the header: the summary, the saved state, the reset button, the page's own listener. */
        void folded(Tile t) {
            boolean open = t.fold.isOpen();
            look(t);
            if (!loading) {
                Prefs p = Prefs.of(c);
                p.setTileFolds(tabName, Layout.fold(p.tileFolds(tabName), t.key, open));
                if (t.spec != null && t.spec.listener != null) t.spec.listener.folded(open);
                updateReset();
            }
        }

        /** True while {@link #arrange} sets the saved states (nothing to save then). */
        boolean loading = true;

        /** The hint and Back to the standard layout, at the end of the page. */
        void footer() {
            loading = false;
            LinearLayout f = Ui.vbox(c);
            f.setGravity(Gravity.CENTER_HORIZONTAL);
            f.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 8));
            TextView hint = Ui.text(c, HINT, 12, Ui.TEXT_3, Ui.REGULAR);
            hint.setGravity(Gravity.CENTER);
            f.addView(hint);
            resetButton = Ui.textButton(c, RESET, Ui.TEXT_2);
            resetButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmReset(c, tabName, reload);
                }
            });
            f.addView(resetButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(f, Ui.matchWrap());
            updateReset();
        }

        /** The reset button shows only when the page is not in the standard layout. */
        void updateReset() {
            if (resetButton == null) return;
            boolean same = true;
            for (int i = 0; i < tiles.size() && same; i++) {
                Tile t = tiles.get(i);
                if (!t.key.equals(standard.get(i)) || t.fold.isOpen() != t.standardOpen) same = false;
            }
            resetButton.setVisibility(same ? View.GONE : View.VISIBLE);
        }

        // --------------------------------------------------------------------------------------- moving

        void touch(Tile t, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    lastRaw = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    lastRaw = e.getRawY();
                    if (dragged == t) follow();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    if (dragged == t) {
                        lastRaw = e.getRawY();
                        boolean menu = !moved && e.getActionMasked() == MotionEvent.ACTION_UP;
                        drop();
                        if (menu) menu(t);
                    }
                    break;
                default:
                    break;
            }
        }

        /** The header was held: lift the tile under the finger, or open the menu when held without a finger (TalkBack). */
        boolean lift(Tile t) {
            if (!touching || dragged != null) {
                if (dragged == null) menu(t);
                return true;
            }
            int i = tiles.indexOf(t);
            if (i < 0) return false;
            dragged = t;
            busy = this;
            from = to = i;
            moved = false;
            startRaw = lastRaw;
            startScroll = scroll.getScrollY();
            int n = tiles.size();
            tops = new int[n];
            heights = new int[n];
            shift = new int[n];
            for (int k = 0; k < n; k++) {
                tops[k] = tiles.get(k).view.getTop();
                heights[k] = tiles.get(k).view.getHeight();
            }
            t.fold.head.getParent().requestDisallowInterceptTouchEvent(true);
            t.fold.head.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            View v = t.view;
            v.animate().cancel();
            v.setTranslationZ(Ui.dp(c, 12));
            v.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.94f).setDuration(120).start();
            return true;
        }

        /** The lifted tile follows the finger and the page; the others make room. Scrolls near the top and bottom edge. */
        void follow() {
            if (dragged == null) return;
            float dy = lastRaw - startRaw + (scroll.getScrollY() - startScroll);
            if (Math.abs(lastRaw - startRaw) > ViewConfiguration.get(c).getScaledTouchSlop()) moved = true;
            dragged.view.setTranslationY(dy);
            to = Layout.target(tops, heights, from, dy);
            int room = heights[from] + gap(dragged.view);
            for (int i = 0; i < tiles.size(); i++) {
                if (i == from) continue;
                int want = from < i && i <= to ? -room : to <= i && i < from ? room : 0;
                if (shift[i] != want) {
                    shift[i] = want;
                    tiles.get(i).view.animate().translationY(want).setDuration(160).start();
                }
            }
            edgeScroll();
        }

        /** Scrolls while the finger is near the top or bottom edge of the page, faster the closer it is. */
        void edgeScroll() {
            if (scrolling || dragged == null) return;
            if (edgeSpeed() == 0) return;
            scrolling = true;
            scroll.postOnAnimation(new Runnable() {
                @Override
                public void run() {
                    int v = dragged == null ? 0 : edgeSpeed();
                    if (v == 0) {
                        scrolling = false;
                        return;
                    }
                    int before = scroll.getScrollY();
                    scroll.scrollBy(0, v);
                    if (scroll.getScrollY() != before) moved = true;
                    scrolling = false;
                    follow();
                }
            });
        }

        int edgeSpeed() {
            int[] at = new int[2];
            scroll.getLocationOnScreen(at);
            float edge = Ui.dp(c, 72), max = Ui.dp(c, 14);
            float top = at[1] + edge, bottom = at[1] + scroll.getHeight() - edge;
            if (lastRaw < top) return -Math.round(max * Math.min(1, (top - lastRaw) / edge) + 1);
            if (lastRaw > bottom) return Math.round(max * Math.min(1, (lastRaw - bottom) / edge) + 1);
            return 0;
        }

        /** Lets go: the tiles take their new order (no jump: the lifted one glides into its place) and the order is saved. */
        void drop() {
            final Tile t = dragged;
            dragged = null;
            busy = null;
            if (t == null) return;
            float visualTop = tops[from] + t.view.getTranslationY();
            int newTop = to > from ? tops[to] + heights[to] - heights[from] : tops[to];
            for (Tile o : tiles) {
                o.view.animate().cancel();
                o.view.setTranslationY(0);
            }
            if (to != from) put(from, to);
            t.view.setTranslationY(visualTop - newTop);
            t.view.animate().translationY(0).scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).withEndAction(new Runnable() {
                @Override
                public void run() {
                    t.view.setTranslationZ(0);
                }
            }).start();
            Runnable r = afterDrop;
            afterDrop = null;
            if (r != null) root.postDelayed(r, 200);
        }

        /** Moves the tile at {@code a} to {@code b} on the page and saves the order. */
        void put(int a, int b) {
            List<String> keys = new ArrayList<String>();
            for (Tile x : tiles) keys.add(x.key);
            keys = Layout.move(keys, a, b);
            // the places the tiles take on the page now (a notice added above them after arranging moved them all down)
            List<Integer> at = new ArrayList<Integer>();
            for (Tile x : tiles) at.add(root.indexOfChild(x.view));
            java.util.Collections.sort(at);
            tiles.add(Math.max(0, Math.min(tiles.size() - 1, b)), tiles.remove(a));
            for (Tile x : tiles) root.removeView(x.view);
            for (int i = 0; i < at.size(); i++) root.addView(tiles.get(i).view, at.get(i));
            Prefs p = Prefs.of(c);
            p.setTileOrder(tabName, Layout.join(Layout.remember(Layout.parse(p.tileOrder(tabName)), keys)));
            updateReset();
        }

        /** The menu of a held tile: move it without dragging (also with TalkBack), or fold or open it. */
        void menu(final Tile t) {
            final int i = tiles.indexOf(t);
            if (i < 0) return;
            final int last = tiles.size() - 1;
            PopupMenu m = new PopupMenu(c, t.fold.head, Gravity.END);
            Menu menu = m.getMenu();
            menu.add(0, 1, 1, "Move up").setEnabled(i > 0);
            menu.add(0, 2, 2, "Move down").setEnabled(i < last);
            menu.add(0, 3, 3, "Move to the top").setEnabled(i > 0);
            menu.add(0, 4, 4, "Move to the bottom").setEnabled(i < last);
            menu.add(0, 5, 5, t.fold.isOpen() ? "Fold" : "Open");
            m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    int id = item.getItemId();
                    if (id == 5) {
                        show(t, !t.fold.isOpen());
                        return true;
                    }
                    int b = id == 1 ? i - 1 : id == 2 ? i + 1 : id == 3 ? 0 : last;
                    put(i, b);
                    keepInView(t);
                    return true;
                }
            });
            m.show();
        }

        /** After a move from the menu: the page scrolls so the tile is on screen, its header a little below the top. */
        void keepInView(final Tile t) {
            t.view.post(new Runnable() {
                @Override
                public void run() {
                    int top = t.view.getTop() - Ui.dp(c, 12);
                    int y = scroll.getScrollY();
                    if (top < y || t.view.getTop() + Ui.dp(c, 60) > y + scroll.getHeight()) scroll.smoothScrollTo(0, Math.max(0, top));
                    t.fold.head.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------- reading the cards

    /** The space under a tile (its bottom margin), so the tiles making room move by the lifted tile's full height. */
    static int gap(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return lp instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) lp).bottomMargin + ((ViewGroup.MarginLayoutParams) lp).topMargin : 0;
    }

    /** A card's first child is its title when it is a short medium weight text (a caption or a heading). */
    static boolean isTitle(View v) {
        if (!(v instanceof TextView) || v.getVisibility() != View.VISIBLE) return false;
        TextView t = (TextView) v;
        CharSequence s = t.getText();
        return t.getTypeface() == Ui.MEDIUM && s != null && s.length() > 0 && s.length() <= 60 && s.toString().indexOf('\n') < 0;
    }

    /** The first text in {@code v}, null when none. */
    static String firstText(View v) {
        if (v instanceof TextView) {
            CharSequence s = ((TextView) v).getText();
            return s != null && s.length() > 0 ? s.toString() : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String s = firstText(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    /**
     * The line shown under a folded tile's title when the page gave none: its first big number with the label and unit of its
     * stat ("Active burn 412 kcal", "+0.2 °C", the day's line on Your day), else the first sentence with a number in it
     * ("Average 45 ms."). Null when the card has neither (a chart alone).
     */
    static String autoSummary(View card) {
        List<TextView> texts = new ArrayList<TextView>();
        collect(card, texts);
        for (TextView t : texts) {
            float sp = t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity;
            String s = t.getText().toString().trim();
            if (sp < 18 || s.isEmpty() || s.equals("--")) continue;
            StringBuilder b = new StringBuilder();
            String label = statLabel(t);
            if (label != null) b.append(label).append(' ');
            b.append(s);
            String unit = statUnit(t);
            if (unit != null) b.append(' ').append(unit);
            return b.toString();
        }
        for (TextView t : texts) {
            String s = t.getText().toString().trim();
            if (!hasDigit(s)) continue;
            int dot = s.indexOf(". ");
            return dot > 0 ? s.substring(0, dot + 1) : s;
        }
        return null;
    }

    private static void collect(View v, List<TextView> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            if (((TextView) v).getText() != null && ((TextView) v).getText().length() > 0) out.add((TextView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** The caption of the stat a value belongs to ({@link Ui#stat}: a caption over a row with the value), null when none. */
    private static String statLabel(TextView value) {
        if (!(value.getParent() instanceof ViewGroup)) return null;
        ViewGroup row = (ViewGroup) value.getParent();
        if (!(row.getParent() instanceof ViewGroup)) return null;
        ViewGroup stat = (ViewGroup) row.getParent();
        View first = stat.getChildAt(0);
        if (first == row || !(first instanceof TextView) || ((TextView) first).getTypeface() != Ui.MEDIUM) return null;
        return sentence(((TextView) first).getText().toString());
    }

    /** The unit after a value in its row, null when none. */
    private static String statUnit(TextView value) {
        if (!(value.getParent() instanceof ViewGroup)) return null;
        ViewGroup row = (ViewGroup) value.getParent();
        int i = row.indexOfChild(value);
        if (i < 0 || i + 1 >= row.getChildCount() || !(row.getChildAt(i + 1) instanceof TextView)) return null;
        String u = ((TextView) row.getChildAt(i + 1)).getText().toString().trim();
        return u.isEmpty() ? null : u;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    /** "RESTING HR" as "Resting HR": a caption (upper case on screen) as a label in a sentence. */
    static String sentence(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean upper = s.equals(s.toUpperCase(Locale.getDefault()));
        if (!upper) return s;
        String[] w = s.toLowerCase(Locale.getDefault()).split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            String bare = x.replaceAll("[^a-z0-9]", "");
            if (bare.equals("hr") || bare.equals("hrv") || bare.equals("rem") || bare.equals("met") || bare.equals("spo2")) x = x.toUpperCase(Locale.getDefault());
            else if (i == 0 && !x.isEmpty()) x = Character.toUpperCase(x.charAt(0)) + x.substring(1);
            if (i > 0) b.append(' ');
            b.append(x);
        }
        return b.toString();
    }

}
