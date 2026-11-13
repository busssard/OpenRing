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

    /**
     * The colour of a badge's group (owner's wish of 12 November), the colour of its tab: Sleep, Readiness, Activity, Body
     * (heart), Screen and Period as on their screens, a neutral grey for anything else.
     */
    static int color(int kind) {
        switch (Badges.group(kind)) {
            case Badges.SLEEP:
                return Ui.SLEEP;
            case Badges.READINESS:
                return Ui.READY;
            case Badges.ACTIVITY:
                return Ui.ACTIVE;
            case Badges.BODY:
                return Ui.HEART;
            case Badges.SCREEN:
                return Ui.OXY;
            case Badges.PERIOD:
                return Ui.PERIOD;
            default:
                return Ui.TEXT_2;
        }
    }

    /** How strongly a badge shows its colour: earned in the last 7 days, earlier, or not yet (the Badges page's list). */
    static final int RECENT = 2, EARLIER = 1, NOT_YET = 0;

    /**
     * A badge's surface, the same on Today and the Badges page: the raised card colour with a thin frame in the badge's
     * colour, full for a badge of the last 7 days and paler for older ones. {@code touched}: the one whose numbers are
     * read out under the chips, with a faint wash of its colour and a slightly thicker frame.
     */
    static android.graphics.drawable.GradientDrawable frame(Context c, int kind, int level, boolean touched) {
        int col = color(kind);
        android.graphics.drawable.GradientDrawable bg = Ui.rounded(touched ? Ui.blend(Ui.CARD_HI, col, 0.16f) : Ui.CARD_HI, Ui.dp(c, 12));
        float a = level == RECENT ? 0.9f : level == EARLIER ? 0.45f : 0.2f;
        bg.setStroke(Ui.dp(c, touched ? 2f : 1.5f), touched ? col : Ui.alpha(col, a));
        return bg;
    }

    /** A badge's title line: a small disc and the name in the badge's colour (paler when not earned yet). */
    static LinearLayout title(Context c, int kind, float sp, int level) {
        int col = color(kind);
        LinearLayout line = Ui.hbox(c);
        View disc = new View(c);
        disc.setBackground(Ui.rounded(level == NOT_YET ? Ui.alpha(col, 0.4f) : col, Ui.dp(c, 4)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8));
        dl.setMargins(0, 0, Ui.dp(c, 7), 0);
        line.addView(disc, dl);
        TextView name = Ui.text(c, Badges.NAMES[kind], sp, level == NOT_YET ? Ui.blend(col, Ui.TEXT_2, 0.5f) : col, Ui.MEDIUM);
        line.addView(name, Ui.weight(1));
        return line;
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
            chip.addView(title(c, b.kind, 14, Badges.thisWeek(b.date, today) ? RECENT : EARLIER), Ui.matchWrap());
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
                    select(c, chips, idx, readout, list, today);
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
        select(c, chips, 0, readout, list, today);
        return card;
    }

    /** Marks chip {@code idx} as touched and shows its badge's full text with the numbers under the chips. */
    static void select(Context c, LinearLayout[] chips, int idx, TextView readout, List<Badges.Badge> list, LocalDate today) {
        for (int i = 0; i < chips.length; i++) {
            Badges.Badge b = list.get(i);
            chips[i].setBackground(frame(c, b.kind, Badges.thisWeek(b.date, today) ? RECENT : EARLIER, i == idx));
        }
        Badges.Badge b = list.get(idx);
        readout.setText(Badges.NAMES[b.kind] + ": " + b.text);
    }
}
