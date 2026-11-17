package app.openring.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.openring.core.analysis.Badges;
import app.openring.data.BadgeDays;
import app.openring.data.Db;
import app.openring.data.Prefs;

/**
 * The Badges page (issue #265, phase 1b), from Today's Recent badges and from Settings: a month calendar with a dot on
 * each day with a badge (touch reads the day and its badges with their numbers, a tap opens the day), each kind with
 * how often and when last (a tap opens that day) and its rule, a line while there are under 14 nights, and why the
 * screen badges are missing without usage access, with the button to allow it. No totals to beat and no streaks.
 * The whole history is read once, in the background ({@link BadgeDays#all}); nothing is stored but the newest date seen.
 */
public final class BadgesActivity extends Activity {
    private LinearLayout body;
    private boolean demo;
    private YearMonth month;
    private int loads;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);

        LinearLayout top = Ui.hbox(this);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Badges"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        body = Ui.vbox(this);
        root.addView(body, Ui.matchWrap());
        body.addView(Ui.text(this, "Reading your days", 14, Ui.TEXT_2, Ui.REGULAR));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // again on every return: usage access may have been switched on in Android's settings meanwhile
        load();
    }

    /** What the background read hands to the screen. */
    static final class Loaded {
        BadgeDays.History history;
        LocalDate seen, today;
        boolean failed;
    }

    void load() {
        final int mine = ++loads;
        final android.content.Context ctx = getApplicationContext();
        demo = Prefs.of(this).demo();
        new AsyncTask<Void, Void, Loaded>() {
            @Override
            protected Loaded doInBackground(Void... v) {
                Loaded l = new Loaded();
                l.today = LocalDate.now();
                try {
                    Prefs p = Prefs.of(ctx);
                    Db db = Db.get(ctx);
                    // the nights and, without a ring, the days of the watch or the phone, as Today's badges (owner's report of 14 November)
                    l.history = BadgeDays.all(db, p.targetKcal(), p.screenExcluded(), l.today, System.currentTimeMillis() / 1000.0, DayData.shownNow(ctx));
                    l.seen = BadgeViews.seen(db);
                    // opening the page marks every badge as seen; written here, before Today can load again
                    BadgeViews.writeSeen(db, Badges.newest(l.history.badges));
                } catch (Throwable e) {
                    l.failed = true;
                    try {
                        app.openring.ble.RingManager.get(ctx).log("badges: the history could not be read: " + e);
                    } catch (Throwable ignored) {
                        // the page says it failed
                    }
                }
                return l;
            }

            @Override
            protected void onPostExecute(Loaded l) {
                if (mine != loads || isFinishing() || isDestroyed()) return;
                fill(l);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    void fill(Loaded l) {
        body.removeAllViews();
        if (l.failed || l.history == null) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.body(this, Explain.BADGES_FAILED));
            body.addView(card);
            return;
        }
        final LocalDate today = l.today;
        final List<Badges.Badge> all = l.history.badges;

        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.body(this, Explain.BADGES));
        String wait = Explain.badgesWait(l.history.nights);
        if (wait != null) {
            intro.addView(Ui.gap(this, 8));
            intro.addView(Ui.text(this, wait, 13, Ui.ACTIVE, Ui.REGULAR));
        }
        body.addView(intro);

        // without usage access the screen badges cannot come: say why and offer the permission here (asked in context)
        if (!demo && !(app.openring.data.ScreenUse.supported() && app.openring.data.ScreenUse.allowed(this))) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.caption(this, "Screen badges"));
            card.addView(Ui.gap(this, 6));
            boolean old = !app.openring.data.ScreenUse.supported();
            card.addView(Ui.body(this, old ? Explain.BADGES_SCREEN_OLD : Explain.BADGES_SCREEN_ACCESS));
            if (!old) {
                card.addView(Ui.gap(this, 12));
                TextView allow = Ui.button(this, "Allow usage access", Ui.OXY, true);
                allow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        app.openring.data.ScreenUse.openAccessSettings(BadgesActivity.this);
                    }
                });
                card.addView(allow, Ui.matchWrap());
            }
            body.addView(card);
        }

        // ---- the calendar ----
        final Map<LocalDate, List<Badges.Badge>> byDay = new HashMap<LocalDate, List<Badges.Badge>>();
        // the colours of each day's badges, in the order of the kinds (Badges.of lists a date's badges by kind)
        Map<LocalDate, List<Integer>> colors = new HashMap<LocalDate, List<Integer>>();
        for (Badges.Badge b : all) {
            List<Badges.Badge> on = byDay.get(b.date);
            if (on == null) byDay.put(b.date, on = new ArrayList<Badges.Badge>());
            on.add(b);
            List<Integer> cs = colors.get(b.date);
            if (cs == null) colors.put(b.date, cs = new ArrayList<Integer>());
            cs.add(BadgeViews.color(b.kind));
        }
        if (month == null) month = YearMonth.from(today);
        LinearLayout cal = Ui.card(this);
        cal.addView(Ui.caption(this, "Calendar"));
        cal.addView(Ui.gap(this, 6));
        final TextView title = Ui.text(this, monthTitle(month), 15, Ui.TEXT, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        final TextView readout = Ui.text(this, Explain.BADGES_CALENDAR, 13, Ui.TEXT_2, Ui.REGULAR);
        readout.setMinLines(3);
        final BadgeCalendar view = new BadgeCalendar(this, colors, today, month, new BadgeCalendar.Listener() {
            @Override
            public void read(LocalDate day) {
                List<Badges.Badge> on = byDay.get(day);
                readout.setText(Explain.badgeDay(day, on == null ? new ArrayList<Badges.Badge>() : on, today));
            }

            @Override
            public void tap(LocalDate day) {
                if (day.isAfter(today)) return;
                List<Badges.Badge> on = byDay.get(day);
                open(day, on == null || on.isEmpty() ? Pages.TODAY : BadgeViews.tab(on.get(0).kind));
            }
        });
        LinearLayout head = Ui.hbox(this);
        TextView prev = Ui.smallButton(this, "‹", Ui.TEXT_2), next = Ui.smallButton(this, "›", Ui.TEXT_2);
        prev.setContentDescription("Month before");
        next.setContentDescription("Month after");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.minusMonths(1), view, title, readout);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(month.plusMonths(1), view, title, readout);
            }
        });
        head.addView(prev);
        head.addView(title, Ui.weight(1));
        head.addView(next);
        cal.addView(head, Ui.matchWrap());
        cal.addView(Ui.gap(this, 6));
        cal.addView(view, Ui.matchWrap());
        cal.addView(Ui.gap(this, 6));
        cal.addView(legend());
        cal.addView(Ui.gap(this, 8));
        cal.addView(readout);
        body.addView(cal);

        // ---- each kind: how often, when last, the rule ----
        LinearLayout kinds = Ui.card(this);
        kinds.addView(Ui.caption(this, "Each badge"));
        kinds.addView(Ui.gap(this, 4));
        kinds.addView(Ui.text(this, Explain.BADGES_COUNT_NOTE, 12, Ui.TEXT_3, Ui.REGULAR));
        // each kind as a chip of one size in two columns (owner's wish of 17 November), as on Today; touching one shows its
        // rule with the count under the grid, and Open that day opens its newest day
        final String[] counts = new String[Badges.KINDS], rules = new String[Badges.KINDS];
        final LocalDate[] lasts = new LocalDate[Badges.KINDS];
        final int[] levels = new int[Badges.KINDS];
        final LinearLayout[] chips = new LinearLayout[Badges.KINDS];
        final TextView rule = Ui.text(this, "", 13, Ui.TEXT_2, Ui.REGULAR);
        rule.setLineSpacing(0, 1.15f);
        final TextView openDay = Ui.smallButton(this, Explain.BADGES_OPEN, Ui.GOOD);
        final int[] picked = {0};
        EqualGrid grid = new EqualGrid(this, 2, 8);
        for (int k = 0; k < Badges.KINDS; k++) {
            int n = 0;
            LocalDate last = null;
            for (Badges.Badge b : all) {
                if (b.kind != k) continue;
                n++;
                if (last == null || b.date.isAfter(last)) last = b.date;
            }
            counts[k] = Explain.badgeCount(n, last, today);
            rules[k] = Explain.badgeRule(k);
            lasts[k] = last;
            levels[k] = last == null ? BadgeViews.NOT_YET : Badges.thisWeek(last, today) ? BadgeViews.RECENT : BadgeViews.EARLIER;
            LinearLayout item = BadgeViews.chip(this, k, levels[k], counts[k], false);
            item.setContentDescription(Badges.NAMES[k] + ", " + counts[k] + ". Shows its rule below.");
            final int kind = k;
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picked[0] = kind;
                    pick(chips, levels, kind, rule, counts, rules, lasts, openDay);
                }
            });
            chips[k] = item;
            grid.addView(item);
        }
        LinearLayout.LayoutParams gl = Ui.matchWrap();
        gl.setMargins(0, Ui.dp(this, 8), 0, 0);
        kinds.addView(grid, gl);
        LinearLayout.LayoutParams rl = Ui.matchWrap();
        rl.setMargins(0, Ui.dp(this, 10), 0, 0);
        kinds.addView(rule, rl);
        openDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LocalDate at = lasts[picked[0]];
                if (at != null) open(at, BadgeViews.tab(picked[0]));
            }
        });
        LinearLayout.LayoutParams ol = Ui.matchWrap();
        ol.setMargins(0, Ui.dp(this, 8), 0, 0);
        kinds.addView(openDay, ol);
        // the newest earned kind first, else the first one
        int first = 0;
        for (int k = 0; k < Badges.KINDS; k++) if (lasts[k] != null && (lasts[first] == null || lasts[k].isAfter(lasts[first]))) first = k;
        picked[0] = first;
        pick(chips, levels, first, rule, counts, rules, lasts, openDay);
        body.addView(kinds);
    }

    /** Marks the touched kind's chip and shows its count and rule under the grid; Open that day only for a kind earned. */
    void pick(LinearLayout[] chips, int[] levels, int k, TextView rule, String[] counts, String[] rules, LocalDate[] lasts, TextView openDay) {
        for (int i = 0; i < chips.length; i++) chips[i].setBackground(BadgeViews.frame(this, i, levels[i], i == k));
        rule.setText(Badges.NAMES[k] + ": " + (lasts[k] == null ? "not earned yet" : counts[k]) + ". " + rules[k]);
        openDay.setVisibility(lasts[k] == null ? View.GONE : View.VISIBLE);
    }

    /** One line under the calendar: a dot in each colour the badges have, with its tab's name (Sleep, Readiness, ...). */
    View legend() {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER);
        boolean[] done = new boolean[Badges.GROUPS];
        for (int k = 0; k < Badges.KINDS; k++) {
            int g = Badges.group(k);
            if (done[g]) continue;
            done[g] = true;
            View dot = new View(this);
            dot.setBackground(Ui.rounded(BadgeViews.color(k), Ui.dp(this, 3)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6));
            dl.setMargins(row.getChildCount() == 0 ? 0 : Ui.dp(this, 12), 0, Ui.dp(this, 5), 0);
            row.addView(dot, dl);
            row.addView(Ui.text(this, Badges.GROUP_NAMES[g], 12, Ui.TEXT_2, Ui.REGULAR));
        }
        return row;
    }

    void showMonth(YearMonth m, BadgeCalendar view, TextView title, TextView readout) {
        month = m;
        view.month(m);
        title.setText(monthTitle(m));
        readout.setText(Explain.BADGES_CALENDAR);
    }

    static String monthTitle(YearMonth m) {
        return m.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()));
    }

    /** Opens the main screen at {@code day} on {@code tab}; the screen then stays on that date until another is picked. */
    void open(LocalDate day, int tab) {
        startActivity(new Intent(this, MainActivity.class).putExtra("day", day.toString()).putExtra("tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
