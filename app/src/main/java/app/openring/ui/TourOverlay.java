package app.openring.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

import app.openring.core.Tour;

/**
 * The short tour over the main screen (issue #453): a dim layer with a rounded hole around one real part of the app and a
 * bubble with the step's sentences, its count, Next and Skip the tour; the demo ends with "That was the demo." and a way to
 * add a device. The steps, their texts and every decision are in {@code core/Tour}; this view only draws them.
 * <p>
 * The screens mark the part of each step where they build it ({@link #mark}), in a weak map so no view tag is taken. After
 * every build of the page the overlay looks for its step's mark again, so moved tiles and new cards never break it. A part
 * inside a folded tile is shown by its tile (the page's child that holds it), except the chart, whose step is then skipped; a
 * part not on screen at all skips its step. A touch inside the hole goes to the app under it (the part works as usual), a
 * touch elsewhere does nothing. While the tour runs the page under it is hidden from TalkBack, which reads the bubble.
 */
final class TourOverlay extends FrameLayout {
    /** What the overlay needs from the main screen. */
    interface Host {
        /** The tab shown now ({@code Pages} numbers). */
        int tab();

        /** Shows another tab; the screen calls {@link #pageChanged} once the page is built. */
        void goTab(int tab);

        ScrollView scroll();

        boolean demo();

        /** A device is set up (a ring key, a watch, or the choice without a ring): the ending goes back to it. */
        boolean hasDevice();

        /** Done or skipped once: the tour never starts by itself again. */
        void seen();

        /** The ending's first button: leaves the demo (Getting started opens when nothing is set up). */
        void leaveDemo();

        /** The overlay is gone. */
        void closed();
    }

    private static final Map<View, String> MARKS = new WeakHashMap<View, String>();

    /** Marks {@code v} as the part a step points at ({@code core/Tour} keys); returns it, so a builder can wrap a call. */
    static <T extends View> T mark(T v, String key) {
        if (v != null && key != null) MARKS.put(v, key);
        return v;
    }

    private final Host host;
    /** The step shown now ({@code core/Tour}: 0 to 6, {@link Tour#END}, or {@link Tour#CLOSED}). */
    int step = Tour.CLOSED;
    /** The tour started after the first sync with a real device (step 1 says so). */
    final boolean firstSync;
    /** The way the last move went (1 Next, -1 Back): a step without its part is skipped the same way. */
    private int dir = 1;
    private int misses;
    /** The step whose texts the bubble holds (TalkBack hears a step once, not again after each build of the page). */
    private int said = Tour.CLOSED;
    private View target;
    private final RectF hole = new RectF();
    private boolean hasHole;
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final LinearLayout bubble, stepRow, endRow;
    private final TextView count, text, next, endMain;
    private boolean forwarding, moved;
    private float downX, downY;
    private final int slop;

    private final Runnable locateRun = new Runnable() {
        @Override
        public void run() {
            locate();
        }
    };
    private final Runnable placeRun = new Runnable() {
        @Override
        public void run() {
            place();
        }
    };
    private final ViewTreeObserver.OnScrollChangedListener onScroll = new ViewTreeObserver.OnScrollChangedListener() {
        @Override
        public void onScrollChanged() {
            if (target != null) place();
        }
    };

    private TourOverlay(Context c, Host host, boolean firstSync) {
        super(c);
        this.host = host;
        this.firstSync = firstSync;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        dim.setColor(Ui.alpha(0xFF000000, 0.72f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ring.setColor(Ui.SLEEP);
        // the dim layer itself is nothing to read; the bubble inside it is
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);

        bubble = Ui.vbox(c);
        bubble.setBackground(Ui.rounded(Ui.CARD_HI, Ui.dp(c, 16)));
        int p = Ui.dp(c, 16);
        bubble.setPadding(p, Ui.dp(c, 12), p, Ui.dp(c, 10));
        bubble.setClickable(true);
        count = Ui.text(c, "", 12, Ui.TEXT_3, Ui.MEDIUM);
        bubble.addView(count);
        text = Ui.text(c, "", 15, Ui.TEXT, Ui.REGULAR);
        text.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 8));
        bubble.addView(text);

