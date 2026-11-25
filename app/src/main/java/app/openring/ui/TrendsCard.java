package app.openring.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * The way into Trends (issue #374): one card at the bottom of Sleep, Ready, Activity, Body and Screen, "Your months", which opens
 * Trends at the group of that tab and the month of the shown day. Not on Today, so Today's first screen stays as it is. Sleep's
 * Further analysis has one line of its own ({@link #link}).
 */
final class TrendsCard {
    private TrendsCard() {}

    /** Opens Trends at the group of {@code tab} ({@code Pages.SLEEP} and so on) with {@code d}'s date as the shown day. */
    static void open(Context c, DayData d, int tab) {
        Intent i = new Intent(c, TrendsActivity.class);
        i.putExtra(TrendsActivity.GROUP, app.openring.core.analysis.Trends.groupOfTab(tab));
        if (d != null && d.date != null) i.putExtra(TrendsActivity.DAY, d.date.toString());
        c.startActivity(i);
    }

    /**
     * Adds the card at the end of a tab's page; nothing on Today, nor on Sleep and Ready while they only ask for a device (with
     * only the phone, Activity and Screen still get it: its steps and screen time have trends; code review of #413).
     */
    static void add(final Context c, LinearLayout root, final DayData d, final int tab) {
        if (tab == Pages.TODAY || d == null) return;
        if ((tab == Pages.SLEEP || tab == Pages.READINESS) && PhoneViews.needsDevice(c, d)) return;
        LinearLayout card = Ui.card(c);
        // the title first, so it becomes the tile's header (ui/Tiles: a tap there folds; the card moves by its handle in the edit
        // session, Rearrange tiles); the rest opens Trends
        // a caption as every other tile's header (small, grey, upper case); the key stays "your months"
        card.addView(Ui.caption(c, Explain.TRENDS_CARD_TITLE));
        card.addView(Ui.gap(c, 4));
        LinearLayout row = Ui.hbox(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(c, Explain.TRENDS_CARD, 12, Ui.TEXT_3, Ui.REGULAR), Ui.weight(1));
        ImageView go = new ImageView(c);
        go.setImageResource(app.openring.R.drawable.ic_chevron_right);
        go.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        row.addView(go, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        card.addView(row);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(Explain.TRENDS_CARD_TITLE);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(c, d, tab);
            }
        });
        // folded it still says what it holds (it has no number of its own); its key comes from the title, "your months"
        Tiles.summary(card, Explain.TRENDS_CARD);
        root.addView(card);
    }

    /** "Month by month in Trends", a text button for inside a card (Sleep's Further analysis). */
    static View link(final Context c, final DayData d) {
        android.widget.TextView b = Ui.textButton(c, Explain.TRENDS_LINK, Ui.SLEEP);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(c, d, Pages.SLEEP);
            }
        });
        return b;
    }
}
