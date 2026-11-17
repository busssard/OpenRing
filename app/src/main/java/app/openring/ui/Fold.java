package app.openring.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One foldable section, the same everywhere (owner's wish of 17 November): a header row with a title, a one line status and a
 * chevron (down when closed, up when open), and the details under it, closed by default. Two sizes of the same thing: a whole
 * card ({@link #card}, every section of Settings, the Ring, Watch and Phone blocks of #330) and a row inside a card
 * ({@link #inline}: the battery on Today, How it works, Why this line, the note on scores without a ring).
 * <p>
 * The state lives in {@code open[0]}, kept by the screen, so it survives the screen being rebuilt (Find my ring's
 * {@code helpOpen} pattern); nothing is stored. Folding only hides details: every number keeps its explanation inside.
 */
final class Fold {
    /** What goes on the page: a card, or a box inside a card. */
    final LinearLayout view;
    /** The row that opens and closes the section. */
    final LinearLayout head;
    /** The details: shown only while open. */
    final LinearLayout body;
    /** The one line under the title; null when the header has its own content. */
    final TextView status;
    final ImageView chevron;
    private final boolean[] open;
    private final String name;
    private Runnable onChange;

    private Fold(LinearLayout view, LinearLayout head, LinearLayout body, TextView status, ImageView chevron, boolean[] open, String name) {
        this.view = view;
        this.head = head;
        this.body = body;
        this.status = status;
        this.chevron = chevron;
        this.open = open;
        this.name = name;
    }

    /** A card whose header is the title (17 sp) with its status under it; the body is inside the same card. */
    static Fold card(Context c, String title, String status, boolean[] open) {
        LinearLayout card = Ui.card(c);
        LinearLayout words = Ui.vbox(c);
        words.addView(Ui.text(c, title, 17, Ui.TEXT, Ui.MEDIUM));
        TextView st = Ui.text(c, status == null ? "" : status, 13, Ui.TEXT_2, Ui.REGULAR);
        st.setVisibility(status == null || status.isEmpty() ? View.GONE : View.VISIBLE);
        words.addView(st);
        LinearLayout body = Ui.vbox(c);
        body.setPadding(0, Ui.dp(c, 14), 0, 0);
        return build(c, card, words, st, body, 28, Ui.TEXT_2, false, open, title);
    }

    /** A row inside a card: the title (14 sp) and its status after it, a thin line above when {@code divider}. */
    static Fold inline(Context c, String title, String status, boolean[] open, boolean divider) {
        LinearLayout words = Ui.vbox(c);
        words.addView(Ui.text(c, title, 14, Ui.TEXT_2, Ui.MEDIUM));
        TextView st = Ui.text(c, status == null ? "" : status, 12, Ui.TEXT_3, Ui.REGULAR);
        st.setVisibility(status == null || status.isEmpty() ? View.GONE : View.VISIBLE);
        words.addView(st);
        return build(c, Ui.vbox(c), words, st, Ui.vbox(c), 22, Ui.TEXT_3, divider, open, title);
    }

    /** A row inside a card with its own content in the header (the battery lines); {@code name} is read out by TalkBack. */
    static Fold inline(Context c, View headContent, String name, boolean[] open, boolean divider) {
        return build(c, Ui.vbox(c), headContent, null, Ui.vbox(c), 22, Ui.TEXT_3, divider, open, name);
    }

    private static Fold build(Context c, LinearLayout view, View words, TextView status, LinearLayout body, int chevronDp, int chevronColor,
            boolean divider, boolean[] open, String name) {
        if (divider) view.addView(Ui.divider(c));
        LinearLayout head = Ui.hbox(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Ui.dp(c, 48));
        head.addView(words, Ui.weight(1));
        ImageView chev = new ImageView(c);
        chev.setImageResource(app.openring.R.drawable.ic_chevron_right);
        chev.setImageTintList(ColorStateList.valueOf(chevronColor));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(c, chevronDp), Ui.dp(c, chevronDp));
        cl.setMargins(Ui.dp(c, 8), 0, 0, 0);
        head.addView(chev, cl);
        head.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(Ui.TEXT_2, 0.2f)), null, Ui.rounded(0xFFFFFFFF, Ui.dp(c, 10))));
        head.setClickable(true);
        head.setFocusable(true);
        view.addView(head, Ui.matchWrap());
        view.addView(body, Ui.matchWrap());
        final Fold f = new Fold(view, head, body, status, chev, open, name);
        f.apply();
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                f.toggle();
            }
        });
        return f;
    }

    /** Called after each open or close (Settings keeps which sections are open). */
    Fold onChange(Runnable r) {
        onChange = r;
        return this;
    }

    boolean isOpen() {
        return open[0];
    }

    void toggle() {
        show(!open[0]);
    }

    void show(boolean on) {
        open[0] = on;
        apply();
        if (onChange != null) onChange.run();
    }

    private void apply() {
        boolean on = open[0];
        body.setVisibility(on ? View.VISIBLE : View.GONE);
        chevron.setRotation(on ? 270 : 90); // down when closed, up when open
        head.setContentDescription(name + (status != null && status.getVisibility() == View.VISIBLE ? ", " + status.getText() : "")
                + (on ? ", open, tap to close" : ", closed, tap to open"));
    }

    /** A new status line (a sync ended, a setting changed). */
    void setStatus(String s) {
        if (status == null) return;
        status.setText(s == null ? "" : s);
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        apply();
    }

    /** Adds a part of the details below the ones there, with a thin line between them. */
    void add(View section) {
        if (body.getChildCount() > 0) body.addView(Ui.divider(view.getContext()));
        body.addView(section, Ui.matchWrap());
    }

    /** Adds a grey note as in How it works (12 sp, the style of the notes under numbers). */
    void note(String s) {
        if (s == null || s.isEmpty()) return;
        Context c = view.getContext();
        TextView t = Ui.text(c, s, 12, Ui.TEXT_3, Ui.REGULAR);
        t.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 8));
        body.addView(t);
    }

    /** Whether {@code v} is this section or inside it. */
    boolean holds(View v) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == view) return true;
        return false;
    }

    /** One line that never wraps: ends with dots when the screen is narrow (the battery lines, the headers' status). */
    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
