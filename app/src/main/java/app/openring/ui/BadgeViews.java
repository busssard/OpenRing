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

    /** Stores {@code newest} as seen when it is later than what was seen before. Call off the main thread. */
    static void writeSeen(app.openring.data.Db db, LocalDate newest) {
        if (newest == null) return;
        LocalDate before = seen(db);
        if (before == null || newest.isAfter(before)) db.put(SEEN, newest.toString());
    }

    /**
     * Notes {@code newest} as seen in the background, then runs {@code then} on the main thread, so a reload that
     * follows reads the new value (review of #309). One date is stored: opening any badge's day marks every badge up to
     * the newest one shown as seen. Never throws; on a failed write only the New marks stay a little longer.
     */
    static void markSeen(Context c, final LocalDate newest, final Runnable then) {
        final Context ctx = c.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    writeSeen(app.openring.data.Db.get(ctx), newest);
                } catch (Throwable ignored) {
                    // only the New marks stay a little longer
                }
                main.post(then);
            }
        });
    }

    /**
     * The Recent badges card for Today, compact: a chip per badge (name, date, New), two per line, newest first; one line
     * under them with the full text of the chip touched (the newest at first), and "Open that day" for it.
     */
    static View row(final Context c, DayData d, final Pages.Nav nav) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, Explain.BADGES_ROW));
        card.addView(Ui.gap(c, 2));
        card.addView(Ui.text(c, Explain.BADGES_ROW_NOTE, 12, Ui.TEXT_3, Ui.REGULAR));
        final List<Badges.Badge> list = new ArrayList<Badges.Badge>(d.badges);
        java.util.Collections.reverse(list); // newest first
        final LocalDate newest = Badges.newest(list);
        final LocalDate today = LocalDate.now();
        final TextView readout = Ui.text(c, "", 13, Ui.TEXT_2, Ui.REGULAR);
        readout.setMinLines(2);
        final LinearLayout[] chips = new LinearLayout[list.size()];
        final int[] selected = {0};
        LinearLayout line = null;
        for (int i = 0; i < list.size(); i++) {
            final Badges.Badge b = list.get(i);
            if (i % 2 == 0) {
                line = Ui.hbox(c);
                LinearLayout.LayoutParams ll = Ui.matchWrap();
                ll.setMargins(0, Ui.dp(c, 8), 0, 0);
                card.addView(line, ll);
            }
            LinearLayout chip = Ui.vbox(c);
            int p = Ui.dp(c, 8);
            chip.setPadding(Ui.dp(c, 10), p, Ui.dp(c, 10), p);
            chip.addView(Ui.text(c, Badges.NAMES[b.kind], 14, Ui.TEXT, Ui.MEDIUM));
            LinearLayout when = Ui.hbox(c);
            when.addView(Ui.text(c, Explain.badgeWhen(b.date, today), 12, Ui.TEXT_2, Ui.REGULAR));
            if (Badges.isNew(b, d.badgesSeen)) {
                TextView fresh = Ui.text(c, Explain.BADGES_NEW, 12, Ui.GOOD, Ui.MEDIUM);
                fresh.setPadding(Ui.dp(c, 8), 0, 0, 0);
                when.addView(fresh);
            }
            chip.addView(when);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setContentDescription(Badges.NAMES[b.kind] + ", " + Explain.badgeWhen(b.date, today) + ". Shows its numbers below.");
            final int idx = i;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected[0] = idx;
                    select(c, chips, idx, readout, list.get(idx));
                }
            });
            chips[i] = chip;
            LinearLayout.LayoutParams cl = Ui.weight(1);
            cl.setMargins(i % 2 == 0 ? 0 : Ui.dp(c, 4), 0, i % 2 == 0 ? Ui.dp(c, 4) : 0, 0);
            line.addView(chip, cl);
        }
        // an odd count keeps the last chip half wide
        if (list.size() % 2 == 1 && line != null) line.addView(new View(c), Ui.weight(1));
        LinearLayout.LayoutParams rl = Ui.matchWrap();
        rl.setMargins(0, Ui.dp(c, 10), 0, 0);
        card.addView(readout, rl);
        LinearLayout actions = Ui.hbox(c);
        TextView open = Ui.smallButton(c, Explain.BADGES_OPEN, Ui.GOOD);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Badges.Badge b = list.get(selected[0]);
                markSeen(c, newest, new Runnable() {
                    @Override
                    public void run() {
                        nav.go(tab(b.kind));
                        nav.showDay(b.date);
                    }
                });
            }
        });
        TextView all = Ui.smallButton(c, Explain.BADGES_ALL, Ui.TEXT_2);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new Intent(c, BadgesActivity.class));
            }
        });
        LinearLayout.LayoutParams ol = Ui.weight(1);
        ol.setMargins(0, 0, Ui.dp(c, 4), 0);
        actions.addView(open, ol);
        LinearLayout.LayoutParams al = Ui.weight(1);
        al.setMargins(Ui.dp(c, 4), 0, 0, 0);
        actions.addView(all, al);
        LinearLayout.LayoutParams acl = Ui.matchWrap();
        acl.setMargins(0, Ui.dp(c, 8), 0, 0);
        card.addView(actions, acl);
        select(c, chips, 0, readout, list.get(0));
        return card;
    }

    /** Marks chip {@code idx} as touched and shows its badge's full text with the numbers under the chips. */
    static void select(Context c, LinearLayout[] chips, int idx, TextView readout, Badges.Badge b) {
        for (int i = 0; i < chips.length; i++) {
            android.graphics.drawable.GradientDrawable bg = Ui.rounded(Ui.CARD_HI, Ui.dp(c, 12));
            if (i == idx) bg.setStroke(Ui.dp(c, 1.5f), Ui.GOOD);
            chips[i].setBackground(bg);
        }
        readout.setText(Badges.NAMES[b.kind] + ": " + b.text);
    }
}
