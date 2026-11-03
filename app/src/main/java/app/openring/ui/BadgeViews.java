package app.openring.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.analysis.Badges;

/**
 * The Recent badges row on Today (issue #265, phase 1b) and what the badge screens share: which tab shows a badge's
 * night or day, and the date of the newest badge seen (kv {@code badges_seen}, in the database file open now, so demo
 * mode and the person's own data keep their own).
 */
final class BadgeViews {
    private BadgeViews() {}

    /** kv key: the newest badge date already seen; badges after it are marked New. */
    static final String SEEN = "badges_seen";

    /** The tab that shows a badge's numbers: Sleep, Readiness, Screen or Activity. */
    static int tab(int kind) {
        switch (kind) {
            case Badges.BEST_NIGHT:
            case Badges.RESTFUL_WEEK:
                return Pages.SLEEP;
            case Badges.RECOVERED:
                return Pages.READINESS;
            case Badges.PHONE_FREE:
            case Badges.CALM_SCREEN:
                return Pages.SCREEN;
            default:
                return Pages.ACTIVITY;
        }
    }

    /** Reads kv {@link #SEEN}; null when never set or unreadable. Call off the main thread. */
    static LocalDate seen(app.openring.data.Db db) {
        try {
            String s = db.kv(SEEN, null);
            return s == null ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Notes {@code newest} as seen in the background, when it is later than what was seen before. Never throws. */
    static void markSeen(Context c, final LocalDate newest) {
        if (newest == null) return;
        final Context ctx = c.getApplicationContext();
        android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    app.openring.data.Db db = app.openring.data.Db.get(ctx);
                    LocalDate before = seen(db);
                    if (before == null || newest.isAfter(before)) db.put(SEEN, newest.toString());
                } catch (Throwable ignored) {
                    // only the New marks stay a little longer
                }
            }
        });
    }

    /** The Recent badges card for Today: each badge with its date and text, newest first; a tap opens its night or day. */
    static View row(final Context c, DayData d, final Pages.Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, Explain.BADGES_ROW));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, Explain.BADGES_ROW_NOTE, 12, Ui.TEXT_3, Ui.REGULAR));
        List<Badges.Badge> list = new ArrayList<Badges.Badge>(d.badges);
        java.util.Collections.reverse(list); // newest first
        final LocalDate newest = Badges.newest(list);
        LocalDate today = LocalDate.now();
        for (final Badges.Badge b : list) {
            LinearLayout item = Ui.vbox(c);
            int p = Ui.dp(c, 10);
            item.setPadding(p, p, p, p);
            item.setBackground(Ui.rounded(Ui.CARD_HI, Ui.dp(c, 12)));
            LinearLayout top = Ui.hbox(c);
            top.addView(Ui.text(c, Badges.NAMES[b.kind], 15, Ui.TEXT, Ui.MEDIUM), Ui.weight(1));
            if (Badges.isNew(b, d.badgesSeen)) {
                TextView fresh = Ui.text(c, Explain.BADGES_NEW, 12, Ui.GOOD, Ui.MEDIUM);
                fresh.setPadding(0, 0, Ui.dp(c, 8), 0);
                top.addView(fresh);
            }
            top.addView(Ui.text(c, Explain.badgeWhen(b.date, today), 12, Ui.TEXT_2, Ui.REGULAR));
            item.addView(top);
            item.addView(Ui.text(c, b.text, 13, Ui.TEXT_2, Ui.REGULAR));
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(Badges.NAMES[b.kind] + ", " + Explain.badgeWhen(b.date, today) + ". " + b.text + ". Opens that day.");
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    markSeen(c, newest);
                    nav.go(tab(b.kind));
                    nav.showDay(b.date);
                }
            });
            LinearLayout.LayoutParams il = Ui.matchWrap();
            il.setMargins(0, Ui.dp(c, 8), 0, 0);
            card.addView(item, il);
        }
        TextView all = Ui.smallButton(c, Explain.BADGES_ALL, Ui.GOOD);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new Intent(c, BadgesActivity.class));
            }
        });
        LinearLayout.LayoutParams al = Ui.matchWrap();
        al.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(all, al);
        return card;
    }
}