        stepRow = Ui.hbox(c);
        stepRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView skip = Ui.textButton(c, Tour.SKIP, Ui.TEXT_2);
        skip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        stepRow.addView(skip);
        stepRow.addView(new View(c), Ui.weight(1));
        next = Ui.button(c, Tour.NEXT, Ui.SLEEP, true);
        next.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                next();
            }
        });
        stepRow.addView(next);
        bubble.addView(stepRow, Ui.matchWrap());

        endRow = Ui.vbox(c);
        endMain = Ui.button(c, Tour.ADD_DEVICE, Ui.SLEEP, true);
        endMain.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
                TourOverlay.this.host.leaveDemo();
            }
        });
        endRow.addView(endMain, Ui.matchWrap());
        TextView keep = Ui.button(c, Tour.KEEP_LOOKING, Ui.TEXT_2, false);
        keep.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        LinearLayout.LayoutParams kl = Ui.matchWrap();
        kl.setMargins(0, Ui.dp(c, 10), 0, 0);
        endRow.addView(keep, kl);
        bubble.addView(endRow, Ui.matchWrap());

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        int m = Ui.dp(c, 16);
        bl.setMargins(m, m, m, m);
        addView(bubble, bl);
    }

    /**
     * Puts the tour over the screen of {@code a} at {@code step} (0 to 6, or {@link Tour#END}; the ending only in demo mode).
     * The screen's own view must be the first child of Android's content frame (setContentView).
     */
    static TourOverlay start(Activity a, Host host, int step, boolean firstSync) {
        FrameLayout content = (FrameLayout) a.findViewById(android.R.id.content);
        TourOverlay t = new TourOverlay(a, host, firstSync);
        content.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View page = t.page();
        if (page != null) page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ViewTreeObserver o = host.scroll().getViewTreeObserver();
        if (o.isAlive()) o.addOnScrollChangedListener(t.onScroll);
        if (step >= Tour.COUNT) step = host.demo() ? Tour.END : Tour.COUNT - 1;
        t.show(Math.max(0, step), 1);
        return t;
    }

    /** The screen's own view, under the overlay. */
    private View page() {
        ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        View first = parent == null || parent.getChildCount() == 0 ? null : parent.getChildAt(0);
        return first == this ? null : first;
    }

    /** The page was built again (a load, another tab): the step's part is looked for once it is laid out. */
    void pageChanged() {
        if (step < 0 || step >= Tour.COUNT) return;
        removeCallbacks(locateRun);
        post(locateRun);
    }

    void next() {
        show(Tour.step(step, 1, host.demo()), 1);
    }

    /** Back goes one step back; on the first step it closes the tour (as Skip). */
    void back() {
        show(Tour.step(step, -1, host.demo()), -1);
    }

    private void show(int s, int d) {
        dir = d;
        if (s == Tour.CLOSED) {
            close();
            return;
        }
        step = s;
        if (s == Tour.END) {
            ending();
            return;
        }
        target = null;
        hasHole = false;
        bubble.setVisibility(INVISIBLE);
        invalidate();
        locate();
    }

    /** Finds the step's part (after the tab it needs is shown), brings it on screen and puts the hole and bubble around it. */
    private void locate() {
        if (step < 0 || step >= Tour.COUNT || getParent() == null) return;
        Tour.Step st = Tour.STEPS.get(step);
        if (st.tab != Tour.ANY && host.tab() != st.tab) {
            host.goTab(st.tab); // the page is built at once; pageChanged looks again after its layout
            pageChanged();
            return;
        }
        View t = find(st);
        if (t == null) {
            // not on screen (no night, a folded chart, only the phone): the next step the same way, never a hole on nothing
            android.util.Log.i("OpenRing", "tour: step " + (step + 1) + " (" + st.key + ") not on screen, skipped");
            if (++misses > 2 * Tour.COUNT) {
                close();
                return;
            }
            show(Tour.step(step, dir, host.demo()), dir);
            return;
        }
        misses = 0;
        target = t;
        if (said != step) {
            said = step;
            count.setVisibility(VISIBLE);
            count.setText(Tour.count(step));
            text.setText(Tour.text(step, firstSync));
            next.setText(Tour.nextLabel(step, host.demo()));
            stepRow.setVisibility(VISIBLE);
            endRow.setVisibility(GONE);
            android.util.Log.i("OpenRing", "tour: step " + (step + 1) + " (" + st.key + ")");
            bubble.announceForAccessibility(Tour.count(step) + ". " + Tour.text(step, firstSync));
        }
        bring(t);
        removeCallbacks(placeRun);
        post(placeRun);
    }

    /** The demo's ending: no hole, the bubble in the middle; seen from here on. */
    private void ending() {
        host.seen();
        said = Tour.END;
        target = null;
        hasHole = false;
        count.setVisibility(GONE);
        text.setText(Tour.END_TEXT);
        endMain.setText(Tour.endButton(host.hasDevice()));
        stepRow.setVisibility(GONE);
        endRow.setVisibility(VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.topMargin = lp.bottomMargin = Ui.dp(getContext(), 16);
        bubble.setLayoutParams(lp);
        bubble.setVisibility(VISIBLE);
        android.util.Log.i("OpenRing", "tour: the demo's ending");
        bubble.announceForAccessibility(Tour.END_TEXT);
        invalidate();
    }

    /** Closes the tour (Skip, Done, Back on the first step, the ending's buttons): never by itself again. */
    void close() {
        if (step == Tour.CLOSED && getParent() == null) return;
        step = Tour.CLOSED;
        target = null;
        removeCallbacks(locateRun);
        removeCallbacks(placeRun);
        View page = page();
        if (page != null) page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ViewTreeObserver o = host.scroll().getViewTreeObserver();
        if (o.isAlive()) o.removeOnScrollChangedListener(onScroll);
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        host.seen();
        android.util.Log.i("OpenRing", "tour: closed");
        host.closed();
    }

    // ------------------------------------------------------------------------------------------- finding the part

    private View find(Tour.Step st) {
        View root = page();
        if (root == null) return null;
        if (Tour.TILE.equals(st.key)) {
            // the title of the chart's tile (folded or not), else the first tile of the page
            View chart = findMark(root, Tour.CHART);
            View head = chart == null ? null : headOf(tileOf(chart));
            if (head == null) head = firstHead();
            return head != null && shown(head) ? head : null;
        }
        View v = findMark(root, st.key);
        if (v == null) return null;
        if (shown(v)) return v;
        if (!st.foldedOk) return null;
        View tile = tileOf(v);
        return tile != null && shown(tile) ? tile : null;
    }

    private static View findMark(View v, String key) {
        if (key.equals(MARKS.get(v))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findMark(g.getChildAt(i), key);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static boolean shown(View v) {
        return v.isShown() && v.getWidth() > 0 && v.getHeight() > 0;
    }

    /** The page as the scroll view shows it now. */
    private View content() {
        ScrollView s = host.scroll();
        return s.getChildCount() == 0 ? null : s.getChildAt(0);
    }

    /** The tile that holds {@code v}: the page's child it is in, null outside the page. */
    private View tileOf(View v) {
        View content = content();
        if (content == null || v == null) return null;
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) {
            if (x.getParent() == content) return x;
        }
        return null;
    }

    /** A tile's header: the first row of its fold ({@code ui/Tiles}: the tile holds the fold, the fold its header first). */
    private static View headOf(View tile) {
        if (!(tile instanceof ViewGroup) || ((ViewGroup) tile).getChildCount() == 0) return null;
        View fold = ((ViewGroup) tile).getChildAt(0);
        if (!(fold instanceof ViewGroup) || ((ViewGroup) fold).getChildCount() == 0) return null;
        View head = ((ViewGroup) fold).getChildAt(0);
        return head instanceof LinearLayout && head.isClickable() ? head : null;
    }

    private View firstHead() {
        View content = content();
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) content;
        for (int i = 0; i < g.getChildCount(); i++) {
            View h = headOf(g.getChildAt(i));
            if (h != null && shown(h)) return h;
        }
        return null;
    }

    /** Scrolls the page so the part is on screen with room around it; parts outside the page (header, tabs) stay. */
    private void bring(View t) {
        ScrollView s = host.scroll();
        View content = content();
        if (content == null) return;
        int y = 0;
        View x = t;
        while (x != null && x != content) {
            y += x.getTop();
            x = x.getParent() instanceof View ? (View) x.getParent() : null;
        }
        if (x != content) return;
        int top = s.getScrollY(), h = s.getHeight(), edge = Ui.dp(getContext(), 8);
        if (y >= top + edge && y + t.getHeight() <= top + h - edge) return;
        s.scrollTo(0, Math.max(0, y - h / 5));
    }

    /** The hole around the part and the bubble on the side with more room. */
    private void place() {
        if (target == null || step < 0 || step >= Tour.COUNT) return;
        if (!target.isAttachedToWindow() || !shown(target) || getWidth() == 0) {
            pageChanged();
            return;
        }
        int[] a = new int[2], b = new int[2];
        target.getLocationInWindow(a);
        getLocationInWindow(b);
        float pad = Ui.dp(getContext(), 6);
        hole.set(a[0] - b[0] - pad, a[1] - b[1] - pad, a[0] - b[0] + target.getWidth() + pad, a[1] - b[1] + target.getHeight() + pad);
        if (!hole.intersect(0, 0, getWidth(), getHeight())) hole.setEmpty();
        hasHole = !hole.isEmpty();
        int m = Ui.dp(getContext(), 16), gap = Ui.dp(getContext(), 12);
        bubble.measure(MeasureSpec.makeMeasureSpec(getWidth() - 2 * m, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int bh = bubble.getMeasuredHeight();
        int top = getHeight() - hole.bottom >= hole.top ? (int) hole.bottom + gap : (int) hole.top - gap - bh;
        top = Math.max(m, Math.min(top, getHeight() - bh - m));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        if (lp.gravity != Gravity.TOP || lp.topMargin != top) {
            lp.gravity = Gravity.TOP;
            lp.topMargin = top;
            lp.bottomMargin = 0;
            bubble.setLayoutParams(lp);
        }
        bubble.setVisibility(VISIBLE);
        invalidate();
    }

    // ------------------------------------------------------------------------------------------- drawing and touch

    @Override
    protected void dispatchDraw(Canvas c) {
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        float r = Ui.dp(getContext(), 18);
        if (hasHole) path.addRoundRect(hole, r, r, Path.Direction.CW);
        c.drawPath(path, dim);
        if (hasHole) c.drawRoundRect(hole, r, r, ring);
        super.dispatchDraw(c);
    }

    /**
     * A touch that starts inside the hole goes to the screen under it, so the part works as usual (Sync, a dial, a tab, the
     * chart's readout, a chip, a title, the gear); a tap there moves the tour on where the step says so. The bubble takes its
     * own touches; everything else is held back.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            boolean inBubble = bubble.getVisibility() == VISIBLE && e.getX() >= bubble.getLeft() && e.getX() < bubble.getRight()
                    && e.getY() >= bubble.getTop() && e.getY() < bubble.getBottom();
            View page = page();
            forwarding = hasHole && page != null && !inBubble && hole.contains(e.getX(), e.getY());
            moved = false;
            downX = e.getX();
            downY = e.getY();
        }
        if (forwarding) {
            View page = page();
            if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) moved = true;
            if (page != null) page.dispatchTouchEvent(e); // the same place: both fill Android's content frame
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                forwarding = false;
                if (act == MotionEvent.ACTION_UP && !moved) tapped();
            }
            return true;
        }
        super.dispatchTouchEvent(e);
        return true;
    }

    /** A tap on the part: after the part's own action (posted by Android), the tour moves on where the step says so. */
    private void tapped() {
        if (step < 0 || step >= Tour.COUNT || !Tour.STEPS.get(step).tapMovesOn) return;
        final int from = step;
        post(new Runnable() {
            @Override
            public void run() {
                if (step == from && getParent() != null) next();
            }
        });
    }
}
